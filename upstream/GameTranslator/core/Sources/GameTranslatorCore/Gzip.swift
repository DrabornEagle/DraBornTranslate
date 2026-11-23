// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// gzip (RFC 1952) ve DEFLATE (RFC 1951) açıcı. Oyun paketleri ve sözcük listesi `.gz`'dir; Foundation'da gzip yok
/// (Apple'ın Compression çerçevesi de yalnız Apple platformlarında), bu yüzden düz Swift ile açılır.
public enum Gzip {
    public struct InvalidData: Error, Equatable {
        public let reason: String
    }

    /// Veri gzip mi (ilk iki bayt 1f 8b).
    public static func isGzip(_ data: Data) -> Bool {
        data.count >= 2 && data[data.startIndex] == 0x1f && data[data.startIndex + 1] == 0x8b
    }

    /// Bütün gzip üyelerini sırayla açar.
    public static func decompress(_ data: Data) throws -> Data {
        let bytes = [UInt8](data)
        var output: [UInt8] = []
        // Açılmış boyut son dört baytta yazar (ISIZE); dizi bir kerede ayrılsın.
        if bytes.count >= 18 {
            let size = Int(bytes[bytes.count - 4]) | Int(bytes[bytes.count - 3]) << 8 | Int(bytes[bytes.count - 2]) << 16
                | Int(bytes[bytes.count - 1]) << 24
            if size > 0 && size < 1 << 29 { output.reserveCapacity(size) }
        }
        var position = 0
        repeat {
            position = try member(bytes, at: position, into: &output)
        } while position + 18 <= bytes.count && bytes[position] == 0x1f && bytes[position + 1] == 0x8b
        return Data(output)
    }

    /// Bir gzip üyesini açar; sonraki üyenin yerini döner.
    private static func member(_ bytes: [UInt8], at start: Int, into output: inout [UInt8]) throws -> Int {
        guard bytes.count - start >= 18, bytes[start] == 0x1f, bytes[start + 1] == 0x8b else { throw InvalidData(reason: "gzip değil") }
        guard bytes[start + 2] == 8 else { throw InvalidData(reason: "bilinmeyen sıkıştırma") }
        let flags = bytes[start + 3]
        var position = start + 10
        func need(_ count: Int) throws { if position + count > bytes.count { throw InvalidData(reason: "kısa gzip başlığı") } }
        if flags & 0x04 != 0 {
            try need(2)
            let extra = Int(bytes[position]) | Int(bytes[position + 1]) << 8
            position += 2 + extra
        }
        if flags & 0x08 != 0 {
            while position < bytes.count && bytes[position] != 0 { position += 1 }
            position += 1
        }
        if flags & 0x10 != 0 {
            while position < bytes.count && bytes[position] != 0 { position += 1 }
            position += 1
        }
        if flags & 0x02 != 0 { position += 2 }
        try need(0)
        let begin = output.count
        var inflater = Inflater(bytes, start: position)
        try inflater.run(into: &output)
        position = inflater.endOfStream
        guard position + 8 <= bytes.count else { throw InvalidData(reason: "gzip sonu eksik") }
        let expected = UInt32(bytes[position]) | UInt32(bytes[position + 1]) << 8 | UInt32(bytes[position + 2]) << 16 | UInt32(bytes[position + 3]) << 24
        let size = UInt32(bytes[position + 4]) | UInt32(bytes[position + 5]) << 8 | UInt32(bytes[position + 6]) << 16 | UInt32(bytes[position + 7]) << 24
        guard UInt32(truncatingIfNeeded: output.count - begin) == size, crc32(output, from: begin) == expected else {
            throw InvalidData(reason: "gzip denetimi tutmadı")
        }
        return position + 8
    }

    /// Ham DEFLATE akışını açar.
    public static func inflate(_ data: Data) throws -> Data {
        var output: [UInt8] = []
        var inflater = Inflater([UInt8](data), start: 0)
        try inflater.run(into: &output)
        return Data(output)
    }

