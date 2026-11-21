<p align="center">
  <img src="app/src/main/res/drawable-nodpi/brand_mark.webp" width="96" height="96" alt="DraBornTranslate logosu">
</p>

<h1 align="center">DraBornTranslate</h1>
<p align="center"><strong>PlayStation oyunları için canlı Türkçe altyazı</strong></p>
<p align="center">DrabornEagle ekosisteminin Android canlı oyun çevirisi uygulaması.</p>
<p align="center">
  <a href="https://github.com/DrabornEagle/DraBornTranslate/releases/download/v0.4.8/DraBornTranslate-v0.4.8-Android-arm64.apk"><strong>↓ İmzalı APK’yı indir</strong></a>
  · <a href="https://github.com/DrabornEagle/DraBornTranslate/releases/tag/v0.4.8">Sürüm notları</a>
  · <a href="docs/KURULUM.md">Kurulum rehberi</a>
</p>

## İndir ve kur

| Güncel sürüm | Cihaz | Çeviri |
| --- | --- | --- |
| **v0.4.8** | Android 10 ve üzeri, ARM64 | Türkçe, çevrimdışı ve ücretsiz |

1. İmzalı APK’yı indirip Android telefonuna kur. Önceki sürüm aynı imzayı taşıyorsa üzerine kurabilirsin.
2. Çeviri modeli APK’ya dahildir ve telefonda çevrimdışı çalışır.
3. **Çeviriyi dene** ile ilk sonucu al. **Son çeviri** kartında Türkçeyi ve uygulamanın okuduğu İngilizceyi birlikte görebilirsin.

## Oyunda kullan

| İhtiyacın | Uygulamadaki seçim |
| --- | --- |
| Oyun diyaloglarını takip etmek | **Çevrilecek alan → Altyazı bölgesi** |
| Menüleri ve görev metinlerini okumak | **Çevrilecek alan → Tüm oyun metinleri** |
| Telefonda oyun oynarken altyazı görmek | **Telefonda oyna** ve **PS Remote Play** |
| PS Portal’da oynarken çeviriyi görmek veya dinlemek | **PS Portal** ve **Çeviri çıkışı** |

PS Portal için sürmekte olan oyun görüntüsünü **PlayStation App** üzerinden Android telefonda aç ve **Canlı çeviriyi başlat** seçeneğine dokun. Türkçe çeviri telefon ekranında, Türkçe ses olarak veya deneysel Portal yan panelinde gösterilebilir. PlayStation App, yayın ve panel adımları [kurulum rehberinde](docs/KURULUM.md) yer alıyor.

## v0.4.8’de neler değişti?

- Kısa cevaplar, önceki altyazıda geçen soru veya eylemden yararlanıyor.
- Yaygın oyun deyimleri, konuşma kalıpları ve yol tarifleri anlamı korunarak Türkçeleştiriliyor.
- Motor, çeviri adayları arasından sayıları ve kapı kodu gibi değerleri koruyan sonucu tercih ediyor.

## DrabornEagle kaynak kodu

Bu depo, DrabornEagle ekosistemindeki **DraBornTranslate** uygulamasının Android kaynak kodunu ve Portal panelini içerir. Proje DrabornEagle tarafından geliştiriliyor ve sürdürülüyor. Diğer projeler için [DrabornEagle GitHub profiline](https://github.com/DrabornEagle) bakabilirsin.

Derleme için JDK 17 ve Android SDK 35 kullanılır.

```bash
./gradlew --no-daemon -PtargetAbi=arm64-v8a :app:testDebugUnitTest :app:assembleDebug
```

Çıktı `app/build/outputs/apk/debug/app-debug.apk` yoluna yazılır. Hata ve önerileri [Sorunlar](https://github.com/DrabornEagle/DraBornTranslate/issues) sayfasından paylaşabilirsin.
