// SPDX-License-Identifier: AGPL-3.0-only

@testable import GameTranslatorCore
import XCTest

private func wordSet(_ words: String...) -> (String) -> Bool {
    let set = Set(words.map { $0.lowercased() })
    return { set.contains($0.lowercased()) }
}

/// Karışan harflerin onarımı (Windows: OcrConfusionsTests, Android: OcrConfusionsTest).
final class OcrConfusionsTests: XCTestCase {
    private let words = wordSet(
        "crouch", "to", "make", "your", "steps", "quiet", "knives", "do", "not", "vanish", "they", "are", "used", "for",
        "attack", "and", "defense", "the", "noise", "of", "moving", "after", "alter", "tor", "lake", "take", "it", "is",
        "modern", "moment", "are", "you", "ok", "hold", "on", "weapon", "from", "form",
        "time", "running", "out", "my", "love", "there", "much", "we", "won't", "find", "him", "this", "way", "let's",
        "that", "trail", "blood", "use", "words", "brother", "no", "truth", "hurts", "huh", "a", "i", "here", "where",
        "heywood", "as", "her", "hey", "wood", "oh", "help", "brother", "try", "contraption", "just", "next", "pod", "did",
        "something", "when", "sealed", "me", "in", "learned", "come", "had", "apples", "fi", "et", "oy", "money", "lot",
        "paying", "field", "bello", "win", "bellowing", "hence", "ray", "field", "mine", "should", "known", "stay", "trash",
        "sea", "woman", "little", "faith", "jackie")
    private let gameWords = wordSet(
        "think", "they'd", "ever", "let", "him", "go", "get", "some", "sleep", "gonna", "drive", "you", "guys", "to", "the",
        "other", "one", "does", "flying", "but", "it's", "drugs", "blow", "them", "out", "of", "water", "take", "holy",
        "shit", "ha", "karma's", "a", "bitch", "his", "head", "down", "helps", "people", "brothers", "okay", "is", "then", "lion")

    private func repair(_ line: String) -> String { OcrConfusions.repair(line, isWord: words) }

    private func check(_ cases: [(String, String)], file: StaticString = #filePath, line: UInt = #line) {
        for (read, expected) in cases { XCTAssertEqual(repair(read), expected, read, file: file, line: line) }
    }

    func testConfusedLettersAreRepaired() {
        check([
            ("Crouch Io make your steps quiet.", "Crouch to make your steps quiet."),
            ("Knives do nol vanish.", "Knives do not vanish."),
            ("They are used lor attack and defense.", "They are used for attack and defense."),
            ("The noise 01 moving", "The noise of moving"),
            ("Take it aller the attack.", "Take it after the attack."),
            ("Are you 0K?", "Are you OK?"),
            ("A rnodern weapon", "A modern weapon"),
            ("Io make it quiet", "To make it quiet"),
        ])
    }

    func testRoundFontConfusionsAreRepaired() {
        check([
            ("Tinne is running out, ny love.", "Time is running out, my love."),
            ("And there is nauch co do.", "And there is much to do."),
            ("Wc won't find hinn chis way.", "We won't find him this way."),
            ("Let's find chat trail of blood.", "Let's find that trail of blood."),
            ("Time is running out, noy iove.", "Time is running out, my love."),
        ])
    }

    func testGluedWordsAreSplit() {
        check([
            ("Andthereismuchtodo.", "And there is much to do."),
            ("Let'sfindthattrailofblood...", "Let's find that trail of blood..."),
            ("Useyourwords,brother.", "Use your words, brother."),
            ("No.It hurts.", "No. It hurts."),
            ("Shadowheart:Trythatcontraptionjustnexttothepod-theydidsomethingtoitwhen",
             "Shadowheart: Try that contraption just next to the pod - they did something to it when"),
        ])
    }

