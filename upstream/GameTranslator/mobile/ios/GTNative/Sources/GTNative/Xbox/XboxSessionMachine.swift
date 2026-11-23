// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Ev yayını oturumunun durum makinesi (Android: xbox/XboxSessionMachine.kt; ağdan bağımsız, birim testli). Kurallar:
/// `/state` saniyede bir, en çok 90 kez sorulur; `ReadyToConnect` görülünce aktarım jetonu gönderilir, `Provisioned`
/// olunca WebRTC el sıkışması başlar. Hata ya da kopmada 5, 15, 45 sn sonra baştan denenir; üç ardışık denemeden sonra
/// durulur. Konsol `Failed` ya da `errorDetails` dönerse, yeniden giriş gerekirse hiç denenmez. Yayın `healthyStreamMs`
/// ayakta kaldıysa deneme sayısı sıfırlanır.
final class XboxSessionMachine: @unchecked Sendable {
    enum Phase: Equatable {
        case idle
        case starting
        case provisioning(polls: Int, connectSent: Bool)
        case negotiating
        case streaming
        /// `attempt`: bu kopmadan sonraki ardışık deneme (1…3); denenmiyorsa 0.
        case failed(kind: XboxError, message: String, willRetry: Bool, attempt: Int)
        case stopped
    }

    enum Action: Equatable {
        /// `/play` gönder.
        case play
        /// `delayMs` bekle, `/state` sor.
        case pollState(delayMs: Int64)
        /// Aktarım jetonuyla `/connect` gönder, sonra yine sor.
        case connect
        /// SDP ve ICE el sıkışması.
        case negotiate
        /// Oturumu kapat (`delayMs` sonra baştan başla; nil ise durul).
        case retry(delayMs: Int64?)
        case nothing
    }

    /// Yeniden denemenin işe yaramadığı hatalar: kullanıcı bir şey yapmalı.
    static let fatal: Set<XboxError> = [.signInRequired, .noXboxAccount]

    let maxPolls: Int
    let pollIntervalMs: Int64
    let retryDelaysMs: [Int64]
    let healthyStreamMs: Int64
    private let clock: () -> Int64

    private let lock = NSLock()
    private var current: Phase = .idle
    /// Ardışık başarısız deneme sayısı.
    private var failures = 0
    private var streamingSince: Int64 = 0

    init(maxPolls: Int = 90, pollIntervalMs: Int64 = 1000, retryDelaysMs: [Int64] = [5000, 15000, 45000],
         healthyStreamMs: Int64 = 60_000, clock: @escaping () -> Int64 = XboxAuth.wallClock) {
        self.maxPolls = maxPolls
        self.pollIntervalMs = pollIntervalMs
        self.retryDelaysMs = retryDelaysMs
        self.healthyStreamMs = healthyStreamMs
        self.clock = clock
    }

    var phase: Phase { lock.withLock { current } }

    func start() -> Action {
        lock.withLock {
            if current == .stopped { return .nothing }
            current = .starting
            return .play
        }
    }

    func played() -> Action {
        lock.withLock {
            guard current == .starting else { return .nothing }
            current = .provisioning(polls: 0, connectSent: false)
            return .pollState(delayMs: 0)
        }
    }

    /// `/state` yanıtı geldi.
    func status(_ state: String, _ errorDetails: String?) -> Action {
        lock.withLock {
            guard case let .provisioning(polls, connectSent) = current else { return .nothing }
            // Konsol yayını kendisi reddetti: yeniden denemek konsolu yeniden tetikler, denenmez.
            if state == "Failed" || (state != "Provisioned" && Self.hasErrorDetails(errorDetails)) {
                return failLocked(Self.classifyFailure(errorDetails), errorDetails ?? "Xbox yayını açılamadı", permanent: true)
            }
            switch state {
            case "Provisioned":
                current = .negotiating
                return .negotiate
            case "ReadyToConnect" where !connectSent:
                current = .provisioning(polls: polls, connectSent: true)
                return .connect
            default:
                return pollLocked(polls, connectSent)
            }
        }
    }

