// SPDX-License-Identifier: AGPL-3.0-only

using System.IO;
using GameTranslator.Core;
using GameTranslator.Translation;

namespace GameTranslator.App.Services;

/// <summary>
/// Yerel çevirinin ekran kartı desteği: NVIDIA'nın CUDA kütüphaneleri (<see cref="CudaRuntimeStore"/>) yalnız kullanıcı
/// "Ekran kartında çalıştır"ı seçince iner. İndirme durumu ayarlar ekranına buradan bildirilir.
/// Ölçüm (RTX 4060 Ti, aynı model): işlemcide cümle başına ~124 ms, ekran kartında ~32 ms.
/// </summary>
public static class LocalTranslation
{
    public static CudaRuntimeStore Cuda { get; } = new(Path.Combine(AppPaths.Models, "cuda12"));

    /// <summary>Hızlı yerel model (CTranslate2, 475 MB).</summary>
    public static Ct2ModelStore FastModel { get; } = new(Path.Combine(AppPaths.Models, "en-tr-ct2"));

    public static double ModelProgress { get; private set; }
    public static string? ModelError { get; private set; }
    private static Task? modelDownload;
    private static readonly object ModelGate = new();

    /// <summary>Hızlı model iniyor mu.</summary>
    public static bool ModelDownloading { get { lock (ModelGate) return modelDownload is { IsCompleted: false }; } }

    /// <summary>Hızlı model hazır mı (çalışma zamanı uygulamayla geldi ve model indi).</summary>
    public static bool FastModelReady => MarianTranslator.FastRuntimeAvailable && FastModel.IsInstalled;

    /// <summary>
    /// Hızlı model yoksa arka planda indirir (tek görev). Yerel modeli hiç indirmemiş kullanıcıda model kendiliğinden
    /// inmiyordu: çeviriler Google'ın ücretsiz çevirisine gidiyor, o da sık sık 4 sn'de zaman aşımına düşüyordu; ekran
    /// kartını açan kullanıcıya CUDA iniyor ama model olmadığı için hiç kullanılmıyordu.
    /// </summary>
    public static void StartModelDownload()
    {
        if (!MarianTranslator.FastRuntimeAvailable || FastModel.IsInstalled) return;
        lock (ModelGate)
        {
            if (modelDownload is { IsCompleted: false }) return;
            ModelError = null;
            ModelProgress = 0;
            SubtitleLog.Write($"hızlı çeviri modeli (CTranslate2, {Ct2ModelStore.TotalBytes / 1_000_000} MB) arka planda iniyor");
            modelDownload = FastModel.DownloadAsync(new Progress(value => { ModelProgress = value; CudaChanged?.Invoke(); }), CancellationToken.None);
            modelDownload.ContinueWith(done =>
            {
                ModelError = done.Exception?.GetBaseException().Message;
                SubtitleLog.Write(ModelError is null
                    ? "hızlı çeviri modeli indi - çeviri yeniden başlatılınca kullanılacak"
                    : $"hızlı çeviri modeli inemedi ({ModelError}) - sonraki açılışta yeniden denenir");
                CudaChanged?.Invoke();
            }, TaskScheduler.Default);
        }
        CudaChanged?.Invoke();
    }

    /// <summary>İndirme ilerlemesi (0–1) ya da bitti/başarısız olunca çağrılır (arka plan iş parçacığından).</summary>
    public static event Action? CudaChanged;

    public static double CudaProgress { get; private set; }
    public static string? CudaError { get; private set; }

    /// <summary>Ekran kartı seçilebilir mi: CTranslate2 çalışma zamanı uygulamayla geldi ve NVIDIA sürücüsü kurulu.</summary>
    public static bool GpuPossible => MarianTranslator.FastRuntimeAvailable && CudaRuntimeStore.NvidiaDriverPresent;

    /// <summary>CUDA kütüphaneleri hazırsa klasörü; değilse null (çeviri işlemcide).</summary>
    public static string? CudaDirectoryIfReady => GpuPossible && Cuda.IsInstalled ? Cuda.InstallDirectory : null;

    /// <summary>Kütüphaneler yoksa indirmeyi başlatır (tek görev paylaşılır); günlüğe ve ayarlar ekranına bildirir.</summary>
    public static void StartCudaDownload()
    {
        if (!GpuPossible || Cuda.IsInstalled || Cuda.Downloading) return;
        CudaError = null;
        CudaProgress = 0;
        SubtitleLog.Write($"ekran kartı dosyaları (NVIDIA CUDA, {Cuda.DownloadBytes / 1_000_000} MB) iniyor");
        var task = Cuda.StartDownload(new Progress(value => { CudaProgress = value; CudaChanged?.Invoke(); }));
        CudaChanged?.Invoke();
        _ = task.ContinueWith(done =>
        {
            CudaError = done.Exception?.GetBaseException().Message;
            SubtitleLog.Write(CudaError is null
                ? "ekran kartı dosyaları indi - çeviri yeniden başlatılınca ekran kartında çalışacak"
                : $"ekran kartı dosyaları inemedi ({CudaError}) - çeviri işlemcide sürüyor");
            CudaChanged?.Invoke();
        }, TaskScheduler.Default);
    }

    /// <summary>Arka plan iş parçacığından da güvenle raporlayan ilerleme (Progress&lt;T&gt; arayüz bağlamını yakalar).</summary>
    private sealed class Progress(Action<double> report) : IProgress<double>
    {
        public void Report(double value) => report(value);
    }
}
