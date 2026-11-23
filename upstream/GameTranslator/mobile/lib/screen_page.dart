// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import 'bridge.dart';
import 'l10n.dart';
import 'main.dart';
import 'ui.dart';

/// İkinci ekran: TV uygulaması kurulamayan (desteklenmeyen) TV'ler için çeviri, TV'nin altına konan telefonda siyah
/// zemin üstünde büyük yazıyla gösterilir. Görünüm ve davranış, tarayıcıda açılan /ekran sayfasıyla
/// (windows/.../Resources/second-screen.html) aynıdır.

/// Ekran açık kalır ve sistem çubukları gizlenir (ikinci ekran sayfaları açılırken).
Future<void> enterScreenMode() async {
  await Bridge.keepScreenOn(true);
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
}

/// [enterScreenMode]'un geri alınması: ekran yeniden kararabilir, uygulamanın olağan kenardan kenara düzeni döner.
Future<void> leaveScreenMode() async {
  await Bridge.keepScreenOn(false);
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
}

/// Ekranda duran satır ve üstündeki soluk önceki satır. /ekran sayfasındaki kurallar: aynı cümlenin uzamış okuması
/// öncekinin yerine geçer; yeni cümlede eskisi üstte soluk kalır; silinince ikisi de kalkar.
class SubtitleBoard {
  String line = '';
  String previous = '';

  /// Şu an ekranda satır var mı?
  bool visible = false;

  /// Önceki satır gösteriliyor mu?
  bool previousVisible = false;

  /// Bu oturumda en az bir satır gösterildi mi (bekleme yazısı yalnız ilk satırdan önce görünür)?
  bool everShown = false;

  void show(String text, {required bool replace, required bool keepPrevious}) {
    if (text.isEmpty) return hide();
    if (visible && line.isNotEmpty && !replace && text != line && keepPrevious) {
      previous = line;
      previousVisible = true;
    }
    line = text;
    visible = true;
    everShown = true;
  }

  void hide() {
    visible = false;
    previousVisible = false;
  }

  /// "Önceki satır" kapatılınca üstteki soluk satır hemen kalkar.
  void dropPrevious() => previousVisible = false;
}

/// Yazı ölçeği: A− ve A+ ile değişir, hatırlanır.
abstract final class ScreenScale {
  static const min = 0.5;
  static const max = 3.0;
  static const step = 1.15;

  static double clamp(double scale) => scale.clamp(min, max).toDouble();
  static double bigger(double scale) => clamp(scale * step);
  static double smaller(double scale) => clamp(scale / step);

  /// Satırın yazı boyu: ekranın kısa kenarına göre, yatay da dikey de okunur kalır.
  static double lineSize(Size screen, double scale) =>
      math.max(18, math.min(screen.width, screen.height * 1.6) * 0.075 * clamp(scale));

  /// Önceki (soluk) satırın yazı boyu.
  static double previousSize(Size screen, double scale) => math.max(13, lineSize(screen, scale) * 0.55);
}

/// Satırın ekranda kalacağı süre: çekirdeğin verdiği süre, en az 2,5 sn (/ekran sayfasıyla aynı).
Duration screenHold(int durationMs) => Duration(milliseconds: math.max(2500, durationMs));

/// "Bu telefonda göster": telefonun kendi çevirisi, tam ekran. Çeviri açıkken de kapalıyken de açılabilir; satırlar
/// çekirdekten TV'ye giden olaylarla aynı anda gelir ([Bridge.subtitles]).
class ScreenPage extends StatefulWidget {
  const ScreenPage({super.key});

  @override
  State<ScreenPage> createState() => _ScreenPageState();
}

class _ScreenPageState extends State<ScreenPage> {
  /// Düğmeler bu kadar sonra gizlenir; ekrana dokununca geri gelir.
  static const controlsFor = Duration(milliseconds: 4500);

  final board = SubtitleBoard();
  double scale = 1;
  bool keepPrevious = true;
  bool controls = true;
  EngineState state = const EngineState();
  StreamSubscription<SubtitleEvent>? subtitles;
  StreamSubscription<EngineState>? states;
  Timer? hideTimer;
  Timer? controlsTimer;

