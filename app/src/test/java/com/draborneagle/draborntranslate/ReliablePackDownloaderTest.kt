// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Tiny single-threaded HTTP server using only java.net. com.sun.net.httpserver
 * is not available on Android's test compilation boot classpath.
 */
class ReliablePackDownloaderTest {
    private data class Response(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val declaredLength: Int = body.size,
        val contentRange: String? = null,
    )

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun serve(responder: (String?, Int) -> Response, test: (URL) -> Unit) {
        val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = 1000
        val count = AtomicInteger()
        val worker = thread(name = "draborn-http-test", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { peer ->
                        peer.soTimeout = 3000
                        val input = peer.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = input.readLine() ?: return@use
                        if (!request.startsWith("GET /pack ")) return@use
                        var range: String? = null
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                range = line.substringAfter(':').trim()
                            }
                        }
                        val response = responder(range, count.incrementAndGet())
                        val reason = when (response.status) {
                            200 -> "OK"
                            206 -> "Partial Content"
                            503 -> "Service Unavailable"
                            else -> "Error"
                        }
                        val headers = buildString {
                            append("HTTP/1.1 ${response.status} $reason\r\n")
                            append("Content-Length: ${response.declaredLength}\r\n")
                            append("Connection: close\r\n")
                            response.contentRange?.let { append("Content-Range: $it\r\n") }
                            append("\r\n")
                        }
                        val output = peer.getOutputStream()
                        output.write(headers.toByteArray(Charsets.US_ASCII))
                        output.write(response.body)
                        output.flush()
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Loop so the owner can close the socket after each test.
                } catch (_: SocketException) {
                    if (socket.isClosed) break
                } catch (_: java.io.IOException) {
                    if (socket.isClosed) break
                }
            }
        }
        try {
            test(URL("http://127.0.0.1:${socket.localPort}/pack"))
        } finally {
            socket.close()
            worker.join(1500)
        }
    }

    @Test fun manifestRetriesServerFailure() {
        val expected = """{"packs":[]}""".toByteArray()
        val requests = AtomicInteger()
        serve({ _, _ ->
            if (requests.incrementAndGet() == 1) Response(503)
            else Response(200, expected)
        }) { url ->
            assertArrayEquals(expected, ReliablePackDownloader.manifest(url, 1024))
            assertEquals(2, requests.get())
        }
    }

    @Test fun rangeRequestsResumeAfterInterruptedFirstChunk() {
        val data = ByteArray(2 * 1024 * 1024 + 123) { (it * 19 + 17).toByte() }
        val seen = mutableListOf<String>()
        val count = AtomicInteger()
        serve({ range, _ ->
            val value = checkNotNull(range)
            seen.add(value)
            val start = value.removePrefix("bytes=").substringBefore("-").toLong()
            val end = value.substringAfter("-").toLong().coerceAtMost(data.size - 1L)
            val size = (end - start + 1).toInt()
            val written = if (count.incrementAndGet() == 1) size / 4 else size
            Response(
                status = 206,
                body = data.copyOfRange(start.toInt(), start.toInt() + written),
                declaredLength = size,
                contentRange = "bytes $start-$end/${data.size}",
            )
        }) { url ->
            val dir = Files.createTempDirectory("draborn-resume").toFile()
            try {
                val progress = mutableListOf<Int>()
                val archive = ReliablePackDownloader.archive(
                    url, File(dir, "test.part"), data.size.toLong(), sha(data), progress::add
                )
                assertArrayEquals(data, archive)
                assertTrue(count.get() >= 4)
                assertTrue(seen.any { !it.startsWith("bytes=0-") })
                assertEquals(100, progress.last())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun serverIgnoringRangeRestartsRatherThanAppendingBadBytes() {
        val data = ByteArray(120_000) { (it * 7).toByte() }
        val count = AtomicInteger()
        serve({ _, _ ->
            val written = if (count.incrementAndGet() == 1) 600 else data.size
            Response(200, data.copyOf(written), declaredLength = data.size)
        }) { url ->
            val dir = Files.createTempDirectory("draborn-restart").toFile()
            try {
                val archive = ReliablePackDownloader.archive(
                    url, File(dir, "retry.part"), data.size.toLong(), sha(data)
                )
                assertArrayEquals(data, archive)
                assertEquals(2, count.get())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun mismatchedHashDeletesTemporaryArchive() {
        val data = ByteArray(10_000) { 13 }
        serve({ range, _ ->
            val value = checkNotNull(range)
            val start = value.removePrefix("bytes=").substringBefore("-").toInt()
            val end = value.substringAfter("-").toInt().coerceAtMost(data.size - 1)
            Response(
                206, data.copyOfRange(start, end + 1), contentRange = "bytes $start-$end/${data.size}"
            )
        }) { url ->
            val dir = Files.createTempDirectory("draborn-invalid").toFile()
            try {
                val part = File(dir, "invalid.part")
                val error = runCatching {
                    ReliablePackDownloader.archive(url, part, data.size.toLong(), "f".repeat(64))
                }.exceptionOrNull()
                assertTrue(error?.message.orEmpty().contains("bütünlük"))
                assertFalse(part.exists())
            } finally { dir.deleteRecursively() }
        }
    }
}
