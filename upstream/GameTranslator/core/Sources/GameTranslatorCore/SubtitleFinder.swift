// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Altyazıyı ekranın tamamından, kullanıcı alan seçmeden bulur (Windows: SubtitleFinder.cs, Android: SubtitleFinder.kt;
/// aynı kurallar ve sabitler). Yazının yerine değil davranışına bakar: altyazı ortalıdır, cümle gibidir, birkaç saniyede
/// bir değişir ve hep aynı şeritte çıkar; arayüz yazısı (görev hedefi, gösterge) uzun süre aynı yerde aynı kalır, menü
/// kısa ve noktalamasız satırlardır, kumanda ipucu düğme adı taşır. Şerit, son bir buçuk dakikada görülen açıkça konuşma
/// olan satırların en çok toplandığı yerdir. Konuşmacısı belli satırlar ("Ad: söz" ya da üstünde büyük harfli ad satırı)
/// üç kat sayılır. Şerit, başka bir yer belirgin biçimde ağır basınca değişir; konuşmacılı satırlarla kurulmuş şeridi
/// yalnız konuşmacılı satırlar yerinden eder. Kutular karenin tamamına oranladır (0–1). Durum tutar; tek iş
/// parçacığından çağrılır.
public final class SubtitleFinder {
    public enum Verdict: Equatable, Sendable {
        case subtitle, prompt, menu, staticText, offCenter, notSentence, outsideBand
    }

    public struct Finding: Equatable, Sendable {
        public var line: TextLine
        public var verdict: Verdict
    }

    /// Öğrenilen altyazı şeridi (karenin yüksekliğine oranla: üst, alt); henüz öğrenilmediyse nil.
    public private(set) var band: (top: Double, bottom: Double)?

    /// Şeridin kurulması için gereken kanıt ağırlığı (düz satır 1, konuşmacılı satır `speakerWeight`).
    public static let weightToLearn = 4
    /// Konuşmacısı belli satırın ağırlığı.
    public static let speakerWeight = 3
    /// Başka bir yerin şeridi yerinden etmesi için şeritteki kanıttan bu kadar ağır basması gerekir.
    public static let weightToMove = 3
    /// Kanıt bu kadar süre geçerlidir (ms).
    public static let evidenceMs: Int64 = 90000
    /// Aynı yerde aynı yazı bu kadar süre kalırsa arayüzdür (ms).
    public static let staticAfterMs: Int64 = 9000
    /// Satırın ortası ekranın ortasından en çok bu kadar uzak olabilir.
    public static let centerTolerance = 0.12
    /// Okunan geniş alandan altyazı seçilirken satırın ortası ekranın ortasından en çok bu kadar uzak olabilir.
    public static let coreCenterTolerance = 0.22
    /// Ortaları birbirine bu kadar yakın satırlar aynı yerdedir (iki satırlı altyazının iki satırı).
    private static let samePlace = 0.05

    private struct Seen { var first: Int64; var last: Int64 }
    private struct Evidence { var middle: Double; var height: Double; var text: String; var at: Int64; var weight: Int }

    private var seen: [String: Seen] = [:]
    private var evidence: [Evidence] = []
    /// Şerit konuşmacılı satırlarla mı kuruldu (öyleyse yalnız konuşmacılı satırlar yerinden eder).
    private var bandHasSpeakers = false

    public init() {}

    /// Geniş bir alanın okumasından altyazı bloğunu seçer: şeridin içindeki ve ekranın ortasına yakın satırlar
    /// çekirdektir; onlara dikeyde bitişik ve yatayda hizalı satırlar da (altyazının üçüncü, dördüncü satırı, üstteki
    /// konuşmacı adı) bloğa katılır. Şeritte hiç satır yoksa okuma boştur. Kutular karenin tamamına oranladır.
    public static func grow(_ lines: [TextLine], band: (top: Double, bottom: Double)) -> [TextLine] {
        let placed = lines.filter { $0.box != nil }
        if placed.isEmpty { return lines }
        var kept = placed.filter { line in
            let box = line.box!
            let middle = (box.top + box.bottom) / 2
            return middle >= band.top && middle <= band.bottom && abs((box.left + box.right) / 2 - 0.5) <= coreCenterTolerance
        }
        var grew = !kept.isEmpty
        while grew {
            grew = false
            for line in placed {
                if kept.contains(line) { continue }
                if !kept.contains(where: { adjoins(line.box!, $0.box!) }) { continue }
                kept.append(line)
                grew = true
            }
        }
        return lines.filter { kept.contains($0) }
    }

