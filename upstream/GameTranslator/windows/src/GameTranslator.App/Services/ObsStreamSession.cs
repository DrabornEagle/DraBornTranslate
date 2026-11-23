// SPDX-License-Identifier: AGPL-3.0-only

using System.IO;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using GameTranslator.Core;
using GameTranslator.RemotePlay;

namespace GameTranslator.App.Services;

/// <summary>
/// OBS'in WebSocket sunucusuna (OBS 28 ve sonrasında hazır gelir, protokol 5) bağlanan küçük istemci. Yakalama kartı
/// OBS'teyken kartı OBS'ten almadan görüntüsünü istemek için kullanılır: yayın etkilenmez.
/// Şifre ve port aynı bilgisayardaki OBS ayar dosyasından okunur (%APPDATA%\obs-studio\plugin_config\obs-websocket\config.json).
/// </summary>
public sealed class ObsClient : IAsyncDisposable
{
    private readonly ClientWebSocket socket = new();
    private int nextId;

    /// <summary>OBS'in kendi ayar dosyası: sunucu açık mı, port, şifre.</summary>
    public sealed record Config(bool Enabled, int Port, string? Password);

    public static Config? ReadConfig()
    {
        try
        {
            var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "obs-studio", "plugin_config", "obs-websocket", "config.json");
            if (!File.Exists(path)) return null;
            var json = JsonNode.Parse(File.ReadAllText(path));
            var auth = json?["auth_required"]?.GetValue<bool>() ?? true;
            return new Config(json?["server_enabled"]?.GetValue<bool>() ?? false, json?["server_port"]?.GetValue<int>() ?? 4455,
                auth ? json?["server_password"]?.GetValue<string>() : null);
        }
        catch
        {
            return null;
        }
    }

    public static bool IsObsRunning()
    {
        try { return System.Diagnostics.Process.GetProcessesByName("obs64").Length > 0 || System.Diagnostics.Process.GetProcessesByName("obs32").Length > 0; }
        catch { return false; }
    }

    /// <summary>Bağlanır ve kimlik doğrular. OBS kapalıysa, sunucusu kapalıysa ya da şifre tutmazsa açıklamalı hata verir.</summary>
    public static async Task<ObsClient> ConnectAsync(CancellationToken token)
    {
        var config = ReadConfig();
        var client = new ObsClient();
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
            timeout.CancelAfter(TimeSpan.FromSeconds(4));
            await client.socket.ConnectAsync(new Uri($"ws://127.0.0.1:{config?.Port ?? 4455}"), timeout.Token);

            // Hello (op 0): kimlik doğrulama isteniyorsa challenge + salt gelir
            var hello = await client.ReceiveAsync(timeout.Token);
            var identify = new JsonObject { ["rpcVersion"] = 1, ["eventSubscriptions"] = 0 };
            if (hello["d"]?["authentication"] is JsonObject auth)
            {
                var password = config?.Password ?? "";
                var secret = Convert.ToBase64String(SHA256.HashData(Encoding.UTF8.GetBytes(password + auth["salt"]!.GetValue<string>())));
                identify["authentication"] = Convert.ToBase64String(SHA256.HashData(Encoding.UTF8.GetBytes(secret + auth["challenge"]!.GetValue<string>())));
            }
            await client.SendAsync(new JsonObject { ["op"] = 1, ["d"] = identify }, timeout.Token);
            var identified = await client.ReceiveAsync(timeout.Token);
            if (identified["op"]?.GetValue<int>() != 2) throw new InvalidOperationException("identify");
            return client;
        }
        catch (Exception error)
        {
            await client.DisposeAsync();
            var message = !IsObsRunning() ? Localizer.Current["OBS is not running."]
                : config is { Enabled: false } || error is WebSocketException or OperationCanceledException && client.socket.State != WebSocketState.Open
                    ? Localizer.Current["OBS is running but its WebSocket server is off. In OBS: Tools › WebSocket Server Settings › Enable WebSocket server."]
                : client.socket.CloseStatus == (WebSocketCloseStatus)4009
                    ? Localizer.Current["OBS did not accept the password. Restart OBS and try again."]
                    : Localizer.Current.Format("Could not connect to OBS: %@", error.Message);
            throw new InvalidOperationException(message, error);
        }
    }

    /// <summary>Bir istek gönderip yanıtının responseData'sını döndürür (op 6 → op 7).</summary>
    public async Task<JsonNode?> RequestAsync(string type, JsonObject? data, CancellationToken token)
    {
        var id = Interlocked.Increment(ref nextId).ToString();
        var request = new JsonObject { ["requestType"] = type, ["requestId"] = id };
        if (data is not null) request["requestData"] = data;
        await SendAsync(new JsonObject { ["op"] = 6, ["d"] = request }, token);
        while (true)
        {
            var message = await ReceiveAsync(token);
            if (message["op"]?.GetValue<int>() != 7 || message["d"]?["requestId"]?.GetValue<string>() != id) continue;
            var status = message["d"]?["requestStatus"];
            if (status?["result"]?.GetValue<bool>() != true)
                throw new InvalidOperationException($"OBS {type}: {status?["comment"]?.GetValue<string>() ?? status?["code"]?.ToString()}");
            return message["d"]?["responseData"];
        }
    }

    /// <summary>OBS'teki video girişleri (yakalama kartı, kamera, pencere / oyun yakalama ...).</summary>
    public async Task<IReadOnlyList<(string Name, string Kind)>> VideoInputsAsync(CancellationToken token)
    {
        var data = await RequestAsync("GetInputList", null, token);
        var list = new List<(string, string)>();
        foreach (var input in data?["inputs"]?.AsArray() ?? [])
        {
            var name = input?["inputName"]?.GetValue<string>();
            var kind = input?["inputKind"]?.GetValue<string>() ?? "";
            if (name is null || IsAudio(kind)) continue;
            list.Add((name, kind));
        }
        // Yakalama kartları önce: video yakalama aygıtı ve adı karta benzeyenler
        return list.OrderByDescending(i => i.Item2 == "dshow_input").ThenByDescending(i => new CaptureDevice("", i.Item1).LooksLikeCaptureCard).ToList();
    }

    /// <summary>Bu aygıtı (ör. "Elgato HD60 X") kullanan OBS video yakalama kaynağının adı; yoksa null.</summary>
    public async Task<string?> InputForDeviceAsync(string deviceName, CancellationToken token)
    {
        foreach (var (name, kind) in await VideoInputsAsync(token))
        {
            if (kind != "dshow_input") continue;
            var data = await RequestAsync("GetInputSettings", new JsonObject { ["inputName"] = name }, token);
            // OBS aygıtı "Ad:\\?\usb#..." biçiminde saklar
            var device = data?["inputSettings"]?["video_device_id"]?.GetValue<string>();
            if (device is not null && device.StartsWith(deviceName, StringComparison.OrdinalIgnoreCase)) return name;
        }
        return null;
    }

    private static bool IsAudio(string kind) => kind.Contains("wasapi") || kind.Contains("audio") || kind.Contains("coreaudio") || kind.Contains("pulse");

    private async Task SendAsync(JsonObject message, CancellationToken token) =>
        await socket.SendAsync(Encoding.UTF8.GetBytes(message.ToJsonString()), WebSocketMessageType.Text, true, token);

    private readonly byte[] buffer = new byte[1 << 16];

    private async Task<JsonNode> ReceiveAsync(CancellationToken token)
    {
        using var stream = new MemoryStream();
        while (true)
        {
            var result = await socket.ReceiveAsync(buffer, token);
            if (result.MessageType == WebSocketMessageType.Close) throw new WebSocketException(Localizer.Current["OBS closed the connection."]);
            stream.Write(buffer, 0, result.Count);
            if (result.EndOfMessage) break;
        }
        return JsonNode.Parse(stream.ToArray()) ?? throw new InvalidDataException("empty");
    }

    public async ValueTask DisposeAsync()
    {
        try
        {
            if (socket.State == WebSocketState.Open)
                await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(1));
        }
        catch { }
        socket.Dispose();
    }
}

