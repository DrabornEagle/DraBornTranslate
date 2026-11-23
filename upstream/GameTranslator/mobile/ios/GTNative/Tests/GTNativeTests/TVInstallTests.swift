// SPDX-License-Identifier: AGPL-3.0-only

import CryptoKit
import Security
import XCTest
@testable import GTNative

/// Android'deki TvInstallTest.kt'nin karşılığı: sürümler, adresler, arama yanıtları, HTTP, ADB ve LG.
final class TVVersionsAndAddressTests: XCTestCase {
    func testComparesNumerically() {
        XCTAssertTrue(Versions.isNewer("0.10.0", "0.9.9"))
        XCTAssertTrue(Versions.isNewer("1.0.5", "1.0.0"))
        XCTAssertFalse(Versions.isNewer("1.0.0", "1.0.0"))
        XCTAssertFalse(Versions.isNewer("1.0", "1.0.0"))
    }

    func testReadsBundledVersions() {
        XCTAssertEqual(Versions.ofBundledFile("gametranslator-androidtv_1.0.0.apk"), "1.0.0")
        XCTAssertEqual(Versions.ofBundledFile("tv-app/com.gametranslator.tv_1.0.5_all.ipk"), "1.0.5")
        XCTAssertEqual(Versions.ofBundledFile("gametranslator-samsung_1.0.5.wgt"), "1.0.5")
        XCTAssertNil(Versions.ofBundledFile("app.apk"))
    }

    func testCleansTypedAddresses() {
        XCTAssertEqual(ManualAddress.normalize(" http://192.168.1.5:3000/x "), "192.168.1.5")
        XCTAssertEqual(ManualAddress.normalize("192,168,1,5"), "192.168.1.5")
        XCTAssertEqual(ManualAddress.normalize("010.0.0.1."), "10.0.0.1")
        XCTAssertEqual(ManualAddress.normalize("LGwebOSTV.local"), "lgwebostv.local")
        XCTAssertNil(ManualAddress.normalize("192.168.1.256"))
        XCTAssertNil(ManualAddress.normalize("192.168.1"))
        XCTAssertNil(ManualAddress.normalize("fe80::1"))
        XCTAssertNil(ManualAddress.normalize("1.2.3.4:abc"))
        XCTAssertNil(ManualAddress.normalize(""))
        XCTAssertNil(ManualAddress.normalize(nil))
    }

    func testScanTargetsSkipSelf() {
        let targets = TvNetwork.scanTargets(["192.168.1.20"])
        XCTAssertEqual(targets.count, 253)
        XCTAssertFalse(targets.contains("192.168.1.20"))
        XCTAssertTrue(targets.contains("192.168.1.254"))
        XCTAssertEqual(TvNetwork.scanTargets(["192.168.1.20", "10.0.0.5"]).count, 253 + 253)
        XCTAssertTrue(TvNetwork.isPrivate("172.20.1.1"))
        XCTAssertFalse(TvNetwork.isPrivate("8.8.8.8"))
    }