    /// Şerit henüz öğrenilmeden geniş alan okunur; orada ekranın ortasından belirgin biçimde uzaktaki satırlar (köşe
    /// yazıları, göstergeler) atılır (Windows: AppController.WithoutOffCenterLines).
    public static func withoutOffCenterLines(_ lines: [TextLine]) -> [TextLine] {
        if lines.isEmpty { return lines }
        return lines.filter { line in
            guard let box = line.box else { return true }
            return abs((box.left + box.right) / 2 - 0.5) <= 0.2
        }
    }

    /// İki satır alt alta mı: aralarındaki boşluk bir satır yüksekliğinden az ve yatayda hizalı.
    private static func adjoins(_ a: TextBox, _ b: TextBox) -> Bool {
        let height = max(a.bottom - a.top, b.bottom - b.top)
        let gap = max(a.top, b.top) - min(a.bottom, b.bottom)
        if gap > height * 0.9 { return false }
        let overlap = min(a.right, b.right) - max(a.left, b.left)
        let narrower = min(a.right - a.left, b.right - b.left)
        return abs((a.left + a.right) / 2 - (b.left + b.right) / 2) <= centerTolerance || (overlap > 0 && overlap >= narrower * 0.5)
    }

    /// Konuşma satırı gibi mi: en az iki sözcük ya da noktalamayla biten tek sözcük ("Run!"), harflerinin çoğu küçük
    /// harf ya da cümle sonu işaretiyle biter (tümü büyük harfli kısa yazılar çoğunlukla başlık ve göstergedir).
    static func looksSpoken(_ text: String) -> Bool {
        let words = text.whitespaceTokens.filter { $0.contains(where: \.isLetter) }
        if words.isEmpty { return false }
        let ends = SubtitleStabilizer.endsSentence(text)
        if words.count <= 2 && !ends { return false }
        let letters = text.filter(\.isLetter)
        if letters.count < 3 { return false }
        let lower = letters.filter(\.isLowercase).count
        return lower * 2 >= letters.count || (ends && words.count >= 2)
    }

    /// Şeridi öğretecek kadar açık konuşma: en az üç sözcük ve ya cümle sonu işaretiyle biter ya da "Ad:" ile başlar.
    static func isClearlySpoken(_ text: String) -> Bool {
        let count = text.whitespaceTokens.filter { $0.contains(where: \.isLetter) }.count
        if count < 3 || !looksSpoken(text) { return false }
        let colon = text.characterOffset(of: ":")
        let labelled = (2...30).contains(colon) && text.first!.isUppercase && colon < text.count - 2
        return labelled || SubtitleStabilizer.endsSentence(text)
    }

    /// Tümü büyük harfli, en çok üç sözcüklü ad satırı ("MARKOS").
    static func isSpeakerLine(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let letters = trimmed.filter(\.isLetter)
        return letters.count >= 2 && letters.allSatisfy(\.isUppercase) && trimmed.whitespaceTokens.count <= 3 &&
            trimmed.allSatisfy { $0.isLetter || $0 == " " || $0 == "-" || $0 == "." || $0 == "'" || $0 == "’" }
    }

    /// Alt alta dizilmiş, sola hizalı ya da aynı ortalı, kısa ve noktalamasız üç ve daha çok satır menüdür.
    private static func menuLines(_ lines: [TextLine]) -> Set<TextLine> {
        var result = Set<TextLine>()
        let shorts = lines.enumerated()
            .filter { $0.element.box != nil && $0.element.text.whitespaceTokens.count <= 3 && !SubtitleStabilizer.endsSentence($0.element.text) }
            .sorted { $0.element.box!.top != $1.element.box!.top ? $0.element.box!.top < $1.element.box!.top : $0.offset < $1.offset }
            .map(\.element)
        for line in shorts {
            let box = line.box!
            let column = shorts.filter { other in
                let o = other.box!
                return abs(o.left - box.left) <= 0.02 || abs((o.left + o.right) / 2 - (box.left + box.right) / 2) <= 0.02
            }
            if column.count >= 3 { result.formUnion(column) }
        }
        return result
    }

