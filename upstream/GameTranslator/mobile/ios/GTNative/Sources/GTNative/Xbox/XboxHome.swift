// SPDX-License-Identifier: AGPL-3.0-only
// Xbox konsol listesi ve ev yayını uçları (Android: xbox/XboxHome.kt): Windows köprüsündeki (windows/xbox-bridge/
// bridge.cjs) istekler birebir. Protokol sırası Greenlight ve xbox-xcloud-player'dan (MIT, unknownskl).

import Foundation

/// ICE adayı (gssv `/ice` biçimi; `candidate` "a=candidate:…" ya da "candidate:…").
struct XboxIceCandidate: Equatable {
    var candidate: String
    var sdpMid: String?
    var sdpMLineIndex: Int

    var json: XboxJSONObject {
        XboxJSONObject(["candidate": candidate, "sdpMLineIndex": sdpMLineIndex, "sdpMid": sdpMid ?? NSNull()])
    }

    /// libwebrtc'ye verilecek satır: "a=" öneki ve sondaki boşluk atılır; "end-of-candidates" için nil.
    var sdpLine: String? {
        var line = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
        if line.hasPrefix("a=") { line.removeFirst(2) }
        return line.hasPrefix("candidate:") ? line : nil
    }

    static func parse(_ o: [String: Any]) -> XboxIceCandidate {
        XboxIceCandidate(candidate: o.optString("candidate"), sdpMid: o.hasValue("sdpMid") ? o.optString("sdpMid") : nil,
                         sdpMLineIndex: o.optInt("sdpMLineIndex"))
    }
}

/// `/state` yanıtı.
struct XboxSessionStatus {
    let state: String
    let errorDetails: String?
}

final class XboxHome: @unchecked Sendable {
    /// Xbox yalnız tarayıcı/Windows istemcisini kabul ediyor; köprüdeki X-MS-Device-Info birebir.
    static let deviceInfo = "{\"appInfo\":{\"env\":{\"clientAppId\":\"Microsoft.GamingApp\",\"clientAppType\":\"native\"," +
        "\"clientAppVersion\":\"2203.1001.4.0\",\"clientSdkVersion\":\"8.5.2\",\"httpEnvironment\":\"prod\",\"sdkInstallId\":\"\"}}," +
        "\"dev\":{\"hw\":{\"make\":\"Microsoft\",\"model\":\"Surface Pro\",\"sdktype\":\"native\"}," +
        "\"os\":{\"name\":\"Windows 11\",\"ver\":\"22631.2715\",\"platform\":\"desktop\"}," +
        "\"displayInfo\":{\"dimensions\":{\"widthInPixels\":1920,\"heightInPixels\":1080},\"pixelDensity\":{\"dpiX\":1,\"dpiY\":1}}}}"
    static let exchangeAttempts = 30
    static let exchangeIntervalMs: Int64 = 250

    private let auth: XboxAuth
    private let http: XboxHttp
    private let sleep: (Int64) -> Void

    init(auth: XboxAuth, http: XboxHttp? = nil, sleep: @escaping (Int64) -> Void = { Thread.sleep(forTimeInterval: Double($0) / 1000) }) {
        self.auth = auth
        self.http = http ?? auth.http
        self.sleep = sleep
    }

    /// `/play` gövdesi (köprüdeki `start`).
    static func playBody(consoleId: String, timezoneOffsetMinutes: Int) -> XboxJSONObject {
        XboxJSONObject([
            "clientSessionId": "", "titleId": "", "systemUpdateGroup": "", "serverId": consoleId,
            "fallbackRegionNames": [Any](),
            "settings": XboxJSONObject([
                "nanoVersion": "V3;WebrtcTransport.dll", "enableOptionalDataCollection": false, "enableTextToSpeech": false,
                "highContrast": 0, "locale": "en-US", "useIceConnection": false, "timezoneOffsetMinutes": timezoneOffsetMinutes,
                "sdkType": "web", "osName": "windows",
            ]),
        ])
    }

    /// `/sdp` teklif gövdesi (kanal sürümleri ve sohbet sesi biçimi köprüdeki gibi).
    static func sdpBody(_ sdp: String) -> XboxJSONObject {
        func range(_ min: Int, _ max: Int) -> XboxJSONObject { XboxJSONObject(["minVersion": min, "maxVersion": max]) }
        return XboxJSONObject([
            "messageType": "offer", "sdp": sdp,
            "configuration": XboxJSONObject([
                "chatConfiguration": XboxJSONObject([
                    "bytesPerSample": 2, "expectedClipDurationMs": 20,
                    "format": XboxJSONObject(["codec": "opus", "container": "webm"]), "numChannels": 1, "sampleFrequencyHz": 24000,
                ]),
                "chat": range(1, 1), "control": range(1, 3), "input": range(1, 8), "message": range(1, 1),
            ]),
        ])
    }

    /// `sessionPath` ("v5/sessions/home/<id>") son parçası.
    static func sessionId(_ playResponse: [String: Any]) -> String? {
        let last = playResponse.optString("sessionPath").components(separatedBy: "/").last ?? ""
        return last.trimmingCharacters(in: .whitespaces).isEmpty ? nil : last
    }

