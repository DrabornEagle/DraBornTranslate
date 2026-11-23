// SPDX-License-Identifier: AGPL-3.0-only

import XCTest
@testable import GTNative
import GameTranslatorCore

final class SettingsTests: XCTestCase {
    func testDefaultsWhenEmpty() {
        let settings = Settings.from([:], defaultTarget: "en")
        XCTAssertEqual(settings.targetLanguage, "en")
        XCTAssertEqual(settings.engine, "google")
        // Kayıtlı ayarda alan yoksa kullanıcı bölgeyi elle seçmişti (Android ile aynı kural).
        XCTAssertFalse(settings.autoRegion)
    }

    func testRoundTripKeepsAndroidShape() throws {
        var settings = Settings()
        settings.engine = "deepl"
        settings.deepLKey = "k:fx"
        settings.style.regionScale = 120
        settings.style.regionColor = "yellow"
        settings.extraAreas = settings.extraAreas.with(.quest, on: true)
        let json = settings.json
        let style = try XCTUnwrap(json["style"] as? [String: Any])
        XCTAssertEqual(style["regionScale"] as? Int, 120)
        XCTAssertEqual(style["regionColor"] as? String, "yellow")
        let extras = try XCTUnwrap(json["extraAreas"] as? [Any])
        XCTAssertEqual((extras[0] as? [String: Any])?["preset"] as? String, "quest")
        XCTAssertTrue(extras[1] is NSNull)
        let back = try XCTUnwrap(Settings.from(text: settings.jsonText))
        XCTAssertEqual(back, settings)
        XCTAssertEqual(back.watchedAreas.map(\.id), ["area1"])
    }

    func testRegionIsClamped() {
        let settings = Settings.from(["region": ["top": 0.9, "bottom": 0.5, "left": -1, "right": 2]])
        XCTAssertEqual(settings.region.top, 0.9)
        XCTAssertEqual(settings.region.bottom, 0.95, accuracy: 1e-9)
        XCTAssertEqual(settings.region.left, 0)
        XCTAssertEqual(settings.region.right, 1)
    }

    func testButtonLayoutRoundTrip() throws {
        XCTAssertEqual(Settings.from(["buttonLayout": "Xbox"]).buttonLayout, "xbox")
        XCTAssertEqual(Settings.from(["buttonLayout": "nintendo"]).buttonLayout, "auto")
        var settings = Settings()
        settings.buttonLayout = "playstation"
        XCTAssertEqual(try XCTUnwrap(Settings.from(text: settings.jsonText)).buttonLayout, "playstation")
    }

    func testPresetNameWins() {
        let settings = Settings.from(["extraAreas": [["preset": "phone", "live": true, "region": ["top": 0.1]]]])
        XCTAssertEqual(settings.extraAreas.region(1), ExtraAreaPreset.phone.region)
        XCTAssertTrue(settings.extraAreas.live(1))
    }

    func testStyleMapsToCoreStyle() {
        var style = StyleSettings()
        style.placement = "above"
        style.textColor = "cyan"
        style.regionScale = 140
        let core = style.subtitleStyle
        XCTAssertEqual(core.placement, .above)
        XCTAssertEqual(core.textColor, .cyan)
        XCTAssertEqual(core.regionTextScale, 140)
        XCTAssertNil(core.regionTextColor)
    }
}

final class LanguagesTests: XCTestCase {
    func testEngines() {
        XCTAssertEqual(Languages.effectiveEngine("deepl", target: "tr"), "deepl")
        XCTAssertEqual(Languages.effectiveEngine("mlkit", target: "az"), "google")
        XCTAssertEqual(Languages.deepLTarget("pt", region: "PT"), "PT-PT")
        XCTAssertEqual(Languages.googleCode("zh"), "zh-CN")
        XCTAssertEqual(Languages.defaultTarget(systemLanguage: "iw"), "he")
        XCTAssertEqual(Languages.defaultTarget(systemLanguage: "xx"), "en")
        XCTAssertEqual((Languages.maps()["targets"] as? [Any])?.count, Languages.targets.count)
    }

