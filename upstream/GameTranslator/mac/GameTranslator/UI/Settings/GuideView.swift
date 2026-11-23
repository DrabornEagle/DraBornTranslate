// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Rehber (telefon: guide_page.dart, Windows: MainView.Guide): kurulumdan sorun gidermeye kısa bölümler. Mac'e
/// uyarlanmıştır: bildirim ve arka plan servisi yok; uygulama arka planda kalsa da Mac çeviriye devam eder.
struct GuideView: View {
    @Environment(MacModel.self) private var model
    @Environment(Navigation.self) private var navigation
    @State private var selected = "how"

    var body: some View {
        let chapters = GuideContent.chapters(address: model.macAddress ?? L("Mac'in adresi", "the Mac's address"), port: model.serverPortOrDefault)
        HStack(spacing: 0) {
            List(selection: $selected) {
                ForEach(GuideContent.groups(chapters), id: \.title) { group in
                    SwiftUI.Section(group.title) {
                        ForEach(group.chapters) { chapter in
                            Label(chapter.title, systemImage: chapter.symbol).tag(chapter.id)
                        }
                    }
                }
            }
            .listStyle(.sidebar)
            .frame(width: 250)
            Divider()
            ScrollViewReader { proxy in
                ScrollView {
                    if let chapter = chapters.first(where: { $0.id == selected }) ?? chapters.first {
                        chapterView(chapter, next: next(after: chapter, in: chapters))
                            .id(chapter.id)
                            .frame(maxWidth: 720, alignment: .leading)
                            .padding(Theme.space("xl"))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .onChange(of: selected) { _, value in proxy.scrollTo(value, anchor: .top) }
            }
        }
    }

    private func next(after chapter: GuideChapter, in chapters: [GuideChapter]) -> GuideChapter? {
        guard let index = chapters.firstIndex(where: { $0.id == chapter.id }), index + 1 < chapters.count else { return nil }
        return chapters[index + 1]
    }

    private func chapterView(_ chapter: GuideChapter, next: GuideChapter?) -> some View {
        VStack(alignment: .leading, spacing: Theme.space("l")) {
            HStack(spacing: Theme.space("m")) {
                IconBadge(symbol: chapter.symbol)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: chapter.title).font(.csTitle)
                    Text(verbatim: chapter.subtitle).foregroundStyle(Color.csSecondary)
                }
            }
            ForEach(Array(chapter.blocks.enumerated()), id: \.offset) { _, block in
                blockView(block)
            }
            if let action = chapter.action {
                Button(action.title) { navigation.section = action.section }.buttonStyle(.secondary)
            }
            if let next {
                Divider().padding(.top, Theme.space("m"))
                Button {
                    selected = next.id
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("SONRAKİ BÖLÜM", "NEXT CHAPTER")).font(.csCaption.weight(.semibold)).foregroundStyle(Color.csSecondary)
                            Text(verbatim: next.title).font(.csHeadline).foregroundStyle(Color.csText)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(Color.csSecondary)
                    }
                    .padding(Theme.space("m"))
                    .background(Color.csSurface, in: RoundedRectangle(cornerRadius: Theme.radius("control")))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder private func blockView(_ block: GuideBlock) -> some View {
        switch block {
        case let .text(text):
            Text(verbatim: text).lineSpacing(3).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
        case let .heading(text):
            Text(verbatim: text).font(.csHeadline).padding(.top, Theme.space("s"))
        case let .steps(items):
            VStack(alignment: .leading, spacing: Theme.space("s")) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in StepRow(number: index + 1, text: item) }
            }
        case let .bullets(items):
            VStack(alignment: .leading, spacing: Theme.space("s")) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    HStack(alignment: .firstTextBaseline, spacing: Theme.space("s")) {
                        Text(verbatim: "•").foregroundStyle(Color.csAccentText)
                        Text(verbatim: item).lineSpacing(3).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                    }
                }
            }
        case let .note(text):
            NoticeBox(text: text, symbol: "lightbulb", tone: .csAccentText)
        case let .problem(title, items):
            VStack(alignment: .leading, spacing: Theme.space("s")) {
                Text(verbatim: title).font(.csBody.weight(.semibold))
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    HStack(alignment: .firstTextBaseline, spacing: Theme.space("s")) {
                        Image(systemName: "checkmark.circle").font(.csCallout).foregroundStyle(Color.csSecondary)
                        Text(verbatim: item).font(.csCallout).lineSpacing(2).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                    }
                }
            }
            .padding(Theme.space("m"))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.csSurface, in: RoundedRectangle(cornerRadius: Theme.radius("control")))
        case let .table(rows):
            Grid(alignment: .leading, horizontalSpacing: Theme.space("l"), verticalSpacing: Theme.space("s")) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    GridRow {
                        Text(verbatim: row.0).font(.csBody.weight(.semibold))
                        Text(verbatim: row.1).foregroundStyle(Color.csSecondary).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .padding(Theme.space("m"))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.csSurface, in: RoundedRectangle(cornerRadius: Theme.radius("control")))
        }
    }
}

