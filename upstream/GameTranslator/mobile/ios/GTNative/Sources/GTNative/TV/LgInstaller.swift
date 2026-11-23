// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

struct CommandResult {
    let status: Int
    let output: String
}

/// TV'de komut çalıştırır ve dosya yazar (Android'de RemoteShell: dosya `cat > dosya` komutunun girişine akıtılır;
/// burada `upload` aynı işi görür).
protocol RemoteShell: AnyObject {
    func run(_ command: String) async throws -> CommandResult
    /// `data`'yı TV'deki `path` dosyasına yazar.
    func upload(_ data: Data, to path: String) async throws -> CommandResult
    func close() async
}

/// Key Server'dan alınan anahtar: parolasız PEM ya da (yeni biçimde) anahtar ve parolası.
struct LgKey: Equatable {
    let text: String
    let passphrase: String?

    func toJson() -> Data {
        (try? JSONSerialization.data(withJSONObject: ["text": text, "passphrase": passphrase as Any? ?? NSNull()] as [String: Any])) ?? Data()
    }

    static func fromJson(_ data: Data?) -> LgKey? {
        guard let data, let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let text = json["text"] as? String, !text.isEmpty else { return nil }
        return LgKey(text: text, passphrase: json["passphrase"] as? String)
    }
}

/// TV uygulamasını LG'nin geliştirici modu üzerinden kurar ve günceller (Android: LgInstaller.kt). Geliştirici modu açık
/// TV 9922 portunda SSH bekler; anahtar Key Server'dan bir kez alınır.
final class LgInstaller {
    static let sshPort = 9922
    static let keyPort = 9991
    static let appId = "com.gametranslator.tv"
    static let appInfoPath = "/media/developer/apps/usr/palm/applications/\(appId)/appinfo.json"
    static let tempDir = "/media/developer/temp"
    private static let installLog = "\(tempDir)/GameTranslator-install.log"
    private static let sessionTokenPath = "/var/luna/preferences/devmode_enabled"

    private let shell: RemoteShell
    private let cancel: Cancel
    private let pollIntervalMs: Int

    init(shell: RemoteShell, cancel: Cancel, pollIntervalMs: Int = 2_000) {
        self.shell = shell
        self.cancel = cancel
        self.pollIntervalMs = pollIntervalMs
    }

    /// TV'deki sürüm; uygulama kurulu değilse nil.
    func installedVersion() async throws -> String? {
        Self.versionFromAppInfo(try await shell.run("cat \(Self.appInfoPath)").output)
    }

    /// Paketi TV'ye yükler ve ares-install'un yaptığı gibi kurar: kurulum aboneliği TV "installed" ya da hata diyene
    /// kadar açık kalır, paket ancak bundan sonra silinir.
    func install(_ ipk: Data, fileName: String, version: String, installTimeoutSeconds: Int = 180,
                 onProgress: (String) -> Void = { _ in }) async throws {
        let remote = "\(Self.tempDir)/\(fileName)"
        onProgress("uploading:0")
        // Önceki denemelerden kalan paketler TV'de yer kaplamasın.
        let prepare = try await shell.run("mkdir -p \(Self.tempDir) && rm -f \(Self.tempDir)/com.gametranslator.tv_*.ipk \(Self.tempDir)/com.GameTranslator.tv_*.ipk")
        let upload = prepare.status == 0 ? try await shell.upload(ipk, to: remote) : prepare
        if upload.status != 0 { throw failure("yükleme çıkış kodu \(upload.status): \(upload.output.tvShort)", upload.output) }
        onProgress("uploading:100")
        // Paket TV'ye eksiksiz ulaştı mı? (wc yoksa ya da çıktı okunamazsa denetim atlanır.)
        if let size = Int(try await shell.run("wc -c < '\(remote)'").output.trimmingCharacters(in: .whitespacesAndNewlines)), size != ipk.count {
            throw TvInstallError(.INSTALL_FAILED, "paket TV'ye eksik ya da bozuk ulaştı: \(size)/\(ipk.count) bayt")
        }

        onProgress("installing")
        let response = try await shell.run(Self.installCommand(remote, waitSeconds: installTimeoutSeconds))
        let outcome = Self.parseInstallReplies(response.output)
        if outcome.failed {
            await remove(remote)
            let reason = outcome.reason.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
            throw failure(reason ?? "kurulum başarısız: \(response.output.tvShort)", response.output)
        }
        // TV "installed" dese de (ya da sonucu okunamadıysa) sürüm appinfo.json'dan doğrulanır.
        onProgress("verifying")
        let limit = Date().addingTimeInterval(outcome.installed ? 30 : 60)
        while true {
            if try await installedVersion() == version {
                await remove(remote)
                return
            }
            if Date() >= limit { break }
            try await sleep(pollIntervalMs)
        }
        if outcome.installed { await remove(remote) }
        throw TvInstallError(.INSTALL_FAILED, "TV'de \(version) sürümü görünmedi; kurulum yanıtı (\(response.status)): \(response.output.tvShort)")
    }

