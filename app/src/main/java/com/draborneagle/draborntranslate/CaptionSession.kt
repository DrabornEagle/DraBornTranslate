// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.StabilizerKind
import com.gametranslator.gametranslator_mobile.core.SubtitleStabilizer
import com.gametranslator.gametranslator_mobile.core.TextMatch

internal data class Caption(val source: String, val turkish: String)

/** Tüm çağrılar ana iş parçacığından gelir. Çevirmene yalnızca en yeni bekleyen satır verilir. */
internal class CaptionSession(
    private val translate: (TranslationInput, (TranslationReply) -> Unit) -> Unit,
    private val show: (Caption?) -> Unit,
    private val onFailure: (String?) -> Unit,
    private val readingArea: ReadingArea = ReadingArea.SUBTITLES,
    private val stabilizer: SubtitleStabilizer = SubtitleStabilizer(
        stableFrames = if (readingArea == ReadingArea.GAME_TEXT) 1 else 2,
        exactText = true,
        acceptCompletedSentence = false,
        minTextLength = 2,
        isGarbled = ::looksDamaged,
    ),
) {
    private data class Request(
        val number: Int,
        val source: String,
        val input: TranslationInput,
    )

    private val cache = object : LinkedHashMap<TranslationInput, TranslationReply>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TranslationInput, TranslationReply>?): Boolean = size > 128
    }
    private var number = 0
    private var waiting: Request? = null
    private var busy = false
    private var closed = false
    private var previousGameLines = emptySet<String>()

    fun read(lines: List<String>) {
        if (closed) return
        val clean = lines.map(::cleanOcrLine).filter(String::isNotBlank)
        val accepted = if (readingArea == ReadingArea.GAME_TEXT) steadyGameText(clean) else clean
        val text = accepted.joinToString("\n")
        val action = stabilizer.feed(text)
        when (action.kind) {
            StabilizerKind.NONE -> Unit
            StabilizerKind.CLEAR -> clearCaption()
            StabilizerKind.TRANSLATE, StabilizerKind.RESTORE -> {
                val source = action.text ?: return
                clearCaption(hide = readingArea == ReadingArea.GAME_TEXT)
                val input = TranslationInput(source, readingArea)
                val cached = cache[input]
                if (cached != null) {
                    deliver(source, cached)
                } else {
                    waiting = Request(number, source, input)
                    translateNext()
                }
            }
        }
    }

    fun reset() {
        previousGameLines = emptySet()
        stabilizer.reset()
        clearCaption()
    }

    fun close() {
        closed = true
        reset()
        cache.clear()
    }

    private fun steadyGameText(lines: List<String>): List<String> {
        val current = lines.associateBy(TextMatch::literal).filterKeys { it.length >= 2 }
        val steady = current.filterKeys(previousGameLines::contains).values.take(6)
        previousGameLines = current.keys
        return steady
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
                if (turkish != null) cache[request.input] = reply
                if (request.number == number) {
                    if (turkish == null) {
                        stabilizer.forgetSent()
                        show(null)
                        onFailure(reply.issue)
                    } else {
                        deliver(request.source, reply)
                    }
                }
                translateNext()
            }
        }
    }

    private fun deliver(source: String, reply: TranslationReply) {
        val turkish = reply.text?.trim() ?: return
        val changed = !TextMatch.literal(source).equals(TextMatch.literal(turkish), ignoreCase = true)
        val displayed = if (readingArea == ReadingArea.SUBTITLES) formatDialogue(turkish) else turkish
        if (changed) show(Caption(source, displayed))
        else show(null)
    }
}

private val subtitleWhitespace = Regex("[\\s\\u00a0]+")

internal fun formatDialogue(text: String): String {
    if (text.contains('\n')) return text.trim()
    val line = subtitleWhitespace.replace(text.trim(), " ")
    if (line.length <= 43) return line
    val midpoint = line.length / 2
    val split = line.indices.filter { line[it] == ' ' }.minByOrNull { at ->
        val punctuation = at > 0 && line[at - 1] in ".?!…"
        kotlin.math.abs(at - midpoint) - if (punctuation) 8 else 0
    } ?: return line
    return line.substring(0, split) + "\n" + line.substring(split + 1)
}
