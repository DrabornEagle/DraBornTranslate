// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionSessionTest {
    private var now = 100L
    private var cancelCount = 0
    private var recognized: String? = null
    private var caption: Caption? = null
    private val delivered = mutableListOf<String>()
    private val requests = ArrayDeque<Pair<TranslationInput, (TranslationReply) -> Unit>>()

    private fun session(
        area: ReadingArea = ReadingArea.SUBTITLES,
        known: (TranslationInput) -> String? = { null },
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
    fun aKnownRepliqueAppearsWhileAnObsoleteModelRequestIsStillReturning() {
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
    fun aReturningCueCanBeTranslatedAfterItsFirstRequestWasCanceled() {
        val dialogue = session()
        dialogue.read(listOf("Follow me."), confident = true)
        dialogue.read(listOf("Go!"), confident = true)
        dialogue.read(listOf("Follow me."), confident = true)
        val (first, finish) = requests.removeFirst()
        assertEquals("Follow me.", first.text)
        finish(TranslationReply(issue = "Canceled"))
        answer("Follow me.", "Beni takip et.")
        assertEquals("Beni takip et.", caption?.turkish)
        assertEquals(listOf("Follow me."), delivered)
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
        assertEquals(1, cancelCount)
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
            assertEquals(0, requests.size)
            now += 220
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
