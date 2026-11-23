// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Oyunun renkli yazdığı sözcük ya da sözcük öbeği ("Go to the <b>plane</b>") ve rengi (#rrggbb).
public struct ColoredPhrase: Hashable, Sendable {
    public var text: String
    public var color: String

    public init(_ text: String, color: String) {
        self.text = text
        self.color = color
    }
}

/// Oyunlar görev yazısında önemli sözcüğü renkli yazar (GTA 6: yer ve nesne pembe, kişi mavi). Ek alanın okuyucusu
/// sözcüklerin rengini görüntüden bulur; çeviride o sözcüğün karşılığı aynı renkte gösterilir. TV'ye giden metinde renkli
/// parça `⁅#rrggbb|metin⁆` biçimindedir (yalnız ek alanlarda; altyazıda kullanılmaz). Windows: ColoredWords.cs,
/// Android: ColoredWords.kt.
public enum ColoredWords {
    public static let open: Character = "⁅"
    public static let divider: Character = "|"
    public static let close: Character = "⁆"
    private static let turkish = Locale(identifier: "tr_TR")
    private static let marked = Patterns.make("⁅#[0-9a-fA-F]{6}\\|([^⁆]*)⁆")

    /// Sözcüğün yazı rengi: kutudaki en parlak piksellerin (yazının kendisi; zemin ve gölge koyudur) ortalaması. Beyaz ya
    /// da gri yazıysa nil.
    public static func colorOf(_ image: PixelImage, box: TextBox) -> String? {
        func clamp(_ v: Int, _ hi: Int) -> Int { min(max(v, 0), hi) }
        let x0 = clamp(Int(box.left * Double(image.width)), image.width)
        let x1 = clamp(Int(ceil(box.right * Double(image.width))), image.width)
        let y0 = clamp(Int(box.top * Double(image.height)), image.height)
        let y1 = clamp(Int(ceil(box.bottom * Double(image.height))), image.height)
        if x1 - x0 < 2 || y1 - y0 < 2 { return nil }
        var top = 0.0
        for y in y0..<y1 { for x in x0..<x1 { top = max(top, image.luma(x, y)) } }
        if top < 0.35 { return nil }
        var r = 0.0, g = 0.0, b = 0.0
        var count = 0
        for y in y0..<y1 {
            for x in x0..<x1 where image.luma(x, y) >= top * 0.7 {
                b += Double(image.blue(x, y))
                g += Double(image.green(x, y))
                r += Double(image.red(x, y))
                count += 1
            }
        }
        if count < 6 { return nil }
        r /= Double(count) * 255
        g /= Double(count) * 255
        b /= Double(count) * 255
        let hi = max(r, g, b), lo = min(r, g, b)
        if hi < 0.35 || (hi - lo) / hi < 0.35 { return nil }
        // Renk canlı gösterilsin diye en parlak kanal 255'e çekilir.
        func channel(_ value: Double) -> String {
            String(format: "%02x", Int((min(1, max(0, value / hi)) * 255).rounded(.toNearestOrEven)))
        }
        return "#\(channel(r))\(channel(g))\(channel(b))"
    }

    /// Satırdaki renkli sözcükler; yan yana ve aynı renkteki sözcükler tek öbektir ("sea plane").
    public static func phrases(_ words: [OcrWord], image: PixelImage) -> [ColoredPhrase] {
        var result: [ColoredPhrase] = []
        var run: [String] = []
        var runColor: String?
        func flush() {
            if !run.isEmpty, let color = runColor { result.append(ColoredPhrase(run.joined(separator: " "), color: color)) }
            run.removeAll()
            runColor = nil
        }
        for word in words {
            // Tek harf (yanlış okunmuş renkli tuş simgesi: "e") renkli sözcük sayılmaz.
            let color = word.text.filter(\.isLetterOrDigit).count >= 2 ? colorOf(image, box: word.box) : nil
            guard let color else {
                flush()
                continue
            }
            if let current = runColor, !sameHue(current, color) { flush() }
            if runColor == nil { runColor = color }
            run.append(word.text.trimBoth(".,!?:;"))
        }
        flush()
        return result
    }

    private static func hue(_ hex: String) -> Double {
        let chars = Array(hex)
        func component(_ i: Int) -> Double { Double(Int(String(chars[i..<(i + 2)]), radix: 16) ?? 0) / 255 }
        let r = component(1), g = component(3), b = component(5)
        let hi = max(r, g, b), lo = min(r, g, b)
        let d = hi - lo
        if d < 1e-6 { return 0 }
        if hi == r { return 60 * (((g - b) / d).truncatingRemainder(dividingBy: 6) + 6).truncatingRemainder(dividingBy: 6) }
        if hi == g { return 60 * ((b - r) / d + 2) }
        return 60 * ((r - g) / d + 4)
    }

