// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import Security

/// ADB'nin ağ protokolünün paket biçimi ve kimlik doğrulaması (AOSP packages/modules/adb, protocol.txt; Android:
/// AdbProtocol.kt). Samsung'un sdb'si aynı çerçeveyi kullanır.
enum Adb {
    static let CNXN: UInt32 = 0x4e58_4e43
    static let AUTH: UInt32 = 0x4854_5541
    static let OPEN: UInt32 = 0x4e45_504f
    static let OKAY: UInt32 = 0x5941_4b4f
    static let CLSE: UInt32 = 0x4553_4c43
    static let WRTE: UInt32 = 0x4554_5257

    static let authToken: UInt32 = 1
    static let authSignature: UInt32 = 2
    static let authPublicKey: UInt32 = 3

    static let header = 24
    private static let maxData = 1024 * 1024

    struct Packet: CustomStringConvertible {
        let command: UInt32
        let arg0: UInt32
        let arg1: UInt32
        let data: Data

        var description: String { "\(Adb.name(command))(\(arg0), \(arg1), \(data.count) bayt)" }
    }

    static func name(_ command: UInt32) -> String {
        String(decoding: le32(command), as: UTF8.self)
    }

    static func le32(_ value: UInt32) -> Data {
        Data([UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF), UInt8((value >> 16) & 0xFF), UInt8(value >> 24)])
    }

    static func readLe32(_ data: Data, _ offset: Int) -> UInt32 {
        let i = data.startIndex + offset
        return UInt32(data[i]) | (UInt32(data[i + 1]) << 8) | (UInt32(data[i + 2]) << 16) | (UInt32(data[i + 3]) << 24)
    }

    static func encode(_ command: UInt32, _ arg0: UInt32, _ arg1: UInt32, _ data: Data) -> Data {
        var sum: UInt32 = 0
        for byte in data { sum = sum &+ UInt32(byte) }
        var out = Data(capacity: header + data.count)
        out.append(le32(command))
        out.append(le32(arg0))
        out.append(le32(arg1))
        out.append(le32(UInt32(data.count)))
        out.append(le32(sum))
        out.append(le32(~command))
        out.append(data)
        return out
    }

    /// Bir paket okur. Sağlama toplamı denetlenmez: yeni ADB sürümleri (0x01000001) onu sıfır gönderir. Bozuk başlık
    /// ya da aşırı uzunluk `TvIOError` verir.
    static func read(_ input: TvReader) throws -> Packet {
        let head = try input.readExactly(header)
        let command = readLe32(head, 0)
        let length = Int(readLe32(head, 12))
        let magic = readLe32(head, 20)
        guard magic == ~command, length <= maxData else {
            throw TvIOError.failed("ADB paketi bozuk (\(String(command, radix: 16)))")
        }
        return Packet(command: command, arg0: readLe32(head, 4), arg1: readLe32(head, 8), data: try input.readExactly(length))
    }

    /// adb, TV'nin gönderdiği 20 baytlık jetonu SHA-1 özeti yerine geçen değer olarak imzalar (RSA_sign NID_sha1): jeton
    /// yeniden özetlenmez. Security.framework'ün "digest" imzası tam bunu yapar (DigestInfo + PKCS#1 v1.5 dolgu).
    static func signToken(_ key: RsaKey, _ token: Data) throws -> Data {
        try key.sign(token, .rsaSignatureDigestPKCS1v15SHA1)
    }

    /// sdb (Samsung) jetonu SHA-1 ile özetleyip imzalar (TizenSdb'deki SignData).
    static func signSha1(_ key: RsaKey, _ token: Data) throws -> Data {
        try key.sign(token, .rsaSignatureMessagePKCS1v15SHA1)
    }

    /// Android'in kendi RSA açık anahtar biçimi (system/core/libcrypto_utils android_pubkey.c): sözcük sayısı, n0inv,
    /// küçük sonlu modül, R² mod n ve üs.
    static func encodePublicKey(_ key: RsaKey) throws -> Data {
        let words = 64
        guard key.bitLength == 2048 else { throw TvIOError.failed("ADB yalnız 2048 bitlik RSA anahtarını kabul eder") }
        let n = littleWords(key.modulus, words)
        // n0inv = -1/n[0] mod 2^32 (Newton yinelemesi; n tek sayıdır).
        var inverse: UInt32 = 1
        for _ in 0..<5 { inverse = inverse &* (2 &- n[0] &* inverse) }
        let n0inv = 0 &- inverse
        let rr = powerOfTwoMod(4096, n)
        var out = Data()
        out.append(le32(UInt32(words)))
        out.append(le32(n0inv))
        for word in n { out.append(le32(word)) }
        for word in rr { out.append(le32(word)) }
        var exponent: UInt32 = 0
        for byte in key.exponent { exponent = (exponent << 8) | UInt32(byte) }
        out.append(le32(exponent))
        return out
    }

    /// Büyük sonlu baytları küçük sonlu 32 bitlik sözcüklere çevirir.
    static func littleWords(_ bigEndian: [UInt8], _ count: Int) -> [UInt32] {
        var words = [UInt32](repeating: 0, count: count)
        for (i, byte) in bigEndian.reversed().enumerated() where i / 4 < count {
            words[i / 4] |= UInt32(byte) << (8 * UInt32(i % 4))
        }
        return words
    }

    /// 2^power mod n (n küçük sonlu sözcükler): 1'den başlayıp her adımda ikiye katlayıp n'yi çıkararak.
    static func powerOfTwoMod(_ power: Int, _ n: [UInt32]) -> [UInt32] {
        let count = n.count
        var r = [UInt32](repeating: 0, count: count + 1)
        r[0] = 1
        let modulus = n + [0]
        func greaterOrEqual() -> Bool {
            for i in stride(from: count, through: 0, by: -1) where r[i] != modulus[i] { return r[i] > modulus[i] }
            return true
        }
        for _ in 0..<power {
            var carry: UInt32 = 0
            for i in 0...count {
                let next = r[i] >> 31
                r[i] = (r[i] << 1) | carry
                carry = next
            }
            if greaterOrEqual() {
                var borrow: UInt64 = 0
                for i in 0...count {
                    let difference = UInt64(r[i]) &- UInt64(modulus[i]) &- borrow
                    r[i] = UInt32(truncatingIfNeeded: difference)
                    borrow = (difference >> 63) & 1
                }
            }
        }
        return Array(r[0..<count])
    }

    /// Açık anahtar satırı: base64 + boşluk + ad + NUL.
    static func publicKeyPayload(_ key: RsaKey, _ name: String) throws -> Data {
        Data((try encodePublicKey(key).base64EncodedString() + " " + name + "\u{0}").utf8)
    }
}

