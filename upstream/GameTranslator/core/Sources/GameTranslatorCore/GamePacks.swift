// SPDX-License-Identifier: AGPL-3.0-only
// CouchSubs'tan uyarlandı: CouchSubs (Copyright (C) 2026 Sinan Yorulmaz), AGPL-3.0, yazarının izniyle.

import Foundation

/// Bir oyunun çeviri paketi (Windows: GamePack, Android: GamePack.kt). Dosya gzip'li JSON'dur:
/// `{"format":1|2,"id":…,"title":…,"entries":{anahtar: Türkçe}}`; yalnız `{anahtar: Türkçe}` nesnesi de kabul edilir
/// (adı dosya adından gelir). Biçim 2 ayrıca `segments` (oyunun ayrı gösterdiği parçalar ve çok cümleli satırların
/// cümleleri; aynı düzen) ve `fuzzy` (OCR'ın bozduğu satırı bulmak için imzalar, bkz. `PackFuzzy`) taşır. İngilizce metin yoktur.
///
/// Satırlar sıralı anahtar dizisi ve tek bir UTF-8 bayt bloğunda tutulur (sözlükten çok daha az bellek), ikili aramayla bulunur.
public final class GamePack: @unchecked Sendable {
    public let id: String
    public let title: String
    let entries: Table
    let segments: Table
    /// Bulanık imzalar (biçim 2); yoksa ya da bozuksa nil.
    let fuzzy: PackFuzzyIndex?

    /// Sıralı anahtarlar ve UTF-8 metin bloğu.
    struct Table {
        let keys: [UInt64]
        let offsets: [Int]
        let blob: [UInt8]

        static let empty = Table(keys: [], offsets: [0], blob: [])

        var count: Int { keys.count }

        func index(_ key: UInt64) -> Int? {
            var low = 0, high = keys.count
            while low < high {
                let middle = (low + high) / 2
                if keys[middle] < key { low = middle + 1 } else { high = middle }
            }
            return low < keys.count && keys[low] == key ? low : nil
        }

        subscript(key: UInt64) -> String? {
            guard let index = index(key) else { return nil }
            return String(decoding: blob[offsets[index]..<offsets[index + 1]], as: UTF8.self)
        }

        func contains(_ key: UInt64) -> Bool { index(key) != nil }
    }

    /// Satırları geldiği sırayla düz dizilere yazar, sonunda anahtara göre sıralı `Table` kurar.
    struct TableBuilder {
        private var keys: [UInt64] = []
        private var ends: [Int] = []
        private var blob: [UInt8] = []

        var isEmpty: Bool { keys.isEmpty }

        mutating func add(_ key: UInt64, _ text: String) {
            blob.append(contentsOf: text.utf8)
            keys.append(key)
            ends.append(blob.count)
        }

        func build() -> Table {
            guard !keys.isEmpty else { return .empty }
            // Kararlı sıralama: aynı anahtarın kayıtları geliş sırasıyla yan yana kalır, sonuncusu alınır.
            let order = keys.indices.sorted { keys[$0] != keys[$1] ? keys[$0] < keys[$1] : $0 < $1 }
            var sortedKeys: [UInt64] = []
            var offsets: [Int] = [0]
            var text: [UInt8] = []
            sortedKeys.reserveCapacity(order.count)
            offsets.reserveCapacity(order.count + 1)
            text.reserveCapacity(blob.count)
            for position in order.indices {
                let index = order[position]
                if position + 1 < order.count && keys[order[position + 1]] == keys[index] { continue }
                let from = index == 0 ? 0 : ends[index - 1]
                text.append(contentsOf: blob[from..<ends[index]])
                sortedKeys.append(keys[index])
                offsets.append(text.count)
            }
            return Table(keys: sortedKeys, offsets: offsets, blob: text)
        }
    }

    init(id: String, title: String, entries: Table, segments: Table, fuzzy: PackFuzzyIndex?) {
        self.id = id
        self.title = title
        self.entries = entries
        self.segments = segments
        self.fuzzy = fuzzy
    }

    /// Tam satır sayısı (parçalar hariç).
    public var count: Int { entries.count }
    public var segmentCount: Int { segments.count }
    /// Bulanık imzalı satır sayısı (0: imza yok).
    public var fuzzyCount: Int { fuzzy?.count ?? 0 }

    /// Anahtarın (sayı hâli) tam satır Türkçesi.
    public subscript(key: UInt64) -> String? { entries[key] }

