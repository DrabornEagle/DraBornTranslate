// SPDX-License-Identifier: AGPL-3.0-only

@testable import GameTranslatorCore
import XCTest

// CouchSubs'tan (Copyright (C) 2026 Sinan Yorulmaz, AGPL-3.0) uyarlanan denemeler (Android: PackStoreTest).
final class PackStoreTests: XCTestCase {
    private final class FakeServer: PackServer {
        var packs: [RemotePack]
        var files: [String: Data]
        var downloads: [String] = []
        var status: Int?
        var listFails = false
        var identity = ""

        init(_ packs: [RemotePack], _ files: [String: Data]) {
            self.packs = packs
            self.files = files
        }

        func list() throws -> [RemotePack] {
            if listFails { throw PackDownloadError(status: nil, message: "offline") }
            return packs
        }

        func download(_ id: String) throws -> Data {
            if let status { throw PackDownloadError(status: status, message: "HTTP \(status)") }
            downloads.append(id)
            guard let data = files[id] else { throw PackDownloadError(status: 404, message: "missing") }
            return data
        }
    }

    private func remote(_ id: String, _ data: Data, version: Int64 = 1) -> RemotePack {
        RemotePack(id: id, title: id.uppercased(), version: version, sha256: PackStore.sha256(data), size: Int64(data.count), entries: 1)
    }

    private let gta = Data("gta".utf8)
    private let rdr = Data("rdr".utf8)

    private func directory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("packs-\(UUID().uuidString)")
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    private func names(_ files: [URL]) -> Set<String> { Set(files.map(\.lastPathComponent)) }

