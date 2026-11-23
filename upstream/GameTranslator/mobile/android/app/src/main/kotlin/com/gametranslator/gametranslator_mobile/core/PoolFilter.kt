// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Ortak havuza gidecek satırın süzgeci: havuz yalnız pakette olmayan İngilizce oyun konuşmalarını toplamalı (PS5'in
 * Türkçe arayüzü, yarım okumalar ve oyun arayüzü elenir). Windows'taki PoolFilter.cs ve sunucudaki
 * server/pool/src/filter.js ile aynı kurallar.
 */
object PoolFilter {
    /** Bundan kısa ya da daha az sözcüklü satır ancak cümle noktalamasıyla bitiyorsa alınır ("Get down!"). */
    const val MIN_SHORT_LENGTH = 15
    const val MIN_SHORT_WORDS = 3
    /** Tümü büyük harfli, noktalamasız ve bu kadar ya da daha az sözcüklü satır arayüz yazısıdır. */
    const val MAX_CAPS_UI_WORDS = 6

    /** Satır İngilizce oyun konuşmasına benziyor mu (Türkçe, sistem yazısı ya da yarım okuma değil). */
    fun accepts(text: String): Boolean = !isFragment(text) && !looksTurkish(text) && !looksLikeSystemText(text)

    internal val danglingWords = setOf(
        "the", "a", "an", "to", "of", "and", "or", "but", "with", "for", "from", "my", "your", "his", "her", "their", "our",
        "at", "in", "on", "by", "into", "than", "that", "if", "as", "is", "are", "was", "were",
    )

    /** Yarım ya da arayüz okuması (kısa ve noktalamasız, küçük harfle başlayan, bağlaçla biten, kısa BÜYÜK HARF). */
    internal fun isFragment(raw: String): Boolean {
        val text = raw.trim()
        val words = text.split(Regex("\\s+")).filter { w -> w.any { it.isLetter() } }
        val ends = SubtitleStabilizer.endsSentence(text)
        if (!ends && (words.size < MIN_SHORT_WORDS || text.codePointCount(0, text.length) < MIN_SHORT_LENGTH)) return true
        if (text.isNotEmpty() && text[0].isLetter() && text[0].isLowerCase()) return true
        if (ends) return false
        if (words.isNotEmpty() && words.last().lowercase(Locale.ROOT).filter { it.isLetter() } in danglingWords) return true
        val letters = text.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.none { it.isLowerCase() } && words.size <= MAX_CAPS_UI_WORDS
    }

    private const val TURKISH_ONLY = "ğĞşŞıİ"
    private const val TURKISH_SHARED = "üÜöÖçÇ"

    internal val turkishWords: List<String> = listOf(
        "icin", "lutfen", "bekleyin", "oyunu", "oyun", "oyunlar", "kapat", "ekran", "ekranda", "ekrani", "sistemini",
        "yeniden", "baslat", "ayarlar", "ayarlari", "kupalar", "kupa", "oturum", "acti", "kapatti", "secenekleri",
        "sifirlama", "tut", "kesfet", "ilgili", "takip", "edin", "ilerlemenizi", "cikanlar", "goruntusu", "kaydedildi",
        "bildirimler", "arkadaslar", "medya", "galerisi", "indiriliyor", "guncelleme", "kitaplik", "denetleyici",
    ).map { fold(it).joinToString("") }

    internal fun looksTurkish(text: String): Boolean {
        if (text.any { it in TURKISH_ONLY }) return true
        if (text.count { it in TURKISH_SHARED } >= 2) return true
        return fold(text).count { word -> turkishWords.any { isClose(word, it) } } >= 2
    }

    internal val systemPhrases: List<String> = listOf(
        "signed in", "signed out", "earned a trophy", "trophy earned", "trophy unlocked", "screenshot saved",
        "video clip saved", "media gallery", "is now online", "joined the party", "left the party", "remote play",
        "controller battery", "controller is disconnected", "controller disconnected", "press the ps button",
        "playstation network", "download complete", "installation complete", "update available", "turn off your console",
        "cloud save", "do not quit", "photo mode", "story mode", "press any button",
        "oturum acti", "oturum kapatti", "ekran ve video", "oyunu kapat", "ana ekranda tut", "yeniden baslat",
        "sifirlama secenekleri", "lutfen bekleyin", "ilerlemenizi takip",
    ).map { fold(it).joinToString(" ") }

    internal fun looksLikeSystemText(text: String): Boolean {
        val padded = " " + fold(text).joinToString(" ") + " "
        return systemPhrases.any { padded.contains(" $it ") }
    }

    /** Küçük harf, aksansız, yazı tanımanın karıştırdığı harfler birleşik (q→c, l ve 1→i); yalnız eşleştirme içindir. */
    internal fun fold(text: String): List<String> {
        val plain = Normalizer.normalize(text.lowercase(Locale.ROOT).replace('ı', 'i'), Normalizer.Form.NFD)
        val builder = StringBuilder(plain.length)
        for (c in plain) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            builder.append(when (c) { 'q' -> 'c'; 'l', '1' -> 'i'; else -> c })
        }
        val words = ArrayList<String>()
        val word = StringBuilder()
        for (c in builder) {
            if (c.isLetterOrDigit()) word.append(c)
            else if (word.isNotEmpty()) { words.add(word.toString()); word.setLength(0) }
        }
        if (word.isNotEmpty()) words.add(word.toString())
        return words
    }

    /** Aynı sözcük mü: altı ve daha uzun harflide bir harf farkına izin verilir. */
    internal fun isClose(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 6 || b.length < 6 || abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
