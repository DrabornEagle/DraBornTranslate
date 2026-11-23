// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Türkçe büyük/küçük harf (I ↔ ı, İ ↔ i); her platformda aynı sonuç için yerel ayara bırakılmaz.
enum TurkishCase {
    static func lower(_ text: String) -> String {
        text.replacingOccurrences(of: "I", with: "ı").replacingOccurrences(of: "İ", with: "i").lowercased()
    }

    static func upper(_ text: String) -> String {
        text.replacingOccurrences(of: "i", with: "İ").replacingOccurrences(of: "ı", with: "I").uppercased()
    }

    /// İlk harfi büyütür (Türkçe kurala göre).
    static func capitalizeFirst(_ text: String) -> String {
        guard let first = text.first else { return text }
        return upper(String(first)) + text.dropFirst()
    }
}

/// Ücretsiz çevirinin terim sözlüğü (Windows: Glossary.cs, aynı biçim ve kurallar). Dosya biçimi:
/// `{ "game": "God of War", "terms": { "Leviathan Axe": "Leviathan Baltası" }, "keep": ["Kratos", "Atreus"] }`.
/// `terms` İngilizce terimi verilen Türkçeyle değiştirir, `keep` adları olduğu gibi bırakır. Eşleşme büyük/küçük harfe
/// bakmaz, yalnız tam sözcüklerde olur, uzun terim önce gelir ("Leviathan Axe", "Axe"dan önce). `keepExact` da adları
/// bırakır ama yalnız yazıldığı gibi geçince ("Soul" kahramanın adıyken "soul" ruh demek). Uygulamayla gelen genel
/// sözlüğün üstüne kullanıcının klasöründeki `*.json` dosyaları eklenir (ad sırasıyla; aynı terimde sonraki kazanır).
public final class Glossary: @unchecked Sendable {
    struct Term: Equatable {
        let english: String
        let turkish: String
        let exact: Bool
    }

    /// Metinde bulunan terim: konumu (Character sırası), sözlükteki İngilizcesi ve Türkçesi.
    public struct TermMatch: Equatable, Sendable {
        public let start: Int
        public let length: Int
        public let english: String
        public let turkish: String
    }

    /// Küçük harfli İngilizce → terim.
    private let terms: [String: Term]
    /// Uzundan kısaya terimler; arama bu sırayla yapılır.
    private let ordered: [Term]

    /// Büyük harfle kalması gereken sözcükler (küçük harfle); `SubtitleSegmenter.normalizeCase`'e ad olarak verilir.
    public let capitalWords: Set<String>
    /// Sözlüğün içeriğinden kısa bir iz; terim içeren satırların önbellek anahtarına girer, sözlük değişince yeniden çevrilir.
    public let fingerprint: String

    public static let empty = Glossary([:])

    init(_ terms: [String: Term]) {
        self.terms = terms
        ordered = terms.values.sorted {
            $0.english.utf16.count != $1.english.utf16.count ? $0.english.utf16.count > $1.english.utf16.count
                : Array($0.english.utf16).lexicographicallyPrecedes(Array($1.english.utf16))
        }
        var words = Set<String>()
        for term in ordered where !term.exact && term.english.contains(where: \.isUppercase) {
            // Sözlükte büyük harfle yazılmış terimler ad sayılır; keepExact adları sıradan sözcük de olduğundan sayılmaz.
            for word in term.english.split(separator: " ") where word.count >= 2 { words.insert(word.lowercased()) }
        }
        capitalWords = words
        fingerprint = Self.makeFingerprint(ordered)
    }

    public var count: Int { terms.count }
    public var isEmpty: Bool { terms.isEmpty }

    /// Uygulamayla gelen genel oyun terimleri (Resources/glossary-en-tr.json).
    public static let builtIn: Glossary = {
        guard let url = Bundle.module.url(forResource: "glossary-en-tr", withExtension: "json"),
              let data = try? Data(contentsOf: url), let glossary = try? parse(data) else { return .empty }
        return glossary
    }()

    /// `terms` İngilizce → Türkçe, `keep` olduğu gibi kalacak adlar, `keepExact` yalnız yazıldığı gibi geçince kalacak adlar.
    public static func of(_ terms: [String: String], keep: [String] = [], keepExact: [String] = []) -> Glossary {
        var all: [String: Term] = [:]
        for (english, turkish) in terms.sorted(by: { $0.key < $1.key }) { add(&all, english, turkish) }
        for name in keep { add(&all, name, name) }
        for name in keepExact { add(&all, name, name, exact: true) }
        return Glossary(all)
    }

