# GTA 6 görev paketi

**Oyun Paketleri → GTA 6 paketini indir** GitHub’daki manifesti ve ZIP’i indirir. Uygulamayla gelen görev kataloğu çevrimdışı arama için de kullanılabilir. **Görev Ara** Türkçe ve İngilizce başlıkları, oyunda görünen başlıkları ve teknik kodları eşleştirir.

2026.11.26.5 kataloğunda 204 görev ve oyun kaydı bulunur. Birden fazla kodla eşleşen görevler aramada tek kart açar. **Görev Bilgileri** Türkçe sahne rehberlerini ve mevcut kareleri gösterir. Portre ve oyun kareleri yayımlandıkları adreslerden yüklenir.

**Konuşmalar** hazır paketteki özgün Türkçe sahne özetlerini konuşanlar ve konumlarıyla birlikte gösterir. Etkin pakette diyalog satırları varsa ekran bunları sahneye göre sunar. **Hedefler/Kaynaklar** görev hedefleri ve rehber bağlantılarını içerir.

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
| `tr`, `en` | Türkçe metin ve varsa İngilizce kaynak. Yalnız İngilizce sağlandığında cihazda **Türkçeye çevir** seçeneği çıkar. |
| `sceneId` | Görev kaydındaki sahne kimliği |
| `speaker`, `contextTr` | Konuşan kişi ve varsa satırın Türkçe bağlamı |
| `startMs`, `endMs` | Varsa sahnenin başlangıcından itibaren doğrulanmış milisaniye değerleri |

GitHub paketini güncellerken katalogdaki `version` değerini değiştir, komutu yeniden çalıştır ve manifesti yeni ZIP’le aynı commit’te gönder. Dosya adı ZIP’in SHA-256 özetinden üretilir.
