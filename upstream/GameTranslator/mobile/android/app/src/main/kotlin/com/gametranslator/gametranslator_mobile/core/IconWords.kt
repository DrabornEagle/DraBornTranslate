// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale

/** Yazı tanımanın verdiği bir sözcük ve görüntüdeki yeri (0–1, üstten). */
data class OcrWord(val text: String, val box: TextBox)

/** Yazı tanımanın verdiği bir satır, yeri ve sözcükleri (yerleriyle; simge ve renk tanıma için). */
data class OcrLine(val text: String, val box: TextBox?, val words: List<OcrWord>)

/**
 * Oyunlar düğmeyi yazının ortasına küçük bir resim olarak koyar ("Hold ⓧ to lock on"). Yazı tanıma o resmi ya hiç
 * okumaz (sözcükler arasında simge genişliğinde boşluk kalır) ya da "•", "(x)", "L", "O" gibi tek bir karakter sanır.
 * İkisinde de o parça görüntüden tanınıp ([ButtonIcons]) doğru düğmeyle değiştirilir ("Hold □ to lock on").
 * Windows: IconWords.cs (Nino Game Translator'ın AGPL-3.0 VisionReader.withIcons / withEdgeIcons uyarlaması).
 */
object IconWords {
    private val verbs = setOf("press", "hold", "tap", "push", "use", "release", "mash")

    private fun upper(text: String) = text.uppercase(Locale.ROOT)
    private fun isVerb(text: String) = text.lowercase(Locale.ROOT) in verbs

    /**
     * Satırın sözcüklerinden simgeli metni kurar. [classify] görüntüdeki bir yerdeki simgeyi tanır (tanınamazsa null).
     * [width] / [height]: görüntünün piksel boyutu (boşluk satır yüksekliğiyle piksel cinsinden karşılaştırılır).
     * Değişiklik yoksa [text] aynen döner.
     */
    fun withIcons(text: String, words: List<OcrWord>, width: Double, height: Double, classify: (TextBox) -> String?): String {
        if (words.isEmpty() || (words.size == 1 && !mayBeIcon(words[0].text))) return text
        val top = words.minOf { it.box.top }
        val bottom = words.maxOf { it.box.bottom }
        val lineHeight = (bottom - top) * height
        // Büyük harfli eylem satırı ("SET DESTINATION e"): son sözcük dışında her şey büyük harf; sondaki tek harf (küçük
        // de olsa) simgenin yanlış okunmuş hâlidir (GTA 6'nın tuş çubuğunda ✕ "e" okunuyor). Tek harfli parçalar
        // (yanlış okunmuş simgeler: "OFFER RIDE e LUCIA") büyük harf denetimine katılmaz.
        val head = words.take(maxOf(1, words.size - 1)).map { it.text }.filter { it.length > 1 }.joinToString(" ")
        val shouting = head.any { it.isLetter() } && head == upper(head)
        val output = mutableListOf<String>()
        var changed = false
        for (i in words.indices) {
            val item = words[i]
            if (i > 0) {
                val left = words[i - 1].box
                val gap = (item.box.left - left.right) * width
                if (gap > lineHeight * 0.9 && buttonContext(output.lastOrNull(), item.text, shouting)) {
                    // Boşlukta simge bulunamazsa hiçbir şey eklenmez.
                    classify(TextBox(left.right, top, item.box.left, bottom))?.let { found ->
                        output.add(found)
                        changed = true
                    }
                }
            }
            // Büyük harfli eylem satırının sonundaki tek harf ("MOUNT HORSE A") simgedir (△ "A" okunuyor).
            val trailingLetter = i == words.size - 1 && i > 0 && shouting &&
                (item.text.length == 1 || item.text in setOf("11", "12", "13", "21", "22", "23"))
            // "Hold A to …": fiilden sonraki tek büyük harf de simge olabilir.
            val afterVerb = i > 0 && item.text.length == 1 && item.text == upper(item.text) && isVerb(words[i - 1].text)
            // Büyük harfli satırın içindeki tek küçük harf de simgedir ("OFFER RIDE △ e LUCIA": ✕ "e" okunuyor).
            val others = words.filterIndexed { k, _ -> k != i }.joinToString(" ") { it.text }
            val lowerInCaps = item.text.length == 1 && item.text[0].isLowerCase() && others.any { it.isLetter() } && others == upper(others)
            val suspicious = mayBeIcon(item.text) || trailingLetter || afterVerb || lowerInCaps
            val context = suspicious && buttonContext(output.lastOrNull(), if (i + 1 < words.size) words[i + 1].text else null, shouting)
            val icon = if (context) classify(TextBox(item.box.left, top, item.box.right, bottom)) else null
            if (icon != null) {
                output.add(icon)
                changed = true
            } else if (suspicious && item.text !in setOf("+", "/", "&") &&
                (item.text.none { it.isLetterOrDigit() } ||
                    // Cümlenin ortasında tek başına duran büyük harf ("Talk to O Brian"): tuşun yeri değilse bozuk okumadır.
                    (!context && !shouting && item.text.length == 1 && item.text[0].isUpperCase() && item.text != "A" && item.text != "I"))) {
                // Tanınmayan resim ("*", "•", "+"): düğme değildir, atılır.
                changed = true
            } else {
                output.add(item.text)
            }
        }
        return if (changed) normalizedIcons(output.joinToString(" ")) else text
    }

