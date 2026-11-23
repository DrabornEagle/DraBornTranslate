// SPDX-License-Identifier: AGPL-3.0-only

using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace GameTranslator.App.Services;

/// <summary>
/// Hesap kimliği, PS5 anahtarları ve TV anahtarı. Windows'un veri koruması (DPAPI, yalnız bu kullanıcı)
/// ile şifrelenmiş tek bir dosyada tutulur.
/// </summary>
public sealed class SecureStore(string path)
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("gametranslator-secure-store");
    private readonly object gate = new();

    public static SecureStore Default { get; } = new(Path.Combine(AppPaths.Data, "secure.dat"));

    public void Save<T>(string key, T value)
    {
        lock (gate)
        {
            var all = ReadAll();
            all[key] = JsonSerializer.SerializeToNode(value);
            WriteAll(all);
        }
    }

    public T? Load<T>(string key)
    {
        lock (gate)
        {
            var node = ReadAll()[key];
            if (node is null) return default;
            try { return node.Deserialize<T>(); } catch (JsonException) { return default; }
        }
    }

    public void Remove(string key)
    {
        lock (gate)
        {
            var all = ReadAll();
            if (all.Remove(key)) WriteAll(all);
        }
    }

    private JsonObject ReadAll()
    {
        if (!File.Exists(path) && !File.Exists(path + ".bak")) return [];
        // Ana dosya okunamazsa (yarım yazılmış, bozuk) son sağlam yedeğe dönülür; eskiden boş sayılıyor, ilk kayıtta
        // da PS5 eşleşmesi, TV anahtarı ve hesap bilgisi tek anahtarla ezilip kayboluyordu.
        foreach (var file in new[] { path, path + ".bak" })
        {
            if (TryRead(file) is { } all) return all;
        }
        // Başka kullanıcıdan kopyalanmış ya da bozulmuş dosya: kurulum baştan yapılır (bozuk dosya yine de saklanır).
        try { if (File.Exists(path)) File.Copy(path, $"{path}.broken-{DateTime.Now:yyyyMMdd-HHmmss}", overwrite: true); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
        return [];
    }

    private static JsonObject? TryRead(string file)
    {
        for (var attempt = 1; File.Exists(file); attempt++)
        {
            try
            {
                var plain = ProtectedData.Unprotect(File.ReadAllBytes(file), Entropy, DataProtectionScope.CurrentUser);
                return JsonNode.Parse(plain) as JsonObject;
            }
            catch (Exception error) when (error is CryptographicException or JsonException) { return null; }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                // Dosya o an kilitli (virüs tarayıcı, kapanmakta olan eski sürüm): kısa aralıkla yeniden denenir
                if (attempt >= 5) return null;
                Thread.Sleep(150);
            }
        }
        return null;
    }

    private void WriteAll(JsonObject all)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var cipher = ProtectedData.Protect(Encoding.UTF8.GetBytes(all.ToJsonString()), Entropy, DataProtectionScope.CurrentUser);
        var temporary = path + ".tmp";
        File.WriteAllBytes(temporary, cipher);
        // Önceki sağlam hâl .bak olarak kalır
        if (File.Exists(path)) File.Replace(temporary, path, path + ".bak", ignoreMetadataErrors: true);
        else File.Move(temporary, path);
    }
}

/// <summary>Uygulamanın kullanıcıya özel klasörü (%LOCALAPPDATA%\GameTranslator).</summary>
public static class AppPaths
{
    public static string Data { get; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GameTranslator");
    public static string Models { get; } = Path.Combine(Data, "models");
}
