// SPDX-License-Identifier: AGPL-3.0-only

namespace GameTranslator.Core;

/// <summary>
/// Yazı tanımanın atladığı ya da yanlış okuduğu kumanda simgesini görüntüden tanır. Oyunlar düğmeyi yazının içine küçük
/// bir resim olarak koyar ("Hold ⓧ to climb"); yazı tanıma o resmi ya hiç okumaz ya da "•", "O", "X" sanır.
/// Nino Game Translator'ın (AGPL-3.0) ButtonIcons.swift'inin uyarlamasıdır.
/// <para>
/// Sıra: simgenin kendisi bulunur (kesitteki en büyük, kareye yakın parlak leke). Renkliyse (sarı "B" gibi) harita
/// işaretidir. Artı biçimliyse yön tuşudur, enliyse dokunmatik yüzeydir. Değilse içindeki yazı ("L2", "R1") okunur;
/// yazı yoksa içindeki sembol (koyu ya da açık olabilir) ✕ ○ △ □ şablonlarıyla karşılaştırılır.
/// </para>
/// </summary>
public static class ButtonIcons
{
    /// <summary>
    /// <paramref name="box"/>: simgenin karedeki kabaca yeri (0–1, üstten). Tanınamazsa null.
    /// <paramref name="readText"/>: küçük bir kesitteki yazıyı okur ("L2" gibi); verilmezse yazılı simgeler tanınmaz.
    /// <paramref name="strict"/>: satırın kenarında aranırken (orada simge olmayabilir) yalnız belirgin, tek başına duran
    /// bir düğme rozeti kabul edilir; arka plandaki leke simge sanılmasın.
    /// </summary>
    /// <para>
    /// <paramref name="line"/> ve <paramref name="readWords"/>: simgenin içinde durduğu satır ve sözcükleri yerleriyle okuyan
    /// tanıyıcı. Windows'un yazı tanıması tek başına kesilmiş kısa yazıyı ("R2") okumuyor, satırın tamamı siyah-beyaza
    /// çevrilince okuyor ("Press R2 for a Heavy Attack"). Yazılı simge önce böyle aranır: simgenin çerçevesi (daire, kutu)
    /// silinir ki "@" okunmasın, satır okunur ve simgenin yerine düşen sözcük alınır.
    /// </para>
    /// <para>
    /// <paramref name="layout"/>: beklenen kumanda (PlayStation / Xbox / Otomatik). Otomatik'te kesin biçimli simgeler
    /// (dairede "A" harfi, üçgen) kararı besler; belirsiz olanlar (mavi daire içinde X: ✕ mı Ⓧ mi) o ana kadarki karara
    /// göre okunur. Verilmezse Otomatik ve kararsız sayılır.
    /// </para>
    public static string? Classify(Frame image, TextBox box, Func<Frame, string?>? readText = null, bool strict = false, TextBox? line = null,
                                   Func<Frame, IReadOnlyList<OcrWord>>? readWords = null, ButtonLayoutVote? layout = null)
    {
        if (Around(image, box) is not { } at) return null;
        Func<int, int, int, int, string?>? readLine = null;
        if (readWords is not null && line is { } lineBox && Around(image, lineBox) is { } lineAt)
            readLine = (x0, y0, x1, y1) => WordAt(lineAt, at.X + x0, at.Y + y0, at.X + x1, at.Y + y1, readWords);
        return Classify(at.Crop, readText, strict, readLine, layout);
    }

    /// <summary>
    /// Satır siyah-beyaz okunur; simgenin (karede x0..x1, y0..y1) çerçevesi silinir, yalnız içi kalır. Simgenin ortasına
    /// düşen sözcük döner.
    /// </summary>
    private static string? WordAt((Frame Crop, int X, int Y) line, int x0, int y0, int x1, int y1, Func<Frame, IReadOnlyList<OcrWord>> readWords)
    {
        var bin = Binarized(line.Crop);
        double cx = (x0 + x1) / 2.0 - line.X, cy = (y0 + y1) / 2.0 - line.Y;
        double rx = Math.Max(1, (x1 - x0) / 2.0), ry = Math.Max(1, (y1 - y0) / 2.0);
        // Dolu beyaz daire (GTA 6: içinde koyu "R3"): siyah-beyaz okumada daire siyah, yazı beyaz kalır ve okunmaz. İçi
        // çoğunlukla siyahsa ters çevrilir, yazı beyaz zeminde siyah olur.
        int inside = 0, dark = 0;
        for (var y = Math.Max(0, y0 - line.Y); y < Math.Min(bin.Height, y1 - line.Y); y++)
            for (var x = Math.Max(0, x0 - line.X); x < Math.Min(bin.Width, x1 - line.X); x++)
            {
                double dx = (x - cx) / rx, dy = (y - cy) / ry;
                if (dx * dx + dy * dy > 0.8 * 0.8) continue;
                inside++;
                if (bin.Pixels[y * bin.Stride + x * 4] == 0) dark++;
            }
        var filled = inside > 0 && dark * 2 > inside;
        for (var y = Math.Max(0, y0 - line.Y - 2); y < Math.Min(bin.Height, y1 - line.Y + 3); y++)
            for (var x = Math.Max(0, x0 - line.X - 2); x < Math.Min(bin.Width, x1 - line.X + 3); x++)
            {
                double dx = (x - cx) / rx, dy = (y - cy) / ry;
                var p = y * bin.Stride + x * 4;
                if (dx * dx + dy * dy <= 0.8 * 0.8)
                {
                    if (filled) bin.Pixels[p] = bin.Pixels[p + 1] = bin.Pixels[p + 2] = (byte)(255 - bin.Pixels[p]);
                    continue;
                }
                bin.Pixels[p] = bin.Pixels[p + 1] = bin.Pixels[p + 2] = 255;
            }
        var (padded, ox, oy, cw, ch) = PaddedMap(bin);
        double left = (ox + x0 - line.X) / cw, right = (ox + x1 - line.X) / cw, top = (oy + y0 - line.Y) / ch, bottom = (oy + y1 - line.Y) / ch;
        var words = readWords(padded);
        Trace?.Invoke("satır okuması: " + string.Join(' ', words.Select(word => word.Text)));
        return words.FirstOrDefault(word =>
        {
            double mx = (word.Box.Left + word.Box.Right) / 2, my = (word.Box.Top + word.Box.Bottom) / 2;
            return mx >= left && mx <= right && my >= top && my <= bottom;
        })?.Text;
    }

    /// <summary>Geliştirme: simge kararlarının izi (testler ve deneme aracı için).</summary>
    internal static Action<string>? Trace;

