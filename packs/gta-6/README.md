# GTA 6 görev paketi

**Oyun paketleri → GTA 6 paketini indir** düğmesi GitHub’daki manifesti ve ona karşılık gelen ZIP’i indirir. **Görev ara** bölümündeki **Türkçe** ve **İngilizce** seçenekleri başlıkların hangi dilde gösterileceğini ve eşit arama sonuçlarının sırasını belirler. Oyun dosyası kodu ve bilinen diğer adlar iki seçimde de aranabilir. İndirilen görev bilgileri ve metinler çevrimdışı açılır.

Paket, GTA VI Explorer’ın beş bölümde dizinlediği 110 oyun dosyası kaydını ve Papa Audacity’nin altı yan görevini kapsar. Bazı kayıtlar yalnızca oyun dosyasındaki kodla görünür. 116 kaydın tümünde özgün Türkçe rehber metni bulunur. Paket 250 rehber notu ve bir kısa görev hedefi çevirisi içerir. Kaynakta doğrulanabilen konum ve sahne bilgileri ilgili metnin yanında görünür. Ayrıntısı sınırlı kayıtlarda bilinen görev kodu ve kaynak bağlantısı gösterilir. Görev bilgileri ve görev metinleri ekranlarında kaynak bağlantıları bulunur.

| Kaynak | Kapsam |
| --- | --- |
| [CyberLeek / psviethoa](https://github.com/psviethoa/gta6-text-en) | Xbox Series 1.0.2.1 Amerikan İngilizcesi dökümü. Yaklaşık 579 bin satır, ana tablo, hikâye ve mini oyun metinleri. |
| [GTA VI Explorer görev dizini](https://six.mics.dev/missions) | Bölümler, görev kodları ve görev sayfaları. |
| [GTA VI Explorer diyalog araması](https://six.mics.dev/dialogue) | 136.961 diyalog satırı, İngilizce ve İspanyolca. |
| [AudaCity yan hikâyesi](https://six.mics.dev/extras/audacity) ve [The Audacity görev rehberi](https://www.powerpyx.com/gta-6-the-audacity-attend-the-launch-party-walkthrough/) | Lansman hedefinin adı, ön koşulu, karakterleri ve sahne ortamları. |

Sahne zamanı için videonun veya sahnenin başlangıcına göre ölçüm gerekir. Metin dökümündeki satır numarası ve hash, videodaki saniyeyi vermez.

## Kendi görev paketini hazırlama

1. [catalog.json](catalog.json) dosyasında görev bilgilerini düzenle. Her görevin `id` değeri benzersiz olmalı. İsteğe bağlı `scenes`, `requires`, `sourceUrl` ve `textSourceUrl` sahne bilgisini, ön koşulları ve kaynakları bağlar.
2. Eklemeye yetkili olduğun metinleri `texts/<görev-id>.json` dosyasına sıralı JSON dizisi olarak kaydet. Her satırda aşağıdaki alanları kullan.
3. `python3 scripts/gta-mission-pack.py --build --directory <paket-klasörü>` komutuyla ZIP’i oluştur. Android’de **Oyun paketleri → Görev paketi seç (.zip)** düğmesiyle yükle. Özel ZIP aramada önceliklidir. **Yüklü paketleri yönet** menüsünden özel ZIP’i kaldırarak GitHub paketine dönebilirsin.

| Alan | İçerik |
| --- | --- |
| `type` | `dialogue`, `objective`, `message`, `hint`, `ui`, `guide` veya `other` |
| `tr`, `en` | Türkçe metin ve varsa İngilizce kaynak. Yalnız İngilizce sağlandığında satır için cihazda **Türkçeye çevir** seçeneği çıkar. |
| `sceneId` | Görev kaydındaki sahne kimliği |
| `speaker`, `contextTr` | Konuşan kişi ve varsa satırın Türkçe bağlamı |
| `startMs`, `endMs` | Varsa sahnenin başlangıcından itibaren doğrulanmış milisaniye değerleri |

GitHub paketini güncellerken katalogdaki `version` değerini değiştir, komutu yeniden çalıştır ve manifesti yeni ZIP’le aynı commit’te gönder. Dosya adı ZIP’in SHA-256 özetinden üretilir.
