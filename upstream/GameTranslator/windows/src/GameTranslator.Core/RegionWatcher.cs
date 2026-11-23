// SPDX-License-Identifier: AGPL-3.0-only

namespace GameTranslator.Core;

/// <summary>Ek alanların çevirisini TV'ye ileten taraf (gerçekte <see cref="SubtitleServer"/>).</summary>
public interface IRegionSender
{
    /// <summary><paramref name="text"/> boşsa alanın kutusu kalkar.</summary>
    void SendRegion(string id, string text, TextBox? box, double? lineHeight = null);
    void ClearRegions();
}

/// <summary>Sürekli okunan ek alan (görev yazısı, telefon mesajı, bildirim).</summary>
/// <param name="Id">TV'deki kutunun kimliği.</param>
/// <param name="Name">Günlükte görünen ad.</param>
public sealed record WatchedArea(string Id, string Name, CropRegion Region);

/// <summary>
/// Ek alanları ana altyazıdan ayrı ve seyrek okur, çevirip TV'ye kendi yerleriyle gönderir (Mac: RegionWatcher.swift ile
/// aynı kurallar). CouchSubs / Nino Game Translator (AGPL-3.0) RegionWatcher.cs uyarlamasıdır.
/// <list type="bullet">
/// <item>Ana okuma hattını beklemez, onu bekletmez: <see cref="Offer"/> hemen döner, tur arka planda sürer.</item>
/// <item>En çok <see cref="DefaultIntervalMs"/>'de bir tur atılır; önceki tur bitmediyse kare atlanır.</item>
/// <item>Bölge izi değişmeyen alan yeniden okunmaz.</item>
/// <item>Yazı ancak kararlıysa çevrilir: iz bir tur değişmediyse ya da iki okuma aynı yazıyı verdiyse.</item>
/// <item>Yazı değişmedikçe yeniden çevrilmez; boşalan alanın kutusu kalkar.</item>
/// <item>Paket ve çeviri motoru altyazıyla aynıdır; ama ek alan satırları ortak havuza önerilmez.</item>
/// </list>
/// </summary>
public sealed class RegionWatcher
{
    /// <summary>
    /// İki tur arasındaki en kısa süre. 1 saniyeyken yazı ancak 2–3 saniyede çevriliyordu (bir tur bekleme, bir tur
    /// kararlılık); görüntüsü değişmeyen alan yeniden okunmadığı için sık tur işlemciyi yormaz.
    /// </summary>
    public const int DefaultIntervalMs = 250;

    private sealed class State
    {
        public byte[]? Signature;
        public string? LastRead;
        public Candidate? Candidate;
        public string Shown = "";
        public string? RenderedKey;
        public string? RenderedText;
        public TextBox? RenderedBox;
        public double? RenderedLineHeight;
        /// <summary>Son gösterilen (boş olmayan) yazı; alan boşalınca da hatırlanır.</summary>
        public string Remembered = "";
        /// <summary>Yarım okuma ("Fly to a", tamamı "Fly to a drop") kaç turdur bekletiliyor.</summary>
        public int PrefixWaits;
        /// <summary>Gösterilen yazıdaki tuş simgesi sayısı.</summary>
        public int ShownIcons;
    }

    /// <summary>
    /// Okuma, gösterilen yazının eksik hâli mi ("ADD MARKER □ SET DESTINATIONLO" ← "ADD MARKER □ SET DESTINATION ✕ EXIT ○"):
    /// sözcüklerinin hepsi (biri harf hatalı olabilir) gösterilende sırasıyla geçiyor.
    /// </summary>
    internal static bool LosesWords(string reading, string shown)
    {
        static string[] Words(string key) => [.. key.Split(' ', StringSplitOptions.RemoveEmptyEntries).Where(token => token.Any(char.IsLetterOrDigit))];
        var have = Words(reading);
        var lost = Words(shown).Count(word => !have.Any(w => w == word || TextMatch.IsSimilar(w, word, 0.75)));
        // Aynı yazı olmalı: okumanın sözcükleri gösterilende geçiyor, ama gösterilenin bir sözcüğü kayıp.
        var shownWords = Words(shown);
        return lost > 0 && have.Length > 0 && have.All(word => shownWords.Any(w => w == word || TextMatch.IsSimilar(w, word, 0.75)));
    }

