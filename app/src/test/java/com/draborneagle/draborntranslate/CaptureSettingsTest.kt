// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSettingsTest {
    @Test
    fun widescreenPhoneCropExcludesTheSideBars() {
        val area = videoArea(1600, 720)
        assertEquals(Pixels(160, 0, 1280, 720), area)
        assertEquals(Pixels(237, 468, 1126, 216), subtitleBand(area, 65))
    }

    @Test
    fun portraitCropStaysInsideTheCenteredVideo() {
        val area = videoArea(720, 1600)
        val bottom = subtitleBand(area, CaptureSettings.MAX_TOP)
        assertTrue(area.y > 0)
        assertEquals(area.y + area.height, bottom.y + bottom.height)
    }
}
