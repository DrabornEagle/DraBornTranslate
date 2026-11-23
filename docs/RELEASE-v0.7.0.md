# DraBornTranslate v0.7.0 · Cloud Gaming Translate (Android önizleme)

## Neden farklı bir bağlantı yöntemi?

PS Portal, PlayStation Plus Premium Cloud Streaming oturumunu PS5 konsolundan bağımsız açar. Sony'nin güncel destek belgelerinde bu modda **Share Screen, Share Play ve canlı yayın desteklenmez**; PS Portal'ın doğrudan video akışını üçüncü parti Android uygulamalarına teslim eden belgelenmiş bir API yoktur. Cloud Streaming ekran görüntüleri ve kısa video klipleri PlayStation App'te sonradan görülebilir ama canlı OCR için yeterli değildir.

Kaynaklar:
- https://www.playstation.com/en-us/support/subscriptions/psportal-cloud-game-streaming/
- https://blog.playstation.com/2025/04/09/playstation-portal-remote-player-system-update-releases-today-with-cloud-game-streaming-beta-ux-enhancements/

## İlk çalışan yol: Cloud Camera Translate

- Android telefonun **arka kamerası fiziksel PS Portal ekranına bakar**. Portal'daki cloud oyunu kesilmez, PS5'in yakında veya açık olması gerekmez.
- Tek kamera görüntüsünde **mavi konuşma altyazısı bandı** ve **yeşil GTA 6 telefon mesajı alanı** birlikte algılanır.
- Kamera yazıları cihazda Google ML Kit OCR ile tespit eder; GTA 6 Türkçe paketinin eşleştiği metinler yerel paketten alınır, aksi durumda cihaz içi çeviriye geçilir. Kullanıcı düzeltmelerinin önceliği korunur.
- Sonuçlar **telefon ekranında** ayrı Türkçe konuşma altyazısı ve mesaj blokları olarak gösterilir; Portal ekranı ve Sony hesabı değiştirilmez.
- Yerel kamera kareleri, oyun sesleri ve İngilizce replikler sunucuya yüklenmez; görüntüler dosyaya kaydedilmez.
- **Cloud Gaming Translate** ana ekranda bağımsız bir moda sahiptir. Android kamera izni yalnızca mod açılınca istenir. Telefon yatay kullanılır.

## Kullanım

1. GTA 6'yı PS Portal Cloud Streaming'de aç; oyundaki İngilizce altyazıyı etkinleştir.
2. Telefonu Portal ekranına doğru bakan sabit bir stand üzerine koy. Ekranın çerçevesini kadrajın büyük bölümüne al; yansıma ve parlamayı azalt.
3. DraBornTranslate ana ekranından **Cloud Gaming Translate başlat** seç. Kamera iznine izin ver.
4. Ekrandaki **⚙ Alanları ayarla** seçeneğiyle mavi bandı oyun altyazılarına, yeşil Lens kutusunu GTA 6 içindeki telefon mesajlarına hizala.
5. Konuşma Türkçesi ve oyun telefonu mesajları telefon ekranında birlikte görünür.

## v0.7 sınırlamaları

- **Fiziksel PS Portal ekranına altyazı yazamaz**. Telefon ekranında çeviri gösterilir. Bu özelliğin yalnızca telefonun kamerasıyla mümkün olduğu iddia edilmez; ileride belgelenmiş ve izinli canlı video kaynağı sağlanırsa aynı OCR/çeviri motoru entegre edilebilir.
- Telefon kamerasının optik görüntüsü iyi odaklanmazsa, ışık yansırsa veya çok küçük altyazılar görünürse OCR eksik metin okuyabilir.
- Paket eşleşmesi anlık olabilir fakat **kamera kareleri, OCR ve sonraki model çevirileri sıfır gecikmeli değildir**. Performans ve gerçek GTA 6 cloud gameplay görüntüsüyle test edilmelidir.
- Kamera yalnızca bu ekran açıkken çalışır; Android arka planda kamera erişimini sınırlar.
- Bu mod kamera, OCR ve cihaz üstü çeviri kodunu içerir. Cloud Streaming akışını çözme, şifresini kırma, Portal'a dışarıdan uygulama yükleme veya Sony oturumuna bağlanma özelliği **yoktur**.
- v0.7.0 Android `versionCode 5`. Önizleme Debug imzalıysa eski Release sertifikasıyla üzerine kurulamaz. Uygulamayı kaldırmak yüklü GTA 6 paketi ve kişisel düzeltmeleri silebilir.
- 475.893 satırlık oyun paketinin tamamının dilsel kalitesi yeniden elden geçirilmiş değildir.

## İmzalama ve GitHub yayın koşulları

GitHub Actions test/lint/Debug APK oluşturur. **Üretim imzalı APK**, mevcut üretim sertifikasını içeren GitHub Secrets ve yetkili Release token tanımlanmadan yayımlanamaz. Debug artefaktının varlığı üretim imzalı Release yayımlandığı anlamına gelmez. Lisans gereği açık kaynak telif ve NOTICE dosyaları korunur.
