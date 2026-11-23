// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import GameTranslatorCore

/// Kaç kişinin GameTranslator ile oynadığı (Windows: PoolActivity.cs): ortak havuzun `/v1/active` ucu son 15 dakikada
/// (`now`) ve son 24 saatte (`today`) satır gönderen kurulumları sayar. İstekle hiçbir veri gitmez.
public struct PoolActivity: Equatable, Sendable {
    public static let refreshInterval: TimeInterval = 10 * 60
    /// Bundan az kişi gösterilmez.
    public static let minShown = 50

    public let now: Int
    public let today: Int

    public init(now: Int, today: Int) {
        self.now = now
        self.today = max(now, today)
    }

    /// Havuzun satır adresinden (…/v1/lines) sayım adresi (…/v1/active).
    public static func address(lines: String = LinePool.ownEndpoint) -> URL? {
        URL(string: lines).map { $0.deletingLastPathComponent().appendingPathComponent("active") }
    }

    public static func parse(_ data: Data) -> PoolActivity? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let now = root["now"] as? Int, let today = root["today"] as? Int, now >= 0, today >= 0 else { return nil }
        return PoolActivity(now: now, today: today)
    }

    /// Sunucuya ulaşılamazsa ya da yanıt bozuksa nil.
    public static func fetch() async -> PoolActivity? {
        guard let url = address() else { return nil }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = "GET"
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return parse(data)
    }

    /// Ana ekrandaki metin; gösterilecek kadar kişi yoksa nil (Windows'taki kuralla aynı).
    public func text(turkish: Bool) -> String? {
        guard today >= Self.minShown else { return nil }
        let format = NumberFormatter()
        format.numberStyle = .decimal
        format.locale = Locale(identifier: turkish ? "tr_TR" : "en_US")
        let todayText = format.string(from: today as NSNumber) ?? "\(today)"
        if now >= Self.minShown {
            let nowText = format.string(from: now as NSNumber) ?? "\(now)"
            return turkish ? "Şu an \(nowText) kişi GameTranslator ile oynuyor · son 24 saatte \(todayText)"
                : "\(nowText) people are playing with GameTranslator right now · \(todayText) in the last 24 hours"
        }
        return turkish ? "Son 24 saatte \(todayText) kişi GameTranslator ile oynadı"
            : "\(todayText) people played with GameTranslator in the last 24 hours"
    }
}
