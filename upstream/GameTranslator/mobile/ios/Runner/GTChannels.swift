// SPDX-License-Identifier: AGPL-3.0-only

import Flutter
import GTNative
import RemotePlay
import SafariServices
import SwiftUI
import UIKit
#if canImport(Translation)
import Translation
#endif

/// Flutter arayüzü ile yerel motor (GTNative.Engine) arasındaki köprü; Android'deki MainActivity.kt ile aynı kanallar,
/// yöntemler ve dönüş biçimleri: "gt/bridge" yöntem kanalı, "gt/events" durum akışı, "gt/subtitles" altyazı olayları,
/// "gt/tv" ve "gt/tv/events" TV'ye uygulama kurma (iPhone'da henüz kurulum yok).
final class GTChannels: NSObject {
    private let engine = Engine.shared
    private var store: Store { engine.store }

    static func register(with registrar: FlutterPluginRegistrar) {
        let channels = GTChannels()
        let messenger = registrar.messenger()
        FlutterMethodChannel(name: "gt/bridge", binaryMessenger: messenger).setMethodCallHandler(channels.handle)
        FlutterEventChannel(name: "gt/events", binaryMessenger: messenger).setStreamHandler(StateStream(engine: channels.engine))
        FlutterEventChannel(name: "gt/subtitles", binaryMessenger: messenger).setStreamHandler(SubtitleStream(engine: channels.engine))
        FlutterMethodChannel(name: "gt/tv", binaryMessenger: messenger).setMethodCallHandler(channels.handleTv)
        channels.tvStages.install = channels.tv
        FlutterEventChannel(name: "gt/tv/events", binaryMessenger: messenger).setStreamHandler(channels.tvStages)
        retained.append(channels)
        Engine.shared.note(2, "kanallar kuruldu")
        handleDebugArguments()
    }

