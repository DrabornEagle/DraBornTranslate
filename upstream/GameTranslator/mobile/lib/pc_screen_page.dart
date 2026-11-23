// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'bridge.dart';
import 'l10n.dart';
import 'main.dart';
import 'screen_page.dart';
import 'ui.dart';

/// "Bilgisayarın altyazısını göster": çeviriyi bilgisayardaki GameTranslator yapar, telefon yalnızca ekrandır.
/// Aynı Wi-Fi'deki sunucu bulunur (çeviri yapan öncelikli) ve onun /ekran sayfası tam ekran açılır. Telefonda konsol
/// kurulumu gerekmez. Sayfa, telefonun kendi adresindeki köprü üzerinden açılır (bkz. LoopbackRelay.kt); istekler
/// yalnızca yerel ağdaki o bilgisayara gider.
class PcScreenPage extends StatefulWidget {
  const PcScreenPage({super.key});

  @override
  State<PcScreenPage> createState() => _PcScreenPageState();
}

enum _Stage { searching, missing, showing }

class _PcScreenPageState extends State<PcScreenPage> {
  /// Sayfadaki düğmelerle aynı süre: çıkış düğmesi bu kadar sonra solar, ekrana dokununca belirginleşir.
  static const controlsFor = Duration(milliseconds: 4500);

  final addressController = TextEditingController();
  _Stage stage = _Stage.searching;
  WebViewController? web;

  /// Bulunamadıysa gösterilen başlık ve açıklama.
  (String, String) problem = ('', '');
  String? addressError;
  bool connecting = false;
  bool screenMode = false;
  bool controls = true;
  Timer? controlsTimer;

  /// Yarıda kalan aramanın sonucu, yenisi başladıysa yok sayılır.
  int attempt = 0;

  @override
  void initState() {
    super.initState();
    search();
  }

  @override
  void dispose() {
    attempt++;
    controlsTimer?.cancel();
    addressController.dispose();
    Bridge.closeDisplayRelay();
    if (screenMode) leaveScreenMode();
    super.dispose();
  }

  Future<void> search() async {
    final current = ++attempt;
    setState(() {
      stage = _Stage.searching;
      addressError = null;
    });
    final DisplaySearch found;
    try {
      found = await Bridge.findDisplayServer();
    } catch (_) {
      if (mounted && current == attempt) fail(t('pcscreen.err.search.title'), t('pcscreen.err.search.text'));
      return;
    }
    if (!mounted || current != attempt) return;
    final server = found.server;
    if (server != null) return open(server);
    if (found.wifi) {
      fail(t('pcscreen.err.notfound.title'), t('pcscreen.err.notfound.text'));
    } else {
      fail(t('pcscreen.err.wifi.title'), t('pcscreen.err.wifi.text'));
    }
  }

  void fail(String title, String text) {
    if (screenMode) {
      screenMode = false;
      leaveScreenMode();
    }
    setState(() {
      stage = _Stage.missing;
      problem = (title, text);
      connecting = false;
      web = null;
    });
  }

  /// Elle yazılan adres: önce orada GameTranslator var mı bakılır.
  Future<void> connectManually() async {
    final text = addressController.text.trim();
    if (text.isEmpty || connecting) return;
    FocusScope.of(context).unfocus();
    final current = ++attempt;
    setState(() {
      connecting = true;
      addressError = null;
    });
    DisplayServer? server;
    String? error;
    try {
      server = await Bridge.probeDisplayServer(text);
      if (server == null) error = t('pcscreen.err.none');
    } on FormatException {
      error = t('pcscreen.err.format');
    } catch (_) {
      error = t('pcscreen.err.probe');
    }
    if (!mounted || current != attempt) return;
    if (server == null) {
      setState(() {
        connecting = false;
        addressError = error;
      });
      return;
    }
    await open(server);
  }

