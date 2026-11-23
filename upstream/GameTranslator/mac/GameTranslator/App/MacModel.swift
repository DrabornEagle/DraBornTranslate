// SPDX-License-Identifier: AGPL-3.0-only

import AppKit
import Foundation
import GameTranslatorCore
import GTNative
import Observation
import RemotePlay

/// Motorun arayüze yansıyan durumu (GTNative.Engine.state()'in tipli hâli; anahtarlar Android ve iPhone ile aynı).
struct EngineState: Equatable {
    var phase = "idle"
    var message = ""
    var running = false
    var tvClients = 0
    var otherServer: String?
    var serverPort = 0
    var localAddress: String?
    var lastSource = ""
    var lastTranslation = ""
    var lastRead = ""
    var frames: Int64 = 0
    var frameWidth = 0
    var frameHeight = 0
    var translated = 0
    var packHits = 0
    var packGame = ""
    var runningGame = ""

    init() {}

    init(_ map: [String: Any]) {
        phase = map["phase"] as? String ?? "idle"
        message = map["message"] as? String ?? ""
        running = map["running"] as? Bool ?? false
        tvClients = map["tvClients"] as? Int ?? 0
        otherServer = map["otherServer"] as? String
        serverPort = map["serverPort"] as? Int ?? 0
        localAddress = map["localAddress"] as? String
        lastSource = map["lastSource"] as? String ?? ""
        lastTranslation = map["lastTranslation"] as? String ?? ""
        lastRead = map["lastRead"] as? String ?? ""
        frames = (map["frames"] as? NSNumber)?.int64Value ?? 0
        frameWidth = map["frameWidth"] as? Int ?? 0
        frameHeight = map["frameHeight"] as? Int ?? 0
        translated = map["translated"] as? Int ?? 0
        packHits = map["packHits"] as? Int ?? 0
        packGame = map["packGame"] as? String ?? ""
        runningGame = map["runningGame"] as? String ?? ""
    }

    var streaming: Bool { phase == "streaming" }
    var connecting: Bool { ["starting", "connecting", "waking", "reconnecting"].contains(phase) }
    /// Çeviri açık ya da açılıyor (durdur düğmesi gösterilir).
    var active: Bool { running || !(phase == "idle" || phase == "failed") }
}

/// Mac arayüzünün tek modeli: GTNative motoru (PS5/Xbox oturumu, okuma, çeviri, TV sunucusu), kayıtlar ve TV kurulumu.
/// Bilgisayardaki AppController'ın karşılığı. Ana iş parçacığında yaşar; motor kendi kuyruklarında çalışır.
@MainActor
@Observable
final class MacModel {
    let engine: Engine
    let store: Store
    let tv: TvInstall
    let xbox = XboxAccount.shared
    /// Mac'e özgü tercihler (görüntü kaynağı, ekran katmanı, OBS görünümü, dublaj).
    let preferences = MacPreferences()

    private(set) var state = EngineState()
    /// Ayarlar (Android ve iPhone ile aynı JSON). Değiştirilince kaydedilir ve motora uygulanır.
    var settings: Settings {
        didSet {
            guard settings != oldValue else { return }
            store.settings = settings
            engine.applySettings()
        }
    }

    /// PS5 eşleştirmesi ve hesap.
    private(set) var console: RegisteredConsole?
    private(set) var hasAccount = false
    var consoleKind: String {
        didSet { store.consoleKind = consoleKind }
    }
    var introSeen: Bool {
        didSet { store.introSeen = introSeen }
    }

    /// Kurulum tamam mı (PS5: hesap ve eşleştirme; Xbox: giriş ve seçili konsol). Görüntü yakalama kartından, bir
    /// pencereden ya da ekrandan alınıyorsa konsol kurulumu gerekmez.
    var ready: Bool {
        if preferences.videoSource != .console { return true }
        return consoleKind == Store.kindXbox ? (xbox.signedIn && xbox.selectedConsole != nil) : (hasAccount && console != nil)
    }

