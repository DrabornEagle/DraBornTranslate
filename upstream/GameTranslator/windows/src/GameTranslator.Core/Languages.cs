// SPDX-License-Identifier: AGPL-3.0-only

using System.Globalization;

namespace GameTranslator.Core;

/// <summary>
/// Çevirinin yapılabildiği bir dil. <paramref name="Code"/> ISO 639-1'dir ("zh": Basitleştirilmiş Çince).
/// </summary>
/// <param name="NativeName">Dilin kendi adı (listede her zaman gösterilir: Türkçe bilmeyen kullanıcı kendi dilini tanır).</param>
/// <param name="Rtl">Sağdan sola yazılır (Arapça, Farsça, İbranice).</param>
/// <param name="DeepL">DeepL'in hedef dil kodu; DeepL bu dile çevirmiyorsa null.</param>
/// <param name="Google">Google çeviri ucunun beklediği kod (çoğunda <paramref name="Code"/> ile aynı).</param>
public sealed record LanguageInfo(string Code, string NativeName, string EnglishName, string TurkishName, bool Rtl = false, string? DeepL = null, string? Google = null)
{
    public string GoogleCode => Google ?? Code;

    /// <summary>Listede gösterilecek ad: dilin kendi adı ve arayüz dilindeki adı ("日本語 · Japonca").</summary>
    public string Label(string uiLanguage)
    {
        var local = uiLanguage.StartsWith("tr", StringComparison.OrdinalIgnoreCase) ? TurkishName : EnglishName;
        return string.Equals(local, NativeName, StringComparison.OrdinalIgnoreCase) ? NativeName : $"{NativeName} · {local}";
    }
}

/// <summary>
/// Çeviri dilleri (hedef). Bilgisayar ve telefon uygulaması aynı listeyi kullanır (telefondaki karşılığı
/// <c>mobile/.../core/Languages.kt</c> içindedir; kodlar aynıdır). Her dilde her motor çalışmaz: yerel model yalnız
/// İngilizce→Türkçedir; desteklemeyen motor yerine Google kullanılır. DeepL kodları DeepL'in desteklenen diller
/// sayfasından (Kasım 2026: listedeki 27 dilin hepsi); bir anahtarla denenmedi, DeepL bir dili reddederse o satır
/// yedek motorla (Google) çevrilir.
/// </summary>
public static class Languages
{
    public const string Turkish = "tr", English = "en";

    public static IReadOnlyList<LanguageInfo> Targets { get; } =
    [
        new("tr", "Türkçe", "Turkish", "Türkçe", DeepL: "TR"),
        new("en", "English", "English", "İngilizce", DeepL: "EN-US"),
        new("ar", "العربية", "Arabic", "Arapça", Rtl: true, DeepL: "AR"),
        new("ja", "日本語", "Japanese", "Japonca", DeepL: "JA"),
        new("de", "Deutsch", "German", "Almanca", DeepL: "DE"),
        new("fr", "Français", "French", "Fransızca", DeepL: "FR"),
        new("es", "Español", "Spanish", "İspanyolca", DeepL: "ES"),
        new("it", "Italiano", "Italian", "İtalyanca", DeepL: "IT"),
        new("pt", "Português", "Portuguese", "Portekizce", DeepL: "PT-BR"),
        new("ru", "Русский", "Russian", "Rusça", DeepL: "RU"),
        new("zh", "简体中文", "Chinese (Simplified)", "Çince (Basitleştirilmiş)", DeepL: "ZH-HANS", Google: "zh-CN"),
        new("ko", "한국어", "Korean", "Korece", DeepL: "KO"),
        new("pl", "Polski", "Polish", "Lehçe", DeepL: "PL"),
        new("nl", "Nederlands", "Dutch", "Felemenkçe", DeepL: "NL"),
        new("id", "Bahasa Indonesia", "Indonesian", "Endonezce", DeepL: "ID"),
        new("hi", "हिन्दी", "Hindi", "Hintçe", DeepL: "HI"),
        new("fa", "فارسی", "Persian", "Farsça", Rtl: true, DeepL: "FA"),
        new("uk", "Українська", "Ukrainian", "Ukraynaca", DeepL: "UK"),
        new("el", "Ελληνικά", "Greek", "Yunanca", DeepL: "EL"),
        new("hu", "Magyar", "Hungarian", "Macarca", DeepL: "HU"),
        new("ro", "Română", "Romanian", "Rumence", DeepL: "RO"),
        new("cs", "Čeština", "Czech", "Çekçe", DeepL: "CS"),
        new("sv", "Svenska", "Swedish", "İsveççe", DeepL: "SV"),
        new("th", "ไทย", "Thai", "Tayca", DeepL: "TH"),
        new("vi", "Tiếng Việt", "Vietnamese", "Vietnamca", DeepL: "VI"),
        new("he", "עברית", "Hebrew", "İbranice", Rtl: true, DeepL: "HE", Google: "iw"),
        new("az", "Azərbaycanca", "Azerbaijani", "Azerbaycanca", DeepL: "AZ"),
    ];

    /// <summary>Koda karşılık gelen dil ("zh-CN", "pt_BR", "TR" gibi biçimler de kabul edilir); listede yoksa null.</summary>
    public static LanguageInfo? Find(string? code)
    {
        if (string.IsNullOrWhiteSpace(code)) return null;
        var two = code.Trim().Split('-', '_')[0].ToLowerInvariant();
        if (two == "iw") two = "he";
        if (two == "in") two = "id";
        return Targets.FirstOrDefault(language => language.Code == two);
    }

    /// <summary>Dil listedeyse kendisi, değilse Türkçe (eski ya da elle bozulmuş ayar dosyası için).</summary>
    public static LanguageInfo OrDefault(string? code) => Find(code) ?? Targets[0];

    public static bool IsTurkish(string? code) => Find(code)?.Code == Turkish;

    /// <summary>
    /// İlk kurulumda çeviri dili: sistem dili listedeyse o, değilse İngilizce. Türkçe sistemde Türkçe çıkar (bugüne
    /// kadarki davranış).
    /// </summary>
    public static string DefaultTarget(CultureInfo culture) => Find(culture.TwoLetterISOLanguageName)?.Code ?? English;

    /// <summary>
    /// Metin sağdan sola mı okunur: ilk "güçlü" harfe bakılır (Unicode çift yönlü metin kuralının ilk adımı). Arapça bir
    /// satır "MARKOS:" gibi Latin bir adla başlasa da çoğunluğu Arapçaysa sağdan sola sayılır.
    /// </summary>
    public static bool IsRtlText(string? text)
    {
        if (string.IsNullOrEmpty(text)) return false;
        int rtl = 0, ltr = 0;
        foreach (var rune in text.EnumerateRunes())
        {
            var value = rune.Value;
            // İbranice, Arapça, Süryanice, Thaana, NKo ve Arapça sunum biçimleri
            var isRtl = value is >= 0x0590 and <= 0x08FF or >= 0xFB1D and <= 0xFDFF or >= 0xFE70 and <= 0xFEFF or >= 0x10800 and <= 0x10FFF or >= 0x1E800 and <= 0x1EFFF;
            if (isRtl) rtl++;
            else if (System.Text.Rune.IsLetter(rune)) ltr++;
        }
        return rtl > 0 && rtl >= ltr;
    }
}
