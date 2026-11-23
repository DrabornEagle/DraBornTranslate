// SPDX-License-Identifier: AGPL-3.0-only

using System.Text;
using System.Text.RegularExpressions;

namespace GameTranslator.Core;

/// <summary>
/// Xbox'ı bilgisayara aktaran pencerenin tanınması. Xbox'ta PS5'teki gibi kendi bağlantımız yok; oyun, Xbox uygulamasında
/// (uzaktan oynatma / bulut) ya da tarayıcıda (xbox.com/play) oynatılır, görüntü o pencereden yakalanır. Burada yalnız
/// pencere başlığı ve program adına bakılır (saf, sınanabilir); pencereleri dolaşan kod uygulama katmanındadır.
/// </summary>
public static partial class XboxWindow
{
    /// <summary>Görüntü kaynağı ayarında "Xbox penceresini kendin bul" anlamına gelen hedef.</summary>
    public const string Target = "xbox:";

    private static readonly string[] Browsers = ["msedge", "chrome", "firefox", "brave", "opera", "vivaldi", "msedgewebview2"];
    /// <summary>Xbox akışını gösteren bilinen programlar (Microsoft'un uygulaması ve yaygın açık kaynak istemciler).</summary>
    private static readonly string[] Apps = ["xboxpcapp", "xbox", "greenlight", "xstreaming", "xbplay"];
    private static readonly string[] StreamWords = ["xbox cloud gaming", "xbox remote play", "xbox.com/play", "on xbox.com", "xcloud"];
    private static readonly string[] Excluded = ["xbox game bar", "game bar", "xbox accessories", "xbox console companion", "xbox insider"];

    /// <summary>
    /// Pencerenin Xbox akışı olma puanı; 0 ise değil. Tarayıcıda yalnız başlığı Xbox oynatma sayfasını gösteren sekme
    /// sayılır (başlığında "Xbox" geçen haber sayfası değil). En yüksek puanlı pencere seçilir.
    /// </summary>
    public static int Score(string? title, string? process)
    {
        var name = Plain(title).ToLowerInvariant();
        var program = (process ?? "").Trim().ToLowerInvariant();
        if (program.EndsWith(".exe", StringComparison.Ordinal)) program = program[..^4];
        if (name.Length == 0) return 0;
        if (Excluded.Any(name.Contains) || program.Contains("gamebar", StringComparison.Ordinal)) return 0;

        // "Play XBOX": uzaktan oynatma sayfasının (play.xbox.com) başlığı; yalnız başta sayılır ("how to play xbox…" değil)
        var stream = StreamWords.Any(name.Contains) || name == "play xbox" || name.StartsWith("play xbox ", StringComparison.Ordinal) && name.Length < 40;
        if (Browsers.Contains(program)) return stream ? 80 : 0;
        if (Apps.Contains(program)) return stream ? 90 : 60;
        // Başka bir program (ör. tarayıcıdan "uygulama olarak yüklenen" sayfa) ama başlık akışı gösteriyor
        return stream ? 70 : 0;
    }

    /// <summary>
    /// Pencere başlığından oyunun adı ("Forza Horizon 5 | Xbox Cloud Gaming (Beta) on Xbox.com - Microsoft Edge" →
    /// "Forza Horizon 5"); başlıkta oyun adı yoksa (yalnız "Xbox", "Xbox Cloud Gaming"…) null.
    /// </summary>
    public static string? GameName(string? title)
    {
        var text = Plain(title);
        if (text.Length == 0) return null;
        text = BrowserSuffix().Replace(text, "");
        text = NotificationCount().Replace(text, "");
        // Oyun adı ilk parçadadır; gerisi site ve hizmet adı
        var first = text.Split(" | ", 2, StringSplitOptions.TrimEntries)[0];
        first = ServiceTail().Replace(first, "").Trim();
        // Sitenin kendi sayfaları ("Xbox.com'da Xbox Cloud Gaming", çevrilmiş başlıklar): hizmet adları çıkınca oyun kalmaz
        first = string.Join(' ', ServiceWords().Replace(first, " ").Split(' ', StringSplitOptions.RemoveEmptyEntries));
        if (first.StartsWith("Play ", StringComparison.OrdinalIgnoreCase)) first = first[5..].Trim();
        first = first.Trim(' ', '-', '–', '—', '|', ':');
        if (first.Length < 2) return null;
        var lower = first.ToLowerInvariant();
        if (Generic().IsMatch(lower)) return null;
        return first.Length > RunningGame.MaxName ? first[..RunningGame.MaxName].Trim() : first;
    }

    /// <summary>Görünmeyen biçim karakterleri (Edge başlığa sıfır genişlikli boşluk koyar) atılır, boşluklar teke iner.</summary>
    private static string Plain(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return "";
        var builder = new StringBuilder(value.Length);
        foreach (var rune in value.EnumerateRunes())
        {
            var category = Rune.GetUnicodeCategory(rune);
            if (category is System.Globalization.UnicodeCategory.Format) continue;
            builder.Append(category is System.Globalization.UnicodeCategory.Control ? " " : rune.ToString());
        }
        return string.Join(' ', builder.ToString().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
    }

    [GeneratedRegex(@"\s[-–—]\s(?:[^-–—|]*\s[-–—]\s)?(?:Microsoft Edge|Google Chrome|Mozilla Firefox|Firefox|Brave|Opera|Vivaldi|Chromium)\s*$", RegexOptions.IgnoreCase)]
    private static partial Regex BrowserSuffix();

    [GeneratedRegex(@"^\(\d+\+?\)\s*")]
    private static partial Regex NotificationCount();

    [GeneratedRegex(@"\s*(?:[-–—:]\s*)?(?:Xbox Cloud Gaming|Xbox Remote Play)(?:\s*\(Beta\))?(?:\s+on\s+Xbox\.com)?\s*$", RegexOptions.IgnoreCase)]
    private static partial Regex ServiceTail();

    [GeneratedRegex(@"Xbox\.com\S*|Xbox Cloud Gaming|Xbox Remote Play|\(Beta\)", RegexOptions.IgnoreCase)]
    private static partial Regex ServiceWords();

    [GeneratedRegex(@"^(?:xbox|xbox app|xbox pc|xbox cloud gaming(?: \(beta\))?|xbox remote play|cloud gaming|remote play|xcloud|play xbox|home|ana sayfa|new tab|yeni sekme|greenlight|xstreaming)(?: on xbox\.com)?$")]
    private static partial Regex Generic();
}
