// SPDX-License-Identifier: AGPL-3.0-only
// Android'deki XboxTest.kt (TeredoTest, XboxChannelsTest, XboxSessionMachineTest) ve XboxStreamFixesTest.kt'nin
// aynı örnekleriyle; ek olarak kare onayı çözücü sarmalayıcısı.

import XCTest
import WebRTC
@testable import GTNative

final class TeredoTests: XCTestCase {
    func testDecodesClientAddressAndPort() throws {
        // RFC 4380 örneği (Wikipedia): istemci 192.0.2.45, bağlantı noktası 40000.
        let info = try XCTUnwrap(Teredo.inspect("2001:0000:4136:e378:8000:63bf:3fff:fdd2"))
        XCTAssertEqual(info.client4, "192.0.2.45")
        XCTAssertEqual(info.udpPort, 40000)
        XCTAssertEqual(Teredo.inspect("2001:0:4136:e378:8000:63bf:3fff:fdd2")?.client4, "192.0.2.45")
        XCTAssertEqual(Teredo.inspect("[2001:0:4136:e378:8000:63bf:3fff:fdd2%en0]")?.udpPort, 40000)
    }

    func testParsesCompressedAndRejectsInvalid() {
        XCTAssertEqual(Teredo.parseIpv6("2001::1"), [0x2001, 0, 0, 0, 0, 0, 0, 1])
        XCTAssertEqual(Teredo.parseIpv6("::"), [0, 0, 0, 0, 0, 0, 0, 0])
        XCTAssertNil(Teredo.parseIpv6("192.168.1.1"))
        XCTAssertNil(Teredo.parseIpv6("2001:::1"))
        XCTAssertNil(Teredo.parseIpv6("2001:0:1"))
        XCTAssertNil(Teredo.parseIpv6("2001:zzzz::1"))
        XCTAssertNil(Teredo.parseIpv6("2001:+1::1"))
        XCTAssertNil(Teredo.parseIpv6("1:2:3:4:5:6:7:8::"))
    }

    func testNonTeredoCandidatesAreKept() {
        let plain = XboxIceCandidate(candidate: "a=candidate:2 1 UDP 1 192.168.1.20 9002 typ host ", sdpMid: "0", sdpMLineIndex: 0)
        XCTAssertEqual(XboxHome.expandCandidates([plain]), [plain])
    }
}

final class XboxChannelsTests: XCTestCase {
    func u16(_ data: Data, _ at: Int) -> UInt16 { UInt16(data[at]) | UInt16(data[at + 1]) << 8 }
    func u32(_ data: Data, _ at: Int) -> UInt32 { (0..<4).reduce(0) { $0 | UInt32(data[at + $1]) << (8 * UInt32($1)) } }
    func f64(_ data: Data, _ at: Int) -> Double {
        Double(bitPattern: (0..<8).reduce(UInt64(0)) { $0 | UInt64(data[at + $1]) << (8 * UInt64($1)) })
    }

    func testClientMetadataPacketIsFifteenBytes() {
        let packet = XboxChannels.clientMetadataPacket(sequence: 0, timestampMs: 1234.5)
        XCTAssertEqual(packet.count, 15)
        XCTAssertEqual(u16(packet, 0), 8)
        XCTAssertEqual(u32(packet, 2), 0)
        XCTAssertEqual(f64(packet, 6), 1234.5)
        XCTAssertEqual(packet[14], 2)
    }

