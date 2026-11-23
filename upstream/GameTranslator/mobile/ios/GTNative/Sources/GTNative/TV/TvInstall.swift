// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Kanal çağrısının bozuk argümanı (Android'de `result.error(code, message, null)`). Runner bunu FlutterError'a çevirir.
public struct TvInstallArgumentError: Error, Equatable {
    public let code: String
    public let message: String
}

/// Telefondan TV uygulaması kurma sihirbazının yerel tarafı (Android: TvInstallChannel.kt). Yöntemler "gt/tv" kanalının
/// yöntemleriyle bire birdir ve Flutter'a doğrudan verilebilecek değerler döner; ilerleme `onStage` ile "gt/tv/events"
/// akışına gider. Ağ işleri arka planda yapılır; aynı anda tek kurulum çalışır ve `cancel` ile kesilir. `openUrl`
/// Runner'dadır.
public final class TvInstall: @unchecked Sendable {
    /// Kurulum aşaması: connecting, approve, checking, key, reading, certificate, signing, uninstalling,
    /// `uploading:<yüzde>`, installing, verifying, launching. Ana iş parçacığında çağrılır.
    public var onStage: ((String) -> Void)?
    /// Günlüğe yazılacak satırlar (Android'deki Log.w); Runner bunu Engine.note'a bağlayabilir. Arka planda çağrılır.
    public var onLog: ((String) -> Void)?
    /// TV uygulamasının bağlanacağı altyazı sunucusunun portu (Android'de sabit 8787).
    public var serverPort = ServerAddress.defaultPort
    /// Samsung giriş sayfasının son ekranının dili (Ayarlar'daki arayüz dili; "auto" telefonun dili).
    public var uiLanguage = UiText.auto

    private let packagesDirectory: URL?
    private let store: TvStore
    private let makeCertificates: () -> SamsungCertificates
    private let job = TvLocked<Cancel?>(nil)
    /// Samsung hesabı: yalnız bellekte tutulur; sertifika alındıktan sonra o TV için bir daha gerekmez.
    private let samsungAccount = TvLocked<SamsungAccount?>(nil)
    private let signIn = TvLocked<SamsungLogin.Listener?>(nil)
    private let caDirectory: URL?
    private let loadedCas = TvLocked<[X509Cert]?>(nil)

    static let signInTimeoutMs = 10 * 60 * 1000

    /// `packagesDirectory`: uygulamayla gelen TV paketlerinin klasörü (.apk, .ipk, .wgt; adları Android'deki
    /// assets/tv-app ile aynı, ör. `gametranslator-androidtv_1.0.12.apk`). `samsungCaDirectory`: Samsung ara
    /// sertifikalarının klasörü (assets/samsung-ca); nil ya da eksikse koda gömülü kopyaları kullanılır.
    public convenience init(packagesDirectory: URL?, samsungCaDirectory: URL? = nil) {
        self.init(packagesDirectory: packagesDirectory, samsungCaDirectory: samsungCaDirectory,
                  store: TvStore(defaults: UserDefaults(suiteName: "gametranslator") ?? .standard, secrets: KeychainSecrets()))
    }

    init(packagesDirectory: URL?, samsungCaDirectory: URL? = nil, store: TvStore,
         certificates: @escaping () -> SamsungCertificates = { SamsungCertificates() }) {
        self.packagesDirectory = packagesDirectory
        caDirectory = samsungCaDirectory
        self.store = store
        makeCertificates = certificates
    }

    private var samsungCas: [X509Cert] {
        loadedCas.update { cached in
            if let cached { return cached }
            let loaded = SamsungCa.load(from: caDirectory)
            cached = loaded
            return loaded
        }
    }

    // MARK: - Kanal yöntemleri

    /// Uygulamayla gelen TV paketlerinin sürümleri (marka → sürüm).
    public func bundled() -> [String: String] {
        bundledApps().reduce(into: [:]) { $0[$1.key.rawValue] = $1.value.version }
    }

