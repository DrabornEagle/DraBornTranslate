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
    fun statementsDoNotUseAPackMatchThatIsAQuestion() {
        val pipeline = GameTranslationPipeline(
            packLookup = { PackMatch("Şimdi çıkıyor muyuz?") },
            model = { "Şimdi çıkıyoruz." },
        )
        assertEquals("Şimdi çıkıyoruz.", pipeline.translate(TranslationInput("We're leaving now.")).text)
    }

    @Test
    fun shortAnswersUseTheQuestionInTheSameSubtitle() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, model = { text ->
            when (text) {
                "Who's got the keys?" -> "Anahtarlar kimde?"
                "Who opened the door?" -> "Kapıyı kim açtı?"
                "Who has to drive?" -> "Kim sürmek zorunda?"
                "I do." -> "Ben sürmeliyim."
                else -> error(text)
            }
        })
        assertEquals("Anahtarlar kimde? Bende.", pipeline.translate(TranslationInput("Who's got the keys? I do.")).text)
        assertEquals("Kim sürmek zorunda? Ben sürmeliyim.", pipeline.translate(TranslationInput("Who has to drive? I do.")).text)
        assertEquals(
            "LUCIA: Kapıyı kim açtı?\nJASON: Ben.",
            pipeline.translate(TranslationInput("LUCIA: Who opened the door?\nJASON: I did.")).text,
        )
    }

    @Test
    fun briefAnswerExpandsTheActionFromTheQuestion() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, model = { text ->
            when (text) {
                "Did you tell her what happened?" -> "Ona olanları anlattın mı?"
                "No, I didn't tell her what happened." -> "Hayır, anlatmadım."
                else -> error(text)
            }
        })
        val together = TranslationInput("Did you tell her what happened? No, I didn’t.")
        val separate = TranslationInput("No, I didn’t.", previousSubtitle = "Did you tell her what happened?")
        assertEquals("Ona olanları anlattın mı? Hayır, anlatmadım.", pipeline.translate(together).text)
        assertEquals("Hayır, anlatmadım.", pipeline.translate(separate).text)
    }

    @Test
    fun contextDoesNotChangeWhoIsBeingDiscussed() {
        val earlier = "Did you tell her where I live?"
        assertEquals("No, I didn’t.", DialogueContext.expand("No, I didn’t.", earlier))
        assertEquals("He can drive.", DialogueContext.expand("He can drive.", "Could you call her?"))
        assertEquals(
            "The light stays on even when the garage is empty.",
            DialogueContext.expand("The light stays on even when it is.", "The garage is empty."),
        )
    }

    @Test
    fun dialogueIdiomsAreClarifiedBeforeModelTranslation() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, model = { text ->
            when (text) {
                "He needs some help carrying the boxes." -> "Kutuları taşımak için yardıma ihtiyacı var."
                "It is unlikely to work, but we have to try." -> "İşe yarama ihtimali düşük ama denemeliyiz."
                else -> error(text)
            }
        })
        assertEquals(
            "Kutuları taşımak için yardıma ihtiyacı var.",
            pipeline.translate(TranslationInput("He could use a hand carrying the boxes.")).text,
        )
        assertEquals(
            "İşe yarama ihtimali düşük ama denemeliyiz.",
            pipeline.translate(TranslationInput("It's a long shot, but we have to try.")).text,
        )
    }

    @Test
    fun directionalReferencesKeepTheDoorAndSide() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, model = { text ->
            when (text) {
                "Go through the door on the left." -> "Soldaki kapıdan geç."
                "Actually, go through the door on the right." -> "Daha doğrusu sağdaki kapıdan geç."
                else -> error(text)
            }
        })
        assertEquals(
            "Soldaki kapıdan geç. Daha doğrusu sağdaki kapıdan geç.",
            pipeline.translate(TranslationInput("Take the left door. Actually, take the right one.")).text,
        )
    }

    @Test
    fun meridiemAndPossessiveGameTermsStayUnambiguous() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, model = { text ->
            when (text) {
                "The call is at 00:30." -> "Arama saat 00.30'da."
                "Zarmon is almost empty." -> "Zarmon neredeyse boş."
                else -> error(text)
            }
        })
        assertEquals(
            "Arama saat 00.30'da.",
            pipeline.translate(TranslationInput("The call is at 12:30 a.m.")).text,
        )
        assertEquals(
            "Can çubuğum neredeyse boş.",
            pipeline.translate(TranslationInput("My health bar is almost empty.")).text,
        )
    }

    @Test
    fun doesNotDisplayOnlyFirstSentenceOfAMultiSentenceSubtitleWhenTheModelFails() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, model = { null })
        assertNull(pipeline.translate(TranslationInput("Take cover! I will look for a way around the building.")).text)
    }
}
