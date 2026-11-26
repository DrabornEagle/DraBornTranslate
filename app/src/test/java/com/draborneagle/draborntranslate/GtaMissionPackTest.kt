// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GtaMissionPackTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun matchesTurkishEnglishAndAnExplicitMisspelling() {
        val catalog = GtaMissionPack.validate(archive())
        val expected = listOf("launch")
        assertEquals(expected, catalog.search("attent the launch party").map { it.id })
        assertEquals(expected, catalog.search("ATTEND THE LAUNCH PARTY").map { it.id })
        assertEquals(expected, catalog.search("lansman partisine katıl").map { it.id })
        assertTrue(catalog.search("unknown name").isEmpty())
    }

    @Test
    fun preferredLanguageBreaksSearchTiesAndKeepsMissionCodes() {
        val catalog = GtaMissionPack.validate(archive())
        val turkish = catalog.missions.single().copy(title = "Sahil", englishTitle = "The Beach")
        val english = turkish.copy(id = "english", title = "Kıyı", englishTitle = "Sahil")
        val coded = turkish.copy(id = "coded", title = "Liman", englishTitle = null, aliases = listOf("DOCK_ROUTE"))
        val combined = catalog.copy(missions = listOf(turkish, english, coded))
        assertEquals(turkish.id, combined.search("Sahil", GtaMissionSearchLanguage.TURKISH).first().id)
        assertEquals(english.id, combined.search("Sahil", GtaMissionSearchLanguage.ENGLISH).first().id)
        assertEquals("DOCK_ROUTE", coded.nameIn(GtaMissionSearchLanguage.ENGLISH))
        assertEquals(coded.id, combined.search("dock route", GtaMissionSearchLanguage.ENGLISH).single().id)
    }

    @Test
    fun keepsTextOrderAndOnlyUsesSuppliedSceneTimes() {
        val file = archive()
        val catalog = GtaMissionPack.validate(file)
        val mission = catalog.missions.single()
        val lines = GtaMissionPack.texts(file, catalog.version, mission)
        assertEquals(GtaTextType.OBJECTIVE, lines[0].type)
        assertEquals("Kapıya git.", lines[0].turkish)
        assertEquals(null, lines[0].startMs)
        assertEquals("arrival", lines[1].sceneId)
        assertEquals(4500L, lines[1].startMs)
        assertEquals(7000L, lines[1].endMs)
        assertEquals("Giriş holü", mission.scenes.single().environment)
        assertThrows(IOException::class.java) { GtaMissionPack.texts(file, "older", mission) }
    }

    @Test
    fun rejectsInvalidManifestReferencesAndIncompleteOrUnexpectedEntries() {
        val unknownScene = texts().also { it.getJSONObject(1).put("sceneId", "unknown") }
        val endBeforeStart = texts().also { it.getJSONObject(1).put("endMs", 2000L) }
        val invalidSource = mission().put("sourceUrl", "javascript:alert(1)")
        val wrongCount = mission().put("textCount", 3)
        val missingPrerequisite = mission().put("requires", JSONArray().put("unknown"))
        assertThrows(IOException::class.java) { GtaMissionPack.validate(archive(rows = unknownScene)) }
        assertThrows(IOException::class.java) { GtaMissionPack.validate(archive(rows = endBeforeStart)) }
        assertThrows(IOException::class.java) { GtaMissionPack.validate(archive(entry = invalidSource)) }
        assertThrows(IOException::class.java) { GtaMissionPack.validate(archive(entry = wrongCount)) }
        assertThrows(IOException::class.java) { GtaMissionPack.validate(archive(entry = missingPrerequisite)) }
        assertThrows(IOException::class.java) {
            GtaMissionPack.validate(archive(extra = "../unexpected.txt"))
        }
    }

    @Test
    fun acceptsAnEnglishOnlyPersonalTextForOnDeviceTranslation() {
        val rows = texts().also {
            val row = it.getJSONObject(0)
            row.remove("tr")
            row.put("en", "Find the gate.")
        }
        val file = archive(rows = rows)
        val catalog = GtaMissionPack.validate(file)
        val text = GtaMissionPack.texts(file, catalog.version, catalog.missions.single()).first()
        assertEquals("Find the gate.", text.english)
        assertEquals(null, text.turkish)
    }

    @Test
    fun publishesTheKnownLaunchPartyTargetWithItsSourceAndNoEstimatedTime() {
        val root = sequenceOf(File("packs/gta-6"), File("../packs/gta-6")).first { it.isDirectory }
        val manifest = JSONObject(root.resolve("manifest.json").readText())
        val checksum = manifest.getString("sha256")
        val file = root.resolve("gta-6-tr-${checksum.take(12)}.zip")
        assertEquals(manifest.getLong("bytes"), file.length())
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(checksum, actual)
        val catalog = GtaMissionPack.validate(file)
        assertEquals(manifest.getString("version"), catalog.version)
        assertTrue(catalog.missions.all { it.textCount > 0 && it.source != null })
        val lab = catalog.search("Lab Fees", GtaMissionSearchLanguage.ENGLISH).first()
        assertEquals("com-kbos-shop", lab.id)
        assertEquals("Dükkân Kirası", lab.nameIn(GtaMissionSearchLanguage.TURKISH))
        val details = GtaMissionPack.texts(file, catalog.version, lab)
        assertTrue(details.size >= 4 && details.all { it.sceneId != null && !it.turkish.isNullOrBlank() })
        val mission = catalog.search("attent the launch party").first()
        assertEquals("the-audacity", mission.id)
        assertEquals(GtaMissionType.SIDE, mission.type)
        assertTrue(mission.location.orEmpty().contains("Neo Paloma Apartments"))
        assertEquals(listOf("catching-a-whale"), mission.prerequisites)
        val lines = GtaMissionPack.texts(file, catalog.version, mission)
        assertEquals("Lansman partisine katıl.", lines.first().turkish)
        assertTrue(lines.any { it.type == GtaTextType.GUIDE })
        assertFalse(lines.any { it.startMs != null })
    }

    private fun mission() = JSONObject(
        """{"id":"launch","titleTr":"Lansman Partisine Katıl","titleEn":"Attend the Launch Party","type":"side","chapter":3,
        "summaryTr":"Deneme görevi.","aliases":["Attent the Launch Party"],"textCount":2,
        "scenes":[{"id":"arrival","titleTr":"Varış","environmentTr":"Giriş holü"}]}""",
    )

    private fun texts() = JSONArray(
        """[{"type":"objective","tr":"Kapıya git.","sceneId":"arrival"},
        {"type":"dialogue","tr":"Hoş geldin.","sceneId":"arrival","speaker":"Deniz","startMs":4500,"endMs":7000}]""",
    )

    private fun archive(entry: JSONObject = mission(), rows: JSONArray = texts(), extra: String? = null): File {
        val file = temporary.newFile()
        val data = JSONObject().put("format", 1).put("version", "test").put("gameBuild", "test")
            .put("missions", JSONArray().put(entry))
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add("catalog.json", data.toString())
            add("texts/launch.json", rows.toString())
            extra?.let { add(it, "unexpected") }
        }
        return file
    }
}
