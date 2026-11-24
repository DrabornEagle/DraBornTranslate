# DraBornTranslate v5.0

**Android 10+ · ARM64 · Android sürüm kodu 1**

[İmzalı APK ve sürüm notları](https://github.com/DrabornEagle/DraBornTranslate/releases/tag/v5.0)

v5.0 yeni paket kimliğiyle kurulur. v0.4.x ve v5.0 aynı telefonda açık kalabilir. İlk açılışta **GTA 6 Türkçe paketini al → GTA 6 Türkçe paketini indir** yolunu kullan. Paket cihaza kaydedilir. Pakete uyan satırlar yerel tablodan, diğer metinler telefondaki Töz-Çevir modelinden alınır.

## PS5 ve TV

1. PS5’i ve telefonu aynı ev ağına bağla. PS5’te **Ayarlar → Sistem → Uzaktan Oynama** ayarını aç.
2. TV’de ana hesabınla oynarken görüntüyü telefona almak için PS5’e ikinci hesap ekle. **Cihazı Eşleştir** ekranındaki kodu kullan. Eşlenen hesabın sayısal PSN kimliğini, PS5’in IP adresini ve konsoldaki kodu **PS5 ve TV’ye bağlan → PS5’i eşleştir** bölümüne gir.
3. Oyunun İngilizce altyazısını aç. **Canlı çeviriyi başlat** seçeneğine dokun.
4. Türkçeyi telefonda okumak için **Çeviri çıkışı → Telefon** seç. TV tarayıcısı için **Telefon ve TV tarayıcısı** seçeneğini kullan ve panel adresini aynı ağdaki tarayıcıda aç. Oyun ile tarayıcıyı yan yana göstermek, TV’nin çoklu görüntü desteğine bağlıdır.

PS5’te ikinci Remote Play oturumu kabul edilmezse uygulamadaki **PS Remote Play ile telefonda oyna** yolunu kullan. PS Remote Play’i aç ve Android ekran paylaşımında onu seç.

## PlayStation Portal

1. Portal’da PS5’e normal şekilde bağlan. Oyunda İngilizce altyazıyı aç.
2. PS5’te **Game Base → Paylaşımlı Ekran’ı Başlat** yolunu kullan. Telefonda PlayStation App’te ikinci hesabınla yayını yatay olarak aç.
3. DraBornTranslate’te **Portal’a bağlan → Canlı çeviriyi başlat** seç. Android 14 ve üzerinde **Tek uygulama → PlayStation App** seçeneğiyle yayın görüntüsünü paylaş. Telefon ekranındaki Türkçe katman için **Üstte gösterme** izni ver.
4. Telefonu yanında ikinci ekran olarak kullanabilirsin. Portal yan paneli, PS5’in Game Base bağlantısını açmasına ve **Yana Sabitle** seçeneğini göstermesine bağlıdır. Uygulamadaki panel testinde bu seçenek doğrulanabilir.

[PlayStation App Paylaşımlı Ekran](https://www.playstation.com/tr-tr/support/games/playstation-app-features/) · [PS Portal Uzaktan Oynama](https://www.playstation.com/tr-tr/support/hardware/playstation-remote-play-on-psportal/)

## Altyazı ve DraBorn Odak

1. Çeviri modundan **Altyazı + DraBorn Odak** seç. İngilizce altyazı açıkken oyundaki telefon mesajını, Brief bölümünü veya tabelayı aç.
2. **DraBorn Odak · Alan seç** ekranında görüntü üzerinden bir dikdörtgen çiz. **Alanı çevir** seçeneği Türkçeyi görüntünün üstünde gösterir ve bu alanı canlı oturum için saklar.
3. Android 14+ uygulama paylaşımında oyun ekranına dönünce seçili alan canlı taranır. Android 10–13’te Odak sonucu görüntü inceleme ekranından okunur. PS5 eşleme oturumunda **Canlı alanı takip et** seçeneği telefon ekranındaki kareyi günceller.

Canlı altyazı ve alanın OCR işlemi bağımsızdır. Ekranda küçük veya bulanık bir satır varsa oyunda HUD yazı boyutunu büyütmek ve tam ekran görüntü kullanmak okumayı iyileştirir.

## PS App yakalamaları

PS App’te **Kütüphane → Yakalamalar** bölümüne geç. Brief’in birden çok sayfasını okuyacaksan her sayfa için bir yakalama oluştur.

- **Hemen çevir:** PS App’te **Paylaş → DraBornTranslate** seç.
- **Otomatik çeviri:** DraBornTranslate’te **PS App yakalamaları → Otomatik çeviriyi aç** seç ve medya izni ver. PS App’te **İndir** dediğinde görüntüler `Pictures/PS App`, klipler `Movies/PS App` klasöründen okunur.
- **Klip:** İnceleme ekranında istediğin zaman konumunu seçip **Kareyi seç** düğmesine dokun.

PS5’in otomatik yüklemesi yakalamaları PS App’e getirir. Telefona kaydetmek için PlayStation App’te **İndir** veya **Paylaş** kullanılır. İndirilmiş dosyaların arka planda işlenme zamanını Android pil ve arka plan ayarları belirler. [PlayStation yakalama rehberi](https://www.playstation.com/tr-tr/support/games/ps5-game-captures-ps-app/)

## Kaynak koddan derleme

Android SDK 35, Build Tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1, JDK 17 ve `protoc` gerekir. CMake’e verilen Python yorumlayıcısında `protobuf` ve `grpcio-tools` kurulu olmalıdır.

```bash
./scripts/prepare-ps5.sh
python3 -m venv .venv
.venv/bin/pip install protobuf==5.29.5 grpcio-tools==1.71.0
DRA_PS5_PYTHON="$PWD/.venv/bin/python" ./gradlew --no-daemon :app:assembleRelease
./gradlew --no-daemon -PskipPs5 :app:testDebugUnitTest :app:lintDebug
```

ARM64 imzasız derleme `app/build/outputs/apk/release/app-release-unsigned.apk` yoluna yazılır. GitHub Actions çıktısı imzalama ve APK doğrulama araçlarını da içerir. Yayımlanmış v5 APK’ları güncellenirken aynı sürüm anahtarı ve artan Android sürüm kodu kullanılır. İmza sertifikası APK ile birlikte doğrulanır.

[Açık kaynak bileşenleri](KAYNAKLAR.md)
