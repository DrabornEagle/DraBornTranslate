// SPDX-License-Identifier: AGPL-3.0-only

import Darwin
import Foundation

/// Ağda TV arama (Android: TvDiscovery). Önce SSDP denenir; çok noktaya yayın geçmezse (modem engeli ya da iOS'ta
/// multicast yetkisi yoksa) /24 ağında port taraması yapılır.
enum TvDiscovery {
    static let lgTarget = "urn:lge-com:service:webos-second-screen:1"
    static let samsungTarget = "urn:samsung.com:device:RemoteControlReceiver:1"

    /// SSDP yanıtından TV'nin adresini çıkarır; aranan servis değilse nil.
    static func hostFromSsdpResponse(_ response: String, searchTarget: String) -> String? {
        var location: String?
        var matches = false
        for raw in response.components(separatedBy: "\n") {
            let line = raw.hasSuffix("\r") ? String(raw.dropLast()) : raw
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces).uppercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            if name == "LOCATION" {
                location = value
            } else if name == "ST" || name == "NT" {
                matches = matches || value == searchTarget
            }
        }
        guard matches, let location, let host = URLComponents(string: location)?.host, !host.isEmpty else { return nil }
        return host
    }

    /// Cihaz tanımındaki <friendlyName> (LG: http://TV:1990/).
    static func friendlyName(_ xml: String) -> String? {
        guard let start = xml.range(of: "<friendlyName>"),
              let end = xml.range(of: "</friendlyName>", range: start.upperBound..<xml.endIndex) else { return nil }
        let name = xml[start.upperBound..<end.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? nil : name
    }

    /// Samsung'un http://TV:8001/api/v2/ yanıtındaki TV adı.
    static func samsungName(_ json: String) -> String? {
        guard let object = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              let device = object["device"] as? [String: Any], let name = device["name"] as? String else { return nil }
        let text = name.replacingOccurrences(of: "&quot;", with: "\"")
        return text.isEmpty ? nil : text
    }

    /// SSDP M-SEARCH; yanıt veren aygıtların adresleri. Hata olursa (çok noktaya yayın engelli) boş liste.
    static func ssdp(_ searchTarget: String, timeoutMs: Int, cancel: Cancel) -> [String] {
        var hosts: [String] = []
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return [] }
        let closed = TvLocked(false)
        let closeOnce = {
            closed.update { done in
                if !done {
                    done = true
                    _ = shutdown(fd, SHUT_RDWR)
                    _ = Darwin.close(fd)
                }
            }
        }
        defer { closeOnce() }
        guard let tracked = try? cancel.track(closeOnce) else { return [] }
        defer { cancel.untrack(tracked) }
        var timeout = timeval(tv_sec: 0, tv_usec: 300_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        let request = Array("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: \(searchTarget)\r\n\r\n".utf8)
        var target = sockaddr_in()
        target.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        target.sin_family = sa_family_t(AF_INET)
        target.sin_port = in_port_t(1900).bigEndian
        inet_pton(AF_INET, "239.255.255.250", &target.sin_addr)
        var sent = false
        for _ in 0..<2 {
            let n = withUnsafePointer(to: &target) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sendto(fd, request, request.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
            }
            if n > 0 { sent = true }
        }
        guard sent else { return [] }
        let end = Date().addingTimeInterval(Double(timeoutMs) / 1000)
        var buffer = [UInt8](repeating: 0, count: 4096)
        while Date() < end && !cancel.cancelled {
            let n = recv(fd, &buffer, buffer.count, 0)
            if n <= 0 {
                if closed.get { break }
                continue
            }
            if let host = hostFromSsdpResponse(String(decoding: buffer[0..<n], as: UTF8.self), searchTarget: searchTarget),
               !hosts.contains(host) {
                hosts.append(host)
            }
        }
        return hosts
    }

    static func findLg(_ cancel: Cancel) throws -> [FoundTv] {
        var hosts = ssdp(lgTarget, timeoutMs: 3000, cancel: cancel)
        // Bazı modemler çok noktaya yayını geçirmez: LG TV'ler 3000 portunda ikinci ekran servisini sunar.
        if hosts.isEmpty { hosts = try TvNetwork.hostsWithOpenPort(3000, timeoutMs: 600, cancel: cancel) }
        return hosts.map { host in
            let name = (try? LanHttp.get(host, 1990, "/", timeoutMs: 2000)).flatMap { friendlyName($0.text) } ?? "LG TV"
            return FoundTv(brand: .lg, host: host, name: name, ready: TvSocket.isPortOpen(host, LgInstaller.sshPort, timeoutMs: 800))
        }
    }

    static func findSamsung(_ cancel: Cancel) throws -> [FoundTv] {
        var hosts = ssdp(samsungTarget, timeoutMs: 3000, cancel: cancel)
        if hosts.isEmpty { hosts = try TvNetwork.hostsWithOpenPort(8001, timeoutMs: 600, cancel: cancel) }
        return hosts.compactMap { host in
            let name = (try? LanHttp.get(host, 8001, "/api/v2/", timeoutMs: 2000)).flatMap { samsungName($0.text) }
            if name == nil && hosts.count > 1 { return nil }
            return FoundTv(brand: .samsung, host: host, name: name ?? "Samsung TV",
                           ready: TvSocket.isPortOpen(host, SamsungInstaller.sdbPort, timeoutMs: 800))
        }
    }

    /// Android TV: hata ayıklaması açık TV'ler 5555'te yanıt verir.
    static func findAndroid(_ cancel: Cancel) throws -> [FoundTv] {
        try TvNetwork.hostsWithOpenPort(AndroidTvInstaller.defaultPort, timeoutMs: 800, cancel: cancel)
            .map { FoundTv(brand: .android, host: $0, name: "Android TV", ready: true) }
    }

    static func find(_ brand: TvBrand, _ cancel: Cancel) throws -> [FoundTv] {
        switch brand {
        case .lg: return try findLg(cancel)
        case .samsung: return try findSamsung(cancel)
        case .android: return try findAndroid(cancel)
        }
    }

    /// Kurulum portu açık mı (adım ekranlarındaki canlı denetim).
    static func isReady(_ brand: TvBrand, _ host: String) -> Bool {
        switch brand {
        case .lg: return TvSocket.isPortOpen(host, LgInstaller.sshPort, timeoutMs: 1500)
        case .samsung: return TvSocket.isPortOpen(host, SamsungInstaller.sdbPort, timeoutMs: 1500)
        case .android:
            let parts = host.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            let port = parts.count > 1 ? Int(parts[1]) ?? 5555 : 5555
            return TvSocket.isPortOpen(String(parts[0]), port, timeoutMs: 1500)
        }
    }

    /// LG Key Server açık mı?
    static func isKeyServerOn(_ host: String) -> Bool { TvSocket.isPortOpen(host, LgInstaller.keyPort, timeoutMs: 1500) }
}
