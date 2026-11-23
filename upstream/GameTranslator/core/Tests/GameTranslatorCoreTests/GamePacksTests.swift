// SPDX-License-Identifier: AGPL-3.0-only

import XCTest
@testable import GameTranslatorCore

/// Windows ve Android'le ortak deneme dosyaları (Fixtures) ve Windows'a gömülü gerçek paketler.
enum PackFixtures {
    static let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures")

    static func data(_ name: String) throws -> Data { try Data(contentsOf: directory.appendingPathComponent(name)) }

    static func json(_ name: String) throws -> Any { try JSONSerialization.jsonObject(with: data(name)) }

    /// windows/src/GameTranslator.Core/Resources/packs (depo kökünden); yoksa nil, gerçek paket denemeleri atlanır.
    static let packs: URL? = {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("windows/src/GameTranslator.Core/Resources/packs")
        return FileManager.default.fileExists(atPath: url.appendingPathComponent("mafia-the-old-country.json.gz").path) ? url : nil
    }()

    /// Yalnız tek gerçek paketle kitaplık.
    static func library(_ file: String) throws -> GamePackLibrary {
        let directory = try XCTUnwrap(packs)
        return GamePackLibrary(bundled: { [(file, { try Data(contentsOf: directory.appendingPathComponent(file)) })] }, userDirectory: nil)
    }

    static func temporaryDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("packs-" + UUID().uuidString)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

final class PackKeyTests: XCTestCase {
    /// Windows, Android ve paket hazırlayan Python betiği (canon.py) ile aynı sonuç.
    func testGoldenVectors() throws {
        let vectors = try XCTUnwrap(try PackFixtures.json("golden-vectors.json") as? [[String: String]])
        XCTAssertEqual(vectors.count, 15)
        for vector in vectors {
            let input = vector["input"]!
            XCTAssertEqual(PackKey.canon(input), vector["canon"], input)
            XCTAssertEqual(PackKey.key(input) ?? "", vector["key"], input)
            XCTAssertEqual(PackKey.key(input).flatMap(PackKey.number), PackKey.keyNumber(input), input)
        }
    }

    func testTagsLongerThanFortyCharactersStay() {
        XCTAssertEqual(PackKey.stripTags("a <b> c"), "a   c")
        let tooLong = "<" + String(repeating: "x", count: 41) + ">"
        XCTAssertEqual(PackKey.stripTags(tooLong), tooLong)
        XCTAssertEqual(PackKey.stripTags("<" + String(repeating: "x", count: 40) + ">"), " ")
        XCTAssertEqual(PackKey.stripTags("a < b <i> c"), "a < b   c")
        XCTAssertEqual(PackKey.stripTags("<<b>>"), "< >")
    }

    func testNumberRoundTripsUnsignedKeys() {
        XCTAssertEqual(PackKey.number("ffffffffffffffff"), UInt64.max)
        XCTAssertNil(PackKey.number("xyz"))
        XCTAssertNil(PackKey.number("0123"))
    }

    func testDigestsMatchKnownValues() {
        XCTAssertEqual(Digest.hex(Digest.sha256(Array("abc".utf8))), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(Digest.hex(Digest.sha256([UInt8](repeating: 0x61, count: 1000))),
                       "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3")
        XCTAssertEqual(Digest.hex(Digest.sha1(Array("".utf8))), "da39a3ee5e6b4b0d3255bfef95601890afd80709")
    }
}

final class GamePackTests: XCTestCase {
    private func load(_ name: String) throws -> GamePack {
        try GamePack.decode(PackFixtures.data(name), name: name.replacingOccurrences(of: ".json.gz", with: ""))
    }

    func testWrappedAndBareFormatsLoad() throws {
        let wrapped = try load("pack-wrapped.json.gz")
        XCTAssertEqual(wrapped.id, "test-game")
        XCTAssertEqual(wrapped.title, "Test Game")
        XCTAssertEqual(wrapped.count, 5)
        let bare = try load("pack-bare.json.gz")
        XCTAssertEqual(bare.id, "pack-bare")
        XCTAssertEqual(bare.title, "pack-bare")
        XCTAssertEqual(bare.count, 2)
    }

    func testPlainJsonIsAccepted() throws {
        let key = try XCTUnwrap(PackKey.key("Get back!"))
        let pack = try GamePack.decode(Data("{\"\(key)\": \"Geri çekil!\"}".utf8), name: "plain")
        XCTAssertEqual(pack[PackKey.number(key)!], "Geri çekil!")
    }

