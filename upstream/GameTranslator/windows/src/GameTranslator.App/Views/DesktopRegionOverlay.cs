// SPDX-License-Identifier: AGPL-3.0-only

using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using GameTranslator.Core;
using GameTranslator.Shell;
using TextBox = GameTranslator.Core.TextBox;

namespace GameTranslator.App.Views;

/// <summary>
/// Bilgisayar ekranında sürekli okunan ek alanların (görev yazısı, ipucu kutusu, telefon mesajı) çevirisi: oyunun
/// bulunduğu ekranın tamamını kaplayan şeffaf, tıklama geçiren pencere. Her çeviri oyundaki İngilizce yazının üstünü
/// örter; altyazının yazı tipi ve rengiyle yazılır, tuşlar simge, renkli sözcükler oyundaki renkte (TV ve OBS ile aynı).
/// Altyazı penceresi gibi ekran kaydından gizlenir (yayına OBS kaplaması gider). Bilgisayarda altyazı açıkken görünür.
/// </summary>
public sealed class DesktopRegionOverlay : Window
{
    private const int GWL_EXSTYLE = -20;
    private const int WS_EX_TRANSPARENT = 0x00000020;
    private const int WS_EX_LAYERED = 0x00080000;
    private const int WS_EX_NOACTIVATE = 0x08000000;
    private const int WS_EX_TOOLWINDOW = 0x00000080;
    private const uint WDA_EXCLUDEFROMCAPTURE = 0x00000011;

