// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.GamePackLibrary
import com.gametranslator.gametranslator_mobile.core.PackKey
import com.gametranslator.gametranslator_mobile.core.PackMatch
import com.gametranslator.gametranslator_mobile.core.PackTranslation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameTranslationPipelineTest {
    @Test
    fun compatibleGamePackKeepsSpeakerMarksAndSkipsTheModel() {
        val pack = JSONObject().put("id", "sample").put("format", 1)
            .put("entries", JSONObject()
                .put(checkNotNull(PackKey.key("Go downstairs.")), "Aşağı in.")
                .put(checkNotNull(PackKey.key("Shut the window!")), "Pencereyi kapat!"))
        val library = GamePackLibrary({ listOf("sample.json" to { pack.toString().toByteArray() }) }, null)
        val pipeline = GameTranslationPipeline(
            packLookup = { PackTranslation.match(it, library::findWithPack) },
            candidates = { error("Pack hits must not call the model") },
        )
        val cue = pipeline.translate(TranslationInput("- Go downstairs.\n- Shut the window!"))
        assertEquals(TranslationReply("- Aşağı in.\n- Pencereyi kapat!"), cue)
    }

    @Test
    fun splitSentencesAvoidMissingDialogueAndKeepSeparateSpeakers() {
        val calls = mutableListOf<String>()
        val pipeline = GameTranslationPipeline(packLookup = { null }, candidates = { text ->
            calls.add(text)
            when (text) {
                "We need more fuel." -> listOf("Biraz daha yakıt lazım.")
                "Where's the truck?" -> listOf("Kamyon nerede.")
                else -> error(text)
            }
        })
        val input = ">> LUCIA: We need more\nfuel.\n- JASON: Where’s the truck?"
        val output = ">> LUCIA: Biraz daha yakıt lazım.\n- JASON: Kamyon nerede?"
        assertEquals(TranslationReply(output), pipeline.translate(TranslationInput(input)))
        assertEquals(listOf("We need more fuel.", "Where's the truck?"), calls)
    }

    @Test
    fun normalizesGameIdiomsAndAvoidsDuplicateModelOutput() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, candidates = { text ->
            assertEquals("We need to stay hidden until things calm down.", text)
            listOf("Ortalık sakinleşene kadar saklanmalıyız. Ortalık sakinleşene kadar saklanmalıyız.")
        })
        assertEquals(
            "Ortalık sakinleşene kadar saklanmalıyız.",
            pipeline.translate(TranslationInput("We need to lay low until the heat dies down.")).text,
        )
    }

    @Test
    fun translatesMenuLabelsSeparatelyAndPreservesKnownLines() {
        val pipeline = GameTranslationPipeline(
            packLookup = { null },
            candidates = { text ->
                assertEquals("Change the radio station", text)
                listOf("Radyo istasyonunu değiştir")
            },
        )
        assertEquals(
            "Takip ediliyoruz. Polisleri atlat!",
            pipeline.translate(TranslationInput("We're being tailed. Lose the cops!")).text,
        )
        assertEquals(
            "Ayarlar\nRadyo istasyonunu değiştir\nOyuna dön",
            pipeline.translate(TranslationInput("Settings\nChange the radio station\nResume", ReadingArea.GAME_TEXT)).text,
        )
    }

    @Test
    fun doesNotDisplayOnlyFirstSentenceOfAMultiSentenceSubtitleWhenTheModelFails() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, candidates = { emptyList() })
        assertNull(pipeline.translate(TranslationInput("Take cover! I will look for a way around the building.")).text)
    }

    @Test
    fun rejectsCandidatesWithMissingOrChangedNamesAndNumbers() {
        val pipeline = GameTranslationPipeline({ null }) { text ->
            assertEquals("Meet Lucia at pier 7 at 11:45.", text)
            listOf(
                "Lucy ile 7. iskelede saat 11:45'te buluş.",
                "Lucia ile 9. iskelede saat 11:45'te buluş.",
                "Lucia ile 7. iskelede buluş.",
                "Lucia ile 7. iskelede saat 11:45'te buluş.",
            )
        }
        assertEquals(
            "Lucia ile 7. iskelede saat 11:45'te buluş.",
            pipeline.translate(TranslationInput("MEET LUCIA AT PIER 7 AT 11:45.")).text,
        )
    }

    @Test
    fun ignoresAStatementStoredUnderTheSamePackKeyAsAQuestion() {
        val pipeline = GameTranslationPipeline({ PackMatch("Güvendeyiz.") }) {
            listOf("Güvende miyiz")
        }
        assertEquals("Güvende miyiz?", pipeline.translate(TranslationInput("We're safe?")).text)
    }

    @Test
    fun menuHeadersRetainValuesAndPlusSigns() {
        val pipeline = GameTranslationPipeline({ null }) { error("These labels translate without the model") }
        assertEquals(
            "Envanter: 3/12\nYeni Oyun+\nKayıt yuvası: 2",
            pipeline.translate(TranslationInput("Inventory: 3/12\nNew Game+\nSave Slot: 2", ReadingArea.GAME_TEXT)).text,
        )
        assertEquals(
            "Depar atmak için L3'e bas.",
            pipeline.translate(TranslationInput("Tap L3 to sprint.", ReadingArea.GAME_TEXT)).text,
        )
    }

    @Test
    fun keepsContractionsAndNumbersWhenRepairingOcr() {
        assertEquals("I'll leave 3 cars here. Does R2-D2 know?", cleanOcrLine("l’ll leave 3 cars here. Does R2-D2 know ?"))
        assertEquals("3 More cars are coming.", cleanOcrLine(" 3  More cars are coming. "))
    }
}
