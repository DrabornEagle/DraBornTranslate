# GTA 6 görev paketi

**Oyun Paketleri → GTA 6 paketini indir** GitHub’daki manifesti ve ZIP’i indirir. Uygulamayla gelen görev kataloğu çevrimdışı arama için de kullanılabilir. **Görev Ara** Türkçe ve İngilizce başlıkları, oyunda görünen başlıkları ve teknik kodları eşleştirir.

2026.11.26.6 kataloğunda 204 görev ve oyun kaydı bulunur. Birden fazla kodla eşleşen görevler aramada tek kart açar. **Görev Bilgileri** bölümünde aşamalar, sahne özetlerinin üstünde yer alır. Aramada açılan 197 görev kartının 195’inde toplam 311 Türkçe sahne özeti bulunur. Portre ve oyun kareleri yayımlandıkları adreslerden yüklenir.

**Konuşmalar** etkin görev paketindeki veya bağlı replik paketindeki `dialogue` kayıtlarını sahne ve konuşmacı bilgisiyle satır satır gösterir. Hazır görev paketi 0 replik içerir. **Hedefler/Kaynaklar** görev hedefleri ve rehber bağlantılarını içerir.

| Kaynak | Kapsam |
| --- | --- |
| [CyberLeek / psviethoa](https://github.com/psviethoa/gta6-text-en) | Xbox Series 1.0.2.1 Amerikan İngilizcesi dökümü. Yaklaşık 579 bin satır, ana tablo, hikâye ve mini oyun metinleri. |
| [GTA VI Explorer görev dizini](https://six.mics.dev/missions) | Bölümler, görev kodları ve görev sayfaları. |
| [GTA VI Explorer diyalog araması](https://six.mics.dev/dialogue) | 136.961 diyalog satırı, İngilizce ve İspanyolca. |
| [PowerPyx görev rehberi](https://www.powerpyx.com/gta-6-full-walkthrough-all-missions/) ve [GTABase](https://www.gtabase.com/gta-6/missions/) | Görev başlıkları, haritadaki adlar, karakterler ve rehber görselleri. |
| [Rockstar Games görselleri](https://www.rockstargames.com/VI/media/screenshots) | Resmî tanıtım görselleri ve karakter profilleri. |
| [AudaCity yan hikâyesi](https://six.mics.dev/extras/audacity) ve [The Audacity görev rehberi](https://www.powerpyx.com/gta-6-the-audacity-attend-the-launch-party-walkthrough/) | Lansman hedefinin adı, ön koşulu, karakterleri ve sahne ortamları. |

Sahne zamanı için videonun veya sahnenin başlangıcına göre ölçüm gerekir. Metin dökümündeki satır numarası ve hash, videodaki saniyeyi vermez.

## Kendi görev paketini hazırlama

1. [catalog.json](catalog.json) dosyasında görev bilgilerini düzenle. Her görevin `id` değeri benzersiz olmalı. İsteğe bağlı `scenes`, `requires`, `mapLabels`, `giver`, `sourceUrl`, `textSourceUrl`, `gameDataSourceUrl`, `profileSourceUrl` ve `gallerySourceUrl` alanları sahne bilgilerini, farklı adları ve kaynakları bağlar.
2. Eklemeye yetkili olduğun metinleri `texts/<görev-id>.json` dosyasına sıralı JSON dizisi olarak kaydet. Her satırda aşağıdaki alanları kullan.
3. İsteğe bağlı `media.json` içinde yayıncıya ait görsel URL’lerini `portraits` ve `previews` alanlarında tanımla. `portraitAliases` kısa karakter adlarını portre anahtarlarına bağlar.
4. İsteğe bağlı `conversations.json` içinde görev kimliklerini Türkçe sahne özetlerine bağla. Her özet `titleTr` ve `summaryTr` değerlerini, varsa `locationTr` metnini ve `speakers` dizisini içerir.
5. `python3 scripts/gta-mission-pack.py --build --directory <paket-klasörü>` komutuyla ZIP’i oluştur. Android’de **Oyun paketleri → Görev paketi seç (.zip)** düğmesiyle yükle. Özel ZIP aramada önceliklidir. **Yüklü paketleri yönet** menüsünden özel ZIP’i kaldırarak GitHub paketine dönebilirsin.

| Alan | İçerik |
| --- | --- |
| `type` | `dialogue`, `objective`, `message`, `hint`, `ui`, `guide` veya `other` |
| `tr`, `en` | Türkçe metin ve varsa İngilizce metin. Yalnız İngilizce sağlandığında cihazda otomatik olarak Türkçeye çevrilir. |
| `sceneId` | Görev kaydındaki sahne kimliği |
| `speaker`, `contextTr` | Konuşan kişi ve varsa satırın Türkçe bağlamı |
| `startMs`, `endMs` | Varsa sahnenin başlangıcından itibaren doğrulanmış milisaniye değerleri |

GitHub paketini güncellerken katalogdaki `version` değerini değiştir, komutu yeniden çalıştır ve manifesti yeni ZIP’le aynı commit’te gönder. Dosya adı ZIP’in SHA-256 özetinden üretilir.

## GitHub’dan replikleri eşitleme

1. Yayımlama izni olan replikler için yukarıdaki biçimde bir paket oluştur. Katalogdaki görev `id` değerlerini kullan, sahne dizisini okuma sırasına göre yaz ve her replikte `sceneId` ile `speaker` belirt. Görev başına satırlar sahne içindeki yazılış sırasıyla görünür.
2. `manifest.json` dosyasını ve üretilen ZIP’i herkese açık GitHub deponda aynı dizine koy. Uygulama GitHub’daki dosya bağlantısını veya `raw.githubusercontent.com` bağlantısını kabul eder.
3. Android’de **Oyun Paketleri → Replik paketi bağla** seç ve manifest bağlantısını gir. Uygulama eşleşen görev ve replik sayısını gösterir. **Konuşmalar** açıldığında en fazla saatte bir güncelleme aranır. **Replik paketini güncelle** üzerinden hemen kontrol edebilirsin.

Son doğrulanan dosya cihazda saklanır. Bir görev özel ZIP içinde replik içeriyorsa o satırlar önceliklidir.
