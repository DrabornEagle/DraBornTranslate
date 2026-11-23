// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Okunan bir satır ve karedeki yeri (karenin tamamına oranla, 0–1); yeri bilinmiyorsa null. */
data class TextLine(val text: String, val box: TextBox?)

/**
 * Altyazıyı ekranın tamamından, kullanıcı alan seçmeden bulur (Windows: SubtitleFinder.cs, aynı kurallar ve sabitler).
 * Yazının yerine değil davranışına bakar: altyazı ortalıdır, cümle gibidir, birkaç saniyede bir değişir ve hep aynı
 * şeritte çıkar; arayüz yazısı (görev hedefi, gösterge) uzun süre aynı yerde aynı kalır, menü kısa ve noktalamasız
 * satırlardır, kumanda ipucu düğme adı taşır. Şerit, son bir buçuk dakikada görülen açıkça konuşma olan satırların en
 * çok toplandığı yerdir. Konuşmacısı belli satırlar ("Ad: söz" ya da üstünde büyük harfli ad satırı) üç kat sayılır.
 * Şerit, başka bir yer belirgin biçimde ağır basınca değişir; konuşmacılı satırlarla kurulmuş şeridi yalnız konuşmacılı
 * satırlar yerinden eder. Kutular karenin tamamına oranladır (0–1). Durum tutar; tek iş parçacığından çağrılır.
 */
class SubtitleFinder {
    enum class Verdict { SUBTITLE, PROMPT, MENU, STATIC, OFF_CENTER, NOT_SENTENCE, OUTSIDE_BAND }

    data class Finding(val line: TextLine, val verdict: Verdict)

    /** Öğrenilen altyazı şeridi (karenin yüksekliğine oranla: üst, alt); henüz öğrenilmediyse null. */
    var band: Pair<Double, Double>? = null
        private set

