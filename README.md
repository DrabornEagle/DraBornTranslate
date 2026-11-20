# DraBornTranslate

PlayStation Portal’da oynarken PS5’in **Paylaşımlı Ekran** videosunu Android telefonda İngilizceden Türkçeye çevirir. Telefon, PlayStation App’te açılan görüntüyü Android ekran paylaşımı izniyle okur. Çeviriyi telefonda yazıyla gösterir, Türkçe sesle okur veya bir web paneline verir. Kamera, mikrofon, bilgisayar ve görüntü yakalama kartı gerekmez.

**Android 10 ve üzeri.** Android 14 ve üzeri, yalnızca seçtiğiniz uygulamayı paylaşmanıza izin verir. Portal yan paneli deneysel. Gerçek bir PS5 ve Portal oturumunda sabitleme henüz doğrulanmadı.

## v0.3.2 · GameTranslator çevirisi

1. **Çeviri motoru → GameTranslator · Otomatik** seç. Bu seçenek için hesap veya API anahtarı gerekmez.
2. İstersen **Oyun paketi ekle** seçeneğinden yüklü GameTranslator’ın paketlerinden birini al veya GameTranslator biçimindeki .json ya da .json.gz dosyasını seç.
3. **Örnek altyazıyı çevir** ile hazır repliği dene. Oyun diyaloğu için **Çevrilecek alan → Altyazı bölgesi** seç ve canlı altyazıyı başlat.

DraBornTranslate önce GameTranslator biçimindeki seçili pakette eşleşen Türkçe satırı arar. Yaygın oyun replikleri için hazır Türkçe karşılıklar da bulunur. Diğer satırları GameTranslator’ın kaynak kodundaki anahtarsız Google motoru çevirir. Çevrimiçi motor yanıt vermezse cihazdaki dil modeli kullanılır. Terim sözlüğü oyun adlarını ve terimlerini korur.