    private static let crcTable: [UInt32] = (0..<256).map { index -> UInt32 in
        var value = UInt32(index)
        for _ in 0..<8 { value = value & 1 != 0 ? 0xEDB88320 ^ (value >> 1) : value >> 1 }
        return value
    }

    static func crc32(_ bytes: [UInt8], from start: Int = 0) -> UInt32 {
        var crc: UInt32 = 0xFFFFFFFF
        bytes.withUnsafeBufferPointer { buffer in
            var index = start
            while index < buffer.count {
                crc = crcTable[Int((crc ^ UInt32(buffer[index])) & 0xff)] ^ (crc >> 8)
                index += 1
            }
        }
        return crc ^ 0xFFFFFFFF
    }
}

/// DEFLATE çözücü (RFC 1951; zlib'in "puff" örneğinin düzeni: kanonik Huffman tabloları, sayma ile çözme).
private struct Inflater {
    private let input: [UInt8]
    private var position: Int
    private var bitBuffer: UInt32 = 0
    private var bitCount: UInt32 = 0

    init(_ input: [UInt8], start: Int) {
        self.input = input
        position = start
    }

    /// Akışın bittiği bayt (son bloğun ardından bayt sınırına yuvarlanır).
    var endOfStream: Int { position }

    private struct Huffman {
        var counts = [Int](repeating: 0, count: 16)
        var symbols: [Int]

        init(lengths: [Int]) throws {
            symbols = [Int](repeating: 0, count: lengths.count)
            for length in lengths { counts[length] += 1 }
            counts[0] = 0
            var left = 1
            for length in 1..<16 {
                left <<= 1
                left -= counts[length]
                if left < 0 { throw Gzip.InvalidData(reason: "bozuk Huffman tablosu") }
            }
            var offsets = [Int](repeating: 0, count: 16)
            for length in 1..<15 { offsets[length + 1] = offsets[length] + counts[length] }
            for (symbol, length) in lengths.enumerated() where length != 0 {
                symbols[offsets[length]] = symbol
                offsets[length] += 1
            }
        }
    }