    /// <summary><paramref name="readLine"/>: simgenin (kesitte x0, y0, x1, y1) yerindeki sözcüğü satırın okumasından verir.</summary>
    public static string? Classify(Frame crop, Func<Frame, string?>? readText = null, bool strict = false, Func<int, int, int, int, string?>? readLine = null,
                                   ButtonLayoutVote? layout = null)
    {
        var vote = layout ?? new ButtonLayoutVote();
        var (token, sure) = Decide(crop, readText, strict, readLine, vote);
        // Yalnız kesin biçimli simge kararı besler: mavi dairedeki X ya da yön tuşu iki ailede de olabilir.
        if (token is not null && sure && ButtonTokens.FamilyOf(token) is { } family) vote.Add(family);
        if (token is not null) Trace?.Invoke($"sonuç {token}{(sure ? " (kesin)" : "")} aile {vote.Current}");
        return token;
    }

    private static (string? Token, bool Sure) Decide(Frame crop, Func<Frame, string?>? readText, bool strict, Func<int, int, int, int, string?>? readLine,
                                                     ButtonLayoutVote vote)
    {
        if (Gray.From(crop, 48) is not { } gray || (gray.IconBlob() ?? gray.IconBlob(grow: true)) is not { } blob)
        {
            Trace?.Invoke($"leke yok ({crop.Width}x{crop.Height})");
            return (null, false);
        }
        int w = blob.MaxX - blob.MinX + 1, h = blob.MaxY - blob.MinY + 1;
        var aspect = (double)w / h;
        Trace?.Invoke($"leke {blob.MinX},{blob.MinY}-{blob.MaxX},{blob.MaxY} / {gray.Width}x{gray.Height} oran {aspect:0.00} doygunluk {blob.Saturation:0.00}");
        if (strict)
        {
            // Kesit satırın iki katı yüksekliğinde: rozet satır boyunda (kesitin %27–65'i), kareye yakın, kenara değmiyor.
            var relative = (double)h / gray.Height;
            if (relative <= 0.27 || relative >= 0.65 || aspect <= 0.78 || aspect >= 1.28 ||
                blob.MinX <= 0 || blob.MinY <= 0 || blob.MaxX >= gray.Width - 1 || blob.MaxY >= gray.Height - 1) return (null, false);
        }
        var family = vote.Current;
        var forced = vote.Setting != ButtonLayout.Auto;
        // Otomatik'te iki ailenin şablonları da denenir; seçilen ailede öteki ailenin şekilleri aranmaz (Xbox'ta △ yok,
        // PlayStation'da dairede harf yok).
        var letters = !(forced && family == ButtonFamily.PlayStation);
        var symbols = family != ButtonFamily.Xbox;
        Shape? shape = null;
        var shaped = false;
        Shape? ShapeOf()
        {
            if (!shaped) { shape = gray.Symbol(blob, letters, symbols); shaped = true; }
            return shape;
        }
        // Renkli (ör. sarı) daire: düğme değil, haritadaki görev işareti.
        // Önce simgenin yerindeki sözcüğe bakılır: renkli yazılmış ad (GTA 6'da pembe "Donnie") renk denetiminde pembe □
        // sanılıyordu.
        // Yazılı simge (L2, R1, LT, sol çubuk "L"): önce satırın siyah-beyaz okumasında, simgenin yerine düşen sözcüğe bakılır.
        if (aspect >= 0.62 && readLine is not null)
        {
            var scale = (double)crop.Height / gray.Height;
            var word = readLine((int)(blob.MinX * scale), (int)(blob.MinY * scale), (int)((blob.MaxX + 1) * scale), (int)((blob.MaxY + 1) * scale));
            Trace?.Invoke($"simgenin yerindeki sözcük: '{word}'");
            if (Label(word, stick: true, family, vote.LeansXbox) is { } named) return (named, SureName(named, word));
            var letter = word?.Trim() is "x" or "×" ? 'X' : Letter(word);
            // Dolu dairedeki çarpı satır okumasında "X" okunur (GTA 6). Xbox'ta aynı daire X tuşudur.
            if (letter == 'X') return (family == ButtonFamily.Xbox ? ButtonTokens.X : "✕", false);
            // Dairedeki "A" / "B" / "Y": Xbox yüz tuşu. PlayStation'ın △'ı da "A" okunabildiği için Otomatik'te harf şekli
            // ya da Xbox rengi doğrulamalı.
            if (letter is 'A' or 'B' or 'Y' && ButtonTokens.XboxFace(letter.Value) is { } face)
            {
                if (family == ButtonFamily.Xbox) return (face, ShapeOf()?.Name == face);
                if (!forced && (ShapeOf() is { } s && s.Name == face ||
                                (blob.Saturation > 0.28 && ButtonTokens.XboxFaceByHue(blob.Hue) == face && ShapeOf()?.Name is not ("△" or "○" or "□"))))
                    return (face, true);
            }
            // "O" okunan ise □ de olabildiği için şekilden bakılır.
            // Simgenin yerinde gerçek bir sözcük var: renkli yazılmış ad ("Follow Brian"; ad başka renkte olduğu için yazı
            // tanıma araya boşluk koyuyor) simge değildir.
            if (word is not null && word.Count(char.IsLetter) >= 2) return (null, false);
            // Simgede rakam okundu ("2"): yarısı okunmuş "R2" rozeti; yüz tuşu sembolü değildir (GTA 6'da □ sanılıyordu).
            if (word is not null && word.Any(char.IsDigit)) return (null, false);
            // Yazı tanıma daire içindeki harfi ("L3", sol çubuk "L") "@" okuyor: yüz tuşu sembolü değildir, şablona sokulmaz
            // (GoW'daki beyaz "L3" dairesi "□" sanılıyordu). Xbox'ın "A" dairesi de "@" okunabilir: harf şekli bakılır.
            if (word?.Contains('@') == true && blob.Saturation <= 0.28 && !(letters && ShapeOf()?.Name == ButtonTokens.A)) return (null, false);
        }
        // Xbox'ta koyu dairedeki renkli harf (GTA V: yeşil "A") lekenin küçük bir parçasıdır, ortalama doygunluk düşük kalır.
        if (blob.Saturation > (family == ButtonFamily.Xbox ? 0.18 : 0.28))
        {
            Trace?.Invoke($"renk tonu {blob.Hue:0}");
            var round = aspect is > 0.7 and < 1.4;
            if (family == ButtonFamily.Xbox)
            {
                // Xbox'ın yüz tuşları: yeşil A, kırmızı B, mavi X, sarı Y (renkli harf koyu dairede ya da renkli dairede harf).
                if (round && ShapeOf() is { } s && IsXboxFace(s.Name)) return (s.Name, false);
                if (round && ButtonTokens.XboxFaceByHue(blob.Hue) is { } byHue) return (byHue, false);
                if (readText?.Invoke(Padded(crop)) is { } read && Letter(read) is { } c)
                    return (ButtonTokens.XboxFace(c) ?? ((char)(0x24B6 + c - 'A')).ToString(), false);
                return ("◆", false);
            }
            // PlayStation'ın yüz tuşları renklidir: △ yeşil, ○ kırmızı, ✕ mavi, □ pembe. Sarı/turuncu daire düğme değil,
            // haritadaki görev işaretidir (Nino'da her renkli simge işaret sayılıyordu; GoW'daki yeşil △ "◆" oluyordu).
            // Otomatik'te önce harf şekline bakılır: Xbox'ın yeşil A'sı ve kırmızı B'si △ ve ○ sanılmasın.
            // Harf şekli tek başına yetmez (pembe yazı ve sol çubuk simgesi "B"ye benziyor): dairedeki harf de okunmalı.
            if (!forced && round && ShapeOf() is { Sure: true } letterShape && IsXboxFace(letterShape.Name) &&
                ButtonTokens.XboxFaceByHue(blob.Hue) == letterShape.Name &&
                Letter(readText?.Invoke(Padded(crop))) is { } confirm && ButtonTokens.XboxFace(confirm) == letterShape.Name)
                return (letterShape.Name, true);
            if (round && FaceButtonByHue(blob.Hue) is { } face) return (face, ShapeOf()?.Name == face);
            if (readText?.Invoke(Padded(crop)) is { } letterRead && Letter(letterRead) is { } one)
            {
                // Sarı dairede "Y": Xbox'ın Y tuşu. PlayStation'da dairedeki A/B/X/Y harfi harita işaretidir.
                if (!forced && ButtonTokens.XboxFace(one) is { } xbox && ButtonTokens.XboxFaceByHue(blob.Hue) == xbox) return (xbox, true);
                if (ButtonTokens.XboxFace(one) is not null) return ("◆", false);
                return (((char)(0x24B6 + one - 'A')).ToString(), false);
            }
            return ("◆", false);
        }
        if (aspect < 0.62) return (null, false);
        if (gray.Dpad(blob) is { } pad) return (pad, false);
        var readLetters = new List<char>();
        // Yazı simgenin kendisinden okunur (sıkı kesit; koyu yazı beyaz kutuda olabilir, ters renkle de denenir).
        if (readText is not null)
        {
            var scale = (double)crop.Height / gray.Height;
            double padX = w * 0.15, padY = h * 0.15;
            var x0 = (int)Math.Max(0, (blob.MinX - padX) * scale);
            var y0 = (int)Math.Max(0, (blob.MinY - padY) * scale);
            var x1 = (int)Math.Min(crop.Width, (blob.MaxX + 1 + padX) * scale);
            var y1 = (int)Math.Min(crop.Height, (blob.MaxY + 1 + padY) * scale);
            var icon = x1 - x0 >= 4 && y1 - y0 >= 4 ? crop.Crop(x0, y0, x1 - x0, y1 - y0) : crop;
            string? read = null;
            string? Named(string? text)
            {
                read = text;
                Trace?.Invoke($"simge okuması: '{text?.Trim()}'");
                if (Letter(text) is { } c) readLetters.Add(c);
                return Label(text, family: family, leanXbox: vote.LeansXbox);
            }
            // Son çare: yalnız simgenin içi siyah-beyaz okunur (ince çerçeveli kapsüldeki "RT" çerçeveyle birlikte okunmuyor).
            var label = Named(readText(Padded(icon))) ?? Named(readText(Padded(Inverted(icon)))) ?? Named(readText(Padded(crop))) ??
                        Named(readText(Padded(Interior(icon))));
            if (label is not null) return (label, SureName(label, read));
        }
        // Dokunmatik yüzey: yazısız, en boyunun ~1,6 katı, içi dikey çizgili (Xbox'ta yoktur).
        if (symbols && aspect > 1.45 && gray.Stripes(blob) >= 4) return ("▦", true);
        // Yazılı kutu ama yazı okunamadı: daire/kare sembol sanılmasın.
        if (aspect > 1.15) return (null, false);
        if (ShapeOf() is not { } found || found.Score <= (strict ? 0.5 : 0.45)) return (null, false);
        return found.Name switch
        {
            // Çarpı ile X harfi aynı biçimdedir: aileye göre okunur.
            "✕" => (family == ButtonFamily.Xbox ? ButtonTokens.X : "✕", false),
            // Harf şekli tek başına yetmez (sol çubuk simgesi ve yarısı dolu "LT" dairesi "B"ye benziyor): dairedeki harf de
            // okunmuş olmalı.
            ButtonTokens.A or ButtonTokens.B or ButtonTokens.Y =>
                readLetters.Any(c => ButtonTokens.XboxFace(c) == found.Name) ? (found.Name, family != ButtonFamily.Xbox) : (null, false),
            // View ve Menu yalnız Xbox beklenirken ve satırın içinde aranır: kenarda arka plandaki çizgiler ☰ sanılıyordu.
            ButtonTokens.View or ButtonTokens.Menu => family == ButtonFamily.Xbox && !strict ? (found.Name, false) : (null, false),
            _ => (found.Name, found.Sure),
        };
    }

