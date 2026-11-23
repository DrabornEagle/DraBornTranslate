// SPDX-License-Identifier: AGPL-3.0-only
// CouchSubs'tan alındı (Xbox desteği, Beta; cdbced1, yazan: Berkovant). Copyright (C) 2026 Sinan Yorulmaz ve katkıda bulunanlar.

using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using GameTranslator.Core;
using GameTranslator.RemotePlay;
using GameTranslator.Shell;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;

namespace GameTranslator.App.Services;

/// <summary>
/// Xbox'ın WebRTC görüntüsünü ekran dışındaki bir WebView2'de alır ve çözülmüş kareleri olağan okuma hattına verir.
/// El sıkışmayı <see cref="XboxBridge"/> yürütür; görüntü yalnız Xbox ile bu pencere arasında akar. Bağlantı koparsa
/// 5 saniyede bir yeniden denenir.
/// </summary>
internal sealed class XboxStreamSession(XboxBridge bridge, XboxConsole console, string token, Action<string> tokenUpdated) : IStreamSession
{
    private const string PlayerHost = "gametranslator-xbox.local";

    // Oynatıcı sayfası yalnız WebRTC el sıkışma komutlarını çağırabilir; giriş ve jeton dönen komutlar ona kapalı.
    private static readonly HashSet<string> PlayerCommands = ["sdp", "chatSdp", "ice", "keepalive"];
    private readonly CancellationTokenSource cancel = new();
    private string currentToken = token;
    private Window? window;
    private WebView2? browser;
    private TaskCompletionSource<bool>? browserEnded;
    private bool started;
    private int frameBusy;

    public event Action<Frame>? FrameReceived;
    public event Action<SessionState>? StateChanged;
    public event Action<RegisteredConsole>? ConsoleMoved { add { } remove { } }

