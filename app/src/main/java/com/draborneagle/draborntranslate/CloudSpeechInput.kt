// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * PS Portal cloud audio -> phone microphone. Default is strictly on-device;
 * system speech recognition requires a separate, explicit user consent.
 */
internal class CloudSpeechInput(
    private val context: Context,
    private val systemRecognizerConsent: Boolean,
    private val onFinal: (String) -> Unit,
    private val onPreview: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) : RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var failures = 0
    private var lastPhrase = ""
    private var lastPhraseAt = 0L
    private val restart = Runnable { beginListening() }
    private val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    val availableWithoutNetwork: Boolean get() = onDevice

    fun start(): Boolean {
        if (active) return true
        if (!onDevice && !systemRecognizerConsent) {
            onStatus("Bu telefonda çevrimdışı ses tanıma bulunamadı. Sistem hizmeti için ayrıca izin ver.")
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("Etkin konuşma tanıma hizmeti bulunamadı.")
            return false
        }
        return try {
            recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(this)
            active = true
            failures = 0
            onStatus(if (onDevice) "Mikrofon • cihaz içinde İngilizce algılanıyor"
                     else "Mikrofon • sistem hizmeti etkin (sağlayıcı çevrimiçi olabilir)")
            beginListening()
            true
        } catch (error: RuntimeException) {
            active = false
            recognizer?.destroy()
            recognizer = null
            onStatus("Ses tanıma açılamadı: \${error.message.orEmpty()}")
            false
        }
    }

    private fun beginListening() {
        if (!active) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
        }
        try { recognizer?.startListening(intent) } catch (error: RuntimeException) {
            onStatus("Ses hizmeti yeniden açılamadı: \${error.message.orEmpty()}")
            schedule(2400L)
        }
    }

    private fun schedule(delay: Long) {
        main.removeCallbacks(restart)
        if (active) main.postDelayed(restart, delay)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() { failures = 0 }
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onError(error: Int) {
        if (!active) return
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            error == SpeechRecognizer.ERROR_CLIENT) {
            onStatus("Mikrofon veya ses hizmeti erişimi sona erdi.")
            stop()
            return
        }
        failures = (failures + 1).coerceAtMost(7)
        val delay = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 600L
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 2500L
            else -> (900L * failures).coerceAtMost(6500L)
        }
        if (failures >= 4) onStatus("Konuşma duyulamıyor; Portal hoparlörünü mikrofona yaklaştır.")
        schedule(delay)
    }

    override fun onResults(results: Bundle?) {
        if (!active) return
        failures = 0
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.length >= 3) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (text.lowercase() != lastPhrase.lowercase() || now - lastPhraseAt > 8500) {
                lastPhrase = text
                lastPhraseAt = now
                onFinal(text.take(320))
            }
        }
        schedule(450L)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!active) return
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isNotEmpty()) onPreview(text.take(150))
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    fun stop() {
        active = false
        main.removeCallbacks(restart)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }
}
