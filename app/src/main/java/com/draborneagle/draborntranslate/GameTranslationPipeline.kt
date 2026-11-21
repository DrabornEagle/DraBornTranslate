// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.Glossary
import com.gametranslator.gametranslator_mobile.core.PackMatch
import com.gametranslator.gametranslator_mobile.core.SubtitleSegmenter
import com.gametranslator.gametranslator_mobile.core.TermProtector
import com.gametranslator.gametranslator_mobile.core.TextMatch
import java.util.Locale

internal data class TranslationInput(
    val text: String,
    val readingArea: ReadingArea = ReadingArea.SUBTITLES,
    val allowPartial: Boolean = false,
    val preservePlaceholders: Boolean = false,
)
internal data class TranslationReply(val text: String? = null, val issue: String? = null, val refined: Boolean = false)

/** Sends full speaker turns to the local model and keeps menu labels in their original order. */
internal class GameTranslationPipeline(
    private val packLookup: (String) -> PackMatch?,
    private val candidates: ((TranslationInput) -> List<String>)? = null,
    private val canceled: () -> Boolean = { false },
) {
    fun translate(input: TranslationInput): TranslationReply {
        val lines = input.text.lines().map(::cleanOcrLine).filter(String::isNotBlank)
        if (lines.isEmpty() || canceled()) return TranslationReply()
        val area = input.readingArea
        val reply = if (area == ReadingArea.SUBTITLES) {
            translateText(lines.joinToString("\n"), area, allowPartial = input.allowPartial)
        } else {
            TranslationReply(lines.mapNotNull(::translateMenuLine).joinToString("\n").ifBlank { null })
        }
        return if (canceled()) TranslationReply() else reply
    }

    private fun translateMenuLine(line: String): String? {
        if (canceled()) return null
        findPack(line)?.let { return it }
        GamePhrases.direct(line, ReadingArea.GAME_TEXT)?.let { return it }
        GamePhrases.direct(line, ReadingArea.SUBTITLES)?.let { return it }
        val colon = line.indexOf(':')
        if (colon > 0) {
            val header = GamePhrases.direct(line.substring(0, colon), ReadingArea.GAME_TEXT)
            if (header != null) {
                val detail = line.substring(colon + 1).trim()
                return when {
                    detail.isEmpty() -> "$header:"
                    detail.none(Char::isLetter) -> "$header: $detail"
                    else -> translateText(detail, ReadingArea.GAME_TEXT).text?.let { "$header: $it" }
                }
            }
        }
        return translateText(line, ReadingArea.GAME_TEXT, lookupWholeText = false).text
    }

    private fun translateText(
        text: String,
        area: ReadingArea,
        lookupWholeText: Boolean = true,
        allowPartial: Boolean = false,
    ): TranslationReply {
        if (lookupWholeText) findPack(text)?.let { return TranslationReply(it) }
        val dialogue = area == ReadingArea.SUBTITLES
        val pieces = SubtitleSegmenter.split(text, preserveSpeakers = dialogue, keepSpeakerTurns = dialogue)
        val speakerNames = pieces.mapNotNull(SubtitleSegmenter::labelName)
        return renderPieces(pieces, area, speakerNames, splitOnFailure = dialogue, allowPartial = allowPartial)
    }

    private fun renderPieces(
        pieces: List<SubtitleSegmenter.Piece>,
        area: ReadingArea,
        speakerNames: List<String>,
        splitOnFailure: Boolean,
        allowPartial: Boolean,
    ): TranslationReply {
        val result = StringBuilder()
        var translatedAny = false
        var incomplete = false
        for (piece in pieces) {
            if (canceled()) return TranslationReply()
            if (!piece.translate) {
                result.append(piece.text)
                continue
            }
            val source = piece.text
            val known = findPack(source) ?: GamePhrases.direct(source, area)
            val sentences = if (known == null && splitOnFailure) {
                SubtitleSegmenter.split(source, preserveSpeakers = false)
            } else emptyList()
            val sentenceCount = sentences.count { it.translate }
            val canSplit = sentenceCount >= 2
            val hasVerifiedSentence = canSplit && sentences.any {
                it.translate && (GamePhrases.direct(it.text, area) != null || findPack(it.text) != null)
            }
            // Keep confirmed idiomatic meanings when a turn also contains a new sentence.
            val direct = known ?: if (!hasVerifiedSentence) {
                translatePiece(source, area, speakerNames, sentenceCount)
            } else null
            if (direct != null) {
                translatedAny = true
                result.append(direct)
                continue
            }
            val fallback = if (canSplit) {
                renderPieces(sentences, area, speakerNames, splitOnFailure = false, allowPartial = allowPartial)
            } else TranslationReply()
            if (fallback.text == null && !allowPartial) return TranslationReply()
            translatedAny = translatedAny || fallback.text != null
            incomplete = incomplete || fallback.text == null || fallback.issue != null
            result.append(fallback.text ?: "[…]")
        }
        if (!translatedAny) return TranslationReply()
        return TranslationReply(result.toString().trim(),
            if (incomplete) "Repliğin bir bölümü çevrilemedi." else null)
    }

    private fun findPack(source: String): String? {
        val text = runCatching { packLookup(source)?.text?.trim()?.takeIf(String::isNotEmpty) }.getOrNull() ?: return null
        // Package keys ignore punctuation, so a statement and an incredulous question may share a key.
        if (ending(source)?.startsWith('?') == true && ending(text)?.startsWith('?') != true) return null
        return text.takeIf { preservesSubtitleNumbers(source, it) }
    }

    private fun translatePiece(source: String, area: ReadingArea, speakerNames: List<String>, sentenceCount: Int): String? {
        val model = candidates ?: return null
        if (looksDamaged(source) || canceled()) return null
        val sound = source.startsWith('[') && source.endsWith(']')
        val body = if (sound) source.substring(1, source.length - 1) else source
        val ids = gameId.findAll(body).map(MatchResult::value).toList()
        val protected = Glossary.of(emptyMap(), knownNames + speakerNames + ids)
        val namesInSource = protected.find(body).map { it.turkish }.toSet()
        val wordsToKeep = namesInSource.flatMap { it.lowercase(Locale.ROOT).split(' ') }.toSet()
        val normalized = SubtitleSegmenter.normalizeCase(body, wordsToKeep)
        val modelSource = GamePhrases.contextualize(digitizeSubtitleQuantities(normalized), area)
        fun checked(output: String?): String? {
            val text = output?.trim()?.takeIf(String::isNotBlank)?.let(::correctWithoutPronoun) ?: return null
            return text.takeUnless {
                looksDamaged(it) || '\uFFFD' in it ||
                    !preservesSubtitleNumbers(body, it) ||
                    !protected.find(it).map { match -> match.turkish }.containsAll(namesInSource) ||
                    (englishProhibition.containsMatchIn(body) && !turkishProhibition.containsMatchIn(it)) ||
                    (englishNeverSaid.containsMatchIn(body) && !turkishDeniedSaying.containsMatchIn(it)) ||
                    (sentenceCount > 1 && SubtitleSegmenter.split(it, preserveSpeakers = false).count { it.translate } < sentenceCount) ||
                    (englishFunctionWords.containsMatchIn(body) && TextMatch.normalize(body) == TextMatch.normalize(it))
            }
        }
        val protector = TermProtector(gameTerms, modelSource)
        val (prepared, slots) = protector.protect(modelSource)
        val withTerms = if (slots.isNotEmpty()) {
            model(TranslationInput(prepared, area, preservePlaceholders = true)).firstNotNullOfOrNull {
                checked(TermProtector.restore(it, slots, protector.allSlots))
            }
        } else null
        if (canceled()) return null
        val candidate = withTerms ?: model(TranslationInput(modelSource, area)).firstNotNullOfOrNull(::checked)
            ?: return null
        val result = restoreEnding(body, candidate)
        return if (sound) "[${result.trim('[', ']')}]" else result
    }
}

