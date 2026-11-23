// SPDX-License-Identifier: AGPL-3.0-only
// Android'deki XboxPortTest.kt (XboxFramesTest) ve XboxStreamFixesTest.kt'nin (satır dolgusu, tek sayılı ölçüler)
// örnekleri; ek olarak VideoToolbox'ın NV12 karesi ve WebRTC kare nesneleri.

import CoreVideo
import XCTest
import WebRTC
@testable import GTNative

final class XboxFramesTests: XCTestCase {
    /// Görüntünün (x, y) pikseli 0xAARRGGBB olarak (çıktı BGRA).
    func pixel(_ image: CGImage, _ x: Int, _ y: Int) -> UInt32 {
        let data = image.dataProvider!.data! as Data
        let at = y * image.bytesPerRow + x * 4
        return UInt32(data[at + 3]) << 24 | UInt32(data[at + 2]) << 16 | UInt32(data[at + 1]) << 8 | UInt32(data[at])
    }

    func near(_ expected: UInt32, _ actual: UInt32, _ tolerance: Int = 2, file: StaticString = #filePath, line: UInt = #line) {
        for shift: UInt32 in [16, 8, 0] {
            let e = Int((expected >> shift) & 0xFF), a = Int((actual >> shift) & 0xFF)
            XCTAssertLessThanOrEqual(abs(e - a), tolerance, String(format: "%08x ~ %08x", expected, actual), file: file, line: line)
        }
        XCTAssertEqual(actual >> 24, 0xFF, file: file, line: line)
    }

    func i420(_ width: Int, _ height: Int, strideY: Int, strideC: Int, y: [UInt8], u: [UInt8], v: [UInt8]) -> CGImage {
        y.withUnsafeBufferPointer { py in
            u.withUnsafeBufferPointer { pu in
                v.withUnsafeBufferPointer { pv in
                    XboxFrames.imageFromI420(y: py.baseAddress!, u: pu.baseAddress!, v: pv.baseAddress!, strideY: strideY,
                                             strideU: strideC, strideV: strideC, width: width, height: height)!
                }
            }
        }
    }

    func testBlackWhiteAndGrey() {
        // 2x2, satır aralığı 4 (dolgu baytları 0xFF: okunmamalı).
        let image = i420(2, 2, strideY: 4, strideC: 2, y: [16, 235, 0xFF, 0xFF, 126, 16, 0xFF, 0xFF], u: [128, 0xFF], v: [128, 0xFF])
        XCTAssertEqual(image.width, 2)
        near(0xFF00_0000, pixel(image, 0, 0), 0)
        near(0xFFFF_FFFF, pixel(image, 1, 0), 0)
        near(0xFF80_8080, pixel(image, 0, 1), 1)
        near(0xFF00_0000, pixel(image, 1, 1), 0)
    }

    func testPrimaryColours() {
        // BT.601 sınırlı aralık: kırmızı (81, 90, 240), yeşil (145, 54, 34), mavi (41, 240, 110). 2x2 (vImage) ve 1x1
        // (Android'deki tamsayı hesabı) yolları aynı sonucu verir.
        for size in [1, 2] {
            func colour(_ y: UInt8, _ u: UInt8, _ v: UInt8) -> UInt32 {
                pixel(i420(size, size, strideY: size, strideC: 1, y: Array(repeating: y, count: size * size), u: [u], v: [v]), 0, 0)
            }
            near(0xFFFF_0000, colour(81, 90, 240))
            near(0xFF00_FF00, colour(145, 54, 34))
            near(0xFF00_00FF, colour(41, 240, 110))
        }
    }

    func testOddSizeUsesTheIntegerPath() {
        // 3x3: renk düzlemi 2x2.
        let image = i420(3, 3, strideY: 3, strideC: 2, y: [235, 235, 235, 16, 16, 16, 235, 235, 235], u: [128, 128, 128, 128], v: [128, 128, 128, 128])
        XCTAssertEqual([pixel(image, 0, 0), pixel(image, 2, 0)], [0xFFFF_FFFF, 0xFFFF_FFFF])
        XCTAssertEqual([pixel(image, 0, 1), pixel(image, 2, 1)], [0xFF00_0000, 0xFF00_0000])
        XCTAssertEqual(pixel(image, 1, 2), 0xFFFF_FFFF)
    }

