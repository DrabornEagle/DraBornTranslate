# DraBornTranslate v0.7.13

Görev Detayı konuşmaları APK içindeki arşivden açar. Arşiv okuması bittiğinde ekrandaki yükleniyor durumu sona erer. Sayfayı başa alan ok başlığın sağındadır. **Daha Fazla Oku** sıradaki konuşmaları gösterir.

197 görev kartı tarandı. 54 kartta daha sahne akışı açıldı. Toplam 145 görevde 13.594 replik sahne sırasıyla gösteriliyor. 52 kart için görev akışına bağlanabilen bir konuşma dizisi henüz doğrulanamadı. Buna kısa etkileşimler ve birden fazla oynanış dalına ait kayıtlar da dahil. **Saçını Kestir** kartında kuaför hedefi görüntülenir.

Görev portreleri, eşlenen görevlerde oyunun kendi harita simgesini kullanır. Brian’ın ana görev rozeti pembe zeminli koyu B, yan görev rozeti koyu zeminli pembe harftir. Oyun simgesi bulunmayan kartlarda bilinen karakterin harfleri gösterilir.

## Android derlemesi

Bu sürüm, [yayımlanan v0.7.12 imzalı APK](https://github.com/DrabornEagle/DraBornTranslate/releases/tag/v0.7.12) üzerinden hazırlanır. Bu dizin v0.7.13 Android ekran değişikliklerini ve görev verisi güncellemesini içerir. Derleme, temel APK’nın SHA-256 değerini ve imzasını doğrular.

Linux, Python 3.11+, JDK 17+, Android SDK Platform 35, Build Tools 35.0.0, smali ve baksmali 2.5.2 ile dex2jar 2.4 gerekir. JDK’nın bin dizinini PATH’e ekle.

```bash
python3 build_apk.py DraBornTranslate-v0.7.12-Kisisel-Android-arm64.apk \
  DraBornTranslate-v0.7.13-unsigned.apk \
  --android-tools "$ANDROID_SDK_ROOT/build-tools/35.0.0" \
  --android-jar "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
  --smali-tools "/path/to/smali-jars/*" \
  --dex2jar /path/to/dex2jar/d2j-dex2jar.sh
```

Paket kimliği `com.draborneagle.draborntranslate.v5`, Android sürüm kodu 31 ve desteklenen en düşük Android sürümü 10’dur. Yayımlanan ARM64 APK önceki sürümün v5 anahtarıyla imzalandı. Yetkili imzalama için `--keystore-zip` yerel anahtar arşivini kabul eder.

## Görev kaynakları ve doğrulama

`scene-plan.json` derleme girdisidir. `rebuild_gaps.py`, önceki arşivi `d_*.json` ve `dialogue-index.json` oyun metni dizinleriyle karşılaştırır. Görevin kendi numaralı replik blokları ve kayıtlarla kontrol edilen sahneler akışa yerleştirilir. Türkçe metinler, mevcut arşivden ve Türkçe dil paketindeki altyazı anahtarlarından gelir. `reviewed-scenes.json` elle eşleştirilen sahnelerin rehberlerini ve karşılık gelen oyun karelerini içerir. `compile_mission_icons.py` önceki APK’daki simgeleri görevlerle eşler.

`verify_dialogue.py` sınıfları ve iki görev arşivini doğrudan APK’dan çıkarır. Aramayı, konuşmacıları, sahne sırasını, görselleri, simge eşleşmelerini ve Android kitaplıklarının bütünlüğünü kontrol eder. JVM’de çalışan bir org.json JAR’ı gerekir.

- [GTA VI Explorer](https://six.mics.dev/dialogue)
- [GTA 6 İngilizce metin dizini](https://github.com/psviethoa/gta6-text-en)
- [GameTranslator v1.9.3](https://gitlab.com/sefflex/GameTranslator/-/releases/v1.9.3)
- [GTA 6 harita simgeleri ve renkleri](https://www.gtabites.com/guides/gta-6-icons-explained)
