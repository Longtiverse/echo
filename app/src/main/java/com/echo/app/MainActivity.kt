package com.echo.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.echo.app.databinding.ActivityMainBinding
import com.echo.app.permission.PermissionManager
import com.echo.app.service.AudioMonitoringService

/**
 * Phase 3/4/5: 完整功能主Activity
 *
 * 功能:
 * - 权限管理 (Phase 1)
 * - 音量控制Slider (Phase 3)
 * - 前台服务绑定/控制 (Phase 4)
 * - 电池优化白名单引导 (Phase 4)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionManager: PermissionManager
    private var hasPermissions = false

    // 服务绑定
    private var audioService: AudioMonitoringService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as AudioMonitoringService.LocalBinder
            audioService = binder.getService()
            isBound = true
            updateUIFromServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            audioService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionManager = PermissionManager(this)

        checkPermissions()
        initUI()
        bindToService()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindFromService()
    }

    private fun bindToService() {
        Intent(this, AudioMonitoringService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindFromService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun checkPermissions() {
        hasPermissions = permissionManager.hasAllPermissions()

        if (!hasPermissions) {
            permissionManager.requestRuntimePermissions()
        } else {
            // 检查电池优化白名单
            if (!permissionManager.isIgnoringBatteryOptimizations()) {
                showBatteryOptimizationDialog()
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        // 可以显示一个Dialog解释为什么需要白名单
        Log.d(TAG, "Not in battery optimization whitelist")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        permissionManager.handlePermissionResult(
            requestCode = requestCode,
            permissions = permissions as Array<String>,
            grantResults = grantResults,
            onAllGranted = {
                hasPermissions = true
                Toast.makeText(this, "权限已获取", Toast.LENGTH_SHORT).show()
                updateStatus("就绪")
            },
            onDenied = { deniedPermissions ->
                hasPermissions = false
                val message = "缺少权限: ${deniedPermissions.joinToString(", ")}"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                updateStatus("缺少权限")
            }
        )
    }

    private fun initUI() {
        // 启动按钮
        binding.btnStart.setOnClickListener {
            if (hasPermissions) {
                startMonitoring()
            } else {
                Toast.makeText(this, "请先授予权限", Toast.LENGTH_SHORT).show()
                checkPermissions()
            }
        }

        // 停止按钮
        binding.btnStop.setOnClickListener {
            stopMonitoring()
        }

        // 音量Slider (Phase 3)
        binding.seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateVolume(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 电池优化白名单按钮 (Phase 4)
        binding.btnBatteryOptimization.setOnClickListener {
            if (!permissionManager.isIgnoringBatteryOptimizations()) {
                permissionManager.requestBatteryOptimizationExemption()
            } else {
                Toast.makeText(this, "已在白名单中", Toast.LENGTH_SHORT).show()
            }
        }

        updateStatus(if (hasPermissions) "就绪" else "等待权限")
        updateVolumeDisplay(70)
    }

    private fun updateVolume(progress: Int) {
        val percent = progress.coerceIn(0, 100)
        audioService?.setVolume(percent)
        updateVolumeDisplay(percent)
    }

    private fun updateVolumeDisplay(percent: Int) {
        binding.textVolumeValue.text = "$percent%"
        binding.seekBarVolume.progress = percent
    }

    private fun startMonitoring() {
        Log.d(TAG, "Starting monitoring service")

        // 启动前台服务
        val intent = Intent(this, AudioMonitoringService::class.java).apply {
            action = AudioMonitoringService.ACTION_START
        }

        // 获取当前音量设置
        val volumePercent = binding.seekBarVolume.progress
        intent.putExtra(AudioMonitoringService.EXTRA_VOLUME, volumePercent)

        startForegroundService(intent)

        updateStatus("运行中")
        Toast.makeText(this, "开始监听", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        Log.d(TAG, "Stopping monitoring service")

        val intent = Intent(this, AudioMonitoringService::class.java).apply {
            action = AudioMonitoringService.ACTION_STOP
        }
        startService(intent)

        updateStatus("已停止")
        Toast.makeText(this, "停止监听", Toast.LENGTH_SHORT).show()
    }

    private fun updateUIFromServiceState() {
        val isRunning = audioService?.isRunning() ?: false
        updateStatus(if (isRunning) "运行中" else "就绪")
    }

    private fun updateStatus(status: String) {
        binding.textStatus.text = "状态: $status"
    }
}
