// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Ağdaki başka GameTranslator altyazı sunucuları (çoğu zaman açık duran bilgisayar uygulaması; Android: OtherServers).
/// TV uygulaması ağı tarayıp ilk bulduğu sunucuya bağlanır; bilgisayardaki uygulama açıksa TV ona bağlanır ve telefonun
/// çevirisi TV'ye hiç gitmez (TV uygulaması 1.0.6'dan itibaren çeviri yapanı seçer). Telefon bunu görüp kullanıcıya
/// söyler. Telefon yalnızca ekran olarak kullanılırken ("Bilgisayarın altyazısını göster") çeviri yapan sunucu da
/// buradan bulunur. iOS'ta yerel ağa düz HTTP için Info.plist'te NSAllowsLocalNetworking ve
/// NSLocalNetworkUsageDescription gerekir.
public enum OtherServers {
    /// Altyazı sunucusunun varsayılan portu (SubtitleServer ile aynı).
    public static let defaultPort = 8787

    /// Ağda bulunan bir sunucu. `active`: `/hello` yanıtına göre çeviri şu an çalışıyor.
    public struct Found: Equatable, Sendable {
        public var host: String
        public var active: Bool

        public init(host: String, active: Bool) {
            self.host = host
            self.active = active
        }
    }

    /// `host`:`port` bir GameTranslator sunucusu mu (`/hello` yanıtına bakılır). Ulaşılamıyorsa false. Bloklar.
    public static func isServer(_ host: String, port: Int = defaultPort, timeoutMs: Int = 700) -> Bool {
        hello(host, port: port, timeoutMs: timeoutMs) != nil
    }

    /// `host`:`port` bir GameTranslator sunucusuysa çevirinin çalışıp çalışmadığı (`"active"`); sunucu değilse ya da
    /// ulaşılamıyorsa nil. Bloklar (en çok `timeoutMs` kadar); ana iş parçacığından çağrılmaz.
    public static func hello(_ host: String, port: Int = defaultPort, timeoutMs: Int = 700) -> Bool? {
        guard let url = URL(string: "http://\(host):\(port)/hello") else { return nil }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: Double(timeoutMs) / 1000)
        request.setValue("close", forHTTPHeaderField: "Connection")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = Double(timeoutMs) / 1000
        configuration.timeoutIntervalForResource = Double(timeoutMs) / 1000 * 2
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }
        let done = DispatchSemaphore(value: 0)
        var response: String?
        session.dataTask(with: request) { data, reply, error in
            defer { done.signal() }
            guard error == nil, let http = reply as? HTTPURLResponse else { return }
            let body = data.flatMap { String(data: $0.prefix(2048), encoding: .utf8) } ?? ""
            response = "HTTP/1.1 \(http.statusCode) \r\n\r\n\(body)"
        }.resume()
        // URLSession'ın kendi süresi aşılırsa da beklemek bırakılır.
        if done.wait(timeout: .now() + .milliseconds(timeoutMs * 2 + 200)) == .timedOut { return nil }
        return response.flatMap(parseHello)
    }

    private static let activeTrue = Patterns.make("\"active\"\\s*:\\s*true")

    /// `/hello` yanıtı (başlıklarıyla) bir GameTranslator sunucusundansa `"active"` değeri; değilse nil.
    public static func parseHello(_ response: String) -> Bool? {
        let body = response.lowercased()
        guard body.hasPrefix("http/1.1 200"), body.contains("gametranslator") || body.contains("couchsubs") else { return nil }
        return Patterns.matches(activeTrue, body)
    }

    /// `own` adresinin /24 ağında, kendisi dışındaki ilk GameTranslator sunucusunun adresi; yoksa nil. Bloklar
    /// (en çok birkaç saniye). `probe` testte değiştirilir.
    public static func find(own: String, probe: @escaping (String) -> Bool = { isServer($0) }) -> String? {
        scan(own: own, firstOnly: true) { host in probe(host) ? Found(host: host, active: false) : nil }.first?.host
    }

    /// Ekran olarak bağlanılacak sunucu: `own` adresinin /24 ağında, kendisi dışındaki sunuculardan çeviri yapan
    /// (`"active":true`); çeviri yapan yoksa bulunanlardan ilki, hiç yoksa nil. Bloklar (en çok birkaç saniye).
    /// `probe`: adres sunucuysa `active` değeri, değilse nil; testte değiştirilir.
    public static func findDisplaySource(own: String, probe: @escaping (String) -> Bool? = { hello($0) }) -> Found? {
        let found = scan(own: own, firstOnly: false) { host in probe(host).map { Found(host: host, active: $0) } }
        return found.first { $0.active } ?? found.first
    }

    /// Taramanın sonuçları (iş parçacıkları arasında paylaşılır).
    private final class Results: @unchecked Sendable {
        let condition = NSCondition()
        var found: [Found?]
        var done: [Bool]
        init(_ count: Int) {
            found = [Found?](repeating: nil, count: count)
            done = [Bool](repeating: false, count: count)
        }
    }

    /// /24 ağındaki (`own` dışındaki) adresleri `probe` ile sorar (aynı anda en çok 32); bulunanlar adres sırasıyla.
    /// `firstOnly`: ilk bulunan yeter, kalanlar beklenmez. Geçersiz adreste boş.
    private static func scan(own: String, firstOnly: Bool, probe: @escaping (String) -> Found?) -> [Found] {
        let parts = own.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4, parts.allSatisfy({ Int($0) != nil }) else { return [] }
        let prefix = parts.prefix(3).joined(separator: ".") + "."
        let hosts = (1...254).map { prefix + String($0) }.filter { $0 != own }
        let results = Results(hosts.count)
        let queue = OperationQueue()
        queue.name = "gt-server-scan"
        queue.maxConcurrentOperationCount = 32
        for (index, host) in hosts.enumerated() {
            queue.addOperation {
                let found = probe(host)
                results.condition.lock()
                results.found[index] = found
                results.done[index] = true
                results.condition.broadcast()
                results.condition.unlock()
            }
        }
        // Her adres en çok 15 sn beklenir (Android ile aynı üst sınır).
        let deadline = Date().addingTimeInterval(15)
        var collected: [Found] = []
        results.condition.lock()
        for index in hosts.indices {
            while !results.done[index] && results.condition.wait(until: deadline) {}
            guard results.done[index] else { continue }
            if let found = results.found[index] {
                collected.append(found)
                if firstOnly { break }
            }
        }
        results.condition.unlock()
        queue.cancelAllOperations()
        return collected
    }
}
