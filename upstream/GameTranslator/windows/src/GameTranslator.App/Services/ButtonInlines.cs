// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using System.Windows.Shapes;

namespace GameTranslator.App.Services;

/// <summary>
/// Bilgisayardaki altyazı pencereleri: metindeki kumanda tuşları (✕ ○ △ □, L1–R3, OPTIONS; Xbox: Ⓐ Ⓑ Ⓧ Ⓨ, LB–RS, ⧉ ☰) TV ve OBS'deki gibi
/// PlayStation simgesi olarak çizilir; ek alanda oyunun renkli yazdığı sözcük (<c>⁅#rrggbb|metin⁆</c>) aynı renkte
/// yazılır (tv/webos/app.js ile aynı kurallar).
/// </summary>
internal static partial class ButtonInlines
{
    [GeneratedRegex(@"(✕|×|✖|○|◯|△|□|✜|▦|⧉|☰|[↑↓←→]|[Ⓐ-Ⓩ]|\b(?:L[123]|R[123]|[LR][BTS]|OPTIONS|TOUCHPAD)\b)")]
    private static partial Regex Button();

    [GeneratedRegex("⁅(#[0-9a-fA-F]{6})\\|([^⁆]*)⁆")]
    private static partial Regex Colored();

    private static readonly Brush Cross = Frozen(0x7F, 0xB8, 0xF0), Circle = Frozen(0xFF, 0x6F, 0x78),
        Triangle = Frozen(0x3F, 0xDC, 0xB4), Square = Frozen(0xF0, 0x8C, 0xE4), Rim = Frozen(0xEE, 0xEE, 0xEE),
        Fill = Frozen(0x8C, 0x00, 0x00, 0x00),
        // Xbox: koyu dolu daire ve tuşun kendi renginde harf (tv/webos/style.css ile aynı renkler).
        XboxFill = Frozen(0x1B, 0x1B, 0x1B), XboxA = Frozen(0x8D, 0xC6, 0x3F), XboxB = Frozen(0xFF, 0x4B, 0x4B),
        XboxX = Frozen(0x3D, 0x9B, 0xFF), XboxY = Frozen(0xFF, 0xB9, 0x00), XboxMark = Frozen(0xE6, 0xE6, 0xE6);

    private static SolidColorBrush Frozen(byte r, byte g, byte b) => Frozen(0xFF, r, g, b);