    /// Yalnız hata ayıklama derlemesinde (Android'deki handleDebugIntent): başlatma argümanlarıyla eşleştirme aktarımı ve
    /// otomatik başlatma, uçtan uca denemeler için.
    /// `xcrun simctl launch booted <paket> -gt_import '{"host":..,"name":..,"registKey":b64,"morning":b64,"accountId":b64}'
    ///  -gt_settings '{..}' -gt_start YES`
    static func handleDebugArguments() {
        #if DEBUG
        // UserDefaults "{" ile başlayan değeri özellik listesi sanıp okuyamıyor; argümanlar doğrudan okunur.
        let list = ProcessInfo.processInfo.arguments
        func value(_ key: String) -> String? {
            guard let index = list.firstIndex(of: "-" + key), index + 1 < list.count else { return nil }
            return list[index + 1]
        }
        let store = Engine.shared.store
        if let text = value("gt_import"),
           let json = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any],
           let key = (json["registKey"] as? String).flatMap(Store.decode), key.count == 16,
           let morning = (json["morning"] as? String).flatMap(Store.decode), morning.count == 16,
           let account = (json["accountId"] as? String).flatMap(Store.decode), account.count == 8,
           let host = json["host"] as? String {
            store.accountId = account
            store.console = RegisteredConsole(host: host, name: json["name"] as? String ?? "PS5", registKey: key, morning: morning)
            store.introSeen = true
            Engine.shared.note(2, "hata ayıklama: eşleştirme aktarıldı (\(host))")
        }
        if let text = value("gt_settings"), let settings = Settings.from(text: text) {
            store.settings = settings
            Engine.shared.applySettings()
        }
        if value("gt_start").map({ ["yes", "true", "1"].contains($0.lowercased()) }) == true {
            UIApplication.shared.isIdleTimerDisabled = true
            Engine.shared.start()
        }
        #endif
    }

    /// Kanallar uygulama boyunca yaşar.
    private static var retained: [GTChannels] = []

    // MARK: - gt/bridge

    private func handle(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) {
        let args = call.arguments as? [String: Any]
        switch call.method {
        case "getState": result(engine.state())
        case "getSetup": result(setup())
        case "getSettings": result(store.settings.jsonText)
        case "getLanguages": result(Languages.maps())
        case "setSettings":
            guard let text = call.arguments as? String, let settings = Settings.from(text: text) else {
                return result(FlutterError(code: "bad_settings", message: "Ayarlar okunamadı", details: nil))
            }
            store.settings = settings
            engine.applySettings()
            result(nil)
        case "setAccountId":
            guard let bytes = (call.arguments as? String).flatMap(Store.decode), bytes.count == 8 else {
                return result(FlutterError(code: "bad_account", message: "Hesap kimliği 8 bayt olmalı", details: nil))
            }
            store.accountId = bytes
            result(nil)
        case "forgetAccount":
            store.accountId = nil
            store.console = nil
            result(nil)
        case "setIntroSeen":
            store.introSeen = call.arguments as? Bool ?? true
            result(nil)
        case "noticeSeen":
            result(store.noticeSeen(call.arguments as? String ?? ""))
        case "setNoticeSeen":
            store.setNoticeSeen(call.arguments as? String ?? "")
            result(nil)
        case "forgetConsole":
            store.console = nil
            result(nil)
        case "discover":
            let hosts = args?["hosts"] as? [String] ?? []
            let timeout = args?["timeoutMs"] as? Int ?? 3000
            Task {
                let found = await engine.discover(extra: hosts, timeoutMs: timeout)
                await MainActor.run { result(found) }
            }
        case "register":
            let host = (args?["host"] as? String ?? "").trimmingCharacters(in: .whitespaces)
            let pin = args?["pin"] as? Int ?? -1
            guard !host.isEmpty, pin >= 0 else {
                return result(FlutterError(code: "bad_arguments", message: "Adres ve kod gerekli", details: nil))
            }
            Task {
                let outcome = await engine.register(host: host, pin: pin)
                await MainActor.run { result(outcome) }
            }
        case "start":
            // Çeviri açıkken ekran kararmasın (Android'de ön plan hizmeti var; iPhone'da uygulama önde kalmalı).
            UIApplication.shared.isIdleTimerDisabled = true
            engine.start()
            result(nil)
        case "stop":
            UIApplication.shared.isIdleTimerDisabled = false
            engine.stop()
            result(nil)
        case "snapshot":
            let width = call.arguments as? Int ?? 960
            DispatchQueue.global(qos: .userInitiated).async { [engine] in
                let jpeg = engine.snapshot(maxWidth: width)
                DispatchQueue.main.async { result(jpeg.map { FlutterStandardTypedData(bytes: $0) }) }
            }
        case "testSubtitle":
            engine.sendTestSubtitle()
            result(nil)
        case "logs": result(engine.logLines())
        case "getHistory":
            DispatchQueue.global(qos: .userInitiated).async { [engine] in
                let entries = engine.historyEntries()
                DispatchQueue.main.async { result(entries) }
            }
        case "clearHistory":
            engine.clearHistory()
            result(nil)
        case "keepScreenOn":
            UIApplication.shared.isIdleTimerDisabled = call.arguments as? Bool == true
            result(nil)
        case "getScreenPrefs":
            result(["scale": store.screenScale, "previous": store.screenPrevious, "displayServer": store.displayServer as Any])
        case "setScreenPrefs":
            if let scale = args?["scale"] as? Double { store.screenScale = scale }
            if let previous = args?["previous"] as? Bool { store.screenPrevious = previous }
            result(nil)
        // "Bilgisayarın altyazısını göster": ağdaki GameTranslator'ı bulur, sayfasını telefondaki köprüden açar.
        case "findDisplayServer":
            DispatchQueue.global(qos: .userInitiated).async { [engine] in
                let found = engine.findDisplayServer()
                DispatchQueue.main.async { result(found) }
            }
        case "probeDisplayServer":
            let text = call.arguments as? String ?? ""
            DispatchQueue.global(qos: .userInitiated).async { [engine] in
                let found = engine.probeDisplayServer(text)
                DispatchQueue.main.async { result(found) }
            }
        case "openDisplayRelay":
            let host = args?["host"] as? String ?? "", port = args?["port"] as? Int ?? ServerAddress.defaultPort
            DispatchQueue.global(qos: .userInitiated).async { [engine] in
                let local = try? engine.openDisplayRelay(host: host, port: port)
                DispatchQueue.main.async {
                    result(local ?? FlutterError(code: "relay", message: "Köprü açılamadı", details: nil))
                }
            }
        case "closeDisplayRelay":
            engine.closeDisplayRelay()
            result(nil)
        case "setConsoleKind":
            store.consoleKind = call.arguments as? String ?? Store.kindPS5
            result(nil)
        // --- Xbox (Beta) ---
        case "xboxStartSignIn":
            Task { let outcome = await XboxAccount.shared.startSignIn(); await MainActor.run { result(outcome) } }
        case "xboxAwaitSignIn":
            Task { let outcome = await XboxAccount.shared.awaitSignIn(); await MainActor.run { result(outcome) } }
        case "xboxCancelSignIn":
            XboxAccount.shared.cancelSignIn()
            result(nil)
        case "xboxConsoles":
            Task { let outcome = await XboxAccount.shared.consoles(); await MainActor.run { result(outcome) } }
        case "xboxSelectConsole":
            let id = args?["id"] as? String ?? ""
            guard !id.isEmpty else { return result(FlutterError(code: "bad_arguments", message: "Xbox kimliği gerekli", details: nil)) }
            XboxAccount.shared.selectConsole(id: id, name: args?["name"] as? String ?? "", model: args?["model"] as? String ?? "",
                                             streamingEnabled: args?["streamingEnabled"] as? Bool ?? true,
                                             powerState: args?["powerState"] as? String ?? "")
            result(nil)
        case "xboxForgetConsole":
            XboxAccount.shared.forgetConsole()
            result(nil)
        case "xboxSignOut":
            Task { await XboxAccount.shared.signOut(); await MainActor.run { result(nil) } }
        #if DEBUG
        // Yalnız hata ayıklama: eşleştirme, ayarlar ve geçmiş tek çağrıda (mağaza görselleri ve uçtan uca denemeler).
        case "debugImport":
            let json = args ?? [:]
            if let key = (json["registKey"] as? String).flatMap(Store.decode), key.count == 16,
               let morning = (json["morning"] as? String).flatMap(Store.decode), morning.count == 16,
               let account = (json["accountId"] as? String).flatMap(Store.decode), account.count == 8,
               let host = json["host"] as? String {
                store.accountId = account
                store.console = RegisteredConsole(host: host, name: json["name"] as? String ?? "PS5", registKey: key, morning: morning)
            }
            if let seen = json["introSeen"] as? Bool { store.introSeen = seen }
            if let text = json["settings"] as? String, let settings = Settings.from(text: text) {
                store.settings = settings
                engine.applySettings()
            }
            if let history = json["history"] as? [[String: Any]] {
                engine.debugSeedHistory(history.map { ($0["source"] as? String ?? "", $0["translation"] as? String ?? "", $0["fromPack"] as? Bool ?? false) })
            }
            if let phase = json["phase"] as? String { engine.debugPhase(phase) }
            if let fields = json["state"] as? [String: Any] { engine.debugOverrideState(fields) }
            if let frame = json["frame"] as? FlutterStandardTypedData { engine.debugSetFrame(frame.data) }
            if let line = json["subtitle"] as? String { engine.debugShowSubtitle(line) }
            result(nil)
        #endif
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func setup() -> [String: Any] {
        let console = store.console
        var fields: [String: Any] = [
            "hasAccount": store.accountId != nil,
            "accountId": store.accountId.map(Store.encode) as Any,
            "consoleHost": console?.host as Any,
            "consoleName": console?.name as Any,
            "introSeen": store.introSeen,
            "consoleKind": store.consoleKind,
            "version": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as Any,
            "xboxSignedIn": false,
        ]
        // Microsoft girişi yalnız Xbox seçiliyken Anahtarlık'tan okunur (PlayStation kullananlarda dokunulmaz).
        fields.merge(XboxAccount.shared.setupFields(consoleKind: store.consoleKind)) { _, new in new }
        return fields
    }

    // MARK: - gt/tv (TV'ye uygulama kurma: Android TV, LG, Samsung)

    private lazy var tv: TvInstall = {
        let install = TvInstall(packagesDirectory: Bundle.main.url(forResource: "tv-app", withExtension: nil),
                                samsungCaDirectory: Bundle.main.url(forResource: "samsung-ca", withExtension: nil))
        install.onLog = { [engine] line in engine.note(1, "tv: \(line)") }
        return install
    }()
    private let tvStages = TvStageStream()
    /// Samsung girişi uygulamanın içindeki Safari görünümünde açılır: Safari uygulamasına geçilirse iPhone uygulamayı
    /// askıya alır ve telefondaki geri dönüş dinleyicisi (127.0.0.1:4794) yanıt veremez.
    private weak var browser: SFSafariViewController?
    private var samsungSignInOpen = false

    private func handleTv(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) {
        let args = call.arguments as? [String: Any]
        let tv = self.tv
        tv.uiLanguage = store.settings.uiLanguage
        func run(_ body: @escaping () async throws -> Any?) {
            Task {
                do {
                    let value = try await body()
                    await MainActor.run { result(value) }
                } catch let error as TvInstallArgumentError {
                    await MainActor.run { result(FlutterError(code: error.code, message: error.message, details: nil)) }
                } catch {
                    await MainActor.run { result(FlutterError(code: "tv", message: "\(error)", details: nil)) }
                }
            }
        }
        switch call.method {
        case "bundled": result(tv.bundled())
        case "phoneAddress": result(tv.phoneAddress())
        case "saved": result(tv.saved())
        case "forget":
            tv.forget()
            result(nil)
        case "normalize": result(tv.normalize(call.arguments as? String))
        case "find":
            let brand = call.arguments as? String
            run { try await tv.find(brand) }
        case "check":
            run { try await tv.check(brand: args?["brand"] as? String, host: args?["host"] as? String) }
        case "samsungSignIn":
            samsungSignInOpen = true
            result(tv.samsungSignIn())
        case "samsungSignInWait":
            run {
                let outcome = await tv.samsungSignInWait()
                await MainActor.run {
                    // Giriş bitti (ya da vazgeçildi): sayfa kapanır, kullanıcı kurulum sihirbazına döner.
                    self.samsungSignInOpen = false
                    self.browser?.dismiss(animated: true)
                }
                return outcome
            }
        case "samsungSignInAbort":
            samsungSignInOpen = false
            tv.samsungSignInAbort()
            result(nil)
        case "openUrl":
            guard let text = call.arguments as? String, let url = URL(string: text), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else {
                return result(false)
            }
            result(present(url))
        case "install":
            run {
                try await tv.install(brand: args?["brand"] as? String, host: args?["host"] as? String, name: args?["name"] as? String,
                                     passphrase: args?["passphrase"] as? String, force: args?["force"] as? Bool ?? false)
            }
        case "cancel":
            tv.cancel()
            result(nil)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    /// Adresi uygulamanın içinde Safari görünümünde açar.
    private func present(_ url: URL) -> Bool {
        guard let root = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController else {
            return false
        }
        var top = root
        while let next = top.presentedViewController { top = next }
        let safari = SFSafariViewController(url: url)
        safari.delegate = self
        browser = safari
        top.present(safari, animated: true)
        return true
    }
}

/// "gt/events": motor durumu (bağlanınca hemen bir kez, sonra değiştikçe).
private final class StateStream: NSObject, FlutterStreamHandler {
    let engine: Engine
    init(engine: Engine) { self.engine = engine }

    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        engine.onChanged = { state in events(state) }
        events(engine.state())
        return nil
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        engine.onChanged = nil
        return nil
    }
}

/// "gt/subtitles": "Bu telefonda göster" ekranının altyazı olayları.
private final class SubtitleStream: NSObject, FlutterStreamHandler {
    let engine: Engine
    init(engine: Engine) { self.engine = engine }

    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        engine.onSubtitle = { event in events(event) }
        return nil
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        engine.onSubtitle = nil
        return nil
    }
}

/// "gt/tv/events": kurulum aşamaları (`{"stage": ...}`).
final class TvStageStream: NSObject, FlutterStreamHandler {
    weak var install: TvInstall?

    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        install?.onStage = { stage in events(["stage": stage]) }
        return nil
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        install?.onStage = nil
        return nil
    }
}