    internal static bool IsPartOf(string reading, string shown)
    {
        var part = reading.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        var whole = shown.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (part.Length == 0 || part.Length >= whole.Length) return false;
        int at = 0, misses = 0;
        foreach (var word in part)
        {
            var found = Array.FindIndex(whole, at, w => w == word || TextMatch.IsSimilar(w, word, 0.75));
            if (found < 0) { if (++misses > 1) return false; continue; }
            at = found + 1;
        }
        return true;
    }

    private static int Icons(IEnumerable<string> paragraphs) =>
        paragraphs.Sum(paragraph => paragraph.Split(' ', StringSplitOptions.RemoveEmptyEntries).Count(ButtonPrompts.IsIcon));

    /// <summary>
    /// Çevrilmiş yazılar (anahtar → TV'ye giden metin). Oyun görev yazısını arada silip yeniden gösteriyor; aynı yazı
    /// gelince çevirmene yeniden gidilmez, anında gösterilir.
    /// </summary>
    private readonly Dictionary<string, string> rendered = [];
    private const int RenderedLimit = 64;
    /// <summary>Yarım okuma en çok bu kadar tur bekletilir; tamamı gelmezse yine de gösterilir.</summary>
    private const int PrefixWaitLimit = 4;

    internal sealed record Candidate(IReadOnlyList<string> Paragraphs, TextBox? Box, double? LineHeight, string Key)
    {
        /// <summary>Oyunun renkli yazdığı sözcükler; çeviride aynı renkte gösterilir.</summary>
        public IReadOnlyList<ColoredPhrase> Colored { get; init; } = [];
    }

    private readonly ITextReader reader;
    private readonly ITextTranslator translator;
    private readonly IRegionSender server;
    private readonly int intervalMs;
    private readonly Func<long> now;
    private Dictionary<string, State> states = [];
    private long? lastPass;
    private int busy;
    private int session;
    private int passes;
    private int reads;

    /// <summary>Oyun paketi: satırın hazır Türkçesi (ana altyazıyla aynı arama).</summary>
    public Func<string, PackMatch?>? PackLookup { get; set; }
    public Action<string>? Log { get; set; }
    /// <summary>
    /// Tuş ipuçları ("Hold ✕ to climb", "✕ Select ○ Back") atılmaz, simgesiyle çevrilir. Okuyucu simgeleri görüntüden
    /// tanıyorsa (<see cref="IIconReader"/>) açılır.
    /// </summary>
    public bool KeepsPrompts { get; set; }
    /// <summary>
    /// Yazı kararlı mı diye beklenirken çevirisi şimdiden istensin (gecikmeyi DeepL'in süresi kadar kısaltır; yazı
    /// belirirken okunan yarım hâller de çevrilebildiği için biraz fazla karakter harcar).
    /// </summary>
    public bool PrefetchesTranslations { get; set; }
    /// <summary>Ek alanda çevrilmeyecek satır (altyazının alana düşen satırı); verilmezse hepsi çevrilir.</summary>
    public Func<string, bool>? SkipLine { get; set; }
    /// <summary>Her okumanın süresi (ms; ölçüm için).</summary>
    public Action<long>? ReadMeasured { get; set; }
    public int Passes => Volatile.Read(ref passes);
    public int Reads => Volatile.Read(ref reads);
    public bool IsBusy => Volatile.Read(ref busy) == 1;

    public RegionWatcher(ITextReader reader, ITextTranslator translator, IRegionSender server, int intervalMs = DefaultIntervalMs,
                         Func<long>? clock = null)
    {
        this.reader = reader;
        this.translator = translator;
        this.server = server;
        this.intervalMs = intervalMs;
        now = clock ?? (() => Environment.TickCount64);
    }

