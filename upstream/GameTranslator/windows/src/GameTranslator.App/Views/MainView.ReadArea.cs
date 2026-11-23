// SPDX-License-Identifier: AGPL-3.0-only

using System.Windows;
using System.Windows.Input;

namespace GameTranslator.App.Views;

/// <summary>
/// Okuma alanı kutusu canlı görüntünün üstünde fareyle düzenlenir: köşelerden ve kenarlardan çekilerek boyutlandırılır,
/// ortasından tutulup taşınır, boş yere sürüklenerek yeniden çizilir. Kaydırıcılar kutuyla birlikte hareket eder;
/// ayar fare bırakılınca bir kez kaydedilir (sürüklerken her karede diske yazılmaz).
/// </summary>
public partial class MainView
{
    /// <summary>Kutunun kenarına bu kadar piksel yakınlık "kenardan tut" sayılır.</summary>
    private const double GripPixels = 9;
    /// <summary>Kutu en az bu kadar (görüntünün oranı) geniş ve yüksek kalır.</summary>
    private const double MinBoxSize = 0.04;

    [Flags]
    private enum BoxEdge { None = 0, Left = 1, Right = 2, Top = 4, Bottom = 8, Move = 16, Draw = 32 }

    private BoxEdge boxDrag;
    private Point dragStart;
    private (double Top, double Bottom, double Left, double Right) dragFrom;

    private (double Top, double Bottom, double Left, double Right) CurrentBox() =>
        (CropTopSlider.Value, CropBottomSlider.Value, CropLeftSlider.Value, CropRightSlider.Value);

    /// <summary>
    /// Görüntünün tuvalde gerçekten çizildiği alan. Görüntü oranı korunarak sığdırılır (Uniform): geniş önizlemede iki
    /// yanda, dar önizlemede üstte ve altta boşluk kalır. Kutunun oranları bu alana göredir; eskiden tuvalin tamamına
    /// göreydi ve görüntüdeki altyazının üstüne çizilen kutu gerçek karede daha dar bir yere düşüyordu (satırların iki ucu
    /// kesik okunuyordu, 2026-11-18 GTA III yayını). Görüntü yokken tuvalin tamamı.
    /// </summary>
    internal static Rect PictureArea(double canvasWidth, double canvasHeight, double pictureWidth, double pictureHeight)
    {
        if (pictureWidth <= 0 || pictureHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) return new Rect(0, 0, canvasWidth, canvasHeight);
        var scale = Math.Min(canvasWidth / pictureWidth, canvasHeight / pictureHeight);
        var (width, height) = (pictureWidth * scale, pictureHeight * scale);
        return new Rect((canvasWidth - width) / 2, (canvasHeight - height) / 2, width, height);
    }

    private Rect PictureArea()
    {
        var source = LiveStreamImage?.Source;
        return PictureArea(OcrCanvas.ActualWidth, OcrCanvas.ActualHeight, source?.Width ?? 0, source?.Height ?? 0);
    }

    /// <summary>Farenin olduğu yer kutunun neresi (köşe, kenar, iç, dış).</summary>
    private BoxEdge HitBox(Point p)
    {
        var area = PictureArea();
        double w = area.Width, h = area.Height;
        if (w <= 10 || h <= 10) return BoxEdge.None;
        p = new Point(p.X - area.X, p.Y - area.Y);
        var (top, bottom, left, right) = CurrentBox();
        double x0 = left * w, x1 = right * w, y0 = top * h, y1 = bottom * h;
        var inX = p.X >= x0 - GripPixels && p.X <= x1 + GripPixels;
        var inY = p.Y >= y0 - GripPixels && p.Y <= y1 + GripPixels;
        if (!inX || !inY) return BoxEdge.Draw;
        var edge = BoxEdge.None;
        if (Math.Abs(p.X - x0) <= GripPixels) edge |= BoxEdge.Left;
        else if (Math.Abs(p.X - x1) <= GripPixels) edge |= BoxEdge.Right;
        if (Math.Abs(p.Y - y0) <= GripPixels) edge |= BoxEdge.Top;
        else if (Math.Abs(p.Y - y1) <= GripPixels) edge |= BoxEdge.Bottom;
        return edge == BoxEdge.None ? BoxEdge.Move : edge;
    }

