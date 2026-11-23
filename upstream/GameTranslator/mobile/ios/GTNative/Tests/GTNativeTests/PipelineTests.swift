// SPDX-License-Identifier: AGPL-3.0-only

import CoreGraphics
import XCTest
import GameTranslatorCore
@testable import GTNative

private final class RecordingSink: SubtitleSink, @unchecked Sendable {
    let lock = NSLock()
    var shown: [(text: String, replaces: Bool)] = []
    var clears = 0
    func show(_ text: String, durationMs: Int, box: TextBox?, replaces: Bool) { lock.withLock { shown.append((text, replaces)) } }
    func clear() { lock.withLock { clears += 1 } }
}

final class PipelineTests: XCTestCase {
    private func wait(_ sink: RecordingSink, count: Int) {
        let deadline = Date().addingTimeInterval(3)
        while sink.lock.withLock({ sink.shown.count }) < count && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.01)) }
    }

    func testStableLineIsTranslatedOnceAndCleared() {
        let sink = RecordingSink()
        let pipeline = SubtitlePipeline(translator: ClosureTranslator { _ in "Gün doğmadan ayrılmalıyız." }, sink: sink)
        var pairs: [String] = []
        pipeline.onPair = { source, _ in pairs.append(source) }
        for _ in 0..<4 { pipeline.onRead(["We have to leave before sunrise."], box: nil) }
        wait(sink, count: 1)
        XCTAssertEqual(sink.shown.map(\.text), ["Gün doğmadan ayrılmalıyız."])
        for _ in 0..<4 { pipeline.onRead([], box: nil) }
        XCTAssertGreaterThanOrEqual(sink.clears, 1)
        XCTAssertEqual(pairs.count, 1)
    }

    func testPackHitSkipsEngine() {
        let sink = RecordingSink()
        let pipeline = SubtitlePipeline(translator: ClosureTranslator { _ in XCTFail("motora gitmemeli"); return nil }, sink: sink)
        pipeline.packLookup = { _ in PackMatch("Paketten", packId: "gta-vi") }
        var hits = 0
        pipeline.onPackHit = { _, _ in hits += 1 }
        for _ in 0..<4 { pipeline.onRead(["Hello there, friend."], box: nil) }
        wait(sink, count: 1)
        XCTAssertEqual(sink.shown.first?.text, "Paketten")
        XCTAssertEqual(hits, 1)
    }

    func testGrowingLineReplacesPrevious() {
        let sink = RecordingSink()
        // Çeviri kaynağına çok benzerse metin zaten hedef dilde sayılır; sahte çeviri belirgin biçimde farklı olmalı.
        let pipeline = SubtitlePipeline(translator: ClosureTranslator { "Çeviri " + String($0.reversed()) }, sink: sink)
        for _ in 0..<4 { pipeline.onRead(["We do not have"], box: nil) }
        wait(sink, count: 1)
        for _ in 0..<4 { pipeline.onRead(["We do not have time to waste."], box: nil) }
        wait(sink, count: 2)
        XCTAssertEqual(sink.shown.count, 2)
        XCTAssertTrue(sink.shown.count == 2 && sink.shown[1].replaces)
    }
}

final class FramesTests: XCTestCase {
    func testPixelRoundTripKeepsColors() throws {
        let source = PixelImage(width: 20, height: 18)
        for y in 0..<18 { for x in 0..<20 { source.set(x, y, x < 10 ? 250 : 10, 120, y < 9 ? 30 : 200) } }
        let image = try XCTUnwrap(Frames.cgImage(source))
        let back = try XCTUnwrap(Frames.pixels(image, region: TextBox(left: 0, top: 0, right: 1, bottom: 1)))
        XCTAssertEqual(back.width, 20)
        XCTAssertEqual(back.red(2, 2), 250, accuracy: 2)
        XCTAssertEqual(back.blue(2, 15), 200, accuracy: 2)
        XCTAssertEqual(back.red(15, 2), 10, accuracy: 2)
        XCTAssertNil(Frames.pixels(image, region: TextBox(left: 0, top: 0, right: 0.5, bottom: 0.5)))
    }

    func testJpegAndCrop() throws {
        let source = PixelImage(width: 64, height: 32)
        let image = try XCTUnwrap(Frames.cgImage(source))
        XCTAssertNotNil(Frames.jpeg(image, maxWidth: 32))
        XCTAssertEqual(Frames.crop(image, to: TextBox(left: 0.5, top: 0.5, right: 1, bottom: 1))?.width, 32)
        XCTAssertEqual(Frames.signature(of: image).count, 64 * 12)
    }
}

final class EngineRulesTests: XCTestCase {
    func testGta6Names() {
        XCTAssertTrue(Engine.isGta6(name: "Grand Theft Auto VI"))
        XCTAssertTrue(Engine.isGta6(name: "GTA 6"))
        XCTAssertFalse(Engine.isGta6(name: "Grand Theft Auto V"))
        XCTAssertFalse(Engine.isGta6(name: ""))
    }

