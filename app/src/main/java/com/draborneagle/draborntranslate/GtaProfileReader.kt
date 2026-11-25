// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.gametranslator.gametranslator_mobile.core.ColoredWords
import com.gametranslator.gametranslator_mobile.core.ButtonIcons
import com.gametranslator.gametranslator_mobile.core.ButtonLayout
import com.gametranslator.gametranslator_mobile.core.ButtonLayoutVote
import com.gametranslator.gametranslator_mobile.core.IconWords
import com.gametranslator.gametranslator_mobile.core.OcrWord
import com.gametranslator.gametranslator_mobile.core.PixelImage
import com.gametranslator.gametranslator_mobile.core.TextBox
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor

/** Two readings of the same video frame. The emphasis pass lifts bright, colored HUD text. */
internal class GtaProfileReader(
    private val callbacks: Executor,
    buttonLayout: ButtonLayout = ButtonLayout.PLAYSTATION,
) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val buttons = ButtonLayoutVote(buttonLayout)
    @Volatile private var closed = false

    /** Takes ownership of [bitmap]. Results are delivered on [callbacks]. */
    fun read(bitmap: Bitmap, done: (ProfileReading?) -> Unit) {
        if (closed) {
            bitmap.recycle()
            callbacks.execute { done(null) }
            return
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(callbacks) { task ->
            if (closed) {
                bitmap.recycle()
                done(null)
                return@addOnCompleteListener
            }
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            val source = PixelImage(width, height, pixels)
            val normal = if (task.isSuccessful) lines(task.result, source) else emptyList()
            val enhanced = Bitmap.createBitmap(emphasize(pixels), width, height, Bitmap.Config.ARGB_8888)
            recognizer.process(InputImage.fromBitmap(enhanced, 0)).addOnCompleteListener(callbacks) { second ->
                enhanced.recycle()
                done(
                    if (!closed && (task.isSuccessful || second.isSuccessful)) {
                        Gta6Profile.read(normal, if (second.isSuccessful) lines(second.result, source) else emptyList())
                    } else null,
                )
            }
        }
    }

    private fun lines(result: Text, source: PixelImage): List<ProfileLine> {
        fun position(rect: Rect) = TextBox(
            rect.left / source.width.toDouble(), rect.top / source.height.toDouble(),
            rect.right / source.width.toDouble(), rect.bottom / source.height.toDouble(),
        )
        return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val bounds = line.boundingBox ?: return@mapNotNull null
            val text = line.text.trim().take(320)
            if (bounds.height() < 6 || text.count(Char::isLetter) < 2) return@mapNotNull null
            val words = line.elements.mapNotNull elements@{ element ->
                val box = element.boundingBox ?: return@elements null
                OcrWord(element.text, position(box))
            }
            val color = Gta6Profile.speakerName(text)?.let { name ->
                val wordCount = name.split(' ').size
                words.take(wordCount).firstNotNullOfOrNull { ColoredWords.colorOf(source, it.box) }
            }
            val box = position(bounds)
            val hudText = if (isPromptArea(box)) restoreButtons(text, words, box, source) else text
            ProfileLine(hudText, box, if (isPromptArea(box)) ColoredWords.phrases(words, source) else emptyList(), color)
        }
    }

    private fun isPromptArea(box: TextBox) = box.top > 0.91 || box.left < 0.24 && box.top in 0.22..0.46

    private fun restoreButtons(text: String, words: List<OcrWord>, box: TextBox, image: PixelImage): String {
        val width = image.width.toDouble()
        val height = image.height.toDouble()
        val inline = IconWords.withIcons(text, words, width, height) { place ->
            ButtonIcons.classify(image, place, strict = true, layout = buttons)
        }
        val edges = IconWords.withEdgeIcons(inline, box, width, height) { place ->
            ButtonIcons.classify(image, place, strict = true, layout = buttons)
        }
        return IconWords.withXboxLetters(edges, buttons.current)
    }

    private fun emphasize(pixels: IntArray): IntArray = IntArray(pixels.size) { index ->
        val pixel = pixels[index]
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)
        val luma = (3 * red + 6 * green + blue) / 10
        val chroma = maxOf(red, green, blue) - minOf(red, green, blue)
        val ink = ((luma + chroma / 3 - 82) * 2).coerceIn(0, 255)
        Color.rgb(ink, ink, ink)
    }

    override fun close() {
        closed = true
        recognizer.close()
    }
}
