// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

struct XboxHttpResponse {
    let status: Int
    let body: String

    var ok: Bool { (200...299).contains(status) }
}

/// Xbox ve Microsoft uçlarına HTTPS isteği (Android: XboxHttp.kt; testlerde sahtesi verilir). Çağrı bloklar; ağ
/// hatasında `XboxException(.network)` atar.
protocol XboxHttp: Sendable {
    func request(_ method: String, _ url: String, _ headers: [String: String], _ body: String?) throws -> XboxHttpResponse
}

/// URLSession ile (Android'deki HttpURLConnection'ın karşılığı: önbellek yok, çerez yok, 15/30 sn).
final class URLSessionHttp: XboxHttp, @unchecked Sendable {
    static let shared = URLSessionHttp()

    private let session: URLSession

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 45
        configuration.waitsForConnectivity = false
        session = URLSession(configuration: configuration)
    }

    func request(_ method: String, _ url: String, _ headers: [String: String], _ body: String?) throws -> XboxHttpResponse {
        guard let target = URL(string: url) else { throw XboxException(.network, "Bağlantı açılamadı: geçersiz adres \(url)") }
        var request = URLRequest(url: target, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.httpMethod = method
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        if let body { request.httpBody = Data(body.utf8) }

        final class Box: @unchecked Sendable {
            var data: Data?
            var response: URLResponse?
            var error: Error?
        }
        let box = Box()
        let done = DispatchSemaphore(value: 0)
        let task = session.dataTask(with: request) { data, response, error in
            box.data = data
            box.response = response
            box.error = error
            done.signal()
        }
        task.resume()
        done.wait()
        if let error = box.error {
            throw XboxException(.network, "Xbox hizmetine ulaşılamadı: \(error.localizedDescription)")
        }
        guard let http = box.response as? HTTPURLResponse else {
            throw XboxException(.network, "Xbox hizmetine ulaşılamadı: yanıt yok")
        }
        return XboxHttpResponse(status: http.statusCode, body: String(decoding: box.data ?? Data(), as: UTF8.self))
    }
}

/// `application/x-www-form-urlencoded` gövdesi; Java'nın URLEncoder'ı gibi (boşluk "+", `A-Za-z0-9.-*_` korunur).
func xboxFormBody(_ pairs: [(String, String)]) -> String {
    pairs.map { formEncode($0.0) + "=" + formEncode($0.1) }.joined(separator: "&")
}

private func formEncode(_ text: String) -> String {
    var out = ""
    for byte in Array(text.utf8) {
        switch byte {
        case UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "0")...UInt8(ascii: "9"),
             UInt8(ascii: "."), UInt8(ascii: "-"), UInt8(ascii: "*"), UInt8(ascii: "_"):
            out.unicodeScalars.append(Unicode.Scalar(byte))
        case UInt8(ascii: " "):
            out += "+"
        default:
            out += String(format: "%%%02X", byte)
        }
    }
    return out
}
