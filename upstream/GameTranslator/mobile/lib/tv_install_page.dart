// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'l10n.dart';
import 'main.dart';
import 'tv_install.dart';
import 'ui.dart';

/// TV uygulamasını telefondan kurma sihirbazı: marka → TV'yi bul → geliştirici modu adımları (her ekranda tek iş)
/// → kurulum → TV'de aç. Kurulum Android tarafında yapılır (tvinstall/); bu sayfa yalnız yol gösterir.
class TvInstallPage extends StatefulWidget {
  const TvInstallPage({super.key});

  @override
  State<TvInstallPage> createState() => _TvInstallPageState();
}

enum _Step { brand, find, guide, signin, install, done }

/// Geliştirici modu adımlarından biri. Metinler tablodadır: `[id].title`, `[id].body` ve `[id].diff` ("Menü farklı
/// görünüyorsa" açıklaması). [check]: adım yapılınca TV'de açılan port (canlı denetim).
class _GuideStep {
  const _GuideStep(this.id, {this.check = _Check.none, this.copy, this.showPhone = false});

  final String id;
  final _Check check;

  /// Kopyalanabilecek yazı (ör. web adresi).
  final String? copy;

  /// Telefonun adresi büyük gösterilir (Samsung'da TV'ye yazılacak "Host PC IP").
  final bool showPhone;

  String get title => t('$id.title');
  String get body => t('$id.body');
  String get different => t('$id.diff');
}

/// [hostIp]: Samsung TV'nin bildirdiği geliştirici modu açık ve "Host PC IP" bu telefon.
enum _Check { none, ready, keyServer, hostIp }

const _androidSteps = [
  _GuideStep('tv.android.s1'),
  _GuideStep('tv.android.s2'),
  _GuideStep('tv.android.s3'),
  _GuideStep('tv.android.s4'),
  _GuideStep('tv.android.s5', check: _Check.ready),
];

const _lgSteps = [
  _GuideStep('tv.lg.s1', copy: 'https://webostv.developer.lge.com'),
  _GuideStep('tv.lg.s2'),
  _GuideStep('tv.lg.s3', check: _Check.ready),
  _GuideStep('tv.lg.s4', check: _Check.keyServer),
];

const _samsungSteps = [
  _GuideStep('tv.samsung.s1'),
  _GuideStep('tv.samsung.s2'),
  _GuideStep('tv.samsung.s3', check: _Check.hostIp, showPhone: true),
  _GuideStep('tv.samsung.s4', check: _Check.ready),
];

class _TvInstallPageState extends State<TvInstallPage> {
  _Step step = _Step.brand;
  TvKind? kind;
  String? host;
  String tvName = '';
  bool force = false;

  SavedTv? saved;
  Map<TvKind, String> bundled = {};
  String? phone;

  List<FoundTv> found = [];
  bool searching = false;
  bool searched = false;
  final manualController = TextEditingController();
  String? manualError;

  int guideIndex = 0;
  TvCheck? check;
  Timer? poll;
  bool polling = false;

  final passController = TextEditingController();
  bool installing = false;
  String stage = '';
  TvInstallResult? result;
  StreamSubscription<String>? stages;

  /// Samsung girişi: bu oturumda giriş yapılan hesap ve son giriş hatası.
  String? samsungEmail;
  bool signingIn = false;
  ({String title, String fix})? signInError;

  @override
  void initState() {
    super.initState();
    stages = TvBridge.stages.listen((value) {
      if (mounted) setState(() => stage = value);
    });
    () async {
      final values = await Future.wait([TvBridge.saved(), TvBridge.bundled(), TvBridge.phoneAddress()]);
      if (!mounted) return;
      setState(() {
        saved = values[0] as SavedTv?;
        bundled = values[1] as Map<TvKind, String>;
        phone = values[2] as String?;
      });
    }();
  }

  @override
  void dispose() {
    poll?.cancel();
    stages?.cancel();
    if (installing || searching) TvBridge.cancel();
    manualController.dispose();
    passController.dispose();
    super.dispose();
  }

  List<_GuideStep> get guideSteps => switch (kind) {
        TvKind.android => _androidSteps,
        // Anahtarı kayıtlı TV'de Key Server adımı gerekmez.
        TvKind.lg => check?.hasKey == true ? _lgSteps.sublist(0, 3) : _lgSteps,
        TvKind.samsung => _samsungSteps,
        _ => const [],
      };

  bool get samsung => kind == TvKind.samsung;

  /// Samsung: bu TV'ye giriş yapmadan kurulabilir mi (sertifika kayıtlı ya da bu oturumda giriş yapıldı)?
  bool get samsungSignedIn => check?.hasKey == true || check?.signedIn == true || samsungEmail != null;

