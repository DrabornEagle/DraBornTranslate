// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale

/**
 * Yazı tanımanın birbirine karıştırdığı harfleri onarır (Windows: OcrConfusions.cs, aynı kurallar). İnce yazı
 * tiplerinde "t" ve "f" sık sık "l", "o" sıfır, "m" de "rn" okunur ("Io make", "do nol disappear", "used lor attack",
 * "noise 01 your"); her karede aynı okunduğu için beklemek düzeltmez. Gerçek sözcük olmayan parçada bu harfler
 * değiştirilerek gerçek bir sözcük aranır; tek bir aday çıkarsa (ya da adaylardan yalnız biri çok sık kullanılan bir
 * sözcükse) parça onunla değiştirilir. Gerçek sözcüğe ve özel ada dokunulmaz.
 */
object OcrConfusions {
    /** Her zaman aynı biçimde bozulan çok kısa sözcükler (denetime gerek yok). */
    private val fixed = mapOf(
        "Io" to "to", "lo" to "to", "01" to "of", "0f" to "of", "0K" to "OK", "0k" to "ok",
        "lhe" to "the", "Ihe" to "the", "lt" to "It", "lf" to "If", "ls" to "Is",
        // Yuvarlak, ince yazı tiplerinde "m" "n", "t" "c", "e" "c" okunuyor ("ny love", "co do", "Wc won't").
        "ny" to "my", "Ny" to "My", "co" to "to", "Wc" to "We", "wc" to "we", "chis" to "this", "Chis" to "This",
    )

    /** Birden çok aday çıktığında tercih edilen, çok sık kullanılan sözcükler (büyük/küçük harfe bakılmaz). */
    private val common = setOf(
        "the", "to", "of", "for", "not", "that", "this", "with", "it", "at", "if", "off", "after", "often", "from", "first",
        "just", "last", "left", "let", "lot", "get", "got", "out", "but", "what", "there", "their", "they", "them", "then",
        "than", "time", "little", "better", "until", "still", "must", "most", "find", "fast", "soft", "take", "into",
        "want", "about", "attack", "fight", "right", "night", "light", "might", "enemy", "weapon", "from", "more", "come",
    )

    private val swaps = listOf(
        "l" to "t", "l" to "f", "I" to "l", "I" to "t", "0" to "o", "1" to "l", "rn" to "m", "vv" to "w", "cl" to "d",
        // Yuvarlak yazı tiplerinde: "m" → "nn" / "n" / "na" / "no" ("Tinne", "hinn", "nauch", "noy"), "t" → "c"
        // ("chis", "chat"), "e" → "c", "l" → "i" ("iove").
        "nn" to "m", "n" to "m", "na" to "m", "no" to "m", "c" to "t", "c" to "e", "i" to "l",
        // Köşeli yazı tiplerinde "a" "o" okunuyor ("Stoy where you are", "trosh").
        "o" to "a",
        // İnce, yüksek yazı tiplerinde "m" "Ili" / "lli" okunuyor ("Ilioney" → "money").
        "Ili" to "m", "lli" to "m",
    )

    /** İngilizcede bulunmayan, Türkçe okuyucunun ürettiği harfler. */
    private const val TURKISH_LETTERS = "İıŞşĞğÇçÖöÜü"

    /** Türkçe harfin İngilizce karşılıkları (Türkçe okuyucunun karıştırdıkları; ilk seçenek en olası). */
    private val turkishOptions = mapOf(
        'İ' to listOf("I"), 'ı' to listOf("i", "l"), 'Ş' to listOf("S"), 'ş' to listOf("s"), 'Ğ' to listOf("G"), 'ğ' to listOf("g"),
        'Ç' to listOf("C", "G"), 'ç' to listOf("c", "g"), 'Ö' to listOf("O"), 'ö' to listOf("o"), 'Ü' to listOf("U"), 'ü' to listOf("u"),
    )

    private val shortWords = setOf(
        "a", "i", "of", "to", "in", "on", "at", "is", "it", "we", "he", "me", "my", "be", "do", "go", "no", "so", "up", "us",
        "an", "as", "by", "if", "or", "oh", "ok", "am", "hi",
    )

    private fun upperFirst(word: String) = word.substring(0, 1).uppercase(Locale.ROOT) + word.substring(1)

