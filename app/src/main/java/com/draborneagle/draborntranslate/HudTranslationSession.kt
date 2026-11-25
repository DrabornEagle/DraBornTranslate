// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.ColoredWords
import com.gametranslator.gametranslator_mobile.core.UiPrompts
import java.util.Locale

/** HUD updates settle independently and use the same pack/model as live subtitles. */
internal class HudTranslationSession(
    private val translate: (TranslationInput, (TranslationReply) -> Unit) -> Unit,
    private val show: (List<HudBlock>) -> Unit,
) {
    private class Region {
        var candidate: HudBlock? = null
        var accepted: HudBlock? = null
        var sameFrames = 0
        var rendered: HudBlock? = null
    }

    private val regions = HudKind.entries.associateWith { Region() }
    private val translations = object : LinkedHashMap<String, String>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 192
    }
    private var translating = false
    private var session = 0
    private var shown: List<HudBlock> = emptyList()

    fun read(blocks: List<HudBlock>) {
        for ((kind, region) in regions) {
            val candidate = blocks.firstOrNull { it.kind == kind }
            val unchanged = candidate?.contentKey() == region.candidate?.contentKey()
            region.sameFrames = if (unchanged) minOf(region.sameFrames + 1, 2) else 1
            region.candidate = candidate
            if (region.sameFrames < 2) continue
            val alreadyTranslated = region.accepted?.contentKey() == candidate?.contentKey()
            region.accepted = candidate
            if (candidate == null) region.rendered = null
            else if (alreadyTranslated) region.rendered = region.rendered?.copy(box = candidate.box)
            else region.rendered = null
        }
        publish()
        next()
    }

    fun reset() {
        session++
        translating = false
        for (state in regions.values) {
            state.candidate = null
            state.accepted = null
            state.rendered = null
            state.sameFrames = 0
        }
        publish()
    }

    private fun next() {
        if (translating) return
        val region = regions.values.firstOrNull { it.accepted != null && it.rendered == null } ?: return
        val source = checkNotNull(region.accepted)
        translating = true
        render(region, source, 0, mutableListOf(), session)
    }

    private fun render(region: Region, source: HudBlock, index: Int, rows: MutableList<HudLine>, epoch: Int) {
        if (epoch != session) return
        val current = region.accepted
        if (current == null || source.contentKey() != current.contentKey()) {
            translating = false
            next()
            return
        }
        val line = source.lines.getOrNull(index)
        if (line == null) {
            region.rendered = current.copy(lines = rows)
            translating = false
            publish()
            next()
            return
        }
        if (line.prompt || line.style == HudLineStyle.NAME) {
            rows.add(line)
            render(region, source, index + 1, rows, epoch)
            return
        }
        translateLine(line.text, epoch) { translated ->
            if (translated != null) {
                val withButtons = UiPrompts.keepIcons(line.text, translated)
                colorLine(line, withButtons, epoch) { marked ->
                    rows.add(line.copy(text = marked, colors = emptyList()))
                    render(region, source, index + 1, rows, epoch)
                }
            } else {
                render(region, source, index + 1, rows, epoch)
            }
        }
    }

    private fun colorLine(line: HudLine, text: String, epoch: Int, done: (String) -> Unit) {
        val colors = line.colors.distinct().take(4).map { it.text to it.color }
        val unmodified = ColoredWords.mark(text, colors)
        val translated = colors.firstOrNull { !unmodified.contains("${it.second}|") }
        if (translated == null) {
            done(unmodified)
            return
        }
        translateLine(translated.first, epoch) { word ->
            done(if (word.isNullOrBlank()) unmodified else ColoredWords.mark(text, colors + (word to translated.second)))
        }
    }

    private fun translateLine(text: String, epoch: Int, done: (String?) -> Unit) {
        val cached = translations[text]
        if (cached != null) {
            done(cached)
            return
        }
        UiPrompts.translate(text.uppercase(Locale.ROOT))?.let {
            done(it)
            return
        }
        translate(TranslationInput(text, ReadingArea.GAME_TEXT)) { reply ->
            if (epoch != session) return@translate
            val rendered = reply.text?.takeIf(String::isNotBlank)
            if (rendered != null) translations[text] = rendered
            done(rendered)
        }
    }

    private fun publish() {
        val current = regions.values.mapNotNull { it.rendered }.filter { it.lines.isNotEmpty() }
        if (current != shown) {
            shown = current
            show(current)
        }
    }
}
