// SPDX-License-Identifier: AGPL-3.0-only

import AppKit
import GTNative
import SwiftUI

/// TV uygulamasını Mac'ten kurma (telefondaki tv_install_page.dart'ın sadeleştirilmişi): marka, ağda bulma ya da elle
/// adres, canlı denetim ve adımlar, LG parolası, Samsung girişi, kurulum ilerlemesi ve sonucu. Bağlantı ekranında ve
/// kurulum sihirbazının TV adımında aynı panel kullanılır.
struct TvInstallPanel: View {
    @Environment(MacModel.self) private var model

    private struct FoundTv: Identifiable, Equatable {
        let host: String
        let name: String
        let ready: Bool
        var id: String { host }
    }

    private struct Check: Equatable {
        var ready = false
        var keyServer = false
        var hasKey = false
        var signedIn = false
        var developerMode: Bool?
        var developerIp: String?
        var hostIpMatches: Bool?
        var year: Int?

        init() {}

        init(_ map: [String: Any]) {
            ready = map["ready"] as? Bool ?? false
            keyServer = map["keyServer"] as? Bool ?? false
            hasKey = map["hasKey"] as? Bool ?? false
            signedIn = map["signedIn"] as? Bool ?? false
            developerMode = map["developerMode"] as? Bool
            developerIp = map["developerIp"] as? String
            hostIpMatches = map["hostIpMatches"] as? Bool
            year = map["year"] as? Int
        }
    }

    private struct Outcome {
        var ok: Bool
        var version: String?
        var installed = false
        var launched = false
        var hoursLeft: Int?
        var error: String?
        var detail: String?

        init(_ map: [String: Any]) {
            ok = map["ok"] as? Bool ?? false
            version = map["version"] as? String
            installed = map["installed"] as? Bool ?? false
            launched = map["launched"] as? Bool ?? false
            hoursLeft = map["hoursLeft"] as? Int
            error = map["error"] as? String
            detail = map["detail"] as? String
        }

        init(error: String, detail: String? = nil) {
            ok = false
            self.error = error
            self.detail = detail
        }
    }

    @State private var saved: [String: Any]?
    @State private var bundled: [String: String] = [:]
    /// Kayıtlı TV varken yeni kurulum adımları açık mı.
    @State private var wizard = false
    @State private var brand: String?
    @State private var found: [FoundTv] = []
    @State private var finding = false
    @State private var searched = false
    @State private var host: String?
    @State private var tvName = ""
    @State private var manual = ""
    @State private var manualError: String?
    @State private var check: Check?
    @State private var passphrase = ""
    @State private var samsungEmail: String?
    @State private var signingIn = false
    @State private var signInError: (title: String, fix: String)?
    @State private var installing = false
    @State private var stage = "connecting"
    @State private var result: Outcome?