    /// Anahtarın parça ("segments") Türkçesi.
    public func segment(_ key: UInt64) -> String? { segments[key] }

    /// Anahtar pakette (tam satır ya da parça) var mı.
    public func contains(_ key: UInt64) -> Bool { entries.contains(key) || segments.contains(key) }

    /// `name`: dosya adı uzantısız ("mafia-the-old-country"); düz biçimde kimlik ve başlık olur.
    public static func decode(_ data: Data, name: String) throws -> GamePack {
        var bytes: [UInt8]
        if Gzip.isGzip(data) {
            do { bytes = [UInt8](try Gzip.decompress(data)) } catch { throw InvalidPackError("Paket açılamadı") }
        } else {
            bytes = [UInt8](data)
        }
        var entries = TableBuilder()
        var segments = TableBuilder()
        // İmzaların sırası: dosyadaki bütün geçerli anahtarlar (Türkçesi boş kalanlar da).
        var order: [UInt64] = []
        func add(_ target: inout TableBuilder, _ key: String, _ value: String) {
            guard let number = PackKey.number(key.lowercased()) else { return }
            order.append(number)
            let text = cleaned(value)
            // Aynı anahtar iki kez varsa sonraki kazanır (TableBuilder.build).
            if !text.isEmpty { target.add(number, text) }
        }
        let parsed = try PackJson.parse(bytes, onEntry: { add(&entries, $0, $1) }, onSegment: { add(&segments, $0, $1) })
        bytes = []
        guard parsed.wrapped else {
            for (key, value) in parsed.rootStrings { add(&entries, key, value) }
            guard !entries.isEmpty else { throw InvalidPackError("Pakette satır yok") }
            return GamePack(id: name, title: name, entries: entries.build(), segments: .empty, fuzzy: nil)
        }
        if let format = parsed.format, format != 1, format != 2 { throw InvalidPackError("Desteklenmeyen paket biçimi") }
        let id = parsed.id.flatMap { $0.isEmpty ? nil : $0 } ?? name
        guard !entries.isEmpty else { throw InvalidPackError("Pakette satır yok") }
        let fuzzy = parsed.fuzzy.flatMap { PackFuzzyIndex.decode($0, order: Array(Set(order)).sorted()) }
        return GamePack(id: id, title: parsed.title.flatMap { $0.isEmpty ? nil : $0 } ?? id,
                        entries: entries.build(), segments: segments.build(), fuzzy: fuzzy)
    }

