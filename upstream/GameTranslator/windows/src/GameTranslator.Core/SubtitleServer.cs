// SPDX-License-Identifier: AGPL-3.0-only

using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace GameTranslator.Core;

/// <summary>TV'de çevirinin oyundaki İngilizce altyazıya göre yeri.</summary>
[JsonConverter(typeof(JsonStringEnumConverter<SubtitlePlacement>))]
public enum SubtitlePlacement
{
    /// <summary>İngilizce altyazının üstüne, onu örterek.</summary>
    Cover,
    /// <summary>İngilizce altyazının hemen üstünde.</summary>
    Above,
    /// <summary>İngilizce altyazının hemen altında.</summary>
    Below,
    /// <summary>Ekranda sabit bir yükseklikte (<see cref="SubtitleStyle.BottomPercent"/>).</summary>
    Fixed,
}

/// <summary>TV'de altyazının görünümü. Eski sürümün kaydettiği ayarlarda olmayan alanlar varsayılanla dolar.</summary>
/// <param name="FontSize">36 (küçük), 46 (orta), 58 (büyük), 72 (çok büyük)</param>
/// <param name="BottomPercent">Sabit yerleşimde altyazının ekranın altından uzaklığı, yüzde.</param>
/// <param name="Background">Yazının arkasındaki siyah zeminin koyuluğu, yüzde (0: zemin yok).</param>
/// <param name="RegionBackground">Ek alan kutusunun zemininin koyuluğu, yüzde (100: İngilizce hiç görünmez).</param>
/// <param name="RegionTextScale">Ek alan yazısının boyu, kutuya göre seçilen boyun yüzdesi (70–150).</param>
/// <param name="RegionTextColor">Ek alan yazısının rengi; null: altyazının rengi.</param>
/// <param name="StackPrevious">
/// Hızlı konuşmada önceki satır(lar) yeni satırın üstünde kısa süre soluk kalsın mı; kapalıysa yeni satır eskisinin
/// yerine geçer. TV uygulaması 0.9.16 ve sonrası tanır.
/// </param>
public sealed record SubtitleStyle(
    int FontSize = 46,
    int BottomPercent = 7,
    SubtitlePlacement Placement = SubtitlePlacement.Cover,
    int Background = 72,
    bool Bold = false,
    SubtitleTextColor TextColor = SubtitleTextColor.White,
    TvDisplayMode DisplayMode = TvDisplayMode.InApp,
    bool StackPrevious = true,
    bool SpeakerColor = false,
    string Font = "",
    int RegionBackground = 100,
    int RegionTextScale = 100,
    SubtitleTextColor? RegionTextColor = null);

/// <summary>Bilgisayarda kurulu bir font dosyası (TV, OBS ve ikinci ekran onu sunucudan indirir).</summary>
public sealed record FontFile(byte[] Data, string Type);

/// <summary>
/// Altyazı fontu. <see cref="SubtitleStyle.Font"/> boşsa her ekranın kendi standart yazısı, hazır seçeneklerden biriyse
/// onun font adı, değilse bilgisayarda kurulu bir fontun adıdır. Font dosyaları uygulamaya gömülmez (lisansları
/// dağıtıma izin vermez); kullanıcı fontu kendi bilgisayarına kurar, uygulama onu bulup kendi ekranlarına verir.
/// </summary>
public static class SubtitleFonts
{
    /// <summary>Eski ad: menüde artık yok, kaydedilmişse Pricedown sayılır.</summary>
    public const string Gta6 = "gta6";
    public const string Pricedown = "pricedown";
    /// <summary>Uygulamaya gömülü (SIL OFL): GTA 6 altyazılarına en yakın açık lisanslı font.</summary>
    public const string Quicksand = "quicksand";

    /// <summary>Menüde gösterilecek anahtar (eski "gta6" → "pricedown").</summary>
    public static string MenuKey(string? font) => font?.Trim() is Gta6 ? Pricedown : font?.Trim() ?? "";

    /// <summary>
    /// Pricedown Black'in bedava indirmesi (Typodermic'in dafont'ta Typodermic Desktop License ile yayımladığı zip;
    /// Typodermic'in kendi sayfası artık yalnız ücretli satıcılara gidiyor). Uygulama zip'i indirip fontu kullanıcının
    /// kendi bilgisayarına kurar; dosya uygulamayla dağıtılmaz.
    /// </summary>
    public const string PricedownDownload = "https://dl.dafont.com/dl/?f=pricedown";

    /// <summary>İndirme olmazsa tarayıcıda açılan sayfa.</summary>
    public const string PricedownPage = "https://www.dafont.com/pricedown.font";

    /// <summary>Menüdeki hazır seçenekler: ayardaki anahtar ve gösterilen ad.</summary>
    public static IReadOnlyList<(string Key, string Label)> Presets { get; } = [(Quicksand, "Quicksand (GTA 6)"), (Pricedown, "Pricedown")];