/// ADB ile sdb'nin farkları: sürüm, karşılama yazısı, paket boyu, jetonun nasıl imzalandığı.
struct AdbOptions {
    let version: UInt32
    let banner: String
    let offeredMaxPayload: Int
    /// true: jeton özet yerine geçer (adb); false: jeton SHA-1 ile özetlenip imzalanır (sdb).
    let rawTokenSignature: Bool
    let keyName: String
    /// sync SEND isteğine ",izinler" eklensin mi (adb ekler; TizenSdb eklemez, sdbd 0644 varsayar).
    let sendMode: Bool

    static let adb = AdbOptions(version: 0x0100_0001, banner: "host::\u{0}", offeredMaxPayload: 256 * 1024, rawTokenSignature: true,
                                keyName: "gametranslator@GameTranslator", sendMode: true)
    static let sdb = AdbOptions(version: 0x0100_0000, banner: "host::sdb-net-client", offeredMaxPayload: 4096, rawTokenSignature: false,
                                keyName: "gametranslator@GameTranslator", sendMode: false)
}

/// ADB (ve Samsung'un sdb'si) protokolünün ağ üzerinden kullanılan küçük bir parçası: bağlantı, RSA kimlik doğrulama ve
/// aynı anda tek akış (Android: AdbClient). Bütün çağrılar bloklar; ana iş parçacığında çağrılmamalı.
final class AdbClient {
    private let input: TvReader
    private let output: TvByteOutput
    private let options: AdbOptions
    private let setTimeout: (Int) -> Void
    private let closer: (() -> Void)?

    private(set) var maxPayload = 4096
    /// TV'nin karşılama yazısı (ör. "device::ro.product.name=...;").
    private(set) var banner = ""
    private var nextLocalId: UInt32 = 1

    init(input: TvByteInput, output: TvByteOutput, options: AdbOptions, setTimeout: @escaping (Int) -> Void = { _ in },
         closer: (() -> Void)? = nil) {
        self.input = TvReader(input)
        self.output = output
        self.options = options
        self.setTimeout = setTimeout
        self.closer = closer
    }

