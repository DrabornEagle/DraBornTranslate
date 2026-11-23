// SPDX-License-Identifier: AGPL-3.0-only

@testable import GameTranslatorCore
import XCTest

/// Kumanda simgesi görüntüden tanınır (Windows: ButtonIconsTests, Android: ButtonIconsTest; aynı yapay simgeler).
final class ButtonIconsTests: XCTestCase {
    private let size = 96

    /// Koyu zemin üstünde beyaz (ya da renkli) daire; içine `mark` koyu çizilir (PlayStation simgeleri böyle).
    private func icon(_ disc: (Int, Int, Int) = (235, 235, 235), mark: (Double, Double) -> Bool) -> PixelImage {
        let image = PixelImage(width: size * 2, height: size)
        for y in 0..<size {
            for x in 0..<(size * 2) {
                let dx = Double(x - size), dy = Double(y) - Double(size) / 2
                let r = Double(size) * 0.32
                var color = (20, 20, 24)
                if dx * dx + dy * dy <= r * r {
                    color = disc
                    // İşaret, dairenin içinde -1..1 koordinatıyla çizilir.
                    if mark(dx / r, dy / r) { color = (30, 30, 30) }
                }
                image.set(x, y, color.0, color.1, color.2)
            }
        }
        return image
    }

    private let stroke = 0.13

    private func cross(_ x: Double, _ y: Double) -> Bool {
        abs(x) < 0.5 && abs(y) < 0.5 && (abs(x - y) < stroke * 1.4 || abs(x + y) < stroke * 1.4)
    }

    private func ring(_ x: Double, _ y: Double) -> Bool {
        let d = (x * x + y * y).squareRoot()
        return d > 0.38 && d < 0.55
    }

    private func square(_ x: Double, _ y: Double) -> Bool {
        let m = max(abs(x), abs(y))
        return m > 0.36 && m < 0.5
    }

    private func triangle(_ x: Double, _ y: Double) -> Bool {
        // Tepe üstte (y = -0.45), taban altta (y = 0.4).
        if y < -0.47 || y > 0.42 { return false }
        let half = (y + 0.45) / 0.85 * 0.5
        return (y > 0.28 && abs(x) <= half) || abs(abs(x) - half) < stroke * 1.3
    }

    func testFaceButtonsAreRecognizedFromTheirSymbol() {
        XCTAssertEqual(ButtonIcons.classify(icon(mark: cross)), "✕")
        XCTAssertEqual(ButtonIcons.classify(icon(mark: ring)), "○")
        XCTAssertEqual(ButtonIcons.classify(icon(mark: square)), "□")
        XCTAssertEqual(ButtonIcons.classify(icon(mark: triangle)), "△")
    }

    func testColouredDiscIsAMapMarkerNotAButton() {
        let yellow = icon((230, 190, 20)) { _, _ in false }
        XCTAssertEqual(ButtonIcons.classify(yellow), "◆")
        XCTAssertEqual(ButtonIcons.classify(yellow, readText: { _ in "B" }), "◆")
    }

    /// PlayStation'ın renkli yüz tuşları rengiyle tanınır (GTA 6, God of War): yeşil △, mavi ✕, pembe □, kırmızı ○.
    func testColouredFaceButtonsAreRecognizedByHue() {
        XCTAssertEqual(ButtonIcons.classify(icon((40, 200, 120)) { _, _ in false }), "△")
        XCTAssertEqual(ButtonIcons.classify(icon((60, 120, 230)) { _, _ in false }), "✕")
        XCTAssertEqual(ButtonIcons.classify(icon((230, 80, 200)) { _, _ in false }), "□")
        XCTAssertEqual(ButtonIcons.classify(icon((230, 40, 40)) { _, _ in false }), "○")
        XCTAssertNil(ButtonIcons.faceButtonByHue(50))
    }