    /// <summary>Her karede çağrılır; tur zamanı geldiyse ve önceki tur bittiyse turu arka planda başlatır, hemen döner.</summary>
    public void Offer(Frame frame, IReadOnlyList<WatchedArea> areas)
    {
        if (areas.Count == 0 && !AnyShown()) return;
        var time = now();
        if (lastPass is { } last && time - last < intervalMs) return;
        if (Interlocked.Exchange(ref busy, 1) == 1) return;
        lastPass = time;
        _ = Task.Run(async () =>
        {
            try { await PassAsync(frame, areas).ConfigureAwait(false); }
            catch (Exception error) { Log?.Invoke($"ek alan turu hata verdi: {error.Message}"); }
            finally { Volatile.Write(ref busy, 0); }
        });
    }

    private bool AnyShown()
    {
        var map = states;
        lock (map) return map.Values.Any(state => state.Shown.Length > 0);
    }

    /// <summary>Bir tur: kaldırılan alanların kutusu kalkar, her alan gerekirse okunur ve kararlı yazısı çevrilir. Testler doğrudan çağırır.</summary>
    public async Task PassAsync(Frame frame, IReadOnlyList<WatchedArea> areas)
    {
        var started = Volatile.Read(ref session);
        var map = states;
        Interlocked.Increment(ref passes);
        var ids = areas.Select(area => area.Id).ToHashSet();
        List<string> removed;
        lock (map)
        {
            removed = [.. map.Where(pair => !ids.Contains(pair.Key) && pair.Value.Shown.Length > 0).Select(pair => pair.Key)];
            foreach (var id in map.Keys.Where(id => !ids.Contains(id)).ToList()) map.Remove(id);
        }
        foreach (var id in removed) server.SendRegion(id, "", null);
        foreach (var area in areas)
        {
            await VisitAsync(area, frame, map, started).ConfigureAwait(false);
            if (Volatile.Read(ref session) != started) return;
        }
    }

    private async Task VisitAsync(WatchedArea area, Frame frame, Dictionary<string, State> map, int started)
    {
        var crop = RegionCropper.Crop(frame, area.Region);
        var signature = RegionCropper.Signature(crop);
        State state;
        lock (map)
        {
            if (!map.TryGetValue(area.Id, out state!)) map[area.Id] = state = new State();
        }
        var changed = state.Signature is not { } previous || ReadScheduler.Changed(previous, signature, ReadScheduler.ChangeThreshold);
        state.Signature = signature;
        if (!changed)
        {
            // İz bir tur değişmedi: okunan yazı kararlıdır.
            if (state.Candidate is { } stable)
            {
                state.Candidate = null;
                await CommitAsync(stable, area, state, started).ConfigureAwait(false);
            }
            return;
        }
        var clock = now();
        Reading reading;
        try { reading = reader.ReadLines(crop, false); }
        catch
        {
            // OCR hatası boş alan değildir. Eski kutuyu koru; aynı kareyi sonraki normal turda yeniden oku.
            // Başarısız kare, önceki adayın kararlılığını da doğrulayamaz.
            state.Signature = null;
            state.LastRead = null;
            state.Candidate = null;
            return;
        }
        if (Volatile.Read(ref session) != started) return;
        Interlocked.Increment(ref reads);
        ReadMeasured?.Invoke(now() - clock);
        if (SkipLine is { } skip && reading.Lines.Any(line => skip(line.Text)))
            reading = new Reading(reading.Lines.Where(line => !skip(line.Text)).ToList());
        var candidate = MakeCandidate(reading, area.Region, KeepsPrompts);
        if (reader is IIconReader { LastColored.Count: > 0 } colors)
            candidate = candidate with { Colored = [.. colors.LastColored.Where(phrase => candidate.Paragraphs.Any(p => p.Contains(phrase.Text, StringComparison.Ordinal)))] };
        if (state.LastRead == candidate.Key)
        {
            state.Candidate = null;
            await CommitAsync(candidate, area, state, started).ConfigureAwait(false);
        }
        else
        {
            state.LastRead = candidate.Key;
            state.Candidate = candidate;
            // Yazı kararlı mı diye bir tur beklenirken çevirisi şimdiden istenir: doğrulanınca çoğu zaman hazırdır.
            Prefetch(candidate, state);
        }
    }

