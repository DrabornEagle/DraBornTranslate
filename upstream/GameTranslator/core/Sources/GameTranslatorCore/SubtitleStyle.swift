// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// TV'de çevirinin oyundaki İngilizce altyazıya göre yeri.
public enum SubtitlePlacement: String, Codable, CaseIterable, Sendable {
    /// İngilizce altyazının üstüne, onu örterek.
    case cover
    /// İngilizce altyazının hemen üstünde.
    case above
    /// İngilizce altyazının hemen altında.
    case below
    /// Ekranda sabit bir yükseklikte (`bottomPercent`).
    case fixed
}

/// TV'de altyazı yazısının rengi (Windows: SubtitleTextColor).
public enum SubtitleTextColor: String, Codable, CaseIterable, Sendable {
    case white, yellow, cyan, green, orange

    /// Yazı renginin ekrandaki tonu (TV uygulaması ve OBS katmanı `color` alanını okur).
    public var hex: String {
        switch self {
        case .yellow: "#FFE600"
        case .cyan: "#38BDF8"
        case .green: "#10B981"
        case .orange: "#FF9F0A"
        case .white: "#FFFFFF"
        }
    }

    /// Windows'un ayar dosyası adı büyük harfle yazar ("Yellow"); ikisi de okunur.
    init?(loose value: String) {
        self.init(rawValue: value.lowercased())
    }
}

/// Android TV'de PS5 görüntüsünün nasıl gösterildiği. Yalnız Android TV uygulaması kullanır.
public enum TvDisplayMode: String, Codable, CaseIterable, Sendable {
    /// HDMI GameTranslator'ın içinde; görüntü hiç gelmezse TV uygulaması kendiliğinden katmana geçer.
    case inApp
    /// TV HDMI'yı kendi ekranında gösterir, altyazı üstteki şeffaf katmanda.
    case overlay

    init?(loose value: String) {
        switch value.lowercased() {
        case "inapp": self = .inApp
        case "overlay": self = .overlay
        default: return nil
        }
    }
}

/// TV'de altyazının görünümü (Windows'taki SubtitleStyle ile aynı alanlar). Eski sürümün kaydettiği ayarlarda olmayan
/// alanlar varsayılanla dolar.
public struct SubtitleStyle: Codable, Equatable, Sendable {
    /// 36 (küçük), 46 (orta), 58 (büyük), 72 (çok büyük)
    public var fontSize: Int
    /// Sabit yerleşimde altyazının ekranın altından uzaklığı, yüzde.
    public var bottomPercent: Int
    public var placement: SubtitlePlacement
    /// Yazının arkasındaki siyah zeminin koyuluğu, yüzde (0: zemin yok).
    public var background: Int
    public var bold: Bool
    public var textColor: SubtitleTextColor
    public var displayMode: TvDisplayMode
    /// Hızlı konuşmada önceki satır(lar) yeni satırın üstünde kısa süre soluk kalsın mı (TV 0.9.16+).
    public var stackPrevious: Bool
    /// Konuşmacı adı renkli: her konuşmacı kendi rengini alır (TV 1.0.9+).
    public var speakerColor: Bool
    /// "" standart yazı, hazır seçeneklerden biri ("quicksand", "pricedown") ya da bilgisayarda kurulu bir fontun adı.
    public var font: String
    /// Ek alan kutusunun zemininin koyuluğu, yüzde (100: İngilizce hiç görünmez).
    public var regionBackground: Int
    /// Ek alan yazısının boyu, kutuya göre seçilen boyun yüzdesi (70–150).
    public var regionTextScale: Int
    /// Ek alan yazısının rengi; nil: altyazının rengi.
    public var regionTextColor: SubtitleTextColor?

    public init(fontSize: Int = 46, bottomPercent: Int = 7, placement: SubtitlePlacement = .cover, background: Int = 72,
                bold: Bool = false, textColor: SubtitleTextColor = .white, displayMode: TvDisplayMode = .inApp,
                stackPrevious: Bool = true, speakerColor: Bool = false, font: String = "", regionBackground: Int = 100,
                regionTextScale: Int = 100, regionTextColor: SubtitleTextColor? = nil) {
        self.fontSize = fontSize
        self.bottomPercent = bottomPercent
        self.placement = placement
        self.background = background
        self.bold = bold
        self.textColor = textColor
        self.displayMode = displayMode
        self.stackPrevious = stackPrevious
        self.speakerColor = speakerColor
        self.font = font
        self.regionBackground = regionBackground
        self.regionTextScale = regionTextScale
        self.regionTextColor = regionTextColor
    }

    private enum CodingKeys: String, CodingKey {
        case fontSize, bottomPercent, placement, background, bold, textColor, displayMode, stackPrevious, speakerColor, font
        case regionBackground, regionTextScale, regionTextColor
    }

