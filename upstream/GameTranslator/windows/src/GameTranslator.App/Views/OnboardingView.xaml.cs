// SPDX-License-Identifier: AGPL-3.0-only

using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using GameTranslator.Core;
using GameTranslator.Core.LgTv;
using GameTranslator.RemotePlay;
using GameTranslator.Shell;
using GameTranslator.App.Services;

namespace GameTranslator.App.Views;

/// <summary>Kurulum sihirbazı: Mac sürümüyle birebir aynı 5 adımlı modern kurulum arayüzü.</summary>
public partial class OnboardingView : UserControl
{
    private readonly AppController controller;
    private int currentStep = 1;
    private string selectedTvPlatform = "LG";

    public OnboardingView(AppController controller)
    {
        this.controller = controller;
        DataContext = controller;
        if (controller.Tv != null)
        {
            selectedTvPlatform = controller.Tv.Brand switch
            {
                TvBrand.Lg => "LG",
                TvBrand.Samsung => "Samsung",
                TvBrand.AndroidTv when controller.Tv.Name.Contains("PC") => "PC",
                TvBrand.AndroidTv => "Android",
                _ => "LG"
            };
        }
        else
        {
            selectedTvPlatform = "PC";
        }
        InitializeComponent();
        LanguageSwitch.Controller = controller;
        UpdateTvPlatformSelection();
        UpdateStepView();
    }

    /// <summary>Ekran görüntüsü aracı (--render-screens) her adımı ayrı çizebilsin diye.</summary>
    internal void ShowStep(int step) => SetStep(step);

    /// <summary>Arayüz dili değişip ekran yeniden kurulurken aynı adımda kalınsın diye.</summary>
    internal int CurrentStep => currentStep;

    private void SetStep(int step)
    {
        currentStep = Math.Clamp(step, 1, 4);
        UpdateStepView();
    }

    private void UpdateStepView()
    {
        StepBadgeText.Text = Localizer.Current.Format("Step %lld of %lld", currentStep, 4);

        // Panel Görünürlükleri
        Step1Panel.Visibility = currentStep == 1 ? Visibility.Visible : Visibility.Collapsed;
        Step2Panel.Visibility = currentStep == 2 ? Visibility.Visible : Visibility.Collapsed;
        Step3Panel.Visibility = currentStep == 3 ? Visibility.Visible : Visibility.Collapsed;
        Step4Panel.Visibility = currentStep == 4 ? Visibility.Visible : Visibility.Collapsed;

        // Butonlar
        BackButton.Visibility = currentStep == 1 ? Visibility.Hidden : Visibility.Visible;
        NextButton.Content = currentStep == 4 ? Localizer.Current["Finish"] : Localizer.Current["Continue"];
        SkipButton.Visibility = currentStep == 4 ? Visibility.Hidden : Visibility.Visible;

        // Kapsül Renkleri (Progress Pills)
        UpdatePill(PillBar1, PillText1, 1);
        UpdatePill(PillBar2, PillText2, 2);
        UpdatePill(PillBar3, PillText3, 3);
        UpdatePill(PillBar4, PillText4, 4);

        if (currentStep == 2)
        {
            UpdateConsoleKind();
            if (controller.HasAccount)
            {
                UpdatePsnIdDisplay();
            }

            if (PairStatusText != null)
            {
                var savedHost = ChiakiLauncher.GetSavedHost();
                if (!string.IsNullOrWhiteSpace(savedHost) && Ps5HostInput != null && string.IsNullOrWhiteSpace(Ps5HostInput.Text))
                {
                    Ps5HostInput.Text = savedHost;
                }

                var host = controller.Console?.Host ?? Typed(Ps5HostInput);
                if (!string.IsNullOrWhiteSpace(host) && ChiakiLauncher.IsRegistered(host))
                {
                    PairStatusText.Text = Localizer.Current.Format("✓ The PS5 is already paired (%@). You can continue.", host);
                    PairStatusText.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
                }
                else if (string.IsNullOrWhiteSpace(Ps5HostInput?.Text))
                {
                    // Otomatik ağda tara
                    OnScanPs5(this, new RoutedEventArgs());
                }
            }
        }
        else if (currentStep == 3)
        {
            if (AndroidHostInput != null)
            {
                if (!string.IsNullOrEmpty(controller.ConnectedTvIp))
                {
                    AndroidHostInput.Text = controller.ConnectedTvIp;
                }
                else if (controller.Tv != null && controller.Tv.Brand == TvBrand.AndroidTv && !string.IsNullOrWhiteSpace(controller.Tv.Host) && controller.Tv.Host != "192.168.1.50")
                {
                    AndroidHostInput.Text = controller.Tv.Host;
                }
            }
        }
    }

