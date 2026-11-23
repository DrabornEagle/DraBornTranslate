// SPDX-License-Identifier: AGPL-3.0-only
// Xbox ev yayını (Beta; Android: xbox/XboxStreamSession.kt): Windows'taki XboxStreamSession + player.html'in
// telefondaki karşılığı. Görüntü WebRTC'yle (Google'ın Objective-C kitaplığı) alınır; kareler CGImage olarak
// dinleyiciye (Engine) verilir, o da PS5 kareleriyle aynı okuma hattına koyar. Ses çalınmaz, mikrofon açılmaz,
// kumanda girdisi gönderilmez. Android'deki sonraki düzeltmeler de burada: kare onayı, sınırlı yeniden deneme ve
// Xbox günlüğü, kare sayacı.

import CoreGraphics
import Foundation
import WebRTC

/// Oturumun kendi iş parçacıklarından çağrılır. `dispose()` sonrasında çağrı gelmez (son kare çağrısı sürüyor olabilir).
public protocol XboxStreamListener: AnyObject {
    /// `level`: 0 ayrıntı, 1 uyarı/hata, 2 bilgi (Engine.log ile aynı).
    func onLog(_ level: Int, _ text: String)
    /// Oturum açılıyor (ilk deneme ve her yeniden deneme).
    func onConnecting()
    /// İlk kare geldi.
    func onStreaming()
    /// `willRetry` true ise oturum kendisi yeniden dener (5, 15, 45 sn); false ise durmuştur (kullanıcı bir şey yapmalı).
    func onFailed(_ kind: XboxError, _ detail: String?, _ willRetry: Bool)
    /// Renkli kare (BGRA); en çok `frameIntervalMs`'de bir.
    func onFrame(_ image: CGImage)
}

/// Kapanışlarla dinleyici (Engine'deki PS5 oturumunun `onFrame`/`onState` biçimine uysun diye).
public final class XboxStreamCallbacks: XboxStreamListener, @unchecked Sendable {
    public var log: ((Int, String) -> Void)?
    public var connecting: (() -> Void)?
    public var streaming: (() -> Void)?
    public var failed: ((XboxError, String?, Bool) -> Void)?
    public var frame: ((CGImage) -> Void)?

    public init() {}

    public func onLog(_ level: Int, _ text: String) { log?(level, text) }
    public func onConnecting() { connecting?() }
    public func onStreaming() { streaming?() }
    public func onFailed(_ kind: XboxError, _ detail: String?, _ willRetry: Bool) { failed?(kind, detail, willRetry) }
    public func onFrame(_ image: CGImage) { frame?(image) }
}

public final class XboxStreamSession: @unchecked Sendable {
    static let iceGatherMs: Int64 = 5000
    static let keepaliveMs: Int64 = 30_000
    /// El sıkışmadan sonra bu sürede kare gelmezse oturum yeniden açılır.
    static let firstFrameMs: Int64 = 20_000
    /// Kare onayları oynatıcıdaki gibi 16 ms'de bir paketlenir (Input.ts `_inputInterval`).
    static let ackIntervalMs: Int64 = 16
    /// WebRTC'nin geri çağırmalı işlemleri en çok bu kadar beklenir.
    static let callbackTimeoutMs: Int64 = 10_000

    private let auth: XboxAuth
    private let console: XboxConsole
    private let listener: XboxStreamListener
    /// Dinleyiciye iki kare arasında en az bu kadar süre bırakılır (yazı okuma saniyede birkaç kare ister).
    private let frameIntervalMs: Int64
    private let home: XboxHome
    let machine = XboxSessionMachine()
    private let frameCounter = XboxFrameCounter()
    private let acks = XboxFrameAckQueue()
    private let ackQueue = DispatchQueue(label: "gt-xbox-acks", qos: .userInitiated)
    private let keepaliveQueue = DispatchQueue(label: "gt-xbox-keepalive", qos: .utility)
    /// `dispose()` bunu açar: bekleyen her uyku hemen biter.
    private let stopSignal = XboxLatch()