    /// <summary>Ayardaki fontun aranacak adı; null: standart yazı.</summary>
    public static string? Family(string? font) => font?.Trim() switch
    {
        null or "" => null,
        Gta6 or Pricedown => "Pricedown",
        Quicksand => "Quicksand",
        var name => name,
    };

    /// <summary>Uygulamanın içindeki font dosyaları (aile adı → kaynak adı).</summary>
    private static readonly Dictionary<string, string> EmbeddedFonts = new(StringComparer.OrdinalIgnoreCase)
    {
        ["Quicksand"] = "fonts/Quicksand-Bold.ttf",
    };

    /// <summary>Gömülü fontun dosyası (kurulum gerekmez); yoksa null.</summary>
    public static FontFile? Embedded(string? family)
    {
        if (family is null || !EmbeddedFonts.TryGetValue(family, out var resource)) return null;
        using var stream = typeof(SubtitleFonts).Assembly.GetManifestResourceStream(resource);
        if (stream is null) return null;
        using var memory = new MemoryStream();
        stream.CopyTo(memory);
        return new FontFile(memory.ToArray(), "font/ttf");
    }

    /// <summary>Gömülü fontun dosya adı ("Quicksand-Bold.ttf"); bilgisayardaki pencereler onu diske açıp kullanır.</summary>
    public static string? EmbeddedFileName(string? family) =>
        family is not null && EmbeddedFonts.TryGetValue(family, out var resource) ? Path.GetFileName(resource) : null;

    /// <summary>Hazır seçeneğin font zip'i (kurulu değilse "Fontu indir ve kur" bunu indirir).</summary>
    public static string? DownloadPage(string? font) => font is Gta6 or Pricedown ? PricedownDownload : null;

    /// <summary>İndirme olmazsa tarayıcıda açılacak sayfa.</summary>
    public static string? WebPage(string? font) => font is Gta6 or Pricedown ? PricedownPage : null;
}

/// <summary>
/// Android TV'de PS5 görüntüsünün nasıl gösterildiği. Yalnız Android TV uygulaması kullanır; diğer TV'ler ve OBS
/// katmanı alanı tanımaz.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter<TvDisplayMode>))]
public enum TvDisplayMode
{
    /// <summary>HDMI GameTranslator'ın içinde; görüntü hiç gelmezse TV uygulaması kendiliğinden katmana geçer.</summary>
    InApp,
    /// <summary>TV HDMI'yı kendi ekranında gösterir, altyazı üstteki şeffaf katmanda (bazı TV'lerde uygulama içi görüntü siyah kalıyor).</summary>
    Overlay,
}

/// <summary>TV'de altyazı yazısının rengi.</summary>
[JsonConverter(typeof(JsonStringEnumConverter<SubtitleTextColor>))]
public enum SubtitleTextColor
{
    White,
    Yellow,
    Cyan,
    Green,
    Orange,
}

public static class SubtitleTextColors
{
    /// <summary>Yazı renginin ekrandaki tonu (TV uygulaması ve OBS katmanı <c>color</c> alanını okur).</summary>
    public static string Hex(this SubtitleTextColor color) => color switch
    {
        SubtitleTextColor.Yellow => "#FFE600",
        SubtitleTextColor.Cyan => "#38BDF8",
        SubtitleTextColor.Green => "#10B981",
        SubtitleTextColor.Orange => "#FF9F0A",
        _ => "#FFFFFF",
    };
}

/// <summary>
/// Konuşmacı adlarının renkleri ("Konuşmacı adı renkli" açıkken). GTA 6'daki gibi her konuşmacı kendi rengini alır: renk
/// ilk görülme sırasıyla verilir, aynı ad hep aynı renkte kalır; renkler biterse en uzun süredir konuşmayanınki alınır.
/// TV uygulaması, OBS katmanı ve ikinci ekran aynı kuralı uygular (style olayındaki <c>speakerColors</c>).
/// </summary>
public sealed class SpeakerColors
{
    private static readonly (string Hex, SubtitleTextColor? Clash)[] All =
    [
        ("#FFD60A", SubtitleTextColor.Yellow), ("#64D2FF", SubtitleTextColor.Cyan), ("#FF9F0A", SubtitleTextColor.Orange),
        ("#30D158", SubtitleTextColor.Green), ("#FF7AB6", null), ("#C49BFF", null),
    ];

    /// <summary>Yazı rengine karışmayan renkler; ilki eski tek renkle aynıdır (sarı, yazı sarıysa açık mavi).</summary>
    public static IReadOnlyList<string> Palette(SubtitleTextColor text) => [.. All.Where(c => c.Clash != text).Select(c => c.Hex)];

    /// <summary>Ad: büyük harfle başlayan en çok üç sözcük, satır başında ya da cümle bitiminde, ardından ":" (TV ile aynı).</summary>
    private static readonly System.Text.RegularExpressions.Regex Speaker = new(
        @"(^|[.!?…”""]\s+|\n)([A-ZÇĞİÖŞÜÂÎÛ][^\s:.!?]*(?: [A-ZÇĞİÖŞÜÂÎÛ][^\s:.!?]*){0,2}):(?=\s)",
        System.Text.RegularExpressions.RegexOptions.Compiled);

