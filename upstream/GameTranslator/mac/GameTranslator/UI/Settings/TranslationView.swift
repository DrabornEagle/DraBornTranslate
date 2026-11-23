// SPDX-License-Identifier: AGPL-3.0-only

import GTNative
import SwiftUI

/// Çeviri ve paketler (Windows: MainView "Çeviri ve Paketler"): çeviri motoru, DeepL anahtarı, oyun paketleri, ortak
/// havuz ve terim sözlüğü.
struct TranslationView: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        @Bindable var model = model
        SettingsPage(title: L("Çeviri ve Paketler", "Translation and Packs"),
                     subtitle: L("Paketteki satırlar hazır çevirisiyle gelir; pakette olmayanları seçtiğin çeviri motoru çevirir.",
                                 "Lines in a pack come with their ready translation; the engine you choose translates the rest.")) {
            engineCard
            SettingsCard(title: L("Topluluk oyun paketleri", "Community game packs"),
                         footer: L("Oyunun paketinde bulunan satır çeviri motoruna gitmeden paketteki Türkçesiyle, gecikmesiz gösterilir. Paketler topluluk çevirilerinden gelir ve haftada bir yenilenir.",
                                   "A line found in the game's pack is shown in the pack's Turkish right away, without going to the translation engine. Packs come from community translations and are refreshed once a week.")) {
                ToggleRow(title: L("Oyun paketlerini kullan", "Use game packs"),
                          help: L("GTA V, RDR2, Elden Ring… satırları hazır Türkçesiyle", "GTA V, RDR2, Elden Ring… lines shown in ready-made Turkish"),
                          isOn: $model.settings.packsEnabled)
                Divider()
                ToggleRow(title: L("Pakette olmayan satırları ortak havuza gönder", "Send lines that are not in a pack to the shared pool"),
                          help: L("Yeni oyun paketleri bunlardan hazırlanır. Yalnız oyundaki İngilizce satırın metni ve çalışan oyunun adı gider; adın, hesabın ya da IP adresin gitmez. Kapatınca bekleyen satırlar da silinir.",
                                  "New game packs are made from these. Only the text of the English line in the game and the name of the game running are sent; your name, account and IP address are not. Turning this off also deletes the lines waiting to be sent."),
                          isOn: $model.settings.sharePool)
                if model.settings.sourceLanguage != "en" {
                    HintText(text: L("Ortak havuz yalnız İngilizce oyunlarda kullanılır.", "The shared pool is used only for games in English."))
                }
            }
            GlossaryEditor()
        }
    }

    // MARK: - Çeviri motoru

    private static let engines = ["google", "deepl", "mlkit"]

    private var engineCard: some View {
        let target = model.settings.targetLanguage
        let targetName = LanguagesView.name(target)
        let chosen = model.settings.engine
        return SettingsCard(title: L("Çeviri motoru", "Translation engine"),
                            footer: L("İnternet koparsa çeviri kendiliğinden cihazdaki çeviriye geçer. DeepL anahtarı boşsa ya da çalışmazsa Google kullanılır.",
                                      "If the internet drops, translation switches to on-device translation by itself. If the DeepL key is empty or does not work, Google is used.")
                                + (Languages.onDeviceSupports(target) ? ""
                                   : " " + L("\(targetName) için cihazda çeviri yok; bu dilde internet koparsa çeviri durur.",
                                             "There is no on-device translation for \(targetName); in this language translation stops if the internet drops."))) {
            ForEach(Self.engines, id: \.self) { engine in
                engineRow(engine, supported: Languages.supports(engine: engine, target: target), selected: chosen == engine, targetName: targetName)
                if engine == "deepl" && chosen == "deepl" { deepLKey }
            }
            if !Languages.supports(engine: chosen, target: target) {
                NoticeText(text: L("\(Self.engineName(chosen)) bu dile (\(targetName)) çeviremiyor. Bu dilde çeviriyi Google yapıyor; motor seçimin saklanır.",
                                   "\(Self.engineName(chosen)) can't translate into \(targetName), so Google is translating for this language. Your engine choice is kept."))
            }
        }
    }

    private func engineRow(_ engine: String, supported: Bool, selected: Bool, targetName: String) -> some View {
        Button {
            var settings = model.settings
            settings.engine = engine
            model.settings = settings
        } label: {
            HStack(alignment: .top, spacing: Theme.space("m")) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 16))
                    .foregroundStyle(selected ? Color.csAccent : Color.csSecondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text(verbatim: Self.engineName(engine)).font(.csBody.weight(.medium))
                    HintText(text: supported ? Self.engineHelp(engine)
                                             : L("\(targetName) diline çeviremiyor.", "Can't translate into \(targetName)."))
                }
                Spacer()
            }
            .padding(Theme.space("m"))
            .background(selected ? Color.csAccentSoft : Color.clear, in: RoundedRectangle(cornerRadius: Theme.radius("control")))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!supported && !selected)
        .opacity(supported ? 1 : 0.6)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var deepLKey: some View {
        @Bindable var model = model
        return VStack(alignment: .leading, spacing: 6) {
            SecureField(L("DeepL API anahtarı", "DeepL API key"), text: Binding(
                get: { model.settings.deepLKey },
                set: { model.settings.deepLKey = $0.trimmingCharacters(in: .whitespacesAndNewlines) }))
                .textFieldStyle(CSTextFieldStyle())
            HStack(spacing: Theme.space("m")) {
                if model.settings.deepLKey.isEmpty {
                    HintText(text: L("Anahtar boşken Google kullanılır.", "Google is used while the key is empty."))
                } else {
                    HintText(text: model.settings.deepLKey.hasSuffix(":fx")
                             ? L("Ücretsiz DeepL anahtarı (ayda 500.000 karakter).", "Free DeepL key (500,000 characters a month).")
                             : L("Anahtar bu Mac'te saklanır.", "The key is stored on this Mac."))
                }
                Spacer()
                Link(L("Ücretsiz DeepL API anahtarı al", "Get a free DeepL API key"), destination: URL(string: "https://www.deepl.com/pro-api")!)
                    .font(.csCallout)
            }
        }
        .padding(.leading, 36)
    }

    static func engineName(_ engine: String) -> String {
        switch engine {
        case "deepl": "DeepL"
        case "mlkit": L("Cihazda (Apple Çeviri)", "On device (Apple Translation)")
        default: "Google"
        }
    }

    static func engineHelp(_ engine: String) -> String {
        switch engine {
        case "deepl": L("Daha doğal cümleler; kendi DeepL API anahtarınla çalışır.", "More natural sentences; works with your own DeepL API key.")
        case "mlkit": L("İnternetsiz çalışır; kalitesi daha düşüktür. Dil modeli yüklü değilse Google kullanılır; modeli Sistem Ayarları › Genel › Dil ve Bölge › Çeviri Dilleri'nden indirebilirsin.",
                        "Works without internet; quality is lower. If the language model is not installed, Google is used; download it under System Settings › General › Language & Region › Translation Languages.")
        default: L("İnternet ister, anahtar gerekmez. Çoğu oyun için önerilir.", "Needs internet, no key required. Recommended for most games.")
        }
    }
}

