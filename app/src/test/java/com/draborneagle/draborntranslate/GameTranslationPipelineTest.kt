// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.GamePackLibrary
import com.gametranslator.gametranslator_mobile.core.PackKey
import com.gametranslator.gametranslator_mobile.core.PackMatch
import com.gametranslator.gametranslator_mobile.core.PackTranslation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            translateModel = { error("Pack hits must not call the model") },
        )
        val cue = pipeline.translate(TranslationInput("- Go downstairs.\n- Shut the window!"))
        assertEquals(TranslationReply("- Aşağı in.\n- Pencereyi kapat!"), cue)
    }

    @Test
    fun keepsSentencesFromTheSameSpeakerTogetherButPreservesAttribution() {
        val calls = mutableListOf<String>()
        val pipeline = GameTranslationPipeline(packLookup = { null }, translateModel = { request ->
            calls.add(request.text)
            when (request.text) {
                "We need more fuel. The truck is empty." -> "Biraz daha yakıt lazım. Kamyonun deposu boş."
                "Where's the truck?" -> "Kamyon nerede."
                else -> error(request.text)
            }
        })
        val input = ">> LUCIA: We need\nmore fuel. The truck is empty.\n- JASON: Where’s the truck?"
        val output = ">> LUCIA: Biraz daha yakıt lazım. Kamyonun deposu boş.\n- JASON: Kamyon nerede?"
        assertEquals(TranslationReply(output), pipeline.translate(TranslationInput(input)))
        assertEquals(listOf("We need more fuel. The truck is empty.", "Where's the truck?"), calls)
    }

    @Test
    fun fallsBackBySentenceIfAWholeTurnLosesAQuantity() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, translateModel = { request ->
            when (request.text) {
                "We've got 20 minutes. Don't start without me." -> "On iki dakikamız var. Bensiz başlama."
                "We've got 20 minutes." -> "Yirmi dakikamız var."
                "Don't start without me." -> "Benim olmadan başlama."
                else -> error(request.text)
            }
        })
        assertEquals("Yirmi dakikamız var. Ben olmadan başlama.",
            pipeline.translate(TranslationInput("We've got twenty minutes. Don't start without me.")).text)
    }

    @Test
    fun preservesQuantitiesClockTimesAndMenuProgress() {
        assertTrue(preservesSubtitleNumbers("We have twenty-one minutes.", "Yirmi bir dakikamız var."))
        assertTrue(preservesSubtitleNumbers("Meet at 11:45.", "11.45'te buluşalım."))
        assertTrue(preservesSubtitleNumbers("There's $2,500 here.", "Burada 2.500 dolar var."))
        assertFalse(preservesSubtitleNumbers("Inventory 3/12", "Envanter 12/3"))
        assertFalse(preservesSubtitleNumbers("We have twenty minutes.", "On iki dakikamız var."))
        assertFalse(preservesSubtitleNumbers("See you at 11:45.", "10.45'te görüşürüz."))
        assertEquals("Wait one hundred and twenty seconds.", digitizeSubtitleQuantities("Wait one hundred and twenty seconds."))
    }

    @Test
    fun alsoTranslatesMenusWithoutChangingTheirOrder() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, translateModel = { request ->
            assertEquals(TranslationInput("Change the radio station", ReadingArea.GAME_TEXT), request)
            "Radyo istasyonunu değiştir"
        })
        assertEquals(
            "Ayarlar\nRadyo istasyonunu değiştir\nOyuna dön\nEnvanter: 3/12\nYeni Oyun+",
            pipeline.translate(TranslationInput(
                "Settings\nChange the radio station\nResume\nInventory: 3/12\nNew Game+", ReadingArea.GAME_TEXT,
            )).text,
        )
    }

    @Test
    fun doesNotPublishAnIncompleteTurnIfTheModelFails() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, translateModel = { null })
        assertNull(pipeline.translate(TranslationInput("Take cover! I will look for a way around the building.")).text)
    }

    @Test
    fun liveDialogueKeepsVerifiedSentencesAndMarksAMissingOne() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, translateModel = { request ->
            if (request.text == "We have 20 minutes.") "On iki dakikamız var." else null
        })
        val reply = pipeline.translate(TranslationInput("Take cover! We have 20 minutes.", allowPartial = true))
        assertEquals("Siper al! […]", reply.text)
        assertEquals("Repliğin bir bölümü çevrilemedi.", reply.issue)
    }

    @Test
    fun rejectsAnAlteredNameOrAReversedNegativeCommand() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, translateModel = { request ->
            when (request.text) {
                "Meet Lucia at pier 7." -> "Lucy ile 7. iskelede buluş."
                "Do not open the gate." -> "Kapıyı aç."
                else -> error(request.text)
            }
        })
        assertNull(pipeline.translate(TranslationInput("MEET LUCIA AT PIER 7.")).text)
        assertNull(pipeline.translate(TranslationInput("Do not open the gate.")).text)
    }

    @Test
    fun ignoresAStatementStoredUnderTheSamePackKeyAsAQuestion() {
        val pipeline = GameTranslationPipeline(
            packLookup = { PackMatch("Güvendeyiz.") },
            translateModel = { "Güvende miyiz" },
        )
        assertEquals("Güvende miyiz?", pipeline.translate(TranslationInput("We're safe?")).text)
    }

    @Test
    fun keepsContractionsAndNumbersWhenRepairingOcr() {
        assertEquals("I'll leave 3 cars here. Does R2-D2 know?", cleanOcrLine("l’ll leave 3 cars here. Does R2-D2 know ?"))
        assertEquals("3 More cars are coming.", cleanOcrLine(" 3  More cars are coming. "))
    }

    @Test
    fun translatesAnInvitationToMeetAsAnInvitationRatherThanAnInstructionToGreet() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, translateModel = { request ->
            assertEquals("Let's meet by the bridge.", request.text)
            "Köprünün yanında buluşalım."
        })
        assertEquals("LUCIA: Köprünün yanında buluşalım.",
            pipeline.translate(TranslationInput("LUCIA: Meet me by the bridge.")).text)
    }

    @Test
    fun aVerifiedIdiomRetainsItsMeaningInsideALongerDialogueCue() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, translateModel = { request ->
            assertEquals("We should hear him out.", request.text)
            "Sözünü bitirene kadar onu dinleyelim."
        })
        assertEquals("Bir bakıma haklı. Sözünü bitirene kadar onu dinleyelim.",
            pipeline.translate(TranslationInput("He's got a point. We should hear him out.")).text)
    }

    @Test
    fun aDenialCannotBecomeAnAdmission() {
        val input = TranslationInput("I never said she stole the money.")
        val wrong = GameTranslationPipeline(packLookup = { null }, translateModel = { "Parayı çaldığını söyledim." })
        val right = GameTranslationPipeline(packLookup = { null }, translateModel = { "Parayı çaldığını hiç söylemedim." })
        assertNull(wrong.translate(input).text)
        assertEquals("Parayı çaldığını hiç söylemedim.", right.translate(input).text)
    }

    @Test
    fun keepsTheRecipientAndSpeakerRolesInAQuestion() {
        val pipeline = GameTranslationPipeline(packLookup = { null }, translateModel = { request ->
            assertEquals("Before he left, what did you tell Kratos?", request.text)
            "Kratos'a gitmeden önce ne söyledin?"
        })
        assertEquals("Kratos'a gitmeden önce ne söyledin?",
            pipeline.translate(TranslationInput("What did you say to Kratos before he left?")).text)
    }
}