    func testExclamationReadAsLAndOtherSingleConfusions() {
        check([
            ("Ohl Time is running out.", "Oh! Time is running out."),
            ("Brotherl Brother!", "Brother! Brother!"),
            ("Help him, brotherl", "Help him, brother!"),
            ("Ohl no.", "Ohl no."),
            ("1 learned a long time ago.", "I learned a long time ago."),
            ("Stoy where you are!", "Stay where you are!"),
            ("paying you a lot of Ilioney", "paying you a lot of money"),
            ("You're o woman of little faith.", "You're a woman of little faith."),
            ("Street trosh in the seo.", "Street trash in the sea."),
            ("Conne on.", "Come on."),
            ("We had 1 apples.", "We had 1 apples."),
        ])
    }

    func testNamesRealWordsAndUnknownsAreLeftAlone() {
        for line in ["You live in Heywood, huh?", "They are Asherhere.", "U.S. Army", "Jackiefiet the car.", "Hence my bellowin'.",
                     "Jackie: Rayfield's mine.", "You shoulda known.", "They alter the form.", "Artyom, hold on!",
                     "Kephallonia is not Zorvald.", "They are xqlz."] {
            XCTAssertEqual(repair(line), line)
        }
        XCTAssertEqual(repair("Io lake the weapon"), "To lake the weapon")
        XCTAssertEqual(OcrConfusions.repair("Knives do nol vanish.", isWord: nil), "Knives do nol vanish.")
    }

    func testTurkishLettersInEnglishAreRepaired() {
        func turkish(_ line: String) -> String { OcrConfusions.repair(line, isWord: gameWords, fromTurkishLetters: true) }
        for (read, expected) in [
            ("thİnk they'd ever let hinı out", "think they'd ever let him out"),
            ("Go get some şleep.", "Go get some sleep."),
            ("gonna drive you guyş to the", "gonna drive you guys to the"),
            ("the other one does the flyinç", "the other one does the flying"),
            ("But İt's drugsı okay", "But It's drugs, okay"),
            ("Blow thenı out of the water!", "Blow them out of the water!"),
            ("Sanı: Ha ha. Karnıa's a bitch.", "Sam: Ha ha. Karma's a bitch."),
            ("his head downı helps people", "his head down, helps people"),
            ("Tommy Vercettı...", "Tommy Vercetti..."),
        ] {
            XCTAssertEqual(turkish(read), expected, read)
        }
        XCTAssertEqual(OcrConfusions.repair("thİnk şleep", isWord: gameWords), "thİnk şleep")
    }

    /// Uygulamayla gelen sözcük listesiyle de onarır.
    func testBuiltInWordListRepairs() {
        XCTAssertEqual(OcrConfusions.repair("Knives do nol vanish.", isWord: EnglishWords.builtIn.isWord), "Knives do not vanish.")
        XCTAssertEqual(OcrConfusions.repair("Andthereismuchtodo.", isWord: EnglishWords.builtIn.isWord), "And there is much to do.")
    }
}

/// Okumaların sözcük sözcük birleştirilmesi (Windows: ReadingConsensusTests, Android: ReadingConsensusTest).
final class ReadingConsensusTests: XCTestCase {
    private let words = wordSet(
        "stop", "the", "gate", "is", "open", "so", "do", "we", "hold", "this", "place", "or", "let", "them", "pass",
        "keep", "your", "head", "down", "and", "stay", "close", "to", "me", "slop", "wall")

    private func merge(_ best: String, _ others: String...) -> String {
        ReadingConsensus.merge(best, readings: [best] + others, isWord: words)
    }

    func testBrokenWordsAreFilledFromOtherReadings() {
        XCTAssertEqual(merge("Stop! The gafe is open, so do we hold this place?", "Stop! The gate is opem, so do we hold this place?"),
                       "Stop! The gate is open, so do we hold this place?")
        XCTAssertEqual(merge("Keep your heod down\nand stay clcse to me.", "Keep your head down\nand stay close to rne."),
                       "Keep your head down\nand stay close to me.")
    }

