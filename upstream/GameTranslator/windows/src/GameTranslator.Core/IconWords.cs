// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.RegularExpressions;

namespace GameTranslator.Core;

/// <summary>Yazı tanımanın verdiği bir sözcük ve karedeki yeri (0–1, üstten).</summary>
public sealed record OcrWord(string Text, TextBox Box);

/// <summary>
/// Oyunlar düğmeyi yazının ortasına küçük bir resim olarak koyar ("Hold ⓧ to lock on"). Yazı tanıma o resmi ya hiç
/// okumaz (sözcükler arasında simge genişliğinde boşluk kalır) ya da "•", "(x)", "L", "O" gibi tek bir karakter sanır.
/// İkisinde de o parça görüntüden tanınıp (<see cref="ButtonIcons"/>) doğru düğmeyle değiştirilir ("Hold □ to lock on").
/// Nino Game Translator'ın (AGPL-3.0) VisionReader.withIcons / withEdgeIcons kurallarının uyarlamasıdır.
/// </summary>
public static partial class IconWords
{
    private static readonly HashSet<string> Verbs = new(StringComparer.OrdinalIgnoreCase)
    {
        "press", "hold", "tap", "push", "use", "release", "mash",
    };

    /// <summary>
    /// Satırın sözcüklerinden simgeli metni kurar. <paramref name="classify"/> karedeki bir yerdeki simgeyi tanır
    /// (tanınamazsa null). <paramref name="width"/> / <paramref name="height"/>: karenin piksel boyutu (boşluk satır
    /// yüksekliğiyle piksel cinsinden karşılaştırılır). Değişiklik yoksa <paramref name="text"/> aynen döner.
    /// </summary>
    public static string WithIcons(string text, IReadOnlyList<OcrWord> words, double width, double height, Func<TextBox, string?> classify)
    {
        if (words.Count == 0 || (words.Count == 1 && !MayBeIcon(words[0].Text))) return text;
        var top = words.Min(word => word.Box.Top);
        var bottom = words.Max(word => word.Box.Bottom);
        var lineHeight = (bottom - top) * height;
        // Büyük harfli eylem satırı ("SET DESTINATION e"): son sözcük dışında her şey büyük harf; sondaki tek harf (küçük
        // de olsa) simgenin yanlış okunmuş hâlidir (GTA 6'nın tuş çubuğunda ✕ "e" okunuyor).
        // Tek harfli parçalar (yanlış okunmuş simgeler: "OFFER RIDE e LUCIA") büyük harf denetimine katılmaz.
        var head = string.Join(' ', words.Take(Math.Max(1, words.Count - 1)).Select(word => word.Text).Where(word => word.Length > 1));
        var shouting = head.Any(char.IsLetter) && head == head.ToUpperInvariant();
        var output = new List<string>();
        var changed = false;
        for (var i = 0; i < words.Count; i++)
        {
            var item = words[i];
            if (i > 0)
            {
                var left = words[i - 1].Box;
                var gap = (item.Box.Left - left.Right) * width;
                if (gap > lineHeight * 0.9 && ButtonContext(output.LastOrDefault(), item.Text, shouting) &&
                    classify(new TextBox(left.Right, top, item.Box.Left, bottom)) is { } found)
                {
                    // Boşlukta simge bulunamazsa hiçbir şey eklenmez.
                    output.Add(found);
                    changed = true;
                }
            }
            // Büyük harfli eylem satırının sonundaki tek harf ("MOUNT HORSE A") simgedir (△ "A" okunuyor).
            var trailingLetter = i == words.Count - 1 && i > 0 && shouting &&
                                 (item.Text.Length == 1 || item.Text is "11" or "12" or "13" or "21" or "22" or "23");
            // "Hold A to …": fiilden sonraki tek büyük harf de simge olabilir.
            var afterVerb = i > 0 && item.Text.Length == 1 && item.Text == item.Text.ToUpperInvariant() && Verbs.Contains(words[i - 1].Text);
            // Büyük harfli satırın içindeki tek küçük harf de simgedir ("OFFER RIDE △ e LUCIA": ✕ "e" okunuyor).
            var others = string.Join(' ', words.Where((_, k) => k != i).Select(word => word.Text));
            var lowerInCaps = item.Text.Length == 1 && char.IsLower(item.Text[0]) && others.Any(char.IsLetter) && others == others.ToUpperInvariant();
            var suspicious = MayBeIcon(item.Text) || trailingLetter || afterVerb || lowerInCaps;
            var context = suspicious && ButtonContext(output.LastOrDefault(), i + 1 < words.Count ? words[i + 1].Text : null, shouting);
            if (context && classify(new TextBox(item.Box.Left, top, item.Box.Right, bottom)) is { } icon)
            {
                output.Add(icon);
                changed = true;
            }
            else if (suspicious && item.Text is not ("+" or "/" or "&") &&
                     (!item.Text.Any(char.IsLetterOrDigit) ||
                      // Cümlenin ortasında tek başına duran büyük harf ("Talk to O Brian"): tuşun yeri değilse bozuk okumadır.
                      (!context && !shouting && item.Text.Length == 1 && char.IsUpper(item.Text[0]) && item.Text is not ("A" or "I"))))
            {
                // Tanınmayan resim ("*", "•", "+"): düğme değildir, atılır.
                changed = true;
            }
            else
            {
                output.Add(item.Text);
            }
        }
        return changed ? NormalizedIcons(string.Join(' ', output)) : text;
    }

