// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale

/**
 * Cihaz üstü çevirinin Türkçesine yapılan güvenli düzeltmeler (Windows: TurkishPostEdit.cs, Mac: TurkishPostEdit.swift,
 * aynı kurallar). Yalnız anlamı değiştirmeyen biçim kuralları ve dar bir hitap kuralı vardır:
 * - noktalamadan önceki boşluk silinir ("Dur !" → "Dur!")
 * - kaynağın "!", "?", "?!", "…" sonu korunur (model "Koş!"u "Koş." yapabiliyor)
 * - tırnaklar kaynaktaki gibi düz ya da kıvrık olur
 * - aynı satırda "sen" ile konuşulurken model emri "siz"li verdiyse ("Sana söyledim. Bekleyin!") bilinen emirler "sen"li
 *   olur ("Bekle!"). Yalnız listedeki fiiller, yalnız emir yerindeyse (ardından noktalama gelir) ve cümlede "siz" geçmiyorsa.
 */
object TurkishPostEdit {
    private val turkish = Locale("tr", "TR")
    private val spaceBeforePunctuation = Regex("[ \\t\\u00A0]+(?=[,.!?;:…])")
    private val repeatedSpaces = Regex("[ \\t\\u00A0]{2,}")
    private val nonLetters = Regex("[^\\p{L}]+")

    /** Tek cümlenin çevirisini düzeltir; [source] İngilizce cümledir. */
    fun apply(source: String, translation: String): String {
        if (translation.none { it.isLetter() }) return translation
        var text = quotes(source, translation)
        text = spaceBeforePunctuation.replace(text, "")
        text = repeatedSpaces.replace(text, " ").trim()
        return ending(source, text)
    }

    /** Kaynağın ünlem/soru/üç nokta sonu çeviride başka bir sonla bittiyse kaynağınki konur. */
    internal fun ending(source: String, translation: String): String {
        val (_, sourceEnd, sourceClosers) = splitEnd(source)
        if (!isExpressive(sourceEnd)) return translation
        val (body, end, closers) = splitEnd(translation)
        // Kaynakta son noktalama tırnağın içindeyse (He said "Run!") çeviride tırnak sonda olmayabilir; dokunulmaz.
        if (sourceClosers.isNotEmpty() != closers.isNotEmpty()) return translation
        if (end == sourceEnd || !(end.isEmpty() || end == "." || isExpressive(end))) return translation
        if (body.none { it.isLetter() }) return translation
        return body + sourceEnd + closers
    }

    private fun isExpressive(end: String) = end.isNotEmpty() && (end.contains('!') || end.contains('?') || end.contains('…') || end == "...")

    /** Metin = gövde + son noktalama (. ! ? …) + kapanış tırnak/parantezleri. */
    private fun splitEnd(raw: String): Triple<String, String, String> {
        val text = raw.trimEnd()
        var closerStart = text.length
        while (closerStart > 0 && "\"'”’)]»".contains(text[closerStart - 1])) closerStart--
        var endStart = closerStart
        while (endStart > 0 && ".!?…".contains(text[endStart - 1])) endStart--
        return Triple(text.substring(0, endStart), text.substring(endStart, closerStart), text.substring(closerStart))
    }

    /** Kaynak kıvrık tırnak kullanıyorsa çeviri de kullanır; yoksa düz tırnak. */
    internal fun quotes(source: String, translation: String): String {
        val curlyDouble = source.contains('“') || source.contains('”')
        val curlySingle = source.contains('’') || source.contains('‘')
        val builder = StringBuilder(translation.length)
        for (i in translation.indices) {
            val c = translation[i]
            val opening = i == 0 || translation[i - 1].isWhitespace() || "([-–—".contains(translation[i - 1])
            builder.append(when {
                (c == '“' || c == '”' || c == '„' || c == '‟') && !curlyDouble -> '"'
                c == '"' && curlyDouble -> if (opening) '“' else '”'
                (c == '’' || c == '‘') && !curlySingle -> '\''
                c == '\'' && curlySingle -> if (opening) '‘' else '’'
                else -> c
            })
        }
        return builder.toString()
    }

    /** Kaynakta kıvrık kesme varsa Türkçe eklerde de o kullanılır. */
    fun apostrophe(source: String): Char = if (source.contains('’')) '’' else '\''

    private val senWords = setOf("sen", "seni", "sana", "senin", "sende", "senden", "seninle", "senle")
    private val sizWords = setOf("siz", "sizi", "size", "sizin", "sizde", "sizden", "sizinle", "sizler")

    /** "Siz"li emir → "sen"li emir; çoğul ya da kibar anlamı da olabilen sözcükler bilerek yok ("Alın" = alın). */
    private val imperatives = listOf(
        "dikkat edin" to "dikkat et", "acele edin" to "acele et", "ateş edin" to "ateş et", "yardım edin" to "yardım et",
        "takip edin" to "takip et", "sessiz olun" to "sessiz ol", "hazır olun" to "hazır ol", "sakin olun" to "sakin ol",
        "bekleyin" to "bekle", "durun" to "dur", "gelin" to "gel", "koşun" to "koş", "gidin" to "git", "bakın" to "bak",
        "dinleyin" to "dinle", "saklanın" to "saklan", "kaçın" to "kaç", "savaşın" to "savaş", "dövüşün" to "dövüş",
        "durdurun" to "durdur", "çekilin" to "çekil", "eğilin" to "eğil", "atlayın" to "atla", "tutun" to "tut",
        "bırakın" to "bırak", "söyleyin" to "söyle", "gösterin" to "göster", "getirin" to "getir", "kalkın" to "kalk",
        "odaklanın" to "odaklan", "yaklaşın" to "yaklaş", "uzaklaşın" to "uzaklaş",
    ).map { (polite, informal) -> Regex("(?<!\\p{L})${Regex.escape(polite)}(?=\\s*[!.,?…]|\\s*$)", RegexOption.IGNORE_CASE) to informal }

    /**
     * Bir altyazının cümle çevirileri: herhangi birinde "sen" geçiyorsa (seni, sana…), "siz" geçmeyen cümlelerdeki
     * bilinen "siz"li emirler "sen"li olur. Başka hiçbir şeye dokunulmaz.
     */
    fun register(translations: List<String?>): List<String?> {
        if (translations.none { it != null && containsWord(it, senWords) }) return translations
        return translations.map { if (it == null || containsWord(it, sizWords)) it else informal(it) }
    }

    private fun informal(sentence: String): String {
        var text = sentence
        for ((pattern, informal) in imperatives)
            text = pattern.replace(text) { match ->
                if (match.value[0].isUpperCase()) informal.substring(0, 1).uppercase(turkish) + informal.substring(1) else informal
            }
        return text
    }

    private fun containsWord(text: String, words: Set<String>): Boolean =
        text.lowercase(turkish).split(nonLetters).any { it in words }
}
