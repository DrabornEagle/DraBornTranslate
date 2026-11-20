// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.SubtitleStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionSessionTest {
    private var now = 100L
    private var caption: Caption? = null
    private val requests = ArrayDeque<Pair<TranslationInput, (TranslationReply) -> Unit>>()
    private val session = CaptionSession(
        translate = { source, answer -> requests.addLast(source to answer) },
        show = { caption = it },
        onFailure = {},
        stabilizer = SubtitleStabilizer(clock = { now }),
    )

    @Test
    fun slowTranslationDoesNotReplaceNewerDialogue() {
        session.read(listOf("Get to the bridge!"))
        now += 340
        session.read(listOf("We must leave now."))
        now += 340
        session.read(listOf("Close the gate."))

        assertEquals(1, requests.size)
        requests.removeFirst().second(TranslationReply("Köprüye git!"))
        assertNull(caption)
        assertEquals("Close the gate.", requests.first().first.text)
        requests.removeFirst().second(TranslationReply("Kapıyı kapat."))
        assertEquals(Caption("Close the gate.", "Kapıyı kapat."), caption)
    }

    @Test
    fun subtitleIsRemovedWhenSourceDisappears() {
        session.read(listOf("Follow me."))
        requests.removeFirst().second(TranslationReply("Beni takip et."))
        assertEquals("Beni takip et.", caption?.turkish)
        now += 340
        session.read(emptyList())
        now += SubtitleStabilizer.CLEAR_AFTER_MS
        session.read(emptyList())
        assertNull(caption)
    }

    @Test
    fun aReturningSubtitleUsesTheCachedTranslation() {
        session.read(listOf("Follow me."))
        requests.removeFirst().second(TranslationReply("Beni takip et."))
        now += 340
        session.read(emptyList())
        now += SubtitleStabilizer.CLEAR_AFTER_MS
        session.read(emptyList())
        now += 340
        session.read(listOf("Follow me."))
        assertEquals("Beni takip et.", caption?.turkish)
        assertEquals(0, requests.size)
    }

    @Test
    fun stoppedSessionDiscardsAnInFlightTranslation() {
        session.read(listOf("Ready to go?"))
        session.close()
        requests.removeFirst().second(TranslationReply("Gitmeye hazır mısın?"))
        assertNull(caption)
    }

    @Test
    fun menuTextKeepsStableLabelsEvenWhenHudNumbersChange() {
        val session = CaptionSession(
            translate = { source, answer -> requests.addLast(source to answer) },
            show = { caption = it },
            onFailure = {},
            readingArea = ReadingArea.GAME_TEXT,
            stabilizer = SubtitleStabilizer(stableFrames = 1, clock = { now }, exactText = true),
        )
        session.read(listOf("Settings", "Exit", "99 HP"))
        assertEquals(0, requests.size)
        session.read(listOf("Settings", "Exit", "98 HP"))
        val first = requests.removeFirst()
        assertEquals("Settings\nExit", first.first.text)
        first.second(TranslationReply("Ayarlar\nÇıkış"))
        assertEquals("Ayarlar\nÇıkış", caption?.turkish)

        session.read(listOf("Settings", "Resume", "97 HP"))
        val second = requests.removeFirst()
        assertEquals("Settings", second.first.text)
        session.read(listOf("Settings", "Resume", "96 HP"))
        second.second(TranslationReply("Ayarlar"))
        assertNull(caption)
        val third = requests.removeFirst()
        assertEquals("Settings\nResume", third.first.text)
        third.second(TranslationReply("Ayarlar\nDevam et"))
        assertEquals("Ayarlar\nDevam et", caption?.turkish)
    }

    @Test
    fun keepsPreviousDialogueWhileTheNextLineIsTranslated() {
        val dialogue = CaptionSession(
            translate = { input, answer -> requests.addLast(input to answer) },
            show = { caption = it },
            onFailure = {},
            stabilizer = SubtitleStabilizer(clock = { now }, acceptCompletedSentence = false, minTextLength = 2),
        )
        dialogue.read(listOf("The cops are after us."))
        assertEquals(0, requests.size)
        now += 340
        dialogue.read(listOf("The cops are after us."))
        requests.removeFirst().second(TranslationReply("Polis peşimizde."))
        val previous = Caption("The cops are after us.", "Polis peşimizde.")
        assertEquals(previous, caption)
        now += 340
        dialogue.read(listOf("Go!"))
        now += 340
        dialogue.read(listOf("Go!"))
        assertEquals(previous, caption)
        val next = requests.removeFirst()
        assertEquals(TranslationInput("Go!"), next.first)
        next.second(TranslationReply("Bas gaza!"))
        assertEquals(Caption("Go!", "Bas gaza!"), caption)
    }

    @Test
    fun recognizesDifferentNumbersAndQuestionsAcrossSuccessiveSubtitles() {
        val dialogue = CaptionSession(
            translate = { input, answer -> requests.addLast(input to answer) },
            show = { caption = it },
            onFailure = {},
            stabilizer = SubtitleStabilizer(clock = { now }, exactText = true),
        )
        for ((source, turkish) in listOf(
            "Do we need 3 more cars?" to "3 araba daha gerekiyor mu?",
            "Do we need 4 more cars?" to "4 araba daha gerekiyor mu?",
            "We need 4 more cars." to "4 araba daha gerekiyor.",
            "We need 4 more cars?" to "4 araba daha mı gerekiyor?",
        )) {
            dialogue.read(listOf(source))
            assertEquals(0, requests.size)
            now += 280
            dialogue.read(listOf(source))
            val (input, answer) = requests.removeFirst()
            assertEquals(source, input.text)
            answer(TranslationReply(turkish))
            assertEquals(Caption(source, turkish), caption)
            now += 280
        }
    }

    @Test
    fun longDialogueUsesTwoLinesWithoutLosingWords() {
        val text = "Hadi buradan çıkalım. Polisler gelmeden köprüde buluşmamız gerek."
        val lines = formatDialogue(text).lines()
        assertEquals(2, lines.size)
        assertEquals(text, lines.joinToString(" "))
    }
}