    func testFrameAckPacketHasThePlayersLayout() {
        let packet = XboxChannels.frameAckPacket(sequence: 7, timestampMs: 1234.0, frames: [
            XboxFrameAck(serverDataKey: 90_000, arrivalMs: 10, decodedMs: 25), XboxFrameAck(serverDataKey: 93_000, arrivalMs: 43, decodedMs: 58)])
        XCTAssertEqual(packet.count, 14 + 1 + 28 * 2)
        XCTAssertEqual(u16(packet, 0), 1)          // rapor türü: Metadata
        XCTAssertEqual(u32(packet, 2), 7)          // sıra no
        XCTAssertEqual(f64(packet, 6), 1234.0)
        XCTAssertEqual(packet[14], 2)              // kare sayısı
        let fields = (0..<14).map { u32(packet, 15 + 4 * $0) }
        XCTAssertEqual(Array(fields[0..<7]), [90_000, 10, 10, 25, 25, 1234, 1234])
        XCTAssertEqual(fields[7], 93_000)
        // Alt 32 bit yazılır (JavaScript setUint32 gibi).
        let wrapped = XboxChannels.frameAckPacket(sequence: 1, timestampMs: 0, frames: [XboxFrameAck(serverDataKey: 0x1_0000_0005, arrivalMs: -1, decodedMs: 0)])
        XCTAssertEqual(u32(wrapped, 15), 5)
        XCTAssertEqual(u32(wrapped, 19), 0xFFFF_FFFF)
    }

