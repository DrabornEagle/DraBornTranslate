# DraBornTranslate v0.7.11

**Görev Detayı → Görev Metinleri → Konuşmalar** görünümü, kayıtlarla eşleştirilen görevlerde ara sahneleri, yolculukları ve görev içi konuşmaları olay sırasıyla gösterir. **Sahnelere göre** ve **Hızlı Oku** aynı sırayı kullanır. **Ek replikleri göster** alternatifleri ve yeri doğrulanamayan konuşmaları açar. Cihazda daha önce kaydedilmiş bir oynanış varsa **Oynanış kaydımı göster** o oturumdaki zaman sırasını kullanır.

59 görev için 8.333 satır görev akışında yer alır. 24 satır Türkçe altyazı paketinden geri alındı. Önceki APK’da bulunan 71.659 repliğin tamamı erişilebilir. Sağlam bir eşleştirme kurulamadığında **Arşiv sırası** gösterilir. Oyun içi seçimler, oyuncunun kullandığı karakter ve farklı görev denemeleri başka replikleri tetikleyebilir.

## Bu sürümü derle

Bu dizin v0.7.11’in Android değişikliğini, görev akışını hazırlayan kodu ve derlemede kullanılan planı içerir. Derleyici [v0.7.10 APK’sını](https://github.com/DrabornEagle/DraBornTranslate/releases/tag/v0.7.10) taban olarak alır ve SHA-256 değerini doğrular. Ana dalda tarihsel Gradle projesi de bulunur.

Linux’ta Python 3.11+, JDK 17+, Android SDK platform 35 ve build-tools 35.0.0, smali/baksmali 2.5.2 ve dex2jar 2.4 kullan. PATH’e JDK’nın bin dizinini ekle. Aşağıdaki komut hizalanmış bir APK oluşturur.

```bash
python3 build_apk.py DraBornTranslate-v0.7.10-Kisisel-Android-arm64.apk \
  DraBornTranslate-v0.7.11-unsigned.apk \
  --android-tools "$ANDROID_SDK_ROOT/build-tools/35.0.0" \
  --android-jar "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
  --smali-tools "/path/to/smali-jars/*" \
  --dex2jar /path/to/dex2jar/d2j-dex2jar.sh
```

Uygulama kimliği `com.draborneagle.draborntranslate.v5`, sürüm kodu 29 ve asgari Android sürümü 10’dur. Yayımlanan sürüm, orijinal v5 anahtarıyla imzalandı. Yetkili imza için `--keystore-zip` parametresi yerel anahtar arşivini alır. Paket kimliği, imza sertifikası ve ZIP hizalaması derlemenin sonunda denetlenir.

## Görev verisini denetle

`flow-rules.json` The Missing Link, Bricked Up, Credit Check ve Gone Fishing için elle incelenen sahne sırasını tanımlar. `prepare_flow.py`, yerel oynanış sayfaları, İngilizce metin dizini ve Türkçe dil paketiyle planı yeniden üretir. `flow-plan.json` yayımlanan derlemenin sabit girdisidir. Kaynak satırları konumları ve altyazı anahtarları ile eşleştirir.

`verify_dialogue.py` gerçek APK’dan görev arşivlerini ve DEX dosyalarını okur. Arama sonuçlarını, replik sayılarını, iki okuma görünümündeki sırayı ve dört örnek görevin sahne geçişlerini denetler. Derleme araçlarına ek olarak JVM için bir org.json JAR’ı gerekir.

Oynanış sırası ve sahne referansları [The Missing Link](https://leonidex.com/missions/the-missing-link), [Bricked Up](https://leonidex.com/missions/bricked-up), [Credit Check](https://leonidex.com/missions/credit-check) ve [Gone Fishing](https://leonidex.com/missions/gone-fishing) kayıtlarıyla karşılaştırıldı. Altyazı eşleştirmelerinde [GTA VI Dialogue Explorer](https://six.mics.dev/dialogue) ve [İngilizce metin dizini](https://github.com/psviethoa/gta6-text-en) kullanıldı.