    /// Karşılaşma: CNXN, gerekirse AUTH. `onApprovalNeeded`: TV bu telefonu ilk kez görüyor, ekranda "hata ayıklamaya
    /// izin verilsin mi" sorusu çıktı. Onay için `approvalTimeoutMs` beklenir.
    func handshake(_ key: RsaKey, onApprovalNeeded: () -> Void = {}, approvalTimeoutMs: Int = 90_000) throws {
        try write(Adb.CNXN, options.version, UInt32(options.offeredMaxPayload), Data(options.banner.utf8))
        var signed = false
        var waitingForApproval = false
        setTimeout(10_000)
        while true {
            let packet: Adb.Packet
            do {
                packet = try Adb.read(input)
            } catch let error as TvIOError {
                // Onay beklenirken bağlantı kapandıysa ya da süre dolduysa kullanıcı izin vermedi.
                throw TvInstallError(waitingForApproval ? .NOT_APPROVED : .UNREACHABLE, error.description)
            }
            if packet.command == Adb.CNXN {
                maxPayload = min(options.offeredMaxPayload, packet.arg1 == 0 || packet.arg1 > Int32.max ? 4096 : Int(packet.arg1))
                var text = String(decoding: packet.data, as: UTF8.self)
                while text.hasSuffix("\u{0}") { text.removeLast() }
                banner = text
                return
            } else if packet.command == Adb.AUTH && packet.arg0 == Adb.authToken && !signed {
                signed = true
                let signature = options.rawTokenSignature ? try Adb.signToken(key, packet.data) : try Adb.signSha1(key, packet.data)
                try write(Adb.AUTH, Adb.authSignature, 0, signature)
            } else if packet.command == Adb.AUTH && (packet.arg0 == Adb.authToken || packet.arg0 == Adb.authPublicKey) && !waitingForApproval {
                // İmza tanınmadı: TV bu anahtarı ilk kez görüyor. Açık anahtarı gönderince TV kullanıcıya sorar.
                try write(Adb.AUTH, Adb.authPublicKey, 0, try Adb.publicKeyPayload(key, options.keyName))
                waitingForApproval = true
                setTimeout(approvalTimeoutMs)
                onApprovalNeeded()
            } else if packet.command == Adb.AUTH {
                continue // onay beklenirken gelen yeni jeton: kullanıcının yanıtı bekleniyor
            } else {
                throw TvInstallError(.UNREACHABLE, "beklenmeyen paket \(packet)")
            }
        }
    }

    /// Okuma zaman aşımı (milisaniye) sonraki komutlar için.
    func timeout(_ millis: Int) { setTimeout(millis) }

    /// Bir hizmet açar (ör. `shell:…`, `exec:…`, `sync:`). Hizmet açılamazsa hata verir.
    func open(_ service: String) throws -> AdbStream {
        let localId = nextLocalId
        nextLocalId += 1
        try write(Adb.OPEN, localId, 0, Data((service + "\u{0}").utf8))
        let stream = AdbStream(client: self, localId: localId)
        while stream.remoteId == 0 {
            let packet = try Adb.read(input)
            if packet.arg1 != localId { continue }
            switch packet.command {
            case Adb.OKAY: stream.remoteId = packet.arg0
            case Adb.CLSE: throw TvInstallError(.INSTALL_FAILED, "\(service) açılamadı")
            case Adb.WRTE:
                try stream.received(packet)
                if stream.remoteId == 0 { stream.remoteId = packet.arg0 }
            default: break
            }
        }
        return stream
    }

    /// Hizmeti açar, varsa `data`'yı gönderir ve hizmet kapanana kadar çıktıyı toplar. `onSent`: gönderilen bayt sayısı.
    func run(_ service: String, _ data: Data? = nil, onSent: (Int) -> Void = { _ in }) throws -> String {
        String(decoding: try runRaw(service, data, onSent: onSent), as: UTF8.self)
    }

    /// `run` gibi; çıktı çözülmeden döner (sdb'nin capability yanıtı ikili önekle başlar).
    func runRaw(_ service: String, _ data: Data? = nil, onSent: (Int) -> Void = { _ in }) throws -> Data {
        let stream = try open(service)
        defer { stream.close() }
        if let data {
            var offset = 0
            while offset < data.count && !stream.closed {
                let length = min(maxPayload, data.count - offset)
                try stream.write(data.subdata(in: offset..<(offset + length)))
                offset += length
                onSent(offset)
            }
        }
        return try stream.readAll()
    }

    /// Dosyayı sync hizmetiyle TV'ye yazar.
    func push(_ data: Data, remotePath: String, mode: Int = 0o644, onSent: (Int) -> Void = { _ in }) throws {
        let stream = try open("sync:")
        defer { stream.close() }
        let path = options.sendMode ? "\(remotePath),\(0x8000 | mode)" : remotePath
        try stream.write(Self.syncPacket("SEND", Data(path.utf8)))
        var offset = 0
        while offset < data.count {
            let length = min(Self.syncData, data.count - offset)
            try stream.write(Self.syncPacket("DATA", data.subdata(in: offset..<(offset + length))))
            offset += length
            onSent(offset)
        }
        try stream.write(Self.syncHeader("DONE", UInt32(truncatingIfNeeded: Int(Date().timeIntervalSince1970))))
        let reply = try stream.readExactly(8)
        let id = String(decoding: reply.prefix(4), as: UTF8.self)
        if id != "OKAY" {
            let length = Int(Adb.readLe32(reply, 4))
            let message = id == "FAIL" && (1...4096).contains(length) ? String(decoding: try stream.readExactly(length), as: UTF8.self) : id
            throw TvInstallError(.INSTALL_FAILED, "dosya TV'ye yazılamadı: \(message)")
        }
        try? stream.write(Self.syncHeader("QUIT", 0))
    }

