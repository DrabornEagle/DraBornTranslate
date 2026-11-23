// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import kotlin.math.abs

/**
 * Aynı altyazı satırının art arda karelerdeki okumalarını sözcük sözcük birleştirir (Windows: ReadingConsensus.cs,
 * aynı kurallar). Yazı tanımanın hatası kareden kareye değişir: bir karede "Slop! The pack is here", ötekinde
 * "Stop! The pock is here" okunur; hiçbiri tam doğru değildir ama her sözcüğün doğrusu okumalardan birinde vardır. En
 * iyi okuma temel alınır; onun gerçek sözcük olmayan parçaları, aynı yerde gerçek sözcük okuyan başka bir okumadan
 * tamamlanır. Gerçek sözcüğe dokunulmaz ve sözcük sayısı farklı okumalar kullanılmaz (hizası belirsizdir); bu yüzden
 * birleştirme yeni hata üretmez.
 */
object ReadingConsensus {
    /**
     * [best]: temel alınan okuma. [readings]: aynı satırın bütün okumaları ([best] dahil olabilir). [isWord]: parça
     * gerçek bir sözcük mü (sözcük listesi); null ise birleştirme yapılmaz.
     */
    fun merge(best: String, readings: List<String>, isWord: ((String) -> Boolean)?): String {
        if (isWord == null || readings.size < 2) return best
        val rows = best.split('\n').toMutableList()
        val others = readings.filter { it != best }.map { it.split('\n') }.filter { it.size == rows.size }
        if (others.isEmpty()) return best
        var changed = false
        for (row in rows.indices) {
            val words = rows[row].split(' ').filter { it.isNotEmpty() }.toMutableList()
            val aligned = others.map { candidate -> candidate[row].split(' ').filter { it.isNotEmpty() } }.filter { it.size == words.size }
            if (aligned.isEmpty()) continue
            for (index in words.indices) {
                if (isGood(words[index], isWord)) continue
                // Aynı yerde gerçek sözcük okuyan okumalardan en çok tekrar edeni (eşitlikte ilk görülen).
                val better = aligned.map { it[index] }.filter { isGood(it, isWord) && close(it, words[index]) }
                    .groupingBy { it }.eachCount().entries.maxByOrNull { it.value }?.key ?: continue
                words[index] = better
                changed = true
            }
            rows[row] = words.joinToString(" ")
        }
        return if (changed) rows.joinToString("\n") else best
    }

    /** Parçanın harf kısmı gerçek bir sözcük mü; harfsiz parçalar (sayı, işaret) ve özel adlar iyi sayılır. */
    private fun isGood(token: String, isWord: (String) -> Boolean): Boolean {
        var start = 0
        var end = token.length
        while (start < end && !token[start].isLetter()) start++
        while (end > start && !token[end - 1].isLetter()) end--
        val word = token.substring(start, end)
        if (word.count { it.isLetter() } < 2) return true
        val rest = word.drop(1)
        val proper = word[0].isUpperCase() && rest.filter { it.isLetter() }.all { it.isLowerCase() }
        val acronym = word.all { !it.isLetter() || it.isUpperCase() }
        return acronym || isWord(word) || (proper && rest.none { !it.isLetter() && it != '\'' && it != '’' && it != '-' })
    }

    /** İki parça aynı sözcüğün okumaları olacak kadar yakın mı (boyları ve harfleri benzer). */
    private fun close(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 2) return false
        val x = TextMatch.normalize(a)
        val y = TextMatch.normalize(b)
        return x.isNotEmpty() && y.isNotEmpty() && TextMatch.isSimilar(x, y, 0.5)
    }
}
