# DraBornTranslate v0.8 — Smart Dialogue Sync

## Kullanım
1. Ana ekranda Smart Dialogue Sync'i aç.
2. GTA VI Explorer'da (https://six.mics.dev/dialogue) görev veya replik ara. Yalnızca erişim ve kullanım iznin olan metinleri kopyala; DraBornTranslate bu sitenin veri tabanını indirmez veya paketlemez.
3. Metin yapıştır ile görev adını ve her satıra bir İngilizce replik ekle veya Dosya ekle ile TXT, SRT veya JSON seç.
4. Görev seç ve ileri/geri düğmeleriyle takip et. Replik bul ile birkaç İngilizce kelime yazıp eşleşmeye atla.
5. Otomatik ilerlet, SRT/JSON zaman farklarına veya ayarlardaki tahmini süreye göre replik değiştirir. Oyundaki duraklama/dallanmayı algılamaz.
6. Ana ekrandaki GTA 6 Türkçe paketini önceden kurduysan çeviri motoru uygun eşleşmeleri öncelikle paketten alır. Türkçe metin içeren JSON doğrudan görüntülenir.

## JSON örneği (özgün demo, gerçek oyun repliği değil)
```json
{"missions":[{"name":"Demo görev","cues":[{"en":"Meet me at the pier.","tr":"İskelede buluşalım.","atMs":0},{"en":"I will be there soon.","tr":"Birazdan orada olacağım.","atMs":4000}]}]}
```

Dosya yerel olarak saklanır. Görev ve replik konumu kaydedilir. Kamera, mikrofon, bilgisayar, Portal bağlantısı gerekmez. GTA VI Explorer bir üçüncü taraf fan sitesidir; toplu veri lisansı ve yeniden dağıtım hakkı doğrulanmadığından oyun diyalogları APK'ya eklenmedi. Bu özellik otomatik canlı oyun senkronizasyonu değildir. Mevcut v0.7.2 modları korunur.

## Doğrulama
TXT/SRT/JSON ayrıştırma, arama, zaman farkı ve JSON yeniden yükleme için birim testleri eklendi. Gerçek cihaz testi ve imzalı Release ayrı doğrulanır.
