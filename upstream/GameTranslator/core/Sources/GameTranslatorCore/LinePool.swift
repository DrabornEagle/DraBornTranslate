// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Ortak havuza gidecek satırın süzgeci: havuz yalnız pakette olmayan İngilizce oyun konuşmalarını toplamalı (PS5'in
/// Türkçe arayüzü, yarım okumalar ve oyun arayüzü elenir). Windows'taki PoolFilter.cs, Android'deki PoolFilter.kt ve
/// sunucudaki server/pool/src/filter.js ile aynı kurallar.
public enum PoolFilter {
    /// Bundan kısa ya da daha az sözcüklü satır ancak cümle noktalamasıyla bitiyorsa alınır ("Get down!").
    public static let minShortLength = 15
    public static let minShortWords = 3
    /// Tümü büyük harfli, noktalamasız ve bu kadar ya da daha az sözcüklü satır arayüz yazısıdır.
    public static let maxCapsUiWords = 6

    /// Satır İngilizce oyun konuşmasına benziyor mu (Türkçe, sistem yazısı ya da yarım okuma değil).
    public static func accepts(_ text: String) -> Bool {
        !isFragment(text) && !looksTurkish(text) && !looksLikeSystemText(text)
    }

    static let danglingWords: Set<String> = [
        "the", "a", "an", "to", "of", "and", "or", "but", "with", "for", "from", "my", "your", "his", "her", "their", "our",
        "at", "in", "on", "by", "into", "than", "that", "if", "as", "is", "are", "was", "were",
    ]

    /// Yarım ya da arayüz okuması (kısa ve noktalamasız, küçük harfle başlayan, bağlaçla biten, kısa BÜYÜK HARF).
    static func isFragment(_ raw: String) -> Bool {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let words = text.split(whereSeparator: \.isWhitespace).filter { $0.contains(where: \.isLetter) }
        let ends = SubtitleStabilizer.endsSentence(text)
        if !ends && (words.count < minShortWords || text.unicodeScalars.count < minShortLength) { return true }
        if let first = text.first, first.isLetter, first.isLowercase { return true }
        if ends { return false }
        if let last = words.last, danglingWords.contains(String(last.lowercased().filter(\.isLetter))) { return true }
        let letters = text.filter(\.isLetter)
        return !letters.isEmpty && !letters.contains(where: \.isLowercase) && words.count <= maxCapsUiWords
    }

    private static let turkishOnly: Set<Character> = ["ğ", "Ğ", "ş", "Ş", "ı", "İ"]
    private static let turkishShared: Set<Character> = ["ü", "Ü", "ö", "Ö", "ç", "Ç"]

    static let turkishWords: [String] = [
        "icin", "lutfen", "bekleyin", "oyunu", "oyun", "oyunlar", "kapat", "ekran", "ekranda", "ekrani", "sistemini",
        "yeniden", "baslat", "ayarlar", "ayarlari", "kupalar", "kupa", "oturum", "acti", "kapatti", "secenekleri",
        "sifirlama", "tut", "kesfet", "ilgili", "takip", "edin", "ilerlemenizi", "cikanlar", "goruntusu", "kaydedildi",
        "bildirimler", "arkadaslar", "medya", "galerisi", "indiriliyor", "guncelleme", "kitaplik", "denetleyici",
    ].map { fold($0).joined() }

    static func looksTurkish(_ text: String) -> Bool {
        if text.contains(where: { turkishOnly.contains($0) }) { return true }
        if text.filter({ turkishShared.contains($0) }).count >= 2 { return true }
        return fold(text).filter { word in turkishWords.contains { isClose(word, $0) } }.count >= 2
    }

    static let systemPhrases: [String] = [
        "signed in", "signed out", "earned a trophy", "trophy earned", "trophy unlocked", "screenshot saved",
        "video clip saved", "media gallery", "is now online", "joined the party", "left the party", "remote play",
        "controller battery", "controller is disconnected", "controller disconnected", "press the ps button",
        "playstation network", "download complete", "installation complete", "update available", "turn off your console",
        "cloud save", "do not quit", "photo mode", "story mode", "press any button",
        "oturum acti", "oturum kapatti", "ekran ve video", "oyunu kapat", "ana ekranda tut", "yeniden baslat",
        "sifirlama secenekleri", "lutfen bekleyin", "ilerlemenizi takip",
    ].map { fold($0).joined(separator: " ") }

