// SPDX-License-Identifier: AGPL-3.0-only

import Sparkle
import SwiftUI

@main
struct GameTranslatorApp: App {
    @State private var model = MacModel()
    @State private var navigation = Navigation()
    /// Sparkle: açılışta ve günde bir yeni sürüme bakar; testlerde başlamaz.
    private let updater: SPUStandardUpdaterController

    init() {
        updater = SPUStandardUpdaterController(
            startingUpdater: ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil,
            updaterDelegate: UpdateState.shared, userDriverDelegate: nil)
        UpdateState.shared.controller = updater
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(navigation)
                .appSurface()
                .frame(minWidth: 960, idealWidth: 1120, minHeight: 680, idealHeight: 760)
                .containerBackground(Color.csBackground, for: .window)
                .onAppear {
                    UILanguage.setting = model.settings.uiLanguage
                    #if DEBUG
                    // Yalnız hata ayıklama: uçtan uca denemeler için açılışta çeviriyi başlatır (-gt_start).
                    if CommandLine.arguments.contains("-gt_start") { model.start() }
                    #endif
                }
                .onChange(of: model.settings.uiLanguage) { _, value in UILanguage.setting = value }
        }
        .commands {
            CommandGroup(replacing: .appInfo) {
                Button(L("GameTranslator Hakkında", "About GameTranslator")) { navigation.showAbout = true }
                Button(L("Güncellemeleri Denetle…", "Check for Updates…")) { updater.checkForUpdates(nil) }
            }
            CommandGroup(replacing: .appSettings) {
                Button(L("Ayarlar", "Settings")) { navigation.section = .reading }
                    .keyboardShortcut(",")
                    .disabled(!model.ready)
            }
            CommandMenu(L("Çeviri", "Translation")) {
                Button(model.state.active ? L("Çeviriyi Durdur", "Stop Translation") : L("Çeviriyi Başlat", "Start Translation")) {
                    model.toggle()
                }
                .keyboardShortcut("t", modifiers: [.command, .shift])
                .disabled(!model.ready)
                Button(L("TV'ye Deneme Altyazısı Gönder", "Send Test Subtitle to TV")) { model.sendTestSubtitle() }
            }
        }
    }
}

/// Ana pencerenin bölümleri (Windows'taki MainView sekmeleriyle aynı sıra).
enum Section: String, CaseIterable, Identifiable {
    case home, reading, appearance, languages, translation, connection, history, guide

    var id: String { rawValue }

    @MainActor var title: String {
        switch self {
        case .home: L("Ana Sayfa", "Home")
        case .reading: L("Okuma Alanı", "Reading Area")
        case .appearance: L("Altyazı Görünümü", "Subtitle Appearance")
        case .languages: L("Diller ve Ses", "Languages & Sound")
        case .translation: L("Çeviri ve Paketler", "Translation & Packs")
        case .connection: L("Bağlantı", "Connection")
        case .history: L("Altyazı Geçmişi", "Subtitle History")
        case .guide: L("Rehber", "Guide")
        }
    }

    var symbol: String {
        switch self {
        case .home: "house"
        case .reading: "viewfinder"
        case .appearance: "textformat.size"
        case .languages: "globe"
        case .translation: "character.book.closed"
        case .connection: "antenna.radiowaves.left.and.right"
        case .history: "clock.arrow.circlepath"
        case .guide: "book"
        }
    }
}

@MainActor
@Observable
final class Navigation {
    var section: Section = .home
    var showAbout = false
    var showNotice = false
}

/// Sürüm duyurusu: ana pencere ilk açıldığında bir kez (kurulum sihirbazı bittikten sonra). Yeni duyuru için `id`
/// değişir. Yalnız çeviri dili Türkçe olana gösterilir: GTA 6 paketi Türkçedir (Windows: Services/ReleaseNotice).
enum ReleaseNotice {
    private static let id = "notice.2026-11-paket-sunucusu"

    static func shouldShow(targetLanguage: String) -> Bool {
        targetLanguage.lowercased() == "tr" && !UserDefaults.standard.bool(forKey: id)
    }

    static func markSeen() { UserDefaults.standard.set(true, forKey: id) }
}

struct RootView: View {
    @Environment(MacModel.self) private var model
    @Environment(Navigation.self) private var navigation

    var body: some View {
        @Bindable var navigation = navigation
        Group {
            if model.setupComplete {
                NavigationSplitView {
                    List(Section.allCases, selection: Binding(get: { navigation.section }, set: { navigation.section = $0 ?? .home })) { section in
                        Label(section.title, systemImage: section.symbol).tag(section)
                    }
                    .navigationSplitViewColumnWidth(min: 200, ideal: 220)
                } detail: {
                    SectionView(section: navigation.section)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
            } else {
                OnboardingView()
            }
        }
        .sheet(isPresented: $navigation.showAbout) { AboutView().appSurface() }
        .alert(L("GTA 6 artık tamamen Türkçe", "GTA 6 is now fully in Turkish"), isPresented: $navigation.showNotice) {
            Button(L("Harika!", "Great!")) {}
        } message: {
            Text(L("Oyun paketlerimiz artık kendi sunucumuzdan geliyor. GTA 6'nın bütün metinleri Türkçeye çevrildi; altyazılar paketten anında gelir.",
                   "Game packs now come from our own server. Every line of GTA 6 has been translated into Turkish, so subtitles appear instantly from the pack."))
        }
        .onAppear { offerNotice() }
        .onChange(of: model.setupComplete) { _, _ in offerNotice() }
    }

    /// Ana pencere açıksa ve duyuru görülmediyse bir kez gösterir (gösterilir gösterilmez görüldü sayılır).
    private func offerNotice() {
        guard model.setupComplete, ReleaseNotice.shouldShow(targetLanguage: model.settings.targetLanguage) else { return }
        ReleaseNotice.markSeen()
        navigation.showNotice = true
    }
}

/// Bölümün ekranı.
struct SectionView: View {
    let section: Section

    var body: some View {
        switch section {
        case .home: HomeView()
        case .reading: ReadingAreaView()
        case .appearance: AppearanceView()
        case .languages: LanguagesView()
        case .translation: TranslationView()
        case .connection: ConnectionView()
        case .history: HistoryView()
        case .guide: GuideView()
        }
    }
}
