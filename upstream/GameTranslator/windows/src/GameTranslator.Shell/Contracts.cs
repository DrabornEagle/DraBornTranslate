// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.Json.Serialization;
using GameTranslator.Core;
using GameTranslator.Core.LgTv;
using GameTranslator.RemotePlay;

namespace GameTranslator.Shell;

public static class StoreKeys
{
    /// <summary>Microsoft (Xbox) oturum jetonu; şifreli depoda durur, parola hiç görülmez.</summary>
    public const string XboxToken = "xboxToken";
    /// <summary>Seçilen Xbox konsolu.</summary>
    public const string XboxConsole = "xboxConsole";
    public const string AccountId = "accountId";
    public const string Console = "console";
    public const string Tv = "tv";
    public const string TvKey = "tvKey";
    public const string TvDevKey = "tvDevKey";
    /// <summary>Samsung ve Android TV'ye GameTranslator'ın kurduğu sürüm (LG'de sürüm TV'den okunur).</summary>
    public const string TvAppVersion = "tvAppVersion";
    /// <summary>LG geliştirici modu süresinin son uzatıldığı an.</summary>
    public const string DevSessionRenewed = "devSessionRenewed";
    /// <summary>Kullanıcının DeepL anahtarı (isteğe bağlı çevrimiçi çeviri).</summary>
    public const string DeepLKey = "deepLKey";
    /// <summary>Kullanıcının Google Gemini API anahtarı.</summary>
    public const string GeminiKey = "geminiKey";
}

/// <summary>Kullanıcının seçtiği çeviri motoru/modeli.</summary>
[JsonConverter(typeof(JsonStringEnumConverter<TranslationEngine>))]
public enum TranslationEngine
{
    /// <summary>Cihaz üstü donanım hızlandırmalı model (DirectML / NPU / CPU).</summary>
    DirectML,
    /// <summary>Google Gemini 2.5 / 2.0 Flash (Bulut Yapay Zeka).</summary>
    Gemini,
    /// <summary>DeepL REST API servisi.</summary>
    DeepL
}

/// <summary>Kalıcı ayarlar (şifreli depo değil, sıradan ayar dosyası).</summary>
public sealed record UserSettings
{
    public CropRegion Region { get; init; } = new();
    /// <summary>
    /// Altyazının yeri kendiliğinden bulunur (<see cref="SubtitleFinder"/>); <see cref="Region"/> yalnız kapalıyken
    /// kullanılır. Altyazıyı alışılmadık yerde gösteren oyunlar için kapatılıp alan elle çizilebilir.
    /// </summary>
    public bool AutoRegion { get; init; } = true;
    /// <summary>Aktif çeviri motoru (DirectML, Gemini veya DeepL).</summary>
    public TranslationEngine Engine { get; init; } = TranslationEngine.DirectML;
    /// <summary>Ek bölge 1 (ör. sağ üstteki görev paneli); null ise kapalı. Num 1 tuşuyla bir kez okunup çevrilir.</summary>
    public CropRegion? ExtraRegion1 { get; init; }
    /// <summary>Ek bölge 2 (ör. sağ alttaki bilgi kutusu); null ise kapalı. Num 2 tuşuyla bir kez okunup çevrilir.</summary>
    public CropRegion? ExtraRegion2 { get; init; }
    /// <summary>Ek bölge 3 (ör. ekranın ortası); null ise kapalı. Num 3 tuşuyla bir kez okunup çevrilir.</summary>
    public CropRegion? ExtraRegion3 { get; init; }
    /// <summary>Ek bölgelerin çevirisi nerede gösterilir (TV'de, bilgisayarda ya da ikisinde de).</summary>
    public ResultTarget ExtraTarget1 { get; init; } = ResultTarget.Tv;
    public ResultTarget ExtraTarget2 { get; init; } = ResultTarget.Tv;
    public ResultTarget ExtraTarget3 { get; init; } = ResultTarget.Tv;
    /// <summary>Ek bölge tuşa basmadan da sürekli okunur mu (görev yazısı, telefon mesajı, bildirim).</summary>
    public bool ExtraLive1 { get; init; }
    public bool ExtraLive2 { get; init; }
    public bool ExtraLive3 { get; init; }
    /// <summary>Serbest alan çevirisinin (Num 5) sonucu nerede gösterilir.</summary>
    public ResultTarget FreeAreaTarget { get; init; } = ResultTarget.Tv;
    public SubtitleStyle Style { get; init; } = new();
    /// <summary>OBS katmanının ayrı görünümü; null ise TV'ninkiyle aynıdır (eski ayarlarda alan yoktur: görünüm değişmez).</summary>
    public SubtitleStyle? ObsStyle { get; init; }
    /// <summary>Bilgisayar ekranında gösterilen altyazının arkasındaki barın görünümü (TV'ye gitmez).</summary>
    public PcBarSettings PcBar { get; init; } = new();