    private static bool IsXboxFace(string? token) => token is ButtonTokens.A or ButtonTokens.B or ButtonTokens.X or ButtonTokens.Y;

    /// <summary>Okunan yazı tek bir büyük harf mi ("A", "(B)", "[Y]"); değilse null.</summary>
    internal static char? Letter(string? read)
    {
        if (read is null) return null;
        var core = read.Trim().Trim('(', ')', '[', ']', '{', '}', '<', '>', '.', ',', ':');
        return core.Length == 1 && core[0] is >= 'A' and <= 'Z' ? core[0] : null;
    }

    /// <summary>
    /// Ad kendi ailesini kesin gösteriyor mu: okunan yazıda gerçekten "LT" / "RS" (Xbox) ya da "L2" / "R1" / OPTIONS
    /// (PlayStation) yazıyor. Benzer harflerden düzeltilen ad ("RI" → R1, "LB" → L3) kesin sayılmaz.
    /// </summary>
    private static bool SureName(string named, string? read)
    {
        var text = new string((read ?? "").ToUpperInvariant().Where(char.IsLetterOrDigit).ToArray());
        return named switch
        {
            "LT" or "RT" or "LS" or "RS" => text.Contains(named, StringComparison.Ordinal),
            "L1" or "L2" or "R1" or "R2" => text.Contains(named, StringComparison.Ordinal),
            "OPTIONS" => true,
            _ => false,
        };
    }

    /// <summary>Renk tonundan (0–360) PlayStation yüz tuşu: yeşil △, mavi ✕, pembe □, kırmızı ○; sarı/turuncu düğme değildir.</summary>
    internal static string? FaceButtonByHue(double hue) => hue switch
    {
        >= 120 and < 190 => "△",
        >= 190 and < 260 => "✕",
        >= 285 and < 335 => "□",
        >= 335 or < 18 => "○",
        _ => null,
    };

