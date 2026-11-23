// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.GamePackLibrary
import com.gametranslator.gametranslator_mobile.core.PackTranslation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Optional integration test. It uses the *actual* GTA 6 pack, but the data
 * stays in GitHub Actions scratch storage and is never committed to the repo.
 * Without DBT_GTA6_ARCHIVE the regular offline test suite skips this test.
 */
class Gta6LivePackIntegrationTest {
    @Test
    fun verified475893EntryPackResolvesLabelledTextWithoutModel() {
        val path = System.getenv("DBT_GTA6_ARCHIVE")
        assumeTrue("GTA 6 live archive is not provided", !path.isNullOrBlank())
        val file = File(path!!)
        assumeTrue("No live archive found", file.isFile)
        val archive = file.readBytes()
        val library = GamePackLibrary(
            { listOf("gta-6.json.gz" to { archive }) }, null
        )
        assertEquals("The real archive must contain all manifest entries",
            475_893, library.infos.single().entries)
        val label = "Kolt & Associates:"
        val known = PackTranslation.match(library, label)
        assertNotNull("Labelled entry must exist in the real pack", known)
        assertTrue("Label must have usable Turkish text", known!!.text.isNotBlank())

        val pipeline = GameTranslationPipeline(
            packLookup = { PackTranslation.match(library, it) },
            model = { error("Pack-hit must bypass neural translation") },
        )
        repeat(16) {
            assertEquals(known.text, pipeline.translate(TranslationInput(label)).text)
            assertEquals(known.text, pipeline.translate(
                TranslationInput(label, ReadingArea.GAME_TEXT)
            ).text)
        }
    }
}
