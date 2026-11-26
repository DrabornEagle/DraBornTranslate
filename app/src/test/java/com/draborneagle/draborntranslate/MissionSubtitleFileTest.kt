// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MissionSubtitleFileTest {
    @Test
    fun readsSrtInTimeOrderAndPreservesSubtitleLineBreaks() {
        val content = "\uFEFF2\r\n00:01:01,002 --> 00:01:04,009\r\n<i>İkinci satır</i>\r\n\r\n" +
            "1\r\n00:00:06,000 --> 00:00:09,025\r\nBirinci\r\nsatır\r\n"
        val cues = MissionSubtitleFile.parse(content)
        assertEquals(listOf(6_000L, 61_002L), cues.map { it.startMs })
        assertEquals(9_025L, cues[0].endMs)
        assertEquals("Birinci\nsatır", cues[0].text)
        assertEquals("İkinci satır", cues[1].text)
    }

    @Test
    fun readsWebVttSpeakerAndSettings() {
        val rows = MissionSubtitleFile.parse("""
            WEBVTT

            NOTE Scene label

            00:01.150 --> 00:03.000 position:45%
            <v Deniz>Merhaba &amp; hoş geldin.</v>
        """.trimIndent())
        assertEquals(listOf(MissionCue(1_150L, 3_000L, "Merhaba & hoş geldin.", "Deniz")), rows)
    }

    @Test
    fun rejectsInvalidAndBackwardsTimestamps() {
        assertThrows(IOException::class.java) { MissionSubtitleFile.parse("1\n00:13,170 --> 00:12,000\nTest") }
        assertThrows(IOException::class.java) { MissionSubtitleFile.parse("1\n27:66:00,100 --> 28:00:00,000\nTest") }
    }
}
