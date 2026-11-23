// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.util.AtomicFile
import com.gametranslator.gametranslator_mobile.core.GamePack
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
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
    @Synchronized fun install(context: Context, onProgress: (Int) -> Unit = {}): Installed {
        val manifest = JSONObject(String(
            ReliablePackDownloader.manifest(URL(BASE_URL + "/v1/packs"), MAX_MANIFEST),
            Charsets.UTF_8
        ))
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
        if (target.size !in 1..MAX_PACK_BYTES.toLong() || target.entries < 1) {
            throw IOException("Paket boyutu veya kayıt sayısı geçersiz.")
        }
        val old = installedInfo(context)
        if (old != null && old.sha256 == target.sha256 &&
            runCatching { sha256(installedFile(context).readBytes()) == target.sha256 }.getOrDefault(false)
        ) {
            onProgress(100)
            return old
        }

        val id = URLEncoder.encode(target.id, "UTF-8")
        val partial = File(context.filesDir, NAME + ".part")
        val bytes = ReliablePackDownloader.archive(
            URL(BASE_URL + "/v1/packs/" + id),
            partial, target.size, target.sha256, onProgress
        )
        val actual = target.sha256
        if (bytes.size >= 4 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            val size = (bytes[bytes.size-4].toLong() and 255) or
                ((bytes[bytes.size-3].toLong() and 255) shl 8) or
                ((bytes[bytes.size-2].toLong() and 255) shl 16) or
                ((bytes[bytes.size-1].toLong() and 255) shl 24)
            if (size > MAX_JSON_BYTES) throw IOException("Paket açılmış boyutu sınırı aşıyor.")
        }
        val parsed = GamePack.decode(bytes, target.title)
        if (parsed.size < 1) throw IOException("Pakette çevrilebilir satır bulunamadı.")
        // Never advertise the 475k-line pack merely because the server says so.
        // Verify the real number of decoded, usable Turkish lines before activation.
        if (parsed.size != target.entries) {
            throw IOException("GTA 6 paketi doğrulanamadı: sunucu ${target.entries}, gerçek ${parsed.size} satır. Eski paket korundu.")
        }
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
        File(context.filesDir, NAME + ".part").delete()
        File(context.filesDir, NAME + ".part.state").delete()
        return info
    }

    @Synchronized fun remove(context: Context) {
        AtomicFile(installedFile(context)).delete()
        AtomicFile(File(context.filesDir, METADATA)).delete()
        File(context.filesDir, NAME + ".part").delete()
        File(context.filesDir, NAME + ".part.state").delete()
    }

    private fun isGta6(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return normalized.contains("gta6") || normalized.contains("gtavi") ||
            normalized.contains("grandtheftauto6") || normalized.contains("grandtheftautovi")
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }

}
