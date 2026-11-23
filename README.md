<p align="center">
  <img src="app/src/main/res/drawable-nodpi/brand_mark.webp" width="96" height="96" alt="DraBornTranslate logosu">
</p>

<h1 align="center">DraBornTranslate</h1>
<p align="center"><strong>PlayStation oyunları için canlı Türkçe altyazı</strong></p>
<p align="center">DrabornEagle ekosisteminin Android canlı oyun çevirisi uygulaması.</p>
<p align="center">
  <a href="https://github.com/DrabornEagle/DraBornTranslate/releases/download/v0.4.11/DraBornTranslate-v0.4.11-Android-arm64.apk"><strong>↓ Son kararlı APK (v0.4.11)</strong></a>
  · <a href="https://github.com/DrabornEagle/DraBornTranslate/releases/tag/v0.4.11">Sürüm notları</a>
  · <a href="docs/KURULUM.md">Kurulum rehberi</a>
</p>

## İndir ve kur

| Güncel sürüm | Cihaz | Çeviri |
| --- | --- | --- |
| **v0.4.12 (kaynak geliştirme)** | Android 10 ve üzeri, ARM64 | Cihazda OCR + EN→TR çeviri |
| **v0.4.11 (APK)** | Android 10 ve üzeri, ARM64 | Son yayımlanmış imzalı APK |

1. İmzalı APK’yı indirip Android telefonuna kur. Önceki sürüm aynı imzayı taşıyorsa üzerine kurabilirsin.
2. Eski Töz yedek çeviri modeli APK’ya dahildir. v0.4.12 kaynak sürümünde Google ML Kit İngilizce-Türkçe modeli ilk kullanımda indirilebilir; hazır olduğunda çeviri telefonun üzerinde çalışır.
3. **Çeviriyi dene** ile ilk sonucu al. **Son çeviri** kartında Türkçeyi ve uygulamanın okuduğu İngilizceyi birlikte görebilirsin.

Sağ üstteki üç noktadan **Hakkında** penceresini aç ve **Yeni sürüm kontrolü** ile güncelle. Yeni APK otomatik indirilir ve imzası doğrulanır. Android kurulum izni veya onay isterse ekrandaki adımları tamamla.

## Oyunda kullan

| İhtiyacın | Uygulamadaki seçim |
| --- | --- |
| Oyun diyaloglarını takip etmek | **Çevrilecek alan → Altyazı bölgesi** |
| Menüleri ve görev metinlerini okumak | **Çevrilecek alan → Tüm oyun metinleri** |
| Telefonda oyun oynarken altyazı görmek | **Telefonda oyna** ve **PS Remote Play** |
| PS Portal’da oynarken çeviriyi görmek veya dinlemek | **PS Portal** ve **Çeviri çıkışı** |

PS Portal için sürmekte olan oyun görüntüsünü **PlayStation App** üzerinden Android telefonda aç ve **Canlı çeviriyi başlat** seçeneğine dokun. Türkçe çeviri telefon ekranında, Türkçe ses olarak veya deneysel Portal yan panelinde gösterilebilir. PlayStation App, yayın ve panel adımları [kurulum rehberinde](docs/KURULUM.md) yer alıyor.

## v0.4.12 kaynak sürümünde geliştirilenler

- **Lens · Yazının üzerine çevir** modu: ekran yazılarının konumları korunarak telefon üzerindeki oyun görüntüsüne Türkçe katman eklenir.
- OCR sonuçları konuma göre takip edilir, tekrar eden satırlar önbellekten gösterilir.
- **Ücretli API ve API anahtarı gerektirmeyen** Google ML Kit cihaz üstü İngilizce–Türkçe çeviri birincil motor olarak eklenir; indirme başarısızsa paketlenmiş Töz ONNX modeli yedek kalır.
- Bu özellik PS Portal ekranını doğrudan değiştirmez; Lens katmanı Android telefonun görüntüsü üzerinde çalışır. Ekranda yazılı olmayan seslendirmeyi OCR çeviremez.
- **v0.4.12 için imzalı APK henüz yayımlanmadı.** Yukarıdaki indirme bağlantısı v0.4.11 içindir. Derleme ve cihaz testi gereklidir.

## v0.4.11’de neler değişti?

- Amerikan İngilizcesindeki günlük konuşma, argo ve sürüş komutları anlamına göre Türkçeye çevriliyor.
- Kısa yanıtların anlamı yakın repliklerle tamamlanıyor. Bağlam kısa bir altyazı boşluğunda korunuyor.
- Ekran okumasında bölünen olumsuzluklar, yol adları ve çıkış kodları için düzeltmeler yapıldı.

## DrabornEagle kaynak kodu

Bu depo, DrabornEagle ekosistemindeki **DraBornTranslate** uygulamasının Android kaynak kodunu ve Portal panelini içerir. Proje DrabornEagle tarafından geliştiriliyor ve sürdürülüyor. Diğer projeler için [DrabornEagle GitHub profiline](https://github.com/DrabornEagle) bakabilirsin.

Lisans bilgilerine uygulamada **Görünüm ve oyun paketi → Açık kaynak lisansı** yolundan erişebilirsin. Mevcut CouchSubs/GameTranslator AGPL bildirimleri korunmuştur; v0.4.12'de Google ML Kit hizmeti ve model indirme koşulları da [kurulum rehberinde](docs/KURULUM.md) açıklanır. Lisans ve telif metinleri depodaki [LICENSE](app/src/main/assets/LICENSE) ve [NOTICE](app/src/main/assets/NOTICE) dosyalarında yer alır.

Derleme için JDK 17 ve Android SDK 35 kullanılır.

```bash
./gradlew --no-daemon -PtargetAbi=arm64-v8a :app:testDebugUnitTest :app:assembleDebug
```

Çıktı `app/build/outputs/apk/debug/app-debug.apk` yoluna yazılır. Hata ve önerileri [Sorunlar](https://github.com/DrabornEagle/DraBornTranslate/issues) sayfasından paylaşabilirsin.
