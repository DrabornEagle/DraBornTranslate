// SPDX-License-Identifier: AGPL-3.0-only

import XCTest
@testable import GameTranslatorCore

/// Windows'taki GlossaryTests, TurkishSuffixTests ve TermProtectionTests ile aynı durumlar.
final class GlossaryTests: XCTestCase {
    private let gow = Glossary.of(["Leviathan Axe": "Leviathan Baltası", "Axe": "Balta", "Midgard": "Midgard", "World Serpent": "Dünya Yılanı"],
                                  keep: ["Kratos", "Atreus"])

    func testParsesTermsAndKeepNames() {
        XCTAssertEqual(gow.count, 6)
        XCTAssertEqual(gow.turkish("leviathan  axe"), "Leviathan Baltası")
        XCTAssertEqual(gow.turkish("KRATOS"), "Kratos")
        XCTAssertNil(gow.turkish("Freya"))
        XCTAssertEqual(Glossary.of(["...": "x", "Odin": "Odin", "": "y"]).count, 1)
    }

    func testFindsWholeWordsCaseInsensitiveLongestFirst() {
        let found = gow.find("THROW THE LEVIATHAN AXE, then the axe. Kratos's axes. Midgardian.")
        XCTAssertEqual(found.map(\.english), ["Leviathan Axe", "Axe", "Kratos"])
        XCTAssertEqual(found[0].start, 10)
    }

