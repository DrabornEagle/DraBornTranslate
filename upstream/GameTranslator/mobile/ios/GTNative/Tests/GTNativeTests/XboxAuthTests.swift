// SPDX-License-Identifier: AGPL-3.0-only
// Android'deki XboxTest.kt (XboxTokensTest, XboxAuthTest, XboxHomeTest) ve XboxPortTest.kt'nin (XboxInstantTest)
// aynı örnekleriyle; ek olarak köprü eşlemelerinin (XboxAccount) biçimi.

import XCTest
@testable import GTNative

/// Sahte HTTP: istekleri kaydeder, yanıtı verilen işlev seçer.
final class FakeHttp: XboxHttp, @unchecked Sendable {
    struct Call {
        let method: String
        let url: String
        let headers: [String: String]
        let body: String?
    }

    private let lock = NSLock()
    private var recorded: [Call] = []
    private let answer: (String, String, String?) throws -> XboxHttpResponse

    init(_ answer: @escaping (_ method: String, _ url: String, _ body: String?) throws -> XboxHttpResponse) {
        self.answer = answer
    }

    var calls: [Call] { lock.withLock { recorded } }

    func request(_ method: String, _ url: String, _ headers: [String: String], _ body: String?) throws -> XboxHttpResponse {
        lock.withLock { recorded.append(Call(method: method, url: url, headers: headers, body: body)) }
        return try answer(method, url, body)
    }
}

func ok(_ json: String) -> XboxHttpResponse { XboxHttpResponse(status: 200, body: json) }

func object(_ text: String?) throws -> [String: Any] { try XCTUnwrap(XboxJSON.object(text)) }

/// UTC takvim anı → epoch ms (java.time `Instant.parse` yerine).
func utc(_ y: Int, _ mo: Int, _ d: Int, _ h: Int = 0, _ mi: Int = 0, _ s: Int = 0, ms: Int = 0) -> Int64 {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "UTC")!
    let date = calendar.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi, second: s))!
    return Int64(date.timeIntervalSince1970) * 1000 + Int64(ms)
}

final class XboxTokensTests: XCTestCase {
    let now = utc(2026, 11, 16, 10)

