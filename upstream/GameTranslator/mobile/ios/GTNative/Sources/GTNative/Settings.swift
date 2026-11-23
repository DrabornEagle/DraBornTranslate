// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import GameTranslatorCore

/// Altyazının oyunda arandığı bölge, ekrana oranla (0–1). Android: Store.kt Region.
public struct Region: Equatable, Sendable {
    public var top = 0.62
    public var bottom = 0.97
    public var left = 0.14
    public var right = 0.86

    public init(top: Double = 0.62, bottom: Double = 0.97, left: Double = 0.14, right: Double = 0.86) {
        self.top = top
        self.bottom = bottom
        self.left = left
        self.right = right
    }

    public var box: TextBox { TextBox(left: left, top: top, right: right, bottom: bottom) }

    public static func of(_ box: TextBox) -> Region { Region(top: box.top, bottom: box.bottom, left: box.left, right: box.right) }

    var json: [String: Any] { ["top": top, "bottom": bottom, "left": left, "right": right] }

    /// Kayıttaki bölge; kenarlar karenin içinde, en az %5 yükseklik ve genişlikte.
    static func from(_ value: Any?, defaults: Region = Region()) -> Region {
        let r = value as? [String: Any]
        let top = min(max(JSONValue.double(r?["top"]) ?? defaults.top, 0), 0.95)
        let left = min(max(JSONValue.double(r?["left"]) ?? defaults.left, 0), 0.95)
        return Region(top: top, bottom: min(max(JSONValue.double(r?["bottom"]) ?? defaults.bottom, top + 0.05), 1),
                      left: left, right: min(max(JSONValue.double(r?["right"]) ?? defaults.right, left + 0.05), 1))
    }
}

/// Ayarlar; JSON biçimi Android'deki Settings.toJson/fromJson ile birebir aynıdır (Dart tarafı değişmesin).
public struct Settings: Equatable, Sendable {
    public var region = Region()
    public var sourceLanguage = "en"
    public var targetLanguage = "tr"
    /// google (çevrimiçi, anahtarsız), mlkit (cihaz üstünde; iPhone'da Apple Çeviri), deepl (kendi anahtarınla)
    public var engine = "google"
    public var deepLKey = ""
    /// Görünüm, Android'deki anahtarlarla (regionScale, regionColor) tutulur.
    public var style = StyleSettings()
    public var autoWake = true
    public var glossary = ""
    public var packsEnabled = true
    public var uiLanguage = UiText.auto
    public var sharePool = true
    public var autoRegion = true
    /// Ek alanlarda tanınan tuş simgelerinin kumandası: "auto" (Xbox kaynağında Xbox, PS5'te PlayStation),
    /// "playstation" ya da "xbox" (Android: Settings.buttonLayout).
    public var buttonLayout = "auto"
    /// Sürekli okunan ek alan yuvaları (çekirdeğin ExtraSlots'u; JSON biçimi Android'deki `extraAreas` ile aynı).
    public var extraAreas = ExtraSlots()

    public init() {}

    /// Sürekli okunan ek alanlar (bölgesi olan ve açık yuvalar).
    public var watchedAreas: [WatchedArea] { extraAreas.watchedAreas() }

    /// Sözlük metni çözülmüş hâliyle; boş ya da bozuksa boş sözlük.
    public var parsedGlossary: Glossary {
        guard !glossary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let root = (try? JSONSerialization.jsonObject(with: Data(glossary.utf8))) as? [String: Any] else { return .empty }
        var terms: [String: String] = [:]
        for (key, value) in root["terms"] as? [String: Any] ?? [:] { if let text = value as? String { terms[key] = text } }
        let keep = (root["keep"] as? [Any] ?? []).compactMap { $0 as? String }
        return Glossary.of(terms, keep: keep)
    }

    public var json: [String: Any] {
        [
            "region": region.json,
            "sourceLanguage": sourceLanguage,
            "targetLanguage": targetLanguage,
            "engine": engine,
            "deepLKey": deepLKey,
            "autoWake": autoWake,
            "glossary": glossary,
            "packsEnabled": packsEnabled,
            "uiLanguage": uiLanguage,
            "sharePool": sharePool,
            "autoRegion": autoRegion,
            "buttonLayout": buttonLayout,
            "extraAreas": extraAreas.json(),
            "style": style.json,
        ]
    }

