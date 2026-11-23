// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Yaygın İngilizce sözcükler (SCOWL/ESDB 60 boyutu, küçük harf): Windows uygulamasının english-words.txt.gz dosyasının
/// kendisi (Windows: WindowsSpelling.EnglishListCheck, Android: EnglishWords.kt, aynı kurallar). Sözcükler tek bir bayt
/// bloğunda sıralı tutulur, ikili aramayla bulunur.
public final class EnglishWords: @unchecked Sendable {
    private let blob: [UInt8]
    private let starts: [Int]

    private init(blob: [UInt8], starts: [Int]) {
        self.blob = blob
        self.starts = starts
    }

    public var count: Int { starts.count - 1 }

    /// Uygulamayla gelen liste (Resources/english-words.txt.gz); okunamazsa boş.
    public static let builtIn: EnglishWords = {
        guard let url = Bundle.module.url(forResource: "english-words.txt", withExtension: "gz"),
              let data = try? Data(contentsOf: url), let words = try? load(data) else { return of([]) }
        return words
    }()

    /// Satır satır sözcük listesi; gzip'liyse açılır.
    public static func load(_ data: Data) throws -> EnglishWords {
        let text = Gzip.isGzip(data) ? try Gzip.decompress(data) : data
        let words = String(decoding: text, as: UTF8.self).split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        return build(words.map { Array($0.utf8) })
    }

    public static func of(_ words: [String]) -> EnglishWords {
        build(words.map { Array($0.lowercased().utf8) })
    }

    private static func build(_ list: [[UInt8]]) -> EnglishWords {
        let sorted = list.sorted { $0.lexicographicallyPrecedes($1) }
        var starts: [Int] = []
        var blob: [UInt8] = []
        starts.reserveCapacity(sorted.count + 1)
        for word in sorted {
            starts.append(blob.count)
            blob.append(contentsOf: word)
        }
        starts.append(blob.count)
        return EnglishWords(blob: blob, starts: starts)
    }

    /// Sözcük listede mi ("'s", sonu düşmüş "-ing" ve tireli parçalar dahil).
    public func isWord(_ word: String) -> Bool {
        let lower = word.lowercased().replacingOccurrences(of: "’", with: "'")
        if contains(lower) { return true }
        if lower.hasSuffix("'s") && contains(String(lower.dropLast(2))) { return true }
        if lower.hasSuffix("s'") && contains(String(lower.dropLast())) { return true }
        if lower.hasSuffix("in'") && contains(String(lower.dropLast()) + "g") { return true }
        if lower.hasSuffix("in") && lower.count > 4 && contains(lower + "g") { return true }
        if lower.contains("-") { return lower.split(separator: "-", omittingEmptySubsequences: false).allSatisfy { $0.isEmpty || contains(String($0)) } }
        return false
    }

    /// Tam eşleşme (küçük harfli liste).
    public func contains(_ word: String) -> Bool {
        guard !word.isEmpty else { return false }
        let key = Array(word.utf8)
        var low = 0, high = count - 1
        while low <= high {
            let middle = (low + high) / 2
            let order = compare(middle, key)
            if order == 0 { return true }
            if order < 0 { low = middle + 1 } else { high = middle - 1 }
        }
        return false
    }

    private func compare(_ index: Int, _ key: [UInt8]) -> Int {
        let start = starts[index]
        let length = starts[index + 1] - start
        for i in 0..<min(length, key.count) where blob[start + i] != key[i] { return Int(blob[start + i]) - Int(key[i]) }
        return length - key.count
    }
}

/// Okumanın yazım denetimine göre ne kadar bozuk olduğu (Windows: Misspelling.cs). Yazı tanıma hareketli sahnede harf
/// düşürür ya da sözcükleri yapıştırır ("Igot it", "gonnatry workaroun"); bunlar gerçek sözcük değildir ama simge
/// içermediği için `OcrNoise` yakalamaz. Sözcüğün gerçek olup olmadığını uygulamanın verdiği denetçi söyler. Özel adlar
/// yanlış sayılmasın diye büyük harfle başlayan düzgün yazılmış sözcükler sayılmaz; büyük-küçük harfi karışık olanlar sayılır.
public struct Misspelling {
    private let isWord: (String) -> Bool

    public init(isWord: @escaping (String) -> Bool) {
        self.isWord = isWord
    }

    /// Uygulamayla gelen İngilizce sözcük listesiyle.
    public static var english: Misspelling { Misspelling(isWord: EnglishWords.builtIn.isWord) }

