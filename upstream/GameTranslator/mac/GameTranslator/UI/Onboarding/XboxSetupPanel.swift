// SPDX-License-Identifier: AGPL-3.0-only

import AppKit
import GTNative
import SwiftUI

/// Xbox (Beta): Microsoft cihaz koduyla giriş. Kod ekranda gösterilir, Microsoft'un sayfası varsayılan tarayıcıda açılır;
/// parola uygulamaya gelmez. Telefondaki xbox_setup.dart'ın Mac karşılığı.
struct XboxSignInPanel: View {
    @Environment(MacModel.self) private var model
    var onSignedIn: () -> Void = {}

    @State private var code: String?
    @State private var uri: String?
    @State private var starting = false
    @State private var waiting = false
    @State private var error: String?
    @State private var signedIn = false

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.space("l")) {
            if signedIn {
                DoneLabel(text: L("Microsoft hesabı bağlandı.", "Microsoft account linked."))
            } else if let code, let uri {
                VStack(alignment: .leading, spacing: Theme.space("m")) {
                    Text(L("Microsoft sayfasına bu kodu yazın", "Enter this code on Microsoft's page"))
                        .font(.csCaption.weight(.semibold)).foregroundStyle(Color.csSecondary)
                    HStack(spacing: Theme.space("l")) {
                        Text(verbatim: code)
                            .font(.system(size: 34, weight: .bold, design: .monospaced))
                            .textSelection(.enabled)
                        Button(L("Kopyala", "Copy")) { copyToPasteboard(code) }.buttonStyle(.secondary)
                    }
                    Text(verbatim: uri).font(.csCode).foregroundStyle(Color.csSecondary).textSelection(.enabled)
                    Button(L("Kodu kopyala ve sayfayı aç", "Copy the code and open the page")) { openPage() }
                        .buttonStyle(.primary)
                    HStack(spacing: Theme.space("s")) {
                        ProgressView().controlSize(.small)
                        Text(L("Onayınız bekleniyor… Girişi tarayıcıda bitirince buraya dönün.", "Waiting for your approval… Return here after finishing the sign-in in the browser."))
                            .font(.csCallout).foregroundStyle(Color.csSecondary)
                    }
                    Button(L("Vazgeç", "Cancel")) { cancel() }.buttonStyle(.link)
                }
            } else {
                Button {
                    Task { await start() }
                } label: {
                    if starting {
                        HStack { ProgressView().controlSize(.small); Text(L("Kod alınıyor…", "Getting a code…")) }
                    } else {
                        Text(L("Microsoft ile giriş yap", "Sign in with Microsoft"))
                    }
                }
                .buttonStyle(.primary)
                .disabled(starting)
            }
            ErrorText(error: error)
            HintText(text: L("Giriş Microsoft'un kendi sayfasında yapılır. Şifreniz bu uygulamaya gelmez; yalnızca giriş anahtarı bu Mac'in Anahtar Zinciri'nde saklanır.",
                             "You sign in on Microsoft's own page. Your password never reaches this app; only the sign-in token is kept in this Mac's Keychain."))
        }
        .onAppear { signedIn = model.xbox.signedIn }
        .onDisappear { if waiting { model.xbox.cancelSignIn() } }
    }

    private func start() async {
        starting = true
        error = nil
        let result = await model.xbox.startSignIn()
        starting = false
        guard result["ok"] as? Bool == true, let userCode = result["userCode"] as? String,
              let verification = result["verificationUri"] as? String else {
            error = model.describe(result["error"] as? String ?? "@xs_start_failed")
            return
        }
        code = userCode
        uri = verification
        openPage()
        waiting = true
        let outcome = await model.xbox.awaitSignIn()
        waiting = false
        code = nil
        uri = nil
        if outcome["ok"] as? Bool == true {
            signedIn = true
            model.refreshSetup()
            onSignedIn()
        } else if outcome["cancelled"] as? Bool != true {
            error = model.describe(outcome["error"] as? String ?? "@xs_finish_failed")
        }
    }

    private func openPage() {
        guard let code, let uri, let url = URL(string: uri) else { return }
        copyToPasteboard(code)
        NSWorkspace.shared.open(url)
    }

    private func cancel() {
        model.xbox.cancelSignIn()
        code = nil
        uri = nil
    }
}

/// Hesaptaki Xbox'lardan birini seçme.
struct XboxConsolePanel: View {
    @Environment(MacModel.self) private var model
    var onChosen: () -> Void = {}
    /// Giriş süresi dolduysa çağrılır (yeniden giriş istenir).
    var onNeedsSignIn: () -> Void = {}