    /// <summary>Adım göstergesi: geçilen ve o anki adım vurgu renginde, sıradakiler soluk.</summary>
    private void UpdatePill(Border bar, TextBlock text, int step)
    {
        var reached = step <= currentStep;
        bar.SetResourceReference(Border.BackgroundProperty, reached ? "accentBrush" : "cardStrongBrush");
        if (bar.Child is TextBlock number) number.SetResourceReference(TextBlock.ForegroundProperty, reached ? "onAccentBrush" : "textSecondaryBrush");
        text.SetResourceReference(TextBlock.ForegroundProperty, step == currentStep ? "textPrimaryBrush" : reached ? "textSecondaryBrush" : "textTertiaryBrush");
        text.FontWeight = step == currentStep ? FontWeights.SemiBold : FontWeights.Normal;
    }

    // --- Konsol seçimi: PS5 (Remote Play) ya da Xbox (ev yayını) ---

    private XboxLoginChallenge? xboxLogin;
    private bool xboxSigningIn, xboxLoadingConsoles, xboxConsolesLoaded, fillingXboxList;
    private string? xboxError;

    private bool XboxChosen => controller.Settings.VideoSource == VideoSourceType.Xbox;

    private void OnConsoleKind(object sender, RoutedEventArgs e)
    {
        var xbox = ConsoleXbox.IsChecked == true;
        if (xbox != XboxChosen)
            controller.Settings = controller.Settings with { VideoSource = xbox ? VideoSourceType.Xbox : VideoSourceType.RemotePlay };
        UpdateConsoleKind();
    }

    /// <summary>Seçili konsola göre adımın içeriği: PS5'te giriş ve eşleştirme, Xbox'ta Microsoft girişi ve konsol seçimi.</summary>
    private void UpdateConsoleKind()
    {
        if (ConsoleXbox == null || XboxSetup == null) return;
        var text = Localizer.Current;
        // Xbox bileşeni bu kopyada yoksa (kaynak koddan derlenen kopya) seçenek kapalıdır
        ConsoleXbox.IsEnabled = controller.XboxAvailable;
        var xbox = XboxChosen && controller.XboxAvailable;
        ConsoleXbox.IsChecked = xbox;
        ConsolePs5.IsChecked = !xbox;
        Ps5Setup.Visibility = xbox ? Visibility.Collapsed : Visibility.Visible;
        XboxSetup.Visibility = xbox ? Visibility.Visible : Visibility.Collapsed;
        ConsoleLead.Text = xbox
            ? text["Sign in with your Microsoft account, then choose your Xbox. You only do this once."]
            : text["Sign in with the second account, then enter the code from the PS5. You only do this once."];
        if (!xbox) return;

        var signedIn = controller.HasXboxAccount;
        XboxSignInButton.IsEnabled = !xboxSigningIn;
        XboxSignInButton.Content = xboxSigningIn ? text["Waiting for sign-in…"] : signedIn ? text["Sign in with another account"] : text["Sign in with a Microsoft account"];
        XboxCodeCard.Visibility = xboxSigningIn && xboxLogin is not null ? Visibility.Visible : Visibility.Collapsed;
        if (xboxLogin is not null) XboxCodeValue.Text = xboxLogin.Code;
        XboxSignInStatus.Text = xboxError ?? (signedIn ? text["✓ Signed in."] : xboxSigningIn ? text["Waiting for the Microsoft sign-in"] : "");
        XboxSignInStatus.SetResourceReference(TextBlock.ForegroundProperty, xboxError is not null ? "dangerBrush" : signedIn ? "successBrush" : "textSecondaryBrush");

        XboxConsoleList.IsEnabled = signedIn;
        var console = controller.SelectedXboxConsole;
        XboxConsoleStatus.Text = !signedIn ? text["Sign in first."]
            : xboxLoadingConsoles ? text["Looking for consoles…"]
            : console is not null ? text.Format("✓ Ready: %@. You can continue.", console.Name)
            : XboxConsoleList.Items.Count == 0 ? text["No Xbox was found on this account. Make sure you are signed in on the console with the same Microsoft account, then press \"Refresh consoles\"."]
            : text["Choose your Xbox"];
        XboxConsoleStatus.SetResourceReference(TextBlock.ForegroundProperty, console is not null && signedIn ? "successBrush" : "textSecondaryBrush");
        if (signedIn && !xboxConsolesLoaded && !xboxLoadingConsoles) _ = LoadXboxConsolesAsync();
    }

