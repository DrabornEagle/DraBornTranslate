// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualReadingAreaTest {
    @Test fun dualModeSharesFullGameFrameButUsesTwoIndependentCrops() {
        val cfg = CaptureSettings(readingArea = ReadingArea.DUAL)
        val video = Pixels(15, 30, 1600, 900)
        assertEquals(video, cfg.scanArea(video))
        assertEquals(Pixels(111, 615, 1408, 270), cfg.subtitleArea(video))
        assertEquals(Pixels(847, 84, 736, 432), cfg.lensArea(video))
    }

    @Test fun defaultPhoneLensAndSubtitlesDoNotOverlap() {
        val cfg = CaptureSettings(readingArea = ReadingArea.DUAL)
        val video = Pixels(0, 0, 1000, 1000)
        val subtitles = cfg.subtitleArea(video)
        val lens = cfg.lensArea(video)
        assertTrue(lens.y + lens.height < subtitles.y)
        assertEquals(2, listOf(lens, subtitles).size)
    }

    @Test fun selectedRegionIsSafelyClampedAtEdges() {
        val video = Pixels(30, 20, 1500, 750)
        val regions = listOf(
            percentRect(video, -100, -100, 999, 999),
            percentRect(video, 96, 98, 80, 80),
            percentRect(video, 85, 85, 15, 15),
        )
        for (rectangle in regions) {
            assertTrue(rectangle.x >= video.x)
            assertTrue(rectangle.y >= video.y)
            assertTrue(rectangle.width > 0)
            assertTrue(rectangle.height > 0)
            assertTrue(rectangle.x + rectangle.width <= video.x + video.width)
            assertTrue(rectangle.y + rectangle.height <= video.y + video.height)
        }
    }

    @Test fun legacyReadingModesKeepTheirOriginalScanAreas() {
        val video = Pixels(0, 0, 1920, 1080)
        assertEquals(subtitleBand(video, 65), CaptureSettings().scanArea(video))
        assertEquals(video, CaptureSettings(readingArea = ReadingArea.LENS).scanArea(video))
        assertEquals(video, CaptureSettings(readingArea = ReadingArea.GAME_TEXT).scanArea(video))
        assertEquals(450L, ReadingArea.DUAL.sampleIntervalMs)
    }

    @Test fun defaultDualGta6ModeKeepsPhoneOverlayEnabled() {
        val cfg = CaptureSettings(
            source = Source.PORTAL,
            readingArea = ReadingArea.DUAL,
            display = SubtitleDisplay.BOTH,
        )
        assertTrue(cfg.showOnPhone)
        assertTrue(cfg.showOnPortal)
        assertFalse(cfg.speakOnPhone)
    }
}
