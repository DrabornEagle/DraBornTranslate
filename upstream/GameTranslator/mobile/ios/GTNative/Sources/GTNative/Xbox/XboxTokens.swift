// SPDX-License-Identifier: AGPL-3.0-only
// Xbox jeton biçimleri (Android: xbox/XboxTokens.kt). Alan adları ve süre hesabı xal-node'daki gibidir (MIT,
// unknownskl; bkz. THIRD-PARTY-NOTICES.md): Windows köprüsünün ve Android'in sakladığı userToken JSON'u burada da aynı.

import Foundation

/// Xbox hatasının kullanıcıya anlatılabilecek türü. Ham değerler Android'deki adlardır (günlükte aynı görünsün).
public enum XboxError: String, Sendable, CaseIterable {
    /// Microsoft girişi yok ya da yenileme jetonu geçersiz: yeniden giriş gerekir.
    case signInRequired = "SIGN_IN_REQUIRED"
    /// Cihaz kodunun süresi doldu (15 dakika).
    case codeExpired = "CODE_EXPIRED"
    /// Kullanıcı Microsoft sayfasında girişi reddetti.
    case signInDeclined = "SIGN_IN_DECLINED"
    /// Hesabın Xbox profili yok ya da çocuk hesabı izni gerekiyor (XSTS 401).
    case noXboxAccount = "NO_XBOX_ACCOUNT"
    /// Konsol kapalı, uzaktan oynatma kapalı ya da konsol yayını açamadı.
    case consoleUnavailable = "CONSOLE_UNAVAILABLE"
    /// Konsol başka bir yayında.
    case busy = "BUSY"
    /// Yanıt beklenirken süre doldu.
    case timeout = "TIMEOUT"
    /// İnternet ya da Xbox hizmeti erişilemiyor.
    case network = "NETWORK"
    /// Xbox hizmeti beklenmedik yanıt verdi.
    case service = "SERVICE"
}

public struct XboxException: Error, CustomStringConvertible {
    public let kind: XboxError
    public let message: String

    public init(_ kind: XboxError, _ message: String) {
        self.kind = kind
        self.message = message
    }

    public var description: String { "\(kind.rawValue): \(message)" }
}

/// Bekleme kullanıcı tarafından iptal edildi (Android: InterruptedException).
struct XboxCancelled: Error {}

/// Microsoft kullanıcı jetonu: kalıcı saklanan tek jeton (Anahtar Zinciri).
final class XboxUserToken: @unchecked Sendable {
    let json: [String: Any]

    init(json: [String: Any]) {
        self.json = json
    }

    var accessToken: String { json.optString("access_token") }
    var refreshToken: String { json.optString("refresh_token") }
    /// xal-node `expires_on` (ISO 8601); yoksa süresi dolmuş sayılır.
    var expiresOnMs: Int64 { XboxInstant.parse(json.optString("expires_on")) ?? 0 }

    func secondsValid(_ nowMs: Int64) -> Int64 { XboxInstant.floorDiv(expiresOnMs - nowMs, 1000) }

    /// Saklanacak metin (Windows'taki userToken JSON'u).
    func serialize() -> String { XboxJSON.text(of: json) }

    /// Microsoft jeton yanıtından: xal-node `TokenStore.setUserToken` gibi `expires_on = şimdi + expires_in`. Yenileme
    /// yanıtında yenileme jetonu yoksa öncekisi korunur.
    static func fromResponse(_ body: [String: Any], nowMs: Int64, previous: XboxUserToken? = nil) throws -> XboxUserToken {
        let access = body.optString("access_token")
        if access.isEmpty { throw XboxException(.service, "Microsoft jeton yanıtında access_token yok") }
        var copy = body
        if copy.optString("refresh_token").isEmpty, let kept = previous?.refreshToken, !kept.isEmpty {
            copy["refresh_token"] = kept
        }
        copy["expires_on"] = XboxInstant.format(nowMs + body.optLong("expires_in", 0) * 1000)
        return XboxUserToken(json: copy)
    }

    /// Saklanan metinden; bozuksa ya da yenileme jetonu yoksa nil (yeniden giriş).
    static func parse(_ text: String?) -> XboxUserToken? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let object = XboxJSON.object(text) else { return nil }
        let token = XboxUserToken(json: object)
        return token.refreshToken.isEmpty ? nil : token
    }
}

