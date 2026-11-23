// SPDX-License-Identifier: AGPL-3.0-only

using System.ComponentModel;
using System.Windows;
using System.Windows.Interop;
using GameTranslator.App.Services;
using GameTranslator.App.Views;
using GameTranslator.Shell;
using Microsoft.Win32;

namespace GameTranslator.App;

/// <summary>Uygulamanın tek penceresi: kurulum bitmediyse sihirbaz, bittiyse ana ekran.</summary>
public partial class ShellWindow : Window
{
    private readonly AppController controller;
    private bool showingMain;
    private GlobalHotkeys? hotkeys;

    public ShellWindow(AppController controller)
    {
        this.controller = controller;
        InitializeComponent();
        // Pencere başlığında sürüm: görev çubuğunda ve ekran görüntülerinde hangi sürüm olduğu görülür
        Title = $"GameTranslator {Services.AppInfo.Version}";
        controller.PropertyChanged += OnChanged;
        // Bilgisayar uykudan uyanınca bağlantılar hemen yenilenir.
        SystemEvents.PowerModeChanged += OnPowerModeChanged;
        // Num 0, 1, 2, 3, 5, 8, 9 kısayolları yalnız çeviri sürerken tutulur.
        SourceInitialized += (_, _) =>
        {
            try
            {
                hotkeys = new GlobalHotkeys(new WindowInteropHelper(this).Handle, OnHotkey);
                hotkeys.Enabled = controller.IsRunning;
            }
            catch { }
        };
        // Bilgisayarda gösterilecek çeviriler küçük, üstte duran bir kutuda çıkar.
        controller.PcResultReady += PcResultWindow.Display;
        // Canlı oyun içi Türkçe altyazıyı doğrudan bilgisayar ekranında (HUD overlay) göster
        Action<string, int, GameTranslator.Core.TextBox?> overlayHandler = (text, ms, box) => DesktopSubtitleOverlay.Display(text, ms, controller.Settings.Style, box);
        controller.LiveSubtitleShown += overlayHandler;
        controller.SubtitlesCleared += DesktopSubtitleOverlay.Clear;
        // Sürekli okunan ek alanlar da bilgisayar ekranında, oyundaki yazının üstünde (TV ve OBS ile aynı görünüm)
        Action<string, string, GameTranslator.Core.TextBox?, double?> regionHandler = (id, text, box, line) =>
            DesktopRegionOverlay.Show(id, text, box, line, controller.Settings.Style);
        controller.RegionShown += regionHandler;
        controller.RegionsCleared += DesktopRegionOverlay.Clear;
        DesktopSubtitleOverlay.Bar = controller.Settings.PcBar;
        DesktopRegionOverlay.Offsets = controller.Settings.PcRegionOffsets ?? new Dictionary<string, Shell.PcOffset>();
        DesktopSubtitleOverlay.Initialize();
        Closed += (_, _) =>
        {
            controller.PcResultReady -= PcResultWindow.Display;
            controller.LiveSubtitleShown -= overlayHandler;
            controller.SubtitlesCleared -= DesktopSubtitleOverlay.Clear;
            controller.RegionShown -= regionHandler;
            controller.RegionsCleared -= DesktopRegionOverlay.Clear;
            DesktopSubtitleOverlay.Clear();
            DesktopRegionOverlay.Clear();
            hotkeys?.Dispose();
            SystemEvents.PowerModeChanged -= OnPowerModeChanged;
            controller.Dispose();
        };
        Controls.LanguageButton.UiLanguageChanged += OnUiLanguageChanged;
        Closed += (_, _) => Controls.LanguageButton.UiLanguageChanged -= OnUiLanguageChanged;
        ShowScreen(force: true);
    }

    /// <summary>Arayüz dili değişti: ekran yeni dille yeniden kurulur; kurulum sihirbazı kaldığı adımda açılır.</summary>
    private void OnUiLanguageChanged()
    {
        var step = (Host.Content as OnboardingView)?.CurrentStep;
        ShowScreen(force: true);
        if (step is int n && Host.Content is OnboardingView view) view.ShowStep(n);
    }

    private void OnChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(AppController.Step)) ShowScreen();
        if (e.PropertyName == nameof(AppController.IsRunning) && hotkeys is not null) hotkeys.Enabled = controller.IsRunning;
    }

    private void OnHotkey(HotkeyAction action)
    {
        switch (action)
        {
            case HotkeyAction.ExtraRegion1: _ = controller.TranslateExtraRegionAsync(1); break;
            case HotkeyAction.ExtraRegion2: _ = controller.TranslateExtraRegionAsync(2); break;
            case HotkeyAction.ExtraRegion3: _ = controller.TranslateExtraRegionAsync(3); break;
            case HotkeyAction.PauseSubtitles: controller.ToggleSubtitles(); break;
            case HotkeyAction.Snapshot: controller.RequestSnapshot(); break;
            case HotkeyAction.FreeArea: FreeAreaWindow.Open(controller); break;
            case HotkeyAction.Screen: _ = controller.TranslateScreenAsync(); break;
        }
    }

    private void ShowScreen(bool force = false)
    {
        var main = controller.Step == OnboardingStep.Done;
        if (!force && main == showingMain) return;
        showingMain = main;
        Host.Content = main ? new MainView(controller) : new OnboardingView(controller);
        // Sürüm duyurusu: ana ekran ilk açıldığında bir kez (pencere çizildikten sonra).
        if (main && Services.ReleaseNotice.ShouldShow(controller.Settings.TargetLanguage))
        {
            Services.ReleaseNotice.MarkSeen();
            Dispatcher.BeginInvoke(() => Views.NoticeWindow.Open(this), System.Windows.Threading.DispatcherPriority.ApplicationIdle);
        }
    }

    private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode == PowerModes.Resume) Dispatcher.Invoke(controller.SystemResumed);
    }
}
