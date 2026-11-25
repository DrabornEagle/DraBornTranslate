// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URI
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePackRequestTest {
    @Test
    fun reconnectsAfterAResetAndReadsTheCompleteBody() {
        val data = "catalog".toByteArray()
        val reset = Response { throw SocketException("Connection reset") }
        val success = Response { data.inputStream() }
        val responses = listOf(reset, success).iterator()
        val waits = mutableListOf<Long>()
        val downloaded = requestPackBytes(32, waitBeforeRetry = { waits.add(it) }) { responses.next() }
        assertArrayEquals(data, downloaded)
        assertTrue(reset.closed && success.closed)
        assertEquals("close", success.getRequestProperty("Connection"))
        assertEquals(listOf(350L), waits)
    }

    @Test
    fun retriesAnIncompletePackageAtItsDeclaredSize() {
        val data = byteArrayOf(1, 2, 3, 4)
        val responses = listOf(Response { byteArrayOf(1).inputStream() }, Response { data.inputStream() }).iterator()
        val downloaded = requestPackBytes(data.size, exactSize = true, waitBeforeRetry = {}) { responses.next() }
        assertArrayEquals(data, downloaded)
    }

    @Test
    fun stopsAfterThreeResetConnectionsWithATurkishError() {
        var connections = 0
        val failure = assertThrows(IOException::class.java) {
            requestPackBytes(32, waitBeforeRetry = {}) {
                connections++
                Response { throw SocketException("Connection reset") }
            }
        }
        assertEquals(3, connections)
        assertEquals("Oyun paketi indirilemedi. İnternet bağlantını kontrol edip tekrar dene.", failure.message)
        assertTrue(failure.cause is SocketException)
    }

    @Test
    fun leavesPermanentHttpErrorsToTheCallerWithoutRetrying() {
        val missing = Response(code = 404) { error("Response body must not be read") }
        val failure = assertThrows(IOException::class.java) {
            requestPackBytes(32, waitBeforeRetry = { error("HTTP 404 must not be retried") }) { missing }
        }
        assertEquals("Paket sunucusu HTTP 404 döndürdü.", failure.message)
        assertTrue(missing.closed)
    }

    private class Response(private val code: Int = 200, private val body: () -> InputStream) :
        HttpURLConnection(URI("https://localhost/v1/packs").toURL()) {
        var closed = false
            private set
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = body()
    }
}