    /// <summary>
    /// Simgenin içindeki "L2", "R1", "L3", "OPTIONS" (PlayStation) ya da "LB", "RT", "LS" (Xbox) gibi yazı; düğme adı
    /// değilse null. Benzer harfler aileye göre düzeltilir: PlayStation'da "RI" → R1, "LB" → L3; Xbox'ta "RI" → RT,
    /// "L8" → LB. "LT" / "RT" / "LS" / "RS" her ailede Xbox'tır (PlayStation'da böyle tuş yok).
    /// </summary>
    /// <param name="stick">Yazı simgenin tam yerinden okundu: tek "L" / "R" sol / sağ çubuktur.</param>
    /// <param name="leanXbox">Otomatik'te karar Xbox'a yatıyor: okunan "LB" / "RB" L3 / R3'e çevrilmez.</param>
    internal static string? Label(string? read, bool stick = false, ButtonFamily family = ButtonFamily.PlayStation, bool leanXbox = false)
    {
        if (read is null) return null;
        var xbox = family == ButtonFamily.Xbox;
        var text = new string(read.ToUpperInvariant().Where(char.IsLetterOrDigit).ToArray());
        // Xbox oyunları çubuğu "LS" / "RS" yazar; tek "R" orada kesik okunmuş "RT" de olabilir (Starfield).
        if (stick && text is "L" or "R") return xbox ? null : text;
        if (text.StartsWith("OPTION", StringComparison.Ordinal)) return "OPTIONS";
        if (xbox && stick && text is "VIEW") return ButtonTokens.View;
        if (xbox && stick && text is "MENU") return ButtonTokens.Menu;
        if (text.Length == 3 && text[0] is 'C' or 'I') text = text[1..];
        // Kesite komşu harf karışabilir ("DL2T"): kısa yazının içinde L/R + rakam (Xbox'ta harf) aranır.
        if (text.Length is > 2 and <= 5 &&
            System.Text.RegularExpressions.Regex.Match(text, xbox ? "[LR][TBS8]" : "[LR][123IZ7]") is { Success: true } m) text = m.Value;
        if (text.Length != 2 || text[0] is not ('L' or 'R')) return null;
        // Xbox'ın tetik ve çubuk tuşu adları PlayStation'da yoktur.
        if (text[1] is 'T' or 'S') return text;
        if (xbox)
        {
            var second = text[1] switch { 'I' or '1' or '7' or 'L' => 'T', 'B' or '8' or '3' => 'B', '5' => 'S', var c => c };
            var named = $"{text[0]}{second}";
            if (ButtonTokens.XboxNames.Contains(named)) return named;
        }
        if (leanXbox && text[1] is 'B' or '8') return $"{text[0]}B";
        var digit = text[1] switch { 'I' or '7' => '1', 'Z' => '2', 'B' => '3', var c => c };
        var name = $"{text[0]}{digit}";
        return name is "L1" or "L2" or "L3" or "R1" or "R2" or "R3" ? name : null;
    }
    /// <summary>Simgenin aranacağı kesit: boşluğun tamamı (dar ise satır yüksekliği kadar genişletilir), satırın 2 katı yükseklik.</summary>
    internal static Frame? CropAround(Frame image, TextBox box) => Around(image, box)?.Crop;

    private static (Frame Crop, int X, int Y)? Around(Frame image, TextBox box)
    {
        double w = image.Width, h = image.Height;
        var lineHeight = (box.Bottom - box.Top) * h;
        var half = lineHeight;
        var cy = (box.Top + box.Bottom) / 2 * h;
        double left = box.Left * w, right = box.Right * w;
        var minWidth = lineHeight * 2.0;
        if (right - left < minWidth)
        {
            var cx = (left + right) / 2;
            left = cx - minWidth / 2;
            right = cx + minWidth / 2;
        }
        var x0 = (int)Math.Floor(Math.Max(0, left - 2));
        var y0 = (int)Math.Floor(Math.Max(0, cy - half));
        var x1 = (int)Math.Ceiling(Math.Min(w, right + 2));
        var y1 = (int)Math.Ceiling(Math.Min(h, cy + half));
        if (x1 - x0 < 6 || y1 - y0 < 6) return null;
        return (image.Crop(x0, y0, x1 - x0, y1 - y0), x0, y0);
    }

    /// <summary>Parlak yazı siyah, gerisi beyaz (Otsu eşiği): yazı tanıma simgedeki ince beyaz yazıyı böyle okuyor.</summary>
    internal static Frame Binarized(Frame image)
    {
        var values = new double[image.Width * image.Height];
        for (var y = 0; y < image.Height; y++)
            for (var x = 0; x < image.Width; x++)
            {
                var p = y * image.Stride + x * 4;
                values[y * image.Width + x] = (0.114 * image.Pixels[p] + 0.587 * image.Pixels[p + 1] + 0.299 * image.Pixels[p + 2]) / 255;
            }
        var t = Gray.Threshold(values);
        var result = Frame.Blank(image.Width, image.Height);
        for (var i = 0; i < values.Length; i++)
        {
            var v = values[i] > t ? (byte)0 : (byte)255;
            result.Pixels[i * 4] = result.Pixels[i * 4 + 1] = result.Pixels[i * 4 + 2] = v;
            result.Pixels[i * 4 + 3] = 255;
        }
        return result;
    }

    /// <summary>
    /// Simgenin yalnız içi: siyah-beyaza çevrilir (parlak yazı siyah), kenardaki çerçeve (daire, kapsül) silinir; içi
    /// çoğunlukla siyahsa (dolu daire) ters çevrilir.
    /// </summary>
    private static Frame Interior(Frame icon)
    {
        var bin = Binarized(icon);
        double cx = bin.Width / 2.0, cy = bin.Height / 2.0, rx = bin.Width * 0.5 * 0.7, ry = bin.Height * 0.5 * 0.7;
        int inside = 0, dark = 0;
        for (var y = 0; y < bin.Height; y++)
            for (var x = 0; x < bin.Width; x++)
            {
                double dx = (x - cx) / rx, dy = (y - cy) / ry;
                if (dx * dx + dy * dy > 1) continue;
                inside++;
                if (bin.Pixels[y * bin.Stride + x * 4] == 0) dark++;
            }
        var filled = inside > 0 && dark * 2 > inside;
        for (var y = 0; y < bin.Height; y++)
            for (var x = 0; x < bin.Width; x++)
            {
                double dx = (x - cx) / rx, dy = (y - cy) / ry;
                var p = y * bin.Stride + x * 4;
                var v = dx * dx + dy * dy > 1 ? (byte)255 : filled ? (byte)(255 - bin.Pixels[p]) : bin.Pixels[p];
                bin.Pixels[p] = bin.Pixels[p + 1] = bin.Pixels[p + 2] = v;
            }
        return bin;
    }

    /// <summary>Renkleri ters çevrilmiş kopya (beyaz kutudaki koyu yazı için).</summary>
    private static Frame Inverted(Frame image)
    {
        var result = Frame.Blank(image.Width, image.Height);
        for (var y = 0; y < image.Height; y++)
            for (var x = 0; x < image.Width; x++)
            {
                int s = y * image.Stride + x * 4, t = y * result.Stride + x * 4;
                result.Pixels[t] = (byte)(255 - image.Pixels[s]);
                result.Pixels[t + 1] = (byte)(255 - image.Pixels[s + 1]);
                result.Pixels[t + 2] = (byte)(255 - image.Pixels[s + 2]);
                result.Pixels[t + 3] = 255;
            }
        return result;
    }

