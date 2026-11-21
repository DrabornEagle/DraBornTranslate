// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.util.Locale

private val englishUnits = "seconds?|minutes?|hours?|days?|weeks?|months?|years?|dollars?|euros?|" +
    "meters?|metres?|kilometers?|kilometres?|miles?|blocks?|cars?|guards?|rounds?|shots?|points?|keys?|people"
private val turkishUnits = "saniye|dakika|saat|gün|hafta|ay|yıl|dolar|avro|euro|metre|kilometre|mil|" +
    "araba|araç|nöbetçi|muhafız|raunt|atış|mermi|puan|anahtar|kişi"
private val englishLocale = Locale.ENGLISH
private val turkishLocale = Locale.forLanguageTag("tr")
private val englishCardinals = cardinals(
    "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen",
    "twenty thirty forty fifty sixty seventy eighty ninety",
)
private val turkishCardinals = cardinals("sıfır bir iki üç dört beş altı yedi sekiz dokuz",
    "on yirmi otuz kırk elli altmış yetmiş seksen doksan")
private val sourceQuantities = quantityPattern(englishCardinals, englishUnits)
private val targetQuantities = quantityPattern(turkishCardinals, "(?:$turkishUnits)\\p{L}{0,8}")
private val largerQuantity = Regex("(?:hundred|thousand|million)(?: and)? +$", RegexOption.IGNORE_CASE)
private val digits = Regex("\\d+(?:[.,:/]\\d+)*")
private val groupedThousands = Regex("\\d{1,3}(?:[.,]\\d{3})+")

private fun cardinals(smallWords: String, tensWords: String): Map<String, Int> {
    val small = smallWords.split(' ')
    val tens = tensWords.split(' ')
    return buildMap {
        small.forEachIndexed { number, word -> put(word, number) }
        tens.forEachIndexed { index, word ->
            val number = 100 - tens.size * 10 + index * 10
            put(word, number)
            for (unit in 1..9) put("$word ${small[unit]}", number + unit)
        }
    }
}

private fun quantityPattern(cardinals: Map<String, Int>, units: String): Regex {
    val names = cardinals.keys.sortedByDescending(String::length)
        .joinToString("|") { it.replace(" ", "[- ]") }
    return Regex("(?<![\\p{L}\\p{N}])(?:$names)(?= +(?:$units)\\b)", RegexOption.IGNORE_CASE)
}

internal fun digitizeSubtitleQuantities(text: String): String = sourceQuantities.replace(text) {
    val word = it.value.lowercase(englishLocale).replace('-', ' ')
    if (largerQuantity.containsMatchIn(text.substring(0, it.range.first))) it.value
    else englishCardinals[word]?.toString() ?: it.value
}

internal fun preservesSubtitleNumbers(source: String, translation: String): Boolean {
    val expected = numberCounts(digitizeSubtitleQuantities(source))
    if (expected.isEmpty()) return true
    val candidate = targetQuantities.replace(translation) {
        val word = it.value.lowercase(turkishLocale).replace('-', ' ')
        turkishCardinals[word]?.toString() ?: it.value
    }
    val translated = numberCounts(candidate)
    return expected.all { (value, count) -> (translated[value] ?: 0) >= count } &&
        numberCounts(translation).keys.all(expected::containsKey)
}

private fun numberCounts(text: String): Map<String, Int> = digits.findAll(text).map { match ->
    val value = match.value
    if (groupedThousands.matches(value)) value.replace(".", "").replace(",", "")
    else value.replace(',', '.').replace(':', '.')
}.groupingBy { it }.eachCount()
