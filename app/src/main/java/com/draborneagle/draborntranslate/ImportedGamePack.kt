// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.gametranslator.gametranslator_mobile.core.GamePack
import com.gametranslator.gametranslator_mobile.core.GamePackLibrary
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal object ImportedGamePack {
    private const val MAX_JSON_BYTES = 96 * 1024 * 1024
    private const val MAX_DOWNLOAD_BYTES = 48 * 1024 * 1024
    private const val ORIGIN = "https://gametranslator-packs.gametranslator-pool.workers.dev"
    private const val GTA_FILE = "gta-6.json.gz"
    private const val IMPORTED_FILE = "oyun-paketi.json"
    private const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L
    private val digestPattern = Regex("[0-9a-fA-F]{64}")
    private val downloadLock = Any()
    private var gtaGeneration = 0L
    private var shared: GamePackLibrary? = null

    fun hasPack(context: Context): Boolean = file(context, IMPORTED_FILE).baseFile.isFile
    fun hasGta(context: Context): Boolean = file(context, GTA_FILE).baseFile.isFile

    @Synchronized
    fun library(context: Context): GamePackLibrary {
        val app = context.applicationContext
        return shared ?: GamePackLibrary(
            bundled = { sourceFiles(app) },
            userDirectory = null,
        ).also { shared = it }
    }

    fun sourceFiles(app: Context): List<Pair<String, () -> ByteArray>> = buildList {
        if (hasPack(app)) add(IMPORTED_FILE to { file(app, IMPORTED_FILE).readFully() })
        if (hasGta(app)) add(GTA_FILE to { file(app, GTA_FILE).readFully() })
    }

    fun downloadedChecksum(context: Context): String? =
        context.getSharedPreferences("pack_download", Context.MODE_PRIVATE).getString("sha256", null)
            ?.takeIf { hasGta(context) && digestPattern.matches(it) }

    @Synchronized
    fun install(context: Context, uri: Uri): String {
        val data = context.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_JSON_BYTES) }
            ?: throw IOException("Oyun paketi açılamadı.")
        val title = validate(data, "Oyun paketi").title
        write(file(context, IMPORTED_FILE), data)
        shared = null
        return title
    }

    fun downloadGta(context: Context, force: Boolean = true): String? {
        synchronized(downloadLock) {
            val generation = synchronized(this) { gtaGeneration }
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
            context.getSharedPreferences("pack_download", Context.MODE_PRIVATE).edit().clear().apply()
        }
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

    private fun request(path: String, maxBytes: Int, exactSize: Boolean = false): ByteArray =
        requestPackBytes(maxBytes, exactSize) {
            URI("$ORIGIN$path").toURL().openConnection() as HttpsURLConnection
        }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private class PackHttpError(val status: Int) : IOException("Paket sunucusu HTTP $status döndürdü.")
private class PackSizeError : IOException("Oyun paketi belirtilen boyutu aşıyor.")

internal fun requestPackBytes(
    maxBytes: Int,
    exactSize: Boolean = false,
    waitBeforeRetry: (Long) -> Unit = Thread::sleep,
    connect: () -> HttpURLConnection,
): ByteArray {
    var attempt = 1
    while (true) {
        try {
            val connection = connect()
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Connection", "close")
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw PackHttpError(code)
                val data = connection.inputStream.use { readLimited(it, maxBytes) }
                if (exactSize && data.size != maxBytes) throw EOFException("Incomplete game pack")
                return data
            } finally {
                connection.disconnect()
            }
        } catch (error: IOException) {
            when (error) {
                is PackSizeError -> throw error
                is PackHttpError -> if (error.status != 408 && error.status !in 500..599) throw error
                is SSLHandshakeException, is SSLPeerUnverifiedException ->
                    throw IOException("Paket sunucusuyla güvenli bağlantı kurulamadı.", error)
            }
            if (attempt == 3) {
                throw IOException("Oyun paketi indirilemedi. İnternet bağlantını kontrol edip tekrar dene.", error)
            }
            try {
                waitBeforeRetry(350L * attempt * attempt)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Oyun paketi indirmesi durduruldu.").apply { initCause(interrupted) }
            }
            attempt++
        }
    }
}

private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > maxBytes) throw PackSizeError()
        output.write(buffer, 0, count)
    }
}