    /// <summary>
    /// Yazı tanıma çok kısa ve tek başına duran yazıyı ("L1") çoğu zaman okumaz: kesit, kenar rengiyle doldurulmuş geniş
    /// bir zeminin ortasına konur ve en az 120 piksel boya büyütülür.
    /// </summary>
    internal static Frame Padded(Frame image) => PaddedMap(image).Frame;

    /// <summary><see cref="Padded"/> ve görüntünün zemindeki yeri (büyütmeden önceki piksel ve zemin boyutu).</summary>
    private static (Frame Frame, int X, int Y, double Width, double Height) PaddedMap(Frame image)
    {
        int w = image.Width, h = image.Height;
        double sum = 0;
        var n = 0;
        for (var x = 0; x < w; x++)
        {
            foreach (var y in new[] { 0, h - 1 })
            {
                var p = y * image.Stride + x * 4;
                sum += image.Pixels[p] + image.Pixels[p + 1] + image.Pixels[p + 2];
                n += 3;
            }
        }
        var edge = (byte)Math.Clamp(sum / Math.Max(n, 1), 0, 255);
        int cw = w * 4, ch = h * 2;
        var canvas = Frame.Blank(cw, ch);
        for (var i = 0; i < canvas.Pixels.Length; i += 4)
        {
            canvas.Pixels[i] = canvas.Pixels[i + 1] = canvas.Pixels[i + 2] = edge;
            canvas.Pixels[i + 3] = 255;
        }
        int ox = (cw - w) / 2, oy = (ch - h) / 2;
        for (var y = 0; y < h; y++)
            Buffer.BlockCopy(image.Pixels, y * image.Stride, canvas.Pixels, (oy + y) * canvas.Stride + ox * 4, w * 4);
        var factor = (int)Math.Ceiling(140.0 / Math.Max(ch, 1));
        return (factor > 1 ? canvas.Scaled(Math.Min(factor, 8)) : canvas, ox, oy, cw, ch);
    }

    /// <summary>Kesitin gri tonlu ve renk doygunluğu tutan küçük kopyası (satırlar üstten).</summary>
    private sealed class Gray
    {
        public int Width { get; private init; }
        public int Height { get; private init; }
        private double[] luma = [];
        private double[] sat = [];
        private double[] hue = [];

        public sealed record Blob(int MinX, int MinY, int MaxX, int MaxY, HashSet<int> Pixels, double Saturation, double Hue);

        public static Gray? From(Frame image, int target)
        {
            if (image.Width < 2 || image.Height < 2) return null;
            var scale = (double)target / image.Height;
            var width = Math.Max(4, (int)(image.Width * scale));
            var gray = new Gray { Width = width, Height = target, luma = new double[width * target], sat = new double[width * target], hue = new double[width * target] };
            for (var y = 0; y < target; y++)
            {
                // Kutu ortalaması: küçültürken ince çizgiler kaybolmasın.
                int sy0 = (int)(y / scale), sy1 = Math.Max(sy0 + 1, Math.Min(image.Height, (int)((y + 1) / scale)));
                for (var x = 0; x < width; x++)
                {
                    int sx0 = (int)(x / scale), sx1 = Math.Max(sx0 + 1, Math.Min(image.Width, (int)((x + 1) / scale)));
                    double r = 0, g = 0, b = 0;
                    var count = 0;
                    for (var yy = Math.Min(sy0, image.Height - 1); yy < sy1; yy++)
                        for (var xx = Math.Min(sx0, image.Width - 1); xx < sx1; xx++)
                        {
                            var p = yy * image.Stride + xx * 4;
                            b += image.Pixels[p];
                            g += image.Pixels[p + 1];
                            r += image.Pixels[p + 2];
                            count++;
                        }
                    r /= count * 255.0;
                    g /= count * 255.0;
                    b /= count * 255.0;
                    var i = y * width + x;
                    gray.luma[i] = 0.299 * r + 0.587 * g + 0.114 * b;
                    double mx = Math.Max(r, Math.Max(g, b)), mn = Math.Min(r, Math.Min(g, b));
                    gray.sat[i] = mx > 0.15 ? (mx - mn) / mx : 0;
                    var d = mx - mn;
                    gray.hue[i] = d < 1e-6 ? 0
                        : mx == r ? 60 * (((g - b) / d % 6 + 6) % 6)
                        : mx == g ? 60 * ((b - r) / d + 2)
                        : 60 * ((r - g) / d + 4);
                }
            }
            return gray;
        }

        public static double Threshold(IReadOnlyList<double> values) => Otsu(values);

        /// <summary>Otsu eşiği: parlak ile koyuyu en iyi ayıran değer.</summary>
        private static double Otsu(IReadOnlyList<double> values)
        {
            var hist = new double[64];
            foreach (var v in values) hist[Math.Clamp((int)(v * 63.999), 0, 63)]++;
            double total = values.Count, sumAll = 0;
            for (var i = 0; i < 64; i++) sumAll += i * hist[i];
            double sumB = 0, wB = 0, best = 0;
            var threshold = 32;
            for (var i = 0; i < 64; i++)
            {
                wB += hist[i];
                if (wB == 0) continue;
                var wF = total - wB;
                if (wF == 0) break;
                sumB += i * hist[i];
                double mB = sumB / wB, mF = (sumAll - sumB) / wF;
                var between = wB * wF * (mB - mF) * (mB - mF);
                if (between > best)
                {
                    best = between;
                    threshold = i;
                }
            }
            return (threshold + 0.5) / 64;
        }