    /// Eski sürümün kaydettiği ayarlarda yeni alanlar yoktur; eksikler (ya da tanınmayan değerler) varsayılanla dolar.
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        let defaults = SubtitleStyle()
        fontSize = (try? values.decodeIfPresent(Int.self, forKey: .fontSize)) ?? defaults.fontSize
        bottomPercent = (try? values.decodeIfPresent(Int.self, forKey: .bottomPercent)) ?? defaults.bottomPercent
        placement = (try? values.decodeIfPresent(String.self, forKey: .placement)).flatMap { SubtitlePlacement(rawValue: $0.lowercased()) }
            ?? defaults.placement
        background = (try? values.decodeIfPresent(Int.self, forKey: .background)) ?? defaults.background
        bold = (try? values.decodeIfPresent(Bool.self, forKey: .bold)) ?? defaults.bold
        textColor = (try? values.decodeIfPresent(String.self, forKey: .textColor)).flatMap { SubtitleTextColor(loose: $0) }
            ?? defaults.textColor
        displayMode = (try? values.decodeIfPresent(String.self, forKey: .displayMode)).flatMap { TvDisplayMode(loose: $0) }
            ?? defaults.displayMode
        stackPrevious = (try? values.decodeIfPresent(Bool.self, forKey: .stackPrevious)) ?? defaults.stackPrevious
        speakerColor = (try? values.decodeIfPresent(Bool.self, forKey: .speakerColor)) ?? defaults.speakerColor
        font = (try? values.decodeIfPresent(String.self, forKey: .font)) ?? defaults.font
        regionBackground = (try? values.decodeIfPresent(Int.self, forKey: .regionBackground)) ?? defaults.regionBackground
        regionTextScale = (try? values.decodeIfPresent(Int.self, forKey: .regionTextScale)) ?? defaults.regionTextScale
        regionTextColor = (try? values.decodeIfPresent(String.self, forKey: .regionTextColor)).flatMap { SubtitleTextColor(loose: $0) }
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(fontSize, forKey: .fontSize)
        try values.encode(bottomPercent, forKey: .bottomPercent)
        try values.encode(placement, forKey: .placement)
        try values.encode(background, forKey: .background)
        try values.encode(bold, forKey: .bold)
        try values.encode(textColor, forKey: .textColor)
        try values.encode(displayMode, forKey: .displayMode)
        try values.encode(stackPrevious, forKey: .stackPrevious)
        try values.encode(speakerColor, forKey: .speakerColor)
        try values.encode(font, forKey: .font)
        try values.encode(regionBackground, forKey: .regionBackground)
        try values.encode(regionTextScale, forKey: .regionTextScale)
        try values.encodeIfPresent(regionTextColor, forKey: .regionTextColor)
    }
}

/// Bilgisayarda kurulu ya da uygulamaya gömülü bir font dosyası (TV, OBS ve ikinci ekran onu sunucudan indirir).
public struct FontFile: Equatable, Sendable {
    public var data: Data
    /// "font/ttf" ya da "font/otf"
    public var type: String

    public init(data: Data, type: String) {
        self.data = data
        self.type = type
    }
}

/// Altyazı fontu (Windows: SubtitleFonts). `SubtitleStyle.font` boşsa her ekranın kendi standart yazısı, hazır
/// seçeneklerden biriyse onun font adı, değilse bilgisayarda kurulu bir fontun adıdır. Yalnız Quicksand uygulamaya
/// gömülüdür (SIL OFL); Pricedown'u kullanıcı kendi bilgisayarına kurar.
public enum SubtitleFonts {
    /// Eski ad: menüde artık yok, kaydedilmişse Pricedown sayılır.
    public static let gta6 = "gta6"
    public static let pricedown = "pricedown"
    /// Uygulamaya gömülü (SIL OFL): GTA 6 altyazılarına en yakın açık lisanslı font.
    public static let quicksand = "quicksand"

    public static let pricedownDownload = "https://dl.dafont.com/dl/?f=pricedown"
    public static let pricedownPage = "https://www.dafont.com/pricedown.font"

    /// Menüdeki hazır seçenekler: ayardaki anahtar ve gösterilen ad.
    public static let presets: [(key: String, label: String)] = [(quicksand, "Quicksand (GTA 6)"), (pricedown, "Pricedown")]

    /// Menüde gösterilecek anahtar (eski "gta6" → "pricedown").
    public static func menuKey(_ font: String?) -> String {
        let trimmed = font?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed == gta6 ? pricedown : trimmed
    }

    /// Ayardaki fontun aranacak adı; nil: standart yazı.
    public static func family(_ font: String?) -> String? {
        guard let name = font?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { return nil }
        switch name {
        case gta6, pricedown: return "Pricedown"
        case quicksand: return "Quicksand"
        default: return name
        }
    }

