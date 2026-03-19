package com.echo.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echo.app.MainActivity
import com.echo.app.R
import com.echo.app.audio.AudioRouteMode
import com.echo.app.audio.OboeAudioEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ???????????
 *
 * ???
 * - ?????????
 * - ?????? / ??? / ????
 * - ????????????????
 * - ? UI ???????
 */
class AudioMonitoringService : Service() {

    companion object {
        const val TAG = "AudioMonitoringService"

        const val ACTION_START = "com.echo.app.ACTION_START"
        const val ACTION_STOP = "com.echo.app.ACTION_STOP"
        const val ACTION_UPDATE_VOLUME = "com.echo.app.ACTION_UPDATE_VOLUME"
        const val ACTION_UPDATE_SETTINGS = "com.echo.app.ACTION_UPDATE_SETTINGS"
        const val ACTION_STATUS_CHANGED = "com.echo.app.ACTION_STATUS_CHANGED"
        const val ACTION_LOG_UPDATED = "com.echo.app.ACTION_LOG_UPDATED"

        const val EXTRA_VOLUME = "extra_volume"
        const val EXTRA_IS_RUNNING = "extra_is_running"
        const val EXTRA_STATUS_TEXT = "extra_status_text"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_ENGINE_INFO = "extra_engine_info"
        const val EXTRA_LOG_LINE = "extra_log_line"
        const val EXTRA_AUDIO_SOURCE_MODE = "extra_audio_source_mode"
        const val EXTRA_AUDIO_ROUTE_MODE = "extra_audio_route_mode"
        const val EXTRA_AUTO_RESTART = "extra_auto_restart"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "echo_audio_monitoring"
        private const val MAX_RESTART_ATTEMPTS = 3
        private const val RESTART_DELAY_MS = 1500L
        private const val MAX_LOG_LINES = 120
    }

    private data class OutputDeviceSnapshot(
        val labels: List<String>,
        val hasWiredOrUsb: Boolean,
        val hasBluetoothSco: Boolean,
        val hasBluetoothA2dp: Boolean,
        val hasBuiltinEarpiece: Boolean,
        val hasBuiltinSpeaker: Boolean
    ) {
        fun summary(): String = labels.ifEmpty { listOf("?") }.joinToString(" / ")
    }

    private data class EffectiveRoute(
        val summary: String,
        val audioMode: Int,
        val speakerphoneOn: Boolean,
        val bluetoothScoOn: Boolean
    )

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    inner class LocalBinder : Binder() {
        fun getService(): AudioMonitoringService = this@AudioMonitoringService
    }

    private lateinit var audioEngine: OboeAudioEngine
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var restartRunnable: Runnable? = null

    @Volatile
    private var isRunning = false

    private var currentVolume = 70
    private var hasForegroundNotification = false
    private var autoRestartEnabled = true
    private var preferredAudioSourceMode = OboeAudioEngine.AudioSourceMode.AUTO
    private var preferredAudioRouteMode = AudioRouteMode.AUTO
    private var lastEngineInfoText = "???"
    private var lastStatusText = "??"
    private var restartAttempts = 0
    private var effectiveRouteSummary = "???"
    private var outputDevicesSummary = "?"
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneState: Boolean? = null
    private var previousBluetoothScoState: Boolean? = null
    private var hasLoggedBluetoothLatencyWarning = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        audioEngine = OboeAudioEngine(
            applicationContext,
            object : OboeAudioEngine.Listener {
                override fun onLog(message: String) {
                    appendLog(message)
                }

                override fun onEngineReady(info: OboeAudioEngine.EngineInfo) {
                    lastEngineInfoText = info.toDisplayText(this@AudioMonitoringService)
                    sendStatusBroadcast(running = isRunning, statusText = lastStatusText)
                }

                override fun onUnexpectedStop(reason: String) {
                    appendLog(getString(R.string.log_unexpected_stop_format, reason))
                    handleUnexpectedStop(reason)
                }
            }
        )
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        outputDevicesSummary = inspectOutputDevices().summary()

