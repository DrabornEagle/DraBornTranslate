// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Yazı tanımanın birbirine karıştırdığı harfleri onarır (Windows: OcrConfusions.cs, Android: OcrConfusions.kt; aynı
/// kurallar). İnce yazı tiplerinde "t" ve "f" sık sık "l", "o" sıfır, "m" de "rn" okunur ("Io make", "do nol disappear",
/// "used lor attack", "noise 01 your"); her karede aynı okunduğu için beklemek düzeltmez. Gerçek sözcük olmayan parçada
/// bu harfler değiştirilerek gerçek bir sözcük aranır; tek bir aday çıkarsa (ya da adaylardan yalnız biri çok sık
/// kullanılan bir sözcükse) parça onunla değiştirilir. Gerçek sözcüğe ve özel ada dokunulmaz.
public enum OcrConfusions {
    /// Her zaman aynı biçimde bozulan çok kısa sözcükler (denetime gerek yok).
    private static let fixed: [String: String] = [
        "Io": "to", "lo": "to", "01": "of", "0f": "of", "0K": "OK", "0k": "ok",
        "lhe": "the", "Ihe": "the", "lt": "It", "lf": "If", "ls": "Is",
        // Yuvarlak, ince yazı tiplerinde "m" "n", "t" "c", "e" "c" okunuyor ("ny love", "co do", "Wc won't").
        "ny": "my", "Ny": "My", "co": "to", "Wc": "We", "wc": "we", "chis": "this", "Chis": "This",
    ]

    /// Birden çok aday çıktığında tercih edilen, çok sık kullanılan sözcükler (büyük/küçük harfe bakılmaz).
    private static let common: Set<String> = [
        "the", "to", "of", "for", "not", "that", "this", "with", "it", "at", "if", "off", "after", "often", "from", "first",
        "just", "last", "left", "let", "lot", "get", "got", "out", "but", "what", "there", "their", "they", "them", "then",
        "than", "time", "little", "better", "until", "still", "must", "most", "find", "fast", "soft", "take", "into",
        "want", "about", "attack", "fight", "right", "night", "light", "might", "enemy", "weapon", "more", "come",
    ]

    private static let swaps: [([Character], [Character])] = [
        ("l", "t"), ("l", "f"), ("I", "l"), ("I", "t"), ("0", "o"), ("1", "l"), ("rn", "m"), ("vv", "w"), ("cl", "d"),
        // Yuvarlak yazı tiplerinde: "m" → "nn" / "n" / "na" / "no" ("Tinne", "hinn", "nauch", "noy"), "t" → "c"
        // ("chis", "chat"), "e" → "c", "l" → "i" ("iove").
        ("nn", "m"), ("n", "m"), ("na", "m"), ("no", "m"), ("c", "t"), ("c", "e"), ("i", "l"),
        // Köşeli yazı tiplerinde "a" "o" okunuyor ("Stoy where you are", "trosh").
        ("o", "a"),
        // İnce, yüksek yazı tiplerinde "m" "Ili" / "lli" okunuyor ("Ilioney" → "money").
        ("Ili", "m"), ("lli", "m"),
    ].map { (Array($0.0), Array($0.1)) }

    /// İngilizcede bulunmayan, Türkçe okuyucunun ürettiği harfler.
    private static let turkishLetters: Set<Character> = Set("İıŞşĞğÇçÖöÜü")

    /// Türkçe harfin İngilizce karşılıkları (Türkçe okuyucunun karıştırdıkları; ilk seçenek en olası).
    private static let turkishOptions: [Character: [String]] = [
        "İ": ["I"], "ı": ["i", "l"], "Ş": ["S"], "ş": ["s"], "Ğ": ["G"], "ğ": ["g"],
        "Ç": ["C", "G"], "ç": ["c", "g"], "Ö": ["O"], "ö": ["o"], "Ü": ["U"], "ü": ["u"],
    ]

    private static let shortWords: Set<String> = [
        "a", "i", "of", "to", "in", "on", "at", "is", "it", "we", "he", "me", "my", "be", "do", "go", "no", "so", "up", "us",
        "an", "as", "by", "if", "or", "oh", "ok", "am", "hi",
    ]

    private static func upperFirst(_ word: String) -> String {
        guard let first = word.first else { return word }
        return first.uppercased() + word.dropFirst()
    }

    /// Parçanın baştaki ve sondaki harf/rakam olmayan kısmı atıldığında kalan aralık.
    private static func coreRange(_ token: [Character]) -> (Int, Int) {
        var start = 0, end = token.count
        while start < end && !token[start].isLetterOrDigit { start += 1 }
        while end > start && !token[end - 1].isLetterOrDigit { end -= 1 }
        return (start, end)
    }

