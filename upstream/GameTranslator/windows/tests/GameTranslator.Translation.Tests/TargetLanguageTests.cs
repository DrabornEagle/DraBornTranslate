// SPDX-License-Identifier: AGPL-3.0-only

using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using GameTranslator.Core;

namespace GameTranslator.Translation.Tests;

/// Çeviri dili Türkçe dışında bir dil olduğunda motorlara doğru dilin gitmesi (eskiden İngilizce→Türkçe sabitti).
public class TargetLanguageTests
{
    private sealed class Capture(Func<string, string> reply) : HttpMessageHandler
    {
        public List<(HttpRequestMessage Request, string Body)> Requests { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var body = request.Content is null ? "" : await request.Content.ReadAsStringAsync(cancellationToken);
            Requests.Add((request, body));
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(reply(body), Encoding.UTF8, "application/json") };
        }
    }

    private static string DeepLEcho(string body)
    {
        var texts = JsonDocument.Parse(body).RootElement.GetProperty("text").EnumerateArray().Select(t => t.GetString());
        return JsonSerializer.Serialize(new { translations = texts.Select(t => new { text = "[x] " + t }) });
    }

    [Theory]
    [InlineData("en", "ar", "EN", "AR")]
    [InlineData("en", "ja", "EN", "JA")]
    [InlineData("ja", "en", "JA", "EN-US")]
    [InlineData("en", "pt", "EN", "PT-BR")]
    [InlineData("en", "zh", "EN", "ZH-HANS")]
    [InlineData("en", "hi", "EN", "HI")]
    [InlineData("en", "fa", "EN", "FA")]
    [InlineData("en", "tr", "EN", "TR")]
    public async Task DeepLGetsTheChosenLanguages(string source, string target, string expectedSource, string expectedTarget)
    {
        var http = new Capture(DeepLEcho);
        var translator = new DeepLTranslator("abc:fx", null, new HttpClient(http), source: source, target: target);
        Assert.Equal("[x] We move on.", await translator.TranslateAsync("We move on."));
        var json = JsonDocument.Parse(http.Requests[0].Body).RootElement;
        Assert.Equal(expectedSource, json.GetProperty("source_lang").GetString());
        Assert.Equal(expectedTarget, json.GetProperty("target_lang").GetString());
    }

    [Theory]
    [InlineData("xx")]
    [InlineData("fi")]       // listemizde olmayan dil
    [InlineData("")]
    public void DeepLDoesNotPretendToSupportEveryLanguage(string target)
    {
        Assert.False(DeepLTranslator.Supports(target));
        Assert.Throws<ArgumentException>(() => new DeepLTranslator("abc:fx", null, source: "en", target: target));
    }

    /// Terim sözlüğü İngilizce→Türkçedir: başka dile çevirirken adlar etiketlenmez, Türkçe terim karşılıkları kullanılmaz.
    [Fact]
    public async Task TurkishGlossaryIsNotUsedForOtherLanguages()
    {
        var directory = Path.Combine(Path.GetTempPath(), "gt-glossary-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            File.WriteAllText(Path.Combine(directory, "test.json"), """{"keep":["Soul"],"terms":{"Old Gods":"Kadim Tanrılar"}}""");
            var glossary = new GlossaryStore(directory);

            var turkish = new Capture(DeepLEcho);
            await new DeepLTranslator("abc:fx", null, new HttpClient(turkish), glossary: glossary).TranslateAsync("Soul began to fight.");
            var arabic = new Capture(DeepLEcho);
            await new DeepLTranslator("abc:fx", null, new HttpClient(arabic), glossary: glossary, source: "en", target: "ar").TranslateAsync("Soul began to fight.");

            Assert.Contains("tag_handling", turkish.Requests[0].Body);        // Türkçede sözlük adı etiketle korunur
            Assert.DoesNotContain("tag_handling", arabic.Requests[0].Body);   // Arapçada düz metin gider
            Assert.Equal("Soul began to fight.", JsonDocument.Parse(arabic.Requests[0].Body).RootElement.GetProperty("text")[0].GetString());
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void AiPromptForTurkishIsUnchanged()
    {
        Assert.Equal(AiRequest.SystemPrompt, AiRequest.SystemPromptFor("en", "tr"));
        Assert.Equal(AiRequest.SystemPrompt, AiRequest.SystemPromptFor(null, null));
        Assert.Equal(AiRequest.UserMessage(["Hello."], []), AiRequest.UserMessage(["Hello."], [], source: "en", target: "tr"));
    }

    [Theory]
    [InlineData("en", "ar", "English", "Arabic")]
    [InlineData("en", "ja", "English", "Japanese")]
    [InlineData("ja", "en", "Japanese", "English")]
    [InlineData("en", "zh", "English", "Chinese (Simplified)")]
    public void AiPromptNamesTheChosenLanguages(string source, string target, string from, string to)
    {
        var system = AiRequest.SystemPromptFor(source, target);
        Assert.Contains($"{from} video game subtitles into natural, spoken {to}", system);
        Assert.DoesNotContain("Turkish", system);
        Assert.DoesNotContain("\"sen\"", system);   // Türkçeye özgü hitap kuralı başka dile gitmez

        var user = AiRequest.UserMessage(["Hello."], [new AiContextLine("Before.", "Önce.")], game: "Elden Ring", source: source, target: target);
        Assert.Contains($"into {to}.", user);
        Assert.Contains($"{from}: Before.", user);
        Assert.Contains($"{to}: Önce.", user);
        Assert.DoesNotContain("Turkish", user);
    }

    [Fact]
    public async Task GeminiRequestCarriesTheChosenLanguage()
    {
        var http = new Capture(_ => """{"candidates":[{"content":{"parts":[{"text":"[\"行こう。\"]"}]},"finishReason":"STOP"}]}""");
        var translator = new AiTranslator(AiProvider.Gemini, "key", model: null, fallback: null, http: new HttpClient(http), source: "en", target: "ja");
        Assert.Equal("行こう。", await translator.TranslateAsync("Let's go."));
        var json = JsonNode.Parse(http.Requests[0].Body)!;
        Assert.Contains("spoken Japanese", json["systemInstruction"]!["parts"]![0]!["text"]!.GetValue<string>());
        Assert.Contains("into Japanese", json.ToJsonString());
    }
}
