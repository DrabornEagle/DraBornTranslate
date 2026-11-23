// SPDX-License-Identifier: AGPL-3.0-only

using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using GameTranslator.App.Theme;
using GameTranslator.App.Views;
using GameTranslator.Core;
using GameTranslator.Core.LgTv;
using GameTranslator.RemotePlay;
using GameTranslator.Shell;

namespace GameTranslator.App.Services;

/// <summary>
/// Geliştirme aracı: <c>GameTranslator.exe --render-screens klasör</c> bütün ekranları iki arayüz dilinde (tr, en), açık ve koyu temada PNG'ye
/// döker ve kapanır. Ekranların görünümü Windows makinesi olmadan (sürekli tümleştirmede) böyle denetlenir.
/// </summary>
public static class ScreenRenderer
{
    public static void Run(Application app, string folder)
    {
        Directory.CreateDirectory(folder);
        // Açılan ilk pencere kendiliğinden ana pencere sayılır; kapanınca uygulama da kapanmasın.
        app.ShutdownMode = ShutdownMode.OnExplicitShutdown;
        var log = Path.Combine(folder, "errors.txt");
        // Bir ekranın hatası diğerlerini durdurmasın; hatalar dosyaya yazılır.
        app.DispatcherUnhandledException += (_, e) =>
        {
            File.AppendAllText(log, $"[dispatcher] {e.Exception}\n\n");
            e.Handled = true;
        };
        // Her ekran iki arayüz dilinde çizilir (dosya adı sonu -tr / -en): İngilizce metnin sığmadığı yer böyle görülür.
        var before = Localizer.Current.Language;
        foreach (var language in new[] { "tr", "en" })
        {
            Localizer.Use(language);
            foreach (var dark in new[] { false, true })
            {
                ThemeManager.Apply(app.Resources, dark);
                var suffix = dark ? "koyu" : "acik";
                for (var step = 1; step <= 4; step++)
                {
                    var controller = Demo(OnboardingStep.Welcome);
                    var n = step;
                    // Sihirbaz pencerenin gerçek boyutunda (1120×820) ve izin verilen en küçük boyutta (960×640) çizilir
                    Save(() => { var v = new OnboardingView(controller); v.ShowStep(n); return v; }, 1120, 820, Path.Combine(folder, $"adim-{step}-{suffix}-{language}.png"));
                    Save(() => { var v = new OnboardingView(Demo(OnboardingStep.Welcome)); v.ShowStep(n); return v; }, 960, 640, Path.Combine(folder, $"adim-{step}-kucuk-{suffix}-{language}.png"));
                }
                Save(() => { var v = new MainView(Demo(OnboardingStep.Done)); v.ShowActive(new GameTranslator.Core.PoolActivity(79, 329)); return v; }, 1120, 820, Path.Combine(folder, $"ana-{suffix}-{language}.png"));
                // Ana ekranın tamamı (altındaki destek kartıyla)
                Save(() => new MainView(Demo(OnboardingStep.Done)), 1120, 1000, Path.Combine(folder, $"ana-uzun-{suffix}-{language}.png"));
                // Ana ekran, yeni sürüm bandıyla
                Save(() => { var v = new MainView(Demo(OnboardingStep.Done)); v.ShowUpdatePreview("1.8.0"); return v; }, 640, 720, Path.Combine(folder, $"guncelleme-{suffix}-{language}.png"));
                // Ana ekran, yeni oyun paketi bandıyla
                Save(() => { var v = new MainView(Demo(OnboardingStep.Done)); v.ShowPackPreview(); return v; }, 1120, 820, Path.Combine(folder, $"paket-bandi-{suffix}-{language}.png"));
                // İlk ekrandaki dil düğmesinin açtığı kutu
                Save(() => Controls.LanguageButton.BuildPanel(new UserSettings { TargetLanguage = "ja" }, _ => { }, _ => { }), 460, 520, Path.Combine(folder, $"dil-kutusu-{suffix}-{language}.png"));
                // Ayar sekmeleri: Okuma Alanı, Modeller, Rehber (baştan)
                foreach (var tab in new[] { "alan", "modeller", "rehber", "diller", "gecmis" })
                {
                    var name = tab;
                    Save(() => { var v = new MainView(Demo(OnboardingStep.Done)); v.ShowTab(name); return v; }, 1180, name == "rehber" ? 1500 : 1100, Path.Combine(folder, $"sekme-{tab}-{suffix}-{language}.png"));
                }
                Save(() => { var v = new MainView(Demo(OnboardingStep.Done)); v.ShowDisplayTab(); return v; }, 1180, 1100, Path.Combine(folder, $"gorunum-{suffix}-{language}.png"));
                Save(() => { var v = new MainView(Demo(OnboardingStep.Done)); v.ShowDisplayPcTab(); return v; }, 1180, 1100, Path.Combine(folder, $"gorunum-pc-{suffix}-{language}.png"));
                // OBS sekmesi: TV ile aynı (denetimler kilitli) ve ayrı görünüm (sarı, büyük, sabit)
                Save(() => { var v = new MainView(Demo(OnboardingStep.Done)); v.ShowDisplayObsTab(); return v; }, 1180, 1100, Path.Combine(folder, $"gorunum-obs-{suffix}-{language}.png"));
                Save(() => { var c = Demo(OnboardingStep.Done); c.Settings = c.Settings with { ObsStyle = new GameTranslator.Core.SubtitleStyle(FontSize: 64, Placement: GameTranslator.Core.SubtitlePlacement.Fixed, TextColor: GameTranslator.Core.SubtitleTextColor.Green) }; var v = new MainView(c); v.ShowDisplayObsTab(); return v; }, 1180, 1100, Path.Combine(folder, $"gorunum-obs-ayri-{suffix}-{language}.png"));
                Save(() => { var v = new MainView(Demo(OnboardingStep.Done)); v.ShowXboxSourceTab(); return v; }, 1180, 900, Path.Combine(folder, $"xbox-kaynak-{suffix}-{language}.png"));
                // İkinci ekran kartı (Bağlantı sekmesi) ve rehberdeki "TV'm desteklenmiyor" bölümü
                Save(() => { var v = new MainView(Demo(OnboardingStep.Done)); v.ShowConnectionTab(); return v; }, 1180, 1000, Path.Combine(folder, $"ikinci-ekran-{suffix}-{language}.png"));
                Save(() => { var v = new MainView(Demo(OnboardingStep.Done)); v.ShowSecondScreenGuide(); return v; }, 1180, 2100, Path.Combine(folder, $"ikinci-ekran-rehber-{suffix}-{language}.png"));
                // Sihirbazın konsol adımı, Xbox seçili: giriş yapılmamış ve giriş yapılmış hâli
                Save(() => { var c = Demo(OnboardingStep.Welcome); c.Settings = c.Settings with { VideoSource = GameTranslator.Shell.VideoSourceType.Xbox }; var v = new OnboardingView(c); v.ShowStep(2); return v; }, 960, 640, Path.Combine(folder, $"adim-2-xbox-{suffix}-{language}.png"));
                Save(() => { var c = Demo(OnboardingStep.Welcome); c.Settings = c.Settings with { VideoSource = GameTranslator.Shell.VideoSourceType.Xbox }; c.CompleteXboxLoginAsync().GetAwaiter().GetResult(); var v = new OnboardingView(c); v.ShowStep(2); return v; }, 960, 640, Path.Combine(folder, $"adim-2-xbox-giris-{suffix}-{language}.png"));
                // Giriş yapılmış hâli: konsol listesi
                Save(() =>
                {
                    var controller = Demo(OnboardingStep.Done);
                    controller.CompleteXboxLoginAsync().GetAwaiter().GetResult();
                    var v = new MainView(controller);
                    v.ShowXboxSourceTab();
                    return v;
                }, 1180, 900, Path.Combine(folder, $"xbox-kaynak-giris-{suffix}-{language}.png"));
                Save(() => { var v = new MainView(Demo(OnboardingStep.Done)); v.ShowXboxGuide(); return v; }, 1180, 1500, Path.Combine(folder, $"xbox-rehber-{suffix}-{language}.png"));
                // TV uygulaması kurulum kartı (geliştirici modu kapalı): yeni tasarımda nasıl durduğu
                Save(() =>
                {
                    var c = Demo(OnboardingStep.Done);
                    var card = new Views.Steps.TvAppCard { DataContext = c, Width = 560 };
                    var flags = System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic;
                    typeof(Views.Steps.TvAppCard).GetField("controller", flags)!.SetValue(card, c);
                    var show = typeof(Views.Steps.TvAppCard).GetMethod("Show", flags)!;
                    show.Invoke(card, [new TvAppState(TvAppKind.DevModeOff)]);
                    // Kart yüklenince kendi durumunu yeniden okur (örnekte bilinmiyor, gizlenir); örnek durum ondan sonra verilir
                    card.Loaded += (_, _) => show.Invoke(card, [new TvAppState(TvAppKind.DevModeOff)]);
                    return card;
                }, 600, 420, Path.Combine(folder, $"tv-kart-{suffix}-{language}.png"));
                // Uygulamanın gerçek penceresi: XAML'ı (simge dahil) açılıştaki gibi yüklenir.
                Save(() => Detach(new ShellWindow(Demo(OnboardingStep.Done))), 640, 720, Path.Combine(folder, $"pencere-{suffix}-{language}.png"));
                Save(() => Detach(new SettingsWindow(Demo(OnboardingStep.Done))), 600, 720, Path.Combine(folder, $"ayarlar-{suffix}-{language}.png"));
                Save(() => Detach(new AboutWindow()), 460, 640, Path.Combine(folder, $"hakkinda-{suffix}-{language}.png"));
                Save(() => Detach(new GamePickerWindow()), 440, 560, Path.Combine(folder, $"oyun-sec-{suffix}-{language}.png"));
            }
        }
        Localizer.Use(before);
        app.Shutdown();
    }

