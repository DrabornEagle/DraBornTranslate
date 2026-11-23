// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Ek alanlar: ana altyazıdan ayrı, seyrek okunur; yazı kararlıysa ve değiştiyse çevrilir (Windows: RegionWatcherTests;
 * CouchSubs / Nino uyarlaması). Turlar testte aynı iş parçacığında çalışır.
 */
class RegionWatcherTest {
    private class Reader : RegionReader {
        var lines: List<TextLine> = emptyList()
        var colored: List<ColoredPhrase> = emptyList()
        var count = 0
        var failuresRemaining = 0
        override fun read(image: PixelImage): RegionReading {
            count++
            if (failuresRemaining > 0) {
                failuresRemaining--
                throw IllegalStateException("beklenen okuma hatası")
            }
            return RegionReading(lines, colored)
        }
    }

    private class Translator : TextTranslator {
        val calls = mutableListOf<String>()
        var failuresRemaining = 0
        override fun translate(text: String): String? {
            calls.add(text)
            if (failuresRemaining > 0) {
                failuresRemaining--
                return null
            }
            return "TR: $text"
        }
    }

    private class Sent(val id: String, val text: String, val box: TextBox?, val lineHeight: Double?)

    private class Server : RegionSender {
        val sent = mutableListOf<Sent>()
        var clears = 0
        override fun sendRegion(id: String, text: String, box: TextBox?, lineHeight: Double?) {
            sent.add(Sent(id, text, box, lineHeight))
        }
        override fun clearRegions() {
            clears++
        }
    }

    /** Düz gri kare; [shade] değişince bölge izi değişir. */
    private fun shade(value: Int): PixelImage {
        val image = PixelImage(320, 180)
        image.pixels.fill((0xFF shl 24) or (value shl 16) or (value shl 8) or value)
        return image
    }

    private val quest = WatchedArea("q", "Quest", ExtraAreaPreset.QUEST.region)
    private val phone = WatchedArea("p", "Phone", ExtraAreaPreset.PHONE.region)

    private fun line(text: String, top: Double = 0.2, height: Double = 0.1) = TextLine(text, TextBox(0.1, top, 0.9, top + height))

    private fun texts(server: Server) = server.sent.map { it.text }

