// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import GameTranslatorCore
import Network

/// Yerel ağdaki GameTranslator sunucusunun adresi (Android: core/ServerAddress.kt). Yalnız özel ağ adresleri kabul edilir.
public struct ServerAddress: Equatable, Sendable, CustomStringConvertible {
    public static let defaultPort = 8787

    public let host: String
    public let port: Int

    public init(host: String, port: Int = ServerAddress.defaultPort) {
        self.host = host
        self.port = port
    }

    public var description: String { "\(host):\(port)" }

    /// "192.168.1.5", "192.168.1.5:8787" ya da "http://192.168.1.5:8787/ekran"; yerel ağ adresi değilse nil.
    public static func parse(_ text: String) -> ServerAddress? {
        var trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("http://") { trimmed.removeFirst("http://".count) }
        if let slash = trimmed.firstIndex(of: "/") { trimmed = String(trimmed[..<slash]) }
        let parts = trimmed.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        let host = String(parts[0])
        var port = defaultPort
        if parts.count == 2 {
            guard let value = Int(parts[1]) else { return nil }
            port = value
        }
        guard (1...65535).contains(port), isLocal(host) else { return nil }
        return ServerAddress(host: host, port: port)
    }

    static func isLocal(_ host: String) -> Bool {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4, parts.allSatisfy({ !$0.isEmpty && $0.count <= 3 && $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) }) else {
            return false
        }
        let n = parts.compactMap { Int($0) }
        guard n.count == 4, n.allSatisfy({ $0 <= 255 }) else { return false }
        return n[0] == 10 || (n[0] == 172 && (16...31).contains(n[1])) || (n[0] == 192 && n[1] == 168) || (n[0] == 169 && n[1] == 254)
    }

    /// Bağlanılacak sunucu: son adres çeviri yapıyorsa o; değilse ağda çeviri yapan, o da yoksa son adres (yanıt verdiyse)
    /// ya da bulunan herhangi biri. `probe`: true çeviri yapıyor, false açık ama çeviri yapmıyor, nil yanıt yok.
    static func choose(last: ServerAddress?, probe: (ServerAddress) -> Bool?, scan: () -> OtherServers.Found?) -> (ServerAddress, Bool)? {
        let lastActive = last.flatMap(probe)
        if let last, lastActive == true { return (last, true) }
        let scanned = scan()
        if let scanned, scanned.active { return (ServerAddress(host: scanned.host), true) }
        if let last, lastActive != nil { return (last, false) }
        if let scanned { return (ServerAddress(host: scanned.host), false) }
        return nil
    }
}

/// Telefonun kendisinde (127.0.0.1) dinleyip her bağlantıyı yerel ağdaki bir GameTranslator sunucusuna olduğu gibi aktaran
/// köprü (Android: core/LoopbackRelay.kt). "Bilgisayarın altyazısını göster" ekranındaki tarayıcı bilgisayarın /ekran
/// sayfasını bunun üzerinden açar. Yalnız 127.0.0.1'de dinler.
public final class DisplayRelay: @unchecked Sendable {
    /// Önce denenen port: sayfa yazı boyunu adrese (porta) göre saklar, port aynı kaldıkça ayar hatırlanır.
    public static let preferredPort: UInt16 = 8788

    private let target: ServerAddress
    private let queue = DispatchQueue(label: "gt.relay")
    private var listener: NWListener?
    private var connections: [NWConnection] = []

    public init(target: ServerAddress) {
        self.target = target
    }

    /// Köprüyü başlatır ve dinlenen portu döner (bloklar, en çok 3 sn).
    public func start() throws -> Int {
        do {
            return try listen(on: Self.preferredPort)
        } catch {
            return try listen(on: 0)
        }
    }

