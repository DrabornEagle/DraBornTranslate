// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Telefonun kendisinde (127.0.0.1) dinleyip her bağlantıyı yerel ağdaki bir GameTranslator sunucusuna olduğu gibi
 * aktaran köprü. "Bilgisayarın altyazısını göster" ekranındaki uygulama içi tarayıcı bilgisayarın /ekran sayfasını
 * bunun üzerinden açar: uygulamada şifresiz HTTP yalnız telefonun kendi adresine açıktır
 * (res/xml/network_security_config.xml) ve bu kural yerel ağ adresleri için gevşetilmez. Yalnız 127.0.0.1'de dinler;
 * ağdaki başka cihazlar köprüye bağlanamaz.
 */
class LoopbackRelay(private val targetHost: String, private val targetPort: Int, private val connectTimeoutMs: Int = 3000) {
    companion object {
        /**
         * Önce denenen port. Sayfa yazı boyunu tarayıcının yerel deposunda adrese (porta) göre saklar; port aynı
         * kaldıkça ayar hatırlanır. Doluysa rastgele bir port seçilir.
         */
        const val PREFERRED_PORT = 8788
    }

    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "gt-relay").apply { isDaemon = true } }
    private val open = CopyOnWriteArrayList<Socket>()
    private var listener: ServerSocket? = null

    /** Köprüyü başlatır ve 127.0.0.1'de dinlenen portu döner. */
    @Synchronized
    fun start(preferredPort: Int = PREFERRED_PORT): Int {
        listener?.let { return it.localPort }
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val server = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(loopback, preferredPort)) }
        } catch (error: java.io.IOException) {
            ServerSocket(0, 50, loopback)
        }
        listener = server
        workers.execute { acceptLoop(server) }
        return server.localPort
    }

    @Synchronized
    fun stop() {
        close(listener)
        listener = null
        for (socket in open) close(socket)
        open.clear()
        workers.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (true) {
            val client = try { server.accept() } catch (error: java.io.IOException) { return }
            try {
                workers.execute { serve(client) }
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                return close(client)
            }
        }
    }

    private fun serve(client: Socket) {
        val target = Socket()
        open.add(client)
        open.add(target)
        try {
            target.connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
            // Altyazı çerçeveleri küçüktür; bekletilmeden iletilsin.
            target.tcpNoDelay = true
            client.tcpNoDelay = true
            target.keepAlive = true
            workers.execute { pump(target.getInputStream(), client.getOutputStream(), client, target) }
            pump(client.getInputStream(), target.getOutputStream(), client, target)
        } catch (error: Exception) {
            // Hedefe ulaşılamadı: bağlantı kapanır, tarayıcı sayfa hatası gösterir.
            finish(client, target)
        }
    }

    /** Bir yön bitince iki uç da kapanır (istek/yanıt ve olay akışı tek bağlantıdadır). */
    private fun pump(from: InputStream, to: OutputStream, client: Socket, target: Socket) {
        try {
            val buffer = ByteArray(8192)
            while (true) {
                val count = from.read(buffer)
                if (count <= 0) break
                to.write(buffer, 0, count)
                to.flush()
            }
        } catch (_: java.io.IOException) {
        } finally {
            finish(client, target)
        }
    }

    private fun finish(client: Socket, target: Socket) {
        close(client)
        close(target)
        open.remove(client)
        open.remove(target)
    }

    private fun close(closeable: java.io.Closeable?) {
        try { closeable?.close() } catch (_: java.io.IOException) {}
    }
}