    func testBundledPackagesAndFacadeBasics() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("tvapp-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        for name in ["com.gametranslator.tv_1.0.12_all.ipk", "gametranslator-androidtv_1.0.12.apk", "gametranslator-samsung_1.0.11.wgt", "readme.txt"] {
            try Data([1]).write(to: directory.appendingPathComponent(name))
        }
        let suite = "tvtest-\(UUID().uuidString)"
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let store = TvStore(defaults: UserDefaults(suiteName: suite)!, secrets: MemorySecrets())
        let tv = TvInstall(packagesDirectory: directory, store: store)
        XCTAssertEqual(tv.bundled(), ["lg": "1.0.12", "android": "1.0.12", "samsung": "1.0.11"])
        XCTAssertEqual(TvInstall(packagesDirectory: nil, store: store).bundled(), [:])
        XCTAssertEqual(tv.normalize("192,168,1,5"), "192.168.1.5")
        XCTAssertNil(tv.saved())
        store.tv = ["brand": "lg", "host": "192.168.1.9", "name": "TV", "version": NSNull(), "installedAt": 5]
        store.setLgKey("192.168.1.9", LgKey(text: "k", passphrase: nil))
        XCTAssertEqual(tv.saved()?["host"] as? String, "192.168.1.9")
        XCTAssertTrue(tv.saved()?["version"] is NSNull)
        XCTAssertEqual(store.lgKey("192.168.1.9"), LgKey(text: "k", passphrase: nil))
        tv.forget()
        XCTAssertNil(tv.saved())
        XCTAssertNil(store.lgKey("192.168.1.9"))
        let failure = tv.failure(TvInstallError(.CANCELLED))
        XCTAssertEqual(failure["ok"] as? Bool, false)
        XCTAssertEqual(failure["error"] as? String, "CANCELLED")
        XCTAssertEqual(failure["detail"] as? String, "CANCELLED")
        XCTAssertEqual(tv.failure(TvIOError.failed("x"))["error"] as? String, "INSTALL_FAILED")
    }

    func testBadArgumentsAreReported() async {
        let tv = TvInstall(packagesDirectory: nil, store: TvStore(defaults: UserDefaults(suiteName: "tvtest-args")!, secrets: MemorySecrets()))
        do {
            _ = try await tv.find("sony")
            XCTFail("hata bekleniyordu")
        } catch {
            XCTAssertEqual(error as? TvInstallArgumentError, TvInstallArgumentError(code: "bad_brand", message: "marka"))
        }
        do {
            _ = try await tv.check(brand: "lg", host: nil)
            XCTFail("hata bekleniyordu")
        } catch {
            XCTAssertEqual((error as? TvInstallArgumentError)?.code, "bad_host")
        }
        let missing = try? await tv.install(brand: "android", host: "127.0.0.1", name: nil, passphrase: nil, force: false)
        XCTAssertEqual(missing?["error"] as? String, "PACKAGE_MISSING")
    }

    func testStoreKeepsDeviceKey() throws {
        let store = TvStore(defaults: UserDefaults(suiteName: "tvtest-key")!, secrets: MemorySecrets())
        let first = try store.deviceKey("adbkey")
        let second = try store.deviceKey("adbkey")
        XCTAssertEqual(first.modulus, second.modulus)
        XCTAssertEqual(first.bitLength, 2048)
    }
}

final class TVDiscoveryParsingTests: XCTestCase {
    func testReadsLgSsdpReply() {
        let reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.44:1990/abc\r\n" +
            "ST: urn:lge-com:service:webos-second-screen:1\r\nUSN: uuid:x\r\n\r\n"
        XCTAssertEqual(TvDiscovery.hostFromSsdpResponse(reply, searchTarget: TvDiscovery.lgTarget), "192.168.1.44")
        XCTAssertNil(TvDiscovery.hostFromSsdpResponse(reply, searchTarget: TvDiscovery.samsungTarget))
        let samsung = "NOTIFY * HTTP/1.1\nLocation: http://10.0.0.7:9197/dmr\nNT: urn:samsung.com:device:RemoteControlReceiver:1\n"
        XCTAssertEqual(TvDiscovery.hostFromSsdpResponse(samsung, searchTarget: TvDiscovery.samsungTarget), "10.0.0.7")
    }

    func testReadsNames() {
        XCTAssertEqual(TvDiscovery.friendlyName("<root><device><friendlyName>[LG] webOS TV UK7550</friendlyName></device></root>"), "[LG] webOS TV UK7550")
        XCTAssertEqual(TvDiscovery.samsungName("{\"device\":{\"name\":\"Samsung &quot;Q&quot;\"}}"), "Samsung \"Q\"")
        XCTAssertNil(TvDiscovery.samsungName("bozuk"))
        XCTAssertNil(TvDiscovery.friendlyName("<root/>"))
    }

    func testParsesHttpReplies() throws {
        let chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n3\r\nabc\r\n0\r\n\r\n"
        let response = try LanHttp.parse(TvReader(TvDataInput(Data(chunked.utf8))))
        XCTAssertEqual(response.status, 200)
        XCTAssertEqual(response.text, "helloabc")
        let sized = try LanHttp.parse(TvReader(TvDataInput(Data("HTTP/1.0 404 Not Found\r\nContent-Length: 2\r\n\r\nnoEXTRA".utf8))))
        XCTAssertEqual(sized.status, 404)
        XCTAssertEqual(sized.text, "no")
        let open = try LanHttp.parse(TvReader(TvDataInput(Data("HTTP/1.1 200 OK\r\n\r\nuntil close".utf8))))
        XCTAssertEqual(open.text, "until close")
        XCTAssertThrowsError(try LanHttp.parse(TvReader(TvDataInput(Data("garbage\r\n".utf8)))))
    }