    private static SolidColorBrush Frozen(byte a, byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromArgb(a, r, g, b));
        brush.Freeze();
        return brush;
    }

    /// <summary>Metinde tuş ya da renk işareti var mı (yoksa düz metin yazılır).</summary>
    public static bool HasMarks(string text) => Button().IsMatch(text) || text.Contains('⁅');

    /// <summary>Metni tuş simgeleriyle ve renkli parçalarıyla ekler; <paramref name="foreground"/> verilirse yazı o renkte.</summary>
    public static void Append(InlineCollection inlines, string text, double fontSize, Brush? foreground = null)
    {
        var last = 0;
        foreach (Match match in Colored().Matches(text))
        {
            if (match.Index > last) AppendButtons(inlines, text[last..match.Index], fontSize, foreground);
            var color = (Color)ColorConverter.ConvertFromString(match.Groups[1].Value);
            AppendButtons(inlines, match.Groups[2].Value, fontSize, new SolidColorBrush(color));
            last = match.Index + match.Length;
        }
        if (last < text.Length) AppendButtons(inlines, text[last..], fontSize, foreground);
    }

    private static void AppendButtons(InlineCollection inlines, string text, double fontSize, Brush? foreground)
    {
        var last = 0;
        foreach (Match match in Button().Matches(text))
        {
            if (match.Index > last) inlines.Add(Plain(text[last..match.Index], foreground));
            inlines.Add(new InlineUIContainer(Icon(match.Value, fontSize)) { BaselineAlignment = BaselineAlignment.Center });
            last = match.Index + match.Length;
        }
        if (last < text.Length) inlines.Add(Plain(text[last..], foreground));
    }

    private static Run Plain(string text, Brush? foreground)
    {
        var run = new Run(text);
        if (foreground is not null) run.Foreground = foreground;
        return run;
    }

    /// <summary>Tek tuşun simgesi: yüz tuşları kendi renginde dairede, omuz tuşları yuvarlak köşeli kutuda.</summary>
    private static FrameworkElement Icon(string token, double fontSize)
    {
        var size = fontSize * 1.1;
        Brush? xbox = token switch
        {
            "Ⓐ" => XboxA, "Ⓑ" => XboxB, "Ⓧ" => XboxX, "Ⓨ" => XboxY, "⧉" or "☰" => XboxMark, _ => null,
        };
        if (xbox is not null)
        {
            // Xbox tuşu: koyu dairede tuşun renginde harf; View ve Menu işaretleri açık gri.
            var mark = token is "⧉" or "☰";
            return new Border
            {
                Width = size, Height = size,
                CornerRadius = new CornerRadius(size / 2),
                BorderBrush = Rim, BorderThickness = new Thickness(size * 0.07),
                Background = XboxFill,
                Margin = new Thickness(size * 0.1, 0, size * 0.1, 0),
                Child = new TextBlock
                {
                    Text = token switch { "Ⓐ" => "A", "Ⓑ" => "B", "Ⓧ" => "X", "Ⓨ" => "Y", _ => token },
                    FontSize = size * (mark ? 0.5 : 0.62),
                    FontWeight = FontWeights.Bold,
                    FontFamily = new FontFamily(mark ? "Segoe UI Symbol" : "Arial"),
                    Foreground = xbox,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                },
            };
        }
        var face = token switch
        {
            "✕" or "×" or "✖" => "cross",
            "○" or "◯" => "circle",
            "△" => "triangle",
            "□" => "square",
            _ => null,
        };
        if (face is not null || token.Length == 1)
        {
            UIElement mark = face switch
            {
                "circle" => new Ellipse { Width = size * 0.46, Height = size * 0.46, Stroke = Circle, StrokeThickness = size * 0.09 },
                "square" => new Rectangle { Width = size * 0.42, Height = size * 0.42, Stroke = Square, StrokeThickness = size * 0.09, RadiusX = size * 0.04, RadiusY = size * 0.04 },
                _ => new TextBlock
                {
                    Text = face == "cross" ? "✕" : token,
                    FontSize = size * 0.74,
                    FontWeight = FontWeights.Bold,
                    FontFamily = new FontFamily("Segoe UI Symbol"),
                    Foreground = face switch { "cross" => Cross, "triangle" => Triangle, _ => Rim },
                    Margin = new Thickness(0, 0, 0, size * 0.04),
                },
            };
            if (mark is FrameworkElement element) { element.HorizontalAlignment = HorizontalAlignment.Center; element.VerticalAlignment = VerticalAlignment.Center; }
            return new Border
            {
                Width = size, Height = size,
                CornerRadius = new CornerRadius(size / 2),
                BorderBrush = Rim, BorderThickness = new Thickness(size * 0.07),
                Background = Fill,
                Margin = new Thickness(size * 0.1, 0, size * 0.1, 0),
                Child = mark,
            };
        }
        return new Border
        {
            Height = size,
            MinWidth = size * 1.5,
            Padding = new Thickness(size * 0.18, 0, size * 0.18, 0),
            CornerRadius = new CornerRadius(size * 0.3),
            BorderBrush = Rim, BorderThickness = new Thickness(size * 0.07),
            Background = Fill,
            Margin = new Thickness(size * 0.1, 0, size * 0.1, 0),
            Child = new TextBlock
            {
                Text = token == "TOUCHPAD" ? "▦" : token,
                FontSize = size * 0.5,
                FontWeight = FontWeights.Bold,
                FontFamily = new FontFamily("Arial"),
                Foreground = Rim,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
        };
    }
}
