// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Oyunlar uzun cümleyi art arda gelen birkaç altyazıya böler ("You're the ones" → "who are gonna need help..." →
/// "when they come for you."). Parçalar tek tek çevrilince anlam dağılır. Bu sarmalayıcı, cümlesi bitmemiş satırın
/// ardından aynı konuşmacıdan gelen devamı önceki parçayla birlikte çevirir ve cümlenin o ana kadarki tamamının
/// çevirisini verir. Konuşmacı adı çevirmene verilmez, olduğu gibi başa konur; birkaç kişi konuşuyorsa her konuşma ayrı
/// çevrilir. Aynı okumada iki kez gelen satırı teke indirir. Windows'taki ContinuationTranslator.cs ve Android'deki
/// ContinuationTranslator.kt ile aynı kurallar. `inner` nil dönerse (çeviremedi) sonuç da nil'dir.
public final class ContinuationTranslator: @unchecked Sendable {
    /// Önceki parçadan en çok bu kadar sonra gelen satır onun devamı sayılabilir.
    public static let withinMs: Int64 = 8000
    /// Birleşen cümle bundan uzunsa yeni satır tek başına çevrilir (zincir sonsuza uzamasın).
    public static let maxLength = 260

    public typealias Inner = (String) async throws -> String?

    private static let speakerLabel = try! NSRegularExpression(pattern: "^(\\p{Lu}[\\p{L}.’'-]*(?: \\p{Lu}[\\p{L}.’'-]*){0,2}):\\s")
    private static let bareLabel = try! NSRegularExpression(pattern: "^\\p{Lu}[\\p{L}.’'-]*(?: \\p{Lu}[\\p{L}.’'-]*){0,2}:$")
    // Ayraç simge, yıldız, tire ya da bunların noktayla karışığıdır; tek başına nokta ayraç değildir. Addan önce tek bir
    // artık harf olabilir ("Z YERMAK•. come here").
    private static let iconLabel = try! NSRegularExpression(pattern:
        "^(?:[\\p{Lu}\\d] )?(\\p{Lu}[\\p{Lu}'’-]{2,14}(?: \\p{Lu}[\\p{Lu}'’-]{1,14})?)\\s*" +
        "(?=[•·▸►>▶‣◆■*\\-–—.]*[•·▸►>▶‣◆■*\\-–—])[•·▸►>▶‣◆■*\\-–—.]+\\s*(\\S.*)$")

    /// Cümlenin ortasında kalındığını gösteren son sözcükler (edat, bağlaç, tanımlık).
    private static let connectors: Set<String> = [
        "a", "an", "the", "of", "to", "in", "on", "at", "by", "for", "from", "with", "without", "into", "onto", "over", "under",
        "beneath", "between", "through", "about", "against", "and", "or", "but", "that", "which", "who", "whose", "than", "as",
        "if", "because", "when", "while", "my", "your", "his", "her", "our", "their", "its", "this", "these", "those",
    ]

    private struct Previous {
        let speaker: String?
        let body: String
        let raw: String
        let at: Int64
    }

    private let inner: Inner
    private let clock: () -> Int64
    private let looksMisread: ((String) -> Bool)?
    private let names: SpeakerNames
    private let phrasebook: ((String) -> String?)?
    private let lock = NSLock()
    private var previous: Previous?

