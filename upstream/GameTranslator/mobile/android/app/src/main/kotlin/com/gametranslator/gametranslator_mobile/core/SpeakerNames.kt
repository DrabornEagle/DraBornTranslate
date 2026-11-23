// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale
import kotlin.math.abs

/**
 * Konuşmacı adlarını oyundan öğrenir ve bozuk okunmuş adı doğrusuna çevirir (Windows: SpeakerNames.cs, aynı kurallar).
 * Adlar sözlükte olmadığı için sözcük denetimi "Minnir" ile "Mimir"i ayıramaz; ama oyun aynı adı onlarca kez gösterir,
 * yazı tanıma çoğunda doğru okur. Sık görülen ad bilinen addır; ilk kez (ya da ikinci kez) görülen ve bilinen bir ada
 * çok benzeyen ad onun bozuk okumasıdır ("Minnir", "Mimnir", "Mimire" → "Mimir"; "Kracos" → "Kratos"). Birbirine
 * benzeyen iki gerçek ad ("Maria", "Marie") ikisi de yeterince görülünce ayrı kalır.
 *
 * Bilgisayarda adı henüz doğrulanmamış satır ikinci, güçlü bir okuyucuyla da okunur ([confirm], [needsCheck]); telefonda
 * tek okuyucu (ML Kit) vardır, bu iki yöntem uyumluluk için durur ve hatta bağlı değildir.
 */
class SpeakerNames {
    companion object {
        /** Bir ad bu kadar görülünce bilinen ad olur. */
        const val KNOWN_AFTER = 3
        /** Bilinen ad, benzer addan en az bu kat sık görülmeliyse onun doğrusu sayılır. */
        const val RATIO = 3
        /** Güçlü okuyucu bir adı bu kadar okuyunca ad doğrulanmış sayılır. */
        const val CONFIRMED_AFTER = 2

        /** "Aloy: Let's go." → "Aloy"; en çok üç sözcük, baş harfi büyük. */
        internal fun label(text: String): String? {
            val line = text.trimStart()
            val colon = line.indexOf(": ")
            if (colon < 2 || colon > 30 || !line[0].isUpperCase()) return null
            val name = line.substring(0, colon)
            return if (name.split(' ').size <= 3 && name.all { it.isLetter() || it == ' ' || it == '-' || it == '.' || it == '\'' || it == '’' }) name else null
        }

        /**
         * Büyük harfle yazılan adda küçük okunan harfler büyütülür: "GAETANo" → "GAETANO", "ENzo" → "ENZO", "DAMiR" →
         * "DAMIR". İlk iki harfi büyük olan (en az üç harfli) sözcük büyük harfli sayılır; "Mimir", "McKay" dokunulmaz.
         */
        fun fixCaps(name: String): String {
            if (name.none { it.isLowerCase() }) return name
            val words = name.split(' ').toMutableList()
            var changed = false
            for (i in words.indices) {
                val letters = words[i].filter { it.isLetter() }
                if (letters.length < 3 || !letters[0].isUpperCase() || !letters[1].isUpperCase() || letters.none { it.isLowerCase() }) continue
                words[i] = words[i].uppercase(Locale.ROOT)
                changed = true
            }
            return if (changed) words.joinToString(" ") else name
        }

        /**
         * Satır başında art arda iki ad varsa öndeki atılır: "HSS: GAETANO: Worries are for tomorrow." → "GAETANO: …".
         * Öndeki, sahnedeki dokudan okunup ayrı satırda ad sanılan çöptür; gerçek altyazıda iki ad yan yana gelmez.
         */
        fun withoutLeadingJunkLabel(line: String): String {
            val first = label(line) ?: return line
            if (first.length > 12) return line
            val start = line.indexOf("$first: ")
            if (start < 0) return line
            val rest = line.substring(start + first.length + 2).trimStart()
            val second = label(rest)
            return if (second != null && second != first) rest else line
        }

        /** Satırın başındaki konuşmacı adı [fixCaps] ile düzeltilmiş hâli ("ENzo: Here." → "ENZO: Here."). */
        fun fixLabelCaps(line: String): String {
            val name = label(line) ?: return line
            val fixedName = fixCaps(name)
            if (fixedName == name) return line
            val start = line.indexOf(name)
            return line.substring(0, start) + fixedName + line.substring(start + name.length)
        }

        /** Aynı sayıda sözcük ve harflerin çoğu aynı (uzunluk farkı en çok iki). */
        private fun alike(read: String, known: String): Boolean {
            if (known.length < 4 || abs(read.length - known.length) > 2) return false
            if (read.count { it == ' ' } != known.count { it == ' ' }) return false
            return TextMatch.isSimilar(read.lowercase(Locale.ROOT), known.lowercase(Locale.ROOT), 0.6)
        }
    }

