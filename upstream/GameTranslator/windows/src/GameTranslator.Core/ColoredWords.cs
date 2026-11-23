// SPDX-License-Identifier: AGPL-3.0-only

using System.Globalization;
using System.Text;

namespace GameTranslator.Core;

/// <summary>Oyunun renkli yazdığı sözcük ya da sözcük öbeği ("Go to the <b>plane</b>") ve rengi (#rrggbb).</summary>
public sealed record ColoredPhrase(string Text, string Color);

/// <summary>
/// Oyunlar görev yazısında önemli sözcüğü renkli yazar (GTA 6: yer ve nesne pembe, kişi mavi). Ek alanın okuyucusu
/// sözcüklerin rengini görüntüden bulur; çeviride o sözcüğün karşılığı aynı renkte gösterilir. TV'ye giden metinde renkli
/// parça <c>⁅#rrggbb|metin⁆</c> biçimindedir (yalnız ek alanlarda; altyazıda kullanılmaz).
/// </summary>
public static class ColoredWords
{
    public const char Open = '⁅', Divider = '|', Close = '⁆';
    private static readonly CultureInfo Turkish = CultureInfo.GetCultureInfo("tr-TR");

    /// <summary>
    /// Sözcüğün yazı rengi: kutudaki en parlak piksellerin (yazının kendisi; zemin ve gölge koyudur) ortalaması. Beyaz ya
    /// da gri yazıysa null.
    /// </summary>
    public static string? ColorOf(Frame image, TextBox box)
    {
        int x0 = Math.Clamp((int)(box.Left * image.Width), 0, image.Width), x1 = Math.Clamp((int)Math.Ceiling(box.Right * image.Width), 0, image.Width);
        int y0 = Math.Clamp((int)(box.Top * image.Height), 0, image.Height), y1 = Math.Clamp((int)Math.Ceiling(box.Bottom * image.Height), 0, image.Height);
        if (x1 - x0 < 2 || y1 - y0 < 2) return null;
        var max = 0.0;
        for (var y = y0; y < y1; y++)
            for (var x = x0; x < x1; x++) max = Math.Max(max, Luma(image, x, y));
        if (max < 0.35) return null;
        double r = 0, g = 0, b = 0;
        var count = 0;
        for (var y = y0; y < y1; y++)
            for (var x = x0; x < x1; x++)
            {
                if (Luma(image, x, y) < max * 0.7) continue;
                var p = y * image.Stride + x * 4;
                b += image.Pixels[p];
                g += image.Pixels[p + 1];
                r += image.Pixels[p + 2];
                count++;
            }
        if (count < 6) return null;
        r /= count * 255.0; g /= count * 255.0; b /= count * 255.0;
        double hi = Math.Max(r, Math.Max(g, b)), lo = Math.Min(r, Math.Min(g, b));
        if (hi < 0.35 || (hi - lo) / hi < 0.35) return null;
        // Renk canlı gösterilsin diye en parlak kanal 255'e çekilir.
        static int Channel(double value, double top) => (int)Math.Round(Math.Clamp(value / top, 0, 1) * 255);
        return $"#{Channel(r, hi):x2}{Channel(g, hi):x2}{Channel(b, hi):x2}";
    }

    private static double Luma(Frame image, int x, int y)
    {
        var p = y * image.Stride + x * 4;
        return (0.114 * image.Pixels[p] + 0.587 * image.Pixels[p + 1] + 0.299 * image.Pixels[p + 2]) / 255;
    }

    /// <summary>Satırdaki renkli sözcükler; yan yana ve aynı renkteki sözcükler tek öbektir ("sea plane").</summary>
    public static IReadOnlyList<ColoredPhrase> Phrases(IReadOnlyList<OcrWord> words, Frame image)
    {
        var result = new List<ColoredPhrase>();
        var run = new List<string>();
        string? runColor = null;
        void Flush()
        {
            if (run.Count > 0 && runColor is not null) result.Add(new ColoredPhrase(string.Join(' ', run), runColor));
            run.Clear();
            runColor = null;
        }
        foreach (var word in words)
        {
            // Tek harf (yanlış okunmuş renkli tuş simgesi: "e") renkli sözcük sayılmaz.
            var color = word.Text.Count(char.IsLetterOrDigit) >= 2 ? ColorOf(image, word.Box) : null;
            if (color is null) { Flush(); continue; }
            if (runColor is not null && !SameHue(runColor, color)) Flush();
            runColor ??= color;
            run.Add(word.Text.Trim('.', ',', '!', '?', ':', ';'));
        }
        Flush();
        return result;
    }

    private static bool SameHue(string a, string b)
    {
        static double Hue(string hex)
        {
            double r = Convert.ToInt32(hex[1..3], 16) / 255.0, g = Convert.ToInt32(hex[3..5], 16) / 255.0, b = Convert.ToInt32(hex[5..7], 16) / 255.0;
            double hi = Math.Max(r, Math.Max(g, b)), lo = Math.Min(r, Math.Min(g, b)), d = hi - lo;
            if (d < 1e-6) return 0;
            return hi == r ? 60 * (((g - b) / d % 6 + 6) % 6) : hi == g ? 60 * ((b - r) / d + 2) : 60 * ((r - g) / d + 4);
        }
        var diff = Math.Abs(Hue(a) - Hue(b));
        return Math.Min(diff, 360 - diff) < 30;
    }

