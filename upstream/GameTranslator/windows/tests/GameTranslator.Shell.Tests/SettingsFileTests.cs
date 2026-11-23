// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.Json;
using System.Text.Json.Nodes;
using GameTranslator.Core;
using GameTranslator.Shell;
using Xunit;

namespace GameTranslator.Shell.Tests;

/// <summary>Güncellemede ve hatada kullanıcının hiçbir ayarı kaybolmamalı, değişmemeli.</summary>
public sealed class SettingsFileTests : IDisposable
{
    private readonly string folder = Path.Combine(Path.GetTempPath(), "gt-settings-" + Guid.NewGuid().ToString("N"));
    private string FilePath => Path.Combine(folder, "settings.json");

    public SettingsFileTests() => Directory.CreateDirectory(folder);
    public void Dispose() { try { Directory.Delete(folder, recursive: true); } catch (IOException) { } }

    /// <summary>1.7.x'in yazdığı gerçek biçimde, her alanı varsayılandan farklı bir ayar dosyası.</summary>
    private const string OldVersionFile = """
        {"Region":{"Top":0.7155947136563876,"Bottom":0.9647577092511013,"Left":0.2301262102351314,"Right":0.7603315026223222},
         "AutoRegion":false,"Engine":"DeepL","ExtraRegion1":{"Top":0.1,"Bottom":0.2,"Left":0.3,"Right":0.4},"ExtraRegion2":null,"ExtraRegion3":null,
         "ExtraTarget1":1,"ExtraTarget2":2,"ExtraTarget3":0,"FreeAreaTarget":2,
         "Style":{"FontSize":30,"BottomPercent":12,"Placement":"Above","Background":55,"Bold":true,"TextColor":"Yellow","DisplayMode":"Overlay","StackPrevious":false,"SpeakerColor":true},
         "PcBar":{"Opacity":40,"WidthPercent":80,"Padding":20,"Corner":6,"Border":true,"Box":{"Left":0.25625,"Top":0.742,"Width":0.35,"Height":0.069},
                  "Edge":1,"TextSize":160,"BarColor":"#0A0E17","Look":2},
         "SourceLanguage":"en","TargetLanguage":"de","UiLanguage":"tr",
         "Dubbing":{"Enabled":true,"Engine":"Natural","Output":"Tv","Cast":{"Narrator":"Male1","Partner":"Female1"}},
         "DubbingEnabled":true,"OnlineTranslation":true,"SharePool":false,"VideoSource":1,"CaptureCardDeviceName":"Elgato HD60 X",
         "DesktopWindowName":"device:Elgato HD60 X","ScreenIndex":1,"PcOverlay":true,"UseGpu":true,"XboxExternalWindow":true}
        """;

    [Fact]
    public void AnOlderVersionsFileIsReadAndSavedBackWithEveryValueUnchanged()
    {
        File.WriteAllText(FilePath, OldVersionFile);
        var store = new SettingsFile(FilePath);
        var settings = store.Load()!;
        store.Save(settings);

        var before = JsonNode.Parse(OldVersionFile)!.AsObject();
        var after = JsonNode.Parse(File.ReadAllText(FilePath))!.AsObject();
        // Eski dosyadaki her alan, kaydedilen dosyada aynı değerle durmalı (yeni alanlar eklenebilir, eskiler değişmez)
        // PcBar.Look kayıtlı bir seçim değil: zemin, kenar ve renk değerlerinden hesaplanır (yalnız bilgi için yazılır)
        foreach (var (path, value) in Leaves(before).Where(leaf => leaf.Path != "PcBar.Look"))
            Assert.True(JsonNode.DeepEquals(value, Find(after, path)), $"{path}: {value?.ToJsonString()} → {Find(after, path)?.ToJsonString()}");
        Assert.Equal(SubtitleTextColor.Yellow, settings.Style.TextColor);
        Assert.Null(settings.ObsStyle); // eski ayarda OBS'in ayrı görünümü yok: OBS TV'yle aynı kalır
    }

