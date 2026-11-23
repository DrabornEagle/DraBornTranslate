// SPDX-License-Identifier: AGPL-3.0-only

using GameTranslator.RemotePlay;

namespace GameTranslator.Shell.Tests;

internal sealed class FakeXboxService : IXboxService
{
    public event Action<string>? TokenUpdated;
    public int Clears { get; private set; }
    public string LoginToken { get; set; } = """{"refresh":"r1"}""";
    public List<XboxConsole> Consoles { get; } = [new("F400AAAA", "Salon Xbox", "XboxSeriesX", true), new("F400BBBB", "Eski Xbox", "XboxOne", false)];
    public List<(XboxConsole Console, string Token, FakeStream Stream)> Streams { get; } = [];
    public string? DiscoveredWith { get; private set; }

    public void Clear() => Clears++;
    public Task<XboxLoginChallenge> BeginLoginAsync() => Task.FromResult(new XboxLoginChallenge("ABCD1234", new Uri("https://www.microsoft.com/link"), 900));
    public Task<string> CompleteLoginAsync() => Task.FromResult(LoginToken);
    public Task<IReadOnlyList<XboxConsole>> DiscoverAsync(string token)
    {
        DiscoveredWith = token;
        return Task.FromResult<IReadOnlyList<XboxConsole>>(Consoles);
    }
    public IStreamSession MakeStream(XboxConsole console, string token)
    {
        var stream = new FakeStream();
        Streams.Add((console, token, stream));
        return stream;
    }
    public void Refresh(string token) => TokenUpdated?.Invoke(token);
}

/// Xbox ev yayını (CouchSubs'tan): Microsoft girişi, konsol seçimi ve çevirinin Xbox oturumuyla başlaması.
public class XboxControllerTests
{
    private static (AppController Controller, FakeServices Services, FakeXboxService Xbox, MemoryStore Store) Make(bool xboxSource = true)
    {
        var xbox = new FakeXboxService();
        var services = new FakeServices { Xbox = xbox };
        var store = new MemoryStore();
        var settings = new MemorySettings();
        if (xboxSource) settings.Save(new UserSettings { VideoSource = VideoSourceType.Xbox });
        return (AppControllerTests.Make(services, store, settings), services, xbox, store);
    }

    private static async Task<XboxConsole> SignInAndPick(AppController controller)
    {
        await controller.BeginXboxLoginAsync();
        await controller.CompleteXboxLoginAsync();
        var console = (await controller.DiscoverXboxConsolesAsync()).First(c => c.StreamingEnabled);
        controller.SelectXboxConsole(console);
        return console;
    }

    [Fact]
    public async Task LoginShowsTheCodeOpensThePageAndStoresTheToken()
    {
        var (controller, _, _, store) = Make();
        Uri? opened = null;
        controller.OpenInBrowser = url => opened = url;
        Assert.False(controller.HasXboxAccount);

        var login = await controller.BeginXboxLoginAsync();
        Assert.Equal("ABCD1234", login.Code);
        Assert.Equal(login.Url, opened);
        Assert.False(controller.HasXboxAccount);   // kullanıcı kodu onaylayana kadar giriş yok

        await controller.CompleteXboxLoginAsync();
        Assert.True(controller.HasXboxAccount);
        Assert.Equal("""{"refresh":"r1"}""", store.Load<string>(StoreKeys.XboxToken));
        Assert.False(controller.XboxReady);        // konsol seçilmedi
        controller.Dispose();
    }

    [Fact]
    public async Task ConsolesAreListedWithTheTokenAndOnlyStreamableOnesCanBePicked()
    {
        var (controller, _, xbox, store) = Make();
        await controller.BeginXboxLoginAsync();
        await controller.CompleteXboxLoginAsync();
        var consoles = await controller.DiscoverXboxConsolesAsync();
        Assert.Equal("""{"refresh":"r1"}""", xbox.DiscoveredWith);
        Assert.Equal(2, consoles.Count);

        controller.SelectXboxConsole(consoles.Single(c => !c.StreamingEnabled));   // uzak özellikler kapalı
        Assert.Null(controller.SelectedXboxConsole);
        controller.SelectXboxConsole(consoles.Single(c => c.StreamingEnabled));
        Assert.Equal("Salon Xbox", controller.SelectedXboxConsole!.Name);
        Assert.Equal(controller.SelectedXboxConsole, store.Load<XboxConsole>(StoreKeys.XboxConsole));
        Assert.True(controller.XboxReady);
        controller.Dispose();
    }