    /// <summary>
    /// Bilgisayar ekranında ek alan kutularının elle taşındığı yer: alan kimliği ("area1") → oyundaki yazının üstünden
    /// kayma, ekrana oranla. Boşsa kutular İngilizce yazının üstünü örter (varsayılan); "Eski yerine al" bunu siler.
    /// </summary>
    public IReadOnlyDictionary<string, PcOffset>? PcRegionOffsets { get; init; }
    public string SourceLanguage { get; init; } = "en";
    /// <summary>Çeviri dili (<see cref="Languages.Targets"/>). Yeni kurulumda sistem diline göre seçilir; eski ayar dosyası Türkçe kalır.</summary>
    public string TargetLanguage { get; init; } = "tr";
    /// <summary>Uygulamanın arayüz dili: "auto" (Windows'un diline göre), "tr" ya da "en". Değişiklik yeniden açılışta geçerli olur.</summary>
    public string UiLanguage { get; init; } = "auto";
    public DubbingSettings Dubbing { get; init; } = new();
    public bool DubbingEnabled
    {
        get => Dubbing.Enabled;
        init => Dubbing = (Dubbing ?? new DubbingSettings()) with { Enabled = value };
    }
    /// <summary>Anahtar girildiyse çeviri DeepL'le yapılır; kapalıysa ya da DeepL'e ulaşılamazsa cihaz üstü model.</summary>
    public bool OnlineTranslation { get; init; }
    /// <summary>
    /// Hiçbir pakette bulunmayan İngilizce oyun satırları ortak havuza gönderilir (anonim, yalnız satır metni ve oyun adı;
    /// bkz. <see cref="LinePool"/>). Yeni oyun paketleri bunlardan hazırlanır. Ayarlardan kapatılabilir.
    /// </summary>
    public bool SharePool { get; init; } = LinePool.EnabledByDefault;
    /// <summary>Görüntü kaynağı: Remote Play (Chiaki arkada gizli), Yakalama Kartı (Capture Card), Masaüstü / Ekran / OBS.</summary>
    public VideoSourceType VideoSource { get; init; } = VideoSourceType.RemotePlay;
    public string? CaptureCardDeviceName { get; init; }
    public string? DesktopWindowName { get; init; }
    public int ScreenIndex { get; init; } = 0;
    /// <summary>
    /// Bilgisayar ekranındaki altyazı katmanı açık mı. Eskiden kaydedilmiyordu, her açılışta kapalı başlıyordu: bilgisayar
    /// ekranında oynayan çeviriyi hiç görmüyordu. null: hiç seçilmemiş; hedef "PC &amp; OBS" ise açık sayılır.
    /// </summary>
    public bool? PcOverlay { get; init; }
    /// <summary>Yerel çeviri ekran kartında (NVIDIA, CUDA) çalışsın; kütüphaneler hazır değilse işlemcide sürer.</summary>
    public bool UseGpu { get; init; }
    /// <summary>
    /// Xbox kaynağında görüntü uygulamanın kendi Xbox penceresinden değil, kullanıcının açtığı pencereden (Xbox
    /// uygulaması ya da tarayıcı) alınır. Varsayılan kapalı: uygulama akışı kendisi açar (PS5'teki gibi).
    /// </summary>
    public bool XboxExternalWindow { get; init; }
    /// <summary>
    /// Ek alanlarda tanınan tuş simgelerinin kumandası. Otomatik: Xbox kaynağında Xbox, Remote Play'de PlayStation;
    /// yakalama kartı ve masaüstünde ekrandaki simgelerin biçiminden (bkz. <see cref="ButtonLayoutVote"/>).
    /// </summary>
    public ButtonLayout ButtonLayout { get; init; } = ButtonLayout.Auto;

