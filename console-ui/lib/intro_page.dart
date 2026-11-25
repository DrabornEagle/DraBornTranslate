// SPDX-License-Identifier: AGPL-3.0-only
import 'package:material_ui/material_ui.dart';

import 'l10n.dart';
import 'language_section.dart';
import 'main.dart';
import 'ui.dart';

/// İlk açılıştaki tanıtım: uygulama ne yapar, nasıl çalışır, başlamadan önce ne gerekir.
/// Ayarlar › Hakkında'dan yeniden açılabilir; o zaman [onDone] yalnızca sayfayı kapatır.
class IntroPage extends StatefulWidget {
  const IntroPage({super.key, required this.onDone, this.replay = false});

  final Future<void> Function() onDone;

  /// Kurulum bittikten sonra yeniden açıldıysa son düğme kuruluma değil geri götürür.
  final bool replay;

  @override
  State<IntroPage> createState() => _IntroPageState();
}

class _IntroPageState extends State<IntroPage> {
  static const pageCount = 3;

  final controller = PageController();
  int page = 0;

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  void next() {
    if (page == pageCount - 1) {
      widget.onDone();
    } else {
      controller.nextPage(duration: const Duration(milliseconds: 320), curve: Curves.easeOutCubic);
    }
  }

  @override
  Widget build(BuildContext context) {
    final last = page == pageCount - 1;
    return GlassScaffold(
      body: SafeArea(
        child: Column(
          children: [
            Row(
              children: [
                // Dil düğmesi ilk ekranda: uygulamanın dilini okuyamayan kullanıcı buradan kendi dilini seçer
                const Padding(padding: EdgeInsets.fromLTRB(16, 6, 0, 0), child: LanguageGlobeButton()),
                const Spacer(),
                Padding(
                padding: const EdgeInsets.fromLTRB(8, 4, 8, 0),
                child: AnimatedOpacity(
                  opacity: last ? 0 : 1,
                  duration: const Duration(milliseconds: 200),
                  child: TextButton(onPressed: last ? null : () => widget.onDone(), child: Text(widget.replay ? t('intro.close') : t('intro.skip'), style: const TextStyle(color: Tokens.textSecondary, fontSize: 17, fontWeight: FontWeight.w500))),
                ),
              ),
              ],
            ),
            Expanded(
              child: PageView(
                controller: controller,
                onPageChanged: (index) => setState(() => page = index),
                children: const [_WelcomePage(), _HowPage(), _NeedsPage()],
              ),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                for (var i = 0; i < pageCount; i++)
                  AnimatedContainer(
                    duration: const Duration(milliseconds: 220),
                    margin: const EdgeInsets.symmetric(horizontal: 6),
                    width: i == page ? 11 : 9,
                    height: i == page ? 11 : 9,
                    decoration: BoxDecoration(
                      color: i == page ? Tokens.accentText : Tokens.border,
                      shape: BoxShape.circle,
                    ),
                  ),
              ],
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 20),
              child: GlowButton(onPressed: next, label: last ? (widget.replay ? t('intro.done') : t('intro.start_setup')) : t('intro.next')),
            ),
          ],
        ),
      ),
    );
  }
}

/// Her tanıtım sayfasının iskeleti: üstte görsel, altta başlık ve açıklama.
class _IntroLayout extends StatelessWidget {
  const _IntroLayout({required this.visual, required this.title, required this.body, this.extra});

  final Widget visual;
  final String title;
  final String body;
  final Widget? extra;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 28),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              visual,
              const SizedBox(height: 34),
              Text(
                title,
                textAlign: TextAlign.center,
                style: Txt.largeTitle,
              ),
              const SizedBox(height: 12),
              Text(
                body,
                textAlign: TextAlign.center,
                style: Txt.body.copyWith(color: Tokens.textSecondary),
              ),
              if (extra != null) ...[const SizedBox(height: 24), extra!],
              const SizedBox(height: 12),
            ],
          ),
        ),
      ),
    );
  }
}

class _WelcomePage extends StatelessWidget {
  const _WelcomePage();

