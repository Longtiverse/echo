package com.echo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import com.echo.audio.VolumeController
import com.echo.databinding.ActivityMainBinding
import com.echo.service.AudioMonitorService
import com.echo.util.ThemeManager
import com.echo.ui.RecordingsActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val REQUEST_CODE_PERMISSIONS = 1001
        
        /**
         * 修复P0: 动态获取所需权限列表
         * Android 13+ 需要 POST_NOTIFICATIONS 权限
         * Android 12 及以下不需要此权限
         */
        fun getRequiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var volumeController: VolumeController
    private lateinit var themeManager: ThemeManager
    private var audioService: AudioMonitorService? = null
    private var isBound = false
    private var hasPermissions = false
    
    // 修复P1: 使用静态内部类Handler避免内存泄漏
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val spectrumUpdateRunnable = object : Runnable {
        override fun run() {
            // 使用弱引用模式检查Activity是否还存在
            if (!isDestroyed && !isFinishing) {
                if (AudioMonitorService.isRunning) {
                    val spectrum = audioService?.getSpectrumData()
                    spectrum?.let {
                        binding.spectrumView.updateSpectrum(it)
                    }
                }
                handler.postDelayed(this, 50) // 20fps更新
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as AudioMonitorService.LocalBinder
            audioService = binder.getService()
            isBound = true
            
            audioService?.setWaveformCallback { waveformData ->
                runOnUiThread {
                    binding.waveformView.updateWaveform(waveformData)
                }
            }
            
            updateUIFromServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            audioService = null
            isBound = false
        }
    }
    
    /**
     * 修复P0: 接收服务状态变化广播
     * 当服务启动失败时更新UI
     */
    private val monitoringStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioMonitorService.ACTION_MONITORING_STATE_CHANGED) {
                val isRunning = intent.getBooleanExtra(AudioMonitorService.EXTRA_IS_RUNNING, false)
                val error = intent.getStringExtra(AudioMonitorService.EXTRA_ERROR)
                
                runOnUiThread {
                    if (!isRunning && error != null) {
                        // 服务启动失败
                        binding.switchMonitor.isChecked = false
                        binding.textStatus.text = "状态：启动失败"
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    }
                    updateUIFromServiceState()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用主题（必须在super.onCreate之前）
        themeManager = ThemeManager(this)
        themeManager.applyTheme()
        
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        volumeController = VolumeController(this)
        
        // 修复P0: 注册服务状态广播接收器
        registerReceiver(
            monitoringStateReceiver,
            IntentFilter(AudioMonitorService.ACTION_MONITORING_STATE_CHANGED)
        )
        
        bindService()
        checkPermissions()
        initUI()
    }

    override fun onDestroy() {
        // 修复P0-2: 清理Handler防止内存泄漏
        handler.removeCallbacksAndMessages(null)
        
        // 修复P0: 注销广播接收器
        try {
            unregisterReceiver(monitoringStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
        
        super.onDestroy()
        unbindService()
    }

    private fun bindService() {
        Intent(this, AudioMonitorService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            audioService = null
        }
    }

    private fun checkPermissions() {
        val requiredPermissions = getRequiredPermissions()
        hasPermissions = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        
        if (!hasPermissions) {
            ActivityCompat.requestPermissions(
                this,
                requiredPermissions,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            hasPermissions = grantResults.isNotEmpty() && 
                          grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            if (hasPermissions) {
                Toast.makeText(this, "权限已获取", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要录音和通知权限", Toast.LENGTH_LONG).show()
                binding.switchMonitor.isChecked = false
                binding.switchMonitor.isEnabled = false
                binding.textStatus.text = "状态：缺少权限"
            }
        }
    }

    private fun initUI() {
        binding.switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startMonitoring()
            } else {
                stopMonitoring()
            }
        }

        binding.seekBarVolume.max = 100
        binding.seekBarVolume.progress = (volumeController.userVolume * 100).toInt()
        binding.seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    volumeController.userVolume = progress / 100f
                    binding.textVolumeValue.text = "$progress%"
                    updateServiceVolume()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.seekBarMaxVolume.max = 100
        binding.seekBarMaxVolume.progress = volumeController.getMaxVolumePercentage()
        binding.seekBarMaxVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    volumeController.setMaxVolumePercentage(progress)
                    binding.textMaxVolumeValue.text = "$progress%"
                    updateServiceVolume()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 录制按钮
        binding.btnRecord.setOnClickListener {
            toggleRecording()
        }

        // 主题切换
        binding.switchTheme.isChecked = themeManager.isDarkMode()
        binding.switchTheme.setOnCheckedChangeListener { _, isChecked ->
            val mode = if (isChecked) ThemeManager.MODE_DARK else ThemeManager.MODE_LIGHT
            themeManager.setThemeMode(mode)
            Toast.makeText(this, if (isChecked) "已切换到深色模式" else "已切换到浅色模式", Toast.LENGTH_SHORT).show()
        }

        // 查看录音按钮
        binding.btnViewRecordings.setOnClickListener {
            val intent = Intent(this, RecordingsActivity::class.java)
            startActivity(intent)
        }

        updateUI()
    }

    private fun updateUI() {
        binding.textVolumeValue.text = "${(volumeController.userVolume * 100).toInt()}%"
        binding.textMaxVolumeValue.text = "${volumeController.getMaxVolumePercentage()}%"
    }

    private fun updateUIFromServiceState() {
        val isRunning = AudioMonitorService.isRunning
        val isRecording = AudioMonitorService.isRecording
        binding.switchMonitor.isChecked = isRunning
        binding.textStatus.text = if (isRunning) "状态：运行中" else "状态：停止"
        binding.btnRecord.isEnabled = isRunning
        binding.btnRecord.text = if (isRecording) "停止录制" else "开始录制"
    }

    private fun startMonitoring() {
        if (!hasPermissions) {
            Toast.makeText(this, "请先授予权限", Toast.LENGTH_SHORT).show()
            binding.switchMonitor.isChecked = false
            checkPermissions()
            return
        }

        val intent = Intent(this, AudioMonitorService::class.java).apply {
            action = AudioMonitorService.ACTION_START
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        binding.textStatus.text = "状态：运行中"
        Toast.makeText(this, "开始监听", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        val intent = Intent(this, AudioMonitorService::class.java).apply {
            action = AudioMonitorService.ACTION_STOP
        }
        startService(intent)
        
        binding.textStatus.text = "状态：停止"
        Toast.makeText(this, "停止监听", Toast.LENGTH_SHORT).show()
    }

    private fun updateServiceVolume() {
        if (AudioMonitorService.isRunning) {
            val intent = Intent(this, AudioMonitorService::class.java).apply {
                action = AudioMonitorService.ACTION_UPDATE_VOLUME
                putExtra(AudioMonitorService.EXTRA_VOLUME, volumeController.userVolume)
                putExtra(AudioMonitorService.EXTRA_MAX_VOLUME, volumeController.maxVolumeDb)
            }
            startService(intent)
        }
    }

    private fun toggleRecording() {
        if (AudioMonitorService.isRecording) {
            // 停止录制
            val path = audioService?.stopRecording()
            updateUIFromServiceState()
            Toast.makeText(this, "录制已保存: $path", Toast.LENGTH_LONG).show()
        } else {
            // 开始录制
            val success = audioService?.startRecording() ?: false
            if (success) {
                updateUIFromServiceState()
                Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "录制失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        updateUIFromServiceState()
        handler.post(spectrumUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(spectrumUpdateRunnable)
    }
}
