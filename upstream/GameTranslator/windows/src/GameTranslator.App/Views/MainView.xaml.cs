// SPDX-License-Identifier: AGPL-3.0-only

using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Navigation;
using System.Windows.Threading;
using GameTranslator.App.Controls;
using GameTranslator.App.Services;
using GameTranslator.Core;
using GameTranslator.Core.LgTv;
using GameTranslator.RemotePlay;
using GameTranslator.Shell;
using GameTranslator.Translation;
using Microsoft.Win32;

namespace GameTranslator.App.Views;

/// <summary>Altyazı geçmişi öğesi.</summary>
public sealed class SubtitleHistoryItem
{
    public required string Turkish { get; init; }
    public required string English { get; init; }
    public required string Time { get; init; }
    /// <summary>Satır oyun paketinden mi geldi (insan çevirisi) yoksa çeviri motorundan mı.</summary>
    public bool FromPack { get; init; }
    public string Summary => $"[{Time}] {(FromPack ? "📦" : "📺")} {Turkish}  |  🎮 {English}";
}

/// <summary>Ana ekran: Mac sürümüyle birebir aynı 2 panelli modern arayüz ve 5 sekme.</summary>
public partial class MainView : UserControl
{
    private readonly AppController controller;
    private readonly DispatcherTimer connectionWatch = new() { Interval = TimeSpan.FromSeconds(5) };
    private readonly ObservableCollection<SubtitleHistoryItem> history = new();
    private bool loading = true;
    private string selectedColor = "#FFFFFF";
    private bool isMutePc = true;
    private string selectedPreset = "BottomCenter";
    private int isRenderingLiveFrame = 0;
    private DateTime lastThumbnailUpdate = DateTime.MinValue;

    public MainView(AppController controller)
    {
        this.controller = controller;
        DataContext = controller;
        InitializeComponent();
        RenderGuide();
        Action gpuChanged = () => Dispatcher.BeginInvoke(() => { UpdateGpuUi(); Refresh(); });
        if (controller.Settings.Engine == TranslationEngine.DirectML && !controller.Settings.OnlineTranslation &&
            controller.Settings.SourceLanguage.StartsWith("en", StringComparison.OrdinalIgnoreCase) && Languages.IsTurkish(controller.Settings.TargetLanguage) &&
            !new GameTranslator.Translation.ModelStore(System.IO.Path.Combine(AppPaths.Models, "en-tr")).IsInstalled)
            LocalTranslation.StartModelDownload();
        LocalTranslation.CudaChanged += gpuChanged;
        Unloaded += (_, _) => LocalTranslation.CudaChanged -= gpuChanged;

        HistoryListBox.ItemsSource = history;
        LanguageSwitch.Controller = controller;
        LanguageSwitch.TargetChanged += () => ShowLanguages(controller.Settings);
        controller.PropertyChanged += OnChanged;
        connectionWatch.Tick += async (_, _) => await controller.CheckConnectionsAsync();

        // Geçmiş denetleyicide tutulur ve history.json'a yazılır; önceki oturumların satırları da görünür.
        ReloadHistory();

        Loaded += (_, _) =>
        {
            connectionWatch.Start();
            LoadSettings();
            UpdateOcrBox();
            UpdateTvPreview();
        };

        Action patchUpdatedHandler = () =>
        {
            if (Dispatcher.HasShutdownStarted || Dispatcher.HasShutdownFinished) return;
            Dispatcher.BeginInvoke(UpdatePatchUi);
        };
        CommunityPatchManager.Shared.PatchesUpdated += patchUpdatedHandler;
        // Altyazı kutusu fareyle yeniden çizilince (çift tık) ayar kaydedilir
        DesktopSubtitleOverlay.BarEdited += OnOverlayBarEdited;

        Unloaded += (_, _) =>
        {
            controller.PropertyChanged -= OnChanged;
            connectionWatch.Stop();
            CommunityPatchManager.Shared.PatchesUpdated -= patchUpdatedHandler;
            DesktopSubtitleOverlay.BarEdited -= OnOverlayBarEdited;
        };

        Refresh();
        FillGames();
        // Bilgisayardaki altyazı katmanı kayıtlı ayardan; hiç seçilmemişse "bilgisayar ekranı" hedefinde açık
        DesktopSubtitleOverlay.IsOverlayEnabled = controller.Settings.PcOverlay ?? (controller.Tv?.Name == PcScreenTarget);
        UpdateOverlayStatusUi(DesktopSubtitleOverlay.IsOverlayEnabled);
        // Sürüm: ana ekranın altında ve Ayarlar'ın kenar çubuğunda (destek isterken hangi sürüm olduğu sorulur)
        HomeVersionText.Text = Localizer.Current.Format("Version %@", AppInfo.Version);
        SettingsVersionText.Text = Localizer.Current.Format("Version %@", AppInfo.Version);
        ShowUpdate();
        _ = controller.CheckForUpdateAsync();
        // Uygulama günlerce açık kalabiliyor: yeni sürüme arada bir yeniden bakılır
        updateWatch.Tick += async (_, _) => await controller.CheckForUpdateAsync();
        Loaded += (_, _) => updateWatch.Start();
        Unloaded += (_, _) => updateWatch.Stop();
        // Oyun paketleri: açılışta bakılır (App), açık kaldıkça yeni sürüm bakışıyla birlikte; bekleyen varsa bant sorar
        Action packsChanged = () =>
        {
            if (Dispatcher.HasShutdownStarted || Dispatcher.HasShutdownFinished) return;
            Dispatcher.BeginInvoke(ShowPacks);
        };
        Services.PackUpdates.Changed += packsChanged;
        Unloaded += (_, _) => Services.PackUpdates.Changed -= packsChanged;
        updateWatch.Tick += (_, _) => _ = Task.Run(Services.PackUpdates.CheckAsync);
        ShowPacks();
        // Kaç kişi oynuyor: açılışta ve arada bir
        activeWatch.Tick += async (_, _) => await RefreshActiveAsync();
        Loaded += async (_, _) => { activeWatch.Start(); await RefreshActiveAsync(); };
        Unloaded += (_, _) => activeWatch.Stop();
    }

    private readonly DispatcherTimer updateWatch = new() { Interval = TimeSpan.FromHours(6) };
    private readonly DispatcherTimer activeWatch = new() { Interval = PoolActivity.RefreshInterval };

    /// <summary>
    /// Ana ekrandaki "şu an X kişi oynuyor" satırı. Sayı kendi havuzumuzdan gelir; havuzu kapatan kullanıcının
    /// uygulaması sunucuya hiç sormaz ve satır gizlenir.
    /// </summary>
    private async Task RefreshActiveAsync()
    {
        if (!controller.Settings.SharePool)
        {
            ActivePanel.Visibility = Visibility.Collapsed;
            return;
        }
        var activity = await PoolActivity.FetchAsync(LinePool.OwnEndpointOrOverride);
        ShowActive(controller.Settings.SharePool ? activity : null);
    }

    /// <summary>Sayıyı gösterir (null ya da hiç kimse yoksa gizler); ekran görüntüsü aracı da örnek sayıyla çağırır.</summary>
    internal void ShowActive(PoolActivity? activity)
    {
        // Az sayı gösterilmez: "şu an" kısmı 50 kişinin altındaysa yalnız son 24 saat, o da 50'nin altındaysa hiçbiri
        if (activity is null || activity.Today < PoolActivity.MinShown)
        {
            ActivePanel.Visibility = Visibility.Collapsed;
            return;
        }
        var text = Localizer.Current;
        var culture = System.Globalization.CultureInfo.GetCultureInfo(text.Language == "tr" ? "tr-TR" : "en-US");
        var today = activity.Today.ToString("N0", culture);
        ActiveText.Text = activity.Now >= PoolActivity.MinShown
            ? text.Format("%@ people are playing with GameTranslator right now", activity.Now.ToString("N0", culture))
              + " · " + text.Format("%@ in the last 24 hours", today)
            : text.Format("%@ people played with GameTranslator in the last 24 hours", today);
        ActivePanel.Visibility = Visibility.Visible;
    }

    /// <summary>Yeni sürüm bandı: sürüm numarası, ne olacağı, indirme ilerlemesi ve hata.</summary>
    private void ShowUpdate()
    {
        if (UpdateBanner == null) return;
        var version = controller.UpdateVersion;
        UpdateBanner.Visibility = version is null ? Visibility.Collapsed : Visibility.Visible;
        if (version is null) return;
        var text = Localizer.Current;
        UpdateTitle.Text = text.Format("GameTranslator %@ is available", version);
        UpdateHint.Text = controller.UpdateError is { Length: > 0 } error
            ? text.Format("The update could not be installed: %@", error)
            : controller.Updating
                ? text["Downloading the update; the app will reopen by itself."]
                : controller.CanUpdateItself
                    ? text["Your settings and PS5 pairing are kept. Translation stops while the update is installed."]
                    : text["This copy cannot update itself; the download page will open."];
        UpdateButton.IsEnabled = !controller.Updating;
        UpdateButton.Content = controller.CanUpdateItself ? text["Update"] : text["Download"];
        UpdateProgressBar.Visibility = controller.Updating ? Visibility.Visible : Visibility.Collapsed;
        UpdateProgressBar.Value = controller.UpdateProgress;
    }

    private async void OnInstallUpdate(object sender, RoutedEventArgs e) => await controller.InstallUpdateAsync();

    private DispatcherTimer? packDoneTimer;

    /// <summary>Oyun paketi bandı: yeni / güncellenen paketler, indirme, sonuç ("güncellendi" birkaç saniye görünür).</summary>
    private void ShowPacks()
    {
        if (PackBanner == null) return;
        var text = Localizer.Current;
        var pending = Services.PackUpdates.Pending;
        var installing = Services.PackUpdates.Installing;
        var done = Services.PackUpdates.JustInstalled;
        PackProgress.Visibility = installing ? Visibility.Visible : Visibility.Collapsed;
        PackButton.IsEnabled = !installing;
        PackLaterButton.Visibility = installing ? Visibility.Collapsed : Visibility.Visible;

        if (installing)
        {
            PackBanner.Visibility = Visibility.Visible;
            PackHint.Text = text["Downloading…"];
            return;
        }
        if (pending.Count == 0 && done.Count > 0 && !Services.PackUpdates.Failed)
        {
            PackBanner.Visibility = Visibility.Visible;
            PackTitle.Text = text["Game packs updated"];
            PackHint.Text = string.Join(", ", done);
            PackButton.Visibility = Visibility.Collapsed;
            PackLaterButton.Visibility = Visibility.Collapsed;
            packDoneTimer?.Stop();
            packDoneTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(8) };
            packDoneTimer.Tick += (_, _) =>
            {
                packDoneTimer!.Stop();
                Services.PackUpdates.ForgetInstalled();
                ShowPacks();
            };
            packDoneTimer.Start();
            return;
        }
        if (pending.Count == 0)
        {
            PackBanner.Visibility = Visibility.Collapsed;
            return;
        }

