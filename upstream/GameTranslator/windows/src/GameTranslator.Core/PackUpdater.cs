// SPDX-License-Identifier: AGPL-3.0-only

using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace GameTranslator.Core;

/// <summary>Paket sunucusundaki (server/packs: <c>GET /v1/packs</c> = R2'deki index.json) bir paket.</summary>
public sealed record RemotePack(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("title")] string Title,
    [property: JsonPropertyName("version")] long Version,
    [property: JsonPropertyName("sha256")] string Sha256,
    [property: JsonPropertyName("size")] long? Size = null,
    [property: JsonPropertyName("entries")] int? Entries = null,
    [property: JsonPropertyName("lang")] string? Lang = null);

/// <summary>
/// Sunucudaki yeni ve güncellenen oyun paketleri (ücretsiz, hesapsız): Android'deki PackStore'un Windows karşılığı.
/// Liste alınır, değişen paket indirilir, SHA-256 ile doğrulanır ve paket klasörüne (<see cref="GamePackLibrary.UserDirectory"/>)
/// konur; uygulamayla gelen aynı kimlikli paketin yerine geçer. Haftada bir bakılır; sürümü değişmeyen paket yeniden inmez,
/// sunucudan kalkan paket silinir. Yarım ya da bozuk dosya hiç yüklenmez (önce geçici dosya, doğrulanınca yerine).
/// Klasöre kullanıcının kendi bıraktığı (sunucudan inmemiş ya da sonradan değiştirilmiş) dosyanın üstüne yazılmaz, o dosya
/// silinmez. Sunucu adresi yapılandırmadan gelir (<see cref="ConfiguredBaseUrl"/>); adres yoksa özellik kapalıdır.
/// Mac'teki GameTranslatorCore/PackUpdater.swift ile aynı kurallar.
/// </summary>
public sealed partial class PackUpdater : IDisposable
{
    public static readonly TimeSpan RefreshInterval = TimeSpan.FromDays(7);

    /// <summary>
    /// Paketlerin kullanıldığı tek dil (İngilizce → Türkçe). Sunucudaki başka dillerin paketleri (lang alanı farklı)
    /// indirilmez, sorulmaz ve elde olanları sildirmez; dili yazılmamış paket Türkçedir.
    /// </summary>
    public const string Language = "tr";

    internal static bool Usable(RemotePack pack) => Usable(pack, Language);

    /// <summary>Paket bu dilin mi (dili yazılmamış paket Türkçedir).</summary>
    internal static bool Usable(RemotePack pack, string language) =>
        string.Equals(string.IsNullOrEmpty(pack.Lang) ? Language : pack.Lang, language, StringComparison.OrdinalIgnoreCase);

    /// <summary>Bu güncelleyicinin dili: yalnız bu dilin paketleri sorulur ve iner (Türkçe, Arapça…).</summary>
    public string PackLanguage { get; init; } = Language;

    /// <summary>Bir dilin durum dosyası: Türkçe eski adında kalır (packs-remote.json), başka diller packs-remote-ar.json.</summary>
    public static string StateFileFor(string language) =>
        string.Equals(language, Language, StringComparison.OrdinalIgnoreCase) ? DefaultStateFile
            : Path.Combine(Path.GetDirectoryName(DefaultStateFile)!, $"packs-remote-{language.ToLowerInvariant()}.json");

    /// <summary>Uygulamayla gelen paketin SHA-256'sı (küçük harf onaltılık); sunucudakiyle aynıysa paket indirilmez.</summary>
    public Func<string, string?>? BundledSha256 { get; init; }
    internal const int MaxPackBytes = 64 * 1024 * 1024;

    [GeneratedRegex("^[a-z0-9][a-z0-9-]{0,63}$")]
    private static partial Regex PackId();

    [GeneratedRegex("^[0-9a-fA-F]{64}$")]
    private static partial Regex Hex64();

    /// <param name="Version">-1: son yenilemede inemedi (eski sürümü duruyor), sonraki açılışta yeniden denenir.</param>
    /// <param name="Legacy">Önceki paket sunucusundan inmiş: şimdiki sunucu aynı oyunun paketini sunana kadar kalır.</param>
    public sealed record Installed(
        [property: JsonPropertyName("id")] string Id,
        [property: JsonPropertyName("title")] string Title,
        [property: JsonPropertyName("version")] long Version,
        [property: JsonPropertyName("sha256")] string Sha256,
        [property: JsonPropertyName("legacy")] bool Legacy = false);

