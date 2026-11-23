// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import RemotePlay

/// Uygulamanın kayıtları (Android: Store.kt; SharedPreferences yerine UserDefaults'un kendi alanı). Anahtar adları ve
/// biçimler Android'dekiyle aynıdır.
public final class Store: @unchecked Sendable {
    public static let kindPS5 = "ps5"
    public static let kindXbox = "xbox"
    public static let screenScaleMin = 0.5
    public static let screenScaleMax = 3.0

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = UserDefaults(suiteName: "gametranslator") ?? .standard) {
        self.defaults = defaults
    }

    public var accountId: Data? {
        get { defaults.string(forKey: "accountId").flatMap(Self.decode).flatMap { $0.count == 8 ? $0 : nil } }
        set { if let newValue { defaults.set(Self.encode(newValue), forKey: "accountId") } else { defaults.removeObject(forKey: "accountId") } }
    }

    /// PS5'e kaydedilmiş bu telefonun bağlantı bilgileri.
    public var console: RegisteredConsole? {
        get {
            guard let text = defaults.string(forKey: "console"),
                  let json = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any],
                  let key = (json["registKey"] as? String).flatMap(Self.decode),
                  let morning = (json["morning"] as? String).flatMap(Self.decode),
                  key.count == 16, morning.count == 16,
                  let host = json["host"] as? String, !host.isEmpty else { return nil }
            return RegisteredConsole(host: host, name: json["name"] as? String ?? "PS5", registKey: key, morning: morning)
        }
        set {
            guard let value = newValue else { return defaults.removeObject(forKey: "console") }
            let json: [String: Any] = ["host": value.host, "name": value.name, "registKey": Self.encode(value.registKey),
                                       "morning": Self.encode(value.morning)]
            if let data = try? JSONSerialization.data(withJSONObject: json) {
                defaults.set(String(decoding: data, as: UTF8.self), forKey: "console")
            }
        }
    }

    public var consoleKind: String {
        get { defaults.string(forKey: "consoleKind") == Self.kindXbox ? Self.kindXbox : Self.kindPS5 }
        set { defaults.set(newValue == Self.kindXbox ? Self.kindXbox : Self.kindPS5, forKey: "consoleKind") }
    }

    public var introSeen: Bool {
        get { defaults.bool(forKey: "introSeen") }
        set { defaults.set(newValue, forKey: "introSeen") }
    }

    /// Sürüm duyurusu `id` gösterildi mi (her duyuru bir kez).
    public func noticeSeen(_ id: String) -> Bool { defaults.bool(forKey: "notice.\(id)") }

    public func setNoticeSeen(_ id: String) { defaults.set(true, forKey: "notice.\(id)") }

    public var screenScale: Double {
        get { min(max(defaults.object(forKey: "screenScale") as? Double ?? 1, Self.screenScaleMin), Self.screenScaleMax) }
        set { defaults.set(min(max(newValue, Self.screenScaleMin), Self.screenScaleMax), forKey: "screenScale") }
    }

    public var screenPrevious: Bool {
        get { defaults.object(forKey: "screenPrevious") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "screenPrevious") }
    }

    public var displayServer: String? {
        get { defaults.string(forKey: "displayServer") }
        set { if let newValue { defaults.set(newValue, forKey: "displayServer") } else { defaults.removeObject(forKey: "displayServer") } }
    }

    /// DeepL anahtarının güvenli deposu (Mac: Anahtar Zinciri; Windows'taki gibi). Verilirse anahtar ayar JSON'unda
    /// tutulmaz: okurken buradan doldurulur, yazarken buraya gider. Verilmezse (iPhone) anahtar Android'deki gibi JSON'dadır.
    public var deepLKeyVault: (load: () -> String?, save: (String) -> Void)? {
        didSet { migrateDeepLKey() }
    }

    public var settings: Settings {
        get {
            var value = Settings.from(text: defaults.string(forKey: "settings"), defaultTarget: defaultTarget())
                ?? Settings.from(nil as [String: Any]?, defaultTarget: defaultTarget())
            if let vault = deepLKeyVault { value.deepLKey = vault.load() ?? "" }
            return value
        }
        set {
            var stored = newValue
            if let vault = deepLKeyVault {
                vault.save(newValue.deepLKey)
                stored.deepLKey = ""
            }
            defaults.set(stored.jsonText, forKey: "settings")
        }
    }

    /// Önceden JSON'a yazılmış anahtar kasaya taşınır (kasa boşsa), JSON'dan silinir.
    private func migrateDeepLKey() {
        guard let vault = deepLKeyVault, let stored = Settings.from(text: defaults.string(forKey: "settings")), !stored.deepLKey.isEmpty else { return }
        if (vault.load() ?? "").isEmpty { vault.save(stored.deepLKey) }
        var cleaned = stored
        cleaned.deepLKey = ""
        defaults.set(cleaned.jsonText, forKey: "settings")
    }

    /// Çeviri dili hiç seçilmemişse kullanılacak dil; ilk soruluşta belirlenir ve saklanır. Daha önce kullanılmış
    /// kurulumda (herhangi bir kayıt var) Türkçe; yeni kurulumda telefonun dili listede varsa o, yoksa İngilizce.
    private func defaultTarget() -> String {
        if let saved = defaults.string(forKey: "defaultTarget") { return saved }
        let used = ["accountId", "console", "settings", "introSeen"].contains { defaults.object(forKey: $0) != nil }
        let value = used ? "tr" : Languages.defaultTarget(systemLanguage: Locale.current.language.languageCode?.identifier ?? "en")
        defaults.set(value, forKey: "defaultTarget")
        return value
    }

    public static func encode(_ bytes: Data) -> String { bytes.base64EncodedString() }
    public static func decode(_ text: String) -> Data? { Data(base64Encoded: text.trimmingCharacters(in: .whitespacesAndNewlines)) }
}