    /** [fromTurkishLetters]: oyun dili İngilizce; Türkçe harfli parçalar İngilizceye çevrilir (Türkçe okuyucunun bozduğu sözcükler). */
    fun repair(input: String, isWord: ((String) -> Boolean)?, fromTurkishLetters: Boolean = false): String {
        if (isWord == null || input.isEmpty()) return input
        val line = spaceAfterPunctuation(input)
        val tokens = line.split(' ').toMutableList()
        var changed = false
        for (index in tokens.indices) {
            var token = tokens[index]
            var start = 0
            var end = token.length
            while (start < end && !token[start].isLetterOrDigit()) start++
            while (end > start && !token[end - 1].isLetterOrDigit()) end--
            // Tek başına "o" İngilizcede sözcük değildir; "a" harfidir ("You're o woman", "for o whirl").
            if (token == "o" && index > 0 && index + 1 < tokens.size && tokens[index + 1].isNotEmpty() && tokens[index + 1][0].isLetter()) {
                tokens[index] = "a"
                changed = true
                continue
            }
            // Cümle başında "I" "1" okunabiliyor ("1 learned a long time ago"): ardından küçük harfli gerçek sözcük gelirse.
            if (token == "1" && index + 1 < tokens.size && tokens[index + 1].length > 1 && tokens[index + 1][0].isLowerCase() &&
                isWord(tokens[index + 1].trimEnd('.', ',', '!', '?')) &&
                (index == 0 || tokens[index - 1].endsWith('.') || tokens[index - 1].endsWith('!') || tokens[index - 1].endsWith('?'))) {
                tokens[index] = "I"
                changed = true
                continue
            }
            // Türkçe okuyucu İngilizce sözcüğü Türkçe harflerle bozuyor ("thİnk", "hinı", "guyş"); İngilizce sözcüğe çevrilir.
            if (fromTurkishLetters && (token.substring(start, end).any { it in TURKISH_LETTERS } || (end < token.length && token[end] == 'ı'))) {
                val nextLower = index + 1 < tokens.size && tokens[index + 1].isNotEmpty() && tokens[index + 1][0].isLowerCase()
                val repaired = fromTurkishLetters(token, isWord, nextLower)
                if (repaired != token) {
                    tokens[index] = repaired
                    changed = true
                    token = repaired
                    start = 0
                    end = token.length
                    while (start < end && !token[start].isLetterOrDigit()) start++
                    while (end > start && !token[end - 1].isLetterOrDigit()) end--
                }
            }
            if (end - start < 2) continue
            val core = token.substring(start, end)
            // Sözcük arası boşlukları okunmamış parça ("Andthereismuchtodo") gerçek sözcüklere bölünür. Cümle ortasında
            // büyük harfle başlayan parça büyük olasılıkla addır ("Heywood"); bölünmez.
            val sentenceStart = index == 0 || tokens[index - 1].trimEnd('"', '\'', '’', '”').endsWith('.') ||
                tokens[index - 1].endsWith('!') || tokens[index - 1].endsWith('?') || tokens[index - 1].endsWith(':')
            if (sentenceStart || !core[0].isUpperCase()) {
                val split = gluedAroundHyphens(core, isWord)
                if (split != null) {
                    tokens[index] = token.substring(0, start) + split + token.substring(end)
                    changed = true
                    continue
                }
            }
            // Ünlem "l" okunabiliyor ("Ohl shit.", "Brotherl Brother!"): sözcüğün sonundaki "l" atılınca gerçek sözcük
            // kalıyorsa ve ardından yeni cümle başlıyorsa (satır sonu ya da büyük harf) "!" olur.
            val nextStarts = index == tokens.size - 1 || (tokens[index + 1].isNotEmpty() && tokens[index + 1][0].isUpperCase())
            if (end == token.length && core.length >= 3 && core.last() == 'l' && nextStarts && !isWord(core) && isWord(core.dropLast(1))) {
                tokens[index] = token.substring(0, start) + core.dropLast(1) + "!"
                changed = true
                continue
            }
            var fixedWord = fixed[core] ?: candidate(core, isWord, sentenceStart)
            if (fixedWord == null || fixedWord == core) continue
            // Cümle başındaki sözcük büyük harfle başlar ("Io make" satır başındaysa "To make").
            if (index == 0 && core[0].isUpperCase() && fixedWord[0].isLowerCase()) fixedWord = upperFirst(fixedWord)
            tokens[index] = token.substring(0, start) + fixedWord + token.substring(end)
            changed = true
        }
        return if (changed) tokens.joinToString(" ") else line
    }

