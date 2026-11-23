// SPDX-License-Identifier: AGPL-3.0-only

using GameTranslator.Core;

namespace GameTranslator.Core.Tests;

/// <summary>Xbox tuşları: işaretler, görüntüden tanıma, harf olarak okunan tuşlar ve Otomatik aile kararı.</summary>
public class XboxButtonsTests
{
    private const int Size = 96;

    /// <summary>Koyu zeminde <paramref name="disc"/> renginde daire; içine <paramref name="mark"/> koyu çizilir.</summary>
    private static Frame Icon(Func<double, double, bool> mark, (byte R, byte G, byte B) disc)
    {
        var frame = Frame.Blank(Size * 2, Size);
        for (var y = 0; y < Size; y++)
            for (var x = 0; x < Size * 2; x++)
            {
                var p = y * frame.Stride + x * 4;
                double dx = x - Size, dy = y - Size / 2.0;
                var r = Size * 0.32;
                byte cr = 20, cg = 20, cb = 24;
                if (dx * dx + dy * dy <= r * r)
                {
                    (cr, cg, cb) = disc;
                    if (mark(dx / r, dy / r)) (cr, cg, cb) = (30, 30, 30);
                }
                frame.Pixels[p] = cb;
                frame.Pixels[p + 1] = cg;
                frame.Pixels[p + 2] = cr;
                frame.Pixels[p + 3] = 255;
            }
        return frame;
    }

    private static readonly (byte, byte, byte) Green = (127, 186, 0), Red = (232, 17, 35), Blue = (0, 120, 215), Yellow = (255, 185, 0);
    private static readonly (byte, byte, byte) White = (235, 235, 235);

    private static bool Nothing(double x, double y) => false;

    /// <summary>Üç yatay çizgi (Menu).</summary>
    private static bool Bars(double x, double y) =>
        Math.Abs(x) < 0.42 && (Math.Abs(y + 0.3) < 0.08 || Math.Abs(y) < 0.08 || Math.Abs(y - 0.3) < 0.08);

    [Theory]
    [InlineData("Ⓐ", 0)]
    [InlineData("Ⓑ", 1)]
    [InlineData("Ⓧ", 2)]
    [InlineData("Ⓨ", 3)]
    public void XboxFaceButtonsAreReadFromTheirColour(string expected, int colour)
    {
        var disc = new[] { Green, Red, Blue, Yellow }[colour];
        Assert.Equal(expected, ButtonIcons.Classify(Icon(Nothing, disc), layout: new ButtonLayoutVote(ButtonLayout.Xbox)));
    }

    [Fact]
    public void PlayStationColoursStayPlayStation()
    {
        // Seçili PlayStation'da mavi daire ✕, sarı daire harita işaretidir.
        var ps = new ButtonLayoutVote(ButtonLayout.PlayStation);
        Assert.Equal("✕", ButtonIcons.Classify(Icon(Nothing, Blue), layout: ps));
        Assert.Equal("◆", ButtonIcons.Classify(Icon(Nothing, Yellow), layout: ps));
        Assert.Equal("◆", ButtonIcons.Classify(Icon(Nothing, Yellow), _ => "Y", layout: ps));
    }

    [Fact]
    public void YellowCircleWithYIsTheXboxYButtonInAuto()
    {
        var vote = new ButtonLayoutVote();
        Assert.Equal("Ⓨ", ButtonIcons.Classify(Icon(Nothing, Yellow), _ => "Y", layout: vote));
        Assert.True(vote.LeansXbox);
    }

    [Fact]
    public void LetterReadAtTheIconBecomesTheXboxButton()
    {
        // GTA 6 Xbox: beyaz dolu dairede koyu "X" (satır okumasında "x"); Xbox'ta Ⓧ, PlayStation'da ✕.
        static bool Cross(double x, double y) => Math.Abs(x) < 0.5 && Math.Abs(y) < 0.5 && (Math.Abs(x - y) < 0.18 || Math.Abs(x + y) < 0.18);
        var icon = Icon(Cross, White);
        Assert.Equal("Ⓧ", ButtonIcons.Classify(icon, readLine: (_, _, _, _) => "x", layout: new ButtonLayoutVote(ButtonLayout.Xbox)));
        Assert.Equal("✕", ButtonIcons.Classify(icon, readLine: (_, _, _, _) => "x", layout: new ButtonLayoutVote(ButtonLayout.PlayStation)));
        // Xbox'ın yeşil dairesinde okunan "A" Otomatik'te de Ⓐ'dır (renk Xbox yeşili, şekil üçgen değil).
        Assert.Equal("Ⓐ", ButtonIcons.Classify(Icon(Nothing, Green), readLine: (_, _, _, _) => "A"));
    }

    [Fact]
    public void ShoulderLabelsFollowTheFamily()
    {
        Assert.Equal("RT", ButtonIcons.Label("RT"));
        Assert.Equal("LS", ButtonIcons.Label("(LS)"));
        Assert.Equal("R1", ButtonIcons.Label("RI"));
        Assert.Equal("RT", ButtonIcons.Label("RI", family: ButtonFamily.Xbox));
        Assert.Equal("L3", ButtonIcons.Label("LB"));
        Assert.Equal("LB", ButtonIcons.Label("LB", family: ButtonFamily.Xbox));
        Assert.Equal("LB", ButtonIcons.Label("L8", family: ButtonFamily.Xbox));
        Assert.Equal("RB", ButtonIcons.Label("RB", leanXbox: true));
        Assert.Equal("L", ButtonIcons.Label("L", stick: true));
        Assert.Null(ButtonIcons.Label("L", stick: true, family: ButtonFamily.Xbox));
        Assert.Equal("⧉", ButtonIcons.Label("View", stick: true, family: ButtonFamily.Xbox));
    }

