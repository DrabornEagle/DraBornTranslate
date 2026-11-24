<p align="center">
  <img src="docs/github-hero.svg" width="100%" alt="DraBornTranslate — oyunlarda canlı Türkçe çeviri">
</p>

<h1 align="center">🎮 DraBornTranslate</h1>
<p align="center"><strong>PlayStation deneyimin için tasarlanmış Türkçe oyun çeviri merkezi.</strong></p>

<p align="center">
  <a href="https://github.com/DrabornEagle/DraBornTranslate/releases"><img alt="Releases" src="https://img.shields.io/github/v/release/DrabornEagle/DraBornTranslate?color=8B5CF6&label=S%C3%BCr%C3%BCm"></a>
  <a href="https://github.com/DrabornEagle/DraBornTranslate/actions/workflows/android.yml"><img alt="Android CI" src="https://github.com/DrabornEagle/DraBornTranslate/actions/workflows/android.yml/badge.svg"></a>
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-22D3EE?logo=android&logoColor=white">
  <a href="app/src/main/assets/LICENSE"><img alt="AGPL-3.0" src="https://img.shields.io/badge/Lisans-AGPL--3.0-F59E0B"></a>
</p>

<p align="center">
  <a href="https://github.com/DrabornEagle/DraBornTranslate/releases"><strong>📥 APK sürümleri</strong></a> ·
  <a href="docs/KURULUM.md"><strong>📖 Kurulum</strong></a> ·
  <a href="https://github.com/DrabornEagle/DraBornTranslate/actions"><strong>⚙️ Android derlemeleri</strong></a> ·
  <a href="https://github.com/DrabornEagle/DraBornTranslate/issues"><strong>🛟 Destek</strong></a>
</p>

---

## 🚀 v0.7.1 · Cloud Gaming Pro

- CameraX: yatay, dikey ve otomatik yön, OCR/önizleme koordinat uyumu.
- İki bağımsız sürüklenebilir ve boyutlandırılabilir alan, hassas yüzde ayarı, yakınlaştırma, yazı boyutu ve okuma modu.
- Deneysel kamerasız Android istemcisi modu: mevcut ekran paylaşımı ile ayrı bir istemcinin görüntüsünü çevirir. Portal bulut oturumunu aynalamaz; korumalı görüntü okunamaz.
- [v0.7.1 sürüm notları ve test planı](docs/RELEASE-v0.7.1.md). Kaynak önizlemesi; gerçek cihaz testi ve imzalı APK doğrulaması gereklidir.

### v0.7.0 · Cloud Gaming Translate (PS Portal bulut oyunları)

PS Portal Cloud Streaming'de Sony'nin belgelenmiş canlı Share Screen desteği bulunmadığı için, oyunu buluttan oynarken telefon kamerasıyla Türkçe çeviren yeni mod geliştirildi.

- **Fiziksel Portal ekranı → telefonun arka kamerası → ML Kit OCR → GTA 6 Türkçe paket / yerel model → telefon ekranında Türkçe altyazı.**
- Mavi konuşma altyazısı bölgesi ve yeşil oyun telefonu mesaj bölgesi aynı anda çevrilir.
- Görüntüler kaydedilmez, bizim sunucumuza aktarılmaz ve PSN hesap bilgileri istenmez.
- Çeviriler doğrudan Portal'ın donanım ekranında değil, telefon ekranında gösterilir. Gerçek oynanışta kamera odak/performance testi gereklidir.

[Cloud Gaming Translate v0.7.0 kullanım ve sınırlamalar](docs/RELEASE-v0.7.0.md).

### Önceki v0.6.3 modu



DraBornTranslate açılışta **iki büyük seçenek** gösterir. Kurulum ve altyazı araçlarının tamamı DrabornEagle görsel kimliğiyle sunulur.

| Mod | Neler var? |
|---|---|
| **🎮 PS5 ve TV'ye Bağlan** | PlayStation 5 kurulum ekranı, uygulama/çeviri/oyun dili tercihleri, PSN hesap yönlendirmesi ve TV adımları |
| **🕹 PS Portal'a Bağlan** | Önceki DraBornTranslate'in ekran yakalama, canlı çeviri, Lens, oyun paketi ve Portal yardımcı araçları |
| **🔄 Sürüm kontrolü** | Ana sayfada sürüm numarası, GitHub Releases üzerinden imza ve SHA-256 doğrulamalı güncelleme kontrolü |

> **Geliştirme durumu:** v0.6.0'ın PS5/TV tarafı bir **kurulum arayüzü önizlemesidir**. Sony PSN/Chiaki oturum eşleştirmesi veya TV'ye gerçek zamanlı görüntü aktarımı mevcut Kotlin APK'ya henüz entegre edilmemiştir. PSN düğmesi resmî Sony hesabı sayfasını açar; tek başına konsol bağlantısı oluşturmaz.

## ✨ Portal çeviri özellikleri

