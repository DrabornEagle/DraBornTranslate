// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Çeviriyi Android'in yüklü Türkçe ses verisiyle okur. */
internal class TurkishVoice(context: Context, private val onIssue: (String) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { fail() }
    private var ready = false
    private var closed = false
    private val pending = ArrayDeque<String>()
    private var lastText: String? = null
    private var lastSpokenAt = 0L
    private var sequence = 0
    private var utterance: String? = null
    private var engine: TextToSpeech? = try {
        TextToSpeech(context.applicationContext) { result -> main.post { initialize(result) } }
    } catch (_: RuntimeException) {
        null
    }

    init {
        main.postDelayed(timeout, if (engine == null) 0 else 12_000L)
    }

    private fun initialize(result: Int) {
        if (closed) return
        main.removeCallbacks(timeout)
        val speech = engine
        if (result != TextToSpeech.SUCCESS || speech == null) {
            fail()
            return
        }
        val turkish = Locale.forLanguageTag("tr-TR")
        val language = speech.setLanguage(turkish)
        val voice = speech.voices.orEmpty()
            .filter { it.locale.language == turkish.language && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .maxWithOrNull(compareBy({ it.locale == turkish }, { it.quality }, { -it.latency }))
        if (language < TextToSpeech.LANG_AVAILABLE || voice == null || speech.setVoice(voice) != TextToSpeech.SUCCESS) {
            fail()
            return
        }
        speech.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post {
                    if (!closed && utteranceId == utterance) {
                        utterance = null
                        speakNext()
                    }
                }
            }
            @Deprecated("Android uyumluluğu")
            override fun onError(utteranceId: String?) {
                main.post { if (!closed && utteranceId == utterance) fail() }
            }
        })
        ready = true
        speakNext()
    }

    fun speak(text: String?, latestOnly: Boolean = false) {
        if (closed) return
        val line = text?.replace('\n', ' ')?.trim()?.takeIf(String::isNotEmpty) ?: return
        val now = SystemClock.elapsedRealtime()
        if (line == lastText && now - lastSpokenAt < 3500L) return
        lastText = line
        lastSpokenAt = now
        if (latestOnly) {
            pending.clear()
            if (utterance != null) {
                utterance = null
                engine?.stop()
            }
        }
        if (pending.size == 8) pending.removeFirst()
        pending.addLast(line)
        speakNext()
    }

    private fun speakNext() {
        if (!ready || closed || utterance != null) return
        val line = pending.removeFirstOrNull() ?: return
        val id = "caption-${++sequence}"
        utterance = id
        if (engine?.speak(line, TextToSpeech.QUEUE_ADD, Bundle(), id) != TextToSpeech.SUCCESS) fail()
    }

    fun pause() {
        pending.clear()
        utterance = null
        lastText = null
        engine?.stop()
    }

    private fun fail() {
        if (closed) return
        close()
        onIssue("Türkçe ses açılamadı. Android’in metin okuma ayarlarından Türkçe ses verisini yükle ve sesi tekrar dene.")
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        pause()
        engine?.shutdown()
        engine = null
    }
}