    /// <summary>Pencerenin içeriğini başka bir pencerede çizebilmek için kendi penceresinden ayırır.</summary>
    private static FrameworkElement Detach(Window window)
    {
        var content = (FrameworkElement)window.Content;
        window.Content = null;
        return content;
    }

    private static AppController Demo(OnboardingStep step)
    {
        var controller = new AppController(new DemoServices(), new DemoStore(step == OnboardingStep.Done), new DemoSettings(), new DemoSleep(),
            Localizer.Current, action => action());
        if (step != OnboardingStep.Done) controller.Step = step;
        return controller;
    }

    private static void Save(Func<FrameworkElement> make, int width, int height, string path)
    {
        try
        {
            Render(make(), width, height, path);
        }
        catch (Exception error)
        {
            File.AppendAllText(Path.Combine(Path.GetDirectoryName(path)!, "errors.txt"), $"[{Path.GetFileName(path)}] {error}\n\n");
        }
    }

    private static void Render(FrameworkElement view, int width, int height, string path)
    {
        // Görüntüye pencere zemini de girsin (içerik saydam zemin üzerinde çizilir).
        var root = new System.Windows.Controls.Border { Child = view };
        root.SetResourceReference(System.Windows.Controls.Border.BackgroundProperty, "windowBrush");
        root.SetResourceReference(System.Windows.Documents.TextElement.ForegroundProperty, "textPrimaryBrush");
        var window = new Window
        {
            Content = root, Width = width, Height = height, WindowStyle = WindowStyle.None, ShowInTaskbar = false,
            Left = -10000, Top = -10000, ShowActivated = false,
        };
        window.SetResourceReference(Window.BackgroundProperty, "backgroundBrush");
        window.Show();
        window.UpdateLayout();
        // Yükleme sırasında başlayan arama vb. işlerin ekrana yansıması için kısa bekleme.
        var frame = new System.Windows.Threading.DispatcherFrame();
        var timer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(600) };
        timer.Tick += (_, _) => { timer.Stop(); frame.Continue = false; };
        timer.Start();
        System.Windows.Threading.Dispatcher.PushFrame(frame);

