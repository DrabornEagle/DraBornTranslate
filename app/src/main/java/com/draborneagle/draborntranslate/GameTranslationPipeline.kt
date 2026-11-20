// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.Glossary
import com.gametranslator.gametranslator_mobile.core.PackMatch
import com.gametranslator.gametranslator_mobile.core.SubtitleSegmenter
import com.gametranslator.gametranslator_mobile.core.TermProtector
import com.gametranslator.gametranslator_mobile.core.TextMatch
import java.util.Locale

internal data class TranslationInput(val text: String, val readingArea: ReadingArea = ReadingArea.SUBTITLES)
internal data class TranslationReply(val text: String? = null, val issue: String? = null)

/** Translates complete dialogue sentences or independently recognized menu labels on the device. */
internal class GameTranslationPipeline(
    private val packLookup: (String) -> PackMatch?,
    private val candidates: (String) -> List<String>,
) {
    fun translate(input: TranslationInput): TranslationReply {
        val lines = input.text.lines().map(::cleanOcrLine).filter(String::isNotBlank)
        if (lines.isEmpty()) return TranslationReply()
        val area = input.readingArea
        val rendered = if (area == ReadingArea.SUBTITLES) {
            translateText(lines.joinToString("\n"), area)
        } else {
            lines.mapNotNull { translateMenuLine(it) }.joinToString("\n")
        }
        return TranslationReply(rendered?.takeIf(String::isNotBlank))
    }

    private fun translateMenuLine(line: String): String? {
        findPack(line)?.let { return it }
        val colon = line.indexOf(':')
        if (colon > 0) {
            val header = GamePhrases.direct(line.substring(0, colon), ReadingArea.GAME_TEXT)
            if (header != null) {
                val detail = line.substring(colon + 1).trim()
                return when {
                    detail.isEmpty() -> "$header:"
                    detail.none(Char::isLetter) -> "$header: $detail"
                    else -> translateText(detail, ReadingArea.GAME_TEXT)?.let { "$header: $it" }
                }
            }
        }
        return translateText(line, ReadingArea.GAME_TEXT, lookupWholeText = false)
    }

    private fun translateText(text: String, area: ReadingArea, lookupWholeText: Boolean = true): String? {
        if (lookupWholeText) findPack(text)?.let { return it }
        val pieces = SubtitleSegmenter.split(text, preserveSpeakers = area == ReadingArea.SUBTITLES)
        if (pieces.none { it.translate }) return null
        val names = pieces.mapNotNull(SubtitleSegmenter::labelName).map { label ->
            label.split(' ').joinToString(" ") { word ->
                word.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercaseChar)
            }
        } + knownNames
        return buildString {
            for (piece in pieces) {
                if (!piece.translate) {
                    append(piece.text)
                    continue
                }
                val source = piece.text
                val translation = findPack(source) ?: GamePhrases.direct(source, area)
                    ?: translateSentence(source, area, names)
                    ?: return null
                append(translation)
            }
        }.trim().takeIf(String::isNotEmpty)
    }

    private fun findPack(source: String): String? {
        val text = runCatching { packLookup(source)?.text?.trim()?.takeIf(String::isNotEmpty) }.getOrNull() ?: return null
        // The pack key ignores punctuation. A statement can share its key with an incredulous question.
        if (ending(source)?.startsWith('?') == true && ending(text)?.startsWith('?') != true) return null
        return text.takeIf { preservesNumbers(source, it) }
    }

    private fun translateSentence(source: String, area: ReadingArea, names: List<String>): String? {
        if (looksDamaged(source)) return null
        val sound = source.startsWith('[') && source.endsWith(']')
        val body = if (sound) source.substring(1, source.length - 1) else source
        val ids = gameId.findAll(body).map(MatchResult::value).toList()
        val namesInSource = Glossary.of(emptyMap(), names + ids).find(body).map { it.turkish }.distinct()
        val wordsToKeep = namesInSource.flatMap { it.lowercase(Locale.ROOT).split(' ') }.toSet()
        val preserved = Glossary.of(emptyMap(), namesInSource)
        val normalized = SubtitleSegmenter.normalizeCase(body, wordsToKeep)
        val rewritten = GamePhrases.forModel(normalized, area)

        fun choose(options: List<String>): String? = options.firstNotNullOfOrNull { suggestion ->
            val candidate = simplifyRepetition(body, suggestion.trim())
            if (candidate.isBlank() || '\uFFFD' in candidate || looksDamaged(candidate) ||
                !preservesNumbers(body, candidate) || preserved.find(candidate).map { it.turkish }.toSet().size != namesInSource.size ||
                looksUntranslated(body, candidate)
            ) null else candidate
        }

        val withTerms = if (area == ReadingArea.SUBTITLES) choose(protectedCandidates(rewritten, dialogueTerms)) else null
        val selected = withTerms ?: choose(candidates(rewritten))
            ?: choose(protectedCandidates(rewritten, preserved))
            ?: (if (rewritten != normalized) choose(candidates(normalized)) else null)
            ?: return null
        val restored = restoreEnding(body, selected)
        return if (sound) "[${restored.trim('[', ']')}]" else restored
    }

    private fun protectedCandidates(text: String, glossary: Glossary): List<String> {
        val protector = TermProtector(glossary, text)
        val (protected, slots) = protector.protect(text)
        if (slots.isEmpty()) return emptyList()
        return candidates(protected).mapNotNull { TermProtector.restore(it, slots, protector.allSlots) }
    }
}

