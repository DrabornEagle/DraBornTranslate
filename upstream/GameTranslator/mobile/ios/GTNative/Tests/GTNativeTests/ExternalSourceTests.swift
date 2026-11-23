// SPDX-License-Identifier: AGPL-3.0-only

import CoreGraphics
import CoreText
import Foundation
import XCTest
import GameTranslatorCore
@testable import GTNative

/// Dış görüntü kaynağı (Mac: yakalama kartı, pencere, ekran): evreler konsoldakiyle aynı, kareler okuma hattına girer.
final class ExternalSourceTests: XCTestCase {
    private final class FakeSource: FrameSource, @unchecked Sendable {
        let label = "Deneme Kartı"
        private let lock = NSLock()
        private var _started = 0
        private var _stopped = 0
        var started: Int { lock.withLock { _started } }
        var stopped: Int { lock.withLock { _stopped } }
        func start(into engine: Engine) { lock.withLock { _started += 1 } }
        func stop() { lock.withLock { _stopped += 1 } }
    }

    private func makeEngine() -> Engine {
        let store = Store(defaults: UserDefaults(suiteName: "gt-external-\(UUID().uuidString)")!)
        var settings = Settings()
        settings.autoRegion = false
        settings.packsEnabled = false
        settings.sharePool = false
        store.settings = settings
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("gt-external-\(UUID().uuidString)")
        let engine = Engine(store: store, filesDirectory: folder)
        engine.bundledPacks = nil
        return engine
    }

    private func phase(_ engine: Engine) -> String { engine.state()["phase"] as? String ?? "" }

    @discardableResult
    private func waitUntil(_ timeout: TimeInterval = 10, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline { return false }
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        return true
    }

    /// Altta, ortada beyaz yazılı siyah 1280×720 kare (oyundaki altyazı gibi).
    private func frame(_ text: String) -> CGImage {
        let width = 1280, height = 720
        let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue)!
        context.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let font = CTFontCreateWithName("Helvetica-Bold" as CFString, 40, nil)
        let attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(red: 1, green: 1, blue: 1, alpha: 1),
        ]
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attributes))
        let bounds = CTLineGetBoundsWithOptions(line, [])
        // CoreGraphics'te y aşağıdan yukarı: yazı karenin altından %18 yukarıda (okunan alan %62–%97).
        context.textPosition = CGPoint(x: (Double(width) - bounds.width) / 2, y: Double(height) * 0.18)
        CTLineDraw(line, context)
        return context.makeImage()!
    }

    func testFramesFlowThroughPipeline() {
        let engine = makeEngine()
        let source = FakeSource()
        engine.start(source: source)
        XCTAssertTrue(waitUntil { self.phase(engine) == "connecting" && source.started == 1 })
        XCTAssertEqual(engine.state()["message"] as? String, "Deneme Kartı")
        XCTAssertEqual(engine.state()["running"] as? Bool, true)

        let image = frame("Hello there, my friend.")
        let read = waitUntil(20) {
            engine.submit(frame: image)
            return (engine.state()["lastRead"] as? String ?? "").contains("friend")
        }
        XCTAssertTrue(read, "okunan: \(engine.state()["lastRead"] ?? "")")
        XCTAssertEqual(phase(engine), "streaming")
        XCTAssertEqual(engine.state()["frameWidth"] as? Int, 1280)
        XCTAssertNotNil(engine.snapshot(maxWidth: 640))

        engine.stop()
        XCTAssertTrue(waitUntil { self.phase(engine) == "idle" })
        XCTAssertEqual(source.stopped, 1)
        // Durduktan sonra gelen kare yok sayılır.
        let frames = (engine.state()["frames"] as? NSNumber)?.int64Value
        engine.submit(frame: image)
        XCTAssertEqual((engine.state()["frames"] as? NSNumber)?.int64Value, frames)
        XCTAssertEqual(phase(engine), "idle")
    }

    func testSubmitWithoutSourceIsIgnored() {
        let engine = makeEngine()
        engine.submit(frame: frame("Hello"))
        XCTAssertEqual((engine.state()["frames"] as? NSNumber)?.int64Value, 0)
        XCTAssertEqual(phase(engine), "idle")
    }

    func testFailureStopsAndRetryReconnects() {
        let engine = makeEngine()
        let source = FakeSource()
        engine.start(source: source)
        XCTAssertTrue(waitUntil { self.phase(engine) == "connecting" })
        let image = frame("Hi")
        engine.submit(frame: image)
        XCTAssertEqual(phase(engine), "streaming")

        engine.sourceFailed("kart çıkarıldı", willRetry: true)
        XCTAssertEqual(phase(engine), "reconnecting")
        XCTAssertEqual(engine.state()["message"] as? String, "kart çıkarıldı")
        engine.submit(frame: image)
        XCTAssertEqual(phase(engine), "streaming")

        engine.sourceFailed("izin yok")
        XCTAssertTrue(waitUntil { self.phase(engine) == "failed" })
        XCTAssertEqual(engine.state()["message"] as? String, "izin yok")
        XCTAssertEqual(engine.state()["running"] as? Bool, false)
        XCTAssertEqual(source.stopped, 1)
        engine.stop()
        XCTAssertTrue(waitUntil { self.phase(engine) == "idle" })
    }

    func testSubtitleEventBoxRoundTrip() {
        let box = TextBox(left: 0.2, top: 0.8, right: 0.8, bottom: 0.9)
        let event: [String: Any] = ["type": "subtitle", "box": box.eventMap]
        XCTAssertEqual(TextBox.fromEvent(event["box"]), box)
        XCTAssertNil(TextBox.fromEvent(nil))
        XCTAssertNil(TextBox.fromEvent(["left": 0.1]))
    }
}
