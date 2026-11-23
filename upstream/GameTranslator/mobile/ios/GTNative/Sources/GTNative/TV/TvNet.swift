// SPDX-License-Identifier: AGPL-3.0-only

import Darwin
import Foundation

/// Bayt okuyan kaynak: soket, testlerde bellekteki boru. Boş dönüş akışın bittiğini söyler.
protocol TvByteInput: AnyObject {
    func readSome(_ max: Int) throws -> Data
}

/// Bayt yazan hedef.
protocol TvByteOutput: AnyObject {
    func write(_ data: Data) throws
}

/// Kaynaktan tam sayıda bayt ya da satır okuyan tamponlu okuyucu (Kotlin'deki BufferedInputStream + Adb.readExactly).
final class TvReader {
    private let input: TvByteInput
    private var buffer = Data()

    init(_ input: TvByteInput) { self.input = input }

    /// Tamponda yoksa kaynaktan okur; akış bittiyse false.
    private func fill() throws -> Bool {
        let chunk = try input.readSome(16384)
        if chunk.isEmpty { return false }
        buffer.append(chunk)
        return true
    }

    func readExactly(_ count: Int) throws -> Data {
        while buffer.count < count {
            if try !fill() { throw TvIOError.closed("bağlantı kapandı") }
        }
        let out = Data(buffer.prefix(count))
        buffer.removeFirst(count)
        return out
    }

    /// Bir bayt; akış bittiyse nil.
    func readByte() throws -> UInt8? {
        if buffer.isEmpty, try !fill() { return nil }
        return buffer.removeFirst()
    }

    /// Eldeki ya da gelen ilk parça (en çok `max` bayt); akış bittiyse boş.
    func readSome(_ max: Int) throws -> Data {
        if buffer.isEmpty, try !fill() { return Data() }
        let out = Data(buffer.prefix(max))
        buffer.removeFirst(out.count)
        return out
    }
}

/// Bellekteki bir veriyi okutan kaynak (ayrıştırma denemeleri için).
final class TvDataInput: TvByteInput {
    private var data: Data

    init(_ data: Data) { self.data = data }

    func readSome(_ max: Int) throws -> Data {
        let out = Data(data.prefix(max))
        data.removeFirst(out.count)
        return out
    }
}

/// Bloklayan TCP soketi (Kotlin'deki java.net.Socket). `abort` başka bir iş parçacığında bekleyen okumayı hemen
/// sonlandırır (vazgeçme için).
final class TvSocket: TvByteInput, TvByteOutput, @unchecked Sendable {
    private let lock = NSLock()
    private var fd: Int32
    private var closed = false

    private init(fd: Int32) { self.fd = fd }

    deinit { close() }