    func testObjectiveLineIsInsideExclusionZone() {
        let subtitle = TextLine("Cal: I've been sitting here all day.", box: TextBox(left: 0.3, top: 0.86, right: 0.7, bottom: 0.9))
        let objective = TextLine("Drive the boat", box: TextBox(left: 0.44, top: 0.94, right: 0.56, bottom: 0.97))
        let zone = [WatchedArea(id: "objective", name: "Görev", region: ExtraAreaPreset.bottomObjective.region)]
        XCTAssertEqual(ExtraAreas.withoutWatchedAreas([subtitle, objective], zone).map(\.text), [subtitle.text])
    }
}

final class DisplayRelayTests: XCTestCase {
    func testServerAddressParse() {
        XCTAssertEqual(ServerAddress.parse("192.168.1.5"), ServerAddress(host: "192.168.1.5"))
        XCTAssertEqual(ServerAddress.parse(" http://10.0.0.2:9000/ekran "), ServerAddress(host: "10.0.0.2", port: 9000))
        XCTAssertNil(ServerAddress.parse("8.8.8.8"))
        XCTAssertNil(ServerAddress.parse("192.168.1.5:0"))
        XCTAssertNil(ServerAddress.parse("example.com"))
    }

    func testChoosePrefersActive() {
        let last = ServerAddress(host: "192.168.1.5")
        XCTAssertEqual(ServerAddress.choose(last: last, probe: { _ in true }, scan: { nil })?.0, last)
        let scanned = ServerAddress.choose(last: last, probe: { _ in false }, scan: { OtherServers.Found(host: "192.168.1.9", active: true) })
        XCTAssertEqual(scanned?.0.host, "192.168.1.9")
        XCTAssertEqual(ServerAddress.choose(last: last, probe: { _ in false }, scan: { nil })?.1, false)
        XCTAssertNil(ServerAddress.choose(last: nil, probe: { _ in nil }, scan: { nil }))
    }

    func testRelayForwardsBytes() throws {
        // Hedef: yalnız tek satır yanıt veren yerel sunucu.
        let server = try NWListenerEcho()
        let relay = DisplayRelay(target: ServerAddress(host: "127.0.0.1", port: server.port))
        // 127.0.0.1 yerel ağ adresi sayılmadığı için ServerAddress.parse değil doğrudan kurulur.
        let local = try relay.start()
        defer { relay.stop(); server.stop() }
        let reply = try fetch(port: local, send: "ping\n")
        XCTAssertEqual(reply, "pong:ping\n")
    }

    private func fetch(port: Int, send: String) throws -> String {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        defer { close(fd) }
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = UInt16(port).bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let connected = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        XCTAssertEqual(connected, 0)
        _ = send.withCString { write(fd, $0, strlen($0)) }
        var wait = timeval(tv_sec: 3, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &wait, socklen_t(MemoryLayout<timeval>.size))
        var buffer = [UInt8](repeating: 0, count: 256)
        var text = ""
        while !text.hasSuffix("\n") {
            let count = read(fd, &buffer, buffer.count)
            if count <= 0 { break }
            text += String(decoding: buffer[0..<count], as: UTF8.self)
        }
        return text
    }
}

import Network

/// Denemeler için: gelen satırı "pong:" önekiyle geri yollayan sunucu.
final class NWListenerEcho: @unchecked Sendable {
    let listener: NWListener
    let port: Int
    private let queue = DispatchQueue(label: "echo")

    init() throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { if case .ready = $0 { ready.signal() } }
        listener.newConnectionHandler = { [queue] connection in
            connection.start(queue: queue)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { data, _, _, _ in
                let text = data.map { String(decoding: $0, as: UTF8.self) } ?? ""
                connection.send(content: Data("pong:\(text)".utf8), completion: .contentProcessed { _ in })
            }
        }
        listener.start(queue: queue)
        _ = ready.wait(timeout: .now() + 3)
        port = Int(listener.port?.rawValue ?? 0)
    }

    func stop() { listener.cancel() }
}

final class PoolActivityTests: XCTestCase {
    func testParseAndText() {
        let activity = PoolActivity.parse(Data(#"{"now":79,"today":329}"#.utf8))
        XCTAssertEqual(activity, PoolActivity(now: 79, today: 329))
        XCTAssertEqual(activity?.text(turkish: true), "Şu an 79 kişi GameTranslator ile oynuyor · son 24 saatte 329")
        XCTAssertEqual(PoolActivity(now: 10, today: 60).text(turkish: false), "60 people played with GameTranslator in the last 24 hours")
        XCTAssertNil(PoolActivity(now: 3, today: 20).text(turkish: true))
        XCTAssertNil(PoolActivity.parse(Data(#"{"now":-1,"today":2}"#.utf8)))
        XCTAssertEqual(PoolActivity.address(lines: "https://x.dev/v1/lines")?.absoluteString, "https://x.dev/v1/active")
    }
}