    /// Uygulamayı açar; açılamazsa false (kullanıcı TV'den açar).
    func launch(_ server: String?) async -> Bool {
        var params: [String: Any] = ["id": Self.appId]
        if let server, !server.isEmpty { params["params"] = ["server": server] }
        guard let json = try? JSONSerialization.data(withJSONObject: params, options: [.sortedKeys, .withoutEscapingSlashes]) else { return false }
        let text = String(decoding: json, as: UTF8.self)
        for service in ["luna://com.webos.applicationManager/launch", "luna://com.webos.service.applicationmanager/launch"] {
            guard let result = try? await shell.run("/usr/bin/luna-send-pub -n 1 '\(service)' '\(text)'") else { continue }
            if result.output.contains("\"returnValue\":true") || result.output.contains("\"returnValue\" : true") { return true }
        }
        return false
    }

    /// Geliştirici modunun 1000 saatlik süresini baştan başlatır (Developer Mode uygulamasının EXTEND düğmesi gibi).
    /// Başarısız olursa nil: süre dolmadan kullanıcı TV'den de uzatabilir.
    func renewSession(session: URLSession = .shared) async -> Int? {
        guard let token = (try? await shell.run("cat \(Self.sessionTokenPath)"))?.output.trimmingCharacters(in: .whitespacesAndNewlines),
              !token.isEmpty, !token.contains(" "),
              let encoded = token.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { return nil }
        func get(_ url: String) async -> String? {
            guard let url = URL(string: url) else { return nil }
            var request = URLRequest(url: url)
            request.timeoutInterval = 10
            guard let (data, _) = try? await session.data(for: request) else { return nil }
            return String(decoding: data, as: UTF8.self)
        }
        guard let renewed = await get("https://developer.lge.com/secure/ResetDevModeSession.dev?sessionToken=\(encoded)"),
              renewed.range(of: "success", options: .caseInsensitive) != nil,
              let remaining = await get("https://developer.lge.com/secure/CheckDevModeSession.dev?sessionToken=\(encoded)") else { return nil }
        return Self.remainingHours(remaining)
    }

    private func sleep(_ millis: Int) async throws {
        let end = Date().addingTimeInterval(Double(millis) / 1000)
        while true {
            try cancel.check()
            let left = end.timeIntervalSinceNow
            if left <= 0 { return }
            try? await Task.sleep(nanoseconds: UInt64(min(left, 0.1) * 1e9))
        }
    }

    private func remove(_ remote: String) async {
        _ = try? await shell.run("rm -f '\(remote)'")
    }

    private func failure(_ detail: String, _ evidence: String) -> TvInstallError {
        TvInstallError(Self.isStorageProblem(evidence) || Self.isStorageProblem(detail) ? .NOT_ENOUGH_SPACE : .INSTALL_FAILED, detail)
    }

    struct InstallOutcome {
        let installed: Bool
        let failed: Bool
        let reason: String?
    }

    /// Key Server'dan anahtarı alır ve TV ekranındaki parolayla çözer.
    static func fetchKey(_ host: String, _ passphrase: String, cancel: Cancel) throws -> LgKey {
        let response: LanHttp.Response
        do {
            response = try cancel.guarded { try LanHttp.get(host, keyPort, "/webos_rsa", timeoutMs: 5_000, cancel: cancel) }
        } catch let error as TvIOError {
            try cancel.check()
            throw TvInstallError(.KEY_SERVER_OFF, error.description)
        }
        guard response.status == 200, !response.body.isEmpty else { throw TvInstallError(.KEY_SERVER_OFF, "HTTP \(response.status)") }
        let pem = response.text
        if LegacyPemKey.isLegacyEncrypted(pem) { return LgKey(text: try LegacyPemKey.decryptWithCaseFallback(pem, passphrase), passphrase: nil) }
        // Başka biçim (ör. OpenSSH): Citadel parolayı kendisi çözer; burada yalnız denenir.
        return try LgSsh.acceptKey(pem, passphrase)
    }

