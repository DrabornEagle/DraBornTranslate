// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import 'bridge.dart';
import 'guide_page.dart';
import 'history_page.dart';
import 'home_page.dart';
import 'intro_page.dart';
import 'l10n.dart';
import 'language_section.dart';
import 'languages.dart';
import 'main.dart';
import 'pc_screen_page.dart';
import 'screen_page.dart';
import 'tv_install_page.dart';
import 'tv_install.dart';
import 'ui.dart';

/// Ayarlar sekmesi, iOS ayarları düzeninde: çeviri, altyazı bölgesi, TV'deki görünüm, konsol (PS5 ya da Xbox) ve hakkında.
/// Gönüllü destek sayfası.
const supportUrl = 'https://buymeacoffee.com/gametranslator';

class SettingsTab extends StatefulWidget {
  const SettingsTab({super.key, required this.setup, required this.onSetupChanged, required this.active});

  final SetupInfo setup;
  final Future<void> Function() onSetupChanged;

  /// Sekme ekranda mı? Arkadayken oyun görüntüsü istenmez.
  final bool active;

  @override
  State<SettingsTab> createState() => _SettingsTabState();
}

class _SettingsTabState extends State<SettingsTab> {
  static const placements = ['cover', 'above', 'below', 'fixed'];
  static const colors = ['white', 'yellow', 'cyan', 'green', 'orange'];
  String colorName(String color) => colors.contains(color) ? t('settings.look.color.$color') : '';
  Map<String, String> fontNames() => {'': t('settings.look.font.standard'), 'quicksand': t('settings.look.font.quicksand')};
  static const edges = ['top', 'bottom', 'left', 'right'];
  static const defaultRegion = {'top': 0.62, 'bottom': 0.97, 'left': 0.14, 'right': 0.86};

  AppSettings? settings;
  final deepLController = TextEditingController();
  Uint8List? preview;
  Timer? saveTimer;
  Timer? previewTimer;

  /// Altyazı bölgesinde sürgünün ayarladığı kenar.
  String edge = 'top';

  @override
  void initState() {
    super.initState();
    load();
    languageChanges.addListener(load);
    // Bölge ayarlanırken oyun görüntüsü güncel kalsın.
    previewTimer = Timer.periodic(const Duration(seconds: 2), (_) => refreshPreview());
  }

  @override
  void dispose() {
    saveTimer?.cancel();
    languageChanges.removeListener(load);
    previewTimer?.cancel();
    final current = settings;
    if (current != null) Bridge.saveSettings(current);
    deepLController.dispose();
    super.dispose();
  }

  Future<void> load() async {
    final loaded = await Bridge.settings();
    final image = await Bridge.snapshot(width: 640);
    if (!mounted) return;
    setState(() {
      settings = loaded;
      preview = image;
      deepLController.text = loaded.deepLKey;
    });
  }

  Future<void> refreshPreview() async {
    if (!mounted || !widget.active) return;
    final image = await Bridge.snapshot(width: 640);
    if (mounted && image != null) setState(() => preview = image);
  }

  /// Değişiklik hemen ekrana yansır; kaydetme sürgü sürüklenirken her adımda değil, kısa bir aradan sonra yapılır.
  void change(void Function(AppSettings) edit) {
    final current = settings;
    if (current == null) return;
    setState(() => edit(current));
    saveTimer?.cancel();
    saveTimer = Timer(const Duration(milliseconds: 300), () => Bridge.saveSettings(current));
  }

  void setRegion(String edge, double value) => change((s) {
        final region = s.region;
        region[edge] = value;
        // Bölge en az %5 yüksekliğinde ve genişliğinde kalır.
        if (edge == 'top' && region['bottom'] < value + 0.05) region['bottom'] = (value + 0.05).clamp(0.0, 1.0);
        if (edge == 'bottom' && region['top'] > value - 0.05) region['top'] = (value - 0.05).clamp(0.0, 1.0);
        if (edge == 'left' && region['right'] < value + 0.05) region['right'] = (value + 0.05).clamp(0.0, 1.0);
        if (edge == 'right' && region['left'] > value - 0.05) region['left'] = (value - 0.05).clamp(0.0, 1.0);
        s.json['region'] = region;
      });

  void setStyle(String key, Object value) => change((s) {
        final style = s.style;
        style[key] = value;
        s.json['style'] = style;
      });