    /**
     * Türkçe harfli parça (noktalamasıyla birlikte) İngilizceye: önce harf seçenekleri ve "nı"/"rı" → "m" ile gerçek
     * sözcük aranır ("hinı" → "him", "flyinç" → "flying", "thİnk" → "think"); sondaki "ı" sözcükten sonraysa okunmamış
     * virgüldür ("downı" → "down,"). Gerçek sözcük çıkmazsa harfler ilk seçenekle düzleştirilir ("Vercettı" → "Vercetti").
     */
    @Suppress("UNUSED_PARAMETER")
    private fun fromTurkishLetters(token: String, isWord: (String) -> Boolean, nextLower: Boolean): String {
        var start = 0
        var end = token.length
        while (start < end && !token[start].isLetterOrDigit()) start++
        while (end > start && !token[end - 1].isLetterOrDigit() && token[end - 1] != 'ı' && token[end - 1] != 'İ') end--
        val core = token.substring(start, end)
        val tail = token.substring(end)
        val head = token.substring(0, start)
        fun word(w: String) = w.isNotEmpty() && (isWord(w) || isWord(w.lowercase(Locale.ROOT)))
        fun hasTurkish(text: String) = text.any { it in TURKISH_LETTERS }

        // Sondaki "nı" / "rı" çoğunlukla "m"dir ("thenı" → "them", "hinı" → "him", "Sanı" → "Sam"); "m"li hâli sözcük
        // değilken "n" ile biten sözcük çıkıyorsa okunmamış virgüldür ("lionı" → "lion,").
        if (core.length >= 3 && core.last() == 'ı' && (core[core.length - 2] == 'n' || core[core.length - 2] == 'r') &&
            !hasTurkish(core.substring(0, core.length - 2))) {
            val withM = core.dropLast(2) + "m"
            return head + (if (!word(withM) && word(core.dropLast(1))) core.dropLast(1) + "," else withM) + tail
        }
        // Sondaki "ı": "downı helps" → "down, helps"; "brothersı Okay" → "brothers, Okay"
        if (core.length >= 3 && core.last() == 'ı' && !hasTurkish(core.dropLast(1)) && word(core.dropLast(1)) && !word(plain(core)))
            return head + core.dropLast(1) + "," + tail

        val candidates = mutableListOf<String>()
        fun expand(done: String, at: Int) {
            if (candidates.size >= 64) return
            if (at >= core.length) {
                candidates.add(done)
                return
            }
            val c = core[at]
            // "nı" / "rı" → "m" (Türkçe okuyucu "m"yi böyle bölüyor: "thenı", "Sanı", "hinı")
            if ((c == 'n' || c == 'r') && at + 1 < core.length && core[at + 1] == 'ı') expand(done + "m", at + 2)
            // Sözcük ortasındaki "İ" küçük "i"dir ("thİnk" → "think"); başta büyük "I" ("İt" → "It")
            val options = turkishOptions[c]
            if (c == 'İ' && at > 0) {
                expand(done + "i", at + 1)
                expand(done + "I", at + 1)
            } else if (options != null) {
                for (option in options) expand(done + option, at + 1)
            } else expand(done + c, at + 1)
        }
        expand("", 0)
        val found = candidates.firstOrNull { word(it) }
        return head + (found ?: plain(core)) + tail
    }

    private fun plain(text: String): String {
        val builder = StringBuilder(text.length)
        for (i in text.indices) {
            val c = text[i]
            builder.append(if (c == 'İ' && i > 0) "i" else turkishOptions[c]?.first() ?: c.toString())
        }
        return builder.toString()
    }

    /**
     * Noktalamadan sonra boşluğu okunmamış iki sözcük ("words,brother", "No.It") ayrılır. Nokta yalnız ardından büyük
     * harf geliyor ve önünde en az iki harf varsa (kısaltma: "U.S.") ayırır.
     */
    private fun spaceAfterPunctuation(line: String): String {
        val builder = StringBuilder(line.length + 4)
        for (i in line.indices) {
            builder.append(line[i])
            if (i == 0 || i + 1 >= line.length || !line[i - 1].isLetter() || !line[i + 1].isLetter()) continue
            val c = line[i]
            if (c == ',' || c == ';' || c == '!' || c == '?') builder.append(' ')
            // "Shadowheart:Try that" (konuşmacı adından sonra boşluk okunmamış).
            else if (c == ':' && line[i + 1].isUpperCase()) builder.append(' ')
            else if (c == '.' && line[i + 1].isUpperCase() && i >= 2 && line[i - 2].isLetter()) builder.append(' ')
        }
        return if (builder.length == line.length) line else builder.toString()
    }

