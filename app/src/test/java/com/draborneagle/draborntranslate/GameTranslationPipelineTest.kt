// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.GamePackLibrary
import com.gametranslator.gametranslator_mobile.core.PackKey
import com.gametranslator.gametranslator_mobile.core.PackTranslation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameTranslationPipelineTest {
    @Test
    fun compatibleGamePackKeepsSpeakerMarksAndSkipsBothEngines() {
        val pack = JSONObject().put("id", "sample").put("format", 1)
            .put("entries", JSONObject()
                .put(checkNotNull(PackKey.key("Go downstairs.")), "Aşağı in.")
                .put(checkNotNull(PackKey.key("Shut the window!")), "Pencereyi kapat!"))
        val library = GamePackLibrary({ listOf("sample.json" to { pack.toString().toByteArray() }) }, null)
        val pipeline = GameTranslationPipeline(
            packLookup = { PackTranslation.match(library, it) },
            online = { error("Pack hits must not call the online engine") },
            offline = { error("Pack hits must not call the local model") },
        )
        val cue = pipeline.translate(TranslationInput("- Go downstairs.\n- Shut the window!"))
        assertEquals(TranslationReply("- Aşağı in.\n- Pencereyi kapat!", googleAttribution = false), cue)
    }

    @Test
    fun fallsBackToTheDeviceWhenTheKeylessEngineDoesNotRespond() {
        val calls = mutableListOf<String>()
        val pipeline = GameTranslationPipeline(
            packLookup = { null },
            online = { calls.add("online"); null },
            offline = { calls.add("offline"); "Biraz daha yakıt lazım." },
        )
        assertEquals(TranslationReply(">> Biraz daha yakıt lazım."), pipeline.translate(TranslationInput(">> We need more fuel.")))
        assertEquals(listOf("online", "offline"), calls)
    }

    @Test
    fun commonDialogueAndMenusKeepTheirOwnLineStructure() {
        val pipeline = GameTranslationPipeline(
            packLookup = { null },
            online = null,
            offline = { text ->
                assertEquals("Settings\nResume", text)
                "Ayarlar\nDevam et"
            },
        )
        assertEquals(
            TranslationReply("Takip ediliyoruz. Polisleri atlat!", googleAttribution = false),
            pipeline.translate(TranslationInput("We're being tailed. Lose the cops!")),
        )
        assertEquals("Ayarlar\nDevam et", pipeline.translate(TranslationInput("Settings\nResume", ReadingArea.GAME_TEXT)).text)
    }

    @Test
    fun acceptsBothResponseShapesUsedByGameTranslator() {
        assertEquals("Köprüye git!", parseGoogleTranslation("""[[["Köprüye ","To the "],["git!","bridge!"]]]"""))
        assertEquals("Siper al!", parseGoogleTranslation(")]}'\n[\"Siper al!\"]"))
        assertNull(parseGoogleTranslation("[null]"))
    }
}