    /// `looksMisread`: bozuk okunmuş parça mı (`Misspelling.looksMisread`); bozuk parçalar birbirine eklenmez.
    /// `names`: konuşmacı adları (bozuk okunan adı doğrusuna çevirmek için). `phrasebook`: kısa ünlemlerin hazır karşılığı
    /// (`ShortPhrases.translate`; İngilizce→Türkçe); verilirse ve sözün karşılığı varsa söz çevirmene gönderilmez.
    public init(inner: @escaping Inner, clock: (() -> Int64)? = nil, looksMisread: ((String) -> Bool)? = nil,
                names: SpeakerNames = SpeakerNames(), phrasebook: ((String) -> String?)? = nil) {
        self.inner = inner
        self.clock = clock ?? { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
        self.looksMisread = looksMisread
        self.names = names
        self.phrasebook = phrasebook
    }

    /// Büyük harfle yazılan adda yazı tanımanın küçük okuduğu "i" büyütülür ("DAMiR", "SOLDiER").
    static func capsName(_ name: String) -> String { SpeakerNames.fixCaps(name) }

    private static func rows(_ text: String) -> [String] {
        text.split(separator: "\n", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    private static func firstMatch(_ regex: NSRegularExpression, _ text: String) -> NSTextCheckingResult? {
        regex.firstMatch(in: text, range: Regexes.whole(text))
    }

    /// Alt alta gelen neredeyse aynı satırlardan (aynı altyazının iki okuması) daha düzgün okunan kalır.
    public static func withoutRepeatedLines(_ text: String) -> String {
        let lines = rows(text)
        guard lines.count >= 2 else { return text.trimmingCharacters(in: .whitespacesAndNewlines) }
        var kept: [String] = []
        for line in lines {
            // Tekrar hemen alttaki satır olmayabilir. Önceki satırın sonunun bozuk bir kopyası da tekrardır.
            if let twin = kept.firstIndex(where: { earlier in
                TextMatch.isSimilar(TextMatch.normalize(earlier), TextMatch.normalize(line), threshold: 0.8) || isTailCopy(line, earlier)
            }) {
                // Puan eşitse sözcükleri ayrı okunan kazanır ("yowwill" yerine "you will").
                let better = OcrNoise.score(line), current = OcrNoise.score(kept[twin])
                if better > current || (better == current && line.filter({ $0 == " " }).count > kept[twin].filter({ $0 == " " }).count) {
                    kept[twin] = line
                }
                continue
            }
            kept.append(line)
        }
        return kept.joined(separator: "\n")
    }

    private static func isTailCopy(_ line: String, _ earlier: String) -> Bool {
        let a = TextMatch.normalize(line).split(separator: " "), b = TextMatch.normalize(earlier).split(separator: " ")
        return a.count >= 3 && a.count <= b.count && a[a.count - 1] == b[b.count - 1] && a[a.count - 2] == b[b.count - 2]
    }

    /// Bazı oyunlar adı iki nokta yerine simgeyle ayırır ("CAL • Think BD wants…"); "Ad: söz" biçimine çevrilir.
    private static func withIconLabels(_ text: String) -> String {
        var rows = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        var changed = false
        for i in rows.indices {
            let row = rows[i].trimmingCharacters(in: .whitespaces)
            guard let match = firstMatch(iconLabel, row) else { continue }
            let ns = row as NSString
            rows[i] = Regexes.group(match, 1, in: ns) + ": " + Regexes.group(match, 2, in: ns).trimmingCharacters(in: .whitespaces)
            changed = true
        }
        return changed ? rows.joined(separator: "\n") : text
    }

    /// Sözü olmayan ad satırı ("… / Mimir:": bir sonraki konuşmanın önce beliren adı) başka satır varsa atılır.
    private static func withoutBareLabels(_ text: String) -> String {
        let all = rows(text)
        let kept = all.filter { firstMatch(bareLabel, $0) == nil }
        return kept.count == all.count || kept.isEmpty ? text : kept.joined(separator: "\n")
    }

    /// Okumayı konuşmalara böler: konuşmacı adıyla başlayan her satır yeni bir konuşmadır, adsız satırlar bir öncekinin
    /// devamıdır. İkiden az konuşmacı varsa nil.
    private static func turns(_ source: String) -> [(speaker: String?, body: String)]? {
        let all = rows(source)
        let labelled = all.filter { firstMatch(speakerLabel, $0) != nil }.count
        let tailThenSpeaker = all.count > 1 && firstMatch(speakerLabel, all[0]) == nil && labelled >= 1
        if labelled < 2 && !tailThenSpeaker { return nil }
        var turns: [(speaker: String?, body: String)] = []
        for row in all {
            let (speaker, body) = split(row)
            if speaker == nil, let last = turns.last {
                turns[turns.count - 1] = (last.speaker, (last.body + " " + body).trimmingCharacters(in: .whitespaces))
            } else {
                turns.append((speaker, body))
            }
        }
        return turns
    }

    /// En az iki harfli bir sözcüğü olan ve rakamla başlamayan yazı.
    private static func isSpeech(_ text: String) -> Bool {
        guard let first = text.first(where: { !$0.isWhitespace }) else { return false }
        return !first.isNumber && text.filter(\.isLetter).count >= 3
    }

    /// Okunan ad, bilinen adın birkaç harfi eksik ya da yanlış hâli mi ("anke", "Gankc" → "Ganke").
    private static func sameName(_ read: String, _ known: String) -> Bool {
        let a = read.trimmingCharacters(in: CharacterSet(charactersIn: "-—_ .•")).lowercased()
        let b = known.lowercased()
        guard a.count >= 3, b.count >= 4 else { return false }
        return b.hasSuffix(a) || TextMatch.isSimilar(a, b, threshold: 0.75)
    }

    private static func sameLine(_ a: String, _ b: String) -> Bool {
        let left = TextMatch.normalize(a), right = TextMatch.normalize(b)
        return left == right || TextMatch.isSimilar(left, right, threshold: 0.8)
            || (left.count >= 8 && right.count >= 8 && (left.contains(right) || right.contains(left)))
    }

    /// "Flint Marko: who are gonna need help..." → ("Flint Marko", "who are gonna need help...").
    private static func split(_ text: String) -> (String?, String) {
        guard let match = firstMatch(speakerLabel, text) else { return (nil, text.trimmingCharacters(in: .whitespacesAndNewlines)) }
        let ns = text as NSString
        let name = Regexes.group(match, 1, in: ns)
        var body = ns.substring(from: match.range.length).trimmingCharacters(in: .whitespacesAndNewlines)
        // Satır değişirken ad iki kez okunabiliyor ("Faye: Faye: This way.").
        while true {
            let rest = String(body.drop(while: { "\"“'•-—( ".contains($0) }))
            let restChars = Array(rest)
            let nameCount = name.count
            // Virgül sayılmaz: "Sam: Sam, wait!" seslenmedir.
            if rest.hasPrefix(name) && restChars.count >= nameCount + 2 && ":.;•".contains(restChars[nameCount]) {
                body = String(restChars[(nameCount + 1)...]).trimmingCharacters(in: .whitespacesAndNewlines)
                continue
            }
            // İki sözcüklü ad noktalamasız yinelenmiş: "Woman's Voice: Woman's Voice The Zero Dawn system".
            if name.contains(" ") && rest.hasPrefix(name + " ") && restChars.count > nameCount + 2 && restChars[nameCount + 1].isUppercase {
                body = String(restChars[(nameCount + 1)...]).trimmingCharacters(in: .whitespacesAndNewlines)
                continue
            }
            // Adın bozuk ikinci okuması ("Lae'zel: Lae'ze: It cannot be helped.").
            guard let colonRange = rest.range(of: ": ") else { break }
            let colon = rest.distance(from: rest.startIndex, to: colonRange.lowerBound)
            let head = String(rest[..<colonRange.lowerBound])
            if colon < 3 || colon > nameCount + 2 || head.contains(" ") != name.contains(" ") || !sameName(head, name) { break }
            body = String(rest[colonRange.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        // Addan sonra tek başına okunan harf, konuşmacı simgesinin artığıdır ("Faye: S This way."). "I" ve "A" sözdür.
        let chars = Array(body)
        if chars.count > 3 && chars[0].isUppercase && chars[0] != "I" && chars[0] != "A" && chars[1] == " " && chars[2].isUppercase {
            body = String(chars[2...])
        }
        return (name, body)
    }

    public func translate(_ text: String) async throws -> String? {
        let source = relabel(Self.withoutBareLabels(Self.withIconLabels(Self.withoutRepeatedLines(text))))
        // Birkaç kişi konuşuyorsa her konuşma kendi adıyla ayrı çevrilir; adlar çevrilmez.
        if let all = Self.turns(source), all.count > 1 {
            // Sözü olmayan konuşma (bir sonraki konuşmanın önce beliren adı) gösterilmez.
            let spokenTurns = all.filter { $0.body.contains(where: \.isLetter) }
            let spoken = spokenTurns.isEmpty ? all : spokenTurns
            lock.withLock { previous = nil }
            var parts: [String] = []
            for turn in spoken {
                guard let part = try await translateTurn(turn.speaker, turn.body) else { return nil }
                parts.append(part)
            }
            return parts.joined(separator: "\n")
        }
        let (speaker, body) = lock.withLock { combine(source) }
        return try await translateTurn(speaker, body)
    }

    /// İki nokta üst üstesi okunmamış konuşmacı adını ("Stintsy Fuck, he's here.") oyunda daha önce görülen adla
    /// "Stints: Fuck, he's here." biçimine getirir.
    private func relabel(_ source: String) -> String {
        var rows = source.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        var changed = false
        for i in rows.indices {
            let row = rows[i].trimmingCharacters(in: .whitespaces)
            if row.isEmpty || Self.firstMatch(Self.speakerLabel, row) != nil { continue }
            // Kotlin'deki split(' ', limit = 4): en çok dört parça, sonuncusu satırın geri kalanı.
            let words = row.split(separator: " ", maxSplits: 3, omittingEmptySubsequences: false).map(String.init).filter { !$0.isEmpty }
            // İki sözcüklü ad: "Charles Rookwoods Jelve done…".
            if words.count >= 3, let longName = names.labelIn(words[0] + " " + words[1], next: words[2]) {
                rows[i] = longName + ": " + words.dropFirst(2).joined(separator: " ")
                changed = true
                continue
            }
            guard words.count >= 2, let name = names.labelIn(words[0], next: words[1]), let space = row.firstIndex(of: " ") else { continue }
            rows[i] = name + ": " + String(row[row.index(after: space)...].drop(while: \.isWhitespace))
            changed = true
        }
        return changed ? rows.joined(separator: "\n") : source
    }

    /// Sözü çevirir, konuşmacı adını olduğu gibi başına koyar. Ad çevirmene verilmez: çevirmen onu sıradan sözcük sanıp
    /// çeviriyor ("ANNA:" → "ANNE:", "MILLER:" → "DEĞİRMENCİ:").
    private func translateTurn(_ speakerRead: String?, _ bodyRead: String) async throws -> String? {
        var speaker = speakerRead
        var body = bodyRead
        // Gerçek adın önüne çöp bir "ad" eklenmiş ("QHI: Mimir: Uzun zaman oldu..."): söz bilinen bir adla başlıyorsa ve
        // öndeki ad bilinmiyorsa konuşmacı içteki addır.
        if let outer = speaker, let match = Self.firstMatch(Self.speakerLabel, body) {
            let ns = body as NSString
            let innerName = Regexes.group(match, 1, in: ns)
            if names.isKnown(innerName) && !names.isKnown(outer) {
                speaker = innerName
                body = ns.substring(from: match.range.length).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        // Bozuk okunmuş ad ("Minnir") oyunda sık geçen doğrusuna ("Mimir") çevrilir.
        if let read = speaker { speaker = Self.capsName(names.canonical(read)) }
        if body.isEmpty { return speaker.map { $0 + ":" } ?? "" }
        let ready = phrasebook?(body)
        let radio = ready == nil && phrasebook != nil ? ShortPhrases.splitRadioTail(body) : nil
        let translated: String
        if let ready {
            translated = ready
        } else if let radio {
            guard let main = try await inner(radio.main)?.trimmingCharacters(in: .whitespacesAndNewlines) else { return nil }
            translated = ShortPhrases.fixShouting(source: radio.main, translation: main) + " " + radio.tail
        } else {
            guard let result = try await inner(body)?.trimmingCharacters(in: .whitespacesAndNewlines) else { return nil }
            translated = ShortPhrases.fixShouting(source: body, translation: result)
        }
        return speaker.map { $0 + ": " + translated } ?? translated
    }

    /// Satır önceki parçanın devamıysa ikisi birlikte, değilse kendisi. Durumu günceller; kilit altında çağrılır.
    private func combine(_ raw: String) -> (String?, String) {
        var source = raw
        var (speaker, body) = Self.split(source)
        let at = clock()
        // Konuşmacı adı bozuk okunmuşsa ("anke: …", önceki satır "Ganke: …") ad sözün parçası sayılmasın.
        if speaker == nil, let before = previous, let known = before.speaker, at - before.at <= Self.withinMs,
           let colonRange = body.range(of: ": ") {
            let colon = body.distance(from: body.startIndex, to: colonRange.lowerBound)
            let head = String(body[..<colonRange.lowerBound])
            if colon >= 2 && colon <= 24 && head.contains(" ") == known.contains(" ") && Self.sameName(head, known) {
                speaker = known
                body = String(body[colonRange.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
                source = known + ": " + body
            }
        }
        // Ekranda iki üç satıra sarılmış söz tek cümledir; satır başında konuşmacı tiresi varsa ayrı konuşmalardır.
        if body.contains("\n") {
            let lines = Self.rows(body)
            if !lines.dropFirst().contains(where: { $0.hasPrefix("-") || $0.hasPrefix("–") || $0.hasPrefix("—") || $0.hasPrefix(">>") }) {
                body = lines.joined(separator: " ")
                source = speaker.map { $0 + ": " + body } ?? body
            }
        }
        var whole = body
        if let last = previous, at - last.at <= Self.withinMs, !body.contains("\n"), !last.body.contains("\n"),
           speaker == nil || speaker == last.speaker, continues(last.body, body), !Self.sameLine(last.raw, source),
           last.body.utf16.count + body.utf16.count + 1 <= Self.maxLength {
            whole = last.body + " " + body
            if speaker == nil { speaker = last.speaker }
        }
        // Aynı satırın yeniden okunması zinciri ilerletmez (yoksa satır kendisiyle birleşirdi).
        if previous == nil || !Self.sameLine(previous!.raw, source) { previous = Previous(speaker: speaker, body: whole, raw: source, at: at) }
        return (speaker, whole)
    }

    /// Önceki parça cümleyi bitirmediyse ya da yeni parça küçük harfle başlıyorsa devamdır.
    private func continues(_ before: String, _ after: String) -> Bool {
        guard let first = after.first(where: \.isLetter) else { return false }
        // Gösterge ve sayaç yazıları ("19m", "x3") söz değildir; birbirine eklenmez.
        if !Self.isSpeech(before) || !Self.isSpeech(after) { return false }
        // Bozuk okunmuş parçalar birbirine eklenmez.
        if let looksMisread, looksMisread(before) || looksMisread(after) { return false }
        // Sondaki tırnak ve yazı tanıma artıkları sayılmaz.
        var trimmed = before
        while let last = trimmed.last, last.isWhitespace || "'\"’”`".contains(last) { trimmed.removeLast() }
        // Baştaki tek harf ("r and Hit Indicators…") yarım okunmuş başka bir yazıdır, devam değil.
        let head = String(after.drop(while: \.isWhitespace).split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
            .trimmingCharacters(in: CharacterSet(charactersIn: ".,'\""))
        if first.isLowercase && head.count == 1 && head != "a" && head != "i" { return false }
        // Küçük harfle başlayan satır her zaman bir öncekinin devamıdır.
        if first.isLowercase {
            return (trimmed.last.map { !".!?".contains($0) } ?? false) || trimmed.hasSuffix("...") || trimmed.hasSuffix("…")
        }
        // Büyük harfle başlayan satır yalnız önceki söz açıkça yarıda kaldıysa devamdır.
        if trimmed.hasSuffix(",") || trimmed.hasSuffix(";") { return true }
        guard let lastWord = trimmed.split(separator: " ").last?.lowercased() else { return false }
        return Self.connectors.contains(lastWord)
    }
}
