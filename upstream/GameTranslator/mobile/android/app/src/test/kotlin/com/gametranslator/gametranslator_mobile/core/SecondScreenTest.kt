// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** İkinci ekran: telefonun /ekran sayfası, izleyici bağlantıları, ekran olarak bağlanılacak sunucunun seçimi ve köprü. */
class SecondScreenTest {
    /** Bilgisayar uygulamasının sunduğu sayfa; derleme aynı dosyayı assets/second-screen.html olarak kopyalar. */
    private val page: ByteArray = File(System.getProperty("gametranslator.secondScreen")!!).readBytes()

    private fun get(url: String) = URL(url).openConnection() as HttpURLConnection

    @Test
    fun servesTheSharedPageAtBothPathsOnlyWhenProvided() {
        val server = SubtitleServer(port = 0)
        val port = server.start()
        try {
            assertEquals(404, get("http://127.0.0.1:$port/ekran").responseCode)
            assertEquals(404, get("http://127.0.0.1:$port/screen").responseCode)
            server.screenPage = page
            for (path in listOf("/ekran", "/screen", "/ekran?size=1.3")) {
                val response = get("http://127.0.0.1:$port$path")
                assertEquals(200, response.responseCode)
                assertTrue(response.contentType.startsWith("text/html"))
                assertArrayEquals(page, response.inputStream.readBytes())
            }
            // /obs ayrı bir sayfadır; ikinci ekran verilince o açılmaz.
            assertEquals(404, get("http://127.0.0.1:$port/obs").responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun sharedPageConnectsAsViewer() {
        val text = page.toString(Charsets.UTF_8)
        // Sayfa akışa izleyici olarak bağlanmazsa telefon onu TV sayar ("TV bağlı").
        assertTrue(text.contains("EventSource('/events?viewer=obs"))
        assertTrue(text.contains("GameTranslator"))
        assertFalse(text.contains("CouchSubs"))
    }

    @Test
    fun viewerReceivesSubtitlesButIsNotCountedAsTv() {
        val server = SubtitleServer(port = 0)
        val port = server.start()
        val reported = AtomicInteger(-1)
        val notifications = AtomicInteger(0)
        server.onClientsChanged = { reported.set(it); notifications.incrementAndGet() }
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET /events?viewer=obs&screen=1 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                fun readUntil(marker: String): String {
                    val text = StringBuilder()
                    while (!text.contains(marker)) text.append(reader.readLine() ?: break).append('\n')
                    return text.toString()
                }
                assertTrue(readUntil("retry:").contains("text/event-stream"))
                var waited = 0
                while (notifications.get() == 0 && waited++ < 300) Thread.sleep(10)
                assertEquals(0, server.connectedClients)
                assertEquals(0, reported.get())
                server.sendSubtitle("Merhaba", 15000, null, false)
                assertTrue(readUntil("Merhaba").contains("\"text\":\"Merhaba\""))
                server.clear()
                assertTrue(readUntil("event: clear").contains("event: clear"))
                assertEquals(0, server.connectedClients)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun helloTellsWhetherTheServerTranslates() {
        assertEquals(true, OtherServers.parseHello("HTTP/1.1 200 OK\r\n\r\n{\"active\":true,\"app\":\"gametranslator\"}"))
        assertEquals(false, OtherServers.parseHello("HTTP/1.1 200 OK\r\n\r\n{\"active\":false,\"app\":\"gametranslator\"}"))
        // Eski sunucular "active" göndermez: sunucudur ama çeviri yaptığı bilinmez.
        assertEquals(false, OtherServers.parseHello("HTTP/1.1 200 OK\r\n\r\n{\"app\":\"gametranslator\"}"))
        assertNull(OtherServers.parseHello("HTTP/1.1 200 OK\r\n\r\n{\"active\":true,\"app\":\"router\"}"))
        assertNull(OtherServers.parseHello("HTTP/1.1 404 Not Found\r\n\r\ngametranslator"))
        val server = SubtitleServer(port = 0)
        val port = server.start()
        try {
            assertEquals(false, OtherServers.hello("127.0.0.1", port))
            server.active = true
            assertEquals(true, OtherServers.hello("127.0.0.1", port))
            assertTrue(OtherServers.isServer("127.0.0.1", port))
        } finally {
            server.stop()
        }
        assertNull(OtherServers.hello("127.0.0.1", port, 300))
    }

    @Test
    fun displaySourcePrefersTheServerThatTranslates() {
        val idle = "192.168.1.20"
        val busy = "192.168.1.77"
        val asked = java.util.Collections.synchronizedSet(HashSet<String>())
        fun probe(servers: Map<String, Boolean>): (String) -> Boolean? = { asked.add(it); servers[it] }
        assertEquals(OtherServers.Found(busy, true), OtherServers.findDisplaySource("192.168.1.5", probe(mapOf(idle to false, busy to true))))
        assertTrue("192.168.1.5" !in asked && "192.168.1.1" in asked && "192.168.1.254" in asked && asked.all { it.startsWith("192.168.1.") })
        assertEquals(OtherServers.Found(idle, false), OtherServers.findDisplaySource("192.168.1.5", probe(mapOf(idle to false))))
        assertNull(OtherServers.findDisplaySource("192.168.1.5", probe(emptyMap())))
        assertNull(OtherServers.findDisplaySource("yok") { true })
    }

    @Test
    fun addressesAreLimitedToTheLocalNetwork() {
        assertEquals(ServerAddress("192.168.1.20", 8787), ServerAddress.parse(" 192.168.1.20 "))
        assertEquals(ServerAddress("10.0.0.7", 9000), ServerAddress.parse("10.0.0.7:9000"))
        assertEquals(ServerAddress("172.20.3.4", 8787), ServerAddress.parse("http://172.20.3.4:8787/ekran"))
        assertEquals("192.168.1.20:8787", ServerAddress("192.168.1.20").toString())
        for (bad in listOf("", "8.8.8.8", "example.com", "192.168.1", "192.168.1.300", "172.32.0.1", "192.168.1.20:0", "192.168.1.20:abc",
            "192.168.1.20:70000", "https://192.168.1.20", "127.0.0.1"))
            assertNull(bad, ServerAddress.parse(bad))
    }

    @Test
    fun lastServerIsReusedOnlyWhenNothingBetterIsFound() {
        val last = ServerAddress("192.168.1.20", 9000)
        var scans = 0
        fun scan(found: OtherServers.Found?): () -> OtherServers.Found? = { scans++; found }
        // Son adres çeviri yapıyor: ağ taranmaz.
        assertEquals(Pair(last, true), ServerAddress.choose(last, { true }, scan(OtherServers.Found("192.168.1.77", true))))
        assertEquals(0, scans)
        // Son adres açık ama çevirmiyor, ağda çeviren var: çeviren seçilir.
        assertEquals(Pair(ServerAddress("192.168.1.77"), true), ServerAddress.choose(last, { false }, scan(OtherServers.Found("192.168.1.77", true))))
        // Çeviren yok: yanıt veren son adres (portuyla) kalır.
        assertEquals(Pair(last, false), ServerAddress.choose(last, { false }, scan(OtherServers.Found("192.168.1.77", false))))
        assertEquals(Pair(last, false), ServerAddress.choose(last, { false }, scan(null)))
        // Son adres yanıt vermiyor ya da hiç yok: taramada bulunan.
        assertEquals(Pair(ServerAddress("192.168.1.77"), false), ServerAddress.choose(last, { null }, scan(OtherServers.Found("192.168.1.77", false))))
        assertEquals(Pair(ServerAddress("192.168.1.77"), true), ServerAddress.choose(null, { null }, scan(OtherServers.Found("192.168.1.77", true))))
        assertNull(ServerAddress.choose(last, { null }, scan(null)))
        assertNull(ServerAddress.choose(null, { true }, scan(null)))
    }

    @Test
    fun relayCarriesThePageAndTheEventStream() {
        val server = SubtitleServer(port = 0)
        val port = server.start()
        server.screenPage = page
        val relay = LoopbackRelay("127.0.0.1", port)
        // 0: boş bir port; testler aynı anda çalışırken sabit port çakışmasın.
        val local = relay.start(preferredPort = 0)
        try {
            assertEquals(local, relay.start())
            assertArrayEquals(page, get("http://127.0.0.1:$local/ekran").inputStream.readBytes())
            assertTrue(get("http://127.0.0.1:$local/hello").inputStream.bufferedReader().readText().contains("gametranslator"))
            Socket("127.0.0.1", local).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET /events?viewer=obs&screen=1 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                fun readUntil(marker: String): String {
                    val text = StringBuilder()
                    while (!text.contains(marker)) text.append(reader.readLine() ?: break).append('\n')
                    return text.toString()
                }
                assertTrue(readUntil("retry:").contains("text/event-stream"))
                server.sendSubtitle("Köprüden geçti", 15000, null, false)
                assertTrue(readUntil("Köprüden").contains("\"text\":\"Köprüden geçti\""))
                // Köprü kapanınca açık akış da kapanır.
                relay.stop()
                var rest: String? = ""
                while (rest != null) rest = runCatching { reader.readLine() }.getOrNull()
            }
        } finally {
            relay.stop()
            server.stop()
        }
    }

    @Test
    fun relayClosesTheConnectionWhenTheServerIsGone() {
        val server = SubtitleServer(port = 0)
        val port = server.start()
        server.stop()
        val relay = LoopbackRelay("127.0.0.1", port, connectTimeoutMs = 500)
        val local = relay.start(preferredPort = 0)
        try {
            Socket("127.0.0.1", local).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET /ekran HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                assertTrue(runCatching { socket.getInputStream().read() }.getOrDefault(-1) <= 0)
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun screenLineReplaysWhatIsStillOnScreen() {
        val line = ScreenLine()
        assertNull(line.current(0))
        assertEquals(mapOf("type" to "subtitle", "text" to "Merhaba", "duration" to 8000, "replace" to true), line.show("Merhaba", 8000, true, 1000))
        // Ekran sonradan açılırsa satır kalan süresiyle gelir; "replace" taşınmaz (öncesi yoktur).
        assertEquals(mapOf("type" to "subtitle", "text" to "Merhaba", "duration" to 5000, "replace" to false), line.current(4000))
        assertNull(line.current(9000))
        line.show("İkinci", 8000, false, 10000)
        assertEquals(mapOf("type" to "clear"), line.clear())
        assertNull(line.current(10001))
    }
}
