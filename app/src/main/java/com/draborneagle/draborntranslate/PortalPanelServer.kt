// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.io.BufferedInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Serves one temporary browser panel on the chosen interface. */
internal class PortalPanelServer(
    address: Inet4Address,
    template: String,
    private val badge: ByteArray,
    fontSize: Int,
    private val onViewed: () -> Unit,
) : AutoCloseable {
    private val prefix = "/${newToken()}/"
    private val page = template.replace("{{caption-size}}", fontSize.coerceIn(16, 32).toString())
        .toByteArray(Charsets.UTF_8)
    private val listener = ServerSocket().apply {
        try {
            bind(InetSocketAddress(address, 0), 8)
        } catch (error: IOException) {
            close()
            throw error
        }
    }
    val url = "http://${address.hostAddress}:${listener.localPort}$prefix"

    private val closed = AtomicBoolean(false)
    private val viewed = AtomicBoolean(false)
    private val currentClient = AtomicReference<Socket?>()
    private data class CaptionFrame(val body: ByteArray, val testing: Boolean = false)

    @Volatile private var caption = CaptionFrame(ByteArray(0))

    private val thread = Thread(::listen, "DraBornTranslate-panel").apply {
        isDaemon = true
        start()
    }

    fun publish(text: String?, testing: Boolean = false) {
        if (!closed.get()) caption = CaptionFrame(text.orEmpty().toByteArray(Charsets.UTF_8), testing)
    }

    private fun listen() {
        while (!closed.get()) {
            val client = try {
                listener.accept()
            } catch (error: IOException) {
                break
            }
            currentClient.set(client)
            try {
                client.use {
                    if (!closed.get()) respond(it)
                }
            } catch (_: IOException) {
                // Browsers can cancel a request midway when changing tabs.
            } finally {
                currentClient.compareAndSet(client, null)
            }
        }
    }

    private fun respond(client: Socket) {
        client.soTimeout = 900
        val input = BufferedInputStream(client.getInputStream())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        val request = readLine(input, deadline, 1024)?.split(' ', limit = 3) ?: return
        if (request.size != 3 || !request[2].startsWith("HTTP/1.")) return

        var remaining = 8192
        while (remaining > 0) {
            val header = readLine(input, deadline, remaining) ?: return
            if (header.isEmpty()) break
            remaining -= header.length + 2
        }
        if (remaining <= 0) return
        val method = request[0]
        if (method != "GET" && method != "HEAD") {
            send(client, "405 Method Not Allowed", "text/plain", ByteArray(0), false, "Allow: GET, HEAD\r\n")
            return
        }

        val headOnly = method == "HEAD"
        when (request[1].substringBefore('?')) {
            prefix.dropLast(1) -> send(
                client, "302 Found", "text/plain", ByteArray(0), headOnly, "Location: $prefix\r\n",
            )
            prefix -> {
                send(client, "200 OK", "text/html; charset=utf-8", page, headOnly)
                if (!headOnly && viewed.compareAndSet(false, true)) onViewed()
            }
            "${prefix}caption.txt" -> {
                val frame = caption
                val mode = if (frame.testing) "X-DraBorn-Panel: test\r\n" else ""
                send(client, "200 OK", "text/plain; charset=utf-8", frame.body, headOnly, mode)
            }
            "${prefix}translate.png" -> send(client, "200 OK", "image/png", badge, headOnly)
            else -> send(client, "404 Not Found", "text/plain", ByteArray(0), headOnly)
        }
    }

    private fun readLine(input: BufferedInputStream, deadline: Long, limit: Int): String? {
        val line = StringBuilder()
        while (line.length < limit && System.nanoTime() < deadline) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
        return null
    }

    private fun send(
        client: Socket,
        status: String,
        type: String,
        body: ByteArray,
        headOnly: Boolean,
        extraHeaders: String = "",
    ) {
        val headers = "HTTP/1.1 $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            extraHeaders + "\r\n"
        client.getOutputStream().apply {
            write(headers.toByteArray(Charsets.US_ASCII))
            if (!headOnly) write(body)
            flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        caption = CaptionFrame(ByteArray(0))
        try {
            listener.close()
        } finally {
            currentClient.get()?.close()
        }
    }

    private companion object {
        fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