    /**
     * Tuş bu yerde olabilir mi: fiilden sonra ("Press □", "Hold L2"), "or / and / +" ile ("□ or ○"), "to"dan önce
     * ("□ to aim") ya da büyük harfli tuş çubuğunda ("SELECT ✕"). Sıradan cümlede adın ardındaki simge haritadaki
     * işarettir ("your personal vehicle ◆ for easy access"; GTA 6'nın ipucu kutusu) ve satıra eklenmez.
     */
    private fun buttonContext(previous: String?, next: String?, shouting: Boolean): Boolean {
        if (shouting) return true
        fun word(text: String?) = (text ?: "").trim('.', ',', '!', '?', ':', ';', '(', ')').lowercase(Locale.ROOT)
        val before = word(previous)
        val after = word(next)
        // Yanındaki simge yalnız gerçek simgeyse sayılır: yazı tanımanın okuduğu "O" / "X" sayılsaydı bozuk parçalar
        // birbirini onaylıyordu ("Talk to □ ○ Brian": renkli adın önündeki iki bozuk parça).
        fun glyph(token: String?) = token != null && ButtonPrompts.isIcon(token) && token.trim('(', ')', '[', ']').let { it != "O" && it != "X" }
        return previous == null || before in verbs || before in setOf("or", "and", "+", "/") || glyph(previous) ||
            // "or / and" sonrası tek başına yetmez: "your stash ◆ or by depositing" harita işaretidir. Sıradan cümlenin
            // sonu tek başına yetmez ("Follow ◆": renkli adın bozuk okuması); tuş çubuğunu "shouting" kapsar.
            after in setOf("to", "+", "/") || glyph(next)
    }

    /**
     * Satırın hemen solunda ve sağında simge genişliğinde bir alan görüntüden tanınır; simge varsa satıra eklenir
     * (görev ve ipucu satırlarında düğme çoğu zaman satırın başındadır: "□ Pick up").
     */
    fun withEdgeIcons(text: String, line: TextBox, width: Double, height: Double, classifyStrict: (TextBox) -> String?): String {
        val lineHeight = (line.bottom - line.top) * height
        if (lineHeight <= 4 || text.isEmpty()) return text
        val span = lineHeight * 1.8 / width
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        var result = text
        if (tokens.isNotEmpty() && !ButtonPrompts.isIcon(tokens[0]) && line.left - span > 0) {
            classifyStrict(TextBox(line.left - span, line.top, line.left - span * 0.1, line.bottom))?.let { before -> result = "$before $result" }
        }
        // Sağ kenarda tuş yalnız büyük harfli tuş çubuğunda ("SELECT ✕") ya da fiilden sonra ("Press ✕") aranır: sıradan
        // cümlenin ardındaki renkli ad (GTA 6: "Find" + pembe "Donnie", ad okunmamış) pembe □ sanılıyordu.
        val letters = tokens.filter { it.length > 1 }.joinToString("")
        val bar = letters.any { it.isLetter() } && letters == upper(letters)
        // Büyük rozet (GTA 6 Xbox'ta büyük harfli adın yanındaki "LT" dairesi satırın ~1,8 katı, Starfield'ın "TALK  Ⓐ"
        // dairesi ~2,5 katı ve biraz açıkta) dar alana sığmıyor: bulunamazsa üç kat yüksek, daha geniş alana bakılır.
        if (tokens.isNotEmpty() && !ButtonPrompts.isIcon(tokens.last()) && line.right + span < 1 &&
            (bar || isVerb(tokens.last().trim('.', ',', ':')))) {
            val grow = line.bottom - line.top
            val after = classifyStrict(TextBox(line.right + span * 0.1, line.top, line.right + span, line.bottom))
                ?: (if (line.right + span * 1.6 < 1) classifyStrict(TextBox(line.right + span * 0.1, line.top - grow, line.right + span * 1.6, line.bottom + grow)) else null)
                ?: (if (line.right + span * 2.2 < 1) classifyStrict(TextBox(line.right + span * 0.5, line.top - grow, line.right + span * 2.2, line.bottom + grow)) else null)
            if (after != null) result = "$result $after"
        }
        return if (result == text) text else normalizedIcons(result)
    }

