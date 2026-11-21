// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.os.SystemClock
import com.gametranslator.gametranslator_mobile.core.StabilizerKind
import com.gametranslator.gametranslator_mobile.core.SubtitleStabilizer
import com.gametranslator.gametranslator_mobile.core.TextMatch
import java.util.Locale

internal data class Caption(val source: String, val turkish: String)

/** The original subtitle controls the lifetime of its translation. All callbacks run on the main thread. */
internal class CaptionSession(
    private val translate: (TranslationInput, (TranslationReply) -> Unit) -> Unit,
    private val show: (Caption?) -> Unit,
    private val onFailure: (String?) -> Unit,
    private val onRead: (String?) -> Unit = {},
    private val cancelTranslation: () -> Unit = {},
    private val translateKnown: (TranslationInput) -> String? = { null },
    private val refine: (TranslationInput, (TranslationReply) -> Unit) -> Boolean = { _, _ -> false },
    private val readingArea: ReadingArea = ReadingArea.SUBTITLES,
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val stabilizer = SubtitleStabilizer(
        clock = clock,
        exactText = true,
        acceptCompletedSentence = false,
        minTextLength = 2,
        isGarbled = { false }, // The unmodified OCR text is checked before feeding its comparison key.
        clearAfterMs = 170,
    )
    private data class Cue(
        val input: TranslationInput,
        val key: TranslationInput,
        var primaryFinished: Boolean = false,
        var refinementStarted: Boolean = false,
    )
    private data class Work(val key: TranslationInput, val generation: Int)

    private val cache = object : LinkedHashMap<TranslationInput, TranslationReply>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TranslationInput, TranslationReply>?) = size > 128
    }
    private var inFlight: Work? = null
    private var generation = 0
    private var closed = false
    private var currentSubtitle: Cue? = null
    private var pendingSubtitle: Cue? = null
    private var shown: Caption? = null
    private var previousRows = emptySet<TranslationInput>()
    private var screen = emptyList<TranslationInput>()
    private val screenResults = mutableMapOf<TranslationInput, String?>()

    fun read(lines: List<String>, confident: Boolean = false) {
        if (closed) return
        val clean = lines.map(::cleanOcrLine).filter { it.isNotBlank() && !looksDamaged(it) }
        if (readingArea == ReadingArea.GAME_TEXT) {
            val output = shown?.turkish?.lines().orEmpty().map(TextMatch::literal).toSet()
            readScreen(clean.filterNot { TextMatch.literal(it) in output })
        } else {
            readSubtitle(clean.joinToString("\n"), confident && clean.size == lines.size)
        }
        translateNext()
        refineVisibleSubtitle()
    }

    private fun readSubtitle(text: String, confident: Boolean) {
        val input = TranslationInput(text, allowPartial = true)
        val key = cacheKey(input)
        when (stabilizer.feed(key.text, confident).kind) {
            StabilizerKind.NONE -> Unit
            StabilizerKind.CLEAR -> clearReadSubtitle()
            StabilizerKind.RESTORE, StabilizerKind.TRANSLATE -> {
                if (currentSubtitle?.key == key) return
                val cue = Cue(input, key)
                currentSubtitle = cue
                pendingSubtitle = null
                onRead(text)
                val ready = immediate(input, key)
                if (ready != null) {
                    cue.primaryFinished = true
                    displaySubtitle(cue, ready)
                } else {
                    publish(null)
                    if (inFlight != Work(key, generation)) pendingSubtitle = cue
                }
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
    }

    private fun clearReadSubtitle() {
        currentSubtitle = null
        pendingSubtitle = null
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
            val ready = immediate(input, input) ?: continue
            screenResults[input] = changedTranslation(input, ready.text)
        }
        renderScreen()
    }

    fun reset() {
        generation++
        // Ignore old replies, but allow the worker to drain before submitting new text.
        if (inFlight != null || currentSubtitle?.refinementStarted == true) cancelTranslation()
        currentSubtitle = null
        pendingSubtitle = null
        stabilizer.reset()
        previousRows = emptySet()
        screen = emptyList()
        screenResults.clear()
        publish(null)
        onRead(null)
    }

    fun close() {
        closed = true
        reset()
        cache.clear()
    }

    private fun cacheKey(input: TranslationInput): TranslationInput =
        if (input.readingArea == ReadingArea.SUBTITLES) {
            input.copy(text = TextMatch.literal(input.text).lowercase(Locale.ROOT).trimEnd('.', '!', '…'))
        } else input

    private fun immediate(input: TranslationInput, key: TranslationInput): TranslationReply? =
        cache[key] ?: translateKnown(input)?.let {
            TranslationReply(it, refined = true).also { reply -> cache[key] = reply }
        }

    private fun remember(key: TranslationInput, reply: TranslationReply) {
        if (changedTranslation(key, reply.text) == null || reply.issue != null) return
        if (cache[key]?.refined != true || reply.refined) cache[key] = reply
    }

    private fun translateNext() {
        if (closed || inFlight != null) return
        val input = when (readingArea) {
            ReadingArea.SUBTITLES -> pendingSubtitle?.input.also { pendingSubtitle = null }
            ReadingArea.GAME_TEXT -> screen.firstOrNull { it !in screenResults }
        } ?: return
        val key = cacheKey(input)
        val work = Work(key, generation)
        inFlight = work
        translate(input) { reply ->
            inFlight = null
            if (work.generation == generation && !closed) {
                remember(key, reply)
                if (readingArea == ReadingArea.GAME_TEXT) {
                    if (input in screen) {
                        screenResults[input] = changedTranslation(input, reply.text)
                        renderScreen()
                        if (reply.text == null) onFailure(reply.issue ?: "Bu oyun satırı çevrilemedi.")
                    }
                } else {
                    currentSubtitle?.takeIf { it.key == key }?.let { cue ->
                        cue.primaryFinished = true
                        displaySubtitle(cue, cache[key] ?: reply)
                    }
                }
            }
            translateNext()
            refineVisibleSubtitle()
        }
    }

    private fun refineVisibleSubtitle() {
        val cue = currentSubtitle ?: return
        if (closed || !cue.primaryFinished || cue.refinementStarted || cache[cue.key]?.refined == true) return
        val started = generation
        cue.refinementStarted = refine(cue.input) { reply ->
            if (started != generation || closed || reply.issue != null ||
                changedTranslation(cue.input, reply.text) == null) return@refine
            val refined = reply.copy(refined = true)
            remember(cue.key, refined)
            currentSubtitle?.takeIf { it.key == cue.key }?.let { displaySubtitle(it, refined) }
        }
    }

    private fun displaySubtitle(cue: Cue, reply: TranslationReply) {
        val turkish = changedTranslation(cue.input, reply.text)
        publish(turkish?.let { Caption(cue.input.text, formatDialogue(it)) })
        onFailure(reply.issue ?: if (reply.text == null) "Bu replik çevrilemedi." else null)
    }

    private fun changedTranslation(input: TranslationInput, turkish: String?): String? =
        turkish?.trim()?.takeIf {
            it.isNotEmpty() && !cacheKey(input).text.equals(cacheKey(input.copy(text = it)).text, ignoreCase = true)
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