  /// Samsung'da bağlantı hazırsa sıradaki ekran: giriş gerekiyorsa giriş, yoksa kurulum.
  _Step get samsungNext => samsungSignedIn ? _Step.install : _Step.signin;

  // ------------------------------------------------------------------------------------------- gezinme

  void go(_Step next) {
    poll?.cancel();
    setState(() {
      step = next;
      if (next != _Step.install) result = null;
    });
    if (next == _Step.find) search();
    if (next == _Step.guide) startPolling();
  }

  void chooseBrand(TvKind value) {
    setState(() {
      kind = value;
      host = null;
      found = [];
      searched = false;
      check = null;
      guideIndex = 0;
      force = false;
      signInError = null;
    });
    go(_Step.find);
  }

  /// Kayıtlı TV'ye doğrudan kurulum (güncelleme ya da yeniden kurulum).
  Future<void> reinstallSaved(SavedTv tv) async {
    setState(() {
      kind = tv.kind;
      host = tv.host;
      tvName = tv.name;
      force = true;
      guideIndex = 0;
    });
    final status = await TvBridge.check(tv.kind, tv.host);
    if (!mounted) return;
    setState(() => check = status);
    if (tv.kind == TvKind.samsung) {
      if (!status.ready) {
        setState(() => guideIndex = status.developerMode == true && status.hostIpMatches == true ? 3 : 0);
        go(_Step.guide);
      } else if (samsungNext == _Step.install) {
        go(_Step.install);
        install();
      } else {
        go(_Step.signin);
      }
      return;
    }
    if (status.ready && (tv.kind != TvKind.lg || status.hasKey)) {
      go(_Step.install);
      install();
    } else {
      go(_Step.guide);
    }
  }

  void back() {
    switch (step) {
      case _Step.brand:
        Navigator.of(context).pop();
      case _Step.find:
        go(_Step.brand);
      case _Step.signin:
        if (signingIn) return;
        setState(() => guideIndex = guideSteps.length - 1);
        go(_Step.guide);
      case _Step.guide:
        if (guideIndex > 0) {
          setState(() => guideIndex--);
          startPolling();
        } else {
          go(_Step.find);
        }
      case _Step.install:
        if (installing) {
          TvBridge.cancel();
          return;
        }
        setState(() => guideIndex = guideSteps.length - 1);
        go(_Step.guide);
      case _Step.done:
        Navigator.of(context).pop();
    }
  }

  // ------------------------------------------------------------------------------------------- arama

  Future<void> search() async {
    final current = kind;
    if (current == null || searching) return;
    setState(() {
      searching = true;
      manualError = null;
    });
    final list = await TvBridge.find(current);
    if (!mounted || kind != current) return;
    setState(() {
      searching = false;
      searched = true;
      found = list;
    });
  }

  Future<void> useManual() async {
    final address = await TvBridge.normalize(manualController.text);
    if (!mounted) return;
    if (address == null) {
      setState(() => manualError = t('tv.manual.invalid'));
      return;
    }
    FocusScope.of(context).unfocus();
    selectTv(address, kind?.title ?? 'TV');
  }

  Future<void> selectTv(String address, String name) async {
    setState(() {
      host = address;
      tvName = name;
      guideIndex = 0;
      manualError = null;
    });
    final status = await TvBridge.check(kind!, address);
    if (!mounted) return;
    setState(() => check = status);
    if (samsung) {
      // Telefon zaten kabul ediliyorsa adımlar atlanır; Host PC IP doğruysa yalnız yeniden başlatma kalmıştır.
      if (status.ready) {
        go(samsungNext);
      } else {
        guideIndex = status.developerMode == true && status.hostIpMatches == true ? 3 : 0;
        go(_Step.guide);
      }
      return;
    }
    // Geliştirici modu zaten açıksa adımlar atlanır.
    if (status.ready && (kind == TvKind.android || status.hasKey)) {
      go(_Step.install);
    } else {
      if (status.ready && kind == TvKind.lg) guideIndex = 3; // yalnız Key Server kaldı
      go(_Step.guide);
    }
  }

  // ------------------------------------------------------------------------------------------- canlı denetim

  void startPolling() {
    poll?.cancel();
    final current = guideSteps.elementAtOrNull(guideIndex);
    if (current == null || current.check == _Check.none) return;
    poll = Timer.periodic(const Duration(seconds: 3), (_) => probe());
    probe();
  }