    static func versionFromAppInfo(_ text: String) -> String? {
        guard let match = text.firstMatch(of: #/"version"\s*:\s*"([^"]+)"/#) else { return nil }
        return String(match.1)
    }

    /// TV'de kurulumu başlatan kabuk komutu (Windows'taki InstallCommand ile aynı). `luna-send-pub -i` girişi açık
    /// kaldıkça aboneliği sürdürür; giriş `sleep`'ten gelir. Yanıtlar dosyaya yazılır; TV "installed" ya da hata
    /// dediğinde, luna-send kendiliğinden çıktığında ya da süre dolduğunda yanıtlar basılır.
    static func installCommand(_ remotePackage: String, waitSeconds: Int) -> String {
        let parameters = "{\"id\":\"com.ares.defaultName\",\"ipkUrl\":\"\(remotePackage)\",\"subscribe\":true}"
        return [
            "rm -f \(installLog)",
            "sleep \(waitSeconds + 30) </dev/null 2>/dev/null | /usr/bin/luna-send-pub -i 'luna://com.webos.appInstallService/dev/install' '\(parameters)' >\(installLog) 2>&1 & L=$!",
            "i=0",
            "while [ $i -lt \(waitSeconds) ]; do grep -q -i -e '\"state\" *: *\"installed' -e 'failed' -e '\"returnValue\" *: *false' -e '\"state\" *: *\"success' \(installLog) 2>/dev/null && break; kill -0 $L 2>/dev/null || break; sleep 1; i=$((i+1)); done",
            "cat \(installLog)",
            "kill $L 2>/dev/null",
            "rm -f \(installLog)",
        ].joined(separator: "; ")
    }

    /// luna-send'in satır satır JSON yanıtlarını ares-install gibi okur: `returnValue: false` ya da `details.state`
    /// içinde FAILED hata, `installed`/`SUCCESS` başarıdır.
    static func parseInstallReplies(_ output: String) -> InstallOutcome {
        var pending = ""
        for line in output.split(separator: "\n", omittingEmptySubsequences: false) {
            pending += line.trimmingCharacters(in: .whitespacesAndNewlines)
            if pending.isEmpty { continue }
            guard pending.hasPrefix("{") else {
                pending = "" // luna-send'in kendi yazısı (ör. "Error: ..."); ares de JSON olmayanı atlar.
                continue
            }
            guard let reply = (try? JSONSerialization.jsonObject(with: Data(pending.utf8))) as? [String: Any] else {
                continue // satır yarım; sonrakiyle birleşir
            }
            pending = ""
            let details = reply["details"] as? [String: Any]
            let state = text(details, "state") ?? ""
            if let value = reply["returnValue"], (value as? NSNumber).map({ CFGetTypeID($0) == CFBooleanGetTypeID() && !$0.boolValue }) == true {
                return InstallOutcome(installed: false, failed: true,
                                      reason: reason(text(reply, "errorText") ?? text(reply, "errorMessage"), text(reply, "errorCode")))
            }
            if state.range(of: "failed", options: .caseInsensitive) != nil {
                return InstallOutcome(installed: false, failed: true,
                                      reason: reason(text(details, "reason") ?? text(details, "errorText") ?? state,
                                                     text(details, "errorCode") ?? text(reply, "errorCode")))
            }
            if state.range(of: "installed", options: .caseInsensitive) != nil || state.lowercased().hasPrefix("success") {
                return InstallOutcome(installed: true, failed: false, reason: nil)
            }
        }
        return InstallOutcome(installed: false, failed: false, reason: nil)
    }

    static func isStorageProblem(_ text: String) -> Bool { AndroidTvInstaller.isStorageProblem(text) }

    /// LG'nin yanıtındaki kalan süre `999:58:40` (saat:dakika:saniye) biçimindedir.
    static func remainingHours(_ response: String) -> Int? {
        guard let match = response.firstMatch(of: #/(\d{1,4}):(\d{2}):(\d{2})/#) else { return nil }
        return Int(match.1)
    }

    private static func reason(_ text: String?, _ code: String?) -> String {
        let reason = (text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let code, !code.trimmingCharacters(in: .whitespaces).isEmpty else { return reason }
        return "\(reason) (errorCode \(code))".trimmingCharacters(in: .whitespaces)
    }

    private static func text(_ element: [String: Any]?, _ name: String) -> String? {
        guard let value = element?[name] else { return nil }
        if let string = value as? String { return string }
        if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() { return number.stringValue }
        return nil
    }
}
