// SPDX-License-Identifier: AGPL-3.0-only
// Giriş akışı Apps2Samsung'dan (MIT, Patrick Stel) uyarlandı: https://github.com/Jellyfin2Samsung/Samsung-Jellyfin-Installer
// Windows'taki SamsungLogin.cs'nin karşılığı.
package com.gametranslator.gametranslator_mobile.tvinstall

import com.gametranslator.gametranslator_mobile.core.UiText
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Samsung hesabıyla giriş. Samsung'un SignInGate sayfası (telefonda uygulama içi tarayıcıda ya da tarayıcıda açılır)
 * giriş bitince jetonu `http://localhost:4794/signin/callback` adresine POST eder; bu adreste telefonun kendisinde
 * küçük bir dinleyici bekler. Tizen Studio'nun sertifika eklentisi de aynı yolu kullanır. Şifre bu uygulamaya hiç
 * gelmez; yalnız Samsung'un verdiği erişim jetonu gelir.
 */
object SamsungLogin {
    private const val SIGN_IN_GATE = "https://account.samsung.com/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate"
    private const val CLIENT_ID = "v285zxnl3h"
    private const val STATE = "accountcheckdogeneratedstatetext"
    const val CALLBACK_PORT = 4794
    const val CALLBACK_PATH = "/signin/callback"

    val authorizeUrl: String
        get() = "$SIGN_IN_GATE?locale=&clientId=$CLIENT_ID" +
            "&redirect_uri=${URLEncoder.encode("http://localhost:$CALLBACK_PORT$CALLBACK_PATH", "UTF-8")}" +
            "&state=$STATE&tokenType=TOKEN"

    /** Gövde: `code=<JSON>&state=…`. JSON'da access_token, userId ve inputEmailID bulunur. */
    fun parseCallback(body: String): SamsungAccount? {
        var state: String? = null
        var code: String? = null
        for (part in body.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            if (eq < 0) continue
            val name = part.substring(0, eq)
            val value = part.substring(eq + 1)
            // .NET: state WebUtility.UrlDecode ("+" boşluk olur), code Uri.UnescapeDataString ("+" kalır).
            if (name == "state") state = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
            if (name == "code") code = percentDecode(value)
        }
        if (state != STATE || code.isNullOrBlank()) return null
        return try {
            val json = JSONObject(code)
            fun text(name: String): String? = if (json.opt(name) is String) json.getString(name) else null
            val token = text("access_token")?.takeIf { it.isNotEmpty() } ?: return null
            val user = text("userId")?.takeIf { it.isNotEmpty() } ?: return null
            SamsungAccount(token, user, text("inputEmailID") ?: "")
        } catch (error: Exception) {
            null
        }
    }

    /** Yüzde kaçışlarını UTF-8 olarak çözer; "+" olduğu gibi kalır. Bozuk kaçış olduğu gibi bırakılır. */
    fun percentDecode(text: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString("UTF-8")
    }

    /** HTTP isteğinin satırı ve gövdesi. */
    class Request(val method: String, val path: String, val body: String)

    fun readRequest(input: InputStream): Request? {
        val received = ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        var headerEnd: Int
        while (true) {
            headerEnd = indexOfHeaderEnd(received.toByteArray())
            if (headerEnd >= 0) break
            val n = input.read(buffer)
            if (n <= 0 || received.size() > 1024 * 1024) return null
            received.write(buffer, 0, n)
        }
        val bytes = received.toByteArray()
        val head = String(bytes, 0, headerEnd, Charsets.US_ASCII)
        val lines = head.split("\r\n")
        val request = lines[0].split(' ')
        var length = 0
        for (line in lines) {
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substring(15).trim().toIntOrNull() ?: 0
        }
        val body = ByteArrayOutputStream()
        body.write(bytes, headerEnd + 4, bytes.size - headerEnd - 4)
        while (body.size() < length && body.size() < 1024 * 1024) {
            val n = input.read(buffer)
            if (n <= 0) break
            body.write(buffer, 0, n)
        }
        return Request(request.getOrElse(0) { "" }, request.getOrElse(1) { "" }, body.toString("UTF-8"))
    }

    private fun indexOfHeaderEnd(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() && bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()) return i
        }
        return -1
    }

    private fun respond(output: OutputStream, status: Int, signedIn: Boolean) {
        val page = if (signedIn) {
            "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\"><title>GameTranslator</title>" +
                "<body style=\"font-family:system-ui;padding:2em;background:#000;color:#fff\"><h2>${UiText.t("samsung.done.title")}</h2>" +
                "<p>${UiText.t("samsung.done.text")}</p>"
        } else {
            "Not found"
        }
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            else -> "Not Found"
        }
        val body = page.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    /**
     * Geri dönüş adresini dinler. [start] dinleyiciyi açar (port doluysa [TvError.SIGN_IN_FAILED]); [await] giriş
     * gelene, süre dolana, [abort] ya da vazgeçme olana kadar bekler. Yalnız bu telefonun kendisinden gelen bağlantı
     * kabul edilir (127.0.0.1 ve ::1).
     */
    class Listener : Closeable {
        private val sockets = ArrayList<ServerSocket>()
        private val results = LinkedBlockingQueue<Result<SamsungAccount>>()

        @Volatile
        private var closed = false

        fun start(): Listener {
            for (address in listOf("127.0.0.1", "::1")) {
                try {
                    val socket = ServerSocket()
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(InetAddress.getByName(address), CALLBACK_PORT))
                    sockets.add(socket)
                } catch (error: Exception) {
                    if (address == "127.0.0.1") {
                        close()
                        throw TvInstallException(TvError.SIGN_IN_FAILED, "port $CALLBACK_PORT: ${error.message}", error)
                    }
                    // IPv6 yoksa yalnız IPv4 dinlenir.
                }
            }
            for (socket in sockets) thread(name = "samsung-login", isDaemon = true) { acceptLoop(socket) }
            return this
        }

        private fun acceptLoop(server: ServerSocket) {
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (error: SocketException) {
                    return
                } catch (error: Exception) {
                    continue
                }
                try {
                    client.use {
                        it.soTimeout = 15_000
                        val request = readRequest(it.getInputStream()) ?: return@use
                        val isCallback = request.method == "POST" && request.path.startsWith(CALLBACK_PATH)
                        val account = if (isCallback) parseCallback(request.body) else null
                        respond(it.getOutputStream(), if (account != null) 200 else if (isCallback) 400 else 404, account != null)
                        if (account != null) results.offer(Result.success(account))
                    }
                } catch (error: Exception) {
                    // bozuk istek: dinlemeye devam
                }
            }
        }

        /** Girişi bekler. Süre dolarsa ya da [abort] çağrılırsa [TvError.SIGN_IN_FAILED]. */
        fun await(timeoutMs: Long, cancel: Cancel): SamsungAccount {
            val end = System.currentTimeMillis() + timeoutMs
            while (true) {
                cancel.check()
                val left = end - System.currentTimeMillis()
                if (left <= 0) throw TvInstallException(TvError.SIGN_IN_FAILED, "zaman aşımı")
                val result = results.poll(minOf(left, 200), TimeUnit.MILLISECONDS) ?: continue
                return result.getOrThrow()
            }
        }

        fun abort(reason: String) {
            results.offer(Result.failure(TvInstallException(TvError.SIGN_IN_FAILED, reason)))
        }

        override fun close() {
            closed = true
            for (socket in sockets) runCatching { socket.close() }
        }
    }
}
