// SPDX-License-Identifier: AGPL-3.0-only
// Arayüz dili denetimleri: tablolar eksiksiz mi, ekran kodunda tabloya girmemiş Türkçe metin kaldı mı, dil değişince
// ekran hemen yenileniyor mu, Türkçeye özel ayarlar başka dilde gizleniyor mu.
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gametranslator_mobile/bridge.dart';
import 'package:gametranslator_mobile/l10n.dart';
import 'package:gametranslator_mobile/l10n/en.dart';
import 'package:gametranslator_mobile/l10n/tr.dart';
import 'package:gametranslator_mobile/language_section.dart';
import 'package:gametranslator_mobile/languages.dart';
import 'package:gametranslator_mobile/settings_page.dart';
import 'package:gametranslator_mobile/ui.dart';
import 'package:material_ui/material_ui.dart';

final _literal = RegExp(r'''(?:'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*")''');
final _turkishLetters = RegExp('[çğıöşüÇĞİÖŞÜ]');
final _placeholder = RegExp(r'\{\d+\}');
final _key = RegExp(r'^[a-z_]+(\.[a-z0-9_]+)+$');

/// Ekran kodu: lib/ altındaki bütün Dart dosyaları, metin tabloları (lib/l10n/) dışında.
List<File> _uiFiles() => Directory('lib')
    .listSync(recursive: true)
    .whereType<File>()
    .where((file) => file.path.endsWith('.dart') && !file.path.replaceAll('\\', '/').contains('lib/l10n/'))
    .toList();

/// Dosyadaki metin sabitleri (tırnaklar dışında), yorum satırları ve satır sonu yorumları atlanarak.
Iterable<(int, String)> _literals(File file) sync* {
  final lines = file.readAsLinesSync();
  for (var i = 0; i < lines.length; i++) {
    final trimmed = lines[i].trimLeft();
    if (trimmed.startsWith('//') || trimmed.startsWith('import ') || trimmed.startsWith('export ') || trimmed.startsWith('part ')) continue;
    final code = lines[i].replaceFirst(RegExp(r'\s//.*$'), '');
    for (final match in _literal.allMatches(code)) {
      final text = match.group(0)!;
      yield (i + 1, text.substring(1, text.length - 1));
    }
  }
}

RegExp _dynamicKey(String literal) =>
    RegExp('^${literal.split(RegExp(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*')).map(RegExp.escape).join('.+')}\$');

