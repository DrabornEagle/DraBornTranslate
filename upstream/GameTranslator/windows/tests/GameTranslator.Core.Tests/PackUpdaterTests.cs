// SPDX-License-Identifier: AGPL-3.0-only

using System.Net;
using System.Text;
using System.Text.Json;

namespace GameTranslator.Core.Tests;

/// <summary>Paket sunucusunun yerine geçen sahte HTTP: liste ve dosyalar bellekte.</summary>
internal sealed class FakePackServer : HttpMessageHandler
{
    public List<RemotePack> Packs { get; } = [];
    public Dictionary<string, byte[]> Files { get; } = [];
    public HttpStatusCode ListStatus { get; set; } = HttpStatusCode.OK;
    public List<string> Downloads { get; } = [];

    public void Add(string id, long version, string content, string? title = null, string? lang = null)
    {
        var data = Encoding.UTF8.GetBytes(content);
        Packs.RemoveAll(p => p.Id == id);
        Packs.Add(new RemotePack(id, title ?? id, version, PackUpdater.Sha256(data), data.Length, 1, lang));
        Files[id] = data;
    }

    public bool FailDownloads { get; set; }

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var path = request.RequestUri!.AbsolutePath;
        if (path == "/v1/packs")
            return Task.FromResult(new HttpResponseMessage(ListStatus) { Content = new StringContent(JsonSerializer.Serialize(new { packs = Packs })) });
        var id = path[(path.LastIndexOf('/') + 1)..];
        if (FailDownloads) return Task.FromResult(new HttpResponseMessage(HttpStatusCode.ServiceUnavailable));
        Downloads.Add(id);
        return Task.FromResult(Files.TryGetValue(id, out var data)
            ? new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(data) }
            : new HttpResponseMessage(HttpStatusCode.NotFound));
    }
}

