// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import 'bridge.dart';
import 'l10n.dart';
import 'main.dart';
import 'ui.dart';

/// Altyazı geçmişi: çevrilen son satırlar (en yenisi başta), bilgisayardaki "Altyazı geçmişi" sekmesinin karşılığı.
/// Hızlı geçen bir satır sonradan buradan okunur. Satıra dokununca kopyalanır; üstteki düğmeler hepsini kopyalar ya da
/// geçmişi siler. Çeviri açıkken liste birkaç saniyede bir yenilenir.
class HistoryPage extends StatefulWidget {
  const HistoryPage({super.key, this.refresh = const Duration(seconds: 3)});

  /// Listenin yenilenme aralığı (testlerde kısaltılır).
  final Duration refresh;

  @override
  State<HistoryPage> createState() => _HistoryPageState();
}

class _HistoryPageState extends State<HistoryPage> {
  List<HistoryEntry>? entries;
  Timer? timer;

  @override
  void initState() {
    super.initState();
    load();
    timer = Timer.periodic(widget.refresh, (_) => load());
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> load() async {
    List<HistoryEntry> loaded;
    try {
      loaded = await Bridge.history();
    } catch (_) {
      loaded = const [];
    }
    if (mounted) setState(() => entries = loaded);
  }

  void copy(String text, String message) {
    Clipboard.setData(ClipboardData(text: text));
    toast(context, message);
  }

  Future<void> clear() async {
    final confirmed = await confirmApple(
      context,
      title: t('history.clear.title'),
      message: t('history.clear.text'),
      action: t('history.clear'),
      destructive: true,
    );
    if (!confirmed) return;
    await Bridge.clearHistory();
    await load();
  }

  @override
  Widget build(BuildContext context) {
    final list = entries;
    final empty = list != null && list.isEmpty;
    return GlassScaffold(
      title: t('history.title'),
      body: list == null
          ? const Center(child: CupertinoActivityIndicator(radius: 14))
          : ListView(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.only(left: 4),
                        child: Text(tn('history.count', list.length), style: Txt.footnote.copyWith(color: Tokens.textSecondary)),
                      ),
                    ),
                    PillButton(
                      key: const ValueKey('history-copy'),
                      label: t('history.copy_all'),
                      onTap: empty ? null : () => copy(HistoryEntry.plainText(list), t('history.copied_all')),
                    ),
                    const SizedBox(width: 6),
                    PillButton(
                      key: const ValueKey('history-clear'),
                      label: t('history.clear'),
                      color: Tokens.danger,
                      onTap: empty ? null : clear,
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                if (empty)
                  Glass(
                    padding: const EdgeInsets.fromLTRB(18, 18, 18, 18),
                    child: Text(t('history.empty'), style: Txt.subhead.copyWith(color: Tokens.textSecondary)),
                  )
                else
                  Group(
                    children: [
                      for (final entry in list)
                        _HistoryRow(entry: entry, onTap: () => copy('${entry.translation}\n${entry.source}', t('history.copied'))),
                    ],
                  ),
                GroupFooter(t('history.footer')),
              ],
            ),
    );
  }
}

/// Geçmişteki satır: üstte çeviri, altında oyundaki metin; sağ üstte saat (ve paketten geldiyse etiket).
class _HistoryRow extends StatelessWidget {
  const _HistoryRow({required this.entry, required this.onTap});

  final HistoryEntry entry;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final source = entry.source.replaceAll('\n', ' ');
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 11, 14, 11),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text(entry.timeLabel(), style: Txt.caption.copyWith(color: Tokens.textSecondary, fontFeatures: const [FontFeature.tabularFigures()])),
                  if (entry.fromPack) ...[
                    const SizedBox(width: 8),
                    Text(t('history.pack'), style: Txt.caption.copyWith(color: Tokens.success, fontWeight: FontWeight.w600)),
                  ],
                ],
              ),
              const SizedBox(height: 3),
              SizedBox(
                width: double.infinity,
                child: Text(entry.translation, textDirection: dirOf(entry.translation), style: Txt.callout.copyWith(fontWeight: FontWeight.w500)),
              ),
              const SizedBox(height: 2),
              SizedBox(
                width: double.infinity,
                child: Text(source, textDirection: dirOf(source), style: Txt.footnote.copyWith(color: Tokens.textSecondary)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