        /// <summary>Kesitteki simge: yüksekliği kesitin üçte birinden büyük, kareye yakın, ortaya yakın en büyük parlak leke.</summary>
        /// <para>
        /// <paramref name="grow"/>: parlak alan bir piksel büyütülür. İnce çizgili çerçeve (Starfield'ın "RT" kapsülü, içi boş
        /// daire) küçültmede kopuk kalıyor, parçaları simge boyuna ulaşmıyordu.
        /// </para>
        public Blob? IconBlob(bool grow = false)
        {
            var t = Otsu(luma);
            var mask = luma.Select(v => v > t).ToArray();
            if (grow)
            {
                var grown = (bool[])mask.Clone();
                for (var y = 0; y < Height; y++)
                    for (var x = 0; x < Width; x++)
                    {
                        if (!mask[y * Width + x]) continue;
                        for (var dy = -1; dy <= 1; dy++)
                            for (var dx = -1; dx <= 1; dx++)
                            {
                                int nx = x + dx, ny = y + dy;
                                if (nx >= 0 && ny >= 0 && nx < Width && ny < Height) grown[ny * Width + nx] = true;
                            }
                    }
                mask = grown;
            }
            Blob? best = null;
            var bestScore = 0.0;
            var stack = new Stack<int>();
            for (var start = 0; start < Width * Height; start++)
            {
                if (!mask[start]) continue;
                var pixels = new List<int>();
                mask[start] = false;
                stack.Push(start);
                int minX = Width, minY = Height, maxX = 0, maxY = 0;
                while (stack.Count > 0)
                {
                    var p = stack.Pop();
                    pixels.Add(p);
                    int x = p % Width, y = p / Width;
                    minX = Math.Min(minX, x); maxX = Math.Max(maxX, x); minY = Math.Min(minY, y); maxY = Math.Max(maxY, y);
                    if (x + 1 < Width && mask[p + 1]) { mask[p + 1] = false; stack.Push(p + 1); }
                    if (x > 0 && mask[p - 1]) { mask[p - 1] = false; stack.Push(p - 1); }
                    if (y + 1 < Height && mask[p + Width]) { mask[p + Width] = false; stack.Push(p + Width); }
                    if (y > 0 && mask[p - Width]) { mask[p - Width] = false; stack.Push(p - Width); }
                }
                int bw = maxX - minX + 1, bh = maxY - minY + 1;
                if (bh < Height / 3 || bh >= Height) continue;
                var aspect = (double)bw / bh;
                if (aspect <= 0.4 || aspect >= 2.2) continue;
                var cx = (minX + maxX) / 2.0 / Width;
                var squareness = 1 - Math.Min(1, Math.Abs(Math.Log(aspect)));
                var score = bw * bh * (0.5 + squareness) * (1.2 - Math.Abs(cx - 0.5));
                if (score <= bestScore) continue;
                bestScore = score;
                var s = pixels.Sum(i => sat[i]) / Math.Max(pixels.Count, 1);
                // Renk tonu, doygun piksellerin ton açılarının ortalaması (kırmızı 0/360 sınırında bölünmesin diye vektörle).
                double hx = 0, hy = 0;
                foreach (var i in pixels)
                {
                    if (sat[i] < 0.25) continue;
                    hx += Math.Cos(hue[i] * Math.PI / 180) * sat[i];
                    hy += Math.Sin(hue[i] * Math.PI / 180) * sat[i];
                }
                var h = (Math.Atan2(hy, hx) * 180 / Math.PI + 360) % 360;
                best = new Blob(minX, minY, maxX, maxY, [.. pixels], s, h);
            }
            return best;
        }

        /// <summary>Lekenin orta satırında açık-koyu geçiş sayısının yarısı (dokunmatik yüzey ızgarası çok çizgilidir).</summary>
        public int Stripes(Blob b)
        {
            var y = (b.MinY + b.MaxY) / 2;
            var row = Enumerable.Range(b.MinX, b.MaxX - b.MinX + 1).Select(x => luma[y * Width + x]).ToList();
            var t = Otsu(row);
            var flips = 0;
            for (var i = 1; i < row.Count; i++) if ((row[i] > t) != (row[i - 1] > t)) flips++;
            return flips / 2;
        }

        /// <summary>Yön tuşu: artı biçimli leke; ötekilerden parlak kol yönü verir.</summary>
        public string? Dpad(Blob b)
        {
            int w = b.MaxX - b.MinX + 1, h = b.MaxY - b.MinY + 1;
            if (w < 6 || h < 6) return null;
            (double Fill, double Light) Cell(int cx, int cy)
            {
                int x0 = b.MinX + cx * w / 3, x1 = Math.Max(x0 + 1, b.MinX + (cx + 1) * w / 3);
                int y0 = b.MinY + cy * h / 3, y1 = Math.Max(y0 + 1, b.MinY + (cy + 1) * h / 3);
                int on = 0, n = 0;
                var light = 0.0;
                for (var y = y0; y < y1; y++)
                    for (var x = x0; x < x1; x++)
                    {
                        n++;
                        var p = y * Width + x;
                        if (b.Pixels.Contains(p)) on++;
                        light += luma[p];
                    }
                return ((double)on / Math.Max(n, 1), light / Math.Max(n, 1));
            }
            var corners = new[] { Cell(0, 0), Cell(2, 0), Cell(0, 2), Cell(2, 2) }.Select(c => c.Fill);
            var arms = new[] { ("↑", Cell(1, 0)), ("←", Cell(0, 1)), ("→", Cell(2, 1)), ("↓", Cell(1, 2)) };
            if (corners.Max() >= 0.2 || !arms.All(a => a.Item2.Fill > 0.35)) return null;
            var sorted = arms.OrderByDescending(a => a.Item2.Light).ToList();
            return sorted[0].Item2.Light > sorted[1].Item2.Light * 1.12 ? sorted[0].Item1 : "✜";
        }

        /// <summary>
        /// Simgenin içindeki sembol: iç dairede azınlıkta kalan renk (beyaz daire üstünde koyu çarpı ya da koyu zeminde
        /// beyaz çarpı) ✕ ○ △ □ (<paramref name="symbols"/>) ve Xbox'ın A B Y harfleri, View ⧉ ve Menu ☰
        /// (<paramref name="letters"/>) şablonlarıyla karşılaştırılır. Simgeler iç içe halkalı olabilir: birkaç iç
        /// yarıçapta aranır. Eşik uygulanmaz; çağıran puana bakar.
        /// </summary>
        public Shape? Symbol(Blob b, bool letters = false, bool symbols = true)
        {
            Shape? best = null;
            foreach (var factor in new[] { 0.74, 0.6, 0.48 })
                if (SymbolAt(b, factor, letters, symbols) is { } found && (best is null || found.Score > best.Score)) best = found;
            return best;
        }

