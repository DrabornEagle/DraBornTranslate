# DraBornTranslate v0.6.1 · GTA 6 paket indirme bağlantı düzeltmesi

🎮 **DraBornTranslate** artık açılışta iki kullanım modu sunar: **PS5 ve TV'ye Bağlan** ve **PS Portal'a Bağlan**. Mevcut DraBornTranslate logosu ve önceki Portal ekranı korunur; güncel sürüm numarası ile GitHub güncelleme kontrolü ana ekrana taşınmıştır.

## 📶 Connection reset düzeltmesi

- Hata veren mobil bağlantılarda paket listesi ve paket dosyası için yeniden deneme.
- Resmî sunucu `HTTP 206 Range` destekliyorsa 1 MiB parçalarla kesintisiz aktarma ve yarım kalan indirmeyi devam ettirme.
- Range desteklenmiyorsa tüm dosyayı güvenli biçimde yeniden alma; yarım dosyaya yanlış veri eklenmez.
- Arşiv tamamlandıktan sonra **SHA-256, dosya boyutu ve gerçek 475.893 satır doğrulaması** sürer.
- Ana ekranda yüzde ilerleme (sayfa kaydırması sıfırlanmadan).
- Doğrudan tarayıcıdan açma ve `.json.gz` dosyasını elle içe alma seçenekleri.
- Farklı ağ koşullarını taklit eden yerel HTTP testleri; bağlantının operatör tarafından engellenmesi durumunda farklı ağ gerekebilir.

## ⚡ GTA 6 hazır Türkçe paket · doğrulanan 475.893 satır

Bağımsız otomatik kontrol, Türkçe `gta-6` paketinin **25.271.152 bayt** sıkıştırılmış veri içerdiğini, SHA-256 özetinin manifestle eşleştiğini ve dosyanın içinden **475.893 kullanılabilir Türkçe kayıt** ayrıştırıldığını doğruladı. `Kolt & Associates:` etiketi için GamePack anahtarı da gerçekten mevcut.

- Paket kullanıcı seçimiyle çevrimiçi indirilir. Dosya APK içerisine gömülü değildir.
- **SHA-256 + gerçek satır sayısı doğrulanmadan** yeni paket etkinleşmez; başarısız güncellemede eski paket korunur.
- Eşleşen altyazı doğrudan yerel paketten gelir; **çeviri modelini beklemez**. Pakette bulunmayan satırlar yerel motora yönlendirilir.
- OCR okunması, ilk paket açılışı, cihazın işlem hızı ve PS5 yayın gecikmesi yine zaman alabilir. "0 ms" tüm sistem için garanti edilmez.
- Türkçe kayıt sayısı doğrulanmıştır; oyundaki her İngilizce repliğin eksiksiz kapsandığı ve bütün çevirilerin kusursuz olduğu iddia edilmez.

[GitHub Actions GTA 6 doğrulaması](https://github.com/DrabornEagle/DraBornTranslate/actions/workflows/gta6-pack-verification.yml).

## Arayüz ve kullanım

- **PS5 / TV:** Dil seçimi ve Sony'nin hesap sayfasına güvenli bağlantı sunan kurulum arayüzü. **Gerçek Chiaki/PSN konsol eşleştirme ve TV'ye yayın henüz bu Kotlin APK'ya entegre edilmedi.**
- **PS Portal:** Önceki uygulamadaki ekran yakalama, altyazı, Lens ve Portal paneli araçları.
- **GTA 6:** Ana ekrandan Türkçe paketi indir/güncelle, yüklü kayıt sayısını gör.
- **Güncelleme:** GitHub Releases üzerinden yalnızca doğrulanabilir DraBornTranslate paketlerini arar.

## Android dağıtım bilgisi

| Alan | Değer |
|---|---|
| Uygulama | DraBornTranslate |
| versionName | `0.6.0` |
| versionCode | `2` |
| applicationId | `com.draborneagle.draborntranslate` |
| Mimari | ARM64 (Android 10+) |

⚠️ **Önemli kurulum notu:** Önceki üretim sürümünün Android sürüm kodu `22` idi. İstenilen `versionCode 1`, aynı imza kullanılsa dahi eski kurulum üzerine normal yükseltme olarak uygulanamaz. Önceki ayarlarını yedekleyip kaldırdıktan sonra yükleme gerekebilir; uygulama verilerinin silinmesini göze almadan kaldırma yapmayın.

Bu sürüm **önizleme** niteliğindedir: PS5/TV gerçek bağlantı modülü tamamlanıp gerçek PS5 ve TV'de test edilmediğinden kararlı sürüm olarak sunulmamalıdır.

## İmza, güvenlik ve lisans

İmzalama yalnızca GitHub Actions sırlarında bulunan mevcut üretim sertifikası ile yapılır. Otomasyon APK'yı imza, sürüm, eski sertifika eşitliği ve GTA 6 canlı paket testi açısından doğrulamadan Release yayımlamaz.

GNU AGPL-3.0 lisansı ve üçüncü taraf kaynak telif bildirimleri uygulama kaynaklarında ve [NOTICE](../app/src/main/assets/NOTICE) içinde korunur. GTA 6 oyun diyaloğu metinleri kaynak repoya veya bu APK'ya gömülmez. DraBornTranslate DrabornEagle'in bağımsız projesidir.
