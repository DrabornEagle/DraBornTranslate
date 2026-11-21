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
            packLookup = { PackTranslation.match(library, it) },
            model = { error("Pack hits must not call the model") },
        )
        val cue = pipeline.translate(TranslationInput("- Go downstairs.\n- Shut the window!"))
        assertEquals(TranslationReply("- Aşağı in.\n- Pencereyi kapat!"), cue)
    }

    @Test
    fun splitSentencesAvoidMissingDialogueAndKeepSeparateSpeakers() {
        val calls = mutableListOf<String>()
        val pipeline = GameTranslationPipeline(packLookup = { null }, model = { text ->
            calls.add(text)
            when (text) {
                "We need more fuel." -> "Biraz daha yakıt lazım."
                "Where's the truck?" -> "Kamyon nerede."
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
        val pipeline = GameTranslationPipeline(packLookup = { null }, model = { text ->
            assertEquals("We need to stay hidden until things calm down.", text)
            "Ortalık sakinleşene kadar saklanmalıyız. Ortalık sakinleşene kadar saklanmalıyız."
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
            model = { text ->
                assertEquals("Change the radio station", text)
                "Radyo istasyonunu değiştir"
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
    fun menuValuesAndControllerHintsKeepNumbers() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, model = { error(it) })
        val menu = "Save Slot: 4\nInventory: 5/16\nDisplay Resolution: 1920 x 1080\nPress and hold L2 to aim."
        val expected = "Kayıt yuvası: 4\nEnvanter: 5/16\nEkran çözünürlüğü: 1920 x 1080\nNişan almak için L2'yi basılı tut."

        assertEquals(expected, pipeline.translate(TranslationInput(menu, ReadingArea.GAME_TEXT)).text)
    }

    @Test
    fun disguiseIdiomPreservesItsMeaningAndNegation() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, model = { text ->
            assertEquals("Don't reveal your real identity.", text)
            "Kimliğini açığa çıkarma."
        })

        assertEquals("Kimliğini açığa çıkarma.", pipeline.translate(TranslationInput("Don’t blow your cover.")).text)
    }

    @Test
    fun questionsDoNotUseAnAffirmativePackMatch() {
        val pipeline = GameTranslationPipeline(
            packLookup = { PackMatch("Güvendeyiz.") },
            model = { "Güvende miyiz." },
        )

        assertEquals("Güvende miyiz?", pipeline.translate(TranslationInput("We're safe?")).text)
    }

    @Test
    fun doesNotDisplayOnlyFirstSentenceOfAMultiSentenceSubtitleWhenTheModelFails() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, model = { null })
        assertNull(pipeline.translate(TranslationInput("Take cover! I will look for a way around the building.")).text)
    }
}
