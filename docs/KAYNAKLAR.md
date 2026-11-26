# Açık kaynak bileşenleri

DraBornTranslate, [GNU AGPL-3.0](../app/src/main/assets/LICENSE) kapsamında dağıtılır. [Telif bildirimleri](../app/src/main/assets/NOTICE) kaynak koduyla birlikte dağıtılır.

Android paket okuma ve Remote Play köprüsü, [GameTranslator v1.8.51](https://gitlab.com/sefflex/GameTranslator/-/tree/90074239529de5b4738340d00bbb06f706adef59) ve [CouchSubs](https://github.com/yorulmazsinan/couchsubs) çalışmalarından gelir. DraBornTranslate v5.0, Kasım 2026’da Android arayüzü, paket önceliği, DraBorn Odak, yakalama işleme ve Portal bağlantı akışını güncelledi. [Geçerli kaynak sürümü](../scripts/upstream-revision.json) depoda tutulur.

DraBornTranslate v0.6, GameTranslator’ın Windows sürümünde yayımlanan GTA 6 bölgelerini, telefon panelini ve hatalı OCR karşısında paket çevirisini koruma davranışını Android ve Portal’a uyarlar. Android kodu ML Kit, mevcut oyun paketi okuyucusu ve Töz-Çevir altyapısını kullanır. [GameTranslator GTA okuma kaynakları](https://gitlab.com/sefflex/GameTranslator/-/tree/oyun-profilleri) · [1.8.55 sürüm notları](https://gitlab.com/sefflex/GameTranslator/-/releases/v1.8.55)

DraBornTranslate v0.7, GameTranslator 1.9.3 Windows sürümündeki Capiche ve destek bağlantılarını, oyun penceresini ve duraklatma menüsü düzeltmesini Android’e uyarlar. Upstream 1.9.3 etiketinin Android kaynakları 16 Kasım 2026 tarihli `70e12ef2` commit’ine bağlıdır. DraBornTranslate’in Android tabanı 23 Kasım 2026 tarihli `90074239` commit’ini kullanır. [GameTranslator 1.9.3 sürümü](https://gitlab.com/sefflex/GameTranslator/-/releases/v1.9.3) · [Capiche](https://capiche.gg/tr)

| Bileşen | Kaynak |
| --- | --- |
| PS5 protokolü | [chiaki-ng v1.10.1](https://github.com/chiaki-ng/chiaki-ng/tree/f30da0aaeb96fc0026cc03683153f682352e7d7c) |
| Ekranda yazı tanıma | [ML Kit Latin OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) |
| Çevrimdışı tutarlılık ve metin çevirisi | [Töz-Çevir-1](https://huggingface.co/TozAI/Toz-Cevir-1/tree/6476d3e0b4b189891fcc07a262ec2d6473c69ae2) ve [ONNX Runtime](https://github.com/microsoft/onnxruntime) |
| Yerel ağ bağlantısı | [OpenSSL 3.5.9](https://github.com/openssl/openssl/tree/openssl-3.5.9) ve chiaki-ng’nin kaynak alt bağımlılıkları |

Türkçe GTA 6 verisi, uygulama içinden ayrı bir paket olarak indirilebilir. Paket dosyaları APK’ya ve kaynak deposuna dahil edilmez.

PS5/TV arayüzü `console-ui/` altında, Android bağlantı ve TV istemcisi bileşenleri kendi kaynak ad alanlarında bulunur. Kaynak arayüzündeki çeviri seçenekleri İngilizce → Türkçe oyun paketi ve cihazdaki Töz-Çevir modeline bağlanır. [Konsol modülü lisansı](../console-ui/LICENSE) · [Inter yazı tipi lisansı](../console-ui/assets/fonts/Inter-LICENSE)
