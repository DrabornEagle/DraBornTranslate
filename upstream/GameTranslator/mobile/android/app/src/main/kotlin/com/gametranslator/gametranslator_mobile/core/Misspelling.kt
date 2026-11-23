// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale

/**
 * Okumanın sözcük listesine göre ne kadar bozuk olduğu (Windows: Misspelling.cs, aynı kurallar). Yazı tanıma hareketli
 * sahnede harf düşürür ya da sözcükleri yapıştırır ("Igot it", "gonnatry workaroun"); bunlar gerçek sözcük değildir
 * ama simge içermediği için [OcrNoise] yakalamaz. Özel adlar yanlış sayılmasın diye büyük harfle başlayan düzgün
 * yazılmış sözcükler ("Kraven", "Ganke") sayılmaz; büyük-küçük harfi karışık olanlar ("IVe", "IEot") sayılır.
 * Telefonda devam satırlarının birleştirilmesinde ([ContinuationTranslator]) bozuk parçaları ayırmak için kullanılır.
 */
class Misspelling(private val isWord: (String) -> Boolean) {
    /** Bozuk sayılan sözcüklerin harf sayısı ve satırdaki toplam harf sayısı. */
    fun measure(text: String): Pair<Int, Int> {
        var bad = 0
        var all = 0
        for (token in OcrNoise.tokens(text)) {
            // Baştaki ve sondaki noktalama atılır; sözcük içindeki kesme ve tire kalır ("you're", "Spider-Man").
            var start = 0
            var end = token.length
            while (start < end && !token[start].isLetter()) start++
            while (end > start && !token[end - 1].isLetter()) end--
            val word = token.substring(start, end)
            val letters = word.count { it.isLetter() }
            if (letters == 0) continue
            all += letters
            // Tek harf ve kısaltmalar denetlenmez; tire ve kesme içeren sözcükler bütün sorulur.
            if (letters < 2 || word.all { !it.isLetter() || it.isUpperCase() }) continue
            val proper = word[0].isUpperCase() && word.drop(1).filter { it.isLetter() }.all { it.isLowerCase() }
            if (proper) {
                // "I" sonraki sözcüğe yapışmış ("Igot it", "Iknow"): ad değil, bozuk okumadır.
                if (word[0] == 'I' && letters >= 3 && !isWord(word) && isWord(word.substring(1))) bad += letters
                // Boşlukları okunmamış bütün satır ("Kirkitrustimlikeobrother") ad değildir: adlar bu kadar uzun olmaz.
                else if (letters > 13 && !word.contains('-') && !isWord(word)) bad += letters
                continue
            }
            if (!isWord(word) && !isContraction(word)) bad += letters
        }
        return bad to all
    }

    /** Kesme işareti okunmamış kısaltmalar ("dont", "theyre") ve konuşma dili ("'em", "c'mon") doğru sayılır. */
    private fun isContraction(word: String): Boolean {
        val lower = word.lowercase(Locale.ROOT)
        if (lower == "em" || lower == "cmon" || lower == "ya" || lower == "til" || lower == "cause") return true
        if (word.contains('\'') || word.contains('’') || word.length < 3) return false
        for (tail in intArrayOf(1, 2)) {
            if (word.length <= tail) continue
            if (isWord(word.substring(0, word.length - tail) + "'" + word.substring(word.length - tail))) return true
        }
        return false
    }

    /** Satırda gerçek olmayan sözcük var mı. */
    fun looksMisread(text: String): Boolean = measure(text).first > 0

    /** Okuma çevrilemeyecek kadar bozuk mu: harflerinin üçte birinden çoğu gerçek olmayan sözcüklerde. */
    fun isGarbage(text: String): Boolean {
        val (bad, all) = measure(text)
        return all >= 6 && bad * 3 > all
    }
}
