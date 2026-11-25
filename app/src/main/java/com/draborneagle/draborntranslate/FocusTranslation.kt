// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.gametranslator.gametranslator_mobile.core.TextMatch
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.roundToInt

internal data class FocusRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun pixels(width: Int, height: Int): Rect {
        val l = (left * width).roundToInt().coerceIn(0, width - 1)
        val t = (top * height).roundToInt().coerceIn(0, height - 1)
        return Rect(l, t, (right * width).roundToInt().coerceIn(l + 1, width), (bottom * height).roundToInt().coerceIn(t + 1, height))
    }

    fun save(context: Context) {
        context.getSharedPreferences("focus", Context.MODE_PRIVATE).edit()
            .putFloat("left", left).putFloat("top", top).putFloat("right", right).putFloat("bottom", bottom).apply()
    }

    companion object {
        val full = FocusRegion(0f, 0f, 1f, 1f)
        fun saved(context: Context): FocusRegion? =
            context.getSharedPreferences("focus", Context.MODE_PRIVATE).let { prefs ->
                if (!prefs.contains("left")) null else FocusRegion(
                    prefs.getFloat("left", 0f), prefs.getFloat("top", 0f),
                    prefs.getFloat("right", 1f), prefs.getFloat("bottom", 1f),
                )
            }
    }
}

internal data class FocusText(val box: Rect, val source: String, val turkish: String?, val background: Int)

internal class FocusTranslator(context: Context) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = GameTranslatorEngine(context)
    private val cache = object : LinkedHashMap<String, String>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 48
    }
    private var closed = false

    fun read(frame: Bitmap, region: FocusRegion, done: (List<FocusText>?) -> Unit) {
        val crop = region.pixels(frame.width, frame.height)
        val cropBitmap = Bitmap.createBitmap(frame, crop.left, crop.top, crop.width(), crop.height())
            .let { if (it === frame) checkNotNull(it.copy(Bitmap.Config.ARGB_8888, false)) else it }
        val factor = if (maxOf(cropBitmap.width, cropBitmap.height) < 1100) 2 else 1
        val input = if (factor == 1) cropBitmap else Bitmap.createScaledBitmap(
            cropBitmap, cropBitmap.width * factor, cropBitmap.height * factor, true,
        )
        recognizer.process(InputImage.fromBitmap(input, 0)).addOnCompleteListener { task ->
            try {
                if (closed) return@addOnCompleteListener
                if (!task.isSuccessful) {
                    done(null)
                    return@addOnCompleteListener
                }
                val candidates = task.result.textBlocks.mapNotNull { block ->
                    val raw = block.boundingBox ?: return@mapNotNull null
                    val source = block.lines.joinToString(" ") { it.text.trim() }.take(500)
                    if (source.count(Char::isLetter) < 2 || raw.height() < 6 * factor) return@mapNotNull null
                    val box = Rect(crop.left + raw.left / factor, crop.top + raw.top / factor,
                        crop.left + raw.right / factor, crop.top + raw.bottom / factor)
                    if (!box.intersect(crop) || box.width() <= 1 || box.height() <= 1) return@mapNotNull null
                    val sampleX = (raw.left - 4 * factor).coerceIn(0, input.width - 1)
                    val sampleY = (raw.top - 4 * factor).coerceIn(0, input.height - 1)
                    FocusText(box, source, null, input.getPixel(sampleX, sampleY))
                }.sortedWith(compareBy({ it.box.top / 12 }, { it.box.left })).take(32)
                translate(candidates, 0, mutableListOf(), done)
            } finally {
                if (input !== cropBitmap) input.recycle()
                cropBitmap.recycle()
            }
        }
    }

    private fun translate(items: List<FocusText>, index: Int, result: MutableList<FocusText>, done: (List<FocusText>?) -> Unit) {
        if (closed) return
        val line = items.getOrNull(index)
        if (line == null) {
            done(result.toList())
            return
        }
        val cached = cache[line.source]
        if (cached != null) {
            result.add(line.copy(turkish = cached))
            translate(items, index + 1, result, done)
            return
        }
        translator.translate(TranslationInput(line.source, ReadingArea.GAME_TEXT)) { reply ->
            val text = reply.text?.trim()
                ?.takeIf { it.isNotEmpty() && TextMatch.normalize(it) != TextMatch.normalize(line.source) }
            if (text != null) cache[line.source] = text
            result.add(line.copy(turkish = text))
            translate(items, index + 1, result, done)
        }
    }

    override fun close() {
        closed = true
        recognizer.close()
        translator.close()
    }
}

/** Owned by the main thread. Only the latest reduced frame stays in memory. */
internal object LiveFocusFrames {
    data class Snapshot(val bitmap: Bitmap, val source: Source, val observedAt: Long)

    private var frame: Bitmap? = null
    private var source = Source.PORTAL
    private var observedAt = 0L
    private val observers = mutableSetOf<() -> Unit>()
    var revision: Long = 0
        private set

    fun replace(bitmap: Bitmap, origin: Source, timeMs: Long = SystemClock.elapsedRealtime()) {
        frame?.recycle()
        frame = bitmap
        source = origin
        observedAt = timeMs
        revision++
        observers.toList().forEach { it() }
    }

    fun snapshot(): Bitmap? = frame?.copy(Bitmap.Config.ARGB_8888, false)
    fun sample(): Snapshot? = snapshot()?.let { Snapshot(it, source, observedAt) }
    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun remove(observer: () -> Unit) { observers.remove(observer) }

    fun clear() {
        if (frame == null) return
        frame?.recycle()
        frame = null
        revision++
    }
}

internal class LiveFocus(context: Context, private val displayOnAndroid: Boolean) : AutoCloseable {
    private val app = context.applicationContext
    private val reader = FocusTranslator(context)
    private val overlay = if (displayOnAndroid) FocusOverlay(context) else null
    private var lastFrameTime = 0L
    private var request = 0
    private var busy = false
    private var closed = false

    fun frame(bitmap: Bitmap) {
        if (closed) {
            bitmap.recycle()
            return
        }
        val region = FocusRegion.saved(app)
        if (region == null || !displayOnAndroid) return
        val now = SystemClock.elapsedRealtime()
        if (busy || now - lastFrameTime < 1300) return
        lastFrameTime = now
        val size = bitmap.width to bitmap.height
        busy = true
        val generation = request
        reader.read(bitmap, region) { result ->
            busy = false
            if (!closed && generation == request && FocusRegion.saved(app) == region) {
                runCatching { overlay?.show(size.first, size.second, result.orEmpty()) }
            }
        }
    }

    fun clear() {
        request++
        overlay?.close()
    }

    override fun close() {
        closed = true
        clear()
        reader.close()
    }
}
