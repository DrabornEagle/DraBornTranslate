<p align="center">
  <img src="app/src/main/res/drawable-nodpi/brand_mark.webp" width="96" height="96" alt="DraBornTranslate logosu">
</p>

<h1 align="center">DraBornTranslate</h1>
<p align="center"><strong>PlayStation oyunları için canlı Türkçe altyazı</strong></p>
<p align="center">DrabornEagle ekosisteminin Android canlı oyun çevirisi uygulaması.</p>
<p align="center">
  <a href="https://github.com/DrabornEagle/DraBornTranslate/releases/download/v0.4.2/DraBornTranslate-v0.4.2-Android-arm64.apk"><strong>↓ İmzalı APK’yı indir</strong></a>
  · <a href="https://github.com/DrabornEagle/DraBornTranslate/releases/tag/v0.4.2">Sürüm notları</a>
  · <a href="docs/KURULUM.md">Kurulum rehberi</a>
</p>

## İndir ve kur

| Güncel sürüm | Cihaz | Çeviri |
| --- | --- | --- |
| **v0.4.2** | Android 10 ve üzeri, ARM64 | Türkçe, ücretsiz ve API’siz |

1. İmzalı APK’yı indirip Android telefonuna kur. Önceki sürüm aynı imzayı taşıyorsa üzerine kurabilirsin ve daha önce indirdiğin model korunur.
2. İlk kurulumda **Türkçe modeli indir** ile 806 MB’lık modeli cihaza al. Sonraki çeviriler telefonda çevrimdışı çalışır.
3. **Çeviriyi dene** ile ilk sonucu al. **Son çeviri** kartında Türkçeyi ve uygulamanın okuduğu İngilizceyi birlikte görebilirsin.

## Oyunda kullan

| İhtiyacın | Uygulamadaki seçim |
| --- | --- |
| Oyun diyaloglarını takip etmek | **Çevrilecek alan → Altyazı bölgesi** |
| Menüleri ve görev metinlerini okumak | **Çevrilecek alan → Tüm oyun metinleri** |
| Telefonda oyun oynarken altyazı görmek | **Telefonda oyna** ve **PS Remote Play** |
| PS Portal’da oynarken çeviriyi görmek veya dinlemek | **PS Portal** ve **Çeviri çıkışı** |

PS Portal için sürmekte olan oyun görüntüsünü **PlayStation App** üzerinden Android telefonda aç ve **Canlı çeviriyi başlat** seçeneğine dokun. Türkçe çeviri telefon ekranında, Türkçe ses olarak veya deneysel Portal yan panelinde gösterilebilir. PlayStation App, yayın ve panel adımları [kurulum rehberinde](docs/KURULUM.md) yer alıyor.

## v0.4.2’de neler değişti?

- Canlı çeviri, ekrandaki güncel repliğe öncelik veriyor ve sonucu hazır olduğu anda gösteriyor.
- Yaygın oyun ifadeleri doğrudan Türkçeye aktarılıyor. Yeni repliklerde konuşma dili ve deyimler için çeviri iyileştirildi.
- Portal yan paneli yeni metni daha sık kontrol ediyor. Altyazının ekrana gelme süresi telefonun işlemcisine, repliğin uzunluğuna ve yayın kalitesine bağlı.

## DrabornEagle kaynak kodu

Bu depo, DrabornEagle ekosistemindeki **DraBornTranslate** uygulamasının Android kaynak kodunu ve Portal panelini içerir. Proje DrabornEagle tarafından geliştiriliyor ve sürdürülüyor. Diğer projeler için [DrabornEagle GitHub profiline](https://github.com/DrabornEagle) bakabilirsin.

Derleme için JDK 17, Android SDK 35, NDK 28.2.13676358 ve CMake 3.22.1 kullanılır.

```bash
./gradlew --no-daemon -PtargetAbi=arm64-v8a :app:testDebugUnitTest :app:assembleDebug
```

Çıktı `app/build/outputs/apk/debug/app-debug.apk` yoluna yazılır. Hata ve önerileri [Sorunlar](https://github.com/DrabornEagle/DraBornTranslate/issues) sayfasından paylaşabilirsin.
