// SPDX-License-Identifier: AGPL-3.0-only

using System.Collections.Concurrent;
using System.IO.Compression;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using GameTranslator.Core;

namespace GameTranslator.Translation;

/// <summary>
/// Topluluk veya kullanıcı tarafından sağlanan bir oyun yaması.
/// </summary>
public sealed class GamePatch
{
    public string Id { get; }
    public string GameName { get; }
    public string Source { get; }
    public int Count => Subtitles.Count;
    public Dictionary<string, string> Subtitles { get; } = new(StringComparer.OrdinalIgnoreCase);
    public Dictionary<string, string> StrippedSubtitles { get; } = new(StringComparer.OrdinalIgnoreCase);

    public GamePatch(string id, string gameName, string source, IDictionary<string, string> entries)
    {
        Id = id;
        GameName = gameName;
        Source = source;
        foreach (var (k, v) in entries)
        {
            if (string.IsNullOrWhiteSpace(k) || string.IsNullOrWhiteSpace(v)) continue;
            var normKey = Normalize(k);
            Subtitles[normKey] = v.Trim();
            var stripped = StripPunctuation(normKey);
            if (stripped.Length > 0 && !StrippedSubtitles.ContainsKey(stripped))
            {
                StrippedSubtitles[stripped] = v.Trim();
            }
        }
    }

    private static string Normalize(string text) =>
        Regex.Replace(text.Trim(), @"\s+", " ");

    private static string StripPunctuation(string text)
    {
        var sb = new StringBuilder(text.Length);
        foreach (var c in text)
        {
            if (char.IsLetterOrDigit(c) || char.IsWhiteSpace(c))
                sb.Append(char.ToLowerInvariant(c));
        }
        return Normalize(sb.ToString());
    }
}

