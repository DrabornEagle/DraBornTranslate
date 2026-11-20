# DraBornTranslate

PlayStation Portal’da oyun oynarken PS5’in **Paylaşımlı Ekran** videosundaki İngilizce altyazıyı Android telefonda okur ve Türkçeye çevirir. Türkçe metni telefondaki yayının üzerine yazabilir, telefonun hoparlöründen okuyabilir veya deneysel bir web paneline verebilir.

**[DraBornTranslate v0.3.3 APK’yı indir](https://github.com/DrabornEagle/DraBornTranslate/releases/download/v0.3.3/DraBornTranslate-v0.3.3-Android-arm64.apk)** · Android 10 ve üzeri, ARM64.

## v0.3.3 çeviri

- **Töz-Çevir-1** İngilizce–Türkçe modeli APK’ya dahildir. İlk açılışta telefonda hazırlanır. Çeviri için hesap, API anahtarı, abonelik veya ek model indirmesi gerekmez.
- Oyun altyazısı cümle cümle çevrilir. Ekrana sığdığı için iki satıra bölünen bir cümle birleştirilir. Konuşmacı adları ve diyalog işaretleri korunur. Oyun terimleri ve yaygın deyimler, kelimesi kelimesine hataları azaltmak için ele alınır.
- **Oyun paketi ekle** ile GameTranslator biçimindeki `.json` veya `.json.gz` paketini seçebilirsin. Eşleşen satırda paketin Türkçesi kullanılır. Oyun paketi isteğe bağlıdır.
- **Örnek altyazıyı çevir** ile PS5 bağlantısı kurmadan iki cümleli bir görselde metin tanımayı ve çeviriyi dene. Sonucun altında okunan İngilizceyi görürsün. **İngilizceyi Google Çeviri’de aç** seçeneği, seçtiğin metni Google Çeviri sayfasında açar.

Canlı çeviri telefonda tamamlanır. PlayStation yayını ve uzaktaki PS5 için açılan panel internet bağlantısını kullanır.

## PlayStation App yayınını hazırla

1. Uzak PS5’te **Ayarlar → Sistem → Uzaktan Oynama → Uzaktan Oynama’yı Etkinleştir** ayarını aç. Dinlenme modundan bağlanacaksan **Sistem → Güç Tasarrufu → Dinlenme Modunda Kullanılabilen Özellikler** altında internet bağlantısını ve ağdan açmayı etkinleştir.
2. Portal’da oyunun olduğu hesapla PS5’e **Uzaktan Oynama** üzerinden bağlan. Oyunun dilini İngilizce seç ve altyazıyı aç.
3. Telefonda **PlayStation App**’e ikinci hesabınla giriş yap. Ana hesabınla aynı Game Base sesli sohbetine katıl. PS5’te **PS düğmesi → Game Base → sesli sohbet → Paylaşımlı Ekran’ı Başlat** yolunu kullan.
4. Telefonda takım kartındaki oyun önizlemesine dokun. Oyunun canlı görüntüsünü yatay tam ekrana al. Ekranda İngilizce repliği görebildiğini doğrula.
5. DraBornTranslate’te **PS Portal → Çevrilecek alan → Altyazı bölgesi** seç. **Altyazı ekranı** seçeneklerinden okumak veya dinlemek istediğin çıkışı seç.

[PS Portal Uzaktan Oynama](https://www.playstation.com/tr-tr/support/hardware/playstation-remote-play-on-psportal/) · [Paylaşımlı Ekran](https://www.playstation.com/tr-tr/support/games/ps5-share-screen/) · [PlayStation App](https://www.playstation.com/tr-tr/support/games/playstation-app-features/)

## PS5 uzaktaysa Portal yan panelini dene

Portal yan paneli deneysel. Oyunun yanında görünmesi, PS5’in panel bağlantısını açmasına ve sabitlenmiş sayfayı Uzaktan Oynama’ya aktarmasına bağlı. Bu davranış fiziksel bir PS Portal oturumunda doğrulanmadı.

1. DraBornTranslate’te **Altyazı ekranı → Portal yan paneli (deneysel)** seç. PS App yayını hazırsa **Canlı altyazıyı başlat** ve Android 14 ve üzerinde **Tek uygulama → PlayStation App** seç. Android 10–13’te tüm ekran izni kullanılır.
2. PS5’e gönderilecek bağlantı için DraBornTranslate’e dön. **PS5 uzakta · Bağlantıyı hazırla → Komutu kopyala ve Termux’u aç** seç. Eski bir tünel açıksa Termux’ta `Ctrl+C` ile durdur ve yeni kopyalanan komutu yapıştırıp çalıştır. Komut, telefonun güncel IP’sini ve portunu içerir.
3. Termux’ta görünen güncel `https://…trycloudflare.com` adresini kopyala. Termux’u çalışır bırak. DraBornTranslate’teki bağlantı sihirbazına dön, adresi yapıştır ve **Dış bağlantıyı kontrol et** seçeneğine dokun.
4. Dış bağlantı kontrolü başarılı olunca **Tam Portal bağlantısını kopyala** seç. Uygulama adresin sonuna mevcut oturumun özel yolunu ekler. Tam bağlantıyı PlayStation App’te ikinci hesabından ana hesabına mesajla gönder.
5. DraBornTranslate’te **Portal’da örnek yazı göster** seç. Portal’da **PS düğmesi → Game Base → gelen mesaj → bağlantı** yolunu izle. Web sayfası açılır ve **Options → Yana Sabitle** görünürse seçip oyuna dön. Örnek Türkçe metnin oyunun yanında değişmeye devam ettiğini kontrol et.
6. Telefonda **Canlı çeviriye dön** seç. PlayStation App’teki yatay canlı yayını tekrar öne getir ve ekranı açık tut. Oyunda İngilizce altyazı çıktığında, okunan Türkçe satır panele gönderilir.

**Önce kısa deneme yapmak için** **Portal bağlantısını test et** seç. Bu seçenek ekran paylaşımı izni istemeden iki örnek yazı gösterir. Test açıkken uzak PS5 için tünel sihirbazını da kullanabilirsin. Test 2,5 dakika sürer. Süre dolmadan canlı altyazıya geçersen panel bağlantın korunur. Test kapanırsa yeni oturumdan güncel bağlantıyı al.

**PS5 aynı yerel ağdaysa** tünel adımlarını atlayıp **Panel bağlantısını kopyala** ile yerel adresi aynı şekilde mesajla PS5’e gönder. Telefonun ve Portal’ın aynı Wi-Fi’da olması, başka ağdaki PS5’in yerel adrese erişmesi için yeterli değildir.

[Tünel komutu ve geçici adresler](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)

## Takıldığında

| Belirti | Kontrol |
| --- | --- |
| Termux’ta kırmızı satırlar var | `Registered tunnel connection` göründükten sonra asıl kontrolü uygulamadaki **Dış bağlantıyı kontrol et** ile yap. |
| Bağlantı yanıt vermiyor | Termux’un çalıştığını ve sihirbazdaki komutla o oturumdan aldığın güncel adresi kullandığını kontrol et. Oturum veya tünel değişince yeni bağlantı hazırla. |
| Telefon tarayıcısında panel var, Portal’da yok | Telefondaki açılış yalnızca paneli doğrular. Portal’da **Game Base** mesajını ve **Yana Sabitle** seçeneğini ayrıca kontrol et. Seçenek yoksa veya sayfa oyuna dönünce kapanıyorsa Portal ekranında bu yöntemle altyazı gösterilemez. |
| Portal’da örnek yazı var, oyun yazısı yok | **Canlı çeviriye dön**, telefonda PS App yayınını yatay tam ekranda bırak ve oyunda İngilizce altyazının çıktığını kontrol et. **Altyazı hizası** bandını repliğe ayarla. |
| Telefonda yayın karanlık | PS App’te izlediğin yayını tekrar aç ve Android ekran paylaşımında PS App’i seç. Bazı oyun sahneleri ve korumalı görüntüler ekran paylaşımına yansımayabilir. |
| Portal’da sabitleme çıkmıyor | **Altyazı ekranı → Telefon** Türkçeyi PS App videosu üzerinde gösterir. İlk kullanımda üstte gösterme iznini aç. **Sesli Türkçe** seçeneği telefonundan okur ve Termux istemez. |

Portal’ın **Bulut Akışı** modunda PS5 Paylaşımlı Ekran yayını sağlanmadığı için bu kurulum **Uzaktan Oynama** ile kullanılır. Oturumu bitirince DraBornTranslate’te **Canlı altyazıyı durdur** seç ve Termux’ta tüneli `Ctrl+C` ile kapat. Özel panel bağlantısını yalnızca kullandığın hesaplar arasında paylaş.

## PS App menüsü ve telefonda oyun

- **Çevrilecek alan → Tam oyun görüntüsü** PS App’teki yayında menüleri ve görev metinlerini okur. Çevirmek istediğin menüyü birkaç saniye ekranda tut. **Altyazı ekranı → Telefon** Türkçe satırları telefonunda gösterir.
- **Telefonda oyna** seçeneğinde PS Remote Play ile oyunu aç. Android paylaşım ekranında **PS Remote Play**’i seç.
- **Sesli Türkçe** için **Türkçe sesi dene** düğmesini kullan. Gerekirse Android metin okuma ayarlarında Türkçe ses verisini yükle.

## Derleme ve kaynaklar

JDK 17 ve Android SDK 35 ile derle.

```bash
./gradlew --no-daemon -PtargetAbi=arm64-v8a :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Gradle, [Töz-Çevir-1](https://huggingface.co/TozAI/Toz-Cevir-1/tree/6476d3e0b4b189891fcc07a262ec2d6473c69ae2) dosyalarını sabit revizyondan indirir ve SHA-256 değerlerini kontrol eder. APK, `app/build/outputs/apk/debug/app-debug.apk` yolunda oluşturulur.

Uygulama [GameTranslator v1.8.48](https://gitlab.com/sefflex/GameTranslator/-/releases/v1.8.48) ve [CouchSubs](https://github.com/yorulmazsinan/couchsubs) kaynaklarındaki OCR temizleme, altyazı sabitleme, terim sözlüğü ve paket eşleştirme kodlarından yararlanır. Model Töz AI tarafından yayımlanmıştır. [GNU AGPL v3](LICENSE) · [Telif bildirimi](NOTICE) · [Üçüncü taraf bileşenler](THIRD-PARTY-NOTICES.md)
