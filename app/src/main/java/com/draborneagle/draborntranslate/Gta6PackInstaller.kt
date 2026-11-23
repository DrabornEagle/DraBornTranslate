// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.util.AtomicFile
import com.gametranslator.gametranslator_mobile.core.GamePack
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Optional GTA 6 translation-pack import using GameTranslator's public v1.8.50 pack feed.
 * No paid translation API, no credentials and no copyrighted dialogue bundled in this APK.
 * User initiates installation. SHA-256 is checked against the remote manifest.
 * Source protocol: https://gitlab.com/sefflex/GameTranslator/-/blob/v1.8.50/mobile/android/app/src/main/kotlin/com/gametranslator/gametranslator_mobile/core/PackStore.kt
 */
internal object Gta6PackInstaller {
    private const val BASE_URL = "https://gametranslator-packs.gametranslator-pool.workers.dev"
    private const val NAME = "gta6-community.json.gz"
    private const val METADATA = "gta6-community.metadata"
    private const val MAX_MANIFEST = 512 * 1024
    private const val MAX_PACK_BYTES = 96 * 1024 * 1024
    private const val MAX_JSON_BYTES = 245 * 1024 * 1024

    internal data class Installed(val title: String, val entries: Int, val sha256: String)
    private data class Remote(val id: String, val title: String, val sha256: String, val entries: Int, val size: Long)

    fun installedFile(context: Context): File = File(context.filesDir, NAME)
    fun isInstalled(context: Context) = installedFile(context).isFile

    fun installedInfo(context: Context): Installed? {
        if (!isInstalled(context)) return null
        return runCatching {
            val json = JSONObject(File(context.filesDir, METADATA).readText())
            Installed(json.optString("title", "GTA 6"), json.optInt("entries", 0), json.optString("sha256", ""))
        }.getOrNull()
    }

    /** Must run on a worker thread. An existing verified pack survives failed downloads. */
    @Synchronized fun install(context: Context): Installed {
        val manifest = JSONObject(String(download(BASE_URL + "/v1/packs", MAX_MANIFEST), Charsets.UTF_8))
        val packs = manifest.optJSONArray("packs") ?: throw IOException("Sunucu oyun paketi listesi döndürmedi.")
        val matches = (0 until packs.length()).mapNotNull { index ->
            val entry = packs.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString("id").trim()
            if (!id.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) || id == "." || id == "..") return@mapNotNull null
            if (!entry.optString("lang", "tr").equals("tr", true)) return@mapNotNull null
            val title = entry.optString("title", id)
            if (!isGta6(id) && !isGta6(title)) return@mapNotNull null
            val hash = entry.optString("sha256").lowercase(Locale.ROOT)
            if (!hash.matches(Regex("[a-f0-9]{64}"))) return@mapNotNull null
            Remote(id, title, hash, entry.optInt("entries", 0), entry.optLong("size", -1))
        }
        val target = matches.maxByOrNull { it.entries }
            ?: throw IOException("Paket sunucusunda Türkçe GTA 6 paketi bulunamadı.")
        if (target.size > MAX_PACK_BYTES) throw IOException("Paket izin verilen boyuttan büyük.")
        val old = installedInfo(context)
        if (old != null && old.sha256 == target.sha256) return old

        val id = URLEncoder.encode(target.id, "UTF-8")
        val bytes = download(BASE_URL + "/v1/packs/" + id, MAX_PACK_BYTES)
        val actual = sha256(bytes)
        if (actual != target.sha256) throw IOException("Paket SHA-256 kontrolü başarısız. Eski paket korundu.")
        if (bytes.size >= 4 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            val size = (bytes[bytes.size-4].toLong() and 255) or
                ((bytes[bytes.size-3].toLong() and 255) shl 8) or
                ((bytes[bytes.size-2].toLong() and 255) shl 16) or
                ((bytes[bytes.size-1].toLong() and 255) shl 24)
            if (size > MAX_JSON_BYTES) throw IOException("Paket açılmış boyutu sınırı aşıyor.")
        }
        val parsed = GamePack.decode(bytes, target.title)
        if (parsed.size < 1) throw IOException("Pakette çevrilebilir satır bulunamadı.")
        val info = Installed(parsed.title, parsed.size, actual)

        val destination = AtomicFile(installedFile(context))
        val stream = destination.startWrite()
        try {
            stream.write(bytes)
            destination.finishWrite(stream)
        } catch (failure: Throwable) {
            destination.failWrite(stream)
            throw failure
        }
        val metadata = AtomicFile(File(context.filesDir, METADATA))
        val metaStream = metadata.startWrite()
        try {
            metaStream.write(JSONObject().put("title", info.title).put("entries", info.entries)
                .put("sha256", info.sha256).toString().toByteArray(Charsets.UTF_8))
            metadata.finishWrite(metaStream)
        } catch (failure: Throwable) {
            metadata.failWrite(metaStream)
            throw failure
        }
        return info
    }

    @Synchronized fun remove(context: Context) {
        AtomicFile(installedFile(context)).delete()
        AtomicFile(File(context.filesDir, METADATA)).delete()
    }

    private fun isGta6(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return normalized.contains("gta6") || normalized.contains("gtavi") ||
            normalized.contains("grandtheftauto6") || normalized.contains("grandtheftautovi")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun download(address: String, max: Int): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) throw IOException("Paket sunucusu HTTP " + connection.responseCode)
            if (connection.contentLengthLong > max) throw IOException("Paket boyutu sınırı aşıldı.")
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(max, 1 shl 17))
                val block = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(block)
                    if (count < 0) break
                    if (output.size() + count > max) throw IOException("Paket boyutu sınırı aşıldı.")
                    output.write(block, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