/// <summary>
/// GameTranslator Topluluk Oyun Yamaları Yöneticisi (0 ms Gecikme, %100 İnsan Çevirisi).
/// Açılışta resmi depodan ve yerel klasörlerden yamaları otomatik günceller ve SubtitlePipeline'a bağlar.
/// </summary>
public sealed class CommunityPatchManager
{
    private static readonly Lazy<CommunityPatchManager> LazyInstance = new(() => new CommunityPatchManager());
    public static CommunityPatchManager Shared => LazyInstance.Value;

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(8) };
    private readonly object _gate = new();
    private readonly List<GamePatch> _patches = [];
    private GamePackLibrary _packLibrary;

    /// <summary>Paketlerin dili (çeviri dili): Türkçe uygulamayla gelen paketler + indirilenler; başka diller yalnız indirilenler.</summary>
    public string PackLanguage { get; private set; } = PackUpdater.Language;

    /// <summary>Paket dili değişti (o dilin paketleri indirilmeli).</summary>
    public event Action? PackLanguageChanged;

    private static GamePackLibrary CreateLibrary(string language) =>
        string.Equals(language, PackUpdater.Language, StringComparison.OrdinalIgnoreCase)
            ? new GamePackLibrary(GamePackLibrary.EmbeddedPacks, GamePackLibrary.UserDirectory, () => PackUpdater.LegacyIds(PackUpdater.DefaultStateFile))
            : new GamePackLibrary(null, GamePackLibrary.UserDirectoryFor(language), () => PackUpdater.LegacyIds(PackUpdater.StateFileFor(language)));

    /// <summary>Elle seçilen paketin yeni dildeki karşılığı ("gta-6" ↔ "gta-6-ar").</summary>
    private static string? MapPackId(string? id, string language, IEnumerable<string> ids)
    {
        if (id is null) return null;
        var all = ids.ToHashSet(StringComparer.Ordinal);
        if (all.Contains(id)) return id;
        var baseId = id.EndsWith("-ar", StringComparison.Ordinal) ? id[..^3] : id;
        var mapped = string.Equals(language, PackUpdater.Language, StringComparison.OrdinalIgnoreCase) ? baseId : baseId + "-" + language;
        return all.Contains(mapped) ? mapped : id;
    }

    /// <summary>
    /// Paket dilini değiştirir (ayarlardaki çeviri dili). Kütüphane o dilinkiyle değişir, arka planda açılır; elle seçilen
    /// oyun yeni dildeki paketine geçer. Değiştiyse <see cref="PackLanguageChanged"/>.
    /// </summary>
    public void SetPackLanguage(string language)
    {
        language = language.ToLowerInvariant();
        if (language == PackLanguage) return;
        GamePackLibrary library;
        lock (_gate)
        {
            PackLanguage = language;
            PacksReady = false;
            library = CreateLibrary(language);
            _packLibrary = library;
            _patches.Clear();
        }
        Reload();
        Task.Run(() =>
        {
            try
            {
                library.WarmUp();
                if (!ReferenceEquals(library, _packLibrary)) return;
                var mapped = MapPackId(_selectedGameId, language, library.Packs.Select(p => p.Id));
                if (mapped != _selectedGameId) { _selectedGameId = mapped; try { if (mapped is not null) File.WriteAllText(SelectionFile, mapped); } catch { } }
                library.Pinned = _selectedGameId;
                PacksReady = true;
                library.Detected = library.FindByGameName(RunningGame?.Name);
                UpdateSummaryCache();
                PatchesUpdated?.Invoke();
            }
            catch { }
        });
        PackLanguageChanged?.Invoke();
    }
    private string? _selectedGameId;
    private string _cachedSummary = Localizer.Current["✅ Community game patches are ready (0ms)"];
    public bool IsEnabled { get; set; } = true;

    public event Action? PatchesUpdated;

    public string PatchesDirectory { get; }
    public IReadOnlyList<GamePatch> Patches { get { lock (_gate) return [.. _patches]; } }

    /// <summary>
    /// Kullanıcının elle seçtiği oyun (paket ya da yama kimliği); null ise otomatik. Seçim diske yazılır, açılışta
    /// geri okunur. Seçili oyunun paketi ilk satırdan itibaren kullanılır; başka oyunların paketleri aranmaz.
    /// </summary>
    public string? SelectedGameId
    {
        get => _selectedGameId;
        set
        {
            value = string.IsNullOrWhiteSpace(value) ? null : value;
            if (value is not null) SetCustomGame(null, notify: false);
            if (_selectedGameId == value) return;
            _selectedGameId = value;
            _packLibrary.Pinned = value;
            try
            {
                if (value is null) File.Delete(SelectionFile);
                else File.WriteAllText(SelectionFile, value);
            }
            catch { }
            PatchesUpdated?.Invoke();
        }
    }

    /// <summary>
    /// Paketi olmayan bir oyunu oynadığını kullanıcı söyledi ("Grand Theft Auto VI"); null ise yok. PS5'e sorulamayan
    /// kaynaklarda (capture kart, ekran, Xbox) havuz satırları bu adla gider, ad yapay zekâya bağlam olarak da gider.
    /// Diske yazılır; bir paket seçilince silinir.
    /// </summary>
    public string? CustomGameName { get; private set; }

    /// <summary>Paketi olmayan oyunu seçer (null: kaldırır); elle paket seçimi kalkar, otomatik tanıma sürer.</summary>
    public void SetCustomGame(string? name) => SetCustomGame(name, notify: true);

    private void SetCustomGame(string? name, bool notify)
    {
        name = RunningGame.NormalizedName(name);
        if (name == CustomGameName) return;
        CustomGameName = name;
        try
        {
            if (name is null) File.Delete(CustomGameFile);
            else File.WriteAllText(CustomGameFile, name);
        }
        catch { }
        if (name is not null && _selectedGameId is not null)
        {
            _selectedGameId = null;
            _packLibrary.Pinned = null;
            try { File.Delete(SelectionFile); } catch { }
        }
        if (notify) PatchesUpdated?.Invoke();
    }

    /// <summary>%LOCALAPPDATA%\GameTranslator\custom-game.txt</summary>
    private static string CustomGameFile =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GameTranslator", "custom-game.txt");

    /// <summary>Elle seçilen paketin oyun adı; seçim yoksa ya da paketler yüklenmediyse null.</summary>
    public string? SelectedGameTitle => _selectedGameId is { } id && PacksReady ? Games.FirstOrDefault(g => g.Id == id).Title : null;

    /// <summary>
    /// Havuz satırlarına yazılacak oyun, PS5'in bildirmediği durumda (capture kart, ekran, Xbox): elle yazılan ad,
    /// elle seçilen paket, Xbox pencere başlığı ya da ilk satırlardan tanınan paket. Kimlik yoktur (PS5 doğrulaması yok).
    /// </summary>
    public RunningGame? GameForPool =>
        (CustomGameName ?? SelectedGameTitle ?? RunningGame?.Name ?? (PacksReady ? _packLibrary.ScopeTitle : null)) is { } name
            ? new RunningGame("", RunningGame.NormalizedName(name)) : null;

    /// <summary>Paketler arka planda yüklendi mi (arayüz listeyi ancak o zaman ister; yoksa yükleme beklenirdi).</summary>
    public bool PacksReady { get; private set; }

    /// <summary>%LOCALAPPDATA%\GameTranslator\selected-game.txt</summary>
    private static string SelectionFile =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GameTranslator", "selected-game.txt");

    /// <summary>Elle seçilebilen oyunlar (paketler ve yüklenen yamalar): (kimlik, ad, hazır satır sayısı), ada göre sıralı.</summary>
    public IReadOnlyList<(string Id, string Title, int Lines)> Games
    {
        get
        {
            var packs = _packLibrary.Packs.Select(p => (p.Id, p.Title, p.Entries));
            List<(string Id, string Title, int Lines)> patches;
            lock (_gate) patches = [.. _patches.Select(p => (p.Id, p.GameName, p.Count))];
            return [.. packs.Concat(patches).DistinctBy(g => g.Id).OrderBy(g => g.Title, StringComparer.CurrentCultureIgnoreCase)];
        }
    }

    /// <summary>
    /// Oynanan oyunun adı (elle seçilen, PS5'in bildirdiği ya da paketten anlaşılan); bilinmiyorsa null. Yapay zekâya
    /// bağlam olarak gider; paketi olmayan oyunda da PS5'in bildirdiği ad gider.
    /// </summary>
    public string? PlayedGameTitle => (PacksReady ? _packLibrary.ScopeTitle : null) ?? RunningGame?.Name ?? CustomGameName;

    /// <summary>PS5'te çalışan oyun (keşif yanıtından); bilinmiyorsa null.</summary>
    public RunningGame? RunningGame { get; private set; }

    /// <summary>PS5'te çalışan oyunun paketi varsa onun kimliği (elle seçim yoksa paket ilk satırdan kullanılır).</summary>
    public string? DetectedGameId => _packLibrary.Detected;

    /// <summary>Oyun belli (elle seçildi ya da PS5 bildirdi ve paketi var): paket oyunun tanınmasını beklemeden kullanılır.</summary>
    public bool GameKnown => _selectedGameId is not null || _packLibrary.Detected is not null;

    /// <summary>PS5'in bildirdiği oyun değişti; paketi varsa elle seçim yokken o paket kullanılır. Değiştiyse true.</summary>
    public bool SetRunningGame(RunningGame? game)
    {
        if (game == RunningGame) return false;
        RunningGame = game;
        _packLibrary.Detected = PacksReady ? _packLibrary.FindByGameName(game?.Name) : null;
        PatchesUpdated?.Invoke();
        return true;
    }

    /// <summary>
    /// Oyunun adı pencere başlığından okundu (Xbox kaynağı; konsola sorulamaz). Paketi varsa elle seçim yokken o paket
    /// kullanılır, ad yapay zekâya bağlam olarak gider. Kimlik boş kalır: ortak havuza bu adla bir şey gönderilmez.
    /// Değiştiyse true.
    /// </summary>
    public bool SetWindowGame(string? name)
    {
        var game = string.IsNullOrWhiteSpace(name) ? null : new RunningGame("", name.Trim());
        return SetRunningGame(game);
    }

    /// <summary>Seçili oyun bir paket mi (yama değil).</summary>
    private bool SelectedIsPack => _selectedGameId is { } id && _packLibrary.Packs.Any(p => p.Id == id);

    public CommunityPatchManager()
    {
        PatchesDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GameTranslator", "patches");
        // Önceki paket sunucusundan kalan paketler en düşük öncelikte: aynı satır bizim paketimizde varsa onunki.
        _packLibrary = CreateLibrary(PackLanguage);
        try
        {
            Directory.CreateDirectory(PatchesDirectory);
            if (File.Exists(SelectionFile) && File.ReadAllText(SelectionFile).Trim() is { Length: > 0 } saved)
                _packLibrary.Pinned = _selectedGameId = saved;
            else if (File.Exists(CustomGameFile))
                CustomGameName = RunningGame.NormalizedName(File.ReadAllText(CustomGameFile));
        }
        catch { }

        // İlk yüklemeyi hızlı yap, ağır paket çözümlemesini arka plana devret
        Reload();
        Task.Run(() =>
        {
            try
            {
                _packLibrary.WarmUp();
                PacksReady = true;
                UpdateSummaryCache();
                PatchesUpdated?.Invoke();
            }
            catch { }
        });
    }

    /// <summary>
    /// UI ve kullanıcı için özet metin: "✅ Desteklenen Oyunlar: GTA V, RDR 2, Elden Ring..."
    /// UI thread'i asla dondurmaz, önbellekten 0 gecikmeyle döner.
    /// </summary>
    public string GetStatusSummary()
    {
        if (!IsEnabled)
        {
            return Localizer.Current["⏸️ Community game patches are off. AI translation is active."];
        }
        lock (_gate)
        {
            return _cachedSummary;
        }
    }

    private void UpdateSummaryCache()
    {
        lock (_gate)
        {
            var builtInCount = _packLibrary.Packs.Count;
            var patchCount = _patches.Count;
            if (patchCount == 0 && builtInCount == 0)
            {
                _cachedSummary = Localizer.Current["💡 No game patch is installed yet. The AI engine is in use."];
                return;
            }

            var gameNames = _packLibrary.Packs.Select(p => p.Title)
                .Concat(_patches.Select(p => p.GameName))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();

            var totalLines = _packLibrary.Packs.Sum(p => p.Entries) + _patches.Sum(p => p.Count);
            var namesText = string.Join(", ", gameNames.Take(7));
            if (gameNames.Count > 7) namesText += " " + Localizer.Current["etc."];

            _cachedSummary = Localizer.Current.Format("✅ Supported games: %1$@ (%2$@ lines ready in total - 0ms)", namesText, totalLines.ToString("N0"));
        }
    }

    /// <summary>
    /// Hem yerel paketleri (%LOCALAPPDATA%\GameTranslator\packs ve patches/) hem uzak repoyu tarar.
    /// </summary>
    public async Task<(bool Success, string Message)> ScanAndReloadAsync()
    {
        Reload();
        try
        {
            await UpdateFromRemoteRepoAsync();
        }
        catch { }
        UpdateSummaryCache();
        PatchesUpdated?.Invoke();
        lock (_gate)
        {
            var count = _packLibrary.Packs.Count + _patches.Count;
            var totalLines = _packLibrary.Packs.Sum(p => p.Entries) + _patches.Sum(p => p.Count);
            return (true, Localizer.Current.Format("✅ %1$@ game packs scanned (%2$@ ready lines active).", count, totalLines.ToString("N0")));
        }
    }

    /// <summary>
    /// Yerel diskteki tüm yamaları (yerel proje dizini, AppData, paketler) yükler.
    /// </summary>
    public void Reload()
    {
        lock (_gate)
        {
            _patches.Clear();
            _packLibrary.Reload();

            // 1. Proje içindeki patches/ klasörü
            var searchPaths = new List<string>
            {
                PatchesDirectory,
                Path.Combine(AppContext.BaseDirectory, "patches"),
                Path.Combine(Directory.GetCurrentDirectory(), "patches"),
            };

            var loadedFiles = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

            foreach (var dir in searchPaths)
            {
                if (!Directory.Exists(dir)) continue;
                try
                {
                    foreach (var file in Directory.EnumerateFiles(dir, "*.*", SearchOption.TopDirectoryOnly))
                    {
                        var ext = Path.GetExtension(file).ToLowerInvariant();
                        if (ext is not (".json" or ".txt" or ".srt")) continue;
                        if (file.EndsWith("package.json", StringComparison.OrdinalIgnoreCase)) continue;

                        var fileName = Path.GetFileName(file);
                        if (loadedFiles.Contains(fileName)) continue;

                        try
                        {
                            var patch = LoadFromFile(file);
                            if (patch != null && patch.Count > 0)
                            {
                                _patches.Add(patch);
                                loadedFiles.Add(fileName);
                            }
                        }
                        catch { }
                    }
                }
                catch { }
            }
            UpdateSummaryCache();
        }

        PatchesUpdated?.Invoke();
    }

    /// <summary>
    /// GitLab / GitHub topluluk yama reposundan en son yamaları arka planda çeker ve yerel önbelleğe kaydeder.
    /// </summary>
    public async Task<(bool Success, string Message)> UpdateFromRemoteRepoAsync()
    {
        int updated = 0;
        try
        {
            // Bilinen popüler topluluk yama listesi ve repo raw URL'leri
            var knownRemoteFiles = new[]
            {
                "patches/dead_island_2.json"
            };

            var baseUrls = new[]
            {
                "https://gitlab.com/sefflex/GameTranslator/-/raw/main/",
                "https://raw.githubusercontent.com/Sefflex/GameTranslate/main/"
            };

            foreach (var relPath in knownRemoteFiles)
            {
                var fileName = Path.GetFileName(relPath);
                var targetFile = Path.Combine(PatchesDirectory, fileName);

                foreach (var baseUrl in baseUrls)
                {
                    try
                    {
                        var url = baseUrl + relPath;
                        var content = await Http.GetStringAsync(url);
                        if (!string.IsNullOrWhiteSpace(content) && content.Length > 20)
                        {
                            await File.WriteAllTextAsync(targetFile, content, Encoding.UTF8);
                            updated++;
                            break;
                        }
                    }
                    catch { }
                }
            }

            Reload();
            return (true, updated > 0 ? Localizer.Current.Format("%@ patches updated!", updated) : Localizer.Current["The patches are already up to date."]);
        }
        catch (Exception ex)
        {
            return (false, Localizer.Current.Format("Update failed: %@", ex.Message));
        }
    }

    /// <summary>
    /// Kullanıcının dosya seçiciden yüklediği yama dosyasını içeri aktarır.
    /// </summary>
    public (bool Success, int Count, string Message) ImportFile(string filePath)
    {
        try
        {
            if (!File.Exists(filePath)) return (false, 0, Localizer.Current["File not found."]);
            var patch = LoadFromFile(filePath);
            if (patch == null || patch.Count == 0) return (false, 0, Localizer.Current["No valid lines were found in the patch file."]);

            // Kalıcı klasöre kopyala
            var destPath = Path.Combine(PatchesDirectory, Path.GetFileName(filePath));
            File.Copy(filePath, destPath, true);

            Reload();
            return (true, patch.Count, Localizer.Current.Format("%2$@ lines loaded for '%1$@'!", patch.GameName, patch.Count));
        }
        catch (Exception ex)
        {
            return (false, 0, Localizer.Current.Format("Load error: %@", ex.Message));
        }
    }

    /// <summary>
    /// Okunan İngilizce satırı aktif oyun yamalarında (0ms) arar.
    /// </summary>
    public PackMatch? Match(string text)
    {
        if (!IsEnabled || string.IsNullOrWhiteSpace(text)) return null;
        var trimmed = text.Trim();

        // 1. Gömülü/kullanıcı GamePackLibrary'de ara (elle bir yama seçildiyse paketlere bakılmaz)
        if ((_selectedGameId is null || SelectedIsPack) && PackLookup.Match(_packLibrary, trimmed) is { } packMatch)
        {
            return packMatch;
        }
        // Elle seçilen oyun bir paketse yamalara bakılmaz.
        if (SelectedIsPack) return null;

        // 2. Yüklü topluluk yamalarında (Community Patches) ara
        lock (_gate)
        {
            if (_patches.Count == 0) return null;

            var activePatches = string.IsNullOrEmpty(_selectedGameId)
                ? _patches
                : _patches.Where(p => p.Id == _selectedGameId).ToList();

            if (activePatches.Count == 0) activePatches = _patches;

            // A. Tam normalleştirilmiş eşleşme
            var norm = Regex.Replace(trimmed, @"\s+", " ");
            foreach (var patch in activePatches)
            {
                if (patch.Subtitles.TryGetValue(norm, out var tr))
                    return new PackMatch(tr, []);
            }

            // B. Noktalama ve harf dışı temizlenmiş eşleşme
            var stripped = StripPunctuation(norm);
            if (stripped.Length >= 3)
            {
                foreach (var patch in activePatches)
                {
                    if (patch.StrippedSubtitles.TryGetValue(stripped, out var tr))
                        return new PackMatch(tr, []);
                }
            }

            // C. Konuşmacı etiketi ayrıştırması ("LOGAN: We need the keys!" -> "LOGAN: Anahtarları bulmamız gerek.")
            int colonIndex = trimmed.IndexOf(':');
            if (colonIndex > 0 && colonIndex <= 30 && !trimmed[..colonIndex].Contains('\n'))
            {
                var speaker = trimmed[..colonIndex].Trim();
                var dialogue = trimmed[(colonIndex + 1)..].Trim();
                var normDialogue = Regex.Replace(dialogue, @"\s+", " ");
                var strippedDialogue = StripPunctuation(normDialogue);

                foreach (var patch in activePatches)
                {
                    if (patch.Subtitles.TryGetValue(normDialogue, out var tr) ||
                        (strippedDialogue.Length >= 3 && patch.StrippedSubtitles.TryGetValue(strippedDialogue, out tr)))
                    {
                        return new PackMatch($"{speaker}: {tr}", []);
                    }
                }
            }

            // D. Kelime bazlı örtüşme (Fuzzy Match - %75 benzerlik, OCR bozulmalarına karşı)
            var words = stripped.Split(' ', StringSplitOptions.RemoveEmptyEntries).Where(w => w.Length >= 2).ToList();
            if (words.Count >= 3)
            {
                double bestScore = 0.0;
                string? bestTr = null;

                foreach (var patch in activePatches)
                {
                    foreach (var (k, v) in patch.StrippedSubtitles)
                    {
                        var kWords = k.Split(' ', StringSplitOptions.RemoveEmptyEntries).Where(w => w.Length >= 2).ToHashSet();
                        if (kWords.Count == 0) continue;

                        int common = words.Count(w => kWords.Contains(w));
                        double score = (double)common / Math.Max(words.Count, kWords.Count);
                        if (score >= 0.75 && score > bestScore)
                        {
                            bestScore = score;
                            bestTr = v;
                        }
                    }
                }

                if (bestTr != null)
                {
                    return new PackMatch(bestTr, []);
                }
            }
        }

        return null;
    }

    private static string StripPunctuation(string text)
    {
        var sb = new StringBuilder(text.Length);
        foreach (var c in text)
        {
            if (char.IsLetterOrDigit(c) || char.IsWhiteSpace(c))
                sb.Append(char.ToLowerInvariant(c));
        }
        return Regex.Replace(sb.ToString().Trim(), @"\s+", " ");
    }

    private static GamePatch? LoadFromFile(string filePath)
    {
        var ext = Path.GetExtension(filePath).ToLowerInvariant();
        var fileNameWithoutExt = Path.GetFileNameWithoutExtension(filePath);
        var entries = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        string gameName = CleanGameName(fileNameWithoutExt);

        if (ext == ".json")
        {
            var text = File.ReadAllText(filePath, Encoding.UTF8);
            using var doc = JsonDocument.Parse(text);
            var root = doc.RootElement;

            if (root.ValueKind == JsonValueKind.Object)
            {
                if (root.TryGetProperty("game", out var g) && g.ValueKind == JsonValueKind.String)
                {
                    gameName = g.GetString() ?? gameName;
                }

                if (root.TryGetProperty("subtitles", out var subs) && subs.ValueKind == JsonValueKind.Object)
                {
                    foreach (var prop in subs.EnumerateObject())
                    {
                        if (prop.Value.ValueKind == JsonValueKind.String)
                            entries[prop.Name] = prop.Value.GetString() ?? "";
                    }
                }
                else if (root.TryGetProperty("entries", out var ents) && ents.ValueKind == JsonValueKind.Object)
                {
                    foreach (var prop in ents.EnumerateObject())
                    {
                        if (prop.Value.ValueKind == JsonValueKind.String)
                            entries[prop.Name] = prop.Value.GetString() ?? "";
                    }
                }
                else
                {
                    // Düz Key: Value nesnesi
                    foreach (var prop in root.EnumerateObject())
                    {
                        if (prop.Value.ValueKind == JsonValueKind.String)
                            entries[prop.Name] = prop.Value.GetString() ?? "";
                    }
                }
            }
            else if (root.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in root.EnumerateArray())
                {
                    if (item.ValueKind == JsonValueKind.Object)
                    {
                        string? en = null, tr = null;
                        if (item.TryGetProperty("en", out var enProp)) en = enProp.GetString();
                        if (item.TryGetProperty("tr", out var trProp)) tr = trProp.GetString();
                        if (en != null && tr != null) entries[en] = tr;
                    }
                }
            }
        }
        else if (ext is ".txt" or ".srt")
        {
            var lines = File.ReadAllLines(filePath, Encoding.UTF8);
            foreach (var line in lines)
            {
                var trLine = line.Trim();
                if (string.IsNullOrWhiteSpace(trLine) || trLine.StartsWith('#')) continue;

                if (trLine.Contains('='))
                {
                    var parts = trLine.Split(['='], 2);
                    if (parts.Length == 2 && parts[0].Trim().Length > 0 && parts[1].Trim().Length > 0)
                        entries[parts[0].Trim()] = parts[1].Trim();
                }
                else if (trLine.Contains('\t'))
                {
                    var parts = trLine.Split(['\t'], 2);
                    if (parts.Length == 2 && parts[0].Trim().Length > 0 && parts[1].Trim().Length > 0)
                        entries[parts[0].Trim()] = parts[1].Trim();
                }
            }
        }

        if (entries.Count == 0) return null;
        return new GamePatch(fileNameWithoutExt, gameName, filePath, entries);
    }

    private static string CleanGameName(string name)
    {
        return name.Replace('_', ' ')
            .Replace('-', ' ')
            .Replace("community pack", "", StringComparison.OrdinalIgnoreCase)
            .Replace("slang", "", StringComparison.OrdinalIgnoreCase)
            .Trim();
    }
}
