// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.RegularExpressions;

namespace GameTranslator.Core;

/// <summary>
/// Oyunların tuş çubuğundaki ve etkileşim istemlerindeki kısa komutların ("OPEN ✕", "PUT DOWN ○", "SET DESTINATION ✕")
/// Türkçesi. Çeviri motoru bu tek sözcükleri bağlamsız görünce yanlış anlıyordu ("OPEN" → "AÇIK", "DRINK" → "İÇECEK")
/// ya da hiç çevirmiyordu ("FLIP", "Set destination"); sözlükteki komut motora gitmez, tuş simgeleri yerinde kalır.
/// Ayrıca motorun düşürdüğü tuş simgeleri çeviriye geri konur (<see cref="KeepIcons"/>).
/// </summary>
public static partial class UiPrompts
{
    private static readonly Dictionary<string, string> Commands = new(StringComparer.OrdinalIgnoreCase)
    {
        ["ADD MARKER"] = "İşaret ekle", ["SET DESTINATION"] = "Hedef belirle", ["REMOVE DESTINATION"] = "Hedefi kaldır",
        ["RECENTER"] = "Ortala", ["2D VIEW"] = "2B görünüm", ["3D VIEW"] = "3B görünüm", ["ZOOM"] = "Yakınlaştır",
        ["EXIT"] = "Çıkış", ["BACK"] = "Geri", ["SELECT"] = "Seç", ["CLOSE"] = "Kapat", ["CLOSE MENU"] = "Menüyü kapat",
        ["MENU"] = "Menü", ["OPEN"] = "Aç", ["NEXT PAGE"] = "Sonraki sayfa", ["PREVIOUS PAGE"] = "Önceki sayfa",
        ["SHOW TEXT"] = "Metni göster", ["SHOW"] = "Göster", ["FLIP"] = "Çevir", ["PUT DOWN"] = "Bırak", ["PICK UP"] = "Al",
        ["DRINK"] = "İç", ["EAT"] = "Ye", ["WEAR"] = "Giy", ["TAKE OFF"] = "Çıkar", ["BUY"] = "Satın al", ["SELL"] = "Sat",
        ["PAY"] = "Öde", ["PAY LATER"] = "Sonra öde", ["PAY NOW"] = "Şimdi öde", ["ACCEPT"] = "Kabul et",
        ["DECLINE"] = "Reddet", ["YES"] = "Evet", ["NO"] = "Hayır", ["CONFIRM"] = "Onayla", ["CANCEL"] = "İptal",
        ["GOODBYE"] = "Hoşça kal", ["GREET"] = "Selamla", ["TALK"] = "Konuş", ["DEFUSE"] = "Yatıştır",
        ["PROVOKE"] = "Kışkırt", ["WARN"] = "Uyar", ["OFFER RIDE"] = "Arabaya çağır", ["ENTER"] = "Gir",
        ["LEAVE"] = "Ayrıl", ["SKIP"] = "Geç", ["CONTINUE"] = "Devam et", ["RETRY"] = "Tekrar dene",
        ["RESTORE ALL"] = "Hepsini geri yükle", ["RESTORE DEFAULT"] = "Varsayılana döndür", ["PREVIEW"] = "Önizle",
        ["PAUSE PREVIEW"] = "Önizlemeyi durdur", ["ADJUST"] = "Ayarla", ["SCROLL"] = "Kaydır", ["REFUEL"] = "Yakıt al",
        ["LIFT"] = "Kaldır", ["ORDER"] = "Sipariş ver", ["MAYBE LATER"] = "Belki sonra", ["PHOTO STUDIO"] = "Fotoğraf stüdyosu",
        ["REMINDERS"] = "Hatırlatıcılar", ["ROTATE"] = "Döndür", ["INTERACT"] = "Etkileşim", ["USE"] = "Kullan",
        ["KNOCK"] = "Kapıyı çal", ["CALL"] = "Ara", ["HANG UP"] = "Telefonu kapat", ["ANSWER"] = "Cevapla",
        ["IGNORE"] = "Yoksay", ["REPLY"] = "Yanıtla", ["DELETE"] = "Sil", ["SAVE"] = "Kaydet", ["VIEW"] = "Görüntüle",
        ["INSPECT"] = "İncele", ["SEARCH"] = "Ara", ["HIDE"] = "Saklan", ["JOIN"] = "Katıl", ["TERMS"] = "Koşullar",
        ["MARKERS"] = "İşaretler", ["DROP"] = "Bırak", ["EQUIP"] = "Kuşan", ["UNEQUIP"] = "Çıkar", ["STORE"] = "Dükkân",
        ["HELP"] = "Yardım", ["OPTIONS"] = "Seçenekler", ["CAMERA"] = "Kamera", ["TOGGLE"] = "Aç / kapat", ["GRAB"] = "Tut",
        ["SIT"] = "Otur", ["STAND UP"] = "Kalk", ["SLEEP"] = "Uyu", ["SHOWER"] = "Duş al", ["TURN OFF"] = "Kapat",
        ["TURN ON"] = "Aç", ["WATCH"] = "İzle", ["LISTEN"] = "Dinle", ["DRIVE"] = "Sür", ["GET IN"] = "Bin",
        ["GET OUT"] = "İn", ["STEAL"] = "Çal", ["HACK"] = "Hackle", ["LOOT"] = "Yağmala", ["COLLECT"] = "Topla",
    };

