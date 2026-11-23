// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Karenin bir bölgesini kırpıp verir (yoksa nil); ek alanların okuyucusu her turda alanını böyle alır.
public protocol RegionFrame {
    /// `region`: karenin kenarlarına oranla (0–1).
    func area(_ region: TextBox) -> PixelImage?
}

/// Platformdan bağımsız ARGB görüntü (piksel başına bir UInt32: 0xAARRGGBB; Android'deki Bitmap.getPixels ile aynı
/// biçim). Windows'taki Frame'in, Android'deki PixelImage'ın karşılığı; kumanda simgesi ve renkli sözcük tanıma bunun
/// üstünde çalışır. iOS'ta CGImage / CVPixelBuffer baytları `init(width:height:rgba:)` ya da `init(width:height:bgra:)`
/// ile aktarılır.
public final class PixelImage: RegionFrame, @unchecked Sendable {
    public let width: Int
    public let height: Int
    public var pixels: [UInt32]

    public init(width: Int, height: Int, pixels: [UInt32]? = nil) {
        precondition(width >= 0 && height >= 0, "boyut eksi olamaz")
        self.width = width
        self.height = height
        let pixels = pixels ?? [UInt32](repeating: 0, count: width * height)
        precondition(pixels.count >= width * height, "piksel dizisi küçük")
        self.pixels = pixels
    }

    /// Sıralı R, G, B, A baytları (CGContext ile çizilmiş görüntü). `bytesPerRow` verilmezse `width * 4`.
    public convenience init(width: Int, height: Int, rgba: [UInt8], bytesPerRow: Int? = nil) {
        self.init(width: width, height: height, bytes: rgba, bytesPerRow: bytesPerRow, order: (0, 1, 2, 3))
    }

    /// Sıralı B, G, R, A baytları (CVPixelBuffer kCVPixelFormatType_32BGRA, Windows'taki Frame).
    public convenience init(width: Int, height: Int, bgra: [UInt8], bytesPerRow: Int? = nil) {
        self.init(width: width, height: height, bytes: bgra, bytesPerRow: bytesPerRow, order: (2, 1, 0, 3))
    }

    private convenience init(width: Int, height: Int, bytes: [UInt8], bytesPerRow: Int?, order: (Int, Int, Int, Int)) {
        let stride = bytesPerRow ?? width * 4
        precondition(bytes.count >= stride * max(0, height - 1) + width * 4, "bayt dizisi küçük")
        var pixels = [UInt32](repeating: 0, count: width * height)
        for y in 0..<height {
            for x in 0..<width {
                let p = y * stride + x * 4
                let r = UInt32(bytes[p + order.0]), g = UInt32(bytes[p + order.1]), b = UInt32(bytes[p + order.2]), a = UInt32(bytes[p + order.3])
                pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b
            }
        }
        self.init(width: width, height: height, pixels: pixels)
    }

    @inline(__always) public func red(_ x: Int, _ y: Int) -> Int { Int((pixels[y * width + x] >> 16) & 0xFF) }
    @inline(__always) public func green(_ x: Int, _ y: Int) -> Int { Int((pixels[y * width + x] >> 8) & 0xFF) }
    @inline(__always) public func blue(_ x: Int, _ y: Int) -> Int { Int(pixels[y * width + x] & 0xFF) }

    public func set(_ x: Int, _ y: Int, _ r: Int, _ g: Int, _ b: Int) {
        pixels[y * width + x] = PixelImage.opaque(r, g, b)
    }

    /// Opak ARGB değeri.
    public static func opaque(_ r: Int, _ g: Int, _ b: Int) -> UInt32 {
        0xFF00_0000 | (UInt32(r & 0xFF) << 16) | (UInt32(g & 0xFF) << 8) | UInt32(b & 0xFF)
    }

    /// Bütün pikselleri tek renge boyar (Kotlin: pixels.fill).
    public func fill(_ argb: UInt32) {
        for i in pixels.indices { pixels[i] = argb }
    }

    /// Parlaklık (0–1; Windows'taki ağırlıklar).
    @inline(__always) public func luma(_ x: Int, _ y: Int) -> Double {
        (0.114 * Double(blue(x, y)) + 0.587 * Double(green(x, y)) + 0.299 * Double(red(x, y))) / 255
    }

