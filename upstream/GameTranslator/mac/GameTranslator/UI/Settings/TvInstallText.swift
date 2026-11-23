// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// TV kurulumunun metinleri (telefondaki lib/l10n 'tv.*' tablolarının Mac'e uyarlanmış hâli: "telefon" yerine "Mac").
enum TvText {
    static let brands = ["android", "lg", "samsung"]

    @MainActor static func brandName(_ brand: String) -> String {
        switch brand {
        case "lg": "LG"
        case "samsung": "Samsung"
        default: "Android TV / Google TV"
        }
    }

    @MainActor static func brandDetail(_ brand: String) -> String {
        switch brand {
        case "lg": L("webOS. Ana ekranda \"LG Content Store\" ve alt şeritte renkli uygulama kartları bulunur.",
                     "webOS. The home screen has \"LG Content Store\" and colorful app cards along the bottom.")
        case "samsung": L("Tizen, 2017 ve sonrası. Ana ekranda \"Samsung TV Plus\" ve \"Smart Hub\" bulunur.",
                          "Tizen, 2017 and later. The home screen has \"Samsung TV Plus\" and \"Smart Hub\".")
        default: L("TCL, Sony, Philips, Xiaomi, Vestel, Arçelik, Beko, Grundig… Ana ekranda Google Play ya da \"Google TV\" yazar.",
                   "TCL, Sony, Philips, Xiaomi, Vestel, Arçelik, Beko, Grundig… The home screen shows Google Play or \"Google TV\".")
        }
    }

    /// Kurulum aşamasının metni (`uploading:<yüzde>` dahil).
    @MainActor static func stage(_ stage: String) -> String {
        if stage.hasPrefix("uploading") {
            let percent = stage.split(separator: ":").last.map(String.init) ?? "0"
            return L("Uygulama TV'ye gönderiliyor… %\(percent)", "Sending the app to the TV… \(percent)%")
        }
        switch stage {
        case "connecting": return L("TV'ye bağlanılıyor…", "Connecting to the TV…")
        case "approve": return L("TV'de izin bekleniyor… TV'deki \"USB hata ayıklamaya izin verilsin mi?\" sorusunda \"Bu bilgisayardan her zaman izin ver\"i işaretleyip \"İzin ver\"i seçin.",
                                 "Waiting for approval on the TV… When the TV asks \"Allow USB debugging?\", check \"Always allow from this computer\" and choose \"Allow\".")
        case "checking": return L("TV'deki sürüme bakılıyor…", "Checking the version on the TV…")
        case "key": return L("TV'den anahtar alınıyor…", "Getting the key from the TV…")
        case "reading": return L("TV'nin bilgileri okunuyor…", "Reading the TV's details…")
        case "certificate": return L("Samsung'dan bu TV'ye özel sertifika alınıyor…", "Getting a certificate for this TV from Samsung…")
        case "signing": return L("Uygulama imzalanıyor…", "Signing the app…")
        case "uninstalling": return L("TV'deki eski kopya kaldırılıyor…", "Removing the old copy from the TV…")
        case "installing": return L("TV'ye kuruluyor…", "Installing on the TV…")
        case "verifying": return L("Kurulum doğrulanıyor…", "Verifying the installation…")
        case "launching": return L("TV'de açılıyor…", "Opening on the TV…")
        default: return L("Hazırlanıyor…", "Preparing…")
        }
    }

