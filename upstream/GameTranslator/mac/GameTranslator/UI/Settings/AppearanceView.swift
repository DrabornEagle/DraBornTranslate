// SPDX-License-Identifier: AGPL-3.0-only

import AppKit
import GameTranslatorCore
import GTNative
import SwiftUI

/// Altyazı görünümü (Windows: MainView "Altyazı Görünümü"): TV, OBS / ikinci ekran ve Mac ekranındaki katmanın
/// ayarları ayrıdır.
struct AppearanceView: View {
    @Environment(MacModel.self) private var model
    @State private var target: Target = .tv
    @State private var testSent = false

    enum Target: String, CaseIterable, Identifiable {
        case tv, obs, mac
        var id: String { rawValue }
    }

    var body: some View {
        SettingsPage(title: L("Altyazı Görünümü", "Subtitle Appearance"),
                     subtitle: L("TV, OBS ve Mac ekranının ayarları ayrıdır.", "The TV, OBS and the Mac screen each have their own settings.")) {
            HStack(spacing: Theme.space("m")) {
                Picker("", selection: $target) {
                    Text(verbatim: "TV").tag(Target.tv)
                    Text(verbatim: L("OBS ve ikinci ekran", "OBS and second screen")).tag(Target.obs)
                    Text(verbatim: L("Mac ekranı", "Mac screen")).tag(Target.mac)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(maxWidth: 460)
                Spacer()
                if testSent { DoneLabel(text: L("Gönderildi", "Sent")) }
                Button(L("Deneme altyazısı gönder", "Send a test subtitle")) {
                    model.sendTestSubtitle()
                    testSent = true
                    Task { try? await Task.sleep(for: .seconds(3)); testSent = false }
                }
                .buttonStyle(.secondary)
                .help(L("TV'ye, OBS'e ve açıksa Mac ekranındaki katmana bir deneme altyazısı gönderir.",
                        "Sends a test subtitle to the TV, OBS and, if it is on, the bar on the Mac screen."))
            }
            switch target {
            case .tv: tvGroup
            case .obs: obsGroup
            case .mac: MacOverlaySettings()
            }
        }
    }

    private var tvGroup: some View {
        @Bindable var model = model
        return StyleEditor(style: $model.settings.style, showsDisplayMode: true)
    }

    private var obsGroup: some View {
        @Bindable var preferences = model.preferences
        let same = Binding(get: { !preferences.obsSeparateStyle }, set: { same in
            // İlk kez ayrılınca OBS, TV'nin görünümüyle başlar.
            if !same && preferences.obsStyle == StyleSettings() { preferences.obsStyle = model.settings.style }
            preferences.obsSeparateStyle = !same
        })
        return VStack(alignment: .leading, spacing: Theme.space("l")) {
            SettingsCard {
                ToggleRow(title: L("TV ile aynı", "Same as the TV"),
                          help: L("Açıkken OBS'teki ve ikinci ekrandaki altyazı TV'dekinin aynısıdır. Kapatınca OBS'e ayrı görünüm verebilirsin; TV etkilenmez.",
                                  "When on, OBS and the second screen show the subtitle exactly like the TV. Turn it off to give OBS its own look; the TV is not affected."),
                          isOn: same)
            }
            if preferences.obsSeparateStyle {
                StyleEditor(style: $preferences.obsStyle, showsDisplayMode: false)
            }
        }
    }
}

/// TV ya da OBS için altyazı görünümü düzenleyicisi (aynı denetimler).
struct StyleEditor: View {
    @Binding var style: StyleSettings
    /// Android TV'nin görüntü biçimi yalnız TV'de anlamlıdır.
    let showsDisplayMode: Bool

