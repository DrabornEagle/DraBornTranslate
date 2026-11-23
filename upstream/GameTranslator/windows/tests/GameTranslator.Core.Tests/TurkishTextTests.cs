// SPDX-License-Identifier: AGPL-3.0-only

namespace GameTranslator.Core.Tests;

public class TurkishTextTests
{
    [Theory]
    [InlineData("Sistem yazılımı güncellemesi indiriliyor. Daha sonra tekrar deneyin.")]
    [InlineData("Bağlantı kesildi, devam etmek için bir tuşa basın.")]
    [InlineData("Evet, şimdi gidiyoruz.")]
    public void TurkishLinesAreRecognized(string line) => Assert.True(TurkishText.IsSure(line));

    [Theory]
    [InlineData("We have to leave before sunrise.")]
    [InlineData("Atatürk said peace at home, peace in the world.")]
    [InlineData("Take him out.")]
    public void EnglishLinesAreTranslated(string line) => Assert.False(TurkishText.IsSure(line));
}
