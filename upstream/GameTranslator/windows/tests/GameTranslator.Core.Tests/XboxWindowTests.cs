// SPDX-License-Identifier: AGPL-3.0-only

namespace GameTranslator.Core.Tests;

public class XboxWindowTests
{
    [Theory]
    // Tarayıcıda Xbox oynatma sekmesi
    [InlineData("Forza Horizon 5 | Xbox Cloud Gaming (Beta) on Xbox.com - Microsoft​ Edge", "msedge", true)]
    [InlineData("Xbox Remote Play | Xbox Cloud Gaming (Beta) on Xbox.com - Google Chrome", "chrome.exe", true)]
    [InlineData("Halo Infinite | Xbox Cloud Gaming — Mozilla Firefox", "firefox", true)]
    // Xbox uygulaması (akış kendi penceresinde)
    [InlineData("Xbox", "XboxPcApp", true)]
    [InlineData("Xbox Remote Play", "XboxPcApp", true)]
    // Sayfa "uygulama olarak" yüklenmiş ya da açık kaynak istemci
    [InlineData("Xbox Cloud Gaming", "ApplicationFrameHost", true)]
    [InlineData("Greenlight", "greenlight", true)]
    [InlineData("Play  XBOX - Microsoft Edge", "msedge", true)]
    [InlineData("How to play Xbox games on PC - YouTube - Google Chrome", "chrome", false)]
    // Xbox akışı olmayanlar
    [InlineData("Xbox Series X fiyatları - Haber - Google Chrome", "chrome", false)]
    [InlineData("YouTube - Microsoft Edge", "msedge", false)]
    [InlineData("Xbox Game Bar", "GameBar", false)]
    [InlineData("Xbox Accessories", "XboxAccessories", false)]
    [InlineData("OBS 31.0 - Profil: Xbox", "obs64", false)]
    [InlineData("", "XboxPcApp", false)]
    public void RecognisesXboxStreamWindows(string title, string process, bool expected) =>
        Assert.Equal(expected, XboxWindow.Score(title, process) > 0);

    [Fact]
    public void PlayingTabBeatsThePlainXboxApp()
    {
        // İkisi de açıksa oyunun oynandığı pencere seçilir
        Assert.True(XboxWindow.Score("Xbox Remote Play", "XboxPcApp") > XboxWindow.Score("Xbox", "XboxPcApp"));
        Assert.True(XboxWindow.Score("Starfield | Xbox Cloud Gaming (Beta) on Xbox.com - Microsoft Edge", "msedge") > XboxWindow.Score("Xbox", "XboxPcApp"));
    }

    [Theory]
    [InlineData("Forza Horizon 5 | Xbox Cloud Gaming (Beta) on Xbox.com - Microsoft​ Edge", "Forza Horizon 5")]
    [InlineData("Play Starfield | Xbox Cloud Gaming (Beta) on Xbox.com - Google Chrome", "Starfield")]
    [InlineData("Red Dead Redemption 2 | Xbox Cloud Gaming (Beta) on Xbox.com - Kişisel - Microsoft Edge", "Red Dead Redemption 2")]
    [InlineData("(3) Grand Theft Auto V | Xbox Cloud Gaming — Mozilla Firefox", "Grand Theft Auto V")]
    [InlineData("Elden Ring - Xbox Cloud Gaming (Beta)", "Elden Ring")]
    [InlineData("Assassin's Creed Shadows", "Assassin's Creed Shadows")]
    public void ReadsTheGameFromTheTitle(string title, string expected) => Assert.Equal(expected, XboxWindow.GameName(title));

    [Theory]
    [InlineData("Xbox")]
    [InlineData("Xbox Remote Play | Xbox Cloud Gaming (Beta) on Xbox.com - Google Chrome")]
    [InlineData("Xbox Cloud Gaming (Beta) on Xbox.com - Microsoft Edge")]
    [InlineData("Home | Xbox Cloud Gaming (Beta) on Xbox.com - Microsoft Edge")]
    [InlineData("Greenlight")]
    // Sitenin Türkçe ana sayfası (uygulamanın kendi Xbox penceresinde görülen gerçek başlık)
    [InlineData("Xbox.com’da Xbox Cloud  Gaming")]
    [InlineData("Xbox Cloud Gaming (Beta) on Xbox.com")]
    // Uzaktan oynatma sayfası (play.xbox.com) ve sitenin genel başlığı
    [InlineData("Play  XBOX")]
    [InlineData("Play XBOX - Microsoft Edge")]
    [InlineData("")]
    [InlineData(null)]
    public void TitleWithoutAGameGivesNothing(string? title) => Assert.Null(XboxWindow.GameName(title));

    /// Başlıktan okunan ad paketi buluyor (PS5'in bildirdiği adla aynı yol).
    [Theory]
    [InlineData("Red Dead Redemption 2 | Xbox Cloud Gaming (Beta) on Xbox.com - Microsoft Edge", "red-dead-redemption-2")]
    [InlineData("Grand Theft Auto V | Xbox Cloud Gaming (Beta) on Xbox.com - Google Chrome", "gta-v")]
    public void GameNameFindsThePack(string title, string packId)
    {
        var library = new GamePackLibrary(GamePackLibrary.EmbeddedPacks, null);
        Assert.Equal(packId, library.FindByGameName(XboxWindow.GameName(title)));
    }
}