  Future<void> probe() async {
    final current = kind;
    if (polling || current == null) return;
    polling = true;
    try {
      var address = host;
      if (address == null && current == TvKind.android) {
        // TV hata ayıklama açılmadan ağda görünmez: açılınca burada bulunur.
        final list = await TvBridge.find(current);
        if (list.isNotEmpty) {
          address = list.first.host;
          if (mounted) setState(() => host = address);
        }
      }
      if (address == null) return;
      final status = await TvBridge.check(current, address);
      if (mounted) setState(() => check = status);
    } finally {
      polling = false;
    }
  }

  bool stepDone(_GuideStep s) => switch (s.check) {
        _Check.none => false,
        _Check.ready => check?.ready == true,
        _Check.keyServer => check?.keyServer == true,
        _Check.hostIp => check?.ready == true || (check?.developerMode == true && check?.hostIpMatches == true),
      };

  void nextGuide() {
    if (guideIndex + 1 < guideSteps.length) {
      setState(() => guideIndex++);
      startPolling();
    } else if (samsung && check?.ready == true) {
      go(samsungNext);
    } else {
      // Samsung'da bağlantı henüz doğrulanmadıysa kurulum kendisi dener; sertifika gerekirse giriş ekranına geçilir.
      go(_Step.install);
    }
  }

  /// Samsung canlı denetiminde beklerken gösterilen durum.
  String waitingText(_GuideStep s) {
    final c = check;
    if (host == null) return t('tv.wait.searching');
    if (!samsung || c == null) return t('tv.wait.tv', [host]);
    if (s.check == _Check.hostIp || s.check == _Check.ready) {
      if (c.developerMode == false) return t('tv.wait.devmode_off');
      if (c.hostIpMatches == false) {
        return t('tv.wait.host_ip', [c.developerIp ?? '?', phone ?? t('tv.wait.this_phone')]);
      }
      if (s.check == _Check.ready) return t('tv.wait.restart', [host]);
    }
    return t('tv.wait.tv', [host]);
  }

  // ------------------------------------------------------------------------------------------- kurulum

  bool get needsPassphrase => kind == TvKind.lg && check?.hasKey != true;

  Future<void> install() async {
    final current = kind;
    final address = host;
    if (current == null || address == null || installing) return;
    final pass = passController.text.trim();
    if (current == TvKind.lg && needsPassphrase && pass.isEmpty) {
      setState(() => result = TvInstallResult(ok: false, error: 'WRONG_PASSPHRASE', detail: t('tv.install.pass_empty')));
      return;
    }
    FocusScope.of(context).unfocus();
    setState(() {
      installing = true;
      result = null;
      stage = 'connecting';
    });
    final outcome = await TvBridge.install(current, address, name: tvName, passphrase: needsPassphrase ? pass : null, force: force);
    if (!mounted) return;
    if (current == TvKind.samsung && outcome.error == 'SIGN_IN_NEEDED') {
      // Bu TV'nin sertifikası yok: giriş ekranına geçilir, giriş bitince kurulum kendiliğinden sürer.
      setState(() {
        installing = false;
        check = TvCheck(true, false, false, signedIn: false, developerMode: check?.developerMode, hostIpMatches: check?.hostIpMatches);
        samsungEmail = null;
      });
      go(_Step.signin);
      return;
    }
    setState(() {
      if (outcome.error == 'CERTIFICATE_FAILED') samsungEmail = null;
      installing = false;
      result = outcome;
      if (outcome.ok) step = _Step.done;
      // Kayıtlı anahtar geçmediyse parola yeniden sorulur.
      if (outcome.error == 'KEY_REFUSED' || outcome.error == 'WRONG_PASSPHRASE') check = TvCheck(check?.ready ?? false, check?.keyServer ?? false, false);
    });
    if (outcome.ok) TvBridge.saved().then((value) => mounted ? setState(() => saved = value) : null);
  }

  /// Samsung girişi: telefonda geri dönüş dinleyicisi açılır, Samsung'un sayfası uygulama içinde açılır. Giriş bitince
  /// kurulum ekranına geçilip kurulum başlar.
  Future<void> signInSamsung() async {
    if (signingIn) return;
    setState(() {
      signingIn = true;
      signInError = null;
    });
    final url = await TvBridge.samsungSignIn();
    if (!mounted) return;
    if (url == null) {
      setState(() {
        signingIn = false;
        signInError = (
          title: t('tv.signin.start_failed.title'),
          fix: t('tv.signin.start_failed.fix'),
        );
      });
      return;
    }
    final wait = TvBridge.samsungSignInWait();
    await Navigator.of(context).push<void>(appleRoute<void>((_) => SamsungSignInPage(url: url, wait: wait)));
    final outcome = await wait;
    if (!mounted) return;
    setState(() {
      signingIn = false;
      if (outcome.ok) {
        samsungEmail = outcome.email ?? '';
      } else {
        signInError = tvErrorText(outcome.error ?? 'SIGN_IN_FAILED', TvKind.samsung);
      }
    });
    if (outcome.ok) {
      go(_Step.install);
      install();
    }
  }