    private static func sameHue(_ a: String, _ b: String) -> Bool {
        let diff = abs(hue(a) - hue(b))
        return min(diff, 360 - diff) < 30
    }

    /// Çeviride renkli öbeklerin karşılığını işaretler. Öbeğin tek başına çevirisi ("plane" → "uçak") cümlede aranır;
    /// Türkçe ekler ve ünsüz yumuşaması için sözcük başının eşleşmesi yeter ("uçak" ~ "uçağa", "tekne" ~ "teknenin",
    /// "Brian" ~ "Brian'ı"). Bulunamayan öbek renksiz kalır. `parts`: (öbeğin çevirisi, renk).
    public static func mark(_ translation: String, parts: [(String, String)]) -> String {
        let tokens = translation.splitKeepingEmpty(" ")
        var colors = [String?](repeating: nil, count: tokens.count)
        for (translated, color) in parts {
            let wanted = translated.split(separator: " ").map { core(String($0)) }.filter { !$0.isEmpty }
            if wanted.isEmpty { continue }
            var i = 0
            while i + wanted.count <= tokens.count {
                if wanted.indices.contains(where: { k in colors[i + k] != nil || !matches(core(tokens[i + k]), wanted[k]) }) {
                    i += 1
                    continue
                }
                for k in wanted.indices { colors[i + k] = color }
                break
            }
        }
        var result = ""
        var i = 0
        while i < tokens.count {
            if i > 0 { result += " " }
            guard let color = colors[i] else {
                result += tokens[i]
                i += 1
                continue
            }
            // Aynı renkteki ardışık sözcükler tek parça olur.
            var end = i
            while end + 1 < tokens.count && colors[end + 1] == color { end += 1 }
            result += String(open) + color + String(divider) + tokens[i...end].joined(separator: " ") + String(close)
            i = end + 1
        }
        return result
    }

    /// İşaretli metnin düz hâli.
    public static func plain(_ text: String) -> String {
        text.contains(open) ? Patterns.replace(marked, text, "$1") : text
    }

    private static func core(_ token: String) -> String {
        String(token.lowercased(with: turkish).filter(\.isLetterOrDigit))
    }

    private static func matches(_ tokenText: String, _ wantedText: String) -> Bool {
        if tokenText.isEmpty { return false }
        if tokenText == wantedText { return true }
        let token = Array(tokenText), wanted = Array(wantedText)
        if wanted.count <= 3 { return tokenText.hasPrefix(wantedText) }
        var common = 0
        while common < token.count && common < wanted.count && token[common] == wanted[common] { common += 1 }
        return common >= max(3, wanted.count - 2)
    }
}

/// Küçük yazıda yazı tanıma "m" harfini "rn" / "nn" / "rri" okuyor ("frorn your noailbox", "longarrn"). Sözlükte
/// olmayan sözcükte bu kalıplar "m" yapılır; sonuç sözlükteyse düzeltme kabul edilir (Windows: LetterM).
public enum LetterM {
    private static let misreads: [[Character]] = ["rn", "nn", "rri", "no"].map(Array.init)

    public static func fix(_ text: String, isWord: ((String) -> Bool)?) -> String {
        guard let isWord, !text.isEmpty else { return text }
        var tokens = text.splitKeepingEmpty(" ")
        var changed = false
        for i in tokens.indices {
            let token = Array(tokens[i])
            var start = 0, end = token.count
            while start < end && !token[start].isLetter { start += 1 }
            while end > start && !token[end - 1].isLetter { end -= 1 }
            if end - start < 3 { continue }
            let word = String(token[start..<end])
            let lower = word.lowercased()
            let lowerChars = Array(lower)
            if isWord(lower) || !misreads.contains(where: { lowerChars.firstOffset(of: $0) >= 0 }) { continue }
            guard let repaired = fixed(lowerChars, isWord) else { continue }
            var cased = word.first!.isUppercase ? repaired.prefix(1).uppercased() + repaired.dropFirst() : repaired
            if word.allSatisfy({ !$0.isLetter || $0.isUppercase }) { cased = repaired.uppercased() }
            tokens[i] = String(token[0..<start]) + cased + String(token[end...])
            changed = true
        }
        return changed ? tokens.joined(separator: " ") : text
    }

    /// Kalıpların bir ya da iki yerde "m" yapılmış hâllerinden sözlükte olan ilki.
    private static func fixed(_ word: [Character], _ isWord: (String) -> Bool) -> String? {
        var candidates: [[Character]] = []
        func addAll(_ from: [Character]) {
            for misread in misreads {
                var at = from.firstOffset(of: misread)
                while at >= 0 {
                    candidates.append(Array(from[0..<at]) + ["m"] + Array(from[(at + misread.count)...]))
                    at = from.firstOffset(of: misread, from: at + 1)
                }
            }
        }
        addAll(word)
        for once in candidates { addAll(once) }
        return candidates.lazy.map { String($0) }.first(where: isWord)
    }
}
