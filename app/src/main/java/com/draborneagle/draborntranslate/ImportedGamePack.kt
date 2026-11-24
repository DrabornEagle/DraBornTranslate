// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.gametranslator.gametranslator_mobile.core.GamePack
import com.gametranslator.gametranslator_mobile.core.GamePackLibrary
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
    private const val GTA_FILE = "gta-6.json.gz"
    private const val IMPORTED_FILE = "oyun-paketi.json"
    private const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L
    private val digestPattern = Regex("[0-9a-fA-F]{64}")
    private var shared: GamePackLibrary? = null

    fun hasPack(context: Context): Boolean = file(context, IMPORTED_FILE).baseFile.isFile
    fun hasGta(context: Context): Boolean = file(context, GTA_FILE).baseFile.isFile

    @Synchronized
    fun library(context: Context): GamePackLibrary {
        val app = context.applicationContext
        return shared ?: GamePackLibrary(
            bundled = {
                buildList {
                    if (hasPack(app)) add(IMPORTED_FILE to { file(app, IMPORTED_FILE).readFully() })
                    if (hasGta(app)) add(GTA_FILE to { file(app, GTA_FILE).readFully() })
                }
            },
            userDirectory = null,
        ).also { shared = it }
    }

    @Synchronized
    fun install(context: Context, uri: Uri): String {
        val data = context.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_JSON_BYTES) }
            ?: throw IOException("Oyun paketi açılamadı.")
        val title = validate(data, "Oyun paketi").title
        write(file(context, IMPORTED_FILE), data)
        shared = null
        return title
    }

    @Synchronized
    fun downloadGta(context: Context, force: Boolean = true): String? {
        val prefs = context.getSharedPreferences("pack_download", Context.MODE_PRIVATE)
        if (!force && (!hasGta(context) || System.currentTimeMillis() - prefs.getLong("checked", 0) < CHECK_INTERVAL)) {
            return null
        }
        val manifest = JSONObject(request("/v1/packs", 512 * 1024).toString(Charsets.UTF_8))
        val entries = manifest.getJSONArray("packs")
        val item = (0 until entries.length()).asSequence().map(entries::getJSONObject).firstOrNull {
            it.optString("id") == "gta-6" && it.optString("lang", "tr").ifBlank { "tr" } == "tr"
        } ?: throw IOException("GTA 6 Türkçe paketi sunucuda bulunamadı.")
        val checksum = item.getString("sha256")
        val size = item.getLong("size")
        if (!digestPattern.matches(checksum) || size !in 1L..MAX_DOWNLOAD_BYTES.toLong()) {
            throw IOException("Paket boyutu veya doğrulama bilgisi geçersiz.")
        }
        val target = file(context, GTA_FILE)
        val upToDate = hasGta(context) && target.baseFile.length() == size &&
            prefs.getString("sha256", null).equals(checksum, ignoreCase = true)
        val count = if (upToDate) prefs.getInt("entries", 0) else {
            val data = request("/v1/packs/gta-6", size.toInt())
            if (data.size.toLong() != size || !sha256(data).equals(checksum, ignoreCase = true)) {
                throw IOException("Paket bütünlüğü doğrulanamadı.")
            }
            val verifiedCount = validate(data, "gta-6").size
            write(target, data)
            shared = null
            verifiedCount
        }
        prefs.edit().putString("sha256", checksum).putInt("entries", count)
            .putLong("checked", System.currentTimeMillis()).apply()
        return "GTA 6 Türkçe · ${java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("tr-TR")).format(count)} satır"
    }

    @Synchronized
    fun remove(context: Context, gta: Boolean = false) {
        file(context, if (gta) GTA_FILE else IMPORTED_FILE).delete()
        shared = null
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

    private fun request(path: String, maxBytes: Int): ByteArray {
        val connection = URI("$ORIGIN$path").toURL().openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (connection.responseCode != 200) throw IOException("Paket sunucusu HTTP ${connection.responseCode} döndürdü.")
            return connection.inputStream.use { readLimited(it, maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > maxBytes) throw IOException("Oyun paketi belirtilen boyutu aşıyor.")
            output.write(buffer, 0, count)
        }
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
