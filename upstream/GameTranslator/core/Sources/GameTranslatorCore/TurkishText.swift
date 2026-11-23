// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// NSRegularExpression yardımcıları: değiştirme kapanışla (C#'taki MatchEvaluator gibi) ve bölme.
enum Regexes {
    static func whole(_ text: String) -> NSRange { NSRange(location: 0, length: (text as NSString).length) }

    static func replace(_ regex: NSRegularExpression, in text: String, _ transform: (NSTextCheckingResult, NSString) -> String) -> String {
        let ns = text as NSString
        var output = ""
        var last = 0
        for match in regex.matches(in: text, range: whole(text)) {
            output += ns.substring(with: NSRange(location: last, length: match.range.location - last))
            output += transform(match, ns)
            last = match.range.location + match.range.length
        }
        return output + ns.substring(from: last)
    }

    static func replace(_ regex: NSRegularExpression, in text: String, with template: String) -> String {
        regex.stringByReplacingMatches(in: text, range: whole(text), withTemplate: template)
    }

    static func split(_ regex: NSRegularExpression, _ text: String) -> [String] {
        let ns = text as NSString
        var parts: [String] = []
        var last = 0
        for match in regex.matches(in: text, range: whole(text)) {
            parts.append(ns.substring(with: NSRange(location: last, length: match.range.location - last)))
            last = match.range.location + match.range.length
        }
        parts.append(ns.substring(from: last))
        return parts
    }

    static func group(_ match: NSTextCheckingResult, _ index: Int, in text: NSString) -> String {
        let range = match.range(at: index)
        return range.location == NSNotFound ? "" : text.substring(with: range)
    }
}

/// Cihaz üstü çevirinin Türkçesine yapılan güvenli düzeltmeler (Windows: TurkishPostEdit.cs, aynı kurallar): noktalamadan
/// önceki boşluk silinir; kaynağın "!", "?", "?!", "…" sonu korunur; tırnaklar kaynaktaki gibi düz ya da kıvrık olur;
/// aynı satırda "sen" ile konuşulurken model emri "siz"li verdiyse bilinen emirler "sen"li olur.
public enum TurkishPostEdit {
    private static let spaceBeforePunctuation = try! NSRegularExpression(pattern: "[ \\t\u{00A0}]+(?=[,.!?;:…])")
    private static let repeatedSpaces = try! NSRegularExpression(pattern: "[ \\t\u{00A0}]{2,}")

