// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Yazının yeri, içinde bulunduğu alanın kenarlarına oranla (0–1, üstten ve soldan).
public struct TextBox: Hashable, Sendable {
    public var left: Double
    public var top: Double
    public var right: Double
    public var bottom: Double

    public init(left: Double, top: Double, right: Double, bottom: Double) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public func union(_ other: TextBox) -> TextBox {
        TextBox(left: min(left, other.left), top: min(top, other.top), right: max(right, other.right), bottom: max(bottom, other.bottom))
    }

    /// Bu kutu `area` içindeki oranlarsa, aynı yerin `area`'yı içeren çerçevedeki oranları.
    public func placed(in area: TextBox) -> TextBox {
        let width = area.right - area.left, height = area.bottom - area.top
        return TextBox(left: area.left + left * width, top: area.top + top * height,
                       right: area.left + right * width, bottom: area.top + bottom * height)
    }

    /// Ekranın ortasındaki altyazının kutusu ortaya göre simetrik genişletilir. Açık zeminde ya da renkli yazıda
    /// yazı tanıma satırın bir ucunu okuyamayabilir; kutu yalnız okunan parçayı örterse İngilizcenin geri kalanı
    /// yanda görünür ve çeviri ortalanmamış durur. Ortadan uzak (köşedeki) yazıya dokunulmaz (Windows: TextBox.CenteredOnScreen).
    public func centeredOnScreen() -> TextBox {
        let middle = (left + right) / 2
        guard middle > 0.3, middle < 0.7 else { return self }
        return TextBox(left: max(0, min(left, 1 - right)), top: top, right: min(1, max(right, 1 - left)), bottom: bottom)
    }
}

/// Yazı tanımanın bulduğu bir satır.
public struct TextLine: Hashable, Sendable {
    public var text: String
    /// Okunan alana göre yeri; bilinmiyorsa nil.
    public var box: TextBox?

    public init(_ text: String, box: TextBox? = nil) {
        self.text = text
        self.box = box
    }
}

/// Bir karenin okunan satırları, yukarıdan aşağı.
public struct Reading: Equatable, Sendable {
    public var lines: [TextLine]

