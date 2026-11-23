// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Yazı tanımanın verdiği bir sözcük ve görüntüdeki yeri (0–1, üstten).
public struct OcrWord: Hashable, Sendable {
    public var text: String
    public var box: TextBox

    public init(_ text: String, box: TextBox) {
        self.text = text
        self.box = box
    }
}

/// Yazı tanımanın verdiği bir satır, yeri ve sözcükleri (yerleriyle; simge ve renk tanıma için). iOS'ta Apple Vision'ın
/// VNRecognizedTextObservation'ından kurulur (kutular üstten ölçülür: Vision'ın alttan ölçen y'si çevrilmelidir).
public struct OcrLine: Hashable, Sendable {
    public var text: String
    public var box: TextBox?
    public var words: [OcrWord]

    public init(_ text: String, box: TextBox?, words: [OcrWord] = []) {
        self.text = text
        self.box = box
        self.words = words
    }
}

/// Oyunlar düğmeyi yazının ortasına küçük bir resim olarak koyar ("Hold ⓧ to lock on"). Yazı tanıma o resmi ya hiç
/// okumaz (sözcükler arasında simge genişliğinde boşluk kalır) ya da "•", "(x)", "L", "O" gibi tek bir karakter sanır.
/// İkisinde de o parça görüntüden tanınıp (`ButtonIcons`) doğru düğmeyle değiştirilir ("Hold □ to lock on").
/// Windows: IconWords.cs, Android: IconWords.kt (Nino Game Translator'ın AGPL-3.0 VisionReader.withIcons / withEdgeIcons
/// uyarlaması).
public enum IconWords {
    private static let verbs: Set<String> = ["press", "hold", "tap", "push", "use", "release", "mash"]

    private static func upper(_ text: String) -> String { text.uppercased() }
    private static func isVerb(_ text: String) -> Bool { verbs.contains(text.lowercased()) }

    /// Satırın sözcüklerinden simgeli metni kurar. `classify` görüntüdeki bir yerdeki simgeyi tanır (tanınamazsa nil).
    /// `width` / `height`: görüntünün piksel boyutu (boşluk satır yüksekliğiyle piksel cinsinden karşılaştırılır).
    /// Değişiklik yoksa `text` aynen döner.
    public static func withIcons(_ text: String, words: [OcrWord], width: Double, height: Double, classify: (TextBox) -> String?) -> String {
        if words.isEmpty || (words.count == 1 && !mayBeIcon(words[0].text)) { return text }
        let top = words.map(\.box.top).min()!
        let bottom = words.map(\.box.bottom).max()!
        let lineHeight = (bottom - top) * height
        // Büyük harfli eylem satırı ("SET DESTINATION e"): son sözcük dışında her şey büyük harf; sondaki tek harf (küçük
        // de olsa) simgenin yanlış okunmuş hâlidir (GTA 6'nın tuş çubuğunda ✕ "e" okunuyor). Tek harfli parçalar
        // (yanlış okunmuş simgeler: "OFFER RIDE e LUCIA") büyük harf denetimine katılmaz.
        let head = words.prefix(max(1, words.count - 1)).map(\.text).filter { $0.count > 1 }.joined(separator: " ")
        let shouting = head.contains(where: \.isLetter) && head == upper(head)
        var output: [String] = []
        var changed = false
        for i in words.indices {
            let item = words[i]
            if i > 0 {
                let left = words[i - 1].box
                let gap = (item.box.left - left.right) * width
                if gap > lineHeight * 0.9 && buttonContext(output.last, item.text, shouting) {
                    // Boşlukta simge bulunamazsa hiçbir şey eklenmez.
                    if let found = classify(TextBox(left: left.right, top: top, right: item.box.left, bottom: bottom)) {
                        output.append(found)
                        changed = true
                    }
                }
            }
            // Büyük harfli eylem satırının sonundaki tek harf ("MOUNT HORSE A") simgedir (△ "A" okunuyor).
            let trailingLetter = i == words.count - 1 && i > 0 && shouting &&
                (item.text.count == 1 || ["11", "12", "13", "21", "22", "23"].contains(item.text))
            // "Hold A to …": fiilden sonraki tek büyük harf de simge olabilir.
            let afterVerb = i > 0 && item.text.count == 1 && item.text == upper(item.text) && isVerb(words[i - 1].text)
            // Büyük harfli satırın içindeki tek küçük harf de simgedir ("OFFER RIDE △ e LUCIA": ✕ "e" okunuyor).
            let others = words.enumerated().filter { $0.offset != i }.map(\.element.text).joined(separator: " ")
            let lowerInCaps = item.text.count == 1 && item.text.first!.isLowercase && others.contains(where: \.isLetter) && others == upper(others)
            let suspicious = mayBeIcon(item.text) || trailingLetter || afterVerb || lowerInCaps
            let context = suspicious && buttonContext(output.last, i + 1 < words.count ? words[i + 1].text : nil, shouting)
            let icon = context ? classify(TextBox(left: item.box.left, top: top, right: item.box.right, bottom: bottom)) : nil
            if let icon {
                output.append(icon)
                changed = true
            } else if suspicious && !["+", "/", "&"].contains(item.text) &&
                        (!item.text.contains(where: \.isLetterOrDigit) ||
                         // Cümlenin ortasında tek başına duran büyük harf ("Talk to O Brian"): tuşun yeri değilse bozuk okumadır.
                         (!context && !shouting && item.text.count == 1 && item.text.first!.isUppercase && item.text != "A" && item.text != "I")) {
                // Tanınmayan resim ("*", "•", "+"): düğme değildir, atılır.
                changed = true
            } else {
                output.append(item.text)
            }
        }
        return changed ? normalizedIcons(output.joined(separator: " ")) : text
    }

