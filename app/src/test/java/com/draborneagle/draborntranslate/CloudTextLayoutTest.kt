// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTextLayoutTest {
    private val frame = Pixels(0, 0, 1600, 900)
    private val config = CaptureSettings(readingArea = ReadingArea.DUAL, bandTop = 65)

    @Test fun subtitlesAndPhoneMessagesFromSameCameraFrame() {
        val lines = listOf(
            CloudTextBlock("Jason: We need to move!", 180, 680, 1200, 720),
            CloudTextBlock("Lucia: I'm coming.", 240, 730, 1320, 770),
            CloudTextBlock("Kolt & Associates: Meeting at noon.", 990, 100, 1510, 180),
        )
        val subtitles = CloudTextLayout.subtitles(frame, config.bandTop, lines)
        val phone = CloudTextLayout.messages(frame, config, lines)
        assertEquals(listOf("Jason: We need to move!", "Lucia: I'm coming."), subtitles)
        assertEquals(1, phone.size)
        assertTrue(phone[0].text.startsWith("Kolt & Associates:"))
    }

    @Test fun multilineCameraMessageStaysInOneParagraph() {
        val blocks = listOf(CloudTextBlock("Where are you?\nCall me when you get there.", 970, 120, 1470, 250))
        val message = CloudTextLayout.messages(frame, config, blocks)
        assertEquals(1, message.size)
        assertEquals("Where are you? Call me when you get there.", message[0].text)
    }

    @Test fun unrelatedGameUIOutsideChosenAreaIsIgnored() {
        val blocks = listOf(
            CloudTextBlock("PlayStation settings", 100, 70, 340, 100),
            CloudTextBlock("Last message from Lucia", 960, 120, 1450, 190),
            CloudTextBlock("Take cover!", 700, 695, 900, 727),
        )
        val phone = CloudTextLayout.messages(frame, config, blocks)
        assertEquals(1, phone.size)
        assertEquals("Last message from Lucia", phone[0].text)
        assertFalse(phone.any { it.text == "Take cover!" })
    }

    @Test fun offscreenOrZeroSizeBlocksCannotBeMessages() {
        val blocks = listOf(
            CloudTextBlock("Bad", 1000, 100, 1000, 180),
            CloudTextBlock("A", 1000, 100, 1120, 180),
            CloudTextBlock("Proceed", 1050, 150, 1230, 180),
        )
        assertEquals(listOf("Proceed"), CloudTextLayout.messages(frame, config, blocks).map { it.text })
    }
}
