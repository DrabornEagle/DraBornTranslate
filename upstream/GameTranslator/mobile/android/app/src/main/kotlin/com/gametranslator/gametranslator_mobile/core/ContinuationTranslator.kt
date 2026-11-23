// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale

/**
 * Oyunlar uzun cümleyi art arda gelen birkaç altyazıya böler ("You're the ones" → "who are gonna need help..." →
 * "when they come for you."). Parçalar tek tek çevrilince anlam dağılır. Bu sarmalayıcı, cümlesi bitmemiş satırın
 * ardından aynı konuşmacıdan gelen devamı önceki parçayla birlikte çevirir ve cümlenin o ana kadarki tamamının
 * çevirisini verir. Konuşmacı adı çevirmene verilmez, olduğu gibi başa konur; birkaç kişi konuşuyorsa her konuşma ayrı
 * çevrilir. Aynı okumada iki kez gelen satırı teke indirir. Windows'taki ContinuationTranslator.cs ile aynı kurallar.
 * Kendi durumunu tutar; başka bir çevirmeni sarar. [inner] null dönerse (çeviremedi) sonuç da null'dur.
 */
class ContinuationTranslator(
    private val inner: TextTranslator,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val looksMisread: ((String) -> Boolean)? = null,
    /** Konuşmacı adları (bozuk okunan adı doğrusuna çevirmek için). */
    private val names: SpeakerNames = SpeakerNames(),
    /**
     * Kısa ünlemlerin hazır karşılığı ([ShortPhrases.translate]; İngilizce→Türkçe). Verilirse ve sözün karşılığı varsa
     * söz çevirmene gönderilmez.
     */
    private val phrasebook: ((String) -> String?)? = null,
) : TextTranslator {
    companion object {
        /** Önceki parçadan en çok bu kadar sonra gelen satır onun devamı sayılabilir. */
        const val WITHIN_MS = 8000L
        /** Birleşen cümle bundan uzunsa yeni satır tek başına çevrilir (zincir sonsuza uzamasın). */
        const val MAX_LENGTH = 260

        private val speakerLabel = Regex("^(?<name>\\p{Lu}[\\p{L}.’'-]*(?: \\p{Lu}[\\p{L}.’'-]*){0,2}):\\s")
        private val bareLabel = Regex("^\\p{Lu}[\\p{L}.’'-]*(?: \\p{Lu}[\\p{L}.’'-]*){0,2}:$")
        // Ayraç simge, yıldız, tire ya da bunların noktayla karışığıdır ("·-", ".-", "*-", "-"); tek başına nokta ayraç
        // değildir. Addan önce tek bir artık harf olabilir ("Z YERMAK•. come here": simgenin kenarı harf okunmuş).
        private val iconLabel = Regex(
            "^(?:[\\p{Lu}\\d] )?(?<name>\\p{Lu}[\\p{Lu}'’-]{2,14}(?: \\p{Lu}[\\p{Lu}'’-]{1,14})?)\\s*" +
                "(?=[•·▸►>▶‣◆■*\\-–—.]*[•·▸►>▶‣◆■*\\-–—])[•·▸►>▶‣◆■*\\-–—.]+\\s*(?<body>\\S.*)$")

        /** Cümlenin ortasında kalındığını gösteren son sözcükler (edat, bağlaç, tanımlık). */
        private val connectors = setOf(
            "a", "an", "the", "of", "to", "in", "on", "at", "by", "for", "from", "with", "without", "into", "onto", "over", "under",
            "beneath", "between", "through", "about", "against", "and", "or", "but", "that", "which", "who", "whose", "than", "as",
            "if", "because", "when", "while", "my", "your", "his", "her", "our", "their", "its", "this", "these", "those",
        )

        /** Büyük harfle yazılan adda yazı tanımanın küçük okuduğu "i" büyütülür ("DAMiR", "SOLDiER"). */
        internal fun capsName(name: String): String = SpeakerNames.fixCaps(name)

        private fun rowsOf(text: String) = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        /** Alt alta gelen neredeyse aynı satırlardan (aynı altyazının iki okuması) daha düzgün okunan kalır. */
        fun withoutRepeatedLines(text: String): String {
            val lines = rowsOf(text)
            if (lines.size < 2) return text.trim()
            val kept = mutableListOf<String>()
            for (line in lines) {
                // Tekrar hemen alttaki satır olmayabilir. Önceki satırın sonunun bozuk bir kopyası da tekrardır: son iki
                // sözcüğü aynıdır ve daha uzun değildir.
                val twin = kept.indexOfFirst { earlier ->
                    TextMatch.isSimilar(TextMatch.normalize(earlier), TextMatch.normalize(line), 0.8) || isTailCopy(line, earlier)
                }
                if (twin >= 0) {
                    // Puan eşitse sözcükleri ayrı okunan kazanır ("yowwill" yerine "you will").
                    val better = OcrNoise.score(line)
                    val current = OcrNoise.score(kept[twin])
                    if (better > current || (better == current && line.count { it == ' ' } > kept[twin].count { it == ' ' })) kept[twin] = line
                    continue
                }
                kept.add(line)
            }
            return kept.joinToString("\n")
        }

        private fun isTailCopy(line: String, earlier: String): Boolean {
            val a = TextMatch.normalize(line).split(' ').filter { it.isNotEmpty() }
            val b = TextMatch.normalize(earlier).split(' ').filter { it.isNotEmpty() }
            return a.size >= 3 && a.size <= b.size && a[a.size - 1] == b[b.size - 1] && a[a.size - 2] == b[b.size - 2]
        }

        /**
         * Bazı oyunlar adı iki nokta yerine simgeyle ayırır ("CAL • Think BD wants…", "BODE ·- Now I'm…"). Büyük harfli ad
         * ve ardından gelen simge "Ad: söz" biçimine çevrilir; yoksa ad sözün parçası sanılıp çevriliyordu.
         */
        private fun withIconLabels(text: String): String {
            val rows = text.split('\n').toMutableList()
            var changed = false
            for (i in rows.indices) {
                val match = iconLabel.find(rows[i].trim()) ?: continue
                rows[i] = "${match.groups["name"]!!.value}: ${match.groups["body"]!!.value.trim()}"
                changed = true
            }
            return if (changed) rows.joinToString("\n") else text
        }

        /** Sözü olmayan ad satırı ("… / Mimir:": bir sonraki konuşmanın önce beliren adı) başka satır varsa atılır. */
        private fun withoutBareLabels(text: String): String {
            val rows = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val kept = rows.filter { !bareLabel.matches(it) }
            return if (kept.size == rows.size || kept.isEmpty()) text else kept.joinToString("\n")
        }

        /**
         * Okumayı konuşmalara böler: konuşmacı adıyla başlayan her satır yeni bir konuşmadır, adsız satırlar bir öncekinin
         * devamıdır (ekranda sarılmış). İkiden az konuşmacı varsa null.
         */
        private fun turns(source: String): List<Pair<String?, String>>? {
            val rows = rowsOf(source)
            // İki adlı satır ya da adsız bir satırın (önceki konuşmanın ekranda kalan sonu) ardından gelen adlı satır.
            val labelled = rows.count { speakerLabel.containsMatchIn(it) }
            val tailThenSpeaker = rows.size > 1 && !speakerLabel.containsMatchIn(rows[0]) && labelled >= 1
            if (labelled < 2 && !tailThenSpeaker) return null
            val turns = mutableListOf<Pair<String?, String>>()
            for (row in rows) {
                val (speaker, body) = split(row)
                if (speaker == null && turns.isNotEmpty()) turns[turns.size - 1] = turns.last().first to (turns.last().second + " " + body).trim()
                else turns.add(speaker to body)
            }
            return turns
        }

        /** En az iki harfli bir sözcüğü olan ve rakamla başlamayan yazı. */
        private fun isSpeech(text: String): Boolean {
            val trimmed = text.trimStart()
            return trimmed.isNotEmpty() && !trimmed[0].isDigit() && text.count { it.isLetter() } >= 3
        }

        /** Okunan ad, bilinen adın birkaç harfi eksik ya da yanlış hâli mi ("anke", "Gankc" → "Ganke"). */
        private fun sameName(read: String, known: String): Boolean {
            val a = read.trim('-', '—', '_', ' ', '.', '•').lowercase(Locale.ROOT)
            val b = known.lowercase(Locale.ROOT)
            if (a.length < 3 || b.length < 4) return false
            return b.endsWith(a) || TextMatch.isSimilar(a, b, 0.75)
        }

        private fun sameLine(a: String, b: String): Boolean {
            val left = TextMatch.normalize(a)
            val right = TextMatch.normalize(b)
            return left == right || TextMatch.isSimilar(left, right, 0.8) ||
                (left.length >= 8 && right.length >= 8 && (left.contains(right) || right.contains(left)))
        }

        /** "Flint Marko: who are gonna need help..." → ("Flint Marko", "who are gonna need help..."). */
        private fun split(text: String): Pair<String?, String> {
            val match = speakerLabel.find(text) ?: return null to text.trim()
            val name = match.groups["name"]!!.value
            var body = text.substring(match.value.length).trim()
            // Satır değişirken ad iki kez okunabiliyor ("Faye: Faye: This way.", "Kratos: Kratos. The tracks stop here.").
            while (true) {
                val rest = body.trimStart('"', '“', '\'', '•', '-', '—', '(', ' ')
                // Virgül sayılmaz: "Sam: Sam, wait!" seslenmedir.
                if (rest.startsWith(name) && rest.length >= name.length + 2 && ":.;•".contains(rest[name.length])) {
                    body = rest.substring(name.length + 1).trim()
                    continue
                }
                // İki sözcüklü ad noktalamasız yinelenmiş: "Woman's Voice: Woman's Voice The Zero Dawn system".
                if (name.contains(' ') && rest.startsWith("$name ") && rest.length > name.length + 2 && rest[name.length + 1].isUpperCase()) {
                    body = rest.substring(name.length + 1).trim()
                    continue
                }
                // Adın bozuk ikinci okuması ("Lae'zel: Lae'ze: It cannot be helped.").
                val colon = rest.indexOf(": ")
                if (colon < 3 || colon > name.length + 2 || rest.substring(0, colon).contains(' ') != name.contains(' ') ||
                    !sameName(rest.substring(0, colon), name)) break
                body = rest.substring(colon + 2).trim()
            }
            // Addan sonra tek başına okunan harf, konuşmacı simgesinin artığıdır ("Faye: S This way."). "I" ve "A" sözdür.
            if (body.length > 3 && body[0].isUpperCase() && body[0] != 'I' && body[0] != 'A' && body[1] == ' ' && body[2].isUpperCase())
                body = body.substring(2)
            return name to body
        }
    }

    private data class Previous(val speaker: String?, val body: String, val raw: String, val at: Long)

    private val lock = Any()
    private var previous: Previous? = null

    override fun translate(text: String): String? {
        val source = relabel(withoutBareLabels(withIconLabels(withoutRepeatedLines(text))))
        // Birkaç kişi konuşuyorsa her konuşma kendi adıyla ayrı çevrilir; adlar çevrilmez.
        val all = turns(source)
        if (all != null && all.size > 1) {
            // Sözü olmayan konuşma (bir sonraki konuşmanın önce beliren adı: "… / Mimir:") gösterilmez.
            val spoken = all.filter { turn -> turn.second.any { it.isLetter() } }.ifEmpty { all }
            synchronized(lock) { previous = null }
            val parts = spoken.map { (speaker, body) -> translateTurn(speaker, body) ?: return null }
            return parts.joinToString("\n")
        }
        val (speaker, body) = synchronized(lock) { combine(source) }
        return translateTurn(speaker, body)
    }

    /**
     * İki nokta üst üstesi okunmamış konuşmacı adını ("Stintsy Fuck, he's here.") oyunda daha önce görülen adla
     * "Stints: Fuck, he's here." biçimine getirir; adın çevrilmesi ve söze karışması önlenir.
     */
    private fun relabel(source: String): String {
        val rows = source.split('\n').toMutableList()
        var changed = false
        for (i in rows.indices) {
            val row = rows[i].trim()
            if (row.isEmpty() || speakerLabel.containsMatchIn(row)) continue
            val words = row.split(' ', limit = 4).filter { it.isNotEmpty() }
            // İki sözcüklü ad: "Charles Rookwoods Jelve done…" (ikinci sözcüğe ":" harf olarak yapışmış).
            if (words.size >= 3) {
                val longName = names.labelIn(words[0] + " " + words[1], words[2])
                if (longName != null) {
                    rows[i] = "$longName: ${words.drop(2).joinToString(" ")}"
                    changed = true
                    continue
                }
            }
            if (words.size < 2) continue
            val name = names.labelIn(words[0], words[1]) ?: continue
            rows[i] = "$name: ${row.substring(row.indexOf(' ') + 1).trimStart()}"
            changed = true
        }
        return if (changed) rows.joinToString("\n") else source
    }

    /**
     * Sözü çevirir, konuşmacı adını olduğu gibi başına koyar. Ad çevirmene verilmez: çevirmen onu sıradan sözcük sanıp
     * çeviriyor ("ANNA:" → "ANNE:", "MILLER:" → "DEĞİRMENCİ:") ve aynı ad bir öyle bir böyle çıkıyordu.
     */
    private fun translateTurn(speakerRead: String?, bodyRead: String): String? {
        var speaker = speakerRead
        var body = bodyRead
        // Gerçek adın önüne çöp bir "ad" eklenmiş ("QHI: Mimir: Uzun zaman oldu..."): söz bilinen bir adla başlıyorsa
        // ve öndeki ad bilinmiyorsa konuşmacı içteki addır.
        if (speaker != null) {
            val innerLabel = speakerLabel.find(body)
            if (innerLabel != null && names.isKnown(innerLabel.groups["name"]!!.value) && !names.isKnown(speaker)) {
                speaker = innerLabel.groups["name"]!!.value
                body = body.substring(innerLabel.value.length).trim()
            }
        }
        // Bozuk okunmuş ad ("Minnir") oyunda sık geçen doğrusuna ("Mimir") çevrilir.
        if (speaker != null) speaker = synchronized(lock) { capsName(names.canonical(speaker!!)) }
        if (body.isEmpty()) return if (speaker == null) "" else "$speaker:"
        val ready = phrasebook?.invoke(body)
        val radio = if (ready == null && phrasebook != null) ShortPhrases.splitRadioTail(body) else null
        val translated = when {
            ready != null -> ready
            radio != null -> ShortPhrases.fixShouting(radio.first, inner.translate(radio.first)?.trim() ?: return null) + " " + radio.second
            else -> ShortPhrases.fixShouting(body, inner.translate(body)?.trim() ?: return null)
        }
        return if (speaker == null) translated else "$speaker: $translated"
    }

    /** Satır önceki parçanın devamıysa ikisi birlikte, değilse kendisi. Durumu günceller; kilit altında çağrılır. */
    private fun combine(raw: String): Pair<String?, String> {
        var source = raw
        var (speaker, body) = split(source)
        val at = clock()
        // Konuşmacı adı bozuk okunmuşsa ("anke: …", önceki satır "Ganke: …") ad sözün parçası sayılmasın.
        val before = previous
        val known = before?.speaker
        if (speaker == null && before != null && known != null && at - before.at <= WITHIN_MS) {
            val colon = body.indexOf(": ")
            if (colon in 2..24 && !body.substring(0, colon).contains(' ') == !known.contains(' ') && sameName(body.substring(0, colon), known)) {
                speaker = known
                body = body.substring(colon + 2).trim()
                source = "$known: $body"
            }
        }
        // Ekranda iki üç satıra sarılmış söz tek cümledir; satır başında konuşmacı tiresi varsa ayrı konuşmalardır.
        if (body.contains('\n')) {
            val rows = rowsOf(body)
            if (rows.drop(1).none { it.startsWith('-') || it.startsWith('–') || it.startsWith('—') || it.startsWith(">>") }) {
                body = rows.joinToString(" ")
                source = if (speaker == null) body else "$speaker: $body"
            }
        }
        var whole = body
        val last = previous
        if (last != null && at - last.at <= WITHIN_MS && !body.contains('\n') && !last.body.contains('\n') &&
            (speaker == null || speaker == last.speaker) && continues(last.body, body) &&
            !sameLine(last.raw, source) && last.body.length + body.length + 1 <= MAX_LENGTH) {
            whole = last.body + " " + body
            if (speaker == null) speaker = last.speaker
        }
        // Aynı satırın yeniden okunması zinciri ilerletmez (yoksa satır kendisiyle birleşirdi).
        val kept = previous
        if (kept == null || !sameLine(kept.raw, source)) previous = Previous(speaker, whole, source, at)
        return speaker to whole
    }

    /** Önceki parça cümleyi bitirmediyse ya da yeni parça küçük harfle başlıyorsa devamdır. */
    private fun continues(before: String, after: String): Boolean {
        val first = after.firstOrNull { it.isLetter() } ?: return false
        // Gösterge ve sayaç yazıları ("19m", "x3") söz değildir; birbirine eklenmez.
        if (!isSpeech(before) || !isSpeech(after)) return false
        // Bozuk okunmuş parçalar birbirine eklenmez (anlamsız parçalar zincir olup uzuyordu).
        if (looksMisread != null && (looksMisread.invoke(before) || looksMisread.invoke(after))) return false
        // Sondaki tırnak ve yazı tanıma artıkları ("wait--'") sayılmaz.
        val trimmed = before.trimEnd().trimEnd('\'', '"', '’', '”', '`', ' ')
        // Baştaki tek harf ("r and Hit Indicators…": "Air"in kesik okuması) yarım okunmuş başka bir yazıdır, devam değil.
        val head = after.trimStart().split(' ', limit = 2)[0].trim('.', ',', '\'', '"')
        if (first.isLowerCase() && head.length == 1 && head != "a" && head != "i") return false
        // Küçük harfle başlayan satır her zaman bir öncekinin devamıdır ("...need help..." → "when they come for you.").
        if (first.isLowerCase())
            return (trimmed.isNotEmpty() && !".!?".contains(trimmed.last())) || trimmed.endsWith("...") || trimmed.endsWith('…')
        // Büyük harfle başlayan satır yalnız önceki söz açıkça yarıda kaldıysa devamdır: virgülle ya da bağlayıcı bir
        // sözcükle bitiyorsa ("…in your old age," → "Sergei."; "…in the caves beneath" → "Kephallonia when…").
        if (trimmed.endsWith(',') || trimmed.endsWith(';')) return true
        val lastWord = trimmed.split(' ').lastOrNull { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        return lastWord != null && lastWord in connectors
    }
}