        private Shape? SymbolAt(Blob b, double factor, bool letters, bool symbols)
        {
            int w = b.MaxX - b.MinX + 1, h = b.MaxY - b.MinY + 1;
            double cx = (b.MinX + b.MaxX) / 2.0, cy = (b.MinY + b.MaxY) / 2.0;
            var r = Math.Min(w, h) / 2.0 * factor;
            if (r < 4) return null;
            var inner = new List<(double X, double Y, double V)>();
            for (var y = (int)(cy - r); y <= (int)(cy + r); y++)
                for (var x = (int)(cx - r); x <= (int)(cx + r); x++)
                {
                    if (x < 0 || y < 0 || x >= Width || y >= Height) continue;
                    double dx = x - cx, dy = y - cy;
                    if (dx * dx + dy * dy > r * r) continue;
                    inner.Add((dx / r, dy / r, luma[y * Width + x]));
                }
            if (inner.Count <= 30) return null;
            var values = inner.Select(p => p.V).ToList();
            if (values.Max() - values.Min() <= 0.15) return null;
            var t = Otsu(values);
            var bright = inner.Count(p => p.V > t);
            var minorityIsBright = bright * 2 < inner.Count;
            var marks = inner.Where(p => minorityIsBright ? p.V > t : p.V <= t).ToList();
            if (marks.Count < 6) return null;
            // İşaretli noktalar sembolün kendi kutusuna oranlanır, 16x16 ızgarada şablonlarla karşılaştırılır.
            double mx0 = marks.Min(m => m.X), mx1 = marks.Max(m => m.X), my0 = marks.Min(m => m.Y), my1 = marks.Max(m => m.Y);
            const int n = Templates.N;
            var grid = new bool[n * n];
            foreach (var m in marks)
            {
                var gx = Math.Min(n - 1, (int)((m.X - mx0) / Math.Max(mx1 - mx0, 0.01) * n));
                var gy = Math.Min(n - 1, (int)((m.Y - my0) / Math.Max(my1 - my0, 0.01) * n));
                grid[gy * n + gx] = true;
            }
            // İçinde yan yana iki parça olan işaret ("R3", "L3") yazıdır, sembol değildir: şablonla karşılaştırılmaz.
            // Örnekleme ızgarada tek hücrelik boşluklar bırakır (halka kopuk görünür): parçalar bir hücre büyütülüp sayılır.
            // Alt alta üç yatay çizgi ise Xbox'ın Menu tuşudur (☰).
            var parts = ComponentBoxes(Templates.Grown(grid), n);
            // Menu çizgileri birbirine yakın: dört yöne büyütülünce birleşebildiği için yalnız yatayda büyütülmüş ızgarada da
            // bakılır.
            if (letters && parts.Count < 3 && IsMenu(ComponentBoxes(Templates.GrownSideways(grid), n), n)) return new Shape(ButtonTokens.Menu, 0.9, true);
            if (parts.Count >= 2)
            {
                if (letters && IsMenu(parts, n)) return new Shape(ButtonTokens.Menu, 0.9, true);
                Trace?.Invoke($"sembol {factor}: birden çok parça (yazı)");
                return null;
            }
            // Karenin köşeleri doludur; daire ve çarpıda köşe hücreleri boş kalır. Köşeleri yuvarlak kare (GTA 6) için
            // köşe hücresinin bir hücre içerisi de sayılır.
            var corners = 0;
            foreach (var (qx, qy) in new[] { (0, 0), (n - 3, 0), (0, n - 3), (n - 3, n - 3) })
            {
                var hit = false;
                for (var yy = qy; yy < qy + 3 && !hit; yy++)
                    for (var xx = qx; xx < qx + 3 && !hit; xx++)
                        hit = grid[yy * n + xx];
                if (hit) corners++;
            }
            // Kare ile daire, şeklin merkezden uzaklığıyla ayrılır: karede çapraz yönler kenar yönlerinden ~1,4 kat uzaktır,
            // dairede her yön eşittir. Köşeleri yuvarlatılmış kare (GTA 6) şablonda daireye benzediği için ○ sanılıyordu.
            var corneriness = Corneriness(marks.Select(m => (m.X - (mx0 + mx1) / 2, m.Y - (my0 + my1) / 2)).ToList());
            Trace?.Invoke($"köşelilik {corneriness:0.00}");
            (string Name, double Score)? best = null;
            double bestSymbol = 0, bestLetter = 0;
            foreach (var (name, template) in Templates.All)
            {
                var letter = Templates.IsLetter(name);
                // Çarpı X harfiyle aynıdır: iki ailede de aranır.
                if (letter ? !letters : !symbols && name != "✕") continue;
                if (name == "□" && corners < 4) continue;
                // Harfin içi boşluklu: dolu leke (GTA 6'nın yarısı dolu "LT" dairesi) B sanılmasın diye doluluk da uymalı.
                if (letter && Math.Abs(Templates.Fill(grid) - Templates.Fill(template)) > 0.25) continue;
                var score = Templates.Similarity(grid, template);
                Trace?.Invoke($"sembol {factor} {name} {score:0.00} köşe {corners}");
                if (letter) bestLetter = Math.Max(bestLetter, score);
                else bestSymbol = Math.Max(bestSymbol, score);
                if (best is null || score > best.Value.Score) best = (name, score);
            }
            if (best is not { } result) return null;
            if (result.Name == "○" && corneriness > 1.10) result.Name = "□";
            else if (result.Name == "□" && corneriness < 1.06) result.Name = "○";
            // Kesin karar: harf, PlayStation şekillerinden belirgin biçimde iyi uyuyor (ya da tersi). Çarpı iki ailede de var.
            const double margin = 0.06;
            var sure = result.Name != "✕" && (Templates.IsLetter(result.Name) ? result.Score - bestSymbol >= margin : result.Score - bestLetter >= margin);
            return new Shape(result.Name, result.Score, sure);
        }

        /// <summary>Alt alta, eşit aralıklı, en az ızgaranın yarısı genişliğinde üç yatay çizgi: Xbox'ın Menu tuşu (☰).</summary>
        private static bool IsMenu(IReadOnlyList<(int MinX, int MinY, int MaxX, int MaxY)> parts, int n)
        {
            // Örnekleme çizgi kenarında tek satırlık kırıntı bırakabilir: en çok bir ince parça atlanır.
            if (parts.Count == 4) parts = [.. parts.OrderByDescending(p => (p.MaxX - p.MinX + 1) * (p.MaxY - p.MinY + 1)).Take(3)];
            if (parts.Count != 3) return false;
            var sorted = parts.OrderBy(p => p.MinY).ToList();
            if (sorted.Any(p => p.MaxX - p.MinX + 1 < n * 0.6 || p.MaxY - p.MinY + 1 > n * 0.4)) return false;
            // Üç çizgi aynı boyda ve hizada (yazının satırları ya da harf parçaları değil).
            if (sorted.Max(p => p.MaxX - p.MinX) - sorted.Min(p => p.MaxX - p.MinX) > n * 0.2 ||
                sorted.Max(p => p.MinX) - sorted.Min(p => p.MinX) > n * 0.15) return false;
            double c0 = (sorted[0].MinY + sorted[0].MaxY) / 2.0, c1 = (sorted[1].MinY + sorted[1].MaxY) / 2.0, c2 = (sorted[2].MinY + sorted[2].MaxY) / 2.0;
            return Math.Abs((c1 - c0) - (c2 - c1)) <= n * 0.15;
        }
    }

    /// <summary>Şablonla eşleşen şekil: ad, benzerlik (0–1) ve öteki ailenin şekillerinden belirgin biçimde iyi mi.</summary>
    private sealed record Shape(string Name, double Score, bool Sure);

    /// <summary>
    /// Çapraz yönlerdeki en uzak işaretin merkeze uzaklığı / eksen yönlerindeki (sağ, sol, üst, alt) en uzak işaretinki.
    /// Kare ~1,4, daire ~1; çarpı ve üçgende anlamsızdır (yalnız ○ / □ kararında kullanılır).
    /// </summary>
    private static double Corneriness(IReadOnlyList<(double X, double Y)> points)
    {
        var far = new double[16];
        foreach (var (x, y) in points)
        {
            var bin = (int)((Math.Atan2(y, x) + Math.PI) / (2 * Math.PI) * 16) % 16;
            far[bin] = Math.Max(far[bin], Math.Sqrt(x * x + y * y));
        }
        // 16 dilim: 0, 4, 8, 12 eksen yönleri (sol, üst, sağ, alt); 2, 6, 10, 14 çaprazlar.
        double axis = (far[0] + far[4] + far[8] + far[12]) / 4, diagonal = (far[2] + far[6] + far[10] + far[14]) / 4;
        return axis <= 0 ? 1 : diagonal / axis;
    }

