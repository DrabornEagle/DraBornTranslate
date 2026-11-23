# DraBornTranslate v0.7.1 — Cloud Translate Studio (Android önizleme)

## Neden kamera görüntüsü tuhaf görünüyordu?

v0.7.0 doğrudan Camera2 `TextureView` yüzeyi kullanıyordu. Sensör yönü, ekran dönüşü, kamera 16:9 oranı ve ekrana sığdırma aynı dönüşümde uygulanmadığı için özellikle yatay kullanımda görüntü yan dönmüş, kırpılmış veya gerilmiş görünebiliyordu.

**v0.7.1:** CameraX `PreviewView(FIT_CENTER)` kamera görüntüsünü sensör ve ekran yönüne göre otomatik döndürüp tamamını oranı koruyarak gösterir. Telefonu fiziksel olarak yamuk tutunca oluşan perspektif bozulmasını yazılım tek başına gidermez; Portal ekranının kameraya mümkün olduğunca düz bakması hâlâ gerekir.

## Üç mod

| Mod | Girdi | Türkçe sonuç |
|---|---|---|
| 📷 **Kamera + Lens** | Portal ekranı telefon kamerasında görünür | Mavi kutudan konuşma altyazısı, yeşil kutudan oyun telefon mesajı |
| 🎙 **Sadece ses** | Portal hoparlörü → telefon mikrofonu | Konuşulan İngilizcenin Türkçe altyazısı; **kamera gerekmez** |
| 📷+🎙 **Kamera + ses** | Mikrofon konuşma için, kamera sadece mesaj/ekran yazıları için | Ayrı ses altyazısı + Lens mesajı, çift konuşma OCR tekrarı yok |

Ses tanıma **önce cihaz içi** Android `SpeechRecognizer.createOnDeviceSpeechRecognizer` kullanır (Android 12+ ve cihazda destek varsa). Destek yoksa sistem ses tanımasına geçiş için **ayrı açık onay** gerekir; bu sağlayıcıya ses verisi gönderilebilir. Mikrofon verileri DraBornTranslate sunucularına iletilmez ve uygulamada ses dosyası olarak saklanmaz.

### Profesyonel alan editörü

- Tam **yatay/dikey ekran modu** düğmesi; CameraX önizlemesi ekran dönüşüyle hizalanır.
- **Mavi altyazı alanı** ve **yeşil mesaj alanı**: ikisi de tam 2D dört kenarlı bağımsız dikdörtgendir.
- **Alanlar** düğmesi: kutuları parmakla sürükle, dört köşeden boyutlandır.
- **Hassas ayar**: soldan başlangıç, üstten başlangıç, genişlik ve yükseklik için %1 hassasiyetli kaydırıcılar.
- **Yakınlaştır**: cihazın desteklediği optik/dijital kamera zoom kontrolü.
- Alanlar oturumlar arasında saklanır; varsayılan yerleşim geri yüklenebilir.
- `PreviewView.ScaleType.FIT_CENTER` en-boy oranını korur, görüntünün kenarlarını kesmez.

## Bulut oyununda önerilen pratik kullanım

**Telefonu sabitlemek istemiyorsan:** Portal sesini aç ve DraBornTranslate'de **🎙 Ses** modunu seç. Telefonu Portal'ın yanına koy; görüntü almaya ve kamera iznine gerek yok. Hoparlör gürültüsü, müzik ve mikrofon kalitesi tanıma doğruluğunu etkiler. İstersen Portal'ın **3,5 mm kulaklık çıkışına** bağlanan uygun ses kablosu + telefonla uyumlu harici **ses giriş adaptörü** kullanılabilir; düz bir AUX kablosu her telefonda mikrofon girişi sağlamaz.

**Telefon mesajı ve yazılı görevler için:** Ses tanıma ekrandaki sessiz yazıları çeviremez. Kamera + Lens veya Birlikte modunda yeşil bölgeyi mesaj paneline hizala.

**Portal'ın kendi ekranında altyazı:** Resmî PS Portal Cloud Streaming, Share Screen/Share Play/broadcast sağlamaz. Belgelenmiş Portal video çıkışı veya üçüncü taraf canlı görüntü API'si bulunmadığından DraBornTranslate'in doğrudan Portal donanım ekranına yazı yerleştirme özelliği yoktur.