    /// <summary>
    /// Okumadan çevrilecek parçalar: kumanda ipuçları, yazı tanıma çöpleri ve harfsiz göstergeler atılır; satırlar
    /// yukarıdan aşağı parçalara toplanır.
    /// </summary>
    internal static Candidate MakeCandidate(Reading reading, CropRegion region, bool keepPrompts = false)
    {
        // Alanın sol ya da sağ kenarına değen satır, alanın dışında süren bir yazının parçasıdır (uzun altyazı satırının ucu
        // telefon alanına taşıyordu: "ot it?", "nd..."); oyunun kutuları ve tuş çubuğu kenara değmez.
        var lines = InReadingOrder((keepPrompts ? reading : reading.WithoutButtonPrompts()).WithoutOcrNoise().Lines
            .Where(line => ScreenTranslation.IsWords(line.Text))
            // Kesilen harfler okunmadığından kesik satır kenara tam değmeyebilir ("hing set" ← "everything set"): pay %4.
            .Where(line => line.Box is not { } b || (b.Left > 0.04 && b.Right < 0.96)));
        // Oyunun menü listesi (GTA 6'da duraklatınca soldaki "Game / Store / Help") çevrilmez; alan boş sayılır.
        if (IsMenuList(lines)) lines = [];
        // Küçük yazıda "m" harfi "rn" / "nn" okunuyor ("frorn your noailbox"): sözlüğe göre düzeltilir.
        var paragraphs = ScreenTranslation.Paragraphs(lines).Select(paragraph => LetterM.Fix(paragraph, SubtitlePipeline.ExtraWordCheck)).ToList();
        var place = new TextBox(region.Left, region.Top, region.Right, region.Bottom);
        var boxes = lines.Where(line => line.Box is not null).Select(line => line.Box!.Value).ToList();
        TextBox? box = boxes.Count == 0 ? null : boxes.Skip(1).Aggregate(boxes[0], (a, b) => a.Union(b)).PlacedIn(place);
        var heights = boxes.Select(b => (b.Bottom - b.Top) * (region.Bottom - region.Top)).Order().ToList();
        double? lineHeight = heights.Count == 0 ? null : heights[heights.Count / 2];
        // Anahtar tuş simgelerini de içerir: simgesi sonradan bulunan okuma ("SET DESTINATION ✕") eskisinden ayrılsın.
        var key = string.Join(' ', paragraphs.SelectMany(paragraph => paragraph.Split(' ', StringSplitOptions.RemoveEmptyEntries))
            .Select(token => ButtonPrompts.IsIcon(token) && !token.Any(char.IsLetterOrDigit) ? token : TextMatch.Normalize(token))
            .Where(token => token.Length > 0));
        return new Candidate(paragraphs, box, lineHeight, key);
    }

    /// <summary>
    /// Satırlar okuma sırasıyla: yukarıdan aşağı, aynı hizadakiler soldan sağa. Başka renkte yazılmış sözcük ayrı satır
    /// okunuyor ve biraz yukarıda başladığı için öne geçiyordu ("Brian Follow").
    /// </summary>
    internal static List<TextLine> InReadingOrder(IEnumerable<TextLine> source)
    {
        var rows = new List<List<TextLine>>();
        foreach (var line in source.OrderBy(line => line.Box?.Top ?? 0))
        {
            var row = line.Box is { } box ? rows.FirstOrDefault(r => r[0].Box is { } first &&
                Math.Abs((first.Top + first.Bottom) / 2 - (box.Top + box.Bottom) / 2) < Math.Max(first.Bottom - first.Top, box.Bottom - box.Top) * 0.5) : null;
            if (row is null) rows.Add([line]);
            else row.Add(line);
        }
        return [.. rows.SelectMany(row => row.OrderBy(line => line.Box?.Left ?? 0))];
    }