    private static let sizes = [36, 46, 58, 72]
    private static let placements = ["cover", "above", "below", "fixed"]

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.space("l")) {
            SubtitlePreview(style: style)
            SettingsCard(title: L("Yazı", "Text")) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(verbatim: L("Yazı boyutu", "Text size")).font(.csBody.weight(.medium))
                    Picker("", selection: $style.fontSize) {
                        Text(verbatim: L("Küçük", "Small")).tag(36)
                        Text(verbatim: L("Orta", "Medium")).tag(46)
                        Text(verbatim: L("Büyük", "Large")).tag(58)
                        Text(verbatim: L("Dev", "Huge")).tag(72)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
                SliderRow(title: L("İnce ayar", "Fine tune"), value: $style.fontSize, range: 24...72, format: { "\($0) px" })
                VStack(alignment: .leading, spacing: 6) {
                    Text(verbatim: L("Yazı rengi", "Text color")).font(.csBody.weight(.medium))
                    ColorSwatches(selection: $style.textColor)
                }
                SettingRow(title: L("Yazı tipi", "Font"),
                           help: L("Quicksand, GTA 6 altyazılarına en yakın açık lisanslı yazı tipidir ve uygulamayla gelir.",
                                   "Quicksand is the open-licensed font closest to the GTA 6 subtitles and comes with the app.")) {
                    Picker("", selection: $style.font) {
                        ForEach(fontOptions, id: \.key) { option in Text(verbatim: option.label).tag(option.key) }
                    }
                    .labelsHidden()
                    .frame(width: 220)
                }
                ToggleRow(title: L("Kalın yazı", "Bold text"), isOn: $style.bold)
                ToggleRow(title: L("Konuşmacı adı renkli", "Speaker name in color"),
                          help: L("Her konuşmacı kendi renginde (ör. MARKOS:)", "Each speaker in their own color (e.g. MARKOS:)"),
                          isOn: $style.speakerColor)
            }
            SettingsCard(title: L("Yeri ve zemini", "Position and background")) {
                SettingRow(title: L("Altyazının yeri", "Subtitle position"), help: placementHelp) {
                    Picker("", selection: $style.placement) {
                        ForEach(Self.placements, id: \.self) { key in Text(verbatim: Self.placementName(key)).tag(key) }
                    }
                    .labelsHidden()
                    .frame(width: 260)
                }
                if style.placement == "fixed" {
                    SliderRow(title: L("Ekran konumu (alttan yükseklik)", "Screen position (height from the bottom)"),
                              value: $style.bottomPercent, range: 2...30, format: { "%\($0)" })
                }
                if style.placement != "cover" {
                    ToggleRow(title: L("Önceki satır soluk kalsın", "Keep the previous line faded"),
                              help: L("Hızlı konuşmada önceki satır yenisinin üstünde kısa süre görünür.",
                                      "In fast dialogue the previous line stays above the new one for a moment."),
                              isOn: $style.stackPrevious)
                }
                SliderRow(title: L("Zemin koyuluğu", "Background darkness"),
                          help: L("Yazının arkasındaki siyah zemin. 0: zemin yok.", "The black background behind the text. 0: no background."),
                          value: $style.background, range: 0...100, step: 5, format: { "%\($0)" })
            }
            if showsDisplayMode {
                SettingsCard(title: "Android TV",
                             footer: L("Android TV uygulamasının içinde görüntü siyah kalıyorsa ya da PS5 açılınca TV kendiliğinden PS5'in girişine geçiyorsa Katman'ı seç: TV, PS5'i kendi HDMI girişinde gösterir, altyazı görüntünün üstünde çıkar. Görüntü gelmezse TV uygulaması kendiliğinden geçer. LG ve Samsung TV'lerde etkisi yoktur.",
                                       "If the picture stays black inside the Android TV app, or the TV jumps to the PS5's input when the PS5 turns on, choose Overlay: the TV shows the PS5 on its own HDMI input and the subtitles appear on top. If no picture arrives, the TV app switches by itself. LG and Samsung TVs ignore this setting.")) {
                    ChoiceRow(title: L("Görüntü", "Picture"), selection: $style.displayMode, options: [
                        ("inApp", L("Uygulama içinde", "Inside the app")), ("overlay", L("Katman (TV kendi gösterir)", "Overlay (the TV shows it)")),
                    ])
                }
            }
        }
    }

    private var fontOptions: [(key: String, label: String)] {
        var options: [(key: String, label: String)] = [("", L("Standart", "Standard")), (SubtitleFonts.quicksand, "Quicksand (GTA 6)")]
        // Bilgisayarda seçilmiş başka bir font (Pricedown ya da kurulu bir font) seçici boş görünmesin.
        let current = style.font
        if !current.isEmpty && current != SubtitleFonts.quicksand {
            options.append((current, SubtitleFonts.family(current) ?? current))
        }
        return options
    }

    static func placementName(_ key: String) -> String {
        switch key {
        case "above": L("İngilizce altyazının üstünde", "Above the English subtitle")
        case "below": L("İngilizce altyazının altında", "Below the English subtitle")
        case "fixed": L("Ekranda sabit yükseklikte", "At a fixed height on the screen")
        default: L("İngilizce altyazının üstünü örtsün", "Over the English subtitle")
        }
    }

    private var placementHelp: String {
        switch style.placement {
        case "above", "below": L("Oyunun kendi altyazısı da görünür kalır.", "The game's own subtitle stays visible too.")
        case "fixed": L("Oyunun altyazısının yerinden bağımsız, hep aynı yükseklikte.", "Always at the same height, wherever the game puts its subtitle.")
        default: L("Çeviri, oyunun altyazısını kapatarak tam onun yerinde görünür.", "The translation covers the game's subtitle, right where it was.")
        }
    }
}

/// Seçilen görünümün küçük önizlemesi (oyun görüntüsü yerine koyu zemin).
struct SubtitlePreview: View {
    let style: StyleSettings

