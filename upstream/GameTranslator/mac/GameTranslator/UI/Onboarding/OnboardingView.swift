// SPDX-License-Identifier: AGPL-3.0-only

import GTNative
import SwiftUI

/// İlk kurulum sihirbazı (Windows: OnboardingView, telefon: setup_page.dart): hoş geldin, konsol seçimi, PS5 (ikinci
/// hesap, PSN girişi, eşleştirme) ya da Xbox (Microsoft girişi, konsol), TV uygulaması (isteğe bağlı), çeviri dili ve
/// motoru, deneme altyazısı. Sonunda `introSeen` true olur ve ana pencere açılır.
struct OnboardingView: View {
    @Environment(MacModel.self) private var model

    enum Step: Hashable {
        case welcome, console, psnWhy, psnLogin, pair, xboxSignIn, xboxConsole, tv, translation, test
    }

    @State private var step: Step = .welcome
    @State private var showLogin = false
    @State private var started = false

    private var flow: [Step] {
        let console: [Step] = model.isXbox ? [.xboxSignIn, .xboxConsole] : [.psnWhy, .psnLogin, .pair]
        return [.welcome, .console] + console + [.tv, .translation, .test]
    }

    private var index: Int { flow.firstIndex(of: step) ?? 0 }

    /// Bu adım tamamlanmadan ileri gidilemez.
    private var canContinue: Bool {
        _ = SetupSignal.shared.revision
        switch step {
        case .psnLogin: return model.hasAccount
        case .pair: return model.console != nil
        case .xboxSignIn: return model.xbox.signedIn
        case .xboxConsole: return model.xbox.selectedConsole != nil
        default: return true
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            progress
            Divider()
            ScrollView {
                content
                    .frame(maxWidth: 680, alignment: .leading)
                    .padding(.horizontal, Theme.space("xl"))
                    .padding(.vertical, Theme.space("xl"))
                    .frame(maxWidth: .infinity)
            }
            Divider()
            footer
        }
        .background(Color.csBackground)
        .onAppear(perform: chooseStart)
        .sheet(isPresented: $showLogin) {
            PSNLoginSheet { advance(from: .psnLogin) }
                .environment(model)
                .appSurface()
        }
    }

    /// Yarım kalmış bir kurulumda kalınan yerden başlanır.
    private func chooseStart() {
        guard !started else { return }
        started = true
        if model.ready {
            step = .tv
        } else if model.introSeen {
            // Ana pencereden bir şey unutulduysa (PS5, hesap, Xbox) doğrudan o adıma gidilir.
            if model.isXbox { step = model.xbox.signedIn ? .xboxConsole : .xboxSignIn }
            else { step = model.hasAccount ? .pair : .psnLogin }
        } else if model.isXbox, model.xbox.signedIn {
            step = .xboxConsole
        } else if !model.isXbox, model.hasAccount {
            step = .pair
        }
    }

    private func go(_ target: Step) { withAnimation(.easeInOut(duration: 0.2)) { step = target } }

    private func advance(from current: Step) {
        guard step == current, let position = flow.firstIndex(of: current), position + 1 < flow.count else { return }
        go(flow[position + 1])
    }

    private func finish() {
        model.introSeen = true
        model.refreshSetup()
    }

    // MARK: - Üst ve alt çubuk

    private var progress: some View {
        HStack(spacing: Theme.space("m")) {
            LogoView(size: 28)
            Text("GameTranslator").font(.csHeadline)
            Spacer()
            HStack(spacing: 6) {
                ForEach(Array(flow.enumerated()), id: \.offset) { position, _ in
                    Capsule()
                        .fill(position <= index ? Color.csAccent : Color.csBorder)
                        .frame(width: position == index ? 22 : 8, height: 8)
                }
            }
            Text(L("Adım \(index + 1) / \(flow.count)", "Step \(index + 1) of \(flow.count)"))
                .font(.csCaption).foregroundStyle(Color.csSecondary)
        }
        .padding(.horizontal, Theme.space("xl"))
        .padding(.vertical, Theme.space("m"))
    }