## İncelenen açık kaynak referansları

- [LiveTranscribe-Android](https://github.com/chartmann1590/LiveTranscribe-Android): Android mikrofon, cihaz içi transkripsiyon ve altyazı katmanları.
- [live-captions](https://github.com/samoylenkodmitry/live-captions): Android çevrimdışı konuşma tanıma + çeviri.
- [canli-ceviri](https://github.com/90karatinsa/canli-ceviri): Android whisper.cpp tabanlı gerçek zamanlı ses çeviri yapısı.
- [Tercana](https://github.com/Rumpony/Tercana): Mac bilgisayarda PS **Remote Play** için OCR çeviri; PS Portal'ın kendi Cloud Streaming akışını açmaz.
- [CouchSubs](https://github.com/yorulmazsinan/couchsubs): PS5 Remote Play ve TV üstüne altyazı, **PS Portal Cloud Streaming ile doğrudan çalışmaz**.

Bu kaynaklar referans araştırmasıdır; kodları veya GPL lisans yükümlülükleri ihlal edilerek yeniden markalanıp projeye kopyalanmaz.

### Deneysel üçüncü yol: bulut oyununu doğrudan Android'de açmak (Portal'dan bağımsız)

Araştırmada [Con-app](https://github.com/houqai/Con-app), [CloudPad-Android](https://github.com/Chazq2023/CloudPad-Android) ve [Asobi](https://play.google.com/store/apps/details?id=com.asobi.droid) gibi **resmî olmayan** PS Plus bulut istemcileri bulundu. Bu çözümler teorik olarak oyun görüntüsünü doğrudan telefonda gösterebildiği için Android ekran üstü OCR/altyazı yaklaşımına kapı açabilir. **Portal'daki mevcut bulut oturumunu aynalamazlar**; aynı hesapta eşzamanlı ikinci oturumun çalıştığı doğrulanmamıştır.

Bu istemcilerin PSN kimlik doğrulaması, güvenliği, kullanım şartları ve hesap riski bağımsız olarak doğrulanmadı. Bu yüzden DraBornTranslate v0.7.1 bunların hiçbirine PSN şifresi/tokenı vermez, otomatik bağlantı veya entegrasyon yapmaz. Özellikle CloudPad kendi belgelerinde PSN hesabının kısıtlanabileceğine dikkat çeker.

**Önerilen güvenli yol:** Önce resmi PS Portal Cloud Streaming + telefon mikrofonu/kamera modlarını kullan; yalnızca resmî olmayan istemcileri ayrı araştırma konusu olarak değerlendir.

## Kurulum, kalite ve güvenlik

- `versionName 0.7.1` / `versionCode 6` / Android 10+ (ses cihaz-içi tanıma desteği Android 12+ ve servis varlığına bağlı).
- 475.893 satırlık GTA 6 Türkçe paketinin yüklü olması tekrar indirme gereksinimini ortadan kaldırır; OCR veya ses tanıma metniyle **eşleşirse** yerel paketten anlık çeviri kullanılır. Diğer metinler cihaz içi çeviriye yönlendirilir.
- **Bütün GTA 6 repliklerinin profesyonel olarak yeniden çevrildiği iddia edilmez**. Ses tanıma ve OCR'nin doğruluğu gerçek Portal cihazında test edilmelidir.
- İmzalı Debug APK, GitHub Actions üzerinden test derlemesidir; üretim imzalı APK ancak mevcut orijinal keystore ve yayın yetkisiyle yayımlanır.
- Debug imzası üretim imzasıyla farklı olabilir. Mevcut uygulamayı kaldırmak oyun paketini ve kullanıcı düzeltmelerini **silebilir**. Silmeden önce yedek al.

## Referans belgeler

- [Sony PS Portal Cloud Streaming support](https://www.playstation.com/en-us/support/subscriptions/psportal-cloud-game-streaming/)
- [Sony PS Portal cihaz portları](https://www.playstation.com/en-us/support/hardware/ps-accessories/)
- [Android Camera Preview Rotation](https://developer.android.com/media/camera/camera2/camera-preview)
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
