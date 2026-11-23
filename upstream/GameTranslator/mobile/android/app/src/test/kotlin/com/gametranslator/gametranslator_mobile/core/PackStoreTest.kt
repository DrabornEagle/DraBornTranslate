// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// CouchSubs'tan (Copyright (C) 2026 Sinan Yorulmaz, AGPL-3.0) uyarlanan denemeler.
class PackStoreTest {
    private class FakeServer(var packs: List<RemotePack>, val files: MutableMap<String, ByteArray>) : PackServer {
        val downloads = mutableListOf<String>()
        var status: Int? = null
        override var identity = ""
        override fun list() = packs
        override fun download(id: String): ByteArray {
            status?.let { throw PackDownloadException(it, "HTTP $it") }
            downloads.add(id)
            return files[id] ?: throw PackDownloadException(404, "missing")
        }
    }

    private fun remote(id: String, data: ByteArray, version: Long = 1) = RemotePack(id, id.uppercase(), version, PackStore.sha256(data), data.size.toLong(), 1)

    private val gta = "gta".toByteArray()
    private val rdr = "rdr".toByteArray()

    @Test
    fun downloadsVerifiesAndReusesPacks() {
        val dir = Files.createTempDirectory("packs").toFile()
        var now = 1_000L
        val server = FakeServer(listOf(remote("gta-v", gta), remote("rdr2", rdr)), mutableMapOf("gta-v" to gta, "rdr2" to rdr))
        val store = PackStore(dir, server, { now })
        assertEquals(listOf("gta-v", "rdr2"), store.refresh().downloaded)
        assertEquals(setOf("gta-v.json.gz", "rdr2.json.gz"), store.files().map { it.name }.toSet())
        // Haftası dolmadan sunucuya bakılmaz.
        now += 60_000
        assertTrue(store.refresh().skipped)
        // Zorla yenilemede aynı sürüm yeniden inmez.
        assertEquals(emptyList<String>(), store.refresh(force = true).downloaded)
        assertEquals(listOf("gta-v", "rdr2"), server.downloads)
    }

    @Test
    fun weeklyRefreshUpdatesChangedAndRemovesDroppedPacks() {
        val dir = Files.createTempDirectory("packs").toFile()
        var now = 1_000L
        val server = FakeServer(listOf(remote("gta-v", gta), remote("rdr2", rdr)), mutableMapOf("gta-v" to gta, "rdr2" to rdr))
        val store = PackStore(dir, server, { now })
        store.refresh()
        val newGta = "gta v2".toByteArray()
        server.files["gta-v"] = newGta
        server.packs = listOf(remote("gta-v", newGta, version = 2))
        now += PackStore.REFRESH_INTERVAL_MS
        val result = store.refresh()
        assertEquals(listOf("gta-v"), result.downloaded)
        assertEquals(listOf("rdr2"), result.removed)
        assertEquals("gta v2", File(dir, "gta-v.json.gz").readText())
        assertFalse(File(dir, "rdr2.json.gz").exists())
        assertEquals(2L, store.installed().single().version)
    }

    @Test
    fun corruptDownloadIsNeverInstalled() {
        val dir = Files.createTempDirectory("packs").toFile()
        val server = FakeServer(listOf(remote("gta-v", gta)), mutableMapOf("gta-v" to "tampered".toByteArray()))
        val store = PackStore(dir, server)
        assertEquals(emptyList<String>(), store.refresh().downloaded)
        assertTrue(store.files().isEmpty())
        assertFalse(File(dir, "gta-v.json.gz").exists())
        // Kontrol zamanı ilerlemez: sonraki açılışta yeniden denenir.
        server.files["gta-v"] = gta
        assertEquals(listOf("gta-v"), store.refresh().downloaded)
    }

    @Test
    fun rateLimitedDownloadKeepsTheOldVersionAndRetries() {
        val dir = Files.createTempDirectory("packs").toFile()
        var now = 1_000L
        val server = FakeServer(listOf(remote("gta-v", gta)), mutableMapOf("gta-v" to gta))
        val store = PackStore(dir, server, { now })
        store.refresh()
        val newGta = "gta v2".toByteArray()
        server.files["gta-v"] = newGta
        server.packs = listOf(remote("gta-v", newGta, version = 2))
        server.status = 429
        now += PackStore.REFRESH_INTERVAL_MS
        assertEquals(emptyList<String>(), store.refresh().downloaded)
        assertEquals("gta", File(dir, "gta-v.json.gz").readText())
        // Kontrol zamanı ilerlemedi: sınır kalkınca hemen yeniden denenir.
        server.status = null
        now += 1000
        assertEquals(listOf("gta-v"), store.refresh().downloaded)
    }

