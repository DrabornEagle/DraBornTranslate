# DraBornTranslate v0.6.2 — GTA 6 Türkçe kalite güncellemesi (önizleme)

## Düzeltilenler
- GTA 6 Türkçe oyun paketindeki **kesin biçim hatalarını** gideren korumalı kalite katmanı: HTML özel karakterleri, gereksiz boşluklar, kaynak metinle belirgin uyuşmazlıklar ve soru işareti kayıpları.
- Geçersiz/İngilizce kalmış bazı paket satırlarında otomatik yerel çeviri motoruna geçiş.
- **Portal > Son çeviri > Türkçe repliği düzelt:** Hatalı İngilizce repliğin anlamını doğru verdiğin Türkçesini kaydet. Sonraki karşılaşmada, ağ/AI beklemeden kişisel düzeltme öncelikle uygulanır.
- Kaydettiğin düzeltme cihazda saklanır ve silinebilir; çevrimiçi sunucuya aktarılmaz.
- Kullanıcıya görünen paket seçimi ve GitHub ana sayfası DraBornTranslate markasına göre sadeleştirildi. Zorunlu açık kaynak lisans/telif ve kaynak bildirimleri **korundu**.

## Gerçekçi kalite sınırları
**475.893 satırlık paket korunuyor; bu sürüm 475.893 satırı profesyonel çevirmenlerle yeniden çevirmiş değildir.** Paketteki kaynak İngilizce satırlar yalnızca tek yönlü SHA-256 anahtarları olarak depolandığından, tüm hatalı İngilizce/Türkçe çiftlerini görmeden güvenilir bir editoryal yeniden yazım mümkün değildir. Gerçek oyun altyazısı, karakter üslubu, Amerikan argosu ve bağlam için örneklerle ek editör denetimi gereklidir.

## Dağıtım ve uyumluluk
- Android: **versionName 0.6.2**, **versionCode 3**, ARM64.
- GTA 6 paketi cihazda daha önce yüklüyse tekrar indirilmesi gerekmiyor.
- Debug önizleme APK'sı üretim sertifikasıyla imzalanmaz; mevcut sürümün üzerine kurulamayabilir. Uygulamayı kaldırmadan önce yerel verileri yedekle: kaldırma işlemi **GTA 6 paketi ve kişisel düzeltmeleri silebilir**.
- Üretim sürümü yalnızca mevcut orijinal DraBornTranslate keystore'u GitHub Actions Secrets'a tanımlıysa aynı sertifikayla imzalanabilir.
- PS5/TV kurulumu hâlen önizleme ekranıdır; doğrudan PSN/Chiaki eşleştirmesi bu Kotlin sürümünde tamamlanmış değildir.

[Geliştirme kodu ve testler](https://github.com/DrabornEagle/DraBornTranslate) · [Açık kaynak lisans bildirimleri](../app/src/main/assets/NOTICE).
