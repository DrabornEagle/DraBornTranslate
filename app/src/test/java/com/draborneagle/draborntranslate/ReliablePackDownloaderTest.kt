// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class ReliablePackDownloaderTest {
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun serve(handler: com.sun.net.httpserver.HttpHandler, block: (URL) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/pack", handler)
        server.start()
        try {
            block(URL("http://127.0.0.1:${server.address.port}/pack"))
        } finally {
            server.stop(0)
        }
    }

    @Test fun manifestRetriesServerFailure() {
        val count = AtomicInteger(0)
        val expected = """{"packs":[]}""".toByteArray()
        serve({ request ->
            if (count.incrementAndGet() == 1) {
                request.sendResponseHeaders(503, -1)
            } else {
                request.sendResponseHeaders(200, expected.size.toLong())
                request.responseBody.use { it.write(expected) }
            }
            request.close()
        }) { url ->
            assertArrayEquals(expected, ReliablePackDownloader.manifest(url, 1024))
            assertEquals(2, count.get())
        }
    }

    @Test fun rangeRequestsResumeAfterInterruptedFirstChunk() {
        val data = ByteArray(2 * 1024 * 1024 + 123) { (it * 19 + 17).toByte() }
        val requests = AtomicInteger(0)
        val ranges = mutableListOf<String>()
        serve({ exchange ->
            val value = exchange.requestHeaders.getFirst("Range")
            synchronized(ranges) { ranges.add(value) }
            val start = value.removePrefix("bytes=").substringBefore("-").toLong()
            val end = value.substringAfter("-").toLong().coerceAtMost(data.size - 1L)
            val size = end - start + 1
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
            exchange.sendResponseHeaders(206, size)
            exchange.responseBody.use { output ->
                val written = if (requests.incrementAndGet() == 1) (size / 4).toInt()
                else size.toInt()
                output.write(data, start.toInt(), written)
            }
            exchange.close()
        }) { url ->
            val dir = Files.createTempDirectory("draborn-resume").toFile()
            try {
                val progress = mutableListOf<Int>()
                val archive = ReliablePackDownloader.archive(
                    url, File(dir, "test.part"), data.size.toLong(), sha(data), progress::add
                )
                assertArrayEquals(data, archive)
                assertTrue(requests.get() >= 4)
                assertTrue(ranges.any { !it.startsWith("bytes=0-") })
                assertEquals(100, progress.last())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun serverIgnoringRangeRestartsRatherThanAppendingBadBytes() {
        val data = ByteArray(120_000) { (it * 7).toByte() }
        val calls = AtomicInteger(0)
        serve({ exchange ->
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { output ->
                val length = if (calls.incrementAndGet() == 1) 600 else data.size
                output.write(data, 0, length)
            }
            exchange.close()
        }) { url ->
            val dir = Files.createTempDirectory("draborn-restart").toFile()
            try {
                val archive = ReliablePackDownloader.archive(
                    url, File(dir, "retry.part"), data.size.toLong(), sha(data)
                )
                assertArrayEquals(data, archive)
                assertEquals(2, calls.get())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun mismatchedHashDeletesTemporaryArchive() {
        val data = ByteArray(10_000) { 13 }
        serve({ exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val start = range.removePrefix("bytes=").substringBefore("-").toInt()
            val end = range.substringAfter("-").toInt().coerceAtMost(data.size - 1)
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
            exchange.sendResponseHeaders(206, (end - start + 1).toLong())
            exchange.responseBody.use { it.write(data, start, end - start + 1) }
            exchange.close()
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