    /// Tek cümlenin çevirisini düzeltir; `source` İngilizce cümledir.
    public static func apply(source: String, translation: String) -> String {
        guard translation.contains(where: \.isLetter) else { return translation }
        var text = quotes(source: source, translation: translation)
        text = Regexes.replace(spaceBeforePunctuation, in: text, with: "")
        text = Regexes.replace(repeatedSpaces, in: text, with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        return ending(source: source, translation: text)
    }

    /// Kaynağın ünlem/soru/üç nokta sonu çeviride başka bir sonla bittiyse kaynağınki konur.
    static func ending(source: String, translation: String) -> String {
        let (_, sourceEnd, sourceClosers) = splitEnd(source)
        guard isExpressive(sourceEnd) else { return translation }
        let (body, end, closers) = splitEnd(translation)
        // Kaynakta son noktalama tırnağın içindeyse çeviride tırnak sonda olmayabilir; dokunulmaz.
        if sourceClosers.isEmpty != closers.isEmpty { return translation }
        if end == sourceEnd || !(end.isEmpty || end == "." || isExpressive(end)) { return translation }
        guard body.contains(where: \.isLetter) else { return translation }
        return body + sourceEnd + closers
    }

    private static func isExpressive(_ end: String) -> Bool {
        !end.isEmpty && (end.contains("!") || end.contains("?") || end.contains("…") || end == "...")
    }

    /// Metin = gövde + son noktalama (. ! ? …) + kapanış tırnak/parantezleri.
    private static func splitEnd(_ raw: String) -> (String, String, String) {
        var text = raw
        while let last = text.last, last.isWhitespace { text.removeLast() }
        let characters = Array(text)
        var closerStart = characters.count
        while closerStart > 0 && "\"'”’)]»".contains(characters[closerStart - 1]) { closerStart -= 1 }
        var endStart = closerStart
        while endStart > 0 && ".!?…".contains(characters[endStart - 1]) { endStart -= 1 }
        return (String(characters[0..<endStart]), String(characters[endStart..<closerStart]), String(characters[closerStart...]))
    }

    /// Kaynak kıvrık tırnak kullanıyorsa çeviri de kullanır; yoksa düz tırnak.
    static func quotes(source: String, translation: String) -> String {
        let curlyDouble = source.contains("“") || source.contains("”")
        let curlySingle = source.contains("’") || source.contains("‘")
        let characters = Array(translation)
        var output = ""
        for (i, c) in characters.enumerated() {
            let opening = i == 0 || characters[i - 1].isWhitespace || "([-–—".contains(characters[i - 1])
            // Çok kalıplı case'te where yalnız son kalıba bağlanır; bu yüzden if ile.
            if "“”„‟".contains(c) && !curlyDouble {
                output.append("\"")
            } else if c == "\"" && curlyDouble {
                output.append(opening ? "“" : "”")
            } else if (c == "’" || c == "‘") && !curlySingle {
                output.append("'")
            } else if c == "'" && curlySingle {
                output.append(opening ? "‘" : "’")
            } else {
                output.append(c)
            }
        }
        return output
    }

    /// Kaynakta kıvrık kesme varsa Türkçe eklerde de o kullanılır.
    public static func apostrophe(_ source: String) -> Character { source.contains("’") ? "’" : "'" }

    private static let senWords: Set<String> = ["sen", "seni", "sana", "senin", "sende", "senden", "seninle", "senle"]
    private static let sizWords: Set<String> = ["siz", "sizi", "size", "sizin", "sizde", "sizden", "sizinle", "sizler"]

    /// "Siz"li emir → "sen"li emir; çoğul ya da kibar anlamı da olabilen sözcükler bilerek yok ("Alın" = alın).
    private static let imperatives: [(polite: String, informal: String)] = [
        ("dikkat edin", "dikkat et"), ("acele edin", "acele et"), ("ateş edin", "ateş et"), ("yardım edin", "yardım et"),
        ("takip edin", "takip et"), ("sessiz olun", "sessiz ol"), ("hazır olun", "hazır ol"), ("sakin olun", "sakin ol"),
        ("bekleyin", "bekle"), ("durun", "dur"), ("gelin", "gel"), ("koşun", "koş"), ("gidin", "git"), ("bakın", "bak"),
        ("dinleyin", "dinle"), ("saklanın", "saklan"), ("kaçın", "kaç"), ("savaşın", "savaş"), ("dövüşün", "dövüş"),
        ("durdurun", "durdur"), ("çekilin", "çekil"), ("eğilin", "eğil"), ("atlayın", "atla"), ("tutun", "tut"),
        ("bırakın", "bırak"), ("söyleyin", "söyle"), ("gösterin", "göster"), ("getirin", "getir"), ("kalkın", "kalk"),
        ("odaklanın", "odaklan"), ("yaklaşın", "yaklaş"), ("uzaklaşın", "uzaklaş"),
    ]

    private static let imperativePatterns: [(NSRegularExpression, String)] = imperatives.map { pair in
        (try! NSRegularExpression(pattern: "(?<!\\p{L})\(NSRegularExpression.escapedPattern(for: pair.polite))(?=\\s*[!.,?…]|\\s*$)",
                                  options: [.caseInsensitive]), pair.informal)
    }

    /// Bir altyazının cümle çevirileri: herhangi birinde "sen" geçiyorsa (seni, sana…), "siz" geçmeyen cümlelerdeki bilinen
    /// "siz"li emirler "sen"li olur. Başka hiçbir şeye dokunulmaz.
    public static func register(_ translations: [String?]) -> [String?] {
        guard translations.contains(where: { $0.map { containsWord($0, senWords) } ?? false }) else { return translations }
        return translations.map { text in
            guard let text, !containsWord(text, sizWords) else { return text }
            return informal(text)
        }
    }

    private static func informal(_ sentence: String) -> String {
        var text = sentence
        for (pattern, informal) in imperativePatterns {
            text = Regexes.replace(pattern, in: text) { match, ns in
                let value = ns.substring(with: match.range)
                return value.first?.isUppercase == true ? TurkishCase.capitalizeFirst(informal) : informal
            }
        }
        return text
    }

    private static func containsWord(_ text: String, _ words: Set<String>) -> Bool {
        TurkishCase.lower(text).split(whereSeparator: { !$0.isLetter }).contains { words.contains(String($0)) }
    }
}

/// Oyunlarda sık geçen kısa ünlemlerin İngilizce→Türkçe karşılıkları (Windows: ShortPhrases.cs). Bağlamsız kısa satırı
/// çevirmenler sözlük anlamıyla çeviriyor ("Well?" → "Kuyu?", "See?" → "Görmek?"). Satırın her cümlesi tablodaysa satır
/// buradan çevrilir; biri bile değilse satırın tamamı çevirmene gider. Cümlenin sonundaki noktalama kaynaktaki gibi kalır.
public enum ShortPhrases {
    /// Cümle sonu "?" ise kullanılan karşılık önce, değilse ikinci.
    private static let table: [String: (question: String, statement: String)] = [
        "okay": ("Tamam mı", "Tamam"), "ok": ("Tamam mı", "Tamam"), "alright": ("Tamam mı", "Peki"),
        "all right": ("Tamam mı", "Peki"), "fine": ("Peki", "Peki"), "good": ("İyi mi", "Güzel"),
        "yes": ("Evet mi", "Evet"), "yeah": ("Öyle mi", "Evet"), "yep": ("Evet", "Evet"), "yup": ("Evet", "Evet"),
        "no": ("Hayır mı", "Hayır"), "nope": ("Hayır", "Hayır"), "nah": ("Yok", "Yok"),
        "sure": ("Emin misin", "Tabii"), "of course": ("Elbette", "Elbette"), "right": ("Değil mi", "Doğru"),
        "well": ("Ee", "Şey"), "see": ("Gördün mü", "Gördün mü"), "huh": ("Ha", "Ha"), "what": ("Ne", "Ne"),
        "why": ("Neden", "Neden"), "how": ("Nasıl", "Nasıl"), "where": ("Nerede", "Nerede"), "who": ("Kim", "Kim"),
        "really": ("Gerçekten mi", "Gerçekten"), "what now": ("Şimdi ne olacak", "Şimdi ne olacak"),
        "wait": ("Bekle", "Bekle"), "hurry": ("Acele et", "Acele et"), "hurry up": ("Acele et", "Acele et"),
        "come": ("Gel", "Gel"), "come on": ("Hadi", "Hadi"), "come here": ("Buraya gel", "Buraya gel"),
        "go": ("Git", "Git"), "run": ("Koş", "Koş"), "stop": ("Dur", "Dur"), "look": ("Bak", "Bak"),
        "listen": ("Dinle", "Dinle"), "help": ("İmdat", "İmdat"), "move": ("Çekil", "Çekil"),
        "get down": ("Eğil", "Eğil"), "look out": ("Dikkat et", "Dikkat et"), "watch out": ("Dikkat et", "Dikkat et"),
        "let's go": ("Gidelim mi", "Hadi gidelim"), "quiet": ("Sessiz ol", "Sessiz ol"), "enough": ("Yeter", "Yeter"),
        "behind you": ("Arkanda", "Arkanda"), "got it": ("Anladın mı", "Anladım"), "understood": ("Anlaşıldı mı", "Anlaşıldı"),
        "thanks": ("Teşekkürler", "Teşekkürler"), "thank you": ("Teşekkür ederim", "Teşekkür ederim"),
        "sorry": ("Pardon", "Özür dilerim"), "please": ("Lütfen", "Lütfen"), "hello": ("Merhaba", "Merhaba"),
        "hi": ("Selam", "Selam"), "hey": ("Hey", "Hey"), "morning": ("Günaydın", "Günaydın"),
        "good morning": ("Günaydın", "Günaydın"), "good night": ("İyi geceler", "İyi geceler"), "bye": ("Hoşça kal", "Hoşça kal"),
        "damn": ("Lanet olsun", "Lanet olsun"), "damn it": ("Kahretsin", "Kahretsin"), "shit": ("Kahretsin", "Kahretsin"),
        "oh": ("Ah", "Ah"), "ah": ("Ah", "Ah"), "hmm": ("Hmm", "Hmm"), "uh": ("Ee", "Ee"),
        "brother": ("Kardeşim", "Kardeşim"), "father": ("Baba", "Baba"), "mother": ("Anne", "Anne"),
        "dad": ("Baba", "Baba"), "mom": ("Anne", "Anne"), "boy": ("Evlat", "Evlat"), "kid": ("Evlat", "Evlat"),
        "sir": ("Efendim", "Efendim"), "boss": ("Patron", "Patron"),
        // Ara sahne düğmesi: "Skip" → "Atlamak" çıkıyordu.
        "skip": ("Geç", "Geç"),
        // Telsiz dili: "Over!" → "Üzerinde!", "Roger that" → "Roger bu" çıkıyordu.
        "over": ("Tamam", "Tamam"), "over and out": ("Tamam, kapatıyorum", "Tamam, kapatıyorum"),
        "roger": ("Anlaşıldı", "Anlaşıldı"), "roger that": ("Anlaşıldı", "Anlaşıldı"),
        "copy": ("Duydun mu", "Anlaşıldı"), "copy that": ("Anlaşıldı", "Anlaşıldı"),
    ]

