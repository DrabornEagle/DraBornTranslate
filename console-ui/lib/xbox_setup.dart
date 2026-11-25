// SPDX-License-Identifier: AGPL-3.0-only
part of 'setup_page.dart';

/// Xbox (Beta) kurulumu: 1) Microsoft girişi (cihaz kodu), 2) hesaptaki Xbox'lardan birini seç.
/// PSN, ikinci kullanıcı ve eşleştirme kodu yoktur: Xbox ev yayını kendi hesabınla çalışır.
class _XboxSetup extends StatefulWidget {
  const _XboxSetup({required this.setup, required this.onChanged});

  final SetupInfo setup;
  final Future<void> Function() onChanged;

  @override
  State<_XboxSetup> createState() => _XboxSetupState();
}

class _XboxSetupState extends State<_XboxSetup> {
  // Giriş
  XboxSignInCode? code;
  bool starting = false;
  String? signInError;

  // Xbox listesi
  List<XboxConsoleInfo>? consoles;
  bool loading = false;
  bool needsSignIn = false;
  String? listError;
  String? selectedId;
  bool saving = false;

  @override
  void initState() {
    super.initState();
    if (widget.setup.xboxSignedIn) loadConsoles();
  }

  @override
  void didUpdateWidget(_XboxSetup old) {
    super.didUpdateWidget(old);
    if (widget.setup.xboxSignedIn && !old.setup.xboxSignedIn) loadConsoles();
  }

  @override
  void dispose() {
    // Ekrandan çıkılırsa (konsol türü değişti) arka plandaki kod beklemesi de biter.
    if (code != null || starting) Bridge.xboxCancelSignIn();
    super.dispose();
  }

  Future<void> signIn() async {
    if (starting || code != null) return;
    setState(() {
      starting = true;
      signInError = null;
    });
    try {
      final challenge = await Bridge.xboxStartSignIn();
      if (!mounted) return;
      setState(() {
        starting = false;
        code = challenge;
      });
      final done = await Bridge.xboxAwaitSignIn();
      if (!mounted) return;
      setState(() => code = null);
      if (done) await widget.onChanged();
    } on XboxFailure catch (failure) {
      if (!mounted) return;
      setState(() {
        starting = false;
        code = null;
        signInError = nt(failure.message);
      });
    }
  }

  /// Kod panoya kopyalanır ve Microsoft'un sayfası telefonun tarayıcısında açılır; kod orada yapıştırılır.
  Future<void> openMicrosoft() async {
    final current = code;
    if (current == null) return;
    await Clipboard.setData(ClipboardData(text: current.userCode));
    final opened = await TvBridge.openUrl(current.verificationUri);
    if (!mounted) return;
    toast(context, opened ? t('xbox.toast.copied') : t('xbox.toast.failed'));
  }

  Future<void> cancelSignIn() async {
    await Bridge.xboxCancelSignIn();
    if (mounted) setState(() => code = null);
  }

  Future<void> signOut() async {
    await Bridge.xboxSignOut();
    if (!mounted) return;
    setState(() {
      consoles = null;
      selectedId = null;
      listError = null;
      needsSignIn = false;
    });
    await widget.onChanged();
  }

  Future<void> loadConsoles() async {
    if (loading) return;
    setState(() {
      loading = true;
      listError = null;
    });
    final result = await Bridge.xboxConsoles();
    if (!mounted) return;
    setState(() {
      loading = false;
      consoles = result.consoles;
      listError = result.error == null ? null : nt(result.error);
      needsSignIn = result.needsSignIn;
      final usable = result.consoles.where((console) => console.streamingEnabled).toList();
      if (usable.every((console) => console.id != selectedId)) selectedId = usable.length == 1 ? usable.first.id : null;
    });
  }

  Future<void> choose() async {
    final console = consoles?.where((item) => item.id == selectedId && item.streamingEnabled).firstOrNull;
    if (console == null) {
      setState(() => listError = t('xbox.err.choose'));
      return;
    }
    setState(() => saving = true);
    await Bridge.xboxSelectConsole(console);
    await widget.onChanged();
    if (mounted) setState(() => saving = false);
  }

