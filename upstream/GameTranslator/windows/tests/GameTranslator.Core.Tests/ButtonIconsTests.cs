// SPDX-License-Identifier: AGPL-3.0-only

using GameTranslator.Core;

namespace GameTranslator.Core.Tests;

/// <summary>Kumanda simgesi görüntüden tanınır (Nino Game Translator'ın ButtonIcons kurallarıyla).</summary>
public class ButtonIconsTests
{
    private const int Size = 96;

    /// <summary>Koyu zemin üstünde beyaz daire; içine <paramref name="mark"/> koyu çizilir (PlayStation simgeleri böyle).</summary>
    private static Frame Icon(Func<double, double, bool> mark, (byte R, byte G, byte B)? disc = null)
    {
        var color = disc ?? (235, 235, 235);
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
                    (cr, cg, cb) = color;
                    // İşaret, dairenin içinde -1..1 koordinatıyla çizilir.
                    if (mark(dx / r, dy / r)) (cr, cg, cb) = (30, 30, 30);
                }
                frame.Pixels[p] = cb;
                frame.Pixels[p + 1] = cg;
                frame.Pixels[p + 2] = cr;
                frame.Pixels[p + 3] = 255;
            }
        return frame;
    }

    private const double Stroke = 0.13;

    private static bool Cross(double x, double y) =>
        Math.Abs(x) < 0.5 && Math.Abs(y) < 0.5 && (Math.Abs(x - y) < Stroke * 1.4 || Math.Abs(x + y) < Stroke * 1.4);

    private static bool Ring(double x, double y)
    {
        var d = Math.Sqrt(x * x + y * y);
        return d > 0.38 && d < 0.55;
    }

    private static bool Square(double x, double y) =>
        Math.Max(Math.Abs(x), Math.Abs(y)) is > 0.36 and < 0.5;

    private static bool Triangle(double x, double y)
    {
        // Tepe üstte (y = -0.45), taban altta (y = 0.4).
        if (y < -0.47 || y > 0.42) return false;
        var t = (y + 0.45) / 0.85;
        var half = t * 0.5;
        return (y > 0.28 && Math.Abs(x) <= half) || Math.Abs(Math.Abs(x) - half) < Stroke * 1.3;
    }

    [Theory]
    [InlineData("✕")]
    [InlineData("○")]
    [InlineData("□")]
    [InlineData("△")]
    public void FaceButtonsAreRecognizedFromTheirSymbol(string expected)
    {
        Func<double, double, bool> mark = expected switch { "✕" => Cross, "○" => Ring, "□" => Square, _ => Triangle };
        Assert.Equal(expected, ButtonIcons.Classify(Icon(mark)));
    }

    [Fact]
    public void ColouredDiscIsAMapMarkerNotAButton()
    {
        Assert.Equal("◆", ButtonIcons.Classify(Icon((_, _) => false, (230, 190, 20))));
        // Sarı dairedeki A/B/X harfi PlayStation'da harita işaretidir (Ⓑ artık Xbox'ın kırmızı B tuşu olarak çiziliyor);
        // başka harf daire içinde harf olarak kalır.
        Assert.Equal("◆", ButtonIcons.Classify(Icon((_, _) => false, (230, 190, 20)), _ => "B"));
        Assert.Equal("Ⓚ", ButtonIcons.Classify(Icon((_, _) => false, (230, 190, 20)), _ => "K"));
    }

    [Fact]
    public void ShoulderButtonIsReadFromItsLabel()
    {
        // Yazılı kutu (L2): yazı okuyucusu "L2" okur; okunamazsa daire/kare sembol sanılmaz.
        var frame = Frame.Blank(Size * 2, Size);
        for (var y = 0; y < Size; y++)
            for (var x = 0; x < Size * 2; x++)
            {
                var p = y * frame.Stride + x * 4;
                var inside = Math.Abs(x - Size) < 26 && Math.Abs(y - Size / 2) < 16;
                frame.Pixels[p] = frame.Pixels[p + 1] = frame.Pixels[p + 2] = inside ? (byte)230 : (byte)20;
                frame.Pixels[p + 3] = 255;
            }
        Assert.Equal("L2", ButtonIcons.Classify(frame, _ => "L2"));
        Assert.Equal("L1", ButtonIcons.Classify(frame, _ => "(LI)"));
        Assert.Null(ButtonIcons.Classify(frame, _ => null));
    }

    [Fact]
    public void PlainBackgroundIsNotAnIcon()
    {
        var frame = Frame.Blank(Size * 2, Size);
        for (var i = 0; i < frame.Pixels.Length; i += 4)
        {
            frame.Pixels[i] = frame.Pixels[i + 1] = frame.Pixels[i + 2] = 40;
            frame.Pixels[i + 3] = 255;
        }
        Assert.Null(ButtonIcons.Classify(frame));
    }

    [Theory]
    [InlineData("L2", "L2")]
    [InlineData("(RI)", "R1")]
    [InlineData("DL2T", "L2")]
    [InlineData("OPTIONS", "OPTIONS")]
    [InlineData("Hello", null)]
    public void LabelsAreNormalized(string read, string? expected) => Assert.Equal(expected, ButtonIcons.Label(read));
}
