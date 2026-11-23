// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Oyunun renkli yazdığı sözcük ya da sözcük öbeği ("Go to the <b>plane</b>") ve rengi (#rrggbb). */
data class ColoredPhrase(val text: String, val color: String)

/**
 * Oyunlar görev yazısında önemli sözcüğü renkli yazar (GTA 6: yer ve nesne pembe, kişi mavi). Ek alanın okuyucusu
 * sözcüklerin rengini görüntüden bulur; çeviride o sözcüğün karşılığı aynı renkte gösterilir. TV'ye giden metinde renkli
 * parça `⁅#rrggbb|metin⁆` biçimindedir (yalnız ek alanlarda; altyazıda kullanılmaz). Windows: ColoredWords.cs.
 */
object ColoredWords {
    const val OPEN = '⁅'
    const val DIVIDER = '|'
    const val CLOSE = '⁆'
    private val turkish = Locale("tr", "TR")
    private val marked = Regex("⁅#[0-9a-fA-F]{6}\\|([^⁆]*)⁆")

    /**
     * Sözcüğün yazı rengi: kutudaki en parlak piksellerin (yazının kendisi; zemin ve gölge koyudur) ortalaması. Beyaz ya
     * da gri yazıysa null.
     */
    fun colorOf(image: PixelImage, box: TextBox): String? {
        val x0 = (box.left * image.width).toInt().coerceIn(0, image.width)
        val x1 = ceil(box.right * image.width).toInt().coerceIn(0, image.width)
        val y0 = (box.top * image.height).toInt().coerceIn(0, image.height)
        val y1 = ceil(box.bottom * image.height).toInt().coerceIn(0, image.height)
        if (x1 - x0 < 2 || y1 - y0 < 2) return null
        var top = 0.0
        for (y in y0 until y1) for (x in x0 until x1) top = max(top, image.luma(x, y))
        if (top < 0.35) return null
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var count = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            if (image.luma(x, y) < top * 0.7) continue
            b += image.blue(x, y)
            g += image.green(x, y)
            r += image.red(x, y)
            count++
        }
        if (count < 6) return null
        r /= count * 255.0
        g /= count * 255.0
        b /= count * 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        if (hi < 0.35 || (hi - lo) / hi < 0.35) return null
        // Renk canlı gösterilsin diye en parlak kanal 255'e çekilir.
        fun channel(value: Double) = String.format(Locale.ROOT, "%02x", Math.rint((value / hi).coerceIn(0.0, 1.0) * 255).toInt())
        return "#${channel(r)}${channel(g)}${channel(b)}"
    }

    /** Satırdaki renkli sözcükler; yan yana ve aynı renkteki sözcükler tek öbektir ("sea plane"). */
    fun phrases(words: List<OcrWord>, image: PixelImage): List<ColoredPhrase> {
        val result = mutableListOf<ColoredPhrase>()
        val run = mutableListOf<String>()
        var runColor: String? = null
        fun flush() {
            val color = runColor
            if (run.isNotEmpty() && color != null) result.add(ColoredPhrase(run.joinToString(" "), color))
            run.clear()
            runColor = null
        }
        for (word in words) {
            // Tek harf (yanlış okunmuş renkli tuş simgesi: "e") renkli sözcük sayılmaz.
            val color = if (word.text.count { it.isLetterOrDigit() } >= 2) colorOf(image, word.box) else null
            if (color == null) {
                flush()
                continue
            }
            if (runColor != null && !sameHue(runColor!!, color)) flush()
            if (runColor == null) runColor = color
            run.add(word.text.trim('.', ',', '!', '?', ':', ';'))
        }
        flush()
        return result
    }

    private fun hue(hex: String): Double {
        val r = hex.substring(1, 3).toInt(16) / 255.0
        val g = hex.substring(3, 5).toInt(16) / 255.0
        val b = hex.substring(5, 7).toInt(16) / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val d = hi - lo
        if (d < 1e-6) return 0.0
        return if (hi == r) 60 * (((g - b) / d % 6 + 6) % 6) else if (hi == g) 60 * ((b - r) / d + 2) else 60 * ((r - g) / d + 4)
    }

    private fun sameHue(a: String, b: String): Boolean {
        val diff = abs(hue(a) - hue(b))
        return min(diff, 360 - diff) < 30
    }

    /**
     * Çeviride renkli öbeklerin karşılığını işaretler. Öbeğin tek başına çevirisi ("plane" → "uçak") cümlede aranır;
     * Türkçe ekler ve ünsüz yumuşaması için sözcük başının eşleşmesi yeter ("uçak" ~ "uçağa", "tekne" ~ "teknenin",
     * "Brian" ~ "Brian'ı"). Bulunamayan öbek renksiz kalır.
     */
    fun mark(translation: String, parts: List<Pair<String, String>>): String {
        val tokens = translation.split(' ')
        val colors = arrayOfNulls<String>(tokens.size)
        for ((translated, color) in parts) {
            val wanted = translated.split(' ').filter { it.isNotEmpty() }.map { core(it) }.filter { it.isNotEmpty() }
            if (wanted.isEmpty()) continue
            var i = 0
            while (i + wanted.size <= tokens.size) {
                if (wanted.indices.any { k -> colors[i + k] != null || !matches(core(tokens[i + k]), wanted[k]) }) {
                    i++
                    continue
                }
                for (k in wanted.indices) colors[i + k] = color
                break
            }
        }
        val result = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            if (i > 0) result.append(' ')
            val color = colors[i]
            if (color == null) {
                result.append(tokens[i])
                i++
                continue
            }
            // Aynı renkteki ardışık sözcükler tek parça olur.
            var end = i
            while (end + 1 < tokens.size && colors[end + 1] == color) end++
            result.append(OPEN).append(color).append(DIVIDER).append(tokens.subList(i, end + 1).joinToString(" ")).append(CLOSE)
            i = end + 1
        }
        return result.toString()
    }

    /** İşaretli metnin düz hâli. */
    fun plain(marked: String): String = if (marked.indexOf(OPEN) < 0) marked else this.marked.replace(marked, "$1")

    private fun core(token: String): String = token.lowercase(turkish).filter { it.isLetterOrDigit() }

    private fun matches(token: String, wanted: String): Boolean {
        if (token.isEmpty()) return false
        if (token == wanted) return true
        if (wanted.length <= 3) return token.startsWith(wanted)
        var common = 0
        while (common < token.length && common < wanted.length && token[common] == wanted[common]) common++
        return common >= max(3, wanted.length - 2)
    }
}

