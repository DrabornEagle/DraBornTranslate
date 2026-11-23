// SPDX-License-Identifier: AGPL-3.0-only
// CouchSubs'tan uyarlandı: CouchSubs (Copyright (C) 2026 Sinan Yorulmaz), AGPL-3.0, yazarının izniyle.

import Foundation

/// Paketteki satırın bulanık imzası: OCR'ın bozduğu satırı (harf hatası, birleşmiş sözcük) bulmak için. canon metnin
/// boşlukları atılır, OCR'ın karıştırdığı harfler birleştirilir (l/1/ı/j → i, 0 → o, 5 → s), kalan dizinin her farklı
/// üçlüsü FNV-1a 64 ile özetlenip m bitten birine düşer (Bloom kümesi; m uzunlukla büyür: 64/128/256). İngilizce metin
/// geri çıkarılamaz. Windows (PackFuzzy.cs), Android (PackFuzzy.kt) ve paket betiğiyle (augment.py, common.py) birebir
/// aynı; ortak deneme verisi fuzzy-vectors.json.
public enum PackFuzzy {
    /// Bundan kısa satırda bulanık arama yok.
    public static let minLength = 16
    /// Bloom kümesinin en büyük boyu (32 bayt).
    public static let maxBits = 256
    /// Üçlü başına bit.
    public static let bitsPerGram = 3
    /// Türkçesi farklı en yakın ikinci aday en az bu kadar geride olmalı.
    public static let margin = 5.0

    /// OCR izi olmayan satır neredeyse aynı olmalı: (uzunluk sınırı, en düşük benzerlik).
    private static let cleanMinimum: [(Int, Double)] = [(24, 90), (256, 80)]
    /// OCR izi olan satır: (uzunluk sınırı, en düşük benzerlik).
    private static let damagedMinimum: [(Int, Double)] = [(24, 70), (40, 60), (256, 55)]

    /// Tek yönlü kapsama (yarım ya da fazlalıklı okuma): iki taraf da en az `minLength` harf; kısa tarafın en az `inner`'ı
    /// uzun tarafta; uzun tarafın en az `partial`'ı (satır yarım okunmuş) ya da `extra`'sı (okumada fazlalık var) kısa
    /// tarafta; benzerlik `inner - penalty` sayılır.
    enum Containment {
        static let minLength = 22
        static let inner = 90.0
        static let partial = 25.0
        static let extra = 55.0
        static let penalty = 10.0
    }

    /// Fazlalıklı okumada yan cümle denetimi (`clauseHashes`): her en az `letters` harfli yan cümlenin üçlülerinin en az
    /// `covered`'ı kısa adayda olmalı.
    enum SentenceCheck {
        static let letters = 6
        static let covered = 30.0
    }

    private static let clauseEnders: Set<Unicode.Scalar> = [".", "!", "?", "…", ",", ";", ":"]
    /// Ardından küçük harf gelse de bölen işaretler (yan cümle).
    private static let clauseMarks: Set<Unicode.Scalar> = [",", ";", ":"]
    private static let clauseClosers: Set<Unicode.Scalar> = ["\"", "'", "”", "’", ")", "]"]

