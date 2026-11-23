// SPDX-License-Identifier: AGPL-3.0-only

using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Documents;
using System.Windows.Media;
using GameTranslator.Core;
using GameTranslator.Shell;

namespace GameTranslator.App.Controls;

/// <summary>
/// İlk ekrandaki dil düğmesi: dünya simgesi ve arayüz dilinin kendi adı. Tıklanınca açılan kutuda uygulama dili ve çeviri
/// dili, her dilin kendi adıyla seçilir; böylece uygulamanın o anki dilini okuyamayan kullanıcı da kendi dilini bulur.
/// Uygulama dili hemen değişir (<see cref="UiLanguageChanged"/> ile pencere ekranı yeniden kurar).
/// </summary>
public sealed class LanguageButton : Button
{
    /// <summary>Segoe Fluent Icons / MDL2: dünya.</summary>
    public const string Globe = "";

    /// <summary>Arayüz dili değişti: açık ekran yeni dille yeniden kurulmalı.</summary>
    public static event Action? UiLanguageChanged;

    /// <summary>Çeviri dili bu düğmeden değişti (ayar ekranındaki kutu kendini yeniler).</summary>
    public event Action? TargetChanged;

    private readonly TextBlock label = new() { FontSize = 13, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center };
    private Popup? popup;

    public AppController? Controller { get; set; }