enum GuideBlock {
    case text(String)
    case heading(String)
    case steps([String])
    case bullets([String])
    case note(String)
    case problem(String, [String])
    case table([(String, String)])
}

struct GuideChapter: Identifiable {
    let id: String
    let group: String
    let symbol: String
    let title: String
    let subtitle: String
    let blocks: [GuideBlock]
    var action: (title: String, section: Section)?
}

/// Rehberin metni (telefondaki l10n 'guide.*' tablolarından, Mac'e uyarlanmış).
@MainActor
enum GuideContent {
    static func groups(_ chapters: [GuideChapter]) -> [(title: String, chapters: [GuideChapter])] {
        var order: [String] = []
        var map: [String: [GuideChapter]] = [:]
        for chapter in chapters {
            if map[chapter.group] == nil { order.append(chapter.group) }
            map[chapter.group, default: []].append(chapter)
        }
        return order.map { ($0, map[$0] ?? []) }
    }

    static func chapters(address: String, port: Int) -> [GuideChapter] {
        let start = L("Başlangıç", "Getting started")
        let use = L("Kullanım", "Using it")
        let help = L("Yardım", "Help")
        return [
            GuideChapter(id: "how", group: start, symbol: "sparkles", title: L("Nasıl çalışır?", "How it works"),
                         subtitle: L("Bir bakışta bütün düzen", "The whole setup at a glance"), blocks: [
                .text(L("GameTranslator konsolu, Mac'i ve TV'yi birbirine bağlar. Oyun konsolda çalışır, siz TV'de oynarsınız; Mac arada çevirmenlik yapar:",
                        "GameTranslator links the console, the Mac and the TV. The game runs on the console and you play on the TV; the Mac translates in between:")),
                .steps([
                    L("Mac oyun görüntüsünü PS5'ten Remote Play ile, Xbox'tan (Beta) ev yayınıyla alır. İsterseniz görüntüyü bir yakalama kartından, bir pencereden ya da ekrandan da alabilir (Bağlantı › Görüntü kaynağı).",
                      "The Mac gets the game picture from the PS5 over Remote Play, or from the Xbox (Beta) over home streaming. It can also take it from a capture card, a window or a display (Connection › Video source)."),
                    L("Görüntüde altyazının çıktığı bölgeyi okur ve yazıyı çevirir.", "It reads the area of the picture where subtitles appear and translates the text."),
                    L("Çeviriyi aynı ağdaki TV'ye gönderir; TV'deki GameTranslator uygulaması onu oyunun üstünde gösterir. Çeviri ayrıca ikinci ekranda, Mac ekranında ve OBS'te de gösterilebilir.",
                      "It sends the translation to the TV on the same network, where the GameTranslator TV app shows it over the game. It can also be shown on a second screen, on the Mac's screen and in OBS."),
                ]),
                .heading(L("Neler gerekli?", "What you need")),
                .bullets([
                    L("PS5 ve üzerinde ikinci bir kullanıcı, ya da Xbox ve Microsoft hesabınız.", "A PS5 with a second user on it, or an Xbox and your Microsoft account."),
                    L("GameTranslator TV uygulamasının kurulabildiği bir TV: LG, Samsung ya da Android TV / Google TV. Değilse ikinci ekranı kullanın.",
                      "A TV that can run the GameTranslator TV app: LG, Samsung or Android TV / Google TV. Otherwise use a second screen."),
                    L("Mac, konsol ve TV'nin bağlı olduğu tek bir ev ağı.", "One home network for the Mac, the console and the TV."),
                    L("Çevrimiçi çeviri için internet. İnternetsiz çalışan Apple Çeviri de seçilebilir.", "Internet for online translation. Apple Translation also works offline."),
                ]),
                .note(L("Kurulum bir kez yapılır. Sonrasında tek yapacağınız: çeviriyi başlatmak ve TV'de uygulamayı açmak.",
                        "Setup is done once. After that you just start translation and open the app on the TV.")),
            ]),
            GuideChapter(id: "ps5", group: start, symbol: "gamecontroller", title: L("İlk kurulum: PS5", "First-time setup: PS5"),
                         subtitle: L("İkinci kullanıcı, Uzaktan Oynatma ve eşleştirme", "Second user, Remote Play and pairing"), blocks: [
                .heading(L("1. İkinci bir kullanıcı ekleyin", "1. Add a second user")),
                .text(L("PS5, Uzaktan Oynatma ile bağlanan cihazı o kullanıcının kumandası sayar. Mac sizin hesabınızla bağlanırsa elinizdeki kumanda TV'den kopar. Bu yüzden Mac ayrı bir kullanıcıyla bağlanır; siz kendi hesabınızla oynamaya devam edersiniz.",
                        "The PS5 treats a device connected over Remote Play as that user's controller. If the Mac connects with your account, the controller in your hand drops off the TV. So the Mac connects as a separate user and you keep playing on your own account.")),
                .steps([
                    L("PS5'te yeni bir kullanıcı ekleyin (ana ekrandaki profil resmi › Kullanıcı Değiştir › Kullanıcı Ekle).", "Add a new user on the PS5 (home screen profile picture › Switch User › Add User)."),
                    L("Bu kullanıcıya bir PSN hesabıyla giriş yapın. Hesap ücretsiz açılabilir; PS Plus gerekmez.", "Sign this user in with a PSN account. It can be created for free; PS Plus isn't needed."),
                    L("Mac'te PSN girişini de bu ikinci hesapla yapın.", "Use this second account for the PSN sign-in on the Mac too."),
                ]),
                .heading(L("2. Uzaktan Oynatmayı açın", "2. Turn on Remote Play")),
                .steps([
                    L("PS5'te ikinci kullanıcıya geçin.", "Switch to the second user on the PS5."),
                    L("Ayarlar › Sistem › Uzaktan Oynatma'da \"Uzaktan Oynatmayı Etkinleştir\"i açın.", "Turn on \"Enable Remote Play\" in Settings › System › Remote Play."),
                    L("Dinlenme modundan uyandırmak için Ayarlar › Sistem › Güç Tasarrufu › Dinlenme Modunda Kullanılabilen Özellikler'de \"İnternete Bağlı Kal\" ve \"PS5'in Ağdan Açılmasını Etkinleştir\"i açın.",
                      "To wake it from rest mode, turn on \"Stay Connected to the Internet\" and \"Enable Turning On PS5 from Network\" in Settings › System › Power Saving › Features Available in Rest Mode."),
                ]),
                .heading(L("3. Mac'i eşleştirin", "3. Pair the Mac")),
                .steps([
                    L("Kurulumda \"PSN ile giriş yap\"a basın; Sony'nin kendi sayfası açılır. İkinci kullanıcının hesabıyla giriş yapın. Şifreniz doğrudan Sony'ye gider; uygulama yalnızca hesabın kimlik numarasını saklar.",
                      "In setup press \"Sign in with PSN\"; Sony's own page opens. Sign in with the second user's account. Your password goes straight to Sony; the app only keeps the account's ID number."),
                    L("PS5'te ikinci kullanıcıdayken Ayarlar › Sistem › Uzaktan Oynatma › \"Cihaz Eşleştir\"e girin. 8 haneli kod yaklaşık 5 dakika geçerlidir.",
                      "On the PS5, as the second user, open Settings › System › Remote Play › \"Link Device\". The 8-digit code is valid for about 5 minutes."),
                    L("Mac'te bulunan PS5'i seçin (yoksa IP adresini yazın: PS5'te Ayarlar › Ağ › Bağlantı Durumu), kodu girip \"Eşleştir\"e basın.",
                      "On the Mac choose the PS5 found (or type its IP address: on the PS5, Settings › Network › Connection Status), enter the code and press \"Pair\"."),
                ]),
                .note(L("Eşleştirme bir kez yapılır. PS5'in adresi değişirse uygulama onu ağda yeniden bulur. Yeniden eşleştirmek için: Bağlantı › PS5.",
                        "Pairing is done once. If the PS5's address changes, the app finds it again. To pair again: Connection › PS5.")),
            ], action: (L("Bağlantı'yı aç", "Open Connection"), .connection)),
            GuideChapter(id: "xbox", group: start, symbol: "gamecontroller.fill", title: L("İlk kurulum: Xbox (Beta)", "First-time setup: Xbox (Beta)"),
                         subtitle: L("Ev yayınıyla, kendi hesabınızla", "Home streaming, with your own account"), blocks: [
                .text(L("Mac, Xbox'ın ev yayınını Microsoft hesabınızla açar, görüntüde altyazıyı okur, çevirir ve TV'ye gönderir. Mac yalnızca izler, kumanda girdisi göndermez; PS5'teki gibi ikinci hesap gerekmez.",
                        "The Mac opens the Xbox home stream with your Microsoft account, reads the subtitles, translates them and sends them to the TV. The Mac only watches and sends no controller input, so no second account is needed.")),
                .heading(L("Xbox'ı hazırlayın (bir kez)", "Prepare the Xbox (once)")),
                .steps([
                    L("Xbox'ta Ayarlar › Cihazlar ve bağlantılar › Uzak özellikler'e girin.", "On the Xbox, open Settings › Devices & connections › Remote features."),
                    L("\"Uzak özellikleri etkinleştir\"i işaretleyin.", "Check \"Enable remote features\"."),
                    L("Güç seçeneklerinde \"Uyku\"yu seçin; \"Kapat (enerji tasarrufu)\" seçiliyken uzaktan bağlanılamaz.", "Choose \"Sleep\" in power options; with \"Shut down (energy saving)\" it can't be reached remotely."),
                ]),
                .heading(L("Mac'te bağlayın (bir kez)", "Link it on the Mac (once)")),
                .steps([
                    L("Kurulumda Xbox'ı seçip \"Microsoft ile giriş yap\"a basın. Ekranda bir kod görünür.", "In setup choose Xbox and press \"Sign in with Microsoft\". A code appears."),
                    L("Açılan Microsoft sayfasına (microsoft.com/link) kodu yapıştırıp Xbox'taki hesabınızla giriş yapın.", "Paste the code on the Microsoft page that opens (microsoft.com/link) and sign in with your Xbox account."),
                    L("Uygulamaya dönün, hesabınızdaki Xbox'ı seçip \"Bu Xbox'ı kullan\"a basın.", "Return to the app, choose your Xbox and press \"Use this Xbox\"."),
                ]),
                .bullets([
                    L("Bulut oyunları (Xbox Cloud Gaming) okunmaz; bu yol kendi konsolunuzdan ev yayınıdır.", "Cloud games (Xbox Cloud Gaming) aren't read; this is home streaming from your own console."),
                    L("Xbox aynı anda tek bir uzaktan oynatma kabul eder; çeviri açıkken Xbox uygulamasından uzaktan oynatmayın.", "The Xbox accepts one remote play session at a time; don't remote play from the Xbox app while translating."),
                    L("Yayın görüntüsü sıkıştırılmıştır; küçük altyazılarda oyunda altyazı boyutunu büyütün.", "The stream is compressed; for small subtitles, increase the subtitle size in the game."),
                ]),
            ]),
            GuideChapter(id: "first", group: start, symbol: "play.circle", title: L("İlk kullanım", "First use"),
                         subtitle: L("Çeviriyi başlatın ve TV'de görün", "Start translating and see it on the TV"), blocks: [
                .steps([
                    L("Konsolda oyunu kendi hesabınızla açın ve oyunun ayarlarından altyazıları açın.", "Open the game on the console with your own account and turn on subtitles in its settings."),
                    L("Mac'te Ana Sayfa'daki büyük düğmeye basın (ya da ⇧⌘T). İlk seferde yerel ağ izni sorulursa izin verin ki Mac konsolu ve TV'yi bulabilsin.",
                      "On the Mac press the big button on Home (or ⇧⌘T). If asked for local network access the first time, allow it so the Mac can find the console and the TV."),
                    L("Durum \"Çeviri açık\" olunca konsol kutusu \"Bağlı\" yazar. İlk bağlantı birkaç saniye sürer.", "When the status says \"Translation is on\", the console tile shows \"Connected\". The first connection takes a few seconds."),
                    L("TV'de GameTranslator uygulamasını açın. Birkaç saniye içinde TV kutusu \"Bağlı\" olur.", "Open the GameTranslator app on the TV. Within a few seconds the TV tile shows \"Connected\"."),
                    L("TV'de oyun görüntüsü yoksa Tamam ile paneli açıp sol/sağ ile konsolun HDMI girişini seçin.", "If the TV shows no game picture, open the panel with OK and choose the console's HDMI input with left/right."),
                    L("\"TV'ye deneme altyazısı gönder\"e basın. TV'de yazıyı görüyorsanız her şey hazır.", "Press \"Send a test subtitle to the TV\". If you see it on the TV, you're all set."),
                ]),
                .note(L("Uygulama arka planda kalsa ya da pencere kapansa da Mac çeviriye devam eder; çeviri açıkken Mac uyumaz. Durdurmak için düğmeye ya da Çeviri menüsüne basın. Mac'in ekranı kararabilir; kapağı kapatırsanız Mac uyuyabilir.",
                        "The Mac keeps translating even when the app is in the background or its window is closed, and it doesn't sleep while translating. Stop it with the button or the Translation menu. The display may dim; closing the lid can put the Mac to sleep.")),
            ], action: (L("Ana Sayfa'yı aç", "Open Home"), .home)),
            GuideChapter(id: "tv", group: start, symbol: "tv", title: L("TV'yi hazırlayın", "Set up the TV"),
                         subtitle: L("Desteklenen TV'ler ve markaya göre kurulum", "Supported TVs and setup by brand"), blocks: [
                .text(L("Altyazıyı TV'de gösteren ayrı bir GameTranslator TV uygulaması vardır. Mağazalarda olmadığı için TV'ye bir kez Mac'ten kurulur (Bağlantı › TV uygulaması).",
                        "A separate GameTranslator TV app shows subtitles on the TV. It isn't in the stores, so it's installed once from the Mac (Connection › TV app).")),
                .table([
                    ("LG", L("webOS 4 ve üstü", "webOS 4 and later")),
                    ("Samsung", L("Tizen, 2017 ve sonrası", "Tizen, 2017 and later")),
                    ("Android TV / Google TV", L("Sony, TCL, Philips, Xiaomi, Vestel, Arçelik, Beko, Grundig ve diğerleri", "Sony, TCL, Philips, Xiaomi, Vestel, Arçelik, Beko, Grundig and others")),
                    (L("Desteklenmeyen", "Not supported"), L("Vestel/Arçelik/Beko/Grundig'in Linux modelleri, Philips Saphi, Titan OS", "Linux models from Vestel/Arçelik/Beko/Grundig, Philips Saphi, Titan OS")),
                ]),
                .heading("LG"),
                .steps([
                    L("webostv.developer.lge.com adresinde ücretsiz bir LG geliştirici hesabı açın.", "Create a free LG developer account at webostv.developer.lge.com."),
                    L("TV'de LG Content Store'dan \"Developer Mode\" uygulamasını kurun, hesabınızla giriş yapıp \"Dev Mode Status\"u açın. TV yeniden başlar.",
                      "On the TV install \"Developer Mode\" from the LG Content Store, sign in and turn on \"Dev Mode Status\". The TV restarts."),
                    L("Developer Mode uygulamasında \"Key Server\"ı açın.", "Turn on \"Key Server\" in the Developer Mode app."),
                    L("Mac'te LG'yi seçin, TV'yi bulun ve ekrandaki 6 karakterli parolayı yazıp kurun.", "On the Mac choose LG, find the TV, type the 6-character passphrase shown on screen and install."),
                ]),
                .note(L("LG'de geliştirici modu en çok 1000 saat açık kalır. Süre dolmadan Developer Mode uygulamasında \"EXTEND\"e basın; süre dolarsa TV uygulaması silinir. Mac'ten yeniden kurmak da süreyi uzatır.",
                        "On LG, developer mode lasts up to 1000 hours. Press \"EXTEND\" in the Developer Mode app before it runs out, or the TV app is removed. Reinstalling from the Mac also extends it.")),
                .heading("Samsung"),
                .steps([
                    L("TV'de Uygulamalar ekranını açıp kumandayla 1 2 3 4 5'e basın. Geliştirici modu penceresi açılır.", "Open the Apps screen on the TV and press 1 2 3 4 5 on the remote. The developer mode window opens."),
                    L("\"Developer mode\"u açın ve \"Host PC IP\" alanına bu Mac'in adresini (\(address)) yazın.", "Turn on \"Developer mode\" and type this Mac's address (\(address)) in \"Host PC IP\"."),
                    L("TV'yi güç tuşunu basılı tutarak tamamen kapatıp açın; kısa basmak yetmez.", "Turn the TV fully off and on by holding the power button; a short press isn't enough."),
                    L("Mac'te Samsung'u seçin. Ücretsiz bir Samsung hesabıyla bir kez giriş yapın (varsayılan tarayıcıda açılır); uygulama bu TV için imzalanıp kurulur.",
                      "On the Mac choose Samsung. Sign in once with a free Samsung account (opens in your default browser); the app is signed for this TV and installed."),
                ]),
                .heading("Android TV / Google TV"),
                .steps([
                    L("TV'de Ayarlar › Cihaz Tercihleri › Hakkında'ya girin (Google TV: Ayarlar › Sistem › Hakkında).", "On the TV open Settings › Device Preferences › About (Google TV: Settings › System › About)."),
                    L("\"Yapı numarası\" satırında Tamam'a 7 kez basın.", "Press OK 7 times on \"Build number\"."),
                    L("Geliştirici seçenekleri'nden \"USB hata ayıklama\"yı (varsa \"Ağ hata ayıklama\"yı da) açın.", "In Developer options turn on \"USB debugging\" (and \"Network debugging\" if present)."),
                    L("Mac'te Android TV'yi seçip kurun ve TV'deki izin sorusunu \"Her zaman izin ver\" ile onaylayın.", "On the Mac choose Android TV, install, and accept the prompt on the TV with \"Always allow\"."),
                ]),
                .text(L("Kurulduktan sonra TV uygulaması Mac'i ağda kendisi bulur; TV'nin adresini yazmanız gerekmez.", "Once installed, the TV app finds the Mac on the network by itself; you don't need to type the TV's address.")),
            ], action: (L("TV uygulamasını kur", "Install the TV app"), .connection)),
            GuideChapter(id: "remote", group: use, symbol: "appletvremote.gen4", title: L("TV kumandası", "TV remote"),
                         subtitle: L("TV uygulamasındaki tuşlar", "Keys in the TV app"), blocks: [
                .table([
                    (L("Tamam / Yeşil", "OK / Green"), L("Durum panelini açar ve kapatır (HDMI girişi, bağlantı).", "Opens and closes the status panel (HDMI input, connection).")),
                    (L("Sol / Sağ", "Left / Right"), L("Panel açıkken HDMI girişini değiştirir.", "Changes the HDMI input while the panel is open.")),
                    ("1 – 4", L("HDMI girişini doğrudan seçer.", "Selects the HDMI input directly.")),
                    (L("Yukarı / Aşağı", "Up / Down"), L("Altyazıyı ekranda yukarı ya da aşağı kaydırır.", "Moves the subtitle up or down.")),
                    (L("Kırmızı", "Red"), L("TV'nin kendi deneme yazısını gösterir.", "Shows the TV's own test text.")),
                    (L("Mavi", "Blue"), L("Örnek altyazılarla tanıtımı başlatır ya da durdurur.", "Starts or stops the demo with sample subtitles.")),
                    (L("Geri", "Back"), L("Uygulamadan çıkar.", "Exits the app.")),
                ]),
                .text(L("Oyunu konsolun kumandasıyla oynarsınız; TV kumandası yalnızca bu ayarlar için gerekir.", "You play with the console's controller; the TV remote is only for these settings.")),
            ]),
            GuideChapter(id: "region", group: use, symbol: "viewfinder", title: L("Okuma alanı ve çeviri", "Reading area and translation"),
                         subtitle: L("Mac'in ekranda nereye bakacağı, motor ve görünüm", "Where the Mac looks, engine and appearance"), blocks: [
                .text(L("Mac bütün ekranı değil, yalnızca altyazının çıktığı bölgeyi okur. Bölge yanlışsa çeviri gelmez ya da başka yazılar çevrilir.",
                        "The Mac reads only the area where subtitles appear, not the whole screen. If the area is wrong, nothing gets translated or other text does.")),
                .bullets([
                    L("Cümlenin başı ya da sonu kesiliyorsa alanı genişletin; iki satırda üst satır kayboluyorsa üst kenarı yukarı alın.", "If sentences are cut off, widen the area; if the top line of two is lost, raise the top edge."),
                    L("Görev yazıları, harita adları gibi başka yazılar çevriliyorsa alanı daraltın.", "If other text like quests or map names gets translated, narrow the area."),
                    L("Her oyun altyazıyı farklı yere koyar; yeni bir oyunda alana bir kez bakmak yeter.", "Every game places subtitles differently; check the area once per new game."),
                ]),
                .table([
                    ("Google", L("İnternet ister, anahtar gerekmez. Çoğu oyun için en iyi başlangıç.", "Needs internet, no key. The best start for most games.")),
                    ("DeepL", L("Daha doğal cümleler; kendi API anahtarınız gerekir.", "More natural sentences; needs your own API key.")),
                    (L("Bu Mac'te", "On this Mac"), L("Apple Çeviri, internetsiz çalışır; kalitesi daha düşüktür.", "Apple Translation, works offline; lower quality.")),
                ]),
                .text(L("İngilizce → Türkçede hazır oyun paketleri ve terim sözlüğü de kullanılır (Çeviri ve Paketler).", "For English → Turkish, ready-made game packs and the glossary are also used (Translation & Packs).")),
            ], action: (L("Okuma Alanı'nı aç", "Open Reading Area"), .reading)),
            GuideChapter(id: "screens", group: use, symbol: "ipad.and.iphone", title: L("İkinci ekran ve OBS", "Second screen and OBS"),
                         subtitle: L("TV'niz desteklenmiyorsa ya da yayın yapıyorsanız", "If your TV isn't supported or you stream"), blocks: [
                .text(L("TV'nize uygulama kurulamıyorsa çeviriyi TV'nin altına koyduğunuz bir tablet, telefon ya da dizüstünün tarayıcısında okuyabilirsiniz. Her şey evdeki ağda kalır; uygulama kurmak gerekmez.",
                        "If the app can't be installed on your TV, read the translation in the browser of a tablet, phone or laptop placed under the TV. Everything stays on your home network; no app is needed.")),
                .steps([
                    L("Mac'te çeviriyi başlatın.", "Start translation on the Mac."),
                    L("Aynı ağdaki cihazın tarayıcısında http://\(address):\(port)/ekran adresini açın ya da Bağlantı'daki QR kodunu okutun.",
                      "Open http://\(address):\(port)/ekran in the browser of a device on the same network, or scan the QR code in Connection."),
                    L("Adres çubuğunu gizlemek için sayfadaki \"Tam ekran\"a dokunun.", "Tap \"Full screen\" on the page to hide the address bar."),
                ]),
                .heading(L("Yayın yapanlar için: OBS", "For streamers: OBS")),
                .text(L("OBS'te \"Tarayıcı Kaynağı\" ekleyip adres olarak http://\(address):\(port)/obs yazın (1920×1080). Sayfanın zemini saydamdır; yalnızca yayın ve kayıt düzenleri içindir.",
                        "In OBS add a \"Browser Source\" with http://\(address):\(port)/obs (1920×1080). The page is transparent and meant for stream and recording layouts.")),
            ], action: (L("Bağlantı'yı aç", "Open Connection"), .connection)),
            GuideChapter(id: "trouble", group: help, symbol: "wrench.and.screwdriver", title: L("Sorun giderme", "Troubleshooting"),
                         subtitle: L("Sık karşılaşılan durumlar", "Common situations"), blocks: [
                .problem(L("Çeviri başlamıyor, \"Bağlanıyor\"da kalıyor", "Translation won't start, stuck on \"Connecting\""), [
                    L("PS5 açık mı? Dinlenme modundaysa uyandırma ayarlarını \"İlk kurulum: PS5\" bölümündeki gibi açın.", "Is the PS5 on? If it's in rest mode, enable waking as in \"First-time setup: PS5\"."),
                    L("Mac ile PS5 aynı ağda mı? Misafir ağı olmamalı.", "Are the Mac and the PS5 on the same network? Not a guest network."),
                    L("PS5'te Uzaktan Oynatma açık mı?", "Is Remote Play enabled on the PS5?"),
                ]),
                .problem(L("Mac PS5'i ya da TV'yi ağda görmüyor", "The Mac can't see the PS5 or the TV on the network"), [
                    L("Sistem Ayarları › Gizlilik ve Güvenlik › Yerel Ağ'da GameTranslator açık olmalı. Kapalıysa açın ve uygulamayı yeniden başlatın.",
                      "GameTranslator must be allowed under System Settings › Privacy & Security › Local Network. If it's off, turn it on and restart the app."),
                    L("Mac ve konsol aynı Wi-Fi bandında olsun (ikisi de 2.4 GHz ya da ikisi de 5 GHz) ya da konsol modeme kabloyla bağlı olsun. Modemde \"bant geçişi\" (band steering) bantları ayırıyorsa kapatın.",
                      "Keep the Mac and the console on the same Wi-Fi band (both 2.4 GHz or both 5 GHz), or connect the console to the router by cable. If band steering on the router splits them, turn it off."),
                    L("Modemde \"istemci yalıtımı\" (AP/client isolation) açıksa cihazlar birbirini göremez; kapatın.", "If \"client isolation\" (AP isolation) is on in the router, devices can't see each other; turn it off."),
                    L("macOS güvenlik duvarı açıksa GameTranslator'ın gelen bağlantılarına izin verin (Sistem Ayarları › Ağ › Güvenlik Duvarı › Seçenekler).",
                      "If the macOS firewall is on, allow incoming connections for GameTranslator (System Settings › Network › Firewall › Options)."),
                    L("VPN açıksa kapatın; yerel ağ trafiğini başka yere yönlendirebilir.", "Turn off any VPN; it can reroute local network traffic."),
                ]),
                .problem(L("\"Uzaktan Oynatma başka bir cihazda kullanılıyor\"", "\"Remote Play is in use on another device\""), [
                    L("PS5 aynı anda tek bir Uzaktan Oynatma bağlantısı kabul eder. Telefondaki ya da başka bir bilgisayardaki GameTranslator'ı, PS Remote Play uygulamasını kapatın.",
                      "The PS5 accepts one Remote Play connection at a time. Close GameTranslator on your phone or another computer, and the PS Remote Play app."),
                    L("Az önce durdurduysanız birkaç saniye bekleyin; uygulama kendiliğinden yeniden dener.", "If you just stopped, wait a few seconds; the app retries by itself."),
                ]),
                .problem(L("Kumandam TV'den koptu", "My controller disconnected from the TV"), [
                    L("Mac sizin oynadığınız hesapla bağlanmış. Bağlantı › PS5 › \"PSN hesabını değiştir\" ile ikinci kullanıcının hesabına geçin.",
                      "The Mac is connected with the account you play on. Switch to the second user's account with Connection › PS5 › \"Change PSN account\"."),
                ]),
                .problem(L("TV \"Bağlı değil\"de kalıyor", "The TV stays \"Not connected\""), [
                    L("TV'de GameTranslator uygulaması açık mı ve TV Mac ile aynı ağda mı?", "Is the GameTranslator app open on the TV, and is the TV on the same network as the Mac?"),
                    L("Ağda başka bir GameTranslator (telefon ya da bilgisayar) açıksa kapatın; TV ilk bulduğuna bağlanır.", "If another GameTranslator (phone or computer) is running on the network, close it; the TV connects to the first one it finds."),
                    L("TV uygulaması eski bir adreste takılmış olabilir. Android TV'de Ayarlar › Uygulamalar › GameTranslator › \"Verileri temizle\"; Samsung ve LG'de uygulamayı kaldırıp Mac'ten yeniden kurun.",
                      "The TV app may be stuck on an old address. On Android TV use Settings › Apps › GameTranslator › \"Clear data\"; on Samsung and LG remove the app and reinstall it from the Mac."),
                ]),
                .problem(L("Altyazı hiç gelmiyor", "No subtitles at all"), [
                    L("Oyunun kendi altyazıları açık mı?", "Are the game's own subtitles on?"),
                    L("Okuma alanı altyazının çıktığı yerde mi? Okuma Alanı'nda kontrol edin.", "Is the reading area where the subtitles appear? Check in Reading Area."),
                    L("Oyunun dili ile ayarlardaki \"Oyunun dili\" aynı mı?", "Does the game's language match \"Game language\" in settings?"),
                    L("\"TV'ye deneme altyazısı gönder\" çalışıyorsa TV bağlantısı sağlamdır; sorun okumadadır.", "If \"Send a test subtitle\" works, the TV connection is fine and the problem is in reading."),
                ]),
                .problem(L("Çeviri geç geliyor ya da yarım", "Translation is slow or incomplete"), [
                    L("Konsol mümkünse modeme kabloyla bağlı olsun; zayıf Wi-Fi görüntüyü geciktirir.", "Connect the console to the router by cable if possible; weak Wi-Fi delays the picture."),
                    L("Motor \"Bu Mac'te\" ise Google ya da DeepL'e geçin.", "If the engine is \"On this Mac\", switch to Google or DeepL."),
                    L("Oyunda altyazı boyutunu büyütmek ve arka plan eklemek okumayı kolaylaştırır.", "Larger subtitles with a background in the game make reading easier."),
                ]),
                .note(L("Çözemediğiniz bir sorun olursa günlük kayıtlarını kopyalayıp bizimle paylaşın.", "If you can't solve a problem, copy the log and share it with us.")),
            ]),
        ]
    }
}
