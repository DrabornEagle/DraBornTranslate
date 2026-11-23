// SPDX-License-Identifier: AGPL-3.0-only

@testable import GameTranslatorCore
import XCTest

/// Xbox tuşları: işaretler, görüntüden tanıma, harf olarak okunan tuşlar ve Otomatik karar (Windows: XboxButtonsTests,
/// Android: XboxButtonsTest; aynı yapay simgeler).
final class XboxButtonsTests: XCTestCase {
    private let size = 96

    /// Koyu zeminde `disc` renginde daire; içine `mark` koyu çizilir.
    private func icon(_ disc: (Int, Int, Int), mark: (Double, Double) -> Bool = { _, _ in false }) -> PixelImage {
        let image = PixelImage(width: size * 2, height: size)
        for y in 0..<size {
            for x in 0..<(size * 2) {
                let dx = Double(x - size), dy = Double(y) - Double(size) / 2
                let r = Double(size) * 0.32
                var color = (20, 20, 24)
                if dx * dx + dy * dy <= r * r {
                    color = disc
                    if mark(dx / r, dy / r) { color = (30, 30, 30) }
                }
                image.set(x, y, color.0, color.1, color.2)
            }
        }
        return image
    }

    private let green = (127, 186, 0)
    private let red = (232, 17, 35)
    private let blue = (0, 120, 215)
    private let yellow = (255, 185, 0)
    private let white = (235, 235, 235)

    /// Üç yatay çizgi (Menu).
    private func bars(_ x: Double, _ y: Double) -> Bool {
        abs(x) < 0.42 && (abs(y + 0.3) < 0.08 || abs(y) < 0.08 || abs(y - 0.3) < 0.08)
    }

    private func cross(_ x: Double, _ y: Double) -> Bool {
        abs(x) < 0.5 && abs(y) < 0.5 && (abs(x - y) < 0.18 || abs(x + y) < 0.18)
    }

    func testXboxFaceButtonsAreReadFromTheirColour() {
        let xbox = ButtonLayoutVote(.xbox)
        XCTAssertEqual(ButtonIcons.classify(icon(green), layout: xbox), "Ⓐ")
        XCTAssertEqual(ButtonIcons.classify(icon(red), layout: xbox), "Ⓑ")
        XCTAssertEqual(ButtonIcons.classify(icon(blue), layout: xbox), "Ⓧ")
        XCTAssertEqual(ButtonIcons.classify(icon(yellow), layout: xbox), "Ⓨ")
    }

    func testPlayStationColoursStayPlayStation() {
        // Seçili PlayStation'da mavi daire ✕, sarı daire harita işaretidir.
        let ps = ButtonLayoutVote(.playstation)
        XCTAssertEqual(ButtonIcons.classify(icon(blue), layout: ps), "✕")
        XCTAssertEqual(ButtonIcons.classify(icon(yellow), layout: ps), "◆")
        XCTAssertEqual(ButtonIcons.classify(icon(yellow), readText: { _ in "Y" }, layout: ps), "◆")
    }

    func testYellowCircleWithYIsTheXboxYButtonInAuto() {
        let vote = ButtonLayoutVote()
        XCTAssertEqual(ButtonIcons.classify(icon(yellow), readText: { _ in "Y" }, layout: vote), "Ⓨ")
        XCTAssertTrue(vote.leansXbox)
    }

    func testLetterReadAtTheIconBecomesTheXboxButton() {
        // GTA 6 Xbox: beyaz dolu dairede koyu "X" (satır okumasında "x"); Xbox'ta Ⓧ, PlayStation'da ✕.
        let crossed = icon(white, mark: cross)
        XCTAssertEqual(ButtonIcons.classify(crossed, readLine: { _, _, _, _ in "x" }, layout: ButtonLayoutVote(.xbox)), "Ⓧ")
        XCTAssertEqual(ButtonIcons.classify(crossed, readLine: { _, _, _, _ in "x" }, layout: ButtonLayoutVote(.playstation)), "✕")
        // Xbox'ın yeşil dairesinde okunan "A" Otomatik'te de Ⓐ'dır (renk Xbox yeşili, şekil üçgen değil).
        XCTAssertEqual(ButtonIcons.classify(icon(green), readLine: { _, _, _, _ in "A" }), "Ⓐ")
    }

    func testDigitAtTheIconIsNotASymbol() {
        // Yarısı okunmuş "R2" rozeti: simgenin yerinde rakam okunursa yüz tuşu sembolü sanılmaz.
        XCTAssertNil(ButtonIcons.classify(icon(white, mark: cross), readLine: { _, _, _, _ in "2" }))
    }

