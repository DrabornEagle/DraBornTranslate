// SPDX-License-Identifier: AGPL-3.0-only

using GameTranslator.Core;

namespace GameTranslator.Core.Tests;

/// <summary>Ek alanlar: ana altyazıdan ayrı, seyrek okunur; yazı kararlıysa ve değiştiyse çevrilir (CouchSubs / Nino RegionWatcherTests uyarlaması).</summary>
public class RegionWatcherTests
{
    private sealed class Reader : ITextReader
    {
        public List<TextLine> Lines { get; set; } = [];
        public int Count;
        public int FailuresRemaining;
        public string Read(Frame image) => string.Join('\n', Lines.Select(line => line.Text));
        public Reading ReadLines(Frame image, bool deep)
        {
            Interlocked.Increment(ref Count);
            if (FailuresRemaining > 0)
            {
                FailuresRemaining--;
                throw new InvalidOperationException("Expected OCR failure");
            }
            return new Reading(Lines);
        }
    }

    private sealed class Translator : ITextTranslator
    {
        public List<string> Calls { get; } = [];
        public int FailuresRemaining;
        public Task<string> TranslateAsync(string text, CancellationToken cancellationToken = default)
        {
            lock (Calls)
            {
                Calls.Add(text);
                if (FailuresRemaining > 0)
                {
                    FailuresRemaining--;
                    return Task.FromException<string>(new InvalidOperationException("Expected test failure"));
                }
            }
            return Task.FromResult("TR: " + text);
        }
    }

    private sealed class Server : IRegionSender
    {
        public List<(string Id, string Text, TextBox? Box, double? LineHeight)> Sent { get; } = [];
        public int Clears;
        public void SendRegion(string id, string text, TextBox? box, double? lineHeight = null)
        {
            lock (Sent) Sent.Add((id, text, box, lineHeight));
        }
        public void ClearRegions() => Interlocked.Increment(ref Clears);
    }

    /// <summary>Düz gri kare; <paramref name="shade"/> değişince bölge izi değişir.</summary>
    private static Frame Shade(byte shade)
    {
        var frame = Frame.Blank(320, 180);
        Array.Fill(frame.Pixels, shade);
        return frame;
    }

    private static readonly WatchedArea Quest = new("q", "Quest", ExtraAreaPreset.Quest.Region());
    private static readonly WatchedArea Phone = new("p", "Phone", ExtraAreaPreset.Phone.Region());

    private static TextLine Line(string text, double top = 0.2, double height = 0.1) => new(text, new TextBox(0.1, top, 0.9, top + height));

    [Fact]
    public async Task FailedReadRetriesOnAnUnchangedFrameThenStopsReading()
    {
        var reader = new Reader { Lines = [Line("Find the blacksmith")], FailuresRemaining = 1 };
        var translator = new Translator();
        var server = new Server();
        var watcher = new RegionWatcher(reader, translator, server);
        await watcher.PassAsync(Shade(10), [Quest]);
        await watcher.PassAsync(Shade(10), [Quest]);
        Assert.Empty(server.Sent); // Başarılı yeni okuma yine kararlılık bekler.
        for (var i = 0; i < 4; i++) await watcher.PassAsync(Shade(10), [Quest]);
        Assert.Equal(2, reader.Count);
        Assert.Equal(["Find the blacksmith"], translator.Calls);
        Assert.Equal(["TR: Find the blacksmith"], server.Sent.Select(s => s.Text));
    }

    [Fact]
    public async Task FailedReadKeepsShownTextAndDiscardsUnconfirmedReading()
    {
        var reader = new Reader { Lines = [Line("Talk to Arthur")] };
        var translator = new Translator();
        var server = new Server();
        var watcher = new RegionWatcher(reader, translator, server);
        await watcher.PassAsync(Shade(10), [Quest]);
        await watcher.PassAsync(Shade(10), [Quest]);
        reader.Lines = [Line("Meet Arthur at the barn")];
        await watcher.PassAsync(Shade(60), [Quest]);
        reader.FailuresRemaining = 1;
        await watcher.PassAsync(Shade(100), [Quest]);
        reader.Lines = [Line("Find the blacksmith")];
        await watcher.PassAsync(Shade(100), [Quest]);
        Assert.Equal(["TR: Talk to Arthur"], server.Sent.Select(s => s.Text));
        await watcher.PassAsync(Shade(100), [Quest]);
        Assert.Equal(["Talk to Arthur", "Find the blacksmith"], translator.Calls);
        Assert.Equal(["TR: Talk to Arthur", "TR: Find the blacksmith"], server.Sent.Select(s => s.Text));
    }

