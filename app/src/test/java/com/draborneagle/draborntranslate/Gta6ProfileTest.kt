// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.PackCaptionContinuity
import com.gametranslator.gametranslator_mobile.core.PackMatch
import com.gametranslator.gametranslator_mobile.core.SubtitlePipeline
import com.gametranslator.gametranslator_mobile.core.SubtitleSink
import com.gametranslator.gametranslator_mobile.core.SubtitleStabilizer
import com.gametranslator.gametranslator_mobile.core.TextBox
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gta6ProfileTest {
    @Test
    fun sendsHudAndSubtitleToSeparateRegions() {
        val dialogue = line("Kolt & Associates: Are you there?", 0.32, 0.83, 0.68)
        val lines = listOf(
            dialogue,
            line("Drive to the marker", 0.37, 0.94, 0.64),
            line("Check the map for details.", 0.04, 0.30, 0.21),
            line("Avery: Meet me later.", 0.79, 0.82, 0.96),
            line("✕ SELECT ○ BACK", 0.75, 0.96, 0.98),
            line("VICE HARDWARE", 0.28, 0.79, 0.40),
        )
        val result = Gta6Profile.read(lines, lines)
        assertEquals(listOf(dialogue), result.subtitles)
        assertEquals(setOf(HudKind.OBJECTIVE, HudKind.HELP, HudKind.MESSAGE, HudKind.CONTROLS), result.hud.map { it.kind }.toSet())
        assertEquals("Avery", result.hud.first { it.kind == HudKind.MESSAGE }.title)
        assertTrue(result.hud.first { it.kind == HudKind.CONTROLS }.lines.joinToString { it.text }.contains("Seç"))
    }

    @Test
    fun recognizesGroupChatAndPreservesNamesAndReplies() {
        val lines = listOf(
            line("Kolt & Associates", 0.74, 0.31, 0.92),
            line("Avery: Can you stop by?", 0.71, 0.41, 0.94),
            line("Sure, see you soon.", 0.80, 0.52, 0.98),
            line("LATER?", 0.76, 0.76, 0.89),
            line("✕ SEND ○ EXIT", 0.81, 0.95, 0.98),
        )
        val result = Gta6Profile.read(lines, lines)
        val phone = result.hud.single { it.kind == HudKind.PHONE }
        assertEquals("Kolt & Associates", phone.title)
        assertEquals(listOf(HudLineStyle.NAME, HudLineStyle.INCOMING, HudLineStyle.OUTGOING, HudLineStyle.REPLY),
            phone.lines.map { it.style })
        assertTrue(result.subtitles.isEmpty())
        assertEquals("Polis: Buraya gel.", Gta6Profile.localizeSpeaker("Cop: Buraya gel."))
        assertEquals("Kolt & Associates: Anlaştık.", Gta6Profile.localizeSpeaker("Kolt & Associates: Anlaştık."))
    }

    @Test
    fun waitsOnDisagreeingReadingsBeforeModelTranslation() {
        val one = line("Take the left exit.", 0.38, 0.82, 0.62)
        val two = one.copy(text = "Take the right exit.")
        assertFalse(Gta6Profile.read(listOf(one), listOf(two)).allowFallback)
        assertTrue(Gta6Profile.read(listOf(one), listOf(one)).allowFallback)
    }

    @Test
    fun packContinuityKeepsBrokenLettersButReleasesRealChanges() {
        assertTrue(PackCaptionContinuity.isReread("Whaf's Wro-ng with you?", "What's wrong with you?"))
        assertFalse(PackCaptionContinuity.isReread("Take the right exit.", "Take the left exit."))
        assertFalse(PackCaptionContinuity.isReread("Take the exit at 10.", "Take the exit at 11."))
        assertFalse(PackCaptionContinuity.isReread("Avery: Are you okay?", "Cal: Are you okay?"))
        assertFalse(PackCaptionContinuity.isReread("We are ready to go.", "We are ready."))
    }

    @Test
    fun packedCaptionDoesNotReachTheFallbackOnEitherLivePipeline() {
        val source = "What's wrong with you?"
        val broken = "Whaf's Wro-ng with you?"
        val packed = "Neyin var?"
        var now = 10L
        val requests = mutableListOf<String>()
        var shown: String? = null
        val session = CaptionSession(
            translate = { input, done -> requests.add(input.text); done(TranslationReply("Kapıyı kapat.")) },
            show = { shown = it?.turkish },
            onFailure = {},
            stabilizer = SubtitleStabilizer(clock = { now }),
            lookup = { input, done -> done(if (input.text == source) TranslationReply(packed, fromPack = true) else null) },
        )
        val second = SubtitlePipeline({ text -> requests.add(text); "Kapıyı kapat." }, object : SubtitleSink {
            override fun show(text: String, durationMs: Int, box: TextBox?, replaces: Boolean) { shown = text }
            override fun clear() { shown = null }
        }, Executor { it.run() }, SubtitleStabilizer(clock = { now }))
        second.packLookup = { if (it == source) PackMatch(packed, "gta-6") else null }
        fun assertFlow(read: (List<String>) -> Unit) {
            requests.clear()
            read(listOf(source))
            now += 350
            assertEquals(packed, shown)
            repeat(3) {
                read(listOf(broken))
                now += 350
            }
            assertEquals(packed, shown)
            assertTrue(requests.isEmpty())
            read(listOf("Close the door."))
            assertEquals(listOf("Close the door."), requests)
            assertEquals("Kapıyı kapat.", shown)
        }
        assertFlow { session.read(it) }
        assertFlow { second.onRead(it, null) }
    }

    private fun line(text: String, left: Double, top: Double, right: Double) =
        ProfileLine(text, TextBox(left, top, right, top + 0.022))
}