    func testBrokenFilesAreRejected() {
        XCTAssertThrowsError(try GamePack.decode(Data("not json".utf8), name: "x"))
        XCTAssertThrowsError(try GamePack.decode(Data([0x1f, 0x8b, 8, 0] + [UInt8](repeating: 7, count: 30)), name: "x"))
        XCTAssertThrowsError(try GamePack.decode(Data("{\"format\":3,\"entries\":{\"0123456789abcdef\":\"x\"}}".utf8), name: "x"))
        XCTAssertThrowsError(try GamePack.decode(Data("{\"bad\":\"x\"}".utf8), name: "x"))
    }

    /// Biçim 2: tam satırlar önce, "segments" yalnız tam satır bulunmazsa.
    func testFormatTwoSegmentsAreFoundAfterWholeLines() throws {
        let down = PackKey.key("Get Down!")!, back = PackKey.key("Get back!")!
        let json = "{\"format\":2,\"id\":\"g\",\"entries\":{\"\(down)\":\"Yere yat!\"},\"segments\":{\"\(back)\":\"Geri çekil!\",\"\(down)\":\"Eğil!\"},\"fuzzy\":{\"x\":[1,2]}}"
        XCTAssertEqual(try GamePack.decode(Data(json.utf8), name: "g").count, 1)
        let library = GamePackLibrary(bundled: { [("g.json", { Data(json.utf8) })] }, userDirectory: nil)
        XCTAssertEqual(library.find("Get Down!"), "Yere yat!")
        XCTAssertEqual(library.find("Get back!"), "Geri çekil!")
    }

    func testTagsAreRemovedFromTurkish() throws {
        let pack = try load("pack-wrapped.json.gz")
        XCTAssertEqual(pack[PackKey.keyNumber("<b><h>SCANNATURI SPECIALE</></> KNIFE")!], "SCANNATURI SPECIALE BIÇAĞI")
    }

    func testEscapesInJsonStrings() throws {
        let key = PackKey.key("Get back!")!
        let pack = try GamePack.decode(Data("{\"\(key)\": \"Geri \\u00e7ekil \\\"\\ud83d\\ude00\\\"\"}".utf8), name: "plain")
        XCTAssertEqual(pack[PackKey.number(key)!], "Geri çekil \"😀\"")
    }

    func testLibraryMergesBundledAndUserPacks() throws {
        let user = PackFixtures.temporaryDirectory()
        let bare = try PackFixtures.data("pack-bare.json.gz"), wrapped = try PackFixtures.data("pack-wrapped.json.gz")
        let library = GamePackLibrary(bundled: {
            [("a.json.gz", { bare }), ("b.json.gz", { wrapped }), ("c.json.gz", { Data("bozuk".utf8) })]
        }, userDirectory: user)
        XCTAssertEqual(library.infos.map(\.title), ["a", "Test Game"])
        XCTAssertEqual(library.infos.map(\.entries), [2, 5])
        // Aynı anahtarda sonraki paket kazanır.
        XCTAssertEqual(library.find("Get Down!"), "Yere yat!")
        // Windows'la aynı: satır birden çok paketteyse kapsam ilk sahibine geçer; sonraki aramada o paketin çevirisi kullanılır.
        XCTAssertEqual(library.findWithPack("Get Down!")?.packId, "a")
        XCTAssertEqual(library.find("Get back!"), "Geri çekil!")

        // Kullanıcının klasöründeki aynı kimlikli paket uygulamanınkinin yerine geçer.
        let key = PackKey.key("Get Down!")!
        try Data("{\"format\":1,\"id\":\"test-game\",\"title\":\"Test Game 2\",\"entries\":{\"\(key)\":\"Yat yere!\"}}".utf8)
            .write(to: user.appendingPathComponent("test.json"))
        library.reload()
        XCTAssertEqual(library.infos.map(\.title), ["a", "Test Game 2"])
        XCTAssertEqual(library.infos.map(\.builtIn), [true, false])
        XCTAssertEqual(library.find("Get Down!"), "Yat yere!")
        XCTAssertNil(library.find("Who is that?"))
        // Kapatılan paket aranmaz; önceki paketin çevirisi görünür.
        library.setDisabled(["test-game"])
        XCTAssertEqual(library.findWithPack("Get Down!")?.packId, "a")
    }