    private func listen(on port: UInt16) throws -> Int {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port) ?? .any)
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters)
        let ready = DispatchSemaphore(value: 0)
        final class Outcome: @unchecked Sendable { var port: Int?; var failed = false }
        let outcome = Outcome()
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                outcome.port = listener.port.map { Int($0.rawValue) }
                ready.signal()
            case .failed, .cancelled:
                outcome.failed = true
                ready.signal()
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] client in self?.serve(client) }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 3) == .success, !outcome.failed, let bound = outcome.port, bound > 0 else {
            listener.cancel()
            throw NWError.posix(.EADDRINUSE)
        }
        queue.sync { self.listener = listener }
        return bound
    }

    public func stop() {
        queue.sync {
            listener?.cancel()
            listener = nil
            for connection in connections { connection.cancel() }
            connections.removeAll()
        }
    }

    private func serve(_ client: NWConnection) {
        let parameters = NWParameters.tcp
        if let tcp = parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options { tcp.noDelay = true }
        let upstream = NWConnection(host: NWEndpoint.Host(target.host), port: NWEndpoint.Port(rawValue: UInt16(target.port)) ?? 8787,
                                    using: parameters)
        connections.append(contentsOf: [client, upstream])
        let finish: @Sendable () -> Void = { [weak self] in
            client.cancel()
            upstream.cancel()
            self?.queue.async { self?.connections.removeAll { $0 === client || $0 === upstream } }
        }
        upstream.stateUpdateHandler = { state in
            if case .failed = state { finish() }
            // Hedefe 3 sn'de ulaşılamazsa tarayıcı sayfa hatası gösterir.
            if case .waiting = state { finish() }
        }
        client.stateUpdateHandler = { state in if case .failed = state { finish() } }
        client.start(queue: queue)
        upstream.start(queue: queue)
        pump(from: client, to: upstream, finish: finish)
        pump(from: upstream, to: client, finish: finish)
    }

    /// Bir yön bitince iki uç da kapanır (istek/yanıt ve olay akışı tek bağlantıdadır).
    private func pump(from source: NWConnection, to sink: NWConnection, finish: @escaping @Sendable () -> Void) {
        source.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, complete, error in
            if let data, !data.isEmpty {
                sink.send(content: data, completion: .contentProcessed { sendError in
                    if sendError != nil { return finish() }
                    if complete || error != nil { return finish() }
                    self?.pump(from: source, to: sink, finish: finish)
                })
            } else if complete || error != nil {
                finish()
            } else {
                self?.pump(from: source, to: sink, finish: finish)
            }
        }
    }
}

// MARK: - "Bilgisayarın altyazısını göster" (Android: MainActivity.findDisplayServer ve köprü)

extension Engine {
    /// Ekran olarak bağlanılacak GameTranslator sunucusunu arar (bloklar): en son bağlanılan adres çeviri yapıyorsa o;
    /// değilse telefonun /24 ağında çeviri yapan, o da yoksa bulunan herhangi biri.
    public func findDisplayServer() -> [String: Any] {
        let own = Self.localAddress()
        let choice = ServerAddress.choose(
            last: store.displayServer.flatMap(ServerAddress.parse),
            probe: { OtherServers.hello($0.host, port: $0.port) },
            scan: { own.flatMap { OtherServers.findDisplaySource(own: $0) } })
        guard let (address, active) = choice else { return ["found": false, "wifi": own != nil] }
        return ["found": true, "host": address.host, "port": address.port, "active": active, "wifi": own != nil]
    }

    /// Elle yazılan adresin denemesi.
    public func probeDisplayServer(_ text: String) -> [String: Any] {
        guard let address = ServerAddress.parse(text) else { return ["valid": false, "found": false] }
        let active = OtherServers.hello(address.host, port: address.port, timeoutMs: 2000)
        return ["valid": true, "found": active != nil, "host": address.host, "port": address.port, "active": active == true]
    }

    /// Köprüyü açar (önceki kapanır) ve telefondaki portu döner; adres son bağlanılan sunucu olarak saklanır.
    public func openDisplayRelay(host: String, port: Int) throws -> Int {
        guard let address = ServerAddress.parse("\(host):\(port)") else { throw RelayError.badAddress }
        store.displayServer = address.description
        closeDisplayRelay()
        let relay = DisplayRelay(target: address)
        let local = try relay.start()
        relayLock.withLock { displayRelay = relay }
        return local
    }

    public func closeDisplayRelay() {
        let open = relayLock.withLock { () -> DisplayRelay? in
            defer { displayRelay = nil }
            return displayRelay
        }
        open?.stop()
    }

    public enum RelayError: Error { case badAddress }
}
