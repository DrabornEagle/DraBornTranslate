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
        assertEquals("Liman", coded.nameIn(GtaMissionSearchLanguage.ENGLISH))
        assertEquals("DOCK_ROUTE", coded.code)
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
        val credit = catalog.search("Meet Raul's Crew", GtaMissionSearchLanguage.ENGLISH).single()
        assertEquals("rob-bank", credit.id)
        assertEquals("Credit Check", credit.englishTitle)
        assertEquals("Raul Bautista", credit.giver)
        assertEquals(credit.id, catalog.search("Meet Raúl’s Crew").single().id)
        assertEquals(credit.id, catalog.search("Meet Rauls Crew").single().id)
        assertEquals(credit.id, catalog.search("ROB BANK").single().id)
        assertEquals("rob-drive", catalog.search("Meeting with Raul").first().id)
        assertEquals("rob-boat-j", catalog.search("Meet Raul").first().id)
        assertEquals("Boobie Ike", catalog.search("Return to the Apartment").first().giver)
        assertTrue(credit.scenes.any { it.environment.orEmpty().contains("Stockyard") })
        assertFalse(GtaMissionPack.texts(file, catalog.version, credit).any { it.type == GtaTextType.DIALOGUE })
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

    @Test
    fun bundledMediaResolvesCalAndLabFeesFromTheirPublishers() {
        val root = sequenceOf(File("packs/gta-6"), File("../packs/gta-6")).first { it.isDirectory }
        val manifest = JSONObject(root.resolve("manifest.json").readText())
        val checksum = manifest.getString("sha256")
        val catalog = GtaMissionPack.validate(root.resolve("gta-6-tr-${checksum.take(12)}.zip"))
        val cal = catalog.search("Gone Fishing").first()
        assertTrue(catalog.portrait(cal.giver)?.url.orEmpty().contains("Cal_Hampton"))
        assertEquals(3, cal.previews.size)
        val lab = catalog.search("Lab Fees").first()
        assertEquals(3, lab.previews.size)
        assertTrue(lab.previews.all { it.url.contains("lab-fees") })
        assertEquals("https://six.mics.dev/dialogue?mission=COM&q=COM_KBOS_SHOP", lab.dialogueSource)
        assertEquals("https://six.mics.dev/dialogue?mission=t:INT_PROLO",
            catalog.missions.first { it.id == "prologue" }.dialogueSource)
        val kenton = catalog.search("Island Dreams").single()
        assertTrue(catalog.missionPortrait(kenton)?.second?.url.orEmpty().endsWith("/kenton-2.jpg"))
        val welcome = catalog.search("Welcome to Vice City").single()
        assertTrue(welcome.conversationSummaries.any { "Dre’Quan Priest" in it.speakers })
        assertEquals(3, welcome.previews.size)
        assertTrue(catalog.missionPortrait(welcome) != null)
        val clonin = catalog.search("Clonin' Keys, Makin' Gs").single()
        assertEquals(3, clonin.previews.size)
        assertEquals(3, clonin.conversationSummaries.size)
        assertTrue(catalog.portrait("Unlisted Person") == null)
    }

    @Test
    fun resolvedCodesFindTheNamedMissionAndMissingRedirectsAreRejected() {
        val root = sequenceOf(File("packs/gta-6"), File("../packs/gta-6")).first { it.isDirectory }
        val checksum = JSONObject(root.resolve("manifest.json").readText()).getString("sha256")
        val catalog = GtaMissionPack.validate(root.resolve("gta-6-tr-${checksum.take(12)}.zip"))
        val titles = mapOf(
            "BIL_HOUSE" to "Home Sweet Home",
            "ROB_VEH" to "Meet the Heders",
            "ANT_SRCH" to "Dubious Provenance",
            "FT_MOTEL_SHOP" to "Just Moved In",
            "COM_SFH_FLTPK" to "Building a Life",
        )
        for ((code, title) in titles) {
            val mission = catalog.search(code, GtaMissionSearchLanguage.ENGLISH).single()
            assertEquals(title, mission.englishTitle)
            assertTrue(mission.previews.isNotEmpty())
            assertEquals(mission, catalog.search(title, GtaMissionSearchLanguage.ENGLISH).single())
        }
        assertThrows(IOException::class.java) {
            GtaMissionPack.validate(archive(entry = mission().put("searchTarget", "missing")))
        }
    }

    @Test
    fun imageRequestsStayOnVerifiedHttpsMediaPaths() {
        assertTrue(missionImageUri("https://www.gtabase.com/images/gta-6/characters/cal-hampton.jpg") != null)
        assertTrue(missionImageUri("https://www.gtabase.com/images/jch-optimize/ng/cal-hampton.avif") != null)
        assertTrue(missionImageUri("https://www.rockstargames.com/VI/_next/static/media/cal.jpg?imwidth=420") != null)
        listOf(
            "http://www.gtabase.com/images/gta-6/cal.jpg",
            "https://www.gtabase.com@other.example/images/gta-6/cal.jpg",
            "https://www.gtabase.com/images/gta-6/../../profile.jpg",
            "https://www.gtabase.com/images/gta-6/%2e%2e/profile.jpg",
            "https://www.gtabase.com:4040/images/gta-6/cal.jpg",
            "https://www.gtabase.com/stream/cal.jpg",
            "https://www.gtabase.com/images/gta-6/cal.html",
        ).forEach { assertTrue(missionImageUri(it) == null) }
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
