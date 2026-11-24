# DraBornTranslate v0.7.2 — Live Stream Translate

## Yeni özellik: LunaTranslator canlı ağ bağlantısı (deneysel)
- Açık kaynak LunaTranslator, bilgisayarda görülebilen oyun görüntüsünün seçilmiş OCR bölgesinden orijinal metni WebSocket üzerinden yayınlayabilir.
- DraBornTranslate, belgelenen `/api/ws/text/origin` uç noktasına bağlanır. Metin düz UTF-8 WebSocket mesajı olarak alınır; Android cihazda mevcut GTA 6 paket öncelikli çeviri motoruna gönderilir.
- Yalnızca RFC1918 yerel IPv4 adreslerine bağlantı, yönlendirmesiz WebSocket, en yeni bekleyen satır, tekrar filtreleme, eski çeviri yanıtlarını reddetme, 6 saniyelik ekran temizleme, son 8 çeviri.
- Telefon kamerası ve mikrofonu kullanılmaz; bu mod için telefonda oyunu açmak gerekmez. Bilgisayarda ise oyunun okunabilir görüntüsü gerekir.
- Bu bir kaynaklar arası metin aktarımıdır; PS Portal bulut oyununu aynalamaz.

## Kurulum
1. Windows bilgisayarda açık kaynak LunaTranslator'ı resmi GitHub sürümünden kur: https://github.com/HIllya51/LunaTranslator
2. Bilgisayarda oyunun görüntüsünü aç (örneğin uyumlu bir bağımsız bulut istemcisi veya PC'deki görüntülenebilir oyun yayını).
3. LunaTranslator OCR modunda İngilizce altyazının olduğu alanı seç. Ağ hizmetini ve orijinal metin WebSocket çıkışını etkinleştir.
4. Bilgisayar ve Android telefon aynı güvenilir Wi-Fi ağında olsun. Windows güvenlik duvarında yalnızca özel ağ için ilgili porta izin ver; internete port yönlendirme yapma.
5. DraBornTranslate → Canlı ağ altyazısı (LunaTranslator) → bilgisayarın yerel IPv4 adresi ve Luna ağ hizmetinin ayarlı portunu gir.
6. Canlı bağlantıyı başlat. Kaynak yeni metin gönderdiğinde çeviri gösterilir. Ekrandan ayrılınca bağlantı kapanır.

Luna protokolü: https://docs.lunatranslator.org/en/apiservice.html#websocket-service
Açık kaynak uygulama kodu: https://github.com/HIllya51/LunaTranslator/blob/c68357cd30b97ee28127f1475f50f5e661b1ef4a/src/LunaTranslator/textio/textoutput/websocket.py
LunaTranslator ayrı bir uygulamadır; kaynak kodu veya ikili dosyası DraBornTranslate içine kopyalanmamıştır.

## Diğer canlı seçenekler
- Android bulut istemcisi: ayrı, desteklenen Android istemcisini aç; mevcut MediaProjection + OCR + Lens sistemi çalışabilir. Korumalı/siyah görüntüler desteklenmez.
- Con (https://github.com/houqai/Con-app) bağımsız istemci; mevcut Portal oturumunun kapanmasını bekler. GTA 6 ve Android ekran yakalama uyumluluğu doğrulanmadı.
- Chiaki-ng Cloud (https://github.com/oversoul-love/chiaki-ng-cloud) deneysel istemci altyapısı; mevcut Portal oturumunu eş zamanlı aynalama doğrulanmadı.
- Sony, Portal bulut oyununda Share Screen/Share Play/yayın desteği sunmadığını belirtir: https://www.playstation.com/en-us/support/subscriptions/psportal-cloud-game-streaming/
- PS App ekran görüntüsü modu önceki sürümden korunur; canlı çözüm değildir.
- Kamera modu önceki v0.7.1 CameraX düzenlemeleriyle korunur.

## Gizlilik ve doğrulama
- Yerel ağ WebSocket şifrelenmez; yalnızca kendi güvenilir özel ağında kullan. PSN bilgisi, görüntü, mikrofon veya kamera verisi toplanmaz.
- Bu sürümde fiziksel Portal ekranına yazı yerleştirme veya devam eden bulut oturumunu kamerasız/sessiz olarak telefona aktarma desteklenmez.
- Yeni kuyruk ve ağ adresi doğrulamasına JVM birim testleri eklendi. CI sonucu ve gerçek cihaz/Luna bağlantı testi ayrı aşamalardır.