    static func looksLikeSystemText(_ text: String) -> Bool {
        let padded = " " + fold(text).joined(separator: " ") + " "
        return systemPhrases.contains { padded.contains(" \($0) ") }
    }

    /// Küçük harf, aksansız, yazı tanımanın karıştırdığı harfler birleşik (q→c, l ve 1→i); yalnız eşleştirme içindir.
    static func fold(_ text: String) -> [String] {
        let plain = text.lowercased().replacingOccurrences(of: "ı", with: "i").decomposedStringWithCanonicalMapping
        var words: [String] = []
        var word = String.UnicodeScalarView()
        for scalar in plain.unicodeScalars {
            if scalar.properties.generalCategory == .nonspacingMark { continue }
            let mapped: Unicode.Scalar = scalar == "q" ? "c" : (scalar == "l" || scalar == "1") ? "i" : scalar
            if Scalars.isLetter(mapped) || Scalars.isDigit(mapped) {
                word.append(mapped)
            } else if !word.isEmpty {
                words.append(String(word))
                word = String.UnicodeScalarView()
            }
        }
        if !word.isEmpty { words.append(String(word)) }
        return words
    }

    /// Aynı sözcük mü: altı ve daha uzun harflide bir harf farkına izin verilir.
    static func isClose(_ first: String, _ second: String) -> Bool {
        if first == second { return true }
        let a = Array(first.unicodeScalars), b = Array(second.unicodeScalars)
        if a.count < 6 || b.count < 6 || abs(a.count - b.count) > 1 { return false }
        var i = 0, j = 0, edits = 0
        while i < a.count && j < b.count {
            if a[i] == b[j] {
                i += 1
                j += 1
                continue
            }
            edits += 1
            if edits > 1 { return false }
            if a.count > b.count { i += 1 } else if b.count > a.count { j += 1 } else {
                i += 1
                j += 1
            }
        }
        return edits + (a.count - i) + (b.count - j) <= 1
    }
}

/// Ortak havuz (varsayılan açık, ayarlardan kapatılabilir): hiçbir pakette bulunmayan İngilizce oyun satırları biriktirilir
/// ve beş dakikada bir (ve çeviri durunca) GameTranslator'ın havuzuna gönderilir; yeni paketler bunlardan hazırlanır.
/// Yalnız satırın metni, anahtarı, ilk görüldüğü an ve biliniyorsa oyunun kimliğiyle adı gider; kimlik olarak kuruluma
/// özgü rastgele bir UUID kullanılır (IP, kullanıcı adı, PSN bilgisi yok). Sunucuya ulaşılamazsa kuyruk diskte bekler.
/// Windows'taki LinePool.cs ve Android'deki LinePool.kt ile aynı kurallar.
public final class LinePool: @unchecked Sendable {
    /// Sunucu yanıtı: durum kodu ve (429'da) Retry-After başlığı.
    public struct Response: Equatable, Sendable {
        public var status: Int
        public var retryAfter: String?
        public init(status: Int, retryAfter: String? = nil) {
            self.status = status
            self.retryAfter = retryAfter
        }
    }

    public struct Line: Equatable, Sendable {
        public let text: String
        public let key: String
        public let firstSeen: String
        public let titleId: String?
        public let game: String?
    }

    /// (adres, JSON gövde) → yanıt. Bloklar; ulaşılamazsa hata atar.
    public typealias Sender = (String, String) throws -> Response

    public static let ownEndpoint = "https://gametranslator-pool.gametranslator-pool.workers.dev/v1/lines"
    public static let platform = "macos"
    public static let batchSize = 200
    public static let maxQueue = 5000
    public static let maxLength = 300
    public static let minWords = 2
    public static let flushIntervalMs: Int64 = 5 * 60_000
    static let rememberedSent = 20000
    static let saveIntervalMs: Int64 = 30_000

    /// Havuza uygun satır: en az iki sözcük, en çok 300 karakter, çöp değil, Türkçe / sistem yazısı / yarım değil.
    public static func accepts(_ raw: String) -> Bool {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty || text.utf16.count > maxLength { return false }
        let words = text.split(whereSeparator: \.isWhitespace).filter { $0.contains(where: \.isLetter) }.count
        return words >= minWords && !looksLikeNoise(text) && PoolFilter.accepts(text)
    }

