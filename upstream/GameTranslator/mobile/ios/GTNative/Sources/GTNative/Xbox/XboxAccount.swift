// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Xbox (Beta) için Microsoft girişi ve konsol seçimi (Android: XboxAccount.kt): arayüzün (Flutter) köprüden çağırdığı
/// işler. Giriş cihaz koduyla yapılır; parola Microsoft'un sayfasına yazılır, uygulama onu görmez. Kalıcı olan yalnız
/// Microsoft kullanıcı jetonudur ve Anahtar Zinciri'nde durur; başka hiçbir yere gönderilmez. Seçili Xbox Android'deki
/// gibi "gametranslator" UserDefaults alanında `xboxConsole` anahtarındadır (JSON).
///
/// Dönen eşlemeler Android'in köprüye verdikleriyle aynıdır (lib/bridge.dart `xbox*`).
public final class XboxAccount: @unchecked Sendable {
    public static let shared = XboxAccount()

    static let tokenName = "xboxUserToken"
    static let consoleKey = "xboxConsole"

    private let secrets: XboxSecrets
    private let defaults: UserDefaults
    private let http: XboxHttp
    private let clock: () -> Int64
    private let sleep: (Int64) -> Void
    private let work = DispatchQueue(label: "gt-xbox-account", qos: .userInitiated, attributes: .concurrent)

    private let lock = NSLock()
    private var instance: XboxAuth?
    private var pending: XboxDeviceCode?
    /// Her yeni giriş denemesi ve her iptal bunu artırır; eski bekleme kendini iptal edilmiş sayar.
    private var attempt = 0

    public convenience init() {
        self.init(secrets: XboxKeychain(), defaults: UserDefaults(suiteName: "gametranslator") ?? .standard)
    }

    init(secrets: XboxSecrets, defaults: UserDefaults, http: XboxHttp = URLSessionHttp.shared,
         clock: @escaping () -> Int64 = XboxAuth.wallClock,
         sleep: @escaping (Int64) -> Void = { Thread.sleep(forTimeInterval: Double($0) / 1000) }) {
        self.secrets = secrets
        self.defaults = defaults
        self.http = http
        self.clock = clock
        self.sleep = sleep
    }

    /// Süreçte tek örnek: kullanıcı jetonu her yenilendiğinde Anahtar Zinciri'ne yazılır, çıkışta silinir. Anahtar
    /// Zinciri ilk istekte okunur (PlayStation kullananlarda hiç okunmaz).
    var auth: XboxAuth {
        lock.withLock {
            if let instance { return instance }
            let secrets = self.secrets
            let created = XboxAuth(http: http, clock: clock, sleep: sleep, userToken: XboxUserToken.parse(secrets.get(Self.tokenName)),
                                   onUserToken: { secrets.put(Self.tokenName, $0?.serialize()) })
            instance = created
            return created
        }
    }

    /// Microsoft girişi var mı (Anahtar Zinciri'nde geçerli jeton).
    public var signedIn: Bool { auth.signedIn }

    /// Seçili Xbox.
    public var selectedConsole: XboxConsole? {
        get { XboxConsole.fromJson(defaults.string(forKey: Self.consoleKey)) }
        set {
            if let newValue { defaults.set(newValue.toJson(), forKey: Self.consoleKey) } else { defaults.removeObject(forKey: Self.consoleKey) }
        }
    }

    /// `getSetup`'a eklenecek alanlar (Android MainActivity `setup`): `xboxSignedIn` (Anahtar Zinciri yalnız Xbox
    /// seçiliyken okunur) ve varsa `xboxConsoleName`.
    public func setupFields(consoleKind: String) -> [String: Any] {
        var fields: [String: Any] = ["xboxSignedIn": consoleKind == Store.kindXbox && signedIn]
        if let console = selectedConsole { fields["xboxConsoleName"] = console.label }
        return fields
    }

    // MARK: - Köprü yöntemleri

    /// Girişi başlatır: kullanıcıya gösterilecek kod ve açılacak adres.
    /// `{ok: true, userCode, verificationUri, expiresIn}` ya da `{ok: false, error}`.
    public func startSignIn() async -> [String: Any] { await background { self.startSignInNow() } }

    /// Kullanıcı kodu Microsoft'un sayfasında onaylayana kadar bekler (en çok kodun süresi kadar).
    /// `{ok: true}`, `{ok: false, cancelled: true}` ya da `{ok: false, error}`.
    public func awaitSignIn() async -> [String: Any] { await background { self.awaitSignInNow() } }