    private static Cursor CursorFor(BoxEdge edge) => edge switch
    {
        BoxEdge.Left | BoxEdge.Top or BoxEdge.Right | BoxEdge.Bottom => Cursors.SizeNWSE,
        BoxEdge.Right | BoxEdge.Top or BoxEdge.Left | BoxEdge.Bottom => Cursors.SizeNESW,
        BoxEdge.Left or BoxEdge.Right => Cursors.SizeWE,
        BoxEdge.Top or BoxEdge.Bottom => Cursors.SizeNS,
        BoxEdge.Move => Cursors.SizeAll,
        _ => Cursors.Cross,
    };

    private void OnOcrCanvasMouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton != MouseButton.Left) return;
        dragStart = e.GetPosition(OcrCanvas);
        boxDrag = HitBox(dragStart);
        if (boxDrag == BoxEdge.None) return;
        dragFrom = CurrentBox();
        OcrCanvas.CaptureMouse();
        e.Handled = true;
    }

    private void OnOcrCanvasMouseMove(object sender, MouseEventArgs e)
    {
        var p = e.GetPosition(OcrCanvas);
        if (boxDrag == BoxEdge.None)
        {
            OcrCanvas.Cursor = CursorFor(HitBox(p));
            return;
        }
        var area = PictureArea();
        double w = area.Width, h = area.Height;
        if (w <= 10 || h <= 10) return;
        var dx = (p.X - dragStart.X) / w;
        var dy = (p.Y - dragStart.Y) / h;
        var (top, bottom, left, right) = dragFrom;

        if (boxDrag == BoxEdge.Draw)
        {
            // Boş yere sürükleme: başlangıç ile fare arası yeni kutu (görüntünün dışındaki boşluk görüntünün kenarı sayılır)
            double ax = Math.Clamp((dragStart.X - area.X) / w, 0, 1), ay = Math.Clamp((dragStart.Y - area.Y) / h, 0, 1);
            double bx = Math.Clamp((p.X - area.X) / w, 0, 1), by = Math.Clamp((p.Y - area.Y) / h, 0, 1);
            (left, right) = (Math.Min(ax, bx), Math.Max(ax, bx));
            (top, bottom) = (Math.Min(ay, by), Math.Max(ay, by));
            if (right - left < MinBoxSize) right = Math.Min(1, left + MinBoxSize);
            if (bottom - top < MinBoxSize) bottom = Math.Min(1, top + MinBoxSize);
        }
        else if (boxDrag == BoxEdge.Move)
        {
            // Taşıma: boyut aynı kalır, kutu görüntünün dışına çıkmaz
            var bw = right - left;
            var bh = bottom - top;
            left = Math.Clamp(left + dx, 0, 1 - bw);
            top = Math.Clamp(top + dy, 0, 1 - bh);
            right = left + bw;
            bottom = top + bh;
        }
        else
        {
            if (boxDrag.HasFlag(BoxEdge.Left)) left = Math.Clamp(left + dx, 0, right - MinBoxSize);
            if (boxDrag.HasFlag(BoxEdge.Right)) right = Math.Clamp(right + dx, left + MinBoxSize, 1);
            if (boxDrag.HasFlag(BoxEdge.Top)) top = Math.Clamp(top + dy, 0, bottom - MinBoxSize);
            if (boxDrag.HasFlag(BoxEdge.Bottom)) bottom = Math.Clamp(bottom + dy, top + MinBoxSize, 1);
        }

        // Kaydırıcılar kutuyu izler; sürüklerken ayar kaydedilmez (loading), yalnız kutu çizilir
        var was = loading;
        loading = true;
        CropTopSlider.Value = top;
        CropBottomSlider.Value = bottom;
        CropLeftSlider.Value = left;
        CropRightSlider.Value = right;
        loading = was;
        UpdateOcrBox();
        e.Handled = true;
    }

    private void OnOcrCanvasMouseUp(object sender, MouseButtonEventArgs e)
    {
        if (boxDrag == BoxEdge.None) return;
        boxDrag = BoxEdge.None;
        OcrCanvas.ReleaseMouseCapture();
        // Bırakınca bir kez: elle seçilen alan olarak kaydedilir (otomatik algılama kapanır)
        OnCropSliderChanged(this, new RoutedPropertyChangedEventArgs<double>(0, 0));
        e.Handled = true;
    }

    private void OnOcrCanvasLostCapture(object sender, MouseEventArgs e)
    {
        if (boxDrag == BoxEdge.None) return;
        boxDrag = BoxEdge.None;
        OnCropSliderChanged(this, new RoutedPropertyChangedEventArgs<double>(0, 0));
    }
}