    private val shoulders = setOf("L1", "L2", "L3", "R1", "R2", "R3")
    private val misreadShoulder = Regex("^[\\(\\[]?[LRC][0-9DIlZ|][\\)\\]]?$")

    /**
     * Simgenin yanlış okunmuş hali olabilecek parça: harfsiz kısa parça ("•", "(x)", "&amp;") ya da tek büyük harf
     * ("I" ve "A" gerçek sözcük olabildiği için hariç).
     */
    fun mayBeIcon(word: String): Boolean {
        // Yazıdaki bağlaç ve noktalama işaretleri simge değildir ("Chuck's Tackle & Shot": "&" ✕ sanılıyordu).
        if (word in setOf("&", "+", "/", "-", "–", "—", "|")) return false
        val core = word.trim('(', ')', '[', ']', '{', '}', '.', ',')
        // L1/R2 simgesinin yanlış okunmuş hali: "LD", "(C1", "Ll", "RZ".
        if (misreadShoulder.matches(word)) return true
        if (word.length <= 3 && word.startsWith('(') && word.endsWith(')')) return true
        if (core.isEmpty()) return word.length <= 3
        if (core.length <= 2 && core.none { it.isLetterOrDigit() }) return true
        if (core.length == 1 && core[0].isUpperCase() && core[0] != 'I' && core[0] != 'A') return true
        return ButtonPrompts.isIcon(core) && core.length <= 2 && core !in shoulders && core !in ButtonTokens.xboxNames
    }

    private val normalizers = listOf(
        Regex("(?<![\\p{L}\\p{N}_])[\\(\\[]?[1lI|]([123])[\\)\\]](?=\\s|$)") to "L$1",
        Regex("(?<![\\p{L}\\p{N}_])[\\(\\[][1lI|]([123])(?=\\s|$)") to "L$1",
        Regex("(?<![\\p{L}\\p{N}_])[\\(\\[]?([LR])([123])[\\)\\]](?=\\s|$)") to "$1$2",
        Regex("(?<![\\p{L}\\p{N}_])[\\(\\[]([LR])([123])(?=\\s|$)") to "$1$2",
        Regex("(?<![\\p{L}\\p{N}_])[\\(\\[]C([12])[\\)\\]]?(?=\\s|$)") to "L$1",
        // Xbox'ın omuz ve tetik tuşları köşeli parantezle okunabilir ("[LT]", "(RB)").
        Regex("(?<![\\p{L}\\p{N}_])[\\(\\[]([LR][BTS])[\\)\\]](?=\\s|$|[.,:])") to "$1",
        Regex("([✕○△□↑↓←→✜▦◆●Ⓐ-Ⓩ⧉☰])(?:\\s+\\1)+") to "$1",
        Regex("(?<![\\p{L}\\p{N}_])([LR][123]|[LR][BTS]|OPTIONS|TOUCHPAD)(?:\\s+\\1)+(?![\\p{L}\\p{N}_])") to "$1",
    )

