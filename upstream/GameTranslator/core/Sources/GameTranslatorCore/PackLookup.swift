// SPDX-License-Identifier: AGPL-3.0-only
// CouchSubs'tan uyarlandı: CouchSubs (Copyright (C) 2026 Sinan Yorulmaz), AGPL-3.0, yazarının izniyle.

import Foundation

/// Satırı oyun paketinde arar (Windows: PackLookup.cs, Android: PackLookup). Sırayla: satırın tamamı; konuşmacı etiketi
/// ya da diyalog tiresi varsa onsuz hâli (etiket çeviriye eklenir); birden çok satırsa her satır ayrı; birden çok cümleyse
/// her cümle ayrı. Parçalı aramada parçaların hepsi bulunmalıdır, yoksa satır motorla çevrilir. Tam anahtarla hiçbir yol
/// tutmazsa aynı sıra bir kez daha denenir; bu kez her parça önce tam, sonra `nearest` ile (OCR hatalarına dayanıklı) aranır.
public enum PackLookup {
    /// Kütüphanedeki (açık) paketlerde arar (bulanık arama oynanan oyunun paketinde); sonuçta satırın son bulunan
    /// parçasının paketi yazılır.
    public static func match(_ library: GamePackLibrary, _ text: String) -> PackMatch? {
        var lastPack: String?
        // Aynı parça bir aramada birkaç yoldan sorulabilir; bulanık sonuç bir kez hesaplanır.
        var asked: [String: PackMatch?] = [:]
        let found = match(text, nearest: { line in
            let near: PackMatch?
            if let known = asked[line] { near = known } else {
                near = library.nearestWithPack(line)
                asked[line] = .some(near)
            }
            if let near { lastPack = near.packId }
            return near?.text
        }, find: { line in
            guard let match = library.findWithPack(line) else { return nil }
            lastPack = match.packId
            return match.text
        })
        return found.map { PackMatch($0.text, packId: lastPack) }
    }