    /// Gerçek soketle yerel bir sunucudan HTTP GET.
    func testGetsOverLoopbackSocket() throws {
        let server = socket(AF_INET, SOCK_STREAM, 0)
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        address.sin_port = 0
        _ = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(server, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        Darwin.listen(server, 1)
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(server, $0, &length) } }
        let port = Int(UInt16(bigEndian: address.sin_port))
        let peer = Background {
            let client = accept(server, nil, nil)
            var buffer = [UInt8](repeating: 0, count: 4096)
            _ = recv(client, &buffer, buffer.count, 0)
            let reply = Array("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nanah".utf8)
            _ = send(client, reply, reply.count, 0)
            Darwin.close(client)
        }
        let response = try LanHttp.get("127.0.0.1", port, "/webos_rsa", timeoutMs: 2000)
        XCTAssertTrue(peer.join())
        Darwin.close(server)
        XCTAssertEqual(response.text, "anah")
    }
}

final class TVAdbProtocolTests: XCTestCase {
    private static let key = try! RsaKey.generate()

    func testFramesPackets() throws {
        let data = Data("host::\u{0}".utf8)
        let packet = Adb.encode(Adb.CNXN, 0x0100_0001, 4096, data)
        XCTAssertEqual(Adb.readLe32(packet, 0), Adb.CNXN)
        XCTAssertEqual(Int(Adb.readLe32(packet, 12)), data.count)
        XCTAssertEqual(Adb.readLe32(packet, 16), data.reduce(UInt32(0)) { $0 + UInt32($1) })
        XCTAssertEqual(Adb.readLe32(packet, 20), ~Adb.CNXN)
        let read = try Adb.read(TvReader(TvDataInput(packet)))
        XCTAssertEqual(read.command, Adb.CNXN)
        XCTAssertEqual(read.arg1, 4096)
        XCTAssertEqual(read.data, data)
        XCTAssertEqual(Adb.name(Adb.CNXN), "CNXN")
    }

    func testRejectsBrokenHeader() {
        var packet = Adb.encode(Adb.OKAY, 1, 2, Data())
        packet[20] = 0
        XCTAssertThrowsError(try Adb.read(TvReader(TvDataInput(packet))))
    }

    /// adb jetonu SHA-1 özeti yerine geçen değer olarak imzalar: SHA1(x) imzası, x'in SHA1withRSA imzasıyla aynıdır.
    func testSignsTokenAsDigest() throws {
        let message = Data("jeton".utf8)
        let token = Data(Insecure.SHA1.hash(data: message))
        let expected = try Self.key.sign(message, .rsaSignatureMessagePKCS1v15SHA1)
        XCTAssertEqual(try Adb.signToken(Self.key, token), expected)
        XCTAssertEqual(try Adb.signSha1(Self.key, message), expected)
    }

    func testEncodesAndroidPublicKey() throws {
        let blob = try Adb.encodePublicKey(Self.key)
        XCTAssertEqual(blob.count, 524)
        XCTAssertEqual(Adb.readLe32(blob, 0), 64)
        let n = Adb.littleWords(Self.key.modulus, 64)
        XCTAssertEqual(Array(blob[8..<264].reversed()), Self.key.modulus)
        // n0inv = -1/n mod 2^32
        XCTAssertEqual(Adb.readLe32(blob, 4) &* n[0], UInt32.max)
        XCTAssertEqual(Adb.readLe32(blob, 520), 65537)
        XCTAssertTrue(lessThan(Adb.littleWords(Array(blob[264..<520].reversed()), 64), n))
        let small = Adb.powerOfTwoMod(64, [1_000_003])
        XCTAssertEqual(UInt64(small[0]), (0..<64).reduce(UInt64(1)) { r, _ in (r * 2) % 1_000_003 })
        let payload = try Adb.publicKeyPayload(Self.key, "gametranslator@GameTranslator")
        XCTAssertTrue(String(decoding: payload, as: UTF8.self).hasSuffix(" gametranslator@GameTranslator\u{0}"))
    }