    companion object {
        /** Şeridin kurulması için gereken kanıt ağırlığı (düz satır 1, konuşmacılı satır [SPEAKER_WEIGHT]). */
        const val WEIGHT_TO_LEARN = 4
        /** Konuşmacısı belli satırın ağırlığı. */
        const val SPEAKER_WEIGHT = 3
        /** Başka bir yerin şeridi yerinden etmesi için şeritteki kanıttan bu kadar ağır basması gerekir. */
        const val WEIGHT_TO_MOVE = 3
        /** Kanıt bu kadar süre geçerlidir. */
        const val EVIDENCE_MS = 90000L
        /** Aynı yerde aynı yazı bu kadar süre kalırsa arayüzdür. */
        const val STATIC_AFTER_MS = 9000L
        /** Satırın ortası ekranın ortasından en çok bu kadar uzak olabilir. */
        const val CENTER_TOLERANCE = 0.12
        /** Okunan geniş alandan altyazı seçilirken satırın ortası ekranın ortasından en çok bu kadar uzak olabilir. */
        const val CORE_CENTER_TOLERANCE = 0.22
        /** Ortaları birbirine bu kadar yakın satırlar aynı yerdedir (iki satırlı altyazının iki satırı). */
        private const val SAME_PLACE = 0.05

        /**
         * Geniş bir alanın okumasından altyazı bloğunu seçer: şeridin içindeki ve ekranın ortasına yakın satırlar
         * çekirdektir; onlara dikeyde bitişik ve yatayda hizalı satırlar da (altyazının üçüncü, dördüncü satırı, üstteki
         * konuşmacı adı) bloğa katılır. Şeritte hiç satır yoksa okuma boştur. Kutular karenin tamamına oranladır.
         */
        fun grow(lines: List<TextLine>, band: Pair<Double, Double>): List<TextLine> {
            val placed = lines.filter { it.box != null }
            if (placed.isEmpty()) return lines
            val kept = placed.filter { line ->
                val box = line.box!!
                val middle = (box.top + box.bottom) / 2
                middle >= band.first && middle <= band.second && abs((box.left + box.right) / 2 - 0.5) <= CORE_CENTER_TOLERANCE
            }.toMutableList()
            var grew = kept.isNotEmpty()
            while (grew) {
                grew = false
                for (line in placed) {
                    if (line in kept) continue
                    if (kept.none { adjoins(line.box!!, it.box!!) }) continue
                    kept.add(line)
                    grew = true
                }
            }
            return lines.filter { it in kept }
        }

        /**
         * Şerit henüz öğrenilmeden geniş alan okunur; orada ekranın ortasından belirgin biçimde uzaktaki satırlar (köşe
         * yazıları, göstergeler) atılır (Windows: AppController.WithoutOffCenterLines).
         */
        fun withoutOffCenterLines(lines: List<TextLine>): List<TextLine> {
            if (lines.isEmpty()) return lines
            val kept = lines.filter { line -> line.box == null || abs((line.box.left + line.box.right) / 2 - 0.5) <= 0.2 }
            return if (kept.size == lines.size) lines else kept
        }

        /** İki satır alt alta mı: aralarındaki boşluk bir satır yüksekliğinden az ve yatayda hizalı. */
        private fun adjoins(a: TextBox, b: TextBox): Boolean {
            val height = max(a.bottom - a.top, b.bottom - b.top)
            val gap = max(a.top, b.top) - min(a.bottom, b.bottom)
            if (gap > height * 0.9) return false
            val overlap = min(a.right, b.right) - max(a.left, b.left)
            val narrower = min(a.right - a.left, b.right - b.left)
            return abs((a.left + a.right) / 2 - (b.left + b.right) / 2) <= CENTER_TOLERANCE || (overlap > 0 && overlap >= narrower * 0.5)
        }

        private fun words(text: String) = OcrNoise.tokens(text)

        /**
         * Konuşma satırı gibi mi: en az iki sözcük ya da noktalamayla biten tek sözcük ("Run!"), harflerinin çoğu küçük
         * harf ya da cümle sonu işaretiyle biter (tümü büyük harfli kısa yazılar çoğunlukla başlık ve göstergedir).
         */
        internal fun looksSpoken(text: String): Boolean {
            val words = words(text).filter { word -> word.any { it.isLetter() } }
            if (words.isEmpty()) return false
            val ends = SubtitleStabilizer.endsSentence(text)
            if (words.size <= 2 && !ends) return false
            val letters = text.filter { it.isLetter() }
            if (letters.length < 3) return false
            val lower = letters.count { it.isLowerCase() }
            return lower * 2 >= letters.length || (ends && words.size >= 2)
        }

        /** Şeridi öğretecek kadar açık konuşma: en az üç sözcük ve ya cümle sonu işaretiyle biter ya da "Ad:" ile başlar. */
        internal fun isClearlySpoken(text: String): Boolean {
            val count = words(text).count { word -> word.any { it.isLetter() } }
            if (count < 3 || !looksSpoken(text)) return false
            val colon = text.indexOf(':')
            val labelled = colon in 2..30 && text[0].isUpperCase() && colon < text.length - 2
            return labelled || SubtitleStabilizer.endsSentence(text)
        }

        /** Tümü büyük harfli, en çok üç sözcüklü ad satırı ("MARKOS"). */
        internal fun isSpeakerLine(text: String): Boolean {
            val trimmed = text.trim()
            val letters = trimmed.filter { it.isLetter() }
            return letters.length >= 2 && letters.all { it.isUpperCase() } && words(trimmed).size <= 3 &&
                trimmed.all { it.isLetter() || it == ' ' || it == '-' || it == '.' || it == '\'' || it == '’' }
        }

        /** Alt alta dizilmiş, sola hizalı ya da aynı ortalı, kısa ve noktalamasız üç ve daha çok satır menüdür. */
        private fun menuLines(lines: List<TextLine>): Set<TextLine> {
            val result = HashSet<TextLine>()
            val shorts = lines.filter { it.box != null && words(it.text).size <= 3 && !SubtitleStabilizer.endsSentence(it.text) }
                .sortedBy { it.box!!.top }
            for (line in shorts) {
                val box = line.box!!
                val column = shorts.filter { other ->
                    val o = other.box!!
                    abs(o.left - box.left) <= 0.02 || abs((o.left + o.right) / 2 - (box.left + box.right) / 2) <= 0.02
                }
                if (column.size >= 3) result.addAll(column)
            }
            return result
        }

        /** Aynı yerdeki aynı yazı: yer yüzde ikilik ızgaraya yuvarlanır (yazı tanıma kutuları kare kare oynar). */
        private fun key(text: String, box: TextBox): String =
            "${roundEven((box.left + box.right) / 2 / 0.02)}:${roundEven((box.top + box.bottom) / 2 / 0.02)}:${TextMatch.normalize(text)}"

        /** C#'taki Math.Round (yarıda çifte yuvarlar). */
        private fun roundEven(value: Double): Long = Math.rint(value).roundToLong()

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }

        /** Satır ortalarının ortancası çevresinde şerit: yukarı ve aşağı iki buçuk satır yüksekliği pay bırakılır. */
        private fun bandOf(lines: List<Pair<Double, Double>>): Pair<Double, Double> {
            val middle = median(lines.map { it.first })
            val height = max(0.015, median(lines.map { it.second }))
            return max(0.0, middle - height * 2.5) to min(1.0, middle + height * 2.5)
        }
    }

    private data class Seen(val first: Long, val last: Long)
    private data class Evidence(val middle: Double, val height: Double, val text: String, val at: Long, val weight: Int)

    private val seen = HashMap<String, Seen>()
    private val evidence = mutableListOf<Evidence>()
    /** Şerit konuşmacılı satırlarla mı kuruldu (öyleyse yalnız konuşmacılı satırlar yerinden eder). */
    private var bandHasSpeakers = false

    /** Karenin tamamının okumasını sınıflar. [now] milisaniyedir (tekdüze artan saat). */
    fun classify(lines: List<TextLine>, now: Long): List<Finding> {
        forget(now)
        val menu = menuLines(lines)
        val findings = mutableListOf<Finding>()
        for (line in lines) {
            val box = line.box ?: continue
            if (line.text.trim().isEmpty()) continue
            val key = key(line.text, box)
            val first = seen[key]?.first ?: now
            seen[key] = Seen(first, now)

            val verdict = when {
                ButtonPrompts.isPrompt(line.text) -> Verdict.PROMPT
                line in menu -> Verdict.MENU
                now - first >= STATIC_AFTER_MS -> Verdict.STATIC
                abs((box.left + box.right) / 2 - 0.5) > CENTER_TOLERANCE -> Verdict.OFF_CENTER
                !looksSpoken(line.text) -> Verdict.NOT_SENTENCE
                else -> if (inBand(box)) Verdict.SUBTITLE else Verdict.OUTSIDE_BAND
            }
            // Şeridi yalnız açıkça konuşma olan satırlar belirler; "Spider Barrage" gibi kısa yazılar belirlemez.
            if (verdict == Verdict.SUBTITLE || verdict == Verdict.OUTSIDE_BAND) {
                val speaker = hasSpeaker(line, lines)
                if (speaker || isClearlySpoken(line.text)) note(line.text, box, now, if (speaker) SPEAKER_WEIGHT else 1)
            }
            findings.add(Finding(line, verdict))
        }
        if (relearn(now)) {
            // Şerit değişti: bu karedeki satırlar yeni şeride göre yeniden değerlendirilir.
            for (i in findings.indices) {
                val finding = findings[i]
                val box = finding.line.box ?: continue
                if (finding.verdict == Verdict.SUBTITLE || finding.verdict == Verdict.OUTSIDE_BAND)
                    findings[i] = finding.copy(verdict = if (inBand(box)) Verdict.SUBTITLE else Verdict.OUTSIDE_BAND)
            }
        }
        return findings
    }

    /** Altyazı sayılan satırlar, yukarıdan aşağı. */
    fun subtitles(lines: List<TextLine>, now: Long): List<TextLine> =
        classify(lines, now).filter { it.verdict == Verdict.SUBTITLE }.map { it.line }

    private fun inBand(box: TextBox): Boolean {
        val current = band ?: return true
        val middle = (box.top + box.bottom) / 2
        return middle >= current.first && middle <= current.second
    }

    /**
     * Satırın konuşmacısı belli mi: "Ad: söz" biçiminde başlar ya da hemen üstünde, ortalı, büyük harfli kısa bir ad
     * satırı vardır ("MARKOS" / "We have no option but to try!").
     */
    private fun hasSpeaker(line: TextLine, lines: List<TextLine>): Boolean {
        val text = line.text.trimStart()
        val colon = text.indexOf(": ")
        if (colon in 2..30 && text[0].isUpperCase() && text.substring(0, colon).split(' ').size <= 3 &&
            text.substring(0, colon).all { it.isLetter() || it == ' ' || it == '-' || it == '.' || it == '\'' || it == '’' })
            return true
        val box = line.box ?: return false
        val height = box.bottom - box.top
        return lines.any { other ->
            val above = other.box
            above != null && isSpeakerLine(other.text) && above.bottom <= box.top + height * 0.5 && box.top - above.bottom <= height * 1.5 &&
                abs((above.left + above.right) / 2 - (box.left + box.right) / 2) <= CENTER_TOLERANCE
        }
    }

    private fun note(text: String, box: TextBox, now: Long, weight: Int) {
        val normal = TextMatch.normalize(text)
        val known = evidence.indexOfFirst { it.text == normal }
        // Aynı satır yeniden görülünce tarihi yenilenir; ağırlığı artmaz (ekranda duran yazı şeridi çekmesin).
        if (known >= 0) evidence[known] = evidence[known].copy(at = now, weight = max(evidence[known].weight, weight))
        else evidence.add(Evidence((box.top + box.bottom) / 2, box.bottom - box.top, normal, now, weight))
        if (evidence.size > 40) evidence.removeAt(0)
    }

    /** Kanıtın en çok toplandığı yeri bulur; şerit yoksa kurar, başka yer ağır basıyorsa oraya taşır. Değiştiyse true. */
    private fun relearn(now: Long): Boolean {
        evidence.removeAll { now - it.at > EVIDENCE_MS }
        if (evidence.isEmpty()) return false
        fun around(middle: Double) = evidence.filter { abs(it.middle - middle) <= SAME_PLACE }
        // En ağır küme; eşitlikte en yeni kanıtı olan (ilk görülen kazanır).
        var best: List<Evidence> = emptyList()
        for (item in evidence) {
            val cluster = around(item.middle)
            if (best.isEmpty()) { best = cluster; continue }
            val w = cluster.sumOf { it.weight }
            val bw = best.sumOf { it.weight }
            if (w > bw || (w == bw && cluster.maxOf { it.at } > best.maxOf { it.at })) best = cluster
        }
        val weight = best.sumOf { it.weight }
        val speakers = best.any { it.weight >= SPEAKER_WEIGHT }
        val candidate = bandOf(best.map { it.middle to it.height })

        val current = band
        if (current == null) {
            if (weight < WEIGHT_TO_LEARN) return false
            band = candidate
            bandHasSpeakers = speakers
            return true
        }
        val inBand = evidence.filter { it.middle >= current.first && it.middle <= current.second }
        val center = median(best.map { it.middle })
        if (center >= current.first && center <= current.second) {
            // Aynı yer: şerit ince ayarlanır.
            bandHasSpeakers = bandHasSpeakers || speakers
            if (abs(candidate.first - current.first) < 0.005 && abs(candidate.second - current.second) < 0.005) return false
            band = candidate
            return true
        }
        // Başka yer: belirgin biçimde ağır basmalı; konuşmacılı şeridi konuşmacısız yazılar (ipuçları) yerinden etmez.
        val stillSpeakers = bandHasSpeakers && inBand.any { it.weight >= SPEAKER_WEIGHT }
        // Konuşmacılı satırlar konuşmacısız bir şeridi (ipuçlarından öğrenilmiş) eşit ağırlıkta bile yerinden eder.
        val margin = if (speakers && !stillSpeakers) 0 else WEIGHT_TO_MOVE
        if (weight < inBand.sumOf { it.weight } + margin || (stillSpeakers && !speakers) || weight < WEIGHT_TO_LEARN) return false
        band = candidate
        bandHasSpeakers = speakers
        return true
    }

    /** Bir süredir görülmeyen yazılar unutulur (aynı altyazı sonra yeniden gelirse sabit sayılmasın). */
    private fun forget(now: Long) {
        seen.entries.removeAll { now - it.value.last > 4000 }
    }
}

