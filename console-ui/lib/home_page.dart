// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';
import 'dart:typed_data';

import 'package:material_ui/material_ui.dart';

import 'bridge.dart';
import 'guide_page.dart';
import 'history_page.dart';
import 'l10n.dart';
import 'language_section.dart';
import 'main.dart';
import 'pc_screen_page.dart';
import 'screen_page.dart';
import 'tv_install.dart';
import 'settings_page.dart';
import 'tv_install_page.dart';
import 'ui.dart';

/// Ana ekran: altta yüzen cam sekme çubuğu ve üç sekme (Çeviri, Rehber, Ayarlar).
/// Çeviri sekmesi: başlat/durdur, PS5 · telefon · TV durumu, canlı önizleme ve son çeviri.
class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.setup, required this.onSetupChanged});

  final SetupInfo setup;
  final Future<void> Function() onSetupChanged;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with SingleTickerProviderStateMixin {
  /// Sekme adları her çizimde okunur: uygulama dili değişince hemen güncellenir.
  List<(IconData, IconData, String)> get tabs => [
        (CupertinoIcons.captions_bubble, CupertinoIcons.captions_bubble_fill, t('home.tab.translate')),
        (CupertinoIcons.book, CupertinoIcons.book_fill, t('home.tab.guide')),
        (CupertinoIcons.gear_alt, CupertinoIcons.gear_alt_fill, t('home.tab.settings')),
      ];

  int tab = 0;
  EngineState state = const EngineState();
  AppSettings? settings;
  StreamSubscription<EngineState>? subscription;
  Timer? previewTimer;
  Uint8List? preview;
  late final AnimationController pulse;

  /// Görüntü akarken TV'nin bağlanmasının beklenmeye başladığı an; TV bağlıyken ya da çeviri kapalıyken null.
  DateTime? tvWaitingSince;

  /// TV uygulaması telefonu genelde birkaç saniyede bulur; bu kadar beklendiyse bir sorun vardır.
  static const tvWaitLimit = Duration(seconds: 25);

  bool get tvStuck => tvWaitingSince != null && DateTime.now().difference(tvWaitingSince!) > tvWaitLimit;

  /// TV uygulamasının yeni sürümü telefonda hazırsa (kurulu, yeni); ana ekranda güncelleme önerilir.
  (String, String)? tvUpdate;

  /// Kullanıcı "sonra" dediyse bu açılışta öneri gösterilmez. TV'deki sürümü telefon yalnız kendi kurduğunda bilir:
  /// TV başka yerden (bilgisayardan) güncellendiyse kayıt eski kalır, öneri yersiz olabilir.
  bool tvUpdateDismissed = false;

  Future<void> checkTvUpdate() async {
    final update = await tvUpdateAvailable();
    if (mounted) setState(() => tvUpdate = update);
  }

  Future<void> openTvInstall() async {
    await Navigator.of(context).push(appleRoute<void>((_) => const TvInstallPage()));
    checkTvUpdate();
  }

  @override
  void initState() {
    super.initState();
    pulse = AnimationController(vsync: this, duration: const Duration(milliseconds: 1800))..repeat(reverse: true);
    subscription = Bridge.states.listen((next) {
      if (!mounted) return;
      setState(() {
        state = next;
        final waiting = next.phase == 'streaming' && next.tvClients == 0;
        tvWaitingSince = waiting ? (tvWaitingSince ?? DateTime.now()) : null;
      });
    });
    loadSettings();
    checkTvUpdate();
    // Önizleme yalnız görüntü akarken yenilenir. Aynı sayaç, TV bekleme süresi dolunca uyarının değişmesini sağlar.
    previewTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      refreshPreview();
      if (mounted && tvWaitingSince != null) setState(() {});
    });
  }

  @override
  void dispose() {
    subscription?.cancel();
    previewTimer?.cancel();
    pulse.dispose();
    super.dispose();
  }

  bool get active => state.running || (state.phase != 'idle' && state.phase != 'failed');
  bool get streaming => state.phase == 'streaming';

  Future<void> loadSettings() async {
    final loaded = await Bridge.settings();
    if (mounted) setState(() => settings = loaded);
    offerNotice(loaded);
  }

  /// Sürüm duyurusu: ana ekran ilk açıldığında bir kez; yalnız çeviri dili Türkçe olana (GTA 6 paketi Türkçedir).
  /// Gösterilmeden önce görüldü sayılır. Yeni duyuru için kimlik değişir (Windows ve Mac'teki ReleaseNotice ile aynı).
  static const noticeId = '2026-11-paket-sunucusu';
  bool noticeOffered = false;

  Future<void> offerNotice(AppSettings loaded) async {
    if (noticeOffered || loaded.targetLanguage != 'tr') return;
    noticeOffered = true;
    if (await Bridge.noticeSeen(noticeId)) return;
    await Bridge.setNoticeSeen(noticeId);
    if (!mounted) return;
    await noticeApple(context, title: t('notice.packs.title'), message: t('notice.packs.body'), action: t('notice.packs.ok'));
  }

  Future<void> refreshPreview() async {
    if (!streaming || tab != 0) return;
    final image = await Bridge.snapshot(width: 640);
    if (mounted && image != null) setState(() => preview = image);
  }

  /// Sekme değişir; Çeviri'ye dönülünce bölge çerçevesi güncel ayarlarla çizilsin diye ayarlar yeniden okunur.
  void selectTab(int index) {
    setState(() => tab = index);
    if (index == 0) loadSettings();
  }

  Future<void> toggle() async {
    if (active) {
      await Bridge.stop();
    } else {
      try {
        await Bridge.start();
      } catch (error) {
        // Sistem çeviri hizmetini başlatmadıysa düğme sessiz kalmasın; ayrıntı Ayarlar > Günlük'te de yazar.
        if (mounted) toast(context, '$error');
      }
    }
  }

  Future<void> sendTest() async {
    await Bridge.testSubtitle();
    if (!mounted) return;
    toast(context, state.tvClients > 0 ? t('home.test.sent') : t('home.test.no_tv'));
  }

  /// Görüntü Xbox'tan mı alınıyor (Beta)? Değilse PS5.
  bool get xbox => widget.setup.isXbox;

  (String, String, Color) get headline => switch (state.phase) {
        'streaming' => (t('home.phase.streaming.title'), t('home.phase.streaming.hint'), Tokens.success),
        'starting' => (t('home.phase.starting.title'), t('home.phase.starting.hint'), Tokens.warning),
        'connecting' when xbox => (t('home.phase.connecting_xbox.title'), t('home.phase.connecting_xbox.hint'), Tokens.warning),
        'connecting' => (t('home.phase.connecting.title'), t('home.phase.connecting.hint'), Tokens.warning),
        'waking' => (t('home.phase.waking.title'), t('home.phase.waking.hint'), Tokens.warning),
        'reconnecting' => (t('home.phase.reconnecting.title'), t('home.phase.reconnecting.hint'), Tokens.warning),
        'stopping' => (t('home.phase.stopping.title'), '', Tokens.textSecondary),
        // Xbox oturumu çoğu hatada kendisi yeniden dener; çeviri açık kalır.
        'failed' when xbox && state.running => (t('home.phase.failed_xbox.title'), t('home.phase.failed_xbox.hint'), Tokens.danger),
        'failed' => (t('home.phase.failed.title'), t('home.phase.failed.hint'), Tokens.danger),
        _ => (t('home.phase.idle.title'), t('home.phase.idle.hint'), Tokens.textSecondary),
      };

  List<FlowNode> get nodes {
    final connecting = const {'starting', 'connecting', 'waking', 'reconnecting'}.contains(state.phase);
    final address = state.localAddress;
    final tvOn = state.tvClients > 0;
    return [
      FlowNode(
        icon: CupertinoIcons.gamecontroller_fill,
        label: widget.setup.consoleLabel,
        detail: streaming
            ? t('home.node.connected')
            : switch (state.phase) {
                'waking' => t('home.node.waking'),
                'starting' || 'connecting' || 'reconnecting' => t('home.node.connecting'),
                _ => xbox ? t('home.node.selected') : t('home.node.paired'),
              },
        color: streaming ? Tokens.success : (connecting ? Tokens.warning : Tokens.textSecondary),
        busy: connecting,
      ),
      FlowNode(
        icon: CupertinoIcons.device_phone_portrait,
        label: t('home.node.phone'),
        detail: address == null ? t('home.node.no_wifi') : (streaming ? t('home.node.translating') : t('home.node.ready')),
        color: address == null ? Tokens.danger : (streaming ? Tokens.accent : Tokens.textSecondary),
      ),
      FlowNode(
        icon: CupertinoIcons.tv_fill,
        label: 'TV',
        detail: tvOn ? t('home.node.connected') : (active ? t('home.node.waiting') : t('home.node.not_connected')),
        color: tvOn ? Tokens.success : (active ? Tokens.warning : Tokens.textSecondary),
      ),
    ];
  }

  /// Oynanan oyun: PS5'in bildirdiği oyun, yoksa satırlarını gösteren paketin oyunu; bilinmiyorsa "Otomatik"
  /// (bilgisayardaki "Oyun: Otomatik").
  String get gameValue {
    if (state.runningGame.isNotEmpty) return state.runningGame;
    if ((settings?.packsApply ?? false) && state.packGame.isNotEmpty) return state.packGame;
    return t('home.status.auto');
  }

  /// Okunan yer: otomatik ya da elle seçilen bölge (bilgisayardaki "Okunan yer").
  String get areaValue => (settings?.autoRegion ?? true) ? t('home.status.auto') : t('home.status.area.manual');

  /// TV'deki görünüm özeti: yazı boyu ve rengi.
  String get lookValue {
    final style = settings?.style;
    if (style == null) return '';
    final size = switch ((style['fontSize'] as num?)?.toInt()) { 36 => 's', 58 => 'l', 72 => 'xl', _ => 'm' };
    final color = style['textColor'] as String? ?? 'white';
    final colorKey = const {'white', 'yellow', 'cyan', 'green', 'orange'}.contains(color) ? color : 'white';
    final sizeName = t('settings.look.size.$size');
    final colorName = t('settings.look.color.$colorKey');
    return '$sizeName · $colorName';
  }

  Future<void> openHistory() => Navigator.of(context).push(appleRoute<void>((_) => const HistoryPage()));

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.paddingOf(context).bottom;
    return Stack(
      fit: StackFit.expand,
      children: [
        const GlassBackground(),
        Scaffold(
          backgroundColor: Colors.transparent,
          body: Stack(
            fit: StackFit.expand,
            children: [
              IndexedStack(
                index: tab,
                children: [
                  translateTab(),
                  const GuideTab(),
                  SettingsTab(setup: widget.setup, onSetupChanged: widget.onSetupChanged, active: tab == 2),
                ],
              ),
              Positioned(
                left: 0,
                right: 0,
                bottom: bottom + 12,
                child: Center(child: GlassTabBar(items: tabs, index: tab, onTap: selectTab)),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget translateTab() {
    final (title, hint, color) = headline;
    final failed = state.phase == 'failed';
    final tvOn = state.tvClients > 0;
    return LargeTitleList(
      title: 'DraBornTranslate',
      trailing: const Row(mainAxisSize: MainAxisSize.min, children: [LanguageGlobeButton(), SizedBox(width: 10), GtLogo(size: 38, glow: false)]),
      children: [
        const SizedBox(height: 22),
        Center(
          child: _PowerButton(
            phase: state.phase,
            active: active,
            color: color,
            pulse: pulse,
            // Xbox yayını Remote Play kütüphanesini kullanmaz.
            onTap: (state.nativeAvailable || xbox) && state.phase != 'stopping' ? toggle : null,
          ),
        ),
        const SizedBox(height: 22),
        Text(title, textAlign: TextAlign.center, style: Txt.title1),
        const SizedBox(height: 4),
        Text(
          failed && state.message.isNotEmpty ? nt(state.message) : hint,
          textAlign: TextAlign.center,
          style: Txt.subhead.copyWith(color: failed ? Tokens.danger : Tokens.textSecondary),
        ),
        if (xbox) ...[
          const SizedBox(height: 10),
          const Center(child: Pill(text: 'Xbox · Beta', color: Tokens.warning)),
        ],
        if (tvUpdate case (final installed, final bundled) when !tvUpdateDismissed) ...[
          const SizedBox(height: 18),
          Group(
            inset: 60,
            children: [
              Cell(
                icon: CupertinoIcons.arrow_down_circle_fill,
                iconColor: Tokens.warning,
                title: t('home.tv_update.title'),
                subtitle: t('home.tv_update.subtitle', [installed, bundled]),
                trailing: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => setState(() => tvUpdateDismissed = true),
                  child: const Padding(
                    padding: EdgeInsets.all(6),
                    child: Icon(CupertinoIcons.xmark_circle_fill, size: 22, color: Tokens.textSecondary),
                  ),
                ),
                onTap: openTvInstall,
              ),
            ],
          ),
        ],
        const SizedBox(height: 26),
        FlowRow(nodes: nodes),
        // Bilgisayarın ana ekranındaki gibi: oynanan oyun, okunan yer ve TV'deki görünüm; altyazı geçmişi.
        const SizedBox(height: 12),
        Group(
          inset: 60,
          children: [
            Cell(
              key: const ValueKey('home-game'),
              icon: CupertinoIcons.gamecontroller,
              iconColor: Tokens.accentText,
              title: t('home.status.game'),
              value: gameValue,
            ),
            Cell(
              key: const ValueKey('home-area'),
              icon: CupertinoIcons.crop,
              iconColor: Tokens.accentText,
              title: t('home.status.area'),
              value: areaValue,
              subtitle: (settings?.extraLiveCount ?? 0) > 0 ? tn('home.status.extra', settings!.extraLiveCount) : null,
              chevron: true,
              onTap: () => selectTab(2),
            ),
            Cell(
              key: const ValueKey('home-look'),
              icon: CupertinoIcons.textformat,
              iconColor: Tokens.accentText,
              title: t('home.status.look'),
              value: lookValue,
              chevron: true,
              onTap: () => selectTab(2),
            ),
            Cell(
              icon: CupertinoIcons.viewfinder,
              iconColor: Tokens.teal,
              title: 'DraBorn Odak',
              subtitle: 'Oyun karesinden bir alan seç ve çevir.',
              chevron: true,
              onTap: () => Bridge.openTool('focus'),
            ),
            Cell(
              icon: CupertinoIcons.cube_box_fill,
              iconColor: Tokens.purple,
              title: 'Oyun paketleri',
              chevron: true,
              onTap: () => Bridge.openTool('packs'),
            ),
            Cell(
              key: const ValueKey('home-history'),
              icon: CupertinoIcons.clock,
              iconColor: Tokens.accentText,
              title: t('history.title'),
              chevron: true,
              onTap: openHistory,
            ),
          ],
        ),
        // TV'sine uygulama kurulamayanlar için: çeviri telefonun kendi ekranında, TV bağlanmadan da çalışır.
        const SizedBox(height: 12),
        Group(
          inset: 60,
          children: [
            Cell(
              icon: CupertinoIcons.device_phone_landscape,
              iconColor: Tokens.teal,
              title: t('home.screen.title'),
              subtitle: t('home.screen.subtitle'),
              chevron: true,
              onTap: () => Navigator.of(context).push(appleRoute<void>((_) => const ScreenPage())),
            ),
            Cell(
              icon: CupertinoIcons.globe,
              iconColor: Tokens.accentText,
              title: 'TV tarayıcısında göster',
              chevron: true,
              onTap: () => Bridge.openTool('tvBrowser'),
            ),
            // Çeviriyi bilgisayar yapıyorsa telefon yalnız ekran olur (bilgisayardaki ikinci ekran kartı burayı gösterir)
            Cell(
              icon: CupertinoIcons.desktopcomputer,
              iconColor: Tokens.indigo,
              title: t('home.pc_screen.title'),
              subtitle: t('home.pc_screen.subtitle'),
              chevron: true,
              onTap: () => Navigator.of(context).push(appleRoute<void>((_) => const PcScreenPage())),
            ),
          ],
        ),
        if (!state.nativeAvailable && !xbox) ...[
          const SizedBox(height: 12),
          Notice(
            icon: CupertinoIcons.exclamationmark_circle_fill,
            color: Tokens.danger,
            title: t('home.unsupported.title'),
            text: t('home.unsupported.text'),
          ),
        ],
        if (state.localAddress == null) ...[
          const SizedBox(height: 12),
          Notice(
            icon: CupertinoIcons.wifi_slash,
            color: Tokens.danger,
            title: t('home.no_wifi.title'),
            text: t('home.no_wifi.text', [widget.setup.consoleLabel]),
          ),
        ],
        if (streaming && !tvOn && !tvStuck && state.otherServer == null) ...[
          const SizedBox(height: 12),
          Notice(
            icon: CupertinoIcons.tv,
            color: Tokens.warning,
            title: t('home.open_tv.title'),
            text: t('home.open_tv.text'),
          ),
        ],
        if (streaming && !tvOn && state.otherServer != null) ...[
          const SizedBox(height: 12),
          Notice(
            icon: CupertinoIcons.exclamationmark_triangle_fill,
            color: Tokens.warning,
            title: t('home.other_server.title'),
            text: t('home.other_server.text', [state.otherServer]),
          ),
        ],
        if (streaming && !tvOn && tvStuck && state.otherServer == null) ...[
          const SizedBox(height: 12),
          Notice(
            icon: CupertinoIcons.exclamationmark_triangle_fill,
            color: Tokens.warning,
            title: t('home.tv_stuck.title'),
            text: t('home.tv_stuck.text'),
            action: TextButton(
              style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: const Size(0, 32)),
              onPressed: () => Navigator.of(context).push(appleRoute<void>((_) => const TvHelpPage())),
              child: Text(t('home.tv_stuck.action'), style: const TextStyle(fontWeight: FontWeight.w600)),
            ),
          ),
        ],
        if (active) ...[
          GroupHeader(t('home.live')),
          _LiveCard(state: state, preview: streaming ? preview : null, settings: settings, console: widget.setup.consoleLabel),
          const SizedBox(height: 12),
          Group(
            inset: 60,
            children: [
              Cell(icon: CupertinoIcons.captions_bubble_fill, iconColor: Tokens.indigo, title: t('home.send_test'), onTap: sendTest),
            ],
          ),
        ] else ...[
          GroupHeader(t('home.how')),
          Group(
            inset: 60,
            children: [
              _HowStep(number: 1, text: t('home.how.1')),
              _HowStep(number: 2, text: t('home.how.2')),
              _HowStep(number: 3, text: t('home.how.3')),
              Cell(
                icon: CupertinoIcons.tv_fill,
                iconColor: Tokens.purple,
                title: t('home.install_tv.title'),
                subtitle: t('home.install_tv.subtitle'),
                chevron: true,
                onTap: openTvInstall,
              ),
              Cell(icon: CupertinoIcons.book_fill, iconColor: Tokens.warning, title: t('home.guide'), chevron: true, onTap: () => selectTab(1)),
            ],
          ),
        ],
      ],
    );
  }
}

/// Ortadaki büyük başlat/durdur düğmesi: koyu bir halkanın içinde düz daire (kapalıyken bilgisayardaki ana düğme gibi
/// mavi, açıkken koyu ve durum renginde kenarlı).
class _PowerButton extends StatelessWidget {
  const _PowerButton({required this.phase, required this.active, required this.color, required this.pulse, required this.onTap});

  final String phase;
  final bool active;
  final Color color;
  final Animation<double> pulse;
  final VoidCallback? onTap;

  static const double outer = 172;
  static const double inner = 144;

  @override
  Widget build(BuildContext context) {
    final streaming = phase == 'streaming';
    final busy = active && !streaming;
    final idle = !active;
    final tone = idle ? (phase == 'failed' ? Tokens.danger : Tokens.accent) : color;
    return Semantics(
      button: true,
      label: active ? t('home.stop') : t('home.start'),
      child: AnimatedBuilder(
        animation: pulse,
        builder: (context, child) {
          // Kapalıyken ve çeviri açıkken hafif bir ışık yavaşça nefes alır; bağlanırken sabit kalır.
          final glow = busy ? 0.3 : Curves.easeInOut.transform(pulse.value);
          return Container(
            width: outer,
            height: outer,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: Tokens.surface,
              boxShadow: [BoxShadow(color: tone.withValues(alpha: 0.10 + 0.10 * glow), blurRadius: 32 + 16 * glow, spreadRadius: 1 + 2 * glow)],
            ),
            child: child,
          );
        },
        child: Stack(
          alignment: Alignment.center,
          children: [
            if (busy) SizedBox(width: outer - 4, height: outer - 4, child: CircularProgressIndicator(strokeWidth: 2.5, color: color)),
            Material(
              shape: const CircleBorder(),
              clipBehavior: Clip.antiAlias,
              color: Colors.transparent,
              child: Ink(
                width: inner,
                height: inner,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: idle ? Tokens.accent : Tokens.surfaceHigh,
                  border: idle ? null : Border.all(color: tone.withValues(alpha: busy ? 0.3 : 0.9), width: 2),
                ),
                child: InkWell(
                  onTap: onTap,
                  customBorder: const CircleBorder(),
                  child: Icon(
                    active ? CupertinoIcons.stop_fill : CupertinoIcons.play_fill,
                    size: active ? 46 : 54,
                    color: Colors.white.withValues(alpha: busy ? 0.6 : 1),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// "Nasıl kullanılır" listesindeki numaralı satır.
class _HowStep extends StatelessWidget {
  const _HowStep({required this.number, required this.text});

  final int number;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      child: Row(
        children: [
          Container(
            width: 30,
            height: 30,
            alignment: Alignment.center,
            decoration: BoxDecoration(color: Tokens.surfaceHigh, borderRadius: BorderRadius.circular(7.2)),
            child: Text('$number', style: Txt.subhead.copyWith(fontWeight: FontWeight.w700)),
          ),
          const SizedBox(width: 14),
          Expanded(child: Text(text, style: Txt.callout)),
        ],
      ),
    );
  }
}

/// Çeviri açıkken: oyun görüntüsü, altyazı bölgesi ve son çeviri.
class _LiveCard extends StatelessWidget {
  const _LiveCard({required this.state, required this.preview, required this.settings, required this.console});

  final EngineState state;
  final Uint8List? preview;
  final AppSettings? settings;

  /// Görüntünün geldiği konsolun kısa adı (PS5 ya da Xbox).
  final String console;

  @override
  Widget build(BuildContext context) {
    final hasTranslation = state.lastTranslation.isNotEmpty;
    final streaming = state.phase == 'streaming';
    return Glass(
      clip: true,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (preview != null)
            RegionPreview(image: preview!, settings: settings, subtitle: hasTranslation ? state.lastTranslation : null)
          else
            AspectRatio(
              aspectRatio: 16 / 9,
              child: Container(
                color: Colors.black,
                alignment: Alignment.center,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const CupertinoActivityIndicator(radius: 12),
                    const SizedBox(height: 12),
                    Text(streaming ? t('home.preview.preparing') : t('home.preview.waiting', [console]), style: Txt.footnote.copyWith(color: Tokens.textSecondary)),
                  ],
                ),
              ),
            ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(child: Text(t('home.last'), style: Txt.headline)),
                    if (streaming) Pill(text: tn('home.lines', state.translated), color: Tokens.accent),
                  ],
                ),
                const SizedBox(height: 8),
                if (hasTranslation) ...[
                  SizedBox(
                    width: double.infinity,
                    child: Text(
                      state.lastTranslation,
                      textDirection: dirOf(state.lastTranslation),
                      style: Txt.title3.copyWith(fontWeight: FontWeight.w500),
                    ),
                  ),
                  const SizedBox(height: 4),
                  SizedBox(
                    width: double.infinity,
                    child: Text(
                      state.lastSource.replaceAll('\n', ' '),
                      textDirection: dirOf(state.lastSource),
                      style: Txt.subhead.copyWith(color: Tokens.textSecondary),
                    ),
                  ),
                  // Paketler yalnız İngilizce→Türkçe çeviride kullanılır; başka dilde paket durumu gösterilmez.
                  if ((settings?.packsApply ?? true) && state.packHits > 0 && state.packGame.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Text(tn('home.pack', state.packHits, [state.packGame]),
                        style: Txt.footnote.copyWith(color: Tokens.success)),
                  ],
                ] else
                  Text(t('home.last.empty'), style: Txt.subhead.copyWith(color: Tokens.textSecondary)),
                if (streaming) ...[
                  const SizedBox(height: 14),
                  // Bölge doğru yerde mi ve yazı okunuyor mu, buradan anlaşılır.
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(color: Tokens.inset, borderRadius: BorderRadius.circular(Tokens.radiusControl)),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(t('home.read'), style: Txt.section.copyWith(fontSize: 11.5)),
                        const SizedBox(height: 3),
                        Text(
                          state.lastRead.isEmpty ? t('home.read.empty') : state.lastRead.replaceAll('\n', ' '),
                          textDirection: state.lastRead.isEmpty ? null : dirOf(state.lastRead),
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                          style: Txt.footnote.copyWith(fontSize: 13.5),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Oyun görüntüsü, üstünde altyazının arandığı bölgenin çerçevesi ve (verildiyse) TV'deki gibi çeviri.
class RegionPreview extends StatelessWidget {
  const RegionPreview({super.key, required this.image, required this.settings, this.subtitle});

  final Uint8List image;
  final AppSettings? settings;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    final s = settings;
    return AspectRatio(
      aspectRatio: 16 / 9,
      child: LayoutBuilder(
        builder: (context, constraints) => Stack(
          fit: StackFit.expand,
          children: [
            Image.memory(image, fit: BoxFit.fill, gaplessPlayback: true),
            // Otomatik algılamada okunan alan uygulamanındır; elle seçilen çerçeve gösterilmez.
            if (s != null && !s.autoRegion)
              Positioned(
                left: s.regionValue('left') * constraints.maxWidth,
                top: s.regionValue('top') * constraints.maxHeight,
                width: (s.regionValue('right') - s.regionValue('left')) * constraints.maxWidth,
                height: (s.regionValue('bottom') - s.regionValue('top')) * constraints.maxHeight,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    border: Border.all(color: Tokens.accent, width: 1.5),
                    borderRadius: BorderRadius.circular(6),
                    color: Tokens.accent.withValues(alpha: 0.10),
                  ),
                ),
              ),
            if (subtitle != null)
              Positioned(
                left: 12,
                right: 12,
                bottom: 8,
                child: Center(
                  child: Glass(
                    blur: true,
                    radius: 10,
                    tint: Colors.black,
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    child: Text(
                      subtitle!,
                      textDirection: dirOf(subtitle!),
                      textAlign: TextAlign.center,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Colors.white, fontSize: 11.5, fontWeight: FontWeight.w600, height: 1.25),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