    func testUserTokenGetsExpiresOnLikeXalNode() throws {
        let token = try XboxUserToken.fromResponse(object(#"{"access_token":"A","refresh_token":"R","expires_in":3600,"token_type":"bearer"}"#), nowMs: now)
        XCTAssertEqual(token.accessToken, "A")
        XCTAssertEqual(token.refreshToken, "R")
        XCTAssertEqual(token.json["expires_on"] as? String, "2026-11-16T11:00:00Z")
        XCTAssertEqual(token.secondsValid(now), 3600)
        // Saklanan metin geri okunur (Windows'taki userToken JSON'u).
        let restored = try XCTUnwrap(XboxUserToken.parse(token.serialize()))
        XCTAssertEqual(restored.expiresOnMs, now + 3_600_000)
        XCTAssertEqual(restored.json["token_type"] as? String, "bearer")
    }

    func testRefreshKeepsPreviousRefreshTokenWhenMissing() throws {
        let first = try XboxUserToken.fromResponse(object(#"{"access_token":"A","refresh_token":"R","expires_in":10}"#), nowMs: now)
        let second = try XboxUserToken.fromResponse(object(#"{"access_token":"B","expires_in":10}"#), nowMs: now, previous: first)
        XCTAssertEqual(second.refreshToken, "R")
        XCTAssertThrowsError(try XboxUserToken.fromResponse(object(#"{"refresh_token":"R"}"#), nowMs: now))
    }

    func testWindowsStoredTokenParses() throws {
        let stored = #"{"token_type":"Bearer","scope":"xboxlive.signin openid profile offline_access","expires_in":3600,"ext_expires_in":3600,"access_token":"EwA","refresh_token":"M.C5","id_token":"x","expires_on":"2026-11-16T10:30:00.123Z"}"#
        let token = try XCTUnwrap(XboxUserToken.parse(stored))
        XCTAssertEqual(token.secondsValid(now), 1800)
        XCTAssertNil(XboxUserToken.parse(#"{"access_token":"A"}"#))
        XCTAssertNil(XboxUserToken.parse("bozuk"))
        XCTAssertNil(XboxUserToken.parse(nil))
    }

    func testExpiredTokenHasNegativeValidity() throws {
        let token = try XboxUserToken.fromResponse(object(#"{"access_token":"A","refresh_token":"R","expires_in":0}"#), nowMs: now)
        XCTAssertEqual(token.secondsValid(now + 1), -1)  // floorDiv
        XCTAssertEqual(XboxUserToken(json: ["refresh_token": "R"]).expiresOnMs, 0)
    }

    func testXstsTokenWithSevenFractionDigits() throws {
        let token = try XboxXstsToken.parse(object(#"{"IssueInstant":"2026-11-16T09:00:00.1234567Z","NotAfter":"2026-11-16T10:16:00.1234567Z","Token":"T","DisplayClaims":{"xui":[{"uhs":"123"}]}}"#))
        XCTAssertEqual(token.token, "T")
        XCTAssertEqual(token.userHash, "123")
        XCTAssertEqual(token.secondsValid(now), 960)
        XCTAssertThrowsError(try XboxXstsToken.parse([:]))
    }

    func testHomeEndpointPicksDefaultRegion() throws {
        let body = try object(#"""
        {"gsToken":"G","durationInSeconds":14400,"offeringSettings":{"regions":[
            {"name":"WestEurope","baseUri":"https://weu.core.gssv-play-prodxhome.xboxlive.com/","isDefault":false},
            {"name":"UKSouth","baseUri":"https://uks.core.gssv-play-prodxhome.xboxlive.com","isDefault":true}]}}
        """#)
        let endpoint = try XboxHomeEndpoint.parse(body, nowMs: now)
        XCTAssertEqual(endpoint.baseUrl, "https://uks.core.gssv-play-prodxhome.xboxlive.com")
        XCTAssertEqual(endpoint.token, "G")
        XCTAssertEqual(endpoint.expiresAtMs, now + 14_400_000)
        let first = try XboxHomeEndpoint.parse(object(#"{"gsToken":"G","offeringSettings":{"regions":[{"baseUri":"https://a/","isDefault":false}]}}"#), nowMs: now)
        XCTAssertEqual(first.baseUrl, "https://a")
        XCTAssertEqual(first.expiresAtMs, now + 30 * 60_000)
        XCTAssertThrowsError(try XboxHomeEndpoint.parse(object(#"{"gsToken":"G","offeringSettings":{"regions":[]}}"#), nowMs: now)) {
            XCTAssertEqual(($0 as? XboxException)?.kind, .service)
        }
        XCTAssertThrowsError(try XboxHomeEndpoint.parse(object(#"{"offeringSettings":{"regions":[{"baseUri":"https://a"}]}}"#), nowMs: now))
    }

    func testDeviceCodeAndPollErrors() throws {
        let code = try XboxDeviceCode.parse(object(#"{"user_code":"ABCD1234","device_code":"DC","verification_uri":"https://www.microsoft.com/link","expires_in":900,"interval":0}"#))
        XCTAssertEqual(code.userCode, "ABCD1234")
        XCTAssertEqual(code.intervalSeconds, 1)
        let defaults = try XboxDeviceCode.parse(object(#"{"user_code":"U","device_code":"D"}"#))
        XCTAssertEqual(defaults.verificationUri, "https://www.microsoft.com/link")
        XCTAssertEqual(defaults.expiresInSeconds, 900)
        XCTAssertEqual(defaults.intervalSeconds, 5)
        XCTAssertThrowsError(try XboxDeviceCode.parse(object(#"{"user_code":"U"}"#)))
        XCTAssertEqual(DeviceCodePoll.classify(XboxHttpResponse(status: 400, body: #"{"error":"authorization_pending"}"#)), .pending)
        XCTAssertEqual(DeviceCodePoll.classify(XboxHttpResponse(status: 400, body: #"{"error":"slow_down"}"#)), .slowDown)
        XCTAssertEqual(DeviceCodePoll.classify(XboxHttpResponse(status: 400, body: #"{"error":"expired_token"}"#)), .expired)
        XCTAssertEqual(DeviceCodePoll.classify(XboxHttpResponse(status: 400, body: #"{"error":"authorization_declined"}"#)), .declined)
        XCTAssertEqual(DeviceCodePoll.classify(XboxHttpResponse(status: 400, body: #"{"error":"invalid_grant"}"#)), .failed)
        XCTAssertEqual(DeviceCodePoll.classify(XboxHttpResponse(status: 503, body: "")), .pending)
    }

    func testConsoleListRequiresStreamingAndRemoteManagement() throws {
        let list = XboxConsole.parseList(try object(#"""
        {"status":{"errorCode":"OK"},"result":[
            {"id":"F4001","name":"Salon","consoleType":"XboxSeriesX","powerState":"ConnectedStandby","consoleStreamingEnabled":true,"remoteManagementEnabled":true},
            {"id":"F4002","name":"Oda","consoleType":"XboxOne","consoleStreamingEnabled":true,"remoteManagementEnabled":false},
            {"name":"kimliksiz"}]}
        """#))
        XCTAssertEqual(list.count, 2)
        XCTAssertTrue(list[0].streamingEnabled)
        XCTAssertEqual(list[0].model, "XboxSeriesX")
        XCTAssertEqual(list[0].powerState, "ConnectedStandby")
        XCTAssertFalse(list[1].streamingEnabled)
        XCTAssertEqual(XboxConsole.fromJson(list[0].toJson()), list[0])
        // Android'in yazdığı biçim de okunur.
        XCTAssertEqual(XboxConsole.fromJson(#"{"id":"F","name":"N","model":"M","streamingEnabled":true,"powerState":"On"}"#),
                       XboxConsole(id: "F", name: "N", model: "M", streamingEnabled: true, powerState: "On"))
        XCTAssertNil(XboxConsole.fromJson(#"{"name":"N"}"#))
        XCTAssertNil(XboxConsole.fromJson("bozuk"))
        XCTAssertEqual(XboxConsole(id: "F", name: "", model: "", streamingEnabled: true).label, "Xbox")
    }

    func testFormBodyEncodesLikeJavaUrlEncoder() {
        XCTAssertEqual(xboxFormBody([("scope", "a b"), ("x", "urn:a/b*c-d_e.f~ğ")]), "scope=a+b&x=urn%3Aa%2Fb*c-d_e.f%7E%C4%9F")
    }

    func testOrderedJsonWriter() throws {
        let text = XboxJSONObject(["b": 1, "a": "x\"/\n", "n": NSNull(), "list": [true, 2.5, XboxJSONObject(["z": 0])]]).text
        XCTAssertEqual(text, #"{"b":1,"a":"x\"/\n","n":null,"list":[true,2.5,{"z":0}]}"#)
    }

    func testLooseReadersLikeOrgJson() throws {
        let o = try object(#"{"s":5,"n":"12","b":"true","f":1.9,"null":null}"#)
        XCTAssertEqual(o.optString("s"), "5")
        XCTAssertEqual(o.optInt("n"), 12)
        XCTAssertTrue(o.optBool("b"))
        XCTAssertEqual(o.optLong("f"), 1)
        XCTAssertEqual(o.optString("null", "d"), "d")
        XCTAssertFalse(o.hasValue("null"))
        XCTAssertEqual(o.optInt("missing", 7), 7)
    }
}

final class XboxInstantTests: XCTestCase {
    func testParsesLikeJavaTime() {
        XCTAssertEqual(XboxInstant.parse("2026-11-16T10:00:00Z"), utc(2026, 11, 16, 10))
        XCTAssertEqual(XboxInstant.parse("2026-11-16T10:30:00.123Z"), utc(2026, 11, 16, 10, 30, ms: 123))
        XCTAssertEqual(XboxInstant.parse("2026-11-16T10:16:00.1234567Z"), utc(2026, 11, 16, 10, 16, ms: 123))
        XCTAssertEqual(XboxInstant.parse("2024-02-29T23:59:59Z"), utc(2024, 2, 29, 23, 59, 59))
        XCTAssertEqual(XboxInstant.parse("1970-01-01T00:00:00Z"), 0)
        XCTAssertEqual(XboxInstant.parse("2000-03-01T00:00:00.5Z"), utc(2000, 3, 1, ms: 500))
        XCTAssertEqual(XboxInstant.parse("2099-12-31T23:59:59.999999999Z"), utc(2099, 12, 31, 23, 59, 59, ms: 999))
        XCTAssertEqual(XboxInstant.parse("2026-11-16T10:00:00+03:00"), utc(2026, 11, 16, 7))
    }

    func testRejectsInvalid() {
        XCTAssertNil(XboxInstant.parse(nil))
        XCTAssertNil(XboxInstant.parse(""))
        XCTAssertNil(XboxInstant.parse("bozuk"))
        XCTAssertNil(XboxInstant.parse("2026-13-01T00:00:00Z"))
        XCTAssertNil(XboxInstant.parse("2026-11-16 10:00:00"))
    }

    func testFormatsLikeJavaTime() {
        XCTAssertEqual(XboxInstant.format(0), "1970-01-01T00:00:00Z")
        XCTAssertEqual(XboxInstant.format(1_794_823_200_000), "2026-11-16T10:00:00Z")
        XCTAssertEqual(XboxInstant.format(1_794_823_200_123), "2026-11-16T10:00:00.123Z")
        XCTAssertEqual(XboxInstant.format(951_782_400_000), "2000-02-29T00:00:00Z")
        for ms: Int64 in [0, 1_794_823_200_000, 1_794_823_200_123, 951_782_400_000, 4_102_444_799_999] {
            XCTAssertEqual(XboxInstant.parse(XboxInstant.format(ms)), ms)
        }
    }
}

final class XboxAuthTests: XCTestCase {
    var clock = utc(2026, 11, 16, 10)

    func makeAuth(_ http: FakeHttp, token: XboxUserToken? = nil, saved: ((XboxUserToken?) -> Void)? = nil) -> XboxAuth {
        XboxAuth(http: http, clock: { self.clock }, sleep: { self.clock += $0 }, userToken: token, onUserToken: saved ?? { _ in })
    }

    func testDeviceCodeFlowWaitsThenSaves() throws {
        var polls = 0
        let http = FakeHttp { _, url, _ in
            if url.hasSuffix("/devicecode") {
                return ok(#"{"user_code":"U","device_code":"D","verification_uri":"https://www.microsoft.com/link","expires_in":900,"interval":5}"#)
            }
            if url.hasSuffix("/token") {
                polls += 1
                return polls < 3 ? XboxHttpResponse(status: 400, body: #"{"error":"authorization_pending"}"#)
                    : ok(#"{"access_token":"A","refresh_token":"R","expires_in":3600}"#)
            }
            XCTFail(url)
            return XboxHttpResponse(status: 500, body: "")
        }
        var saved: XboxUserToken?
        let auth = makeAuth(http, saved: { saved = $0 })
        let code = try auth.startDeviceCode()
        XCTAssertEqual(http.calls[0].url, "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode")
        XCTAssertEqual(http.calls[0].headers["Content-Type"], "application/x-www-form-urlencoded")
        XCTAssertTrue(http.calls[0].body!.contains("client_id=\(XboxAuth.clientId)"))
        XCTAssertTrue(http.calls[0].body!.contains("scope=xboxlive.signin+openid+profile+offline_access"))
        let start = clock
        let token = try auth.pollDeviceCode(code)
        XCTAssertEqual(token.accessToken, "A")
        XCTAssertEqual(saved?.accessToken, "A")
        XCTAssertTrue(auth.signedIn)
        XCTAssertEqual(polls, 3)
        XCTAssertEqual(clock - start, 10_000)  // iki kez 5 sn
        XCTAssertTrue(http.calls[1].body!.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        XCTAssertTrue(http.calls[1].body!.contains("device_code=D"))
    }

    func testSlowDownAddsFiveSecondsAndNetworkErrorsKeepPolling() throws {
        var step = 0
        let http = FakeHttp { _, _, _ in
            step += 1
            switch step {
            case 1: return XboxHttpResponse(status: 400, body: #"{"error":"slow_down"}"#)
            case 2: throw XboxException(.network, "kesildi")
            default: return ok(#"{"access_token":"A","refresh_token":"R","expires_in":3600}"#)
            }
        }
        let auth = makeAuth(http)
        let start = clock
        try auth.pollDeviceCode(XboxDeviceCode(userCode: "U", deviceCode: "D", verificationUri: "u", expiresInSeconds: 900, intervalSeconds: 5))
        XCTAssertEqual(clock - start, 20_000)
    }

    func testDeviceCodeExpiresAndDeclines() {
        let auth = makeAuth(FakeHttp { _, _, _ in XboxHttpResponse(status: 400, body: #"{"error":"expired_token"}"#) })
        XCTAssertThrowsError(try auth.pollDeviceCode(XboxDeviceCode(userCode: "U", deviceCode: "D", verificationUri: "u", expiresInSeconds: 900, intervalSeconds: 5))) {
            XCTAssertEqual(($0 as? XboxException)?.kind, .codeExpired)
        }
        let declined = makeAuth(FakeHttp { _, _, _ in XboxHttpResponse(status: 400, body: #"{"error":"access_denied"}"#) })
        XCTAssertThrowsError(try declined.pollDeviceCode(XboxDeviceCode(userCode: "U", deviceCode: "D", verificationUri: "u", expiresInSeconds: 900, intervalSeconds: 5))) {
            XCTAssertEqual(($0 as? XboxException)?.kind, .signInDeclined)
        }
        // Süre dolunca bekleme biter.
        let pending = makeAuth(FakeHttp { _, _, _ in XboxHttpResponse(status: 400, body: #"{"error":"authorization_pending"}"#) })
        XCTAssertThrowsError(try pending.pollDeviceCode(XboxDeviceCode(userCode: "U", deviceCode: "D", verificationUri: "u", expiresInSeconds: 12, intervalSeconds: 5))) {
            XCTAssertEqual(($0 as? XboxException)?.kind, .codeExpired)
        }
    }

    func testCancelledPollStops() {
        let auth = makeAuth(FakeHttp { _, _, _ in XboxHttpResponse(status: 400, body: #"{"error":"authorization_pending"}"#) })
        var asked = 0
        XCTAssertThrowsError(try auth.pollDeviceCode(XboxDeviceCode(userCode: "U", deviceCode: "D", verificationUri: "u", expiresInSeconds: 900, intervalSeconds: 5)) {
            asked += 1
            return asked > 2
        }) { XCTAssertTrue($0 is XboxCancelled) }
    }

    func testChainRefreshesExpiredUserTokenAndCachesXsts() throws {
        let expired = try XboxUserToken.fromResponse(object(#"{"access_token":"OLD","refresh_token":"R","expires_in":30}"#), nowMs: clock)
        let http = FakeHttp { _, url, body in
            if url.hasSuffix("/token") { return ok(#"{"access_token":"NEW","refresh_token":"R2","expires_in":3600}"#) }
            if url.contains("user.auth.xboxlive.com") {
                XCTAssertTrue(body!.contains(#""RpsTicket":"d=NEW""#))
                return ok(#"{"Token":"XBL","NotAfter":"2026-11-30T00:00:00Z","DisplayClaims":{"xui":[{"uhs":"H"}]}}"#)
            }
            if url.contains("xsts.auth.xboxlive.com") {
                XCTAssertTrue(body!.contains(#""UserTokens":["XBL"]"#))
                return ok(#"{"Token":"XSTS","NotAfter":"2026-11-17T00:00:00Z","DisplayClaims":{"xui":[{"uhs":"H"}]}}"#)
            }
            if url.contains("xhome.gssv-play-prod.xboxlive.com/v2/login/user") {
                XCTAssertTrue(body!.contains(#""offeringId":"xhome""#))
                return ok(#"{"gsToken":"GS","durationInSeconds":14400,"offeringSettings":{"regions":[{"baseUri":"https://uks.example/","isDefault":true}]}}"#)
            }
            XCTFail(url)
            return XboxHttpResponse(status: 500, body: "")
        }
        var saved: XboxUserToken?
        let auth = makeAuth(http, token: expired, saved: { saved = $0 })
        let home = try auth.homeEndpoint()
        XCTAssertEqual(home.baseUrl, "https://uks.example")
        XCTAssertEqual(saved?.refreshToken, "R2")
        let refresh = try XCTUnwrap(http.calls.first { $0.url.hasSuffix("/token") })
        XCTAssertEqual(refresh.headers["Cache-Control"], "no-store, must-revalidate, no-cache")
        XCTAssertTrue(refresh.body!.contains("grant_type=refresh_token&refresh_token=R&scope="))
        let xsts = try XCTUnwrap(http.calls.first { $0.url.contains("xsts.auth") })
        XCTAssertEqual(xsts.headers["x-xbl-contract-version"], "1")
        XCTAssertEqual(xsts.headers["ms-cv"], "0")
        XCTAssertEqual(xsts.headers["Origin"], "https://www.xbox.com")
        XCTAssertTrue(xsts.headers["User-Agent"]!.hasPrefix("Mozilla/5.0"))
        XCTAssertEqual(http.calls.first { $0.url.contains("/v2/login/user") }?.headers["x-gssv-client"], "XboxComBrowser")
        let count = http.calls.count
        _ = try auth.homeEndpoint()
        _ = try auth.xsts(XboxAuth.rpGssv)
        XCTAssertEqual(http.calls.count, count)
        // Konsol listesi için ayrı XSTS (http://xboxlive.com).
        _ = try auth.xsts(XboxAuth.rpXboxLive)
        XCTAssertEqual(http.calls.count, count + 1)
        XCTAssertTrue(http.calls.last!.body!.contains(#""RelyingParty":"http://xboxlive.com""#))
    }

    func testInvalidRefreshTokenAsksForSignIn() throws {
        let expired = try XboxUserToken.fromResponse(object(#"{"access_token":"OLD","refresh_token":"R","expires_in":0}"#), nowMs: clock)
        let auth = makeAuth(FakeHttp { _, _, _ in XboxHttpResponse(status: 400, body: #"{"error":"invalid_grant"}"#) }, token: expired)
        XCTAssertThrowsError(try auth.userAccessToken()) { XCTAssertEqual(($0 as? XboxException)?.kind, .signInRequired) }
        let none = makeAuth(FakeHttp { _, _, _ in ok("{}") })
        XCTAssertThrowsError(try none.userAccessToken()) { XCTAssertEqual(($0 as? XboxException)?.kind, .signInRequired) }
    }

    func testXstsNoXboxProfile() throws {
        let token = try XboxUserToken.fromResponse(object(#"{"access_token":"A","refresh_token":"R","expires_in":3600}"#), nowMs: clock)
        let auth = makeAuth(FakeHttp { _, url, _ in
            url.contains("user.auth") ? ok(#"{"Token":"XBL","NotAfter":"2026-11-30T00:00:00Z","DisplayClaims":{"xui":[{"uhs":"H"}]}}"#)
                : XboxHttpResponse(status: 401, body: #"{"Identity":"0","XErr":2148916233,"Message":""}"#)
        }, token: token)
        XCTAssertThrowsError(try auth.xsts(XboxAuth.rpXboxLive)) { XCTAssertEqual(($0 as? XboxException)?.kind, .noXboxAccount) }
        let other = makeAuth(FakeHttp { _, url, _ in
            url.contains("user.auth") ? ok(#"{"Token":"XBL","NotAfter":"2026-11-30T00:00:00Z","DisplayClaims":{"xui":[{"uhs":"H"}]}}"#)
                : XboxHttpResponse(status: 401, body: #"{"XErr":1}"#)
        }, token: token)
        XCTAssertThrowsError(try other.xsts(XboxAuth.rpXboxLive)) { XCTAssertEqual(($0 as? XboxException)?.kind, .signInRequired) }
    }

    func testSignOutClearsAndReports() throws {
        let token = try XboxUserToken.fromResponse(object(#"{"access_token":"A","refresh_token":"R","expires_in":3600}"#), nowMs: clock)
        var reported: [XboxUserToken?] = []
        let auth = makeAuth(FakeHttp { _, _, _ in ok("{}") }, token: token, saved: { reported.append($0) })
        XCTAssertTrue(auth.signedIn)
        auth.signOut()
        XCTAssertFalse(auth.signedIn)
        XCTAssertEqual(reported.count, 1)
        XCTAssertNil(reported[0] ?? nil)
    }
}

final class XboxHomeTests: XCTestCase {
    let clock = utc(2026, 11, 16, 10)

    func makeAuth(_ http: FakeHttp) throws -> XboxAuth {
        XboxAuth(http: http, clock: { self.clock }, sleep: { _ in },
                 userToken: try XboxUserToken.fromResponse(object(#"{"access_token":"A","refresh_token":"R","expires_in":3600}"#), nowMs: clock))
    }

    func tokens(_ url: String) -> XboxHttpResponse? {
        if url.contains("user.auth") || url.contains("xsts.auth") {
            return ok(#"{"Token":"X","NotAfter":"2026-11-30T00:00:00Z","DisplayClaims":{"xui":[{"uhs":"H"}]}}"#)
        }
        if url.contains("/v2/login/user") {
            return ok(#"{"gsToken":"GS","durationInSeconds":14400,"offeringSettings":{"regions":[{"baseUri":"https://uks.example/","isDefault":true}]}}"#)
        }
        if url.contains("login.live.com") { return ok(#"{"access_token":"LPT","refresh_token":"R3"}"#) }
        return nil
    }

    func testPlayStateConnectWithBridgeHeaders() throws {
        let http = FakeHttp { method, url, _ in
            if let token = self.tokens(url) { return token }
            if url == "https://uks.example/v5/sessions/home/play" { return ok(#"{"sessionPath":"v5/sessions/home/ABC-123"}"#) }
            if url.hasSuffix("/state") { return ok(#"{"state":"ReadyToConnect"}"#) }
            if url.hasSuffix("/connect") && method == "POST" { return XboxHttpResponse(status: 202, body: "") }
            XCTFail(url)
            return XboxHttpResponse(status: 500, body: "")
        }
        let home = XboxHome(auth: try makeAuth(http), http: http, sleep: { _ in })
        XCTAssertEqual(try home.play("F4001"), "ABC-123")
        let play = try XCTUnwrap(http.calls.first { $0.url.hasSuffix("/play") })
        XCTAssertEqual(play.method, "POST")
        XCTAssertEqual(play.headers["Authorization"], "Bearer GS")
        XCTAssertEqual(play.headers["X-Gssv-Client"], "XboxComBrowser")
        XCTAssertEqual(play.headers["X-MS-Device-Info"], XboxHome.deviceInfo)
        let body = try object(play.body)
        XCTAssertEqual(body["serverId"] as? String, "F4001")
        XCTAssertEqual((body["settings"] as? [String: Any])?["nanoVersion"] as? String, "V3;WebrtcTransport.dll")
        XCTAssertEqual((body["fallbackRegionNames"] as? [Any])?.count, 0)
        XCTAssertEqual(try home.state("ABC-123").state, "ReadyToConnect")
        XCTAssertNil(try home.state("ABC-123").errorDetails)
        try home.connect("ABC-123")
        XCTAssertTrue(http.calls.last!.body!.contains(#""userToken":"LPT""#))
        let transfer = try XCTUnwrap(http.calls.first { $0.url.contains("login.live.com") })
        XCTAssertTrue(transfer.body!.contains("scope=service%3A%3Ahttp%3A%2F%2FPassport.NET%2Fpurpose%3A%3APURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"))
        // Cihaz bilgisi geçerli JSON (Windows köprüsündeki nesneyle aynı).
        let info = try object(XboxHome.deviceInfo)
        XCTAssertEqual(((info["dev"] as? [String: Any])?["hw"] as? [String: Any])?["model"] as? String, "Surface Pro")
    }

    func testStateErrorDetails() throws {
        let http = FakeHttp { _, url, _ in
            self.tokens(url) ?? ok(#"{"state":"Failed","errorDetails":{"code":"ServerInUse","message":"busy"}}"#)
        }
        let status = try XboxHome(auth: try makeAuth(http), http: http).state("S")
        XCTAssertEqual(status.state, "Failed")
        XCTAssertEqual(status.errorDetails, #"{"code":"ServerInUse","message":"busy"}"#)
        XCTAssertEqual(XboxSessionMachine.classifyFailure(status.errorDetails), .busy)
    }

    func testConsoleListRequest() throws {
        let http = FakeHttp { _, url, _ in
            self.tokens(url) ?? ok(#"{"result":[{"id":"F1","name":"Salon","consoleType":"XboxSeriesS","consoleStreamingEnabled":true,"remoteManagementEnabled":true}]}"#)
        }
        let list = try XboxHome(auth: try makeAuth(http), http: http).consoles()
        XCTAssertEqual(list.map(\.id), ["F1"])
        let call = try XCTUnwrap(http.calls.last)
        XCTAssertEqual(call.url, "https://xccs.xboxlive.com/lists/devices?queryCurrentDevice=false&includeStorageDevices=true")
        XCTAssertEqual(call.headers["Authorization"], "XBL3.0 x=H;X")
        XCTAssertEqual(call.headers["x-xbl-contract-version"], "4")
        XCTAssertEqual(call.headers["skillplatform"], "RemoteManagement")
        let failing = FakeHttp { _, url, _ in self.tokens(url) ?? XboxHttpResponse(status: 500, body: "oops") }
        XCTAssertThrowsError(try XboxHome(auth: try makeAuth(failing), http: failing).consoles()) {
            XCTAssertEqual(($0 as? XboxException)?.kind, .service)
        }
    }

    func testSdpExchangeWaitsForResponse() throws {
        var gets = 0
        let http = FakeHttp { method, url, _ in
            if let token = self.tokens(url) { return token }
            if url.hasSuffix("/sdp") && method == "POST" { return XboxHttpResponse(status: 202, body: "") }
            if url.hasSuffix("/sdp") && method == "GET" {
                gets += 1
                if gets < 3 { return XboxHttpResponse(status: 204, body: "") }
                return ok(XboxJSONObject(["exchangeResponse": #"{"messageType":"answer","sdp":"v=0 answer","status":"success"}"#]).text)
            }
            XCTFail(url)
            return XboxHttpResponse(status: 500, body: "")
        }
        var slept: [Int64] = []
        let home = XboxHome(auth: try makeAuth(http), http: http, sleep: { slept.append($0) })
        XCTAssertEqual(try home.sdp("S", offer: "v=0 offer"), "v=0 answer")
        let offer = try object(http.calls.first { $0.method == "POST" && $0.url.hasSuffix("/sdp") }?.body)
        XCTAssertEqual(offer["messageType"] as? String, "offer")
        XCTAssertEqual(offer["sdp"] as? String, "v=0 offer")
        let configuration = try XCTUnwrap(offer["configuration"] as? [String: Any])
        XCTAssertEqual((configuration["input"] as? [String: Any])?["maxVersion"] as? Int, 8)
        XCTAssertEqual((configuration["control"] as? [String: Any])?["maxVersion"] as? Int, 3)
        XCTAssertEqual((configuration["chatConfiguration"] as? [String: Any])?["sampleFrequencyHz"] as? Int, 24000)
        XCTAssertEqual(gets, 3)
        XCTAssertEqual(slept, [250, 250])
    }

    func testExchangeTimesOut() throws {
        let http = FakeHttp { method, url, _ in
            self.tokens(url) ?? (method == "POST" ? XboxHttpResponse(status: 202, body: "") : XboxHttpResponse(status: 204, body: ""))
        }
        XCTAssertThrowsError(try XboxHome(auth: try makeAuth(http), http: http, sleep: { _ in }).sdp("S", offer: "offer")) {
            XCTAssertEqual(($0 as? XboxException)?.kind, .timeout)
        }
        XCTAssertEqual(http.calls.filter { $0.method == "GET" && $0.url.hasSuffix("/sdp") }.count, XboxHome.exchangeAttempts)
    }

    func testIceAddsTeredoCandidates() throws {
        let remote = #"""
        [{"candidate":"a=candidate:1 1 UDP 100 2001:0000:4136:e378:8000:63bf:3fff:fdd2 1234 typ host ","sdpMLineIndex":0,"sdpMid":"0"},
         {"candidate":"a=candidate:2 1 UDP 1 192.168.1.20 9002 typ host ","sdpMLineIndex":0,"sdpMid":"0"},
         {"candidate":"a=end-of-candidates","sdpMLineIndex":0,"sdpMid":"0"}]
        """#
        let http = FakeHttp { method, url, _ in
            self.tokens(url) ?? (method == "POST" ? XboxHttpResponse(status: 202, body: "") : ok(XboxJSONObject(["exchangeResponse": remote]).text))
        }
        let list = try XboxHome(auth: try makeAuth(http), http: http, sleep: { _ in })
            .ice("S", [XboxIceCandidate(candidate: "candidate:1 1 udp 2122260223 192.168.1.5 50000 typ host", sdpMid: "0", sdpMLineIndex: 0)])
        XCTAssertEqual(list.count, 5)
        XCTAssertEqual(list[0].candidate, "a=candidate:10 1 UDP 1 192.0.2.45 9002 typ host ")
        XCTAssertEqual(list[1].candidate, "a=candidate:10 1 UDP 1 192.0.2.45 40000 typ host ")
        XCTAssertEqual(list[0].sdpLine, "candidate:10 1 UDP 1 192.0.2.45 9002 typ host")
        XCTAssertEqual(list[0].sdpMid, "0")
        XCTAssertNil(list[4].sdpLine)
        let sent = try object(http.calls.first { $0.method == "POST" && $0.url.hasSuffix("/ice") }?.body)
        XCTAssertEqual(sent["messageType"] as? String, "iceCandidate")
        let candidates = try XCTUnwrap(sent["candidate"] as? [[String: Any]])
        XCTAssertEqual(candidates.count, 1)
        XCTAssertEqual(candidates[0]["sdpMid"] as? String, "0")
        XCTAssertEqual(candidates[0]["sdpMLineIndex"] as? Int, 0)
        // sdpMid yoksa null gönderilir.
        XCTAssertEqual(XboxIceCandidate(candidate: "c", sdpMid: nil, sdpMLineIndex: 1).json.text, #"{"candidate":"c","sdpMLineIndex":1,"sdpMid":null}"#)
        XCTAssertNil(XboxIceCandidate.parse(try object(#"{"candidate":"c","sdpMid":null}"#)).sdpMid)
    }

    func testServiceErrorsAreClassified() throws {
        func kind(_ status: Int, _ body: String) -> XboxError { XboxHome.serviceError(XboxHttpResponse(status: status, body: body)).kind }
        XCTAssertEqual(kind(400, #"{"code":"ServerInUse","message":"busy"}"#), .busy)
        XCTAssertEqual(kind(401, ""), .signInRequired)
        XCTAssertEqual(kind(403, "InUse"), .signInRequired)
        XCTAssertEqual(kind(404, ""), .consoleUnavailable)
        XCTAssertEqual(kind(400, "ConsoleOffline"), .consoleUnavailable)
        XCTAssertEqual(kind(500, "x"), .service)
        let http = FakeHttp { _, url, _ in self.tokens(url) ?? XboxHttpResponse(status: 400, body: #"{"code":"ServerInUse","message":"busy"}"#) }
        XCTAssertThrowsError(try XboxHome(auth: try makeAuth(http), http: http).play("F")) {
            XCTAssertEqual(($0 as? XboxException)?.kind, .busy)
        }
    }

    func testKeepaliveAndStop() throws {
        let http = FakeHttp { _, url, _ in self.tokens(url) ?? XboxHttpResponse(status: 204, body: "") }
        let home = XboxHome(auth: try makeAuth(http), http: http)
        try home.keepalive("S")
        XCTAssertEqual(http.calls.last?.url, "https://uks.example/v5/sessions/home/S/keepalive")
        XCTAssertEqual(http.calls.last?.body, "{}")
        try home.stop("S")
        XCTAssertEqual(http.calls.last?.method, "DELETE")
        XCTAssertEqual(http.calls.last?.url, "https://uks.example/v5/sessions/home/S")
        XCTAssertNil(http.calls.last?.body)
    }
}

final class XboxAccountTests: XCTestCase {
    var defaults: UserDefaults!
    var suite = ""

    override func setUp() {
        suite = "gt-xbox-test-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
    }

    func account(_ http: FakeHttp, secrets: XboxSecrets = XboxMemorySecrets()) -> XboxAccount {
        XboxAccount(secrets: secrets, defaults: defaults, http: http, clock: { utc(2026, 11, 16, 10) }, sleep: { _ in })
    }

    func testSignInShapes() async throws {
        let secrets = XboxMemorySecrets()
        let http = FakeHttp { _, url, _ in
            url.hasSuffix("/devicecode")
                ? ok(#"{"user_code":"ABCD","device_code":"D","verification_uri":"https://www.microsoft.com/link","expires_in":900,"interval":5}"#)
                : ok(#"{"access_token":"A","refresh_token":"R","expires_in":3600}"#)
        }
        let account = account(http, secrets: secrets)
        XCTAssertFalse(account.signedIn)
        let early = await account.awaitSignIn()
        XCTAssertEqual(early["ok"] as? Bool, false)
        XCTAssertEqual(early["error"] as? String, "@xs_start_first")
        let started = await account.startSignIn()
        XCTAssertEqual(started["ok"] as? Bool, true)
        XCTAssertEqual(started["userCode"] as? String, "ABCD")
        XCTAssertEqual(started["verificationUri"] as? String, "https://www.microsoft.com/link")
        XCTAssertEqual(started["expiresIn"] as? Int, 900)
        let done = await account.awaitSignIn()
        XCTAssertEqual(done as NSDictionary, ["ok": true] as NSDictionary)
        XCTAssertTrue(account.signedIn)
        // Jeton Anahtar Zinciri'ne (burada bellekteki depo) Windows/Android biçiminde yazıldı.
        XCTAssertEqual(XboxUserToken.parse(secrets.get(XboxAccount.tokenName))?.refreshToken, "R")
        // Yeni örnek kayıttan okur.
        XCTAssertTrue(self.account(http, secrets: secrets).signedIn)
        await account.signOut()
        XCTAssertFalse(account.signedIn)
        XCTAssertNil(secrets.get(XboxAccount.tokenName))
    }

    func testCancelledSignIn() async throws {
        let http = FakeHttp { _, url, _ in
            url.hasSuffix("/devicecode") ? ok(#"{"user_code":"U","device_code":"D","interval":1}"#)
                : XboxHttpResponse(status: 400, body: #"{"error":"authorization_pending"}"#)
        }
        let account = XboxAccount(secrets: XboxMemorySecrets(), defaults: defaults, http: http, clock: { utc(2026, 11, 16, 10) },
                                  sleep: { _ in Thread.sleep(forTimeInterval: 0.01) })
        _ = await account.startSignIn()
        async let waiting = account.awaitSignIn()
        try await Task.sleep(nanoseconds: 50_000_000)
        account.cancelSignIn()
        let result = await waiting
        XCTAssertEqual(result["ok"] as? Bool, false)
        XCTAssertEqual(result["cancelled"] as? Bool, true)
    }

    func testSignInErrorsAreCodes() async {
        let expired = account(FakeHttp { _, url, _ in
            url.hasSuffix("/devicecode") ? ok(#"{"user_code":"U","device_code":"D"}"#) : XboxHttpResponse(status: 400, body: #"{"error":"expired_token"}"#)
        })
        _ = await expired.startSignIn()
        let result = await expired.awaitSignIn()
        XCTAssertEqual(result["error"] as? String, "@xs_code_expired")
        let offline = account(FakeHttp { _, _, _ in throw XboxException(.network, "yok") })
        let start = await offline.startSignIn()
        XCTAssertEqual(start as NSDictionary, ["ok": false, "error": "@xs_network"] as NSDictionary)
        let broken = account(FakeHttp { _, _, _ in XboxHttpResponse(status: 500, body: "x") })
        let failed = await broken.startSignIn()
        XCTAssertEqual(failed["error"] as? String, "@xs_service\u{1f}Microsoft cihaz kodu: HTTP 500 x")
    }

    func testConsolesShapeAndNeedsSignIn() async throws {
        let notSigned = await account(FakeHttp { _, _, _ in ok("{}") }).consoles()
        XCTAssertEqual(notSigned as NSDictionary, ["ok": false, "error": "@xs_signin_incomplete", "needsSignIn": true] as NSDictionary)

        let secrets = XboxMemorySecrets()
        secrets.put(XboxAccount.tokenName, try XboxUserToken.fromResponse(object(#"{"access_token":"A","refresh_token":"R","expires_in":3600}"#), nowMs: utc(2026, 11, 16, 10)).serialize())
        let http = FakeHttp { _, url, _ in
            if url.contains("auth.xboxlive.com") { return ok(#"{"Token":"X","NotAfter":"2026-11-30T00:00:00Z","DisplayClaims":{"xui":[{"uhs":"H"}]}}"#) }
            return ok(#"{"result":[{"id":"F1","name":"Salon","consoleType":"XboxSeriesX","powerState":"On","consoleStreamingEnabled":true,"remoteManagementEnabled":true}]}"#)
        }
        let result = await account(http, secrets: secrets).consoles()
        XCTAssertEqual(result["ok"] as? Bool, true)
        let list = try XCTUnwrap(result["consoles"] as? [[String: Any]])
        XCTAssertEqual(list[0] as NSDictionary, ["id": "F1", "name": "Salon", "model": "XboxSeriesX", "streamingEnabled": true, "powerState": "On"] as NSDictionary)
    }

    func testSelectedConsoleIsPersistedLikeAndroid() {
        let account = account(FakeHttp { _, _, _ in ok("{}") })
        XCTAssertNil(account.selectedConsole)
        XCTAssertEqual(account.setupFields(consoleKind: "xbox") as NSDictionary, ["xboxSignedIn": false] as NSDictionary)
        account.selectConsole(id: "F1", name: "", model: "XboxSeriesS", streamingEnabled: true, powerState: "On")
        let stored = try? object(defaults.string(forKey: "xboxConsole"))
        XCTAssertEqual(stored?["id"] as? String, "F1")
        XCTAssertEqual(stored?["model"] as? String, "XboxSeriesS")
        XCTAssertEqual(account.selectedConsole?.label, "XboxSeriesS")
        XCTAssertEqual(account.setupFields(consoleKind: "ps5") as NSDictionary, ["xboxSignedIn": false, "xboxConsoleName": "XboxSeriesS"] as NSDictionary)
        account.forgetConsole()
        XCTAssertNil(account.selectedConsole)
        XCTAssertNil(defaults.string(forKey: "xboxConsole"))
    }

    func testStreamAndSetupCodes() {
        XCTAssertEqual(XboxAccount.describeStream(.signInRequired), "@xr_signin_expired")
        XCTAssertEqual(XboxAccount.describeStream(.codeExpired), "@xr_signin_expired")
        XCTAssertEqual(XboxAccount.describeStream(.noXboxAccount), "@x_no_profile")
        XCTAssertEqual(XboxAccount.describeStream(.consoleUnavailable), "@xr_unavailable")
        XCTAssertEqual(XboxAccount.describeStream(.busy), "@xr_busy")
        XCTAssertEqual(XboxAccount.describeStream(.timeout), "@xr_timeout")
        XCTAssertEqual(XboxAccount.describeStream(.network), "@xr_network")
        XCTAssertEqual(XboxAccount.describeStream(.service), "@xr_service")
        XCTAssertEqual(XboxAccount.describeSetup(XboxException(.signInDeclined, "")), "@xs_signin_incomplete")
        XCTAssertEqual(XboxAccount.describeSetup(XboxException(.noXboxAccount, "")), "@x_no_profile")
        XCTAssertEqual(XboxAccount.describeSetup(XboxException(.busy, "")), "@xs_service\u{1f}BUSY")
    }

    func testKeychainRoundTrip() throws {
        let store = XboxKeychain(service: "gametranslator-test-\(UUID().uuidString)")
        store.put("t", "değer")
        guard let value = store.get("t") else { throw XCTSkip("Anahtar Zinciri bu ortamda kullanılamıyor") }
        XCTAssertEqual(value, "değer")
        store.put("t", "yeni")
        XCTAssertEqual(store.get("t"), "yeni")
        store.put("t", nil)
        XCTAssertNil(store.get("t"))
    }
}
