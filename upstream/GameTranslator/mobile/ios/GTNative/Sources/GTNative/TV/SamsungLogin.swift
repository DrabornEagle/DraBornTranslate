// SPDX-License-Identifier: AGPL-3.0-only
// Giriş akışı Apps2Samsung'dan (MIT, Patrick Stel) uyarlandı: https://github.com/Jellyfin2Samsung/Samsung-Jellyfin-Installer
// Android'deki SamsungLogin.kt'nin karşılığı.

import Darwin
import Foundation

/// Samsung hesabıyla giriş. Samsung'un SignInGate sayfası (uygulama içi tarayıcıda açılır) giriş bitince jetonu
/// `http://localhost:4794/signin/callback` adresine POST eder; bu adreste telefonun kendisinde küçük bir dinleyici
/// bekler. Tizen Studio'nun sertifika eklentisi de aynı yolu kullanır. Şifre bu uygulamaya hiç gelmez; yalnız Samsung'un
/// verdiği erişim jetonu gelir.
enum SamsungLogin {
    private static let signInGate = "https://account.samsung.com/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate"
    private static let clientId = "v285zxnl3h"
    private static let state = "accountcheckdogeneratedstatetext"
    static let callbackPort = 4794
    static let callbackPath = "/signin/callback"

    static var authorizeUrl: String {
        "\(signInGate)?locale=&clientId=\(clientId)&redirect_uri=\(formEncode("http://localhost:\(callbackPort)\(callbackPath)"))" +
            "&state=\(state)&tokenType=TOKEN"
    }