private val places = listOf("Vice City", "Leonida", "Los Santos", "Liberty City", "San Andreas")
private val knownNames = places + listOf(
    "Lucia", "Jason", "Franklin", "Michael", "Trevor", "Lamar", "Kratos", "Atreus", "Mimir",
)
private val dialogueTerms = Glossary.of(
    terms = mapOf(
        "safe house" to "sığınak",
        "wanted level" to "aranma seviyesi",
        "glove compartment" to "torpido gözü",
    ),
    keep = places,
)

private val number = Regex("\\d+(?:[.,:/]\\d+)*")
private val thousands = Regex("\\d{1,3}(?:[.,]\\d{3})+")
private val bareNumbers = Regex("[.,]")
private val endMark = Regex("""([?!…]+)(["”'’»\)\]]*)$""")
private val endClosers = setOf('"', '”', '\'', '’', '»', ')', ']')
private val englishFunctionWords = Regex("""\b(?:the|you|your|we|they|is|are|was|were|have|will|with|must|could|don't|can't)\b""", RegexOption.IGNORE_CASE)

private fun numberCounts(text: String): Map<String, Int> = number.findAll(text).map {
    val value = it.value
    if (thousands.matches(value)) bareNumbers.replace(value, "") else value.replace(',', '.')
}.groupingBy { it }.eachCount()

private fun preservesNumbers(source: String, candidate: String): Boolean {
    val numbers = numberCounts(source)
    if (numbers.isEmpty()) return true
    val translated = numberCounts(candidate)
    return numbers.all { (value, count) -> (translated[value] ?: 0) >= count }
}

private fun ending(text: String): String? = endMark.find(text.trimEnd())?.groupValues?.get(1)

private fun restoreEnding(source: String, translated: String): String {
    val end = ending(source) ?: return translated
    val closing = translated.takeLastWhile { it in endClosers }
    return translated.dropLast(closing.length).trimEnd('.', '?', '!', '…') + end + closing
}

private fun looksUntranslated(source: String, output: String): Boolean =
    englishFunctionWords.containsMatchIn(source) && TextMatch.normalize(source) == TextMatch.normalize(output)

private fun simplifyRepetition(source: String, output: String): String {
    val sourceWords = TextMatch.normalize(source).split(' ')
    val repeatedSource = sourceWords.size >= 2 && sourceWords.windowed(2).distinct().size < sourceWords.size - 1
    if (repeatedSource) return output
    val sentences = SubtitleSegmenter.split(output, preserveSpeakers = false).filter { it.translate }.map { it.text }
    if (sentences.size < 2) return output
    val distinct = sentences.filterIndexed { index, sentence ->
        index == 0 || TextMatch.normalize(sentence) != TextMatch.normalize(sentences[index - 1])
    }
    return if (distinct.size < sentences.size) distinct.joinToString(" ") else output
}
