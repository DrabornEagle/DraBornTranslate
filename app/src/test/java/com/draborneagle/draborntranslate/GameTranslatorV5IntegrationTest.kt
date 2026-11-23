package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.ShortPhrases
import com.gametranslator.gametranslator_mobile.core.TurkishPostEdit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTranslatorV5IntegrationTest {
    @Test fun informalEnglishQuestionUsesNaturalTurkish() {
        assertEquals("Ee?", ShortPhrases.translate("Well?"))
        assertEquals("Acele et!", ShortPhrases.translate("Hurry!"))
    }

    @Test fun modelPunctuationFollowsSourceDialogue() {
        assertEquals("Dur!", TurkishPostEdit.apply("Stop!", "Dur ."))
        assertEquals("Nerede?", TurkishPostEdit.apply("Where?", "Nerede ."))
    }

    @Test fun upstreamReleaseMetadataIsPinnedToOfficialGitLabProject() {
        val release = GameTranslatorSource.parse(JSONObject("""{"tag_name":"v1.8.51","name":"GameTranslator v1.8.51"}"""))
        assertEquals("v1.8.51", release.tag)
        assertTrue(release.url.startsWith("https://gitlab.com/sefflex/GameTranslator/-/releases/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidReleaseTagCannotCreateExternalDownloadLink() {
        GameTranslatorSource.parse(JSONObject("""{"tag_name":"../unsafe"}"""))
    }
}