    private IReadOnlyList<string> palette = [];
    private readonly Dictionary<string, string> colors = new(StringComparer.Ordinal);
    private readonly List<string> recent = [];

    /// <summary>Metni parçalara böler: konuşmacı adı (":" dahil) rengiyle, gerisi null renkle.</summary>
    public IReadOnlyList<(string Text, string? Color)> Split(string text, IReadOnlyList<string> palette)
    {
        if (!this.palette.SequenceEqual(palette))
        {
            this.palette = [.. palette];
            colors.Clear();
            recent.Clear();
        }
        var parts = new List<(string, string?)>();
        if (palette.Count == 0)
        {
            parts.Add((text, null));
            return parts;
        }
        var last = 0;
        foreach (System.Text.RegularExpressions.Match match in Speaker.Matches(text))
        {
            var start = match.Index + match.Groups[1].Length;
            if (start > last) parts.Add((text[last..start], null));
            parts.Add((match.Groups[2].Value + ":", ColorFor(match.Groups[2].Value)));
            last = match.Index + match.Length;
        }
        if (last < text.Length) parts.Add((text[last..], null));
        return parts;
    }

    private string ColorFor(string name)
    {
        var key = name.ToUpperInvariant();
        if (!recent.Remove(key))
        {
            var free = palette.FirstOrDefault(c => !colors.ContainsValue(c));
            if (free is null)
            {
                var oldest = recent[0];
                recent.RemoveAt(0);
                free = colors[oldest];
                colors.Remove(oldest);
            }
            colors[key] = free;
        }
        recent.Add(key);
        return colors[key];
    }
}

/// <summary>Altyazıları TV'ye ileten taraf (gerçekte <see cref="SubtitleServer"/>).</summary>
public interface ISubtitleSender
{
    /// <param name="box">Oyundaki İngilizce altyazının ekrandaki yeri (kenarlara oranla); TV çeviriyi buna göre yerleştirir.</param>
    /// <param name="paragraphs">true ise metin satır satır, sola yaslı gösterilir (görev paneli gibi başlık ve maddeli yazılar).</param>
    /// <param name="replaces">Satır az önce gösterilenin uzamış ya da düzelmiş okumasıdır (<see cref="TextMatch.IsRevision"/>); TV onu öncekinin üstüne yığmaz.</param>
    void SendSubtitle(string text, int durationMs = 15000, TextBox? box = null, bool paragraphs = false, bool replaces = false);
    void Clear();
}

public enum SubtitleEventKind { Subtitle, Clear }

/// <summary>Sunucudan geçen ve bilgisayar ekranındaki altyazı penceresinin de gösterdiği olay.</summary>
/// <param name="Replaces">Satır öncekinin uzamış ya da düzelmiş okumasıdır; öncekinin üstüne yığılmaz, yerine geçer.</param>
public sealed record SubtitleEvent(SubtitleEventKind Kind, string Text = "", int DurationMs = 0, bool Paragraphs = false, bool Replaces = false)
{
    public static readonly SubtitleEvent Cleared = new(SubtitleEventKind.Clear);
}

/// <summary>
/// TV uygulamasının bağlandığı yerel Server-Sent Events sunucusu.
/// <c>GET /events</c> akışında <c>subtitle</c>, <c>clear</c> ve <c>style</c> olaylarını yayınlar.
/// </summary>
public sealed class SubtitleServer(int port = 8787, TimeSpan? pingInterval = null) : ISubtitleSender, IRegionSender, IDisposable
{
    private sealed class Client(TcpClient tcp, bool viewer = false)
    {
        public TcpClient Tcp { get; } = tcp;
        /// <summary>OBS katmanı gibi TV olmayan izleyici: olayları alır ama "TV bağlı" sayısına katılmaz.</summary>
        public bool Viewer { get; } = viewer;
        public Stream Stream { get; } = tcp.GetStream();
        /// <summary>Aynı bağlantıya iki yazma iç içe geçmesin.</summary>
        public SemaphoreSlim WriteLock { get; } = new(1, 1);
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        // Türkçe harfler ı gibi kaçışlarla değil olduğu gibi gönderilir.
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    /// <summary>
    /// Bağlantı canlı tutma çerçevesi: yorum satırını EventSource görmez; adlı "ping" olayını TV görür ve
    /// bir süre gelmezse yarı açık kalmış bağlantıyı kapatıp yeniden bağlanır (Mac ile aynı).
    /// </summary>
    internal const string PingFrame = ": ping\n\nevent: ping\ndata: {}\n\n";