    public init(lines: [TextLine]) {
        self.lines = lines.filter { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    public static let empty = Reading(lines: [])

    /// Satırları ayrı ayrı bilinmeyen eski okuyucular için: her satır sonu bir satırdır.
    public init(text: String) {
        self.init(lines: text.components(separatedBy: .newlines).map { TextLine($0.trimmingCharacters(in: .whitespaces)) })
    }

    public var text: String { lines.map(\.text).joined(separator: "\n") }

    /// Bütün satırları içine alan kutu; hiçbir satırın yeri bilinmiyorsa nil.
    public var box: TextBox? {
        lines.compactMap(\.box).reduce(nil) { total, box in total?.union(box) ?? box }
    }

    /// Oyunun düğme ipuçları ("R2 Swing", "△ Pick up") çıkarılmış hali; bunlar altyazı değildir.
    public func withoutButtonPrompts() -> Reading {
        Reading(lines: lines.filter { !ButtonPrompts.isPrompt($0.text) })
    }

    /// Yazı tanımanın uydurduğu sözcük çöpleri ("ou$3e", "waters2aljd") ayıklanmış hali; çevirmen bunları olduğu
    /// gibi taşıyıp TV'de anlamsız harfler gösteriyordu.
    public func withoutOcrNoise() -> Reading {
        Reading(lines: lines.map { TextLine(OcrNoise.clean($0.text), box: $0.box) })
    }

    /// Alandaki altyazı bloğu. Seçilen alanda altyazının yanında menü ("MAP / HELP / SETTINGS" alt alta), köşedeki
    /// gösterge ("9", "PHOTO MODE") da okunuyordu; hepsi tek cümle gibi çevrilip TV'de bütün alanı kaplayan kutuyla
    /// gösteriliyordu. Satırlar yerlerine göre bloklara ayrılır; ortalanmış ve menü olmayan en alttaki blok altyazıdır.
    /// Ortalanmış blok yoksa en az üç sözcüklü, menü olmayan en alttaki blok alınır. Satırların yeri bilinmiyorsa
    /// okuma olduğu gibi kalır. `area`, okunan alanın karedeki yeridir (ortalanmışlık karenin ortasına göre ölçülür).
    public func subtitleBlock(in area: TextBox) -> Reading {
        guard !lines.isEmpty, lines.allSatisfy({ $0.box != nil }) else { return self }
        var blocks: [[TextLine]] = []
        for line in lines.sorted(by: { $0.box!.top < $1.box!.top }) {
            let box = line.box!.placed(in: area)
            if let index = blocks.lastIndex(where: { Self.belongs(box, to: $0, in: area) }) {
                blocks[index].append(line)
            } else {
                blocks.append([line])
            }
        }
        let candidates = blocks.map { Reading(lines: $0) }.filter { !$0.isMenu }
        let centered = candidates.filter { reading in
            guard let box = reading.box?.placed(in: area) else { return false }
            return abs((box.left + box.right) / 2 - 0.5) <= 0.12
        }
        if let block = centered.max(by: { $0.box!.bottom < $1.box!.bottom }) { return block }
        return candidates.filter { $0.text.split(whereSeparator: \.isWhitespace).count >= 3 }
            .max(by: { $0.box!.bottom < $1.box!.bottom }) ?? .empty
    }

    /// Bloğun son satırının hemen altında (arada bir satır yüksekliğinden az) ve yatayda onunla üst üste binen satır
    /// o bloğa aittir.
    private static func belongs(_ box: TextBox, to block: [TextLine], in area: TextBox) -> Bool {
        let last = block.last!.box!.placed(in: area)
        let height = max(box.bottom - box.top, last.bottom - last.top)
        guard box.top - last.bottom < height * 0.8, box.top >= last.top else { return false }
        let overlap = min(box.right, last.right) - max(box.left, last.left)
        let narrower = min(box.right - box.left, last.right - last.left)
        return overlap > 0 && overlap >= narrower * 0.5
    }

    /// Alt alta kısa ve noktalamasız satırlar ("MAP", "HELP", "SOCIAL CLUB") menüdür, konuşma değil.
    var isMenu: Bool {
        lines.count >= 3 && lines.allSatisfy { line in
            line.text.split(whereSeparator: \.isWhitespace).count <= 3 && !SubtitleStabilizer.endsSentence(line.text)
        }
    }

    /// Satır kutularını `area` içindeki oranlardan dış çerçevenin oranlarına taşır.
    public func placed(in area: TextBox) -> Reading {
        Reading(lines: lines.map { TextLine($0.text, box: $0.box?.placed(in: area)) })
    }
}

/// Oyunların ekranda gösterdiği kumanda ipuçlarını tanır: "R2 Swing", "L1 + R1 Spartan Rage",
/// "△ Pick up", "Press ✕ to jump". Konuşma satırları dokunulmadan kalır.
public enum ButtonPrompts {
    /// Her zaman kumanda düğmesi olan simgeler ve adlar (yazı tanıma bazen köşeli parantezle okur).
    private static let buttons: Set<String> = [
        "R1", "R2", "R3", "L1", "L2", "L3", "OPTIONS", "TOUCHPAD",
        "✕", "×", "⨯", "✖", "○", "◯", "●", "□", "▢", "◻", "■", "△", "▲", "⨂",
        // Xbox: yüz tuşları, omuz / tetik / çubuk tuşları, View ve Menu.
        "Ⓐ", "Ⓑ", "Ⓧ", "Ⓨ", "LB", "RB", "LT", "RT", "LS", "RS", "⧉", "☰",
    ]
    /// Simge yazı tanımada harfe dönüşebilir; yalnız ipucu kalıbında düğme sayılır.
    private static let lookalikes: Set<String> = ["X", "O"]
    private static let verbs: Set<String> = ["press", "hold", "tap", "push", "use", "mash", "release"]
    private static let brackets = CharacterSet(charactersIn: "()[]{}<>「」")

    /// Görüntüden tanınan simgeler (Windows: ButtonIcons): yön tuşu (iki ailede de ↑ ↓ ← → ✜), dokunmatik yüzey, harita işareti.
    private static let iconGlyphs: Set<String> = ["↑", "↓", "←", "→", "✜", "▦", "◆"]

    /// Sözcük bir düğme simgesi ya da adı mı ("✕", "R2", "Ⓑ"); harfe dönüşmüş "O" / "X" de sayılır (Windows: ButtonPrompts.IsIcon).
    public static func isIcon(_ token: String) -> Bool {
        let t = token.trimmingCharacters(in: brackets)
        if t.unicodeScalars.count == 1, let scalar = t.unicodeScalars.first, ("Ⓐ"..."Ⓩ").contains(scalar) { return true }
        return buttons.contains(t.uppercased()) || iconGlyphs.contains(t) || lookalikes.contains(t)
    }

    /// Düğme adının yanlış okunmuş hali ("R1" → "RI" / "Rl"); yalnız satır başında ve cümle gibi bitmeyen kısa satırda.
    private static let misreadButtons: Set<String> = ["RI", "LI", "Rl", "Ll", "RZ", "LZ"]
    private static let labels = Patterns.make("\\((?:[A-Z]{1,3}|[A-Z]{2,4})\\)")
    private static let decimal = Patterns.make("(?<![\\w.])\\d+\\.\\d(?![\\w.])")

    private static func isButton(_ token: String) -> Bool {
        buttons.contains(token.uppercased()) || iconGlyphs.contains(token)
    }

    /// Oyun arayüzü satırı: iki ya da daha çok düğme etiketi taşıyan çubuk ya da ondalıklı sayı taşıyan kısa gösterge.
    private static func isInterfaceBar(_ line: String) -> Bool {
        if SubtitleStabilizer.endsSentence(line) { return false }
        let count = Patterns.count(labels, line)
        let tokens = line.split(separator: " ").map(String.init)
        if count >= 2 || count + tokens.filter({ lookalikes.contains($0) }).count >= 3 { return true }
        let words = tokens.filter { $0.filter(\.isLetter).count >= 2 }.count
        return words <= 6 && Patterns.matches(decimal, line)
    }

    /// Satır bir kumanda ipucu mu (Windows: ButtonPrompts.IsPrompt, Android: ButtonPrompts.isPrompt; aynı kurallar).
    public static func isPrompt(_ line: String) -> Bool {
        let tokens = line
            .components(separatedBy: CharacterSet(charactersIn: " \t+/"))
            .map { $0.trimmingCharacters(in: brackets) }
            .filter { !$0.isEmpty }
        guard let first = tokens.first, let last = tokens.last else { return false }
        if isInterfaceBar(line) { return true }
        let ends = SubtitleStabilizer.endsSentence(line)
        // Tuş simgesi noktalama okunmuş etkileşim yazısı (":Read message"): simge, ardından cümle gibi bitmeyen kısa eylem.
        let trimmedLine = line.trimStart(" \t\n\r")
        if trimmedLine.count > 1, let lead = trimmedLine.first, ":;•©®".contains(lead) {
            let rest = trimmedLine.trimStart(":;•©® ")
            if let head = rest.first, head.isUppercase, tokens.count <= 5, !ends { return true }
        }
        let words = tokens.filter { !isButton($0) && $0.contains(where: \.isLetter) }.count
        // "R2 Swing", "△ Pick up", "L1 R1 Spartan Rage"
        if isButton(first) { return words <= 5 }
        if misreadButtons.contains(first), tokens.count >= 2, words <= 6, !ends { return true }
        // "PHOTO MODE L1 + R1": düğme sonda, önünde kısa ve noktalamasız bir eylem adı.
        if isButton(last), words <= 4, !ends { return true }
        // Ekranın altındaki ipucu çubuğu: düğmeyle başlar, içinde en az bir düğme daha vardır, cümle gibi bitmez.
        if lookalikes.contains(first), (4...16).contains(tokens.count), !ends,
           tokens.dropFirst().contains(where: { lookalikes.contains($0) || isButton($0) }),
           tokens.filter({ $0.first!.isUppercase }).count * 3 >= tokens.count * 2 {
            return true
        }
        // "X Interact", "O Back": ardından kısa, büyük harfle başlayan, noktalamasız bir eylem gelir.
        if lookalikes.contains(first), (2...4).contains(tokens.count), tokens[1].first!.isUppercase, !ends { return true }
        // "Press ✕ to jump", "Hold R2 to aim"; düğme simgesi harf olmayan bir işarete dönüşebilir ("PRESS @ TO CONTINUE").
        if verbs.contains(first.lowercased()), (2...10).contains(tokens.count) {
            let second = tokens[1]
            return isButton(second) || lookalikes.contains(second) || misreadButtons.contains(second) ||
                (second.count <= 2 && !second.contains(where: \.isLetterOrDigit)) ||
                (tokens.count >= 4 && second.count <= 2 && tokens[2].lowercased() == "to")
        }
        return false
    }
}
