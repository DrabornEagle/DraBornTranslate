// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.gametranslator.gametranslator_mobile.Engine
import java.io.IOException

private const val CAPICHE = "https://capiche.gg/tr"

class CapicheActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var toggle: Button
    private lateinit var copyLink: Button
    private val observer: (SessionState.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = miamiPage("Capiche", Miami.coral)
        content.addBelow(miamiHero("Türkçe dublaj", "GTA 6 altyazılarını seslendiren Capiche’yi bilgisayar tarayıcısında aç.", Miami.coral))
        content.addBelow(actionRow(
            press("Capiche’yi aç", Miami.coral) { openBrowserLink(CAPICHE) },
            press("Capiche’ye bağış yap") { openBrowserLink("$CAPICHE#supportBar") },
        ), 12)
        val window = miamiSection(
            "Oyun penceresi",
            "Konsol yayını sırasında bağlantıyı aynı ağdaki bilgisayarda ayrı bir Chrome veya Edge penceresinde aç. " +
                "Capiche’de pencereyi seç, çerçeveyi İngilizce altyazılara yerleştir ve dublajı başlat.",
            Miami.aqua,
        )
        status = copy("", 14, Miami.aqua).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        window.addBelow(status, 10)
        toggle = press("Oyun penceresini paylaş", Miami.aqua) {
            val result = runCatching {
                if (CapicheGameWindow.url == null) CapicheGameWindow.start(this)
                else CapicheGameWindow.stop()
            }
            render(SessionState.current)
            result.exceptionOrNull()?.let { status.text = it.message ?: "Oyun penceresi açılamadı." }
        }
        window.addBelow(toggle, 10)
        address = copy("", 13, Palette.white).apply { setTextIsSelectable(true) }
        window.addBelow(address, 7)
        copyLink = press("Oyun bağlantısını kopyala") {
            CapicheGameWindow.url?.let { link ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Oyun penceresi", link))
                status.text = "Oyun bağlantısı kopyalandı."
            }
        }
        window.addBelow(copyLink, 7)
        content.addBelow(window, 14)
        render(SessionState.current)
    }

    override fun onResume() {
        super.onResume()
        SessionState.observe(observer)
    }

    override fun onPause() {
        SessionState.remove(observer)
        super.onPause()
    }

    private fun render(state: SessionState.State) {
        val url = CapicheGameWindow.url
        val ready = ConsoleSession.active && state.videoVisible
        status.text = when {
            url != null -> "Oyun penceresi yerel ağda açık."
            ready -> "Konsol görüntüsü hazır."
            else -> "Oyun penceresi için PS5 ve TV’ye bağlan ekranından konsol yayınını başlat."
        }
        toggle.text = if (url == null) "Oyun penceresini paylaş" else "Oyun penceresini durdur"
        toggle.isEnabled = ready || url != null
        address.text = url.orEmpty()
        address.visibility = if (url == null) View.GONE else View.VISIBLE
        copyLink.visibility = address.visibility
    }
}

/** Explicit, temporary sharing of the console session's raw frames on the selected LAN. */
internal object CapicheGameWindow {
    private var server: PortalPanelServer? = null
    val url get() = server?.url

    fun start(context: Context) {
        if (server != null) return
        if (!ConsoleSession.active || !SessionState.current.videoVisible) {
            throw IOException("Konsol görüntüsü hazır olduğunda oyun penceresini aç.")
        }
        val address = portalLanAddress(context) ?: throw IOException("Telefonu ve bilgisayarı aynı Wi-Fi ağına bağla.")
        val page = context.assets.open("capiche-game-window.html").bufferedReader().use { it.readText() }
        val frames = GameWindowFrames()
        server = try {
            PortalPanelServer(address, page, 20, gameJpeg = frames::image) {}
        } catch (_: IOException) {
            throw IOException("Oyun penceresi yerel ağda açılamadı.")
        }
    }

    fun stop() {
        val open = server
        server = null
        runCatching { open?.close() }
    }

    private class GameWindowFrames {
        private var jpeg: ByteArray? = null
        private var capturedAt = 0L

        @Synchronized fun image(): ByteArray? {
            if (!ConsoleSession.active || !Engine.running || Engine.phase != "streaming") {
                jpeg = null
                return null
            }
            val now = SystemClock.elapsedRealtime()
            if (jpeg == null || now - capturedAt >= 160L) {
                jpeg = Engine.snapshot(1280)
                capturedAt = now
            }
            return jpeg
        }
    }
}
