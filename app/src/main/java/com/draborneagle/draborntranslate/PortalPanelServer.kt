// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.TextBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Serves one temporary browser panel on the chosen interface. */
internal class PortalPanelServer(
    address: Inet4Address,
    template: String,
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
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val workers = ThreadPoolExecutor(0, 6, 15, TimeUnit.SECONDS, SynchronousQueue()) { work ->
        Thread(work, "DraBornTranslate-panel-client").apply { isDaemon = true }
    }
    private val change = Object()
    private data class CaptionFrame(
        val revision: Long,
        val body: ByteArray,
        val testing: Boolean = false,
        val profile: ByteArray = EMPTY_STATE,
    )

    @Volatile private var caption = CaptionFrame(0, ByteArray(0))

    private val thread = Thread(::listen, "DraBornTranslate-panel").apply {
        isDaemon = true
        start()
    }

    fun publish(
        text: String?,
        testing: Boolean = false,
        position: CaptionPosition? = null,
        hud: List<HudBlock> = emptyList(),
        plainPhone: Boolean = false,
    ) {
        val body = text.orEmpty().toByteArray(Charsets.UTF_8)
        val profile = panelState(text, testing, position, hud, plainPhone)
        synchronized(change) {
            if (closed.get() || caption.profile.contentEquals(profile)) return
            caption = CaptionFrame(caption.revision + 1, body, testing, profile)
            change.notifyAll()
        }
    }

    private fun listen() {
        while (!closed.get()) {
            val client = try {
                listener.accept()
            } catch (error: IOException) {
                break
            }
            synchronized(clients) {
                if (closed.get()) {
                    runCatching { client.close() }
                    return
                }
                clients.add(client)
            }
            try {
                workers.execute { handle(client) }
            } catch (_: RejectedExecutionException) {
                clients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { if (!closed.get()) respond(it) }
        } catch (_: IOException) {
            // Browsers can cancel a request midway when changing tabs.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            clients.remove(client)
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
                send(client, "200 OK", "text/html; charset=utf-8", page, headOnly,
                    "X-DraBorn-Panel: ready\r\n")
            }
            "${prefix}caption.txt", "${prefix}state.json" -> {
                val json = request[1].substringBefore('?').endsWith("/state.json")
                val after = request[1].substringAfter("?after=", "").toLongOrNull()
                val frame = waitForCaption(if (headOnly) null else after)
                val mode = if (frame.testing) "X-DraBorn-Panel: test\r\n" else ""
                val type = if (json) "application/json; charset=utf-8" else "text/plain; charset=utf-8"
                send(client, "200 OK", type, if (json) frame.profile else frame.body, headOnly,
                    "X-DraBorn-Revision: ${frame.revision}\r\n$mode")
                if (!headOnly && viewed.compareAndSet(false, true)) onViewed()
            }
            else -> send(client, "404 Not Found", "text/plain", ByteArray(0), headOnly)
        }
    }

    private fun waitForCaption(after: Long?): CaptionFrame {
        if (after == null) return caption
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LONG_POLL_MS)
        synchronized(change) {
            while (!closed.get() && caption.revision == after) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) break
                TimeUnit.NANOSECONDS.timedWait(change, remainingNanos)
            }
            return caption
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
        BufferedOutputStream(client.getOutputStream()).apply {
            write(headers.toByteArray(Charsets.US_ASCII))
            if (!headOnly) write(body)
            flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(change) {
            caption = CaptionFrame(caption.revision + 1, ByteArray(0))
            change.notifyAll()
        }
        try {
            listener.close()
        } finally {
            synchronized(clients) {
                clients.forEach { runCatching { it.close() } }
            }
            workers.shutdownNow()
        }
    }

    private companion object {
        const val LONG_POLL_MS = 900L

        fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

private val EMPTY_STATE = """{"caption":"","testing":false,"plainPhone":false,"hud":[]}""".toByteArray(Charsets.UTF_8)

private fun panelState(
    caption: String?, testing: Boolean, position: CaptionPosition?, blocks: List<HudBlock>, plainPhone: Boolean,
): ByteArray {
    if (caption.isNullOrEmpty() && !testing && position == null && blocks.isEmpty() && !plainPhone) return EMPTY_STATE
    val hud = JSONArray()
    for (block in blocks) {
        hud.put(JSONObject().put("type", block.kind.name).put("label", block.kind.label)
            .put("title", block.title).put("box", block.box.coordinates())
            .put("rows", JSONArray(block.lines.map { line ->
                JSONObject().put("text", line.text).put("style", line.style.name)
            })))
    }
    return JSONObject().put("caption", caption.orEmpty()).put("testing", testing)
        .put("position", position?.box?.coordinates()).put("speakerColor", position?.speakerColor)
        .put("plainPhone", plainPhone).put("hud", hud).toString().toByteArray(Charsets.UTF_8)
}

private fun TextBox.coordinates() = JSONArray(listOf(left, top, right, bottom))