    func testShaIsLowercaseHex() {
        XCTAssertEqual(PackStore.sha256(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    func testDownloadsVerifiesAndReusesPacks() throws {
        let dir = directory()
        var now: Int64 = 1_000
        let server = FakeServer([remote("gta-v", gta), remote("rdr2", rdr)], ["gta-v": gta, "rdr2": rdr])
        let store = PackStore(directory: dir, server: server, clock: { now })
        XCTAssertEqual(try store.refresh().downloaded, ["gta-v", "rdr2"])
        XCTAssertEqual(names(store.files()), ["gta-v.json.gz", "rdr2.json.gz"])
        // Haftası dolmadan sunucuya bakılmaz.
        now += 60_000
        XCTAssertTrue(try store.refresh().skipped)
        // Zorla yenilemede aynı sürüm yeniden inmez.
        XCTAssertEqual(try store.refresh(force: true).downloaded, [])
        XCTAssertEqual(server.downloads, ["gta-v", "rdr2"])
        XCTAssertEqual(store.lastCheck, now)
        // Liste alınamazsa elde olanlar kalır.
        server.listFails = true
        XCTAssertThrowsError(try store.refresh(force: true))
        XCTAssertEqual(store.files().count, 2)
    }

    func testWeeklyRefreshUpdatesChangedAndRemovesDroppedPacks() throws {
        let dir = directory()
        var now: Int64 = 1_000
        let server = FakeServer([remote("gta-v", gta), remote("rdr2", rdr)], ["gta-v": gta, "rdr2": rdr])
        let store = PackStore(directory: dir, server: server, clock: { now })
        try store.refresh()
        let newGta = Data("gta v2".utf8)
        server.files["gta-v"] = newGta
        server.packs = [remote("gta-v", newGta, version: 2)]
        now += PackStore.refreshIntervalMs
        let result = try store.refresh()
        XCTAssertEqual(result.downloaded, ["gta-v"])
        XCTAssertEqual(result.removed, ["rdr2"])
        XCTAssertEqual(try String(contentsOf: dir.appendingPathComponent("gta-v.json.gz"), encoding: .utf8), "gta v2")
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent("rdr2.json.gz").path))
        XCTAssertEqual(store.installed().map(\.version), [2])
    }

    func testSwitchingServerChecksTheNewServerAtOnce() throws {
        let dir = directory()
        var now: Int64 = 1_000
        let old = FakeServer([remote("gta-6", gta)], ["gta-6": gta])
        old.identity = "https://old.example"
        try PackStore(directory: dir, server: old, clock: { now }).refresh()
        // Uygulama güncellendi, adres değişti: haftanın dolmasını beklemeden yeni sunucuya bakılır.
        now += 60_000
        let ours = Data("gta ours".utf8)
        let new = FakeServer([remote("gta-6", ours)], ["gta-6": ours])
        new.identity = "https://new.example"
        let store = PackStore(directory: dir, server: new, clock: { now })
        let result = try store.refresh()
        XCTAssertFalse(result.skipped)
        XCTAssertEqual(result.downloaded, ["gta-6"])
        XCTAssertEqual(try String(contentsOf: dir.appendingPathComponent("gta-6.json.gz"), encoding: .utf8), "gta ours")
        // Sonraki açılışta yine haftalık kural geçerli.
        now += 60_000
        XCTAssertTrue(try store.refresh().skipped)
    }

    func testPacksFromThePreviousServerStayUntilTheNewServerHasThem() throws {
        let dir = directory()
        var now: Int64 = 1_000
        let old = FakeServer([remote("gta-6", gta), remote("rdr2", rdr)], ["gta-6": gta, "rdr2": rdr])
        old.identity = "https://old.example"
        try PackStore(directory: dir, server: old, clock: { now }).refresh()
        let ours = Data("gta ours".utf8)
        let new = FakeServer([remote("gta-6", ours)], ["gta-6": ours])
        new.identity = "https://new.example"
        let store = PackStore(directory: dir, server: new, clock: { now })
        let first = try store.refresh()
        XCTAssertEqual(first.downloaded, ["gta-6"])
        XCTAssertEqual(first.removed, [], "eski sunucudan inen rdr2 cihazda kalır")
        XCTAssertEqual(names(store.files()), ["gta-6.json.gz", "rdr2.json.gz"])
        // Haftalık yenilemede de kalır.
        now += PackStore.refreshIntervalMs
        XCTAssertEqual(try store.refresh().removed, [])
        XCTAssertEqual(names(store.files()), ["gta-6.json.gz", "rdr2.json.gz"])
        // Yeni sunucu kendi rdr2 paketini çıkarınca onunki iner; sonra listeden kalkarsa artık silinir.
        let ourRdr = Data("rdr ours".utf8)
        new.packs.append(remote("rdr2", ourRdr))
        new.files["rdr2"] = ourRdr
        XCTAssertEqual(try store.refresh(force: true).downloaded, ["rdr2"])
        XCTAssertEqual(try String(contentsOf: dir.appendingPathComponent("rdr2.json.gz"), encoding: .utf8), "rdr ours")
        new.packs.removeAll { $0.id == "rdr2" }
        XCTAssertEqual(try store.refresh(force: true).removed, ["rdr2"])
    }

    func testFailedDownloadAfterServerSwitchRetriesOnNextLaunch() throws {
        let dir = directory()
        var now: Int64 = 1_000
        let old = FakeServer([remote("gta-6", gta)], ["gta-6": gta])
        old.identity = "https://old.example"
        try PackStore(directory: dir, server: old, clock: { now }).refresh()
        let ours = Data("gta ours".utf8)
        let new = FakeServer([remote("gta-6", ours)], ["gta-6": ours])
        new.identity = "https://new.example"
        new.status = 503
        let store = PackStore(directory: dir, server: new, clock: { now })
        XCTAssertTrue(store.serverChanged)
        now += 60_000
        XCTAssertEqual(try store.refresh().downloaded, [])
        XCTAssertEqual(try String(contentsOf: dir.appendingPathComponent("gta-6.json.gz"), encoding: .utf8), "gta", "eski paket yerinde")
        // İnternet geldi: bir sonraki açılışta (haftayı beklemeden) yeniden denenir.
        new.status = nil
        now += 60_000
        XCTAssertEqual(try store.refresh().downloaded, ["gta-6"])
        XCTAssertFalse(store.serverChanged)
        XCTAssertEqual(store.legacyIds(), [])
    }

    func testPreviousServerPacksHaveTheLowestPriority() throws {
        let dir = directory()
        let line = "Dispatch, we got them, they're pinned in!"
        let key = try XCTUnwrap(PackKey.key(line))
        func bare(_ text: String) -> Data { Data("{\"\(key)\": \"\(text)\"}".utf8) }
        let theirs = bare("eski çeviri")
        let old = FakeServer([remote("gta-6", theirs), remote("pool-gta-6", theirs)], ["gta-6": theirs, "pool-gta-6": theirs])
        old.identity = "https://old.example"
        try PackStore(directory: dir, server: old, clock: { 1_000 }).refresh()
        let ours = bare("bizim çeviri")
        let new = FakeServer([remote("gta-6", ours)], ["gta-6": ours])
        new.identity = "https://new.example"
        let store = PackStore(directory: dir, server: new, clock: { 2_000 })
        try store.refresh()
        XCTAssertEqual(store.legacyIds(), ["pool-gta-6"])
        // "pool-gta-6" ada göre sonra gelir; yine de önceki sunucunun paketi olduğu için bizimki kazanır.
        let library = GamePackLibrary(bundled: nil, userDirectory: dir, lowPriority: { store.legacyIds() })
        XCTAssertEqual(library.find(line), "bizim çeviri")
    }

    func testPreviousServerPackNeverBecomesThePlayedGameOverOurs() throws {
        let dir = directory()
        let onlyOld = "Where the hell did you park the boat?"
        let shared = "Dispatch, we got them, they're pinned in!"
        let a = try XCTUnwrap(PackKey.key(onlyOld)), b = try XCTUnwrap(PackKey.key(shared))
        let theirs = Data("{\"\(a)\": \"tekne nerede\", \"\(b)\": \"eski çeviri\"}".utf8)
        let old = FakeServer([remote("pool-gta-6", theirs)], ["pool-gta-6": theirs])
        old.identity = "https://old.example"
        try PackStore(directory: dir, server: old, clock: { 1_000 }).refresh()
        let ours = Data("{\"\(b)\": \"bizim çeviri\"}".utf8)
        let new = FakeServer([remote("gta-6", ours)], ["gta-6": ours])
        new.identity = "https://new.example"
        let store = PackStore(directory: dir, server: new, clock: { 2_000 })
        try store.refresh()
        let library = GamePackLibrary(bundled: nil, userDirectory: dir, lowPriority: { store.legacyIds() })
        // İlk satır yalnız eski pakette: oradan gelir (bizde yok). Ardından iki pakette de olan satır bizimkinden.
        XCTAssertEqual(library.find(onlyOld), "tekne nerede")
        XCTAssertEqual(library.find(shared), "bizim çeviri")
        XCTAssertEqual(library.find(shared), "bizim çeviri")
    }

    func testPreviousServerPacksOfAGameWeServeAreRemoved() throws {
        let dir = directory()
        func pack(_ id: String, _ title: String, _ data: Data) -> RemotePack {
            RemotePack(id: id, title: title, version: 1, sha256: PackStore.sha256(data), size: Int64(data.count), entries: 1)
        }
        let extra = Data("extra".utf8)
        let old = FakeServer([pack("gta-6", "Grand Theft Auto VI", gta), pack("pool-gta-6", "Grand Theft Auto VI (ek satırlar)", extra),
                              pack("red-dead-redemption-2", "Red Dead Redemption 2", rdr), pack("pool-rdr-2", "Red Dead Redemption 2 (ek satırlar)", extra)],
                             ["gta-6": gta, "pool-gta-6": extra, "red-dead-redemption-2": rdr, "pool-rdr-2": extra])
        old.identity = "https://old.example"
        try PackStore(directory: dir, server: old, clock: { 1_000 }).refresh()
        let ours = Data("gta ours".utf8)
        let new = FakeServer([pack("gta-6", "Grand Theft Auto VI", ours)], ["gta-6": ours])
        new.identity = "https://new.example"
        let store = PackStore(directory: dir, server: new, clock: { 2_000 })
        let result = try store.refresh()
        // GTA 6 bizde var: önceki sunucunun GTA 6 paketlerinin hepsi gider. RDR2 bizde yok: onunkiler kalır.
        XCTAssertEqual(result.removed, ["pool-gta-6"])
        XCTAssertEqual(names(store.files()), ["gta-6.json.gz", "red-dead-redemption-2.json.gz", "pool-rdr-2.json.gz"])
        XCTAssertEqual(store.legacyIds(), ["red-dead-redemption-2", "pool-rdr-2"])
        // Sonra RDR2 paketimizi çıkarınca onun eski paketleri de gider.
        new.packs.append(pack("red-dead-redemption-2", "Red Dead Redemption 2", Data("rdr ours".utf8)))
        new.files["red-dead-redemption-2"] = Data("rdr ours".utf8)
        XCTAssertEqual(try store.refresh(force: true).removed, ["pool-rdr-2"])
        XCTAssertEqual(store.legacyIds(), [])
    }

    func testPacksInOtherLanguagesAreIgnored() throws {
        let dir = directory()
        let arabic = Data("gta ar".utf8)
        var ar = remote("gta-6-ar", arabic)
        ar.lang = "ar"
        let server = FakeServer([remote("gta-6", gta), ar], ["gta-6": gta, "gta-6-ar": arabic])
        let store = PackStore(directory: dir, server: server, clock: { 1_000 })
        XCTAssertEqual(try store.refresh().downloaded, ["gta-6"])
        XCTAssertEqual(server.downloads, ["gta-6"], "Arapça paket inmez")
        XCTAssertEqual(PackStore.usable(server.packs).map(\.id), ["gta-6"])
        // Yalnız başka dilde paket kalırsa elde olan Türkçe paket silinmez.
        server.packs = [ar]
        XCTAssertThrowsError(try store.refresh(force: true))
        XCTAssertEqual(names(store.files()), ["gta-6.json.gz"])
    }

    func testArabicStoreDownloadsOnlyArabicPacks() throws {
        let arabic = Data("gta ar".utf8)
        var ar = remote("gta-6-ar", arabic)
        ar.lang = "ar"
        let server = FakeServer([remote("gta-6", gta), ar], ["gta-6": gta, "gta-6-ar": arabic])
        let store = PackStore(directory: directory(), server: server, clock: { 1_000 }, language: "ar")
        XCTAssertEqual(try store.refresh().downloaded, ["gta-6-ar"])
        XCTAssertEqual(server.downloads, ["gta-6-ar"], "Türkçe paket Arapça depoya inmez")
        XCTAssertEqual(names(store.files()), ["gta-6-ar.json.gz"])
        XCTAssertEqual(PackStore.usable(server.packs, language: "ar").map(\.id), ["gta-6-ar"])
    }

    func testPackLanguageFollowsTheTargetLanguage() {
        XCTAssertEqual(PackTranslation.packLanguage(sourceLanguage: "en", targetLanguage: "tr"), "tr")
        XCTAssertEqual(PackTranslation.packLanguage(sourceLanguage: "en", targetLanguage: "ar"), "ar")
        XCTAssertNil(PackTranslation.packLanguage(sourceLanguage: "en", targetLanguage: "de"))
        XCTAssertNil(PackTranslation.packLanguage(sourceLanguage: "ja", targetLanguage: "tr"))
        XCTAssertFalse(PackTranslation.applies(sourceLanguage: "en", targetLanguage: "ar"), "Türkçe sözlük Arapçada çalışmaz")
    }

    func testEmptyListRemovesNothing() throws {
        let dir = directory()
        var now: Int64 = 1_000
        let server = FakeServer([remote("gta-6", gta), remote("rdr2", rdr)], ["gta-6": gta, "rdr2": rdr])
        let store = PackStore(directory: dir, server: server, clock: { now })
        try store.refresh()
        let checked = store.lastCheck
        server.packs = []
        now += PackStore.refreshIntervalMs
        XCTAssertThrowsError(try store.refresh())
        XCTAssertEqual(store.files().count, 2)
        XCTAssertEqual(store.lastCheck, checked, "bir sonraki açılışta yeniden denenir")
    }

    func testCorruptDownloadIsNeverInstalled() throws {
        let dir = directory()
        let server = FakeServer([remote("gta-v", gta)], ["gta-v": Data("tampered".utf8)])
        let store = PackStore(directory: dir, server: server)
        XCTAssertEqual(try store.refresh().downloaded, [])
        XCTAssertTrue(store.files().isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent("gta-v.json.gz").path))
        // Kontrol zamanı ilerlemez: sonraki açılışta yeniden denenir.
        server.files["gta-v"] = gta
        XCTAssertEqual(try store.refresh().downloaded, ["gta-v"])
    }

