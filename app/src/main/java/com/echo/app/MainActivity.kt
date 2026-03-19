package com.echo.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.echo.app.audio.AudioRouteMode
import com.echo.app.audio.OboeAudioEngine
import com.echo.app.databinding.ActivityMainBinding
import com.echo.app.permission.PermissionManager
import com.echo.app.service.AudioMonitoringService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ????????????????????/????????????
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "echo_monitor_prefs"
        private const val PREF_VOLUME = "pref_volume"
        private const val PREF_AUTO_RESTART = "pref_auto_restart"
        private const val PREF_AUDIO_SOURCE = "pref_audio_source"
        private const val PREF_AUDIO_ROUTE = "pref_audio_route"
        private const val MAX_LOG_LINES = 120
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionManager: PermissionManager

    private var hasPermissions = false
    private var audioService: AudioMonitoringService? = null
    private var isBound = false
    private var isReceiverRegistered = false
    private var isInitializingControls = false
    private val logBuffer = ArrayDeque<String>()

    private val audioSourceModes = listOf(
        OboeAudioEngine.AudioSourceMode.AUTO,
        OboeAudioEngine.AudioSourceMode.VOICE_RECOGNITION,
        OboeAudioEngine.AudioSourceMode.MIC,
        OboeAudioEngine.AudioSourceMode.CAMCORDER,
        OboeAudioEngine.AudioSourceMode.DEFAULT
    )

    private val audioRouteModes = listOf(
        AudioRouteMode.AUTO,
        AudioRouteMode.SPEAKER,
        AudioRouteMode.EARPIECE
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? AudioMonitoringService.LocalBinder ?: return
            audioService = binder.getService()
            isBound = true
            syncFromServiceSnapshot()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isBound = false
            updateUIFromServiceState()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioMonitoringService.ACTION_STATUS_CHANGED -> handleStatusBroadcast(intent)
                AudioMonitoringService.ACTION_LOG_UPDATED -> handleLogBroadcast(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionManager = PermissionManager(this)

        initUI()
        loadSavedSettings()
        checkPermissions()
        bindToService()
    }

    override fun onStart() {
        super.onStart()
        registerServiceReceivers()
    }

    override fun onStop() {
        unregisterServiceReceivers()
        super.onStop()
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

    private fun registerServiceReceivers() {
        if (isReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(AudioMonitoringService.ACTION_STATUS_CHANGED)
            addAction(AudioMonitoringService.ACTION_LOG_UPDATED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        isReceiverRegistered = true
    }

    private fun unregisterServiceReceivers() {
        if (!isReceiverRegistered) return
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: IllegalArgumentException) {
        }
        isReceiverRegistered = false
    }

    private fun initUI() {
        setupAudioSourceSpinner()
        setupAudioRouteSpinner()

        binding.btnStart.setOnClickListener {
            if (!hasPermissions) {
                Toast.makeText(this, getString(R.string.toast_request_permissions), Toast.LENGTH_SHORT).show()
                checkPermissions()
                return@setOnClickListener
            }
            startMonitoring()
        }

        binding.btnStop.setOnClickListener {
            stopMonitoring()
        }

        binding.seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateVolume(progress)
                    persistSettings()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.switchAutoRestart.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingControls) {
                persistSettings()
                pushSettingsToService(restartIfRunning = true)
            }
        }

        binding.spinnerAudioSource.onItemSelectedListener = SimpleItemSelectedListener { position ->
            if (!isInitializingControls && position in audioSourceModes.indices) {
                persistSettings()
                pushSettingsToService(restartIfRunning = true)
            }
        }

        binding.spinnerAudioRoute.onItemSelectedListener = SimpleItemSelectedListener { position ->
            if (!isInitializingControls && position in audioRouteModes.indices) {
                persistSettings()
                pushSettingsToService(restartIfRunning = true)
            }
        }

        binding.btnBatteryOptimization.setOnClickListener {
            if (!permissionManager.isIgnoringBatteryOptimizations()) {
                permissionManager.requestBatteryOptimizationExemption()
            } else {
                Toast.makeText(this, getString(R.string.toast_whitelist_already), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnExportDiagnostics.setOnClickListener {
            exportDiagnostics()
        }

        binding.btnClearLogs.setOnClickListener {
            logBuffer.clear()
            renderLogs()
        }

        updateStatus(getString(R.string.status_preparing))
        binding.textEngineInfo.text = getString(R.string.engine_info_format, getString(R.string.engine_info_idle))
        updateVolumeDisplay(70)
        binding.switchAutoRestart.text = getString(R.string.auto_restart_label)
        renderLogs()
    }

    private fun setupAudioSourceSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            audioSourceModes.map { getString(it.labelResId) }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAudioSource.adapter = adapter
    }

    private fun setupAudioRouteSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            audioRouteModes.map { getString(it.labelResId) }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAudioRoute.adapter = adapter
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val volume = prefs.getInt(PREF_VOLUME, 70)
        val autoRestart = prefs.getBoolean(PREF_AUTO_RESTART, true)
        val sourceKey = prefs.getString(PREF_AUDIO_SOURCE, OboeAudioEngine.AudioSourceMode.AUTO.key)
        val routeKey = prefs.getString(PREF_AUDIO_ROUTE, AudioRouteMode.AUTO.key)
        val sourceIndex = audioSourceModes.indexOfFirst { it.key == sourceKey }.coerceAtLeast(0)
        val routeIndex = audioRouteModes.indexOfFirst { it.key == routeKey }.coerceAtLeast(0)

        isInitializingControls = true
        updateVolumeDisplay(volume)
        binding.switchAutoRestart.isChecked = autoRestart
        binding.spinnerAudioSource.setSelection(sourceIndex, false)
        binding.spinnerAudioRoute.setSelection(routeIndex, false)
        isInitializingControls = false
    }

    private fun persistSettings() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(PREF_VOLUME, binding.seekBarVolume.progress)
            .putBoolean(PREF_AUTO_RESTART, binding.switchAutoRestart.isChecked)
            .putString(PREF_AUDIO_SOURCE, getSelectedAudioSourceMode().key)
            .putString(PREF_AUDIO_ROUTE, getSelectedAudioRouteMode().key)
            .apply()
    }

    private fun checkPermissions() {
        hasPermissions = permissionManager.hasAllPermissions()
        if (!hasPermissions) {
            permissionManager.requestRuntimePermissions()
            updateStatus(getString(R.string.status_waiting_permission))
        } else {
            updateUIFromServiceState()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        permissionManager.handlePermissionResult(
            requestCode = requestCode,
            permissions = permissions.map { it }.toTypedArray(),
            grantResults = grantResults,
            onAllGranted = {
                hasPermissions = true
                updateStatus(getString(R.string.status_ready))
                appendLog(getString(R.string.log_permission_granted))
                Toast.makeText(this, getString(R.string.toast_permission_granted), Toast.LENGTH_SHORT).show()
            },
            onDenied = { deniedPermissions ->
                hasPermissions = false
                updateStatus(getString(R.string.status_waiting_permission))
                appendLog(getString(R.string.log_permission_denied_format, deniedPermissions.joinToString(", ")))
                Toast.makeText(
                    this,
                    getString(R.string.toast_missing_permissions_format, deniedPermissions.joinToString(", ")),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun handleStatusBroadcast(intent: Intent) {
        val running = intent.getBooleanExtra(AudioMonitoringService.EXTRA_IS_RUNNING, false)
        val statusText = intent.getStringExtra(AudioMonitoringService.EXTRA_STATUS_TEXT)
        val message = intent.getStringExtra(AudioMonitoringService.EXTRA_MESSAGE)
        val volume = intent.getIntExtra(AudioMonitoringService.EXTRA_VOLUME, binding.seekBarVolume.progress)
        val engineInfo = intent.getStringExtra(AudioMonitoringService.EXTRA_ENGINE_INFO).orEmpty()
        val autoRestart = intent.getBooleanExtra(AudioMonitoringService.EXTRA_AUTO_RESTART, binding.switchAutoRestart.isChecked)
        val sourceKey = intent.getStringExtra(AudioMonitoringService.EXTRA_AUDIO_SOURCE_MODE)
        val routeKey = intent.getStringExtra(AudioMonitoringService.EXTRA_AUDIO_ROUTE_MODE)

        isInitializingControls = true
        updateVolumeDisplay(volume)
        binding.switchAutoRestart.isChecked = autoRestart
        binding.spinnerAudioSource.setSelection(audioSourceModes.indexOfFirst { it.key == sourceKey }.coerceAtLeast(0), false)
        binding.spinnerAudioRoute.setSelection(audioRouteModes.indexOfFirst { it.key == routeKey }.coerceAtLeast(0), false)
        isInitializingControls = false

        binding.textEngineInfo.text = getString(
            R.string.engine_info_format,
            if (engineInfo.isBlank()) getString(R.string.engine_info_idle) else engineInfo
        )
        updateStatus(
            statusText ?: if (running) {
                getString(R.string.status_running)
            } else if (hasPermissions) {
                getString(R.string.status_ready)
            } else {
                getString(R.string.status_waiting_permission)
            }
        )

        if (!message.isNullOrBlank()) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleLogBroadcast(intent: Intent) {
        intent.getStringExtra(AudioMonitoringService.EXTRA_LOG_LINE)?.let { appendLog(it, alreadyFormatted = true) }
    }

    private fun syncFromServiceSnapshot() {
        val service = audioService ?: return
        isInitializingControls = true
        updateVolumeDisplay(service.getCurrentVolume())
        binding.switchAutoRestart.isChecked = service.isAutoRestartEnabled()
        binding.spinnerAudioSource.setSelection(
            audioSourceModes.indexOfFirst { it.key == service.getPreferredAudioSourceModeKey() }.coerceAtLeast(0),
            false
        )
        binding.spinnerAudioRoute.setSelection(
            audioRouteModes.indexOfFirst { it.key == service.getPreferredAudioRouteModeKey() }.coerceAtLeast(0),
            false
        )
        isInitializingControls = false

        binding.textEngineInfo.text = getString(R.string.engine_info_format, service.getEngineInfoText())
        logBuffer.clear()
        service.getRecentLogs().forEach { appendLog(it, alreadyFormatted = true) }
        updateStatus(service.getLastStatusText())
        updateUIFromServiceState()
    }

    private fun pushSettingsToService(restartIfRunning: Boolean) {
        val action = if (restartIfRunning) AudioMonitoringService.ACTION_UPDATE_SETTINGS else AudioMonitoringService.ACTION_START
        val intent = Intent(this, AudioMonitoringService::class.java).apply {
            this.action = action
            putExtra(AudioMonitoringService.EXTRA_VOLUME, binding.seekBarVolume.progress)
            putExtra(AudioMonitoringService.EXTRA_AUTO_RESTART, binding.switchAutoRestart.isChecked)
            putExtra(AudioMonitoringService.EXTRA_AUDIO_SOURCE_MODE, getSelectedAudioSourceMode().key)
            putExtra(AudioMonitoringService.EXTRA_AUDIO_ROUTE_MODE, getSelectedAudioRouteMode().key)
        }
        startService(intent)
    }

    private fun updateVolume(progress: Int) {
        val percent = progress.coerceIn(0, 100)
        updateVolumeDisplay(percent)

        audioService?.setVolume(percent)

        val intent = Intent(this, AudioMonitoringService::class.java).apply {
            action = AudioMonitoringService.ACTION_UPDATE_VOLUME
            putExtra(AudioMonitoringService.EXTRA_VOLUME, percent)
        }
        startService(intent)
    }

    private fun updateVolumeDisplay(percent: Int) {
        binding.textVolumeValue.text = getString(R.string.volume_value_format, percent)
        if (binding.seekBarVolume.progress != percent) {
            binding.seekBarVolume.progress = percent
        }
    }

    private fun startMonitoring() {
        persistSettings()
        val intent = Intent(this, AudioMonitoringService::class.java).apply {
            action = AudioMonitoringService.ACTION_START
            putExtra(AudioMonitoringService.EXTRA_VOLUME, binding.seekBarVolume.progress)
            putExtra(AudioMonitoringService.EXTRA_AUTO_RESTART, binding.switchAutoRestart.isChecked)
            putExtra(AudioMonitoringService.EXTRA_AUDIO_SOURCE_MODE, getSelectedAudioSourceMode().key)
            putExtra(AudioMonitoringService.EXTRA_AUDIO_ROUTE_MODE, getSelectedAudioRouteMode().key)
        }

        startForegroundService(intent)
        updateStatus(getString(R.string.status_starting))
        appendLog(getString(R.string.log_request_start))
    }

    private fun stopMonitoring() {
        val intent = Intent(this, AudioMonitoringService::class.java).apply {
            action = AudioMonitoringService.ACTION_STOP
        }
        startService(intent)
        updateStatus(getString(R.string.status_stopping))
        appendLog(getString(R.string.log_request_stop))
    }

    private fun updateUIFromServiceState() {
        val running = audioService?.isRunning() ?: false
        updateStatus(
            when {
                running -> getString(R.string.status_running)
                hasPermissions -> getString(R.string.status_ready)
                else -> getString(R.string.status_waiting_permission)
            }
        )
    }

    private fun updateStatus(status: String) {
        binding.textStatus.text = getString(R.string.status_format, status)
    }

    private fun exportDiagnostics() {
        try {
            val exportDir = File(cacheDir, "diagnostics").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(exportDir, "echo_diagnostics_${timestamp}.txt")
            file.writeText(buildDiagnosticsReport(), Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            shareDiagnosticsFile(uri)
            appendLog(getString(R.string.log_export_success_format, file.absolutePath))
            Toast.makeText(this, getString(R.string.toast_diagnostics_exported), Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            appendLog(getString(R.string.log_export_failed_format, e.message ?: "unknown"))
            Toast.makeText(
                this,
                getString(R.string.toast_diagnostics_export_failed_format, e.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun buildDiagnosticsReport(): String {
        val service = audioService
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val selectedSource = getSelectedAudioSourceMode()
        val selectedRoute = getSelectedAudioRouteMode()

        return buildString {
            appendLine("Echo Diagnostics")
            appendLine("Generated: $now")
            appendLine()
            appendLine("=== App ===")
            appendLine("Package: $packageName")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("=== Device ===")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("=== Runtime ===")
            appendLine("Has permissions: $hasPermissions")
            appendLine("Service bound: $isBound")
            appendLine("Running: ${service?.isRunning() ?: false}")
            appendLine("Status: ${service?.getLastStatusText() ?: binding.textStatus.text}")
            appendLine("Volume: ${binding.seekBarVolume.progress}%")
            appendLine("Auto restart: ${binding.switchAutoRestart.isChecked}")
            appendLine("Selected source: ${getString(selectedSource.labelResId)} (${selectedSource.key})")
            appendLine("Selected route: ${getString(selectedRoute.labelResId)} (${selectedRoute.key})")
            appendLine("Effective route: ${service?.getEffectiveRouteSummary() ?: getString(R.string.route_summary_idle)}")
            appendLine("Output devices: ${service?.getOutputDevicesSummary() ?: getString(R.string.route_summary_idle)}")
            appendLine("Engine info: ${service?.getEngineInfoText() ?: binding.textEngineInfo.text}")
            appendLine()
            appendLine("=== Recent logs ===")
            val logs = service?.getRecentLogs() ?: logBuffer.toList()
            if (logs.isEmpty()) {
                appendLine(getString(R.string.logs_empty))
            } else {
                logs.forEach(::appendLine)
            }
        }
    }

    private fun shareDiagnosticsFile(uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Echo diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("echo_diagnostics", uri)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_diagnostics_chooser)))
    }

    private fun getSelectedAudioSourceMode(): OboeAudioEngine.AudioSourceMode {
        return audioSourceModes.getOrElse(binding.spinnerAudioSource.selectedItemPosition) {
            OboeAudioEngine.AudioSourceMode.AUTO
        }
    }

    private fun getSelectedAudioRouteMode(): AudioRouteMode {
        return audioRouteModes.getOrElse(binding.spinnerAudioRoute.selectedItemPosition) {
            AudioRouteMode.AUTO
        }
    }

    private fun appendLog(message: String, alreadyFormatted: Boolean = false) {
        val line = if (alreadyFormatted) message else "[UI] $message"
        if (logBuffer.size >= MAX_LOG_LINES) {
            logBuffer.removeFirst()
        }
        logBuffer.addLast(line)
        renderLogs()
    }

    private fun renderLogs() {
        binding.textLogs.text = if (logBuffer.isEmpty()) {
            getString(R.string.logs_empty)
        } else {
            logBuffer.joinToString("\n")
        }
    }
}
