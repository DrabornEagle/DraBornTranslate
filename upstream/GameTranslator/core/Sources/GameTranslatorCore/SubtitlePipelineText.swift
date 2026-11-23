// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Android'deki SubtitlePipeline'ın (Windows: SubtitlePipeline.cs) okuma tarafı yardımcıları: satır temizliği, kararsız
/// okumaların birleştirilmesi ve gösterim süresi. iOS'taki hat (Engine) bunları Android'dekiyle aynı sırada çağırır:
/// her satır `LineTidier.tidy`, sonra `RepeatedText.without`, kararlayıcı satırı beklerken `ReadingMerger.note`,
/// satır kararlı olunca `ReadingMerger.settle`; gösterilen satırın öncekinin düzeltmesi olup olmadığı
/// `TextMatch.isRevision` ile bulunur.
public enum SubtitlePipelineText {
    /// Satır ekranda durdukça çeviri de durur; kalkınca TV'den silinir.
    public static let onScreenMs = 15000
    /// Bir satırın en çok bu kadar okuması birleştirilir (`ReadingConsensus`).
    public static let maxReadings = 8

    /// Çevirisi geldiğinde ekrandan kalkmış satırın TV'de kalacağı süre (ms).
    public static func readingTime(_ text: String) -> Int { min(8000, max(2000, 1200 + 60 * text.count)) }
}

/// Okunan bir satırın temizlenmiş hâli: gürültü, karışan harfler, konuşmacı adındaki bozukluklar (Android:
/// SubtitlePipeline.tidy). Okuma çoğu karede aynı geldiği için aynı satır yeniden onarılmaz (son 128 satır). Birden çok
/// iş parçacığından çağrılabilir.
public final class LineTidier: @unchecked Sendable {
    private let lock = NSLock()
    private var repaired: [String: String] = [:]
    private var order: [String] = []
    private var check: ((String) -> Bool)?
    private var english = false

    public init() {}

    /// Parça gerçek bir İngilizce sözcük mü (sözcük listesi; yüklenene kadar nil). Varsa karışan harfler onarılır.
    public var wordCheck: ((String) -> Bool)? {
        get { lock.lock(); defer { lock.unlock() }; return check }
        set { lock.lock(); check = newValue; repaired.removeAll(); order.removeAll(); lock.unlock() }
    }

    /// Oyun dili İngilizce: Türkçe harfli bozuk okumalar da İngilizceye onarılır.
    public var englishSource: Bool {
        get { lock.lock(); defer { lock.unlock() }; return english }
        set { lock.lock(); english = newValue; repaired.removeAll(); order.removeAll(); lock.unlock() }
    }

    public func tidy(_ line: String) -> String {
        let clean = OcrNoise.clean(line)
        if clean.isEmpty { return clean }
        lock.lock()
        let check = self.check, english = self.english, known = repaired[clean]
        lock.unlock()
        var fixed = clean
        if let check {
            if let known {
                fixed = known
            } else {
                fixed = OcrConfusions.repair(clean, isWord: check, fromTurkishLetters: english)
                lock.lock()
                if repaired[clean] == nil {
                    repaired[clean] = fixed
                    order.append(clean)
                    if order.count > 128 { repaired[order.removeFirst()] = nil }
                }
                lock.unlock()
            }
        }
        // Art arda iki addan öndeki ("HSS: GAETANO: …") sahneden okunan çöptür; büyük harfli addaki küçük harfler düzelir.
        return SpeakerNames.fixLabelCaps(SpeakerNames.withoutLeadingJunkLabel(fixed))
    }
}

/// Kararlayıcı satırı beklerken gelen okumalar; satır kararlı olunca sözcük sözcük birleştirilir (Android:
/// SubtitlePipeline.readings ve settle). Yalnız aynı satırın okumaları (satır ve sözcük sayısı aynı olanlar) birleşir.
/// Birden çok iş parçacığından çağrılabilir.
public final class ReadingMerger: @unchecked Sendable {
    private let lock = NSLock()
    private var readings: [String] = []

    public init() {}

    /// Kararlayıcı henüz bir şey istemedi: okuma saklanır (en çok `SubtitlePipelineText.maxReadings`, en eskisi düşer).
    public func note(_ text: String) {
        lock.lock()
        defer { lock.unlock() }
        if text.trimmingCharacters(in: .whitespacesAndNewlines).count >= 3 && !readings.contains(text) {
            readings.append(text)
            if readings.count > SubtitlePipelineText.maxReadings { readings.removeFirst() }
        }
    }

    /// Satır kalktı ya da yeniden gösterildi: saklanan okumalar unutulur.
    public func clear() {
        lock.lock()
        readings.removeAll()
        lock.unlock()
    }

    /// Çevrilecek satır: kararlı olana kadar gelen okumalarla birleştirilir; saklananlar unutulur.
    public func settle(_ source: String, wordCheck: ((String) -> Bool)?) -> String {
        lock.lock()
        let normal = TextMatch.normalize(source)
        let related = readings.filter { TextMatch.isSimilar(TextMatch.normalize($0), normal, threshold: 0.7) }
        readings.removeAll()
        lock.unlock()
        guard let wordCheck, !related.isEmpty else { return source }
        return ReadingConsensus.merge(source, readings: [source] + related, isWord: wordCheck)
    }
}
