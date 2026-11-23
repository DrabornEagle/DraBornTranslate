// SPDX-License-Identifier: AGPL-3.0-only

using System.IO;

namespace GameTranslator.App.Services;

/// <summary>
/// Sürüm duyurusu: ana ekran ilk açıldığında bir kez gösterilir (kurulum sihirbazı bittikten sonra). Gösterildiği
/// %LOCALAPPDATA%\GameTranslator altındaki bir işaret dosyasıyla hatırlanır. Yeni duyuru için <see cref="Id"/> değişir.
/// Yalnız çeviri dili Türkçe olana gösterilir: GTA 6 paketi Türkçedir.
/// </summary>
public static class ReleaseNotice
{
    private const string Id = "2026-11-paket-sunucusu";

    private static string Flag => Path.Combine(AppPaths.Data, $"notice-{Id}.seen");

    public static bool ShouldShow(string targetLanguage) =>
        string.Equals(targetLanguage, "tr", StringComparison.OrdinalIgnoreCase) && !File.Exists(Flag);

    /// <summary>Gösterilmeden önce yazılır: pencere açıkken uygulama kapansa da bir daha çıkmaz.</summary>
    public static void MarkSeen()
    {
        try
        {
            Directory.CreateDirectory(AppPaths.Data);
            File.WriteAllText(Flag, DateTimeOffset.UtcNow.ToString("o"));
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
