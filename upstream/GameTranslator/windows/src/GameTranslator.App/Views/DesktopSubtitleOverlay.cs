// SPDX-License-Identifier: AGPL-3.0-only

using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Threading;
using GameTranslator.Core;

namespace GameTranslator.App.Views;

/// <summary>
/// Bilgisayar ekranında oyunun tam üstünde şeffaf, tıklama geçiren (click-through)
/// ve odağı çalmayan canlı Türkçe altyazı katmanı (Desktop HUD Overlay).
/// MORT (Monkeyhead's OCR Realtime Translator) gibi çalışır:
/// OBS Elgato'dan temiz oyun görüntüsü kaydederken, oyuncu monitöründe canlı Türkçe altyazı okur.
/// 3 monitörlü yayıncı kurulumlarında istenen ekrana serbestçe sürüklenebilir.
/// </summary>
public sealed class DesktopSubtitleOverlay : Window
{
    private const int GWL_EXSTYLE = -20;
    private const int WS_EX_TRANSPARENT = 0x00000020;
    private const int WS_EX_LAYERED = 0x00080000;
    private const int WS_EX_NOACTIVATE = 0x08000000;
    private const int WS_EX_TOOLWINDOW = 0x00000080;
    private const uint WDA_EXCLUDEFROMCAPTURE = 0x00000011;

    [DllImport("user32.dll")]
    private static extern int GetWindowLong(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll")]
    private static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);

    [DllImport("user32.dll")]
    private static extern bool SetWindowDisplayAffinity(IntPtr hWnd, uint dwAffinity);

    private static DesktopSubtitleOverlay? _instance;
    private static bool _enabled = false; // Varsayılan olarak kapalı (kullanıcı isterse açar)
    private static Rect? _targetMonitorRect;
    private static bool _isPositioningMode;
    private static GameTranslator.Shell.PcBarSettings _bar = new();

    /// <summary>Barın görünümü (opaklık, genişlik, yükseklik, köşe, çerçeve); değişince açık altyazıya hemen uygulanır.</summary>
    public static GameTranslator.Shell.PcBarSettings Bar
    {
        get => _bar;
        set
        {
            _bar = (value ?? new()).Normalized();
            if (Application.Current == null) return;
            Application.Current.Dispatcher.BeginInvoke(() =>
            {
                if (_instance is not { } w) return;
                w.ApplyBar();
                // Konumlandırma kutusu açıkken de kaydırıcılar işler: görünüm hemen değişir, genişlik kutuyu yeniden boyutlar
                if (_isPositioningMode) w.ApplyBarWhilePositioning();
                else if (w.Visibility == Visibility.Visible) w.UpdatePosition(w._lastStyle, w._lastBox);
            });
        }
    }

    /// <summary>
    /// Kullanıcı konumlandırmayı bitirdi (çift tık): barın yeni yeri ve boyutu ayarlara yazılmalıdır. Arayüz iş
    /// parçacığında çağrılır.
    /// </summary>
    public static event Action<GameTranslator.Shell.PcBarSettings>? BarEdited;

    /// <summary>Konumlandırma kutusu açık mı.</summary>
    public static bool IsPositioning => _isPositioningMode;

    /// <summary>Konumlandırmayı bitirir ve kaydeder (çift tıkla aynı).</summary>
    public static void FinishPositioning()
    {
        if (Application.Current == null) return;
        Application.Current.Dispatcher.Invoke(() => _instance?.DisableDragMode());
    }

    /// <summary>Barın hangi kenarından ya da köşesinden tutulduğu.</summary>
    [Flags]
    public enum Grip { None = 0, Left = 1, Top = 2, Right = 4, Bottom = 8, Move = 16 }

