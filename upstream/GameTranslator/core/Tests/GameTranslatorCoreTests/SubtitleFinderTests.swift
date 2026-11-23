// SPDX-License-Identifier: AGPL-3.0-only

@testable import GameTranslatorCore
import XCTest

/// Otomatik altyazı algılama (Windows: SubtitleFinderTests, Android: SubtitleFinderTest).
final class SubtitleFinderTests: XCTestCase {
    /// Ortası (`x`, `y`) olan, genişliği metne göre, yüksekliği %2,5 olan satır.
    private func line(_ text: String, _ y: Double, x: Double = 0.5) -> TextLine {
        let half = min(0.45, Double(text.count) * 0.006)
        return TextLine(text, box: TextBox(left: x - half, top: y - 0.0125, right: x + half, bottom: y + 0.0125))
    }

    private func verdict(_ finder: SubtitleFinder, _ now: Int64, _ lines: TextLine...) -> SubtitleFinder.Verdict {
        finder.classify(lines, now: now).last!.verdict
    }

    private func inside(_ value: Double, _ band: (top: Double, bottom: Double)?) -> Bool {
        guard let band else { return false }
        return value >= band.top && value <= band.bottom
    }

    /// Konuşmacısız dört altyazıyla (`y` yüksekliğinde) şeridi öğrenmiş bulucu; son kare 9. saniyededir.
    private func learned(_ y: Double = 0.88) -> SubtitleFinder {
        let finder = SubtitleFinder()
        _ = finder.classify([line("Where are you going?", y)], now: 0)
        _ = finder.classify([line("We need to move, now.", y)], now: 3000)
        _ = finder.classify([line("Stay close to me.", y)], now: 6000)
        _ = finder.classify([line("Do not look back.", y)], now: 9000)
        XCTAssertNotNil(finder.band)
        return finder
    }

    func testLearnsTheBandFromTheFirstSubtitles() throws {
        let finder = SubtitleFinder()
        XCTAssertEqual(verdict(finder, 0, line("Where are you going?", 0.88)), .subtitle)
        _ = finder.classify([line("We need to move, now.", 0.88)], now: 3000)
        _ = finder.classify([line("Stay close to me.", 0.885)], now: 6000)
        XCTAssertNil(finder.band)
        _ = finder.classify([line("Do not look back.", 0.88)], now: 9000)
        let band = try XCTUnwrap(finder.band)
        XCTAssertTrue((0.78...0.86).contains(band.top))
        XCTAssertTrue((0.90...0.98).contains(band.bottom))
    }

    func testSpeakerLinesTeachTheBandQuickly() {
        let inline = SubtitleFinder()
        _ = inline.classify([line("Elena: Where are you going?", 0.88)], now: 0)
        XCTAssertNil(inline.band)
        _ = inline.classify([line("Tomas: Away from here.", 0.88)], now: 2000)
        XCTAssertNotNil(inline.band)

        let above = SubtitleFinder()
        _ = above.classify([line("ELENA", 0.79), line("I can tell from the look on your face", 0.82), line("that you were paid.", 0.85)], now: 0)
        XCTAssertTrue(inside(0.79, above.band) && inside(0.85, above.band))
    }

    func testShortCombatTextDoesNotTeachTheBand() {
        let finder = SubtitleFinder()
        _ = finder.classify([line("Shield Charge", 0.75)], now: 0)
        _ = finder.classify([line("Glide up", 0.75)], now: 1000)
        _ = finder.classify([line("Wings ready now", 0.75)], now: 2000)
        _ = finder.classify([line("Hold to pull", 0.75)], now: 3000)
        XCTAssertNil(finder.band)
        XCTAssertEqual(verdict(finder, 4000, line("Shield Charge", 0.75)), .notSentence)
    }

    func testLoadingScreenTipsDoNotKeepTheBandFromDialogue() {
        let finder = SubtitleFinder()
        _ = finder.classify([line("Mercenaries are among the strongest fighters in the land.", 0.45)], now: 0)
        _ = finder.classify([line("Hone your skills and climb their ranks.", 0.48)], now: 0)
        _ = finder.classify([line("You can cancel a parry early by dodging.", 0.45)], now: 4000)
        _ = finder.classify([line("Upgrade your ship to survive naval battles.", 0.45)], now: 8000)
        XCTAssertTrue(inside(0.45, finder.band))
        _ = finder.classify([line("ELENA", 0.79), line("These bows will make me rich.", 0.82)], now: 12000)
        _ = finder.classify([line("TOMAS", 0.79), line("They are that good, are they?", 0.82)], now: 15000)
        XCTAssertTrue(inside(0.82, finder.band))
        XCTAssertEqual(verdict(finder, 18000, line("War is coming, you know.", 0.82)), .subtitle)
    }

    func testTipsDoNotMoveABandBuiltFromDialogue() {
        let finder = SubtitleFinder()
        _ = finder.classify([line("Elena: Where are you going?", 0.88)], now: 0)
        _ = finder.classify([line("Tomas: Away from here.", 0.88)], now: 2000)
        for i in 0..<8 { _ = finder.classify([line("Tip number \(i): you can do many different things here.", 0.45)], now: 4000 + Int64(i) * 2000) }
        XCTAssertTrue(inside(0.88, finder.band))
        XCTAssertEqual(verdict(finder, 21000, line("One more piece of advice for you.", 0.45)), .outsideBand)
    }