    func testShoulderButtonIsReadFromItsLabel() {
        // Yazılı kutu (L2): yazı okuyucusu "L2" okur; okunamazsa daire/kare sembol sanılmaz.
        let image = PixelImage(width: size * 2, height: size)
        for y in 0..<size {
            for x in 0..<(size * 2) {
                let v = abs(x - size) < 26 && abs(y - size / 2) < 16 ? 230 : 20
                image.set(x, y, v, v, v)
            }
        }
        XCTAssertEqual(ButtonIcons.classify(image, readText: { _ in "L2" }), "L2")
        XCTAssertEqual(ButtonIcons.classify(image, readText: { _ in "(LI)" }), "L1")
        XCTAssertNil(ButtonIcons.classify(image, readText: { _ in nil }))
    }

    func testPlainBackgroundIsNotAnIcon() {
        let image = PixelImage(width: size * 2, height: size)
        image.fill(0xFF28_2828)
        XCTAssertNil(ButtonIcons.classify(image))
    }

    func testLabelsAreNormalized() {
        XCTAssertEqual(ButtonIcons.label("L2"), "L2")
        XCTAssertEqual(ButtonIcons.label("(RI)"), "R1")
        XCTAssertEqual(ButtonIcons.label("DL2T"), "L2")
        XCTAssertEqual(ButtonIcons.label("OPTIONS"), "OPTIONS")
        XCTAssertNil(ButtonIcons.label("Hello"))
        XCTAssertEqual(ButtonIcons.label("L", stick: true), "L")
        XCTAssertNil(ButtonIcons.label("L"))
    }

    /// Bir karede sözcükler arasındaki simge, karedeki yerinden kesilip tanınır.
    func testIconIsFoundAtItsPlaceInTheFrame() throws {
        let frame = PixelImage(width: 400, height: 200)
        frame.fill(0xFF14_1418)
        let disc = icon(mark: cross)
        // Simge karenin ortasına (100..292, 52..148) yapıştırılır.
        for y in 0..<disc.height { for x in 0..<disc.width { frame.pixels[(52 + y) * 400 + 100 + x] = disc.pixels[y * disc.width + x] } }
        let box = TextBox(left: 170.0 / 400, top: 76.0 / 200, right: 230.0 / 400, bottom: 124.0 / 200)
        let crop = try XCTUnwrap(ButtonIcons.cropAround(frame, box))
        XCTAssertTrue(crop.width >= 60 && crop.height >= 48)
        XCTAssertEqual(ButtonIcons.classify(frame, box: box), "✕")
    }

    func testPaddedImageIsTallEnoughToRead() {
        let padded = ButtonIcons.padded(PixelImage(width: 20, height: 12))
        // Zemin 4 kat geniş, 2 kat yüksek (80x24); 140 piksele varmak için 6 kat büyütülür.
        XCTAssertEqual(padded.height, 144)
        XCTAssertEqual(padded.width, 480)
    }
}

/// Görüntü kesitleri, izler ve bayt aktarımı (Android: PixelImage).
final class PixelImageTests: XCTestCase {
    func testBytesCropAreaAndSignature() throws {
        // 4x2 RGBA: sol yarı kırmızı, sağ yarı mavi; satır sonunda 4 bayt dolgu.
        var rgba: [UInt8] = []
        for _ in 0..<2 {
            for x in 0..<4 { rgba += x < 2 ? [255, 0, 0, 255] : [0, 0, 255, 255] }
            rgba += [0, 0, 0, 0]
        }
        let image = PixelImage(width: 4, height: 2, rgba: rgba, bytesPerRow: 20)
        XCTAssertEqual(image.red(0, 0), 255)
        XCTAssertEqual(image.blue(3, 1), 255)
        let bgra = PixelImage(width: 1, height: 1, bgra: [10, 20, 30, 255])
        XCTAssertEqual([bgra.red(0, 0), bgra.green(0, 0), bgra.blue(0, 0)], [30, 20, 10])

        let right = try XCTUnwrap(image.area(TextBox(left: 0.5, top: 0, right: 1, bottom: 1)))
        XCTAssertEqual(right.width, 2)
        XCTAssertEqual(right.blue(0, 0), 255)
        XCTAssertEqual(image.crop(3, 1, 10, 10).width, 1)

        let a = PixelImage.signature(image)
        XCTAssertEqual(a.count, PixelImage.signatureWidth * PixelImage.signatureHeight)
        XCTAssertFalse(PixelImage.changed(a, PixelImage.signature(image)))
        let darker = PixelImage(width: 4, height: 2, pixels: image.pixels.map { $0 & 0xFF00_0000 })
        XCTAssertTrue(PixelImage.changed(a, PixelImage.signature(darker)))
        XCTAssertEqual(image.scaled(2).width, 8)
    }
}