    /// <summary>
    /// Tuş bu yerde olabilir mi: fiilden sonra ("Press □", "Hold L2"), "or / and / +" ile ("□ or ○"), "to"dan önce
    /// ("□ to aim") ya da büyük harfli tuş çubuğunda ("SELECT ✕"). Sıradan cümlede adın ardındaki simge haritadaki
    /// işarettir ("your personal vehicle ◆ for easy access"; GTA 6'nın ipucu kutusu) ve satıra eklenmez.
    /// </summary>
    private static bool ButtonContext(string? previous, string? next, bool shouting)
    {
        if (shouting) return true;
        static string Word(string? text) => (text ?? "").Trim('.', ',', '!', '?', ':', ';', '(', ')').ToLowerInvariant();
        var before = Word(previous);
        var after = Word(next);
        // Yanındaki simge yalnız gerçek simgeyse sayılır: yazı tanımanın okuduğu "O" / "X" sayılsaydı bozuk parçalar
        // birbirini onaylıyordu ("Talk to □ ○ Brian": renkli adın önündeki iki bozuk parça).
        static bool Glyph(string? token) => token is not null && ButtonPrompts.IsIcon(token) && token.Trim('(', ')', '[', ']') is not ("O" or "X");
        return previous is null || Verbs.Contains(before) || before is "or" or "and" or "+" or "/" || Glyph(previous) ||
               // "or / and" sonrası tek başına yetmez: "your stash ◆ or by depositing" harita işaretidir.
               // Sıradan cümlenin sonu tek başına yetmez ("Follow ◆": renkli adın bozuk okuması); tuş çubuğunu "shouting" kapsar.
               after is "to" or "+" or "/" || Glyph(next);
    }

    /// <summary>
    /// Satırın hemen solunda ve sağında simge genişliğinde bir alan görüntüden tanınır; simge varsa satıra eklenir
    /// (görev ve ipucu satırlarında düğme çoğu zaman satırın başındadır: "□ Pick up").
    /// </summary>
    public static string WithEdgeIcons(string text, TextBox line, double width, double height, Func<TextBox, string?> classifyStrict)
    {
        var lineHeight = (line.Bottom - line.Top) * height;
        if (lineHeight <= 4 || text.Length == 0) return text;
        var span = lineHeight * 1.8 / width;
        var tokens = text.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        var result = text;
        if (tokens.Length > 0 && !ButtonPrompts.IsIcon(tokens[0]) && line.Left - span > 0 &&
            classifyStrict(new TextBox(line.Left - span, line.Top, line.Left - span * 0.1, line.Bottom)) is { } before)
            result = before + " " + result;
        // Sağ kenarda tuş yalnız büyük harfli tuş çubuğunda ("SELECT ✕") ya da fiilden sonra ("Press ✕") aranır: sıradan
        // cümlenin ardındaki renkli ad (GTA 6: "Find" + pembe "Donnie", ad okunmamış) pembe □ sanılıyordu.
        var letters = string.Concat(tokens.Where(token => token.Length > 1));
        var bar = letters.Any(char.IsLetter) && letters == letters.ToUpperInvariant();
        // Büyük rozet (GTA 6 Xbox'ta büyük harfli adın yanındaki "LT" dairesi satırın ~1,8 katı, Starfield'ın "TALK  Ⓐ"
        // dairesi ~2,5 katı ve biraz açıkta) dar alana sığmıyor: bulunamazsa üç kat yüksek, daha geniş alana bakılır.
        var grow = line.Bottom - line.Top;
        if (tokens.Length > 0 && !ButtonPrompts.IsIcon(tokens[^1]) && line.Right + span < 1 &&
            (bar || Verbs.Contains(tokens[^1].Trim('.', ',', ':'))) &&
            (classifyStrict(new TextBox(line.Right + span * 0.1, line.Top, line.Right + span, line.Bottom)) ??
             (line.Right + span * 1.6 < 1 ? classifyStrict(new TextBox(line.Right + span * 0.1, line.Top - grow, line.Right + span * 1.6, line.Bottom + grow)) : null) ??
             (line.Right + span * 2.2 < 1 ? classifyStrict(new TextBox(line.Right + span * 0.5, line.Top - grow, line.Right + span * 2.2, line.Bottom + grow)) : null)) is { } after)
            result = result + " " + after;
        return result == text ? text : NormalizedIcons(result);
    }