    /// `find`: tek bir metnin (tamamının) paketteki Türkçesi. `nearest`: tam anahtar yoksa en benzer satırın Türkçesi
    /// (bulanık arama); verilmezse yapılmaz.
    public static func match(_ text: String, nearest: ((String) -> String?)? = nil, find: (String) -> String?) -> PackMatch? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let found = phase(trimmed, find) { return found }
        guard let nearest else { return nil }
        return phase(trimmed) { line in find(line) ?? nearest(line) }
    }

    private static func phase(_ trimmed: String, _ find: (String) -> String?) -> PackMatch? {
        if let found = single(trimmed, find) { return result(found.pieces, found.translations) }

        // Her satır ayrı bir altyazı olabilir (iki konuşmacı alt alta).
        let lines = trimmed.replacingOccurrences(of: "\r\n", with: "\n").split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if lines.count > 1 {
            var pieces: [SubtitleSegmenter.Piece] = []
            var translations: [String?] = []
            var all = true
            for line in lines {
                guard let found = single(line, find) else {
                    // Altyazıyla aynı bloğa düşen köşe yazısı ("San Andreas", "First Person • zoom") atlanır.
                    if isOverlay(line) { continue }
                    all = false
                    break
                }
                if !pieces.isEmpty {
                    pieces.append(SubtitleSegmenter.Piece("\n", translate: false))
                    translations.append(nil)
                }
                pieces += found.pieces
                translations += found.translations
            }
            if all && !pieces.isEmpty { return result(pieces, translations) }
        }

        // Cümle cümle (etiket, tire ve ayraçlar olduğu gibi kalır).
        let split = SubtitleSegmenter.split(trimmed)
        guard split.filter(\.translate).count > 1 else { return nil }
        var sentences: [String?] = []
        for piece in split {
            guard piece.translate else {
                sentences.append(nil)
                continue
            }
            guard let turkish = find(piece.text) else { return nil }
            sentences.append(turkish)
        }
        return result(split, sentences)
    }

    /// Tek satır: başında konuşmacı etiketi ya da diyalog tiresi varsa önce onlarsız hâli (etiket/tire çeviriye eklenir),
    /// sonra tamamı, sonra OCR'ın bozduğu etiketsiz hâli (`looseLabel`).
    private static func single(_ line: String, _ find: (String) -> String?) -> (pieces: [SubtitleSegmenter.Piece], translations: [String?])? {
        let split = SubtitleSegmenter.split(line)
        let lead = Array(split.prefix { !$0.translate })
        if !lead.isEmpty && lead.count < split.count && !split.contains(where: { $0.text == "\n" }) {
            let body = split.dropFirst(lead.count).map(\.text).joined()
            if let turkish = find(body) {
                return (lead + [SubtitleSegmenter.Piece(body, translate: true)], [String?](repeating: nil, count: lead.count) + [turkish])
            }
        }
        if let turkish = find(line) { return ([SubtitleSegmenter.Piece(line, translate: true)], [turkish]) }
        guard let (label, body) = looseLabel(line), let turkish = find(body) else { return nil }
        return ([SubtitleSegmenter.Piece(label, translate: false), SubtitleSegmenter.Piece(body, translate: true)], [nil, turkish])
    }

    /// Altyazı olmayan köşe yazısı satırı: "•" içerir ya da en çok dört sözcük, noktalama yok ve her sözcük büyük harfle
    /// başlar ("San Andreas", "Mirror Park", "LOSNN"). Yalnız başka satırlar pakette bulunduysa atlanır.
    public static func isOverlay(_ line: String) -> Bool {
        if line.contains("•") { return true }
        let words = PackFuzzy.words(line)
        guard !words.isEmpty, words.count <= 4 else { return false }
        return words.allSatisfy { word in Scalars.isUpper(word[0]) && word.allSatisfy { Scalars.isLetter($0) || Scalars.isDigit($0) } }
    }

    /// OCR'ın bozduğu konuşmacı etiketi: "-GAETANO:", "M]:", "li LUCA:", "MİNE GUARD•" (ilk 32 karakterde iki nokta ya da
    /// • ve önündeki sözcükte küçük harf yok) ya da iki noktasız "SEBASTIAN Where…" (en çok üç büyük harfli sözcük, ardından
    /// büyük harfle başlayan küçük harfli sözcük). (ekranda kalacak etiket, aranacak gövde) ya da nil.
    public static func looseLabel(_ line: String) -> (label: String, body: String)? {
        let points = Array(line.unicodeScalars)
        func text(_ start: Int, _ end: Int) -> String { Scalars.string(points[start..<end]) }
        func nameLike(_ token: [Unicode.Scalar], _ minimum: Int) -> Bool {
            let letters = token.filter(Scalars.isLetter)
            return letters.count >= minimum && !letters.contains(where: Scalars.isLower)
        }
        if points.count > 1 {
            for index in 1..<min(points.count, 33) where points[index] == ":" || points[index] == "•" {
                guard let last = PackFuzzy.words(text(0, index)).last, nameLike(last, 1) else { continue }
                var start = index + 1
                while start < points.count && !Scalars.isLetter(points[start]) && !Scalars.isNumber(points[start]) { start += 1 }
                let body = text(start, points.count).trimmingCharacters(in: .whitespacesAndNewlines)
                guard body.unicodeScalars.contains(where: Scalars.isLetter) else { return nil }
                return (text(0, index + 1).trimmingCharacters(in: .whitespacesAndNewlines) + " ", body)
            }
        }
        var position = 0
        while position < points.count && !Scalars.isLetter(points[position]) { position += 1 }
        for _ in 0..<3 {
            var end = position
            while end < points.count && !Scalars.isSpace(points[end]) { end += 1 }
            guard end < points.count, Scalars.isUpper(points[position]), nameLike(Array(points[position..<end]), 2) else { return nil }
            while end < points.count && Scalars.isSpace(points[end]) { end += 1 }
            position = end
            if position + 1 < points.count && Scalars.isUpper(points[position]) && Scalars.isLower(points[position + 1]) {
                return (text(0, position).trimmingCharacters(in: .whitespacesAndNewlines) + " ", text(position, points.count).trimmingCharacters(in: .whitespacesAndNewlines))
            }
        }
        return nil
    }

    private static func result(_ pieces: [SubtitleSegmenter.Piece], _ translations: [String?]) -> PackMatch {
        PackMatch(pieces.indices.map { translations[$0] ?? pieces[$0].text }.joined())
    }
}