private val knownNames = listOf(
    "Vice City", "Leonida", "Los Santos", "Liberty City", "San Andreas",
    "Lucia", "Jason", "Franklin", "Michael", "Trevor", "Lamar", "Kratos", "Atreus", "Mimir",
)
private val gameTerms = Glossary.of(mapOf(
    "safe house" to "sığınak",
    "glove compartment" to "torpido gözü",
    "wanted level" to "aranma seviyesi",
    "spare key" to "yedek anahtar",
    "aim assist" to "nişan yardımı",
))
private val endMark = Regex("""([?!…]+)(["”'’»\)\]]*)$""")
private val endClosers = setOf('"', '”', '\'', '’', '»', ')', ']')
private val englishFunctionWords = Regex("""\b(?:the|you|your|we|they|is|are|was|were|have|will|with|must|could|don't|can't)\b""", RegexOption.IGNORE_CASE)
private val englishProhibition = Regex("""^(?:please +)?(?:don't|do not|never)\b""", RegexOption.IGNORE_CASE)
private val englishNeverSaid = Regex("""^I (?:never said|didn't say|did not say)\b""", RegexOption.IGNORE_CASE)
private val turkishDeniedSaying = Regex(
    """\b(?:söylemedim|demedim|iddia etmedim|söylemiş değilim|demiş değilim|ağzımdan çıkmadı)\b""",
    RegexOption.IGNORE_CASE,
)
private val turkishProhibition = Regex(
    """\b(?:sakın|asla|kaçın\p{L}*|yasak\p{L}*|\p{L}{2,}m[ae](?:y[ıi]n(?:[ıi]z)?|s[ıi]n(?:[ıi]z)?|l[ıi](?:s[ıi]n(?:[ıi]z)?)?)?)\b""",
    RegexOption.IGNORE_CASE,
)
private val turkishLocale = Locale.forLanguageTag("tr")
private val possessives = mapOf(
    "benim" to "ben", "senin" to "sen", "onun" to "o",
    "bizim" to "biz", "sizin" to "siz", "onların" to "onlar",
)
private val possessiveWithout = Regex(
    "\\b(${possessives.keys.joinToString("|")})(?= (?:olmadan|olmasa(?:m|n|k|nız|lar|yd[ıi]\\p{L}*)?)\\b)",
    RegexOption.IGNORE_CASE,
)

private fun correctWithoutPronoun(text: String): String = possessiveWithout.replace(text) { match ->
    val pronoun = possessives[match.value.lowercase(turkishLocale)]
        ?: possessives[match.value.lowercase(Locale.ROOT)]
        ?: return@replace match.value
    pronoun.replaceFirstChar { if (match.value.first().isUpperCase()) it.uppercaseChar() else it }
}

private fun ending(text: String): String? = endMark.find(text.trimEnd())?.groupValues?.get(1)

private fun restoreEnding(source: String, translated: String): String {
    val end = ending(source) ?: return translated
    val closing = translated.takeLastWhile { it in endClosers }
    return translated.dropLast(closing.length).trimEnd('.', '?', '!', '…') + end + closing
}