    [DllImport("user32.dll")] private static extern int GetWindowLong(IntPtr hWnd, int nIndex);
    [DllImport("user32.dll")] private static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);
    [DllImport("user32.dll")] private static extern bool SetWindowDisplayAffinity(IntPtr hWnd, uint dwAffinity);

    private const double Pad = 0.004;
    private const double MinFont = 11, MaxFont = 48;
    private static DesktopRegionOverlay? _instance;
    private readonly Canvas _canvas = new();
    private readonly Dictionary<string, Border> _boxes = [];
    /// <summary>Kutunun kendi yeri (İngilizce yazının üstü), taşınmadan önce; piksel.</summary>
    private readonly Dictionary<string, Point> _base = [];

    /// <summary>Elle taşınan kutuların kayması (ayardan; <see cref="UserSettings.PcRegionOffsets"/>).</summary>
    public static IReadOnlyDictionary<string, PcOffset> Offsets { get; set; } = new Dictionary<string, PcOffset>();

    private static bool _moving;
    private static Dictionary<string, PcOffset> _moved = [];
    private static Action<IReadOnlyDictionary<string, PcOffset>>? _onMoved;
    private Border? _dragging;
    private Point _dragStart, _boxStart;

    /// <summary>Kutular fareyle taşınıyor mu.</summary>
    public static bool IsMoving => _moving;

    private DesktopRegionOverlay()
    {
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        Topmost = true;
        ShowInTaskbar = false;
        ShowActivated = false;
        Focusable = false;
        IsHitTestVisible = false;
        Content = _canvas;
        SourceInitialized += (_, _) =>
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            if (hwnd == IntPtr.Zero) return;
            SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE);
            SetWindowLong(hwnd, GWL_EXSTYLE, GetWindowLong(hwnd, GWL_EXSTYLE) | WS_EX_LAYERED | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW);
            ApplyClickThrough(!_moving);
        };
    }

    /// <summary>Taşırken pencere fareyi alır (yalnız kutuların üstünde; saydam yerler yine geçirir), değilse geçirir.</summary>
    private void ApplyClickThrough(bool through)
    {
        IsHitTestVisible = !through;
        var hwnd = new WindowInteropHelper(this).Handle;
        if (hwnd == IntPtr.Zero) return;
        var style = GetWindowLong(hwnd, GWL_EXSTYLE);
        SetWindowLong(hwnd, GWL_EXSTYLE, through ? style | WS_EX_TRANSPARENT : style & ~WS_EX_TRANSPARENT);
    }

    /// <summary>
    /// Kutuları fareyle taşıma: açık alanların kutuları (çevirisi yoksa alan adıyla bir örnek) görünür, sürüklenir;
    /// kutuya çift tıklamak ya da <see cref="EndMove"/> bitirir ve kaymalar <paramref name="onMoved"/> ile kaydedilir.
    /// </summary>
    public static void BeginMove(IReadOnlyList<WatchedArea> areas, SubtitleStyle? style, Action<IReadOnlyDictionary<string, PcOffset>> onMoved)
    {
        if (Application.Current == null) return;
        Application.Current.Dispatcher.BeginInvoke(() =>
        {
            _moving = true;
            _moved = new Dictionary<string, PcOffset>(Offsets);
            _onMoved = onMoved;
            _instance ??= new DesktopRegionOverlay();
            var window = _instance;
            foreach (var area in areas)
            {
                if (window._boxes.ContainsKey(area.Id)) continue;
                var r = area.Region;
                // Çevirisi henüz gelmemiş alan için örnek: oyundaki yazının yerinde, tek satır.
                var height = Math.Min(r.Bottom - r.Top, 0.05);
                window.Place(area.Id, area.Name + " ✥", new TextBox(r.Left, r.Top, r.Right, r.Top + height), height, style);
            }
            foreach (var border in window._boxes.Values) border.Cursor = Cursors.SizeAll;
            if (!window.IsVisible) window.Show();
            window.ApplyClickThrough(false);
        });
    }

    /// <summary>Taşımayı bitirir, kaymaları kaydeder.</summary>
    public static void EndMove()
    {
        if (Application.Current == null) return;
        Application.Current.Dispatcher.BeginInvoke(() =>
        {
            if (!_moving) return;
            _moving = false;
            Offsets = _moved;
            _onMoved?.Invoke(_moved);
            _onMoved = null;
            if (_instance is not { } window) return;
            window.ApplyClickThrough(true);
            foreach (var border in window._boxes.Values) border.ClearValue(CursorProperty);
            // Taşıma için konan örnek kutular kalkar; gerçek çeviriler sonraki okumada gelir.
            foreach (var id in window._boxes.Where(pair => pair.Value.Tag is "sample").Select(pair => pair.Key).ToList()) window.Remove(id);
        });
    }

    /// <summary>Bütün kutular İngilizce yazının üstüne döner (kaymalar silinir).</summary>
    public static void ResetPositions()
    {
        if (Application.Current == null) return;
        Application.Current.Dispatcher.BeginInvoke(() =>
        {
            Offsets = new Dictionary<string, PcOffset>();
            _moved = [];
            if (_instance is not { } window) return;
            foreach (var (id, border) in window._boxes)
                if (window._base.TryGetValue(id, out var at)) { Canvas.SetLeft(border, at.X); Canvas.SetTop(border, at.Y); }
        });
    }

    private void OnBoxDown(object sender, MouseButtonEventArgs e)
    {
        if (!_moving || sender is not Border border) return;
        if (e.ClickCount >= 2) { EndMove(); return; }
        _dragging = border;
        _dragStart = e.GetPosition(_canvas);
        _boxStart = new Point(Canvas.GetLeft(border), Canvas.GetTop(border));
        border.CaptureMouse();
        e.Handled = true;
    }

    private void OnBoxMove(object sender, MouseEventArgs e)
    {
        if (_dragging is not { } border || !ReferenceEquals(sender, border)) return;
        var now = e.GetPosition(_canvas);
        var x = Math.Clamp(_boxStart.X + now.X - _dragStart.X, 0, Math.Max(0, Width - border.ActualWidth));
        var y = Math.Clamp(_boxStart.Y + now.Y - _dragStart.Y, 0, Math.Max(0, Height - border.ActualHeight));
        Canvas.SetLeft(border, x);
        Canvas.SetTop(border, y);
    }

    private void OnBoxUp(object sender, MouseButtonEventArgs e)
    {
        if (_dragging is not { } border) return;
        border.ReleaseMouseCapture();
        _dragging = null;
        var id = _boxes.FirstOrDefault(pair => ReferenceEquals(pair.Value, border)).Key;
        if (id is null || !_base.TryGetValue(id, out var at) || Width <= 0 || Height <= 0) return;
        _moved[id] = new PcOffset((Canvas.GetLeft(border) - at.X) / Width, (Canvas.GetTop(border) - at.Y) / Height);
    }

    /// <summary>Ek alanın çevirisi; <paramref name="text"/> boşsa o alanın kutusu kalkar.</summary>
    public static void Show(string id, string text, TextBox? box, double? lineHeight, SubtitleStyle? style)
    {
        if (Application.Current == null) return;
        Application.Current.Dispatcher.BeginInvoke(() =>
        {
            if (!DesktopSubtitleOverlay.IsOverlayEnabled || string.IsNullOrWhiteSpace(text) || box is null)
            {
                // Taşırken alan boşalsa da kutusu kalır (yerini göstermek için)
                if (!_moving) _instance?.Remove(id);
                return;
            }
            _instance ??= new DesktopRegionOverlay();
            _instance.Place(id, text, box.Value, lineHeight, style);
        });
    }

    /// <summary>Bütün ek alan kutularını kaldırır.</summary>
    public static void Clear()
    {
        if (Application.Current == null) return;
        Application.Current.Dispatcher.BeginInvoke(() =>
        {
            if (_instance is not { } window || _moving) return;
            window._canvas.Children.Clear();
            window._boxes.Clear();
            window._base.Clear();
            window.Hide();
        });
    }

    private void Remove(string id)
    {
        if (!_boxes.Remove(id, out var border)) return;
        _base.Remove(id);
        _canvas.Children.Remove(border);
        if (_boxes.Count == 0) Hide();
    }

    private void Place(string id, string text, TextBox box, double? lineHeight, SubtitleStyle? style)
    {
        var work = DesktopSubtitleOverlay.WorkArea();
        Left = work.Left; Top = work.Top; Width = work.Width; Height = work.Height;
        if (!IsVisible) Show();

        if (!_boxes.TryGetValue(id, out var border))
        {
            border = new Border
            {
                // İngilizce yazı seçilmesin diye örtücü; TV ve OBS'deki ek alan kutusuyla aynı renk (koyuluk ayardan).
                CornerRadius = new CornerRadius(6),
                Padding = new Thickness(8, 2, 8, 2),
                Child = new TextBlock { LineHeight = double.NaN },
            };
            border.MouseLeftButtonDown += OnBoxDown;
            border.MouseMove += OnBoxMove;
            border.MouseLeftButtonUp += OnBoxUp;
            if (_moving) border.Cursor = Cursors.SizeAll;
            _boxes[id] = border;
            _canvas.Children.Add(border);
        }
        var block = (TextBlock)border.Child;
        // Ek alan görünümü (Okuma Alanı ayarları): zemin koyuluğu, yazı rengi, yazı boyu.
        var alpha = (byte)Math.Round(Math.Clamp(style?.RegionBackground ?? 100, 0, 100) * 255 / 100.0);
        border.Background = new SolidColorBrush(Color.FromArgb(alpha, 28, 28, 32));
        var colorHex = (style?.RegionTextColor ?? style?.TextColor ?? SubtitleTextColor.White).Hex();
        block.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString(colorHex));
        block.Effect = alpha < 180 ? new System.Windows.Media.Effects.DropShadowEffect { Color = Colors.Black, ShadowDepth = 0, BlurRadius = 6, Opacity = 1 } : null;
        block.FontWeight = style?.Bold == true ? FontWeights.Bold : FontWeights.SemiBold;
        if (style is not null && Services.InstalledFonts.Family(style.Font) is { } family) block.FontFamily = family;
        else block.ClearValue(TextBlock.FontFamilyProperty);
        block.FlowDirection = Languages.IsRtlText(text) ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;

        double left = Math.Max(0, box.Left - Pad), top = Math.Max(0, box.Top - Pad);
        var boxWidth = (Math.Min(1, box.Right + Pad) - left) * work.Width;
        var boxHeight = (Math.Min(1, box.Bottom + Pad) - top) * work.Height;
        var line = lineHeight is > 0 ? lineHeight.Value * work.Height : boxHeight;
        var center = (box.Left + box.Right) / 2;
        var centered = Math.Abs(center - 0.5) < 0.12;
        block.TextAlignment = centered ? TextAlignment.Center : TextAlignment.Left;
        // Oyundaki yazı tek satırsa çeviri de tek satır kalır: kutu en çok %35 genişler, yetmezse yazı küçülür.
        var single = !text.Contains('\n') && (box.Bottom - box.Top) * work.Height < line * 1.6;
        var size = Math.Clamp(line * 0.95 * Math.Clamp(style?.RegionTextScale ?? 100, 70, 150) / 100.0, MinFont, MaxFont * 1.5);
        double width, x;
        if (single)
        {
            width = Math.Min(work.Width - 4, boxWidth * 1.35);
            x = centered ? left * work.Width - (width - boxWidth) / 2 : center > 0.5 ? left * work.Width - (width - boxWidth) : left * work.Width;
            x = Math.Clamp(x, 2, work.Width - width - 2);
            block.TextWrapping = TextWrapping.NoWrap;
        }
        else
        {
            width = Math.Max(60, boxWidth);
            x = left * work.Width;
            block.TextWrapping = TextWrapping.Wrap;
        }
        border.Width = width;
        border.MinHeight = boxHeight;
        // Örnek kutu (taşıma sırasında, çevirisi olmayan alan) gerçek çeviri gelince örnek olmaktan çıkar.
        border.Tag = _moving && text.EndsWith(" ✥", StringComparison.Ordinal) ? "sample" : null;
        // Kendi yeri İngilizce yazının üstü; elle taşındıysa kayması eklenir. Taşınırken kutu sürüklenen yerde kalır.
        var home = new Point(x, top * work.Height);
        _base[id] = home;
        if (_dragging != border)
        {
            var offset = (_moving ? _moved : Offsets).TryGetValue(id, out var o) ? o : null;
            Canvas.SetLeft(border, Math.Clamp(home.X + (offset?.X ?? 0) * work.Width, 0, Math.Max(0, work.Width - width)));
            Canvas.SetTop(border, Math.Clamp(home.Y + (offset?.Y ?? 0) * work.Height, 0, Math.Max(0, work.Height - boxHeight)));
        }
        var inner = width - border.Padding.Left - border.Padding.Right;
        var limit = Math.Max(boxHeight * 1.35, line * 2.4);
        while (true)
        {
            block.FontSize = size;
            block.Inlines.Clear();
            Services.ButtonInlines.Append(block.Inlines, text, size);
            block.Measure(new Size(single ? double.PositiveInfinity : inner, double.PositiveInfinity));
            var fits = single ? block.DesiredSize.Width <= inner : block.DesiredSize.Height <= limit;
            if (fits || size <= MinFont) break;
            size -= 1;
        }
    }
}
