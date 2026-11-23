// SPDX-License-Identifier: AGPL-3.0-only
// Gerçek Xbox olmadan uçtan uca deneme: aynı süreçte ikinci bir RTCPeerConnection "Xbox" rolünü oynar (gssv uçları
// sahte HTTP'den). Oturumun WebRTC yolu bütünüyle çalışır: teklif ve stereo düzeltmesi, dört veri kanalı, el sıkışma,
// yapılandırma iletileri, ClientMetadata, H.264 görüntü (VideoToolbox) → sarılı çözücü → kare onayı paketleri →
// CGImage kare, Xbox oturumunun kapatılması. Gerçek Xbox'ın davranışını (ör. onaysız 40 sn'de kapatma) denemez.

import XCTest
import WebRTC
@testable import GTNative

private final class FakeXbox: NSObject, RTCPeerConnectionDelegate, RTCDataChannelDelegate, @unchecked Sendable {
    let factory = RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
    var peer: RTCPeerConnection!
    let source: RTCVideoSource
    let track: RTCVideoTrack
    let capturer: RTCVideoCapturer
    let lock = NSLock()
    var answer: String?
    var offer: String?
    var local: [RTCIceCandidate] = []
    var gathered = false
    var channels: [String: RTCDataChannel] = [:]
    var texts: [String: [String]] = [:]
    var binary: [Data] = []
    var timer: DispatchSourceTimer?
    var states: [String] = ["Provisioning", "ReadyToConnect", "Provisioned"]