    /// Kesit; kenarlar görüntünün içine kırpılır.
    public func crop(_ x: Int, _ y: Int, _ w: Int, _ h: Int) -> PixelImage {
        let cx = min(max(x, 0), width), cy = min(max(y, 0), height)
        let cw = min(max(w, 0), width - cx), ch = min(max(h, 0), height - cy)
        var result = [UInt32](repeating: 0, count: cw * ch)
        for row in 0..<ch {
            let source = (cy + row) * width + cx
            for i in 0..<cw { result[row * cw + i] = pixels[source + i] }
        }
        return PixelImage(width: cw, height: ch, pixels: result)
    }

    /// Kenarları dışa yuvarlanmış bölge (Windows: RegionCropper.Crop).
    public func area(_ region: TextBox) -> PixelImage? {
        let left = Int(floor(Double(width) * region.left))
        let top = Int(floor(Double(height) * region.top))
        let right = Int(ceil(Double(width) * region.right))
        let bottom = Int(ceil(Double(height) * region.bottom))
        return crop(left, top, right - left, bottom - top)
    }

    /// İki doğrusal (bilinear) büyütme (Windows: Frame.Scaled).
    public func scaled(_ factor: Int) -> PixelImage {
        let w = width * factor, h = height * factor
        var out = [UInt32](repeating: 0, count: w * h)
        pixels.withUnsafeBufferPointer { source in
            for ty in 0..<h {
                var sy = min((Double(ty) + 0.5) / Double(factor) - 0.5, Double(height) - 1)
                if sy < 0 { sy = 0 }
                let y0 = Int(sy), y1 = min(y0 + 1, height - 1)
                let fy = sy - Double(y0)
                for tx in 0..<w {
                    var sx = min((Double(tx) + 0.5) / Double(factor) - 0.5, Double(width) - 1)
                    if sx < 0 { sx = 0 }
                    let x0 = Int(sx), x1 = min(x0 + 1, width - 1)
                    let fx = sx - Double(x0)
                    var value: UInt32 = 0
                    for shift: UInt32 in [24, 16, 8, 0] {
                        func p(_ x: Int, _ y: Int) -> Double { Double((source[y * width + x] >> shift) & 0xFF) }
                        let top = p(x0, y0) + (p(x1, y0) - p(x0, y0)) * fx
                        let bottom = p(x0, y1) + (p(x1, y1) - p(x0, y1)) * fx
                        let v = Int((top + (bottom - top) * fy).rounded(.toNearestOrEven))
                        value |= (UInt32(v & 0xFF)) << shift
                    }
                    out[ty * w + tx] = value
                }
            }
        }
        return PixelImage(width: w, height: h, pixels: out)
    }

    /// Bölge izinin boyutu (Windows: ReadScheduler.SignatureWidth/Height).
    public static let signatureWidth = 64
    public static let signatureHeight = 12
    /// İzdeki bir örnek bu kadar değişirse bölge değişmiş sayılır (Windows: ReadScheduler.ChangeThreshold).
    public static let changeThreshold = 16

    /// Bölgenin küçültülmüş gri kopyası: iki kareyi yazı tanımaya gerek kalmadan karşılaştırmaya yeter.
    public static func signature(_ image: PixelImage) -> [UInt8] {
        var samples = [UInt8](repeating: 0, count: signatureWidth * signatureHeight)
        if image.width == 0 || image.height == 0 { return samples }
        for y in 0..<signatureHeight {
            let sy = y * image.height / signatureHeight
            for x in 0..<signatureWidth {
                let sx = x * image.width / signatureWidth
                samples[y * signatureWidth + x] = UInt8((image.blue(sx, sy) + image.green(sx, sy) * 2 + image.red(sx, sy)) / 4)
            }
        }
        return samples
    }

    /// İki iz farklı mı: bir örnek `threshold`'dan çok değiştiyse.
    public static func changed(_ a: [UInt8], _ b: [UInt8], threshold: Int = changeThreshold) -> Bool {
        if a.count != b.count { return true }
        for i in a.indices where abs(Int(a[i]) - Int(b[i])) > threshold { return true }
        return false
    }
}
