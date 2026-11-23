// SPDX-License-Identifier: AGPL-3.0-only

using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace GameTranslator.Translation;

/// <summary>
/// Aynı OPUS-MT tc-big en-tr modelini CTranslate2 ile (işlemcide int8) çalıştırır. CouchSubs'ın Windows motorundan
/// uyarlandı (#150, #176, #240; ekran kartı yolu alınmadı). Uygulamayla gelen gömülü Python/CTranslate2 çalışma
/// zamanı (uygulama klasöründe "ct2") kalıcı bir alt süreçte tutulur; JSON satırları yalnız yerel stdin/stdout'tan
/// geçer, her altyazıda süreç ya da model yeniden açılmaz.
/// Ölçüm (bu modelle, 4 ışın, 2 iş parçacığı, 12 çekirdekli bilgisayar): ONNX Runtime'da cümle başına ortalama 423 ms
/// (işlemci doluyken 793 ms), CTranslate2'de 110 ms (doluyken 108 ms).
/// </summary>
internal sealed class Ct2MarianEngine : ISentenceEngine
{
    private readonly MarianTokenizer tokenizer;
    private readonly string modelDirectory;
    private readonly int maxOutputTokens;
    private readonly int beams;
    private readonly int threads;
    private readonly string? cudaDirectory;
    private readonly Action<string>? log;
    private Process worker;
    private bool disposed;

    /// <summary>Şu an ekran kartında mı çalışıyor (seçildiyse ve CUDA açılabildiyse).</summary>
    public bool GpuActive { get; private set; }

    /// <summary>Çalışma zamanının klasörü (geliştirmede GAMETRANSLATOR_CT2_RUNTIME ile değiştirilebilir).</summary>
    public static string RuntimeDirectory =>
        Environment.GetEnvironmentVariable("GAMETRANSLATOR_CT2_RUNTIME") ?? Path.Combine(AppContext.BaseDirectory, "ct2");

    /// <summary>Uygulamayla birlikte CTranslate2 çalışma zamanı geldi mi (kaynak koddan derlenen kopyada olmayabilir).</summary>
    public static bool RuntimeAvailable =>
        File.Exists(Path.Combine(RuntimeDirectory, "python.exe")) && File.Exists(Path.Combine(RuntimeDirectory, "worker.py"));

    /// <param name="cudaDirectory">Ekran kartında çalışsın: NVIDIA'nın CUDA kütüphanelerinin klasörü (<see cref="CudaRuntimeStore"/>).
    /// Verilmezse ya da CUDA açılamazsa işlemcide çalışır.</param>
    public Ct2MarianEngine(string modelDirectory, int maxOutputTokens, int beams, int threads = 2, string? cudaDirectory = null,
        Action<string>? log = null)
    {
        this.modelDirectory = modelDirectory;
        this.maxOutputTokens = maxOutputTokens;
        this.beams = Math.Max(1, beams);
        this.threads = Math.Clamp(threads, 1, Math.Max(1, Environment.ProcessorCount));
        this.cudaDirectory = cudaDirectory;
        this.log = log;
        tokenizer = MarianTokenizer.Load(Path.Combine(modelDirectory, ModelStore.SourceSpmFile),
            Path.Combine(modelDirectory, ModelStore.VocabFile));
        if (cudaDirectory is not null)
        {
            try
            {
                worker = Start(gpu: true);
                GpuActive = true;
                return;
            }
            catch (Exception error)
            {
                log?.Invoke($"çeviri motoru: ekran kartı açılamadı ({error.Message}); işlemcide çalışacak");
            }
        }
        worker = Start(gpu: false);
    }