    /// `/connect` gönderildi.
    func connected() -> Action {
        lock.withLock {
            guard case let .provisioning(polls, connectSent) = current else { return .nothing }
            return pollLocked(polls, connectSent)
        }
    }

    private func pollLocked(_ polls: Int, _ connectSent: Bool) -> Action {
        let next = polls + 1
        if next >= maxPolls { return failLocked(.timeout, "Xbox yayını hazır olmayı beklerken zaman aşımına uğradı", permanent: false) }
        current = .provisioning(polls: next, connectSent: connectSent)
        return .pollState(delayMs: pollIntervalMs)
    }

    /// İlk kare geldi.
    @discardableResult
    func streaming() -> Bool {
        lock.withLock {
            guard current == .negotiating else { return false }
            current = .streaming
            streamingSince = clock()
            return true
        }
    }

    /// `permanent`: konsol yayını reddetti (`Failed` ya da `errorDetails`); yeniden denenmez.
    func fail(_ kind: XboxError, _ message: String, permanent: Bool = false) -> Action {
        lock.withLock { failLocked(kind, message, permanent: permanent) }
    }

    private func failLocked(_ kind: XboxError, _ message: String, permanent: Bool) -> Action {
        if current == .stopped { return .nothing }
        // Yayın en az `healthyStreamMs` sürdüyse bu kopma yeni bir dizinin ilkidir.
        if streamingSince > 0 && clock() - streamingSince >= healthyStreamMs { failures = 0 }
        streamingSince = 0
        failures += 1
        let retry = !permanent && !Self.fatal.contains(kind) && failures <= retryDelaysMs.count
        current = .failed(kind: kind, message: message, willRetry: retry, attempt: retry ? failures : 0)
        return .retry(delayMs: retry ? retryDelaysMs[failures - 1] : nil)
    }

    func stop() {
        lock.withLock { current = .stopped }
    }

    private static let emptyDetails = try! NSRegularExpression(pattern: #"^\{("[^"]*":(null|""),?)*\}$"#)

    /// `errorDetails` gerçek bir hata mı (boş nesne ya da tüm alanları null olan nesne sayılmaz).
    static func hasErrorDetails(_ details: String?) -> Bool {
        let text = (details ?? "").replacingOccurrences(of: " ", with: "")
        if text.isEmpty || text == "{}" || text == "null" { return false }
        return emptyDetails.firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length)) == nil
    }

    /// `errorDetails` içinden anlaşılan neden.
    static func classifyFailure(_ details: String?) -> XboxError {
        let text = details ?? ""
        func has(_ word: String) -> Bool { text.range(of: word, options: .caseInsensitive) != nil }
        if has("InUse") || has("Busy") || has("AlreadyStreaming") { return .busy }
        // Kapalı, bulunamadı, uzaktan oynatma kapalı ve tanınmayan neden: hepsi "Xbox'a ulaşılamadı".
        return .consoleUnavailable
    }
}

/// Ömür boyu tanı sayacını korurken her yeni WebRTC bağlantısının ilk karesini ayrı tanır.
final class XboxFrameCounter: @unchecked Sendable {
    final class Session {}

    private let lock = NSLock()
    private var total: Int64 = 0
    private var currentSession: Session?
    private var currentSessionFrames: Int64 = 0

    var totalFrames: Int64 { lock.withLock { total } }

    func beginSession() -> Session {
        lock.withLock {
            let session = Session()
            currentSession = session
            currentSessionFrames = 0
            return session
        }
    }

    func endSession(_ session: Session) {
        lock.withLock {
            if currentSession === session {
                currentSession = nil
                currentSessionFrames = 0
            }
        }
    }

    /// Bu bağlantıdaki kaçıncı kare; bağlantı kapanmışsa nil.
    func recordFrame(_ session: Session) -> Int64? {
        lock.withLock {
            guard currentSession === session else { return nil }
            total += 1
            currentSessionFrames += 1
            return currentSessionFrames
        }
    }

    func framesInSession(_ session: Session) -> Int64 {
        lock.withLock { currentSession === session ? currentSessionFrames : 0 }
    }

    func runIfCurrent(_ session: Session, _ action: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        if currentSession === session { action() }
    }
}