    func testRowPaddingAndOddSizes() {
        for (width, height, pad) in [(1920, 1080, 0), (1280, 720, 64), (853, 479, 11), (2, 2, 0), (1, 1, 3)] {
            let chromaWidth = (width + 1) / 2, chromaHeight = (height + 1) / 2
            let strideY = width + pad, strideC = chromaWidth + pad
            // Son satırda dolgu yok (WebRTC'nin verdiği arabellek tam bu kadar olabilir).
            let y = [UInt8](repeating: 235, count: strideY * (height - 1) + width)
            let c = [UInt8](repeating: 128, count: strideC * (chromaHeight - 1) + chromaWidth)
            let image = i420(width, height, strideY: strideY, strideC: strideC, y: y, u: c, v: c)
            XCTAssertEqual(pixel(image, 0, 0), 0xFFFF_FFFF, "\(width)x\(height)")
            XCTAssertEqual(pixel(image, width - 1, height - 1), 0xFFFF_FFFF, "\(width)x\(height)")
        }
    }

    func testIntegerPathMatchesAndroidExactly() {
        // Android'in i420ToArgb'si: (126,128,128) → 0x808080; kırmızı (81,90,240) → hesapla aynı.
        XCTAssertTrue(XboxFrames.yuvToRgb(126, 128, 128, fullRange: false) == (128, 128, 128))
        XCTAssertTrue(XboxFrames.yuvToRgb(16, 128, 128, fullRange: false) == (0, 0, 0))
        XCTAssertTrue(XboxFrames.yuvToRgb(235, 128, 128, fullRange: false) == (255, 255, 255))
        XCTAssertTrue(XboxFrames.yuvToRgb(0, 128, 128, fullRange: true) == (0, 0, 0))
        XCTAssertTrue(XboxFrames.yuvToRgb(255, 128, 128, fullRange: true) == (255, 255, 255))
    }

