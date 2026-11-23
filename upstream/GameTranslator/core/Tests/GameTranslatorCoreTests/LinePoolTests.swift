// SPDX-License-Identifier: AGPL-3.0-only

import XCTest
@testable import GameTranslatorCore

/// Ortak havuz: Windows'taki LinePoolTests / PoolFilterTests ve Android'deki LinePoolTest ile aynı örnekler.
final class LinePoolTests: XCTestCase {
    private let rejected = [
        "Oturum Açtı", "Oturum Aqtl", "Oturum Aqtl.", "Sifirlama Seqenekleri", "Sifirlama Seqenekleri.",
        "PS5 Sistemini Yeniden Baslat", "PS5 Sistemini Yeniden Baslat.", "Oyunu Kapat", "Ana Ekranda Tut", "Lütfen bekleyin..",
        "Ekran ve Video", "ilerlemenizi takip edin Kupalar", "Keşfet PS5 ile ilgili Öne Çıkanlar", "Ekran görüntüsü kaydedildi.",
        "Kupa kazandınız!", "John signed in.", "You've earned a trophy!",
        "Please do not quit or turn off your console while the Rockstar cloud save icon is showing.",
        "GO to the", "Take out the trash and the", "it Auto V's Story Mode.", "FREE MONTH OF GTA+", "PHOTO MODE SELECT",
        "Main menu", "Hold on", "100/100 HP 25%",
    ]
    private let accepted = [
        "Herr Müller will see you now.", "This place was designed in a hurry.", "Tell Sam I signed the deal.",
        "I lie to you, you lie to me.", "DON TORRISI WANTS YOU!", "Get down!", "Who's there?", "Line number 7 here",
        "We need to move before the cops show up",
    ]

    func testFilterKeepsGameDialogueAndDropsSystemTextFragmentsAndTurkish() {
        for line in rejected { XCTAssertFalse(LinePool.accepts(line), "alınmamalı: \(line)") }
        for line in accepted { XCTAssertTrue(LinePool.accepts(line), "alınmalı: \(line)") }
    }

    private func temporaryFile() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("pool-" + UUID().uuidString).appendingPathComponent("pool.json")
    }

    func testLinesAreSentOnceWithInstallIdAndGame() throws {
        var bodies: [String] = []
        let file = temporaryFile()
        let pool = LinePool(file: file, app: "GameTranslator 1.8.0", post: { _, body in bodies.append(body); return .init(status: 200) },
                            clock: { 0 })
        pool.setGame(titleId: "PPSA01234_00", name: "Grand Theft Auto VI")
        pool.offer("We need to move before the cops show up")
        pool.offer("We need to move before the cops show up")
        pool.offer("Get down!")
        pool.offer("Oyunu Kapat")
        XCTAssertEqual(pool.queued.count, 2)
        pool.flush()
        XCTAssertEqual(bodies.count, 1)
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(bodies[0].utf8)) as? [String: Any])
        XCTAssertEqual(json["platform"] as? String, "macos")
        XCTAssertEqual(json["app"] as? String, "GameTranslator 1.8.0")
        XCTAssertEqual((json["install"] as? String)?.count, 36)
        let lines = try XCTUnwrap(json["lines"] as? [[String: Any]])
        XCTAssertEqual(lines.count, 2)
        XCTAssertEqual(lines[0]["game"] as? String, "Grand Theft Auto VI")
        XCTAssertEqual(lines[0]["titleId"] as? String, "PPSA01234_00")
        XCTAssertEqual(lines[0]["firstSeen"] as? String, "1970-01-01T00:00:00Z")
        XCTAssertEqual(lines[0]["key"] as? String, PackKey.key("We need to move before the cops show up"))
        XCTAssertTrue(pool.queued.isEmpty)
        // Gönderilen satır yeniden kuyruğa girmez; kimlik ve "gönderildi" kaydı diskte kalır.
        pool.offer("Get down!")
        XCTAssertTrue(pool.queued.isEmpty)
        let reopened = LinePool(file: file, app: "GameTranslator 1.8.0", post: { _, _ in .init(status: 200) })
        XCTAssertEqual(reopened.installId, pool.installId)
        reopened.offer("Get down!")
        XCTAssertTrue(reopened.queued.isEmpty)
    }

    func testUnreachableServerKeepsQueueAndTooManyRequestsWaits() {
        var now: Int64 = 1_000_000
        var calls = 0
        var status = 500
        let pool = LinePool(file: temporaryFile(), app: "GameTranslator", post: { _, _ in calls += 1; return .init(status: status, retryAfter: "120") },
                            clock: { now })
        pool.offer("Get down!")
        pool.flush()
        XCTAssertEqual(pool.queued.count, 1)
        status = 429
        pool.flush()
        XCTAssertEqual(calls, 2)
        pool.flush()
        XCTAssertEqual(calls, 2, "429'dan sonra beklenir")
        now += 121_000
        status = 200
        pool.flush()
        XCTAssertEqual(calls, 3)
        XCTAssertTrue(pool.queued.isEmpty)
    }

    func testQueueSurvivesARestartAndBadRequestsAreDropped() {
        let file = temporaryFile()
        let offline = LinePool(file: file, app: "GameTranslator", post: { _, _ in throw URLError(.notConnectedToInternet) }, clock: { 0 })
        offline.offer("We need to move before the cops show up")
        offline.flush()
        let reopened = LinePool(file: file, app: "GameTranslator", post: { _, _ in .init(status: 400) })
        XCTAssertEqual(reopened.queued.map(\.text), ["We need to move before the cops show up"])
        reopened.flush()
        XCTAssertTrue(reopened.queued.isEmpty)
    }

    func testTurningOffDropsTheQueue() {
        let pool = LinePool(file: temporaryFile(), app: "GameTranslator", post: { _, _ in .init(status: 200) })
        pool.offer("Get down!")
        pool.setEnabled(false)
        XCTAssertTrue(pool.queued.isEmpty)
        pool.offer("Who's there?")
        XCTAssertTrue(pool.queued.isEmpty)
    }

    func testRetryAfterIsClamped() {
        XCTAssertEqual(LinePool.retryDelayMs(nil), 60_000)
        XCTAssertEqual(LinePool.retryDelayMs(" 5 "), 5000)
        XCTAssertEqual(LinePool.retryDelayMs("999999"), 86_400_000)
        XCTAssertEqual(LinePool.retryDelayMs("abc"), 60_000)
    }

    func testTurkishTextIsRecognised() {
        XCTAssertTrue(TurkishText.isSure("Sistem Yazılımı Güncellemesi"))
        XCTAssertTrue(TurkishText.isSure("Lütfen bekleyin"))
        XCTAssertFalse(TurkishText.isSure("Atatürk said we should go."))
        XCTAssertFalse(TurkishText.isSure("Get down!"))
    }
}
