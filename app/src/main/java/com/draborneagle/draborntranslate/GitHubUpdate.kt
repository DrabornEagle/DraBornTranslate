// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class GitHubRelease(
    val version: String,
    val url: URI,
    val size: Long,
    val sha256: String,
)

internal class UpdateException(message: String) : IOException(message)

internal object GitHubUpdate {
    private const val REPOSITORY = "DrabornEagle/DraBornTranslate"
    private val latestUrl = URI("https://api.github.com/repos/$REPOSITORY/releases/latest")
    private val downloadHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private val stableVersion = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")
    private val digestFormat = Regex("sha256:([a-f0-9]{64})")

    fun latest(installedVersion: String): GitHubRelease? {
        val connection = connect(latestUrl)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_FORBIDDEN, 429 -> throw UpdateException("GitHub şu anda sürüm sorgusuna yanıt veremiyor. Biraz sonra tekrar dene.")
                else -> throw UpdateException("GitHub sürüm bilgisi alınamadı.")
            }
            val metadata = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            return parse(metadata, installedVersion)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(metadata: JSONObject, installedVersion: String): GitHubRelease? {
        if (metadata.optBoolean("draft") || metadata.optBoolean("prerelease")) return null
        val tag = metadata.optString("tag_name")
        val version = tag.removePrefix("v")
        val remote = versionParts(version)
        val local = versionParts(installedVersion)
        val compared = remote.indices.firstOrNull { remote[it] != local[it] } ?: return null
        if (remote[compared] < local[compared]) return null

        val file = "DraBornTranslate-v$version-Android-arm64.apk"
        val officialUrl = URI("https://github.com/$REPOSITORY/releases/download/$tag/$file")
        val assets = metadata.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).asSequence().mapNotNull { assets?.optJSONObject(it) }
            .firstOrNull { it.optString("name") == file && it.optString("state") == "uploaded" }
            ?: throw UpdateException("Yeni sürüm bulundu. APK henüz yayımlanmamış.")
        val digest = digestFormat.matchEntire(apk.optString("digest"))?.groupValues?.get(1)
        val size = apk.optLong("size")
        if (apk.optString("browser_download_url") != officialUrl.toString() || digest == null || size <= 0) {
            throw UpdateException("GitHub’daki APK bilgileri doğrulanamadı.")
        }
        return GitHubRelease(version, officialUrl, size, digest)
    }

    fun download(release: GitHubRelease, destination: File, onProgress: (Int) -> Unit) {
        val sha = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var percent = 0
        val connection = openDownload(release.url)
        try {
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        bytes += count
                        if (bytes > release.size) throw UpdateException("İndirilen APK’nın boyutu uyuşmuyor.")
                        sha.update(chunk, 0, count)
                        output.write(chunk, 0, count)
                        val progress = (100.0 * bytes / release.size).toInt()
                        if (progress >= percent + 2 || progress == 100) {
                            percent = progress
                            onProgress(progress)
                        }
                    }
                }
            }
            if (bytes != release.size || sha.digest().joinToString("") { "%02x".format(it) } != release.sha256) {
                throw UpdateException("APK’nın bütünlüğü doğrulanamadı. İndirmeyi tekrar dene.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun versionParts(version: String): List<Int> {
        val parts = stableVersion.matchEntire(version)?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull)
        return parts?.takeIf { it.size == 3 } ?: throw UpdateException("GitHub sürüm numarası doğrulanamadı.")
    }

    private fun connect(url: URI): HttpsURLConnection = (url.toURL().openConnection() as HttpsURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 12_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "DraBornTranslate Android")
    }

    private fun openDownload(start: URI): HttpsURLConnection {
        var url = start
        repeat(5) {
            if (url.scheme != "https" || url.host !in downloadHosts || url.userInfo != null || url.port !in listOf(-1, 443)) {
                throw UpdateException("GitHub APK bağlantısı doğrulanamadı.")
            }
            val connection = connect(url)
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return connection
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw UpdateException("GitHub APK bağlantısı eksik.")
                        url = url.resolve(location)
                    }
                    else -> throw UpdateException("APK indirilemedi. Biraz sonra tekrar dene.")
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw UpdateException("GitHub APK bağlantısı açılamadı.")
    }
}
