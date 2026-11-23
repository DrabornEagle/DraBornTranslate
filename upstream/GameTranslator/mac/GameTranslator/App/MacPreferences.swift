// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import GameTranslatorCore
import GTNative
import Observation

/// Mac'e özgü tercihler (telefonla ortak ayar JSON'unda olmayanlar): görüntü kaynağı, Mac ekranındaki altyazı katmanı,
/// OBS'in ayrı görünümü ve dublaj. Windows'taki UserSettings'in Mac'e özgü alanları. UserDefaults'ta tutulur.
@MainActor
@Observable
final class MacPreferences {
    /// Görüntünün alındığı yer. "console": PS5 ya da Xbox (Store.consoleKind'e göre), "captureCard": USB yakalama kartı,
    /// "window": bir uygulama penceresi, "display": bir ekranın tamamı.
    enum VideoSource: String, CaseIterable, Codable, Sendable {
        case console, captureCard, window, display
    }

    /// Mac ekranındaki altyazı katmanının yeri: oyundaki altyazının üstünü örter ya da ekranın altında sabit durur.
    enum OverlayPlacement: String, CaseIterable, Codable, Sendable {
        case cover, fixed
    }

    private let defaults: UserDefaults

    var videoSource: VideoSource { didSet { save() } }
    /// Yakalama kartının AVCaptureDevice.uniqueID'si (boşsa ilk bulunan).
    var captureDeviceID: String { didSet { save() } }
    /// Pencere kaynağında uygulamanın paket kimliği ve pencere başlığı (pencere kimliği her açılışta değişir).
    var windowBundleID: String { didSet { save() } }
    var windowTitle: String { didSet { save() } }
    /// Ekran kaynağında CGDirectDisplayID (0: ana ekran).
    var displayID: UInt32 { didSet { save() } }

    /// Mac ekranında altyazı katmanı (Windows: DesktopSubtitleOverlay).
    var overlayEnabled: Bool { didSet { save() } }
    var overlayPlacement: OverlayPlacement { didSet { save() } }
    /// Katmanın gösterildiği ekran (0: ana ekran).
    var overlayDisplayID: UInt32 { didSet { save() } }
    var overlayFontSize: Int { didSet { save() } }
    /// Zeminin koyuluğu, yüzde.
    var overlayBackground: Int { didSet { save() } }
    /// Sabit yerleşimde ekranın altından uzaklık, yüzde.
    var overlayBottomPercent: Int { didSet { save() } }
    /// Ek alanların kutuları da Mac ekranında gösterilsin mi (Windows: DesktopRegionOverlay).
    var overlayRegions: Bool { didSet { save() } }

    /// OBS katmanı ve ikinci ekran TV'den ayrı görünüm kullansın mı; kullanırsa görünümü.
    var obsSeparateStyle: Bool { didSet { save() } }
    var obsStyle: StyleSettings { didSet { save() } }

    /// Türkçe dublaj (pocket-tts) açık mı ve ses nereden çıkacak.
    var dubbingEnabled: Bool { didSet { save() } }
    var dubbingOutputTV: Bool { didSet { save() } }
    /// Dublajda karakterlere verilen sesler (yeni karakter ilk konuştuğunda atanır, kalıcıdır).
    var dubbingCast: VoiceCast { didSet { save() } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        func string(_ key: String, _ fallback: String) -> String { defaults.string(forKey: key) ?? fallback }
        func int(_ key: String, _ fallback: Int) -> Int { defaults.object(forKey: key) as? Int ?? fallback }
        func bool(_ key: String, _ fallback: Bool) -> Bool { defaults.object(forKey: key) as? Bool ?? fallback }
        videoSource = VideoSource(rawValue: string("mac.videoSource", "console")) ?? .console
        captureDeviceID = string("mac.captureDeviceID", "")
        windowBundleID = string("mac.windowBundleID", "")
        windowTitle = string("mac.windowTitle", "")
        displayID = UInt32(int("mac.displayID", 0))
        overlayEnabled = bool("mac.overlayEnabled", false)
        overlayPlacement = OverlayPlacement(rawValue: string("mac.overlayPlacement", "fixed")) ?? .fixed
        overlayDisplayID = UInt32(int("mac.overlayDisplayID", 0))
        overlayFontSize = int("mac.overlayFontSize", 34)
        overlayBackground = int("mac.overlayBackground", 70)
        overlayBottomPercent = int("mac.overlayBottomPercent", 8)
        overlayRegions = bool("mac.overlayRegions", true)
        obsSeparateStyle = bool("mac.obsSeparateStyle", false)
        obsStyle = (defaults.string(forKey: "mac.obsStyle")).flatMap { text in
            (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any]
        }.map { Settings.from(["style": $0]).style } ?? StyleSettings()
        dubbingEnabled = bool("mac.dubbingEnabled", false)
        dubbingOutputTV = bool("mac.dubbingOutputTV", true)
        dubbingCast = defaults.data(forKey: "mac.dubbingCast").flatMap { try? JSONDecoder().decode(VoiceCast.self, from: $0) } ?? VoiceCast()
    }

    private func save() {
        defaults.set(videoSource.rawValue, forKey: "mac.videoSource")
        defaults.set(captureDeviceID, forKey: "mac.captureDeviceID")
        defaults.set(windowBundleID, forKey: "mac.windowBundleID")
        defaults.set(windowTitle, forKey: "mac.windowTitle")
        defaults.set(Int(displayID), forKey: "mac.displayID")
        defaults.set(overlayEnabled, forKey: "mac.overlayEnabled")
        defaults.set(overlayPlacement.rawValue, forKey: "mac.overlayPlacement")
        defaults.set(Int(overlayDisplayID), forKey: "mac.overlayDisplayID")
        defaults.set(overlayFontSize, forKey: "mac.overlayFontSize")
        defaults.set(overlayBackground, forKey: "mac.overlayBackground")
        defaults.set(overlayBottomPercent, forKey: "mac.overlayBottomPercent")
        defaults.set(overlayRegions, forKey: "mac.overlayRegions")
        defaults.set(obsSeparateStyle, forKey: "mac.obsSeparateStyle")
        var settings = Settings()
        settings.style = obsStyle
        if let style = settings.json["style"], let data = try? JSONSerialization.data(withJSONObject: style) {
            defaults.set(String(decoding: data, as: UTF8.self), forKey: "mac.obsStyle")
        }
        defaults.set(dubbingEnabled, forKey: "mac.dubbingEnabled")
        defaults.set(dubbingOutputTV, forKey: "mac.dubbingOutputTV")
        if let cast = try? JSONEncoder().encode(dubbingCast) { defaults.set(cast, forKey: "mac.dubbingCast") }
    }
}
