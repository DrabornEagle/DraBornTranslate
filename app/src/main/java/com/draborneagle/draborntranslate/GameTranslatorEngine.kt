// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import com.gametranslator.gametranslator_mobile.core.Glossary
import com.gametranslator.gametranslator_mobile.core.GlossaryTranslator
import com.gametranslator.gametranslator_mobile.core.PackKey
import com.gametranslator.gametranslator_mobile.core.PackMatch
import com.gametranslator.gametranslator_mobile.core.PackLookup
import com.gametranslator.gametranslator_mobile.core.PackTranslation
import com.gametranslator.gametranslator_mobile.core.PrefixTranslator
import com.gametranslator.gametranslator_mobile.core.TextTranslator
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class GameTranslatorEngine(context: Context, engine: TranslationEngine) : CaptionTranslator {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val local = DeviceGameTranslator()
    private val online = if (engine == TranslationEngine.AUTO) GoogleGtxTranslator() else null
    private val packs = ImportedGamePack.library(context.applicationContext)
    private val pipeline = GameTranslationPipeline(
        packLookup = { text -> PackTranslation.match(packs, text) },
        online = online,
        offline = local,
    )
    @Volatile private var closed = false

    override fun prepare(done: (Boolean) -> Unit) {
        local.warmUp()
        done(true)
    }

    override fun translate(input: TranslationInput, done: (TranslationReply) -> Unit) {
        if (closed) return
        executor.execute {
            if (closed) return@execute
            val answer = pipeline.translate(input)
            val result = if (answer.text != null) answer else answer.copy(issue = local.issue())
            main.post { if (!closed) done(result) }
        }
    }

    override fun close() {
        closed = true
        online?.close()
        executor.shutdownNow()
        local.close()
    }
}

/** GameTranslator v1.8.48 sırası: hazır satır, anahtarsız çeviri, cihazdaki model. */
internal class GameTranslationPipeline(
    private val packLookup: (String) -> PackMatch?,
    online: TextTranslator?,
    offline: TextTranslator,
) {
    private val translator = PrefixTranslator(GlossaryTranslator(GAME_TERMS, TextTranslator { text ->
        online?.translate(text)?.takeIf(String::isNotBlank) ?: offline.translate(text)
    }))

    fun translate(input: TranslationInput): TranslationReply {
        val packed = runCatching { packLookup(input.text) }.getOrNull()
            ?: PackTranslation.match(input.text) { line -> PackLookup.match(line) { key ->
                PackKey.key(key)?.let(commonLines::get)
            } }
        val localText = packed?.text?.takeIf(String::isNotBlank)
        if (localText != null) return TranslationReply(localText, googleAttribution = false)
        val text = if (input.readingArea == ReadingArea.SUBTITLES) input.text.replace('\n', ' ') else input.text
        return TranslationReply(translator.translate(text)?.trim()?.takeIf(String::isNotEmpty))
    }
}

private val GAME_TERMS = Glossary.of(
    terms = mapOf(
        "wanted level" to "aranma seviyesi",
        "side mission" to "yan görev",
        "safe house" to "sığınak",
        "save point" to "kayıt noktası",
        "fast travel" to "hızlı seyahat",
        "health bar" to "can çubuğu",
    ),
    keep = listOf("Vice City", "Leonida", "Los Santos", "Liberty City", "San Andreas"),
)

private val commonLines = mapOf(
    "Lose the cops!" to "Polisleri atlat!",
    "We are being tailed." to "Takip ediliyoruz.",
    "We're being tailed." to "Takip ediliyoruz.",
    "They're on our tail." to "Peşimizdeler.",
    "Take cover!" to "Siper al!",
    "Get in the car!" to "Arabaya bin!",
    "Stay out of sight." to "Görünmeden ilerle.",
    "Hold your fire!" to "Ateş etmeyin!",
    "Keep your head down." to "Başını kaldırma.",
    "Let's get out of here." to "Hadi buradan çıkalım.",
    "Meet me at the bridge." to "Köprüde buluşalım.",
    "I'll cover you." to "Seni korurum.",
).mapKeys { (source, _) -> checkNotNull(PackKey.key(source)) }

/** GameTranslator v1.8.48 GoogleGtxTranslator uygulamasından uyarlandı. */
internal class GoogleGtxTranslator : TextTranslator, AutoCloseable {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var closed = false
    private var retryAfter = 0L

    override fun translate(text: String): String? {
        if (closed || SystemClock.elapsedRealtime() < retryAfter) return null
        val query = URLEncoder.encode(text.trim(), "UTF-8")
        if (query.isEmpty()) return null
        val endpoints = listOf(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=tr&dt=t&q=$query",
            "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl=en&tl=tr&dt=t&q=$query",
            "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=tr&q=$query",
        )
        for (endpoint in endpoints) {
            fetch(endpoint)?.let { return it }
            if (closed || SystemClock.elapsedRealtime() < retryAfter) return null
        }
        retryAfter = SystemClock.elapsedRealtime() + 15_000
        return null
    }

    private fun fetch(url: String): String? {
        val request = URL(url).openConnection() as HttpURLConnection
        connection = request
        return try {
            if (closed) return null
            request.instanceFollowRedirects = false
            request.connectTimeout = 1600
            request.readTimeout = 1800
            when (request.responseCode) {
                200 -> request.inputStream.bufferedReader(Charsets.UTF_8).use {
                    parseGoogleTranslation(it.readText())?.let { text ->
                        if (text.contains('&')) Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString() else text
                    }
                }
                in 300..399, 403, 429 -> {
                    retryAfter = SystemClock.elapsedRealtime() + 60_000
                    null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            request.disconnect()
            connection = null
        }
    }

    override fun close() {
        closed = true
        connection?.disconnect()
    }
}

internal fun parseGoogleTranslation(json: String): String? {
    val root = JSONArray(json.removePrefix(")]}'").trimStart())
    val sentences = root.optJSONArray(0)
    val text = if (sentences == null) root.opt(0) as? String
    else buildString {
        for (index in 0 until sentences.length()) {
            val part = sentences.optJSONArray(index)?.opt(0) as? String ?: continue
            append(part)
        }
    }
    return text?.trim()?.takeIf(String::isNotEmpty)
}

/** GameTranslator’ın ML Kit cihaz motoru, model indirmesi altyazı kuyruğunu bekletmez. */
private class DeviceGameTranslator : TextTranslator, AutoCloseable {
    private val translator = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.TURKISH)
        .build())
    @Volatile private var modelReady = false
    @Volatile private var downloadFailed = false

    fun warmUp() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).addOnCompleteListener { task ->
            modelReady = task.isSuccessful
            downloadFailed = !task.isSuccessful
        }
    }

    override fun translate(text: String): String? {
        if (!modelReady) return null
        return runCatching { Tasks.await(translator.translate(text), 3, TimeUnit.SECONDS) }.getOrNull()
    }

    fun issue() = when {
        downloadFailed -> "Cihazdaki dil modeli indirilemedi. İnterneti kontrol edip oturumu yeniden başlat."
        !modelReady -> "Cihazdaki dil modeli hazırlanıyor. Paket satırları bu sırada kullanılabilir."
        else -> "Bu satır çevrilemedi. Yeni replikte tekrar denenecek."
    }

    override fun close() = translator.close()
}
