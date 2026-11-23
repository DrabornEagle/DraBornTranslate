// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Konuşmacı etiketlerinden öğrenilen karakter adları. Adlar cümle içinde de büyük harfle kalır ve çevrilmez.
///
/// Ayrıca adları oyundan sayarak öğrenir ve bozuk okunmuş adı doğrusuna çevirir (Windows: SpeakerNames.cs, Android:
/// SpeakerNames.kt, aynı kurallar): sık görülen ad bilinen addır; ilk kez görülen ve bilinen bir ada çok benzeyen ad onun
/// bozuk okumasıdır ("Minnir", "Mimnir" → "Mimir"). Birbirine benzeyen iki gerçek ad ikisi de yeterince görülünce ayrı kalır.
public final class SpeakerNames: @unchecked Sendable {
    /// Bir ad bu kadar görülünce bilinen ad olur.
    public static let knownAfter = 3
    /// Bilinen ad, benzer addan en az bu kat sık görülmeliyse onun doğrusu sayılır.
    public static let ratio = 3
    /// Güçlü okuyucu bir adı bu kadar okuyunca ad doğrulanmış sayılır.
    public static let confirmedAfter = 2

    private let lock = NSLock()
    private var names: Set<String> = []
    /// Görülen adlar ve sayıları, ilk görülme sırasıyla.
    private var seen: [(name: String, count: Int)] = []
    /// Güçlü okuyucunun okuduğu adlar ve kaç kez okuduğu.
    private var confirmed: [(name: String, count: Int)] = []

    public init() {}

    public var all: Set<String> { lock.withLock { names } }

    /// Parçalardaki etiketlerden adları öğrenir ("OLD MAN: " → "Old", "Man" değil, "Old Man").
    public func learn(from pieces: [SubtitleSegmenter.Piece]) {
        let found = pieces.compactMap { piece -> String? in
            guard !piece.translate, piece.text.hasSuffix(": ") else { return nil }
            let name = SubtitleSegmenter.name(fromLabel: piece.text)
            return name.isEmpty ? nil : name
        }
        guard !found.isEmpty else { return }
        lock.withLock {
            for name in found {
                // Çok sözcüklü adın her sözcüğü ayrıca tanınır: "ATREUS" etiketi cümledeki "ATREUS"u korur.
                for word in name.split(separator: " ") where word.count >= 2 { names.insert(String(word)) }
            }
        }
    }

    // MARK: - Bozuk okunan adlar

    /// "Aloy: Let's go." → "Aloy"; en çok üç sözcük, baş harfi büyük.
    static func label(_ text: String) -> String? {
        let line = String(text.drop(while: \.isWhitespace))
        guard let colon = line.range(of: ": ") else { return nil }
        let offset = line.distance(from: line.startIndex, to: colon.lowerBound)
        guard offset >= 2, offset <= 30, line.first?.isUppercase == true else { return nil }
        let name = String(line[..<colon.lowerBound])
        guard name.split(separator: " ", omittingEmptySubsequences: false).count <= 3,
              name.allSatisfy({ $0.isLetter || " -.'’".contains($0) }) else { return nil }
        return name
    }

    /// Büyük harfle yazılan adda küçük okunan harfler büyütülür: "GAETANo" → "GAETANO", "DAMiR" → "DAMIR". İlk iki harfi
    /// büyük olan (en az üç harfli) sözcük büyük harfli sayılır; "Mimir", "McKay" dokunulmaz.
    public static func fixCaps(_ name: String) -> String {
        guard name.contains(where: \.isLowercase) else { return name }
        var words = name.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        var changed = false
        for i in words.indices {
            let letters = Array(words[i].filter(\.isLetter))
            guard letters.count >= 3, letters[0].isUppercase, letters[1].isUppercase, letters.contains(where: \.isLowercase) else { continue }
            words[i] = words[i].uppercased()
            changed = true
        }
        return changed ? words.joined(separator: " ") : name
    }

    /// Satır başında art arda iki ad varsa öndeki atılır: "HSS: GAETANO: Worries are for tomorrow." → "GAETANO: …".
    public static func withoutLeadingJunkLabel(_ line: String) -> String {
        guard let first = label(line), first.count <= 12, let start = line.range(of: first + ": ") else { return line }
        let rest = String(line[start.upperBound...].drop(while: \.isWhitespace))
        if let second = label(rest), second != first { return rest }
        return line
    }

    /// Satırın başındaki konuşmacı adı `fixCaps` ile düzeltilmiş hâli ("ENzo: Here." → "ENZO: Here.").
    public static func fixLabelCaps(_ line: String) -> String {
        guard let name = label(line) else { return line }
        let fixed = fixCaps(name)
        guard fixed != name, let range = line.range(of: name) else { return line }
        return line.replacingCharacters(in: range, with: fixed)
    }

