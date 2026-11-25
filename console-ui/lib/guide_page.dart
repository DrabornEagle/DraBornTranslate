// SPDX-License-Identifier: AGPL-3.0-only
import 'package:material_ui/material_ui.dart';

import 'bridge.dart';
import 'l10n.dart';
import 'main.dart';
import 'pc_screen_page.dart';
import 'screen_page.dart';
import 'tv_install_page.dart';
import 'ui.dart';

/// Ayrıntılı kullanım rehberi: PS5 hazırlığından sorun gidermeye kadar her adım. iOS ayarlarındaki gibi bölüm
/// listesi ve her bölümün kendi sayfası. TV kurulum adımları docs/tv-kurulum.md ile aynıdır.
class GuideChapter {
  const GuideChapter({required this.icon, required this.color, required String title, required String subtitle, required this.children})
      : titleKey = title,
        subtitleKey = subtitle;

  final IconData icon;
  final Color color;
  /// Metin tablosundaki anahtarlar; metin her okunuşta arayüz diline göre çözülür.
  final String titleKey;
  final String subtitleKey;
  final List<Widget> children;

  String get title => t(titleKey);
  String get subtitle => t(subtitleKey);
}

/// Rehber sekmesi: bölüm listesi.
class GuideTab extends StatelessWidget {
  const GuideTab({super.key});

  @override
  Widget build(BuildContext context) {
    return LargeTitleList(
      title: t('guide.title'),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 0, 4, 4),
          child: Text(t('guide.intro'), style: Txt.body.copyWith(color: Tokens.textSecondary)),
        ),
        const GuideList(),
      ],
    );
  }
}

/// Bölümlerin gruplu listesi; satıra dokununca bölüm sayfası açılır.
class GuideList extends StatelessWidget {
  const GuideList({super.key});

  @override
  Widget build(BuildContext context) {
    final chapters = guideChapters(null);
    Widget cell(int index) => Cell(
          icon: chapters[index].icon,
          iconColor: chapters[index].color,
          title: chapters[index].title,
          subtitle: chapters[index].subtitle,
          chevron: true,
          onTap: () => Navigator.of(context).push(appleRoute<void>((_) => GuideChapterPage(index: index))),
        );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        GroupHeader(t('guide.group.start')),
        // "TV'm desteklenmiyor" listede TV bölümünün hemen altında durur (numarası sondadır: unsupportedTvGuideChapter).
        Group(inset: 60, children: [for (var i = 0; i < 4; i++) cell(i), cell(unsupportedTvGuideChapter), cell(4)]),
        GroupHeader(t('guide.group.use')),
        Group(inset: 60, children: [for (var i = 5; i < 8; i++) cell(i)]),
        GroupHeader(t('guide.group.consoles')),
        Group(inset: 60, children: [cell(9)]),
        GroupHeader(t('guide.group.help')),
        Group(inset: 60, children: [cell(8)]),
      ],
    );
  }
}

/// Tek bir bölümün sayfası. Kurulumdaki yardım düğmesi doğrudan ilgili bölümü açar.
class GuideChapterPage extends StatefulWidget {
  const GuideChapterPage({super.key, required this.index});

  final int index;

  @override
  State<GuideChapterPage> createState() => _GuideChapterPageState();
}

class _GuideChapterPageState extends State<GuideChapterPage> {
  String? address;

  @override
  void initState() {
    super.initState();
    Bridge.state().then((state) {
      if (mounted) setState(() => address = state.localAddress);
    });
  }