    /// <summary>
    /// Menü listesi mi: alt alta en az üç kısa satır (en çok üç sözcük, tuş simgeleri sayılmaz), hiçbiri cümle gibi
    /// bitmiyor ve hiçbiri tuş simgesiyle bitmiyor. Sonu tuşla biten satır tuş göstergesidir ("SET DESTINATION ✕",
    /// "EXIT ○"; haritadaki dikey tuş listesi) ve çevrilir; menü maddesinde simge başta durur ("○ Store") ya da hiç yoktur.
    /// </summary>
    internal static bool IsMenuList(IReadOnlyList<TextLine> lines)
    {
        if (lines.Count < 3) return false;
        return lines.All(line =>
        {
            var tokens = line.Text.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (tokens.Length == 0 || ButtonPrompts.IsIcon(tokens[^1])) return false;
            var words = tokens.Count(word => !ButtonPrompts.IsIcon(word) && word.Any(char.IsLetter));
            return words is >= 1 and <= 3 && !SubtitleStabilizer.EndsSentence(line.Text);
        });
    }

    private async Task CommitAsync(Candidate candidate, WatchedArea area, State state, int started)
    {
        if (state.Shown == candidate.Key)
        {
            if (state.RenderedKey != candidate.Key || string.IsNullOrEmpty(state.RenderedText)) return;
            if (state.RenderedBox == candidate.Box && state.RenderedLineHeight == candidate.LineHeight) return;
            server.SendRegion(area.Id, state.RenderedText, candidate.Box, candidate.LineHeight);
            state.RenderedBox = candidate.Box;
            state.RenderedLineHeight = candidate.LineHeight;
            return;
        }
        // Yazı belirirken renkli sözcük geç geliyor: önce "Fly to a", yarım saniye sonra "Fly to a drop" okunuyordu ve
        // görev her belirişinde iki kez çevrilip değişiyordu. Az önce gösterilen yazının başı okunursa tamamı beklenir.
        if (candidate.Key.Length > 0 && state.Remembered.Length > candidate.Key.Length &&
            state.Remembered.StartsWith(candidate.Key + " ", StringComparison.Ordinal) && state.PrefixWaits < PrefixWaitLimit)
        {
            state.PrefixWaits++;
            state.Candidate = candidate;
            return;
        }
        state.PrefixWaits = 0;
        // Ekrandakiyle neredeyse aynı okuma (harita arkada oynarken tuş çubuğu "SET DESTINATION ✕ EXIT ○" / "SET
        // DESTINATIPN EXIT ○" diye okunup kutu sürekli değişiyordu): daha çok tuş simgesi bulmadıysa ekrandaki kalır.
        if (candidate.Key.Length > 0 && state.Shown.Length > 0 && state.Shown != candidate.Key &&
            ((Icons(candidate.Paragraphs) <= state.ShownIcons && (TextMatch.IsSimilar(state.Shown, candidate.Key, 0.85) || IsPartOf(candidate.Key, state.Shown))) ||
             // Simgesi fazla ama ekrandakinin bir sözcüğünü simgeyle değiştirmiş ("Find □" ← "Find Donnie"): bozuk okumadır.
             (Icons(candidate.Paragraphs) > state.ShownIcons && LosesWords(candidate.Key, state.Shown))))
            return;
        // Altyazı bu sırada gösterilmiş olabilir: alana taşan altyazı ucu gönderilmeden önce bir kez daha denetlenir.
        if (candidate.Key.Length > 0 && SkipLine is { } skipNow && candidate.Paragraphs.All(skipNow))
            candidate = candidate with { Paragraphs = [], Key = "", Box = null, LineHeight = null, Colored = [] };
        var previousShown = state.Shown;
        state.Shown = candidate.Key;
        state.ShownIcons = Icons(candidate.Paragraphs);
        string? cached = null;
        lock (rendered) if (candidate.Key.Length > 0) rendered.TryGetValue(candidate.Key, out cached);
        if (cached is not null)
        {
            // Daha önce çevrildi: anında, çevirmene gitmeden.
            if (cached.Length == 0) server.SendRegion(area.Id, "", null);
            else server.SendRegion(area.Id, cached, candidate.Box, candidate.LineHeight);
            state.RenderedKey = candidate.Key;
            state.RenderedText = cached;
            state.RenderedBox = cached.Length == 0 ? null : candidate.Box;
            state.RenderedLineHeight = cached.Length == 0 ? null : candidate.LineHeight;
            state.Remembered = candidate.Key;
            Log?.Invoke($"ek alan {area.Name}: {SubtitleLog.Excerpt(string.Join(' ', candidate.Paragraphs))} → önceki çeviri ⇒ " +
                        SubtitleLog.Excerpt(ColoredWords.Plain(cached.Replace('\n', '/'))));
            return;
        }
        if (candidate.Key.Length == 0)
        {
            server.SendRegion(area.Id, "", null);
            state.RenderedKey = candidate.Key;
            state.RenderedText = "";
            state.RenderedBox = null;
            state.RenderedLineHeight = null;
            Log?.Invoke($"ek alan {area.Name}: boşaldı");
            return;
        }
        string?[] result;
        int fromPack;
        try
        {
            (result, fromPack) = await Translated(candidate).ConfigureAwait(false);
            lock (inflight) inflight.Remove(candidate.Key);
        }
        catch (Exception error)
        {
            if (Volatile.Read(ref session) == started)
            {
                if (state.Shown == candidate.Key)
                {
                    state.Shown = previousShown;
                    state.Candidate = candidate;
                }
                Log?.Invoke($"ek alan {area.Name}: {SubtitleLog.Excerpt(string.Join(' ', candidate.Paragraphs))} çevrilemedi ({error.Message})");
            }
            return;
        }
        if (Volatile.Read(ref session) != started || state.Shown != candidate.Key) return;
        // Motorun düşürdüğü tuş simgeleri yerine konur ("SHOW TEXT △" ⇒ "Metni göster" ⇒ "Metni göster △").
        var text = candidate.Paragraphs.Select((source, i) => result[i]?.Trim() is { Length: > 0 } translated ? UiPrompts.KeepIcons(source, translated) : source).ToList();
        // Çevirisi aslıyla aynı (ad, sayı, zaten hedef dilde): kutu gösterilmez.
        var same = candidate.Paragraphs.Select((source, i) => TextMatch.IsSimilar(TextMatch.Normalize(source), TextMatch.Normalize(text[i]), 0.90))
            .All(similar => similar);
        if (same)
        {
            Remember(candidate.Key, "");
            server.SendRegion(area.Id, "", null);
            state.RenderedKey = candidate.Key;
            state.RenderedText = "";
            state.RenderedBox = null;
            state.RenderedLineHeight = null;
            return;
        }
        if (candidate.Colored.Count > 0) text = await WithColorsAsync(candidate, text).ConfigureAwait(false);
        var origin = fromPack == candidate.Paragraphs.Count ? "paket" : fromPack > 0 ? "paket+motor" : "motor";
        Log?.Invoke($"ek alan {area.Name}: {SubtitleLog.Excerpt(string.Join(' ', candidate.Paragraphs))} → {origin} ⇒ " +
                    SubtitleLog.Excerpt(ColoredWords.Plain(string.Join(" / ", text))) + Place(candidate.Box));
        server.SendRegion(area.Id, string.Join('\n', text), candidate.Box, candidate.LineHeight);
        Remember(candidate.Key, string.Join('\n', text));
        state.Remembered = candidate.Key;
        state.RenderedKey = candidate.Key;
        state.RenderedText = string.Join('\n', text);
        state.RenderedBox = candidate.Box;
        state.RenderedLineHeight = candidate.LineHeight;
    }