  /// Köprüyü açar ve bilgisayarın /ekran sayfasını tam ekran yükler.
  Future<void> open(DisplayServer server) async {
    final current = attempt;
    final int port;
    try {
      port = await Bridge.openDisplayRelay(server);
    } catch (_) {
      if (mounted && current == attempt) fail(t('pcscreen.err.connect.title'), t('pcscreen.err.connect.retry'));
      return;
    }
    if (!mounted || current != attempt) return;
    final controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.black)
      // Sayfaya dokunulunca (sayfa kendi düğmelerini gösterir) çıkış düğmesi de belirginleşir.
      ..addJavaScriptChannel('GtApp', onMessageReceived: (_) => wake())
      ..setNavigationDelegate(NavigationDelegate(
        // Sayfa yalnız köprüden açılır; başka bir adrese gidilmez.
        onNavigationRequest: (request) =>
            request.url.startsWith('http://127.0.0.1:$port/') ? NavigationDecision.navigate : NavigationDecision.prevent,
        onPageFinished: (_) => web?.runJavaScript(pageHook),
        onWebResourceError: (error) {
          if (error.isForMainFrame != true || !mounted || current != attempt) return;
          fail(t('pcscreen.err.connect.title'), t('pcscreen.err.connect.text', [server.host]));
        },
      ));
    await enterScreenMode();
    if (!mounted || current != attempt) return leaveScreenMode();
    screenMode = true;
    setState(() {
      web = controller;
      stage = _Stage.showing;
      connecting = false;
    });
    wake();
    await controller.loadRequest(Uri.parse('http://127.0.0.1:$port/ekran'));
  }

  /// Sayfa dosyası değişmez; yalnızca uygulama içinde açılınca: dokunuşlar uygulamaya bildirilir ve "Tam ekran" düğmesi
  /// gizlenir (uygulama sayfayı zaten tam ekran gösterir).
  static const pageHook = '''
(function () {
  if (window.gtAppHooked) return;
  window.gtAppHooked = true;
  var full = document.getElementById('full');
  if (full) full.style.display = 'none';
  document.body.addEventListener('click', function () { GtApp.postMessage('tap'); });
})();
''';

  void wake() {
    if (!mounted) return;
    controlsTimer?.cancel();
    if (!controls) setState(() => controls = true);
    controlsTimer = Timer(controlsFor, () {
      if (mounted) setState(() => controls = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    final controller = web;
    if (stage == _Stage.showing && controller != null) {
      return Scaffold(
        backgroundColor: Colors.black,
        body: Stack(
          fit: StackFit.expand,
          children: [
            SafeArea(child: WebViewWidget(controller: controller)),
            // Çıkış düğmesi hiç kaybolmaz, yalnızca solar: sayfanın dokunuşu uygulamaya ulaşmasa da çıkılabilsin.
            SafeArea(
              child: Align(
                alignment: Alignment.topLeft,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(12, 10, 0, 0),
                  child: AnimatedOpacity(
                    duration: const Duration(milliseconds: 250),
                    opacity: controls ? 1 : 0.3,
                    child: GlassIconButton(icon: CupertinoIcons.xmark, tooltip: t('pcscreen.close'), onTap: () => Navigator.of(context).maybePop()),
                  ),
                ),
              ),
            ),
          ],
        ),
      );
    }
    return GlassScaffold(
      title: t('pcscreen.title'),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: stage == _Stage.searching ? searchingView() : missingView(),
      ),
    );
  }

  List<Widget> searchingView() => [
        const SizedBox(height: 80),
        const Center(child: CupertinoActivityIndicator(radius: 14)),
        const SizedBox(height: 18),
        Text(t('pcscreen.searching.title'), textAlign: TextAlign.center, style: Txt.title3),
        const SizedBox(height: 6),
        Text(
          t('pcscreen.searching.text'),
          textAlign: TextAlign.center,
          style: Txt.subhead.copyWith(color: Tokens.textSecondary),
        ),
      ];

  List<Widget> missingView() => [
        Notice(icon: CupertinoIcons.wifi_exclamationmark, color: Tokens.warning, title: problem.$1, text: problem.$2),
        const SizedBox(height: 14),
        GlowButton(onPressed: connecting ? null : search, icon: CupertinoIcons.arrow_clockwise, label: t('pcscreen.retry')),
        GroupHeader(t('pcscreen.manual.header')),
        Glass(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: addressController,
                keyboardType: TextInputType.url,
                autocorrect: false,
                onSubmitted: (_) => connectManually(),
                decoration: InputDecoration(labelText: t('pcscreen.manual.label'), hintText: '192.168.1.20'),
              ),
              if (addressError != null) ...[
                const SizedBox(height: 12),
                Notice(icon: CupertinoIcons.exclamationmark_circle_fill, color: Tokens.danger, text: addressError!),
              ],
              const SizedBox(height: 14),
              OutlinedButton(
                onPressed: connecting ? null : connectManually,
                child: Text(connecting ? t('pcscreen.connecting') : t('pcscreen.connect')),
              ),
            ],
          ),
        ),
        GroupFooter(t('pcscreen.manual.footer')),
      ];
}
