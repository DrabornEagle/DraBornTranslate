// SPDX-License-Identifier: AGPL-3.0-only

using GameTranslator.Core;

namespace GameTranslator.Core.Tests;

/// <summary>Oyunun renkli yazdığı sözcük çeviride aynı renkte gösterilir; küçük yazıda "m" okuma hatası düzeltilir.</summary>
public class ColoredWordsTests
{
    [Theory]
    [InlineData("Uçağa bin", "uçak", "⁅#ff8dab|Uçağa⁆ bin")]
    [InlineData("Teknenin yakınına in", "tekne", "⁅#ff8dab|Teknenin⁆ yakınına in")]
    [InlineData("Brian'ı takip et", "Brian", "⁅#ff8dab|Brian'ı⁆ takip et")]
    [InlineData("Deniz uçağına bin", "deniz uçağı", "⁅#ff8dab|Deniz uçağına⁆ bin")]
    [InlineData("Arabaya bin", "uçak", "Arabaya bin")]
    public void TranslatedPhraseIsColoredInTheSentence(string translation, string phrase, string expected) =>
        Assert.Equal(expected, ColoredWords.Mark(translation, [(phrase, "#ff8dab")]));

    [Fact]
    public void PlainRemovesTheMarks() =>
        Assert.Equal("Deniz uçağına bin", ColoredWords.Plain("⁅#ff8dab|Deniz uçağına⁆ bin"));

    [Fact]
    public void ColouredWordIsFoundAndWhiteWordIsNot()
    {
        // Koyu zemin üstünde iki sözcük: soldaki beyaz, sağdaki pembe yazı.
        var frame = Frame.Blank(200, 40);
        for (var y = 0; y < 40; y++)
            for (var x = 0; x < 200; x++)
            {
                var p = y * frame.Stride + x * 4;
                var stroke = y is > 12 and < 28 && x % 6 < 3;
                (byte r, byte g, byte b) = !stroke ? ((byte)20, (byte)20, (byte)24) : x < 100 ? ((byte)240, (byte)240, (byte)240) : ((byte)255, (byte)140, (byte)170);
                frame.Pixels[p] = b; frame.Pixels[p + 1] = g; frame.Pixels[p + 2] = r; frame.Pixels[p + 3] = 255;
            }
        var phrases = ColoredWords.Phrases([new OcrWord("Get", new TextBox(0.05, 0.2, 0.45, 0.8)), new OcrWord("plane", new TextBox(0.55, 0.2, 0.95, 0.8))], frame);
        var phrase = Assert.Single(phrases);
        Assert.Equal("plane", phrase.Text);
        Assert.StartsWith("#ff", phrase.Color);
    }

    [Theory]
    [InlineData("Collect cash fronn conopleted jobs", "Collect cash from completed jobs")]
    [InlineData("frorn your noailbox", "from your mailbox")]
    [InlineData("Pick up a longarrn", "Pick up a longarm")]
    [InlineData("Working for Brian will require sonne gear", "Working for Brian will require some gear")]
    [InlineData("Turn on the corner", "Turn on the corner")]
    public void MisreadLetterMIsRepaired(string read, string expected)
    {
        var words = new HashSet<string> { "collect", "cash", "from", "completed", "jobs", "your", "mailbox", "pick", "up", "a", "longarm",
            "working", "for", "brian", "will", "require", "some", "gear", "turn", "on", "the", "corner" };
        Assert.Equal(expected, LetterM.Fix(read, words.Contains));
    }
}
