// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import Security

/// Gizli değerlerin (Microsoft giriş jetonu) deposu; testlerde bellekteki sahtesi verilir.
protocol XboxSecrets: Sendable {
    /// `value` nil ya da boşsa kayıt silinir.
    func put(_ name: String, _ value: String?)
    func get(_ name: String) -> String?
}

/// Anahtar Zinciri (Android: SecretStore.kt, Android Keystore'daki AES-GCM anahtarıyla şifreli kayıt). Kayıt yalnız
/// bu cihazda ve cihaz ilk kez açıldıktan sonra okunur; yedekle başka cihaza geçmez (Android'de de anahtar cihazdan
/// çıkmaz). Uygulama silinse de Anahtar Zinciri'nde kalabilir; okunamazsa yeniden giriş yapılır.
final class XboxKeychain: XboxSecrets, @unchecked Sendable {
    let service: String

    init(service: String = "gametranslator-secrets") {
        self.service = service
    }

    private func query(_ name: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: name]
    }

    func put(_ name: String, _ value: String?) {
        guard let value, !value.isEmpty else {
            SecItemDelete(query(name) as CFDictionary)
            return
        }
        let data = Data(value.utf8)
        let update: [String: Any] = [kSecValueData as String: data,
                                     kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query(name) as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = query(name)
            add.merge(update) { $1 }
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    func get(_ name: String) -> String? {
        var request = query(name)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

/// Bellekte (testler).
final class XboxMemorySecrets: XboxSecrets, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: String] = [:]

    func put(_ name: String, _ value: String?) {
        lock.withLock {
            if let value, !value.isEmpty { values[name] = value } else { values.removeValue(forKey: name) }
        }
    }

    func get(_ name: String) -> String? { lock.withLock { values[name] } }
}