    /** Uygulamayla gelen paketin aynısı indirilmez; sunucuda sürümü değişince iner. */
    @Test
    fun packsAlreadyInTheAppAreNotDownloaded() {
        val dir = Files.createTempDirectory("packs").toFile()
        var now = 1_000L
        var hashed = 0
        val server = FakeServer(listOf(remote("gta-v", gta), remote("rdr2", rdr)), mutableMapOf("gta-v" to gta, "rdr2" to rdr))
        val store = PackStore(dir, server, { now }, { hashed++; setOf(PackStore.sha256(gta)) })
        assertEquals(listOf("rdr2"), store.refresh().downloaded)
        assertEquals(listOf("rdr2"), server.downloads)
        assertEquals(listOf("rdr2.json.gz"), store.files().map { it.name })
        assertEquals(listOf("rdr2"), store.installed().map { it.id })
        assertEquals(1, hashed)
        // Haftası dolmadan bakılmaz; dolunca aynı sürümler için uygulamadaki dosyalar yeniden özetlenmez.
        assertTrue(store.refresh().skipped)
        now += PackStore.REFRESH_INTERVAL_MS
        assertEquals(emptyList<String>(), store.refresh().downloaded)
        assertEquals(1, hashed)
        val newGta = "gta v2".toByteArray()
        server.files["gta-v"] = newGta
        server.packs = listOf(remote("gta-v", newGta, version = 2), remote("rdr2", rdr))
        assertEquals(listOf("gta-v"), store.refresh(force = true).downloaded)
        assertEquals(setOf("gta-v.json.gz", "rdr2.json.gz"), store.files().map { it.name }.toSet())
    }

    @Test
    fun downloadedPacksLoadIntoTheLibrary() {
        val dir = Files.createTempDirectory("packs").toFile()
        val pack = Fixtures.bytes("pack-wrapped.json.gz")
        val store = PackStore(dir, FakeServer(listOf(remote("test-game", pack)), mutableMapOf("test-game" to pack)))
        store.refresh()
        val library = GamePackLibrary(null, store.files().first().parentFile)
        assertEquals(listOf("test-game"), library.infos.map { it.id })
        assertEquals("Yere yat!", library.find("Get Down!"))
    }
    private fun pack(id: String, title: String, data: ByteArray) = RemotePack(id, title, 1, PackStore.sha256(data), data.size.toLong(), 1)

    private fun switched(old: List<RemotePack>, oldFiles: Map<String, ByteArray>, dir: File, now: () -> Long = { 1_000L }) {
        val server = FakeServer(old, oldFiles.toMutableMap())
        server.identity = "https://old.example"
        PackStore(dir, server, now).refresh()
    }

    @Test
    fun switchingServerChecksTheNewServerAtOnceAndKeepsOtherGames() {
        val dir = Files.createTempDirectory("packs").toFile()
        var now = 1_000L
        switched(listOf(remote("gta-6", gta), remote("rdr2", rdr)), mapOf("gta-6" to gta, "rdr2" to rdr), dir) { now }
        now += 60_000
        val ours = "gta ours".toByteArray()
        val new = FakeServer(listOf(remote("gta-6", ours)), mutableMapOf("gta-6" to ours))
        new.identity = "https://new.example"
        val store = PackStore(dir, new, { now })
        val result = store.refresh()
        assertFalse(result.skipped)
        assertEquals(listOf("gta-6"), result.downloaded)
        assertEquals(emptyList<String>(), result.removed)
        assertEquals("gta ours", File(dir, "gta-6.json.gz").readText())
        assertEquals(setOf("rdr2"), store.legacyIds())
        now += 60_000
        assertTrue(store.refresh().skipped)
    }

    @Test
    fun emptyListRemovesNothing() {
        val dir = Files.createTempDirectory("packs").toFile()
        var now = 1_000L
        val server = FakeServer(listOf(remote("gta-6", gta)), mutableMapOf("gta-6" to gta))
        val store = PackStore(dir, server, { now })
        store.refresh()
        val checked = store.lastCheck
        server.packs = emptyList()
        now += PackStore.REFRESH_INTERVAL_MS
        assertTrue(runCatching { store.refresh() }.isFailure)
        assertEquals(1, store.files().size)
        assertEquals(checked, store.lastCheck)
    }

    @Test
    fun failedDownloadAfterServerSwitchRetriesOnNextLaunch() {
        val dir = Files.createTempDirectory("packs").toFile()
        var now = 1_000L
        switched(listOf(remote("gta-6", gta)), mapOf("gta-6" to gta), dir) { now }
        val ours = "gta ours".toByteArray()
        val new = FakeServer(listOf(remote("gta-6", ours)), mutableMapOf("gta-6" to ours))
        new.identity = "https://new.example"
        new.status = 503
        val store = PackStore(dir, new, { now })
        now += 60_000
        assertEquals(emptyList<String>(), store.refresh().downloaded)
        assertEquals("gta", File(dir, "gta-6.json.gz").readText())
        new.status = null
        now += 60_000
        assertEquals(listOf("gta-6"), store.refresh().downloaded)
        assertEquals(emptySet<String>(), store.legacyIds())
    }

