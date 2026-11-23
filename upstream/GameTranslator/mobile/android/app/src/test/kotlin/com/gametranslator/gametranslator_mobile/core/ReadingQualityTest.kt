// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor

private fun wordSet(vararg words: String): (String) -> Boolean {
    val set = words.map { it.lowercase(Locale.ROOT) }.toSet()
    return { word -> word.lowercase(Locale.ROOT) in set }
}

/** Karışan harflerin onarımı (Windows: OcrConfusionsTests). */
class OcrConfusionsTest {
    private val words = wordSet(
        "crouch", "to", "make", "your", "steps", "quiet", "knives", "do", "not", "vanish", "they", "are", "used", "for",
        "attack", "and", "defense", "the", "noise", "of", "moving", "after", "alter", "tor", "lake", "take", "it", "is",
        "modern", "moment", "are", "you", "ok", "hold", "on", "weapon", "from", "form",
        "time", "running", "out", "my", "love", "there", "much", "we", "won't", "find", "him", "this", "way", "let's",
        "that", "trail", "blood", "use", "words", "brother", "no", "truth", "hurts", "huh", "a", "i", "here", "where",
        "heywood", "as", "her", "hey", "wood", "oh", "help", "brother", "try", "contraption", "just", "next", "pod", "did", "something", "when", "sealed", "me", "in", "learned", "come", "had", "apples", "fi", "et", "oy", "money", "lot", "paying", "field", "bello", "win", "bellowing", "hence", "ray", "field", "mine", "should", "known", "stay", "trash", "sea", "woman", "little", "faith", "jackie",
    )
    private val gameWords = wordSet(
        "think", "they'd", "ever", "let", "him", "go", "get", "some", "sleep", "gonna", "drive", "you", "guys", "to", "the",
        "other", "one", "does", "flying", "but", "it's", "drugs", "blow", "them", "out", "of", "water", "take", "holy",
        "shit", "ha", "karma's", "a", "bitch", "his", "head", "down", "helps", "people", "brothers", "okay", "is", "then", "lion",
    )

    private fun repair(line: String) = OcrConfusions.repair(line, words)

    @Test
    fun confusedLettersAreRepaired() {
        assertEquals("Crouch to make your steps quiet.", repair("Crouch Io make your steps quiet."))
        assertEquals("Knives do not vanish.", repair("Knives do nol vanish."))
        assertEquals("They are used for attack and defense.", repair("They are used lor attack and defense."))
        assertEquals("The noise of moving", repair("The noise 01 moving"))
        assertEquals("Take it after the attack.", repair("Take it aller the attack."))
        assertEquals("Are you OK?", repair("Are you 0K?"))
        assertEquals("A modern weapon", repair("A rnodern weapon"))
        assertEquals("To make it quiet", repair("Io make it quiet"))
    }

    @Test
    fun roundFontConfusionsAreRepaired() {
        assertEquals("Time is running out, my love.", repair("Tinne is running out, ny love."))
        assertEquals("And there is much to do.", repair("And there is nauch co do."))
        assertEquals("We won't find him this way.", repair("Wc won't find hinn chis way."))
        assertEquals("Let's find that trail of blood.", repair("Let's find chat trail of blood."))
        assertEquals("Time is running out, my love.", repair("Time is running out, noy iove."))
    }

    @Test
    fun gluedWordsAreSplit() {
        assertEquals("And there is much to do.", repair("Andthereismuchtodo."))
        assertEquals("Let's find that trail of blood...", repair("Let'sfindthattrailofblood..."))
        assertEquals("Use your words, brother.", repair("Useyourwords,brother."))
        assertEquals("No. It hurts.", repair("No.It hurts."))
        assertEquals("Shadowheart: Try that contraption just next to the pod - they did something to it when",
            repair("Shadowheart:Trythatcontraptionjustnexttothepod-theydidsomethingtoitwhen"))
    }

