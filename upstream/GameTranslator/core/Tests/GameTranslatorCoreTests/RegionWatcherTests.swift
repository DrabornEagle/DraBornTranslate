// SPDX-License-Identifier: AGPL-3.0-only

@testable import GameTranslatorCore
import XCTest

/// Ek alanlar: ana altyazıdan ayrı, seyrek okunur; yazı kararlıysa ve değiştiyse çevrilir (Windows: RegionWatcherTests,
/// Android: RegionWatcherTest; CouchSubs / Nino uyarlaması). Turlar testte aynı iş parçacığında çalışır.
final class RegionWatcherTests: XCTestCase {
    private struct ReadFailure: Error {}

    private final class Reader: RegionReader {
        var lines: [TextLine] = []
        var colored: [ColoredPhrase] = []
        var count = 0
        var failuresRemaining = 0
        func read(_ image: PixelImage) throws -> RegionReading {
            count += 1
            if failuresRemaining > 0 {
                failuresRemaining -= 1
                throw ReadFailure()
            }
            return RegionReading(lines: lines, colored: colored)
        }
    }

    private final class Translator {
        var calls: [String] = []
        var failuresRemaining = 0
        func translate(_ text: String) -> String? {
            calls.append(text)
            if failuresRemaining > 0 {
                failuresRemaining -= 1
                return nil
            }
            return "TR: \(text)"
        }
    }

    private struct Sent { let id: String; let text: String; let box: TextBox?; let lineHeight: Double? }

    private final class Server: RegionSender {
        var sent: [Sent] = []
        var clears = 0
        func sendRegion(id: String, text: String, box: TextBox?, lineHeight: Double?) {
            sent.append(Sent(id: id, text: text, box: box, lineHeight: lineHeight))
        }
        func clearRegions() { clears += 1 }
    }

    /// Düz gri kare; `value` değişince bölge izi değişir.
    private func shade(_ value: Int) -> PixelImage {
        let image = PixelImage(width: 320, height: 180)
        image.fill(PixelImage.opaque(value, value, value))
        return image
    }

    private let quest = WatchedArea(id: "q", name: "Quest", region: ExtraAreaPreset.quest.region)
    private let phone = WatchedArea(id: "p", name: "Phone", region: ExtraAreaPreset.phone.region)

    private func line(_ text: String, _ top: Double = 0.2, _ height: Double = 0.1) -> TextLine {
        TextLine(text, box: TextBox(left: 0.1, top: top, right: 0.9, bottom: top + height))
    }

    private func texts(_ server: Server) -> [String] { server.sent.map(\.text) }

    private func watcher(_ reader: Reader, _ translator: Translator, _ server: Server, clock: (() -> Int64)? = nil,
                         executor: WorkRunner? = nil, prefetch: WorkRunner? = nil) -> RegionWatcher {
        RegionWatcher(reader: reader, translator: { translator.translate($0) }, server: server,
                      executor: executor ?? { $0() }, prefetchExecutor: prefetch, clock: clock ?? { 0 })
    }