    /** Tirelerle ayrılmış parçaların ("pod-theydidsomethingtoitwhen") her biri ayrı bölünür; hiçbiri bölünmezse null. */
    private fun gluedAroundHyphens(core: String, isWord: (String) -> Boolean): String? {
        if (!core.contains('-')) return glued(core, isWord)
        val parts = core.split('-').toMutableList()
        var any = false
        for (i in parts.indices) {
            val split = glued(parts[i], isWord) ?: continue
            parts[i] = split
            any = true
        }
        return if (any) parts.filter { it.isNotEmpty() }.joinToString(" - ") else null
    }

    /**
     * Boşlukları okunmamış birkaç sözcük: parça gerçek sözcük değilse ve tamamı en az iki gerçek sözcüğe bölünebiliyorsa
     * en az parçalı bölünüşü. Tek harfli parça yalnız "a" ve "I" olabilir; sözcükler ortalama üç harften kısaysa
     * (rastgele harflerden kısa sözcük uydurmak kolaydır) bölünmez.
     */
    internal fun glued(core: String, isWord: (String) -> Boolean): String? {
        if (core.length < 7 || core.length > 60 || !core.all { it.isLetter() || it == '\'' || it == '’' } || isWord(core)) return null
        // "Shoulda", "kinda", "sorta": konuşma dili, yapışık sözcük değil.
        if (core.endsWith('a') && isWord(core.dropLast(1))) return null
        // Sonu düşmüş "-ing" ("bellowin'", "lookin'") konuşma dilidir, yapışık sözcük değil.
        if (core.endsWith("in") && isWord(core + "g")) return null
        val n = core.length
        val best = IntArray(n + 1) { Int.MAX_VALUE }
        val from = IntArray(n + 1)
        best[0] = 0
        for (end in 1..n) {
            for (start in maxOf(0, end - 14) until end) {
                if (best[start] == Int.MAX_VALUE || best[start] + 1 >= best[end]) continue
                val piece = core.substring(start, end)
                // Yazım denetimi "fi", "oy", "et" gibi parçaları da sözcük sayabilir; bir iki harfli parça yalnız çok
                // yaygın sözcüklerden olabilir (yoksa "Jackiefiet" "Jackie fi et" olur).
                if (if (piece.length <= 2) piece.lowercase(Locale.ROOT) !in shortWords else !isWord(piece)) continue
                if (piece[0] == '\'' || piece[0] == '’') continue
                best[end] = best[start] + 1
                from[end] = start
            }
        }
        if (best[n] == Int.MAX_VALUE || best[n] < 2 || n < best[n] * 3) return null
        // Büyük harfle başlayan iki parçalı ad ("Rayfield", "Heywood", "Blackwood") bölünmez.
        if (best[n] == 2 && core[0].isUpperCase()) return null
        val pieces = mutableListOf<String>()
        var end = n
        while (end > 0) {
            pieces.add(core.substring(from[end], end))
            end = from[end]
        }
        pieces.reverse()
        return pieces.joinToString(" ")
    }

    /** Parça gerçek sözcük değilse, karışan harfler değiştirilerek bulunan tek (ya da tek yaygın) gerçek sözcük. */
    private fun candidate(core: String, isWord: (String) -> Boolean, sentenceStart: Boolean = false): String? {
        if (core.length < 3 || core.length > 14 || !core.all { it.isLetterOrDigit() || it == '\'' || it == '’' }) return null
        if (!core.any { it.isLetter() } || isWord(core)) return null
        // Özel ad gibi duran parça (baş harfi büyük, gerisi küçük, rakamsız) yalnız içinde karışan harf dizisi varsa denenir.
        val proper = core[0].isUpperCase() && core.drop(1).all { it.isLowerCase() }
        // Cümle başında her sözcük büyük harfle başlar; orada denenir ("Stoy where you are" → "Stay").
        if (proper && !sentenceStart && !core.contains("rn") && !core.contains("nn") && core[0] != 'I') return null

        val found = LinkedHashSet<String>()
        var tries = 0
        fun attempt(text: String, depth: Int) {
            for ((from, to) in swaps) {
                var at = text.indexOf(from)
                while (at >= 0) {
                    val next = text.substring(0, at) + to + text.substring(at + from.length)
                    if (isWord(next)) found.add(next)
                    if (depth < 2 && found.size < 12 && ++tries < 400) attempt(next, depth + 1)
                    at = text.indexOf(from, at + 1)
                }
            }
        }
        attempt(core, 1)
        if (found.isEmpty()) return null
        if (found.size == 1) return found.first()
        val usual = found.filter { it.lowercase(Locale.ROOT) in common }
        return if (usual.size == 1) usual[0] else null
    }
}