    private let lock = NSLock()
    private var started = false
    private var stopped = false
    private var sessionId: String?
    private var frameSession: XboxFrameCounter.Session?
    private var ended: XboxLatch?
    private var endReason: String?
    private var lastError: String?
    private var lastDelivered: Int64 = 0
    private var firstFrameAt: Int64 = 0
    /// Kare onayları el sıkışmadan sonra gönderilir. Xbox çözülen karelerin onayını bekler; onay gitmezse yayını
    /// yaklaşık 40 sn'de kendisi kapatır.
    private var acking = false
    private var ackSequence = 0
    private var acksSent: Int64 = 0
    /// Onay paketlerindeki anların sıfırı (oynatıcıdaki performance.now() gibi).
    private var clockStart: Int64 = 0
    private var frameErrorLogged = false

    // WebRTC nesneleri yalnız oturum iş parçacığında kurulur ve kapatılır.
    private var factory: RTCPeerConnectionFactory?
    private var peer: RTCPeerConnection?
    private var peerDelegate: PeerDelegate?
    private var channels: [String: RTCDataChannel] = [:]
    private var channelDelegates: [ChannelDelegate] = []
    private var videoTrack: RTCVideoTrack?
    private var renderer: FrameRenderer?
    private var ackTimer: DispatchSourceTimer?
    private var keepaliveTimer: DispatchSourceTimer?

    /// Engine'in kullanacağı yol: girişi `account`'tan alır (`XboxAccount.shared`).
    public convenience init(console: XboxConsole, listener: XboxStreamListener, frameIntervalMs: Int64 = 200, account: XboxAccount = .shared) {
        self.init(auth: account.auth, console: console, listener: listener, frameIntervalMs: frameIntervalMs)
    }

    init(auth: XboxAuth, console: XboxConsole, listener: XboxStreamListener, frameIntervalMs: Int64 = 200, home: XboxHome? = nil) {
        self.auth = auth
        self.console = console
        self.listener = listener
        self.frameIntervalMs = frameIntervalMs
        self.home = home ?? XboxHome(auth: auth)
    }

    /// Oturum boyunca gelen kare sayısı (tanı: görüntü geliyor mu).
    public var framesReceived: Int64 { frameCounter.totalFrames }

    public func start() {
        let go = lock.withLock { () -> Bool in
            guard !started, !stopped else { return false }
            started = true
            return true
        }
        guard go else { return }
        let thread = Thread { [self] in run() }
        thread.name = "gt-xbox"
        thread.qualityOfService = .userInitiated
        thread.start()
    }

    /// Bloklamaz: oturum arka planda kapatılır (WebRTC bağlantısı ve Xbox'taki yayın oturumu).
    public func dispose() {
        let latch = lock.withLock { () -> XboxLatch?? in
            guard !stopped else { return .none }
            stopped = true
            return .some(ended)
        }
        guard let latch else { return }
        machine.stop()
        latch?.open()
        stopSignal.open()
        // Oturum hiç başlamadıysa kapatılacak bir şey yok; başladıysa iş parçacığı döngüden çıkarken kapatır.
    }

    private var isStopped: Bool { lock.withLock { stopped } }

    private func log(_ text: String, _ level: Int = 2) {
        if !isStopped { listener.onLog(level, text) }
    }

    /// `ms` bekler; oturum kapatılırsa hemen döner (true).
    private func pause(_ ms: Int64) -> Bool { stopSignal.wait(ms) }