    func testRealWordsNamesNumbersAndPunctuationAreNeverReplaced() {
        XCTAssertEqual(merge("Slop! The gate is open.", "Stop! The gate is open."), "Slop! The gate is open.")
        XCTAssertEqual(merge("Markos: hold the gate, 12 of them!", "Marlos: hold the gate, 72 of them!"), "Markos: hold the gate, 12 of them!")
    }

    func testReadingsWithADifferentShapeAreIgnored() {
        XCTAssertEqual(merge("Hold the gafe open", "Hold the gate"), "Hold the gafe open")
        XCTAssertEqual(merge("Hold the gafe", "Hold the gate\nand stay"), "Hold the gafe")
        XCTAssertEqual(merge("Hold the gafe", "Hold the place"), "Hold the gafe")
        XCTAssertEqual(ReadingConsensus.merge("Hold the gafe", readings: ["Hold the gafe", "Hold the gate"], isWord: nil), "Hold the gafe")
        XCTAssertEqual(ReadingConsensus.merge("Hold the gafe", readings: ["Hold the gafe"], isWord: words), "Hold the gafe")
    }

    /// Hat: kararlı olana kadar gelen okumalar birleşir, satır onarılmış hâliyle çevrilir (Android: pipeline testi).
    func testMergerSettlesTheReadingsOfAnUnsettledLine() {
        let merger = ReadingMerger()
        let check = wordSet("hold", "the", "gate", "open", "now")
        merger.note("Hold the gate opem now")
        merger.note("Hold the gafe open now")
        merger.note("Hold the gafe open now")
        merger.note("ab")
        XCTAssertEqual(merger.settle("Hold the gafe open now", wordCheck: check), "Hold the gate open now")
        // Saklananlar unutuldu.
        XCTAssertEqual(merger.settle("Hold the gafe open now", wordCheck: check), "Hold the gafe open now")
        merger.note("Hold the gate opem now")
        XCTAssertEqual(merger.settle("Hold the gafe open now", wordCheck: nil), "Hold the gafe open now")
    }

    func testTidierCleansRepairsAndFixesLabels() {
        let tidier = LineTidier()
        XCTAssertEqual(tidier.tidy("Knives do nol vanish."), "Knives do nol vanish.")
        tidier.wordCheck = wordSet("knives", "do", "not", "vanish")
        XCTAssertEqual(tidier.tidy("Knives do nol vanish."), "Knives do not vanish.")
        XCTAssertEqual(tidier.tidy("Knives do nol vanish."), "Knives do not vanish.")
        XCTAssertEqual(tidier.tidy("HSS: GAETANO: Worries are for tomorrow."), "GAETANO: Worries are for tomorrow.")
        XCTAssertEqual(tidier.tidy("ENzo: Here."), "ENZO: Here.")
        XCTAssertEqual(tidier.tidy("the waters2aljd are cold"), "the waters are cold")
    }

    func testReadingTime() {
        XCTAssertEqual(SubtitlePipelineText.readingTime(""), 2000)
        XCTAssertEqual(SubtitlePipelineText.readingTime(String(repeating: "a", count: 50)), 4200)
        XCTAssertEqual(SubtitlePipelineText.readingTime(String(repeating: "a", count: 500)), 8000)
    }
}

/// Yinelenen arayüz yazıları (Windows: RepeatedTextTests, Android: RepeatedTextTest).
final class RepeatedTextTests: XCTestCase {
    func testLineShownThreeTimesWithoutSentenceEndIsInterface() {
        var time: Int64 = 0
        let repeats = RepeatedText { time }
        let bar = "Pick up Select Actions Sort Tooltips Close"
        XCTAssertNil(repeats.note(bar))
        time += 8000
        XCTAssertNil(repeats.note(bar))
        XCTAssertEqual(repeats.without([bar]), [bar])
        time += 8000
        XCTAssertEqual(repeats.note(bar), bar)
        XCTAssertEqual(repeats.without([bar]), [])
        XCTAssertEqual(repeats.without([bar, "We must go to the helm."]), ["We must go to the helm."])
    }

