// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.Json;

namespace GameTranslator.Shell.Tests;

public class PcBarSettingsTests
{
    [Fact]
    public void OldSettingsFileWithoutBarGetsDefaults()
    {
        var settings = JsonSerializer.Deserialize<UserSettings>("""{"AutoRegion":true,"SourceLanguage":"en"}""")!;
        Assert.Equal(new PcBarSettings(), settings.PcBar);
    }

    [Fact]
    public void BarSurvivesSaveAndLoad()
    {
        var bar = new PcBarSettings(Opacity: 35, WidthPercent: 60, Padding: 4, Corner: 0, Border: false);
        var json = JsonSerializer.Serialize(new UserSettings { PcBar = bar });
        Assert.Equal(bar, JsonSerializer.Deserialize<UserSettings>(json)!.PcBar);
    }

    [Fact]
    public void PartlyWrittenBarKeepsDefaultsForTheRest()
    {
        var settings = JsonSerializer.Deserialize<UserSettings>("""{"PcBar":{"Opacity":0}}""")!;
        Assert.Equal(new PcBarSettings() with { Opacity = 0 }, settings.PcBar);
    }

    [Theory]
    [InlineData(-5, 0)]
    [InlineData(0, 0)]
    [InlineData(7, PcBarSettings.MinWidthPercent)]
    [InlineData(55, 55)]
    [InlineData(400, 100)]
    public void WidthBelowZeroMeansAutomaticAndTheRestIsClamped(int given, int expected) =>
        Assert.Equal(expected, new PcBarSettings(WidthPercent: given).Normalized().WidthPercent);

    /// Kullanıcının fareyle çizdiği kutu (yer + boyut) kaydedilip geri okunur; eski ayar dosyasında yoktur.
    [Fact]
    public void HandDrawnBoxSurvivesSaveAndLoad()
    {
        var bar = new PcBarSettings(Box: new PcBarBox(0.25, 0.8, 0.5, 0.09));
        var json = JsonSerializer.Serialize(new UserSettings { PcBar = bar });
        Assert.Equal(bar, JsonSerializer.Deserialize<UserSettings>(json)!.PcBar.Normalized());
        Assert.Null(JsonSerializer.Deserialize<UserSettings>("""{"PcBar":{"Opacity":50}}""")!.PcBar.Box);
    }

    [Fact]
    public void BoxOnASecondMonitorIsKept()
    {
        // Çalışma alanının dışına (soldaki ya da sağdaki ekrana) taşınmış kutu geçerlidir
        var box = new PcBarBox(-0.6, 0.1, 0.4, 0.08);
        Assert.Equal(box, new PcBarSettings(Box: box).Normalized().Box);
        Assert.Equal(1.3, new PcBarSettings(Box: box with { Left = 1.3 }).Normalized().Box!.Left);
    }

    [Theory]
    [InlineData(0.5, 0.5, 0.0, 0.1)]                    // genişlik yok
    [InlineData(0.5, 0.5, 0.3, -0.1)]                   // eksi yükseklik
    [InlineData(double.NaN, 0.5, 0.3, 0.1)]
    [InlineData(0.5, double.PositiveInfinity, 0.3, 0.1)]
    [InlineData(40, 0.5, 0.3, 0.1)]                     // ekranlardan çok uzakta
    public void BrokenBoxIsDropped(double left, double top, double width, double height) =>
        Assert.Null(new PcBarSettings(Box: new PcBarBox(left, top, width, height)).Normalized().Box);

    [Fact]
    public void BoxSizeIsKeptWithinUsableLimits()
    {
        var tiny = new PcBarSettings(Box: new PcBarBox(0.1, 0.1, 0.001, 0.001)).Normalized().Box!;
        Assert.Equal((PcBarBox.MinWidth, PcBarBox.MinHeight), (tiny.Width, tiny.Height));
        var huge = new PcBarSettings(Box: new PcBarBox(0, 0, 9, 9)).Normalized().Box!;
        Assert.Equal((1.5, 1.0), (huge.Width, huge.Height));
    }