    /// Yazı tanıma çöpü içeren ya da harften çok rakam ve simge taşıyan satır.
    public static func looksLikeNoise(_ text: String) -> Bool {
        if PackKey.canon(OcrNoise.clean(text)) != PackKey.canon(text) { return true }
        let visible = text.filter { !$0.isWhitespace }
        return visible.filter(\.isLetter).count * 2 < visible.count
    }

    public static func timestamp(_ millis: Int64) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    /// Retry-After: saniye; yoksa ya da okunamazsa bir dakika, en çok bir gün.
    public static func retryDelayMs(_ header: String?) -> Int64 {
        let seconds = header.flatMap { Int64($0.trimmingCharacters(in: .whitespaces)) } ?? 60
        return min(86400, max(1, seconds)) * 1000
    }

    private let file: URL
    private let app: String
    private let endpoint: String
    private let post: Sender
    private let clock: () -> Int64
    private let lock = NSLock()
    private let sending = NSLock()
    private var install = UUID().uuidString.lowercased()
    private var queue: [Line] = []
    private var sent: [String] = []
    private var queuedKeys: Set<String> = []
    private var sentKeys: Set<String> = []
    private var retryAfterMs: Int64?
    private var lastSave: Int64 = 0
    private var dirty = false
    private var enabledNow: Bool
    private var titleId: String?
    private var game: String?

    /// `file`: kuyruğun ve kurulum kimliğinin JSON dosyası. `app`: "GameTranslator 1.8.48" gibi. `post` verilmezse
    /// URLSession ile gönderilir. `clock`: Unix zamanı, milisaniye.
    public init(file: URL, app: String, endpoint: String = LinePool.ownEndpoint, enabled: Bool = true, post: Sender? = nil,
                clock: (() -> Int64)? = nil) {
        self.file = file
        self.app = app
        self.endpoint = endpoint
        self.post = post ?? LinePool.httpPost
        self.clock = clock ?? { Int64(Date().timeIntervalSince1970 * 1000) }
        enabledNow = enabled
        load()
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    public var installId: String { locked { install } }
    public var queued: [Line] { locked { queue } }
    public var isEnabled: Bool { locked { enabledNow } }

    /// Kapatılınca bekleyen satırlar da silinir: kullanıcı vazgeçtiyse hiçbir şey gitmez.
    public func setEnabled(_ value: Bool) {
        locked {
            enabledNow = value
            if !value && !queue.isEmpty {
                queue.removeAll()
                queuedKeys.removeAll()
                dirty = true
                saveLocked()
            }
        }
    }

    /// O an oynanan oyun (PS5 kimliği ve adı; bilinmiyorsa nil). Bundan sonra kuyruğa giren satırlar bu oyunla gider.
    public func setGame(titleId: String?, name: String?) {
        locked {
            self.titleId = titleId.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
            game = name.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        }
    }

    /// Okunan (pakette bulunmayan) satırı kuyruğa ekler; kapalıysa, filtreye takılırsa ya da zaten varsa bir şey yapmaz.
    public func offer(_ raw: String) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.accepts(text), let key = PackKey.key(text) else { return }
        locked {
            guard enabledNow, !queuedKeys.contains(key), !sentKeys.contains(key) else { return }
            queue.append(Line(text: text, key: key, firstSeen: Self.timestamp(clock()), titleId: titleId, game: game))
            queuedKeys.insert(key)
            if queue.count > Self.maxQueue {
                for line in queue.prefix(queue.count - Self.maxQueue) { queuedKeys.remove(line.key) }
                queue.removeFirst(queue.count - Self.maxQueue)
            }
            dirty = true
            if clock() - lastSave >= Self.saveIntervalMs { saveLocked() }
        }
    }

    /// Kuyruğu 200'lük parçalar hâlinde gönderir (bloklar; arka planda çağrılır). 429'da bekler; ulaşılamazsa kuyruk kalır.
    public func flush() {
        sending.lock()
        defer { sending.unlock() }
        while sendNext() {}
        save()
    }

