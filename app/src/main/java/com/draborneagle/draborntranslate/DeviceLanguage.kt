// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

internal data class DetectedLine(val source: String, val bounds: Rect)

internal interface CaptionTranslator : AutoCloseable {
    fun prepare(done: (Boolean) -> Unit) = done(true)
    fun translate(input: TranslationInput, done: (TranslationReply) -> Unit)
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

    /** Preserve OCR bounding boxes for Lens mode instead of flattening them to subtitles. */
    fun readLocated(bitmap: Bitmap, done: (List<DetectedLine>?) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
            bitmap.recycle()
            if (!task.isSuccessful) {
                done(null)
                return@addOnCompleteListener
            }
            val result = task.result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val source = line.text.trim().replace(Regex("\\s+"), " ")
                if (source.count(Char::isLetter) < 2 || box.height() < 7 || box.width() < 13) {
                    return@mapNotNull null
                }
                DetectedLine(source.take(150), Rect(box))
            }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            done(result.take(24))
        }
    }

    override fun close() = recognizer.close()
}