    func testRateLimitedDownloadKeepsTheOldVersionAndRetries() throws {
        let dir = directory()
        var now: Int64 = 1_000
        let server = FakeServer([remote("gta-v", gta)], ["gta-v": gta])
        let store = PackStore(directory: dir, server: server, clock: { now })
        try store.refresh()
        let newGta = Data("gta v2".utf8)
        server.files["gta-v"] = newGta
        server.packs = [remote("gta-v", newGta, version: 2)]
        server.status = 429
        now += PackStore.refreshIntervalMs
        XCTAssertEqual(try store.refresh().downloaded, [])
        XCTAssertEqual(try String(contentsOf: dir.appendingPathComponent("gta-v.json.gz"), encoding: .utf8), "gta")
        // Kontrol zamanı ilerlemedi: sınır kalkınca hemen yeniden denenir.
        server.status = nil
        now += 1000
        XCTAssertEqual(try store.refresh().downloaded, ["gta-v"])
    }

    /// Uygulamayla gelen paketin aynısı indirilmez; sunucuda sürümü değişince iner.
    func testPacksAlreadyInTheAppAreNotDownloaded() throws {
        let dir = directory()
        var now: Int64 = 1_000
        var hashed = 0
        let gtaHash = PackStore.sha256(gta)
        let server = FakeServer([remote("gta-v", gta), remote("rdr2", rdr)], ["gta-v": gta, "rdr2": rdr])
        let store = PackStore(directory: dir, server: server, clock: { now }, bundledHashes: { hashed += 1; return [gtaHash] })
        XCTAssertEqual(try store.refresh().downloaded, ["rdr2"])
        XCTAssertEqual(server.downloads, ["rdr2"])
        XCTAssertEqual(store.files().map(\.lastPathComponent), ["rdr2.json.gz"])
        XCTAssertEqual(store.installed().map(\.id), ["rdr2"])
        XCTAssertEqual(hashed, 1)
        // Haftası dolmadan bakılmaz; dolunca aynı sürümler için uygulamadaki dosyalar yeniden özetlenmez.
        XCTAssertTrue(try store.refresh().skipped)
        now += PackStore.refreshIntervalMs
        XCTAssertEqual(try store.refresh().downloaded, [])
        XCTAssertEqual(hashed, 1)
        let newGta = Data("gta v2".utf8)
        server.files["gta-v"] = newGta
        server.packs = [remote("gta-v", newGta, version: 2), remote("rdr2", rdr)]
        XCTAssertEqual(try store.refresh(force: true).downloaded, ["gta-v"])
        XCTAssertEqual(names(store.files()), ["gta-v.json.gz", "rdr2.json.gz"])
        store.clear()
        XCTAssertTrue(store.files().isEmpty)
    }

