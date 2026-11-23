// SPDX-License-Identifier: AGPL-3.0-only
// CouchSubs'tan uyarlandı: CouchSubs (Copyright (C) 2026 Sinan Yorulmaz), AGPL-3.0, yazarının izniyle.
// Kaynak: couchsubs-android/app/src/main/java/com/couchsubs/mobile/packs/PackStore.kt (Android: PackStore.kt)

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Sunucudaki bir paket (GET /v1/packs).
public struct RemotePack: Equatable, Sendable {
    public var id: String
    public var title: String
    public var version: Int64
    public var sha256: String
    public var size: Int64
    public var entries: Int
    /// Paketin çeviri dili ("tr"); sunucu yazmadıysa Türkçe sayılır.
    public var lang: String

    public init(id: String, title: String, version: Int64, sha256: String, size: Int64, entries: Int, lang: String = "tr") {
        self.id = id
        self.title = title
        self.version = version
        self.sha256 = sha256
        self.size = size
        self.entries = entries
        self.lang = lang
    }
}

/// Paket indirilemedi; `status` HTTP kodu (429: sınır aşıldı), ağ hatasında nil.
public struct PackDownloadError: Error, CustomStringConvertible {
    public var status: Int?
    public var message: String

    public init(status: Int?, message: String) {
        self.status = status
        self.message = message
    }

    public var description: String { message }
}

/// Paket sunucusu (gerçeği `HTTPPackServer`; testlerde sahtesi).
public protocol PackServer {
    func list() throws -> [RemotePack]
    func download(_ id: String) throws -> Data
    /// Sunucunun kimliği (adresi). Değişirse (uygulama başka bir paket sunucusuna geçti) yenileme haftayı beklemez.
    var identity: String { get }
}

public extension PackServer {
    var identity: String { "" }
}

/// Sunucudaki yeni ve güncellenen oyun paketleri (ücretsiz, hesapsız): sunucudan indirilir, SHA-256 ile doğrulanıp
/// uygulamanın klasöründe saklanır, haftada bir yenilenir. Sürümü değişmeyen paket yeniden indirilmez; sunucudan kalkan
/// paket silinir. Bozuk ya da yarım dosya hiçbir zaman yüklenmez (önce geçici dosyaya yazılır, doğrulanınca yerine
/// konur). Uygulamayla gelen paketlerle aynı kimlikteki indirilmiş paket onların yerine geçer (bkz. GamePackLibrary).
///
/// `bundledHashes`: uygulamayla gelen paket dosyalarının SHA-256'ları (küçük harf hex). Sunucudaki paket bunlardan
/// biriyle aynıysa indirilmez (uygulamada zaten var); yalnız gerekince ve yenileme başına bir kez hesaplanır.
public final class PackStore: @unchecked Sendable {
    public static let refreshIntervalMs: Int64 = 7 * 24 * 60 * 60 * 1000
    /// Varsayılan paket dili (İngilizce → Türkçe). Her depo tek bir dilin paketlerini tutar (`language`); sunucudaki
    /// başka dillerin paketleri o depoya inmez, sorulmaz ve elde olanları sildirmez.
    public static let language = "tr"

    /// Sunucu listesinden bir dilin paketleri.
    public static func usable(_ packs: [RemotePack], language: String = PackStore.language) -> [RemotePack] {
        packs.filter { $0.lang.lowercased() == language.lowercased() }
    }

    /// Bu deponun dili (PackTranslation.packLanguage).
    public let language: String
    /// GameTranslator'ın paket sunucusu (server/packs).
    public static let defaultURL = "https://gametranslator-packs.gametranslator-pool.workers.dev"
    private static let manifestName = "packs.state"

    /// Paket adından oyunun adı: "Grand Theft Auto VI (ek satırlar)" ile "Grand Theft Auto VI" aynı oyundur.
    static func gameName(_ title: String) -> String {
        var name = title.trimmingCharacters(in: .whitespaces).lowercased()
        for suffix in [" (ek satırlar)", " (extra lines)"] where name.hasSuffix(suffix) { name = String(name.dropLast(suffix.count)) }
        return name
    }

