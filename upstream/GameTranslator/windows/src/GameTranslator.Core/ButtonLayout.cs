// SPDX-License-Identifier: AGPL-3.0-only

namespace GameTranslator.Core;

/// <summary>Ayardaki kumanda tuşları seçimi: Otomatik, PlayStation ya da Xbox.</summary>
[System.Text.Json.Serialization.JsonConverter(typeof(System.Text.Json.Serialization.JsonStringEnumConverter<ButtonLayout>))]
public enum ButtonLayout
{
    /// <summary>Görüntü kaynağından (Xbox / PS5) ya da ekrandaki simgelerin biçiminden anlaşılır.</summary>
    Auto = 0,
    PlayStation = 1,
    Xbox = 2,
}

/// <summary>Ekrandaki tuş simgelerinin ailesi.</summary>
public enum ButtonFamily
{
    PlayStation,
    Xbox,
}

/// <summary>
/// Simgeler hangi kumandanın: ayarda seçildiyse o, Otomatik'te kaynağa göre (Xbox kaynağı Xbox, PS5 Remote Play
/// PlayStation); yakalama kartı, masaüstü ve OBS'de karelerdeki simgelerin biçiminden (dairede harf mi, PlayStation
/// sembolü mü) karar verilir. Karar yapışkandır: tek bir yanlış tanınan simge aileyi değiştirmez, birkaç tutarlı
/// simgeden sonra değişir (titreşmesin). Bilinmeden önce PlayStation sayılır (eski davranış).
/// </summary>
public sealed class ButtonLayoutVote
{
    /// <summary>Kararın değişmesi için gereken üst üste ağır basan simge sayısı.</summary>
    public const int Switch = 3;
    private const int Limit = 6;
    private int score;
    private ButtonFamily sticky = ButtonFamily.PlayStation;

    public ButtonLayoutVote(ButtonLayout setting = ButtonLayout.Auto) => Setting = setting;

    /// <summary>Geçerli seçim (kaynağa göre çözülmüş; bkz. <see cref="Resolve"/>).</summary>
    public ButtonLayout Setting { get; set; }

    /// <summary>Şu an beklenen aile.</summary>
    public ButtonFamily Current => Setting switch
    {
        ButtonLayout.PlayStation => ButtonFamily.PlayStation,
        ButtonLayout.Xbox => ButtonFamily.Xbox,
        _ => sticky,
    };

    /// <summary>Otomatik ve henüz karar verilmemiş mi (görüntüdeki biçim kendi başına belirleyici olmalı).</summary>
    public bool Undecided => Setting == ButtonLayout.Auto && Math.Abs(score) < Switch;

    /// <summary>Karar Xbox'a yatıyor mu (seçilmiş ya da Otomatik'te Xbox simgesi daha çok görülmüş).</summary>
    public bool LeansXbox => Setting == ButtonLayout.Xbox || (Setting == ButtonLayout.Auto && score > 0);

    /// <summary>Kesin biçimli bir simge görüldü (dairede yeşil "A" Xbox'tır, üçgen PlayStation'dır).</summary>
    public void Add(ButtonFamily seen)
    {
        if (Setting != ButtonLayout.Auto) return;
        score = Math.Clamp(score + (seen == ButtonFamily.Xbox ? 1 : -1), -Limit, Limit);
        if (score >= Switch) sticky = ButtonFamily.Xbox;
        else if (score <= -Switch) sticky = ButtonFamily.PlayStation;
    }

    public void Reset()
    {
        score = 0;
        sticky = ButtonFamily.PlayStation;
    }

    /// <summary>
    /// Otomatik seçim kaynağa göre çözülür: <paramref name="xboxSource"/> true ise Xbox, false ise PlayStation (PS5
    /// Remote Play), null ise (yakalama kartı, masaüstü, OBS) görüntüden karar verilir.
    /// </summary>
    public static ButtonLayout Resolve(ButtonLayout setting, bool? xboxSource) => setting != ButtonLayout.Auto ? setting
        : xboxSource switch { true => ButtonLayout.Xbox, false => ButtonLayout.PlayStation, null => ButtonLayout.Auto };
}

/// <summary>
/// Bütün platformların ortak tuş işaretleri. PlayStation: ✕ ○ △ □, L1–R3, OPTIONS, TOUCHPAD (▦). Xbox: Ⓐ Ⓑ Ⓧ Ⓨ,
/// LB RB LT RT LS RS, View ⧉ ve Menu ☰. Yön tuşu ikisinde de ↑ ↓ ← → ✜.
/// </summary>
public static class ButtonTokens
{
    public const string A = "Ⓐ", B = "Ⓑ", X = "Ⓧ", Y = "Ⓨ", View = "⧉", Menu = "☰";

    /// <summary>Xbox'ın omuz, tetik ve çubuk tuşları.</summary>
    public static readonly IReadOnlySet<string> XboxNames = new HashSet<string>(StringComparer.Ordinal) { "LB", "RB", "LT", "RT", "LS", "RS" };

    /// <summary>Xbox yüz tuşunun harfi ("A") → işareti ("Ⓐ"); yüz tuşu harfi değilse null.</summary>
    public static string? XboxFace(char letter) => char.ToUpperInvariant(letter) switch { 'A' => A, 'B' => B, 'X' => X, 'Y' => Y, _ => null };

    /// <summary>İşaret hangi ailenin: yön tuşu ve harita işareti ikisinde de olabilir (null).</summary>
    public static ButtonFamily? FamilyOf(string token) => token switch
    {
        A or B or X or Y or View or Menu => ButtonFamily.Xbox,
        _ when XboxNames.Contains(token) => ButtonFamily.Xbox,
        "✕" or "○" or "△" or "□" or "▦" or "OPTIONS" or "TOUCHPAD" or "L1" or "L2" or "L3" or "R1" or "R2" or "R3" => ButtonFamily.PlayStation,
        _ => null,
    };

    /// <summary>Xbox yüz tuşunun rengine göre işareti (ton 0–360): yeşil Ⓐ, kırmızı Ⓑ, mavi Ⓧ, sarı Ⓨ.</summary>
    internal static string? XboxFaceByHue(double hue) => hue switch
    {
        >= 75 and < 170 => A,
        >= 170 and < 265 => X,
        >= 38 and < 75 => Y,
        >= 330 or < 22 => B,
        _ => null,
    };
}
