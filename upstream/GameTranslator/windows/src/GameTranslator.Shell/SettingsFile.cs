// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.Json;

namespace GameTranslator.Shell;

/// <summary>
/// Kullanıcı ayarlarının dosyası. Güncellemede ve beklenmedik bir hatada hiçbir ayar kaybolmasın diye:
/// <list type="bullet">
/// <item>Yazma önce geçici dosyaya yapılır, sonra tek adımda yerine konur; güncelleme uygulamayı yazarken kapatsa da
/// dosya yarım kalmaz (eskiden yarım dosya okunamayınca bütün ayarlar varsayılana dönüyor, ilk kayıtta da üstüne yazılıyordu).</item>
/// <item>Dosya o an kilitliyse (virüs tarayıcı, kapanmakta olan eski sürüm) okuma birkaç kez yeniden denenir.</item>
/// <item>Her başarılı açılışta son sağlam hâli <c>.bak</c> olarak saklanır; ana dosya okunamazsa oradan dönülür,
/// okunamayan dosya da silinmez, yanına <c>.broken-…</c> adıyla kopyalanır.</item>
/// </list>
/// Biçim değişmez (System.Text.Json varsayılanları): eski sürümlerin yazdığı dosya olduğu gibi okunur.
/// </summary>
public sealed class SettingsFile(string path, Action<string>? log = null)
{
    private Action<string>? Log { get; } = log;
    private const int Attempts = 5;
    private static readonly TimeSpan RetryDelay = TimeSpan.FromMilliseconds(150);

    public string Path { get; } = path;
    private string BackupPath => Path + ".bak";
    private string TemporaryPath => Path + ".tmp";

    /// <summary>Ayarlar; hiç ayar dosyası yoksa (ilk kurulum) null.</summary>
    public UserSettings? Load()
    {
        if (!File.Exists(Path) && !File.Exists(BackupPath) && !File.Exists(TemporaryPath)) return null;
        if (TryRead(Path, out var settings, out var broken))
        {
            Backup();
            return settings;
        }
        if (broken) KeepBroken();
        // Yazma yarıda kaldıysa geçici dosya sağlam olabilir; yoksa son sağlam yedek
        foreach (var fallback in new[] { TemporaryPath, BackupPath })
        {
            if (TryRead(fallback, out settings, out _))
            {
                Log?.Invoke($"ayarlar: {System.IO.Path.GetFileName(Path)} okunamadı, {System.IO.Path.GetFileName(fallback)} dosyasından geri yüklendi");
                return settings;
            }
        }
        Log?.Invoke("ayarlar: ayar dosyası ve yedeği okunamadı - varsayılanlarla açıldı (okunamayan dosya .broken olarak saklandı)");
        return new UserSettings();
    }

    public void Save(UserSettings settings)
    {
        var json = JsonSerializer.Serialize(settings);
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
                File.WriteAllText(TemporaryPath, json);
                File.Move(TemporaryPath, Path, overwrite: true);
                return;
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                if (attempt >= Attempts)
                {
                    Log?.Invoke($"ayarlar kaydedilemedi ({error.Message})");
                    return;
                }
                Thread.Sleep(RetryDelay);
            }
        }
    }

    /// <param name="broken">Dosya okundu ama içi bozuk (yarım, boş ya da tanınmayan biçim).</param>
    private static bool TryRead(string file, out UserSettings? settings, out bool broken)
    {
        settings = null;
        broken = false;
        if (!File.Exists(file)) return false;
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                var text = File.ReadAllText(file);
                settings = JsonSerializer.Deserialize<UserSettings>(text);
                broken = settings is null;
                return settings is not null;
            }
            catch (JsonException)
            {
                broken = true;
                return false;
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                if (attempt >= Attempts) return false;
                Thread.Sleep(RetryDelay);
            }
        }
    }

    private void Backup()
    {
        try { File.Copy(Path, BackupPath, overwrite: true); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }

    private void KeepBroken()
    {
        try { File.Copy(Path, $"{Path}.broken-{DateTime.Now:yyyyMMdd-HHmmss}", overwrite: true); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }
}