    /// Tuş bu yerde olabilir mi: fiilden sonra ("Press □", "Hold L2"), "or / and / +" ile ("□ or ○"), "to"dan önce
    /// ("□ to aim") ya da büyük harfli tuş çubuğunda ("SELECT ✕"). Sıradan cümlede adın ardındaki simge haritadaki
    /// işarettir ("your personal vehicle ◆ for easy access"; GTA 6'nın ipucu kutusu) ve satıra eklenmez.
    private static func buttonContext(_ previous: String?, _ next: String?, _ shouting: Bool) -> Bool {
        if shouting { return true }
        func word(_ text: String?) -> String { (text ?? "").trimBoth(".,!?:;()").lowercased() }
        let before = word(previous), after = word(next)
        // Yanındaki simge yalnız gerçek simgeyse sayılır: yazı tanımanın okuduğu "O" / "X" sayılsaydı bozuk parçalar
        // birbirini onaylıyordu ("Talk to □ ○ Brian": renkli adın önündeki iki bozuk parça).
        func glyph(_ token: String?) -> Bool {
            guard let token, ButtonPrompts.isIcon(token) else { return false }
            let bare = token.trimBoth("()[]")
            return bare != "O" && bare != "X"
        }
        return previous == nil || verbs.contains(before) || ["or", "and", "+", "/"].contains(before) || glyph(previous) ||
            // "or / and" sonrası tek başına yetmez: "your stash ◆ or by depositing" harita işaretidir. Sıradan cümlenin
            // sonu tek başına yetmez ("Follow ◆": renkli adın bozuk okuması); tuş çubuğunu "shouting" kapsar.
            ["to", "+", "/"].contains(after) || glyph(next)
    }

    /// Satırın hemen solunda ve sağında simge genişliğinde bir alan görüntüden tanınır; simge varsa satıra eklenir
    /// (görev ve ipucu satırlarında düğme çoğu zaman satırın başındadır: "□ Pick up").
    public static func withEdgeIcons(_ text: String, line: TextBox, width: Double, height: Double, classifyStrict: (TextBox) -> String?) -> String {
        let lineHeight = (line.bottom - line.top) * height
        if lineHeight <= 4 || text.isEmpty { return text }
        let span = lineHeight * 1.8 / width
        let tokens = text.split(separator: " ").map(String.init)
        var result = text
        if let first = tokens.first, !ButtonPrompts.isIcon(first), line.left - span > 0,
           let before = classifyStrict(TextBox(left: line.left - span, top: line.top, right: line.left - span * 0.1, bottom: line.bottom)) {
            result = "\(before) \(result)"
        }
        // Sağ kenarda tuş yalnız büyük harfli tuş çubuğunda ("SELECT ✕") ya da fiilden sonra ("Press ✕") aranır: sıradan
        // cümlenin ardındaki renkli ad (GTA 6: "Find" + pembe "Donnie", ad okunmamış) pembe □ sanılıyordu.
        let letters = tokens.filter { $0.count > 1 }.joined()
        let bar = letters.contains(where: \.isLetter) && letters == upper(letters)
        // Büyük rozet (GTA 6 Xbox'ta büyük harfli adın yanındaki "LT" dairesi satırın ~1,8 katı, Starfield'ın "TALK  Ⓐ"
        // dairesi ~2,5 katı ve biraz açıkta) dar alana sığmıyor: bulunamazsa üç kat yüksek, daha geniş alana bakılır.
        if let last = tokens.last, !ButtonPrompts.isIcon(last), line.right + span < 1, bar || isVerb(last.trimBoth(".,:")) {
            let grow = line.bottom - line.top
            var after = classifyStrict(TextBox(left: line.right + span * 0.1, top: line.top, right: line.right + span, bottom: line.bottom))
            if after == nil && line.right + span * 1.6 < 1 {
                after = classifyStrict(TextBox(left: line.right + span * 0.1, top: line.top - grow, right: line.right + span * 1.6, bottom: line.bottom + grow))
            }
            if after == nil && line.right + span * 2.2 < 1 {
                after = classifyStrict(TextBox(left: line.right + span * 0.5, top: line.top - grow, right: line.right + span * 2.2, bottom: line.bottom + grow))
            }
            if let after { result = "\(result) \(after)" }
        }
        return result == text ? text : normalizedIcons(result)
    }

