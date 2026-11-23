// SPDX-License-Identifier: AGPL-3.0-only

import CoreGraphics
import Foundation
import GameTranslatorCore
import ImageIO
import UniformTypeIdentifiers
import Vision

/// Apple Vision ile cihaz üstünde yazı tanıma (Mac uygulamasındaki VisionReader'ın aynısı; Android'de ML Kit).
/// Satırların yeri okunan görüntüye oranla (0–1, üstten) verilir, yukarıdan aşağı sıralanır.
struct VisionReader: Sendable {
    var language = "en"

    func readLines(_ image: CGImage, deep: Bool) throws -> Reading {
        deep ? try firstLines(of: image) : try recognize(image)
    }

    /// Vision geniş bölgede "Go!" gibi kısa satırları sık sık hiç bulmaz. Bölgede yazı çıkmazsa ortadaki şerit ve bölgenin
    /// tamamı büyütülerek yeniden okunur; ikisi de yazı bulursa şeridinki seçilir.
    private func firstLines(of image: CGImage) throws -> Reading {
        let reading = try recognize(image)
        guard reading.lines.isEmpty else { return reading }
        let width = Double(image.width) * 0.4
        let strip = CGRect(x: (Double(image.width) - width) / 2, y: 0, width: width, height: Double(image.height)).integral
        let stripArea = TextBox(left: strip.minX / Double(image.width), top: 0, right: strip.maxX / Double(image.width), bottom: 1)
        if let cropped = image.cropping(to: strip), let big = Frames.scaled(cropped, by: 2) {
            let found = try recognize(big)
            if !found.lines.isEmpty { return found.placed(in: stripArea) }
        }
        if let big = Frames.scaled(image, by: 2) { return try recognize(big) }
        return .empty
    }

    func recognize(_ image: CGImage) throws -> Reading {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = [language]
        request.usesLanguageCorrection = true
        try VNImageRequestHandler(cgImage: image).perform([request])
        let lines = (request.results ?? [])
            .compactMap { observation -> TextLine? in
                guard let candidate = observation.topCandidates(1).first, candidate.confidence >= 0.5 else { return nil }
                let text = candidate.string.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { return nil }
                // Vision'ın kutusu sol alttan ölçülür; burada üstten ölçülür.
                let box = observation.boundingBox
                return TextLine(text, box: TextBox(left: box.minX, top: 1 - box.maxY, right: box.maxX, bottom: 1 - box.minY))
            }
            .sorted { ($0.box?.top ?? 0) < ($1.box?.top ?? 0) }
        return Reading(lines: lines)
    }
}

/// Kare işlemleri: bölge kesme, değişim imzası, küçültme, JPEG.
enum Frames {
    /// Bölgenin kesiti (bölge karenin kenarlarına oranla).
    static func crop(_ image: CGImage, to region: TextBox) -> CGImage? {
        let width = Double(image.width), height = Double(image.height)
        let rect = CGRect(x: width * region.left, y: height * region.top,
                          width: width * (region.right - region.left), height: height * (region.bottom - region.top)).integral
        guard rect.width >= 2, rect.height >= 2 else { return nil }
        return image.cropping(to: rect)
    }

    /// Bölgenin küçültülmüş gri kopyası: iki kareyi yazı tanımaya gerek kalmadan karşılaştırmaya yeter.
    static func signature(of image: CGImage) -> [UInt8] {
        let width = 64, height = 12
        var pixels = [UInt8](repeating: 0, count: width * height)
        pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                                          space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return }
            context.interpolationQuality = .low
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return pixels
    }

    static func scaled(_ image: CGImage, by factor: Int) -> CGImage? {
        resized(image, width: image.width * factor, height: image.height * factor)
    }

    static func resized(_ image: CGImage, width: Int, height: Int) -> CGImage? {
        guard width > 0, height > 0,
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()
    }

    /// En çok `maxWidth` genişlikte JPEG (önizleme ve bölge ayarı için).
    static func jpeg(_ image: CGImage, maxWidth: Int, quality: Double = 0.8) -> Data? {
        let width = min(maxWidth, image.width)
        let source = width == image.width ? image : resized(image, width: width, height: image.height * width / image.width)
        guard let source else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, source, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }
}

extension VisionReader {
    /// Ek alanın okuyucusu (çekirdeğin IconReader'ı için): satırlar ve sözcükleri görüntüye oranla, üstten (0–1).
    func ocrLines(_ image: PixelImage) throws -> [OcrLine] {
        guard image.width >= 16, image.height >= 16, let picture = Frames.cgImage(image) else { return [] }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = [language]
        request.usesLanguageCorrection = true
        try VNImageRequestHandler(cgImage: picture).perform([request])
        func box(_ rect: CGRect) -> TextBox { TextBox(left: rect.minX, top: 1 - rect.maxY, right: rect.maxX, bottom: 1 - rect.minY) }
        return (request.results ?? []).compactMap { observation -> OcrLine? in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            let text = candidate.string
            guard !text.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            var words: [OcrWord] = []
            var index = text.startIndex
            while index < text.endIndex {
                guard let start = text[index...].firstIndex(where: { !$0.isWhitespace }) else { break }
                let end = text[start...].firstIndex(where: \.isWhitespace) ?? text.endIndex
                if let rect = try? candidate.boundingBox(for: start..<end)?.boundingBox {
                    words.append(OcrWord(String(text[start..<end]), box: box(rect)))
                }
                index = end
            }
            return OcrLine(text.trimmingCharacters(in: .whitespaces), box: box(observation.boundingBox), words: words)
        }
        .sorted { ($0.box?.top ?? 0) < ($1.box?.top ?? 0) }
    }
}

extension Frames {
    /// ARGB (0xAARRGGBB) piksellerden görüntü.
    static func cgImage(_ image: PixelImage) -> CGImage? {
        let data = image.pixels.withUnsafeBufferPointer { Data(buffer: $0) }
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        return CGImage(width: image.width, height: image.height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: image.width * 4,
                       space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }

    /// Görüntünün bir bölgesi ARGB piksel olarak (ek alanlar için; alan 16 pikselden küçükse nil).
    static func pixels(_ image: CGImage, region: TextBox) -> PixelImage? {
        let left = min(max(Int((Double(image.width) * region.left).rounded(.down)), 0), image.width)
        let top = min(max(Int((Double(image.height) * region.top).rounded(.down)), 0), image.height)
        let right = min(max(Int((Double(image.width) * region.right).rounded(.up)), left), image.width)
        let bottom = min(max(Int((Double(image.height) * region.bottom).rounded(.up)), top), image.height)
        let width = right - left, height = bottom - top
        guard width >= 16, height >= 16, let part = image.cropping(to: CGRect(x: left, y: top, width: width, height: height)) else { return nil }
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.draw(part, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return drawn ? PixelImage(width: width, height: height, rgba: bytes) : nil
    }
}

/// Son kare; ek alan okuyucusu her turda alanını buradan keser.
struct FrameAreas: RegionFrame {
    let image: CGImage
    func area(_ region: TextBox) -> PixelImage? { Frames.pixels(image, region: region) }
}