    @Test
    fun failedReadRetriesOnAnUnchangedFrameThenStopsReading() {
        val reader = Reader().apply { lines = listOf(line("Find the blacksmith")); failuresRemaining = 1 }
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server)
        watcher.pass(shade(10), listOf(quest))
        watcher.pass(shade(10), listOf(quest))
        // Başarılı yeni okuma yine kararlılık bekler.
        assertTrue(server.sent.isEmpty())
        repeat(4) { watcher.pass(shade(10), listOf(quest)) }
        assertEquals(2, reader.count)
        assertEquals(listOf("Find the blacksmith"), translator.calls)
        assertEquals(listOf("TR: Find the blacksmith"), texts(server))
    }

    @Test
    fun failedReadKeepsShownTextAndDiscardsUnconfirmedReading() {
        val reader = Reader().apply { lines = listOf(line("Talk to Arthur")) }
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server)
        watcher.pass(shade(10), listOf(quest))
        watcher.pass(shade(10), listOf(quest))
        reader.lines = listOf(line("Meet Arthur at the barn"))
        watcher.pass(shade(60), listOf(quest))
        reader.failuresRemaining = 1
        watcher.pass(shade(100), listOf(quest))
        reader.lines = listOf(line("Find the blacksmith"))
        watcher.pass(shade(100), listOf(quest))
        assertEquals(listOf("TR: Talk to Arthur"), texts(server))
        watcher.pass(shade(100), listOf(quest))
        assertEquals(listOf("Talk to Arthur", "Find the blacksmith"), translator.calls)
        assertEquals(listOf("TR: Talk to Arthur", "TR: Find the blacksmith"), texts(server))
    }

    @Test
    fun failedReadDoesNotBlockAnotherArea() {
        val reader = Reader().apply { lines = listOf(line("Find the blacksmith")); failuresRemaining = 1 }
        val server = Server()
        val watcher = RegionWatcher(reader, Translator(), server)
        watcher.pass(shade(10), listOf(quest, phone))
        watcher.pass(shade(10), listOf(quest, phone))
        assertEquals(listOf("p"), server.sent.map { it.id })
        watcher.pass(shade(10), listOf(quest, phone))
        assertEquals(listOf("p", "q"), server.sent.map { it.id })
        assertEquals(3, reader.count)
    }

    @Test
    fun stableTextIsTranslatedOnceAndPlacedInItsArea() {
        val reader = Reader()
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server)
        reader.lines = listOf(line("Find the blacksmith"))
        watcher.pass(shade(10), listOf(quest))
        assertTrue(server.sent.isEmpty())
        watcher.pass(shade(10), listOf(quest))
        assertEquals(1, server.sent.size)
        val sent = server.sent[0]
        assertEquals("TR: Find the blacksmith", sent.text)
        assertTrue(sent.box!!.left > quest.region.left && sent.box!!.right < quest.region.right)
        watcher.pass(shade(10), listOf(quest))
        watcher.pass(shade(10), listOf(quest))
        assertEquals(1, reader.count)
        assertEquals(1, translator.calls.size)
        assertEquals(1, server.sent.size)
    }

    @Test
    fun sameTextAtNewGeometryUpdatesRegionWithoutRetranslating() {
        val reader = Reader().apply { lines = listOf(line("Find the blacksmith", 0.2, 0.1)) }
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server)
        watcher.pass(shade(10), listOf(quest))
        watcher.pass(shade(10), listOf(quest))
        val first = server.sent[0]
        reader.lines = listOf(line("Find the blacksmith", 0.45, 0.2))
        watcher.pass(shade(60), listOf(quest))
        assertEquals(listOf("TR: Find the blacksmith", "TR: Find the blacksmith"), texts(server))
        assertNotEquals(first.box?.top, server.sent[1].box?.top)
        assertNotEquals(first.lineHeight, server.sent[1].lineHeight)
        assertEquals(listOf("Find the blacksmith"), translator.calls)
    }

    @Test
    fun failedTranslationRetriesWithoutLosingPreviouslyShownText() {
        val reader = Reader()
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server)
        reader.lines = listOf(line("Talk to Arthur"))
        watcher.pass(shade(10), listOf(quest))
        watcher.pass(shade(10), listOf(quest))
        assertEquals(listOf("TR: Talk to Arthur"), texts(server))
        reader.lines = listOf(line("Meet Arthur at the barn"))
        translator.failuresRemaining = 1
        watcher.pass(shade(90), listOf(quest))
        watcher.pass(shade(90), listOf(quest))
        assertEquals(listOf("TR: Talk to Arthur"), texts(server))
        watcher.pass(shade(90), listOf(quest))
        assertEquals(listOf("Talk to Arthur", "Meet Arthur at the barn", "Meet Arthur at the barn"), translator.calls)
        assertEquals(listOf("TR: Talk to Arthur", "TR: Meet Arthur at the barn"), texts(server))
    }

    @Test
    fun movingBackgroundWithTheSameTextIsNotTranslatedAgain() {
        val reader = Reader().apply { lines = listOf(line("Talk to Arthur")) }
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server)
        for (value in listOf(10, 60, 110, 160, 210)) watcher.pass(shade(value), listOf(quest))
        assertEquals(5, reader.count)
        assertEquals(listOf("Talk to Arthur"), translator.calls)
        assertEquals(listOf("TR: Talk to Arthur"), texts(server))
    }

    @Test
    fun changedTextIsTranslatedAndEmptyAreaRemovesTheBox() {
        val reader = Reader()
        val server = Server()
        val watcher = RegionWatcher(reader, Translator(), server)
        reader.lines = listOf(line("Talk to Arthur"))
        watcher.pass(shade(10), listOf(quest))
        watcher.pass(shade(10), listOf(quest))
        reader.lines = listOf(line("Meet Arthur at the barn"))
        watcher.pass(shade(90), listOf(quest))
        watcher.pass(shade(90), listOf(quest))
        reader.lines = emptyList()
        watcher.pass(shade(200), listOf(quest))
        watcher.pass(shade(200), listOf(quest))
        assertEquals(listOf("TR: Talk to Arthur", "TR: Meet Arthur at the barn", ""), texts(server))
    }

    @Test
    fun promptsAndNumbersShowNothing() {
        val reader = Reader().apply { lines = listOf(line("R2 Swing"), line("23m", 0.5)) }
        val server = Server()
        val watcher = RegionWatcher(reader, Translator(), server)
        watcher.pass(shade(10), listOf(quest))
        watcher.pass(shade(10), listOf(quest))
        assertTrue(server.sent.isEmpty())
    }

    @Test
    fun packTranslationIsUsedBeforeTheEngine() {
        val reader = Reader().apply { lines = listOf(line("New message", 0.1), line("Call me when you get this.", 0.25)) }
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server)
        watcher.packLookup = { text -> if (text == "New message") PackMatch("Yeni mesaj") else null }
        watcher.pass(shade(10), listOf(quest))
        watcher.pass(shade(10), listOf(quest))
        assertEquals(listOf("Yeni mesaj\nTR: Call me when you get this."), texts(server))
        assertEquals(listOf("Call me when you get this."), translator.calls)
    }

    /** Tuş çubuğu sözlükten çevrilir, simgeleri yerinde kalır; motora gitmez. */
    @Test
    fun buttonBarIsTranslatedFromTheCommandListWithItsIcons() {
        val reader = Reader().apply { lines = listOf(line("SET DESTINATION ✕")) }
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server)
        watcher.keepsPrompts = true
        watcher.packLookup = { text -> UiPrompts.translate(text)?.let { PackMatch(it) } }
        watcher.pass(shade(10), listOf(quest))
        watcher.pass(shade(10), listOf(quest))
        assertEquals(listOf("Hedef belirle ✕"), texts(server))
        assertTrue(translator.calls.isEmpty())
    }

    /** Oyunun renkli yazdığı sözcüğün çevirisi aynı renkte işaretlenir. */
    @Test
    fun colouredWordKeepsItsColourInTheTranslation() {
        val reader = Reader().apply {
            lines = listOf(line("Go to the plane"))
            colored = listOf(ColoredPhrase("plane", "#ff8dab"), ColoredPhrase("elsewhere", "#00aaff"))
        }
        val server = Server()
        val translations = mapOf("Go to the plane" to "Uçağa git", "plane" to "uçak")
        val watcher = RegionWatcher(reader, TextTranslator { translations[it] }, server)
        watcher.pass(shade(10), listOf(quest))
        watcher.pass(shade(10), listOf(quest))
        assertEquals(listOf("⁅#ff8dab|Uçağa⁆ git"), texts(server))
    }

    /** Altyazının ek alana düşen satırı ek alanda çevrilmez. */
    @Test
    fun skippedLinesAreNotTranslated() {
        val reader = Reader().apply { lines = listOf(line("Lucia: Get in, we're late."), line("Follow Brian", 0.5)) }
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server)
        watcher.skipLine = { ExtraAreas.skipLine(it, null) }
        watcher.pass(shade(10), listOf(quest))
        watcher.pass(shade(10), listOf(quest))
        assertEquals(listOf("Follow Brian"), translator.calls)
    }

    @Test
    fun removedAreaLosesItsBoxAndResetClearsAll() {
        val reader = Reader().apply { lines = listOf(line("Where are you?")) }
        val server = Server()
        val watcher = RegionWatcher(reader, Translator(), server)
        watcher.pass(shade(10), listOf(quest, phone))
        watcher.pass(shade(10), listOf(quest, phone))
        assertEquals(listOf("p", "q"), server.sent.map { it.id }.sorted())
        watcher.pass(shade(10), listOf(quest))
        assertEquals("p", server.sent.last().id)
        assertEquals("", server.sent.last().text)
        watcher.reset()
        assertEquals(1, server.clears)
        watcher.reset()
        assertEquals(1, server.clears)
    }

    @Test
    fun offerRunsAtMostOncePerInterval() {
        var clock = 0L
        val reader = Reader().apply { lines = listOf(line("Find the blacksmith")) }
        val server = Server()
        val watcher = RegionWatcher(reader, Translator(), server, clock = { clock })
        val frame = shade(10)
        watcher.offer(frame, listOf(quest))
        for (step in 1..9) {
            clock = step * RegionWatcher.DEFAULT_INTERVAL_MS / 10
            watcher.offer(frame, listOf(quest))
        }
        assertEquals(1, watcher.passes)
        clock = RegionWatcher.DEFAULT_INTERVAL_MS
        watcher.offer(frame, listOf(quest))
        assertEquals(2, watcher.passes)
        assertEquals(listOf("TR: Find the blacksmith"), texts(server))
    }

    @Test
    fun repeatedReadFailuresStillRespectThePassInterval() {
        var clock = 0L
        val reader = Reader().apply { failuresRemaining = 10 }
        val server = Server()
        val watcher = RegionWatcher(reader, Translator(), server, clock = { clock })
        for (step in 0..20) {
            clock = step * RegionWatcher.DEFAULT_INTERVAL_MS / 10
            watcher.offer(shade(10), listOf(quest))
        }
        assertEquals(3, watcher.passes)
        assertEquals(3, reader.count)
        assertTrue(server.sent.isEmpty())
    }

    /** Önceki tur bitmediyse yeni kare atlanır (tur ayrı iş parçacığında sürerken). */
    @Test
    fun busyWatcherSkipsFrames() {
        val queued = mutableListOf<Runnable>()
        var clock = 0L
        val reader = Reader().apply { lines = listOf(line("Find the blacksmith")) }
        val watcher = RegionWatcher(reader, Translator(), Server(), executor = Executor { queued.add(it) }, clock = { clock })
        watcher.offer(shade(10), listOf(quest))
        assertTrue(watcher.isBusy)
        clock = 10_000
        watcher.offer(shade(10), listOf(quest))
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertFalse(watcher.isBusy)
        assertEquals(1, watcher.passes)
    }

    /** Kararlılık beklenirken çeviri şimdiden istenir; doğrulanınca yeniden çevrilmez. */
    @Test
    fun prefetchedTranslationIsReused() {
        val queued = mutableListOf<Runnable>()
        val reader = Reader().apply { lines = listOf(line("Find the blacksmith")) }
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server, prefetchExecutor = Executor { queued.add(it) })
        watcher.pass(shade(10), listOf(quest))
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertEquals(listOf("Find the blacksmith"), translator.calls)
        watcher.pass(shade(10), listOf(quest))
        assertEquals(listOf("TR: Find the blacksmith"), texts(server))
        assertEquals(1, translator.calls.size)
    }

    /** Alanın kenarına değen satır (alanın dışında süren yazının ucu) çevrilmez. */
    @Test
    fun linesCutByTheAreaEdgeAreSkipped() {
        val cut = TextLine("ot it?", TextBox(0.0, 0.40, 0.12, 0.50))
        val card = TextLine("Good work covering with Brian", TextBox(0.15, 0.60, 0.90, 0.70))
        val candidate = RegionWatcher.makeCandidate(listOf(cut, card), ExtraAreaPreset.PHONE_MESSAGE.region)
        assertEquals(listOf("Good work covering with Brian"), candidate.paragraphs)
    }

    /** Başka renkte yazılmış ad ayrı satır okunsa da yerinde kalır ("Follow Brian", "Brian Follow" değil). */
    @Test
    fun sameRowPartsKeepTheirOrder() {
        val follow = TextLine("Follow", TextBox(0.40, 0.30, 0.48, 0.40))
        val brian = TextLine("Brian", TextBox(0.50, 0.29, 0.58, 0.39))
        assertEquals(listOf("Follow", "Brian"), RegionWatcher.inReadingOrder(listOf(brian, follow)).map { it.text })
    }

    /** Menü listesi (GTA 6 duraklatma menüsü) çevrilmez; ipucu cümlesi ve tek satırlık görev çevrilir. */
    @Test
    fun menuListsAreNotTranslated() {
        assertTrue(RegionWatcher.isMenuList(listOf(line("Game", 0.1), line("○ Store", 0.2), line("O Help", 0.3))))
        assertFalse(RegionWatcher.isMenuList(listOf(line("Follow the manager", 0.1))))
        // Haritadaki dikey tuş listesi menü değildir (sonu tuşla biter), çevrilir; yatay tuş çubuğu tek satırdır.
        assertFalse(RegionWatcher.isMenuList(listOf(line("RECENTER L3", 0.1), line("2D VIEW R3", 0.2), line("SET DESTINATION ✕", 0.3), line("EXIT ○", 0.4))))
        assertFalse(RegionWatcher.isMenuList(listOf(line("PHOTO STUDIO □ SELECT ✕ BACK ○", 0.1))))
        assertFalse(RegionWatcher.isMenuList(listOf(line("You can use the RydeMe", 0.1), line("app to order", 0.2), line("a ride.", 0.3))))
        val candidate = RegionWatcher.makeCandidate(listOf(line("Map", 0.1), line("Brief", 0.2), line("Progress", 0.3)), ExtraAreaPreset.HELP_BOX.region)
        assertTrue(candidate.paragraphs.isEmpty())
    }

    /** Satırlar çevrilecek parçalara toplanır; "m" okuma hatası sözcük listesiyle onarılır. */
    @Test
    fun linesAreJoinedIntoParagraphs() {
        val candidate = RegionWatcher.makeCandidate(
            listOf(line("New message", 0.1), line("You can use the RydeMe", 0.25), line("app to order frorn here.", 0.4)),
            ExtraAreaPreset.PHONE_MESSAGE.region, wordCheck = { it in setOf("from", "here", "app", "to", "order") })
        assertEquals(listOf("New message", "You can use the RydeMe app to order from here."), candidate.paragraphs)
    }

    /** Görev yeniden belirince yarım okuması gösterilmez, çevirisi çevirmene gitmeden yeniden kullanılır. */
    @Test
    fun reappearingTextWaitsForItsEndAndReusesTheTranslation() {
        val reader = Reader()
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server)
        fun show(text: String, value: Int) {
            reader.lines = if (text.isEmpty()) emptyList() else listOf(line(text))
            watcher.pass(shade(value), listOf(quest))
            watcher.pass(shade(value), listOf(quest))
        }
        show("Fly to a drop", 10)
        show("", 70)
        show("Fly to a", 130)
        // Yarım okuma bekletilir: TV'de hâlâ boş, çevirmene gidilmedi.
        assertEquals("q", server.sent.last().id)
        assertEquals("", server.sent.last().text)
        show("Fly to a drop", 190)
        assertEquals("TR: Fly to a drop", server.sent.last().text)
        assertEquals(listOf("Fly to a drop"), translator.calls)
    }

    /** Neredeyse aynı okuma ekrandakini değiştirmez; daha çok tuş simgesi bulan okuma değiştirir. */
    @Test
    fun nearlyIdenticalReadingKeepsTheShownTextUnlessItFindsMoreIcons() {
        val reader = Reader()
        val translator = Translator()
        val server = Server()
        val watcher = RegionWatcher(reader, translator, server)
        fun show(text: String, value: Int) {
            reader.lines = listOf(line(text))
            watcher.pass(shade(value), listOf(quest))
            watcher.pass(shade(value), listOf(quest))
        }
        show("ADD MARKER □ SET DESTINATION EXIT ○", 10)
        show("ADD MARKER □ SET DESTINATIPN EXIT ○", 70)
        assertEquals(1, server.sent.size)
        // (Sahte çevirmenin "TR: …" çevirisi aslına çok benzediği için kutu boş gönderilir; sayılan, gönderim sayısıdır.)
        show("ADD MARKER □ SET DESTINATION ✕ EXIT ○", 130)
        assertEquals(2, server.sent.size)
        assertEquals(2, translator.calls.size)
        show("ADD MARKER □ SET DESTINATION EXIT ○", 190)
        assertEquals(2, server.sent.size)
    }

    @Test
    fun readingThatSwapsAWordForAnIconLosesWords() {
        assertTrue(RegionWatcher.losesWords("find □", "find donnie"))
        assertFalse(RegionWatcher.losesWords("add marker □ set destination ✕ exit ○", "add marker □ set destination exit ○"))
    }

    @Test
    fun shorterReadingOfTheShownTextIsRecognized() {
        assertTrue(RegionWatcher.isPartOf("add marker □ set destinationlo", "add marker □ set destinatioy ✕ exit ○"))
        assertFalse(RegionWatcher.isPartOf("follow brian", "add marker □ set destination ✕ exit ○"))
        assertFalse(RegionWatcher.isPartOf("fly to a drop point", "fly to a"))
    }

    @Test
    fun noAreasMeansNoWork() {
        val reader = Reader()
        val watcher = RegionWatcher(reader, Translator(), Server())
        watcher.offer(shade(10), emptyList())
        assertFalse(watcher.isBusy)
        assertEquals(0, watcher.passes)
        assertEquals(0, reader.count)
    }

    /** Okuyucu simgeleri ve renkleri görüntüden ekler; tanıyıcının verdiği sözcük kutuları kullanılır. */
    @Test
    fun iconReaderAddsTheButtonFromThePicture() {
        // 400x100 görüntü: koyu zemin, ortada beyaz daire içinde koyu çarpı; solunda "Hold", sağında "to climb" yazıyor sayılır.
        val image = PixelImage(400, 100)
        image.pixels.fill(0xFF141418.toInt())
        val r = 16.0
        for (y in 0 until 100) for (x in 0 until 400) {
            val dx = x - 200.0
            val dy = y - 50.0
            if (dx * dx + dy * dy > r * r) continue
            val u = dx / r
            val v = dy / r
            val mark = kotlin.math.abs(u) < 0.5 && kotlin.math.abs(v) < 0.5 && (kotlin.math.abs(u - v) < 0.18 || kotlin.math.abs(u + v) < 0.18)
            if (mark) image.set(x, y, 30, 30, 30) else image.set(x, y, 235, 235, 235)
        }
        val top = 0.34
        val bottom = 0.66
        val words = listOf(OcrWord("Hold", TextBox(0.30, top, 0.45, bottom)), OcrWord("to", TextBox(0.55, top, 0.60, bottom)),
            OcrWord("climb", TextBox(0.62, top, 0.75, bottom)))
        val reader = IconReader { listOf(OcrLine("Hold to climb", TextBox(0.30, top, 0.75, bottom), words)) }
        assertEquals(listOf("Hold ✕ to climb"), reader.read(image).lines.map { it.text })
    }
}