    private func run() {
        log("konsol: \(console.name)")
        while !isStopped {
            var action = machine.start()
            // Makine `/state` yanıtında kendisi karar verdiyse (Failed, errorDetails) aynı hata yeniden sayılmaz.
            var decided: XboxSessionMachine.Action?
            listener.onConnecting()
            do {
                log("oturum açılıyor: \(console.name)")
                let id = try home.play(console.id)
                lock.withLock { sessionId = id }
                log("play yanıtı: oturum \(id.prefix(8))…")
                action = machine.played()
                loop: while !isStopped {
                    switch action {
                    case .pollState(let delayMs):
                        if delayMs > 0, pause(delayMs) { throw XboxCancelled() }
                        let status = try home.state(id)
                        action = machine.status(status.state, status.errorDetails)
                        if status.state != "Provisioning" { log("durum: \(status.state)") }
                        if let details = status.errorDetails { log("errorDetails: \(details)") }
                    case .connect:
                        try home.connect(id)
                        log("connect: başarılı")
                        action = machine.connected()
                    case .negotiate:
                        try negotiate(id)
                        break loop
                    case .retry:
                        decided = action
                        if case let .failed(kind, message, _, _) = machine.phase { throw XboxException(kind, message) }
                        throw XboxException(.service, "Xbox")
                    default:
                        break loop
                    }
                }
                if !isStopped {
                    throw XboxException(.network, lock.withLock { endReason } ?? "Xbox görüntü bağlantısı kapandı")
                }
            } catch is XboxCancelled {
                // Kapatıldı.
            } catch let error as XboxException {
                action = decided ?? machine.fail(error.kind, error.message)
                report(error.kind, error.message, action)
            } catch {
                let text = "\(error)"
                action = machine.fail(.service, text)
                report(.service, text, action)
            }
            summarize()
            closePeer()
            stopRemote()
            if isStopped { break }
            guard case let .retry(delay?) = action else { break }
            if pause(delay) { break }
        }
        // Kapatma döngünün içinde yapılmadıysa (ör. play sırasında kapatıldı) burada yapılır.
        closePeer()
        stopRemote()
    }

    /// Bağlantı bitince: kaç kare geldi, kaç onay gitti, ilk kareden kaç saniye sonra ve neden bitti.
    private func summarize() {
        guard let session = lock.withLock({ frameSession }) else { return }
        let frames = frameCounter.framesInSession(session)
        let now = xboxUptimeMs()
        let (first, reason, sent) = lock.withLock { (firstFrameAt, endReason ?? lastError ?? "durduruldu", acksSent) }
        let lived = first > 0 ? "\((now - first) / 1000) sn" : "ilk kare yok"
        log("bağlantı bitti: \(reason); kare \(frames), onay \(sent), ilk kareden sonra \(lived)")
    }

    private func report(_ kind: XboxError, _ detail: String?, _ action: XboxSessionMachine.Action) {
        lock.withLock { lastError = "\(kind.rawValue): \(detail ?? "")" }
        if isStopped { return }
        log("hata (\(kind.rawValue)): \(detail ?? "")", 1)
        var delay: Int64?
        if case let .retry(value) = action { delay = value }
        var attempt = 0
        if case let .failed(_, _, _, value) = machine.phase { attempt = value }
        if let delay {
            log("yeniden deneme \(attempt)/\(machine.retryDelaysMs.count): \(delay / 1000) sn sonra")
        } else {
            log("yeniden denenmiyor, yayın durdu", 1)
        }
        listener.onFailed(kind, detail, delay != nil)
    }

    private func stopRemote() {
        guard let id = lock.withLock({ () -> String? in
            defer { sessionId = nil }
            return sessionId
        }) else { return }
        // Kopan bağlantının kapatılacak oturumu yoktur; hata önemsiz.
        try? home.stop(id)
    }

    // MARK: - WebRTC

    private static let sslReady: Bool = RTCInitializeSSL()