    /// <summary>Kaynağa göre çözülmüş tuş seçimi (Otomatik yakalama kartında / masaüstünde Otomatik kalır).</summary>
    public ButtonLayout EffectiveButtonLayout => ButtonLayoutVote.Resolve(ButtonLayout, VideoSource switch
    {
        VideoSourceType.Xbox => true,
        VideoSourceType.RemotePlay => false,
        _ => null,
    });

    /// <summary>Ek bölgenin sayısı; <see cref="ExtraRegion"/> 1..ExtraRegionCount arasında çalışır.</summary>
    public const int ExtraRegionCount = 3;

    /// <summary>1, 2 ya da 3 numaralı ek bölge; kapalıysa null.</summary>
    public CropRegion? ExtraRegion(int slot) => slot switch { 1 => ExtraRegion1, 2 => ExtraRegion2, 3 => ExtraRegion3, _ => null };

    public UserSettings WithExtraRegion(int slot, CropRegion? region) => slot switch
    {
        1 => this with { ExtraRegion1 = region },
        2 => this with { ExtraRegion2 = region },
        3 => this with { ExtraRegion3 = region },
        _ => this,
    };

    /// <summary>Ek bölgenin çevirisinin gösterileceği yer.</summary>
    public ResultTarget ExtraTarget(int slot) => slot switch { 1 => ExtraTarget1, 2 => ExtraTarget2, 3 => ExtraTarget3, _ => ResultTarget.Tv };

    public UserSettings WithExtraTarget(int slot, ResultTarget target) => slot switch
    {
        1 => this with { ExtraTarget1 = target },
        2 => this with { ExtraTarget2 = target },
        3 => this with { ExtraTarget3 = target },
        _ => this,
    };

    /// <summary>Ek bölge sürekli okunuyor mu.</summary>
    public bool ExtraLive(int slot) => slot switch { 1 => ExtraLive1, 2 => ExtraLive2, 3 => ExtraLive3, _ => false };

    public UserSettings WithExtraLive(int slot, bool live) => slot switch
    {
        1 => this with { ExtraLive1 = live },
        2 => this with { ExtraLive2 = live },
        3 => this with { ExtraLive3 = live },
        _ => this,
    };

    /// <summary>Hazır ek alan sürekli okunuyor mu (üç yuvadan birinde açık).</summary>
    public bool HasExtraPreset(ExtraAreaPreset preset) =>
        Enumerable.Range(1, ExtraRegionCount).Any(slot => ExtraLive(slot) && ExtraRegion(slot) == preset.Region());

    /// <summary>
    /// Hazır ek alanı açar ya da kapatır. Açarken boş (sürekli okunmayan) ilk yuva kullanılır; üç yuva da doluysa ayar
    /// değişmez (<see cref="HasExtraPreset"/> false kalır). Kapatınca yuvanın bölgesi kalır (Num tuşu yine kullanabilir).
    /// </summary>
    public UserSettings WithExtraPreset(ExtraAreaPreset preset, bool on)
    {
        var region = preset.Region();
        if (!on)
        {
            var result = this;
            for (var slot = 1; slot <= ExtraRegionCount; slot++)
                if (result.ExtraRegion(slot) == region) result = result.WithExtraLive(slot, false);
            return result;
        }
        if (HasExtraPreset(preset)) return this;
        var free = Enumerable.Range(1, ExtraRegionCount).Where(slot => !ExtraLive(slot))
            .OrderBy(slot => ExtraRegion(slot) == region ? 0 : 1).FirstOrDefault();
        return free == 0 ? this : WithExtraRegion(free, region).WithExtraLive(free, true);
    }

    /// <summary>
    /// GTA 6 için tek tıkla en iyi okuma: altyazının yeri kendiliğinden bulunur, üç ek alan sürekli okunur (alttaki görev
    /// yazısı, sol üstteki ipucu kutusu, sağ alttaki telefon mesajı).
    /// </summary>
    public UserSettings WithGta6Reading() => (this with { AutoRegion = true })
        .WithExtraRegion(1, ExtraAreaPreset.BottomObjective.Region()).WithExtraLive(1, true)
        .WithExtraRegion(2, ExtraAreaPreset.HelpBox.Region()).WithExtraLive(2, true)
        .WithExtraRegion(3, ExtraAreaPreset.PhoneMessage.Region()).WithExtraLive(3, true);

