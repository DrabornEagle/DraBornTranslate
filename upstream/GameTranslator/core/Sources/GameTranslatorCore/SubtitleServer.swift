// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

// Görünüm türleri (SubtitleStyle, SubtitleTextColor, SpeakerColors…) SubtitleStyle.swift'te, olay çerçeveleri
// SubtitleFrames.swift'te: ikisi de yalnız Foundation kullanır, her platformda sınanır. Sunucunun kendisi Apple'ın
// Network çerçevesiyle çalışır.

#if canImport(Network)
import Network

/// TV uygulamasının bağlandığı yerel Server-Sent Events sunucusu (Windows'taki SubtitleServer.cs ile aynı protokol).
/// `GET /events` akışında `subtitle`, `clear`, `style`, `region`, `screen`, `speech` ve `ping` olaylarını yayınlar;
/// `GET /hello` ile TV bilgisayarı ağda tanır, `GET /font/<sha1>.ttf` altyazı fontunu verir.
public final class SubtitleServer: @unchecked Sendable {
    public enum ServerError: Error { case failedToStart(String) }

    private let requestedPort: UInt16
    private let pingInterval: TimeInterval
    private let queue = DispatchQueue(label: "gametranslator.subtitle-server")
    private var listener: NWListener?
    private var clients: [ObjectIdentifier: NWConnection] = [:]
    /// OBS katmanı gibi TV olmayan izleyiciler: olayları alır ama "TV bağlı" sayısına katılmaz.
    private var viewers: Set<ObjectIdentifier> = []
    private var lastStyle: SubtitleStyle?
    /// OBS katmanı ve ikinci ekran gibi izleyicilerin görünümü (ayrı ayar yoksa TV'ninkiyle aynı).
    private var lastViewerStyle: SubtitleStyle?
    private var pingTimer: DispatchSourceTimer?
    /// `GET` ile verilen dosyalar: dublaj sesleri, seslendirme modelinin ses örnekleri ve fontlar.
    private var files: [String: (data: Data, type: String)] = [:]
    private var speechPaths: [String] = []
    private var speechNumber = 0
    /// Font adı (küçük harf) → sunucudaki adresi (nil: bulunamadı). Bir kez aranır.
    private var fontPaths: [String: String?] = [:]
    private let stableCover = StableCover()
    /// Oyunun altyazısının gerçek yeri (`source`) için ayrı zıplama önleyici.
    private let stableSource = StableCover()
    /// OBS'in görünümü TV'den farklıysa onun örtüsü ayrı izlenir.
    private let obsCover = StableCover()
    private let obsSource = StableCover()
    private var activeValue = false
    private var sendsToTvValue = true

    public var onClientsChanged: ((Int) -> Void)?

    /// Font adından bilgisayarda kurulu font dosyasını bulur (uygulama verir). Gömülü font (Quicksand) önce gelir.
    /// Bulunan dosya `/font/...` adresinden verilir; TV, OBS ve ikinci ekran `style` olayındaki `fontUrl`'den yükler.
    public var findFont: ((String) -> FontFile?)?

    public init(port: UInt16 = 8787, pingInterval: TimeInterval = 15) {
        requestedPort = port
        self.pingInterval = pingInterval
    }

    public var connectedClients: Int {
        queue.sync { clients.count - viewers.count }
    }

    /// Çeviri şu an çalışıyor mu (`/hello` yanıtında TV'ye bildirilir; ağda birden çok GameTranslator varsa TV çeviri yapana bağlanır).
    public var active: Bool {
        get { queue.sync { activeValue } }
        set { queue.sync { activeValue = newValue } }
    }

    /// false ise altyazı olayları TV'lere gitmez (altyazı yalnız bilgisayar ekranında); OBS katmanı almaya devam eder.
    public var sendsToTv: Bool {
        get { queue.sync { sendsToTvValue } }
        set { queue.sync { sendsToTvValue = newValue } }
    }

    /// OBS'de "Tarayıcı kaynağı" olarak eklenen altyazı katmanı (`GET /obs`).
    public static let obsOverlay: Data = {
        guard let url = Bundle.module.url(forResource: "obs-overlay", withExtension: "html"),
              let data = try? Data(contentsOf: url) else { return Data() }
        return data
    }()