    [Fact]
    public void ThreeBarsAreTheMenuButton()
    {
        Assert.Equal("☰", ButtonIcons.Classify(Icon(Bars, White), layout: new ButtonLayoutVote(ButtonLayout.Xbox)));
        // PlayStation'da Menu tuşu yoktur.
        Assert.Null(ButtonIcons.Classify(Icon(Bars, White), layout: new ButtonLayoutVote(ButtonLayout.PlayStation)));
    }

    [Fact]
    public void VoteIsStickyAndForcedSettingWins()
    {
        var vote = new ButtonLayoutVote();
        Assert.Equal(ButtonFamily.PlayStation, vote.Current);
        vote.Add(ButtonFamily.Xbox);
        vote.Add(ButtonFamily.Xbox);
        Assert.Equal(ButtonFamily.PlayStation, vote.Current);
        vote.Add(ButtonFamily.Xbox);
        Assert.Equal(ButtonFamily.Xbox, vote.Current);
        // Tek bir PlayStation simgesi kararı çevirmez.
        vote.Add(ButtonFamily.PlayStation);
        Assert.Equal(ButtonFamily.Xbox, vote.Current);
        vote.Reset();
        Assert.Equal(ButtonFamily.PlayStation, vote.Current);
        var forced = new ButtonLayoutVote(ButtonLayout.Xbox);
        for (var i = 0; i < 6; i++) forced.Add(ButtonFamily.PlayStation);
        Assert.Equal(ButtonFamily.Xbox, forced.Current);
        Assert.Equal(ButtonLayout.Xbox, ButtonLayoutVote.Resolve(ButtonLayout.Auto, true));
        Assert.Equal(ButtonLayout.PlayStation, ButtonLayoutVote.Resolve(ButtonLayout.Auto, false));
        Assert.Equal(ButtonLayout.Auto, ButtonLayoutVote.Resolve(ButtonLayout.Auto, null));
        Assert.Equal(ButtonLayout.PlayStation, ButtonLayoutVote.Resolve(ButtonLayout.PlayStation, true));
    }

    [Theory]
    [InlineData("Press A to jump", "Press Ⓐ to jump")]
    [InlineData("Hold [X]", "Hold Ⓧ")]
    [InlineData("(B) Back", "Ⓑ Back")]
    [InlineData("A Talk", "Ⓐ Talk")]
    [InlineData("TALK A", "TALK Ⓐ")]
    [InlineData("Press A or B to dodge", "Press Ⓐ or Ⓑ to dodge")]
    [InlineData("PRESS Y TO CONTINUE", "PRESS Ⓨ TO CONTINUE")]
    [InlineData("Use A Medkit to heal.", "Use A Medkit to heal.")]
    [InlineData("A new day begins.", "A new day begins.")]
    [InlineData("Plan B is ready.", "Plan B is ready.")]
    [InlineData("Hold (LT) to aim", "Hold LT to aim")]
    public void XboxLettersAreTurnedIntoButtonsOnlyWhereAButtonBelongs(string line, string expected) =>
        Assert.Equal(expected, IconWords.WithXboxLetters(line, ButtonFamily.Xbox));

    [Fact]
    public void PlayStationTextIsLeftAlone() =>
        Assert.Equal("Press A to jump", IconWords.WithXboxLetters("Press A to jump", ButtonFamily.PlayStation));

    [Fact]
    public void PlainButtonNamesFeedTheAutoVote()
    {
        var vote = new ButtonLayoutVote();
        for (var i = 0; i < 3; i++) IconWords.Observe("Hold LT to aim", vote);
        Assert.Equal(ButtonFamily.Xbox, vote.Current);
        var other = new ButtonLayoutVote();
        IconWords.Observe("We lost the LT signal, Captain.", other);
        IconWords.Observe("We lost the LT signal, Captain.", other);
        IconWords.Observe("We lost the LT signal, Captain.", other);
        Assert.Equal(ButtonFamily.PlayStation, other.Current);
    }

    [Fact]
    public void XboxTokensArePromptsAndKeptInTranslation()
    {
        Assert.True(ButtonPrompts.IsPrompt("Hold LT to aim"));
        Assert.True(ButtonPrompts.IsPrompt("Ⓐ Talk"));
        Assert.True(ButtonPrompts.IsPrompt("RB Next"));
        foreach (var token in new[] { "Ⓐ", "Ⓨ", "LB", "RS", "⧉", "☰" }) Assert.True(ButtonPrompts.IsIcon(token), token);
        Assert.Equal("Nişan almak için LT basılı tut", UiPrompts.KeepIcons("Hold LT to aim", "Nişan almak için lt basılı tut"));
        Assert.Equal("Ⓐ Konuş", UiPrompts.KeepIcons("Ⓐ Talk", "Konuş"));
        Assert.Equal("Ⓐ Konuş", UiPrompts.Translate("Ⓐ TALK"));
        Assert.Equal(ButtonFamily.Xbox, ButtonTokens.FamilyOf("RT"));
        Assert.Equal(ButtonFamily.PlayStation, ButtonTokens.FamilyOf("△"));
        Assert.Null(ButtonTokens.FamilyOf("↑"));
        Assert.Equal("LT Aim", IconWords.NormalizedIcons("[LT] Aim"));
        Assert.Equal("Hold Ⓐ to climb", IconWords.NormalizedIcons("Hold Ⓐ Ⓐ to climb"));
    }
}
