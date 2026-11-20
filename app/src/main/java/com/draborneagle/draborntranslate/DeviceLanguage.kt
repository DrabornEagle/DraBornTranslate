// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

internal data class TranslationInput(
    val text: String,
    val previous: List<String> = emptyList(),
    val readingArea: ReadingArea = ReadingArea.SUBTITLES,
)

internal data class TranslationReply(val text: String? = null, val issue: String? = null)

internal interface CaptionTranslator : AutoCloseable {
    fun prepare(done: (Boolean) -> Unit) = done(true)
    fun translate(input: TranslationInput, done: (TranslationReply) -> Unit)
}

internal fun Context.captionTranslator(engine: TranslationEngine): CaptionTranslator? = when (engine) {
    TranslationEngine.OFFLINE -> DeviceTranslator()
    TranslationEngine.NATURAL -> GeminiKey.read(this)?.let(::GeminiTranslator)
}

internal class DeviceTranslator : CaptionTranslator {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build(),
    )

    override fun prepare(done: (Boolean) -> Unit) {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnCompleteListener { done(it.isSuccessful) }
    }

    override fun translate(input: TranslationInput, done: (TranslationReply) -> Unit) {
        translator.translate(input.text).addOnCompleteListener {
            done(TranslationReply(text = if (it.isSuccessful) it.result else null))
        }
    }

    override fun close() = translator.close()
}

internal class SubtitleReader(private val readingArea: ReadingArea = ReadingArea.SUBTITLES) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(bitmap: Bitmap, done: (List<String>?) -> Unit) {
        val width = bitmap.width
        val height = bitmap.height
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
            bitmap.recycle()
            if (!task.isSuccessful) {
                done(null)
                return@addOnCompleteListener
            }
            val lines = task.result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                if (line.text.count(Char::isLetter) < 2 || box.height() < 7) return@mapNotNull null
                line.text.trim() to box
            }
            if (readingArea == ReadingArea.GAME_TEXT) {
                done(lines.sortedWith(compareBy({ it.second.top / 10 }, { it.second.left }))
                    .map { it.first.take(100) }
                    .distinct()
                    .take(24))
                return@addOnCompleteListener
            }
            // Altyazı bölgesinde ortadaki satır grubuna öncelik ver.
            val anchor = lines.minByOrNull { (_, box) ->
                abs(box.exactCenterX() / width - 0.5f) + abs(box.exactCenterY() / height - 0.5f) * 0.35f
            }
            if (anchor == null) {
                done(emptyList())
                return@addOnCompleteListener
            }
            val center = anchor.second
            val group = lines.filter { (_, box) ->
                abs(box.exactCenterY() - center.exactCenterY()) <= center.height() * 2.1f &&
                    abs(box.exactCenterX() - center.exactCenterX()) <= width * 0.32f &&
                    box.height() in (center.height() * 0.5f).toInt()..(center.height() * 2f).toInt()
            }.sortedWith(compareBy({ it.second.top }, { it.second.left }))
            done(group.take(3).map { it.first })
        }
    }

    override fun close() = recognizer.close()
}