/// <summary>
/// Görüntüyü OBS'teki bir kaynaktan (genellikle yakalama kartı) alan IStreamSession: kart OBS'te kalır, yayın sürer.
/// OBS'ten saniyede ~8 kez kaynağın anlık görüntüsü istenir (GetSourceScreenshot, 1280 genişlik: ölçümde alma 32 ms, 1920'de 74 ms). Yayın sahnesi
/// değil kaynağın kendisi alındığı için bindirmeler altyazı okumayı bozmaz. OBS kapanırsa 3 saniyede bir yeniden denenir.
/// </summary>
public sealed class ObsStreamSession : IStreamSession
{
    public event Action<Frame>? FrameReceived;
    public event Action<SessionState>? StateChanged;
#pragma warning disable CS0067
    public event Action<RegisteredConsole>? ConsoleMoved;
#pragma warning restore CS0067

    /// <summary>Ayarlarda OBS kaynağını gösteren önek: "obs:Elgato" (boşsa otomatik seçilir).</summary>
    public const string Prefix = "obs:";

    private readonly string? wantedSource;
    private readonly string? deviceName;
    private CancellationTokenSource? cts;
    private bool running;

    /// <param name="wantedSource">OBS'teki kaynağın adı; null ise <paramref name="deviceName"/> aygıtını kullanan kaynak, o da yoksa ilk video kaynağı.</param>
    public ObsStreamSession(string? wantedSource, string? deviceName = null)
    {
        this.wantedSource = string.IsNullOrWhiteSpace(wantedSource) ? null : wantedSource;
        this.deviceName = deviceName;
    }

