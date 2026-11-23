// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Çevirinin yapılabildiği bir dil (Android: core/Languages.kt TargetLanguage).
public struct TargetLanguage: Equatable, Sendable {
    public let code: String
    public let nativeName: String
    public let englishName: String
    public let turkishName: String
    public var rtl = false
}

/// Çeviri dillerinin tek tanımı (Windows ve Android ile aynı liste ve sıra) ve her motorun bu dillerdeki karşılığı.
/// "mlkit" Android'deki cihaz üstü çeviridir; iPhone'da Apple Çeviri aynı adla seçilir (ayar dosyası değişmesin).
public enum Languages {
    public static let targets: [TargetLanguage] = [
        TargetLanguage(code: "tr", nativeName: "Türkçe", englishName: "Turkish", turkishName: "Türkçe"),
        TargetLanguage(code: "en", nativeName: "English", englishName: "English", turkishName: "İngilizce"),
        TargetLanguage(code: "ar", nativeName: "العربية", englishName: "Arabic", turkishName: "Arapça", rtl: true),
        TargetLanguage(code: "ja", nativeName: "日本語", englishName: "Japanese", turkishName: "Japonca"),
        TargetLanguage(code: "de", nativeName: "Deutsch", englishName: "German", turkishName: "Almanca"),
        TargetLanguage(code: "fr", nativeName: "Français", englishName: "French", turkishName: "Fransızca"),
        TargetLanguage(code: "es", nativeName: "Español", englishName: "Spanish", turkishName: "İspanyolca"),
        TargetLanguage(code: "it", nativeName: "Italiano", englishName: "Italian", turkishName: "İtalyanca"),
        TargetLanguage(code: "pt", nativeName: "Português", englishName: "Portuguese", turkishName: "Portekizce"),
        TargetLanguage(code: "ru", nativeName: "Русский", englishName: "Russian", turkishName: "Rusça"),
        TargetLanguage(code: "zh", nativeName: "简体中文", englishName: "Chinese (Simplified)", turkishName: "Çince (Basitleştirilmiş)"),
        TargetLanguage(code: "ko", nativeName: "한국어", englishName: "Korean", turkishName: "Korece"),
        TargetLanguage(code: "pl", nativeName: "Polski", englishName: "Polish", turkishName: "Lehçe"),
        TargetLanguage(code: "nl", nativeName: "Nederlands", englishName: "Dutch", turkishName: "Felemenkçe"),
        TargetLanguage(code: "id", nativeName: "Bahasa Indonesia", englishName: "Indonesian", turkishName: "Endonezce"),
        TargetLanguage(code: "hi", nativeName: "हिन्दी", englishName: "Hindi", turkishName: "Hintçe"),
        TargetLanguage(code: "fa", nativeName: "فارسی", englishName: "Persian", turkishName: "Farsça", rtl: true),
        TargetLanguage(code: "uk", nativeName: "Українська", englishName: "Ukrainian", turkishName: "Ukraynaca"),
        TargetLanguage(code: "el", nativeName: "Ελληνικά", englishName: "Greek", turkishName: "Yunanca"),
        TargetLanguage(code: "hu", nativeName: "Magyar", englishName: "Hungarian", turkishName: "Macarca"),
        TargetLanguage(code: "ro", nativeName: "Română", englishName: "Romanian", turkishName: "Rumence"),
        TargetLanguage(code: "cs", nativeName: "Čeština", englishName: "Czech", turkishName: "Çekçe"),
        TargetLanguage(code: "sv", nativeName: "Svenska", englishName: "Swedish", turkishName: "İsveççe"),
        TargetLanguage(code: "th", nativeName: "ไทย", englishName: "Thai", turkishName: "Tayca"),
        TargetLanguage(code: "vi", nativeName: "Tiếng Việt", englishName: "Vietnamese", turkishName: "Vietnamca"),
        TargetLanguage(code: "he", nativeName: "עברית", englishName: "Hebrew", turkishName: "İbranice", rtl: true),
        TargetLanguage(code: "az", nativeName: "Azərbaycanca", englishName: "Azerbaijani", turkishName: "Azerbaycanca"),
    ]

    /// Oyunun dili seçicisindeki diller.
    public static let sources = ["tr", "en", "de", "fr", "es", "it", "pt", "ru", "ar", "ja"]

    public static func find(_ code: String) -> TargetLanguage? { targets.first { $0.code == code } }

