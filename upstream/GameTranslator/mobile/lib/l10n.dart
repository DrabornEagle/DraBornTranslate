// SPDX-License-Identifier: AGPL-3.0-only
// Arayüz dili: küçük, elle yazılmış metin tabloları (kod üretimi yok). Her dil `l10n/<kod>.dart` içinde tek bir
// `Map<String, String>`'tir; yeni bir dil eklemek için o dosyayı yazıp aşağıdaki [L10n.tables]'a bir satır eklemek
// yeter, ekranlara dokunulmaz.
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

import 'l10n/en.dart';
import 'l10n/tr.dart';

class L10n {
  /// Arayüz dilleri. Sıra, "Uygulama dili" seçicisindeki sıradır.
  static const Map<String, Map<String, String>> tables = {'tr': trStrings, 'en': enStrings};

  /// Tablosu olmayan sistem dillerinde ve eksik anahtarlarda kullanılan dil.
  static const fallback = 'en';

  /// Ayardaki değer: 'auto' (telefonun dili) ya da bir tablo kodu.
  static String setting = 'auto';

  /// Şu an gösterilen dil (tablo kodu).
  static String lang = resolve('auto', _systemCode());

  static String _systemCode() => WidgetsBinding.instance.platformDispatcher.locale.languageCode;

  /// [setting] ve telefonun dili ([system], ör. 'tr') → gösterilecek tablo.
  static String resolve(String setting, String system) {
    if (tables.containsKey(setting)) return setting;
    return tables.containsKey(system) ? system : fallback;
  }

  /// Dili ayarlar ve açık olan bütün ekranları yeniden çizdirir (uygulama yeniden başlamaz, sayfalar kapanmaz).
  static void apply(String value) {
    setting = value == 'auto' || tables.containsKey(value) ? value : 'auto';
    final next = resolve(setting, _systemCode());
    if (next == lang) return;
    lang = next;
    rebuildAll();
  }

  /// Telefonun dili değişmiş olabilir ("Otomatik" seçiliyken).
  static void refreshSystem() => apply(setting);

  /// Yalnız testler için: dili doğrudan seçer.
  static void debugSet(String code) {
    setting = code;
    lang = resolve(code, fallback);
  }

  /// Metinler `build` içinde okunduğu için bütün ağacı yeniden kurmak dili değiştirmeye yeter.
  static void rebuildAll() {
    void visit(Element element) {
      element.markNeedsBuild();
      element.visitChildren(visit);
    }

    WidgetsBinding.instance.rootElement?.visitChildren(visit);
  }

  /// Bir dilin kendi dilindeki adı ("Türkçe", "English"): tablodaki `_name`.
  static String nameOf(String code) => tables[code]?['_name'] ?? code;

  /// iPhone'da `anahtar.ios` varsa o kullanılır: arka plan, bildirim gibi Android'e özgü anlatımların iPhone karşılığı.
  static String? _raw(String key) {
    if (defaultTargetPlatform == TargetPlatform.iOS) {
      final ios = '$key.ios';
      final own = tables[lang]?[ios] ?? tables[fallback]?[ios];
      if (own != null) return own;
    }
    return tables[lang]?[key] ?? tables[fallback]?[key];
  }

  static String _fill(String text, List<Object?> args) {
    var out = text;
    for (var i = 0; i < args.length; i++) {
      out = out.replaceAll('{$i}', '${args[i]}');
    }
    return out;
  }
}

/// [key]'in arayüz dilindeki metni; `{0}`, `{1}`… yer tutucuları [args] ile dolar. Anahtar yoksa anahtarın kendisi
/// döner (test/l10n_test.dart eksik anahtarı yakalar).
String t(String key, [List<Object?> args = const []]) => L10n._fill(L10n._raw(key) ?? key, args);

/// Sayıya göre: [n] 1 ise ve gösterilen dilin tablosunda `key.one` varsa o, yoksa [key]. Metinde `{0}` sayının kendisidir, [args] `{1}`'den
/// başlar. (Türkçede tekil/çoğul ayrımı gerekmez; İngilizcede "1 line" / "{0} lines".)
String tn(String key, int n, [List<Object?> args = const []]) {
  final own = L10n.tables[L10n.lang] ?? const <String, String>{};
  final one = n == 1 ? own['$key.one'] : null;
  return L10n._fill(one ?? L10n._raw(key) ?? key, [n, ...args]);
}

/// Android tarafından (Kotlin) gelen metin. Çekirdek, kullanıcıya gösterilecek durum ve hata metinlerini kod olarak
/// yollar: `@anahtar` ve (varsa) U+001F ile ayrılmış değerler; karşılığı tablodaki `n.anahtar`'dır. `@` ile
/// başlamayan metin (adres, konsol adı, teknik ayrıntı) olduğu gibi gösterilir.
String nt(String? raw) {
  if (raw == null || raw.isEmpty) return '';
  if (!raw.startsWith('@')) return raw;
  final parts = raw.substring(1).split('\u001f');
  return t('n.${parts.first}', parts.sublist(1));
}

/// Çevrilmiş bir altyazının yazım yönü, metnin kendisinden: ilk güçlü harf Arapça, Farsça ya da İbraniceyse sağdan
/// sola. Çeviri gösterilen her `Text`'e `textDirection: dirOf(metin)` verilir; arayüzün geri kalanı soldan sağadır.
TextDirection dirOf(String text) {
  for (final rune in text.runes) {
    if ((rune >= 0x0590 && rune <= 0x08FF) || (rune >= 0xFB1D && rune <= 0xFDFF) || (rune >= 0xFE70 && rune <= 0xFEFF)) {
      return TextDirection.rtl;
    }
    final latin = (rune >= 0x41 && rune <= 0x5A) || (rune >= 0x61 && rune <= 0x7A);
    if (latin || (rune >= 0xC0 && !(rune >= 0x2000 && rune <= 0x206F))) return TextDirection.ltr;
  }
  return TextDirection.ltr;
}
