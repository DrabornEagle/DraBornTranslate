// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * PS5'e keşif sorusu (UDP 9302, "SRCH"): açık mı ve o an hangi oyun çalışıyor. Yanıt satırlarından
 * `running-app-titleid` ve `running-app-name` okunur (chiaki-ng discovery.c ile aynı biçim). Soru PS5'i uyandırmaz;
 * dinlenmedeyse yanıt 620 koduyla gelir ya da hiç gelmez. Windows'taki Ps5Status.cs ile aynı kurallar.
 */
object Ps5Status {
    const val PORT = 9302
    private const val REQUEST = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010\n"
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** [awake]: yanıt kodu 200 (açık); 620 dinlenmede. */
    data class Info(val awake: Boolean, val hostName: String?, val game: RunningGame?)

    /** PS5'in yanıtı (bloklar, en çok [timeoutMs]); yanıt gelmezse (kapalı, ağda değil) ya da okunamazsa null. */
    fun query(host: String, timeoutMs: Int): Info? {
        // Ad çözümlemesi yapılmaz: kayıtta hep IP adresi durur.
        if (!ipv4.matches(host.trim())) return null
        return try {
            val address = InetAddress.getByName(host.trim())
            DatagramSocket().use { socket ->
                val request = REQUEST.toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(request, request.size, address, PORT))
                val deadline = System.nanoTime() / 1_000_000 + timeoutMs
                val buffer = ByteArray(2048)
                while (true) {
                    val left = deadline - System.nanoTime() / 1_000_000
                    if (left <= 0) return null
                    socket.soTimeout = left.toInt().coerceAtLeast(1)
                    val reply = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(reply)
                    } catch (timeout: SocketTimeoutException) {
                        return null
                    }
                    if (reply.address != address) continue
                    return parse(String(reply.data, reply.offset, reply.length, Charsets.UTF_8))
                }
                null
            }
        } catch (error: Exception) {
            null
        }
    }

    /** "HTTP/1.1 200 Ok" ve "anahtar:değer" satırları; ilk satır HTTP değilse null. */
    fun parse(text: String): Info? {
        val lines = text.replace("\r", "").split('\n')
        if (lines.isEmpty() || !lines[0].startsWith("HTTP/")) return null
        val parts = lines[0].split(' ', limit = 3)
        val awake = parts.size > 1 && parts[1] == "200"
        val headers = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        return Info(awake, headers["host-name"], RunningGame.create(headers["running-app-titleid"], headers["running-app-name"]))
    }
}