    /// <summary>Sürekli okunan ek bölgeler (açık ve "Sürekli oku" seçili olanlar).</summary>
    public IReadOnlyList<WatchedArea> WatchedAreas() =>
        [.. Enumerable.Range(1, ExtraRegionCount)
            .Where(ExtraLive)
            .Select(slot => ExtraRegion(slot) is { } region ? new WatchedArea("area" + slot, "Alan " + slot, region) : null)
            .OfType<WatchedArea>()];
}

/// <summary>Bilgisayar ekranında bir kutunun kendi yerinden kayması, ekrana oranla (sağa ve aşağı artı).</summary>
public sealed record PcOffset(double X, double Y);

/// <summary>
/// Bilgisayarda (şeffaf katman ve oyun penceresi) altyazının arkasındaki bar. Yerleşim ne olursa olsun buradaki
/// değerler geçerlidir; "İngilizcenin üstünü örtsün" seçiliyken de opaklık kullanıcının verdiği kadardır.
/// </summary>
/// <param name="Opacity">Barın koyuluğu, yüzde (0: bar yok, yalnız gölgeli yazı).</param>
/// <param name="WidthPercent">Barın genişliği, ekranın yüzdesi; 0 ise yazıya (örtmede İngilizce satıra) göre kendiliğinden.</param>
/// <param name="Padding">Yazının üstünde ve altında kalan boşluk, piksel; barın yüksekliğini belirler.</param>
/// <param name="Corner">Köşe yuvarlaklığı, piksel.</param>
/// <param name="Border">Barın çevresindeki ince mavi çizgi (varsayılan kapalı; kullanıcılar çerçevesiz istedi).</param>
/// <param name="Box">
/// Kullanıcının fareyle çizdiği yer ve boyut ("Altyazıyı Konumlandır"da kenarlardan çekerek). Doluysa şeffaf katmandaki
/// bar her satırda tam bu alanda çıkar (yazı içine sığmazsa aşağı yukarı eşit büyür); <paramref name="WidthPercent"/>
/// ve yerleşim ayarı o zaman kullanılmaz. Null: yer ve boyut kendiliğinden.
/// </param>
/// <param name="Edge">Yazının kenarı: gölge, kontur (barsız görünümde yazı her zeminde okunsun diye) ya da hiçbiri.</param>
/// <param name="TextSize">Bilgisayardaki yazının boyutu, TV için seçilen boyuta oranla yüzde (50–200).</param>
/// <param name="BarColor">Barın rengi (#RRGGBB); saydamlığı <paramref name="Opacity"/> verir.</param>
public sealed record PcBarSettings(int Opacity = 90, int WidthPercent = 0, int Padding = 12, int Corner = 12, bool Border = false, PcBarBox? Box = null,
    PcTextEdge Edge = PcTextEdge.Shadow, int TextSize = 100, string BarColor = PcBarSettings.DefaultBarColor)
{
    public const int MinWidthPercent = 20;
    public const string DefaultBarColor = "#0A0E17";

    /// <summary>Barın rengi (kırmızı, yeşil, mavi); bozuk değerde varsayılan lacivert.</summary>
    public (byte R, byte G, byte B) BarRgb => ParseColor(BarColor) ?? ParseColor(DefaultBarColor)!.Value;

    private static (byte, byte, byte)? ParseColor(string? text)
    {
        if (text is not { Length: 7 } || text[0] != '#') return null;
        return int.TryParse(text.AsSpan(1), System.Globalization.NumberStyles.HexNumber, System.Globalization.CultureInfo.InvariantCulture, out var value)
            ? ((byte)(value >> 16), (byte)(value >> 8), (byte)value)
            : null;
    }

    /// <summary>
    /// Hazır görünüm: yalnız görünümle ilgili değerleri değiştirir; yer, genişlik ve yazı boyutu kalır.
    /// </summary>
    public PcBarSettings WithLook(PcBarLook look) => look switch
    {
        // Yalnız yazı: bar ve çerçeve yok, yazı konturla okunur
        PcBarLook.TextOnly => this with { Opacity = 0, Border = false, Edge = PcTextEdge.Outline },
        PcBarLook.Bar => this with { Opacity = 90, Border = false, Edge = PcTextEdge.Shadow, Corner = 12, Padding = 12, BarColor = DefaultBarColor },
        // Film altyazısı gibi: köşesiz, ince, yarı saydam siyah şerit
        PcBarLook.Cinema => this with { Opacity = 60, Border = false, Edge = PcTextEdge.Shadow, Corner = 0, Padding = 6, BarColor = "#000000" },
        PcBarLook.Framed => this with { Opacity = 90, Border = true, Edge = PcTextEdge.Shadow, Corner = 12, Padding = 12, BarColor = DefaultBarColor },
        _ => this,
    };

    /// <summary>Ayarlar hazır görünümlerden birine denk geliyorsa o (arayüzde seçili gösterilir); elle değiştirildiyse null.</summary>
    public PcBarLook? Look
    {
        get
        {
            foreach (var look in Enum.GetValues<PcBarLook>())
                if (WithLook(look) == this) return look;
            return null;
        }
    }

    /// <summary>Elle düzenlenmiş ya da bozuk ayar dosyasındaki değerleri kullanılabilir aralığa çeker.</summary>
    public PcBarSettings Normalized() => this with
    {
        Opacity = Math.Clamp(Opacity, 0, 100),
        WidthPercent = WidthPercent <= 0 ? 0 : Math.Clamp(WidthPercent, MinWidthPercent, 100),
        Padding = Math.Clamp(Padding, 0, 60),
        Corner = Math.Clamp(Corner, 0, 30),
        Box = Box?.Normalized(),
        Edge = Enum.IsDefined(Edge) ? Edge : PcTextEdge.Shadow,
        TextSize = TextSize <= 0 ? 100 : Math.Clamp(TextSize, 50, 200),
        BarColor = ParseColor(BarColor) is null ? DefaultBarColor : BarColor.ToUpperInvariant(),
    };
}

