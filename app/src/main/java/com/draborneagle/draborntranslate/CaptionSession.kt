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
    private val translateKnown: (TranslationInput) -> String? = { null },
    private val readingArea: ReadingArea = ReadingArea.SUBTITLES,
    clock: () -> Long = SystemClock::elapsedRealtime,
    private val stabilizer: SubtitleStabilizer = SubtitleStabilizer(
        clock = clock,
        exactText = true,
        acceptCompletedSentence = false,
        minTextLength = 2,
        isGarbled = ::looksDamaged,
        clearAfterMs = 650,
    ),
) {
    private val cache = object : LinkedHashMap<TranslationInput, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TranslationInput, String>?) = size > 128
    }
    private data class Work(val input: TranslationInput, var canceled: Boolean = false)

    private var inFlight: Work? = null
    private var generation = 0
    private var closed = false
    private var currentSubtitle: TranslationInput? = null
    private var pendingSubtitle: TranslationInput? = null
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
        translateNext()
    }

    private fun readSubtitle(text: String, confident: Boolean) {
        val action = stabilizer.feed(text, confident)
        when (action.kind) {
            StabilizerKind.NONE -> Unit
            StabilizerKind.CLEAR -> clearReadSubtitle()
            StabilizerKind.RESTORE, StabilizerKind.TRANSLATE -> {
                val source = action.text ?: return
                val input = TranslationInput(source, allowPartial = true)
                if (currentSubtitle == input) return
                currentSubtitle = input
                pendingSubtitle = null
                onRead(source)
                val ready = immediate(input)
                if (ready != null) {
                    accept(input, ready, null)
                } else {
                    publish(null)
                    if (inFlight?.let { !it.canceled && it.input == input } != true) pendingSubtitle = input
                }
                cancelObsolete()
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
        translateNext()
    }

    private fun clearReadSubtitle() {
        currentSubtitle = null
        pendingSubtitle = null
        cancelObsolete()
        publish(null)
        onRead(null)
    }

    private fun readScreen(lines: List<String>) {
        val rows = lines.distinctBy(TextMatch::literal).map { TranslationInput(it, ReadingArea.GAME_TEXT) }
        val stable = rows.filter { it in previousRows || it in screenResults }
        previousRows = rows.toSet()
        if (stable == screen) return
        val previousScreen = screen.toSet()
        screen = stable
        screenResults.keys.retainAll(screen.toSet())
        onRead(screen.joinToString("\n", transform = TranslationInput::text).ifBlank { null })
        for (input in screen) {
            if (input in screenResults || input in previousScreen) continue
            val ready = immediate(input) ?: continue
            screenResults[input] = changedTranslation(input, ready)
        }
        cancelObsolete()
        renderScreen()
    }

    fun reset() {
        generation++
        currentSubtitle = null
        pendingSubtitle = null
        stabilizer.reset()
        previousRows = emptySet()
        screen = emptyList()
        screenResults.clear()
        cancelObsolete()
        publish(null)
        onRead(null)
    }

    fun close() {
        closed = true
        reset()
        cache.clear()
    }

    private fun nextInput(): TranslationInput? = when (readingArea) {
        ReadingArea.SUBTITLES -> pendingSubtitle.also { pendingSubtitle = null }
        ReadingArea.GAME_TEXT -> screen.firstOrNull { it !in screenResults }
    }

    private fun immediate(input: TranslationInput): String? =
        cache[input] ?: translateKnown(input)?.also { cache[input] = it }

    private fun stillVisible(input: TranslationInput): Boolean = when (readingArea) {
        ReadingArea.SUBTITLES -> currentSubtitle == input
        ReadingArea.GAME_TEXT -> input in screen
    }

    private fun cancelObsolete() {
        val work = inFlight ?: return
        if (!work.canceled && !stillVisible(work.input)) {
            work.canceled = true
            cancelTranslation()
        }
    }

    private fun translateNext() {
        if (closed || inFlight != null) return
        val input = nextInput() ?: return
        val started = generation
        val work = Work(input)
        inFlight = work
        translate(input) { reply ->
            inFlight = null
            if (started == generation && !closed) {
                val turkish = reply.text?.trim()?.takeIf(String::isNotBlank)
                if (turkish != null && reply.issue == null) cache[input] = turkish
                if (stillVisible(input) && (turkish != null || !work.canceled)) {
                    if (input == pendingSubtitle) pendingSubtitle = null
                    accept(input, turkish, reply.issue)
                }
            }
            translateNext()
        }
    }

    private fun accept(input: TranslationInput, turkish: String?, issue: String?) {
        val changed = changedTranslation(input, turkish)
        if (readingArea == ReadingArea.GAME_TEXT) {
            screenResults[input] = changed
            renderScreen()
            if (turkish == null) onFailure(issue ?: "Bu oyun satırı çevrilemedi.")
        } else {
            publish(changed?.let { Caption(input.text, formatDialogue(it)) })
            onFailure(issue ?: if (turkish == null) "Bu replik çevrilemedi." else null)
        }
    }

    private fun changedTranslation(input: TranslationInput, turkish: String?): String? =
        turkish?.takeUnless { TextMatch.literal(input.text).equals(TextMatch.literal(it), ignoreCase = true) }

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