    /// Hazır seçeneğin font zip'i (kurulu değilse "Fontu indir ve kur" bunu indirir).
    public static func downloadPage(_ font: String?) -> String? { font == gta6 || font == pricedown ? pricedownDownload : nil }

    /// İndirme olmazsa tarayıcıda açılacak sayfa.
    public static func webPage(_ font: String?) -> String? { font == gta6 || font == pricedown ? pricedownPage : nil }

    /// Gömülü fontun dosya adı ("Quicksand-Bold.ttf"); yoksa nil.
    public static func embeddedFileName(_ family: String?) -> String? {
        family?.lowercased() == "quicksand" ? "Quicksand-Bold.ttf" : nil
    }

    private static let quicksandFile: FontFile? = {
        guard let url = Bundle.module.url(forResource: "Quicksand-Bold", withExtension: "ttf", subdirectory: "fonts")
            ?? Bundle.module.url(forResource: "Quicksand-Bold", withExtension: "ttf"),
              let data = try? Data(contentsOf: url), !data.isEmpty else { return nil }
        return FontFile(data: data, type: "font/ttf")
    }()

    /// Gömülü fontun dosyası (kurulum gerekmez); yoksa nil.
    public static func embedded(_ family: String?) -> FontFile? {
        family?.lowercased() == "quicksand" ? quicksandFile : nil
    }
}

/// Konuşmacı adlarının renkleri ("Konuşmacı adı renkli" açıkken; Windows: SpeakerColors). Her konuşmacı kendi rengini
/// alır: renk ilk görülme sırasıyla verilir, aynı ad hep aynı renkte kalır; renkler biterse en uzun süredir
/// konuşmayanınki alınır. TV uygulaması, OBS katmanı ve ikinci ekran aynı kuralı uygular (style olayındaki `speakerColors`).
public final class SpeakerColors: @unchecked Sendable {
    private static let all: [(hex: String, clash: SubtitleTextColor?)] = [
        ("#FFD60A", .yellow), ("#64D2FF", .cyan), ("#FF9F0A", .orange), ("#30D158", .green), ("#FF7AB6", nil), ("#C49BFF", nil),
    ]

    /// Yazı rengine karışmayan renkler; ilki eski tek renkle aynıdır (sarı, yazı sarıysa açık mavi).
    public static func palette(_ text: SubtitleTextColor) -> [String] {
        all.filter { $0.clash != text }.map(\.hex)
    }

    /// Ad: büyük harfle başlayan en çok üç sözcük, satır başında ya da cümle bitiminde, ardından ":" (TV ile aynı).
    private static let speaker = try! NSRegularExpression(
        pattern: #"(^|[.!?…”"]\s+|\n)([A-ZÇĞİÖŞÜÂÎÛ][^\s:.!?]*(?: [A-ZÇĞİÖŞÜÂÎÛ][^\s:.!?]*){0,2}):(?=\s)"#)

    private let lock = NSLock()
    private var currentPalette: [String] = []
    private var colors: [String: String] = [:]
    private var recent: [String] = []

    public init() {}

    /// Metni parçalara böler: konuşmacı adı (":" dahil) rengiyle, gerisi nil renkle.
    public func split(_ text: String, palette: [String]) -> [(text: String, color: String?)] {
        lock.lock()
        defer { lock.unlock() }
        if currentPalette != palette {
            currentPalette = palette
            colors.removeAll()
            recent.removeAll()
        }
        guard !palette.isEmpty else { return [(text, nil)] }
        let ns = text as NSString
        var parts: [(text: String, color: String?)] = []
        var last = 0
        for match in Self.speaker.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            let lead = match.range(at: 1)
            let name = ns.substring(with: match.range(at: 2))
            let start = match.range.location + lead.length
            if start > last { parts.append((ns.substring(with: NSRange(location: last, length: start - last)), nil)) }
            parts.append((name + ":", color(for: name)))
            last = match.range.location + match.range.length
        }
        if last < ns.length { parts.append((ns.substring(from: last), nil)) }
        return parts
    }

    /// Kilit altında çağrılır.
    private func color(for name: String) -> String {
        let key = name.uppercased()
        if let index = recent.firstIndex(of: key) {
            recent.remove(at: index)
        } else {
            let used = Set(colors.values)
            var free = currentPalette.first { !used.contains($0) }
            if free == nil, !recent.isEmpty {
                let oldest = recent.removeFirst()
                free = colors.removeValue(forKey: oldest)
            }
            colors[key] = free ?? currentPalette[0]
        }
        recent.append(key)
        return colors[key] ?? currentPalette[0]
    }
}

