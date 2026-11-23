// SPDX-License-Identifier: AGPL-3.0-only
// Microsoft girişi ve Xbox jeton zinciri (Android: xbox/XboxAuth.kt): Windows köprüsünün kullandığı xal-node `Msal`
// yolunun birebir karşılığı (istemci kimliği, uçlar, başlıklar ve gövdeler aynı; xal-node MIT, unknownskl).

import Foundation

/// Jetonlar bellekte tutulur ve süresi dolmadan (60 sn pay) yenilenir; kalıcı olan yalnız kullanıcı jetonudur, her
/// değiştiğinde `onUserToken` çağrılır (çağıran Anahtar Zinciri'ne yazar). İş parçacığı güvenlidir; çağrılar bloklar.
final class XboxAuth: @unchecked Sendable {
    static let clientId = "1f907974-e22b-4810-a9de-d9647380c97e"
    static let scope = "xboxlive.signin openid profile offline_access"
    static let transferScope = "service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"
    static let rpXboxLive = "http://xboxlive.com"
    static let rpGssv = "http://gssv.xboxlive.com/"
    private static let marginS: Int64 = 60
    private static let login = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private static let form = "application/x-www-form-urlencoded"
    private static let browserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    /// XSTS'nin 401 yanıtındaki XErr: hesabın Xbox profili yok (…233), çocuk hesabı (…238), ülke (…235).
    private static let noAccountXerr: Set<Int64> = [2_148_916_233, 2_148_916_235, 2_148_916_236, 2_148_916_237, 2_148_916_238]

    let http: XboxHttp
    private let clock: () -> Int64
    private let sleep: (Int64) -> Void
    private let onUserToken: (XboxUserToken?) -> Void

    /// Kotlin'deki `synchronized` gibi iç içe alınabilir (xsts → xblUserToken → userAccessToken).
    private let lock = NSRecursiveLock()
    private var storedUserToken: XboxUserToken?
    private var xblUser: XboxXstsToken?
    private var xsts: [String: XboxXstsToken] = [:]
    private var home: XboxHomeEndpoint?

    init(http: XboxHttp = URLSessionHttp.shared, clock: @escaping () -> Int64 = XboxAuth.wallClock,
         sleep: @escaping (Int64) -> Void = { Thread.sleep(forTimeInterval: Double($0) / 1000) },
         userToken: XboxUserToken? = nil, onUserToken: @escaping (XboxUserToken?) -> Void = { _ in }) {
        self.http = http
        self.clock = clock
        self.sleep = sleep
        self.storedUserToken = userToken
        self.onUserToken = onUserToken
    }

