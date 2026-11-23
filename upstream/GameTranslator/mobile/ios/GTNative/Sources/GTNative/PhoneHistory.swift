// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import GameTranslatorCore

/// Çevrilen satırların geçmişi (Android: core/SubtitleHistory.kt; dosya biçimi Windows ve Android ile aynı):
/// `{"version":1,"entries":[{"id","date","source","translation","origin":"pack"|"engine"}]}`, en yenisi başta.
final class PhoneHistory: @unchecked Sendable {
    struct Entry: Equatable {
        let id: Int
        /// Unix zamanı, milisaniye.
        let date: Int64
        let source: String
        let translation: String
        let fromPack: Bool
    }

    static let defaultLimit = 200
    static let maxTextLength = 500

    let limit: Int
    private let lock = NSLock()
    private var items: [Entry] = []
    private var nextId = 0

    init(limit: Int = defaultLimit) {
        self.limit = max(1, limit)
    }

    var entries: [Entry] { lock.withLock { items } }

    /// Yeni satır başa eklenir; baştaki satırın yeniden okunmuş hâliyse onun yerine geçer. Eklenmediyse false.
    @discardableResult
    func add(source: String, translation: String, fromPack: Bool = false, date: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        let text = Self.clip(translation.trimmingCharacters(in: .whitespacesAndNewlines))
        guard !text.isEmpty else { return false }
        let clipped = Self.clip(source)
        return lock.withLock {
            if let first = items.first, TextMatch.isSimilar(TextMatch.normalize(first.source), TextMatch.normalize(clipped)) {
                items[0] = Entry(id: first.id, date: date, source: clipped, translation: text, fromPack: fromPack)
                return true
            }
            items.insert(Entry(id: nextId, date: date, source: clipped, translation: text, fromPack: fromPack), at: 0)
            nextId += 1
            if items.count > limit { items.removeLast(items.count - limit) }
            return true
        }
    }

    func clear() { lock.withLock { items.removeAll() } }

    func json() -> Data {
        let list = entries.map { entry -> [String: Any] in
            ["id": entry.id, "date": Self.format(entry.date), "source": entry.source, "translation": entry.translation,
             "origin": entry.fromPack ? "pack" : "engine"]
        }
        return (try? JSONSerialization.data(withJSONObject: ["version": 1, "entries": list])) ?? Data()
    }

    func save(to file: URL) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try json().write(to: file, options: .atomic)
    }

    static func load(from file: URL, limit: Int = defaultLimit) -> PhoneHistory {
        let history = PhoneHistory(limit: limit)
        guard let data = try? Data(contentsOf: file),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let stored = root["entries"] as? [Any] else { return history }
        var loaded: [Entry] = []
        for case let item as [String: Any] in stored {
            if loaded.count >= history.limit { break }
            guard let source = item["source"] as? String, let translation = item["translation"] as? String,
                  let date = (item["date"] as? String).flatMap(parse) else { continue }
            loaded.append(Entry(id: JSONValue.int(item["id"]) ?? 0, date: date, source: source, translation: translation,
                                fromPack: item["origin"] as? String == "pack"))
        }
        history.lock.withLock {
            history.items = loaded
            history.nextId = (loaded.map(\.id).max() ?? -1) + 1
        }
        return history
    }

    private static func clip(_ text: String) -> String { text.count > maxTextLength ? String(text.prefix(maxTextLength)) : text }

    private static func format(_ millis: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    /// Kesirli saniyeli ya da saat dilimli ISO 8601 da kabul edilir (Windows'un eski dosyaları).
    static func parse(_ text: String) -> Int64? {
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = plain.date(from: text) ?? fractional.date(from: text) else { return nil }
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }
}