  Future<void> pickPlacement() async {
    final picked = await showApplePicker(
      context,
      title: t('settings.place.title'),
      options: {for (final place in placements) place: t('settings.place.$place')},
      selected: settings?.style['placement'] as String?,
    );
    if (picked != null) setStyle('placement', picked);
  }

  Future<void> pickColor() async {
    final picked = await showApplePicker(
      context,
      title: t('settings.look.color'),
      options: {for (final color in colors) color: t('settings.look.color.$color')},
      selected: settings?.style['textColor'] as String?,
    );
    if (picked != null) setStyle('textColor', picked);
  }

  Future<void> pickFont() async {
    final picked = await showApplePicker(
      context,
      title: t('settings.look.font'),
      options: fontNames(),
      selected: settings?.style['font'] as String? ?? '',
    );
    if (picked != null) setStyle('font', picked);
  }

  Future<void> editGlossary() async {
    final current = settings;
    if (current == null) return;
    final text = await Navigator.of(context).push<String>(appleRoute((_) => GlossaryPage(initial: current.glossary)));
    if (text != null) change((s) => s.json['glossary'] = text);
  }

  Future<void> sendTest() async {
    await Bridge.testSubtitle();
    if (mounted) toast(context, t('settings.test.sent'));
  }

  Future<void> confirmForget({required bool account}) async {
    final confirmed = await confirmApple(
      context,
      title: account ? t('settings.ps5.forget_account.title') : t('settings.ps5.repair.title'),
      message: account ? t('settings.ps5.forget_account.text') : t('settings.ps5.repair.text'),
      action: t('settings.ps5.forget.action'),
      destructive: true,
    );
    if (!confirmed) return;
    await Bridge.stop();
    if (account) {
      await Bridge.forgetAccount();
    } else {
      await Bridge.forgetConsole();
    }
    await widget.onSetupChanged();
  }

  /// Konsol türü değişir: çeviri durur; öbür konsolun kurulumu yapılmamışsa kurulum ekranı açılır.
  Future<void> pickConsoleKind() async {
    final current = widget.setup.isXbox ? 'xbox' : 'ps5';
    final picked = await showApplePicker(
      context,
      title: t('settings.console.kind'),
      options: const {'ps5': 'PlayStation 5', 'xbox': 'Xbox (Beta)'},
      selected: current,
    );
    if (picked == null || picked == current) return;
    await Bridge.stop();
    await Bridge.setConsoleKind(xbox: picked == 'xbox');
    await widget.onSetupChanged();
  }

  Future<void> confirmForgetXbox({required bool account}) async {
    final confirmed = await confirmApple(
      context,
      title: account ? t('settings.xbox.signout.title') : t('settings.xbox.other.title'),
      message: account ? t('settings.xbox.signout.text') : t('settings.xbox.other.text'),
      action: account ? t('settings.xbox.signout.action') : t('settings.xbox.other.action'),
      destructive: account,
    );
    if (!confirmed) return;
    await Bridge.stop();
    if (account) {
      await Bridge.xboxSignOut();
    } else {
      await Bridge.xboxForgetConsole();
    }
    await widget.onSetupChanged();
  }

  void showIntro() {
    Navigator.of(context).push(appleRoute<void>((context) => IntroPage(replay: true, onDone: () async => Navigator.of(context).pop())));
  }

