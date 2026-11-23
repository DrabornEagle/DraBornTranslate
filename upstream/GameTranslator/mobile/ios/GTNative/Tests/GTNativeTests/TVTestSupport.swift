// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
@testable import GTNative

/// Bellekteki tek yönlü boru: bir uçtan yazılan baytlar öbür uçtan bloklayarak okunur (Kotlin denemelerindeki
/// PipedInputStream/PipedOutputStream).
final class MemoryPipe: TvByteInput, TvByteOutput, @unchecked Sendable {
    private let condition = NSCondition()
    private var data = Data()
    private var closed = false

    func write(_ bytes: Data) throws {
        condition.lock()
        defer { condition.unlock() }
        if closed { throw TvIOError.closed("boru kapalı") }
        data.append(bytes)
        condition.broadcast()
    }

    func readSome(_ max: Int) throws -> Data {
        condition.lock()
        defer { condition.unlock() }
        let deadline = Date().addingTimeInterval(10)
        while data.isEmpty && !closed {
            if !condition.wait(until: deadline) { throw TvIOError.timeout }
        }
        let out = Data(data.prefix(max))
        data.removeFirst(out.count)
        return out
    }

    func close() {
        condition.lock()
        closed = true
        condition.broadcast()
        condition.unlock()
    }
}

/// Kotlin denemeleriyle ortak deneme dosyaları (mobile/android/app/src/test/resources/fixtures).
enum TVFixtures {
    static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    static func samsung(_ name: String) throws -> Data {
        try Data(contentsOf: root.appendingPathComponent("mobile/android/app/src/test/resources/fixtures/samsung/\(name)"))
    }
}

/// Arka planda çalışan bir iş ve bitmesini bekleme.
final class Background: @unchecked Sendable {
    private let done = DispatchSemaphore(value: 0)
    private(set) var error: Error?

    init(_ body: @escaping () throws -> Void) {
        let thread = Thread { [self] in
            do { try body() } catch { self.error = error }
            done.signal()
        }
        thread.start()
    }

    @discardableResult
    func join(_ seconds: Double = 10) -> Bool { done.wait(timeout: .now() + seconds) == .success }
}
