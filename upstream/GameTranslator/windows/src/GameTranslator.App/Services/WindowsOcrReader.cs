// SPDX-License-Identifier: AGPL-3.0-only

using System.Runtime.InteropServices.WindowsRuntime;
using GameTranslator.Core;
using Windows.Globalization;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;

namespace GameTranslator.App.Services;

/// <summary>
/// Windows'un cihaz üstü yazı tanıması. Oyun dilinin OCR paketi yoksa kullanıcının yüklü dillerinden
/// biri kullanılır (Latin alfabeli diller İngilizce altyazıyı da okuyabilir). Satırlar yukarıdan aşağı sıralanır.
/// </summary>
public sealed class WindowsOcrReader : ITextReader, IIconReader
{
    /// <inheritdoc />
    public bool ReadsIcons { get; set; }

    /// <inheritdoc />
    public IReadOnlyList<ColoredPhrase> LastColored { get; private set; } = [];

    /// <summary>Simgelerin hangi kumandadan olduğu kararı (seçilmişse sabit, Otomatik'te karelerden).</summary>
    private readonly ButtonLayoutVote layout = new();

    /// <inheritdoc />
    public ButtonLayout ButtonLayout
    {
        get => layout.Setting;
        set
        {
            if (layout.Setting == value) return;
            layout.Setting = value;
            layout.Reset();
        }
    }

    /// <inheritdoc />
    public void ResetLayout() => layout.Reset();

    private readonly OcrEngine engine;

    private WindowsOcrReader(OcrEngine engine) => this.engine = engine;

    /// <summary>Hiçbir OCR dili yüklü değilse null.</summary>
    public static WindowsOcrReader? Create(string language)
    {
        var engine = OcrEngine.TryCreateFromLanguage(new Language(language))
                     ?? OcrEngine.TryCreateFromUserProfileLanguages();
        return engine is null ? null : new WindowsOcrReader(engine);
    }

    /// <summary>Oyun dilinin kendi OCR paketi yüklü mü (değilse ayarlarda eklenmesi önerilir).</summary>
    public static bool HasLanguage(string language) => OcrEngine.IsLanguageSupported(new Language(language));

    public string Read(Frame image) => Read(image, deep: true);

    public string Read(Frame image, bool deep) => string.Join(' ', ReadLines(image, deep).Lines.Select(line => line.Text));

    public Reading ReadLines(Frame image, bool deep) => deep ? ReadingFallback.FirstLines(image, Recognize) : Recognize(image);

    /// <summary>
    /// Kare hem olduğu gibi hem de yalnız beyaz yazısı bırakılarak okunur; daha sözcüğe benzeyen okuma seçilir.
    /// Parlak sahnelerde ikincisi, renkli altyazılarda birincisi kazanır.
    /// </summary>
    private Reading Recognize(Frame image)
    {
        var raw = RecognizeOnce(image);
        // Ek alanın okuyucusu (simge tanıma açık) yalnız olduğu gibi okur: beyaz yazı süzgeci oyunun renkli yazdığı sözcüğü
        // ("Fly to a drop point" içinde pembe "drop point") siliyordu; oyunun kutuları koyu zeminli olduğu için gerekmiyor.
        if (ReadsIcons || image.Width < 40 || image.Height < 40) return WithIcons(raw, image).Reading;
        var whiteText = RecognizeOnce(TextImage.WhiteTextOnly(image));
        // Renkli konuşmacı adı beyaz okumada düşer; seçilen okuma adsızsa ad öteki okumadan alınır. Simgeler yalnız seçilen
        // okumada ve her zaman özgün kareden aranır (beyaz yazı süzgeci renkli simgeyi siler).
        return OcrNoise.Score(whiteText.Reading.Text) > OcrNoise.Score(raw.Reading.Text)
            ? SpeakerMerge.WithSpeakerFrom(WithIcons(whiteText, image).Reading, raw.Reading)
            : SpeakerMerge.WithSpeakerFrom(WithIcons(raw, image).Reading, whiteText.Reading);
    }

    /// <summary>Bir okuma ve her satırının sözcükleri (yerleriyle; simge tanıma için).</summary>
    private sealed record Recognized(Reading Reading, IReadOnlyList<IReadOnlyList<GameTranslator.Core.OcrWord>> Words);

    private Recognized RecognizeOnce(Frame image)
    {
        // OCR motoru çok küçük ya da çok büyük görüntüyü reddeder.
        if (image.Width < 40 || image.Height < 40 || image.Width > OcrEngine.MaxImageDimension || image.Height > OcrEngine.MaxImageDimension)
            return new Recognized(Reading.Empty, []);
        using var bitmap = new SoftwareBitmap(BitmapPixelFormat.Bgra8, image.Width, image.Height, BitmapAlphaMode.Premultiplied);
        bitmap.CopyFromBuffer(image.Pixels.AsBuffer());
        var result = engine.RecognizeAsync(bitmap).AsTask().GetAwaiter().GetResult();
        double width = image.Width, height = image.Height;
        var parts = result.Lines
            .SelectMany(line => Split(line, width, height))
            .OrderBy(part => part.Line.Box?.Top ?? 0)
            .ToList();
        return new Recognized(new Reading(parts.Select(part => part.Line)), [.. parts.Select(part => part.Words)]);
    }

