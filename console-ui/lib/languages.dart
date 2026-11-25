// SPDX-License-Identifier: AGPL-3.0-only
// Çeviri dilleri. Listenin tek tanımı Android tarafındadır (core/Languages.kt: kod, kendi dilindeki ad, İngilizce ve
// Türkçe ad, yazım yönü, hangi motor çevirebiliyor); burası onu köprüden bir kez okur.
import 'bridge.dart';
import 'l10n.dart';

class Lang {
  const Lang(this.code, this.native, this.english, this.turkish, {this.rtl = false, this.deepl = true, this.mlkit = true});

  final String code;

  /// Dilin kendi dilindeki adı.
  final String native;
  final String english;
  final String turkish;
  final bool rtl;

  /// DeepL ve cihaz üstü çeviri (ML Kit) bu dile çevirebiliyor mu? Çeviremiyorsa o dilde Google kullanılır.
  final bool deepl;
  final bool mlkit;

  /// Arayüz dilindeki adı (Türkçe arayüzde Türkçe, öbür dillerde İngilizce).
  String get local => L10n.lang == 'tr' ? turkish : english;

  /// Seçicide: kendi dilindeki ad ve arayüz dilindeki ad ("日本語 · Japonca").
  String get label => native.toLowerCase() == local.toLowerCase() ? native : '$native · $local';

  bool supports(String engine) => switch (engine) {
        'deepl' => deepl,
        'mlkit' => mlkit,
        _ => true,
      };
}

class Languages {
  static List<Lang> targets = const [];
  static List<String> sources = const [];

  static Future<void> load() async => apply(await Bridge.languages());

  static void apply(Map<Object?, Object?> map) {
    targets = [
      for (final item in (map['targets'] as List<Object?>? ?? const []).cast<Map<Object?, Object?>>())
        Lang(
          item['code'] as String,
          item['native'] as String,
          item['english'] as String,
          item['turkish'] as String,
          rtl: item['rtl'] as bool? ?? false,
          deepl: item['deepl'] as bool? ?? true,
          mlkit: item['mlkit'] as bool? ?? true,
        ),
    ];
    sources = (map['sources'] as List<Object?>? ?? const []).cast<String>();
  }

  static Lang? find(String code) {
    for (final lang in targets) {
      if (lang.code == code) return lang;
    }
    return null;
  }

  /// Seçicideki ad; liste okunamadıysa kodun kendisi.
  static String label(String code) => find(code)?.label ?? code;

  /// Yalnız arayüz dilindeki ad ("Japonca").
  static String local(String code) => find(code)?.local ?? code;
}