    public void Start()
    {
        if (started) return;
        started = true;
        // Pencere ve WebView2 arayüz iş parçacığında yaşar
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is null || dispatcher.CheckAccess()) _ = RunAsync();
        else dispatcher.BeginInvoke(() => { _ = RunAsync(); });
    }

    private async Task RunAsync()
    {
        while (!cancel.IsCancellationRequested)
        {
            try
            {
                StateChanged?.Invoke(SessionState.Connecting);
                await bridge.RestoreAsync(currentToken);
                var startedSession = await bridge.CallAsync("start", new { consoleId = console.Id });
                RememberToken(startedSession);
                for (var attempt = 0; attempt < 90 && !cancel.IsCancellationRequested; attempt++)
                {
                    var status = await bridge.CallAsync("status");
                    RememberToken(status);
                    var state = status.TryGetProperty("state", out var value) ? value.GetString() : null;
                    if (state == "Provisioned") break;
                    if (state == "Failed")
                    {
                        var reason = status.TryGetProperty("errorDetails", out var details) ? details.ToString() : Localizer.Current["The Xbox stream could not be started"];
                        throw new InvalidOperationException(reason);
                    }
                    if (attempt == 89) throw new TimeoutException(Localizer.Current["Timed out waiting for the Xbox stream to get ready."]);
                    await Task.Delay(1000, cancel.Token);
                }
                if (cancel.IsCancellationRequested) break;
                await OpenPlayerAsync();
                if (browserEnded is not null) await browserEnded.Task.WaitAsync(cancel.Token);
                if (!cancel.IsCancellationRequested) throw new IOException(Localizer.Current["The Xbox video connection closed."]);
            }
            catch (OperationCanceledException) when (cancel.IsCancellationRequested) { break; }
            catch (Exception error)
            {
                SubtitleLog.Write("Xbox: " + error.Message);
                StateChanged?.Invoke(SessionState.Disconnected(DisconnectKind.Other, error.Message));
            }
            finally
            {
                ClosePlayer();
                try { await bridge.CallAsync("stop"); } catch { /* Kopmuş bağlantının durdurulacak oturumu yoktur. */ }
            }
            if (!cancel.IsCancellationRequested)
            {
                try { await Task.Delay(5000, cancel.Token); }
                catch (OperationCanceledException) { break; }
            }
        }
    }

    private async Task OpenPlayerAsync()
    {
        var folder = XboxBridge.Folder;
        if (!File.Exists(Path.Combine(folder, "player.html"))) throw new FileNotFoundException(Localizer.Current["The Xbox player is missing from this installation."]);
        browser = new WebView2();
        window = new Window
        {
            Content = browser, Width = 1280, Height = 720, Left = -10000, Top = -10000,
            WindowStartupLocation = WindowStartupLocation.Manual, WindowStyle = WindowStyle.None,
            ShowInTaskbar = false, ShowActivated = false, ResizeMode = ResizeMode.NoResize,
            Title = "GameTranslator Xbox reader",
        };
        browserEnded = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        window.Closed += (_, _) => browserEnded?.TrySetResult(true);
        window.Show();
        var profile = Path.Combine(AppPaths.Data, "xbox-webview");
        var options = new CoreWebView2EnvironmentOptions("--disable-background-timer-throttling --disable-renderer-backgrounding --disable-backgrounding-occluded-windows");
        var environment = await CoreWebView2Environment.CreateAsync(null, profile, options);
        await browser.EnsureCoreWebView2Async(environment);
        browser.CoreWebView2.Settings.IsStatusBarEnabled = false;
        browser.CoreWebView2.Settings.AreDevToolsEnabled = false;
        browser.CoreWebView2.IsMuted = true;   // ses TV'den gelir
        browser.CoreWebView2.SetVirtualHostNameToFolderMapping(PlayerHost, folder, CoreWebView2HostResourceAccessKind.DenyCors);
        browser.CoreWebView2.WebMessageReceived += OnMessage;
        browser.CoreWebView2.NavigationCompleted += (_, result) =>
        {
            try
            {
                if (result.IsSuccess) browser?.CoreWebView2?.PostWebMessageAsJson("{\"kind\":\"start\"}");
                else browserEnded?.TrySetResult(true);
            }
            catch { browserEnded?.TrySetResult(true); }
        };
        browser.CoreWebView2.Navigate($"https://{PlayerHost}/player.html");
    }

    private async void OnMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs eventArgs)
    {
        try
        {
            using var document = JsonDocument.Parse(eventArgs.WebMessageAsJson);
            var message = document.RootElement;
            switch (message.GetProperty("kind").GetString())
            {
                case "frame":
                    if (Interlocked.Exchange(ref frameBusy, 1) != 0) return;
                    try
                    {
                        var data = message.GetProperty("image").GetString()!;
                        var comma = data.IndexOf(',');
                        if (comma >= 0) FrameReceived?.Invoke(DecodeJpeg(Convert.FromBase64String(data[(comma + 1)..])));
                    }
                    finally { Volatile.Write(ref frameBusy, 0); }
                    break;
                case "state":
                    var state = message.GetProperty("state").GetString();
                    if (state == "connected") StateChanged?.Invoke(SessionState.Streaming);
                    else if (state is "closed" or "failed" or "error")
                    {
                        var detail = message.TryGetProperty("detail", out var value) ? value.GetString() : Localizer.Current["The Xbox video connection dropped"];
                        StateChanged?.Invoke(SessionState.Disconnected(DisconnectKind.Other, detail));
                        browserEnded?.TrySetResult(true);
                    }
                    break;
                case "rpc":
                    var id = message.GetProperty("id").GetInt32();
                    var command = message.GetProperty("command").GetString()!;
                    if (!PlayerCommands.Contains(command))
                    {
                        browser?.CoreWebView2?.PostWebMessageAsJson(JsonSerializer.Serialize(new { kind = "reply", id, error = Localizer.Current["This command is not allowed."] }));
                        break;
                    }
                    var args = message.TryGetProperty("args", out var parameters) ? (object)parameters.Clone() : null;
                    try
                    {
                        var result = await bridge.CallAsync(command, args);
                        browser?.CoreWebView2?.PostWebMessageAsJson(JsonSerializer.Serialize(new { kind = "reply", id, value = result }));
                    }
                    catch (Exception error)
                    {
                        browser?.CoreWebView2?.PostWebMessageAsJson(JsonSerializer.Serialize(new { kind = "reply", id, error = error.Message }));
                    }
                    break;
            }
        }
        catch (Exception error)
        {
            StateChanged?.Invoke(SessionState.Disconnected(DisconnectKind.Other, error.Message));
            browserEnded?.TrySetResult(true);
        }
    }

    internal static Frame DecodeJpeg(byte[] bytes)
    {
        using var input = new MemoryStream(bytes);
        var source = new JpegBitmapDecoder(input, BitmapCreateOptions.None, BitmapCacheOption.OnLoad).Frames[0];
        var converted = new FormatConvertedBitmap(source, PixelFormats.Bgra32, null, 0);
        var stride = converted.PixelWidth * 4;
        var pixels = new byte[stride * converted.PixelHeight];
        converted.CopyPixels(pixels, stride, 0);
        return new Frame(pixels, converted.PixelWidth, converted.PixelHeight, stride);
    }

    private void RememberToken(JsonElement result)
    {
        if (!result.TryGetProperty("userToken", out var refreshed)) return;
        currentToken = refreshed.GetRawText();
        tokenUpdated(currentToken);
    }

    private void ClosePlayer()
    {
        browserEnded?.TrySetResult(true);
        browserEnded = null;
        var closing = window;
        var disposing = browser;
        window = null;
        browser = null;
        if (closing is null && disposing is null) return;
        void Close() { closing?.Close(); disposing?.Dispose(); }
        var dispatcher = closing?.Dispatcher ?? Application.Current?.Dispatcher;
        if (dispatcher is null || dispatcher.CheckAccess()) Close();
        else dispatcher.BeginInvoke(Close);
    }

    public void Stop()
    {
        if (cancel.IsCancellationRequested) return;
        cancel.Cancel();
        ClosePlayer();
        // Çalışan döngü de kapanırken "stop" gönderir; döngü hiç başlamadıysa ya da beklemedeyse burada gönderilir
        _ = Task.Run(async () => { try { await bridge.CallAsync("stop"); } catch { } });
        StateChanged?.Invoke(SessionState.Disconnected(DisconnectKind.Stopped));
    }

    public void ReconnectNow()
    {
        if (!cancel.IsCancellationRequested) browserEnded?.TrySetResult(true);
    }

    public void Dispose()
    {
        Stop();
    }
}