extension GTChannels: SFSafariViewControllerDelegate {
    /// Kullanıcı Samsung giriş sayfasını bitirmeden kapattı: bekleyen giriş hemen biter.
    func safariViewControllerDidFinish(_ controller: SFSafariViewController) {
        if samsungSignInOpen {
            samsungSignInOpen = false
            tv.samsungSignInAbort()
        }
    }
}
// MARK: - Apple Çeviri (iOS 18–25)

/// iOS 18–25'te Apple'ın çeviri oturumu yalnız bir SwiftUI görünümünün `.translationTask`'ından alınır. Pencereye
/// eklenen 1 noktalık görünmez görünüm oturumu açık tutar; GTNative istekleri buraya gönderir (iOS 26'da gerekmez:
/// oturum doğrudan kurulur).
@available(iOS 18.0, *)
final class TranslationBridge: ObservableObject, @unchecked Sendable {
    static let shared = TranslationBridge()

    struct Request {
        let text: String
        let reply: CheckedContinuation<String?, Never>
    }

    @Published var configuration: TranslationSession.Configuration?
    private var pair = ""
    private let lock = NSLock()
    private var pending: [Request] = []
    private var signal: AsyncStream<Void>.Continuation?
    let wakeups: AsyncStream<Void>

    private init() {
        var continuation: AsyncStream<Void>.Continuation!
        wakeups = AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation = $0 }
        signal = continuation
    }

    /// Kaynak → hedef çevirisi; oturum 8 sn'de yanıt vermezse nil.
    func translate(_ text: String, source: String, target: String) async -> String? {
        let key = source + ">" + target
        await MainActor.run {
            if pair != key {
                pair = key
                configuration = TranslationSession.Configuration(source: Locale.Language(identifier: source),
                                                                 target: Locale.Language(identifier: target))
            }
        }
        return await withTaskGroup(of: String?.self) { group in
            group.addTask {
                await withCheckedContinuation { reply in
                    self.lock.withLock { self.pending.append(Request(text: text, reply: reply)) }
                    self.signal?.yield()
                }
            }
            group.addTask {
                try? await Task.sleep(for: .seconds(8))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    /// Görünümün `translationTask`'ında çalışır: bekleyen istekleri oturumla çevirir.
    func serve(_ session: TranslationSession) async {
        for await _ in wakeups {
            while let request = lock.withLock({ pending.isEmpty ? nil : pending.removeFirst() }) {
                let result = try? await session.translate(request.text)
                request.reply.resume(returning: result?.targetText)
            }
            if Task.isCancelled { return }
        }
    }
}

@available(iOS 18.0, *)
private struct TranslationHostView: View {
    @ObservedObject var bridge = TranslationBridge.shared

    var body: some View {
        Color.clear
            .frame(width: 1, height: 1)
            .translationTask(bridge.configuration) { session in await bridge.serve(session) }
    }
}

enum TranslationHost {
    /// Görünmez çeviri görünümünü pencereye ekler ve GTNative'in kancasını kurar (iOS 18–25).
    static func install(in window: UIWindow?) {
        guard #available(iOS 18.0, *), !isNewSystem, let root = window?.rootViewController else { return }
        let host = UIHostingController(rootView: TranslationHostView())
        host.view.frame = CGRect(x: 0, y: 0, width: 1, height: 1)
        host.view.isUserInteractionEnabled = false
        host.view.backgroundColor = .clear
        root.addChild(host)
        root.view.addSubview(host.view)
        host.didMove(toParent: root)
        AppleTranslator.hosted = { text, source, target in
            await TranslationBridge.shared.translate(text, source: source, target: target)
        }
    }

    private static var isNewSystem: Bool {
        if #available(iOS 26.0, *) { return true }
        return false
    }
}