    /// Bir parçayı gönderir; devam edilecekse true.
    private func sendNext() -> Bool {
        let prepared = locked { () -> ([Line], String)? in
            guard enabledNow, !queue.isEmpty else { return nil }
            if let retry = retryAfterMs, clock() < retry { return nil }
            let batch = Array(queue.prefix(Self.batchSize))
            return (batch, payload(batch))
        }
        guard let (batch, body) = prepared, let response = try? post(endpoint, body) else { return false }
        return locked {
            switch response.status {
            case 200...299:
                retryAfterMs = nil
                remove(batch, sent: true)
                return true
            case 429:
                retryAfterMs = clock() + Self.retryDelayMs(response.retryAfter)
                dirty = true
                return false
            case 400...499:
                // Sunucu bu parçayı kabul etmiyor; yeniden göndermek düzeltmez.
                remove(batch, sent: false)
                return true
            default:
                return false
            }
        }
    }

    public func save() { locked { saveLocked() } }

    private func payload(_ batch: [Line]) -> String {
        let lines: [Any] = batch.map { line -> [String: Any] in
            var item: [String: Any] = ["text": line.text, "key": line.key, "firstSeen": line.firstSeen]
            if let titleId = line.titleId { item["titleId"] = titleId }
            if let game = line.game { item["game"] = game }
            return item
        }
        let json: [String: Any] = ["install": install, "app": app, "platform": Self.platform, "lines": lines]
        return (try? JSONSerialization.data(withJSONObject: json)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
    }

    private func remove(_ batch: [Line], sent wasSent: Bool) {
        let keys = Set(batch.map(\.key))
        queue.removeAll { keys.contains($0.key) }
        queuedKeys.subtract(keys)
        if wasSent {
            sent += batch.map(\.key)
            sentKeys.formUnion(keys)
            if sent.count > Self.rememberedSent {
                let dropped = sent.count - Self.rememberedSent
                for key in sent.prefix(dropped) { sentKeys.remove(key) }
                sent.removeFirst(dropped)
            }
        }
        dirty = true
    }

    private func saveLocked() {
        guard dirty else { return }
        let json: [String: Any] = [
            "install": install,
            "queue": queue.map { line -> [String: Any] in
                ["text": line.text, "key": line.key, "firstSeen": line.firstSeen,
                 "titleId": line.titleId ?? NSNull(), "game": line.game ?? NSNull()]
            },
            "sent": sent,
            "retryAfter": retryAfterMs.map { NSNumber(value: $0) } ?? NSNull(),
        ]
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            let data = try JSONSerialization.data(withJSONObject: json)
            try data.write(to: file, options: .atomic)
        } catch {}
        dirty = false
        lastSave = clock()
    }

    private func load() {
        guard let data = try? Data(contentsOf: file),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        if let id = json["install"] as? String, id.count == 36 { install = id.lowercased() }
        for item in json["queue"] as? [[String: Any]] ?? [] {
            guard let key = item["key"] as? String, !key.isEmpty else { continue }
            func optional(_ name: String) -> String? { (item[name] as? String).flatMap { $0.isEmpty ? nil : $0 } }
            queue.append(Line(text: item["text"] as? String ?? "", key: key, firstSeen: item["firstSeen"] as? String ?? "",
                              titleId: optional("titleId"), game: optional("game")))
            queuedKeys.insert(key)
        }
        for key in json["sent"] as? [String] ?? [] where !key.isEmpty {
            sent.append(key)
            sentKeys.insert(key)
        }
        if let retry = (json["retryAfter"] as? NSNumber)?.int64Value, retry > 0 { retryAfterMs = retry }
    }

    /// Varsayılan gönderici: URLSession ile POST (en çok 20 sn bekler).
    private static func httpPost(_ url: String, _ body: String) throws -> Response {
        guard let address = URL(string: url) else { throw URLError(.badURL) }
        var request = URLRequest(url: address, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(body.utf8)
        let done = DispatchSemaphore(value: 0)
        let box = ResultBox()
        URLSession.shared.dataTask(with: request) { _, response, error in
            if let http = response as? HTTPURLResponse {
                box.value = Response(status: http.statusCode, retryAfter: http.value(forHTTPHeaderField: "Retry-After"))
            } else {
                box.error = error ?? URLError(.badServerResponse)
            }
            done.signal()
        }.resume()
        done.wait()
        if let value = box.value { return value }
        throw box.error ?? URLError(.unknown)
    }

    private final class ResultBox: @unchecked Sendable {
        var value: Response?
        var error: Error?
    }
}