    /// <summary>
    /// Renkli sözcüklerin çeviriyi aynı renkte gösterir: her renkli öbek tek başına çevrilir (önce paket) ve karşılığı
    /// cümlede işaretlenir (<see cref="ColoredWords.Mark"/>). Çeviri olmazsa metin renksiz kalır.
    /// </summary>
    private async Task<List<string>> WithColorsAsync(Candidate candidate, List<string> text)
    {
        var parts = new List<(string Translated, string Color)>();
        foreach (var phrase in candidate.Colored)
        {
            try
            {
                var translated = PackLookup?.Invoke(phrase.Text)?.Text ?? await translator.TranslateAsync(phrase.Text).ConfigureAwait(false);
                if (!string.IsNullOrWhiteSpace(translated)) parts.Add((translated.Trim().TrimEnd('.', '!', '?'), phrase.Color));
            }
            catch
            {
                // Renk süs: çevrilemezse sözcük renksiz kalır.
            }
        }
        if (parts.Count == 0) return text;
        var marked = text.Select(paragraph => ColoredWords.Mark(paragraph, parts)).ToList();
        Log?.Invoke($"renkli: {string.Join(", ", candidate.Colored.Select(phrase => phrase.Text + " " + phrase.Color))} → " +
                    $"{string.Join(", ", parts.Select(part => part.Translated))}{(marked.SequenceEqual(text) ? " (çeviride bulunamadı)" : "")}");
        return marked;
    }