    public struct FormatError: Error {}

    /// JSON sözlük dosyasını okur; biçim bozuksa hata atar.
    public static func parse(_ data: Data) throws -> Glossary {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw FormatError() }
        var all: [String: Term] = [:]
        if let map = root["terms"] as? [String: Any] {
            // JSON nesnesinin sırası korunmaz; aynı küçük harfli terim iki kez yazılmışsa sonuç anahtar sırasına göredir.
            for (english, value) in map.sorted(by: { $0.key < $1.key }) {
                if let turkish = value as? String { add(&all, english, turkish) }
            }
        }
        for name in root["keep"] as? [Any] ?? [] { if let name = name as? String { add(&all, name, name) } }
        for name in root["keepExact"] as? [Any] ?? [] { if let name = name as? String { add(&all, name, name, exact: true) } }
        return Glossary(all)
    }

    private static func add(_ terms: inout [String: Term], _ rawEnglish: String, _ rawTurkish: String, exact: Bool = false) {
        let english = collapse(rawEnglish)
        let turkish = collapse(rawTurkish)
        // Harfsiz terim her yerde eşleşirdi; yer tutucu sayısı sınırlı olduğundan çok uzun terim de alınmaz.
        guard english.contains(where: \.isLetter), !turkish.isEmpty, english.utf16.count <= 80 else { return }
        terms[english.lowercased()] = Term(english: english, turkish: turkish, exact: exact)
    }

    /// Sözlükleri birleştirir; aynı terimde sonraki kazanır.
    public static func merge(_ glossaries: [Glossary]) -> Glossary {
        var all: [String: Term] = [:]
        for glossary in glossaries { all.merge(glossary.terms) { _, new in new } }
        return Glossary(all)
    }

    /// Genel sözlük ve klasördeki `*.json` dosyaları; okunamayan ya da bozuk dosya atlanır.
    public static func load(directory: URL?) -> Glossary {
        var all = [builtIn]
        for file in userFiles(directory) {
            if let data = try? Data(contentsOf: file), let glossary = try? parse(data) { all.append(glossary) }
        }
        return merge(all)
    }

    static func userFiles(_ directory: URL?) -> [URL] {
        guard let directory, let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else { return [] }
        return names.filter { $0.hasSuffix(".json") }.sorted().map { directory.appendingPathComponent($0) }
    }

    /// Terimin Türkçesi; yoksa nil.
    public func turkish(_ english: String) -> String? { terms[Self.collapse(english).lowercased()]?.turkish }

    /// Metindeki terimler: büyük/küçük harfe bakmadan (keepExact adları bakarak), tam sözcük, uzun terim önce; çakışan kısa
    /// terim alınmaz. Sonuç metindeki sıraya göredir.
    public func find(_ text: String) -> [TermMatch] {
        guard !ordered.isEmpty, !text.isEmpty else { return [] }
        let characters = Array(text)
        let folded = characters.map { String($0).lowercased() }
        var found: [TermMatch] = []
        for term in ordered {
            let english = Array(term.english)
            let target = english.map { String($0).lowercased() }
            guard english.count <= characters.count else { continue }
            for index in 0...(characters.count - english.count) {
                var same = true
                for offset in 0..<english.count {
                    let equal = term.exact ? characters[index + offset] == english[offset] : folded[index + offset] == target[offset]
                    if !equal { same = false; break }
                }
                guard same else { continue }
                let end = index + english.count
                if Self.isBoundary(characters, index - 1) && Self.isBoundary(characters, end)
                    && !found.contains(where: { index < $0.start + $0.length && $0.start < end }) {
                    found.append(TermMatch(start: index, length: english.count, english: term.english, turkish: term.turkish))
                }
            }
        }
        return found.sorted { $0.start < $1.start }
    }

    public func matches(_ text: String) -> Bool { !find(text).isEmpty }

    private static func isBoundary(_ text: [Character], _ index: Int) -> Bool {
        index < 0 || index >= text.count || !(text[index].isLetter || text[index].isNumber)
    }

    static func collapse(_ text: String) -> String {
        text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    /// FNV-1a (32 bit), terimler İngilizcesine göre sıralı; Windows'ta da aynı hesap.
    private static func makeFingerprint(_ terms: [Term]) -> String {
        var hash: UInt32 = 2166136261
        let sorted = terms.sorted { Array($0.english.lowercased().utf16).lexicographicallyPrecedes(Array($1.english.lowercased().utf16)) }
        for term in sorted {
            // exact işareti yalnız varsa eklenir; eski sözlüklerin izi değişmez.
            for byte in (term.english.lowercased() + "\u{1}" + term.turkish + (term.exact ? "\u{3}" : "") + "\u{2}").utf8 {
                hash = (hash ^ UInt32(byte)) &* 16777619
            }
        }
        let hex = String(hash, radix: 16)
        return "g" + String(repeating: "0", count: 8 - hex.count) + hex
    }
}

