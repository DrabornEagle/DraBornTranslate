// SPDX-License-Identifier: AGPL-3.0-only

import Security
import XCTest
@testable import GTNative

/// Samsung kurulumu (Android: SamsungTest.kt). Beklenen dosyalar (Android'in test/resources/fixtures/samsung) Windows
/// uygulamasının kullandığı TizenSdb.Core 1.1.9 ve SamsungCertificates.cs ile aynı anahtar ve sertifikalardan üretildi:
/// imza XML'leri ve sertifika istekleri bayt bayt aynı olmalı (RSA PKCS#1 v1.5 imzası belirlenimcidir).
final class TVSamsungTests: XCTestCase {
    private var key: RsaKey!
    private var cas: [X509Cert]!
    private var suite = ""
    private var store: TvStore!

    override func setUpWithError() throws {
        key = try RsaKey.load(der: TVFixtures.samsung("key.pk8"))
        cas = SamsungCa.load()
        suite = "tvtest-samsung-\(UUID().uuidString)"
        store = TvStore(defaults: UserDefaults(suiteName: suite)!, secrets: MemorySecrets())
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suite)
    }

    private func identity(_ name: String) throws -> SamsungSigner.Identity {
        let leaf = try X509Cert(TVFixtures.samsung(name))
        return SamsungSigner.Identity(key: key, chain: [leaf] + (SamsungCa.intermediate(leaf, cas).map { [$0] } ?? []))
    }

    func testLoadsBundledCas() throws {
        XCTAssertEqual(cas.count, 4)
        let author = try X509Cert(TVFixtures.samsung("author.crt"))
        XCTAssertTrue(try XCTUnwrap(SamsungCa.intermediate(author, cas)).subjectSummary.contains("Samsung VD Author CA") ||
            String(decoding: try XCTUnwrap(SamsungCa.intermediate(author, cas)).subject, as: UTF8.self).contains("Samsung VD Author CA"))
        // Klasörden okuma: Android'in assets/samsung-ca'sı ile gömülü kopya aynı sertifikalardır.
        let directory = TVFixtures.root.appendingPathComponent("mobile/android/app/src/main/assets/samsung-ca")
        XCTAssertEqual(SamsungCa.load(from: directory).map(\.der), cas.map(\.der))
    }

    func testCertificateFields() throws {
        let author = try X509Cert(TVFixtures.samsung("author.crt"))
        XCTAssertLessThan(author.notBefore, author.notAfter)
        XCTAssertFalse(author.isSelfSigned)
        let pem = Pem.encode(author.der, "CERTIFICATE")
        XCTAssertEqual(try X509Cert(Data(pem.utf8)).der, author.der)
    }

    func testAuthorSignatureMatchesTizenSdb() throws {
        let files = try SamsungSigner.readEntries(TVFixtures.samsung("input.wgt"))
        XCTAssertEqual(files.map(\.name), ["config.xml", "index.html", "img/icon.png", "js/app.js"])
        let xml = try SamsungSigner.signatureXml(SamsungSigner.author, files, identity("author.crt"))
        XCTAssertEqual(String(decoding: xml, as: UTF8.self), String(decoding: try TVFixtures.samsung("author-signature.xml"), as: UTF8.self))
        XCTAssertEqual(xml, try TVFixtures.samsung("author-signature.xml"))
    }

    func testDistributorSignatureMatchesTizenSdb() throws {
        let files = try SamsungSigner.readEntries(TVFixtures.samsung("input.wgt"))
        let all = [SamsungSigner.Entry(name: "author-signature.xml", data: try TVFixtures.samsung("author-signature.xml"))] + files
        let xml = try SamsungSigner.signatureXml(SamsungSigner.distributor, all, identity("distributor.crt"))
        XCTAssertEqual(String(decoding: xml, as: UTF8.self), String(decoding: try TVFixtures.samsung("signature1.xml"), as: UTF8.self))
    }

    func testSignedPackageIsValidZipWithSignaturesFirst() throws {
        let signed = try SamsungSigner.sign(TVFixtures.samsung("input.wgt"), author: identity("author.crt"), distributor: identity("distributor.crt"))
        let entries = try Zip.read(signed)
        XCTAssertEqual(entries.map(\.name), ["author-signature.xml", "signature1.xml", "config.xml", "index.html", "img/icon.png", "js/app.js"])
        XCTAssertEqual(entries[0].data, try TVFixtures.samsung("author-signature.xml"))
        XCTAssertEqual(entries[1].data, try TVFixtures.samsung("signature1.xml"))
        let original = try SamsungSigner.readEntries(TVFixtures.samsung("input.wgt"))
        XCTAssertEqual(Array(entries.dropFirst(2)).map(\.data), original.map(\.data))
        // Yeniden imzalamak eski imzaları atar, aynı sonucu verir.
        let again = try SamsungSigner.sign(signed, author: identity("author.crt"), distributor: identity("distributor.crt"))
        XCTAssertEqual(try SamsungSigner.readEntries(signed).map(\.name), try SamsungSigner.readEntries(again).map(\.name))
        // Yerel başlıklarda veri tanımlayıcısı yok (bayrak 3. bit).
        XCTAssertEqual(Int(signed[6]) & 0x08, 0)
        // Sistemin zip açıcısı da (unzip -t) paketi doğrular.
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("signed-\(UUID().uuidString).wgt")
        try signed.write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let unzip = Process()
        unzip.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
        unzip.arguments = ["-tq", file.path]
        unzip.standardOutput = FileHandle.nullDevice
        try unzip.run()
        unzip.waitUntilExit()
        XCTAssertEqual(unzip.terminationStatus, 0)
    }

    func testSignatureVerifiesOverCanonicalSignedInfo() throws {
        let xml = String(decoding: try TVFixtures.samsung("author-signature.xml"), as: UTF8.self)
        let afterRoot = xml.components(separatedBy: "<Signature Id=\"AuthorSignature\" xmlns=\"http://www.w3.org/2000/09/xmldsig#\">")[1]
        var signedInfo = afterRoot.components(separatedBy: "<SignatureValue>")[0]
        signedInfo = signedInfo.replacing(#/<(\w+) (\w+)="([^"]*)" \/>/#) { "<\($0.1) \($0.2)=\"\($0.3)\"></\($0.1)>" }
        signedInfo = signedInfo.replacingOccurrences(of: "<SignedInfo>", with: "<SignedInfo xmlns=\"http://www.w3.org/2000/09/xmldsig#\">")
        let value = Data(base64Encoded: xml.components(separatedBy: "<SignatureValue>")[1].components(separatedBy: "</SignatureValue>")[0])!
        let publicKey = try XCTUnwrap(X509Cert(TVFixtures.samsung("author.crt")).publicKey)
        XCTAssertTrue(SecKeyVerifySignature(publicKey, .rsaSignatureMessagePKCS1v15SHA512, Data(signedInfo.utf8) as CFData, value as CFData, nil))
    }

    func testCertificateRequestsMatchDotNet() throws {
        XCTAssertEqual(String(decoding: try SamsungCsr.author(key), as: UTF8.self), String(decoding: try TVFixtures.samsung("author.csr"), as: UTF8.self))
        XCTAssertEqual(String(decoding: try SamsungCsr.distributor(key, duid: "TESTDUID0123456789", email: "user@example.com"), as: UTF8.self),
                       String(decoding: try TVFixtures.samsung("distributor.csr"), as: UTF8.self))
    }

    func testDerEncodesLengthsAndOids() {
        XCTAssertEqual(Der.oid("2.5.29.17"), Data([0x06, 0x03, 0x55, 0x1D, 0x11]))
        XCTAssertEqual(Der.oid("1.2.840.113549.1.9.14"), Data([0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x09, 0x0E]))
        XCTAssertEqual(Der.tlv(0x04, Data(count: 200))[1], 0x81)
        XCTAssertEqual(Der.tlv(0x04, Data(count: 300))[1], 0x82)
        XCTAssertEqual(try DerNode.read(Der.oid("1.2.840.113549.1.9.14")).0.oidText, "1.2.840.113549.1.9.14")
    }

    func testParsesSignInCallback() throws {
        let json = "{\"access_token\":\"tok+en\",\"userId\":\"u1\",\"inputEmailID\":\"a@b.c\"}"
        let body = "code=" + SamsungLogin.formEncode(json).replacingOccurrences(of: "+", with: "%20") + "&state=accountcheckdogeneratedstatetext"
        let account = try XCTUnwrap(SamsungLogin.parseCallback(body))
        XCTAssertEqual(account.accessToken, "tok+en")
        XCTAssertEqual(account.userId, "u1")
        XCTAssertEqual(account.email, "a@b.c")
        XCTAssertNil(SamsungLogin.parseCallback(body.replacingOccurrences(of: "accountcheck", with: "other")))
        XCTAssertNil(SamsungLogin.parseCallback("code=%7B%7D&state=accountcheckdogeneratedstatetext"))
        XCTAssertNil(SamsungLogin.parseCallback("code=bozuk&state=accountcheckdogeneratedstatetext"))
        XCTAssertTrue(SamsungLogin.authorizeUrl.contains("redirect_uri=http%3A%2F%2Flocalhost%3A4794%2Fsignin%2Fcallback"))
        XCTAssertEqual(SamsungLogin.percentDecode("a+b%20%C3%BC%"), "a+b ü%")
    }

    private func http(_ request: String) throws -> String {
        let socket = try TvSocket.connect(host: "127.0.0.1", port: SamsungLogin.callbackPort, timeoutMs: 2000)
        defer { socket.close() }
        socket.setReadTimeout(5000)
        try socket.write(Data(request.utf8))
        var reply = Data()
        while true {
            let chunk = try socket.readSome(4096)
            if chunk.isEmpty { break }
            reply.append(chunk)
        }
        return String(decoding: reply, as: UTF8.self)
    }

    func testListenerReceivesCallbackOnLocalhost() throws {
        let listener: SamsungLogin.Listener
        do {
            listener = try SamsungLogin.Listener(language: "tr").start()
        } catch {
            throw XCTSkip("port 4794 bu makinede dolu")
        }
        defer { listener.close() }
        XCTAssertTrue(try http("GET /favicon.ico HTTP/1.1\r\nHost: localhost\r\n\r\n").hasPrefix("HTTP/1.1 404"))
        let body = "code=%7B%22access_token%22%3A%22t%22%2C%22userId%22%3A%22u%22%7D&state=accountcheckdogeneratedstatetext"
        let reply = try http("POST /signin/callback HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: \(body.utf8.count)\r\n\r\n\(body)")
        XCTAssertTrue(reply.hasPrefix("HTTP/1.1 200"))
        XCTAssertTrue(reply.contains("Giriş tamam"))
        XCTAssertEqual(try listener.await(timeoutMs: 5000, cancel: Cancel()).accessToken, "t")
        listener.close()
        // Kapatılan dinleyicinin portu hemen yeniden açılabilir (giriş yeniden başlatılınca).
        let again = try SamsungLogin.Listener().start()
        again.close()

        let aborted = SamsungLogin.Listener()
        aborted.abort("kapatıldı")
        XCTAssertThrowsError(try aborted.await(timeoutMs: 1000, cancel: Cancel())) { error in
            XCTAssertEqual((error as? TvInstallError)?.error, .SIGN_IN_FAILED)
        }
    }

    func testFacadeSignInFlow() async throws {
        let tv = TvInstall(packagesDirectory: nil, store: store)
        let missing = await tv.samsungSignInWait()
        XCTAssertEqual(missing["error"] as? String, "SIGN_IN_FAILED")
        guard let url = tv.samsungSignIn() else { throw XCTSkip("port 4794 bu makinede dolu") }
        XCTAssertTrue(url.hasPrefix("https://account.samsung.com/"))
        tv.samsungSignInAbort()
        let aborted = await tv.samsungSignInWait()
        XCTAssertEqual(aborted["ok"] as? Bool, false)
        XCTAssertEqual(aborted["error"] as? String, "SIGN_IN_FAILED")
        XCTAssertEqual(aborted["detail"] as? String, "giriş sayfası kapatıldı")
    }

    func testParsesTvInfoAndDiagnoses() throws {
        let json = "{\"device\":{\"OS\":\"Tizen\",\"developerIP\":\"192.168.1.20\",\"developerMode\":\"1\",\"model\":\"20_PONTUSM_FHD\"," +
            "\"modelName\":\"F27T850\",\"name\":\"[Monitor] Samsung\"},\"name\":\"x\"}"
        let info = try XCTUnwrap(SamsungTvInfo.parse(json))
        XCTAssertEqual(info.developerMode, true)
        XCTAssertEqual(info.developerIp, "192.168.1.20")
        XCTAssertEqual(info.year, 2020)
        XCTAssertEqual(info.modelName, "F27T850")
        XCTAssertNil(SamsungTvInfo.diagnose(info, ["192.168.1.20"]))
        XCTAssertEqual(SamsungTvInfo.diagnose(info, ["192.168.1.30"])?.error, .WRONG_HOST_IP)
        let off = try XCTUnwrap(SamsungTvInfo.parse("{\"device\":{\"developerMode\":\"0\",\"developerIP\":\"0.0.0.0\"}}"))
        XCTAssertEqual(off.developerMode, false)
        XCTAssertNil(off.year)
        XCTAssertEqual(SamsungTvInfo.diagnose(off, ["192.168.1.30"])?.error, .UNREACHABLE)
        XCTAssertNil(SamsungTvInfo.parse("bozuk"))
        XCTAssertNil(SamsungTvInfo.diagnose(nil, ["192.168.1.30"]))
    }

    func testParsesCapabilityAndDuid() {
        let caps = SamsungInstaller.parseCapability(Data("\u{0}\u{1}secure_protocol:enabled\nplatform_version:6.0\nsdk_toolpath:/opt/usr/apps/tmp\nempty:\nx:y:z\n".utf8))
        XCTAssertEqual(caps["platform_version"], "6.0")
        XCTAssertEqual(caps["sdk_toolpath"], "/opt/usr/apps/tmp")
        XCTAssertNil(caps["empty"])
        XCTAssertNil(caps["x"])
        XCTAssertTrue(SamsungInstaller.parseCapability(Data([1])).isEmpty)
        XCTAssertTrue(SamsungInstaller.isDuid("MWCQ3SZJ5NYUQ"))
        XCTAssertFalse(SamsungInstaller.isDuid("closed"))
        XCTAssertFalse(SamsungInstaller.isDuid("error: x y"))
        XCTAssertTrue(SamsungInstaller.installFailed("spend time for pkgcmd is [100]ms\ninstall failed[118, -12], reason: Author certificate not match"))
        XCTAssertFalse(SamsungInstaller.installFailed("start process (install)\nprocessing result : Ok [0]\nspend time"))
        XCTAssertEqual(SamsungProfile.folderName("MWCQ3SZJ5NYUQ/a+b"), "MWCQ3SZJ5NYUQ_a_b")
    }

    private func saveFixtureProfile(_ duid: String, email: String = "") throws {
        try SamsungProfile(store: store, duid: duid).save(key: key, author: X509Cert(TVFixtures.samsung("author.crt")),
                                                          distributor: X509Cert(TVFixtures.samsung("distributor.crt")),
                                                          deviceProfile: Data(), duid: duid, email: email)
    }

    func testProfileValidity() throws {
        let profile = SamsungProfile(store: store, duid: "DUID1234")
        XCTAssertFalse(profile.isValid())
        try saveFixtureProfile("DUID1234", email: "a@b.c")
        XCTAssertTrue(profile.isValid(Date()))
        XCTAssertEqual(profile.email, "a@b.c")
        XCTAssertFalse(profile.isValid(Date(timeIntervalSince1970: 2_300_000_000))) // 2042: süresi dolmuş
        let (author, distributor) = try profile.identities(cas)
        XCTAssertEqual(author.chain.count, 2)
        XCTAssertEqual(distributor.chain.count, 2)
    }

    func testMultipartBodyHasFieldsAndFile() {
        let body = String(decoding: SamsungCertificates.multipartBody(boundary: "B", fields: [("access_token", "t"), ("platform", "VD")],
                                                                      file: Data("CSR".utf8), fileName: "author.csr"), as: UTF8.self)
        XCTAssertTrue(body.hasPrefix("--B\r\n"))
        XCTAssertTrue(body.contains("name=\"access_token\"\r\n\r\nt\r\n"))
        XCTAssertTrue(body.contains("name=\"csr\"; filename=\"author.csr\""))
        XCTAssertTrue(body.hasSuffix("CSR\r\n--B--\r\n"))
    }

    func testCertificatesAreRequestedForDuidAndSaved() throws {
        var calls: [(String, [(String, String)])] = []
        let certificates = SamsungCertificates { url, fields, csr, fileName, _ in
            calls.append((url, fields))
            XCTAssertTrue(String(decoding: csr, as: UTF8.self).hasPrefix("-----BEGIN CERTIFICATE REQUEST-----"))
            switch url {
            case SamsungCertificates.authorUrl: return try TVFixtures.samsung("author.crt")
            case SamsungCertificates.distributorProfileUrl: return Data("<profile/>".utf8)
            default:
                XCTAssertEqual(fileName, "distributor.csr")
                return try TVFixtures.samsung("distributor.crt")
            }
        }
        let profile = try certificates.create(duid: "DUID1234", account: SamsungAccount(accessToken: "tok", userId: "uid", email: "a@b.c"),
                                              profile: SamsungProfile(store: store, duid: "DUID1234"), cancel: Cancel())
        XCTAssertTrue(profile.exists)
        XCTAssertEqual(calls.count, 3)
        XCTAssertTrue(calls[0].1.contains { $0 == ("user_id", "uid") })
        XCTAssertTrue(calls[2].1.contains { $0 == ("developer_type", "Individual") })
        XCTAssertFalse(calls[0].1.contains { $0.0 == "privilege_level" })
        XCTAssertEqual(profile.deviceProfile, Data("<profile/>".utf8))
    }

    func testCertificateErrorIsReported() {
        let certificates = SamsungCertificates { _, _, _, _, _ in Data("{\"error\":\"x\"}".utf8) }
        XCTAssertThrowsError(try certificates.create(duid: "DUID1234", account: SamsungAccount(accessToken: "t", userId: "u", email: ""),
                                                     profile: SamsungProfile(store: store, duid: "DUID1234"), cancel: Cancel())) { error in
            XCTAssertEqual((error as? TvInstallError)?.error, .CERTIFICATE_FAILED)
        }
    }

    // MARK: - Sahte Samsung TV

    /// Sahte Samsung TV: sdb karşılaşması, capability, getduid, sync ile dosya, kurulum ve açma komutları.
    final class FakeTv: @unchecked Sendable {
        let toDevice = MemoryPipe()
        let fromDevice = MemoryPipe()
        private let lock = NSLock()
        private var _commands: [String] = []
        private var _pushed: [(String, Data)] = []
        private var installReplies: [String]
        private let platform: String
        private var nextRemote: UInt32 = 500
        private var thread: Background?

        var commands: [String] { lock.withLock { _commands } }
        var pushed: [(String, Data)] { lock.withLock { _pushed } }

        init(platform: String = "6.0", installReplies: [String] = ["processing result : Ok [0]"]) {
            self.platform = platform
            self.installReplies = installReplies
            thread = Background { [unowned self] in try self.serve() }
        }

        private func send(_ command: UInt32, _ a0: UInt32, _ a1: UInt32, _ data: Data = Data()) throws {
            try fromDevice.write(Adb.encode(command, a0, a1, data))
        }

        private func reply(_ input: TvReader, _ local: UInt32, _ remote: UInt32, _ text: Data) throws {
            try send(Adb.WRTE, remote, local, text)
            while try Adb.read(input).command != Adb.OKAY {}
            try send(Adb.CLSE, remote, local)
        }

        private func serve() throws {
            let input = TvReader(toDevice)
            let cnxn = try Adb.read(input)
            guard String(decoding: cnxn.data, as: UTF8.self) == "host::sdb-net-client" else { throw TvIOError.failed("karşılama yanlış") }
            try send(Adb.CNXN, 0x0100_0000, 4096, Data("device::".utf8))
            while true {
                let open = try Adb.read(input)
                if open.command == Adb.CLSE || open.command == Adb.OKAY { continue }
                if open.command == Adb.WRTE { // sync'in QUIT'i
                    try send(Adb.OKAY, open.arg1, open.arg0)
                    continue
                }
                guard open.command == Adb.OPEN else { throw TvIOError.failed("beklenmeyen paket") }
                var service = String(decoding: open.data, as: UTF8.self)
                while service.hasSuffix("\u{0}") { service.removeLast() }
                let local = open.arg0
                let remote = nextRemote
                nextRemote += 1
                try send(Adb.OKAY, remote, local)
                lock.withLock { _commands.append(service) }
                if service == "capability:" {
                    try reply(input, local, remote, Data("\u{0}\u{0}platform_version:\(platform)\nsdk_toolpath:/tmp/sdk\n".utf8))
                } else if service == "shell:0 getduid" {
                    try reply(input, local, remote, Data("DUIDTEST1234\n".utf8))
                } else if service.hasPrefix("shell:0 vd_appinstall") {
                    let text = lock.withLock { installReplies.isEmpty ? "ok" : installReplies.removeFirst() }
                    try reply(input, local, remote, Data(text.utf8))
                } else if service.hasPrefix("shell:") {
                    try reply(input, local, remote, Data("ok\n".utf8))
                } else if service == "sync:" {
                    var data = Data()
                    while true {
                        let packet = try Adb.read(input)
                        if packet.command != Adb.WRTE { continue }
                        data.append(packet.data)
                        try send(Adb.OKAY, remote, local)
                        if data.count >= 16 && String(decoding: data[(data.count - 8)..<(data.count - 4)], as: UTF8.self) == "DONE" { break }
                    }
                    let pathLength = Int(Adb.readLe32(data, 4))
                    let path = String(decoding: data[8..<(8 + pathLength)], as: UTF8.self)
                    var body = Data()
                    var offset = 8 + pathLength
                    while offset < data.count - 8 {
                        let length = Int(Adb.readLe32(data, offset + 4))
                        body.append(data[(offset + 8)..<(offset + 8 + length)])
                        offset += 8 + length
                    }
                    lock.withLock { _pushed.append((path, body)) }
                    try send(Adb.WRTE, remote, local, AdbClient.syncHeader("OKAY", 0))
                }
            }
        }

        func client() -> AdbClient { AdbClient(input: fromDevice, output: toDevice, options: .sdb, closer: { [toDevice] in toDevice.close() }) }
    }

    private func installer(_ tv: FakeTv, certificates: SamsungCertificates = SamsungCertificates { _, _, _, _, _ in
        XCTFail("sertifika istenmemeliydi")
        return Data()
    }) -> SamsungInstaller {
        let deviceKey = key!
        return SamsungInstaller(store: store, cas: cas, certificates: certificates, cancel: Cancel(), connector: {
            let client = tv.client()
            try client.handshake(deviceKey)
            return client
        })
    }

    func testInstallsWithStoredProfile() throws {
        try saveFixtureProfile("DUIDTEST1234")
        let tv = FakeTv()
        var stages: [String] = []
        let installer = installer(tv)
        installer.onProgress = { stages.append($0) }
        XCTAssertTrue(try installer.install(TVFixtures.samsung("input.wgt")))
        XCTAssertEqual(tv.pushed.count, 1)
        let (path, data) = tv.pushed[0]
        XCTAssertTrue(path.hasPrefix("/tmp/sdk/gametranslator-") && path.hasSuffix(".wgt"))
        XCTAssertEqual(try Zip.read(data).first?.name, "author-signature.xml")
        XCTAssertTrue(tv.commands.contains("shell:0 vd_appinstall GameTransT.GameTranslator \(path)"))
        XCTAssertEqual(tv.commands.last, "shell:0 execute GameTransT.GameTranslator")
        XCTAssertTrue(stages.contains("signing") && stages.contains("installing") && stages.contains("uploading:100"))
        XCTAssertFalse(stages.contains("certificate"))
    }

    func testReinstallsAfterUninstallWhenCertificateDiffers() throws {
        try saveFixtureProfile("DUIDTEST1234")
        let tv = FakeTv(installReplies: ["install failed[118, -12], reason: Author certificate not match", "processing result : Ok [0]"])
        XCTAssertTrue(try installer(tv).install(TVFixtures.samsung("input.wgt")))
        XCTAssertTrue(tv.commands.contains("shell:0 vd_appuninstall GameTransT.GameTranslator"))
        XCTAssertTrue(tv.commands.contains("shell:0 vd_appuninstall GameTransT"))
        XCTAssertEqual(tv.pushed.count, 2)
    }

    func testReportsInstallFailureAndTooOld() throws {
        try saveFixtureProfile("DUIDTEST1234")
        XCTAssertThrowsError(try installer(FakeTv(installReplies: ["install failed[1]", "install failed[2]"])).install(TVFixtures.samsung("input.wgt"))) { error in
            XCTAssertEqual((error as? TvInstallError)?.error, .INSTALL_FAILED)
            XCTAssertTrue((error as? TvInstallError)?.detail?.contains("install failed[2]") == true)
        }
        XCTAssertThrowsError(try installer(FakeTv(platform: "2.4")).install(TVFixtures.samsung("input.wgt"))) { error in
            XCTAssertEqual((error as? TvInstallError)?.error, .TOO_OLD)
        }
    }

    func testAsksForSignInThenRequestsCertificate() throws {
        XCTAssertThrowsError(try installer(FakeTv()).install(TVFixtures.samsung("input.wgt"))) { error in
            XCTAssertEqual((error as? TvInstallError)?.error, .SIGN_IN_NEEDED)
        }
        let certificates = SamsungCertificates { url, _, _, _, _ in
            switch url {
            case SamsungCertificates.authorUrl: return try TVFixtures.samsung("author.crt")
            case SamsungCertificates.distributorUrl: return try TVFixtures.samsung("distributor.crt")
            default: return Data()
            }
        }
        let installer = installer(FakeTv(), certificates: certificates)
        installer.account = { SamsungAccount(accessToken: "t", userId: "u", email: "a@b.c") }
        // Sertifikalar başka anahtarla imzalı (deneme), yine de imzalanıp kurulur; profil kaydedilir.
        XCTAssertTrue(try installer.install(TVFixtures.samsung("input.wgt")))
        XCTAssertNotNil(SamsungProfile(store: store, duid: "DUIDTEST1234").key())
        XCTAssertTrue(try self.installer(FakeTv()).status().1)
    }

    func testUnreachableUsesDiagnosis() {
        let installer = SamsungInstaller(store: store, cas: cas, certificates: SamsungCertificates(), cancel: Cancel(),
                                         connector: { throw TvInstallError(.UNREACHABLE, "kapalı") },
                                         diagnose: { TvInstallError(.WRONG_HOST_IP, "192.168.1.20") })
        XCTAssertThrowsError(try installer.install(Data())) { error in
            XCTAssertEqual((error as? TvInstallError)?.error, .WRONG_HOST_IP)
        }
    }
}