  @override
  Widget build(BuildContext context) {
    return _IntroLayout(
      visual: Column(
        children: [
          const GtLogo(size: 104),
          const SizedBox(height: 14),
          const Text('DraBornTranslate', style: Txt.title2),
          const SizedBox(height: 28),
          const _SceneCard(),
        ],
      ),
      title: t('intro.welcome.title'),
      body: t('intro.welcome.body'),
    );
  }
}

/// Örnek oyun sahnesi: oyunun kendi altyazısı ve üstünde çevirisi, TV'de görüneceği gibi.
class _SceneCard extends StatelessWidget {
  const _SceneCard();

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: Tokens.accentText.withValues(alpha: 0.55), width: 1.4),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(23),
        child: AspectRatio(
          aspectRatio: 1.6,
          child: Stack(
            fit: StackFit.expand,
            children: [
              Image.asset('assets/intro_scene.jpg', fit: BoxFit.cover, alignment: const Alignment(-0.5, 0), filterQuality: FilterQuality.medium),
              // Altyazılar okunsun diye sahnenin altı koyulaşır.
              const DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [Color(0x00000000), Color(0x99000000)],
                    stops: [0.45, 1],
                  ),
                ),
              ),
              Positioned(
                left: 14,
                right: 14,
                bottom: 14,
                child: Column(
                  children: [
                    Glass(
                      blur: true,
                      radius: 999,
                      tint: Colors.black,
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                      child: Text(t('intro.sample.source'), style: const TextStyle(color: Color(0xFFD5D9E0), fontSize: 13)),
                    ),
                    const SizedBox(height: 6),
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: const Color(0xE60B0D12),
                        borderRadius: BorderRadius.circular(999),
                        border: Border.all(color: const Color(0x59FFFFFF), width: 0.8),
                      ),
                      child: FittedBox(
                        fit: BoxFit.scaleDown,
                        child: Text(
                          t('intro.sample.translation'),
                          textDirection: dirOf(t('intro.sample.translation')),
                          style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _HowPage extends StatelessWidget {
  const _HowPage();

  @override
  Widget build(BuildContext context) {
    return _IntroLayout(
      visual: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: FlowRow(
          nodes: [
            FlowNode(icon: CupertinoIcons.gamecontroller_fill, label: 'PS5', detail: t('intro.how.video'), color: Tokens.textSecondary),
            FlowNode(icon: CupertinoIcons.device_phone_portrait, label: t('intro.how.phone'), detail: t('intro.how.translates'), color: Tokens.accent),
            FlowNode(icon: CupertinoIcons.tv_fill, label: 'TV', detail: t('intro.how.shows'), color: Tokens.success),
          ],
        ),
      ),
      title: t('intro.how.title'),
      body: t('intro.how.body'),
    );
  }
}

class _NeedsPage extends StatelessWidget {
  const _NeedsPage();

  @override
  Widget build(BuildContext context) {
    return _IntroLayout(
      visual: const SizedBox.shrink(),
      title: t('intro.needs.title'),
      body: t('intro.needs.body'),
      extra: Column(
        children: [
          _Need(
            icon: CupertinoIcons.person_2_fill,
            title: t('intro.needs.user.title'),
            text: t('intro.needs.user.text'),
          ),
          _Need(
            icon: CupertinoIcons.tv_fill,
            title: t('intro.needs.tv.title'),
            text: t('intro.needs.tv.text'),
          ),
          _Need(
            icon: CupertinoIcons.wifi,
            title: t('intro.needs.wifi.title'),
            text: t('intro.needs.wifi.text'),
          ),
        ],
      ),
    );
  }
}

class _Need extends StatelessWidget {
  const _Need({required this.icon, required this.title, required this.text});

  final IconData icon;
  final String title;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Tokens.surface,
        borderRadius: BorderRadius.circular(Tokens.radiusCard),
        border: Border.all(color: Tokens.border),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(color: Tokens.accentSoft, borderRadius: BorderRadius.circular(12)),
            child: Icon(icon, size: 20, color: Tokens.accentText),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
                const SizedBox(height: 3),
                Text(text, style: const TextStyle(color: Tokens.textSecondary, fontSize: 13.5, height: 1.4)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
