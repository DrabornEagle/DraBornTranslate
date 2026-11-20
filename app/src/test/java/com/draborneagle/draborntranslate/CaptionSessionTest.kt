// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.SubtitleStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionSessionTest {
    private var now = 100L
    private var caption: Caption? = null
    private val requests = ArrayDeque<Pair<String, (String?) -> Unit>>()
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
        requests.removeFirst().second("Köprüye git!")
        assertNull(caption)
        assertEquals("Close the gate.", requests.first().first)
        requests.removeFirst().second("Kapıyı kapat.")
        assertEquals(Caption("Close the gate.", "Kapıyı kapat."), caption)
    }

    @Test
    fun subtitleIsRemovedWhenSourceDisappears() {
        session.read(listOf("Follow me."))
        requests.removeFirst().second("Beni takip et.")
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
        requests.removeFirst().second("Beni takip et.")
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
        requests.removeFirst().second("Gitmeye hazır mısın?")
        assertNull(caption)
    }
}
