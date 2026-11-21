// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

internal interface CaptionTranslator : AutoCloseable {
    fun prepare(done: (Boolean) -> Unit) = done(true)
    fun translate(input: TranslationInput, done: (TranslationReply) -> Unit)
    fun cancelCurrent() {}
}

internal class SubtitleReader(private val readingArea: ReadingArea = ReadingArea.SUBTITLES) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(bitmap: Bitmap, release: (Bitmap) -> Unit = Bitmap::recycle, done: (TextReading?) -> Unit) {
        val width = bitmap.width
        val height = bitmap.height
        val recognition = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
        } catch (_: RuntimeException) {
            release(bitmap)
            done(null)
            return
        }
        recognition.addOnCompleteListener { task ->
            release(bitmap)
            if (!task.isSuccessful) {
                done(null)
                return@addOnCompleteListener
            }
            val lines = task.result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                if (line.text.count(Char::isLetter) < 2 || box.height() < 6) return@mapNotNull null
                RecognizedLine(line.text.trim(), box.left, box.top, box.right, box.bottom, line.confidence)
            }
            if (readingArea == ReadingArea.GAME_TEXT) {
                val readable = lines.filter { it.confidence == 0f || it.confidence >= 0.35f }
                done(TextReading(joinSubtitleFragments(readable).sortedWith(compareBy({ it.top / 10 }, { it.left }))
                    .map { it.text }
                    .distinct()))
                return@addOnCompleteListener
            }
            done(selectSubtitleLines(lines, width, height))
        }
    }

    override fun close() = recognizer.close()
}
