// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Ayardaki kumanda tuşları seçimi: Otomatik, PlayStation ya da Xbox (Windows: ButtonLayout.cs, Android: ButtonLayout.kt).
public enum ButtonLayout: String, CaseIterable, Sendable {
    /// Görüntü kaynağından (Xbox / PS5) ya da ekrandaki simgelerin biçiminden anlaşılır.
    case auto
    case playstation
    case xbox

    /// Kayıttaki ad ("auto", "playstation", "xbox"); bilinmiyorsa Otomatik.
    public static func parse(_ value: String?) -> ButtonLayout {
        ButtonLayout(rawValue: (value ?? "").lowercased()) ?? .auto
    }

    /// Kayıttaki adı.
    public var key: String { rawValue }
}

/// Ekrandaki tuş simgelerinin ailesi.
public enum ButtonFamily: Sendable {
    case playstation
    case xbox
}

/// Simgeler hangi kumandanın: ayarda seçildiyse o, Otomatik'te kaynağa göre (Xbox kaynağı Xbox, PS5 Remote Play
/// PlayStation); başka kaynakta karelerdeki simgelerin biçiminden (dairede harf mi, PlayStation sembolü mü) karar verilir.
/// Karar yapışkandır: tek bir yanlış tanınan simge aileyi değiştirmez, birkaç tutarlı simgeden sonra değişir. Bilinmeden
/// önce PlayStation sayılır (Windows: ButtonLayoutVote, Android: ButtonLayoutVote). İş parçacıkları arasında paylaşılabilir.
public final class ButtonLayoutVote: @unchecked Sendable {
    /// Kararın değişmesi için gereken üst üste ağır basan simge sayısı.
    public static let switchCount = 3
    private static let limit = 6

    /// Otomatik seçim kaynağa göre çözülür: `xboxSource` true ise Xbox, false ise PlayStation (PS5 Remote Play), nil ise
    /// görüntüden karar verilir.
    public static func resolve(_ setting: ButtonLayout, xboxSource: Bool?) -> ButtonLayout {
        if setting != .auto { return setting }
        switch xboxSource {
        case .some(true): return .xbox
        case .some(false): return .playstation
        case .none: return .auto
        }
    }

    private let lock = NSLock()
    private var score = 0
    private var sticky = ButtonFamily.playstation
    private var stored: ButtonLayout

    public init(_ setting: ButtonLayout = .auto) {
        stored = setting
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    /// Geçerli seçim (kaynağa göre çözülmüş). Değişince Otomatik karar sıfırlanır.
    public var setting: ButtonLayout {
        get { locked { stored } }
        set {
            locked {
                if stored == newValue { return }
                stored = newValue
                score = 0
                sticky = .playstation
            }
        }
    }

    /// Şu an beklenen aile.
    public var current: ButtonFamily {
        locked {
            switch stored {
            case .playstation: return .playstation
            case .xbox: return .xbox
            case .auto: return sticky
            }
        }
    }

    /// Otomatik ve henüz karar verilmemiş mi.
    public var undecided: Bool { locked { stored == .auto && abs(score) < Self.switchCount } }

    /// Karar Xbox'a yatıyor mu (seçilmiş ya da Otomatik'te Xbox simgesi daha çok görülmüş).
    public var leansXbox: Bool { locked { stored == .xbox || (stored == .auto && score > 0) } }

    /// Kesin biçimli bir simge görüldü (dairede yeşil "A" Xbox'tır, üçgen PlayStation'dır).
    public func add(_ seen: ButtonFamily) {
        locked {
            if stored != .auto { return }
            score = min(Self.limit, max(-Self.limit, score + (seen == .xbox ? 1 : -1)))
            if score >= Self.switchCount { sticky = .xbox } else if score <= -Self.switchCount { sticky = .playstation }
        }
    }

    public func reset() {
        locked {
            score = 0
            sticky = .playstation
        }
    }
}

/// Bütün platformların ortak tuş işaretleri. PlayStation: ✕ ○ △ □, L1–R3, OPTIONS, TOUCHPAD (▦). Xbox: Ⓐ Ⓑ Ⓧ Ⓨ,
/// LB RB LT RT LS RS, View ⧉ ve Menu ☰. Yön tuşu ikisinde de ↑ ↓ ← → ✜ (Windows: ButtonTokens, Android: ButtonTokens).
public enum ButtonTokens {
    public static let a = "Ⓐ"
    public static let b = "Ⓑ"
    public static let x = "Ⓧ"
    public static let y = "Ⓨ"
    public static let view = "⧉"
    public static let menu = "☰"

    /// Xbox'ın omuz, tetik ve çubuk tuşları.
    public static let xboxNames: Set<String> = ["LB", "RB", "LT", "RT", "LS", "RS"]

    private static let playstationNames: Set<String> = [
        "✕", "○", "△", "□", "▦", "OPTIONS", "TOUCHPAD", "L1", "L2", "L3", "R1", "R2", "R3",
    ]

    /// Xbox yüz tuşunun harfi ("A") → işareti ("Ⓐ"); yüz tuşu harfi değilse nil.
    public static func xboxFace(_ letter: Character) -> String? {
        switch letter.uppercased() {
        case "A": return a
        case "B": return b
        case "X": return x
        case "Y": return y
        default: return nil
        }
    }

    /// İşaret hangi ailenin: yön tuşu ve harita işareti ikisinde de olabilir (nil).
    public static func familyOf(_ token: String) -> ButtonFamily? {
        if [a, b, x, y, view, menu].contains(token) || xboxNames.contains(token) { return .xbox }
        if playstationNames.contains(token) { return .playstation }
        return nil
    }

    /// Xbox yüz tuşunun rengine göre işareti (ton 0–360): yeşil Ⓐ, kırmızı Ⓑ, mavi Ⓧ, sarı Ⓨ.
    static func xboxFaceByHue(_ hue: Double) -> String? {
        if hue >= 75 && hue < 170 { return a }
        if hue >= 170 && hue < 265 { return x }
        if hue >= 38 && hue < 75 { return y }
        if hue >= 330 || hue < 22 { return b }
        return nil
    }
}
