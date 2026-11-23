// SPDX-License-Identifier: AGPL-3.0-only

using System.Net.Http;
using System.Reflection;
using System.Text.Json;
using GameTranslator.Shell;
using Velopack;
using Velopack.Sources;

namespace GameTranslator.App.Services;

/// <summary>
/// Uygulamanın kendini güncellemesi (Velopack). Güncelleme dosyaları sürümlerin yayımlandığı yerden düz HTTP ile alınır:
/// <c>{adres}/releases.win.json</c> (sürüm listesi) ve aynı klasördeki <c>GameTranslator-x.y.z-full.nupkg</c>.
/// Varsayılan adres GitLab paket deposundaki sabit güncelleme kanalıdır (App projesinde <c>GameTranslatorUpdateUrl</c>;
/// dosyaları scripts/publish-gitlab-release.py yükler). Yalnız kurulum dosyasıyla (Setup.exe) kurulan kopya kendini
/// günceller; zip'ten açılan kopyada yeni sürüm bildirilir ve indirme sayfası açılır.
/// </summary>
internal sealed class Updater : IUpdater
{
    /// <summary>Sürümlerin insan için sayfası (zip kullanıcısına açılır).</summary>
    private const string ReleasesPage = "https://gitlab.com/sefflex/GameTranslator/-/releases";

    private readonly UpdateManager? manager;
    private readonly Uri? feed;
    private UpdateInfo? pending;

    public Updater()
    {
        feed = ConfiguredFeed();
        try
        {
            manager = feed is null ? null : new UpdateManager(new SimpleWebSource(feed.AbsoluteUri.TrimEnd('/')));
        }
        catch
        {
            manager = null;
        }
    }

    /// <summary>
    /// Güncelleme adresi: <c>GAMETRANSLATOR_UPDATE_URL</c> ortam değişkeni (deneme için) ya da derlemedeki
    /// <c>GameTranslatorUpdateUrl</c> bilgisi (App projesinde MSBuild özelliği). İkisi de yoksa güncelleme kapalıdır.
    /// </summary>
    internal static Uri? ConfiguredFeed(string? environment = null, Assembly? assembly = null)
    {
        var fromBuild = (assembly ?? typeof(Updater).Assembly).GetCustomAttributes<AssemblyMetadataAttribute>()
            .FirstOrDefault(a => a.Key == "GameTranslatorUpdateUrl")?.Value;
        foreach (var candidate in new[] { environment ?? Environment.GetEnvironmentVariable("GAMETRANSLATOR_UPDATE_URL"), fromBuild })
        {
            var value = candidate?.Trim();
            if (string.IsNullOrEmpty(value) || !Uri.TryCreate(value, UriKind.Absolute, out var url)) continue;
            if (url.Scheme is "https" or "http" && url.Host.Length > 0) return url;
        }
        return null;
    }

    public bool CanUpdateItself => manager?.IsInstalled ?? false;
    public Uri DownloadPage { get; } = new(ReleasesPage);

    public async Task<string?> CheckAsync()
    {
        if (manager == null || !manager.IsInstalled) return await LatestListedAsync();
        pending = await manager.CheckForUpdatesAsync();
        return pending?.TargetFullRelease.Version.ToString();
    }

    public async Task InstallAsync(IProgress<double> progress)
    {
        if (manager == null || pending is null) return;
        await manager.DownloadUpdatesAsync(pending, percent => progress.Report(percent / 100.0));
        StopTranslationWorkers();
        manager.ApplyUpdatesAndRestart(pending);
    }

    /// <summary>
    /// Yerel çevirinin Python süreci (uygulama klasöründeki ct2\python.exe) güncelleme dosyaları değiştirmeden önce
    /// kapatılır; açık kalırsa klasör kilitli kalıp güncelleme yarım kalabilir.
    /// </summary>
    private static void StopTranslationWorkers()
    {
        var folder = System.IO.Path.Combine(AppContext.BaseDirectory, "ct2");
        foreach (var process in System.Diagnostics.Process.GetProcessesByName("python"))
        {
            try
            {
                if (process.MainModule?.FileName is { } file && file.StartsWith(folder, StringComparison.OrdinalIgnoreCase))
                {
                    process.Kill();
                    process.WaitForExit(2000);
                }
            }
            catch { }
            finally { process.Dispose(); }
        }
    }

    /// <summary>
    /// Komut satırı sınaması (<c>--update-check</c>, <c>--update-install</c>): sonucu döner; kurulumda uygulamayı yeniden
    /// başlatmadan çıkar (deneme klasöründeki kopya, kurulu uygulamayı kapatmasın diye).
    /// </summary>
    internal async Task<string> RunFromCommandLineAsync(bool install)
    {
        if (feed is null) return "no update address";
        if (manager is null || !manager.IsInstalled) return "not an installed copy; newer version listed: " + (await LatestListedAsync() ?? "none");
        var version = await CheckAsync();
        if (version is null) return $"up to date ({manager.CurrentVersion})";
        if (!install) return $"update available: {version} (current {manager.CurrentVersion})";
        await manager.DownloadUpdatesAsync(pending!);
        manager.ApplyUpdatesAndExit(pending!);
        return $"installing: {version}";
    }

    /// <summary>Zip kullanıcısı için: sürüm listesindeki en yeni sürüm, bu sürümden yeniyse.</summary>
    private async Task<string?> LatestListedAsync()
    {
        if (feed is null) return null;
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("GameTranslator");
            var latest = AppVersion.LatestInFeed(await http.GetStringAsync(feed.AbsoluteUri.TrimEnd('/') + "/releases.win.json"));
            return latest is not null && AppVersion.IsNewer(latest, AppVersion.Current(typeof(Updater).Assembly)) ? latest : null;
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException or JsonException
                                          or KeyNotFoundException or InvalidOperationException)
        {
            return null;
        }
    }
}