  // ------------------------------------------------------------------------------------------- görünüm

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: step == _Step.brand || step == _Step.done,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) back();
      },
      child: GlassScaffold(
        title: t('tv.title'),
        body: AnimatedSwitcher(
          duration: const Duration(milliseconds: 220),
          child: KeyedSubtree(
            key: ValueKey('$step-$guideIndex'),
            child: ListView(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
              children: switch (step) {
                _Step.brand => brandView(),
                _Step.find => findView(),
                _Step.guide => guideView(),
                _Step.signin => signinView(),
                _Step.install => installView(),
                _Step.done => doneView(),
              },
            ),
          ),
        ),
      ),
    );
  }

  /// Adım sayısı: Samsung'da kurulumdan önce Samsung girişi de var.
  int get totalSteps => samsung ? 5 : 4;

  Widget progress(int index, String title) => Padding(
        padding: const EdgeInsets.fromLTRB(4, 0, 4, 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                for (var i = 0; i < totalSteps; i++)
                  Expanded(
                    child: Container(
                      height: 4,
                      margin: const EdgeInsets.only(right: 4),
                      decoration: BoxDecoration(
                        color: i <= index ? Tokens.accent : Tokens.surfaceHigh,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            Text(t('tv.step_of', [index + 1, totalSteps]), style: Txt.footnote.copyWith(color: Tokens.textSecondary, fontWeight: FontWeight.w600)),
            const SizedBox(height: 2),
            Text(title, style: Txt.title2),
          ],
        ),
      );

  // ----- 1. marka

  List<Widget> brandView() {
    final tv = saved;
    return [
      progress(0, t('tv.brand.title')),
      Padding(
        padding: const EdgeInsets.fromLTRB(4, 0, 4, 6),
        child: Text(t('tv.brand.intro'), style: Txt.subhead.copyWith(color: Tokens.textSecondary)),
      ),
      if (tv != null) ...[
        GroupHeader(t('tv.brand.saved')),
        Group(
          inset: 60,
          children: [
            Cell(
              icon: CupertinoIcons.tv_fill,
              iconColor: Tokens.success,
              title: tv.name.isEmpty ? tv.kind.title : tv.name,
              subtitle: t('tv.brand.saved_sub', [tv.host, tv.version ?? '?', bundled[tv.kind] ?? '?']),
              trailing: tv.version != null && bundled[tv.kind] != null && _newer(bundled[tv.kind]!, tv.version!)
                  ? Pill(text: t('tv.brand.update_available'), color: Tokens.warning)
                  : null,
            ),
            Cell(
              title: tv.version != null && bundled[tv.kind] != null && _newer(bundled[tv.kind]!, tv.version!) ? t('tv.brand.update') : t('tv.brand.reinstall'),
              titleColor: Tokens.accent,
              onTap: () => reinstallSaved(tv),
            ),
          ],
        ),
      ],
      GroupHeader(t('tv.brand.header')),
      Group(
        inset: 60,
        children: [
          Cell(
            icon: CupertinoIcons.tv,
            iconColor: Tokens.success,
            title: 'Android TV / Google TV',
            subtitle: t('tv.brand.android_sub'),
            chevron: true,
            onTap: () => chooseBrand(TvKind.android),
          ),
          Cell(
            icon: CupertinoIcons.tv,
            iconColor: Tokens.danger,
            title: 'LG',
            subtitle: t('tv.brand.lg_sub'),
            chevron: true,
            onTap: () => chooseBrand(TvKind.lg),
          ),
          Cell(
            icon: CupertinoIcons.tv,
            iconColor: Tokens.accent,
            title: 'Samsung',
            subtitle: t('tv.brand.samsung_sub'),
            chevron: true,
            onTap: () => chooseBrand(TvKind.samsung),
          ),
        ],
      ),
      GroupFooter(t('tv.brand.footer')),
    ];
  }

  // ----- Samsung: hesapla giriş (kurulumdan önce, TV başına bir kez)

  List<Widget> signinView() {
    final error = signInError;
    return [
      progress(3, t('tv.signin.title')),
      Glass(
        padding: const EdgeInsets.fromLTRB(18, 18, 18, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(t('tv.signin.why'), style: Txt.title3),
            const SizedBox(height: 10),
            Text(
              t('tv.signin.why_body'),
              style: Txt.callout,
            ),
            const SizedBox(height: 14),
            _Numbered(1, t('tv.signin.p1')),
            _Numbered(2, t('tv.signin.p2')),
            _Numbered(3, t('tv.signin.p3')),
          ],
        ),
      ),
      if (error != null) ...[
        const SizedBox(height: 14),
        Notice(icon: CupertinoIcons.exclamationmark_triangle_fill, color: Tokens.danger, title: error.title, text: error.fix),
      ],
      const SizedBox(height: 18),
      GlowButton(
        icon: CupertinoIcons.person_crop_circle_fill,
        label: signingIn ? t('tv.signin.waiting') : t('tv.signin.button'),
        onPressed: signingIn ? null : signInSamsung,
      ),
      const SizedBox(height: 10),
      OutlinedButton(
        onPressed: signingIn
            ? null
            : () {
                setState(() => guideIndex = guideSteps.length - 1);
                go(_Step.guide);
              },
        child: Text(t('tv.back_to_steps')),
      ),
    ];
  }

  // ----- 2. TV'yi bul

  List<Widget> findView() {
    final current = kind!;
    final android = current == TvKind.android;
    return [
      progress(1, t('tv.find.title')),
      Padding(
        padding: const EdgeInsets.fromLTRB(4, 0, 4, 4),
        child: Text(
          android
              ? t('tv.find.intro_android')
              : t('tv.find.intro', [current.title]),
          style: Txt.subhead.copyWith(color: Tokens.textSecondary),
        ),
      ),
      GroupHeader(searching ? t('tv.find.searching') : t('tv.find.found')),
      Group(
        inset: 60,
        children: [
          if (searching)
            Cell(title: t('tv.find.scanning'), trailing: const CupertinoActivityIndicator())
          else if (found.isEmpty)
            Cell(title: searched ? t('tv.find.none') : t('tv.find.not_yet'), subtitle: searched ? t('tv.find.none_sub') : null),
          for (final tv in found)
            Cell(
              icon: CupertinoIcons.tv_fill,
              iconColor: tv.ready ? Tokens.success : Tokens.gray,
              title: tv.name,
              subtitle: tv.host,
              trailing: tv.ready ? Pill(text: t('tv.find.ready'), color: Tokens.success) : null,
              chevron: true,
              onTap: () => selectTv(tv.host, tv.name),
            ),
          Cell(title: t('tv.find.refresh'), titleColor: Tokens.accent, onTap: searching ? null : search),
        ],
      ),
      GroupHeader(t('tv.find.manual')),
      Glass(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: manualController,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: InputDecoration(hintText: t('tv.find.manual_hint'), errorText: manualError),
              onSubmitted: (_) => useManual(),
            ),
            const SizedBox(height: 10),
            OutlinedButton(onPressed: useManual, child: Text(t('tv.find.use_address'))),
          ],
        ),
      ),
      GroupFooter(t(android
              ? 'tv.find.where_android'
              : current == TvKind.samsung
                  ? 'tv.find.where_samsung'
                  : 'tv.find.where_lg') +
          (phone == null ? '' : t('tv.find.phone_address', [phone]))),
      if (android) ...[
        const SizedBox(height: 16),
        GlowButton(
          label: t('tv.find.show_steps'),
          icon: CupertinoIcons.list_number,
          onPressed: () {
            setState(() => guideIndex = 0);
            go(_Step.guide);
          },
        ),
      ],
    ];
  }

  // ----- 3. geliştirici modu

  List<Widget> guideView() {
    final steps = guideSteps;
    final index = guideIndex.clamp(0, steps.length - 1);
    final s = steps[index];
    final done = stepDone(s);
    final waiting = s.check != _Check.none && !done;
    return [
      progress(2, kind == TvKind.android ? t('tv.guide.title_android') : t('tv.guide.title')),
      Glass(
        padding: const EdgeInsets.fromLTRB(18, 18, 18, 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 34,
                  height: 34,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(color: done ? Tokens.success : Tokens.accent, shape: BoxShape.circle),
                  child: done
                      ? const Icon(CupertinoIcons.checkmark_alt, size: 20, color: Colors.white)
                      : Text('${index + 1}', style: Txt.headline.copyWith(color: Colors.white)),
                ),
                const SizedBox(width: 12),
                Expanded(child: Text(s.title, style: Txt.title3)),
              ],
            ),
            const SizedBox(height: 4),
            Padding(
              padding: const EdgeInsets.only(left: 46),
              child: Text(t('tv.guide.step_of', [index + 1, steps.length]), style: Txt.footnote.copyWith(color: Tokens.textSecondary)),
            ),
            const SizedBox(height: 14),
            Text(s.body, style: Txt.callout),
            if (s.showPhone) ...[
              const SizedBox(height: 14),
              Glass(
                radius: 16,
                padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('HOST PC IP', style: Txt.footnote.copyWith(color: Tokens.textSecondary, fontWeight: FontWeight.w600)),
                          const SizedBox(height: 2),
                          Text(
                            phone ?? t('tv.guide.no_wifi'),
                            style: phone == null
                                ? Txt.headline.copyWith(color: Tokens.danger)
                                : Txt.largeTitle,
                          ),
                        ],
                      ),
                    ),
                    if (phone != null)
                      IconButton(
                        tooltip: t('tv.copy'),
                        icon: const Icon(CupertinoIcons.doc_on_clipboard, color: Tokens.accent),
                        onPressed: () {
                          Clipboard.setData(ClipboardData(text: phone!));
                          toast(context, t('tv.copied'));
                        },
                      ),
                  ],
                ),
              ),
            ],
            if (s.copy != null) ...[
              const SizedBox(height: 12),
              Glass(
                radius: 14,
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                child: Row(
                  children: [
                    Expanded(child: SelectableText(s.copy!, style: Txt.subhead.copyWith(fontWeight: FontWeight.w600))),
                    IconButton(
                      tooltip: t('tv.copy'),
                      icon: const Icon(CupertinoIcons.doc_on_clipboard, color: Tokens.accent),
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: s.copy!));
                        toast(context, t('tv.copied'));
                      },
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
      if (s.check != _Check.none) ...[
        const SizedBox(height: 12),
        done
            ? Notice(
                icon: CupertinoIcons.checkmark_circle_fill,
                color: Tokens.success,
                compact: true,
                text: switch (s.check) {
                  _Check.keyServer => t('tv.guide.ok_key_server'),
                  _Check.hostIp => t('tv.guide.ok_host_ip'),
                  _ => samsung ? t('tv.guide.ok_samsung') : t('tv.guide.ok_ready'),
                },
              )
            : Glass(
                radius: 18,
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                child: Row(
                  children: [
                    const CupertinoActivityIndicator(),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(waitingText(s), style: Txt.subhead.copyWith(color: Tokens.textSecondary)),
                    ),
                  ],
                ),
              ),
      ],
      const SizedBox(height: 12),
      _Expandable(title: t('tv.guide.different'), text: s.different),
      const SizedBox(height: 18),
      GlowButton(
        label: waiting || index + 1 < steps.length ? t('tv.guide.done_this') : t('tv.guide.to_install'),
        icon: CupertinoIcons.checkmark_alt,
        // Canlı denetimde TV henüz görünmüyorsa yine de geçilebilir: bazı ağlar denetimi engeller, kurulum kendisi dener.
        onPressed: (s.check == _Check.ready && host == null && waiting) ? null : nextGuide,
      ),
      if (s.check == _Check.ready && host == null && waiting)
        GroupFooter(t('tv.guide.waiting_footer')),
    ];
  }

  // ----- 4. kurulum

  List<Widget> installView() {
    final current = kind!;
    final failure = result != null && !result!.ok ? result : null;
    final text = failure == null ? null : tvErrorText(failure.error, current);
    final old = current == TvKind.samsung && (check?.year ?? 9999) < 2017;
    return [
      progress(totalSteps - 1, t('tv.install.title')),
      Group(
        inset: 60,
        children: [
          Cell(icon: CupertinoIcons.tv_fill, iconColor: Tokens.purple, title: tvName.isEmpty ? current.title : tvName, subtitle: host),
          Cell(icon: CupertinoIcons.cube_box_fill, iconColor: Tokens.indigo, title: t('tv.install.version'), value: bundled[current] ?? '?'),
          if (current == TvKind.samsung && samsungEmail != null && samsungEmail!.isNotEmpty)
            Cell(icon: CupertinoIcons.person_crop_circle_fill, iconColor: Tokens.accent, title: t('tv.install.samsung_account'), value: samsungEmail),
        ],
      ),
      if (old && failure == null)
        Padding(
          padding: const EdgeInsets.only(top: 14),
          child: Notice(
            icon: CupertinoIcons.exclamationmark_circle_fill,
            color: Tokens.warning,
            title: t('tv.install.old.title', [check!.year]),
            text: t('tv.install.old.text'),
          ),
        ),
      if (current == TvKind.lg && needsPassphrase) ...[
        GroupHeader(t('tv.install.pass_header')),
        Glass(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: passController,
                enabled: !installing,
                textCapitalization: TextCapitalization.characters,
                autocorrect: false,
                maxLength: 10,
                style: Txt.title2.copyWith(letterSpacing: 6),
                textAlign: TextAlign.center,
                decoration: InputDecoration(hintText: t('tv.install.pass_hint'), counterText: ''),
                onSubmitted: (_) => install(),
              ),
            ],
          ),
        ),
        GroupFooter(t('tv.install.pass_footer')),
      ],
      if (current == TvKind.android && !installing && failure == null)
        Padding(
          padding: const EdgeInsets.only(top: 14),
          child: Notice(
            icon: CupertinoIcons.hand_raised_fill,
            title: t('tv.install.prompt.title'),
            text: t('tv.install.prompt.text'),
          ),
        ),
      if (installing) ...[
        const SizedBox(height: 14),
        Glass(
          padding: const EdgeInsets.all(18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const CupertinoActivityIndicator(),
                  const SizedBox(width: 12),
                  Expanded(child: Text(tvStageText(stage), style: Txt.headline)),
                ],
              ),
              if (stage.startsWith('uploading:')) ...[
                const SizedBox(height: 12),
                LinearProgressIndicator(value: (int.tryParse(stage.substring(10)) ?? 0) / 100, borderRadius: BorderRadius.circular(4)),
              ],
            ],
          ),
        ),
        if (stage == 'approve') ...[
          const SizedBox(height: 12),
          Notice(
            icon: CupertinoIcons.tv,
            color: Tokens.warning,
            title: t('tv.install.look.title'),
            text: t('tv.install.look.text'),
          ),
        ],
        const SizedBox(height: 10),
        GroupFooter(t('tv.install.keep_on')),
      ],
      if (failure != null) ...[
        const SizedBox(height: 14),
        Notice(icon: CupertinoIcons.exclamationmark_triangle_fill, color: Tokens.danger, title: text!.title, text: text.fix),
        if ((failure.detail ?? '').isNotEmpty && failure.error != 'CANCELLED') ...[
          const SizedBox(height: 10),
          _Expandable(title: t('tv.install.said'), text: failure.detail!),
        ],
      ],
      const SizedBox(height: 18),
      if (installing)
        OutlinedButton(onPressed: TvBridge.cancel, child: Text(t('tv.install.stop')))
      else ...[
        GlowButton(
          icon: failure == null ? CupertinoIcons.arrow_down_circle_fill : CupertinoIcons.arrow_clockwise,
          label: failure == null ? t('tv.install.go') : t('tv.install.retry'),
          onPressed: install,
        ),
        if (failure != null && current == TvKind.samsung && (failure.error == 'CERTIFICATE_FAILED' || failure.error == 'SIGN_IN_FAILED')) ...[
          const SizedBox(height: 10),
          OutlinedButton(onPressed: () => go(_Step.signin), child: Text(t('tv.install.sign_in_again'))),
        ],
        if (failure != null) ...[
          const SizedBox(height: 10),
          OutlinedButton(
            onPressed: () {
              setState(() => guideIndex = switch (failure.error) {
                    'KEY_SERVER_OFF' => guideSteps.length - 1,
                    // Samsung: "Host PC IP" adımı
                    'WRONG_HOST_IP' => 2,
                    _ => 0,
                  });
              go(_Step.guide);
            },
            child: Text(t('tv.back_to_steps')),
          ),
        ],
      ],
    ];
  }

  // ----- 5. tamam

  List<Widget> doneView() {
    final r = result!;
    return [
      const SizedBox(height: 24),
      const Center(child: Icon(CupertinoIcons.checkmark_seal_fill, size: 84, color: Tokens.success)),
      const SizedBox(height: 16),
      Center(child: Text(r.installed ? t('tv.done.installed') : t('tv.done.current'), style: Txt.title2, textAlign: TextAlign.center)),
      const SizedBox(height: 6),
      Center(child: Text(t('tv.done.version', [r.version ?? '?']), style: Txt.subhead.copyWith(color: Tokens.textSecondary))),
      const SizedBox(height: 22),
      Notice(
        icon: CupertinoIcons.tv_fill,
        color: r.launched ? Tokens.success : Tokens.accent,
        title: r.launched ? t('tv.done.launched.title') : t('tv.done.open.title'),
        text: r.launched
            ? t('tv.done.launched.text')
            : t('tv.done.open.text'),
      ),
      if (kind == TvKind.lg) ...[
        const SizedBox(height: 12),
        Notice(
          icon: CupertinoIcons.clock_fill,
          color: Tokens.warning,
          title: t('tv.done.lg.title'),
          text: (r.hoursLeft != null ? tn('tv.done.lg.hours', r.hoursLeft!) : '') + t('tv.done.lg.text'),
        ),
      ],
      if (kind == TvKind.android) ...[
        const SizedBox(height: 12),
        Notice(icon: CupertinoIcons.info_circle_fill, text: t('tv.done.android')),
      ],
      if (kind == TvKind.samsung) ...[
        const SizedBox(height: 12),
        Notice(icon: CupertinoIcons.info_circle_fill, text: t('tv.done.samsung')),
      ],
      const SizedBox(height: 22),
      GlowButton(label: t('tv.done.finish'), onPressed: () => Navigator.of(context).pop()),
    ];
  }
}

