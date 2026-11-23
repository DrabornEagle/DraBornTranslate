// SPDX-License-Identifier: AGPL-3.0-only
// Kare onayı (Android: xbox/XboxFrameAcks.kt): Windows'taki xbox-xcloud-player 0.2.11 (MIT, unknownskl) her gösterilen
// karede `input` kanalına bir Metadata kaydı yazar (Component/Video.ts `requestVideoFrameCallback` → Channel/Input.ts
// `addProcessedFrame`, 16 ms'de bir paket). Xbox bu onayları bekler: onay gitmezse yayın ~40 sn sonra kapanır
// (Greenlight wiki, WebRTC).
//
// iPhone'da kare bilgisi Android'deki gibi çözücüden alınır: `XboxDecoderFactory` WebRTC'nin kendi çözücü fabrikasını
// (RTCDefaultVideoDecoderFactory: H.264 için VideoToolbox, VP8/VP9/AV1 için yazılım) sarar. Çözücüye giren
// RTCEncodedImage'ın `timeStamp`'i ve çıkan RTCVideoFrame'in `timeStamp`'i karenin RTP zaman damgasıdır (90 kHz;
// RTCVideoDecoderH264 çıkan kareye girişteki damgayı yazar); geliş anı girişte, çözülme anı çıkışta ölçülür.

import Foundation
import WebRTC

/// Çözülen kare: RTP zaman damgasının milisaniyesi (damga / 90), çözücüye geliş ve çözülme anı (tekdüze saat, ms).
typealias XboxDecodedFrameListener = (_ rtpMs: Int64, _ arrivedAt: Int64, _ decodedAt: Int64) -> Void

/// Tekdüze saat (ms; Android'deki SystemClock.elapsedRealtime yerine).
func xboxUptimeMs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }

/// WebRTC'nin kendi fabrikasının aynısı; yalnız çözücüler çözdükleri kareyi bildirir.
final class XboxDecoderFactory: NSObject, RTCVideoDecoderFactory {
    private let inner: RTCVideoDecoderFactory
    private let listener: XboxDecodedFrameListener
    private let onError: (String) -> Void

    init(listener: @escaping XboxDecodedFrameListener, onError: @escaping (String) -> Void = { _ in },
         inner: RTCVideoDecoderFactory = RTCDefaultVideoDecoderFactory()) {
        self.inner = inner
        self.listener = listener
        self.onError = onError
    }

    func createDecoder(_ info: RTCVideoCodecInfo) -> RTCVideoDecoder? {
        inner.createDecoder(info).map { XboxReportingDecoder($0, listener: listener, onError: onError) }
    }

    func supportedCodecs() -> [RTCVideoCodecInfo] { inner.supportedCodecs() }
}

/// Çözücüyü sarar: girişte geliş anını, çıkışta kareyi bildirir; kareye dokunmaz. Çözücünün hata kodu bir kez
/// günlüğe yazılır.
final class XboxReportingDecoder: NSObject, RTCVideoDecoder, @unchecked Sendable {
    private let inner: RTCVideoDecoder
    private let listener: XboxDecodedFrameListener
    private let onError: (String) -> Void
    private let clock: () -> Int64
    private let lock = NSLock()
    /// RTP damgası → çözücüye geliş anı.
    private var arrivals: [UInt32: Int64] = [:]
    private var errorReported = false

    init(_ inner: RTCVideoDecoder, listener: @escaping XboxDecodedFrameListener, onError: @escaping (String) -> Void = { _ in },
         clock: @escaping () -> Int64 = xboxUptimeMs) {
        self.inner = inner
        self.listener = listener
        self.onError = onError
        self.clock = clock
    }

    func setCallback(_ callback: @escaping RTCVideoDecoderCallback) {
        inner.setCallback { [weak self] frame in
            if let self {
                let now = self.clock()
                let rtp = UInt32(bitPattern: frame.timeStamp)
                let arrived = self.lock.withLock { self.arrivals.removeValue(forKey: rtp) } ?? now
                self.listener(Int64(rtp) / 90, arrived, now)
            }
            callback(frame)
        }
    }

    func startDecode(withNumberOfCores numberOfCores: Int32) -> Int {
        report("başlatma", inner.startDecode(withNumberOfCores: numberOfCores))
    }

    func release() -> Int {
        lock.withLock { arrivals.removeAll() }
        return report("kapatma", inner.release())
    }

    func decode(_ encodedImage: RTCEncodedImage, missingFrames: Bool, codecSpecificInfo info: RTCCodecSpecificInfo?, renderTimeMs: Int64) -> Int {
        let now = clock()
        lock.withLock {
            // Çözücünün düşürdüğü karelerin kaydı birikmesin.
            if arrivals.count > 240 { arrivals.removeAll() }
            arrivals[encodedImage.timeStamp] = now
        }
        return report("çözme", inner.decode(encodedImage, missingFrames: missingFrames, codecSpecificInfo: info, renderTimeMs: renderTimeMs))
    }

    func implementationName() -> String { inner.implementationName() }

    /// WEBRTC_VIDEO_CODEC_OK = 0; eksi değerler hata (1 = OK_REQUEST_KEYFRAME, hata sayılmaz).
    private func report(_ stage: String, _ status: Int) -> Int {
        if status < 0 {
            let first = lock.withLock { () -> Bool in
                defer { errorReported = true }
                return !errorReported
            }
            if first { onError("çözücü hatası (\(stage), \(inner.implementationName())): kod \(status)") }
        }
        return status
    }
}

/// Gönderilmeyi bekleyen kare onayları (oynatıcının `_frameMetadataQueue`'su).
final class XboxFrameAckQueue: @unchecked Sendable {
    private let capacity: Int
    private let lock = NSLock()
    private var items: [XboxFrameAck] = []

    init(capacity: Int = 600) {
        self.capacity = capacity
    }

    func offer(_ ack: XboxFrameAck) {
        lock.withLock {
            // Kanal açılmadan biriken onaylar eskir; en yeniler tutulur.
            if items.count >= capacity { items.removeFirst() }
            items.append(ack)
        }
    }

    func drain(max: Int = XboxChannels.maxFrameAcks) -> [XboxFrameAck] {
        lock.withLock {
            let count = Swift.min(max, items.count)
            let out = Array(items.prefix(count))
            items.removeFirst(count)
            return out
        }
    }

    func clear() {
        lock.withLock { items.removeAll() }
    }

    /// RTP damgasının milisaniyesinden serverDataKey (90 kHz saat; u32).
    static func serverDataKey(_ rtpMs: Int64) -> Int64 { (rtpMs &* 90) & 0xFFFF_FFFF }
}
