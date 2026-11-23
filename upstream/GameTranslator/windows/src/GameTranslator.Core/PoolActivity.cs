// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.Json;

namespace GameTranslator.Core;

/// <summary>
/// Kaç kişinin GameTranslator ile oynadığı (ana ekranda): kendi havuzumuzun <c>/v1/active</c> ucu, son 15 dakikada
/// (<see cref="Now"/>) ve son 24 saatte (<see cref="Today"/>) satır gönderen kurulumları sayar. Yalnız havuzu açık
/// olanlar sayılır. İstekle hiçbir veri gitmez.
/// </summary>
public sealed record PoolActivity(int Now, int Today)
{
    public static readonly TimeSpan RefreshInterval = TimeSpan.FromMinutes(10);
    /// <summary>Bundan az kişi gösterilmez (ana ekranda o kısım hiç görünmez).</summary>
    public const int MinShown = 50;

    /// <summary>Havuzun satır adresinden (…/v1/lines) sayım adresi (…/v1/active).</summary>
    public static Uri Address(Uri linesEndpoint) => new(linesEndpoint, "active");

    /// <summary>Sunucuya ulaşılamazsa ya da yanıt bozuksa null.</summary>
    public static async Task<PoolActivity?> FetchAsync(Uri linesEndpoint, HttpMessageHandler? handler = null, CancellationToken cancel = default)
    {
        try
        {
            using var http = handler is null ? new HttpClient() : new HttpClient(handler, disposeHandler: false);
            http.Timeout = TimeSpan.FromSeconds(15);
            using var response = await http.GetAsync(Address(linesEndpoint), cancel).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode) return null;
            using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancel).ConfigureAwait(false));
            var root = doc.RootElement;
            if (!root.TryGetProperty("now", out var now) || !now.TryGetInt32(out var n) ||
                !root.TryGetProperty("today", out var today) || !today.TryGetInt32(out var t) || n < 0 || t < 0)
                return null;
            return new PoolActivity(n, Math.Max(n, t));
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException)
        {
            return null;
        }
    }
}