    func testDownloadedPacksLoadIntoTheLibrary() throws {
        let dir = directory()
        let pack = try PackFixtures.data("pack-wrapped.json.gz")
        let store = PackStore(directory: dir, server: FakeServer([remote("test-game", pack)], ["test-game": pack]))
        try store.refresh()
        let library = GamePackLibrary(bundled: nil, userDirectory: store.files().first!.deletingLastPathComponent())
        XCTAssertEqual(library.infos.map(\.id), ["test-game"])
        XCTAssertEqual(library.find("Get Down!"), "Yere yat!")
    }
}

/// Ağdaki başka GameTranslator sunucuları (Android: SecondScreenTest).
final class OtherServersTests: XCTestCase {
    func testHelloTellsWhetherTheServerTranslates() {
        XCTAssertEqual(OtherServers.parseHello("HTTP/1.1 200 OK\r\n\r\n{\"active\":true,\"app\":\"gametranslator\"}"), true)
        XCTAssertEqual(OtherServers.parseHello("HTTP/1.1 200 OK\r\n\r\n{\"active\":false,\"app\":\"gametranslator\"}"), false)
        // Eski sunucular "active" göndermez: sunucudur ama çeviri yaptığı bilinmez.
        XCTAssertEqual(OtherServers.parseHello("HTTP/1.1 200 OK\r\n\r\n{\"app\":\"gametranslator\"}"), false)
        XCTAssertNil(OtherServers.parseHello("HTTP/1.1 200 OK\r\n\r\n{\"active\":true,\"app\":\"router\"}"))
        XCTAssertNil(OtherServers.parseHello("HTTP/1.1 404 Not Found\r\n\r\ngametranslator"))
        // Bizim sunucumuzun gerçek yanıt gövdesi tanınır.
        XCTAssertEqual(OtherServers.parseHello("HTTP/1.1 200 OK\r\n\r\n" + SubtitleFrames.helloBody(active: true)), true)
    }