    public func phoneAddress() -> String? { TvNetwork.privateAddresses().first }

    public func saved() -> [String: Any]? { store.tv }

    public func forget() {
        if let host = store.tv?["host"] as? String { store.setLgKey(host, nil) }
        store.tv = nil
    }

    /// Elle yazılan adresi temizler; adres değilse nil.
    public func normalize(_ text: String?) -> String? { ManualAddress.normalize(text) }

    /// Ağdaki TV'ler (`brand`: "lg", "samsung", "android"). Vazgeçilirse ya da arama hata verirse boş liste.
    public func find(_ brand: String?) async throws -> [[String: Any]] {
        guard let kind = TvBrand.of(brand) else { throw TvInstallArgumentError(code: "bad_brand", message: "marka") }
        let cancel = Cancel()
        job.set(cancel)
        do {
            return try await tvBackground { try TvDiscovery.find(kind, cancel).map { $0.toMap() } }
        } catch {
            log("TV araması başarısız: \(tvMessage(error))")
            return []
        }
    }

    /// Adım ekranlarının canlı denetimi.
    public func check(brand: String?, host: String?) async throws -> [String: Any] {
        guard let kind = TvBrand.of(brand) else { throw TvInstallArgumentError(code: "bad_brand", message: "marka") }
        guard let host else { throw TvInstallArgumentError(code: "bad_host", message: "adres") }
        return await guarded {
            if kind == .samsung { return try self.samsungCheck(host) }
            return [
                "ready": TvDiscovery.isReady(kind, host),
                "keyServer": kind == .lg && TvDiscovery.isKeyServerOn(host),
                "hasKey": kind == .lg && self.store.lgKey(host) != nil,
            ]
        }
    }

    /// Samsung girişi: dinleyiciyi açar ve giriş sayfasının adresini döner; sayfa Flutter'daki WebView'da (ya da Runner'ın
    /// uygulama içi tarayıcısında) açılır. Dinleyici açılamazsa nil.
    public func samsungSignIn() -> String? {
        signIn.get?.close()
        do {
            signIn.set(try SamsungLogin.Listener(language: uiLanguage).start())
            return SamsungLogin.authorizeUrl
        } catch {
            signIn.set(nil)
            log("Samsung giriş dinleyicisi açılamadı: \(tvMessage(error))")
            return nil
        }
    }

    /// Girişin bitmesini bekler (en çok 10 dakika): `{"ok": true, "email": …}` ya da hata.
    public func samsungSignInWait() async -> [String: Any] {
        guard let listener = signIn.get else { return failure(TvInstallError(.SIGN_IN_FAILED, "giriş başlatılmadı")) }
        let result = await guarded { () -> [String: Any] in
            defer {
                listener.close()
                self.signIn.update { if $0 === listener { $0 = nil } }
            }
            let account = try listener.await(timeoutMs: Self.signInTimeoutMs, cancel: .none)
            self.samsungAccount.set(account)
            return ["ok": true, "email": account.email]
        }
        return result
    }

    public func samsungSignInAbort() { signIn.get?.abort("giriş sayfası kapatıldı") }

    /// TV uygulamasını kurar ya da günceller ve açar: `{"ok", "version", "installed", "launched", "hoursLeft"}` ya da
    /// `{"ok": false, "error", "detail"}`.
    public func install(brand: String?, host: String?, name: String?, passphrase: String?, force: Bool) async throws -> [String: Any] {
        guard let kind = TvBrand.of(brand) else { throw TvInstallArgumentError(code: "bad_brand", message: "marka") }
        guard let host else { throw TvInstallArgumentError(code: "bad_host", message: "adres") }
        let cancel = Cancel()
        job.update { previous in
            previous?.cancel()
            previous = cancel
        }
        do {
            return try await install(kind, host, name ?? "", passphrase, force, cancel)
        } catch {
            log("TV kurulumu başarısız: \(tvMessage(error))")
            return failure(error)
        }
    }

