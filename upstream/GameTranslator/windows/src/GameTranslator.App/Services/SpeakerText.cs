// SPDX-License-Identifier: AGPL-3.0-only

using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using GameTranslator.Core;

namespace GameTranslator.App.Services;

/// <summary>
/// Bilgisayardaki altyazı pencereleri: "Konuşmacı adı renkli" açıksa her konuşmacının adı kendi renginde yazılır
/// (TV, OBS ve ikinci ekranla aynı renkler, <see cref="SpeakerColors"/>).
/// </summary>
internal static class SpeakerText
{
    public static void Show(TextBlock block, string text, SubtitleStyle? style, SpeakerColors speakers, bool paragraphs = false)
    {
        // Metindeki kumanda tuşları (✕, R2) TV ve OBS'deki gibi simge olarak çizilir.
        var marks = ButtonInlines.HasMarks(text);
        if (style is not { SpeakerColor: true } || paragraphs)
        {
            if (!marks) { block.Text = text; return; }
            block.Inlines.Clear();
            ButtonInlines.Append(block.Inlines, text, block.FontSize);
            return;
        }
        var parts = speakers.Split(text, SpeakerColors.Palette(style.TextColor));
        if (parts.All(p => p.Color is null) && !marks)
        {
            block.Text = text;
            return;
        }
        block.Inlines.Clear();
        foreach (var (part, color) in parts)
            ButtonInlines.Append(block.Inlines, part, block.FontSize,
                color is null ? null : new SolidColorBrush((Color)ColorConverter.ConvertFromString(color)));
    }
}
