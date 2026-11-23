// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Xbox ICE adaylarındaki Teredo adresi (2001:0::/32): istemcinin IPv4 adresi ve UDP bağlantı noktası adresin içinde
/// ters çevrilmiş (bit bit tümleyen) durur. Windows köprüsü (ip-address `inspectTeredo`) bunlardan IPv4 adayı türetir
/// (Android: xbox/Teredo.kt).
enum Teredo {
    struct Info: Equatable {
        let client4: String
        let udpPort: Int
    }

    /// `address` IPv6 değilse nil.
    static func inspect(_ address: String) -> Info? {
        guard let groups = parseIpv6(address) else { return nil }
        let port = ~UInt32(groups[5]) & 0xffff
        let client = ~((UInt32(groups[6]) << 16) | UInt32(groups[7]))
        let ip = [24, 16, 8, 0].map { String((client >> UInt32($0)) & 0xff) }.joined(separator: ".")
        return Info(client4: ip, udpPort: Int(port))
    }

    /// Sekiz 16 bitlik grup; "::" kısaltması açılır. Geçersizse nil.
    static func parseIpv6(_ address: String) -> [Int]? {
        var text = address.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("[") { text.removeFirst() }
        if text.hasSuffix("]") { text.removeLast() }
        if let percent = text.firstIndex(of: "%") { text = String(text[..<percent]) }
        if text.isEmpty || text.filter({ $0 == ":" }).count < 2 { return nil }
        let halves = text.components(separatedBy: "::")
        if halves.count > 2 { return nil }
        func groups(_ part: String) -> [Int]? {
            if part.isEmpty { return [] }
            var out: [Int] = []
            for group in part.split(separator: ":", omittingEmptySubsequences: false) {
                guard (1...4).contains(group.count), group.allSatisfy(\.isHexDigit), let value = Int(group, radix: 16) else { return nil }
                out.append(value)
            }
            return out
        }
        guard let head = groups(halves[0]) else { return nil }
        var tail: [Int] = []
        if halves.count == 2 {
            guard let parsed = groups(halves[1]) else { return nil }
            tail = parsed
        }
        let missing = 8 - head.count - tail.count
        if halves.count == 1 && missing != 0 { return nil }
        if halves.count == 2 && missing < 1 { return nil }
        return head + Array(repeating: 0, count: max(missing, 0)) + tail
    }
}
