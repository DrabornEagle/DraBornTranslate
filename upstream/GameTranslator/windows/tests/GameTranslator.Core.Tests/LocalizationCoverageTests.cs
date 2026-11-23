// SPDX-License-Identifier: AGPL-3.0-only

using System.Net;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace GameTranslator.Core.Tests;

/// <summary>
/// Windows uygulamasının ekran metinleri katalogdan (Localizable.xcstrings) gelmeli: koda gömülü Türkçe metin
/// İngilizce arayüzde Türkçe kalır. İlk test gömülü Türkçe metni, ikincisi katalogda olmayan anahtarı yakalar.
/// Yakalayamadıkları: Türkçeye özgü harf içermeyen Türkçe metin ("Kapat", "Yenile"), çalışma anında kurulan
/// anahtarlar ve XAML'de öznitelik dışında (öğe içine) yazılmış metin; bunlar gözle denetlenir.
/// </summary>
public class LocalizationCoverageTests
{
    private const string TurkishLetters = "çğıöşüİŞĞÇÖÜ";

    /// <summary>Türkçe metni bilerek taşıyan dosyalar (dosya adı → neden).</summary>
    private static readonly Dictionary<string, string> AllowedFiles = new()
    {
        // Çekirdek kitaplık (ve içindeki katalog) yüklenemediğinde de çalışmalı: eksik dosya iletisi kendi tr/en çiftini taşır.
        ["StartupGuard.cs"] = "katalog yüklenmeden önce çalışır",
    };

    /// <summary>Türkçe kalması gereken tek tek metinler (dosya adı, metnin başı → neden).</summary>
    private static readonly (string File, string Starts, string Why)[] AllowedLiterals =
    [
        ("Dubbing.cs", "Merhaba, oyundaki karakterleri", "Türkçe dublaj sesinin deneme cümlesi: seslendirilen metin, arayüz değil"),
        ("AppController.cs", "elendi(", "altyazi.log'a yazılan aday dökümünün etiketi (günlük satırı)"),
        ("MainView.Guide.cs", "GameTranslator's TV app installs on LG", "rehberin İngilizce katalog anahtarı; 'ç' marka adından (Arçelik), ekranda text[...] ile çevrilir"),
    ];

    /// <summary>Günlük çağrıları: geliştirici günlüğü çevrilmez.</summary>
    private static readonly string[] LogCalls = ["SubtitleLog.Write", "RemotePlayLog", "Log?.Invoke", "Log(", "Console.Error.WriteLine", "Debug.WriteLine"];

    private static string Root([CallerFilePath] string source = "") =>
        Path.GetFullPath(Path.Combine(Path.GetDirectoryName(source)!, "..", "..", ".."));

