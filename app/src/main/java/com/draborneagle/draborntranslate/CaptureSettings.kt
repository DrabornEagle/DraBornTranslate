// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import kotlin.math.roundToInt

internal enum class Source(val appName: String, val packageName: String) {
    PORTAL("PlayStation App", "com.scee.psxandroid"),
    MOBILE("PS Remote Play", "com.playstation.remoteplay"),
}

internal data class CaptureSettings(
    val bandTop: Int = 65,
    val textSize: Int = 22,
    val source: Source = Source.PORTAL,
) {
    val captionsAtTop: Boolean get() = bandTop >= 35

    companion object {
        const val BAND_HEIGHT = 30
        const val MAX_TOP = 100 - BAND_HEIGHT
        const val MIN_SIZE = 16
        const val MAX_SIZE = 32
    }
}

internal data class Pixels(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun videoArea(width: Int, height: Int): Pixels {
    val videoWidth = minOf(width, (height * (16f / 9)).roundToInt())
    val videoHeight = minOf(height, (width * (9f / 16)).roundToInt())
    return Pixels((width - videoWidth) / 2, (height - videoHeight) / 2, videoWidth, videoHeight)
}

internal fun subtitleBand(area: Pixels, top: Int): Pixels {
    val inset = (area.width * 0.06f).roundToInt()
    val topPercent = top.coerceIn(0, CaptureSettings.MAX_TOP)
    val bandY = (area.height * topPercent / 100f).roundToInt()
    val bandBottom = (area.height * (topPercent + CaptureSettings.BAND_HEIGHT) / 100f).roundToInt()
    return Pixels(area.x + inset, area.y + bandY, area.width - inset * 2, (bandBottom - bandY).coerceAtLeast(1))
}