    /// Aynı yerdeki aynı yazı: yer yüzde ikilik ızgaraya yuvarlanır (yazı tanıma kutuları kare kare oynar).
    private static func key(_ text: String, _ box: TextBox) -> String {
        "\(roundEven((box.left + box.right) / 2 / 0.02)):\(roundEven((box.top + box.bottom) / 2 / 0.02)):\(TextMatch.normalize(text))"
    }

    /// C#'taki Math.Round (yarıda çifte yuvarlar).
    private static func roundEven(_ value: Double) -> Int { Int(value.rounded(.toNearestOrEven)) }

    private static func median(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        return sorted.count % 2 == 1 ? sorted[sorted.count / 2] : (sorted[sorted.count / 2 - 1] + sorted[sorted.count / 2]) / 2
    }

    /// Satır ortalarının ortancası çevresinde şerit: yukarı ve aşağı iki buçuk satır yüksekliği pay bırakılır.
    private static func bandOf(_ lines: [(middle: Double, height: Double)]) -> (top: Double, bottom: Double) {
        let middle = median(lines.map(\.middle))
        let height = max(0.015, median(lines.map(\.height)))
        return (max(0, middle - height * 2.5), min(1, middle + height * 2.5))
    }

    /// Karenin tamamının okumasını sınıflar. `now` milisaniyedir (tekdüze artan saat).
    public func classify(_ lines: [TextLine], now: Int64) -> [Finding] {
        forget(now)
        let menu = Self.menuLines(lines)
        var findings: [Finding] = []
        for line in lines {
            guard let box = line.box else { continue }
            if line.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            let key = Self.key(line.text, box)
            let first = seen[key]?.first ?? now
            seen[key] = Seen(first: first, last: now)

            let verdict: Verdict
            if ButtonPrompts.isPrompt(line.text) {
                verdict = .prompt
            } else if menu.contains(line) {
                verdict = .menu
            } else if now - first >= Self.staticAfterMs {
                verdict = .staticText
            } else if abs((box.left + box.right) / 2 - 0.5) > Self.centerTolerance {
                verdict = .offCenter
            } else if !Self.looksSpoken(line.text) {
                verdict = .notSentence
            } else {
                verdict = inBand(box) ? .subtitle : .outsideBand
            }
            // Şeridi yalnız açıkça konuşma olan satırlar belirler; "Spider Barrage" gibi kısa yazılar belirlemez.
            if verdict == .subtitle || verdict == .outsideBand {
                let speaker = hasSpeaker(line, lines)
                if speaker || Self.isClearlySpoken(line.text) { note(line.text, box, now, speaker ? Self.speakerWeight : 1) }
            }
            findings.append(Finding(line: line, verdict: verdict))
        }
        if relearn(now) {
            // Şerit değişti: bu karedeki satırlar yeni şeride göre yeniden değerlendirilir.
            for i in findings.indices {
                guard let box = findings[i].line.box else { continue }
                if findings[i].verdict == .subtitle || findings[i].verdict == .outsideBand {
                    findings[i].verdict = inBand(box) ? .subtitle : .outsideBand
                }
            }
        }
        return findings
    }

    /// Altyazı sayılan satırlar, yukarıdan aşağı.
    public func subtitles(_ lines: [TextLine], now: Int64) -> [TextLine] {
        classify(lines, now: now).filter { $0.verdict == .subtitle }.map(\.line)
    }

    private func inBand(_ box: TextBox) -> Bool {
        guard let current = band else { return true }
        let middle = (box.top + box.bottom) / 2
        return middle >= current.top && middle <= current.bottom
    }

