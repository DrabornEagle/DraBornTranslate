// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.GridView
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.gametranslator.gametranslator_mobile.ConsoleStudioActivity
import com.gametranslator.gametranslator_mobile.Store
import com.gametranslator.gametranslator_mobile.core.PackKey
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val missionSync = app.getSharedPreferences("gta_mission_catalog", Context.MODE_PRIVATE)
    private var priorMissionCheck = 0L

    @Before
    fun useCachedMissionPack() {
        priorMissionCheck = missionSync.getLong("nextCheck", 0L)
        missionSync.edit().putLong("nextCheck", System.currentTimeMillis() + 5 * 60_000L).commit()
    }

    @After
    fun restoreMissionSyncCheck() {
        missionSync.edit().putLong("nextCheck", priorMissionCheck).commit()
    }

    @Test
    fun missionSearchOpensDetailsAndContextForAUserPack() {
        val file = File(app.filesDir, "gta-6-missions-local.zip")
        val backup = file.takeIf(File::isFile)?.readBytes()
        try {
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, content: String) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("catalog.json", """
                    {"format":1,"version":"test","gameBuild":"Test",
                    "missions":[{"id":"launch","titleTr":"Lansman Partisine Katıl","titleEn":"The Audacity",
                    "type":"side","chapter":3,"summaryTr":"Lansmandan önce daveti bekle.","textCount":5,
                    "aliases":["Attent the Launch Party"],"locationTr":"Neo Paloma Apartments",
                    "sourceUrl":"https://example.org/mission","textSourceUrl":"https://example.org/lines",
                    "scenes":[{"id":"lobby","titleTr":"Buluşma","environmentTr":"Giriş holü"},
                    {"id":"hall","titleTr":"Koridora geçiş","environmentTr":"Arka koridor"}],
                    "conversationSummaries":[{"titleTr":"İlk karşılaşma","summaryTr":"İkili girişte buluşur."}]}]}
                """.trimIndent())
                entry("texts/launch.json", """
                    [{"type":"objective","tr":"Lansman partisine katıl.","sceneId":"lobby"},
                     {"type":"guide","tr":"Giriş kapısından geçilir.","sceneId":"lobby"},
                     {"type":"dialogue","tr":"Seni girişte bekleyeceğim.","sceneId":"lobby","speaker":"Deniz","startMs":2500},
                     {"type":"dialogue","tr":"Birazdan oradayım.","sceneId":"lobby","speaker":"Ece","startMs":4100},
                     {"type":"dialogue","tr":"Kapıyı açık bıraktım.","sceneId":"hall","speaker":"Mert","startMs":800}]
                """.trimIndent())
            }
            ActivityScenario.launch(HomeActivity::class.java).use {
                waitFor { resumed() is HomeActivity }
                click("Görev ara")
                waitFor { resumed() is GtaMissionSearchActivity && hasText("Türkçe") }
                click("Türkçe")
                waitFor { resumed() is GtaMissionSearchActivity && hasText("Lansman Partisine Katıl") }
                onMain { (resumed()?.window?.decorView?.findChild { it is EditText } as EditText).setText("Attent the Launch Party") }
                click("İngilizce")
                waitFor { hasText("The Audacity") }
                click("The Audacity")
                waitFor {
                    resumed() is GtaMissionDetailsActivity && hasText("Neo Paloma Apartments") &&
                        hasText("Sahne özetleri") && hasText("İlk karşılaşma")
                }
                assertFalse(hasText("Seni girişte bekleyeceğim."))
                click("Görev metinleri")
                val readingOrder = listOf("Buluşma", "Deniz", "Seni girişte bekleyeceğim.", "Ece", "Birazdan oradayım.",
                    "Koridora geçiş", "Mert", "Kapıyı açık bıraktım.")
                waitFor { readingOrder.all(::hasText) && hasText("Giriş holü") && hasText("Arka koridor") }
                assertReadingOrder(readingOrder)
                assertFalse(hasText("Canlı kayıt başlat"))
                assertFalse(hasText("SRT / VTT ekle"))
                assertFalse(hasText("Lansman partisine katıl."))
                assertFalse(hasText("Sahne özetleri"))
                assertFalse(hasText("İlk karşılaşma"))
                assertFalse(hasText("Giriş kapısından geçilir."))
                assertFalse(hasText("Bu görev için konuşma kaydı bulunmuyor."))
                screenshot("08-gorev-konusmalari")
                click("Hedefler/Kaynaklar")
                waitFor { hasText("Lansman partisine katıl.") && hasText("Giriş holü") }
                assertTrue(hasText("Görev kaynağını aç"))
                assertTrue(hasText("Görev metinlerini kaynakta aç"))
                assertFalse(hasText("Seni girişte bekleyeceğim."))
                assertFalse(hasText("Neo Paloma Apartments"))
                click("Görev bilgileri")
                waitFor { hasText("Neo Paloma Apartments") }
                click("Geri")
                waitFor { resumed() is GtaMissionSearchActivity && hasText("The Audacity") }
                click("Türkçe")
                waitFor { hasText("Lansman Partisine Katıl") }
            }
        } finally {
            if (backup == null) file.delete() else file.writeBytes(backup)
        }
    }

    @Test
    fun publishedDialogueLoadsAutomaticallyWithItsOwnScenesAndNoSetupButtons() {
        val base = File(app.filesDir, "gta-6-missions-local.zip")
        val cache = File(app.filesDir, "gta-6-missions.zip")
        val backups = listOf(base, cache).associateWith { file -> file.takeIf(File::isFile)?.readBytes() }
        val archive = File.createTempFile("published-dialogue-", ".zip", app.cacheDir)
        try {
            writeDialogueFixture(base, "base", "entrance", "Giriş", """
                [{"type":"objective","sceneId":"entrance","tr":"Girişe git."}]
            """.trimIndent())
            val version = "2099.01.01.1"
            writeDialogueFixture(archive, version, "garden", "Bahçedeki buluşma", """
                [{"type":"dialogue","sceneId":"garden","speaker":"Deniz","tr":"Yardımın için teşekkürler."}]
            """.trimIndent())
            val data = archive.readBytes()
            val checksum = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            val zipName = "gta-6-tr-${checksum.take(12)}.zip"
            val manifest = """{"version":"$version","bytes":${data.size},"sha256":"$checksum"}"""
            val requests = mutableListOf<String>()
            missionSync.edit().remove("nextCheck").commit()
            assertTrue(GtaMissionStore.refreshPublished(app) { request ->
                assertEquals("raw.githubusercontent.com", request.host)
                val path = request.path.substringAfterLast('/')
                requests.add(path)
                val reply = when (path) {
                    "manifest.json" -> manifest.toByteArray(Charsets.UTF_8)
                    zipName -> data
                    else -> error("Unexpected pack request")
                }
                object : HttpURLConnection(request.toURL()) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode() = HTTP_OK
                    override fun getInputStream() = ByteArrayInputStream(reply)
                }
            })
            assertEquals(listOf("manifest.json", zipName), requests)
            assertFalse(GtaMissionStore.refreshPublished(app) { error("Recent checks should use the downloaded copy") })

            ActivityScenario.launch(GamePacksActivity::class.java).use {
                waitFor { hasText("Oyun Paketleri") && hasText("Görev ara") }
                listOf("Görev paketi seç (.zip)", "Replik paketi seç (.zip)", "Replik paketi bağla",
                    "Replik paketini güncelle").forEach { assertFalse(it, hasText(it)) }
            }
            ActivityScenario.launch<GtaMissionDetailsActivity>(Intent(app, GtaMissionDetailsActivity::class.java)
                .putExtra("mission", "dialogue-test")).use {
                waitFor { hasText("Deneme görevi") && hasText("Görev metinleri") }
                click("Görev metinleri")
                val dialogueOrder = listOf("Bahçedeki buluşma", "Deniz", "Yardımın için teşekkürler.")
                waitFor { dialogueOrder.all(::hasText) }
                assertReadingOrder(dialogueOrder)
                assertFalse(hasText("Girişe git."))
                assertFalse(hasText("Replik paketini güncelle"))
                click("Hedefler/Kaynaklar")
                waitFor { hasText("Girişe git.") }
                assertFalse(hasText("Yardımın için teşekkürler."))
            }
        } finally {
            archive.delete()
            backups.forEach { (file, data) -> if (data == null) file.delete() else file.writeBytes(data) }
        }
    }

    @Test
    fun readAllIncludesMissionIndexedLinesWithoutASceneOrSpeaker() {
        val guide = File(app.filesDir, "gta-6-missions-local.zip")
        val subtitles = File(app.filesDir, "gta-6-local.json.gz")
        val dialogue = File(app.filesDir, "gta-6-dialogue-local.zip")
        val backups = listOf(guide, subtitles, dialogue).associateWith { file -> file.takeIf(File::isFile)?.readBytes() }
        val gzip = File(app.cacheDir, "indexed-subtitles.gz")
        val reply = checkNotNull(PackKey.key("Meet me by the lift."))
        val answer = checkNotNull(PackKey.key("I am on my way."))
        val ungrouped = checkNotNull(PackKey.key("The door is open."))
        val unrelated = checkNotNull(PackKey.key("Save your game."))
        try {
            writeDialogueFixture(guide, "base", "entrance", "Giriş", """
                [{"type":"objective","sceneId":"entrance","tr":"Girişe git."}]
            """.trimIndent())
            GZIPOutputStream(gzip.outputStream()).bufferedWriter().use {
                it.write("""
                    {"format":2,"id":"gta-6","lang":"tr","title":"Deneme altyazıları",
                    "entries":{"$reply":"<b>Asansörün yanında buluşalım.</>","$unrelated":"Oyunu kaydet."},
                    "segments":{"$reply":"Önceliği düşük karşılık.","$answer":"Yoldayım.","$ungrouped":"Kapı açık."}}
                """.trimIndent())
            }
            val installed = ImportedGamePack.install(app, Uri.fromFile(gzip))
            assertTrue(installed.gta)
            assertTrue(ImportedGamePack.hasGta(app))
            assertEquals("Asansörün yanında buluşalım.",
                ImportedGamePack.library(app, GameReadingProfile.GTA_6).find("Meet me by the lift."))
            val indexCatalog = """
                {"format":1,"version":"indexed","gameBuild":"Test","missions":[
                 {"id":"dialogue-test","titleTr":"Deneme görevi","type":"story","summaryTr":"Buluşma.",
                  "textCount":4,"scenes":[{"id":"lobby","titleTr":"Holde buluşma","environmentTr":"Apartman girişi"},
                  {"id":"lift","titleTr":"Asansöre geçiş"}]}]}
            """.trimIndent()
            writeMissionFixture(dialogue, indexCatalog, """
                [{"type":"dialogue","sceneId":"lobby","speaker":"Deniz","subtitleKey":"$reply",
                  "contextTr":"Replik açıklaması"},
                 {"type":"dialogue","subtitleKey":"$ungrouped"},
                 {"type":"dialogue","sceneId":"lift","speaker":"Ece","subtitleKey":"$answer"},
                 {"type":"dialogue","sceneId":"lobby","speaker":"Eşleşmeyen","subtitleKey":"0000000000000000"}]
            """.trimIndent())
            GtaMissionPack.validate(dialogue)
            ActivityScenario.launch<GtaMissionDetailsActivity>(Intent(app, GtaMissionDetailsActivity::class.java)
                .putExtra("mission", "dialogue-test")).use {
                waitFor { hasText("Deneme görevi") && hasText("Görev metinleri") }
                click("Görev metinleri")
                val readingOrder = listOf("Holde buluşma", "Deniz", "Asansörün yanında buluşalım.",
                    "Asansöre geçiş", "Ece", "Yoldayım.")
                waitFor { readingOrder.all(::hasText) && hasText("Apartman girişi") }
                assertReadingOrder(readingOrder)
                listOf("Girişe git.", "Replik açıklaması", "Eşleşmeyen", "Oyunu kaydet.",
                    "Önceliği düşük karşılık.", "Görevin diyalog arşivini aç", "Kapı açık.").forEach { item ->
                    assertFalse(item, hasText(item))
                }
                click("Bütün konuşmayı oku")
                val allLines = listOf("Deniz", "Asansörün yanında buluşalım.", "Kapı açık.", "Ece", "Yoldayım.")
                waitFor { allLines.all(::hasText) }
                assertReadingOrder(allLines)
                listOf("Holde buluşma", "Asansöre geçiş", "Apartman girişi", "Girişe git.", "Oyunu kaydet.",
                    "Eşleşmeyen", "Görev metinlerini kaynakta aç").forEach { assertFalse(it, hasText(it)) }
                it.recreate()
                waitFor { allLines.all(::hasText) }
                assertReadingOrder(allLines)
                assertFalse(hasText("Holde buluşma"))
                click("Sahnelere göre")
                waitFor { readingOrder.all(::hasText) }
                assertFalse(hasText("Kapı açık."))
                click("Hedefler/Kaynaklar")
                waitFor { hasText("Girişe git.") }
                assertFalse(hasText("Yoldayım."))
            }
        } finally {
            gzip.delete()
            ImportedGamePack.removeLocalGta(app)
            backups.forEach { (file, data) -> if (data == null) file.delete() else file.writeBytes(data) }
        }
    }

    private fun writeDialogueFixture(file: File, version: String, sceneId: String, sceneTitle: String, lines: String) {
        val catalog = """
            {"format":1,"version":"$version","gameBuild":"Test",
             "missions":[{"id":"dialogue-test","titleTr":"Deneme görevi","type":"story","summaryTr":"Bir buluşma.",
             "textCount":1,"scenes":[{"id":"$sceneId","titleTr":"$sceneTitle"}]}]}
        """.trimIndent()
        writeMissionFixture(file, catalog, lines)
    }

    @Test
    fun selectedMissionShowsLocallyRecordedSubtitlesInOrder() {
        val guide = File(app.filesDir, "gta-6-missions-local.zip")
        val backup = guide.takeIf(File::isFile)?.readBytes()
        val missionId = "local-caption-${java.util.UUID.randomUUID()}"
        val catalog = """{"format":1,"version":"local-capture","gameBuild":"Test","missions":[
            {"id":"$missionId","titleTr":"Kayıt deneme görevi","type":"story","summaryTr":"Deneme rehberi.",
             "textCount":1}]}"""
        try {
            writeMissionFixture(guide, catalog, """[{"type":"objective","tr":"Görev hedefi."}]""", missionId)
            checkNotNull(GtaMissionRecording.start(app, missionId)).apply {
                add("Deniz: Is this the first run?", "Deniz: Bu ilk oturum mu?")
                close()
            }
            val recording = checkNotNull(GtaMissionRecording.start(app, missionId))
            val tick = SystemClock.elapsedRealtime()
            fun objective(text: String) = listOf(HudBlock(HudKind.OBJECTIVE,
                com.gametranslator.gametranslator_mobile.core.TextBox(0.1, 0.8, 0.9, 0.9),
                listOf(HudLine(text))))
            recording.observeObjectives(objective("Girişe git"))
            recording.add("Deniz: Wait for me.", "Deniz: Beni bekle.", tick)
            recording.add("Deniz: Wait for me.", "Deniz: Beni bekle.", tick + 200)
            recording.observeObjectives(objective("Avluya çık"))
            recording.add("Ece: I am here.", "Ece: Buradayım.", tick + 1200)
            recording.add("All clear.", "Her yer temiz.", tick + 3400)
            recording.close()
            recording.add("Deniz: This is after capture.", "Kayıt kapandıktan sonra.")
            checkNotNull(GtaMissionRecording.start(app, "$missionId-other")).apply {
                add("Elsewhere: hello.", "Başka görevin repliği.")
                close()
            }
            assertEquals(listOf("Deniz", "Ece", null),
                GtaMissionRecording.dialogue(app, missionId)?.inSceneOrder()?.map { it.speaker })

            ActivityScenario.launch<GtaMissionDetailsActivity>(Intent(app, GtaMissionDetailsActivity::class.java)
                .putExtra("mission", missionId)).use {
                waitFor { hasText("Bu görevde canlı altyazı") }
                click("Görev metinleri")
                val order = listOf("Girişe git", "Deniz", "Beni bekle.", "Avluya çık", "Ece", "Buradayım.", "Her yer temiz.")
                waitFor { order.all(::hasText) }
                assertReadingOrder(order)
                listOf("Bu ilk oturum mu?", "Başka görevin repliği.", "Kayıt kapandıktan sonra.", "Görev hedefi.",
                    "Bu görevde canlı altyazı").forEach { assertFalse(it, hasText(it)) }
                click("Bütün konuşmayı oku")
                val allLines = listOf("Deniz", "Beni bekle.", "Ece", "Buradayım.", "Her yer temiz.")
                waitFor { allLines.all(::hasText) }
                assertReadingOrder(allLines)
                listOf("Girişe git", "Avluya çık", "Başka görevin repliği.", "Bu ilk oturum mu?").forEach {
                    assertFalse(it, hasText(it))
                }
                click("Hedefler/Kaynaklar")
                waitFor { hasText("Görev hedefi.") }
                assertFalse(hasText("Beni bekle."))
            }
        } finally {
            if (backup == null) guide.delete() else guide.writeBytes(backup)
        }
    }

    private fun writeMissionFixture(file: File, catalog: String, lines: String, missionId: String = "dialogue-test") {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in mapOf("catalog.json" to catalog, "texts/$missionId.json" to lines)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    @Test
    fun bundledCatalogShowsMissionMediaAndSummariesOnlyInMissionInfo() {
        ActivityScenario.launch(GtaMissionSearchActivity::class.java).use {
            waitFor { resumed() is GtaMissionSearchActivity && hasText("İngilizce") }
            click("İngilizce")
            onMain { (resumed()?.window?.decorView?.findChild { it is EditText } as EditText).setText("gone fishing") }
            waitFor {
                hasText("Gone Fishing") && resumed()?.window?.decorView?.findChild {
                    it is ImageView && it.contentDescription == "Cal Hampton profil resmi"
                } != null
            }
            screenshot("06-gorev-ara")
            click("Gone Fishing")
            waitFor {
                resumed() is GtaMissionDetailsActivity && hasText("Cal Hampton") && hasText("Buluşma noktası") &&
                    hasText("Sahne özetleri") && hasText("Teknedeki iş") && hasText("Kampı terk ederken")
            }
            assertFalse(hasText("Profil fotoğrafı ekle"))
            assertFalse(hasText("Oyundan görüntü ekle"))
            assertFalse(hasText("Görsel kaynağı · GTABase"))
            screenshot("07-gorev-detayi")
            click("Görev metinleri")
            waitFor { hasText("Bu görev için konuşma kaydı bulunmuyor.") }
            assertFalse(hasText("Sahne özetleri"))
            assertFalse(hasText("Teknedeki iş"))
            assertFalse(hasText("Görevin diyalog arşivini aç"))
            assertFalse(hasText("Konuşma kayıtlarım"))
            assertFalse(hasText("Altyazı geçmişinden seç"))
            click("Hedefler/Kaynaklar")
            waitFor { hasText("Kaynaklar") && hasText("Görev kaynağını aç") }
            assertFalse(hasText("Görevin diyalog arşivini aç"))
            click("Geri")
            waitFor { resumed() is GtaMissionSearchActivity }
            onMain { (resumed()?.window?.decorView?.findChild { it is EditText } as EditText).setText("lab fees") }
            waitFor { hasText("Lab Fees") }
            click("Lab Fees")
            waitFor { hasText("Önce tamamlanacak görev") }
            click("Gone Fishing")
            waitFor { resumed() is GtaMissionDetailsActivity && hasText("Cal Hampton") }
        }
    }

    @Test
    fun oldMissionCodesOpenTheNamedStoryMission() {
        ActivityScenario.launch(GtaMissionSearchActivity::class.java).use {
            waitFor { resumed() is GtaMissionSearchActivity && hasText("İngilizce") }
            click("İngilizce")
            onMain { (resumed()?.window?.decorView?.findChild { it is EditText } as EditText).setText("rob_veh") }
            waitFor { hasText("Meet the Heders") }
            assertFalse(hasText("Görev adı doğrulanmadı"))
            click("Meet the Heders")
            waitFor { hasText("Brian’la balkon görüşmesi") && hasText("Balkondaki konuşma") }
            click("Görev metinleri")
            waitFor { hasText("Bu görev için konuşma kaydı bulunmuyor.") }
            assertFalse(hasText("Balkondaki konuşma"))
            assertFalse(hasText("Görevin diyalog arşivini aç"))
            click("Geri")
            waitFor { resumed() is GtaMissionSearchActivity }
            onMain { (resumed()?.window?.decorView?.findChild { it is EditText } as EditText).setText("first impressions") }
            waitFor { hasText("First Impressions") }
            click("First Impressions")
            waitFor {
                hasText("Garaja yönlendirme") && hasText("Arabadaki açıklama") &&
                    hasText("Asıl işin ortaya çıkması")
            }
            assertReadingOrder(listOf("Görev aşamaları", "Sahne özetleri", "Garaja yönlendirme"))
            click("Görev metinleri")
            waitFor { hasText("Bu görev için konuşma kaydı bulunmuyor.") }
            assertFalse(hasText("Garaja yönlendirme"))
        }
    }

    @Test
    fun connectionsOpenTheirOwnScreensAndReturnToHome() {
        onMain {
            Store(app).apply {
                introSeen = true
                settings = settings.copy(uiLanguage = "tr")
            }
        }
        ActivityScenario.launch(HomeActivity::class.java).use {
            waitFor { resumed() is HomeActivity }
            waitForAccessible("PS5 ve TV’ye bağlan", "01-home")
            waitForAccessible("Portal’a bağlan", "01-home")
            waitForAccessible("Telefonda oyna", "01-home")
            screenshot("01-home")
            click("Sürüm ve güncellemeler")
            waitFor { resumed() is UpdatesActivity }
            val installedVersion = app.packageManager.getPackageInfo(app.packageName, 0).versionName
            assertTrue(hasText("Sürüm $installedVersion"))
            assertFalse(hasText("Açık kaynak lisansı"))
            screenshot("09-surum-ve-guncellemeler")
            click("Geri")
            waitFor { resumed() is HomeActivity }
            click("PS5 ve TV’ye bağlan")
            waitFor { resumed() is ConsoleStudioActivity }
            waitForAccessible("Kurulum", "02-ps5-tv")
            screenshot("02-ps5-tv")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor { resumed() is HomeActivity }
            click("Portal’a bağlan")
            waitFor { resumed() is MainActivity }
            assertTrue(hasText("PlayStation Portal"))
            assertTrue(hasText("Canlı çeviriyi başlat"))
            assertTrue(hasText("PlayStation App’i aç"))
            assertTrue(hasText("Çeviri seçenekleri"))
            screenshot("03-portal")
            val target = app.readCaptureSettings(Source.PORTAL).display
            val alternate = if (target == SubtitleDisplay.PHONE) SubtitleDisplay.BOTH else SubtitleDisplay.PHONE
            click("Altyazı ve ses · ${target.label}")
            waitForAccessible("Altyazı ve ses", "10-altyazi-ve-ses")
            assertTrue(optionNode(target.label)?.isSelected == true)
            assertFalse(hasAccessibleText("(deneysel)"))
            screenshot("10-altyazi-ve-ses")
            assertTrue(optionNode(alternate.label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            waitFor { hasText(alternate.label) }
            click("Altyazı ve ses · ${alternate.label}")
            waitForAccessible("Altyazı ve ses", "10-altyazi-ve-ses")
            assertTrue(optionNode(alternate.label)?.isSelected == true)
            assertTrue(optionNode(target.label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            waitFor { hasText(target.label) }
            val originalProfile = app.readCaptureSettings(Source.PORTAL).gameProfile
            click("Oyun profili · ${originalProfile.label}")
            waitForAccessible("GTA 6", "12-oyun-profili")
            assertTrue(optionNode(originalProfile.label)?.isSelected == true)
            assertTrue(optionNode(GameReadingProfile.GTA_6.label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            waitFor { hasText("Oyun profili · GTA 6") }
            assertEquals(GameReadingProfile.GTA_6, app.readCaptureSettings(Source.PORTAL).gameProfile)
            click("Oyun profili · GTA 6")
            waitForAccessible("Telefon paneli", "12-oyun-profili")
            screenshot("12-oyun-profili")
            assertTrue(optionNode(originalProfile.label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            waitFor { hasText("Oyun profili · ${originalProfile.label}") }
            click("Geri")
            waitFor { resumed() is HomeActivity }
            click("Telefonda oyna")
            waitFor { resumed() is MainActivity }
            assertTrue(hasText("PS Remote Play uygulamasını aç"))
            assertFalse(hasText("Açık kaynak lisansı"))
        }
    }

    @Test
    fun capicheShowsBrowserActionsAndWaitsForTheConsoleBeforeSharing() {
        ActivityScenario.launch(CapicheActivity::class.java).use {
            waitFor { resumed() is CapicheActivity && hasText("Capiche’yi aç") && hasText("Capiche’ye bağış yap") }
            onMain {
                val screen = checkNotNull(resumed()?.window?.decorView)
                val window = checkNotNull(screen.findChild { it is TextView && it.text == "Oyun penceresini paylaş" })
                val copy = checkNotNull(screen.findChild { it is TextView && it.text == "Oyun bağlantısını kopyala" })
                assertFalse(window.isEnabled)
                assertEquals(View.GONE, copy.visibility)
            }
        }
    }

    @Test
    fun currentFramePromptsForASourceAndDisplaysPortraitPortalFrames() {
        val saved = saveSample("recent-screenshotUrl.png", Color.RED)
        try {
            onMain {
                LiveFocusFrames.clear()
                SessionState.update(SessionState.State())
            }
            ActivityScenario.launch(FocusActivity::class.java).use {
                waitFor { resumed() is FocusActivity }
                click("Güncel oyun karesini al")
                waitForAccessible("Portal yayınını paylaş", "06a-yayin-kaynagi")
                assertFalse(hasAccessibleText("Son PS App yakalaması"))
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                onMain {
                    SessionState.update(SessionState.State(active = true, source = Source.PORTAL))
                    cachePortalFrame(Color.BLUE)
                }
                click("Güncel oyun karesini al")
                waitFor { hasText("PS App · Son görülen kare") && focusFrameColor() == Color.BLUE }
                onMain { cachePortalFrame(Color.GREEN) }
                assertEquals(Color.BLUE, onMain { focusFrameColor() })
                click("Güncel oyun karesini al")
                waitFor {
                    resumed()?.intent?.data == null && hasText("PS App · Son görülen kare") && focusFrameColor() == Color.GREEN
                }
            }
        } finally {
            app.contentResolver.delete(saved, null, null)
            onMain {
                LiveFocusFrames.clear()
                SessionState.update(SessionState.State())
            }
        }
    }

    @Test
    fun focusRequestsASourceAndTakesTheNextGameFrame() {
        onMain {
            LiveFocusFrames.clear()
            SessionState.update(SessionState.State())
        }
        try {
            ActivityScenario.launch(FocusActivity::class.java).use {
                waitFor { resumed() is FocusActivity }
                assertTrue(hasText("Görsel Çeviri"))
                screenshot("05-gorsel-ceviri")
                click("Güncel oyun karesini al")
                waitForAccessible("Oyun görüntüsü kaynağı", "06-kaynak-secimi")
                assertTrue(hasAccessibleText("Portal yayınını paylaş"))
                screenshot("06-kaynak-secimi")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                onMain {
                    assertTrue(ConsoleSession.begin())
                }
                click("Güncel oyun karesini al")
                waitFor { hasText("İlk oyun karesi bekleniyor. Oyun yayınını ekranda aç.") }
                onMain {
                    val sample = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(29, 88, 140)) }
                    ConsoleSession.frame(sample, SystemClock.elapsedRealtime())
                }
                waitFor { hasText("Konsol · Oyun karesi") }
            }
        } finally {
            onMain {
                ConsoleSession.end()
                LiveFocusFrames.clear()
                SessionState.update(SessionState.State())
            }
        }
    }

    @Test
    fun newestPlayStationClipOpensFromTheCaptureGallery() {
        val files = mutableListOf<Uri>()
        try {
            saveSample("older-screenshotUrl.png", Color.BLUE).also(files::add)
            val clip = saveClip().also(files::add)
            saveSample("unrelated.png", Color.RED, "Pictures/Screenshots/").also(files::add)
            ActivityScenario.launch(CaptureInboxActivity::class.java).use {
                waitFor { firstCapture()?.endsWith("/${clip.lastPathSegment}") == true }
                openFirstCapture()
                waitFor { resumed()?.intent?.data?.lastPathSegment == clip.lastPathSegment }
                waitFor {
                    resumed()?.window?.decorView?.findChild { view ->
                        view.isShown && view.contentDescription == "Klip konumu"
                    } != null
                }
                screenshot("07-son-ps-app-klibi")
                onMain { resumed()?.finish() }
            }
        } finally {
            files.forEach { app.contentResolver.delete(it, null, null) }
        }
    }

    @Test
    fun newestPlayStationImageCanBeDraggedAndOpenedFullScreen() {
        val photo = saveSample("newest-screenshotUrl.png", Color.rgb(45, 86, 146))
        val capture = CaptureInbox.items(app).first { it.uri.lastPathSegment == photo.lastPathSegment }
        try {
            CaptureInbox.save(app, capture, listOf(FocusText(Rect(152, 136, 448, 211), "Look around.", "Etrafına bak.", Color.BLUE)))
            ActivityScenario.launch(CaptureInboxActivity::class.java).use {
                waitFor { firstCapture()?.endsWith("/${photo.lastPathSegment}") == true }
                openFirstCapture()
                waitFor { resumed()?.intent?.data?.lastPathSegment == photo.lastPathSegment }
                waitFor { hasText("1 Türkçe çeviri · 1 okunan metin") }
                touchFrame(drag = true)
                assertFalse(hasAccessibleText("Tam ekranı kapat"))
                touchFrame(drag = false)
                waitForAccessible("Tam ekranı kapat", "08-tam-ekran")
                assertTrue(hasAccessibleText("İngilizceyi göster"))
                screenshot("08-tam-ekran")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                onMain { resumed()?.finish() }
            }
        } finally {
            app.contentResolver.delete(photo, null, null)
            File(app.filesDir, "capture-inbox/${capture.key}.json").delete()
        }
    }

    @Test
    fun galleryAddsNewSavedCaptureWithoutRefreshAndPutsItFirst() {
        val files = mutableListOf<Uri>()
        try {
            val old = saveSample("old-screenshotUrl.png", Color.rgb(36, 97, 159)).also(files::add)
            ActivityScenario.launch(CaptureInboxActivity::class.java).use {
                waitFor { firstCapture()?.endsWith("/${old.lastPathSegment}") == true }
                val fresh = saveSample("new-screenshotUrl.png", Color.rgb(180, 65, 123)).also(files::add)
                waitFor { firstCapture()?.endsWith("/${fresh.lastPathSegment}") == true }
                assertTrue(onMain { currentGrid()?.adapter?.count == 2 })
                waitFor {
                    val grid = currentGrid() ?: return@waitFor false
                    grid.getChildAt(0)?.hasThumbnail() == true && grid.getChildAt(1)?.hasThumbnail() == true
                }
                screenshot("04-yakalamalar")
                openFirstCapture()
                waitFor { resumed() is FocusActivity }
                assertEquals(fresh.lastPathSegment, onMain { resumed()?.intent?.data?.lastPathSegment })
            }
        } finally {
            for (uri in files) app.contentResolver.delete(uri, null, null)
        }
    }

    private fun saveSample(name: String, color: Int, path: String = "Pictures/PS App/"): Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, values))
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun saveClip(): Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "new-master_playlist.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/PS App/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        instrumentation.context.assets.open("ps-app-capture.mp4").use { input ->
            checkNotNull(resolver.openOutputStream(uri)).use { output -> input.copyTo(output) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun cachePortalFrame(color: Int) {
        val frame = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        LiveFocusFrames.replace(frame, Source.PORTAL, SystemClock.elapsedRealtime() - 15_000L)
    }

    private fun focusFrameColor(): Int? {
        val view = resumed()?.window?.decorView?.findChild { it is FocusCanvas && it.isShown } ?: return null
        if (view.width == 0 || view.height == 0) return null
        val preview = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(preview))
            preview.getPixel(preview.width / 2, preview.height / 2)
        } finally {
            preview.recycle()
        }
    }

    private fun touchFrame(drag: Boolean) = onMain {
        val frame = checkNotNull(resumed()?.window?.decorView?.findChild { view -> view is FocusCanvas && view.isShown })
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val motion = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, frame.width * x, frame.height * y, 0)
            try {
                frame.dispatchTouchEvent(motion)
            } finally {
                motion.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0.37f, 0.39f)
        if (drag) send(MotionEvent.ACTION_MOVE, 0.63f, 0.6f)
        send(MotionEvent.ACTION_UP, if (drag) 0.63f else 0.37f, if (drag) 0.6f else 0.39f)
    }

    private fun firstCapture(): String? = onMain { currentGrid()?.getChildAt(0)?.tag?.toString() }

    private fun openFirstCapture() = onMain {
        val grid = checkNotNull(currentGrid())
        grid.performItemClick(grid.getChildAt(0), 0, grid.adapter.getItemId(0))
    }

    private fun currentGrid(): GridView? = resumed()?.window?.decorView?.findChild { it is GridView } as? GridView
    private fun View.hasThumbnail(): Boolean = findChild { it is ImageView && it.drawable is BitmapDrawable } != null
    private fun resumed(): Activity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).lastOrNull()

    private fun hasText(value: String): Boolean = onMain {
        resumed()?.window?.decorView?.findChild { it is TextView && it.text.toString() == value } != null
    }

    private fun assertReadingOrder(labels: List<String>) {
        val positions = onMain {
            val screen = checkNotNull(resumed()?.window?.decorView)
            labels.map { label ->
                val item = checkNotNull(screen.findChild { it is TextView && it.text.toString() == label })
                IntArray(2).also(item::getLocationInWindow)[1]
            }
        }
        assertTrue("Expected vertical order: $labels", positions.zipWithNext().all { (first, next) -> first < next })
    }

    private fun hasAccessibleText(value: String): Boolean =
        instrumentation.uiAutomation.rootInActiveWindow?.containsLabel(value) == true

    private fun optionNode(label: String): AccessibilityNodeInfo? =
        instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull { it.isClickable && it.text?.toString() == label }
        }

    private fun AccessibilityNodeInfo.containsLabel(value: String): Boolean {
        if (isVisibleToUser && packageName == app.packageName &&
            (text?.contains(value) == true || contentDescription?.contains(value) == true)
        ) return true
        return (0 until childCount).any { index -> getChild(index)?.containsLabel(value) == true }
    }

    private fun waitForAccessible(value: String, diagnostic: String) {
        repeat(100) {
            if (hasAccessibleText(value)) return
            Thread.sleep(175)
        }
        screenshot("$diagnostic-failed")
        assertTrue("Expected visible text: $value", hasAccessibleText(value))
    }

    private fun click(label: String) {
        onMain {
            val button = resumed()?.window?.decorView?.findChild { view ->
                view.isClickable && (view.contentDescription?.toString() == label || view is TextView && view.text.toString() == label)
            }
            assertNotNull(label, button)
            button!!.performClick()
        }
    }

    private fun View.findChild(match: (View) -> Boolean): View? {
        if (match(this)) return this
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).findChild(match)?.let { return it }
        return null
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        runCatching { instrumentation.uiAutomation.waitForIdle(350, 3_000) }
        Thread.sleep(280)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(app.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun waitFor(condition: () -> Boolean) {
        repeat(85) {
            instrumentation.waitForIdleSync()
            if (onMain(condition)) return
            Thread.sleep(150)
        }
        assertTrue("Android screen did not reach its expected state", onMain(condition))
    }

    private fun <T> onMain(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return checkNotNull(value).getOrThrow()
    }
}
