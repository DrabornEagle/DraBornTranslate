// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// TV markası (Android: TvBrand). Kanalda küçük harfli adıyla gider ("lg", "samsung", "android").
enum TvBrand: String, CaseIterable, Sendable {
    case lg, samsung, android

    static func of(_ text: String?) -> TvBrand? { text.flatMap { TvBrand(rawValue: $0.lowercased()) } }
}

/// Ağda bulunan TV. `ready`: kurulum portu açık (LG: geliştirici modu, Android: hata ayıklama, Samsung: sdb).
struct FoundTv: Equatable, Sendable {
    let brand: TvBrand
    let host: String
    let name: String
    let ready: Bool

    func toMap() -> [String: Any] { ["brand": brand.rawValue, "host": host, "name": name, "ready": ready] }
}

/// TV uygulamasını kurarken çıkan sorunlar (Android: TvError). Adları kanalda olduğu gibi gider; arayüz her birini
/// açıklama ve çözümle gösterir (lib/tv_install.dart).
enum TvError: String, Sendable {
    /// TV yanıt vermedi: kapalı, başka ağda ya da geliştirici modu/hata ayıklama kapalı.
    case UNREACHABLE
    /// LG: Developer Mode uygulamasında Key Server kapalı.
    case KEY_SERVER_OFF
    /// LG: TV'deki parola yanlış yazıldı.
    case WRONG_PASSPHRASE
    /// LG: TV anahtarı kabul etmedi (geliştirici modu yeniden açılmış, anahtar değişmiş).
    case KEY_REFUSED
    case INSTALL_FAILED
    /// Samsung: TV Tizen 3.0'dan (2017) eski.
    case TOO_OLD
    /// Samsung hesabıyla giriş tamamlanmadı.
    case SIGN_IN_FAILED
    /// Samsung: bu TV için kayıtlı sertifika yok; önce Samsung hesabıyla giriş yapılmalı.
    case SIGN_IN_NEEDED
    /// Samsung TV'ye özel sertifikayı vermedi.
    case CERTIFICATE_FAILED
    /// Android: TV'de hata ayıklama izni verilmedi.
    case NOT_APPROVED
    /// TV kurulumda yer kalmadığını söyledi.
    case NOT_ENOUGH_SPACE
    /// Samsung: Geliştirici modunda girilen "Host PC IP" bu telefonun adresi değil.
    case WRONG_HOST_IP
    /// Kurulum sırasında TV bağlantısı koptu.
    case CONNECTION_LOST
    /// Uygulamanın içinde bu TV'nin paketi yok (derleme hatası).
    case PACKAGE_MISSING
    /// Kullanıcı vazgeçti.
    case CANCELLED
}

/// `detail`: sorunu bulmak için ayrıntı (TV'nin çıktısı); arayüzde "TV'nin söylediği" olarak görünür.
struct TvInstallError: Error, CustomStringConvertible {
    let error: TvError
    let detail: String?

    init(_ error: TvError, _ detail: String? = nil) {
        self.error = error
        self.detail = detail
    }

    var description: String { detail.map { "\(error.rawValue): \($0)" } ?? error.rawValue }
}

/// Soket ve akış hataları (Kotlin'deki IOException'ın karşılığı): TV hatası değildir, çağıran anlamını belirler.
enum TvIOError: Error, CustomStringConvertible {
    case timeout
    case closed(String)
    case failed(String)

    var description: String {
        switch self {
        case .timeout: return "zaman aşımı"
        case .closed(let text), .failed(let text): return text
        }
    }
}

/// Hatanın ayrıntı yazısı (Kotlin'deki error.message).
func tvMessage(_ error: Error) -> String {
    if let known = error as? TvInstallError { return known.description }
    if let io = error as? TvIOError { return io.description }
    return String(describing: error)
}

/// Kurulumu yarıda kesmek için (Android: Cancel). Ağ işleri bloklayan soketlerle yapılır; vazgeçildiğinde açık soket ve
/// oturumlar kapatılır, bekleyen okuma hemen hata verir ve iş `CANCELLED` ile biter.
final class Cancel: @unchecked Sendable {
    private let lock = NSLock()
    private var flag = false
    private var open: [Int: () -> Void] = [:]
    private var nextId = 0

    /// Hiç vazgeçilmeyen (Kotlin'deki Cancel.NONE).
    static let none = Cancel()

    var cancelled: Bool { lock.withLock { flag } }

    func cancel() {
        let toClose: [() -> Void] = lock.withLock {
            flag = true
            let items = Array(open.values)
            open.removeAll()
            return items
        }
        for close in toClose { close() }
    }

    /// Vazgeçilince çağrılacak kapatıcıyı kaydeder; zaten vazgeçildiyse hemen kapatır ve `CANCELLED` verir.
    @discardableResult
    func track(_ close: @escaping () -> Void) throws -> Int {
        let (id, already): (Int, Bool) = lock.withLock {
            nextId += 1
            if !flag { open[nextId] = close }
            return (nextId, flag)
        }
        if already {
            close()
            throw TvInstallError(.CANCELLED)
        }
        return id
    }

    func untrack(_ id: Int) {
        _ = lock.withLock { open.removeValue(forKey: id) }
    }

    func check() throws {
        if cancelled { throw TvInstallError(.CANCELLED) }
    }

    /// Vazgeçilince erken uyanan bekleme.
    func sleep(_ millis: Int) throws {
        let end = Date().addingTimeInterval(Double(millis) / 1000)
        while true {
            try check()
            let left = end.timeIntervalSinceNow
            if left <= 0 { return }
            Thread.sleep(forTimeInterval: min(left, 0.1))
        }
    }