    func testUiCode() {
        XCTAssertEqual(UiText.code("not_found", "1.2.3.4"), "@not_found\u{1f}1.2.3.4")
        XCTAssertEqual(UiText.code("lost"), "@lost")
    }
}

final class TranslatorTests: XCTestCase {
    func testGoogleParse() {
        XCTAssertEqual(GoogleGtxTranslator.parse(Data(#"[[["Merhaba ","Hello",null],["dünya","world"]],null,"en"]"#.utf8)), "Merhaba dünya")
        XCTAssertEqual(GoogleGtxTranslator.parse(Data(#"["Tamam &#39;evet&#39;"]"#.utf8)), "Tamam 'evet'")
        XCTAssertNil(GoogleGtxTranslator.parse(Data("[]".utf8)))
    }

    func testPrefixKept() async {
        let upper = PrefixTranslator(inner: ClosureTranslator { $0.uppercased() })
        let result = await upper.translate("- hello")
        XCTAssertEqual(result, "- HELLO")
    }

    func testFallback() async {
        let fails = ClosureTranslator { _ in nil }
        let works = ClosureTranslator { "ok:\($0)" }
        let result = await FallbackTranslator(primary: fails, fallback: works).translate("x")
        XCTAssertEqual(result, "ok:x")
    }
}

final class HistoryTests: XCTestCase {
    func testAddReplacesRereadAndRoundTrips() throws {
        let history = PhoneHistory(limit: 3)
        XCTAssertTrue(history.add(source: "Hello there.", translation: "Merhaba.", date: 1_700_000_000_000))
        XCTAssertTrue(history.add(source: "Hello there!", translation: "Merhaba!", fromPack: true, date: 1_700_000_001_000))
        XCTAssertEqual(history.entries.count, 1)
        XCTAssertFalse(history.add(source: "x", translation: "  "))
        history.add(source: "Second line", translation: "İkinci", date: 1_700_000_002_000)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".json")
        try history.save(to: file)
        let loaded = PhoneHistory.load(from: file)
        XCTAssertEqual(loaded.entries, history.entries)
        XCTAssertTrue(loaded.entries[1].fromPack)
        XCTAssertEqual(PhoneHistory.parse("2026-11-20T05:00:00.123Z"), 1_795_150_800_123)
    }
}

final class StoreTests: XCTestCase {
    func testDeepLKeyVaultKeepsKeyOutOfJSON() throws {
        let suite = "gt-vault-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let store = Store(defaults: defaults)
        var settings = store.settings
        settings.deepLKey = "old:fx"
        store.settings = settings
        var vault = ""
        store.deepLKeyVault = (load: { vault.isEmpty ? nil : vault }, save: { vault = $0 })
        XCTAssertEqual(vault, "old:fx")
        XCTAssertFalse(defaults.string(forKey: "settings")?.contains("old:fx") ?? true)
        XCTAssertEqual(store.settings.deepLKey, "old:fx")
        settings = store.settings
        settings.deepLKey = "new:fx"
        store.settings = settings
        XCTAssertEqual(vault, "new:fx")
        XCTAssertFalse(defaults.string(forKey: "settings")?.contains("new:fx") ?? true)
    }

    func testAccountAndSettings() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "gt-test-\(UUID().uuidString)"))
        let store = Store(defaults: defaults)
        XCTAssertEqual(store.settings.targetLanguage, Languages.defaultTarget(systemLanguage: Locale.current.language.languageCode?.identifier ?? "en"))
        store.accountId = Data([1, 2, 3])
        XCTAssertNil(store.accountId)
        store.accountId = Data(repeating: 7, count: 8)
        XCTAssertEqual(store.accountId?.count, 8)
        XCTAssertEqual(store.consoleKind, Store.kindPS5)
        store.screenScale = 9
        XCTAssertEqual(store.screenScale, Store.screenScaleMax)
    }
}
