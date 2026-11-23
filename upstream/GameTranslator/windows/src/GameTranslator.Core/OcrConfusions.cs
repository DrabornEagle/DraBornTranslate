// SPDX-License-Identifier: AGPL-3.0-only

namespace GameTranslator.Core;

/// <summary>
/// Yazı tanımanın birbirine karıştırdığı harfleri onarır. İnce yazı tiplerinde "t" ve "f" sık sık "l", "o" sıfır,
/// "m" de "rn" okunur ("Io make", "do nol disappear", "used lor attack", "noise 01 your"); her karede aynı okunduğu
/// için beklemek düzeltmez. Gerçek sözcük olmayan parçada bu harfler değiştirilerek gerçek bir sözcük aranır; tek bir
/// aday çıkarsa (ya da adaylardan yalnız biri çok sık kullanılan bir sözcükse) parça onunla değiştirilir. Gerçek
/// sözcüğe ve özel ada dokunulmaz.
/// </summary>
public static class OcrConfusions
{
    /// <summary>Her zaman aynı biçimde bozulan çok kısa sözcükler (denetime gerek yok).</summary>
    private static readonly Dictionary<string, string> Fixed = new(StringComparer.Ordinal)
    {
        ["Io"] = "to", ["lo"] = "to", ["01"] = "of", ["0f"] = "of", ["0K"] = "OK", ["0k"] = "ok",
        ["lhe"] = "the", ["Ihe"] = "the", ["lt"] = "It", ["lf"] = "If", ["ls"] = "Is",
        // Yuvarlak, ince yazı tiplerinde "m" "n", "t" "c", "e" "c" okunuyor ("ny love", "co do", "Wc won't").
        ["ny"] = "my", ["Ny"] = "My", ["co"] = "to", ["Wc"] = "We", ["wc"] = "we", ["chis"] = "this", ["Chis"] = "This",
    };

    /// <summary>Birden çok aday çıktığında tercih edilen, çok sık kullanılan sözcükler.</summary>
    private static readonly HashSet<string> Common = new(StringComparer.OrdinalIgnoreCase)
    {
        "the", "to", "of", "for", "not", "that", "this", "with", "it", "at", "if", "off", "after", "often", "from", "first",
        "just", "last", "left", "let", "lot", "get", "got", "out", "but", "what", "there", "their", "they", "them", "then",
        "than", "time", "little", "better", "until", "still", "must", "most", "find", "fast", "soft", "take", "into",
        "want", "about", "attack", "fight", "right", "night", "light", "might", "enemy", "weapon", "from", "more", "come",
    };

    private static readonly (string From, string To)[] Swaps =
    [
        ("l", "t"), ("l", "f"), ("I", "l"), ("I", "t"), ("0", "o"), ("1", "l"), ("rn", "m"), ("vv", "w"), ("cl", "d"),
        // Yuvarlak yazı tiplerinde: "m" → "nn" / "n" / "na" / "no" ("Tinne", "hinn", "nauch", "noy"), "t" → "c"
        // ("chis", "chat"), "e" → "c", "l" → "i" ("iove").
        ("nn", "m"), ("n", "m"), ("na", "m"), ("no", "m"), ("c", "t"), ("c", "e"), ("i", "l"),
        // Köşeli yazı tiplerinde "a" "o" okunuyor ("Stoy where you are", "trosh").
        ("o", "a"),
        // İnce, yüksek yazı tiplerinde "m" "Ili" / "lli" okunuyor ("Ilioney" → "money").
        ("Ili", "m"), ("lli", "m"),
    ];