    public func cancel() { job.get?.cancel() }

    // MARK: - Kurulum

    struct BundledApp {
        let url: URL
        let version: String
        var fileName: String { url.lastPathComponent }
    }

    func bundledApps() -> [TvBrand: BundledApp] {
        guard let directory = packagesDirectory,
              let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) else { return [:] }
        var out: [TvBrand: BundledApp] = [:]
        for file in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            let name = file.lastPathComponent
            let brand: TvBrand
            if name.hasSuffix(".ipk") { brand = .lg } else if name.hasSuffix(".apk") { brand = .android } else if name.hasSuffix(".wgt") { brand = .samsung } else { continue }
            guard let version = Versions.ofBundledFile(name) else { continue }
            out[brand] = BundledApp(url: file, version: version)
        }
        return out
    }

    private func install(_ brand: TvBrand, _ host: String, _ name: String, _ passphrase: String?, _ force: Bool, _ cancel: Cancel) async throws -> [String: Any] {
        guard let app = bundledApps()[brand] else { throw TvInstallError(.PACKAGE_MISSING, "uygulama paketinde \(brand.rawValue) paketi yok") }
        let bytes: Data
        do { bytes = try Data(contentsOf: app.url) } catch { throw TvInstallError(.PACKAGE_MISSING, error.localizedDescription) }
        let server = phoneAddress().map { "http://\($0):\(serverPort)" }
        let outcome: [String: Any]
        switch brand {
        case .android:
            outcome = try await tvBackground {
                let installer = AndroidTvInstaller(host: host, key: { try self.store.deviceKey("adbkey") })
                installer.onProgress = { self.emit($0) }
                let done = try installer.ensureInstalled(bytes, version: app.version, cancel: cancel, server: server, force: force)
                return ["ok": true, "version": done.version, "installed": done.installed, "launched": true]
            }
        case .lg:
            outcome = try await installLg(host, passphrase, app, bytes, server, force, cancel)
        case .samsung:
            outcome = try await tvBackground {
                self.emit("connecting")
                let installer = try self.samsungInstaller(host, cancel)
                installer.onProgress = { self.emit($0) }
                installer.account = { self.samsungAccount.get }
                let launched: Bool
                do {
                    launched = try installer.install(bytes)
                } catch let error as TvInstallError {
                    // Jeton geçersizse (süresi dolmuş) bir sonraki denemede yeniden giriş istenir.
                    if error.error == .CERTIFICATE_FAILED { self.samsungAccount.set(nil) }
                    throw error
                }
                return ["ok": true, "version": app.version, "installed": true, "launched": launched]
            }
        }
        store.tv = ["brand": brand.rawValue, "host": host, "name": name, "version": outcome["version"] ?? NSNull(),
                    "installedAt": Int64(Date().timeIntervalSince1970 * 1000)]
        return outcome
    }

    private func installLg(_ host: String, _ passphrase: String?, _ app: BundledApp, _ bytes: Data, _ server: String?, _ force: Bool,
                           _ cancel: Cancel) async throws -> [String: Any] {
        emit("connecting")
        let open = try await tvBackground { TvSocket.isPortOpen(host, LgInstaller.sshPort, timeoutMs: 3000) }
        if !open { throw TvInstallError(.UNREACHABLE, "9922 kapalı") }
        var key = store.lgKey(host)
        let typed = (passphrase ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if key == nil || !typed.isEmpty {
            if typed.isEmpty { throw TvInstallError(.WRONG_PASSPHRASE, "parola gerekli") }
            emit("key")
            key = try await tvBackground { try LgInstaller.fetchKey(host, passphrase ?? "", cancel: cancel) }
        }
        guard let key else { throw TvInstallError(.WRONG_PASSPHRASE, "parola gerekli") }
        let shell = LgSsh(host: host, key: key, cancel: cancel)
        do {
            let installer = LgInstaller(shell: shell, cancel: cancel)
            emit("checking")
            let current: String?
            do {
                current = try await installer.installedVersion()
            } catch let error as TvInstallError {
                // Kayıtlı anahtar artık geçmiyorsa (geliştirici modu yeniden açılmış) unutulur; parola yeniden sorulur.
                if error.error == .KEY_REFUSED { store.setLgKey(host, nil) }
                throw error
            }
            store.setLgKey(host, key)
            let needs = force || current == nil || Versions.isNewer(app.version, current!)
            if needs { try await installer.install(bytes, fileName: app.fileName, version: app.version, onProgress: { [weak self] in self?.emit($0) }) }
            emit("launching")
            let launched = await installer.launch(server)
            let hours = await installer.renewSession()
            await shell.close()
            return ["ok": true, "version": (needs ? app.version : current) ?? NSNull(), "installed": needs, "launched": launched,
                    "hoursLeft": hours ?? NSNull()]
        } catch {
            await shell.close()
            throw error
        }
    }

    private func samsungInstaller(_ host: String, _ cancel: Cancel, connectTimeoutMs: Int = 5_000) throws -> SamsungInstaller {
        let key = try store.deviceKey("sdbkey")
        return SamsungInstaller(store: store, cas: samsungCas, certificates: makeCertificates(), cancel: cancel,
                                connector: SamsungInstaller.connector(host: host, key: key, cancel: cancel, connectTimeoutMs: connectTimeoutMs),
                                diagnose: { SamsungTvInfo.diagnose(SamsungTvInfo.fetch(host, timeoutMs: 2000), TvNetwork.privateAddresses()) })
    }

    /// Samsung adım ekranlarının canlı denetimi: TV'nin bildirdiği geliştirici modu ve "Host PC IP", sdb bağlantısı bu
    /// telefondan kurulabiliyor mu ve bu TV için sertifika kayıtlı mı.
    private func samsungCheck(_ host: String) throws -> [String: Any] {
        let info = SamsungTvInfo.fetch(host, timeoutMs: 1500)
        let phones = TvNetwork.privateAddresses()
        var ready = false
        var hasCertificate = false
        if TvSocket.isPortOpen(host, SamsungInstaller.sdbPort, timeoutMs: 1500),
           let status = try? samsungInstaller(host, Cancel(), connectTimeoutMs: 2_000).status() {
            ready = true
            hasCertificate = status.1
        }
        return [
            "ready": ready,
            "keyServer": false,
            "hasKey": hasCertificate,
            "signedIn": samsungAccount.get != nil,
            "reachable": info != nil,
            "developerMode": info?.developerMode ?? NSNull(),
            "developerIp": info?.developerIp ?? NSNull(),
            "hostIpMatches": info?.developerIp.map { phones.contains($0) } ?? NSNull(),
            "year": info?.year ?? NSNull(),
            "name": info?.name ?? NSNull(),
        ]
    }

    // MARK: - Yardımcılar

    private func emit(_ stage: String) {
        DispatchQueue.main.async { [weak self] in self?.onStage?(stage) }
    }

    private func log(_ text: String) { onLog?(text) }

    /// Bloklayan işi arka planda çalıştırır; hata olursa hata eşlemesi döner (Android'deki `async`).
    private func guarded(_ block: @escaping () throws -> [String: Any]) async -> [String: Any] {
        do {
            return try await tvBackground(block)
        } catch {
            log("TV işi başarısız: \(tvMessage(error))")
            return failure(error)
        }
    }

    func failure(_ error: Error) -> [String: Any] {
        let known = error as? TvInstallError
        return ["ok": false, "error": (known?.error ?? .INSTALL_FAILED).rawValue, "detail": known.map { $0.detail ?? $0.error.rawValue } ?? tvMessage(error)]
    }
}