    /// Bağlanır; `timeoutMs` içinde bağlanamazsa hata verir. `cancel` bağlanırken de soketi kapatabilir.
    static func connect(host: String, port: Int, timeoutMs: Int, cancel: Cancel? = nil) throws -> TvSocket {
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        hints.ai_flags = AI_NUMERICSERV
        var result: UnsafeMutablePointer<addrinfo>?
        let status = getaddrinfo(host, String(port), &hints, &result)
        guard status == 0, let first = result else {
            throw TvIOError.failed("adres çözülemedi: \(host) (\(String(cString: gai_strerror(status))))")
        }
        defer { freeaddrinfo(result) }
        var lastError = "bağlanılamadı"
        for info in sequence(first: first, next: { $0.pointee.ai_next }) {
            let fd = socket(info.pointee.ai_family, info.pointee.ai_socktype, info.pointee.ai_protocol)
            if fd < 0 { continue }
            let socket = TvSocket(fd: fd)
            var one: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
            let tracked = try cancel?.track { socket.abort() }
            defer { if let tracked { cancel?.untrack(tracked) } }
            let flags = fcntl(fd, F_GETFL, 0)
            _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)
            let started = Darwin.connect(fd, info.pointee.ai_addr, info.pointee.ai_addrlen)
            if started != 0 && errno != EINPROGRESS {
                lastError = String(cString: strerror(errno))
                socket.close()
                continue
            }
            if started != 0 {
                var poller = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
                let ready = poll(&poller, 1, Int32(max(1, timeoutMs)))
                if ready <= 0 {
                    lastError = ready == 0 ? "bağlantı zaman aşımı" : String(cString: strerror(errno))
                    socket.close()
                    if ready == 0 { throw TvIOError.timeout }
                    continue
                }
                var error: Int32 = 0
                var length = socklen_t(MemoryLayout<Int32>.size)
                getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length)
                if error != 0 {
                    lastError = String(cString: strerror(error))
                    socket.close()
                    continue
                }
            }
            _ = fcntl(fd, F_SETFL, flags & ~O_NONBLOCK)
            if cancel?.cancelled == true {
                socket.close()
                throw TvInstallError(.CANCELLED)
            }
            return socket
        }
        throw TvIOError.failed(lastError)
    }

    /// Port bağlantı kabul ediyor mu?
    static func isPortOpen(_ host: String, _ port: Int, timeoutMs: Int) -> Bool {
        guard let socket = try? connect(host: host, port: port, timeoutMs: timeoutMs) else { return false }
        socket.close()
        return true
    }

    /// Okuma zaman aşımı (milisaniye; 0 sınırsız).
    func setReadTimeout(_ millis: Int) {
        let descriptor = lock.withLock { closed ? -1 : fd }
        guard descriptor >= 0 else { return }
        var value = timeval(tv_sec: millis / 1000, tv_usec: Int32((millis % 1000) * 1000))
        setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, &value, socklen_t(MemoryLayout<timeval>.size))
    }

    func readSome(_ max: Int) throws -> Data {
        let descriptor = lock.withLock { closed ? -1 : fd }
        guard descriptor >= 0 else { throw TvIOError.closed("soket kapalı") }
        var buffer = [UInt8](repeating: 0, count: max)
        while true {
            let n = recv(descriptor, &buffer, max, 0)
            if n > 0 { return Data(buffer[0..<n]) }
            if n == 0 { return Data() }
            if errno == EINTR { continue }
            if errno == EAGAIN || errno == EWOULDBLOCK { throw TvIOError.timeout }
            throw TvIOError.closed(String(cString: strerror(errno)))
        }
    }

    func write(_ data: Data) throws {
        let descriptor = lock.withLock { closed ? -1 : fd }
        guard descriptor >= 0 else { throw TvIOError.closed("soket kapalı") }
        try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let base = raw.baseAddress else { return }
            var offset = 0
            while offset < raw.count {
                let n = send(descriptor, base + offset, raw.count - offset, 0)
                if n < 0 {
                    if errno == EINTR { continue }
                    if errno == EAGAIN || errno == EWOULDBLOCK { throw TvIOError.timeout }
                    throw TvIOError.closed(String(cString: strerror(errno)))
                }
                offset += n
            }
        }
    }

    /// Bekleyen okuma ve yazmaları hemen bitirir; soketi kapatmaz (sahibi `close` eder).
    func abort() {
        lock.withLock {
            if !closed { _ = shutdown(fd, SHUT_RDWR) }
        }
    }

    func close() {
        lock.withLock {
            guard !closed else { return }
            closed = true
            _ = shutdown(fd, SHUT_RDWR)
            _ = Darwin.close(fd)
            fd = -1
        }
    }
}

/// Yerel ağdaki TV'lere düz HTTP GET (LG Key Server, LG/Samsung cihaz bilgisi). Android'deki gibi doğrudan soketle
/// yapılır: TV adresleri önceden bilinmez, ATS'nin yerel ağ izni de gerekmez.
enum LanHttp {
    struct Response {
        let status: Int
        let body: Data
        var text: String { String(decoding: body, as: UTF8.self) }
    }

    static let maxBody = 4 * 1024 * 1024

    static func get(_ host: String, _ port: Int, _ path: String, timeoutMs: Int = 4000, cancel: Cancel = .none) throws -> Response {
        let socket = try TvSocket.connect(host: host, port: port, timeoutMs: timeoutMs, cancel: cancel === Cancel.none ? nil : cancel)
        let tracked = cancel === Cancel.none ? nil : try cancel.track { socket.abort() }
        defer {
            socket.close()
            if let tracked { cancel.untrack(tracked) }
        }
        socket.setReadTimeout(timeoutMs)
        let request = "GET \(path) HTTP/1.1\r\nHost: \(host):\(port)\r\nUser-Agent: GameTranslator\r\nAccept: */*\r\nConnection: close\r\n\r\n"
        try socket.write(Data(request.utf8))
        return try parse(TvReader(socket))
    }