    /// Bilinen anahtar (Samsung deneme anahtarı) için beklenen blob Python'un büyük sayılarıyla hesaplandı:
    /// pack('<II', 64, -n⁻¹ mod 2³²) + n (LE) + 2⁴⁰⁹⁶ mod n (LE) + pack('<I', 65537).
    func testAndroidPublicKeyMatchesReference() throws {
        let key = try RsaKey.load(der: TVFixtures.samsung("key.pk8"))
        let digest = SHA256.hash(data: try Adb.encodePublicKey(key)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(digest, "f3c2a41c86b538b00590b2d55fef02de6267e3d663016016d24f897e3f65f965")
    }

    private func lessThan(_ a: [UInt32], _ b: [UInt32]) -> Bool {
        for i in stride(from: a.count - 1, through: 0, by: -1) where a[i] != b[i] { return a[i] < b[i] }
        return false
    }

    func testSyncFraming() {
        let packet = AdbClient.syncPacket("SEND", Data("/tmp/a,33188".utf8))
        XCTAssertEqual(String(decoding: packet.prefix(4), as: UTF8.self), "SEND")
        XCTAssertEqual(Adb.readLe32(packet, 4), 12)
        XCTAssertEqual(AdbClient.syncHeader("DONE", 5).count, 8)
    }

    /// Sahte TV ile uçtan uca: karşılaşma (onay sorusu dahil), komut ve veri akıtma.
    func testTalksToFakeDevice() throws {
        let toDevice = MemoryPipe()
        let fromDevice = MemoryPipe()
        var received = Data()
        var publicKeyLine = ""
        var opened = ""
        let device = Background {
            let input = TvReader(toDevice)
            func send(_ command: UInt32, _ a0: UInt32, _ a1: UInt32, _ data: Data = Data()) throws {
                try fromDevice.write(Adb.encode(command, a0, a1, data))
            }
            guard try Adb.read(input).command == Adb.CNXN else { throw TvIOError.failed("CNXN yok") }
            try send(Adb.AUTH, Adb.authToken, 0, Data(repeating: 7, count: 20))
            guard try Adb.read(input).arg0 == Adb.authSignature else { throw TvIOError.failed("imza yok") }
            try send(Adb.AUTH, Adb.authToken, 0, Data(repeating: 8, count: 20)) // imza tanınmadı
            let publicKey = try Adb.read(input)
            guard publicKey.arg0 == Adb.authPublicKey else { throw TvIOError.failed("açık anahtar yok") }
            publicKeyLine = String(decoding: publicKey.data, as: UTF8.self)
            try send(Adb.CNXN, 0x0100_0001, 8, Data("device::".utf8)) // küçük paket boyu: veri parçalara bölünmeli

            let open = try Adb.read(input)
            guard open.command == Adb.OPEN else { throw TvIOError.failed("OPEN yok") }
            opened = String(decoding: open.data, as: UTF8.self)
            try send(Adb.OKAY, 99, open.arg0)
            while received.count < 20 {
                let write = try Adb.read(input)
                guard write.command == Adb.WRTE, write.data.count <= 8 else { throw TvIOError.failed("parça büyük") }
                received.append(write.data)
                try send(Adb.OKAY, 99, open.arg0)
            }
            try send(Adb.WRTE, 99, open.arg0, Data("Success\n".utf8))
            guard try Adb.read(input).command == Adb.OKAY else { throw TvIOError.failed("OKAY yok") }
            try send(Adb.CLSE, 99, open.arg0)
        }
        var approval = false
        let client = AdbClient(input: fromDevice, output: toDevice, options: .adb)
        try client.handshake(Self.key, onApprovalNeeded: { approval = true })
        XCTAssertTrue(approval)
        XCTAssertEqual(client.maxPayload, 8)
        XCTAssertEqual(client.banner, "device::")
        let payload = Data((0..<20).map { UInt8($0) })
        XCTAssertEqual(try client.run("exec:cat", payload), "Success\n")
        XCTAssertTrue(device.join())
        XCTAssertNil(device.error)
        XCTAssertEqual(received, payload)
        XCTAssertEqual(opened, "exec:cat\u{0}")
        XCTAssertTrue(publicKeyLine.hasSuffix("gametranslator@GameTranslator\u{0}"))
    }

    func testApprovalTimeoutIsNotApproved() throws {
        let toDevice = MemoryPipe()
        let fromDevice = MemoryPipe()
        let device = Background {
            let input = TvReader(toDevice)
            _ = try Adb.read(input)
            try fromDevice.write(Adb.encode(Adb.AUTH, Adb.authToken, 0, Data(repeating: 1, count: 20)))
            _ = try Adb.read(input)
            try fromDevice.write(Adb.encode(Adb.AUTH, Adb.authToken, 0, Data(repeating: 2, count: 20)))
            _ = try Adb.read(input)
            fromDevice.close() // kullanıcı reddetti
        }
        let client = AdbClient(input: fromDevice, output: toDevice, options: .adb)
        XCTAssertThrowsError(try client.handshake(Self.key)) { error in
            XCTAssertEqual((error as? TvInstallError)?.error, .NOT_APPROVED)
        }
        device.join()
    }

    func testReadsDumpsysVersion() {
        XCTAssertEqual(AndroidTvInstaller.versionFromDumpsys("Packages:\n  versionCode=100 minSdk=21\n    versionName=1.0.0\n"), "1.0.0")
        XCTAssertNil(AndroidTvInstaller.versionFromDumpsys("Unable to find package"))
        XCTAssertTrue(AndroidTvInstaller.isStorageProblem("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"))
        XCTAssertTrue(AndroidTvInstaller.isStorageProblem("Not enough storage"))
        XCTAssertTrue(AndroidTvInstaller.needsLegacyInstall("/system/bin/sh: cmd: not found"))
        XCTAssertFalse(AndroidTvInstaller.needsLegacyInstall("Success"))
    }

    func testCancelClosesTrackedAndThrows() throws {
        let cancel = Cancel()
        var closed = 0
        try cancel.track { closed += 1 }
        cancel.cancel()
        XCTAssertEqual(closed, 1)
        XCTAssertThrowsError(try cancel.track { closed += 1 })
        XCTAssertEqual(closed, 2)
        XCTAssertThrowsError(try cancel.check())
        XCTAssertThrowsError(try cancel.guarded { throw TvIOError.closed("x") }) { error in
            XCTAssertEqual((error as? TvInstallError)?.error, .CANCELLED)
        }
    }
}

final class TVLgTests: XCTestCase {
    static let aesKey = """

    -----BEGIN RSA PRIVATE KEY-----
    Proc-Type: 4,ENCRYPTED
    DEK-Info: AES-128-CBC,AA662CDF46D1C77EFDF879AD733AD05F

    minEkIZFLzDW4MKhHMm78YLlU5juIDfzxlukiHY8Hjj4zaGg44p2SNkAiEPqaELS
    OxbsKzqZLnbH6NtvnadfaQFwx5os5WXZ/2jCBpuxPGlEpR/EN9ov9TxEWchKN06/
    7Lpl6wDfxTlkkzLC/+K0FHPvi5mkrRPAjUAXKHNqVhhpL8SBHJZfyqafOifo+2Gb
    o+oRR0pSiu6GtFePiBJu3vx37l4fADiRsYiEunauxZqWUDmhUc08oPL5Xvjkt7Ta
    nMdTJwKOjK1raQv/EMnLbn7RompTcJ4z/sz14TBD4794mNXdFRjBEiPxJPXvwz5N
    E7hMLryyy+2k5wuLuHYmpEmYLeVD8Z7aYuL0HduSjH2gqKJjh9eHNWU3RWqET0uL
    9h62yOzX6tmGIOFIyykq6vrZaaCGQ0sfrPj1amAv2dpqn+yiTxjRcsOs/TzDnb2X
    TS17jDKAF4lsIjNwLZQBE/apHk2ve69vUYgJ8wNRkw+Hj1a+r9hsuBeByv8o2th8
    NW5o5y8buwBjHyVYaDUgaYGeBMJx9Zz7gVW9D4ai4LaJyI8nFTw61rEb9VooGjo7
    w/rwvV6bmJMS9GSOfIrpvYp+ThGUO+yyj6wSyPGRrgH7o9atTikaSyMHIXsMgt9c
    BenxGs0tYWsGSyYO2wreH4EFLXzOmaDNa3KiS1aeCmBbPDxj34XVYWcHh/mMAqZb
    MrqK8gEmwCfNjv4YYiHFywQk+v7GYHIySCHe5v0Y6HGXBXD1ZUwb0Qh/v/Vf3fxu
    ewX+DaXdj5u+qGmPtNGCxpuwEiv9w6G2dhP/VCTjU9HCqJ10r4Jf6XQxX439Ha2T
    -----END RSA PRIVATE KEY-----

    """