    [Fact]
    public void ButtonLayoutIsSavedByNameAndOldFilesStayAutomatic()
    {
        File.WriteAllText(FilePath, OldVersionFile);
        var store = new SettingsFile(FilePath);
        var old = store.Load()!;
        // Eski dosyada alan yok: Otomatik; yakalama kartında (VideoSource 1) Otomatik kalır, karar görüntüden verilir.
        Assert.Equal(ButtonLayout.Auto, old.ButtonLayout);
        Assert.Equal(ButtonLayout.Auto, old.EffectiveButtonLayout);
        store.Save(old with { ButtonLayout = ButtonLayout.Xbox });
        Assert.Contains("\"ButtonLayout\":\"Xbox\"", File.ReadAllText(FilePath).Replace(" ", ""));
        var loaded = new SettingsFile(FilePath).Load()!;
        Assert.Equal(ButtonLayout.Xbox, loaded.ButtonLayout);
        Assert.Equal(ButtonLayout.Xbox, (loaded with { VideoSource = VideoSourceType.RemotePlay }).EffectiveButtonLayout);
        Assert.Equal(ButtonLayout.PlayStation, (loaded with { ButtonLayout = ButtonLayout.Auto, VideoSource = VideoSourceType.RemotePlay }).EffectiveButtonLayout);
        Assert.Equal(ButtonLayout.Xbox, (loaded with { ButtonLayout = ButtonLayout.Auto, VideoSource = VideoSourceType.Xbox }).EffectiveButtonLayout);
    }

    [Fact]
    public void ATruncatedFileFallsBackToTheLastGoodCopyInsteadOfDefaults()
    {
        File.WriteAllText(FilePath, OldVersionFile);
        Assert.Equal("de", new SettingsFile(FilePath).Load()!.TargetLanguage); // sağlam açılış yedeği alır

        // Güncelleme yazma sırasında uygulamayı kapattı: dosya yarım
        File.WriteAllText(FilePath, OldVersionFile[..200]);
        var restored = new SettingsFile(FilePath).Load()!;

        Assert.Equal("de", restored.TargetLanguage);
        Assert.Equal(30, restored.Style.FontSize);
        Assert.Single(Directory.GetFiles(folder, "settings.json.broken-*")); // bozuk dosya silinmedi
    }

    [Fact]
    public void AnEmptyFileWithoutBackupKeepsTheBrokenCopy()
    {
        File.WriteAllText(FilePath, "");
        var settings = new SettingsFile(FilePath).Load();
        Assert.NotNull(settings);
        Assert.Single(Directory.GetFiles(folder, "settings.json.broken-*"));
    }

    [Fact]
    public void NoFileMeansFirstRun() => Assert.Null(new SettingsFile(FilePath).Load());

    [Fact]
    public void SavingLeavesNoTemporaryFileAndRoundTrips()
    {
        var store = new SettingsFile(FilePath);
        var settings = new UserSettings { TargetLanguage = "fr", ObsStyle = new SubtitleStyle(FontSize: 60, TextColor: SubtitleTextColor.Orange) };
        store.Save(settings);
        Assert.False(File.Exists(FilePath + ".tmp"));
        var loaded = new SettingsFile(FilePath).Load()!;
        Assert.Equal("fr", loaded.TargetLanguage);
        Assert.Equal(settings.ObsStyle, loaded.ObsStyle);
    }

    /// <summary>
    /// Sayı olarak kaydedilen seçeneklerin numaraları değişirse eski ayar dosyası başka seçenek olarak okunur (ör. yakalama
    /// kartı Masaüstü olur). Yeni seçenek yalnız sona eklenmeli.
    /// </summary>
    [Fact]
    public void NumbersOfSavedChoicesNeverChange()
    {
        Assert.Equal([0, 1, 2, 3], Enum.GetValues<VideoSourceType>().Select(v => (int)v));
        Assert.Equal(VideoSourceType.CaptureCard, (VideoSourceType)1);
        Assert.Equal(["Tv", "Pc", "Both"], Enum.GetNames<ResultTarget>());
        Assert.Equal(["Shadow", "Outline", "None"], Enum.GetNames<PcTextEdge>());
        Assert.Equal(["TextOnly", "Bar", "Cinema", "Framed"], Enum.GetNames<PcBarLook>());
    }

    private static IEnumerable<(string Path, JsonNode? Value)> Leaves(JsonObject node, string prefix = "")
    {
        foreach (var (key, value) in node)
        {
            if (value is JsonObject child) foreach (var leaf in Leaves(child, prefix + key + ".")) yield return leaf;
            else yield return (prefix + key, value);
        }
    }

    private static JsonNode? Find(JsonObject root, string path)
    {
        JsonNode? node = root;
        foreach (var part in path.Split('.')) node = node?[part];
        return node;
    }
}
