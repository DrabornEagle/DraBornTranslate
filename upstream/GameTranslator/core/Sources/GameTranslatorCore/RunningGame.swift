// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
#if canImport(Darwin)
import Darwin
#endif

/// PS5'te o an çalışan oyun: keşif yanıtındaki `running-app-titleid` ve `running-app-name`. Ortak havuza giden satıra
/// eklenir. Windows'taki RunningGame.cs ve Android'deki RunningGame.kt ile aynı kurallar (sunucu da aynısını doğrular).
public struct RunningGame: Equatable, Sendable {
    public static let maxName = 120
    private static let titleIdPattern = try! NSRegularExpression(pattern: "^[A-Z]{4}[0-9]{5}(_[0-9]{2})?$")

    public let titleId: String
    public let name: String?

    public init(titleId: String, name: String?) {
        self.titleId = titleId
        self.name = name
    }

    /// Kimlik PS5 biçiminde değilse nil.
    public static func create(titleId: String?, name: String?) -> RunningGame? {
        guard let id = titleId?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() else { return nil }
        guard titleIdPattern.firstMatch(in: id, range: NSRange(location: 0, length: (id as NSString).length)) != nil else { return nil }
        return RunningGame(titleId: id, name: normalizedName(name))
    }

    /// Denetim ve biçim karakterleri ile fazla boşluk atılır, en çok 120 karakter; boşsa nil.
    public static func normalizedName(_ value: String?) -> String? {
        guard let value else { return nil }
        var cleaned = String.UnicodeScalarView()
        for scalar in value.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .control, .format: cleaned.append(" ")
            default: cleaned.append(scalar)
            }
        }
        // C#'taki string.Split(null): her türlü boşluk karakteri ayırır.
        let words = String(cleaned).unicodeScalars.split(whereSeparator: { $0.properties.isWhitespace }).map { Scalars.string(Array($0)) }
        let all = words.joined(separator: " ")
        // Kesim UTF-16 birimiyle (Windows ve Android ile aynı uzunluk).
        let utf16 = Array(all.utf16)
        let cut = utf16.count > maxName ? String(decoding: utf16[0..<maxName], as: UTF16.self) : all
        let trimmed = cut.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

/// PS5'e keşif sorusu (UDP 9302, "SRCH"): açık mı ve o an hangi oyun çalışıyor (Windows: Ps5Status.cs). Yanıt
/// satırlarından `running-app-titleid` ve `running-app-name` okunur (chiaki-ng discovery.c ile aynı biçim). Soru PS5'i
/// uyandırmaz; dinlenmedeyse yanıt 620 koduyla gelir ya da hiç gelmez.
public enum Ps5Status {
    public static let port: UInt16 = 9302
    static let request = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010\n"
    private static let ipv4 = try! NSRegularExpression(pattern: "^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")

    /// `awake`: yanıt kodu 200 (açık); 620 dinlenmede.
    public struct Info: Equatable, Sendable {
        public let awake: Bool
        public let hostName: String?
        public let game: RunningGame?
    }

    /// "HTTP/1.1 200 Ok" ve "anahtar:değer" satırları; ilk satır HTTP değilse nil.
    public static func parse(_ text: String) -> Info? {
        let lines = text.replacingOccurrences(of: "\r", with: "").components(separatedBy: "\n")
        guard let first = lines.first, first.hasPrefix("HTTP/") else { return nil }
        let parts = first.split(separator: " ", maxSplits: 2)
        let awake = parts.count > 1 && parts[1] == "200"
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { continue }
            headers[line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()] =
                line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        return Info(awake: awake, hostName: headers["host-name"],
                    game: RunningGame.create(titleId: headers["running-app-titleid"], name: headers["running-app-name"]))
    }

    static func isAddress(_ host: String) -> Bool {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        return ipv4.firstMatch(in: trimmed, range: NSRange(location: 0, length: (trimmed as NSString).length)) != nil
    }

    #if canImport(Darwin)
    /// PS5'in yanıtı (bloklar, en çok `timeoutMs`); yanıt gelmezse (kapalı, ağda değil) ya da okunamazsa nil.
    /// Ad çözümlemesi yapılmaz: kayıtta hep IP adresi durur.
    public static func query(host: String, timeoutMs: Int) -> Info? {
        guard isAddress(host) else { return nil }
        let address = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        var target = sockaddr_in()
        target.sin_family = sa_family_t(AF_INET)
        target.sin_port = port.bigEndian
        target.sin_addr.s_addr = inet_addr(address)
        let sent = request.withCString { pointer in
            withUnsafePointer(to: &target) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(fd, pointer, strlen(pointer), 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        guard sent > 0 else { return nil }
        let deadline = Date().addingTimeInterval(Double(timeoutMs) / 1000)
        var buffer = [UInt8](repeating: 0, count: 2048)
        while true {
            let left = deadline.timeIntervalSinceNow
            if left <= 0 { return nil }
            var wait = timeval(tv_sec: Int(left), tv_usec: Int32((left - Double(Int(left))) * 1_000_000))
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &wait, socklen_t(MemoryLayout<timeval>.size))
            var from = sockaddr_in()
            var length = socklen_t(MemoryLayout<sockaddr_in>.size)
            let count = withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, &buffer, buffer.count, 0, $0, &length) }
            }
            if count <= 0 { return nil }
            // Başka bir cihazın yanıtı sayılmaz.
            if from.sin_addr.s_addr != target.sin_addr.s_addr { continue }
            return parse(String(decoding: buffer[0..<count], as: UTF8.self))
        }
    }
    #endif
}