void main() {
  group('tables', () {
    test('every key exists in every language', () {
      for (final entry in L10n.tables.entries) {
        final other = L10n.tables.entries.where((e) => e.key != entry.key);
        for (final table in other) {
          // `.one` (sayı 1 iken) isteğe bağlıdır: Türkçede tekil/çoğul ayrımı yok.
          final missing = entry.value.keys.where((key) => !key.endsWith('.one') && !table.value.containsKey(key)).toList();
          expect(missing, isEmpty, reason: 'in "${entry.key}" but missing from "${table.key}"');
        }
      }
      expect(trStrings.length, greaterThan(700));
    });

    test('no empty text and the same placeholders in every language', () {
      for (final key in trStrings.keys) {
        final tr = trStrings[key]!;
        final en = enStrings[key];
        if (en == null) continue;
        expect(tr.trim(), isNotEmpty, reason: key);
        expect(en.trim(), isNotEmpty, reason: key);
        Set<String> holes(String text) => _placeholder.allMatches(text).map((m) => m.group(0)!).toSet();
        expect(holes(en), holes(tr), reason: 'placeholders differ: $key');
      }
    });

    test('every `.one` key has its plural key', () {
      for (final table in L10n.tables.values) {
        for (final key in table.keys.where((key) => key.endsWith('.one'))) {
          expect(table.containsKey(key.substring(0, key.length - 4)), isTrue, reason: key);
        }
      }
    });

    test('English table has no Turkish text left in it', () {
      // ğ, ş, ı ve İ İngilizce bir metinde olmaz. İzin verilenler: marka adı ve İngilizce → Türkçe sözlük örneği.
      const allowed = {'settings.glossary.example'};
      final letters = RegExp('[ğşıİĞŞ]');
      final bad = enStrings.entries
          .where((e) => !allowed.contains(e.key) && letters.hasMatch(e.value))
          .map((e) => '${e.key}: ${e.value}')
          .toList();
      expect(bad, isEmpty);
      // Türkçe tablodan aynen kopyalanmış (çevrilmemiş) cümle: Türkçe harf içeren ve iki dilde aynı olan metin.
      final copied = enStrings.entries
          .where((e) => !allowed.contains(e.key) && e.value == trStrings[e.key] && _turkishLetters.hasMatch(e.value) && e.value.contains(' '))
          .map((e) => '${e.key}: ${e.value}')
          .toList();
      expect(copied, isEmpty);
    });
  });

  group('screen code', () {
    test('no string literal with Turkish letters outside the tables', () {
      // Kapsam: lib/ altında, lib/l10n/ dışındaki her Dart dosyasındaki her metin sabiti (yorumlar hariç).
      // Yakalayamadığı: Türkçe harf içermeyen Türkçe metin ("Tamam", "Kapat"); onun için aşağıdaki sözcük listesi var.
      final found = <String>[];
      for (final file in _uiFiles()) {
        for (final (line, text) in _literals(file)) {
          if (_turkishLetters.hasMatch(text)) found.add('${file.path}:$line: $text');
        }
      }
      expect(found, isEmpty, reason: 'move these into lib/l10n/tr.dart and en.dart and use t()');
    });

    test('no common Turkish word without Turkish letters left as a literal', () {
      // Türkçe harfi olmayan sık arayüz sözcükleri. Liste sınırlıdır: burada olmayan bir sözcük gözden kaçabilir.
      final words = RegExp(
        r'\b(Tamam|Kapat|Geri|Devam|Ayarlar|Rehber|Dene|Yeniden|Tekrar|Kaydet|Sil|Ekle|Kopyala|Evet|Bitti|Kur|Kurulum|Bilgisayar|'
        r'Telefon|Ekran|Hata|Adres|Hesap|Oyun|Dil|Durdur|Yenile|Ara|Bul|Bekle|Bekleniyor|Konsol|Kod|Kodu|Daha|Sonra|Yok|Var|'
        r've|bir|ile|bu|ya da|olarak|gerekli|yok|var)\b',
      );
      final found = <String>[];
      for (final file in _uiFiles()) {
        for (final (line, text) in _literals(file)) {
          if (_key.hasMatch(text) || text.startsWith('http')) continue;
          if (words.hasMatch(text)) found.add('${file.path}:$line: $text');
        }
      }
      expect(found, isEmpty);
    });

    test('every key used in the code exists, and every key in the tables is used', () {
      final prefixes = trStrings.keys.map((key) => key.split('.').first).toSet();
      final literals = <String>{};
      final dynamic = <RegExp>[];
      for (final file in _uiFiles()) {
        for (final (_, text) in _literals(file)) {
          if (text.contains(r'$') && text.contains('.')) {
            dynamic.add(_dynamicKey(text));
          } else if (_key.hasMatch(text) && prefixes.contains(text.split('.').first)) {
            literals.add(text);
          }
        }
      }
      // Kotlin'den gelen kodlar: UiText.code("x") → n.x; köprüdeki varsayılanlar '@x'.
      final native = <String>{};
      for (final file in Directory('android/app/src/main/kotlin').listSync(recursive: true).whereType<File>()) {
        if (!file.path.endsWith('.kt')) continue;
        for (final match in RegExp(r'UiText\.code\("([a-z_]+)"').allMatches(file.readAsStringSync())) {
          native.add('n.${match.group(1)}');
        }
      }
      for (final match in RegExp(r"'@([a-z_]+)'").allMatches(File('lib/bridge.dart').readAsStringSync())) {
        native.add('n.${match.group(1)}');
      }
      expect(native, isNotEmpty);
      for (final table in L10n.tables.entries) {
        final keys = table.value.keys.toSet();
        bool known(String literal) => keys.contains(literal) || keys.any((key) => key.startsWith('$literal.'));
        expect(literals.where((literal) => !known(literal)).toList(), isEmpty, reason: 'used in code, missing from "${table.key}"');
        expect(native.where((key) => !keys.contains(key)).toList(), isEmpty, reason: 'sent by Kotlin, missing from "${table.key}"');
        for (final pattern in dynamic) {
          if (!prefixes.any((prefix) => pattern.pattern.startsWith('^$prefix\\.'))) continue;
          expect(keys.any(pattern.hasMatch), isTrue, reason: 'no key matches ${pattern.pattern} in "${table.key}"');
        }
        bool used(String key) {
          if (key == '_name' || native.contains(key) || literals.contains(key)) return true;
          if (key.endsWith('.one')) return used(key.substring(0, key.length - 4));
          return literals.any((literal) => key.startsWith('$literal.')) || dynamic.any((pattern) => pattern.hasMatch(key));
        }

        expect(keys.where((key) => !used(key)).toList(), isEmpty, reason: 'in "${table.key}" but never used');
      }
    });
  });

  group('L10n', () {
    tearDown(() => L10n.debugSet('tr'));

    test('automatic follows the phone: Turkish phone → Turkish, anything else → English', () {
      expect(L10n.resolve('auto', 'tr'), 'tr');
      expect(L10n.resolve('auto', 'en'), 'en');
      expect(L10n.resolve('auto', 'de'), 'en');
      expect(L10n.resolve('auto', 'ar'), 'en');
      expect(L10n.resolve('tr', 'en'), 'tr');
      expect(L10n.resolve('en', 'tr'), 'en');
      expect(L10n.resolve('xx', 'tr'), 'tr');
    });

    test('t fills placeholders, tn picks the singular, nt decodes codes from Kotlin', () {
      L10n.debugSet('en');
      expect(t('settings.title'), 'Settings');
      expect(t('settings.percent', [40]), '40%');
      expect(tn('settings.glossary.count', 1), '1 term');
      expect(tn('settings.glossary.count', 3), '3 terms');
      expect(nt('@rest_mode'), 'The PS5 is in rest mode');
      expect(nt('@not_found\u001f192.168.1.20'), 'The PS5 was not found on the network (192.168.1.20)');
      expect(nt('192.168.1.20'), '192.168.1.20');
      expect(nt(null), '');
      L10n.debugSet('tr');
      expect(t('settings.title'), 'Ayarlar');
      expect(t('settings.percent', [40]), '%40');
      expect(tn('settings.glossary.count', 1), '1 terim');
      expect(nt('@rest_mode'), 'PS5 dinlenme modunda');
      expect(t('no.such.key'), 'no.such.key');
    });

    test('each table names its own language', () {
      expect(L10n.nameOf('tr'), 'Türkçe');
      expect(L10n.nameOf('en'), 'English');
    });
  });

  group('settings', () {
    late Map<String, dynamic> stored;
    final saved = <Map<String, dynamic>>[];
    const channel = MethodChannel('gt/bridge');

    Map<String, dynamic> settingsJson({String target = 'tr', String source = 'en', String engine = 'google', String ui = 'tr'}) => {
          'region': {'top': 0.62, 'bottom': 0.97, 'left': 0.14, 'right': 0.86},
          'sourceLanguage': source,
          'targetLanguage': target,
          'engine': engine,
          'deepLKey': '',
          'autoWake': true,
          'glossary': '',
          'packsEnabled': true,
          'uiLanguage': ui,
          'style': {
            'fontSize': 46,
            'bottomPercent': 8,
            'placement': 'cover',
            'background': 60,
            'bold': true,
            'textColor': 'white',
            'displayMode': 'subtitle',
            'stackPrevious': false,
          },
        };

    setUp(() {
      saved.clear();
      // Listenin asıl tanımı Android tarafında (core/Languages.kt); burada ondan küçük bir örnek.
      Languages.apply({
        'targets': [
          {'code': 'tr', 'native': 'Türkçe', 'english': 'Turkish', 'turkish': 'Türkçe', 'rtl': false, 'deepl': true, 'mlkit': true},
          {'code': 'en', 'native': 'English', 'english': 'English', 'turkish': 'İngilizce', 'rtl': false, 'deepl': true, 'mlkit': true},
          {'code': 'ja', 'native': '日本語', 'english': 'Japanese', 'turkish': 'Japonca', 'rtl': false, 'deepl': true, 'mlkit': true},
          {'code': 'ar', 'native': 'العربية', 'english': 'Arabic', 'turkish': 'Arapça', 'rtl': true, 'deepl': true, 'mlkit': true},
          {'code': 'az', 'native': 'Azərbaycanca', 'english': 'Azerbaijani', 'turkish': 'Azerbaycanca', 'rtl': false, 'deepl': true, 'mlkit': false},
        ],
        'sources': ['tr', 'en', 'ja'],
      });
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
        switch (call.method) {
          case 'getSettings':
            return jsonEncode(stored);
          case 'setSettings':
            stored = jsonDecode(call.arguments as String) as Map<String, dynamic>;
            saved.add(stored);
            return null;
          default:
            return null;
        }
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
      L10n.debugSet('tr');
    });

    Future<void> open(WidgetTester tester, Map<String, dynamic> json, {String ui = 'tr'}) async {
      stored = json;
      L10n.debugSet(ui);
      tester.view.physicalSize = const Size(1080, 6000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: SettingsTab(setup: const SetupInfo(hasAccount: true, consoleHost: '192.168.1.20', consoleName: 'PS5'), onSetupChanged: () async {}, active: false)),
      ));
      await tester.pump();
      await tester.pump();
    }

    Future<void> close(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 1));
    }

    testWidgets('English → Turkish shows the glossary and game packs', (tester) async {
      await open(tester, settingsJson());
      expect(find.text(trStrings['settings.packs']!), findsOneWidget);
      expect(find.text(trStrings['settings.glossary']!), findsOneWidget);
      expect(find.text(trStrings['settings.tr_only']!), findsNothing);
      expect(find.byKey(const ValueKey('engine-fallback')), findsNothing);
      await close(tester);
    });

    testWidgets('another target hides packs and glossary and says they are Turkish-only', (tester) async {
      await open(tester, settingsJson(target: 'ja'), ui: 'en');
      expect(find.text(enStrings['settings.packs']!), findsNothing);
      expect(find.text(enStrings['settings.glossary']!), findsNothing);
      expect(find.text(enStrings['settings.packs.footer']!), findsNothing);
      expect(find.text(enStrings['settings.tr_only']!), findsOneWidget);
      expect(find.text('日本語 · Japanese'), findsOneWidget);
      await close(tester);
    });

    testWidgets('an engine that cannot translate into the target says Google is used instead', (tester) async {
      await open(tester, settingsJson(target: 'az', engine: 'mlkit'), ui: 'en');
      final notice = tester.widget<Notice>(find.byKey(const ValueKey('engine-fallback')));
      expect(notice.text, "On device can't translate into Azerbaijani, so Google is translating for this language. Your engine choice is kept.");
      expect(find.textContaining('There is no on-device model for Azerbaijani'), findsOneWidget);
      await close(tester);
    });

    // Ekrandaki başka yazılar (Deneysel): GTA 6 düğmesi üç alanı açar; üçü açıkken dördüncüsü açılamaz ve bildirim çıkar.
    testWidgets('the GTA 6 button turns on three extra areas and a fourth is blocked', (tester) async {
      await open(tester, settingsJson()..['autoRegion'] = false);
      expect(find.byKey(const ValueKey('extra-full')), findsNothing);
      final gta = find.byKey(const ValueKey('extra-gta6'));
      await tester.ensureVisible(gta);
      await tester.tap(gta);
      await tester.pump(const Duration(milliseconds: 400));
      expect(saved.last['autoRegion'], true);
      expect((saved.last['extraAreas'] as List).map((slot) => (slot as Map)['preset']), ['bottom_objective', 'help_box', 'phone_message']);
      expect(find.byKey(const ValueKey('extra-full')), findsOneWidget);
      CupertinoSwitch switchOf(String preset) =>
          tester.widget<CupertinoSwitch>(find.descendant(of: find.byKey(ValueKey('extra-$preset')), matching: find.byType(CupertinoSwitch)));
      expect(switchOf('help_box').value, isTrue);
      expect(switchOf('quest').onChanged, isNull);

      // Bir alan kapanınca yuva boşalır, başka alan açılabilir.
      switchOf('help_box').onChanged!(false);
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.byKey(const ValueKey('extra-full')), findsNothing);
      switchOf('quest').onChanged!(true);
      await tester.pump(const Duration(milliseconds: 400));
      final slots = (saved.last['extraAreas'] as List).cast<Map>();
      expect(slots.where((slot) => slot['live'] == true).map((slot) => slot['preset']).toSet(), {'bottom_objective', 'phone_message', 'quest'});

      // Kutunun görünümü stil anahtarlarına yazılır.
      final same = find.text(trStrings['settings.extra.color.same']!);
      expect(same, findsOneWidget);
      await tester.pump(const Duration(seconds: 4));
      await close(tester);
    });

    testWidgets('same game and translation language shows a warning', (tester) async {
      await open(tester, settingsJson(target: 'en'), ui: 'en');
      expect(find.byKey(const ValueKey('lang-same')), findsOneWidget);
      await close(tester);
    });

    testWidgets('changing the app language redraws the open screen at once and is saved', (tester) async {
      await open(tester, settingsJson());
      expect(find.text('Ayarlar'), findsOneWidget);
      expect(find.text('Settings'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('lang-app')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('English'));
      await tester.pumpAndSettle(const Duration(milliseconds: 400));
      expect(L10n.lang, 'en');
      expect(find.text('Settings'), findsOneWidget);
      expect(find.text('Ayarlar'), findsNothing);
      expect(find.text(enStrings['settings.engine.header']!), findsOneWidget);
      expect(saved.last['uiLanguage'], 'en');
      await close(tester);
    });

    // İlk ekrandaki dünya simgeli düğme: dillerin kendi adları, başlık iki dilde, seçim hemen geçerli ve kaydedilir.
    testWidgets('the globe button on the first screen switches app and translation language', (tester) async {
      stored = settingsJson();
      L10n.debugSet('tr');
      await tester.pumpWidget(const MaterialApp(home: Scaffold(body: Center(child: LanguageGlobeButton()))));
      expect(find.text('Türkçe'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('lang-globe')));
      await tester.pumpAndSettle();
      expect(find.text('Uygulama dili · App language'), findsOneWidget);
      expect(find.text('Çeviri dili · Translate into'), findsOneWidget);
      expect(find.text('日本語'), findsOneWidget);
      expect(find.text('العربية'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('lang-target-ja')));
      await tester.pumpAndSettle();
      expect(saved.last['targetLanguage'], 'ja');

      await tester.tap(find.byKey(const ValueKey('lang-ui-en')));
      await tester.pumpAndSettle(const Duration(milliseconds: 400));
      expect(L10n.lang, 'en');
      expect(saved.last['uiLanguage'], 'en');
      expect(saved.last['targetLanguage'], 'ja');
      // İngilizcede başlık tek dilde; düğmenin üstündeki ad da değişti
      expect(find.text('App language'), findsOneWidget);
      expect(find.text('Translate into'), findsOneWidget);
      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();
      expect(find.text('English'), findsOneWidget);
      await close(tester);
    });

    testWidgets('translated text in a language picker keeps native names', (tester) async {
      L10n.debugSet('tr');
      expect(targetLanguageOptions()['ja'], '日本語 · Japonca');
      expect(targetLanguageOptions()['tr'], 'Türkçe');
      L10n.debugSet('en');
      expect(targetLanguageOptions()['tr'], 'Türkçe · Turkish');
      expect(uiLanguageOptions().keys, ['auto', 'tr', 'en']);
    });
  });
}