    private static let lengthBase = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258]
    private static let lengthExtra = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0]
    private static let distanceBase = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073,
                                       4097, 6145, 8193, 12289, 16385, 24577]
    private static let distanceExtra = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13]
    private static let codeOrder = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]

    private static let fixed: (Huffman, Huffman) = {
        var lengths = [Int](repeating: 8, count: 288)
        for symbol in 144..<256 { lengths[symbol] = 9 }
        for symbol in 256..<280 { lengths[symbol] = 7 }
        return (try! Huffman(lengths: lengths), try! Huffman(lengths: [Int](repeating: 5, count: 30)))
    }()

    private mutating func bits(_ need: UInt32) throws -> Int {
        while bitCount < need {
            guard position < input.count else { throw Gzip.InvalidData(reason: "DEFLATE akışı yarım") }
            bitBuffer |= UInt32(input[position]) << bitCount
            position += 1
            bitCount += 8
        }
        let value = bitBuffer & ((1 << need) - 1)
        bitBuffer = need == 32 ? 0 : bitBuffer >> need
        bitCount -= need
        return Int(value)
    }

    private mutating func decode(_ table: Huffman) throws -> Int {
        var code = 0, first = 0, index = 0
        for length in 1..<16 {
            code |= try bits(1)
            let count = table.counts[length]
            if code - count < first { return table.symbols[index + (code - first)] }
            index += count
            first += count
            first <<= 1
            code <<= 1
        }
        throw Gzip.InvalidData(reason: "bozuk Huffman kodu")
    }

    mutating func run(into output: inout [UInt8]) throws {
        var last = false
        while !last {
            last = try bits(1) == 1
            switch try bits(2) {
            case 0: try stored(into: &output)
            case 1: try codes(Self.fixed.0, Self.fixed.1, into: &output)
            case 2:
                let (lengths, distances) = try dynamicTables()
                try codes(lengths, distances, into: &output)
            default: throw Gzip.InvalidData(reason: "bilinmeyen DEFLATE bloğu")
            }
        }
        // Kalan bitler atılır: gzip sonu bayt sınırından başlar.
        bitBuffer = 0
        bitCount = 0
    }

    private mutating func stored(into output: inout [UInt8]) throws {
        bitBuffer = 0
        bitCount = 0
        guard position + 4 <= input.count else { throw Gzip.InvalidData(reason: "DEFLATE akışı yarım") }
        let length = Int(input[position]) | Int(input[position + 1]) << 8
        let complement = Int(input[position + 2]) | Int(input[position + 3]) << 8
        guard length == (~complement & 0xffff) else { throw Gzip.InvalidData(reason: "bozuk saklı blok") }
        position += 4
        guard position + length <= input.count else { throw Gzip.InvalidData(reason: "DEFLATE akışı yarım") }
        output.append(contentsOf: input[position..<(position + length)])
        position += length
    }

    private mutating func dynamicTables() throws -> (Huffman, Huffman) {
        let literalCount = try bits(5) + 257
        let distanceCount = try bits(5) + 1
        let codeCount = try bits(4) + 4
        guard literalCount <= 286, distanceCount <= 30 else { throw Gzip.InvalidData(reason: "bozuk dinamik blok") }
        var codeLengths = [Int](repeating: 0, count: 19)
        for index in 0..<codeCount { codeLengths[Self.codeOrder[index]] = try bits(3) }
        let codeTable = try Huffman(lengths: codeLengths)
        var lengths = [Int](repeating: 0, count: literalCount + distanceCount)
        var index = 0
        while index < lengths.count {
            var symbol = try decode(codeTable)
            if symbol < 16 {
                lengths[index] = symbol
                index += 1
                continue
            }
            var repeated = 0
            if symbol == 16 {
                guard index > 0 else { throw Gzip.InvalidData(reason: "bozuk dinamik blok") }
                repeated = lengths[index - 1]
                symbol = 3 + (try bits(2))
            } else if symbol == 17 {
                symbol = 3 + (try bits(3))
            } else {
                symbol = 11 + (try bits(7))
            }
            guard index + symbol <= lengths.count else { throw Gzip.InvalidData(reason: "bozuk dinamik blok") }
            for _ in 0..<symbol {
                lengths[index] = repeated
                index += 1
            }
        }
        guard lengths[256] != 0 else { throw Gzip.InvalidData(reason: "blok sonu kodu yok") }
        return (try Huffman(lengths: Array(lengths[0..<literalCount])), try Huffman(lengths: Array(lengths[literalCount...])))
    }

    private mutating func codes(_ lengths: Huffman, _ distances: Huffman, into output: inout [UInt8]) throws {
        while true {
            let symbol = try decode(lengths)
            if symbol < 256 {
                output.append(UInt8(symbol))
                continue
            }
            if symbol == 256 { return }
            let lengthIndex = symbol - 257
            guard lengthIndex < 29 else { throw Gzip.InvalidData(reason: "bozuk uzunluk kodu") }
            let length = Self.lengthBase[lengthIndex] + (try bits(UInt32(Self.lengthExtra[lengthIndex])))
            let distanceSymbol = try decode(distances)
            guard distanceSymbol < 30 else { throw Gzip.InvalidData(reason: "bozuk uzaklık kodu") }
            let distance = Self.distanceBase[distanceSymbol] + (try bits(UInt32(Self.distanceExtra[distanceSymbol])))
            guard distance <= output.count else { throw Gzip.InvalidData(reason: "uzaklık çok büyük") }
            // Dizinin kendi diliminden eklemek bütün diziyi kopyalatırdı; baytlar tek tek eklenir (örtüşen kopya da böyle doğrudur).
            let from = output.count - distance
            for offset in 0..<length { output.append(output[from + offset]) }
        }
    }
}
