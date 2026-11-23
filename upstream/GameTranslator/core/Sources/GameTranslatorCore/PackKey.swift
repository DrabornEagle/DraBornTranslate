// SPDX-License-Identifier: AGPL-3.0-only
// CouchSubs'tan uyarlandı: CouchSubs (Copyright (C) 2026 Sinan Yorulmaz), AGPL-3.0, yazarının izniyle.

import Foundation

/// Kod noktası sınıfları: Java/.NET'in Character.isLetter, isDigit, isWhitespace karşılıkları (Unicode genel kategorisi).
/// Paket anahtarı, bulanık imza ve gevşek etiket kuralı Windows, Android ve paket betiğiyle aynı sınıfları kullanmalı.
enum Scalars {
    static func isLetter(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: true
        default: false
        }
    }

    /// Ondalık rakam ya da rakam değerli işaret ("²", "①").
    static func isDigit(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .decimalNumber: return true
        case .otherNumber:
            guard let value = scalar.properties.numericValue else { return false }
            return value >= 0 && value <= 9 && value == value.rounded()
        default: return false
        }
    }

    /// Ondalık rakam, harf-sayı (Ⅳ) ve diğer sayılar (½, ²).
    static func isNumber(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .decimalNumber, .letterNumber, .otherNumber: true
        default: false
        }
    }

    static func isSpace(_ scalar: Unicode.Scalar) -> Bool { scalar.properties.isWhitespace }
    static func isUpper(_ scalar: Unicode.Scalar) -> Bool { scalar.properties.isUppercase }
    static func isLower(_ scalar: Unicode.Scalar) -> Bool { scalar.properties.isLowercase }

    static func string(_ scalars: ArraySlice<Unicode.Scalar>) -> String {
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        return String(view)
    }

    static func string(_ scalars: [Unicode.Scalar]) -> String { string(scalars[...]) }
}

/// Oyun paketinin anahtarı: ekranda okunan İngilizce satırın sadeleştirilmiş hâlinin SHA-256'sının ilk 8 baytı (hex).
/// Windows (GamePacks.cs), Android (GamePacks.kt) ve paket hazırlayan Python betiğiyle (canon.py) birebir aynı adımlar;
/// ortak deneme verisi Tests/GameTranslatorCoreTests/Fixtures/golden-vectors.json.
public enum PackKey {
    /// Biçim etiketi: en çok 40 karakterlik `<…>` ("<b>", "</>").
    static let maxTagLength = 40

    /// 1. etiketler boşluk olur, 2. NFKC, 3. küçük harf, 4. NFD ile aksan ve işaretler atılır,
    /// 5. harf ve rakam dışındaki her şey boşluk olur, 6. boşluklar teklenir, baş ve son kırpılır.
    public static func canon(_ text: String) -> String {
        // Swift'in küçük harfi Python'un str.lower()'ı gibi tam eşlemedir: "İ" → "i" + üst nokta (nokta 4. adımda atılır).
        let lowered = stripTags(text).precomposedStringWithCompatibilityMapping.lowercased()
        var output = String.UnicodeScalarView()
        var gap = false
        for scalar in lowered.decomposedStringWithCanonicalMapping.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .nonspacingMark, .spacingMark, .enclosingMark:
                continue
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
                 .decimalNumber, .letterNumber, .otherNumber:
                if gap && !output.isEmpty { output.append(" ") }
                gap = false
                output.append(scalar)
            default:
                gap = true
            }
        }
        return String(output)
    }

    /// 16 haneli küçük harf hex anahtar; satırda harf ya da rakam yoksa nil.
    public static func key(_ text: String) -> String? {
        let canon = canon(text)
        guard !canon.isEmpty else { return nil }
        return Digest.hex(Array(Digest.sha256(Array(canon.utf8)).prefix(8)))
    }

    /// Anahtarın sayı hâli doğrudan (`number(key(text))` ile aynı); harf ya da rakam yoksa nil.
    public static func keyNumber(_ text: String) -> UInt64? {
        canonNumber(canon(text))
    }

    /// Zaten canon olan metnin anahtar sayısı; boşsa nil.
    public static func canonNumber(_ canon: String) -> UInt64? {
        guard !canon.isEmpty else { return nil }
        let hash = Digest.sha256(Array(canon.utf8))
        var value: UInt64 = 0
        for index in 0..<8 { value = value << 8 | UInt64(hash[index]) }
        return value
    }

    /// Anahtarın sayı hâli; 16 haneli hex değilse nil.
    public static func number(_ key: String) -> UInt64? {
        guard key.utf8.count == 16, key.unicodeScalars.allSatisfy({ $0.properties.isASCIIHexDigit }) else { return nil }
        return UInt64(key, radix: 16)
    }

    /// `<…>` etiketlerini (en çok 40 karakter, içinde < ya da > yok) `replacement` ile değiştirir. Python'daki
    /// `re.sub(r"<[^<>]{0,40}>", " ", s)` ile aynı: kod noktası sayılır.
    public static func stripTags(_ text: String, replacement: String = " ") -> String {
        guard text.contains("<") else { return text }
        let points = Array(text.unicodeScalars)
        var output = String.UnicodeScalarView()
        var index = 0
        while index < points.count {
            if points[index] == "<" {
                var end = index + 1
                while end < points.count && end - index - 1 <= maxTagLength && points[end] != "<" && points[end] != ">" { end += 1 }
                if end < points.count && points[end] == ">" && end - index - 1 <= maxTagLength {
                    output.append(contentsOf: replacement.unicodeScalars)
                    index = end + 1
                    continue
                }
            }
            output.append(points[index])
            index += 1
        }
        return String(output)
    }
}
