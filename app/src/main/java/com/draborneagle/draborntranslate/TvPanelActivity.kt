// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.gametranslator.gametranslator_mobile.Engine
import java.util.concurrent.Executors

class TvPanelActivity : Activity() {
    private val socket = Executors.newSingleThreadExecutor()
    private lateinit var address: TextView
    private lateinit var state: TextView
    private lateinit var copyAddress: Button
    private lateinit var openAddress: Button
    private lateinit var test: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = contentPage("TV tarayıcısı", Palette.purple)
        body.addBelow(featureHeader("TV altyazı paneli", "Telefon ve TV aynı ağa bağlıyken panel adresini TV’de aç.",
            R.drawable.ic_tv_screen, Palette.purple))
        state = copy("Panel hazırlanıyor…", 14, Palette.quiet).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addBelow(state, 14)
        address = copy("", 19, Palette.blue, true).apply {
            setTextIsSelectable(true)
            background = filled(Palette.surface, 16, Palette.border)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        body.addBelow(address, 8)
        copyAddress = press("Adresi kopyala") {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("DraBornTranslate TV paneli", address.text))
        }
        openAddress = press("Telefonda dene") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address.text.toString()))) }
        }
        body.addBelow(actionRow(copyAddress, openAddress), 8)
        test = press("Örnek altyazıyı göster", Palette.purple) {
            test.isEnabled = false
            socket.execute {
                val sent = runCatching { Engine.sendTestSubtitle() }.isSuccess
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    test.isEnabled = true
                    render()
                    if (!sent) state.text = "Panel açılamadı. Telefonun Wi-Fi bağlantısını kontrol et."
                }
            }
        }
        body.addBelow(test, 12)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val host = Engine.localAddress()
        val port = Engine.server.boundPort
        val url = if (host != null && port != 0) "http://$host:$port/ekran" else null
        address.text = url.orEmpty()
        address.visibility = if (url == null) View.GONE else View.VISIBLE
        copyAddress.visibility = address.visibility
        openAddress.visibility = address.visibility
        test.isEnabled = host != null
        state.text = when {
            host == null -> "Telefonu TV’nin Wi-Fi ağına bağla."
            url == null -> "Örnek yazıyı göstererek TV bağlantısını dene."
            else -> "Bu adresi TV’nizin tarayıcısına yaz."
        }
    }

    override fun onDestroy() {
        socket.shutdown()
        super.onDestroy()
    }
}
