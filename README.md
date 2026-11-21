# DraBornTranslate

PlayStation Portal’da oyun oynarken PS5’in **Paylaşımlı Ekran** videosundaki İngilizce altyazıyı Android telefonda okur ve Türkçeye çevirir. Türkçe metni telefondaki yayının üzerine yazabilir, telefonun hoparlöründen okuyabilir veya deneysel bir web paneline verebilir.

**Android 10 ve üzeri, ARM64.** [Sürümler](https://github.com/DrabornEagle/DraBornTranslate/releases)

## v0.4.1 çeviri

1. APK’yı kur ve **Türkçe modeli indir** seçeneğini aç. Uygulama MiLMMT-46 1B modelini bir defa indirir (806 MB) ve dosyanın özetini doğrular. Çeviri telefonun işlemcisinde çalışır. Hesap, API anahtarı veya abonelik gerekmez.
2. **Çeviriyi dene** ile örnek görseldeki yazıyı okut veya İngilizce metin gir. **Son çeviri** kartında Türkçe metni ve ona karşılık gelen İngilizceyi görebilirsin. Yeni algılanan metin henüz çevrilmediyse **Okunan İngilizce** altında görünür.
3. **Canlı çeviriyi başlat** ile PlayStation yayınına dön. Kısa ve net replikler tek kareden alınabilir. Doğrulanan replikler sırayla çevrilir. Aynı konuşmacının cümleleri bağlam için bir arada çevrilir. Konuşmacı adları, sayılar, soru işaretleri ve belirgin olumsuz komutlar korunur. Güvenilir bir çevirisi bulunamayan replik bölümü `[…]` ile işaretlenir.

**Görünüm ve oyun paketi → Oyun paketi ekle** ile GameTranslator biçimindeki `.json` veya `.json.gz` paketini seçebilirsin. Kaynak cümle pakette eşleşirse paketin Türkçesi kullanılır.

Model indirildikten sonra çeviri çevrimdışı çalışır. PlayStation yayını ve uzaktaki PS5 için açılan panel internet bağlantısını kullanır.

## PlayStation App yayınını hazırla

1. Uzak PS5’te **Ayarlar → Sistem → Uzaktan Oynama → Uzaktan Oynama’yı Etkinleştir** ayarını aç. Dinlenme modundan bağlanacaksan **Sistem → Güç Tasarrufu → Dinlenme Modunda Kullanılabilen Özellikler** altında internet bağlantısını ve ağdan açmayı etkinleştir.
2. Portal’da oyunun olduğu hesapla PS5’e **Uzaktan Oynama** üzerinden bağlan. Oyunun dilini İngilizce seç ve altyazıyı aç.
3. Telefonda **PlayStation App**’e ikinci hesabınla giriş yap. Ana hesabınla aynı Game Base sesli sohbetine katıl. PS5’te **PS düğmesi → Game Base → sesli sohbet → Paylaşımlı Ekran’ı Başlat** yolunu kullan.
4. Telefonda takım kartındaki oyun önizlemesine dokun. Oyunun canlı görüntüsünü yatay tam ekrana al. Ekranda İngilizce repliği görebildiğini doğrula.
5. DraBornTranslate’te **PS Portal → Çevrilecek alan → Altyazı bölgesi** seç. **Çeviri çıkışı** seçeneklerinden okumak veya dinlemek istediğin yeri seç.

[PS Portal Uzaktan Oynama](https://www.playstation.com/tr-tr/support/hardware/playstation-remote-play-on-psportal/) · [Paylaşımlı Ekran](https://www.playstation.com/tr-tr/support/games/ps5-share-screen/) · [PlayStation App](https://www.playstation.com/tr-tr/support/games/playstation-app-features/)

## PS5 uzaktaysa Portal yan panelini dene

Portal yan paneli deneysel. Oyunun yanında görünmesi, PS5’in panel bağlantısını açmasına ve sabitlenmiş sayfayı Uzaktan Oynama’ya aktarmasına bağlı. Bu davranış fiziksel bir PS Portal oturumunda doğrulanmadı.

1. DraBornTranslate’te **Çeviri çıkışı → Portal yan paneli (deneysel)** seç. PS App yayını hazırsa **Canlı çeviriyi başlat** ve Android 14 ve üzerinde **Tek uygulama → PlayStation App** seç. Android 10–13’te tüm ekran izni kullanılır.
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
| Portal’da sabitleme çıkmıyor | **Çeviri çıkışı → Telefon** Türkçeyi PS App videosu üzerinde gösterir. İlk kullanımda üstte gösterme iznini aç. **Sesli Türkçe** seçeneği telefonundan okur ve Termux istemez. |

Portal’ın **Bulut Akışı** modunda PS5 Paylaşımlı Ekran yayını sağlanmadığı için bu kurulum **Uzaktan Oynama** ile kullanılır. Oturumu bitirince DraBornTranslate’te **Canlı çeviriyi durdur** seç ve Termux’ta tüneli `Ctrl+C` ile kapat. Özel panel bağlantısını yalnızca kullandığın hesaplar arasında paylaş.

## PS App menüsü ve telefonda oyun

- **Çevrilecek alan → Tüm oyun metinleri** menüleri ve görev metinlerini okur. Çevirmek istediğin ekranı yazılar okunana kadar açık tut. Yeni sonuçlar her satır çevrildikçe eklenir. Telefonda uzun listeler sırayla gösterilir. **Son çeviri** kartından listenin tamamını okuyabilir ve kopyalayabilirsin.
- **Telefonda oyna** seçeneğinde PS Remote Play ile oyunu aç. Android paylaşım ekranında **PS Remote Play**’i seç.
- **Sesli Türkçe** için **Türkçe sesi dene** düğmesini kullan. Gerekirse Android metin okuma ayarlarında Türkçe ses verisini yükle.

## Derleme ve kaynaklar

JDK 17, Android SDK 35, NDK 28.2.13676358 ve CMake 3.22.1 ile derle.

```bash
./gradlew --no-daemon -PtargetAbi=arm64-v8a :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CMake, [llama.cpp](https://github.com/ggml-org/llama.cpp/tree/b1b770d90d94cad9e9ea5b993bdbc383a522fb28) kaynaklarını sabit revizyondan alır ve arşiv özetini doğrular. APK, `app/build/outputs/apk/debug/app-debug.apk` yolunda oluşturulur.

Uygulama [GameTranslator v1.8.48](https://gitlab.com/sefflex/GameTranslator/-/releases/v1.8.48) ve [CouchSubs](https://github.com/yorulmazsinan/couchsubs) kaynaklarındaki OCR temizleme, altyazı sabitleme, terim sözlüğü ve paket eşleştirme kodlarından yararlanır. MiLMMT-46 1B modeli Xiaomi Research tarafından yayımlanmıştır. [GNU AGPL v3](LICENSE) · [Telif bildirimi](NOTICE) · [Üçüncü taraf bileşenler](THIRD-PARTY-NOTICES.md)
