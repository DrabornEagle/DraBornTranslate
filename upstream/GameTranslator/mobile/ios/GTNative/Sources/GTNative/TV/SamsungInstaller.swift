// SPDX-License-Identifier: AGPL-3.0-only
// Kurulum adımları Windows'taki SamsungTvInstaller.cs'den; sdb komutları ve paket kurulumu TizenSdb.Core'un
// SdbTcpDevice ve TizenInstaller'ından (MIT, Patrick Stel; https://github.com/PatrickSt1991/tizen-sdb) uyarlandı.

import Foundation

/// Samsung TV'nin http://TV:8001/api/v2/ yanıtından kurulum için gerekenler.
struct SamsungTvInfo {
    let name: String?
    let modelName: String?
    let os: String?
    /// Geliştirici modu açık mı; TV söylemiyorsa nil.
    let developerMode: Bool?
    /// Geliştirici modunda girilen "Host PC IP"; yalnız bu adresten gelen sdb bağlantısı kabul edilir.
    let developerIp: String?
    /// Model kodunun başındaki yıl (ör. "20_PONTUSM_FHD" → 2020); bilinmiyorsa nil.
    let year: Int?

    static func parse(_ json: String) -> SamsungTvInfo? {
        guard let object = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              let device = object["device"] as? [String: Any] else { return nil }
        func text(_ name: String) -> String? {
            let value: String
            if let string = device[name] as? String { value = string } else if let number = device[name] as? NSNumber { value = number.stringValue } else { return nil }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        let year = text("model").flatMap { $0.firstMatch(of: #/^(\d{2})_/#) }.flatMap { Int($0.1) }.map { 2000 + $0 }
        let mode: Bool?
        switch text("developerMode") {
        case "1", "true": mode = true
        case "0", "false": mode = false
        default: mode = nil
        }
        return SamsungTvInfo(name: text("name")?.replacingOccurrences(of: "&quot;", with: "\""), modelName: text("modelName"),
                             os: text("OS"), developerMode: mode, developerIp: text("developerIP"), year: year)
    }

    static func fetch(_ host: String, timeoutMs: Int = 2000, cancel: Cancel = .none) -> SamsungTvInfo? {
        (try? LanHttp.get(host, 8001, "/api/v2/", timeoutMs: timeoutMs, cancel: cancel)).flatMap { parse($0.text) }
    }

    /// sdb bağlantısı kurulamadığında nedeni: geliştirici modu kapalı ya da "Host PC IP" bu telefonun adresi değil. TV
    /// bilgi vermiyorsa nil (genel "TV yanıt vermedi").
    static func diagnose(_ info: SamsungTvInfo?, _ phoneAddresses: [String]) -> TvInstallError? {
        guard let info else { return nil }
        if info.developerMode == false { return TvInstallError(.UNREACHABLE, "TV'de Developer mode kapalı") }
        if let ip = info.developerIp, let phone = phoneAddresses.first, !phoneAddresses.contains(ip) {
            return TvInstallError(.WRONG_HOST_IP, "TV'deki Host PC IP \(ip), telefonun adresi \(phone)")
        }
        return nil
    }
}

/// GameTranslator'ın Samsung TV uygulamasını geliştirici modu açık TV'ye kurar (Android: SamsungInstaller.kt): sdb ile
/// bağlanır, TV'nin DUID'ini ve Tizen sürümünü okur, gerekirse Samsung hesabıyla TV'ye özel sertifika alır, paketi
/// imzalar, gönderir, kurar ve açar. Samsung yalnız geliştirici modunda girilen IP'den gelen sdb bağlantısını kabul
/// eder. Bütün çağrılar bloklar.
final class SamsungInstaller {
    static let sdbPort = 26101
    static let appId = "GameTransT.GameTranslator"
    static let packageId = "GameTransT"

    private let store: TvStore
    private let cas: [X509Cert]
    private let certificates: SamsungCertificates
    private let cancel: Cancel
    /// TV'ye bağlanıp karşılaşmayı yapar; testlerde sahte TV.
    private let connector: () throws -> AdbClient
    /// Bağlantı kurulamazsa nedeni bulur (REST bilgisi ve telefonun adresi).
    private let diagnose: () -> TvInstallError?

    var onProgress: (String) -> Void = { _ in }

    /// Sertifika gerekince Samsung hesabı; yoksa nil (kurulum `SIGN_IN_NEEDED` ile durur).
    var account: () -> SamsungAccount? = { nil }

    init(store: TvStore, cas: [X509Cert], certificates: SamsungCertificates, cancel: Cancel, connector: @escaping () throws -> AdbClient,
         diagnose: @escaping () -> TvInstallError? = { nil }) {
        self.store = store
        self.cas = cas
        self.certificates = certificates
        self.cancel = cancel
        self.connector = connector
        self.diagnose = diagnose
    }

    /// TV'nin DUID'i ve bu TV için geçerli sertifika kayıtlı mı (giriş gerekmeden kurulabilir mi)?
    func status() throws -> (String, Bool) {
        let client = try connect(attempts: 1)
        defer { client.close() }
        let duid = try readDuid(client)
        return (duid, SamsungProfile(store: store, duid: duid).isValid())
    }

    /// Paketi imzalayıp kurar ve uygulamayı açar. `wgt`: imzasız ya da imzalı paket. Uygulama açıldıysa true.
    func install(_ wgt: Data) throws -> Bool {
        let client = try connect(attempts: 3)
        defer { client.close() }
        onProgress("reading")
        let capabilities = Self.parseCapability(try cancel.guarded { try client.runRaw("capability:") })
        let platform = (capabilities["platform_version"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !platform.isEmpty && Versions.isNewer("3.0", platform) { throw TvInstallError(.TOO_OLD, "Tizen \(platform)") }
        let toolPath = capabilities["sdk_toolpath"].map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
            ?? "/opt/usr/apps/tmp"

        let duid = try readDuid(client)
        var profile = SamsungProfile(store: store, duid: duid)
        if !profile.isValid() {
            guard let signedIn = account() else { throw TvInstallError(.SIGN_IN_NEEDED, duid) }
            onProgress("certificate")
            profile = try certificates.create(duid: duid, account: signedIn, profile: profile, cancel: cancel)
        }

        onProgress("signing")
        let (author, distributor) = try profile.identities(cas)
        let signed = try SamsungSigner.sign(wgt, author: author, distributor: distributor)
        let remote = "\(toolPath)/gametranslator-\(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()).wgt"
        var log = try attempt(client, signed, remote)
        if Self.installFailed(log) {
            // Başka sertifikayla kurulmuş eski kopya varsa ("Author certificate not match") kaldırıp bir kez daha dene.
            // TV sürümüne göre kaldırma uygulama kimliğini ya da paket kimliğini bekliyor; ikisi de denenir.
            onProgress("uninstalling")
            var removal = ""
            for id in [Self.appId, Self.packageId] {
                do {
                    removal += try cancel.guarded { try client.run("shell:0 vd_appuninstall \(id)") } + "\n"
                } catch let error as TvInstallError where error.error == .CANCELLED {
                    throw error
                } catch {
                    removal += tvMessage(error) + "\n"
                }
            }
            log = try attempt(client, signed, remote)
            if Self.installFailed(log) {
                let error: TvError = log.range(of: "not enough space", options: .caseInsensitive) != nil ? .NOT_ENOUGH_SPACE : .INSTALL_FAILED
                throw TvInstallError(error, Self.tail(log) + "\nuninstall: " + Self.tail(removal))
            }
        }
        onProgress("launching")
        // Açmak isteğe bağlı: açılmazsa kullanıcı uygulamalar listesinden açar.
        return (try? cancel.guarded { try client.run("shell:0 execute \(Self.appId)") }) != nil
    }

    /// Paketi gönderip kurar; TV kurulum hatasını çıktıda söyler, bağlantı hatası da çıktıya yazılır.
    private func attempt(_ client: AdbClient, _ signed: Data, _ remote: String) throws -> String {
        do {
            return try cancel.guarded {
                var last = -1
                client.timeout(60_000)
                let total = max(1, signed.count)
                try client.push(signed, remotePath: remote) { sent in
                    let percent = sent * 100 / total
                    if percent != last {
                        last = percent
                        onProgress("uploading:\(percent)")
                    }
                }
                onProgress("installing")
                client.timeout(300_000)
                return try client.run("shell:0 vd_appinstall \(Self.appId) \(remote)")
            }
        } catch {
            if cancel.cancelled { throw TvInstallError(.CANCELLED) }
            return "install failed: \(tvMessage(error))"
        }
    }

    private func connect(attempts: Int) throws -> AdbClient {
        var last: Error?
        for attempt in 1...attempts {
            try cancel.check()
            do {
                return try connector()
            } catch {
                if cancel.cancelled { throw TvInstallError(.CANCELLED) }
                last = error
                if attempt < attempts { try cancel.sleep(400) }
            }
        }
        if let diagnosis = diagnose() { throw diagnosis }
        if let known = last as? TvInstallError { throw known }
        throw TvInstallError(.UNREACHABLE, last.map(tvMessage))
    }

    private func readDuid(_ client: AdbClient) throws -> String {
        // Yavaş TV'lerde ilk yanıt boş gelebiliyor; birkaç kez dene.
        for _ in 0..<3 {
            let output = try cancel.guarded { try client.run("shell:0 getduid") }
            let duid = (output.split(separator: "\n", omittingEmptySubsequences: false).first.map(String.init) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if Self.isDuid(duid) { return duid }
            try cancel.sleep(500)
        }
        throw TvInstallError(.UNREACHABLE, "DUID okunamadı")
    }

    static func isDuid(_ text: String) -> Bool { text.wholeMatch(of: #/[A-Za-z0-9_\-+=\/]{8,}/#) != nil }

    /// sdb "capability:" yanıtı: iki baytlık önek, sonra `ad:değer` satırları (TizenSdb.CapabilityAsync). İki nokta
    /// içeren ya da değeri boş satırlar atlanır.
    static func parseCapability(_ data: Data) -> [String: String] {
        guard data.count >= 2 else { return [:] }
        var out: [String: String] = [:]
        for line in String(decoding: data.dropFirst(2), as: UTF8.self).split(separator: "\n", omittingEmptySubsequences: false) {
            let parts = line.split(separator: ":", omittingEmptySubsequences: true)
            if parts.count == 2, out[String(parts[0])] == nil { out[String(parts[0])] = String(parts[1]) }
        }
        return out
    }

    /// TV, kurulum başarısız olsa da komutu başarılı bitirir; hata çıktıdadır (ör. `install failed[118]`). İşaretler
    /// Apps2Samsung'un TizenInstallDiagnostics'inden.
    static func installFailed(_ log: String) -> Bool {
        log.range(of: "install failed", options: .caseInsensitive) != nil || log.range(of: "val[fail]", options: .caseInsensitive) != nil ||
            log.range(of: "not enough space", options: .caseInsensitive) != nil
    }

    static func tail(_ log: String) -> String {
        (log.count <= 400 ? log : String(log.suffix(400))).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Gerçek TV bağlantısı: sdb karşılaşması (TizenSdb'deki gibi "host::sdb-net-client", SHA-1 imzalı jeton).
    static func connector(host: String, key: RsaKey, cancel: Cancel, connectTimeoutMs: Int = 5_000) -> () throws -> AdbClient {
        {
            try AdbClient.connect(host: host, port: sdbPort, key: key, options: .sdb, cancel: cancel, approvalTimeoutMs: 15_000,
                                  connectTimeoutMs: connectTimeoutMs)
        }
    }
}