    /// Sunucuda bekleyen yeni ya da güncellenmiş oyun paketleri (Windows'taki gibi indirmeden önce sorulur).
    private(set) var pendingPacks: [RemotePack] = []
    private(set) var installingPacks = false
    private(set) var packsJustInstalled = 0

    /// Paket sunucusuna bakar (indirmez); paketler kapalıysa bakılmaz.
    func checkPackUpdates() async {
        guard settings.packsEnabled, !installingPacks else { return }
        if let found = await engine.pendingPackUpdates() { pendingPacks = found }
    }

    func installPackUpdates() async {
        guard !installingPacks else { return }
        installingPacks = true
        let count = pendingPacks.count
        let ok = await engine.installPackUpdates()
        installingPacks = false
        if ok {
            pendingPacks = []
            packsJustInstalled = count
        }
    }

    func postponePackUpdates() { pendingPacks = [] }

    /// Ekranda TV'deki altyazının kopyası (Mac ekranı katmanı ve "bu Mac'te göster" için).
    private(set) var screenSubtitle: String?
    /// Oyundaki altyazının karedeki yeri (kenarlara oranla); katmanın "altyazıyı ört" yerleşimi için. Bilinmiyorsa nil.
    private(set) var screenSubtitleBox: TextBox?
    /// Ek alanların çevirileri (Mac ekranındaki katmanda TV'deki gibi kutular).
    private(set) var screenRegions: [OverlayRegion] = []
    /// Pencere ya da ekran kaynağında okunan alanın ekrandaki yeri (Cocoa koordinatları); katman "altyazıyı ört"
    /// yerleşiminde kendini buraya koyar. Yakalama kartı ve konsolda nil (katman seçilen ekranı kaplar).
    private(set) var coverFrame: CGRect?
    @ObservationIgnored private var windowWatch: Task<Void, Never>?
    /// Dublajın son hatası (ayarlar ekranı gösterir).
    var dubbingError: String?

    /// Açık dış görüntü kaynağı (yakalama kartı, pencere, ekran); konsolda nil.
    @ObservationIgnored private var source: FrameSource?
    @ObservationIgnored private var dubber: Dubber?
    @ObservationIgnored private var overlay: OverlayController?
    @ObservationIgnored private var screenHotkey: ScreenHotkey?

    init(engine: Engine = .shared) {
        self.engine = engine
        store = engine.store
        // Paket güncellemeleri indirmeden önce sorulur (Windows ile aynı).
        engine.autoRefreshPacks = false
        // DeepL anahtarı Anahtar Zinciri'nde (Windows'taki gibi); ayar dosyasına yazılmaz.
        engine.store.deepLKeyVault = (load: { SecureStore.load(String.self, for: "deepLKey") },
                                      save: { key in key.isEmpty ? SecureStore.remove("deepLKey") : SecureStore.save(key, for: "deepLKey") })
        settings = engine.store.settings
        consoleKind = engine.store.consoleKind
        introSeen = engine.store.introSeen
        tv = TvInstall(packagesDirectory: Bundle.main.url(forResource: "tv-app", withExtension: nil),
                       samsungCaDirectory: Bundle.main.url(forResource: "samsung-ca", withExtension: nil))
        tv.onLog = { [engine] line in engine.note(1, "tv: \(line)") }
        reloadSetup()
        state = EngineState(engine.state())
        engine.onChanged = { [weak self] map in
            MainActor.assumeIsolated { self?.engineChanged(EngineState(map)) }
        }
        engine.onSubtitle = { [weak self] event in
            MainActor.assumeIsolated { self?.subtitleEvent(event) }
        }
        engine.onRegion = { [weak self] event in
            MainActor.assumeIsolated { self?.regionEvent(event) }
        }
        engine.onPair = { [weak self] source, translation in
            MainActor.assumeIsolated { self?.speak(source: source, translation: translation) }
        }
        overlay = OverlayController(model: self)
        screenHotkey = ScreenHotkey { [weak self] in self?.translateScreen() }
        observe { [weak self] in self?.state.active } apply: { [weak self] active in self?.screenHotkey?.enabled = active }
        // OBS katmanı ve ikinci ekran: ayrı görünüm açıksa onun stili, değilse TV'ninki.
        observe { [weak self] () -> SubtitleStyle?? in
            guard let self else { return nil }
            return .some(self.preferences.obsSeparateStyle ? self.preferences.obsStyle.subtitleStyle : nil)
        } apply: { [weak self] style in self?.engine.setViewerStyle(style) }
        observe { [weak self] in self.map { DubbingChoice(enabled: $0.preferences.dubbingEnabled, toTV: $0.preferences.dubbingOutputTV) } }
            apply: { [weak self] choice in self?.dubbingChanged(choice) }
    }

