// SPDX-License-Identifier: AGPL-3.0-only

using GameTranslator.Core;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;
using GameTranslator.App.Controls;
using GameTranslator.Translation;

namespace GameTranslator.App.Views;

/// <summary>
/// Oynanan oyunu elle seçme: Otomatik ya da hazır çeviri paketi olan oyunlardan biri. Seçilen oyunun paketi ilk
/// cümleden kullanılır (<see cref="CommunityPatchManager.SelectedGameId"/>). Satıra basınca seçilir ve pencere kapanır.
/// </summary>
public partial class GamePickerWindow : Window
{
    private static readonly CultureInfo Turkish = CultureInfo.GetCultureInfo("tr-TR");
    private readonly IReadOnlyList<(string Id, string Title, int Lines)> games;

    internal GamePickerWindow()
    {
        InitializeComponent();
        games = CommunityPatchManager.Shared.Games;
        Fill();
        Loaded += (_, _) => Search.Focus();
    }

    /// <summary>Pencereyi açar; seçim değiştiyse true.</summary>
    public static bool Open(Window? owner)
    {
        var pm = CommunityPatchManager.Shared;
        var before = (pm.SelectedGameId, pm.CustomGameName);
        new GamePickerWindow { Owner = owner }.ShowDialog();
        return (pm.SelectedGameId, pm.CustomGameName) != before;
    }

    /// <summary>Paketi henüz olmayan yeni oyunlar: seçilince satırları oyun adıyla toplanır (capture kart ve Xbox'ta da).</summary>
    internal static readonly string[] NewGames = ["Grand Theft Auto VI"];

    private void Fill()
    {
        var query = Search.Text.Trim();
        SearchHint.Visibility = query.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        var pm = CommunityPatchManager.Shared;
        var selected = pm.SelectedGameId;
        var custom = pm.CustomGameName;
        List.Children.Clear();
        if (query.Length == 0)
            Add(Localizer.Current["Automatic"], Localizer.Current["The game is recognized from its first few lines (recommended)"],
                selected is null && custom is null, () => { pm.SetCustomGame(null); pm.SelectedGameId = null; });
        foreach (var name in NewGames)
        {
            if (query.Length > 0 && Turkish.CompareInfo.IndexOf(name, query, CompareOptions.IgnoreCase) < 0) continue;
            if (games.Any(g => string.Equals(g.Title, name, StringComparison.OrdinalIgnoreCase))) continue;
            Add(name, Localizer.Current["No pack yet: the translation model translates it; your lines help make its pack"],
                string.Equals(custom, name, StringComparison.OrdinalIgnoreCase), () => pm.SetCustomGame(name));
        }
        foreach (var (id, title, lines) in games)
        {
            if (query.Length > 0 && !Turkish.CompareInfo.IsPrefix(title, query, CompareOptions.IgnoreCase) &&
                Turkish.CompareInfo.IndexOf(title, query, CompareOptions.IgnoreCase) < 0) continue;
            Add(title, Localizer.Current.Format("%@ ready lines", lines.ToString("N0", Localizer.Current.Language == "tr" ? Turkish : CultureInfo.InvariantCulture)),
                id == selected, () => pm.SelectedGameId = id);
        }
        // Listede olmayan oyun: yazılan adla seçilir (paket yok, satırları o adla toplanır)
        if (query.Length >= 2 && !games.Any(g => string.Equals(g.Title, query, StringComparison.OrdinalIgnoreCase))
            && !NewGames.Any(n => string.Equals(n, query, StringComparison.OrdinalIgnoreCase)))
            Add(Localizer.Current.Format("I'm playing \"%@\"", query), Localizer.Current["No pack yet: the translation model translates it; your lines help make its pack"],
                string.Equals(custom, query, StringComparison.OrdinalIgnoreCase), () => pm.SetCustomGame(query));
        if (List.Children.Count == 0)
            List.Children.Add(new TextBlock
            {
                Text = Localizer.Current["There is no game pack with this name. For games without a pack, stay on Automatic; the translation model translates them."],
                Style = (Style)FindResource("HintText"), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(12),
            });
    }

    private void Add(string title, string detail, bool isSelected, Action choose)
    {
        if (List.Children.Count > 0)
            List.Children.Add(new Rectangle { Height = 1, Fill = (Brush)FindResource("hairlineBrush"), Margin = new Thickness(10, 0, 10, 0) });
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var text = new StackPanel();
        text.Children.Add(new TextBlock { Text = title, FontSize = 15, FontWeight = isSelected ? FontWeights.SemiBold : FontWeights.Normal });
        text.Children.Add(new TextBlock { Text = detail, FontSize = 12, Foreground = (Brush)FindResource("textSecondaryBrush"), Margin = new Thickness(0, 2, 0, 0) });
        grid.Children.Add(text);
        if (isSelected)
        {
            var check = new TextBlock
            {
                Text = Glyphs.Check, Style = (Style)FindResource("GlyphText"), FontSize = 16,
                Foreground = (Brush)FindResource("accentBrush"), VerticalAlignment = VerticalAlignment.Center,
            };
            Grid.SetColumn(check, 1);
            grid.Children.Add(check);
        }
        var row = new Button { Style = (Style)FindResource("RowButton"), Content = grid, HorizontalContentAlignment = HorizontalAlignment.Stretch };
        row.Click += (_, _) =>
        {
            choose();
            Close();
        };
        List.Children.Add(row);
    }

    private void OnSearch(object sender, TextChangedEventArgs e) => Fill();

    private void OnClose(object sender, RoutedEventArgs e) => Close();
}
