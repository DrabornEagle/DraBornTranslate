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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalPanelServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address

    @Test
    fun browserReceivesNewTurkishCaptionAndClearsIt() {
        val opened = CountDownLatch(1)
        PortalPanelServer(loopback, "<p>{{caption-size}}</p>", 24) {
            opened.countDown()
        }.use { server ->
            val page = get(server.url)
            assertEquals(200, page.code)
            assertEquals("<p>24</p>", page.body)
            assertEquals("ready", page.panelMode)
            assertEquals(1L, opened.count)

            val captionUrl = server.url + "caption.txt"
            assertEquals("", get(captionUrl).body)
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            val sample = "Köprüye git!\nKapıyı aç."
            server.publish(sample)
            val caption = get(captionUrl)
            assertEquals(sample, caption.body)
            assertEquals("no-store", caption.cacheControl)
            assertEquals("text/plain; charset=utf-8", caption.contentType)
            assertNull(caption.translation)
            assertEquals(404, get(server.url + "translate.png").code)
            server.publish(null)
            assertEquals("", get(captionUrl).body)
        }
    }

    @Test
    fun missingOrDifferentTokenCannotReadCaptions() {
        PortalPanelServer(loopback, "panel", 22) {}.use { server ->
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
        val firstUrl = PortalPanelServer(loopback, "panel", 22) {}.use { server ->
            server.publish("Son satır")
            server.url
        }
        PortalPanelServer(loopback, "panel", 22) {}.use { server ->
            assertNotEquals(URL(firstUrl).path, URL(server.url).path)
            assertEquals("", get(server.url + "caption.txt").body)
        }
    }

    @Test
    fun testPanelSwitchesToLiveCaptionsAtTheSameUrl() {
        PortalPanelServer(loopback, "panel", 22) {}.use { server ->
            val captionUrl = server.url + "caption.txt"
            server.publish("Bağlantı testi.", testing = true)
            val test = get(captionUrl)
            assertEquals("test", test.panelMode)
            assertEquals("Bağlantı testi.", test.body)
            assertNull(test.translation)

            server.publish(null)
            assertEquals("", get(captionUrl).body)
            server.publish("Oyuna dön.")
            val live = get(captionUrl)
            assertNull(live.panelMode)
            assertNull(live.translation)
            assertEquals("Oyuna dön.", live.body)
        }
    }

    @Test
    fun panelLinkStillOpensIfTheChatDropsTheTrailingSlash() {
        PortalPanelServer(loopback, "panel", 22) {}.use { server ->
            val response = get(server.url.dropLast(1), followRedirects = false)
            assertEquals(302, response.code)
            assertEquals(URL(server.url).path, response.location)
        }
    }

    private fun get(url: String, followRedirects: Boolean = true): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = followRedirects
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        try {
            val code = connection.responseCode
            val input = if (code < 400) connection.inputStream else connection.errorStream
            val body = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Response(
                code,
                body,
                connection.contentType,
                connection.getHeaderField("Cache-Control"),
                connection.getHeaderField("X-DraBorn-Panel"),
                connection.getHeaderField("Location"),
                connection.getHeaderField("X-DraBorn-Translation"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private data class Response(
        val code: Int,
        val body: String,
        val contentType: String?,
        val cacheControl: String?,
        val panelMode: String?,
        val location: String?,
        val translation: String?,
    )
}