    /// Satırın cümle ve yan cümleleri: . ! ? … (isteğe bağlı kapanış tırnağı ya da ayraç) ardından boşluk ve büyük harf,
    /// ya da , ; : … ve üç nokta ardından boşluk. Tek noktadan sonra küçük harfle süren yer bölünmez (OCR çöpüdür).
    public static func clauses(_ text: String) -> [String] {
        let points = Array(text.unicodeScalars)
        var output: [String] = []
        var begin = 0
        var index = 0
        func cut(_ end: Int) {
            let clause = Scalars.string(points[begin..<end]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !clause.isEmpty { output.append(clause) }
        }
        while index < points.count {
            guard clauseEnders.contains(points[index]) else {
                index += 1
                continue
            }
            var end = index
            while end < points.count && clauseEnders.contains(points[end]) { end += 1 }
            while end < points.count && clauseClosers.contains(points[end]) { end += 1 }
            var next = end
            while next < points.count && Scalars.isSpace(points[next]) { next += 1 }
            // Üç nokta da küçük harfle süren yan cümleyi ayırır ("to escape... if we're able").
            var dots = 0
            var soft = false
            for position in index..<end {
                if clauseMarks.contains(points[position]) || points[position] == "…" { soft = true }
                if points[position] == "." { dots += 1 }
            }
            if next > end && next < points.count && (soft || dots > 1 || Scalars.isUpper(points[next])) {
                cut(end)
                begin = next
            }
            index = max(next, index + 1)
        }
        cut(points.count)
        // SentenceCheck.letters harften kısa yan cümle ("Bye," "Dad.") sonrakine (sonuncuysa öncekine) eklenir.
        var merged: [String] = []
        var pending = ""
        for clause in output {
            let joined = pending.isEmpty ? clause : pending + " " + clause
            if folded(joined).count < SentenceCheck.letters { pending = joined } else {
                merged.append(joined)
                pending = ""
            }
        }
        if !pending.isEmpty {
            if merged.isEmpty { merged.append(pending) } else { merged[merged.count - 1] += " " + pending }
        }
        return merged
    }

    /// Fazlalıklı okumanın denetlenecek yan cümleleri: her biri için üçlü kümesi seçenekleri. Köşe yazısına benzeyen yan
    /// cümle atlanır; ilk yan cümlenin başındaki ve sonuncunun sonundaki en çok üç büyük harfli sözcük köşe yazısı
    /// olabilir, onlarsız hâli de seçenektir. Okuma tek yan cümleyse boş.
    static func clauseHashes(_ text: String) -> [[[UInt64]]] {
        let parts = clauses(text)
        guard parts.count >= 2 else { return [] }
        var output: [[[UInt64]]] = []
        for (position, part) in parts.enumerated() {
            if overlayLike(part) { continue }
            let tokens = words(part).map { Scalars.string($0) }
            var options = [part]
            for count in 1...3 {
                if count >= tokens.count { break }
                if position == 0 && tokens.prefix(count).allSatisfy(capitalized) { options.append(tokens.dropFirst(count).joined(separator: " ")) }
                if position == parts.count - 1 && tokens.suffix(count).allSatisfy(capitalized) {
                    options.append(tokens.dropLast(count).joined(separator: " "))
                }
            }
            let folds = options.map(folded)
            if folds.contains(where: { $0.count < SentenceCheck.letters }) { continue }
            output.append(folds.map(trigramHashes))
        }
        return output
    }

    /// Büyük harfle başlayan, yalnız harf ve rakamlı sözcük (köşe yazısı sözcüğü: "Vinewood", "LOSNN").
    public static func capitalized(_ word: String) -> Bool {
        let points = Array(word.unicodeScalars)
        guard let first = points.first else { return false }
        return Scalars.isUpper(first) && points.allSatisfy { Scalars.isLetter($0) || Scalars.isDigit($0) }
    }

    /// Köşe yazısına benzeyen parça: "•" içerir ya da en çok dört sözcük, noktalama yok ve her sözcük büyük harfle başlar.
    public static func overlayLike(_ text: String) -> Bool {
        if text.contains("•") { return true }
        let tokens = words(text).map { Scalars.string($0) }
        return !tokens.isEmpty && tokens.count <= 4 && tokens.allSatisfy(capitalized)
    }

    /// Her yan cümlenin seçeneklerinden birinin üçlülerinin en az `SentenceCheck.covered`'ı adayın Bloom'unda mı.
    static func coversClauses(_ clauses: [[[UInt64]]], entry: [UInt64], offset: Int, bits: Int, inEntry: Int) -> Bool {
        let fill = Double(inEntry) / Double(bits)
        if fill >= 1 { return false }
        func covered(_ hashes: [UInt64]) -> Bool {
            let words = bloom(hashes, bits: bits)
            var both = 0
            var inClause = 0
            for index in words.indices {
                both += (words[index] & entry[offset + index]).nonzeroBitCount
                inClause += words[index].nonzeroBitCount
            }
            if inClause == 0 { return true }
            return 100 * (Double(both) / Double(inClause) - fill) / (1 - fill) >= SentenceCheck.covered
        }
        return clauses.allSatisfy { options in options.contains(where: covered) }
    }

    private static let foldMap: [UInt32: UInt32] = [
        0x6C: 0x69, 0x31: 0x69, 0x131: 0x69, 0x6A: 0x69, 0x30: 0x6F, 0x35: 0x73, 0xE6: 0x61, 0xF8: 0x6F, 0x153: 0x6F, 0xDF: 0x73,
    ]

    /// Yazı tanımanın sık karıştırdığı harf dizileri (canon metinde, küçük harf): okunan → asıl olabilecek.
    public static let confusions: [(String, String)] = [
        ("rn", "m"), ("m", "rn"), ("cl", "d"), ("d", "cl"), ("vv", "w"), ("ri", "n"), ("n", "ri"), ("li", "h"),
        ("l", "i"), ("i", "l"), ("1", "l"), ("1", "i"), ("0", "o"), ("5", "s"), ("j", "i"), ("c", "e"), ("e", "c"),
        ("a", "o"), ("o", "a"), ("h", "b"), ("b", "h"), ("f", "t"), ("t", "f"), ("u", "v"), ("v", "u"), ("y", "v"),
        ("v", "y"), ("q", "g"), ("g", "q"),
    ]
    /// Tek düzeltmeyle denenecek en kısa ve en uzun canon satır (harf sayısı); daha uzun satırda bulanık imza yeter.
    public static let variantMinimum = 6
    public static let variantMaximum = 48

    /// Satırın tek bir OCR düzeltmesiyle olabileceği canon hâller: karıştırılan harf dizisi değişir, bir harf düşer,
    /// sözcük ikiye bölünür ("cant" → "can t") ya da iki sözcük birleşir. Pakette tam anahtarla aranırlar.
    public static func variants(_ text: String) -> [String] {
        let canon = PackKey.canon(text)
        let points = Array(canon.unicodeScalars)
        let count = points.count
        guard count >= variantMinimum && count <= variantMaximum else { return [] }
        var output = Set<String>()
        func add(_ cut: Int, _ middle: String, _ resume: Int) {
            var view = String.UnicodeScalarView()
            view.append(contentsOf: points[0..<cut])
            view.append(contentsOf: middle.unicodeScalars)
            view.append(contentsOf: points[resume..<count])
            let value = String(view)
            if !value.isEmpty && !value.hasPrefix(" ") && !value.hasSuffix(" ") && !value.contains("  ") { output.insert(value) }
        }
        let confusionScalars = confusions.map { (Array($0.0.unicodeScalars), $0.1) }
        for index in 0..<count {
            for (read, real) in confusionScalars {
                guard index + read.count <= count, Array(points[index..<(index + read.count)]) == read else { continue }
                add(index, real, index + read.count)
            }
            add(index, "", index + 1)
            if points[index] != " " && index > 0 && points[index - 1] != " " { add(index, " ", index) }
        }
        output.remove(canon)
        // Kotlin/Java'nın dize sırası: UTF-16 kod birimi sırası.
        return output.sorted { Array($0.utf16).lexicographicallyPrecedes(Array($1.utf16)) }
    }

    /// canon metin, boşluksuz ve OCR'ın karıştırdığı harfler birleşmiş (kod noktaları).
    public static func folded(_ text: String) -> [UInt32] {
        PackKey.canon(text).unicodeScalars.compactMap { scalar -> UInt32? in
            scalar == " " ? nil : (foldMap[scalar.value] ?? scalar.value)
        }
    }

    static func foldedString(_ text: String) -> String {
        var view = String.UnicodeScalarView()
        for value in folded(text) { if let scalar = Unicode.Scalar(value) { view.append(scalar) } }
        return String(view)
    }

    /// Uzunluğa göre Bloom boyu (bit).
    public static func bits(_ length: Int) -> Int {
        var bits = 64
        while bits < maxBits && length * bitsPerGram > bits { bits *= 2 }
        return bits
    }

    static func trigramHashes(_ points: [UInt32]) -> [UInt64] {
        guard points.count >= 3 else { return [] }
        var seen = Set<UInt64>()
        var hashes: [UInt64] = []
        for index in 0...(points.count - 3) {
            var hash: UInt64 = 0xcbf29ce484222325
            for offset in 0..<3 {
                for byte in utf8(points[index + offset]) {
                    hash ^= UInt64(byte)
                    hash = hash &* 0x100000001b3
                }
            }
            if seen.insert(hash).inserted { hashes.append(hash) }
        }
        return hashes
    }

    private static func utf8(_ codePoint: UInt32) -> [UInt8] {
        switch codePoint {
        case ..<0x80: return [UInt8(codePoint)]
        case ..<0x800: return [UInt8(0xc0 | (codePoint >> 6)), UInt8(0x80 | (codePoint & 0x3f))]
        case ..<0x10000:
            return [UInt8(0xe0 | (codePoint >> 12)), UInt8(0x80 | ((codePoint >> 6) & 0x3f)), UInt8(0x80 | (codePoint & 0x3f))]
        default:
            return [UInt8(0xf0 | (codePoint >> 18)), UInt8(0x80 | ((codePoint >> 12) & 0x3f)), UInt8(0x80 | ((codePoint >> 6) & 0x3f)),
                    UInt8(0x80 | (codePoint & 0x3f))]
        }
    }

    static func bloom(_ hashes: [UInt64], bits: Int) -> [UInt64] {
        var words = [UInt64](repeating: 0, count: bits / 64)
        for hash in hashes {
            let bit = Int(hash & UInt64(bits - 1))
            words[bit >> 6] |= 1 << UInt64(bit & 63)
        }
        return words
    }

    /// İki yönlü, rastlantıdan arındırılmış kapsama (0-100). `entry`: dizindeki kelimeler, `offset`ten başlayarak bits / 64 tane.
    static func similarity(_ query: [UInt64], _ entry: [UInt64], offset: Int, bits: Int) -> Double {
        var both = 0, inQuery = 0, inEntry = 0
        for index in 0..<(bits / 64) {
            let q = query[index], e = entry[offset + index]
            both += (q & e).nonzeroBitCount
            inQuery += q.nonzeroBitCount
            inEntry += e.nonzeroBitCount
        }
        return score(both: both, inQuery: inQuery, inEntry: inEntry, bits: bits)
    }

    /// Benzerlik, bit sayılarından (ortak, sorgudaki, adaydaki).
    static func score(both: Int, inQuery: Int, inEntry: Int, bits: Int) -> Double {
        guard inQuery > 0, inEntry > 0 else { return 0 }
        let fillQuery = Double(inQuery) / Double(bits)
        let fillEntry = Double(inEntry) / Double(bits)
        guard fillQuery < 1, fillEntry < 1 else { return 0 }
        return 100 * min((Double(both) / Double(inQuery) - fillEntry) / (1 - fillEntry),
                         (Double(both) / Double(inEntry) - fillQuery) / (1 - fillQuery))
    }

    /// Rastlantıdan arındırılmış tek yönlü kapsamalar (0-100): sorgunun adayda olanı (`query`), adayın sorguda olanı.
    static func coverage(both: Int, inQuery: Int, inEntry: Int, bits: Int) -> (query: Double, entry: Double) {
        guard inQuery > 0, inEntry > 0 else { return (0, 0) }
        let fillQuery = Double(inQuery) / Double(bits)
        let fillEntry = Double(inEntry) / Double(bits)
        guard fillQuery < 1, fillEntry < 1 else { return (0, 0) }
        return (100 * (Double(both) / Double(inQuery) - fillEntry) / (1 - fillEntry),
                100 * (Double(both) / Double(inEntry) - fillQuery) / (1 - fillQuery))
    }

    /// (uzunluk, Bloom baytları) — paket betiğinin yazdığıyla aynı; satır çok kısaysa nil.
    public static func signature(_ text: String) -> (length: Int, bloom: [UInt8])? {
        let points = folded(text)
        guard points.count >= minLength else { return nil }
        let length = min(points.count, 255)
        let words = bloom(trigramHashes(points), bits: bits(length))
        var bytes: [UInt8] = []
        for word in words {
            for shift in stride(from: 0, to: 64, by: 8) { bytes.append(UInt8((word >> UInt64(shift)) & 0xff)) }
        }
        return (length, bytes)
    }

    /// Kabul için en düşük benzerlik (0-100), OCR izine ve uzunluğa göre.
    static func required(_ length: Int, damaged: Bool) -> Double {
        let table = damaged ? damagedMinimum : cleanMinimum
        return table.first { length < $0.0 }?.1 ?? table.last!.1
    }

    private static let edge: Set<Unicode.Scalar> = Set(".,!?;:'\"()[]-…“”’‘".unicodeScalars)
    private static let typographic: Set<Unicode.Scalar> = Set("’‘“”—–…".unicodeScalars)

    /// Boşluklarla ayrılan sözcükler (kod noktası dizileri).
    static func words(_ text: String) -> [[Unicode.Scalar]] {
        var words: [[Unicode.Scalar]] = []
        var current: [Unicode.Scalar] = []
        for scalar in text.unicodeScalars {
            if Scalars.isSpace(scalar) {
                if !current.isEmpty { words.append(current); current = [] }
            } else {
                current.append(scalar)
            }
        }
        if !current.isEmpty { words.append(current) }
        return words
    }

    /// OCR izi: İngilizce satırda ASCII dışı harf ya da işaret, sözcük içinde harf olmayan işaret ya da rakam, tek harfli
    /// garip sözcük, çok uzun (birleşmiş) sözcük. İzi olmayan satırın neredeyse aynı olması gerekir.
    public static func damaged(_ text: String) -> Bool {
        if text.unicodeScalars.contains(where: { $0.value > 127 && ($0.value > 0xffff || !typographic.contains($0)) }) { return true }
        for word in words(text) {
            var from = 0, to = word.count
            while from < to && edge.contains(word[from]) { from += 1 }
            while to > from && edge.contains(word[to - 1]) { to -= 1 }
            let core = Array(word[from..<to])
            if core.isEmpty { continue }
            if core.count > 2 {
                for index in 1..<(core.count - 1) where Scalars.isLetter(core[index - 1]) && Scalars.isLetter(core[index + 1])
                    && !Scalars.isLetter(core[index]) && core[index] != "'" && core[index] != "’" && core[index] != "-" {
                    return true
                }
            }
            if core.count > 1 {
                for index in 1..<core.count {
                    let previous = core[index - 1], current = core[index]
                    if (Scalars.isLetter(previous) && Scalars.isDigit(current)) || (Scalars.isDigit(previous) && Scalars.isLetter(current)) {
                        return true
                    }
                }
            }
            if core.count == 1 && Scalars.isLetter(core[0]) && !["a", "A", "I", "o", "O"].contains(core[0]) { return true }
            if core.count > 14 { return true }
        }
        return false
    }
}

/// Bir paketin bulanık dizini: imzalı satırlar uzunluğa göre sıralı (aynı uzunlukta anahtar sırasıyla), Bloom kelimeleri
/// ardışık. Satır başına yalnız anahtar ve Bloom kelimeleri tutulur; uzunluk ve kelime yeri uzunluk kovalarından çıkar.
final class PackFuzzyIndex: @unchecked Sendable {
    private let keys: [UInt64]
    private let words: [UInt64]
    /// `start[n]`: uzunluğu n olan ilk satırın sırası (`start[256]` = satır sayısı).
    private let start: [Int]
    /// `wordStart[n]`: uzunluğu n olan ilk satırın ilk Bloom kelimesi.
    private let wordStart: [Int]