    /// VideoToolbox'ın verdiği biçim: NV12 CVPixelBuffer.
    func nv12(width: Int, height: Int, fullRange: Bool, y: (Int, Int) -> UInt8, cb: UInt8, cr: UInt8) throws -> CVPixelBuffer {
        var buffer: CVPixelBuffer?
        let format = fullRange ? kCVPixelFormatType_420YpCbCr8BiPlanarFullRange : kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        XCTAssertEqual(CVPixelBufferCreate(nil, width, height, format, nil, &buffer), kCVReturnSuccess)
        let pixels = try XCTUnwrap(buffer)
        CVPixelBufferLockBaseAddress(pixels, [])
        let planeY = CVPixelBufferGetBaseAddressOfPlane(pixels, 0)!.assumingMemoryBound(to: UInt8.self)
        let strideY = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0)
        for row in 0..<height { for column in 0..<width { planeY[row * strideY + column] = y(column, row) } }
        let planeC = CVPixelBufferGetBaseAddressOfPlane(pixels, 1)!.assumingMemoryBound(to: UInt8.self)
        let strideC = CVPixelBufferGetBytesPerRowOfPlane(pixels, 1)
        for row in 0..<(height + 1) / 2 {
            for column in 0..<(width + 1) / 2 {
                planeC[row * strideC + column * 2] = cb
                planeC[row * strideC + column * 2 + 1] = cr
            }
        }
        CVPixelBufferUnlockBaseAddress(pixels, [])
        return pixels
    }

    func testNV12VideoAndFullRange() throws {
        let video = try nv12(width: 64, height: 32, fullRange: false, y: { x, _ in x < 32 ? 16 : 235 }, cb: 128, cr: 128)
        let image = try XCTUnwrap(XboxFrames.image(pixelBuffer: video))
        XCTAssertEqual(image.width, 64)
        XCTAssertEqual(image.height, 32)
        near(0xFF00_0000, pixel(image, 0, 0), 1)
        near(0xFFFF_FFFF, pixel(image, 63, 31), 1)
        let full = try nv12(width: 64, height: 32, fullRange: true, y: { _, _ in 255 }, cb: 128, cr: 128)
        near(0xFFFF_FFFF, pixel(try XCTUnwrap(XboxFrames.image(pixelBuffer: full)), 10, 10), 1)
        // Renk: kırmızı (sınırlı aralık 81, 90, 240).
        let red = try nv12(width: 16, height: 16, fullRange: false, y: { _, _ in 81 }, cb: 90, cr: 240)
        near(0xFFFF_0000, pixel(try XCTUnwrap(XboxFrames.image(pixelBuffer: red)), 5, 5))
    }

    func testNV12CropAndOddSize() throws {
        let buffer = try nv12(width: 64, height: 32, fullRange: false, y: { x, _ in x < 32 ? 16 : 235 }, cb: 128, cr: 128)
        let right = try XCTUnwrap(XboxFrames.image(pixelBuffer: buffer, cropX: 32, cropY: 2, cropWidth: 32, cropHeight: 30))
        XCTAssertEqual(right.width, 32)
        XCTAssertEqual(right.height, 30)
        near(0xFFFF_FFFF, pixel(right, 0, 0), 1)
        let odd = try XCTUnwrap(XboxFrames.image(pixelBuffer: buffer, cropX: 0, cropY: 0, cropWidth: 33, cropHeight: 31))
        XCTAssertEqual(odd.width, 33)
        near(0xFF00_0000, pixel(odd, 0, 0), 0)
        near(0xFFFF_FFFF, pixel(odd, 32, 30), 0)
    }

    func testWebRtcFramesOfBothKinds() throws {
        // VideoToolbox yolu: RTCCVPixelBuffer.
        let pixels = try nv12(width: 32, height: 16, fullRange: false, y: { _, _ in 235 }, cb: 128, cr: 128)
        let native = RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pixels), rotation: ._0, timeStampNs: 0)
        let a = try XCTUnwrap(XboxFrames.image(from: native))
        XCTAssertEqual([a.width, a.height], [32, 16])
        near(0xFFFF_FFFF, pixel(a, 31, 15), 1)
        // Yazılım çözücü yolu: I420.
        let i420 = RTCMutableI420Buffer(width: 4, height: 2)
        memset(i420.mutableDataY, 16, Int(i420.strideY) * 2)
        memset(i420.mutableDataU, 128, Int(i420.strideU))
        memset(i420.mutableDataV, 128, Int(i420.strideV))
        let b = try XCTUnwrap(XboxFrames.image(from: RTCVideoFrame(buffer: i420, rotation: ._0, timeStampNs: 0)))
        XCTAssertEqual([b.width, b.height], [4, 2])
        near(0xFF00_0000, pixel(b, 3, 1), 0)
    }

    func testOutputIsDrawableForTheReadingPipeline() throws {
        let image = i420(4, 4, strideY: 4, strideC: 2, y: Array(repeating: 235, count: 16), u: [128, 128, 128, 128], v: [128, 128, 128, 128])
        // Engine'in kullandığı gibi bir bağlama çizilebilir (Frames.signature/resized CGContext'e çizer).
        var gray = [UInt8](repeating: 0, count: 16)
        gray.withUnsafeMutableBytes { buffer in
            let context = CGContext(data: buffer.baseAddress, width: 4, height: 4, bitsPerComponent: 8, bytesPerRow: 4,
                                    space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)!
            context.draw(image, in: CGRect(x: 0, y: 0, width: 4, height: 4))
        }
        XCTAssertEqual(gray[0], 255)
    }

    func testThroughputOf1080pFrame() {
        let y = [UInt8](repeating: 120, count: 1920 * 1080), c = [UInt8](repeating: 128, count: 960 * 540)
        measure {
            _ = i420(1920, 1080, strideY: 1920, strideC: 960, y: y, u: c, v: c)
        }
    }
}
