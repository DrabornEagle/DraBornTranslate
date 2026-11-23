// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

/** Ek alanların çevirisini TV'ye ileten taraf (gerçekte [SubtitleServer]). */
interface RegionSender {
    /** [text] boşsa alanın kutusu kalkar. */
    fun sendRegion(id: String, text: String, box: TextBox?, lineHeight: Double? = null)
    fun clearRegions()
}

/** Bir ek alanın okuması: satırlar (kutuları okunan görüntüye oranla) ve renkli yazılmış sözcük öbekleri. */
class RegionReading(val lines: List<TextLine>, val colored: List<ColoredPhrase> = emptyList())

/** Ek alanın görüntüsünü okur; bloklar. Okunamazsa hata fırlatır (boş alan boş okuma döner). */
fun interface RegionReader {
    fun read(image: PixelImage): RegionReading
}

/**
 * Kumanda simgelerini ve renkli sözcükleri görüntüden tanıyan okuyucu (Windows: WindowsOcrReader, ReadsIcons açıkken).
 * [recognize] görüntüdeki satırları ve sözcükleri yerleriyle verir (görüntüye oranla, 0–1); telefonda ML Kit'tir. Yazı
 * tanımanın atladığı ya da yanlış okuduğu düğme simgesi satıra eklenir ("Hold ✕ to climb"); simgenin içindeki yazı
 * ("L2") gerekirse aynı tanıyıcıyla küçük bir kesitten okunur.
 */
class IconReader(private val recognize: (PixelImage) -> List<OcrLine>) : RegionReader {
    /** Simgelerin hangi kumandadan olduğu kararı (seçilmişse sabit, Otomatik'te karelerden; Windows: ButtonLayout). */
    val layout = ButtonLayoutVote()

    override fun read(image: PixelImage): RegionReading {
        val raw = recognize(image)
        // Renkli yazılmış sözcükler (GTA 6: "Go to the plane" içinde pembe "plane"); çeviride aynı renkte gösterilir.
        val colored = raw.flatMap { ColoredWords.phrases(it.words, image) }
        val width = image.width.toDouble()
        val height = image.height.toDouble()
        val lines = raw.map { line ->
            val box = line.box ?: return@map TextLine(line.text, null)
            // Düz okunan tuş adları ("Hold LT to aim") Otomatik kararı besler.
            IconWords.observe(line.text, layout)
            var text = IconWords.withIcons(line.text, line.words, width, height) { place ->
                ButtonIcons.classify(image, place, ::readPlain, line = box, readWords = ::readWords, layout = layout)
            }
            text = IconWords.withEdgeIcons(text, box, width, height) { place ->
                ButtonIcons.classify(image, place, ::readPlain, strict = true, line = TextBox(minOf(box.left, place.left), minOf(box.top, place.top), maxOf(box.right, place.right), maxOf(box.bottom, place.bottom)),
                    readWords = ::readWords, layout = layout)
            }
            // Xbox oyununda harf olarak okunan tuşlar ("Press A to jump") işarete çevrilir.
            text = IconWords.withXboxLetters(text, layout.current)
            TextLine(text.trim(), box)
        }
        return RegionReading(lines, colored)
    }

    /** Simgenin içindeki kısa yazı ("L2"): kesit düz okunur. */
    private fun readPlain(image: PixelImage): String? =
        recognize(image).joinToString(" ") { it.text }.trim().ifEmpty { null }

    /** Satırın siyah-beyaz okumasındaki sözcükler ve yerleri (simgenin yerine düşen sözcük aranır). */
    private fun readWords(image: PixelImage): List<OcrWord> = recognize(image).flatMap { it.words }
}

/**
 * Ek alanları ana altyazıdan ayrı ve seyrek okur, çevirip TV'ye kendi yerleriyle gönderir (Windows: RegionWatcher.cs,
 * aynı kurallar; CouchSubs / Nino Game Translator AGPL-3.0 RegionWatcher uyarlaması).
 * - Ana okuma hattını beklemez, onu bekletmez: [offer] hemen döner, tur [executor]'da sürer.
 * - En çok [intervalMs]'de bir tur atılır; önceki tur bitmediyse kare atlanır.
 * - Bölge izi değişmeyen alan yeniden okunmaz.
 * - Yazı ancak kararlıysa çevrilir: iz bir tur değişmediyse ya da iki okuma aynı yazıyı verdiyse.
 * - Yazı değişmedikçe yeniden çevrilmez; boşalan alanın kutusu kalkar.
 * - Paket ve çeviri motoru altyazıyla aynıdır; ama ek alan satırları ortak havuza önerilmez.
 */