    /// Satırın konuşmacısı belli mi: "Ad: söz" biçiminde başlar ya da hemen üstünde, ortalı, büyük harfli kısa bir ad
    /// satırı vardır ("MARKOS" / "We have no option but to try!").
    private func hasSpeaker(_ line: TextLine, _ lines: [TextLine]) -> Bool {
        let text = line.text.trimStart(" \t\n\r")
        let colon = text.characterOffset(of: ": ")
        if (2...30).contains(colon), text.first!.isUppercase {
            let name = text.slice(0, colon)
            if name.splitKeepingEmpty(" ").count <= 3 &&
                name.allSatisfy({ $0.isLetter || $0 == " " || $0 == "-" || $0 == "." || $0 == "'" || $0 == "’" }) {
                return true
            }
        }
        guard let box = line.box else { return false }
        let height = box.bottom - box.top
        return lines.contains { other in
            guard let above = other.box else { return false }
            return Self.isSpeakerLine(other.text) && above.bottom <= box.top + height * 0.5 && box.top - above.bottom <= height * 1.5 &&
                abs((above.left + above.right) / 2 - (box.left + box.right) / 2) <= Self.centerTolerance
        }
    }

    private func note(_ text: String, _ box: TextBox, _ now: Int64, _ weight: Int) {
        let normal = TextMatch.normalize(text)
        // Aynı satır yeniden görülünce tarihi yenilenir; ağırlığı artmaz (ekranda duran yazı şeridi çekmesin).
        if let known = evidence.firstIndex(where: { $0.text == normal }) {
            evidence[known].at = now
            evidence[known].weight = max(evidence[known].weight, weight)
        } else {
            evidence.append(Evidence(middle: (box.top + box.bottom) / 2, height: box.bottom - box.top, text: normal, at: now, weight: weight))
        }
        if evidence.count > 40 { evidence.removeFirst() }
    }

    /// Kanıtın en çok toplandığı yeri bulur; şerit yoksa kurar, başka yer ağır basıyorsa oraya taşır. Değiştiyse true.
    private func relearn(_ now: Int64) -> Bool {
        evidence.removeAll { now - $0.at > Self.evidenceMs }
        if evidence.isEmpty { return false }
        func around(_ middle: Double) -> [Evidence] { evidence.filter { abs($0.middle - middle) <= Self.samePlace } }
        func weight(_ items: [Evidence]) -> Int { items.reduce(0) { $0 + $1.weight } }
        // En ağır küme; eşitlikte en yeni kanıtı olan (ilk görülen kazanır).
        var best: [Evidence] = []
        for item in evidence {
            let cluster = around(item.middle)
            if best.isEmpty { best = cluster; continue }
            let w = weight(cluster), bw = weight(best)
            if w > bw || (w == bw && cluster.map(\.at).max()! > best.map(\.at).max()!) { best = cluster }
        }
        let total = weight(best)
        let speakers = best.contains { $0.weight >= Self.speakerWeight }
        let candidate = Self.bandOf(best.map { ($0.middle, $0.height) })

        guard let current = band else {
            if total < Self.weightToLearn { return false }
            band = candidate
            bandHasSpeakers = speakers
            return true
        }
        let inBand = evidence.filter { $0.middle >= current.top && $0.middle <= current.bottom }
        let center = Self.median(best.map(\.middle))
        if center >= current.top && center <= current.bottom {
            // Aynı yer: şerit ince ayarlanır.
            bandHasSpeakers = bandHasSpeakers || speakers
            if abs(candidate.top - current.top) < 0.005 && abs(candidate.bottom - current.bottom) < 0.005 { return false }
            band = candidate
            return true
        }
        // Başka yer: belirgin biçimde ağır basmalı; konuşmacılı şeridi konuşmacısız yazılar (ipuçları) yerinden etmez.
        let stillSpeakers = bandHasSpeakers && inBand.contains { $0.weight >= Self.speakerWeight }
        // Konuşmacılı satırlar konuşmacısız bir şeridi (ipuçlarından öğrenilmiş) eşit ağırlıkta bile yerinden eder.
        let margin = speakers && !stillSpeakers ? 0 : Self.weightToMove
        if total < weight(inBand) + margin || (stillSpeakers && !speakers) || total < Self.weightToLearn { return false }
        band = candidate
        bandHasSpeakers = speakers
        return true
    }

    /// Bir süredir görülmeyen yazılar unutulur (aynı altyazı sonra yeniden gelirse sabit sayılmasın).
    private func forget(_ now: Int64) {
        seen = seen.filter { now - $0.value.last <= 4000 }
    }
}