    /// Hatanın sade dilde açıklaması ve çözümü (telefondaki tvErrorText ile aynı durumlar).
    @MainActor static func error(_ code: String?, brand: String) -> (title: String, fix: String) {
        switch code ?? "" {
        case "UNREACHABLE" where brand == "samsung":
            return (L("TV bağlantıyı kabul etmedi", "The TV refused the connection"),
                    L("TV açık ve Mac ile aynı ağda mı? Uygulamalar ekranında 1 2 3 4 5'e basıp \"Developer mode\"un On olduğuna ve \"Host PC IP\"de bu Mac'in adresinin yazdığına bakın. Değiştirdiyseniz TV'yi güç tuşunu basılı tutarak (ya da fişini çekip) tamamen kapatıp açın.",
                      "Is the TV on and on the same network as the Mac? On the Apps screen press 1 2 3 4 5 and check that \"Developer mode\" is On and \"Host PC IP\" shows this Mac's address. If you changed it, turn the TV fully off and on by holding the power button (or unplugging it)."))
        case "UNREACHABLE" where brand == "android":
            return (L("TV yanıt vermedi", "The TV didn't respond"),
                    L("TV açık mı ve Mac ile aynı ağda mı? Geliştirici seçeneklerinde \"USB hata ayıklama\" açık olmalı. Bazı TV'lerde bu ayar TV yeniden başlayınca kapanır; açıp yeniden deneyin.",
                      "Is the TV on and on the same network as the Mac? \"USB debugging\" must be on in Developer options. On some TVs it turns off when the TV restarts; turn it on and try again."))
        case "UNREACHABLE":
            return (L("TV yanıt vermedi", "The TV didn't respond"),
                    L("TV açık mı ve Mac ile aynı ağda mı? Developer Mode uygulamasında \"Dev Mode Status\" açık olmalı; açtıktan sonra TV yeniden başlar, açılmasını bekleyin.",
                      "Is the TV on and on the same network as the Mac? \"Dev Mode Status\" must be on in the Developer Mode app; the TV restarts after you turn it on, so wait for it."))
        case "WRONG_HOST_IP":
            return (L("TV'deki \"Host PC IP\" bu Mac değil", "The TV's \"Host PC IP\" isn't this Mac"),
                    L("Samsung yalnız geliştirici modunda yazılan adresten gelen bağlantıyı kabul eder. Uygulamalar ekranında 1 2 3 4 5'e basın, \"Host PC IP\" alanına bu Mac'in adresini yazın, OK'e basın ve TV'yi tamamen kapatıp açın.",
                      "Samsung only accepts connections from the address entered in developer mode. On the Apps screen press 1 2 3 4 5, type this Mac's address in \"Host PC IP\", press OK and turn the TV fully off and on."))
        case "SIGN_IN_FAILED":
            return (L("Samsung girişi tamamlanmadı", "The Samsung sign-in wasn't completed"),
                    L("Giriş sayfası kapandı ya da süre doldu. Yeniden deneyin.", "The sign-in page was closed or timed out. Try again."))
        case "SIGN_IN_NEEDED":
            return (L("Samsung hesabıyla giriş gerekiyor", "A Samsung account sign-in is needed"),
                    L("Bu TV için henüz sertifika yok. Samsung hesabınızla bir kez giriş yapın; sonraki kurulumlarda sorulmaz.",
                      "There's no certificate for this TV yet. Sign in once with your Samsung account; you won't be asked again."))
        case "CERTIFICATE_FAILED":
            return (L("Samsung sertifikayı vermedi", "Samsung didn't issue the certificate"),
                    L("Samsung'un sunucusu şu an yanıt vermiyor ya da giriş süresi doldu. Mac'in internete bağlı olduğundan emin olun, yeniden giriş yapıp tekrar deneyin.",
                      "Samsung's server isn't responding or the sign-in expired. Make sure the Mac is online, sign in again and retry."))
        case "TOO_OLD" where brand == "samsung":
            return (L("Bu Samsung TV çok eski", "This Samsung TV is too old"),
                    L("TV uygulaması Tizen 3.0 (2017 modelleri) ve sonrasında çalışır. TV'nin Ayarlar › Destek › Yazılım Güncelleme ekranından güncellemeyi deneyin.",
                      "The TV app needs Tizen 3.0 (2017 models) or later. Try updating from the TV's Settings › Support › Software Update."))
        case "TOO_OLD":
            return (L("Bu TV çok eski", "This TV is too old"), L("TV uygulaması bu TV'nin yazılımında çalışmıyor.", "The TV app doesn't run on this TV's software."))
        case "KEY_SERVER_OFF":
            return (L("Key Server kapalı", "Key Server is off"),
                    L("TV'de Developer Mode uygulamasını açın, \"Key Server\"ı açın ve uygulamayı ekranda bırakın. Sonra yeniden deneyin.",
                      "Open the Developer Mode app on the TV, turn on \"Key Server\" and leave the app on screen. Then try again."))
        case "WRONG_PASSPHRASE":
            return (L("Parola doğru değil", "The passphrase is wrong"),
                    L("TV ekranında Developer Mode uygulamasının sol altındaki 6 karakterlik \"Passphrase\"i yeniden yazın. 0 (sıfır) ile O harfi, 1 ile I harfi karışabilir.",
                      "Retype the 6-character \"Passphrase\" shown at the bottom left of the Developer Mode app. 0 (zero) and O, 1 and I are easy to mix up."))
        case "KEY_REFUSED":
            return (L("TV anahtarı kabul etmedi", "The TV refused the key"),
                    L("Geliştirici modu kapatılıp açıldıysa TV yeni bir anahtar üretir. Key Server'ı açıp parolayı yeniden yazın.",
                      "If developer mode was turned off and on, the TV made a new key. Turn on Key Server and type the passphrase again."))
        case "NOT_APPROVED":
            return (L("TV'de izin verilmedi", "Not approved on the TV"),
                    L("Yeniden deneyin. TV'de \"USB hata ayıklamaya izin verilsin mi?\" sorusu çıkınca \"Bu bilgisayardan her zaman izin ver\"i işaretleyip \"İzin ver\"i seçin. Soru TV ekranında yaklaşık bir buçuk dakika bekler.",
                      "Try again. When the TV asks \"Allow USB debugging?\", check \"Always allow from this computer\" and choose \"Allow\". The prompt stays for about a minute and a half."))
        case "NOT_ENOUGH_SPACE":
            return (L("TV'de yer kalmamış", "The TV is out of space"), L("TV'den kullanmadığınız bir uygulamayı kaldırıp yeniden deneyin.", "Remove an app you don't use from the TV and try again."))
        case "CONNECTION_LOST":
            return (L("Bağlantı koptu", "Connection lost"),
                    L("Kurulum sırasında TV ile bağlantı kesildi. Mac uyumasın ve ağa bağlı kalsın; yeniden deneyin.",
                      "The connection to the TV dropped during installation. Keep the Mac awake and on the network, then try again."))
        case "PACKAGE_MISSING":
            return (L("TV paketi bulunamadı", "TV package not found"),
                    L("Bu Mac uygulamasının içinde TV paketi yok. Uygulamanın son sürümünü kurun.", "This Mac app has no TV package inside. Install the latest version."))
        case "CANCELLED":
            return (L("Kurulum durduruldu", "Installation stopped"), L("Hazır olduğunuzda yeniden başlatabilirsiniz.", "You can start again when you're ready."))
        default:
            return (L("TV uygulaması kurulamadı", "The TV app couldn't be installed"),
                    L("Yeniden deneyin. TV'yi kapatıp açmak yarım kalan kurulumların çoğunu düzeltir. Yine olmazsa aşağıdaki \"TV'nin söylediği\" yazısıyla birlikte bize bildirin.",
                      "Try again. Turning the TV off and on fixes most half-finished installs. If it still fails, report it to us with the \"TV said\" text below."))
        }
    }

