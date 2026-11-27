# GTA 6 görev paketi

**Oyun Paketleri → GTA 6 paketini indir** GitHub’daki manifesti ve ZIP’i indirir. Uygulamayla gelen görev kataloğu çevrimdışı arama için de kullanılabilir. **Görev Ara** Türkçe ve İngilizce başlıkları, oyunda görünen başlıkları ve teknik kodları eşleştirir.

2026.11.27.1 kataloğundaki 204 kayıt aramada 196 kart açar. Görev araması Türkçe ve İngilizce ana görev, yan görev, hedef ve bölüm filtrelerini içerir. 33 kaydın aşamaları, hedefleri veya teknik kod bağlantıları güncellendi. Aramada açılan kartların 55’i oyun içi harita simgesine bağlıdır. Kartlardaki 370 aşamanın 154’ünde kaynak görseli, 101’inde birden fazla görsel bulunur.

**Konuşmalar → Bütün konuşmayı oku** seçili görevin okunabilir `dialogue` kayıtlarını tek akışta gösterir. **Sahnelere göre** aynı sırayı sahne başlıklarıyla gösterir. **Bu görevde canlı altyazı** üzerinden başlatılan oturum cihazın gördüğü replikleri zaman sırasıyla saklar ve sonradan gelen Türkçe çeviriyi aynı satıra ekler. GitHub görev paketi Konuşmalar açıldığında en fazla saatte bir denetlenir ve doğrulanmış arşiv çevrimdışı kalır. Yayındaki pakette tam oyun konuşması bulunmuyor. **Hedefler/Kaynaklar** görev hedeflerini ve rehber bağlantılarını içerir.

| Kaynak | Kapsam |
| --- | --- |
| [CyberLeek / psviethoa](https://github.com/psviethoa/gta6-text-en) | Xbox Series 1.0.2.1 Amerikan İngilizcesi dökümü. Yaklaşık 579 bin satır, ana tablo, hikâye ve mini oyun metinleri. |
| [GTA VI Explorer görev dizini](https://six.mics.dev/missions) | Bölümler, görev kodları ve görev sayfaları. |
| [GTA VI Explorer diyalog araması](https://six.mics.dev/dialogue) | 136.961 diyalog satırı, İngilizce ve İspanyolca. |
| [PowerPyx görev rehberi](https://www.powerpyx.com/gta-6-full-walkthrough-all-missions/) ve [GTABase](https://www.gtabase.com/gta-6/missions/) | Görev başlıkları, haritadaki adlar, karakterler ve rehber görselleri. |
| [Rockstar Games görselleri](https://www.rockstargames.com/VI/media/screenshots) | Resmî tanıtım görselleri ve karakter profilleri. |
| [GTA VI Explorer harita işaretleri](https://six.mics.dev/ui-art#map-blips) | Katalogdaki görev ve karakter işaretlerinin yayımlanan oyun dokuları. |
| [Lucia & Jason oynanış galerisi](https://www.luciaandjason.com/screenshots#prologue) | Açılışın ve prologun sahneye bağlanan oyun kareleri. |
| [AudaCity yan hikâyesi](https://six.mics.dev/extras/audacity) ve [The Audacity görev rehberi](https://www.powerpyx.com/gta-6-the-audacity-attend-the-launch-party-walkthrough/) | Lansman hedefinin adı, ön koşulu, karakterleri ve sahne ortamları. |

Sahne zamanı için videonun veya sahnenin başlangıcına göre ölçüm gerekir. Metin dökümündeki satır numarası ve hash, videodaki saniyeyi vermez.

## Görev paketi yayını

1. [catalog.json](catalog.json) dosyasında görev bilgilerini düzenle. Her görevin `id` değeri benzersiz olmalı. `chapterOrder` bölümdeki gezinme sırasını, `mapIconUrl` kaynağı eşleştirilmiş harita simgesini verir. `scenes` öğeleri sahne sırasıyla yazılır. Her sahnenin `images` dizisi o aşamaya ait en fazla dört farklı görsel URL’si içerir.
2. Eklemeye yetkili olduğun metinleri `texts/<görev-id>.json` dosyasına sıralı JSON dizisi olarak kaydet. Her satırda aşağıdaki alanları kullan.
3. İsteğe bağlı `media.json` içinde yayıncıya ait görsel URL’lerini `portraits` ve `previews` alanlarında tanımla. `portraitAliases` kısa karakter adlarını portre anahtarlarına bağlar.
4. İsteğe bağlı `conversations.json` içinde görev kimliklerini Türkçe sahne özetlerine bağla. Her özet `titleTr` ve `summaryTr` değerlerini, varsa `locationTr` metnini ve `speakers` dizisini içerir.
5. Katalogdaki `version` değerini artır ve `python3 scripts/gta-mission-pack.py --build --directory packs/gta-6` komutuyla arşivi oluştur. Yeni manifesti ve ZIP’i aynı commit’te yayımla. Uygulama güncellemeyi bu dizinden otomatik alır.

| Alan | İçerik |
| --- | --- |
| `type` | `dialogue`, `objective`, `message`, `hint`, `ui`, `guide` veya `other` |
| `tr`, `en` | Türkçe metin ve varsa İngilizce metin. Yalnız İngilizce sağlandığında cihazda otomatik olarak Türkçeye çevrilir. |
| `subtitleKey` | `dialogue` kaydı için Türkçe GTA 6 altyazı paketindeki 16 haneli hex anahtar. Doğrudan metin yerine yalnızca anahtar da verilebilir. |
| `sceneId` | Varsa görev kaydındaki sahne kimliği |
| `speaker`, `contextTr` | Varsa konuşan kişi ve satırın Türkçe bağlamı |
| `startMs`, `endMs` | Varsa sahnenin başlangıcından itibaren doğrulanmış milisaniye değerleri |

ZIP’in adı SHA-256 özetinden üretilir. Uygulama arşivin boyutunu, özetini, sürümünü, replik anahtarlarını ve varsa sahne referanslarını doğrular.

## Altyazı anahtarları

`scenes` dizisi sahnelerin sırasını belirler. Bir sahnedeki tüm repliklerin `startMs` değeri varsa satırlar zamana göre dizilir. Eşit zamanlarda veya zaman bilgisi eksikse dosyadaki sıraya başvurulur. Satırlardan birinin sahne bilgisi eksikse iki okuma görünümü de tüm satırları dosyadaki sırayla gösterir. Sahne başlığı için `titleTr`, bilinen ortam için `environmentTr` kullan.

`gta-6.gz` bir Türkçe çeviri sözlüğüdür. Görev, sahne, konuşmacı ve sıra bilgilerini görev paketi sağlar. `subtitleKey` alanı İngilizce metnin [PackKey](../../app/src/main/java/com/gametranslator/gametranslator_mobile/core/GamePacks.kt) ile hesaplanan anahtarını kullanır. Uygulama önce `entries`, sonra `segments` tablosunda tam eşleşme arar. Yayımlanmış replikte anahtar varsa ve gerekli altyazı paketi cihazda yoksa onu da otomatik indirir.
