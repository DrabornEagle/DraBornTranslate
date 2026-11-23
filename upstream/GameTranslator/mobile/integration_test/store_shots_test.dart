// SPDX-License-Identifier: AGPL-3.0-only
// Mağaza görselleri için uygulamanın gerçek ekranları (yalnız hata ayıklama derlemesinde: "debugImport" köprü yöntemi).
// flutter drive --driver=test_driver/integration_test.dart --target=integration_test/store_shots_test.dart \
//   -d <simülatör> --dart-define=SHOT_LANG=tr      (SHOTS_DIR ortam değişkeni: görsellerin klasörü)
import 'dart:convert';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gametranslator_mobile/history_page.dart';
import 'package:gametranslator_mobile/l10n.dart';
import 'package:gametranslator_mobile/main.dart' as app;
import 'package:gametranslator_mobile/screen_page.dart';
import 'package:gametranslator_mobile/tv_install_page.dart';
import 'package:integration_test/integration_test.dart';

const lang = String.fromEnvironment('SHOT_LANG', defaultValue: 'tr');
const _bridge = MethodChannel('gt/bridge');

/// Örnek diyaloglar (hiçbir oyundan alınmamıştır): kaynak, Türkçe, İspanyolca.
const _lines = [
  ('We have to leave before sunrise.', 'Gün doğmadan ayrılmalıyız.', 'Tenemos que irnos antes del amanecer.'),
  ('Marcus: Stay close. The bridge won\'t hold forever.', 'Marcus: Yakın dur. Köprü sonsuza kadar dayanmaz.',
      'Marcus: No te alejes. El puente no aguantará para siempre.'),
  ('Elena: Did you find the map?', 'Elena: Haritayı buldun mu?', 'Elena: ¿Encontraste el mapa?'),
  ('Marcus: Not yet. Check the old lighthouse.', 'Marcus: Henüz değil. Eski deniz fenerine bak.',
      'Marcus: Todavía no. Revisa el viejo faro.'),
  ('Follow the river to the camp.', 'Kampa kadar nehri takip et.', 'Sigue el río hasta el campamento.'),
  ('Elena: Someone has been here before us.', 'Elena: Bizden önce biri buraya gelmiş.', 'Elena: Alguien ha estado aquí antes que nosotros.'),
];

String _target((String, String, String) line) => lang == 'tr' ? line.$2 : line.$3;

Future<void> _pumpFor(WidgetTester tester, Duration duration) async {
  final end = DateTime.now().add(duration);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('mağaza ekranları', (tester) async {
    final settings = jsonEncode({
      'uiLanguage': lang,
      'targetLanguage': lang == 'tr' ? 'tr' : 'es',
      'sourceLanguage': 'en',
      'engine': 'google',
      'autoRegion': true,
      'packsEnabled': true,
      'style': {'speakerColor': true},
    });
    // Sahte eşleştirme: gerçek bir konsola bağlanılmaz (anahtarlar sıfır baytlardır).
    final zeros16 = base64Encode(List.filled(16, 0));
    await _bridge.invokeMethod('forgetAccount');
    await _bridge.invokeMethod('debugImport', {
      'introSeen': false,
      'settings': settings,
      'history': [
        for (final line in _lines) {'source': line.$1, 'translation': _target(line), 'fromPack': line == _lines[1] || line == _lines[3]},
      ],
    });

    Future<void> shot(String name) async {
      await _pumpFor(tester, const Duration(milliseconds: 600));
      await binding.takeScreenshot('${lang}_$name');
    }

    await app.main();
    await _pumpFor(tester, const Duration(seconds: 5));
    await shot('01_intro');

    await _bridge.invokeMethod('debugImport', {
      'host': '192.168.1.20',
      'name': lang == 'tr' ? 'Salon Konsolu' : 'Living Room Console',
      'registKey': zeros16,
      'morning': zeros16,
      'accountId': base64Encode(List.filled(8, 1)),
    });
    await tester.tap(find.text(t('intro.skip')));
    await _pumpFor(tester, const Duration(seconds: 3));
    await shot('02_home');
    // Çeviri açıkken ana ekran: önizlemede oyun görüntüsü yerine uygulamanın kendi tanıtım resmi.
    final frame = (await rootBundle.load('assets/intro_scene.jpg')).buffer.asUint8List();
    await _bridge.invokeMethod('debugImport', {
      'phase': 'streaming',
      'frame': frame,
      'state': {
        'running': true, 'tvClients': 1, 'frames': 4210, 'frameWidth': 1920, 'frameHeight': 1080, 'translated': 37, 'packHits': 12,
        'lastSource': _lines[1].$1, 'lastTranslation': _target(_lines[1]), 'lastRead': _lines[1].$1,
      },
    });
    await _pumpFor(tester, const Duration(seconds: 4));
    await shot('02_home_live');
    await _bridge.invokeMethod('debugImport', {'state': {'running': false, 'tvClients': 0}, 'phase': 'idle'});

    final navigator = tester.state<NavigatorState>(find.byType(Navigator).first);
    navigator.push(MaterialPageRoute<void>(builder: (_) => const ScreenPage()));
    await _pumpFor(tester, const Duration(seconds: 2));
    await _bridge.invokeMethod('debugImport', {'phase': 'streaming', 'subtitle': _target(_lines[1])});
    await _pumpFor(tester, const Duration(seconds: 2));
    await shot('03_screen');
    navigator.pop();
    await _bridge.invokeMethod('debugImport', {'phase': 'idle'});
    await _pumpFor(tester, const Duration(seconds: 1));

    await tester.tap(find.text(t('home.tab.settings')).last);
    await _pumpFor(tester, const Duration(seconds: 2));
    await shot('04_settings');
    // Bölümler başlıklarıyla ekranın üstüne hizalanır.
    // "TV'de görünüm" ekranın ortasından başlar: altındaki deneysel bölüm (oyun adları geçer) ekrana girmesin.
    for (final (name, key, align) in [('05_engine', 'settings.engine.header', 0.08), ('05_look', 'settings.look.header', 0.42)]) {
      final header = find.text(t(key)).first;
      await tester.ensureVisible(header);
      await Scrollable.ensureVisible(tester.element(header), alignment: align);
      await _pumpFor(tester, const Duration(seconds: 1));
      await shot(name);
    }

    navigator.push(MaterialPageRoute<void>(builder: (_) => const HistoryPage()));
    await _pumpFor(tester, const Duration(seconds: 2));
    await shot('06_history');
    navigator.pop();
    await _pumpFor(tester, const Duration(seconds: 1));

    navigator.push(MaterialPageRoute<void>(builder: (_) => const TvInstallPage()));
    await _pumpFor(tester, const Duration(seconds: 3));
    await shot('07_tv');
  });
}