/// Satırın içindeki simge boşluğu ve yanlış okunan simge görüntüden tanınan düğmeyle değiştirilir (Windows:
/// IconWordsTests, Android: IconWordsTest).
final class IconWordsTests: XCTestCase {
    private let width = 1920.0
    private let height = 1080.0
    private let top = 0.80
    private let bottom = 0.83

    private func words(_ items: (String, Double, Double)...) -> [OcrWord] {
        items.map { OcrWord($0.0, box: TextBox(left: $0.1, top: top, right: $0.2, bottom: bottom)) }
    }

    private let never: (TextBox) -> String? = { _ in
        XCTFail("sorulmamalıydı")
        return nil
    }

    func testIconGapBetweenWordsGetsTheButton() {
        // "Hold" ile "to" arasında simge genişliğinde boşluk (satır ~32 px, boşluk ~58 px).
        var asked: [TextBox] = []
        let text = IconWords.withIcons("Hold to climb", words: words(("Hold", 0.40, 0.43), ("to", 0.46, 0.47), ("climb", 0.475, 0.50)),
                                       width: width, height: height) { box in
            asked.append(box)
            return "✕"
        }
        XCTAssertEqual(text, "Hold ✕ to climb")
        XCTAssertEqual(asked.count, 1)
        XCTAssertEqual(asked[0].left, 0.43, accuracy: 0.001)
        XCTAssertEqual(asked[0].right, 0.46, accuracy: 0.001)
    }

    func testOrdinaryWordSpacingIsLeftAlone() {
        let line = words(("Get", 0.40, 0.42), ("in", 0.425, 0.435), ("the", 0.44, 0.455), ("car.", 0.46, 0.48))
        XCTAssertEqual(IconWords.withIcons("Get in the car.", words: line, width: width, height: height, classify: never), "Get in the car.")
    }

    func testMisreadIconIsReplacedAndUnknownPictureIsDropped() {
        let line = words(("Press", 0.40, 0.43), ("•", 0.432, 0.44), ("to", 0.442, 0.45), ("jump", 0.452, 0.47))
        XCTAssertEqual(IconWords.withIcons("Press • to jump", words: line, width: width, height: height) { _ in "□" }, "Press □ to jump")
        XCTAssertEqual(IconWords.withIcons("Press • to jump", words: line, width: width, height: height) { _ in nil }, "Press to jump")
    }

    func testLetterAfterVerbIsCheckedButKeptWhenNoIconIsThere() {
        let line = words(("Hold", 0.40, 0.43), ("A", 0.432, 0.44), ("to", 0.442, 0.45), ("aim", 0.452, 0.47))
        XCTAssertEqual(IconWords.withIcons("Hold A to aim", words: line, width: width, height: height) { _ in "△" }, "Hold △ to aim")
        XCTAssertEqual(IconWords.withIcons("Hold A to aim", words: line, width: width, height: height) { _ in nil }, "Hold A to aim")
    }

    func testEdgeIconIsAddedOnceAndDuplicatesCollapse() {
        let line = TextBox(left: 0.45, top: top, right: 0.55, bottom: bottom)
        XCTAssertEqual(IconWords.withEdgeIcons("Pick up", line: line, width: width, height: height) { $0.right <= line.left ? "□" : nil }, "□ Pick up")
        XCTAssertEqual(IconWords.withEdgeIcons("✕ Open", line: line, width: width, height: height) { $0.right <= line.left ? "✕" : nil }, "✕ Open")
        XCTAssertEqual(IconWords.normalizedIcons("Hold ✕ ✕ to climb"), "Hold ✕ to climb")
        XCTAssertEqual(IconWords.normalizedIcons("(l2) Aim"), "L2 Aim")
        XCTAssertEqual(IconWords.normalizedIcons("(R1) Pull"), "R1 Pull")
        XCTAssertEqual(IconWords.normalizedIcons("(C1 Aim"), "L1 Aim")
        XCTAssertEqual(IconWords.normalizedIcons("R2 R2 Fire"), "R2 Fire")
    }