    static let desKey = """

    -----BEGIN RSA PRIVATE KEY-----
    Proc-Type: 4,ENCRYPTED
    DEK-Info: DES-EDE3-CBC,7C4E75EE9CCC14BA

    GoQI3C3wNYZmACNrWBLIepmueNP2095KkjB2XOu/dIbRVNwLVK52lfEUR5Yh/6kc
    u1FnbFAkui4oaqiYzEJ+nJJo2Wi1iSHq+TVposM+MC2JLfnXaIYp09226owmKH9G
    Th841RUYwubP+lDjoe8BbUflbzmrdkXqfcXzVxmwHRrI3AahJyx1wXBERyW6D6WT
    SszipsrbJovzFD5oMm06WSPsyeB1XqCaMFxlrT2TMmHH9Gq09KMFmuKGnrTYei0y
    11Aep9rCKp2uex+RYhu3lUalV4HEXAbi4QpqxBy4Urln318/ZlfKSkDM/I7e8qU9
    +CqtCWWxkr8ushM1k5t5FQtNEdOutXy13kaF8aG32sJwv9lQJLEmpW8s1rYZuXT8
    90B4q99z5AFzn26658e4ArjTMxQYTl6Axic4qb2qJ3DggZezv4QL9t5lkaNx2Y3X
    gpIxMFY1fWUmKq3CaAnboFIsCHMfdgkZFPoHFTjDlsoBdVVVoI1m7NLVTqDweuCa
    LbfUsSNFqMwAS3VNULDT1qc5eLt45jyard45oC51b1ilfGKbstHYxo3acLGZfbiF
    07dPUIW0ujRL8IbD7kQ9mJyN12IYHTYNG/1O6j/UFqSSqFzgY0Wxv3Hm67lt9Pg3
    rJbeb9zmwOSHoCJAF8bg1Fpi0mkNhMueYqoIjPeMeZsMZ/eJ4NLxfIz2j9QDiux4
    98/d4E4d3HLtoes+ayQ+w1yUiQO43+BpWqEebHiGwIe/1e+3nVQTA7OKGhKygg7U
    +/yzTtqWwr+me8RfhjAfAupYx+cUZ+0iBc/XBmrwbkI7Bfn2YcIcUQ==
    -----END RSA PRIVATE KEY-----

    """

