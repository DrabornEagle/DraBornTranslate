// SPDX-License-Identifier: AGPL-3.0-only

using System.Runtime.InteropServices.WindowsRuntime;
using GameTranslator.Core;
using GameTranslator.RemotePlay;
using Windows.Graphics.Imaging;
using Windows.Media.Capture;
using Windows.Media.Capture.Frames;
using Windows.Media.MediaProperties;

namespace GameTranslator.App.Services;

/// <summary>Bilgisayarın gördüğü bir video yakalama aygıtı (yakalama kartı, web kamerası).</summary>
public sealed record CaptureDevice(string Id, string Name)
{
    /// <summary>Yakalama kartı olduğu adından belli olanlar (Elgato, AVerMedia vb.) önce seçilir.</summary>
    public bool LooksLikeCaptureCard =>
        CardWords.Any(word => Name.Contains(word, StringComparison.OrdinalIgnoreCase));

    private static readonly string[] CardWords =
        ["Elgato", "Game Capture", "HD60", "4K60", "4K X", "4K S", "Cam Link", "AVerMedia", "Live Gamer", "Capture",
         "HDMI", "USB Video", "UVC", "Razer Ripsaw", "Magewell", "EVGA XR1", "Genki ShadowCast"];
}

/// <summary>
/// Yakalama kartını (Elgato HD60 S+/X, 4K X, Cam Link, AVerMedia ...) Windows'un kamera arayüzüyle (Media Foundation)
/// doğrudan okuyan IStreamSession. Kart Windows'a kamera gibi görünür; OBS ya da kartın kendi uygulaması gerekmez.
/// Kareler BGRA olarak saniyede en çok 15 kez verilir. Aygıt çıkarılır ya da başka uygulama alırsa 3 saniyede bir
/// yeniden denenir. PS5'te HDCP açıkken kart siyah görüntü verir; bu durum günlüğe bir kez yazılır.
/// </summary>
public sealed class CaptureDeviceStreamSession : IStreamSession
{
    public event Action<Frame>? FrameReceived;
    public event Action<SessionState>? StateChanged;
#pragma warning disable CS0067
    public event Action<RegisteredConsole>? ConsoleMoved;
#pragma warning restore CS0067

    /// <summary>Ayarlarda aygıtı gösteren önek: "device:Elgato HD60 X".</summary>
    public const string Prefix = "device:";

    private readonly string? wantedName;
    private CancellationTokenSource? cts;
    private MediaCapture? capture;
    private MediaFrameReader? reader;
    private bool running, streaming;
    private long lastFrameAt, darkSince;
    private bool darkReported;

    /// <param name="wantedName">Aygıtın adı; null ise yakalama kartına benzeyen ilk aygıt, o da yoksa ilk aygıt.</param>
    public CaptureDeviceStreamSession(string? wantedName) => this.wantedName = string.IsNullOrWhiteSpace(wantedName) ? null : wantedName;

    /// <summary>Renkli görüntü veren aygıtlar (yakalama kartları önce).</summary>
    public static async Task<IReadOnlyList<CaptureDevice>> ListAsync()
    {
        try
        {
            var groups = await MediaFrameSourceGroup.FindAllAsync();
            return groups
                .Where(g => g.SourceInfos.Any(IsColorVideo))
                .Select(g => new CaptureDevice(g.Id, g.DisplayName))
                .OrderByDescending(d => d.LooksLikeCaptureCard)
                .ToList();
        }
        catch
        {
            return [];
        }
    }

    /// <summary>Arayüz iş parçacığından da güvenle çağrılabilen eşzamanlı liste.</summary>
    public static IReadOnlyList<CaptureDevice> List() => Task.Run(ListAsync).GetAwaiter().GetResult();

    private static bool IsColorVideo(MediaFrameSourceInfo info) =>
        info.SourceKind == MediaFrameSourceKind.Color &&
        info.MediaStreamType is MediaStreamType.VideoRecord or MediaStreamType.VideoPreview;

    public void Start()
    {
        if (running) return;
        running = true;
        streaming = false;
        cts = new CancellationTokenSource();
        StateChanged?.Invoke(SessionState.Connecting);
        var token = cts.Token;
        _ = Task.Run(() => RunAsync(token));
    }

    /// <summary>Aygıt koptuğunda tamamlanır; tek döngü (RunAsync) bunu bekler. Eskiden kopma yeni bir döngü başlatıyordu,
    /// kart başka uygulamadayken döngüler çoğalıp saniyede onlarca kez açmayı deniyordu.</summary>
    private TaskCompletionSource<string>? lost;

