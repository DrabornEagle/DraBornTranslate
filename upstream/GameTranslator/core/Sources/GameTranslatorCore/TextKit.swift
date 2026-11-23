// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

// Kotlin/C# kaynaklarından taşınan metin işlemlerinin ortak küçük yardımcıları (modül içi).

extension Character {
    /// Kotlin'deki Char.isLetterOrDigit: harf ya da ondalık rakam.
    var isLetterOrDigit: Bool { isLetter || (isNumber && wholeNumberValue != nil) }
}

extension String {
    /// Sondaki `chars` karakterleri atılır (Kotlin: trimEnd(vararg chars)).
    func trimEnd(_ chars: String) -> String {
        var result = Substring(self)
        while let last = result.last, chars.contains(last) { result.removeLast() }
        return String(result)
    }

    /// Baştaki `chars` karakterleri atılır (Kotlin: trimStart(vararg chars)).
    func trimStart(_ chars: String) -> String {
        var result = Substring(self)
        while let first = result.first, chars.contains(first) { result.removeFirst() }
        return String(result)
    }

    /// İki uçtaki `chars` karakterleri atılır (Kotlin: trim(vararg chars)).
    func trimBoth(_ chars: String) -> String { trimStart(chars).trimEnd(chars) }

    /// Kotlin'deki split(' '): boş parçalar da kalır.
    func splitKeepingEmpty(_ separator: Character) -> [String] {
        split(separator: separator, omittingEmptySubsequences: false).map(String.init)
    }

    /// Boşluklara göre sözcükler (Kotlin: OcrNoise.tokens).
    var whitespaceTokens: [String] { split(whereSeparator: \.isWhitespace).map(String.init) }

    /// `needle`'ın ilk geçtiği yerin karakter sırası; yoksa -1 (Kotlin: indexOf).
    func characterOffset(of needle: String) -> Int {
        guard let range = range(of: needle) else { return -1 }
        return distance(from: startIndex, to: range.lowerBound)
    }

    /// Karakter sırasıyla kesit (Kotlin: substring(start, end)).
    func slice(_ start: Int, _ end: Int) -> String {
        let chars = Array(self)
        let a = max(0, min(start, chars.count)), b = max(a, min(end, chars.count))
        return String(chars[a..<b])
    }
}

extension Array where Element == Character {
    /// `pattern`'ın `from` sırasından sonraki ilk geçtiği yer; yoksa -1.
    func firstOffset(of pattern: [Character], from: Int = 0) -> Int {
        guard !pattern.isEmpty, pattern.count <= count else { return -1 }
        var at = Swift.max(0, from)
        while at + pattern.count <= count {
            if self[at..<(at + pattern.count)].elementsEqual(pattern) { return at }
            at += 1
        }
        return -1
    }
}

/// NSRegularExpression kısayolları (desenler derlenirken bir kez kurulur).
enum Patterns {
    static func make(_ pattern: String) -> NSRegularExpression {
        // Desenler kaynakta sabittir; derlenemezse geliştirme hatasıdır.
        try! NSRegularExpression(pattern: pattern)
    }

    static func matches(_ regex: NSRegularExpression, _ text: String) -> Bool {
        regex.firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length)) != nil
    }

    static func count(_ regex: NSRegularExpression, _ text: String) -> Int {
        regex.numberOfMatches(in: text, range: NSRange(location: 0, length: (text as NSString).length))
    }

    static func replace(_ regex: NSRegularExpression, _ text: String, _ template: String) -> String {
        regex.stringByReplacingMatches(in: text, range: NSRange(location: 0, length: (text as NSString).length), withTemplate: template)
    }

    static func firstMatch(_ regex: NSRegularExpression, _ text: String) -> String? {
        let ns = text as NSString
        guard let match = regex.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return ns.substring(with: match.range)
    }
}

/// Tekdüze artan saat, milisaniye (varsayılan saat; testler kendi saatini verir).
public func monotonicMilliseconds() -> Int64 {
    Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
}
