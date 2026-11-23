// SPDX-License-Identifier: AGPL-3.0-only

using System.Diagnostics;
using GameTranslator.Shell;

namespace GameTranslator.App.Services;

/// <summary>Uygulamanın sürümü ve dış bağlantıları: pencere başlığı, ana ekran, Ayarlar ve Hakkında aynı yerden okur.</summary>
public static class AppInfo
{
    /// <summary>Sürüm numarası ("1.7.4"); derlemedeki "+commit" eki yazılmaz.</summary>
    public static string Version { get; } = AppVersion.Current(typeof(AppInfo).Assembly);

    /// <summary>Kaynak kodun ve sürümlerin yayımlandığı yer.</summary>
    public const string SourceUrl = "https://gitlab.com/sefflex/GameTranslator";

    /// <summary>Gönüllü destek sayfası (uygulama ücretsiz ve açık kaynak).</summary>
    public const string SupportUrl = "https://buymeacoffee.com/gametranslator";

    /// <summary>Adresi varsayılan tarayıcıda açar; açılamazsa sessizce geçer.</summary>
    public static void Open(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (Exception) { }
    }
}