**Yalnızca cihazda** modunda hazır paketler ve cihazdaki dil modeli kullanılır. Dil modeli ilk kullanımda internetten indirilir. **Otomatik** modun çevrimiçi kısmı yalnızca okunan metni çeviri motoruna gönderir. Google atfı Google motorlarından gelen sonuçlarda gösterilir. [ML Kit atıf kuralları](https://developers.google.com/ml-kit/language/translation/translation-terms)

## Portal’da oyna, Türkçe çeviriyi dinle

1. Portal’dan PS5’e bağlan. Oyunda İngilizce altyazıyı aç ve Game Base sesli sohbetinden **Paylaşımlı Ekran’ı Başlat** seçeneğini kullan.
2. Telefonda ikinci hesabınla **PlayStation App**’i aç. Yayın önizlemesine dokun ve yatay tam ekrana geç.
3. DraBornTranslate’te **PS Portal → Altyazı ekranı → Sesli Türkçe** seç. **Türkçe sesi dene** ile telefonun ses çıkışını kontrol et. Gerekirse Android metin okuma ayarlarında Türkçe ses verisini yükle.
4. Canlı altyazıyı başlat. Android 14 ve üzerindeki paylaşım penceresinde **Tek uygulama → PlayStation App** seç. Yayını telefonda önde tut. Telefon, Türkçe satırları hoparlöründen veya bağlı kulaklıktan okur.

Bu mod, PS5 uzaktayken de Paylaşımlı Ekran üzerinden çalışır. Termux veya tünel kurulumu gerekmez.

## PlayStation App’te yayın metnini çevir

**Çevrilecek alan** düğmesinden **Altyazı bölgesi** ya da **Tam oyun görüntüsü** seç. Tam oyun görüntüsü, yatay tam ekran videoda okunabilen menü ve görev yazılarını tarar. Sonuç, aynı anda en fazla altı sabit satır olarak gösterilir. Çevirmek istediğin metni birkaç saniye ekranda tut. **Altyazı ekranı → Telefon** ile Türkçe metin yayın üstünde görünür. İlk kullanımda **Diğer uygulamaların üzerinde göster** iznini ver.

## Portal’da oyna, telefonda altyazı oku

1. Portal’dan kendi PS5 konsoluna **Uzaktan Oynama** ile bağlan. Oyunda dili İngilizce seç ve altyazıları aç.
2. Telefonda **PlayStation App**’e ikinci bir PlayStation hesabıyla giriş yap. İki hesabı arkadaş olarak ekle ve aynı Game Base sesli sohbetine katıl.
3. PS5’in sesli sohbet kartından **Paylaşımlı Ekran’ı Başlat** seçeneğini kullan. Telefonda daveti aç ve oyun videosunu yatay tam ekranda izle.
4. Son uygulamalardan **DraBornTranslate**’e dön. **PS Portal → Canlı altyazıyı başlat** seçeneğine dokun ve üstte gösterme iznini aç.
5. Android’in paylaşım penceresinde **Tek uygulama → PlayStation App**’i seç. Android 10–13’te sistem tüm ekran için izin ister. İngilizce satır okunduğunda Türkçesi telefonda görünür.

Altyazı farklı bir hizadaysa **Altyazı hizası** sürgüsünü ayarla. Oturumu DraBornTranslate içinden veya bildirimdeki **Durdur** düğmesiyle kapatabilirsin. **Örnek altyazıyı çevir** düğmesi, PS5 bağlantısı olmadan uygulamanın OCR ve çeviri işlevlerini dener.

[PlayStation App](https://play.google.com/store/apps/details?id=com.scee.psxandroid) · [Sony Paylaşımlı Ekran kılavuzu](https://www.playstation.com/tr-tr/support/games/ps5-share-screen/) · [PlayStation App destek sayfası](https://www.playstation.com/tr-tr/support/games/playstation-app-features/)

## Portal yan paneli (deneysel)

### PS5 uzaktaysa

1. PS5’te Paylaşımlı Ekran’ı başlat. Telefonda ikinci hesabınla PS App yayınını yatay tam ekrana al. **PS Portal → Altyazı ekranı → Portal yan paneli (deneysel)** ve **Çevrilecek alan → Altyazı bölgesi** seç. Canlı altyazıyı başlatıp Android paylaşımında PlayStation App’i seç.
2. **PS5 uzakta · Bağlantıyı hazırla → Komutu kopyala ve Termux’u aç** düğmesine dokun. Komutu Termux’ta çalıştır ve Termux’un verdiği trycloudflare.com HTTPS adresini DraBornTranslate’e yapıştır.
3. **Dış bağlantıyı kontrol et** ve **Tam Portal bağlantısını kopyala**. Bağlantıyı ikinci hesabından ana hesabına Game Base mesajıyla gönder.
4. Telefonda **Portal’da örnek yazı göster** düğmesine dokun. Portal’da **PS düğmesi → Game Base → mesajdaki bağlantı** yolunu izle. **Options → Yana Sabitle** çıkıyorsa seçip oyuna dön. Örnek Türkçe replik oyunun yanında değişmeye devam ederse **Canlı çeviriye dön** ve PS App yayınını telefonda yatay tam ekranda bırak.

Canlı oturum veya Termux tüneli yeniden başladığında dış bağlantıyı yeniden hazırla. Dış bağlantı kontrolü telefonun paneline ulaşmayı doğrular. Portal’da oyunun yanında göstermek, PS5’in sayfayı sabitlemesine ve Uzaktan Oynama’ya aktarmasına bağlıdır. **Yana Sabitle** seçeneği çıkmazsa bu oturumda Portal ekranında oyunla birlikte panel görünmez.

### Önce bağlantıyı test et

1. Telefon ve PS5 aynı yerel ağa bağlıyken DraBornTranslate’te **PS Portal → Portal bağlantısını test et** seçeneğine dokun. Test, Android ekran paylaşımı izni ve çeviri modeli olmadan örnek Türkçe metin gösterir.
2. **Panel bağlantısını kopyala** ve PlayStation App’ten kendi PS5 hesabına Game Base mesajıyla gönder.
3. Portal’da PS düğmesine basıp Game Base mesajındaki bağlantıyı aç. **Options → Yana Sabitle** görünürse oyuna dön ve örnek metnin değişmeye devam ettiğini kontrol et.

Test 2,5 dakika sürer. Test açıkken canlı altyazıyı başlatarak aynı bağlantıyı kullanabilirsin. Test süresi dolarsa yeni oturum yeni bağlantı üretir.

### Canlı altyazıyı başlat

1. Telefonu ve kendi PS5’ini aynı yerel ağa bağla. Portal’ı PS5’e Uzaktan Oynama ile bağla.
2. Android’de **PS Portal → Altyazı ekranı → Portal yan paneli (deneysel)** seç. Hem telefonda hem panelde görmek için **Telefon ve Portal paneli** seçeneğini kullan.
3. PlayStation App’te **Paylaşımlı Ekran** yayınını aç. DraBornTranslate’te canlı altyazıyı başlat ve Android ekran paylaşımında **PlayStation App**’i seç.
4. DraBornTranslate’te **Panel bağlantısını kopyala** düğmesine dokun. Telefonda PlayStation App içinden bağlantıyı kendi PS5 hesabına Game Base mesajıyla gönder. Ardından telefonda Paylaşımlı Ekran yayınına dön.
5. Portal’da oyundayken PS düğmesini kullanarak **Game Base**’i aç. PS5’te gelen bağlantıyı seç. Web görünümü açılır ve **Options → Yana Sabitle** seçeneği sunulursa onu seçip oyuna dön.

DraBornTranslate yerel ağda bir web paneli sunar. Panel Portal’da ancak PS5 bağlantıyı açabiliyor ve sabitlenen web görünümünü Uzaktan Oynama’ya aktarabiliyorsa oyunla birlikte görünür. Sony, Portal’ın kendi arayüzüne dışarıdan katman eklemek için belgelenmiş bir arayüz sunmuyor. **Yerel bağlantıyı telefonda aç** düğmesi yerel yayını kendi telefonunda sınar.

Panel sadece oturum açıkken, telefonun özel yerel ağ adresinden hizmet verir. Rastgele oluşturulan bağlantı her oturumda değişir. Panel yalnızca çevrilmiş Türkçe metni alır. Portal paneli seçeneği için telefonda üstte gösterme izni gerekmez.

[Sony PS5 Game Base mesajları](https://www.playstation.com/en-ca/support/games/send-messages-ps5/) · [PS5 web görünümünü oyunun yanına sabitleme örneği](https://www.playstationlifestyle.net/2022/07/14/how-to-watch-youtube-while-playing-ps5-games-pip-splitscreen/)

## Telefonda oyna

**Telefonda oyna** modunda [PS Remote Play](https://play.google.com/store/apps/details?id=com.playstation.remoteplay) ile oyunu Android telefonda aç. Altyazıyı başlatırken Android’in paylaşım penceresinde **PS Remote Play**’i seç. Aynı Türkçe katman bu uygulamanın üzerinde görünür.

## Cihaz uyumluluğu

- Portal modu, PS5’in ayrı **Paylaşımlı Ekran** yayınını kullanır. Telefonda ikinci bir Remote Play oturumu açma. Sony’nin **Paylaşımlı Oyun** özelliği farklıdır. PS5 Pro’nun 8K çıkışında Sony, Paylaşımlı Ekran ile Uzaktan Oynama’nın birlikte kullanımını kısıtlar. [Sony Uzaktan Oynama kısıtlamaları](https://remoteplay.dl.playstation.net/remoteplay/lang/en/#restrictions)
- Portal’ın ayrı **Bulut Akışı** modu bir PS5 Paylaşımlı Ekran yayını sağlamaz. Bulut Akışı sırasında bu uygulamanın Portal yan paneli kullanılamaz.
- Android kaynak videonun görüntüsünü paylaşmaya izin vermelidir. Video ekran paylaşımında siyah kalırsa oyun, PlayStation App veya cihaz ekran yakalamayı sınırlıyor olabilir.
- Uygulama ekrandaki İngilizce yazıları cihazda tanır. Oyun kareleri ve canlı oturumda çevrilen altyazılar diske kaydedilmez. İçe aktarılan oyun paketi uygulama içinde saklanır. Portal paneli, Türkçe metni oturuma özel bağlantıyı açan tarayıcıya sunar. [ML Kit veri açıklaması](https://developers.google.com/ml-kit/terms#data_disclosure)

## Android derlemesi

JDK 17 ve Android SDK 35 kurulu bir ortamda çalıştır:

```sh
./gradlew -PtargetAbi=arm64-v8a :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

ARM64 telefonlara kurulabilir önizleme `app/build/outputs/apk/debug/app-debug.apk` dosyasında oluşturulur. Bu paket geliştirme imzası taşır.

## Kaynak ve lisans

Bu Android sürümü [GameTranslator v1.8.48](https://gitlab.com/sefflex/GameTranslator/-/releases/v1.8.48) ve [CouchSubs](https://github.com/yorulmazsinan/couchsubs) kaynaklarındaki OCR temizleme, altyazı sabitleme, terim sözlüğü ve oyun paketi eşleştirmesini kullanır. Anahtarsız çevrimiçi motor ve cihazda çeviri sırası GameTranslator’dan uyarlandı.

© Sinan Yorulmaz ve Sefflex. [GNU AGPL v3](LICENSE) · [Orijinal telif bildirimi](NOTICE) · [Üçüncü taraf bileşenler](THIRD-PARTY-NOTICES.md).
