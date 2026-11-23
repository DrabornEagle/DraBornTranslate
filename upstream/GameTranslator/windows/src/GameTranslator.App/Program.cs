// SPDX-License-Identifier: AGPL-3.0-only

using Velopack;

namespace GameTranslator.App;

public static class Program
{
    /// <summary>
    /// Velopack kurulum, güncelleme ve kaldırma sırasında uygulamayı kısa süreli çağırır; bu kancalar WPF başlamadan
    /// işlenip çıkılır. Normal açılışta hiçbir şey yapmadan döner.
    /// </summary>
    [STAThread]
    public static void Main(string[] args)
    {
        try { VelopackApp.Build().Run(); } catch { }

        // Güncelleme sınaması (pencere açılmaz): --update-check <dosya> yeni sürüm var mı yazar; --update-install <dosya>
        // indirip kurar ve yeniden başlatmadan çıkar. Sonuç dosyaya yazılır (pencere uygulamasının konsol çıktısı yoktur).
        if (args is ["--update-check" or "--update-install", var output])
        {
            string result;
            try { result = new Services.Updater().RunFromCommandLineAsync(args[0] == "--update-install").GetAwaiter().GetResult(); }
            catch (Exception error) { result = $"error: {error.GetType().Name}: {error.Message}"; }
            try { System.IO.File.WriteAllText(output, result); } catch { }
            return;
        }

        // Eğer arka planda takılı kalmış eski bir kopya varsa port 8787'yi ve TV bağlantısını kilitlemesin diye sonlandır
        // (Geliştirme araçları (--replay, --repair) kurulu uygulamanın yanında çalışır; ona dokunmaz.)
        if (args is not (["--replay", ..] or ["--repair", ..] or ["--render-screens", ..])) try
        {
            var current = System.Diagnostics.Process.GetCurrentProcess();
            foreach (var p in System.Diagnostics.Process.GetProcessesByName("GameTranslator"))
            {
                if (p.Id != current.Id)
                {
                    try { p.Kill(); } catch { }
                }
            }
        }
        catch { }

        var app = new App();
        app.InitializeComponent();
        app.Run();
    }
}