/// <summary>Bilgisayardaki altyazı yazısının kenarı.</summary>
public enum PcTextEdge { Shadow, Outline, None }

/// <summary>Bilgisayardaki altyazının hazır görünümleri.</summary>
public enum PcBarLook { TextOnly, Bar, Cinema, Framed }

/// <summary>
/// Barın elle verilen yeri ve boyutu, hedef ekranın çalışma alanına oranla (0–1; ikinci monitöre taşınmışsa aralığın
/// dışına çıkabilir). Oran olarak saklanır ki çözünürlük değişince bar aynı yerde kalsın.
/// </summary>
public sealed record PcBarBox(double Left, double Top, double Width, double Height)
{
    public const double MinWidth = 0.06, MinHeight = 0.025;

    /// <summary>Sayı olmayan ya da anlamsız değerler atılır (null döner); boyut alt ve üst sınıra çekilir.</summary>
    public PcBarBox? Normalized()
    {
        if (!double.IsFinite(Left) || !double.IsFinite(Top) || !double.IsFinite(Width) || !double.IsFinite(Height)) return null;
        if (Width <= 0 || Height <= 0 || Math.Abs(Left) > 8 || Math.Abs(Top) > 8) return null;
        return this with { Width = Math.Clamp(Width, MinWidth, 1.5), Height = Math.Clamp(Height, MinHeight, 1.0) };
    }
}

/// <summary>Tek seferlik çevirinin sonucunun gösterileceği yer.</summary>
public enum ResultTarget { Tv, Pc, Both }

/// <summary>Görüntünün alınacağı video giriş kaynağı.</summary>
public enum VideoSourceType
{
    /// <summary>PlayStation 5 Remote Play (Arka planda gizli oturum).</summary>
    RemotePlay = 0,
    /// <summary>HDMI Video Yakalama Kartı (Elgato, Cam Link, USB Video Capture).</summary>
    CaptureCard = 1,
    /// <summary>Masaüstü / OBS Tam Ekran / Pencere Yakalama.</summary>
    DesktopScreen = 2,
    /// <summary>
    /// Xbox: oyun Xbox uygulamasında (uzaktan oynatma / bulut) ya da tarayıcıda (xbox.com/play) oynatılır; o pencere
    /// kendiliğinden bulunup yakalanır (bkz. <see cref="XboxWindow"/>). Eşleştirme gerekmez.
    /// </summary>
    Xbox = 3
}