    private func createFactory() -> RTCPeerConnectionFactory {
        _ = Self.sslReady
        let decoders = XboxDecoderFactory(listener: { [weak self] rtpMs, arrivedAt, decodedAt in
            self?.decoded(rtpMs: rtpMs, arrivedAt: arrivedAt, decodedAt: decodedAt)
        }, onError: { [weak self] text in self?.log(text, 0) })
        log("çözücünün bildirdiği biçimler: " + (decoders.supportedCodecs().map(\.name).joined(separator: ", ").nonEmpty ?? "yok"))
        #if os(iOS)
        // Ses çalınmaz ve mikrofon açılmaz: sessiz ses aygıtı (AVAudioSession'a hiç dokunulmaz).
        return RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: decoders,
                                        audioDevice: XboxSilentAudioDevice())
        #else
        return RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: decoders)
        #endif
    }

    private func decoded(rtpMs: Int64, arrivedAt: Int64, decodedAt: Int64) {
        guard let start = lock.withLock({ acking ? clockStart : nil }) else { return }
        acks.offer(XboxFrameAck(serverDataKey: XboxFrameAckQueue.serverDataKey(rtpMs), arrivalMs: arrivedAt - start, decodedMs: decodedAt - start))
    }

    private func negotiate(_ id: String) throws {
        let negotiatedFrames = frameCounter.beginSession()
        let done = XboxLatch()
        let stoppedAlready = lock.withLock { () -> Bool in
            frameSession = negotiatedFrames
            ended = done
            endReason = nil
            lastError = nil
            firstFrameAt = 0
            acking = false
            ackSequence = 0
            acksSent = 0
            clockStart = xboxUptimeMs()
            frameErrorLogged = false
            return stopped
        }
        if stoppedAlready { return }
        acks.clear()

        let pcFactory = createFactory()
        factory = pcFactory
        let gathered = XboxLatch()
        let config = RTCConfiguration()
        config.iceServers = [RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"]),
                             RTCIceServer(urlStrings: ["stun:stun1.l.google.com:19302"])]
        config.sdpSemantics = .unifiedPlan
        let delegate = PeerDelegate(session: self, done: done, gathered: gathered)
        peerDelegate = delegate
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = pcFactory.peerConnection(with: config, constraints: constraints, delegate: delegate) else {
            throw XboxException(.service, "WebRTC bağlantısı kurulamadı")
        }
        peer = connection

        // Oynatıcıdaki sıra: önce veri kanalları, sonra ses (gönder-al) ve görüntü (yalnız al).
        for (name, proto) in XboxChannels.channels {
            let init_ = RTCDataChannelConfiguration()
            init_.isOrdered = true
            init_.protocol = proto
            guard let channel = connection.dataChannel(forLabel: name, configuration: init_) else {
                throw XboxException(.service, "Veri kanalı açılamadı: \(name)")
            }
            let observer = ChannelDelegate(name: name, session: self, done: done)
            channelDelegates.append(observer)
            lock.withLock { channels[name] = channel }
            channel.delegate = observer
        }
        let audioInit = RTCRtpTransceiverInit()
        audioInit.direction = .sendRecv
        let audio = connection.addTransceiver(of: .audio, init: audioInit)
        let videoInit = RTCRtpTransceiverInit()
        videoInit.direction = .recvOnly
        let video = connection.addTransceiver(of: .video, init: videoInit)
        audio?.receiver.track?.isEnabled = false
        guard let track = video?.receiver.track as? RTCVideoTrack else { throw XboxException(.service, "Görüntü alıcısı kurulamadı") }
        let sink = FrameRenderer { [weak self] frame in self?.received(frame, negotiatedFrames) }
        renderer = sink
        videoTrack = track
        track.add(sink)

        let offer: RTCSessionDescription = try awaitCallback("SDP teklifi") { finish in
            connection.offer(for: constraints) { description, error in
                if let description { finish(.success(description)) } else { finish(.failure(CallbackFailure(error))) }
            }
        }
        let munged = RTCSessionDescription(type: .offer, sdp: XboxChannels.mungeOffer(offer.sdp))
        let _: Bool = try awaitCallback("yerel SDP") { finish in
            connection.setLocalDescription(munged) { error in finish(error == nil ? .success(true) : .failure(CallbackFailure(error))) }
        }
        log("SDP gönderiliyor")
        let answer = try home.sdp(id, offer: munged.sdp)
        let _: Bool = try awaitCallback("Xbox SDP") { finish in
            connection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: answer)) { error in
                finish(error == nil ? .success(true) : .failure(CallbackFailure(error)))
            }
        }
        // Yerel adaylar toplanana kadar (en çok 5 sn) beklenir; yavaş ağ bağdaştırıcılarının adayları da gitsin.
        if gathered.wait(Self.iceGatherMs) == false, isStopped { throw XboxCancelled() }
        let candidates = delegate.candidates
        log("ICE: \(candidates.count) yerel aday gönderiliyor")
        let remote = try home.ice(id, candidates)
        var added = 0
        for candidate in remote {
            guard let line = candidate.sdpLine else { continue }
            let ok: Bool = (try? awaitCallback("ICE adayı", timeoutMs: 2000) { finish in
                connection.add(RTCIceCandidate(sdp: line, sdpMLineIndex: Int32(candidate.sdpMLineIndex), sdpMid: candidate.sdpMid ?? "")) { error in
                    finish(.success(error == nil))
                }
            }) ?? false
            if ok { added += 1 }
        }
        log("ICE: Xbox'tan \(remote.count) aday, \(added) eklendi")

        let negotiatedAt = xboxUptimeMs()
        while !isStopped {
            if done.wait(1000) { break }
            if frameCounter.framesInSession(negotiatedFrames) == 0 && xboxUptimeMs() - negotiatedAt > Self.firstFrameMs {
                lock.withLock { endReason = "Xbox'tan \(Self.firstFrameMs / 1000) sn içinde görüntü gelmedi" }
                throw XboxException(.consoleUnavailable, "Xbox'tan görüntü gelmedi")
            }
        }
    }

    /// Görüntü karesi (WebRTC'nin iş parçacığından).
    private func received(_ frame: RTCVideoFrame, _ session: XboxFrameCounter.Session) {
        if isStopped { return }
        guard let sessionFrames = frameCounter.recordFrame(session) else { return }
        let now = xboxUptimeMs()
        if sessionFrames == 1 {
            lock.withLock { firstFrameAt = now }
            log("ilk kare \(frame.width)x\(frame.height)")
            frameCounter.runIfCurrent(session) {
                if !isStopped && machine.streaming() { listener.onStreaming() }
            }
        }
        // Her kareyi çevirmeye gerek yok: okuma saniyede birkaç kare ister.
        let due = lock.withLock { () -> Bool in
            guard now - lastDelivered >= frameIntervalMs else { return false }
            lastDelivered = now
            return true
        }
        guard due else { return }
        guard let image = XboxFrames.image(from: frame) else {
            let first = lock.withLock { () -> Bool in
                defer { frameErrorLogged = true }
                return !frameErrorLogged
            }
            if first { log("kare işlenemedi (\(frame.width)x\(frame.height), \(type(of: frame.buffer)))", 0) }
            return
        }
        if frameCounter.framesInSession(session) > 0 && !isStopped { listener.onFrame(image) }
    }

    fileprivate func channelStateChanged(_ name: String, _ channel: RTCDataChannel, _ done: XboxLatch) {
        let state = channel.readyState
        log("kanal \(name): \(Self.describe(state))")
        if state == .open && name == "message" { sendText("message", XboxChannels.handshake) }
        if state == .open && name == "input" { startKeepalive() }
        if state == .closed && name == "input" {
            lock.withLock { endReason = "Xbox görüntü bağlantısı kapandı" }
            done.open()
        }
    }

    fileprivate func channelMessage(_ name: String, _ buffer: RTCDataBuffer) {
        let bytes = buffer.data
        if buffer.isBinary {
            // input kanalından gelenler: 16 = ServerMetadata, 128 = titreşim (her an gelir, yazılmaz).
            let type = bytes.first.map(Int.init) ?? -1
            if type != 128 && type != 16 { log("kanal \(name): ikili ileti, tür \(type), \(bytes.count) bayt") }
            return
        }
        let text = String(decoding: bytes, as: UTF8.self)
        guard name == "message", XboxChannels.isHandshakeAck(text) else {
            // Xbox'ın kapatma bildirimi gibi iletiler (ör. TransactionStart) burada görünür.
            log("kanal \(name): \(text.prefix(300))")
            return
        }
        log("el sıkışma tamam")
        sendText("control", XboxChannels.authorization)
        sendText("control", XboxChannels.gamepadAdded)
        let start = lock.withLock { clockStart }
        send("input", XboxChannels.clientMetadataPacket(sequence: 0, timestampMs: Double(xboxUptimeMs() - start)), binary: true)
        XboxChannels.configurationMessages().forEach { sendText("message", $0) }
        startAcks()
    }

    fileprivate func connectionChanged(_ state: RTCPeerConnectionState, _ done: XboxLatch) {
        log("bağlantı: \(Self.describe(state))")
        if state == .failed || state == .closed {
            lock.withLock { endReason = "Xbox WebRTC bağlantısı kesildi (\(Self.describe(state)))" }
            done.open()
        }
    }

    fileprivate func iceChanged(_ state: RTCIceConnectionState) {
        log("ICE: \(Self.describe(state))")
    }

    private func sendText(_ name: String, _ text: String) { send(name, Data(text.utf8), binary: false) }

    private func send(_ name: String, _ bytes: Data, binary: Bool) {
        guard let channel = lock.withLock({ channels[name] }), channel.readyState == .open else { return }
        channel.sendData(RTCDataBuffer(data: bytes, isBinary: binary))
    }

    /// El sıkışmadan sonra çözülen karelerin onayı 16 ms'de bir `input` kanalına gider (oynatıcıdaki döngü).
    private func startAcks() {
        acks.clear()
        let timer = DispatchSource.makeTimerSource(queue: ackQueue)
        let previous = lock.withLock { () -> DispatchSourceTimer?? in
            if stopped { return .none }
            acking = true
            defer { ackTimer = timer }
            return .some(ackTimer)
        }
        guard let previous else { return }
        previous?.cancel()
        let interval = DispatchTimeInterval.milliseconds(Int(Self.ackIntervalMs))
        timer.schedule(deadline: .now() + interval, repeating: interval, leeway: .milliseconds(2))
        timer.setEventHandler { [weak self] in self?.flushAcks() }
        timer.resume()
    }

    private func flushAcks() {
        let frames = acks.drain()
        if frames.isEmpty { return }
        let (sequence, start) = lock.withLock { () -> (Int, Int64) in
            ackSequence += 1
            acksSent += Int64(frames.count)
            return (ackSequence, clockStart)
        }
        send("input", XboxChannels.frameAckPacket(sequence: sequence, timestampMs: Double(xboxUptimeMs() - start), frames: frames), binary: true)
        if sequence == 1 { log("kare onayı başladı") }
    }

    private func startKeepalive() {
        let timer = DispatchSource.makeTimerSource(queue: keepaliveQueue)
        let previous = lock.withLock { () -> DispatchSourceTimer?? in
            if stopped { return .none }
            defer { keepaliveTimer = timer }
            return .some(keepaliveTimer)
        }
        guard let previous else { return }
        previous?.cancel()
        let interval = DispatchTimeInterval.milliseconds(Int(Self.keepaliveMs))
        timer.schedule(deadline: .now() + interval, repeating: interval, leeway: .seconds(1))
        timer.setEventHandler { [weak self] in
            guard let self, let id = self.lock.withLock({ self.sessionId }) else { return }
            do {
                try self.home.keepalive(id)
            } catch {
                self.log("canlı tutma: \(error)", 1)
            }
        }
        timer.resume()
    }

    private func closePeer() {
        let (session, timers, open) = lock.withLock { () -> (XboxFrameCounter.Session?, [DispatchSourceTimer], [RTCDataChannel]) in
            let session = frameSession
            frameSession = nil
            acking = false
            let timers = [ackTimer, keepaliveTimer].compactMap { $0 }
            ackTimer = nil
            keepaliveTimer = nil
            let open = Array(channels.values)
            channels.removeAll()
            ended = nil
            return (session, timers, open)
        }
        if let session { frameCounter.endSession(session) }
        timers.forEach { $0.cancel() }
        acks.clear()
        // WebRTC çağrıları kilit dışında: kapatma, temsilcileri kendi iş parçacığında çağırabilir.
        for channel in open {
            channel.delegate = nil
            channel.close()
        }
        channelDelegates.removeAll()
        if let videoTrack, let renderer { videoTrack.remove(renderer) }
        videoTrack = nil
        renderer = nil
        peer?.delegate = nil
        peer?.close()
        peer = nil
        peerDelegate = nil
        factory = nil
    }

    /// WebRTC'nin geri çağırmalı işlemini bekler (en çok 10 sn).
    private func awaitCallback<T>(_ what: String, timeoutMs: Int64 = XboxStreamSession.callbackTimeoutMs,
                                  _ start: (@escaping (Result<T, CallbackFailure>) -> Void) -> Void) throws -> T {
        let box = CallbackBox<T>()
        let latch = XboxLatch()
        start { result in
            box.lock.withLock { if box.result == nil { box.result = result } }
            latch.open()
        }
        if !latch.wait(timeoutMs) { throw XboxException(.timeout, "\(what) zaman aşımı") }
        switch box.lock.withLock({ box.result })! {
        case .success(let value): return value
        case .failure(let failure): throw XboxException(.service, "\(what): \(failure.underlying?.localizedDescription ?? "?")")
        }
    }

    // MARK: - Günlük adları (Android'deki enum adları gibi)

    static func describe(_ state: RTCPeerConnectionState) -> String {
        switch state {
        case .new: return "NEW"
        case .connecting: return "CONNECTING"
        case .connected: return "CONNECTED"
        case .disconnected: return "DISCONNECTED"
        case .failed: return "FAILED"
        case .closed: return "CLOSED"
        @unknown default: return "\(state.rawValue)"
        }
    }

    static func describe(_ state: RTCIceConnectionState) -> String {
        switch state {
        case .new: return "NEW"
        case .checking: return "CHECKING"
        case .connected: return "CONNECTED"
        case .completed: return "COMPLETED"
        case .failed: return "FAILED"
        case .disconnected: return "DISCONNECTED"
        case .closed: return "CLOSED"
        case .count: return "COUNT"
        @unknown default: return "\(state.rawValue)"
        }
    }

    static func describe(_ state: RTCDataChannelState) -> String {
        switch state {
        case .connecting: return "CONNECTING"
        case .open: return "OPEN"
        case .closing: return "CLOSING"
        case .closed: return "CLOSED"
        @unknown default: return "\(state.rawValue)"
        }
    }
}