    /// Cümlenin içindeki harita işareti ("your stash ◆ or by…") tuş sayılmaz; fiilden sonraki simge tuştur.
    func testMapMarkerInsideASentenceIsNotAButton() {
        let line = words(("your", 0.40, 0.42), ("stash", 0.425, 0.44), ("or", 0.47, 0.48), ("by", 0.485, 0.49))
        XCTAssertEqual(IconWords.withIcons("your stash or by", words: line, width: width, height: height) { _ in "△" }, "your stash or by")
        let press = words(("Press", 0.40, 0.43), ("to", 0.46, 0.47), ("jump", 0.475, 0.49))
        XCTAssertEqual(IconWords.withIcons("Press to jump", words: press, width: width, height: height) { _ in "✕" }, "Press ✕ to jump")
    }

    /// Renkli adın önündeki bozuk parçalar birbirini tuş diye onaylamaz ("Talk to □ ○ Brian").
    func testJunkBeforeAColouredNameIsNotAChainOfButtons() {
        let line = words(("Talk", 0.40, 0.42), ("to", 0.425, 0.435), ("•", 0.44, 0.445), ("O", 0.45, 0.455), ("Brian", 0.46, 0.48))
        XCTAssertEqual(IconWords.withIcons("Talk to • O Brian", words: line, width: width, height: height) { _ in "□" }, "Talk to Brian")
    }

    func testRightEdgeIconIsOnlyLookedForAfterAVerbOrInAButtonBar() {
        let line = TextBox(left: 0.45, top: top, right: 0.50, bottom: bottom)
        XCTAssertEqual(IconWords.withEdgeIcons("Find", line: line, width: width, height: height) { $0.left >= line.right ? "□" : nil }, "Find")
        XCTAssertEqual(IconWords.withEdgeIcons("SELECT", line: line, width: width, height: height) { $0.left >= line.right ? "✕" : nil }, "SELECT ✕")
        XCTAssertEqual(IconWords.withEdgeIcons("Press", line: line, width: width, height: height) { $0.left >= line.right ? "✕" : nil }, "Press ✕")
    }

    func testJunkAtTheEndOfASentenceIsNotAButton() {
        XCTAssertEqual(IconWords.withIcons("Follow •", words: words(("Follow", 0.40, 0.43), ("•", 0.46, 0.47)), width: width, height: height) { _ in "□" }, "Follow")
        XCTAssertEqual(IconWords.withIcons("Press •", words: words(("Press", 0.40, 0.43), ("•", 0.46, 0.47)), width: width, height: height) { _ in "□" }, "Press □")
    }

    func testLowercaseLetterInsideAnUppercaseBarIsChecked() {
        let bar = words(("OFFER", 0.40, 0.43), ("RIDE", 0.432, 0.45), ("e", 0.452, 0.455), ("LUCIA", 0.457, 0.48))
        XCTAssertEqual(IconWords.withIcons("OFFER RIDE e LUCIA", words: bar, width: width, height: height) { _ in "✕" }, "OFFER RIDE ✕ LUCIA")
        let sentence = words(("Get", 0.40, 0.42), ("in", 0.425, 0.43), ("the", 0.435, 0.44), ("e", 0.445, 0.45), ("car", 0.455, 0.47))
        XCTAssertEqual(IconWords.withIcons("Get in the e car", words: sentence, width: width, height: height, classify: never), "Get in the e car")
    }

    func testSuspiciousTokens() {
        for word in ["•", "(x)", "LZ", "B"] { XCTAssertTrue(IconWords.mayBeIcon(word), word) }
        for word in ["A", "I", "car", "&", "+"] { XCTAssertFalse(IconWords.mayBeIcon(word), word) }
    }

