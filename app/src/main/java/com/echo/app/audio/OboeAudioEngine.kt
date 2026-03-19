package com.echo.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import com.echo.app.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * ???????????????
 *
 * ?????????????????????
 * ?? AudioRecord + AudioTrack ???????????
 */
class OboeAudioEngine(
    private val context: Context,
    private val listener: Listener? = null
) {

    interface Listener {
        fun onLog(message: String)
        fun onEngineReady(info: EngineInfo)
        fun onUnexpectedStop(reason: String)
    }

    enum class AudioSourceMode(
        val key: String,
        val labelResId: Int,
        val source: Int?
    ) {
        AUTO("auto", R.string.audio_source_auto, null),
        VOICE_RECOGNITION(
            "voice_recognition",
            R.string.audio_source_voice_recognition,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        ),
        MIC("mic", R.string.audio_source_mic, MediaRecorder.AudioSource.MIC),
        CAMCORDER("camcorder", R.string.audio_source_camcorder, MediaRecorder.AudioSource.CAMCORDER),
        DEFAULT("default", R.string.audio_source_default, MediaRecorder.AudioSource.DEFAULT);

        companion object {
            fun fromKey(value: String?): AudioSourceMode {
                return entries.firstOrNull { it.key == value } ?: AUTO
            }
        }
    }

    data class RuntimeConfig(
        val preferredAudioSource: AudioSourceMode = AudioSourceMode.AUTO
    )

    data class EngineInfo(
        val sampleRate: Int,
        val bufferSizeInBytes: Int,
        val framesPerChunk: Int,
        val trackBufferSizeInFrames: Int,
        val audioSourceMode: AudioSourceMode,
        val acousticEchoCancelerEnabled: Boolean,
        val noiseSuppressorEnabled: Boolean
    ) {
        fun toDisplayText(context: Context): String {
            val effects = buildList {
                if (acousticEchoCancelerEnabled) add("AEC")
                if (noiseSuppressorEnabled) add("NS")
            }.ifEmpty { listOf(context.getString(R.string.log_no_effects)) }

            return "${sampleRate}Hz | ${context.getString(audioSourceMode.labelResId)} | chunk=${framesPerChunk} | track=${trackBufferSizeInFrames}f | ${effects.joinToString("+")}"
        }
    }

    companion object {
        private const val TAG = "OboeAudioEngine"
        private const val BYTES_PER_PCM16_FRAME_MONO = 2
        private const val DEFAULT_VOLUME_PERCENT = 70
        private const val DEFAULT_SAMPLE_RATE = 48_000
        private const val FALLBACK_SAMPLE_RATE = 44_100
        private const val SECOND_FALLBACK_SAMPLE_RATE = 16_000
        private const val MIN_CHUNK_FRAMES = 128
        private const val MAX_CHUNK_FRAMES = 256
    }

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isRunning = false

    @Volatile
    private var currentVolumePercent = DEFAULT_VOLUME_PERCENT

    private var currentConfig = RuntimeConfig()
    private var currentEngineInfo: EngineInfo? = null
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var bufferSizeInBytes = 0
    private var chunkSizeInFrames = MIN_CHUNK_FRAMES
    private var trackBufferSizeInFrames = MIN_CHUNK_FRAMES * 2

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var loopThread: Thread? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    fun updateConfig(config: RuntimeConfig) {
        currentConfig = config
        log("Config updated: source=${context.getString(config.preferredAudioSource.labelResId)}")
    }

    fun initialize(config: RuntimeConfig = currentConfig): Boolean {
        currentConfig = config
        if (isInitialized) {
            return true
        }

        releaseAudioPipeline()

        val candidateSampleRates = buildCandidateSampleRates()
        val candidateSources = buildCandidateSources(config.preferredAudioSource)

        for (sourceMode in candidateSources) {
            for (candidateRate in candidateSampleRates) {
                if (tryCreateAudioPipeline(candidateRate, sourceMode)) {
                    isInitialized = true
                    currentEngineInfo?.let {
                        log("Audio pipeline ready: ${it.toDisplayText(context)}")
                        listener?.onEngineReady(it)
                    }
                    return true
                }
            }
        }

        log(
            "Audio pipeline init failed: rates=$candidateSampleRates, sources=${
                candidateSources.joinToString { context.getString(it.labelResId) }
            }"
        )
        return false
    }

    fun start(): Boolean {
        if (isRunning) {
            return true
        }

        if (!isInitialized && !initialize()) {
            return false
        }

        val record = audioRecord ?: run {
            log("AudioRecord is missing")
            return false
        }
        val track = audioTrack ?: run {
            log("AudioTrack is missing")
            return false
        }

        return try {
            track.play()
            record.startRecording()

            when {
                record.recordingState != AudioRecord.RECORDSTATE_RECORDING -> {
                    log("AudioRecord did not enter recording state")
                    stopInternal(releasePipeline = true)
                    false
                }

                track.playState != AudioTrack.PLAYSTATE_PLAYING -> {
                    log("AudioTrack did not enter playback state")
                    stopInternal(releasePipeline = true)
                    false
                }

                else -> {
                    isRunning = true
                    loopThread = Thread(::audioLoop, "EchoAudioLoop").apply {
                        isDaemon = true
                        start()
                    }
                    log("Audio loop started")
                    true
                }
            }
        } catch (e: Throwable) {
            log("Failed to start audio loop: ${e.message}")
            stopInternal(releasePipeline = true)
            false
        }
    }

    fun stop() {
        stopInternal(releasePipeline = true)
    }

    fun release() {
        stopInternal(releasePipeline = true)
    }

    fun isRunning(): Boolean = isRunning

    fun getCurrentEngineInfo(): EngineInfo? = currentEngineInfo

    fun setVolume(percent: Float) {
        currentVolumePercent = percent.roundToInt().coerceIn(0, 100)
    }

    fun getVolume(): Float = currentVolumePercent / 100f

    fun getVolumePercent(): Float = currentVolumePercent.toFloat()

    private fun stopInternal(releasePipeline: Boolean) {
        isRunning = false

        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (_: Throwable) {
        }

        loopThread?.interrupt()
        try {
            loopThread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        loopThread = null

        if (releasePipeline) {
            releaseAudioPipeline()
            isInitialized = false
            currentEngineInfo = null
        }
    }

    private fun buildCandidateSampleRates(): List<Int> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val deviceRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()

        return listOfNotNull(
            DEFAULT_SAMPLE_RATE,
            deviceRate,
            FALLBACK_SAMPLE_RATE,
            SECOND_FALLBACK_SAMPLE_RATE
        ).distinct()
    }

    private fun buildCandidateSources(preferred: AudioSourceMode): List<AudioSourceMode> {
        return if (preferred == AudioSourceMode.AUTO) {
            listOf(
                AudioSourceMode.VOICE_RECOGNITION,
                AudioSourceMode.MIC,
                AudioSourceMode.CAMCORDER,
                AudioSourceMode.DEFAULT
            )
        } else {
            listOf(preferred)
        }
    }

    private fun tryCreateAudioPipeline(candidateSampleRate: Int, sourceMode: AudioSourceMode): Boolean {
        val minRecordBuffer = AudioRecord.getMinBufferSize(
            candidateSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minTrackBuffer = AudioTrack.getMinBufferSize(
            candidateSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minRecordBuffer <= 0 || minTrackBuffer <= 0) {
            log("Invalid min buffer sizes: ${candidateSampleRate}Hz, record=$minRecordBuffer, track=$minTrackBuffer")
            return false
        }

        val framesPerBuffer = estimateFramesPerBuffer(candidateSampleRate)
        val preferredChunkFrames = framesPerBuffer.coerceIn(MIN_CHUNK_FRAMES, MAX_CHUNK_FRAMES)
        val desiredTrackBufferFrames = maxOf(preferredChunkFrames * 2, framesPerBuffer)
        val desiredTrackBufferBytes = desiredTrackBufferFrames * BYTES_PER_PCM16_FRAME_MONO
        val targetBufferSize = maxOf(minRecordBuffer, minTrackBuffer, desiredTrackBufferBytes)

        val record = buildAudioRecord(candidateSampleRate, targetBufferSize, sourceMode) ?: return false
        val track = buildAudioTrack(candidateSampleRate, targetBufferSize, desiredTrackBufferFrames) ?: run {
            record.release()
            return false
        }

        releaseAudioPipeline()
        audioRecord = record
        audioTrack = track
        sampleRate = candidateSampleRate
        bufferSizeInBytes = targetBufferSize
        chunkSizeInFrames = preferredChunkFrames
        trackBufferSizeInFrames = track.bufferSizeInFrames.takeIf { it > 0 } ?: desiredTrackBufferFrames
        val effects = attachAudioEffects(record.audioSessionId)
        setVolume(currentVolumePercent.toFloat())

        log(
            "Candidate accepted: ${candidateSampleRate}Hz / ${context.getString(sourceMode.labelResId)} / " +
                "recordMin=$minRecordBuffer / trackMin=$minTrackBuffer / chunk=$chunkSizeInFrames / " +
                "trackFrames=$trackBufferSizeInFrames"
        )

        currentEngineInfo = EngineInfo(
            sampleRate = sampleRate,
            bufferSizeInBytes = bufferSizeInBytes,
            framesPerChunk = chunkSizeInFrames,
            trackBufferSizeInFrames = trackBufferSizeInFrames,
            audioSourceMode = sourceMode,
            acousticEchoCancelerEnabled = effects.first,
            noiseSuppressorEnabled = effects.second
        )
        return true
    }

    private fun estimateFramesPerBuffer(candidateSampleRate: Int): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val propertyFrames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

        return propertyFrames ?: (candidateSampleRate / 200).coerceAtLeast(MIN_CHUNK_FRAMES)
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(
        sampleRate: Int,
        bufferSizeInBytes: Int,
        sourceMode: AudioSourceMode
    ): AudioRecord? {
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val record = AudioRecord.Builder()
                .setAudioSource(sourceMode.source ?: MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                log("AudioRecord init failed: ${sampleRate}Hz / ${context.getString(sourceMode.labelResId)}")
                record.release()
                null
            } else {
                record
            }
        } catch (e: Throwable) {
            log("Create AudioRecord failed: ${sampleRate}Hz / ${context.getString(sourceMode.labelResId)} / ${e.message}")
            null
        }
    }

    private fun buildAudioTrack(
        sampleRate: Int,
        bufferSizeInBytes: Int,
        desiredTrackBufferFrames: Int
    ): AudioTrack? {
        return try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .build()

            val requestedTrackFrames = desiredTrackBufferFrames.coerceAtLeast(MIN_CHUNK_FRAMES * 2)
            val resizedTrackFrames = track.setBufferSizeInFrames(requestedTrackFrames)
            if (resizedTrackFrames > 0) {
                log("AudioTrack buffer resized: requested=$requestedTrackFrames, actual=$resizedTrackFrames")
            }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                log("AudioTrack init failed: ${sampleRate}Hz")
                track.release()
                null
            } else {
                track
            }
        } catch (e: Throwable) {
            log("Create AudioTrack failed: ${sampleRate}Hz / ${e.message}")
            null
        }
    }

    private fun attachAudioEffects(audioSessionId: Int): Pair<Boolean, Boolean> {
        releaseAudioEffects()
        var aecEnabled = false
        var nsEnabled = false

        if (AcousticEchoCanceler.isAvailable()) {
            acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                enabled = true
                aecEnabled = enabled
            }
        }

        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                enabled = true
                nsEnabled = enabled
            }
        }

        return aecEnabled to nsEnabled
    }

    private fun releaseAudioEffects() {
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
    }

    private fun releaseAudioPipeline() {
        releaseAudioEffects()

        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null

        try {
            audioTrack?.release()
        } catch (_: Throwable) {
        }
        audioTrack = null
    }

    private fun audioLoop() {
        val record = audioRecord ?: return
        val track = audioTrack ?: return
        val buffer = ShortArray(chunkSizeInFrames)
        var failureReason: String? = null

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Throwable) {
            log("Failed to raise audio thread priority: ${e.message}")
        }

        while (isRunning && !Thread.currentThread().isInterrupted) {
            val read = try {
                record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (e: Throwable) {
                failureReason = "Read failed: ${e.message}"
                break
            }

            if (read <= 0) {
                if (isRunning) {
                    failureReason = "AudioRecord returned $read"
                    break
                }
                continue
            }

            applyVolumeAndLimiter(buffer, read)

            val written = try {
                track.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING)
            } catch (e: Throwable) {
                failureReason = "Write failed: ${e.message}"
                break
            }

            if (written < 0) {
                failureReason = "AudioTrack returned $written"
                break
            }
        }

        val shouldNotifyUnexpected = isRunning && !failureReason.isNullOrBlank()
        isRunning = false
        log("Audio loop stopped${failureReason?.let { ": $it" } ?: ""}")
        if (shouldNotifyUnexpected) {
            listener?.onUnexpectedStop(failureReason ?: "Unknown error")
        }
    }

    private fun applyVolumeAndLimiter(buffer: ShortArray, count: Int) {
        val gain = currentVolumePercent.coerceIn(0, 100) / 100f
        for (index in 0 until count) {
            val normalized = buffer[index] / 32768f
            val scaled = normalized * gain
            val limited = softClip(scaled)
            val pcm = (limited * 32767f).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[index] = pcm.toShort()
        }
    }

    private fun softClip(sample: Float): Float {
        val threshold = 0.85f
        val absolute = abs(sample)
        if (absolute <= threshold) {
            return sample
        }

        val sign = if (sample >= 0f) 1f else -1f
        val compressed = threshold + 0.15f * tanh((absolute - threshold) / 0.15f)
        return sign * compressed.coerceAtMost(1f)
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        listener?.onLog(message)
    }
}
