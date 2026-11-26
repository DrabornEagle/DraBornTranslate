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
    private val dialogueLock = Any()
    private val bundledPrefix = "gta-6-reference-"
    private val publishedVersion = Regex("[0-9]{4}[.][0-9]{2}[.][0-9]{2}[.][0-9]+")
    private const val DIALOGUE_MANIFEST = "manifest"
    private const val DIALOGUE_LAST_CHECK = "lastCheck"
    private const val DIALOGUE_CHECK_INTERVAL = 60L * 60 * 1000

    fun installed(context: Context): Boolean = hasDownload(context) || hasLocal(context)
    fun hasDownload(context: Context): Boolean = destination(context, local = false).isFile
    fun hasLocal(context: Context): Boolean = destination(context, local = true).isFile
    fun hasLocalDialogue(context: Context): Boolean = localDialogueFile(context).isFile

    fun catalog(context: Context): GtaMissionCatalog? = synchronized(lock) {
        activeFile(context)?.let(GtaMissionPack::catalog)
    }

    fun texts(context: Context, catalog: GtaMissionCatalog, mission: GtaMission): List<GtaMissionText> = synchronized(lock) {
        val file = activeFile(context) ?: throw IOException("Görev paketi açılamadı.")
        GtaMissionPack.texts(file, catalog.version, mission)
    }

    fun download(context: Context): GtaMissionCatalog = synchronized(lock) {
        downloadPublishedPack(repository.resolve("manifest.json"), destination(context, local = false),
            GtaMissionCatalog::version) { file, existing ->
            if (existing) GtaMissionPack.catalog(file) else GtaMissionPack.validate(file)
        }
    }

    fun dialogueSource(context: Context): String? = dialoguePreferences(context).getString(DIALOGUE_MANIFEST, null)

    fun dialogue(context: Context, missionId: String): GtaMissionDialogue? {
        val local = localDialogueFile(context)
        if (local.isFile) GtaMissionPack.dialogue(local, missionId)?.let { return it }
        val source = dialogueSource(context) ?: return null
        val file = dialogueFile(context, source)
        return if (file.isFile) GtaMissionPack.dialogue(file, missionId) else null
    }

    fun installLocalDialogue(context: Context, uri: Uri): GtaDialoguePackCoverage = synchronized(dialogueLock) {
        val missionIds = dialogueMissionIds(context)
        stageImport(context, uri, localDialogueFile(context)) { file ->
            GtaMissionPack.validateDialogue(file, missionIds)
        }
    }

    fun removeLocalDialogue(context: Context) = synchronized(dialogueLock) {
        Files.deleteIfExists(localDialogueFile(context).toPath())
        Unit
    }

    fun connectDialogue(context: Context, address: String): GtaDialoguePackCoverage = synchronized(dialogueLock) {
        val source = githubDialogueManifest(address)
            ?: throw IOException("GitHub’daki manifest.json dosyasının HTTPS bağlantısını gir.")
        val activeSource = dialogueSource(context)
        val result = downloadDialogue(context, source)
        val preferences = dialoguePreferences(context)
        if (!preferences.edit().putString(DIALOGUE_MANIFEST, source.toString())
                .putLong(DIALOGUE_LAST_CHECK, System.currentTimeMillis()).commit()) {
            throw IOException("Replik kaynağı kaydedilemedi.")
        }
        if (activeSource != null && activeSource != source.toString()) {
            dialogueFile(context, activeSource).delete()
        }
        result
    }

    fun refreshDialogue(context: Context): Boolean = synchronized(dialogueLock) {
        val source = dialogueSource(context)?.let(::githubDialogueManifest) ?: return@synchronized false
        val preferences = dialoguePreferences(context)
        val now = System.currentTimeMillis()
        if (now - preferences.getLong(DIALOGUE_LAST_CHECK, 0L) in 0L until DIALOGUE_CHECK_INTERVAL) {
            return@synchronized false
        }
        preferences.edit().putLong(DIALOGUE_LAST_CHECK, now).apply()
        downloadDialogue(context, source)
        true
    }

    fun removeDialogue(context: Context) = synchronized(dialogueLock) {
        val source = dialogueSource(context) ?: return@synchronized
        if (!dialoguePreferences(context).edit().clear().commit()) throw IOException("Replik kaynağı kaldırılamadı.")
        Files.deleteIfExists(dialogueFile(context, source).toPath())
        Unit
    }

    private fun downloadDialogue(context: Context, source: URI): GtaDialoguePackCoverage {
        val missionIds = dialogueMissionIds(context)
        return downloadPublishedPack(source, dialogueFile(context, source.toString()),
            GtaDialoguePackCoverage::version) { file, _ ->
            GtaMissionPack.validateDialogue(file, missionIds)
        }
    }

    private fun dialogueMissionIds(context: Context): Set<String> =
        catalog(context)?.missions?.filter { it.searchTarget == null }?.mapTo(mutableSetOf()) { it.id }
            ?: throw IOException("Görev kataloğu açılamadı.")

    private fun <T> downloadPublishedPack(
        source: URI,
        destination: File,
        versionOf: (T) -> String,
        inspect: (File, Boolean) -> T,
    ): T {
        val manifest = try {
            JSONObject(requestPackBytes(8 * 1024) { connection(source) }.toString(Charsets.UTF_8))
        } catch (error: JSONException) {
            throw IOException("GitHub paket bilgisi okunamadı.", error)
        }
        val checksum = manifest.optString("sha256")
        val size = manifest.optLong("bytes")
        val version = manifest.optString("version")
        if (!digestPattern.matches(checksum) || size !in 1L..GtaMissionPack.MAX_ARCHIVE_BYTES.toLong() || version.isBlank()) {
            throw IOException("GitHub paket bilgisi geçersiz.")
        }
        fun validate(file: File, existing: Boolean): T {
            val result = inspect(file, existing)
            if (versionOf(result) != version) throw IOException("GitHub paketinin sürüm bilgisi uyuşmuyor.")
            return result
        }
        if (destination.isFile && destination.length() == size && sha256(destination) == checksum) {
            return validate(destination, existing = true)
        }
        return stage(destination) { temp ->
            requestPackFile(size.toInt(), temp) { connection(source.resolve("gta-6-tr-${checksum.take(12)}.zip")) }
            if (sha256(temp) != checksum) throw IOException("GitHub paketinin bütünlüğü doğrulanamadı.")
            validate(temp, existing = false)
        }
    }

    fun installLocal(context: Context, uri: Uri): GtaMissionCatalog = synchronized(lock) {
        stageImport(context, uri, destination(context, local = true), GtaMissionPack::validate)
    }

    private fun <T> stageImport(context: Context, uri: Uri, destination: File, validate: (File) -> T): T =
        stage(destination) { pending ->
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Görev paketi açılamadı.")
            input.use { source ->
                pending.outputStream().use { copyLimited(source, it, GtaMissionPack.MAX_ARCHIVE_BYTES) }
            }
            validate(pending)
        }

    fun remove(context: Context, local: Boolean) {
        synchronized(lock) { Files.deleteIfExists(destination(context, local).toPath()) }
    }

    private fun <T> stage(destination: File, prepare: (File) -> T): T {
        val pending = File.createTempFile("gta-missions-", ".part", destination.parentFile)
        try {
            val result = prepare(pending)
            Files.move(pending.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            return result
        } finally {
            pending.delete()
        }
    }

    private fun connection(url: URI): HttpsURLConnection =
        (url.toURL().openConnection() as HttpsURLConnection).apply {
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

    private fun dialoguePreferences(context: Context) =
        context.getSharedPreferences("gta_mission_dialogue", Context.MODE_PRIVATE)

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

private val githubPathSegment = Regex("[a-zA-Z0-9._-]+")

internal fun githubDialogueManifest(address: String): URI? {
    val clean = address.trim()
    if (clean.length > 640) return null
    val uri = runCatching { URI(clean) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
        uri.rawQuery != null || uri.rawFragment != null) return null
    val path = uri.rawPath?.removePrefix("/")?.split('/') ?: return null
    if (path.size < 4 || path.last() != "manifest.json" ||
        path.any { it == "." || it == ".." || !it.matches(githubPathSegment) }) return null
    val rawPath = when (uri.host) {
        "raw.githubusercontent.com" -> path
        "github.com" -> {
            if (path.size < 5 || path[2] != "blob") return null
            path.take(2) + path.drop(3)
        }
        else -> return null
    }
    return URI("https://raw.githubusercontent.com/" + rawPath.joinToString("/"))
}