    /// Yanıtı okur: durum satırı, başlıklar, gövde (Content-Length, chunked ya da bağlantı kapanana kadar).
    static func parse(_ input: TvReader) throws -> Response {
        guard let statusLine = try readLine(input) else { throw TvIOError.failed("boş yanıt") }
        let pieces = statusLine.split(separator: " ", omittingEmptySubsequences: false)
        guard pieces.count > 1, let status = Int(pieces[1]) else { throw TvIOError.failed("HTTP yanıtı değil: \(statusLine)") }
        var length = -1
        var chunked = false
        while let line = try readLine(input), !line.isEmpty {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            if name == "content-length" { length = Int(value) ?? -1 }
            if name == "transfer-encoding" && value.lowercased().contains("chunked") { chunked = true }
        }
        var body = Data()
        if chunked {
            while let line = try readLine(input) {
                let sizeText = line.split(separator: ";", omittingEmptySubsequences: false).first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
                guard let size = Int(sizeText, radix: 16) else { break }
                if size == 0 { break }
                body.append(try input.readExactly(size))
                _ = try readLine(input)
            }
        } else if length >= 0 {
            body = try input.readExactly(min(length, maxBody))
        } else {
            while body.count < maxBody {
                let chunk = try input.readSome(8192)
                if chunk.isEmpty { break }
                body.append(chunk)
            }
        }
        return Response(status: status, body: body)
    }

    private static func readLine(_ input: TvReader) throws -> String? {
        var line = Data()
        while true {
            guard let byte = try input.readByte() else { return line.isEmpty ? nil : String(decoding: line, as: UTF8.self) }
            if byte == 0x0A {
                var text = String(decoding: line, as: UTF8.self)
                while text.hasSuffix("\r") { text.removeLast() }
                return text
            }
            line.append(byte)
            if line.count > 16384 { throw TvIOError.failed("satır çok uzun") }
        }
    }
}

/// Telefonun yerel ağ adresleri ve port taraması (Android: LocalNetwork).
enum TvNetwork {
    static func isPrivate(_ address: String) -> Bool {
        let b = address.split(separator: ".", omittingEmptySubsequences: false).compactMap { Int($0) }
        guard b.count == 4 else { return false }
        return b[0] == 10 || (b[0] == 172 && (16...31).contains(b[1])) || (b[0] == 192 && b[1] == 168)
    }

    /// Telefonun özel IPv4 adresleri; Wi-Fi (en0) önce. Hücresel (pdp_ip) ve VPN (utun) adresleri TV'nin göreceği
    /// adres değildir, atlanır.
    static func privateAddresses() -> [String] {
        var wifi: [String] = []
        var other: [String] = []
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [] }
        defer { freeifaddrs(list) }
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            guard let address = entry.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
                  entry.ifa_flags & UInt32(IFF_UP) != 0, entry.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let text = String(cString: host)
            let name = String(cString: entry.ifa_name)
            guard isPrivate(text), !name.hasPrefix("pdp_ip"), !name.hasPrefix("utun"), !name.hasPrefix("ipsec") else { continue }
            if name == "en0" { wifi.append(text) } else { other.append(text) }
        }
        var seen = Set<String>()
        return (wifi + other).filter { seen.insert($0).inserted }
    }

    /// Her yerel adresin /24 ağındaki diğer adresler (kendisi hariç), tekrarsız.
    static func scanTargets(_ locals: [String]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for local in locals {
            let b = local.split(separator: ".", omittingEmptySubsequences: false)
            guard b.count == 4 else { continue }
            for last in 1...254 where String(last) != b[3] {
                let host = "\(b[0]).\(b[1]).\(b[2]).\(last)"
                if seen.insert(host).inserted { out.append(host) }
            }
        }
        return out
    }

    /// Verilen portu açık olan adresler (64 bağlantı aynı anda).
    static func hostsWithOpenPort(_ port: Int, timeoutMs: Int, cancel: Cancel) throws -> [String] {
        let targets = scanTargets(privateAddresses())
        let found = TvLocked<[String]>([])
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 64
        for host in targets {
            queue.addOperation {
                if !cancel.cancelled && TvSocket.isPortOpen(host, port, timeoutMs: timeoutMs) { found.update { $0.append(host) } }
            }
        }
        while queue.operationCount > 0 {
            if cancel.cancelled {
                queue.cancelAllOperations()
                throw TvInstallError(.CANCELLED)
            }
            Thread.sleep(forTimeInterval: 0.1)
        }
        return found.get.sorted { (Int($0.split(separator: ".").last ?? "") ?? 0) < (Int($1.split(separator: ".").last ?? "") ?? 0) }
    }
}