    func testJsonFileWithKeepExactAndBuiltIn() throws {
        let glossary = try Glossary.parse(Data(#"{"game":"PBZ","terms":{"Soul Blade":"Ruh Kılıcı"},"keep":["Kratos"],"keepExact":["Soul"]}"#.utf8))
        XCTAssertEqual(glossary.find("Soul, take the soul blade.").map(\.english), ["Soul", "Soul Blade"])
        XCTAssertEqual(glossary.find("I sold my soul.").count, 0)
        XCTAssertEqual(glossary.capitalWords, ["soul", "blade", "kratos"])
        XCTAssertTrue(glossary.fingerprint.hasPrefix("g") && glossary.fingerprint.count == 9)
        XCTAssertEqual(Glossary.builtIn.turkish("fast travel"), "hızlı seyahat")
        XCTAssertNotEqual(Glossary.merge([Glossary.builtIn, glossary]).fingerprint, Glossary.builtIn.fingerprint)
        XCTAssertEqual(Glossary.merge([Glossary.builtIn, glossary]).count, Glossary.builtIn.count + 2) // "Soul" genel sözlükte de var
    }

    func testStoreReadsTheUserFolder() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("glossary-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(#"{"terms":{"Leviathan Axe":"Leviathan Baltası"}}"#.utf8).write(to: directory.appendingPathComponent("gow.json"))
        try Data("bozuk".utf8).write(to: directory.appendingPathComponent("broken.json"))
        let store = GlossaryStore(directory: directory)
        XCTAssertEqual(store.glossary.turkish("leviathan axe"), "Leviathan Baltası")
        XCTAssertEqual(store.glossary.turkish("skill tree"), "yetenek ağacı")
    }
}

final class TurkishSuffixTests: XCTestCase {
    private func check(_ cases: [(String, String, String)], file: StaticString = #filePath, line: UInt = #line) {
        for (term, suffix, expected) in cases {
            XCTAssertEqual(TurkishSuffix.attach(term, suffix), expected, "\(term) + \(suffix)", file: file, line: line)
        }
    }

    func testProperNounsTakeHarmonizedSuffixWithApostrophe() {
        check([("Kratos", "u", "Kratos'u"), ("Kratos", "un", "Kratos'un"), ("Kratos", "a", "Kratos'a"),
               ("Kratos", "da", "Kratos'ta"), ("Kratos", "dan", "Kratos'tan"), ("Kratos", "la", "Kratos'la"),
               ("Kratos", "dur", "Kratos'tur"), ("Atreus", "un", "Atreus'un"), ("Midgard", "a", "Midgard'a"),
               ("Midgard", "da", "Midgard'da"), ("Midgard", "u", "Midgard'ı"), ("Midgard", "un", "Midgard'ın"),
               ("Midgard", "daki", "Midgard'daki"), ("Freya", "u", "Freya'yı"), ("Freya", "a", "Freya'ya"),
               ("Freya", "un", "Freya'nın"), ("Freya", "la", "Freya'yla"), ("Freya", "da", "Freya'da"),
               ("Mimir", "a", "Mimir'e"), ("Mimir", "u", "Mimir'i"), ("Mimir", "dan", "Mimir'den"),
               ("Brok", "da", "Brok'ta"), ("Jötunheim", "a", "Jötunheim'e"), ("Thor", "un", "Thor'un"),
               ("Sindri", "un", "Sindri'nin"), ("Sindri", "u", "Sindri'yi"), ("Odin", "ya", "Odin'e"),
               ("Freya", "nın", "Freya'nın"), ("Kratos", "nın", "Kratos'un"), ("Kratos", "", "Kratos")])
    }

    func testCompoundNamesTakeTheBufferN() {
        check([("Leviathan Baltası", "u", "Leviathan Baltası'nı"), ("Leviathan Baltası", "a", "Leviathan Baltası'na"),
               ("Leviathan Baltası", "da", "Leviathan Baltası'nda"), ("Leviathan Baltası", "dan", "Leviathan Baltası'ndan"),
               ("Leviathan Baltası", "un", "Leviathan Baltası'nın"), ("Leviathan Baltası", "la", "Leviathan Baltası'yla"),
               ("Dünya Ağacı", "a", "Dünya Ağacı'na"), ("Kaos Bıçakları", "u", "Kaos Bıçakları'nı")])
    }

    func testCommonNounsAreWrittenTogether() {
        check([("can iksiri", "u", "can iksirini"), ("kontrol noktası", "a", "kontrol noktasına"),
               ("bekleme süresi", "da", "bekleme süresinde"), ("envanter", "a", "envantere"),
               ("kılıç", "u", "kılıcı"), ("kitap", "u", "kitabı"), ("bıçak", "u", "bıçağı"),
               ("renk", "u", "rengi"), ("top", "u", "topu"), ("balta", "u", "baltayı"),
               ("balta", "lar", "baltalar"), ("kalkan", "dan", "kalkandan"), ("kalkan", "la", "kalkanla")])
    }
}

final class TermProtectionTests: XCTestCase {
    private let gow = Glossary.of(["Leviathan Axe": "Leviathan Baltası", "Midgard": "Midgard", "health potion": "can iksiri"],
                                  keep: ["Kratos", "Atreus"])

    func testReplacesTermsWithPlaceholderNames() {
        let (text, slots) = TermProtector(gow, "").protect("Kratos, take the Leviathan Axe to Midgard. Kratos!")
        XCTAssertEqual(text, "Zarmon, take the Velkor to Dorvan. Zarmon!")
        XCTAssertEqual(slots.map(\.english), ["Kratos", "Leviathan Axe", "Midgard"])
    }

    func testRestoresTermsWithSuffixesDespiteCaseAndSpacing() {
        let cases = [
            ("Zarmon, Velkor'u Dorvan'a götür. Zarmon!", "Kratos, Leviathan Baltası'nı Midgard'a götür. Kratos!"),
            ("ZARMON, VELKOR'U DORVAN'A GÖTÜR. ZARMON!", "Kratos, Leviathan Baltası'nı Midgard'a GÖTÜR. Kratos!"),
            ("Zarmon, Velkor ’u Dorvana götür. Zarmon!", "Kratos, Leviathan Baltası'nı Midgard'a götür. Kratos!"),
        ]
        for (output, expected) in cases {
            let protector = TermProtector(gow, "")
            let slots = protector.protect("Kratos, take the Leviathan Axe to Midgard. Kratos!").slots
            XCTAssertEqual(TermProtector.restore(output, expected: slots, all: protector.allSlots), expected)
        }
    }

    func testMissingOrForeignPlaceholderMeansUnreliable() {
        let protector = TermProtector(gow, "")
        let slots = protector.protect("Take the Leviathan Axe to Midgard.").slots
        XCTAssertNil(TermProtector.restore("Baltayı Dorvan'a götür.", expected: slots, all: protector.allSlots))
        let other = TermProtector(gow, "")
        _ = other.protect("Kratos!")
        let second = other.protect("Go to Midgard.").slots
        XCTAssertNil(TermProtector.restore("Zarmon Velkor'a git.", expected: second, all: other.allSlots))
        XCTAssertEqual(TermProtector.restore("Velkor'a git.", expected: second, all: other.allSlots), "Midgard'a git.")
    }

    func testCommonNounAtSentenceStartIsCapitalizedAndQuotesAreNotSuffixes() {
        let slots = TermProtector(gow, "").protect("Drink the health potion. Health potion first!").slots
        XCTAssertEqual(TermProtector.restore("Zarmon'u iç. Önce Zarmon!", expected: slots), "Can iksirini iç. Önce can iksiri!")
        let quoted = TermProtector(gow, "").protect("He said 'Midgard' again.").slots
        XCTAssertEqual(TermProtector.restore("Yine 'Zarmon' dedi.", expected: quoted), "Yine 'Midgard' dedi.")
    }

    func testPlaceholderAlreadyInTheSourceIsSkipped() {
        XCTAssertEqual(TermProtector(gow, "Zarmon met Kratos.").protect("Zarmon met Kratos.").text, "Zarmon met Velkor.")
    }
}

final class UiPromptsTests: XCTestCase {
    func testButtonBarCommandsAreTranslatedFromTheList() {
        XCTAssertEqual(UiPrompts.translate("OPEN ✕"), "Aç ✕")
        XCTAssertEqual(UiPrompts.translate("PUT DOWN ○"), "Bırak ○")
        XCTAssertEqual(UiPrompts.translate("SET DESTINATION ✕"), "Hedef belirle ✕")
        XCTAssertEqual(UiPrompts.translate("□ DRINK"), "□ İç")
        XCTAssertEqual(UiPrompts.translate("REFUEL"), "Yakıt al")
        XCTAssertNil(UiPrompts.translate("Open the door"))
        XCTAssertNil(UiPrompts.translate("SHOOT THE MOON ✕"))
        XCTAssertNil(UiPrompts.translate("✕ ○"))
    }

    func testDroppedIconsComeBack() {
        XCTAssertEqual(UiPrompts.keepIcons(source: "SHOW TEXT △", translation: "Metni göster"), "Metni göster △")
        XCTAssertEqual(UiPrompts.keepIcons(source: "CLOSE L1", translation: "Kapat l1"), "Kapat L1")
        XCTAssertEqual(UiPrompts.keepIcons(source: "□ Pick up", translation: "Al"), "□ Al")
        XCTAssertEqual(UiPrompts.keepIcons(source: "Hold ✕ to climb", translation: "Tırmanmak için ✕ basılı tut"), "Tırmanmak için ✕ basılı tut")
        XCTAssertEqual(UiPrompts.keepIcons(source: "Hold ✕ to climb", translation: "Tırmanmak için basılı tut"), "Tırmanmak için basılı tut ✕")
    }

    func testIconsAndButtonNamesAreKnown() {
        for token in ["✕", "(○)", "R2", "l1", "Ⓑ", "O", "X", "OPTIONS", "↑"] { XCTAssertTrue(ButtonPrompts.isIcon(token), token) }
        for token in ["Open", "o", "LZ", "1"] { XCTAssertFalse(ButtonPrompts.isIcon(token), token) }
    }
}

final class ShortPhrasesTests: XCTestCase {
    func testKnownShortLinesHaveFixedTranslations() {
        for (source, expected) in [("Well?", "Ee?"), ("See?", "Gördün mü?"), ("Brother?", "Kardeşim?"), ("Hurry! Please!", "Acele et! Lütfen!"),
                                   ("Okay.", "Tamam."), ("Morning!", "Günaydın!"), ("No...", "Hayır..."), ("...Yeah.", "...Evet."),
                                   ("Let's go.", "Hadi gidelim."), ("Let’s go!", "Hadi gidelim!"), ("Roger that!", "Anlaşıldı!")] {
            XCTAssertEqual(ShortPhrases.translate(source), expected, source)
        }
    }

    func testOtherLinesAreLeftToTheTranslator() {
        for source in ["See? We're rich.", "Behind you, brother!", "Well, I think we should go now and find him.", "Faye?"] {
            XCTAssertNil(ShortPhrases.translate(source), source)
        }
    }

    func testShoutingTranslationOfAQuietLineIsLowered() {
        XCTAssertEqual(ShortPhrases.fixShouting(source: "No...", translation: "HAYIR..."), "Hayır...")
        XCTAssertEqual(ShortPhrases.fixShouting(source: "Where is it?", translation: "NEREDE?"), "Nerede?")
        XCTAssertEqual(ShortPhrases.fixShouting(source: "STOP!", translation: "DUR!"), "DUR!")
        XCTAssertEqual(ShortPhrases.fixShouting(source: "Wait.", translation: "Bekle."), "Bekle.")
        XCTAssertEqual(ShortPhrases.fixShouting(source: "Is it the NCPD?", translation: "NCPD mi?"), "NCPD mi?")
    }

    func testRadioSignOffIsSplit() {
        let split = ShortPhrases.splitRadioTail("Base, we apprehended the engineer! Over!")
        XCTAssertEqual(split?.main, "Base, we apprehended the engineer!")
        XCTAssertEqual(split?.tail, "Tamam!")
        XCTAssertNil(ShortPhrases.splitRadioTail("Game over."))
    }
}

/// Türkçe düzeltmeler ve cihaz üstü çevirinin metin hattı (Windows: TurkishPostEditTests, FreeTranslationTests).
final class FreeTranslationTests: XCTestCase {
    func testSourceEndingIsKept() {
        for (source, translation, expected) in [
            ("Run!", "Koş.", "Koş!"), ("Run!", "Koş", "Koş!"), ("What?!", "Ne?", "Ne?!"),
            ("Wait…", "Bekle.", "Bekle…"), ("Wait...", "Bekle.", "Bekle..."), ("Where is he?", "O nerede.", "O nerede?"),
            ("He said \"Run!\"", "\"Koş.\" dedi.", "\"Koş.\" dedi."), ("\"Run!\"", "\"Koş.\"", "\"Koş!\""),
            ("We go.", "Gidiyoruz!", "Gidiyoruz!"), ("Boy!", "Evlat!", "Evlat!"),
        ] {
            XCTAssertEqual(TurkishPostEdit.apply(source: source, translation: translation), expected, source)
        }
    }

    func testSpacesAndQuotesFollowTheRules() {
        XCTAssertEqual(TurkishPostEdit.apply(source: "Okay. Go.", translation: "Dur , bekle !"), "Dur, bekle!")
        XCTAssertEqual(TurkishPostEdit.apply(source: "Okay. Go.", translation: "Ne  oldu ?"), "Ne oldu?")
        XCTAssertEqual(TurkishPostEdit.apply(source: "Okay. Go.", translation: "Peki ... gidelim ."), "Peki... gidelim.")
        XCTAssertEqual(TurkishPostEdit.apply(source: "He said \"run\", Kratos's son.", translation: "“Koş” dedi, Kratos’un oğlu."), "\"Koş\" dedi, Kratos'un oğlu.")
        XCTAssertEqual(TurkishPostEdit.apply(source: "He said “run”, Kratos’s son.", translation: "\"Koş\" dedi, Kratos'un oğlu."), "“Koş” dedi, Kratos’un oğlu.")
    }

    func testImperativesFollowAnInformalLine() {
        XCTAssertEqual(TurkishPostEdit.register(["Sana söyledim.", "Bekleyin!", "Dikkat edin, evlat."]), ["Sana söyledim.", "Bekle!", "Dikkat et, evlat."])
        XCTAssertEqual(TurkishPostEdit.register(["Seni gördüm. Hemen gelin!"]), ["Seni gördüm. Hemen gel!"])
        for sentence in ["Bekleyin!", "Siz bekleyin, sana sonra bakarım!", "Sen gelin misin?"] {
            XCTAssertEqual(TurkishPostEdit.register([sentence]), [sentence])
        }
        XCTAssertEqual(TurkishPostEdit.register([nil, "Sana söyledim.", nil, "Durun!"]), [nil, "Sana söyledim.", nil, "Dur!"])
    }

    func testSeparatorAndShortLines() {
        XCTAssertTrue(FreeTranslation.isShort("Not you, boy."))
        XCTAssertFalse(FreeTranslation.isShort("We go to the mountain."))
        XCTAssertFalse(FreeTranslation.isShort("..."))
        XCTAssertEqual(FreeTranslation.afterSeparator("Annen nerede? *** Sen değil.", source: "Not you."), "Sen değil.")
        XCTAssertNil(FreeTranslation.afterSeparator("Annen nerede? Sen değil.", source: "Not you."))
        XCTAssertNil(FreeTranslation.afterSeparator("Annen *** nerede? *** Sen değil.", source: "Not you."))
        XCTAssertNil(FreeTranslation.afterSeparator("Annen nerede? **** Sen değil.", source: "Not you."))
    }

    private let gow = Glossary.of(["Leviathan Axe": "Leviathan Baltası"], keep: ["Kratos", "Atreus"])

    func testTermsAreProtectedAndRestored() {
        var calls: [String] = []
        let result = FreeTranslation.translate("Kratos, throw the Leviathan Axe!", context: nil, glossary: gow, normalize: { $0 }) { input in
            calls.append(input)
            return "Zarmon, Velkor'u fırlat."
        }
        XCTAssertEqual(calls, ["Zarmon, throw the Velkor!"])
        XCTAssertEqual(result, "Kratos, Leviathan Baltası'nı fırlat!")
    }

    func testLostPlaceholderFallsBackAndGameEnglishRunsFirst() {
        var calls: [String] = []
        let result = FreeTranslation.translate("Throw the Leviathan Axe!", context: nil, glossary: gow, normalize: { $0 }) { input in
            calls.append(input)
            return input.contains("Zarmon") ? "Baltayı fırlat!" : "Leviathan Baltasını fırlat!"
        }
        XCTAssertEqual(calls, ["Throw the Zarmon!", "Throw the Leviathan Axe!"])
        XCTAssertEqual(result, "Leviathan Baltasını fırlat!")
        var sent: String?
        _ = FreeTranslation.translate("HEADS UP, KRATOS!", context: nil, glossary: gow,
                                      normalize: { $0 == "HEADS UP, KRATOS!" ? "Heads up, kratos!" : $0 }) { input in
            sent = input
            return "Dikkat et, Zarmon!"
        }
        XCTAssertEqual(sent, "Watch out, Zarmon!")
    }

    func testShortLineIsTranslatedWithItsContext() {
        var calls: [String] = []
        let result = FreeTranslation.translate("Not you.", context: "Where is your mother?", glossary: gow, normalize: { $0 }) { input in
            calls.append(input)
            return input.contains("***") ? "Annen nerede, sen değil." : "Sen değil."
        }
        XCTAssertEqual(calls, ["Where is your mother? *** Not you.", "Not you."])
        XCTAssertEqual(result, "Sen değil.")
    }

    func testCacheKeyAndRecentLines() {
        XCTAssertEqual(FreeTranslation.cacheKey("Go.", context: nil, glossary: gow), "Go.")
        XCTAssertTrue(FreeTranslation.cacheKey("Kratos, go.", context: "Hey.", glossary: gow).hasPrefix(gow.fingerprint + "\u{1F}Hey.\u{1F}"))
        var now = Date(timeIntervalSince1970: 0)
        let recent = RecentLines(clock: { now })
        XCTAssertNil(recent.shown("Where is your mother?"))
        now += 2
        XCTAssertEqual(recent.shown("Not you."), "Where is your mother?")
        XCTAssertEqual(recent.shown("Not you."), "Where is your mother?")
        now += 30
        XCTAssertNil(recent.shown("Boy!"))
    }
}

final class MisspellingTests: XCTestCase {
    private let words = EnglishWords.of(["i", "got", "it", "know", "don't", "can't", "they're", "the", "kraven", "is", "here", "you", "going"])

    func testMisreadWordsAreCountedAndNamesAreNot() {
        let spelling = Misspelling(isWord: words.isWord)
        XCTAssertTrue(spelling.looksMisread("Igot it"))
        XCTAssertFalse(spelling.looksMisread("I got it, Ganke."))
        XCTAssertFalse(spelling.looksMisread("I dont know"))
        XCTAssertTrue(spelling.isGarbage("Thre Ogedto egey billior"))
        XCTAssertFalse(spelling.isGarbage("Kraven is here"))
        XCTAssertTrue(Misspelling.isOrphanFragment("bad at surrendering.", previous: nil))
        XCTAssertFalse(Misspelling.isOrphanFragment("old friend.", previous: "You grow slow in your old age,"))
        XCTAssertTrue(Misspelling.isOrphanFragment("round.", previous: "Get down!"))
        XCTAssertFalse(Misspelling.isOrphanFragment("Round.", previous: nil))
    }

    func testEnglishWordListLoads() {
        let list = EnglishWords.builtIn
        XCTAssertGreaterThan(list.count, 50_000)
        XCTAssertTrue(list.isWord("Going"))
        XCTAssertTrue(list.isWord("Kratos's") == list.isWord("Kratos"))
        XCTAssertTrue(list.isWord("goin'"))
        XCTAssertTrue(list.isWord("well-known"))
        XCTAssertFalse(list.isWord("gonnatry"))
    }

    func testOcrNoiseScore() {
        XCTAssertGreaterThan(OcrNoise.score("you will"), OcrNoise.score("yowwiII"))
        XCTAssertTrue(OcrNoise.looksGarbled("Gtt solkolfege."))
        XCTAssertFalse(OcrNoise.looksGarbled("Hmm, let's go."))
    }
}

/// Bölünen cümleler ve konuşmacı adları (Windows: ContinuationTranslatorTests, Android: ContinuationTranslatorTest).
final class ContinuationTranslatorTests: XCTestCase {
    private final class Box: @unchecked Sendable {
        var time: Int64 = 0
        var sent: [String] = []
    }

    /// Satırları sırayla çevirir; (çevirmene giden metinler, dönen çeviriler).
    private func feed(_ lines: [(String, Int64)]) async throws -> ([String], [String]) {
        let box = Box()
        let translator = ContinuationTranslator(inner: { text in box.sent.append(text); return "tr:" + text }, clock: { box.time })
        var shown: [String] = []
        for (text, at) in lines {
            box.time = at
            shown.append(try await translator.translate(text) ?? "nil")
        }
        return (box.sent, shown)
    }

    func testSpeakerNameIsKeptAsItIsAndOnlyTheSpeechIsTranslated() async throws {
        let (sent, shown) = try await feed([("ANNA: Are you all right?", 0), ("Careful now.", 5000)])
        XCTAssertEqual(sent, ["Are you all right?", "Careful now."])
        XCTAssertEqual(shown, ["ANNA: tr:Are you all right?", "tr:Careful now."])
    }

    func testSpeakerNameReadTwiceIsNotPartOfTheSpeech() async throws {
        for (line, speech) in [
            ("Faye: Faye: This way.", "This way."),
            ("Kratos: Kratos. The tracks stop here.", "The tracks stop here."),
            ("Jesse: \"Jesse: Sounds exciting.", "Sounds exciting."),
            ("Lae'zel: Lae'ze: It cannot be helped. Come.", "It cannot be helped. Come."),
            ("Faye: S This way.", "This way."),
            ("CAL • Think BD wants you to knock that down.", "Think BD wants you to knock that down."),
            ("BODE ·- Now I'm taking orders from a droid?", "Now I'm taking orders from a droid?"),
            ("Faye: Faye:S This way.", "This way."),
            ("Varl: Varl• So the storms.", "So the storms."),
            ("Woman's Voice: Woman's Voice The Zero Dawn system.", "The Zero Dawn system."),
            ("Aloy: \" —Aloy: We'll need to cut through.", "We'll need to cut through."),
            ("Sam: Sam, wait for me!", "Sam, wait for me!"),
            ("Sam: Samuel is gone.", "Samuel is gone."),
        ] {
            let (sent, _) = try await feed([(line, 0)])
            XCTAssertEqual(sent, [speech], line)
        }
    }

    func testMisreadColonAndMisreadSpeakerNamesUseTheKnownName() async throws {
        var lines: [(String, Int64)] = (0..<4).map { ("Stints: Line number \($0) it is.", Int64($0) * 20000) }
        lines += [("Stintsy Fuck, he's here.", 100_000), ("Jockie Hey, pal.", 120_000), ("Stints is a cop.", 140_000)]
        let (_, shown) = try await feed(lines)
        XCTAssertEqual(shown[4], "Stints: tr:Fuck, he's here.")
        XCTAssertEqual(shown[5], "tr:Jockie Hey, pal.")
        XCTAssertEqual(shown[6], "tr:Stints is a cop.")

        let mimir: [(String, Int64)] = (0..<4).map { ("Mimir: Line number \($0) it is.", Int64($0) * 20000) }
            + [("Minnir: He never came back.", 100_000), ("QHI: Mimir: It's been some time...", 120_000)]
        let (sent, said) = try await feed(mimir)
        XCTAssertEqual(said[4], "Mimir: tr:He never came back.")
        XCTAssertEqual(sent.last, "It's been some time...")
        XCTAssertEqual(said.last, "Mimir: tr:It's been some time...")
    }

    func testSeveralSpeakersAreTranslatedSeparatelyWithTheirNames() async throws {
        let (sent, shown) = try await feed([("DUKE: I cannot believe my eyes!\nYou gave us a scare, brother.\nDAMIR: Move, Duke!", 0)])
        XCTAssertEqual(sent.sorted(), ["I cannot believe my eyes! You gave us a scare, brother.", "Move, Duke!"])
        XCTAssertEqual(shown[0], "DUKE: tr:I cannot believe my eyes! You gave us a scare, brother.\nDAMIR: tr:Move, Duke!")
        let (_, alone) = try await feed([("Mimir: It's been some time...\nKratos:", 0)])
        XCTAssertEqual(alone[0], "Mimir: tr:It's been some time...")
    }

    func testSentenceSplitOverSeveralSubtitlesIsTranslatedWhole() async throws {
        let (sent, shown) = try await feed([("Marko: You are the ones", 0), ("Marko: who are going to need help...", 2000),
                                            ("Marko: when they come for you.", 5000), ("Elena: Come for us?", 9000)])
        XCTAssertEqual(sent, ["You are the ones", "You are the ones who are going to need help...",
                              "You are the ones who are going to need help... when they come for you.", "Come for us?"])
        XCTAssertEqual(shown[2], "Marko: tr:You are the ones who are going to need help... when they come for you.")
        XCTAssertEqual(shown[3], "Elena: tr:Come for us?")
        let (_, kraven) = try await feed([("Kraven: You grow slow in your old age,", 0), ("old friend.", 2500)])
        XCTAssertEqual(kraven[1], "Kraven: tr:You grow slow in your old age, old friend.")
    }

    func testFinishedSentencesAnotherSpeakerAndLateLinesStaySeparate() async throws {
        let (sent, _) = try await feed([("Sandman: Leave me alone!", 0), ("Sandman: I just wanted a regular life!", 2000),
                                        ("Sandman: You care about them--", 4000), ("Elena: Of course I do.", 6000),
                                        ("Rio: I am heading downtown to", 8000), ("Rio: help coordinate supplies.", 8000 + ContinuationTranslator.withinMs + 1)])
        XCTAssertEqual(sent, ["Leave me alone!", "I just wanted a regular life!", "You care about them--", "Of course I do.",
                              "I am heading downtown to", "help coordinate supplies."])
    }

    func testNewSentenceAfterAnEllipsisPartialReadsAndRereadsAreNotJoined() async throws {
        let (sent, _) = try await feed([("ANNA: At least it held...", 0), ("ANNA: Well, I would say we are out of the woods.", 3000),
                                        ("ANNA: Got another", 9000), ("ANNA: We sure gave them a fight!", 11000),
                                        ("PRESS X TO CONTINUE", 20000), ("LEVEL 2", 22000)])
        XCTAssertEqual(sent[1], "Well, I would say we are out of the woods.")
        XCTAssertEqual(sent[3], "We sure gave them a fight!")
        XCTAssertEqual(sent[5], "LEVEL 2")
        let (again, _) = try await feed([("Marko: I havent been okay", 0), ("Marko: I haven't been okay", 500), ("Marko: for a long time--", 2500)])
        XCTAssertEqual(again[1], "I haven't been okay")
        XCTAssertEqual(again[2], "I havent been okay for a long time--")
        let (cut, _) = try await feed([("DUKE: Colonel, Sir! We have a situation here...", 0), ("r and Hit Indicators are active by default.", 600)])
        XCTAssertEqual(cut.last, "r and Hit Indicators are active by default.")
    }

    func testOpenSentenceJoinsACapitalizedContinuationAndMisreadSpeaker() async throws {
        let (sent, _) = try await feed([("Priestess: The carvings say the spear was hidden in\nthe caves beneath", 0),
                                        ("Priestess: Kephallonia when the temple was built!", 3000)])
        XCTAssertEqual(sent[1], "The carvings say the spear was hidden in the caves beneath Kephallonia when the temple was built!")
        let (marta, shown) = try await feed([("Marta: Okay,", 0), ("arta: the gate is open now!", 1500)])
        XCTAssertEqual(marta[1], "Okay, the gate is open now!")
        XCTAssertEqual(shown[1], "Marta: tr:Okay, the gate is open now!")
    }

    func testRepeatedLinesInOneReadingCollapse() {
        XCTAssertEqual(ContinuationTranslator.withoutRepeatedLines("Kraven: A fate yowwill not share.\nKraven: A fate you will not share."),
                       "Kraven: A fate you will not share.")
        XCTAssertEqual(ContinuationTranslator.withoutRepeatedLines("Where are you going?\n- Home."), "Where are you going?\n- Home.")
        XCTAssertEqual(ContinuationTranslator.withoutRepeatedLines(" One line. "), "One line.")
        XCTAssertEqual(ContinuationTranslator.withoutRepeatedLines("Take the north road.\nThen the south road."), "Take the north road.\nThen the south road.")
    }

    func testCapsNameFixesLowercaseI() {
        for (read, expected) in [("DAMiR", "DAMIR"), ("SOLDiER", "SOLDIER"), ("GAETANo", "GAETANO"), ("ENzo", "ENZO"),
                                 ("McKay", "McKay"), ("Young ALEXiOS", "Young ALEXIOS"), ("Mimir", "Mimir"), ("Ri", "Ri")] {
            XCTAssertEqual(ContinuationTranslator.capsName(read), expected)
        }
    }

    func testPhrasebookAndRadioSignOff() async throws {
        let box = Box()
        let translator = ContinuationTranslator(inner: { text in box.sent.append(text); return "tr:" + text }, phrasebook: ShortPhrases.translate)
        let well = try await translator.translate("Mimir: Well?")
        XCTAssertEqual(well, "Mimir: Ee?")
        XCTAssertTrue(box.sent.isEmpty)
        let radio = try await translator.translate("OFFICER: Base, we apprehended the engineer! Over!")
        XCTAssertEqual(radio, "OFFICER: tr:Base, we apprehended the engineer! Tamam!")
        XCTAssertEqual(box.sent.last, "Base, we apprehended the engineer!")
    }

    func testFailedTranslationIsNil() async throws {
        let first = try await ContinuationTranslator(inner: { _ in nil }).translate("Where are you going?")
        XCTAssertNil(first)
        let second = try await ContinuationTranslator(inner: { _ in nil }).translate("DUKE: Go!\nDAMIR: Move!")
        XCTAssertNil(second)
    }
}