    /**
     * Düğme adlarının yanlış okunmuş hallerini düzeltir ("(l2)" → "L2", "(C1" → "L1") ve yan yana yinelenen simgeyi
     * teke indirir ("Hold ✕ ✕ to …": simge hem yazı içinden hem kenardan bulunabiliyor).
     */
    fun normalizedIcons(line: String): String = normalizers.fold(line) { s, (pattern, replacement) -> pattern.replace(s, replacement) }

    private val xboxLetter = Regex("^(?:[\\(\\[\\{<]([ABXY])[\\)\\]\\}>]|([ABXY]))([.,:]?)$")

    /**
     * Xbox oyununda yazı tanımanın harf olarak okuduğu yüz tuşları işarete çevrilir: "Press A to jump" → "Press Ⓐ to
     * jump", "(B) Back" → "Ⓑ Back", "Hold [X]" → "Hold Ⓧ". Yalnız tuşun yerinde: fiilden sonra ve ardından "to / or /
     * and" ya da satır sonu geliyorsa, parantezli harf tuş bağlamındaysa ya da satır harf ve bilinen bir komuttan
     * ibaretse ("A Talk", "TALK A"). Cümledeki "A" tanımlığı ("Use A Medkit", "A new day") değişmez. PlayStation'da
     * metne dokunulmaz (Windows: IconWords.WithXboxLetters).
     */
    fun withXboxLetters(line: String, family: ButtonFamily): String {
        if (family != ButtonFamily.XBOX || line.isEmpty()) return line
        val tokens = line.split(' ').filter { it.isNotEmpty() }.toMutableList()
        val letters = tokens.filter { it.length > 1 }.joinToString("")
        val shouting = letters.any { it.isLetter() } && letters == upper(letters)
        var changed = false
        fun word(text: String?) = (text ?: "").trim('.', ',', '!', '?', ':', ';').lowercase(Locale.ROOT)
        for (i in tokens.indices) {
            val match = xboxLetter.matchEntire(tokens[i]) ?: continue
            val bracketed = match.groupValues[1].isNotEmpty()
            val face = ButtonTokens.xboxFace((match.groupValues[1] + match.groupValues[2])[0])!!
            val previous = tokens.getOrNull(i - 1)
            val next = tokens.getOrNull(i + 1)
            val afterVerb = isVerb(word(previous)) &&
                (next == null || word(next) in setOf("to", "or", "and", "+", "/") || ButtonPrompts.isIcon(next) || shouting)
            // "A Talk", "B Back", "TALK A": harf ve bir komut (UiPrompts sözlüğü).
            val rest = tokens.filterIndexed { k, _ -> k != i }.joinToString(" ").trim('.', ',', ':')
            val command = (i == 0 || i == tokens.size - 1) && tokens.size in 2..4 && UiPrompts.isCommand(rest)
            val chained = previous != null && word(previous) in setOf("or", "and", "+", "/") && i >= 2 && isXboxGlyph(tokens[i - 2])
            if (if (bracketed) buttonContext(previous, next, shouting) || command else afterVerb || command || chained) {
                tokens[i] = face + match.groupValues[3]
                changed = true
            }
        }
        // Parantezli omuz tuşu ("(LT)") her durumda düzeltilir.
        return normalizedIcons(if (changed) tokens.joinToString(" ") else line)
    }

    private fun isXboxGlyph(token: String) = ButtonTokens.familyOf(token.trimEnd('.', ',', ':')) == ButtonFamily.XBOX

    /**
     * Yazı tanımanın düz okuduğu tuş adları da kararı besler: ipucu satırındaki "LT", "RB" Xbox'ı, "R2", "L1", "OPTIONS"
     * PlayStation'ı gösterir. Yalnız tuş çubuğu ya da "Hold LT to aim" gibi ipucu satırına bakılır.
     */
    fun observe(line: String, vote: ButtonLayoutVote) {
        if (vote.setting != ButtonLayout.AUTO || !ButtonPrompts.isPrompt(line)) return
        for (raw in line.split(' ')) {
            when (raw.trim('(', ')', '[', ']', '.', ',', ':')) {
                "LT", "RT", "LB", "RB", "LS", "RS" -> vote.add(ButtonFamily.XBOX)
                "L1", "L2", "R1", "R2", "OPTIONS", "TOUCHPAD" -> vote.add(ButtonFamily.PLAYSTATION)
            }
        }
    }
}