    private func engineChanged(_ new: EngineState) {
        let old = state
        state = new
        if new.phase != old.phase || new.streaming && coverFrame == nil {
            coverFrame = (source as? ScreenCaptureSource)?.contentFrame
        }
        if !new.active && old.active { regionEvent(.clearAll) }
        followWindow(new.active)
    }

    /// Pencere kaynağında "altyazıyı ört" yerleşiminde katman pencereyi izler: pencere taşınır ya da boyutu değişirse
    /// katmanın yeri saniyede bir güncellenir.
    private func followWindow(_ active: Bool) {
        guard active, source is ScreenCaptureSource else {
            windowWatch?.cancel()
            windowWatch = nil
            return
        }
        guard windowWatch == nil else { return }
        windowWatch = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, let capture = self.source as? ScreenCaptureSource,
                      self.preferences.overlayPlacement == .cover else { continue }
                let frame = capture.currentContentFrame()
                if frame != self.coverFrame { self.coverFrame = frame }
            }
        }
    }

    func subtitleEvent(_ event: [String: Any]) {
        if event["type"] as? String == "subtitle" {
            screenSubtitle = event["text"] as? String
            screenSubtitleBox = TextBox.fromEvent(event["box"])
        } else {
            screenSubtitle = nil
            screenSubtitleBox = nil
        }
    }

    func regionEvent(_ event: RegionEvent) {
        switch event {
        case .clearAll:
            screenRegions.removeAll()
        case .show(let id, let text, let box, let lineHeight):
            screenRegions.removeAll { $0.id == id }
            if let box, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                screenRegions.append(OverlayRegion(id: id, text: text, box: box, lineHeight: lineHeight))
            }
        }
    }

    func reloadSetup() {
        console = store.console
        hasAccount = store.accountId != nil
    }

    // MARK: - Çeviri

    func start() {
        // Çeviri açıkken Mac uyumasın (PS5 oturumu ve TV sunucusu sürsün).
        sleepActivity = ProcessInfo.processInfo.beginActivity(options: [.idleSystemSleepDisabled, .userInitiated],
                                                             reason: "GameTranslator çeviri yapıyor")
        coverFrame = nil
        source = makeSource()
        if let source { engine.start(source: source) } else { engine.start() }
        if preferences.dubbingEnabled, DubbingModelStore.isReady { makeDubber().prepare() }
    }

    /// Tercihteki görüntü kaynağı; konsolda nil (motor PS5/Xbox oturumunu kendisi açar).
    private func makeSource() -> FrameSource? {
        switch preferences.videoSource {
        case .console: nil
        case .captureCard: CaptureCardSource(deviceID: preferences.captureDeviceID)
        case .window: ScreenCaptureSource(target: .window(bundleID: preferences.windowBundleID, title: preferences.windowTitle))
        case .display: ScreenCaptureSource(target: .display(preferences.displayID))
        }
    }

    func stop() {
        engine.stop()
        source = nil
        coverFrame = nil
        shutdownDubber()
        if let sleepActivity { ProcessInfo.processInfo.endActivity(sleepActivity) }
        sleepActivity = nil
    }

    func toggle() { state.active ? stop() : start() }

    private var sleepActivity: NSObjectProtocol?

    func sendTestSubtitle() { engine.sendTestSubtitle() }

    /// ⌥⌘T: oyunun şu anki karesinin tamamı çevrilip TV'de yazıların üstüne konur; yeniden basmak kaldırır (motor:
    /// Engine.translateScreen). Kısayol yalnız çeviri açıkken tutulur.
    func translateScreen() {
        guard state.active else { return }
        engine.translateScreen()
    }

    // MARK: - Dublaj

    private struct DubbingChoice: Equatable {
        var enabled: Bool
        var toTV: Bool
    }

    private var dubbingOutput: DubbingOutput { preferences.dubbingOutputTV ? .tv : .computer }

    private var serverPort: UInt16 { UInt16(clamping: engine.state()["serverPort"] as? Int ?? state.serverPort) }

    @discardableResult
    private func makeDubber() -> Dubber {
        if let dubber {
            dubber.update(serverPort: serverPort)
            return dubber
        }
        var settings = DubbingSettings()
        settings.enabled = true
        settings.output = dubbingOutput
        settings.cast = preferences.dubbingCast
        let created = Dubber(server: engine.subtitleServer, serverPort: serverPort, settings: settings)
        created.onCastChanged = { [weak self] cast in self?.preferences.dubbingCast = cast }
        created.onError = { [weak self] message in
            self?.dubbingError = message
            self?.engine.note(1, "dublaj: \(message)")
        }
        dubber = created
        return created
    }

    /// Her yeni çeviri seslendirilir (dublaj açıksa ve ses modeli indiyse).
    private func speak(source: String, translation: String) {
        guard preferences.dubbingEnabled, DubbingModelStore.isReady, state.active else { return }
        let lines = DubbingScript.lines(source: source, translation: translation)
        guard !lines.isEmpty else { return }
        makeDubber().speak(lines)
    }

    private func dubbingChanged(_ choice: DubbingChoice) {
        guard let dubber else {
            if choice.enabled, state.active, DubbingModelStore.isReady { makeDubber().prepare() }
            return
        }
        if !choice.enabled { return shutdownDubber() }
        let output: DubbingOutput = choice.toTV ? .tv : .computer
        if dubber.output != output {
            dubber.stop()
            dubber.output = output
        }
    }

    private func shutdownDubber() {
        guard let dubber else { return }
        self.dubber = nil
        Task { await dubber.shutdown() }
    }

    // MARK: - PS5 hesabı ve eşleştirme

    /// PSN girişinin dönüş adresinden hesap kimliği (8 bayt) alınır ve saklanır.
    func finishPSNLogin(redirect: URL) async throws {
        let id = try await PSNAccount.accountId(fromRedirect: redirect)
        store.accountId = id
        reloadSetup()
    }

    func forgetAccount() {
        store.accountId = nil
        store.console = nil
        reloadSetup()
    }

    func forgetConsole() {
        store.console = nil
        reloadSetup()
    }

    func discover(hosts: [String] = []) async -> [[String: Any]] { await engine.discover(extra: hosts, timeoutMs: 3000) }

    /// PS5'in "Cihazı Bağla" koduyla eşleştirme; başarısızsa yerelleştirilecek ileti kodu döner.
    func register(host: String, pin: Int) async -> (ok: Bool, detail: String?) {
        let outcome = await engine.register(host: host, pin: pin)
        reloadSetup()
        return (outcome["ok"] as? Bool ?? false, outcome["detail"] as? String)
    }

    // MARK: - Yardımcılar

    func snapshot(width: Int = 1280) -> NSImage? {
        engine.snapshot(maxWidth: width).flatMap { NSImage(data: $0) }
    }

    var logs: [String] { engine.logLines() }
    var history: [[String: Any]] { engine.historyEntries() }
    func clearHistory() { engine.clearHistory() }

    /// "@anahtar␟değer" biçimindeki motor iletisinin okunur hâli.
    func describe(_ message: String) -> String { MessageText.text(message) }
}

/// Gözlenen değer değiştikçe `apply` çağrılır (ilk değerle de bir kez). `read` nil dönerse (sahibi gitti) gözlem biter.
@MainActor
private func observe<T: Equatable>(_ read: @escaping @MainActor () -> T?, apply: @escaping @MainActor (T) -> Void, last: T? = nil) {
    var value: T?
    withObservationTracking { value = read() } onChange: {
        Task { @MainActor in observe(read, apply: apply, last: value) }
    }
    guard let value, value != last else { return }
    apply(value)
}
