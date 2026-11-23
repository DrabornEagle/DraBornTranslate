// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Otomatik altyazı algılama (Windows: SubtitleFinderTests). */
class SubtitleFinderTest {
    /** Ortası ([x], [y]) olan, genişliği metne göre, yüksekliği %2,5 olan satır. */
    private fun line(text: String, y: Double, x: Double = 0.5): TextLine {
        val half = minOf(0.45, text.length * 0.006)
        return TextLine(text, TextBox(x - half, y - 0.0125, x + half, y + 0.0125))
    }

    private fun verdict(finder: SubtitleFinder, now: Long, vararg lines: TextLine) = finder.classify(lines.toList(), now).last().verdict

    private fun inside(value: Double, band: Pair<Double, Double>) = value >= band.first && value <= band.second

    /** Konuşmacısız dört altyazıyla ([y] yüksekliğinde) şeridi öğrenmiş bulucu; son kare 9. saniyededir. */
    private fun learned(y: Double = 0.88): SubtitleFinder {
        val finder = SubtitleFinder()
        finder.classify(listOf(line("Where are you going?", y)), 0)
        finder.classify(listOf(line("We need to move, now.", y)), 3000)
        finder.classify(listOf(line("Stay close to me.", y)), 6000)
        finder.classify(listOf(line("Do not look back.", y)), 9000)
        assertNotNull(finder.band)
        return finder
    }

    @Test
    fun learnsTheBandFromTheFirstSubtitles() {
        val finder = SubtitleFinder()
        assertEquals(SubtitleFinder.Verdict.SUBTITLE, verdict(finder, 0, line("Where are you going?", 0.88)))
        finder.classify(listOf(line("We need to move, now.", 0.88)), 3000)
        finder.classify(listOf(line("Stay close to me.", 0.885)), 6000)
        assertNull(finder.band)
        finder.classify(listOf(line("Do not look back.", 0.88)), 9000)
        val band = finder.band!!
        assertTrue(band.first in 0.78..0.86)
        assertTrue(band.second in 0.90..0.98)
    }

    @Test
    fun speakerLinesTeachTheBandQuickly() {
        val inline = SubtitleFinder()
        inline.classify(listOf(line("Elena: Where are you going?", 0.88)), 0)
        assertNull(inline.band)
        inline.classify(listOf(line("Tomas: Away from here.", 0.88)), 2000)
        assertNotNull(inline.band)

        val above = SubtitleFinder()
        above.classify(listOf(line("ELENA", 0.79), line("I can tell from the look on your face", 0.82), line("that you were paid.", 0.85)), 0)
        val band = above.band!!
        assertTrue(inside(0.79, band) && inside(0.85, band))
    }

    @Test
    fun shortCombatTextDoesNotTeachTheBand() {
        val finder = SubtitleFinder()
        finder.classify(listOf(line("Shield Charge", 0.75)), 0)
        finder.classify(listOf(line("Glide up", 0.75)), 1000)
        finder.classify(listOf(line("Wings ready now", 0.75)), 2000)
        finder.classify(listOf(line("Hold to pull", 0.75)), 3000)
        assertNull(finder.band)
        assertEquals(SubtitleFinder.Verdict.NOT_SENTENCE, verdict(finder, 4000, line("Shield Charge", 0.75)))
    }

    @Test
    fun loadingScreenTipsDoNotKeepTheBandFromDialogue() {
        val finder = SubtitleFinder()
        finder.classify(listOf(line("Mercenaries are among the strongest fighters in the land.", 0.45)), 0)
        finder.classify(listOf(line("Hone your skills and climb their ranks.", 0.48)), 0)
        finder.classify(listOf(line("You can cancel a parry early by dodging.", 0.45)), 4000)
        finder.classify(listOf(line("Upgrade your ship to survive naval battles.", 0.45)), 8000)
        assertTrue(inside(0.45, finder.band!!))
        finder.classify(listOf(line("ELENA", 0.79), line("These bows will make me rich.", 0.82)), 12000)
        finder.classify(listOf(line("TOMAS", 0.79), line("They are that good, are they?", 0.82)), 15000)
        assertTrue(inside(0.82, finder.band!!))
        assertEquals(SubtitleFinder.Verdict.SUBTITLE, verdict(finder, 18000, line("War is coming, you know.", 0.82)))
    }

    @Test
    fun tipsDoNotMoveABandBuiltFromDialogue() {
        val finder = SubtitleFinder()
        finder.classify(listOf(line("Elena: Where are you going?", 0.88)), 0)
        finder.classify(listOf(line("Tomas: Away from here.", 0.88)), 2000)
        for (i in 0 until 8) finder.classify(listOf(line("Tip number $i: you can do many different things here.", 0.45)), 4000L + i * 2000)
        assertTrue(inside(0.88, finder.band!!))
        assertEquals(SubtitleFinder.Verdict.OUTSIDE_BAND, verdict(finder, 21000, line("One more piece of advice for you.", 0.45)))
    }