    /// Bozuk sayılan sözcüklerin harf sayısı ve satırdaki toplam harf sayısı.
    public func measure(_ text: String) -> (bad: Int, all: Int) {
        var bad = 0, all = 0
        for token in text.split(whereSeparator: \.isWhitespace) {
            // Baştaki ve sondaki noktalama atılır; sözcük içindeki kesme ve tire kalır ("you're", "Spider-Man").
            let characters = Array(token)
            var start = 0, end = characters.count
            while start < end && !characters[start].isLetter { start += 1 }
            while end > start && !characters[end - 1].isLetter { end -= 1 }
            let word = String(characters[start..<end])
            let letters = word.filter(\.isLetter).count
            if letters == 0 { continue }
            all += letters
            // Tek harf ve kısaltmalar denetlenmez.
            if letters < 2 || word.allSatisfy({ !$0.isLetter || $0.isUppercase }) { continue }
            let proper = word.first!.isUppercase && word.dropFirst().filter(\.isLetter).allSatisfy(\.isLowercase)
            if proper {
                // "I" sonraki sözcüğe yapışmış ("Igot it", "Iknow"): ad değil, bozuk okumadır.
                if word.first == "I" && letters >= 3 && !isWord(word) && isWord(String(word.dropFirst())) {
                    bad += letters
                } else if letters > 13 && !word.contains("-") && !isWord(word) {
                    // Boşlukları okunmamış bütün satır ad değildir: adlar bu kadar uzun olmaz.
                    bad += letters
                }
                continue
            }
            if !isWord(word) && !isContraction(word) { bad += letters }
        }
        return (bad, all)
    }

    /// Yazı tanıma kesme işaretini sık sık görmez ("dont", "cant", "theyre"); kesme eklenince gerçek sözcük olan biçimler
    /// doğru sayılır. Konuşma dilindeki kısaltmalar da ("'em", "c'mon") öyle.
    private func isContraction(_ word: String) -> Bool {
        let lower = word.lowercased()
        if ["em", "cmon", "ya", "til", "cause"].contains(lower) { return true }
        if word.contains("'") || word.contains("’") || word.count < 3 { return false }
        for tail in [1, 2] where word.count > tail {
            if isWord(String(word.dropLast(tail)) + "'" + String(word.suffix(tail))) { return true }
        }
        return false
    }

    /// Satırda gerçek olmayan sözcük var mı (daha iyi bir okuma beklemeye değer mi).
    public func looksMisread(_ text: String) -> Bool { measure(text).bad > 0 }

    /// Okuma çevrilemeyecek kadar bozuk mu: harflerinin üçte birinden çoğu gerçek olmayan sözcüklerde.
    public func isGarbage(_ text: String) -> Bool {
        let (bad, all) = measure(text)
        return all >= 6 && bad * 3 > all
    }

    /// Okuma puanı: `OcrNoise.score` eksi bozuk sözcüklerin harflerinin üç katı.
    public func score(_ text: String) -> Int { OcrNoise.score(text) - 3 * measure(text).bad }

    /// Cümlenin başı okunamamış parça mı: küçük harfle başlar, konuşmacı adı yoktur ve bir önceki satır cümlesini
    /// bitirmiştir. Yazı belirirken ya da solarken yazı tanıma satırın yalnız sonunu okur ("bad at surrendering.").
    public static func isOrphanFragment(_ text: String, previous: String?) -> Bool {
        guard let first = text.first(where: { $0.isLetter || $0.isNumber }), first.isLowercase else { return false }
        guard let previous else { return true }
        var before = previous
        while let last = before.last, last.isWhitespace { before.removeLast() }
        while let last = before.last, "'\"’”".contains(last) { before.removeLast() }
        // Önceki satır açık kaldıysa küçük harfle başlayan satır onun devamıdır.
        guard let last = before.last else { return true }
        return ".!?".contains(last) && !before.hasSuffix("...")
    }
}

/// Ücretsiz (cihaz üstü) çevirinin ortak metin hattı (Windows: FreeTranslation.cs, aynı adımlar ve sabitler):
/// büyük harf düzeni → oyun deyimleri → terim koruma → çeviri → terim ve ekleri → Türkçe düzeltmeler. Kısa satır
/// (3 sözcük ve altı) önceki cümleyle birlikte ("***" ayracıyla) çevrilebilir; ayraçtan sonrası alınır, ayrım belirsizse
/// satır tek başına çevrilir.
public enum FreeTranslation {
    /// Çeviri öncesi/sonrası kuralların sürümü; kalıcı önbelleğin motor adına girer (Windows ile aynı).
    public static let pipelineVersion = 2
    /// Bu sözcük sayısının altındaki cümle önceki cümleyle birlikte çevrilir.
    public static let shortLineWords = 4
    /// Bağlam ile kısa satır arasındaki ayraç (OPUS-MT sözlüğünde "▁***" tek parça).
    public static let separator = " *** "
    private static let separatorCore = "***"
    /// Önbellek anahtarında bağlam ile satırı ayıran karakter.
    private static let keyJoin = "\u{1F}"

    public static func isShort(_ sentence: String) -> Bool {
        let words = sentence.split(whereSeparator: \.isWhitespace).filter { $0.contains(where: \.isLetter) }.count
        return words > 0 && words < shortLineWords
    }

    /// Cümlenin önbellek anahtarı: bağlamla çevrildiyse bağlam da, sözlük terimi içeriyorsa sözlüğün izi de girer.
    public static func cacheKey(_ sentence: String, context: String?, glossary: Glossary) -> String {
        let basis = context.map { $0 + keyJoin + sentence } ?? sentence
        return glossary.matches(basis) ? glossary.fingerprint + keyJoin + basis : basis
    }