    private var footer: some View {
        HStack {
            if index > 0 {
                Button(L("Geri", "Back")) { go(flow[index - 1]) }.buttonStyle(.secondary)
            }
            Spacer()
            if step == .tv {
                Button(L("Şimdilik geç", "Skip for now")) { advance(from: .tv) }.buttonStyle(.link)
            }
            if step == .test {
                Button(L("Bitir", "Finish")) { finish() }
                    .buttonStyle(.primary)
                    .disabled(!model.ready)
                    .keyboardShortcut(.defaultAction)
            } else {
                Button(step == .welcome ? L("Başla", "Get started") : L("Devam", "Continue")) { advance(from: step) }
                    .buttonStyle(.primary)
                    .disabled(!canContinue)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(.horizontal, Theme.space("xl"))
        .padding(.vertical, Theme.space("m"))
    }

    // MARK: - Adımlar

    @ViewBuilder private var content: some View {
        switch step {
        case .welcome: welcome
        case .console: consoleChoice
        case .psnWhy: psnWhy
        case .psnLogin: psnLogin
        case .pair:
            page("link", L("PS5 ile eşleştir", "Pair with your PS5"),
                 L("Mac'i PS5'inizle bir kez eşleştirin. PS5 açık ve Mac ile aynı ağda olsun.", "Pair the Mac with your PS5 once. Keep the PS5 on and on the same network as the Mac.")) {
                PS5PairingPanel { advance(from: .pair) }
            }
        case .xboxSignIn:
            page("person.badge.key", L("Microsoft ile giriş yap", "Sign in with Microsoft"),
                 L("Xbox'ta oyun oynadığınız Microsoft hesabıyla giriş yapın. Mac Xbox'ın ev yayınını izler; ikinci hesap gerekmez, oyununuz kesilmez.",
                   "Sign in with the Microsoft account you play with on the Xbox. The Mac watches the Xbox's home stream; no second account is needed and your game isn't interrupted.")) {
                NoticeBox(text: L("Xbox desteği Beta: gerçek konsollarda yeni deneniyor. Bir şey çalışmazsa günlüğü bizimle paylaşın.",
                                  "Xbox support is in Beta and still being tested on real consoles. If something doesn't work, share the log with us."),
                          symbol: "flask", tone: .csAccentText)
                XboxSignInPanel { advance(from: .xboxSignIn) }
            }
        case .xboxConsole:
            page("gamecontroller", L("Xbox'ınızı seçin", "Choose your Xbox"),
                 L("Hesabınızdaki Xbox'lardan birini seçin.", "Choose one of the Xbox consoles on your account.")) {
                XboxConsolePanel(onChosen: { advance(from: .xboxConsole) }, onNeedsSignIn: { go(.xboxSignIn) })
            }
        case .tv:
            page("tv", L("TV uygulamasını kur", "Install the TV app"),
                 L("Altyazıyı oyunun üstünde gösteren GameTranslator TV uygulamasını Mac'ten TV'nize kurun. Mac ve TV aynı ağda olsun. Bu adımı geçip sonra Bağlantı bölümünden de yapabilirsiniz.",
                   "Install the GameTranslator TV app, which shows subtitles over the game, from the Mac onto your TV. Keep the Mac and the TV on the same network. You can skip this and do it later from Connection.")) {
                TvInstallPanel()
            }
        case .translation:
            page("character.book.closed", L("Çeviri", "Translation"),
                 L("Oyunun dilini, çeviri dilini ve çeviri motorunu seçin. Sonra Ayarlar'dan değiştirebilirsiniz.",
                   "Choose the game's language, the translation language and the engine. You can change these later in Settings.")) {
                TranslationQuickPicks()
            }
        case .test: test
        }
    }

    private func page<Content: View>(_ symbol: String, _ title: String, _ message: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: Theme.space("l")) {
            IconBadge(symbol: symbol)
            Text(verbatim: title).font(.csDisplay)
            Text(verbatim: message)
                .foregroundStyle(Color.csSecondary)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
            content().padding(.top, Theme.space("s"))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var welcome: some View {
        @Bindable var model = model
        return VStack(alignment: .leading, spacing: Theme.space("l")) {
            LogoView(size: 64)
            Text(L("GameTranslator'a hoş geldiniz", "Welcome to GameTranslator")).font(.csDisplay)
            Text(L("Konsoldaki oyunun altyazısını Mac okur, çevirir ve TV'de oyunun üstünde gösterir. Kurulum birkaç dakika sürer ve bir kez yapılır.",
                   "The Mac reads the game's subtitles from your console, translates them and shows them over the game on your TV. Setup takes a few minutes and is done once."))
                .foregroundStyle(Color.csSecondary).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
            TitledCard(title: L("Neler gerekli?", "What you need")) {
                VStack(alignment: .leading, spacing: Theme.space("s")) {
                    StepRow(number: 1, text: L("PS5 ve üzerinde ikinci bir kullanıcı, ya da Xbox ve Microsoft hesabınız.", "A PS5 with a second user on it, or an Xbox and your Microsoft account."))
                    StepRow(number: 2, text: L("GameTranslator TV uygulamasının kurulabildiği bir TV: LG, Samsung ya da Android TV / Google TV. Değilse ikinci ekranı kullanabilirsiniz.",
                                               "A TV that can run the GameTranslator TV app: LG, Samsung or Android TV / Google TV. Otherwise you can use a second screen."))
                    StepRow(number: 3, text: L("Mac, konsol ve TV'nin bağlı olduğu tek bir ev ağı.", "One home network that the Mac, the console and the TV are all on."))
                }
            }
            HStack {
                Text(L("Arayüz dili", "Interface language"))
                Picker("", selection: $model.settings.uiLanguage) {
                    Text(L("Mac'in dili", "Mac's language")).tag(UiText.auto)
                    Text("Türkçe").tag("tr")
                    Text("English").tag("en")
                }
                .labelsHidden()
                .frame(width: 180)
            }
        }
    }

    private var consoleChoice: some View {
        page("gamecontroller", L("Konsolunuz", "Your console"), L("Hangi konsolda oynuyorsunuz?", "Which console do you play on?")) {
            VStack(spacing: Theme.space("m")) {
                PickRow(title: "PS5", detail: L("Remote Play ile, ikinci bir PSN hesabıyla.", "With Remote Play, using a second PSN account."),
                        selected: !model.isXbox) { model.consoleKind = Store.kindPS5 }
                PickRow(title: "Xbox", detail: L("Ev yayınıyla, kendi Microsoft hesabınızla.", "With home streaming, using your own Microsoft account."),
                        badge: "Beta", badgeGood: false, selected: model.isXbox) { model.consoleKind = Store.kindXbox }
                HintText(text: L("Görüntüyü yakalama kartından, bir pencereden ya da ekrandan almak isterseniz kurulumdan sonra Bağlantı bölümünden seçebilirsiniz.",
                                 "To take the picture from a capture card, a window or a display instead, choose it in Connection after setup."))
            }
        }
    }

    private var psnWhy: some View {
        page("person.2", L("İkinci bir PSN hesabı", "A second PSN account"),
             L("PS5, Uzaktan Oynatma ile bağlanan cihazı o kullanıcının kumandası sayar. Mac sizin hesabınızla bağlanırsa elinizdeki kumanda TV'den kopar. Bu yüzden Mac ayrı bir kullanıcıyla bağlanır; siz kendi hesabınızla oynamaya devam edersiniz.",
               "The PS5 treats a device connected with Remote Play as that user's controller. If the Mac connects with your account, the controller in your hand gets disconnected from the TV. So the Mac connects as a separate user and you keep playing on your own account.")) {
            VStack(alignment: .leading, spacing: Theme.space("s")) {
                StepRow(number: 1, text: L("PS5'te yeni bir kullanıcı ekleyin (ana ekrandaki profil resminden Kullanıcı Değiştir › Kullanıcı Ekle).",
                                           "Add a new user on the PS5 (profile picture on the home screen › Switch User › Add User)."))
                StepRow(number: 2, text: L("Bu kullanıcıya bir PSN hesabıyla giriş yapın. Hesap ücretsiz açılabilir; PS Plus gerekmez.",
                                           "Sign this user in with a PSN account. The account can be created for free; PS Plus isn't needed."))
                StepRow(number: 3, text: L("Bu kullanıcıdayken Ayarlar › Sistem › Uzaktan Oynatma'da \"Uzaktan Oynatmayı Etkinleştir\"i açın.",
                                           "While on this user, turn on \"Enable Remote Play\" in Settings › System › Remote Play."))
                StepRow(number: 4, text: L("Dinlenme modundan uyandırmak için: Ayarlar › Sistem › Güç Tasarrufu › Dinlenme Modunda Kullanılabilen Özellikler'de \"İnternete Bağlı Kal\" ve \"PS5'in Ağdan Açılmasını Etkinleştir\"i açın (isteğe bağlı).",
                                           "To wake it from rest mode: in Settings › System › Power Saving › Features Available in Rest Mode, turn on \"Stay Connected to the Internet\" and \"Enable Turning On PS5 from Network\" (optional)."))
            }
        }
    }

    private var psnLogin: some View {
        page("person.crop.circle.badge.checkmark", L("PSN ile giriş yap", "Sign in with PSN"),
             L("PS5'teki ikinci kullanıcının PSN hesabıyla giriş yapın.", "Sign in with the PSN account of the second user on the PS5.")) {
            VStack(alignment: .leading, spacing: Theme.space("m")) {
                if model.hasAccount {
                    DoneLabel(text: L("PSN hesabı bağlandı.", "PSN account linked."))
                    Button(L("Başka bir hesapla giriş yap", "Sign in with another account")) {
                        model.forgetAccount()
                        model.refreshSetup()
                        showLogin = true
                    }
                    .buttonStyle(.link)
                } else {
                    Button(L("PSN ile giriş yap", "Sign in with PSN")) { showLogin = true }.buttonStyle(.primary)
                }
                HintText(text: L("Giriş Sony'nin kendi sayfasında yapılır. Şifreniz bu uygulamaya ya da başka bir yere kaydedilmez; yalnızca hesabın kimlik numarası saklanır.",
                                 "You sign in on Sony's own page. Your password isn't stored by this app or anywhere else; only the account's ID number is kept."))
            }
        }
    }

    private var test: some View {
        page("checkmark.seal", L("Deneme", "Test"),
             L("TV'de GameTranslator uygulamasını açın ve bir deneme altyazısı gönderin. TV'de yazıyı görüyorsanız her şey hazır.",
               "Open the GameTranslator app on the TV and send a test subtitle. If you see the text on the TV, everything is ready.")) {
            VStack(alignment: .leading, spacing: Theme.space("m")) {
                StatusRow(title: "TV", value: model.state.tvClients > 0 ? L("Bağlı", "Connected") : L("Bekleniyor", "Waiting"),
                          tone: model.state.tvClients > 0 ? .good : .idle)
                Button(L("TV'ye deneme altyazısı gönder", "Send a test subtitle to the TV")) { model.sendTestSubtitle() }
                    .buttonStyle(.secondary)
                OtherServerNotice()
                HintText(text: L("TV'de oyun görüntüsü gelmezse TV uygulamasında Tamam tuşuyla paneli açıp sol/sağ ile konsolun bağlı olduğu HDMI girişini seçin. TV'niz desteklenmiyorsa Bağlantı › İkinci ekran'ı kullanabilirsiniz.",
                                 "If the game picture doesn't show on the TV, open the panel in the TV app with OK and choose the console's HDMI input with left/right. If your TV isn't supported, use Connection › Second screen."))
                HintText(text: L("Çeviriyi ana sayfadaki büyük düğmeyle başlatırsınız. Uygulama arka planda kalsa da Mac çeviriye devam eder; çeviri açıkken Mac uyumaz.",
                                 "You start translation with the big button on Home. The Mac keeps translating even when the app is in the background, and it doesn't sleep while translating."))
            }
        }
    }
}

/// Çeviri dili, oyunun dili ve motor için hızlı seçim (kurulum sihirbazı).
struct TranslationQuickPicks: View {
    @Environment(MacModel.self) private var model

    private func name(_ code: String) -> String {
        guard let language = Languages.find(code) else { return code }
        let local = UILanguage.isTurkish ? language.turkishName : language.englishName
        return local == language.nativeName ? local : "\(local) · \(language.nativeName)"
    }

    var body: some View {
        @Bindable var model = model
        let target = model.settings.targetLanguage
        VStack(alignment: .leading, spacing: Theme.space("l")) {
            Grid(alignment: .leading, horizontalSpacing: Theme.space("l"), verticalSpacing: Theme.space("m")) {
                GridRow {
                    Text(L("Oyunun dili", "Game language"))
                    Picker("", selection: $model.settings.sourceLanguage) {
                        ForEach(Languages.sources, id: \.self) { Text(verbatim: name($0)).tag($0) }
                    }
                    .labelsHidden().frame(width: 260)
                }
                GridRow {
                    Text(L("Çeviri dili", "Translate into"))
                    Picker("", selection: $model.settings.targetLanguage) {
                        ForEach(Languages.targets, id: \.code) { Text(verbatim: name($0.code)).tag($0.code) }
                    }
                    .labelsHidden().frame(width: 260)
                }
            }
            VStack(alignment: .leading, spacing: Theme.space("s")) {
                Text(L("ÇEVİRİ MOTORU", "TRANSLATION ENGINE")).font(.csCaption.weight(.semibold)).foregroundStyle(Color.csSecondary)
                PickRow(title: "Google", detail: L("İnternet ister, anahtar gerekmez. Çoğu oyun için en iyi başlangıç.", "Needs internet, no key. The best start for most games."),
                        selected: model.settings.engine == "google") { model.settings.engine = "google" }
                PickRow(title: "DeepL", detail: Languages.supports(engine: "deepl", target: target)
                    ? L("Daha doğal cümleler. DeepL'den aldığınız API anahtarı gerekir.", "More natural sentences. Needs your own DeepL API key.")
                    : L("Bu dile çeviremiyor; Google kullanılır.", "Can't translate into this language; Google is used."),
                        selected: model.settings.engine == "deepl") { model.settings.engine = "deepl" }
                if model.settings.engine == "deepl" {
                    SecureField(L("DeepL API anahtarı", "DeepL API key"), text: $model.settings.deepLKey)
                        .textFieldStyle(CSTextFieldStyle())
                }
                PickRow(title: L("Bu Mac'te (Apple Çeviri)", "On this Mac (Apple Translation)"),
                        detail: Languages.supports(engine: "mlkit", target: target)
                            ? L("İnternetsiz çalışır; kalitesi daha düşüktür. Dil modeli ilk kullanımda bir kez iner.", "Works offline; lower quality. The language model downloads once on first use.")
                            : L("Bu dile çeviremiyor; Google kullanılır.", "Can't translate into this language; Google is used."),
                        selected: model.settings.engine == "mlkit") { model.settings.engine = "mlkit" }
                HintText(text: L("İnternet koparsa çeviri kendiliğinden Mac'teki modele geçer.", "If the internet drops, translation switches to the on-device model by itself."))
            }
        }
    }
}