    /// <summary>
    /// Aygıt OBS'teki bir kaynakta açık mı (OBS çalışıyor, WebSocket açık ve bir video yakalama kaynağı bu aygıtı kullanıyor).
    /// Öyleyse kart OBS'tedir, doğrudan açılamaz: görüntü OBS'ten alınır. Aygıt adı null ise OBS'te herhangi bir kart kaynağı yeter.
    /// </summary>
    public static bool HoldsDevice(string? deviceName)
    {
        if (!ObsClient.IsObsRunning()) return false;
        try
        {
            return Task.Run(async () =>
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
                await using var client = await ObsClient.ConnectAsync(timeout.Token);
                return deviceName is null
                    ? (await client.VideoInputsAsync(timeout.Token)).Any(i => i.Kind == "dshow_input")
                    : await client.InputForDeviceAsync(deviceName, timeout.Token) is not null;
            }).GetAwaiter().GetResult();
        }
        catch (Exception error)
        {
            SubtitleLog.Write($"OBS: kart OBS'te mi bakılamadı: {error.Message}");
            return false;
        }
    }

    /// <summary>Arayüz listesi için: OBS açık ve WebSocket açıksa video kaynakları; değilse nedeniyle boş liste.</summary>
    public static (IReadOnlyList<string> Sources, string? Problem) ListSources()
    {
        if (!ObsClient.IsObsRunning()) return ([], null);
        try
        {
            return Task.Run(async () =>
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
                await using var client = await ObsClient.ConnectAsync(timeout.Token);
                var inputs = await client.VideoInputsAsync(timeout.Token);
                return ((IReadOnlyList<string>)inputs.Select(i => i.Name).ToList(), (string?)null);
            }).GetAwaiter().GetResult();
        }
        catch (Exception error)
        {
            return ([], error.Message);
        }
    }

    public void Start()
    {
        if (running) return;
        running = true;
        cts = new CancellationTokenSource();
        StateChanged?.Invoke(SessionState.Connecting);
        var token = cts.Token;
        _ = Task.Run(() => RunAsync(token));
    }

    private async Task RunAsync(CancellationToken token)
    {
        while (running && !token.IsCancellationRequested)
        {
            try
            {
                await using var client = await ObsClient.ConnectAsync(token);
                var inputs = await client.VideoInputsAsync(token);
                var source = wantedSource is not null
                    ? inputs.FirstOrDefault(i => string.Equals(i.Name, wantedSource, StringComparison.OrdinalIgnoreCase)).Name
                      ?? throw new InvalidOperationException(Localizer.Current.Format("OBS has no source named \"%@\".", wantedSource))
                    : (deviceName is null ? null : await client.InputForDeviceAsync(deviceName, token))
                      ?? inputs.FirstOrDefault().Name ?? throw new InvalidOperationException(Localizer.Current["OBS has no video source. Add your capture card to OBS."]);
                SubtitleLog.Write($"OBS: bağlandı, kaynak \"{source}\" (OBS'teki video kaynakları: {string.Join(", ", inputs.Select(i => $"{i.Name} [{i.Kind}]"))})");
                await PullFramesAsync(client, source, token);
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                return;
            }
            catch (Exception error)
            {
                SubtitleLog.Write($"OBS: {error.Message}");
                StateChanged?.Invoke(SessionState.Disconnected(DisconnectKind.NotFound, error.Message));
            }
            try { await Task.Delay(3000, token); } catch (OperationCanceledException) { return; }
            if (running) StateChanged?.Invoke(SessionState.Connecting);
        }
    }

    private async Task PullFramesAsync(ObsClient client, string source, CancellationToken token)
    {
        var streaming = false;
        var darkReported = false;
        long darkSince = 0;
        while (running && !token.IsCancellationRequested)
        {
            var started = Environment.TickCount64;
            var data = await client.RequestAsync("GetSourceScreenshot", new JsonObject
            {
                ["sourceName"] = source,
                ["imageFormat"] = "jpg",
                ["imageWidth"] = 1280,
                ["imageCompressionQuality"] = 92,
            }, token);
            var frame = Decode(data?["imageData"]?.GetValue<string>());
            if (frame is not null)
            {
                if (!streaming)
                {
                    streaming = true;
                    SubtitleLog.Write($"OBS: görüntü geliyor ({frame.Width}x{frame.Height}, {Environment.TickCount64 - started} ms)");
                    StateChanged?.Invoke(SessionState.Streaming);
                }
                // Kaynak OBS'te görünmüyorsa ya da HDCP açıksa görüntü siyah gelir: günlüğe bir kez
                if (!darkReported && IsDark(frame))
                {
                    darkSince = darkSince == 0 ? started : darkSince;
                    if (started - darkSince > 5000)
                    {
                        darkReported = true;
                        SubtitleLog.Write($"OBS: \"{source}\" görüntüsü 5 saniyedir siyah. Kaynak OBS'te görünür mü (gözü açık mı), PS5'te HDCP kapalı mı?");
                    }
                }
                else darkSince = 0;
                FrameReceived?.Invoke(frame);
            }
            // Saniyede ~8 kare: altyazı okumak için yeter, OBS'i yormaz
            var wait = 120 - (int)(Environment.TickCount64 - started);
            if (wait > 0) await Task.Delay(wait, token);
        }
    }

    /// <summary>"data:image/jpg;base64,..." → BGRA kare.</summary>
    internal static Frame? Decode(string? dataUrl)
    {
        if (string.IsNullOrEmpty(dataUrl)) return null;
        var comma = dataUrl.IndexOf(',');
        var bytes = Convert.FromBase64String(comma >= 0 ? dataUrl[(comma + 1)..] : dataUrl);
        using var stream = new MemoryStream(bytes);
        var decoder = BitmapDecoder.Create(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        BitmapSource image = decoder.Frames[0];
        if (image.Format != PixelFormats.Bgra32) image = new FormatConvertedBitmap(image, PixelFormats.Bgra32, null, 0);
        var stride = image.PixelWidth * 4;
        var pixels = new byte[stride * image.PixelHeight];
        image.CopyPixels(pixels, stride, 0);
        return new Frame(pixels, image.PixelWidth, image.PixelHeight, stride);
    }

    private static bool IsDark(Frame frame)
    {
        long sum = 0;
        var samples = 0;
        for (var y = frame.Height / 8; y < frame.Height; y += Math.Max(1, frame.Height / 8))
            for (var x = frame.Width / 8; x < frame.Width; x += Math.Max(1, frame.Width / 8))
            {
                var i = y * frame.Stride + x * 4;
                sum += frame.Pixels[i] + frame.Pixels[i + 1] + frame.Pixels[i + 2];
                samples++;
            }
        return samples > 0 && sum / (samples * 3) < 4;
    }

    public void Stop()
    {
        if (!running) return;
        running = false;
        cts?.Cancel();
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
