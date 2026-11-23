// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// PS5 eşleştirmesi: ağdaki konsollar (ya da elle yazılan IP) ve PS5'in "Cihaz Eşleştir" ekranındaki 8 haneli kod.
/// Kurulum sihirbazında ve Bağlantı › "Yeniden eşleştir"de kullanılır.
struct PS5PairingPanel: View {
    @Environment(MacModel.self) private var model
    /// Eşleşince çağrılır.
    var onPaired: () -> Void = {}

    @State private var consoles: [FoundConsole] = []
    @State private var searching = false
    @State private var searched = false
    @State private var selectedHost: String?
    @State private var manualIP = ""
    @State private var pin = ""
    @State private var pairing = false
    @State private var error: String?
    @State private var pairedName: String?

    struct FoundConsole: Identifiable, Equatable {
        let host: String
        let name: String
        let awake: Bool
        var id: String { host }
    }

    private var host: String? {
        let typed = manualIP.trimmingCharacters(in: .whitespaces)
        return typed.isEmpty ? selectedHost : typed
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.space("l")) {
            VStack(alignment: .leading, spacing: Theme.space("s")) {
                StepRow(number: 1, text: L("PS5'te ikinci kullanıcıya geçin.", "On the PS5, switch to the second user."))
                StepRow(number: 2, text: L("Ayarlar › Sistem › Uzaktan Oynatma'da \"Uzaktan Oynatmayı Etkinleştir\"i açın.",
                                           "Turn on \"Enable Remote Play\" in Settings › System › Remote Play."))
                StepRow(number: 3, text: L("\"Cihaz Eşleştir\"e girin. Ekranda 8 haneli bir kod görünür (yaklaşık 5 dakika geçerli).",
                                           "Open \"Link Device\". An 8-digit code appears on the screen (valid for about 5 minutes)."))
            }

            VStack(alignment: .leading, spacing: Theme.space("s")) {
                HStack {
                    Text(L("BULUNAN KONSOL", "CONSOLES FOUND")).font(.csCaption.weight(.semibold)).foregroundStyle(Color.csSecondary)
                    Spacer()
                    if searching { ProgressView().controlSize(.small) }
                    Button(L("Yenile", "Refresh")) { Task { await search() } }
                        .buttonStyle(.link)
                        .disabled(searching)
                }
                if consoles.isEmpty {
                    HintText(text: searching ? L("Aranıyor…", "Searching…") :
                        searched ? L("PS5 bulunamadı. Mac ve PS5 aynı ağda olmalı. PS5'in IP adresini aşağıya elle de yazabilirsiniz (PS5'te Ayarlar › Ağ › Bağlantı Durumu).",
                                     "No PS5 found. The Mac and the PS5 must be on the same network. You can also type the PS5's IP address below (on the PS5: Settings › Network › Connection Status).")
                        : L("Henüz aranmadı.", "Not searched yet."))
                } else {
                    ForEach(consoles) { console in
                        PickRow(title: console.name, detail: console.host,
                                badge: console.awake ? L("açık", "on") : L("dinlenmede", "rest mode"), badgeGood: console.awake,
                                selected: manualIP.isEmpty && selectedHost == console.host) {
                            selectedHost = console.host
                            manualIP = ""
                        }
                    }
                }
                HStack {
                    TextField(L("PS5 IP adresi (isteğe bağlı), ör. 192.168.1.20", "PS5 IP address (optional), e.g. 192.168.1.20"), text: $manualIP)
                        .textFieldStyle(CSTextFieldStyle())
                        .onSubmit { Task { await search() } }
                    Button(L("Ara", "Search")) { Task { await search() } }
                        .buttonStyle(.secondary)
                        .disabled(searching || manualIP.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }

            VStack(alignment: .leading, spacing: Theme.space("s")) {
                Text(L("EŞLEŞTİRME KODU", "LINK CODE")).font(.csCaption.weight(.semibold)).foregroundStyle(Color.csSecondary)
                HStack {
                    TextField("12345678", text: $pin)
                        .textFieldStyle(CSTextFieldStyle(font: .system(size: 22, weight: .semibold, design: .monospaced), height: 48))
                        .frame(width: 220)
                        .onChange(of: pin) { _, value in
                            let digits = String(value.filter(\.isNumber).prefix(8))
                            if digits != value { pin = digits }
                        }
                        .onSubmit { Task { await pair() } }
                    Button {
                        Task { await pair() }
                    } label: {
                        if pairing {
                            HStack { ProgressView().controlSize(.small); Text(L("Eşleştiriliyor…", "Pairing…")) }
                        } else {
                            Text(L("Eşleştir", "Pair"))
                        }
                    }
                    .buttonStyle(.primary)
                    .disabled(pairing || host == nil || pin.count != 8)
                }
            }

            ErrorText(error: error)
            if let pairedName { DoneLabel(text: L("\(pairedName) eşleştirildi.", "\(pairedName) is paired.")) }
        }
        .task { await search() }
    }

    private func search() async {
        guard !searching else { return }
        searching = true
        let extra = manualIP.trimmingCharacters(in: .whitespaces)
        let found = await model.discover(hosts: extra.isEmpty ? [] : [extra])
        consoles = found.compactMap { map in
            guard let host = map["host"] as? String else { return nil }
            let name = (map["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "PS5"
            return FoundConsole(host: host, name: name, awake: map["awake"] as? Bool ?? false)
        }
        if selectedHost == nil || !consoles.contains(where: { $0.host == selectedHost }) { selectedHost = consoles.first?.host }
        if !extra.isEmpty, consoles.contains(where: { $0.host == extra }) {
            selectedHost = extra
            manualIP = ""
        }
        searching = false
        searched = true
    }

    private func pair() async {
        guard let host, pin.count == 8, let code = Int(pin), !pairing else {
            if host == nil { error = L("Önce PS5'i seçin ya da IP adresini yazın.", "Choose the PS5 or type its IP address first.") }
            else if pin.count != 8 { error = L("PS5 ekranındaki 8 haneli kodu girin.", "Enter the 8-digit code shown on the PS5.") }
            return
        }
        pairing = true
        error = nil
        let outcome = await model.register(host: host, pin: code)
        pairing = false
        if outcome.ok {
            pin = ""
            pairedName = model.console?.name ?? "PS5"
            model.refreshSetup()
            onPaired()
        } else {
            error = model.describePairing(outcome.detail)
        }
    }
}