    /// <summary>
    /// Simgenin yanlış okunmuş hali olabilecek parça: harfsiz kısa parça ("•", "(x)", "&amp;") ya da tek büyük harf
    /// ("I" ve "A" gerçek sözcük olabildiği için hariç).
    /// </summary>
    public static bool MayBeIcon(string word)
    {
        // Yazıdaki bağlaç ve noktalama işaretleri simge değildir ("Chuck's Tackle & Shot": "&" ✕ sanılıyordu).
        if (word is "&" or "+" or "/" or "-" or "–" or "—" or "|") return false;
        var core = word.Trim('(', ')', '[', ']', '{', '}', '.', ',');
        // L1/R2 simgesinin yanlış okunmuş hali: "LD", "(C1", "Ll", "RZ".
        if (MisreadShoulder().IsMatch(word)) return true;
        if (word.Length <= 3 && word.StartsWith('(') && word.EndsWith(')')) return true;
        if (core.Length == 0) return word.Length <= 3;
        if (core.Length <= 2 && !core.Any(char.IsLetterOrDigit)) return true;
        if (core.Length == 1 && char.IsUpper(core[0]) && core[0] is not ('I' or 'A')) return true;
        return ButtonPrompts.IsIcon(core) && core.Length <= 2 && core is not ("L1" or "L2" or "L3" or "R1" or "R2" or "R3") &&
               !ButtonTokens.XboxNames.Contains(core);
    }

    /// <summary>
    /// Düğme adlarının yanlış okunmuş hallerini düzeltir ("(l2)" → "L2", "(C1" → "L1") ve yan yana yinelenen simgeyi
    /// teke indirir ("Hold ✕ ✕ to …": simge hem yazı içinden hem kenardan bulunabiliyor).
    /// </summary>
    public static string NormalizedIcons(string line)
    {
        var s = line;
        s = Regex.Replace(s, @"(?<![\w])[\(\[]?[1lI|]([123])[\)\]](?=\s|$)", "L$1");
        s = Regex.Replace(s, @"(?<![\w])[\(\[][1lI|]([123])(?=\s|$)", "L$1");
        s = Regex.Replace(s, @"(?<![\w])[\(\[]?([LR])([123])[\)\]](?=\s|$)", "$1$2");
        s = Regex.Replace(s, @"(?<![\w])[\(\[]([LR])([123])(?=\s|$)", "$1$2");
        s = Regex.Replace(s, @"(?<![\w])[\(\[]C([12])[\)\]]?(?=\s|$)", "L$1");
        // Xbox'ın omuz ve tetik tuşları köşeli parantezle okunabilir ("[LT]", "(RB)").
        s = Regex.Replace(s, @"(?<![\w])[\(\[]([LR][BTS])[\)\]](?=\s|$|[.,:])", "$1");
        s = Regex.Replace(s, @"([✕○△□↑↓←→✜▦◆●Ⓐ-Ⓩ⧉☰])(?:\s+\1)+", "$1");
        s = Regex.Replace(s, @"(?<![\w])([LR][123]|[LR][BTS]|OPTIONS|TOUCHPAD)(?:\s+\1)+(?![\w])", "$1");
        return s;
    }

