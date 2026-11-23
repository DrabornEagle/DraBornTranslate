// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Sürekli okunan ek alanın hazır seçimleri: oyunların çoğu bu yazıları ekranın köşelerinde gösterir (Windows:
/// ExtraAreaPreset, Android: ExtraAreaPreset; aynı bölgeler; CouchSubs / Nino ile aynı). `key` ayar dosyasında ve
/// arayüzde kullanılan addır. Bölgeler karenin kenarlarına oranladır (sol, üst, sağ, alt).
public enum ExtraAreaPreset: String, CaseIterable, Sendable {
    /// Sağ üstteki görev yazısı.
    case quest
    /// Sol alttaki bildirim.
    case notification
    /// Sağ alttaki telefon mesajı ya da e-posta.
    case phone
    /// En alttaki ortalı görev yazısı (GTA 6: altyazının hemen altında, %94–97). Sağ alttaki tuş çubuğu ("PHOTO STUDIO
    /// ✕ SELECT ○ BACK") karışmasın diye alan ortada dardır.
    case bottomObjective = "bottom_objective"
    /// Sol üstteki ipucu kutusu (GTA 6: "Check the map index to view…", %26–37 yükseklikte, %1–23 genişlikte).
    case helpBox = "help_box"
    /// Sağ alttaki telefon mesajı kartı (GTA 6: gönderenin adı ve mesaj). Sağ alttaki tuş çubuğunun başı ("PHOTO
    /// STUDIO") kesilmesin diye biraz geniş.
    case phoneMessage = "phone_message"

    public var key: String { rawValue }

    public var region: TextBox {
        switch self {
        case .quest: return TextBox(left: 0.66, top: 0.03, right: 0.98, bottom: 0.30)
        case .notification: return TextBox(left: 0.02, top: 0.58, right: 0.30, bottom: 0.88)
        case .phone: return TextBox(left: 0.72, top: 0.50, right: 0.98, bottom: 0.95)
        case .bottomObjective: return TextBox(left: 0.28, top: 0.925, right: 0.72, bottom: 0.99)
        case .helpBox: return TextBox(left: 0.01, top: 0.22, right: 0.27, bottom: 0.46)
        case .phoneMessage: return TextBox(left: 0.70, top: 0.78, right: 0.995, bottom: 0.99)
        }
    }

    public static func byKey(_ key: String?) -> ExtraAreaPreset? {
        guard let key else { return nil }
        return ExtraAreaPreset(rawValue: key)
    }

    /// Bölge bir hazır seçimle aynıysa o.
    public static func matching(_ region: TextBox) -> ExtraAreaPreset? { allCases.first { $0.region == region } }
}

/// Sürekli okunan ek alan (görev yazısı, telefon mesajı, bildirim). `id`: TV'deki kutunun kimliği; `name`: günlükte
/// görünen ad; `region`: karenin kenarlarına oranla.
public struct WatchedArea: Hashable, Sendable {
    public var id: String
    public var name: String
    public var region: TextBox

    public init(id: String, name: String, region: TextBox) {
        self.id = id
        self.name = name
        self.region = region
    }
}

public enum ExtraAreas {
    /// Ayarlardaki ek alan yuvası sayısı (Windows: UserSettings.ExtraRegionCount).
    public static let slots = 3

    /// Sürekli okunan ek alanların içine düşen satırlar altyazı değildir (GTA 6'da altyazı şeridi %76–100, alttaki görev
    /// yazısı %94–97: görev yazısı altyazı gibi de çevriliyordu). Satırın ortası bir ek alanın içindeyse atılır. Satır
    /// kutuları karenin tamamına oranladır (Windows: AppController.WithoutWatchedAreas).
    public static func withoutWatchedAreas(_ lines: [TextLine], _ watched: [WatchedArea]) -> [TextLine] {
        if watched.isEmpty || lines.isEmpty { return lines }
        return lines.filter { line in
            guard let box = line.box else { return true }
            let cx = (box.left + box.right) / 2, cy = (box.top + box.bottom) / 2
            return !watched.contains { w in cx >= w.region.left && cx <= w.region.right && cy >= w.region.top && cy <= w.region.bottom }
        }
    }

