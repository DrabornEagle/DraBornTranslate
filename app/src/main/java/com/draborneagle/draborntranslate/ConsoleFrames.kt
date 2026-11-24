// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.gametranslator.gametranslator_mobile.Native
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

internal data class PairedConsole(val host: String, val name: String, val account: ByteArray, val keys: ByteArray)

internal object ConsolePairing {
    val available: Boolean get() = Native.available
    private fun prefs(context: Context) = context.getSharedPreferences("ps5-local", Context.MODE_PRIVATE)

    fun localHost(value: String): String? {
        val fields = value.trim().split('.').map { it.toIntOrNull()?.takeIf { number -> number in 0..255 } ?: return null }
        if (fields.size != 4 || fields[3] == 255) return null
        val private = fields[0] == 10 || fields[0] == 192 && fields[1] == 168 || fields[0] == 172 && fields[1] in 16..31
        return if (private) fields.joinToString(".") else null
    }

    fun account(value: String): ByteArray? {
        val text = value.trim()
        if (text.matches(Regex("[0-9]{1,20}"))) {
            return text.toULongOrNull()?.takeIf { it > 0u }?.let {
                ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(it.toLong()).array()
            }
        }
        return runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull()?.takeIf { it.size == 8 && it.any { byte -> byte != 0.toByte() } }
    }

    fun discover(context: Context): List<Pair<String, String>> {
        if (!available) return emptyList()
        val ip = portalLanAddress(context)?.hostAddress ?: return emptyList()
        val broadcast = ip.substringBeforeLast('.') + ".255"
        val found = linkedMapOf<String, String>()
        Native.discover(arrayOf(broadcast, "255.255.255.255"), 2400, object : Native.ConsoleCallback {
            override fun onConsole(host: String, name: ByteArray, awake: Boolean) {
                val validated = localHost(host) ?: return
                synchronized(found) {
                    found[validated] = name.toString(Charsets.UTF_8).trimEnd('\u0000').ifBlank { "PS5" } +
                        if (awake) "" else " · Dinlenme modu"
                }
            }
        })
        return synchronized(found) { found.toList() }
    }

    fun pair(context: Context, host: String, account: ByteArray, pin: String): PairedConsole {
        val address = localHost(host) ?: throw IOException("PS5’in yerel Wi-Fi adresini gir.")
        if (account.size != 8 || !pin.matches(Regex("[0-9]{8}"))) throw IOException("Hesap kimliğini ve 8 haneli kodu kontrol et.")
        if (!available) throw IOException("PS5 bağlantı bileşeni açılamadı.")
        val reply = ByteArray(Native.REGISTERED_SIZE)
        val reason = IntArray(1)
        val status = Native.register(address, account, pin.toInt(), reply, reason, null)
        if (status != 0) throw IOException(when (reason[0]) {
            0x80108b02.toInt() -> "Bu PSN kimliği PS5’te seçili kullanıcıya ait görünmüyor."
            0x80108b09.toInt(), 0x80108b12.toInt() -> "PS5’te yeni eşleme kodu açıp tekrar dene."
            else -> "PS5 yanıt vermedi. İki cihazı aynı ağa bağla ve PS5’te Cihazı Eşleştir ekranını açık tut."
        })
        val nameEnd = (0 until 32).firstOrNull { reply[it] == 0.toByte() } ?: 32
        val pairing = PairedConsole(address, String(reply, 0, nameEnd, Charsets.UTF_8).ifBlank { "PS5" },
            account, reply.copyOfRange(32, 64))
        prefs(context).edit().putString("host", pairing.host).putString("name", pairing.name)
            .putString("account", Base64.encodeToString(pairing.account, Base64.NO_WRAP))
            .putString("keys", Base64.encodeToString(pairing.keys, Base64.NO_WRAP)).apply()
        return pairing
    }

    fun load(context: Context): PairedConsole? = runCatching {
        val prefs = prefs(context)
        val host = localHost(prefs.getString("host", "").orEmpty()) ?: return null
        val account = account(prefs.getString("account", "").orEmpty()) ?: return null
        val keys = Base64.decode(prefs.getString("keys", "").orEmpty(), Base64.DEFAULT)
        if (keys.size != 32) return null
        PairedConsole(host, prefs.getString("name", "PS5").orEmpty(), account, keys)
    }.getOrNull()

