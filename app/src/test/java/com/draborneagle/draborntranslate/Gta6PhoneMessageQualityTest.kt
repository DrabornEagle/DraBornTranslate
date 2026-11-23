// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.PackMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gta6PhoneMessageQualityTest {
    @Test fun commonPhoneShorthandGetsHumanReviewedTurkishWithoutNetworkOrModel() {
        val pipeline = GameTranslationPipeline(
            packLookup = { PackMatch("Kötü OCR eşleşmesi", "gta-6") },
            model = { error("Should never invoke the model for reviewed phone shorthand") },
        )
        val examples = mapOf(
            "OMW." to "Yoldayım.",
            "Where u at?" to "Neredesin?",
            "U up?" to "Uyanık mısın?",
            "Hit me up." to "Bana yaz.",
            "BRB." to "Hemen döneceğim.",
            "Don't tell anyone." to "Kimseye söyleme.",
            "Keep this between us." to "Bu aramızda kalsın.",
        )
        examples.forEach { (english, expected) ->
            assertEquals(expected, pipeline.translate(
                TranslationInput(english, ReadingArea.GAME_TEXT)
            ).text)
        }
    }

    @Test fun fullMessageStillUsesPackBeforeModelWhenNotInReviewedSet() {
        val pipeline = GameTranslationPipeline(
            packLookup = {
                if (it == "We will meet tomorrow at the diner.") PackMatch(
                    "Yarın lokantada buluşacağız.", "gta-6"
                ) else null
            },
            model = { error("Verified pack hit must remain instant") },
        )
        assertEquals("Yarın lokantada buluşacağız.", pipeline.translate(
            TranslationInput("We will meet tomorrow at the diner.", ReadingArea.GAME_TEXT)
        ).text)
    }

    @Test fun uncertainSlangIsNotBlindlyRewritten() {
        var modelCalled = false
        val pipeline = GameTranslationPipeline(
            packLookup = { null },
            model = { modelCalled = true; "Arabayı yanaştır." },
        )
        assertEquals("Arabayı yanaştır.", pipeline.translate(
            TranslationInput("Pull up.", ReadingArea.GAME_TEXT)
        ).text)
        assertTrue("Ambiguous phrase must use context-aware translator", modelCalled)
    }
}
