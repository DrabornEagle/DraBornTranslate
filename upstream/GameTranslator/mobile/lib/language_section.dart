// SPDX-License-Identifier: AGPL-3.0-only
// "Uygulama dili", "Çeviri dili" ve "Oyunun dili": Ayarlar'ın en üstünde ve kurulumun ilk ekranında aynı satırlar.
import 'package:material_ui/material_ui.dart';

import 'bridge.dart';
import 'l10n.dart';
import 'languages.dart';
import 'main.dart';
import 'ui.dart';

/// Arayüz dili seçenekleri: Otomatik ve her tablonun kendi adı.
Map<String, String> uiLanguageOptions() => {
      'auto': t('lang.auto'),
      for (final code in L10n.tables.keys) code: L10n.nameOf(code),
    };

Map<String, String> targetLanguageOptions() => {for (final lang in Languages.targets) lang.code: lang.label};

/// Üç satır: uygulama dili (hemen değişir), çeviri dili ve oyunun dili. [change] ayarı değiştirip kaydeder.
class LanguageSection extends StatelessWidget {
  const LanguageSection({super.key, required this.settings, required this.change});

  final AppSettings settings;
  final void Function(void Function(AppSettings) edit) change;

  Future<void> pickUi(BuildContext context) async {
    final picked = await showApplePicker(context, title: t('lang.app'), options: uiLanguageOptions(), selected: settings.uiLanguage);
    if (picked == null) return;
    change((s) => s.json['uiLanguage'] = picked);
    L10n.apply(picked);
  }

  Future<void> pickTarget(BuildContext context) async {
    final picked =
        await showApplePicker(context, title: t('lang.target'), options: targetLanguageOptions(), selected: settings.targetLanguage);
    if (picked != null) change((s) => s.json['targetLanguage'] = picked);
  }

  Future<void> pickSource(BuildContext context) async {
    final picked = await showApplePicker(
      context,
      title: t('lang.source'),
      options: {for (final code in Languages.sources) code: Languages.label(code)},
      selected: settings.sourceLanguage,
    );
    if (picked != null) change((s) => s.json['sourceLanguage'] = picked);
  }

  @override
  Widget build(BuildContext context) {
    final ui = settings.uiLanguage;
    final group = Group(
      inset: 60,
      children: [
        Cell(
          key: const ValueKey('lang-app'),
          icon: CupertinoIcons.textformat,
          iconColor: Tokens.gray,
          title: t('lang.app'),
          value: ui == 'auto' ? t('lang.auto_value', [L10n.nameOf(L10n.lang)]) : L10n.nameOf(ui),
          chevron: true,
          onTap: () => pickUi(context),
        ),
        Cell(
          key: const ValueKey('lang-target'),
          icon: CupertinoIcons.globe,
          iconColor: Tokens.accent,
          title: t('lang.target'),
          value: Languages.label(settings.targetLanguage),
          chevron: true,
          onTap: () => pickTarget(context),
        ),
        Cell(
          key: const ValueKey('lang-source'),
          icon: CupertinoIcons.gamecontroller_fill,
          iconColor: Tokens.indigo,
          title: t('lang.source'),
          value: Languages.label(settings.sourceLanguage),
          chevron: true,
          onTap: () => pickSource(context),
        ),
      ],
    );
    // İlk kurulumda çeviri dili telefonun dilidir; oyunun dili (varsayılan İngilizce) ile aynı olabilir.
    if (settings.sourceLanguage != settings.targetLanguage) return group;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        group,
        const SizedBox(height: 10),
        Notice(
          key: const ValueKey('lang-same'),
          icon: CupertinoIcons.exclamationmark_circle_fill,
          color: Tokens.warning,
          text: t('lang.same', [Languages.local(settings.targetLanguage)]),
        ),
      ],
    );
  }
}

/// Kurulum ekranı için: ayarları kendisi okur ve her değişikliği hemen kaydeder.
class SetupLanguageSection extends StatefulWidget {
  const SetupLanguageSection({super.key});

  @override
  State<SetupLanguageSection> createState() => _SetupLanguageSectionState();
}

class _SetupLanguageSectionState extends State<SetupLanguageSection> {
  AppSettings? settings;

  @override
  void initState() {
    super.initState();
    Bridge.settings().then((loaded) {
      if (mounted) setState(() => settings = loaded);
    }, onError: (_) {});
  }

  void change(void Function(AppSettings) edit) {
    final current = settings;
    if (current == null) return;
    setState(() => edit(current));
    Bridge.saveSettings(current);
  }