/// <summary>Hesap kimliği, PS5 ve TV anahtarları (gerçekte DPAPI ile şifreli dosya).</summary>
public interface IAppStore
{
    void Save<T>(string key, T value);
    T? Load<T>(string key);
    void Remove(string key);
}

public interface ISettingsStore
{
    UserSettings Load();
    void Save(UserSettings settings);
}

/// <summary>Çeviri sürerken bilgisayarın boşta uykuya geçmesini engeller.</summary>
public interface ISleepGuard
{
    void Begin();
    void End();
}

/// <summary>TV uygulamasını açık tutan görevli (gerçekte TvKeeper).</summary>
public interface IKeeper
{
    void Start(TimeSpan interval);
    void Stop();
    void Reconnect();
}

/// <summary>Cihaz üstü çeviri modeli: kurulu mu, indir, çevirmen oluştur.</summary>
public interface ITranslationModel
{
    bool IsSupported(string source, string target);
    bool IsInstalled(string source, string target);
    Task DownloadAsync(string source, string target, IProgress<double> progress, CancellationToken cancellationToken);
    ITextTranslator Create(string source, string target);
    /// <summary>Yerel model ekran kartında çalışsın (destekleyen modelde; değişince çevirmen yeniden kurulur).</summary>
    bool UseGpu { get => false; set { } }
}

/// <summary>Dış dünyayla (PS5, PSN, TV, OCR, çeviri) konuşan her şey; testlerde sahtesi kullanılır.</summary>
/// <summary>Microsoft hesabındaki bir Xbox konsolu. <paramref name="StreamingEnabled"/>: konsolda uzak özellikler açık mı.</summary>
public sealed record XboxConsole(string Id, string Name, string Model, bool StreamingEnabled);

/// <summary>Cihaz koduyla Microsoft girişi: kullanıcı <paramref name="Url"/> adresinde <paramref name="Code"/> kodunu girer.</summary>
public sealed record XboxLoginChallenge(string Code, Uri Url, int ExpiresIn);

/// <summary>
/// Xbox ev yayını (konsoldan uzaktan oynatma) arka ucu: Microsoft girişi, konsol listesi, görüntü oturumu.
/// CouchSubs'tan (Xbox desteği, Beta) alındı; Windows'ta gömülü yardımcı programla çalışır.
/// </summary>
public interface IXboxService
{
    /// <summary>Jeton yenilendi; yenisi kaydedilmelidir.</summary>
    event Action<string>? TokenUpdated;
    /// <summary>Oturumu ve yardımcı programı kapatır (hesap unutulurken).</summary>
    void Clear();
    Task<XboxLoginChallenge> BeginLoginAsync();
    /// <summary>Kullanıcı kodu girene kadar bekler; oturum jetonunu döndürür.</summary>
    Task<string> CompleteLoginAsync();
    Task<IReadOnlyList<XboxConsole>> DiscoverAsync(string token);
    IStreamSession MakeStream(XboxConsole console, string token);
}

