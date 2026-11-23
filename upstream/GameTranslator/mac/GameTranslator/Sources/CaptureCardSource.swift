// SPDX-License-Identifier: AGPL-3.0-only

import AVFoundation
import CoreGraphics
import CoreVideo
import Foundation
import GTNative

/// Kaynakların arayüz dilinde hata iletileri (kaynaklar ana iş parçacığı dışında çalışır; metinler kurulurken seçilir).
struct SourceText: Sendable {
    var cameraDenied: String
    var noDevice: String
    var deviceLost: String
    var screenDenied: String
    var noWindow: String
    var noDisplay: String
    var captureFailed: String

    @MainActor static var current: SourceText {
        SourceText(
            cameraDenied: L("Yakalama kartını kullanmak için kamera izni gerekli: Sistem Ayarları › Gizlilik ve Güvenlik › Kamera'dan GameTranslator'ı aç.",
                            "Camera access is needed to use the capture card: open System Settings › Privacy & Security › Camera and turn on GameTranslator."),
            noDevice: L("Yakalama kartı bulunamadı. Kartı tak ve Ayarlar'dan seç.", "No capture card found. Plug it in and choose it in Settings."),
            deviceLost: L("Yakalama kartı çıkarıldı.", "The capture card was unplugged."),
            screenDenied: L("Ekranı okumak için Ekran Kaydı izni gerekli: Sistem Ayarları › Gizlilik ve Güvenlik › Ekran ve Sistem Ses Kaydı'ndan GameTranslator'ı aç, sonra uygulamayı yeniden başlat.",
                            "Screen Recording permission is needed to read the screen: open System Settings › Privacy & Security › Screen & System Audio Recording, turn on GameTranslator, then restart the app."),
            noWindow: L("Seçilen pencere bulunamadı. Oyunu aç ya da Ayarlar'dan başka pencere seç.",
                        "The chosen window wasn't found. Open the game or choose another window in Settings."),
            noDisplay: L("Seçilen ekran bulunamadı.", "The chosen display wasn't found."),
            captureFailed: L("Görüntü alınamadı", "Couldn't capture the picture"))
    }
}

/// BGRA piksel arabelleğinden CGImage (kopyalanır; arabellek hemen geri verilebilir).
enum PixelBufferImage {
    static func make(_ buffer: CVPixelBuffer) -> CGImage? {
        guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA else { return nil }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let info = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
        let context = CGContext(data: base, width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer),
                                bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info)
        return context?.makeImage()
    }
}

/// USB yakalama kartı (Elgato, ucuz HDMI kartları): AVCaptureSession, saniyede ~20 kare BGRA → CGImage → motor.
/// Kart `AVCaptureDevice.uniqueID` ile seçilir; boşsa ya da bulunamazsa ilk dış aygıt kullanılır.
final class CaptureCardSource: NSObject, FrameSource, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    static let fps = 20.0

    let label: String
    private let device: AVCaptureDevice?
    private let text: SourceText
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "gt.capture-card", qos: .userInitiated)
    private weak var engine: Engine?
    private var lastFrame: TimeInterval = 0
    private var observers: [NSObjectProtocol] = []

    /// Bağlı video aygıtları: önce dış aygıtlar (yakalama kartları), sonra yerleşik kamera.
    static func devices() -> [AVCaptureDevice] {
        AVCaptureDevice.DiscoverySession(deviceTypes: [.external, .builtInWideAngleCamera, .continuityCamera], mediaType: .video,
                                         position: .unspecified).devices
            .sorted { ($0.deviceType == .external ? 0 : 1) < ($1.deviceType == .external ? 0 : 1) }
    }

    @MainActor init(deviceID: String) {
        let all = Self.devices()
        device = all.first { $0.uniqueID == deviceID } ?? all.first { $0.deviceType == .external } ?? all.first
        label = device?.localizedName ?? L("Yakalama kartı", "Capture card")
        text = .current
        super.init()
    }

    func start(into engine: Engine) {
        self.engine = engine
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            queue.async { self.run() }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                if granted { self.queue.async { self.run() } } else { engine.sourceFailed(self.text.cameraDenied) }
            }
        default:
            engine.sourceFailed(text.cameraDenied)
        }
    }

    /// Kuyrukta: oturum kurulur ve açılır.
    private func run() {
        guard let engine else { return }
        guard let device else { return engine.sourceFailed(text.noDevice) }
        do {
            session.beginConfiguration()
            let input = try AVCaptureDeviceInput(device: device)
            guard session.canAddInput(input) else { throw CocoaError(.featureUnsupported) }
            session.addInput(input)
            let output = AVCaptureVideoDataOutput()
            output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            output.alwaysDiscardsLateVideoFrames = true
            output.setSampleBufferDelegate(self, queue: queue)
            guard session.canAddOutput(output) else { throw CocoaError(.featureUnsupported) }
            session.addOutput(output)
            if session.canSetSessionPreset(.hd1920x1080) { session.sessionPreset = .hd1920x1080 }
            session.commitConfiguration()
        } catch {
            session.commitConfiguration()
            return engine.sourceFailed("\(text.captureFailed): \(error.localizedDescription)")
        }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVCaptureDevice.wasDisconnectedNotification, object: device, queue: nil) { [weak self] _ in
            guard let self else { return }
            self.engine?.sourceFailed(self.text.deviceLost)
        })
        observers.append(center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: nil) { [weak self] note in
            guard let self else { return }
            let error = note.userInfo?[AVCaptureSessionErrorKey] as? Error
            self.engine?.sourceFailed("\(self.text.captureFailed): \(error?.localizedDescription ?? "?")")
        })
        session.startRunning()
    }

    func stop() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        queue.async {
            self.engine = nil
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastFrame >= 1 / Self.fps * 0.9, let engine, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              let image = PixelBufferImage.make(buffer) else { return }
        lastFrame = now
        engine.submit(frame: image)
    }
}