/**
 * Oyunların ekranda gösterdiği kumanda ipuçlarını tanır: "R2 Swing", "L1 + R1 Spartan Rage", "△ Pick up", "Press ✕ to
 * jump" (Windows: Reading.cs içindeki ButtonPrompts, aynı kurallar). Konuşma satırları dokunulmadan kalır.
 */
object ButtonPrompts {
    /** Her zaman kumanda düğmesi olan simgeler ve adlar (büyük harfle tutulur; karşılaştırma büyük/küçük harfe bakmaz). */
    private val buttons = setOf(
        "R1", "R2", "R3", "L1", "L2", "L3", "OPTIONS", "TOUCHPAD",
        "✕", "×", "⨯", "✖", "○", "◯", "●", "□", "▢", "◻", "■", "△", "▲", "⨂",
        "↑", "↓", "←", "→", "✜", "▦", "◆",
        // Xbox: yüz tuşları, omuz / tetik / çubuk tuşları, View ve Menu.
        "Ⓐ", "Ⓑ", "Ⓧ", "Ⓨ", "LB", "RB", "LT", "RT", "LS", "RS", "⧉", "☰",
    )

    private fun isButton(token: String) = token.uppercase(java.util.Locale.ROOT) in buttons

    /**
     * Sözcük bir düğme simgesi ya da adı mı ("✕", "R2", "Ⓑ"); harfe dönüşmüş "O" / "X" de sayılır (Windows:
     * ButtonPrompts.IsIcon). Ek alanlarda simgeli metnin işlenmesinde kullanılır.
     */
    fun isIcon(token: String): Boolean {
        val t = token.trim { it in BRACKETS }
        if (t.length == 1 && t[0] in 'Ⓐ'..'Ⓩ') return true
        return isButton(t) || t == "O" || t == "X"
    }

