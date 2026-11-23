// SPDX-License-Identifier: AGPL-3.0-only

@testable import GameTranslatorCore
import XCTest

/// Ek alan yuvaları, hazır seçimler, GTA 6 ayarı ve altyazı okumasının ek alanları atlaması (Windows: ExtraRegionTests,
/// Android: ExtraAreasTest).
final class ExtraAreasTests: XCTestCase {
    func testLiveAreasAreOffByDefault() {
        let slots = ExtraSlots()
        XCTAssertTrue(slots.watchedAreas().isEmpty)
        for slot in 1...ExtraAreas.slots {
            XCTAssertFalse(slots.live(slot))
            XCTAssertNil(slots.region(slot))
        }
        XCTAssertNil(slots.region(0))
        XCTAssertEqual(slots.withLive(4, true), slots)
    }

    /// GTA 6 için tek dokunuş: üç ek alan (görev, ipucu kutusu, telefon mesajı) sürekli okunur.
    func testGta6ReadingTurnsOnItsThreeAreas() {
        let slots = ExtraSlots().withGta6Reading()
        XCTAssertEqual(slots.watchedAreas().count, 3)
        XCTAssertTrue(slots.has(.bottomObjective))
        XCTAssertTrue(slots.has(.helpBox))
        XCTAssertTrue(slots.has(.phoneMessage))
        XCTAssertEqual(slots.watchedAreas().map(\.id), ["area1", "area2", "area3"])
    }

    /// Hazır alan boş ilk yuvaya girer; üç yuva doluysa ayar değişmez; kapatınca yuva boşalır.
    func testPresetAreasFillFreeSlotsUpToThree() {
        let slots = ExtraSlots().with(.helpBox, on: true).with(.phoneMessage, on: true).with(.quest, on: true)
        XCTAssertEqual(slots.watchedAreas().count, 3)
        let full = slots.with(.notification, on: true)
        XCTAssertFalse(full.has(.notification))
        XCTAssertEqual(full, slots)
        let freed = slots.with(.phoneMessage, on: false).with(.notification, on: true)
        XCTAssertTrue(freed.has(.notification))
        XCTAssertFalse(freed.has(.phoneMessage))
        XCTAssertEqual(freed.watchedAreas().count, 3)
        // Aynı alanı yeniden açmak yuva harcamaz.
        XCTAssertEqual(slots.with(.quest, on: true), slots)
    }

    func testOnlyConfiguredLiveRegionsAreWatched() {
        let slots = ExtraSlots().withRegion(1, ExtraAreaPreset.quest.region).withLive(1, true)
            .withRegion(2, TextBox(left: 0.14, top: 0.62, right: 0.86, bottom: 0.97)).withLive(3, true)
        let watched = slots.watchedAreas()
        XCTAssertEqual(watched.count, 1)
        XCTAssertEqual(watched[0].id, "area1")
        XCTAssertEqual(watched[0].region, ExtraAreaPreset.quest.region)
    }

    /// Yuvalar ayar dosyasında kalır; arayüzün yalnız adını yazdığı hazır seçimin bölgesi addan gelir.
    func testExtraAreasSurviveTheSettingsFile() throws {
        let slots = ExtraSlots().withGta6Reading().with(.helpBox, on: false)
        let json = slots.json()
        XCTAssertEqual((json[0] as? [String: Any])?["preset"] as? String, "bottom_objective")
        let data = try JSONSerialization.data(withJSONObject: ["extraAreas": json])
        let decoded = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let back = ExtraSlots(json: decoded["extraAreas"])
        XCTAssertEqual(back, slots)
        XCTAssertEqual(back.watchedAreas().count, 2)

        let fromUi: [Any] = [["preset": "phone_message", "live": true], NSNull(), ["preset": "no_such_preset", "live": true]]
        let parsed = ExtraSlots(json: fromUi)
        XCTAssertTrue(parsed.has(.phoneMessage))
        XCTAssertEqual(parsed.watchedAreas().count, 1)
        XCTAssertNil(parsed.region(3))
        // Eski kayıtta alan yoktur: yuvalar boş.
        XCTAssertTrue(ExtraSlots(json: nil).watchedAreas().isEmpty)
        // Bölge kenarları karenin içine çekilir.
        let clamped = ExtraSlots.region(fromJson: ["top": 0.99, "left": -1, "right": 0.01])
        XCTAssertEqual(clamped, TextBox(left: 0, top: 0.95, right: 0.05, bottom: 1))
    }

    /// Altyazı okuması ek alanların içine düşen satırları atlar (GTA 6'da alttaki görev yazısı altyazı şeridinde).
    func testSubtitleReadingSkipsLinesInsideExtraAreas() {
        let watched = ExtraSlots().withGta6Reading().watchedAreas()
        let subtitle = TextLine("Lucia: Get in, we're late.", box: TextBox(left: 0.26, top: 0.84, right: 0.74, bottom: 0.88))
        let objective = TextLine("Follow Brian", box: TextBox(left: 0.46, top: 0.94, right: 0.54, bottom: 0.97))
        let kept = ExtraAreas.withoutWatchedAreas([subtitle, objective], watched)
        XCTAssertEqual(kept.map(\.text), ["Lucia: Get in, we're late."])
        XCTAssertEqual(ExtraAreas.withoutWatchedAreas(kept, []), kept)
    }

    /// Ek alana düşen altyazı satırı ve uzun altyazının alana taşan ucu ek alanda çevrilmez.
    func testSubtitleLinesAreSkippedInExtraAreas() {
        XCTAssertTrue(ExtraAreas.skipLine("Lucia: Get in, we're late.", shownSource: nil))
        XCTAssertTrue(ExtraAreas.skipLine("ot it?", shownSource: "Have you got it?"))
        XCTAssertTrue(ExtraAreas.skipLine("Have you got it", shownSource: "Have you got it?"))
        XCTAssertFalse(ExtraAreas.skipLine("Follow Brian", shownSource: "Have you got it?"))
        XCTAssertFalse(ExtraAreas.skipLine("Follow Brian", shownSource: nil))
    }

    func testPresetsAreFoundByNameAndRegion() {
        for preset in ExtraAreaPreset.allCases {
            XCTAssertEqual(ExtraAreaPreset.byKey(preset.key), preset)
            XCTAssertEqual(ExtraAreaPreset.matching(preset.region), preset)
            XCTAssertTrue(preset.region.left < preset.region.right && preset.region.top < preset.region.bottom)
        }
        XCTAssertNil(ExtraAreaPreset.byKey("x"))
        XCTAssertEqual(ExtraAreaPreset.allCases.map(\.key), ["quest", "notification", "phone", "bottom_objective", "help_box", "phone_message"])
    }
}