    /// `fromTurkishLetters`: oyun dili İngilizce; Türkçe harfli parçalar İngilizceye çevrilir (Türkçe okuyucunun bozduğu
    /// sözcükler). `isWord` nil ise satır olduğu gibi döner.
    public static func repair(_ input: String, isWord: ((String) -> Bool)?, fromTurkishLetters: Bool = false) -> String {
        guard let isWord, !input.isEmpty else { return input }
        let line = spaceAfterPunctuation(input)
        var tokens = line.splitKeepingEmpty(" ")
        var changed = false
        for index in tokens.indices {
            var token = Array(tokens[index])
            var (start, end) = coreRange(token)
            let next: String? = index + 1 < tokens.count ? tokens[index + 1] : nil
            // Tek başına "o" İngilizcede sözcük değildir; "a" harfidir ("You're o woman", "for o whirl").
            if tokens[index] == "o", index > 0, let next, let first = next.first, first.isLetter {
                tokens[index] = "a"
                changed = true
                continue
            }
            // Cümle başında "I" "1" okunabiliyor ("1 learned a long time ago"): ardından küçük harfli gerçek sözcük gelirse.
            if tokens[index] == "1", let next, next.count > 1, next.first!.isLowercase, isWord(next.trimEnd(".,!?")),
               index == 0 || tokens[index - 1].hasSuffix(".") || tokens[index - 1].hasSuffix("!") || tokens[index - 1].hasSuffix("?") {
                tokens[index] = "I"
                changed = true
                continue
            }
            // Türkçe okuyucu İngilizce sözcüğü Türkçe harflerle bozuyor ("thİnk", "hinı", "guyş"); İngilizce sözcüğe çevrilir.
            if fromTurkishLetters && (token[start..<end].contains(where: turkishLetters.contains) || (end < token.count && token[end] == "ı")) {
                let nextLower = next?.first?.isLowercase == true
                let repaired = Self.fromTurkishLetters(String(token), isWord: isWord, nextLower: nextLower)
                if repaired != String(token) {
                    tokens[index] = repaired
                    changed = true
                    token = Array(repaired)
                    (start, end) = coreRange(token)
                }
            }
            if end - start < 2 { continue }
            let core = String(token[start..<end])
            let head = String(token[0..<start]), tail = String(token[end...])
            // Sözcük arası boşlukları okunmamış parça ("Andthereismuchtodo") gerçek sözcüklere bölünür. Cümle ortasında
            // büyük harfle başlayan parça büyük olasılıkla addır ("Heywood"); bölünmez.
            let previous = index > 0 ? tokens[index - 1] : ""
            let sentenceStart = index == 0 || previous.trimEnd("\"'’”").hasSuffix(".") ||
                previous.hasSuffix("!") || previous.hasSuffix("?") || previous.hasSuffix(":")
            if sentenceStart || !core.first!.isUppercase {
                if let split = gluedAroundHyphens(core, isWord: isWord) {
                    tokens[index] = head + split + tail
                    changed = true
                    continue
                }
            }
            // Ünlem "l" okunabiliyor ("Ohl shit.", "Brotherl Brother!"): sözcüğün sonundaki "l" atılınca gerçek sözcük
            // kalıyorsa ve ardından yeni cümle başlıyorsa (satır sonu ya da büyük harf) "!" olur.
            let nextStarts = index == tokens.count - 1 || (next?.first?.isUppercase == true)
            if end == token.count, core.count >= 3, core.last == "l", nextStarts, !isWord(core), isWord(String(core.dropLast())) {
                tokens[index] = head + core.dropLast() + "!"
                changed = true
                continue
            }
            guard var fixedWord = fixed[core] ?? candidate(core, isWord: isWord, sentenceStart: sentenceStart), fixedWord != core else { continue }
            // Cümle başındaki sözcük büyük harfle başlar ("Io make" satır başındaysa "To make").
            if index == 0, core.first!.isUppercase, fixedWord.first?.isLowercase == true { fixedWord = upperFirst(fixedWord) }
            tokens[index] = head + fixedWord + tail
            changed = true
        }
        return changed ? tokens.joined(separator: " ") : line
    }

