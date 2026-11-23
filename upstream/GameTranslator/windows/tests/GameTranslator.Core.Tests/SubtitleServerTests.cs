// SPDX-License-Identifier: AGPL-3.0-only

using System.Net;
using GameTranslator.Core;

namespace GameTranslator.Core.Tests;

public class SubtitleServerTests
{
    private static readonly HttpClient Http = new() { Timeout = Timeout.InfiniteTimeSpan };

    private static async Task WaitUntil(Func<bool> condition, int timeoutMs = 3000)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (!condition())
        {
            Assert.True(DateTime.UtcNow < deadline, "Koşul zamanında sağlanmadı");
            await Task.Delay(20);
        }
    }

    private static async Task<List<string>> ReadLines(StreamReader reader, string until)
    {
        var lines = new List<string>();
        while (await reader.ReadLineAsync() is { } line)
        {
            lines.Add(line);
            if (line == until) break;
        }
        return lines;
    }

    [Fact]
    public async Task StreamsSubtitleAndClearEventsWithCors()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();

        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("*", response.Headers.GetValues("Access-Control-Allow-Origin").Single());
        Assert.StartsWith("text/event-stream", response.Content.Headers.ContentType?.ToString());

        await WaitUntil(() => server.ConnectedClients == 1);
        server.SendSubtitle("Merhaba", 4000, new TextBox(0.25, 0.8, 0.75, 0.87499));
        // Aynı cümlenin uzamış okuması: TV öncekinin üstüne yığmaz, yerine koyar.
        server.SendSubtitle("Merhaba dostum", 4000, replaces: true);
        server.Clear();

        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        var lines = await ReadLines(reader, "event: clear");
        Assert.Contains("event: subtitle", lines);
        Assert.Contains(lines, l => l.StartsWith("data:") && l.Contains("\"text\":\"Merhaba\"") && l.Contains("\"duration\":4000")
            && l.Contains("\"box\":{\"bottom\":0.875,\"left\":0.25,\"right\":0.75,\"top\":0.8}") && !l.Contains("replace"));
        Assert.Contains(lines, l => l.StartsWith("data:") && l.Contains("\"text\":\"Merhaba dostum\"") && l.Contains("\"replace\":true"));
    }

    /// Örtme yerleşiminde oyunun altyazısının gerçek yeri de (source) gider: TV çeviriyi o alana sığdırır, kara zemin
    /// İngilizce kadar kalır. Büyütülmüş box eski TV uygulamaları için durur.
    [Fact]
    public async Task CoverSendsTheRealSubtitleArea()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        await WaitUntil(() => server.ConnectedClients == 1);
        server.SendStyle(new SubtitleStyle(46, 12, SubtitlePlacement.Cover, 72, false));
        server.SendSubtitle("Bana niye söylüyorsun? Bunu bilmem gerekiyor mu gerçekten?", 4000, new TextBox(0.4, 0.85, 0.6, 0.89));
        server.Clear();
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        var lines = await ReadLines(reader, "event: clear");
        var data = lines.Single(l => l.StartsWith("data:") && l.Contains("\"text\":\"Bana"));
        Assert.Contains("\"source\":{\"bottom\":0.89,\"left\":0.4,\"right\":0.6,\"top\":0.85}", data);
        Assert.DoesNotContain("\"box\":{\"bottom\":0.89,\"left\":0.4,", data);
    }

    /// TV yarı açık bağlantıyı adlı "ping" olayının gelmemesinden anlar; yorum satırını EventSource görmez.
    [Fact]
    public async Task PingIsSentAsNamedEvent()
    {
        using var server = new SubtitleServer(0, TimeSpan.FromMilliseconds(50));
        var port = await server.StartAsync();
        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        var lines = await ReadLines(reader, "event: ping");
        Assert.Equal("event: ping", lines[^1]);
        Assert.Equal("data: {}", await reader.ReadLineAsync());
    }

    [Fact]
    public async Task StreamsScreenTranslationsAndTheirClear()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        await WaitUntil(() => server.ConnectedClients == 1);
        server.SendScreen([
            new("Map\nSettings", "Harita\nAyarlar", new TextBox(0.05, 0.3, 0.2, 0.4), 0.03),
            new("Open the door.", "Kapıyı aç.", new TextBox(0.4, 0.8, 0.6, 0.84), 0.04),
        ], 12000);
        server.ClearScreen();

        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        var lines = await ReadLines(reader, "event: screen-clear");
        Assert.Contains("event: screen", lines);
        Assert.Contains(lines, l => l.StartsWith("data:") && l.Contains("\"duration\":12000") &&
            l.Contains("{\"box\":{\"bottom\":0.4,\"left\":0.05,\"right\":0.2,\"top\":0.3},\"line\":0.03,\"paragraphs\":true,\"text\":\"Harita\\nAyarlar\"}") &&
            l.Contains("{\"box\":{\"bottom\":0.84,\"left\":0.4,\"right\":0.6,\"top\":0.8},\"line\":0.04,\"text\":\"Kapıyı aç.\"}"));
    }

    /// <summary>Ek alanın çevirisi kendi olayıyla gider (eski TV uygulamaları tanımadığı olayı yok sayar; CouchSubs ile aynı).</summary>
    [Fact]
    public async Task StreamsRegionTranslationsAndTheirClear()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        await WaitUntil(() => server.ConnectedClients == 1);
        server.SendRegion("q", "Demirciyi bul", new TextBox(0.7, 0.05, 0.95, 0.12), 0.03);
        server.SendRegion("q", "", null);
        server.ClearRegions();

        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        var lines = await ReadLines(reader, "event: region-clear");
        Assert.Contains("event: region", lines);
        Assert.Contains("data: {\"box\":{\"bottom\":0.12,\"left\":0.7,\"right\":0.95,\"top\":0.05},\"id\":\"q\",\"line\":0.03,\"text\":\"Demirciyi bul\"}", lines);
        Assert.Contains("data: {\"id\":\"q\",\"text\":\"\"}", lines);
    }

    [Fact]
    public async Task ObsOverlayIsServedAndNotCountedAsTv()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();

        using var page = await Http.GetAsync($"http://127.0.0.1:{port}/obs");
        Assert.Equal(HttpStatusCode.OK, page.StatusCode);
        Assert.StartsWith("text/html", page.Content.Headers.ContentType?.ToString());
        Assert.Contains("/events?viewer=obs", await page.Content.ReadAsStringAsync());

        // OBS katmanı olayları alır ama "TV bağlı" sayısına katılmaz.
        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events?viewer=obs", HttpCompletionOption.ResponseHeadersRead);
        await Task.Delay(200);
        Assert.Equal(0, server.ConnectedClients);
        server.SendSubtitle("Merhaba", 4000);
        server.Clear();
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        Assert.Contains("event: subtitle", await ReadLines(reader, "event: clear"));
    }

    /// İkinci ekran (desteklenmeyen TV'ler): sayfa uygulamanın içinden verilir, TV sayılmaz ama altyazıyı alır.
    [Theory]
    [InlineData("/ekran")]
    [InlineData("/screen")]
    [InlineData("/ekran?size=1.4")]
    public async Task SecondScreenPageIsServedFromTheApp(string path)
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        using var page = await Http.GetAsync($"http://127.0.0.1:{port}{path}");
        Assert.Equal(HttpStatusCode.OK, page.StatusCode);
        Assert.StartsWith("text/html", page.Content.Headers.ContentType?.ToString());
        var html = await page.Content.ReadAsStringAsync();
        Assert.Contains("İkinci ekran", html);
        // Sayfa yalnız bu sunucuya bağlanır: dışarıdan betik, yazı tipi ya da resim çekmez
        Assert.Contains("new EventSource('/events?viewer=obs", html);
        Assert.DoesNotContain("https://", html);
        Assert.DoesNotContain("http://", html);
        Assert.Equal(SubtitleServer.SecondScreenPath, "/ekran");
    }

    [Fact]
    public async Task SecondScreenGetsSubtitlesWithoutBeingCountedAsATv()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        using var screen = await Http.GetAsync($"http://127.0.0.1:{port}/events?viewer=obs&screen=1", HttpCompletionOption.ResponseHeadersRead);
        await Task.Delay(200);
        Assert.Equal(0, server.ConnectedClients);
        server.SendSubtitle("Öyleyse anlaştık.", 4000);
        server.Clear();
        using var reader = new StreamReader(await screen.Content.ReadAsStreamAsync());
        var lines = await ReadLines(reader, "event: clear");
        Assert.Contains(lines, l => l.StartsWith("data:") && l.Contains("\"text\":\"Öyleyse anlaştık.\""));
    }

    /// TV ile OBS katmanı birbirinin yerine geçmez: ikisi de bağlıyken aynı altyazı ikisine de gider (yayıncı hem TV'de
    /// oynar hem yayına altyazı koyar). Kurulumda hangisinin seçildiği gönderimi değiştirmez.
    [Fact]
    public async Task TvAndObsBothGetTheSameSubtitle()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        using var tv = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        using var obs = await Http.GetAsync($"http://127.0.0.1:{port}/events?viewer=obs", HttpCompletionOption.ResponseHeadersRead);
        using var secondObs = await Http.GetAsync($"http://127.0.0.1:{port}/events?viewer=obs", HttpCompletionOption.ResponseHeadersRead);
        await WaitUntil(() => server.ConnectedClients == 1);
        await Task.Delay(200);
        server.SendSubtitle("Merhaba", 4000, new TextBox(0.3, 0.8, 0.7, 0.86));
        server.Clear();

        foreach (var stream in new[] { tv, obs, secondObs })
        {
            using var reader = new StreamReader(await stream.Content.ReadAsStreamAsync());
            var lines = await ReadLines(reader, "event: clear");
            Assert.Contains(lines, l => l.StartsWith("data:") && l.Contains("\"text\":\"Merhaba\""));
        }
    }

    /// OBS'e ayrı görünüm verilince OBS kendi yazı boyunu, rengini ve yerleşimini alır; TV'ninki değişmez. Renk TV
    /// uygulamasının okuduğu "color" alanında da gider (eskiden gitmiyordu, TV'de hiçbir renk görünmüyordu).
    [Fact]
    public async Task ObsGetsItsOwnStyleWhileTheTvKeepsItsOwn()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        server.SendStyle(new SubtitleStyle(FontSize: 46, Placement: SubtitlePlacement.Cover, TextColor: SubtitleTextColor.Yellow),
                         new SubtitleStyle(FontSize: 64, Placement: SubtitlePlacement.Fixed, TextColor: SubtitleTextColor.Green));
        using var tv = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        using var obs = await Http.GetAsync($"http://127.0.0.1:{port}/events?viewer=obs", HttpCompletionOption.ResponseHeadersRead);
        await WaitUntil(() => server.ConnectedClients == 1);
        await Task.Delay(200);
        server.SendSubtitle("Merhaba", 4000, new TextBox(0.3, 0.8, 0.7, 0.86));
        server.Clear();

        using var tvReader = new StreamReader(await tv.Content.ReadAsStreamAsync());
        var tvLines = await ReadLines(tvReader, "event: clear");
        Assert.Contains(tvLines, l => l.Contains("\"fontSize\":46") && l.Contains("\"color\":\"#FFE600\"") && l.Contains("\"placement\":\"cover\""));
        Assert.Contains(tvLines, l => l.Contains("\"text\":\"Merhaba\"") && l.Contains("\"source\""));

        using var obsReader = new StreamReader(await obs.Content.ReadAsStreamAsync());
        var obsLines = await ReadLines(obsReader, "event: clear");
        Assert.Contains(obsLines, l => l.Contains("\"fontSize\":64") && l.Contains("\"color\":\"#10B981\"") && l.Contains("\"placement\":\"fixed\""));
        Assert.DoesNotContain(obsLines, l => l.Contains("\"fontSize\":46"));
        // Sabit yerleşimde OBS'e örtme kutusu (source) gitmez
        Assert.Contains(obsLines, l => l.Contains("\"text\":\"Merhaba\"") && !l.Contains("\"source\""));
    }

    /// Altyazı yalnız bilgisayar ekranındayken TV'ye altyazı gitmez; OBS katmanı ve dublaj sesi gitmeye devam eder.
    [Fact]
    public async Task SubtitlesSkipTvsWhenTvOutputIsOff()
    {
        using var server = new SubtitleServer(0) { SendsToTv = false };
        var port = await server.StartAsync();
        using var tv = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        using var obs = await Http.GetAsync($"http://127.0.0.1:{port}/events?viewer=obs", HttpCompletionOption.ResponseHeadersRead);
        await WaitUntil(() => server.ConnectedClients == 1);
        await Task.Delay(200);
        server.SendSubtitle("Merhaba", 4000);
        server.Clear();
        server.StopSpeech();

        using var obsReader = new StreamReader(await obs.Content.ReadAsStreamAsync());
        Assert.Contains("event: subtitle", await ReadLines(obsReader, "event: clear"));
        using var tvReader = new StreamReader(await tv.Content.ReadAsStreamAsync());
        Assert.DoesNotContain("event: subtitle", await ReadLines(tvReader, "event: speech-stop"));
    }

    /// Bilgisayar ekranındaki pencere sunucudan geçen altyazıyı alır; sunucu başlamamış olsa da.
    [Fact]
    public void LocalListenerGetsSubtitleAndClear()
    {
        using var server = new SubtitleServer(0);
        var received = new List<SubtitleEvent>();
        server.EventSent += received.Add;
        server.SendSubtitle("Merhaba", 4000, null, true);
        server.Clear();
        Assert.Equal([new SubtitleEvent(SubtitleEventKind.Subtitle, "Merhaba", 4000, true), SubtitleEvent.Cleared], received);
    }

    [Fact]
    public async Task TurkishTextIsSentAsUtf8()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        await WaitUntil(() => server.ConnectedClients == 1);
        server.SendSubtitle("Kapıyı tut, evlat!", 4000);
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        var lines = await ReadLines(reader, "");
        lines = await ReadLines(reader, "");
        Assert.Contains(lines, l => l.Contains("Kapıyı tut, evlat!"));
    }

    [Fact]
    public async Task NewClientReceivesLastStyle()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        server.SendStyle(new SubtitleStyle(58, 10, SubtitlePlacement.Above, 40, true, SubtitleTextColor.Yellow, TvDisplayMode.Overlay));

        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        string? data = null;
        while (await reader.ReadLineAsync() is { } line)
            if (line.StartsWith("data:")) { data = line; break; }
        Assert.Contains("\"fontSize\":58", data);
        Assert.Contains("\"bottomPercent\":10", data);
        Assert.Contains("\"placement\":\"above\"", data);
        Assert.Contains("\"background\":40", data);
        Assert.Contains("\"bold\":true", data);
        Assert.Contains("\"textColor\":\"yellow\"", data);
        Assert.Contains("\"displayMode\":\"overlay\"", data);
        // Varsayılan: önceki satırlar üstte kalır (eski davranış); ayar TV'ye gider.
        Assert.Contains("\"stackPrevious\":true", data);
    }

    [Fact]
    public async Task ChosenFontIsSentAndItsFileIsServed()
    {
        using var server = new SubtitleServer(0);
        var asked = new List<string>();
        var bytes = new byte[] { 0, 1, 0, 0, 42 };
        server.FindFont = family => { asked.Add(family); return family == "Pricedown" ? new FontFile(bytes, "font/ttf") : null; };
        var port = await server.StartAsync();
        server.SendStyle(new SubtitleStyle(Font: SubtitleFonts.Gta6));

        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        string? data = null;
        while (await reader.ReadLineAsync() is { } line)
            if (line.StartsWith("data:")) { data = line; break; }
        Assert.Contains("\"fontFamily\":\"Pricedown\"", data);
        var url = System.Text.Json.JsonDocument.Parse(data!["data:".Length..]).RootElement.GetProperty("fontUrl").GetString();
        Assert.StartsWith("/font/", url);
        Assert.EndsWith(".ttf", url);
        Assert.Equal(bytes, await Http.GetByteArrayAsync($"http://127.0.0.1:{port}{url}"));
        // Dosya bir kez aranır (her görünüm gönderiminde diskten okunmaz)
        server.SendStyle(new SubtitleStyle(Font: SubtitleFonts.Pricedown));
        Assert.Single(asked);
    }

    [Fact]
    public async Task EmbeddedQuicksandIsServedWithoutInstalling()
    {
        using var server = new SubtitleServer(0);
        server.FindFont = _ => null;
        var port = await server.StartAsync();
        server.SendStyle(new SubtitleStyle(Font: SubtitleFonts.Quicksand));
        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        string? data = null;
        while (await reader.ReadLineAsync() is { } line)
            if (line.StartsWith("data:")) { data = line; break; }
        var url = System.Text.Json.JsonDocument.Parse(data!["data:".Length..]).RootElement.GetProperty("fontUrl").GetString();
        Assert.StartsWith("/font/", url);
        var font = await Http.GetByteArrayAsync($"http://127.0.0.1:{port}{url}");
        Assert.Equal(SubtitleFonts.Embedded("Quicksand")!.Data, font);
        Assert.True(font.Length > 50_000);
    }

    [Fact]
    public void EachSpeakerKeepsTheirOwnColor()
    {
        var palette = SpeakerColors.Palette(SubtitleTextColor.White);
        var speakers = new SpeakerColors();
        string? Color(string text, string name) => speakers.Split(text, palette).Single(p => p.Text == name + ":").Color;

        var jason = Color("Jason: Hadi gidelim.", "Jason");
        var lucia = Color("Lucia: Bekle!", "Lucia");
        Assert.Equal(palette[0], jason);
        Assert.Equal(palette[1], lucia);
        Assert.Equal(jason, Color("Jason: Tamam.", "Jason"));
        // Aynı satırda iki konuşmacı: ikisi de kendi renginde
        var both = speakers.Split("Lucia: Şimdi! Jason: Geliyorum.", palette).Where(p => p.Color is not null).ToList();
        Assert.Equal([("Lucia:", lucia), ("Jason:", jason)], both);
        // Adsız satır olduğu gibi kalır
        Assert.Equal([("Hepsi bu kadar.", (string?)null)], speakers.Split("Hepsi bu kadar.", palette));
    }

    [Fact]
    public void SpeakerColorsAvoidTheTextColorAndRecycleTheQuietestSpeaker()
    {
        Assert.DoesNotContain("#FFD60A", SpeakerColors.Palette(SubtitleTextColor.Yellow));
        Assert.Equal("#64D2FF", SpeakerColors.Palette(SubtitleTextColor.Yellow)[0]);
        var palette = new[] { "#1", "#2" };
        var speakers = new SpeakerColors();
        speakers.Split("Ali: a", palette);
        speakers.Split("Veli: b", palette);
        speakers.Split("Ali: c", palette);
        // Renk kalmadı: en uzun süredir konuşmayan Veli'nin rengi Ayşe'ye geçer
        Assert.Equal("#2", speakers.Split("Ayşe: d", palette)[0].Color);
        Assert.Equal("#1", speakers.Split("Ali: e", palette)[0].Color);
    }

    [Fact]
    public async Task StyleSendsOneColorPerSpeakerOnlyWhenTurnedOn()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        server.SendStyle(new SubtitleStyle(SpeakerColor: true));
        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        string? data = null;
        while (await reader.ReadLineAsync() is { } line)
            if (line.StartsWith("data:")) { data = line; break; }
        var json = System.Text.Json.JsonDocument.Parse(data!["data:".Length..]).RootElement;
        Assert.Equal("#FFD60A", json.GetProperty("speakerColor").GetString());
        Assert.True(json.GetProperty("speakerColors").GetArrayLength() >= 5);
    }

    [Fact]
    public async Task MissingOrStandardFontSendsNoFile()
    {
        using var server = new SubtitleServer(0);
        server.FindFont = _ => null;
        var port = await server.StartAsync();
        server.SendStyle(new SubtitleStyle(Font: "Bilinmeyen Font"));
        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
        string? data = null;
        while (await reader.ReadLineAsync() is { } line)
            if (line.StartsWith("data:")) { data = line; break; }
        Assert.Contains("\"fontFamily\":\"Bilinmeyen Font\"", data);
        Assert.Contains("\"fontUrl\":\"\"", data);
    }

    [Fact]
    public void FontPresetsAndOldSettings()
    {
        Assert.Null(SubtitleFonts.Family(""));
        Assert.Equal("Pricedown", SubtitleFonts.Family(SubtitleFonts.Gta6));
        Assert.Equal("Pricedown", SubtitleFonts.Family(SubtitleFonts.Pricedown));
        Assert.Equal("Arial", SubtitleFonts.Family("Arial"));
        // Menüde tek seçenek: eski "GTA 6" kaydı Pricedown olarak görünür
        Assert.Equal([SubtitleFonts.Quicksand, SubtitleFonts.Pricedown], SubtitleFonts.Presets.Select(p => p.Key));
        Assert.Equal(SubtitleFonts.Pricedown, SubtitleFonts.MenuKey(SubtitleFonts.Gta6));
        Assert.Equal("Arial", SubtitleFonts.MenuKey("Arial"));
        Assert.NotNull(SubtitleFonts.DownloadPage(SubtitleFonts.Gta6));
        Assert.Null(SubtitleFonts.DownloadPage("Arial"));
        // Eski sürümün kaydettiği görünümde font yok: standart yazı
        var old = System.Text.Json.JsonSerializer.Deserialize<SubtitleStyle>("{\"FontSize\":58,\"Bold\":true}");
        Assert.Equal("", old!.Font);
        Assert.True(old.Bold);
    }

    [Fact]
    public void DisplayModeIsSavedAndOldSettingsStayInsideTheApp()
    {
        var old = System.Text.Json.JsonSerializer.Deserialize<SubtitleStyle>("{\"FontSize\":58}");
        Assert.Equal(TvDisplayMode.InApp, old!.DisplayMode);
        var saved = System.Text.Json.JsonSerializer.Serialize(old with { DisplayMode = TvDisplayMode.Overlay });
        Assert.Contains("\"Overlay\"", saved);
        Assert.Equal(TvDisplayMode.Overlay, System.Text.Json.JsonSerializer.Deserialize<SubtitleStyle>(saved)!.DisplayMode);
    }

    [Fact]
    public void OldSavedStyleGetsNewDefaults()
    {
        var style = System.Text.Json.JsonSerializer.Deserialize<SubtitleStyle>("{\"FontSize\":58,\"BottomPercent\":12}");
        Assert.Equal(new SubtitleStyle(58, 12, SubtitlePlacement.Cover, 72, false), style);
        var again = System.Text.Json.JsonSerializer.Deserialize<SubtitleStyle>(System.Text.Json.JsonSerializer.Serialize(style! with { Placement = SubtitlePlacement.Below }));
        Assert.Equal(SubtitlePlacement.Below, again!.Placement);
    }

    [Fact]
    public async Task ClientCountDropsWhenClientDisconnects()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        var response = await Http.GetAsync($"http://127.0.0.1:{port}/events", HttpCompletionOption.ResponseHeadersRead);
        await WaitUntil(() => server.ConnectedClients == 1);
        response.Dispose();
        await WaitUntil(() => server.ConnectedClients == 0);
    }

    [Fact]
    public async Task HelloIdentifiesTheApp()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        using var http = new HttpClient();
        var hello = await http.GetStringAsync($"http://127.0.0.1:{port}/hello");
        Assert.Contains("\"app\":\"gametranslator\"", hello);
        Assert.Contains("\"active\":false", hello);
        server.Active = true;
        Assert.Contains("\"active\":true", await http.GetStringAsync($"http://127.0.0.1:{port}/hello"));
    }

    [Fact]
    public async Task UnknownPathIs404()
    {
        using var server = new SubtitleServer(0);
        var port = await server.StartAsync();
        using var response = await Http.GetAsync($"http://127.0.0.1:{port}/nope");
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }
}

