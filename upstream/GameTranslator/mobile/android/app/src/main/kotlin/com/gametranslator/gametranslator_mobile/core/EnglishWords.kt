// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Yaygın İngilizce sözcükler (SCOWL/ESDB 60 boyutu, küçük harf): bilgisayar uygulamasının english-words.txt.gz
 * dosyasının kendisi (Windows: WindowsSpelling.EnglishListCheck, aynı kurallar). Okuma onarımı ([OcrConfusions]) ve
 * okumaların birleştirilmesi ([ReadingConsensus]) bir parçanın gerçek sözcük olup olmadığını buna sorar.
 * Telefonda bellek az: ~93 bin sözcük tek bir bayt bloğunda sıralı tutulur (~0,8 MB), ikili aramayla bulunur.
 */
class EnglishWords private constructor(private val blob: ByteArray, private val starts: IntArray) {
    val size: Int get() = starts.size - 1

    /** Sözcük listede mi ("'s", sonu düşmüş "-ing" ve tireli parçalar dahil). */
    fun isWord(word: String): Boolean {
        val lower = word.lowercase(Locale.ROOT).replace('’', '\'')
        if (contains(lower)) return true
        if (lower.endsWith("'s") && contains(lower.dropLast(2))) return true
        if (lower.endsWith("s'") && contains(lower.dropLast(1))) return true
        if (lower.endsWith("in'") && contains(lower.dropLast(1) + "g")) return true
        if (lower.endsWith("in") && lower.length > 4 && contains(lower + "g")) return true
        if (lower.contains('-')) return lower.split('-').all { it.isEmpty() || contains(it) }
        return false
    }

    /** Tam eşleşme (küçük harfli liste). */
    fun contains(word: String): Boolean {
        if (word.isEmpty()) return false
        val key = word.toByteArray(Charsets.UTF_8)
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val order = compare(middle, key)
            if (order == 0) return true
            if (order < 0) low = middle + 1 else high = middle - 1
        }
        return false
    }

    private fun compare(index: Int, key: ByteArray): Int {
        val start = starts[index]
        val length = starts[index + 1] - start
        val common = minOf(length, key.size)
        for (i in 0 until common) {
            val a = blob[start + i].toInt() and 0xFF
            val b = key[i].toInt() and 0xFF
            if (a != b) return a - b
        }
        return length - key.size
    }

    companion object {
        /** Satır satır sözcük listesi; gzip'liyse açılır (AGP varlıktaki .gz'yi açıp koyabiliyor). */
        fun load(input: InputStream): EnglishWords {
            val buffered = BufferedInputStream(input)
            buffered.mark(2)
            val gzip = buffered.read() == 0x1F && buffered.read() == 0x8B
            buffered.reset()
            val stream = if (gzip) GZIPInputStream(buffered) else buffered
            val words = stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.map { it.toByteArray(Charsets.UTF_8) }.toMutableList()
            }
            return of(words)
        }

        fun of(words: Collection<String>): EnglishWords = of(words.map { it.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8) }.toMutableList())

        private fun of(words: MutableList<ByteArray>): EnglishWords {
            words.sortWith { a, b ->
                val common = minOf(a.size, b.size)
                var order = 0
                for (i in 0 until common) {
                    order = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                    if (order != 0) break
                }
                if (order != 0) order else a.size - b.size
            }
            val starts = IntArray(words.size + 1)
            var total = 0
            for (i in words.indices) {
                starts[i] = total
                total += words[i].size
            }
            starts[words.size] = total
            val blob = ByteArray(total)
            for (i in words.indices) System.arraycopy(words[i], 0, blob, starts[i], words[i].size)
            return EnglishWords(blob, starts)
        }
    }
}