    /// <summary>Farenin kutudaki yerine göre tutamaç: kenara <paramref name="edge"/> pikselden yakınsa o kenar(lar), değilse taşıma.</summary>
    public static Grip GripAt(Point point, Size size, double edge = 14)
    {
        // Küçük kutuda kenar şeritleri ortayı yutmasın: orta üçte bir her zaman taşıma kalır
        double ex = Math.Min(edge, size.Width / 3), ey = Math.Min(edge, size.Height / 3);
        var grip = Grip.None;
        if (point.X <= ex) grip |= Grip.Left; else if (point.X >= size.Width - ex) grip |= Grip.Right;
        if (point.Y <= ey) grip |= Grip.Top; else if (point.Y >= size.Height - ey) grip |= Grip.Bottom;
        return grip == Grip.None ? Grip.Move : grip;
    }

    /// <summary>
    /// Sürükleme sonucu yeni dikdörtgen: tutulan kenar fareyle gider, karşı kenar yerinde kalır; taşımada boyut değişmez.
    /// En küçük boyutun altına inilmez (kenar, karşı kenara <paramref name="min"/> kadar yaklaşınca durur).
    /// </summary>
    public static Rect Resize(Rect start, Grip grip, Vector delta, Size min)
    {
        if (grip.HasFlag(Grip.Move)) return new Rect(start.X + delta.X, start.Y + delta.Y, start.Width, start.Height);
        double left = start.Left, top = start.Top, right = start.Right, bottom = start.Bottom;
        if (grip.HasFlag(Grip.Left)) left = Math.Min(left + delta.X, right - min.Width);
        if (grip.HasFlag(Grip.Right)) right = Math.Max(right + delta.X, left + min.Width);
        if (grip.HasFlag(Grip.Top)) top = Math.Min(top + delta.Y, bottom - min.Height);
        if (grip.HasFlag(Grip.Bottom)) bottom = Math.Max(bottom + delta.Y, top + min.Height);
        return new Rect(left, top, right - left, bottom - top);
    }

    private static Cursor CursorFor(Grip grip) => grip switch
    {
        Grip.Left or Grip.Right => Cursors.SizeWE,
        Grip.Top or Grip.Bottom => Cursors.SizeNS,
        Grip.Left | Grip.Top or Grip.Right | Grip.Bottom => Cursors.SizeNWSE,
        Grip.Right | Grip.Top or Grip.Left | Grip.Bottom => Cursors.SizeNESW,
        _ => Cursors.SizeAll,
    };

    internal static Rect WorkArea() => _targetMonitorRect ?? new Rect(SystemParameters.WorkArea.Left, SystemParameters.WorkArea.Top,
        SystemParameters.WorkArea.Width, SystemParameters.WorkArea.Height);

    public static void SetTargetMonitor(int left, int top, int width, int height)
    {
        _targetMonitorRect = new Rect(left, top, width, height);
    }

    private readonly TextBlock _subtitleText;
    private readonly Border _container;
    private readonly DispatcherTimer _timer;
    private SubtitleStyle? _lastStyle;
    private GameTranslator.Core.TextBox? _lastBox;

    public static bool IsOverlayEnabled
    {
        get => _enabled;
        set
        {
            _enabled = value;
            if (!value) { Clear(); DesktopRegionOverlay.Clear(); }
        }
    }

    public static void Initialize()
    {
        if (Application.Current == null) return;
        Application.Current.Dispatcher.Invoke(() =>
        {
            if (_instance == null)
            {
                _instance = new DesktopSubtitleOverlay();
            }
        });
    }

    public static void Display(string text, int durationMs = 4500, SubtitleStyle? style = null, GameTranslator.Core.TextBox? box = null)
    {
        if (!_enabled || string.IsNullOrWhiteSpace(text)) return;

        if (Application.Current == null) return;
        Application.Current.Dispatcher.BeginInvoke(() =>
        {
            try
            {
                if (_instance == null)
                {
                    _instance = new DesktopSubtitleOverlay();
                }
                _instance.ShowText(text, durationMs, style, box);
            }
            catch { }
        });
    }

    public static void StartDragMode()
    {
        if (Application.Current == null) return;
        Application.Current.Dispatcher.Invoke(() =>
        {
            if (_instance == null)
            {
                _instance = new DesktopSubtitleOverlay();
            }
            _instance.EnableDragMode();
        });
    }