    private static let radioTail = try! NSRegularExpression(pattern: "^(.*\\S[.!?…])\\s+((?:Over|Over and out)[.!]*)$",
                                                            options: [.dotMatchesLineSeparators])
    private static let sentenceBreak = try! NSRegularExpression(pattern: "(?<=[.!?…])\\s+")
    private static let parts = try! NSRegularExpression(pattern: "^([.…—\\-\\s]*)(.*?)([.!?…,]*)$", options: [.dotMatchesLineSeparators])

    /// Telsiz konuşmasının sonundaki "Over!" ayrılır: ("Base, we got him!", "Tamam!"). Sonunda yoksa nil.
    public static func splitRadioTail(_ text: String) -> (main: String, tail: String)? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let ns = trimmed as NSString
        guard let match = radioTail.firstMatch(in: trimmed, range: Regexes.whole(trimmed)) else { return nil }
        let main = Regexes.group(match, 1, in: ns)
        guard main.filter(\.isLetter).count >= 3 else { return nil }
        return (main, translate(Regexes.group(match, 2, in: ns)) ?? "Tamam.")
    }

    /// Satırın Türkçesi (her cümlesi tablodaysa); değilse nil.
    public static func translate(_ raw: String) -> String? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.utf16.count <= 40, !text.contains("\n") else { return nil }
        var result: [String] = []
        for sentence in Regexes.split(sentenceBreak, text) {
            let ns = sentence as NSString
            guard let match = parts.firstMatch(in: sentence, range: Regexes.whole(sentence)) else { return nil }
            let body = Regexes.group(match, 2, in: ns).trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "’", with: "'")
            let end = Regexes.group(match, 3, in: ns)
            guard let turkish = table[body.lowercased()] else { return nil }
            let phrase = end.contains("?") ? turkish.question : turkish.statement
            // "Tamam mı?" gibi karşılık soru ekini içeriyor; kaynakta soru işareti yoksa bile soru olarak kalır.
            result.append(Regexes.group(match, 1, in: ns).trimmingCharacters(in: .whitespacesAndNewlines) + phrase + end)
        }
        return result.joined(separator: " ")
    }

    /// Kaynak normal yazılmışken çevirmenin bütünüyle büyük harfle döndürdüğü çeviri ("No..." → "HAYIR...") normal yazıma
    /// çevrilir: küçük harf, ilk harf büyük. Kaynak da büyük harfle yazılmışsa (bağırma, başlık) dokunulmaz.
    public static func fixShouting(source: String, translation: String) -> String {
        let sourceLetters = source.filter(\.isLetter)
        let letters = translation.filter(\.isLetter)
        guard letters.count >= 3, letters.allSatisfy(\.isUppercase), !sourceLetters.isEmpty, !sourceLetters.allSatisfy(\.isUppercase) else {
            return translation
        }
        let lower = TurkishCase.lower(translation)
        guard let first = lower.firstIndex(where: \.isLetter) else { return lower }
        return String(lower[..<first]) + TurkishCase.upper(String(lower[first])) + String(lower[lower.index(after: first)...])
    }
}