    /// <summary>
    /// Çeviride renkli öbeklerin karşılığını işaretler. Öbeğin tek başına çevirisi ("plane" → "uçak") cümlede aranır;
    /// Türkçe ekler ve ünsüz yumuşaması için sözcük başının eşleşmesi yeter ("uçak" ~ "uçağa", "tekne" ~ "teknenin",
    /// "Brian" ~ "Brian'ı"). Bulunamayan öbek renksiz kalır.
    /// </summary>
    public static string Mark(string translation, IReadOnlyList<(string Translated, string Color)> parts)
    {
        var tokens = translation.Split(' ');
        var colors = new string?[tokens.Length];
        foreach (var (translated, color) in parts)
        {
            var wanted = translated.Split(' ', StringSplitOptions.RemoveEmptyEntries).Select(Core).Where(word => word.Length > 0).ToList();
            if (wanted.Count == 0) continue;
            for (var i = 0; i + wanted.Count <= tokens.Length; i++)
            {
                if (Enumerable.Range(0, wanted.Count).Any(k => colors[i + k] is not null || !Matches(Core(tokens[i + k]), wanted[k]))) continue;
                for (var k = 0; k < wanted.Count; k++) colors[i + k] = color;
                break;
            }
        }
        var result = new StringBuilder();
        for (var i = 0; i < tokens.Length; i++)
        {
            if (i > 0) result.Append(' ');
            if (colors[i] is not { } color) { result.Append(tokens[i]); continue; }
            // Aynı renkteki ardışık sözcükler tek parça olur.
            var end = i;
            while (end + 1 < tokens.Length && colors[end + 1] == color) end++;
            result.Append(Open).Append(color).Append(Divider).Append(string.Join(' ', tokens[i..(end + 1)])).Append(Close);
            i = end;
        }
        return result.ToString();
    }

    /// <summary>İşaretli metnin düz hâli.</summary>
    public static string Plain(string marked)
    {
        if (marked.IndexOf(Open) < 0) return marked;
        return System.Text.RegularExpressions.Regex.Replace(marked, "⁅#[0-9a-fA-F]{6}\\|([^⁆]*)⁆", "$1");
    }

    private static string Core(string token) => new string(token.ToLower(Turkish).Where(char.IsLetterOrDigit).ToArray());

    private static bool Matches(string token, string wanted)
    {
        if (token.Length == 0) return false;
        if (token == wanted) return true;
        if (wanted.Length <= 3) return token.StartsWith(wanted, StringComparison.Ordinal);
        var common = 0;
        while (common < token.Length && common < wanted.Length && token[common] == wanted[common]) common++;
        return common >= Math.Max(3, wanted.Length - 2);
    }
}

/// <summary>
/// Windows'un yazı tanıması küçük yazıda "m" harfini "rn" / "nn" / "rri" okuyor ("frorn your noailbox", "longarrn").
/// Sözlükte olmayan sözcükte bu kalıplar "m" yapılır; sonuç sözlükteyse düzeltme kabul edilir.
/// </summary>
public static class LetterM
{
    private static readonly string[] Misreads = ["rn", "nn", "rri", "no"];

    public static string Fix(string text, Func<string, bool>? isWord)
    {
        if (isWord is null || text.Length == 0) return text;
        var tokens = text.Split(' ');
        var changed = false;
        for (var i = 0; i < tokens.Length; i++)
        {
            var token = tokens[i];
            int start = 0, end = token.Length;
            while (start < end && !char.IsLetter(token[start])) start++;
            while (end > start && !char.IsLetter(token[end - 1])) end--;
            if (end - start < 3) continue;
            var word = token[start..end];
            var lower = word.ToLowerInvariant();
            if (isWord(lower) || !Misreads.Any(lower.Contains)) continue;
            if (Fixed(lower, isWord) is not { } repaired) continue;
            var cased = char.IsUpper(word[0]) ? char.ToUpperInvariant(repaired[0]) + repaired[1..] : repaired;
            if (word.All(c => !char.IsLetter(c) || char.IsUpper(c))) cased = repaired.ToUpperInvariant();
            tokens[i] = token[..start] + cased + token[end..];
            changed = true;
        }
        return changed ? string.Join(' ', tokens) : text;
    }

    /// <summary>Kalıpların bir ya da iki yerde "m" yapılmış hâllerinden sözlükte olan ilki.</summary>
    private static string? Fixed(string word, Func<string, bool> isWord)
    {
        var candidates = new List<string>();
        foreach (var misread in Misreads)
            for (var at = word.IndexOf(misread, StringComparison.Ordinal); at >= 0; at = word.IndexOf(misread, at + 1, StringComparison.Ordinal))
                candidates.Add(word[..at] + "m" + word[(at + misread.Length)..]);
        foreach (var once in candidates.ToList())
            foreach (var misread in Misreads)
                for (var at = once.IndexOf(misread, StringComparison.Ordinal); at >= 0; at = once.IndexOf(misread, at + 1, StringComparison.Ordinal))
                    candidates.Add(once[..at] + "m" + once[(at + misread.Length)..]);
        return candidates.FirstOrDefault(isWord);
    }
}