    @Test
    fun exclamationReadAsLAndOtherSingleConfusions() {
        assertEquals("Oh! Time is running out.", repair("Ohl Time is running out."))
        assertEquals("Brother! Brother!", repair("Brotherl Brother!"))
        assertEquals("Help him, brother!", repair("Help him, brotherl"))
        assertEquals("Ohl no.", repair("Ohl no."))
        assertEquals("I learned a long time ago.", repair("1 learned a long time ago."))
        assertEquals("Stay where you are!", repair("Stoy where you are!"))
        assertEquals("paying you a lot of money", repair("paying you a lot of Ilioney"))
        assertEquals("You're a woman of little faith.", repair("You're o woman of little faith."))
        assertEquals("Street trash in the sea.", repair("Street trosh in the seo."))
        assertEquals("Come on.", repair("Conne on."))
        assertEquals("We had 1 apples.", repair("We had 1 apples."))
    }

    @Test
    fun namesRealWordsAndUnknownsAreLeftAlone() {
        for (line in listOf("You live in Heywood, huh?", "They are Asherhere.", "U.S. Army", "Jackiefiet the car.", "Hence my bellowin'.",
            "Jackie: Rayfield's mine.", "You shoulda known.", "They alter the form.", "Artyom, hold on!", "Kephallonia is not Zorvald.", "They are xqlz."))
            assertEquals(line, repair(line))
        assertEquals("To lake the weapon", repair("Io lake the weapon"))
        assertEquals("Knives do nol vanish.", OcrConfusions.repair("Knives do nol vanish.", null))
    }

    @Test
    fun turkishLettersInEnglishAreRepaired() {
        fun turkish(line: String) = OcrConfusions.repair(line, gameWords, fromTurkishLetters = true)
        assertEquals("think they'd ever let him out", turkish("thİnk they'd ever let hinı out"))
        assertEquals("Go get some sleep.", turkish("Go get some şleep."))
        assertEquals("But It's drugs, okay", turkish("But İt's drugsı okay"))
        assertEquals("Blow them out of the water!", turkish("Blow thenı out of the water!"))
        assertEquals("his head down, helps people", turkish("his head downı helps people"))
        assertEquals("Tommy Vercetti...", turkish("Tommy Vercettı..."))
        assertEquals("thİnk şleep", OcrConfusions.repair("thİnk şleep", gameWords))
    }
}

/** Okumaların sözcük sözcük birleştirilmesi (Windows: ReadingConsensusTests). */
class ReadingConsensusTest {
    private val words = wordSet(
        "stop", "the", "gate", "is", "open", "so", "do", "we", "hold", "this", "place", "or", "let", "them", "pass",
        "keep", "your", "head", "down", "and", "stay", "close", "to", "me", "slop", "wall",
    )

    private fun merge(best: String, vararg others: String) = ReadingConsensus.merge(best, listOf(best) + others, words)

    @Test
    fun brokenWordsAreFilledFromOtherReadings() {
        assertEquals("Stop! The gate is open, so do we hold this place?",
            merge("Stop! The gafe is open, so do we hold this place?", "Stop! The gate is opem, so do we hold this place?"))
        assertEquals("Keep your head down\nand stay close to me.",
            merge("Keep your heod down\nand stay clcse to me.", "Keep your head down\nand stay close to rne."))
    }

    @Test
    fun realWordsNamesNumbersAndPunctuationAreNeverReplaced() {
        assertEquals("Slop! The gate is open.", merge("Slop! The gate is open.", "Stop! The gate is open."))
        assertEquals("Markos: hold the gate, 12 of them!", merge("Markos: hold the gate, 12 of them!", "Marlos: hold the gate, 72 of them!"))
    }

    @Test
    fun readingsWithADifferentShapeAreIgnored() {
        assertEquals("Hold the gafe open", merge("Hold the gafe open", "Hold the gate"))
        assertEquals("Hold the gafe", merge("Hold the gafe", "Hold the gate\nand stay"))
        assertEquals("Hold the gafe", merge("Hold the gafe", "Hold the place"))
        assertEquals("Hold the gafe", ReadingConsensus.merge("Hold the gafe", listOf("Hold the gafe", "Hold the gate"), null))
        assertEquals("Hold the gafe", ReadingConsensus.merge("Hold the gafe", listOf("Hold the gafe"), words))
    }

