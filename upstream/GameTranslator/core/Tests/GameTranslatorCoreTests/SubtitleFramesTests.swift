// SPDX-License-Identifier: AGPL-3.0-only

import XCTest
@testable import GameTranslatorCore

/// TV'ye giden olay çerçeveleri (Windows: SubtitleServerTests'in çerçeve denemeleri). Her platformda çalışır.
final class SubtitleFramesTests: XCTestCase {
    private func data(_ frame: String) -> String {
        frame.components(separatedBy: "\n").first { $0.hasPrefix("data: ") }.map { String($0.dropFirst(6)) } ?? ""
    }

    private func json(_ frame: String) throws -> [String: Any] {
        try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(data(frame).utf8)) as? [String: Any])
    }

    func testSubtitleCarriesBoxReplaceAndParagraphs() {
        let box = TextBox(left: 0.25, top: 0.8, right: 0.75, bottom: 0.874_99)
        let frame = SubtitleFrames.subtitle("Merhaba", durationMs: 4000, box: box, style: nil, replaces: true, at: 12)
        XCTAssertEqual(frame, "event: subtitle\ndata: {\"at\":12,\"box\":{\"bottom\":0.875,\"left\":0.25,\"right\":0.75,\"top\":0.8},"
            + "\"duration\":4000,\"replace\":true,\"text\":\"Merhaba\"}\n\n")
        let panel = SubtitleFrames.subtitle("Görev\nDemirciyi bul", durationMs: 9000, box: nil, style: SubtitleStyle(), paragraphs: true, at: 1)
        XCTAssertEqual(data(panel), "{\"at\":1,\"duration\":9000,\"paragraphs\":true,\"text\":\"Görev\\nDemirciyi bul\"}")
    }

    /// Örtme yerleşiminde oyunun altyazısının gerçek yeri de (`source`) gider; büyütülmüş `box` eski TV uygulamaları içindir.
    func testCoverSendsTheRealSubtitleArea() {
        let style = SubtitleStyle(fontSize: 46, bottomPercent: 12, placement: .cover, background: 72, bold: false)
        let frame = SubtitleFrames.subtitle("Bana niye söylüyorsun? Bunu bilmem gerekiyor mu gerçekten?", durationMs: 4000,
                                            box: TextBox(left: 0.4, top: 0.85, right: 0.6, bottom: 0.89), style: style, at: 0,
                                            cover: StableCover(), source: StableCover())
        XCTAssertTrue(frame.contains("\"source\":{\"bottom\":0.89,\"left\":0.4,\"right\":0.6,\"top\":0.85}"), frame)
        XCTAssertFalse(frame.contains("\"box\":{\"bottom\":0.89,\"left\":0.4,"), frame)
        // Paragraflı yazı ve örtmeyen yerleşim kutuyu olduğu gibi taşır, source yok.
        let fixed = SubtitleFrames.subtitle("Merhaba", durationMs: 1, box: TextBox(left: 0.4, top: 0.85, right: 0.6, bottom: 0.89),
                                            style: SubtitleStyle(placement: .fixed), at: 0)
        XCTAssertFalse(fixed.contains("source"))
    }

    func testStyleHasAllWindowsKeys() throws {
        let style = SubtitleStyle(fontSize: 58, bottomPercent: 10, placement: .above, background: 40, bold: true, textColor: .yellow,
                                  displayMode: .overlay, stackPrevious: true, regionBackground: 140, regionTextScale: 40, regionTextColor: .cyan)
        let frame = SubtitleFrames.style(style)
        XCTAssertTrue(frame.hasPrefix("event: style\n"))
        XCTAssertEqual(data(frame), "{\"background\":40,\"bold\":true,\"bottomPercent\":10,\"color\":\"#FFE600\",\"displayMode\":\"overlay\","
            + "\"fontFamily\":\"\",\"fontSize\":58,\"fontUrl\":\"\",\"placement\":\"above\",\"regionBackground\":100,\"regionColor\":\"#38BDF8\","
            + "\"regionScale\":70,\"speakerColor\":\"\",\"speakerColors\":[],\"stackPrevious\":true,\"textColor\":\"yellow\"}")
        // Örtmede çevirinin kendi zemini kapatılır, önceki satır da yığılmaz.
        let cover = try json(SubtitleFrames.style(SubtitleStyle(placement: .cover, background: 80)))
        XCTAssertEqual(cover["background"] as? Int, 0)
        XCTAssertEqual(cover["stackPrevious"] as? Bool, false)
    }

    func testSpeakerColorsOnlyWhenTurnedOn() throws {
        let style = try json(SubtitleFrames.style(SubtitleStyle(speakerColor: true)))
        XCTAssertEqual(style["speakerColor"] as? String, "#FFD60A")
        XCTAssertGreaterThanOrEqual((style["speakerColors"] as? [String])?.count ?? 0, 5)
    }

    func testFontFamilyAndUrl() throws {
        let named = try json(SubtitleFrames.style(SubtitleStyle(font: "Bilinmeyen Font"), fontUrl: nil))
        XCTAssertEqual(named["fontFamily"] as? String, "Bilinmeyen Font")
        XCTAssertEqual(named["fontUrl"] as? String, "")
        let quicksand = try json(SubtitleFrames.style(SubtitleStyle(font: SubtitleFonts.quicksand), fontUrl: "/font/abc.ttf"))
        XCTAssertEqual(quicksand["fontFamily"] as? String, "Quicksand")
        XCTAssertEqual(quicksand["fontUrl"] as? String, "/font/abc.ttf")
        // Font seçilmemişse adres gönderilmez.
        XCTAssertEqual(try json(SubtitleFrames.style(SubtitleStyle(), fontUrl: "/font/abc.ttf"))["fontUrl"] as? String, "")
    }

    func testEmbeddedFontIsServedAtItsHashPath() throws {
        let font = try XCTUnwrap(SubtitleFonts.embedded("Quicksand"))
        XCTAssertGreaterThan(font.data.count, 50_000)
        let path = SubtitleFrames.fontPath(font)
        XCTAssertTrue(path.hasPrefix("/font/") && path.hasSuffix(".ttf"), path)
        XCTAssertEqual(path.count, "/font/".count + 12 + ".ttf".count)
        XCTAssertNil(SubtitleFonts.embedded("Arial"))
        XCTAssertEqual(SubtitleFrames.fontPath(FontFile(data: Data("abc".utf8), type: "font/otf")), "/font/a9993e364706.otf")
    }

    func testFontPresetsAndOldSettings() {
        XCTAssertNil(SubtitleFonts.family(""))
        XCTAssertEqual(SubtitleFonts.family(SubtitleFonts.gta6), "Pricedown")
        XCTAssertEqual(SubtitleFonts.family(SubtitleFonts.pricedown), "Pricedown")
        XCTAssertEqual(SubtitleFonts.family("Arial"), "Arial")
        XCTAssertEqual(SubtitleFonts.presets.map(\.key), [SubtitleFonts.quicksand, SubtitleFonts.pricedown])
        XCTAssertEqual(SubtitleFonts.menuKey(SubtitleFonts.gta6), SubtitleFonts.pricedown)
        XCTAssertNotNil(SubtitleFonts.downloadPage(SubtitleFonts.gta6))
        XCTAssertNil(SubtitleFonts.downloadPage("Arial"))
    }

    func testRegionFramesAndClear() {
        XCTAssertEqual(SubtitleFrames.region(id: "q", text: "Demirciyi bul", box: TextBox(left: 0.7, top: 0.05, right: 0.95, bottom: 0.12), lineHeight: 0.03),
                       "event: region\ndata: {\"box\":{\"bottom\":0.12,\"left\":0.7,\"right\":0.95,\"top\":0.05},\"id\":\"q\",\"line\":0.03,\"text\":\"Demirciyi bul\"}\n\n")
        XCTAssertEqual(data(SubtitleFrames.region(id: "q", text: "", box: nil)), "{\"id\":\"q\",\"text\":\"\"}")
        XCTAssertEqual(SubtitleFrames.regionClear, "event: region-clear\ndata: {}\n\n")
    }

    func testScreenSpeechHelloAndPing() {
        let screen = SubtitleFrames.screen([
            .init(source: "Map\nSettings", translation: "Harita\nAyarlar", box: TextBox(left: 0.05, top: 0.3, right: 0.2, bottom: 0.4), lineHeight: 0.03),
        ], durationMs: 12000)
        XCTAssertEqual(data(screen), "{\"duration\":12000,\"items\":[{\"box\":{\"bottom\":0.4,\"left\":0.05,\"right\":0.2,\"top\":0.3},"
            + "\"line\":0.03,\"paragraphs\":true,\"text\":\"Harita\\nAyarlar\"}]}")
        XCTAssertEqual(data(SubtitleFrames.speech(url: "/speech/1.wav", durationMs: 1200)), "{\"duration\":1200,\"url\":\"/speech/1.wav\"}")
        XCTAssertTrue(SubtitleFrames.helloBody(active: false).contains("\"active\":false"))
        XCTAssertTrue(SubtitleFrames.helloBody(active: true).hasPrefix("{\"active\":true,\"app\":\"gametranslator\""))
        XCTAssertEqual(SubtitleFrames.ping, ": ping\n\nevent: ping\ndata: {}\n\n")
    }

    func testJsonEscapingAndNumbers() {
        XCTAssertEqual(FrameValue.string("a\"b\\c\u{1}ğ").json, "\"a\\\"b\\\\c\\u0001ğ\"")
        XCTAssertEqual(FrameValue.ratioText(0.8), "0.8")
        XCTAssertEqual(FrameValue.ratioText(1.4), "1")
        XCTAssertEqual(FrameValue.ratioText(-1), "0")
        XCTAssertEqual(FrameValue.ratioText(0.00005), "0.0001")
        XCTAssertEqual(FrameValue.ratioText(0.0123), "0.0123")
    }

    func testEachSpeakerKeepsTheirOwnColor() {
        let palette = SpeakerColors.palette(.white)
        let speakers = SpeakerColors()
        func color(_ text: String, _ name: String) -> String? { speakers.split(text, palette: palette).first { $0.text == name + ":" }?.color }
        let jason = color("Jason: Hadi gidelim.", "Jason")
        let lucia = color("Lucia: Bekle!", "Lucia")
        XCTAssertEqual(jason, palette[0])
        XCTAssertEqual(lucia, palette[1])
        XCTAssertEqual(color("Jason: Tamam.", "Jason"), jason)
        let both = speakers.split("Lucia: Şimdi! Jason: Geliyorum.", palette: palette).filter { $0.color != nil }
        XCTAssertEqual(both.map(\.text), ["Lucia:", "Jason:"])
        XCTAssertEqual(both.map(\.color), [lucia, jason])
        let plain = speakers.split("Hepsi bu kadar.", palette: palette)
        XCTAssertEqual(plain.count, 1)
        XCTAssertNil(plain[0].color)
    }

    func testSpeakerColorsAvoidTheTextColorAndRecycleTheQuietestSpeaker() {
        XCTAssertFalse(SpeakerColors.palette(.yellow).contains("#FFD60A"))
        XCTAssertEqual(SpeakerColors.palette(.yellow).first, "#64D2FF")
        let palette = ["#1", "#2"]
        let speakers = SpeakerColors()
        _ = speakers.split("Ali: a", palette: palette)
        _ = speakers.split("Veli: b", palette: palette)
        _ = speakers.split("Ali: c", palette: palette)
        // Renk kalmadı: en uzun süredir konuşmayan Veli'nin rengi Ayşe'ye geçer.
        XCTAssertEqual(speakers.split("Ayşe: d", palette: palette)[0].color, "#2")
        XCTAssertEqual(speakers.split("Ali: e", palette: palette)[0].color, "#1")
    }

    func testOldSavedStyleGetsNewDefaultsAndWindowsNamesAreRead() throws {
        let old = try JSONDecoder().decode(SubtitleStyle.self, from: Data(#"{"fontSize":58,"bottomPercent":12}"#.utf8))
        XCTAssertEqual(old, SubtitleStyle(fontSize: 58, bottomPercent: 12, placement: .cover, background: 72, bold: false))
        let windows = try JSONDecoder().decode(SubtitleStyle.self, from: Data(#"{"placement":"Below","textColor":"Yellow","displayMode":"Overlay","regionTextColor":"Green"}"#.utf8))
        XCTAssertEqual(windows.placement, .below)
        XCTAssertEqual(windows.textColor, .yellow)
        XCTAssertEqual(windows.displayMode, .overlay)
        XCTAssertEqual(windows.regionTextColor, .green)
        let style = SubtitleStyle(textColor: .orange, displayMode: .overlay, stackPrevious: false, speakerColor: true, font: "quicksand",
                                  regionBackground: 50, regionTextScale: 120, regionTextColor: .cyan)
        XCTAssertEqual(try JSONDecoder().decode(SubtitleStyle.self, from: JSONEncoder().encode(style)), style)
    }

    func testTextColorHexMatchesWindows() {
        XCTAssertEqual(SubtitleTextColor.allCases.map(\.hex), ["#FFFFFF", "#FFE600", "#38BDF8", "#10B981", "#FF9F0A"])
    }

    func testCoverFitGrowsTheBoxAroundTheTranslation() {
        let source = TextBox(left: 0.4, top: 0.85, right: 0.6, bottom: 0.89)
        let fitted = CoverFit.box(source, text: "Bana niye söylüyorsun? Bunu bilmem gerekiyor mu gerçekten?", fontSize: 46)
        XCTAssertLessThan(fitted.left, source.left)
        XCTAssertGreaterThan(fitted.right, source.right)
        XCTAssertEqual((fitted.left + fitted.right) / 2, 0.5, accuracy: 0.001)
        XCTAssertEqual(CoverFit.box(source, text: "  ", fontSize: 46), source)
    }

    func testStableCoverHoldsTheBackgroundAndResetsLater() {
        var now: Int64 = 0
        let cover = StableCover(clock: { now })
        let first = cover.next(TextBox(left: 0.2, top: 0.80, right: 0.8, bottom: 0.84))
        now = 1000
        let second = cover.next(TextBox(left: 0.25, top: 0.84, right: 0.75, bottom: 0.88))
        XCTAssertEqual(second, TextBox(left: 0.2, top: 0.80, right: 0.8, bottom: 0.88))
        now = 1000 + StableCover.holdMs + 1
        let later = cover.next(TextBox(left: 0.3, top: 0.84, right: 0.7, bottom: 0.88))
        XCTAssertEqual(later, TextBox(left: 0.3, top: 0.84, right: 0.7, bottom: 0.88))
        XCTAssertEqual(first.top, 0.80)
    }
}