    override init() {
        source = factory.videoSource()
        track = factory.videoTrack(with: source, trackId: "video0")
        capturer = RTCVideoCapturer(delegate: source)
        super.init()
        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        peer = factory.peerConnection(with: config, constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil), delegate: self)
    }

    func nextState() -> String {
        lock.withLock { states.count > 1 ? states.removeFirst() : states[0] }
    }

    /// İstemcinin teklifine yanıt: görüntü izi gönderilir.
    func receiveOffer(_ sdp: String) {
        lock.withLock { offer = sdp }
        peer.setRemoteDescription(RTCSessionDescription(type: .offer, sdp: sdp)) { [self] error in
            XCTAssertNil(error, "teklif kabul edilmedi")
            if let video = peer.transceivers.first(where: { $0.mediaType == .video }) {
                video.sender.track = track
                video.setDirection(.sendOnly, error: nil)
            }
            peer.answer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { [self] description, _ in
                guard let description else { return XCTFail("yanıt yok") }
                peer.setLocalDescription(description) { [self] _ in
                    lock.withLock { answer = description.sdp }
                    startFrames()
                }
            }
        }
    }

    func addRemote(_ candidates: [[String: Any]]) {
        for c in candidates {
            peer.add(RTCIceCandidate(sdp: c["candidate"] as! String, sdpMLineIndex: Int32(c["sdpMLineIndex"] as! Int), sdpMid: c["sdpMid"] as? String)) { _ in }
        }
    }

    /// 640x360 düz gri (Y=200) kareler, saniyede 30.
    func startFrames() {
        let made = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "fake-xbox-frames"))
        var count: Int64 = 0
        made.schedule(deadline: .now(), repeating: .milliseconds(33))
        made.setEventHandler { [self] in
            let buffer = RTCMutableI420Buffer(width: 640, height: 360)
            memset(buffer.mutableDataY, 200, Int(buffer.strideY) * 360)
            memset(buffer.mutableDataU, 128, Int(buffer.strideU) * 180)
            memset(buffer.mutableDataV, 128, Int(buffer.strideV) * 180)
            count += 1
            source.capturer(capturer, didCapture: RTCVideoFrame(buffer: buffer, rotation: ._0, timeStampNs: count * 33_000_000))
        }
        made.resume()
        lock.withLock { timer = made }
    }

    func stop() {
        lock.withLock { timer }?.cancel()
        peer.close()
    }

    // RTCPeerConnectionDelegate
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        if newState == .complete { lock.withLock { gathered = true } }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        lock.withLock { local.append(candidate) }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        lock.withLock { channels[dataChannel.label] = dataChannel }
        dataChannel.delegate = self
    }

    // RTCDataChannelDelegate
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {}
    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        if buffer.isBinary {
            lock.withLock { binary.append(buffer.data) }
            return
        }
        let text = String(decoding: buffer.data, as: UTF8.self)
        lock.withLock { texts[dataChannel.label, default: []].append(text) }
        if dataChannel.label == "message", (XboxJSON.object(text)?["type"] as? String) == "Handshake" {
            dataChannel.sendData(RTCDataBuffer(data: Data(#"{"type":"HandshakeAck","version":"messageV1"}"#.utf8), isBinary: false))
        }
    }
}

final class XboxLoopbackTests: XCTestCase {
    final class Recorder: XboxStreamListener, @unchecked Sendable {
        let lock = NSLock()
        var logs: [String] = []
        var images: [CGImage] = []
        var failures: [String] = []
        let streaming = XCTestExpectation(description: "onStreaming")
        let frame = XCTestExpectation(description: "onFrame")

        func onLog(_ level: Int, _ text: String) { lock.withLock { logs.append(text) } }
        func onConnecting() {}
        func onStreaming() { streaming.fulfill() }
        func onFailed(_ kind: XboxError, _ detail: String?, _ willRetry: Bool) {
            lock.withLock { failures.append("\(kind.rawValue): \(detail ?? "")") }
        }
        func onFrame(_ image: CGImage) {
            let first = lock.withLock { () -> Bool in
                images.append(image)
                return images.count == 1
            }
            if first { frame.fulfill() }
        }
    }

    func testWholeWebRtcPathAgainstALocalPeer() throws {
        let xbox = FakeXbox()
        defer { xbox.stop() }
        let http = FakeHttp { method, url, body in
            if url.contains("user.auth") || url.contains("xsts.auth") {
                return ok(#"{"Token":"X","NotAfter":"2099-11-30T00:00:00Z","DisplayClaims":{"xui":[{"uhs":"H"}]}}"#)
            }
            if url.contains("/v2/login/user") {
                return ok(#"{"gsToken":"GS","durationInSeconds":14400,"offeringSettings":{"regions":[{"baseUri":"https://xhome.test","isDefault":true}]}}"#)
            }
            if url.contains("login.live.com") { return ok(#"{"access_token":"LPT"}"#) }
            if url.hasSuffix("/play") { return ok(#"{"sessionPath":"v5/sessions/home/LOOP"}"#) }
            if url.hasSuffix("/state") { return ok(#"{"state":"\#(xbox.nextState())"}"#) }
            if url.hasSuffix("/connect") || url.hasSuffix("/keepalive") { return XboxHttpResponse(status: 202, body: "") }
            if url.hasSuffix("/sdp") && method == "POST" {
                xbox.receiveOffer((try XboxJSON.parseObject(body ?? ""))["sdp"] as! String)
                return XboxHttpResponse(status: 202, body: "")
            }
            if url.hasSuffix("/sdp") {
                guard let answer = xbox.lock.withLock({ xbox.answer }) else { return XboxHttpResponse(status: 204, body: "") }
                let response = XboxJSONObject(["messageType": "answer", "sdp": answer, "status": "success"]).text
                return ok(XboxJSONObject(["exchangeResponse": response]).text)
            }
            if url.hasSuffix("/ice") && method == "POST" {
                xbox.addRemote((try XboxJSON.parseObject(body ?? ""))["candidate"] as! [[String: Any]])
                return XboxHttpResponse(status: 202, body: "")
            }
            if url.hasSuffix("/ice") {
                let (done, candidates) = xbox.lock.withLock { (xbox.gathered, xbox.local) }
                guard done else { return XboxHttpResponse(status: 204, body: "") }
                let list = candidates.map { XboxJSONObject(["candidate": "a=" + $0.sdp, "sdpMLineIndex": Int($0.sdpMLineIndex), "sdpMid": $0.sdpMid ?? NSNull()]) as Any }
                var text = ""
                XboxJSON.write(list, to: &text)
                return ok(XboxJSONObject(["exchangeResponse": text]).text)
            }
            if method == "DELETE" { return XboxHttpResponse(status: 204, body: "") }
            XCTFail("beklenmeyen istek \(method) \(url)")
            return XboxHttpResponse(status: 500, body: "")
        }
        let auth = XboxAuth(http: http, userToken: try XboxUserToken.fromResponse(
            object(#"{"access_token":"A","refresh_token":"R","expires_in":3600}"#), nowMs: XboxAuth.wallClock()))
        let recorder = Recorder()
        let session = XboxStreamSession(auth: auth, console: XboxConsole(id: "F", name: "Salon", model: "XboxSeriesX", streamingEnabled: true),
                                        listener: recorder, frameIntervalMs: 200)
        session.start()
        let result = XCTWaiter().wait(for: [recorder.streaming, recorder.frame], timeout: 30)
        if result != .completed {
            print(recorder.lock.withLock { recorder.logs }.joined(separator: "\n"))
            XCTFail("görüntü gelmedi: \(result.rawValue); hatalar: \(recorder.lock.withLock { recorder.failures })")
            session.dispose()
            return
        }
        // Onaylar 16 ms'de bir gider; birkaç paket birikmesi için biraz beklenir.
        Thread.sleep(forTimeInterval: 1.5)

        // Teklif stereo düzeltmesiyle gitti.
        XCTAssertTrue(xbox.lock.withLock { xbox.offer ?? "" }.contains("useinbandfec=1; stereo=1"))
        // Kare: 640x360, gri. WebRTC'nin H.264 kodlayıcısı I420 baytlarını tam aralıklı NV12 olarak kodlar ve çözücüsü
        // VideoToolbox'tan hep tam aralık ister (sınırlı aralıklı Xbox akışını VideoToolbox genişletir); Y=200 → 200.
        let image = try XCTUnwrap(recorder.lock.withLock { recorder.images.first })
        XCTAssertEqual([image.width, image.height], [640, 360])
        let data = image.dataProvider!.data! as Data
        let middle = 180 * image.bytesPerRow + 320 * 4
        XCTAssertEqual(Double(data[middle + 1]), 200, accuracy: 6)
        XCTAssertEqual(Double(data[middle]), Double(data[middle + 2]), accuracy: 4)  // gri: B ≈ R
        XCTAssertGreaterThan(session.framesReceived, 1)
        // Kareler en çok 200 ms'de bir verilir (1,5 sn'de ~8; 30 fps'nin tamamı değil).
        XCTAssertLessThan(recorder.lock.withLock { recorder.images.count }, 20)

        // Veri kanalları ve iletiler.
        let (texts, binary, channels) = xbox.lock.withLock { (xbox.texts, xbox.binary, xbox.channels) }
        XCTAssertEqual(Set(channels.keys), ["input", "chat", "control", "message"])
        XCTAssertEqual(channels["input"]?.protocol, "1.0")
        XCTAssertEqual(channels["message"]?.protocol, "messageV1")
        XCTAssertEqual(texts["message"]?.first, XboxChannels.handshake)
        XCTAssertEqual(texts["message"]?.count, 7)  // el sıkışma + 6 yapılandırma
        XCTAssertEqual(texts["control"], [XboxChannels.authorization, XboxChannels.gamepadAdded])
        let metadata = try XCTUnwrap(binary.first)
        XCTAssertEqual(metadata.count, 15)
        XCTAssertEqual(metadata[0], 8)
        let acks = binary.dropFirst().filter { $0.first == 1 }
        XCTAssertFalse(acks.isEmpty, "kare onayı gitmedi")
        for packet in acks {
            let frames = Int(packet[14])
            XCTAssertEqual(packet.count, 15 + 28 * frames)
            let key = UInt32(packet[15]) | UInt32(packet[16]) << 8 | UInt32(packet[17]) << 16 | UInt32(packet[18]) << 24
            XCTAssertEqual(key % 90, 0)  // RTP damgasının ms'si × 90
        }
        let sequences = acks.map { UInt32($0[2]) | UInt32($0[3]) << 8 }
        XCTAssertEqual(sequences, Array(1...UInt32(acks.count)))

        session.dispose()
        let deadline = Date().addingTimeInterval(5)
        while !http.calls.contains(where: { $0.method == "DELETE" }) && Date() < deadline { Thread.sleep(forTimeInterval: 0.05) }
        XCTAssertEqual(http.calls.last { $0.method == "DELETE" }?.url, "https://xhome.test/v5/sessions/home/LOOP")
        XCTAssertTrue(recorder.lock.withLock { recorder.failures }.isEmpty, "\(recorder.lock.withLock { recorder.failures })")
    }
}