    /// Türkçe harfli parça (noktalamasıyla birlikte) İngilizceye: önce harf seçenekleri ve "nı"/"rı" → "m" ile gerçek
    /// sözcük aranır ("hinı" → "him", "flyinç" → "flying", "thİnk" → "think"); sondaki "ı" sözcükten sonraysa okunmamış
    /// virgüldür ("downı" → "down,"). Gerçek sözcük çıkmazsa harfler ilk seçenekle düzleştirilir ("Vercettı" → "Vercetti").
    private static func fromTurkishLetters(_ text: String, isWord: (String) -> Bool, nextLower: Bool) -> String {
        let token = Array(text)
        var start = 0, end = token.count
        while start < end && !token[start].isLetterOrDigit { start += 1 }
        while end > start && !token[end - 1].isLetterOrDigit && token[end - 1] != "ı" && token[end - 1] != "İ" { end -= 1 }
        let core = Array(token[start..<end])
        let tail = String(token[end...])
        let head = String(token[0..<start])
        func word(_ w: String) -> Bool { !w.isEmpty && (isWord(w) || isWord(w.lowercased())) }
        func hasTurkish<S: Sequence>(_ text: S) -> Bool where S.Element == Character { text.contains(where: turkishLetters.contains) }

        // Sondaki "nı" / "rı" çoğunlukla "m"dir ("thenı" → "them", "hinı" → "him", "Sanı" → "Sam"); "m"li hâli sözcük
        // değilken "n" ile biten sözcük çıkıyorsa okunmamış virgüldür ("lionı" → "lion,").
        if core.count >= 3, core.last == "ı", core[core.count - 2] == "n" || core[core.count - 2] == "r", !hasTurkish(core[0..<(core.count - 2)]) {
            let withM = String(core.dropLast(2)) + "m"
            let withoutLast = String(core.dropLast())
            return head + (!word(withM) && word(withoutLast) ? withoutLast + "," : withM) + tail
        }
        // Sondaki "ı": "downı helps" → "down, helps"; "brothersı Okay" → "brothers, Okay"
        if core.count >= 3, core.last == "ı", !hasTurkish(core.dropLast()), word(String(core.dropLast())), !word(plain(core)) {
            return head + String(core.dropLast()) + "," + tail
        }

        var candidates: [String] = []
        func expand(_ done: String, _ at: Int) {
            if candidates.count >= 64 { return }
            if at >= core.count {
                candidates.append(done)
                return
            }
            let c = core[at]
            // "nı" / "rı" → "m" (Türkçe okuyucu "m"yi böyle bölüyor: "thenı", "Sanı", "hinı")
            if (c == "n" || c == "r") && at + 1 < core.count && core[at + 1] == "ı" { expand(done + "m", at + 2) }
            // Sözcük ortasındaki "İ" küçük "i"dir ("thİnk" → "think"); başta büyük "I" ("İt" → "It")
            if c == "İ" && at > 0 {
                expand(done + "i", at + 1)
                expand(done + "I", at + 1)
            } else if let options = turkishOptions[c] {
                for option in options { expand(done + option, at + 1) }
            } else {
                expand(done + String(c), at + 1)
            }
        }
        expand("", 0)
        let found = candidates.first(where: word)
        return head + (found ?? plain(core)) + tail
    }

    private static func plain(_ text: [Character]) -> String {
        var builder = ""
        for (i, c) in text.enumerated() {
            builder += c == "İ" && i > 0 ? "i" : turkishOptions[c]?.first ?? String(c)
        }
        return builder
    }

    /// Noktalamadan sonra boşluğu okunmamış iki sözcük ("words,brother", "No.It") ayrılır. Nokta yalnız ardından büyük
    /// harf geliyor ve önünde en az iki harf varsa (kısaltma: "U.S.") ayırır.
    private static func spaceAfterPunctuation(_ text: String) -> String {
        let line = Array(text)
        var builder: [Character] = []
        builder.reserveCapacity(line.count + 4)
        var added = false
        for i in line.indices {
            builder.append(line[i])
            if i == 0 || i + 1 >= line.count || !line[i - 1].isLetter || !line[i + 1].isLetter { continue }
            let c = line[i]
            if c == "," || c == ";" || c == "!" || c == "?" {
                builder.append(" "); added = true
            } else if c == ":" && line[i + 1].isUppercase {
                // "Shadowheart:Try that" (konuşmacı adından sonra boşluk okunmamış).
                builder.append(" "); added = true
            } else if c == "." && line[i + 1].isUppercase && i >= 2 && line[i - 2].isLetter {
                builder.append(" "); added = true
            }
        }
        return added ? String(builder) : text
    }