public sealed class PackUpdaterTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "updater-" + Guid.NewGuid());
    private readonly FakePackServer server = new();
    private DateTimeOffset now = DateTimeOffset.FromUnixTimeSeconds(1_793_000_000);
    private string Packs => Path.Combine(root, "packs");

    private PackUpdater Make() =>
        new(new Uri("https://packs.example.test"), Packs, Path.Combine(root, "packs-remote.json"), server, () => now);

    private PackUpdater Make(string url, FakePackServer with) =>
        new(new Uri(url), Packs, Path.Combine(root, "packs-remote.json"), with, () => now);

    /// <summary>Eski sürüm: önceki sunucudan GTA 6 (ve ek satırları) ile RDR2 inmiş.</summary>
    private async Task<FakePackServer> FromOldServer()
    {
        var old = new FakePackServer();
        old.Add("gta-6", 9, "their gta", "Grand Theft Auto VI");
        old.Add("pool-gta-6", 3, "their extra", "Grand Theft Auto VI (ek satırlar)");
        old.Add("red-dead-redemption-2", 2, "their rdr", "Red Dead Redemption 2");
        using var updater = Make("https://old.example.test", old);
        await updater.RefreshAsync();
        return old;
    }

    [Fact]
    public async Task SwitchingServerReplacesTheGamesWeServeAndKeepsTheRest()
    {
        await FromOldServer();
        now += TimeSpan.FromMinutes(1);
        var ours = new FakePackServer();
        ours.Add("gta-6", 1, "our gta", "Grand Theft Auto VI");
        using var updater = Make("https://new.example.test", ours);
        Assert.True(updater.ServerChanged);
        var result = await updater.RefreshAsync();
        Assert.False(result.Skipped);
        Assert.Equal(["gta-6"], result.Downloaded);
        Assert.Equal(["pool-gta-6"], result.Removed);
        Assert.Equal("our gta", Content("gta-6"));
        Assert.Null(Content("pool-gta-6"));
        Assert.Equal("their rdr", Content("red-dead-redemption-2"));
        Assert.False(updater.ServerChanged);
        Assert.Equal(["red-dead-redemption-2"], PackUpdater.LegacyIds(Path.Combine(root, "packs-remote.json")));
        // Haftalık yenilemede de kalır; sonra RDR2 paketimiz çıkınca onunki iner.
        now += PackUpdater.RefreshInterval + TimeSpan.FromSeconds(1);
        Assert.Empty((await updater.RefreshAsync()).Removed);
        ours.Add("red-dead-redemption-2", 1, "our rdr", "Red Dead Redemption 2");
        Assert.Equal(["red-dead-redemption-2"], (await updater.RefreshAsync(force: true)).Downloaded);
        Assert.Empty(PackUpdater.LegacyIds(Path.Combine(root, "packs-remote.json")));
    }

    [Fact]
    public async Task PacksInOtherLanguagesAreIgnored()
    {
        server.Add("gta-6", 1, "tr pack");
        server.Add("gta-6-ar", 1, "ar pack", lang: "ar");
        using var updater = Make();
        Assert.Equal(["gta-6"], (await updater.CheckAsync())!.Select(p => p.Pack.Id));
        Assert.Equal(["gta-6"], (await updater.RefreshAsync()).Downloaded);
        Assert.DoesNotContain("gta-6-ar", server.Downloads);
        // Yalnız başka dilde paket kalırsa elde olan Türkçe paket silinmez.
        server.Packs.RemoveAll(p => p.Id == "gta-6");
        Assert.True((await updater.RefreshAsync(force: true)).Failed);
        Assert.Equal("tr pack", Content("gta-6"));
    }

    [Fact]
    public async Task ArabicUpdaterDownloadsOnlyArabicPacks()
    {
        server.Add("gta-6", 1, "tr pack");
        server.Add("gta-6-ar", 1, "ar pack", lang: "ar");
        using var updater = new PackUpdater(new Uri("https://packs.example.test"), Packs, Path.Combine(root, "packs-remote-ar.json"), server, () => now)
        {
            PackLanguage = "ar",
        };
        Assert.Equal(["gta-6-ar"], (await updater.CheckAsync())!.Select(p => p.Pack.Id));
        Assert.Equal(["gta-6-ar"], (await updater.RefreshAsync()).Downloaded);
        Assert.DoesNotContain("gta-6", server.Downloads);
        Assert.EndsWith("packs-remote-ar.json", PackUpdater.StateFileFor("ar"));
        Assert.Equal(PackUpdater.DefaultStateFile, PackUpdater.StateFileFor("tr"));
    }

    [Fact]
    public async Task EmptyListRemovesNothing()
    {
        server.Add("gta-6", 1, "one");
        using var updater = Make();
        await updater.RefreshAsync();
        server.Packs.Clear();
        now += PackUpdater.RefreshInterval + TimeSpan.FromSeconds(1);
        Assert.True((await updater.RefreshAsync()).Failed);
        Assert.Equal("one", Content("gta-6"));
    }

    [Fact]
    public async Task FailedDownloadAfterSwitchRetriesOnNextLaunch()
    {
        await FromOldServer();
        var ours = new FakePackServer { FailDownloads = true };
        ours.Add("gta-6", 1, "our gta", "Grand Theft Auto VI");
        using var updater = Make("https://new.example.test", ours);
        now += TimeSpan.FromMinutes(1);
        Assert.Empty((await updater.RefreshAsync()).Downloaded);
        Assert.Equal("their gta", Content("gta-6"));
        ours.FailDownloads = false;
        now += TimeSpan.FromMinutes(1);
        Assert.Equal(["gta-6"], (await updater.RefreshAsync()).Downloaded);
        Assert.Equal("our gta", Content("gta-6"));
    }

    [Fact]
    public async Task PreviousServerPackNeverWinsOverOurs()
    {
        const string onlyOld = "Where the hell did you park the boat?";
        const string shared = "Dispatch, we got them, they're pinned in!";
        string a = PackKey.Key(onlyOld)!, b = PackKey.Key(shared)!;
        var old = new FakePackServer();
        old.Add("pool-gta-6", 1, $"{{\"{a}\": \"tekne nerede\", \"{b}\": \"eski çeviri\"}}", "Grand Theft Auto VI (ek satırlar)");
        using (var first = Make("https://old.example.test", old)) await first.RefreshAsync();
        var ours = new FakePackServer();
        ours.Add("gta-6", 1, $"{{\"{b}\": \"bizim çeviri\"}}", "Grand Theft Auto VI 2");
        using (var updater = Make("https://new.example.test", ours)) await updater.RefreshAsync();
        var library = new GamePackLibrary(null, Packs, () => PackUpdater.LegacyIds(Path.Combine(root, "packs-remote.json")));
        Assert.Equal("tekne nerede", library.Find(onlyOld));
        Assert.Equal("bizim çeviri", library.Find(shared));
        Assert.Equal("bizim çeviri", library.Find(shared));
    }

    private string? Content(string id)
    {
        var file = Path.Combine(Packs, $"{id}.json.gz");
        return File.Exists(file) ? File.ReadAllText(file) : null;
    }

    public void Dispose()
    {
        try { Directory.Delete(root, recursive: true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    [Fact]
    public async Task DownloadsVerifiesAndSkipsUntilAWeekPasses()
    {
        server.Add("gta-6", 1, "one");
        using var updater = Make();
        Assert.Equal(["gta-6"], (await updater.RefreshAsync()).Downloaded);
        Assert.Equal("one", Content("gta-6"));

        server.Add("gta-6", 2, "two");
        Assert.True((await updater.RefreshAsync()).Skipped);
        now += PackUpdater.RefreshInterval + TimeSpan.FromSeconds(1);
        Assert.Equal(["gta-6"], (await updater.RefreshAsync()).Downloaded);
        Assert.Equal("two", Content("gta-6"));

        var count = server.Downloads.Count;
        await updater.RefreshAsync(force: true);
        Assert.Equal(count, server.Downloads.Count);
    }

    [Fact]
    public async Task WrongChecksumIsNeverInstalled()
    {
        server.Add("gta-6", 1, "good");
        server.Files["gta-6"] = Encoding.UTF8.GetBytes("tampered");
        using var updater = Make();
        Assert.Empty((await updater.RefreshAsync()).Downloaded);
        Assert.Null(Content("gta-6"));
        Assert.Empty(updater.InstalledPacks);
    }

    [Fact]
    public async Task RemovedPackIsDeletedAndUnreachableServerKeepsPacks()
    {
        server.Add("gta-6", 1, "one");
        server.Add("rdr-2", 1, "two");
        using var updater = Make();
        await updater.RefreshAsync();
        server.ListStatus = HttpStatusCode.ServiceUnavailable;
        Assert.True((await updater.RefreshAsync(force: true)).Failed);
        Assert.Equal("one", Content("gta-6"));
        server.ListStatus = HttpStatusCode.OK;
        server.Packs.RemoveAll(p => p.Id == "gta-6");
        Assert.Equal(["gta-6"], (await updater.RefreshAsync(force: true)).Removed);
        Assert.Null(Content("gta-6"));
        Assert.Equal("two", Content("rdr-2"));
    }

    /// <summary>Açılışta yalnız bakılır: yeni ve güncellenen paketler listelenir, hiçbir şey inmez; onaydan sonra iner.</summary>
    [Fact]
    public async Task CheckListsNewAndUpdatedPacksWithoutDownloading()
    {
        server.Add("gta-v", 1, "bundled");
        server.Add("rdr-2", 1, "old");
        using var updater = new PackUpdater(new Uri("https://packs.example.test"), Packs, Path.Combine(root, "packs-remote.json"), server, () => now)
        {
            BundledSha256 = id => id == "gta-v" ? PackUpdater.Sha256(Encoding.UTF8.GetBytes("bundled")) : null,
        };
        await updater.RefreshAsync(force: true); // rdr-2 v1 iner; gta-v uygulamayla gelenle aynı
        Assert.Empty(await updater.CheckAsync() ?? throw new Xunit.Sdk.XunitException("liste yok"));

        server.Add("rdr-2", 2, "new");
        server.Add("gta-v", 2, "fixed");
        server.Add("gta-6", 1, "first");
        var count = server.Downloads.Count;
        var pending = await updater.CheckAsync();
        Assert.NotNull(pending);
        Assert.Equal(count, server.Downloads.Count);
        Assert.Equal(["gta-6", "gta-v", "rdr-2"], pending!.Select(p => p.Pack.Id).Order());
        Assert.True(pending.Single(p => p.Pack.Id == "gta-6").IsNew);
        Assert.False(pending.Single(p => p.Pack.Id == "gta-v").IsNew);
        Assert.False(pending.Single(p => p.Pack.Id == "rdr-2").IsNew);
        Assert.Equal("old", Content("rdr-2"));

        Assert.Equal(3, (await updater.RefreshAsync(force: true)).Downloaded.Count);
        Assert.Equal("new", Content("rdr-2"));
        Assert.Empty(await updater.CheckAsync() ?? []);

        server.ListStatus = HttpStatusCode.ServiceUnavailable;
        Assert.Null(await updater.CheckAsync());
    }

    /// <summary>Kullanıcının klasöre kendisi bıraktığı aynı adlı dosyanın üstüne yazılmaz, silinmez.</summary>
    [Fact]
    public async Task UserFilesAreLeftAlone()
    {
        Directory.CreateDirectory(Packs);
        File.WriteAllText(Path.Combine(Packs, "gta-6.json.gz"), "mine");
        server.Add("gta-6", 1, "server");
        server.Add("../evil", 1, "x");
        using var updater = Make();
        Assert.Empty((await updater.RefreshAsync()).Downloaded);
        Assert.Equal("mine", Content("gta-6"));
        Assert.DoesNotContain("evil", server.Downloads);
        updater.Clear();
        Assert.Equal("mine", Content("gta-6"));
    }

    [Fact]
    public void AddressComesOnlyFromConfiguration()
    {
        var assembly = typeof(PackUpdaterTests).Assembly;
        Assert.Null(PackUpdater.ConfiguredBaseUrl("", assembly));
        Assert.Null(PackUpdater.ConfiguredBaseUrl("ftp://x", assembly));
        Assert.Equal("packs.example.test", PackUpdater.ConfiguredBaseUrl("https://packs.example.test", assembly)?.Host);
    }

    [Fact]
    public void PackIdsMatchTheServer()
    {
        Assert.True(PackUpdater.IsValidId("gta-6"));
        Assert.False(PackUpdater.IsValidId("-gta"));
        Assert.False(PackUpdater.IsValidId("../evil"));
        Assert.False(PackUpdater.IsValidId("GTA"));
    }
}
