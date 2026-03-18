package com.echo.app.audio

import android.util.Log

/**
 * Phase 2: Oboe音频引擎Kotlin包装器
 *
 * 通过JNI桥接调用C++低延迟音频引擎
 * 提供简洁的Kotlin API供上层使用
 */
class OboeAudioEngine {

    companion object {
        private const val TAG = "OboeAudioEngine"

        init {
            try {
                System.loadLibrary("echo_audio")
                Log.d(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
                throw RuntimeException("Cannot load echo_audio library", e)
            }
        }
    }

    // Native方法声明 - 对应jni_bridge.cpp中的实现
    private external fun nativeInit(): Boolean
    private external fun nativeStart()
    private external fun nativeStop()
    private external fun nativeRelease()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetVolume(volume: Float)
    private external fun nativeGetVolume(): Float

    // 状态追踪
    private var isInitialized = false

    /**
     * 初始化音频引擎
     * @return 是否成功
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return true
        }

        val result = nativeInit()
        isInitialized = result
        Log.d(TAG, "Initialize result: $result")
        return result
    }

    /**
     * 开始音频监听
     * 必须先调用initialize()
     */
    fun start() {
        if (!isInitialized) {
            throw IllegalStateException("Must call initialize() before start()")
        }
        Log.d(TAG, "Starting audio engine")
        nativeStart()
    }

    /**
     * 停止音频监听
     */
    fun stop() {
        Log.d(TAG, "Stopping audio engine")
        nativeStop()
    }

    /**
     * 释放资源
     * 应用退出时调用
     */
    fun release() {
        Log.d(TAG, "Releasing audio engine")
        nativeRelease()
        isInitialized = false
    }

    /**
     * 检查是否正在运行
     */
    fun isRunning(): Boolean {
        return nativeIsRunning()
    }

    /**
     * 设置音量 (0.0 - 1.0)
     * 0.0 = 静音, 1.0 = 100% (默认0.7 = 70%)
     *
     * @param percent 音量百分比 0-100
     */
    fun setVolume(percent: Float) {
        // 将百分比转换为0.0-1.0范围
        val normalized = percent.coerceIn(0f, 100f) / 100f
        nativeSetVolume(normalized)
    }

    /**
     * 获取当前音量 (0.0 - 1.0)
     */
    fun getVolume(): Float {
        return nativeGetVolume()
    }

    /**
     * 获取当前音量百分比 (0-100)
     */
    fun getVolumePercent(): Float {
        return nativeGetVolume() * 100f
    }
}
