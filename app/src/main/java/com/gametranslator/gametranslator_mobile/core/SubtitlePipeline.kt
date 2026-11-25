// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

/** Metni çevirir; bloklar. Çevrilemezse null. */
fun interface TextTranslator {
    fun translate(text: String): String?
}

/** Çevirinin gittiği yer (TV sunucusu ve telefondaki ekran). */
interface SubtitleSink {
    fun show(text: String, durationMs: Int, box: TextBox?, replaces: Boolean)
    fun clear()
}

/**
 * Okunan altyazıyı çevirip gönderen hat: gürültü temizliği → kararlılık → önbellek → oyun paketi/çeviri → TV.
 * Windows'taki SubtitlePipeline.cs'in çekirdeği (bekletme ve dublaj yok).
 * [onRead] tek bir iş parçacığından çağrılır; çeviri [translateExecutor]'da yapılır, sonuçlar kilit altında işlenir.
 */
class SubtitlePipeline(
    private val translator: TextTranslator,
    private val sink: SubtitleSink,
    private val translateExecutor: Executor,
    private val stabilizer: SubtitleStabilizer = SubtitleStabilizer(),
    /**
     * İki satırlık altyazı çevirmene tek satır olarak gider. false ise satırlar olduğu gibi gider (çevirmen zinciri
     * konuşmacıları ve sarılmış satırları kendisi ayırır: [ContinuationTranslator]).
     */
    private val joinLines: Boolean = true,
) {
    companion object {
        /** Satır ekranda durdukça çeviri de durur; kalkınca TV'den silinir. */
        const val ON_SCREEN_MS = 15000
        private const val CACHE_SIZE = 300
        /** Bir satırın en çok bu kadar okuması birleştirilir ([ReadingConsensus]). */
        private const val MAX_READINGS = 8

        /** Çevirisi geldiğinde ekrandan kalkmış satırın TV'de kalacağı süre. */
        fun readingTime(text: String): Int = min(8000, max(2000, 1200 + 60 * text.length))
    }

    private val lock = Any()
    private data class CachedSubtitle(val text: String, val fromPack: Boolean)
    private val cache = object : LinkedHashMap<String, CachedSubtitle>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSubtitle>?) = size > CACHE_SIZE
    }
    private var latest = 0
    /** Ekranda duran satırın numarası; satır kalktıysa -1. */
    private var onScreen = -1
    private var lastShownSource: String? = null
    private var shownPackSource: String? = null

    /** Çeviri gösterildiğinde (kaynak, çeviri). */
    var onPair: ((String, String) -> Unit)? = null
    /** Çeviri başarısız olduğunda kaynak satır. */
    var onTranslateFailed: ((String) -> Unit)? = null
    /** Okunan ve temizlenen ham metin (boş olabilir); tanılama için. */
    var onReadText: ((String) -> Unit)? = null
    /**
     * Oyun paketi: kararlı satırın hazır Türkçesi (yoksa null). Çeviri motorundan önce, çeviri iş parçacığında bakılır;
     * bulunursa motora gidilmez ve paketteki metin olduğu gibi gösterilir (sözlük de uygulanmaz).
     */
    @Volatile var packLookup: ((String) -> PackMatch?)? = null
    /** Pakette bulunmayan, çeviri motoruna giden satır (ortak havuz için; çeviri iş parçacığında çağrılır). */
    @Volatile var onUnpacked: ((String) -> Unit)? = null
    /** Paketten gösterilen satır (kaynak, paket). */
    var onPackHit: ((String, PackMatch) -> Unit)? = null
    /** Paketten gösterilen satır sayısı. */
    @Volatile var packHits = 0
        private set

    /**
     * Parça gerçek bir İngilizce sözcük mü (sözcük listesi; yüklenene kadar null). Varsa karışan harfler onarılır
     * ([OcrConfusions]) ve satırın kararsız okumaları sözcük sözcük birleştirilir ([ReadingConsensus]).
     */
    @Volatile var wordCheck: ((String) -> Boolean)? = null
    /** Oyun dili İngilizce: Türkçe harfli bozuk okumalar da İngilizceye onarılır. */
    @Volatile var englishSource = false
    /** Altyazı bölgesine düşen, yinelenen arayüz yazıları ([RepeatedText]); null ise süzülmez. */
    @Volatile var repeats: RepeatedText? = null
    /** Arayüz yazısı yinelendiği için artık çevrilmeyecek satır (günlük için). */
    var onRepeatBlocked: ((String) -> Unit)? = null

    /** Onarılmış satırlar: okuma çoğu karede aynı geldiği için aynı satır yeniden onarılmaz. Okuma iş parçacığında. */
    private val repaired = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128
    }
    /** Kararlayıcı satırı beklerken gelen okumalar (kilit altında). */
    private val readings = mutableListOf<String>()

    /** Okunan bir satırın temizlenmiş hâli: gürültü, karışan harfler, konuşmacı adındaki bozukluklar. */
    private fun tidy(line: String): String {
        val clean = OcrNoise.clean(line)
        if (clean.isEmpty()) return clean
        val check = wordCheck
        val fixed = if (check == null) clean
        else repaired[clean] ?: OcrConfusions.repair(clean, check, englishSource).also { repaired[clean] = it }
        // Art arda iki addan öndeki ("HSS: GAETANO: …") sahneden okunan çöptür; büyük harfli addaki küçük harfler düzelir.
        return SpeakerNames.fixLabelCaps(SpeakerNames.withoutLeadingJunkLabel(fixed))
    }

    /** Bir karenin okuması: [lines] yukarıdan aşağı satırlar, [box] yazının ekrandaki yeri. */
    fun onRead(lines: List<String>, box: TextBox?, allowFallback: Boolean = true) {
        val tidied = lines.map { tidy(it) }.filter { it.isNotEmpty() }
        val text = (repeats?.without(tidied) ?: tidied).joinToString("\n")
        onReadText?.invoke(text)
        synchronized(lock) {
            noteSeen(text)
            val action = stabilizer.feed(text)
            when (action.kind) {
                // Son okumalar (en çok [MAX_READINGS], en eskisi düşer); satır kararlı olunca birleştirilir.
                StabilizerKind.NONE -> if (text.trim().length >= 3 && text !in readings) {
                    readings.add(text)
                    if (readings.size > MAX_READINGS) readings.removeAt(0)
                }
                StabilizerKind.CLEAR -> {
                    readings.clear()
                    onScreen = -1
                    shownPackSource = null
                    lastShownSource = null
                    sink.clear()
                }
                StabilizerKind.RESTORE -> {
                    readings.clear()
                    val source = action.text ?: return
                    val number = ++latest
                    onScreen = number
                    val cached = cache[key(source)]?.takeIf { allowFallback || it.fromPack }
                    if (cached != null) {
                        if (cached.fromPack || !PackCaptionContinuity.isReread(source, shownPackSource)) {
                            show(source, cached, box, ON_SCREEN_MS, replaces = false)
                        }
                    } else request(number, source, box, allowFallback)
                }
                StabilizerKind.TRANSLATE -> {
                    val source = settle(action.text ?: return)
                    val number = ++latest
                    onScreen = number
                    val cached = cache[key(source)]?.takeIf { allowFallback || it.fromPack }
                    if (cached != null) deliver(number, source, cached, box) else request(number, source, box, allowFallback)
                }
            }
        }
    }

    /**
     * Çevrilecek satır: kararlı olana kadar gelen okumaları sözcük sözcük birleştirilir ([ReadingConsensus]; yalnız aynı
     * satırın okumaları, satır ve sözcük sayısı aynı olanlar). Kilit altında çağrılır.
     */
    private fun settle(source: String): String {
        val check = wordCheck
        val normal = TextMatch.normalize(source)
        val related = readings.filter { TextMatch.isSimilar(TextMatch.normalize(it), normal, 0.7) }
        readings.clear()
        if (check == null || related.isEmpty()) return source
        return ReadingConsensus.merge(source, listOf(source) + related, check)
    }

    private fun request(number: Int, source: String, box: TextBox?, allowFallback: Boolean) {
        firstSeenOf[number] = if (seenAt > 0) seenAt else clock()
        requestedAt[number] = clock()
        translateExecutor.execute {
            val started = clock()
            val packed = try {
                packLookup?.invoke(source)?.takeIf { it.text.isNotBlank() }
            } catch (error: Exception) {
                null
            }
            if (packed == null && synchronized(lock) {
                val keepPacked = number == onScreen && PackCaptionContinuity.isReread(source, shownPackSource)
                if (!allowFallback || keepPacked) {
                    if (!allowFallback && number == onScreen && !keepPacked) stabilizer.forgetSent(lastShownSource)
                    firstSeenOf.remove(number)
                    requestedAt.remove(number)
                    true
                } else false
            }) return@execute
            if (packed == null) try { onUnpacked?.invoke(source.replace('\n', ' ')) } catch (_: Exception) {}
            // İki satırlık altyazı tek cümledir; çevirmene tek satır olarak gider.
            val translated = packed?.text?.trim() ?: try {
                translator.translate(if (joinLines) source.replace('\n', ' ') else source)?.trim()
            } catch (error: Exception) {
                null
            }
            synchronized(lock) {
                translateMsOf[number] = clock() - started
                packedOf[number] = packed != null
                if (packed != null) {
                    packHits++
                    onPackHit?.invoke(source, packed)
                }
                if (translated.isNullOrEmpty()) {
                    if (number == onScreen) stabilizer.forgetSent()
                    onTranslateFailed?.invoke(source)
                    return@synchronized
                }
                val result = CachedSubtitle(translated, packed != null)
                cache[key(source)] = result
                deliver(number, source, result, box)
            }
        }
    }

    /** Kilit altında çağrılır. Yerine yenisi gelmiş satırın geç kalan çevirisi gösterilmez. */
    private fun deliver(number: Int, source: String, translated: CachedSubtitle, box: TextBox?) {
        if (number != latest) return
        if (!translated.fromPack && PackCaptionContinuity.isReread(source, shownPackSource)) return
        // Çeviri kaynağıyla neredeyse aynıysa metin zaten hedef dildedir.
        if (!stabilizer.shouldSend(source, translated.text)) return
        val duration = if (number == onScreen) ON_SCREEN_MS else readingTime(translated.text)
        val replaces = lastShownSource?.let { TextMatch.isRevision(source, it) } ?: false
        show(source, translated, box, duration, replaces)
        val seen = firstSeenOf.remove(number)
        val asked = requestedAt.remove(number)
        val translateMs = translateMsOf.remove(number) ?: 0L
        val packed = packedOf.remove(number) ?: false
        if (seen != null && asked != null) onTiming?.invoke(source, clock() - seen, asked - seen, translateMs, packed)
        if (requestedAt.size > 32) { requestedAt.clear(); firstSeenOf.clear(); translateMsOf.clear(); packedOf.clear() }
    }

    private fun show(source: String, translated: CachedSubtitle, box: TextBox?, duration: Int, replaces: Boolean) {
        lastShownSource = source
        shownPackSource = source.takeIf { translated.fromPack }
        sink.show(translated.text, duration, box, replaces)
        // Kısa sürede aynen yinelenen ve cümle gibi bitmeyen satır arayüz yazısıdır; bir süre okunmaz.
        repeats?.note(source)?.let { blocked -> onRepeatBlocked?.invoke(blocked) }
        onPair?.invoke(source, translated.text)
    }

    private fun key(source: String): String {
        val normalized = TextMatch.normalize(source)
        return if (TextMatch.endsQuestion(source)) "$normalized?" else normalized
    }

    // --- Gecikme ölçümü (günlük için): satır ilk okunduğundan TV'ye gidene kadar geçen süre ---
    /** (kaynak, toplam ms, kararlılık bekleme ms, çeviri ms, paketten mi) */
    var onTiming: ((String, Long, Long, Long, Boolean) -> Unit)? = null
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    private var seenText = ""
    private var seenAt = 0L
    private val requestedAt = HashMap<Int, Long>()
    private val firstSeenOf = HashMap<Int, Long>()
    private val translateMsOf = HashMap<Int, Long>()
    private val packedOf = HashMap<Int, Boolean>()

    /** Kilit altında: okunan metin öncekinden belirgin farklıysa "ilk görülme" anı yenilenir. */
    private fun noteSeen(text: String) {
        if (text.isEmpty()) { seenText = ""; return }
        val normal = TextMatch.normalize(text)
        if (seenText.isEmpty() || !TextMatch.isSimilar(normal, seenText, 0.6)) seenAt = clock()
        seenText = normal
    }

    fun reset() {
        synchronized(lock) {
            latest++
            onScreen = -1
            lastShownSource = null
            shownPackSource = null
            readings.clear()
            if (stabilizer.reset().kind == StabilizerKind.CLEAR) sink.clear()
        }
    }
}

/**
 * Hangi karede yazı tanıma yapılacağını seçer: bölge değişmediyse son okuma yeniden kullanılır.
 * Oyun süresinin çoğunda bölge boştur ya da değişmez; telefonda bu pil ve ısı demektir (Windows'taki ReadScheduler).
 */
class ReadScheduler(private val changeThreshold: Int = 16, private val maxReuse: Int = 20) {
    private var previous: ByteArray? = null
    private var reused = 0

    /** true ise bölge değişmedi; son okuma geçerli. */
    fun canReuse(signature: ByteArray): Boolean {
        val before = previous
        previous = signature
        if (before != null && reused < maxReuse && !changed(before, signature)) {
            reused++
            return true
        }
        reused = 0
        return false
    }

    fun reset() {
        previous = null
        reused = 0
    }

    private fun changed(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return true
        for (i in a.indices) if (kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) > changeThreshold) return true
        return false
    }
}