    private readonly object gate = new();
    private readonly StableCover stableCover = new();
    /// <summary>Oyunun altyazısının gerçek yeri (<c>source</c>) için ayrı zıplama önleyici: zemin satırdan satıra kaymasın.</summary>
    private readonly StableCover stableSource = new();
    /// <summary>OBS'in görünümü TV'den farklıysa (ayrı yerleşim ya da boy) onun örtüsü ayrı izlenir.</summary>
    private readonly StableCover obsCover = new();
    private readonly StableCover obsSource = new();
    private readonly List<Client> clients = [];
    private TcpListener? listener;
    private Timer? pingTimer;
    private SubtitleStyle? lastStyle;
    /// <summary>OBS katmanı ve ikinci ekran gibi izleyicilerin görünümü (ayrı ayar yoksa TV'ninkiyle aynı).</summary>
    private SubtitleStyle? lastViewerStyle;
    /// <summary>GET ile verilen dosyalar: dublaj sesleri ve seslendirme modelinin ses örnekleri.</summary>
    private readonly Dictionary<string, (byte[] Data, string Type)> files = new(StringComparer.Ordinal);
    private readonly Queue<string> speechPaths = new();
    private int speechNumber;
    /// <summary>Font adı → sunucudaki adresi (null: bilgisayarda bulunamadı). Bir kez aranır.</summary>
    private readonly Dictionary<string, string?> fontPaths = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>
    /// Font adından bilgisayarda kurulu font dosyasını bulur (Windows uygulaması verir). Bulunan dosya
    /// <c>/font/...</c> adresinden verilir; TV, OBS katmanı ve ikinci ekran <c>style</c> olayındaki <c>fontUrl</c>'den yükler.
    /// </summary>
    public Func<string, FontFile?>? FindFont { get; set; }

    /// <summary>Font dosyası sunucudaki adrese konur (adres font adından türer; aynı font için aynı kalır).</summary>
    private string? FontPath(string family)
    {
        lock (gate)
        {
            if (fontPaths.TryGetValue(family, out var known)) return known;
        }
        // Gömülü font önce (her yerde aynı dosya), sonra bilgisayarda kurulu olan
        var file = SubtitleFonts.Embedded(family);
        try { file ??= FindFont?.Invoke(family); } catch { }
        string? path = null;
        if (file is { Data.Length: > 0 })
        {
            var hash = Convert.ToHexString(System.Security.Cryptography.SHA1.HashData(file.Data))[..12].ToLowerInvariant();
            var extension = file.Type.Contains("otf", StringComparison.OrdinalIgnoreCase) || file.Type.Contains("opentype", StringComparison.OrdinalIgnoreCase) ? "otf" : "ttf";
            path = $"/font/{hash}.{extension}";
        }
        lock (gate)
        {
            if (path is not null) files[path] = (file!.Data, file.Type);
            fontPaths[family] = path;
        }
        return path;
    }

    /// <summary>Kurulan ya da kaldırılan font yeniden aransın (font menüsü açılınca).</summary>
    public void ForgetFonts()
    {
        lock (gate) fontPaths.Clear();
    }

    /// <summary>Bağlı TV sayısı değişince çağrılır (iş parçacığı havuzunda).</summary>
    public event Action<int>? ClientsChanged;

    /// <summary>
    /// Altyazı ve temizleme olayları gönderildiği anda buraya da verilir (çağıranın iş parçacığında). Bilgisayar
    /// ekranındaki pencere TV'ye gidenin aynısını gösterir; sunucu başlamamış olsa da çalışır.
    /// </summary>
    public event Action<SubtitleEvent>? EventSent;

    /// <summary>false ise altyazı olayları TV'lere gitmez (altyazı yalnız bilgisayar ekranında); OBS katmanı almaya devam eder.</summary>
    public bool SendsToTv { get; set; } = true;

    /// <summary>Son bağlanan veya /hello sorgulayan istemcinin IP adresi (TV adresi).</summary>
    public string? LastClientAddress { get; private set; }

    public int ConnectedClients
    {
        get { lock (gate) return TvCount(); }
    }

    private int TvCount() => clients.Count(c => !c.Viewer);

    /// <summary>OBS'de "Tarayıcı kaynağı" olarak eklenen altyazı katmanı (<c>GET /obs</c>; Mac ile aynı dosya).</summary>
    public static byte[] ObsOverlay { get; } = LoadPage("obs-overlay.html");

    /// <summary>Sayfanın adresteki yolu: aynı Wi-Fi'deki bir cihaz <c>http://bilgisayar:port/ekran</c> adresini açar.</summary>
    public const string SecondScreenPath = "/ekran";

    /// <summary>
    /// İkinci ekran sayfası (<c>GET /ekran</c>): TV uygulaması kurulamayan TV'lerde çeviri bir telefonun, tabletin ya da
    /// başka bir bilgisayarın tarayıcısında büyük yazıyla gösterilir. Sayfa uygulamanın içindedir; internete çıkılmaz.
    /// </summary>
    public static byte[] SecondScreen { get; } = LoadPage("second-screen.html");

    private static byte[] LoadPage(string name)
    {
        using var stream = typeof(SubtitleServer).Assembly.GetManifestResourceStream(name);
        if (stream is null) return [];
        using var memory = new MemoryStream();
        stream.CopyTo(memory);
        return memory.ToArray();
    }