    private async Task RunAsync(CancellationToken token)
    {
        while (running && !token.IsCancellationRequested)
        {
            var signal = lost = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
            try
            {
                await OpenAsync(signal);
                // Açıldı; kareler FrameArrived'dan gelir. Aygıt kopana ya da oturum kapanana kadar beklenir.
                var reason = await signal.Task.WaitAsync(token);
                await CloseAsync();
                streaming = false;
                StateChanged?.Invoke(SessionState.Disconnected(DisconnectKind.Network, reason));
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                return;
            }
            catch (Exception error)
            {
                await CloseAsync();
                streaming = false;
                SubtitleLog.Write($"yakalama kartı açılamadı: {error.Message}");
                StateChanged?.Invoke(SessionState.Disconnected(DisconnectKind.NotFound, error.Message));
            }
            try { await Task.Delay(3000, token); } catch (OperationCanceledException) { return; }
            if (running) StateChanged?.Invoke(SessionState.Connecting);
        }
    }

    private async Task OpenAsync(TaskCompletionSource<string> signal)
    {
        var groups = await MediaFrameSourceGroup.FindAllAsync();
        var usable = groups.Where(g => g.SourceInfos.Any(IsColorVideo)).ToList();
        SubtitleLog.Write("yakalama aygıtları: " + (usable.Count == 0 ? "yok" : string.Join(", ", usable.Select(g => $"\"{g.DisplayName}\""))) +
                          $" | istenen: {(wantedName is null ? "otomatik" : $"\"{wantedName}\"")}");
        var group = (wantedName is null ? null : usable.FirstOrDefault(g => string.Equals(g.DisplayName, wantedName, StringComparison.OrdinalIgnoreCase)))
                    ?? (wantedName is null ? usable.FirstOrDefault(g => new CaptureDevice(g.Id, g.DisplayName).LooksLikeCaptureCard) ?? usable.FirstOrDefault() : null)
                    ?? throw new InvalidOperationException(Localizer.Current["No capture card was found. Check the USB cable and close other apps using the card."]);

        // Önce tam denetimle (görüntü boyutu seçilebilsin); kartı başka uygulama tutuyorsa yalnız okuma izniyle.
        capture = new MediaCapture();
        var exclusive = true;
        try
        {
            await capture.InitializeAsync(Settings(group, MediaCaptureSharingMode.ExclusiveControl));
        }
        catch
        {
            capture.Dispose();
            capture = new MediaCapture();
            exclusive = false;
            await capture.InitializeAsync(Settings(group, MediaCaptureSharingMode.SharedReadOnly));
        }
        capture.Failed += OnFailed;

        var source = capture.FrameSources.Values.FirstOrDefault(s => s.Info.SourceKind == MediaFrameSourceKind.Color && s.Info.MediaStreamType == MediaStreamType.VideoRecord)
                     ?? capture.FrameSources.Values.First(s => IsColorVideo(s.Info));
        if (exclusive && Best(source.SupportedFormats) is { } format)
        {
            try { await source.SetFormatAsync(format); } catch { }
        }

        var current = source.CurrentFormat;
        SubtitleLog.Write($"yakalama kartı: \"{group.DisplayName}\" {(exclusive ? "tam denetim" : "paylaşımlı (kartı başka uygulama tutuyor)")}, " +
                          $"biçim {current?.Subtype} {current?.VideoFormat.Width}x{current?.VideoFormat.Height}, " +
                          $"desteklenen: {string.Join(" ", source.SupportedFormats.Select(f => $"{f.Subtype}/{f.VideoFormat.Width}x{f.VideoFormat.Height}").Distinct().Take(8))}");