/// Kullanıcı sözlük klasörünü izler: dosyalar değişince (ad, boyut, değişiklik zamanı) sözlük yeniden okunur. Klasöre en
/// çok birkaç saniyede bir bakılır; her altyazıda diske gidilmez (Windows: GlossaryStore).
public final class GlossaryStore: @unchecked Sendable {
    private static let checkEvery: TimeInterval = 5

    private let directory: URL?
    private let clock: () -> Date
    private let lock = NSLock()
    private var current: Glossary?
    private var stamp = ""
    private var checkedAt = Date.distantPast

    /// `directory`: kullanıcı sözlüklerinin klasörü; nil ise yalnız genel sözlük.
    public init(directory: URL?, clock: (() -> Date)? = nil) {
        self.directory = directory
        self.clock = clock ?? { Date() }
    }

    public var glossary: Glossary {
        lock.lock()
        defer { lock.unlock() }
        let now = clock()
        if let current, now.timeIntervalSince(checkedAt) < Self.checkEvery { return current }
        checkedAt = now
        let newStamp = makeStamp()
        if let current, newStamp == stamp { return current }
        let loaded = Glossary.load(directory: directory)
        current = loaded
        stamp = newStamp
        return loaded
    }

    private func makeStamp() -> String {
        var text = ""
        for file in Glossary.userFiles(directory) {
            guard let values = try? FileManager.default.attributesOfItem(atPath: file.path) else { continue }
            let size = (values[.size] as? NSNumber)?.int64Value ?? 0
            let modified = (values[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
            text += "\(file.path)|\(size)|\(modified)\n"
        }
        return text
    }
}

/// Sözlük teriminin Türkçe eki (Windows: TurkishSuffix.cs, aynı kurallar). Çeviri motoru terimin yerindeki yer tutucu
/// ada bir ek koyar ("Zarmon'a", "Zarmon'un"); ek, hangi hâl olduğu korunarak gerçek terime göre yeniden kurulur: ünlü
/// uyumu, kaynaştırma, ünsüz benzeşmesi; özel ad kesmeyle ayrılır, cins ad bitişik yazılır ve p/ç/k yumuşar; çok sözcüklü,
/// dar ünlüyle biten terim tamlama sayılır ("Leviathan Baltası'nı").
public enum TurkishSuffix {
    private static let vowels: Set<Character> = ["a", "e", "ı", "i", "o", "ö", "u", "ü"]
    private static let backVowels: Set<Character> = ["a", "ı", "o", "u"]
    private static let roundedVowels: Set<Character> = ["o", "u", "ö", "ü"]
    private static let highVowels: Set<Character> = ["ı", "i", "u", "ü"]
    private static let voiceless: Set<Character> = ["f", "s", "t", "k", "ç", "ş", "h", "p"]
    /// Daha uzun "ek" büyük olasılıkla ek değildir; dokunulmaz.
    private static let maxSuffixLength = 12

    static func isVowel(_ c: Character) -> Bool { vowels.contains(c) }

    /// `term` + ek. `suffix` motorun yer tutucuya koyduğu ektir (kesmesiz: "a", "un", "ya", "dan"); boşsa terim döner.
    public static func attach(_ term: String, _ suffix: String, apostrophe: Character = "'") -> String {
        guard !term.isEmpty, !suffix.isEmpty else { return term }
        var body = Array(TurkishCase.lower(suffix))
        if body.count > maxSuffixLength || !body.allSatisfy(\.isLetter) { return term + String(apostrophe) + suffix }

        let proper = term.first!.isUppercase
        let lastWord = Array(TurkishCase.lower(String(term.split(separator: " ", omittingEmptySubsequences: false).last ?? "")))
        let lastLetter = lastWord.last(where: \.isLetter)
        var lastVowel = lastWord.last(where: isVowel)
        // Ünlüsüz kısaltma ("XP", "HP") harf adlarıyla okunur: "iks-pe" → ince, ünlüyle biter.
        let endsWithVowel = lastVowel == nil || (lastLetter.map(isVowel) ?? false)
        if lastVowel == nil { lastVowel = "e" }
        let compound = term.contains(" ") && lastLetter.map { highVowels.contains($0) } == true && lastWord.count >= 3
        let isVoiceless = !endsWithVowel && lastLetter.map { voiceless.contains($0) } == true

        // Baştaki kaynaştırma harfi yer tutucuya göreydi; atılır.
        if body.count >= 2 && body[0] == "y" && (isVowel(body[1]) || body[1] == "l") {
            body.removeFirst()
        } else if body.count >= 3 && body[0] == "n" && highVowels.contains(body[1]) && body[2] == "n" {
            body.removeFirst()
        }

        let genitive = body.count >= 2 && highVowels.contains(body[0]) && body[1] == "n" && (body.count == 2 || !isVowel(body[2]))
        let instrumental = body.count >= 2 && body[0] == "l" && (body[1] == "a" || body[1] == "e") && !(body.count >= 3 && body[2] == "r")
        var buffer = ""
        if isVowel(body[0]) {
            if compound || (endsWithVowel && genitive) { buffer = "n" } else if endsWithVowel { buffer = "y" }
        } else if body[0] == "d" || body[0] == "t" {
            if compound { buffer = "n" }
            body[0] = compound || !isVoiceless ? "d" : "t"
        } else if instrumental && (endsWithVowel || compound) {
            buffer = "y"
        }

        let harmonized = harmonize(String(body), lastVowel: lastVowel!)
        if proper { return term + String(apostrophe) + buffer + harmonized }
        let stem = buffer.isEmpty && harmonized.first.map(isVowel) == true ? soften(term) : term
        return stem + buffer + harmonized
    }

    /// Ekteki a/e ve ı/i/u/ü ünlülerini önceki ünlüye uydurur; "-ki" (Midgard'daki) değişmez.
    static func harmonize(_ text: String, lastVowel: Character) -> String {
        let body = Array(text)
        var output = ""
        var previous = lastVowel
        for i in body.indices {
            var c = body[i]
            let invariantKi = c == "i" && i > 0 && body[i - 1] == "k" && i == body.count - 1
            if !invariantKi && (c == "a" || c == "e") {
                c = backVowels.contains(previous) ? "a" : "e"
            } else if !invariantKi && highVowels.contains(c) {
                let back = backVowels.contains(previous)
                let rounded = roundedVowels.contains(previous)
                c = back ? (rounded ? "u" : "ı") : (rounded ? "ü" : "i")
            }
            if isVowel(c) { previous = c }
            output.append(c)
        }
        return output
    }

    /// Cins adın son p/ç/k'si ünlüyle başlayan ekten önce yumuşar (kitap → kitabı, kılıç → kılıcı, bıçak → bıçağı,
    /// renk → rengi). Tek heceli sözcükler yumuşamaz (top → topu); t dokunulmaz.
    static func soften(_ term: String) -> String {
        guard term.count >= 2, let last = term.last else { return term }
        let lower = TurkishCase.lower(term)
        let word = lower.split(separator: " ", omittingEmptySubsequences: false).last.map(String.init) ?? lower
        let stem = String(term.dropLast())
        if word.hasSuffix("nk") { return stem + "g" }
        if word.filter(isVowel).count < 2 { return term }
        switch last {
        case "p": return stem + "b"
        case "ç": return stem + "c"
        case "k": return stem + "ğ"
        default: return term
        }
    }
}

/// Sözlük terimlerini çeviriden korur (Windows: TermProtection.cs, aynı kurallar). Terim, çeviri öncesinde uydurma bir
/// özel adla ("Zarmon", "Velkor") değiştirilir: çeviri motorları özel adı olduğu gibi bırakıp Türkçe ekini kesmeyle ekler
/// ("Zarmon'u al"). Çeviriden sonra ad bulunur, yerine Türkçe terim konur ve ek terime göre yeniden kurulur. Bir ad
/// çeviride yoksa sonuç güvenilmez sayılır (nil), çağıran korumasız çeviriye döner.
public final class TermProtector {
    /// Korunan terim: yer tutucusu ve yerine konacak Türkçe.
    public struct Slot: Equatable, Hashable, Sendable {
        public let placeholder: String
        public let english: String
        public let turkish: String
    }

    /// Yer tutucu adlar: Türkçe ve İngilizcede sözcük değil, birbirinin başı değil, i/I içermez, ünsüzle biter ve kalın ünlülüdür.
    static let placeholders = ["Zarmon", "Velkor", "Dorvan", "Kelmar", "Tolvar", "Brandur", "Halvor", "Feldor"]

    private let glossary: Glossary
    private var free: [String]
    private var byTerm: [String: Slot] = [:]
    private var slotOrder: [String] = []

    /// `sources`: kaynak metinler; içlerinde geçen yer tutucu ad kullanılmaz.
    public init(glossary: Glossary, sources: [String]) {
        self.glossary = glossary
        free = Self.placeholders.filter { placeholder in !sources.contains { $0.range(of: placeholder, options: .caseInsensitive) != nil } }
    }

    public convenience init(_ glossary: Glossary, _ sources: String...) {
        self.init(glossary: glossary, sources: sources)
    }

    /// Bütün yer tutucular (bağlamdakiler dahil); çıktıda beklenmeyen ad kalmasın diye.
    public var allSlots: [Slot] { slotOrder.compactMap { byTerm[$0] } }

    /// Terimleri yer tutucularla değiştirir; bu metinde kullanılan yer tutucular döner.
    public func protect(_ text: String) -> (text: String, slots: [Slot]) {
        let matches = glossary.find(text)
        guard !matches.isEmpty else { return (text, []) }
        let characters = Array(text)
        var output = ""
        var used: [Slot] = []
        var position = 0
        for match in matches {
            let key = match.english.lowercased()
            var slot = byTerm[key]
            if slot == nil {
                // Ad kalmadıysa geri kalan terimler korunmaz; motor onları kendi çevirir.
                guard !free.isEmpty else { continue }
                let created = Slot(placeholder: free.removeFirst(), english: match.english, turkish: match.turkish)
                byTerm[key] = created
                slotOrder.append(key)
                slot = created
            }
            output += String(characters[position..<match.start]) + slot!.placeholder
            position = match.start + match.length
            if !used.contains(slot!) { used.append(slot!) }
        }
        output += String(characters[position...])
        return (output, used)
    }

    /// Yer tutucuları Türkçe terimle değiştirir. `expected` çıktıda en az bir kez geçmeli, `all` içindeki diğer adlar hiç
    /// geçmemeli; yoksa nil.
    public static func restore(_ output: String, expected: [Slot], all: [Slot]? = nil, apostrophe: Character = "'") -> String? {
        if expected.isEmpty && (all?.isEmpty ?? true) { return output }
        for slot in all ?? [] where !expected.contains(slot) && pattern(slot.placeholder).firstMatch(in: output, range: whole(output)) != nil {
            return nil
        }
        var text = output
        for slot in expected {
            let regex = pattern(slot.placeholder)
            let current = text as NSString
            let found = regex.matches(in: text, range: whole(text))
            guard !found.isEmpty else { return nil }
            var result = ""
            var last = 0
            for match in found {
                result += current.substring(with: NSRange(location: last, length: match.range.location - last))
                let group1 = match.range(at: 1), group2 = match.range(at: 2)
                let suffix = group1.location != NSNotFound ? current.substring(with: group1)
                    : group2.location != NSNotFound ? current.substring(with: group2) : ""
                let term = TurkishSuffix.attach(slot.turkish, suffix, apostrophe: apostrophe)
                result += startsSentence(current, match.range.location) ? capitalize(term) : term
                last = match.range.location + match.range.length
            }
            result += current.substring(from: last)
            text = result
        }
        return text
    }

    private static func whole(_ text: String) -> NSRange { NSRange(location: 0, length: (text as NSString).length) }

    /// Yer tutucu ve ardındaki ek: kesmeyle ("Zarmon'a", "Zarmon 'a") ya da bitişik ("Zarmona").
    private static func pattern(_ placeholder: String) -> NSRegularExpression {
        try! NSRegularExpression(pattern: "(?<![\\p{L}\\p{N}])\(placeholder)(?:\\s?['’`´](\\p{L}+)|(\\p{L}+))?(?![\\p{L}\\p{N}])",
                                 options: [.caseInsensitive])
    }

    /// Metnin ya da cümlenin başı mı (önünde yalnız boşluk, tırnak, tire ya da cümle sonu var). `index`: UTF-16 konumu.
    private static func startsSentence(_ text: NSString, _ index: Int) -> Bool {
        let skip = Set("\"“'‘(-–—¡¿".utf16)
        let enders = Set(".!?…".utf16)
        var i = index - 1
        while i >= 0 {
            let unit = text.character(at: i)
            if let scalar = Unicode.Scalar(unit), scalar.properties.isWhitespace || skip.contains(unit) {
                i -= 1
                continue
            }
            if skip.contains(unit) {
                i -= 1
                continue
            }
            return enders.contains(unit)
        }
        return true
    }

    private static func capitalize(_ term: String) -> String {
        guard let first = term.first, first.isLowercase else { return term }
        return TurkishCase.capitalizeFirst(term)
    }
}