    /// Ek alanda çevrilmeyecek satır: altyazının alana düşen satırı (konuşmacı adlı ya da o an gösterilen altyazıya
    /// benzeyen) ve uzun altyazı satırının alana taşan ucu ("ot it?", "nd..."). `shownSource`: son gösterilen
    /// altyazının aslı (yoksa nil). Windows: AppController.UpdateRegionWatcher içindeki SkipLine.
    public static func skipLine(_ text: String, shownSource: String?) -> Bool {
        if hasSpeakerLabel(text) { return true }
        guard let shownSource, !shownSource.isEmpty else { return false }
        let shown = TextMatch.normalize(shownSource), part = TextMatch.normalize(text)
        return TextMatch.isSimilar(shown, part, threshold: 0.8) || (part.count >= 3 && shown.contains(part))
    }

    /// Satır "Ad: söz" biçiminde mi (ad büyük harfle başlar, en çok üç sözcüktür).
    public static func hasSpeakerLabel(_ text: String) -> Bool {
        let line = text.trimStart(" \t\n\r")
        let colon = line.characterOffset(of: ": ")
        guard (2...30).contains(colon), line.first!.isUppercase else { return false }
        let name = line.slice(0, colon)
        return name.splitKeepingEmpty(" ").count <= 3 &&
            name.allSatisfy { $0.isLetter || $0 == " " || $0 == "-" || $0 == "." || $0 == "'" || $0 == "’" }
    }
}

/// Ek alan yuvası (Windows: UserSettings.ExtraRegion1-3 ve ExtraLive1-3; Android: ExtraSlot): `region` nil ise yuva
/// boş; `live` açıksa alan sürekli okunup çevrilir.
public struct ExtraSlot: Hashable, Sendable {
    public var region: TextBox?
    public var live: Bool

    public init(region: TextBox? = nil, live: Bool = false) {
        self.region = region
        self.live = live
    }
}

/// Ayarlardaki ek alan yuvaları (Android: Settings.extraAreas ve yardımcıları; Windows: UserSettings). Değer türüdür;
/// her değişiklik yeni bir kopya döner. Yuvalar 1'den başlar.
public struct ExtraSlots: Hashable, Sendable {
    public private(set) var slots: [ExtraSlot]

    public init(_ slots: [ExtraSlot] = []) {
        self.slots = (0..<ExtraAreas.slots).map { $0 < slots.count ? slots[$0] : ExtraSlot() }
    }

    /// 1, 2 ya da 3 numaralı ek alanın bölgesi; boşsa nil.
    public func region(_ slot: Int) -> TextBox? { (1...ExtraAreas.slots).contains(slot) ? slots[slot - 1].region : nil }

    public func live(_ slot: Int) -> Bool { (1...ExtraAreas.slots).contains(slot) && slots[slot - 1].live }

    private func with(_ slot: Int, _ edit: (inout ExtraSlot) -> Void) -> ExtraSlots {
        guard (1...ExtraAreas.slots).contains(slot) else { return self }
        var copy = self
        edit(&copy.slots[slot - 1])
        return copy
    }

    public func withRegion(_ slot: Int, _ region: TextBox?) -> ExtraSlots { with(slot) { $0.region = region } }

    public func withLive(_ slot: Int, _ live: Bool) -> ExtraSlots { with(slot) { $0.live = live } }

    /// Hazır ek alan sürekli okunuyor mu.
    public func has(_ preset: ExtraAreaPreset) -> Bool {
        (1...ExtraAreas.slots).contains { live($0) && region($0) == preset.region }
    }