  @override
  void initState() {
    super.initState();
    enterScreenMode();
    Bridge.screenPrefs().then((prefs) {
      if (!mounted) return;
      setState(() {
        scale = ScreenScale.clamp(prefs.scale);
        keepPrevious = prefs.previous;
      });
    });
    Bridge.state().then((current) {
      if (mounted) setState(() => state = current);
    });
    states = Bridge.states.listen((next) {
      if (mounted) setState(() => state = next);
    });
    subtitles = Bridge.subtitles.listen(onSubtitle);
    wake();
  }

  @override
  void dispose() {
    subtitles?.cancel();
    states?.cancel();
    hideTimer?.cancel();
    controlsTimer?.cancel();
    leaveScreenMode();
    super.dispose();
  }

  void onSubtitle(SubtitleEvent event) {
    if (!mounted) return;
    hideTimer?.cancel();
    setState(() {
      if (event.clear) {
        board.hide();
      } else {
        board.show(event.text, replace: event.replace, keepPrevious: keepPrevious);
      }
    });
    if (board.visible) {
      hideTimer = Timer(screenHold(event.durationMs), () {
        if (mounted) setState(board.hide);
      });
    }
  }

  /// Düğmeleri gösterir ve gizlenme süresini baştan başlatır.
  void wake() {
    controlsTimer?.cancel();
    if (!controls) setState(() => controls = true);
    controlsTimer = Timer(controlsFor, () {
      if (mounted) setState(() => controls = false);
    });
  }

  void resize(double next) {
    setState(() => scale = next);
    Bridge.saveScreenPrefs(scale: next);
    wake();
  }

  void togglePrevious() {
    setState(() {
      keepPrevious = !keepPrevious;
      if (!keepPrevious) board.dropPrevious();
    });
    Bridge.saveScreenPrefs(previous: keepPrevious);
    wake();
  }

  bool get streaming => state.phase == 'streaming';
  bool get active => state.running || (state.phase != 'idle' && state.phase != 'failed');

  /// Satır yokken ortada duran soluk yazı; çeviri akarken ilk satırdan sonra ekran boş kalır.
  String? get idleText {
    if (board.visible) return null;
    if (streaming) return board.everShown ? null : t('screen.waiting');
    if (active) return t('screen.connecting');
    return t('screen.off.hint');
  }

  (String, Color) get status => streaming
      ? (t('screen.status.on'), Tokens.success)
      : active
          ? (t('screen.status.connecting'), Tokens.warning)
          : (t('screen.status.off'), Tokens.warning);

