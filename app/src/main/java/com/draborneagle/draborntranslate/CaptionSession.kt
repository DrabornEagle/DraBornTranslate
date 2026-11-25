// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.OcrNoise
import com.gametranslator.gametranslator_mobile.core.PackCaptionContinuity
import com.gametranslator.gametranslator_mobile.core.SpeakerPrefix
import com.gametranslator.gametranslator_mobile.core.StabilizerKind
import com.gametranslator.gametranslator_mobile.core.SubtitleStabilizer
import com.gametranslator.gametranslator_mobile.core.TextMatch
import com.gametranslator.gametranslator_mobile.core.TextBox

internal data class CaptionPosition(val box: TextBox, val speakerColor: String?)
internal data class Caption(
    val source: String,
    val turkish: String,
    val position: CaptionPosition? = null,
    val packId: String? = null,
)

/** Tüm çağrılar ana iş parçacığından gelir. Çevirmene yalnızca en yeni bekleyen satır verilir. */
internal class CaptionSession(
    private val translate: (TranslationInput, (TranslationReply) -> Unit) -> Unit,
    private val show: (Caption?) -> Unit,
    private val onFailure: (String?) -> Unit,
    private val readingArea: ReadingArea = ReadingArea.SUBTITLES,
    private val stabilizer: SubtitleStabilizer = SubtitleStabilizer(
        stableFrames = if (readingArea == ReadingArea.GAME_TEXT) 1 else 2,
        exactText = readingArea == ReadingArea.GAME_TEXT,
        acceptCompletedSentence = false,
        minTextLength = 2,
    ),
    private val contextClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val lookup: ((TranslationInput, (TranslationReply?) -> Unit) -> Unit)? = null,
) {
    private data class Request(
        val number: Int,
        val source: String,
        val input: TranslationInput,
        val position: CaptionPosition?,
        val allowFallback: Boolean,
    )

    private val cache = object : LinkedHashMap<TranslationInput, TranslationReply>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TranslationInput, TranslationReply>?): Boolean = size > 128
    }
    private var number = 0
    private var waiting: Request? = null
    private var busy = false
    private var closed = false
    private var previousGameLines = emptySet<String>()
    private var previousSubtitle: TranslationInput? = null
    private var previousSubtitleAt = 0L
    private var shownPack: TranslationInput? = null
    private var shownSource: String? = null

    fun read(lines: List<String>, position: CaptionPosition? = null, allowFallback: Boolean = true) {
        if (closed) return
        val clean = if (readingArea == ReadingArea.GAME_TEXT) steadyGameText(lines) else lines.map { line ->
            val (prefix, body) = SpeakerPrefix.split(line)
            val cleaned = OcrNoise.clean(body)
            if (cleaned.isBlank()) "" else prefix + cleaned
        }
        val text = clean.filter(String::isNotBlank).joinToString("\n")
        val action = stabilizer.feed(text)
        when (action.kind) {
            StabilizerKind.NONE -> {
                if (text == previousSubtitle?.text) previousSubtitleAt = contextClock()
            }
            StabilizerKind.CLEAR -> {
                shownPack = null
                clearCaption()
            }
            StabilizerKind.TRANSLATE, StabilizerKind.RESTORE -> {
                val source = action.text ?: return
                clearCaption(hide = readingArea == ReadingArea.GAME_TEXT)
                val input = subtitleInput(source, action.kind)
                val cached = cache[input]
                if (cached?.fromPack == true) {
                    deliver(source, cached, position)
                } else {
                    val request = Request(number, source, input, position, allowFallback)
                    val find = lookup
                    if (find == null || cached != null) {
                        onPackMiss(request, cached)
                    } else find(input) { reply ->
                        if (!closed && number == request.number) {
                            if (reply == null) {
                                onPackMiss(request, null)
                            }
                            else {
                                cache[input] = reply
                                deliver(source, reply, position)
                            }
                        }
                    }
                }
            }
        }
    }

    fun reset() {
        previousGameLines = emptySet()
        previousSubtitle = null
        shownPack = null
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

    private fun subtitleInput(source: String, kind: StabilizerKind): TranslationInput {
        if (readingArea == ReadingArea.GAME_TEXT) return TranslationInput(source, readingArea)
        val now = contextClock()
        val previous = previousSubtitle?.takeIf { now - previousSubtitleAt <= CONTEXT_WINDOW_MS }
        val restored = if (kind == StabilizerKind.RESTORE) cache.keys.lastOrNull { it.text == source } else null
        val input = restored ?: previous?.takeIf { it.text == source } ?: TranslationInput(
            text = source,
            previousSubtitle = previous?.text?.takeIf { DialogueContext.mightNeedPrevious(source) },
        )
        previousSubtitle = input
        previousSubtitleAt = now
        return input
    }

    private fun clearCaption(hide: Boolean = true) {
        number++
        waiting = null
        if (hide) {
            shownPack = null
            shownSource = null
            show(null)
        }
    }

    private fun enqueue(request: Request) {
        waiting = request
        translateNext()
    }

    private fun keepPackedReading(input: TranslationInput): Boolean {
        val packed = shownPack ?: return false
        if (!PackCaptionContinuity.isReread(input.text, packed.text)) return false
        previousSubtitle = packed
        previousSubtitleAt = contextClock()
        return true
    }

    private fun onPackMiss(request: Request, cached: TranslationReply?) {
        if (keepPackedReading(request.input)) return
        when {
            !request.allowFallback -> stabilizer.forgetSent(shownSource)
            cached != null -> deliver(request.source, cached, request.position)
            else -> enqueue(request)
        }
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
                        shownPack = null
                        shownSource = null
                        show(null)
                        onFailure(reply.issue)
                    } else {
                        deliver(request.source, reply, request.position)
                    }
                }
                translateNext()
            }
        }
    }

    private fun deliver(source: String, reply: TranslationReply, position: CaptionPosition?) {
        val turkish = reply.text?.trim() ?: return
        val changed = if (readingArea == ReadingArea.GAME_TEXT) TextMatch.normalize(source) != TextMatch.normalize(turkish)
        else stabilizer.shouldSend(source, turkish)
        val localized = if (position != null) Gta6Profile.localizeSpeaker(turkish) else turkish
        val displayed = if (readingArea == ReadingArea.SUBTITLES) formatDialogue(localized) else localized
        shownPack = if (changed && reply.fromPack && readingArea == ReadingArea.SUBTITLES) previousSubtitle else null
        shownSource = source.takeIf { changed }
        show(if (changed) Caption(source, displayed, position, reply.packId) else null)
    }

    private companion object {
        // Keep nearby dialogue through a normal pause, then discard the old topic.
        const val CONTEXT_WINDOW_MS = 6_000L
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