/// Oyunların tuş çubuğundaki ve etkileşim istemlerindeki kısa komutların ("OPEN ✕", "PUT DOWN ○") Türkçesi (Windows:
/// UiPrompts.cs). Çeviri motoru bu tek sözcükleri bağlamsız görünce yanlış anlıyordu ("OPEN" → "AÇIK"); sözlükteki komut
/// motora gitmez, tuş simgeleri yerinde kalır. Ayrıca motorun düşürdüğü tuş simgeleri çeviriye geri konur (`keepIcons`).
public enum UiPrompts {
    private static let commands: [String: String] = [
        "ADD MARKER": "İşaret ekle", "SET DESTINATION": "Hedef belirle", "REMOVE DESTINATION": "Hedefi kaldır",
        "RECENTER": "Ortala", "2D VIEW": "2B görünüm", "3D VIEW": "3B görünüm", "ZOOM": "Yakınlaştır",
        "EXIT": "Çıkış", "BACK": "Geri", "SELECT": "Seç", "CLOSE": "Kapat", "CLOSE MENU": "Menüyü kapat",
        "MENU": "Menü", "OPEN": "Aç", "NEXT PAGE": "Sonraki sayfa", "PREVIOUS PAGE": "Önceki sayfa",
        "SHOW TEXT": "Metni göster", "SHOW": "Göster", "FLIP": "Çevir", "PUT DOWN": "Bırak", "PICK UP": "Al",
        "DRINK": "İç", "EAT": "Ye", "WEAR": "Giy", "TAKE OFF": "Çıkar", "BUY": "Satın al", "SELL": "Sat",
        "PAY": "Öde", "PAY LATER": "Sonra öde", "PAY NOW": "Şimdi öde", "ACCEPT": "Kabul et",
        "DECLINE": "Reddet", "YES": "Evet", "NO": "Hayır", "CONFIRM": "Onayla", "CANCEL": "İptal",
        "GOODBYE": "Hoşça kal", "GREET": "Selamla", "TALK": "Konuş", "DEFUSE": "Yatıştır",
        "PROVOKE": "Kışkırt", "WARN": "Uyar", "OFFER RIDE": "Arabaya çağır", "ENTER": "Gir",
        "LEAVE": "Ayrıl", "SKIP": "Geç", "CONTINUE": "Devam et", "RETRY": "Tekrar dene",
        "RESTORE ALL": "Hepsini geri yükle", "RESTORE DEFAULT": "Varsayılana döndür", "PREVIEW": "Önizle",
        "PAUSE PREVIEW": "Önizlemeyi durdur", "ADJUST": "Ayarla", "SCROLL": "Kaydır", "REFUEL": "Yakıt al",
        "LIFT": "Kaldır", "ORDER": "Sipariş ver", "MAYBE LATER": "Belki sonra", "PHOTO STUDIO": "Fotoğraf stüdyosu",
        "REMINDERS": "Hatırlatıcılar", "ROTATE": "Döndür", "INTERACT": "Etkileşim", "USE": "Kullan",
        "KNOCK": "Kapıyı çal", "CALL": "Ara", "HANG UP": "Telefonu kapat", "ANSWER": "Cevapla",
        "IGNORE": "Yoksay", "REPLY": "Yanıtla", "DELETE": "Sil", "SAVE": "Kaydet", "VIEW": "Görüntüle",
        "INSPECT": "İncele", "SEARCH": "Ara", "HIDE": "Saklan", "JOIN": "Katıl", "TERMS": "Koşullar",
        "MARKERS": "İşaretler", "DROP": "Bırak", "EQUIP": "Kuşan", "UNEQUIP": "Çıkar", "STORE": "Dükkân",
        "HELP": "Yardım", "OPTIONS": "Seçenekler", "CAMERA": "Kamera", "TOGGLE": "Aç / kapat", "GRAB": "Tut",
        "SIT": "Otur", "STAND UP": "Kalk", "SLEEP": "Uyu", "SHOWER": "Duş al", "TURN OFF": "Kapat",
        "TURN ON": "Aç", "WATCH": "İzle", "LISTEN": "Dinle", "DRIVE": "Sür", "GET IN": "Bin",
        "GET OUT": "İn", "STEAL": "Çal", "HACK": "Hackle", "LOOT": "Yağmala", "COLLECT": "Topla",
    ]