    /// Sunucuyu başlatır ve dinlenen portu döner (`port: 0` ise rastgele seçilen port).
    public func start() async throws -> UInt16 {
        let port: NWEndpoint.Port = requestedPort == 0 ? .any : NWEndpoint.Port(rawValue: requestedPort)!
        // Sessizce kaybolan TV'ler (fişten çekme, Wi-Fi kopması) TCP canlılık denetimiyle fark edilir.
        let tcp = NWProtocolTCP.Options()
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 10
        tcp.keepaliveInterval = 5
        tcp.keepaliveCount = 3
        // Altyazı çerçeveleri küçüktür; Nagle beklemesi her satırı TV'ye geç ulaştırmasın.
        tcp.noDelay = true
        let listener = try NWListener(using: NWParameters(tls: nil, tcp: tcp), on: port)
        self.listener = listener
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }

        return try await withCheckedThrowingContinuation { continuation in
            // Durum değişiklikleri seri kuyrukta gelir; devamı yalnız bir kez çağırmak için saklanır.
            startContinuation = continuation
            listener.stateUpdateHandler = { [weak self] state in
                guard let self, let continuation = self.startContinuation else { return }
                switch state {
                case .ready:
                    self.startContinuation = nil
                    self.startPing()
                    continuation.resume(returning: listener.port?.rawValue ?? 0)
                case .failed(let error), .waiting(let error):
                    // .waiting: port ya da ağ şu an kullanılamıyor; beklemek yerine hata ver ki çağıran başka port denesin.
                    self.startContinuation = nil
                    listener.cancel()
                    continuation.resume(throwing: ServerError.failedToStart(error.localizedDescription))
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
    }

    private var startContinuation: CheckedContinuation<UInt16, Error>?

    public func stop() {
        queue.sync {
            pingTimer?.cancel()
            pingTimer = nil
            listener?.cancel()
            listener = nil
            clients.values.forEach { $0.cancel() }
            clients.removeAll()
            viewers.removeAll()
            activeValue = false
        }
    }

    /// `box`: oyundaki İngilizce altyazının ekrandaki yeri (kenarlara oranla); TV çeviriyi buna göre yerleştirir.
    /// `paragraphs`: metin satır satır, sola yaslı gösterilir. `replaces`: satır az önce gösterilenin uzamış ya da
    /// düzelmiş okumasıdır; TV onu öncekinin üstüne yığmaz (`replace` alanı; eski TV uygulamaları yok sayar).
    public func sendSubtitle(_ text: String, durationMs: Int = 15000, box: TextBox? = nil, paragraphs: Bool = false, replaces: Bool = false) {
        let at = Int64(Date().timeIntervalSince1970 * 1000)
        queue.async {
            let style = self.lastStyle
            let viewerStyle = self.lastViewerStyle ?? self.lastStyle
            let tvFrame = SubtitleFrames.subtitle(text, durationMs: durationMs, box: box, style: style, paragraphs: paragraphs,
                                                  replaces: replaces, at: at, cover: self.stableCover, source: self.stableSource)
            // OBS'in görünümü TV'ninkiyle aynıysa aynı çerçeve gider; değilse örtü OBS'in yazı boyuna göre ayrı hesaplanır.
            let viewerFrame = box == nil || viewerStyle == style ? tvFrame
                : SubtitleFrames.subtitle(text, durationMs: durationMs, box: box, style: viewerStyle, paragraphs: paragraphs,
                                          replaces: replaces, at: at, cover: self.obsCover, source: self.obsSource)
            self.sendSubtitleFrames(tv: tvFrame, viewer: viewerFrame)
        }
    }

    public func clear() {
        broadcast(SubtitleFrames.clear, subtitle: true)
    }

    /// "Ekranı çevir" sonuçları (bkz. `SubtitleFrames.screen`). TV öncekinin yerine bunları koyar.
    public func sendScreen(_ items: [ScreenTranslation.Item], durationMs: Int) {
        broadcast(SubtitleFrames.screen(items, durationMs: durationMs), subtitle: true)
    }

    /// TV'deki "ekranı çevir" çevirilerini kaldırır (altyazıya dokunmaz).
    public func clearScreen() {
        broadcast(SubtitleFrames.screenClear, subtitle: true)
    }

    /// Sürekli okunan ek alanın çevirisi (boş metin: o alanın kutusu kalkar). Ana altyazıya dokunmaz (TV 1.0.11+).
    public func sendRegion(id: String, text: String, box: TextBox?, lineHeight: Double? = nil) {
        broadcast(SubtitleFrames.region(id: id, text: text, box: box, lineHeight: lineHeight), subtitle: true)
    }

    /// Bütün ek alan kutularını kaldırır.
    public func clearRegions() {
        broadcast(SubtitleFrames.regionClear, subtitle: true)
    }

    /// Dosyayı `path` adresinden verir ("/voices/male_1.wav").
    public func publish(_ data: Data, at path: String, type: String = "audio/wav") {
        queue.async { self.files[path] = (data, type) }
    }

    /// Dublaj sesini TV'ye gönderir: ses `/speech/<n>.wav` adresinden verilir, `speech` olayı adresi bildirir.
    /// Son 16 ses tutulur; TV kopup bağlanınca eskisini istemez.
    public func sendSpeech(_ wav: Data, durationMs: Int) {
        queue.async {
            self.speechNumber += 1
            let path = "/speech/\(self.speechNumber).wav"
            self.files[path] = (wav, "audio/wav")
            self.speechPaths.append(path)
            while self.speechPaths.count > 16 { self.files[self.speechPaths.removeFirst()] = nil }
            self.sendToAll(Data(SubtitleFrames.speech(url: path, durationMs: durationMs).utf8))
        }
    }

    /// TV'de çalan dublajı durdurur (yeni sahne, oturum sonu).
    public func stopSpeech() {
        broadcast(SubtitleFrames.speechStop)
    }

    /// `viewerStyle`: OBS katmanının ayrı görünümü; nil ise TV'ninkiyle aynı.
    public func sendStyle(_ style: SubtitleStyle, viewerStyle: SubtitleStyle? = nil) {
        queue.async {
            self.lastStyle = style
            self.lastViewerStyle = viewerStyle
            let frame = self.styleFrame(style)
            let viewerFrame = viewerStyle == nil || viewerStyle == style ? frame : self.styleFrame(viewerStyle!)
            self.sendFrames(tv: frame, viewer: viewerFrame, subtitle: false)
        }
    }

    /// Kurulan ya da kaldırılan font yeniden aransın (font menüsü açılınca).
    public func forgetFonts() {
        queue.async { self.fontPaths.removeAll() }
    }

    // MARK: - Fontlar (kuyrukta çağrılır)

    private func styleFrame(_ style: SubtitleStyle) -> String {
        let family = SubtitleFonts.family(style.font)
        return SubtitleFrames.style(style, fontUrl: family.flatMap { fontPath($0) })
    }

    /// Font dosyası sunucudaki adrese konur (adres dosyanın içeriğinden türer; aynı font için aynı kalır).
    private func fontPath(_ family: String) -> String? {
        let key = family.lowercased()
        if let known = fontPaths[key] { return known }
        let file = SubtitleFonts.embedded(family) ?? findFont?(family)
        var path: String?
        if let file, !file.data.isEmpty {
            let served = SubtitleFrames.fontPath(file)
            files[served] = (file.data, file.type)
            path = served
        }
        fontPaths[key] = .some(path)
        return path
    }

    // MARK: - Bağlantılar

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        readRequest(connection, buffer: Data())
    }

    private func readRequest(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            var buffer = buffer
            if let data { buffer.append(data) }
            if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(decoding: buffer[..<end.lowerBound], as: UTF8.self)
                self.route(connection, requestLine: head.components(separatedBy: "\r\n").first ?? "")
            } else if isComplete || error != nil || buffer.count > 65536 {
                connection.cancel()
            } else {
                self.readRequest(connection, buffer: buffer)
            }
        }
    }

    private func route(_ connection: NWConnection, requestLine: String) {
        let parts = requestLine.split(separator: " ")
        let target = parts.count > 1 ? String(parts[1]) : ""
        let path = target.components(separatedBy: "?")[0]
        let method = parts.first.map(String.init) ?? ""

        if method == "OPTIONS" {
            respondAndClose(connection, status: "204 No Content")
        } else if method == "GET" && path == "/events" {
            openStream(connection, viewer: target.contains("viewer=obs"))
        } else if method == "GET" && path == "/obs" {
            respondAndClose(connection, status: "200 OK", data: Self.obsOverlay, type: "text/html; charset=utf-8")
        } else if method == "GET" && path == "/hello" {
            // TV uygulaması ağdaki bilgisayarı bu yanıtla tanır.
            respondAndClose(connection, status: "200 OK", data: Data(SubtitleFrames.helloBody(active: activeValue).utf8),
                            type: "application/json; charset=utf-8")
        } else if method == "GET", let file = files[path] {
            respondAndClose(connection, status: "200 OK", data: file.data, type: file.type)
        } else {
            respondAndClose(connection, status: "404 Not Found")
        }
    }

    private func respondAndClose(_ connection: NWConnection, status: String, body: String = "") {
        respondAndClose(connection, status: status, data: Data(body.utf8), type: nil)
    }

    private func respondAndClose(_ connection: NWConnection, status: String, data: Data, type: String?) {
        var head = "HTTP/1.1 \(status)\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\n"
        if let type { head += "Content-Type: \(type)\r\nCache-Control: no-cache\r\n" }
        head += "Content-Length: \(data.count)\r\nConnection: close\r\n\r\n"
        connection.send(content: Data(head.utf8) + data, completion: .contentProcessed { _ in connection.cancel() })
    }

    private func openStream(_ connection: NWConnection, viewer: Bool = false) {
        var head = "HTTP/1.1 200 OK\r\n"
        head += "Content-Type: text/event-stream; charset=utf-8\r\n"
        head += "Cache-Control: no-cache, no-transform\r\n"
        head += "Connection: keep-alive\r\n"
        head += "Access-Control-Allow-Origin: *\r\n\r\n"
        head += "retry: 2000\n\n"
        head += ": ping\n\n"
        if let style = viewer ? lastViewerStyle ?? lastStyle : lastStyle {
            head += styleFrame(style)
        }
        connection.send(content: Data(head.utf8), completion: .contentProcessed { _ in })

        let id = ObjectIdentifier(connection)
        clients[id] = connection
        if viewer { viewers.insert(id) }
        notifyClientsChanged()
        watchForClose(connection, id: id)
    }

    /// İstemci bağlantıyı kapattığında listeden çıkarır.
    private func watchForClose(_ connection: NWConnection, id: ObjectIdentifier) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { [weak self] _, _, isComplete, error in
            guard let self else { return }
            if isComplete || error != nil {
                connection.cancel()
                self.viewers.remove(id)
                if self.clients.removeValue(forKey: id) != nil { self.notifyClientsChanged() }
            } else {
                self.watchForClose(connection, id: id)
            }
        }
    }

    private func notifyClientsChanged() {
        let count = clients.count - viewers.count
        if let handler = onClientsChanged { DispatchQueue.main.async { handler(count) } }
    }

    /// `subtitle`: altyazı olayı; altyazı TV'ye gitmiyorsa (`sendsToTv` false) yalnız izleyiciler (OBS) alır.
    /// Dublaj sesi, görünüm ve ping her zaman herkese gider.
    private func broadcast(_ frame: String, subtitle: Bool = false) {
        queue.async { self.sendFrames(tv: frame, viewer: frame, subtitle: subtitle) }
    }

    private func sendSubtitleFrames(tv: String, viewer: String) {
        sendFrames(tv: tv, viewer: viewer, subtitle: true)
    }

    /// TV'ler `tv` çerçevesini, izleyiciler (OBS) `viewer` çerçevesini alır. Kuyrukta çağrılır.
    private func sendFrames(tv: String, viewer: String, subtitle: Bool) {
        let tvData = Data(tv.utf8)
        let viewerData = tv == viewer ? tvData : Data(viewer.utf8)
        for (id, connection) in clients {
            let isViewer = viewers.contains(id)
            if !isViewer && subtitle && !sendsToTvValue { continue }
            send(isViewer ? viewerData : tvData, to: connection, id: id)
        }
    }

    /// Gönderim hatası alan bağlantı kopmuş sayılır ve listeden çıkarılır.
    private func sendToAll(_ data: Data) {
        for (id, connection) in clients { send(data, to: connection, id: id) }
    }

    private func send(_ data: Data, to connection: NWConnection, id: ObjectIdentifier) {
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            guard let self, error != nil else { return }
            connection.cancel()
            self.viewers.remove(id)
            if self.clients.removeValue(forKey: id) != nil { self.notifyClientsChanged() }
        })
    }

    private func startPing() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + pingInterval, repeating: pingInterval)
        timer.setEventHandler { [weak self] in self?.sendToAll(Data(SubtitleFrames.ping.utf8)) }
        timer.resume()
        pingTimer = timer
    }
}
#endif
