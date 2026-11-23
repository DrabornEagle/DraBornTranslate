// SPDX-License-Identifier: AGPL-3.0-only
// CouchSubs'tan uyarlandı: CouchSubs (Copyright (C) 2026 Sinan Yorulmaz), AGPL-3.0, yazarının izniyle.

import Foundation

/// Dosya paket değil ya da bozuk.
public struct InvalidPackError: Error, Equatable, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

/// Oyun paketi JSON'unun akış hâlinde okunması (Android: PackJson.kt): `entries` nesnesindeki her satır nesne ağacı
/// kurulmadan geri çağrıya verilir (GTA V paketi tek başına ~190 bin satır). Kökteki dize alanları (`id`, `title` ya da
/// düz biçimde satırların kendisi) ayrıca döner.
enum PackJson {
    struct Result {
        /// Kökte `entries` nesnesi vardı (sarılı biçim).
        var wrapped = false
        var format: Double?
        var id: String?
        var title: String?
        /// Kökteki dize alanları sırayla (düz biçimde satırlar).
        var rootStrings: [(String, String)] = []
        /// Biçim 2'deki "fuzzy" nesnesi (bulanık imzalar); yoksa nil.
        var fuzzy: Fuzzy?
    }

    /// "fuzzy" nesnesi: base64 alanlar baytlara açılır; eksik ya da bozuk alan nil.
    struct Fuzzy {
        var version: Double?
        var count: Double?
        var mask: [UInt8]?
        var lengths: [UInt8]?
        var blooms: [UInt8]?
    }

    /// `onSegment`: biçim 2'deki "segments" nesnesinin satırları (oyunun ayrı gösterdiği parçalar).
    static func parse(_ data: [UInt8], onEntry: (String, String) -> Void, onSegment: (String, String) -> Void = { _, _ in }) throws -> Result {
        var reader = Reader(data)
        return try reader.root(onEntry: onEntry, onSegment: onSegment)
    }

    private struct Reader {
        let data: [UInt8]
        var position = 0

        init(_ data: [UInt8]) {
            self.data = data
            // UTF-8 BOM
            if data.count >= 3 && data[0] == 0xEF && data[1] == 0xBB && data[2] == 0xBF { position = 3 }
        }

        mutating func root(onEntry: (String, String) -> Void, onSegment: (String, String) -> Void) throws -> Result {
            skipSpace()
            guard peek() == UInt8(ascii: "{") else {
                throw InvalidPackError(peek() == UInt8(ascii: "[") ? "Paket JSON nesnesi değil" : "Paket JSON değil")
            }
            position += 1
            var result = Result()
            try members { reader, name in
                switch name {
                case "fuzzy" where reader.peek() == UInt8(ascii: "{"):
                    reader.position += 1
                    result.fuzzy = try reader.fuzzy()
                case "entries" where reader.peek() == UInt8(ascii: "{"):
                    result.wrapped = true
                    reader.position += 1
                    try reader.members { inner, key in
                        if inner.peek() == UInt8(ascii: "\"") { onEntry(key, try inner.string()) } else { try inner.skipValue() }
                    }
                case "segments" where reader.peek() == UInt8(ascii: "{"):
                    reader.position += 1
                    try reader.members { inner, key in
                        if inner.peek() == UInt8(ascii: "\"") { onSegment(key, try inner.string()) } else { try inner.skipValue() }
                    }
                case "format" where reader.isNumberStart(reader.peek()):
                    result.format = try reader.number()
                default:
                    if reader.peek() == UInt8(ascii: "\"") { result.rootStrings.append((name, try reader.string())) } else { try reader.skipValue() }
                }
            }
            skipSpace()
            guard position == data.count else { throw InvalidPackError("Paket JSON değil") }
            result.id = result.rootStrings.last { $0.0 == "id" }?.1
            result.title = result.rootStrings.last { $0.0 == "title" }?.1
            return result
        }

        /// `{` okunmuş "fuzzy" nesnesi.
        private mutating func fuzzy() throws -> Fuzzy {
            var fuzzy = Fuzzy()
            try members { reader, name in
                if (name == "version" || name == "count") && reader.isNumberStart(reader.peek()) {
                    let value = try reader.number()
                    if name == "version" { fuzzy.version = value } else { fuzzy.count = value }
                } else if (name == "mask" || name == "lengths" || name == "blooms") && reader.peek() == UInt8(ascii: "\"") {
                    let bytes = try reader.base64()
                    switch name {
                    case "mask": fuzzy.mask = bytes
                    case "lengths": fuzzy.lengths = bytes
                    default: fuzzy.blooms = bytes
                    }
                } else {
                    try reader.skipValue()
                }
            }
            return fuzzy
        }

        /// base64 dize; bozuksa nil.
        private mutating func base64() throws -> [UInt8]? {
            let text = try string()
            return Data(base64Encoded: text).map { [UInt8]($0) }
        }