    func testConfigurationMessagesMatchPlayer() throws {
        let messages = try XboxChannels.configurationMessages().map { try object($0) }
        XCTAssertEqual(messages.count, 6)
        XCTAssertEqual(messages[0]["target"] as? String, "/streaming/systemUi/configuration")
        XCTAssertEqual((try object(messages[0]["content"] as? String)["systemUis"] as? [Any])?.count, 0)
        XCTAssertEqual((try object(messages[0]["content"] as? String)["version"] as? [Int]), [0, 1, 0])
        XCTAssertEqual(try object(messages[5]["content"] as? String)["preferredWidth"] as? Int, 1920)
        XCTAssertEqual(try object(messages[5]["content"] as? String)["supportsCustomResolution"] as? Bool, true)
        XCTAssertTrue(messages.allSatisfy { $0["type"] as? String == "Message" && $0["id"] as? String == "41f93d5a-900f-4d33-b7a1-2d4ca6747072" })
        XCTAssertTrue(XboxChannels.isHandshakeAck(#"{"type":"HandshakeAck","version":"messageV1"}"#))
        XCTAssertFalse(XboxChannels.isHandshakeAck("{bozuk"))
        XCTAssertEqual(try object(XboxChannels.handshake)["type"] as? String, "Handshake")
        XCTAssertEqual(try object(XboxChannels.authorization)["message"] as? String, "authorizationRequest")
        XCTAssertEqual(XboxChannels.channels.map(\.name), ["input", "chat", "control", "message"])
        XCTAssertEqual(XboxChannels.channels.map(\.protocol), ["1.0", "chatV1", "controlV1", "messageV1"])
    }

    func testOfferGetsStereo() {
        let sdp = "a=fmtp:111 minptime=10;useinbandfec=1\r\na=fmtp:112 useinbandfec=1\r\n"
        XCTAssertEqual(XboxChannels.mungeOffer(sdp), "a=fmtp:111 minptime=10;useinbandfec=1; stereo=1\r\na=fmtp:112 useinbandfec=1\r\n")
        XCTAssertEqual(XboxChannels.mungeOffer("v=0"), "v=0")
    }

    func testSessionIdFromPath() throws {
        XCTAssertEqual(XboxHome.sessionId(try object(#"{"sessionPath":"v5/sessions/home/ABC"}"#)), "ABC")
        XCTAssertNil(XboxHome.sessionId([:]))
        let body = try object(XboxHome.playBody(consoleId: "F", timezoneOffsetMinutes: 180).text)
        XCTAssertEqual((body["settings"] as? [String: Any])?["timezoneOffsetMinutes"] as? Int, 180)
    }
}

final class XboxSessionMachineTests: XCTestCase {
    func testHappyPath() {
        let m = XboxSessionMachine()
        XCTAssertEqual(m.start(), .play)
        XCTAssertEqual(m.played(), .pollState(delayMs: 0))
        XCTAssertEqual(m.status("Provisioning", nil), .pollState(delayMs: 1000))
        XCTAssertEqual(m.status("ReadyToConnect", nil), .connect)
        XCTAssertEqual(m.connected(), .pollState(delayMs: 1000))
        // connect bir kez gönderilir.
        XCTAssertEqual(m.status("ReadyToConnect", nil), .pollState(delayMs: 1000))
        XCTAssertEqual(m.status("Provisioned", nil), .negotiate)
        XCTAssertTrue(m.streaming())
        XCTAssertEqual(m.phase, .streaming)
        XCTAssertFalse(m.streaming())
    }

    func testTimesOutAfterMaxPolls() {
        let m = XboxSessionMachine(maxPolls: 3)
        _ = m.start(); _ = m.played()
        XCTAssertEqual(m.status("Provisioning", nil), .pollState(delayMs: 1000))
        XCTAssertEqual(m.status("Provisioning", nil), .pollState(delayMs: 1000))
        XCTAssertEqual(m.status("Provisioning", nil), .retry(delayMs: 5000))
        guard case let .failed(kind, _, willRetry, attempt) = m.phase else { return XCTFail("\(m.phase)") }
        XCTAssertEqual(kind, .timeout)
        XCTAssertTrue(willRetry)
        XCTAssertEqual(attempt, 1)
    }

    func testFailedStateStopsAndClassifies() {
        let m = XboxSessionMachine()
        _ = m.start(); _ = m.played()
        // Konsol yayını kendisi reddetti: yeniden denenmez.
        XCTAssertEqual(m.status("Failed", #"{"code":"ServerInUse"}"#), .retry(delayMs: nil))
        guard case let .failed(kind, _, _, _) = m.phase else { return XCTFail() }
        XCTAssertEqual(kind, .busy)
        // Yeniden başlar.
        XCTAssertEqual(m.start(), .play)
        XCTAssertEqual(XboxSessionMachine.classifyFailure(#"{"code":"ConsoleOffline"}"#), .consoleUnavailable)
        XCTAssertEqual(XboxSessionMachine.classifyFailure(#"{"code":"alreadystreaming"}"#), .busy)
        XCTAssertEqual(XboxSessionMachine.classifyFailure(nil), .consoleUnavailable)
    }

    func testErrorDetailsWhileProvisioningStop() {
        let m = XboxSessionMachine()
        _ = m.start(); _ = m.played()
        XCTAssertEqual(m.status("Provisioning", #"{"code":null,"message":""}"#), .pollState(delayMs: 1000))
        XCTAssertEqual(m.status("Provisioning", #"{"code":"StreamingDisabled"}"#), .retry(delayMs: nil))
        guard case let .failed(kind, message, willRetry, _) = m.phase else { return XCTFail() }
        XCTAssertEqual(kind, .consoleUnavailable)
        XCTAssertEqual(message, #"{"code":"StreamingDisabled"}"#)
        XCTAssertFalse(willRetry)
    }

    func testSignInErrorsDoNotRetry() {
        let m = XboxSessionMachine()
        _ = m.start()
        XCTAssertEqual(m.fail(.signInRequired, "giriş"), .retry(delayMs: nil))
        guard case let .failed(_, _, willRetry, _) = m.phase else { return XCTFail() }
        XCTAssertFalse(willRetry)
        XCTAssertEqual(XboxSessionMachine().fail(.noXboxAccount, "x"), .retry(delayMs: nil))
    }

    func testStopIsFinal() {
        let m = XboxSessionMachine()
        _ = m.start(); _ = m.played()
        m.stop()
        XCTAssertEqual(m.status("Provisioned", nil), .nothing)
        XCTAssertEqual(m.start(), .nothing)
        XCTAssertEqual(m.fail(.network, "x"), .nothing)
    }

    func testEventsOutOfOrderAreIgnored() {
        let m = XboxSessionMachine()
        XCTAssertEqual(m.played(), .nothing)
        XCTAssertEqual(m.status("Provisioned", nil), .nothing)
        XCTAssertEqual(m.connected(), .nothing)
        XCTAssertFalse(m.streaming())
    }

    func testRetriesBackOffAndStopAfterThree() {
        let machine = XboxSessionMachine()
        let delays = (1...4).map { _ -> Int64?? in
            if case let .retry(delay) = machine.fail(.network, "koptu") { return delay }
            return .none
        }
        XCTAssertEqual(delays, [5000, 15000, 45000, .some(nil)])
        guard case let .failed(_, _, willRetry, _) = machine.phase else { return XCTFail() }
        XCTAssertFalse(willRetry)
    }

    func testAStreamThatStayedUpStartsANewRetrySeries() {
        var now: Int64 = 1000
        let machine = XboxSessionMachine(clock: { now })
        _ = machine.fail(.network, "koptu")
        _ = machine.fail(.network, "koptu")
        _ = machine.start(); _ = machine.played(); _ = machine.status("Provisioned", nil)
        XCTAssertTrue(machine.streaming())
        now += 61_000
        XCTAssertEqual(machine.fail(.network, "koptu"), .retry(delayMs: 5000))
    }

    func testAShortStreamKeepsCounting() {
        var now: Int64 = 1000
        let machine = XboxSessionMachine(clock: { now })
        _ = machine.fail(.network, "koptu")
        _ = machine.start(); _ = machine.played(); _ = machine.status("Provisioned", nil)
        XCTAssertTrue(machine.streaming())
        now += 10_000
        XCTAssertEqual(machine.fail(.network, "koptu"), .retry(delayMs: 15000))
    }

    func testErrorDetailsDetection() {
        XCTAssertFalse(XboxSessionMachine.hasErrorDetails(nil))
        XCTAssertFalse(XboxSessionMachine.hasErrorDetails("{}"))
        XCTAssertFalse(XboxSessionMachine.hasErrorDetails("null"))
        XCTAssertFalse(XboxSessionMachine.hasErrorDetails(#"{"code":null,"message":""}"#))
        XCTAssertFalse(XboxSessionMachine.hasErrorDetails(#"{"code": null, "message": ""}"#))
        XCTAssertTrue(XboxSessionMachine.hasErrorDetails(#"{"code":"X"}"#))
    }

    func testFrameCounterIgnoresFramesOfAClosedConnection() {
        let counter = XboxFrameCounter()
        let first = counter.beginSession()
        XCTAssertEqual(counter.recordFrame(first), 1)
        let second = counter.beginSession()
        XCTAssertNil(counter.recordFrame(first))            // eski bağlantının geç gelen karesi
        XCTAssertEqual(counter.recordFrame(second), 1)      // yeni bağlantının ilk karesi yine "ilk kare"
        XCTAssertEqual(counter.totalFrames, 2)
        var ran = false
        counter.runIfCurrent(first) { ran = true }
        XCTAssertFalse(ran)
        counter.endSession(second)
        XCTAssertEqual(counter.framesInSession(second), 0)
    }
}

/// Sahte çözücü: çözdüğü her kareyi geri çağrıyla verir.
final class FakeDecoder: NSObject, RTCVideoDecoder {
    var callback: RTCVideoDecoderCallback?
    var decoded = 0
    var status = 0

    func setCallback(_ callback: @escaping RTCVideoDecoderCallback) { self.callback = callback }
    func startDecode(withNumberOfCores numberOfCores: Int32) -> Int { 0 }
    func release() -> Int { 0 }
    func decode(_ encodedImage: RTCEncodedImage, missingFrames: Bool, codecSpecificInfo info: RTCCodecSpecificInfo?, renderTimeMs: Int64) -> Int {
        decoded += 1
        return status
    }
    func implementationName() -> String { "fake.h264" }
}

final class XboxFrameAckTests: XCTestCase {
    func testAckQueueDrainsInOrderAndKeepsTheNewestWhenFull() {
        let queue = XboxFrameAckQueue(capacity: 3)
        for key in Int64(1)...5 { queue.offer(XboxFrameAck(serverDataKey: key, arrivalMs: 0, decodedMs: 0)) }
        XCTAssertEqual(queue.drain().map(\.serverDataKey), [3, 4, 5])
        XCTAssertTrue(queue.drain().isEmpty)
        let many = XboxFrameAckQueue()
        for i in 0..<40 { many.offer(XboxFrameAck(serverDataKey: Int64(i), arrivalMs: 0, decodedMs: 0)) }
        XCTAssertEqual(many.drain().count, XboxChannels.maxFrameAcks)
        XCTAssertEqual(many.drain().count, 40 - XboxChannels.maxFrameAcks)
        // RTP damgası 90 kHz, u32'ye sarılır.
        XCTAssertEqual(XboxFrameAckQueue.serverDataKey(1000), 90_000)
        XCTAssertEqual(XboxFrameAckQueue.serverDataKey(50_000_000), (50_000_000 * 90) & 0xFFFF_FFFF)
    }

    func frame(rtp: UInt32) -> RTCVideoFrame {
        let made = RTCVideoFrame(buffer: RTCI420Buffer(width: 2, height: 2), rotation: ._0, timeStampNs: 0)
        made.timeStamp = Int32(bitPattern: rtp)
        return made
    }

    func testDecodedFramesAreReportedWithArrivalAndDecodeTimes() {
        var now: Int64 = 100
        var seen: [[Int64]] = []
        let inner = FakeDecoder()
        let wrapper = XboxReportingDecoder(inner, listener: { seen.append([$0, $1, $2]) }, clock: { now })
        var delivered = 0
        wrapper.setCallback { _ in delivered += 1 }
        let image = RTCEncodedImage()
        image.timeStamp = 630_000  // 7000 ms
        XCTAssertEqual(wrapper.decode(image, missingFrames: false, codecSpecificInfo: nil, renderTimeMs: 0), 0)
        XCTAssertEqual(inner.decoded, 1)
        now = 130
        inner.callback?(frame(rtp: 630_000))
        XCTAssertEqual(seen, [[7000, 100, 130]])
        XCTAssertEqual(delivered, 1)
        // Girişte görülmemiş kare: geliş anı çözülme anıdır. RTP damgası u32 (eksi Int32 değil).
        inner.callback?(frame(rtp: 0xFFFF_FFF0))
        XCTAssertEqual(seen.last, [Int64(0xFFFF_FFF0) / 90, 130, 130])
        XCTAssertEqual(wrapper.implementationName(), "fake.h264")
    }

    func testDecoderErrorsAreReportedOnce() {
        let inner = FakeDecoder()
        inner.status = -1
        var errors: [String] = []
        let wrapper = XboxReportingDecoder(inner, listener: { _, _, _ in }, onError: { errors.append($0) })
        XCTAssertEqual(wrapper.decode(RTCEncodedImage(), missingFrames: false, codecSpecificInfo: nil, renderTimeMs: 0), -1)
        XCTAssertEqual(wrapper.decode(RTCEncodedImage(), missingFrames: false, codecSpecificInfo: nil, renderTimeMs: 0), -1)
        XCTAssertEqual(errors.count, 1)
        XCTAssertTrue(errors[0].contains("fake.h264") && errors[0].contains("-1"))
    }

    func testFactoryWrapsTheDefaultDecoders() throws {
        let factory = XboxDecoderFactory(listener: { _, _, _ in })
        let codecs = factory.supportedCodecs()
        let h264 = try XCTUnwrap(codecs.first { $0.name == "H264" })
        XCTAssertTrue(factory.createDecoder(h264) is XboxReportingDecoder)
    }
}

final class XboxStreamSessionTests: XCTestCase {
    final class Recorder: XboxStreamListener, @unchecked Sendable {
        let lock = NSLock()
        var logs: [String] = []
        var failures: [(XboxError, Bool)] = []
        var connecting = 0
        let failed = XCTestExpectation(description: "onFailed")

        func onLog(_ level: Int, _ text: String) { lock.withLock { logs.append(text) } }
        func onConnecting() { lock.withLock { connecting += 1 } }
        func onStreaming() {}
        func onFailed(_ kind: XboxError, _ detail: String?, _ willRetry: Bool) {
            lock.withLock { failures.append((kind, willRetry)) }
            failed.fulfill()
        }
        func onFrame(_ image: CGImage) {}
    }

    func tokenAuth(_ http: FakeHttp) throws -> XboxAuth {
        let now = XboxAuth.wallClock()
        return XboxAuth(http: http, sleep: { _ in },
                        userToken: try XboxUserToken.fromResponse(object(#"{"access_token":"A","refresh_token":"R","expires_in":3600}"#), nowMs: now))
    }

    func tokens(_ url: String) -> XboxHttpResponse? {
        if url.contains("user.auth") || url.contains("xsts.auth") {
            return ok(#"{"Token":"X","NotAfter":"2099-11-30T00:00:00Z","DisplayClaims":{"xui":[{"uhs":"H"}]}}"#)
        }
        if url.contains("/v2/login/user") {
            return ok(#"{"gsToken":"GS","durationInSeconds":14400,"offeringSettings":{"regions":[{"baseUri":"https://uks.example/","isDefault":true}]}}"#)
        }
        return nil
    }

    /// Konsol yayını reddederse oturum durur (yeniden denemez) ve Xbox'taki oturumu kapatır.
    func testConsoleRefusalStopsWithoutRetry() throws {
        let http = FakeHttp { method, url, _ in
            if let token = self.tokens(url) { return token }
            if url.hasSuffix("/play") { return ok(#"{"sessionPath":"v5/sessions/home/S1"}"#) }
            if url.hasSuffix("/state") { return ok(#"{"state":"Failed","errorDetails":{"code":"ServerInUse"}}"#) }
            if method == "DELETE" { return XboxHttpResponse(status: 204, body: "") }
            XCTFail(url)
            return XboxHttpResponse(status: 500, body: "")
        }
        let recorder = Recorder()
        let session = XboxStreamSession(auth: try tokenAuth(http), console: XboxConsole(id: "F", name: "Salon", model: "X", streamingEnabled: true),
                                        listener: recorder)
        session.start()
        wait(for: [recorder.failed], timeout: 5)
        let deadline = Date().addingTimeInterval(2)
        while !http.calls.contains(where: { $0.method == "DELETE" }) && Date() < deadline { Thread.sleep(forTimeInterval: 0.02) }
        session.dispose()
        XCTAssertEqual(recorder.lock.withLock { recorder.failures.map(\.0) }, [.busy])
        XCTAssertEqual(recorder.lock.withLock { recorder.failures.map(\.1) }, [false])
        XCTAssertEqual(recorder.lock.withLock { recorder.connecting }, 1)
        XCTAssertEqual(http.calls.last { $0.method == "DELETE" }?.url, "https://uks.example/v5/sessions/home/S1")
    }

    /// Ağ hatası yeniden denenir; dispose bekleyen yeniden denemeyi hemen keser.
    func testNetworkErrorRetriesAndDisposeStopsTheWait() throws {
        let http = FakeHttp { _, url, _ in
            if let token = self.tokens(url) { return token }
            throw XboxException(.network, "yok")
        }
        let recorder = Recorder()
        let session = XboxStreamSession(auth: try tokenAuth(http), console: XboxConsole(id: "F", name: "Salon", model: "X", streamingEnabled: true),
                                        listener: recorder)
        session.start()
        wait(for: [recorder.failed], timeout: 5)
        XCTAssertEqual(recorder.lock.withLock { recorder.failures.first?.0 }, .network)
        XCTAssertEqual(recorder.lock.withLock { recorder.failures.first?.1 }, true)
        let before = http.calls.count
        session.dispose()
        Thread.sleep(forTimeInterval: 0.3)
        XCTAssertEqual(http.calls.count, before)  // 5 sn sonraki deneme yapılmadı
        XCTAssertTrue(recorder.lock.withLock { recorder.logs.contains { $0.hasPrefix("yeniden deneme 1/3: 5 sn sonra") } })
    }
}