    /// Küçük harf hex SHA-256.
    public static func sha256(_ data: Data) -> String { Digest.hex(Digest.sha256(data)) }

    /// `bundled`: sunucudaki sürüm uygulamayla gelenle aynı; dosyası yok. `legacy`: önceki paket sunucusundan inmiş;
    /// şimdiki sunucu aynı kimlikte paket çıkarana kadar cihazda kalır, listede yok diye silinmez.
    public struct Local: Equatable, Sendable {
        public var id: String
        public var title: String
        public var version: Int64
        public var sha256: String
        public var bundled: Bool = false
        public var legacy: Bool = false
    }

    /// Yenilemenin sonucu: indirilen ve silinen paketler; `skipped` true ise süresi gelmediği için bakılmadı.
    public struct Refresh: Equatable, Sendable {
        public var downloaded: [String] = []
        public var removed: [String] = []
        public var skipped = false
    }

    private let directory: URL
    private let server: PackServer
    private let clock: () -> Int64
    private let bundledHashes: () throws -> Set<String>
    private let lock = NSLock()
    private let files_ = FileManager.default

    /// `clock`: Unix zamanı, milisaniye.
    public init(directory: URL, server: PackServer, clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
                bundledHashes: @escaping () throws -> Set<String> = { [] }, language: String = PackStore.language) {
        self.language = language.lowercased()
        self.directory = directory
        self.server = server
        self.clock = clock
        self.bundledHashes = bundledHashes
    }

    /// Klasör (GamePackLibrary'nin kullanıcı klasörü olarak verilir).
    public var folder: URL { directory }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    /// Doğrulanmış, yüklenmeye hazır paket dosyaları.
    public func files() -> [URL] {
        locked { manifest().packs.filter { !$0.bundled }.map { packFile($0.id) }.filter { isFile($0) } }
    }

    public func installed() -> [Local] { locked { manifest().packs.filter { !$0.bundled } } }

    public var lastCheck: Int64 { locked { manifest().checked } }

    /// Elde paket var ama son yenileme başka bir sunucudandı (uygulama yeni paket sunucusuna geçti, henüz bakılmadı).
    /// Paketleri sormadan kurmayan uygulamalar (Mac, Windows) bu geçişi sormadan yapar: aynı oyunların yeni sürümü.
    public var serverChanged: Bool {
        locked {
            let (_, packs, source) = manifest()
            return !packs.isEmpty && source != server.identity
        }
    }

    /// Önceki paket sunucusundan inmiş, şimdiki sunucuda karşılığı olmayan paketler. Kütüphanede en düşük önceliktedir:
    /// aynı satır şimdiki sunucunun bir paketinde de varsa onunki kullanılır (bkz. GamePackLibrary `lowPriority`).
    public func legacyIds() -> Set<String> {
        locked { Set(manifest().packs.filter { $0.legacy && !$0.bundled }.map(\.id)) }
    }

