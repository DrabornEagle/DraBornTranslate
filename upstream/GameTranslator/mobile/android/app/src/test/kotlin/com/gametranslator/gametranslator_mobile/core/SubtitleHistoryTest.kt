// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Altyazı geçmişi: Windows'taki SubtitleHistoryTests ile aynı kurallar ve aynı dosya biçimi. */
class SubtitleHistoryTest {
    private fun tempFile(): File = Files.createTempDirectory("history").toFile().resolve("history.json")

    @Test
    fun newestFirstAndEmptyTranslationIgnored() {
        val history = SubtitleHistory()
        assertTrue(history.add("Get down!", "Yere yat!"))
        assertTrue(history.add("Who's there?", "Kim var orada?", fromPack = true))
        assertFalse(history.add("Hello", "   "))
        val entries = history.entries()
        assertEquals(2, entries.size)
        assertEquals("Kim var orada?", entries[0].translation)
        assertTrue(entries[0].fromPack)
        assertEquals("Yere yat!", entries[1].translation)
    }

    @Test
    fun rereadOfTheSameLineUpdatesTheLastEntry() {
        val history = SubtitleHistory()
        history.add("We need to move before the cops show up", "Polisler gelmeden gitmeliyiz")
        history.add("We need to move before the cops show up.", "Polis gelmeden gitmemiz lazım")
        val entries = history.entries()
        assertEquals(1, entries.size)
        assertEquals("Polis gelmeden gitmemiz lazım", entries[0].translation)
    }

    @Test
    fun keepsOnlyTheLimitAndClipsLongText() {
        val history = SubtitleHistory(limit = 3)
        val lines = listOf("Get down!", "Who's there?", "Tell Sam I signed the deal.", "This place was designed in a hurry.", "Hold on")
        lines.forEachIndexed { i, line -> history.add(line, "Satır ${i + 1}") }
        assertEquals(listOf("Satır 5", "Satır 4", "Satır 3"), history.entries().map { it.translation })
        history.add("long", "a".repeat(800))
        assertEquals(SubtitleHistory.MAX_TEXT_LENGTH, history.entries()[0].translation.length)
    }

    @Test
    fun savesAndLoadsTheWindowsFormat() {
        val file = tempFile()
        val history = SubtitleHistory()
        history.add("Get down!", "Yere yat!", date = 1_763_000_000_000)
        history.add("Who's there?", "Kim var orada?", fromPack = true, date = 1_763_000_005_000)
        history.save(file)
        val json = JSONObject(file.readText())
        assertEquals(1, json.getInt("version"))
        val first = json.getJSONArray("entries").getJSONObject(0)
        assertEquals("pack", first.getString("origin"))
        assertEquals("2025-11-13T02:13:25Z", first.getString("date"))
        // Türkçe harfler dosyada okunur kalır.
        assertTrue(file.readText().contains("Kim var orada?"))

        val loaded = SubtitleHistory.load(file)
        assertEquals(history.entries(), loaded.entries())
        // Yeni kayıt eski kimliklerle çakışmaz.
        loaded.add("Something new entirely", "Yepyeni")
        assertEquals(2, loaded.entries()[0].id)
    }

    @Test
    fun readsWindowsFileWithFractionalSecondsAndClear() {
        val file = tempFile()
        file.writeText("""{"version":1,"entries":[{"id":7,"date":"2026-11-12T10:00:00.123Z","source":"Hi","translation":"Selam","origin":"engine"}]}""")
        val loaded = SubtitleHistory.load(file)
        assertEquals(1, loaded.entries().size)
        assertEquals("Selam", loaded.entries()[0].translation)
        assertFalse(loaded.entries()[0].fromPack)
        loaded.clear()
        loaded.save(file)
        assertEquals(0, SubtitleHistory.load(file).entries().size)
    }

    @Test
    fun missingOrBrokenFileGivesEmptyHistory() {
        val file = tempFile()
        assertEquals(0, SubtitleHistory.load(file).entries().size)
        file.writeText("{not json")
        assertEquals(0, SubtitleHistory.load(file).entries().size)
    }
}
