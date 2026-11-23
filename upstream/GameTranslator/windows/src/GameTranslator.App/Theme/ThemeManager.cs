// SPDX-License-Identifier: AGPL-3.0-only

using System.Windows;
using System.Windows.Media;
using GameTranslator.Core;
using Microsoft.Win32;

namespace GameTranslator.App.Theme;

/// <summary>
/// design/tokens.json'daki değerleri uygulama kaynaklarına çevirir. Renkler Windows'un uygulama
/// temasını (açık/koyu) izler; XAML bunları DynamicResource ile kullanır, tema değişince anında güncellenir.
/// </summary>
public static class ThemeManager
{
    private static readonly DesignTokens Tokens = DesignTokens.Shared;

    public static void Apply(ResourceDictionary resources, bool? forceDark = null)
    {
        // GameTranslator bir oyun uygulamasıdır; her zaman modern ve şık Koyu Temayı (Dark Theme) kullanır.
        var dark = forceDark ?? true;
        foreach (var (name, pair) in Tokens.Color)
        {
            var color = ToColor(pair.For(dark));
            resources[$"{name}Color"] = color;
            var brush = new SolidColorBrush(color);
            brush.Freeze();
            resources[$"{name}Brush"] = brush;
        }
        foreach (var (name, font) in Tokens.Font)
        {
            resources[$"{name}FontSize"] = font.Size;
            resources[$"{name}FontWeight"] = font.Weight switch
            {
                "bold" => FontWeights.Bold,
                "semibold" => FontWeights.SemiBold,
                "medium" => FontWeights.Medium,
                _ => FontWeights.Normal,
            };
        }
        foreach (var (name, value) in Tokens.Space)
        {
            resources[$"space.{name}"] = value;
            resources[$"pad.{name}"] = new Thickness(value);
        }
        foreach (var (name, value) in Tokens.Radius) resources[$"radius.{name}"] = new CornerRadius(value);
        foreach (var (name, value) in Tokens.Size) resources[$"size.{name}"] = value;
        ApplyGlass(resources);
    }

    /// <summary>
    /// Mobil uygulamayla aynı görünüm (koyu, cam yüzeyli): iOS'un koyu sistem renkleri, yarı saydam kartlar, büyük
    /// köşe yarıçapları ve koyu lacivert geçişli zemin. tokens.json'daki adların üstüne yazar; XAML değişmeden yeni
    /// görünümü alır. Ek kaynaklar: windowBrush (zemin geçişi), cardBrush, wellBrush, hairlineBrush, glowBrush.
    /// </summary>
    private static void ApplyGlass(ResourceDictionary resources)
    {
        void Solid(string name, Color color)
        {
            resources[$"{name}Color"] = color;
            var brush = new SolidColorBrush(color);
            brush.Freeze();
            resources[$"{name}Brush"] = brush;
        }

        // Apple'ın web sitesindeki koyu dil: siyah zemin, düz koyu gri kartlar, tek mavi vurgu, gölgesiz ve geçişsiz.
        Solid("background", Color.FromRgb(0x00, 0x00, 0x00));
        Solid("nav", Color.FromRgb(0x16, 0x16, 0x17));                   // üst menü çubuğu
        Solid("surface", Color.FromRgb(0x1C, 0x1C, 0x1E));
        Solid("card", Color.FromRgb(0x1C, 0x1C, 0x1E));
        Solid("cardStrong", Color.FromRgb(0x2C, 0x2C, 0x2E));            // ikincil düğme, rozet, seçili satır
        Solid("well", Color.FromRgb(0x11, 0x11, 0x12));                  // içe gömük alan (önizleme, liste)
        Solid("border", Color.FromRgb(0x38, 0x38, 0x3A));
        Solid("hairline", Color.FromRgb(0x2C, 0x2C, 0x2E));
        Solid("textPrimary", Color.FromRgb(0xF5, 0xF5, 0xF7));
        Solid("textSecondary", Color.FromRgb(0xA1, 0xA1, 0xA6));
        Solid("textTertiary", Color.FromRgb(0x86, 0x86, 0x8B));
        Solid("accent", Color.FromRgb(0x00, 0x71, 0xE3));
        Solid("onAccent", Color.FromRgb(0xFF, 0xFF, 0xFF));
        Solid("accentText", Color.FromRgb(0x29, 0x97, 0xFF));            // bağlantılar
        Solid("accentSoft", Color.FromArgb(0x33, 0x00, 0x71, 0xE3));
        Solid("success", Color.FromRgb(0x30, 0xD1, 0x58));
        Solid("successSoft", Color.FromArgb(0x29, 0x30, 0xD1, 0x58));
        Solid("warning", Color.FromRgb(0xFF, 0x9F, 0x0A));
        Solid("danger", Color.FromRgb(0xFF, 0x45, 0x3A));
        Solid("dangerSoft", Color.FromArgb(0x29, 0xFF, 0x45, 0x3A));
        Solid("onDanger", Color.FromRgb(0xFF, 0xFF, 0xFF));
        Solid("idle", Color.FromRgb(0x86, 0x86, 0x8B));

        // Zemin düz siyah
        var window = new SolidColorBrush(Color.FromRgb(0x00, 0x00, 0x00));
        window.Freeze();
        resources["windowBrush"] = window;

        // Hale artık kullanılmıyor; eski görünümler için saydam bırakılır
        var glow = new SolidColorBrush(Colors.Transparent);
        glow.Freeze();
        resources["glowBrush"] = glow;

        resources["radius.control"] = new CornerRadius(12);
        resources["radius.card"] = new CornerRadius(22);
        resources["radius.pill"] = new CornerRadius(999);
    }

    /// <summary>Tema değişince kaynakları yeniler.</summary>
    public static void Follow(ResourceDictionary resources)
    {
        SystemEvents.UserPreferenceChanged += (_, e) =>
        {
            if (e.Category == UserPreferenceCategory.General)
                Application.Current.Dispatcher.Invoke(() => Apply(resources, true));
        };
    }

    public static bool IsDarkTheme()
    {
        return true;
    }

    private static Color ToColor(string hex)
    {
        var (r, g, b) = DesignTokens.Rgb(hex) ?? (0, 0, 0);
        return Color.FromRgb((byte)Math.Round(r * 255), (byte)Math.Round(g * 255), (byte)Math.Round(b * 255));
    }
}
