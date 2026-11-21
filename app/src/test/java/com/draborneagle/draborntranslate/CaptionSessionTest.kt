// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionSessionTest {
    private var now = 100L
    private var cancelCount = 0
    private var recognized: String? = null
    private var caption: Caption? = null
    private val delivered = mutableListOf<String>()
    private val requests = ArrayDeque<Pair<TranslationInput, (TranslationReply) -> Unit>>()
    private val refinements = ArrayDeque<Pair<TranslationInput, (TranslationReply) -> Unit>>()

    private fun session(
        area: ReadingArea = ReadingArea.SUBTITLES,
        known: (TranslationInput) -> String? = { null },
        refine: Boolean = false,
    ) = CaptionSession(
        translate = { source, answer -> requests.addLast(source to answer) },
        show = {
            caption = it
            if (it != null) delivered.add(it.source)
        },
        onFailure = {},
        onRead = { recognized = it },
        cancelTranslation = { cancelCount++ },
        translateKnown = known,
        refine = { source, answer -> refine && refinements.add(source to answer) },
        readingArea = area,
        clock = { now },
    )

    private fun answer(source: String, turkish: String) {
        val (input, reply) = requests.removeFirst()
        assertEquals(source, input.text)
        reply(TranslationReply(turkish))
    }

    @Test
    fun latestConfirmedCueReplacesWaitingWorkAndLateRepliesStayOffScreen() {
        val dialogue = session()
        for (source in listOf("Get to the bridge!", "We must leave now.", "Close the gate.", "Stop!")) {
            dialogue.read(listOf(source), confident = true)
            now += 180
        }

        assertEquals(1, requests.size)
        assertEquals(1, cancelCount)
        answer("Get to the bridge!", "Köprüye git!")
        assertNull(caption)
        assertEquals("Stop!", recognized)
        answer("Stop!", "Dur!")
        assertEquals(Caption("Stop!", "Dur!"), caption)
        assertEquals(listOf("Stop!"), delivered)
        assertEquals(0, requests.size)
    }

    @Test
    fun aReadySubtitleIsPublishedWithoutWaitingForThePreviousOne() {
        val dialogue = session()
        dialogue.read(listOf("Follow me."), confident = true)
        answer("Follow me.", "Beni takip et.")
        now += 180
        dialogue.read(listOf("Go!"), confident = true)
        assertNull(caption)
        answer("Go!", "Git!")
        assertEquals("Go!", caption?.source)
        assertEquals(listOf("Follow me.", "Go!"), delivered)
    }

    @Test
    fun aDarkSceneDiscardsAnAcceptedCueThatIsNoLongerVisible() {
        val dialogue = session()
        dialogue.read(listOf("Follow me."), confident = true)
        dialogue.blankFrame()
        assertEquals(1, cancelCount)
        answer("Follow me.", "Beni takip et.")
        assertNull(caption)
    }

    @Test
    fun oneBlankOcrFrameDoesNotCancelTheVisibleCue() {
        val dialogue = session()
        dialogue.read(listOf("Follow me."), confident = true)
        now += 180
        dialogue.read(emptyList())
        assertEquals(0, cancelCount)
        answer("Follow me.", "Beni takip et.")
        assertEquals("Beni takip et.", caption?.turkish)
    }

    @Test
    fun aKnownCueAppearsWhileAnObsoleteModelRequestIsStillReturning() {
        val dialogue = session(known = { if (it.text == "Go!") "Git!" else null })
        dialogue.read(listOf("Walk across the bridge."), confident = true)
        dialogue.read(listOf("Go!"), confident = true)
        assertEquals(Caption("Go!", "Git!"), caption)
        assertEquals(1, cancelCount)
        answer("Walk across the bridge.", "Köprüden geç.")
        assertEquals("Git!", caption?.turkish)
        assertEquals(listOf("Go!"), delivered)
        assertEquals(0, requests.size)
    }

    @Test
    fun aReturningCueCanUseItsOriginalInFlightTranslation() {
        val dialogue = session()
        dialogue.read(listOf("Follow me."), confident = true)
        dialogue.read(listOf("Go!"), confident = true)
        dialogue.read(listOf("Follow me."), confident = true)
        answer("Follow me.", "Beni takip et.")
        assertEquals("Beni takip et.", caption?.turkish)
        assertEquals(listOf("Follow me."), delivered)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun startsUncertainTranslationOnTheFirstFrameAndShowsItOnConfirmation() {
        val dialogue = session()
        val source = "Stay close behind me."
        dialogue.read(listOf(source), frameAt = 70)
        assertEquals(1, requests.size)
        assertNull(recognized)
        now = 146
        answer(source, "Peşimden ayrılma.")
        assertNull(caption)
        now = 171
        dialogue.read(listOf(source), frameAt = 145)
        assertEquals(Caption(source, "Peşimden ayrılma."), caption)
        assertEquals(101L, dialogue.subtitleDelayMs)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun lateResultIsHeldWhenANewerUncertainCaptionIsConfirmed() {
        val dialogue = session()
        dialogue.read(listOf("Check the back seat."), confident = true)
        now += 75
        dialogue.read(listOf("Did you bring the keys?"))
        assertEquals(0, cancelCount)
        answer("Check the back seat.", "Arka koltuğa bak.")
        assertNull(caption)
        now += 75
        dialogue.read(listOf("Did you bring the keys?"))
        answer("Did you bring the keys?", "Anahtarları getirdin mi?")
        assertEquals(listOf("Did you bring the keys?"), delivered)
    }

    @Test
    fun lateResultIsRestoredWhenTheNewReadingWasOnlyAnOcrError() {
        val dialogue = session()
        dialogue.read(listOf("Check the back seat."), confident = true)
        now += 75
        dialogue.read(listOf("Check the back seal."))
        answer("Check the back seat.", "Arka koltuğa bak.")
        assertNull(caption)
        now += 75
        dialogue.read(emptyList())
        assertNull(caption)
        now += 75
        dialogue.read(listOf("Check the back seat."))
        assertEquals(Caption("Check the back seat.", "Arka koltuğa bak."), caption)
        answer("Check the back seal.", "Arka contayı kontrol et.")
        assertEquals(listOf("Check the back seat."), delivered)
    }

    @Test
    fun canceledResultDoesNotPreventTheSameCueFromReappearing() {
        val dialogue = session()
        dialogue.read(listOf("Follow me."), confident = true)
        dialogue.read(listOf("Go!"), confident = true)
        dialogue.read(listOf("Follow me."), confident = true)
        assertEquals(1, cancelCount)
        val (input, canceledReply) = requests.removeFirst()
        assertEquals("Follow me.", input.text)
        canceledReply(TranslationReply(issue = "Canceled"))
        answer("Follow me.", "Beni takip et.")
        assertEquals(listOf("Follow me."), delivered)
    }

    @Test
    fun shortAndLongCuesKeepTheirOwnLifetimes() {
        val dialogue = session()
        for ((source, target, frames) in listOf(
            Triple("We lost them.", "İzlerini kaybettirdik.", 3),
            Triple("Wait here until I come back.", "Dönene kadar burada bekle.", 45),
            Triple("Take the next exit.", "Sıradaki çıkıştan çık.", 3),
        )) {
            dialogue.read(listOf(source), confident = true)
            assertNull(caption)
            now += 95
            answer(source, target)
            val expected = Caption(source, target)
            assertEquals(expected, caption)
            repeat(frames - 1) {
                now += 180
                dialogue.read(listOf(source), confident = true)
                assertEquals(expected, caption)
                assertTrue(requests.isEmpty())
            }
            now += 85
        }
        dialogue.read(emptyList())
        assertEquals("Take the next exit.", caption?.source)
        now += 180
        dialogue.read(emptyList())
        assertNull(caption)
        assertNull(recognized)
    }

    @Test
    fun repeatedOcrPunctuationAndCaseDoNotRemoveTheTranslation() {
        val dialogue = session()
        dialogue.read(listOf("We should wait."), confident = true)
        answer("We should wait.", "Beklemeliyiz.")
        for (read in listOf("WE SHOULD WAIT!", "We should wait", "We should wait…")) {
            now += 180
            dialogue.read(listOf(read), confident = true)
            assertEquals("Beklemeliyiz.", caption?.turkish)
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun refinementOnlyUpdatesItsOwnVisibleCueAndIsRemembered() {
        val dialogue = session(refine = true)
        dialogue.read(listOf("We lost them."), confident = true)
        answer("We lost them.", "Onları kaybettik.")
        assertTrue(refinements.isEmpty())
        now += 700
        dialogue.read(listOf("We lost them."), confident = true)
        val (first, finishFirst) = refinements.removeFirst()
        assertEquals("We lost them.", first.text)
        dialogue.read(listOf("We'll sort it out."), confident = true)
        answer("We'll sort it out.", "Bunu çözeceğiz.")
        finishFirst(TranslationReply("İzlerini kaybettirdik."))
        assertEquals("Bunu çözeceğiz.", caption?.turkish)
        assertEquals(listOf("We lost them.", "We'll sort it out."), delivered)
        now += 700
        dialogue.read(listOf("We'll sort it out."), confident = true)
        val (second, finishSecond) = refinements.removeFirst()
        assertEquals("We'll sort it out.", second.text)
        finishSecond(TranslationReply("Bir yolunu buluruz."))
        assertEquals("Bir yolunu buluruz.", caption?.turkish)
        dialogue.read(listOf("We lost them."), confident = true)
        assertEquals("İzlerini kaybettirdik.", caption?.turkish)
        assertTrue(requests.isEmpty())
        assertTrue(refinements.isEmpty())
    }

    @Test
    fun resetDoesNotReuseAnInFlightResultFromThePreviousSource() {
        val dialogue = session()
        dialogue.read(listOf("I got you."), confident = true)
        dialogue.reset()
        dialogue.read(listOf("I got you."), confident = true)
        answer("I got you.", "Seni yakaladım.")
        assertNull(caption)
        answer("I got you.", "Arkandayım.")
        assertEquals("Arkandayım.", caption?.turkish)
    }

    @Test
    fun returningSubtitleReusesCacheAndAnUninterruptedReadDoesNotDuplicateIt() {
        val dialogue = session()
        repeat(5) {
            dialogue.read(listOf("Follow me."), confident = true)
            now += 220
        }
        assertEquals(1, requests.size)
        answer("Follow me.", "Beni takip et.")
        dialogue.read(emptyList())
        now += 1500
        dialogue.read(emptyList())
        assertNull(caption)
        dialogue.read(listOf("Follow me."), confident = true)
        assertEquals("Beni takip et.", caption?.turkish)
        assertEquals(0, requests.size)
    }

    @Test
    fun resetCancelsOnlyTheOldSourceAndWaitsForItsCallbackBeforeSendingTheNext() {
        val dialogue = session()
        dialogue.read(listOf("Ready to go?"), confident = true)
        dialogue.read(listOf("Follow me."), confident = true)
        dialogue.reset()
        assertEquals(1, cancelCount)
        dialogue.read(listOf("Get out."), confident = true)
        assertEquals(1, requests.size)
        answer("Ready to go?", "Gitmeye hazır mısın?")
        assertNull(caption)
        answer("Get out.", "Dışarı çık.")
        assertEquals("Dışarı çık.", caption?.turkish)
    }

    @Test
    fun stoppedSessionDiscardsAnInFlightTranslation() {
        val dialogue = session()
        dialogue.read(listOf("Ready to go?"), confident = true)
        dialogue.close()
        answer("Ready to go?", "Gitmeye hazır mısın?")
        assertNull(caption)
        assertEquals(1, cancelCount)
    }

    @Test
    fun translatesEveryStableMenuRowWithoutRetranslatingTheOnesAlreadyShown() {
        val game = session(ReadingArea.GAME_TEXT)
        val menu = (1..31).map { "Option $it" }
        game.read(menu + "99 HP")
        assertEquals(0, requests.size)
        game.read(menu + "98 HP")
        for ((index, row) in menu.withIndex()) {
            answer(row, "Seçenek ${index + 1}")
            game.read(menu + "${97 - index} HP")
        }
        assertEquals(menu, caption?.source?.lines())
        assertEquals(31, caption?.turkish?.lines()?.size)
        assertEquals(0, requests.size)
        assertEquals(0, cancelCount)
    }

    @Test
    fun menuChangesKeepSharedRowsAndDoNotPublishThePreviousScreensPendingRow() {
        val game = session(ReadingArea.GAME_TEXT)
        repeat(2) { game.read(listOf("Settings", "Exit")) }
        answer("Settings", "Ayarlar")
        repeat(2) { game.read(listOf("Settings", "Resume")) }
        assertEquals(0, cancelCount)
        answer("Exit", "Çıkış")
        assertEquals("Ayarlar", caption?.turkish)
        answer("Resume", "Oyuna dön")
        assertEquals("Ayarlar\nOyuna dön", caption?.turkish)
        assertEquals(0, requests.size)
    }

    @Test
    fun verifiedMenuRowsAppearBeforeAnUnknownRowCompletes() {
        val game = session(ReadingArea.GAME_TEXT, known = { if (it.text == "Settings") "Ayarlar" else null })
        repeat(2) { game.read(listOf("Toggle the radio", "Settings")) }
        assertEquals("Ayarlar", caption?.turkish)
        answer("Toggle the radio", "Radyoyu aç veya kapat")
        assertEquals("Radyoyu aç veya kapat\nAyarlar", caption?.turkish)
    }

    @Test
    fun uncertainReadsNeedConfirmationAndDifferentNumbersAndQuestionsRemainSeparate() {
        val dialogue = session()
        for ((source, turkish) in listOf(
            "Do we need 3 more cars?" to "3 araba daha gerekiyor mu?",
            "Do we need 4 more cars?" to "4 araba daha gerekiyor mu?",
            "We need 4 more cars." to "4 araba daha gerekiyor.",
            "We need 4 more cars?" to "4 araba daha mı gerekiyor?",
        )) {
            dialogue.read(listOf(source))
            assertEquals(1, requests.size)
            assertNotEquals(source, recognized)
            now += 75
            dialogue.read(listOf(source))
            answer(source, turkish)
            assertEquals(Caption(source, turkish), caption)
            now += 1200
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
