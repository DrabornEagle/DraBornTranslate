// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal object GtaMissionStore {
    private val repository = URI("https://raw.githubusercontent.com/DrabornEagle/DraBornTranslate/main/packs/gta-6/")
    private val digestPattern = Regex("[a-f0-9]{64}")
    private val lock = Any()
    private val bundledPrefix = "gta-6-reference-"
    private val publishedVersion = Regex("[0-9]{4}[.][0-9]{2}[.][0-9]{2}[.][0-9]+")

    fun installed(context: Context): Boolean = hasDownload(context) || hasLocal(context)
    fun hasDownload(context: Context): Boolean = destination(context, local = false).isFile
    fun hasLocal(context: Context): Boolean = destination(context, local = true).isFile

    fun catalog(context: Context): GtaMissionCatalog? = synchronized(lock) {
        activeFile(context)?.let(GtaMissionPack::catalog)
    }

    fun texts(context: Context, catalog: GtaMissionCatalog, mission: GtaMission): List<GtaMissionText> = synchronized(lock) {
        val file = activeFile(context) ?: throw IOException("Görev paketi açılamadı.")
        GtaMissionPack.texts(file, catalog.version, mission)
    }

    fun download(context: Context): GtaMissionCatalog = synchronized(lock) {
        val manifest = try {
            JSONObject(requestPackBytes(8 * 1024) { connection("manifest.json") }.toString(Charsets.UTF_8))
        } catch (error: JSONException) {
            throw IOException("GitHub paket bilgisi okunamadı.", error)
        }
        val checksum = manifest.optString("sha256")
        val size = manifest.optLong("bytes")
        val version = manifest.optString("version")
        if (!digestPattern.matches(checksum) || size !in 1L..GtaMissionPack.MAX_ARCHIVE_BYTES.toLong() || version.isBlank()) {
            throw IOException("GitHub paket bilgisi geçersiz.")
        }
        val destination = destination(context, local = false)
        if (destination.isFile && destination.length() == size && sha256(destination) == checksum) {
            val catalog = GtaMissionPack.catalog(destination)
            if (catalog.version != version) throw IOException("GitHub paketinin sürüm bilgisi uyuşmuyor.")
            return@synchronized catalog
        }
        stage(destination) { temp ->
            requestPackFile(size.toInt(), temp) { connection("gta-6-tr-${checksum.take(12)}.zip") }
            if (sha256(temp) != checksum) throw IOException("GitHub paketinin bütünlüğü doğrulanamadı.")
            val catalog = GtaMissionPack.validate(temp)
            if (catalog.version != version) throw IOException("GitHub paketinin sürüm bilgisi uyuşmuyor.")
            catalog
        }
    }

    fun installLocal(context: Context, uri: Uri): GtaMissionCatalog = synchronized(lock) {
        stage(destination(context, local = true)) { temp ->
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Görev paketi açılamadı.")
            input.use { source -> temp.outputStream().use { copyLimited(source, it, GtaMissionPack.MAX_ARCHIVE_BYTES) } }
            GtaMissionPack.validate(temp)
        }
    }

    fun remove(context: Context, local: Boolean) {
        synchronized(lock) { Files.deleteIfExists(destination(context, local).toPath()) }
    }

    private fun stage(destination: File, prepare: (File) -> GtaMissionCatalog): GtaMissionCatalog {
        val pending = File.createTempFile("gta-missions-", ".part", destination.parentFile)
        try {
            val catalog = prepare(pending)
            Files.move(pending.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            return catalog
        } finally {
            pending.delete()
        }
    }

    private fun connection(asset: String): HttpsURLConnection =
        (repository.resolve(asset).toURL().openConnection() as HttpsURLConnection).apply {
            setRequestProperty("User-Agent", "DraBornTranslate Android")
        }

    private fun activeFile(context: Context): File? {
        val local = destination(context, local = true)
        if (local.isFile) return local
        val downloaded = destination(context, local = false).takeIf(File::isFile)
        val asset = context.assets.list("")?.firstOrNull { it.startsWith(bundledPrefix) && it.endsWith(".zip") }
            ?: return downloaded
        val bundledVersion = asset.removePrefix(bundledPrefix).removeSuffix(".zip")
        if (downloaded != null) {
            val currentVersion = runCatching { GtaMissionPack.catalog(downloaded).version }.getOrNull()
            if (currentVersion == bundledVersion || currentVersion != null && isNewer(currentVersion, bundledVersion)) {
                return downloaded
            }
        }
        val bundled = File(context.cacheDir, asset)
        if (!bundled.isFile) {
            stage(bundled) { temp ->
                context.assets.open(asset).use { source ->
                    temp.outputStream().use { copyLimited(source, it, GtaMissionPack.MAX_ARCHIVE_BYTES) }
                }
                GtaMissionPack.catalog(temp)
            }
        }
        return bundled
    }

    private fun isNewer(version: String, baseline: String): Boolean {
        if (!publishedVersion.matches(version) || !publishedVersion.matches(baseline)) return false
        val parts = version.split('.').zip(baseline.split('.')) { value, reference ->
            value.toLongOrNull()?.compareTo(reference.toLongOrNull() ?: 0L) ?: 0
        }
        return (parts.firstOrNull { it != 0 } ?: 0) > 0
    }

    private fun destination(context: Context, local: Boolean) = File(context.filesDir,
        if (local) "gta-6-missions-local.zip" else "gta-6-missions.zip")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