  @override
  Widget build(BuildContext context) {
    final current = settings;
    if (current == null) return const SizedBox.shrink();
    return LanguageSection(settings: current, change: change);
  }
}

/// Dil düğmesinden dil değişince artar: ayarları kendi tutan ekranlar (Ayarlar sekmesi) yeniden okur.
final ValueNotifier<int> languageChanges = ValueNotifier<int>(0);

/// İlk ekrandaki dil düğmesi: dünya simgesi ve arayüz dilinin kendi adı. Dokununca açılan sayfada uygulama dili ve
/// çeviri dili, her dilin kendi adıyla seçilir; uygulamanın o anki dilini okuyamayan kullanıcı da kendi dilini bulur.
class LanguageGlobeButton extends StatelessWidget {
  const LanguageGlobeButton({super.key});

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: 'Language',
      child: GestureDetector(
        key: const ValueKey('lang-globe'),
        behavior: HitTestBehavior.opaque,
        onTap: () => showLanguageSheet(context),
        child: Container(
          height: 38,
          padding: const EdgeInsets.fromLTRB(11, 0, 13, 0),
          decoration: BoxDecoration(color: Tokens.surfaceHigh, borderRadius: BorderRadius.circular(19), border: Border.all(color: Tokens.border)),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(CupertinoIcons.globe, size: 19, color: Tokens.textPrimary),
              const SizedBox(width: 6),
              Text(L10n.nameOf(L10n.lang), style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w600, color: Tokens.textPrimary)),
            ],
          ),
        ),
      ),
    );
  }
}

/// Başlık o anki dilde; dil İngilizce değilse yanında İngilizcesi de yazar ("Çeviri dili · Translate into").
String bilingualHeading(String key) {
  final text = t(key);
  final english = L10n.tables['en']?[key] ?? text;
  return text == english ? text : '$text · $english';
}

Future<void> showLanguageSheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Tokens.sheet,
    shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(22))),
    builder: (context) => const _LanguageSheet(),
  );
}

class _LanguageSheet extends StatefulWidget {
  const _LanguageSheet();

  @override
  State<_LanguageSheet> createState() => _LanguageSheetState();
}

class _LanguageSheetState extends State<_LanguageSheet> {
  AppSettings? settings;

  @override
  void initState() {
    super.initState();
    Bridge.settings().then((loaded) {
      if (mounted) setState(() => settings = loaded);
    }, onError: (_) {});
  }

  void change(void Function(AppSettings) edit) {
    final current = settings;
    if (current == null) return;
    setState(() => edit(current));
    Bridge.saveSettings(current).then((_) => languageChanges.value++, onError: (_) {});
  }

  Widget heading(IconData icon, String key) => Padding(
        padding: const EdgeInsets.fromLTRB(2, 0, 0, 10),
        child: Row(
          children: [
            Icon(icon, size: 17, color: Tokens.textSecondary),
            const SizedBox(width: 7),
            Flexible(child: Text(bilingualHeading(key), style: Txt.footnote.copyWith(color: Tokens.textSecondary, fontWeight: FontWeight.w600))),
          ],
        ),
      );

  Widget chip(String key, String text, bool selected, VoidCallback onTap) => GestureDetector(
        key: ValueKey(key),
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 9),
          decoration: BoxDecoration(color: selected ? Tokens.accentSoft : Tokens.surface, borderRadius: BorderRadius.circular(11)),
          child: Text(
            text,
            style: TextStyle(fontSize: 15.5, fontWeight: selected ? FontWeight.w700 : FontWeight.w400, color: selected ? Tokens.accentText : Tokens.textPrimary),
          ),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final current = settings;
    final height = MediaQuery.sizeOf(context).height;
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: height * 0.82),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(18, 18, 18, 14),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              heading(CupertinoIcons.globe, 'lang.app'),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final code in L10n.tables.keys)
                    chip('lang-ui-$code', L10n.nameOf(code), L10n.lang == code, () {
                      change((s) => s.json['uiLanguage'] = code);
                      L10n.apply(code);
                    }),
                ],
              ),
              const SizedBox(height: 20),
              heading(CupertinoIcons.textformat_abc, 'lang.target'),
              if (current != null)
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final lang in Languages.targets)
                      chip('lang-target-${lang.code}', lang.native, current.targetLanguage == lang.code, () => change((s) => s.json['targetLanguage'] = lang.code)),
                  ],
                ),
            ],
          ),
        ),
      ),
    );
  }
}