    private func derMd5(_ pem: String) -> String {
        let body = pem.split(separator: "\n").filter { !$0.hasPrefix("-----") }.joined()
        return Insecure.MD5.hash(data: Data(base64Encoded: body)!).map { String(format: "%02x", $0) }.joined()
    }

    /// Windows'taki LegacyPemKeyTests ve Android'deki LgTest ile aynı anahtarlar ve beklenen sonuçlar (parola 2FEEB2).
    func testDecryptsKeyServerKeys() throws {
        XCTAssertTrue(LegacyPemKey.isLegacyEncrypted(Self.aesKey))
        let aes = try LegacyPemKey.decrypt(Self.aesKey.trimmingCharacters(in: .whitespacesAndNewlines), "2FEEB2")
        XCTAssertTrue(aes.hasPrefix("-----BEGIN RSA PRIVATE KEY-----\n"))
        XCTAssertEqual(derMd5(aes), "7924a944e4baf8734c2f9f9e9787edac")
        XCTAssertEqual(derMd5(try LegacyPemKey.decrypt(Self.desKey, "2FEEB2")), "0ad305359ea07357d79b39fc083242fd")
    }

    func testWrongPassphraseIsReported() throws {
        XCTAssertThrowsError(try LegacyPemKey.decrypt(Self.aesKey, "ABCDEF")) { error in
            XCTAssertEqual((error as? TvInstallError)?.error, .WRONG_PASSPHRASE)
        }
        // Küçük harfle yazılmış parola da kabul edilir.
        XCTAssertEqual(derMd5(try LegacyPemKey.decryptWithCaseFallback(Self.aesKey, " 2feeb2 ")), "7924a944e4baf8734c2f9f9e9787edac")
    }