    private static IEnumerable<string> Sources(string folder, string extension) =>
        Directory.EnumerateFiles(Path.Combine(Root(), "windows", "src", folder), "*" + extension, SearchOption.AllDirectories)
            .Where(path => !path.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar).Any(part => part is "bin" or "obj"));

    [Fact]
    public void NoHardCodedTurkishTextInAppAndShell()
    {
        var found = new List<string>();
        foreach (var project in new[] { "GameTranslator.App", "GameTranslator.Shell" })
        {
            foreach (var path in Sources(project, ".xaml"))
            {
                var text = Regex.Replace(File.ReadAllText(path), "<!--.*?-->", "", RegexOptions.Singleline);
                foreach (Match match in Regex.Matches(text, @"(?<![\w:.])(?:[\w:]+\.)?(Text|Content|ToolTip|Header|Title)=""([^""]*)"""))
                {
                    var value = WebUtility.HtmlDecode(match.Groups[2].Value);
                    if (value.StartsWith('{') || value.IndexOfAny(TurkishLetters.ToCharArray()) < 0) continue;
                    found.Add($"{Path.GetFileName(path)}: {match.Groups[1].Value}=\"{value}\"");
                }
            }
            foreach (var path in Sources(project, ".cs"))
            {
                var name = Path.GetFileName(path);
                if (AllowedFiles.ContainsKey(name)) continue;
                var text = File.ReadAllText(path);
                foreach (var (start, literal) in StringLiterals(text))
                {
                    if (literal.IndexOfAny(TurkishLetters.ToCharArray()) < 0) continue;
                    if (IsCatalogKeyUse(text, start) || IsInsideLogCall(text, start)) continue;
                    if (AllowedLiterals.Any(allowed => allowed.File == name && literal.StartsWith(allowed.Starts, StringComparison.Ordinal))) continue;
                    var line = text.AsSpan(0, start).Count('\n') + 1;
                    found.Add($"{name}:{line}: \"{literal}\"");
                }
            }
        }
        Assert.True(found.Count == 0, "Koda gömülü Türkçe metin (kataloğa taşınmalı):\n" + string.Join("\n", found));
    }

    [Fact]
    public void EveryKeyUsedInXamlAndCodeIsInTheCatalog()
    {
        var catalog = Catalog();
        var missing = new SortedSet<string>(StringComparer.Ordinal);
        var used = 0;
        void Check(string key, string path)
        {
            used++;
            // Anahtar İngilizce metnin kendisidir: "en" girdisi olmayan (eski) kayıtlarda Localizer anahtarı döndürür, bu yüzden
            // İngilizce için kaydın var olması yeter; Türkçe değeri ise zorunludur.
            if (!catalog.TryGetValue(key, out var languages) || !languages.Contains("tr"))
                missing.Add($"{Path.GetFileName(path)}: {key}");
        }
        foreach (var path in Directory.EnumerateDirectories(Path.Combine(Root(), "windows", "src")).SelectMany(folder => Sources(Path.GetFileName(folder), ".xaml")))
            foreach (Match attribute in Regex.Matches(File.ReadAllText(path), @"=""(\{t:T\s[^""]*)"""))
            {
                var key = Regex.Match(WebUtility.HtmlDecode(attribute.Groups[1].Value), @"^\{t:T\s+'((?:[^'\\]|\\.)*)'\s*\}$", RegexOptions.Singleline);
                Assert.True(key.Success, $"{Path.GetFileName(path)}: okunamayan t:T kullanımı: {attribute.Groups[1].Value}");
                Check(Regex.Replace(key.Groups[1].Value, @"\\(.)", "$1", RegexOptions.Singleline), path);
            }
        // Localizer.Current["…"], text["…"], localizer["…"] ve .Format("…", …) (string.Format dışında)
        var inCode = new Regex(@"(?:(?:Localizer\.Current|\btext|\blocalizer|Localizer\.ForLanguage\([^)]*\))\s*\[|(?<!\bstring)(?<!\bString)\.Format\()\s*""((?:[^""\\]|\\.)*)""");
        foreach (var path in Directory.EnumerateDirectories(Path.Combine(Root(), "windows", "src")).SelectMany(folder => Sources(Path.GetFileName(folder), ".cs")))
            foreach (Match match in inCode.Matches(File.ReadAllText(path)))
                Check(Regex.Unescape(match.Groups[1].Value), path);
        Assert.True(used > 300, $"Yalnız {used} anahtar kullanımı bulundu; tarama bozulmuş olabilir.");
        Assert.True(missing.Count == 0, "Katalogda olmayan ya da Türkçesi eksik anahtarlar:\n" + string.Join("\n", missing));
    }

    private static Dictionary<string, HashSet<string>> Catalog()
    {
        using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(Root(), "mac", "GameTranslator", "Resources", "Localizable.xcstrings")));
        var catalog = new Dictionary<string, HashSet<string>>(StringComparer.Ordinal);
        foreach (var entry in document.RootElement.GetProperty("strings").EnumerateObject())
        {
            var languages = new HashSet<string>();
            if (entry.Value.TryGetProperty("localizations", out var localizations))
                foreach (var language in localizations.EnumerateObject())
                    if (language.Value.TryGetProperty("stringUnit", out var unit) && unit.TryGetProperty("value", out var value) && !string.IsNullOrEmpty(value.GetString()))
                        languages.Add(language.Name);
            catalog[entry.Name] = languages;
        }
        return catalog;
    }

    /// <summary>Metin katalog anahtarı olarak mı kullanılıyor: önünde <c>[</c> ya da <c>.Format(</c> var.</summary>
    private static bool IsCatalogKeyUse(string text, int start)
    {
        var before = text.AsSpan(0, start).TrimEnd();
        return before.EndsWith("[") || before.EndsWith(".Format(");
    }

    /// <summary>Metnin bulunduğu deyim (bir önceki <c>;</c> sonrasından metne kadar) bir günlük çağrısı mı.</summary>
    private static bool IsInsideLogCall(string text, int start)
    {
        var from = text.LastIndexOf(';', start) + 1;
        var statement = text.AsSpan(from, start - from);
        foreach (var call in LogCalls)
            if (statement.Contains(call, StringComparison.Ordinal)) return true;
        return false;
    }

    /// <summary>
    /// C# kaynağındaki metin sabitleri (yorumlar dışında): başlangıç konumu ve içeriği. $"…" metinlerinde süslü
    /// parantez içindeki ifadeler de içerikte kalır (içlerindeki metinler ayrıca dönmez); bu test için yeterlidir.
    /// </summary>
    private static IEnumerable<(int Start, string Literal)> StringLiterals(string text)
    {
        var i = 0;
        while (i < text.Length)
        {
            var c = text[i];
            if (c == '/' && i + 1 < text.Length && text[i + 1] == '/') { i = Next(text, "\n", i); continue; }
            if (c == '/' && i + 1 < text.Length && text[i + 1] == '*') { i = Next(text, "*/", i) + 2; continue; }
            if (c == '\'')
            {
                var close = text.IndexOf('\'', i + (i + 1 < text.Length && text[i + 1] == '\\' ? 3 : 2));
                i = close < 0 || close - i > 8 ? i + 1 : close + 1;
                continue;
            }
            var prefix = 0;
            while (i + prefix < text.Length && text[i + prefix] is '$' or '@') prefix++;
            if (i + prefix >= text.Length || text[i + prefix] != '"') { i += Math.Max(prefix, 1); continue; }
            var start = i;
            var verbatim = text.AsSpan(i, prefix).Contains('@');
            var interpolated = text.AsSpan(i, prefix).Contains('$');
            i += prefix;
            if (text.AsSpan(i).StartsWith("\"\"\""))
            {
                var end = text.IndexOf("\"\"\"", i + 3, StringComparison.Ordinal);
                end = end < 0 ? text.Length : end;
                yield return (start, text[(i + 3)..end]);
                i = Math.Min(text.Length, end + 3);
                continue;
            }
            i++;
            var content = new StringBuilder();
            var depth = 0;
            while (i < text.Length)
            {
                var ch = text[i];
                if (depth == 0)
                {
                    if (verbatim && ch == '"' && i + 1 < text.Length && text[i + 1] == '"') { content.Append('"'); i += 2; continue; }
                    if (!verbatim && ch == '\\' && i + 1 < text.Length) { content.Append(ch).Append(text[i + 1]); i += 2; continue; }
                    if (ch == '"') break;
                    if (interpolated && ch == '{') { if (i + 1 < text.Length && text[i + 1] == '{') { content.Append('{'); i += 2; continue; } depth = 1; }
                }
                else if (ch == '"')
                {
                    // İfadenin içindeki metin: kapanışına kadar olduğu gibi alınır.
                    content.Append(ch);
                    i++;
                    while (i < text.Length && text[i] != '"') { if (text[i] == '\\') content.Append(text[i++]); content.Append(text[i++]); }
                }
                else if (ch == '{') depth++;
                else if (ch == '}') depth--;
                content.Append(ch);
                i++;
            }
            yield return (start, content.ToString());
            i++;
        }
    }

    private static int Next(string text, string token, int from)
    {
        var at = text.IndexOf(token, from, StringComparison.Ordinal);
        return at < 0 ? text.Length : at;
    }
}
