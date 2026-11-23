// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.min

/**
 * Retries resets/timeouts on both manifest and archive, uses short HTTP ranges
 * when supported, resumes safely, and verifies size + SHA-256 before activation.
 * Downloads remain private to the phone; no third-party proxy is involved.
 */
internal object ReliablePackDownloader {
    private const val CHUNK = 1024 * 1024
    private const val BUFFER = 64 * 1024
    private const val MAX_FAILURES = 7
    private const val MAX_REQUESTS = 100
    private val SHA = Regex("[a-f0-9]{64}")
    private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")

    fun manifest(url: URL, limit: Int): ByteArray {
        require(limit > 0)
        var failure: IOException? = null
        repeat(MAX_FAILURES) { index ->
            val connection = open(url)
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw httpFailure(code)
                if (connection.contentLengthLong > limit) throw NonRetryable("Paket listesi boyut sınırını aşıyor.")
                val output = ByteArrayOutputStream(32 * 1024)
                connection.inputStream.use { input ->
                    val block = ByteArray(BUFFER)
                    while (true) {
                        val count = input.read(block)
                        if (count < 0) break
                        if (output.size() + count > limit) throw NonRetryable("Paket listesi çok büyük.")
                        output.write(block, 0, count)
                    }
                }
                val data = output.toByteArray()
                if (data.isEmpty()) throw EOFException("Paket listesi boş geldi.")
                return data
            } catch (error: IOException) {
                if (error is NonRetryable) throw error
                failure = error
                if (index < MAX_FAILURES - 1) backoff(index)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Paket listesi alınamadı. Bağlantı tekrar tekrar kesildi; Wi-Fi ile dene.", failure)
    }

    fun archive(
        url: URL,
        partialFile: File,
        expectedBytes: Long,
        expectedHash: String,
        onProgress: (Int) -> Unit = {},
    ): ByteArray {
        require(expectedBytes > 0 && expectedBytes <= 96L * 1024 * 1024)
        require(SHA.matches(expectedHash))
        val marker = File(partialFile.path + ".state")
        val fingerprint = "$expectedBytes:$expectedHash"
        if (marker.takeIf { it.isFile }?.readText() != fingerprint ||
            partialFile.length() > expectedBytes
        ) {
            partialFile.delete()
            marker.delete()
        }
        if (!marker.isFile) marker.writeText(fingerprint)
        var consecutiveErrors = 0
        var requests = 0
        var lastError: IOException? = null
        var lastReported = -1

        fun notifyProgress() {
            val progress = ((100L * partialFile.length()) / expectedBytes).toInt().coerceIn(0, 100)
            if (progress >= lastReported + 3 || progress == 100 || lastReported == -1) {
                lastReported = progress
                onProgress(progress)
            }
        }

        notifyProgress()
        while (partialFile.length() < expectedBytes && requests < MAX_REQUESTS) {
            requests++
            val start = partialFile.length()
            val end = min(expectedBytes - 1, start + CHUNK - 1)
            val connection = open(url, "bytes=$start-$end")
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val match = CONTENT_RANGE.matchEntire(
                            connection.getHeaderField("Content-Range").orEmpty().trim()
                        ) ?: throw NonRetryable("Sunucu parça indirme yanıtı geçersiz.")
                        val actualStart = match.groupValues[1].toLong()
                        val actualEnd = match.groupValues[2].toLong()
                        val total = match.groupValues[3].toLong()
                        if (actualStart != start || actualEnd > end || actualEnd < actualStart ||
                            total != expectedBytes
                        ) throw NonRetryable("Paket parçası yanlış aralıkta geldi.")
                        val length = actualEnd - actualStart + 1
                        if (connection.contentLengthLong > 0 &&
                            connection.contentLengthLong != length
                        ) throw NonRetryable("Paket parçasının boyutu uyuşmuyor.")
                        copyExactly(connection, partialFile, append = true, limit = length, onWrite = ::notifyProgress)
                    }
                    HttpURLConnection.HTTP_OK -> {
                        // If the CDN ignores Range, start anew rather than corrupt partial bytes.
                        if (connection.contentLengthLong > expectedBytes) {
                            throw NonRetryable("Paket izin verilen boyuttan büyük.")
                        }
                        copyExactly(connection, partialFile, append = false, limit = expectedBytes, onWrite = ::notifyProgress)
                    }
                    else -> throw httpFailure(code)
                }
                consecutiveErrors = 0
                lastError = null
                notifyProgress()
            } catch (error: IOException) {
                if (error is NonRetryable) throw error
                lastError = error
                consecutiveErrors++
                notifyProgress()
                if (consecutiveErrors >= MAX_FAILURES) break
                backoff(consecutiveErrors - 1)
            } finally {
                connection.disconnect()
            }
        }
        if (partialFile.length() != expectedBytes) {
            throw IOException(
                "GTA 6 paketi tamamlanamadı (${partialFile.length() / 1024} / ${expectedBytes / 1024} KB). " +
                    "İndirilen bölüm korundu; yeniden dene veya Wi-Fi kullan.",
                lastError,
            )
        }
        val actualHash = sha256(partialFile)
        if (actualHash != expectedHash) {
            partialFile.delete()
            marker.delete()
            throw IOException("GTA 6 paketi bütünlük kontrolünden geçmedi. Bozuk indirme silindi.")
        }
        onProgress(100)
        return partialFile.readBytes()
    }

    private fun copyExactly(
        connection: HttpURLConnection,
        destination: File,
        append: Boolean,
        limit: Long,
        onWrite: () -> Unit,
    ) {
        var received = 0L
        connection.inputStream.use { input ->
            FileOutputStream(destination, append).use { output ->
                val block = ByteArray(BUFFER)
                while (received < limit) {
                    val available = min(block.size.toLong(), limit - received).toInt()
                    val count = input.read(block, 0, available)
                    if (count < 0) throw EOFException("Paket akışı yarım kaldı.")
                    output.write(block, 0, count)
                    received += count
                    onWrite()
                }
            }
        }
    }

    private fun open(url: URL, range: String? = null): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "DraBornTranslate/0.6 (Android; Turkish GamePack)")
        connection.setRequestProperty("Accept", "application/octet-stream, application/json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "close")
        if (range != null) connection.setRequestProperty("Range", range)
        return connection
    }

    private fun httpFailure(code: Int): IOException = if (code == 404 || code == 403 ||
        code == 401 || code in 300..399
    ) NonRetryable("Paket sunucusu HTTP $code döndürdü.")
    else IOException("Paket sunucusu geçici HTTP $code hatası verdi.")

    private fun backoff(attempt: Int) {
        try {
            Thread.sleep((500L shl attempt.coerceAtMost(4)).coerceAtMost(5_000L))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("İndirme iptal edildi.", error)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val block = ByteArray(BUFFER)
            while (true) {
                val count = stream.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private class NonRetryable(message: String) : IOException(message)
}