  @override
  Widget build(BuildContext context) {
    final chapters = guideChapters(address);
    final chapter = chapters[widget.index];
    final next = widget.index + 1 < chapters.length ? widget.index + 1 : null;
    return GlassScaffold(
      title: t('guide.title'),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 0, 4, 14),
            child: Row(
              children: [
                IconTile(chapter.icon, chapter.color, size: 44),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(chapter.title, style: Txt.title2),
                      Text(chapter.subtitle, style: Txt.subhead.copyWith(color: Tokens.textSecondary)),
                    ],
                  ),
                ),
              ],
            ),
          ),
          Glass(
            padding: const EdgeInsets.fromLTRB(18, 10, 18, 20),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: chapter.children),
          ),
          if (next != null) ...[
            GroupHeader(t('guide.next')),
            Group(
              inset: 60,
              children: [
                Cell(
                  icon: chapters[next].icon,
                  iconColor: chapters[next].color,
                  title: chapters[next].title,
                  subtitle: chapters[next].subtitle,
                  chevron: true,
                  onTap: () => Navigator.of(context).pushReplacement(appleRoute<void>((_) => GuideChapterPage(index: next))),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

/// "Xbox ile kullanım" bölümünün [guideChapters] içindeki sırası (kurulum ve ayarlar bu bölümü doğrudan açar).
const xboxGuideChapter = 9;

/// "TV'm desteklenmiyor" bölümünün [guideChapters] içindeki sırası (ayarlar ve TV tablosu bu bölümü doğrudan açar).
const unsupportedTvGuideChapter = 10;

/// Bölümlerin içeriği. [address]: telefonun ağ adresi (OBS adresini tam yazmak için); bilinmiyorsa genel yazılır.
List<GuideChapter> guideChapters(String? address) {
  final phone = address ?? t('guide.phone_address');
  return [
    const GuideChapter(
      icon: CupertinoIcons.arrow_right_arrow_left,
      color: Tokens.accent,
      title: 'guide.how.1',
      subtitle: 'guide.how.2',
      children: [
        _P('guide.how.3'),
        _Steps([
          'guide.how.4',
          'guide.how.5',
          'guide.how.6',
        ]),
        _Sub('guide.how.7'),
        _Bullets([
          'guide.how.8',
          'guide.how.9',
          'guide.how.10',
          'guide.how.11',
        ]),
        _Tip('guide.how.12'),
      ],
    ),
    const GuideChapter(
      icon: CupertinoIcons.gamecontroller_fill,
      color: Tokens.indigo,
      title: 'guide.ps5.1',
      subtitle: 'guide.ps5.2',
      children: [
        _Sub('guide.ps5.3'),
        _P('guide.ps5.4'),
        _Steps([
          'guide.ps5.5',
          'guide.ps5.6',
          'guide.ps5.7',
        ]),
        _Sub('guide.ps5.8'),
        _Steps([
          'guide.ps5.9',
          'guide.ps5.10',
          'guide.ps5.11',
        ]),
        _Sub('guide.ps5.12'),
        _P('guide.ps5.13'),
        _Steps([
          'guide.ps5.14',
          'guide.ps5.15',
        ]),
        _Tip('guide.ps5.16'),
      ],
    ),
    const GuideChapter(
      icon: CupertinoIcons.link,
      color: Tokens.success,
      title: 'guide.pair.1',
      subtitle: 'guide.pair.2',
      children: [
        _Sub('guide.pair.3'),
        _Steps([
          'guide.pair.4',
          'guide.pair.5',
          'guide.pair.6',
        ]),
        _Tip('guide.pair.7'),
        _Sub('guide.pair.8'),
        _Steps([
          'guide.pair.9',
          'guide.pair.10',
          'guide.pair.11',
          'guide.pair.12',
        ]),
        _Sub('guide.pair.13'),
        _Bullets([
          'guide.pair.14',
          'guide.pair.15',
          'guide.pair.16',
        ]),
        _Tip('guide.pair.17'),
      ],
    ),
    GuideChapter(
      icon: CupertinoIcons.tv_fill,
      color: Tokens.purple,
      title: 'guide.tv.1',
      subtitle: 'guide.tv.2',
      children: [
        const _P('guide.tv.3'),
        const InstallTvButton(),
        const _Sub('guide.tv.4'),
        const _Table([
          ('guide.tv.5', 'guide.tv.6', true),
          ('guide.tv.7', 'guide.tv.8', true),
          ('guide.tv.9', 'guide.tv.10', true),
          ('guide.tv.11', 'guide.tv.12', false),
        ]),
        const _Tip('guide.tv.13'),
        const _Sub('guide.tv.14'),
        const _Steps([
          'guide.tv.15',
          'guide.tv.16',
          'guide.tv.17',
          'guide.tv.18',
        ]),
        const _P('guide.tv.19'),
        const _Sub('guide.tv.20'),
        const _Steps([
          'guide.tv.21',
          'guide.tv.22',
          'guide.tv.23',
          'guide.tv.24',
        ]),
        const _Sub('guide.tv.25'),
        const _Steps([
          'guide.tv.26',
          'guide.tv.27',
          'guide.tv.28',
          'guide.tv.29',
        ]),
        const _P('guide.tv.30'),
        const _Sub('guide.tv.31'),
        const _P('guide.tv.32'),
        const _Sub('guide.tv.33'),
        _P('guide.tv.34', [phone]),
      ],
    ),
    const GuideChapter(
      icon: CupertinoIcons.play_fill,
      color: Tokens.accent,
      title: 'guide.first.1',
      subtitle: 'guide.first.2',
      children: [
        _Steps([
          'guide.first.3',
          'guide.first.4',
          'guide.first.5',
          'guide.first.6',
          'guide.first.7',
          'guide.first.8',
        ]),
        _Tip('guide.first.9'),
        _Sub('guide.first.10'),
        _P('guide.first.11'),
        _Sub('guide.first.12'),
        _P('guide.first.13'),
      ],
    ),
    const GuideChapter(
      icon: CupertinoIcons.dot_radiowaves_left_right,
      color: Tokens.gray,
      title: 'guide.remote.1',
      subtitle: 'guide.remote.2',
      children: [
        _P('guide.remote.3'),
        _Keys([
          ('guide.remote.4', 'guide.remote.5'),
          ('guide.remote.6', 'guide.remote.7'),
          ('guide.remote.8', 'guide.remote.9'),
          ('guide.remote.10', 'guide.remote.11'),
          ('guide.remote.12', 'guide.remote.13'),
          ('guide.remote.14', 'guide.remote.15'),
          ('guide.remote.16', 'guide.remote.17'),
          ('guide.remote.18', 'guide.remote.19'),
        ]),
        _Tip('guide.remote.20'),
      ],
    ),
    const GuideChapter(
      icon: CupertinoIcons.crop,
      color: Tokens.teal,
      title: 'guide.region.1',
      subtitle: 'guide.region.2',
      children: [
        _P('guide.region.3'),
        _Steps([
          'guide.region.4',
          'guide.region.5',
          'guide.region.6',
          'guide.region.7',
        ]),
        _Bullets([
          'guide.region.8',
          'guide.region.9',
          'guide.region.10',
        ]),
        _Tip('guide.region.11'),
      ],
    ),
    const GuideChapter(
      icon: CupertinoIcons.textformat,
      color: Tokens.warning,
      title: 'guide.engine.1',
      subtitle: 'guide.engine.2',
      children: [
        _P('guide.engine.9'),
        _Sub('guide.engine.11'),
        _P('guide.engine.12'),
        _Sub('guide.engine.13'),
        _Bullets([
          'guide.engine.14',
          'guide.engine.15',
          'guide.engine.16',
        ]),
      ],
    ),
    const GuideChapter(
      icon: CupertinoIcons.wrench_fill,
      color: Tokens.danger,
      title: 'guide.trouble.1',
      subtitle: 'guide.trouble.2',
      children: [
        _Tip('guide.trouble.3'),
        _Qa('guide.trouble.4', [
          'guide.trouble.5',
          'guide.trouble.6',
          'guide.trouble.7',
        ]),
        _Qa('guide.trouble.8', [
          'guide.trouble.9',
          'guide.trouble.10',
        ]),
        _Qa('guide.trouble.11', [
          'guide.trouble.12',
        ]),
        _Qa('guide.trouble.13', [
        'guide.trouble.14',
        'guide.trouble.15',
        'guide.trouble.16',
        'guide.trouble.17',
      ]),
      _Sub('guide.trouble.18'),
      ...tvRecoverySteps,
      _Qa('guide.trouble.19', [
          'guide.trouble.20',
          'guide.trouble.21',
        ]),
        _Qa('guide.trouble.22', [
          'guide.trouble.23',
          'guide.trouble.24',
          'guide.trouble.25',
          'guide.trouble.26',
        ]),
        _Qa('guide.trouble.27', [
          'guide.trouble.28',
          'guide.trouble.30',
        ]),
        _Qa('guide.trouble.31', [
          'guide.trouble.32',
          'guide.trouble.33',
        ]),
        _Qa('guide.trouble.34', [
          'guide.trouble.35',
          'guide.trouble.36',
        ]),
        _Tip('guide.trouble.37'),
      ],
    ),
    // Sıra önemli: kurulumdaki yardım düğmesi bölümleri numarasıyla açar; yeni bölüm sona eklenir. Bu bölümün numarası
    // xboxGuideChapter'dır.
    const GuideChapter(
      icon: CupertinoIcons.gamecontroller,
      color: Tokens.success,
      title: 'guide.xbox.1',
      subtitle: 'guide.xbox.2',
      children: [
        _Tip('guide.xbox.3'),
        _Sub('guide.xbox.4'),
        _P('guide.xbox.5'),
        _Sub('guide.xbox.6'),
        _Bullets([
          'guide.xbox.7',
          'guide.xbox.8',
          'guide.xbox.9',
          'guide.xbox.10',
        ]),
        _Sub('guide.xbox.11'),
        _Steps([
          'guide.xbox.12',
          'guide.xbox.13',
          'guide.xbox.14',
        ]),
        _Sub('guide.xbox.15'),
        _Steps([
          'guide.xbox.16',
          'guide.xbox.17',
          'guide.xbox.18',
          'guide.xbox.19',
          'guide.xbox.20',
        ]),
        _Tip('guide.xbox.21'),
        _Sub('guide.xbox.22'),
        _Steps([
          'guide.xbox.23',
          'guide.xbox.24',
          'guide.xbox.25',
        ]),
        _Sub('guide.xbox.26'),
        _Bullets([
          'guide.xbox.27',
          'guide.xbox.28',
          'guide.xbox.29',
          'guide.xbox.30',
          'guide.xbox.31',
          'guide.xbox.32',
        ]),
        _Sub('guide.xbox.33'),
        _Qa('guide.xbox.34', [
          'guide.xbox.35',
          'guide.xbox.36',
        ]),
        _Qa('guide.xbox.37', [
          'guide.xbox.38',
          'guide.xbox.39',
          'guide.xbox.40',
        ]),
        _Qa('guide.xbox.41', [
          'guide.xbox.42',
        ]),
        _Qa('guide.xbox.43', [
          'guide.xbox.44',
        ]),
        _Qa('guide.xbox.45', [
          'guide.xbox.46',
          'guide.xbox.47',
        ]),
      ],
    ),
    // Bu bölümün numarası unsupportedTvGuideChapter'dır.
    GuideChapter(
      icon: CupertinoIcons.device_phone_landscape,
      color: Tokens.teal,
      title: 'guide.unsupported.1',
      subtitle: 'guide.unsupported.2',
      children: [
        const _P('guide.unsupported.3'),
        const _Sub('guide.unsupported.4'),
        const _P('guide.unsupported.5'),
        const _Steps([
          'guide.unsupported.6',
          'guide.unsupported.7',
          'guide.unsupported.8',
        ]),
        const ShowOnPhoneButton(),
        const _Bullets([
          'guide.unsupported.9',
          'guide.unsupported.10',
          'guide.unsupported.11',
        ]),
        const _Sub('guide.unsupported.12'),
        const _P('guide.unsupported.13'),
        const _Steps([
          'guide.unsupported.14',
          'guide.unsupported.15',
          'guide.unsupported.16',
        ]),
        const ShowPcScreenButton(),
        const _Sub('guide.unsupported.17'),
        const _P('guide.unsupported.18'),
        _Bullets(['guide.unsupported.19', 'guide.unsupported.20'], [phone]),
        const _Tip('guide.unsupported.21'),
        const _Sub('guide.unsupported.22'),
        const _P('guide.unsupported.23'),
        const _Sub('guide.unsupported.24'),
        const _Bullets([
          'guide.unsupported.25',
          'guide.unsupported.26',
        ]),
      ],
    ),
  ];
}

/// Paragraf.
class _P extends StatelessWidget {
  const _P(this.text, [this.args = const []]);

  final String text;
  final List<Object?> args;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Text(t(text, args), style: const TextStyle(color: Tokens.textSecondary, fontSize: 15.5, height: 1.45, letterSpacing: -0.2)),
      );
}

/// Bölüm içi ara başlık.
class _Sub extends StatelessWidget {
  const _Sub(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 18),
        child: Text(t(text), style: Txt.headline),
      );
}

/// Numaralı adımlar.
class _Steps extends StatelessWidget {
  const _Steps(this.items);

  final List<String> items;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Column(
        children: [
          for (var i = 0; i < items.length; i++)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 24,
                    height: 24,
                    alignment: Alignment.center,
                    decoration: const BoxDecoration(color: Tokens.accentSoft, shape: BoxShape.circle),
                    child: Text('${i + 1}', style: const TextStyle(color: Tokens.accentText, fontSize: 12.5, fontWeight: FontWeight.w800, height: 1.1)),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(t(items[i]), style: const TextStyle(fontSize: 15.5, height: 1.42, letterSpacing: -0.2)),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// Sırasız liste.
class _Bullets extends StatelessWidget {
  const _Bullets(this.items, [this.args = const []]);

  final List<String> items;
  final List<Object?> args;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Column(
        children: [
          for (final item in items)
            Padding(
              padding: const EdgeInsets.only(bottom: 7),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Padding(
                    padding: const EdgeInsets.only(top: 8, left: 9, right: 12),
                    child: Container(width: 6, height: 6, decoration: const BoxDecoration(color: Tokens.accentText, shape: BoxShape.circle)),
                  ),
                  Expanded(child: Text(t(item, args), style: const TextStyle(color: Tokens.textSecondary, fontSize: 15.5, height: 1.42, letterSpacing: -0.2))),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// İpucu kutusu.
class _Tip extends StatelessWidget {
  const _Tip(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 14),
        child: Notice(icon: CupertinoIcons.lightbulb_fill, text: t(text)),
      );
}

/// Ad ve açıklama çiftleri: kumanda tuşları, çeviri motorları.
class _Keys extends StatelessWidget {
  const _Keys(this.items);

  final List<(String, String)> items;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Column(
        children: [
          for (final (name, text) in items)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 104,
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 7),
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: Tokens.inset,
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(color: Tokens.border),
                    ),
                    child: Text(t(name), textAlign: TextAlign.center, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(top: 5),
                      child: Text(t(text), style: const TextStyle(color: Tokens.textSecondary, fontSize: 14, height: 1.4)),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// Desteklenen TV'ler: marka, ayrıntı ve destek durumu.
class _Table extends StatelessWidget {
  const _Table(this.rows);

  final List<(String, String, bool)> rows;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Container(
        decoration: BoxDecoration(color: Tokens.inset, borderRadius: BorderRadius.circular(16), border: Border.all(color: Tokens.border, width: 0.8)),
        child: Column(
          children: [
            for (var i = 0; i < rows.length; i++) ...[
              if (i > 0) const Divider(),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      rows[i].$3 ? CupertinoIcons.checkmark_circle_fill : CupertinoIcons.xmark_circle_fill,
                      size: 20,
                      color: rows[i].$3 ? Tokens.success : Tokens.danger,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(t(rows[i].$1), style: const TextStyle(fontWeight: FontWeight.w700)),
                          Text(t(rows[i].$2), style: const TextStyle(color: Tokens.textSecondary, fontSize: 13.5, height: 1.35)),
                          if (!rows[i].$3)
                            Text(t('guide.table.unsupported'), style: const TextStyle(color: Tokens.warning, fontSize: 13, height: 1.35)),
                          if (!rows[i].$3)
                            TextButton(
                              style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: const Size(0, 30), tapTargetSize: MaterialTapTargetSize.shrinkWrap),
                              onPressed: () =>
                                  Navigator.of(context).push(appleRoute<void>((_) => const GuideChapterPage(index: unsupportedTvGuideChapter))),
                              child: Text(t('guide.table.open_unsupported'), style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Sorun ve denenecekler.
class _Qa extends StatelessWidget {
  const _Qa(this.question, this.answers);

  final String question;
  final List<String> answers;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Padding(
                padding: EdgeInsets.only(top: 2, right: 10),
                child: Icon(CupertinoIcons.question_circle_fill, size: 18, color: Tokens.warning),
              ),
              Expanded(child: Text(t(question), style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, height: 1.35))),
            ],
          ),
          _Bullets(answers),
        ],
      ),
    );
  }
}

/// TV uygulaması eski bir adrese (ör. kapalı bilgisayara) takılı kaldığında yapılacaklar. Sorun gidermede ve
/// ana ekrandaki uyarıdan açılan sayfada aynı metin kullanılır.
const tvRecoverySteps = <Widget>[
  _P('guide.recover.1'),
  _Sub('guide.recover.2'),
  _Steps([
    'guide.recover.3',
    'guide.recover.4',
    'guide.recover.5',
    'guide.recover.6',
  ]),
  _Sub('guide.recover.7'),
  _Steps([
    'guide.recover.8',
    'guide.recover.9',
    'guide.recover.10',
  ]),
  _Sub('guide.recover.11'),
  _Bullets([
    'guide.recover.12',
    'guide.recover.13',
    'guide.recover.14',
  ]),
];

/// Ana ekrandaki "TV hâlâ bağlanmadı" uyarısından açılan yardım sayfası.
class TvHelpPage extends StatelessWidget {
  const TvHelpPage({super.key});

  @override
  Widget build(BuildContext context) {
    return GlassScaffold(
      title: t('guide.tvhelp.title'),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: const [
          Glass(
            padding: EdgeInsets.fromLTRB(18, 10, 18, 20),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: tvRecoverySteps),
          ),
        ],
      ),
    );
  }
}

/// Rehberdeki "Bu telefonda göster" düğmesi.
class ShowOnPhoneButton extends StatelessWidget {
  const ShowOnPhoneButton({super.key});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: GlowButton(
          icon: CupertinoIcons.device_phone_landscape,
          label: t('guide.btn.phone'),
          onPressed: () => Navigator.of(context).push(appleRoute<void>((_) => const ScreenPage())),
        ),
      );
}

/// Rehberdeki "Bilgisayarın altyazısını göster" düğmesi.
class ShowPcScreenButton extends StatelessWidget {
  const ShowPcScreenButton({super.key});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: GlowButton(
          icon: CupertinoIcons.desktopcomputer,
          label: t('guide.btn.pc'),
          onPressed: () => Navigator.of(context).push(appleRoute<void>((_) => const PcScreenPage())),
        ),
      );
}

/// Rehberdeki "TV uygulamasını kur" düğmesi: telefondan kurulum sihirbazını açar.
class InstallTvButton extends StatelessWidget {
  const InstallTvButton({super.key});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: GlowButton(
          icon: CupertinoIcons.tv_fill,
          label: t('guide.btn.install'),
          onPressed: () => Navigator.of(context).push(appleRoute<void>((_) => const TvInstallPage())),
        ),
      );
}