    /// <summary>Sözcükler tuş çubuğu sözlüğündeki bir komut mu ("Talk", "BACK", "Pick up"; büyük-küçük harf fark etmez).</summary>
    public static bool IsCommand(string words) => Commands.ContainsKey(words.Trim());

    [GeneratedRegex(@"\b([lr])([123])\b")]
    private static partial Regex LowerShoulder();

    /// <summary>
    /// Tuş çubuğundaki komutun Türkçesi: büyük harfli, simgesi bir yanında duran ("OPEN ✕", "✕ OPEN") ya da tek başına
    /// büyük harfli komut ("REFUEL"). Sözlükte yoksa null.
    /// </summary>
    public static string? Translate(string paragraph)
    {
        var tokens = paragraph.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (tokens.Length == 0) return null;
        int start = 0, end = tokens.Length;
        while (start < end && ButtonPrompts.IsIcon(tokens[start])) start++;
        while (end > start && ButtonPrompts.IsIcon(tokens[end - 1])) end--;
        if (end <= start) return null;
        var words = string.Join(' ', tokens[start..end]).Trim('.', ',', ':', '!', '?');
        // Yalnız büyük harfli komut: sıradan cümledeki "open" / "Back" sözlükle değişmez.
        if (!words.Any(char.IsLetter) || words != words.ToUpperInvariant()) return null;
        if (!Commands.TryGetValue(words, out var turkish)) return null;
        var before = string.Join(' ', tokens[..start]);
        var after = string.Join(' ', tokens[end..]);
        return string.Join(' ', new[] { before, turkish, after }.Where(part => part.Length > 0));
    }

    /// <summary>
    /// Çeviri motorunun düşürdüğü tuş simgelerini geri koyar: aslında başta duran simge başa, öteki simgeler sona
    /// (sıralarıyla). Motorun küçülttüğü omuz tuşu ("l1") yeniden büyük harf olur.
    /// </summary>
    public static string KeepIcons(string source, string translation)
    {
        var fixedCase = LowerShoulder().Replace(translation, match => match.Groups[1].Value.ToUpperInvariant() + match.Groups[2].Value);
        var tokens = source.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        // Xbox'ın omuz ve tetik tuşu ("LT") da küçültülebilir ("lt"); yalnız kaynakta geçen ad düzeltilir.
        foreach (var name in tokens.Select(token => token.Trim('.', ',', ':')).Where(ButtonTokens.XboxNames.Contains).Distinct())
            fixedCase = Regex.Replace(fixedCase, $@"(?<![\w]){name}(?![\w])", name, RegexOptions.IgnoreCase);
        var have = fixedCase.Split(' ', StringSplitOptions.RemoveEmptyEntries).ToList();
        var leading = new List<string>();
        var trailing = new List<string>();
        var seenWord = false;
        foreach (var token in tokens)
        {
            if (!ButtonPrompts.IsIcon(token) || token is "O" or "X") { seenWord = true; continue; }
            if (have.Remove(token)) continue;
            // Omuz tuşu metin olarak kalmış olabilir ("L1"); yalnız simge gibi duranlar eklenir.
            if (seenWord) trailing.Add(token); else leading.Add(token);
        }
        if (leading.Count == 0 && trailing.Count == 0) return fixedCase;
        return string.Join(' ', leading.Concat([fixedCase.Trim()]).Concat(trailing));
    }
}
