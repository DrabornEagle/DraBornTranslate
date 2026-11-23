# DraBornTranslate v0.6.3 — Altyazı + Lens Aynı Anda (Android Test)

## 🎮 GTA 6: aynı yayından iki bağımsız çeviri

- **Çevrilecek alan → Altyazı + Lens · Aynı anda:** tek Android ekran paylaşımı izni ile iki OCR okuma alanı birlikte çalışır.
- **Mavi altyazı bandı:** normal oyun diyaloglarını algılar, Türkçe altyazıyı telefonda ve seçilmiş Portal panelinde gösterir.
- **Yeşil Lens dikdörtgeni:** GTA 6'daki telefon mesajları veya görev kartları gibi seçilen bölgenin İngilizcesini okur ve Türkçesini yazının üstünde gösterir.
- Lens bölgesinin **sol, üst, genişlik, yükseklik** değerleri yüzde kaydırıcılarla ayarlanabilir; varsayılan GTA 6 telefon sağ üst şablonu var.
- Konuşma ve telefon mesajları ayrı metin oturumlarıdır; Lens sonucu altyazı bandını veya Portal altyazı panelini yanlışlıkla değiştirmez.
- Lens OCR'si çok satırlı bir mesaj bloğunu mümkünse tek paragraf çevirir. İngilizce mesaj kısaltmaları için sınırlı, elle gözden geçirilmiş Türkçe karşılıklar eklendi.
- 475.893 satırlık GTA 6 paketi öncelikli kalır. Kullanıcının yerel replik düzeltmeleri de korunur.

## Nasıl açılır?

1. **PS Portal'a Bağlan → Çevrilecek alan → Altyazı + Lens · Aynı anda** modunu seç.
2. **Görünüm ve oyun paketi** altında mavi altyazı bandını yerleştir; yeşil Lens alanını GTA 6 telefon mesajlarına göre ayarla.
3. Portal/Remote Play görüntüsünü telefonda aç; **Canlı çeviriyi başlat** ve Android paylaşım izninde ilgili uygulamayı seç.
4. Konuşma altyazıları kendi bandında, telefon mesajları ise seçilen Lens alanındaki konumlarında çevrilir.

## Test ve sınırlamalar

- Derleme: `versionName 0.6.3`, `versionCode 4`, Android ARM64.
- Android tek uygulama ekran paylaşımının gizlilik kısıtları aynen geçerlidir. Ekranda görünmeyen oyun telefon mesajları okunamaz.
- Paralel OCR, **tek cihaz üstü çeviri motorunun kuyruğunu** paylaşır; performans cihaz hızına, ekran çözünürlüğüne, görseldeki metin sayısına ve paket eşleşmesine bağlıdır.
- Lens yalnızca telefonda gerçek oyun görüntüsü üstüne çizilir; fiziksel PS Portal ekranına doğrudan Lens katmanı kurulmaz.
- Mesaj paragraflarının otomatik gruplaması her oyun arayüzünde kusursuz olmayabilir; gerçek GTA 6 ekranında test gerekir.
- Debug APK geliştirici/test imzası taşır. Orijinal Release sertifikası olmadan eski üretim kurulumunun üzerine yüklenmeyebilir; uygulamayı silmek paket/veri/düzeltmeleri silebilir.
- Tam 475.893 satırın yeniden profesyonel çevirisi yapılmış değildir; kaynakların editoryal incelemesi ayrı çalışmadır.

GitHub deposunda zorunlu üçüncü taraf lisans ve telif bildirimleri korunur.