    /// Android'de `(?U)` bayrağı sınıf yüklenirken hata veriyordu; burada desenler NSRegularExpression'da derlenir ve
    /// kaynakta o bayrak kullanılmaz.
    func testSourcesDoNotUseFlagsMobileRegexRejects() throws {
        let sources = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources")
        let enumerator = try XCTUnwrap(FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil))
        var offenders: [String] = []
        for case let url as URL in enumerator where url.pathExtension == "swift" {
            let text = try String(contentsOf: url, encoding: .utf8)
            if text.contains("(?U)") { offenders.append(url.lastPathComponent) }
        }
        XCTAssertEqual(offenders, [])
    }
}

/// Oyunun renkli yazdığı sözcük çeviride aynı renkte gösterilir; küçük yazıda "m" okuma hatası düzeltilir (Windows:
/// ColoredWordsTests).
final class ColoredWordsTests: XCTestCase {
    func testTranslatedPhraseIsColoredInTheSentence() {
        for (translation, phrase, expected) in [
            ("Uçağa bin", "uçak", "⁅#ff8dab|Uçağa⁆ bin"),
            ("Teknenin yakınına in", "tekne", "⁅#ff8dab|Teknenin⁆ yakınına in"),
            ("Brian'ı takip et", "Brian", "⁅#ff8dab|Brian'ı⁆ takip et"),
            ("Deniz uçağına bin", "deniz uçağı", "⁅#ff8dab|Deniz uçağına⁆ bin"),
            ("Arabaya bin", "uçak", "Arabaya bin"),
        ] {
            XCTAssertEqual(ColoredWords.mark(translation, parts: [(phrase, "#ff8dab")]), expected, translation)
        }
    }

    func testPlainRemovesTheMarks() {
        XCTAssertEqual(ColoredWords.plain("⁅#ff8dab|Deniz uçağına⁆ bin"), "Deniz uçağına bin")
        XCTAssertEqual(ColoredWords.plain("Düz yazı"), "Düz yazı")
    }

    func testColouredWordIsFoundAndWhiteWordIsNot() throws {
        // Koyu zemin üstünde iki sözcük: soldaki beyaz, sağdaki pembe yazı.
        let frame = PixelImage(width: 200, height: 40)
        for y in 0..<40 {
            for x in 0..<200 {
                let stroke = y > 12 && y < 28 && x % 6 < 3
                let color = !stroke ? (20, 20, 24) : x < 100 ? (240, 240, 240) : (255, 140, 170)
                frame.set(x, y, color.0, color.1, color.2)
            }
        }
        let phrases = ColoredWords.phrases([OcrWord("Get", box: TextBox(left: 0.05, top: 0.2, right: 0.45, bottom: 0.8)),
                                            OcrWord("plane", box: TextBox(left: 0.55, top: 0.2, right: 0.95, bottom: 0.8))], image: frame)
        XCTAssertEqual(phrases.count, 1)
        let phrase = try XCTUnwrap(phrases.first)
        XCTAssertEqual(phrase.text, "plane")
        XCTAssertTrue(phrase.color.hasPrefix("#ff"), phrase.color)
    }

    func testMisreadLetterMIsRepaired() {
        let words: Set<String> = ["collect", "cash", "from", "completed", "jobs", "your", "mailbox", "pick", "up", "a", "longarm",
                                  "working", "for", "brian", "will", "require", "some", "gear", "turn", "on", "the", "corner"]
        for (read, expected) in [
            ("Collect cash fronn conopleted jobs", "Collect cash from completed jobs"),
            ("frorn your noailbox", "from your mailbox"),
            ("Pick up a longarrn", "Pick up a longarm"),
            ("Working for Brian will require sonne gear", "Working for Brian will require some gear"),
            ("Turn on the corner", "Turn on the corner"),
        ] {
            XCTAssertEqual(LetterM.fix(read, isWord: words.contains), expected, read)
        }
        XCTAssertEqual(LetterM.fix("frorn", isWord: nil), "frorn")
    }
}