    [Fact]
    public async Task ListingConsolesBeforeSigningInFails()
    {
        var (controller, _, _, _) = Make();
        await Assert.ThrowsAsync<InvalidOperationException>(() => controller.DiscoverXboxConsolesAsync());
        controller.Dispose();
    }

    [Fact]
    public async Task TranslationStartsOnTheXboxStreamWithoutAnyPs5Pairing()
    {
        var (controller, services, xbox, _) = Make();
        var console = await SignInAndPick(controller);
        Assert.Null(controller.Console);   // PS5 eşleşmesi yok

        await controller.StartAsync();
        Assert.True(controller.IsRunning);
        var (usedConsole, usedToken, stream) = Assert.Single(xbox.Streams);
        Assert.Equal(console, usedConsole);
        Assert.Equal("""{"refresh":"r1"}""", usedToken);
        Assert.True(stream.Started);
        Assert.Empty(services.Streams);    // PS5 / pencere oturumu açılmadı

        controller.Stop();
        Assert.False(stream.Started);
        controller.Dispose();
    }

    [Fact]
    public async Task TranslationDoesNotStartUntilSignedInAndAConsoleIsPicked()
    {
        var (controller, services, xbox, _) = Make();
        await controller.StartAsync();
        Assert.False(controller.IsRunning);

        await controller.BeginXboxLoginAsync();
        await controller.CompleteXboxLoginAsync();
        await controller.StartAsync();     // giriş var, konsol yok
        Assert.False(controller.IsRunning);
        Assert.Empty(xbox.Streams);
        Assert.Empty(services.Streams);
        controller.Dispose();
    }

    [Fact]
    public async Task OwnWindowOptionKeepsUsingTheWindowCapture()
    {
        var (controller, services, xbox, _) = Make();
        await SignInAndPick(controller);
        controller.Settings = controller.Settings with { XboxExternalWindow = true };
        Assert.False(controller.UsesXboxStream);

        await controller.StartAsync();
        Assert.True(controller.IsRunning);
        Assert.Empty(xbox.Streams);
        Assert.Single(services.Streams);
        controller.Dispose();
    }

    [Fact]
    public async Task SwitchingBetweenOwnWindowAndXboxStreamRestartsTheRightSession()
    {
        var (controller, services, xbox, _) = Make();
        await SignInAndPick(controller);
        await controller.StartAsync();
        Assert.Single(xbox.Streams);

        controller.Settings = controller.Settings with { XboxExternalWindow = true };
        Assert.False(xbox.Streams[0].Stream.Started);
        Assert.True(Assert.Single(services.Streams).Started);

        controller.Settings = controller.Settings with { XboxExternalWindow = false };
        Assert.Equal(2, xbox.Streams.Count);
        Assert.True(xbox.Streams[1].Stream.Started);
        controller.Dispose();
    }

    [Fact]
    public async Task Ps5SourceIsUntouchedByAnXboxAccount()
    {
        var (controller, services, xbox, _) = Make(xboxSource: false);
        await SignInAndPick(controller);
        Assert.False(controller.UsesXboxStream);
        await controller.StartAsync();     // PS5 kaynağı, PS5 eşleşmesi yok → başlamaz (eski davranış)
        Assert.False(controller.IsRunning);
        Assert.Empty(xbox.Streams);
        Assert.Empty(services.Streams);
        controller.Dispose();
    }

    [Fact]
    public async Task RefreshedTokenIsSavedAndUsedNextTime()
    {
        var (controller, _, xbox, store) = Make();
        await SignInAndPick(controller);
        xbox.Refresh("""{"refresh":"r2"}""");
        Assert.Equal("""{"refresh":"r2"}""", store.Load<string>(StoreKeys.XboxToken));

        await controller.StartAsync();
        Assert.Equal("""{"refresh":"r2"}""", Assert.Single(xbox.Streams).Token);
        controller.Dispose();
    }