    /// <summary>Izgaradaki sekiz yönlü bağlı parçaların kutuları (tek hücrelik kırıntılar sayılmaz).</summary>
    private static List<(int MinX, int MinY, int MaxX, int MaxY)> ComponentBoxes(bool[] grid, int n)
    {
        var seen = new bool[grid.Length];
        var boxes = new List<(int, int, int, int)>();
        var stack = new Stack<int>();
        for (var start = 0; start < grid.Length; start++)
        {
            if (!grid[start] || seen[start]) continue;
            var size = 0;
            int minX = n, minY = n, maxX = 0, maxY = 0;
            seen[start] = true;
            stack.Push(start);
            while (stack.Count > 0)
            {
                var p = stack.Pop();
                size++;
                int x = p % n, y = p / n;
                minX = Math.Min(minX, x); maxX = Math.Max(maxX, x); minY = Math.Min(minY, y); maxY = Math.Max(maxY, y);
                // Sekiz yönlü: seyrek örneklenen halka ve çapraz çizgiler tek parça kalsın.
                for (var dy = -1; dy <= 1; dy++)
                    for (var dx = -1; dx <= 1; dx++)
                    {
                        int nx = x + dx, ny = y + dy;
                        if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
                        var q = ny * n + nx;
                        if (grid[q] && !seen[q]) { seen[q] = true; stack.Push(q); }
                    }
            }
            if (size > 2) boxes.Add((minX, minY, maxX, maxY));
        }
        return boxes;
    }

    /// <summary>✕ ○ △ □ ve Xbox'ın A B Y harfleri ile View ⧉ şablonları (16x16, sembolün kendi kutusuna oranlı çizgi).</summary>
    private static class Templates
    {
        public const int N = 16;

        public static readonly (string Name, bool[] Grid)[] All =
        [
            ("✕", Draw((x, y) => Math.Abs(x - y) < 0.17 || Math.Abs(x + y - 1) < 0.17)),
            ("○", Draw((x, y) => { var d = Math.Sqrt((x - 0.5) * (x - 0.5) + (y - 0.5) * (y - 0.5)); return d > 0.34 && d < 0.52; })),
            ("□", Draw((x, y) => x < 0.18 || x > 0.82 || y < 0.18 || y > 0.82)),
            // Tepe üstte; iki yan kenar ve taban.
            ("△", Draw((x, y) => y > 0.82 || Math.Abs(x - (0.5 - y / 2)) < 0.12 || Math.Abs(x - (0.5 + y / 2)) < 0.12)),
            // A: üçgenin iki kenarı gibi iki bacak, tabanı yok; ortanın altında yatay çizgi. Üçgenden taban ve çizgiyle ayrılır.
            (ButtonTokens.A, Draw((x, y) => Math.Abs(x - (0.5 - y / 2)) < 0.13 || Math.Abs(x - (0.5 + y / 2)) < 0.13 ||
                                          (Math.Abs(y - 0.63) < 0.08 && x > 0.5 - y / 2 && x < 0.5 + y / 2))),
            // B: solda dikey gövde, üstte, ortada ve altta yatay çizgi, sağda iki göbek.
            (ButtonTokens.B, Draw((x, y) => x < 0.2 || (x < 0.7 && (y < 0.14 || Math.Abs(y - 0.5) < 0.08 || y > 0.86)) ||
                                          (x >= 0.6 && (Bowl(x, y, 0.25) || Bowl(x, y, 0.75))))),
            // Y: üstten ortaya inen iki kol ve alta inen gövde.
            (ButtonTokens.Y, Draw((x, y) => (y < 0.58 && (Math.Abs(x - y * 0.9) < 0.13 || Math.Abs(x - (1 - y * 0.9)) < 0.13)) ||
                                          (y >= 0.45 && Math.Abs(x - 0.5) < 0.11))),
            // View: üst üste binen iki kare çerçeve.
            (ButtonTokens.View, Draw((x, y) => Frame(x, y, 0, 0, 0.66, 0.66) || Frame(x, y, 0.34, 0.34, 1, 1))),
        ];

        /// <summary>Izgaranın dolu hücre oranı.</summary>
        public static double Fill(bool[] g) => (double)g.Count(v => v) / g.Length;

        /// <summary>Xbox şablonu mu (harf ya da View; PlayStation şekilleriyle yarışır).</summary>
        public static bool IsLetter(string name) => name is ButtonTokens.A or ButtonTokens.B or ButtonTokens.Y or ButtonTokens.View or ButtonTokens.Menu;

        private static bool Bowl(double x, double y, double cy)
        {
            var d = Math.Sqrt(((x - 0.6) / 0.38) * ((x - 0.6) / 0.38) + ((y - cy) / 0.25) * ((y - cy) / 0.25));
            return Math.Abs(d - 0.82) < 0.2;
        }

        private static bool Frame(double x, double y, double x0, double y0, double x1, double y1)
        {
            const double s = 0.12;
            var inside = x >= x0 && x <= x1 && y >= y0 && y <= y1;
            return inside && (x - x0 < s || x1 - x < s || y - y0 < s || y1 - y < s);
        }

        private static bool[] Draw(Func<double, double, bool> f)
        {
            var g = new bool[N * N];
            for (var y = 0; y < N; y++)
                for (var x = 0; x < N; x++)
                    g[y * N + x] = f((x + 0.5) / N, (y + 0.5) / N);
            return g;
        }

        /// <summary>Yalnız sağa ve sola bir hücre büyütülmüş ızgara (yatay çizgilerdeki örnekleme boşlukları kapanır).</summary>
        public static bool[] GrownSideways(bool[] g)
        {
            var output = (bool[])g.Clone();
            for (var y = 0; y < N; y++)
                for (var x = 0; x < N; x++)
                {
                    if (!g[y * N + x]) continue;
                    if (x + 1 < N) output[y * N + x + 1] = true;
                    if (x > 0) output[y * N + x - 1] = true;
                }
            return output;
        }

        public static bool[] Grown(bool[] g)
        {
            var output = (bool[])g.Clone();
            for (var y = 0; y < N; y++)
                for (var x = 0; x < N; x++)
                {
                    if (!g[y * N + x]) continue;
                    if (x + 1 < N) output[y * N + x + 1] = true;
                    if (x > 0) output[y * N + x - 1] = true;
                    if (y + 1 < N) output[(y + 1) * N + x] = true;
                    if (y > 0) output[(y - 1) * N + x] = true;
                }
            return output;
        }

        /// <summary>İşaretlerin ne kadarı şablonda (bir hücre payla) × şablonun ne kadarı işaretlenmiş (bir hücre payla).</summary>
        public static double Similarity(bool[] a, bool[] t)
        {
            bool[] gt = Grown(t), ga = Grown(a);
            int inA = 0, aCount = 0, inT = 0, tCount = 0;
            for (var i = 0; i < N * N; i++)
            {
                if (a[i]) { aCount++; if (gt[i]) inA++; }
                if (t[i]) { tCount++; if (ga[i]) inT++; }
            }
            return (double)inA / Math.Max(aCount, 1) * inT / Math.Max(tCount, 1);
        }
    }
}