    /// Köprüdeki gibi: "2001" ile başlayan adresten Teredo'nun IPv4 adayları (9002 ve Teredo bağlantı noktası) öne eklenir.
    static func expandCandidates(_ candidates: [XboxIceCandidate]) -> [XboxIceCandidate] {
        var expanded: [XboxIceCandidate] = []
        for item in candidates {
            let parts = item.candidate.split(separator: " ", omittingEmptySubsequences: false)
            if parts.count > 4, parts[4].hasPrefix("2001"), let teredo = Teredo.inspect(String(parts[4])) {
                for port in [9002, teredo.udpPort] {
                    var copy = item
                    copy.candidate = "a=candidate:10 1 UDP 1 \(teredo.client4) \(port) typ host "
                    expanded.append(copy)
                }
            }
            expanded.append(item)
        }
        return expanded
    }

    static func timezoneOffsetMinutes(_ now: Date = Date()) -> Int { TimeZone.current.secondsFromGMT(for: now) / 60 }

    /// Hesaptaki konsollar (xbox-webapi smartglass `getConsolesList`).
    func consoles() throws -> [XboxConsole] {
        let token = try auth.xsts(XboxAuth.rpXboxLive)
        let response = try http.request("GET", "https://xccs.xboxlive.com/lists/devices?queryCurrentDevice=false&includeStorageDevices=true", [
            "Accept-Language": "en-US", "Accept": "application/json", "x-xbl-contract-version": "4",
            "Content-Type": "application/json", "skillplatform": "RemoteManagement",
            "Authorization": "XBL3.0 x=\(token.userHash);\(token.token)"], nil)
        if !response.ok { throw XboxException(.service, "Xbox listesi: HTTP \(response.status) \(response.body.prefix(300))") }
        return XboxConsole.parseList(try XboxJSON.parseObject(response.body))
    }

    /// gssv isteği (köprüdeki `api`): 204 ya da boş gövde nil.
    @discardableResult
    func api(_ path: String, _ method: String = "GET", _ data: XboxJSONObject? = nil) throws -> [String: Any]? {
        let endpoint = try auth.homeEndpoint()
        let response = try http.request(method, endpoint.baseUrl + path, [
            "Accept": "application/json", "Content-Type": "application/json",
            "Authorization": "Bearer \(endpoint.token)", "X-Gssv-Client": "XboxComBrowser",
            "X-MS-Device-Info": Self.deviceInfo], data?.text)
        if !response.ok { throw Self.serviceError(response) }
        if response.status == 204 || response.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
        return try XboxJSON.parseObject(response.body)
    }

    static func serviceError(_ response: XboxHttpResponse) -> XboxException {
        let detail = String(response.body.prefix(500))
        func has(_ word: String) -> Bool { detail.range(of: word, options: .caseInsensitive) != nil }
        let kind: XboxError
        if response.status == 401 || response.status == 403 {
            kind = .signInRequired
        } else if has("InUse") || has("Busy") {
            kind = .busy
        } else if response.status == 404 || has("NotFound") || has("Offline") {
            kind = .consoleUnavailable
        } else {
            kind = .service
        }
        return XboxException(kind, "Xbox service HTTP \(response.status): \(detail)")
    }

    func play(_ consoleId: String) throws -> String {
        let result = try api("/v5/sessions/home/play", "POST", Self.playBody(consoleId: consoleId, timezoneOffsetMinutes: Self.timezoneOffsetMinutes()))
        guard let result, let id = Self.sessionId(result) else { throw XboxException(.service, "Xbox did not return a stream session") }
        return id
    }

    func state(_ sessionId: String) throws -> XboxSessionStatus {
        let status = try api("/v5/sessions/home/\(sessionId)/state") ?? [:]
        let details = status.hasValue("errorDetails") ? XboxJSON.text(of: status["errorDetails"]!) : nil
        return XboxSessionStatus(state: status.optString("state"), errorDetails: details)
    }

    func connect(_ sessionId: String) throws {
        try api("/v5/sessions/home/\(sessionId)/connect", "POST", XboxJSONObject(["userToken": try auth.transferToken()]))
    }

    /// POST, sonra yanıt gelene kadar GET (köprüdeki `exchange`).
    private func exchange(_ path: String, _ payload: XboxJSONObject) throws -> String {
        try api(path, "POST", payload)
        for _ in 0..<Self.exchangeAttempts {
            let text = try api(path)?.optString("exchangeResponse") ?? ""
            if !text.isEmpty { return text }
            sleep(Self.exchangeIntervalMs)
        }
        throw XboxException(.timeout, "Xbox WebRTC exchange timed out")
    }

    /// SDP yanıtı (Xbox'ın "answer" SDP'si).
    func sdp(_ sessionId: String, offer: String) throws -> String {
        let answer = try XboxJSON.parseObject(try exchange("/v5/sessions/home/\(sessionId)/sdp", Self.sdpBody(offer)))
        let sdp = answer.optString("sdp")
        if sdp.isEmpty { throw XboxException(.service, "Xbox SDP yanıtı boş: \(XboxJSON.text(of: answer).prefix(200))") }
        return sdp
    }

    func ice(_ sessionId: String, _ candidates: [XboxIceCandidate]) throws -> [XboxIceCandidate] {
        let payload = XboxJSONObject(["messageType": "iceCandidate", "candidate": candidates.map { $0.json as Any }])
        let text = try exchange("/v5/sessions/home/\(sessionId)/ice", payload)
        guard let array = XboxJSON.array(text) else { throw XboxException(.service, "Xbox ICE yanıtı okunamadı") }
        return Self.expandCandidates(array.compactMap { ($0 as? [String: Any]).map(XboxIceCandidate.parse) })
    }

    func keepalive(_ sessionId: String) throws {
        try api("/v5/sessions/home/\(sessionId)/keepalive", "POST", XboxJSONObject([:]))
    }

    func stop(_ sessionId: String) throws {
        try api("/v5/sessions/home/\(sessionId)", "DELETE")
    }
}