    @Test
    fun previousServerPacksOfAGameWeServeAreRemoved() {
        val dir = Files.createTempDirectory("packs").toFile()
        val extra = "extra".toByteArray()
        switched(listOf(pack("gta-6", "Grand Theft Auto VI", gta), pack("pool-gta-6", "Grand Theft Auto VI (ek satırlar)", extra),
            pack("red-dead-redemption-2", "Red Dead Redemption 2", rdr), pack("pool-rdr-2", "Red Dead Redemption 2 (ek satırlar)", extra)),
            mapOf("gta-6" to gta, "pool-gta-6" to extra, "red-dead-redemption-2" to rdr, "pool-rdr-2" to extra), dir)
        val ours = "gta ours".toByteArray()
        val new = FakeServer(listOf(pack("gta-6", "Grand Theft Auto VI", ours)), mutableMapOf("gta-6" to ours))
        new.identity = "https://new.example"
        val store = PackStore(dir, new, { 2_000L })
        assertEquals(listOf("pool-gta-6"), store.refresh().removed)
        assertEquals(setOf("gta-6.json.gz", "red-dead-redemption-2.json.gz", "pool-rdr-2.json.gz"), store.files().map { it.name }.toSet())
        assertEquals(setOf("red-dead-redemption-2", "pool-rdr-2"), store.legacyIds())
    }

    @Test
    fun previousServerPackNeverWinsOverOurs() {
        val dir = Files.createTempDirectory("packs").toFile()
        val onlyOld = "Where the hell did you park the boat?"
        val shared = "Dispatch, we got them, they're pinned in!"
        val a = PackKey.key(onlyOld)!!
        val b = PackKey.key(shared)!!
        val theirs = """{"$a": "tekne nerede", "$b": "eski çeviri"}""".toByteArray()
        switched(listOf(remote("pool-gta-6", theirs)), mapOf("pool-gta-6" to theirs), dir)
        val ours = """{"$b": "bizim çeviri"}""".toByteArray()
        val new = FakeServer(listOf(remote("gta-6", ours)), mutableMapOf("gta-6" to ours))
        new.identity = "https://new.example"
        val store = PackStore(dir, new, { 2_000L })
        store.refresh()
        val library = GamePackLibrary(null, dir, lowPriority = { store.legacyIds() })
        assertEquals("tekne nerede", library.find(onlyOld))
        assertEquals("bizim çeviri", library.find(shared))
        assertEquals("bizim çeviri", library.find(shared))
    }

    @Test
    fun packsInOtherLanguagesAreIgnored() {
        val dir = Files.createTempDirectory("packs").toFile()
        val arabic = "gta ar".toByteArray()
        val ar = remote("gta-6-ar", arabic).copy(lang = "ar")
        val server = FakeServer(listOf(remote("gta-6", gta), ar), mutableMapOf("gta-6" to gta, "gta-6-ar" to arabic))
        val store = PackStore(dir, server, { 1_000L })
        assertEquals(listOf("gta-6"), store.refresh().downloaded)
        assertEquals(listOf("gta-6"), server.downloads)
        assertEquals(listOf("gta-6"), PackStore.usable(server.packs).map { it.id })
        server.packs = listOf(ar)
        assertTrue(runCatching { store.refresh(force = true) }.isFailure)
        assertEquals(setOf("gta-6.json.gz"), store.files().map { it.name }.toSet())
    }

    @Test
    fun arabicStoreDownloadsOnlyArabicPacks() {
        val dir = Files.createTempDirectory("packs-ar").toFile()
        val arabic = "gta ar".toByteArray()
        val ar = remote("gta-6-ar", arabic).copy(lang = "ar")
        val server = FakeServer(listOf(remote("gta-6", gta), ar), mutableMapOf("gta-6" to gta, "gta-6-ar" to arabic))
        val store = PackStore(dir, server, { 1_000L }, language = "ar")
        assertEquals(listOf("gta-6-ar"), store.refresh().downloaded)
        assertEquals(listOf("gta-6-ar"), server.downloads)
        assertEquals(setOf("gta-6-ar.json.gz"), store.files().map { it.name }.toSet())
    }

    @Test
    fun packLanguageFollowsTheTargetLanguage() {
        assertEquals("tr", PackTranslation.packLanguage("en", "tr"))
        assertEquals("ar", PackTranslation.packLanguage("en", "ar"))
        assertEquals(null, PackTranslation.packLanguage("en", "de"))
        assertEquals(null, PackTranslation.packLanguage("ja", "tr"))
        assertFalse(PackTranslation.applies("en", "ar"))
    }
}
