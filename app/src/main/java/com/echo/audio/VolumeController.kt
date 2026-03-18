package com.echo.audio

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.pow

/**
 * 音量控制器
 * 管理最大音量限制和用户音量设置
 */
class VolumeController(context: Context) {

    companion object {
        const val PREFS_NAME = "volume_prefs"
        const val KEY_MAX_VOLUME_DB = "max_volume_db"
        const val KEY_USER_VOLUME = "user_volume"
        const val DEFAULT_MAX_VOLUME_DB = -3f  // 默认最大音量-3dB（约70%）
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 最大输出电平（dB）
    var maxVolumeDb: Float
        get() = prefs.getFloat(KEY_MAX_VOLUME_DB, DEFAULT_MAX_VOLUME_DB)
        set(value) {
            prefs.edit().putFloat(KEY_MAX_VOLUME_DB, value).apply()
        }

    // 用户设置的音量比例 (0.0 - 1.0)
    var userVolume: Float
        get() = prefs.getFloat(KEY_USER_VOLUME, 1.0f)
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            prefs.edit().putFloat(KEY_USER_VOLUME, clamped).apply()
        }

    /**
     * 计算最终输出音量
     * 公式：outputVolume = userVolume * maxVolumeLinear
     * 其中 maxVolumeLinear = 10^(maxVolumeDb/20)
     */
    fun calculateOutputVolume(): Float {
        val maxVolumeLinear = 10f.pow(maxVolumeDb / 20f)
        return userVolume * maxVolumeLinear
    }

    /**
     * 设置最大音量限制（百分比）
     * @param percentage 百分比 (0-100)
     */
    fun setMaxVolumePercentage(percentage: Int) {
        val clampedPercentage = percentage.coerceIn(0, 100)
        // 将百分比转换为dB
        // 100% = 0dB, 50% = -6dB, 25% = -12dB
        maxVolumeDb = 20f * kotlin.math.log10(clampedPercentage / 100f)
    }

    /**
     * 获取当前最大音量限制百分比
     */
    fun getMaxVolumePercentage(): Int {
        val linear = 10f.pow(maxVolumeDb / 20f)
        return (linear * 100).toInt().coerceIn(0, 100)
    }
}
