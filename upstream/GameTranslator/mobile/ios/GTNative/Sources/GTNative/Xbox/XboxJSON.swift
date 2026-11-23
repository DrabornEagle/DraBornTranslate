// SPDX-License-Identifier: AGPL-3.0-only
// Xbox isteklerinin JSON'u. Android'deki org.json'un iki davranışı burada da korunur: gönderilen nesnelerin alan
// sırası yazıldığı gibidir (oynatıcının ve köprünün gönderdiği sırayla), okunan alanlar `optString`/`optInt` gibi
// gevşek okunur (sayı gelen yerde metin, metin gelen yerde sayı kabul edilir).

import Foundation

/// Alan sırası korunan JSON nesnesi (yalnız yazmak için). Değerler: String, Int, Int64, Double, Bool, NSNull, [Any],
/// [String: Any] (anahtarlar sıralanır) ya da yine `XboxJSONObject`.
struct XboxJSONObject {
    var pairs: [(String, Any)]

    init(_ pairs: KeyValuePairs<String, Any>) {
        self.pairs = pairs.map { ($0.key, $0.value) }
    }

    init(pairs: [(String, Any)]) {
        self.pairs = pairs
    }

    var text: String {
        var out = ""
        XboxJSON.write(self, to: &out)
        return out
    }
}

enum XboxJSON {
    static func write(_ value: Any?, to out: inout String) {
        switch value {
        case nil, is NSNull:
            out += "null"
        case let object as XboxJSONObject:
            out += "{"
            for (index, pair) in object.pairs.enumerated() {
                if index > 0 { out += "," }
                writeString(pair.0, to: &out)
                out += ":"
                write(pair.1, to: &out)
            }
            out += "}"
        case let dictionary as [String: Any]:
            write(XboxJSONObject(pairs: dictionary.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }), to: &out)
        case let array as [Any]:
            out += "["
            for (index, item) in array.enumerated() {
                if index > 0 { out += "," }
                write(item, to: &out)
            }
            out += "]"
        case let text as String:
            writeString(text, to: &out)
        case let flag as Bool:
            out += flag ? "true" : "false"
        case let number as Int:
            out += String(number)
        case let number as Int64:
            out += String(number)
        case let number as Int32:
            out += String(number)
        case let number as Double:
            out += number.rounded() == number && abs(number) < 1e15 ? String(Int64(number)) : String(number)
        case let number as NSNumber:
            out += number.stringValue
        default:
            writeString("\(value!)", to: &out)
        }
    }

    /// JSON.stringify gibi: `"`, `\` ve denetim karakterleri kaçırılır ("/" kaçırılmaz).
    static func writeString(_ text: String, to out: inout String) {
        out += "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
    }

    /// Okunan herhangi bir JSON değerinin metni (org.json `toString`): nesne ve dizi JSON olarak, metin olduğu gibi.
    static func text(of value: Any) -> String {
        if let string = value as? String { return string }
        if value is [String: Any] || value is [Any] {
            if let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .withoutEscapingSlashes]) {
                return String(decoding: data, as: UTF8.self)
            }
        }
        var out = ""
        write(value, to: &out)
        return out
    }

    /// Metinden nesne; boşsa boş nesne. Okunamazsa `XboxException(.service)` (Android: `parseObject`).
    static func parseObject(_ text: String) throws -> [String: Any] {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return [:] }
        guard let value = try? JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed]),
              let object = value as? [String: Any] else {
            throw XboxException(.service, "Xbox yanıtı okunamadı")
        }
        return object
    }

    /// Okunamazsa nil (sessiz).
    static func object(_ text: String?) -> [String: Any]? {
        guard let text, let data = text.data(using: .utf8),
              let value = try? JSONSerialization.jsonObject(with: data) else { return nil }
        return value as? [String: Any]
    }

    static func array(_ text: String) -> [Any]? {
        guard let value = try? JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed]) else { return nil }
        return value as? [Any]
    }
}

/// org.json'un gevşek okuyucuları (`optString`, `optInt`, `optLong`, `optBoolean`).
extension Dictionary where Key == String, Value == Any {
    func optString(_ key: String, _ fallback: String = "") -> String {
        guard let value = self[key], !(value is NSNull) else { return fallback }
        if let text = value as? String { return text }
        if let number = value as? NSNumber {
            // JSONSerialization'ın Bool'u da NSNumber'dır.
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue ? "true" : "false" }
            return number.stringValue
        }
        return XboxJSON.text(of: value)
    }

    func optLong(_ key: String, _ fallback: Int64 = 0) -> Int64 {
        guard let value = self[key], !(value is NSNull) else { return fallback }
        if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() { return number.int64Value }
        if let text = value as? String {
            if let whole = Int64(text.trimmingCharacters(in: .whitespaces)) { return whole }
            if let double = Double(text.trimmingCharacters(in: .whitespaces)), double.isFinite, abs(double) < 9.2e18 { return Int64(double) }
        }
        return fallback
    }

    func optInt(_ key: String, _ fallback: Int = 0) -> Int {
        guard self[key] != nil else { return fallback }
        let sentinel = Int64.min
        let value = optLong(key, sentinel)
        return value == sentinel ? fallback : Int(truncatingIfNeeded: value)
    }

    func optBool(_ key: String, _ fallback: Bool = false) -> Bool {
        guard let value = self[key] else { return fallback }
        if let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue }
        if let text = value as? String {
            if text.caseInsensitiveCompare("true") == .orderedSame { return true }
            if text.caseInsensitiveCompare("false") == .orderedSame { return false }
        }
        return fallback
    }

    func optObject(_ key: String) -> [String: Any]? { self[key] as? [String: Any] }
    func optArray(_ key: String) -> [Any]? { self[key] as? [Any] }

    /// Anahtar var ve null değil.
    func hasValue(_ key: String) -> Bool {
        guard let value = self[key] else { return false }
        return !(value is NSNull)
    }
}