    [Fact]
    public async Task ForgettingRemovesTheAccountStopsTranslationAndIgnoresLateRefreshes()
    {
        var (controller, _, xbox, store) = Make();
        await SignInAndPick(controller);
        await controller.StartAsync();
        Assert.True(controller.IsRunning);

        controller.ForgetXbox();
        Assert.False(controller.IsRunning);
        Assert.False(controller.HasXboxAccount);
        Assert.Null(controller.SelectedXboxConsole);
        Assert.Null(store.Load<string>(StoreKeys.XboxToken));
        Assert.Null(store.Load<XboxConsole>(StoreKeys.XboxConsole));
        Assert.Equal(1, xbox.Clears);

        xbox.Refresh("""{"refresh":"late"}""");   // eski oturumdan geç gelen yenileme hesabı geri getirmemeli
        Assert.False(controller.HasXboxAccount);
        Assert.Null(store.Load<string>(StoreKeys.XboxToken));
        controller.Dispose();
    }

    [Fact]
    public async Task SavedAccountAndConsoleAreRestoredOnNextLaunch()
    {
        var (first, _, _, store) = Make();
        var console = await SignInAndPick(first);
        first.Dispose();

        var xbox = new FakeXboxService();
        var settings = new MemorySettings();
        settings.Save(new UserSettings { VideoSource = VideoSourceType.Xbox });
        var second = AppControllerTests.Make(new FakeServices { Xbox = xbox }, store, settings);
        Assert.True(second.XboxReady);
        Assert.Equal(console, second.SelectedXboxConsole);
        await second.StartAsync();
        Assert.Equal(console, Assert.Single(xbox.Streams).Console);
        second.Dispose();
    }

    [Fact]
    public async Task ResetSetupForgetsXboxToo()
    {
        var (controller, _, xbox, store) = Make();
        await SignInAndPick(controller);
        controller.ResetSetup();
        Assert.False(controller.HasXboxAccount);
        Assert.Null(controller.SelectedXboxConsole);
        Assert.Null(store.Load<string>(StoreKeys.XboxToken));
        Assert.True(xbox.Clears >= 1);
        controller.Dispose();
    }

    /// <summary>Xbox durum metinleri katalogdan gelir; Türkçe arayüzde eskiden koda gömülü olan metnin aynısı görünür.</summary>
    [Fact]
    public void StatusTextsKeepTheirTurkishWording()
    {
        var tr = GameTranslator.Core.Localizer.ForLanguage("tr");
        Assert.Equal("Xbox bağlanmadı", tr["Xbox not connected"]);
        Assert.Equal("Konsol seçilmedi", tr["No console selected"]);
        Assert.Equal("Salon Xbox hazır", tr.Format("%@ is ready", "Salon Xbox"));
        Assert.Equal("Bağlı", tr["Connected"]);
        Assert.Equal("Bağlanıyor…", tr["Connecting…"]);
        Assert.Equal("Yeniden deneniyor: konsol kapalı", tr.Format("Retrying: %@", "konsol kapalı"));
        Assert.Equal("Yeniden deneniyor…", tr["Retrying…"]);
    }

    [Fact]
    public async Task StatusTellsWhatIsMissingAndWhenItIsReady()
    {
        var (controller, _, xbox, _) = Make();
        Assert.Equal("Xbox not connected", controller.Ps5StatusText);
        await controller.BeginXboxLoginAsync();
        await controller.CompleteXboxLoginAsync();
        Assert.Equal("No console selected", controller.Ps5StatusText);
        controller.SelectXboxConsole(xbox.Consoles[0]);
        Assert.Equal("Salon Xbox is ready", controller.Ps5StatusText);

        await controller.StartAsync();
        var stream = xbox.Streams[0].Stream;
        stream.Emit(SessionState.Streaming);
        Assert.Equal("Connected", controller.Ps5StatusText);
        stream.Emit(SessionState.Disconnected(DisconnectKind.Other, "konsol kapalı"));
        Assert.Equal("Retrying: konsol kapalı", controller.Ps5StatusText);
        controller.Dispose();
    }

    [Fact]
    public void WithoutTheHelperXboxStreamIsUnavailableAndNothingBreaks()
    {
        var settings = new MemorySettings();
        settings.Save(new UserSettings { VideoSource = VideoSourceType.Xbox });
        var controller = AppControllerTests.Make(new FakeServices(), new MemoryStore(), settings);
        Assert.False(controller.XboxAvailable);
        Assert.False(controller.XboxReady);
        controller.ForgetXbox();   // servis yokken de güvenli
        controller.Dispose();
    }
}