    public LanguageButton()
    {
        Cursor = System.Windows.Input.Cursors.Hand;
        Template = PlainTemplate();
        var glyph = new TextBlock { Text = Globe, FontSize = 16, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 7, 0) };
        glyph.SetResourceReference(TextBlock.FontFamilyProperty, "IconFont");
        glyph.SetResourceReference(TextBlock.ForegroundProperty, "textPrimaryBrush");
        label.SetResourceReference(TextBlock.ForegroundProperty, "textPrimaryBrush");
        label.Text = NameOf(Localizer.Current.Language);
        var pill = new Border
        {
            Height = 36, CornerRadius = new CornerRadius(18), Padding = new Thickness(12, 0, 14, 0),
            Child = new StackPanel { Orientation = Orientation.Horizontal, Children = { glyph, label } },
        };
        pill.SetResourceReference(Border.BackgroundProperty, "cardStrongBrush");
        Content = pill;
        // Ekran okuyucu ve ipucu iki dilde: o anki dili bilmeyen de anlasın
        ToolTip = Heading("Language");
        System.Windows.Automation.AutomationProperties.SetName(this, "Language");
        Click += (_, _) => Open();
    }

    private static string NameOf(string code) => Localizer.UiLanguages.FirstOrDefault(l => l.Code == code).Name ?? code;

    /// <summary>Başlık o anki dilde; dil İngilizce değilse yanında İngilizcesi de yazar ("Çeviri dili · Translate into").</summary>
    internal static string Heading(string key)
    {
        var text = Localizer.Current[key];
        return text == key ? key : $"{text} · {key}";
    }

    private void Open()
    {
        if (Controller is null) return;
        popup = new Popup
        {
            PlacementTarget = this, Placement = PlacementMode.Bottom, StaysOpen = false, AllowsTransparency = true,
            VerticalOffset = 4, PopupAnimation = PopupAnimation.Fade,
        };
        popup.Child = BuildPanel(Controller.Settings, PickUi, PickTarget);
        popup.IsOpen = true;
    }

    private void PickUi(string code)
    {
        if (popup is not null) popup.IsOpen = false;
        if (Controller is null) return;
        Controller.Settings = Controller.Settings with { UiLanguage = code };
        var before = Localizer.Current.Language;
        Localizer.Use(code);
        if (Localizer.Current.Language != before) UiLanguageChanged?.Invoke();
    }

    private void PickTarget(string code)
    {
        if (popup is not null) popup.IsOpen = false;
        if (Controller is null) return;
        Controller.Settings = Controller.Settings with { TargetLanguage = code };
        TargetChanged?.Invoke();
    }

    /// <summary>Başka bir yerden (ayar ekranı) arayüz dili değiştiğinde ekranın yeniden kurulmasını ister.</summary>
    public static void NotifyUiLanguageChanged() => UiLanguageChanged?.Invoke();

    /// <summary>Açılan kutunun içeriği (ekran görüntüsü aracı da bunu çizer).</summary>
    internal static FrameworkElement BuildPanel(UserSettings settings, Action<string> pickUi, Action<string> pickTarget)
    {
        var stack = new StackPanel();
        stack.Children.Add(Title(Globe, Heading("App language")));
        var ui = new WrapPanel { Margin = new Thickness(0, 0, 0, 14) };
        foreach (var (code, name) in Localizer.UiLanguages)
            ui.Children.Add(Chip(name, code == Localizer.Current.Language, () => pickUi(code), minWidth: 110));
        stack.Children.Add(ui);

        // E8C1: "字A" (yazı / çeviri) simgesi
        stack.Children.Add(Title("", Heading("Translate into")));
        var target = Languages.OrDefault(settings.TargetLanguage).Code;
        var grid = new UniformGrid { Columns = 3 };
        foreach (var language in Languages.Targets)
        {
            var chip = Chip(language.NativeName, language.Code == target, () => pickTarget(language.Code), minWidth: 0);
            chip.ToolTip = language.EnglishName;
            chip.FlowDirection = FlowDirection.LeftToRight;
            grid.Children.Add(chip);
        }
        stack.Children.Add(grid);

        var card = new Border { Width = 430, CornerRadius = new CornerRadius(14), BorderThickness = new Thickness(1), Padding = new Thickness(14, 12, 10, 10), Child = stack, Margin = new Thickness(6) };
        card.SetResourceReference(Border.BackgroundProperty, "backgroundBrush");
        card.SetResourceReference(Border.BorderBrushProperty, "hairlineBrush");
        card.SetResourceReference(TextElement.ForegroundProperty, "textPrimaryBrush");
        return card;
    }

    private static FrameworkElement Title(string glyph, string text)
    {
        var icon = new TextBlock { Text = glyph, FontSize = 14, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 7, 0) };
        icon.SetResourceReference(TextBlock.FontFamilyProperty, "IconFont");
        icon.SetResourceReference(TextBlock.ForegroundProperty, "textSecondaryBrush");
        var title = new TextBlock { Text = text, FontSize = 12, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center };
        title.SetResourceReference(TextBlock.ForegroundProperty, "textSecondaryBrush");
        return new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(2, 0, 0, 8), Children = { icon, title } };
    }

    private static Button Chip(string text, bool selected, Action click, double minWidth)
    {
        var name = new TextBlock { Text = text, FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis, HorizontalAlignment = HorizontalAlignment.Center, FontWeight = selected ? FontWeights.Bold : FontWeights.Normal };
        name.SetResourceReference(TextBlock.ForegroundProperty, selected ? "accentTextBrush" : "textPrimaryBrush");
        var border = new Border { CornerRadius = new CornerRadius(9), Padding = new Thickness(10, 7, 10, 7), Margin = new Thickness(0, 0, 6, 6), MinWidth = minWidth, Child = name };
        border.SetResourceReference(Border.BackgroundProperty, selected ? "accentSoftBrush" : "cardStrongBrush");
        var button = new Button { Content = border, Template = PlainTemplate(), Cursor = System.Windows.Input.Cursors.Hand };
        System.Windows.Automation.AutomationProperties.SetName(button, text);
        button.Click += (_, _) => click();
        return button;
    }

    private static ControlTemplate PlainTemplate()
    {
        var template = new ControlTemplate(typeof(Button)) { VisualTree = new FrameworkElementFactory(typeof(ContentPresenter)) };
        template.Seal();
        return template;
    }
}