    /// <param name="fromTurkishLetters">Oyun dili İngilizce: Türkçe harfli parçalar İngilizceye çevrilir (Türkçe okuyucunun bozduğu sözcükler).</param>
    public static string Repair(string line, Func<string, bool>? isWord, bool fromTurkishLetters = false)
    {
        if (isWord is null || line.Length == 0) return line;
        line = SpaceAfterPunctuation(line);
        var tokens = line.Split(' ');
        var changed = false;
        for (var index = 0; index < tokens.Length; index++)
        {
            var token = tokens[index];
            var start = 0;
            var end = token.Length;
            while (start < end && !char.IsLetterOrDigit(token[start])) start++;
            while (end > start && !char.IsLetterOrDigit(token[end - 1])) end--;
            // Cümle başında "I" "1" okunabiliyor ("1 learned a long time ago"): ardından küçük harfli gerçek sözcük gelirse.
            // Tek başına "o" İngilizcede sözcük değildir; "a" harfidir ("You're o woman", "for o whirl").
            if (token == "o" && index > 0 && index + 1 < tokens.Length && tokens[index + 1].Length > 0 && char.IsLetter(tokens[index + 1][0]))
            {
                tokens[index] = "a";
                changed = true;
                continue;
            }
            if (token == "1" && index + 1 < tokens.Length && tokens[index + 1].Length > 1 && char.IsLower(tokens[index + 1][0]) &&
                isWord(tokens[index + 1].TrimEnd('.', ',', '!', '?')) &&
                (index == 0 || tokens[index - 1].EndsWith('.') || tokens[index - 1].EndsWith('!') || tokens[index - 1].EndsWith('?')))
            {
                tokens[index] = "I";
                changed = true;
                continue;
            }
            // Windows'ta İngilizce okuma paketi yoksa Türkçe okuyucuya düşülüyor; o da İngilizce sözcüğü Türkçe harflerle
            // bozuyor ("thİnk", "hinı", "guyş", "flyinç", "downı"). Türkçe harfli parça İngilizce sözcüğe çevrilir.
            if (fromTurkishLetters && (token.AsSpan(start, end - start).IndexOfAny(TurkishLetters) >= 0 || (end < token.Length && token[end] == 'ı')))
            {
                var nextLower = index + 1 < tokens.Length && tokens[index + 1].Length > 0 && char.IsLower(tokens[index + 1][0]);
                var repaired = FromTurkishLetters(token, isWord, nextLower);
                if (repaired != token)
                {
                    tokens[index] = repaired;
                    changed = true;
                    token = repaired;
                    start = 0;
                    end = token.Length;
                    while (start < end && !char.IsLetterOrDigit(token[start])) start++;
                    while (end > start && !char.IsLetterOrDigit(token[end - 1])) end--;
                }
            }
            if (end - start < 2) continue;
            var core = token[start..end];
            // Sözcük arası boşlukları okunmamış parça ("Andthereismuchtodo") gerçek sözcüklere bölünür. Cümle ortasında
            // büyük harfle başlayan parça büyük olasılıkla addır ("Heywood"); bölünmez.
            var sentenceStart = index == 0 || tokens[index - 1].TrimEnd('"', '\'', '’', '”').EndsWith('.') ||
                                tokens[index - 1].EndsWith('!') || tokens[index - 1].EndsWith('?') || tokens[index - 1].EndsWith(':');
            if ((sentenceStart || !char.IsUpper(core[0])) && GluedAroundHyphens(core, isWord) is { } split)
            {
                tokens[index] = token[..start] + split + token[end..];
                changed = true;
                continue;
            }
            // Ünlem "l" okunabiliyor ("Ohl shit.", "Brotherl Brother!"): sözcüğün sonundaki "l" atılınca gerçek sözcük
            // kalıyorsa ve ardından yeni cümle başlıyorsa (satır sonu ya da büyük harf) "!" olur.
            var nextStarts = index == tokens.Length - 1 || (tokens[index + 1].Length > 0 && char.IsUpper(tokens[index + 1][0]));
            if (end == token.Length && core.Length >= 3 && core[^1] == 'l' && nextStarts && !isWord(core) && isWord(core[..^1]))
            {
                tokens[index] = token[..start] + core[..^1] + "!";
                changed = true;
                continue;
            }
            var fixedWord = Fixed.GetValueOrDefault(core) ?? Candidate(core, isWord, sentenceStart);
            if (fixedWord is null || fixedWord == core) continue;
            // Cümle başındaki sözcük büyük harfle başlar ("Io make" satır başındaysa "To make").
            if (index == 0 && char.IsUpper(core[0]) && char.IsLower(fixedWord[0])) fixedWord = char.ToUpperInvariant(fixedWord[0]) + fixedWord[1..];
            tokens[index] = token[..start] + fixedWord + token[end..];
            changed = true;
        }
        return changed ? string.Join(' ', tokens) : line;
    }

