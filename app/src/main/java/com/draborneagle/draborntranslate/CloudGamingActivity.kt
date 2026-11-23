// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Foreground, on-device speech prototype; never connects to PSN or captures Portal video. */
class CloudGamingActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var engine: GameTranslatorEngine? = null
    private var running = false
    private var busy = false
    private var pending: String? = null
    private var last = ""
    private var lastAt = 0L
    private var generation = 0
    private lateinit var status: TextView
    private lateinit var original: TextView
    private lateinit var subtitle: TextView
    private lateinit var toggle: Button

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(15, 23, 42))
        }
        fun line(value: String, size: Float, color: Int): TextView =
            TextView(this).apply { text = value; textSize = size; setTextColor(color)
                gravity = Gravity.CENTER; setPadding(8, 14, 8, 14); layout.addView(this) }
        line("☁ Cloud Gaming Translate", 25f, Color.WHITE)
        line("Deneysel • PS Portal hoparlörü → telefon mikrofonu. Çeviri yalnızca telefonda görünür.", 15f, 0xFFCBD5E1.toInt())
        status = line("Hazır • cihazda İngilizce ses tanıma gerekir.", 15f, 0xFF5EEAD4.toInt())
        original = line("", 17f, 0xFFCBD5E1.toInt())
        subtitle = line("Türkçe altyazı burada", 29f, Color.WHITE)
        toggle = Button(this).apply {
            text = "Canlı çeviriyi başlat"
            setOnClickListener { if (running) stopSession() else requestStart() }
        }
        layout.addView(toggle)
        layout.addView(Button(this).apply { text = "Ana ekrana dön"; setOnClickListener { finish() } })
        setContentView(layout)
    }

    private fun requestStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 70)
        } else startSession()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 70 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && !isFinishing) startSession()
        else if (requestCode == 70) status.text = "Mikrofon izni olmadan ses tanınamaz."
    }

    private fun startSession() {
        if (running) return
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            status.text = "Cihazda ses tanıma yok. Android 12+ ve çevrimdışı İngilizce tanıma hizmeti gerekir."
            return
        }
        val session = ++generation
        running = true
        toggle.text = "Durdur"
        status.text = "Oyun paketi ve çeviri motoru hazırlanıyor…"
        val translator = GameTranslatorEngine(this)
        engine = translator
        translator.prepare { ready ->
            if (!running || generation != session) return@prepare
            if (!ready) { stopSession("Çeviri motoru hazırlanamadı."); return@prepare }
            try {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                recognizer?.setRecognitionListener(listener(session))
                listen(session)
            } catch (error: Exception) { stopSession("Ses tanıma başlatılamadı: " + error.message) }
        }
    }

    private fun listen(session: Int) {
        if (!running || generation != session) return
        try {
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (error: Exception) { stopSession("Ses tanıma hatası: " + error.message) }
    }

    private fun listener(session: Int) = object : RecognitionListener {
        private fun active() = running && generation == session
        override fun onReadyForSpeech(params: Bundle?) { if (active()) status.text = "Dinleniyor • İngilizce (ABD)" }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            if (!active()) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    main.postDelayed({ listen(session) }, 350)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    stopSession("Ses tanıma meşgul. Yeniden başlat.")
                else -> stopSession("Ses tanıma hatası ($error). İngilizce çevrimdışı dil modelini ve mikrofonu kontrol et.")
            }
        }
        override fun onResults(results: Bundle?) {
            if (!active()) return
            val line = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (line.isNotEmpty()) enqueue(line)
            main.postDelayed({ listen(session) }, 250)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (active()) original.text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun enqueue(line: String) {
        val key = line.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == last && now - lastAt < 1800) return
        last = key
        lastAt = now
        pending = line
        drain()
    }

    // Keep at most one pending line; avoid an ever-growing translation backlog.
    private fun drain() {
        if (!running || busy) return
        val line = pending ?: return
        val translator = engine ?: return
        pending = null
        busy = true
        val session = generation
        original.text = line
        translator.translate(TranslationInput(line)) { result ->
            if (!running || generation != session) return@translate
            busy = false
            // Never overwrite a newer queued caption with stale output.
            if (pending == null) subtitle.text = result.text?.takeIf { it.isNotBlank() }
                ?.let { formatDialogue(it) } ?: "Çeviri bulunamadı."
            drain()
        }
    }

    private fun stopSession(message: String = "Durduruldu.") {
        running = false
        generation++
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        engine?.close()
        engine = null
        pending = null
        busy = false
        last = ""
        lastAt = 0
        toggle.text = "Canlı çeviriyi başlat"
        status.text = message
    }

    override fun onStop() { stopSession(); super.onStop() }
    override fun onDestroy() { stopSession(); super.onDestroy() }
}