    /// Süresi geldiyse (ya da `force`) sunucuya bakar ve değişen paketleri indirir (bloklar). Liste alınamazsa hata
    /// fırlatır, elde olanlar kalır; inemeyen paket bir sonraki açılışta yeniden denenir.
    @discardableResult
    public func refresh(force: Bool = false) throws -> Refresh {
        try locked { () throws -> Refresh in
            let (checked, stored, source) = manifest()
            // Başka bir sunucudan gelindiyse (uygulama güncellendi, adres değişti) hemen bakılır; oradan inen paketler
            // önceki sunucunun paketleri sayılır.
            let switched = source != server.identity
            let current = switched ? stored.map { local -> Local in
                var local = local
                if !local.bundled { local.legacy = true }
                return local
            } : stored
            if !force && !switched && !current.isEmpty && clock() - checked < Self.refreshIntervalMs { return Refresh(skipped: true) }
            let remote = Self.usable(try server.list(), language: language)
            // Boş liste sunucu tarafında bir sorundur: hiçbir paket silinmez, bir sonraki açılışta yeniden denenir.
            if remote.isEmpty { throw PackDownloadError(status: nil, message: "empty list") }
            try files_.createDirectory(at: directory, withIntermediateDirectories: true)
            var kept: [Local] = []
            var downloaded: [String] = []
            var bundled: Set<String>?
            for pack in remote {
                let existing = current.first { $0.id == pack.id }
                let file = packFile(pack.id)
                let sameVersion = existing.map { $0.version == pack.version && $0.sha256.lowercased() == pack.sha256.lowercased() } ?? false
                if let existing, existing.bundled, sameVersion {
                    kept.append(existing)
                    continue
                }
                if var existing, !existing.bundled, sameVersion, isFile(file),
                   let data = try? Data(contentsOf: file), Self.sha256(data) == pack.sha256.lowercased() {
                    existing.legacy = false
                    kept.append(existing)
                    continue
                }
                // Uygulamayla gelen paketin aynısı indirilmez.
                let inApp: Set<String>
                if let bundled { inApp = bundled } else {
                    inApp = (try? bundledHashes()) ?? []
                    bundled = inApp
                }
                if inApp.contains(pack.sha256.lowercased()) {
                    try? files_.removeItem(at: file)
                    kept.append(Local(id: pack.id, title: pack.title, version: pack.version, sha256: pack.sha256.lowercased(), bundled: true))
                    continue
                }
                // Bu paket şimdi inmedi ya da bozuk indi: eski sürümü varsa o kalır, sonraki yenilemede yeniden denenir.
                func keepOld() {
                    if var existing, !existing.bundled, isFile(file) {
                        // Önceki sunucunun paketi yerinde kalır; yenisi sonraki yenilemede yeniden denenir.
                        if !existing.legacy { existing.version = -1 }
                        kept.append(existing)
                    }
                }
                let data: Data
                do {
                    data = try server.download(pack.id)
                } catch is PackDownloadError {
                    keepOld()
                    continue
                }
                if Self.sha256(data) != pack.sha256.lowercased() {
                    keepOld()
                    continue
                }
                let temporary = directory.appendingPathComponent("\(pack.id).download")
                try data.write(to: temporary)
                try? files_.removeItem(at: file)
                try files_.moveItem(at: temporary, to: file)
                kept.append(Local(id: pack.id, title: pack.title, version: pack.version, sha256: pack.sha256.lowercased()))
                downloaded.append(pack.id)
            }
            // Önceki sunucudan inen paketler: şimdiki sunucu aynı oyunun bir paketini sunuyorsa (ek satır paketleri dahil)
            // kaldırılır; sunmuyorsa cihazda kalır.
            let servedGames = Set(remote.map { Self.gameName($0.title) })
            let replacedLegacy = current.filter { local in
                local.legacy && !local.bundled && !remote.contains { $0.id == local.id } && servedGames.contains(Self.gameName(local.title))
            }.map(\.id)
            for local in current where local.legacy && !remote.contains(where: { $0.id == local.id }) && !replacedLegacy.contains(local.id)
                && isFile(packFile(local.id)) {
                kept.append(local)
            }
            let removed = current.filter { local in !local.bundled && !local.legacy && !remote.contains { $0.id == local.id } }.map(\.id)
                + replacedLegacy
            for id in removed { try? files_.removeItem(at: packFile(id)) }
            // Bir paket inemediyse (sürüm -1, ya da önceki sunucunun paketi yerinde kaldıysa) bir sonraki açılışta
            // yeniden denensin diye kontrol zamanı ilerletilmez.
            let retry = kept.contains { $0.version == -1 } ||
                kept.contains { local in local.legacy && remote.contains { $0.id == local.id } }
            // Süresi dolmuş sayılır: haftalık süre dolmamış olsa da bir sonraki açılışta bakılır (ör. geçişte paket inemedi).
            try writeManifest(retry ? clock() - Self.refreshIntervalMs : clock(), kept, source: server.identity)
            return Refresh(downloaded: downloaded, removed: removed)
        }
    }