private final class CallbackBox<T>: @unchecked Sendable {
    let lock = NSLock()
    var result: Result<T, CallbackFailure>?
}

/// WebRTC geri çağrısının hatası (nil olabilir).
struct CallbackFailure: Error {
    let underlying: Error?
    init(_ underlying: Error?) { self.underlying = underlying }
}

// MARK: - WebRTC temsilcileri

/// Bağlantı olayları; yerel adayları toplar.
private final class PeerDelegate: NSObject, RTCPeerConnectionDelegate, @unchecked Sendable {
    weak var session: XboxStreamSession?
    let done: XboxLatch
    let gathered: XboxLatch
    private let lock = NSLock()
    private var local: [XboxIceCandidate] = []

    init(session: XboxStreamSession, done: XboxLatch, gathered: XboxLatch) {
        self.session = session
        self.done = done
        self.gathered = gathered
    }

    var candidates: [XboxIceCandidate] { lock.withLock { local } }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        session?.iceChanged(newState)
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        if newState == .complete { gathered.open() }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        lock.withLock { local.append(XboxIceCandidate(candidate: candidate.sdp, sdpMid: candidate.sdpMid, sdpMLineIndex: Int(candidate.sdpMLineIndex))) }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        session?.connectionChanged(newState, done)
    }
}