    /// Paketteki Türkçede de oyunun biçim etiketleri kalmış olabilir ("<b><h>…</>"); TV'de görünmesinler.
    public static func cleaned(_ text: String) -> String {
        guard text.contains("<") else { return text.trimmingCharacters(in: .whitespacesAndNewlines) }
        return PackKey.stripTags(text, replacement: "").split(separator: " ").joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// Pakette bulunan satırın Türkçesi ve hangi paketten geldiği (ana ekrandaki "… paketi kullanılıyor" için).
public struct PackMatch: Equatable, Sendable {
    public var text: String
    public var packId: String?

    public init(_ text: String, packId: String? = nil) {
        self.text = text
        self.packId = packId
    }
}

/// Uygulamayla gelen ve kullanıcının klasörüne bırakılan paketler (Windows: GamePackLibrary). Sıra: uygulamanınkiler,
/// sonra kullanıcınınkiler (dosya adına göre); aynı anahtarda sonraki kazanır, aynı kimlikli pakette kullanıcının dosyası
/// uygulamanınkinin yerine geçer. Ayarlarda kapatılan paketlere bakılmaz. Her paket ayrı tutulur ve sondan başa aranır
/// (Windows'un birleşik sözlüğüyle aynı sonuç); böylece satırın hangi oyunun paketinden geldiği bilinir.
public final class GamePackLibrary: @unchecked Sendable {
    public struct Info: Equatable, Sendable {
        public let id: String
        public let title: String
        public let entries: Int
        /// Kullanıcının klasöründen geldiyse false.
        public let builtIn: Bool
    }

    /// Uygulamayla gelen bir paket dosyası: adı ve okuyucusu. Dosyalar sırayla, biri çözüldükten sonra okunur.
    public typealias BundledFile = (name: String, read: () throws -> Data)

    private let bundled: (() -> [BundledFile])?
    private let userDirectory: URL?
    private let lock = NSLock()
    private var loaded = false
    private var packs: [(pack: GamePack, builtIn: Bool)] = []
    /// Her yeniden yüklemede artar: kilitsiz arama bittiğinde kütüphane değişmiş mi anlaşılır.
    private var generation = 0
    private var disabled: Set<String> = []
    private var pinnedId: String?
    private var detectedId: String?
    /// Son tam eşleşmenin geldiği paket: bulanık arama yalnız orada yapılır (oynanan oyun). Hiç tam eşleşme olmadıysa
    /// bulanık arama yapılmaz.
    private var scope: Int?
    /// Kapsam yalnız o pakette olan bir satırla belirlendi (oynanan oyun kesin).
    private var scopeKnown = false

    /// Kullanıcı klasöründe olup en düşük öncelikli sayılan paket kimlikleri (önceki paket sunucusundan kalanlar).
    private let lowPriority: (() -> Set<String>)?
    /// Son yüklemede düşük öncelikli sayılanlar (kilit altında).
    private var lowLoaded: Set<String> = []

    /// `bundled`: uygulamayla gelen paket dosyaları (ad, okuyucu). `userDirectory`: denemecilerin yeni paket bıraktığı klasör.
    /// `lowPriority`: kullanıcı klasöründeki bu kimlikler önce yüklenir; aynı satır başka bir pakette de varsa o kazanır.
    public init(bundled: (() -> [BundledFile])?, userDirectory: URL?, lowPriority: (() -> Set<String>)? = nil) {
        self.bundled = bundled
        self.userDirectory = userDirectory
        self.lowPriority = lowPriority
    }

    /// Klasördeki paket dosyaları uygulamayla gelen paketler sayılır (ör. uygulama paketindeki "packs" klasörü).
    public static func files(in directory: URL) -> [BundledFile] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.filter(isPackFile).sorted().map { name in
            (name, { try Data(contentsOf: directory.appendingPathComponent(name)) })
        }
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    /// Kurulu paketler; ilk çağrıda yüklenir.
    public var infos: [Info] {
        locked {
            loadIfNeeded()
            return packs.map { Info(id: $0.pack.id, title: $0.pack.title, entries: $0.pack.count, builtIn: $0.builtIn) }
        }
    }

    /// Yüklü paketin kendisi (denemeler ve istatistik için).
    public func pack(id: String) -> GamePack? {
        locked {
            loadIfNeeded()
            return packs.first { $0.pack.id == id }?.pack
        }
    }

    /// Kapatılan paket kimlikleri (ayarlardan).
    public func setDisabled(_ ids: Set<String>) {
        locked { disabled = ids }
    }

    /// Kullanıcının elle seçtiği oyunun paket kimliği; nil ise otomatik. Seçiliyse yalnız o paket aranır ve bulanık
    /// arama ilk satırdan itibaren o pakette yapılır.
    public var pinned: String? {
        get { locked { pinnedId } }
        set { locked { pinnedId = Self.nonBlank(newValue) } }
    }

    /// PS5'te çalışan oyunun paketi (bkz. `Ps5Status`); elle seçim yoksa elle seçilmiş gibi davranılır.
    public var detected: String? {
        get { locked { detectedId } }
        set { locked { detectedId = Self.nonBlank(newValue) } }
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return value
    }

    /// PS5'in bildirdiği oyun adına uyan paketin kimliği ("Mafia: The Old Country" ↔ "mafia-the-old-country"); harf ve
    /// rakamlar karşılaştırılır, biri öbürünü içeriyorsa uyar (en uzun başlık seçilir). Yoksa nil. Paketleri yükler.
    public func findByGameName(_ name: String?) -> String? {
        func plain(_ text: String) -> String { String(text.lowercased().filter { $0.isLetter || $0.isNumber }) }
        guard let name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let wanted = plain(name)
        guard wanted.count >= 3 else { return nil }
        return infos.filter { info in
            let title = plain(info.title)
            return title.count >= 3 && (title.contains(wanted) || wanted.contains(title))
        }.max { $0.title.count < $1.title.count }?.id
    }

    /// Seçili (ya da PS5'te çalışan) oyunun paketi yüklü ve kapatılmamışsa sırası; kilit altında çağrılır.
    private func pinnedIndex() -> Int? {
        guard let id = pinnedId ?? detectedId, !disabled.contains(id) else { return nil }
        return packs.firstIndex { $0.pack.id == id }
    }

    /// Oyun belli mi: seçilen ya da PS5'in bildirdiği oyunun paketi var (paket beklemeden, kapıya bakmadan kullanılır).
    public var gameKnown: Bool { locked { loaded && pinnedIndex() != nil } }

    /// Paketleri (yeniden) okur. Bozuk dosya sessizce atlanır.
    public func reload() {
        locked {
            loaded = false
            // Eski paketler yenileri açılmadan bırakılır: ikisi birden bellekte tutulmasın.
            packs = []
            loadIfNeeded()
        }
    }

    /// Yüklemeyi şimdi yapar (açılışta arka planda; ilk altyazı beklemesin).
    public func warmUp() { _ = infos }

    /// Satırın tamamı için paketteki Türkçe (parçalara bölme yok; bkz. `PackLookup`).
    public func find(_ text: String) -> String? { findWithPack(text)?.text }

    /// Satırın tamamı için paketteki Türkçe ve paketin kimliği. Bulunursa bulanık aramanın kapsamı satırın paketine geçer.
    public func findWithPack(_ text: String) -> PackMatch? {
        let (current, only, version, off) = locked { () -> ([(pack: GamePack, builtIn: Bool)], Int?, Int, Set<String>) in
            loadIfNeeded()
            return (packs, pinnedIndex(), generation, disabled)
        }
        guard !current.isEmpty, let number = PackKey.keyNumber(text) else { return nil }
        if let only {
            // Seçilen oyun: yalnız onun paketi (aynı satır başka oyunda başka çevrilmiş olabilir).
            let pack = current[only].pack
            locked {
                if generation == version { (scope, scopeKnown) = (only, false) }
            }
            return (pack[number] ?? pack.segment(number)).map { PackMatch($0, packId: pack.id) }
        }
        guard let found = Self.lookup(current, number, disabled: off) else { return nil }
        var owners = current.indices.filter { !off.contains(current[$0].pack.id) && current[$0].pack.contains(number) }
        guard !owners.isEmpty else { return found }
        return locked { () -> PackMatch? in
            guard generation == version else { return found }
            // Önceki sunucudan kalan paket, satır başka bir pakette de varsa oynanan oyun sayılmaz.
            let preferred = owners.filter { !lowLoaded.contains(current[$0].pack.id) }
            if !preferred.isEmpty { owners = preferred }
            let first = owners[0]
            if let chosen = scope, chosen < current.count {
                // Oynanan oyunun paketinde de var: o oyunun çevirisi.
                if owners.contains(chosen) {
                    if owners.count == 1 { scopeKnown = true }
                    let pack = current[chosen].pack
                    return (pack[number] ?? pack.segment(number)).map { PackMatch($0, packId: pack.id) } ?? found
                }
                // Oyun belliyken onun paketinde olmayan kısa, her oyunda geçen satır ("Janitor: Okay,") başka oyunun
                // paketinden alınmaz. Uzun satır başka oyuna geçildiğini gösterir; kapsam ona geçer.
                if scopeKnown && Self.isShortLine(text) { return nil }
            }
            scope = first
            scopeKnown = owners.count == 1
            return found
        }
    }

    /// Adı çıkarılınca dörtten az sözcük: hangi oyuna ait olduğu satırdan anlaşılamaz.
    static func isShortLine(_ text: String) -> Bool {
        var speech = Substring(text)
        if let colon = text.firstIndex(of: ":") {
            let offset = text.distance(from: text.startIndex, to: colon)
            if offset > 0 && offset <= 24 { speech = text[text.index(after: colon)...] }
        }
        return speech.split(whereSeparator: \.isWhitespace).filter { $0.contains(where: \.isLetter) }.count < 4
    }

    /// OCR hatalarına dayanıklı arama: oynanan oyunun paketinde (son tam eşleşmenin ya da seçilen oyunun paketi) satıra en
    /// çok benzeyen satırın Türkçesi: önce tek OCR düzeltmesiyle (`PackFuzzy.variants`) tam anahtar, sonra bulanık imza.
    /// Benzerlik eşiği geçmezse ya da kapsam yoksa nil.
    public func nearestWithPack(_ text: String) -> PackMatch? {
        let (current, chosen, pinned, off) = locked { () -> ([(pack: GamePack, builtIn: Bool)], Int?, Int?, Set<String>) in
            loadIfNeeded()
            return (packs, scope, pinnedIndex(), disabled)
        }
        let pack: GamePack
        let lookup: (UInt64) -> PackMatch?
        if let pinned {
            // Seçilen oyun: bulanık arama ilk satırdan itibaren onun paketinde, Türkçesi de o paketten.
            let only = current[pinned].pack
            pack = only
            lookup = { key in (only[key] ?? only.segment(key)).map { PackMatch($0, packId: only.id) } }
        } else {
            guard let chosen, chosen < current.count, !off.contains(current[chosen].pack.id) else { return nil }
            pack = current[chosen].pack
            lookup = { Self.lookup(current, $0, disabled: off) }
        }
        // Tek OCR düzeltmesiyle tam bulunan satır bulanık aramadan önce gelir.
        if let corrected = Self.corrected(text, pack: pack, lookup: lookup) { return corrected }
        guard let key = pack.fuzzy?.nearest(text, translation: { lookup($0)?.text }) else { return nil }
        return lookup(key)
    }

    public func nearest(_ text: String) -> String? { nearestWithPack(text)?.text }

    /// Tek OCR düzeltmesiyle `pack`'te tam bulunan satırın Türkçesi; düzeltmelerin hepsi aynı Türkçeye çıkmıyorsa nil.
    private static func corrected(_ text: String, pack: GamePack, lookup: (UInt64) -> PackMatch?) -> PackMatch? {
        var found: PackMatch?
        for variant in PackFuzzy.variants(text) {
            // ASCII canon metin yeniden sadeleştirilince değişmez; doğrudan özetlenir.
            let number = variant.utf8.allSatisfy({ $0 < 128 }) ? PackKey.canonNumber(variant) : PackKey.keyNumber(variant)
            guard let number, pack.contains(number), let turkish = lookup(number) else { continue }
            if let previous = found, previous.text != turkish.text { return nil }
            found = turkish
        }
        return found
    }

    /// Bulanık aramanın yapılacağı paketin kimliği (seçilen oyunun ya da son tam eşleşmenin paketi); yoksa nil.
    public var searchScope: String? {
        locked {
            guard let index = pinnedIndex() ?? scope, index < packs.count else { return nil }
            return packs[index].pack.id
        }
    }

    /// Oynanan oyunun adı: seçilen oyunun ya da son tam eşleşmenin geldiği paketin başlığı; yoksa nil.
    public var scopeTitle: String? {
        locked {
            guard let index = pinnedIndex() ?? scope, index < packs.count else { return nil }
            return packs[index].pack.title
        }
    }

    /// Anahtarın Türkçesi: açık paketlerde sondan başa tam satır, yoksa parça (bir oyunun tam satırı başka oyunun parçasına yenilmez).
    private static func lookup(_ current: [(pack: GamePack, builtIn: Bool)], _ number: UInt64, disabled: Set<String>) -> PackMatch? {
        for entry in current.reversed() where !disabled.contains(entry.pack.id) {
            if let text = entry.pack[number] { return PackMatch(text, packId: entry.pack.id) }
        }
        for entry in current.reversed() where !disabled.contains(entry.pack.id) {
            if let text = entry.pack.segment(number) { return PackMatch(text, packId: entry.pack.id) }
        }
        return nil
    }

    /// Kilit altında çağrılır.
    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        var list: [(pack: GamePack, builtIn: Bool)] = []
        func add(_ fileName: String, _ data: Data, builtIn: Bool) {
            guard let pack = try? GamePack.decode(data, name: Self.baseName(fileName)) else { return }
            list.removeAll { $0.pack.id == pack.id }
            list.append((pack, builtIn))
        }
        for file in (bundled?() ?? []).sorted(by: { $0.name < $1.name }) where Self.isPackFile(file.name) {
            guard let data = try? file.read() else { continue }
            add(file.name, data, builtIn: true)
        }
        if let userDirectory {
            // Arama listenin sonundan başlar: düşük öncelikliler başa.
            let low = lowPriority?() ?? []
            lowLoaded = low
            let files = Self.files(in: userDirectory)
            let ordered = files.filter { low.contains(Self.baseName($0.name)) } + files.filter { !low.contains(Self.baseName($0.name)) }
            for file in ordered {
                guard let data = try? file.read() else { continue }
                add(file.name, data, builtIn: false)
            }
        }
        packs = list
        generation += 1
        scope = nil
        scopeKnown = false
    }

    public static func isPackFile(_ name: String) -> Bool { name.hasSuffix(".json.gz") || name.hasSuffix(".json") }

    /// "mafia.json.gz" → "mafia"
    public static func baseName(_ fileName: String) -> String {
        var name = (fileName as NSString).lastPathComponent
        for suffix in [".gz", ".json"] where name.hasSuffix(suffix) { name.removeLast(suffix.count) }
        return name
    }
}