    private async void OnXboxSignIn(object sender, RoutedEventArgs e)
    {
        if (xboxSigningIn) return;
        xboxSigningIn = true;
        xboxError = null;
        xboxLogin = null;
        UpdateConsoleKind();
        try
        {
            xboxLogin = await controller.BeginXboxLoginAsync();
            UpdateConsoleKind();
            await controller.CompleteXboxLoginAsync();
            xboxConsolesLoaded = false;
        }
        catch (Exception error)
        {
            xboxError = Localizer.Current.Format("Could not finish the Microsoft sign-in: %@", error.Message);
        }
        finally
        {
            xboxSigningIn = false;
            xboxLogin = null;
            UpdateConsoleKind();
        }
    }

    private void OnXboxCopyCode(object sender, RoutedEventArgs e)
    {
        if (xboxLogin is null) return;
        try { Clipboard.SetText(xboxLogin.Code); }
        catch (System.Runtime.InteropServices.ExternalException) { }
    }

    private void OnXboxOpenPage(object sender, RoutedEventArgs e)
    {
        if (xboxLogin is not null) controller.OpenInBrowser(xboxLogin.Url);
    }

    private void OnXboxRefresh(object sender, RoutedEventArgs e)
    {
        xboxError = null;
        _ = LoadXboxConsolesAsync();
    }

    /// <summary>Hesaptaki konsolları listeler; uzaktan özellikleri açık tek konsol varsa kendiliğinden seçilir.</summary>
    private async Task LoadXboxConsolesAsync()
    {
        if (xboxLoadingConsoles || !controller.HasXboxAccount) return;
        xboxLoadingConsoles = true;
        UpdateConsoleKind();
        try
        {
            var consoles = await controller.DiscoverXboxConsolesAsync();
            xboxConsolesLoaded = true;
            var usable = consoles.Where(c => c.StreamingEnabled).ToList();
            if (controller.SelectedXboxConsole is null && usable.Count == 1) controller.SelectXboxConsole(usable[0]);
            fillingXboxList = true;
            XboxConsoleList.Items.Clear();
            foreach (var item in consoles)
            {
                var entry = new ComboBoxItem
                {
                    Content = item.StreamingEnabled ? $"{item.Name} ({item.Model})" : Localizer.Current.Format("%1$@ (%2$@) · remote features off", item.Name, item.Model),
                    Tag = item, IsEnabled = item.StreamingEnabled,
                };
                XboxConsoleList.Items.Add(entry);
                if (item.Id == controller.SelectedXboxConsole?.Id) XboxConsoleList.SelectedItem = entry;
            }
            fillingXboxList = false;
        }
        catch (Exception error)
        {
            xboxConsolesLoaded = true;   // her güncellemede yeniden denenmesin; kullanıcı "yenile"ye basar
            fillingXboxList = false;
            xboxError = Localizer.Current.Format("Could not get the consoles: %@", error.Message);
        }
        finally
        {
            xboxLoadingConsoles = false;
            UpdateConsoleKind();
        }
    }

    private void OnXboxConsolePicked(object sender, SelectionChangedEventArgs e)
    {
        if (fillingXboxList || XboxConsoleList.SelectedItem is not ComboBoxItem { Tag: XboxConsole selected }) return;
        xboxError = null;
        controller.SelectXboxConsole(selected);
        UpdateConsoleKind();
    }

    private void OnBack(object sender, RoutedEventArgs e)
    {
        if (currentStep > 1) SetStep(currentStep - 1);
    }

    private void OnNext(object sender, RoutedEventArgs e)
    {
        if (currentStep == 3)
        {
            SaveCurrentTvPlatform();
        }

        if (currentStep < 4)
        {
            SetStep(currentStep + 1);
        }
        else
        {
            controller.Step = OnboardingStep.Done;
        }
    }

    private void OnSkipToMain(object sender, RoutedEventArgs e)
    {
        SaveCurrentTvPlatform();
        controller.Step = OnboardingStep.Done;
    }

