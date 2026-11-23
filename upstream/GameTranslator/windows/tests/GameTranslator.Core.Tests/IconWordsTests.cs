// SPDX-License-Identifier: AGPL-3.0-only

using GameTranslator.Core;

namespace GameTranslator.Core.Tests;

/// <summary>Satırın içindeki simge boşluğu ve yanlış okunan simge görüntüden tanınan düğmeyle değiştirilir.</summary>
public class IconWordsTests
{
    private const double Width = 1920, Height = 1080;
    private const double Top = 0.80, Bottom = 0.83;

    /// <summary>Sözcükler soldan sağa, aralarında <paramref name="gaps"/> kadar boşlukla (0–1).</summary>
    private static List<OcrWord> Words(params (string Text, double Left, double Right)[] words) =>
        [.. words.Select(word => new OcrWord(word.Text, new TextBox(word.Left, Top, word.Right, Bottom)))];

    [Fact]
    public void IconGapBetweenWordsGetsTheButton()
    {
        // "Hold" ile "to" arasında simge genişliğinde boşluk (satır ~32 px, boşluk ~58 px).
        var words = Words(("Hold", 0.40, 0.43), ("to", 0.46, 0.47), ("climb", 0.475, 0.50));
        var asked = new List<TextBox>();
        var text = IconWords.WithIcons("Hold to climb", words, Width, Height, box => { asked.Add(box); return "✕"; });
        Assert.Equal("Hold ✕ to climb", text);
        var gap = Assert.Single(asked);
        Assert.Equal(0.43, gap.Left, 3);
        Assert.Equal(0.46, gap.Right, 3);
    }

    [Fact]
    public void OrdinaryWordSpacingIsLeftAlone()
    {
        var words = Words(("Get", 0.40, 0.42), ("in", 0.425, 0.435), ("the", 0.44, 0.455), ("car.", 0.46, 0.48));
        Assert.Equal("Get in the car.", IconWords.WithIcons("Get in the car.", words, Width, Height, _ => throw new InvalidOperationException()));
    }

    [Fact]
    public void MisreadIconIsReplacedAndUnknownPictureIsDropped()
    {
        var words = Words(("Press", 0.40, 0.43), ("•", 0.432, 0.44), ("to", 0.442, 0.45), ("jump", 0.452, 0.47));
        Assert.Equal("Press □ to jump", IconWords.WithIcons("Press • to jump", words, Width, Height, _ => "□"));
        Assert.Equal("Press to jump", IconWords.WithIcons("Press • to jump", words, Width, Height, _ => null));
    }

    [Fact]
    public void LetterAfterVerbIsCheckedButKeptWhenNoIconIsThere()
    {
        var words = Words(("Hold", 0.40, 0.43), ("A", 0.432, 0.44), ("to", 0.442, 0.45), ("aim", 0.452, 0.47));
        Assert.Equal("Hold △ to aim", IconWords.WithIcons("Hold A to aim", words, Width, Height, _ => "△"));
        Assert.Equal("Hold A to aim", IconWords.WithIcons("Hold A to aim", words, Width, Height, _ => null));
    }

    [Fact]
    public void EdgeIconIsAddedOnceAndDuplicatesCollapse()
    {
        var line = new TextBox(0.45, Top, 0.55, Bottom);
        Assert.Equal("□ Pick up", IconWords.WithEdgeIcons("Pick up", line, Width, Height, box => box.Right <= line.Left ? "□" : null));
        Assert.Equal("✕ Open", IconWords.WithEdgeIcons("✕ Open", line, Width, Height, box => box.Right <= line.Left ? "✕" : null));
        Assert.Equal("Hold ✕ to climb", IconWords.NormalizedIcons("Hold ✕ ✕ to climb"));
        Assert.Equal("L2 Aim", IconWords.NormalizedIcons("(l2) Aim"));
    }

    /// <summary>Cümlenin içindeki harita işareti ("your stash ◆ or by…") tuş sayılmaz; fiilden sonraki simge tuştur.</summary>
    [Fact]
    public void MapMarkerInsideASentenceIsNotAButton()
    {
        var words = Words(("your", 0.40, 0.42), ("stash", 0.425, 0.44), ("or", 0.47, 0.48), ("by", 0.485, 0.49));
        Assert.Equal("your stash or by", IconWords.WithIcons("your stash or by", words, Width, Height, _ => "△"));
        var press = Words(("Press", 0.40, 0.43), ("to", 0.46, 0.47), ("jump", 0.475, 0.49));
        Assert.Equal("Press ✕ to jump", IconWords.WithIcons("Press to jump", press, Width, Height, _ => "✕"));
    }

    /// <summary>Renkli adın önündeki bozuk parçalar birbirini tuş diye onaylamaz ("Talk to □ ○ Brian").</summary>
    [Fact]
    public void JunkBeforeAColouredNameIsNotAChainOfButtons()
    {
        var words = Words(("Talk", 0.40, 0.42), ("to", 0.425, 0.435), ("•", 0.44, 0.445), ("O", 0.45, 0.455), ("Brian", 0.46, 0.48));
        Assert.Equal("Talk to Brian", IconWords.WithIcons("Talk to • O Brian", words, Width, Height, _ => "□"));
    }

    [Fact]
    public void RightEdgeIconIsOnlyLookedForAfterAVerbOrInAButtonBar()
    {
        var line = new TextBox(0.45, Top, 0.50, Bottom);
        Assert.Equal("Find", IconWords.WithEdgeIcons("Find", line, Width, Height, box => box.Left >= line.Right ? "□" : null));
        Assert.Equal("SELECT ✕", IconWords.WithEdgeIcons("SELECT", line, Width, Height, box => box.Left >= line.Right ? "✕" : null));
        Assert.Equal("Press ✕", IconWords.WithEdgeIcons("Press", line, Width, Height, box => box.Left >= line.Right ? "✕" : null));
    }

    [Fact]
    public void JunkAtTheEndOfASentenceIsNotAButton()
    {
        var words = Words(("Follow", 0.40, 0.43), ("•", 0.46, 0.47));
        Assert.Equal("Follow", IconWords.WithIcons("Follow •", words, Width, Height, _ => "□"));
        var press = Words(("Press", 0.40, 0.43), ("•", 0.46, 0.47));
        Assert.Equal("Press □", IconWords.WithIcons("Press •", press, Width, Height, _ => "□"));
    }

    [Fact]
    public void LowercaseLetterInsideAnUppercaseBarIsChecked()
    {
        var words = Words(("OFFER", 0.40, 0.43), ("RIDE", 0.432, 0.45), ("e", 0.452, 0.455), ("LUCIA", 0.457, 0.48));
        Assert.Equal("OFFER RIDE ✕ LUCIA", IconWords.WithIcons("OFFER RIDE e LUCIA", words, Width, Height, _ => "✕"));
        Assert.Equal("Get in the e car", IconWords.WithIcons("Get in the e car", Words(("Get", 0.40, 0.42), ("in", 0.425, 0.43), ("the", 0.435, 0.44), ("e", 0.445, 0.45), ("car", 0.455, 0.47)), Width, Height, _ => throw new InvalidOperationException()));
    }

    [Theory]
    [InlineData("•", true)]
    [InlineData("(x)", true)]
    [InlineData("LZ", true)]
    [InlineData("B", true)]
    [InlineData("A", false)]
    [InlineData("I", false)]
    [InlineData("car", false)]
    public void SuspiciousTokens(string word, bool expected) => Assert.Equal(expected, IconWords.MayBeIcon(word));
}