    /// İlk kurulumda çeviri dili: telefonun dili listedeyse o, değilse İngilizce.
    public static func defaultTarget(systemLanguage: String) -> String {
        let lower = systemLanguage.lowercased()
        let code = lower == "iw" ? "he" : lower == "in" ? "id" : lower
        return find(code) != nil ? code : "en"
    }

    /// Google'ın ucundaki dil kodu.
    public static func googleCode(_ code: String) -> String { code == "zh" ? "zh-CN" : code }

    /// DeepL'in hedef dil kodu; DeepL bu dile çevirmiyorsa nil.
    public static func deepLTarget(_ code: String, region: String = "") -> String? {
        switch code {
        case "en": return "EN-US"
        case "pt": return region.uppercased() == "PT" ? "PT-PT" : "PT-BR"
        case "zh": return "ZH-HANS"
        default: return deepL.contains(code) ? code.uppercased() : nil
        }
    }

    private static let deepL: Set<String> = [
        "tr", "ar", "ja", "de", "fr", "es", "it", "ru", "ko", "pl", "nl", "id", "hi", "fa", "uk", "el", "hu", "ro", "cs", "sv",
        "th", "vi", "he", "az",
    ]

    /// Apple Çeviri'nin (Translation) bildiği diller; listemizden Azerbaycanca, Farsça, Macarca, Rumence, Çekçe, Yunanca ve
    /// İbranice yok (iOS 26). Dil indirilmemişse çeviri yine Google'a düşer.
    private static let apple: Set<String> = [
        "tr", "en", "ar", "ja", "de", "fr", "es", "it", "pt", "ru", "zh", "ko", "pl", "nl", "id", "hi", "uk", "sv", "th", "vi",
    ]

    public static func onDeviceSupports(_ code: String) -> Bool { apple.contains(code) }

    /// Seçilen motor ([engine]: google, deepl, mlkit) [target] diline çevirebiliyor mu?
    public static func supports(engine: String, target: String) -> Bool {
        switch engine {
        case "deepl": deepLTarget(target) != nil
        case "mlkit": onDeviceSupports(target)
        default: true
        }
    }

    /// Gerçekte çalışacak motor: seçilen motor bu dile çeviremiyorsa Google.
    public static func effectiveEngine(_ engine: String, target: String) -> String {
        supports(engine: engine, target: target) ? engine : "google"
    }

    /// Arayüz için: her dilin adları ve hangi motorların o dile çevirebildiği.
    public static func maps() -> [String: Any] {
        [
            "targets": targets.map { language -> [String: Any] in
                ["code": language.code, "native": language.nativeName, "english": language.englishName,
                 "turkish": language.turkishName, "rtl": language.rtl,
                 "deepl": supports(engine: "deepl", target: language.code), "mlkit": supports(engine: "mlkit", target: language.code)]
            },
            "sources": sources,
        ]
    }
}

/// Yerel katmanın kendi küçük metinleri ve Flutter'da yerelleştirilen ileti kodları (Android: core/UiText.kt).
public enum UiText {
    public static let auto = "auto"
    static let fallback = "en"

    static let tables: [String: [String: String]] = [
        "tr": ["test.subtitle": "Bu bir deneme altyazısıdır. Oyunun üstünde görüyor musunuz?",
               "screen.reading": "Ekran çevriliyor…", "screen.empty": "Ekranda yazı bulunamadı."],
        "en": ["test.subtitle": "This is a test subtitle. Can you see it over the game?",
               "screen.reading": "Translating the screen…", "screen.empty": "No text found on the screen."],
    ]

    static func resolve(_ setting: String, system: String) -> String {
        if tables[setting] != nil { return setting }
        if tables[system.lowercased()] != nil { return system.lowercased() }
        return fallback
    }

    static func t(_ key: String, setting: String) -> String {
        let language = resolve(setting, system: Locale.current.language.languageCode?.identifier ?? "en")
        return tables[language]?[key] ?? tables[fallback]?[key] ?? key
    }

    /// Flutter'da yerelleştirilecek metnin kodu: `@anahtar` ve U+001F ile ayrılmış değerler (lib/l10n.dart: `nt`).
    public static func code(_ key: String, _ args: Any?...) -> String {
        "@" + key + args.map { "\u{1f}\($0.map { "\($0)" } ?? "null")" }.joined()
    }
}
