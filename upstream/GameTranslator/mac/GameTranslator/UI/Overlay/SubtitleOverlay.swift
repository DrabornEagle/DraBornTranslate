// SPDX-License-Identifier: AGPL-3.0-only

import AppKit
import GameTranslatorCore
import SwiftUI

/// Mac ekranındaki altyazı katmanının paneli (Windows: DesktopSubtitleOverlay): saydam, kenarsız, tıklamaları geçiren,
/// bütün pencerelerin ve tam ekran oyunların üstünde, her masaüstünde. `sharingType = .none`: ekran kaydına ve bizim
/// ScreenCaptureKit kaynağımıza girmez (kaynak ayrıca kendi uygulamamızı dışarıda bırakır).
final class SubtitleOverlayPanel: NSPanel {
    init() {
        super.init(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        isOpaque = false
        backgroundColor = .clear
        hasShadow = false
        ignoresMouseEvents = true
        level = .screenSaver
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        sharingType = .none
        isReleasedWhenClosed = false
        hidesOnDeactivate = false
        isFloatingPanel = true
        animationBehavior = .none
    }

    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

/// Ek alanın Mac ekranındaki kutusu (TV'deki `region` kutusunun aynısı).
struct OverlayRegion: Equatable, Identifiable {
    var id: String
    var text: String
    var box: TextBox
    var lineHeight: Double?
}

/// Katmanın yerleşim hesabı (birim denemeleri için ayrı): kutular panelin içinde, sol üst köşeye göre noktalar.
enum OverlayLayout {
    /// "Altyazıyı ört": çeviri oyundaki altyazının kutusunu ortalar, en az onun boyunda ve panelin %40'ı genişliğinde.
    static func cover(_ box: TextBox, in size: CGSize) -> CGRect {
        let width = min(size.width * 0.9, max((box.right - box.left) * size.width, size.width * 0.4))
        let height = max(0, (box.bottom - box.top) * size.height)
        let center = CGPoint(x: (box.left + box.right) / 2 * size.width, y: (box.top + box.bottom) / 2 * size.height)
        return CGRect(x: center.x - width / 2, y: center.y - height / 2, width: width, height: height)
    }

    /// Ek alan kutusu: kutunun kendisi; yazı boyu satır yüksekliğinden (yoksa kutunun yüksekliğinden), en çok altyazınınki.
    static func region(_ region: OverlayRegion, in size: CGSize, maxFont: Double) -> (rect: CGRect, font: Double) {
        let rect = CGRect(x: region.box.left * size.width, y: region.box.top * size.height,
                          width: max(40, (region.box.right - region.box.left) * size.width),
                          height: max(0, (region.box.bottom - region.box.top) * size.height))
        let line = (region.lineHeight ?? (region.box.bottom - region.box.top)) * size.height
        return (rect, min(maxFont, max(12, line * 0.8)))
    }
}

/// Katmanın içeriği: altyazı (sabit ya da örten) ve isteğe bağlı ek alan kutuları.
struct SubtitleOverlayView: View {
    let model: MacModel

    var body: some View {
        let preferences = model.preferences
        let font = Double(preferences.overlayFontSize)
        let background = Double(preferences.overlayBackground) / 100
        GeometryReader { geometry in
            let size = geometry.size
            ZStack(alignment: .topLeading) {
                if preferences.overlayRegions {
                    ForEach(model.screenRegions) { region in
                        let placed = OverlayLayout.region(region, in: size, maxFont: font)
                        bubble(region.text, font: placed.font, background: background)
                            .frame(width: placed.rect.width)
                            .position(x: placed.rect.midX, y: placed.rect.midY)
                    }
                }
                if let text = model.screenSubtitle {
                    if preferences.overlayPlacement == .cover, let box = model.screenSubtitleBox {
                        let rect = OverlayLayout.cover(box, in: size)
                        bubble(text, font: font, background: background)
                            .frame(width: rect.width)
                            .frame(minHeight: rect.height)
                            .position(x: rect.midX, y: rect.midY)
                    } else {
                        VStack(spacing: 0) {
                            Spacer(minLength: 0)
                            bubble(text, font: font, background: background)
                                .frame(maxWidth: size.width * 0.8)
                            Color.clear.frame(height: size.height * Double(preferences.overlayBottomPercent) / 100)
                        }
                        .frame(width: size.width, height: size.height)
                    }
                }
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
        }
        .ignoresSafeArea()
    }

    private func bubble(_ text: String, font: Double, background: Double) -> some View {
        Text(text)
            .font(.system(size: font, weight: .semibold))
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .shadow(color: .black.opacity(background < 0.3 ? 0.9 : 0), radius: 2)
            .padding(.horizontal, font * 0.45)
            .padding(.vertical, font * 0.2)
            .background(RoundedRectangle(cornerRadius: font * 0.25).fill(Color.black.opacity(background)))
    }
}

/// Katmanı yönetir (MacModel'in): çeviri açıkken ve katman açıksa gösterir, ekranı ve yerleşimi değişince taşır.
@MainActor
final class OverlayController {
    private weak var model: MacModel?
    private var panel: SubtitleOverlayPanel?
    private var screensObserver: NSObjectProtocol?

    init(model: MacModel) {
        self.model = model
        screensObserver = NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification,
                                                                 object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
        observe()
    }

    private func observe() {
        withObservationTracking { refresh() } onChange: { [weak self] in
            Task { @MainActor in self?.observe() }
        }
    }

    /// Katmanın gösterileceği yer; gösterilmeyecekse nil.
    static func frame(enabled: Bool, active: Bool, placement: MacPreferences.OverlayPlacement, displayID: UInt32,
                      coverFrame: CGRect?, screens: [NSScreen] = NSScreen.screens) -> CGRect? {
        guard enabled, active else { return nil }
        if placement == .cover, let coverFrame, coverFrame.width > 0, coverFrame.height > 0 { return coverFrame }
        let screen = screens.first { $0.displayID == displayID } ?? NSScreen.main ?? screens.first
        return screen?.frame
    }

    private func refresh() {
        guard let model else { return }
        let preferences = model.preferences
        guard let frame = Self.frame(enabled: preferences.overlayEnabled, active: model.state.active,
                                     placement: preferences.overlayPlacement, displayID: preferences.overlayDisplayID,
                                     coverFrame: model.coverFrame) else {
            panel?.orderOut(nil)
            return
        }
        let panel = self.panel ?? makePanel(model)
        if panel.frame != frame { panel.setFrame(frame, display: true) }
        if !panel.isVisible { panel.orderFrontRegardless() }
    }

    private func makePanel(_ model: MacModel) -> SubtitleOverlayPanel {
        let panel = SubtitleOverlayPanel()
        let host = NSHostingView(rootView: SubtitleOverlayView(model: model))
        host.sizingOptions = []
        panel.contentView = host
        self.panel = panel
        return panel
    }

    func close() {
        panel?.orderOut(nil)
        panel = nil
    }
}

extension NSScreen {
    /// CGDirectDisplayID.
    var displayID: UInt32 { (deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value ?? 0 }
}