    /** Simge yazı tanımada harfe dönüşebilir; yalnız ipucu kalıbında düğme sayılır. */
    private val lookalikes = setOf("X", "O")

    /** Düğme adının yanlış okunmuş hali ("R1" → "RI" / "Rl"); yalnız satır başında ve cümle gibi bitmeyen kısa satırda. */
    private val misreadButtons = setOf("RI", "LI", "Rl", "Ll", "RZ", "LZ")

    private val verbs = setOf("press", "hold", "tap", "push", "use", "mash", "release")

    private val separators = Regex("[ \\t+/]+")
    private const val BRACKETS = "()[]{}<>「」"
    private val labels = Regex("\\((?:[A-Z]{1,3}|[A-Z]{2,4})\\)")
    private val decimal = Regex("(?<![\\w.])\\d+\\.\\d(?![\\w.])")

    /** Oyun arayüzü satırı: iki ya da daha çok düğme etiketi taşıyan çubuk ya da ondalıklı sayı taşıyan kısa gösterge. */
    private fun isInterfaceBar(line: String): Boolean {
        if (SubtitleStabilizer.endsSentence(line)) return false
        val count = labels.findAll(line).count()
        val tokens = line.split(' ').filter { it.isNotEmpty() }
        if (count >= 2 || count + tokens.count { it in lookalikes } >= 3) return true
        val words = tokens.count { token -> token.count { it.isLetter() } >= 2 }
        return words <= 6 && decimal.containsMatchIn(line)
    }

    fun isPrompt(line: String): Boolean {
        val tokens = line.split(separators).map { it.trim { c -> c in BRACKETS } }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        if (isInterfaceBar(line)) return true
        // Tuş simgesi noktalama okunmuş etkileşim yazısı (":Read message"): simge, ardından cümle gibi bitmeyen kısa eylem.
        val trimmedLine = line.trimStart()
        if (trimmedLine.length > 1 && trimmedLine[0] in ":;•©®") {
            val rest = trimmedLine.trimStart(':', ';', '•', '©', '®', ' ')
            if (rest.isNotEmpty() && rest[0].isUpperCase() && tokens.size <= 5 && !SubtitleStabilizer.endsSentence(line)) return true
        }
        val first = tokens[0]
        val words = tokens.count { token -> !isButton(token) && token.any { it.isLetter() } }
        // "R2 Swing", "△ Pick up", "L1 R1 Spartan Rage"
        if (isButton(first)) return words <= 5
        if (first in misreadButtons && tokens.size >= 2 && words <= 6 && !SubtitleStabilizer.endsSentence(line)) return true
        // "PHOTO MODE L1 + R1": düğme sonda, önünde kısa ve noktalamasız bir eylem adı.
        if (isButton(tokens.last()) && words <= 4 && !SubtitleStabilizer.endsSentence(line)) return true
        // Ekranın altındaki ipucu çubuğu: düğmeyle başlar, içinde en az bir düğme daha vardır, cümle gibi bitmez.
        if (first in lookalikes && tokens.size in 4..16 && !SubtitleStabilizer.endsSentence(line) &&
            tokens.drop(1).any { it in lookalikes || isButton(it) } &&
            tokens.count { it[0].isUpperCase() } * 3 >= tokens.size * 2)
            return true
        // "X Interact", "O Back": ardından kısa, büyük harfle başlayan, noktalamasız bir eylem gelir.
        if (first in lookalikes && tokens.size in 2..4 && tokens[1][0].isUpperCase() && !SubtitleStabilizer.endsSentence(line)) return true
        // "Press ✕ to jump", "Hold R2 to aim"; düğme simgesi harf olmayan bir işarete dönüşebilir ("PRESS @ TO CONTINUE").
        if (first.lowercase(java.util.Locale.ROOT) in verbs && tokens.size in 2..10)
            return isButton(tokens[1]) || tokens[1] in lookalikes || tokens[1] in misreadButtons ||
                (tokens[1].length <= 2 && tokens[1].none { it.isLetterOrDigit() }) ||
                (tokens.size >= 4 && tokens[1].length <= 2 && tokens[2].equals("to", ignoreCase = true))
        return false
    }
}