    fun forget(context: Context) { prefs(context).edit().clear().apply() }
}

internal class ConsoleFrames(
    private val console: PairedConsole,
    private val settings: CaptureSettings,
    private val captionFrame: (Bitmap) -> Unit,
    private val reserveCaption: () -> Boolean,
    private val discardCaption: () -> Unit,
    private val focusFrame: (Bitmap) -> Unit,
    private val onConnected: () -> Unit,
    private val onEnded: (String) -> Unit,
) : Native.SessionListener, AutoCloseable {
    @Volatile private var closed = false
    @Volatile var lastFrameAt = 0L
        private set
    private var handle = 0L // Only the native session executor may start and stop libchiaki.
    private var lastCaption = 0L
    private var lastFocus = 0L

    fun start() {
        sessions.execute {
            if (closed) return@execute
            handle = Native.sessionStart(console.host, console.keys.copyOfRange(0, 16),
                console.keys.copyOfRange(16, 32), console.account, 30, this)
            if (handle == 0L && !closed) onEnded("PS5 görüntüsü açılamadı. Eşlemeyi ve aynı ağı kontrol et.")
        }
    }

    override fun onFrame(buffer: ByteBuffer, width: Int, height: Int, stride: Int) {
        if (closed || width !in 2..1920 || height !in 2..1080 || stride < width * 4) return
        val now = SystemClock.elapsedRealtime()
        lastFrameAt = now
        val area = Pixels(0, 0, width, height)
        if (settings.focus && now - lastFocus >= 950L) {
            lastFocus = now
            runCatching { readRgba(buffer, stride, area) }.getOrNull()?.let(focusFrame)
        }
        if (settings.captionEnabled && now - lastCaption >= settings.readingArea.sampleIntervalMs && reserveCaption()) {
            lastCaption = now
            val bitmap = runCatching { readRgba(buffer, stride, settings.scanArea(area)) }.getOrNull()
            if (bitmap == null) discardCaption() else captionFrame(bitmap)
        }
    }

    override fun onEvent(event: Int, quitReason: Int, reasonText: String?) {
        if (closed) return
        when (event) {
            Native.EVENT_CONNECTED -> onConnected()
            Native.EVENT_LOGIN_PIN -> onEnded("PS5 kullanıcı giriş kodunu konsolda tamamla. Ardından bağlantıyı yeniden başlat.")
            Native.EVENT_QUIT -> onEnded(when (quitReason) {
                Native.QUIT_IN_USE -> "PS5’te başka Uzaktan Oynama oturumu açık."
                Native.QUIT_REFUSED -> "PS5 bağlantıyı reddetti. Konsolu aç ve eşlemeyi kontrol et."
                else -> "PS5 görüntüsü kesildi. Konsolun ve telefonun Wi-Fi bağlantısını kontrol et."
            })
        }
    }

    override fun onLog(level: Int, message: String?) = Unit

    override fun close() {
        closed = true
        sessions.execute {
            if (handle != 0L) {
                Native.sessionStop(handle)
                handle = 0L
            }
        }
    }

    companion object { private val sessions = Executors.newSingleThreadExecutor() }
}

internal fun readRgba(source: ByteBuffer, stride: Int, area: Pixels, storage: ByteBuffer? = null): Bitmap? {
    val rowBytes = area.width * 4
    val offset = source.position() + area.y * stride + area.x * 4
    if (area.width <= 0 || area.height <= 0 || offset + (area.height - 1) * stride + rowBytes > source.limit()) return null
    val input = source.duplicate()
    val output = storage ?: ByteBuffer.allocate(rowBytes * area.height)
    output.clear()
    for (row in 0 until area.height) {
        val start = offset + row * stride
        input.limit(start + rowBytes)
        input.position(start)
        output.put(input)
    }
    output.flip()
    return Bitmap.createBitmap(area.width, area.height, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(output) }
}
