// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale

/**
 * Ücretsiz (cihaz üstü) çevirinin ortak metin hattı (Windows: FreeTranslation.cs, Mac: FreeTranslation.swift, aynı
 * adımlar): büyük harf düzeni → oyun deyimleri → terim koruma → çeviri → terim ve ekleri → Türkçe düzeltmeler.
 * Kısa satır önceki cümleyle birlikte ("***" ayracıyla) çevrilebilir; ayraçtan sonrası alınır, ayrım belirsizse satır
 * tek başına çevrilir.
 */
object FreeTranslation {
    /** Bu sözcük sayısının altındaki cümle önceki cümleyle birlikte çevrilir. */
    const val SHORT_LINE_WORDS = 4

    /** Bağlam ile kısa satır arasındaki ayraç (bilgisayarın modelinin sözlüğünde tek parça). */
    const val SEPARATOR = " *** "
    private const val SEPARATOR_CORE = "***"

    fun isShort(sentence: String): Boolean =
        sentence.split(' ', '\t', '\n').count { word -> word.any { it.isLetter() } } in 1 until SHORT_LINE_WORDS

    /** Ayraçtan sonraki kısım; ayraç tam bir kez geçmiyorsa, iki yan da harf içermiyorsa ya da kısım akla yatmayacak kadar uzunsa null. */
    fun afterSeparator(output: String, source: String): String? {
        val first = output.indexOf(SEPARATOR_CORE)
        if (first < 0 || output.indexOf(SEPARATOR_CORE, first + SEPARATOR_CORE.length) >= 0) return null
        val before = output.substring(0, first)
        val after = output.substring(first + SEPARATOR_CORE.length).trim()
        // "****" gibi yapışık yıldızlar da belirsizdir.
        if (after.startsWith('*') || before.endsWith('*')) return null
        if (before.none { it.isLetter() } || after.none { it.isLetter() }) return null
        if (after.length > 3 * source.length + 20) return null
        return after
    }

    /**
     * Tek cümleyi çevirir. [normalize] büyük harf düzenini yapar (öğrenilen adlarla), [engine] modeldir (null: çeviremedi).
     * Önce bağlamla (verildiyse), olmazsa tek başına, terimler korunamadıysa korumasız çevrilir.
     */
    fun translate(sentence: String, context: String?, glossary: Glossary, normalize: (String) -> String, engine: (String) -> String?): String? {
        val apostrophe = TurkishPostEdit.apostrophe(sentence)
        fun prepare(text: String) = GameEnglish.plain(normalize(text))

        if (context != null) {
            val protector = TermProtector(glossary, sentence, context)
            val (contextText, _) = protector.protect(prepare(context))
            val (sentenceText, slots) = protector.protect(prepare(sentence))
            val output = engine(contextText + SEPARATOR + sentenceText)
            val after = output?.let { afterSeparator(it, sentence) }
            val restored = after?.let { TermProtector.restore(it, slots, protector.allSlots, apostrophe) }
            if (restored != null) return TurkishPostEdit.apply(sentence, restored)
        }

        val single = TermProtector(glossary, sentence)
        val (input, used) = single.protect(prepare(sentence))
        val translated = engine(input) ?: return null
        if (used.isEmpty()) return TurkishPostEdit.apply(sentence, translated)
        TermProtector.restore(translated, used, single.allSlots, apostrophe)?.let { return TurkishPostEdit.apply(sentence, it) }
        // Model bir yer tutucuyu düşürdü: terimsiz ama eksiksiz çeviri, eksik terimli çeviriden iyidir.
        return engine(prepare(sentence))?.let { TurkishPostEdit.apply(sentence, it) }
    }
}

/**
 * Cihaz üstü çeviriyi (ML Kit) bilgisayardaki cihaz üstü çevirmenle (MarianTranslator) aynı metin hattından geçirir:
 * altyazı cümlelere bölünür ([SubtitleSegmenter]; konuşmacı etiketi ve tireler çevrilmez), her cümle
 * [FreeTranslation.translate] ile çevrilir, hitap satırın bütününe göre düzeltilir ([TurkishPostEdit.register]).
 * Yalnız İngilizce→Türkçe için kurulur (kurallar Türkçeye göredir). Kısa satırı bağlamıyla ("***") çevirme bilgisayarın
 * modeline özgüdür (ayraç onun sözlüğünde tek parça); ML Kit'e bağlam verilmez.
 */
class FreeTranslator(private val inner: TextTranslator, private val glossary: Glossary = Glossary.EMPTY) : TextTranslator {
    /** Konuşmacı etiketlerinden öğrenilen adlar (küçük harf): büyük harf düzeninde büyük harfle kalırlar. */
    private val names = LinkedHashSet<String>()

    override fun translate(text: String): String? {
        val pieces = SubtitleSegmenter.split(text)
        synchronized(names) {
            for (piece in pieces) {
                val name = SubtitleSegmenter.labelName(piece) ?: continue
                for (word in name.split(' ')) if (word.length >= 2) names.add(word.lowercase(Locale.ROOT))
            }
            if (names.size > 500) names.clear()
        }
        val known = synchronized(names) { names.toSet() }
        val translations = ArrayList<String?>(pieces.size)
        for (piece in pieces) {
            if (!piece.translate) {
                translations.add(null)
                continue
            }
            val translated = FreeTranslation.translate(piece.text, null, glossary, { SubtitleSegmenter.normalizeCase(it, known) }) {
                inner.translate(it)?.trim()
            } ?: return null
            translations.add(translated)
        }
        val final = TurkishPostEdit.register(translations)
        val builder = StringBuilder()
        for (i in pieces.indices) builder.append(final[i] ?: pieces[i].text)
        return builder.toString().trim()
    }
}