        createNotificationChannel()
        registerAudioDeviceCallback()
        appendLog(getString(R.string.log_service_created))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                applyIntentConfig(intent)
                startMonitoring(currentVolume)
            }

            ACTION_STOP -> {
                stopMonitoring(
                    statusText = getString(R.string.status_stopped),
                    message = getString(R.string.toast_stopped)
                )
                stopSelf()
            }

            ACTION_UPDATE_VOLUME -> {
                val volume = intent.getIntExtra(EXTRA_VOLUME, currentVolume)
                updateVolume(volume)
            }

            ACTION_UPDATE_SETTINGS -> {
                val wasRunning = isRunning
                applyIntentConfig(intent)
                if (wasRunning) {
                    appendLog(getString(R.string.log_settings_updated_restart))
                    restartMonitoring(getString(R.string.toast_settings_applied_restart))
                } else {
                    sendStatusBroadcast(false, lastStatusText)
                }
            }

            null -> {
                sendStatusBroadcast(
                    running = isRunning,
                    statusText = if (isRunning) getString(R.string.status_running) else getString(R.string.status_ready)
                )
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        appendLog(getString(R.string.log_service_destroyed))
        stopMonitoring(statusText = getString(R.string.status_stopped))
        unregisterAudioDeviceCallback()
        audioEngine.release()
        restoreOutputRoute()
        super.onDestroy()
    }

    fun setVolume(percent: Int) {
        currentVolume = percent.coerceIn(0, 100)
        audioEngine.setVolume(currentVolume.toFloat())
        if (hasForegroundNotification) {
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        }
    }

    fun isRunning(): Boolean = isRunning
    fun getCurrentVolume(): Int = currentVolume
    fun getEngineInfoText(): String = lastEngineInfoText
    fun getLastStatusText(): String = lastStatusText
    fun isAutoRestartEnabled(): Boolean = autoRestartEnabled
    fun getPreferredAudioSourceModeKey(): String = preferredAudioSourceMode.key
    fun getPreferredAudioRouteModeKey(): String = preferredAudioRouteMode.key
    fun getEffectiveRouteSummary(): String = effectiveRouteSummary
    fun getOutputDevicesSummary(): String = outputDevicesSummary
    fun getRecentLogs(): List<String> = logLines.toList()

    private fun applyIntentConfig(intent: Intent) {
        currentVolume = intent.getIntExtra(EXTRA_VOLUME, currentVolume).coerceIn(0, 100)
        autoRestartEnabled = intent.getBooleanExtra(EXTRA_AUTO_RESTART, autoRestartEnabled)
        preferredAudioSourceMode = OboeAudioEngine.AudioSourceMode.fromKey(
            intent.getStringExtra(EXTRA_AUDIO_SOURCE_MODE)
        )
        preferredAudioRouteMode = AudioRouteMode.fromKey(
            intent.getStringExtra(EXTRA_AUDIO_ROUTE_MODE)
        )
        audioEngine.updateConfig(
            OboeAudioEngine.RuntimeConfig(preferredAudioSource = preferredAudioSourceMode)
        )
        appendLog(
            getString(
                R.string.log_config_format,
                currentVolume,
                getString(preferredAudioSourceMode.labelResId),
                getString(preferredAudioRouteMode.labelResId),
                getString(if (autoRestartEnabled) R.string.log_on else R.string.log_off)
            )
        )
    }

    private fun startMonitoring(volume: Int) {
        cancelScheduledRestart()

        if (isRunning) {
            updateVolume(volume)
            sendStatusBroadcast(
                true,
                getString(R.string.status_running),
                getString(R.string.toast_already_running)
            )
            return
        }

        currentVolume = volume.coerceIn(0, 100)
        lastStatusText = getString(R.string.status_starting)
        ensureForegroundNotification(statusText = lastStatusText)

        requestAudioFocus()
        applyOutputRoute(deviceChanged = false)

        if (!audioEngine.initialize()) {
            handleStartFailure(getString(R.string.toast_start_failed_init))
            return
        }

        audioEngine.setVolume(currentVolume.toFloat())
        if (!audioEngine.start()) {
            handleStartFailure(getString(R.string.toast_start_failed_open))
            return
        }

        acquireWakeLock()
        isRunning = true
        restartAttempts = 0
        lastStatusText = getString(R.string.status_running)
        lastEngineInfoText = audioEngine.getCurrentEngineInfo()?.toDisplayText(this) ?: lastEngineInfoText
        notificationManager.notify(NOTIFICATION_ID, createNotification())
        sendStatusBroadcast(true, lastStatusText, getString(R.string.toast_started))
        appendLog(getString(R.string.log_monitor_started))
        Log.i(TAG, "Monitoring started")
    }

    private fun restartMonitoring(message: String? = null) {
        val volume = currentVolume
        stopMonitoring(statusText = getString(R.string.status_restarting))
        startMonitoring(volume)
        if (!message.isNullOrBlank()) {
            sendStatusBroadcast(isRunning, lastStatusText, message)
        }
    }

    private fun stopMonitoring(statusText: String = getString(R.string.status_stopped), message: String? = null) {
        cancelScheduledRestart()

        if (isRunning || hasForegroundNotification) {
            appendLog(getString(R.string.log_monitor_stopped))
        }

        isRunning = false
        lastStatusText = statusText
        audioEngine.stop()
        abandonAudioFocus()
        releaseWakeLock()
        restoreOutputRoute()

        if (hasForegroundNotification) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            hasForegroundNotification = false
        }

        sendStatusBroadcast(false, statusText, message)
    }

    private fun handleStartFailure(message: String) {
        appendLog(message)
        isRunning = false
        lastStatusText = getString(R.string.status_failed)
        lastEngineInfoText = getString(R.string.engine_info_idle)
        audioEngine.release()
        abandonAudioFocus()
        releaseWakeLock()
        restoreOutputRoute()

        if (hasForegroundNotification) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            hasForegroundNotification = false
        }

        sendStatusBroadcast(false, lastStatusText, message)
    }

    private fun handleUnexpectedStop(reason: String) {
        isRunning = false
        lastStatusText = getString(R.string.status_failed)
        abandonAudioFocus()
        releaseWakeLock()
        restoreOutputRoute()

        if (hasForegroundNotification) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            hasForegroundNotification = false
        }

        if (autoRestartEnabled && restartAttempts < MAX_RESTART_ATTEMPTS) {
            restartAttempts += 1
            val attempt = restartAttempts
            lastStatusText = getString(R.string.status_auto_recovering_format, attempt, MAX_RESTART_ATTEMPTS)
            sendStatusBroadcast(false, lastStatusText, getString(R.string.toast_auto_recovering))
            appendLog(getString(R.string.log_schedule_recovery_format, attempt))
            restartRunnable = Runnable {
                appendLog(getString(R.string.log_execute_recovery_format, attempt))
                startMonitoring(currentVolume)
            }.also { mainHandler.postDelayed(it, RESTART_DELAY_MS) }
        } else {
            sendStatusBroadcast(
                false,
                lastStatusText,
                getString(R.string.toast_unexpected_stop_format, reason)
            )
        }
    }

    private fun cancelScheduledRestart() {
        restartRunnable?.let(mainHandler::removeCallbacks)
        restartRunnable = null
    }

    private fun updateVolume(volume: Int) {
        setVolume(volume)
        val status = if (isRunning) lastStatusText else getString(R.string.status_ready)
        sendStatusBroadcast(isRunning, status)
    }

    private fun ensureForegroundNotification(statusText: String) {
        if (!hasForegroundNotification) {
            startForeground(NOTIFICATION_ID, createNotification(statusText))
            hasForegroundNotification = true
        }
    }

    private fun createNotification(statusOverride: String? = null): Notification {
        val stopIntent = Intent(this, AudioMonitoringService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = statusOverride ?: if (isRunning) getString(R.string.status_running) else getString(R.string.status_ready)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                getString(
                    R.string.notification_content_format,
                    statusText,
                    getString(preferredAudioSourceMode.labelResId),
                    getString(preferredAudioRouteMode.labelResId),
                    currentVolume
                )
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentPendingIntent)
            .setOngoing(isRunning)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_action_stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Echo::AudioMonitoringWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            appendLog(getString(R.string.log_wakelock_acquired))
        } catch (e: Throwable) {
            appendLog(getString(R.string.log_wakelock_failed_format, e.message ?: "unknown"))
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            try {
                if (it.isHeld) {
                    it.release()
                    appendLog(getString(R.string.log_wakelock_released))
                }
            } catch (_: Throwable) {
            }
        }
        wakeLock = null
    }

    private fun requestAudioFocus(): Boolean {
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .setAcceptsDelayedFocusGain(false)
            .build()

        val granted = audioFocusRequest?.let {
            audioManager.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } ?: false

        if (!granted) {
            appendLog(getString(R.string.log_audio_focus_not_granted))
        }
        return granted
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        audioFocusRequest = null
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                appendLog(getString(R.string.log_audio_focus_gained))
                if (isRunning && !audioEngine.isRunning()) {
                    audioEngine.start()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                appendLog(getString(R.string.log_audio_focus_lost_format, focusChange))
                if (isRunning) {
                    stopMonitoring(
                        statusText = getString(R.string.status_paused),
                        message = getString(R.string.toast_focus_lost_stopped)
                    )
                }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            super.onAudioDevicesAdded(addedDevices)
            if (addedDevices.isNotEmpty()) {
                appendLog(getString(R.string.log_audio_devices_added_format, addedDevices.joinToString(" / ") { describeDevice(it) }))
            }
            handleDeviceTopologyChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            super.onAudioDevicesRemoved(removedDevices)
            if (removedDevices.isNotEmpty()) {
                appendLog(getString(R.string.log_audio_devices_removed_format, removedDevices.joinToString(" / ") { describeDevice(it) }))
            }

            val headsetLikeRemoved = removedDevices.any(::isHeadsetLikeDevice)
            handleDeviceTopologyChanged(
                shouldStopForSafety = headsetLikeRemoved && shouldStopToPreventFeedbackAfterRemoval()
            )
        }
    }

    private fun registerAudioDeviceCallback() {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun unregisterAudioDeviceCallback() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    private fun handleDeviceTopologyChanged(shouldStopForSafety: Boolean = false) {
        val snapshot = inspectOutputDevices()
        outputDevicesSummary = snapshot.summary()

        if (shouldStopForSafety && isRunning) {
            appendLog(getString(R.string.log_headset_removed))
            stopMonitoring(
                statusText = getString(R.string.status_stopped),
                message = getString(R.string.toast_headset_removed_stopped)
            )
            return
        }

        if (isRunning) {
            applyOutputRoute(deviceChanged = true)
        }
    }

    private fun shouldStopToPreventFeedbackAfterRemoval(): Boolean {
        if (preferredAudioRouteMode == AudioRouteMode.SPEAKER) {
            return false
        }
        val snapshot = inspectOutputDevices()
        return !snapshot.hasWiredOrUsb && !snapshot.hasBluetoothSco && !snapshot.hasBluetoothA2dp
    }

    private fun inspectOutputDevices(): OutputDeviceSnapshot {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
        return OutputDeviceSnapshot(
            labels = devices.map(::describeDevice).distinct(),
            hasWiredOrUsb = devices.any(::isWiredOrUsbDevice),
            hasBluetoothSco = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO },
            hasBluetoothA2dp = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP },
            hasBuiltinEarpiece = devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE },
            hasBuiltinSpeaker = devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        )
    }

    private fun applyOutputRoute(deviceChanged: Boolean) {
        try {
            if (previousAudioMode == null) {
                previousAudioMode = audioManager.mode
                @Suppress("DEPRECATION")
                run {
                    previousSpeakerphoneState = audioManager.isSpeakerphoneOn
                    previousBluetoothScoState = audioManager.isBluetoothScoOn
                }
            }

            val snapshot = inspectOutputDevices()
            outputDevicesSummary = snapshot.summary()
            val route = resolveEffectiveRoute(snapshot)

            @Suppress("DEPRECATION")
            run {
                if (!route.bluetoothScoOn) {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                }
                audioManager.mode = route.audioMode
                audioManager.isSpeakerphoneOn = route.speakerphoneOn
                if (route.bluetoothScoOn) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
            }

            audioManager.isMicrophoneMute = false
            effectiveRouteSummary = route.summary

            val routeMessageRes = if (deviceChanged) R.string.log_route_reapplied_format else R.string.log_route_applied_format
            appendLog(getString(routeMessageRes, effectiveRouteSummary))
            appendLog(getString(R.string.log_route_device_snapshot_format, outputDevicesSummary))

            if (snapshot.hasBluetoothA2dp) {
                if (!hasLoggedBluetoothLatencyWarning) {
                    appendLog(getString(R.string.log_bluetooth_high_latency_warning))
                    hasLoggedBluetoothLatencyWarning = true
                }
            } else {
                hasLoggedBluetoothLatencyWarning = false
            }
        } catch (e: Throwable) {
            appendLog(getString(R.string.log_route_apply_failed_format, e.message ?: "unknown"))
        }
    }

    private fun resolveEffectiveRoute(snapshot: OutputDeviceSnapshot): EffectiveRoute {
        return when (preferredAudioRouteMode) {
            AudioRouteMode.AUTO -> {
                when {
                    snapshot.hasWiredOrUsb -> EffectiveRoute(
                        summary = getString(R.string.route_summary_auto_wired),
                        audioMode = AudioManager.MODE_NORMAL,
                        speakerphoneOn = false,
                        bluetoothScoOn = false
                    )

                    snapshot.hasBluetoothSco -> EffectiveRoute(
                        summary = getString(R.string.route_summary_auto_bt_sco),
                        audioMode = AudioManager.MODE_IN_COMMUNICATION,
                        speakerphoneOn = false,
                        bluetoothScoOn = true
                    )

                    snapshot.hasBluetoothA2dp -> EffectiveRoute(
                        summary = getString(R.string.route_summary_auto_bt_a2dp),
                        audioMode = AudioManager.MODE_NORMAL,
                        speakerphoneOn = false,
                        bluetoothScoOn = false
                    )

                    else -> EffectiveRoute(
                        summary = getString(R.string.route_summary_auto_default),
                        audioMode = AudioManager.MODE_NORMAL,
                        speakerphoneOn = false,
                        bluetoothScoOn = false
                    )
                }
            }

            AudioRouteMode.SPEAKER -> EffectiveRoute(
                summary = getString(R.string.route_summary_speaker),
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                speakerphoneOn = true,
                bluetoothScoOn = false
            )

            AudioRouteMode.EARPIECE -> {
                when {
                    snapshot.hasBuiltinEarpiece -> EffectiveRoute(
                        summary = getString(R.string.route_summary_earpiece),
                        audioMode = AudioManager.MODE_IN_COMMUNICATION,
                        speakerphoneOn = false,
                        bluetoothScoOn = false
                    )

                    snapshot.hasWiredOrUsb -> EffectiveRoute(
                        summary = getString(R.string.route_summary_earpiece_fallback_wired),
                        audioMode = AudioManager.MODE_NORMAL,
                        speakerphoneOn = false,
                        bluetoothScoOn = false
                    )

                    snapshot.hasBluetoothSco -> EffectiveRoute(
                        summary = getString(R.string.route_summary_auto_bt_sco),
                        audioMode = AudioManager.MODE_IN_COMMUNICATION,
                        speakerphoneOn = false,
                        bluetoothScoOn = true
                    )

                    else -> EffectiveRoute(
                        summary = getString(R.string.route_summary_earpiece),
                        audioMode = AudioManager.MODE_IN_COMMUNICATION,
                        speakerphoneOn = false,
                        bluetoothScoOn = false
                    )
                }
            }
        }
    }

    private fun restoreOutputRoute() {
        try {
            previousAudioMode?.let { audioManager.mode = it }
            @Suppress("DEPRECATION")
            run {
                previousSpeakerphoneState?.let { audioManager.isSpeakerphoneOn = it }
                when (previousBluetoothScoState) {
                    true -> {
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                    }

                    false -> {
                        audioManager.isBluetoothScoOn = false
                        audioManager.stopBluetoothSco()
                    }

                    null -> Unit
                }
            }
        } catch (_: Throwable) {
        } finally {
            previousAudioMode = null
            previousSpeakerphoneState = null
            previousBluetoothScoState = null
            effectiveRouteSummary = getString(R.string.route_summary_idle)
        }
    }

    private fun sendStatusBroadcast(
        running: Boolean,
        statusText: String,
        message: String? = null
    ) {
        lastStatusText = statusText
        lastEngineInfoText = audioEngine.getCurrentEngineInfo()?.toDisplayText(this) ?: lastEngineInfoText

        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            `package` = packageName
            putExtra(EXTRA_IS_RUNNING, running)
            putExtra(EXTRA_STATUS_TEXT, statusText)
            putExtra(EXTRA_VOLUME, currentVolume)
            putExtra(EXTRA_AUTO_RESTART, autoRestartEnabled)
            putExtra(EXTRA_AUDIO_SOURCE_MODE, preferredAudioSourceMode.key)
            putExtra(EXTRA_AUDIO_ROUTE_MODE, preferredAudioRouteMode.key)
            putExtra(EXTRA_ENGINE_INFO, lastEngineInfoText)
            if (!message.isNullOrBlank()) {
                putExtra(EXTRA_MESSAGE, message)
            }
        }
        sendBroadcast(intent)
    }

    private fun appendLog(message: String) {
        val line = "[${timeFormatter.format(Date())}] $message"
        Log.d(TAG, line)
        if (logLines.size >= MAX_LOG_LINES) {
            logLines.removeFirst()
        }
        logLines.addLast(line)

        val intent = Intent(ACTION_LOG_UPDATED).apply {
            `package` = packageName
            putExtra(EXTRA_LOG_LINE, line)
        }
        sendBroadcast(intent)
    }

    private fun describeDevice(device: AudioDeviceInfo): String {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.productName?.toString()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val typeName = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "???"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "??"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "????"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "????"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB ????"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB ????"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "????"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "????"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "??????"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "??????"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            else -> "??${device.type}"
        }
        return if (name.isNullOrBlank()) typeName else "$typeName($name)"
    }

    private fun isHeadsetLikeDevice(device: AudioDeviceInfo): Boolean {
        return isWiredOrUsbDevice(device) ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun isWiredOrUsbDevice(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> true
            else -> false
        }
    }
}