  @override
  Widget build(BuildContext context) {
    final (statusText, statusColor) = status;
    return Scaffold(
      backgroundColor: Colors.black,
      body: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: wake,
        child: SafeArea(
          child: Stack(
            fit: StackFit.expand,
            children: [
              SubtitleStage(board: board, scale: scale, idleText: idleText),
              // Üstte: çıkış ve durum.
              _Fading(
                visible: controls,
                child: Align(
                  alignment: Alignment.topCenter,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(12, 10, 12, 0),
                    child: Row(
                      children: [
                        GlassIconButton(icon: CupertinoIcons.xmark, tooltip: t('screen.close'), onTap: () => Navigator.of(context).maybePop()),
                        Expanded(
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Container(width: 8, height: 8, decoration: BoxDecoration(color: statusColor, shape: BoxShape.circle)),
                              const SizedBox(width: 8),
                              Flexible(child: Text(statusText, style: Txt.footnote.copyWith(color: Tokens.gray))),
                            ],
                          ),
                        ),
                        // Durum yazısı ortada kalsın diye çıkış düğmesi kadar boşluk.
                        const SizedBox(width: 40),
                      ],
                    ),
                  ),
                ),
              ),
              // Altta: yazı boyu ve önceki satır.
              _Fading(
                visible: controls,
                child: Align(
                  alignment: Alignment.bottomCenter,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                    child: Wrap(
                      alignment: WrapAlignment.center,
                      spacing: 10,
                      runSpacing: 8,
                      children: [
                        _BarButton(label: 'A−', tooltip: t('screen.smaller'), onTap: () => resize(ScreenScale.smaller(scale))),
                        _BarButton(label: 'A+', tooltip: t('screen.bigger'), onTap: () => resize(ScreenScale.bigger(scale))),
                        _BarButton(label: keepPrevious ? t('screen.previous.on') : t('screen.previous.off'), onTap: togglePrevious),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Satırın çizildiği alan: ortada beyaz satır, üstünde soluk önceki satır. Yazı ekran genişliğinde satır kırar (boşluksuz
/// Japonca, Çince ve Tayca da: satır sayısı sınırsızdır, kırpma yoktur); yine de sığmayan uzun satır o satır için
/// bütünüyle küçülür. Yazım yönü metnin kendisinden gelir ([dirOf]): Arapça, Farsça ve İbranice sağdan sola yazılır;
/// satır her durumda ortalıdır, sayfanın düzeni ve düğmeleri değişmez.
class SubtitleStage extends StatelessWidget {
  const SubtitleStage({super.key, required this.board, required this.scale, this.idleText});

  final SubtitleBoard board;
  final double scale;
  final String? idleText;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, box) {
        final screen = box.biggest;
        final lineSize = ScreenScale.lineSize(screen, scale);
        final previousSize = ScreenScale.previousSize(screen, scale);
        final horizontal = screen.width * 0.05;
        final vertical = screen.height * 0.04;
        final idle = idleText;
        return Padding(
          padding: EdgeInsets.symmetric(horizontal: horizontal, vertical: vertical),
          child: Center(
            child: FittedBox(
              fit: BoxFit.scaleDown,
              child: SizedBox(
                width: math.max(1, screen.width - 2 * horizontal),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    AnimatedOpacity(
                      duration: const Duration(milliseconds: 200),
                      opacity: board.previousVisible ? 1 : 0,
                      child: Padding(
                        padding: EdgeInsets.only(bottom: previousSize * 0.5),
                        child: subtitleText(
                          board.previous,
                          key: const ValueKey('previous'),
                          style: TextStyle(color: Tokens.gray, fontSize: previousSize, fontWeight: FontWeight.w500, height: 1.25),
                        ),
                      ),
                    ),
                    if (idle != null)
                      Text(
                        idle,
                        key: const ValueKey('idle'),
                        textAlign: TextAlign.center,
                        textScaler: TextScaler.noScaling,
                        style: TextStyle(color: const Color(0xFF636366), fontSize: lineSize, fontWeight: FontWeight.w500, height: 1.22),
                      )
                    else
                      AnimatedOpacity(
                        duration: const Duration(milliseconds: 120),
                        opacity: board.visible ? 1 : 0,
                        child: subtitleText(
                          board.line,
                          key: const ValueKey('line'),
                          style: TextStyle(color: Colors.white, fontSize: lineSize, fontWeight: FontWeight.w600, height: 1.22),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// Ekrandaki satır: ortalı, yazım yönü metinden, sınırsız satır kırma. Metinde kumanda tuşu varsa ([buttonSpans]) tuşlar
/// satırın içinde simge olarak çizilir; yoksa düz [Text] kalır.
Widget subtitleText(String text, {required Key key, required TextStyle style}) {
  final spans = buttonSpans(text, style.fontSize ?? 14);
  if (spans == null) {
    return Text(
      text,
      key: key,
      textAlign: TextAlign.center,
      textDirection: dirOf(text),
      softWrap: true,
      overflow: TextOverflow.visible,
      textScaler: TextScaler.noScaling,
      style: style,
    );
  }
  return Text.rich(
    spans,
    key: key,
    textAlign: TextAlign.center,
    textDirection: dirOf(text),
    softWrap: true,
    overflow: TextOverflow.visible,
    textScaler: TextScaler.noScaling,
    style: style,
  );
}

// --- Kumanda tuşları (tv/webos/app.js ve /ekran sayfasıyla aynı kural ve çizim) ---
// Bilgisayar tuş simgesini görüntüden tanıyıp metne koyar ("Tırmanmak için ✕ basılı tut", "R2", "Ⓐ", "LB").
// Sözcük sınırı büyük-küçük harfe duyarlıdır: "BLT", "LBS", "lb", "Alt" tuş sayılmaz.
final RegExp buttonPattern =
    RegExp(r'(✕|×|✖|○|◯|△|□|✜|▦|⧉|☰|[↑↓←→]|[Ⓐ-Ⓩ]|\b(?:L[123]|R[123]|[LR][BTS]|OPTIONS|TOUCHPAD)\b)');

/// Metni tuş simgeleriyle birlikte span'lere böler; metinde tuş yoksa null.
InlineSpan? buttonSpans(String text, double fontSize) {
  final matches = buttonPattern.allMatches(text).toList();
  if (matches.isEmpty) return null;
  final children = <InlineSpan>[];
  var last = 0;
  for (final match in matches) {
    if (match.start > last) children.add(TextSpan(text: text.substring(last, match.start)));
    children.add(WidgetSpan(
      alignment: PlaceholderAlignment.middle,
      child: ButtonIcon(token: match[0]!, fontSize: fontSize),
    ));
    last = match.end;
  }
  if (last < text.length) children.add(TextSpan(text: text.substring(last)));
  return TextSpan(children: children);
}

/// Tek tuşun simgesi: PlayStation yüz tuşları kendi renginde dairede, Xbox Ⓐ Ⓑ Ⓧ Ⓨ koyu dairede renkli harf,
/// L1 / RT gibi tuşlar yuvarlak köşeli kutuda, ⧉ (View) ve ☰ (Menu) koyu dairede. Boyutlar satırın yazı boyuna göre.
class ButtonIcon extends StatelessWidget {
  const ButtonIcon({super.key, required this.token, required this.fontSize});

  final String token;
  final double fontSize;

  static const _rim = Color(0xE6FFFFFF);
  static const _psBack = Color(0x8C000000);
  static const _xboxBack = Color(0xFF1B1B1B);
  static const _face = {'✕': 'cross', '×': 'cross', '✖': 'cross', '○': 'circle', '◯': 'circle', '△': 'triangle', '□': 'square'};
  static const _faceColor = {
    'cross': Color(0xFF7FB8F0),
    'circle': Color(0xFFFF6F78),
    'triangle': Color(0xFF3FDCB4),
    'square': Color(0xFFF08CE4),
  };
  static const _xbox = {'Ⓐ': 'A', 'Ⓑ': 'B', 'Ⓧ': 'X', 'Ⓨ': 'Y'};
  static const _xboxColor = {
    'A': Color(0xFF8DC63F),
    'B': Color(0xFFFF4B4B),
    'X': Color(0xFF3D9BFF),
    'Y': Color(0xFFFFB900),
  };
  static const _symbol = Color(0xFFE6E6E6);

  Widget _glyph(String text, Color color, double size) => Text(
        text,
        textScaler: TextScaler.noScaling,
        textDirection: TextDirection.ltr,
        style: TextStyle(color: color, fontSize: size, fontWeight: FontWeight.w700, height: 1),
      );

  /// Daire biçimli tuş (yüz tuşları, yön tuşları, View, Menu).
  Widget _round(double em, Color back, Widget? child) => Container(
        width: em * 1.15,
        height: em * 1.15,
        alignment: Alignment.center,
        decoration: BoxDecoration(shape: BoxShape.circle, color: back, border: Border.all(color: _rim, width: em * 0.08)),
        child: child,
      );

  /// Daire içindeki çizgi şekil (○ ve □ yazı tipinde küçük kalıyor; /ekran sayfasında da CSS ile çizilir).
  Widget _outline(double em, Color color, {required bool circle}) => Container(
        width: em * 0.5,
        height: em * 0.5,
        decoration: BoxDecoration(
          shape: circle ? BoxShape.circle : BoxShape.rectangle,
          borderRadius: circle ? null : BorderRadius.circular(em * 0.06),
          border: Border.all(color: color, width: em * 0.1),
        ),
      );

  Widget _frame(double em, double width, double height, {Color? fill}) => Container(
        width: em * width,
        height: em * height,
        decoration: BoxDecoration(
          color: fill,
          borderRadius: BorderRadius.circular(em * 0.04),
          border: Border.all(color: _symbol, width: em * 0.07),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final em = fontSize * 0.95;
    final Widget icon;
    final face = _face[token];
    final xbox = _xbox[token];
    if (face != null) {
      final color = _faceColor[face]!;
      icon = _round(
        em,
        _psBack,
        switch (face) {
          'circle' => _outline(em, color, circle: true),
          'square' => _outline(em, color, circle: false),
          'triangle' => _glyph('△', color, em),
          _ => _glyph('✕', color, em),
        },
      );
    } else if (xbox != null) {
      icon = _round(em, _xboxBack, _glyph(xbox, _xboxColor[xbox]!, em));
    } else if (token == '⧉') {
      // View: üst üste iki küçük pencere.
      icon = _round(
        em,
        _xboxBack,
        SizedBox(
          width: em * 0.52,
          height: em * 0.44,
          child: Stack(
            children: [
              Positioned(left: 0, top: 0, child: _frame(em, 0.36, 0.28)),
              Positioned(left: em * 0.16, top: em * 0.16, child: _frame(em, 0.36, 0.28, fill: _xboxBack)),
            ],
          ),
        ),
      );
    } else if (token == '☰') {
      // Menu: üç yatay çizgi.
      final bar = Container(width: em * 0.46, height: em * 0.07, color: _symbol);
      icon = _round(
        em,
        _xboxBack,
        Column(
          mainAxisSize: MainAxisSize.min,
          children: [bar, SizedBox(height: em * 0.08), bar, SizedBox(height: em * 0.08), bar],
        ),
      );
    } else if (RegExp(r'^[LR](?:[123]|[BTS])$').hasMatch(token) || token == 'OPTIONS' || token == 'TOUCHPAD') {
      // Omuz tuşu ve OPTIONS / dokunmatik yüzey: yuvarlak köşeli kutuda yazı.
      final small = fontSize * 0.72;
      icon = Container(
        constraints: BoxConstraints(minWidth: small * 1.6),
        padding: EdgeInsets.fromLTRB(small * 0.25, small * 0.12, small * 0.25, small * 0.1),
        decoration: BoxDecoration(
          color: _psBack,
          borderRadius: BorderRadius.circular(small * 0.35),
          border: Border.all(color: _rim, width: small * 0.08),
        ),
        child: Text(
          token == 'TOUCHPAD' ? '▦' : token,
          textAlign: TextAlign.center,
          textScaler: TextScaler.noScaling,
          textDirection: TextDirection.ltr,
          style: TextStyle(color: Colors.white, fontSize: small, fontWeight: FontWeight.w700, height: 1),
        ),
      );
    } else {
      // Yön tuşu, dokunmatik yüzey, harita işareti, öbür daireli harfler (Ⓒ…Ⓩ).
      icon = _round(em, _psBack, _glyph(token, Colors.white, em));
    }
    return Padding(padding: EdgeInsets.symmetric(horizontal: fontSize * 0.12), child: icon);
  }
}

/// Birkaç saniye sonra kaybolan denetimler; gizliyken dokunuş almaz.
class _Fading extends StatelessWidget {
  const _Fading({required this.visible, required this.child});

  final bool visible;
  final Widget child;

  @override
  Widget build(BuildContext context) => IgnorePointer(
        ignoring: !visible,
        child: AnimatedOpacity(duration: const Duration(milliseconds: 250), opacity: visible ? 1 : 0, child: child),
      );
}

/// Alt çubuktaki düğme (iOS'un koyu, yuvarlak köşeli düğmesi).
class _BarButton extends StatelessWidget {
  const _BarButton({required this.label, required this.onTap, this.tooltip});

  final String label;
  final String? tooltip;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip ?? '',
      child: Material(
        color: const Color(0xFF2C2C2E),
        borderRadius: BorderRadius.circular(12),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: ConstrainedBox(
            constraints: const BoxConstraints(minWidth: 52, minHeight: 44),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Text(label, textAlign: TextAlign.center, style: Txt.subhead.copyWith(fontWeight: FontWeight.w600, color: Colors.white)),
            ),
          ),
        ),
      ),
    );
  }
}
