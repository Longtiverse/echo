package com.echo.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频引擎 - 使用AudioRecord和AudioTrack实现低延迟音频
 */
class AudioEngine {

    companion object {
        const val TAG = "AudioEngine"
        
        // 音频参数
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_FACTOR = 2
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val isRunning = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var currentVolume = 1.0f
    private var maxVolumeDb = -3.0f
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 示波器回调
    private var waveformCallback: ((FloatArray) -> Unit)? = null
    
    // 缓冲区
    private val bufferSize: Int by lazy {
        val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        minSize * BUFFER_SIZE_FACTOR
    }

    /**
     * 设置示波器回调
     */
    fun setWaveformCallback(callback: (FloatArray) -> Unit) {
        waveformCallback = callback
    }

    /**
     * 初始化音频引擎
     */
    fun initialize(): Boolean {
        return try {
            Log.d(TAG, "Initializing audio engine...")
            
            // 初始化录音
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return false
            }
            
            // 初始化播放
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AUDIO_FORMAT)
                .setChannelMask(CHANNEL_CONFIG_OUT)
                .build()
            
            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                0
            )
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed")
                return false
            }
            
            Log.d(TAG, "Audio engine initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio engine", e)
            false
        }
    }

    /**
     * 开始音频捕获和播放
     */
    fun start() {
        if (isRunning.get()) return
        
        Log.d(TAG, "Starting audio engine...")
        isRunning.set(true)
        
        audioRecord?.startRecording()
        audioTrack?.play()
        
        recordThread = Thread {
            val buffer = ShortArray(bufferSize / 2)
            val waveformBuffer = FloatArray(100) // 用于示波器的采样点
            var waveformIndex = 0
            
            while (isRunning.get()) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (readSize > 0) {
                    // 应用音量和限幅
                    applyVolumeAndLimit(buffer, readSize)
                    
                    // 收集示波器数据（每100个样本取一个）
                    for (i in 0 until readSize step (readSize / 100 + 1)) {
                        if (waveformIndex < waveformBuffer.size) {
                            waveformBuffer[waveformIndex++] = buffer[i] / 32768.0f
                        }
                    }
                    
                    if (waveformIndex >= waveformBuffer.size) {
                        waveformCallback?.invoke(waveformBuffer.copyOf())
                        waveformIndex = 0
                    }
                    
                    // 播放
                    audioTrack?.write(buffer, 0, readSize)
                }
            }
        }.apply { start() }
        
        Log.d(TAG, "Audio engine started successfully")
    }

    /**
     * 停止音频捕获和播放
     */
    fun stop() {
        if (!isRunning.get()) return
        
        Log.d(TAG, "Stopping audio engine...")
        isRunning.set(false)
        
        recordThread?.join(500)
        recordThread = null
        
        audioRecord?.stop()
        audioTrack?.stop()
        
        Log.d(TAG, "Audio engine stopped")
    }

    /**
     * 释放音频引擎资源
     */
    fun release() {
        stop()
        
        audioRecord?.release()
        audioRecord = null
        
        audioTrack?.release()
        audioTrack = null
        
        scope.cancel()
        
        Log.d(TAG, "Audio engine released")
    }

    /**
     * 设置播放音量
     * @param volume 音量值 (0.0 - 1.0)
     */
    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        audioTrack?.setVolume(currentVolume)
    }

    /**
     * 设置最大输出电平（dB）
     * @param maxDb 最大音量dB值
     */
    fun setMaxOutputLevel(maxDb: Float) {
        maxVolumeDb = maxDb
    }

    /**
     * 获取当前延迟（毫秒）
     */
    fun getCurrentLatencyMs(): Double {
        // AudioTrack的延迟大约是缓冲区大小的一半
        return (bufferSize / 2.0 / SAMPLE_RATE) * 1000.0
    }

    /**
     * 应用音量和软限幅
     */
    private fun applyVolumeAndLimit(buffer: ShortArray, size: Int) {
        val maxLinear = Math.pow(10.0, maxVolumeDb / 20.0).toFloat()
        val effectiveVolume = currentVolume * maxLinear
        
        for (i in 0 until size) {
            var sample = buffer[i] * effectiveVolume
            
            // 软限幅
            sample = softClip(sample)
            
            buffer[i] = sample.toInt().toShort()
        }
    }
    
    /**
     * 软限幅函数
     */
    private fun softClip(sample: Float): Float {
        val threshold = 0.8f
        return when {
            sample > threshold -> {
                val x = (sample - threshold) / (1 - threshold)
                threshold + (1 - threshold) * (2 * x - x * x) / 2
            }
            sample < -threshold -> {
                val x = (-sample - threshold) / (1 - threshold)
                -(threshold + (1 - threshold) * (2 * x - x * x) / 2)
            }
            else -> sample
        }
    }
}
