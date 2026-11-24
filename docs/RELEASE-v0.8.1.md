# DraBornTranslate v0.8.1 — Auto Capture Sync

## İlk kurulum
1. Ana ekrandan Auto Capture Sync aç. Fotoğraf ve video erişimine izin ver.
2. Brief seçeneği açıkken görüntünün sağdaki konuşma alanı okunur; normal altyazı ve diğer metinler için kapat.
3. İstersen kayan panel için diğer uygulamaların üzerinde gösterme izni ver.
4. Otomatik çeviriyi başlat, ardından PS App'i aç. Portal Create ile aldığın yakalamaları PS App'ten indir.
5. Telefonun Pictures/PS App ve Movies/PS App klasörlerine tamamlanmış medya dosyaları geldiğinde sistem otomatik OCR ve mevcut paket öncelikli Türkçe çeviriyi başlatır. Geçmişi uygulamada okuyabilirsin.
6. Eski dosyaları denemek için takibi durdur ve Mevcut dosyaları tara / yeniden çevir seç.
7. Deneysel indirme yardımı için erişilebilirlik ayarlarında DraBornTranslate PS App hizmetini aç ve uygulamadaki kutuyu etkinleştir. PS App'in açık olması gerekir. GTA VI yakalamasının kendisine ait açık erişilebilirlik etiketi ve benzersiz kimlik/tarih bulunuyorsa galeri girdisini açabilir; tekil GTA VI detayında açıkça etiketli indirme düğmesini tıklar. Bu etiketler görünmüyorsa manuel indir. Toplu seçim, koordinat tahmini, hesap girişi ve paylaşım ekranları otomatikleştirilmez.
8. Takibi uygulamadan veya kalıcı bildirimden durdur.

## Teknik
- Android MediaStore, ContentObserver ve 4 saniyelik kontrol. Dosya boyutu iki taramada sabit ve is_pending=0 olmalı.
- JPEG/PNG/WebP ve MP4. Video yalnızca görüntü kareleri (0,75–1 saniye aralık, en fazla ilk 3 dakika). Ses işlenmez.
- OCR ML Kit, mevcut GTA 6 paket öncelikli çeviri; tam normalize edilmiş aynı metinler atlanır. OCR hataları ve tekrar eden gerçek repliklerin ayırt edilememesi mümkündür.
- Yerel geçmiş son 30 dosya, dosya başına 300 metin. Dosya parmak izi tekrar işlemeyi önler. Kullanıcı başlatmalı ön plan hizmeti; Android 15 veri eşitleme süre sınırlarına tabidir.
- PSN şifresi, token veya bulut API çağrısı yok. İndirilen dosyalar başka sunucuya gönderilmez. PS App klasörleri birden fazla oyuna ait olabilir; dosya adından oyun belirlenemez.
- Canlı oyun akışı değildir. Sony'nin yükleme ve indirme süresine bağlıdır. PS App erişilebilirlik etiketleri ve otomatik indirme gerçek cihazda doğrulanmadı.
- Mevcut Smart Dialogue Sync ve diğer modlar korunur.
