// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.OcrNoise
import com.gametranslator.gametranslator_mobile.core.StabilizerAction
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
    fun shortAnswerGetsThePrecedingSubtitleAfterAnOcrUpdate() {
        val question = "Did you lock the side door?"
        session.read(listOf(question))
        requests.removeFirst().second(TranslationReply("Yan kapıyı kilitledin mi?"))
        now += 340
        session.read(listOf("Yes, I did."))
        val answer = requests.removeFirst()
        assertEquals(TranslationInput("Yes, I did.", previousSubtitle = question), answer.first)
        answer.second(TranslationReply("Evet, kilitledim."))
        assertEquals(Caption("Yes, I did.", "Evet, kilitledim."), caption)
    }

    @Test
    fun similarSubtitlesUpdateWhenTwoReadingsConfirmTheNewMeaning() {
        val transitions = listOf(
            "Wait by the garage at Gate 7." to "Wait by the garage at Gate 9.",
            "I can open the back door for you." to "I can't open the back door for you.",
            "Go to the left side of the tunnel." to "Go to the right side of the tunnel.",
            "Adam left the van near the warehouse." to "Martin left the van near the warehouse.",
            "We're safe now." to "We're safe now?",
        )
        for ((before, after) in transitions) {
            val tracker = SubtitleStabilizer(clock = { now }, acceptCompletedSentence = false)
            assertEquals(StabilizerAction.None, tracker.feed(before))
            assertEquals(StabilizerAction.translate(before), tracker.feed(before))
            assertEquals(StabilizerAction.None, tracker.feed(after))
            assertEquals(StabilizerAction.translate(after), tracker.feed(after))
        }
    }

    @Test
    fun aMisspelledWordDoesNotReplaceASubtitle() {
        val tracker = SubtitleStabilizer(clock = { now }, acceptCompletedSentence = false)
        val original = "Meet me near the parking garage."
        tracker.feed(original)
        assertEquals(StabilizerAction.translate(original), tracker.feed(original))
        repeat(3) {
            assertEquals(StabilizerAction.None, tracker.feed(OcrNoise.clean("Meet me near the parking garag3.")))
        }
    }

    @Test
    fun subtitleCleaningKeepsCriticalWordsAndLeadingQuantities() {
        assertEquals("Don't show him my phone.", OcrNoise.clean("D0n't show him my phone."))
        assertEquals("Don't turn left at the light.", OcrNoise.clean("Don't turn Ieft at the light."))
        assertEquals("5 More cars are coming.", OcrNoise.clean("5 More cars are coming."))
        assertEquals("Open the gate now.", OcrNoise.clean("9? Open the gate now."))
    }

    @Test
    fun longDialogueUsesTwoLinesWithoutLosingWords() {
        val text = "Hadi buradan çıkalım. Polisler gelmeden köprüde buluşmamız gerek."
        val lines = formatDialogue(text).lines()
        assertEquals(2, lines.size)
        assertEquals(text, lines.joinToString(" "))
    }
}
