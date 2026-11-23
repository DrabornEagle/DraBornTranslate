// SPDX-License-Identifier: AGPL-3.0-only

using System.IO;
using GameTranslator.Core;
using GameTranslator.Translation;

namespace GameTranslator.App.Services;

/// <summary>
/// Oyun paketlerinin yeni ve güncellenen sürümleri: uygulama her açıldığında (ve açık kaldıkça arada bir) paket sunucusuna
/// bakılır; yeni ya da güncellenmiş paket varsa ana ekranda sorulur, kullanıcı onaylarsa indirilir ve paketler yeniden
/// okunur (çeviri durmaz). Sormadan hiçbir şey indirilmez. Sunucu adresi yoksa kapalı.
/// </summary>
public static class PackUpdates
{
    private static readonly object Gate = new();
    private static IReadOnlyList<PackUpdater.Pending> pending = [];
    private static int busy;

    /// <summary>Bekleyen paketler ya da indirme durumu değişti (arka plan iş parçacığından gelebilir).</summary>
    public static event Action? Changed;

    public static IReadOnlyList<PackUpdater.Pending> Pending { get { lock (Gate) return pending; } }
    public static bool Installing { get; private set; }
    /// <summary>Son indirme başarısız olduysa true (şerit hata gösterir, düğme yeniden dener).</summary>
    public static bool Failed { get; private set; }
    /// <summary>Son indirmede inen paketlerin adları (şerit kısa bir süre "güncellendi" der).</summary>
    public static IReadOnlyList<string> JustInstalled { get; private set; } = [];

    private static PackUpdater? Create()
    {
        if (PackUpdater.ConfiguredBaseUrl() is not { } url) return null;
        // Paketler çeviri dilinindir: Türkçe uygulamayla gelenler + indirilenler, başka diller kendi klasöründe.
        var language = CommunityPatchManager.Shared.PackLanguage;
        if (language != PackUpdater.Language)
            return new PackUpdater(url, GamePackLibrary.UserDirectoryFor(language), PackUpdater.StateFileFor(language)) { PackLanguage = language };
        var bundled = GamePackLibrary.EmbeddedPacks().ToDictionary(pack => GamePackLibrary.BaseName(pack.Name),
            pack => Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(pack.Data)));
        return new PackUpdater(url, GamePackLibrary.UserDirectory, PackUpdater.DefaultStateFile)
        {
            BundledSha256 = id => bundled.GetValueOrDefault(id),
        };
    }

    /// <summary>Sunucuya bakar (indirmez); bekleyen paket varsa <see cref="Changed"/> ile ana ekran sorar.</summary>
    public static async Task CheckAsync()
    {
        if (Installing || Interlocked.Exchange(ref busy, 1) == 1) return;
        try
        {
            using var updater = Create();
            if (updater is null) return;
            // Uygulama yeni paket sunucusuna geçti: eldeki oyunların yeni sürümü sormadan iner (aynı oyunlar, başka sunucu);
            // sonraki yeni ve güncellenen paketler yine sorulur.
            // Türkçe dışındaki bir dilin paketleri ilk kez sormadan iner: kullanıcı o dili kendisi seçti.
            var firstOfLanguage = updater.PackLanguage != PackUpdater.Language && !File.Exists(PackUpdater.StateFileFor(updater.PackLanguage));
            if (updater.ServerChanged || firstOfLanguage)
            {
                var switched = await updater.RefreshAsync(force: true).ConfigureAwait(false);
                if (switched.Changed) CommunityPatchManager.Shared.Reload();
                SubtitleLog.Write(switched.Failed ? "yeni paket sunucusuna ulaşılamadı: sonraki açılışta yeniden"
                    : "yeni paket sunucusu: " + string.Join(", ", switched.Downloaded.Select(id => "+" + id).Concat(switched.Removed.Select(id => "-" + id))));
            }
            var found = await updater.CheckAsync().ConfigureAwait(false);
            if (found is null) return; // sunucuya ulaşılamadı: sonraki bakışta yeniden
            lock (Gate) pending = found = [.. found.Where(p => !Postponed.Contains(Mark(p)))];
            if (found.Count > 0)
                SubtitleLog.Write("paket sunucusu: " + string.Join(", ", found.Select(p => $"{p.Pack.Id} v{p.Pack.Version}{(p.IsNew ? " (yeni)" : "")}")));
            Changed?.Invoke();
        }
        catch (Exception error)
        {
            SubtitleLog.Write($"paket sunucusuna bakılamadı ({error.GetType().Name})");
        }
        finally
        {
            Interlocked.Exchange(ref busy, 0);
        }
    }

    /// <summary>Kullanıcı "Güncelle" dedi: bekleyen paketler indirilir, doğrulanır ve hemen kullanılmaya başlanır.</summary>
    public static async Task InstallAsync()
    {
        if (Installing) return;
        Installing = true;
        Failed = false;
        JustInstalled = [];
        Changed?.Invoke();
        try
        {
            using var updater = Create();
            if (updater is null) return;
            var titles = Pending.ToDictionary(p => p.Pack.Id, p => p.Pack.Title);
            var result = await updater.RefreshAsync(force: true).ConfigureAwait(false);
            if (result.Failed)
            {
                Failed = true;
                return;
            }
            if (result.Changed) CommunityPatchManager.Shared.Reload();
            JustInstalled = [.. result.Downloaded.Select(id => titles.GetValueOrDefault(id, id))];
            SubtitleLog.Write("paketler güncellendi: " + string.Join(", ", result.Downloaded.Concat(result.Removed)));
            // İnemeyen paket varsa yine sorulsun
            var left = await updater.CheckAsync().ConfigureAwait(false) ?? [];
            lock (Gate) pending = left;
            Failed = left.Count > 0;
        }
        catch (Exception error)
        {
            Failed = true;
            SubtitleLog.Write($"paketler güncellenemedi ({error.GetType().Name})");
        }
        finally
        {
            Installing = false;
            Changed?.Invoke();
        }
    }

    /// <summary>"Güncellendi" bandı kapandı.</summary>
    public static void ForgetInstalled() => JustInstalled = [];

    /// <summary>"Sonra" denen paket sürümleri: bu açılışta bir daha sorulmaz; daha yeni bir sürüm ya da başka paket gelirse sorulur.</summary>
    private static readonly HashSet<string> Postponed = [];
    private static string Mark(PackUpdater.Pending p) => p.Pack.Id + "@" + p.Pack.Sha256.ToLowerInvariant();

    /// <summary>"Sonra": bu açılışta bir daha sorulmaz (sonraki açılışta yine bakılır).</summary>
    public static void Postpone()
    {
        lock (Gate)
        {
            foreach (var p in pending) Postponed.Add(Mark(p));
            pending = [];
        }
        Failed = false;
        Changed?.Invoke();
    }
}