    /// <summary>Sunucuyu başlatır ve dinlenen portu döner (<c>port: 0</c> ise rastgele seçilen port).</summary>
    public Task<int> StartAsync()
    {
        var listener = new TcpListener(IPAddress.Any, port);
        listener.Start();
        this.listener = listener;
        _ = AcceptLoop(listener);
        var every = pingInterval ?? TimeSpan.FromSeconds(15);
        pingTimer = new Timer(_ => Broadcast(PingFrame), null, every, every);
        return Task.FromResult(((IPEndPoint)listener.LocalEndpoint).Port);
    }

    public void Stop()
    {
        pingTimer?.Dispose();
        pingTimer = null;
        listener?.Stop();
        listener = null;
        List<Client> closing;
        lock (gate)
        {
            closing = [.. clients];
            clients.Clear();
        }
        foreach (var client in closing) client.Tcp.Dispose();
    }

    public void Dispose() => Stop();

    /// <summary>
    /// Çeviri şu an çalışıyor mu. /hello yanıtında TV'ye bildirilir: ağda birden çok GameTranslator varsa (bilgisayar
    /// açık dururken telefonla çeviri) TV uygulaması (1.0.6+) çeviri yapana bağlanır.
    /// </summary>
    public bool Active { get; set; }

    internal static string HelloBody(bool active) =>
        "{\"active\":" + (active ? "true" : "false") + ",\"app\":\"gametranslator\",\"name\":\"GameTranslator\",\"type\":\"gametrans\",\"legacy\":\"couchsubs\",\"product\":\"couchsubs\"}";