    /// <summary>İngilizcede bulunmayan, Türkçe okuyucunun ürettiği harfler.</summary>
    private static readonly System.Buffers.SearchValues<char> TurkishLetters = System.Buffers.SearchValues.Create("İıŞşĞğÇçÖöÜü");

    /// <summary>Türkçe harfin İngilizce karşılıkları (Türkçe okuyucunun karıştırdıkları; ilk seçenek en olası).</summary>
    private static readonly Dictionary<char, string[]> TurkishOptions = new()
    {
        ['İ'] = ["I"], ['ı'] = ["i", "l"], ['Ş'] = ["S"], ['ş'] = ["s"], ['Ğ'] = ["G"], ['ğ'] = ["g"],
        ['Ç'] = ["C", "G"], ['ç'] = ["c", "g"], ['Ö'] = ["O"], ['ö'] = ["o"], ['Ü'] = ["U"], ['ü'] = ["u"],
    };

    /// <summary>
    /// Türkçe harfli parça (noktalamasıyla birlikte) İngilizceye: önce harf seçenekleri ve "nı"/"rı" → "m" ile gerçek
    /// sözcük aranır ("hinı" → "him", "flyinç" → "flying", "thİnk" → "think"); sondaki "ı" sözcükten sonraysa okunmamış
    /// virgüldür ("downı" → "down,"). Gerçek sözcük çıkmazsa harfler ilk seçenekle düzleştirilir ("Vercettı" → "Vercetti").
    /// </summary>
    private static string FromTurkishLetters(string token, Func<string, bool> isWord, bool nextLower)
    {
        var start = 0;
        var end = token.Length;
        while (start < end && !char.IsLetterOrDigit(token[start])) start++;
        while (end > start && !char.IsLetterOrDigit(token[end - 1]) && token[end - 1] != 'ı' && token[end - 1] != 'İ') end--;
        var core = token[start..end];
        var tail = token[end..];
        bool Word(string w) => w.Length > 0 && (isWord(w) || isWord(w.ToLowerInvariant()));

        // Sondaki "nı" / "rı" çoğunlukla "m"dir ("thenı" → "them", "hinı" → "him", "Sanı" → "Sam"); "m"li hâli sözcük
        // değilken "n" ile biten sözcük çıkıyorsa okunmamış virgüldür ("lionı" → "lion,").
        if (core.Length >= 3 && core[^1] == 'ı' && core[^2] is 'n' or 'r' && core.AsSpan(0, core.Length - 2).IndexOfAny(TurkishLetters) < 0)
        {
            var withM = core[..^2] + "m";
            return token[..start] + (!Word(withM) && Word(core[..^1]) ? core[..^1] + "," : withM) + tail;
        }
        // Sondaki "ı": "downı helps" → "down, helps"; "brothersı Okay" → "brothers, Okay"
        if (core.Length >= 3 && core[^1] == 'ı' && core.AsSpan(0, core.Length - 1).IndexOfAny(TurkishLetters) < 0 && Word(core[..^1]) && !Word(Plain(core)))
            return token[..start] + core[..^1] + "," + tail;

        var candidates = new List<string>();
        void Expand(string done, int at)
        {
            if (candidates.Count >= 64) return;
            if (at >= core.Length) { candidates.Add(done); return; }
            var c = core[at];
            // "nı" / "rı" → "m" (Türkçe okuyucu "m"yi böyle bölüyor: "thenı", "Sanı", "hinı")
            if (c is 'n' or 'r' && at + 1 < core.Length && core[at + 1] == 'ı') Expand(done + "m", at + 2);
            // Sözcük ortasındaki "İ" küçük "i"dir ("thİnk" → "think"); başta büyük "I" ("İt" → "It")
            if (c == 'İ' && at > 0) { Expand(done + "i", at + 1); Expand(done + "I", at + 1); }
            else if (TurkishOptions.TryGetValue(c, out var options))
                foreach (var option in options) Expand(done + option, at + 1);
            else Expand(done + c, at + 1);
        }
        Expand("", 0);
        var found = candidates.FirstOrDefault(Word);
        return token[..start] + (found ?? Plain(core)) + tail;
    }