bool _newer(String a, String b) => isNewerVersion(a, b);

class _Numbered extends StatelessWidget {
  const _Numbered(this.number, this.text);

  final int number;
  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 24,
              height: 24,
              alignment: Alignment.center,
              decoration: const BoxDecoration(color: Tokens.accentSoft, shape: BoxShape.circle),
              child: Text('$number', style: Txt.footnote.copyWith(fontWeight: FontWeight.w700, color: Tokens.accent)),
            ),
            const SizedBox(width: 10),
            Expanded(child: Text(text, style: Txt.subhead)),
          ],
        ),
      );
}

/// Dokununca açılan ek açıklama.
class _Expandable extends StatefulWidget {
  const _Expandable({required this.title, required this.text});

  final String title;
  final String text;

  @override
  State<_Expandable> createState() => _ExpandableState();
}

class _ExpandableState extends State<_Expandable> {
  bool open = false;

  @override
  Widget build(BuildContext context) => Glass(
        radius: 18,
        clip: true,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Cell(
              icon: CupertinoIcons.question_circle_fill,
              iconColor: Tokens.gray,
              title: widget.title,
              trailing: Icon(open ? CupertinoIcons.chevron_up : CupertinoIcons.chevron_down, size: 16, color: Tokens.textSecondary),
              onTap: () => setState(() => open = !open),
            ),
            if (open)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
                child: SelectableText(widget.text, style: Txt.subhead.copyWith(color: const Color(0xD9EBEBF5))),
              ),
          ],
        ),
      );
}

