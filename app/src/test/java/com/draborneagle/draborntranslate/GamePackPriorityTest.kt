// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.GamePackLibrary
import com.gametranslator.gametranslator_mobile.core.PackKey
import com.gametranslator.gametranslator_mobile.core.PackLookup
import com.gametranslator.gametranslator_mobile.core.SubtitleStabilizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePackPriorityTest {
    @Test
    fun keepsACompleteLabelInThePackLookup() {
        val key = checkNotNull(PackKey.key("Kolt & Associates:"))
        val content = JSONObject().put(key, "Kolt ve Ortakları:").toString().toByteArray()
        val packs = GamePackLibrary({ listOf("gta-6.json" to { content }) }, null)
        assertEquals("Kolt ve Ortakları:", PackLookup.match("Kolt & Associates:", find = packs::find)?.text)
    }

    @Test
    fun readyPackResultCanOvertakeAnEarlierModelRequest() {
        val captions = mutableListOf<Caption>()
        var older: ((TranslationReply) -> Unit)? = null
        val session = CaptionSession(
            translate = { _, done -> older = done },
            show = { if (it != null) captions.add(it) },
            onFailure = { error("Unexpected failure: $it") },
            stabilizer = SubtitleStabilizer(stableFrames = 1, exactText = true, minTextLength = 2),
            lookup = { input, done ->
                done(if ("Kolt" in input.text) TranslationReply("Kolt ve Ortakları:") else null)
            },
        )
        repeat(2) { session.read(listOf("An older line is still being translated.")) }
        assertTrue(older != null)
        repeat(2) { session.read(listOf("Kolt & Associates:")) }
        assertEquals("Kolt ve Ortakları:", captions.last().turkish)
        older?.invoke(TranslationReply("Artık ekranda olmayan satır."))
        assertEquals(1, captions.size)
    }
}