// MARK: - Terim sözlüğü

/// Terim sözlüğü düzenleyicisi (bilgisayardaki sözlük dosyalarıyla aynı JSON biçimi: "terms" ve "keep").
struct GlossaryEditor: View {
    @Environment(MacModel.self) private var model
    @State private var draft = ""
    @State private var loaded = false

    static let example = "{\n  \"terms\": {\n    \"Leviathan Axe\": \"Leviathan Baltası\"\n  },\n  \"keep\": [\"Kratos\", \"Atreus\"]\n}"

    var body: some View {
        let check = Self.inspect(draft)
        let changed = draft.trimmingCharacters(in: .whitespacesAndNewlines) != model.settings.glossary.trimmingCharacters(in: .whitespacesAndNewlines)
        SettingsCard(title: L("Terim sözlüğü", "Glossary"),
                     footer: L("Bilgisayardaki GameTranslator sözlük dosyalarıyla aynı biçimdir; oradaki dosyanın içeriğini buraya yapıştırabilirsin. Yalnızca İngilizce → Türkçe çeviride kullanılır.",
                               "Same format as the glossary files of GameTranslator on the PC; you can paste the contents of that file here. Used only for English → Turkish translation.")) {
            HintText(text: L("Çevirinin yanlış çevirdiği özel adları ve oyun terimlerini burada sabitleyebilirsin. \"terms\" içindeki terimler yazdığın Türkçeyle çevrilir, \"keep\" içindeki adlar olduğu gibi kalır.",
                             "Pin down the proper names and game terms the translation gets wrong. Terms under \"terms\" are translated with the Turkish you write; names under \"keep\" are left as they are."))
            TextEditor(text: $draft)
                .font(.system(size: 13, design: .monospaced))
                .autocorrectionDisabled()
                .scrollContentBackground(.hidden)
                .padding(Theme.space("s"))
                .frame(minHeight: 180, maxHeight: 320)
                .background(Color.csBackground, in: RoundedRectangle(cornerRadius: Theme.radius("control")))
                .overlay(RoundedRectangle(cornerRadius: Theme.radius("control"))
                    .strokeBorder(check.problem == nil ? Color.csBorder : Color.csDanger))
            HStack(spacing: Theme.space("m")) {
                if let problem = check.problem {
                    NoticeText(text: problem, symbol: "exclamationmark.circle.fill", color: .csDanger)
                } else if draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    HintText(text: L("Sözlük boş. Bir örnekle başlayıp kendi terimlerinle değiştirebilirsin.",
                                     "The glossary is empty. You can start from an example and replace it with your own terms."))
                    Button(L("Örnek ekle", "Add example")) { draft = Self.example }.buttonStyle(.link)
                } else {
                    NoticeText(text: L("\(check.terms) terim, \(check.keep) korunan ad okundu.", "Read \(check.terms) terms and \(check.keep) kept names."),
                               symbol: "checkmark.circle.fill", color: .csSuccess)
                }
                Spacer()
                if changed {
                    Button(L("Geri al", "Revert")) { draft = model.settings.glossary }.buttonStyle(.secondary)
                }
                Button(L("Kaydet", "Save")) {
                    var settings = model.settings
                    settings.glossary = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    model.settings = settings
                }
                .buttonStyle(.primary)
                .disabled(check.problem != nil || !changed)
            }
        }
        .onAppear {
            guard !loaded else { return }
            draft = model.settings.glossary
            loaded = true
        }
    }

    /// Sözlük metnindeki terim ve korunan ad sayısı; okunamıyorsa sorunu söyler (telefondaki GlossaryPage.inspect).
    static func inspect(_ text: String) -> (terms: Int, keep: Int, problem: String?) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return (0, 0, nil) }
        guard let root = try? JSONSerialization.jsonObject(with: Data(trimmed.utf8), options: [.fragmentsAllowed]) else {
            return (0, 0, L("Yazım hatası var: tırnak, virgül ve parantezleri kontrol et.", "There is a syntax error: check the quotes, commas and brackets."))
        }
        guard let object = root as? [String: Any] else {
            return (0, 0, L("Sözlük { ... } biçiminde olmalı.", "The glossary must be in the form { ... }."))
        }
        if let terms = object["terms"], !(terms is [String: Any]) {
            return (0, 0, L("\"terms\" bir { \"İngilizce\": \"Türkçe\" } listesi olmalı.", "\"terms\" must be a { \"English\": \"Turkish\" } list."))
        }
        if let keep = object["keep"], !(keep is [Any]) {
            return (0, 0, L("\"keep\" bir [\"Ad\", \"Ad\"] listesi olmalı.", "\"keep\" must be a [\"Name\", \"Name\"] list."))
        }
        let terms = (object["terms"] as? [String: Any] ?? [:]).values.filter { $0 is String }.count
        let keep = (object["keep"] as? [Any] ?? []).filter { $0 is String }.count
        return (terms, keep, nil)
    }
}