    [Fact]
    public async Task FailedReadDoesNotBlockAnotherArea()
    {
        var reader = new Reader { Lines = [Line("Find the blacksmith")], FailuresRemaining = 1 };
        var server = new Server();
        var watcher = new RegionWatcher(reader, new Translator(), server);
        await watcher.PassAsync(Shade(10), [Quest, Phone]);
        await watcher.PassAsync(Shade(10), [Quest, Phone]);
        Assert.Equal(["p"], server.Sent.Select(s => s.Id));
        await watcher.PassAsync(Shade(10), [Quest, Phone]);
        Assert.Equal(["p", "q"], server.Sent.Select(s => s.Id));
        Assert.Equal(3, reader.Count);
    }

    [Fact]
    public async Task RepeatedReadFailuresStillRespectThePassInterval()
    {
        long clock = 0;
        var reader = new Reader { FailuresRemaining = 10 };
        var server = new Server();
        var watcher = new RegionWatcher(reader, new Translator(), server, clock: () => clock);
        for (var step = 0; step <= 20; step++)
        {
            clock = step * RegionWatcher.DefaultIntervalMs / 10;
            watcher.Offer(Shade(10), [Quest]);
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            while (watcher.IsBusy) await Task.Delay(5, timeout.Token);
        }
        Assert.Equal(3, watcher.Passes);
        Assert.Equal(3, reader.Count);
        Assert.Empty(server.Sent);
    }

    [Fact]
    public async Task StableTextIsTranslatedOnceAndPlacedInItsArea()
    {
        var (reader, translator, server) = (new Reader(), new Translator(), new Server());
        var watcher = new RegionWatcher(reader, translator, server);
        reader.Lines = [Line("Find the blacksmith")];
        await watcher.PassAsync(Shade(10), [Quest]);
        Assert.Empty(server.Sent);
        await watcher.PassAsync(Shade(10), [Quest]);
        var sent = Assert.Single(server.Sent);
        Assert.Equal("TR: Find the blacksmith", sent.Text);
        Assert.True(sent.Box!.Value.Left > Quest.Region.Left && sent.Box.Value.Right < Quest.Region.Right);
        await watcher.PassAsync(Shade(10), [Quest]);
        await watcher.PassAsync(Shade(10), [Quest]);
        Assert.Equal(1, reader.Count);
        Assert.Single(translator.Calls);
        Assert.Single(server.Sent);
    }

    [Fact]
    public async Task SameTextAtNewGeometryUpdatesRegionWithoutRetranslating()
    {
        var reader = new Reader { Lines = [Line("Find the blacksmith", 0.2, 0.1)] };
        var translator = new Translator();
        var server = new Server();
        var watcher = new RegionWatcher(reader, translator, server);
        await watcher.PassAsync(Shade(10), [Quest]);
        await watcher.PassAsync(Shade(10), [Quest]);
        var first = server.Sent[0];

        reader.Lines = [Line("Find the blacksmith", 0.45, 0.2)];
        await watcher.PassAsync(Shade(60), [Quest]);

        Assert.Equal(["TR: Find the blacksmith", "TR: Find the blacksmith"], server.Sent.Select(s => s.Text));
        Assert.NotEqual(first.Box?.Top, server.Sent[1].Box?.Top);
        Assert.NotEqual(first.LineHeight, server.Sent[1].LineHeight);
        Assert.Equal(["Find the blacksmith"], translator.Calls);
    }