    func testDuplicateKeysKeepLastAndAllLinesAreFound() throws {
        let down = PackKey.key("Get Down!")!
        let lines = (0..<5000).map { "Line number \($0)" }
        let body = lines.enumerated().map { "\"\(PackKey.key($0.element)!)\":\"Satır \($0.offset)\"" }.joined(separator: ",")
        let json = "{\"format\":1,\"id\":\"g\",\"entries\":{\"\(down)\":\"Eğil!\",\(body),\"\(down)\":\"Yere yat!\",\"\(PackKey.key("Empty")!)\":\" \"}}"
        let pack = try GamePack.decode(Data(json.utf8), name: "g")
        XCTAssertEqual(pack.count, 5001)
        XCTAssertEqual(pack[PackKey.number(down)!], "Yere yat!")
        XCTAssertNil(pack[PackKey.keyNumber("Empty")!])
        for index in [0, 1, 2499, 4999] { XCTAssertEqual(pack[PackKey.keyNumber(lines[index])!], "Satır \(index)") }
        XCTAssertNil(pack.segment(PackKey.number(down)!))
    }

    /// Spider-Man oynarken "Janitor: Okay," Watch Dogs paketindeki ":-( Tamam..." mesajına çıkıyordu: oyun belliyken onun
    /// paketinde olmayan kısa satır başka oyunun paketinden alınmaz (Windows: AShortLineIsNotTakenFromAnotherGamesPack).
    func testAShortLineIsNotTakenFromAnotherGamesPack() {
        func pack(_ id: String, _ lines: [(String, String)]) -> GamePackLibrary.BundledFile {
            let entries = lines.map { "\"\(PackKey.key($0.0)!)\":\"\($0.1)\"" }.joined(separator: ",")
            return ("\(id).json", { Data("{\"format\":1,\"id\":\"\(id)\",\"title\":\"\(id)\",\"entries\":{\(entries)}}".utf8) })
        }
        let library = GamePackLibrary(bundled: {
            [pack("spider-man", [("Gotta move him away from that door...", "Onu o kapıdan uzaklaştırmalıyım..."), ("Let's go!", "Hadi gidelim!")]),
             pack("watch-dogs", [(":-( Okay...", ":-( Tamam..."), ("Let's go!", "Gidelim!"),
                                 ("I'm taking a bit of a break from the forums, everyone.", "Forumlara biraz ara veriyorum.")])]
        }, userDirectory: nil)
        XCTAssertEqual(library.find("Okay,"), ":-( Tamam...")
        XCTAssertEqual(library.find("Gotta move him away from that door..."), "Onu o kapıdan uzaklaştırmalıyım...")
        XCTAssertNil(library.find("Okay,"))
        XCTAssertEqual(library.find("Let's go!"), "Hadi gidelim!")
        XCTAssertEqual(library.find("I'm taking a bit of a break from the forums, everyone."), "Forumlara biraz ara veriyorum.")
        XCTAssertEqual(library.find("Okay,"), ":-( Tamam...")
    }

    func testMissingDirectoriesGiveAnEmptyLibrary() {
        let library = GamePackLibrary(bundled: nil, userDirectory: FileManager.default.temporaryDirectory.appendingPathComponent("yok-" + UUID().uuidString))
        XCTAssertTrue(library.infos.isEmpty)
        XCTAssertNil(library.find("Get Down!"))
    }

    func testGzipRoundTripOfFixtures() throws {
        let data = try Gzip.decompress(PackFixtures.data("pack-wrapped.json.gz"))
        XCTAssertTrue(String(decoding: data, as: UTF8.self).contains("\"entries\""))
        XCTAssertThrowsError(try Gzip.decompress(Data([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0])))
    }