        var bitmap = new RenderTargetBitmap(width * 2, height * 2, 192, 192, PixelFormats.Pbgra32);
        bitmap.Render((Visual)window.Content);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(path);
        encoder.Save(file);
        window.Close();
    }

    /// <summary>Xbox kartı çizilebilsin diye: giriş kodu ve iki konsol döndüren, hiçbir yere bağlanmayan servis.</summary>
    private sealed class DemoXbox : IXboxService
    {
#pragma warning disable CS0067
        public event Action<string>? TokenUpdated;
#pragma warning restore CS0067
        public void Clear() { }
        public Task<XboxLoginChallenge> BeginLoginAsync() => Task.FromResult(new XboxLoginChallenge("ABCD1234", new Uri("https://www.microsoft.com/link"), 900));
        public Task<string> CompleteLoginAsync() => Task.FromResult("{}");
        public Task<IReadOnlyList<XboxConsole>> DiscoverAsync(string token) => Task.FromResult<IReadOnlyList<XboxConsole>>(
            [new("1", "Salon Xbox", "Xbox Series X", true), new("2", "Oda Xbox", "Xbox One", false)]);
        public IStreamSession MakeStream(XboxConsole console, string token) => throw new NotSupportedException();
    }

    private sealed class DemoServices : IAppServices
    {
        public IXboxService? Xbox { get; } = new DemoXbox();
        public ITranslationModel Translation { get; } = new DemoTranslation();
        public Task<IReadOnlyList<ConsoleInfo>> DiscoverConsolesAsync() => Task.FromResult<IReadOnlyList<ConsoleInfo>>([new("192.168.1.20", "PS5-123", true)]);
        public Task<byte[]> AccountIdFromRedirectAsync(Uri redirect) => Task.FromResult(new byte[8]);
        public Task<RegisteredConsole> RegisterAsync(string host, byte[] accountId, uint pin) => throw new NotSupportedException();
        public Task<IReadOnlyList<LgTv>> DiscoverTvsAsync() => Task.FromResult<IReadOnlyList<LgTv>>([new("[LG] webOS TV", "192.168.1.30")]);
        public Task<TvPairing> PairTvAsync(LgTv tv, string? key, string server) => throw new NotSupportedException();
        public IStreamSession MakeStream(RegisteredConsole? console, byte[]? accountId, UserSettings? settings = null) => throw new NotSupportedException();
        public IKeeper MakeKeeper(LgTv tv, string? key, IReadOnlyDictionary<string, string> parameters, Func<bool> appConnected, Action<LgTv> onMoved,
                       Action<string> onKey) => throw new NotSupportedException();
        public string? BundledTvAppVersion => null;
        public Task<bool> IsDevModeOnAsync(string host) => Task.FromResult(false);
        public Task<string> FetchDevKeyAsync(string host, string passphrase) => throw new NotSupportedException();
        public Task<string?> InstalledTvAppVersionAsync(string host, string devKey) => throw new NotSupportedException();
        public Task InstallTvAppAsync(string host, string devKey) => throw new NotSupportedException();
        public Task<int?> RenewDevSessionAsync(string host, string devKey) => Task.FromResult<int?>(null);
        public BundledTvApp? BundledApp(TvBrand brand) => null;
        public Task<bool> IsSamsungDevModeOnAsync(string host) => Task.FromResult(false);
        public Task InstallSamsungAppAsync(string host, BundledTvApp app, Action<TvInstallEvent> onEvent) => throw new NotSupportedException();
        public Task<bool> IsAndroidDebugOnAsync(string host) => Task.FromResult(false);
        public Task<IReadOnlyList<string>> ScanAndroidTvsAsync() => Task.FromResult<IReadOnlyList<string>>([]);
        public Task<string> InstallAndroidAppAsync(string host, BundledTvApp app, Action<TvInstallEvent> onEvent) => throw new NotSupportedException();
        public ITextReader? MakeReader(string language) => null;
    }

    private sealed class DemoTranslation : ITranslationModel
    {
        public bool IsSupported(string source, string target) => source == "en";
        public bool IsInstalled(string source, string target) => true;
        public Task DownloadAsync(string source, string target, IProgress<double> progress, CancellationToken cancellationToken) => Task.CompletedTask;
        public ITextTranslator Create(string source, string target) => throw new NotSupportedException();
    }

    private sealed class DemoStore(bool ready) : IAppStore
    {
        public void Save<T>(string key, T value) { }
        public T? Load<T>(string key) => !ready ? default : key switch
        {
            StoreKeys.AccountId => (T)(object)new byte[8],
            StoreKeys.Console => (T)(object)new RegisteredConsole("192.168.1.20", "PS5-123", new byte[16], new byte[16]),
            StoreKeys.Tv => (T)(object)new LgTv("[LG] webOS TV", "192.168.1.30"),
            StoreKeys.TvKey => (T)(object)"key",
            _ => default,
        };
        public void Remove(string key) { }
    }

    private sealed class DemoSettings : ISettingsStore
    {
        public UserSettings Load() => new();
        public void Save(UserSettings settings) { }
    }

    private sealed class DemoSleep : ISleepGuard
    {
        public void Begin() { }
        public void End() { }
    }
}
