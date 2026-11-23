// SPDX-License-Identifier: AGPL-3.0-only
// WebRTC veri kanallarının iletileri (Android: xbox/XboxChannels.kt): Windows'taki xbox-xcloud-player 0.2.11'in (MIT,
// unknownskl) gönderdikleriyle birebir (Channel/Message.ts, Control.ts, Input/Packet.ts). Kumanda ve dokunma girdisi
// gönderilmez.

import Foundation

/// Çözülen bir görüntü karesinin onayı: `serverDataKey` karenin RTP zaman damgası, anlar oturum saatine göre ms.
struct XboxFrameAck: Equatable {
    let serverDataKey: Int64
    let arrivalMs: Int64
    let decodedMs: Int64
}

enum XboxChannels {
    /// Kanal adı → protokol; `input` sıralıdır. Oluşturma sırası oynatıcıdaki gibi.
    static let channels: [(name: String, protocol: String)] = [
        ("input", "1.0"), ("chat", "chatV1"), ("control", "controlV1"), ("message", "messageV1"),
    ]

    static let handshake = "{\"type\":\"Handshake\",\"version\":\"messageV1\",\"id\":\"f9c5f412-0e69-4ede-8e62-92c7f5358c56\",\"cv\":\"\"}"
    static let authorization = "{\"message\":\"authorizationRequest\",\"accessKey\":\"4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E\"}"
    static let gamepadAdded = "{\"message\":\"gamepadChanged\",\"gamepadIndex\":0,\"wasAdded\":true}"
    private static let messageId = "41f93d5a-900f-4d33-b7a1-2d4ca6747072"

    /// Oynatıcı bir pakette en çok 29 kare onaylar (Input.ts `getMetadataQueue(30)` → `splice(0, 29)`).
    static let maxFrameAcks = 29

    static func isHandshakeAck(_ text: String) -> Bool {
        XboxJSON.object(text)?.optString("type") == "HandshakeAck"
    }

    /// `message` kanalına HandshakeAck'ten sonra gönderilen altı yapılandırma iletisi.
    static func configurationMessages(width: Int = 1920, height: Int = 1080) -> [String] {
        [
            message("/streaming/systemUi/configuration", XboxJSONObject(["version": [0, 1, 0], "systemUis": [Any]()])),
            message("/streaming/properties/clientappinstallidchanged", XboxJSONObject(["clientAppInstallId": "c11ddb2e-c7e3-4f02-a62b-fd5448e0b851"])),
            message("/streaming/characteristics/orientationchanged", XboxJSONObject(["orientation": 0])),
            message("/streaming/characteristics/touchinputenabledchanged", XboxJSONObject(["touchInputEnabled": false])),
            message("/streaming/characteristics/clientdevicecapabilities", XboxJSONObject([:])),
            message("/streaming/characteristics/dimensionschanged", XboxJSONObject([
                "horizontal": width, "vertical": height, "preferredWidth": width, "preferredHeight": height,
                "safeAreaLeft": 0, "safeAreaTop": 0, "safeAreaRight": width, "safeAreaBottom": height, "supportsCustomResolution": true,
            ])),
        ]
    }

    private static func message(_ target: String, _ data: XboxJSONObject) -> String {
        XboxJSONObject(["type": "Message", "content": data.text, "id": messageId, "target": target, "cv": ""]).text
    }

    /// `input` kanalının ilk paketi (ClientMetadata, 15 bayt): rapor türü 8 (u16 LE), sıra no (u32 LE), zaman damgası
    /// (f64 LE, ms), en çok dokunma noktası (u8; oynatıcı 2 gönderir).
    static func clientMetadataPacket(sequence: Int = 0, timestampMs: Double, maxTouchpoints: Int = 2) -> Data {
        var out = LittleEndianWriter()
        out.u16(8)
        out.u32(UInt32(truncatingIfNeeded: sequence))
        out.f64(timestampMs)
        out.u8(UInt8(truncatingIfNeeded: maxTouchpoints))
        return out.data
    }

    /// `input` kanalının kare onayı paketi (Input/Packet.ts, rapor türü 1 = Metadata): tür (u16 LE), sıra no (u32 LE),
    /// zaman damgası (f64 LE, ms), kare sayısı (u8), her kare için yedi u32 LE: serverDataKey (karenin RTP zaman
    /// damgası), varış, gönderim, çözülme, gösterim, paket hazırlama ve gönderme anları (ms). Son ikisi oynatıcıda
    /// paketin yazıldığı an; burada `timestampMs`.
    static func frameAckPacket(sequence: Int, timestampMs: Double, frames: [XboxFrameAck]) -> Data {
        precondition((1...maxFrameAcks).contains(frames.count), "kare sayısı: \(frames.count)")
        var out = LittleEndianWriter()
        out.u16(1)
        out.u32(UInt32(truncatingIfNeeded: sequence))
        out.f64(timestampMs)
        out.u8(UInt8(frames.count))
        let now = Int64(timestampMs)
        for frame in frames {
            // JavaScript'teki setUint32 gibi: alt 32 bit yazılır.
            out.u32(UInt32(truncatingIfNeeded: frame.serverDataKey))
            out.u32(UInt32(truncatingIfNeeded: frame.arrivalMs))
            out.u32(UInt32(truncatingIfNeeded: frame.arrivalMs))
            out.u32(UInt32(truncatingIfNeeded: frame.decodedMs))
            out.u32(UInt32(truncatingIfNeeded: frame.decodedMs))
            out.u32(UInt32(truncatingIfNeeded: now))
            out.u32(UInt32(truncatingIfNeeded: now))
        }
        return out.data
    }

    /// Oynatıcının SDP düzeltmesi: stereo ses (`useinbandfec=1` ilk geçişte).
    static func mungeOffer(_ sdp: String) -> String {
        guard let range = sdp.range(of: "useinbandfec=1") else { return sdp }
        return sdp.replacingCharacters(in: range, with: "useinbandfec=1; stereo=1")
    }
}

struct LittleEndianWriter {
    private(set) var data = Data()

    mutating func u8(_ value: UInt8) { data.append(value) }
    mutating func u16(_ value: UInt16) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
    mutating func u32(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
    mutating func f64(_ value: Double) { withUnsafeBytes(of: value.bitPattern.littleEndian) { data.append(contentsOf: $0) } }
}