    /// Ulaşılamayan adres beklemeden nil döner.
    func testUnreachableHostIsNotAServer() {
        let started = Date()
        XCTAssertNil(OtherServers.hello("127.0.0.1", port: 9, timeoutMs: 300))
        XCTAssertLessThan(Date().timeIntervalSince(started), 3)
    }

    func testDisplaySourcePrefersTheServerThatTranslates() {
        let idle = "192.168.1.20", busy = "192.168.1.77"
        let lock = NSLock()
        var asked = Set<String>()
        func probe(_ servers: [String: Bool]) -> (String) -> Bool? {
            { host in
                lock.lock()
                asked.insert(host)
                lock.unlock()
                return servers[host]
            }
        }
        XCTAssertEqual(OtherServers.findDisplaySource(own: "192.168.1.5", probe: probe([idle: false, busy: true])), .init(host: busy, active: true))
        XCTAssertTrue(!asked.contains("192.168.1.5") && asked.contains("192.168.1.1") && asked.contains("192.168.1.254") &&
                      asked.allSatisfy { $0.hasPrefix("192.168.1.") })
        XCTAssertEqual(asked.count, 253)
        XCTAssertEqual(OtherServers.findDisplaySource(own: "192.168.1.5", probe: probe([idle: false])), .init(host: idle, active: false))
        XCTAssertNil(OtherServers.findDisplaySource(own: "192.168.1.5", probe: probe([:])))
        XCTAssertNil(OtherServers.findDisplaySource(own: "yok") { _ in true })
    }

    func testFindReturnsTheFirstServerInAddressOrder() {
        XCTAssertEqual(OtherServers.find(own: "10.0.0.3") { ["10.0.0.40", "10.0.0.9"].contains($0) }, "10.0.0.9")
        XCTAssertNil(OtherServers.find(own: "10.0.0.3") { _ in false })
    }
}