class RegionWatcher(
    private val reader: RegionReader,
    /** Çeviri motoru; hat yeniden kurulunca değiştirilir. Çevrilemezse null döner. */
    @Volatile var translator: TextTranslator,
    private val server: RegionSender,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    /** Turların çalıştığı yer (telefonda ayrı bir iş parçacığı; testlerde aynı iş parçacığı). */
    private val executor: Executor = Executor { it.run() },
    /** Kararlılık beklenirken çevirinin şimdiden istendiği yer; null ise önceden çevrilmez. */
    private val prefetchExecutor: Executor? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object {
        /** Windows'taki tur aralığı. Telefon pil ve ısı için daha seyrek tur atar ([PHONE_INTERVAL_MS]). */
        const val DEFAULT_INTERVAL_MS = 250L
        /** Telefondaki tur aralığı: yazı yine bir saniye içinde çevrilir, işlemci ana altyazıya kalır. */
        const val PHONE_INTERVAL_MS = 450L
        private const val RENDERED_LIMIT = 64
        /** Yarım okuma en çok bu kadar tur bekletilir; tamamı gelmezse yine de gösterilir. */
        private const val PREFIX_WAIT_LIMIT = 4

        private fun words(key: String) = key.split(' ').filter { token -> token.isNotEmpty() && token.any { it.isLetterOrDigit() } }

        /**
         * Okuma, gösterilen yazının eksik hâli mi ("find □" ← "find donnie"): sözcüklerinin hepsi gösterilende geçiyor
         * ama gösterilenin bir sözcüğü kayıp.
         */
        internal fun losesWords(reading: String, shown: String): Boolean {
            val have = words(reading)
            val shownWords = words(shown)
            val lost = shownWords.count { word -> have.none { it == word || TextMatch.isSimilar(it, word, 0.75) } }
            return lost > 0 && have.isNotEmpty() && have.all { word -> shownWords.any { it == word || TextMatch.isSimilar(it, word, 0.75) } }
        }

        internal fun isPartOf(reading: String, shown: String): Boolean {
            val part = reading.split(' ').filter { it.isNotEmpty() }
            val whole = shown.split(' ').filter { it.isNotEmpty() }
            if (part.isEmpty() || part.size >= whole.size) return false
            var at = 0
            var misses = 0
            for (word in part) {
                var found = -1
                for (i in at until whole.size) if (whole[i] == word || TextMatch.isSimilar(whole[i], word, 0.75)) { found = i; break }
                if (found < 0) {
                    if (++misses > 1) return false
                    continue
                }
                at = found + 1
            }
            return true
        }

        private fun icons(paragraphs: List<String>) = paragraphs.sumOf { p -> p.split(' ').count { it.isNotEmpty() && ButtonPrompts.isIcon(it) } }

        /** En az iki harfi ve iki harfli bir sözcüğü olan satır yazıdır; geri kalanı sayı ve simgedir. */
        internal fun isWords(text: String): Boolean =
            text.count { it.isLetter() } >= 2 && OcrNoise.tokens(text).any { word -> word.count { it.isLetter() } >= 2 }

        /** Satırlar çevrilecek parçalara: cümle bitince ya da kısa başlıktan sonra büyük harfle yeni parça başlar. */
        internal fun paragraphs(lines: List<TextLine>): List<String> {
            val result = mutableListOf<String>()
            for (line in lines.map { it.text.trim() }) {
                if (result.isNotEmpty() && !startsNew(result.last(), line)) result[result.size - 1] = result.last() + " " + line
                else result.add(line)
            }
            return result
        }

        private fun startsNew(previous: String, line: String): Boolean =
            SubtitleStabilizer.endsSentence(previous) ||
                (line[0].isUpperCase() && OcrNoise.tokens(previous).size <= 3 && !previous.endsWith(','))

        private fun union(a: TextBox, b: TextBox) = TextBox(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

        /** [box] [area] içindeki oranlarsa, aynı yerin [area]'yı içeren çerçevedeki oranları. */
        private fun placedIn(box: TextBox, area: TextBox): TextBox {
            val width = area.right - area.left
            val height = area.bottom - area.top
            return TextBox(area.left + box.left * width, area.top + box.top * height, area.left + box.right * width, area.top + box.bottom * height)
        }

        /**
         * Okumadan çevrilecek parçalar: kumanda ipuçları (simgeler tanınmıyorsa), yazı tanıma çöpleri ve harfsiz
         * göstergeler atılır; satırlar yukarıdan aşağı parçalara toplanır. [wordCheck]: sözcük listesi ("m" onarımı için).
         */
        internal fun makeCandidate(reading: List<TextLine>, region: TextBox, keepPrompts: Boolean = false,
                                   wordCheck: ((String) -> Boolean)? = null): Candidate {
            // Alanın sol ya da sağ kenarına değen satır, alanın dışında süren bir yazının parçasıdır (uzun altyazı satırının
            // ucu telefon alanına taşıyordu: "ot it?", "nd..."). Kesilen harfler okunmadığından kesik satır kenara tam
            // değmeyebilir: pay %4.
            var lines = inReadingOrder(reading
                .filter { it.text.isNotBlank() && (keepPrompts || !ButtonPrompts.isPrompt(it.text)) }
                .map { it.copy(text = OcrNoise.clean(it.text)) }
                .filter { it.text.isNotBlank() && isWords(it.text) }
                .filter { line -> line.box == null || (line.box.left > 0.04 && line.box.right < 0.96) })
            // Oyunun menü listesi (GTA 6'da duraklatınca soldaki "Game / Store / Help") çevrilmez; alan boş sayılır.
            if (isMenuList(lines)) lines = emptyList()
            // Küçük yazıda "m" harfi "rn" / "nn" okunuyor ("frorn your noailbox"): sözlüğe göre düzeltilir.
            val paragraphs = paragraphs(lines).map { LetterM.fix(it, wordCheck) }
            val boxes = lines.mapNotNull { it.box }
            val box = if (boxes.isEmpty()) null else placedIn(boxes.drop(1).fold(boxes[0], ::union), region)
            val heights = boxes.map { (it.bottom - it.top) * (region.bottom - region.top) }.sorted()
            val lineHeight = if (heights.isEmpty()) null else heights[heights.size / 2]
            // Anahtar tuş simgelerini de içerir: simgesi sonradan bulunan okuma ("SET DESTINATION ✕") eskisinden ayrılsın.
            val key = paragraphs.flatMap { p -> p.split(' ').filter { it.isNotEmpty() } }
                .map { token -> if (ButtonPrompts.isIcon(token) && token.none { it.isLetterOrDigit() }) token else TextMatch.normalize(token) }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            return Candidate(paragraphs, box, lineHeight, key)
        }

        /**
         * Satırlar okuma sırasıyla: yukarıdan aşağı, aynı hizadakiler soldan sağa. Başka renkte yazılmış sözcük ayrı satır
         * okunuyor ve biraz yukarıda başladığı için öne geçiyordu ("Brian Follow").
         */
        internal fun inReadingOrder(source: List<TextLine>): List<TextLine> {
            val rows = mutableListOf<MutableList<TextLine>>()
            for (line in source.sortedBy { it.box?.top ?: 0.0 }) {
                val box = line.box
                val row = if (box == null) null else rows.firstOrNull { r ->
                    val first = r[0].box ?: return@firstOrNull false
                    abs((first.top + first.bottom) / 2 - (box.top + box.bottom) / 2) < max(first.bottom - first.top, box.bottom - box.top) * 0.5
                }
                if (row == null) rows.add(mutableListOf(line)) else row.add(line)
            }
            return rows.flatMap { row -> row.sortedBy { it.box?.left ?: 0.0 } }
        }

        /**
         * Menü listesi mi: alt alta en az üç kısa satır (en çok üç sözcük, tuş simgeleri sayılmaz), hiçbiri cümle gibi
         * bitmiyor ve hiçbiri tuş simgesiyle bitmiyor. Sonu tuşla biten satır tuş göstergesidir ("SET DESTINATION ✕") ve
         * çevrilir; menü maddesinde simge başta durur ("○ Store") ya da hiç yoktur.
         */
        internal fun isMenuList(lines: List<TextLine>): Boolean {
            if (lines.size < 3) return false
            return lines.all { line ->
                val tokens = line.text.split(' ').filter { it.isNotEmpty() }
                if (tokens.isEmpty() || ButtonPrompts.isIcon(tokens.last())) return@all false
                val count = tokens.count { word -> !ButtonPrompts.isIcon(word) && word.any { it.isLetter() } }
                count in 1..3 && !SubtitleStabilizer.endsSentence(line.text)
            }
        }

        private fun excerpt(text: String) = if (text.length <= 120) text else text.take(119) + "…"

        /** Kutunun karedeki yeri (günlük için). */
        private fun place(box: TextBox?) = if (box == null) "" else
            String.format(Locale.ROOT, " [x %%%.0f–%.0f, y %%%.0f–%.0f]", box.left * 100, box.right * 100, box.top * 100, box.bottom * 100)
    }

    /** Okunan alandan çevrilecek yazı: parçalar, karedeki yeri, satır yüksekliği ve karşılaştırma anahtarı. */
    internal data class Candidate(val paragraphs: List<String>, val box: TextBox?, val lineHeight: Double?, val key: String,
                                  val colored: List<ColoredPhrase> = emptyList())

    private class State {
        var signature: ByteArray? = null
        var lastRead: String? = null
        var candidate: Candidate? = null
        var shown = ""
        var renderedKey: String? = null
        var renderedText: String? = null
        var renderedBox: TextBox? = null
        var renderedLineHeight: Double? = null
        /** Son gösterilen (boş olmayan) yazı; alan boşalınca da hatırlanır. */
        var remembered = ""
        /** Yarım okuma ("Fly to a", tamamı "Fly to a drop") kaç turdur bekletiliyor. */
        var prefixWaits = 0
        /** Gösterilen yazıdaki tuş simgesi sayısı. */
        var shownIcons = 0
    }

    private class Translated(val result: Array<String?>, val fromPack: Int)

    private class TranslationFailed : Exception("çeviri motoru yanıt vermedi")

    /**
     * Çevrilmiş yazılar (anahtar → TV'ye giden metin). Oyun görev yazısını arada silip yeniden gösteriyor; aynı yazı
     * gelince çevirmene yeniden gidilmez, anında gösterilir.
     */
    private val rendered = HashMap<String, String>()
    /** Süren ya da bitmiş çeviriler (anahtar → paragrafların çevirisi). */
    private val inflight = HashMap<String, FutureTask<Translated>>()
    @Volatile private var states = HashMap<String, State>()
    @Volatile private var lastPass: Long? = null
    private val busyFlag = AtomicBoolean(false)
    private val session = AtomicInteger(0)
    private val passCount = AtomicInteger(0)
    private val readCount = AtomicInteger(0)

    /** Oyun paketi: satırın hazır çevirisi (ana altyazıyla aynı arama; önce tuş çubuğu sözlüğü). */
    @Volatile var packLookup: ((String) -> PackMatch?)? = null
    @Volatile var log: ((String) -> Unit)? = null
    /** Tuş ipuçları ("Hold ✕ to climb", "✕ Select ○ Back") atılmaz, simgesiyle çevrilir (okuyucu simgeleri tanıyorsa). */
    @Volatile var keepsPrompts = false
    /** Ek alanda çevrilmeyecek satır (altyazının alana düşen satırı); null ise hepsi çevrilir. */
    @Volatile var skipLine: ((String) -> Boolean)? = null
    /** Sözcük listesi (İngilizce oyunlarda); küçük yazıdaki "m" okuma hatası bununla onarılır. */
    @Volatile var wordCheck: ((String) -> Boolean)? = null

    val passes: Int get() = passCount.get()
    val reads: Int get() = readCount.get()
    val isBusy: Boolean get() = busyFlag.get()

    /** Her karede çağrılabilir; tur zamanı geldiyse ve önceki tur bittiyse turu [executor]'da başlatır, hemen döner. */
    fun offer(frame: RegionFrame, areas: List<WatchedArea>) {
        if (areas.isEmpty() && !anyShown()) return
        val time = clock()
        val last = lastPass
        if (last != null && time - last < intervalMs) return
        if (!busyFlag.compareAndSet(false, true)) return
        lastPass = time
        try {
            executor.execute {
                try {
                    pass(frame, areas)
                } catch (error: Throwable) {
                    log?.invoke("ek alan turu hata verdi: ${error.message}")
                } finally {
                    busyFlag.set(false)
                }
            }
        } catch (error: Throwable) {
            // Yürütücü kapandıysa tur atılmaz.
            busyFlag.set(false)
        }
    }

    private fun anyShown(): Boolean {
        val map = states
        return synchronized(map) { map.values.any { it.shown.isNotEmpty() } }
    }

    /** Bir tur: kaldırılan alanların kutusu kalkar, her alan gerekirse okunur ve kararlı yazısı çevrilir. Testler doğrudan çağırır. */
    fun pass(frame: RegionFrame, areas: List<WatchedArea>) {
        val started = session.get()
        val map = states
        passCount.incrementAndGet()
        val ids = areas.map { it.id }.toSet()
        val removed = synchronized(map) {
            val gone = map.filter { (id, state) -> id !in ids && state.shown.isNotEmpty() }.keys.toList()
            map.keys.retainAll(ids)
            gone
        }
        for (id in removed) server.sendRegion(id, "", null)
        for (area in areas) {
            visit(area, frame, map, started)
            if (session.get() != started) return
        }
    }

    private fun visit(area: WatchedArea, frame: RegionFrame, map: HashMap<String, State>, started: Int) {
        val crop = frame.area(area.region) ?: return
        if (crop.width < 2 || crop.height < 2) return
        val signature = PixelImage.signature(crop)
        val state = synchronized(map) { map.getOrPut(area.id) { State() } }
        val previous = state.signature
        val changed = previous == null || PixelImage.changed(previous, signature)
        state.signature = signature
        if (!changed) {
            // İz bir tur değişmedi: okunan yazı kararlıdır.
            val stable = state.candidate
            if (stable != null) {
                state.candidate = null
                commit(stable, area, state, started)
            }
            return
        }
        val reading = try {
            reader.read(crop)
        } catch (error: Exception) {
            // Yazı tanıma hatası boş alan değildir. Eski kutu kalır; aynı kare sonraki turda yeniden okunur. Başarısız
            // kare, önceki adayın kararlılığını da doğrulayamaz.
            state.signature = null
            state.lastRead = null
            state.candidate = null
            return
        }
        if (session.get() != started) return
        readCount.incrementAndGet()
        val skip = skipLine
        val lines = if (skip != null) reading.lines.filter { !skip(it.text) } else reading.lines
        var candidate = makeCandidate(lines, area.region, keepsPrompts, wordCheck)
        if (reading.colored.isNotEmpty())
            candidate = candidate.copy(colored = reading.colored.filter { phrase -> candidate.paragraphs.any { it.contains(phrase.text) } })
        if (state.lastRead == candidate.key) {
            state.candidate = null
            commit(candidate, area, state, started)
        } else {
            state.lastRead = candidate.key
            state.candidate = candidate
            // Yazı kararlı mı diye bir tur beklenirken çevirisi şimdiden istenir: doğrulanınca çoğu zaman hazırdır.
            prefetch(candidate, state)
        }
    }

    private fun commit(original: Candidate, area: WatchedArea, state: State, started: Int) {
        var candidate = original
        if (state.shown == candidate.key) {
            val text = state.renderedText
            if (state.renderedKey != candidate.key || text.isNullOrEmpty()) return
            if (state.renderedBox == candidate.box && state.renderedLineHeight == candidate.lineHeight) return
            server.sendRegion(area.id, text, candidate.box, candidate.lineHeight)
            state.renderedBox = candidate.box
            state.renderedLineHeight = candidate.lineHeight
            return
        }
        // Yazı belirirken renkli sözcük geç geliyor: önce "Fly to a", yarım saniye sonra "Fly to a drop" okunuyordu.
        // Az önce gösterilen yazının başı okunursa tamamı beklenir.
        if (candidate.key.isNotEmpty() && state.remembered.length > candidate.key.length &&
            state.remembered.startsWith(candidate.key + " ") && state.prefixWaits < PREFIX_WAIT_LIMIT) {
            state.prefixWaits++
            state.candidate = candidate
            return
        }
        state.prefixWaits = 0
        // Ekrandakiyle neredeyse aynı okuma (harita arkada oynarken tuş çubuğu "SET DESTINATION ✕ EXIT ○" / "SET
        // DESTINATIPN EXIT ○" diye okunup kutu sürekli değişiyordu): daha çok tuş simgesi bulmadıysa ekrandaki kalır.
        // Simgesi fazla ama ekrandakinin bir sözcüğünü simgeyle değiştirmiş ("Find □" ← "Find Donnie"): bozuk okumadır.
        val candidateIcons = icons(candidate.paragraphs)
        if (candidate.key.isNotEmpty() && state.shown.isNotEmpty() && state.shown != candidate.key &&
            ((candidateIcons <= state.shownIcons && (TextMatch.isSimilar(state.shown, candidate.key, 0.85) || isPartOf(candidate.key, state.shown))) ||
                (candidateIcons > state.shownIcons && losesWords(candidate.key, state.shown))))
            return
        // Altyazı bu sırada gösterilmiş olabilir: alana taşan altyazı ucu gönderilmeden önce bir kez daha denetlenir.
        val skipNow = skipLine
        if (candidate.key.isNotEmpty() && skipNow != null && candidate.paragraphs.all(skipNow))
            candidate = Candidate(emptyList(), null, null, "")
        val previousShown = state.shown
        state.shown = candidate.key
        state.shownIcons = icons(candidate.paragraphs)
        val cached = if (candidate.key.isEmpty()) null else synchronized(rendered) { rendered[candidate.key] }
        if (cached != null) {
            // Daha önce çevrildi: anında, çevirmene gitmeden.
            if (cached.isEmpty()) server.sendRegion(area.id, "", null) else server.sendRegion(area.id, cached, candidate.box, candidate.lineHeight)
            state.renderedKey = candidate.key
            state.renderedText = cached
            state.renderedBox = if (cached.isEmpty()) null else candidate.box
            state.renderedLineHeight = if (cached.isEmpty()) null else candidate.lineHeight
            state.remembered = candidate.key
            log?.invoke("ek alan ${area.name}: ${excerpt(candidate.paragraphs.joinToString(" "))} → önceki çeviri ⇒ " +
                excerpt(ColoredWords.plain(cached.replace('\n', '/'))))
            return
        }
        if (candidate.key.isEmpty()) {
            server.sendRegion(area.id, "", null)
            state.renderedKey = candidate.key
            state.renderedText = ""
            state.renderedBox = null
            state.renderedLineHeight = null
            log?.invoke("ek alan ${area.name}: boşaldı")
            return
        }
        val translated = try {
            val task = translation(candidate)
            // Önceden istenmediyse (ya da sırada bekliyorsa) burada çalışır; çalışıyorsa bitmesi beklenir.
            task.run()
            task.get().also { synchronized(inflight) { inflight.remove(candidate.key) } }
        } catch (error: Exception) {
            if (session.get() == started) {
                if (state.shown == candidate.key) {
                    state.shown = previousShown
                    state.candidate = candidate
                }
                val reason = (if (error is ExecutionException) error.cause else error)?.message
                log?.invoke("ek alan ${area.name}: ${excerpt(candidate.paragraphs.joinToString(" "))} çevrilemedi ($reason)")
            }
            return
        }
        if (session.get() != started || state.shown != candidate.key) return
        // Motorun düşürdüğü tuş simgeleri yerine konur ("SHOW TEXT △" ⇒ "Metni göster" ⇒ "Metni göster △").
        var text = candidate.paragraphs.mapIndexed { i, source ->
            translated.result.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { UiPrompts.keepIcons(source, it) } ?: source
        }
        // Çevirisi aslıyla aynı (ad, sayı, zaten hedef dilde): kutu gösterilmez.
        val same = candidate.paragraphs.indices.all { i ->
            TextMatch.isSimilar(TextMatch.normalize(candidate.paragraphs[i]), TextMatch.normalize(text[i]), 0.90)
        }
        if (same) {
            remember(candidate.key, "")
            server.sendRegion(area.id, "", null)
            state.renderedKey = candidate.key
            state.renderedText = ""
            state.renderedBox = null
            state.renderedLineHeight = null
            return
        }
        if (candidate.colored.isNotEmpty()) text = withColors(candidate, text)
        val origin = when {
            translated.fromPack == candidate.paragraphs.size -> "paket"
            translated.fromPack > 0 -> "paket+motor"
            else -> "motor"
        }
        val joined = text.joinToString("\n")
        log?.invoke("ek alan ${area.name}: ${excerpt(candidate.paragraphs.joinToString(" "))} → $origin ⇒ " +
            excerpt(ColoredWords.plain(text.joinToString(" / "))) + place(candidate.box))
        server.sendRegion(area.id, joined, candidate.box, candidate.lineHeight)
        remember(candidate.key, joined)
        state.remembered = candidate.key
        state.renderedKey = candidate.key
        state.renderedText = joined
        state.renderedBox = candidate.box
        state.renderedLineHeight = candidate.lineHeight
    }

    /**
     * Renkli sözcüklerin çevirisi aynı renkte gösterilir: her renkli öbek tek başına çevrilir (önce paket) ve karşılığı
     * cümlede işaretlenir ([ColoredWords.mark]). Çeviri olmazsa metin renksiz kalır.
     */
    private fun withColors(candidate: Candidate, text: List<String>): List<String> {
        val parts = mutableListOf<Pair<String, String>>()
        for (phrase in candidate.colored) {
            try {
                val translated = packLookup?.invoke(phrase.text)?.text ?: translator.translate(phrase.text)
                if (!translated.isNullOrBlank()) parts.add(translated.trim().trimEnd('.', '!', '?') to phrase.color)
            } catch (error: Exception) {
                // Renk süs: çevrilemezse sözcük renksiz kalır.
            }
        }
        if (parts.isEmpty()) return text
        val marked = text.map { ColoredWords.mark(it, parts) }
        log?.invoke("renkli: ${candidate.colored.joinToString(", ") { it.text + " " + it.color }} → ${parts.joinToString(", ") { it.first }}" +
            if (marked == text) " (çeviride bulunamadı)" else "")
        return marked
    }

    /** Çevirinin görevini verir (yoksa kurar, henüz başlatmaz); başarısızı listeden çıkar ki sonra yeniden denensin. */
    private fun translation(candidate: Candidate): FutureTask<Translated> {
        val key = candidate.key
        val paragraphs = candidate.paragraphs
        synchronized(inflight) {
            inflight[key]?.let { return it }
            if (inflight.size > 32) inflight.clear()
            val task = object : FutureTask<Translated>(java.util.concurrent.Callable { translateAll(paragraphs) }) {
                override fun setException(t: Throwable?) {
                    synchronized(inflight) { if (inflight[key] === this) inflight.remove(key) }
                    super.setException(t)
                }
            }
            inflight[key] = task
            return task
        }
    }

    /**
     * Kararlılık beklenirken çeviriyi başlatır. Yarım okuma (az önce gösterilen yazının başı) ve daha önce çevrilmiş yazı
     * için istenmez.
     */
    private fun prefetch(candidate: Candidate, state: State) {
        val executor = prefetchExecutor ?: return
        if (candidate.key.isEmpty() || candidate.key == state.shown) return
        if (state.remembered.startsWith(candidate.key + " ")) return
        if (synchronized(rendered) { rendered.containsKey(candidate.key) }) return
        val skip = skipLine
        if (skip != null && candidate.paragraphs.all(skip)) return
        // Hata gözlenmez; asıl gösterimde yeniden denenir.
        runCatching { executor.execute(translation(candidate)) }
    }

    private fun translateAll(paragraphs: List<String>): Translated {
        val lookup = packLookup
        val result = Array(paragraphs.size) { i -> lookup?.invoke(paragraphs[i])?.text?.takeIf { it.isNotBlank() } }
        val fromPack = result.count { it != null }
        val engine = translator
        for (i in result.indices) {
            if (result[i] != null) continue
            result[i] = engine.translate(paragraphs[i]) ?: throw TranslationFailed()
        }
        return Translated(result, fromPack)
    }

    private fun remember(key: String, text: String) {
        synchronized(rendered) {
            if (rendered.size >= RENDERED_LIMIT) rendered.clear()
            rendered[key] = text
        }
    }

    /** Çeviri durdu ya da hat yeniden kuruldu: süren tur bırakılır, TV'deki ek alan kutuları kalkar. */
    fun reset() {
        session.incrementAndGet()
        val shown = anyShown()
        states = HashMap()
        lastPass = null
        if (shown) server.clearRegions()
    }
}