private final class ChannelDelegate: NSObject, RTCDataChannelDelegate, @unchecked Sendable {
    let name: String
    weak var session: XboxStreamSession?
    let done: XboxLatch

    init(name: String, session: XboxStreamSession, done: XboxLatch) {
        self.name = name
        self.session = session
        self.done = done
    }

    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        session?.channelStateChanged(name, dataChannel, done)
    }

    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        session?.channelMessage(name, buffer)
    }
}

/// Görüntü izinin kareleri buraya gelir (WebRTC'nin çözücü iş parçacığında).
final class FrameRenderer: NSObject, RTCVideoRenderer, @unchecked Sendable {
    private let onFrame: (RTCVideoFrame) -> Void

    init(_ onFrame: @escaping (RTCVideoFrame) -> Void) {
        self.onFrame = onFrame
    }

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        if let frame { onFrame(frame) }
    }
}

/// Bir kez açılan kapı: `open()` bütün bekleyenleri bırakır, sonraki beklemeler hemen döner.
final class XboxLatch: @unchecked Sendable {
    private let condition = NSCondition()
    private var isOpen = false

    func open() {
        condition.lock()
        isOpen = true
        condition.broadcast()
        condition.unlock()
    }

    /// Açıldıysa true; `ms` dolduysa false.
    @discardableResult
    func wait(_ ms: Int64) -> Bool {
        let deadline = Date(timeIntervalSinceNow: Double(ms) / 1000)
        condition.lock()
        defer { condition.unlock() }
        while !isOpen {
            if !condition.wait(until: deadline) { return isOpen }
        }
        return true
    }
}