    fileprivate func write(_ command: UInt32, _ arg0: UInt32, _ arg1: UInt32, _ data: Data) throws {
        try output.write(Adb.encode(command, arg0, arg1, data))
    }

    fileprivate func readPacket() throws -> Adb.Packet { try Adb.read(input) }

    func close() { closer?() }

    static let syncData = 64 * 1024

    static func syncHeader(_ id: String, _ value: UInt32) -> Data { Data(id.utf8) + Adb.le32(value) }

    static func syncPacket(_ id: String, _ data: Data) -> Data { Data(id.utf8) + Adb.le32(UInt32(data.count)) + data }

    /// TV'ye bağlanır ve karşılaşmayı yapar. Bağlanamazsa `UNREACHABLE`; `cancel` soketi kapatabilir.
    static func connect(host: String, port: Int, key: RsaKey, options: AdbOptions, cancel: Cancel, onApprovalNeeded: () -> Void = {},
                        approvalTimeoutMs: Int = 90_000, connectTimeoutMs: Int = 5_000) throws -> AdbClient {
        let socket: TvSocket
        do {
            socket = try cancel.guarded { try TvSocket.connect(host: host, port: port, timeoutMs: connectTimeoutMs, cancel: cancel) }
        } catch let error as TvIOError {
            throw TvInstallError(.UNREACHABLE, error.description)
        }
        let tracked = try cancel.track { socket.abort() }
        let client = AdbClient(input: socket, output: socket, options: options, setTimeout: { socket.setReadTimeout($0) }, closer: {
            socket.close()
            cancel.untrack(tracked)
        })
        do {
            try cancel.guarded { try client.handshake(key, onApprovalNeeded: onApprovalNeeded, approvalTimeoutMs: approvalTimeoutMs) }
            return client
        } catch {
            client.close()
            if case TvIOError.timeout = error { throw TvInstallError(.UNREACHABLE, "zaman aşımı") }
            throw error
        }
    }
}

/// Açık bir akış. Aynı anda tek akış kullanılır; başka akışların paketleri atlanır.
final class AdbStream {
    private unowned let client: AdbClient
    private let localId: UInt32
    var remoteId: UInt32 = 0
    private(set) var closed = false
    private var pending: [Data] = []
    private var head: Data?
    private var headOffset = 0

    fileprivate init(client: AdbClient, localId: UInt32) {
        self.client = client
        self.localId = localId
    }

    fileprivate func received(_ packet: Adb.Packet) throws {
        switch packet.command {
        case Adb.WRTE:
            if !packet.data.isEmpty { pending.append(packet.data) }
            try client.write(Adb.OKAY, localId, packet.arg0, Data())
        case Adb.CLSE:
            closed = true
        default:
            break
        }
    }

    /// Parçayı gönderir ve TV'nin aldığını (OKAY) bekler; arada gelen çıktı saklanır.
    func write(_ data: Data) throws {
        var position = 0
        while position < data.count {
            if closed { throw TvIOError.closed("akış kapandı") }
            let size = min(client.maxPayload, data.count - position)
            try client.write(Adb.WRTE, localId, remoteId, data.subdata(in: position..<(position + size)))
            position += size
            while true {
                let packet = try client.readPacket()
                if packet.arg1 != localId { continue }
                if packet.command == Adb.OKAY { break }
                try received(packet)
                if closed { break }
            }
        }
    }

    /// Sonraki çıktı parçası; akış kapandıysa nil.
    func read() throws -> Data? {
        if !pending.isEmpty { return pending.removeFirst() }
        while !closed {
            let packet = try client.readPacket()
            if packet.arg1 != localId { continue }
            try received(packet)
            if !pending.isEmpty { return pending.removeFirst() }
        }
        return nil
    }

    func readExactly(_ count: Int) throws -> Data {
        var out = Data()
        while out.count < count {
            let chunk: Data
            if let head {
                chunk = head
            } else {
                guard let next = try read() else { throw TvIOError.closed("akış erken kapandı") }
                head = next
                headOffset = 0
                chunk = next
            }
            let n = min(count - out.count, chunk.count - headOffset)
            out.append(chunk.subdata(in: headOffset..<(headOffset + n)))
            headOffset += n
            if headOffset >= chunk.count { head = nil }
        }
        return out
    }

    func readAll() throws -> Data {
        var out = Data()
        if let head { out.append(head.subdata(in: headOffset..<head.count)) }
        head = nil
        while let chunk = try read() { out.append(chunk) }
        return out
    }

    func close() {
        if !closed {
            closed = true
            try? client.write(Adb.CLSE, localId, remoteId, Data())
        }
    }
}
