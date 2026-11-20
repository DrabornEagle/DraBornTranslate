// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.Glossary
import com.gametranslator.gametranslator_mobile.core.GlossaryTranslator
import com.gametranslator.gametranslator_mobile.core.PackKey
import com.gametranslator.gametranslator_mobile.core.PackMatch
import com.gametranslator.gametranslator_mobile.core.SubtitleSegmenter
import com.gametranslator.gametranslator_mobile.core.TextTranslator
import java.util.Locale

internal data class TranslationInput(val text: String, val readingArea: ReadingArea = ReadingArea.SUBTITLES)
internal data class TranslationReply(val text: String? = null, val issue: String? = null)

/** Gives the sentence-level model complete sentences and keeps menu lines and speaker turns separate. */
internal class GameTranslationPipeline(
    private val packLookup: (String) -> PackMatch?,
    model: TextTranslator,
) {
    private val withTerms = GlossaryTranslator(GAME_TERMS, model)

    fun translate(input: TranslationInput): TranslationReply {
        val text = input.text.trim()
        if (text.isEmpty()) return TranslationReply()
        val isSubtitles = input.readingArea == ReadingArea.SUBTITLES
        if (isSubtitles) findPack(text)?.let { return TranslationReply(it) }
        val lines = if (isSubtitles) listOf(text) else text.lines()
        val rendered = lines.mapNotNull { line ->
            (if (isSubtitles) null else findPack(line)) ?: translateLine(line)
        }
        return TranslationReply(rendered.joinToString("\n").takeIf(String::isNotBlank))
    }

    private fun findPack(text: String): String? =
        runCatching { packLookup(text)?.text?.trim()?.takeIf(String::isNotEmpty) }.getOrNull()

    private fun translateLine(text: String): String? {
        val pieces = SubtitleSegmenter.split(text)
        if (pieces.none { it.translate }) return null
        return buildString {
            for (piece in pieces) {
                if (!piece.translate) {
                    append(piece.text)
                    continue
                }
                val source = piece.text
                val direct = PackKey.key(source)?.let(commonLines::get)
                val translation = findPack(source) ?: direct ?: translateSentence(source)
                if (translation.isNullOrBlank()) return null
                append(translation)
            }
        }.trim().takeIf(String::isNotEmpty)
    }

    private fun translateSentence(source: String): String? {
        val sentence = SubtitleSegmenter.normalizeCase(source, knownNames).replace('’', '\'')
        val input = idioms.fold(sentence) { text, (phrase, replacement) ->
            phrase.replace(text) { match ->
                replacement(match).replaceFirstChar { if (match.value[0].isUpperCase()) it.uppercaseChar() else it }
            }
        }
        val translated = withTerms.translate(input)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val sentences = SubtitleSegmenter.split(translated).filter { it.translate }.map { it.text }
        val filtered = sentences.distinctBy { PackKey.key(it) }
        val natural = if (filtered.size < sentences.size) filtered.joinToString(" ") else translated
        return when (val end = source.trimEnd('"', '”').lastOrNull()) {
            '?', '!' -> natural.trimEnd('.', '?', '!') + end
            else -> natural
        }
    }
}

private val places = listOf("Vice City", "Leonida", "Los Santos", "Liberty City", "San Andreas")
private val knownNames = places.flatMap { it.lowercase(Locale.ROOT).split(' ') }.toSet()
private val GAME_TERMS = Glossary.of(
    terms = mapOf(
        "wanted level" to "aranma seviyesi",
        "side mission" to "yan görev",
        "safe house" to "sığınak",
        "save point" to "kayıt noktası",
        "fast travel" to "hızlı seyahat",
        "health bar" to "can çubuğu",
    ),
    keep = places,
)

private val commonLines = mapOf(
    "Lose the cops!" to "Polisleri atlat!",
    "We are being tailed." to "Takip ediliyoruz.",
    "We're being tailed." to "Takip ediliyoruz.",
    "They're on our tail." to "Peşimizdeler.",
    "Take cover!" to "Siper al!",
    "Get in the car!" to "Arabaya bin!",
    "Stay out of sight." to "Görünmeden ilerle.",
    "Hold your fire!" to "Ateş etme!",
    "Keep your head down." to "Başını kaldırma.",
    "Let's get out of here." to "Hadi buradan çıkalım.",
    "Meet me at the bridge." to "Köprüde buluşalım.",
    "I'll cover you." to "Seni korurum.",
    "I should've seen it coming." to "Bunu tahmin etmeliydim.",
    "I should have seen it coming." to "Bunu tahmin etmeliydim.",
    "You set me up." to "Bana tuzak kurdun.",
    "I owe you one." to "Sana bir iyilik borçluyum.",
    "You made it." to "Başardın.",
    "Get down!" to "Yere yat!",
    "Run the plates." to "Plakayı sorgula.",
    "Go around the back." to "Arkadan dolaş.",
    "We're not out of the woods yet." to "Tehlike henüz geçmedi.",
    "Don't make me regret it." to "Beni pişman etme.",
    "The coast is clear." to "Ortalık temiz.",
    "Make a run for it." to "Koş, kaç!",
    "Got it." to "Anladım.",
    "I got this." to "Ben hallederim.",
    "Something's off." to "Bir terslik var.",
    "I don't buy it." to "Buna inanmıyorum.",
    "Keep still." to "Kıpırdama.",
    "Yeah." to "Evet.",
    "No." to "Hayır.",
    "Okay." to "Tamam.",
    "Thanks." to "Sağ ol.",
    "Come on!" to "Hadi!",
    "Go!" to "Git!",
    "Wait!" to "Bekle!",
    "Look out!" to "Dikkat et!",
    "Move!" to "Çabuk!",
    "Continue" to "Devam et",
    "Settings" to "Ayarlar",
    "Resume" to "Oyuna dön",
    "New game" to "Yeni oyun",
    "Load game" to "Oyun yükle",
    "Tracked missions" to "Takip edilen görevler",
    "Game saved" to "Oyun kaydedildi",
).mapKeys { (source, _) -> checkNotNull(PackKey.key(source)) }

private fun idiom(phrase: String, replacement: String): Pair<Regex, (MatchResult) -> String> =
    Regex("""\b$phrase\b""", RegexOption.IGNORE_CASE) to { replacement }

private val idioms = listOf(
    idiom("lay low", "stay hidden"),
    idiom("until the heat dies down", "until things calm down"),
    idiom("pull over", "stop by the side of the road"),
    idiom("county line", "county boundary"),
    idiom("lose the cops", "escape from the police"),
    idiom("getaway car", "escape vehicle"),
    idiom("ditch the car", "leave the car behind"),
    idiom("leave me hanging", "abandon me"),
    idiom("quit messing around", "stop playing around"),
    idiom("keep your eyes on the road", "watch the road"),
    idiom("keep an eye on", "watch"),
    idiom("we've got to move", "we have to leave now"),
    idiom("i thought you said(?: that)?(?= the | he | she | you | we | it | they | this | i )", "I thought you told me that"),
    idiom("wait by the car", "wait next to the car"),
    idiom("out of the woods yet", "safe yet"),
    Regex("""\b(is|was|are|were) unlocked\b""", RegexOption.IGNORE_CASE) to { match: MatchResult ->
        match.groupValues[1] + " not locked"
    },
)
