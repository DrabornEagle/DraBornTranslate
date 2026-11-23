# DraBornTranslate v5.0 · Geliştirme kaydı

> Sürüm türü: **Kaynak kodu entegrasyonu / önizleme**. Henüz imzalı v5.0 APK yayımlanmadı.

## 23 Kasım 2026

- GameTranslator v1.8.51 kaynak kodları (1.694 dosya), orijinal lisans ve telif bildirimleri korunarak `upstream/GameTranslator` altında depoya alındı. Orijinal upstream Git SHA: `90074239529de5b4738340d00bbb06f706adef59`.
- `ShortPhrases` kısa İngilizce oyun konuşmalarında daha doğal Türkçe karşılıklar sağlar; `TurkishPostEdit` modeli çevirdiği cümleleri düzelten son aşama olarak etkinleştirildi.
- Hakkında menüsüne GameTranslator kaynak sürümü sorgulama eklendi. Üçüncü taraf APK'ları DraBornTranslate üzerine kurmaz.
- GitHub Actions resmî GitLab sürümlerini günlük kontrol ederek yeni lisanslı kaynakları yalnızca `upstream/GameTranslator` altında eşitler. Oyun diyalog paketleri ve üçüncü taraf dağıtım ikilileri otomatik kopyalanmaz.
- Birim testleri ve GitHub Actions Android derlemesi doğrulandı. Model CDN'sindeki geçici HTTP 502 sorunları için tekrar deneme eklendi.

## v5.0 üretim sürümü için eksikler

- Flutter ile Kotlin Activity/Service yaşam döngüsünü birleştirme.
- Chiaki JNI ve PS5 bağlantı akışını Android ARM64 üzerinde derleyip gerçek cihazda test etme.
- Xbox/TV ekranları ve izinleri; PS Portal ekranı kısıtlarını gerçeğe uygun şekilde ele alma.
- Kullanıcı verisini bozmadan yükseltme; çalışan sürüme yeni modül yerleştirme; yerel OCR testleri.
- Orijinal DraBornTranslate imzalama anahtarı ile sürüm sertifikasının doğrulanması.
- Kaynak kod ve oyun metni paketlerinin telif/lisans şartlarının doğrulanması.

**Mevcut üretim sürümü:** Yayınlanmış en son imzalı sürüm için GitHub Releases bölümüne bak. Android Gradle `versionName` halen `0.4.13` olduğundan, v5.0 kaynağı ile v5.0 dağıtımı eşit değildir.