/// Xbox Live kullanıcı jetonu ya da XSTS jetonu (`Token`, `NotAfter`, `DisplayClaims.xui[0].uhs`).
struct XboxXstsToken {
    let token: String
    let userHash: String
    let notAfterMs: Int64

    func secondsValid(_ nowMs: Int64) -> Int64 { XboxInstant.floorDiv(notAfterMs - nowMs, 1000) }

    static func parse(_ body: [String: Any]) throws -> XboxXstsToken {
        let token = body.optString("Token")
        if token.isEmpty { throw XboxException(.service, "Xbox Live yanıtında Token yok") }
        let first = body.optObject("DisplayClaims")?.optArray("xui")?.first as? [String: Any]
        return XboxXstsToken(token: token, userHash: first?.optString("uhs") ?? "",
                             notAfterMs: XboxInstant.parse(body.optString("NotAfter")) ?? 0)
    }
}

/// Ev yayını adresi ve jetonu (gssv `/v2/login/user` yanıtının varsayılan bölgesi).
struct XboxHomeEndpoint {
    static let defaultLifetimeMs: Int64 = 30 * 60_000

    let baseUrl: String
    let token: String
    let expiresAtMs: Int64

    static func parse(_ body: [String: Any], nowMs: Int64) throws -> XboxHomeEndpoint {
        let regions = (body.optObject("offeringSettings")?.optArray("regions") ?? []).compactMap { $0 as? [String: Any] }
        let rawRegions = body.optObject("offeringSettings")?.optArray("regions")
        var chosen = regions.first { $0.optBool("isDefault") }
        if chosen == nil { chosen = rawRegions?.first as? [String: Any] }
        var base = chosen?.optString("baseUri") ?? ""
        while base.hasSuffix("/") { base.removeLast() }
        if base.isEmpty { throw XboxException(.service, "Xbox ev yayını bölgesi gelmedi") }
        let token = body.optString("gsToken")
        if token.isEmpty { throw XboxException(.service, "Xbox ev yayını jetonu gelmedi") }
        let seconds = body.optLong("durationInSeconds", 0)
        return XboxHomeEndpoint(baseUrl: base, token: token, expiresAtMs: nowMs + (seconds > 0 ? seconds * 1000 : defaultLifetimeMs))
    }
}

/// Microsoft cihaz kodu: kullanıcı `verificationUri`'de `userCode`'u yazar.
struct XboxDeviceCode {
    let userCode: String
    let deviceCode: String
    let verificationUri: String
    let expiresInSeconds: Int
    let intervalSeconds: Int

    static func parse(_ body: [String: Any]) throws -> XboxDeviceCode {
        let user = body.optString("user_code")
        let device = body.optString("device_code")
        if user.isEmpty || device.isEmpty { throw XboxException(.service, "Microsoft cihaz kodu gelmedi") }
        return XboxDeviceCode(userCode: user, deviceCode: device,
                              verificationUri: body.optString("verification_uri", "https://www.microsoft.com/link"),
                              expiresInSeconds: body.optInt("expires_in", 900), intervalSeconds: max(body.optInt("interval", 5), 1))
    }
}

/// Ana hesaptaki bir Xbox (smartglass `/lists/devices`).
public struct XboxConsole: Equatable, Sendable {
    public let id: String
    public let name: String
    public let model: String
    public let streamingEnabled: Bool
    public let powerState: String

    public init(id: String, name: String, model: String, streamingEnabled: Bool, powerState: String = "") {
        self.id = id
        self.name = name
        self.model = model
        self.streamingEnabled = streamingEnabled
        self.powerState = powerState
    }

    /// Ekranda gösterilen ad (Android `xboxConsoleName`): ad, yoksa model, yoksa "Xbox".
    public var label: String { !name.isEmpty ? name : (!model.isEmpty ? model : "Xbox") }

    /// Flutter'a giden biçim (köprüdeki `xboxConsoles` listesinin öğesi).
    public var map: [String: Any] {
        ["id": id, "name": name, "model": model, "streamingEnabled": streamingEnabled, "powerState": powerState]
    }

    /// Kayıttaki biçim (Android Store `xboxConsole`).
    public func toJson() -> String {
        XboxJSONObject(["id": id, "name": name, "model": model, "streamingEnabled": streamingEnabled, "powerState": powerState]).text
    }