    /// Windows'a gömülü gerçek paketler (Mafia): satırlar, parçalar ve imzalar.
    func testEmbeddedMafiaPackLoads() throws {
        try XCTSkipIf(PackFixtures.packs == nil, "Windows paketleri yok")
        let library = try PackFixtures.library("mafia-the-old-country.json.gz")
        let mafia = try XCTUnwrap(library.pack(id: "mafia-the-old-country"))
        XCTAssertEqual(mafia.title, "Mafia: The Old Country")
        XCTAssertGreaterThan(mafia.count, 18000)
        XCTAssertGreaterThan(mafia.segmentCount, 10000)
        XCTAssertGreaterThan(mafia.fuzzyCount, 20000)
        XCTAssertEqual(library.find("You fucked up now, boy!"), "Şimdi ayvayı yedin, evlat!")
        XCTAssertEqual(library.find("It’s his mother, she passed."), "Annesi vefat etti.")
        XCTAssertEqual(PackLookup.match(library, "MARIA: Get Down!")?.text, "MARIA: Yere yat!")
        XCTAssertEqual(PackLookup.match(library, "Get Down!\nMinchia! You shouldn't be allowed to ride!")?.text,
                       "Yere yat!\nMinchia! Sana at sürmeyi yasaklamak lazım!")
    }
}

final class PackLookupTests: XCTestCase {
    private let pack: [String: String] = [
        PackKey.key("Get Down!")!: "Yere yat!",
        PackKey.key("Who is that?")!: "O da kim?",
        PackKey.key("Don Torrisi wants you.")!: "Don Torrisi seni istiyor.",
        PackKey.key("Get back!")!: "Geri çekil!",
    ]

    private func find(_ line: String) -> String? { PackKey.key(line).flatMap { pack[$0] } }
    private func match(_ text: String) -> String? { PackLookup.match(text, find: find)?.text }

    func testWholeLineIgnoresCaseSpacingAndPunctuation() {
        XCTAssertEqual(match("GET DOWN!!"), "Yere yat!")
        XCTAssertEqual(match("  Who   is\nthat?  "), "O da kim?")
        XCTAssertNil(match("Get out!"))
        XCTAssertNil(match(""))
    }

    func testSpeakerLabelIsKept() {
        XCTAssertEqual(match("ENZO: Don Torrisi wants you."), "ENZO: Don Torrisi seni istiyor.")
        XCTAssertEqual(match("Cesare: Get down!"), "Cesare: Yere yat!")
        XCTAssertNil(match("ENZO: Get out!"))
    }

    func testEachLineAndSentenceIsLookedUpWhenTheWholeIsMissing() {
        XCTAssertEqual(match("ENZO: Get down!\nMARIA: Who is that?"), "ENZO: Yere yat!\nMARIA: O da kim?")
        XCTAssertEqual(match("- Get down!\n- Who is that?"), "- Yere yat!\n- O da kim?")
        XCTAssertNil(match("Get down!\nWho are you?"))
        XCTAssertEqual(match("ENZO: Get down! Who is that?"), "ENZO: Yere yat! O da kim?")
        XCTAssertEqual(match("Get down! Get back!"), "Yere yat! Geri çekil!")
        XCTAssertNil(match("Get down! Who are you?"))
    }

    func testWholeLineWinsOverParts() {
        let custom = [PackKey.key("Get down! Get back!")!: "Eğil ve geri çekil!", PackKey.key("Get Down!")!: "Yere yat!"]
        XCTAssertEqual(PackLookup.match("Get down! Get back!", find: { PackKey.key($0).flatMap { custom[$0] } })?.text, "Eğil ve geri çekil!")
    }

    /// OCR'ın bozduğu etiket ("-GAETANO:", "M]:", "li LUCA:") da atılıp aranır; ekranda olduğu gibi kalır.
    func testGarbledSpeakerLabelIsStripped() {
        XCTAssertEqual(match("-GAETANO: Don Torrisi wants you."), "- GAETANO: Don Torrisi seni istiyor.")
        XCTAssertEqual(match("- GAETANO: Don Torrisi wants you."), "- GAETANO: Don Torrisi seni istiyor.")
        XCTAssertEqual(match("M]: Get down!"), "M]: Yere yat!")
        XCTAssertEqual(match("li LUCA: Who is that?"), "li LUCA: O da kim?")
        XCTAssertEqual(match("SEBASTIAN Who is that?"), "SEBASTIAN O da kim?")
        XCTAssertNil(match("I told you: get out!"))
    }

    func testLooseLabelVectors() throws {
        let root = try XCTUnwrap(try PackFixtures.json("fuzzy-vectors.json") as? [String: Any])
        let vectors = try XCTUnwrap(root["looseLabels"] as? [[String: Any]])
        XCTAssertGreaterThan(vectors.count, 5)
        for vector in vectors {
            let input = vector["input"] as! String
            let found = PackLookup.looseLabel(input)
            XCTAssertEqual(found?.label, vector["label"] as? String, input)
            XCTAssertEqual(found?.body, vector["body"] as? String, input)
        }
    }

