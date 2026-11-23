// SPDX-License-Identifier: AGPL-3.0-only

import AppKit
import AVFoundation
import CoreGraphics
import ScreenCaptureKit
import SwiftUI

/// Görüntünün nereden alınacağı: konsol (PS5/Xbox), yakalama kartı, bir pencere ya da bir ekran. Burada yalnız tercih
/// seçilir; yakalamanın kendisi Sources katmanındadır.
struct VideoSourceCard: View {
    @Environment(MacModel.self) private var model

    @State private var devices: [(id: String, name: String)] = []
    @State private var windows: [WindowChoice] = []
    @State private var windowsError: String?
    @State private var loadingWindows = false
    @State private var screens: [(id: UInt32, name: String)] = []

    struct WindowChoice: Hashable {
        let bundleID: String
        let title: String
        let app: String
        var tag: String { "\(bundleID)\u{1f}\(title)" }
    }

    private func label(_ source: MacPreferences.VideoSource) -> String {
        switch source {
        case .console: model.isXbox ? "Xbox" : "PS5"
        case .captureCard: L("Yakalama kartı", "Capture card")
        case .window: L("Pencere", "Window")
        case .display: L("Ekran", "Display")
        }
    }

    var body: some View {
        @Bindable var preferences = model.preferences
        TitledCard(title: L("Görüntü kaynağı", "Video source"), symbol: "rectangle.on.rectangle",
                   subtitle: L("Altyazının okunacağı görüntü nereden gelsin?", "Where should the picture with the subtitles come from?")) {
            Picker("", selection: $preferences.videoSource) {
                ForEach(MacPreferences.VideoSource.allCases, id: \.self) { Text(verbatim: label($0)).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            switch preferences.videoSource {
            case .console:
                HintText(text: model.isXbox
                    ? L("Görüntü Xbox'ın ev yayınından alınır (Beta). Konsol ayarları aşağıda.", "The picture comes from the Xbox home stream (Beta). Console settings are below.")
                    : L("Görüntü PS5'ten Remote Play ile alınır; kamera ya da kart gerekmez. Konsol ayarları aşağıda.", "The picture comes from the PS5 over Remote Play; no camera or card is needed. Console settings are below."))
            case .captureCard: captureCard
            case .window: windowPicker
            case .display: displayPicker
            }
        }
        .onAppear(perform: refreshDevices)
        .task(id: preferences.videoSource) {
            if preferences.videoSource == .window { await loadWindows() }
            if preferences.videoSource == .display { loadScreens() }
            if preferences.videoSource == .captureCard { refreshDevices() }
        }
    }

    // MARK: - Yakalama kartı

    private var captureCard: some View {
        @Bindable var preferences = model.preferences
        return VStack(alignment: .leading, spacing: Theme.space("s")) {
            if devices.isEmpty {
                NoticeBox(text: L("Bağlı bir yakalama kartı bulunamadı. Kartı USB ile takıp \"Yenile\"ye basın. Konsolun HDMI çıkışı karta, kartın çıkışı (varsa) TV'ye bağlı olsun.",
                                  "No capture card was found. Plug the card in over USB and press \"Refresh\". Connect the console's HDMI to the card and, if it has one, the card's output to the TV."),
                          symbol: "cable.connector", tone: .csSecondary)
            } else {
                Picker(L("Kart", "Card"), selection: $preferences.captureDeviceID) {
                    Text(L("İlk bulunan", "First one found")).tag("")
                    ForEach(devices, id: \.id) { Text(verbatim: $0.name).tag($0.id) }
                }
                .frame(maxWidth: 420)
            }
            HStack {
                Button(L("Yenile", "Refresh"), action: refreshDevices).buttonStyle(.link)
                Spacer()
            }
            HintText(text: L("Kart kullanırken kamera izni istenir (Sistem Ayarları › Gizlilik ve Güvenlik › Kamera).",
                             "Using a card asks for camera permission (System Settings › Privacy & Security › Camera)."))
        }
    }

    private func refreshDevices() {
        let session = AVCaptureDevice.DiscoverySession(deviceTypes: [.external], mediaType: nil, position: .unspecified)
        devices = session.devices
            .filter { $0.hasMediaType(.video) || $0.hasMediaType(.muxed) }
            .map { ($0.uniqueID, $0.localizedName) }
    }

    // MARK: - Pencere

    private var windowSelection: Binding<String> {
        Binding(get: {
            let preferences = model.preferences
            return preferences.windowBundleID.isEmpty ? "" : "\(preferences.windowBundleID)\u{1f}\(preferences.windowTitle)"
        }, set: { tag in
            let parts = tag.split(separator: "\u{1f}", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            model.preferences.windowBundleID = parts.first ?? ""
            model.preferences.windowTitle = parts.count > 1 ? parts[1] : ""
        })
    }

    private var windowPicker: some View {
        let preferences = model.preferences
        let current = WindowChoice(bundleID: preferences.windowBundleID, title: preferences.windowTitle, app: preferences.windowBundleID)
        return VStack(alignment: .leading, spacing: Theme.space("s")) {
            if let windowsError {
                NoticeBox(text: windowsError)
                HStack {
                    Button(L("İzin iste", "Request permission")) {
                        _ = CGRequestScreenCaptureAccess()
                        Task { await loadWindows() }
                    }
                    .buttonStyle(.secondary)
                    Button(L("Sistem Ayarları'nı aç", "Open System Settings")) { openScreenRecordingSettings() }.buttonStyle(.link)
                }
            } else if windows.isEmpty && !loadingWindows {
                HintText(text: L("Açık bir pencere bulunamadı. Oyunun ya da yayın uygulamasının penceresini açıp \"Yenile\"ye basın.",
                                 "No open window was found. Open the game's or the streaming app's window and press \"Refresh\"."))
            } else {
                Picker(L("Pencere", "Window"), selection: windowSelection) {
                    Text(L("Seçilmedi", "None")).tag("")
                    if !preferences.windowBundleID.isEmpty && !windows.contains(current) {
                        Text(verbatim: "\(preferences.windowTitle) " + L("(şu an kapalı)", "(not open now)")).tag(current.tag)
                    }
                    ForEach(windows, id: \.self) { window in
                        Text(verbatim: window.title.isEmpty ? window.app : "\(window.app) — \(window.title)").tag(window.tag)
                    }
                }
                .frame(maxWidth: 520)
            }
            HStack {
                if loadingWindows { ProgressView().controlSize(.small) }
                Button(L("Yenile", "Refresh")) { Task { await loadWindows() } }.buttonStyle(.link).disabled(loadingWindows)
            }
            HintText(text: L("Pencere uygulamaya ve başlığına göre hatırlanır; uygulama yeniden açılınca da bulunur.",
                             "The window is remembered by its app and title, so it's found again after the app reopens."))
        }
    }

    private func loadWindows() async {
        guard CGPreflightScreenCaptureAccess() else {
            windowsError = L("Pencereleri görmek için Ekran ve Sistem Sesi Kaydı izni gerekli (Sistem Ayarları › Gizlilik ve Güvenlik).",
                             "Screen & System Audio Recording permission is needed to see windows (System Settings › Privacy & Security).")
            windows = []
            return
        }
        loadingWindows = true
        defer { loadingWindows = false }
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: true)
            let own = Bundle.main.bundleIdentifier
            var seen = Set<WindowChoice>()
            windows = content.windows.compactMap { window -> WindowChoice? in
                guard window.windowLayer == 0, window.frame.width >= 200, window.frame.height >= 150,
                      let app = window.owningApplication, app.bundleIdentifier != own, !app.bundleIdentifier.isEmpty else { return nil }
                let choice = WindowChoice(bundleID: app.bundleIdentifier, title: window.title ?? "", app: app.applicationName)
                return seen.insert(choice).inserted ? choice : nil
            }
            .sorted { ($0.app, $0.title) < ($1.app, $1.title) }
            windowsError = nil
        } catch {
            windowsError = L("Pencere listesi alınamadı: \(error.localizedDescription)", "Couldn't get the window list: \(error.localizedDescription)")
        }
    }

    private func openScreenRecordingSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") { NSWorkspace.shared.open(url) }
    }