    func testFailedReadRetriesOnAnUnchangedFrameThenStopsReading() {
        let reader = Reader()
        reader.lines = [line("Find the blacksmith")]
        reader.failuresRemaining = 1
        let translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server)
        watcher.pass(shade(10), areas: [quest])
        watcher.pass(shade(10), areas: [quest])
        // Başarılı yeni okuma yine kararlılık bekler.
        XCTAssertTrue(server.sent.isEmpty)
        for _ in 0..<4 { watcher.pass(shade(10), areas: [quest]) }
        XCTAssertEqual(reader.count, 2)
        XCTAssertEqual(translator.calls, ["Find the blacksmith"])
        XCTAssertEqual(texts(server), ["TR: Find the blacksmith"])
    }

    func testFailedReadKeepsShownTextAndDiscardsUnconfirmedReading() {
        let reader = Reader()
        reader.lines = [line("Talk to Arthur")]
        let translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server)
        watcher.pass(shade(10), areas: [quest])
        watcher.pass(shade(10), areas: [quest])
        reader.lines = [line("Meet Arthur at the barn")]
        watcher.pass(shade(60), areas: [quest])
        reader.failuresRemaining = 1
        watcher.pass(shade(100), areas: [quest])
        reader.lines = [line("Find the blacksmith")]
        watcher.pass(shade(100), areas: [quest])
        XCTAssertEqual(texts(server), ["TR: Talk to Arthur"])
        watcher.pass(shade(100), areas: [quest])
        XCTAssertEqual(translator.calls, ["Talk to Arthur", "Find the blacksmith"])
        XCTAssertEqual(texts(server), ["TR: Talk to Arthur", "TR: Find the blacksmith"])
    }

    func testFailedReadDoesNotBlockAnotherArea() {
        let reader = Reader()
        reader.lines = [line("Find the blacksmith")]
        reader.failuresRemaining = 1
        let server = Server()
        let watcher = watcher(reader, Translator(), server)
        watcher.pass(shade(10), areas: [quest, phone])
        watcher.pass(shade(10), areas: [quest, phone])
        XCTAssertEqual(server.sent.map(\.id), ["p"])
        watcher.pass(shade(10), areas: [quest, phone])
        XCTAssertEqual(server.sent.map(\.id), ["p", "q"])
        XCTAssertEqual(reader.count, 3)
    }

    func testStableTextIsTranslatedOnceAndPlacedInItsArea() throws {
        let reader = Reader(), translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server)
        reader.lines = [line("Find the blacksmith")]
        watcher.pass(shade(10), areas: [quest])
        XCTAssertTrue(server.sent.isEmpty)
        watcher.pass(shade(10), areas: [quest])
        XCTAssertEqual(server.sent.count, 1)
        let sent = server.sent[0]
        XCTAssertEqual(sent.text, "TR: Find the blacksmith")
        let box = try XCTUnwrap(sent.box)
        XCTAssertTrue(box.left > quest.region.left && box.right < quest.region.right)
        watcher.pass(shade(10), areas: [quest])
        watcher.pass(shade(10), areas: [quest])
        XCTAssertEqual(reader.count, 1)
        XCTAssertEqual(translator.calls.count, 1)
        XCTAssertEqual(server.sent.count, 1)
    }

    func testSameTextAtNewGeometryUpdatesRegionWithoutRetranslating() {
        let reader = Reader()
        reader.lines = [line("Find the blacksmith", 0.2, 0.1)]
        let translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server)
        watcher.pass(shade(10), areas: [quest])
        watcher.pass(shade(10), areas: [quest])
        let first = server.sent[0]
        reader.lines = [line("Find the blacksmith", 0.45, 0.2)]
        watcher.pass(shade(60), areas: [quest])
        XCTAssertEqual(texts(server), ["TR: Find the blacksmith", "TR: Find the blacksmith"])
        XCTAssertNotEqual(first.box?.top, server.sent[1].box?.top)
        XCTAssertNotEqual(first.lineHeight, server.sent[1].lineHeight)
        XCTAssertEqual(translator.calls, ["Find the blacksmith"])
    }

    func testFailedTranslationRetriesWithoutLosingPreviouslyShownText() {
        let reader = Reader(), translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server)
        reader.lines = [line("Talk to Arthur")]
        watcher.pass(shade(10), areas: [quest])
        watcher.pass(shade(10), areas: [quest])
        XCTAssertEqual(texts(server), ["TR: Talk to Arthur"])
        reader.lines = [line("Meet Arthur at the barn")]
        translator.failuresRemaining = 1
        watcher.pass(shade(90), areas: [quest])
        watcher.pass(shade(90), areas: [quest])
        XCTAssertEqual(texts(server), ["TR: Talk to Arthur"])
        watcher.pass(shade(90), areas: [quest])
        XCTAssertEqual(translator.calls, ["Talk to Arthur", "Meet Arthur at the barn", "Meet Arthur at the barn"])
        XCTAssertEqual(texts(server), ["TR: Talk to Arthur", "TR: Meet Arthur at the barn"])
    }

    func testMovingBackgroundWithTheSameTextIsNotTranslatedAgain() {
        let reader = Reader()
        reader.lines = [line("Talk to Arthur")]
        let translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server)
        for value in [10, 60, 110, 160, 210] { watcher.pass(shade(value), areas: [quest]) }
        XCTAssertEqual(reader.count, 5)
        XCTAssertEqual(translator.calls, ["Talk to Arthur"])
        XCTAssertEqual(texts(server), ["TR: Talk to Arthur"])
    }

    func testChangedTextIsTranslatedAndEmptyAreaRemovesTheBox() {
        let reader = Reader(), server = Server()
        let watcher = watcher(reader, Translator(), server)
        reader.lines = [line("Talk to Arthur")]
        watcher.pass(shade(10), areas: [quest])
        watcher.pass(shade(10), areas: [quest])
        reader.lines = [line("Meet Arthur at the barn")]
        watcher.pass(shade(90), areas: [quest])
        watcher.pass(shade(90), areas: [quest])
        reader.lines = []
        watcher.pass(shade(200), areas: [quest])
        watcher.pass(shade(200), areas: [quest])
        XCTAssertEqual(texts(server), ["TR: Talk to Arthur", "TR: Meet Arthur at the barn", ""])
    }

    func testPromptsAndNumbersShowNothing() {
        let reader = Reader()
        reader.lines = [line("R2 Swing"), line("23m", 0.5)]
        let server = Server()
        let watcher = watcher(reader, Translator(), server)
        watcher.pass(shade(10), areas: [quest])
        watcher.pass(shade(10), areas: [quest])
        XCTAssertTrue(server.sent.isEmpty)
    }

    func testPackTranslationIsUsedBeforeTheEngine() {
        let reader = Reader()
        reader.lines = [line("New message", 0.1), line("Call me when you get this.", 0.25)]
        let translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server)
        watcher.packLookup = { $0 == "New message" ? PackMatch("Yeni mesaj") : nil }
        watcher.pass(shade(10), areas: [quest])
        watcher.pass(shade(10), areas: [quest])
        XCTAssertEqual(texts(server), ["Yeni mesaj\nTR: Call me when you get this."])
        XCTAssertEqual(translator.calls, ["Call me when you get this."])
    }

    /// Tuş çubuğu sözlükten çevrilir, simgeleri yerinde kalır; motora gitmez.
    func testButtonBarIsTranslatedFromTheCommandListWithItsIcons() {
        let reader = Reader()
        reader.lines = [line("SET DESTINATION ✕")]
        let translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server)
        watcher.keepsPrompts = true
        watcher.packLookup = { UiPrompts.translate($0).map { PackMatch($0) } }
        watcher.pass(shade(10), areas: [quest])
        watcher.pass(shade(10), areas: [quest])
        XCTAssertEqual(texts(server), ["Hedef belirle ✕"])
        XCTAssertTrue(translator.calls.isEmpty)
    }

    /// Oyunun renkli yazdığı sözcüğün çevirisi aynı renkte işaretlenir.
    func testColouredWordKeepsItsColourInTheTranslation() {
        let reader = Reader()
        reader.lines = [line("Go to the plane")]
        reader.colored = [ColoredPhrase("plane", color: "#ff8dab"), ColoredPhrase("elsewhere", color: "#00aaff")]
        let server = Server()
        let translations = ["Go to the plane": "Uçağa git", "plane": "uçak"]
        let watcher = RegionWatcher(reader: reader, translator: { translations[$0] }, server: server)
        watcher.pass(shade(10), areas: [quest])
        watcher.pass(shade(10), areas: [quest])
        XCTAssertEqual(texts(server), ["⁅#ff8dab|Uçağa⁆ git"])
    }

    /// Altyazının ek alana düşen satırı ek alanda çevrilmez.
    func testSkippedLinesAreNotTranslated() {
        let reader = Reader()
        reader.lines = [line("Lucia: Get in, we're late."), line("Follow Brian", 0.5)]
        let translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server)
        watcher.skipLine = { ExtraAreas.skipLine($0, shownSource: nil) }
        watcher.pass(shade(10), areas: [quest])
        watcher.pass(shade(10), areas: [quest])
        XCTAssertEqual(translator.calls, ["Follow Brian"])
    }

    func testRemovedAreaLosesItsBoxAndResetClearsAll() {
        let reader = Reader()
        reader.lines = [line("Where are you?")]
        let server = Server()
        let watcher = watcher(reader, Translator(), server)
        watcher.pass(shade(10), areas: [quest, phone])
        watcher.pass(shade(10), areas: [quest, phone])
        XCTAssertEqual(server.sent.map(\.id).sorted(), ["p", "q"])
        watcher.pass(shade(10), areas: [quest])
        XCTAssertEqual(server.sent.last?.id, "p")
        XCTAssertEqual(server.sent.last?.text, "")
        watcher.reset()
        XCTAssertEqual(server.clears, 1)
        watcher.reset()
        XCTAssertEqual(server.clears, 1)
    }

    func testOfferRunsAtMostOncePerInterval() {
        var clock: Int64 = 0
        let reader = Reader()
        reader.lines = [line("Find the blacksmith")]
        let server = Server()
        let watcher = watcher(reader, Translator(), server, clock: { clock })
        let frame = shade(10)
        watcher.offer(frame, areas: [quest])
        for step in Int64(1)...9 {
            clock = step * RegionWatcher.defaultIntervalMs / 10
            watcher.offer(frame, areas: [quest])
        }
        XCTAssertEqual(watcher.passes, 1)
        clock = RegionWatcher.defaultIntervalMs
        watcher.offer(frame, areas: [quest])
        XCTAssertEqual(watcher.passes, 2)
        XCTAssertEqual(texts(server), ["TR: Find the blacksmith"])
    }

    func testRepeatedReadFailuresStillRespectThePassInterval() {
        var clock: Int64 = 0
        let reader = Reader()
        reader.failuresRemaining = 10
        let server = Server()
        let watcher = watcher(reader, Translator(), server, clock: { clock })
        for step in Int64(0)...20 {
            clock = step * RegionWatcher.defaultIntervalMs / 10
            watcher.offer(shade(10), areas: [quest])
        }
        XCTAssertEqual(watcher.passes, 3)
        XCTAssertEqual(reader.count, 3)
        XCTAssertTrue(server.sent.isEmpty)
    }

    /// Önceki tur bitmediyse yeni kare atlanır (tur ayrı iş parçacığında sürerken).
    func testBusyWatcherSkipsFrames() {
        var queued: [() -> Void] = []
        var clock: Int64 = 0
        let reader = Reader()
        reader.lines = [line("Find the blacksmith")]
        let watcher = watcher(reader, Translator(), Server(), clock: { clock }, executor: { queued.append($0) })
        watcher.offer(shade(10), areas: [quest])
        XCTAssertTrue(watcher.isBusy)
        clock = 10_000
        watcher.offer(shade(10), areas: [quest])
        XCTAssertEqual(queued.count, 1)
        queued.removeFirst()()
        XCTAssertFalse(watcher.isBusy)
        XCTAssertEqual(watcher.passes, 1)
    }

    /// Kararlılık beklenirken çeviri şimdiden istenir; doğrulanınca yeniden çevrilmez.
    func testPrefetchedTranslationIsReused() {
        var queued: [() -> Void] = []
        let reader = Reader()
        reader.lines = [line("Find the blacksmith")]
        let translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server, prefetch: { queued.append($0) })
        watcher.pass(shade(10), areas: [quest])
        XCTAssertEqual(queued.count, 1)
        queued.removeFirst()()
        XCTAssertEqual(translator.calls, ["Find the blacksmith"])
        watcher.pass(shade(10), areas: [quest])
        XCTAssertEqual(texts(server), ["TR: Find the blacksmith"])
        XCTAssertEqual(translator.calls.count, 1)
    }

    /// Alanın kenarına değen satır (alanın dışında süren yazının ucu) çevrilmez.
    func testLinesCutByTheAreaEdgeAreSkipped() {
        let cut = TextLine("ot it?", box: TextBox(left: 0.0, top: 0.40, right: 0.12, bottom: 0.50))
        let card = TextLine("Good work covering with Brian", box: TextBox(left: 0.15, top: 0.60, right: 0.90, bottom: 0.70))
        XCTAssertEqual(RegionWatcher.makeCandidate([cut, card], region: ExtraAreaPreset.phoneMessage.region).paragraphs,
                       ["Good work covering with Brian"])
    }

    /// Başka renkte yazılmış ad ayrı satır okunsa da yerinde kalır ("Follow Brian", "Brian Follow" değil).
    func testSameRowPartsKeepTheirOrder() {
        let follow = TextLine("Follow", box: TextBox(left: 0.40, top: 0.30, right: 0.48, bottom: 0.40))
        let brian = TextLine("Brian", box: TextBox(left: 0.50, top: 0.29, right: 0.58, bottom: 0.39))
        XCTAssertEqual(RegionWatcher.inReadingOrder([brian, follow]).map(\.text), ["Follow", "Brian"])
    }

    /// Menü listesi (GTA 6 duraklatma menüsü) çevrilmez; ipucu cümlesi ve tek satırlık görev çevrilir.
    func testMenuListsAreNotTranslated() {
        XCTAssertTrue(RegionWatcher.isMenuList([line("Game", 0.1), line("○ Store", 0.2), line("O Help", 0.3)]))
        XCTAssertFalse(RegionWatcher.isMenuList([line("Follow the manager", 0.1)]))
        // Haritadaki dikey tuş listesi menü değildir (sonu tuşla biter), çevrilir; yatay tuş çubuğu tek satırdır.
        XCTAssertFalse(RegionWatcher.isMenuList([line("RECENTER L3", 0.1), line("2D VIEW R3", 0.2), line("SET DESTINATION ✕", 0.3), line("EXIT ○", 0.4)]))
        XCTAssertFalse(RegionWatcher.isMenuList([line("PHOTO STUDIO □ SELECT ✕ BACK ○", 0.1)]))
        XCTAssertFalse(RegionWatcher.isMenuList([line("You can use the RydeMe", 0.1), line("app to order", 0.2), line("a ride.", 0.3)]))
        let candidate = RegionWatcher.makeCandidate([line("Map", 0.1), line("Brief", 0.2), line("Progress", 0.3)], region: ExtraAreaPreset.helpBox.region)
        XCTAssertTrue(candidate.paragraphs.isEmpty)
    }

    /// Satırlar çevrilecek parçalara toplanır; "m" okuma hatası sözcük listesiyle onarılır.
    func testLinesAreJoinedIntoParagraphs() {
        let candidate = RegionWatcher.makeCandidate(
            [line("New message", 0.1), line("You can use the RydeMe", 0.25), line("app to order frorn here.", 0.4)],
            region: ExtraAreaPreset.phoneMessage.region, wordCheck: { ["from", "here", "app", "to", "order"].contains($0) })
        XCTAssertEqual(candidate.paragraphs, ["New message", "You can use the RydeMe app to order from here."])
    }

    /// Görev yeniden belirince yarım okuması gösterilmez, çevirisi çevirmene gitmeden yeniden kullanılır.
    func testReappearingTextWaitsForItsEndAndReusesTheTranslation() {
        let reader = Reader(), translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server)
        func show(_ text: String, _ value: Int) {
            reader.lines = text.isEmpty ? [] : [line(text)]
            watcher.pass(shade(value), areas: [quest])
            watcher.pass(shade(value), areas: [quest])
        }
        show("Fly to a drop", 10)
        show("", 70)
        show("Fly to a", 130)
        // Yarım okuma bekletilir: TV'de hâlâ boş, çevirmene gidilmedi.
        XCTAssertEqual(server.sent.last?.id, "q")
        XCTAssertEqual(server.sent.last?.text, "")
        show("Fly to a drop", 190)
        XCTAssertEqual(server.sent.last?.text, "TR: Fly to a drop")
        XCTAssertEqual(translator.calls, ["Fly to a drop"])
    }

    /// Neredeyse aynı okuma ekrandakini değiştirmez; daha çok tuş simgesi bulan okuma değiştirir.
    func testNearlyIdenticalReadingKeepsTheShownTextUnlessItFindsMoreIcons() {
        let reader = Reader(), translator = Translator(), server = Server()
        let watcher = watcher(reader, translator, server)
        func show(_ text: String, _ value: Int) {
            reader.lines = [line(text)]
            watcher.pass(shade(value), areas: [quest])
            watcher.pass(shade(value), areas: [quest])
        }
        show("ADD MARKER □ SET DESTINATION EXIT ○", 10)
        show("ADD MARKER □ SET DESTINATIPN EXIT ○", 70)
        XCTAssertEqual(server.sent.count, 1)
        // (Sahte çevirmenin "TR: …" çevirisi aslına çok benzediği için kutu boş gönderilir; sayılan, gönderim sayısıdır.)
        show("ADD MARKER □ SET DESTINATION ✕ EXIT ○", 130)
        XCTAssertEqual(server.sent.count, 2)
        XCTAssertEqual(translator.calls.count, 2)
        show("ADD MARKER □ SET DESTINATION EXIT ○", 190)
        XCTAssertEqual(server.sent.count, 2)
    }

    func testReadingThatSwapsAWordForAnIconLosesWords() {
        XCTAssertTrue(RegionWatcher.losesWords("find □", "find donnie"))
        XCTAssertFalse(RegionWatcher.losesWords("add marker □ set destination ✕ exit ○", "add marker □ set destination exit ○"))
    }

    func testShorterReadingOfTheShownTextIsRecognized() {
        XCTAssertTrue(RegionWatcher.isPartOf("add marker □ set destinationlo", "add marker □ set destinatioy ✕ exit ○"))
        XCTAssertFalse(RegionWatcher.isPartOf("follow brian", "add marker □ set destination ✕ exit ○"))
        XCTAssertFalse(RegionWatcher.isPartOf("fly to a drop point", "fly to a"))
    }

    func testNoAreasMeansNoWork() {
        let reader = Reader()
        let watcher = watcher(reader, Translator(), Server())
        watcher.offer(shade(10), areas: [])
        XCTAssertFalse(watcher.isBusy)
        XCTAssertEqual(watcher.passes, 0)
        XCTAssertEqual(reader.count, 0)
    }

    /// Okuyucu simgeleri ve renkleri görüntüden ekler; tanıyıcının verdiği sözcük kutuları kullanılır.
    func testIconReaderAddsTheButtonFromThePicture() throws {
        // 400x100 görüntü: koyu zemin, ortada beyaz daire içinde koyu çarpı; solunda "Hold", sağında "to climb" yazıyor sayılır.
        let image = PixelImage(width: 400, height: 100)
        image.fill(0xFF14_1418)
        let r = 16.0
        for y in 0..<100 {
            for x in 0..<400 {
                let dx = Double(x) - 200, dy = Double(y) - 50
                if dx * dx + dy * dy > r * r { continue }
                let u = dx / r, v = dy / r
                let mark = abs(u) < 0.5 && abs(v) < 0.5 && (abs(u - v) < 0.18 || abs(u + v) < 0.18)
                if mark { image.set(x, y, 30, 30, 30) } else { image.set(x, y, 235, 235, 235) }
            }
        }
        let top = 0.34, bottom = 0.66
        let words = [OcrWord("Hold", box: TextBox(left: 0.30, top: top, right: 0.45, bottom: bottom)),
                     OcrWord("to", box: TextBox(left: 0.55, top: top, right: 0.60, bottom: bottom)),
                     OcrWord("climb", box: TextBox(left: 0.62, top: top, right: 0.75, bottom: bottom))]
        let reader = IconReader { _ in [OcrLine("Hold to climb", box: TextBox(left: 0.30, top: top, right: 0.75, bottom: bottom), words: words)] }
        XCTAssertEqual(try reader.read(image).lines.map(\.text), ["Hold ✕ to climb"])
    }
}