    public func cancelSignIn() {
        lock.withLock {
            attempt += 1
            pending = nil
        }
    }

    /// Hesaptaki Xbox'lar: `{ok: true, consoles: [{id, name, model, streamingEnabled, powerState}]}` ya da
    /// `{ok: false, error, needsSignIn}`.
    public func consoles() async -> [String: Any] { await background { self.consolesNow() } }

    public func selectConsole(id: String, name: String, model: String, streamingEnabled: Bool, powerState: String) {
        selectedConsole = XboxConsole(id: id, name: name, model: model, streamingEnabled: streamingEnabled, powerState: powerState)
    }

    /// Seçili Xbox unutulur (giriş kalır).
    public func forgetConsole() {
        selectedConsole = nil
    }

    /// Çıkış: Microsoft jetonu Anahtar Zinciri'nden, seçili Xbox kayıttan silinir.
    public func signOut() async {
        await background {
            self.cancelSignIn()
            self.auth.signOut()
            self.selectedConsole = nil
        }
    }

    // MARK: - Bloklayan sürümler (testler ve arka plan)

    func startSignInNow() -> [String: Any] {
        let mine = lock.withLock { () -> Int in
            attempt += 1
            pending = nil
            return attempt
        }
        do {
            let code = try auth.startDeviceCode()
            lock.withLock { if attempt == mine { pending = code } }
            return ["ok": true, "userCode": code.userCode, "verificationUri": code.verificationUri, "expiresIn": code.expiresInSeconds]
        } catch let error as XboxException {
            return ["ok": false, "error": Self.describeSetup(error)]
        } catch {
            return ["ok": false, "error": "\(error)"]
        }
    }

    func awaitSignInNow() -> [String: Any] {
        guard let (code, mine) = lock.withLock({ pending.map { ($0, attempt) } }) else {
            return ["ok": false, "error": UiText.code("xs_start_first")]
        }
        do {
            try auth.pollDeviceCode(code) { self.lock.withLock { self.attempt != mine } }
            lock.withLock { pending = nil }
            return ["ok": true]
        } catch is XboxCancelled {
            return ["ok": false, "cancelled": true]
        } catch let error as XboxException {
            lock.withLock { pending = nil }
            return ["ok": false, "error": Self.describeSetup(error)]
        } catch {
            lock.withLock { pending = nil }
            return ["ok": false, "error": "\(error)"]
        }
    }

    func consolesNow() -> [String: Any] {
        do {
            let list = try XboxHome(auth: auth).consoles()
            return ["ok": true, "consoles": list.map(\.map)]
        } catch let error as XboxException {
            return ["ok": false, "error": Self.describeSetup(error), "needsSignIn": error.kind == .signInRequired]
        } catch {
            return ["ok": false, "error": "\(error)", "needsSignIn": false]
        }
    }

    private func background<T>(_ body: @escaping () -> T) async -> T {
        await withCheckedContinuation { continuation in
            work.async { continuation.resume(returning: body()) }
        }
    }

    // MARK: - İleti kodları

    /// Kurulum ekranlarında gösterilecek nedenin kodu (metni Flutter seçer: lib/l10n/, `n.xs_*`); beklenmedik yanıtta
    /// teknik ayrıntı parantez içinde gösterilir.
    static func describeSetup(_ error: XboxException) -> String {
        switch error.kind {
        case .signInRequired, .signInDeclined: return UiText.code("xs_signin_incomplete")
        case .codeExpired: return UiText.code("xs_code_expired")
        case .noXboxAccount: return UiText.code("x_no_profile")
        case .network: return UiText.code("xs_network")
        default: return UiText.code("xs_service", error.message.isEmpty ? error.kind.rawValue : error.message)
        }
    }

    /// Çeviri sırasında ana ekranda gösterilecek nedenin kodu (`n.xr_*`).
    public static func describeStream(_ kind: XboxError) -> String {
        switch kind {
        case .signInRequired, .codeExpired, .signInDeclined: return UiText.code("xr_signin_expired")
        case .noXboxAccount: return UiText.code("x_no_profile")
        case .consoleUnavailable: return UiText.code("xr_unavailable")
        case .busy: return UiText.code("xr_busy")
        case .timeout: return UiText.code("xr_timeout")
        case .network: return UiText.code("xr_network")
        case .service: return UiText.code("xr_service")
        }
    }
}
