// SPDX-License-Identifier: AGPL-3.0-only

import GTNative
import SwiftUI

/// Diller ve ses (Windows: MainView "Diller ve Ses"): oyunun dili, çeviri dili, uygulama dili ve Türkçe dublaj.
struct LanguagesView: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        @Bindable var model = model
        @Bindable var preferences = model.preferences
        SettingsPage(title: L("Diller ve Ses", "Languages and Sound"),
                     subtitle: L("Oyunun dili, çevirinin yapılacağı dil ve bu uygulamanın dili.",
                                 "The language the game is in, the language to translate into and the language of this app.")) {
            SettingsCard(title: L("Diller", "Languages")) {
                SettingRow(title: L("Oyunun dili", "Game language"),
                           help: L("Oyundaki altyazıların dili.", "The language of the subtitles in your game.")) {
                    Picker("", selection: $model.settings.sourceLanguage) {
                        ForEach(Languages.sources, id: \.self) { code in Text(verbatim: Self.name(code)).tag(code) }
                    }
                    .labelsHidden()
                    .frame(width: 240)
                }
                SettingRow(title: L("Çeviri dili", "Translate into"),
                           help: L("Altyazının TV'de görüneceği dil.", "The language the subtitles appear in on the TV.")) {
                    Picker("", selection: $model.settings.targetLanguage) {
                        ForEach(Languages.targets, id: \.code) { language in
                            Text(verbatim: Self.label(language)).tag(language.code)
                        }
                    }
                    .labelsHidden()
                    .frame(width: 240)
                }
                if model.settings.sourceLanguage == model.settings.targetLanguage {
                    NoticeText(text: L("Oyunun dili ile çeviri dili aynı (\(Self.name(model.settings.targetLanguage))). Oyunun dilini, oyundaki altyazıların diline getir.",
                                       "The game language and the translation language are both \(Self.name(model.settings.targetLanguage)). Set the game language to the language of the subtitles in your game."))
                }
                Divider()
                SettingRow(title: L("Uygulama dili", "App language"),
                           help: L("Bu uygulamanın menü ve ekranlarının dili.", "The language of this app's menus and screens.")) {
                    Picker("", selection: $model.settings.uiLanguage) {
                        Text(verbatim: L("Otomatik (Mac'in dili)", "Automatic (the Mac's language)")).tag("auto")
                        Text(verbatim: "Türkçe").tag("tr")
                        Text(verbatim: "English").tag("en")
                    }
                    .labelsHidden()
                    .frame(width: 240)
                }
            }

            SettingsCard(title: L("Türkçe dublaj", "Turkish dubbing"), badge: L("DENEYSEL", "EXPERIMENTAL"),
                         footer: L("Ses modeli ilk kullanımda bir kez indirilir (yaklaşık 440 MB); inene kadar dublaj başlamaz. Seslendirme bu Mac'te yapılır, internete gitmez.",
                                   "The voice model is downloaded once on first use (about 440 MB); dubbing starts once it is ready. The voice is made on this Mac and nothing is sent to the internet.")) {
                ToggleRow(title: L("Çeviriyi Türkçe seslendir", "Voice the translation in Turkish"),
                          help: L("Her altyazı çevrildikçe Türkçe okunur.", "Each subtitle is read aloud in Turkish as it is translated."),
                          isOn: $preferences.dubbingEnabled)
                ChoiceRow(title: L("Ses nereden çalsın", "Play the voice on"), selection: $preferences.dubbingOutputTV, options: [
                    (true, "TV"), (false, L("Bu Mac", "This Mac")),
                ])
                .disabled(!preferences.dubbingEnabled)
                if preferences.dubbingEnabled && model.settings.targetLanguage != "tr" {
                    NoticeText(text: L("Dublaj yalnız Türkçe çeviride çalışır; çeviri dilini Türkçe yap.",
                                       "Dubbing works only when translating into Turkish; set the translation language to Turkish."))
                }
            }
        }
    }

    /// Dilin arayüz dilindeki adı.
    static func name(_ code: String) -> String {
        guard let language = Languages.find(code) else { return code }
        return L(language.turkishName, language.englishName)
    }

    /// Kendi adı ve arayüz dilindeki adı ("Deutsch · Almanca").
    static func label(_ language: TargetLanguage) -> String {
        let local = L(language.turkishName, language.englishName)
        return local == language.nativeName ? local : "\(language.nativeName) · \(local)"
    }
}