    public static func fromJson(_ text: String?) -> XboxConsole? {
        guard let object = XboxJSON.object(text), object.hasValue("id") else { return nil }
        return XboxConsole(id: object.optString("id"), name: object.optString("name"), model: object.optString("model"),
                           streamingEnabled: object.optBool("streamingEnabled"), powerState: object.optString("powerState"))
    }

    /// Windows köprüsündeki gibi: yayın için hem `consoleStreamingEnabled` hem `remoteManagementEnabled` açık olmalı.
    static func parseList(_ body: [String: Any]) -> [XboxConsole] {
        guard let result = body.optArray("result") else { return [] }
        return result.compactMap { item in
            guard let c = item as? [String: Any] else { return nil }
            let id = c.optString("id")
            if id.isEmpty { return nil }
            return XboxConsole(id: id, name: c.optString("name"), model: c.optString("consoleType"),
                               streamingEnabled: c.optBool("consoleStreamingEnabled") && c.optBool("remoteManagementEnabled"),
                               powerState: c.optString("powerState"))
        }
    }
}

/// ISO 8601 anları (Android'deki elle yazılmış okuyucunun aynısı: 7 basamaklı kesirler de okunur).
enum XboxInstant {
    private static let pattern = try! NSRegularExpression(
        pattern: #"^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:[.,](\d{1,9}))?([Zz]|[+-]\d{2}:\d{2})$"#)

    static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q
    }

    static func floorMod(_ a: Int64, _ b: Int64) -> Int64 { a - floorDiv(a, b) * b }

    /// "2026-11-16T10:16:00.1234567Z" → epoch ms; okunamıyorsa nil.
    static func parse(_ text: String?) -> Int64? {
        guard let raw = text?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
        let ns = raw as NSString
        guard let match = pattern.firstMatch(in: raw, range: NSRange(location: 0, length: ns.length)) else { return nil }
        func group(_ i: Int) -> String {
            let range = match.range(at: i)
            return range.location == NSNotFound ? "" : ns.substring(with: range)
        }
        var year = Int64(group(1))!
        let month = Int64(group(2))!, day = Int64(group(3))!
        let hour = Int64(group(4))!, minute = Int64(group(5))!, second = Int64(group(6))!
        guard (1...12).contains(month), (1...31).contains(day), hour <= 23, minute <= 59, second <= 59 else { return nil }
        let fraction = group(7)
        let millis: Int64 = fraction.isEmpty ? 0 : Int64(String((fraction + "00").prefix(3)))!
        let zone = group(8)
        var offsetMinutes: Int64 = 0
        if zone.count > 1 {
            let chars = Array(zone)
            let h = Int64(String(chars[1...2]))!, m = Int64(String(chars[4...5]))!
            offsetMinutes = (h * 60 + m) * (chars[0] == "-" ? -1 : 1)
        }
        // Takvim gününden epoch gününe (Gregoryen; H. Hinnant'ın days_from_civil hesabı).
        if month <= 2 { year -= 1 }
        let era = floorDiv(year, 400)
        let yearOfEra = year - era * 400
        let dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        let days = era * 146_097 + dayOfEra - 719_468
        return ((days * 24 + hour) * 60 + minute - offsetMinutes) * 60_000 + second * 1000 + millis
    }

    /// Epoch ms → ISO 8601 (UTC, "Z"); tam saniyede kesir yazılmaz (java.time `Instant.toString` gibi).
    static func format(_ ms: Int64) -> String {
        let days = floorDiv(ms, 86_400_000)
        let inDay = floorMod(ms, 86_400_000)
        // Hinnant civil_from_days.
        let z = days + 719_468
        let era = floorDiv(z, 146_097)
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        var year = yoe + era * 400
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let day = doy - (153 * mp + 2) / 5 + 1
        let month = mp < 10 ? mp + 3 : mp - 9
        if month <= 2 { year += 1 }
        let hour = inDay / 3_600_000, minute = inDay / 60_000 % 60, second = inDay / 1000 % 60, millis = inDay % 1000
        let base = String(format: "%04lld-%02lld-%02lldT%02lld:%02lld:%02lld", year, month, day, hour, minute, second)
        return millis == 0 ? base + "Z" : base + String(format: ".%03lldZ", millis)
    }
}
