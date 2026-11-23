// SPDX-License-Identifier: AGPL-3.0-only

using System.IO;
using System.Windows.Media;
using GameTranslator.Core;
using Microsoft.Win32;

namespace GameTranslator.App.Services;

/// <summary>
/// Bilgisayarda kurulu fontlar. Altyazı fontu uygulamaya gömülmez: kullanıcının kurduğu font bulunur, bilgisayardaki
/// altyazı pencereleri onu adıyla kullanır, TV / OBS / ikinci ekran ise dosyasını yerel sunucudan indirir.
/// </summary>
internal static class InstalledFonts
{
    private const string FontsKey = @"SOFTWARE\Microsoft\Windows NT\CurrentVersion\Fonts";

    /// <summary>Kurulu font aileleri, ada göre sıralı (font menüsü).</summary>
    public static IReadOnlyList<string> Families() =>
        [.. Fonts.SystemFontFamilies.Select(f => f.Source).Where(n => !string.IsNullOrWhiteSpace(n)).Distinct(StringComparer.OrdinalIgnoreCase).Order(StringComparer.CurrentCultureIgnoreCase)];

    /// <summary>
    /// Ayardaki fontun bilgisayardaki ailesi; null: standart yazı ya da font kurulu değil. Ad tam tutmazsa onunla
    /// başlayan aile alınır ("Pricedown" → "Pricedown Bl").
    /// </summary>
    public static FontFamily? Family(string? font)
    {
        if (SubtitleFonts.Family(font) is not { } name) return null;
        // Altyazı pencereleri her satırda sorar: kayıt defteri ve font listesi her seferinde taranmasın
        lock (Cache)
        {
            if (Cache.TryGetValue(name, out var known)) return known;
        }
        var found = Find(name);
        lock (Cache) Cache[name] = found;
        return found;
    }

    private static readonly Dictionary<string, FontFamily?> Cache = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Font kurulunca ya da menü açılınca yeniden aransın.</summary>
    public static void Forget()
    {
        lock (Cache) Cache.Clear();
    }

    private static FontFamily? Find(string name)
    {
        if (EmbeddedPath(name) is { } embedded) return Load(embedded);
        var families = Fonts.SystemFontFamilies.ToList();
        var exact = families.FirstOrDefault(f => string.Equals(f.Source, name, StringComparison.OrdinalIgnoreCase));
        if (exact is not null) return exact;
        return families.Where(f => f.Source.StartsWith(name + " ", StringComparison.OrdinalIgnoreCase)).MinBy(f => f.Source.Length)
               ?? FromFile(name);
    }

    /// <summary>
    /// WPF kurulu font listesini açılışta bir kez okur: uygulama açıkken kurulan font (Fontu indir ve kur) listede
    /// yoktur. O zaman font doğrudan dosyasından yüklenir.
    /// </summary>
    private static FontFamily? FromFile(string name) => FilePath(name) is { } path ? Load(path) : null;

    /// <summary>
    /// Gömülü font (Quicksand) uygulama klasörüne bir kez açılır; WPF fontu dosyadan yükler. Windows'a kurulmaz.
    /// </summary>
    private static string? EmbeddedPath(string family)
    {
        if (SubtitleFonts.EmbeddedFileName(family) is not { } fileName || SubtitleFonts.Embedded(family) is not { } file) return null;
        try
        {
            var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GameTranslator", "fonts");
            Directory.CreateDirectory(folder);
            var path = Path.Combine(folder, fileName);
            if (!System.IO.File.Exists(path) || new FileInfo(path).Length != file.Data.Length) System.IO.File.WriteAllBytes(path, file.Data);
            return path;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static FontFamily? Load(string path)
    {
        try
        {
            var family = new GlyphTypeface(new Uri(path)).FamilyNames.Values.FirstOrDefault();
            if (string.IsNullOrWhiteSpace(family)) return null;
            return new FontFamily(new Uri(Path.GetDirectoryName(path)!.TrimEnd('\\') + "\\"), "./" + Path.GetFileName(path) + "#" + family);
        }
        catch (Exception e) when (e is IOException or FileFormatException or UriFormatException or NotSupportedException)
        {
            return null;
        }
    }

    public static bool IsInstalled(string? font) => Family(font) is not null || SubtitleFonts.Family(font) is { } name && FilePath(name) is not null;

    /// <summary>Fontun dosyası (TV'ye gönderilir). Yazı tipi koleksiyonları (.ttc) tarayıcılarda açılmadığı için verilmez.</summary>
    public static FontFile? File(string family)
    {
        if (FilePath(family) is not { } path) return null;
        var type = Path.GetExtension(path).ToLowerInvariant() switch
        {
            ".ttf" => "font/ttf",
            ".otf" => "font/otf",
            _ => null,
        };
        if (type is null) return null;
        try { return new FontFile(System.IO.File.ReadAllBytes(path), type); }
        catch (IOException) { return null; }
        catch (UnauthorizedAccessException) { return null; }
    }

    /// <summary>
    /// Windows'un font kaydından dosya yolu. Kayıttaki adlar "Pricedown Bl (TrueType)", "Arial Bold (TrueType)" gibidir:
    /// tam tutan ad önce, sonra adla başlayanlardan "Regular" olan ya da en kısası.
    /// </summary>
    internal static string? FilePath(string family)
    {
        var entries = new List<(string Name, string File)>();
        foreach (var root in new[] { Registry.CurrentUser, Registry.LocalMachine })
        {
            try
            {
                using var key = root.OpenSubKey(FontsKey);
                if (key is null) continue;
                foreach (var value in key.GetValueNames())
                {
                    if (key.GetValue(value) is not string file || string.IsNullOrWhiteSpace(file)) continue;
                    var name = value.Contains('(') ? value[..value.IndexOf('(')] : value;
                    foreach (var part in name.Split('&', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
                        entries.Add((part, file));
                }
            }
            catch (System.Security.SecurityException) { }
            catch (IOException) { }
        }
        return Choose(entries, family);
    }

    internal static string? Choose(IEnumerable<(string Name, string File)> entries, string family)
    {
        var list = entries.ToList();
        var pick = list.FirstOrDefault(e => string.Equals(e.Name, family, StringComparison.OrdinalIgnoreCase)).File
                   ?? list.Where(e => e.Name.StartsWith(family + " ", StringComparison.OrdinalIgnoreCase))
                       .OrderBy(e => e.Name.Contains("Regular", StringComparison.OrdinalIgnoreCase) ? 0 : 1)
                       .ThenBy(e => e.Name.Length)
                       .Select(e => e.File)
                       .FirstOrDefault();
        if (pick is null) return null;
        var path = Path.IsPathRooted(pick) ? pick : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Fonts), pick);
        return System.IO.File.Exists(path) ? path : null;
    }
}