/**
 * Küçük yazıda yazı tanıma "m" harfini "rn" / "nn" / "rri" okuyor ("frorn your noailbox", "longarrn"). Sözlükte
 * olmayan sözcükte bu kalıplar "m" yapılır; sonuç sözlükteyse düzeltme kabul edilir (Windows: LetterM).
 */
object LetterM {
    private val misreads = listOf("rn", "nn", "rri", "no")

    fun fix(text: String, isWord: ((String) -> Boolean)?): String {
        if (isWord == null || text.isEmpty()) return text
        val tokens = text.split(' ').toMutableList()
        var changed = false
        for (i in tokens.indices) {
            val token = tokens[i]
            var start = 0
            var end = token.length
            while (start < end && !token[start].isLetter()) start++
            while (end > start && !token[end - 1].isLetter()) end--
            if (end - start < 3) continue
            val word = token.substring(start, end)
            val lower = word.lowercase(Locale.ROOT)
            if (isWord(lower) || misreads.none { lower.contains(it) }) continue
            val repaired = fixed(lower, isWord) ?: continue
            var cased = if (word[0].isUpperCase()) repaired[0].uppercaseChar() + repaired.substring(1) else repaired
            if (word.all { !it.isLetter() || it.isUpperCase() }) cased = repaired.uppercase(Locale.ROOT)
            tokens[i] = token.substring(0, start) + cased + token.substring(end)
            changed = true
        }
        return if (changed) tokens.joinToString(" ") else text
    }

    /** Kalıpların bir ya da iki yerde "m" yapılmış hâllerinden sözlükte olan ilki. */
    private fun fixed(word: String, isWord: (String) -> Boolean): String? {
        val candidates = mutableListOf<String>()
        fun addAll(from: String) {
            for (misread in misreads) {
                var at = from.indexOf(misread)
                while (at >= 0) {
                    candidates.add(from.substring(0, at) + "m" + from.substring(at + misread.length))
                    at = from.indexOf(misread, at + 1)
                }
            }
        }
        addAll(word)
        for (once in candidates.toList()) addAll(once)
        return candidates.firstOrNull(isWord)
    }
}