  Future<void> showLogs() async {
    final lines = await Bridge.logs();
    if (!mounted) return;
    final text = lines.isEmpty ? t('settings.log.empty') : lines.reversed.join('\n');
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheet) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.7,
        builder: (sheet, controller) => ListView(
          controller: controller,
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
          children: [
            Row(
              children: [
                Expanded(child: Text(t('settings.log'), style: Txt.title2)),
                TextButton(
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: text));
                    Navigator.of(sheet).pop();
                    toast(context, t('settings.log.copied'));
                  },
                  child: Text(t('settings.log.copy')),
                ),
              ],
            ),
            Text(t('settings.log.hint'), style: Txt.footnote.copyWith(color: Tokens.textSecondary)),
            const SizedBox(height: 12),
            SelectableText(text, style: const TextStyle(fontFamily: 'monospace', fontSize: 12, color: Tokens.textSecondary, height: 1.45)),
          ],
        ),
      ),
    );
  }

  String get glossarySummary {
    final (terms, keep, problem) = GlossaryPage.inspect(settings?.glossary ?? '');
    if (problem != null) return t('settings.glossary.bad');
    if (terms + keep == 0) return t('settings.glossary.empty');
    return tn('settings.glossary.count', terms + keep);
  }

  @override
  Widget build(BuildContext context) {
    final s = settings;
    if (s == null) return const Center(child: CupertinoActivityIndicator(radius: 14));
    final style = s.style;
    final place = style['placement'] as String?;
    final target = Languages.find(s.targetLanguage);
    // Seçilen motor bu dile çeviremiyorsa çekirdek Google'a geçer (core/Languages.kt); burada söylenir.
    final engineFallback = target != null && !target.supports(s.engine);
    final engineName = s.engine == 'deepl' ? 'DeepL' : t('settings.engine.device');
    return LargeTitleList(
      title: t('settings.title'),
      children: [
        // ---------------------------------------------------------------- Dil
        GroupHeader(t('settings.lang.header')),
        LanguageSection(settings: s, change: change),
        // Oyun paketleri ve terim sözlüğü yalnız İngilizce → Türkçe çeviride vardır; başka dilde gösterilmez.
        if (!s.turkishExtras) GroupFooter(t('settings.tr_only')),

        // ---------------------------------------------------------------- Çeviri
        if (s.turkishExtras) ...[
          GroupHeader(t('settings.translation.header')),
          Group(
            inset: 60,
            children: [
              Cell(
                icon: CupertinoIcons.book_fill,
                iconColor: Tokens.warning,
                title: t('settings.glossary'),
                value: glossarySummary,
                chevron: true,
                onTap: editGlossary,
              ),
              Cell(
                icon: CupertinoIcons.cube_box_fill,
                iconColor: Tokens.success,
                title: t('settings.packs'),
                subtitle: t('settings.packs.sub'),
                trailing: CupertinoSwitch(
                  value: s.packsEnabled,
                  activeTrackColor: Tokens.success,
                  onChanged: (value) => change((s) => s.json['packsEnabled'] = value),
                ),
              ),
            ],
          ),
          GroupFooter(t('settings.packs.footer')),
        ],
        // ---------------------------------------------------------------- Ortak havuz (İngilizce oyunlar)
        if (s.sourceLanguage == 'en') ...[
          Group(
            inset: 60,
            children: [
              Cell(
                icon: CupertinoIcons.cloud_upload_fill,
                iconColor: Tokens.accent,
                title: t('settings.pool'),
                subtitle: t('settings.pool.sub'),
                trailing: CupertinoSwitch(
                  value: s.json['sharePool'] as bool? ?? true,
                  activeTrackColor: Tokens.success,
                  onChanged: (value) => change((s) => s.json['sharePool'] = value),
                ),
              ),
            ],
          ),
          GroupFooter(t('settings.pool.footer')),
        ],
        GroupHeader(t('settings.engine.header')),
        Group(
          children: [
            Cell(
              title: 'Google',
              subtitle: t('settings.engine.google.sub'),
              checked: s.engine == 'google',
              onTap: () => change((s) => s.json['engine'] = 'google'),
            ),
            Cell(
              title: 'DeepL',
              subtitle: t('settings.engine.deepl.sub'),
              checked: s.engine == 'deepl',
              onTap: () => change((s) => s.json['engine'] = 'deepl'),
            ),
            if (s.engine == 'deepl')
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
                child: TextField(
                  controller: deepLController,
                  autocorrect: false,
                  decoration: InputDecoration(hintText: t('settings.engine.deepl.key')),
                  onChanged: (value) => change((s) => s.json['deepLKey'] = value.trim()),
                ),
              ),
            Cell(
              title: t('settings.engine.device'),
              subtitle: t('settings.engine.device.sub'),
              checked: s.engine == 'mlkit',
              onTap: () => change((s) => s.json['engine'] = 'mlkit'),
            ),
          ],
        ),
        if (engineFallback)
          Padding(
            padding: const EdgeInsets.only(top: 10),
            child: Notice(
              key: const ValueKey('engine-fallback'),
              icon: CupertinoIcons.info_circle_fill,
              color: Tokens.warning,
              text: t('settings.engine.fallback', [engineName, target.local]),
            ),
          ),
        GroupFooter(
          target != null && !target.mlkit
              ? [t('settings.engine.footer'), t('settings.engine.no_offline', [target.local])].join(' ')
              : t('settings.engine.footer'),
        ),

        // ---------------------------------------------------------------- Altyazı bölgesi
        GroupHeader(t('settings.region.header')),
        Glass(
          clip: true,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (preview != null) RegionPreview(image: preview!, settings: s) else _RegionPlaceholder(settings: s),
              // Otomatik: altyazının yeri oyunun görüntüsünden bulunur; elle seçim yalnız kapalıyken.
              Cell(
                icon: CupertinoIcons.viewfinder,
                iconColor: Tokens.accent,
                title: t('settings.region.auto'),
                subtitle: t('settings.region.auto.sub'),
                trailing: CupertinoSwitch(
                  value: s.autoRegion,
                  activeTrackColor: Tokens.success,
                  onChanged: (value) => change((s) => s.json['autoRegion'] = value),
                ),
              ),
              if (!s.autoRegion) ...[
                const Divider(),
                Padding(
                  padding: const EdgeInsets.fromLTRB(14, 14, 14, 6),
                  child: Segmented<String>(
                    value: edge,
                    items: {for (final side in edges) side: t('settings.edge.$side')},
                    onChanged: (value) => setState(() => edge = value),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(14, 0, 16, 4),
                  child: Row(
                    children: [
                      Expanded(
                        child: CupertinoSlider(
                          value: s.regionValue(edge).clamp(0.0, 1.0),
                          activeColor: Tokens.accent,
                          onChanged: (value) => setRegion(edge, (value * 100).round() / 100),
                        ),
                      ),
                      SizedBox(
                        width: 46,
                        child: Text(t('settings.percent', [(s.regionValue(edge) * 100).round()]), textAlign: TextAlign.end, style: Txt.body.copyWith(color: Tokens.textSecondary)),
                      ),
                    ],
                  ),
                ),
                const Divider(),
                Cell(title: t('settings.region.reset'), titleColor: Tokens.accent, onTap: () => change((s) => s.json['region'] = Map<String, dynamic>.from(defaultRegion))),
              ],
            ],
          ),
        ),
        GroupFooter(s.autoRegion ? t('settings.region.auto.footer') : t('settings.region.footer')),

        // ---------------------------------------------------------------- TV'de görünüm
        GroupHeader(t('settings.look.header')),
        Group(
          children: [
            _ControlRow(
              title: t('settings.look.size'),
              child: Segmented<String>(
                value: '${style['fontSize']}',
                items: {'36': t('settings.look.size.s'), '46': t('settings.look.size.m'), '58': t('settings.look.size.l'), '72': t('settings.look.size.xl')},
                onChanged: (value) => setStyle('fontSize', int.parse(value)),
              ),
            ),
            Cell(
              title: t('settings.look.color'),
              value: colorName(style['textColor'] as String? ?? 'white'),
              chevron: true,
              onTap: pickColor,
            ),
            Cell(
              title: t('settings.look.font'),
              value: fontNames()[style['font'] as String? ?? ''] ?? '',
              chevron: true,
              onTap: pickFont,
            ),
            Cell(
              title: t('settings.place.title'),
              subtitle: placements.contains(place) ? t('settings.place.$place') : '',
              chevron: true,
              onTap: pickPlacement,
            ),
            // Bilgisayardaki "Ekran konumu (alttan yükseklik)": %2–30. TV yalnız sabit yerleşimde kullanır.
            if (place == 'fixed')
              _SliderRow(
                key: const ValueKey('look-bottom'),
                title: t('settings.look.bottom'),
                value: (style['bottomPercent'] as num? ?? 7).toDouble().clamp(2, 30).toDouble(),
                min: 2,
                max: 30,
                divisions: 28,
                onChanged: (value) => setStyle('bottomPercent', value.round()),
              ),
            // Hızlı konuşmada önceki satır yenisinin üstünde soluk kalır; örtme yerleşiminde TV'ye kapalı gider.
            if (place != 'cover')
              Cell(
                key: const ValueKey('look-stack'),
                title: t('settings.look.stack'),
                subtitle: t('settings.look.stack.sub'),
                trailing: CupertinoSwitch(
                  value: style['stackPrevious'] as bool? ?? true,
                  activeTrackColor: Tokens.success,
                  onChanged: (value) => setStyle('stackPrevious', value),
                ),
              ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(child: Text(t('settings.look.background'), style: Txt.body)),
                      Text(t('settings.percent', [style['background']]), style: Txt.body.copyWith(color: Tokens.textSecondary)),
                    ],
                  ),
                  CupertinoSlider(
                    value: (style['background'] as num).toDouble().clamp(0, 100),
                    max: 100,
                    divisions: 20,
                    activeColor: Tokens.accent,
                    onChanged: (value) => setStyle('background', value.round()),
                  ),
                ],
              ),
            ),
            Cell(
              title: t('settings.look.bold'),
              trailing: CupertinoSwitch(value: style['bold'] as bool, activeTrackColor: Tokens.success, onChanged: (value) => setStyle('bold', value)),
            ),
            Cell(
              title: t('settings.look.speaker'),
              subtitle: t('settings.look.speaker.sub'),
              trailing: CupertinoSwitch(
                value: style['speakerColor'] as bool? ?? false,
                activeTrackColor: Tokens.success,
                onChanged: (value) => setStyle('speakerColor', value),
              ),
            ),
            // Android TV: HDMI uygulamanın içinde mi, TV'nin kendi ekranında mı (altyazı üstte katmanda). TV uygulaması
            // style olayındaki displayMode'u alır; LG ve Samsung tanımaz.
            _ControlRow(
              key: const ValueKey('look-display-mode'),
              title: t('settings.look.display_mode'),
              child: Segmented<String>(
                value: style['displayMode'] == 'overlay' ? 'overlay' : 'inApp',
                items: {'inApp': t('settings.look.display_mode.in_app'), 'overlay': t('settings.look.display_mode.overlay')},
                onChanged: (value) => setStyle('displayMode', value),
              ),
            ),
            Cell(title: t('settings.test.send'), titleColor: Tokens.accent, onTap: sendTest),
          ],
        ),
        GroupFooter(t('settings.look.display_mode.footer')),

        // ---------------------------------------------------------------- Ek alanlar (Deneysel)
        // Görev yazısı, ipucu kutusu, telefon mesajı gibi yazılar ayrı okunup TV'de kendi yerlerinde çevrilir. Telefonda
        // alanlar hazır seçimlerdir (yer ve boy düzenlenmez); en çok üçü aynı anda açık olur.
        GroupHeader(t('settings.extra.header')),
        Group(
          inset: 60,
          children: [
            Cell(
              key: const ValueKey('extra-gta6'),
              icon: CupertinoIcons.wand_stars,
              iconColor: Tokens.purple,
              title: t('settings.extra.gta6'),
              subtitle: t('settings.extra.gta6.sub'),
              titleColor: Tokens.accent,
              onTap: () {
                change((s) => s.applyGta6Reading());
                toast(context, t('settings.extra.gta6.done'));
              },
            ),
            for (final preset in AppSettings.extraPresets)
              Cell(
                key: ValueKey('extra-$preset'),
                icon: CupertinoIcons.text_bubble_fill,
                iconColor: Tokens.teal,
                title: t('settings.extra.preset.$preset'),
                subtitle: t('settings.extra.preset.$preset.sub'),
                trailing: CupertinoSwitch(
                  value: s.hasExtraPreset(preset),
                  activeTrackColor: Tokens.success,
                  // Üç alan açıkken kapalı olanlar açılamaz (bildirim aşağıda).
                  onChanged: s.hasExtraPreset(preset) || !s.extraFull ? (value) => change((s) => s.setExtraPreset(preset, value)) : null,
                ),
              ),
          ],
        ),
        if (s.extraFull)
          Padding(
            padding: const EdgeInsets.only(top: 10),
            child: Notice(
              key: const ValueKey('extra-full'),
              icon: CupertinoIcons.info_circle_fill,
              color: Tokens.warning,
              text: t('settings.extra.full'),
            ),
          ),
        const SizedBox(height: 14),
        Group(
          children: [
            _SliderRow(
              title: t('settings.extra.background'),
              value: (style['regionBackground'] as num? ?? 100).toDouble().clamp(0, 100).toDouble(),
              min: 0,
              max: 100,
              divisions: 20,
              onChanged: (value) => setStyle('regionBackground', value.round()),
            ),
            _SliderRow(
              title: t('settings.extra.size'),
              value: (style['regionScale'] as num? ?? 100).toDouble().clamp(70, 150).toDouble(),
              min: 70,
              max: 150,
              divisions: 8,
              onChanged: (value) => setStyle('regionScale', value.round()),
            ),
            _ControlRow(
              title: t('settings.extra.color'),
              child: Segmented<String>(
                value: style['regionColor'] as String? ?? '',
                items: {'': t('settings.extra.color.same'), 'white': t('settings.look.color.white'), 'yellow': t('settings.look.color.yellow')},
                onChanged: (value) => setStyle('regionColor', value),
              ),
            ),
            // Ek alanlarda tanınan tuş simgelerinin kumandası (Otomatik: konsol türüne göre).
            _ControlRow(
              key: const ValueKey('extra-buttons'),
              title: t('settings.extra.buttons'),
              child: Segmented<String>(
                value: s.buttonLayout,
                items: {'auto': t('settings.extra.buttons.auto'), 'playstation': 'PlayStation', 'xbox': 'Xbox'},
                onChanged: (value) => change((s) => s.json['buttonLayout'] = value),
              ),
            ),
          ],
        ),
        GroupFooter(t('settings.extra.footer')),

        // ---------------------------------------------------------------- Altyazı geçmişi
        GroupHeader(t('settings.history.header')),
        Group(
          inset: 60,
          children: [
            Cell(
              key: const ValueKey('settings-history'),
              icon: CupertinoIcons.clock_fill,
              iconColor: Tokens.accentText,
              title: t('history.title'),
              subtitle: t('settings.history.sub'),
              chevron: true,
              onTap: () => Navigator.of(context).push(appleRoute<void>((_) => const HistoryPage())),
            ),
          ],
        ),

        // ---------------------------------------------------------------- TV uygulaması
        GroupHeader(t('settings.tv.header')),
        Group(
          inset: 60,
          children: [
            Cell(
              icon: CupertinoIcons.tv_fill,
              iconColor: Tokens.purple,
              title: t('settings.tv.install'),
              subtitle: t('settings.tv.install.sub'),
              chevron: true,
              onTap: () => Navigator.of(context).push(appleRoute<void>((_) => const TvInstallPage())),
            ),
          ],
        ),

        // ---------------------------------------------------------------- İkinci ekran
        GroupHeader(t('settings.second.header')),
        Group(
          inset: 60,
          children: [
            Cell(
              icon: CupertinoIcons.device_phone_landscape,
              iconColor: Tokens.teal,
              title: t('settings.second.phone'),
              subtitle: t('settings.second.phone.sub'),
              chevron: true,
              onTap: () => Navigator.of(context).push(appleRoute<void>((_) => const ScreenPage())),
            ),
            Cell(
              icon: CupertinoIcons.desktopcomputer,
              iconColor: Tokens.indigo,
              title: t('settings.second.pc'),
              subtitle: t('settings.second.pc.sub'),
              chevron: true,
              onTap: () => Navigator.of(context).push(appleRoute<void>((_) => const PcScreenPage())),
            ),
            Cell(
              icon: CupertinoIcons.book_fill,
              iconColor: Tokens.warning,
              title: t('settings.second.unsupported'),
              subtitle: t('settings.second.unsupported.sub'),
              chevron: true,
              onTap: () => Navigator.of(context).push(appleRoute<void>((_) => const GuideChapterPage(index: unsupportedTvGuideChapter))),
            ),
          ],
        ),
        GroupFooter(t('settings.second.footer')),

        // ---------------------------------------------------------------- Konsol
        GroupHeader(t('settings.console.header')),
        Group(
          inset: 60,
          children: [
            Cell(
              icon: CupertinoIcons.arrow_right_arrow_left,
              iconColor: Tokens.indigo,
              title: t('settings.console.kind'),
              value: widget.setup.isXbox ? 'Xbox (Beta)' : 'PlayStation 5',
              chevron: true,
              onTap: pickConsoleKind,
            ),
          ],
        ),
        GroupFooter(t('settings.console.footer')),

        // ---------------------------------------------------------------- Xbox (Beta)
        if (widget.setup.isXbox) ...[
          const GroupHeader('XBOX'),
          Group(
            inset: 60,
            children: [
              Cell(
                icon: CupertinoIcons.gamecontroller_fill,
                iconColor: Tokens.gray,
                title: widget.setup.xboxConsoleName ?? 'Xbox',
                subtitle: t('settings.xbox.sub'),
                trailing: const Pill(text: 'Beta', color: Tokens.warning),
              ),
              Cell(
                icon: CupertinoIcons.book_fill,
                iconColor: Tokens.success,
                title: t('settings.xbox.guide'),
                chevron: true,
                onTap: () => Navigator.of(context).push(appleRoute<void>((_) => const GuideChapterPage(index: xboxGuideChapter))),
              ),
            ],
          ),
          GroupFooter(t('settings.xbox.footer')),
          const SizedBox(height: 14),
          Group(
            children: [
              Cell(title: t('settings.xbox.other'), titleColor: Tokens.accent, onTap: () => confirmForgetXbox(account: false)),
              Cell(title: t('settings.xbox.signout'), titleColor: Tokens.danger, onTap: () => confirmForgetXbox(account: true)),
            ],
          ),
        ],

        // ---------------------------------------------------------------- PS5
        if (!widget.setup.isXbox) ...[
        const GroupHeader('PS5'),
        Group(
          inset: 60,
          children: [
            Cell(
              icon: CupertinoIcons.gamecontroller_fill,
              iconColor: Tokens.gray,
              title: widget.setup.consoleName ?? 'PS5',
              subtitle: widget.setup.consoleHost ?? '',
              trailing: Pill(text: t('settings.ps5.paired'), color: Tokens.success),
            ),
            Cell(
              icon: CupertinoIcons.power,
              iconColor: Tokens.success,
              title: t('settings.ps5.wake'),
              trailing: CupertinoSwitch(
                value: s.autoWake,
                activeTrackColor: Tokens.success,
                onChanged: (value) => change((s) => s.json['autoWake'] = value),
              ),
            ),
          ],
        ),
        GroupFooter(t('settings.ps5.wake.footer')),
        const SizedBox(height: 14),
        Group(
          children: [
            Cell(title: t('settings.ps5.repair'), titleColor: Tokens.accent, onTap: () => confirmForget(account: false)),
            Cell(title: t('settings.ps5.forget_account'), titleColor: Tokens.danger, onTap: () => confirmForget(account: true)),
          ],
        ),
        ],

        // ---------------------------------------------------------------- Hakkında
        GroupHeader(t('settings.about.header')),
        Group(
          inset: 60,
          children: [
            Cell(icon: CupertinoIcons.play_circle_fill, iconColor: Tokens.purple, title: t('settings.about.intro'), chevron: true, onTap: showIntro),
            Cell(icon: CupertinoIcons.doc_text_fill, iconColor: Tokens.gray, title: t('settings.log'), chevron: true, onTap: showLogs),
            Cell(
              icon: CupertinoIcons.info,
              iconColor: Tokens.accent,
              title: t('settings.about.version'),
              value: widget.setup.version.isEmpty ? '—' : widget.setup.version,
            ),
            // Gönüllü destek: uygulama ücretsiz ve açık kaynak
            Cell(
              key: const ValueKey('about-support'),
              icon: CupertinoIcons.heart_fill,
              iconColor: Tokens.warning,
              title: t('settings.about.support'),
              chevron: true,
              onTap: () => TvBridge.openUrl(supportUrl),
            ),
          ],
        ),
        GroupFooter(t('settings.about.footer')),
      ],
    );
  }
}