    private static let shoulders: Set<String> = ["L1", "L2", "L3", "R1", "R2", "R3"]
    private static let misreadShoulder = Patterns.make("^[\\(\\[]?[LRC][0-9DIlZ|][\\)\\]]?$")

    /// Simgenin yanlış okunmuş hali olabilecek parça: harfsiz kısa parça ("•", "(x)", "&amp;") ya da tek büyük harf
    /// ("I" ve "A" gerçek sözcük olabildiği için hariç).
    public static func mayBeIcon(_ word: String) -> Bool {
        // Yazıdaki bağlaç ve noktalama işaretleri simge değildir ("Chuck's Tackle & Shot": "&" ✕ sanılıyordu).
        if ["&", "+", "/", "-", "–", "—", "|"].contains(word) { return false }
        let core = word.trimBoth("()[]{}.,")
        // L1/R2 simgesinin yanlış okunmuş hali: "LD", "(C1", "Ll", "RZ".
        if Patterns.matches(misreadShoulder, word) { return true }
        if word.count <= 3 && word.hasPrefix("(") && word.hasSuffix(")") { return true }
        if core.isEmpty { return word.count <= 3 }
        if core.count <= 2 && !core.contains(where: \.isLetterOrDigit) { return true }
        if core.count == 1, let c = core.first, c.isUppercase, c != "I", c != "A" { return true }
        return ButtonPrompts.isIcon(core) && core.count <= 2 && !shoulders.contains(core) && !ButtonTokens.xboxNames.contains(core)
    }

    // NSRegularExpression (ICU) desenleri: `\w` yerine `[\p{L}\p{N}_]`; Android ICU motorunun tanımadığı Unicode sınıf bayrağı (soru işareti ve U) kullanılmaz.
    private static let normalizers: [(NSRegularExpression, String)] = [
        ("(?<![\\p{L}\\p{N}_])[\\(\\[]?[1lI|]([123])[\\)\\]](?=\\s|$)", "L$1"),
        ("(?<![\\p{L}\\p{N}_])[\\(\\[][1lI|]([123])(?=\\s|$)", "L$1"),
        ("(?<![\\p{L}\\p{N}_])[\\(\\[]?([LR])([123])[\\)\\]](?=\\s|$)", "$1$2"),
        ("(?<![\\p{L}\\p{N}_])[\\(\\[]([LR])([123])(?=\\s|$)", "$1$2"),
        ("(?<![\\p{L}\\p{N}_])[\\(\\[]C([12])[\\)\\]]?(?=\\s|$)", "L$1"),
        // Xbox'ın omuz ve tetik tuşları köşeli parantezle okunabilir ("[LT]", "(RB)").
        ("(?<![\\p{L}\\p{N}_])[\\(\\[]([LR][BTS])[\\)\\]](?=\\s|$|[.,:])", "$1"),
        ("([✕○△□↑↓←→✜▦◆●Ⓐ-Ⓩ⧉☰])(?:\\s+\\1)+", "$1"),
        ("(?<![\\p{L}\\p{N}_])([LR][123]|[LR][BTS]|OPTIONS|TOUCHPAD)(?:\\s+\\1)+(?![\\p{L}\\p{N}_])", "$1"),
    ].map { (Patterns.make($0.0), $0.1) }

    /// Düğme adlarının yanlış okunmuş hallerini düzeltir ("(l2)" → "L2", "(C1" → "L1") ve yan yana yinelenen simgeyi
    /// teke indirir ("Hold ✕ ✕ to …": simge hem yazı içinden hem kenardan bulunabiliyor).
    public static func normalizedIcons(_ line: String) -> String {
        normalizers.reduce(line) { text, item in Patterns.replace(item.0, text, item.1) }
    }