    private static let lowerShoulder = try! NSRegularExpression(pattern: "\\b([lr])([123])\\b")

    private static func tokens(_ text: String) -> [String] { text.split(separator: " ").map(String.init) }

    /// Sözcükler tuş çubuğu sözlüğündeki bir komut mu ("Talk", "BACK", "Pick up"; büyük-küçük harf fark etmez).
    public static func isCommand(_ words: String) -> Bool {
        commands[words.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()] != nil
    }

    /// Tuş çubuğundaki komutun Türkçesi: büyük harfli, simgesi bir yanında duran ("OPEN ✕", "✕ OPEN") ya da tek başına
    /// büyük harfli komut ("REFUEL"). Sözlükte yoksa nil.
    public static func translate(_ paragraph: String) -> String? {
        let tokens = tokens(paragraph)
        guard !tokens.isEmpty else { return nil }
        var start = 0, end = tokens.count
        while start < end && ButtonPrompts.isIcon(tokens[start]) { start += 1 }
        while end > start && ButtonPrompts.isIcon(tokens[end - 1]) { end -= 1 }
        guard end > start else { return nil }
        let words = tokens[start..<end].joined(separator: " ").trimmingCharacters(in: CharacterSet(charactersIn: ".,:!?"))
        // Yalnız büyük harfli komut: sıradan cümledeki "open" / "Back" sözlükle değişmez.
        guard words.contains(where: \.isLetter), words == words.uppercased(), let turkish = commands[words.uppercased()] else { return nil }
        let before = tokens[0..<start].joined(separator: " ")
        let after = tokens[end...].joined(separator: " ")
        return [before, turkish, after].filter { !$0.isEmpty }.joined(separator: " ")
    }

