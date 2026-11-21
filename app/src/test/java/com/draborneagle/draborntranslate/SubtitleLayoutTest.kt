// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLayoutTest {
    @Test
    fun joinsASpeakerLabelAndTextOnTheSameBaseline() {
        val lines = listOf(
            RecognizedLine("DANA:", 285, 104, 350, 130, 0.91f),
            RecognizedLine("The gate is still locked.", 367, 105, 656, 131, 0.92f),
            RecognizedLine("12 XP", 745, 106, 805, 128, 0.93f),
        )
        assertEquals(TextReading(listOf("DANA: The gate is still locked."), confident = true),
            selectSubtitleLines(lines, 960, 180))
    }
    @Test
    fun keepsWrappedSubtitlesAndOmitsHudTextOnTheSameRow() {
        val lines = listOf(
            RecognizedLine("New message", 200, 143, 303, 166, 0.94f),
            RecognizedLine("l'll be there", 438, 140, 562, 163, 0.96f),
            RecognizedLine("in 3 minutes.", 427, 166, 573, 188, 0.94f),
        )
        assertEquals(listOf("I'll be there", "in 3 minutes."), selectSubtitleLines(lines, 1000, 280).lines)
    }

    @Test
    fun keepsTheReadableLineButRequiresConfirmationWhenTheContinuationIsUnreadable() {
        val lines = listOf(
            RecognizedLine("Do not open the door", 385, 140, 615, 163, 0.95f),
            RecognizedLine("u$3q", 470, 166, 530, 188, 0.24f),
        )
        assertEquals(TextReading(listOf("Do not open the door")), selectSubtitleLines(lines, 1000, 280))
    }

    @Test
    fun keepsFourWrappedRowsInReadingOrder() {
        val lines = listOf("What are you doing", "standing outside", "in the cold", "at midnight?")
            .mapIndexed { index, text ->
                RecognizedLine(text, 380, 100 + 26 * index, 620, 123 + 26 * index, 0.91f)
            }.reversed()
        assertEquals(lines.reversed().map(RecognizedLine::text), selectSubtitleLines(lines, 1000, 240).lines)
    }

    @Test
    fun acceptsCenteredSpeechOnTheFirstReadWhenTheScoreIsUnavailable() {
        val lines = listOf(RecognizedLine("We need to get out", 386, 213, 614, 237, 0f))
        assertEquals(TextReading(listOf("We need to get out"), confident = true),
            selectSubtitleLines(lines, 1000, 280, preferLower = true))
    }

    @Test
    fun prefersLowerDialogueOverAHigherConfidenceHudBanner() {
        val lines = listOf(
            RecognizedLine("CHECKPOINT", 417, 115, 583, 139, 0.98f),
            RecognizedLine("Can you hear me?", 387, 213, 613, 237, 0.65f),
            RecognizedLine("+120 XP", 646, 216, 714, 241, 0.97f),
        )
        assertEquals(TextReading(listOf("Can you hear me?"), confident = true),
            selectSubtitleLines(lines, 1000, 280, preferLower = true))
    }

    @Test
    fun aShortKnownRepliqueCanPassImmediatelyButAWeakOneStillNeedsConfirmation() {
        fun read(text: String, confidence: Float) = selectSubtitleLines(
            listOf(RecognizedLine(text, 452, 188, 548, 212, confidence)), 1000, 280, preferLower = true,
        )
        assertEquals(TextReading(listOf("Run!"), confident = true), read("Run!", 0.64f))
        assertEquals(TextReading(listOf("What did you do?")), read("What did you do?", 0.43f))
    }
}