    func testShoulderLabelsFollowTheFamily() {
        XCTAssertEqual(ButtonIcons.label("RT"), "RT")
        XCTAssertEqual(ButtonIcons.label("(LS)"), "LS")
        XCTAssertEqual(ButtonIcons.label("RI"), "R1")
        XCTAssertEqual(ButtonIcons.label("RI", family: .xbox), "RT")
        XCTAssertEqual(ButtonIcons.label("LB"), "L3")
        XCTAssertEqual(ButtonIcons.label("LB", family: .xbox), "LB")
        XCTAssertEqual(ButtonIcons.label("L8", family: .xbox), "LB")
        XCTAssertEqual(ButtonIcons.label("RB", leanXbox: true), "RB")
        XCTAssertEqual(ButtonIcons.label("L", stick: true), "L")
        XCTAssertNil(ButtonIcons.label("L", stick: true, family: .xbox))
        XCTAssertEqual(ButtonIcons.label("View", stick: true, family: .xbox), "⧉")
    }

    func testThreeBarsAreTheMenuButton() {
        XCTAssertEqual(ButtonIcons.classify(icon(white, mark: bars), layout: ButtonLayoutVote(.xbox)), "☰")
        // PlayStation'da Menu tuşu yoktur.
        XCTAssertNil(ButtonIcons.classify(icon(white, mark: bars), layout: ButtonLayoutVote(.playstation)))
    }

    func testVoteIsStickyAndForcedSettingWins() {
        let vote = ButtonLayoutVote()
        XCTAssertEqual(vote.current, .playstation)
        vote.add(.xbox)
        vote.add(.xbox)
        XCTAssertEqual(vote.current, .playstation)
        vote.add(.xbox)
        XCTAssertEqual(vote.current, .xbox)
        // Tek bir PlayStation simgesi kararı çevirmez.
        vote.add(.playstation)
        XCTAssertEqual(vote.current, .xbox)
        vote.reset()
        XCTAssertEqual(vote.current, .playstation)
        let forced = ButtonLayoutVote(.xbox)
        for _ in 0..<6 { forced.add(.playstation) }
        XCTAssertEqual(forced.current, .xbox)
        XCTAssertEqual(ButtonLayoutVote.resolve(.auto, xboxSource: true), .xbox)
        XCTAssertEqual(ButtonLayoutVote.resolve(.auto, xboxSource: false), .playstation)
        XCTAssertEqual(ButtonLayoutVote.resolve(.auto, xboxSource: nil), .auto)
        XCTAssertEqual(ButtonLayoutVote.resolve(.playstation, xboxSource: true), .playstation)
    }

    func testVoteIsClampedAndResetWhenTheSettingChanges() {
        let vote = ButtonLayoutVote()
        for _ in 0..<10 { vote.add(.xbox) }
        // Puan ±6 ile sınırlı: on Xbox simgesinden sonra üç PlayStation simgesi yetmez, dokuzu kararı çevirir.
        for _ in 0..<3 { vote.add(.playstation) }
        XCTAssertEqual(vote.current, .xbox)
        for _ in 0..<6 { vote.add(.playstation) }
        XCTAssertEqual(vote.current, .playstation)
        for _ in 0..<3 { vote.add(.xbox) }
        vote.setting = .auto
        XCTAssertEqual(vote.current, .playstation, "aynı seçim kararı sıfırlamaz")
        vote.setting = .xbox
        XCTAssertEqual(vote.current, .xbox)
        vote.setting = .auto
        XCTAssertTrue(vote.undecided)
        XCTAssertEqual(vote.current, .playstation)
    }

    func testXboxLettersAreTurnedIntoButtonsOnlyWhereAButtonBelongs() {
        let cases: [(String, String)] = [
            ("Press A to jump", "Press Ⓐ to jump"),
            ("Hold [X]", "Hold Ⓧ"),
            ("(B) Back", "Ⓑ Back"),
            ("A Talk", "Ⓐ Talk"),
            ("TALK A", "TALK Ⓐ"),
            ("Press A or B to dodge", "Press Ⓐ or Ⓑ to dodge"),
            ("PRESS Y TO CONTINUE", "PRESS Ⓨ TO CONTINUE"),
            ("Use A Medkit to heal.", "Use A Medkit to heal."),
            ("A new day begins.", "A new day begins."),
            ("Plan B is ready.", "Plan B is ready."),
            ("Hold (LT) to aim", "Hold LT to aim"),
        ]
        for (line, expected) in cases { XCTAssertEqual(IconWords.withXboxLetters(line, family: .xbox), expected, line) }
        XCTAssertEqual(IconWords.withXboxLetters("Press A to jump", family: .playstation), "Press A to jump")
    }