    /// Çeviri motorunun düşürdüğü tuş simgelerini geri koyar: aslında başta duran simge başa, öteki simgeler sona
    /// (sıralarıyla). Motorun küçülttüğü omuz tuşu ("l1") yeniden büyük harf olur.
    public static func keepIcons(source: String, translation: String) -> String {
        var fixedCase = Regexes.replace(lowerShoulder, in: translation) { match, ns in
            Regexes.group(match, 1, in: ns).uppercased() + Regexes.group(match, 2, in: ns)
        }
        // Xbox'ın omuz ve tetik tuşu ("LT") da küçültülebilir ("lt"); yalnız kaynakta geçen ad düzeltilir.
        var names: [String] = []
        for name in tokens(source).map({ $0.trimBoth(".,:") }) where ButtonTokens.xboxNames.contains(name) && !names.contains(name) {
            names.append(name)
        }
        for name in names {
            let pattern = try! NSRegularExpression(pattern: "(?<![\\p{L}\\p{N}_])\(name)(?![\\p{L}\\p{N}_])", options: .caseInsensitive)
            fixedCase = Regexes.replace(pattern, in: fixedCase, with: name)
        }
        var have = tokens(fixedCase)
        var leading: [String] = []
        var trailing: [String] = []
        var seenWord = false
        for token in tokens(source) {
            if !ButtonPrompts.isIcon(token) || token == "O" || token == "X" {
                seenWord = true
                continue
            }
            if let index = have.firstIndex(of: token) {
                have.remove(at: index)
                continue
            }
            if seenWord { trailing.append(token) } else { leading.append(token) }
        }
        if leading.isEmpty && trailing.isEmpty { return fixedCase }
        return (leading + [fixedCase.trimmingCharacters(in: .whitespacesAndNewlines)] + trailing).joined(separator: " ")
    }
}

/// Ekranda zaten Türkçe olan satır (PS5'in Türkçe arayüzü, oyunun Türkçe yazıları): hedef dil Türkçeyken çevirmene
/// gönderilmez (Windows: TurkishText.cs). Ölçüt temkinlidir: Türkçe harf ve bilinen bir Türkçe sözcük birlikte bulunmalı.
public enum TurkishText {
    private static let letters: Set<Character> = ["ğ", "Ğ", "ş", "Ş", "ı", "İ", "ü", "Ü", "ö", "Ö", "ç", "Ç"]

    static let commonWords: [String] = [
        "daha", "sonra", "simdi", "degil", "haydi", "tamam", "evet", "hayir", "devam", "iptal", "giris", "guncelle",
        "guncellemesi", "yazilimi", "kurulum", "sirasinda", "sisteminizi", "sistemindeki", "kapatmayin", "baglanti",
        "baglantinizi", "uzaktan", "oynama", "dosyasi", "gostergesi", "kablosunu", "bir",
    ].map { PoolFilter.fold($0).joined() }

    private static let known = PoolFilter.turkishWords + commonWords

    /// Satır yüksek güvenle Türkçe mi: en az bir Türkçe harf ve en az bir bilinen Türkçe sözcük.
    public static func isSure(_ text: String) -> Bool {
        guard text.contains(where: { letters.contains($0) }) else { return false }
        return PoolFilter.fold(text).contains { word in known.contains { PoolFilter.isClose(word, $0) } }
    }
}
