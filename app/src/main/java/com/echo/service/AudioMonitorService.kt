package com.echo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echo.MainActivity
import com.echo.R
import com.echo.audio.AudioRecorder
import com.echo.audio.OboeAudioEngine

class AudioMonitorService : Service() {

    companion object {
        const val TAG = "AudioMonitorService"
        const val CHANNEL_ID = "echo_audio_channel"
        const val NOTIFICATION_ID = 1
        
        const val ACTION_START = "com.echo.ACTION_START"
        const val ACTION_STOP = "com.echo.ACTION_STOP"
        const val ACTION_UPDATE_VOLUME = "com.echo.ACTION_UPDATE_VOLUME"
        const val ACTION_START_RECORDING = "com.echo.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.echo.ACTION_STOP_RECORDING"
        const val ACTION_VOLUME_UP = "com.echo.ACTION_VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.echo.ACTION_VOLUME_DOWN"
        const val ACTION_MONITORING_STATE_CHANGED = "com.echo.ACTION_MONITORING_STATE_CHANGED"
        const val EXTRA_VOLUME = "extra_volume"
        const val EXTRA_MAX_VOLUME = "extra_max_volume"
        const val EXTRA_IS_RUNNING = "extra_is_running"
        const val EXTRA_ERROR = "extra_error"
        
        @Volatile
        var isRunning = false
            private set
        
        @Volatile
        var isRecording = false
            private set
    }

