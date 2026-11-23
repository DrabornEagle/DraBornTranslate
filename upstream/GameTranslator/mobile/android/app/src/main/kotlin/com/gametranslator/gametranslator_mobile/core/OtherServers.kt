// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ağdaki başka GameTranslator altyazı sunucuları (çoğu zaman açık duran bilgisayar uygulaması). TV uygulaması ağı
 * tarayıp ilk bulduğu sunucuya bağlanır; bilgisayardaki uygulama açıksa TV ona bağlanır ve telefonun çevirisi TV'ye
 * hiç gitmez (TV uygulaması 1.0.6'dan itibaren çeviri yapanı seçer). Telefon bunu görüp kullanıcıya söyler.
 * Telefon yalnızca ekran olarak kullanılırken ("Bilgisayarın altyazısını göster") çeviri yapan sunucu da buradan bulunur.
 */
object OtherServers {
    /** Ağda bulunan bir sunucu. [active]: `/hello` yanıtına göre çeviri şu an çalışıyor. */
    data class Found(val host: String, val active: Boolean)

    /** [host]:[port] bir GameTranslator sunucusu mu (`/hello` yanıtına bakılır). Ulaşılamıyorsa false. */
    fun isServer(host: String, port: Int = SubtitleServer.DEFAULT_PORT, timeoutMs: Int = 700): Boolean = hello(host, port, timeoutMs) != null

    /**
     * [host]:[port] bir GameTranslator sunucusuysa çevirinin çalışıp çalışmadığı (`"active"`); sunucu değilse ya da
     * ulaşılamıyorsa null.
     */
    fun hello(host: String, port: Int = SubtitleServer.DEFAULT_PORT, timeoutMs: Int = 700): Boolean? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write("GET /hello HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val buffer = ByteArray(1024)
            val text = StringBuilder()
            val input = socket.getInputStream()
            while (text.length < 2048) {
                val count = input.read(buffer)
                if (count <= 0) break
                text.append(String(buffer, 0, count, Charsets.US_ASCII))
            }
            parseHello(text.toString())
        } catch (error: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /** `/hello` yanıtı (başlıklarıyla) bir GameTranslator sunucusundansa `"active"` değeri; değilse null. */
    internal fun parseHello(response: String): Boolean? {
        val body = response.lowercase()
        if (!body.startsWith("http/1.1 200") || !(body.contains("gametranslator") || body.contains("couchsubs"))) return null
        return Regex("\"active\"\\s*:\\s*true").containsMatchIn(body)
    }

    /**
     * [own] adresinin /24 ağında, kendisi dışındaki ilk GameTranslator sunucusunun adresi; yoksa null. Bloklar
     * (en çok birkaç saniye). [probe] testte değiştirilir.
     */
    fun find(own: String, probe: (String) -> Boolean = { isServer(it) }): String? =
        scan(own, firstOnly = true) { host -> if (probe(host)) Found(host, false) else null }.firstOrNull()?.host

    /**
     * Ekran olarak bağlanılacak sunucu: [own] adresinin /24 ağında, kendisi dışındaki sunuculardan çeviri yapan
     * (`"active":true`); çeviri yapan yoksa bulunanlardan ilki, hiç yoksa null. Bloklar (en çok birkaç saniye).
     * [probe]: adres sunucuysa `active` değeri, değilse null; testte değiştirilir.
     */
    fun findDisplaySource(own: String, probe: (String) -> Boolean? = { hello(it) }): Found? {
        val found = scan(own, firstOnly = false) { host -> probe(host)?.let { Found(host, it) } }
        return found.firstOrNull { it.active } ?: found.firstOrNull()
    }

    /**
     * /24 ağındaki ([own] dışındaki) adresleri [probe] ile sorar; bulunanlar adres sırasıyla. [firstOnly]: ilk bulunan
     * yeter, kalanlar beklenmez. Geçersiz adreste boş.
     */
    private fun scan(own: String, firstOnly: Boolean, probe: (String) -> Found?): List<Found> {
        val parts = own.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return emptyList()
        val prefix = parts.take(3).joinToString(".") + "."
        val pool = Executors.newFixedThreadPool(32) { r -> Thread(r, "gt-server-scan").apply { isDaemon = true } }
        try {
            val tasks = (1..254).map { prefix + it }.filter { it != own }
                .map { host -> pool.submit(Callable { probe(host) }) }
            val results = tasks.asSequence().mapNotNull { runCatching { it.get(15, TimeUnit.SECONDS) }.getOrNull() }
            return if (firstOnly) results.take(1).toList() else results.toList()
        } finally {
            pool.shutdownNow()
        }
    }
}