    private static string Plain(string text)
    {
        var builder = new System.Text.StringBuilder(text.Length);
        for (var i = 0; i < text.Length; i++)
            builder.Append(text[i] == 'İ' && i > 0 ? "i" : TurkishOptions.TryGetValue(text[i], out var options) ? options[0] : text[i].ToString());
        return builder.ToString();
    }

    /// <summary>
    /// Noktalamadan sonra boşluğu okunmamış iki sözcük ("words,brother", "No.It") ayrılır. Nokta yalnız ardından büyük
    /// harf geliyor ve önünde en az iki harf varsa (kısaltma: "U.S.") ayırır.
    /// </summary>
    private static string SpaceAfterPunctuation(string line)
    {
        var builder = new System.Text.StringBuilder(line.Length + 4);
        for (var i = 0; i < line.Length; i++)
        {
            builder.Append(line[i]);
            if (i == 0 || i + 1 >= line.Length || !char.IsLetter(line[i - 1]) || !char.IsLetter(line[i + 1])) continue;
            if (line[i] is ',' or ';' or '!' or '?') builder.Append(' ');
            // "Shadowheart:Try that" (konuşmacı adından sonra boşluk okunmamış).
            else if (line[i] == ':' && char.IsUpper(line[i + 1])) builder.Append(' ');
            else if (line[i] == '.' && char.IsUpper(line[i + 1]) && i >= 2 && char.IsLetter(line[i - 2])) builder.Append(' ');
        }
        return builder.Length == line.Length ? line : builder.ToString();
    }

    /// <summary>
    /// Boşlukları okunmamış birkaç sözcük: parça gerçek sözcük değilse ve tamamı en az iki gerçek sözcüğe bölünebiliyorsa
    /// en az parçalı bölünüşü. Tek harfli parça yalnız "a" ve "I" olabilir; sözcükler ortalama üç harften kısaysa
    /// (rastgele harflerden kısa sözcük uydurmak kolaydır) bölünmez.
    /// </summary>
    private static readonly HashSet<string> ShortWords = new(StringComparer.OrdinalIgnoreCase)
    {
        "a", "I", "of", "to", "in", "on", "at", "is", "it", "we", "he", "me", "my", "be", "do", "go", "no", "so", "up", "us",
        "an", "as", "by", "if", "or", "oh", "ok", "am", "hi",
    };

    /// <summary>Tirelerle ayrılmış parçaların ("pod-theydidsomethingtoitwhen") her biri ayrı bölünür; hiçbiri bölünmezse null.</summary>
    private static string? GluedAroundHyphens(string core, Func<string, bool> isWord)
    {
        if (!core.Contains('-')) return Glued(core, isWord);
        var parts = core.Split('-');
        var any = false;
        for (var i = 0; i < parts.Length; i++)
        {
            if (Glued(parts[i], isWord) is not { } split) continue;
            parts[i] = split;
            any = true;
        }
        return any ? string.Join(" - ", parts.Where(part => part.Length > 0)) : null;
    }

