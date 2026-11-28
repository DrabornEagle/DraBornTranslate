# DraBornTranslate v0.7.12

**Görev Ara/Oku → Görev Detayı → Görev Metinleri** bölümünde bir görev akışı görünür. Sahne başlığı sinematik ara sahne, araç içi yolculuk, kaçış veya ilgili oynanış bölümünü belirtir. Karakter isimleri ve eşleştirilen oyun kareleri kendi sahnelerinde gösterilir. Sayfa sonunda yer alan ok, ekranı yukarı kaydırır.

Görev akışını hazırlarken kayıtlardaki sıra, konuşmacı etiketleri ve metin eşleşmeleri birlikte kullanılır. Türkçe satırlar GTA 6 dil paketindeki altyazı anahtarları ile alınır. Kaynakta yalnızca ilk ve son satır gözlemlendiyse, aradaki kayıtlar ancak aynı numaralı blokta eksiksiz bir sıra bulunduğunda eklenir. Akış eşleşmesi bulunmayan bir görev, telefonda varsa o görevin oynanış kaydını gösterir.

91 görevin akışında 10.663 replik gösterilir. Türkçe dil paketinden 1.087 replik geri eklendi. Daha önce yalnızca görev rehberi bulunan 5 görevde konuşmalar açıldı. 835 sahnenin 750 tanesinde gerçek oyun kareleri, 314 tanesinde birden fazla kare bulunur.

## Android derlemesi

Bu sürüm doğrulanmış [v0.7.11 imzalı APK’sını](https://github.com/DrabornEagle/DraBornTranslate/releases/tag/v0.7.11) temel alır. Orijinal sürümün SHA-256 değeri derlemeden önce kontrol edilir. Bu dizindeki kaynaklar Android ekran değişikliğini ve apk ile dağıtılan sahne verisini yeniden üretir.

Linux, Python 3.11+, JDK 17+, Android SDK platform 35 ve build-tools 35.0.0, smali/baksmali 2.5.2 ve dex2jar 2.4 gerekir. JDK’nın bin dizinini PATH’e ekle.

```bash
python3 build_apk.py DraBornTranslate-v0.7.11-Kisisel-Android-arm64.apk \
  DraBornTranslate-v0.7.12-unsigned.apk \
  --android-tools "$ANDROID_SDK_ROOT/build-tools/35.0.0" \
  --android-jar "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
  --smali-tools "/path/to/smali-jars/*" \
  --dex2jar /path/to/dex2jar/d2j-dex2jar.sh
```

Paket kimliği `com.draborneagle.draborntranslate.v5`, sürüm kodu 30 ve asgari Android sürümü 10’dur. Yayımlanan ARM64 APK, aynı v5 sürüm anahtarıyla imzalandı. `--keystore-zip` parametresi, yetkili imza için yerel anahtar arşivi kabul eder.

## Veri ve doğrulama

`scene-plan.json` sabit derleme girdisidir. `rebuild_flow.py` önceki görev arşivi, v0.7.11 akış planı, oynanış ve sahne sayfaları, altyazı dizini ve Türkçe dil paketiyle bu girdiyi hazırlar. `focus-routes.json` ve `scene_context.json` elle karşılaştırılan örnek görevlerin bölüm ve sahne bağlamlarını içerir.

`verify_dialogue.py` APK’nın görev paketlerini doğrular ve doğrudan APK’dan çıkarılan sınıflarla okuma sırasını, sahne türlerini, görselleri, konuşmacıları ve arama sonuçlarını denetler. Kontrol için JVM’de çalışan bir org.json JAR’ı da gerekir.

Sahne eşleşmeleri [Leonidex görev kayıtları](https://leonidex.com/missions), [GTA VI Explorer](https://six.mics.dev/dialogue) ve [GTA 6 Türkçe dil paketi](https://gitlab.com/sefflex/GameTranslator/-/releases/v1.9.3) ile hazırlandı.
