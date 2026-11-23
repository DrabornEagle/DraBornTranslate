// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Ortak havuz: Windows'taki LinePoolTests / PoolFilterTests ile aynı örnekler. */
class LinePoolTest {
    private val rejected = listOf(
        "Oturum Açtı", "Oturum Aqtl", "Oturum Aqtl.", "Sifirlama Seqenekleri", "Sifirlama Seqenekleri.",
        "PS5 Sistemini Yeniden Baslat", "PS5 Sistemini Yeniden Baslat.", "Oyunu Kapat", "Ana Ekranda Tut", "Lütfen bekleyin..",
        "Ekran ve Video", "ilerlemenizi takip edin Kupalar", "Keşfet PS5 ile ilgili Öne Çıkanlar", "Ekran görüntüsü kaydedildi.",
        "Kupa kazandınız!", "John signed in.", "You've earned a trophy!",
        "Please do not quit or turn off your console while the Rockstar cloud save icon is showing.",
        "GO to the", "Take out the trash and the", "it Auto V's Story Mode.", "FREE MONTH OF GTA+", "PHOTO MODE SELECT",
        "Main menu", "Hold on", "100/100 HP 25%",
    )
    private val accepted = listOf(
        "Herr Müller will see you now.", "This place was designed in a hurry.", "Tell Sam I signed the deal.",
        "I lie to you, you lie to me.", "DON TORRISI WANTS YOU!", "Get down!", "Who's there?", "Line number 7 here",
        "We need to move before the cops show up",
    )

    @Test
    fun filterKeepsGameDialogueAndDropsSystemTextFragmentsAndTurkish() {
        for (line in rejected) assertFalse("alınmamalı: $line", LinePool.accepts(line))
        for (line in accepted) assertTrue("alınmalı: $line", LinePool.accepts(line))
    }

    private fun tempFile(): File = Files.createTempDirectory("pool").toFile().resolve("pool.json")

    @Test
    fun linesAreSentOnceWithInstallIdAndGame() {
        val bodies = ArrayList<String>()
        val file = tempFile()
        val pool = LinePool(file, "GameTranslator 1.8.0", post = { _, body -> bodies.add(body); LinePool.Response(200) }, clock = { 0L })
        pool.setGame("PPSA01234_00", "Grand Theft Auto VI")
        pool.offer("We need to move before the cops show up")
        pool.offer("We need to move before the cops show up")
        pool.offer("Get down!")
        pool.offer("Oyunu Kapat")
        assertEquals(2, pool.queued.size)
        pool.flush()
        assertEquals(1, bodies.size)
        val json = JSONObject(bodies[0])
        assertEquals("android", json.getString("platform"))
        assertEquals("GameTranslator 1.8.0", json.getString("app"))
        assertEquals(36, json.getString("install").length)
        val lines = json.getJSONArray("lines")
        assertEquals(2, lines.length())
        assertEquals("Grand Theft Auto VI", lines.getJSONObject(0).getString("game"))
        assertEquals("PPSA01234_00", lines.getJSONObject(0).getString("titleId"))
        assertEquals("1970-01-01T00:00:00Z", lines.getJSONObject(0).getString("firstSeen"))
        assertTrue(pool.queued.isEmpty())
        // Gönderilen satır yeniden kuyruğa girmez; kimlik ve "gönderildi" kaydı diskte kalır
        pool.offer("Get down!")
        assertTrue(pool.queued.isEmpty())
        val reopened = LinePool(file, "GameTranslator 1.8.0", post = { _, _ -> LinePool.Response(200) })
        assertEquals(pool.installId, reopened.installId)
        reopened.offer("Get down!")
        assertTrue(reopened.queued.isEmpty())
    }

    @Test
    fun unreachableServerKeepsQueueAndTooManyRequestsWaits() {
        var now = 1_000_000L
        var calls = 0
        var status = 500
        val pool = LinePool(tempFile(), "GameTranslator", post = { _, _ -> calls++; LinePool.Response(status, "120") }, clock = { now })
        pool.offer("Get down!")
        pool.flush()
        assertEquals(1, pool.queued.size)
        status = 429
        pool.flush()
        assertEquals(2, calls)
        pool.flush()
        assertEquals("429'dan sonra beklenir", 2, calls)
        now += 121_000
        status = 200
        pool.flush()
        assertEquals(3, calls)
        assertTrue(pool.queued.isEmpty())
    }

    @Test
    fun turningOffDropsTheQueue() {
        val pool = LinePool(tempFile(), "GameTranslator", post = { _, _ -> LinePool.Response(200) })
        pool.offer("Get down!")
        pool.setEnabled(false)
        assertTrue(pool.queued.isEmpty())
        pool.offer("Who's there?")
        assertTrue(pool.queued.isEmpty())
    }
}
