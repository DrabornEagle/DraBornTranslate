// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// TV'ye giden olayların JSON değeri. Anahtarlar sıralı yazılır (Windows'taki SortedDictionary ile aynı çıktı); Türkçe
/// harfler kaçışsız gider, sayılar dört basamağa yuvarlanır ve sondaki sıfırlar atılır (0.8750 → 0.875).
public indirect enum FrameValue: Equatable, Sendable {
    case string(String)
    case int(Int64)
    case bool(Bool)
    /// 0–1 arasına kısılır, dört basamağa yuvarlanır.
    case ratio(Double)
    case array([FrameValue])
    case object([String: FrameValue])

    public var json: String {
        var output = ""
        write(to: &output)
        return output
    }

    private func write(to output: inout String) {
        switch self {
        case .string(let text): Self.quote(text, into: &output)
        case .int(let value): output += String(value)
        case .bool(let value): output += value ? "true" : "false"
        case .ratio(let value): output += Self.ratioText(value)
        case .array(let items):
            output += "["
            for (index, item) in items.enumerated() {
                if index > 0 { output += "," }
                item.write(to: &output)
            }
            output += "]"
        case .object(let members):
            output += "{"
            // Sıra: UTF-16 kod birimi sırası (.NET'in sıralı sözlüğü ve Kotlin'in elle yazdığı sırayla aynı; anahtarlar ASCII).
            for (index, key) in members.keys.sorted(by: { Array($0.utf16).lexicographicallyPrecedes(Array($1.utf16)) }).enumerated() {
                if index > 0 { output += "," }
                Self.quote(key, into: &output)
                output += ":"
                members[key]!.write(to: &output)
            }
            output += "}"
        }
    }

    /// Dört basamağa yuvarlanır, sondaki sıfırlar atılır; String(format:) kullanılmaz (her platformda aynı çıktı).
    static func ratioText(_ value: Double) -> String {
        let clamped = value.isNaN ? 0 : min(1, max(0, value))
        let scaled = Int((clamped * 10000).rounded())
        if scaled >= 10000 { return "1" }
        if scaled <= 0 { return "0" }
        var digits = String(scaled)
        while digits.count < 4 { digits = "0" + digits }
        while digits.hasSuffix("0") { digits.removeLast() }
        return "0." + digits
    }

    static func quote(_ text: String, into output: inout String) {
        output += "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": output += "\\\""
            case "\\": output += "\\\\"
            case "\n": output += "\\n"
            case "\r": output += "\\r"
            case "\t": output += "\\t"
            case "\u{08}": output += "\\b"
            case "\u{0C}": output += "\\f"
            default:
                if scalar.value < 0x20 {
                    let hex = String(scalar.value, radix: 16)
                    output += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
                } else {
                    output.unicodeScalars.append(scalar)
                }
            }
        }
        output += "\""
    }
}

/// TV uygulamasının, OBS katmanının ve ikinci ekranın okuduğu Server-Sent Events çerçeveleri (Windows'taki
/// SubtitleServer.cs ile aynı protokol). Yalnız metin üretir; ağ işi `SubtitleServer`'dadır, böylece her platformda sınanır.
public enum SubtitleFrames {
    /// Bağlantı canlı tutma çerçevesi: yorum satırını EventSource görmez; adlı "ping" olayını TV görür ve bir süre
    /// gelmezse yarı açık kalmış bağlantıyı kapatıp yeniden bağlanır.
    public static let ping = ": ping\n\nevent: ping\ndata: {}\n\n"

    /// `/hello` yanıtı. TV uygulaması küçük harfle "gametranslator" arar (Android TV "couchsubs" da arar). `active`: çeviri
    /// şu an çalışıyor; ağda başka bir GameTranslator da açıksa TV uygulaması (1.0.6+) çeviri yapana bağlanır.
    public static func helloBody(active: Bool) -> String {
        "{\"active\":\(active),\"app\":\"gametranslator\",\"name\":\"GameTranslator\",\"type\":\"gametrans\",\"legacy\":\"couchsubs\",\"product\":\"couchsubs\"}"
    }

    public static func frame(_ event: String, _ json: [String: FrameValue] = [:]) -> String {
        "event: \(event)\ndata: \(FrameValue.object(json).json)\n\n"
    }

    public static func box(_ box: TextBox) -> FrameValue {
        .object(["bottom": .ratio(box.bottom), "left": .ratio(box.left), "right": .ratio(box.right), "top": .ratio(box.top)])
    }