    private Process Start(bool gpu)
    {
        var runtime = RuntimeDirectory;
        var python = Path.Combine(runtime, "python.exe");
        var script = Path.Combine(runtime, "worker.py");
        if (!File.Exists(python) || !File.Exists(script))
            throw new FileNotFoundException("CTranslate2 runtime is missing", !File.Exists(python) ? python : script);
        var info = new ProcessStartInfo(python)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardInputEncoding = new UTF8Encoding(false),
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            WorkingDirectory = runtime,
        };
        info.Environment["PYTHONIOENCODING"] = "utf-8";
        // Kullanıcının kurulu Python'u ya da ortam değişkenleri gömülü çalışma zamanına karışmasın
        info.Environment.Remove("PYTHONHOME");
        info.Environment.Remove("PYTHONPATH");
        info.ArgumentList.Add("-u");
        info.ArgumentList.Add("-I");
        // Yalıtılmış kipte PYTHONIOENCODING okunmaz; UTF-8 kipi açıkça istenir (betik de giriş/çıkışı UTF-8 yapar)
        info.ArgumentList.Add("-X");
        info.ArgumentList.Add("utf8");
        info.ArgumentList.Add(script);
        info.ArgumentList.Add(modelDirectory);
        info.ArgumentList.Add(gpu ? "cuda" : "cpu");
        info.ArgumentList.Add((gpu ? 2 : threads).ToString(System.Globalization.CultureInfo.InvariantCulture));
        if (gpu && cudaDirectory is not null) info.ArgumentList.Add(cudaDirectory);
        var process = Process.Start(info) ?? throw new IOException("CTranslate2 worker did not start");
        process.ErrorDataReceived += (_, e) => { if (e.Data is not null) Debug.WriteLine("CTranslate2: " + e.Data); };
        process.BeginErrorReadLine();
        try
        {
            // Çalışma zamanı ya da model düzgün değilse süreç hemen kapanır ya da hata satırı yazar
            var line = process.StandardOutput.ReadLine() ?? throw new IOException("CTranslate2 worker exited during startup");
            using var response = JsonDocument.Parse(line);
            if (!response.RootElement.TryGetProperty("ready", out var ready) || !ready.GetBoolean())
                throw new IOException(response.RootElement.TryGetProperty("error", out var error) ? error.GetString() : line);
            return process;
        }
        catch
        {
            Stop(process);
            throw;
        }
    }

    public string Translate(string sentence, CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        cancellationToken.ThrowIfCancellationRequested();
        try
        {
            return TranslateCurrent(sentence, cancellationToken);
        }
        catch (Exception error) when (!disposed && GpuActive && error is not OperationCanceledException)
        {
            // Ekran kartında hata (bellek yetmedi, sürücü sıfırlandı): işlemciye geçilir, satır orada çevrilir
            log?.Invoke($"çeviri motoru: ekran kartında hata ({error.Message}); işlemciye geçildi");
            Stop(worker);
            GpuActive = false;
            worker = Start(gpu: false);
            return TranslateCurrent(sentence, cancellationToken);
        }
        catch (IOException) when (!disposed)
        {
            // Alt süreç kapandıysa (çöktü, sonlandırıldı) bir kez yeniden açılır; yine olmazsa hata çevirmene gider
            Stop(worker);
            worker = Start(gpu: false);
            return TranslateCurrent(sentence, cancellationToken);
        }
    }

    private string TranslateCurrent(string sentence, CancellationToken cancellationToken)
    {
        var tokens = tokenizer.EncodeTokens(sentence);
        // ONNX hattıyla aynı kaynak sınırı; son belirteç EOS kalır
        if (tokens.Length > 256) tokens = [.. tokens.Take(255), "</s>"];
        var request = JsonSerializer.Serialize(new { tokens, beams, max_output = Math.Min(maxOutputTokens, 2 * tokens.Length + 16) });
        worker.StandardInput.WriteLine(request);
        worker.StandardInput.Flush();
        var line = worker.StandardOutput.ReadLine() ?? throw new IOException("CTranslate2 worker exited during translation");
        using var response = JsonDocument.Parse(line);
        // Tek satırın hatası (süreç ayakta): yalnız o satır çevrilemez, motor kapatılmaz
        if (response.RootElement.TryGetProperty("error", out var error))
            throw new InvalidOperationException("CTranslate2: " + error.GetString());
        var output = response.RootElement.GetProperty("tokens").EnumerateArray().Select(value => value.GetString() ?? "");
        cancellationToken.ThrowIfCancellationRequested();
        return tokenizer.DecodeTokens(output);
    }

    private static void Stop(Process process)
    {
        try
        {
            if (!process.HasExited) process.Kill(entireProcessTree: true);
            process.WaitForExit(2000);
        }
        catch (InvalidOperationException) { }
        finally { process.Dispose(); }
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        Stop(worker);
    }
}