    func testNearestIsTriedOnlyAfterExactParts() {
        var asked: [String] = []
        let nearest = { (line: String) -> String? in
            asked.append(line)
            return line == "Who iz that?" ? "O da kim?" : nil
        }
        XCTAssertEqual(PackLookup.match("ENZO: Get down! Who iz that?", nearest: nearest, find: find)?.text, "ENZO: Yere yat! O da kim?")
        XCTAssertEqual(PackLookup.match("GET DOWN!!", nearest: { _ in "x" }, find: find)?.text, "Yere yat!")
        XCTAssertFalse(asked.contains("Get down!"))
    }

    func testOverlayLineNextToTheSubtitleIsSkipped() {
        XCTAssertEqual(match("San Andreas\nWho is that?"), "O da kim?")
        XCTAssertEqual(match("Get down!\nFirst Person • zoom"), "Yere yat!")
        XCTAssertEqual(match("Get down!\nMirror Park\nWho is that?"), "Yere yat!\nO da kim?")
        XCTAssertNil(match("San Andreas\nMirror Park"))
        XCTAssertNil(match("Get down!\nwho are you"))
        XCTAssertTrue(PackLookup.isOverlay("LOSNN"))
        XCTAssertFalse(PackLookup.isOverlay("Listen to me,"))
        XCTAssertFalse(PackLookup.isOverlay("Hey, Franklin!"))
    }

    func testSpeakerPrefixIsKeptOutsideTheLookup() {
        XCTAssertTrue(SpeakerPrefix.split("- Where are you going?") == ("- ", "Where are you going?"))
        XCTAssertTrue(SpeakerPrefix.split("  — Run!  ") == ("— ", "Run!"))
        XCTAssertTrue(SpeakerPrefix.split(">>Go.") == (">>", "Go."))
        XCTAssertTrue(SpeakerPrefix.split("No marker here - just a dash inside.") == ("", "No marker here - just a dash inside."))
        XCTAssertTrue(SpeakerPrefix.split("- ") == ("-", ""))
        let found = PackTranslation.match(">> Get down!") { line in self.find(line).map { PackMatch($0, packId: "g") } }
        XCTAssertEqual(found, PackMatch(">> Yere yat!", packId: "g"))
        XCTAssertNil(PackTranslation.match(">> ") { _ in PackMatch("x") })
        XCTAssertTrue(PackTranslation.applies(sourceLanguage: "en", targetLanguage: "tr"))
        XCTAssertFalse(PackTranslation.applies(sourceLanguage: "en", targetLanguage: "de"))
    }
}

final class PackFuzzyTests: XCTestCase {
    /// İmza Windows, Android ve paket betiğiyle bit bit aynı.
    func testSignatureVectors() throws {
        let root = try XCTUnwrap(try PackFixtures.json("fuzzy-vectors.json") as? [String: Any])
        let signatures = try XCTUnwrap(root["signatures"] as? [[String: Any]])
        XCTAssertGreaterThan(signatures.count, 10)
        for vector in signatures {
            let input = vector["input"] as! String
            XCTAssertEqual(PackFuzzy.foldedString(input), vector["folded"] as? String, input)
            XCTAssertEqual(PackFuzzy.damaged(input), vector["damaged"] as? Bool, input)
            let signature = PackFuzzy.signature(input)
            guard let length = vector["length"] as? Int else {
                XCTAssertNil(signature, input)
                continue
            }
            XCTAssertEqual(signature?.length, length, input)
            XCTAssertEqual(signature.map { PackFuzzy.bits($0.length) }, vector["bits"] as? Int, input)
            XCTAssertEqual(signature.map { Digest.hex($0.bloom) }, vector["bloom"] as? String, input)
        }
        for pair in root["similarities"] as? [[String: Any]] ?? [] {
            let bits = pair["bits"] as! Int
            let query = PackFuzzy.bloom(PackFuzzy.trigramHashes(PackFuzzy.folded(pair["query"] as! String)), bits: bits)
            let entry = PackFuzzy.bloom(PackFuzzy.trigramHashes(PackFuzzy.folded(pair["entry"] as! String)), bits: bits)
            XCTAssertEqual(PackFuzzy.similarity(query, entry, offset: 0, bits: bits), (pair["similarity"] as! NSNumber).doubleValue,
                           accuracy: 1e-9, pair["query"] as! String)
        }
    }

