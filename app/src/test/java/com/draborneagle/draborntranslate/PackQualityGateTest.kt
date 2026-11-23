// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.PackMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackQualityGateTest {
    @Test fun labelledCompanyStillInstantlyResolves() {
        val result = PackQualityGate.select("Kolt & Associates:", PackMatch("Kolt & Associates:", "gta-6"))
        assertNotNull(result)
        assertEquals("Kolt & Associates:", result!!.text)
    }

    @Test fun fixesSpacingAndHtmlEscapesWithoutRewritingMeaning() {
        val actual = PackQualityGate.select(
            "Jason & Lucia: Move!",
            PackMatch("Jason &amp; Lucia:  Çabuk !", "gta-6"),
        )
        assertEquals("Jason & Lucia: Çabuk!", actual?.text)
    }

    @Test fun rejectsUntranslatedEnglishSentence() {
        val english = "Don't leave me behind with the car."
        assertNull(PackQualityGate.select(english, PackMatch(english, "gta-6")))
    }

    @Test fun rejectsQuestionThatLosesItsMeaningfulPunctuation() {
        assertFalse(PackQualityGate.acceptable("Where are the keys?", "Anahtarlar nerede."))
        assertTrue(PackQualityGate.acceptable("Where are the keys?", "Anahtarlar nerede?"))
    }

    @Test fun rejectsUnusableReplacementCharacters() {
        assertNull(PackQualityGate.select("Come here.", PackMatch("�☒", "gta-6")))
    }

    @Test fun keepsIdiomaticTurkishEvenWhenEnglishLooksVeryDifferent() {
        assertEquals(
            "Bu işte bir bit yeniği var.",
            PackQualityGate.select("There's a catch.", PackMatch("Bu işte bir bit yeniği var.", "gta-6"))?.text,
        )
    }

    @Test fun correctionsCanPreserveNamesAndPunctuation() {
        assertEquals(
            "Jason, Lucia'yı bul!",
            PackQualityGate.select("Find Lucia, Jason!", PackMatch("Jason, Lucia'yı bul!", "gta-6"))?.text,
        )
    }
}