    func testUppercaseBillboardIsBlockedButSpokenLinesMayRepeat() {
        var time: Int64 = 0
        let repeats = RepeatedText { time }
        for _ in 0..<RepeatedText.times {
            repeats.note("GET YOURS: TODAY!")
            time += 3000
        }
        XCTAssertEqual(repeats.without(["GET YOURS: TODAY!"]), [])
        for line in ["Behind you!", "Mimir: Behind you", "Hold the line."] {
            for _ in 0..<6 {
                XCTAssertNil(repeats.note(line))
                time += 3000
            }
            XCTAssertEqual(repeats.without([line]), [line])
        }
    }

    func testRepeatsFarApartDoNotCountAndBlockEnds() {
        var time: Int64 = 0
        let repeats = RepeatedText { time }
        for _ in 0..<6 {
            XCTAssertNil(repeats.note("Bonus Action Movement"))
            time += RepeatedText.windowMs
        }
        for i in 0..<RepeatedText.times { repeats.note("Talus used Main Hand\nLine number \(i) is new.") }
        XCTAssertEqual(repeats.without(["Talus used Main Hand", "Line number 9 is new."]), ["Line number 9 is new."])
        time += RepeatedText.blockMs + 1
        XCTAssertEqual(repeats.without(["Talus used Main Hand"]), ["Talus used Main Hand"])
    }
}

/// Yeni satırın öncekinin düzeltmesi olup olmadığı (Windows: TextMatchTests, Android: CoreTest).
final class TextRevisionTests: XCTestCase {
    func testRevisionsOfTheSameSentence() {
        for (new, old) in [
            ("We do not have time.", "We do not have"),
            ("I know somethin' is wrong here", "I know somethinl is wrong here"),
            ("We do not have time for this.", "We do not have"),
            ("We do not have", "We do not have time for this."),
            ("have time for this, boy.", "We do not have time for this, boy."),
            ("The gate will not hold!", "The gale will not hold"),
            ("Across the deep waters he went, hoping a boat would carry him home.", "Across the deep waters O went, Hoping a boal would carry him home"),
        ] {
            XCTAssertTrue(TextMatch.isRevision(new, old), "\(new) / \(old)")
        }
    }

    func testDifferentSentencesAreNotRevisions() {
        for (new, old) in [("Get in the car.", "Where is the boy?"), ("Run to the boat!", "We do not have time for this."),
                           ("Where is the ship?", "Where is the boy?"), ("No.", "Yes."),
                           ("I told you to stay with your mother.", "You should not be here.")] {
            XCTAssertFalse(TextMatch.isRevision(new, old), "\(new) / \(old)")
        }
    }

    func testGrowthOnlyCountsAddedWords() {
        XCTAssertTrue(TextMatch.isGrowth("We do not have time.", "We do not"))
        XCTAssertFalse(TextMatch.isGrowth("We do not", "We do not have time."))
        XCTAssertFalse(TextMatch.isGrowth("We do not have time.", "We do not have time."))
        XCTAssertTrue(TextMatch.isGrowth("Then we go home.", "Then"))
        // Başı okunmamış satır: sondaki tek sözcük yetmez, iki sözcük yeter.
        XCTAssertFalse(TextMatch.isGrowth("Then we go home.", "home"))
        XCTAssertTrue(TextMatch.isGrowth("Then we go home.", "go home"))
    }

    func testWorseRereadAndRows() {
        XCTAssertTrue(TextMatch.isWorseReread("Across the deep waters O went, Hoping a boal would carry him home",
                                              "Across the deep waters he went, hoping a boat would carry him home."))
        XCTAssertFalse(TextMatch.isWorseReread("Across the deep waters he went, hoping a boat would carry him home tonight.",
                                               "Across the deep waters he went, hoping a boat would carry him home."))
        XCTAssertFalse(TextMatch.isWorseReread("Get in.", "Get in the car."))
        XCTAssertEqual(TextMatch.normalizeRows("We need to LEAVE!\r\n\r\nbefore they find us."), "we need to leave\nbefore they find us")
    }
}