    /// Tirelerle ayrılmış parçaların ("pod-theydidsomethingtoitwhen") her biri ayrı bölünür; hiçbiri bölünmezse nil.
    private static func gluedAroundHyphens(_ core: String, isWord: (String) -> Bool) -> String? {
        guard core.contains("-") else { return glued(core, isWord: isWord) }
        var parts = core.splitKeepingEmpty("-")
        var any = false
        for i in parts.indices {
            guard let split = glued(parts[i], isWord: isWord) else { continue }
            parts[i] = split
            any = true
        }
        return any ? parts.filter { !$0.isEmpty }.joined(separator: " - ") : nil
    }

    /// Boşlukları okunmamış birkaç sözcük: parça gerçek sözcük değilse ve tamamı en az iki gerçek sözcüğe bölünebiliyorsa
    /// en az parçalı bölünüşü. Tek harfli parça yalnız "a" ve "I" olabilir; sözcükler ortalama üç harften kısaysa
    /// (rastgele harflerden kısa sözcük uydurmak kolaydır) bölünmez.
    static func glued(_ text: String, isWord: (String) -> Bool) -> String? {
        let core = Array(text)
        if core.count < 7 || core.count > 60 || !core.allSatisfy({ $0.isLetter || $0 == "'" || $0 == "’" }) || isWord(text) { return nil }
        // "Shoulda", "kinda", "sorta": konuşma dili, yapışık sözcük değil.
        if core.last == "a" && isWord(String(core.dropLast())) { return nil }
        // Sonu düşmüş "-ing" ("bellowin'", "lookin'") konuşma dilidir, yapışık sözcük değil.
        if text.hasSuffix("in") && isWord(text + "g") { return nil }
        let n = core.count
        var best = [Int](repeating: Int.max, count: n + 1)
        var from = [Int](repeating: 0, count: n + 1)
        best[0] = 0
        for end in 1...n {
            for start in max(0, end - 14)..<end {
                if best[start] == Int.max || best[start] + 1 >= best[end] { continue }
                let piece = String(core[start..<end])
                // Yazım denetimi "fi", "oy", "et" gibi parçaları da sözcük sayabilir; bir iki harfli parça yalnız çok
                // yaygın sözcüklerden olabilir (yoksa "Jackiefiet" "Jackie fi et" olur).
                if piece.count <= 2 ? !shortWords.contains(piece.lowercased()) : !isWord(piece) { continue }
                if core[start] == "'" || core[start] == "’" { continue }
                best[end] = best[start] + 1
                from[end] = start
            }
        }
        if best[n] == Int.max || best[n] < 2 || n < best[n] * 3 { return nil }
        // Büyük harfle başlayan iki parçalı ad ("Rayfield", "Heywood", "Blackwood") bölünmez.
        if best[n] == 2 && core[0].isUppercase { return nil }
        var pieces: [String] = []
        var end = n
        while end > 0 {
            pieces.append(String(core[from[end]..<end]))
            end = from[end]
        }
        return pieces.reversed().joined(separator: " ")
    }

    /// Parça gerçek sözcük değilse, karışan harfler değiştirilerek bulunan tek (ya da tek yaygın) gerçek sözcük.
    private static func candidate(_ text: String, isWord: (String) -> Bool, sentenceStart: Bool = false) -> String? {
        let core = Array(text)
        if core.count < 3 || core.count > 14 || !core.allSatisfy({ $0.isLetterOrDigit || $0 == "'" || $0 == "’" }) { return nil }
        if !core.contains(where: \.isLetter) || isWord(text) { return nil }
        // Özel ad gibi duran parça (baş harfi büyük, gerisi küçük, rakamsız) yalnız içinde karışan harf dizisi varsa denenir.
        let proper = core[0].isUppercase && core.dropFirst().allSatisfy(\.isLowercase)
        // Cümle başında her sözcük büyük harfle başlar; orada denenir ("Stoy where you are" → "Stay").
        if proper && !sentenceStart && !text.contains("rn") && !text.contains("nn") && core[0] != "I" { return nil }

        var found: [String] = []
        var tries = 0
        func attempt(_ current: [Character], _ depth: Int) {
            for (from, to) in swaps {
                var at = current.firstOffset(of: from)
                while at >= 0 {
                    let next = Array(current[0..<at]) + to + Array(current[(at + from.count)...])
                    let word = String(next)
                    if isWord(word), !found.contains(word) { found.append(word) }
                    if depth < 2 && found.count < 12 {
                        tries += 1
                        if tries < 400 { attempt(next, depth + 1) }
                    }
                    at = current.firstOffset(of: from, from: at + 1)
                }
            }
        }
        attempt(core, 1)
        if found.isEmpty { return nil }
        if found.count == 1 { return found[0] }
        let usual = found.filter { common.contains($0.lowercased()) }
        return usual.count == 1 ? usual[0] : nil
    }
}