    /// <summary>Elle verilen yer ve boyutu siler; bar yeniden kendiliğinden yerleşir.</summary>
    public static void ResetPosition()
    {
        if (_bar.Box is null) return;
        var cleared = _bar with { Box = null };
        Bar = cleared;
        if (Application.Current == null) return;
        Application.Current.Dispatcher.Invoke(() => BarEdited?.Invoke(cleared));
    }

    public static void Clear()
    {
        if (Application.Current == null) return;
        Application.Current.Dispatcher.BeginInvoke(() =>
        {
            try
            {
                _instance?.HideText();
            }
            catch { }
        });
    }

    private DesktopSubtitleOverlay()
    {
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        Topmost = true;
        ShowInTaskbar = false;
        ShowActivated = false;
        Focusable = false;
        // Boyut UpdatePosition'da barın ölçüsünden verilir (bkz. oradaki not)
        SizeToContent = SizeToContent.Manual;
        Width = 1;
        Height = 1;

        _subtitleText = new TextBlock
        {
            Foreground = Brushes.White,
            FontSize = 28,
            FontWeight = FontWeights.Bold,
            TextAlignment = TextAlignment.Center,
            TextWrapping = TextWrapping.Wrap,
            VerticalAlignment = VerticalAlignment.Center,
            LineHeight = 36,
            Effect = new DropShadowEffect
            {
                Color = Colors.Black,
                Direction = 315,
                ShadowDepth = 2.5,
                BlurRadius = 8,
                Opacity = 0.98
            }
        };

        _container = new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(0xDC, 0x0A, 0x0E, 0x17)),
            CornerRadius = new CornerRadius(12),
            BorderBrush = new SolidColorBrush(Color.FromArgb(0x70, 0x38, 0x75, 0xE8)),
            BorderThickness = new Thickness(1.5),
            Padding = new Thickness(24, 12, 24, 14),
            Child = _subtitleText
        };

        Content = _container;
        ApplyBar();
        Visibility = Visibility.Collapsed;

        _timer = new DispatcherTimer();
        _timer.Tick += (_, _) =>
        {
            if (!_isPositioningMode) HideText();
        };

        MouseDown += OnWindowMouseDown;
        MouseMove += OnWindowMouseMove;
        MouseUp += OnWindowMouseUp;
        LostMouseCapture += (_, _) => _grip = Grip.None;

        SourceInitialized += (_, _) =>
        {
            // Pencerenin tutamacı ilk görünür olduğunda oluşur. Uygulama açıldıktan sonra hiç altyazı çıkmadan
            // "Altyazıyı Konumlandır"a basılırsa tutamaç tam konumlandırma başlarken oluşur; burada koşulsuz
            // "tıklamalar içinden geçsin" denirse kutu ne sürüklenebiliyor ne çift tıklanabiliyordu.
            ApplyClickThrough(!_isPositioningMode);
        };
    }

    private void ApplyClickThrough(bool clickThrough)
    {
        try
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            if (hwnd == IntPtr.Zero) return;

            // Ekran kaydından (OBS, BitBlt, Windows Graphics Capture) gizle - MORT gibi çalışır
            SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE);

            var style = GetWindowLong(hwnd, GWL_EXSTYLE);
            if (clickThrough)
            {
                SetWindowLong(hwnd, GWL_EXSTYLE, style | WS_EX_TRANSPARENT | WS_EX_LAYERED | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW);
            }
            else
            {
                SetWindowLong(hwnd, GWL_EXSTYLE, (style & ~WS_EX_TRANSPARENT) | WS_EX_LAYERED | WS_EX_TOOLWINDOW);
            }
        }
        catch { }
    }

    private static readonly Size MinBox = new(160, 44);
    private Grip _grip;
    private Point _gripStart;
    private Rect _gripRect;

    private void EnableDragMode()
    {
        if (_isPositioningMode) return;
        _isPositioningMode = true;
        _timer.Stop();
        ApplyClickThrough(false);

        _subtitleText.Foreground = new SolidColorBrush(Color.FromRgb(0x38, 0xBD, 0xF8));
        // Önceki altyazı sağdan sola bir dildeyse yön kutunun yazısına taşınmasın
        _subtitleText.FlowDirection = FlowDirection.LeftToRight;
        _subtitleText.Text = Localizer.Current["Drag an edge or a corner: size · drag the middle: position\nDouble-click to save"];
        ApplyBar();
        ApplyPositioningLook();

        // Kutu pencerenin tamamını doldurur; pencere boyutu fareyle verilir
        _container.Width = double.NaN;
        _container.MinWidth = 0;
        _container.MinHeight = 0;
        var work = WorkArea();
        if (_bar.Box is { } saved)
        {
            Width = Math.Max(MinBox.Width, saved.Width * work.Width);
            Height = Math.Max(MinBox.Height, saved.Height * work.Height);
            Left = work.Left + saved.Left * work.Width;
            Top = work.Top + saved.Top * work.Height;
        }
        else
        {
            // İlk kez: yazının sığacağı, rahat tutulur bir kutu; alt ortada (ya da kaydırıcıdaki genişlikte)
            Width = _bar.WidthPercent > 0 ? work.Width * _bar.WidthPercent / 100.0 : Math.Min(work.Width * 0.6, 900);
            Height = Math.Max(96, _bar.Padding * 2 + 70);
            Left = work.Left + (work.Width - Width) / 2;
            Top = work.Bottom - Height - Math.Max(40, work.Height * 0.07);
        }
        Visibility = Visibility.Visible;
        // Tutamaç şimdi oluştuysa stil yukarıdaki çağrıda uygulanamamıştı
        ApplyClickThrough(false);
    }

    /// <summary>Konumlandırma kutusunun belirgin çerçevesi (kullanıcının çerçeve ayarı ne olursa olsun görünür).</summary>
    private void ApplyPositioningLook()
    {
        _container.BorderBrush = new SolidColorBrush(Color.FromRgb(0x38, 0xBD, 0xF8));
        _container.BorderThickness = new Thickness(2.5);
        // Zemin tamamen saydamsa kutu fareyle tutulamaz: en az hafif bir zemin
        if (_container.Background is SolidColorBrush { Color.A: < 40 } faint)
            _container.Background = new SolidColorBrush(Color.FromArgb(40, faint.Color.R, faint.Color.G, faint.Color.B));
    }

    /// <summary>Konumlandırma açıkken kaydırıcı oynadı: görünüm zaten uygulandı; genişlik kaydırıcısı kutuyu ortasından yeniden boyutlar.</summary>
    private void ApplyBarWhilePositioning()
    {
        ApplyPositioningLook();
        if (_bar.WidthPercent <= 0 || _grip != Grip.None) return;
        var work = WorkArea();
        var width = Math.Max(MinBox.Width, work.Width * _bar.WidthPercent / 100.0);
        if (Math.Abs(width - Width) < 1) return;
        Left += (Width - width) / 2;
        Width = width;
    }

    private void DisableDragMode()
    {
        if (!_isPositioningMode) return;
        _isPositioningMode = false;
        _grip = Grip.None;
        if (IsMouseCaptured) ReleaseMouseCapture();

        // Kutunun yeri ve boyutu, ekranın çalışma alanına oranla ayarlara yazılır
        var work = WorkArea();
        var box = new GameTranslator.Shell.PcBarBox((Left - work.Left) / work.Width, (Top - work.Top) / work.Height, Width / work.Width, Height / work.Height).Normalized();
        _bar = (_bar with { Box = box }).Normalized();

        Cursor = Cursors.Arrow;
        ApplyBar();
        _subtitleText.Foreground = Brushes.White;

        ApplyClickThrough(true);
        HideText();
        BarEdited?.Invoke(_bar);
    }

    private void OnWindowMouseDown(object sender, MouseButtonEventArgs e)
    {
        if (!_isPositioningMode || e.ChangedButton != MouseButton.Left) return;

        if (e.ClickCount == 2)
        {
            DisableDragMode();
            return;
        }

        var point = e.GetPosition(this);
        _grip = GripAt(point, new Size(ActualWidth, ActualHeight));
        // Pencere sürüklerken yer değiştirdiği için fare konumu ekran koordinatında tutulur
        _gripStart = new Point(Left + point.X, Top + point.Y);
        _gripRect = new Rect(Left, Top, Width, Height);
        CaptureMouse();
    }

    private void OnWindowMouseMove(object sender, MouseEventArgs e)
    {
        if (!_isPositioningMode) return;
        var point = e.GetPosition(this);
        if (_grip == Grip.None)
        {
            Cursor = CursorFor(GripAt(point, new Size(ActualWidth, ActualHeight)));
            return;
        }
        if (e.LeftButton != MouseButtonState.Pressed)
        {
            _grip = Grip.None;
            ReleaseMouseCapture();
            return;
        }
        var now = new Point(Left + point.X, Top + point.Y);
        var rect = Resize(_gripRect, _grip, now - _gripStart, MinBox);
        Left = rect.Left;
        Top = rect.Top;
        Width = rect.Width;
        Height = rect.Height;
    }

    private void OnWindowMouseUp(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton != MouseButton.Left || _grip == Grip.None) return;
        _grip = Grip.None;
        ReleaseMouseCapture();
    }

    /// <summary>Konuşmacı başına renk (aynı ad hep aynı renkte).</summary>
    private readonly SpeakerColors _speakers = new();

    private void ShowText(string text, int durationMs, SubtitleStyle? style, GameTranslator.Core.TextBox? box)
    {
        if (_isPositioningMode) return;

        _lastStyle = style;
        _lastBox = box;
        ApplyStyle(style);
        ApplyBar();
        // Arapça, Farsça, İbranice sağdan sola okunur: noktalama ve satır kırma o yöne göre dizilir (ortalama aynı kalır)
        _subtitleText.FlowDirection = Languages.IsRtlText(text) ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
        _lastText = text;
        Services.SpeakerText.Show(_subtitleText, text, style, _speakers);

        UpdatePosition(style, box);

        if (Visibility != Visibility.Visible)
        {
            Visibility = Visibility.Visible;
            // Gizliyken yapılan ölçüm eski kalabiliyor; görünür olunca bir kez daha ölçülüp yerleştirilir
            UpdatePosition(style, box);
        }

        _timer.Stop();
        _timer.Interval = TimeSpan.FromMilliseconds(Math.Max(2500, durationMs));
        _timer.Start();
    }

    private void ApplyStyle(SubtitleStyle? style)
    {
        if (style == null) return;

        // Yazı rengi
        var color = style.TextColor == SubtitleTextColor.Yellow ? Color.FromRgb(0xFF, 0xE6, 0x00) : Colors.White;
        _subtitleText.Foreground = new SolidColorBrush(color);

        // Yazı boyutu: 46px standart TV fontunu PC monitörü için ~28px'e oranlar
        double targetSize = Math.Clamp(style.FontSize * 0.62, 20, 52) * _bar.TextSize / 100.0;
        _subtitleText.FontSize = targetSize;
        _subtitleText.LineHeight = targetSize * 1.3;
        _subtitleText.FontWeight = style.Bold ? FontWeights.Bold : FontWeights.SemiBold;
        // Seçilen font kuruluysa o, değilse standart yazı
        if (Services.InstalledFonts.Family(style.Font) is { } family) _subtitleText.FontFamily = family;
        else _subtitleText.ClearValue(TextBlock.FontFamilyProperty);
    }

    /// <summary>Barın opaklığı, yüksekliği, köşesi ve çerçevesi; genişlik <see cref="UpdatePosition"/> içinde verilir.</summary>
    private void ApplyBar()
    {
        var bar = _bar;
        byte alpha = (byte)Math.Round(bar.Opacity * 255 / 100.0);
        var (red, green, blue) = bar.BarRgb;
        _container.Background = new SolidColorBrush(Color.FromArgb(alpha, red, green, blue));
        _subtitleText.Effect = TextEdgeEffect(bar.Edge);
        // Yazı boyutu ayarı açık altyazıya da hemen yansısın
        if (_lastStyle is not null) ApplyStyle(_lastStyle);
        _container.CornerRadius = new CornerRadius(bar.Corner);
        _container.Padding = new Thickness(24, bar.Padding, 24, bar.Padding + 2);
        _container.BorderBrush = new SolidColorBrush(Color.FromArgb(0x70, 0x38, 0x75, 0xE8));
        _container.BorderThickness = new Thickness(bar.Border ? 1.5 : 0);
    }

    /// <summary>
    /// Yazının kenarı: gölge (sağ alta düşen yumuşak gölge), kontur (yazının çevresinde her yönde koyu hale; barsız
    /// görünümde açık zeminde de okunur) ya da hiçbiri.
    /// </summary>
    internal static System.Windows.Media.Effects.Effect? TextEdgeEffect(GameTranslator.Shell.PcTextEdge edge) => edge switch
    {
        GameTranslator.Shell.PcTextEdge.Outline => new DropShadowEffect { Color = Colors.Black, ShadowDepth = 0, BlurRadius = 5, Opacity = 1 },
        GameTranslator.Shell.PcTextEdge.None => null,
        _ => new DropShadowEffect { Color = Colors.Black, Direction = 315, ShadowDepth = 2.5, BlurRadius = 8, Opacity = 0.98 },
    };

    private void HideText()
    {
        _timer.Stop();
        Visibility = Visibility.Collapsed;
    }

    /// <summary>
    /// Örtme yerleşimi (tv/webos/app.js fitToSource ile aynı kural): bar oyunun altyazısının kutusuna (biraz payla, en az
    /// ekranın %16'sı genişlikte) konur, yazı oraya sığan en büyük boyda yazılır (oyunun altyazısından büyük değil); en
    /// küçük boyda da taşarsa bar dikeyde büyür. Bar ayarları burada da geçerlidir (1.8.41'den beri yok sayılıyordu;
    /// barsız, yalnız yazı kullananlar şikâyet etti): zeminin rengi ve opaklığı, elle verilen genişlik, yükseklik
    /// (üst-alt boşluk) ve yazı boyutu.
    /// </summary>
    private void FitToSource(Rect work, GameTranslator.Core.TextBox source, SubtitleStyle style)
    {
        const double padX = 0.008, padY = 0.006, minWidth = 0.16;
        double left = Math.Max(0, source.Left - padX), right = Math.Min(1, source.Right + padX);
        if (_bar.WidthPercent > 0)
        {
            // Genişlik elle verildiyse bar o genişlikte, oyunun altyazısının ortasında.
            var center = (source.Left + source.Right) / 2;
            var half = Math.Min(1, _bar.WidthPercent / 100.0) / 2;
            left = Math.Max(0, center - half);
            right = Math.Min(1, center + half);
            if (right - left < half * 2) { if (left <= 0) right = Math.Min(1, half * 2); else left = Math.Max(0, 1 - half * 2); }
        }
        double top = Math.Max(0, source.Top - padY), bottom = Math.Min(1, source.Bottom + padY);
        if (right - left < minWidth)
        {
            var center = (left + right) / 2;
            left = Math.Max(0, center - minWidth / 2);
            right = Math.Min(1, center + minWidth / 2);
        }
        double areaWidth = (right - left) * work.Width, areaHeight = (bottom - top) * work.Height;
        _container.Width = areaWidth;
        _container.MinWidth = 0;
        _container.MinHeight = areaHeight;
        // Bar yüksekliği: varsayılan boşlukta (12) bar oyunun satırı kadardır; artırılırsa üstte ve altta pay kalır.
        var extra = Math.Max(0, _bar.Padding - 12);
        _container.Padding = new Thickness(6 + extra / 2.0, 1 + extra, 6 + extra / 2.0, 1 + extra);
        var (red, green, blue) = _bar.BarRgb;
        _container.Background = new SolidColorBrush(Color.FromArgb((byte)Math.Round(_bar.Opacity * 255 / 100.0), red, green, blue));
        _subtitleText.LineHeight = double.NaN;
        areaHeight += extra * 2;
        _container.MinHeight = areaHeight;

        var scale = _bar.TextSize / 100.0;
        var baseSize = Math.Clamp(style.FontSize * 0.62, 20, 52) * scale;
        // Boylar tam sayıdır: ondalıklı sınırlarda aşağı yuvarlanan orta nokta hep aynı kalıyor, arama hiç bitmiyordu
        // (1.8.41'de bilgisayardaki altyazı penceresi donup belleği dolduruyordu).
        var low = (int)Math.Max(14, Math.Round(baseSize * 0.55));
        // Tek satırlık altyazıda en büyük boy satırın boyudur; birden çok satırsa ayardaki boyun biraz üstü.
        // Yazı boyutu ayarı tek satırda da geçerli: %100'ün üstü yazıyı oyunun satırından büyütür (bar da büyür).
        var lineHeight = areaHeight - extra * 2;
        var high = (int)Math.Round(lineHeight < baseSize * 2.2 ? lineHeight * 0.82 * scale : baseSize * 1.15);
        high = Math.Max(low, Math.Min((int)Math.Round(baseSize * 1.6), high));
        var fitHeight = areaHeight + Math.Max(0, (scale - 1) * lineHeight);
        bool Fits(double size)
        {
            _subtitleText.FontSize = size;
            Services.SpeakerText.Show(_subtitleText, _lastText, style, _speakers);
            _subtitleText.InvalidateMeasure();
            _container.InvalidateMeasure();
            _container.Measure(new Size(areaWidth, double.PositiveInfinity));
            return _container.DesiredSize.Height <= fitHeight + 1;
        }
        int best = low;
        if (Fits(high)) best = high;
        else if (Fits(low))
        {
            int a = low, b = high;
            // a sığar, b sığmaz; her turda aralık daralır (en çok ~6 tur).
            while (b - a > 1)
            {
                var mid = a + (b - a) / 2;
                if (Fits(mid)) a = mid; else b = mid;
            }
            best = a;
        }
        Fits(best);
        var height = Math.Max(areaHeight, Math.Ceiling(_container.DesiredSize.Height));
        Width = areaWidth;
        Height = height;
        Left = work.Left + left * work.Width;
        // Bar büyüdüyse oyunun altyazısının ortasından yukarı ve aşağı eşit büyür.
        Top = Math.Clamp(work.Top + top * work.Height - (height - lineHeight) / 2, work.Top, Math.Max(work.Top, work.Bottom - height));
    }

    private string _lastText = "";

    private void UpdatePosition(SubtitleStyle? style = null, GameTranslator.Core.TextBox? box = null)
    {
        // Konumlandırma kutusu açıkken boyut ve yer fareyle verilir
        if (_isPositioningMode) return;
        Rect work = WorkArea();

        // "Üstünü örtsün": bar TV'deki gibi her satırda oyunun altyazısının yerine ve boyutuna oturur. Kullanıcı barı
        // "Altyazıyı Konumlandır" ile elle çizdiyse o kutu geçerlidir (1.8.41–1.8.44 yok sayıyordu, kullanıcılar
        // kendi yerini ve boyutunu kaybetti); kendiliğinden yerleşime "Sıfırla" ile dönülür.
        if (style?.Placement == SubtitlePlacement.Cover && box is { } source && _bar.Box is null)
        {
            FitToSource(work, source, style);
            return;
        }
        _container.Padding = new Thickness(24, _bar.Padding, 24, _bar.Padding + 2);

        // Kullanıcı kutuyu fareyle çizdiyse bar her satırda tam orada ve o boyutta çıkar
        if (_bar.Box is { } manual)
        {
            double boxWidth = Math.Max(MinBox.Width, manual.Width * work.Width), boxHeight = Math.Max(MinBox.Height, manual.Height * work.Height);
            _container.Width = boxWidth;
            _container.MinWidth = 0;
            _container.MinHeight = boxHeight;
            _subtitleText.InvalidateMeasure();
            _container.InvalidateMeasure();
            _container.Measure(new Size(boxWidth, double.PositiveInfinity));
            // Yazı kutuya sığmıyorsa (çok satır) bar kutunun ortasından yukarı ve aşağı eşit büyür
            var neededHeight = Math.Max(boxHeight, Math.Ceiling(_container.DesiredSize.Height));
            Width = boxWidth;
            Height = neededHeight;
            Left = work.Left + manual.Left * work.Width;
            Top = work.Top + manual.Top * work.Height - (neededHeight - boxHeight) / 2;
            return;
        }
        _container.MinHeight = 0;

        // Genişlik elle verildiyse bar o kadar olur; yoksa yazıya (örtmede İngilizce satıra) göre
        bool fixedWidth = _bar.WidthPercent > 0;
        double maxWidth = fixedWidth ? work.Width : Math.Max(1200, work.Width * 0.85);
        _container.Width = fixedWidth ? Math.Min(work.Width, work.Width * _bar.WidthPercent / 100.0) : double.NaN;
        // Örtmede bar en az İngilizce satır kadar geniştir (genişlik elle verilmediyse)
        bool cover = !fixedWidth && box.HasValue && style?.Placement == SubtitlePlacement.Cover;
        _container.MinWidth = cover ? Math.Min(maxWidth, Math.Max(420, (box!.Value.Right - box.Value.Left) * work.Width + 40)) : 0;

        // Pencere boyutu barın ölçüsünden ELLE verilir. İçeriğe göre kendiliğinden boyutlanma (SizeToContent) gizliyken
        // değişen içerikte pencereyi eski genişliğinde bırakıyordu; bar pencereden geniş kalınca iki yandan kesiliyordu.
        _subtitleText.InvalidateMeasure();
        _container.InvalidateMeasure();
        _container.Measure(new Size(maxWidth, double.PositiveInfinity));
        var desiredWidth = Math.Ceiling(_container.DesiredSize.Width);
        var desiredHeight = Math.Ceiling(_container.DesiredSize.Height);
        Width = Math.Max(1, desiredWidth);
        Height = Math.Max(1, desiredHeight);

        if (box.HasValue)
        {
            var b = box.Value;
            // Kullanıcının seçtiği okuma alanının (Oyun Okuma Alanı) yatay merkezine tam otur
            double targetCenterX = work.Left + (((b.Left + b.Right) / 2.0) * work.Width);
            Left = Math.Clamp(targetCenterX - (desiredWidth / 2.0), work.Left, Math.Max(work.Left, work.Right - desiredWidth));

            if (style?.Placement == SubtitlePlacement.Cover)
            {
                // Orijinal altyazının tam üstüne oturup onu tamamen kapatır (MORT gibi)
                double originalCenterY = work.Top + (((b.Top + b.Bottom) / 2.0) * work.Height);
                Top = Math.Clamp(originalCenterY - (desiredHeight / 2.0), work.Top, Math.Max(work.Top, work.Bottom - desiredHeight));
                return;
            }
            else if (style?.Placement == SubtitlePlacement.Above)
            {
                double originalTopY = work.Top + (b.Top * work.Height);
                Top = Math.Clamp(originalTopY - desiredHeight - 10, work.Top, Math.Max(work.Top, work.Bottom - desiredHeight));
                return;
            }
            else if (style?.Placement == SubtitlePlacement.Below)
            {
                double originalBottomY = work.Top + (b.Bottom * work.Height);
                Top = Math.Clamp(originalBottomY + 10, work.Top, Math.Max(work.Top, work.Bottom - desiredHeight));
                return;
            }
        }

        // Fixed modunda veya kutu yoksa standart alt ortalama
        Left = work.Left + ((work.Width - desiredWidth) / 2);
        int bottomPercent = style?.BottomPercent ?? 7;
        double marginFromBottom = Math.Max(40, work.Height * (bottomPercent / 100.0));
        Top = work.Bottom - desiredHeight - marginFromBottom;
    }
}