    [Fact]
    public async Task FailedTranslationRetriesWithoutLosingPreviouslyShownText()
    {
        var (reader, translator, server) = (new Reader(), new Translator(), new Server());
        var watcher = new RegionWatcher(reader, translator, server);
        reader.Lines = [Line("Talk to Arthur")];
        await watcher.PassAsync(Shade(10), [Quest]);
        await watcher.PassAsync(Shade(10), [Quest]);
        Assert.Equal(["TR: Talk to Arthur"], server.Sent.Select(s => s.Text));

        reader.Lines = [Line("Meet Arthur at the barn")];
        translator.FailuresRemaining = 1;
        await watcher.PassAsync(Shade(90), [Quest]);
        await watcher.PassAsync(Shade(90), [Quest]);
        Assert.Equal(["TR: Talk to Arthur"], server.Sent.Select(s => s.Text));

        await watcher.PassAsync(Shade(90), [Quest]);
        Assert.Equal(["Talk to Arthur", "Meet Arthur at the barn", "Meet Arthur at the barn"], translator.Calls);
        Assert.Equal(["TR: Talk to Arthur", "TR: Meet Arthur at the barn"], server.Sent.Select(s => s.Text));
    }

    [Fact]
    public async Task MovingBackgroundWithTheSameTextIsNotTranslatedAgain()
    {
        var (reader, translator, server) = (new Reader(), new Translator(), new Server());
        var watcher = new RegionWatcher(reader, translator, server);
        reader.Lines = [Line("Talk to Arthur")];
        foreach (var shade in new byte[] { 10, 60, 110, 160, 210 }) await watcher.PassAsync(Shade(shade), [Quest]);
        Assert.Equal(5, reader.Count);
        Assert.Equal(["Talk to Arthur"], translator.Calls);
        Assert.Equal(["TR: Talk to Arthur"], server.Sent.Select(s => s.Text));
    }

    [Fact]
    public async Task ChangedTextIsTranslatedAndEmptyAreaRemovesTheBox()
    {
        var (reader, server) = (new Reader(), new Server());
        var watcher = new RegionWatcher(reader, new Translator(), server);
        reader.Lines = [Line("Talk to Arthur")];
        await watcher.PassAsync(Shade(10), [Quest]);
        await watcher.PassAsync(Shade(10), [Quest]);
        reader.Lines = [Line("Meet Arthur at the barn")];
        await watcher.PassAsync(Shade(90), [Quest]);
        await watcher.PassAsync(Shade(90), [Quest]);
        reader.Lines = [];
        await watcher.PassAsync(Shade(200), [Quest]);
        await watcher.PassAsync(Shade(200), [Quest]);
        Assert.Equal(["TR: Talk to Arthur", "TR: Meet Arthur at the barn", ""], server.Sent.Select(s => s.Text));
    }

    [Fact]
    public async Task PromptsAndNumbersShowNothing()
    {
        var (reader, server) = (new Reader(), new Server());
        var watcher = new RegionWatcher(reader, new Translator(), server);
        reader.Lines = [Line("R2 Swing"), Line("23m", 0.5)];
        await watcher.PassAsync(Shade(10), [Quest]);
        await watcher.PassAsync(Shade(10), [Quest]);
        Assert.Empty(server.Sent);
    }

    [Fact]
    public async Task PackTranslationIsUsedBeforeTheEngine()
    {
        var (reader, translator, server) = (new Reader(), new Translator(), new Server());
        var watcher = new RegionWatcher(reader, translator, server)
        {
            PackLookup = text => text == "New message" ? new PackMatch("Yeni mesaj", []) : null,
        };
        reader.Lines = [Line("New message", 0.1), Line("Call me when you get this.", 0.25)];
        await watcher.PassAsync(Shade(10), [Quest]);
        await watcher.PassAsync(Shade(10), [Quest]);
        Assert.Equal(["Yeni mesaj\nTR: Call me when you get this."], server.Sent.Select(s => s.Text));
        Assert.Equal(["Call me when you get this."], translator.Calls);
    }