    [Fact]
    public void OutOfRangeValuesAreClamped()
    {
        var bar = new PcBarSettings(Opacity: 250, Padding: -3, Corner: 99).Normalized();
        Assert.Equal((100, 0, 30), (bar.Opacity, bar.Padding, bar.Corner));
    }

    // --- Kişiselleştirme: hazır görünümler, yazı kenarı, yazı boyutu, bar rengi ---

    [Fact]
    public void BorderIsOffByDefaultButSavedChoiceIsKept()
    {
        Assert.False(new PcBarSettings().Border);
        // Çerçeveyi açık kaydetmiş kullanıcının ayarı değişmez
        var saved = System.Text.Json.JsonSerializer.Deserialize<UserSettings>("""{"PcBar":{"Opacity":90,"WidthPercent":0,"Padding":12,"Corner":12,"Border":true,"Box":null}}""")!;
        Assert.True(saved.PcBar.Border);
        // Yeni alanları olmayan eski dosya varsayılanlarla okunur
        Assert.Equal((PcTextEdge.Shadow, 100, "#0A0E17"), (saved.PcBar.Edge, saved.PcBar.TextSize, saved.PcBar.BarColor));
    }

    [Fact]
    public void TextOnlyLookHasNoBarAndNoBorder()
    {
        var bar = new PcBarSettings(Border: true).WithLook(PcBarLook.TextOnly);
        Assert.Equal((0, false, PcTextEdge.Outline), (bar.Opacity, bar.Border, bar.Edge));
        Assert.Equal(PcBarLook.TextOnly, bar.Look);
    }

    [Fact]
    public void LooksKeepPlaceWidthAndTextSize()
    {
        var box = new PcBarBox(0.2, 0.7, 0.5, 0.1);
        var mine = new PcBarSettings(WidthPercent: 60, Box: box, TextSize: 140);
        foreach (var look in Enum.GetValues<PcBarLook>())
        {
            var bar = mine.WithLook(look);
            Assert.Equal((60, box, 140), (bar.WidthPercent, bar.Box, bar.TextSize));
            Assert.Equal(look, bar.Look);
        }
        Assert.Equal("#000000", mine.WithLook(PcBarLook.Cinema).BarColor);
        Assert.True(mine.WithLook(PcBarLook.Framed).Border);
    }

    [Fact]
    public void HandTunedSettingsMatchNoLook()
    {
        Assert.Equal(PcBarLook.Bar, new PcBarSettings().Look);
        Assert.Null((new PcBarSettings() with { Opacity = 47 }).Look);
    }

    [Theory]
    [InlineData(0, 100)]
    [InlineData(-5, 100)]
    [InlineData(10, 50)]
    [InlineData(999, 200)]
    [InlineData(130, 130)]
    public void TextSizeIsKeptInRange(int given, int expected) =>
        Assert.Equal(expected, new PcBarSettings(TextSize: given).Normalized().TextSize);

    [Theory]
    [InlineData("#000000", 0, 0, 0)]
    [InlineData("#3a3f47", 0x3A, 0x3F, 0x47)]
    [InlineData("red", 0x0A, 0x0E, 0x17)]        // bozuk renk: varsayılan lacivert
    [InlineData("#12345", 0x0A, 0x0E, 0x17)]
    [InlineData(null, 0x0A, 0x0E, 0x17)]
    public void BarColorFallsBackToTheDefaultWhenBroken(string? given, byte r, byte g, byte b)
    {
        var bar = new PcBarSettings(BarColor: given!).Normalized();
        Assert.Equal((r, g, b), bar.BarRgb);
        Assert.StartsWith("#", bar.BarColor);
    }

    [Fact]
    public void NewChoicesSurviveSaveAndLoad()
    {
        var settings = new UserSettings { PcBar = new PcBarSettings(Edge: PcTextEdge.Outline, TextSize: 150, BarColor: "#000000") };
        var loaded = System.Text.Json.JsonSerializer.Deserialize<UserSettings>(System.Text.Json.JsonSerializer.Serialize(settings))!;
        Assert.Equal(settings.PcBar, loaded.PcBar);
    }
}
