// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import com.gametranslator.gametranslator_mobile.core.GamePack
import com.gametranslator.gametranslator_mobile.core.GamePackLibrary
import com.gametranslator.gametranslator_mobile.core.PackKey
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

internal object ImportedGamePack {
    private const val MAX_JSON_BYTES = 96 * 1024 * 1024
    private const val MAX_DOWNLOAD_BYTES = 48 * 1024 * 1024
    private const val ORIGIN = "https://gametranslator-packs.gametranslator-pool.workers.dev"
    private const val GTA_ID = "gta-6"
    private const val GTA_FILE = "gta-6.json.gz"
    private const val GTA_LOCAL_FILE = "gta-6-local.json.gz"
    private const val IMPORTED_FILE = "oyun-paketi.json"
    private const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L
    private val digestPattern = Regex("[0-9a-fA-F]{64}")
    private val downloadLock = Any()
    private var gtaGeneration = 0L
    private var shared: GamePackLibrary? = null
    private var gtaLibrary: GamePackLibrary? = null

    fun hasPack(context: Context): Boolean = file(context, IMPORTED_FILE).baseFile.isFile
    fun hasGtaDownload(context: Context): Boolean = file(context, GTA_FILE).baseFile.isFile
    fun hasLocalGta(context: Context): Boolean = file(context, GTA_LOCAL_FILE).baseFile.isFile
    fun hasGta(context: Context): Boolean = gtaSource(context) != null

    @Synchronized
    fun library(context: Context, profile: GameReadingProfile = GameReadingProfile.GENERAL): GamePackLibrary {
        val app = context.applicationContext
        if (profile == GameReadingProfile.GTA_6) {
            return gtaLibrary ?: GamePackLibrary(
                bundled = { gtaSource(app)?.let { name -> listOf(name to { file(app, name).readFully() }) }.orEmpty() },
                userDirectory = null,
            ).also {
                it.detected = GTA_ID
                gtaLibrary = it
            }
        }
        return shared ?: GamePackLibrary(
            bundled = { sourceFiles(app) },
            userDirectory = null,
        ).also { shared = it }
    }

    fun sourceFiles(app: Context): List<Pair<String, () -> ByteArray>> = buildList {
        if (hasPack(app)) add(IMPORTED_FILE to { file(app, IMPORTED_FILE).readFully() })
        gtaSource(app)?.let { name -> add(name to { file(app, name).readFully() }) }
    }

    fun downloadedChecksum(context: Context): String? =
        context.getSharedPreferences("pack_download", Context.MODE_PRIVATE).getString("sha256", null)
            ?.takeIf { hasGtaDownload(context) && digestPattern.matches(it) }

    data class InstallResult(val title: String, val gta: Boolean)