    /// Geliştirici modunu açma adımları (telefondaki tv.<marka>.sN metinlerinin kısaltılmışı).
    @MainActor static func steps(_ brand: String, macAddress: String) -> [String] {
        switch brand {
        case "lg":
            return [
                L("Tarayıcıda webostv.developer.lge.com adresinde ücretsiz bir LG geliştirici hesabı açın (LG ThinQ hesabınız varsa o da olur).",
                  "Create a free LG developer account at webostv.developer.lge.com (an existing LG ThinQ account also works)."),
                L("TV'de LG Content Store'dan \"Developer Mode\" uygulamasını kurun.", "On the TV, install the \"Developer Mode\" app from the LG Content Store."),
                L("Uygulamada hesabınızla giriş yapıp \"Dev Mode Status\"u açın. TV yeniden başlar; açılmasını bekleyin.",
                  "Sign in to the app with your account and turn on \"Dev Mode Status\". The TV restarts; wait for it."),
                L("Developer Mode uygulamasını yeniden açıp \"Key Server\"ı açın ve uygulamayı ekranda bırakın. Sol altta 6 karakterlik \"Passphrase\" görünür.",
                  "Open the Developer Mode app again, turn on \"Key Server\" and leave the app on screen. A 6-character \"Passphrase\" appears at the bottom left."),
            ]
        case "samsung":
            return [
                L("TV kumandasında Ana Sayfa tuşuna basıp \"Uygulamalar\"ı (Apps) açın.", "Press Home on the TV remote and open \"Apps\"."),
                L("Uygulamalar ekranındayken kumandada sırayla 1 2 3 4 5'e basın (sayı tuşu yoksa \"123\" tuşuyla ekrandaki tuş takımını kullanın).",
                  "While on the Apps screen, press 1 2 3 4 5 on the remote (without number keys, use the on-screen keypad from the \"123\" button)."),
                L("\"Developer mode\"u On yapın, \"Host PC IP\" alanına bu Mac'in adresini (\(macAddress)) yazıp OK'e basın.",
                  "Set \"Developer mode\" to On, type this Mac's address (\(macAddress)) in \"Host PC IP\" and press OK."),
                L("TV'yi tamamen kapatıp açın: güç tuşunu TV yeniden açılana kadar basılı tutun (ya da fişini 30 saniye çekin).",
                  "Turn the TV fully off and on: hold the power button until it restarts (or unplug it for 30 seconds)."),
            ]
        default:
            return [
                L("TV'de Ayarlar › Cihaz Tercihleri › Hakkında'ya girin (Google TV: Ayarlar › Sistem › Hakkında).",
                  "On the TV open Settings › Device Preferences › About (Google TV: Settings › System › About)."),
                L("\"Android TV OS yapısı\" (ya da \"Yapı numarası\") satırında Tamam tuşuna 7 kez basın; \"Artık bir geliştiricisiniz\" yazısı çıkar.",
                  "Press OK 7 times on \"Android TV OS build\" (or \"Build number\"); \"You are now a developer\" appears."),
                L("Bir ekran geri dönüp yeni çıkan \"Geliştirici seçenekleri\"ne girin.", "Go back one screen and open the new \"Developer options\"."),
                L("\"USB hata ayıklama\"yı açın; varsa \"Ağ üzerinden hata ayıklama\"yı da açın.", "Turn on \"USB debugging\", and \"Network debugging\" if it's there."),
            ]
        }
    }

    /// TV'nin adresinin nerede yazdığı.
    @MainActor static func whereAddress(_ brand: String) -> String {
        switch brand {
        case "lg": L("TV'nin adresi: Ayarlar › Genel › Ağ › Wi-Fi Bağlantısı › Gelişmiş Wi-Fi Ayarları.", "The TV's address: Settings › General › Network › Wi-Fi Connection › Advanced Wi-Fi Settings.")
        case "samsung": L("TV'nin adresi: Ayarlar › Genel › Ağ › Ağ Durumu › IP Ayarları.", "The TV's address: Settings › General › Network › Network Status › IP Settings.")
        default: L("TV'nin adresi: Ayarlar › Ağ ve İnternet › bağlı olduğunuz Wi-Fi ağının adı.", "The TV's address: Settings › Network & Internet › the name of your Wi-Fi network.")
        }
    }

    /// "1.0.12" > "1.0.9" gibi sürüm karşılaştırması.
    static func isNewer(_ a: String, than b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }
        let y = b.split(separator: ".").map { Int($0) ?? 0 }
        for index in 0..<max(x.count, y.count) {
            let (p, q) = (index < x.count ? x[index] : 0, index < y.count ? y[index] : 0)
            if p != q { return p > q }
        }
        return false
    }
}