    /** Hat: kararlı olana kadar gelen okumalar birleşir, satır onarılmış hâliyle çevrilir. */
    @Test
    fun pipelineMergesTheReadingsOfAnUnsettledLine() {
        var now = 0L
        val sent = mutableListOf<String>()
        val sink = object : SubtitleSink {
            override fun show(text: String, durationMs: Int, box: TextBox?, replaces: Boolean) {}
            override fun clear() {}
        }
        val pipeline = SubtitlePipeline({ text -> sent.add(text); "tr" }, sink, Executor { it.run() }, SubtitleStabilizer(clock = { now }))
        pipeline.wordCheck = { word -> word.lowercase(Locale.ROOT) in setOf("hold", "the", "gate", "open", "now") }
        // Satır cümle sonu işaretiyle bitmiyor: iki aynı okuma gelene kadar kararlı sayılmaz.
        for (frame in listOf("Hold the gate opem now", "Hold the gafe open now", "Hold the gafe open now")) {
            pipeline.onRead(listOf(frame), null)
            now += 100
        }
        assertEquals(listOf("Hold the gate open now"), sent)
    }
}

/** Yinelenen arayüz yazıları (Windows: RepeatedTextTests). */
class RepeatedTextTest {
    @Test
    fun lineShownThreeTimesWithoutSentenceEndIsInterface() {
        var time = 0L
        val repeats = RepeatedText { time }
        val bar = "Pick up Select Actions Sort Tooltips Close"
        assertNull(repeats.note(bar))
        time += 8000
        assertNull(repeats.note(bar))
        assertEquals(listOf(bar), repeats.without(listOf(bar)))
        time += 8000
        assertEquals(bar, repeats.note(bar))
        assertEquals(emptyList<String>(), repeats.without(listOf(bar)))
        assertEquals(listOf("We must go to the helm."), repeats.without(listOf(bar, "We must go to the helm.")))
    }

    @Test
    fun uppercaseBillboardIsBlockedButSpokenLinesMayRepeat() {
        var time = 0L
        val repeats = RepeatedText { time }
        repeat(RepeatedText.TIMES) {
            repeats.note("GET YOURS: TODAY!")
            time += 3000
        }
        assertEquals(emptyList<String>(), repeats.without(listOf("GET YOURS: TODAY!")))
        for (line in listOf("Behind you!", "Mimir: Behind you", "Hold the line.")) {
            repeat(6) {
                assertNull(repeats.note(line))
                time += 3000
            }
            assertEquals(listOf(line), repeats.without(listOf(line)))
        }
    }

    @Test
    fun repeatsFarApartDoNotCountAndBlockEnds() {
        var time = 0L
        val repeats = RepeatedText { time }
        repeat(6) {
            assertNull(repeats.note("Bonus Action Movement"))
            time += RepeatedText.WINDOW_MS
        }
        repeat(RepeatedText.TIMES) { repeats.note("Talus used Main Hand\nLine number $it is new.") }
        assertEquals(listOf("Line number 9 is new."), repeats.without(listOf("Talus used Main Hand", "Line number 9 is new.")))
        time += RepeatedText.BLOCK_MS + 1
        assertEquals(listOf("Talus used Main Hand"), repeats.without(listOf("Talus used Main Hand")))
    }

    /** Hat: üç kez gösterilen arayüz satırı artık gösterilmez. */
    @Test
    fun pipelineStopsShowingRepeatedInterfaceText() {
        var now = 0L
        var shown = 0
        val sink = object : SubtitleSink {
            override fun show(text: String, durationMs: Int, box: TextBox?, replaces: Boolean) { shown++ }
            override fun clear() {}
        }
        val pipeline = SubtitlePipeline({ "çubuk" }, sink, Executor { it.run() }, SubtitleStabilizer(clock = { now }))
        pipeline.repeats = RepeatedText { now }
        repeat(5) {
            repeat(3) {
                pipeline.onRead(listOf("Pick up Select Actions Sort Close"), null)
                now += 100
            }
            // Bölge boşalır, satır sonra yeniden belirir (yeniden gösterme süresinden sonra).
            repeat(20) {
                pipeline.onRead(emptyList(), null)
                now += 200
            }
        }
        assertEquals(RepeatedText.TIMES, shown)
    }
}

