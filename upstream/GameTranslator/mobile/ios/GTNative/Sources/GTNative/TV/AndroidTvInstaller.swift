// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// GameTranslator'ın Android TV uygulamasını ağ üzerinden kurar (Android: AndroidTvInstaller.kt). TV'de geliştirici
/// seçenekleri ve ağ hata ayıklaması açık olmalıdır; Android TV'lerin çoğu bu ayarla 5555 portunda ADB bekler.
final class AndroidTvInstaller {
    static let defaultPort = 5555
    static let packageName = "com.gametranslator.tv"

    private let host: String
    private let port: Int
    private let key: () throws -> RsaKey

    /// "connecting", "approve", "checking", "uploading:<yüzde>", "installing", "launching"
    var onProgress: (String) -> Void = { _ in }

    struct Result {
        let version: String
        let installed: Bool
    }

    /// `key`: bu telefonun ADB anahtarı (TV onayı bu anahtara verilir). "192.168.1.20:5555" gibi yazılmış adres de
    /// kabul edilir.
    init(host: String, key: @escaping () throws -> RsaKey, port: Int = AndroidTvInstaller.defaultPort) {
        let parts = host.trimmingCharacters(in: .whitespaces).split(separator: ":", omittingEmptySubsequences: false)
        self.host = parts[0].trimmingCharacters(in: .whitespaces)
        self.port = parts.count > 1 ? Int(parts[1].trimmingCharacters(in: .whitespaces)) ?? port : port
        self.key = key
    }

    /// Uygulama eksikse ya da eskiyse kurar ve açar; hepsi tek bağlantıda yapılır, TV onayı en çok bir kez sorulur.
    /// `server`: TV uygulamasının bağlanacağı altyazı sunucusu (ör. http://telefon:8787).
    func ensureInstalled(_ apk: Data, version: String, cancel: Cancel, server: String? = nil, force: Bool = false) throws -> Result {
        onProgress("connecting")
        let adb = try connect(cancel)
        defer { adb.close() }
        do {
            return try cancel.guarded {
                adb.timeout(60_000)
                onProgress("checking")
                // Çok kullanıcılı Android TV'ler (ör. TCL / Google TV profilleri) için paketi etkin kullanıcıya da bağla.
                _ = try adb.run("shell:cmd package install-existing \(Self.packageName)")
                let installed = Self.versionFromDumpsys(try adb.run("shell:dumpsys package \(Self.packageName)"))
                if !force, let installed, !Versions.isNewer(version, installed) {
                    // Bu izni vermeyen eski bir sürümün kurduğu güncel uygulama da izni alsın.
                    _ = try grantOverlay(adb)
                    onProgress("launching")
                    if !(try launch(adb, server)).contains("Error") { return Result(version: installed, installed: false) }
                }
                try install(adb, apk, cancel)
                _ = try grantOverlay(adb)
                onProgress("launching")
                _ = try launch(adb, server)
                return Result(version: version, installed: true)
            }
        } catch let error as TvIOError {
            throw TvInstallError(.CONNECTION_LOST, error.description)
        }
    }

    /// "Diğer uygulamaların üzerinde göster" iznini verir: TV uygulamasının katman modu (HDMI'yı TV kendi gösterir, altyazı
    /// üstteki şeffaf pencerede) bunu ister. Android TV'de bu ayar çoğu zaman menüde yoktur; ADB ile verilir. Verilemezse
    /// kurulum yine başarılıdır, TV uygulaması nasıl verileceğini kendisi anlatır (Android: 2705542, CouchSubs'tan, AGPL-3.0).
    private func grantOverlay(_ adb: AdbClient) throws -> Bool {
        do {
            var output = try adb.run("shell:appops set \(Self.packageName) SYSTEM_ALERT_WINDOW allow")
            // Bazı Android sürümlerinde appops yerine izin olarak verilir.
            if Self.overlayGrantFailed(output) { output = try adb.run("shell:pm grant \(Self.packageName) android.permission.SYSTEM_ALERT_WINDOW") }
            return !Self.overlayGrantFailed(output)
        } catch let error as TvIOError {
            throw error
        } catch let error as TvInstallError where error.error == .CONNECTION_LOST {
            throw error
        } catch {
            return false
        }
    }