    @Test
    fun promptsCornerTextTitlesMenusAndStaticTextAreNotSubtitles() {
        val finder = learned()
        assertEquals(SubtitleFinder.Verdict.PROMPT, verdict(finder, 12000, line("R2 Swing", 0.88)))
        assertEquals(SubtitleFinder.Verdict.OFF_CENTER, verdict(finder, 12000, line("Find the water tank on the roof.", 0.88, x = 0.15)))
        assertEquals(SubtitleFinder.Verdict.NOT_SENTENCE, verdict(finder, 12000, line("RESET ALL TO DEFAULT", 0.88)))
        assertEquals(SubtitleFinder.Verdict.NOT_SENTENCE, verdict(finder, 12000, line("Back", 0.88)))
        assertEquals(SubtitleFinder.Verdict.SUBTITLE, verdict(finder, 12000, line("Run!", 0.88)))
        assertEquals(SubtitleFinder.Verdict.OUTSIDE_BAND, verdict(finder, 12000, line("You can sneak up on enemies to take them down quietly.", 0.45)))

        val objective = line("Reach the water tank before the others do.", 0.88)
        val still = learned()
        assertEquals(SubtitleFinder.Verdict.SUBTITLE, verdict(still, 10000, objective))
        assertEquals(SubtitleFinder.Verdict.SUBTITLE, verdict(still, 13000, objective))
        assertEquals(SubtitleFinder.Verdict.SUBTITLE, verdict(still, 16000, objective))
        assertEquals(SubtitleFinder.Verdict.STATIC, verdict(still, 10000 + SubtitleFinder.STATIC_AFTER_MS, objective))
        assertEquals(SubtitleFinder.Verdict.SUBTITLE, verdict(still, 40000, objective))

        val menu = SubtitleFinder().classify(listOf(line("Map", 0.40), line("Settings", 0.44), line("Photo Mode", 0.48), line("Quit", 0.52)), 0)
        assertTrue(menu.all { it.verdict == SubtitleFinder.Verdict.MENU })
    }

    @Test
    fun relearnsWhenSubtitlesMoveAndOldEvidenceExpires() {
        val finder = learned(0.88)
        var time = 12000L
        for (text in listOf("This game shows them at the top.", "Second line up here as well.", "And a third one to be sure.", "A fourth line follows it.",
            "Then a fifth line appears.", "A sixth one comes after that.", "The seventh line is here now.", "Now this is the subtitle band.")) {
            finder.classify(listOf(line(text, 0.10)), time)
            time += 3000
        }
        assertEquals(SubtitleFinder.Verdict.SUBTITLE, verdict(finder, time, line("Everything is read up here.", 0.10)))
        assertEquals(SubtitleFinder.Verdict.OUTSIDE_BAND, verdict(finder, time + 500, line("The old place is not, anymore.", 0.88)))

        val old = learned(0.88)
        val later = 9000 + SubtitleFinder.EVIDENCE_MS + 1000
        old.classify(listOf(line("Elena: It has been a long time.", 0.30)), later)
        old.classify(listOf(line("Tomas: Too long, I would say.", 0.30)), later + 3000)
        assertTrue(inside(0.30, old.band!!))
    }

    @Test
    fun subtitlesReturnsOnlyTheSubtitleLines() {
        val finder = learned()
        val lines = finder.subtitles(listOf(
            line("Objective updated: find the smuggler.", 0.20, x = 0.2),
            line("R2 Swing", 0.70),
            line("Elena: Only way is up.", 0.88)), 12000)
        assertEquals(listOf("Elena: Only way is up."), lines.map { it.text })
    }

    /** Geniş alanın okumasından altyazı bloğu: şeritteki satırlar ve onlara bitişik olanlar; köşe yazıları dışarıda. */
    @Test
    fun growKeepsTheBlockAroundTheBand() {
        val band = 0.84 to 0.92
        val lines = listOf(
            line("ELENA", 0.82),
            line("According to the guards, he was running", 0.85),
            line("down the harbor road, shouting nonsense", 0.88),
            line("and nobody could stop him.", 0.91),
            line("Call Horse Press Twice", 0.88, x = 0.85),
            line("Objective: find the smuggler.", 0.30))
        assertEquals(listOf("ELENA", "According to the guards, he was running", "down the harbor road, shouting nonsense", "and nobody could stop him."),
            SubtitleFinder.grow(lines, band).map { it.text })
        assertEquals(emptyList<String>(), SubtitleFinder.grow(listOf(line("Objective: find the smuggler.", 0.30)), band).map { it.text })
        assertEquals(listOf("Centered line here."),
            SubtitleFinder.withoutOffCenterLines(listOf(line("Centered line here.", 0.9), line("Corner", 0.9, x = 0.9))).map { it.text })
    }

    @Test
    fun buttonPromptsAreRecognised() {
        for (prompt in listOf("R2 Swing", "△ Pick up", "L1 + R1 Spartan Rage", "Press ✕ to jump", "PRESS e TO CONTINUE", "X Interact",
            "PHOTO MODE L1 + R1", "Movement 1.8", "RI (Hold) Pull"))
            assertTrue(prompt, ButtonPrompts.isPrompt(prompt))
        for (speech in listOf("Li, come here.", "Press on, brothers!", "Where are you going?", "X marks the spot."))
            assertFalse(speech, ButtonPrompts.isPrompt(speech))
    }
}