/** Konuşmacı adları (Windows: SpeakerNamesTests). */
class SpeakerNamesTest {
    private fun knowing(name: String, times: Int) = SpeakerNames().also { names -> repeat(times) { names.canonical(name) } }

    @Test
    fun misreadNameBecomesTheNameSeenOften() {
        for ((known, misread) in listOf("Mimir" to "Minnir", "Mimir" to "Mimnir", "Mimir" to "Mimire", "Kratos" to "Kracos",
            "Lae'zel" to "Lael-zel", "Flint Marko" to "Flint Marka"))
            assertEquals(known, knowing(known, 5).canonical(misread))
    }

    @Test
    fun unknownShortAndNotYetKnownNamesStayAsRead() {
        val names = knowing("Mimir", 5)
        assertEquals("Kratos", names.canonical("Kratos"))
        assertEquals("Mimir Smith", names.canonical("Mimir Smith"))
        assertEquals("Sam", knowing("Dan", 5).canonical("Sam"))
        assertEquals("Minnir", knowing("Mimir", SpeakerNames.KNOWN_AFTER - 1).canonical("Minnir"))
    }

    @Test
    fun twoRealSimilarNamesBothSurviveAndOftenMisreadIsCorrected() {
        val names = knowing("Maria", 6)
        repeat(2) { names.canonical("Marie") }
        assertEquals("Marie", names.canonical("Marie"))
        assertEquals("Maria", names.canonical("Maria"))
        val often = SpeakerNames()
        for (i in 0 until 20) {
            often.canonical("Mimir")
            if (i >= 4 && i % 5 == 4) assertEquals("Mimir", often.canonical("Minnir"))
        }
    }

    @Test
    fun confirmedNameWinsOverTheCommonMisreading() {
        val names = knowing("Alou", 10)
        assertTrue(names.needsCheck("Alou: Let's go."))
        names.confirm(listOf("Aloy: Let's go."))
        names.confirm(listOf("Aloy: We're clear.", "Varl: Good."))
        assertFalse(names.needsCheck("Alou: Let's go."))
        assertEquals("Aloy", names.canonical("Alou"))
        assertFalse(names.needsCheck("We're clear."))
    }

    @Test
    fun labelsAreTidied() {
        assertEquals("GAETANO: Worries are for tomorrow.", SpeakerNames.withoutLeadingJunkLabel("HSS: GAETANO: Worries are for tomorrow."))
        assertEquals("ENZO: Here.", SpeakerNames.fixLabelCaps("ENzo: Here."))
        assertEquals("Mimir: Here.", SpeakerNames.fixLabelCaps("Mimir: Here."))
    }
}

/** Uygulamayla gelen İngilizce sözcük listesi (Windows: WindowsSpelling.InEnglishList). */
class EnglishWordsTest {
    @Test
    fun listAnswersWordsAndCommonForms() {
        val words = EnglishWords.of(listOf("dog", "run", "running", "spider", "man", "zebra", "apple"))
        assertTrue(words.isWord("Dog"))
        assertTrue(words.isWord("dog's"))
        assertTrue(words.isWord("runnin'"))
        assertTrue(words.isWord("runnin"))
        assertTrue(words.isWord("Spider-Man"))
        assertTrue(words.isWord("dog’s"))
        assertFalse(words.isWord("dogg"))
        assertFalse(words.isWord(""))
        assertTrue(words.contains("zebra"))
        assertTrue(words.contains("apple"))
    }

    /** Bilgisayar uygulamasının dosyası açılır ve okuma onarımına yeter. */
    @Test
    fun bundledListLoads() {
        val file = File(System.getProperty("gametranslator.englishWords") ?: return)
        val words = file.inputStream().use { EnglishWords.load(it) }
        assertTrue(words.size > 90_000)
        assertTrue(words.isWord("weapon"))
        assertFalse(words.isWord("nol"))
        assertEquals("Knives do not vanish.", OcrConfusions.repair("Knives do nol vanish.", words::isWord))
        assertEquals("And there is much to do.", OcrConfusions.repair("Andthereismuchtodo.", words::isWord))
    }
}
