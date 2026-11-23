// SPDX-License-Identifier: AGPL-3.0-only

import AppKit
import CoreVideo
import GameTranslatorCore
import XCTest
@testable import GameTranslator

/// Mac'e özgü motor parçaları: dublaj cümleleri, ekran katmanının yerleşimi, kaynakların yardımcıları.
final class DubbingScriptTests: XCTestCase {
    func testSentencesPairWithSpeaker() {
        let lines = DubbingScript.lines(source: "KRATOS: We go now. Stay close.", translation: "KRATOS: Şimdi gidiyoruz. Yakın dur.")
        XCTAssertEqual(lines, [
            SpokenLine(speaker: "KRATOS", english: "We go now.", turkish: "Şimdi gidiyoruz."),
            SpokenLine(speaker: "KRATOS", english: "Stay close.", turkish: "Yakın dur."),
        ])
    }

    func testMismatchedSentencesBecomeOneLine() {
        let lines = DubbingScript.lines(source: "ATREUS: Wait. What is that?", translation: "ATREUS: Bekle, o ne?")
        XCTAssertEqual(lines, [SpokenLine(speaker: "ATREUS", english: "Wait. What is that?", turkish: "Bekle, o ne?")])
    }

    func testDialogueDashesStartNewTurns() {
        let lines = DubbingScript.lines(source: "- Let's go.\n- Wait!", translation: "- Gidelim.\n- Bekle!")
        XCTAssertEqual(lines.map(\.turkish), ["Gidelim.", "Bekle!"])
        XCTAssertTrue(lines.allSatisfy(\.newTurn))
    }

    func testNothingToSay() {
        XCTAssertEqual(DubbingScript.lines(source: "123", translation: "123"), [])
        XCTAssertEqual(DubbingScript.lines(source: "Hello.", translation: "..."), [])
    }
}

final class OverlayLayoutTests: XCTestCase {
    private let size = CGSize(width: 1000, height: 500)

    func testCoverCentersOnSubtitleAndIsAtLeastItsSize() {
        let rect = OverlayLayout.cover(TextBox(left: 0.3, top: 0.8, right: 0.7, bottom: 0.9), in: size)
        XCTAssertEqual(rect.midX, 500, accuracy: 0.001)
        XCTAssertEqual(rect.midY, 425, accuracy: 0.001)
        XCTAssertEqual(rect.width, 400, accuracy: 0.001)
        XCTAssertEqual(rect.height, 50, accuracy: 0.001)
        // Dar altyazıda çeviri sığsın diye kutu en az panelin %40'ı genişliğindedir, en çok %90'ı.
        XCTAssertEqual(OverlayLayout.cover(TextBox(left: 0.45, top: 0.8, right: 0.55, bottom: 0.9), in: size).width, 400, accuracy: 0.001)
        XCTAssertEqual(OverlayLayout.cover(TextBox(left: 0, top: 0.8, right: 1, bottom: 0.9), in: size).width, 900, accuracy: 0.001)
    }

    func testRegionFontFollowsLineHeight() {
        let region = OverlayRegion(id: "a", text: "Görev", box: TextBox(left: 0.1, top: 0.1, right: 0.3, bottom: 0.2), lineHeight: 0.05)
        let placed = OverlayLayout.region(region, in: size, maxFont: 34)
        XCTAssertEqual(placed.rect.minX, 100, accuracy: 0.001)
        XCTAssertEqual(placed.rect.minY, 50, accuracy: 0.001)
        XCTAssertEqual(placed.rect.width, 200, accuracy: 0.001)
        XCTAssertEqual(placed.rect.height, 50, accuracy: 0.001)
        XCTAssertEqual(placed.font, 20, accuracy: 0.001)
        XCTAssertEqual(OverlayLayout.region(region, in: CGSize(width: 1000, height: 5000), maxFont: 34).font, 34)
    }

    @MainActor
    func testOverlayShownOnlyWhileTranslatingAndEnabled() {
        let cover = CGRect(x: 10, y: 20, width: 800, height: 600)
        XCTAssertNil(OverlayController.frame(enabled: false, active: true, placement: .fixed, displayID: 0, coverFrame: nil))
        XCTAssertNil(OverlayController.frame(enabled: true, active: false, placement: .fixed, displayID: 0, coverFrame: nil))
        XCTAssertEqual(OverlayController.frame(enabled: true, active: true, placement: .cover, displayID: 0, coverFrame: cover), cover)
        // Sabit yerleşimde katman seçilen ekranı (yoksa ana ekranı) kaplar; pencerenin yeri kullanılmaz.
        if let main = NSScreen.main {
            XCTAssertEqual(OverlayController.frame(enabled: true, active: true, placement: .fixed, displayID: 0, coverFrame: cover), main.frame)
        }
    }

    @MainActor
    func testPanelStaysOutOfCaptureAndClicks() {
        let panel = SubtitleOverlayPanel()
        XCTAssertEqual(panel.sharingType, .none)
        XCTAssertTrue(panel.ignoresMouseEvents)
        XCTAssertTrue(panel.collectionBehavior.contains(.canJoinAllSpaces))
        XCTAssertTrue(panel.collectionBehavior.contains(.fullScreenAuxiliary))
        XCTAssertFalse(panel.canBecomeKey)
    }
}

final class FrameSourceHelperTests: XCTestCase {
    func testPixelBufferBecomesImage() throws {
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(nil, 64, 32, kCVPixelFormatType_32BGRA, nil, &buffer)
        let pixels = try XCTUnwrap(buffer)
        let image = try XCTUnwrap(PixelBufferImage.make(pixels))
        XCTAssertEqual(image.width, 64)
        XCTAssertEqual(image.height, 32)

        var other: CVPixelBuffer?
        CVPixelBufferCreate(nil, 8, 8, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange, nil, &other)
        XCTAssertNil(PixelBufferImage.make(try XCTUnwrap(other)))
    }

    func testCocoaRectFlipsY() {
        let mainHeight = CGDisplayBounds(CGMainDisplayID()).height
        let rect = ScreenCaptureSource.cocoaRect(CGRect(x: 100, y: 50, width: 400, height: 300))
        XCTAssertEqual(rect, CGRect(x: 100, y: mainHeight - 350, width: 400, height: 300))
    }
}
