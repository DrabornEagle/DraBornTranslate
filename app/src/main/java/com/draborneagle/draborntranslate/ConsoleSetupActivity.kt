// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.Executors

class ConsoleSetupActivity : Activity() {
    private val network = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var account: EditText
    private lateinit var pin: EditText
    private lateinit var pair: Button
    private lateinit var scan: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = page()
        content.addView(heading("PS5 ve TV’ye bağlan"))
        content.addView(text("Telefonu ve konsolu aynı ağa bağla. TV’de ana hesabınla oynarken eşleme için PS5’te ikinci bir PSN kullanıcısı seç."))
        val current = ConsolePairing.load(this)
        status = text(current?.let { "Kayıtlı PS5 · ${it.name}" } ?: "PS5 → Ayarlar → Sistem → Uzaktan Oynama → Cihazı Eşleştir")
        content.addView(status)
        host = field("PS5 yerel IP adresi", InputType.TYPE_CLASS_PHONE)
        host.setText(current?.host.orEmpty())
        content.addView(host)
        scan = action("Ağda PS5 ara") { discover() }
        content.addView(scan)
        account = field("PSN hesap kimliği", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        content.addView(account)
        content.addView(text("Sayısal hesap kimliği veya 8 baytlık Base64. PSN kullanıcı adı ve parola girilmez."))
        content.addView(action("Hesap kimliğini bul") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://psn.flipscreen.games/")))
        })
        pin = field("PS5’in verdiği 8 haneli eşleme kodu", InputType.TYPE_CLASS_NUMBER)
        pin.filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        content.addView(pin)
        pair = action("PS5’i eşleştir", primary = true) { pair() }
        content.addView(pair)
        if (current != null) content.addView(action("Konsol eşlemesini kaldır") {
            ConsolePairing.forget(this)
            setResult(RESULT_OK)
            finish()
        })
        content.addView(action("Geri dön") { finish() })
        if (!ConsolePairing.available) {
            status.text = "PS5 yerel bağlantı bileşeni açılamadı. PS Remote Play üzerinden ekran paylaşımını kullanabilirsin."
            setBusy(true)
        }
    }

    private fun discover() {
        setBusy(true)
        status.text = "Yerel ağdaki PS5 aranıyor…"
        network.execute {
            val consoles = runCatching { ConsolePairing.discover(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                setBusy(false)
                when (consoles.size) {
                    0 -> status.text = "PS5 bulunamadı. Konsolun ağ ayarlarındaki IP adresini yaz."
                    1 -> {
                        host.setText(consoles[0].first)
                        status.text = consoles[0].second
                    }
                    else -> AlertDialog.Builder(this).setTitle("PS5’i seç")
                        .setItems(consoles.map { "${it.second} · ${it.first}" }.toTypedArray()) { _, index ->
                            host.setText(consoles[index].first)
                        }.setNegativeButton("Kapat", null).show()
                }
            }
        }
    }

    private fun pair() {
        val id = ConsolePairing.account(account.text.toString())
        if (id == null) {
            status.text = "Geçerli PSN hesap kimliğini gir."
            return
        }
        val ip = host.text.toString()
        val code = pin.text.toString()
        setBusy(true)
        status.text = "PS5 eşleniyor…"
        network.execute {
            val result = runCatching { ConsolePairing.pair(applicationContext, ip, id, code) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                setBusy(false)
                if (result.isSuccess) {
                    setResult(RESULT_OK)
                    finish()
                } else status.text = result.exceptionOrNull()?.message ?: "PS5 eşlenemedi."
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        pair.isEnabled = !busy
        scan.isEnabled = !busy
    }

    private fun field(label: String, kind: Int) = EditText(this).apply {
        hint = label
        contentDescription = label
        inputType = kind
        setSingleLine()
        minHeight = dp(58)
    }

    override fun onDestroy() {
        network.shutdownNow()
        super.onDestroy()
    }
}