/// Başlığı üstte, denetimi altında olan satır (parçalı seçiciler için).
class _ControlRow extends StatelessWidget {
  const _ControlRow({super.key, required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [Text(title, style: Txt.body), const SizedBox(height: 10), child],
      ),
    );
  }
}

/// Başlığı ve yüzde değeri üstte, sürgüsü altında olan satır.
class _SliderRow extends StatelessWidget {
  const _SliderRow({super.key, required this.title, required this.value, required this.min, required this.max, required this.divisions, required this.onChanged});

  final String title;
  final double value;
  final double min;
  final double max;
  final int divisions;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(child: Text(title, style: Txt.body)),
              Text(t('settings.percent', [value.round()]), style: Txt.body.copyWith(color: Tokens.textSecondary)),
            ],
          ),
          CupertinoSlider(value: value, min: min, max: max, divisions: divisions, activeColor: Tokens.accent, onChanged: onChanged),
        ],
      ),
    );
  }
}

/// Çeviri kapalıyken oyun görüntüsü yoktur; bölge boş bir ekran üstünde gösterilir.
class _RegionPlaceholder extends StatelessWidget {
  const _RegionPlaceholder({required this.settings});

  final AppSettings settings;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 16 / 9,
      child: LayoutBuilder(
        builder: (context, constraints) => Stack(
          children: [
            Positioned.fill(child: Container(color: Colors.black)),
            Positioned(
              left: 0,
              right: 0,
              top: 18,
              child: Text(t('settings.region.placeholder'), textAlign: TextAlign.center, style: Txt.footnote.copyWith(color: Tokens.textSecondary)),
            ),
            if (!settings.autoRegion)
              Positioned(
                left: settings.regionValue('left') * constraints.maxWidth,
                top: settings.regionValue('top') * constraints.maxHeight,
                width: (settings.regionValue('right') - settings.regionValue('left')) * constraints.maxWidth,
                height: (settings.regionValue('bottom') - settings.regionValue('top')) * constraints.maxHeight,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    border: Border.all(color: Tokens.accent, width: 1.5),
                    borderRadius: BorderRadius.circular(6),
                    color: Tokens.accent.withValues(alpha: 0.12),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// Terim sözlüğü düzenleyicisi: bilgisayardaki sözlük dosyalarıyla aynı JSON biçimi.
class GlossaryPage extends StatefulWidget {
  const GlossaryPage({super.key, required this.initial});

  final String initial;

  /// Örnek sözlük (İngilizce → Türkçe); metni tablolardadır.
  static String get example => t('settings.glossary.example');

  /// Sözlük metnindeki terim ve korunan ad sayısı; okunamıyorsa üçüncü değer sorunu söyler.
  static (int, int, String?) inspect(String text) {
    if (text.trim().isEmpty) return (0, 0, null);
    try {
      final root = jsonDecode(text);
      if (root is! Map) return (0, 0, t('settings.glossary.not_object'));
      final terms = root['terms'] is Map ? (root['terms'] as Map).values.whereType<String>().length : 0;
      final keep = root['keep'] is List ? (root['keep'] as List).whereType<String>().length : 0;
      return (terms, keep, null);
    } on FormatException {
      return (0, 0, t('settings.glossary.syntax'));
    }
  }

  @override
  State<GlossaryPage> createState() => _GlossaryPageState();
}

class _GlossaryPageState extends State<GlossaryPage> {
  late final controller = TextEditingController(text: widget.initial);

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final (terms, keep, problem) = GlossaryPage.inspect(controller.text);
    final empty = controller.text.trim().isEmpty;
    return GlassScaffold(
      title: t('settings.glossary.page'),
      actions: [
        TextButton(
          onPressed: problem == null ? () => Navigator.of(context).pop(controller.text.trim()) : null,
          child: Text(t('settings.glossary.save'), style: const TextStyle(fontWeight: FontWeight.w600)),
        ),
      ],
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Text(
              t('settings.glossary.intro'),
              style: Txt.subhead.copyWith(color: Tokens.textSecondary),
            ),
          ),
          const SizedBox(height: 14),
          TextField(
            controller: controller,
            minLines: 10,
            maxLines: 22,
            autocorrect: false,
            enableSuggestions: false,
            onChanged: (_) => setState(() {}),
            style: const TextStyle(fontFamily: 'monospace', fontSize: 13.5, height: 1.45),
            decoration: InputDecoration(
              hintText: GlossaryPage.example,
              hintMaxLines: 8,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(20), borderSide: const BorderSide(color: Tokens.border)),
              enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(20), borderSide: const BorderSide(color: Tokens.border)),
            ),
          ),
          const SizedBox(height: 12),
          if (problem != null)
            Notice(icon: CupertinoIcons.exclamationmark_circle_fill, color: Tokens.danger, text: problem)
          else if (empty)
            Notice(
              icon: CupertinoIcons.wand_stars,
              text: t('settings.glossary.blank'),
              action: TextButton(
                style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: const Size(0, 32)),
                onPressed: () => setState(() => controller.text = GlossaryPage.example),
                child: Text(t('settings.glossary.add_example')),
              ),
            )
          else
            Notice(icon: CupertinoIcons.checkmark_circle_fill, color: Tokens.success, text: t('settings.glossary.read', [terms, keep])),
          GroupFooter(t('settings.glossary.footer')),
        ],
      ),
    );
  }
}