    /// Sözcük tek başına bir Xbox yüz tuşu harfi mi ("A", "(B)", "[Y].", "X,"): harf, parantezli mi ve sondaki noktalama.
    private static func xboxLetter(_ token: String) -> (letter: Character, bracketed: Bool, suffix: String)? {
        var core = token
        var suffix = ""
        if let last = core.last, ".,:".contains(last) {
            suffix = String(last)
            core.removeLast()
        }
        let chars = Array(core)
        let faces: Set<Character> = ["A", "B", "X", "Y"]
        if chars.count == 1, faces.contains(chars[0]) { return (chars[0], false, suffix) }
        if chars.count == 3, "([{<".contains(chars[0]), ")]}>".contains(chars[2]), faces.contains(chars[1]) { return (chars[1], true, suffix) }
        return nil
    }

    /// Xbox oyununda yazı tanımanın harf olarak okuduğu yüz tuşları işarete çevrilir: "Press A to jump" → "Press Ⓐ to
    /// jump", "(B) Back" → "Ⓑ Back", "Hold [X]" → "Hold Ⓧ". Yalnız tuşun yerinde: fiilden sonra ve ardından "to / or /
    /// and" ya da satır sonu geliyorsa, parantezli harf tuş bağlamındaysa ya da satır harf ve bilinen bir komuttan
    /// ibaretse ("A Talk", "TALK A"). Cümledeki "A" tanımlığı ("Use A Medkit", "A new day") değişmez. PlayStation'da
    /// metne dokunulmaz (Windows: IconWords.WithXboxLetters, Android: IconWords.withXboxLetters).
    public static func withXboxLetters(_ line: String, family: ButtonFamily) -> String {
        if family != .xbox || line.isEmpty { return line }
        var tokens = line.split(separator: " ").map(String.init)
        let letters = tokens.filter { $0.count > 1 }.joined()
        let shouting = letters.contains(where: \.isLetter) && letters == upper(letters)
        var changed = false
        func word(_ text: String?) -> String { (text ?? "").trimBoth(".,!?:;").lowercased() }
        let joiners: Set<String> = ["or", "and", "+", "/"]
        for i in tokens.indices {
            guard let match = xboxLetter(tokens[i]), let face = ButtonTokens.xboxFace(match.letter) else { continue }
            let previous = i > 0 ? tokens[i - 1] : nil
            let next = i + 1 < tokens.count ? tokens[i + 1] : nil
            let afterVerb = isVerb(word(previous)) &&
                (next == nil || joiners.contains(word(next)) || word(next) == "to" || ButtonPrompts.isIcon(next!) || shouting)
            // "A Talk", "B Back", "TALK A": harf ve bir komut (UiPrompts sözlüğü).
            let rest = tokens.enumerated().filter { $0.offset != i }.map(\.element).joined(separator: " ").trimBoth(".,:")
            let command = (i == 0 || i == tokens.count - 1) && (2...4).contains(tokens.count) && UiPrompts.isCommand(rest)
            let chained = previous != nil && joiners.contains(word(previous)) && i >= 2 && isXboxGlyph(tokens[i - 2])
            if match.bracketed ? (buttonContext(previous, next, shouting) || command) : (afterVerb || command || chained) {
                tokens[i] = face + match.suffix
                changed = true
            }
        }
        // Parantezli omuz tuşu ("(LT)") her durumda düzeltilir.
        return normalizedIcons(changed ? tokens.joined(separator: " ") : line)
    }

    private static func isXboxGlyph(_ token: String) -> Bool { ButtonTokens.familyOf(token.trimEnd(".,:")) == .xbox }

    /// Yazı tanımanın düz okuduğu tuş adları da kararı besler: ipucu satırındaki "LT", "RB" Xbox'ı, "R2", "L1", "OPTIONS"
    /// PlayStation'ı gösterir. Yalnız tuş çubuğu ya da "Hold LT to aim" gibi ipucu satırına bakılır.
    public static func observe(_ line: String, vote: ButtonLayoutVote) {
        if vote.setting != .auto || !ButtonPrompts.isPrompt(line) { return }
        for raw in line.split(separator: " ") {
            switch String(raw).trimBoth("()[].,:") {
            case "LT", "RT", "LB", "RB", "LS", "RS": vote.add(.xbox)
            case "L1", "L2", "R1", "R2", "OPTIONS", "TOUCHPAD": vote.add(.playstation)
            default: break
            }
        }
    }
}
