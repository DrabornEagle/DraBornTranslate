// SPDX-License-Identifier: AGPL-3.0-only

import Accelerate
import CoreGraphics
import CoreVideo
import Foundation
import WebRTC

/// WebRTC'nin verdiği kareyi PS5 karesiyle aynı biçime (renkli CGImage) çevirir (Android: xbox/XboxFrames.kt). Yazı
/// tanıma ve önizleme aynı görüntüyü kullanır.
///
/// iPhone'da H.264'ü VideoToolbox çözer ve kare CVPixelBuffer (NV12: Y düzlemi + iç içe CbCr) olarak gelir; bu durumda
/// düzlemler kopyalanmadan doğrudan çevrilir. Yazılım çözücülerinin karesi (VP8/VP9) I420'dir. Çeviri Accelerate
/// (vImage) ile yapılır; tek sayılı ölçülerde (vImage 4:2:0 çift ölçü ister) Android'deki tamsayı hesabına düşülür.
/// Renk dönüşümü BT.601'dir (Android ve WebRTC'nin kendi NV12 görüntüleyicisi gibi).
enum XboxFrames {
    /// Çıktı: 8 bit BGRA (bellekte B, G, R, A; CoreGraphics'in yerli düzeni), alfa 255.
    static let bitmapInfo = CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue)

    /// Herhangi bir WebRTC karesi; çevrilemezse nil.
    static func image(from frame: RTCVideoFrame) -> CGImage? {
        if let native = frame.buffer as? RTCCVPixelBuffer {
            return image(pixelBuffer: native.pixelBuffer, cropX: Int(native.cropX), cropY: Int(native.cropY),
                         cropWidth: Int(native.cropWidth), cropHeight: Int(native.cropHeight))
        }
        let i420 = frame.buffer.toI420()
        return imageFromI420(y: i420.dataY, u: i420.dataU, v: i420.dataV, strideY: Int(i420.strideY), strideU: Int(i420.strideU),
                             strideV: Int(i420.strideV), width: Int(i420.width), height: Int(i420.height))
    }

    // MARK: I420

    /// I420 (Y + yarı çözünürlükte U ve V), BT.601 sınırlı aralık (16–235).
    static func imageFromI420(y: UnsafePointer<UInt8>, u: UnsafePointer<UInt8>, v: UnsafePointer<UInt8>,
                              strideY: Int, strideU: Int, strideV: Int, width: Int, height: Int) -> CGImage? {
        guard width > 0, height > 0 else { return nil }
        return makeImage(width: width, height: height) { out, rowBytes in
            if width % 2 == 0, height % 2 == 0, let info = conversion(.i420, fullRange: false) {
                var srcY = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: y), height: vImagePixelCount(height), width: vImagePixelCount(width), rowBytes: strideY)
                var srcU = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: u), height: vImagePixelCount(height / 2), width: vImagePixelCount(width / 2), rowBytes: strideU)
                var srcV = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: v), height: vImagePixelCount(height / 2), width: vImagePixelCount(width / 2), rowBytes: strideV)
                var dest = vImage_Buffer(data: out, height: vImagePixelCount(height), width: vImagePixelCount(width), rowBytes: rowBytes)
                var info = info
                let error = vImageConvert_420Yp8_Cb8_Cr8ToARGB8888(&srcY, &srcU, &srcV, &dest, &info, bgraPermute, 255, vImage_Flags(kvImageNoFlags))
                if error == kvImageNoError { return true }
            }
            i420ToBgra(y: y, u: u, v: v, strideY: strideY, strideU: strideU, strideV: strideV, width: width, height: height,
                       out: out.assumingMemoryBound(to: UInt8.self), rowBytes: rowBytes)
            return true
        }
    }

    /// Android'deki `i420ToArgb`'nin aynısı (tamsayı BT.601, sınırlı aralık); çıktı BGRA.
    static func i420ToBgra(y: UnsafePointer<UInt8>, u: UnsafePointer<UInt8>, v: UnsafePointer<UInt8>,
                           strideY: Int, strideU: Int, strideV: Int, width: Int, height: Int,
                           out: UnsafeMutablePointer<UInt8>, rowBytes: Int) {
        for row in 0..<height {
            let rowY = y + row * strideY
            let rowU = u + (row / 2) * strideU
            let rowV = v + (row / 2) * strideV
            let target = out + row * rowBytes
            for column in 0..<width {
                let (r, g, b) = yuvToRgb(Int(rowY[column]), Int(rowU[column >> 1]), Int(rowV[column >> 1]), fullRange: false)
                target[column * 4] = b
                target[column * 4 + 1] = g
                target[column * 4 + 2] = r
                target[column * 4 + 3] = 255
            }
        }
    }

    // MARK: NV12 (CVPixelBuffer)

    /// VideoToolbox karesi: NV12 (sınırlı ya da tam aralık) ya da 32 bit BGRA/ARGB. Kırpma bölgesi uygulanır.
    static func image(pixelBuffer: CVPixelBuffer, cropX: Int = 0, cropY: Int = 0, cropWidth: Int? = nil, cropHeight: Int? = nil) -> CGImage? {
        let format = CVPixelBufferGetPixelFormatType(pixelBuffer)
        let fullWidth = CVPixelBufferGetWidth(pixelBuffer), fullHeight = CVPixelBufferGetHeight(pixelBuffer)
        // Kırpma çift sayıya hizalanır (renk düzlemi yarı çözünürlükte).
        let x = max(0, cropX) & ~1, y = max(0, cropY) & ~1
        let width = min(cropWidth ?? fullWidth, fullWidth - x), height = min(cropHeight ?? fullHeight, fullHeight - y)
        guard width > 0, height > 0 else { return nil }
        guard CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        switch format {
        case kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange:
            guard CVPixelBufferGetPlaneCount(pixelBuffer) == 2,
                  let baseY = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0),
                  let baseC = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1) else { return nil }
            let strideY = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
            let strideC = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1)
            let planeY = baseY.assumingMemoryBound(to: UInt8.self) + y * strideY + x
            let planeC = baseC.assumingMemoryBound(to: UInt8.self) + (y / 2) * strideC + x
            return imageFromNV12(y: planeY, uv: planeC, strideY: strideY, strideUV: strideC, width: width, height: height,
                                 fullRange: format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
        case kCVPixelFormatType_32BGRA, kCVPixelFormatType_32ARGB:
            guard let base = CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }
            let stride = CVPixelBufferGetBytesPerRow(pixelBuffer)
            let source = base.assumingMemoryBound(to: UInt8.self) + y * stride + x * 4
            let argb = format == kCVPixelFormatType_32ARGB
            return makeImage(width: width, height: height) { out, rowBytes in
                let target = out.assumingMemoryBound(to: UInt8.self)
                for row in 0..<height {
                    let from = source + row * stride, to = target + row * rowBytes
                    if argb {
                        for column in 0..<width {
                            to[column * 4] = from[column * 4 + 3]
                            to[column * 4 + 1] = from[column * 4 + 2]
                            to[column * 4 + 2] = from[column * 4 + 1]
                            to[column * 4 + 3] = 255
                        }
                    } else {
                        memcpy(to, from, width * 4)
                    }
                }
                return true
            }
        default:
            return nil
        }
    }

    /// NV12: Y düzlemi + iç içe Cb, Cr (yarı çözünürlükte), BT.601.
    static func imageFromNV12(y: UnsafePointer<UInt8>, uv: UnsafePointer<UInt8>, strideY: Int, strideUV: Int,
                              width: Int, height: Int, fullRange: Bool) -> CGImage? {
        guard width > 0, height > 0 else { return nil }
        return makeImage(width: width, height: height) { out, rowBytes in
            if width % 2 == 0, height % 2 == 0, let info = conversion(.nv12, fullRange: fullRange) {
                var srcY = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: y), height: vImagePixelCount(height), width: vImagePixelCount(width), rowBytes: strideY)
                var srcC = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: uv), height: vImagePixelCount(height / 2), width: vImagePixelCount(width / 2), rowBytes: strideUV)
                var dest = vImage_Buffer(data: out, height: vImagePixelCount(height), width: vImagePixelCount(width), rowBytes: rowBytes)
                var info = info
                let error = vImageConvert_420Yp8_CbCr8ToARGB8888(&srcY, &srcC, &dest, &info, bgraPermute, 255, vImage_Flags(kvImageNoFlags))
                if error == kvImageNoError { return true }
            }
            let target = out.assumingMemoryBound(to: UInt8.self)
            for row in 0..<height {
                let rowY = y + row * strideY, rowC = uv + (row / 2) * strideUV, to = target + row * rowBytes
                for column in 0..<width {
                    let c = (column >> 1) * 2
                    let (r, g, b) = yuvToRgb(Int(rowY[column]), Int(rowC[c]), Int(rowC[c + 1]), fullRange: fullRange)
                    to[column * 4] = b
                    to[column * 4 + 1] = g
                    to[column * 4 + 2] = r
                    to[column * 4 + 3] = 255
                }
            }
            return true
        }
    }

    // MARK: Ortak

    /// Tamsayı BT.601 (Android'deki hesap; tam aralıkta 16 çıkarılmaz ve ölçek 256'dır).
    @inline(__always)
    static func yuvToRgb(_ y: Int, _ u: Int, _ v: Int, fullRange: Bool) -> (UInt8, UInt8, UInt8) {
        let d = u - 128, e = v - 128
        let r, g, b: Int
        if fullRange {
            let c = y * 256 + 128
            r = (c + 359 * e) >> 8
            g = (c - 88 * d - 183 * e) >> 8
            b = (c + 454 * d) >> 8
        } else {
            let c = (y - 16) * 298 + 128
            r = (c + 409 * e) >> 8
            g = (c - 100 * d - 208 * e) >> 8
            b = (c + 516 * d) >> 8
        }
        return (UInt8(clamping: r), UInt8(clamping: g), UInt8(clamping: b))
    }

    /// vImage ARGB çıktısını BGRA'ya çevirir (bellekte B, G, R, A).
    private static let bgraPermute: [UInt8] = [3, 2, 1, 0]

    private enum Layout { case i420, nv12 }

    private static let conversionLock = NSLock()
    nonisolated(unsafe) private static var conversions: [String: vImage_YpCbCrToARGB] = [:]

    private static func conversion(_ layout: Layout, fullRange: Bool) -> vImage_YpCbCrToARGB? {
        let key = "\(layout)-\(fullRange)"
        return conversionLock.withLock {
            if let cached = conversions[key] { return cached }
            var range = fullRange
                ? vImage_YpCbCrPixelRange(Yp_bias: 0, CbCr_bias: 128, YpRangeMax: 255, CbCrRangeMax: 255, YpMax: 255, YpMin: 0, CbCrMax: 255, CbCrMin: 0)
                : vImage_YpCbCrPixelRange(Yp_bias: 16, CbCr_bias: 128, YpRangeMax: 235, CbCrRangeMax: 240, YpMax: 235, YpMin: 16, CbCrMax: 240, CbCrMin: 16)
            var info = vImage_YpCbCrToARGB()
            let type = layout == .i420 ? kvImage420Yp8_Cb8_Cr8 : kvImage420Yp8_CbCr8
            let error = vImageConvert_YpCbCrToARGB_GenerateConversion(kvImage_YpCbCrToARGBMatrix_ITU_R_601_4, &range, &info, type,
                                                                      kvImageARGB8888, vImage_Flags(kvImageNoFlags))
            guard error == kvImageNoError else { return nil }
            conversions[key] = info
            return info
        }
    }

    /// `fill` çıktı belleğini (width x height, BGRA) doldurur; görüntü bu belleği sahiplenir.
    private static func makeImage(width: Int, height: Int, fill: (UnsafeMutableRawPointer, Int) -> Bool) -> CGImage? {
        let rowBytes = width * 4
        guard let data = CFDataCreateMutable(nil, rowBytes * height) else { return nil }
        CFDataSetLength(data, rowBytes * height)
        guard let bytes = CFDataGetMutableBytePtr(data), fill(UnsafeMutableRawPointer(bytes), rowBytes),
              let provider = CGDataProvider(data: data) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: rowBytes,
                       space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: bitmapInfo, provider: provider, decode: nil,
                       shouldInterpolate: false, intent: .defaultIntent)
    }
}
