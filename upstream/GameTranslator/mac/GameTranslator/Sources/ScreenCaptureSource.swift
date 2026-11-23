// SPDX-License-Identifier: AGPL-3.0-only

import CoreGraphics
import CoreMedia
import Foundation
import GTNative
import ScreenCaptureKit

/// Mac'te açık bir pencere ya da ekranın tamamı (ScreenCaptureKit): PS5 Remote Play uygulaması, Xbox uygulaması, bulut
/// oyun penceresi ya da Mac oyunu. Pencere kimliği her açılışta değiştiği için pencere paket kimliği ve başlıkla bulunur
/// (başlık tutmazsa o uygulamanın en büyük penceresi). Ekranın tamamında kendi pencerelerimiz (altyazı katmanı dahil)
/// görüntüye girmez.
///
/// Ekran Kaydı izni yoksa macOS ilk denemede izni sorar; reddedilirse ya da izin sonradan açıldıysa uygulamanın yeniden
/// başlatılması gerekir; ileti bunu söyler.
final class ScreenCaptureSource: NSObject, FrameSource, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {
    enum Target: Equatable, Sendable {
        case window(bundleID: String, title: String)
        /// 0: ana ekran.
        case display(CGDirectDisplayID)
    }

    static let fps: Int32 = 20
    /// Görüntü bu genişlikten büyükse küçültülür (yazı tanıma için 1080p yeter).
    static let maxWidth = 1920.0
    /// Ekran değişmezse ScreenCaptureKit yeni kare göndermez; okuma hattı aynı kareyi birkaç kez görmeli (altyazının
    /// kararlı olduğunu böyle anlar). Son kare bu aralıkla yeniden verilir.
    static let repeatEvery = 0.2

    let target: Target
    let label: String
    private let text: SourceText
    private let ownBundleID = Bundle.main.bundleIdentifier
    private let queue = DispatchQueue(label: "gt.screen-capture", qos: .userInitiated)
    private let lock = NSLock()
    private weak var engine: Engine?
    private var stream: SCStream?
    private var lastImage: CGImage?
    private var lastSubmit: TimeInterval = 0
    private var timer: DispatchSourceTimer?
    private var stopped = false
    private var _contentFrame: CGRect?

    /// Okunan pencerenin ekrandaki yeri (Cocoa koordinatları, sol alt köşe); ekranın tamamında o ekranın çerçevesi.
    /// Mac ekranındaki katman "altyazıyı ört" yerleşiminde kutuları buna göre koyar. Başlarken bir kez ölçülür.
    var contentFrame: CGRect? { lock.withLock { _contentFrame } }

    /// Okunan pencerenin kimliği (pencere kaynağında); pencere taşınınca yeri buradan yeniden ölçülür.
    private var windowID: CGWindowID?

    /// Pencerenin şimdiki yeri (Cocoa koordinatları); pencere kaynağı değilse ya da pencere kapandıysa son bilinen yer.
    /// Mac ekranındaki katman pencere taşındıkça bununla yerini günceller.
    func currentContentFrame() -> CGRect? {
        guard let id = lock.withLock({ windowID }) else { return contentFrame }
        guard let info = (CGWindowListCopyWindowInfo([.optionIncludingWindow], id) as? [[String: Any]])?.first,
              let bounds = info[kCGWindowBounds as String] as? [String: Any],
              let rect = CGRect(dictionaryRepresentation: bounds as CFDictionary) else { return contentFrame }
        let frame = Self.cocoaRect(rect)
        lock.withLock { _contentFrame = frame }
        return frame
    }

    @MainActor init(target: Target) {
        self.target = target
        switch target {
        case .window(let bundleID, let title):
            label = title.isEmpty ? (bundleID.isEmpty ? L("Pencere", "Window") : bundleID) : title
        case .display:
            label = L("Ekranın tamamı", "Whole display")
        }
        text = .current
        super.init()
    }

    func start(into engine: Engine) {
        self.engine = engine
        Task { await self.run() }
    }

