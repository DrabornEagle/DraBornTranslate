// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.OcrNoise
import com.gametranslator.gametranslator_mobile.core.StabilizerKind
import com.gametranslator.gametranslator_mobile.core.SubtitleStabilizer
import com.gametranslator.gametranslator_mobile.core.TextMatch

internal data class Caption(val source: String, val turkish: String)

/** Tüm çağrılar ana iş parçacığından gelir. Çevirmene yalnızca en yeni bekleyen satır verilir. */
internal class CaptionSession(
    private val translate: (String, (String?) -> Unit) -> Unit,
    private val show: (Caption?) -> Unit,
    private val onFailure: () -> Unit,
    private val stabilizer: SubtitleStabilizer = SubtitleStabilizer(),
) {
    private data class Request(val number: Int, val source: String, val key: String)

    private val cache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
    }
    private var number = 0
    private var waiting: Request? = null
    private var busy = false
    private var closed = false

    fun read(lines: List<String>) {
        if (closed) return
        val text = lines.map(OcrNoise::clean).filter(String::isNotBlank).joinToString("\n")
        val action = stabilizer.feed(text)
        when (action.kind) {
            StabilizerKind.NONE -> Unit
            StabilizerKind.CLEAR -> clearCaption()
            StabilizerKind.TRANSLATE, StabilizerKind.RESTORE -> {
                val source = action.text ?: return
                clearCaption()
                val key = TextMatch.normalize(source)
                val cached = cache[key]
                if (cached != null) {
                    deliver(source, cached)
                } else {
                    waiting = Request(number, source, key)
                    translateNext()
                }
            }
        }
    }

    fun reset() {
        stabilizer.reset()
        clearCaption()
    }

    fun close() {
        closed = true
        reset()
        cache.clear()
    }

    private fun clearCaption() {
        number++
        waiting = null
        show(null)
    }

    private fun translateNext() {
        if (busy || closed) return
        val request = waiting ?: return
        waiting = null
        busy = true
        translate(request.source.replace('\n', ' ')) { result ->
            busy = false
            if (!closed) {
                val turkish = result?.trim()?.takeIf(String::isNotEmpty)
                if (turkish != null) cache[request.key] = turkish
                if (request.number == number) {
                    if (turkish == null) {
                        stabilizer.forgetSent()
                        onFailure()
                    } else {
                        deliver(request.source, turkish)
                    }
                }
                translateNext()
            }
        }
    }

    private fun deliver(source: String, turkish: String) {
        if (stabilizer.shouldSend(source, turkish)) show(Caption(source, turkish))
    }
}
