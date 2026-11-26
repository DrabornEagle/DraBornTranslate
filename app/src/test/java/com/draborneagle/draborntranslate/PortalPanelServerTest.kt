// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.TextBox
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun portalReceivesTheSamePositionAndHudWithNoSourceText() {
        PortalPanelServer(loopback, "panel", 22) {}.use { server ->
            val box = TextBox(0.42, 0.81, 0.61, 0.85)
            val phone = HudBlock(HudKind.PHONE, box, listOf(HudLine("Yoldayım.", HudLineStyle.OUTGOING)), "Avery")
            server.publish("Polis: Geri çekil.", position = CaptionPosition(box, "#23aaff"), hud = listOf(phone), plainPhone = true)
            val response = get(server.url + "state.json")
            val state = JSONObject(response.body)
            assertEquals("application/json; charset=utf-8", response.contentType)
            assertEquals("Polis: Geri çekil.", state.getString("caption"))
            assertEquals(0.42, state.getJSONArray("position").getDouble(0), 0.001)
            assertEquals("#23aaff", state.getString("speakerColor"))
            assertTrue(state.getBoolean("plainPhone"))
            val block = state.getJSONArray("hud").getJSONObject(0)
            assertEquals("Avery", block.getString("title"))
            assertEquals("OUTGOING", block.getJSONArray("rows").getJSONObject(0).getString("style"))
            assertTrue(!state.has("source"))
            server.publish(null)
            assertEquals(0, JSONObject(get(server.url + "state.json").body).getJSONArray("hud").length())
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
    fun gameFramesRequireAnExplicitWindowAndItsOwnLink() {
        PortalPanelServer(loopback, "captions", 22) {}.use { captions ->
            val frame = AtomicReference<ByteArray?>()
            PortalPanelServer(loopback, "game", 22, gameJpeg = frame::get) {}.use { game ->
                val url = URL(game.url)
                assertEquals(204, get(game.url + "frame.jpg").code)
                frame.set("test frame".toByteArray())
                val image = get(game.url + "frame.jpg")
                assertEquals(200, image.code)
                assertEquals("image/jpeg", image.contentType)
                assertEquals("no-store", image.cacheControl)
                assertEquals("test frame", image.body)
                assertEquals(404, get("http://${url.host}:${url.port}${URL(captions.url).path}frame.jpg").code)
                assertEquals(404, get(captions.url + "frame.jpg").code)
                frame.set(null)
                assertEquals(204, get(game.url + "frame.jpg").code)
            }
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
    fun anOpenPanelRequestReceivesTheNewCaptionWithoutPollingAgain() {
        PortalPanelServer(loopback, "panel", 22) {}.use { server ->
            val endpoint = URL(server.url + "caption.txt")
            val initial = get(endpoint.toString())
            val revision = initial.revision!!.toLong()
            Socket(loopback, endpoint.port).use { client ->
                client.soTimeout = 100
                client.getOutputStream().write(
                    "GET ${endpoint.path}?after=$revision HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(),
                )
                assertThrows(SocketTimeoutException::class.java) { client.getInputStream().read() }
                server.publish("Hemen dön.")
                client.soTimeout = 450
                val response = client.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                assertTrue(response.contains("X-DraBorn-Revision: ${revision + 1}"))
                assertTrue(response.endsWith("Hemen dön."))
            }
            server.publish(null)
            val cleared = get("$endpoint?after=${revision + 1}")
            assertEquals("", cleared.body)
            assertEquals((revision + 2).toString(), cleared.revision)
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
                connection.getHeaderField("X-DraBorn-Revision"),
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
        val revision: String?,
    )
}
