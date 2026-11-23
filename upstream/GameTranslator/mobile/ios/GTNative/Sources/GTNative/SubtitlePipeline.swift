// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import GameTranslatorCore

/// Çevirinin gittiği yer (TV sunucusu ve telefondaki ekran).
protocol SubtitleSink: AnyObject, Sendable {
    func show(_ text: String, durationMs: Int, box: TextBox?, replaces: Bool)
    func clear()
}

/// Okunan altyazıyı çevirip gönderen hat: gürültü temizliği → kararlılık → önbellek → oyun paketi/çeviri → TV.
/// Android'deki core/SubtitlePipeline.kt'nin karşılığı. `onRead` tek bir kuyruktan çağrılır; çeviriler ayrı görevlerde
/// yapılır, sonuçlar kilit altında işlenir. Satır onarımı (LineTidier: OcrConfusions), okumaların birleştirilmesi
/// (ReadingMerger: ReadingConsensus), yinelenen arayüz yazısı (RepeatedText) ve yerine geçen satır (TextMatch.isRevision)
/// çekirdekten gelir.
final class SubtitlePipeline: @unchecked Sendable {
    /// Satır ekranda durdukça çeviri de durur; kalkınca TV'den silinir.
    static let onScreenMs = SubtitlePipelineText.onScreenMs
    private static let cacheSize = 300

    /// Çevirisi geldiğinde ekrandan kalkmış satırın TV'de kalacağı süre (Android ile aynı).
    static func readingTime(_ text: String) -> Int { SubtitlePipelineText.readingTime(text) }

    private let translator: TextTranslating
    private weak var sink: SubtitleSink?
    private let lock = NSLock()
    /// Android'de 5 kare/sn'de 2 boş kare (≈0,4 sn) satırın kalktığı anlamına gelir.
    private var stabilizer = SubtitleStabilizer()
    private var cache: [String: String] = [:]
    private var cacheOrder: [String] = []
    private var latest = 0
    /// Ekranda duran satırın numarası; satır kalktıysa -1.
    private var onScreen = -1
    private var session = 0
    private var lastShownSource: String?
    private let tidier = LineTidier()
    private let merger = ReadingMerger()
    /// Altyazı bölgesine düşen, kısa sürede aynen yinelenen arayüz yazısı (envanter çubuğu) bir süre çevrilmez.
    let repeats = RepeatedText()
    var onRepeatBlocked: ((String) -> Void)?

    /// Parça gerçek bir İngilizce sözcük mü (sözcük listesi); varsa karışan harfler onarılır, okumalar birleştirilir.
    var wordCheck: ((String) -> Bool)? {
        get { tidier.wordCheck }
        set { tidier.wordCheck = newValue }
    }

    /// Oyun dili İngilizce: Türkçe harfli bozuk okumalar da İngilizceye onarılır.
    var englishSource: Bool {
        get { tidier.englishSource }
        set { tidier.englishSource = newValue }
    }

    var onPair: ((String, String) -> Void)?
    var onTranslateFailed: ((String) -> Void)?
    var onReadText: ((String) -> Void)?
    /// Oyun paketi: kararlı satırın hazır çevirisi (yoksa nil); bulunursa motora gidilmez.
    var packLookup: ((String) -> PackMatch?)?
    /// Pakette bulunmayan, motora giden satır (ortak havuz için).
    var onUnpacked: ((String) -> Void)?
    var onPackHit: ((String, PackMatch) -> Void)?

    init(translator: TextTranslating, sink: SubtitleSink) {
        self.translator = translator
        self.sink = sink
    }

