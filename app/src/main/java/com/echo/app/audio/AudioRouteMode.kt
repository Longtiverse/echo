package com.echo.app.audio

import com.echo.app.R

/**
 * ???????
 */
enum class AudioRouteMode(
    val key: String,
    val labelResId: Int
) {
    AUTO("auto", R.string.audio_route_auto),
    SPEAKER("speaker", R.string.audio_route_speaker),
    EARPIECE("earpiece", R.string.audio_route_earpiece);

    companion object {
        fun fromKey(value: String?): AudioRouteMode {
            return entries.firstOrNull { it.key == value } ?: AUTO
        }
    }
}