    func testVariantsAreSingleCorrections() {
        let variants = PackFuzzy.variants("Somebody help rne!")
        XCTAssertTrue(variants.contains("somebody help me"))
        XCTAssertTrue(PackFuzzy.variants("You cant put a price").contains("you can t put a price"))
        XCTAssertTrue(PackFuzzy.variants("Yeah, well, well see.").contains("yeah well we ll see"))
        XCTAssertFalse(variants.contains("somebody help rne"))
        XCTAssertTrue(variants.allSatisfy { !$0.hasPrefix(" ") && !$0.hasSuffix(" ") && !$0.contains("  ") })
        XCTAssertTrue(PackFuzzy.variants("Hey").isEmpty)
        XCTAssertTrue(PackFuzzy.variants(String(repeating: "x", count: 49)).isEmpty)
    }

    func testClauses() {
        XCTAssertEqual(PackFuzzy.clauses("Mirror Park Errr. Your broke. Call me back, okay? Bye, Dad."),
                       ["Mirror Park Errr.", "Your broke.", "Call me back,", "okay? Bye, Dad."])
        XCTAssertEqual(PackFuzzy.clauses("to escape... if we're able. You said seriously. nis seriously."),
                       ["to escape...", "if we're able.", "You said seriously. nis seriously."])
    }