    var count: Int { keys.count }

    private init(keys: [UInt64], words: [UInt64], start: [Int], wordStart: [Int]) {
        self.keys = keys
        self.words = words
        self.start = start
        self.wordStart = wordStart
    }

    /// Paketteki "fuzzy" nesnesi. `order`: paketteki bütün anahtarlar (entries + segments, Türkçesi boş olanlar da),
    /// sayı sırasıyla, tekrarsız. Biçim tutmazsa nil (paket yine de tam eşleşmeyle çalışır).
    static func decode(_ fuzzy: PackJson.Fuzzy, order: [UInt64]) -> PackFuzzyIndex? {
        guard let mask = fuzzy.mask, let lengths = fuzzy.lengths, let blooms = fuzzy.blooms,
              fuzzy.version == 1, fuzzy.count == Double(order.count), mask.count == (order.count + 7) / 8 else { return nil }
        let total = lengths.count
        var start = [Int](repeating: 0, count: 257)
        for length in lengths { start[Int(length) + 1] += 1 }
        for length in 1...256 { start[length] += start[length - 1] }
        var wordStart = [Int](repeating: 0, count: 257)
        for length in 1...256 {
            wordStart[length] = wordStart[length - 1] + (start[length] - start[length - 1]) * (PackFuzzy.bits(length - 1) / 64)
        }
        var expectedBytes = 0
        for length in lengths { expectedBytes += PackFuzzy.bits(Int(length)) / 8 }
        guard expectedBytes == blooms.count else { return nil }
        var keys = [UInt64](repeating: 0, count: total)
        var words = [UInt64](repeating: 0, count: blooms.count / 8)
        var next = start
        // Dosyadaki sıra: imzalı anahtarlar sayı sırasıyla, Bloom'ları ardışık; uzunluğa göre sayarak sıralanır.
        var signed = 0
        var byteOffset = 0
        for index in order.indices {
            if (mask[index >> 3] >> UInt8(index & 7)) & 1 == 0 { continue }
            if signed >= total { return nil }
            let length = Int(lengths[signed])
            let perLine = PackFuzzy.bits(length) / 64
            let position = next[length]
            next[length] += 1
            keys[position] = order[index]
            let wordOffset = wordStart[length] + (position - start[length]) * perLine
            for word in 0..<perLine {
                var value: UInt64 = 0
                let base = byteOffset + word * 8
                for byte in 0..<8 { value |= UInt64(blooms[base + byte]) << UInt64(byte * 8) }
                words[wordOffset + word] = value
            }
            byteOffset += perLine * 8
            signed += 1
        }
        guard signed == total else { return nil }
        return PackFuzzyIndex(keys: keys, words: words, start: start, wordStart: wordStart)
    }

