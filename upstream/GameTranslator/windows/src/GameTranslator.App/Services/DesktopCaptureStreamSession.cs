// SPDX-License-Identifier: AGPL-3.0-only

using GameTranslator.Core;
using GameTranslator.RemotePlay;

namespace GameTranslator.App.Services;

/// <summary>
/// Masaüstünü, seçili monitörü veya OBS / Pencereyi yakalayarak kareleri altyazı motoruna aktaran IStreamSession.
/// Capture Card kullanıcıları ve OBS Fullscreen Projector kullananlar için PS5 Remote Play gerektirmez.
/// </summary>
public sealed class DesktopCaptureStreamSession : IStreamSession
{
    public event Action<Frame>? FrameReceived;
    public event Action<SessionState>? StateChanged;
#pragma warning disable CS0067
    public event Action<RegisteredConsole>? ConsoleMoved;
#pragma warning restore CS0067

    private readonly string? targetWindowTitle;
    private readonly int screenIndex;
    private CancellationTokenSource? cts;
    private Task? loopTask;
    private bool running;

    [System.Runtime.InteropServices.DllImport("winmm.dll", EntryPoint = "timeBeginPeriod")]
    private static extern uint TimeBeginPeriod(uint uMilliseconds);

    [System.Runtime.InteropServices.DllImport("winmm.dll", EntryPoint = "timeEndPeriod")]
    private static extern uint TimeEndPeriod(uint uMilliseconds);

    public DesktopCaptureStreamSession(string? targetWindowTitle = null, int screenIndex = 0)
    {
        this.targetWindowTitle = targetWindowTitle;
        this.screenIndex = screenIndex;
    }

    public void Start()
    {
        if (running) return;
        running = true;
        cts = new CancellationTokenSource();
        StateChanged?.Invoke(SessionState.Connecting);

        loopTask = Task.Run(() => CaptureLoopAsync(cts.Token));
    }

    private async Task CaptureLoopAsync(CancellationToken token)
    {
        bool emittedStreaming = false;
        TimeBeginPeriod(1);

        try
        {
            while (running && !token.IsCancellationRequested)
            {
                try
                {
                    Frame? frame = null;

                    // 1. Belirli bir fiziksel monitör seçilmişse o monitörün koordinatlarını yakala
                    if (!string.IsNullOrWhiteSpace(targetWindowTitle) && targetWindowTitle.StartsWith("monitor:", StringComparison.OrdinalIgnoreCase))
                    {
                        if (int.TryParse(targetWindowTitle["monitor:".Length..], out int mIndex))
                        {
                            var monitors = WindowCaptureService.GetDisplayMonitors();
                            var targetMon = monitors.FirstOrDefault(m => m.Index == mIndex) ?? monitors.FirstOrDefault(m => m.IsPrimary);
                            if (targetMon != null)
                            {
                                frame = WindowCaptureService.CaptureScreen(targetMon.Left, targetMon.Top, targetMon.Width, targetMon.Height);
                            }
                        }
                    }
                    // 1b. Xbox: akışı gösteren pencere kendiliğinden bulunur; kapanır ya da değişirse yeniden aranır
                    else if (string.Equals(targetWindowTitle, XboxWindow.Target, StringComparison.OrdinalIgnoreCase))
                    {
                        frame = CaptureXbox();
                    }
                    // 2. Özel bir pencere seçilmişse (SADECE o pencere yakalanır, asla tüm ekrana taşmaz)
                    else if (!string.IsNullOrWhiteSpace(targetWindowTitle))
                    {
                        string winTitle = targetWindowTitle.StartsWith("window:", StringComparison.OrdinalIgnoreCase)
                            ? targetWindowTitle["window:".Length..]
                            : targetWindowTitle;

                        var hWnd = WindowCaptureService.FindWindowBySubstring(winTitle);
                        if (hWnd != IntPtr.Zero)
                        {
                            frame = WindowCaptureService.Capture(hWnd);
                        }
                    }
                    // 3. Özel bir pencere veya monitör belirtilmemişse genel masaüstünü yakala
                    else
                    {
                        frame = WindowCaptureService.CaptureScreen();
                    }

                    if (frame != null)
                    {
                        if (!emittedStreaming)
                        {
                            emittedStreaming = true;
                            StateChanged?.Invoke(SessionState.Streaming);
                        }
                        FrameReceived?.Invoke(frame);
                    }
                }
                catch { }

                try
                {
                    await Task.Delay(16, token); // ~60 FPS akıcı ve gecikmesiz canlı yayın (OBS berraklığında)
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
        }
        finally
        {
            TimeEndPeriod(1);
        }
    }

    /// <summary>Yakalanan pencerenin başlığı değişti (yalnız Xbox kaynağında); pencere yoksa null. Oyun adı buradan okunur.</summary>
    public event Action<string?>? SourceTitleChanged;

    private IntPtr xboxWindow;
    private string? xboxTitle;
    private long xboxCheckedAt;
    private bool xboxReported;

    /// <summary>
    /// Xbox penceresini yakalar. Pencere 2 saniyede bir yeniden aranır (oyuncu Xbox uygulamasından tarayıcıya geçebilir,
    /// sekme başlığı oyuna göre değişir); pencere yoksa kare gelmez ve durum "bağlanıyor"da kalır.
    /// </summary>
    private Frame? CaptureXbox()
    {
        var now = Environment.TickCount64;
        if (!WindowCaptureService.IsCapturable(xboxWindow) || now - xboxCheckedAt > 2000)
        {
            xboxCheckedAt = now;
            var found = WindowCaptureService.FindXboxWindow(out var title);
            xboxWindow = found;
            var current = found == IntPtr.Zero ? null : title;
            if (!xboxReported || current != xboxTitle)
            {
                xboxReported = true;
                xboxTitle = current;
                try { SourceTitleChanged?.Invoke(current); } catch { }
            }
        }
        return xboxWindow == IntPtr.Zero ? null : WindowCaptureService.Capture(xboxWindow);
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