    func testPlainButtonNamesFeedTheAutoVote() {
        let vote = ButtonLayoutVote()
        for _ in 0..<3 { IconWords.observe("Hold LT to aim", vote: vote) }
        XCTAssertEqual(vote.current, .xbox)
        let other = ButtonLayoutVote()
        for _ in 0..<3 { IconWords.observe("We lost the LT signal, Captain.", vote: other) }
        XCTAssertEqual(other.current, .playstation)
    }

    func testXboxTokensArePromptsAndKeptInTranslation() {
        XCTAssertTrue(ButtonPrompts.isPrompt("Hold LT to aim"))
        XCTAssertTrue(ButtonPrompts.isPrompt("Ⓐ Talk"))
        XCTAssertTrue(ButtonPrompts.isPrompt("RB Next"))
        for token in ["Ⓐ", "Ⓨ", "LB", "RS", "⧉", "☰"] { XCTAssertTrue(ButtonPrompts.isIcon(token), token) }
        XCTAssertEqual(UiPrompts.keepIcons(source: "Hold LT to aim", translation: "Nişan almak için lt basılı tut"), "Nişan almak için LT basılı tut")
        XCTAssertEqual(UiPrompts.keepIcons(source: "Ⓐ Talk", translation: "Konuş"), "Ⓐ Konuş")
        XCTAssertEqual(UiPrompts.translate("Ⓐ TALK"), "Ⓐ Konuş")
        XCTAssertTrue(UiPrompts.isCommand("Pick up"))
        XCTAssertFalse(UiPrompts.isCommand("Medkit"))
        XCTAssertEqual(ButtonTokens.familyOf("RT"), .xbox)
        XCTAssertEqual(ButtonTokens.familyOf("△"), .playstation)
        XCTAssertNil(ButtonTokens.familyOf("↑"))
        XCTAssertEqual(IconWords.normalizedIcons("[LT] Aim"), "LT Aim")
        XCTAssertEqual(IconWords.normalizedIcons("Hold Ⓐ Ⓐ to climb"), "Hold Ⓐ to climb")
        XCTAssertFalse(IconWords.mayBeIcon("LT"))
    }

    func testButtonLayoutSettingIsStoredByName() {
        XCTAssertEqual(ButtonLayout.parse("playstation"), .playstation)
        XCTAssertEqual(ButtonLayout.parse("XBOX"), .xbox)
        XCTAssertEqual(ButtonLayout.parse("nintendo"), .auto)
        XCTAssertEqual(ButtonLayout.parse(nil), .auto)
        XCTAssertEqual(ButtonLayout.xbox.key, "xbox")
        for layout in ButtonLayout.allCases { XCTAssertEqual(ButtonLayout.parse(layout.key), layout) }
    }

    /// Uygulama ayarı ve kaynağı okuyucuya verir; kopyası (RegionWatcher'daki) aynı kararı kullanır.
    func testIconReaderTurnsXboxLettersIntoButtons() throws {
        let line = OcrLine("Press A to jump", box: TextBox(left: 0.1, top: 0.4, right: 0.6, bottom: 0.6))
        let reader = IconReader(recognize: { _ in [line] })
        let image = PixelImage(width: 200, height: 50)
        XCTAssertEqual(try reader.read(image).lines.map(\.text), ["Press A to jump"])
        reader.setButtonLayout(.auto, xboxSource: true)
        XCTAssertEqual(reader.layout.setting, .xbox)
        XCTAssertEqual(try reader.read(image).lines.map(\.text), ["Press Ⓐ to jump"])

        let watcher = RegionWatcher(reader: reader, translator: { $0 }, server: NullSender())
        watcher.setButtonLayout(.playstation, xboxSource: true)
        XCTAssertEqual(reader.layout.setting, .playstation)
        XCTAssertTrue(watcher.buttonLayout === reader.layout)
        XCTAssertEqual(try reader.read(image).lines.map(\.text), ["Press A to jump"])
    }

    func testPlainButtonNamesReadByTheReaderFeedTheVote() throws {
        let line = OcrLine("Hold LT to aim", box: TextBox(left: 0.1, top: 0.4, right: 0.6, bottom: 0.6))
        let reader = IconReader(recognize: { _ in [line] })
        let image = PixelImage(width: 200, height: 50)
        for _ in 0..<3 { _ = try reader.read(image) }
        XCTAssertEqual(reader.layout.current, .xbox)
    }

    private final class NullSender: RegionSender {
        func sendRegion(id: String, text: String, box: TextBox?, lineHeight: Double?) {}
        func clearRegions() {}
    }
}