    private struct Candidate {
        let score: Double
        let key: UInt64
    }

    /// Eşik, uzunluk ve ikinci aday şartlarını sağlayan en benzer satırın anahtarı; yoksa nil. `translation`: anahtarın
    /// Türkçesi (aynı Türkçeli iki aday birbirinin rakibi sayılmaz). Önce boyu yakın adaylar (iki yönlü kapsamanın
    /// küçüğü); hiçbiri eşiği geçmezse boyu çok farklı adaylarda tek yönlü kapsamaya bakılır (`PackFuzzy.Containment`).
    func nearest(_ text: String, translation: (UInt64) -> String?) -> UInt64? {
        let points = PackFuzzy.folded(text)
        guard points.count >= PackFuzzy.minLength, !keys.isEmpty else { return nil }
        let length = min(points.count, 255)
        let need = PackFuzzy.required(length, damaged: PackFuzzy.damaged(text))
        let floor = need - PackFuzzy.margin
        let hashes = PackFuzzy.trigramHashes(points)
        // Sorgunun üç boydaki Bloom'u (64, 128, 256 bit) ve bit sayıları.
        let queries = [PackFuzzy.bloom(hashes, bits: 64), PackFuzzy.bloom(hashes, bits: 128), PackFuzzy.bloom(hashes, bits: 256)]
        let queryOnes = queries.map { $0.reduce(0) { $0 + $1.nonzeroBitCount } }
        typealias Contain = PackFuzzy.Containment
        var close: [Candidate] = []
        var contained: [Candidate] = []
        // Okumanın yan cümle üçlüleri; ilk fazlalıklı adayda hesaplanır.
        var clauses: [[[UInt64]]]?

        /// Boyu `lower`...`upper` olan adaylar: `wide` false ise iki yönlü benzerlik, true ise tek yönlü kapsama.
        func scan(_ lower: Int, _ upper: Int, wide: Bool) {
            guard lower <= upper else { return }
            for entryLength in lower...upper {
                let first = start[entryLength], end = start[entryLength + 1]
                if first == end { continue }
                let bits = PackFuzzy.bits(entryLength)
                let perLine = bits / 64
                let tier = bits == 64 ? 0 : bits == 128 ? 1 : 2
                let query = queries[tier]
                let inQuery = queryOnes[tier]
                if wide && (entryLength < Contain.minLength || entryLength == length) { continue }
                var offset = wordStart[entryLength]
                for index in first..<end {
                    var both = 0, inEntry = 0
                    for word in 0..<perLine {
                        let value = words[offset + word]
                        both += (query[word] & value).nonzeroBitCount
                        inEntry += value.nonzeroBitCount
                    }
                    offset += perLine
                    if !wide {
                        let score = PackFuzzy.score(both: both, inQuery: inQuery, inEntry: inEntry, bits: bits)
                        if score >= floor { close.append(Candidate(score: score, key: keys[index])) }
                        continue
                    }
                    // Kısa tarafın bitlerinin en az %90'ı ortak olmalı (hızlı eleme).
                    if both * 10 < 9 * (entryLength > length ? inQuery : inEntry) { continue }
                    let cover = PackFuzzy.coverage(both: both, inQuery: inQuery, inEntry: inEntry, bits: bits)
                    let longer = entryLength > length
                    let inner = longer ? cover.query : cover.entry
                    let outer = longer ? cover.entry : cover.query
                    let least = longer ? Contain.partial : Contain.extra
                    if inner < Contain.inner || outer < least || inner - Contain.penalty < floor { continue }
                    // Fazlalıklı okumada her yan cümle adayda olmalı: ikinci bir altyazının cümlesi fazlalık sayılmaz.
                    if !longer {
                        let found = clauses ?? PackFuzzy.clauseHashes(text)
                        clauses = found
                        if !found.isEmpty && !PackFuzzy.coversClauses(found, entry: words, offset: offset - perLine, bits: bits, inEntry: inEntry) {
                            continue
                        }
                    }
                    contained.append(Candidate(score: inner - Contain.penalty, key: keys[index]))
                }
            }
        }

        /// En iyi aday; eşiği geçmiyorsa (nil, false), Türkçesi farklı rakibi yakınsa (nil, true).
        func pick(_ found: [Candidate]) -> (UInt64?, Bool) {
            var best: Candidate?
            for candidate in found {
                if let current = best, !(candidate.score > current.score || (candidate.score == current.score && candidate.key > current.key)) {
                    continue
                }
                best = candidate
            }
            guard let best, best.score >= need else { return (nil, false) }
            let turkish = translation(best.key)
            for other in found where other.key != best.key && other.score > best.score - PackFuzzy.margin && translation(other.key) != turkish {
                return (nil, true)
            }
            return (best.key, false)
        }

        // İki yönlü aday: boyu en çok ±%25 farklı.
        scan(length - length / 4, min(255, length + length / 4), wide: false)
        // Tek yönlü adaylara yalnız iki yönlü hiçbir aday eşiği geçmediyse bakılır.
        let (key, ambiguous) = pick(close)
        if key != nil || ambiguous || length < Contain.minLength { return key }
        scan(max(Contain.minLength, length * 11 / 20), min(255, length * 5 / 2), wide: true)
        if contained.isEmpty { return nil }
        return pick(close + contained).0
    }
}
