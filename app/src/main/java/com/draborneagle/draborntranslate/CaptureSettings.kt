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
    GAME_TEXT("Tam oyun görüntüsü", 650L),
}

internal enum class TranslationEngine(val label: String) {
    OFFLINE("Google Çeviri · Çevrimdışı"),
    NATURAL("Doğal Türkçe · Gemini"),
}

internal data class CaptureSettings(
    val bandTop: Int = 65,
    val textSize: Int = 22,
    val source: Source = Source.PORTAL,
    val display: SubtitleDisplay = SubtitleDisplay.PHONE,
    val readingArea: ReadingArea = ReadingArea.SUBTITLES,
    val engine: TranslationEngine = TranslationEngine.OFFLINE,
) {
    val captionsAtTop: Boolean get() = bandTop >= 35
    val showOnPhone: Boolean get() = source == Source.MOBILE || display.phone
    val showOnPortal: Boolean get() = source == Source.PORTAL && display.portal
    val speakOnPhone: Boolean get() = source == Source.PORTAL && display == SubtitleDisplay.VOICE
    fun scanArea(video: Pixels): Pixels = if (readingArea == ReadingArea.GAME_TEXT) video else subtitleBand(video, bandTop)

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