    /// <summary>
    /// Simge tanıma açıksa sözcük arasındaki simge boşluğu ve simgenin yanlış okunmuş hali ("•", "@") görüntüden tanınır;
    /// satırın kenarındaki simge de aranır ("□ Pick up").
    /// </summary>
    private Recognized WithIcons(Recognized recognized, Frame image)
    {
        if (!ReadsIcons) return recognized;
        // Renkli yazılmış sözcükler (GTA 6: "Go to the plane" içinde pembe "plane"); çeviride aynı renkte gösterilir.
        LastColored = [.. recognized.Words.SelectMany(words => ColoredWords.Phrases(words, image))];
        if (recognized.Reading.Lines.Count == 0) return recognized;
        double width = image.Width, height = image.Height;
        var lines = recognized.Reading.Lines.Select((line, index) =>
        {
            if (line.Box is not { } lineBox) return line;
            // Düz okunan tuş adları ("Hold LT to aim") Otomatik kararı besler.
            IconWords.Observe(line.Text, layout);
            var text = IconWords.WithIcons(line.Text, recognized.Words[index], width, height,
                place => ButtonIcons.Classify(image, place, ReadPlain, line: lineBox, readWords: ReadWords, layout: layout));
            text = IconWords.WithEdgeIcons(text, lineBox, width, height, place => ButtonIcons.Classify(image, place, ReadPlain, strict: true,
                line: lineBox.Union(place), readWords: ReadWords, layout: layout));
            // Xbox oyununda harf olarak okunan tuşlar ("Press A to jump") işarete çevrilir.
            text = IconWords.WithXboxLetters(text, layout.Current);
            return text == line.Text ? line : line with { Text = text.Trim() };
        }).ToList();
        return recognized with { Reading = new Reading(lines) };
    }

    /// <summary>Sözcükler ve görüntüdeki yerleri (0–1); simge tanıma satırın siyah-beyaz okumasında simgenin yerine düşen sözcüğü arar.</summary>
    private IReadOnlyList<GameTranslator.Core.OcrWord> ReadWords(Frame image)
    {
        if (image.Width < 40 || image.Height < 40 || image.Width > OcrEngine.MaxImageDimension || image.Height > OcrEngine.MaxImageDimension)
            return [];
        using var bitmap = new SoftwareBitmap(BitmapPixelFormat.Bgra8, image.Width, image.Height, BitmapAlphaMode.Premultiplied);
        bitmap.CopyFromBuffer(image.Pixels.AsBuffer());
        double width = image.Width, height = image.Height;
        return [.. engine.RecognizeAsync(bitmap).AsTask().GetAwaiter().GetResult().Lines.SelectMany(line => line.Words).Select(word =>
            new GameTranslator.Core.OcrWord(word.Text, new TextBox(word.BoundingRect.X / width, word.BoundingRect.Y / height,
                (word.BoundingRect.X + word.BoundingRect.Width) / width, (word.BoundingRect.Y + word.BoundingRect.Height) / height)))];
    }

    /// <summary>Simgenin içindeki kısa yazı ("L2"): kesit simge tanımadan geçmeden düz okunur.</summary>
    private string? ReadPlain(Frame image)
    {
        if (image.Width < 40 || image.Height < 40 || image.Width > OcrEngine.MaxImageDimension || image.Height > OcrEngine.MaxImageDimension)
            return null;
        using var bitmap = new SoftwareBitmap(BitmapPixelFormat.Bgra8, image.Width, image.Height, BitmapAlphaMode.Premultiplied);
        bitmap.CopyFromBuffer(image.Pixels.AsBuffer());
        var text = engine.RecognizeAsync(bitmap).AsTask().GetAwaiter().GetResult().Text;
        return string.IsNullOrWhiteSpace(text) ? null : text;
    }

    /// <summary>
    /// Satırın kutusu sözcük kutularının birleşimidir; görüntünün kenarlarına oranla verilir. Windows aynı hizadaki
    /// uzak yazıları (soldaki menü, sağdaki gösterge) bazen tek satır sayar; aradaki boşluk bir sözcük boşluğu olamayacak
    /// kadar genişse satır orada ayrı satırlara bölünür (<see cref="Reading.IsSeparateText"/>).
    /// </summary>
    private static IEnumerable<(TextLine Line, IReadOnlyList<GameTranslator.Core.OcrWord> Words)> Split(OcrLine line, double width, double height)
    {
        var words = line.Words.ToList();
        if (words.Count == 0) yield break;
        var lineHeight = words.Max(word => word.BoundingRect.Height);
        var start = 0;
        for (var index = 1; index <= words.Count; index++)
        {
            var end = index == words.Count;
            if (!end && !Reading.IsSeparateText(words[index].BoundingRect.X - (words[index - 1].BoundingRect.X + words[index - 1].BoundingRect.Width), lineHeight, width))
                continue;
            var part = words.GetRange(start, index - start);
            var located = part.Select(word => new GameTranslator.Core.OcrWord(word.Text, new TextBox(word.BoundingRect.X / width, word.BoundingRect.Y / height,
                (word.BoundingRect.X + word.BoundingRect.Width) / width, (word.BoundingRect.Y + word.BoundingRect.Height) / height))).ToList();
            TextBox? box = null;
            foreach (var word in located) box = box?.Union(word.Box) ?? word.Box;
            // Bölünmeyen satırın metni Windows'un verdiği gibi kalır (boşluksuz yazılan dillerde sözcükler birleşik).
            var text = start == 0 && end ? line.Text : string.Join(' ', part.Select(word => word.Text));
            yield return (new TextLine(text.Trim(), box), located);
            start = index;
        }
    }
}
