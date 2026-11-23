# Gizlilik / Privacy

*Son güncelleme / Last updated: 2026-11-22*

## Türkçe

GameTranslator, Sefflex tarafından yayınlanan açık kaynaklı bir uygulamadır. Hiçbir kişisel veriyi toplamaz.
Reklam, analiz ya da izleme kodu içermez. Sunucularımız aşağıda anlatılan altyazı havuzu ve oyun paketi sunucusudur.

**Bilgisayarda kalanlar**

- PS5'ten gelen oyun görüntüsü yalnız altyazıyı okumak için bellekte işlenir; kaydedilmez, hiçbir yere
  gönderilmez.
- Okunan altyazılar ve çevirileri bilgisayarda üretilir ve yalnız ev ağınızdaki TV'ye gönderilir (çevrimiçi çeviriyi
  açarsanız İngilizce metin DeepL'e de gider; aşağıya bakın).
- PlayStation Network hesap kimliği (sayısal), PS5 eşleme anahtarı ve TV eşleme anahtarı bilgisayarda
  saklanır: Mac'te Anahtar Zinciri'nde, Windows'ta yalnız sizin kullanıcı hesabınızın açabileceği
  şifreli bir dosyada (DPAPI).

**Dışarıyla kurulan bağlantılar**

- **Sony:** PlayStation Network girişi doğrudan Sony'nin sayfasında yapılır; şifrenizi GameTranslator görmez.
  Girişten sonra Sony'den yalnız hesap kimliği alınır, oturum anahtarı saklanmaz.
- **Hugging Face (yalnız Windows):** ilk açılışta çeviri modeli `huggingface.co` adresinden bir kez indirilir.
- **Apple (yalnız Mac):** çeviri dilleri macOS tarafından indirilir ve yönetilir.
- **DeepL (yalnız siz açarsanız):** ayarlarda kendi DeepL anahtarınızı girip çevrimiçi çeviriyi açarsanız okunan
  İngilizce altyazı metni çeviri için DeepL'e (`api.deepl.com` ya da `api-free.deepl.com`) gönderilir. Görüntü,
  ses ve hesap bilgisi gönderilmez. DeepL'in kendi gizlilik koşulları geçerlidir. Anahtar Mac'te Anahtar
  Zinciri'nde, Windows'ta DPAPI ile şifreli dosyada saklanır. Açmazsanız DeepL'e hiçbir şey gönderilmez.
- **Altyazı havuzu (Windows, Mac ve Android; varsayılan açık, ayarlardan kapatılabilir):** hiçbir oyun paketinde
  bulunmayan İngilizce altyazı satırları yeni oyun paketleri hazırlamak için yalnız GameTranslator'ın kendi havuzuna
  (`gametranslator-pool.gametranslator-pool.workers.dev`, Cloudflare üzerinde) gönderilir. Yalnız satırın metni, ilk görüldüğü
  an, biliniyorsa o an oynanan oyunun adı ve PS5 kimliği, uygulama sürümü ve kuruluma özgü rastgele bir kimlik gider.
  Görüntü, ses, IP'ye bağlı kayıt, kullanıcı adı ya da PSN bilgisi gönderilmez. Havuzu kapatırsanız bekleyen satırlar
  da silinir ve hiçbir şey gönderilmez.
- **Oyun paketleri:** yeni ve güncellenen oyun paketleri GameTranslator'ın paket sunucusundan
  (`gametranslator-packs.gametranslator-pool.workers.dev`, Cloudflare üzerinde) indirilir. Bu istekte hiçbir kişisel
  bilgi ya da kimlik gönderilmez.
- **Ev ağı:** PS5 ve LG TV'yi bulmak ve onlarla eşleşmek için yalnız yerel ağ kullanılır.

TV uygulaması altyazıları yalnız ev ağındaki GameTranslator bilgisayarından alır ve internete bağlanmaz.

Sorularınız için: https://gitlab.com/sefflex/GameTranslator/-/issues

## English

GameTranslator is open-source software published by Sefflex. It does not collect personal data and contains no
advertising, analytics or tracking code. The servers we operate are the subtitle pool and the game pack server described below.

**Stays on your computer:** the game picture received from the PS5 is processed in memory only to read
subtitles and is never stored or uploaded. Subtitles and translations are produced on your computer and
sent only to the TV on your home network. Your numeric PlayStation Network account ID and the PS5 and TV
pairing keys are stored locally (Keychain on Mac; a file encrypted for your Windows user account with
DPAPI on Windows).

**External connections:** PlayStation Network sign-in happens on Sony's own page, and GameTranslator never sees
your password; afterwards only the account ID is requested from Sony, and no session token is kept. On
Windows, the translation model is downloaded once from `huggingface.co`. On Mac, translation languages are
downloaded and managed by macOS. If you enter your own DeepL key in settings and turn on online
translation, the English subtitle text that was read is sent to DeepL (`api.deepl.com` or
`api-free.deepl.com`) to be translated; no picture, audio or account data is sent, and DeepL's own privacy
terms apply. The key is stored in the Keychain on Mac and in the DPAPI-encrypted file on Windows. Nothing is
sent to DeepL unless you turn this on. PS5 and TV discovery and pairing use your local network only.

**Subtitle pool (Windows, Mac and Android; on by default, can be turned off in settings):** English subtitle lines
that are not in any game pack are sent only to GameTranslator's own pool
(`gametranslator-pool.gametranslator-pool.workers.dev`, hosted on Cloudflare) so new game packs can be made. Only the
line text, when it was first
seen, the running game's name and PS5 title ID when known, the app version and a random per-install ID are sent. No
picture, audio, IP-linked record, user name or PSN data is sent. Turning the pool off also deletes queued lines.

**Game packs:** new and updated game packs are downloaded from GameTranslator's pack server
(`gametranslator-packs.gametranslator-pool.workers.dev`, hosted on Cloudflare). No personal data or ID is sent.

The TV app receives subtitles only from the GameTranslator computer on your home network and does not connect
to the internet.

Contact: https://gitlab.com/sefflex/GameTranslator/-/issues