    /// appops ve pm başarıda bir şey yazmaz; hata metni döner.
    static func overlayGrantFailed(_ output: String) -> Bool {
        let text = output.lowercased()
        return text.contains("error") || text.contains("exception") || text.contains("unknown") || text.contains("not allowed")
    }

    private func connect(_ cancel: Cancel) throws -> AdbClient {
        try AdbClient.connect(host: host, port: port, key: try key(), options: .adb, cancel: cancel, onApprovalNeeded: { onProgress("approve") })
    }

    private func launch(_ adb: AdbClient, _ server: String?) throws -> String {
        let extra = (server ?? "").isEmpty ? "" : " --es params '{\"server\":\"\(server!)\"}' --es server '\(server!)'"
        var result = try adb.run("shell:am start -n \(Self.packageName)/.MainActivity\(extra)")
        if result.contains("Error") {
            // Uygulama başka bir kullanıcıya (TV profili) kurulmuş olabilir: etkin kullanıcıya bağlayıp yeniden dene.
            let user = try adb.run("shell:am get-current-user").trimmingCharacters(in: .whitespacesAndNewlines)
            if !user.isEmpty && user.allSatisfy(\.isNumber) {
                _ = try adb.run("shell:cmd package install-existing --user \(user) \(Self.packageName)")
                result = try adb.run("shell:am start --user \(user) -n \(Self.packageName)/.MainActivity\(extra)")
            }
        }
        return result
    }

    /// APK'yı kurar. İmzası farklı eski bir kopya varsa önce onu kaldırır.
    private func install(_ adb: AdbClient, _ apk: Data, _ cancel: Cancel) throws {
        adb.timeout(180_000)
        var result = try streamInstall(adb, apk)
        if result.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || result.contains("INSTALL_FAILED_VERSION_DOWNGRADE") {
            try cancel.check()
            _ = try adb.run("shell:pm uninstall \(Self.packageName)")
            result = try streamInstall(adb, apk)
        }
        if !result.contains("Success") {
            let error: TvError = Self.isStorageProblem(result) ? .NOT_ENOUGH_SPACE : .INSTALL_FAILED
            throw TvInstallError(error, result.trimmingCharacters(in: .whitespacesAndNewlines).tvPrefix(500))
        }
    }

    private func streamInstall(_ adb: AdbClient, _ apk: Data) throws -> String {
        onProgress("uploading:0")
        let total = max(1, apk.count)
        // Android 7 ve sonrası: dosyayı TV'ye ayrıca kopyalamadan doğrudan paket yöneticisine akıtır.
        var result: String
        do {
            result = try adb.run("exec:cmd package install -r -d -g -S \(apk.count)", apk) { sent in
                onProgress("uploading:\(sent * 100 / total)")
            }
        } catch let error as TvInstallError where error.error == .INSTALL_FAILED {
            result = "" // exec: hizmeti yok (çok eski Android)
        }
        if !Self.needsLegacyInstall(result) {
            onProgress("installing")
            return result
        }
        // Android 6 ve öncesi: "cmd package" yok. Dosya sync ile geçici klasöre yazılır, pm ile kurulur.
        let remote = "/data/local/tmp/gametranslator-tv.apk"
        try adb.push(apk, remotePath: remote) { sent in onProgress("uploading:\(sent * 100 / total)") }
        onProgress("installing")
        let output = try adb.run("shell:pm install -r -d \(remote)")
        _ = try? adb.run("shell:rm -f \(remote)")
        return output
    }

    static func versionFromDumpsys(_ output: String) -> String? {
        guard let match = output.firstMatch(of: #/versionName=(\S+)/#) else { return nil }
        return String(match.1)
    }

    /// "cmd package" komutu bu Android'de yoksa.
    static func needsLegacyInstall(_ output: String) -> Bool {
        let text = output.lowercased()
        return text.contains("unknown command") || text.contains("cmd: not found") || text.contains("can't find service") ||
            text.contains("inaccessible or not found") || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func isStorageProblem(_ text: String) -> Bool {
        text.firstMatch(of: #/(?i)no space|not enough (free )?(space|storage)|insufficient (free )?(space|storage)|out of space|storage (is )?full|disk (is )?full|ENOSPC|lack of (space|storage)|INSTALL_FAILED_INSUFFICIENT_STORAGE/#) != nil
    }
}
