// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import kotlin.math.roundToInt

internal enum class Source(val appName: String, val packageName: String) {
    PORTAL("PlayStation App", "com.scee.psxandroid"),
    MOBILE("PS Remote Play", "com.playstation.remoteplay"),
}

internal enum class SubtitleDisplay(val label: String, val phone: Boolean, val portal: Boolean) {
    PHONE("Telefon", true, false),
    PORTAL("Portal yan paneli (deneysel)", false, true),
    BOTH("Telefon ve Portal paneli (deneysel)", true, true),
    VOICE("Sesli Türkçe", false, false),
}

internal enum class ReadingArea(val label: String, val sampleIntervalMs: Long) {
    SUBTITLES("Altyazı bölgesi", 340L),
    GAME_TEXT("Tüm oyun metinleri", 650L),
    LENS("Lens · Yazının üzerine çevir", 520L),
    DUAL("Altyazı + Lens · Aynı anda", 450L),
}

internal data class CaptureSettings(
    val bandTop: Int = DEFAULT_BAND_TOP,
    val textSize: Int = 22,
    val source: Source = Source.PORTAL,
    val display: SubtitleDisplay = SubtitleDisplay.PHONE,
    val readingArea: ReadingArea = ReadingArea.SUBTITLES,
    val lensLeft: Int = 52,
    val lensTop: Int = 6,
    val lensWidth: Int = 46,
    val lensHeight: Int = 48,
) {
    val captionsAtTop: Boolean get() = bandTop >= 35
    val showOnPhone: Boolean get() = source == Source.MOBILE || display.phone
    val showOnPortal: Boolean get() = source == Source.PORTAL && display.portal
    val speakOnPhone: Boolean get() = source == Source.PORTAL && display == SubtitleDisplay.VOICE
    fun scanArea(video: Pixels): Pixels = when (readingArea) {
        ReadingArea.SUBTITLES -> subtitleBand(video, bandTop)
        else -> video
    }

    /** The Lens rectangle is relative to the actual 16:9 video, not status/navigation bars. */
    fun lensArea(video: Pixels): Pixels = percentRect(
        video, lensLeft, lensTop, lensWidth, lensHeight
    )

    fun subtitleArea(video: Pixels): Pixels = subtitleBand(video, bandTop)

    companion object {
        const val DEFAULT_BAND_TOP = 65
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

/** A user-selected rectangle within the game video, clamped against borders. */
internal fun percentRect(area: Pixels, left: Int, top: Int, width: Int, height: Int): Pixels {
    val l = left.coerceIn(0, 95)
    val t = top.coerceIn(0, 95)
    val w = width.coerceIn(5, 100 - l)
    val h = height.coerceIn(5, 100 - t)
    val x = area.x + area.width * l / 100
    val y = area.y + area.height * t / 100
    val right = (area.x + area.width * (l + w) / 100).coerceAtMost(area.x + area.width)
    val bottom = (area.y + area.height * (t + h) / 100).coerceAtMost(area.y + area.height)
    return Pixels(x, y, (right - x).coerceAtLeast(1), (bottom - y).coerceAtLeast(1))
}
