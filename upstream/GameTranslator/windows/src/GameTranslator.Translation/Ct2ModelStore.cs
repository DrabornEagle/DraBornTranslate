// SPDX-License-Identifier: AGPL-3.0-only

using System.IO.Compression;

namespace GameTranslator.Translation;

/// <summary>
/// OPUS-MT tc-big en-tr'nin CTranslate2 dönüştürümü (Helsinki-NLP, CC-BY-4.0; ağırlıklar değişmeden float16'ya
/// dönüştürülmüş). CouchSubs'ın kullandığı arşivin aynısı: sabit revizyondan SHA-256 ile doğrulanır; açılmamış ya da
/// yarım açılmış model kurulu sayılmaz. Belirteçleyici sözlüğü (vocab.json) özgün OPUS-MT deposundan gelir.
/// </summary>
public sealed class Ct2ModelStore
{
    private const string ArchiveName = "opus-mt-en-tr.zip";
    private const string ArchiveUrl = "https://huggingface.co/Unbakedcookie/live-translator-models/resolve/50473df5681764ebdef107dd78f7d21da66c28a7/opus-mt-en-tr.zip";
    private const long ArchiveSize = 474_595_937;
    private const string ArchiveSha = "acabcdc3becfbf4d740c3012d6dc4e8a1ba6ed41a62a125c37f6c228667ff9b9";
    private static readonly string[] Required = ["model.bin", "config.json", ModelStore.SourceSpmFile, ModelStore.VocabFile];
    private readonly ModelStore archive;
    private readonly ModelStore tokenizer;
    private readonly SemaphoreSlim gate = new(1, 1);

    public Ct2ModelStore(string installDirectory)
    {
        InstallDirectory = installDirectory;
        archive = new ModelStore(installDirectory, [new ModelFile(ArchiveName, new Uri(ArchiveUrl), ArchiveSize, ArchiveSha)]);
        tokenizer = new ModelStore(installDirectory, ModelStore.EnglishTurkish.Where(file => file.Name == ModelStore.VocabFile).ToArray());
    }

    public string InstallDirectory { get; }

    /// <summary>İndirilecek toplam boyut (arşiv ve sözlük).</summary>
    public static long TotalBytes => ArchiveSize + ModelStore.EnglishTurkish.Where(file => file.Name == ModelStore.VocabFile).Sum(file => file.Size);

    public bool IsInstalled => Required.All(name => File.Exists(Path.Combine(InstallDirectory, name)))
        && (File.Exists(Path.Combine(InstallDirectory, "shared_vocabulary.json"))
            || File.Exists(Path.Combine(InstallDirectory, "source_vocabulary.json"))
               && File.Exists(Path.Combine(InstallDirectory, "target_vocabulary.json")));

    public async Task DownloadAsync(IProgress<double>? progress, CancellationToken ct)
    {
        await gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (IsInstalled) { progress?.Report(1); return; }
            await archive.DownloadAsync(progress, ct).ConfigureAwait(false);
            await tokenizer.DownloadAsync(null, ct).ConfigureAwait(false);
            using (var zip = ZipFile.OpenRead(archive.PathOf(ArchiveName)))
            {
                var names = new HashSet<string>(StringComparer.Ordinal)
                    { "model.bin", "config.json", "shared_vocabulary.json", "source_vocabulary.json", "target_vocabulary.json",
                      ModelStore.SourceSpmFile, "target.spm", "opus_mt.json", "LICENCE.txt" };
                foreach (var entry in zip.Entries.Where(e => names.Contains(Path.GetFileName(e.FullName))))
                {
                    ct.ThrowIfCancellationRequested();
                    var target = Path.Combine(InstallDirectory, Path.GetFileName(entry.FullName));
                    var part = target + ".part";
                    await using (var input = entry.Open())
                    await using (var output = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None))
                        await input.CopyToAsync(output, ct).ConfigureAwait(false);
                    File.Move(part, target, overwrite: true);
                }
            }
            if (!IsInstalled) throw new InvalidDataException("CTranslate2 archive lacks model or vocabulary files");
            File.Delete(archive.PathOf(ArchiveName));
            progress?.Report(1);
        }
        finally { gate.Release(); }
    }
}