    /// Kullanıcılardan gelen gerçek ıskalamalar (Mafia, OCR hatalı): Windows, Android ve paket betiğiyle aynı Türkçe.
    func testRealOcrMissesMatchLikeThePackScript() throws {
        try XCTSkipIf(PackFixtures.packs == nil, "Windows paketleri yok")
        let fixtures = try XCTUnwrap(try PackFixtures.json("pack-matches.json") as? [String: Any])
        let cases = try XCTUnwrap(fixtures["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 50)
        let library = try PackFixtures.library("mafia-the-old-country.json.gz")
        XCTAssertNil(library.searchScope)
        XCTAssertNotNil(PackLookup.match(library, fixtures["warmUp"] as! String))
        XCTAssertEqual(library.searchScope, "mafia-the-old-country")
        for item in cases {
            let screen = item["screen"] as! String
            XCTAssertEqual(PackLookup.match(library, screen)?.text, item["turkish"] as? String, screen)
        }
    }

    /// GTA V oyuncularının gerçek ıskalamaları: kısa satırda tek harf hatası, yutulmuş kesme işareti, köşe yazısı, iki
    /// satırlık altyazının yalnız bir satırı ve başına çöp eklenmiş okuma; iki altyazı yan yana okununca yarım çeviri yok.
    func testGtaOcrReadsAreFound() throws {
        try XCTSkipIf(PackFixtures.packs == nil, "Windows paketleri yok")
        let library = try PackFixtures.library("gta-v.json.gz")
        XCTAssertNotNil(PackLookup.match(library, "Are you listening to me?"))
        func same(_ screen: String, _ line: String) {
            let expected = PackLookup.match(library, line)?.text
            XCTAssertNotNil(expected, line)
            XCTAssertEqual(PackLookup.match(library, screen)?.text, expected, screen)
        }
        same("Somebody help rne!", "Somebody help me!")
        same("Yeah, well, well see.", "Yeah, well, we'll see.")
        same("You cant put a price on freedom.", "You can't put a price on freedom.")
        same("LOSNN\nI told him to leave it, Trevor.", "I told him to leave it, Trevor.")
        same("I can't do crap with this deathship right", "I can't do crap with this deathship right on top of me! Bring it down, T!")
        same("San Andreas LOSNN but it does mean he's got money to pay people who help him.",
             "but it does mean he's got money to pay people who help him.")
        XCTAssertNil(PackLookup.match(library, "The quick brown fox jumps over the lazy dog near the river bank."))
        XCTAssertNil(PackLookup.match(library, "Sauvage gentleman of the western plains"))
        for (first, second) in [("Hey, look. Good shit on those contracts you been workin' on.", "But listen, I got some other work."),
                                ("I'm calling it. Don't burn your eyes out.", "Bye, Dad.")] {
            let alone = PackLookup.match(library, first)?.text
            XCTAssertNotNil(alone, first)
            let found = PackLookup.match(library, first + " " + second)?.text
            XCTAssertNotEqual(found, alone, first)
            if let found, let alone, let other = PackLookup.match(library, second)?.text {
                XCTAssertTrue(found.contains(alone) && found.contains(other), found)
            }
        }
        XCTAssertNotNil(PackLookup.match(library, "Your vehicle? Sorry, we can't get that sorted at this time. San Andreas"))
        XCTAssertNotNil(PackLookup.match(library, "Vinewood Hills Just not my junk, okay? I'm already scarred down there."))
    }

    /// Hiç tam eşleşme olmadan bulanık arama yapılmaz (hangi oyunun oynandığı belli değil).
    func testNoFuzzySearchBeforeTheGameIsKnown() throws {
        try XCTSkipIf(PackFixtures.packs == nil, "Windows paketleri yok")
        let library = try PackFixtures.library("mafia-the-old-country.json.gz")
        let garbled = "GAETANO: All this will bébehind u soon: Uötil'then don't let any of these båétards getpyou, alrightiz:t"
        XCTAssertNil(PackLookup.match(library, garbled))
        XCTAssertNil(library.scopeTitle)
        XCTAssertNotNil(PackLookup.match(library, "You fucked up now, boy!"))
        XCTAssertEqual(library.scopeTitle, "Mafia: The Old Country")
        let clean = try XCTUnwrap(PackLookup.match(library, "All this will be behind us soon. Until then don’t let any of these bastards get to you, alright?")?.text)
        XCTAssertEqual(PackLookup.match(library, garbled)?.text, "GAETANO: " + clean)
        XCTAssertEqual(PackLookup.match(library, garbled)?.packId, "mafia-the-old-country")
    }
}

final class PackGateTests: XCTestCase {
    private let hit = PackMatch("çeviri")

    func testShortGenericLinesFromAnotherGameAreNotUsed() {
        var time: Int64 = 0
        let gate = PackGate(clock: { time })
        for line in ["Miles?", "Spider-Man: Yeah,", "Nope...", "Class?!", "Leave me alone!"] {
            XCTAssertNil(gate.filter(line, hit))
            time += 20_000
        }
        XCTAssertFalse(gate.isOpen)
        XCTAssertNil(gate.filter("No match at all in any pack here.", nil))
    }

    func testOpensAfterSeveralLongLinesAndThenUsesShortOnesToo() {
        var time: Int64 = 0
        let gate = PackGate(clock: { time })
        XCTAssertNil(gate.filter("We need to get out of here, now.", hit))
        // Aynı satır yeniden sorulunca sayılmaz.
        XCTAssertNil(gate.filter("We need to get out of here, now.", hit))
        time += 5000
        XCTAssertNil(gate.filter("I told you not to come back.", hit))
        time += 5000
        XCTAssertEqual(gate.filter("You should have listened to me, boy.", hit), hit)
        XCTAssertTrue(gate.isOpen)
        XCTAssertEqual(gate.filter("Yeah.", hit), hit)
    }

    func testCoincidencesSpreadOverALongTimeDoNotOpenIt() {
        var time: Int64 = 0
        let gate = PackGate(clock: { time })
        for line in ["What are you doing out here?", "I have no idea what happened.", "We should get back to work."] {
            XCTAssertNil(gate.filter(line, hit))
            time += PackGate.windowMs
        }
        XCTAssertFalse(gate.isOpen)
    }

    func testClosesWhenLongMatchesStop() {
        var time: Int64 = 0
        let gate = PackGate(clock: { time })
        _ = gate.filter("We need to get out of here, now.", hit)
        _ = gate.filter("I told you not to come back.", hit)
        _ = gate.filter("You should have listened to me, boy.", hit)
        XCTAssertTrue(gate.isOpen)
        time += PackGate.closeAfterMs + 1
        XCTAssertFalse(gate.isOpen)
        XCTAssertNil(gate.filter("Yeah.", hit))
    }
}

final class Ps5StatusTests: XCTestCase {
    func testRunningGameIsReadFromTheDiscoveryReply() throws {
        let info = try XCTUnwrap(Ps5Status.parse("HTTP/1.1 200 Ok\nhost-id:ABC\nhost-type:PS5\nhost-name:PS5-471\nhost-request-port:997\n"
            + "running-app-titleid:PPSA01234_00\nrunning-app-name:Mafia: The Old Country\n"))
        XCTAssertTrue(info.awake)
        XCTAssertEqual(info.hostName, "PS5-471")
        XCTAssertEqual(info.game, RunningGame(titleId: "PPSA01234_00", name: "Mafia: The Old Country"))
    }

    func testRestingOrHomeScreenHasNoGame() throws {
        let resting = try XCTUnwrap(Ps5Status.parse("HTTP/1.1 620 Server Standby\r\nhost-name:PS5-471\r\n"))
        XCTAssertFalse(resting.awake)
        XCTAssertNil(resting.game)
        XCTAssertNil(Ps5Status.parse("SRCH * HTTP/1.1\n"))
    }

    func testTitleIdMustLookLikeAPs5IdAndNameIsTidied() {
        XCTAssertEqual(RunningGame.create(titleId: " cusa00001 ", name: "  "), RunningGame(titleId: "CUSA00001", name: nil))
        XCTAssertNil(RunningGame.create(titleId: "NOTANID", name: "Game"))
        XCTAssertNil(RunningGame.create(titleId: nil, name: "Game"))
        XCTAssertEqual(RunningGame.normalizedName("Grand\u{0} Theft\tAuto   V\u{200B}"), "Grand Theft Auto V")
        XCTAssertEqual(RunningGame.normalizedName(String(repeating: "x", count: 300))?.count, RunningGame.maxName)
        XCTAssertTrue(Ps5Status.isAddress("192.168.1.20"))
        XCTAssertFalse(Ps5Status.isAddress("not-an-address"))
    }

    #if canImport(Darwin)
    func testNoReplyGivesNull() {
        XCTAssertNil(Ps5Status.query(host: "not-an-address", timeoutMs: 200))
        XCTAssertNil(Ps5Status.query(host: "127.0.0.1", timeoutMs: 200))
    }
    #endif

    private func pack(_ id: String, _ title: String, _ line: String = "Come on.", _ turkish: String = "Hadi.") -> GamePackLibrary.BundledFile {
        ("\(id).json", { Data("{\"format\":1,\"id\":\"\(id)\",\"title\":\"\(title)\",\"entries\":{\"\(PackKey.key(line)!)\":\"\(turkish)\"}}".utf8) })
    }

    /// PS5'in bildirdiği ad paketin başlığıyla eşleşir; uyan paket elle seçilmiş gibi kullanılır.
    func testRunningGameNameFindsItsPack() {
        let library = GamePackLibrary(bundled: {
            [self.pack("mafia-the-old-country", "Mafia: The Old Country"), self.pack("gta-v", "Grand Theft Auto V"),
             self.pack("death-stranding-2", "Death Stranding 2: On the Beach")]
        }, userDirectory: nil)
        XCTAssertEqual(library.findByGameName("Mafia: The Old Country"), "mafia-the-old-country")
        XCTAssertEqual(library.findByGameName("Grand Theft Auto V"), "gta-v")
        XCTAssertEqual(library.findByGameName("DEATH STRANDING 2: ON THE BEACH"), "death-stranding-2")
        XCTAssertNil(library.findByGameName("FINAL FANTASY VII REMAKE INTERGRADE"))
        XCTAssertFalse(library.gameKnown)
        library.detected = "gta-v"
        XCTAssertTrue(library.gameKnown)
        XCTAssertEqual(library.scopeTitle, "Grand Theft Auto V")
    }

    /// Oyun belliyken yalnız onun paketi aranır: aynı satır başka oyunda başka çevrilmiş olabilir.
    func testDetectedGameUsesOnlyItsOwnPack() {
        let library = GamePackLibrary(bundled: {
            [self.pack("a-game", "A Game", "Come on.", "Hadi."), self.pack("b-game", "B Game", "Come on.", "Gel hadi."),
             self.pack("c-game", "C Game", "Get down!", "Yere yat!")]
        }, userDirectory: nil)
        XCTAssertEqual(library.find("Come on."), "Gel hadi.")
        library.detected = "a-game"
        XCTAssertEqual(library.findWithPack("Come on."), PackMatch("Hadi.", packId: "a-game"))
        XCTAssertNil(library.find("Get down!"))
        // Kapatılan paket oyun belli olsa da kullanılmaz.
        library.setDisabled(["a-game"])
        XCTAssertFalse(library.gameKnown)
        XCTAssertEqual(library.find("Come on."), "Gel hadi.")
    }
}
