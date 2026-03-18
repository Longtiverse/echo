package com.echo.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echo.app.MainActivity
import com.echo.app.R
import com.echo.app.audio.OboeAudioEngine

/**
 * Phase 4/5: 音频监听前台服务
 *
 * 功能:
 * - START_STICKY 保活 (Phase 4)
 * - 前台通知与停止按钮 (Phase 4)
 * - PARTIAL_WAKE_LOCK 息屏保活 (Phase 4)
 * - 音频焦点管理 (Phase 5)
 * - 耳机断开监听 - 自动停止防啸叫 (Phase 5)
 */
class AudioMonitoringService : Service() {

    companion object {
        const val TAG = "AudioMonitoringService"

        // Actions
        const val ACTION_START = "com.echo.app.ACTION_START"
        const val ACTION_STOP = "com.echo.app.ACTION_STOP"
        const val ACTION_UPDATE_VOLUME = "com.echo.app.ACTION_UPDATE_VOLUME"

        // Extras
        const val EXTRA_VOLUME = "extra_volume"

        // Notification
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "echo_audio_monitoring"
    }

    // Binder for Activity binding
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): AudioMonitoringService = this@AudioMonitoringService
    }

    // 组件
    private lateinit var audioEngine: OboeAudioEngine
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    // 状态
    private var isRunning = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentVolume = 70

    // ========== Service生命周期 ==========

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        audioEngine = OboeAudioEngine()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        registerAudioDeviceCallback()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                val volume = intent.getIntExtra(EXTRA_VOLUME, 70)
                startMonitoring(volume)
            }
            ACTION_STOP -> {
                stopMonitoring()
            }
            ACTION_UPDATE_VOLUME -> {
                val volume = intent.getIntExtra(EXTRA_VOLUME, currentVolume)
                updateVolume(volume)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopMonitoring()
        unregisterAudioDeviceCallback()
        releaseWakeLock()
        audioEngine.release()
    }

    // ========== Phase 4: 核心功能 ==========

    private fun startMonitoring(volume: Int) {
        if (isRunning) {
            Log.d(TAG, "Already running")
            return
        }

        Log.d(TAG, "Starting monitoring with volume: $volume")
        currentVolume = volume

        // 请求音频焦点 (Phase 5)
        if (!requestAudioFocus()) {
            Log.w(TAG, "Failed to get audio focus")
        }

        // 初始化音频引擎
        if (!audioEngine.initialize()) {
            Log.e(TAG, "Failed to initialize audio engine")
            return
        }

        // 设置音量
        audioEngine.setVolume(volume.toFloat())

        // 启动音频
        audioEngine.start()

        // 获取WakeLock (Phase 4)
        acquireWakeLock()

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())

        isRunning = true
        Log.d(TAG, "Monitoring started")
    }

    private fun stopMonitoring() {
        if (!isRunning) {
            return
        }

        Log.d(TAG, "Stopping monitoring")

        audioEngine.stop()
        abandonAudioFocus()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)

        isRunning = false
        Log.d(TAG, "Monitoring stopped")
    }

    fun setVolume(percent: Int) {
        currentVolume = percent.coerceIn(0, 100)
        if (isRunning) {
            audioEngine.setVolume(currentVolume.toFloat())
        }
    }

    fun isRunning(): Boolean = isRunning

    private fun updateVolume(volume: Int) {
        setVolume(volume)
    }

    // ========== Phase 4: 通知通道 ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频监听服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "实时环境音监听前台服务"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // 停止按钮的PendingIntent
        val stopIntent = Intent(this, AudioMonitoringService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // 点击通知打开MainActivity
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Echo - 环境音监听")
            .setContentText("正在监听中 | 音量: ${currentVolume}%")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "停止监听", stopPendingIntent)
            .build()
    }

    // ========== Phase 4: WakeLock ==========

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Echo::AudioMonitoringWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // 10分钟, 实际会持续更新
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    // ========== Phase 5: 音频焦点管理 ==========

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest?.let {
                audioManager.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } ?: false
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                if (isRunning) {
                    audioEngine.start()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost")
                if (isRunning) {
                    stopMonitoring()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost transient")
                if (isRunning) {
                    audioEngine.stop()
                }
            }
        }
    }

    // ========== Phase 5: 音频设备回调 (耳机断开保护) ==========

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            super.onAudioDevicesRemoved(removedDevices)

            val wasHeadsetRemoved = removedDevices.any { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }

            if (wasHeadsetRemoved && isRunning) {
                Log.w(TAG, "Headset removed! Stopping monitoring to prevent feedback")
                stopMonitoring()

                // 发送广播通知Activity
                val intent = Intent("com.echo.app.HEADSET_REMOVED")
                sendBroadcast(intent)
            }
        }
    }

    private fun registerAudioDeviceCallback() {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun unregisterAudioDeviceCallback() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }
}
