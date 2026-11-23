// SPDX-License-Identifier: AGPL-3.0-only
// Altyazı geçmişi sayfası ve TV görünümündeki yeni ayarlar (alttan yükseklik, önceki satır).
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gametranslator_mobile/bridge.dart';
import 'package:gametranslator_mobile/history_page.dart';
import 'package:gametranslator_mobile/l10n.dart';
import 'package:gametranslator_mobile/l10n/tr.dart';
import 'package:gametranslator_mobile/settings_page.dart';
import 'package:gametranslator_mobile/ui.dart';
import 'package:material_ui/material_ui.dart';

const _channel = MethodChannel('gt/bridge');

void main() {
  group('HistoryEntry', () {
    test('is decoded from the platform map and labels today with seconds', () {
      final now = DateTime(2026, 11, 20, 21, 5, 9);
      final entry = HistoryEntry.fromMap({
        'source': 'Get down!',
        'translation': 'Yere yat!',
        'date': now.millisecondsSinceEpoch,
        'fromPack': true,
      });
      expect((entry.source, entry.translation, entry.fromPack), ('Get down!', 'Yere yat!', true));
      expect(entry.timeLabel(now), '21:05:09');
      expect(entry.timeLabel(DateTime(2026, 11, 21)), '20.11 21:05');
    });

    test('plain text has the time, the translation and the source under it', () {
      final now = DateTime.now();
      final text = HistoryEntry.plainText([
        HistoryEntry(source: 'Who\n is there?', translation: 'Kim var?', date: now),
        HistoryEntry(source: 'Get down!', translation: 'Yere yat!', date: now),
      ]);
      final lines = text.split('\n');
      expect(lines.length, 5);
      expect(lines[0], endsWith('] Kim var?'));
      expect(lines[1], 'Who  is there?');
      expect(lines[2], '');
      expect(lines[4], 'Get down!');
    });
  });

  group('history page', () {
    late List<Map<String, Object?>> stored;
    final calls = <String>[];
    String? clipboard;

    setUp(() {
      calls.clear();
      clipboard = null;
      final now = DateTime.now().millisecondsSinceEpoch;
      stored = [
        {'source': "Who's there?", 'translation': 'Kim var orada?', 'date': now, 'fromPack': true},
        {'source': 'Get down!', 'translation': 'Yere yat!', 'date': now - 5000, 'fromPack': false},
      ];
      final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(_channel, (call) async {
        calls.add(call.method);
        switch (call.method) {
          case 'getHistory':
            return stored;
          case 'clearHistory':
            stored = [];
            return null;
          default:
            return null;
        }
      });
      messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') clipboard = (call.arguments as Map)['text'] as String?;
        return null;
      });
      L10n.debugSet('tr');
    });

    tearDown(() {
      final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(_channel, null);
      messenger.setMockMethodCallHandler(SystemChannels.platform, null);
    });

    Future<void> open(WidgetTester tester) async {
      await tester.pumpWidget(const MaterialApp(home: HistoryPage(refresh: Duration(hours: 1))));
      await tester.pump();
      await tester.pump();
    }

    testWidgets('lists the lines newest first, marks pack lines and copies', (tester) async {
      await open(tester);
      expect(find.text(trStrings['history.title']!), findsOneWidget);
      expect(find.text('Kim var orada?'), findsOneWidget);
      expect(find.text('Yere yat!'), findsOneWidget);
      expect(find.text(trStrings['history.pack']!), findsOneWidget);
      expect(tester.getTopLeft(find.text('Kim var orada?')).dy, lessThan(tester.getTopLeft(find.text('Yere yat!')).dy));
      expect(find.text(tn('history.count', 2)), findsOneWidget);

      await tester.tap(find.text('Yere yat!'));
      await tester.pump();
      expect(clipboard, 'Yere yat!\nGet down!');

      await tester.tap(find.byKey(const ValueKey('history-copy')));
      await tester.pump();
      expect(clipboard, contains('Kim var orada?'));
      expect(clipboard, contains('Get down!'));
      await tester.pump(const Duration(seconds: 4));
    });

    testWidgets('clear asks first, then empties the list', (tester) async {
      await open(tester);
      await tester.tap(find.byKey(const ValueKey('history-clear')));
      await tester.pumpAndSettle();
      expect(find.text(trStrings['history.clear.title']!), findsOneWidget);
      // Penceredeki onay düğmesi (sayfadaki düğmeyle aynı yazı).
      await tester.tap(find.text(trStrings['history.clear']!).last);
      await tester.pumpAndSettle();
      expect(calls, contains('clearHistory'));
      expect(find.text('Kim var orada?'), findsNothing);
      expect(find.text(trStrings['history.empty']!), findsOneWidget);
      final clear = tester.widget<PillButton>(find.byKey(const ValueKey('history-clear')));
      expect(clear.onTap, isNull);
    });

    testWidgets('empty history says so and disables the buttons', (tester) async {
      stored = [];
      await open(tester);
      expect(find.text(trStrings['history.empty']!), findsOneWidget);
      expect(tester.widget<PillButton>(find.byKey(const ValueKey('history-copy'))).onTap, isNull);
    });
  });

  group('TV appearance', () {
    late Map<String, dynamic> stored;
    final saved = <Map<String, dynamic>>[];

    Map<String, dynamic> settingsJson(String placement) => {
          'region': {'top': 0.62, 'bottom': 0.97, 'left': 0.14, 'right': 0.86},
          'sourceLanguage': 'en',
          'targetLanguage': 'tr',
          'engine': 'google',
          'deepLKey': '',
          'autoWake': true,
          'glossary': '',
          'packsEnabled': true,
          'uiLanguage': 'tr',
          'style': {
            'fontSize': 46,
            'bottomPercent': 8,
            'placement': placement,
            'background': 60,
            'bold': true,
            'textColor': 'white',
            'displayMode': 'inApp',
            'stackPrevious': true,
          },
        };

    setUp(() {
      saved.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(_channel, (call) async {
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
      L10n.debugSet('tr');
    });

    tearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(_channel, null));

    Future<void> open(WidgetTester tester, String placement) async {
      stored = settingsJson(placement);
      tester.view.physicalSize = const Size(1080, 7000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SettingsTab(setup: const SetupInfo(hasAccount: true, consoleHost: '192.168.1.20', consoleName: 'PS5'), onSetupChanged: () async {}, active: false),
        ),
      ));
      await tester.pump();
      await tester.pump();
    }

    Future<void> close(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 1));
    }

    testWidgets('cover hides the height and the previous line', (tester) async {
      await open(tester, 'cover');
      expect(find.byKey(const ValueKey('look-bottom')), findsNothing);
      expect(find.byKey(const ValueKey('look-stack')), findsNothing);
      expect(find.byKey(const ValueKey('settings-history')), findsOneWidget);
      await close(tester);
    });

    testWidgets('fixed shows the height from the bottom and both are saved', (tester) async {
      await open(tester, 'fixed');
      expect(find.byKey(const ValueKey('look-bottom')), findsOneWidget);
      expect(find.text(trStrings['settings.look.bottom']!), findsOneWidget);
      final slider = tester.widget<CupertinoSlider>(find.descendant(of: find.byKey(const ValueKey('look-bottom')), matching: find.byType(CupertinoSlider)));
      expect((slider.value, slider.min, slider.max), (8.0, 2.0, 30.0));
      slider.onChanged!(15);
      final stack = tester.widget<CupertinoSwitch>(find.descendant(of: find.byKey(const ValueKey('look-stack')), matching: find.byType(CupertinoSwitch)));
      expect(stack.value, isTrue);
      stack.onChanged!(false);
      await tester.pump(const Duration(milliseconds: 400));
      final style = saved.last['style'] as Map;
      expect((style['bottomPercent'], style['stackPrevious']), (15, false));
      await close(tester);
    });

    testWidgets('above shows the previous line but not the height', (tester) async {
      await open(tester, 'above');
      expect(find.byKey(const ValueKey('look-bottom')), findsNothing);
      expect(find.byKey(const ValueKey('look-stack')), findsOneWidget);
      await close(tester);
    });
  });
}