    // MARK: - Ekran

    private var displayPicker: some View {
        @Bindable var preferences = model.preferences
        return VStack(alignment: .leading, spacing: Theme.space("s")) {
            Picker(L("Ekran", "Display"), selection: $preferences.displayID) {
                Text(L("Ana ekran", "Main display")).tag(UInt32(0))
                ForEach(screens, id: \.id) { Text(verbatim: $0.name).tag($0.id) }
            }
            .frame(maxWidth: 420)
            if !CGPreflightScreenCaptureAccess() {
                NoticeBox(text: L("Ekranı okumak için Ekran ve Sistem Sesi Kaydı izni gerekli.", "Screen & System Audio Recording permission is needed to read the display."))
                Button(L("İzin iste", "Request permission")) { _ = CGRequestScreenCaptureAccess() }.buttonStyle(.secondary)
            }
            HintText(text: L("Ekranın tamamı alınır; okunacak bölge Okuma Alanı'nda seçilir.", "The whole display is captured; the area to read is chosen in Reading Area."))
        }
    }

    private func loadScreens() {
        screens = NSScreen.screens.compactMap { screen in
            guard let number = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber else { return nil }
            let size = screen.frame.size
            return (number.uint32Value, "\(screen.localizedName) (\(Int(size.width))×\(Int(size.height)))")
        }
    }
}