        PackBanner.Visibility = Visibility.Visible;
        PackButton.Visibility = Visibility.Visible;
        PackButton.Content = text["Update"];
        PackTitle.Text = pending.Count == 1
            ? text.Format(pending[0].IsNew ? "New game pack: %@" : "Game pack updated: %@", pending[0].Pack.Title)
            : text.Format("%@ game packs are new or updated", pending.Count.ToString());
        var question = Services.PackUpdates.Failed
            ? text["Some packs could not be downloaded. Try again?"]
            : text["Do you want to update? Translation keeps running."];
        PackHint.Text = pending.Count == 1
            ? question
            : string.Join(", ", pending.Select(p => p.IsNew ? text.Format("%@ (new)", p.Pack.Title) : p.Pack.Title)) + "\n" + question;
    }

    private async void OnPackUpdate(object sender, RoutedEventArgs e) => await Task.Run(Services.PackUpdates.InstallAsync);

    private void OnPackLater(object sender, RoutedEventArgs e) => Services.PackUpdates.Postpone();

    /// <summary>Ekran görüntüsü aracı için: paket bandını örnek paketlerle gösterir.</summary>
    internal void ShowPackPreview()
    {
        var text = Localizer.Current;
        PackBanner.Visibility = Visibility.Visible;
        PackTitle.Text = text.Format("%@ game packs are new or updated", "2");
        PackHint.Text = text.Format("%@ (new)", "Grand Theft Auto VI") + ", Grand Theft Auto V\n" + text["Do you want to update? Translation keeps running."];
    }

    /// <summary>Ekran görüntüsü aracı (--render-screens) bandı çizebilsin diye: yeni sürüm varmış gibi gösterir.</summary>
    internal void ShowUpdatePreview(string version)
    {
        UpdateBanner.Visibility = Visibility.Visible;
        UpdateTitle.Text = Localizer.Current.Format("GameTranslator %@ is available", version);
        UpdateHint.Text = Localizer.Current["Your settings and PS5 pairing are kept. Translation stops while the update is installed."];
        UpdateButton.Content = Localizer.Current["Update"];
    }

    private void LoadSettings()
    {
        loading = true;
        var s = controller.Settings;

        LoadStyleControls();
        ShowPcBar((s.PcBar ?? new()).Normalized());

        // Kırpma Alanı (CropRegion)
        CropTopSlider.Value = s.Region.Top;
        CropBottomSlider.Value = s.Region.Bottom;
        CropLeftSlider.Value = s.Region.Left;
        CropRightSlider.Value = s.Region.Right;

        if (s.AutoRegion)
        {
            selectedPreset = "Auto";
            ShortcutPresetText.Text = Localizer.Current["Automatic"];
        }

        CropTopLabel.Text = $"%{Math.Round(s.Region.Top * 100)}";
        CropBottomLabel.Text = $"%{Math.Round(s.Region.Bottom * 100)}";
        CropLeftLabel.Text = $"%{Math.Round(s.Region.Left * 100)}";
        CropRightLabel.Text = $"%{Math.Round(s.Region.Right * 100)}";
        ShowExtraAreas();

        // Checkboxlar
        SharePoolCheck.IsChecked = s.SharePool;
        DubbingCheck.IsChecked = s.DubbingEnabled;
        MutePcCheck.IsChecked = isMutePc;

        ShowLanguages(s);

        // Dil Dropdown
        foreach (ComboBoxItem item in LanguageBox.Items)
        {
            if (string.Equals((string)item.Tag, s.SourceLanguage, StringComparison.OrdinalIgnoreCase))
            {
                LanguageBox.SelectedItem = item;
                break;
            }
        }

        ObsText.Text = $"OBS overlay: http://localhost:{controller.ServerPort}/obs";
        UpdateSecondScreen();

        // Video Giriş Kaynağı (Remote Play / Capture Card / Desktop)
        if (s.VideoSource == VideoSourceType.CaptureCard)
        {
            SourceRadioCaptureCard.IsChecked = true;
            SourceWindowPickerCard.Visibility = Visibility.Visible;
            if (QuickSourcePickerCard != null) QuickSourcePickerCard.Visibility = Visibility.Visible;
            PopulateSourceWindows();
            if (StreamThumbnailTitle != null) StreamThumbnailTitle.Text = Localizer.Current["📹 Capture Card Live Stream"];
            if (ThumbnailStatusText != null) ThumbnailStatusText.Text = Localizer.Current["Waiting for the Capture Card"];
        }
        else if (s.VideoSource == VideoSourceType.DesktopScreen)
        {
            SourceRadioDesktop.IsChecked = true;
            SourceWindowPickerCard.Visibility = Visibility.Visible;
            if (QuickSourcePickerCard != null) QuickSourcePickerCard.Visibility = Visibility.Visible;
            PopulateSourceWindows();
            if (StreamThumbnailTitle != null) StreamThumbnailTitle.Text = Localizer.Current["🖥️ Desktop / OBS Live Stream"];
            if (ThumbnailStatusText != null) ThumbnailStatusText.Text = Localizer.Current["Waiting for the Desktop Stream"];
        }
        else if (s.VideoSource == VideoSourceType.Xbox)
        {
            SourceRadioXbox.IsChecked = true;
            ShowXboxSource();
        }
        else
        {
            SourceRadioRemotePlay.IsChecked = true;
            SourceWindowPickerCard.Visibility = Visibility.Collapsed;
            if (QuickSourcePickerCard != null) QuickSourcePickerCard.Visibility = Visibility.Collapsed;
            if (StreamThumbnailTitle != null) StreamThumbnailTitle.Text = Localizer.Current["PS5 picture"];
            if (ThumbnailStatusText != null) ThumbnailStatusText.Text = Localizer.Current["Waiting for the picture"];
        }

        if (controller.HasGeminiKey && GeminiKeyInput != null)
        {
            GeminiKeyInput.Password = "************";
        }

        UpdateModelSelectionUi();
        UpdatePatchUi();

        loading = false;
    }

    private void OnChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(AppController.UpdateVersion) or nameof(AppController.Updating)
            or nameof(AppController.UpdateProgress) or nameof(AppController.UpdateError)) ShowUpdate();
        if (e.PropertyName is nameof(AppController.IsRunning) 
            or nameof(AppController.TranslatorStatus) 
            or nameof(AppController.ModelInstalled)
            or nameof(AppController.Console)
            or nameof(AppController.Tv)
            or nameof(AppController.TvClients)
            or nameof(AppController.Ps5StatusText)
            or nameof(AppController.TvStatusText))
        {
            Refresh();
        }

        // Xbox: giriş bitti, konsol seçildi, bağlantı durumu değişti → karttaki durum güncellenir
        if (e.PropertyName is nameof(AppController.HasXboxAccount) or nameof(AppController.SelectedXboxConsole) or nameof(AppController.XboxReady)
            or nameof(AppController.IsRunning) or nameof(AppController.Ps5StatusText))
            UpdateXboxStatus();

        if (e.PropertyName == nameof(AppController.LastFrame))
        {
            if (controller.LastFrame != null)
            {
                UpdateLiveFrame(controller.LastFrame);
            }
            else
            {
                if (LiveStreamImage != null) LiveStreamImage.Source = null;
                if (ThumbnailStreamImage != null) ThumbnailStreamImage.Source = null;
                if (StreamPlaceholder != null) StreamPlaceholder.Visibility = Visibility.Visible;
                if (ThumbnailPlaceholder != null) ThumbnailPlaceholder.Visibility = Visibility.Visible;
            }
        }

        if (e.PropertyName == nameof(AppController.LastTranslation) && !string.IsNullOrWhiteSpace(controller.LastTranslation))
        {
            MonitorTurkishText.Text = controller.LastTranslation;
            MonitorEnglishText.Text = string.IsNullOrWhiteSpace(controller.LastSource) ? "Subtitles active" : controller.LastSource;
        }

        if (e.PropertyName == nameof(AppController.History)) ReloadHistory();
    }

    /// <summary>Listeyi denetleyicinin geçmişinden yeniden kurar (en çok 200 satır; satır başına bir kez çağrılır).</summary>
    private void ReloadHistory()
    {
        history.Clear();
        foreach (var entry in controller.History)
            history.Add(new SubtitleHistoryItem
            {
                Turkish = entry.Translation,
                English = entry.Source,
                Time = entry.Date.ToLocalTime().ToString(entry.Date.ToLocalTime().Date == DateTime.Today ? "HH:mm:ss" : "dd.MM HH:mm"),
                FromPack = entry.Origin == LineOrigin.Pack,
            });
    }

    private void OnClearHistory(object sender, RoutedEventArgs e) => controller.ClearHistory();

    private void Refresh()
    {
        var running = controller.IsRunning;
        // Çalışırken ana düğme gri (ikincil), kapalıyken mavi
        Toggle.SetResourceReference(BackgroundProperty, running ? "cardStrongBrush" : "accentBrush");
        Toggle.SetResourceReference(ForegroundProperty, running ? "textPrimaryBrush" : "onAccentBrush");
        ToggleGlyph.Text = running ? Glyphs.Stop : Glyphs.Play;
        ToggleButtonText.Text = running ? Localizer.Current["Stop Translation"] : Localizer.Current["Start Translation"];

        bool hasFrame = LiveStreamImage?.Source != null;
        bool isStreaming = running && hasFrame;

        StreamThumbnailDot.Fill = new SolidColorBrush(isStreaming
            ? Color.FromRgb(16, 185, 129)
            : (running ? Color.FromRgb(245, 158, 11) : Color.FromRgb(156, 163, 175)));
        StreamThumbnailBadge.Text = isStreaming ? Localizer.Current["LIVE"] : (running ? Localizer.Current["CONNECTING"] : Localizer.Current["OFF"]);
        StreamThumbnailBadge.Foreground = new SolidColorBrush(isStreaming ? Color.FromRgb(56, 189, 248) : Color.FromArgb(140, 255, 255, 255));
        var captureCard = controller.Settings.VideoSource == VideoSourceType.CaptureCard;
        ThumbnailStatusText.Text = isStreaming
            ? (captureCard ? Localizer.Current["Capture Card Active"] : Localizer.Current["PS5 Live Stream Active"])
            : (running ? (captureCard ? Localizer.Current["Waiting for the Capture Card"] : Localizer.Current["Waiting for the PS5 Stream..."]) : Localizer.Current["Waiting for the picture"]);
        LiveBadgeText.Text = isStreaming ? Localizer.Current["Live"] : (running ? Localizer.Current["Waiting"] : Localizer.Current["Idle"]);

        // PS5 Durum Satırı
        if (Ps5StatusDot != null && Ps5StatusLabel != null)
        {
            var paired = controller.Console != null;
            Ps5StatusDot.Fill = new SolidColorBrush(paired ? Color.FromRgb(16, 185, 129) : Color.FromRgb(156, 163, 175));
            Ps5StatusLabel.Text = paired ? Localizer.Current.Format("Paired (%@)", controller.Console!.Host) : Localizer.Current["Not paired"];
        }

        // TV Durum Satırı (Bağlandı: Yeşil | Bekleniyor: Sarı)
        if (TvStatusDot != null && TvStatusLabel != null)
        {
            var tvConnected = controller.TvClients > 0;
            TvStatusDot.Fill = new SolidColorBrush(tvConnected ? Color.FromRgb(16, 185, 129) : (controller.Tv != null ? Color.FromRgb(245, 158, 11) : Color.FromRgb(148, 163, 184)));
            TvStatusLabel.Text = tvConnected
                ? (controller.TvClients == 1 ? Localizer.Current.Format("%@ (Connected)", controller.Tv?.Name ?? "TV") : Localizer.Current.Format("%@ TVs Connected", controller.TvClients))
                : (controller.Tv != null ? Localizer.Current.Format("%@ (Waiting)", controller.Tv.Name) : Localizer.Current["Waiting for the TV app"]);

            if (TvActionButton != null)
            {
                TvActionButton.Content = Localizer.Current["Set up"];
            }
        }

        RefreshHome(running, isStreaming);

        if (!running)
        {
            if (LiveStreamImage != null) LiveStreamImage.Source = null;
            if (ThumbnailStreamImage != null) ThumbnailStreamImage.Source = null;
            if (StreamPlaceholder != null)
            {
                StreamPlaceholder.Visibility = Visibility.Visible;
                if (StreamPlaceholderTitle != null) StreamPlaceholderTitle.Text = Localizer.Current["No picture"];
                if (StreamPlaceholderSubtitle != null) StreamPlaceholderSubtitle.Text = Localizer.Current["Start translating to see the picture."];
            }
            if (ThumbnailPlaceholder != null) ThumbnailPlaceholder.Visibility = Visibility.Visible;
        }
        else
        {
            if (LiveStreamImage?.Source == null && StreamPlaceholder != null)
            {
                StreamPlaceholder.Visibility = Visibility.Visible;
                var fromCard = controller.Settings.VideoSource == VideoSourceType.CaptureCard;
                if (StreamPlaceholderTitle != null) StreamPlaceholderTitle.Text = fromCard ? Localizer.Current["Waiting for the Capture Card"] : Localizer.Current["Waiting for the PS5 Live Stream..."];
                if (StreamPlaceholderSubtitle != null) StreamPlaceholderSubtitle.Text = fromCard
                    ? controller.SessionState.Reason?.Detail ?? Localizer.Current["Opening the capture card; the picture will appear here automatically."]
                    : Localizer.Current["Connecting to your PS5; the picture will appear here automatically."];
            }
        }
    }

    /// <summary>
    /// Ana ekranın durumu (mobildeki gibi): tek cümlelik ipucu, PS5 → Bilgisayar → TV zinciri ve yalnız bir sorun
    /// varken görünen uyarı.
    /// </summary>
    /// <summary>Kurulumda "Bilgisayar ekranı" seçilince kaydedilen hedefin adı (TV yerine bu bilgisayar ve OBS).</summary>
    internal const string PcScreenTarget = "PC & OBS";

    /// <summary>Ayardaki görüntü hedefi okunur biçimde: "device:Elgato" → "Elgato", "obs:Kart" → "OBS: Kart".</summary>
    private static string SourceLabel(string? target) => target switch
    {
        null or "" => Localizer.Current["Automatic"],
        _ when target.StartsWith(ObsStreamSession.Prefix, StringComparison.Ordinal) => "OBS: " + target[ObsStreamSession.Prefix.Length..],
        _ when target.StartsWith(CaptureDeviceStreamSession.Prefix, StringComparison.Ordinal) => target[CaptureDeviceStreamSession.Prefix.Length..],
        _ when target.StartsWith("window:", StringComparison.Ordinal) => target["window:".Length..],
        _ => target,
    };

    /// <summary>Çalışan çeviri hattı yerel modelle mi kuruldu (çeviri başlarken model hazır mıydı).</summary>
    private bool localEngineActive;
    private bool wasRunning;

    private void RefreshHome(bool running, bool streaming)
    {
        if (!running) localEngineActive = false;
        else if (!wasRunning) localEngineActive = LocalTranslation.FastModelReady;
        wasRunning = running;
        if (HomeHint == null) return;
        var green = (Brush)FindResource("successBrush");
        var orange = (Brush)FindResource("warningBrush");
        var idle = (Brush)FindResource("idleBrush");
        var red = (Brush)FindResource("dangerBrush");
        var phase = controller.SessionState.Phase;
        // Yakalama kartı / ekran kaynağında PS5 eşleştirmesi gerekmez
        var fromCard = controller.Settings.VideoSource is VideoSourceType.CaptureCard or VideoSourceType.DesktopScreen;
        var paired = controller.Console != null || fromCard;
        var tvOn = controller.TvClients > 0;
        var pcScreen = controller.Tv?.Name == PcScreenTarget;
        var onPc = DesktopSubtitleOverlay.IsOverlayEnabled;

        // Apple tarzı: başlık tek cümle, nokta ile biter
        ToggleText.Text = (running ? Localizer.Current["Translation is on"] : Localizer.Current["Translation is off"]).TrimEnd('.') + ".";
        ToggleButtonText.Text = running ? Localizer.Current["Stop Translation"] : Localizer.Current["Start Translation"];
        var xbox = controller.Settings.VideoSource == VideoSourceType.Xbox;
        var card = controller.Settings.VideoSource is VideoSourceType.CaptureCard or VideoSourceType.DesktopScreen;
        HomeConsoleLabel.Text = xbox ? "Xbox" : card ? Localizer.Current["Capture Card"] : "PS5";
        HomeConsoleDetail.Text = xbox ? controller.SelectedXboxConsole?.Name ?? "" : card ? SourceLabel(controller.Settings.DesktopWindowName) : controller.Console?.Host ?? "";
        HomeTvDetail.Text = controller.Tv?.Name == PcScreenTarget ? Localizer.Current["Computer screen"] : controller.Tv?.Name ?? "";
        (HomePs5Text.Text, HomePs5Dot.Fill) = !paired ? (Localizer.Current["Not paired"], red)
            : streaming || phase == SessionPhase.Streaming ? (Localizer.Current["Connected"], green)
            : running ? (Localizer.Current["Connecting"], orange)
            : (Localizer.Current["Paired"], idle);
        (HomePcText.Text, HomePcDot.Fill) = running ? (Localizer.Current["Translating"], green) : (Localizer.Current["Ready"], idle);
        // "Bilgisayar ekranı" hedefinde TV beklenmez: altyazının bu ekranda açık olup olmadığı yazılır
        (HomeTvText.Text, HomeTvDot.Fill) = pcScreen
            ? (onPc ? (Localizer.Current["On screen"], green) : tvOn ? (Localizer.Current["Connected"], green) : (Localizer.Current["Off"], running ? orange : idle))
            : tvOn ? (Localizer.Current["Connected"], green) : running ? (Localizer.Current["Waiting"], orange) : (Localizer.Current["Not connected"], idle);
        HomeTvText.ToolTip = controller.Tv?.Name;
        HomePs5Text.ToolTip = controller.Console?.Host;

        HomeHint.Text = !running ? Localizer.Current["Turn on subtitles in the game, then start."]
            : pcScreen && onPc ? Localizer.Current["You can play; the translation appears over the game on this screen."]
            : pcScreen && !tvOn ? Localizer.Current["Turn on \"Subtitles on the PC Screen\" in Subtitle Appearance to see the translation on this screen."]
            : !tvOn ? Localizer.Current["Open GameTranslator on the TV; the translation appears over the game."]
            : Localizer.Current["You can play; the translation appears over the game on the TV."];

        // Uyarı yalnız bir şey yolunda değilken.
        // Yerel model seçili ama hızlı model inmemiş: çeviri geçici olarak çevrimiçi gidiyor, kullanıcı bunu görmeli
        var localChosen = controller.Settings.Engine == TranslationEngine.DirectML && !controller.Settings.OnlineTranslation &&
                          controller.Settings.SourceLanguage.StartsWith("en", StringComparison.OrdinalIgnoreCase) &&
                          Languages.IsTurkish(controller.Settings.TargetLanguage) && GameTranslator.Translation.MarianTranslator.FastRuntimeAvailable;
        var oldModel = new GameTranslator.Translation.ModelStore(System.IO.Path.Combine(AppPaths.Models, "en-tr")).IsInstalled;
        (string Title, string Text)? modelNotice = !localChosen || oldModel ? null
            : LocalTranslation.ModelDownloading ? (Localizer.Current["The local model is not downloaded yet"], Localizer.Current.Format("Downloading it: %lld%% (475 MB). Until it finishes, lines are translated online for now; restart translating when it is done.", (int)Math.Round(LocalTranslation.ModelProgress * 100)))
            : LocalTranslation.ModelError is { } modelError ? (Localizer.Current["The local model is not downloaded yet"], Localizer.Current.Format("It could not be downloaded (%@). Lines are translated online for now; the app tries again the next time it starts.", modelError))
            : LocalTranslation.FastModelReady && running && !localEngineActive ? (Localizer.Current["The local model is ready"], Localizer.Current["Stop and start translating again to switch to the local model."])
            : !LocalTranslation.FastModelReady ? (Localizer.Current["The local model is not downloaded yet"], Localizer.Current.Format("Downloading it: %lld%% (475 MB). Until it finishes, lines are translated online for now; restart translating when it is done.", 0))
            : null;
        (string Title, string Text)? notice = !paired
            ? (Localizer.Current["PS5 is not paired"], Localizer.Current["Pair the PS5 once in the Connection tab in Settings."])
            : phase == SessionPhase.Disconnected && controller.SessionState.Reason is { Kind: not DisconnectKind.Stopped } reason
                ? (fromCard ? (Localizer.Current["No picture from the capture card"], reason.Detail ?? controller.Ps5StatusText)
                            : (Localizer.Current["Could not connect to the PS5"], controller.Ps5StatusText))
                : running && !tvOn && controller.Tv is null
                    ? (Localizer.Current["No TV selected"], Localizer.Current["Add your TV in the Connection tab in Settings; for now the translation appears on the computer."])
                    : modelNotice;
        HomeNotice.Visibility = notice is null ? Visibility.Collapsed : Visibility.Visible;
        if (notice is { } n)
        {
            HomeNoticeTitle.Text = n.Title;
            HomeNoticeText.Text = n.Text;
        }
        HomePresetText.Text = ShortcutPresetText.Text;
    }

    private void UpdateLiveFrame(GameTranslator.Core.Frame frame)
    {
        // Donmayı engellemek için önceki render bitmediyse bu kareyi atla (Frame Throttling)
        if (Interlocked.CompareExchange(ref isRenderingLiveFrame, 1, 0) != 0) return;

        try
        {
            bool ocrVisible = TabOcrArea?.IsChecked == true && PanelOcrArea?.Visibility == Visibility.Visible;
            bool updateThumb = (DateTime.UtcNow - lastThumbnailUpdate).TotalMilliseconds > 1200;

            if (ocrVisible || updateThumb)
            {
                if (ocrVisible && LiveStreamImage != null)
                {
                    var bitmap = BitmapSource.Create(frame.Width, frame.Height, 96, 96, PixelFormats.Bgra32, null, frame.Pixels, frame.Stride);
                    bitmap.Freeze();
                    var resized = LiveStreamImage.Source is not { } shown || (int)shown.Width != frame.Width || (int)shown.Height != frame.Height;
                    LiveStreamImage.Source = bitmap;
                    // Kutu görüntünün çizildiği alana göre yerleşir: ilk karede ve kare boyu değişince yeniden
                    if (resized) UpdateOcrBox();
                    if (StreamPlaceholder != null) StreamPlaceholder.Visibility = Visibility.Collapsed;
                }
                if (updateThumb)
                {
                    lastThumbnailUpdate = DateTime.UtcNow;
                    var thumbBmp = BitmapSource.Create(frame.Width, frame.Height, 96, 96, PixelFormats.Bgra32, null, frame.Pixels, frame.Stride);
                    thumbBmp.Freeze();
                    if (ThumbnailStreamImage != null) ThumbnailStreamImage.Source = thumbBmp;
                    if (ThumbnailPlaceholder != null) ThumbnailPlaceholder.Visibility = Visibility.Collapsed;
                }
            }

            if (StreamThumbnailDot != null) StreamThumbnailDot.Fill = new SolidColorBrush(Color.FromRgb(16, 185, 129));
            if (StreamThumbnailBadge != null)
            {
                StreamThumbnailBadge.Text = Localizer.Current["1080p LIVE"];
                StreamThumbnailBadge.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));
            }
            if (ThumbnailStatusText != null)
            {
                ThumbnailStatusText.Text = controller.Settings.VideoSource switch
                {
                    VideoSourceType.DesktopScreen => Localizer.Current["Desktop Stream Active"],
                    VideoSourceType.CaptureCard => Localizer.Current["Capture Card Active"],
                    VideoSourceType.Xbox => Localizer.Current["Xbox Stream Active"],
                    _ => Localizer.Current["PS5 Live Stream Active"]
                };
            }
            if (LiveBadgeText != null) LiveBadgeText.Text = Localizer.Current["Live"];
        }
        catch { }
        finally
        {
            Volatile.Write(ref isRenderingLiveFrame, 0);
        }
    }

    private async void OnToggle(object sender, RoutedEventArgs e)
    {
        if (controller.IsRunning)
        {
            controller.Stop();
        }
        else
        {
            if (controller.Console == null || controller.Tv == null)
            {
                // Eğer konsol henüz eşleştirilmediyse kayıtlı yapılandırmayı kontrol et
                if (controller.Console == null)
                {
                    var savedHost = ChiakiLauncher.GetSavedHost();
                    if (!string.IsNullOrWhiteSpace(savedHost))
                    {
                        var nick = ChiakiLauncher.GetRegisteredNickname(savedHost) ?? "PS5";
                        controller.SetRegisteredConsole(new RegisteredConsole(savedHost, nick, controller.AccountId ?? new byte[16], new byte[16]));
                    }
                }

                // TV henüz seçilmediyse varsayılan Bilgisayar & OBS ekran modunu etkinleştir
                if (controller.Tv == null)
                {
                    try
                    {
                        await controller.PairAsync(new LgTv("PC & OBS", "127.0.0.1", TvBrand.AndroidTv));
                    }
                    catch { }
                }
            }
            await controller.StartAsync();
        }
    }

    // --- Sekme Yönetimi ---

    private void OnTabChanged(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        if (PanelOcrArea == null || PanelDisplay == null || PanelModels == null || PanelSystem == null || PanelGuide == null
            || PanelLanguages == null || PanelHistory == null) return;

        PanelOcrArea.Visibility = TabOcrArea.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        PanelDisplay.Visibility = TabDisplay.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        PanelModels.Visibility = TabModels.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        PanelSystem.Visibility = TabSystem.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        PanelGuide.Visibility = TabGuide.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        PanelLanguages.Visibility = TabLanguages.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        PanelHistory.Visibility = TabHistory.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;

        if (TabOcrArea.IsChecked == true) UpdateOcrBox();
        if (TabDisplay.IsChecked == true) UpdateTvPreview();
    }

    // --- Ana ekran ve Ayarlar ekranı ---

    /// <summary>Ayarlar ekranını istenen sekmeyle açar (ana ekran mobildeki gibi sadedir; ayrıntılar burada).</summary>
    private void ShowSettings(RadioButton tab)
    {
        tab.IsChecked = true;
        HomeLayer.Visibility = Visibility.Collapsed;
        SettingsLayer.Visibility = Visibility.Visible;
        if (tab == TabOcrArea) UpdateOcrBox();
        if (tab == TabDisplay) UpdateTvPreview();
        if (tab == TabSystem) UpdateSecondScreen();
    }

    /// <summary>Ekran görüntüsü aracı (--render-screens) "TV Görünümü" sekmesini çizebilsin diye.</summary>
    /// <summary>Altyazı Görünümü'nde TV ya da bilgisayar ekranı ayarları.</summary>
    private void OnDisplayTarget(object sender, RoutedEventArgs e) =>
        ShowDisplayTarget(DisplayTargetPc.IsChecked == true, obs: DisplayTargetObs.IsChecked == true);

    private void ShowDisplayTarget(bool pc, bool obs = false)
    {
        DisplayTargetPc.IsChecked = pc;
        DisplayTargetObs.IsChecked = obs && !pc;
        DisplayTargetTv.IsChecked = !pc && !obs;
        DisplayTvPart.Visibility = pc ? Visibility.Collapsed : Visibility.Visible;
        DisplayPcPart.Visibility = pc ? Visibility.Visible : Visibility.Collapsed;
        editingObs = obs && !pc;
        ObsStylePart.Visibility = editingObs ? Visibility.Visible : Visibility.Collapsed;
        // Görüntü modu yalnız Android TV uygulamasının ayarıdır; OBS sekmesinde gösterilmez.
        AndroidTvModePart.Visibility = editingObs ? Visibility.Collapsed : Visibility.Visible;
        ObsAddressHint.Text = Localizer.Current.Format("In OBS add a Browser source with the address %@ (1920 × 1080). The settings below apply only to OBS.", $"http://localhost:{controller.ServerPort}/obs");
        PreviewTitle.Text = editingObs ? Localizer.Current["LIVE OBS PREVIEW (WHERE THE TRANSLATION APPEARS)"] : Localizer.Current["LIVE TV PREVIEW (WHERE THE TRANSLATION APPEARS)"];
        TestSubtitleButton.Content = editingObs ? Localizer.Current["Send a test subtitle"] : Localizer.Current["Send a test to the TV"];
        LoadStyleControls();
    }

    /// <summary>Ekran görüntüsü aracı için: Altyazı Görünümü, bilgisayar ekranı ayarları.</summary>
    public void ShowDisplayPcTab()
    {
        ShowDisplayTab();
        ShowDisplayTarget(pc: true);
    }

    /// <summary>Ekran görüntüsü aracı için: Altyazı Görünümü, OBS ayarları.</summary>
    public void ShowDisplayObsTab()
    {
        ShowDisplayTab();
        ShowDisplayTarget(pc: false, obs: true);
    }

    public void ShowDisplayTab()
    {
        ShowSettings(TabDisplay);
        foreach (var panel in new[] { PanelOcrArea, PanelModels, PanelSystem, PanelGuide }) panel.Visibility = Visibility.Collapsed;
        PanelDisplay.Visibility = Visibility.Visible;
    }

    /// <summary>Ekran görüntüsü aracı için: Okuma Alanı ("alan"), Modeller ("modeller") ya da Rehber ("rehber") sekmesi.</summary>
    public void ShowTab(string tab)
    {
        var (button, shown) = tab switch
        {
            "modeller" => (TabModels, (FrameworkElement)PanelModels),
            "rehber" => (TabGuide, PanelGuide),
            "diller" => (TabLanguages, PanelLanguages),
            "gecmis" => (TabHistory, PanelHistory),
            _ => (TabOcrArea, PanelOcrArea),
        };
        ShowSettings(button);
        foreach (var panel in new FrameworkElement[] { PanelOcrArea, PanelDisplay, PanelModels, PanelSystem, PanelGuide, PanelLanguages, PanelHistory })
            panel.Visibility = panel == shown ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnBackHome(object sender, RoutedEventArgs e)
    {
        SettingsLayer.Visibility = Visibility.Collapsed;
        HomeLayer.Visibility = Visibility.Visible;
        Refresh();
    }

    private void OnOcrShortcut(object sender, RoutedEventArgs e) => ShowSettings(TabOcrArea);

    private void OnDisplayShortcut(object sender, RoutedEventArgs e) => ShowSettings(TabDisplay);

    private void OnGuideShortcut(object sender, RoutedEventArgs e) => ShowSettings(TabGuide);

    /// <summary>Oyun seçme penceresi (paketler yüklenmeden açılmaz; yükleme sürerken liste eksik kalırdı).</summary>
    private void OnGameShortcut(object sender, RoutedEventArgs e)
    {
        if (!CommunityPatchManager.Shared.PacksReady) return;
        GamePickerWindow.Open(Window.GetWindow(this));
        FillGames();
    }

    /// <summary>Ana ekrandaki ve ayarlardaki seçili oyun yazısı.</summary>
    private void FillGames()
    {
        var pm = CommunityPatchManager.Shared;
        var selected = pm.SelectedGameId;
        // Paketler henüz yükleniyor: ad yükleme bitince gelen olayla yazılır, arayüz beklemez.
        // Otomatik'te PS5'in bildirdiği oyun da yazılır ("Otomatik · Mafia: The Old Country").
        var name = pm.CustomGameName is { } custom ? custom
            : selected is null ? (pm.RunningGame?.Name is { } running ? $"{Localizer.Current["Automatic"]} · {running}" : Localizer.Current["Automatic"])
            : pm.PacksReady ? pm.Games.FirstOrDefault(g => g.Id == selected).Title ?? selected
            : "…";
        if (HomeGameText != null) HomeGameText.Text = name;
        if (GameSelectText != null) GameSelectText.Text = name;
    }

    private void OnSettingsButton(object sender, RoutedEventArgs e) => ShowSettings(TabOcrArea);

    // --- Tab 1: Oyun Okuma Alanı & Şablonlar ---

    private void OnPresetClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string tag) return;
        // Hazır altyazı yerleri altyazı alanı içindir: bir ek alan ayarlanıyorsa önce o bitirilir.
        if (editSlot > 0) EndExtraEdit();
        selectedPreset = tag;

        loading = true;
        switch (tag)
        {
            case "Auto":
                // Altyazının yeri kendiliğinden bulunur; aşağıdaki kutu yalnız elle seçimde kullanılır.
                ShortcutPresetText.Text = Localizer.Current["Automatic"];
                controller.Settings = controller.Settings with { AutoRegion = true };
                loading = false;
                return;
            case "BottomCenter":
                CropTopSlider.Value = 0.72;
                CropBottomSlider.Value = 0.85;
                CropLeftSlider.Value = 0.16;
                CropRightSlider.Value = 0.84;
                ShortcutPresetText.Text = Localizer.Current["Bottom Center (Standard)"];
                break;
            case "BottomWide":
                CropTopSlider.Value = 0.65;
                CropBottomSlider.Value = 0.94;
                CropLeftSlider.Value = 0.08;
                CropRightSlider.Value = 0.92;
                ShortcutPresetText.Text = Localizer.Current["Wide Bottom Band"];
                break;
            case "FullBottom":
                CropTopSlider.Value = 0.50;
                CropBottomSlider.Value = 0.98;
                CropLeftSlider.Value = 0.02;
                CropRightSlider.Value = 0.98;
                ShortcutPresetText.Text = Localizer.Current["Whole Bottom Half"];
                break;
            case "TopBanner":
                CropTopSlider.Value = 0.03;
                CropBottomSlider.Value = 0.22;
                CropLeftSlider.Value = 0.08;
                CropRightSlider.Value = 0.92;
                ShortcutPresetText.Text = Localizer.Current["Top Area (Quests)"];
                break;
            case "Custom":
                ShortcutPresetText.Text = Localizer.Current["Custom Box"];
                break;
        }

        controller.Settings = controller.Settings with
        {
            AutoRegion = false,
            Region = new CropRegion(CropTopSlider.Value, CropBottomSlider.Value, CropLeftSlider.Value, CropRightSlider.Value)
        };
        UpdateOcrBox();
        loading = false;
    }

    private void OnCropSliderChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (loading) return;
        if (editSlot > 0)
        {
            // Ek alanın boyutu: yalnız o yuvanın bölgesi değişir, sürekli okunmaya devam eder.
            controller.Settings = controller.Settings.WithExtraRegion(editSlot,
                new CropRegion(CropTopSlider.Value, CropBottomSlider.Value, CropLeftSlider.Value, CropRightSlider.Value));
            UpdateOcrBox();
            return;
        }
        selectedPreset = "Custom";
        ShortcutPresetText.Text = Localizer.Current["Custom Box"];

        controller.Settings = controller.Settings with
        {
            AutoRegion = false,
            Region = new CropRegion(CropTopSlider.Value, CropBottomSlider.Value, CropLeftSlider.Value, CropRightSlider.Value)
        };
        UpdateOcrBox();
    }

    private void OnOcrCanvasSizeChanged(object sender, SizeChangedEventArgs e)
    {
        UpdateOcrBox();
    }

    private void UpdateOcrBox()
    {
        if (OcrCanvas == null || OcrBoxBorder == null || CropTopSlider == null || CropBottomSlider == null || CropLeftSlider == null || CropRightSlider == null) return;
        var area = OcrCanvas.ActualWidth <= 10 || OcrCanvas.ActualHeight <= 10
            ? new Rect(0, 0, 520, 230)
            : PictureArea();
        double w = area.Width, h = area.Height;

        var top = CropTopSlider.Value;
        var bottom = CropBottomSlider.Value;
        var left = CropLeftSlider.Value;
        var right = CropRightSlider.Value;

        // Kutu görüntünün çizildiği alana göre (bkz. PictureArea)
        var boxX = area.X + left * w;
        var boxY = area.Y + top * h;
        var boxW = Math.Max(40, (right - left) * w);
        var boxH = Math.Max(20, (bottom - top) * h);

        Canvas.SetLeft(OcrBoxBorder, boxX);
        Canvas.SetTop(OcrBoxBorder, boxY);
        OcrBoxBorder.Width = boxW;
        OcrBoxBorder.Height = boxH;

        if (CropTopLabel != null) CropTopLabel.Text = $"%{Math.Round(top * 100)}";
        if (CropBottomLabel != null) CropBottomLabel.Text = $"%{Math.Round(bottom * 100)}";
        if (CropLeftLabel != null) CropLeftLabel.Text = $"%{Math.Round(left * 100)}";
        if (CropRightLabel != null) CropRightLabel.Text = $"%{Math.Round(right * 100)}";
        UpdateExtraBoxes();
    }

    // --- Sürekli okunan ek alanlar (en çok 3 yuva; Num 1–3 ile tek seferlik çeviri de aynı alanları kullanır) ---

    /// <summary>Listede gösterilen hazır alanlar, sırasıyla.</summary>
    private static readonly (ExtraAreaPreset Preset, string Label)[] ExtraAreaChoices =
    [
        (ExtraAreaPreset.BottomObjective, "Quest text at the bottom"),
        (ExtraAreaPreset.HelpBox, "Hint box (top left)"),
        (ExtraAreaPreset.PhoneMessage, "Phone messages (bottom right)"),
        (ExtraAreaPreset.Quest, "Quest (top right)"),
        (ExtraAreaPreset.Notification, "Notification (bottom left)"),
    ];

    private readonly List<Border> extraBoxes = [];

    /// <summary>Tek tıkla oyuna göre en iyi okuma (şimdilik GTA 6).</summary>
    /// <summary>Boyutu ayarlanan ek alan yuvası (1–3); 0 ise mavi kutu altyazı alanıdır.</summary>
    private int editSlot;

    /// <summary>Ek alanın boyutu ayarlanmaya başlar: mavi kutu ve kaydırıcılar o alanı gösterir ve değiştirir.</summary>
    private void BeginExtraEdit(int slot)
    {
        if (controller.Settings.ExtraRegion(slot) is not { } region) return;
        editSlot = slot;
        var was = loading;
        loading = true;
        CropTopSlider.Value = region.Top;
        CropBottomSlider.Value = region.Bottom;
        CropLeftSlider.Value = region.Left;
        CropRightSlider.Value = region.Right;
        loading = was;
        OcrBoxLabel.Text = Localizer.Current.Format("EXTRA AREA %@", slot.ToString());
        ExtraEditText.Text = Localizer.Current["Drag the blue box on the picture or use the sliders to resize this area. Its translation on the TV covers the text inside it."];
        ExtraEditBar.Visibility = Visibility.Visible;
        UpdateOcrBox();
    }

    /// <summary>Ek alanın ayarı biter; mavi kutu yeniden altyazı alanını gösterir.</summary>
    private void EndExtraEdit()
    {
        editSlot = 0;
        var s = controller.Settings;
        var was = loading;
        loading = true;
        CropTopSlider.Value = s.Region.Top;
        CropBottomSlider.Value = s.Region.Bottom;
        CropLeftSlider.Value = s.Region.Left;
        CropRightSlider.Value = s.Region.Right;
        loading = was;
        OcrBoxLabel.Text = Localizer.Current["READING AREA"];
        ExtraEditBar.Visibility = Visibility.Collapsed;
        ShowExtraAreas();
        UpdateOcrBox();
    }

    private void OnExtraEditDone(object sender, RoutedEventArgs e) => EndExtraEdit();

    /// <summary>Ek alanın kutusunun yanındaki "Boyut" düğmesi.</summary>
    private Button ResizeButton(int slot)
    {
        var button = new Button
        {
            Content = Localizer.Current["Resize"],
            Tag = slot,
            Margin = new Thickness(0, 0, 18, 6),
            Padding = new Thickness(10, 2, 10, 2),
            VerticalAlignment = VerticalAlignment.Center,
            Style = (Style)FindResource("PillButton"),
        };
        button.Click += (_, _) => BeginExtraEdit((int)button.Tag);
        return button;
    }

    private void OnGamePresetClick(object sender, RoutedEventArgs e)
    {
        if (editSlot > 0) EndExtraEdit();
        controller.Settings = controller.Settings.WithGta6Reading();
        selectedPreset = "Auto";
        ShortcutPresetText.Text = Localizer.Current["Automatic"];
        ShowExtraAreas();
        UpdateOcrBox();
    }

    /// <summary>Hazır alanların kutuları ve elle seçilmiş açık alanlar ("Area 2 (custom)") yeniden kurulur.</summary>
    private void ShowExtraAreas()
    {
        if (ExtraAreaList == null) return;
        var text = Localizer.Current;
        var s = controller.Settings;
        ExtraAreaList.Children.Clear();
        foreach (var (preset, label) in ExtraAreaChoices)
        {
            var on = s.HasExtraPreset(preset);
            var box = new CheckBox
            {
                Content = text[label],
                IsChecked = on,
                Tag = preset,
                Margin = new Thickness(0, 0, on ? 6 : 18, 6),
                VerticalAlignment = VerticalAlignment.Center,
                Foreground = (Brush)FindResource("textPrimaryBrush"),
            };
            box.Checked += OnExtraAreaToggle;
            box.Unchecked += OnExtraAreaToggle;
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            row.Children.Add(box);
            var slot = Enumerable.Range(1, UserSettings.ExtraRegionCount).FirstOrDefault(n => s.ExtraLive(n) && s.ExtraRegion(n) == preset.Region());
            if (on && slot > 0) row.Children.Add(ResizeButton(slot));
            ExtraAreaList.Children.Add(row);
        }
        // Hazır olmayan bölgeyle açık yuva (eski sürümden ya da elle): kapatılabilsin diye ayrıca gösterilir.
        var known = ExtraAreaChoices.Select(choice => choice.Preset.Region()).ToHashSet();
        for (var slot = 1; slot <= UserSettings.ExtraRegionCount; slot++)
        {
            if (!s.ExtraLive(slot) || s.ExtraRegion(slot) is not { } region || known.Contains(region)) continue;
            var box = new CheckBox
            {
                Content = text.Format("Area %@ (custom)", slot.ToString()),
                IsChecked = true,
                Tag = slot,
                Margin = new Thickness(0, 0, 18, 6),
                Foreground = (Brush)FindResource("textPrimaryBrush"),
            };
            box.Unchecked += (_, _) =>
            {
                if (editSlot == (int)box.Tag) EndExtraEdit();
                controller.Settings = controller.Settings.WithExtraLive((int)box.Tag, false);
                ShowExtraAreas();
            };
            box.Margin = new Thickness(0, 0, 6, 6);
            box.VerticalAlignment = VerticalAlignment.Center;
            var customRow = new StackPanel { Orientation = Orientation.Horizontal };
            customRow.Children.Add(box);
            customRow.Children.Add(ResizeButton(slot));
            ExtraAreaList.Children.Add(customRow);
        }
        ExtraAreaFull.Visibility = Visibility.Collapsed;
        ShowRegionLook();
        UpdateExtraBoxes();
    }

    /// <summary>Ek alanlarda tanınan tuşların kumandası (Otomatik / PlayStation / Xbox).</summary>
    private void OnButtonLayout(object sender, SelectionChangedEventArgs e)
    {
        if (loading || ButtonLayoutChoice.SelectedItem is not ComboBoxItem { Tag: string tag }) return;
        var layout = Enum.Parse<ButtonLayout>(tag);
        if (controller.Settings.ButtonLayout != layout) controller.Settings = controller.Settings with { ButtonLayout = layout };
    }

    /// <summary>Ek alan görünümü kontrollerini ayara göre doldurur.</summary>
    private void ShowRegionLook()
    {
        var style = controller.Settings.Style;
        var was = loading;
        loading = true;
        RegionBackgroundSlider.Value = Math.Clamp(style.RegionBackground, 0, 100);
        RegionScaleSlider.Value = Math.Clamp(style.RegionTextScale, 70, 150);
        ButtonLayoutChoice.SelectedItem = ButtonLayoutChoice.Items.Cast<ComboBoxItem>()
            .FirstOrDefault(item => (string)item.Tag == controller.Settings.ButtonLayout.ToString()) ?? ButtonLayoutChoice.Items[0];
        (style.RegionTextColor switch { SubtitleTextColor.White => RegionColorWhite, SubtitleTextColor.Yellow => RegionColorYellow, _ => RegionColorSame }).IsChecked = true;
        loading = was;
        RegionBackgroundLabel.Text = $"%{Math.Round(RegionBackgroundSlider.Value)}";
        RegionScaleLabel.Text = $"%{Math.Round(RegionScaleSlider.Value)}";
        RegionBackgroundHint.Visibility = RegionBackgroundSlider.Value < 90 ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>Ek alan görünümü TV'nin ve OBS'in altyazı ayarına birlikte yazılır (bilgisayar penceresi TV'ninkini kullanır).</summary>
    private void UpdateRegionLook(Func<SubtitleStyle, SubtitleStyle> change)
    {
        var s = controller.Settings;
        controller.Settings = s with { Style = change(s.Style), ObsStyle = s.ObsStyle is { } obs ? change(obs) : null };
    }

    private void OnRegionLook(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (RegionBackgroundLabel == null || RegionScaleLabel == null || RegionBackgroundHint == null) return;
        RegionBackgroundLabel.Text = $"%{Math.Round(RegionBackgroundSlider.Value)}";
        RegionScaleLabel.Text = $"%{Math.Round(RegionScaleSlider.Value)}";
        RegionBackgroundHint.Visibility = RegionBackgroundSlider.Value < 90 ? Visibility.Visible : Visibility.Collapsed;
        if (loading) return;
        int background = (int)Math.Round(RegionBackgroundSlider.Value), scale = (int)Math.Round(RegionScaleSlider.Value);
        UpdateRegionLook(style => style with { RegionBackground = background, RegionTextScale = scale });
    }

    private void OnRegionColor(object sender, RoutedEventArgs e)
    {
        if (loading || sender is not RadioButton { Tag: string tag }) return;
        SubtitleTextColor? color = Enum.TryParse<SubtitleTextColor>(tag, out var parsed) ? parsed : null;
        UpdateRegionLook(style => style with { RegionTextColor = color });
    }

    /// <summary>Ek alan kutularını bilgisayar ekranında taşıma; ikinci basış (Bitti) kaydeder.</summary>
    private void OnRegionMove(object sender, RoutedEventArgs e)
    {
        if (DesktopRegionOverlay.IsMoving)
        {
            DesktopRegionOverlay.EndMove();
            return;
        }
        DesktopRegionOverlay.Offsets = controller.Settings.PcRegionOffsets ?? new Dictionary<string, PcOffset>();
        DesktopRegionOverlay.BeginMove(controller.Settings.WatchedAreas(), controller.Settings.Style, moved =>
        {
            controller.Settings = controller.Settings with { PcRegionOffsets = moved.Count == 0 ? null : new Dictionary<string, PcOffset>(moved) };
            RegionMoveButton.Content = Localizer.Current["Move the boxes on the PC screen"];
            RegionMoveHint.Visibility = Visibility.Collapsed;
        });
        RegionMoveButton.Content = Localizer.Current["Done"];
        RegionMoveHint.Visibility = Visibility.Visible;
    }

    private void OnRegionReset(object sender, RoutedEventArgs e)
    {
        DesktopRegionOverlay.ResetPositions();
        controller.Settings = controller.Settings with { PcRegionOffsets = null };
    }

    private void OnExtraAreaToggle(object sender, RoutedEventArgs e)
    {
        if (sender is not CheckBox { Tag: ExtraAreaPreset preset } box) return;
        var on = box.IsChecked == true;
        var updated = controller.Settings.WithExtraPreset(preset, on);
        if (on && !updated.HasExtraPreset(preset))
        {
            // Üç yuva da dolu: kutu geri kapanır, kullanıcıya söylenir.
            box.Unchecked -= OnExtraAreaToggle;
            box.IsChecked = false;
            box.Unchecked += OnExtraAreaToggle;
            ExtraAreaFull.Visibility = Visibility.Visible;
            return;
        }
        if (!on && editSlot > 0 && controller.Settings.ExtraRegion(editSlot) == preset.Region()) EndExtraEdit();
        controller.Settings = updated;
        ExtraAreaFull.Visibility = Visibility.Collapsed;
        // "Boyut" düğmesi açılan alanın yanında görünsün diye liste yeniden kurulur (olay bittikten sonra).
        Dispatcher.BeginInvoke(ShowExtraAreas);
    }

    /// <summary>Açık ek alanlar canlı görüntüde yeşil kutularla gösterilir.</summary>
    private void UpdateExtraBoxes()
    {
        if (OcrCanvas == null) return;
        foreach (var old in extraBoxes) OcrCanvas.Children.Remove(old);
        extraBoxes.Clear();
        var s = controller.Settings;
        var area = OcrCanvas.ActualWidth <= 10 || OcrCanvas.ActualHeight <= 10 ? new Rect(0, 0, 520, 230) : PictureArea();
        var green = (Brush)FindResource("successBrush");
        foreach (var watched in s.WatchedAreas())
        {
            // Boyutu ayarlanan alan mavi kutuyla gösterilir.
            if (editSlot > 0 && watched.Id == "area" + editSlot) continue;
            var region = watched.Region;
            var label = new Border
            {
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Top,
                Background = new SolidColorBrush(Color.FromArgb(0xEE, 0, 0, 0)),
                CornerRadius = new CornerRadius(3),
                Padding = new Thickness(4, 1.5, 4, 1.5),
                Margin = new Thickness(2),
                Child = new TextBlock { Text = Localizer.Current["EXTRA AREA"], FontSize = 11, FontWeight = FontWeights.Bold, Foreground = green },
            };
            var border = new Border
            {
                BorderBrush = green,
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(4),
                IsHitTestVisible = false,
                Width = Math.Max(30, (region.Right - region.Left) * area.Width),
                Height = Math.Max(14, (region.Bottom - region.Top) * area.Height),
                Child = label,
            };
            Canvas.SetLeft(border, area.X + region.Left * area.Width);
            Canvas.SetTop(border, area.Y + region.Top * area.Height);
            OcrCanvas.Children.Add(border);
            extraBoxes.Add(border);
        }
    }



    private void OnLaunchChiakiManual(object sender, RoutedEventArgs e)
    {
        try
        {
            var host = controller.Console?.Host;
            var name = controller.Console?.Name;
            if (!string.IsNullOrWhiteSpace(host))
            {
                ChiakiLauncher.SyncConfig(host);
            }
            if (!ChiakiLauncher.LaunchOrBringToFront(host, name))
            {
                MessageBox.Show(
                    Localizer.Current["The Chiaki app was not found. Please check that 'chiaki/chiaki.exe' is in the app folder."],
                    Localizer.Current["Chiaki Not Found"],
                    MessageBoxButton.OK,
                    MessageBoxImage.Warning);
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show(Localizer.Current.Format("Chiaki could not be started: %@", ex.Message), Localizer.Current["Error"], MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void OnRefreshCapture(object sender, RoutedEventArgs e)
    {
        try
        {
            var s = controller.Settings;
            if (s.VideoSource is VideoSourceType.CaptureCard or VideoSourceType.DesktopScreen or VideoSourceType.Xbox)
            {
                var target = s.DesktopWindowName;
                GameTranslator.Core.Frame? frame = null;

                if (s.VideoSource == VideoSourceType.Xbox)
                {
                    var xbox = WindowCaptureService.FindXboxWindow(out _);
                    if (xbox != IntPtr.Zero) frame = WindowCaptureService.Capture(xbox);
                    UpdateXboxStatus();
                }
                else if (!string.IsNullOrWhiteSpace(target) && target.StartsWith("window:", StringComparison.OrdinalIgnoreCase))
                {
                    var winTitle = target["window:".Length..];
                    var hWnd = WindowCaptureService.FindWindowBySubstring(winTitle);
                    if (hWnd != IntPtr.Zero) frame = WindowCaptureService.Capture(hWnd);
                }
                else if (!string.IsNullOrWhiteSpace(target) && target.StartsWith("monitor:", StringComparison.OrdinalIgnoreCase))
                {
                    if (int.TryParse(target["monitor:".Length..], out int mIndex))
                    {
                        var monitors = WindowCaptureService.GetDisplayMonitors();
                        var targetMon = monitors.FirstOrDefault(m => m.Index == mIndex) ?? monitors.FirstOrDefault(m => m.IsPrimary);
                        if (targetMon != null) frame = WindowCaptureService.CaptureScreen(targetMon.Left, targetMon.Top, targetMon.Width, targetMon.Height);
                    }
                }
                else if (!string.IsNullOrWhiteSpace(target))
                {
                    var hWnd = WindowCaptureService.FindWindowBySubstring(target);
                    if (hWnd != IntPtr.Zero) frame = WindowCaptureService.Capture(hWnd);
                }
                else
                {
                    // Yalnızca hiçbir hedef seçilmemişse varsayılan ekranı yakala
                    var monitors = WindowCaptureService.GetDisplayMonitors();
                    var primary = monitors.FirstOrDefault(m => m.IsPrimary) ?? monitors.FirstOrDefault();
                    if (primary != null) frame = WindowCaptureService.CaptureScreen(primary.Left, primary.Top, primary.Width, primary.Height);
                }

                if (frame != null)
                {
                    controller.FeedFrame(frame);
                    UpdateLiveFrame(frame);
                    return;
                }
            }
            else
            {
                var hWnd = WindowCaptureService.FindTargetWindow(out var title);
                if (hWnd != IntPtr.Zero)
                {
                    var frame = WindowCaptureService.Capture(hWnd);
                    if (frame != null)
                    {
                        controller.FeedFrame(frame);
                        UpdateLiveFrame(frame);
                        return;
                    }
                }
            }

            MessageBox.Show(
                Localizer.Current["The target window or live video source was not found.\n\nPlease make sure your game window or PS5 stream is open."],
                Localizer.Current["Could Not Get the Picture"],
                MessageBoxButton.OK,
                MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(Localizer.Current.Format("Capture error: %@", ex.Message), Localizer.Current["Error"], MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    // --- Tab 2: TV Görünümü & Renkler ---

    /// <summary>Altyazı Görünümü'nde OBS sekmesi açık: aşağıdaki denetimler OBS'in ayrı görünümünü düzenler.</summary>
    private bool editingObs;

    /// <summary>Düzenlenen görünüm: TV'ninki ya da (OBS sekmesinde) OBS'inki; OBS'in ayrı görünümü yoksa TV'ninki.</summary>
    private SubtitleStyle EditedStyle => editingObs ? controller.Settings.ObsStyle ?? controller.Settings.Style : controller.Settings.Style;

    /// <summary>Görünüm değişikliği düzenlenen ekrana yazılır (OBS sekmesinde TV'ye dokunmaz) ve hemen gönderilir.</summary>
    private void EditStyle(Func<SubtitleStyle, SubtitleStyle> change)
    {
        var s = controller.Settings;
        controller.Settings = editingObs ? s with { ObsStyle = change(s.ObsStyle ?? s.Style) } : s with { Style = change(s.Style) };
        controller.SyncStyle();
        UpdateTvPreview();
    }

    /// <summary>Görünüm denetimlerini düzenlenen görünümle doldurur (ayar yazmadan).</summary>
    private void LoadStyleControls()
    {
        var was = loading;
        loading = true;
        var st = EditedStyle;
        TextSizeSlider.Value = Math.Clamp(st.FontSize, TextSizeSlider.Minimum, TextSizeSlider.Maximum);
        TextSizeLabel.Text = $"{st.FontSize} px";
        BackgroundSlider.Value = Math.Clamp(st.Background, BackgroundSlider.Minimum, BackgroundSlider.Maximum);
        BackgroundLabel.Text = $"%{st.Background}";
        BottomPercentSlider.Value = Math.Clamp(st.BottomPercent, BottomPercentSlider.Minimum, BottomPercentSlider.Maximum);
        BottomPercentLabel.Text = $"%{st.BottomPercent}";
        selectedColor = st.TextColor.Hex();
        UpdateColorSelection();
        BoldCheck.IsChecked = st.Bold;
        SpeakerColorCheck.IsChecked = st.SpeakerColor;
        foreach (ComboBoxItem item in PlacementBox.Items)
        {
            if (string.Equals((string)item.Tag, st.Placement.ToString(), StringComparison.OrdinalIgnoreCase))
            {
                PlacementBox.SelectedItem = item;
                break;
            }
        }
        ShowFonts(st.Font);
        var overlay = controller.Settings.Style.DisplayMode == TvDisplayMode.Overlay;
        TvModeOverlay.IsChecked = overlay;
        TvModeInApp.IsChecked = !overlay;
        // OBS sekmesi: "TV ile aynı" açıkken denetimler TV'nin değerlerini gösterir, değiştirilemez
        ObsSameCheck.IsChecked = controller.Settings.ObsStyle is null;
        StyleControls.IsEnabled = !editingObs || controller.Settings.ObsStyle is not null;
        loading = was;
        UpdateTvPreview();
    }

    private void OnObsSame(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        var s = controller.Settings;
        // Kapatınca OBS, TV'nin o anki görünümüyle başlar; açınca yine TV'yi izler
        controller.Settings = s with { ObsStyle = ObsSameCheck.IsChecked == true ? null : s.ObsStyle ?? s.Style };
        controller.SyncStyle();
        LoadStyleControls();
    }

    /// <summary>
    /// Font menüsü: standart yazı, hazır seçenek (Pricedown) ve bilgisayarda kurulu bütün fontlar. Her ad
    /// kendi fontuyla yazılır. Kurulu olmayan hazır seçenek seçilirse indirme bağlantısı gösterilir.
    /// </summary>
    private void ShowFonts(string? chosen)
    {
        var was = loading;
        loading = true;
        chosen = SubtitleFonts.MenuKey(chosen);
        Services.InstalledFonts.Forget();
        FontBox.Items.Clear();
        FontBox.Items.Add(new ComboBoxItem { Content = Localizer.Current["Standard"], Tag = "" });
        foreach (var (key, label) in SubtitleFonts.Presets)
        {
            var family = Services.InstalledFonts.Family(key);
            FontBox.Items.Add(new ComboBoxItem
            {
                Content = family is null ? $"{label} ({Localizer.Current["not installed"]})" : label,
                Tag = key,
                FontFamily = family ?? FontBox.FontFamily,
            });
        }
        FontBox.Items.Add(new Separator());
        var families = Services.InstalledFonts.Families();
        foreach (var name in families)
            FontBox.Items.Add(new ComboBoxItem { Content = name, Tag = name, FontFamily = new FontFamily(name) });
        // Kayıtlı font artık kurulu değilse de adıyla listede kalır (seçim kaybolmasın)
        if (chosen.Length > 0 && !FontBox.Items.OfType<ComboBoxItem>().Any(i => string.Equals((string)i.Tag, chosen, StringComparison.OrdinalIgnoreCase)))
            FontBox.Items.Add(new ComboBoxItem { Content = $"{chosen} ({Localizer.Current["not installed"]})", Tag = chosen });
        FontBox.SelectedItem = FontBox.Items.OfType<ComboBoxItem>().FirstOrDefault(i => string.Equals((string)i.Tag, chosen, StringComparison.OrdinalIgnoreCase));
        UpdateFontNote(chosen);
        loading = was;
    }

    private void UpdateFontNote(string font)
    {
        var missing = font.Length > 0 && !Services.InstalledFonts.IsInstalled(font);
        FontNote.Visibility = missing ? Visibility.Visible : Visibility.Collapsed;
        if (!missing) return;
        var download = SubtitleFonts.DownloadPage(font);
        FontNoteText.Text = download is null
            ? Localizer.Current["This font is not installed on this PC; the standard font is used."]
            : Localizer.Current["Not installed on this PC."] + " ";
        FontDownloadLink.NavigateUri = download is null ? null : new Uri(download);
        FontDownloadLink.IsEnabled = true;
        SetFontLinkText(download is null ? "" : Localizer.Current["Download and install the font"]);
    }

    private void SetFontLinkText(string text) =>
        FontDownloadLink.Inlines.FirstInline?.SetValue(System.Windows.Documents.Run.TextProperty, text);

    private void OnFont(object sender, SelectionChangedEventArgs e)
    {
        if (loading || FontBox.SelectedItem is not ComboBoxItem { Tag: string font }) return;
        UpdateFontNote(font);
        EditStyle(st => st with { Font = font });
    }

    /// <summary>Menü açılınca kurulu fontlar yeniden okunur: kullanıcı fontu az önce kurmuş olabilir.</summary>
    private void OnFontListOpened(object sender, EventArgs e)
    {
        if (!StyleControls.IsEnabled) return;
        var font = EditedStyle.Font;
        var installedBefore = Services.InstalledFonts.IsInstalled(font);
        ShowFonts(font);
        if (!installedBefore && Services.InstalledFonts.IsInstalled(font)) controller.RefreshFonts();
    }

    /// <summary>
    /// "Fontu indir ve kur": tıklayınca font indirilir ve bu kullanıcı için kurulur, seçim hemen o fonta geçer (TV, OBS
    /// ve ikinci ekrana da gider). İndirme olmazsa fontun sayfası tarayıcıda açılır.
    /// </summary>
    private async void OnFontDownload(object sender, RoutedEventArgs e)
    {
        if (FontDownloadLink.NavigateUri is not { } uri || !FontDownloadLink.IsEnabled) return;
        var font = EditedStyle.Font;
        FontDownloadLink.IsEnabled = false;
        SetFontLinkText(Localizer.Current["Downloading…"]);
        try
        {
            var installed = await Services.FontInstaller.DownloadAndInstallAsync(uri.AbsoluteUri);
            if (installed.Count == 0) throw new InvalidDataException("no font in the download");
            ShowFonts(font);
            controller.RefreshFonts();
            UpdateTvPreview();
        }
        catch (Exception ex) when (ex is System.Net.Http.HttpRequestException or TaskCanceledException or IOException
                                       or InvalidDataException or UnauthorizedAccessException)
        {
            FontDownloadLink.IsEnabled = true;
            SetFontLinkText(Localizer.Current["Download and install the font"]);
            if (SubtitleFonts.WebPage(font) is { } page)
            {
                try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(page) { UseShellExecute = true }); }
                catch (System.ComponentModel.Win32Exception) { }
            }
        }
    }

    private void OnColorWhite(object sender, RoutedEventArgs e) => SelectColor("#FFFFFF");
    private void OnColorYellow(object sender, RoutedEventArgs e) => SelectColor("#FFE600");
    private void OnColorCyan(object sender, RoutedEventArgs e) => SelectColor("#38BDF8");
    private void OnColorGreen(object sender, RoutedEventArgs e) => SelectColor("#10B981");
    private void OnColorOrange(object sender, RoutedEventArgs e) => SelectColor("#FF9F0A");

    private void SelectColor(string hex)
    {
        if (!StyleControls.IsEnabled) return;
        selectedColor = hex;
        UpdateColorSelection();

        var textColor = Enum.GetValues<SubtitleTextColor>().FirstOrDefault(c => c.Hex() == hex);
        EditStyle(st => st with { TextColor = textColor });
    }

    private void UpdateColorSelection()
    {
        ColorWhiteBorder.BorderBrush = selectedColor == "#FFFFFF" ? new SolidColorBrush(Color.FromRgb(10, 132, 255)) : Brushes.Transparent;
        ColorYellowBorder.BorderBrush = selectedColor == "#FFE600" ? new SolidColorBrush(Color.FromRgb(10, 132, 255)) : Brushes.Transparent;
        ColorCyanBorder.BorderBrush = selectedColor == "#38BDF8" ? new SolidColorBrush(Color.FromRgb(10, 132, 255)) : Brushes.Transparent;
        ColorGreenBorder.BorderBrush = selectedColor == "#10B981" ? new SolidColorBrush(Color.FromRgb(10, 132, 255)) : Brushes.Transparent;
        ColorOrangeBorder.BorderBrush = selectedColor == "#FF9F0A" ? new SolidColorBrush(Color.FromRgb(10, 132, 255)) : Brushes.Transparent;
    }

    private void OnTextSize(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (loading) return;
        var val = (int)Math.Round(e.NewValue);
        TextSizeLabel.Text = $"{val} px";
        EditStyle(st => st with { FontSize = val });
    }

    private void OnBackground(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (loading) return;
        var val = (int)Math.Round(e.NewValue);
        BackgroundLabel.Text = $"%{val}";
        EditStyle(st => st with { Background = val });
    }

    private void OnBottomPercent(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (loading) return;
        var val = (int)Math.Round(e.NewValue);
        BottomPercentLabel.Text = $"%{val}";
        EditStyle(st => st with { BottomPercent = val });
    }

    private void OnPlacement(object sender, SelectionChangedEventArgs e)
    {
        if (loading || PlacementBox.SelectedItem is not ComboBoxItem item) return;
        if (Enum.TryParse<SubtitlePlacement>((string)item.Tag, true, out var placement))
        {
            EditStyle(st => st with { Placement = placement });
        }
    }

    private void OnLanguage(object sender, SelectionChangedEventArgs e)
    {
        if (loading || LanguageBox.SelectedItem is not ComboBoxItem item) return;
        var lang = (string)item.Tag;
        controller.Settings = controller.Settings with { SourceLanguage = lang };
        UpdateTargetLanguageNote();
    }

    // --- Çeviri dili ve uygulama dili ---

    /// <summary>İki dil kutusunu doldurur ve ayardaki değerleri seçer (olay tetiklemeden).</summary>
    private void ShowLanguages(UserSettings s)
    {
        var was = loading;
        loading = true;
        var ui = Localizer.Current.Language;
        TargetLanguageBox.Items.Clear();
        var target = Languages.OrDefault(s.TargetLanguage).Code;
        foreach (var language in Languages.Targets)
        {
            var item = new ComboBoxItem { Content = language.Label(ui), Tag = language.Code };
            TargetLanguageBox.Items.Add(item);
            if (language.Code == target) TargetLanguageBox.SelectedItem = item;
        }
        UiLanguageBox.Items.Clear();
        var chosen = Localizer.UiLanguages.Any(l => l.Code == s.UiLanguage) ? s.UiLanguage : "auto";
        foreach (var (code, name) in new[] { ("auto", Localizer.Current["Automatic (Windows language)"]) }.Concat(Localizer.UiLanguages))
        {
            var item = new ComboBoxItem { Content = name, Tag = code };
            UiLanguageBox.Items.Add(item);
            if (code == chosen) UiLanguageBox.SelectedItem = item;
        }
        loading = was;
        UpdateTargetLanguageNote();
    }

    private void OnTargetLanguage(object sender, SelectionChangedEventArgs e)
    {
        if (loading || TargetLanguageBox.SelectedItem is not ComboBoxItem { Tag: string code }) return;
        controller.Settings = controller.Settings with { TargetLanguage = code };
        UpdateTargetLanguageNote();
    }

    private void OnUiLanguage(object sender, SelectionChangedEventArgs e)
    {
        if (loading || UiLanguageBox.SelectedItem is not ComboBoxItem { Tag: string code }) return;
        controller.Settings = controller.Settings with { UiLanguage = code };
        var before = Localizer.Current.Language;
        Localizer.Use(code);
        // Dil hemen değişir: pencere ekranı yeni dille yeniden kurar (ana ekrana dönülür)
        if (Localizer.Current.Language != before) Dispatcher.BeginInvoke(Controls.LanguageButton.NotifyUiLanguageChanged);
    }

    /// <summary>
    /// Çeviri dili Türkçe değilken neyin değiştiğini söyler: hazır paketler ve dublaj yalnız Türkçe; seçili motor o dile
    /// çevirmiyorsa çeviriyi Google yapar. Arayüz dili değiştiyse yeniden açılış gerektiği de burada yazar.
    /// </summary>
    private void UpdateTargetLanguageNote()
    {
        if (TargetLanguageNote == null) return;
        var s = controller.Settings;
        var turkishUi = Localizer.Current.Language == "tr";
        var notes = new List<string>();
        var language = Languages.OrDefault(s.TargetLanguage);
        // Arapçanın da hazır paketleri var: not yalnız paketi olmayan dillerde (sözlük ve dublaj zaten Türkçeye özgü).
        if (controller.PackLanguage is null)
            notes.Add(Localizer.Current["Ready-made game packs, the term glossary and Turkish dubbing are only used when translating English games into Turkish; for this language pair every line goes through the translation engine."]);
        if (!controller.EngineCoversTarget)
        {
            var engine = s.Engine == TranslationEngine.DeepL ? "DeepL" : Localizer.Current["The on-device model"];
            notes.Add(Localizer.Current.Format("%1$@ does not translate into %2$@; Google translates instead (needs internet). For better results choose Gemini in the Models tab.",
                engine, turkishUi ? language.TurkishName : language.EnglishName));
        }
        TargetLanguageNote.Text = string.Join("\n", notes);
        TargetLanguageNote.Visibility = notes.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnBold(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        EditStyle(st => st with { Bold = BoldCheck.IsChecked == true });
    }

    private void OnSharePool(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        controller.Settings = controller.Settings with { SharePool = SharePoolCheck.IsChecked == true };
        _ = RefreshActiveAsync();
    }

    /// <summary>
    /// Android TV'de HDMI uygulamanın içinde mi, TV'nin kendi ekranında (katman) mı gösterilir. Her zaman TV'nin
    /// görünümüne yazılır (OBS'in ayrı görünümüne değil); TV uygulaması style olayıyla alır.
    /// </summary>
    private void OnTvDisplayMode(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        var mode = TvModeOverlay.IsChecked == true ? TvDisplayMode.Overlay : TvDisplayMode.InApp;
        var s = controller.Settings;
        if (s.Style.DisplayMode == mode) return;
        controller.Settings = s with { Style = s.Style with { DisplayMode = mode } };
        controller.SyncStyle();
    }

    private void OnSpeakerColor(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        EditStyle(st => st with { SpeakerColor = SpeakerColorCheck.IsChecked == true });
    }

    private void OnDubbing(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        controller.Settings = controller.Settings with { DubbingEnabled = DubbingCheck.IsChecked == true };
    }

    private void OnMutePc(object sender, RoutedEventArgs e)
    {
        isMutePc = MutePcCheck.IsChecked == true;
    }

    private void UpdateTvPreview()
    {
        if (TvPreviewSubtitleText == null || TvPreviewSubtitleBox == null || TextSizeSlider == null || BoldCheck == null || BackgroundSlider == null || BottomPercentSlider == null) return;
        try
        {
            var converter = new BrushConverter();
            if (converter.ConvertFromString(selectedColor) is Brush brush)
                TvPreviewSubtitleText.Foreground = brush;
        }
        catch { }

        var size = TextSizeSlider.Value;
        TvPreviewSubtitleText.FontSize = Math.Max(11, size * 0.35);
        TvPreviewSubtitleText.FontWeight = BoldCheck.IsChecked == true ? FontWeights.Bold : FontWeights.Normal;
        if (Services.InstalledFonts.Family(EditedStyle.Font) is { } family) TvPreviewSubtitleText.FontFamily = family;
        else TvPreviewSubtitleText.ClearValue(TextBlock.FontFamilyProperty);

        var opacity = BackgroundSlider.Value / 100.0;
        var byteAlpha = (byte)Math.Clamp(Math.Round(opacity * 255), 0, 255);
        TvPreviewSubtitleBox.Background = new SolidColorBrush(Color.FromArgb(byteAlpha, 0, 0, 0));

        var bottomPx = BottomPercentSlider.Value * 1.5;
        TvPreviewSubtitleBox.Margin = new Thickness(0, 0, 0, bottomPx);
    }

    private void OnTestSubtitle(object sender, RoutedEventArgs e) => controller.SendTestSubtitle();

    // --- İkinci ekran (TV'm desteklenmiyor) ---

    private string? shownSecondScreenUrl;

    /// <summary>İkinci ekran adresini ve QR kodunu gösterir; adres (ağ ya da port) değiştiyse QR yeniden çizilir.</summary>
    private void UpdateSecondScreen()
    {
        if (SecondScreenUrlText == null || SecondScreenQr == null) return;
        _ = CheckSecondScreenFirewallAsync();
        var url = controller.SecondScreenUrl;
        if (url == shownSecondScreenUrl && SecondScreenQr.Source != null) return;
        shownSecondScreenUrl = url;
        if (url is null)
        {
            SecondScreenUrlText.Text = Localizer.Current["No network connection found"];
            SecondScreenQr.Source = null;
            SecondScreenHint.Text = Localizer.Current["The computer must be connected to Wi-Fi or a wired network; the address appears here once it is."];
            return;
        }
        SecondScreenUrlText.Text = url;
        try
        {
            using var generator = new QRCoder.QRCodeGenerator();
            using var data = generator.CreateQrCode(url, QRCoder.QRCodeGenerator.ECCLevel.M);
            var png = new QRCoder.PngByteQRCode(data).GetGraphic(8);
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = new MemoryStream(png);
            image.EndInit();
            image.Freeze();
            SecondScreenQr.Source = image;
        }
        catch (Exception)
        {
            SecondScreenQr.Source = null;   // QR çizilemese de adres yazılı durur
        }
    }

    private DateTime secondScreenFirewallChecked = DateTime.MinValue;

    /// <summary>
    /// Güvenlik duvarı telefon ve tabletten gelen bağlantıyı engelliyorsa kartta "İzin ver" çıkar: engelliyken sayfa
    /// tablete hiç gelmez, tarayıcı beyaz ekranda bekler. Kurallar en çok 30 saniyede bir taranır.
    /// </summary>
    private async Task CheckSecondScreenFirewallAsync(bool force = false)
    {
        if (!force && DateTime.UtcNow - secondScreenFirewallChecked < TimeSpan.FromSeconds(30)) return;
        secondScreenFirewallChecked = DateTime.UtcNow;
        var blocked = await controller.FirewallBlocksIncomingAsync();
        SecondScreenFirewall.Visibility = blocked ? Visibility.Visible : Visibility.Collapsed;
        SecondScreenFirewallButton.IsEnabled = true;
        SecondScreenFirewallText.Text = Localizer.Current["Windows Firewall is blocking the phone or tablet, so the page cannot open."];
    }

    private async void OnSecondScreenFirewall(object sender, RoutedEventArgs e)
    {
        SecondScreenFirewallButton.IsEnabled = false;
        SecondScreenFirewallText.Text = Localizer.Current["Waiting for Windows…"];
        var added = await controller.AllowThroughFirewallAsync();
        if (!added)
        {
            SecondScreenFirewallButton.IsEnabled = true;
            SecondScreenFirewallText.Text = Localizer.Current["The firewall rule was not added. Try again and choose Yes when Windows asks."];
            return;
        }
        await CheckSecondScreenFirewallAsync(force: true);
    }

    private void OnCopySecondScreen(object sender, RoutedEventArgs e)
    {
        if (controller.SecondScreenUrl is not { } url) return;
        try { Clipboard.SetText(url); SecondScreenHint.Text = Localizer.Current["Address copied. Paste it into the browser on your phone or tablet (it must be on the same Wi-Fi)."]; }
        catch (System.Runtime.InteropServices.ExternalException) { }
    }

    private void OnOpenSecondScreen(object sender, RoutedEventArgs e)
    {
        if (controller.SecondScreenUrl is { } url) controller.OpenInBrowser(new Uri(url));
    }

    /// <summary>Rehber sekmesini "TV'm desteklenmiyor" bölümü görünecek şekilde açar.</summary>
    private void OnOpenSecondScreenGuide(object sender, RoutedEventArgs e) => ShowSecondScreenGuide();

    public void ShowSecondScreenGuide()
    {
        ShowSettings(TabGuide);
        foreach (var panel in new[] { PanelOcrArea, PanelDisplay, PanelModels, PanelSystem }) panel.Visibility = Visibility.Collapsed;
        PanelGuide.Visibility = Visibility.Visible;
        ShowGuideTopic(2);
    }

    /// <summary>Ekran görüntüsü aracı için: Bağlantı sekmesi (ikinci ekran kartı görünür).</summary>
    public void ShowConnectionTab()
    {
        ShowSettings(TabSystem);
        foreach (var panel in new[] { PanelOcrArea, PanelDisplay, PanelModels, PanelGuide }) panel.Visibility = Visibility.Collapsed;
        PanelSystem.Visibility = Visibility.Visible;
        UpdateSecondScreen();
    }

    // --- PC altyazı barı ---

    /// <summary>Kaydırıcıları ve etiketleri verilen bar ayarına getirir (olay tetiklemeden).</summary>
    private void ShowPcBar(PcBarSettings bar)
    {
        var was = loading;
        loading = true;
        PcBarOpacitySlider.Value = bar.Opacity;
        // En sol (kaydırıcının alt sınırı) "Otomatik" demektir. Kutu fareyle çizildiyse kaydırıcı onun genişliğini gösterir.
        var width = bar.Box is { } box ? (int)Math.Round(box.Width * 100) : bar.WidthPercent;
        PcBarWidthSlider.Value = width <= 0 ? PcBarWidthSlider.Minimum : Math.Clamp(width, PcBarWidthSlider.Minimum, PcBarWidthSlider.Maximum);
        PcBarPaddingSlider.Value = bar.Padding;
        PcBarCornerSlider.Value = bar.Corner;
        PcBarBorderCheck.IsChecked = bar.Border;
        PcBarTextSizeSlider.Value = bar.TextSize;
        (bar.Edge switch { PcTextEdge.Outline => PcEdgeOutline, PcTextEdge.None => PcEdgeNone, _ => PcEdgeShadow }).IsChecked = true;
        PcColorNavy.IsChecked = bar.BarColor == "#0A0E17";
        PcColorBlack.IsChecked = bar.BarColor == "#000000";
        PcColorGray.IsChecked = bar.BarColor == "#3A3F47";
        loading = was;
        UpdatePcBarLabels(bar);
    }

    private void UpdatePcBarLabels(PcBarSettings bar)
    {
        PcBarOpacityLabel.Text = bar.Opacity == 0 ? Localizer.Current["None"] : $"%{bar.Opacity}";
        PcBarWidthLabel.Text = bar.Box is { } box ? $"{Localizer.Current["Manual"]} · %{Math.Round(box.Width * 100)}" : bar.WidthPercent <= 0 ? Localizer.Current["Automatic"] : $"%{bar.WidthPercent}";
        PcBarPaddingLabel.Text = $"{bar.Padding} px";
        PcBarCornerLabel.Text = $"{bar.Corner} px";
        PcBarTextSizeLabel.Text = $"%{bar.TextSize}";
        // Ayarlar hazır görünümlerden birine denk geliyorsa o seçili görünür; elle değiştirildiyse hiçbiri
        var look = bar.Look;
        PcLookTextOnly.IsChecked = look == PcBarLook.TextOnly;
        PcLookBar.IsChecked = look == PcBarLook.Bar;
        PcLookCinema.IsChecked = look == PcBarLook.Cinema;
        PcLookFramed.IsChecked = look == PcBarLook.Framed;
        if (PcBarBoxHint != null)
        {
            PcBarBoxHint.Text = bar.Box is null
                ? Localizer.Current["Tip: press \"Position the Subtitle\", then drag the box by its edge to resize it or by its middle to move it wherever you like."]
                : Localizer.Current["The bar's position and size were set by hand: every line appears exactly in that box. Use \"Position the Subtitle\" to change it, or \"Reset\" to go back to automatic placement."];
        }
    }

    private PcBarSettings ReadPcBar()
    {
        var current = (controller.Settings.PcBar ?? new()).Normalized();
        var width = (int)Math.Round(PcBarWidthSlider.Value);
        var box = current.Box;
        var widthPercent = width < PcBarSettings.MinWidthPercent ? 0 : width;
        if (box is not null)
        {
            // Kutu fareyle çizilmişken genişlik kaydırıcısı kutuyu ortasından genişletir ya da daraltır; yeri ve yüksekliği kalır.
            // En sola ("Otomatik") çekilirse kutu bırakılır.
            if (widthPercent == 0) box = null;
            else if (Math.Abs(widthPercent - Math.Round(box.Width * 100)) >= 1)
            {
                var newWidth = widthPercent / 100.0;
                box = box with { Left = box.Left + (box.Width - newWidth) / 2, Width = newWidth };
            }
        }
        return new PcBarSettings(
            Opacity: (int)Math.Round(PcBarOpacitySlider.Value),
            WidthPercent: box is null ? widthPercent : current.WidthPercent,
            Padding: (int)Math.Round(PcBarPaddingSlider.Value),
            Corner: (int)Math.Round(PcBarCornerSlider.Value),
            Border: PcBarBorderCheck.IsChecked == true,
            Box: box,
            Edge: PcEdgeOutline.IsChecked == true ? PcTextEdge.Outline : PcEdgeNone.IsChecked == true ? PcTextEdge.None : PcTextEdge.Shadow,
            TextSize: (int)Math.Round(PcBarTextSizeSlider.Value),
            BarColor: PcColorBlack.IsChecked == true ? "#000000" : PcColorGray.IsChecked == true ? "#3A3F47"
                : PcColorNavy.IsChecked == true ? "#0A0E17" : current.BarColor).Normalized();
    }

    /// <summary>Hazır görünüm seçildi: görünümle ilgili değerler değişir; yer, genişlik ve yazı boyutu kalır.</summary>
    private void OnPcLook(object sender, RoutedEventArgs e)
    {
        if (loading || sender is not RadioButton { Tag: string tag } || !Enum.TryParse<PcBarLook>(tag, out var look)) return;
        var bar = (controller.Settings.PcBar ?? new()).Normalized().WithLook(look);
        ShowPcBar(bar);
        ApplyPcBar();
    }

    private void OnPcBar(object sender, RoutedPropertyChangedEventArgs<double> e) => ApplyPcBar();

    private void OnPcBarToggle(object sender, RoutedEventArgs e) => ApplyPcBar();

    private void OnPcBarReset(object sender, RoutedEventArgs e)
    {
        // Konumlandırma kutusu açıksa önce kapanır (kaydedeceği kutu hemen ardından silinir)
        if (DesktopSubtitleOverlay.IsPositioning) DesktopSubtitleOverlay.FinishPositioning();
        var defaults = new PcBarSettings();
        controller.Settings = controller.Settings with { PcBar = defaults };
        DesktopSubtitleOverlay.Bar = defaults;
        ShowPcBar(defaults);
    }

    private void ApplyPcBar()
    {
        if (loading || PcBarCornerSlider == null || PcBarBorderCheck == null || PcBarTextSizeSlider == null || PcColorGray == null) return;
        var bar = ReadPcBar();
        UpdatePcBarLabels(bar);
        if (bar == controller.Settings.PcBar) return;
        controller.Settings = controller.Settings with { PcBar = bar };
        DesktopSubtitleOverlay.Bar = bar;
        // Ayar değişirken sonucu görmek için ekranda kısa bir örnek satır (katman açıksa; konumlandırma kutusu açıkken
        // değişiklik zaten kutunun kendisinde görünür)
        if (DesktopSubtitleOverlay.IsOverlayEnabled && !DesktopSubtitleOverlay.IsPositioning)
            DesktopSubtitleOverlay.Display(Localizer.Current["Sample subtitle: this is how the bar will look."], 2500, controller.Settings.Style, controller.LastSubtitleBox);
    }

    /// <summary>Kullanıcı kutuyu fareyle çizip çift tıkladı: yeni yer ve boyut kaydedilir, kaydırıcılar ona göre gösterilir.</summary>
    private void OnOverlayBarEdited(PcBarSettings bar)
    {
        controller.Settings = controller.Settings with { PcBar = bar };
        ShowPcBar(bar);
        if (DesktopSubtitleOverlay.IsOverlayEnabled)
            DesktopSubtitleOverlay.Display(Localizer.Current["Sample subtitle: from now on the bar will appear here."], 3000, controller.Settings.Style, controller.LastSubtitleBox);
    }

    private void OnDragOverlayPosition(object sender, RoutedEventArgs e)
    {
        DesktopSubtitleOverlay.StartDragMode();
    }

    /// <summary>Anahtar kod tarafından eşitleniyor: kullanıcı seçimi değil, ayar yazılmaz.</summary>
    private bool syncingOverlay;

    private void OnDesktopOverlayToggle(object sender, RoutedEventArgs e)
    {
        if (syncingOverlay || loading) return;
        if (sender is CheckBox cb)
        {
            var isEnabled = cb.IsChecked == true;
            DesktopSubtitleOverlay.IsOverlayEnabled = isEnabled;
            if (controller.Settings.PcOverlay != isEnabled) controller.Settings = controller.Settings with { PcOverlay = isEnabled };
            UpdateOverlayStatusUi(isEnabled);
            Refresh();
        }
    }

    /// <summary>Bilgisayar ekranındaki altyazı başka yerden açılıp kapandıysa (kısayol, ilk açılış) anahtar da onu gösterir.</summary>
    private void UpdateOverlayStatusUi(bool enabled)
    {
        if (DesktopOverlayCheck == null || DesktopOverlayCheck.IsChecked == enabled) return;
        syncingOverlay = true;
        DesktopOverlayCheck.IsChecked = enabled;
        syncingOverlay = false;
    }

    // --- Tab 3: Modeller ---

    private void OnModelSelected(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        TranslationEngine targetEngine = TranslationEngine.DirectML;

        if (RadioModelGemini.IsChecked == true)
        {
            if (!controller.HasGeminiKey)
            {
                MessageBox.Show(Localizer.Current["To use the Google Gemini model, please enter a Gemini API key below and press the 'Save and Activate' button."], Localizer.Current["API Key Required"], MessageBoxButton.OK, MessageBoxImage.Information);
                UpdateModelSelectionUi();
                return;
            }
            targetEngine = TranslationEngine.Gemini;
        }
        else if (RadioModelDeepL.IsChecked == true)
        {
            if (!controller.HasDeepLKey)
            {
                MessageBox.Show(Localizer.Current["To use the DeepL model, please enter a DeepL API key below and press the 'Save and Activate' button."], Localizer.Current["API Key Required"], MessageBoxButton.OK, MessageBoxImage.Information);
                UpdateModelSelectionUi();
                return;
            }
            targetEngine = TranslationEngine.DeepL;
        }
        else
        {
            targetEngine = TranslationEngine.DirectML;
        }

        controller.Settings = controller.Settings with { Engine = targetEngine, OnlineTranslation = (targetEngine != TranslationEngine.DirectML) };
        UpdateModelSelectionUi();
    }

    private async void OnGeminiSave(object sender, RoutedEventArgs e)
    {
        var key = GeminiKeyInput.Password?.Trim();
        if (string.IsNullOrWhiteSpace(key))
        {
            MessageBox.Show(Localizer.Current["Please enter a valid Google Gemini API key."], Localizer.Current["Key Missing"], MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var check = await controller.SetGeminiKeyAsync(key);
        if (check.State is OnlineKeyState.Valid or OnlineKeyState.QuotaExceeded)
        {
            controller.Settings = controller.Settings with { Engine = TranslationEngine.Gemini, OnlineTranslation = true };
            UpdateModelSelectionUi();
            controller.SendTestSubtitle();
            MessageBox.Show(Localizer.Current["The Google Gemini API key was verified and set as the active model!"], Localizer.Current["Gemini Active"], MessageBoxButton.OK, MessageBoxImage.Information);
        }
        else
        {
            MessageBox.Show(Localizer.Current["The Google Gemini API key you entered is not valid, or Google's server could not be reached. Please check your key."], Localizer.Current["Connection Error"], MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private async void OnDeepLSave(object sender, RoutedEventArgs e)
    {
        var key = DeepLKeyInput.Password?.Trim();
        if (string.IsNullOrWhiteSpace(key))
        {
            MessageBox.Show(Localizer.Current["Please enter a valid DeepL API key."], Localizer.Current["Key Missing"], MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var check = await controller.SetDeepLKeyAsync(key);
        if (check.State is OnlineKeyState.Valid or OnlineKeyState.QuotaExceeded)
        {
            controller.Settings = controller.Settings with { Engine = TranslationEngine.DeepL, OnlineTranslation = true };
            UpdateModelSelectionUi();
            controller.SendTestSubtitle();
            MessageBox.Show(Localizer.Current["The DeepL API key was verified and set as the active model!"], Localizer.Current["DeepL Active"], MessageBoxButton.OK, MessageBoxImage.Information);
        }
        else
        {
            MessageBox.Show(Localizer.Current["The DeepL API key you entered is not valid, or the server could not be reached."], Localizer.Current["Connection Error"], MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    /// <summary>Yerel modelin gerçek durumu: hızlı motor hazır mı, eski motor mu çalışıyor, model hiç yok mu.</summary>
    private void UpdateLocalEngineStatus()
    {
        if (LocalEngineStatus == null) return;
        var fast = LocalTranslation.FastModelReady;
        var old = new GameTranslator.Translation.ModelStore(System.IO.Path.Combine(AppPaths.Models, "en-tr")).IsInstalled;
        var onGpu = controller.Settings.UseGpu && LocalTranslation.CudaDirectoryIfReady is not null;
        (LocalEngineStatus.Text, var brush) = fast ? (onGpu ? Localizer.Current["Fast engine ready (CTranslate2, on the graphics card)"] : Localizer.Current["Fast engine ready (CTranslate2, on the processor)"], "successBrush")
            : LocalTranslation.ModelDownloading ? (Localizer.Current.Format("Downloading the fast model: %lld%% (475 MB). Until then the free online translation is used; restart translating when it is done.", (int)Math.Round(LocalTranslation.ModelProgress * 100)), "warningBrush")
            : LocalTranslation.ModelError is { } modelError ? (Localizer.Current.Format("The fast model could not be downloaded (%@); it is retried the next time the app starts.", modelError), "dangerBrush")
            : old ? (Localizer.Current["Old engine (ONNX) in use; the fast model is downloaded in the background and used after the app restarts"], "warningBrush")
            : (Localizer.Current["The model is not installed yet: lines are translated with the free online translation for now"], "textTertiaryBrush");
        LocalEngineStatus.SetResourceReference(TextBlock.ForegroundProperty, brush);
    }

    /// <summary>Ekran kartı anahtarı ve altındaki durum satırı.</summary>
    private void UpdateGpuUi()
    {
        if (GpuCheck == null || GpuHint == null) return;
        var was = loading;
        loading = true;
        GpuCheck.IsChecked = controller.Settings.UseGpu;
        GpuCheck.IsEnabled = LocalTranslation.GpuPossible;
        loading = was;
        var cuda = LocalTranslation.Cuda;
        GpuHint.Text = !LocalTranslation.GpuPossible ? Localizer.Current["No NVIDIA graphics card was found; translation runs on the processor."]
            : !controller.Settings.UseGpu ? Localizer.Current["Much faster translation on an NVIDIA graphics card. When turned on, the needed NVIDIA files are downloaded once (566 MB)."]
            : cuda.IsInstalled ? Localizer.Current["The graphics card is ready; it is used from the next time you start translating."]
            : cuda.Downloading ? Localizer.Current.Format("Downloading the graphics card files: %lld%%", (int)Math.Round(LocalTranslation.CudaProgress * 100))
            : LocalTranslation.CudaError is { } error ? Localizer.Current.Format("The graphics card files could not be downloaded (%@). Turn it off and on to try again; translation keeps running on the processor.", error)
            : Localizer.Current["Much faster translation on an NVIDIA graphics card. When turned on, the needed NVIDIA files are downloaded once (566 MB)."];
        UpdateLocalEngineStatus();
    }

    private void OnGpuToggle(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        var on = GpuCheck.IsChecked == true;
        controller.Settings = controller.Settings with { UseGpu = on };
        if (on) LocalTranslation.StartCudaDownload();
        UpdateGpuUi();
    }

    private void UpdateModelSelectionUi()
    {
        UpdateGpuUi();
        if (BorderDirectMl == null || BorderGemini == null || BorderDeepL == null) return;

        var prevLoading = loading;
        loading = true;
        try
        {
            var engine = controller.Settings.Engine;

            // Radio Buttons
            if (RadioModelDirectMl != null) RadioModelDirectMl.IsChecked = (engine == TranslationEngine.DirectML);
            if (RadioModelGemini != null) RadioModelGemini.IsChecked = (engine == TranslationEngine.Gemini);
            if (RadioModelDeepL != null) RadioModelDeepL.IsChecked = (engine == TranslationEngine.DeepL);

            // Badges
            if (BadgeDirectMl != null) BadgeDirectMl.Visibility = (engine == TranslationEngine.DirectML) ? Visibility.Visible : Visibility.Collapsed;
            if (BadgeGemini != null) BadgeGemini.Visibility = (engine == TranslationEngine.Gemini) ? Visibility.Visible : Visibility.Collapsed;
            if (BadgeDeepL != null) BadgeDeepL.Visibility = (engine == TranslationEngine.DeepL) ? Visibility.Visible : Visibility.Collapsed;

            // Border Glow
            var activeBrush = new SolidColorBrush(Color.FromRgb(10, 132, 255));
            var inactiveBrush = new SolidColorBrush(Color.FromRgb(31, 41, 61));

            BorderDirectMl.BorderBrush = (engine == TranslationEngine.DirectML) ? activeBrush : inactiveBrush;
            BorderGemini.BorderBrush = (engine == TranslationEngine.Gemini) ? activeBrush : inactiveBrush;
            BorderDeepL.BorderBrush = (engine == TranslationEngine.DeepL) ? activeBrush : inactiveBrush;
        }
        finally
        {
            loading = prevLoading;
        }
    }

    private void OnCardDirectMlClick(object sender, MouseButtonEventArgs e)
    {
        controller.Settings = controller.Settings with { Engine = TranslationEngine.DirectML, OnlineTranslation = false };
        UpdateModelSelectionUi();
    }

    private void OnCardGeminiClick(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is PasswordBox || e.OriginalSource is Button) return;

        if (controller.HasGeminiKey)
        {
            controller.Settings = controller.Settings with { Engine = TranslationEngine.Gemini, OnlineTranslation = true };
            UpdateModelSelectionUi();
        }
        else
        {
            GeminiKeyInput?.Focus();
        }
    }

    private void OnCardDeepLClick(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is PasswordBox || e.OriginalSource is Button) return;

        if (controller.HasDeepLKey)
        {
            controller.Settings = controller.Settings with { Engine = TranslationEngine.DeepL, OnlineTranslation = true };
            UpdateModelSelectionUi();
        }
        else
        {
            DeepLKeyInput?.Focus();
        }
    }

    // --- Geçmiş Dışa Aktarma ---

    private void OnExportTxt(object sender, RoutedEventArgs e)
    {
        if (history.Count == 0)
        {
            MessageBox.Show(Localizer.Current["There is no subtitle history to export yet."], Localizer.Current["History Is Empty"], MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        var dlg = new SaveFileDialog { Filter = Localizer.Current["Text File (*.txt)|*.txt"], FileName = Localizer.Current["GameTranslator_Subtitle_History"] + ".txt" };
        if (dlg.ShowDialog() == true)
        {
            var lines = history.Select(h => $"{h.Time}\tTV: {h.Turkish}\tPS5: {h.English}");
            File.WriteAllLines(dlg.FileName, lines);
            MessageBox.Show(Localizer.Current["The subtitle history was saved!"], Localizer.Current["Exported"], MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    private void OnExportJson(object sender, RoutedEventArgs e)
    {
        if (history.Count == 0)
        {
            MessageBox.Show(Localizer.Current["There is no subtitle history to export yet."], Localizer.Current["History Is Empty"], MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        var dlg = new SaveFileDialog { Filter = Localizer.Current["JSON File (*.json)|*.json"], FileName = Localizer.Current["GameTranslator_Subtitle_History"] + ".json" };
        if (dlg.ShowDialog() == true)
        {
            var json = JsonSerializer.Serialize(history, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(dlg.FileName, json);
            MessageBox.Show(Localizer.Current["The subtitle history was saved as JSON!"], Localizer.Current["Exported"], MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    // --- Genel Aksiyonlar & Footer ---

    private void OnCopyObs(object sender, RoutedEventArgs e)
    {
        Clipboard.SetText($"http://localhost:{controller.ServerPort}/obs");
        MessageBox.Show(Localizer.Current.Format("The OBS URL was copied to the clipboard:\n%@", $"http://localhost:{controller.ServerPort}/obs"), Localizer.Current["Copied"], MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void OnSupport(object sender, RoutedEventArgs e) => AppInfo.Open(AppInfo.SupportUrl);

    private void OnAbout(object sender, RoutedEventArgs e)
    {
        new AboutWindow { Owner = Window.GetWindow(this) }.ShowDialog();
    }

    private void OnResetAll(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show(Localizer.Current["Reset GameTranslator's settings?"], Localizer.Current["Reset Settings"],
            MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
        {
            controller.ResetSettings();
            LoadSettings();
            UpdateOcrBox();
            UpdateTvPreview();
        }
    }

    private void OnReinstall(object sender, RoutedEventArgs e)
    {
        controller.Step = OnboardingStep.Welcome;
    }

    private void OnTvActionButton(object sender, RoutedEventArgs e)
    {
        OnReinstall(sender, e);
    }

    private void OnDone(object sender, RoutedEventArgs e) => OnBackHome(sender, e);

    private void OnHyperlink(object sender, RequestNavigateEventArgs e)
    {
        Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute = true });
    }

    // --- Video Giriş Kaynağı (Remote Play, Capture Card, Masaüstü/OBS) ---

    private void OnVideoSourceChanged(object sender, RoutedEventArgs e)
    {
        if (loading) return;

        // Eski donmuş kareyi ve görüntüleri anında sıfırla
        if (LiveStreamImage != null) LiveStreamImage.Source = null;
        if (ThumbnailStreamImage != null) ThumbnailStreamImage.Source = null;
        if (StreamPlaceholder != null) StreamPlaceholder.Visibility = Visibility.Visible;
        if (ThumbnailPlaceholder != null) ThumbnailPlaceholder.Visibility = Visibility.Visible;
        controller.ClearLastFrame();

        VideoSourceType newSource;
        if (SourceXboxCard != null) SourceXboxCard.Visibility = Visibility.Collapsed;
        if (SourceRadioXbox.IsChecked == true)
        {
            newSource = VideoSourceType.Xbox;
            ShowXboxSource();
        }
        else if (SourceRadioCaptureCard.IsChecked == true)
        {
            newSource = VideoSourceType.CaptureCard;
            SourceWindowPickerCard.Visibility = Visibility.Visible;
            if (QuickSourcePickerCard != null) QuickSourcePickerCard.Visibility = Visibility.Visible;
            PopulateSourceWindows();
            if (StreamThumbnailTitle != null) StreamThumbnailTitle.Text = Localizer.Current["📹 Capture Card Live Stream"];
            if (ThumbnailStatusText != null) ThumbnailStatusText.Text = Localizer.Current["Waiting for the Capture Card"];
        }
        else if (SourceRadioDesktop.IsChecked == true)
        {
            newSource = VideoSourceType.DesktopScreen;
            SourceWindowPickerCard.Visibility = Visibility.Visible;
            if (QuickSourcePickerCard != null) QuickSourcePickerCard.Visibility = Visibility.Visible;
            PopulateSourceWindows();
            if (StreamThumbnailTitle != null) StreamThumbnailTitle.Text = Localizer.Current["🖥️ Desktop / OBS Live Stream"];
            if (ThumbnailStatusText != null) ThumbnailStatusText.Text = Localizer.Current["Waiting for the Desktop Stream"];
        }
        else
        {
            newSource = VideoSourceType.RemotePlay;
            SourceWindowPickerCard.Visibility = Visibility.Collapsed;
            if (QuickSourcePickerCard != null) QuickSourcePickerCard.Visibility = Visibility.Collapsed;
            if (StreamThumbnailTitle != null) StreamThumbnailTitle.Text = Localizer.Current["PS5 picture"];
            if (ThumbnailStatusText != null) ThumbnailStatusText.Text = Localizer.Current["Waiting for the picture"];
        }

        controller.Settings = controller.Settings with { VideoSource = newSource };
    }

    private void OnRefreshWindows(object sender, RoutedEventArgs e)
    {
        PopulateSourceWindows();
    }

    /// <summary>Xbox kaynağı seçildi: pencere seçimi gizlenir (kendiliğinden bulunur), bulunan pencere gösterilir.</summary>
    private void ShowXboxSource()
    {
        SourceWindowPickerCard.Visibility = Visibility.Collapsed;
        if (QuickSourcePickerCard != null) QuickSourcePickerCard.Visibility = Visibility.Collapsed;
        if (SourceXboxCard != null) SourceXboxCard.Visibility = Visibility.Visible;
        if (StreamThumbnailTitle != null) StreamThumbnailTitle.Text = Localizer.Current["Xbox picture"];
        if (ThumbnailStatusText != null) ThumbnailStatusText.Text = Localizer.Current["Waiting for the Xbox window"];
        UpdateXboxStatus();
    }

    private void OnRefreshXbox(object sender, RoutedEventArgs e) => UpdateXboxStatus();

    /// <summary>Xbox kartındaki "Rehber": Rehber sekmesini açıp Xbox bölümüne kaydırır.</summary>
    private void OnOpenXboxGuide(object sender, RoutedEventArgs e) => ShowXboxGuide();

    /// <summary>Rehberdeki "Bağlantı sekmesini aç": kaynağın seçildiği sekmeye götürür.</summary>
    private void OnOpenXboxSource(object sender, RoutedEventArgs e) => ShowSettings(TabSystem);

    /// <summary>Rehber sekmesini Xbox bölümü görünecek şekilde açar (ekran görüntüsü aracı da kullanır).</summary>
    public void ShowXboxGuide()
    {
        ShowSettings(TabGuide);
        foreach (var panel in new[] { PanelOcrArea, PanelDisplay, PanelModels, PanelSystem }) panel.Visibility = Visibility.Collapsed;
        PanelGuide.Visibility = Visibility.Visible;
        ShowGuideTopic(1);
    }

    /// <summary>Ekran görüntüsü aracı için: Bağlantı sekmesi, kaynak olarak Xbox seçili.</summary>
    public void ShowXboxSourceTab()
    {
        ShowSettings(TabSystem);
        foreach (var panel in new[] { PanelOcrArea, PanelDisplay, PanelModels, PanelGuide }) panel.Visibility = Visibility.Collapsed;
        PanelSystem.Visibility = Visibility.Visible;
        var was = loading;
        loading = true;
        SourceRadioXbox.IsChecked = true;
        loading = was;
        ShowXboxSource();
    }

    private void SetXboxStatus(string text, string brush)
    {
        XboxWindowStatus.Text = text;
        XboxWindowStatus.SetResourceReference(TextBlock.ForegroundProperty, brush);
    }

    private XboxLoginChallenge? xboxLogin;
    private bool xboxLoggingIn, xboxLoadingConsoles, xboxConsolesLoaded;
    private string? xboxError;

    /// <summary>Xbox kartının durumu: Xbox ev yayını (varsayılan: giriş + konsol) ya da kullanıcının açtığı pencere.</summary>
    private void UpdateXboxStatus()
    {
        if (XboxWindowStatus == null || XboxExternalCheck == null) return;
        var external = controller.Settings.XboxExternalWindow || !controller.XboxAvailable;
        var was = loading;
        loading = true;
        XboxExternalCheck.IsChecked = external;
        // Xbox bileşeni bu kurulumda yoksa (kaynak koddan derlenen kopya) yalnız pencere yolu kalır
        XboxExternalCheck.IsEnabled = controller.XboxAvailable;
        loading = was;
        XboxBuiltInPanel.Visibility = external ? Visibility.Collapsed : Visibility.Visible;
        XboxExternalPanel.Visibility = external ? Visibility.Visible : Visibility.Collapsed;

        if (external)
        {
            if (WindowCaptureService.FindXboxWindow(out var title) == IntPtr.Zero)
                SetXboxStatus(Localizer.Current["Xbox window not found (open the game in the Xbox app or at xbox.com/play)"], "warningBrush");
            else
                SetXboxStatus(XboxWindow.GameName(title) is { } found ? Localizer.Current.Format("Found: %@", found) : Localizer.Current.Format("Found: %@", title), "successBrush");
            return;
        }

        var signedIn = controller.HasXboxAccount;
        var console = controller.SelectedXboxConsole;
        if (xboxError is not null) SetXboxStatus(xboxError, "dangerBrush");
        else if (xboxLoggingIn) SetXboxStatus(Localizer.Current["Waiting for the Microsoft sign-in"], "warningBrush");
        else if (!signedIn) SetXboxStatus(Localizer.Current["Xbox not connected"], "textSecondaryBrush");
        else if (console is null) SetXboxStatus(xboxLoadingConsoles ? Localizer.Current["Looking for consoles…"] : Localizer.Current["Choose your Xbox"], "warningBrush");
        else SetXboxStatus(controller.IsRunning ? $"{console.Name}: {controller.Ps5StatusText}" : Localizer.Current.Format("Ready: %@. Connects when you start translating.", console.Name), "successBrush");

        XboxLoginPanel.Visibility = signedIn ? Visibility.Collapsed : Visibility.Visible;
        XboxLoginButton.IsEnabled = !xboxLoggingIn;
        XboxLoginButton.Content = xboxLoggingIn ? Localizer.Current["Waiting for sign-in…"] : Localizer.Current["Sign in with a Microsoft account"];
        XboxCodePanel.Visibility = xboxLoggingIn && xboxLogin is not null ? Visibility.Visible : Visibility.Collapsed;
        if (xboxLogin is not null) XboxCodeText.Text = xboxLogin.Code;
        XboxConsolePanel.Visibility = signedIn ? Visibility.Visible : Visibility.Collapsed;
        XboxSteps.Text = signedIn && console is not null
            ? Localizer.Current["Set up. Just start translating: the app connects to the console by itself and no window opens. The console must be in \"Sleep\" mode with remote features turned on."]
            : Localizer.Current["Once: sign in with your Microsoft account and choose your Xbox. After that you only start translating; the app connects to the console by itself and no window opens."];
        if (signedIn && !xboxConsolesLoaded && !xboxLoadingConsoles) _ = LoadXboxConsolesAsync();
    }

    private async void OnXboxLogin(object sender, RoutedEventArgs e)
    {
        if (xboxLoggingIn) return;
        xboxLoggingIn = true;
        xboxError = null;
        xboxLogin = null;
        UpdateXboxStatus();
        try
        {
            xboxLogin = await controller.BeginXboxLoginAsync();
            UpdateXboxStatus();
            await controller.CompleteXboxLoginAsync();
            xboxConsolesLoaded = false;
        }
        catch (Exception error)
        {
            xboxError = Localizer.Current.Format("Could not finish the Microsoft sign-in: %@", error.Message);
        }
        finally
        {
            xboxLoggingIn = false;
            xboxLogin = null;
            UpdateXboxStatus();
        }
    }

    private void OnXboxCopyCode(object sender, RoutedEventArgs e)
    {
        if (xboxLogin is null) return;
        try { Clipboard.SetText(xboxLogin.Code); XboxCodeHint.Text = Localizer.Current["Code copied. Waiting for your approval…"]; }
        catch (System.Runtime.InteropServices.ExternalException) { }
    }

    private void OnXboxOpenLoginPage(object sender, RoutedEventArgs e)
    {
        if (xboxLogin is not null) controller.OpenInBrowser(xboxLogin.Url);
    }

    private void OnXboxRefreshConsoles(object sender, RoutedEventArgs e)
    {
        xboxError = null;
        _ = LoadXboxConsolesAsync();
    }

    /// <summary>Hesaptaki konsolları listeler; tek uygun konsol varsa kendiliğinden seçilir.</summary>
    private async Task LoadXboxConsolesAsync()
    {
        if (xboxLoadingConsoles || !controller.HasXboxAccount) return;
        xboxLoadingConsoles = true;
        UpdateXboxStatus();
        try
        {
            var consoles = await controller.DiscoverXboxConsolesAsync();
            xboxConsolesLoaded = true;
            var usable = consoles.Where(c => c.StreamingEnabled).ToList();
            if (controller.SelectedXboxConsole is null && usable.Count == 1) controller.SelectXboxConsole(usable[0]);
            var was = loading;
            loading = true;
            XboxConsoleBox.Items.Clear();
            foreach (var item in consoles)
            {
                var entry = new ComboBoxItem
                {
                    Content = item.StreamingEnabled ? $"{item.Name} ({item.Model})" : Localizer.Current.Format("%1$@ (%2$@) · remote features off", item.Name, item.Model),
                    Tag = item, IsEnabled = item.StreamingEnabled,
                };
                XboxConsoleBox.Items.Add(entry);
                if (item.Id == controller.SelectedXboxConsole?.Id) XboxConsoleBox.SelectedItem = entry;
            }
            loading = was;
            XboxConsoleHint.Text = consoles.Count == 0
                ? Localizer.Current["No Xbox was found on this account. Make sure you are signed in on the console with the same Microsoft account, then press \"Refresh consoles\"."]
                : usable.Count < consoles.Count
                    ? Localizer.Current["A console shown dimmed has remote features turned off: on the Xbox, Settings › Devices & connections › Remote features › \"Enable remote features\"."]
                    : "";
            XboxConsoleHint.Visibility = XboxConsoleHint.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        catch (Exception error)
        {
            xboxConsolesLoaded = true;   // hata her güncellemede yeniden denenmesin; kullanıcı "yenile"ye basar
            xboxError = Localizer.Current.Format("Could not get the consoles: %@", error.Message);
        }
        finally
        {
            xboxLoadingConsoles = false;
            UpdateXboxStatus();
        }
    }

    private void OnXboxConsoleSelected(object sender, SelectionChangedEventArgs e)
    {
        if (loading || XboxConsoleBox.SelectedItem is not ComboBoxItem { Tag: XboxConsole selected }) return;
        xboxError = null;
        controller.SelectXboxConsole(selected);
        UpdateXboxStatus();
    }

    private void OnXboxForget(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show(Localizer.Current["Delete the Microsoft session and the selected Xbox from this computer? You will need to sign in again to reconnect."], "Xbox",
                MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        controller.ForgetXbox();
        xboxError = null;
        xboxConsolesLoaded = false;
        XboxConsoleBox.Items.Clear();
        UpdateXboxStatus();
    }

    private void OnXboxExternal(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        controller.Settings = controller.Settings with { XboxExternalWindow = XboxExternalCheck.IsChecked == true };
        UpdateXboxStatus();
    }
    private bool populatingSourceWindows;
    private void PopulateSourceWindows()
    {
        if (populatingSourceWindows) return;
        populatingSourceWindows = true;
        try
        {
            if (SourceWindowComboBox != null) SourceWindowComboBox.Items.Clear();
            if (QuickSourceWindowComboBox != null) QuickSourceWindowComboBox.Items.Clear();

            ComboBoxItem? toSelectMain = null;
            ComboBoxItem? toSelectQuick = null;
            var currentTarget = controller.Settings.DesktopWindowName;

            // 0. Yakalama kartı kaynağında: kamera gibi görünen aygıtlar (Elgato vb.) en üstte; seçilirse doğrudan okunur
            if (controller.Settings.VideoSource == VideoSourceType.CaptureCard)
            {
                var devices = CaptureDeviceStreamSession.List();
                if (devices.Count > 0)
                {
                    SourceWindowComboBox?.Items.Add(new ComboBoxItem { Content = Localizer.Current["── 📹 Capture cards and cameras ──"], IsEnabled = false, FontWeight = FontWeights.Bold });
                    QuickSourceWindowComboBox?.Items.Add(new ComboBoxItem { Content = Localizer.Current["── 📹 Capture cards and cameras ──"], IsEnabled = false, FontWeight = FontWeights.Bold });
                    foreach (var device in devices)
                    {
                        var tag = CaptureDeviceStreamSession.Prefix + device.Name;
                        var item1 = new ComboBoxItem { Content = Localizer.Current.Format("📹 %@", device.Name), Tag = tag };
                        var item2 = new ComboBoxItem { Content = Localizer.Current.Format("📹 %@", device.Name), Tag = tag };
                        SourceWindowComboBox?.Items.Add(item1);
                        QuickSourceWindowComboBox?.Items.Add(item2);
                        if (currentTarget == tag)
                        {
                            toSelectMain = item1;
                            toSelectQuick = item2;
                        }
                    }
                }
            }

            // 0b. OBS açıksa içindeki video kaynakları (kart OBS'teyken görüntü oradan alınır); WebSocket kapalıysa nasıl açılacağı
            if (controller.Settings.VideoSource is VideoSourceType.CaptureCard or VideoSourceType.DesktopScreen && ObsClient.IsObsRunning())
            {
                var (obsSources, obsProblem) = ObsStreamSession.ListSources();
                SourceWindowComboBox?.Items.Add(new ComboBoxItem { Content = Localizer.Current["── 🎬 OBS ──"], IsEnabled = false, FontWeight = FontWeights.Bold });
                QuickSourceWindowComboBox?.Items.Add(new ComboBoxItem { Content = Localizer.Current["── 🎬 OBS ──"], IsEnabled = false, FontWeight = FontWeights.Bold });
                if (obsProblem is not null)
                {
                    SourceWindowComboBox?.Items.Add(new ComboBoxItem { Content = obsProblem, IsEnabled = false });
                    QuickSourceWindowComboBox?.Items.Add(new ComboBoxItem { Content = obsProblem, IsEnabled = false });
                }
                foreach (var name in obsSources)
                {
                    var tag = ObsStreamSession.Prefix + name;
                    var item1 = new ComboBoxItem { Content = Localizer.Current.Format("🎬 OBS: %@", name), Tag = tag };
                    var item2 = new ComboBoxItem { Content = Localizer.Current.Format("🎬 OBS: %@", name), Tag = tag };
                    SourceWindowComboBox?.Items.Add(item1);
                    QuickSourceWindowComboBox?.Items.Add(item2);
                    if (currentTarget == tag)
                    {
                        toSelectMain = item1;
                        toSelectQuick = item2;
                    }
                }
            }

            // 1. Açık Pencereler & OBS Projektörleri (Öncelikli Seçim!)
            var titles = WindowCaptureService.GetTopLevelWindowTitles();
            if (titles.Count > 0)
            {
                var h1 = new ComboBoxItem { Content = Localizer.Current["── 🪟 Open Game Windows & OBS ──"], IsEnabled = false, FontWeight = FontWeights.Bold };
                var h1q = new ComboBoxItem { Content = Localizer.Current["── 🪟 Open Game Windows & OBS ──"], IsEnabled = false, FontWeight = FontWeights.Bold };
                SourceWindowComboBox?.Items.Add(h1);
                QuickSourceWindowComboBox?.Items.Add(h1q);

                foreach (var title in titles)
                {
                    var tag = $"window:{title}";
                    var item1 = new ComboBoxItem { Content = $"🪟 {title}", Tag = tag };
                    var item2 = new ComboBoxItem { Content = $"🪟 {title}", Tag = tag };
                    SourceWindowComboBox?.Items.Add(item1);
                    QuickSourceWindowComboBox?.Items.Add(item2);

                    if (!string.IsNullOrEmpty(currentTarget) && (currentTarget == tag || currentTarget == title || title.Contains(currentTarget, StringComparison.OrdinalIgnoreCase)))
                    {
                        toSelectMain = item1;
                        toSelectQuick = item2;
                    }
                }
            }

            // 2. Fiziksel Monitörler (Tüm Masaüstü)
            var monitors = WindowCaptureService.GetDisplayMonitors();
            var h2 = new ComboBoxItem { Content = Localizer.Current["── 🖥️ Whole Screen / Monitors ──"], IsEnabled = false, FontWeight = FontWeights.Bold };
            var h2q = new ComboBoxItem { Content = Localizer.Current["── 🖥️ Whole Screen / Monitors ──"], IsEnabled = false, FontWeight = FontWeights.Bold };
            SourceWindowComboBox?.Items.Add(h2);
            QuickSourceWindowComboBox?.Items.Add(h2q);

            foreach (var mon in monitors)
            {
                var tag = $"monitor:{mon.Index}";
                var item1 = new ComboBoxItem { Content = Localizer.Current.Format("🖥️ %@ (Whole Screen)", mon.Name), Tag = tag };
                var item2 = new ComboBoxItem { Content = Localizer.Current.Format("🖥️ %@ (Whole Screen)", mon.Name), Tag = tag };
                SourceWindowComboBox?.Items.Add(item1);
                QuickSourceWindowComboBox?.Items.Add(item2);

                if (currentTarget == tag)
                {
                    toSelectMain = item1;
                    toSelectQuick = item2;
                }
            }

            // Eğer daha önce bir hedef seçilmemişse: Açık bir pencere varsa İLK PENCEREYİ seç, yoksa monitörü seç!
            if (toSelectMain == null)
            {
                if (SourceWindowComboBox != null)
                {
                    foreach (ComboBoxItem it in SourceWindowComboBox.Items)
                    {
                        if (it.IsEnabled && it.Tag != null)
                        {
                            toSelectMain = it;
                            break;
                        }
                    }
                }
                if (QuickSourceWindowComboBox != null)
                {
                    foreach (ComboBoxItem it in QuickSourceWindowComboBox.Items)
                    {
                        if (it.IsEnabled && it.Tag != null)
                        {
                            toSelectQuick = it;
                            break;
                        }
                    }
                }
            }

            if (SourceWindowComboBox != null && toSelectMain != null)
            {
                SourceWindowComboBox.SelectedItem = toSelectMain;
            }
            if (QuickSourceWindowComboBox != null && toSelectQuick != null)
            {
                QuickSourceWindowComboBox.SelectedItem = toSelectQuick;
            }

            // Listede seçili görünen, kullanılan olsun: ayarda hedef yoksa ya da yakalama kartı kaynağında kayıtlı hedef
            // (ör. artık açık olmayan Elgato uygulaması penceresi) listede bulunamadıysa seçilen öğe kaydedilir. Eskiden
            // liste kartı seçili gösterip ayarda eski pencere kalıyordu; görüntü hiç gelmiyordu.
            // Seçilmiş yakalama aygıtı ya da OBS kaynağı o an listede yoksa (kart takılı değil, OBS kapalı) seçim korunur:
            // eskiden açılışta başka aygıtla (ör. web kamerası) değiştirilip kaydediliyordu.
            var chosenDevice = currentTarget is { } chosen &&
                               (chosen.StartsWith(CaptureDeviceStreamSession.Prefix, StringComparison.Ordinal) || chosen.StartsWith(ObsStreamSession.Prefix, StringComparison.Ordinal));
            if (toSelectMain?.Tag is string defTarget && defTarget != currentTarget && !chosenDevice &&
                (string.IsNullOrEmpty(currentTarget) || controller.Settings.VideoSource == VideoSourceType.CaptureCard))
            {
                SubtitleLog.Write($"görüntü kaynağı: kayıtlı hedef \"{currentTarget}\" listede yok, \"{defTarget}\" seçildi");
                controller.Settings = controller.Settings with { DesktopWindowName = defTarget };
            }
        }
        catch { }
        finally
        {
            populatingSourceWindows = false;
        }
    }

    private void OnSourceWindowSelected(object sender, SelectionChangedEventArgs e)
    {
        if (loading || populatingSourceWindows) return;
        if (SourceWindowComboBox.SelectedItem is ComboBoxItem item && item.Tag is string target && !string.IsNullOrEmpty(target))
        {
            controller.Settings = controller.Settings with { DesktopWindowName = target };
            if (QuickSourceWindowComboBox != null)
            {
                foreach (ComboBoxItem q in QuickSourceWindowComboBox.Items)
                {
                    if ((q.Tag as string) == target)
                    {
                        QuickSourceWindowComboBox.SelectedItem = q;
                        break;
                    }
                }
            }

            if (target.StartsWith("monitor:", StringComparison.OrdinalIgnoreCase) && int.TryParse(target["monitor:".Length..], out int mIndex))
            {
                var monitors = WindowCaptureService.GetDisplayMonitors();
                if (monitors.FirstOrDefault(m => m.Index == mIndex) is { } mon)
                {
                    DesktopSubtitleOverlay.SetTargetMonitor(mon.Left, mon.Top, mon.Width, mon.Height);
                }
            }
        }
    }

    private void OnQuickSourceWindowSelected(object sender, SelectionChangedEventArgs e)
    {
        if (loading || populatingSourceWindows) return;
        if (QuickSourceWindowComboBox?.SelectedItem is ComboBoxItem item && item.Tag is string target && !string.IsNullOrEmpty(target))
        {
            controller.Settings = controller.Settings with { DesktopWindowName = target };
            if (SourceWindowComboBox != null)
            {
                foreach (ComboBoxItem m in SourceWindowComboBox.Items)
                {
                    if ((m.Tag as string) == target)
                    {
                        SourceWindowComboBox.SelectedItem = m;
                        break;
                    }
                }
            }

            if (target.StartsWith("monitor:", StringComparison.OrdinalIgnoreCase) && int.TryParse(target["monitor:".Length..], out int mIndex))
            {
                var monitors = WindowCaptureService.GetDisplayMonitors();
                if (monitors.FirstOrDefault(m => m.Index == mIndex) is { } mon)
                {
                    DesktopSubtitleOverlay.SetTargetMonitor(mon.Left, mon.Top, mon.Width, mon.Height);
                }
            }
        }
    }

    private void UpdatePatchUi()
    {
        try
        {
            var pm = CommunityPatchManager.Shared;
            if (CheckCommunityPatches != null && CheckCommunityPatches.IsChecked != pm.IsEnabled)
            {
                CheckCommunityPatches.IsChecked = pm.IsEnabled;
            }
            if (PatchStatusText != null)
            {
                PatchStatusText.Text = pm.GetStatusSummary();
            }
            FillGames();
        }
        catch { }
    }

    private void OnPatchToggleChanged(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        var enabled = CheckCommunityPatches?.IsChecked ?? true;
        CommunityPatchManager.Shared.IsEnabled = enabled;
        if (PatchStatusText != null)
        {
            PatchStatusText.Text = CommunityPatchManager.Shared.GetStatusSummary();
        }
    }

    private async void OnScanPatchesClick(object sender, RoutedEventArgs e)
    {
        try
        {
            if (PatchStatusText != null) PatchStatusText.Text = Localizer.Current["⏳ Scanning packs and checking for updates..."];
            var res = await CommunityPatchManager.Shared.ScanAndReloadAsync();
            UpdatePatchUi();
            MessageBox.Show(res.Message, Localizer.Current["Game Packs Scanned"], MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(Localizer.Current.Format("Pack scan error: %@", ex.Message), Localizer.Current["Error"], MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void OnImportPatchClick(object sender, RoutedEventArgs e)
    {
        try
        {
            var dialog = new Microsoft.Win32.OpenFileDialog
            {
                Filter = Localizer.Current["Game Patches (*.json;*.txt;*.srt)|*.json;*.txt;*.srt|All Files (*.*)|*.*"],
                Title = Localizer.Current["Choose a Game Patch File"]
            };
            if (dialog.ShowDialog() == true)
            {
                var res = CommunityPatchManager.Shared.ImportFile(dialog.FileName);
                UpdatePatchUi();
                MessageBox.Show(res.Message, res.Success ? Localizer.Current["Patch Loaded"] : Localizer.Current["Load Error"], MessageBoxButton.OK, res.Success ? MessageBoxImage.Information : MessageBoxImage.Warning);
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show(Localizer.Current.Format("File load error: %@", ex.Message), Localizer.Current["Error"], MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }
}