    static func wallClock() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }

    var userToken: XboxUserToken? { lock.withLock { storedUserToken } }

    var signedIn: Bool { userToken != nil }

    /// Girişi başlatır: kullanıcıya gösterilecek kod ve adres.
    func startDeviceCode() throws -> XboxDeviceCode {
        let response = try http.request("POST", "\(Self.login)/devicecode", ["Content-Type": Self.form],
                                        xboxFormBody([("client_id", Self.clientId), ("scope", Self.scope)]))
        return try XboxDeviceCode.parse(json(response, "Microsoft cihaz kodu"))
    }

    /// Kullanıcı kodu onaylayana kadar bekler (sunucunun aralığıyla). Onaylanınca kullanıcı jetonu kaydedilir.
    /// `cancelled` true dönerse `XboxCancelled` atılır.
    ///
    /// Ağ hatası girişi bitirmez: kullanıcı kodu tarayıcıda yazarken uygulama arka plandadır ve sistem arka plandaki
    /// isteği kesebilir. Kodun süresi dolana kadar sorulmaya devam edilir.
    @discardableResult
    func pollDeviceCode(_ code: XboxDeviceCode, cancelled: () -> Bool = { false }) throws -> XboxUserToken {
        let deadline = clock() + Int64(code.expiresInSeconds) * 1000
        var interval = Int64(code.intervalSeconds) * 1000
        while true {
            if cancelled() { throw XboxCancelled() }
            let response: XboxHttpResponse
            do {
                response = try http.request("POST", "\(Self.login)/token", ["Content-Type": Self.form], xboxFormBody([
                    ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"), ("client_id", Self.clientId),
                    ("device_code", code.deviceCode)]))
            } catch let error as XboxException {
                if error.kind != .network || clock() >= deadline { throw error }
                sleep(interval)
                continue
            }
            if response.ok {
                let token = try XboxUserToken.fromResponse(try XboxJSON.parseObject(response.body), nowMs: clock())
                lock.withLock {
                    reset()
                    storedUserToken = token
                }
                onUserToken(token)
                return token
            }
            switch DeviceCodePoll.classify(response) {
            case .pending: break
            case .slowDown: interval += 5000
            case .expired: throw XboxException(.codeExpired, "Microsoft kodunun süresi doldu")
            case .declined: throw XboxException(.signInDeclined, "Microsoft girişi reddedildi")
            case .failed: throw XboxException(.service, "Microsoft girişi: HTTP \(response.status) \(response.body.prefix(200))")
            }
            if clock() >= deadline { throw XboxException(.codeExpired, "Microsoft kodunun süresi doldu") }
            sleep(interval)
        }
    }

    /// Çıkış: bütün jetonlar silinir.
    func signOut() {
        lock.withLock {
            reset()
            storedUserToken = nil
        }
        onUserToken(nil)
    }

    private func reset() {
        xblUser = nil
        xsts.removeAll()
        home = nil
    }

    /// Microsoft erişim jetonu; 60 sn'den az kaldıysa yenilenir (xal-node `getOrRefreshUserToken`).
    func userAccessToken() throws -> String {
        lock.lock()
        defer { lock.unlock() }
        guard let current = storedUserToken else { throw XboxException(.signInRequired, "Microsoft girişi gerekiyor") }
        if current.secondsValid(clock()) > Self.marginS { return current.accessToken }
        return try refresh(current).accessToken
    }

    private func refresh(_ current: XboxUserToken) throws -> XboxUserToken {
        if current.refreshToken.isEmpty { throw XboxException(.signInRequired, "Microsoft girişi gerekiyor") }
        let response = try http.request("POST", "\(Self.login)/token",
                                        ["Content-Type": Self.form, "Cache-Control": "no-store, must-revalidate, no-cache"],
                                        xboxFormBody([("client_id", Self.clientId), ("grant_type", "refresh_token"),
                                                      ("refresh_token", current.refreshToken), ("scope", Self.scope)]))
        // 400: yenileme jetonu geçersiz (xal-node TokenRefreshError); kullanıcı yeniden giriş yapmalı.
        if response.status == 400 { throw XboxException(.signInRequired, "Microsoft girişinin süresi doldu") }
        let token = try XboxUserToken.fromResponse(try json(response, "Microsoft jeton yenileme"), nowMs: clock(), previous: current)
        storedUserToken = token
        onUserToken(token)
        return token
    }

    /// Xbox Live kullanıcı jetonu (`user.auth.xboxlive.com`, RPS bileti `d=`).
    private func xblUserToken() throws -> XboxXstsToken {
        if let cached = xblUser, cached.secondsValid(clock()) > Self.marginS { return cached }
        let access = try userAccessToken()
        let body = XboxJSONObject([
            "Properties": XboxJSONObject(["AuthMethod": "RPS", "RpsTicket": "d=\(access)", "SiteName": "user.auth.xboxlive.com"]),
            "RelyingParty": "http://auth.xboxlive.com", "TokenType": "JWT",
        ]).text
        let response = try http.request("POST", "https://user.auth.xboxlive.com/user/authenticate", [
            "x-xbl-contract-version": "1", "Cache-Control": "no-cache", "Content-Type": "application/json",
            "Origin": "https://www.xbox.com", "Referer": "https://www.xbox.com/"], body)
        if response.status == 401 { throw XboxException(.signInRequired, "Xbox Live girişi kabul etmedi") }
        let token = try XboxXstsToken.parse(try json(response, "Xbox Live kullanıcı jetonu"))
        xblUser = token
        return token
    }

    /// XSTS jetonu: `http://xboxlive.com` (konsol listesi) ya da `http://gssv.xboxlive.com/` (yayın).
    func xsts(_ relyingParty: String) throws -> XboxXstsToken {
        lock.lock()
        defer { lock.unlock() }
        if let cached = xsts[relyingParty], cached.secondsValid(clock()) > Self.marginS { return cached }
        let user = try xblUserToken()
        let body = XboxJSONObject([
            "Properties": XboxJSONObject(["SandboxId": "RETAIL", "UserTokens": [user.token]]),
            "RelyingParty": relyingParty, "TokenType": "JWT",
        ]).text
        let response = try http.request("POST", "https://xsts.auth.xboxlive.com/xsts/authorize", [
            "x-xbl-contract-version": "1", "Cache-Control": "no-cache", "Content-Type": "application/json",
            "Origin": "https://www.xbox.com", "Referer": "https://www.xbox.com/", "Accept": "*/*", "ms-cv": "0",
            "User-Agent": Self.browserAgent], body)
        if response.status == 401 {
            let xerr = XboxJSON.object(response.body)?.optLong("XErr") ?? 0
            if Self.noAccountXerr.contains(xerr) {
                throw XboxException(.noXboxAccount, "Bu Microsoft hesabının Xbox profili yok (XErr \(xerr))")
            }
            throw XboxException(.signInRequired, "Xbox yetkisi verilmedi (XErr \(xerr))")
        }
        let token = try XboxXstsToken.parse(try json(response, "XSTS"))
        xsts[relyingParty] = token
        return token
    }

    /// Ev yayını adresi ve jetonu: gssv XSTS → `xhome.gssv-play-prod.xboxlive.com/v2/login/user`.
    func homeEndpoint() throws -> XboxHomeEndpoint {
        lock.lock()
        defer { lock.unlock() }
        if let cached = home, cached.expiresAtMs > clock() + Self.marginS * 1000 { return cached }
        let gssv = try xsts(Self.rpGssv)
        let body = XboxJSONObject(["token": gssv.token, "offeringId": "xhome"]).text
        let response = try http.request("POST", "https://xhome.gssv-play-prod.xboxlive.com/v2/login/user", [
            "Content-Type": "application/json", "Cache-Control": "no-store, must-revalidate, no-cache",
            "x-gssv-client": "XboxComBrowser"], body)
        let endpoint = try XboxHomeEndpoint.parse(try json(response, "Xbox ev yayını girişi"), nowMs: clock())
        home = endpoint
        return endpoint
    }

    /// Konsola aktarım jetonu (`connect` için; xal-node `getMsalToken().lpt`).
    func transferToken() throws -> String {
        _ = try userAccessToken()
        guard let refresh = userToken?.refreshToken else { throw XboxException(.signInRequired, "Microsoft girişi gerekiyor") }
        let response = try http.request("POST", "https://login.live.com/oauth20_token.srf", ["Content-Type": Self.form],
                                        xboxFormBody([("client_id", Self.clientId), ("scope", Self.transferScope),
                                                      ("grant_type", "refresh_token"), ("refresh_token", refresh)]))
        if response.status == 400 { throw XboxException(.signInRequired, "Microsoft girişinin süresi doldu") }
        let token = try json(response, "Konsol aktarım jetonu").optString("access_token")
        if token.isEmpty { throw XboxException(.service, "Konsol aktarım jetonu gelmedi") }
        return token
    }

    private func json(_ response: XboxHttpResponse, _ what: String) throws -> [String: Any] {
        if !response.ok { throw XboxException(.service, "\(what): HTTP \(response.status) \(response.body.prefix(300))") }
        return try XboxJSON.parseObject(response.body)
    }
}

/// Cihaz kodu beklerken gelen hata yanıtının anlamı (OAuth 2.0 cihaz akışı, RFC 8628).
enum DeviceCodePoll {
    case pending, slowDown, expired, declined, failed

    static func classify(_ response: XboxHttpResponse) -> DeviceCodePoll {
        switch XboxJSON.object(response.body)?.optString("error") ?? "" {
        case "authorization_pending": return .pending
        case "slow_down": return .slowDown
        case "expired_token", "code_expired": return .expired
        case "authorization_declined", "access_denied": return .declined
        default: return response.status >= 500 ? .pending : .failed
        }
    }
}
