// SPDX-License-Identifier: AGPL-3.0-only

using System.Globalization;

namespace GameTranslator.Core.Tests;

public class LanguagesTests
{
    [Fact]
    public void ListStartsWithTurkishAndHasNoDuplicates()
    {
        Assert.Equal("tr", Languages.Targets[0].Code);
        Assert.Equal(Languages.Targets.Count, Languages.Targets.Select(l => l.Code).Distinct().Count());
        Assert.All(Languages.Targets, l => Assert.False(string.IsNullOrWhiteSpace(l.NativeName) || string.IsNullOrWhiteSpace(l.EnglishName) || string.IsNullOrWhiteSpace(l.TurkishName)));
        Assert.Contains(Languages.Targets, l => l.Code == "ar");
        Assert.Contains(Languages.Targets, l => l.Code == "ja");
    }

    [Theory]
    [InlineData("tr", "tr")]
    [InlineData("TR", "tr")]
    [InlineData("zh-CN", "zh")]
    [InlineData("pt_BR", "pt")]
    [InlineData("iw", "he")]      // İbranicenin eski kodu
    [InlineData("in", "id")]      // Endonezcenin eski kodu
    [InlineData(" ja ", "ja")]
    public void FindAcceptsRegionAndLegacyCodes(string given, string expected) => Assert.Equal(expected, Languages.Find(given)!.Code);

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("xx")]
    [InlineData("klingon")]
    public void UnknownLanguageIsNotFoundAndFallsBackToTurkish(string? given)
    {
        Assert.Null(Languages.Find(given));
        Assert.Equal("tr", Languages.OrDefault(given).Code);
    }

    /// İlk kurulum: sistem dili listedeyse o, değilse İngilizce; Türkçe sistemde eskisi gibi Türkçe.
    [Theory]
    [InlineData("tr-TR", "tr")]
    [InlineData("ar-SA", "ar")]
    [InlineData("ja-JP", "ja")]
    [InlineData("pt-BR", "pt")]
    [InlineData("zh-CN", "zh")]
    [InlineData("en-US", "en")]
    [InlineData("fi-FI", "en")]   // listede yok
    public void FirstRunTargetFollowsTheSystemLanguage(string culture, string expected) =>
        Assert.Equal(expected, Languages.DefaultTarget(CultureInfo.GetCultureInfo(culture)));

    [Fact]
    public void RightToLeftLanguagesAreMarked()
    {
        Assert.Equal(["ar", "fa", "he"], Languages.Targets.Where(l => l.Rtl).Select(l => l.Code).Order());
    }

    [Theory]
    [InlineData("لنذهب الآن.", true)]                         // Arapça
    [InlineData("בוא נלך עכשיו.", true)]                      // İbranice
    [InlineData("بیا برویم.", true)]                           // Farsça
    [InlineData("MARKOS: لنذهب الآن، لا وقت لدينا.", true)]   // Latin konuşmacı adı + Arapça söz
    [InlineData("لنذهب إلى PS5 الآن.", true)]                  // içinde Latin sözcük
    [InlineData("Öyleyse anlaştık.", false)]
    [InlineData("行こう。", false)]                             // Japonca soldan sağa
    [InlineData("OK!", false)]
    [InlineData("123 ...", false)]
    [InlineData("", false)]
    [InlineData(null, false)]
    public void TextDirectionComesFromTheText(string? text, bool rtl) => Assert.Equal(rtl, Languages.IsRtlText(text));

    [Fact]
    public void LabelShowsNativeNameAndTheNameInTheUiLanguage()
    {
        var japanese = Languages.Find("ja")!;
        Assert.Equal("日本語 · Japonca", japanese.Label("tr"));
        Assert.Equal("日本語 · Japanese", japanese.Label("en"));
        // Aynı ad iki kez yazılmaz
        Assert.Equal("Türkçe", Languages.Find("tr")!.Label("tr"));
        Assert.Equal("English", Languages.Find("en")!.Label("en"));
    }

    [Fact]
    public void EngineCodesDifferWhereTheServiceExpectsThem()
    {
        Assert.Equal("zh-CN", Languages.Find("zh")!.GoogleCode);
        Assert.Equal("iw", Languages.Find("he")!.GoogleCode);
        Assert.Equal("ar", Languages.Find("ar")!.GoogleCode);
        Assert.Equal("PT-BR", Languages.Find("pt")!.DeepL);
        Assert.Equal("EN-US", Languages.Find("en")!.DeepL);
        Assert.Equal("ZH-HANS", Languages.Find("zh")!.DeepL);
        // Telefondaki listeyle aynı: 27 dilin hepsinin DeepL kodu var
        Assert.All(Languages.Targets, language => Assert.False(string.IsNullOrEmpty(language.DeepL)));
        Assert.Equal("HI", Languages.Find("hi")!.DeepL);
        Assert.Equal("AZ", Languages.Find("az")!.DeepL);
    }
}