    /// <summary>Yenilemenin sonucu; <c>Skipped</c>: süresi gelmedi, <c>Failed</c>: liste alınamadı (elde olanlar kaldı).</summary>
    public sealed record Refresh(IReadOnlyList<string> Downloaded, IReadOnlyList<string> Removed, bool Skipped = false, bool Failed = false)
    {
        public bool Changed => Downloaded.Count > 0 || Removed.Count > 0;
    }

    private sealed class State
    {
        [JsonPropertyName("checked")] public double Checked { get; set; }
        [JsonPropertyName("packs")] public List<Installed> Packs { get; set; } = [];
        /// <summary>Son yenilemenin yapıldığı sunucu; eski kayıtlarda yok (o zamanki tek sunucu).</summary>
        [JsonPropertyName("source")] public string? Source { get; set; }
    }

    private sealed record Listing([property: JsonPropertyName("packs")] List<RemotePack>? Packs);

    private readonly Uri baseUrl;
    private readonly string directory;
    private readonly string stateFile;
    private readonly HttpClient http;
    private readonly Func<DateTimeOffset> clock;
    private readonly SemaphoreSlim running = new(1, 1);

    /// <param name="directory">Paketlerin konacağı klasör (uygulamada <see cref="GamePackLibrary.UserDirectory"/>).</param>
    /// <param name="stateFile">Durum dosyası; paket klasörünün dışında durur ki paket gibi okunmasın.</param>
    /// <param name="handler">HTTP katmanı; testlerde sahtesi verilir.</param>
    public PackUpdater(Uri baseUrl, string directory, string stateFile, HttpMessageHandler? handler = null, Func<DateTimeOffset>? clock = null)
    {
        this.baseUrl = new Uri(baseUrl.AbsoluteUri.TrimEnd('/') + "/");
        this.directory = directory;
        this.stateFile = stateFile;
        http = handler is null ? new HttpClient() : new HttpClient(handler);
        http.Timeout = TimeSpan.FromSeconds(60);
        this.clock = clock ?? (() => DateTimeOffset.UtcNow);
    }

