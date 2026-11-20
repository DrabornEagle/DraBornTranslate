// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.OcrNoise
import com.gametranslator.gametranslator_mobile.core.StabilizerKind
import com.gametranslator.gametranslator_mobile.core.SubtitleStabilizer
import com.gametranslator.gametranslator_mobile.core.TextMatch

internal data class Caption(val source: String, val turkish: String, val googleAttribution: Boolean = true)

/** Tüm çağrılar ana iş parçacığından gelir. Çevirmene yalnızca en yeni bekleyen satır verilir. */
internal class CaptionSession(
    private val translate: (TranslationInput, (TranslationReply) -> Unit) -> Unit,
    private val show: (Caption?) -> Unit,
    private val onFailure: (String?) -> Unit,
    private val readingArea: ReadingArea = ReadingArea.SUBTITLES,
    private val engine: TranslationEngine = TranslationEngine.OFFLINE,
    private val stabilizer: SubtitleStabilizer = SubtitleStabilizer(
        stableFrames = if (readingArea == ReadingArea.GAME_TEXT) 1 else 2,
        exactText = readingArea == ReadingArea.GAME_TEXT,
        acceptCompletedSentence = false,
        minTextLength = 2,
    ),
) {
    private data class Request(
        val number: Int,
        val source: String,
        val input: TranslationInput,
        val key: TranslationInput,
    )

    private val cache = object : LinkedHashMap<TranslationInput, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TranslationInput, String>?): Boolean = size > 128
    }
    private val recent = ArrayDeque<TranslationInput>()
    private var number = 0
    private var waiting: Request? = null
    private var busy = false
    private var closed = false
    private var previousGameLines = emptySet<String>()

    fun read(lines: List<String>) {
        if (closed) return
        val clean = if (readingArea == ReadingArea.GAME_TEXT) steadyGameText(lines) else lines.map(OcrNoise::clean)
        val text = clean.filter(String::isNotBlank).joinToString("\n")
        val action = stabilizer.feed(text)
        when (action.kind) {
            StabilizerKind.NONE -> Unit
            StabilizerKind.CLEAR -> clearCaption()
            StabilizerKind.TRANSLATE, StabilizerKind.RESTORE -> {
                val source = action.text ?: return
                clearCaption(hide = engine == TranslationEngine.OFFLINE || readingArea == ReadingArea.GAME_TEXT)
                val input = translationInput(source, action.kind == StabilizerKind.RESTORE)
                val key = if (engine == TranslationEngine.NATURAL) input
                else input.copy(text = TextMatch.normalize(input.text))
                val cached = cache[key]
                if (cached != null) {
                    deliver(source, cached)
                } else {
                    waiting = Request(number, source, input, key)
                    translateNext()
                }
            }
        }
    }

    fun reset() {
        previousGameLines = emptySet()
        recent.clear()
        stabilizer.reset()
        clearCaption()
    }

    fun close() {
        closed = true
        reset()
        cache.clear()
    }

    private fun steadyGameText(lines: List<String>): List<String> {
        val current = lines.associateBy(TextMatch::normalize).filterKeys { it.length >= 2 }
        val steady = current.filterKeys(previousGameLines::contains).values.take(6)
        previousGameLines = current.keys
        return steady
    }

    private fun translationInput(source: String, restore: Boolean): TranslationInput {
        if (readingArea == ReadingArea.GAME_TEXT) return TranslationInput(source, readingArea = readingArea)
        val text = source.replace('\n', ' ')
        if (engine == TranslationEngine.OFFLINE) return TranslationInput(text)

        if (restore) recent.lastOrNull { it.text == text }?.let { return it }
        if (recent.lastOrNull()?.text == text) return recent.last()
        val input = TranslationInput(text, recent.takeLast(3).map { it.text })
        recent.addLast(input)
        if (recent.size > 4) recent.removeFirst()
        return input
    }

    private fun clearCaption(hide: Boolean = true) {
        number++
        waiting = null
        if (hide) show(null)
    }

    private fun translateNext() {
        if (busy || closed) return
        val request = waiting ?: return
        waiting = null
        busy = true
        translate(request.input) { reply ->
            busy = false
            if (!closed) {
                val turkish = reply.text?.trim()?.takeIf(String::isNotEmpty)
                if (turkish != null) cache[request.key] = turkish
                if (request.number == number) {
                    if (turkish == null) {
                        stabilizer.forgetSent()
                        show(null)
                        onFailure(reply.issue)
                    } else {
                        deliver(request.source, turkish)
                    }
                }
                translateNext()
            }
        }
    }

    private fun deliver(source: String, turkish: String) {
        val changed = if (readingArea == ReadingArea.GAME_TEXT) TextMatch.normalize(source) != TextMatch.normalize(turkish)
        else stabilizer.shouldSend(source, turkish)
        val displayed = if (readingArea == ReadingArea.SUBTITLES) formatDialogue(turkish) else turkish
        if (changed) show(Caption(source, displayed, googleAttribution = engine == TranslationEngine.OFFLINE))
        else show(null)
    }
}

private val subtitleWhitespace = Regex("[\\s\\u00a0]+")

internal fun formatDialogue(text: String): String {
    val line = subtitleWhitespace.replace(text.trim(), " ")
    if (line.length <= 43) return line
    val midpoint = line.length / 2
    val split = line.indices.filter { line[it] == ' ' }.minByOrNull { at ->
        val punctuation = at > 0 && line[at - 1] in ".?!…"
        kotlin.math.abs(at - midpoint) - if (punctuation) 8 else 0
    } ?: return line
    return line.substring(0, split) + "\n" + line.substring(split + 1)
}