    private void SaveCurrentTvPlatform()
    {
        try
        {
            switch (selectedTvPlatform)
            {
                case "LG":
                    var lgHost = (Typed(LgHostInput) is { Length: > 0 } typedLgHostInput ? typedLgHostInput : controller.Tv?.Host);
                    if (!string.IsNullOrWhiteSpace(lgHost))
                        _ = controller.PairAsync(new LgTv("LG webOS TV", lgHost, TvBrand.Lg));
                    break;
                case "Samsung":
                    var samHost = (Typed(SamsungHostInput) is { Length: > 0 } typedSamsungHostInput ? typedSamsungHostInput : controller.Tv?.Host);
                    if (!string.IsNullOrWhiteSpace(samHost))
                        _ = controller.PairAsync(new LgTv("Samsung Smart TV", samHost, TvBrand.Samsung));
                    break;
                case "Android":
                    var andHost = (Typed(AndroidHostInput) is { Length: > 0 } typedAndroidHostInput ? typedAndroidHostInput : controller.Tv?.Host);
                    if (!string.IsNullOrWhiteSpace(andHost))
                        _ = controller.PairAsync(new LgTv("Android TV", andHost, TvBrand.AndroidTv));
                    break;
                case "PC":
                default:
                    if (controller.Settings.PcOverlay is null) controller.Settings = controller.Settings with { PcOverlay = true };
                    DesktopSubtitleOverlay.IsOverlayEnabled = controller.Settings.PcOverlay == true;
                    _ = controller.PairAsync(new LgTv(MainView.PcScreenTarget, "127.0.0.1", TvBrand.AndroidTv));
                    break;
            }
        }
        catch { }
    }

    private void OnPill1(object sender, RoutedEventArgs e) => SetStep(1);
    private void OnPill2(object sender, RoutedEventArgs e) => SetStep(2);
    private void OnPill3(object sender, RoutedEventArgs e) => SetStep(3);
    private void OnPill4(object sender, RoutedEventArgs e) => SetStep(4);

    // ==========================================
    // Adım 2: PSN Girişi
    // ==========================================
    private void OnToggleManualPsnCode(object sender, RoutedEventArgs e)
    {
        if (ManualPsnCard != null)
        {
            ManualPsnCard.Visibility = ManualPsnCard.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
        }
    }

    private void UpdatePsnIdDisplay()
    {
        var b64 = controller.AccountIdBase64;
        if (!string.IsNullOrWhiteSpace(b64))
        {
            if (PsnStatusText != null)
            {
                PsnStatusText.Text = Localizer.Current["✓ PlayStation Network account connected!"];
                PsnStatusText.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
            }
            if (PsnSignInButton != null)
            {
                PsnSignInButton.Content = Localizer.Current["✓ PSN Account Connected (Change)"];
                PsnSignInButton.Background = new SolidColorBrush(Color.FromRgb(16, 185, 129));
            }
        }
    }

    private async void OnOpenSonyLogin(object sender, RoutedEventArgs e)
    {
        try
        {
            var redirect = PsnLoginWindow.Show(Window.GetWindow(this));
            if (redirect != null)
            {
                await controller.CompletePsnLoginAsync(redirect);
                UpdatePsnIdDisplay();
            }
        }
        catch (Exception ex)
        {
            // WebView2 yoksa varsayılan tarayıcıda aç
            try
            {
                var url = PsnAccount.AuthorizeUrl.AbsoluteUri;
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            }
            catch { }
            PsnStatusText.Text = Localizer.Current.Format("Sign-in note: %@", ex.Message);
            PsnStatusText.Foreground = new SolidColorBrush(Color.FromRgb(245, 158, 11));
        }
    }