        await StartReaderAsync(source, MediaEncodingSubtypes.Bgra8);
        if (!await FramesWithinAsync(5000, signal))
        {
            SubtitleLog.Write($"yakalama kartı: BGRA dönüşümüyle 5 sn'de görüntü gelmedi (gelen olay {arrived}, boş {empty}); kartın kendi biçimiyle ({current?.Subtype}) yeniden deneniyor");
            await StopReaderAsync();
            await StartReaderAsync(source, current?.Subtype);
            if (!await FramesWithinAsync(5000, signal))
            {
                SubtitleLog.Write($"yakalama kartı: kendi biçimiyle de görüntü gelmedi (gelen olay {arrived}, boş {empty})" + (exclusive ? "" : " - kartı başka uygulama tutuyor"));
                throw new InvalidOperationException(NoPictureMessage(null));
            }
        }
        SubtitleLog.Write($"yakalama kartı açıldı: \"{group.DisplayName}\" görüntü geliyor");
    }

    private int arrived, empty, delivered;

    /// <summary>Kart görüntü vermediğinde kullanıcıya gösterilecek neden: kartı tutan uygulama adıyla; yoksa Windows'un hatası ya da genel öneri.</summary>
    private static string NoPictureMessage(string? windowsError)
    {
        var holders = CardApps();
        SubtitleLog.Write("yakalama kartı görüntü vermiyor" + (holders.Length > 0 ? $", açık uygulamalar: {string.Join(", ", holders)}" : "") +
                          (windowsError is null ? "" : $", Windows: {windowsError}"));
        if (holders.Contains("OBS"))
            return Localizer.Current["The capture card is being used by OBS. Turn on OBS's WebSocket server (Tools › WebSocket Server Settings › Enable WebSocket server) and the picture is taken from OBS; OBS can stay open."];
        if (holders.Length > 0)
            return Localizer.Current.Format("The capture card is being used by %@. Close it and the picture will appear here within a few seconds.", string.Join(", ", holders));
        return windowsError ?? Localizer.Current["The capture card opened but sends no picture. Close the card's own app and OBS, check that the console is on, and turn off HDCP on the PS5."];
    }

    /// <summary>Kartı tutabilen açık uygulamalar (OBS, Streamlabs, Elgato'nun uygulamaları, XSplit).</summary>
    private static string[] CardApps()
    {
        (string Process, string Name)[] known =
        [
            ("obs64", "OBS"), ("obs32", "OBS"), ("obs", "OBS"), ("Streamlabs OBS", "Streamlabs"), ("Streamlabs Desktop", "Streamlabs"),
            ("4KCaptureUtility", "Elgato 4K Capture Utility"), ("GameCapture", "Elgato Game Capture"), ("Elgato Studio", "Elgato Studio"),
            ("Camera Hub", "Elgato Camera Hub"), ("XSplit.Core", "XSplit"), ("RECentral", "AVerMedia RECentral"), ("StreamEngine", "AVerMedia Streaming Center"),
        ];
        try
        {
            return known.Where(k => System.Diagnostics.Process.GetProcessesByName(k.Process).Length > 0).Select(k => k.Name).Distinct().ToArray();
        }
        catch
        {
            return [];
        }
    }

    private async Task StartReaderAsync(MediaFrameSource source, string? subtype)
    {
        arrived = empty = delivered = 0;
        reader = subtype is null ? await capture!.CreateFrameReaderAsync(source) : await capture!.CreateFrameReaderAsync(source, subtype);
        reader.AcquisitionMode = MediaFrameReaderAcquisitionMode.Realtime;
        reader.FrameArrived += OnFrameArrived;
        var status = await reader.StartAsync();
        if (status != MediaFrameReaderStartStatus.Success)
            throw new InvalidOperationException(Localizer.Current.Format("The capture card did not start (%@). Close other apps using the card (OBS, the card's own app) and try again.", status.ToString()));
    }

    private async Task StopReaderAsync()
    {
        if (reader is not { } old) return;
        reader = null;
        old.FrameArrived -= OnFrameArrived;
        try { await old.StopAsync(); } catch { }
        old.Dispose();
    }

    /// <summary>Belirtilen süre içinde kare geldi mi; aygıt bu arada koparsa hemen hayır.</summary>
    private async Task<bool> FramesWithinAsync(int milliseconds, TaskCompletionSource<string> signal)
    {
        for (var waited = 0; waited < milliseconds && running && !signal.Task.IsCompleted; waited += 100)
        {
            if (delivered > 0) return true;
            await Task.Delay(100);
        }
        if (signal.Task.IsCompleted && delivered == 0) throw new InvalidOperationException(NoPictureMessage(signal.Task.Result));
        return delivered > 0;
    }

    private static MediaCaptureInitializationSettings Settings(MediaFrameSourceGroup group, MediaCaptureSharingMode mode) => new()
    {
        SourceGroup = group,
        SharingMode = mode,
        MemoryPreference = MediaCaptureMemoryPreference.Cpu,
        StreamingCaptureMode = StreamingCaptureMode.Video,
    };

    /// <summary>1080p yeterli (altyazı okumak için); 4K kartlarda işlemciyi yormamak için en çok 1920 genişlik, en yüksek kare hızı.</summary>
    private static MediaFrameFormat? Best(IReadOnlyList<MediaFrameFormat> formats) => formats
        .Where(f => f.VideoFormat.Width <= 1920 && f.VideoFormat.Height <= 1080)
        .OrderByDescending(f => f.VideoFormat.Width * f.VideoFormat.Height)
        .ThenByDescending(f => f.FrameRate.Denominator == 0 ? 0 : (double)f.FrameRate.Numerator / f.FrameRate.Denominator)
        .FirstOrDefault();

    private void OnFrameArrived(MediaFrameReader sender, MediaFrameArrivedEventArgs args)
    {
        try
        {
            // Motor saniyede en çok 15 kare ister
            arrived++;
            var now = Environment.TickCount64;
            if (now - lastFrameAt < 66) return;
            using var reference = sender.TryAcquireLatestFrame();
            var video = reference?.VideoMediaFrame;
            // Bazı kartlar kareyi ekran kartı belleğinde verir: işlemci belleğine kopyalanır
            var bitmap = video?.SoftwareBitmap
                         ?? (video?.Direct3DSurface is { } surface ? SoftwareBitmap.CreateCopyFromSurfaceAsync(surface).AsTask().GetAwaiter().GetResult() : null);
            if (bitmap is null) { empty++; return; }
            lastFrameAt = now;

            using var bgra = bitmap.BitmapPixelFormat == BitmapPixelFormat.Bgra8
                ? SoftwareBitmap.Copy(bitmap)
                : SoftwareBitmap.Convert(bitmap, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore);
            var width = bgra.PixelWidth;
            var height = bgra.PixelHeight;
            var pixels = new byte[width * height * 4];
            bgra.CopyToBuffer(pixels.AsBuffer());
            var frame = new Frame(pixels, width, height, width * 4);

            delivered++;
            if (!streaming)
            {
                streaming = true;
                StateChanged?.Invoke(SessionState.Streaming);
            }
            WatchForBlack(frame, now);
            FrameReceived?.Invoke(frame);
        }
        catch { }
    }

    /// <summary>Görüntü 5 saniye boyunca simsiyahsa büyük olasılıkla PS5'te HDCP açıktır; günlüğe bir kez yazılır.</summary>
    private void WatchForBlack(Frame frame, long now)
    {
        if (darkReported) return;
        long sum = 0;
        var samples = 0;
        for (var y = frame.Height / 8; y < frame.Height; y += Math.Max(1, frame.Height / 8))
            for (var x = frame.Width / 8; x < frame.Width; x += Math.Max(1, frame.Width / 8))
            {
                var i = y * frame.Stride + x * 4;
                sum += frame.Pixels[i] + frame.Pixels[i + 1] + frame.Pixels[i + 2];
                samples++;
            }
        var dark = samples > 0 && sum / (samples * 3) < 4;
        if (!dark) { darkSince = 0; return; }
        if (darkSince == 0) { darkSince = now; return; }
        if (now - darkSince < 5000) return;
        darkReported = true;
        SubtitleLog.Write("yakalama kartı: görüntü 5 saniyedir siyah. PS5'te Ayarlar › Sistem › HDMI › HDCP'yi Etkinleştir kapalı olmalı; konsol açık ve kart bağlı mı?");
    }

    private void OnFailed(MediaCapture sender, MediaCaptureFailedEventArgs args)
    {
        SubtitleLog.Write($"yakalama kartı koptu: {args.Message}");
        // Yeni döngü başlatılmaz: çalışan döngüye haber verilir, o kapatıp 3 saniye sonra yeniden dener
        lost?.TrySetResult(args.Message);
    }

    private async Task CloseAsync()
    {
        var oldCapture = capture;
        try { await StopReaderAsync(); } catch { }
        capture = null;
        try
        {
            if (oldCapture is not null)
            {
                oldCapture.Failed -= OnFailed;
                oldCapture.Dispose();
            }
        }
        catch { }
    }

    public void Stop()
    {
        if (!running) return;
        running = false;
        cts?.Cancel();
        _ = Task.Run(CloseAsync);
        StateChanged?.Invoke(SessionState.Disconnected(DisconnectKind.Stopped));
    }

    public void ReconnectNow()
    {
        if (!running) return;
        Stop();
        Start();
    }

    public void Dispose()
    {
        Stop();
        cts?.Dispose();
    }
}