#if os(iOS)
/// Sessiz ses aygıtı: WebRTC'nin sesi çalmaması ve mikrofonu açmaması için (Android'de JavaAudioDeviceModule'ün
/// hoparlörü ve mikrofonu kısılır). Oynatma "başlar" ama hiç veri çekilmez; kayıt hiç başlamaz.
final class XboxSilentAudioDevice: NSObject, RTCAudioDevice, @unchecked Sendable {
    private let lock = NSLock()
    private var initialized = false
    private var playoutInitialized = false
    private var playing = false

    var deviceInputSampleRate: Double { 48000 }
    var inputIOBufferDuration: TimeInterval { 0.01 }
    var inputNumberOfChannels: Int { 1 }
    var inputLatency: TimeInterval { 0 }
    var deviceOutputSampleRate: Double { 48000 }
    var outputIOBufferDuration: TimeInterval { 0.01 }
    var outputNumberOfChannels: Int { 1 }
    var outputLatency: TimeInterval { 0 }
    var isInitialized: Bool { lock.withLock { initialized } }
    var isPlayoutInitialized: Bool { lock.withLock { playoutInitialized } }
    var isPlaying: Bool { lock.withLock { playing } }
    var isRecordingInitialized: Bool { false }
    var isRecording: Bool { false }

    func initialize(with delegate: RTCAudioDeviceDelegate) -> Bool {
        lock.withLock { initialized = true }
        return true
    }

    func terminateDevice() -> Bool {
        lock.withLock {
            initialized = false
            playoutInitialized = false
            playing = false
        }
        return true
    }

    func initializePlayout() -> Bool {
        lock.withLock { playoutInitialized = true }
        return true
    }

    func startPlayout() -> Bool {
        lock.withLock { playing = true }
        return true
    }

    func stopPlayout() -> Bool {
        lock.withLock { playing = false }
        return true
    }

    func initializeRecording() -> Bool { false }
    func startRecording() -> Bool { false }
    func stopRecording() -> Bool { true }
}
#endif

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
