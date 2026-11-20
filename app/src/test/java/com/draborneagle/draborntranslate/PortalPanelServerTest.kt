// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalPanelServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address

    @Test
    fun browserReceivesNewTurkishCaptionAndClearsIt() {
        val opened = CountDownLatch(1)
        PortalPanelServer(loopback, "<p>{{caption-size}}</p>", byteArrayOf(0, 1), 24) {
            opened.countDown()
        }.use { server ->
            val page = get(server.url)
            assertEquals(200, page.code)
            assertEquals("<p>24</p>", page.body)
            assertTrue(opened.await(2, TimeUnit.SECONDS))

            val captionUrl = server.url + "caption.txt"
            assertEquals("", get(captionUrl).body)
            val sample = "Köprüye git!\nKapıyı aç."
            server.publish(sample)
            val caption = get(captionUrl)
            assertEquals(sample, caption.body)
            assertEquals("no-store", caption.cacheControl)
            assertEquals("text/plain; charset=utf-8", caption.contentType)
            server.publish(null)
            assertEquals("", get(captionUrl).body)
        }
    }

    @Test
    fun missingOrDifferentTokenCannotReadCaptions() {
        PortalPanelServer(loopback, "panel", byteArrayOf(), 22) {}.use { server ->
            val url = URL(server.url)
            val origin = "http://${url.host}:${url.port}"
            server.publish("Gizli altyazı")
            assertEquals(404, get("$origin/").code)
            val wrongToken = if (url.path[1] == 'A') "B" else "A"
            val wrongPath = "/" + wrongToken + url.path.substring(2)
            assertEquals(404, get(origin + wrongPath + "caption.txt").code)
        }
    }

    @Test
    fun eachPanelStartsWithItsOwnLinkAndEmptyCaption() {
        val firstUrl = PortalPanelServer(loopback, "panel", byteArrayOf(), 22) {}.use { server ->
            server.publish("Son satır")
            server.url
        }
        PortalPanelServer(loopback, "panel", byteArrayOf(), 22) {}.use { server ->
            assertNotEquals(URL(firstUrl).path, URL(server.url).path)
            assertEquals("", get(server.url + "caption.txt").body)
        }
    }

    private fun get(url: String): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        try {
            val code = connection.responseCode
            val input = if (code < 400) connection.inputStream else connection.errorStream
            val body = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Response(code, body, connection.contentType, connection.getHeaderField("Cache-Control"))
        } finally {
            connection.disconnect()
        }
    }

    private data class Response(val code: Int, val body: String, val contentType: String?, val cacheControl: String?)
}