    private val seen = LinkedHashMap<String, Int>()
    /** Güçlü okuyucunun okuduğu adlar ve kaç kez okuduğu. */
    private val confirmed = LinkedHashMap<String, Int>()
    private val lock = Any()

    /** Güçlü okuyucunun okumasındaki (satırlar) konuşmacı adlarını kaydeder; onun okuduğu ad, benzer okumaların doğrusudur. */
    fun confirm(lines: List<String>) = synchronized(lock) {
        for (line in lines) {
            val read = label(line) ?: continue
            val name = fixCaps(read)
            confirmed[name] = (confirmed[name] ?: 0) + 1
        }
        if (confirmed.size > 300) confirmed.clear()
    }

    /** Okumanın konuşmacı adı henüz doğrulanmadı mı (öyleyse satır güçlü okuyucuyla da okunmalı). */
    fun needsCheck(text: String): Boolean {
        val name = label(text.split('\n')[0]) ?: return false
        return synchronized(lock) { confirmedName(name) == null }
    }

    /** Ad (ya da benzeri) doğrulandı ya da oyunda yeterince görüldü. */
    fun isKnown(name: String): Boolean = synchronized(lock) {
        confirmedName(name) != null || seen.any { (key, count) -> count >= KNOWN_AFTER && (key == name || alike(name, key)) }
    }

    /** Adın doğrulanmış hâli: kendisi doğrulandıysa kendisi, değilse en çok okunan benzer doğrulanmış ad. */
    private fun confirmedName(name: String): String? {
        if ((confirmed[name] ?: 0) >= CONFIRMED_AFTER) return name
        var best: String? = null
        var bestCount = CONFIRMED_AFTER - 1
        for ((known, count) in confirmed) if (count > bestCount && alike(name, known)) { best = known; bestCount = count }
        return best
    }

    /** Adı kaydeder ve doğrusunu döner (bilinen bir adın bozuk okumasıysa o ad, değilse kendisi). */
    fun canonical(read: String): String {
        val name = fixCaps(read)
        synchronized(lock) {
            confirmedName(name)?.let { sure ->
                seen[sure] = (seen[sure] ?: 0) + 1
                return sure
            }
            seen[name] = (seen[name] ?: 0) + 1
            if (seen.size > 300) forget()
            if (name.length < 4) return name
            var best: String? = null
            var bestCount = 0
            val own = seen[name] ?: 0
            for ((known, count) in seen) {
                if (count < KNOWN_AFTER || count <= bestCount || known == name) continue
                if (count < own * RATIO || !alike(name, known)) continue
                best = known
                bestCount = count
            }
            return best ?: name
        }
    }

    /**
     * Satır başındaki parça, iki nokta üst üstesi okunmamış bilinen bir ad mı: adın ardına bir iki harf yapışmış
     * ("Stintsy Fuck": ":" harf okunmuş) ya da ada çok benzeyen ve ardından büyük harfle başlayan söz gelen parça
     * ("Jockte Hey..."). Öyleyse bilinen ad; değilse null. Adın kendisine (ad sözün içindeyse: "Jackie is here") dokunulmaz.
     */
    fun labelIn(first: String, next: String?): String? {
        val token = first.trimEnd('.', ',', ';', ':')
        if (token.length < 3 || !token[0].isUpperCase()) return null
        val names = synchronized(lock) {
            (seen.filter { it.value >= KNOWN_AFTER }.keys + confirmed.filter { it.value >= CONFIRMED_AFTER }.keys).distinct()
        }
        for (known in names) {
            // Büyük harfle yazılan bilinen ad ayraçsız da gelebilir ("BODE Using the Empire's tools"): ardından büyük harfle
            // başlayan söz geliyorsa addır. Baş harfi büyük ad ise sözün içinde de geçebilir ("Jackie is here"), dokunulmaz.
            if (token == known && known.length >= 3 && known.all { !it.isLetter() || it.isUpperCase() } &&
                !next.isNullOrEmpty() && next[0].isUpperCase())
                return known
            if (known.length < 4 || known.count { it == ' ' } != token.count { it == ' ' } || token == known) continue
            val extra = token.length - known.length
            if (token.startsWith(known) && (extra == 1 || extra == 2) && token.substring(known.length).all { it.isLowerCase() }) return known
            if (token.length >= 5 && !next.isNullOrEmpty() && next[0].isUpperCase() && alike(token, known)) return known
        }
        return null
    }

    /** Bir kez görülmüş adlar (çoğu bozuk okuma) atılır. */
    private fun forget() {
        seen.entries.removeAll { it.value < 2 }
    }
}