    /// Aynı sayıda sözcük ve harflerin çoğu aynı (uzunluk farkı en çok iki).
    private static func alike(_ read: String, _ known: String) -> Bool {
        guard known.count >= 4, abs(read.count - known.count) <= 2 else { return false }
        guard read.filter({ $0 == " " }).count == known.filter({ $0 == " " }).count else { return false }
        return TextMatch.isSimilar(read.lowercased(), known.lowercased(), threshold: 0.6)
    }

    private static func increment(_ list: inout [(name: String, count: Int)], _ name: String) {
        if let index = list.firstIndex(where: { $0.name == name }) { list[index].count += 1 } else { list.append((name, 1)) }
    }

    private static func count(_ list: [(name: String, count: Int)], _ name: String) -> Int {
        list.first { $0.name == name }?.count ?? 0
    }

    /// Güçlü okuyucunun okumasındaki (satırlar) konuşmacı adlarını kaydeder; onun okuduğu ad benzer okumaların doğrusudur.
    public func confirm(_ lines: [String]) {
        lock.withLock {
            for line in lines {
                guard let read = Self.label(line) else { continue }
                Self.increment(&confirmed, Self.fixCaps(read))
            }
            if confirmed.count > 300 { confirmed.removeAll() }
        }
    }

    /// Okumanın konuşmacı adı henüz doğrulanmadı mı (öyleyse satır güçlü okuyucuyla da okunmalı).
    public func needsCheck(_ text: String) -> Bool {
        guard let name = Self.label(String(text.split(separator: "\n", omittingEmptySubsequences: false).first ?? "")) else { return false }
        return lock.withLock { confirmedName(name) == nil }
    }

    /// Ad (ya da benzeri) doğrulandı ya da oyunda yeterince görüldü.
    public func isKnown(_ name: String) -> Bool {
        lock.withLock {
            confirmedName(name) != nil || seen.contains { $0.count >= Self.knownAfter && ($0.name == name || Self.alike(name, $0.name)) }
        }
    }

    /// Adın doğrulanmış hâli; kilit altında çağrılır.
    private func confirmedName(_ name: String) -> String? {
        if Self.count(confirmed, name) >= Self.confirmedAfter { return name }
        var best: String?
        var bestCount = Self.confirmedAfter - 1
        for (known, count) in confirmed where count > bestCount && Self.alike(name, known) {
            best = known
            bestCount = count
        }
        return best
    }

    /// Adı kaydeder ve doğrusunu döner (bilinen bir adın bozuk okumasıysa o ad, değilse kendisi).
    public func canonical(_ read: String) -> String {
        let name = Self.fixCaps(read)
        return lock.withLock {
            if let sure = confirmedName(name) {
                Self.increment(&seen, sure)
                return sure
            }
            Self.increment(&seen, name)
            // Bir kez görülmüş adlar (çoğu bozuk okuma) atılır.
            if seen.count > 300 { seen.removeAll { $0.count < 2 } }
            if name.count < 4 { return name }
            var best: String?
            var bestCount = 0
            let own = Self.count(seen, name)
            for (known, count) in seen {
                if count < Self.knownAfter || count <= bestCount || known == name { continue }
                if count < own * Self.ratio || !Self.alike(name, known) { continue }
                best = known
                bestCount = count
            }
            return best ?? name
        }
    }

    /// Satır başındaki parça, iki nokta üst üstesi okunmamış bilinen bir ad mı ("Stintsy Fuck": ":" harf okunmuş; "Jockte
    /// Hey..."). Öyleyse bilinen ad; değilse nil.
    public func labelIn(_ first: String, next: String?) -> String? {
        var token = first
        while let last = token.last, ".,;:".contains(last) { token.removeLast() }
        guard token.count >= 3, token.first!.isUppercase else { return nil }
        let candidates: [String] = lock.withLock {
            var list = seen.filter { $0.count >= Self.knownAfter }.map(\.name)
            for entry in confirmed where entry.count >= Self.confirmedAfter && !list.contains(entry.name) { list.append(entry.name) }
            return list
        }
        let nextUpper = next?.first?.isUppercase == true
        for known in candidates {
            // Büyük harfle yazılan bilinen ad ayraçsız da gelebilir ("BODE Using the Empire's tools").
            if token == known && known.count >= 3 && known.allSatisfy({ !$0.isLetter || $0.isUppercase }) && nextUpper { return known }
            if known.count < 4 || known.filter({ $0 == " " }).count != token.filter({ $0 == " " }).count || token == known { continue }
            let extra = token.count - known.count
            if token.hasPrefix(known) && (extra == 1 || extra == 2) && token.dropFirst(known.count).allSatisfy(\.isLowercase) { return known }
            if token.count >= 5 && nextUpper && Self.alike(token, known) { return known }
        }
        return nil
    }
}
