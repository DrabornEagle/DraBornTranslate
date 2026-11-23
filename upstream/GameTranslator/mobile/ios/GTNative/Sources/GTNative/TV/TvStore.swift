// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import Security

/// Gizli veriler (özel anahtarlar, LG anahtarı): Android'de uygulamanın kendi klasöründeydi, burada Anahtar Zinciri.
protocol TvSecrets: AnyObject {
    func data(_ account: String) -> Data?
    func set(_ data: Data?, _ account: String)
}

/// Anahtar Zinciri (genel parola öğeleri, yalnız bu aygıtta, ilk kilit açılışından sonra okunur).
final class KeychainSecrets: TvSecrets, @unchecked Sendable {
    private let service: String

    init(service: String = "com.gametranslator.tvinstall") { self.service = service }

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    func data(_ account: String) -> Data? {
        var request = query(account)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &item) == errSecSuccess else { return nil }
        return item as? Data
    }

    func set(_ data: Data?, _ account: String) {
        SecItemDelete(query(account) as CFDictionary)
        guard let data else { return }
        var item = query(account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }
}

/// Bellekte tutulan gizli veriler (denemeler için).
final class MemorySecrets: TvSecrets, @unchecked Sendable {
    private let values = TvLocked<[String: Data]>([:])

    func data(_ account: String) -> Data? { values.get[account] }

    func set(_ data: Data?, _ account: String) { values.update { $0[account] = data } }
}

/// TV kurulumunun kayıtları (Android: TvStore ve tvinstall/ klasörü). Düz bilgiler UserDefaults'un "gametranslator"
/// alanında, anahtarlar Anahtar Zinciri'nde.
final class TvStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private let secrets: TvSecrets
    private let lock = NSLock()

    init(defaults: UserDefaults, secrets: TvSecrets) {
        self.defaults = defaults
        self.secrets = secrets
    }

    /// Kurulan TV'nin kaydı: güncellemeler aynı TV'ye yeniden kurulabilsin diye.
    var tv: [String: Any]? {
        get {
            guard let text = defaults.string(forKey: "tvinstall.tv"),
                  let json = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { return nil }
            let version = (json["version"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            return ["brand": json["brand"] as? String ?? "", "host": json["host"] as? String ?? "", "name": json["name"] as? String ?? "",
                    "version": version ?? NSNull(), "installedAt": (json["installedAt"] as? NSNumber)?.int64Value ?? 0]
        }
        set {
            guard let newValue, let data = try? JSONSerialization.data(withJSONObject: newValue) else {
                return defaults.removeObject(forKey: "tvinstall.tv")
            }
            defaults.set(String(decoding: data, as: UTF8.self), forKey: "tvinstall.tv")
        }
    }

    /// LG anahtarı TV'ye göre: parola bir kez sorulur.
    func lgKey(_ host: String) -> LgKey? { LgKey.fromJson(secrets.data("lgkey:\(host)")) }

    func setLgKey(_ host: String, _ key: LgKey?) { secrets.set(key?.toJson(), "lgkey:\(host)") }

    /// Bu telefonun ADB (Android TV) ya da sdb (Samsung) anahtarı. TV onayı bu anahtara verilir; anahtar değişirse TV
    /// yeniden sorar. Bozuk ya da yoksa yenisi üretilir.
    func deviceKey(_ name: String) throws -> RsaKey {
        try lock.withLock {
            if let data = secrets.data(name), let key = try? RsaKey.load(der: data), key.bitLength == 2048 { return key }
            let key = try RsaKey.generate()
            secrets.set(try key.privatePkcs1(), name)
            return key
        }
    }

    func samsungRecord(_ folder: String) -> [String: Any]? { defaults.dictionary(forKey: "tvinstall.samsung.\(folder)") }

    func setSamsungRecord(_ folder: String, _ record: [String: Any]?) {
        if let record { defaults.set(record, forKey: "tvinstall.samsung.\(folder)") } else { defaults.removeObject(forKey: "tvinstall.samsung.\(folder)") }
    }

    func samsungKey(_ folder: String) -> Data? { secrets.data("samsung:\(folder)") }

    func setSamsungKey(_ folder: String, _ key: Data?) { secrets.set(key, "samsung:\(folder)") }
}
