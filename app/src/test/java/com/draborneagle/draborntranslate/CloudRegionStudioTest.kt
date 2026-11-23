// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRegionStudioTest {
    private val frame = Pixels(0, 0, 1600, 900)

    @Test fun twoIndependentRectsCanBeMovedBothVerticallyAndHorizontally() {
        val subtitles = CloudRegion.SUBTITLES.move(-3, -12)
        val messages = CloudRegion.MESSAGES.move(-22, 20)
        assertEquals(CloudRegion(3, 53, 88, 28), subtitles)
        assertEquals(CloudRegion(30, 26, 45, 49), messages)
        assertEquals(Pixels(48, 477, 1408, 252), subtitles.pixel(frame))
    }

    @Test fun cornerResizingClampsToBothScreenEdges() {
        val current = CloudRegion(40, 40, 35, 30)
        assertEquals(CloudRegion(20, 30, 55, 40), current.resize(1, -20, -10))
        assertEquals(CloudRegion(40, 40, 60, 60), current.resize(4, 100, 100))
        assertEquals(CloudRegion(40, 40, 5, 5), current.resize(4, -100, -100))
        assertEquals(CloudRegion(0, 0, 100, 100), current.copy(left = -25, top = -50, width = 190, height = 200).normalized())
    }

    @Test fun shortPhoneMessageRegionDoesNotGrabSubtitleText() {
        val subtitle = CloudTextBlock("Lucia: We need to leave.", 120, 745, 1350, 800)
        val message = CloudTextBlock("Don't tell Jason.", 900, 120, 1430, 190)
        val ui = CloudTextBlock("PlayStation", 190, 85, 400, 140)
        val input = listOf(message, subtitle, ui)
        assertEquals(listOf("Lucia: We need to leave."),
            CloudRegionText.lines(frame, CloudRegion.SUBTITLES, input))
        assertEquals(listOf("Don't tell Jason."),
            CloudRegionText.blocks(frame, CloudRegion.MESSAGES, input).map { it.text })
    }

    @Test fun portraitAndLandscapeCameraFramesUseSamePercentRegionModel() {
        val portrait = Pixels(0, 0, 900, 1600)
        val landscape = Pixels(0, 0, 1600, 900)
        val rect = CloudRegion(25, 20, 50, 40)
        assertEquals(Pixels(225, 320, 450, 640), rect.pixel(portrait))
        assertEquals(Pixels(400, 180, 800, 360), rect.pixel(landscape))
    }

    @Test fun textOutsideSelectedMessageBoxIsNotDetected() {
        val outside = CloudTextBlock("Wrong UI", 40, 50, 250, 120)
        val inside = CloudTextBlock("Call me tomorrow.", 880, 145, 1470, 190)
        val result = CloudRegionText.blocks(frame, CloudRegion.MESSAGES, listOf(outside, inside))
        assertEquals("Call me tomorrow.", result.single().text)
        assertFalse(result.any { it.text == "Wrong UI" })
        assertTrue(result.all { it.text.isNotBlank() })
    }
}