    /// <param name="paragraphs">true ise metin satır satır, sola yaslı gösterilir (görev paneli gibi başlık ve maddeli yazılar).</param>
    /// <param name="replaces">TV satırı öncekinin üstüne yığmaz, yerine koyar (<c>replace</c> alanı; eski TV uygulamaları yok sayar).</param>
    public void SendSubtitle(string text, int durationMs = 15000, TextBox? box = null, bool paragraphs = false, bool replaces = false)
    {
        var json = new SortedDictionary<string, object>
        {
            ["at"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            ["duration"] = durationMs,
            ["text"] = text,
        };
        if (paragraphs) json["paragraphs"] = true;
        if (replaces) json["replace"] = true;
        SubtitleStyle? style, viewerStyle;
        lock (gate) (style, viewerStyle) = (lastStyle, lastViewerStyle ?? lastStyle);
        var tvFrame = Frame("subtitle", Placed(json, text, box, style, paragraphs, replaces, stableCover, stableSource));
        // OBS'in görünümü TV'ninkiyle aynıysa aynı çerçeve gider; değilse örtü OBS'in yazı boyuna ve yerleşimine göre
        var viewerFrame = box is null || viewerStyle == style
            ? tvFrame
            : Frame("subtitle", Placed(json, text, box, viewerStyle, paragraphs, replaces, obsCover, obsSource));
        Broadcast(tvFrame, viewerFrame);
        EventSent?.Invoke(new SubtitleEvent(SubtitleEventKind.Subtitle, text, durationMs, paragraphs, replaces));
    }

    /// <summary>
    /// Satırın yeri: örtme yerleşiminde zemin tek parça olsun diye kutu çevirinin sığacağı kadar büyütülür (StableCover:
    /// satırdan satıra zıplamasın); <c>source</c> oyunun altyazısının gerçek yeridir (TV 1.0.5+ çeviriyi oraya sığdırır).
    /// </summary>
    private static SortedDictionary<string, object> Placed(SortedDictionary<string, object> json, string text, TextBox? box,
        SubtitleStyle? style, bool paragraphs, bool replaces, StableCover cover, StableCover source)
    {
        var placed = new SortedDictionary<string, object>(json);
        if (box is not { } b) return placed;
        var covers = style is { Placement: SubtitlePlacement.Cover } && !paragraphs;
        placed["box"] = BoxJson(covers ? cover.Next(CoverFit.Box(b, text, style!.FontSize), newLine: !replaces) : b);
        if (covers) placed["source"] = BoxJson(source.Next(b, newLine: !replaces));
        return placed;
    }

    /// <summary>Dört basamağa yuvarlanır (decimal: JSON'da 0.8 olarak yazılsın).</summary>
    /// Bölme sondaki sıfırları atar (0.8750 → 0.875).
    private static decimal Round(double value) =>
        Math.Round((decimal)Math.Clamp(value, 0, 1), 4) / 1.000000000000000000000000000000000m;

    public void Clear()
    {
        Broadcast(Frame("clear", new SortedDictionary<string, object>()), subtitle: true);
        EventSent?.Invoke(SubtitleEvent.Cleared);
    }

    /// <summary>
    /// "Ekranı çevir" sonuçları: <c>screen</c> olayı her çeviriyi yazının yeri (<c>box</c>), tipik satır yüksekliği
    /// (<c>line</c>, ekrana oranla) ve çok parçalıysa <c>paragraphs</c> ile taşır. TV öncekinin yerine bunları koyar.
    /// </summary>
    public void SendScreen(IReadOnlyList<ScreenTranslation.Item> items, int durationMs)
    {
        var list = items.Select(item =>
        {
            var json = new SortedDictionary<string, object>
            {
                ["box"] = BoxJson(item.Box),
                ["line"] = Round(item.LineHeight),
                ["text"] = item.Translation,
            };
            if (item.Paragraphs) json["paragraphs"] = true;
            return json;
        }).ToList();
        Broadcast(Frame("screen", new SortedDictionary<string, object> { ["duration"] = durationMs, ["items"] = list }), subtitle: true);
    }

    /// <summary>TV'deki "ekranı çevir" çevirilerini kaldırır (altyazıya dokunmaz).</summary>
    public void ClearScreen() => Broadcast(Frame("screen-clear", new SortedDictionary<string, object>()), subtitle: true);

    /// <summary>
    /// Sürekli okunan ek alanın çevirisi: <c>region</c> olayı alanın kimliğini (<c>id</c>), çeviriyi (<c>text</c>; boşsa
    /// kutu kalkar), yazının karedeki yerini (<c>box</c>) ve satır yüksekliğini (<c>line</c>) taşır. TV çeviriyi o yerde
    /// küçük bir kutuda gösterir; ana altyazıya dokunmaz. Olayı tanımayan eski TV uygulamaları yok sayar (CouchSubs ile aynı).
    /// </summary>
    public void SendRegion(string id, string text, TextBox? box, double? lineHeight = null)
    {
        var json = new SortedDictionary<string, object> { ["id"] = id, ["text"] = text };
        if (box is { } b) json["box"] = BoxJson(b);
        if (lineHeight is { } line) json["line"] = Round(line);
        Broadcast(Frame("region", json), subtitle: true);
    }

    /// <summary>Bütün ek alan kutularını kaldırır.</summary>
    public void ClearRegions() => Broadcast(Frame("region-clear", new SortedDictionary<string, object>()), subtitle: true);

    private static SortedDictionary<string, object> BoxJson(TextBox b) => new()
    {
        ["bottom"] = Round(b.Bottom), ["left"] = Round(b.Left), ["right"] = Round(b.Right), ["top"] = Round(b.Top),
    };

    /// <summary>Dosyayı <paramref name="path"/> adresinden verir ("/voices/male_1.wav").</summary>
    public void Publish(byte[] data, string path, string type = "audio/wav")
    {
        lock (gate) files[path] = (data, type);
    }

    /// <summary>
    /// Dublaj sesini TV'ye gönderir: ses <c>/speech/&lt;n&gt;.wav</c> adresinden verilir, <c>speech</c> olayı adresi
    /// bildirir. Son 16 ses tutulur.
    /// </summary>
    public void SendSpeech(byte[] wav, int durationMs)
    {
        string path;
        lock (gate)
        {
            path = $"/speech/{++speechNumber}.wav";
            files[path] = (wav, "audio/wav");
            speechPaths.Enqueue(path);
            while (speechPaths.Count > 16) files.Remove(speechPaths.Dequeue());
        }
        Broadcast(Frame("speech", new SortedDictionary<string, object> { ["duration"] = durationMs, ["url"] = path }));
    }

    /// <summary>TV'de çalan dublajı durdurur.</summary>
    public void StopSpeech() => Broadcast(Frame("speech-stop", new SortedDictionary<string, object>()));

    /// <param name="viewerStyle">OBS katmanının ayrı görünümü; null ise TV'ninkiyle aynı.</param>
    public void SendStyle(SubtitleStyle style, SubtitleStyle? viewerStyle = null)
    {
        lock (gate) (lastStyle, lastViewerStyle) = (style, viewerStyle);
        var frame = StyleFrame(style);
        Broadcast(frame, viewerStyle is null || viewerStyle == style ? frame : StyleFrame(viewerStyle));
    }

    // --- Bağlantılar ---

    private async Task AcceptLoop(TcpListener listener)
    {
        while (true)
        {
            TcpClient tcp;
            try { tcp = await listener.AcceptTcpClientAsync(); }
            catch { return; }
            _ = Serve(tcp);
        }
    }

    private async Task Serve(TcpClient tcp)
    {
        try
        {
            EnableKeepAlive(tcp.Client);
            // Altyazı çerçeveleri küçüktür; Nagle beklemesi her satırı TV'ye geç ulaştırmasın.
            tcp.NoDelay = true;
            var stream = tcp.GetStream();
            var requestLine = await ReadRequestLine(stream);
            var parts = requestLine?.Split(' ') ?? [];
            var method = parts.Length > 0 ? parts[0] : "";
            var target = parts.Length > 1 ? parts[1] : "";
            var path = target.Split('?')[0];

            if (method == "OPTIONS")
            {
                var optionsHead = "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\nConnection: close\r\n\r\n";
                await tcp.GetStream().WriteAsync(Encoding.ASCII.GetBytes(optionsHead));
                tcp.Dispose();
            }
            else if (method == "GET" && path == "/events") await OpenStream(tcp, target.Contains("viewer=obs", StringComparison.Ordinal));
            else if (method == "GET" && path == "/obs") await RespondAndClose(tcp, ObsOverlay, "text/html; charset=utf-8");
            // "/screen": adresi elle yazanlar için İngilizce eş ad
            else if (method == "GET" && (path == SecondScreenPath || path == "/screen")) await RespondAndClose(tcp, SecondScreen, "text/html; charset=utf-8");
            // TV uygulaması ağdaki bilgisayarı bu yanıtla tanır (Android TV hem gametranslator hem couchsubs arar).
            else if (method == "GET" && path == "/hello")
            {
                if (tcp.Client.RemoteEndPoint is IPEndPoint ep && ep.Address.ToString() is { } ip && ip != "127.0.0.1" && ip != "::1")
                {
                    LastClientAddress = ip;
                }
                await RespondAndClose(tcp, "200 OK", HelloBody(Active));
            }
            else if (method == "GET" && FindFile(path) is { } file) await RespondAndClose(tcp, file.Data, file.Type);
            else await RespondAndClose(tcp, "404 Not Found");
        }
        catch
        {
            tcp.Dispose();
        }
    }

    /// <summary>Başlıkların sonuna (boş satır) kadar okur, ilk satırı döner. 64 KB'yi aşan istek kapatılır.</summary>
    private static async Task<string?> ReadRequestLine(Stream stream)
    {
        var buffer = new byte[8192];
        using var head = new MemoryStream();
        while (head.Length < 65536)
        {
            var count = await stream.ReadAsync(buffer);
            if (count == 0) return null;
            head.Write(buffer, 0, count);
            var text = Encoding.ASCII.GetString(head.GetBuffer(), 0, (int)head.Length);
            var end = text.IndexOf("\r\n\r\n", StringComparison.Ordinal);
            if (end >= 0) return text[..text.IndexOf("\r\n", StringComparison.Ordinal)];
        }
        return null;
    }

    private (byte[] Data, string Type)? FindFile(string path)
    {
        lock (gate) return files.TryGetValue(path, out var file) ? file : null;
    }

    private static async Task RespondAndClose(TcpClient tcp, byte[] data, string type)
    {
        var head = $"HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\nContent-Type: {type}\r\nCache-Control: no-cache\r\n" +
                   $"Content-Length: {data.Length}\r\nConnection: close\r\n\r\n";
        var stream = tcp.GetStream();
        await stream.WriteAsync(Encoding.ASCII.GetBytes(head));
        await stream.WriteAsync(data);
        tcp.Dispose();
    }

    private static async Task RespondAndClose(TcpClient tcp, string status, string body = "")
    {
        var bodyBytes = Encoding.UTF8.GetBytes(body);
        var head = $"HTTP/1.1 {status}\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {bodyBytes.Length}\r\nConnection: close\r\n\r\n";
        var stream = tcp.GetStream();
        await stream.WriteAsync(Encoding.ASCII.GetBytes(head));
        if (bodyBytes.Length > 0)
        {
            await stream.WriteAsync(bodyBytes);
        }
        tcp.Dispose();
    }

    private async Task OpenStream(TcpClient tcp, bool viewer = false)
    {
        var head = new StringBuilder()
            .Append("HTTP/1.1 200 OK\r\n")
            .Append("Content-Type: text/event-stream; charset=utf-8\r\n")
            .Append("Cache-Control: no-cache, no-transform\r\n")
            .Append("Connection: keep-alive\r\n")
            .Append("Access-Control-Allow-Origin: *\r\n")
            .Append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            .Append("Access-Control-Allow-Headers: *\r\n\r\n")
            .Append("retry: 2000\n\n")
            .Append(": ping\n\n");
        var client = new Client(tcp, viewer);
        if (!viewer && tcp.Client.RemoteEndPoint is IPEndPoint ep && ep.Address.ToString() is { } ip && ip != "127.0.0.1" && ip != "::1")
        {
            LastClientAddress = ip;
        }
        int count;
        lock (gate)
        {
            if ((viewer ? lastViewerStyle ?? lastStyle : lastStyle) is { } style) head.Append(StyleFrame(style));
            clients.Add(client);
            count = TvCount();
        }
        await Write(client, head.ToString());
        ClientsChanged?.Invoke(count);
        await WatchForClose(client);
    }

    /// <summary>İstemci bağlantıyı kapattığında listeden çıkarır.</summary>
    private async Task WatchForClose(Client client)
    {
        var buffer = new byte[1024];
        try
        {
            while (await client.Stream.ReadAsync(buffer) > 0) { }
        }
        catch { }
        Remove(client);
    }

    private void Remove(Client client)
    {
        int count;
        lock (gate)
        {
            if (!clients.Remove(client)) return;
            count = TvCount();
        }
        client.Tcp.Dispose();
        ClientsChanged?.Invoke(count);
    }

    /// <summary>
    /// Gönderim hatası alan bağlantı kopmuş sayılır ve listeden çıkarılır. <paramref name="subtitle"/>: altyazı olayı;
    /// altyazı TV'ye gitmiyorsa yalnız izleyiciler (OBS) alır. Dublaj sesi ve ping her zaman herkese gider.
    /// </summary>
    /// <summary>Altyazı olayı: TV'ler <paramref name="tvFrame"/>'i (altyazı TV'ye gidiyorsa), izleyiciler (OBS) kendi çerçevesini alır.</summary>
    private void Broadcast(string tvFrame, string viewerFrame)
    {
        List<Client> targets;
        lock (gate) targets = [.. clients.Where(c => c.Viewer || SendsToTv)];
        foreach (var client in targets)
            _ = Write(client, client.Viewer ? viewerFrame : tvFrame).ContinueWith(t => Remove(client), TaskContinuationOptions.OnlyOnFaulted);
    }

    private void Broadcast(string frame, bool subtitle = false)
    {
        List<Client> targets;
        var toTvs = !subtitle || SendsToTv;
        lock (gate) targets = [.. clients.Where(c => toTvs || c.Viewer)];
        foreach (var client in targets)
            _ = Write(client, frame).ContinueWith(t => Remove(client), TaskContinuationOptions.OnlyOnFaulted);
    }

    private static async Task Write(Client client, string text)
    {
        await client.WriteLock.WaitAsync();
        try
        {
            await client.Stream.WriteAsync(Encoding.UTF8.GetBytes(text));
            await client.Stream.FlushAsync();
        }
        finally
        {
            client.WriteLock.Release();
        }
    }

    /// <summary>Sessizce kaybolan TV'ler (fişten çekme, Wi-Fi kopması) TCP canlılık denetimiyle fark edilir.</summary>
    private static void EnableKeepAlive(Socket socket)
    {
        try
        {
            socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
            socket.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveTime, 10);
            socket.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveInterval, 5);
            socket.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveRetryCount, 3);
        }
        catch (SocketException) { }
    }

    /// <summary>
    /// Örtme yerleşiminde TV iki zemin çizer: İngilizce altyazıyı örten kutu ve çevirinin kendi zemini. Boyları farklı
    /// olduğu için arkada iki ayrı çubuk görünüyordu. Çevirinin kendi zemini kapatılır (yalnız örtü kalır), önceki
    /// satır da üstüne yığılmaz; örtü kutusu çeviriye göre büyütülür (<see cref="CoverFit"/>).
    /// </summary>
    private string StyleFrame(SubtitleStyle style)
    {
        // Font: ekranlar fontUrl'deki dosyayı kendi adıyla yükler; dosya yoksa adı yine gönderilir (OBS aynı bilgisayarda
        // kuruluysa adla bulur). Boş: standart yazı. Eski TV uygulamaları bu alanları tanımaz, standart yazıda kalır.
        var family = SubtitleFonts.Family(style.Font);
        var frame = StyleJson(style);
        frame["fontFamily"] = family ?? "";
        frame["fontUrl"] = family is null ? "" : FontPath(family) ?? "";
        return Frame("style", frame);
    }

    private static SortedDictionary<string, object> StyleJson(SubtitleStyle style) =>
        new SortedDictionary<string, object>
        {
            ["background"] = style.Placement == SubtitlePlacement.Cover ? 0 : Math.Clamp(style.Background, 0, 100),
            ["bold"] = style.Bold,
            ["bottomPercent"] = style.BottomPercent,
            ["displayMode"] = style.DisplayMode == TvDisplayMode.Overlay ? "overlay" : "inApp",
            ["fontSize"] = style.FontSize,
            ["placement"] = style.Placement.ToString().ToLowerInvariant(),
            // Konuşmacı adının rengi ("" kapalı): yazı sarıysa ad açık mavi, değilse sarı yazılır (yeni TV uygulaması).
            ["speakerColor"] = !style.SpeakerColor ? "" : SpeakerColors.Palette(style.TextColor)[0],
            // Her konuşmacıya ayrı renk (yeni TV uygulaması, OBS, ikinci ekran); eski TV uygulaması yalnız speakerColor'ı kullanır
            ["speakerColors"] = style.SpeakerColor ? SpeakerColors.Palette(style.TextColor) : Array.Empty<string>(),
            // TV uygulaması rengi bu alandan okur (textColor'ı tanımıyordu: sarı da dahil hiçbir renk TV'ye geçmiyordu)
            ["color"] = style.TextColor.Hex(),
            ["stackPrevious"] = style.StackPrevious && style.Placement != SubtitlePlacement.Cover,
            ["textColor"] = style.TextColor.ToString().ToLowerInvariant(),
            // Sürekli okunan ek alanların görünümü (TV 1.0.12, OBS, bilgisayar): zemin koyuluğu, yazı boyu, yazı rengi.
            ["regionBackground"] = Math.Clamp(style.RegionBackground, 0, 100),
            ["regionScale"] = Math.Clamp(style.RegionTextScale, 70, 150),
            ["regionColor"] = style.RegionTextColor?.Hex() ?? "",
        };

    private static string Frame(string name, SortedDictionary<string, object> json) =>
        $"event: {name}\ndata: {JsonSerializer.Serialize(json, JsonOptions)}\n\n";
}