    private void OnOpenExternalSonyBrowser(object sender, RoutedEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo(PsnAccount.AuthorizeUrl.AbsoluteUri) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            MessageBox.Show(Localizer.Current.Format("Could not open the browser: %@", ex.Message), Localizer.Current["Error"], MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private static string? ExtractCode(string input)
    {
        if (string.IsNullOrWhiteSpace(input)) return null;
        input = input.Trim();

        // 1. Doğrudan Uri olarak parse etmeyi dene
        if (Uri.TryCreate(input, UriKind.Absolute, out var uri))
        {
            var code = PsnAccount.CodeFromRedirect(uri);
            if (!string.IsNullOrEmpty(code)) return code;

            if (!string.IsNullOrEmpty(uri.Query))
            {
                var queryMatch = System.Text.RegularExpressions.Regex.Match(uri.Query, @"[?&]code=([^&]+)");
                if (queryMatch.Success) return Uri.UnescapeDataString(queryMatch.Groups[1].Value);
            }
        }

        // 2. Metin içinde code= ara
        var match = System.Text.RegularExpressions.Regex.Match(input, @"(?:^|[?&])code=([^&\s]+)");
        if (match.Success)
        {
            return Uri.UnescapeDataString(match.Groups[1].Value);
        }

        // 3. Doğrudan ham kod verilmişse (v3.xxx veya tek parça alfanümerik)
        if (!input.Contains(' ') && !input.Contains('/') && input.Length >= 8)
        {
            return input;
        }

        return null;
    }

    private async void OnVerifyPsnCode(object sender, RoutedEventArgs e)
    {
        var raw = PsnCodeInput.Text.Trim();
        if (string.IsNullOrWhiteSpace(raw))
        {
            MessageBox.Show(Localizer.Current["Paste the address from the browser, or the code."], Localizer.Current["Code required"], MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var code = ExtractCode(raw);
        if (string.IsNullOrWhiteSpace(code))
        {
            PsnStatusText.Text = Localizer.Current["There is no code in this address. It must contain 'code='."];
            PsnStatusText.Foreground = new SolidColorBrush(Color.FromRgb(239, 68, 68));
            return;
        }

        PsnVerifyButton.IsEnabled = false;
        PsnStatusText.Text = Localizer.Current["⏳ Verifying the code with Sony..."];
        PsnStatusText.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));

        try
        {
            await controller.CompletePsnLoginWithCodeAsync(code);
            UpdatePsnIdDisplay();
            PsnCodeInput.Text = "";
        }
        catch (Exception ex)
        {
            PsnStatusText.Text = Localizer.Current.Format("❌ Verification error: %@", ex.Message);
            PsnStatusText.Foreground = new SolidColorBrush(Color.FromRgb(239, 68, 68));
        }
        finally
        {
            PsnVerifyButton.IsEnabled = true;
        }
    }



    // ==========================================
    // Adım 3: PS5 Eşleştirme
    // ==========================================
    private async void OnScanPs5(object sender, RoutedEventArgs e)
    {
        PairStatusText.Text = Localizer.Current["Scanning the local network for a PS5..."];
        PairStatusText.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));
        try
        {
            var found = await controller.DiscoverConsolesAsync();
            var first = found.FirstOrDefault();
            if (first != null)
            {
                Ps5HostInput.Text = first.Host;
                PairStatusText.Text = Localizer.Current.Format("✓ PS5 Found: %1$@ (%2$@)", first.Host, first.Name);
                PairStatusText.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
            }
            else
            {
                PairStatusText.Text = Localizer.Current["No PS5 found. Is it on and on the same network? You can also type its address by hand."];
                PairStatusText.Foreground = new SolidColorBrush(Color.FromRgb(245, 158, 11));
            }
        }
        catch (Exception ex)
        {
            PairStatusText.Text = Localizer.Current.Format("Search note: %@", ex.Message);
        }
    }

    /// <summary>
    /// Elle yazılan PS5/TV adresi temizlenmiş haliyle ("192,168,1,5", ":3000", "http://" düzelir); adres değilse boş
    /// (boş adres zaten "adresi yaz" uyarısı veriyor). Bozuk adres bağlantıyı kurmadan bitiriyordu (CouchSubs 4c1f6ec).
    /// </summary>
    private static string Typed(System.Windows.Controls.TextBox? box) => ManualAddress.Normalize(box?.Text) ?? "";