    /// Hazır ek alanı açar ya da kapatır (Windows: WithExtraPreset). Açarken sürekli okunmayan ilk yuva kullanılır (aynı
    /// bölgeli yuva önce); üç yuva da doluysa ayar değişmez. Kapatınca yuvanın bölgesi kalır.
    public func with(_ preset: ExtraAreaPreset, on: Bool) -> ExtraSlots {
        let region = preset.region
        if !on {
            var result = self
            for slot in 1...ExtraAreas.slots where result.region(slot) == region { result = result.withLive(slot, false) }
            return result
        }
        if has(preset) { return self }
        let free = (1...ExtraAreas.slots).filter { !live($0) }
        guard let slot = free.first(where: { self.region($0) == region }) ?? free.first else { return self }
        return withRegion(slot, region).withLive(slot, true)
    }

    /// GTA 6 için tek dokunuş (Windows: WithGta6Reading): üç ek alan (alttaki görev yazısı, soldaki ipucu kutusu, sağ
    /// alttaki telefon mesajı) sürekli okunur. Altyazının otomatik bulunmasını (autoRegion) uygulama ayrıca açar.
    public func withGta6Reading() -> ExtraSlots {
        withRegion(1, ExtraAreaPreset.bottomObjective.region).withLive(1, true)
            .withRegion(2, ExtraAreaPreset.helpBox.region).withLive(2, true)
            .withRegion(3, ExtraAreaPreset.phoneMessage.region).withLive(3, true)
    }

    /// Sürekli okunan ek alanlar (bölgesi olan ve açık yuvalar).
    public func watchedAreas() -> [WatchedArea] {
        (1...ExtraAreas.slots).compactMap { slot in
            guard live(slot), let region = region(slot) else { return nil }
            return WatchedArea(id: "area\(slot)", name: "Alan \(slot)", region: region)
        }
    }

    /// Ayar dosyasındaki `extraAreas` dizisi (Android ile aynı biçim): boş yuva `null`; dolu yuva bölge, sürekli okuma ve
    /// (bölge hazır seçimse) seçimin adı. JSONSerialization ile yazılabilir.
    public func json() -> [Any] {
        (1...ExtraAreas.slots).map { slot -> Any in
            guard let region = region(slot) else { return NSNull() }
            var item: [String: Any] = [
                "region": ["top": region.top, "bottom": region.bottom, "left": region.left, "right": region.right],
                "live": live(slot),
            ]
            if let preset = ExtraAreaPreset.matching(region) { item["preset"] = preset.key }
            return item
        }
    }

    /// Kayıttaki `extraAreas` dizisi. Hazır seçimin adı yazılıysa bölgesi addan gelir (arayüz yalnız adı yazar); yoksa
    /// kayıtlı bölge. Eksik ya da bozuk kayıtta yuvalar boş.
    public init(json: Any?) {
        let list = json as? [Any] ?? []
        self.init((0..<ExtraAreas.slots).map { i -> ExtraSlot in
            guard i < list.count, let item = list[i] as? [String: Any] else { return ExtraSlot() }
            let preset = ExtraAreaPreset.byKey((item["preset"] as? String).flatMap { $0.isEmpty ? nil : $0 })
            let region = preset?.region ?? (item["region"] as? [String: Any]).map(Self.region(fromJson:))
            return ExtraSlot(region: region, live: region != nil && Self.bool(item["live"]))
        })
    }

    /// Kayıttaki bölge (Android: Region.fromJson); kenarlar karenin içinde, en az %5 yükseklik ve genişlikte.
    public static func region(fromJson json: [String: Any]) -> TextBox {
        func number(_ key: String, _ fallback: Double) -> Double {
            if let value = json[key] as? Double { return value }
            if let value = json[key] as? Int { return Double(value) }
            if let value = json[key] as? NSNumber { return value.doubleValue }
            return fallback
        }
        let top = min(max(number("top", 0.62), 0), 0.95)
        let left = min(max(number("left", 0.14), 0), 0.95)
        return TextBox(left: left, top: top,
                       right: min(max(number("right", 0.86), left + 0.05), 1),
                       bottom: min(max(number("bottom", 0.97), top + 0.05), 1))
    }

    private static func bool(_ value: Any?) -> Bool {
        if let value = value as? Bool { return value }
        if let value = value as? NSNumber { return value.boolValue }
        return false
    }
}