/// Samsung'un giriş sayfası uygulama içinde. Giriş bitince sayfa jetonu telefondaki dinleyiciye gönderir
/// (tvinstall/SamsungLogin.kt); [wait] tamamlanınca sayfa kendiliğinden kapanır. Kapatılırsa giriş iptal edilir.
class SamsungSignInPage extends StatefulWidget {
  const SamsungSignInPage({super.key, required this.url, required this.wait});

  final String url;
  final Future<({bool ok, String? email, String? error, String? detail})> wait;

  @override
  State<SamsungSignInPage> createState() => _SamsungSignInPageState();
}

class _SamsungSignInPageState extends State<SamsungSignInPage> {
  late final WebViewController controller;
  int progress = 0;
  bool finished = false;

  @override
  void initState() {
    super.initState();
    controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.black)
      ..setNavigationDelegate(NavigationDelegate(
        onProgress: (value) {
          if (mounted) setState(() => progress = value);
        },
      ))
      ..loadRequest(Uri.parse(widget.url));
    widget.wait.then((_) {
      finished = true;
      if (mounted) Navigator.of(context).maybePop();
    });
  }

  @override
  void dispose() {
    // Kullanıcı sayfayı kapattıysa bekleyen giriş iptal edilir.
    if (!finished) TvBridge.samsungSignInAbort();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return GlassScaffold(
      appBar: GlassScaffold.navBar(
        context,
        t('tv.web.title'),
        actions: [
          PopupMenuButton<String>(
            color: Tokens.sheet,
            onSelected: (value) async {
              if (value == 'browser') {
                final opened = await TvBridge.openUrl(widget.url);
                if (context.mounted) {
                  toast(context, opened ? t('tv.web.use_browser') : t('tv.web.browser_failed'));
                }
              } else {
                controller.loadRequest(Uri.parse(widget.url));
              }
            },
            itemBuilder: (context) => [
              PopupMenuItem(value: 'reload', child: Text(t('tv.web.reload'))),
              PopupMenuItem(value: 'browser', child: Text(t('tv.web.open_browser'))),
            ],
          ),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(2),
          child: progress < 100
              ? LinearProgressIndicator(value: progress / 100, minHeight: 2, backgroundColor: Colors.transparent)
              : const SizedBox(height: 2),
        ),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
            child: Notice(icon: CupertinoIcons.lock_fill, compact: true, text: t('tv.web.notice')),
          ),
          Expanded(child: WebViewWidget(controller: controller)),
        ],
      ),
    );
  }
}