    private async void OnPairPs5(object sender, RoutedEventArgs e)
    {
        var host = Typed(Ps5HostInput);
        if (string.IsNullOrWhiteSpace(host))
        {
            PairStatusText.Text = Localizer.Current["Type the PS5's address."];
            PairStatusText.Foreground = new SolidColorBrush(Color.FromRgb(239, 68, 68));
            return;
        }

        var pin = Ps5PinInput.Text.Trim();
        var digits = new string([.. pin.Where(char.IsDigit)]);
        if (digits.Length != 8)
        {
            PairStatusText.Text = Localizer.Current["Enter the 8-digit code on the PS5 screen (Settings › System › Remote Play › Pair Device)."];
            PairStatusText.Foreground = new SolidColorBrush(Color.FromRgb(239, 68, 68));
            return;
        }

        PairButton.IsEnabled = false;
        PairButton.Content = Localizer.Current["⏳ Pairing with the PS5..."];
        PairStatusText.Text = Localizer.Current.Format("Setting up an encrypted connection to the PS5 at %@ and verifying the PIN...", host);
        PairStatusText.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));

        try
        {
            var accountId = controller.AccountId;
            ChiakiLauncher.SyncConfig(host, accountId);
            await controller.RegisterAsync(host, digits);

            PairStatusText.Text = Localizer.Current.Format("✓ PS5 Paired! (%@) Moving on to the TV setup step...", host);
            PairStatusText.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
            PairButton.Content = Localizer.Current["✓ Paired!"];

            await Task.Delay(800);
            SetStep(3);
        }
        catch (Exception ex)
        {
            PairButton.IsEnabled = true;
            PairButton.Content = Localizer.Current["🎮 Pair with the PS5 ➔"];
            PairStatusText.Text = Localizer.Current.Format("Pairing failed: %@", ex.Message);
            PairStatusText.Foreground = new SolidColorBrush(Color.FromRgb(239, 68, 68));
        }
    }

    private void OnSkipPs5ForCaptureCard(object sender, RoutedEventArgs e)
    {
        try
        {
            // Yakalama kartı veya OBS modu seçildiğinde ayarları güncelle
            controller.Settings = controller.Settings with { VideoSource = VideoSourceType.CaptureCard };

            // Eğer konsol henüz kayıtlı değilse sahte bir kayıt oluşturarak sonraki adımların bloklanmasını engelle
            if (controller.Console == null)
            {
                var mockKey = new byte[16];
                var mockConsole = new RegisteredConsole("127.0.0.1", Localizer.Current["Capture Card / OBS"], mockKey, mockKey);
                controller.SetRegisteredConsole(mockConsole);
            }
        }
        catch { }

        PairStatusText.Text = Localizer.Current["✓ Capture Card / OBS mode turned on. Moving on to the TV step..."];
        PairStatusText.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
        SetStep(3);
    }

    // ==========================================
    // Adım 4: Akıllı TV Platform Seçimi & Eşleştirme
    // ==========================================
    private async void OnSelectTvPc(object sender, RoutedEventArgs e)
    {
        selectedTvPlatform = "PC";
        UpdateTvPlatformSelection();
        // Bilgisayar ekranında oynayan çeviriyi görsün: altyazı katmanı açılır ve hatırlanır
        controller.Settings = controller.Settings with { PcOverlay = true };
        DesktopSubtitleOverlay.IsOverlayEnabled = true;
        try
        {
            await controller.PairAsync(new LgTv(MainView.PcScreenTarget, "127.0.0.1", TvBrand.AndroidTv));
        }
        catch { }
        TvStatusFeedback.Text = Localizer.Current["✓ Computer & OBS mode saved."];
        TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
    }

    private async void OnSelectTvSamsung(object sender, RoutedEventArgs e)
    {
        selectedTvPlatform = "Samsung";
        UpdateTvPlatformSelection();
        try
        {
            var host = (Typed(SamsungHostInput) is { Length: > 0 } typedSamsungHostInput ? typedSamsungHostInput : controller.Tv?.Host);
            if (!string.IsNullOrWhiteSpace(host))
                await controller.PairAsync(new LgTv("Samsung Smart TV", host, TvBrand.Samsung));
            TvStatusFeedback.Text = Localizer.Current["✓ Samsung Smart TV mode selected."];
            TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
        }
        catch { }
    }

    private async void OnSelectTvLg(object sender, RoutedEventArgs e)
    {
        selectedTvPlatform = "LG";
        UpdateTvPlatformSelection();
        try
        {
            var host = (Typed(LgHostInput) is { Length: > 0 } typedLgHostInput ? typedLgHostInput : controller.Tv?.Host);
            if (!string.IsNullOrWhiteSpace(host))
                await controller.PairAsync(new LgTv("LG webOS TV", host, TvBrand.Lg));
            TvStatusFeedback.Text = Localizer.Current["✓ LG webOS TV mode selected."];
            TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
        }
        catch { }
    }

    private async void OnSelectTvAndroid(object sender, RoutedEventArgs e)
    {
        selectedTvPlatform = "Android";
        UpdateTvPlatformSelection();
        try
        {
            var host = (Typed(AndroidHostInput) is { Length: > 0 } typedAndroidHostInput ? typedAndroidHostInput : controller.Tv?.Host);
            if (!string.IsNullOrWhiteSpace(host))
                await controller.PairAsync(new LgTv("Android TV", host, TvBrand.AndroidTv));
            TvStatusFeedback.Text = Localizer.Current["✓ Android TV mode selected."];
            TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
        }
        catch { }
    }

    private void UpdateTvPlatformSelection()
    {
        var blueBrush = (Brush)FindResource("accentBrush");
        var defBrush = (Brush)FindResource("hairlineBrush");

        // Kenarlıklar
        TvBorderPc.BorderBrush = selectedTvPlatform == "PC" ? blueBrush : defBrush;
        TvBorderSamsung.BorderBrush = selectedTvPlatform == "Samsung" ? blueBrush : defBrush;
        TvBorderLg.BorderBrush = selectedTvPlatform == "LG" ? blueBrush : defBrush;
        TvBorderAndroid.BorderBrush = selectedTvPlatform == "Android" ? blueBrush : defBrush;

        // Rozetler
        TvBadgePc.Visibility = selectedTvPlatform == "PC" ? Visibility.Visible : Visibility.Collapsed;
        TvBadgeSamsung.Visibility = selectedTvPlatform == "Samsung" ? Visibility.Visible : Visibility.Collapsed;
        TvBadgeLg.Visibility = selectedTvPlatform == "LG" ? Visibility.Visible : Visibility.Collapsed;
        TvBadgeAndroid.Visibility = selectedTvPlatform == "Android" ? Visibility.Visible : Visibility.Collapsed;

        // Detay Panelleri
        TvDetailPc.Visibility = selectedTvPlatform == "PC" ? Visibility.Visible : Visibility.Collapsed;
        TvDetailSamsung.Visibility = selectedTvPlatform == "Samsung" ? Visibility.Visible : Visibility.Collapsed;
        TvDetailLg.Visibility = selectedTvPlatform == "LG" ? Visibility.Visible : Visibility.Collapsed;
        TvDetailAndroid.Visibility = selectedTvPlatform == "Android" ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnCopyObsUrl(object sender, RoutedEventArgs e)
    {
        // 8787 başka bir programdaysa sunucu başka porttadır; gösterilen ve kopyalanan adres gerçek port olmalı
        var url = $"http://localhost:{controller.ServerPort}/obs";
        ObsUrlText.Text = url;
        Clipboard.SetText(url);
        MessageBox.Show(Localizer.Current.Format("The OBS URL was copied to the clipboard:\n%@", url), Localizer.Current["Copied"], MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private async void OnSaveSamsung(object sender, RoutedEventArgs e)
    {
        var host = Typed(SamsungHostInput);
        if (string.IsNullOrWhiteSpace(host)) return;
        TvStatusFeedback.Text = Localizer.Current.Format("Saving the Samsung TV (%@)...", host);
        TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));
        try
        {
            await controller.PairAsync(new LgTv("Samsung Smart TV", host, TvBrand.Samsung));
        }
        catch { }
        TvStatusFeedback.Text = Localizer.Current.Format("✓ Samsung TV saved (%@). Open GameTranslator on the TV.", host);
        TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
    }

    private async void OnSaveLg(object sender, RoutedEventArgs e)
    {
        var host = Typed(LgHostInput);
        if (string.IsNullOrWhiteSpace(host)) return;
        TvStatusFeedback.Text = Localizer.Current.Format("Saving the LG TV (%@)...", host);
        TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));
        try
        {
            await controller.PairAsync(new LgTv("LG webOS TV", host, TvBrand.Lg));
        }
        catch { }
        TvStatusFeedback.Text = Localizer.Current.Format("✓ LG TV saved (%@). Open GameTranslator on the TV.", host);
        TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
    }

    private async void OnScanAndroidTv(object sender, RoutedEventArgs e)
    {
        ScanAndroidTvButton.IsEnabled = false;
        AndroidInstallStatus.Visibility = Visibility.Visible;
        AndroidInstallStatus.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));
        AndroidInstallStatus.Text = Localizer.Current["🔍 Looking for an Android TV on the network..."];
        try
        {
            string? first = controller.ConnectedTvIp;

            if (string.IsNullOrEmpty(first))
            {
                var found = await controller.Services.ScanAndroidTvsAsync();
                first = found.FirstOrDefault();
            }

            if (string.IsNullOrEmpty(first) && !string.IsNullOrWhiteSpace(controller.Tv?.Host) && controller.Tv.Host != "192.168.1.50")
            {
                first = controller.Tv.Host;
            }

            if (first != null)
            {
                AndroidHostInput.Text = first;
                AndroidInstallStatus.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
                AndroidInstallStatus.Text = Localizer.Current.Format("✓ Android TV found: %@. Connecting...", first);
                await controller.PairAsync(new LgTv("Android TV", first, TvBrand.AndroidTv));
                TvStatusFeedback.Text = Localizer.Current.Format("✓ Android TV found (%@) and connected!", first);
                TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
            }
            else
            {
                AndroidInstallStatus.Foreground = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                AndroidInstallStatus.Text = Localizer.Current["No TV found. Type its address above or open the app on the TV."];
            }
        }
        catch (Exception ex)
        {
            AndroidInstallStatus.Text = Localizer.Current.Format("Scan note: %@", ex.Message);
        }
        finally
        {
            ScanAndroidTvButton.IsEnabled = true;
        }
    }

    private async void OnInstallAndroidApp(object sender, RoutedEventArgs e)
    {
        var host = Typed(AndroidHostInput);
        if (string.IsNullOrWhiteSpace(host))
        {
            MessageBox.Show(Localizer.Current["Type the TV's address."], Localizer.Current["Address required"], MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        AndroidInstallButton.IsEnabled = false;
        AndroidInstallStatus.Visibility = Visibility.Visible;
        AndroidInstallStatus.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));
        AndroidInstallStatus.Text = Localizer.Current.Format("⏳ Connecting over ADB to the Android TV at %@...", host);

        try
        {
            var version = await controller.InstallAndroidTvAppAsync(host, ev =>
            {
                Dispatcher.Invoke(() =>
                {
                    if (ev.Kind == TvInstallEventKind.ApproveOnTv)
                    {
                        AndroidInstallStatus.Foreground = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                        AndroidInstallStatus.Text = Localizer.Current["⚠️ PLEASE LOOK AT THE TV: when it asks whether to allow USB / network debugging, choose 'Always allow' with the remote and press OK!"];
                    }
                    else
                    {
                        AndroidInstallStatus.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));
                        AndroidInstallStatus.Text = Localizer.Current["⏳ Sending the APK package to the TV and installing it..."];
                    }
                });
            });

            AndroidInstallStatus.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
            AndroidInstallStatus.Text = Localizer.Current.Format("✓ Done! GameTranslator v%@ was installed on the TV and opened.", version);
            TvStatusFeedback.Text = Localizer.Current.Format("✓ Android TV (%@) connected and the app is ready.", host);
            TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
        }
        catch (Exception ex)
        {
            AndroidInstallStatus.Foreground = new SolidColorBrush(Color.FromRgb(239, 68, 68));
            AndroidInstallStatus.Text = Localizer.Current.Format("❌ Install error: %@\n(On the TV, Developer options > Network/USB debugging (ADB) must be on, and the TV screen must be on)", ex.Message);
        }
        finally
        {
            AndroidInstallButton.IsEnabled = true;
        }
    }

    private async void OnSaveAndroid(object sender, RoutedEventArgs e)
    {
        var host = Typed(AndroidHostInput);
        if (string.IsNullOrWhiteSpace(host)) return;
        TvStatusFeedback.Text = Localizer.Current.Format("Pairing with the Android TV (%@)...", host);
        TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));
        try
        {
            await controller.PairAsync(new LgTv("Android TV", host, TvBrand.AndroidTv));
        }
        catch { }
        TvStatusFeedback.Text = Localizer.Current.Format("✓ Android TV saved (%@) and the connect signal was sent.", host);
        TvStatusFeedback.Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
    }

    private void OnOpenSamsungWgt(object sender, RoutedEventArgs e)
    {
        var path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "tv-app", "gametranslator-samsung_1.0.0.wgt");
        if (File.Exists(path))
            Process.Start("explorer.exe", $"/select,\"{path}\"");
        else
            Process.Start(new ProcessStartInfo("https://github.com/Sefflex/GameTranslator/releases") { UseShellExecute = true });
    }

    private void OnOpenLgIpk(object sender, RoutedEventArgs e)
    {
        var path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "tv-app", "com.gametranslator.tv_1.0.0_all.ipk");
        if (File.Exists(path))
            Process.Start("explorer.exe", $"/select,\"{path}\"");
        else
            Process.Start(new ProcessStartInfo("https://github.com/Sefflex/GameTranslator/releases") { UseShellExecute = true });
    }

    private void OnOpenAndroidApk(object sender, RoutedEventArgs e)
    {
        var path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "tv-app", "gametranslator-androidtv_1.0.0.apk");
        if (File.Exists(path))
            Process.Start("explorer.exe", $"/select,\"{path}\"");
        else
            Process.Start(new ProcessStartInfo("https://github.com/Sefflex/GameTranslator/releases") { UseShellExecute = true });
    }

    private void OnOpenGuidePdf(object sender, RoutedEventArgs e)
    {
        Process.Start(new ProcessStartInfo("https://github.com/Sefflex/GameTranslator/raw/main/docs/GameTranslator_TV_Kurulum_Rehberi.pdf") { UseShellExecute = true });
    }

    // ==========================================
    // Adım 5: Test
    // ==========================================
    private void OnTestDesktopOverlay(object sender, RoutedEventArgs e)
    {
        DesktopSubtitleOverlay.Display(Localizer.Current["GameTranslator: Desktop Screen Subtitles Are Working! [HUD TEST]"], 6000);
    }

    private void OnTestTv(object sender, RoutedEventArgs e)
    {
        controller.SendTestSubtitle();
        DesktopSubtitleOverlay.Display(Localizer.Current["GameTranslator: Live Subtitle Test Passed!"], 6000);
        MessageBox.Show(Localizer.Current["A test line was sent. If you can see it on the TV, everything is ready."], Localizer.Current["Sent"], MessageBoxButton.OK, MessageBoxImage.Information);
    }
}