    /// `subtitle` olayı. `paragraphs`: metin satır satır, sola yaslı gösterilir (görev paneli gibi başlık ve maddeli
    /// yazılar). `replaces`: satır az önce gösterilenin uzamış ya da düzelmiş okumasıdır; TV onu öncekinin üstüne yığmaz.
    /// Örtme yerleşiminde zemin tek parça olsun diye kutu çevirinin sığacağı kadar büyütülür (`CoverFit`, `cover` ile
    /// satırdan satıra zıplamaz); `source` oyunun altyazısının gerçek yeridir (TV 1.0.5+ çeviriyi oraya sığdırır).
    public static func subtitle(_ text: String, durationMs: Int, box: TextBox?, style: SubtitleStyle?, paragraphs: Bool = false,
                                replaces: Bool = false, at: Int64, cover: StableCover? = nil, source: StableCover? = nil) -> String {
        var json: [String: FrameValue] = ["at": .int(at), "duration": .int(Int64(durationMs)), "text": .string(text)]
        if paragraphs { json["paragraphs"] = .bool(true) }
        if replaces { json["replace"] = .bool(true) }
        if let box {
            let covers = style?.placement == .cover && !paragraphs
            if covers, let style {
                let fitted = CoverFit.box(box, text: text, fontSize: style.fontSize)
                json["box"] = Self.box(cover?.next(fitted, newLine: !replaces) ?? fitted)
                json["source"] = Self.box(source?.next(box, newLine: !replaces) ?? box)
            } else {
                json["box"] = Self.box(box)
            }
        }
        return frame("subtitle", json)
    }

    /// `style` olayının gövdesi (Windows'taki StyleJson ile aynı alanlar). Örtmede çevirinin kendi zemini kapatılır (yalnız
    /// örtü kalır), önceki satır da üstüne yığılmaz. `fontUrl`: fontun sunucudaki adresi (yoksa ""; yalnız font seçiliyse yazılır).
    public static func styleJSON(_ style: SubtitleStyle, fontUrl: String? = nil) -> [String: FrameValue] {
        let cover = style.placement == .cover
        let palette = style.speakerColor ? SpeakerColors.palette(style.textColor) : []
        let family = SubtitleFonts.family(style.font)
        return [
            "background": .int(Int64(cover ? 0 : min(100, max(0, style.background)))),
            "bold": .bool(style.bold),
            "bottomPercent": .int(Int64(style.bottomPercent)),
            "color": .string(style.textColor.hex),
            "displayMode": .string(style.displayMode.rawValue),
            "fontFamily": .string(family ?? ""),
            "fontSize": .int(Int64(style.fontSize)),
            "fontUrl": .string(family == nil ? "" : fontUrl ?? ""),
            "placement": .string(style.placement.rawValue),
            "regionBackground": .int(Int64(min(100, max(0, style.regionBackground)))),
            "regionColor": .string(style.regionTextColor?.hex ?? ""),
            "regionScale": .int(Int64(min(150, max(70, style.regionTextScale)))),
            // Konuşmacı adının rengi ("" kapalı); eski TV uygulaması yalnız bunu kullanır.
            "speakerColor": .string(palette.first ?? ""),
            // Her konuşmacıya ayrı renk (yeni TV uygulaması, OBS, ikinci ekran).
            "speakerColors": .array(palette.map { .string($0) }),
            "stackPrevious": .bool(style.stackPrevious && !cover),
            "textColor": .string(style.textColor.rawValue),
        ]
    }

    public static func style(_ style: SubtitleStyle, fontUrl: String? = nil) -> String {
        frame("style", styleJSON(style, fontUrl: fontUrl))
    }

    /// Sürekli okunan ek alanın çevirisi: alanın kimliği (`id`), çeviri (`text`; boşsa kutu kalkar), yazının karedeki yeri
    /// (`box`) ve satır yüksekliği (`line`). Ana altyazıya dokunmaz; olayı tanımayan eski TV uygulamaları yok sayar.
    public static func region(id: String, text: String, box: TextBox?, lineHeight: Double? = nil) -> String {
        var json: [String: FrameValue] = ["id": .string(id), "text": .string(text)]
        if let box { json["box"] = Self.box(box) }
        if let lineHeight { json["line"] = .ratio(lineHeight) }
        return frame("region", json)
    }

    public static let regionClear = frame("region-clear")
    public static let clear = frame("clear")
    public static let screenClear = frame("screen-clear")
    public static let speechStop = frame("speech-stop")

    /// "Ekranı çevir" sonuçları: her çeviri yazının yeri (`box`), tipik satır yüksekliği (`line`) ve çok parçalıysa
    /// `paragraphs` ile gider. TV öncekinin yerine bunları koyar.
    public static func screen(_ items: [ScreenTranslation.Item], durationMs: Int) -> String {
        let list: [FrameValue] = items.map { item in
            var json: [String: FrameValue] = ["box": box(item.box), "line": .ratio(item.lineHeight), "text": .string(item.translation)]
            if item.paragraphs { json["paragraphs"] = .bool(true) }
            return .object(json)
        }
        return frame("screen", ["duration": .int(Int64(durationMs)), "items": .array(list)])
    }

    public static func speech(url: String, durationMs: Int) -> String {
        frame("speech", ["duration": .int(Int64(durationMs)), "url": .string(url)])
    }

    /// Font dosyasının sunucudaki adresi: içeriğin SHA-1'inin ilk 12 hanesi ("/font/0123456789ab.ttf"); aynı dosya için aynı kalır.
    public static func fontPath(_ file: FontFile) -> String {
        let hash = Digest.hex(Digest.sha1(file.data)).prefix(12)
        let type = file.type.lowercased()
        let ext = type.contains("otf") || type.contains("opentype") ? "otf" : "ttf"
        return "/font/\(hash).\(ext)"
    }
}