    /// Java'nın URLEncoder.encode'u gibi: harf, rakam ve `.-*_` kalır, boşluk "+" olur, geri kalanı %XX.
    static func formEncode(_ text: String) -> String {
        var out = ""
        for byte in text.utf8 {
            switch byte {
            case UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "."), UInt8(ascii: "-"), UInt8(ascii: "*"), UInt8(ascii: "_"):
                out.unicodeScalars.append(UnicodeScalar(byte))
            case UInt8(ascii: " "):
                out += "+"
            default:
                out += String(format: "%%%02X", byte)
            }
        }
        return out
    }

    /// Gövde: `code=<JSON>&state=…`. JSON'da access_token, userId ve inputEmailID bulunur.
    static func parseCallback(_ body: String) -> SamsungAccount? {
        var receivedState: String?
        var code: String?
        for part in body.split(separator: "&", omittingEmptySubsequences: true) {
            guard let eq = part.firstIndex(of: "=") else { continue }
            let name = part[..<eq]
            let value = String(part[part.index(after: eq)...])
            // .NET: state WebUtility.UrlDecode ("+" boşluk olur), code Uri.UnescapeDataString ("+" kalır).
            if name == "state" { receivedState = percentDecode(value.replacingOccurrences(of: "+", with: " ")) }
            if name == "code" { code = percentDecode(value) }
        }
        guard receivedState == state, let code, !code.trimmingCharacters(in: .whitespaces).isEmpty,
              let json = (try? JSONSerialization.jsonObject(with: Data(code.utf8))) as? [String: Any],
              let token = json["access_token"] as? String, !token.isEmpty,
              let user = json["userId"] as? String, !user.isEmpty else { return nil }
        return SamsungAccount(accessToken: token, userId: user, email: json["inputEmailID"] as? String ?? "")
    }

    /// Yüzde kaçışlarını UTF-8 olarak çözer; "+" olduğu gibi kalır. Bozuk kaçış olduğu gibi bırakılır.
    static func percentDecode(_ text: String) -> String {
        let bytes = Array(text.utf8)
        var out: [UInt8] = []
        var i = 0
        while i < bytes.count {
            if bytes[i] == UInt8(ascii: "%"), i + 2 < bytes.count,
               let hex = UInt8(String(decoding: bytes[(i + 1)...(i + 2)], as: UTF8.self), radix: 16) {
                out.append(hex)
                i += 3
                continue
            }
            out.append(bytes[i])
            i += 1
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// HTTP isteğinin satırı ve gövdesi.
    struct Request {
        let method: String
        let path: String
        let body: String
    }

    static func readRequest(_ input: TvByteInput) throws -> Request? {
        var received = Data()
        var headerEnd: Int
        while true {
            headerEnd = indexOfHeaderEnd(received)
            if headerEnd >= 0 { break }
            let chunk = try input.readSome(16384)
            if chunk.isEmpty || received.count > 1024 * 1024 { return nil }
            received.append(chunk)
        }
        let head = String(decoding: received.prefix(headerEnd), as: UTF8.self)
        let lines = head.components(separatedBy: "\r\n")
        let request = lines[0].split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        var length = 0
        for line in lines where line.lowercased().hasPrefix("content-length:") {
            length = Int(line.dropFirst(15).trimmingCharacters(in: .whitespaces)) ?? 0
        }
        var body = Data(received.dropFirst(headerEnd + 4))
        while body.count < length && body.count < 1024 * 1024 {
            let chunk = try input.readSome(16384)
            if chunk.isEmpty { break }
            body.append(chunk)
        }
        return Request(method: request.first ?? "", path: request.count > 1 ? request[1] : "", body: String(decoding: body, as: UTF8.self))
    }

    private static func indexOfHeaderEnd(_ data: Data) -> Int {
        let bytes = [UInt8](data)
        guard bytes.count >= 4 else { return -1 }
        for i in 0..<(bytes.count - 3) where bytes[i] == 13 && bytes[i + 1] == 10 && bytes[i + 2] == 13 && bytes[i + 3] == 10 { return i }
        return -1
    }

    /// Giriş bitince tarayıcıda görünen son sayfanın metinleri (Android: UiText "samsung.done.*").
    static let doneTexts: [String: (title: String, text: String)] = [
        "tr": ("Giriş tamam", "GameTranslator'a dönebilirsin; kurulum kendiliğinden sürüyor."),
        "en": ("Signed in", "You can go back to GameTranslator; the installation continues on its own."),
    ]

    static func doneText(_ setting: String) -> (title: String, text: String) {
        let system = Locale.current.language.languageCode?.identifier ?? "en"
        let language = doneTexts[setting] != nil ? setting : doneTexts[system.lowercased()] != nil ? system.lowercased() : "en"
        return doneTexts[language] ?? doneTexts["en"]!
    }

    fileprivate static func respond(_ output: TvByteOutput, _ status: Int, _ signedIn: Bool, language: String) {
        let page: String
        if signedIn {
            let done = doneText(language)
            page = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\"><title>GameTranslator</title>" +
                "<body style=\"font-family:system-ui;padding:2em;background:#000;color:#fff\"><h2>\(done.title)</h2><p>\(done.text)</p>"
        } else {
            page = "Not found"
        }
        let reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : "Not Found"
        let body = Data(page.utf8)
        let header = "HTTP/1.1 \(status) \(reason)\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n"
        try? output.write(Data(header.utf8) + body)
    }

    /// Geri dönüş adresini dinler. `start` dinleyiciyi açar (port doluysa `SIGN_IN_FAILED`); `await` giriş gelene, süre
    /// dolana, `abort` ya da vazgeçme olana kadar bekler. Yalnız bu telefonun kendisinden gelen bağlantı kabul edilir
    /// (127.0.0.1 ve ::1).
    final class Listener: @unchecked Sendable {
        private var sockets: [Int32] = []
        private let condition = NSCondition()
        private var results: [Result<SamsungAccount, TvInstallError>] = []
        private let closed = TvLocked(false)
        private let language: String

        init(language: String = "auto") { self.language = language }

        deinit { close() }

        @discardableResult
        func start() throws -> Listener {
            if let fd = Self.bind(ipv6: false) {
                sockets.append(fd)
            } else {
                let reason = String(cString: strerror(errno))
                close()
                throw TvInstallError(.SIGN_IN_FAILED, "port \(callbackPort): \(reason)")
            }
            // IPv6 yoksa yalnız IPv4 dinlenir.
            if let fd = Self.bind(ipv6: true) { sockets.append(fd) }
            for fd in sockets {
                let thread = Thread { [weak self] in self?.acceptLoop(fd) }
                thread.name = "samsung-login"
                thread.start()
            }
            return self
        }

        private static func bind(ipv6: Bool) -> Int32? {
            let fd = socket(ipv6 ? AF_INET6 : AF_INET, SOCK_STREAM, IPPROTO_TCP)
            guard fd >= 0 else { return nil }
            var one: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            let bound: Int32
            if ipv6 {
                setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &one, socklen_t(MemoryLayout<Int32>.size))
                var address = sockaddr_in6()
                address.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
                address.sin6_family = sa_family_t(AF_INET6)
                address.sin6_port = in_port_t(UInt16(callbackPort)).bigEndian
                address.sin6_addr = in6addr_loopback
                bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in6>.size)) } }
            } else {
                var address = sockaddr_in()
                address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                address.sin_family = sa_family_t(AF_INET)
                address.sin_port = in_port_t(UInt16(callbackPort)).bigEndian
                address.sin_addr.s_addr = inet_addr("127.0.0.1")
                bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
            }
            guard bound == 0, listen(fd, 8) == 0 else {
                let saved = errno
                Darwin.close(fd)
                errno = saved
                return nil
            }
            return fd
        }

        private func acceptLoop(_ server: Int32) {
            // macOS/iOS'ta soketi kapatmak bekleyen accept'i uyandırmaz: kısa aralıklarla yoklanır.
            while !closed.get {
                var poller = pollfd(fd: server, events: Int16(POLLIN), revents: 0)
                let ready = poll(&poller, 1, 200)
                if closed.get { return }
                if ready <= 0 { continue }
                if poller.revents & Int16(POLLNVAL) != 0 { return }
                let client = accept(server, nil, nil)
                if client < 0 {
                    if closed.get || errno == EBADF || errno == EINVAL { return }
                    continue
                }
                let connection = AcceptedSocket(client)
                var timeout = timeval(tv_sec: 15, tv_usec: 0)
                setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
                var one: Int32 = 1
                setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
                // Bozuk istek: dinlemeye devam.
                if let request = try? readRequest(connection) {
                    let isCallback = request.method == "POST" && request.path.hasPrefix(callbackPath)
                    let account = isCallback ? parseCallback(request.body) : nil
                    respond(connection, account != nil ? 200 : isCallback ? 400 : 404, account != nil, language: language)
                    if let account { offer(.success(account)) }
                }
                connection.close()
            }
        }

        private func offer(_ result: Result<SamsungAccount, TvInstallError>) {
            condition.lock()
            results.append(result)
            condition.broadcast()
            condition.unlock()
        }

        /// Girişi bekler. Süre dolarsa ya da `abort` çağrılırsa `SIGN_IN_FAILED`.
        func await(timeoutMs: Int, cancel: Cancel) throws -> SamsungAccount {
            let end = Date().addingTimeInterval(Double(timeoutMs) / 1000)
            while true {
                try cancel.check()
                if Date() >= end { throw TvInstallError(.SIGN_IN_FAILED, "zaman aşımı") }
                condition.lock()
                if results.isEmpty { condition.wait(until: min(end, Date().addingTimeInterval(0.2))) }
                let result = results.isEmpty ? nil : results.removeFirst()
                condition.unlock()
                if let result { return try result.get() }
            }
        }

        func abort(_ reason: String) { offer(.failure(TvInstallError(.SIGN_IN_FAILED, reason))) }

        func close() {
            let wasOpen = closed.update { value -> Bool in
                let open = !value
                value = true
                return open
            }
            guard wasOpen else { return }
            for fd in sockets {
                shutdown(fd, SHUT_RDWR)
                Darwin.close(fd)
            }
            sockets.removeAll()
        }
    }
}

/// Dinleyicinin kabul ettiği bağlantı.
private final class AcceptedSocket: TvByteInput, TvByteOutput {
    private let fd: Int32

    init(_ fd: Int32) { self.fd = fd }

    func readSome(_ max: Int) throws -> Data {
        var buffer = [UInt8](repeating: 0, count: max)
        while true {
            let n = recv(fd, &buffer, max, 0)
            if n >= 0 { return Data(buffer[0..<n]) }
            if errno == EINTR { continue }
            throw TvIOError.closed(String(cString: strerror(errno)))
        }
    }

    func write(_ data: Data) throws {
        try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let base = raw.baseAddress else { return }
            var offset = 0
            while offset < raw.count {
                let n = send(fd, base + offset, raw.count - offset, 0)
                if n < 0 {
                    if errno == EINTR { continue }
                    throw TvIOError.closed(String(cString: strerror(errno)))
                }
                offset += n
            }
        }
    }

    func close() {
        shutdown(fd, SHUT_WR)
        Darwin.close(fd)
    }
}