/// Altyazının başındaki konuşmacı işareti: tire ("- ", "– ", "— ") ya da ">>" (Windows'taki GoogleGtxTranslator ve
/// SubtitleSegmenter ile aynı kural). Çeviri motorları bu işaretleri silebildiği için çeviriden önce ayrılır, sonra
/// olduğu gibi geri eklenir.
public enum SpeakerPrefix {
    private static let pattern = try! NSRegularExpression(pattern: #"^(?:[-–—]+|>{1,3})\s*"#)

    /// (işaret, geri kalan metin). İşaret yoksa ilk değer boştur.
    public static func split(_ text: String) -> (prefix: String, rest: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let ns = trimmed as NSString
        guard let match = pattern.firstMatch(in: trimmed, range: NSRange(location: 0, length: ns.length)) else { return ("", trimmed) }
        return (ns.substring(with: match.range), ns.substring(from: match.range.length).trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

/// Hattın oyun paketi araması (Android: PackTranslation): baştaki konuşmacı işareti ("- ", ">>") ayrılır, gövde pakette
/// aranır (`PackLookup`: etiket, satır, cümle ve bulanık arama), işaret Türkçenin başına olduğu gibi geri konur.
public enum PackTranslation {
    /// Türkçeye özgü parçalar (sözlük, kısa ünlemler, tuş çubuğu sözlüğü) yalnız İngilizce → Türkçede çalışır.
    public static func applies(sourceLanguage: String, targetLanguage: String) -> Bool {
        sourceLanguage == "en" && targetLanguage == "tr"
    }

    /// Paketi olan çeviri dilleri (sunucudaki paketlerin "lang" alanı).
    public static let packLanguages: Set<String> = ["tr", "ar"]

    /// Oyun paketlerinin dili: İngilizce oyun ve paketi olan bir çeviri dili (Türkçe, Arapça); yoksa nil (paket yok).
    public static func packLanguage(sourceLanguage: String, targetLanguage: String) -> String? {
        let target = targetLanguage.lowercased()
        return sourceLanguage == "en" && packLanguages.contains(target) ? target : nil
    }

    public static func match(_ text: String, lookup: (String) -> PackMatch?) -> PackMatch? {
        let (prefix, rest) = SpeakerPrefix.split(text)
        if prefix.isEmpty { return lookup(text.trimmingCharacters(in: .whitespacesAndNewlines)) }
        guard !rest.isEmpty, let found = lookup(rest) else { return nil }
        return PackMatch(prefix + found.text, packId: found.packId)
    }

    public static func match(_ library: GamePackLibrary, _ text: String) -> PackMatch? {
        match(text) { PackLookup.match(library, $0) }
    }
}

/// Paket çevirisini yalnız oynanan oyunun paketi varken kullandırır (Windows: PackGate.cs, aynı kurallar). Başka oyunun
/// paketindeki kısa ve genel satırlar ("Yeah,", "Nope...", "Miles?") paketi olmayan oyunda da eşleşip o oyunun çevirisini
/// basıyordu. Kısa süre içinde birkaç uzun satır eşleşmedikçe eşleşmeler kullanılmaz; satır çeviri motoruna gider. Uzun
/// eşleşmeler kesilirse kapı yeniden kapanır. Oyun belliyse (PS5 bildirdi ve paketi var) kapıya bakılmaz.
public final class PackGate: @unchecked Sendable {
    /// Bu kadar sözcükten kısa satır genel sayılır; oyunu tanıtmaz.
    public static let longLineWords = 5
    /// Kapının açılması için `windowMs` içinde eşleşmesi gereken farklı uzun satır sayısı.
    public static let linesToOpen = 3
    public static let windowMs: Int64 = 5 * 60 * 1000
    /// Son uzun eşleşmeden bu kadar sonra kapı kapanır.
    public static let closeAfterMs: Int64 = 15 * 60 * 1000

    private let clock: () -> Int64
    private let lock = NSLock()
    private var recent: [(line: String, at: Int64)] = []
    private var lastLong = Int64.min / 2
    private var open = false

    /// `clock`: milisaniye; verilmezse sistemin açılıştan beri geçen süresi.
    public init(clock: (() -> Int64)? = nil) {
        self.clock = clock ?? { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    }

    /// Oynanan oyunun paketi var sayılıyor mu.
    public var isOpen: Bool {
        lock.lock()
        defer { lock.unlock() }
        return open && clock() - lastLong <= Self.closeAfterMs
    }

    /// `match` kullanılacaksa kendisi, oyunun paketi henüz belli değilse nil.
    public func filter(_ source: String, _ match: PackMatch?) -> PackMatch? {
        guard let match else { return nil }
        lock.lock()
        defer { lock.unlock() }
        let at = clock()
        if open && at - lastLong > Self.closeAfterMs {
            open = false
            recent.removeAll()
        }
        // Konuşmacı adı sayılmaz: "Spider-Man: what got into you?" dört sözcüklük genel bir cümledir.
        var spoken = source
        if let colon = source.firstIndex(of: ":") {
            let offset = source.distance(from: source.startIndex, to: colon)
            if offset >= 2 && offset <= 30 && !source[..<colon].contains("\n") { spoken = String(source[source.index(after: colon)...]) }
        }
        let line = TextMatch.normalize(spoken)
        if line.split(separator: " ").count >= Self.longLineWords {
            lastLong = at
            recent.removeAll { at - $0.at > Self.windowMs || $0.line == line }
            recent.append((line, at))
            if recent.count >= Self.linesToOpen { open = true }
        }
        return open ? match : nil
    }
}