    /// Ayraçtan sonraki kısım; ayraç tam bir kez geçmiyorsa, iki yan da harf içermiyorsa ya da kısım akla yatmayacak
    /// kadar uzunsa nil.
    public static func afterSeparator(_ output: String, source: String) -> String? {
        guard let first = output.range(of: separatorCore), output.range(of: separatorCore, range: first.upperBound..<output.endIndex) == nil else {
            return nil
        }
        let before = output[..<first.lowerBound]
        let after = output[first.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
        // "****" gibi yapışık yıldızlar da belirsizdir.
        if after.hasPrefix("*") || before.hasSuffix("*") { return nil }
        guard before.contains(where: \.isLetter), after.contains(where: \.isLetter) else { return nil }
        if after.utf16.count > 3 * source.utf16.count + 20 { return nil }
        return after
    }

    /// Tek cümleyi çevirir. `normalize` büyük harf düzenini yapar (öğrenilen adlarla), `engine` modeldir. Önce bağlamla
    /// (verildiyse), olmazsa tek başına, terimler korunamadıysa korumasız çevrilir.
    public static func translate(_ sentence: String, context: String?, glossary: Glossary, normalize: (String) -> String,
                                 engine: (String) throws -> String) rethrows -> String {
        let apostrophe = TurkishPostEdit.apostrophe(sentence)
        func prepare(_ text: String) -> String { GameEnglish.plain(normalize(text)) }

        if let context {
            let protector = TermProtector(glossary: glossary, sources: [sentence, context])
            let contextText = protector.protect(prepare(context)).text
            let (sentenceText, slots) = protector.protect(prepare(sentence))
            let output = try engine(contextText + separator + sentenceText)
            if let after = afterSeparator(output, source: sentence),
               let restored = TermProtector.restore(after, expected: slots, all: protector.allSlots, apostrophe: apostrophe) {
                return TurkishPostEdit.apply(source: sentence, translation: restored)
            }
        }

        let single = TermProtector(glossary: glossary, sources: [sentence])
        let (input, used) = single.protect(prepare(sentence))
        let translated = try engine(input)
        if used.isEmpty { return TurkishPostEdit.apply(source: sentence, translation: translated) }
        if let done = TermProtector.restore(translated, expected: used, all: single.allSlots, apostrophe: apostrophe) {
            return TurkishPostEdit.apply(source: sentence, translation: done)
        }
        // Model bir yer tutucuyu düşürdü: terimsiz ama eksiksiz çeviri, eksik terimli çeviriden iyidir.
        return TurkishPostEdit.apply(source: sentence, translation: try engine(prepare(sentence)))
    }

    /// Eşzamansız motorla aynı hat (Apple Translation gibi).
    public static func translateAsync(_ sentence: String, context: String?, glossary: Glossary, normalize: (String) -> String,
                                 engine: (String) async throws -> String) async rethrows -> String {
        let apostrophe = TurkishPostEdit.apostrophe(sentence)
        func prepare(_ text: String) -> String { GameEnglish.plain(normalize(text)) }

        if let context {
            let protector = TermProtector(glossary: glossary, sources: [sentence, context])
            let contextText = protector.protect(prepare(context)).text
            let (sentenceText, slots) = protector.protect(prepare(sentence))
            let output = try await engine(contextText + separator + sentenceText)
            if let after = afterSeparator(output, source: sentence),
               let restored = TermProtector.restore(after, expected: slots, all: protector.allSlots, apostrophe: apostrophe) {
                return TurkishPostEdit.apply(source: sentence, translation: restored)
            }
        }

        let single = TermProtector(glossary: glossary, sources: [sentence])
        let (input, used) = single.protect(prepare(sentence))
        let translated = try await engine(input)
        if used.isEmpty { return TurkishPostEdit.apply(source: sentence, translation: translated) }
        if let done = TermProtector.restore(translated, expected: used, all: single.allSlots, apostrophe: apostrophe) {
            return TurkishPostEdit.apply(source: sentence, translation: done)
        }
        let plain = try await engine(prepare(sentence))
        return TurkishPostEdit.apply(source: sentence, translation: plain)
    }
}

/// Son gösterilen satırlar: kısa satırın bağlamı (Windows: RecentLines). Aynı satır yeniden okunursa aynı bağlam döner
/// (çeviri ekranda değişmesin); bağlam satırı çok önce gösterildiyse bağlam yoktur.
public final class RecentLines: @unchecked Sendable {
    public static let maxAge: TimeInterval = 20

    private let clock: () -> Date
    private let lock = NSLock()
    private var last: (text: String, context: String?, seen: Date)?

    public init(clock: (() -> Date)? = nil) {
        self.clock = clock ?? { Date() }
    }

    /// Satırın gösterildiğini kaydeder; bu satırdan önce gösterilen satırı döner (yoksa nil).
    public func shown(_ text: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        let now = clock()
        if let previous = last, previous.text == text {
            last = (text, previous.context, now)
            return previous.context
        }
        var context: String?
        if let previous = last, now.timeIntervalSince(previous.seen) <= Self.maxAge { context = previous.text }
        last = (text, context, now)
        return context
    }
}
