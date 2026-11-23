// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Altyazı bölgesine düşen arayüz yazılarını davranışından tanır (Windows: RepeatedText.cs, Android: RepeatedText.kt;
/// aynı kurallar): konuşma satırı bir kez söylenir, arayüz yazısı (envanter çubuğu "Pick up Select Actions Sort Close",
/// savaş günlüğü, gösterge) kısa sürede aynen yinelenir ve cümle gibi bitmez. Aynı satır `times` kez çevrildiyse bir süre
/// okumalardan çıkarılır. Cümle sonu işaretiyle biten ya da "Ad:" ile başlayan satırlar yinelense de konuşmadır
/// ("Behind you!"). Birden çok iş parçacığından çağrılabilir.
public final class RepeatedText: @unchecked Sendable {
    /// Bu kadar yinelenen satır arayüzdür.
    public static let times = 3
    /// Yinelemeler bu süre içinde olmalı (ms).
    public static let windowMs: Int64 = 5 * 60 * 1000
    /// Arayüz sayılan satır bu kadar süre okunmaz (ms).
    public static let blockMs: Int64 = 10 * 60 * 1000

    private let clock: () -> Int64
    private let lock = NSLock()
    private var shown: [String: [Int64]] = [:]
    private var blockedUntil: [String: Int64] = [:]

    /// `clock`: tekdüze artan saat, milisaniye.
    public init(clock: @escaping () -> Int64 = monotonicMilliseconds) {
        self.clock = clock
    }

    /// Konuşma gibi mi: cümle sonu işaretiyle biter ya da "Ad: söz" biçimindedir.
    private static func looksSpoken(_ line: String) -> Bool {
        // Tümü büyük harfli satır (reklam panosu "GET YOURS: TODAY!", jenerik) ünlemle bitse de konuşma sayılmaz.
        let letters = line.filter(\.isLetter)
        if letters.count >= 4 && letters.allSatisfy(\.isUppercase) { return false }
        if SubtitleStabilizer.endsSentence(line) { return true }
        let colon = line.characterOffset(of: ": ")
        return (2...30).contains(colon) && line.first!.isUppercase
    }

    /// Çevrilip gösterilen kaynak metni kaydeder; yeni engellenen satır varsa onu döner (günlük için).
    @discardableResult
    public func note(_ source: String) -> String? {
        var blocked: String?
        let at = clock()
        lock.lock()
        defer { lock.unlock() }
        for raw in source.splitKeepingEmpty("\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || Self.looksSpoken(line) { continue }
            let key = TextMatch.normalize(line)
            if key.count < 4 { continue }
            var times = shown[key] ?? []
            times.removeAll { at - $0 > Self.windowMs }
            times.append(at)
            if times.count < Self.times {
                shown[key] = times
                continue
            }
            blockedUntil[key] = at + Self.blockMs
            shown[key] = nil
            if blocked == nil { blocked = line }
        }
        if shown.count > 200 { shown.removeAll() }
        return blocked
    }

    /// Arayüz sayılan satırlar çıkarılmış okuma (satırlar yukarıdan aşağı).
    public func without(_ lines: [String]) -> [String] {
        let at = clock()
        lock.lock()
        defer { lock.unlock() }
        if blockedUntil.isEmpty { return lines }
        blockedUntil = blockedUntil.filter { $0.value > at }
        if blockedUntil.isEmpty || !lines.contains(where: { blockedUntil[TextMatch.normalize($0)] != nil }) { return lines }
        return lines.filter { blockedUntil[TextMatch.normalize($0)] == nil }
    }
}