    public var jsonText: String {
        (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
    }

    /// Eksik alanlar varsayılanla dolar. `defaultTarget`: kayıtta çeviri dili yoksa kullanılacak dil.
    public static func from(_ json: [String: Any]?, defaultTarget: String = "tr") -> Settings {
        var settings = Settings()
        settings.targetLanguage = defaultTarget
        guard let json else { return settings }
        let defaults = settings
        settings.region = Region.from(json["region"], defaults: defaults.region)
        settings.extraAreas = ExtraSlots(json: json["extraAreas"])
        settings.sourceLanguage = JSONValue.string(json["sourceLanguage"]) ?? defaults.sourceLanguage
        settings.targetLanguage = JSONValue.string(json["targetLanguage"]) ?? defaults.targetLanguage
        settings.engine = JSONValue.string(json["engine"]) ?? defaults.engine
        settings.deepLKey = JSONValue.string(json["deepLKey"]) ?? ""
        settings.autoWake = JSONValue.bool(json["autoWake"]) ?? true
        settings.glossary = JSONValue.string(json["glossary"]) ?? ""
        settings.packsEnabled = JSONValue.bool(json["packsEnabled"]) ?? true
        settings.uiLanguage = JSONValue.string(json["uiLanguage"]) ?? UiText.auto
        settings.sharePool = JSONValue.bool(json["sharePool"]) ?? true
        // Kayıtlı ayarlarda alan yoksa kullanıcı bölgeyi elle seçmişti: bölgesi değişmesin.
        settings.autoRegion = JSONValue.bool(json["autoRegion"]) ?? false
        let layout = (JSONValue.string(json["buttonLayout"]) ?? "auto").lowercased()
        settings.buttonLayout = ["auto", "playstation", "xbox"].contains(layout) ? layout : "auto"
        settings.style = StyleSettings.from(json["style"] as? [String: Any])
        return settings
    }

    public static func from(text: String?, defaultTarget: String = "tr") -> Settings? {
        guard let text else { return from(nil as [String: Any]?, defaultTarget: defaultTarget) }
        guard let root = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { return nil }
        return from(root, defaultTarget: defaultTarget)
    }
}

/// Altyazı görünümü, Android'deki alan adlarıyla (Dart bu adları yazar). TV'ye `SubtitleStyle` olarak gider.
public struct StyleSettings: Equatable, Sendable {
    public var fontSize = 46
    public var bottomPercent = 7
    public var placement = "cover"
    public var background = 72
    public var bold = false
    public var textColor = "white"
    public var displayMode = "inApp"
    public var stackPrevious = true
    public var speakerColor = false
    public var font = ""
    public var regionBackground = 100
    public var regionScale = 100
    public var regionColor = ""

    public init() {}

    var json: [String: Any] {
        ["fontSize": fontSize, "bottomPercent": bottomPercent, "placement": placement, "background": background, "bold": bold,
         "textColor": textColor, "displayMode": displayMode, "stackPrevious": stackPrevious, "speakerColor": speakerColor,
         "font": font, "regionBackground": regionBackground, "regionScale": regionScale, "regionColor": regionColor]
    }

    static func from(_ s: [String: Any]?) -> StyleSettings {
        let d = StyleSettings()
        var style = StyleSettings()
        style.fontSize = JSONValue.int(s?["fontSize"]) ?? d.fontSize
        style.bottomPercent = JSONValue.int(s?["bottomPercent"]) ?? d.bottomPercent
        style.placement = JSONValue.string(s?["placement"]) ?? d.placement
        style.background = JSONValue.int(s?["background"]) ?? d.background
        style.bold = JSONValue.bool(s?["bold"]) ?? d.bold
        style.textColor = JSONValue.string(s?["textColor"]) ?? d.textColor
        style.displayMode = JSONValue.string(s?["displayMode"]) ?? d.displayMode
        style.stackPrevious = JSONValue.bool(s?["stackPrevious"]) ?? d.stackPrevious
        style.speakerColor = JSONValue.bool(s?["speakerColor"]) ?? d.speakerColor
        style.font = JSONValue.string(s?["font"]) ?? d.font
        style.regionBackground = min(max(JSONValue.int(s?["regionBackground"]) ?? d.regionBackground, 0), 100)
        style.regionScale = min(max(JSONValue.int(s?["regionScale"]) ?? d.regionScale, 70), 150)
        style.regionColor = JSONValue.string(s?["regionColor"]) ?? d.regionColor
        return style
    }

    /// Çekirdeğin TV'ye gönderdiği biçim (Swift adları: regionTextScale, regionTextColor).
    public var subtitleStyle: SubtitleStyle {
        SubtitleStyle(fontSize: fontSize, bottomPercent: bottomPercent,
                      placement: SubtitlePlacement(rawValue: placement.lowercased()) ?? .cover, background: background, bold: bold,
                      textColor: SubtitleTextColor(rawValue: textColor.lowercased()) ?? .white,
                      displayMode: displayMode.lowercased() == "overlay" ? .overlay : .inApp, stackPrevious: stackPrevious,
                      speakerColor: speakerColor, font: font, regionBackground: regionBackground, regionTextScale: regionScale,
                      regionTextColor: regionColor.isEmpty ? nil : SubtitleTextColor(rawValue: regionColor.lowercased()))
    }
}

/// org.json'un opt* davranışı: tür uymuyorsa varsayılan kullanılır (sayılar arasında dönüşüm yapılır).
enum JSONValue {
    static func double(_ value: Any?) -> Double? {
        if let number = value as? NSNumber { return CFGetTypeID(number) == CFBooleanGetTypeID() ? nil : number.doubleValue }
        return (value as? String).flatMap(Double.init)
    }

    static func int(_ value: Any?) -> Int? { double(value).map { Int($0) } }

    static func bool(_ value: Any?) -> Bool? {
        if let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue }
        if let text = value as? String { return text.lowercased() == "true" ? true : text.lowercased() == "false" ? false : nil }
        return nil
    }

    static func string(_ value: Any?) -> String? {
        if value == nil || value is NSNull { return nil }
        if let text = value as? String { return text }
        if let number = value as? NSNumber { return CFGetTypeID(number) == CFBooleanGetTypeID() ? (number.boolValue ? "true" : "false") : number.stringValue }
        return nil
    }
}