    internal static string? Glued(string core, Func<string, bool> isWord)
    {
        if (core.Length is < 7 or > 60 || !core.All(c => char.IsLetter(c) || c is '\'' or '’') || isWord(core)) return null;
        // "Shoulda", "kinda", "sorta": konuşma dili, yapışık sözcük değil.
        if (core.EndsWith('a') && isWord(core[..^1])) return null;
        // Sonu düşmüş "-ing" ("bellowin'", "lookin'") konuşma dilidir, yapışık sözcük değil.
        if (core.EndsWith("in", StringComparison.Ordinal) && isWord(core + "g")) return null;
        var n = core.Length;
        var best = new int[n + 1];
        var from = new int[n + 1];
        Array.Fill(best, int.MaxValue);
        best[0] = 0;
        for (var end = 1; end <= n; end++)
        {
            for (var start = Math.Max(0, end - 14); start < end; start++)
            {
                if (best[start] == int.MaxValue || best[start] + 1 >= best[end]) continue;
                var piece = core[start..end];
                // Yazım denetimi "fi", "oy", "et" gibi parçaları da sözcük sayıyor; bir iki harfli parça yalnız çok
                // yaygın sözcüklerden olabilir (yoksa "Jackiefiet" "Jackie fi et" olur).
                if (piece.Length <= 2 ? !ShortWords.Contains(piece) : !isWord(piece)) continue;
                if (piece[0] is '\'' or '’') continue;
                best[end] = best[start] + 1;
                from[end] = start;
            }
        }
        if (best[n] is int.MaxValue or < 2 || n < best[n] * 3) return null;
        // Büyük harfle başlayan iki parçalı ad ("Rayfield", "Heywood", "Blackwood") bölünmez.
        if (best[n] == 2 && char.IsUpper(core[0])) return null;
        var pieces = new List<string>();
        for (var end = n; end > 0; end = from[end]) pieces.Add(core[from[end]..end]);
        pieces.Reverse();
        return string.Join(' ', pieces);
    }

    /// <summary>Parça gerçek sözcük değilse, karışan harfler değiştirilerek bulunan tek (ya da tek yaygın) gerçek sözcük.</summary>
    private static string? Candidate(string core, Func<string, bool> isWord, bool sentenceStart = false)
    {
        if (core.Length is < 3 or > 14 || !core.All(c => char.IsLetterOrDigit(c) || c is '\'' or '’')) return null;
        if (!core.Any(char.IsLetter) || isWord(core)) return null;
        // Özel ad gibi duran parça (baş harfi büyük, gerisi küçük, rakamsız) yalnız içinde karışan harf dizisi varsa denenir.
        var proper = char.IsUpper(core[0]) && core.Skip(1).All(char.IsLower);
        // Cümle başında her sözcük büyük harfle başlar; orada denenir ("Stoy where you are" → "Stay").
        if (proper && !sentenceStart && !core.Contains("rn", StringComparison.Ordinal) && !core.Contains("nn", StringComparison.Ordinal) && core[0] != 'I') return null;

        var found = new HashSet<string>(StringComparer.Ordinal);
        var tries = 0;
        void Try(string text, int depth)
        {
            foreach (var (from, to) in Swaps)
            {
                for (var at = text.IndexOf(from, StringComparison.Ordinal); at >= 0; at = text.IndexOf(from, at + 1, StringComparison.Ordinal))
                {
                    var next = text[..at] + to + text[(at + from.Length)..];
                    if (isWord(next)) found.Add(next);
                    if (depth < 2 && found.Count < 12 && ++tries < 400) Try(next, depth + 1);
                }
            }
        }
        Try(core, 1);
        if (found.Count == 0) return null;
        if (found.Count == 1) return found.First();
        var common = found.Where(Common.Contains).ToList();
        return common.Count == 1 ? common[0] : null;
    }
}