    /// "İndirilen paketleri sil".
    public func clear() {
        locked {
            for item in (try? files_.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [] {
                try? files_.removeItem(at: item)
            }
        }
    }

    private func packFile(_ id: String) -> URL { directory.appendingPathComponent("\(id).json.gz") }

    private func isFile(_ url: URL) -> Bool {
        var directory: ObjCBool = false
        return files_.fileExists(atPath: url.path, isDirectory: &directory) && !directory.boolValue
    }

    private static func int64(_ value: Any?) -> Int64 {
        if let value = value as? Int64 { return value }
        if let value = value as? Int { return Int64(value) }
        if let value = value as? Double { return Int64(value) }
        if let value = value as? NSNumber { return value.int64Value }
        return 0
    }

    private func manifest() -> (checked: Int64, packs: [Local], source: String) {
        let url = directory.appendingPathComponent(Self.manifestName)
        guard let data = try? Data(contentsOf: url),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return (0, [], "") }
        let packs = (json["packs"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }.compactMap { item -> Local? in
            guard let id = item["id"] as? String else { return nil }
            let bundled = (item["bundled"] as? Bool) ?? (item["bundled"] as? NSNumber)?.boolValue ?? false
            let legacy = (item["legacy"] as? Bool) ?? (item["legacy"] as? NSNumber)?.boolValue ?? false
            return Local(id: id, title: item["title"] as? String ?? id, version: Self.int64(item["version"]),
                         sha256: item["sha256"] as? String ?? "", bundled: bundled, legacy: legacy)
        }
        // Eski kayıtlarda "source" yoktur (o zamanki tek sunucu): boş dize, bugünkü sunucudan farklı sayılır.
        return (Self.int64(json["checked"]), packs, json["source"] as? String ?? "")
    }

    private func writeManifest(_ checked: Int64, _ packs: [Local], source: String) throws {
        let json: [String: Any] = [
            "checked": checked,
            "source": source,
            "packs": packs.map { ["id": $0.id, "title": $0.title, "version": $0.version, "sha256": $0.sha256, "bundled": $0.bundled,
                                  "legacy": $0.legacy] as [String: Any] },
        ]
        let data = try JSONSerialization.data(withJSONObject: json)
        let target = directory.appendingPathComponent(Self.manifestName)
        let temporary = directory.appendingPathComponent(Self.manifestName + ".tmp")
        try data.write(to: temporary)
        try? files_.removeItem(at: target)
        try files_.moveItem(at: temporary, to: target)
    }
}

/// Paket sunucusu: GET /v1/packs (liste), GET /v1/packs/{id} (gzip'li paket). Bloklar; ana iş parçacığından çağrılmaz.
public final class HTTPPackServer: PackServer, @unchecked Sendable {
    private let baseURL: String
    private let session: URLSession

    public init(baseURL: String = PackStore.defaultURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    public var identity: String { baseURL }

    public func list() throws -> [RemotePack] {
        let body = try request("\(baseURL)/v1/packs", timeout: 15)
        guard let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else {
            throw PackDownloadError(status: nil, message: "bad list")
        }
        return (json["packs"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }.compactMap { item in
            guard let id = item["id"] as? String, let sha = item["sha256"] as? String else { return nil }
            func number(_ key: String) -> Int64 {
                if let v = item[key] as? Int { return Int64(v) }
                if let v = item[key] as? Double { return Int64(v) }
                if let v = item[key] as? NSNumber { return v.int64Value }
                return 0
            }
            let lang = (item["lang"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "tr"
            return RemotePack(id: id, title: item["title"] as? String ?? "", version: number("version"), sha256: sha,
                              size: number("size"), entries: Int(number("entries")), lang: lang)
        }
    }

    public func download(_ id: String) throws -> Data {
        let escaped = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        return try request("\(baseURL)/v1/packs/\(escaped)", timeout: 60)
    }

    private func request(_ address: String, timeout: TimeInterval) throws -> Data {
        guard let url = URL(string: address) else { throw PackDownloadError(status: nil, message: "bad url") }
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        let done = DispatchSemaphore(value: 0)
        var outcome: Result<Data, PackDownloadError> = .failure(PackDownloadError(status: nil, message: "network"))
        session.dataTask(with: request) { data, response, error in
            defer { done.signal() }
            if let error {
                outcome = .failure(PackDownloadError(status: nil, message: error.localizedDescription))
                return
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if !(200...299).contains(status) {
                outcome = .failure(PackDownloadError(status: status, message: "HTTP \(status)"))
                return
            }
            outcome = .success(data ?? Data())
        }.resume()
        done.wait()
        return try outcome.get()
    }
}