    func testPromptsCornerTextTitlesMenusAndStaticTextAreNotSubtitles() {
        let finder = learned()
        XCTAssertEqual(verdict(finder, 12000, line("R2 Swing", 0.88)), .prompt)
        XCTAssertEqual(verdict(finder, 12000, line("Find the water tank on the roof.", 0.88, x: 0.15)), .offCenter)
        XCTAssertEqual(verdict(finder, 12000, line("RESET ALL TO DEFAULT", 0.88)), .notSentence)
        XCTAssertEqual(verdict(finder, 12000, line("Back", 0.88)), .notSentence)
        XCTAssertEqual(verdict(finder, 12000, line("Run!", 0.88)), .subtitle)
        XCTAssertEqual(verdict(finder, 12000, line("You can sneak up on enemies to take them down quietly.", 0.45)), .outsideBand)

        let objective = line("Reach the water tank before the others do.", 0.88)
        let still = learned()
        XCTAssertEqual(verdict(still, 10000, objective), .subtitle)
        XCTAssertEqual(verdict(still, 13000, objective), .subtitle)
        XCTAssertEqual(verdict(still, 16000, objective), .subtitle)
        XCTAssertEqual(verdict(still, 10000 + SubtitleFinder.staticAfterMs, objective), .staticText)
        XCTAssertEqual(verdict(still, 40000, objective), .subtitle)

        let menu = SubtitleFinder().classify([line("Map", 0.40), line("Settings", 0.44), line("Photo Mode", 0.48), line("Quit", 0.52)], now: 0)
        XCTAssertTrue(menu.allSatisfy { $0.verdict == .menu })
    }

    func testRelearnsWhenSubtitlesMoveAndOldEvidenceExpires() {
        let finder = learned(0.88)
        var time: Int64 = 12000
        for text in ["This game shows them at the top.", "Second line up here as well.", "And a third one to be sure.", "A fourth line follows it.",
                     "Then a fifth line appears.", "A sixth one comes after that.", "The seventh line is here now.", "Now this is the subtitle band."] {
            _ = finder.classify([line(text, 0.10)], now: time)
            time += 3000
        }
        XCTAssertEqual(verdict(finder, time, line("Everything is read up here.", 0.10)), .subtitle)
        XCTAssertEqual(verdict(finder, time + 500, line("The old place is not, anymore.", 0.88)), .outsideBand)

        let old = learned(0.88)
        let later = 9000 + SubtitleFinder.evidenceMs + 1000
        _ = old.classify([line("Elena: It has been a long time.", 0.30)], now: later)
        _ = old.classify([line("Tomas: Too long, I would say.", 0.30)], now: later + 3000)
        XCTAssertTrue(inside(0.30, old.band))
    }

    func testSubtitlesReturnsOnlyTheSubtitleLines() {
        let finder = learned()
        let lines = finder.subtitles([
            line("Objective updated: find the smuggler.", 0.20, x: 0.2),
            line("R2 Swing", 0.70),
            line("Elena: Only way is up.", 0.88),
        ], now: 12000)
        XCTAssertEqual(lines.map(\.text), ["Elena: Only way is up."])
    }

    /// Geniş alanın okumasından altyazı bloğu: şeritteki satırlar ve onlara bitişik olanlar; köşe yazıları dışarıda.
    func testGrowKeepsTheBlockAroundTheBand() {
        let band = (top: 0.84, bottom: 0.92)
        let lines = [
            line("ELENA", 0.82),
            line("According to the guards, he was running", 0.85),
            line("down the harbor road, shouting nonsense", 0.88),
            line("and nobody could stop him.", 0.91),
            line("Call Horse Press Twice", 0.88, x: 0.85),
            line("Objective: find the smuggler.", 0.30),
        ]
        XCTAssertEqual(SubtitleFinder.grow(lines, band: band).map(\.text),
                       ["ELENA", "According to the guards, he was running", "down the harbor road, shouting nonsense", "and nobody could stop him."])
        XCTAssertEqual(SubtitleFinder.grow([line("Objective: find the smuggler.", 0.30)], band: band).map(\.text), [])
        XCTAssertEqual(SubtitleFinder.withoutOffCenterLines([line("Centered line here.", 0.9), line("Corner", 0.9, x: 0.9)]).map(\.text),
                       ["Centered line here."])
    }

    func testButtonPromptsAreRecognised() {
        for prompt in ["R2 Swing", "△ Pick up", "L1 + R1 Spartan Rage", "Press ✕ to jump", "PRESS e TO CONTINUE", "X Interact",
                       "PHOTO MODE L1 + R1", "Movement 1.8", "RI (Hold) Pull", ":Read message", "X Select O Back □ Sort"] {
            XCTAssertTrue(ButtonPrompts.isPrompt(prompt), prompt)
        }
        for speech in ["Li, come here.", "Press on, brothers!", "Where are you going?", "X marks the spot."] {
            XCTAssertFalse(ButtonPrompts.isPrompt(speech), speech)
        }
    }
}