    private var audioEngine: OboeAudioEngine? = null
    private var audioRecorder: AudioRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 修复P1: 添加@Volatile确保多线程可见性
    @Volatile
    private var currentVolume = 1.0f
    @Volatile
    private var maxVolumeDb = -3.0f
    @Volatile
    private var waveformCallback: ((FloatArray) -> Unit)? = null
    
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): AudioMonitorService = this@AudioMonitorService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        registerHeadsetReceiver()
        acquireWakeLock()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
            ACTION_UPDATE_VOLUME -> {
                val volume = intent.getFloatExtra(EXTRA_VOLUME, currentVolume)
                val maxVolume = intent.getFloatExtra(EXTRA_MAX_VOLUME, maxVolumeDb)
                updateVolume(volume, maxVolume)
            }
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_VOLUME_UP -> adjustVolume(0.1f)
            ACTION_VOLUME_DOWN -> adjustVolume(-0.1f)
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (isRunning) {
            Log.d(TAG, "Already running")
            return
        }

        Log.d(TAG, "Starting audio monitoring...")
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 初始化音频引擎
        audioEngine = OboeAudioEngine().apply {
            setWaveformCallback { waveformData ->
                waveformCallback?.invoke(waveformData)
            }
        }
        
        val success = audioEngine?.initialize() ?: false
        if (success) {
            audioEngine?.setVolume(currentVolume)
            audioEngine?.setMaxOutputLevel(maxVolumeDb)
            audioEngine?.start()
            isRunning = true
            Log.d(TAG, "Audio monitoring started successfully")
            updateNotification("正在监听环境音")
            
            // 发送启动成功广播
            sendBroadcast(Intent(ACTION_MONITORING_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_RUNNING, true)
            })
        } else {
            Log.e(TAG, "Failed to initialize audio engine")
            // 修复P0: 初始化失败时清理前台状态
            stopForeground(STOP_FOREGROUND_REMOVE)
            isRunning = false
            
            // 发送启动失败广播
            sendBroadcast(Intent(ACTION_MONITORING_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_RUNNING, false)
                putExtra(EXTRA_ERROR, "音频引擎初始化失败")
            })
            
            stopSelf()
        }
    }

    private fun stopMonitoring() {
        if (!isRunning) return
        
        Log.d(TAG, "Stopping audio monitoring...")
        isRunning = false
        
        // 修复P0-1: 停止监听时同时停止录音，防止文件损坏
        if (isRecording) {
            stopRecording()
        }
        
        audioEngine?.stop()
        audioEngine?.release()
        audioEngine = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Audio monitoring stopped")
    }

    private fun updateVolume(volume: Float, maxVolume: Float) {
        currentVolume = volume
        maxVolumeDb = maxVolume
        if (isRunning) {
            audioEngine?.setVolume(volume)
            audioEngine?.setMaxOutputLevel(maxVolume)
            updateNotification("正在监听环境音")
        }
    }

    fun setWaveformCallback(callback: (FloatArray) -> Unit) {
        waveformCallback = callback
        audioEngine?.setWaveformCallback(callback)
    }

    fun getSpectrumData(): FloatArray? {
        return audioEngine?.getSpectrumData()
    }

    private fun adjustVolume(delta: Float) {
        val newVolume = (currentVolume + delta).coerceIn(0f, 1f)
        if (newVolume != currentVolume) {
            currentVolume = newVolume
            if (isRunning) {
                audioEngine?.setVolume(currentVolume)
                updateNotification("正在监听环境音")
            }
        }
    }

    /**
     * 修复P0: Oboe和AudioRecorder不能同时使用麦克风
     * 录音时暂停Oboe监听，录音完成后再恢复
     */
    fun startRecording(): Boolean {
        if (!isRunning) {
            Log.w(TAG, "Cannot record when not monitoring")
            return false
        }
        
        // 修复P0: 暂停Oboe监听，释放麦克风给录音使用
        Log.d(TAG, "Pausing monitoring for recording...")
        audioEngine?.stop()
        
        if (audioRecorder == null) {
            audioRecorder = AudioRecorder(this)
        }
        
        val success = audioRecorder?.startRecording() ?: false
        if (success) {
            isRecording = true
            updateNotification("正在录制")
            Log.d(TAG, "Recording started (monitoring paused)")
        } else {
            // 录音失败，恢复监听
            Log.e(TAG, "Failed to start recording, resuming monitoring")
            audioEngine?.start()
        }
        return success
    }

    fun stopRecording(): String? {
        val path = audioRecorder?.stopRecording()
        isRecording = false
        
        // 修复P0: 录音完成，恢复Oboe监听
        Log.d(TAG, "Resuming monitoring after recording...")
        audioEngine?.start()
        
        updateNotification("正在监听环境音")
        Log.d(TAG, "Recording stopped: $path")
        return path
    }

    fun isRecording(): Boolean = isRecording

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved - 应用被移除，清理资源")
        // 修复P0-3: 确保在任务被移除时释放所有资源
        stopMonitoring()
        releaseWakeLock()
        stopSelf()
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        // 修复P0: 确保录音资源被释放
        if (isRecording) {
            audioRecorder?.stopRecording()
        }
        stopMonitoring()
        unregisterHeadsetReceiver()
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Echo::AudioMonitorWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "音频监听"
            val descriptionText = "实时环境音监听服务"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setSound(null, null)
                enableVibration(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String = "点击打开应用"): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AudioMonitorService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val volumeUpIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AudioMonitorService::class.java).apply {
                action = ACTION_VOLUME_UP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val volumeDownIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AudioMonitorService::class.java).apply {
                action = ACTION_VOLUME_DOWN
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val volumeText = "音量: ${String.format("%.0f dB", 20 * kotlin.math.log10(currentVolume))}"
        val fullContent = "$contentText | $volumeText"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Echo - 环境音监听")
            .setContentText(fullContent)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Vol-", volumeDownIntent)
            .addAction(0, "Vol+", volumeUpIntent)
            .addAction(R.drawable.ic_stop, "停止", stopIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioManager.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    when (state) {
                        0 -> Log.d(TAG, "Headset unplugged")
                        1 -> Log.d(TAG, "Headset plugged")
                    }
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    Log.d(TAG, "Audio becoming noisy")
                }
            }
        }
    }

    private fun registerHeadsetReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        registerReceiver(headsetReceiver, filter)
    }

    private fun unregisterHeadsetReceiver() {
        try {
            unregisterReceiver(headsetReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }
}