public interface IAppServices
{
    /// <summary>Xbox ev yayını; bu derlemede yoksa null.</summary>
    IXboxService? Xbox => null;
    Task<IReadOnlyList<ConsoleInfo>> DiscoverConsolesAsync();
    Task<byte[]> AccountIdFromRedirectAsync(Uri redirect);
    Task<RegisteredConsole> RegisterAsync(string host, byte[] accountId, uint pin);
    Task<IReadOnlyList<LgTv>> DiscoverTvsAsync();
    /// <summary>TV ile eşleşir ve TV uygulamasını sunucu adresiyle başlatmayı dener.</summary>
    Task<TvPairing> PairTvAsync(LgTv tv, string? key, string server);
    IStreamSession MakeStream(RegisteredConsole? console, byte[]? accountId, UserSettings? settings = null);
    /// <param name="appConnected">TV uygulaması bilgisayara bağlı mı (Samsung'da bağlıyken TV'ye dokunulmaz).</param>
    /// <param name="onMoved">TV ağda yeni bir adreste bulununca çağrılır.</param>
    /// <param name="onKey">TV bağlantıda yeni jeton verdi (Samsung); saklanır.</param>
    IKeeper MakeKeeper(LgTv tv, string? key, IReadOnlyDictionary<string, string> parameters, Func<bool> appConnected, Action<LgTv> onMoved,
                       Action<string> onKey);
    /// <summary>Uygulamayla gelen TV paketinin sürümü (geliştirici derlemesinde paket olmayabilir).</summary>
    string? BundledTvAppVersion { get; }
    Task<bool> IsDevModeOnAsync(string host);
    Task<string> FetchDevKeyAsync(string host, string passphrase);
    Task<string?> InstalledTvAppVersionAsync(string host, string devKey);
    Task InstallTvAppAsync(string host, string devKey);
    /// <summary>LG geliştirici modunun süresini baştan başlatır; kalan saati döner (bilinmiyorsa null).</summary>
    Task<int?> RenewDevSessionAsync(string host, string devKey);
    /// <summary>Samsung ve Android TV için uygulamayla gelen paket (geliştirici derlemesinde olmayabilir).</summary>
    BundledTvApp? BundledApp(TvBrand brand);
    Task<bool> IsSamsungDevModeOnAsync(string host);
    Task InstallSamsungAppAsync(string host, BundledTvApp app, Action<TvInstallEvent> onEvent);
    Task<bool> IsAndroidDebugOnAsync(string host);
    /// <summary>Ağda ADB'si açık cihazlar.</summary>
    Task<IReadOnlyList<string>> ScanAndroidTvsAsync();
    /// <summary>Eksikse ya da eskiyse kurar; TV'deki sürümü döner.</summary>
    Task<string> InstallAndroidAppAsync(string host, BundledTvApp app, Action<TvInstallEvent> onEvent);
    /// <summary>Oyun dili için yazı tanıyıcı; bilgisayarda hiç OCR dili yoksa null.</summary>
    ITextReader? MakeReader(string language);
    /// <summary>
    /// Altyazı şeridini okuyan tanıyıcı. Varsayılanı <see cref="MakeReader"/>; uygulama, güçlü okuyucunun modeli
    /// kuruluysa hızlı okuyucuyu onunla birleştirir (<see cref="HybridReader"/>). Karenin tamamını tarayan işler
    /// (altyazı yerini bulma, bölge çevirisi) hızlı okuyucuda kalır.
    /// </summary>
    ITextReader? MakeSubtitleReader(string language) => MakeReader(language);
    /// <summary>
    /// Oyun dilinin kendi yazı tanıma paketi yüklü mü. Değilse okuyucu sistemin diliyle (ör. Türkçe) okur ve İngilizce
    /// altyazı "thıs.messs" gibi bozulur; kullanıcıya paketi yüklemesi söylenir.
    /// </summary>
    bool HasOcrLanguage(string language) => true;
    ITranslationModel Translation { get; }
    /// <summary>DeepL çevirmeni; <paramref name="fallback"/> DeepL'e ulaşılamayan satırları çevirir. Bu derlemede yoksa null.</summary>
    ITextTranslator? MakeOnlineTranslator(string key, ITextTranslator? fallback) => null;
    /// <summary>DeepL çevirmeni, verilen dil çifti için; DeepL hedef dili desteklemiyorsa null (yerine yedek motor kullanılır).</summary>
    ITextTranslator? MakeOnlineTranslator(string key, ITextTranslator? fallback, string source, string target) =>
        source == "en" && target == "tr" ? MakeOnlineTranslator(key, fallback) : null;
    Task<OnlineKeyCheck> CheckOnlineKeyAsync(string key) => Task.FromResult(new OnlineKeyCheck(OnlineKeyState.Unreachable));
    /// <summary>Google Gemini çevirmeni; <paramref name="fallback"/> Gemini'ye ulaşılamayan satırları çevirir.</summary>
    ITextTranslator? MakeGeminiTranslator(string key, ITextTranslator? fallback) => null;
    /// <summary>Gemini çevirmeni, verilen dil çifti için.</summary>
    ITextTranslator? MakeGeminiTranslator(string key, ITextTranslator? fallback, string source, string target) =>
        source == "en" && target == "tr" ? MakeGeminiTranslator(key, fallback) : null;
    Task<OnlineKeyCheck> CheckGeminiKeyAsync(string key) => Task.FromResult(new OnlineKeyCheck(OnlineKeyState.Unreachable));
    /// <summary>Türkçe dublaj modeli; bu derlemede yoksa null.</summary>
    IDubbingModel? Dubbing => null;
    /// <summary>Hızlı dublaj: sistemin kendi Türkçe sesi (Windows'ta Microsoft Tolga); yoksa null.</summary>
    IDubbingModel? FastDubbing => null;
    /// <summary>Bilgisayardan ses çalma (dublaj).</summary>
    IAudioOutput? Audio => null;
    /// <summary>Uygulama güncellemesi; geliştirici derlemesinde null.</summary>
    IUpdater? Updater => null;
    /// <summary>Windows Güvenlik Duvarı izni; Windows dışında ve testlerde null (hiçbir şey önerilmez).</summary>
    IFirewall? Firewall => null;
    /// <summary>Ortak havuz (CouchSubs'ın havuz sunucusu); testlerde null (hiçbir şey gönderilmez).</summary>
    LinePool? Pool => null;
    /// <summary>Altyazı geçmişinin dosyası (%LOCALAPPDATA%\GameTranslator\history.json); testlerde null (diske yazılmaz).</summary>
    string? HistoryFile => null;
}