    @State private var consoles: [XboxConsole] = []
    @State private var loading = false
    @State private var loaded = false
    @State private var selectedID: String?
    @State private var error: String?
    @State private var needsSignIn = false

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.space("l")) {
            VStack(alignment: .leading, spacing: Theme.space("s")) {
                StepRow(number: 1, text: L("Xbox'ta Ayarlar › Cihazlar ve bağlantılar › Uzak özellikler'e girin.",
                                           "On the Xbox, open Settings › Devices & connections › Remote features."))
                StepRow(number: 2, text: L("\"Uzak özellikleri etkinleştir\"i işaretleyin.", "Check \"Enable remote features\"."))
                StepRow(number: 3, text: L("Güç seçeneklerinde \"Uyku\"yu seçin; Xbox açık ve internete bağlı olsun.",
                                           "Choose \"Sleep\" in the power options; keep the Xbox on and connected to the internet."))
            }
            HStack {
                Text(L("HESABINIZDAKİ XBOX'LAR", "XBOX CONSOLES ON YOUR ACCOUNT")).font(.csCaption.weight(.semibold)).foregroundStyle(Color.csSecondary)
                Spacer()
                if loading { ProgressView().controlSize(.small) }
                Button(L("Yenile", "Refresh")) { Task { await load() } }.buttonStyle(.link).disabled(loading)
            }
            if consoles.isEmpty && loaded && error == nil {
                HintText(text: L("Bu hesapta Xbox bulunamadı. Xbox'ta aynı Microsoft hesabıyla oturum açın, uzak özellikleri etkinleştirin ve \"Yenile\"ye basın.",
                                 "No Xbox was found on this account. Sign in on the Xbox with the same Microsoft account, enable remote features and press \"Refresh\"."))
            }
            ForEach(consoles, id: \.id) { console in
                PickRow(title: console.label, detail: console.model.isEmpty ? nil : console.model,
                        badge: console.streamingEnabled ? L("uzak özellikler açık", "remote features on") : L("uzak özellikler kapalı", "remote features off"),
                        badgeGood: console.streamingEnabled, selected: selectedID == console.id, enabled: console.streamingEnabled) {
                    selectedID = console.id
                }
            }
            if consoles.contains(where: { !$0.streamingEnabled }) {
                HintText(text: L("\"Uzak özellikler kapalı\" yazan Xbox seçilemez. O Xbox'ta yukarıdaki 1 ve 2. adımları yapıp \"Yenile\"ye basın.",
                                 "An Xbox showing \"remote features off\" can't be chosen. Do steps 1 and 2 above on that Xbox and press \"Refresh\"."))
            }
            ErrorText(error: error)
            HStack {
                Button(L("Bu Xbox'ı kullan", "Use this Xbox")) { choose() }
                    .buttonStyle(.primary)
                    .disabled(selectedID == nil)
                if needsSignIn {
                    Button(L("Yeniden giriş yap", "Sign in again")) {
                        Task {
                            await model.xbox.signOut()
                            model.refreshSetup()
                            onNeedsSignIn()
                        }
                    }
                    .buttonStyle(.secondary)
                }
            }
        }
        .task { await load() }
    }

    private func load() async {
        loading = true
        error = nil
        let result = await model.xbox.consoles()
        loading = false
        loaded = true
        guard result["ok"] as? Bool == true, let list = result["consoles"] as? [[String: Any]] else {
            error = model.describe(result["error"] as? String ?? "@xs_list_failed")
            needsSignIn = result["needsSignIn"] as? Bool ?? false
            return
        }
        needsSignIn = false
        consoles = list.compactMap { map in
            guard let id = map["id"] as? String else { return nil }
            return XboxConsole(id: id, name: map["name"] as? String ?? "", model: map["model"] as? String ?? "",
                               streamingEnabled: map["streamingEnabled"] as? Bool ?? false, powerState: map["powerState"] as? String ?? "")
        }
        let current = model.xbox.selectedConsole?.id
        if let current, consoles.contains(where: { $0.id == current }) {
            selectedID = current
        } else if selectedID == nil {
            selectedID = consoles.first(where: \.streamingEnabled)?.id
        }
    }

    private func choose() {
        guard let console = consoles.first(where: { $0.id == selectedID }) else {
            error = L("Önce listeden Xbox'ınızı seçin.", "Choose your Xbox from the list first.")
            return
        }
        model.xbox.selectConsole(id: console.id, name: console.name, model: console.model,
                                 streamingEnabled: console.streamingEnabled, powerState: console.powerState)
        model.refreshSetup()
        onChosen()
    }
}