    func testDerivesKeyLikeOpenSsl() {
        // EVP_BytesToKey(MD5, 1 tur): ilk blok MD5(parola + tuz).
        let key = LegacyPemKey.deriveKey(Data("pw".utf8), Data([1, 2, 3, 4, 5, 6, 7, 8]), 24)
        XCTAssertEqual(key.count, 24)
        XCTAssertEqual(key.prefix(16), Data(Insecure.MD5.hash(data: Data("pw".utf8) + Data([1, 2, 3, 4, 5, 6, 7, 8]))))
        XCTAssertTrue(LegacyPemKey.looksLikeDer(Data([0x30, 0x02, 0x05, 0x00])))
        XCTAssertFalse(LegacyPemKey.looksLikeDer(Data([0x30, 0x05, 0x05, 0x00])))
    }

    /// Çözülen PKCS#1 anahtar OpenSSH biçimine çevrilip Citadel'e verilir; Citadel'in ssh-rsa (SHA-1) imzası
    /// Security.framework'ün aynı anahtarla attığı imzayla aynı olmalıdır.
    func testConvertedKeySignsLikeSecurityFramework() throws {
        let pem = try LegacyPemKey.decrypt(Self.desKey, "2FEEB2")
        let openSSH = try LegacyPemKey.openSSH(fromPem: pem)
        XCTAssertTrue(openSSH.hasPrefix("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        let message = Data("prisoner".utf8)
        let ssh = try LgSsh.sshSignature(LgKey(text: pem, passphrase: nil), message)
        let expected = try RsaKey.load(der: Pem.decode(pem)!).sign(message, .rsaSignatureMessagePKCS1v15SHA1)
        XCTAssertEqual(ssh, expected)
        XCTAssertEqual(try LgSsh.acceptKey(openSSH, "x"), LgKey(text: openSSH, passphrase: nil))
    }

    func testReadsInstallReplies() {
        let ok = LgInstaller.parseInstallReplies("{\"returnValue\":true}\n{\"details\":{\"state\":\"installing\"}}\n{\"details\":{\"state\":\"installed\"}}\n")
        XCTAssertTrue(ok.installed)
        XCTAssertFalse(ok.failed)
        let failed = LgInstaller.parseInstallReplies("Error: x\n{\"details\":{\"state\":\"install failed\",\"reason\":\"no space\",\"errorCode\":-5}}")
        XCTAssertTrue(failed.failed)
        XCTAssertEqual(failed.reason, "no space (errorCode -5)")
        let split = LgInstaller.parseInstallReplies("{\"returnValue\":\n false, \"errorText\":\"bad ipk\"}")
        XCTAssertTrue(split.failed)
        XCTAssertEqual(split.reason, "bad ipk")
        XCTAssertFalse(LgInstaller.parseInstallReplies("").failed)
    }

    func testBuildsInstallCommand() {
        let command = LgInstaller.installCommand("/media/developer/temp/com.gametranslator.tv_1.0.5_all.ipk", waitSeconds: 180)
        XCTAssertTrue(command.contains("luna-send-pub -i 'luna://com.webos.appInstallService/dev/install'"))
        XCTAssertTrue(command.contains("\"ipkUrl\":\"/media/developer/temp/com.gametranslator.tv_1.0.5_all.ipk\""))
        XCTAssertTrue(command.contains("sleep 210"))
        XCTAssertTrue(command.contains("while [ $i -lt 180 ]"))
    }

    func testSmallHelpers() {
        XCTAssertEqual(LgInstaller.versionFromAppInfo("{ \"id\": \"com.gametranslator.tv\", \"version\" : \"1.0.5\" }"), "1.0.5")
        XCTAssertEqual(LgInstaller.remainingHours("{\"remain\":\"999:58:40\"}"), 999)
        XCTAssertTrue(LgInstaller.isStorageProblem("ENOSPC"))
        XCTAssertEqual(LgInstaller.appInfoPath, "/media/developer/apps/usr/palm/applications/com.gametranslator.tv/appinfo.json")
        XCTAssertEqual(LgKey.fromJson(LgKey(text: "t", passphrase: "P").toJson()), LgKey(text: "t", passphrase: "P"))
        XCTAssertEqual(LgKey.fromJson(LgKey(text: "t", passphrase: nil).toJson()), LgKey(text: "t", passphrase: nil))
    }

    /// Sahte kabuk: yükleme, boyut denetimi, kurulum, sürüm doğrulaması ve açma sırası.
    func testInstallsThroughShell() async throws {
        let shell = FakeShell()
        try await LgInstaller(shell: shell, cancel: Cancel(), pollIntervalMs: 1)
            .install(Data(repeating: 1, count: 1000), fileName: "com.gametranslator.tv_1.0.5_all.ipk", version: "1.0.5")
        XCTAssertEqual(shell.uploaded?.count, 1000)
        XCTAssertEqual(shell.uploadPath, "/media/developer/temp/com.gametranslator.tv_1.0.5_all.ipk")
        XCTAssertTrue(shell.commands.last!.hasPrefix("rm -f '/media/developer/temp/com.gametranslator.tv_1.0.5_all.ipk'"))
        XCTAssertTrue(shell.commands[0].hasPrefix("mkdir -p /media/developer/temp && rm -f"))
        let launched = await LgInstaller(shell: shell, cancel: Cancel()).launch("http://192.168.1.2:8787")
        XCTAssertTrue(launched)
        XCTAssertTrue(shell.commands.last!.contains("'{\"id\":\"com.gametranslator.tv\",\"params\":{\"server\":\"http://192.168.1.2:8787\"}}'"))
    }

    func testShortUploadFails() async {
        let shell = FakeShell()
        shell.reportedSize = 10
        do {
            try await LgInstaller(shell: shell, cancel: Cancel(), pollIntervalMs: 1).install(Data(count: 100), fileName: "a.ipk", version: "1")
            XCTFail("hata bekleniyordu")
        } catch {
            XCTAssertEqual((error as? TvInstallError)?.error, .INSTALL_FAILED)
        }
    }

    final class FakeShell: RemoteShell {
        var commands: [String] = []
        var uploaded: Data?
        var uploadPath: String?
        var reportedSize: Int?

        func run(_ command: String) async throws -> CommandResult {
            commands.append(command)
            if command.hasPrefix("wc -c") { return CommandResult(status: 0, output: "\(reportedSize ?? uploaded?.count ?? 0)\n") }
            if command.contains("appInstallService") { return CommandResult(status: 0, output: "{\"details\":{\"state\":\"installed\"}}") }
            if command.hasPrefix("cat /media") { return CommandResult(status: 0, output: "{\"version\":\"1.0.5\"}") }
            if command.contains("applicationManager/launch") { return CommandResult(status: 0, output: "{\"returnValue\":true}") }
            return CommandResult(status: 0, output: "")
        }

        func upload(_ data: Data, to path: String) async throws -> CommandResult {
            uploaded = data
            uploadPath = path
            return CommandResult(status: 0, output: "")
        }

        func close() async {}
    }
}

final class TvOverlayGrantTests: XCTestCase {
    func testOverlayGrantOutputs() {
        XCTAssertFalse(AndroidTvInstaller.overlayGrantFailed(""))
        XCTAssertTrue(AndroidTvInstaller.overlayGrantFailed("Error: Unknown operation string: SYSTEM_ALERT_WINDOW"))
        XCTAssertTrue(AndroidTvInstaller.overlayGrantFailed("java.lang.SecurityException: not allowed"))
    }
}