    [Fact]
    public async Task RemovedAreaLosesItsBoxAndResetClearsAll()
    {
        var (reader, server) = (new Reader(), new Server());
        var watcher = new RegionWatcher(reader, new Translator(), server);
        reader.Lines = [Line("Where are you?")];
        await watcher.PassAsync(Shade(10), [Quest, Phone]);
        await watcher.PassAsync(Shade(10), [Quest, Phone]);
        Assert.Equal(["p", "q"], server.Sent.Select(s => s.Id).Order());
        await watcher.PassAsync(Shade(10), [Quest]);
        Assert.Equal(("p", ""), (server.Sent[^1].Id, server.Sent[^1].Text));
        watcher.Reset();
        Assert.Equal(1, server.Clears);
        watcher.Reset();
        Assert.Equal(1, server.Clears);
    }

    [Fact]
    public async Task OfferRunsAtMostOncePerInterval()
    {
        long clock = 0;
        var (reader, server) = (new Reader(), new Server());
        var watcher = new RegionWatcher(reader, new Translator(), server, clock: () => clock);
        reader.Lines = [Line("Find the blacksmith")];
        var frame = Shade(10);
        async Task OfferAndWait()
        {
            watcher.Offer(frame, [Quest]);
            while (watcher.IsBusy) await Task.Delay(5);
        }
        await OfferAndWait();
        for (var step = 1; step <= 9; step++)
        {
            clock = step * RegionWatcher.DefaultIntervalMs / 10;
            await OfferAndWait();
        }
        Assert.Equal(1, watcher.Passes);
        clock = RegionWatcher.DefaultIntervalMs;
        await OfferAndWait();
        Assert.Equal(2, watcher.Passes);
        Assert.Equal(["TR: Find the blacksmith"], server.Sent.Select(s => s.Text));
    }

    /// <summary>Alanın kenarına değen satır (alanın dışında süren yazının ucu) çevrilmez.</summary>
    [Fact]
    public void LinesCutByTheAreaEdgeAreSkipped()
    {
        var cut = new TextLine("ot it?", new TextBox(0.0, 0.40, 0.12, 0.50));
        var card = new TextLine("Good work covering with Brian", new TextBox(0.15, 0.60, 0.90, 0.70));
        var candidate = RegionWatcher.MakeCandidate(new Reading([cut, card]), ExtraAreaPreset.PhoneMessage.Region());
        Assert.Equal(["Good work covering with Brian"], candidate.Paragraphs);
    }

    /// <summary>Başka renkte yazılmış ad ayrı satır okunsa da yerinde kalır ("Follow Brian", "Brian Follow" değil).</summary>
    [Fact]
    public void SameRowPartsKeepTheirOrder()
    {
        var follow = new TextLine("Follow", new TextBox(0.40, 0.30, 0.48, 0.40));
        var brian = new TextLine("Brian", new TextBox(0.50, 0.29, 0.58, 0.39));
        Assert.Equal(["Follow", "Brian"], RegionWatcher.InReadingOrder([brian, follow]).Select(line => line.Text));
    }

    /// <summary>Menü listesi (GTA 6 duraklatma menüsü) çevrilmez; ipucu cümlesi ve tek satırlık görev çevrilir.</summary>
    [Fact]
    public void MenuListsAreNotTranslated()
    {
        Assert.True(RegionWatcher.IsMenuList([Line("Game", 0.1), Line("○ Store", 0.2), Line("O Help", 0.3)]));
        Assert.False(RegionWatcher.IsMenuList([Line("Follow the manager", 0.1)]));
        // Haritadaki dikey tuş listesi menü değildir (sonu tuşla biter), çevrilir; yatay tuş çubuğu tek satırdır.
        Assert.False(RegionWatcher.IsMenuList([Line("RECENTER L3", 0.1), Line("2D VIEW R3", 0.2), Line("SET DESTINATION ✕", 0.3), Line("EXIT ○", 0.4)]));
        Assert.False(RegionWatcher.IsMenuList([Line("PHOTO STUDIO □ SELECT ✕ BACK ○", 0.1)]));
        Assert.False(RegionWatcher.IsMenuList([Line("You can use the RydeMe", 0.1), Line("app to order", 0.2), Line("a ride.", 0.3)]));
        var candidate = RegionWatcher.MakeCandidate(new Reading([Line("Map", 0.1), Line("Brief", 0.2), Line("Progress", 0.3)]), ExtraAreaPreset.HelpBox.Region());
        Assert.Empty(candidate.Paragraphs);
    }