    /// Bir karenin okuması: `lines` yukarıdan aşağı satırlar, `box` yazının ekrandaki yeri.
    func onRead(_ lines: [String], box: TextBox?) {
        let tidied = lines.map(tidier.tidy).filter { !$0.isEmpty }
        let text = repeats.without(tidied).joined(separator: "\n")
        onReadText?(text)
        lock.lock()
        defer { lock.unlock() }
        switch stabilizer.feed(text) {
        case .none:
            // Kararlı olana kadar gelen okumalar; satır kararlı olunca sözcük sözcük birleştirilir.
            merger.note(text)
        case .clear:
            merger.clear()
            onScreen = -1
            sink?.clear()
        case .translate(let read):
            let source = merger.settle(read, wordCheck: tidier.wordCheck)
            latest += 1
            let number = latest
            onScreen = number
            if let cached = cache[key(source)] {
                deliver(number, source: source, translated: cached, box: box)
            } else {
                request(number, source: source, box: box)
            }
        }
    }

    private func request(_ number: Int, source: String, box: TextBox?) {
        let current = session
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            let packed = self.packLookup?(source).flatMap { $0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
            if packed == nil { self.onUnpacked?(source.replacingOccurrences(of: "\n", with: " ")) }
            var translated = packed?.text.trimmingCharacters(in: .whitespacesAndNewlines)
            if translated == nil { translated = await self.translator.translate(source)?.trimmingCharacters(in: .whitespacesAndNewlines) }
            self.finish(number, session: current, source: source, packed: packed, translated: translated, box: box)
        }
    }

    private func finish(_ number: Int, session current: Int, source: String, packed: PackMatch?, translated: String?, box: TextBox?) {
        lock.lock()
        defer { lock.unlock() }
        guard current == session else { return }
        if let packed { onPackHit?(source, packed) }
        guard let result = translated, !result.isEmpty else {
            if number == onScreen { stabilizer.forgetSent() }
            onTranslateFailed?(source)
            return
        }
        remember(key(source), result)
        deliver(number, source: source, translated: result, box: box)
    }

    /// Kilit altında çağrılır. Yerine yenisi gelmiş satırın geç kalan çevirisi gösterilmez.
    private func deliver(_ number: Int, source: String, translated: String, box: TextBox?) {
        guard number == latest else { return }
        // Çeviri kaynağıyla neredeyse aynıysa metin zaten hedef dildedir.
        guard stabilizer.shouldSend(source: source, translation: translated) else { return }
        let duration = number == onScreen ? Self.onScreenMs : Self.readingTime(translated)
        let replaces = lastShownSource.map { TextMatch.isRevision(source, $0) } ?? false
        lastShownSource = source
        sink?.show(translated, durationMs: duration, box: box, replaces: replaces)
        if let blocked = repeats.note(source) { onRepeatBlocked?(blocked) }
        onPair?(source, translated)
    }

    private func remember(_ key: String, _ value: String) {
        if cache[key] == nil {
            cacheOrder.append(key)
            if cacheOrder.count > Self.cacheSize { cache[cacheOrder.removeFirst()] = nil }
        }
        cache[key] = value
    }

    private func key(_ source: String) -> String { TextMatch.normalize(source) }

    func reset() {
        lock.lock()
        defer { lock.unlock() }
        latest += 1
        session += 1
        onScreen = -1
        lastShownSource = nil
        merger.clear()
        if stabilizer.reset() == .clear { sink?.clear() }
    }
}

/// Telefonun kendi ekranında ("Bu telefonda göster") gösterilen satırın olayları (Android: core/ScreenLine.kt).
final class ScreenLine: @unchecked Sendable {
    private let lock = NSLock()
    private var text: String?
    private var until: TimeInterval = 0

    func show(_ text: String, durationMs: Int, replaces: Bool, now: TimeInterval) -> [String: Any] {
        lock.withLock {
            self.text = text
            until = now + Double(durationMs) / 1000
        }
        return ["type": "subtitle", "text": text, "duration": durationMs, "replace": replaces]
    }

    func clear() -> [String: Any] {
        lock.withLock { text = nil }
        return ["type": "clear"]
    }

    /// Ekranda duran satır, kalan süresiyle; satır yoksa ya da süresi dolduysa nil.
    func current(now: TimeInterval) -> [String: Any]? {
        lock.withLock {
            guard let text else { return nil }
            let left = Int((until - now) * 1000)
            return left > 0 ? ["type": "subtitle", "text": text, "duration": left, "replace": false] : nil
        }
    }
}
