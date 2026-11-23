// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gametranslator_mobile/bridge.dart';
import 'package:gametranslator_mobile/guide_page.dart';
import 'package:gametranslator_mobile/l10n.dart';
import 'package:gametranslator_mobile/screen_page.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  group('SubtitleBoard', () {
    test('new sentence keeps the old one as the faint previous line', () {
      final board = SubtitleBoard();
      board.show('Bir', replace: false, keepPrevious: true);
      expect((board.line, board.visible, board.previousVisible), ('Bir', true, false));
      board.show('İki', replace: false, keepPrevious: true);
      expect((board.line, board.previous, board.previousVisible), ('İki', 'Bir', true));
    });

    test('a longer reading of the same sentence replaces it', () {
      final board = SubtitleBoard();
      board.show('Nereye', replace: false, keepPrevious: true);
      board.show('Nereye gidiyorsun?', replace: true, keepPrevious: true);
      expect((board.line, board.previousVisible), ('Nereye gidiyorsun?', false));
      // Aynı satırın yeniden gelmesi (ekrana geri dönen altyazı) öncekini değiştirmez.
      board.show('Nereye gidiyorsun?', replace: false, keepPrevious: true);
      expect(board.previousVisible, false);
    });

    test('clear hides both lines and the next line starts fresh', () {
      final board = SubtitleBoard();
      board.show('Bir', replace: false, keepPrevious: true);
      board.show('İki', replace: false, keepPrevious: true);
      board.hide();
      expect((board.visible, board.previousVisible, board.everShown), (false, false, true));
      board.show('Üç', replace: false, keepPrevious: true);
      expect((board.line, board.visible, board.previousVisible), ('Üç', true, false));
    });

    test('previous line can be switched off', () {
      final board = SubtitleBoard();
      board.show('Bir', replace: false, keepPrevious: false);
      board.show('İki', replace: false, keepPrevious: false);
      expect(board.previousVisible, false);
      board.show('Üç', replace: false, keepPrevious: true);
      expect(board.previousVisible, true);
      board.dropPrevious();
      expect((board.previousVisible, board.visible), (false, true));
    });

    test('empty text clears', () {
      final board = SubtitleBoard()..show('Bir', replace: false, keepPrevious: true);
      board.show('', replace: false, keepPrevious: true);
      expect(board.visible, false);
    });
  });

  test('text size follows the short edge and stays within limits', () {
    const portrait = Size(400, 800);
    const landscape = Size(800, 400);
    expect(ScreenScale.lineSize(portrait, 1), closeTo(30, 0.01));
    expect(ScreenScale.lineSize(landscape, 1), closeTo(48, 0.01));
    expect(ScreenScale.previousSize(landscape, 1), closeTo(26.4, 0.01));
    expect(ScreenScale.lineSize(const Size(100, 100), 0.5), 18);
    expect(ScreenScale.bigger(1), closeTo(1.15, 1e-9));
    expect(ScreenScale.smaller(ScreenScale.bigger(1)), closeTo(1, 1e-9));
    expect(ScreenScale.bigger(2.9), 3);
    expect(ScreenScale.smaller(0.51), 0.5);
    expect(ScreenScale.clamp(99), 3);
    expect(screenHold(800), const Duration(milliseconds: 2500));
    expect(screenHold(15000), const Duration(seconds: 15));
  });

  test('subtitle events are decoded from the platform map', () {
    final shown = SubtitleEvent.fromMap(const {'type': 'subtitle', 'text': 'Merhaba', 'duration': 8000, 'replace': true});
    expect((shown.clear, shown.text, shown.durationMs, shown.replace), (false, 'Merhaba', 8000, true));
    expect(SubtitleEvent.fromMap(const {'type': 'clear'}).clear, true);
  });

  test('guide: new chapter is appended and existing chapter numbers are unchanged', () {
    final chapters = guideChapters(null);
    expect(chapters.length, 11);
    // Bölüm sıraları başka ekranlardan numarayla açılır; başlıkların sözleri dile göre değişir (test/l10n_test.dart).
    expect(xboxGuideChapter, 9);
    expect(unsupportedTvGuideChapter, 10);
    expect(chapters.map((chapter) => chapter.title).toSet().length, 11);
  });

  group('SubtitleStage', () {
    Future<void> pump(WidgetTester tester, Size size, SubtitleBoard board, {double scale = 1, String? idle}) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(backgroundColor: Colors.black, body: SubtitleStage(board: board, scale: scale, idleText: idle)),
      ));
      await tester.pumpAndSettle();
    }

    final long = List.filled(40, 'Bu çok uzun bir altyazı satırıdır ve ekrana sığmayabilir.').join(' ');

    for (final size in const [Size(400, 800), Size(800, 400)]) {
      testWidgets('very long line is scaled down, never clipped (${size.width.toInt()}×${size.height.toInt()})', (tester) async {
        final board = SubtitleBoard()
          ..show('Önceki satır', replace: false, keepPrevious: true)
          ..show(long, replace: false, keepPrevious: true);
        await pump(tester, size, board, scale: 3);
        expect(tester.takeException(), isNull);
        final screen = Offset.zero & size;
        final line = tester.getRect(find.byKey(const ValueKey('line')));
        final previous = tester.getRect(find.byKey(const ValueKey('previous')));
        expect(screen.contains(line.topLeft) && screen.contains(line.bottomRight - const Offset(0.01, 0.01)), true, reason: '$line');
        expect(screen.contains(previous.topLeft), true, reason: '$previous');
        expect(previous.bottom <= line.top + 0.01, true);
      });
    }

    testWidgets('short line keeps its full size and the idle text replaces it', (tester) async {
      final board = SubtitleBoard()..show('Merhaba', replace: false, keepPrevious: true);
      await pump(tester, const Size(800, 400), board);
      expect(tester.getSize(find.byKey(const ValueKey('line'))).height, closeTo(48 * 1.22, 1));
      await pump(tester, const Size(800, 400), SubtitleBoard(), idle: 'Waiting…');
      expect(find.text('Waiting…'), findsOneWidget);
      expect(find.byKey(const ValueKey('line')), findsNothing);
    });
  });

  // Gerçek çeviri örnekleri: sağdan sola diller ve boşluksuz yazılan diller.
  const arabic = 'لا أستطيع أن أتركك هنا وحدك، يجب أن نذهب الآن!';
  const persian = 'نمی‌توانم تو را اینجا تنها بگذارم، باید همین حالا برویم!';
  const hebrew = 'אני לא יכול להשאיר אותך כאן לבד, אנחנו חייבים ללכת עכשיו!';
  const japanese = 'ここから先は危険だ。君を一人で行かせるわけにはいかない、一緒に行こう。';
  const chinese = '前面很危险。我不能让你一个人去，我们一起走吧。无论发生什么，我都会在你身边。';
  const thai = 'ข้างหน้าอันตรายมาก ฉันปล่อยให้เธอไปคนเดียวไม่ได้ เราไปด้วยกันเถอะ ไม่ว่าจะเกิดอะไรขึ้นฉันจะอยู่ข้างเธอ';

  group('dirOf', () {
    test('Arabic, Persian and Hebrew are right-to-left', () {
      expect(dirOf(arabic), TextDirection.rtl);
      expect(dirOf(persian), TextDirection.rtl);
      expect(dirOf(hebrew), TextDirection.rtl);
    });

    test('Latin, Turkish, Cyrillic, Greek, CJK, Thai and Hindi are left-to-right', () {
      for (final text in ['Hello there', 'Şimdi gitmeliyiz', 'Нам нужно идти', 'Πρέπει να φύγουμε', japanese, chinese, thai, 'हमें अभी जाना होगा', '한국어 자막']) {
        expect(dirOf(text), TextDirection.ltr, reason: text);
      }
    });

    test('direction comes from the first strong character; digits and punctuation are skipped', () {
      expect(dirOf('Kratos: $arabic'), TextDirection.ltr);
      expect(dirOf('$arabic Kratos'), TextDirection.rtl);
      expect(dirOf('- 12… "$hebrew"'), TextDirection.rtl);
      expect(dirOf('>> 3. Hello'), TextDirection.ltr);
      expect(dirOf(''), TextDirection.ltr);
      expect(dirOf('123 – ?!'), TextDirection.ltr);
    });
  });

  group('SubtitleStage: right-to-left and unspaced scripts', () {
    Future<void> pump(WidgetTester tester, Size size, SubtitleBoard board, {double scale = 1}) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(backgroundColor: Colors.black, body: SubtitleStage(board: board, scale: scale)),
      ));
      await tester.pumpAndSettle();
    }

    Text textOf(WidgetTester tester, String key) => tester.widget<Text>(find.byKey(ValueKey(key)));

    RenderParagraph paragraphOf(WidgetTester tester, String key) =>
        tester.renderObject<RenderParagraph>(find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(RichText)));

    /// Paragrafın satır sayısı: her karakterin kutusunun üst kenarlarından.
    int linesOf(RenderParagraph paragraph, String text) => paragraph
        .getBoxesForSelection(TextSelection(baseOffset: 0, extentOffset: text.length))
        .map((box) => box.top.round())
        .toSet()
        .length;

    void expectInside(WidgetTester tester, Size size, String key) {
      final rect = tester.getRect(find.byKey(ValueKey(key)));
      expect(rect.left >= -0.01 && rect.top >= -0.01 && rect.right <= size.width + 0.01 && rect.bottom <= size.height + 0.01, true,
          reason: '$key $rect is outside $size');
    }

    testWidgets('Arabic line and previous line are laid out right-to-left, still centred', (tester) async {
      const size = Size(800, 400);
      final board = SubtitleBoard()
        ..show(hebrew, replace: false, keepPrevious: true)
        ..show(arabic, replace: false, keepPrevious: true);
      await pump(tester, size, board);
      expect(tester.takeException(), isNull);
      expect(textOf(tester, 'line').textDirection, TextDirection.rtl);
      expect(textOf(tester, 'previous').textDirection, TextDirection.rtl);
      expect(textOf(tester, 'line').textAlign, TextAlign.center);
      final paragraph = paragraphOf(tester, 'line');
      expect(paragraph.textDirection, TextDirection.rtl);
      // Sağdan sola: metnin ilk harfi son harfinin sağında durur.
      final first = paragraph.getBoxesForSelection(const TextSelection(baseOffset: 0, extentOffset: 1)).first;
      final last = paragraph.getBoxesForSelection(TextSelection(baseOffset: arabic.length - 2, extentOffset: arabic.length - 1)).first;
      expect(first.left > last.left, true, reason: 'first $first, last $last');
      // Sayfanın düzeni değişmez: satır ekranın ortasında, önceki satır üstünde.
      final line = tester.getRect(find.byKey(const ValueKey('line')));
      final previous = tester.getRect(find.byKey(const ValueKey('previous')));
      expect(line.center.dx, closeTo(size.width / 2, 0.5));
      expect(previous.center.dx, closeTo(size.width / 2, 0.5));
      expect(previous.bottom <= line.top + 0.01, true);
      expectInside(tester, size, 'line');
      expectInside(tester, size, 'previous');
    });

    testWidgets('Persian is right-to-left, Latin and Japanese stay left-to-right', (tester) async {
      await pump(tester, const Size(800, 400), SubtitleBoard()..show(persian, replace: false, keepPrevious: true));
      expect(paragraphOf(tester, 'line').textDirection, TextDirection.rtl);
      await pump(tester, const Size(800, 400), SubtitleBoard()..show('We have to go now!', replace: false, keepPrevious: true));
      expect(paragraphOf(tester, 'line').textDirection, TextDirection.ltr);
      await pump(tester, const Size(800, 400), SubtitleBoard()..show(japanese, replace: false, keepPrevious: true));
      expect(paragraphOf(tester, 'line').textDirection, TextDirection.ltr);
    });

    testWidgets('mixed line takes its direction from the first strong character', (tester) async {
      await pump(tester, const Size(800, 400), SubtitleBoard()..show('Kratos: $arabic', replace: false, keepPrevious: true));
      expect(paragraphOf(tester, 'line').textDirection, TextDirection.ltr);
      await pump(tester, const Size(800, 400), SubtitleBoard()..show('$arabic (Kratos)', replace: false, keepPrevious: true));
      expect(paragraphOf(tester, 'line').textDirection, TextDirection.rtl);
      expect(tester.takeException(), isNull);
    });

    for (final size in const [Size(400, 800), Size(800, 400)]) {
      final shape = '${size.width.toInt()}×${size.height.toInt()}';
      for (final sample in const [('Japanese', japanese), ('Chinese', chinese), ('Thai', thai)]) {
        testWidgets('${sample.$1} text without spaces wraps onto several lines and is not clipped ($shape)', (tester) async {
          final text = sample.$2 * 3;
          final board = SubtitleBoard()
            ..show(sample.$2, replace: false, keepPrevious: true)
            ..show(text, replace: false, keepPrevious: true);
          await pump(tester, size, board);
          expect(tester.takeException(), isNull);
          final paragraph = paragraphOf(tester, 'line');
          expect(paragraph.didExceedMaxLines, false);
          expect(textOf(tester, 'line').maxLines, isNull);
          // Yazı ekran genişliğindeki kutuda kırılır: tek satıra sığmaz, kutudan taşmaz.
          expect(paragraph.size.width <= size.width * 0.9 + 0.01, true, reason: '${paragraph.size}');
          expect(linesOf(paragraph, text) > 1, true, reason: 'lines: ${linesOf(paragraph, text)}');
          expect(linesOf(paragraphOf(tester, 'previous'), sample.$2) > 1, true);
          expectInside(tester, size, 'line');
          expectInside(tester, size, 'previous');
        });
      }
    }

    testWidgets('long Arabic text at the largest size wraps and stays on screen', (tester) async {
      const size = Size(400, 800);
      final text = List.filled(12, arabic).join(' ');
      await pump(tester, size, SubtitleBoard()..show(text, replace: false, keepPrevious: true), scale: 3);
      expect(tester.takeException(), isNull);
      final paragraph = paragraphOf(tester, 'line');
      expect(paragraph.textDirection, TextDirection.rtl);
      expect(paragraph.didExceedMaxLines, false);
      expect(linesOf(paragraph, text) > 1, true);
      expectInside(tester, size, 'line');
    });
  });

  group('ScreenPage', () {
    const bridge = MethodChannel('gt/bridge');
    final calls = <MethodCall>[];
    final chrome = <String>[];
    MockStreamHandlerEventSink? subtitles;
    MockStreamHandlerEventSink? states;

    setUp(() {
      calls.clear();
      chrome.clear();
      final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(bridge, (call) async {
        calls.add(call);
        return switch (call.method) {
          'getScreenPrefs' => {'scale': 1.0, 'previous': true},
          'getState' => {'phase': 'streaming', 'running': true},
          _ => null,
        };
      });
      messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'SystemChrome.setEnabledSystemUIMode') chrome.add(call.arguments as String);
        return null;
      });
    });

    Future<void> open(WidgetTester tester) async {
      // Akış taklitleri testin kendi (sahte saatli) bölgesinde kurulur; yoksa olaylar pump ile ilerlemez.
      tester.binding.defaultBinaryMessenger.setMockStreamHandler(
        const EventChannel('gt/subtitles'),
        MockStreamHandler.inline(onListen: (_, events) {
          subtitles = events;
        }, onCancel: (_) {
          subtitles = null;
        }),
      );
      tester.binding.defaultBinaryMessenger.setMockStreamHandler(
        const EventChannel('gt/events'),
        MockStreamHandler.inline(onListen: (_, events) {
          states = events;
        }, onCancel: (_) {
          states = null;
        }),
      );
      tester.view.physicalSize = const Size(800, 400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(const MaterialApp(home: ScreenPage()));
      await tester.pump();
      await tester.pump();
    }

    Future<void> close(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    }

    double opacityOf(WidgetTester tester, Finder child) =>
        tester.widget<AnimatedOpacity>(find.ancestor(of: child, matching: find.byType(AnimatedOpacity)).first).opacity;

    testWidgets('keeps the screen on and hides system bars while open, restores both on exit', (tester) async {
      await open(tester);
      expect(calls.where((c) => c.method == 'keepScreenOn').map((c) => c.arguments), [true]);
      expect(chrome, ['SystemUiMode.immersiveSticky']);
      expect(subtitles, isNotNull);
      await close(tester);
      expect(calls.where((c) => c.method == 'keepScreenOn').map((c) => c.arguments), [true, false]);
      expect(chrome, ['SystemUiMode.immersiveSticky', 'SystemUiMode.edgeToEdge']);
      expect(subtitles, isNull);
    });

    testWidgets('shows lines as they arrive, clears on clear and after the hold time', (tester) async {
      await open(tester);
      expect(find.text(t('screen.waiting')), findsOneWidget);
      subtitles!.success({'type': 'subtitle', 'text': 'Merhaba', 'duration': 15000, 'replace': false});
      await tester.pump();
      await tester.pump();
      expect(find.text('Merhaba'), findsOneWidget);
      expect(find.text(t('screen.waiting')), findsNothing);
      subtitles!.success({'type': 'subtitle', 'text': 'Nasılsın?', 'duration': 3000, 'replace': false});
      await tester.pump();
      await tester.pump();
      expect(opacityOf(tester, find.text('Nasılsın?')), 1);
      expect(opacityOf(tester, find.text('Merhaba')), 1);
      subtitles!.success({'type': 'clear'});
      await tester.pump();
      await tester.pump();
      expect(opacityOf(tester, find.text('Nasılsın?')), 0);
      expect(opacityOf(tester, find.text('Merhaba')), 0);
      subtitles!.success({'type': 'subtitle', 'text': 'Kısa', 'duration': 3000, 'replace': false});
      await tester.pump();
      await tester.pump();
      expect(opacityOf(tester, find.text('Kısa')), 1);
      await tester.pump(const Duration(milliseconds: 3100));
      expect(opacityOf(tester, find.text('Kısa')), 0);
      await close(tester);
    });

    testWidgets('controls hide after a few seconds, come back on tap, and choices are saved', (tester) async {
      await open(tester);
      expect(opacityOf(tester, find.text('A+')), 1);
      await tester.pump(const Duration(milliseconds: 4600));
      expect(opacityOf(tester, find.text('A+')), 0);
      // Gizliyken düğmeye denk gelen dokunuş düğmeyi çalıştırmaz, yalnızca denetimleri geri getirir.
      await tester.tapAt(tester.getCenter(find.text('A+')));
      await tester.pump();
      expect(opacityOf(tester, find.text('A+')), 1);
      expect(calls.where((c) => c.method == 'setScreenPrefs'), isEmpty);
      await tester.tap(find.text('A+'));
      await tester.pump();
      final saved = calls.lastWhere((c) => c.method == 'setScreenPrefs').arguments as Map;
      expect(saved['scale'], closeTo(1.15, 1e-9));
      expect(saved.containsKey('previous'), false);
      await tester.tap(find.text(t('screen.previous.on')));
      await tester.pump();
      expect(find.text(t('screen.previous.off')), findsOneWidget);
      expect((calls.lastWhere((c) => c.method == 'setScreenPrefs').arguments as Map)['previous'], false);
      expect(find.byTooltip(t('screen.close')), findsOneWidget);
      await close(tester);
    });

    testWidgets('says so when translation is off', (tester) async {
      await open(tester);
      states!.success({'phase': 'idle', 'running': false});
      await tester.pump();
      await tester.pump();
      expect(find.text(t('screen.status.off')), findsOneWidget);
      expect(find.text(t('screen.off.hint')), findsOneWidget);
      await close(tester);
    });
  });
}