    /// Vazgeçildiyse oluşan hata `CANCELLED` olur (kapatılan soketin verdiği hata yerine).
    func guarded<T>(_ block: () throws -> T) throws -> T {
        do {
            return try block()
        } catch {
            if cancelled { throw TvInstallError(.CANCELLED) }
            throw error
        }
    }

    /// `guarded`'ın eşzamansız biçimi.
    func guardedAsync<T>(_ block: () async throws -> T) async throws -> T {
        do {
            return try await block()
        } catch {
            if cancelled { throw TvInstallError(.CANCELLED) }
            throw error
        }
    }
}

/// Bloklayan işi Swift'in iş parçacığı havuzunu tutmadan arka planda çalıştırır.
func tvBackground<T>(_ body: @escaping () throws -> T) async throws -> T {
    try await withCheckedThrowingContinuation { continuation in
        DispatchQueue.global(qos: .userInitiated).async {
            do { continuation.resume(returning: try body()) } catch { continuation.resume(throwing: error) }
        }
    }
}

/// Sürüm karşılaştırması ve paket adlarından sürüm (Android: Versions).
enum Versions {
    /// Sayısal karşılaştırma: 0.10.0, 0.9.9'dan yenidir.
    static func isNewer(_ a: String, _ b: String) -> Bool {
        let x = parts(a), y = parts(b)
        for i in 0..<max(x.count, y.count) {
            let left = i < x.count ? x[i] : 0
            let right = i < y.count ? y[i] : 0
            if left != right { return left > right }
        }
        return false
    }

    private static func parts(_ version: String) -> [Int] {
        version.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: ".", omittingEmptySubsequences: false)
            .flatMap { $0.split(separator: "-", omittingEmptySubsequences: false) }
            .map { Int($0) ?? 0 }
    }

    /// `gametranslator-androidtv_0.7.0.apk` → `0.7.0`, `com.gametranslator.tv_1.0.5_all.ipk` → `1.0.5`; ad bu
    /// biçimde değilse nil.
    static func ofBundledFile(_ fileName: String) -> String? {
        let base = fileName.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? fileName
        let name = base.lastIndex(of: ".").map { String(base[..<$0]) } ?? base
        let parts = name.split(separator: "_", omittingEmptySubsequences: false)
        if fileName.hasSuffix(".ipk") { return parts.count >= 3 ? String(parts[1]) : nil }
        guard let underscore = name.lastIndex(of: "_"), underscore > name.startIndex,
              name.index(after: underscore) < name.endIndex else { return nil }
        return String(name[name.index(after: underscore)...])
    }
}

/// Elle yazılan TV adresi (Android: ManualAddress). Kullanıcılar "192.168.1.5:3000", "http://192.168.1.5", sonda boşluk
/// ya da (Türkçe sayı klavyesinde nokta olmadığı için) "192,168,1,5" yazıyor. Adres olmayan giriş nil döner.
enum ManualAddress {
    static func normalize(_ input: String?) -> String? {
        var text = (input ?? "").trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
        if let scheme = text.range(of: "://") { text = String(text[scheme.upperBound...]) }
        if let end = text.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }) { text = String(text[..<end]) }
        let parts = text.split(separator: ":", omittingEmptySubsequences: false)
        // Tek iki nokta port ayırır; daha fazlası IPv6'dır (TV'ler IPv4 adresiyle eşleşir).
        if parts.count == 2 {
            if parts[1].isEmpty || !parts[1].allSatisfy({ ("0"..."9").contains($0) }) { return nil }
            text = String(parts[0])
        } else if parts.count > 2 {
            return nil
        }
        if text.hasSuffix(".") { text.removeLast() }
        if text.isEmpty { return nil }
        if text.allSatisfy({ ("0"..."9").contains($0) || $0 == "." }) { return ipv4(text) }
        return isHostName(text) ? text.lowercased() : nil
    }

    private static func ipv4(_ text: String) -> String? {
        let octets = text.split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4 else { return nil }
        var values: [Int] = []
        for octet in octets {
            guard (1...3).contains(octet.count), let value = Int(octet), value <= 255 else { return nil }
            values.append(value)
        }
        return values.map(String.init).joined(separator: ".")
    }

    /// Harf, rakam ve tireden oluşan, en az bir harf içeren etiketler (RFC 1123).
    private static func isHostName(_ text: String) -> Bool {
        func letter(_ c: Character) -> Bool { ("a"..."z").contains(c) || ("A"..."Z").contains(c) }
        guard text.count <= 253, text.contains(where: letter) else { return false }
        return text.split(separator: ".", omittingEmptySubsequences: false).allSatisfy { label in
            (1...63).contains(label.count) && label.first != "-" && label.last != "-" &&
                label.allSatisfy { letter($0) || ("0"..."9").contains($0) || $0 == "-" }
        }
    }
}

extension String {
    /// Kotlin'deki `take(n)`.
    func tvPrefix(_ count: Int) -> String { count >= self.count ? self : String(prefix(count)) }

    /// 500 karakterden uzunsa kısaltılır (Kotlin'deki `short`).
    var tvShort: String {
        count <= 500 ? trimmingCharacters(in: .whitespacesAndNewlines)
            : String(prefix(500)).trimmingCharacters(in: .whitespacesAndNewlines) + "…"
    }
}

/// Kilitli tek değer (`@unchecked Sendable` sınıflarda paylaşılan durum için).
final class TvLocked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) { self.value = value }

    var get: Value { lock.withLock { value } }

    func set(_ newValue: Value) { lock.withLock { value = newValue } }

    @discardableResult
    func update<R>(_ body: (inout Value) -> R) -> R { lock.withLock { body(&value) } }
}