    /// <summary>Görev yeniden belirince yarım okuması gösterilmez, çevirisi çevirmene gitmeden yeniden kullanılır.</summary>
    [Fact]
    public async Task ReappearingTextWaitsForItsEndAndReusesTheTranslation()
    {
        var (reader, translator, server) = (new Reader(), new Translator(), new Server());
        var watcher = new RegionWatcher(reader, translator, server);
        async Task Show(string text, byte shade)
        {
            reader.Lines = text.Length == 0 ? [] : [Line(text)];
            await watcher.PassAsync(Shade(shade), [Quest]);
            await watcher.PassAsync(Shade(shade), [Quest]);
        }
        await Show("Fly to a drop", 10);
        await Show("", 70);
        await Show("Fly to a", 130);
        // Yarım okuma bekletilir: TV'de hâlâ boş, çevirmene gidilmedi.
        Assert.Equal(("q", ""), (server.Sent[^1].Id, server.Sent[^1].Text));
        await Show("Fly to a drop", 190);
        Assert.Equal("TR: Fly to a drop", server.Sent[^1].Text);
        Assert.Equal(["Fly to a drop"], translator.Calls);
    }

    /// <summary>Neredeyse aynı okuma ekrandakini değiştirmez; daha çok tuş simgesi bulan okuma değiştirir.</summary>
    [Fact]
    public async Task NearlyIdenticalReadingKeepsTheShownTextUnlessItFindsMoreIcons()
    {
        var (reader, translator, server) = (new Reader(), new Translator(), new Server());
        var watcher = new RegionWatcher(reader, translator, server);
        async Task Show(string text, byte shade)
        {
            reader.Lines = [Line(text)];
            await watcher.PassAsync(Shade(shade), [Quest]);
            await watcher.PassAsync(Shade(shade), [Quest]);
        }
        await Show("ADD MARKER □ SET DESTINATION EXIT ○", 10);
        await Show("ADD MARKER □ SET DESTINATIPN EXIT ○", 70);
        Assert.Single(server.Sent);
        // (Sahte çevirmenin "TR: …" çevirisi aslına çok benzediği için kutu boş gönderilir; sayılan, gönderim sayısıdır.)
        await Show("ADD MARKER □ SET DESTINATION ✕ EXIT ○", 130);
        Assert.Equal(2, server.Sent.Count);
        Assert.Equal(2, translator.Calls.Count);
        await Show("ADD MARKER □ SET DESTINATION EXIT ○", 190);
        Assert.Equal(2, server.Sent.Count);
    }

    [Fact]
    public void ReadingThatSwapsAWordForAnIconLosesWords()
    {
        Assert.True(RegionWatcher.LosesWords("find □", "find donnie"));
        Assert.False(RegionWatcher.LosesWords("add marker □ set destination ✕ exit ○", "add marker □ set destination exit ○"));
    }

    [Fact]
    public void ShorterReadingOfTheShownTextIsRecognized()
    {
        Assert.True(RegionWatcher.IsPartOf("add marker □ set destinationlo", "add marker □ set destinatioy ✕ exit ○"));
        Assert.False(RegionWatcher.IsPartOf("follow brian", "add marker □ set destination ✕ exit ○"));
        Assert.False(RegionWatcher.IsPartOf("fly to a drop point", "fly to a"));
    }

    [Fact]
    public void NoAreasMeansNoWork()
    {
        var reader = new Reader();
        var watcher = new RegionWatcher(reader, new Translator(), new Server());
        watcher.Offer(Shade(10), []);
        Assert.False(watcher.IsBusy);
        Assert.Equal(0, reader.Count);
    }
}
