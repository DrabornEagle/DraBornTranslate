// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

/**
 * Oyunlarda sık geçen, cihaz üstü çeviri modelinin kelime kelime çevirdiği ifadeler ("I've got your six" → "Altını
 * aldım") çeviriden önce aynı anlamdaki düz İngilizceye çevrilir (Windows: GameEnglish.cs, aynı liste). Liste
 * bilgisayarın cihaz üstü modeliyle tek tek denendi; telefonda da yalnız cihaz üstü çeviriye (ML Kit) uygulanır.
 */
object GameEnglish {
    private fun phrase(words: String) = Regex("\\b$words\\b", RegexOption.IGNORE_CASE)

    private val phrases = listOf(
        phrase("friendlies") to "allies",
        phrase("(?:I've|I) got your six") to "I've got your back",
        phrase("check your six") to "watch behind you",
        phrase("heads up") to "watch out",
        phrase("took you long enough") to "you're late",
        phrase("we've got company") to "enemies are coming",
        phrase("lock and load") to "get ready",
        phrase("sitting ducks") to "easy targets",
        phrase("moving in") to "going in",
    )

    /** Bilinen ifadeler düz İngilizceye çevrilir; cümle başındaki büyük harf korunur. */
    fun plain(sentence: String): String {
        var text = sentence
        for ((pattern, plain) in phrases)
            text = pattern.replace(text) { match -> if (match.value[0].isUpperCase()) plain[0].uppercaseChar() + plain.substring(1) else plain }
        return text
    }
}
