// SPDX-License-Identifier: AGPL-3.0-only

import CoreGraphics
import Darwin
import Foundation
import GameTranslatorCore
import ImageIO
import RemotePlay

/// Uygulamanın çekirdeği (Android: Engine.kt): PS5 Remote Play oturumunu açar, gelen karelerde altyazı bölgesini okur,
/// çevirir ve TV'deki uygulamaya (ve telefondaki ekrana) gönderir. Tek örnek; Flutter köprüsü durumu izler.
///
/// Durum anahtarları ve evreler Android'dekiyle aynıdır: idle, starting, connecting, streaming, reconnecting, stopping,
/// failed. ("waking" ayrı gösterilmez: uyandırmayı oturum kendisi yapar, o sırada evre "connecting"dir.)
public final class Engine: @unchecked Sendable {
    public static let shared = Engine()

    /// Yazı tanıma için saniyede en çok bu kadar kare okunur (Android ile aynı).
    static let maxFps = 5.0
    static let logLines = 300
    static let runningGameEvery: TimeInterval = 45

    // MARK: Durum (yalnız `stateLock` altında)

    private let stateLock = NSLock()
    private var phase = "idle"
    private var message = ""
    private var running = false
    private var lastSource = ""
    private var lastTranslation = ""
    private var lastRead = ""
    private var frameCount: Int64 = 0
    private var frameWidth = 0
    private var frameHeight = 0
    private var translatedCount = 0
    private var packHits = 0
    private var packGame = ""
    private var runningGame = ""
    private var serverPort = 0
    /// Ağda bulunan başka GameTranslator sunucusu (açık duran bilgisayar uygulaması); TV ona bağlanmış olabilir.
    private var otherServer: String?
    /// Uygulama arka plana gitti mi (dönüşte oturum yenilenir).
    private var wasInBackground = false

    /// Durum değişince ana iş parçacığında çağrılır (en çok ~10 Hz).
    public var onChanged: (([String: Any]) -> Void)? {
        get { stateLock.withLock { changedListener } }
        set { stateLock.withLock { changedListener = newValue } }
    }
    private var changedListener: (([String: Any]) -> Void)?

    /// "Bu telefonda göster" ekranı için altyazı olayları; ana iş parçacığında. Bağlanınca ekranda duran satır gönderilir.
    public var onSubtitle: (([String: Any]) -> Void)? {
        get { stateLock.withLock { subtitleListener } }
        set {
            stateLock.withLock { subtitleListener = newValue }
            if let newValue, let current = screenLine.current(now: Self.now()) {
                DispatchQueue.main.async { newValue(current) }
            }
        }
    }
    private var subtitleListener: (([String: Any]) -> Void)?

    /// Her yeni çeviri (kaynak satır, çevirisi); ana iş parçacığında. Mac'te dublaj bunu seslendirir.
    public var onPair: ((_ source: String, _ translation: String) -> Void)? {
        get { stateLock.withLock { pairListener } }
        set { stateLock.withLock { pairListener = newValue } }
    }
    private var pairListener: ((String, String) -> Void)?

    /// Ek alan kutuları (TV'ye giden `region` çerçevelerinin aynısı); ana iş parçacığında. Mac ekranındaki katman için.
    public var onRegion: ((RegionEvent) -> Void)? {
        get { stateLock.withLock { regionListener } }
        set { stateLock.withLock { regionListener = newValue } }
    }
    private var regionListener: ((RegionEvent) -> Void)?

    private func emitRegion(_ event: RegionEvent) {
        DispatchQueue.main.async { [weak self] in self?.onRegion?(event) }
    }

    // MARK: Parçalar

    public let store: Store
    let server = SubtitleServer()
    /// TV'ye ve OBS'e altyazı gönderen sunucu (Mac: dublaj sesi, ses dosyaları ve "ekranı çevir" için).
    public var subtitleServer: SubtitleServer { server }
    private let screenLine = ScreenLine()
    private let control = DispatchQueue(label: "gt.control")
    private let readQueue = DispatchQueue(label: "gt.reader", qos: .userInitiated)
    private var logs: [String] = []
    private let logLock = NSLock()
    private var notifyPending = false

    private var settings = Settings()
    private var session: StreamSession?
    private var pipeline: SubtitlePipeline?
    private var reader = VisionReader()
    private var scheduler = ReadScheduler()
    private var lastLines: [String] = []
    private var lastBox: TextBox?

    private let frameLock = NSLock()
    private var frame: CGImage?
    private var reading = false
    private var lastReadAt: TimeInterval = 0

    private var pool: LinePool?
    private var poolTimer: DispatchSourceTimer?
    private var gameTimer: DispatchSourceTimer?
    private var ps5Game: RunningGame?
    private var lastPackSource: String?
    /// Son paket eşleşmesinin paketi (oynanan oyunu anlamak için; ör. "gta-6").
    private var lastPackId: String?

    private let packLock = NSLock()
    private var packs: GamePackLibrary?
    private var packStore: PackStore?
    /// `packs` ve `packStore`'un dili (çeviri dili değişince yeniden kurulur).
    private var packsLanguage = PackStore.language
    /// Paketler açıldı mı (açılış ve sunucudan inen paketlerle yeniden yükleme sürerken false). Hazır değilken paket
    /// aranmaz, satır doğrudan çeviri motoruna gider: paketlerin açılması (zayıf telefonda saniyeler) çeviriyi bekletmesin.
    private var packsReady = false
    private var packRefreshing = false

    private var historyStore: PhoneHistory?
    private let historyLock = NSLock()
    private let historyQueue = DispatchQueue(label: "gt.history", qos: .utility)

    /// "Bilgisayarın altyazısını göster" ekranının köprüsü (açıksa).
    let relayLock = NSLock()
    var displayRelay: DisplayRelay?

    /// Uygulamanın dosya klasörü (geçmiş, ortak havuz kuyruğu).
    let filesDirectory: URL
    /// Uygulamayla gelen oyun paketlerinin klasörü (Runner'ın "packs" klasörü); yoksa paketsiz.
    public var bundledPacks: URL? = Bundle.main.url(forResource: "packs", withExtension: nil)
    /// "GameTranslator 1.8.48" gibi; havuza gönderilir.
    public var appVersion: String = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"

    public init(store: Store = Store(), filesDirectory: URL? = nil) {
        self.store = store
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        self.filesDirectory = filesDirectory ?? support.appendingPathComponent("GameTranslator", isDirectory: true)
        try? FileManager.default.createDirectory(at: self.filesDirectory, withIntermediateDirectories: true)
        settings = store.settings
        server.onClientsChanged = { [weak self] count in
            guard let self else { return }
            if count > 0 { self.stateLock.withLock { self.otherServer = nil } }
            self.notifyChanged()
        }
        RemotePlayLog.observe { [weak self] line in self?.log(2, "remoteplay: \(line)") }
    }

    private static func now() -> TimeInterval { ProcessInfo.processInfo.systemUptime }

    // MARK: - Durum

    public func state() -> [String: Any] {
        let address = Self.localAddress()
        var result: [String: Any] = stateLock.withLock {
            [
                "phase": phase, "message": message, "running": running, "tvClients": server.connectedClients,
                "otherServer": otherServer ?? NSNull(), "serverPort": serverPort, "localAddress": address ?? NSNull(),
                "lastSource": lastSource, "lastTranslation": lastTranslation, "lastRead": lastRead,
                "frames": frameCount, "frameWidth": frameWidth, "frameHeight": frameHeight, "translated": translatedCount,
                "packHits": packHits, "packGame": packGame, "runningGame": runningGame, "nativeAvailable": true,
            ]
        }
        #if DEBUG
        result.merge(stateLock.withLock { debugState }) { _, new in new }
        #endif
        return result
    }

    #if DEBUG
    /// Yalnız hata ayıklama: arayüze giden durumun üstüne yazılan alanlar (mağaza görselleri için).
    private var debugState: [String: Any] = [:]

    public func debugOverrideState(_ fields: [String: Any]) {
        stateLock.withLock { debugState.merge(fields) { _, new in new } }
        notifyChanged()
    }

