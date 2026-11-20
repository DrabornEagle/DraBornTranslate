// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.graphics.Bitmap
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

internal class DeviceTranslator : AutoCloseable {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build(),
    )

    fun prepare(done: (Boolean) -> Unit) {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnCompleteListener { done(it.isSuccessful) }
    }

    fun translate(source: String, done: (String?) -> Unit) {
        translator.translate(source).addOnCompleteListener {
            done(if (it.isSuccessful) it.result else null)
        }
    }

    override fun close() = translator.close()
}

internal class SubtitleReader : AutoCloseable {
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
            // Bir yakın satır grubunu oku. Köşelerdeki HUD yazıları yerine ortadaki satırı temel al.
            val lines = task.result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                if (line.text.count(Char::isLetter) < 2 || box.height() < 7) return@mapNotNull null
                line.text.trim() to box
            }
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
