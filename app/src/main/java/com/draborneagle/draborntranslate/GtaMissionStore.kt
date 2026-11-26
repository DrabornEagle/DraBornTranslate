// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
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
    private val downloadLock = Any()
    private val bundledPrefix = "gta-6-reference-"
    private val publishedVersion = Regex("[0-9]{4}[.][0-9]{2}[.][0-9]{2}[.][0-9]+")
    private const val DIALOGUE_MANIFEST = "manifest"
    private const val NEXT_CHECK = "nextCheck"
    private const val CHECK_INTERVAL = 60L * 60 * 1000
    private const val RETRY_INTERVAL = 60L * 1000

    private data class PublishedUpdate(val catalog: GtaMissionCatalog, val changed: Boolean)

    fun installed(context: Context): Boolean = hasDownload(context) || hasLocal(context)
    fun hasDownload(context: Context): Boolean = destination(context, local = false).isFile
    fun hasLocal(context: Context): Boolean = destination(context, local = true).isFile
    fun hasLocalDialogue(context: Context): Boolean = localDialogueFile(context).isFile
    fun hasLinkedDialogue(context: Context): Boolean = dialogueSource(context) != null

    fun catalog(context: Context): GtaMissionCatalog? = synchronized(lock) {
        activeFile(context)?.let(GtaMissionPack::catalog)
    }

    fun texts(context: Context, catalog: GtaMissionCatalog, mission: GtaMission): List<GtaMissionText> = synchronized(lock) {
        val file = activeFile(context) ?: throw IOException("Görev paketi açılamadı.")
        GtaMissionPack.texts(file, catalog.version, mission)
    }

    fun download(context: Context): GtaMissionCatalog = synchronized(downloadLock) {
        val updated = downloadPublishedPack(context, acceptBundled = false, open = ::connection)
        publishedPreferences(context).edit().putLong(NEXT_CHECK, System.currentTimeMillis() + CHECK_INTERVAL).apply()
        updated.catalog
    }

    fun dialogue(context: Context, missionId: String): GtaMissionDialogue? = synchronized(lock) {
        val linked = dialogueSource(context)?.let { dialogueFile(context, it) }
        listOfNotNull(publishedFile(context), destination(context, local = true), localDialogueFile(context), linked)
            .firstNotNullOfOrNull { file -> if (file.isFile) GtaMissionPack.dialogue(file, missionId) else null }
    }

    fun removeLocalDialogue(context: Context) = synchronized(lock) {
        Files.deleteIfExists(localDialogueFile(context).toPath())
        Unit
    }

    fun refreshPublished(
        context: Context,
        open: (URI) -> HttpURLConnection = ::connection,
    ): Boolean = synchronized(downloadLock) {
        val preferences = publishedPreferences(context)
        val now = System.currentTimeMillis()
        if (preferences.getLong(NEXT_CHECK, 0L) - now in 1L..CHECK_INTERVAL) return@synchronized false
        preferences.edit().putLong(NEXT_CHECK, now + RETRY_INTERVAL).apply()
        val updated = downloadPublishedPack(context, acceptBundled = true, open = open)
        preferences.edit().putLong(NEXT_CHECK, System.currentTimeMillis() + CHECK_INTERVAL).apply()
        updated.changed
    }

    fun removeDialogue(context: Context) = synchronized(lock) {
        val source = dialogueSource(context) ?: return@synchronized
        if (!dialoguePreferences(context).edit().clear().commit()) throw IOException("Replik kaynağı kaldırılamadı.")
        Files.deleteIfExists(dialogueFile(context, source).toPath())
        Unit
    }

    private fun downloadPublishedPack(
        context: Context,
        acceptBundled: Boolean,
        open: (URI) -> HttpURLConnection,
    ): PublishedUpdate {
        val source = repository.resolve("manifest.json")
        val destination = destination(context, local = false)
        val manifest = try {
            JSONObject(requestPackBytes(8 * 1024) { open(source) }.toString(Charsets.UTF_8))
        } catch (error: JSONException) {
            throw IOException("GitHub paket bilgisi okunamadı.", error)
        }
        val checksum = manifest.optString("sha256")
        val size = manifest.optLong("bytes")
        val version = manifest.optString("version")
        if (!digestPattern.matches(checksum) || size !in 1L..GtaMissionPack.MAX_ARCHIVE_BYTES.toLong() || version.isBlank()) {
            throw IOException("GitHub paket bilgisi geçersiz.")
        }
        val current = if (acceptBundled) synchronized(lock) { publishedFile(context) } else destination
        if (current != null && current.isFile) {
            val onDevice = runCatching { GtaMissionPack.catalog(current) }.getOrNull()
            if (onDevice != null && (isNewer(onDevice.version, version) ||
                    onDevice.version == version && current.length() == size && sha256(current) == checksum)) {
                return PublishedUpdate(onDevice, changed = false)
            }
        }
        val catalog = stage(destination) { temp ->
            requestPackFile(size.toInt(), temp) { open(source.resolve("gta-6-tr-${checksum.take(12)}.zip")) }
            if (sha256(temp) != checksum) throw IOException("GitHub paketinin bütünlüğü doğrulanamadı.")
            GtaMissionPack.validate(temp).also {
                if (it.version != version) throw IOException("GitHub paketinin sürüm bilgisi uyuşmuyor.")
            }
        }
        return PublishedUpdate(catalog, changed = true)
    }

    fun remove(context: Context, local: Boolean) {
        synchronized(if (local) lock else downloadLock) {
            synchronized(lock) { Files.deleteIfExists(destination(context, local).toPath()) }
        }
    }

    private fun <T> stage(destination: File, prepare: (File) -> T): T {
        val pending = File.createTempFile("gta-missions-", ".part", destination.parentFile)
        try {
            val result = prepare(pending)
            synchronized(lock) { Files.move(pending.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            return result
        } finally {
            pending.delete()
        }
    }

    private fun connection(url: URI): HttpsURLConnection =
        (url.toURL().openConnection() as HttpsURLConnection).apply {
            setRequestProperty("User-Agent", "DraBornTranslate Android")
        }

    private fun activeFile(context: Context): File? =
        destination(context, local = true).takeIf(File::isFile) ?: publishedFile(context)

    private fun publishedFile(context: Context): File? {
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

    private fun dialoguePreferences(context: Context) =
        context.getSharedPreferences("gta_mission_dialogue", Context.MODE_PRIVATE)

    private fun dialogueSource(context: Context): String? =
        dialoguePreferences(context).getString(DIALOGUE_MANIFEST, null)

    private fun publishedPreferences(context: Context) =
        context.getSharedPreferences("gta_mission_catalog", Context.MODE_PRIVATE)

    private fun localDialogueFile(context: Context) = File(context.filesDir, "gta-6-dialogue-local.zip")

    private fun dialogueFile(context: Context, source: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "gta-6-dialogue-$key.zip")
    }

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