/// Örtme yerleşiminde TV, İngilizce altyazının kutusunu koyu bir zeminle örter ve çeviriyi ekranın yatay ortasına, kutunun
/// dikey ortasına yazar (Windows: CoverFit). Kutu, çevirinin kaplayacağı yer tahmin edilerek büyütülür: zemin hem
/// İngilizceyi örter hem çeviriyi içine alır. Tahmin TV uygulamasının ölçülerine dayanır (1920×1080 tasarım, satır aralığı
/// 1,3, yazı alanı ekranın %92'si).
public enum CoverFit {
    private static let screenWidth = 1920.0
    private static let screenHeight = 1080.0
    /// Yazı alanının genişliği (TV'de soldan ve sağdan %4 boşluk).
    private static let maxWidth = 0.92
    /// Ortalama harf genişliğinin yazı boyuna oranı (kalın, Latin harfli yazı; boşluklar dahil).
    private static let charWidth = 0.63
    private static let lineHeight = 1.3

    public static func box(_ source: TextBox, text: String, fontSize: Int) -> TextBox {
        let size = Double(min(120, max(16, fontSize)))
        let rows = text.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard !rows.isEmpty else { return source }
        var widest = 0.0
        var lines = 0
        for row in rows {
            let width = Double(row.utf16.count) * size * charWidth / screenWidth
            // Satır alana sığmazsa TV aşağı sarar.
            let wrapped = max(1, Int((width / maxWidth).rounded(.up)))
            lines += wrapped
            widest = max(widest, wrapped > 1 ? maxWidth : width)
        }
        let height = Double(lines) * size * lineHeight / screenHeight
        let middle = (source.top + source.bottom) / 2
        func clamp(_ value: Double) -> Double { min(1, max(0, value)) }
        // Çeviri ekranın yatay ortasına yazılır; kutu İngilizcenin de çevirinin de tamamını kapsar.
        return TextBox(left: clamp(min(source.left, 0.5 - widest / 2)), top: clamp(min(source.top, middle - height / 2)),
                       right: clamp(max(source.right, 0.5 + widest / 2)), bottom: clamp(max(source.bottom, middle + height / 2)))
    }
}

/// Örtme yerleşiminde TV'deki koyu zeminin satırdan satıra zıplamasını önler (Windows: StableCover). Kısa süre içinde
/// (`holdMs`) aynı yere gelen satırın zemini öncekiyle birleştirilir: büyüyebilir, küçülmez. Bir süre altyazı gelmezse
/// ya da altyazı ekranın başka bir yerine geçerse zemin baştan, yeni kutuya göre kurulur.
public final class StableCover: @unchecked Sendable {
    /// Son altyazıdan bu kadar sonra gelen satırın zemini baştan kurulur.
    public static let holdMs: Int64 = 4000
    /// Birleşen zemin yeni kutunun yüksekliğinin bu katını ya da ekranın %20'sini geçerse birleştirilmez.
    private static let maxGrowth = 2.6
    private static let maxHeight = 0.2
    /// Yeni kutu önceki zeminin genişliğinin bu katından darsa birleştirilmez.
    public static let narrowerThan = 0.7

    private let now: () -> Int64
    private let lock = NSLock()
    private var last: TextBox?
    private var lastAt: Int64 = 0

    /// `clock`: milisaniye; verilmezse sistemin açılıştan beri geçen süresi.
    public init(clock: (() -> Int64)? = nil) {
        now = clock ?? { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    }

    /// `newLine`: kutu yeni bir satırın (öncekinin düzelmiş okuması değil). Yeni satır belirgin şekilde darsa kendi
    /// boyunda örtülür; aynı satırın eksik okunan hâli ise zemini daraltmaz.
    public func next(_ input: TextBox, newLine: Bool = false) -> TextBox {
        lock.lock()
        defer { lock.unlock() }
        let at = now()
        var box = input
        // Geniş alanın kutusu ekranın yarısını kaplayabiliyor; zemin en çok maxHeight kadar alttan alınır.
        if box.bottom - box.top > Self.maxHeight { box.top = box.bottom - Self.maxHeight }
        var result = box
        var narrower = false
        if newLine, let wide = last { narrower = box.right - box.left < (wide.right - wide.left) * Self.narrowerThan }
        if let previous = last, !narrower, at - lastAt <= Self.holdMs, Self.near(previous, box) {
            let union = previous.union(box)
            let height = union.bottom - union.top
            if height <= max(box.bottom - box.top, 0.02) * Self.maxGrowth && height <= Self.maxHeight { result = union }
        }
        last = result
        lastAt = at
        return result
    }

    /// Kutular dikeyde üst üste biniyor ya da aralarında bir kutu yüksekliğinden az boşluk var.
    private static func near(_ a: TextBox, _ b: TextBox) -> Bool {
        let gap = max(a.top, b.top) - min(a.bottom, b.bottom)
        return gap <= max(a.bottom - a.top, b.bottom - b.top)
    }
}