- **Canlı altyazı:** Telefonda görüntülenen PlayStation App veya PS Remote Play oyun görüntüsünden OCR ile İngilizce metin okuma.
- **Lens görünümü:** Telefon ekranındaki yakalanan görüntü üzerine Türkçe metin yerleştirme.
- **Çevrimdışı çeviri:** Model ilk defa indirilip hazırlandıktan sonra cihazda çeviri.
- **İsteğe bağlı oyun paketleri:** Desteklenen paketleri kullanıcı başlatır; indirme ve özet doğrulaması uygulanır.
- **Esnek görünüm:** Altyazı bandı, yazı boyutu, okuma bölgesi ve Portal yardımcı araçları.

**Sınırlar:** DraBornTranslate PlayStation Portal'ın fiziksel ekranına doğrudan sistem katmanı kuramaz. Telefonun görüntü erişim izinleri ve ilgili PlayStation uygulamasındaki yayın gereksinimleri geçerlidir. Oyun çevirilerinin doğruluğu ve oyun paketlerinin tamamlığı garanti edilmez.

## 📲 Kurulum ve güncelleme

1. [GitHub Releases](https://github.com/DrabornEagle/DraBornTranslate/releases) sayfasından **DraBornTranslate imzalı APK'sını** indir.
2. Ana ekrandan **PS5 ve TV'ye Bağlan** veya **PS Portal'a Bağlan** modunu seç.
3. Güncelleme için ana sayfadaki **GitHub'da yeni sürüm kontrolü** seçeneğini kullan.

**Sürüm kimliği:** `versionName 0.7.1` / `versionCode 6` / `applicationId com.draborneagle.draborntranslate`.

**Önemli:** Bu kaynak kodunun derlenmiş, imzalı ve test edilmiş v0.7.1 APK'sı henüz yayımlanmış kabul edilmemelidir. Android, mevcut uygulamadaki `versionCode` daha yüksekse veya imzalama sertifikası farklıysa üzerine kurulum yapmaz. Uygulamayı kaldırmadan önce yedek al.

### Android kaynak derleme

JDK 17 + Android SDK 35:

```bash
./gradlew --no-daemon -PtargetAbi=arm64-v8a :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CI başarılı olduğunda `app/build/outputs/apk/debug/app-debug.apk` önizlemesi GitHub Actions artifact'ı olarak sağlanır. Ayrı GTA 6 entegrasyon iş akışı gerçek 25,27 MB dosyayı doğrular ve Android JVM'deki paket öncelikli çeviri hattıyla test eder; oyun verilerini depoya yayımlamaz. **Debug APK üretim sürümü değildir** ve mevcut Release APK'nın üzerine güvenli güncelleme olarak yüklenmez.

## ⚡ GTA 6 — 475.893 satır paket doğrulaması

GTA 6 Türkçe paketinin sunucuda ilan edilen **475.893 satırı**, gerçek `.json.gz` dosyası indirilerek kontrol edildi. GitHub Actions [canlı test](https://github.com/DrabornEagle/DraBornTranslate/actions/workflows/gta6-pack-verification.yml) şu koşulları doğrular:

- `gta-6` / `tr` / `format: 2` (Arapça paketle karışmaz).
- Manifestteki boyut, indirilen dosyanın SHA-256 özeti ve açılmış dosyadaki **475.893 kullanılabilir satır** eşleşmelidir.
- **"Kolt & Associates:"** için hesaplanan 64-bit GamePack anahtarı dosyada bulunmalı ve boş olmamalıdır.
- Doğrulanmayan yeni dosya kurulmaz; mevcut doğrulanmış sürüm korunur.
- Dosya APK'ya veya GitHub'a kopyalanmaz. İndirmeyi kullanıcı açıkça başlatır; cihazda bir kez açılan paket tekrar ağ üzerinden sorgulanmadan kullanılabilir.

Android'de **modelden önce paket** araması yapılır; tam eşleşme bulunursa makine çevirisi devreye girmez. Paket dışındaki satırlar yerel motora gider. Bu durum OCR okumasının, ilk paket yüklemesinin veya cihazın işleme süresinin sıfır olduğunu **garanti etmez**. GTA 6'daki bütün repliklerin oyunda doğrulanmış olduğu iddia edilmez.

## 🔁 Açık kaynak geliştirme senkronu

`.github/workflows/gametranslator-upstream.yml` GitLab'daki referans açık kaynak sürümlerini kontrol eder; lisanslı kaynakları üçüncü taraf lisanslı kaynak klasörüne alır. **Bu işlem yeni bir DraBornTranslate APK'sı oluşturmaz.** Platformlar arası kaynak değişiklikleri ayrı entegrasyon, Android derleme ve gerçek cihaz testinden geçmek zorundadır. Üçüncü taraf ikili dosyalar veya oyun diyalog paketleri doğrudan repoya kopyalanmaz.


<p align="center"><strong>DrabornEagle · Made for gamers 💜</strong><br>
<a href="https://www.draborneagle.com">Resmî web sitesi</a> ·
<a href="https://github.com/DrabornEagle">GitHub</a> ·
<a href="https://github.com/DrabornEagle/DraBornTranslate/issues">Görüş ve hata bildirimi</a>
</p>
