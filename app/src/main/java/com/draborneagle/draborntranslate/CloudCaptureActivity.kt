// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Camera-free translation of user-selected, downloaded PS App captures. */
class CloudCaptureActivity : Activity() {
    private val translator by lazy { GameTranslatorEngine(this) }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var pick: Button
    private lateinit var image: ImageView
    private var ready = false
    private var closed = false
    private var generation = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
            setBackgroundColor(0xFF10172A.toInt())
            setOnApplyWindowInsetsListener { v, ins ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val safe = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    v.setPadding(dp(16)+safe.left, dp(16)+safe.top, dp(16)+safe.right, dp(16)+safe.bottom)
                }
                ins
            }
        }
        content.addView(label("Cloud Captures · Türkçe", 24))
        content.addView(label("PS Portal'da Create ile ekran görüntüsü al. PS App → Oyun Kitaplığı → Yakalamalar bölümünden telefona indir. Ardından burada seç. Canlı yayın değil; kamera ve mikrofon gerekmez.", 15))
        content.addView(button("PS App'i aç") {
            val app = packageManager.getLaunchIntentForPackage("com.scee.psxandroid")
            if (app != null) startActivity(app) else status.text = "PlayStation App telefonda kurulu değil."
        })
        pick = button("Ekran görüntüsü seç") { choose() }.apply { isEnabled = false }
        content.addView(pick)
        status = label("Türkçe çeviri motoru hazırlanıyor…", 14).apply { setTextColor(0xFF8CEBFF.toInt()) }
        content.addView(status)
        image = ImageView(this).apply { adjustViewBounds = true; maxHeight = dp(300); scaleType = ImageView.ScaleType.FIT_CENTER }
        content.addView(image, LinearLayout.LayoutParams(-1, -2))
        output = label("", 18).apply { setTextIsSelectable(true) }
        content.addView(output, LinearLayout.LayoutParams(-1, -2))
        content.addView(button("Geri") { finish() })
        setContentView(ScrollView(this).apply { addView(content) })
        translator.prepare { ok ->
            if (!closed) {
                ready = ok; pick.isEnabled = ok
                status.text = if (ok) "Hazır · Bir ekran görüntüsü seç." else "Çeviri motoru hazırlanamadı."
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun choose() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }, 810)
    }
    @Deprecated("Android Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 810 && resultCode == RESULT_OK && ready) data?.data?.let(::read)
    }
    private fun read(uri: Uri) {
        val token = ++generation
        output.text = ""; status.text = "Görüntü okunuyor…"
        Thread {
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val ratio = minOf(1.0, 1920.0 / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1), (info.size.height*ratio).toInt().coerceAtLeast(1))
                }
            }.getOrNull()
            runOnUiThread {
                if (closed || token != generation) return@runOnUiThread
                if (bitmap == null) { status.text = "Görüntü açılamadı."; return@runOnUiThread }
                image.setImageBitmap(bitmap)
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        if (closed || token != generation) return@addOnSuccessListener
                        val lines = result.textBlocks.flatMap { it.lines }.map { it.text.trim() }
                            .filter { it.count(Char::isLetter) >= 2 }.distinct().take(40)
                        if (lines.isEmpty()) status.text = "Okunabilir metin bulunamadı."
                        else translate(lines, 0, token)
                    }
                    .addOnFailureListener { if (!closed && token == generation) status.text = "OCR başarısız." }
            }
        }.start()
    }
    private fun translate(lines: List<String>, index: Int, token: Int) {
        if (closed || token != generation) return
        if (index >= lines.size) { status.text = "Tamamlandı · ${lines.size} metin satırı"; return }
        status.text = "Türkçe çeviri · ${index+1}/${lines.size}"
        val line = lines[index]
        translator.translate(TranslationInput(line, readingArea = ReadingArea.GAME_TEXT)) { reply ->
            if (!closed && token == generation) {
                output.append(line + "\n" + (reply.text ?: "Çeviri bulunamadı") + "\n\n")
                translate(lines, index+1, token)
            }
        }
    }
    override fun onDestroy() {
        closed = true; generation++
        recognizer.close(); translator.close()
        super.onDestroy()
    }
    private fun label(s: String, size: Int) = TextView(this).apply {
        text = s; textSize = size.toFloat(); setTextColor(Color.WHITE); setPadding(0,dp(8),0,dp(8))
    }
    private fun button(s: String, action: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(0xFF364A76.toInt()); cornerRadius = dp(12).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { action() }
    }
    private fun dp(v: Int) = (v*resources.displayMetrics.density+.5f).toInt()
}