        /// `{` okunmuş nesnenin üyeleri: her ad için `value` değeri okumalıdır; `}` ile biter.
        private mutating func members(_ value: (inout Reader, String) throws -> Void) throws {
            skipSpace()
            if peek() == UInt8(ascii: "}") {
                position += 1
                return
            }
            while true {
                skipSpace()
                guard peek() == UInt8(ascii: "\"") else { throw fail() }
                let name = try string()
                skipSpace()
                try expect(UInt8(ascii: ":"))
                skipSpace()
                try value(&self, name)
                skipSpace()
                switch try next() {
                case UInt8(ascii: ","): continue
                case UInt8(ascii: "}"): return
                default: throw fail()
                }
            }
        }

        private mutating func skipValue() throws {
            skipSpace()
            let c = peek()
            switch c {
            case UInt8(ascii: "\""): _ = try string()
            case UInt8(ascii: "{"):
                position += 1
                try members { reader, _ in try reader.skipValue() }
            case UInt8(ascii: "["):
                position += 1
                skipSpace()
                if peek() == UInt8(ascii: "]") {
                    position += 1
                    return
                }
                while true {
                    try skipValue()
                    skipSpace()
                    switch try next() {
                    case UInt8(ascii: ","): continue
                    case UInt8(ascii: "]"): return
                    default: throw fail()
                    }
                }
            case UInt8(ascii: "t"): try literal("true")
            case UInt8(ascii: "f"): try literal("false")
            case UInt8(ascii: "n"): try literal("null")
            default:
                if isNumberStart(c) { _ = try number() } else { throw fail() }
            }
        }

        private mutating func string() throws -> String {
            try expect(UInt8(ascii: "\""))
            // Kaçışsız dize kaynak baytlardan doğrudan çözülür.
            let begin = position
            while position < data.count, data[position] != UInt8(ascii: "\""), data[position] != UInt8(ascii: "\\"), data[position] >= 0x20 {
                position += 1
            }
            guard position < data.count else { throw fail() }
            if data[position] == UInt8(ascii: "\"") {
                let text = String(decoding: data[begin..<position], as: UTF8.self)
                position += 1
                return text
            }
            var buffer = Array(data[begin..<position])
            var pendingHigh: UInt32?
            while true {
                let b = try next()
                if b == UInt8(ascii: "\"") { break }
                if b == UInt8(ascii: "\\") {
                    let escape = try next()
                    var unit: UInt32
                    switch escape {
                    case UInt8(ascii: "\""): unit = 0x22
                    case UInt8(ascii: "\\"): unit = 0x5C
                    case UInt8(ascii: "/"): unit = 0x2F
                    case UInt8(ascii: "b"): unit = 8
                    case UInt8(ascii: "f"): unit = 12
                    case UInt8(ascii: "n"): unit = 10
                    case UInt8(ascii: "r"): unit = 13
                    case UInt8(ascii: "t"): unit = 9
                    case UInt8(ascii: "u"):
                        guard position + 4 <= data.count, let value = UInt32(String(decoding: data[position..<(position + 4)], as: UTF8.self), radix: 16)
                        else { throw fail() }
                        position += 4
                        unit = value
                    default: throw fail()
                    }
                    if (0xD800...0xDBFF).contains(unit) {
                        pendingHigh = unit
                        continue
                    }
                    if let high = pendingHigh, (0xDC00...0xDFFF).contains(unit) {
                        unit = 0x10000 + ((high - 0xD800) << 10) + (unit - 0xDC00)
                    }
                    pendingHigh = nil
                    let scalar = Unicode.Scalar(unit) ?? "\u{FFFD}"
                    buffer.append(contentsOf: Array(String(Character(scalar)).utf8))
                    continue
                }
                if b < 0x20 { throw fail() }
                buffer.append(b)
                pendingHigh = nil
            }
            return String(decoding: buffer, as: UTF8.self)
        }

        private mutating func number() throws -> Double {
            let start = position
            while position < data.count {
                let c = data[position]
                guard (c >= UInt8(ascii: "0") && c <= UInt8(ascii: "9")) || c == UInt8(ascii: "+") || c == UInt8(ascii: "-")
                        || c == UInt8(ascii: ".") || c == UInt8(ascii: "e") || c == UInt8(ascii: "E") else { break }
                position += 1
            }
            guard let value = Double(String(decoding: data[start..<position], as: UTF8.self)) else { throw fail() }
            return value
        }

        private mutating func literal(_ word: String) throws {
            for c in word.utf8 {
                if try next() != c { throw fail() }
            }
        }

        func isNumberStart(_ c: UInt8?) -> Bool {
            guard let c else { return false }
            return c == UInt8(ascii: "-") || (c >= UInt8(ascii: "0") && c <= UInt8(ascii: "9"))
        }

        private mutating func skipSpace() {
            while position < data.count {
                let c = data[position]
                if c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 { position += 1 } else { break }
            }
        }

        func peek() -> UInt8? { position < data.count ? data[position] : nil }

        private mutating func next() throws -> UInt8 {
            guard position < data.count else { throw fail() }
            position += 1
            return data[position - 1]
        }

        private mutating func expect(_ c: UInt8) throws {
            if try next() != c { throw fail() }
        }

        private func fail() -> InvalidPackError { InvalidPackError("Paket JSON değil") }
    }
}
