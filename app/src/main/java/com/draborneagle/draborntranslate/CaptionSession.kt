// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.os.SystemClock
import com.gametranslator.gametranslator_mobile.core.StabilizerKind
import com.gametranslator.gametranslator_mobile.core.SubtitleStabilizer
import com.gametranslator.gametranslator_mobile.core.TextMatch

internal data class Caption(val source: String, val turkish: String)

/** The OCR reader and translator deliver their results on the main thread. */
internal class CaptionSession(
    private val translate: (TranslationInput, (TranslationReply) -> Unit) -> Unit,
    private val show: (Caption?) -> Unit,
    private val onFailure: (String?) -> Unit,
    private val onRead: (String?) -> Unit = {},
    private val cancelTranslation: () -> Unit = {},
    private val readingArea: ReadingArea = ReadingArea.SUBTITLES,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val stabilizer: SubtitleStabilizer = SubtitleStabilizer(
        clock = clock,
        exactText = true,
        acceptCompletedSentence = false,
        minTextLength = 2,
        isGarbled = ::looksDamaged,
        clearAfterMs = 1400,
    ),
) {
    private val cache = object : LinkedHashMap<TranslationInput, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TranslationInput, String>?) = size > 128
    }
    private val dialogue = ArrayDeque<TranslationInput>()
    private var inFlight: TranslationInput? = null
    private var generation = 0
    private var closed = false
    private var currentSubtitle: TranslationInput? = null
    private var readySubtitle: Pair<Caption, String?>? = null
    private var visibleUntil = 0L
    private var shown: Caption? = null
    private var previousRows = emptySet<TranslationInput>()
    private var screen = emptyList<TranslationInput>()
    private val screenResults = mutableMapOf<TranslationInput, String?>()

    fun read(lines: List<String>, confident: Boolean = false) {
        if (closed) return
        val clean = lines.map(::cleanOcrLine).filter { it.isNotBlank() && !looksDamaged(it) }
        val output = shown?.turkish?.lines().orEmpty().map(TextMatch::literal).toSet()
        val source = clean.filterNot { TextMatch.literal(it) in output }

        if (readingArea == ReadingArea.GAME_TEXT) readScreen(source)
        else readSubtitle(source.joinToString("\n"), confident && source.size == lines.size)
        tick()
    }

    fun tick() {
        if (closed) return
        presentSubtitle()
        translateNext()
    }

    /** Retain every confirmed short cue long enough for the browser panel to read it. */
    private fun presentSubtitle() {
        if (readingArea != ReadingArea.SUBTITLES || clock() < visibleUntil) return
        val ready = readySubtitle
        if (ready != null) {
            readySubtitle = null
            val (caption, issue) = ready
            visibleUntil = clock() + (caption.turkish.length * 27L).coerceIn(950L, 2200L)
            publish(caption)
            onFailure(issue)
        } else if (inFlight == null && dialogue.isEmpty() && shown?.source != currentSubtitle?.text) {
            publish(null)
        }
    }

    private fun readSubtitle(text: String, confident: Boolean) {
        val action = stabilizer.feed(text, confident)
        when (action.kind) {
            StabilizerKind.NONE -> Unit
            StabilizerKind.CLEAR -> clearReadSubtitle()
            StabilizerKind.RESTORE, StabilizerKind.TRANSLATE -> {
                val source = action.text ?: return
                val input = TranslationInput(source, allowPartial = true)
                currentSubtitle = input
                onRead(source)
                if (shown?.source == source && dialogue.isEmpty() && inFlight == null && readySubtitle == null) return
                if (input == inFlight || input.text == readySubtitle?.first?.source || input in dialogue) return
                // A sustained backlog eventually becomes too old to follow the game.
                if (dialogue.size == MAX_PENDING_SUBTITLES) dialogue.removeFirst()
                dialogue.addLast(input)
            }
        }
    }

    fun blankFrame() {
        if (closed) return
        if (readingArea == ReadingArea.GAME_TEXT) {
            readScreen(emptyList())
        } else {
            stabilizer.reset()
            clearReadSubtitle()
        }
        tick()
    }

    private fun clearReadSubtitle() {
        currentSubtitle = null
        onRead(null)
    }

    private fun readScreen(lines: List<String>) {
        val rows = lines.distinctBy(TextMatch::literal).map { TranslationInput(it, ReadingArea.GAME_TEXT) }
        val stable = rows.filter { it in previousRows || it in screenResults }
        previousRows = rows.toSet()
        if (stable == screen) return
        screen = stable
        screenResults.keys.retainAll(screen.toSet())
        onRead(screen.joinToString("\n", transform = TranslationInput::text).ifBlank { null })
        renderScreen()
    }

    fun reset() {
        generation++
        dialogue.clear()
        currentSubtitle = null
        readySubtitle = null
        visibleUntil = 0L
        stabilizer.reset()
        previousRows = emptySet()
        screen = emptyList()
        screenResults.clear()
        if (inFlight != null) cancelTranslation()
        publish(null)
        onRead(null)
    }

    fun close() {
        closed = true
        reset()
        cache.clear()
    }

    private fun nextInput(): TranslationInput? = when (readingArea) {
        ReadingArea.SUBTITLES -> dialogue.removeFirstOrNull()
        ReadingArea.GAME_TEXT -> screen.firstOrNull { it !in screenResults }
    }

    private fun translateNext() {
        if (closed || inFlight != null || readySubtitle != null) return
        while (true) {
            val input = nextInput() ?: return
            val cached = cache[input]
            if (cached == null) {
                val started = generation
                inFlight = input
                translate(input) { reply ->
                    inFlight = null
                    if (started == generation && !closed) {
                        val turkish = reply.text?.trim()?.takeIf(String::isNotBlank)
                        if (turkish != null && reply.issue == null) cache[input] = turkish
                        accept(input, turkish, reply.issue)
                    }
                    tick()
                }
                return
            }
            accept(input, cached, null)
            presentSubtitle()
            if (readySubtitle != null) return
        }
    }

    private fun accept(input: TranslationInput, turkish: String?, issue: String?) {
        val changed = turkish?.takeUnless { TextMatch.literal(input.text).equals(TextMatch.literal(it), ignoreCase = true) }
        if (readingArea == ReadingArea.GAME_TEXT) {
            if (input !in screen) return
            screenResults[input] = changed
            renderScreen()
            if (turkish == null) onFailure(issue ?: "Bu oyun satırı çevrilemedi.")
        } else if (changed != null) {
            readySubtitle = Caption(input.text, formatDialogue(changed)) to issue
        } else if (turkish == null && (currentSubtitle == input || dialogue.isEmpty())) {
            onFailure(issue ?: "Bu replik çevrilemedi.")
        }
    }

    private fun renderScreen() {
        val translated = screen.mapNotNull { input -> screenResults[input]?.let { input.text to it } }
        publish(translated.takeIf { it.isNotEmpty() }?.let { rows ->
            Caption(rows.joinToString("\n") { it.first }, rows.joinToString("\n") { it.second })
        })
    }

    private fun publish(caption: Caption?) {
        if (shown == caption) return
        shown = caption
        show(caption)
    }

    private companion object {
        const val MAX_PENDING_SUBTITLES = 16
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