  @override
  Widget build(BuildContext context) {
    final signedIn = widget.setup.xboxSignedIn;
    final list = consoles;
    final current = code;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _Stepper(step: signedIn ? 2 : 1),
        const SizedBox(height: 22),
        _StepCard(
          icon: CupertinoIcons.person_fill,
          title: t('xbox.signin.title'),
          subtitle: t('xbox.signin.subtitle'),
          state: signedIn ? _StepState.done : _StepState.active,
          doneChild: Row(
            children: [
              Expanded(
                child: Notice(icon: CupertinoIcons.checkmark_circle_fill, color: Tokens.success, text: t('xbox.signin.done'), compact: true),
              ),
              const SizedBox(width: 4),
              TextButton(onPressed: signOut, child: Text(t('setup.change'))),
            ],
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                t('xbox.signin.body'),
                style: const TextStyle(color: Tokens.textSecondary, height: 1.45),
              ),
              const SizedBox(height: 14),
              Notice(
                icon: CupertinoIcons.lock_fill,
                text: t('xbox.signin.privacy'),
              ),
              const SizedBox(height: 14),
              Notice(
                icon: CupertinoIcons.lab_flask,
                color: Tokens.warning,
                text: t('xbox.signin.beta'),
              ),
              if (current != null) ...[
                const SizedBox(height: 16),
                Text(t('xbox.signin.code'), style: const TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 12),
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: Tokens.inset,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: Tokens.accentText, width: 1.4),
                  ),
                  child: Column(
                    children: [
                      SelectableText(
                        current.userCode,
                        style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w800, letterSpacing: 4, color: Tokens.accentText),
                      ),
                      const SizedBox(height: 4),
                      Text(_shortUrl(current.verificationUri), style: const TextStyle(color: Tokens.textSecondary, fontSize: 13.5)),
                    ],
                  ),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2)),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        t('xbox.signin.waiting'),
                        style: const TextStyle(color: Tokens.textSecondary, fontSize: 13.5),
                      ),
                    ),
                  ],
                ),
              ],
              if (signInError != null) ...[
                const SizedBox(height: 14),
                Notice(icon: CupertinoIcons.exclamationmark_circle_fill, color: Tokens.danger, text: signInError!),
              ],
              const SizedBox(height: 16),
              if (current == null)
                GlowButton(
                  onPressed: starting ? null : signIn,
                  busy: starting,
                  icon: CupertinoIcons.arrow_right_circle_fill,
                  label: starting ? t('xbox.signin.starting') : t('xbox.signin.title'),
                )
              else ...[
                GlowButton(onPressed: openMicrosoft, icon: CupertinoIcons.doc_on_clipboard, label: t('xbox.signin.open')),
                Align(child: TextButton(onPressed: cancelSignIn, child: Text(t('setup.cancel')))),
              ],
            ],
          ),
        ),
        const SizedBox(height: 14),
        _StepCard(
          icon: CupertinoIcons.gamecontroller_fill,
          title: t('xbox.choose.title'),
          subtitle: t('xbox.choose.subtitle'),
          waitingText: t('xbox.choose.wait'),
          state: signedIn ? _StepState.active : _StepState.waiting,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _Instruction(number: 'a', text: t('xbox.choose.a')),
              _Instruction(number: 'b', text: t('xbox.choose.b')),
              _Instruction(number: 'c', text: t('xbox.choose.c')),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(child: Text(t('xbox.choose.list'), style: const TextStyle(fontWeight: FontWeight.w600))),
                  TextButton.icon(
                    onPressed: loading ? null : loadConsoles,
                    icon: loading
                        ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(CupertinoIcons.arrow_clockwise, size: 18),
                    label: Text(loading ? t('setup.searching') : t('setup.refresh')),
                  ),
                ],
              ),
              if (list != null)
                for (final console in list)
                  _XboxConsoleTile(
                    console: console,
                    selected: selectedId == console.id,
                    onTap: console.streamingEnabled ? () => setState(() => selectedId = console.id) : null,
                  ),
              if (list != null && list.isEmpty && listError == null && !loading)
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: Notice(
                    icon: CupertinoIcons.exclamationmark_triangle_fill,
                    color: Tokens.warning,
                    text: t('xbox.choose.none'),
                  ),
                ),
              if (list != null && list.any((console) => !console.streamingEnabled))
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: Notice(
                    icon: CupertinoIcons.info_circle_fill,
                    text: t('xbox.choose.disabled'),
                  ),
                ),
              if (listError != null) ...[
                Notice(
                  icon: CupertinoIcons.exclamationmark_circle_fill,
                  color: Tokens.danger,
                  text: listError!,
                  action: needsSignIn
                      ? TextButton(
                          style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: const Size(0, 32)),
                          onPressed: signOut,
                          child: Text(t('xbox.choose.resignin'), style: const TextStyle(fontWeight: FontWeight.w600)),
                        )
                      : null,
                ),
                const SizedBox(height: 10),
              ],
              const SizedBox(height: 8),
              GlowButton(onPressed: saving || loading ? null : choose, busy: saving, label: t('xbox.choose.use')),
            ],
          ),
        ),
      ],
    );
  }

  /// "https://www.microsoft.com/link" → "microsoft.com/link" (ekranda kısa dursun).
  static String _shortUrl(String url) => url.replaceFirst(RegExp(r'^https?://(www\.)?'), '');
}

class _XboxConsoleTile extends StatelessWidget {
  const _XboxConsoleTile({required this.console, required this.selected, required this.onTap});

  final XboxConsoleInfo console;
  final bool selected;

  /// null: seçilemez (konsolda uzak özellikler kapalı).
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final usable = console.streamingEnabled;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Opacity(
        opacity: usable ? 1 : 0.6,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          decoration: BoxDecoration(
            color: selected ? Tokens.accentSoft : Tokens.inset,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: selected ? Tokens.accentText : Tokens.border, width: selected ? 1.8 : 1),
          ),
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: onTap,
              borderRadius: BorderRadius.circular(16),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
                child: Row(
                  children: [
                    Container(
                      width: 20,
                      height: 20,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        border: Border.all(color: selected ? Tokens.accentText : Tokens.textSecondary, width: 1.8),
                      ),
                      child: selected
                          ? Container(width: 10, height: 10, decoration: const BoxDecoration(color: Tokens.accentText, shape: BoxShape.circle))
                          : null,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text.rich(
                        TextSpan(
                          children: [
                            TextSpan(text: console.label, style: const TextStyle(fontWeight: FontWeight.w600)),
                            const TextSpan(text: '  ·  ', style: TextStyle(color: Tokens.textSecondary)),
                            TextSpan(
                              text: usable ? t('xbox.remote.on') : t('xbox.remote.off'),
                              style: TextStyle(color: usable ? Tokens.success : Tokens.warning, fontWeight: FontWeight.w600),
                            ),
                          ],
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
