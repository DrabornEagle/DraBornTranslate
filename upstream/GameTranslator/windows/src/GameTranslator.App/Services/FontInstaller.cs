// SPDX-License-Identifier: AGPL-3.0-only

using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Windows.Media;
using Microsoft.Win32;

namespace GameTranslator.App.Services;

/// <summary>
/// "Fontu indir ve kur": fontun zip'i yayımlandığı yerden indirilir ve içindeki .ttf/.otf yalnız bu kullanıcı için
/// kurulur (yönetici izni gerekmez; Windows'un Ayarlar → Yazı tipleri'nde görünür, oradan kaldırılabilir). Font dosyası
/// uygulamayla dağıtılmaz; kullanıcının isteğiyle kendi bilgisayarına iner.
/// </summary>
internal static class FontInstaller
{
    private const string FontsKey = @"SOFTWARE\Microsoft\Windows NT\CurrentVersion\Fonts";

    /// <summary>Zip'i indirir, fontları kurar; kurulan dosyaların yolları döner (boşsa zip'te font yoktu).</summary>
    public static async Task<IReadOnlyList<string>> DownloadAndInstallAsync(string url, CancellationToken cancel = default)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("GameTranslator");
        var data = await http.GetByteArrayAsync(url, cancel);
        return await Task.Run(() => InstallZip(data), cancel);
    }

    internal static IReadOnlyList<string> InstallZip(byte[] zipData)
    {
        var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Microsoft", "Windows", "Fonts");
        Directory.CreateDirectory(folder);
        var installed = new List<string>();
        using var zip = new ZipArchive(new MemoryStream(zipData), ZipArchiveMode.Read);
        foreach (var entry in zip.Entries)
        {
            var extension = Path.GetExtension(entry.Name).ToLowerInvariant();
            if (extension is not (".ttf" or ".otf") || entry.FullName.Contains("__MACOSX", StringComparison.OrdinalIgnoreCase)) continue;
            var target = Path.Combine(folder, Path.GetFileName(entry.Name));
            try { entry.ExtractToFile(target, overwrite: true); }
            // Font zaten kurulu ve kullanımdaysa üstüne yazılamaz; var olanla devam edilir
            catch (IOException) when (File.Exists(target)) { }
            var name = FaceName(target) ?? Path.GetFileNameWithoutExtension(target);
            using (var key = Registry.CurrentUser.CreateSubKey(FontsKey))
                key.SetValue($"{name} ({(extension == ".otf" ? "OpenType" : "TrueType")})", target);
            AddFontResource(target);
            installed.Add(target);
        }
        if (installed.Count > 0) SendMessageTimeout(HwndBroadcast, WmFontChange, 0, 0, 2, 1000, out _);
        return installed;
    }

    /// <summary>Fontun adı ("Pricedown Bl" gibi değil, dosyanın kendi ailesi ve stili: "Pricedown", "Arial Bold").</summary>
    private static string? FaceName(string path)
    {
        try
        {
            var glyphs = new GlyphTypeface(new Uri(path));
            var family = glyphs.FamilyNames.Values.FirstOrDefault();
            var face = glyphs.FaceNames.Values.FirstOrDefault();
            if (string.IsNullOrWhiteSpace(family)) return null;
            return string.IsNullOrWhiteSpace(face) || face is "Regular" or "Normal" ? family : $"{family} {face}";
        }
        catch (Exception e) when (e is IOException or FileFormatException or UriFormatException or NotSupportedException)
        {
            return null;
        }
    }

    private static readonly IntPtr HwndBroadcast = new(0xFFFF);
    private const uint WmFontChange = 0x001D;

    [DllImport("gdi32.dll", CharSet = CharSet.Unicode)]
    private static extern int AddFontResource(string fileName);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint msg, nint wParam, nint lParam, uint flags, uint timeout, out nint result);
}