    var body: some View {
        GeometryReader { geometry in
            let scale = geometry.size.width / 1280
            let text = Text(verbatim: L("Bu bir deneme altyazısıdır.", "This is a test subtitle."))
            ZStack(alignment: .bottom) {
                LinearGradient(colors: [Color(red: 0.16, green: 0.2, blue: 0.27), Color(red: 0.05, green: 0.06, blue: 0.09)],
                               startPoint: .top, endPoint: .bottom)
                VStack(spacing: 4 * scale) {
                    if style.placement != "cover" && style.stackPrevious {
                        styled(Text(verbatim: L("Önceki satır.", "The previous line.")), scale: scale).opacity(0.45)
                    }
                    styled(text, scale: scale)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, bottomPadding(height: geometry.size.height))
            }
        }
        .aspectRatio(16 / 6, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: Theme.radius("card")))
        .overlay(RoundedRectangle(cornerRadius: Theme.radius("card")).strokeBorder(Color.csBorder))
        .accessibilityLabel(L("Görünümün önizlemesi", "Preview of the look"))
    }

    private func styled(_ text: Text, scale: CGFloat) -> some View {
        let size = max(10, CGFloat(style.fontSize) * scale)
        let family = SubtitleFonts.family(style.font)
        let font: Font = family.map { Font.custom($0, size: size) } ?? .system(size: size)
        return text
            .font(font.weight(style.bold ? .bold : .medium))
            .foregroundStyle(SubtitleColors.color(style.textColor))
            .multilineTextAlignment(.center)
            .padding(.horizontal, 10 * max(scale, 0.5)).padding(.vertical, 4 * max(scale, 0.5))
            .background(Color.black.opacity(Double(style.background) / 100), in: RoundedRectangle(cornerRadius: 4))
    }

    private func bottomPadding(height: CGFloat) -> CGFloat {
        // Önizleme 16:6; TV'deki yükseklik oranını kabaca korur.
        style.placement == "fixed" ? height * CGFloat(style.bottomPercent) / 100 * 2 : height * 0.14
    }
}

/// Mac ekranındaki altyazı katmanının tercihleri (Windows: DesktopSubtitleOverlay). Katmanın kendisi ayrı yerde kurulur.
struct MacOverlaySettings: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        @Bindable var preferences = model.preferences
        VStack(alignment: .leading, spacing: Theme.space("l")) {
            SettingsCard {
                ToggleRow(title: L("Mac ekranında altyazı", "Subtitles on the Mac screen"),
                          help: L("Çeviri bu Mac'te oyunun üstünde saydam bir barda görünür. TV'deki altyazıyı etkilemez.",
                                  "The translation appears in a transparent bar over the game on this Mac. It does not affect the subtitles on the TV."),
                          isOn: $preferences.overlayEnabled)
            }
            SettingsCard(title: L("Mac altyazı barı", "Mac subtitle bar")) {
                SettingRow(title: L("Ekran", "Display"),
                           help: L("Katmanın gösterileceği ekran.", "The display the bar appears on.")) {
                    Picker("", selection: $preferences.overlayDisplayID) {
                        Text(verbatim: L("Ana ekran", "Main display")).tag(UInt32(0))
                        ForEach(Self.screens, id: \.id) { screen in Text(verbatim: screen.name).tag(screen.id) }
                    }
                    .labelsHidden()
                    .frame(width: 240)
                }
                ChoiceRow(title: L("Yeri", "Position"),
                          help: preferences.overlayPlacement == .cover
                            ? L("Çeviri oyundaki altyazının üstünü örter (okunan alanın yerinde).", "The translation covers the game's subtitle (where the reading area is).")
                            : L("Çeviri ekranın altında, sabit yükseklikte durur.", "The translation stays at a fixed height at the bottom of the screen."),
                          selection: $preferences.overlayPlacement, options: [
                              (.cover, L("Oyunun altyazısını örtsün", "Cover the game's subtitle")),
                              (.fixed, L("Ekranın altında sabit", "Fixed at the bottom")),
                          ])
                if preferences.overlayPlacement == .fixed {
                    SliderRow(title: L("Ekran konumu (alttan yükseklik)", "Screen position (height from the bottom)"),
                              value: $preferences.overlayBottomPercent, range: 2...30, format: { "%\($0)" })
                }
                SliderRow(title: L("Yazı boyutu (Mac)", "Text size (Mac)"), value: $preferences.overlayFontSize, range: 18...72, format: { "\($0) pt" })
                SliderRow(title: L("Zemin koyuluğu", "Background darkness"),
                          help: L("0: bar yok, yalnız gölgeli yazı.", "0: no bar, only text with a shadow."),
                          value: $preferences.overlayBackground, range: 0...100, step: 5, format: { "%\($0)" })
                ToggleRow(title: L("Ek alan kutularını da göster", "Also show the extra area boxes"),
                          help: L("Okuma Alanı'nda açtığın ek alanların çevirisi de Mac ekranında kendi yerinde görünür.",
                                  "The translation of the extra areas you turned on under Reading Area also shows in its place on the Mac screen."),
                          isOn: $preferences.overlayRegions)
            }
            .disabled(!preferences.overlayEnabled)
            .opacity(preferences.overlayEnabled ? 1 : 0.55)
        }
    }

    /// Bağlı ekranlar (CGDirectDisplayID ve adı).
    static var screens: [(id: UInt32, name: String)] {
        NSScreen.screens.compactMap { screen in
            guard let number = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber else { return nil }
            return (number.uint32Value, screen.localizedName)
        }
    }
}