/// <param name="Launched">false ise TV uygulaması açılamadı; büyük olasılıkla TV'de kurulu değil.</param>
public sealed record TvPairing(string Key, bool Launched);

/// <param name="Path">Paket dosyası.</param>
public sealed record BundledTvApp(string Path, string Version);

public enum TvInstallEventKind { SignIn, ApproveOnTv, Progress }

/// <summary>Samsung ve Android TV kurulumu sırasında kullanıcıdan bir şey isteyen olay.</summary>
public sealed record TvInstallEvent(TvInstallEventKind Kind, Uri? Url = null);

/// <summary>
/// SignIn: Samsung sertifikası için tarayıcıda Samsung hesabıyla giriş bekleniyor. ApproveOnTv: Android TV ekranında
/// hata ayıklama izni bekleniyor.
/// </summary>
public enum TvAppKind { Unknown, Working, UpToDate, DevModeOff, NeedsPassphrase, Failed, SignIn, ApproveOnTv }

/// <summary>TV uygulamasının kurulum durumu.</summary>
public sealed record TvAppState(TvAppKind Kind, string? Version = null, DevModeError? Error = null, Uri? Url = null, string? Reason = null)
{
    public static readonly TvAppState Unknown = new(TvAppKind.Unknown);
}

/// <summary>Durum noktasının rengi: bağlı (yeşil), sorun var (turuncu), boşta (gri).</summary>
public enum StatusTone { Idle, Warning, Good }

public enum OnboardingStep { Welcome, SecondAccount, PsnLogin, Console, Tv, Translation, Test, Done }

public enum AppError { InvalidPin, InvalidLogin, MissingAccount, WrongAccount, RegistrationFailed, ConsoleNoAnswer, ConsoleInUse, ConsoleCrashed, ConsoleVersion }

/// <summary>Kullanıcıya gösterilecek, ne yapması gerektiğini söyleyen hata.</summary>
public sealed class AppException(AppError error, string message) : Exception(message)
{
    public AppError Error { get; } = error;

    public static string Describe(AppError error, Localizer text) => text[error switch
    {
        AppError.InvalidPin => "The code must be 8 digits.",
        AppError.InvalidLogin => "Sign-in did not complete. Please try again.",
        AppError.MissingAccount => "Sign in to PlayStation Network first.",
        AppError.WrongAccount => "The PS5 did not recognise this account. Get the code from the PS5 user signed in with the account you used in GameTranslator, or sign in again with that user's account.",
        AppError.ConsoleNoAnswer => "The PS5 did not answer. Make sure it is on (not in rest mode), on the same network as this computer, and that Remote Play is turned on, then try again.",
        AppError.ConsoleInUse => "Remote Play is in use on another device. Close it there and enter a new code.",
        AppError.ConsoleCrashed => "The PS5 Remote Play service stopped unexpectedly. Restart the PS5 and enter a new code.",
        AppError.ConsoleVersion => "This PS5 system version is not supported yet. Update GameTranslator and try again.",
        _ => "The PS5 refused the code. Open Pair Device again on the PS5 and enter the new code.",
    }];
}