    private var tv: TvInstall { model.tv }
    private var macAddress: String { tv.phoneAddress() ?? model.macAddress ?? "?" }
    private var needsPassphrase: Bool { brand == "lg" && check?.hasKey != true }
    private var samsungSignedIn: Bool { check?.hasKey == true || check?.signedIn == true || samsungEmail != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.space("l")) {
            if let result, result.ok, let brand {
                done(result, brand: brand)
            } else if let saved, !wizard {
                savedView(saved)
            } else {
                brandPicker
                if let brand {
                    finder(brand)
                    if let host { target(brand, host: host) }
                }
            }
        }
        .onAppear(perform: reload)
        .task(id: "\(brand ?? "")|\(host ?? "")|\(installing)") { await pollCheck() }
    }

    private func reload() {
        saved = tv.saved()
        bundled = tv.bundled()
    }

    // MARK: - Kayıtlı TV

    private func savedView(_ saved: [String: Any]) -> some View {
        let savedBrand = saved["brand"] as? String ?? "android"
        let savedHost = saved["host"] as? String ?? ""
        let name = (saved["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? TvText.brandName(savedBrand)
        let installedVersion = saved["version"] as? String
        let available = bundled[savedBrand]
        let update = available.map { new in installedVersion.map { TvText.isNewer(new, than: $0) } ?? true } ?? false
        return VStack(alignment: .leading, spacing: Theme.space("m")) {
            Text(L("KAYITLI TV", "SAVED TV")).font(.csCaption.weight(.semibold)).foregroundStyle(Color.csSecondary)
            HStack(spacing: Theme.space("m")) {
                Image(systemName: "tv").font(.title2).foregroundStyle(Color.csAccentText)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: name).font(.csBody.weight(.semibold))
                    Text(verbatim: "\(TvText.brandName(savedBrand)) · \(savedHost) · " +
                        L("TV'de \(installedVersion ?? "?") · Mac'te \(available ?? "?")", "on TV \(installedVersion ?? "?") · in app \(available ?? "?")"))
                        .font(.csCallout).foregroundStyle(Color.csSecondary)
                }
                Spacer()
                if update {
                    Text(L("Güncelleme var", "Update available"))
                        .font(.csCaption.weight(.semibold)).padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Color.csAccentSoft, in: Capsule()).foregroundStyle(Color.csAccentText)
                }
            }
            HStack {
                Button(update ? L("Güncelle", "Update") : L("Yeniden kur", "Reinstall")) {
                    brand = savedBrand
                    host = savedHost
                    tvName = saved["name"] as? String ?? ""
                    wizard = true
                }
                .buttonStyle(update ? .primary : .secondary)
                Button(L("Başka bir TV kur", "Set up another TV")) {
                    brand = nil
                    host = nil
                    wizard = true
                }
                .buttonStyle(.secondary)
                Spacer()
                Button(L("Unut", "Forget"), role: .destructive) {
                    tv.forget()
                    reload()
                }
                .buttonStyle(.link)
            }
        }
    }

    // MARK: - Marka ve bulma

    private var brandPicker: some View {
        VStack(alignment: .leading, spacing: Theme.space("s")) {
            HStack {
                Text(L("TV'NİN MARKASI", "TV BRAND")).font(.csCaption.weight(.semibold)).foregroundStyle(Color.csSecondary)
                Spacer()
                if saved != nil {
                    Button(L("Kayıtlı TV'ye dön", "Back to the saved TV")) { resetWizard() }.buttonStyle(.link)
                }
            }
            ForEach(TvText.brands, id: \.self) { item in
                PickRow(title: TvText.brandName(item), detail: TvText.brandDetail(item), selected: brand == item) {
                    guard brand != item else { return }
                    brand = item
                    host = nil
                    found = []
                    searched = false
                    check = nil
                    result = nil
                    Task { await find() }
                }
            }
            HintText(text: L("Vestel/Arçelik/Beko/Grundig'in Linux modelleri, Philips Saphi ve Titan OS desteklenmiyor; bu TV'lerde ikinci ekranı kullanabilirsiniz.",
                             "Linux models from Vestel/Arçelik/Beko/Grundig, Philips Saphi and Titan OS aren't supported; use the second screen with those TVs."))
        }
    }

    private func finder(_ brand: String) -> some View {
        VStack(alignment: .leading, spacing: Theme.space("s")) {
            HStack {
                Text(L("BULUNAN TV'LER", "TVS FOUND")).font(.csCaption.weight(.semibold)).foregroundStyle(Color.csSecondary)
                Spacer()
                if finding { ProgressView().controlSize(.small) }
                Button(L("Yenile", "Refresh")) { Task { await find() } }.buttonStyle(.link).disabled(finding)
            }
            if found.isEmpty {
                HintText(text: finding ? L("Ağ taranıyor, birkaç saniye sürer…", "Scanning the network, this takes a few seconds…")
                    : searched ? (brand == "android"
                        ? L("Hata ayıklaması açık bir Android TV bulunamadı. Aşağıdaki adımlarla açın ya da adresi elle yazın.",
                            "No Android TV with debugging on was found. Turn it on with the steps below or type the address.")
                        : L("TV bulunamadı. Yenile'ye basın ya da adresi elle yazın.", "No TV found. Press Refresh or type the address."))
                    : L("Henüz aranmadı.", "Not searched yet."))
            }
            ForEach(found) { item in
                PickRow(title: item.name.isEmpty ? TvText.brandName(brand) : item.name, detail: item.host,
                        badge: item.ready ? L("Hazır", "Ready") : nil, selected: host == item.host) {
                    host = item.host
                    tvName = item.name
                    result = nil
                }
            }
            HStack {
                TextField(L("Adresi elle yazın, ör. 192.168.1.40", "Type the address, e.g. 192.168.1.40"), text: $manual)
                    .textFieldStyle(CSTextFieldStyle())
                    .onSubmit(useManual)
                Button(L("Bu adresi kullan", "Use this address"), action: useManual)
                    .buttonStyle(.secondary)
                    .disabled(manual.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            ErrorText(error: manualError)
            HintText(text: TvText.whereAddress(brand) + " " +
                L("Mac'in adresi \(macAddress); TV'ninki ilk üç bölümde aynı olmalı.", "The Mac's address is \(macAddress); the TV's should match in the first three parts."))
        }
    }

    private func useManual() {
        guard let address = tv.normalize(manual) else {
            manualError = L("Bu bir IP adresine benzemiyor. Örnek: 192.168.1.40", "That doesn't look like an IP address. Example: 192.168.1.40")
            return
        }
        manualError = nil
        manual = ""
        host = address
        tvName = ""
        result = nil
    }

    private func find() async {
        guard let brand, !finding else { return }
        finding = true
        let list = (try? await tv.find(brand)) ?? []
        // Arama sürerken marka değiştiyse sonuç atılır.
        guard self.brand == brand else { finding = false; return }
        found = list.compactMap { map in
            guard let host = map["host"] as? String else { return nil }
            return FoundTv(host: host, name: map["name"] as? String ?? "", ready: map["ready"] as? Bool ?? false)
        }
        if host == nil, found.count == 1 { host = found[0].host; tvName = found[0].name }
        finding = false
        searched = true
    }

    // MARK: - Denetim, adımlar ve kurulum

    private func pollCheck() async {
        guard let brand, let host, !installing else { return }
        while !Task.isCancelled {
            if let map = try? await tv.check(brand: brand, host: host), self.brand == brand, self.host == host {
                check = Check(map)
            }
            try? await Task.sleep(for: .seconds(3))
        }
    }

    /// Canlı denetimin sonucu: TV hazır mı, değilse neyi bekliyoruz.
    private func status(_ brand: String) -> (ok: Bool, text: String) {
        guard let check else { return (false, L("TV bekleniyor…", "Waiting for the TV…")) }
        switch brand {
        case "lg":
            if check.hasKey && check.ready { return (true, L("TV hazır. Kurabilirsiniz.", "The TV is ready. You can install.")) }
            if check.keyServer { return (true, L("Key Server açık. TV ekranındaki parolayı yazıp kurabilirsiniz.", "Key Server is on. Type the passphrase shown on the TV and install.")) }
            if check.ready { return (false, L("Geliştirici modu açık. Şimdi Developer Mode uygulamasında \"Key Server\"ı açın.", "Developer mode is on. Now turn on \"Key Server\" in the Developer Mode app.")) }
            return (false, L("TV'de geliştirici modu bekleniyor…", "Waiting for developer mode on the TV…"))
        case "samsung":
            if check.ready { return (true, L("TV bu Mac'in bağlantısını kabul ediyor. Kurabilirsiniz.", "The TV accepts this Mac's connection. You can install.")) }
            if check.developerMode == false { return (false, L("TV'de \"Developer mode\" henüz kapalı görünüyor…", "\"Developer mode\" still looks off on the TV…")) }
            if check.hostIpMatches == false {
                return (false, L("TV'de \"Host PC IP\" şu an \(check.developerIp ?? "?"). \(macAddress) olmalı…", "The TV's \"Host PC IP\" is \(check.developerIp ?? "?"). It should be \(macAddress)…"))
            }
            if check.developerMode == true && check.hostIpMatches == true {
                return (false, L("Ayar doğru. TV'nin tamamen kapatılıp açılması bekleniyor…", "The setting is right. Waiting for the TV to be fully restarted…"))
            }
            return (false, L("TV bekleniyor…", "Waiting for the TV…"))
        default:
            return check.ready ? (true, L("TV hazır. Kurabilirsiniz.", "The TV is ready. You can install."))
                : (false, L("TV'de hata ayıklamanın açılması bekleniyor…", "Waiting for debugging to be turned on on the TV…"))
        }
    }

    private func target(_ brand: String, host: String) -> some View {
        let state = status(brand)
        return VStack(alignment: .leading, spacing: Theme.space("m")) {
            Divider()
            HStack(spacing: Theme.space("s")) {
                Image(systemName: state.ok ? "checkmark.circle.fill" : "hourglass")
                    .foregroundStyle(state.ok ? Color.csSuccess : Color.csWarning)
                Text(verbatim: "\(tvName.isEmpty ? TvText.brandName(brand) : tvName) · \(host)").font(.csBody.weight(.semibold))
            }
            Text(verbatim: state.text).font(.csCallout).foregroundStyle(state.ok ? Color.csSuccess : Color.csSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if brand == "samsung", let year = check?.year, year < 2017 {
                NoticeBox(text: L("Bu TV \(year) modeli görünüyor. TV uygulaması 2017 ve sonrası Samsung TV'lerde (Tizen 3.0+) çalışır; yine de deneyebilirsiniz.",
                                  "This TV looks like a \(year) model. The TV app runs on 2017 and later Samsung TVs (Tizen 3.0+); you can still try."))
            }

            DisclosureGroup(brand == "android" ? L("Hata ayıklamayı açma adımları", "Steps to turn on debugging")
                : L("Geliştirici modunu açma adımları", "Steps to turn on developer mode")) {
                VStack(alignment: .leading, spacing: Theme.space("s")) {
                    ForEach(Array(TvText.steps(brand, macAddress: macAddress).enumerated()), id: \.offset) { index, text in
                        StepRow(number: index + 1, text: text)
                    }
                    if brand == "samsung" {
                        Button(L("Mac'in adresini kopyala (\(macAddress))", "Copy the Mac's address (\(macAddress))")) { copyToPasteboard(macAddress) }
                            .buttonStyle(.link)
                    }
                }
                .padding(.top, Theme.space("s"))
            }
            .disclosureGroupStyle(.automatic)

            if needsPassphrase {
                VStack(alignment: .leading, spacing: Theme.space("xs")) {
                    Text(L("TV EKRANINDAKİ PAROLA", "PASSPHRASE ON THE TV")).font(.csCaption.weight(.semibold)).foregroundStyle(Color.csSecondary)
                    TextField(L("ör. 2FEEB2", "e.g. 2FEEB2"), text: $passphrase)
                        .textFieldStyle(CSTextFieldStyle(font: .system(size: 18, weight: .semibold, design: .monospaced)))
                        .frame(width: 200)
                    HintText(text: L("Developer Mode uygulamasında Key Server açıkken sol altta \"Passphrase\" olarak görünen 6 karakter. Yalnız ilk kurulumda sorulur.",
                                     "The 6 characters shown as \"Passphrase\" at the bottom left of the Developer Mode app while Key Server is on. Asked only on the first install."))
                }
            }

            if brand == "samsung" && !samsungSignedIn { samsungSignIn }
            if brand == "android" && !installing {
                HintText(text: L("Kurulumda TV \"USB hata ayıklamaya izin verilsin mi?\" diye sorar: \"Bu bilgisayardan her zaman izin ver\"i işaretleyip \"İzin ver\"i seçin.",
                                 "During installation the TV asks \"Allow USB debugging?\": check \"Always allow from this computer\" and choose \"Allow\"."))
            }

            if installing {
                HStack(spacing: Theme.space("s")) {
                    ProgressView().controlSize(.small)
                    Text(verbatim: TvText.stage(stage)).font(.csCallout).fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    Button(L("Durdur", "Stop")) { tv.cancel() }.buttonStyle(.secondary)
                }
            } else {
                HStack {
                    Button(L("TV'ye kur", "Install on the TV")) { Task { await install(force: false) } }
                        .buttonStyle(.primary)
                        .disabled(signingIn)
                    if let version = bundled[brand] {
                        Text(L("Kurulacak sürüm \(version)", "Version \(version)")).font(.csCallout).foregroundStyle(Color.csSecondary)
                    }
                    Spacer()
                    Button(L("Zorla yeniden kur", "Force reinstall")) { Task { await install(force: true) } }
                        .buttonStyle(.link)
                        .disabled(signingIn)
                }
            }

            if let result, !result.ok {
                let text = TvText.error(result.error, brand: brand)
                VStack(alignment: .leading, spacing: Theme.space("xs")) {
                    ErrorText(error: text.title)
                    HintText(text: text.fix)
                    if let detail = result.detail, !detail.isEmpty, detail != result.error {
                        Text(verbatim: L("TV'nin söylediği: ", "TV said: ") + detail)
                            .font(.csCaption).foregroundStyle(Color.csSecondary).textSelection(.enabled)
                    }
                }
            }
        }
    }

    private var samsungSignIn: some View {
        VStack(alignment: .leading, spacing: Theme.space("s")) {
            Text(L("Samsung hesabıyla giriş", "Samsung account sign-in")).font(.csBody.weight(.semibold))
            HintText(text: L("Samsung, mağaza dışından kurulan uygulamaların her TV için ayrı ve ücretsiz bir sertifikayla imzalanmasını istiyor. Bu TV için yalnız bir kez yapılır. Giriş Samsung'un sayfasında, varsayılan tarayıcınızda yapılır; şifrenizi görmeyiz.",
                             "Samsung requires apps installed outside the store to be signed with a free certificate for each TV. You do this once per TV. You sign in on Samsung's page in your default browser; we never see your password."))
            if signingIn {
                HStack(spacing: Theme.space("s")) {
                    ProgressView().controlSize(.small)
                    Text(L("Tarayıcıda giriş bekleniyor… Bitince kurulum kendiliğinden sürer.", "Waiting for the sign-in in the browser… Installation continues by itself afterwards."))
                        .font(.csCallout)
                    Spacer()
                    Button(L("Vazgeç", "Cancel")) { tv.samsungSignInAbort() }.buttonStyle(.secondary)
                }
            } else {
                Button(L("Samsung hesabıyla giriş yap", "Sign in with Samsung account")) { Task { await signInSamsung() } }
                    .buttonStyle(.secondary)
            }
            if let signInError {
                ErrorText(error: signInError.title)
                HintText(text: signInError.fix)
            }
        }
        .padding(Theme.space("m"))
        .background(Color.csBackground, in: RoundedRectangle(cornerRadius: Theme.radius("control")))
    }

    private func signInSamsung() async {
        guard !signingIn else { return }
        signInError = nil
        tv.uiLanguage = model.settings.uiLanguage
        guard let address = tv.samsungSignIn(), let url = URL(string: address) else {
            signInError = (L("Giriş başlatılamadı", "Sign-in couldn't start"),
                           L("Mac'te 4794 numaralı port başka bir uygulamada açık. O uygulamayı kapatıp yeniden deneyin.",
                             "Port 4794 is in use by another app on this Mac. Close that app and try again."))
            return
        }
        signingIn = true
        NSWorkspace.shared.open(url)
        let outcome = await tv.samsungSignInWait()
        signingIn = false
        if outcome["ok"] as? Bool == true {
            samsungEmail = outcome["email"] as? String ?? ""
            await install(force: false)
        } else {
            signInError = TvText.error(outcome["error"] as? String ?? "SIGN_IN_FAILED", brand: "samsung")
        }
    }

    private func install(force: Bool) async {
        guard let brand, let host, !installing else { return }
        let pass = passphrase.trimmingCharacters(in: .whitespacesAndNewlines)
        if needsPassphrase && pass.isEmpty {
            result = Outcome(error: "WRONG_PASSPHRASE", detail: L("parola boş", "passphrase is empty"))
            return
        }
        tv.serverPort = model.serverPortOrDefault
        tv.uiLanguage = model.settings.uiLanguage
        tv.onStage = { value in MainActor.assumeIsolated { stage = value } }
        installing = true
        result = nil
        stage = "connecting"
        let map = (try? await tv.install(brand: brand, host: host, name: tvName, passphrase: needsPassphrase ? pass : nil, force: force))
            ?? ["ok": false, "error": "OTHER"]
        let outcome = Outcome(map)
        installing = false
        tv.onStage = nil
        if brand == "samsung" && outcome.error == "SIGN_IN_NEEDED" {
            // Bu TV'nin sertifikası yok: giriş istenir, giriş bitince kurulum kendiliğinden sürer.
            samsungEmail = nil
            check?.hasKey = false
            check?.signedIn = false
            await signInSamsung()
            return
        }
        if outcome.error == "CERTIFICATE_FAILED" { samsungEmail = nil }
        if outcome.error == "KEY_REFUSED" || outcome.error == "WRONG_PASSPHRASE" { check?.hasKey = false }
        result = outcome
        if outcome.ok {
            passphrase = ""
            saved = tv.saved()
        }
    }

    private func resetWizard() {
        wizard = false
        brand = nil
        host = nil
        found = []
        check = nil
        result = nil
        reload()
    }

    // MARK: - Sonuç

    private func done(_ outcome: Outcome, brand: String) -> some View {
        VStack(alignment: .leading, spacing: Theme.space("m")) {
            DoneLabel(text: outcome.installed ? L("TV uygulaması kuruldu", "The TV app is installed") : L("TV uygulaması zaten güncel", "The TV app is already up to date"))
            if let version = outcome.version { Text(L("Sürüm \(version)", "Version \(version)")).foregroundStyle(Color.csSecondary) }
            HintText(text: outcome.launched
                ? L("TV'de GameTranslator açıldı. Çeviriyi başlattığınızda TV uygulaması Mac'i ağda kendisi bulur.",
                    "GameTranslator opened on the TV. When you start translation, the TV app finds the Mac on the network by itself.")
                : L("TV'nin uygulamalar listesinde GameTranslator'ı bulup açın. Çeviriyi başlattığınızda Mac'i kendisi bulur.",
                    "Find GameTranslator in the TV's app list and open it. It finds the Mac by itself when you start translation."))
            switch brand {
            case "lg":
                HintText(text: (outcome.hoursLeft.map { L("Kalan süre: \($0) saat. ", "Time left: \($0) hours. ") } ?? "") +
                    L("Süre dolarsa TV uygulaması silinir. TV'deki Developer Mode uygulamasında \"EXTEND\"e basın; buradan yeniden kurmak da süreyi uzatır.",
                      "When it runs out, the TV app is removed. Press \"EXTEND\" in the Developer Mode app on the TV; reinstalling from here also extends it."))
            case "samsung":
                HintText(text: L("Uygulama TV'de kalır. Güncellemeleri buradan kurarken TV'deki \"Host PC IP\" yine bu Mac'in adresi olmalı. Bu TV'nin sertifikası Mac'te saklandı; yeniden giriş istenmez.",
                                 "The app stays on the TV. When installing updates from here, the TV's \"Host PC IP\" must still be this Mac's address. This TV's certificate is saved on the Mac; you won't need to sign in again."))
            default:
                HintText(text: L("İsterseniz artık TV'de USB hata ayıklamayı kapatabilirsiniz. Yeni bir sürümü kurmak için yeniden açmanız gerekir.",
                                 "You can now turn off USB debugging on the TV if you like. You'll need to turn it on again to install a new version."))
            }
            Button(L("Bitti", "Done")) { resetWizard() }.buttonStyle(.secondary)
        }
    }
}