    /// Yalnız hata ayıklama: son kare yerine verilen resim (önizleme ve bölge ayarı için).
    public func debugSetFrame(_ data: Data) {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return }
        frameLock.withLock { frame = image }
    }
    #endif

    public func logLines() -> [String] { logLock.withLock { logs } }

    /// Arayüz ve köprü tarafındaki olaylar da aynı günlüğe yazılır (Ayarlar > Günlük).
    public func note(_ level: Int, _ text: String) { log(level, text) }

    func log(_ level: Int, _ text: String) {
        let line = "\(level == 0 ? "E" : level == 1 ? "W" : "I") \(text)"
        #if DEBUG
        NSLog("GameTranslator %@", line)
        #endif
        logLock.withLock {
            logs.append(line)
            if logs.count > Self.logLines { logs.removeFirst(logs.count - Self.logLines) }
        }
    }

    private func setPhase(_ value: String, _ text: String = "") {
        stateLock.withLock {
            phase = value
            message = text
        }
        log(value == "failed" ? 0 : 2, "durum: \(value) \(text)".trimmingCharacters(in: .whitespaces))
        notifyChanged()
    }

    private func isRunning() -> Bool { stateLock.withLock { running } }

    /// Arayüze en çok ~10 Hz bildirim: kare sayacı her karede değişir.
    func notifyChanged() {
        let schedule = stateLock.withLock { () -> Bool in
            if notifyPending { return false }
            notifyPending = true
            return true
        }
        guard schedule else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
            guard let self else { return }
            let listener = self.stateLock.withLock { () -> (([String: Any]) -> Void)? in
                self.notifyPending = false
                return self.changedListener
            }
            listener?(self.state())
        }
    }

    // MARK: - Altyazının gittiği yerler: TV sunucusu ve telefondaki ekran

    private func showSubtitle(_ text: String, durationMs: Int, box: TextBox?, replaces: Bool) {
        server.sendSubtitle(text, durationMs: durationMs, box: box, replaces: replaces)
        var event = screenLine.show(text, durationMs: durationMs, replaces: replaces, now: Self.now())
        // Oyundaki altyazının karedeki yeri (Mac ekranındaki katman onu örtebilsin); yoksa alan yok.
        if let box { event["box"] = box.eventMap }
        DispatchQueue.main.async { [weak self] in self?.onSubtitle?(event) }
    }

    private func clearSubtitle() {
        server.clear()
        let event = screenLine.clear()
        DispatchQueue.main.async { [weak self] in self?.onSubtitle?(event) }
    }

    private final class Sink: SubtitleSink, @unchecked Sendable {
        weak var engine: Engine?
        init(_ engine: Engine) { self.engine = engine }
        func show(_ text: String, durationMs: Int, box: TextBox?, replaces: Bool) {
            engine?.showSubtitle(text, durationMs: durationMs, box: box, replaces: replaces)
        }
        func clear() { engine?.clearSubtitle() }
    }
    private lazy var sink = Sink(self)

    // MARK: - Başlat / durdur

    public func start() {
        log(2, "başlat istendi (durum \(stateLock.withLock { phase }), çalışıyor=\(isRunning()))")
        control.async { [self] in
            if isRunning() {
                log(1, "başlatma yok sayıldı: çeviri zaten açık görünüyor")
                return notifyChanged()
            }
            settings = store.settings
            let isXbox = store.consoleKind == Store.kindXbox
            if isXbox {
                // Xbox (Beta): Microsoft girişi ve seçili Xbox yeter; PSN, eşleştirme ve libchiaki gerekmez.
                guard XboxAccount.shared.selectedConsole != nil, XboxAccount.shared.signedIn else {
                    return setPhase("failed", UiText.code("need_xbox_setup"))
                }
            } else if store.console == nil || store.accountId == nil {
                return setPhase("failed", UiText.code("need_ps_setup"))
            }
            stateLock.withLock {
                running = true
                frameCount = 0
                translatedCount = 0
                packHits = 0
                packGame = ""
                runningGame = ""
            }
            ps5Game = nil
            setPhase("starting")
            refreshPacks()
            startServer()
            watchForOtherServer()
            buildPipeline()
            if isXbox {
                // Xbox'ta oyunun PS5 kimliği yoktur: önceki PS5 oturumundan kalan oyun havuz satırlarına yazılmasın.
                pool?.setGame(titleId: nil, name: nil)
                connectXbox()
            } else if let console = store.console, let account = store.accountId {
                watchRunningGame()
                connect(console, account: account)
            }
        }
    }

    // MARK: - Dış görüntü kaynağı (Mac: yakalama kartı, pencere, ekran)

    /// Açık dış kaynak; konsol oturumunda nil. Denetim kuyruğunda değişir, `stateLock` altında okunur.
    private var external: FrameSource?
    /// Dış kaynaktan bu bağlantıda kare geldi mi (ilk kare evreyi "streaming" yapar).
    private var externalStreaming = false

    /// Çeviriyi konsol yerine dış kaynakla başlatır. Evreler konsoldakiyle aynıdır: starting → connecting (kaynağın
    /// adı) → ilk karede streaming. PS5'te çalışan oyun sorgulanmaz; PS5/Xbox kurulumu gerekmez.
    public func start(source: FrameSource) {
        log(2, "başlat istendi: dış kaynak \(source.label)")
        control.async { [self] in
            if isRunning() {
                log(1, "başlatma yok sayıldı: çeviri zaten açık görünüyor")
                return notifyChanged()
            }
            settings = store.settings
            stateLock.withLock {
                running = true
                frameCount = 0
                translatedCount = 0
                packHits = 0
                packGame = ""
                runningGame = ""
            }
            ps5Game = nil
            setPhase("starting")
            refreshPacks()
            startServer()
            watchForOtherServer()
            buildPipeline()
            // Dış kaynakta oyunun PS5 kimliği yoktur: önceki PS5 oturumundan kalan oyun havuz satırlarına yazılmasın.
            pool?.setGame(titleId: nil, name: nil)
            stateLock.withLock {
                external = source
                externalStreaming = false
            }
            setPhase("connecting", source.label)
            source.start(into: self)
        }
    }

    /// Dış kaynağın karesi (herhangi bir iş parçacığından). Dış kaynak açık değilse yok sayılır.
    public func submit(frame image: CGImage) {
        let first = stateLock.withLock { () -> Bool? in
            guard running, external != nil else { return nil }
            defer { externalStreaming = true }
            return !externalStreaming
        }
        guard let first else { return }
        if first { setPhase("streaming") }
        onFrame(image)
    }

    /// Dış kaynakta hata. `willRetry`: kaynak kendisi yeniden deniyor (evre "reconnecting", sonraki kare "streaming"
    /// yapar); değilse kaynak kapanır, çeviri durur ve evre "failed" olur (`message` arayüzde gösterilir).
    public func sourceFailed(_ message: String, willRetry: Bool = false) {
        if willRetry {
            let current = stateLock.withLock { () -> Bool in
                guard running, external != nil else { return false }
                externalStreaming = false
                return true
            }
            if current { setPhase("reconnecting", message) }
            return
        }
        control.async { [self] in
            guard let source = stateLock.withLock({ external }) else { return }
            stateLock.withLock {
                external = nil
                running = false
            }
            source.stop()
            setPhase("failed", message)
        }
    }

    private var hasExternal: Bool { stateLock.withLock { external != nil } }

    public func stop() {
        let active = isRunning() || session != nil || xbox != nil || hasExternal
        if active { setPhase("stopping") }
        control.async { [self] in
            guard isRunning() || session != nil || xbox != nil || hasExternal else { return setPhase("idle") }
            stateLock.withLock { running = false; runningGame = "" }
            let source = stateLock.withLock { () -> FrameSource? in
                defer { external = nil }
                return external
            }
            source?.stop()
            cancelScreen()
            poolTimer?.cancel()
            poolTimer = nil
            gameTimer?.cancel()
            gameTimer = nil
            if let pool { DispatchQueue.global(qos: .utility).async { pool.flush() } }
            session?.stop()
            session = nil
            closeXbox()
            pipeline?.reset()
            pipeline = nil
            regionWatcher?.reset()
            regionWatcher = nil
            resetAutoRegion()
            server.clearRegions()
            emitRegion(.clearAll)
            clearSubtitle()
            server.stop()
            stateLock.withLock { serverPort = 0; otherServer = nil }
            frameLock.withLock { frame = nil }
            setPhase("idle")
        }
    }

    /// Uygulama yeniden öne geldi. iPhone arka plandaki uygulamayı askıya alır (Android'deki ön plan hizmeti yok); dönüşte
    /// kopmuş oturum beklemeden yeniden kurulur, altyazı sunucusu TV'lere stili yeniden gönderir.
    public func resumed() {
        // Açılışta da "öne geldi" bildirimi gelir; oturum yalnız gerçekten arka plandan dönülünce yenilenir (yoksa
        // ilk bağlantı yarıda kesilip PS5 "başka cihazda kullanılıyor" diyordu).
        let returning = stateLock.withLock { () -> Bool in
            defer { wasInBackground = false }
            return wasInBackground
        }
        guard returning else { return }
        control.async { [self] in
            guard isRunning(), let session else { return }
            log(2, "uygulama öne geldi: PS5 oturumu yenileniyor")
            session.reconnectNow()
            sendStyle()
        }
    }

    /// Uygulama arka plana gitti (çeviri birkaç saniye içinde askıya alınır).
    public func suspended() {
        stateLock.withLock { wasInBackground = true }
        guard isRunning() else { return }
        log(1, "uygulama arka planda: iPhone çeviriyi askıya alır; GameTranslator'ı açık tutun")
    }

    /// Ayarlar değişti: görünüm hemen TV'ye gider; dil ya da çeviri motoru değiştiyse hat yeniden kurulur.
    public func applySettings() {
        control.async { [self] in
            let before = settings
            let now = store.settings
            settings = now
            guard isRunning() else { return }
            if now.style != before.style { sendStyle() }
            if now.sharePool != before.sharePool { pool?.setEnabled(now.sharePool) }
            if now.engine != before.engine || now.deepLKey != before.deepLKey || now.sourceLanguage != before.sourceLanguage
                || now.targetLanguage != before.targetLanguage || now.glossary != before.glossary || now.packsEnabled != before.packsEnabled {
                buildPipeline()
                // Çeviri dili değişti: o dilin paketleri (ilk kez ya da süresi geldiyse) iner.
                if now.targetLanguage != before.targetLanguage || now.sourceLanguage != before.sourceLanguage { refreshPacks() }
            }
            if now.region != before.region || now.autoRegion != before.autoRegion {
                readQueue.async { self.scheduler.reset() }
                pipeline?.reset()
            }
            if now.extraAreas != before.extraAreas || now.buttonLayout != before.buttonLayout {
                // Ek alan değişti: altyazı okuması alanları yeniden süzsün; okuyucu gerekiyorsa kurulur, gerekmiyorsa kalkar.
                readQueue.async { self.scheduler.reset() }
                updateRegionWatcher()
            }
        }
    }

    /// OBS katmanı ve ikinci ekranın ayrı görünümü (Mac: "OBS için ayrı görünüm"); nil ise TV'ninkiyle aynı.
    /// Denetim kuyruğunda değişir.
    private var viewerStyle: SubtitleStyle?

    /// OBS ve ikinci ekranın görünümünü TV'ninkinden ayırır (nil: TV ile aynı). Sunucu açıksa hemen gönderilir.
    public func setViewerStyle(_ style: SubtitleStyle?) {
        control.async { [self] in
            guard style != viewerStyle else { return }
            viewerStyle = style
            if stateLock.withLock({ serverPort }) != 0 { sendStyle() }
        }
    }

    /// Denetim kuyruğunda: görünüm TV'ye (ve ayrıysa OBS'e) gider.
    private func sendStyle() {
        server.sendStyle(settings.style.subtitleStyle, viewerStyle: viewerStyle)
    }

    /// Altyazı sunucusu (TV uygulaması, OBS sayfası); denetim kuyruğunda bloklayarak açılır.
    private func startServer() {
        guard stateLock.withLock({ serverPort }) == 0 else { return }
        let done = DispatchSemaphore(value: 0)
        let server = self.server
        Task.detached {
            do {
                let port = try await server.start()
                self.stateLock.withLock { self.serverPort = Int(port) }
            } catch {
                self.log(0, "altyazı sunucusu açılamadı: \(error)")
            }
            done.signal()
        }
        done.wait()
        // TV uygulaması kurulamayan TV'ler için ikinci ekran: tablette ya da başka telefonda http://telefon:8787/ekran
        if let page = Bundle.main.url(forResource: "second-screen", withExtension: "html").flatMap({ try? Data(contentsOf: $0) }) {
            server.publish(page, at: "/ekran", type: "text/html; charset=utf-8")
            server.publish(page, at: "/screen", type: "text/html; charset=utf-8")
        }
        server.active = true
        sendStyle()
        log(2, "altyazı sunucusu: \(Self.localAddress() ?? "?"):\(stateLock.withLock { serverPort })")
    }

    // MARK: - Çeviri hattı

    private func buildPipeline() {
        pipeline?.reset()
        let s = settings
        let onDevice = AppleTranslator(source: s.sourceLanguage, target: s.targetLanguage)
        let chosen = Languages.effectiveEngine(s.engine, target: s.targetLanguage)
        if chosen != s.engine { log(1, "\(s.engine) \(s.targetLanguage) diline çeviremiyor; Google kullanılıyor") }
        log(2, "çeviri motoru: \(chosen)\(chosen == "deepl" && s.deepLKey.isEmpty ? " (anahtar yok: Google)" : ""), \(s.sourceLanguage)→\(s.targetLanguage)")
        let google = GoogleGtxTranslator(source: s.sourceLanguage, target: s.targetLanguage)
        let region = Locale.current.region?.identifier ?? ""
        let online: TextTranslating?
        switch chosen {
        case "deepl":
            if !s.deepLKey.trimmingCharacters(in: .whitespaces).isEmpty, let target = Languages.deepLTarget(s.targetLanguage, region: region) {
                online = FallbackTranslator(primary: DeepLTranslator(key: s.deepLKey, source: s.sourceLanguage, target: target), fallback: google)
            } else {
                online = google
            }
        case "mlkit": online = nil
        default: online = google
        }
        // Türkçeye özgü parçalar (paketler, sözlük, kısa ünlemler, cihaz üstü çevirinin Türkçe düzeltmeleri) yalnız
        // İngilizce→Türkçede çalışır.
        let turkishExtras = PackTranslation.applies(sourceLanguage: s.sourceLanguage, targetLanguage: s.targetLanguage)
        // Oyun paketleri İngilizce→Türkçe ve İngilizce→Arapça (her dilin kendi paketleri).
        let packLanguage = PackTranslation.packLanguage(sourceLanguage: s.sourceLanguage, targetLanguage: s.targetLanguage)
        let glossary = turkishExtras ? s.parsedGlossary : Glossary.empty
        if !glossary.isEmpty { log(2, "terim sözlüğü: \(glossary.count) terim") }
        let offline: TextTranslating = turkishExtras ? FreeTranslator(inner: onDevice, glossary: glossary) : onDevice
        let onlineWithGlossary = online.map { glossary.isEmpty ? $0 : GlossaryTranslator(glossary: glossary, inner: $0) }
        // Cihaz üstü çeviri hazır değilse (model yok) Google yedektir; cihaz üstü seçildiyse önce o denenir.
        let engine: TextTranslating = onlineWithGlossary.map { FallbackTranslator(primary: $0, fallback: offline) }
            ?? FallbackTranslator(primary: offline, fallback: google)
        Task.detached {
            if await onDevice.prepare() { self.log(2, "cihaz üstü çeviri hazır (Apple Çeviri)") }
            else { self.log(1, "cihaz üstü çeviri modeli yüklü değil; çevrimiçi motor kullanılacak") }
        }
        // Ek alanlar aynı motorla çevrilir; altyazıya özgü sarmalayıcılar (konuşmacı adı, bölünmüş cümle) uygulanmaz.
        regionTranslator = engine
        let prefixed = PrefixTranslator(inner: engine)
        let words = s.sourceLanguage == "en" ? EnglishWords.builtIn : nil
        let misspelling = words.map { words in Misspelling(isWord: { words.isWord($0) }) }
        let continuation = ContinuationTranslator(
            inner: { text in await prefixed.translate(text.replacingOccurrences(of: "\n", with: " ")) },
            looksMisread: misspelling.map { m in { m.looksMisread($0) } },
            phrasebook: turkishExtras ? { ShortPhrases.translate($0) } : nil)
        let translator = ClosureTranslator { text in (try? await continuation.translate(text)) ?? nil }

        let built = SubtitlePipeline(translator: translator, sink: sink)
        // Okuma onarımı ve okumaların birleştirilmesi İngilizce sözcük listesiyle.
        if let words {
            built.englishSource = true
            built.wordCheck = { words.isWord($0) }
        }
        built.onRepeatBlocked = { [weak self] line in self?.log(2, "arayüz yazısı: \"\(line)\" yineleniyor, artık çevrilmeyecek") }
        built.onPair = { [weak self] source, translation in
            guard let self else { return }
            self.stateLock.withLock {
                self.lastSource = source
                self.lastTranslation = translation
                self.translatedCount += 1
            }
            self.addToHistory(source, translation, fromPack: self.lastPackSource == source)
            self.log(2, "çeviri: \"\(source.replacingOccurrences(of: "\n", with: " "))\" → \"\(translation)\"")
            self.notifyChanged()
            DispatchQueue.main.async { [weak self] in self?.onPair?(source, translation) }
        }
        built.onTranslateFailed = { [weak self] source in self?.log(1, "çevrilemedi: \"\(source.replacingOccurrences(of: "\n", with: " "))\"") }
        built.onReadText = { [weak self] text in
            guard let self else { return }
            let changed = self.stateLock.withLock { () -> Bool in
                guard text != self.lastRead else { return false }
                self.lastRead = text
                return true
            }
            if changed { self.notifyChanged() }
        }
        // Ek alanların paketi: önce tuş çubuğu sözlüğü ("OPEN ✕" → "Aç ✕"), sonra oyun paketi (yalnız oyun bilindiğinde).
        let regionLibrary = s.packsEnabled ? packLanguage.flatMap { packLibrary($0) } : nil
        regionPackLookup = packLanguage == nil ? nil : { text in
            if turkishExtras, let prompt = UiPrompts.translate(text) { return PackMatch(prompt) }
            guard let library = regionLibrary, self.isPacksReady(), library.gameKnown else { return nil }
            return PackTranslation.match(library, text)
        }
        if s.packsEnabled, let packLanguage, let library = packLibrary(packLanguage) {
            DispatchQueue.global(qos: .utility).async { [weak self] in
                let started = Date()
                let infos = library.infos
                self?.stateLock.withLock { self?.packsReady = true }
                self?.log(2, "oyun paketleri: \(infos.count) paket, \(infos.reduce(0) { $0 + $1.entries }) satır (\(Int(Date().timeIntervalSince(started) * 1000)) ms)")
                self?.applyDetectedGame(library)
            }
            let gate = PackGate()
            built.packLookup = { [weak self] text in
                guard self?.isPacksReady() == true else { return nil }
                let match = PackTranslation.match(library, text)
                return library.gameKnown ? match : gate.filter(text, match)
            }
            built.onPackHit = { [weak self] source, match in
                guard let self else { return }
                self.lastPackSource = source
                self.lastPackId = match.packId
                self.stateLock.withLock {
                    self.packHits += 1
                    self.packGame = library.scopeTitle ?? match.packId ?? ""
                }
                self.log(2, "paket (\(match.packId ?? "?")): \"\(source.replacingOccurrences(of: "\n", with: " "))\"")
            }
        }
        // Ortak havuz: pakette olmayan İngilizce satırlar yeni paketler için biriktirilir, beş dakikada bir gönderilir.
        if s.sourceLanguage == "en" {
            let shared = pool ?? LinePool(file: filesDirectory.appendingPathComponent("pool.json"), app: "GameTranslator \(appVersion)",
                                          enabled: s.sharePool)
            pool = shared
            shared.setEnabled(s.sharePool)
            if let game = ps5Game { shared.setGame(titleId: game.titleId, name: game.name) }
            built.onUnpacked = { line in shared.offer(line) }
            if poolTimer == nil {
                let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
                let every = Double(LinePool.flushIntervalMs) / 1000
                timer.schedule(deadline: .now() + every, repeating: every)
                timer.setEventHandler { shared.flush() }
                timer.resume()
                poolTimer = timer
            }
        }
        reader = VisionReader(language: s.sourceLanguage)
        readQueue.async { self.scheduler.reset() }
        pipeline = built
        updateRegionWatcher()
    }

    // MARK: - Oyun paketleri

    /// Bir dilin paket kütüphanesi. Uygulamayla gelen paketler Türkçedir; başka dillerin paketleri yalnız sunucudan iner.
    private func packLibrary(_ language: String) -> GamePackLibrary? {
        packLock.withLock {
            if packsLanguage != language {
                packs = nil
                packStore = nil
                packsLanguage = language
            }
            if let packs { return packs }
            let turkish = language == PackStore.language
            let folder = turkish ? bundledPacks : nil
            if turkish && folder == nil {
                log(1, "uygulamada oyun paketi yok")
                return nil
            }
            let store = remotePacks()
            // Önceki paket sunucusundan kalan paketler en düşük öncelikte: aynı satır bizim paketimizde varsa onunki.
            let library = GamePackLibrary(bundled: { folder.map { GamePackLibrary.files(in: $0) } ?? [] }, userDirectory: store.folder,
                                          lowPriority: { store.legacyIds() })
            packs = library
            return library
        }
    }

    /// Ayarlardaki çeviri dilinin paket dili (paketi olmayan dilde Türkçe deposu: eskisi gibi yenilenir, kullanılmaz).
    private func settingsPackLanguage() -> String {
        PackTranslation.packLanguage(sourceLanguage: settings.sourceLanguage, targetLanguage: settings.targetLanguage) ?? PackStore.language
    }

    /// Ayarlardaki dilin deposunu seçer (dil değiştiyse açık kütüphane bırakılır, sonraki kurulumda yeniden açılır).
    private func selectPackLanguage() {
        let language = settingsPackLanguage()
        packLock.withLock {
            guard packsLanguage != language else { return }
            packs = nil
            packStore = nil
            packsLanguage = language
        }
    }

    /// Sunucudan inen paketlerin deposu (uygulamada olanlar inmez: özetleri uygulamadaki dosyalardan hesaplanır).
    private func remotePacks() -> PackStore {
        if let packStore { return packStore }
        let language = packsLanguage
        let turkish = language == PackStore.language
        let bundled = turkish ? bundledPacks : nil
        // Türkçe paketler eski klasörde kalır (güncellemede yeniden inmesin); başka diller kendi klasöründe.
        let folder = turkish ? "packs-remote" : "packs-remote-\(language)"
        let created = PackStore(directory: filesDirectory.appendingPathComponent(folder, isDirectory: true), server: HTTPPackServer(),
                                bundledHashes: {
            guard let bundled else { return [] }
            return Set(try FileManager.default.contentsOfDirectory(at: bundled, includingPropertiesForKeys: nil)
                .filter { GamePackLibrary.isPackFile($0.lastPathComponent) }
                .map { PackStore.sha256(try Data(contentsOf: $0)) })
        }, language: language)
        packStore = created
        return created
    }

    /// Haftada bir sunucudaki yeni ve güncellenen paketleri indirir (SHA-256 doğrulanır). Arka planda; değişiklik olduysa
    /// açık paketler yeniden yüklenir.
    /// Paket güncellemeleri sormadan insin mi (iPhone ve Android: evet). Mac'te Windows'taki gibi önce sorulur:
    /// `pendingPackUpdates()` ile bakılır, kullanıcı onaylarsa `installPackUpdates()`.
    public var autoRefreshPacks = true

    /// Sunucudaki yeni ya da güncellenmiş paketler (indirmez). Sunucuya ulaşılamazsa nil.
    public func pendingPackUpdates() async -> [RemotePack]? {
        selectPackLanguage()
        let store = packLock.withLock { remotePacks() }
        let bundledFolder = store.language == PackStore.language ? bundledPacks : nil
        return await Task.detached(priority: .utility) { () -> [RemotePack]? in
            // Yalnız çeviri dilinin paketleri sorulur (başka dillerin paketleri hiç inmez).
            guard let remote = (try? HTTPPackServer().list()).map({ PackStore.usable($0, language: store.language) }) else { return nil }
            var bundled: [String: String] = [:]
            if let folder = bundledFolder {
                for file in GamePackLibrary.files(in: folder) {
                    if let data = try? file.read() { bundled[GamePackLibrary.baseName(file.name)] = PackStore.sha256(data) }
                }
            }
            let installed = store.installed()
            return remote.filter { pack in
                if bundled[pack.id] == pack.sha256.lowercased() { return false }
                return !installed.contains { $0.id == pack.id && $0.version == pack.version && $0.sha256.lowercased() == pack.sha256.lowercased() }
            }
        }.value
    }

    /// Bekleyen paketleri indirir, doğrular ve hemen kullanmaya başlar (çeviri durmaz). Başarısızsa false.
    public func installPackUpdates() async -> Bool {
        selectPackLanguage()
        let store = packLock.withLock { remotePacks() }
        let result = await Task.detached(priority: .utility) { () -> PackStore.Refresh? in try? store.refresh(force: true) }.value
        guard let result else {
            log(1, "paket sunucusuna ulaşılamadı")
            return false
        }
        log(2, "paket sunucusu: \(result.downloaded.count) indirildi, \(result.removed.count) kaldırıldı")
        if !result.downloaded.isEmpty || !result.removed.isEmpty, let library = packLock.withLock({ packs }) {
            stateLock.withLock { packsReady = false }
            library.reload()
            _ = library.infos
            stateLock.withLock { packsReady = true }
        }
        return true
    }

    private func refreshPacks() {
        // Mac'te paketler sorularak kurulur; ama uygulama yeni paket sunucusuna geçtiyse elde olan oyunların yeni sürümü
        // sormadan iner (aynı oyunlar, başka sunucu).
        selectPackLanguage()
        // Türkçe dışındaki bir dilin paketleri ilk kez sormadan iner: kullanıcı o dili kendisi seçti.
        let current = packLock.withLock { remotePacks() }
        let firstOfLanguage = current.language != PackStore.language && current.installed().isEmpty
        guard settings.packsEnabled, autoRefreshPacks || current.serverChanged || firstOfLanguage else { return }
        let begin = packLock.withLock { () -> Bool in
            if packRefreshing { return false }
            packRefreshing = true
            return true
        }
        guard begin else { return }
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self else { return }
            defer { self.packLock.withLock { self.packRefreshing = false } }
            do {
                let store = self.packLock.withLock { self.remotePacks() }
                let result = try store.refresh(force: firstOfLanguage)
                if !result.skipped { self.log(2, "paket sunucusu: \(result.downloaded.count) indirildi, \(result.removed.count) kaldırıldı") }
                if !result.downloaded.isEmpty || !result.removed.isEmpty, let library = self.packLock.withLock({ self.packs }) {
                    self.stateLock.withLock { self.packsReady = false }
                    library.reload()
                    _ = library.infos
                    self.stateLock.withLock { self.packsReady = true }
                }
            } catch {
                self.log(1, "paket sunucusuna ulaşılamadı: \(error)")
            }
        }
    }

    /// PS5'in bildirdiği oyunun paketi (varsa) seçilir; paketler kapalıysa ya da oyun yoksa seçim kalkar.
    private func applyDetectedGame(_ library: GamePackLibrary) {
        library.detected = settings.packsEnabled ? library.findByGameName(ps5Game?.name) : nil
    }

    private func isPacksReady() -> Bool { stateLock.withLock { packsReady } }

    // MARK: - PS5'te çalışan oyun

    private func watchRunningGame() {
        gameTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        timer.schedule(deadline: .now(), repeating: Self.runningGameEvery)
        timer.setEventHandler { [weak self] in self?.checkRunningGame() }
        timer.resume()
        gameTimer = timer
    }

    private func checkRunningGame() {
        guard isRunning(), let host = store.console?.host, let info = Ps5Status.query(host: host, timeoutMs: 2000) else { return }
        let game = info.game
        guard game != ps5Game else { return }
        ps5Game = game
        stateLock.withLock { runningGame = game?.name ?? "" }
        pool?.setGame(titleId: game?.titleId, name: game?.name)
        let library = packLock.withLock { packs }
        if let library { applyDetectedGame(library) }
        if let game {
            log(2, "PS5'te çalışan oyun: \(game.name ?? "?") (\(game.titleId))" + (library?.detected.map { ", paket: \($0)" } ?? ", paketi yok"))
        } else {
            log(2, "PS5'te oyun çalışmıyor")
        }
        notifyChanged()
    }

    // MARK: - PS5 oturumu

    private func connect(_ console: RegisteredConsole, account: Data) {
        setPhase("connecting", console.host)
        log(2, "PS5 oturumu: \(console.name) \(console.host)")
        let created = StreamSession(console: console, accountId: account)
        created.autoWake = settings.autoWake
        created.onConsoleMoved = { [weak self] moved in
            self?.log(2, "PS5 yeni adreste bulundu: \(moved.host)")
            self?.store.console = moved
        }
        created.onState = { [weak self, weak created] state in
            guard let self, let created else { return }
            self.control.async { self.sessionChanged(state, from: created) }
        }
        created.onFrame = { [weak self] image in self?.onFrame(image) }
        session = created
        created.start()
    }

    private func sessionChanged(_ state: SessionState, from source: StreamSession) {
        guard session === source, isRunning() else { return }
        switch state {
        case .connecting:
            if stateLock.withLock({ phase }) != "connecting" { setPhase("connecting", store.console?.host ?? "") }
        case .streaming:
            setPhase("streaming")
        case .disconnected(let reason):
            switch reason {
            case .stopped:
                break
            case .consoleAsleep:
                setPhase("failed", UiText.code(settings.autoWake ? "no_wake" : "rest_mode"))
            case .inUse:
                setPhase("failed", UiText.code("in_use"))
            case .refused:
                setPhase("failed", UiText.code("refused"))
            case .network:
                setPhase("reconnecting", UiText.code("lost"))
            case .loginPasscode, .loginPasscodeIncorrect:
                stopFailed(UiText.code("login_pin"))
            case .registrationInvalid:
                stopFailed(UiText.code("refused"))
            case .notFound(let host):
                setPhase("failed", UiText.code("not_found", host))
            case .other(let text):
                setPhase("reconnecting", text)
            }
        }
    }

    /// Yeniden denemenin işe yaramayacağı hata: oturum kapanır, çeviri durur.
    private func stopFailed(_ text: String) {
        session?.stop()
        session = nil
        stateLock.withLock { running = false }
        setPhase("failed", text)
    }

    // MARK: - Xbox oturumu (Beta)

    /// Açık Xbox ev yayını oturumu; PlayStation seçiliyken hep nil.
    private var xbox: XboxStreamSession?

    /// Xbox ev yayınını açar: görüntü WebRTC ile gelir ve PS5 kareleriyle aynı okuma hattına girer. Oturum koparsa kendisi
    /// yeniden dener; yeniden giriş gerektiren hatada durur (Android: Engine.connectXbox).
    private func connectXbox() {
        guard let console = XboxAccount.shared.selectedConsole else { return setPhase("failed", UiText.code("no_xbox")) }
        closeXbox()
        setPhase("connecting", console.name)
        log(2, "Xbox oturumu: \(console.name) \(console.model)")
        var created: XboxStreamSession?
        let current: () -> Bool = { [weak self] in
            guard let self, let created else { return false }
            return self.xbox === created && self.isRunning()
        }
        let callbacks = XboxStreamCallbacks()
        callbacks.log = { [weak self] level, text in self?.log(level, "xbox: \(text)") }
        callbacks.connecting = { [weak self] in
            guard let self, current(), self.stateLock.withLock({ self.phase }) != "connecting" else { return }
            self.setPhase("connecting", console.name)
        }
        callbacks.streaming = { [weak self] in if current() { self?.setPhase("streaming") } }
        callbacks.failed = { [weak self] kind, _, willRetry in
            guard let self, current() else { return }
            let text = XboxAccount.describeStream(kind)
            if willRetry { return self.setPhase(kind == .network ? "reconnecting" : "failed", text) }
            self.control.async {
                guard let created, self.xbox === created else { return }
                self.closeXbox()
                self.stateLock.withLock { self.running = false }
                self.setPhase("failed", text)
            }
        }
        callbacks.frame = { [weak self] image in if current() { self?.onFrame(image) } }
        let session = XboxStreamSession(console: console, listener: callbacks, frameIntervalMs: Int64(1000 / Self.maxFps))
        created = session
        xbox = session
        session.start()
    }

    private func closeXbox() {
        let open = xbox
        xbox = nil
        open?.dispose()
    }

    // MARK: - Kareler ve yazı okuma

    private func onFrame(_ image: CGImage) {
        frameLock.withLock { frame = image }
        let count = stateLock.withLock { () -> Int64 in
            frameCount += 1
            frameWidth = image.width
            frameHeight = image.height
            return frameCount
        }
        if count % 5 == 1 { notifyChanged() }
        // Saniyede en çok `maxFps` okuma; önceki kare hâlâ okunuyorsa bu kare atlanır.
        let now = Self.now()
        let go = frameLock.withLock { () -> Bool in
            guard !reading, now - lastReadAt >= 1 / Self.maxFps else { return false }
            reading = true
            lastReadAt = now
            return true
        }
        offerRegions(image)
        guard go else { return }
        readQueue.async { [weak self] in
            self?.readFrame(image)
            self?.frameLock.withLock { self?.reading = false }
        }
    }

    /// Okuma kuyruğunda çağrılır.
    private func readFrame(_ image: CGImage) {
        guard let current = pipeline else { return }
        let auto = settings.autoRegion
        if auto { findSubtitles(image) }
        // Otomatikte okunan alan öğrenilen şerittir (henüz öğrenilmediyse ekranın alt yarısına yakın geniş alan).
        let (learned, band, moved) = autoLock.withLock { () -> (Region?, (top: Double, bottom: Double)?, Bool) in
            defer { regionMoved = false }
            return (autoRegion, autoBand, regionMoved)
        }
        if moved { scheduler.reset() }
        let region = (auto ? learned ?? Self.autoStart : settings.region).box
        guard let crop = Frames.crop(image, to: region) else { return }
        let lines: [String]
        let box: TextBox?
        switch scheduler.plan(signature: Frames.signature(of: crop)) {
        case .reuse:
            lines = lastLines
            box = lastBox
        case .read(let deep):
            do {
                // Satırların yeri tüm kareye oranla; kumanda ipuçları altyazı değildir.
                var found = try reader.readLines(crop, deep: deep).withoutButtonPrompts().placed(in: region).lines
                // Otomatik algılamada alan sert bir kırpma değildir: şeritteki satırlar ve onlara bitişik satırlar
                // altyazıdır; şerit henüz öğrenilmediyse ortadan belirgin biçimde uzak satırlar atılır.
                if auto { found = band.map { SubtitleFinder.grow(found, band: $0) } ?? SubtitleFinder.withoutOffCenterLines(found) }
                // Sürekli okunan ek alanların içine düşen satırlar (alttaki görev yazısı) altyazı değildir.
                found = ExtraAreas.withoutWatchedAreas(found, settings.watchedAreas)
                // GTA VI'da altyazının hemen altındaki görev yazısı ("Drive the boat") ek alan açık olmasa da altyazı
                // değildir; çevrilmeden atılır (yalnız GTA VI: başka oyunda o bölge altyazının parçası olabilir).
                if isGta6() {
                    found = ExtraAreas.withoutWatchedAreas(found, [WatchedArea(id: "objective", name: "Görev", region: ExtraAreaPreset.bottomObjective.region)])
                }
                lines = found.map(\.text)
                box = Reading(lines: found).box
                scheduler.record(lines.joined(separator: "\n"))
                lastLines = lines
                lastBox = box
            } catch {
                log(1, "yazı tanıma başarısız: \(error)")
                return
            }
        }
        current.onRead(lines, box: box)
    }

    /// Oynanan oyun GTA VI mı: PS5'in bildirdiği oyun, PS5'e göre seçilen paket ya da son paket eşleşmesi.
    func isGta6() -> Bool {
        if Self.isGta6(name: stateLock.withLock({ runningGame })) { return true }
        if let detected = packLock.withLock({ packs })?.detected, Self.isGta6Pack(detected) { return true }
        return lastPackId.map(Self.isGta6Pack) ?? false
    }

    /// GTA VI paketleri: "gta-6" (Türkçe) ve dillerin kendi paketleri ("gta-6-ar").
    static func isGta6Pack(_ id: String) -> Bool {
        id == "gta-6" || id.hasPrefix("gta-6-")
    }

    static func isGta6(name: String) -> Bool {
        let lower = name.lowercased()
        return lower.contains("grand theft auto vi") || lower.contains("gta vi") || lower.contains("gta 6")
    }

    // MARK: - Otomatik altyazı algılama (SubtitleFinder)
    // Karenin tamamı ayrı kuyrukta aralıklı okunur (şerit öğrenilene kadar saniyede bir, sonra iki saniyede bir; Windows ve
    // Android ile aynı). Öğrenilen şerit, "otomatik" açıkken okunan alan olur.

    /// Şerit öğrenilene kadar okunan alan: ekranın alt yarısına yakın, kenarlar hariç.
    static let autoStart = Region(top: 0.55, bottom: 0.98, left: 0.08, right: 0.92)
    /// Şerit öğrenildikten sonra tam kare 5 sn'de bir okunur (Android ile aynı; 2 sn zayıf telefonda okumayı yavaşlatıyordu).
    static let findEvery: TimeInterval = 5
    static let learnEvery: TimeInterval = 1
    /// Tam kare okuması bu genişlikten büyükse küçültülür.
    static let findMaxWidth = 1280
    /// Okunan alan şeridin bu kadar üstünden ve altından başlar: uzayan altyazının satırları kesilmesin.
    static let bandMarginTop = 0.08
    static let bandMarginBottom = 0.10

    private let autoLock = NSLock()
    private let finder = SubtitleFinder()
    private let findQueue = DispatchQueue(label: "gt.finder", qos: .utility)
    private var finding = false
    private var lastFind: TimeInterval = 0
    private var findBand: (top: Double, bottom: Double)?
    private var autoBand: (top: Double, bottom: Double)?
    private var autoRegion: Region?
    private var regionMoved = false

    private func resetAutoRegion() {
        autoLock.withLock {
            findBand = nil
            autoBand = nil
            autoRegion = nil
            regionMoved = false
            lastFind = 0
        }
    }

    /// Okuma kuyruğundan çağrılır; vakti geldiyse karenin tamamını arka planda okur.
    private func findSubtitles(_ image: CGImage) {
        let now = Self.now()
        let start = autoLock.withLock { () -> Bool in
            let every = autoRegion == nil ? Self.learnEvery : Self.findEvery
            guard now - lastFind >= every, !finding else { return false }
            lastFind = now
            finding = true
            return true
        }
        guard start else { return }
        let reader = self.reader
        findQueue.async { [weak self] in
            guard let self else { return }
            defer { self.autoLock.withLock { self.finding = false } }
            let small = image.width > Self.findMaxWidth
                ? Frames.resized(image, width: Self.findMaxWidth, height: image.height * Self.findMaxWidth / image.width) : image
            guard let small, let reading = try? reader.recognize(small) else { return }
            let lines = reading.lines.compactMap { line -> TextLine? in
                let clean = OcrNoise.clean(line.text)
                return clean.isEmpty ? nil : TextLine(clean, box: line.box)
            }
            self.learnBand(lines, now: Int64(now * 1000))
        }
    }

    /// Tam karenin okumasını sınıflar; şerit belirgin biçimde değiştiyse okunan alanı günceller.
    private func learnBand(_ lines: [TextLine], now: Int64) {
        _ = finder.classify(lines, now: now)
        guard let band = finder.band else { return }
        let changed = autoLock.withLock { () -> Bool in
            // Şerit her yeni altyazıyla azıcık oynar; okunan alan yalnız belirgin değişince güncellenir.
            if let old = findBand, abs(old.top - band.top) <= 0.035, abs(old.bottom - band.bottom) <= 0.035 { return false }
            findBand = band
            autoBand = band
            autoRegion = Region(top: max(0, band.top - Self.bandMarginTop), bottom: min(0.99, band.bottom + Self.bandMarginBottom),
                                left: Self.autoStart.left, right: Self.autoStart.right)
            regionMoved = true
            return true
        }
        if changed { log(2, "otomatik: altyazı şeridi %\(Int(band.top * 100))–%\(Int(band.bottom * 100)) (okunan alan güncellendi)") }
    }

    // MARK: - Ek alanlar (RegionWatcher)
    // Görev yazısı, ipucu kutusu, telefon mesajı gibi alanlar altyazıdan ayrı, kendi kuyruğunda okunur (Android:
    // updateRegionWatcher). Tur en çok 450 ms'de bir atılır; çeviriler TV'ye ayrı kutu olarak gider. Yalnız en az bir alan
    // açıkken çalışır; ek alan satırları ortak havuza gönderilmez.

    private let regionQueue = DispatchQueue(label: "gt.regions", qos: .utility)
    private let regionPrefetch = DispatchQueue(label: "gt.region-translate", qos: .utility)
    private var regionWatcher: RegionWatcher?
    private var regionTranslator: TextTranslating?
    private var regionPackLookup: ((String) -> PackMatch?)?

    private final class Regions: RegionSender {
        weak var engine: Engine?
        init(_ engine: Engine) { self.engine = engine }
        func sendRegion(id: String, text: String, box: TextBox?, lineHeight: Double?) {
            engine?.server.sendRegion(id: id, text: text, box: box, lineHeight: lineHeight)
            engine?.emitRegion(.show(id: id, text: text, box: box, lineHeight: lineHeight))
        }
        func clearRegions() {
            engine?.server.clearRegions()
            engine?.emitRegion(.clearAll)
        }
    }
    private lazy var regionSender = Regions(self)

    // MARK: - Ekranı çevir (Mac: ⌥⌘T)
    // Karenin tamamı bir kez okunur, yazı grupları ayrı ayrı çevrilir (çekirdeğin ScreenTranslation'ı) ve TV her
    // çeviriyi kendi İngilizce yazısının üstüne koyar. Çeviri sürerken ya da çeviriler TV'deyken yeniden çağırmak onları
    // kaldırır (Windows ve eski Mac uygulamasıyla aynı).

    /// Süren "ekranı çevir" işi ve numarası (denetim kuyruğunda).
    private var screenTask: Task<Void, Never>?
    private var screenNumber = 0
    /// TV'deki ekran çevirilerinin kalkacağı an (`now()` cinsinden).
    private var screenShownUntil: TimeInterval = 0

    /// Çeviri açıkken son karenin tamamını çevirip TV'ye gönderir; yeniden çağrılınca çevirileri kaldırır.
    public func translateScreen() {
        control.async { [self] in
            guard isRunning(), let image = frameLock.withLock({ frame }), let translator = regionTranslator else {
                return log(1, "ekranı çevir: çeviri açık değil ya da kare yok")
            }
            if screenTask != nil || Self.now() < screenShownUntil { return cancelScreen() }
            screenNumber += 1
            let number = screenNumber
            let language = settings.sourceLanguage, ui = settings.uiLanguage
            showSubtitle(UiText.t("screen.reading", setting: ui), durationMs: 2500, box: nil, replaces: false)
            log(2, "ekranı çevir: okunuyor")
            screenTask = Task.detached(priority: .userInitiated) { [weak self] in
                guard let self else { return }
                do {
                    let reading = try VisionReader(language: language).readLines(image, deep: false)
                    let items = try await ScreenTranslation.translate(reading, using: { text in
                        await translator.translate(text) ?? text
                    }) { shown in
                        guard !Task.isCancelled else { return }
                        let milliseconds = ScreenTranslation.displayMilliseconds(shown)
                        self.server.sendScreen(shown, durationMs: milliseconds)
                        self.control.async { if self.screenNumber == number { self.screenShownUntil = Self.now() + Double(milliseconds) / 1000 } }
                    }
                    try Task.checkCancellation()
                    self.clearSubtitle()
                    if items.isEmpty { self.showSubtitle(UiText.t("screen.empty", setting: ui), durationMs: 2500, box: nil, replaces: false) }
                    self.log(2, "ekranı çevir: \(items.count) yazı grubu")
                } catch is CancellationError {
                    return
                } catch {
                    self.log(1, "ekranı çevir başarısız: \(error)")
                    self.clearSubtitle()
                }
                self.control.async { if self.screenNumber == number { self.screenTask = nil } }
            }
        }
    }

    /// Denetim kuyruğunda: süren ekran çevirisi iptal edilir, TV'deki çeviriler kalkar.
    private func cancelScreen() {
        let wasActive = screenTask != nil || Self.now() < screenShownUntil
        screenNumber += 1
        screenTask?.cancel()
        screenTask = nil
        screenShownUntil = 0
        guard wasActive else { return }
        server.clearScreen()
        clearSubtitle()
    }

    /// Denetim kuyruğunda: ek alan okuyucusu gerekiyorsa kurulur ya da güncellenir, gerekmiyorsa kaldırılır.
    private func updateRegionWatcher() {
        guard isRunning(), pipeline != nil, let engine = regionTranslator, !settings.watchedAreas.isEmpty else {
            if let watcher = regionWatcher {
                watcher.reset()
                log(2, "ek alanlar kapandı")
            }
            regionWatcher = nil
            return
        }
        let translate: TextTranslate = { text in Engine.blocking { await engine.translate(text) } }
        let watcher: RegionWatcher
        if let existing = regionWatcher {
            watcher = existing
        } else {
            let language = settings.sourceLanguage
            let queue = regionQueue, prefetch = regionPrefetch
            watcher = RegionWatcher(reader: IconReader(recognize: { image in try VisionReader(language: language).ocrLines(image) }),
                                    translator: translate, server: regionSender, intervalMs: RegionWatcher.phoneIntervalMs,
                                    executor: { work in queue.async(execute: work) }, prefetchExecutor: { work in prefetch.async(execute: work) })
            // Okuyucu kumanda simgelerini görüntüden tanır: tuş ipuçları atılmaz, simgesiyle çevrilir ("Hold ✕ to climb").
            watcher.keepsPrompts = true
            watcher.log = { [weak self] text in self?.log(2, text) }
            // Ek alana düşen altyazı satırı ek alanda çevrilmez.
            watcher.skipLine = { [weak self] text in
                let shown = self?.stateLock.withLock { self?.lastSource ?? "" } ?? ""
                return ExtraAreas.skipLine(text, shownSource: shown.isEmpty ? nil : shown)
            }
            log(2, "ek alanlar açık: \(settings.watchedAreas.count)")
        }
        watcher.translator = translate
        watcher.packLookup = regionPackLookup
        // Tuş simgelerinin kumandası: ayar Otomatik ise Xbox kaynağında Xbox, PS5'te PlayStation (Android 931fb33).
        watcher.setButtonLayout(ButtonLayout.parse(settings.buttonLayout), xboxSource: store.consoleKind == Store.kindXbox)
        if settings.sourceLanguage == "en" {
            let words = EnglishWords.builtIn
            watcher.wordCheck = { words.isWord($0) }
        } else {
            watcher.wordCheck = nil
        }
        regionWatcher = watcher
    }

    /// Kare geldikçe çağrılır; tur vakti geldiyse ek alanlar arka planda okunur (hemen döner).
    private func offerRegions(_ image: CGImage) {
        guard let watcher = regionWatcher else { return }
        watcher.offer(FrameAreas(image: image), areas: settings.watchedAreas)
    }

    /// Eşzamansız çeviriyi GCD kuyruğunda bekler (ek alan turları ve çekirdeğin eşzamanlı çevirmen arayüzü için).
    static func blocking(_ body: @escaping @Sendable () async -> String?) -> String? {
        final class Box: @unchecked Sendable { var value: String? }
        let box = Box()
        let done = DispatchSemaphore(value: 0)
        Task.detached {
            box.value = await body()
            done.signal()
        }
        done.wait()
        return box.value
    }

    // MARK: - Başka GameTranslator sunucusu

    /// Çeviri başladıktan 15 sn sonra TV hâlâ bağlanmadıysa ağda başka bir GameTranslator var mı bakılır: eski TV
    /// uygulaması ilk bulduğu sunucuya bağlanır, o da çoğu zaman açık duran bilgisayar uygulamasıdır.
    private func watchForOtherServer() {
        stateLock.withLock { otherServer = nil }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self, self.isRunning(), self.server.connectedClients == 0, let own = Self.localAddress() else { return }
            guard let found = OtherServers.find(own: own), self.isRunning(), self.server.connectedClients == 0 else { return }
            self.stateLock.withLock { self.otherServer = found }
            self.log(1, "ağda başka bir GameTranslator açık: \(found) (TV ona bağlanmış olabilir)")
            self.notifyChanged()
        }
    }

    // MARK: - Altyazı geçmişi

    private var historyFile: URL { filesDirectory.appendingPathComponent("history.json") }

    private func history() -> PhoneHistory {
        historyLock.withLock {
            if let historyStore { return historyStore }
            let loaded = PhoneHistory.load(from: historyFile)
            historyStore = loaded
            return loaded
        }
    }

    /// Geçmiş, en yenisi başta (arayüz için düz eşlemeler).
    public func historyEntries() -> [[String: Any]] {
        history().entries.map { ["source": $0.source, "translation": $0.translation, "date": $0.date, "fromPack": $0.fromPack] }
    }

    #if DEBUG
    /// Yalnız hata ayıklama: arayüzün gösterdiği evre (mağaza görselleri için; oturum açılmaz).
    public func debugPhase(_ value: String, tvClients: Int = 0) {
        setPhase(value)
    }

    /// Yalnız hata ayıklama: TV'ye ve telefondaki ekrana bir altyazı (mağaza görselleri için).
    public func debugShowSubtitle(_ text: String) {
        control.async { [self] in
            startServer()
            server.active = true
            showSubtitle(text, durationMs: 120_000, box: nil, replaces: false)
        }
    }

    /// Yalnız hata ayıklama: mağaza görselleri ve denemeler için geçmişe örnek satırlar (en eskisi önce).
    public func debugSeedHistory(_ entries: [(source: String, translation: String, fromPack: Bool)]) {
        let history = history()
        history.clear()
        var date = Int64(Date().timeIntervalSince1970 * 1000) - Int64(entries.count) * 9000
        for entry in entries {
            history.add(source: entry.source, translation: entry.translation, fromPack: entry.fromPack, date: date)
            date += 9000
        }
        saveHistory()
    }
    #endif

    public func clearHistory() {
        history().clear()
        saveHistory()
    }

    private func addToHistory(_ source: String, _ translation: String, fromPack: Bool) {
        if history().add(source: source, translation: translation, fromPack: fromPack) { saveHistory() }
    }

    private func saveHistory() {
        let current = history()
        let file = historyFile
        historyQueue.async { [weak self] in
            do { try current.save(to: file) } catch { self?.log(1, "geçmiş kaydedilemedi: \(error)") }
        }
    }

    // MARK: - Arayüz için yardımcılar

    /// Son karenin JPEG görüntüsü (bölge ayarı ve önizleme için); kare yoksa nil.
    public func snapshot(maxWidth: Int) -> Data? {
        guard let image = frameLock.withLock({ frame }) else { return nil }
        return Frames.jpeg(image, maxWidth: maxWidth)
    }

    public func sendTestSubtitle() {
        control.async { [self] in
            settings = store.settings
            startServer()
            server.active = true
            sendStyle()
            let r = settings.region
            showSubtitle(UiText.t("test.subtitle", setting: settings.uiLanguage), durationMs: 8000,
                         box: TextBox(left: 0.3, top: r.bottom - 0.1, right: 0.7, bottom: r.bottom - 0.03), replaces: false)
        }
    }

    /// Telefonun yerel ağdaki IPv4 adresi (Wi-Fi, en0 öncelikli).
    public static func localAddress() -> String? {
        var fallback: String?
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            guard let address = entry.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
                  entry.ifa_flags & UInt32(IFF_UP) != 0, entry.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let text = String(cString: host)
            let name = String(cString: entry.ifa_name)
            if name == "en0" { return text }
            // Hücresel (pdp_ip) ve VPN (utun) adresleri TV'nin göreceği adres değildir.
            if fallback == nil, name.hasPrefix("en") || name.hasPrefix("bridge") { fallback = text }
        }
        return fallback
    }

    // MARK: - Arama ve eşleştirme

    /// Ağdaki PS5'leri arar. `extra`: elle girilen adresler (genel yayın bazı ağlarda geçmez).
    public func discover(extra: [String], timeoutMs: Int) async -> [[String: Any]] {
        let timeout = Duration.milliseconds(max(500, timeoutMs))
        var found: [String: ConsoleInfo] = [:]
        await withTaskGroup(of: [ConsoleInfo].self) { group in
            group.addTask { await Console.discover(timeout: timeout) }
            for host in Set(extra.map { $0.trimmingCharacters(in: .whitespaces) }) where !host.isEmpty {
                group.addTask { await Console.discover(timeout: timeout, host: host) }
            }
            for await list in group { for info in list { found[info.host] = info } }
        }
        return found.values.sorted { $0.host < $1.host }.map { ["host": $0.host, "name": $0.name, "awake": $0.isAwake] }
    }

    /// PS5'in "Cihaz Eşleştir" koduyla kayıt (en çok ~30 sn). Başarılıysa kaydı saklar.
    public func register(host: String, pin: Int) async -> [String: Any] {
        guard let account = store.accountId else { return ["ok": false, "detail": UiText.code("need_psn")] }
        log(2, "PS5 kaydı başlıyor: \(host)")
        do {
            let console = try await Registration.register(host: host, accountId: account, pin: UInt32(clamping: pin))
            let name = console.name.trimmingCharacters(in: .whitespaces).isEmpty ? "PS5" : console.name
            store.console = RegisteredConsole(host: host, name: name, registKey: console.registKey, morning: console.morning)
            log(2, "PS5 kaydı tamam: \(name)")
            return ["ok": true, "name": name, "host": host]
        } catch let error as Registration.RegistrationError {
            let detail: String
            switch error.kind {
            case .wrongAccount: detail = UiText.code("reg_unknown_account")
            case .wrongCode: detail = UiText.code("reg_bad_code")
            case .canceled: detail = UiText.code("cancelled")
            case .noAnswer: detail = UiText.code("reg_not_ready")
            case .noReply: detail = UiText.code("reg_no_reply")
            case .inUse: detail = UiText.code("in_use")
            // iPhone'da yerel ağ izni kapalıysa paket ağa çıkamaz; Ayarlar > Gizlilik > Yerel Ağ'dan açılır.
            case .localNetworkBlocked: detail = UiText.code("reg_unreachable")
            default: detail = error.detail ?? UiText.code("reg_no_reply")
            }
            log(0, "PS5 kaydı başarısız: \(error)")
            var result: [String: Any] = ["ok": false, "detail": detail]
            if let reason = error.reason { result["reason"] = Int(Int32(bitPattern: reason)) }
            return result
        } catch {
            log(0, "PS5 kaydı başarısız: \(error)")
            return ["ok": false, "detail": "\(error)"]
        }
    }
}