    @Synchronized
    fun install(context: Context, uri: Uri): InstallResult {
        val data = context.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_JSON_BYTES) }
            ?: throw IOException("Oyun paketi açılamadı.")
        val pack = validate(data, "Oyun paketi")
        val gta = pack.id == GTA_ID && readGtaTranslations(data.inputStream(), emptySet()) != null
        write(file(context, if (gta) GTA_LOCAL_FILE else IMPORTED_FILE), data)
        if (gta) gtaLibrary = null
        shared = null
        return InstallResult(pack.title, gta)
    }

    /**
     * Read only the selected mission's exact keys. A streaming reader keeps the large subtitle
     * dictionary and its OCR index out of the mission screen's memory.
     */
    fun gtaTranslations(context: Context, keys: Set<Long>): Map<Long, String> {
        if (keys.isEmpty()) return emptyMap()
        val input = synchronized(this) {
            // Versions before 0.7.1 saved all custom packs in one slot.
            val name = gtaSource(context) ?: IMPORTED_FILE.takeIf { hasPack(context) }
            name?.let { file(context, it).openRead() }
        } ?: return emptyMap()
        return input.use { readGtaTranslations(it, keys).orEmpty() }
    }

    private fun readGtaTranslations(input: InputStream, keys: Set<Long>): Map<Long, String>? {
        val buffered = input.buffered()
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val content = if (gzip) GZIPInputStream(buffered, 64 * 1024) else buffered
        val entries = mutableMapOf<Long, String>()
        val segments = mutableMapOf<Long, String>()
        var gameId: String? = null
        var language = "tr"
        JsonReader(content.bufferedReader(Charsets.UTF_8)).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "id" -> if (json.peek() == JsonToken.STRING) gameId = json.nextString() else json.skipValue()
                    "lang" -> if (json.peek() == JsonToken.STRING) language = json.nextString().ifBlank { "tr" }
                              else json.skipValue()
                    "entries" -> readSubtitleTable(json, keys, entries)
                    "segments" -> readSubtitleTable(json, keys, segments)
                    else -> json.skipValue()
                }
            }
            json.endObject()
        }
        return if (gameId == GTA_ID && language == "tr") segments + entries else null
    }

    private fun readSubtitleTable(json: JsonReader, keys: Set<Long>, matches: MutableMap<Long, String>) {
        if (keys.isEmpty() || json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return
        }
        json.beginObject()
        while (json.hasNext()) {
            val key = PackKey.number(json.nextName())
            if (key != null && key in keys && json.peek() == JsonToken.STRING) {
                GamePack.cleaned(json.nextString()).takeIf(String::isNotEmpty)?.let { matches[key] = it }
            } else json.skipValue()
        }
        json.endObject()
    }

    fun downloadGta(context: Context, force: Boolean = true): String? {
        synchronized(downloadLock) {
            val generation = synchronized(this) { gtaGeneration }
            val prefs = context.getSharedPreferences("pack_download", Context.MODE_PRIVATE)
            if (!force && (!hasGtaDownload(context) || System.currentTimeMillis() - prefs.getLong("checked", 0) < CHECK_INTERVAL)) {
                return null
            }
            val manifest = JSONObject(request("/v1/packs", 512 * 1024).toString(Charsets.UTF_8))
            val entries = manifest.getJSONArray("packs")
            val item = (0 until entries.length()).asSequence().map(entries::getJSONObject).firstOrNull {
                it.optString("id") == GTA_ID && it.optString("lang", "tr").ifBlank { "tr" } == "tr"
            } ?: throw IOException("GTA 6 Türkçe paketi sunucuda bulunamadı.")
            val checksum = item.getString("sha256")
            val size = item.getLong("size")
            if (!digestPattern.matches(checksum) || size !in 1L..MAX_DOWNLOAD_BYTES.toLong()) {
                throw IOException("Paket boyutu veya doğrulama bilgisi geçersiz.")
            }
            val target = file(context, GTA_FILE)
            val upToDate = hasGtaDownload(context) && target.baseFile.length() == size &&
                prefs.getString("sha256", null).equals(checksum, ignoreCase = true)
            val data = if (upToDate) null else request("/v1/packs/gta-6", size.toInt(), exactSize = true)
            if (data != null && !sha256(data).equals(checksum, ignoreCase = true)) {
                throw IOException("Paket bütünlüğü doğrulanamadı.")
            }
            val count = data?.let { validate(it, "gta-6").size } ?: prefs.getInt("entries", 0)
            synchronized(this) {
                if (gtaGeneration != generation) return null
                if (data != null) {
                    write(target, data)
                    shared = null
                    gtaLibrary = null
                }
                prefs.edit().putString("sha256", checksum).putInt("entries", count)
                    .putLong("checked", System.currentTimeMillis()).apply()
            }
            val formattedCount = java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("tr-TR")).format(count)
            return "GTA 6 Türkçe · $formattedCount satır"
        }
    }

    @Synchronized
    fun remove(context: Context, gta: Boolean = false) {
        file(context, if (gta) GTA_FILE else IMPORTED_FILE).delete()
        if (gta) {
            gtaGeneration++
            gtaLibrary = null
            context.getSharedPreferences("pack_download", Context.MODE_PRIVATE).edit().clear().apply()
        }
        shared = null
    }

    @Synchronized
    fun removeLocalGta(context: Context) {
        file(context, GTA_LOCAL_FILE).delete()
        gtaLibrary = null
        shared = null
    }

    private fun gtaSource(context: Context): String? = when {
        hasLocalGta(context) -> GTA_LOCAL_FILE
        hasGtaDownload(context) -> GTA_FILE
        else -> null
    }

    private fun validate(data: ByteArray, name: String): GamePack {
        val compressed = data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()
        if (compressed) {
            GZIPInputStream(data.inputStream(), 64 * 1024).use { input ->
                val buffer = ByteArray(64 * 1024)
                var size = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_JSON_BYTES) throw IOException("Açılan oyun paketi çok büyük.")
                }
            }
        }
        return GamePack.decode(data, name).also { if (it.size == 0) throw IOException("Oyun paketi boş.") }
    }

    private fun file(context: Context, name: String) = AtomicFile(File(context.filesDir, name))

    private fun write(destination: AtomicFile, data: ByteArray) {
        val output = destination.startWrite()
        try {
            output.write(data)
            destination.finishWrite(output)
        } catch (e: IOException) {
            destination.failWrite(output)
            throw e
        }
    }

    private fun request(path: String, maxBytes: Int, exactSize: Boolean = false): ByteArray =
        requestPackBytes(maxBytes, exactSize) {
            URI("$ORIGIN$path").toURL().openConnection() as HttpsURLConnection
        }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