    /// <summary>Uygulamanın durum dosyası (%LOCALAPPDATA%\GameTranslator\packs-remote.json).</summary>
    public static string DefaultStateFile { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GameTranslator", "packs-remote.json");

    private string Identity => baseUrl.AbsoluteUri;

    /// <summary>
    /// Elde paket var ama son yenileme başka bir sunucudandı (uygulama yeni paket sunucusuna geçti). Bu geçiş sormadan
    /// yapılır (<see cref="RefreshAsync"/>): aynı oyunların yeni sürümü.
    /// </summary>
    public bool ServerChanged => Load() is var state && state.Packs.Count > 0 && state.Source != Identity;

    /// <summary>
    /// Önceki paket sunucusundan inmiş, şimdiki sunucuda karşılığı olmayan paketler. Kütüphanede en düşük önceliktedir
    /// (<see cref="GamePackLibrary"/> lowPriority): aynı satır başka bir pakette de varsa onunki kullanılır.
    /// </summary>
    public static IReadOnlySet<string> LegacyIds(string stateFile)
    {
        try
        {
            var state = JsonSerializer.Deserialize<State>(File.ReadAllText(stateFile));
            return (state?.Packs ?? []).Where(p => p.Legacy).Select(p => p.Id).ToHashSet();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException) { return new HashSet<string>(); }
    }

    /// <summary>Paket adından oyunun adı: "Grand Theft Auto VI (ek satırlar)" ile "Grand Theft Auto VI" aynı oyundur.</summary>
    internal static string GameName(string title)
    {
        var name = title.Trim().ToLowerInvariant();
        foreach (var suffix in new[] { " (ek satırlar)", " (extra lines)" })
            if (name.EndsWith(suffix, StringComparison.Ordinal)) name = name[..^suffix.Length];
        return name;
    }

    /// <summary>
    /// Sunucu adresi: <c>GAMETRANSLATOR_PACKS_URL</c> ortam değişkeni ya da derlemedeki <c>GameTranslatorPacksUrl</c> bilgisi
    /// (App projesinde MSBuild özelliği <c>GameTranslatorPacksUrl</c>). Hiçbiri yoksa (bugünkü durum) null: paket indirme kapalı.
    /// </summary>
    public static Uri? ConfiguredBaseUrl(string? environment = null, Assembly? assembly = null)
    {
        var fromBuild = (assembly ?? Assembly.GetEntryAssembly())?.GetCustomAttributes<AssemblyMetadataAttribute>()
            .FirstOrDefault(a => a.Key == "GameTranslatorPacksUrl")?.Value;
        foreach (var candidate in new[] { environment ?? Environment.GetEnvironmentVariable("GAMETRANSLATOR_PACKS_URL"), fromBuild })
        {
            var value = candidate?.Trim();
            if (string.IsNullOrEmpty(value) || !Uri.TryCreate(value, UriKind.Absolute, out var url)) continue;
            if (url.Scheme is "https" or "http" && url.Host.Length > 0) return url;
        }
        return null;
    }

    public IReadOnlyList<Installed> InstalledPacks => Load().Packs;

    /// <summary>İndirilmeyi bekleyen paket; <c>IsNew</c>: bu bilgisayarda hiç yok (uygulamayla da gelmedi).</summary>
    public sealed record Pending(RemotePack Pack, bool IsNew);

    private enum Decision { Keep, Skip, Download }

    /// <summary>
    /// Paketin ne olacağı: elde aynısı var (Keep), indirilmez (Skip: uygulamayla gelenle aynı ya da kullanıcının kendi
    /// dosyası) ya da indirilir (Download).
    /// </summary>
    private Decision Decide(RemotePack pack, State state, out Installed? existing, out string? onDisk)
    {
        var wanted = pack.Sha256.ToLowerInvariant();
        existing = state.Packs.FirstOrDefault(p => p.Id == pack.Id);
        onDisk = Sha256OfFile(PackFile(pack.Id));
        if (existing is not null && existing.Version == pack.Version && existing.Sha256 == wanted && onDisk == wanted) return Decision.Keep;
        // Uygulamayla gelen paket sunucudakiyle aynıysa indirilmez (ilk açılışta bütün paketler boşuna iniyordu).
        if (onDisk is null && BundledSha256?.Invoke(pack.Id) == wanted) return Decision.Skip;
        // Bizim indirmediğimiz (ya da indirdikten sonra değiştirilmiş) dosyaya dokunulmaz.
        if (onDisk is not null && onDisk != existing?.Sha256 && onDisk != wanted) return Decision.Skip;
        return Decision.Download;
    }

    /// <summary>
    /// Yalnız bakar, hiçbir şey indirmez: sunucuda yeni ya da güncellenmiş paket var mı (açılışta kullanıcıya sorulur,
    /// onaylarsa <see cref="RefreshAsync"/> indirir). Liste alınamazsa null.
    /// </summary>
    public async Task<IReadOnlyList<Pending>?> CheckAsync(CancellationToken cancellationToken = default)
    {
        var remote = await ListAsync(cancellationToken).ConfigureAwait(false);
        if (remote is null) return null;
        var state = Load();
        var pending = new List<Pending>();
        foreach (var pack in remote)
        {
            if (!PackId().IsMatch(pack.Id) || !Hex64().IsMatch(pack.Sha256)) continue;
            if (Decide(pack, state, out _, out var onDisk) != Decision.Download) continue;
            pending.Add(new Pending(pack, IsNew: onDisk is null && BundledSha256?.Invoke(pack.Id) is null));
        }
        return pending;
    }

    /// <summary>
    /// Süresi geldiyse (ya da <paramref name="force"/>) sunucuya bakar ve değişen paketleri indirir. Liste alınamazsa elde
    /// olanlar kalır; inemeyen paket sonraki açılışta yeniden denenir.
    /// </summary>
    public async Task<Refresh> RefreshAsync(bool force = false, CancellationToken cancellationToken = default)
    {
        await running.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var state = Load();
            // Başka bir sunucudan gelindiyse (uygulama güncellendi, adres değişti) hemen bakılır; oradan inen paketler
            // önceki sunucunun paketleri sayılır.
            var switched = state.Source != Identity;
            if (switched) state.Packs = [.. state.Packs.Select(p => p with { Legacy = true })];
            var now = clock();
            var nowSeconds = now.ToUnixTimeMilliseconds() / 1000.0;
            if (!force && !switched && state.Checked > 0 && nowSeconds - state.Checked < RefreshInterval.TotalSeconds && state.Packs.All(p => p.Version != -1))
                return new Refresh([], [], Skipped: true);
            var remote = await ListAsync(cancellationToken).ConfigureAwait(false);
            // Boş liste sunucu tarafında bir sorundur: hiçbir paket silinmez, sonraki açılışta yeniden denenir.
            if (remote is null || remote.Count == 0) return new Refresh([], [], Failed: true);
            Directory.CreateDirectory(directory);

            var kept = new List<Installed>();
            var downloaded = new List<string>();
            foreach (var pack in remote)
            {
                if (!PackId().IsMatch(pack.Id) || !Hex64().IsMatch(pack.Sha256)) continue;
                var wanted = pack.Sha256.ToLowerInvariant();
                var file = PackFile(pack.Id);
                var decision = Decide(pack, state, out var existing, out var onDisk);
                if (decision == Decision.Keep) kept.Add(existing! with { Legacy = false });
                if (decision != Decision.Download) continue;
                var data = await GetAsync($"v1/packs/{pack.Id}", cancellationToken).ConfigureAwait(false);
                if (data is null || data.Length > MaxPackBytes || Sha256(data) != wanted)
                {
                    if (existing is not null && onDisk is not null) kept.Add(existing with { Version = -1 });
                    continue;
                }
                var temporary = Path.Combine(directory, $".{pack.Id}.download");
                try
                {
                    await File.WriteAllBytesAsync(temporary, data, cancellationToken).ConfigureAwait(false);
                    File.Move(temporary, file, overwrite: true);
                }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException)
                {
                    try { File.Delete(temporary); } catch (IOException) { }
                    continue;
                }
                kept.Add(new Installed(pack.Id, pack.Title, pack.Version, wanted));
                downloaded.Add(pack.Id);
            }

            // Sunucudan kalkan paket silinir (yalnız bizim indirdiğimiz ve değişmemişse). Önceki sunucudan inen paket ise
            // şimdiki sunucu aynı oyunun bir paketini sunuyorsa (ek satır paketleri dahil) silinir, sunmuyorsa kalır.
            var remoteIds = remote.Select(p => p.Id).ToHashSet();
            var servedGames = remote.Select(p => GameName(p.Title)).ToHashSet();
            var removed = new List<string>();
            foreach (var old in state.Packs.Where(p => !remoteIds.Contains(p.Id)))
            {
                var file = PackFile(old.Id);
                if (old.Legacy && !servedGames.Contains(GameName(old.Title)))
                {
                    if (File.Exists(file)) kept.Add(old);
                    continue;
                }
                if (Sha256OfFile(file) == old.Sha256) TryDelete(file);
                removed.Add(old.Id);
            }
            Save(new State { Checked = nowSeconds, Packs = kept, Source = Identity });
            return new Refresh(downloaded, removed);
        }
        finally
        {
            running.Release();
        }
    }

    /// <summary>Ayarlardan "indirilen paketleri sil": yalnız sunucudan inmiş ve değişmemiş dosyalar.</summary>
    public void Clear()
    {
        foreach (var pack in Load().Packs)
        {
            var file = PackFile(pack.Id);
            if (Sha256OfFile(file) == pack.Sha256) TryDelete(file);
        }
        TryDelete(stateFile);
    }

    private async Task<List<RemotePack>?> ListAsync(CancellationToken cancellationToken)
    {
        var data = await GetAsync("v1/packs", cancellationToken).ConfigureAwait(false);
        if (data is null) return null;
        try { return [.. (JsonSerializer.Deserialize<Listing>(data)?.Packs ?? []).Where(p => Usable(p, PackLanguage))]; }
        catch (JsonException) { return null; }
    }

    private async Task<byte[]?> GetAsync(string path, CancellationToken cancellationToken)
    {
        try
        {
            using var response = await http.GetAsync(new Uri(baseUrl, path), cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode) return null;
            return await response.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException)
        {
            return null;
        }
    }

    private string PackFile(string id) => Path.Combine(directory, $"{id}.json.gz");

    private State Load()
    {
        try { return JsonSerializer.Deserialize<State>(File.ReadAllText(stateFile)) ?? new State(); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException) { return new State(); }
    }

    private void Save(State state)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(stateFile)!);
            var temporary = stateFile + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(state));
            File.Move(temporary, stateFile, overwrite: true);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private static void TryDelete(string file)
    {
        try { File.Delete(file); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    internal static bool IsValidId(string id) => PackId().IsMatch(id);

    internal static string Sha256(byte[] data) => Convert.ToHexStringLower(SHA256.HashData(data));

    private static string? Sha256OfFile(string file)
    {
        try { return File.Exists(file) ? Sha256(File.ReadAllBytes(file)) : null; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return null; }
    }

    public void Dispose()
    {
        http.Dispose();
        running.Dispose();
    }
}
