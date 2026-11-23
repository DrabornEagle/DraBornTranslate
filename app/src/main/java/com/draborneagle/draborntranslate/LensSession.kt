// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.graphics.Rect
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.Locale

internal data class LensCue(val source: String, val turkish: String, val bounds: Rect)

/**
 * Tracks visible OCR lines independently, so already translated signs are displayed
 * immediately while the local model works through newly discovered text.
 * All callbacks arrive on Android's main thread.
 */
internal class LensSession(
    private val translate: (TranslationInput, (TranslationReply) -> Unit) -> Unit,
    private val show: (List<LensCue>, FrameRegion) -> Unit,
    private val onFailure: (String?) -> Unit,
) : AutoCloseable {
    private val cache = object : LinkedHashMap<String, String>(160, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 160
    }
    private val failedUntil = HashMap<String, Long>()
    private val pending = ArrayDeque<String>()
    private var active = emptyList<DetectedLine>()
    private var region = FrameRegion(0, 0, 1, 1)
    private var inFlight: String? = null
    private var closed = false

    fun read(lines: List<DetectedLine>, frameRegion: FrameRegion) {
        if (closed) return
        region = frameRegion
        active = lines.filter { item ->
            item.source.count(Char::isLetter) >= 2 && item.bounds.width() > 12
        }.take(18)
        val visible = active.map { key(it.source) }.toSet()
        pending.removeIf { key(it) !in visible }
        val now = SystemClock.elapsedRealtime()
        for (line in active) {
            val source = line.source
            val normalized = key(source)
            if (normalized !in cache &&
                normalized != inFlight?.let(::key) &&
                pending.none { key(it) == normalized } &&
                now >= (failedUntil[normalized] ?: 0L)) {
                pending.addLast(source)
            }
        }
        render()
        dispatch()
    }

    fun refresh() {
        if (!closed) render()
    }

    fun reset() {
        if (closed) return
        active = emptyList()
        pending.clear()
        render()
    }

    private fun dispatch() {
        if (closed || inFlight != null) return
        val next = pending.pollFirst() ?: return
        inFlight = next
        translate(TranslationInput(next, readingArea = ReadingArea.GAME_TEXT)) { reply ->
            inFlight = null
            if (!closed) {
                val result = reply.text?.trim()?.takeIf(String::isNotBlank)
                val normalized = key(next)
                if (result != null) {
                    cache[normalized] = result
                    failedUntil.remove(normalized)
                } else {
                    failedUntil[normalized] = SystemClock.elapsedRealtime() + 3500L
                    onFailure(reply.issue)
                }
                render()
                dispatch()
            }
        }
    }

    private fun render() {
        val cues = active.mapNotNull { item ->
            val text = cache[key(item.source)] ?: return@mapNotNull null
            if (text.equals(item.source, ignoreCase = true)) return@mapNotNull null
            LensCue(item.source, text, Rect(item.bounds))
        }
        show(cues, region)
    }

    override fun close() {
        closed = true
        pending.clear()
        active = emptyList()
        failedUntil.clear()
        cache.clear()
    }

    private fun key(input: String): String =
        input.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
