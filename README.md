# DraBornTranslate

PlayStation Portal’da oynarken PS5’in **Paylaşımlı Ekran** videosunu Android telefonda İngilizceden Türkçeye çevirir. Telefon, PlayStation App’te açılan görüntüyü Android ekran paylaşımı izniyle okur ve çeviriyi videonun üzerinde gösterir. Kamera, mikrofon, bilgisayar ve görüntü yakalama kartı gerekmez.

**Android 10 ve üzeri.** Android 14 ve üzeri, yalnızca seçtiğiniz uygulamayı paylaşmanıza izin verir. Bu ilk önizlemenin Portal ile gerçek cihaz uyumluluğu henüz bir PS5 ve Portal ile denenmedi.

## Portal’da oyna, telefonda oku

1. Portal’dan kendi PS5 konsoluna **Uzaktan Oynama** ile bağlan. Oyunda dili İngilizce seç ve altyazıları aç.
2. Telefonda **PlayStation App**’e ikinci bir PlayStation hesabıyla giriş yap. İki hesabı arkadaş olarak ekle ve aynı Game Base sesli sohbetine katıl.
3. PS5’in sesli sohbet kartından **Paylaşımlı Ekran’ı Başlat** seçeneğini kullan. Telefonda daveti aç ve oyun videosunu yatay tam ekranda izle.
4. Son uygulamalardan **DraBornTranslate**’e dön. **PS Portal → Google ile canlı altyazıyı başlat** seçeneğine dokun ve üstte gösterme iznini aç.
5. Android’in paylaşım penceresinde **Tek uygulama → PlayStation App**’i seç. Android 10–13’te sistem tüm ekran için izin ister. İngilizce satır okunduğunda Türkçesi telefonda görünür.

Alt yazı farklı bir hizadaysa **Okuma bölgesi** sürgüsünü ayarla. Oturumu DraBornTranslate içinden veya bildirimdeki **Durdur** düğmesiyle kapatabilirsin. **Google ile örnek altyazıyı çevir** düğmesi, PS5 bağlantısı olmadan uygulamanın OCR ve çeviri işlevlerini dener.

[PlayStation App](https://play.google.com/store/apps/details?id=com.scee.psxandroid) · [Sony Paylaşımlı Ekran kılavuzu](https://www.playstation.com/tr-tr/support/games/ps5-share-screen/) · [PlayStation App destek sayfası](https://www.playstation.com/tr-tr/support/games/playstation-app-features/)

## Telefonda oyna

**Telefonda oyna** modunda [PS Remote Play](https://play.google.com/store/apps/details?id=com.playstation.remoteplay) ile oyunu Android telefonda aç. Altyazıyı başlatırken Android’in paylaşım penceresinde **PS Remote Play**’i seç. Aynı Türkçe katman bu uygulamanın üzerinde görünür.

## Cihaz uyumluluğu

- Portal modu, PS5’in ayrı **Paylaşımlı Ekran** yayınını kullanır. Telefonda ikinci bir Remote Play oturumu açma. Sony’nin **Paylaşımlı Oyun** özelliği farklıdır. PS5 Pro’nun 8K çıkışında Sony, Paylaşımlı Ekran ile Uzaktan Oynama’nın birlikte kullanımını kısıtlar. [Sony Uzaktan Oynama kısıtlamaları](https://remoteplay.dl.playstation.net/remoteplay/lang/en/#restrictions)
- Portal’ın ayrı **Bulut Akışı** modu bir PS5 Paylaşımlı Ekran yayını sağlamaz. Bu sürümün çeviri katmanı Android telefonda görünür.
- Android kaynak videonun görüntüsünü paylaşmaya izin vermelidir. Video ekran paylaşımında siyah kalırsa oyun, PlayStation App veya cihaz ekran yakalamayı sınırlıyor olabilir.
- Uygulama ekranda yazılı olan İngilizce altyazıları okur. İlk kullanımda dil modeli internetten indirilir. Ardından yazı tanıma ve çeviri cihazda çalışır. Uygulama oyun karelerini veya altyazı metnini saklamaz ve sunucuya yüklemez. [ML Kit veri açıklaması](https://developers.google.com/ml-kit/terms#data_disclosure)

## Android derlemesi

JDK 17 ve Android SDK 35 kurulu bir ortamda çalıştır:

```sh
./gradlew -PtargetAbi=arm64-v8a :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

ARM64 telefonlara kurulabilir önizleme `app/build/outputs/apk/debug/app-debug.apk` dosyasında oluşturulur. Bu paket geliştirme imzası taşır.

## Kaynak ve lisans

Bu Android sürümü [GameTranslator v1.8.48](https://gitlab.com/sefflex/GameTranslator/-/releases/v1.8.48) ve [CouchSubs](https://github.com/yorulmazsinan/couchsubs) kaynaklarındaki OCR temizleme, altyazı eşleştirme ve sabitleme kodunu kullanır. Android MediaProjection, Portal kurulum akışı ve telefondaki Türkçe altyazı katmanı 20 Kasım 2026 tarihinde DraBornTranslate için eklendi.

© Sinan Yorulmaz ve Sefflex. [GNU AGPL v3](LICENSE) · [Orijinal telif bildirimi](NOTICE) · [Üçüncü taraf bileşenler](THIRD-PARTY-NOTICES.md).