    /// <summary>Süren ya da bitmiş çeviriler (anahtar → paragrafların çevirisi ve paketten gelen sayısı).</summary>
    private readonly Dictionary<string, Task<(string?[] Result, int FromPack)>> inflight = [];

    /// <summary>Çevirinin görevini başlatır ya da süreni verir; bittikten sonra listeden çıkar.</summary>
    private Task<(string?[] Result, int FromPack)> Translated(Candidate candidate)
    {
        lock (inflight)
        {
            if (inflight.TryGetValue(candidate.Key, out var running)) return running;
            if (inflight.Count > 32) inflight.Clear();
            var task = TranslateAsync(candidate.Paragraphs);
            inflight[candidate.Key] = task;
            // Başarısız çeviri listede kalmaz (sonraki gösterimde yeniden denenir); başarılısı gösterilene kadar saklanır.
            _ = task.ContinueWith(_ => { lock (inflight) inflight.Remove(candidate.Key); },
                CancellationToken.None, TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
            return task;
        }
    }

    /// <summary>
    /// Kararlılık beklenirken çeviriyi başlatır. Yarım okuma (az önce gösterilen yazının başı) ve daha önce çevrilmiş yazı
    /// için istenmez.
    /// </summary>
    private void Prefetch(Candidate candidate, State state)
    {
        if (!PrefetchesTranslations || candidate.Key.Length == 0 || candidate.Key == state.Shown) return;
        if (state.Remembered.StartsWith(candidate.Key + " ", StringComparison.Ordinal)) return;
        lock (rendered) if (rendered.ContainsKey(candidate.Key)) return;
        if (SkipLine is { } skip && candidate.Paragraphs.All(skip)) return;
        // Hata gözlenir; asıl gösterimde yeniden denenir.
        _ = Translated(candidate).ContinueWith(task => _ = task.Exception, TaskContinuationOptions.OnlyOnFaulted);
    }

    private async Task<(string?[] Result, int FromPack)> TranslateAsync(IReadOnlyList<string> paragraphs)
    {
        string?[] result = [.. paragraphs.Select(paragraph => PackLookup?.Invoke(paragraph)?.Text)];
        var fromPack = result.Count(text => text is not null);
        var missing = Enumerable.Range(0, result.Length).Where(i => result[i] is null).ToList();
        if (missing.Count > 0)
        {
            var texts = missing.Select(i => paragraphs[i]).ToList();
            var translations = await translator.TranslateAllAsync(texts).ConfigureAwait(false);
            for (var i = 0; i < missing.Count && i < translations.Count; i++) result[missing[i]] = translations[i];
        }
        return (result, fromPack);
    }

    /// <summary>Kutunun karedeki yeri (günlük için; TV'de ve OBS'te yanlış yere düşen kutuyu bulmak).</summary>
    private static string Place(TextBox? box) => box is { } b
        ? FormattableString.Invariant($" [x %{b.Left * 100:0}–{b.Right * 100:0}, y %{b.Top * 100:0}–{b.Bottom * 100:0}]")
        : "";

    private void Remember(string key, string text)
    {
        lock (rendered)
        {
            if (rendered.Count >= RenderedLimit) rendered.Clear();
            rendered[key] = text;
        }
    }

    /// <summary>Çeviri durdu ya da hat yeniden kuruldu: süren tur bırakılır, TV'deki ek alan kutuları kalkar.</summary>
    public void Reset()
    {
        Interlocked.Increment(ref session);
        var anyShown = AnyShown();
        states = [];
        lastPass = null;
        if (anyShown) server.ClearRegions();
    }
}
