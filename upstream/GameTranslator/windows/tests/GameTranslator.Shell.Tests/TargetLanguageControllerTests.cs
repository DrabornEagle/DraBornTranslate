// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.Json;
using GameTranslator.Core;

namespace GameTranslator.Shell.Tests;

/// Çeviri dili Türkçe dışında seçildiğinde: Türkçeye özgü parçalar devre dışı, motorun o dili kapsayıp kapsamadığı belli.
public class TargetLanguageControllerTests
{
    private static AppController Make(string target, TranslationEngine engine = TranslationEngine.DirectML, string source = "en")
    {
        var settings = new MemorySettings();
        settings.Save(new UserSettings { SourceLanguage = source, TargetLanguage = target, Engine = engine });
        return AppControllerTests.Make(new FakeServices(), new MemoryStore(), settings);
    }

    [Fact]
    public void PackLanguageFollowsTheTargetLanguage()
    {
        Assert.Equal("tr", AppController.PackLanguageFor("en", "tr"));
        Assert.Equal("ar", AppController.PackLanguageFor("en", "ar"));
        Assert.Null(AppController.PackLanguageFor("en", "de"));
        Assert.Null(AppController.PackLanguageFor("ja", "tr"));
    }

    [Fact]
    public void ExistingSettingsFileKeepsTurkish()
    {
        // Eski sürümün ayar dosyasında çeviri dili ve arayüz dili yoktur
        var settings = JsonSerializer.Deserialize<UserSettings>("""{"SourceLanguage":"en"}""")!;
        Assert.Equal("tr", settings.TargetLanguage);
        Assert.Equal("auto", settings.UiLanguage);
    }

    [Fact]
    public void LanguageChoicesSurviveSaveAndLoad()
    {
        var json = JsonSerializer.Serialize(new UserSettings { TargetLanguage = "ja", UiLanguage = "en" });
        var loaded = JsonSerializer.Deserialize<UserSettings>(json)!;
        Assert.Equal(("ja", "en"), (loaded.TargetLanguage, loaded.UiLanguage));
    }

    [Theory]
    [InlineData("en", "tr", true)]
    [InlineData("en", "ar", false)]
    [InlineData("en", "ja", false)]
    [InlineData("ja", "tr", false)]   // paketler İngilizce satırlardan hazırlandı
    public void TurkishOnlyExtrasApplyOnlyForEnglishToTurkish(string source, string target, bool expected)
    {
        var controller = Make(target, source: source);
        Assert.Equal(expected, controller.TurkishExtras);
        controller.Dispose();
    }

    [Theory]
    // Yerel model yalnız İngilizce→Türkçe
    [InlineData(TranslationEngine.DirectML, "tr", true)]
    [InlineData(TranslationEngine.DirectML, "ar", false)]
    // DeepL: listedeki dillerin hepsi (telefonla aynı)
    [InlineData(TranslationEngine.DeepL, "ja", true)]
    [InlineData(TranslationEngine.DeepL, "ar", true)]
    [InlineData(TranslationEngine.DeepL, "hi", true)]
    [InlineData(TranslationEngine.DeepL, "fa", true)]
    // Gemini: hepsi
    [InlineData(TranslationEngine.Gemini, "hi", true)]
    [InlineData(TranslationEngine.Gemini, "az", true)]
    public void TellsWhetherTheChosenEngineCoversTheLanguage(TranslationEngine engine, string target, bool expected)
    {
        var controller = Make(target, engine);
        Assert.Equal(expected, controller.EngineCoversTarget);
        controller.Dispose();
    }

    [Fact]
    public void DubbingStaysOffForOtherLanguages()
    {
        var settings = new MemorySettings();
        settings.Save(new UserSettings { TargetLanguage = "ar", Dubbing = new DubbingSettings { Enabled = true } });
        var controller = AppControllerTests.Make(new FakeServices(), new MemoryStore(), settings);
        Assert.False(controller.DubbingActive);   // sesler Türkçedir
        controller.Dispose();
    }

    [Fact]
    public void BrokenLanguageCodeFallsBackToTurkishBehaviour()
    {
        var controller = Make("xx");
        Assert.Equal("tr", Languages.OrDefault(controller.Settings.TargetLanguage).Code);
        controller.Dispose();
    }
}