    private func run() async {
        guard let engine else { return }
        // İzin yoksa macOS izni sorar (ilk sefer); izin verilse de uygulama yeniden başlatılmadan etkili olmaz.
        guard CGPreflightScreenCaptureAccess() || CGRequestScreenCaptureAccess() else { return engine.sourceFailed(text.screenDenied) }
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: true)
            let filter: SCContentFilter
            let frame: CGRect
            switch target {
            case .window(let bundleID, let title):
                guard let window = Self.find(content.windows, bundleID: bundleID, title: title) else { return engine.sourceFailed(text.noWindow) }
                filter = SCContentFilter(desktopIndependentWindow: window)
                frame = window.frame
                lock.withLock { windowID = window.windowID }
            case .display(let id):
                let wanted = id == 0 ? CGMainDisplayID() : id
                guard let display = content.displays.first(where: { $0.displayID == wanted }) ?? (id == 0 ? content.displays.first : nil) else {
                    return engine.sourceFailed(text.noDisplay)
                }
                // Kendi pencerelerimiz (altyazı katmanı, ana pencere) okunmaz; katman ayrıca sharingType = .none'dır.
                let own = content.applications.filter { $0.bundleIdentifier == ownBundleID }
                filter = SCContentFilter(display: display, excludingApplications: own, exceptingWindows: [])
                frame = display.frame
            }
            lock.withLock { _contentFrame = Self.cocoaRect(frame) }
            let config = SCStreamConfiguration()
            let scale = Double(filter.pointPixelScale)
            let pixels = CGSize(width: filter.contentRect.width * scale, height: filter.contentRect.height * scale)
            let shrink = min(1, Self.maxWidth / max(1, pixels.width))
            config.width = max(2, Int(pixels.width * shrink))
            config.height = max(2, Int(pixels.height * shrink))
            config.minimumFrameInterval = CMTime(value: 1, timescale: Self.fps)
            config.pixelFormat = kCVPixelFormatType_32BGRA
            config.showsCursor = false
            config.queueDepth = 4
            let stream = SCStream(filter: filter, configuration: config, delegate: self)
            try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
            guard lock.withLock({ !stopped }) else { return }
            try await stream.startCapture()
            lock.withLock { self.stream = stream }
            if lock.withLock({ stopped }) { try? await stream.stopCapture() } else { startRepeating() }
        } catch {
            engine.sourceFailed(describe(error))
        }
    }

    /// Ayarlardaki pencere seçimi için: başka uygulamaların ekrandaki pencereleri (izin yoksa boş).
    struct WindowChoice: Hashable, Sendable {
        var bundleID: String
        var title: String
        var appName: String
    }

    static func windowChoices() async -> [WindowChoice] {
        guard CGPreflightScreenCaptureAccess(),
              let content = try? await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: true) else { return [] }
        let own = Bundle.main.bundleIdentifier
        return content.windows.compactMap { window in
            guard let app = window.owningApplication, app.bundleIdentifier != own, window.windowLayer == 0,
                  window.frame.width > 64, window.frame.height > 64 else { return nil }
            return WindowChoice(bundleID: app.bundleIdentifier, title: window.title ?? "", appName: app.applicationName)
        }
    }

    /// Paket kimliği tutan pencereler arasından başlığı tutan, yoksa en büyüğü.
    static func find(_ windows: [SCWindow], bundleID: String, title: String) -> SCWindow? {
        let candidates = windows.filter { $0.owningApplication?.bundleIdentifier == bundleID && $0.frame.width > 64 && $0.frame.height > 64 }
        if !title.isEmpty, let exact = candidates.first(where: { $0.title == title }) { return exact }
        return candidates.max { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }
    }

    /// ScreenCaptureKit'in sol üst köşeli genel koordinatlarından Cocoa'nın sol alt köşeli koordinatlarına.
    static func cocoaRect(_ rect: CGRect) -> CGRect {
        let mainHeight = CGDisplayBounds(CGMainDisplayID()).height
        return CGRect(x: rect.minX, y: mainHeight - rect.maxY, width: rect.width, height: rect.height)
    }

    private func describe(_ error: Error) -> String {
        if let error = error as? SCStreamError, error.code == .userDeclined { return text.screenDenied }
        let ns = error as NSError
        // İzin kapalıyken içerik listesi -3801 (userDeclined) ile döner.
        if ns.domain == SCStreamErrorDomain, ns.code == SCStreamError.Code.userDeclined.rawValue { return text.screenDenied }
        return "\(text.captureFailed): \(error.localizedDescription)"
    }

    private func startRepeating() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + Self.repeatEvery, repeating: Self.repeatEvery)
        timer.setEventHandler { [weak self] in
            guard let self, let image = self.lastImage,
                  ProcessInfo.processInfo.systemUptime - self.lastSubmit >= Self.repeatEvery * 0.9 else { return }
            self.submit(image)
        }
        timer.resume()
        lock.withLock { self.timer = timer }
    }

    /// Kuyrukta.
    private func submit(_ image: CGImage) {
        lastImage = image
        lastSubmit = ProcessInfo.processInfo.systemUptime
        engine?.submit(frame: image)
    }

    func stop() {
        let (stream, timer) = lock.withLock { () -> (SCStream?, DispatchSourceTimer?) in
            stopped = true
            defer { self.stream = nil; self.timer = nil }
            return (self.stream, self.timer)
        }
        timer?.cancel()
        queue.async { self.engine = nil; self.lastImage = nil }
        if let stream { Task { try? await stream.stopCapture() } }
    }

    // MARK: SCStreamOutput, SCStreamDelegate

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = attachments.first?[.status] as? Int, SCFrameStatus(rawValue: raw) == .complete,
              let buffer = sampleBuffer.imageBuffer, let image = PixelBufferImage.make(buffer) else { return }
        submit(image)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        guard !lock.withLock({ stopped }) else { return }
        engine?.sourceFailed(describe(error))
    }
}