    /// <summary>
    /// Xbox oyununda yazı tanımanın harf olarak okuduğu yüz tuşları işarete çevrilir: "Press A to jump" → "Press Ⓐ to
    /// jump", "(B) Back" → "Ⓑ Back", "Hold [X]" → "Hold Ⓧ". Yalnız tuşun yerinde: fiilden sonra ve ardından "to / or /
    /// and" ya da satır sonu geliyorsa, parantezli harf tuş bağlamındaysa ya da satır harf ve bilinen bir komuttan
    /// ibaretse ("A Talk", "TALK A"). Cümledeki "A" tanımlığı ("Use A Medkit", "A new day") değişmez. PlayStation'da
    /// metne dokunulmaz.
    /// </summary>
    public static string WithXboxLetters(string line, ButtonFamily family)
    {
        if (family != ButtonFamily.Xbox || line.Length == 0) return line;
        var tokens = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        var letters = string.Concat(tokens.Where(token => token.Length > 1));
        var shouting = letters.Any(char.IsLetter) && letters == letters.ToUpperInvariant();
        var changed = false;
        for (var i = 0; i < tokens.Length; i++)
        {
            var token = tokens[i];
            var match = XboxLetter().Match(token);
            if (!match.Success) continue;
            var face = ButtonTokens.XboxFace(match.Groups["letter"].Value[0])!;
            var bracketed = match.Groups["open"].Success;
            string? previous = i > 0 ? tokens[i - 1] : null, next = i + 1 < tokens.Length ? tokens[i + 1] : null;
            static string Word(string? text) => (text ?? "").Trim('.', ',', '!', '?', ':', ';').ToLowerInvariant();
            var afterVerb = Verbs.Contains(Word(previous)) &&
                            (next is null || Word(next) is "to" or "or" or "and" or "+" or "/" || ButtonPrompts.IsIcon(next) || shouting);
            // "A Talk", "B Back", "TALK A": harf ve bir komut (UiPrompts sözlüğü).
            var rest = string.Join(' ', tokens.Where((_, k) => k != i)).Trim('.', ',', ':');
            var command = (i == 0 || i == tokens.Length - 1) && tokens.Length is >= 2 and <= 4 && UiPrompts.IsCommand(rest);
            var chained = previous is not null && Word(previous) is "or" or "and" or "+" or "/" && i >= 2 && IsXboxGlyph(tokens[i - 2]);
            if (bracketed ? ButtonContext(previous, next, shouting) || command : afterVerb || command || chained)
            {
                tokens[i] = face + match.Groups["tail"].Value;
                changed = true;
            }
        }
        // Parantezli omuz tuşu ("(LT)") her durumda düzeltilir.
        return NormalizedIcons(changed ? string.Join(' ', tokens) : line);
    }

    /// <summary>
    /// Yazı tanımanın düz okuduğu tuş adları da kararı besler: ipucu satırındaki "LT", "RB" Xbox'ı, "R2", "L1", "OPTIONS"
    /// PlayStation'ı gösterir (Otomatik'te ilk simgeler mavi dairedeki X gibi belirsiz olabilir). Yalnız tuş çubuğu ya
    /// da "Hold LT to aim" gibi ipucu satırına bakılır; sıradan cümle oy vermez.
    /// </summary>
    public static void Observe(string line, ButtonLayoutVote vote)
    {
        if (vote.Setting != ButtonLayout.Auto || !ButtonPrompts.IsPrompt(line)) return;
        foreach (var raw in line.Split(' ', StringSplitOptions.RemoveEmptyEntries))
        {
            var token = raw.Trim('(', ')', '[', ']', '.', ',', ':');
            if (token is "LT" or "RT" or "LB" or "RB" or "LS" or "RS") vote.Add(ButtonFamily.Xbox);
            else if (token is "L1" or "L2" or "R1" or "R2" or "OPTIONS" or "TOUCHPAD") vote.Add(ButtonFamily.PlayStation);
        }
    }

    private static bool IsXboxGlyph(string token) => ButtonTokens.FamilyOf(token.TrimEnd('.', ',', ':')) == ButtonFamily.Xbox;

    [GeneratedRegex(@"^(?:(?<open>[\(\[\{<])(?<letter>[ABXY])[\)\]\}>]|(?<letter>[ABXY]))(?<tail>[.,:]?)$")]
    private static partial Regex XboxLetter();

    [GeneratedRegex(@"^[\(\[]?[LRC][0-9DIlZ|][\)\]]?$")]
    private static partial Regex MisreadShoulder();
}
