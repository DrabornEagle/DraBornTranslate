// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Yazı tanımanın atladığı ya da yanlış okuduğu kumanda simgesini görüntüden tanır (Windows: ButtonIcons.cs, Android:
/// ButtonIcons.kt; aynı kurallar ve sabitler; Nino Game Translator'ın AGPL-3.0 ButtonIcons.swift'inin uyarlaması).
/// Oyunlar düğmeyi yazının içine küçük bir resim olarak koyar ("Hold ⓧ to climb"); yazı tanıma o resmi ya hiç okumaz ya
/// da "•", "O", "X" sanır.
///
/// Sıra: simgenin kendisi bulunur (kesitteki en büyük, kareye yakın parlak leke). Renkliyse PlayStation yüz tuşudur (△
/// yeşil, ✕ mavi, □ pembe, ○ kırmızı) ya da haritadaki işarettir. Artı biçimliyse yön tuşudur, enliyse dokunmatik
/// yüzeydir. Değilse içindeki yazı ("L2", "R1") okunur; yazı yoksa içindeki sembol ✕ ○ △ □ şablonlarıyla karşılaştırılır.
/// Yalnız `PixelImage` üstünde çalışır (platforma bağlı değildir; testler yapay simgelerle sınar).
public enum ButtonIcons {
    /// Geliştirme: simge kararlarının izi (testler için).
    static var trace: ((String) -> Void)?

    /// `box`: simgenin görüntüdeki kabaca yeri (0–1, üstten). Tanınamazsa nil.
    /// `readText`: küçük bir kesitteki yazıyı okur ("L2" gibi); verilmezse yazılı simgeler tanınmaz.
    /// `strict`: satırın kenarında aranırken (orada simge olmayabilir) yalnız belirgin, tek başına duran bir düğme rozeti
    /// kabul edilir; arka plandaki leke simge sanılmasın.
    /// `line` ve `readWords`: simgenin içinde durduğu satır ve sözcükleri yerleriyle okuyan tanıyıcı. Yazılı simge önce
    /// satırın siyah-beyaz okumasında aranır: simgenin çerçevesi silinir, satır okunur, simgenin yerine düşen sözcük alınır.
    public static func classify(_ image: PixelImage, box: TextBox, readText: ((PixelImage) -> String?)? = nil, strict: Bool = false,
                                line: TextBox? = nil, readWords: ((PixelImage) -> [OcrWord])? = nil,
                                layout: ButtonLayoutVote? = nil) -> String? {
        guard let at = around(image, box) else { return nil }
        var readLine: ((Int, Int, Int, Int) -> String?)?
        if let readWords, let line, let lineAt = around(image, line) {
            readLine = { x0, y0, x1, y1 in wordAt(lineAt, at.x + x0, at.y + y0, at.x + x1, at.y + y1, readWords) }
        }
        return classify(at.crop, readText: readText, strict: strict, readLine: readLine, layout: layout)
    }

    private struct Placed { let crop: PixelImage; let x: Int; let y: Int }

    /// Satır siyah-beyaz okunur; simgenin (görüntüde x0..x1, y0..y1) çerçevesi silinir, yalnız içi kalır. Simgenin
    /// ortasına düşen sözcük döner.
    private static func wordAt(_ line: Placed, _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int, _ readWords: (PixelImage) -> [OcrWord]) -> String? {
        let bin = binarized(line.crop)
        let cx = Double(x0 + x1) / 2 - Double(line.x)
        let cy = Double(y0 + y1) / 2 - Double(line.y)
        let rx = max(1.0, Double(x1 - x0) / 2)
        let ry = max(1.0, Double(y1 - y0) / 2)
        // Dolu beyaz daire (GTA 6: içinde koyu "R3"): siyah-beyaz okumada daire siyah, yazı beyaz kalır ve okunmaz. İçi
        // çoğunlukla siyahsa ters çevrilir, yazı beyaz zeminde siyah olur.
        var inside = 0, dark = 0
        for y in Swift.max(0, y0 - line.y)..<Swift.max(Swift.max(0, y0 - line.y), Swift.min(bin.height, y1 - line.y)) {
            for x in Swift.max(0, x0 - line.x)..<Swift.max(Swift.max(0, x0 - line.x), Swift.min(bin.width, x1 - line.x)) {
                let dx = (Double(x) - cx) / rx, dy = (Double(y) - cy) / ry
                if dx * dx + dy * dy > 0.8 * 0.8 { continue }
                inside += 1
                if bin.blue(x, y) == 0 { dark += 1 }
            }
        }
        let filled = inside > 0 && dark * 2 > inside
        let ys = Swift.max(0, y0 - line.y - 2), ye = Swift.min(bin.height, y1 - line.y + 3)
        let xs = Swift.max(0, x0 - line.x - 2), xe = Swift.min(bin.width, x1 - line.x + 3)
        if ys < ye && xs < xe {
            for y in ys..<ye {
                for x in xs..<xe {
                    let dx = (Double(x) - cx) / rx, dy = (Double(y) - cy) / ry
                    if dx * dx + dy * dy <= 0.8 * 0.8 {
                        if filled {
                            let v = 255 - bin.blue(x, y)
                            bin.set(x, y, v, v, v)
                        }
                        continue
                    }
                    bin.set(x, y, 255, 255, 255)
                }
            }
        }
        let map = paddedMap(bin)
        let left = Double(map.x + x0 - line.x) / map.width
        let right = Double(map.x + x1 - line.x) / map.width
        let top = Double(map.y + y0 - line.y) / map.height
        let bottom = Double(map.y + y1 - line.y) / map.height
        let words = readWords(map.image)
        trace?("satır okuması: " + words.map(\.text).joined(separator: " "))
        return words.first { word in
            let mx = (word.box.left + word.box.right) / 2, my = (word.box.top + word.box.bottom) / 2
            return mx >= left && mx <= right && my >= top && my <= bottom
        }?.text
    }

    /// `readLine`: simgenin (kesitte x0, y0, x1, y1) yerindeki sözcüğü satırın okumasından verir.
    /// `layout`: beklenen kumanda (PlayStation / Xbox / Otomatik). Otomatik'te kesin biçimli simgeler (dairede okunan "A",
    /// üçgen) kararı besler; belirsiz olanlar (dairede X: ✕ mı Ⓧ mi) o ana kadarki karara göre okunur.
    public static func classify(_ crop: PixelImage, readText: ((PixelImage) -> String?)? = nil, strict: Bool = false,
                                readLine: ((Int, Int, Int, Int) -> String?)? = nil, layout: ButtonLayoutVote? = nil) -> String? {
        let vote = layout ?? ButtonLayoutVote()
        let (token, sure) = decide(crop, readText, strict, readLine, vote)
        // Yalnız kesin biçimli simge kararı besler: dairedeki X ya da yön tuşu iki ailede de olabilir.
        if let token, sure, let family = ButtonTokens.familyOf(token) { vote.add(family) }
        if let token { trace?("sonuç \(token)\(sure ? " (kesin)" : "") aile \(vote.current)") }
        return token
    }

    private static func isXboxFace(_ token: String?) -> Bool {
        token == ButtonTokens.a || token == ButtonTokens.b || token == ButtonTokens.x || token == ButtonTokens.y
    }

    /// Büyük harfin daire içindeki işareti ("B" → "Ⓑ").
    private static func circled(_ c: Character) -> String {
        guard let ascii = c.asciiValue, let scalar = Unicode.Scalar(0x24B6 + UInt32(ascii) - 65) else { return String(c) }
        return String(Character(scalar))
    }

    private static func decide(_ crop: PixelImage, _ readText: ((PixelImage) -> String?)?, _ strict: Bool,
                               _ readLine: ((Int, Int, Int, Int) -> String?)?, _ vote: ButtonLayoutVote) -> (String?, Bool) {
        let none: (String?, Bool) = (nil, false)
        guard let gray = Gray.from(crop, 48), let blob = gray.iconBlob() ?? gray.iconBlob(grow: true) else {
            trace?("leke yok (\(crop.width)x\(crop.height))")
            return none
        }
        let w = blob.maxX - blob.minX + 1, h = blob.maxY - blob.minY + 1
        let aspect = Double(w) / Double(h)
        trace?("leke \(blob.minX),\(blob.minY)-\(blob.maxX),\(blob.maxY) / \(gray.width)x\(gray.height) oran \(aspect) doygunluk \(blob.saturation)")
        if strict {
            // Kesit satırın iki katı yüksekliğinde: rozet satır boyunda (kesitin %27–65'i), kareye yakın, kenara değmiyor.
            let relative = Double(h) / Double(gray.height)
            if relative <= 0.27 || relative >= 0.65 || aspect <= 0.78 || aspect >= 1.28 ||
                blob.minX <= 0 || blob.minY <= 0 || blob.maxX >= gray.width - 1 || blob.maxY >= gray.height - 1 { return none }
        }
        let family = vote.current
        let forced = vote.setting != .auto
        let leansXbox = vote.leansXbox
        // Otomatik'te iki ailenin şablonları da denenir; seçilen ailede öteki ailenin şekilleri aranmaz.
        let letters = !(forced && family == .playstation)
        let symbols = family != .xbox
        var shapeCache: Shape??
        func shapeOf() -> Shape? {
            if let cached = shapeCache { return cached }
            let found = gray.symbol(blob, letters: letters, symbols: symbols)
            shapeCache = .some(found)
            return found
        }
        // Yazılı simge (L2, R1, LT, sol çubuk "L"): önce satırın siyah-beyaz okumasında, simgenin yerine düşen sözcüğe bakılır.
        // Renkli yazılmış ad (GTA 6'da pembe "Donnie") renk denetiminde pembe □ sanılıyordu; önce sözcüğe bakılır.
        if aspect >= 0.62, let readLine {
            let scale = Double(crop.height) / Double(gray.height)
            let word = readLine(Int(Double(blob.minX) * scale), Int(Double(blob.minY) * scale),
                                Int(Double(blob.maxX + 1) * scale), Int(Double(blob.maxY + 1) * scale))
            trace?("simgenin yerindeki sözcük: '\(word ?? "nil")'")
            if let found = label(word, stick: true, family: family, leanXbox: leansXbox) { return (found, sureName(found, word)) }
            let trimmed = word?.trimmingCharacters(in: .whitespacesAndNewlines)
            let read: Character? = trimmed == "x" || trimmed == "×" ? "X" : letter(word)
            // Dolu dairedeki çarpı satır okumasında "X" okunur (GTA 6). Xbox'ta aynı daire X tuşudur.
            if read == "X" { return (family == .xbox ? ButtonTokens.x : "✕", false) }
            // Dairedeki "A" / "B" / "Y": Xbox yüz tuşu. PlayStation'ın △'ı da "A" okunabildiği için Otomatik'te harf şekli
            // ya da Xbox rengi doğrulamalı.
            if let read, read == "A" || read == "B" || read == "Y", let face = ButtonTokens.xboxFace(read) {
                if family == .xbox { return (face, shapeOf()?.name == face) }
                if !forced {
                    let shape = shapeOf()?.name
                    if shape == face || (blob.saturation > 0.28 && ButtonTokens.xboxFaceByHue(blob.hue) == face &&
                                         !["△", "○", "□"].contains(shape ?? "")) {
                        return (face, true)
                    }
                }
            }
            // Simgenin yerinde gerçek bir sözcük var: renkli yazılmış ad ("Follow Brian") simge değildir.
            if let word, word.filter(\.isLetter).count >= 2 { return none }
            // Simgede rakam okundu ("2"): yarısı okunmuş "R2" rozeti; yüz tuşu sembolü değildir.
            if let word, word.contains(where: \.isNumber) { return none }
            // Yazı tanıma daire içindeki harfi ("L3") "@" okuyor: yüz tuşu sembolü değildir. Xbox'ın "A" dairesi de "@"
            // okunabilir: harf şekli bakılır.
            if word?.contains("@") == true && blob.saturation <= 0.28 && !(letters && shapeOf()?.name == ButtonTokens.a) { return none }
        }
        // Xbox'ta koyu dairedeki renkli harf (GTA V: yeşil "A") lekenin küçük bir parçasıdır, ortalama doygunluk düşük kalır.
        if blob.saturation > (family == .xbox ? 0.18 : 0.28) {
            trace?("renk tonu \(blob.hue)")
            let round = aspect > 0.7 && aspect < 1.4
            if family == .xbox {
                // Xbox'ın yüz tuşları: yeşil A, kırmızı B, mavi X, sarı Y.
                if round, let shape = shapeOf(), isXboxFace(shape.name) { return (shape.name, false) }
                if round, let face = ButtonTokens.xboxFaceByHue(blob.hue) { return (face, false) }
                if let one = letter(readText?(padded(crop))) { return (ButtonTokens.xboxFace(one) ?? circled(one), false) }
                return ("◆", false)
            }
            // PlayStation'ın yüz tuşları renklidir: △ yeşil, ○ kırmızı, ✕ mavi, □ pembe. Sarı/turuncu daire düğme değil,
            // haritadaki görev işaretidir. Otomatik'te önce harf şekline bakılır (dairedeki harf de okunmalı).
            if !forced && round, let shape = shapeOf(), shape.sure, isXboxFace(shape.name),
               ButtonTokens.xboxFaceByHue(blob.hue) == shape.name,
               let confirm = letter(readText?(padded(crop))), ButtonTokens.xboxFace(confirm) == shape.name {
                return (shape.name, true)
            }
            if round, let face = faceButtonByHue(blob.hue) { return (face, shapeOf()?.name == face) }
            if let one = letter(readText?(padded(crop))) {
                // Sarı dairede "Y": Xbox'ın Y tuşu. PlayStation'da dairedeki A/B/X/Y harfi harita işaretidir.
                if let xbox = ButtonTokens.xboxFace(one) {
                    if !forced && ButtonTokens.xboxFaceByHue(blob.hue) == xbox { return (xbox, true) }
                    return ("◆", false)
                }
                return (circled(one), false)
            }
            return ("◆", false)
        }
        if aspect < 0.62 { return none }
        if let pad = gray.dpad(blob) { return (pad, false) }
        var readLetters: [Character] = []
        // Yazı simgenin kendisinden okunur (sıkı kesit; koyu yazı beyaz kutuda olabilir, ters renkle de denenir).
        if let readText {
            let scale = Double(crop.height) / Double(gray.height)
            let padX = Double(w) * 0.15, padY = Double(h) * 0.15
            let x0 = Int(max(0, (Double(blob.minX) - padX) * scale))
            let y0 = Int(max(0, (Double(blob.minY) - padY) * scale))
            let x1 = Int(min(Double(crop.width), (Double(blob.maxX + 1) + padX) * scale))
            let y1 = Int(min(Double(crop.height), (Double(blob.maxY + 1) + padY) * scale))
            let icon = x1 - x0 >= 4 && y1 - y0 >= 4 ? crop.crop(x0, y0, x1 - x0, y1 - y0) : crop
            var lastRead: String?
            func named(_ text: String?) -> String? {
                lastRead = text
                trace?("simge okuması: '\(text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "nil")'")
                if let one = letter(text) { readLetters.append(one) }
                return label(text, family: family, leanXbox: leansXbox)
            }
            // Son çare: yalnız simgenin içi siyah-beyaz okunur (ince çerçeveli kapsüldeki "RT").
            if let found = named(readText(padded(icon))) ?? named(readText(padded(inverted(icon)))) ?? named(readText(padded(crop)))
                ?? named(readText(padded(interior(icon)))) {
                return (found, sureName(found, lastRead))
            }
        }
        // Dokunmatik yüzey: yazısız, en boyunun ~1,6 katı, içi dikey çizgili (Xbox'ta yoktur).
        if symbols && aspect > 1.45 && gray.stripes(blob) >= 4 { return ("▦", true) }
        // Yazılı kutu ama yazı okunamadı: daire/kare sembol sanılmasın.
        if aspect > 1.15 { return none }
        guard let found = shapeOf() else { return none }
        if found.score <= (strict ? 0.5 : 0.45) { return none }
        switch found.name {
        case "✕":
            // Çarpı ile X harfi aynı biçimdedir: aileye göre okunur.
            return (family == .xbox ? ButtonTokens.x : "✕", false)
        case ButtonTokens.a, ButtonTokens.b, ButtonTokens.y:
            // Harf şekli tek başına yetmez (sol çubuk simgesi ve yarısı dolu "LT" dairesi "B"ye benziyor): dairedeki harf
            // de okunmuş olmalı.
            return readLetters.contains { ButtonTokens.xboxFace($0) == found.name } ? (found.name, family != .xbox) : none
        case ButtonTokens.view, ButtonTokens.menu:
            // View ve Menu yalnız Xbox beklenirken ve satırın içinde aranır.
            return family == .xbox && !strict ? (found.name, false) : none
        default:
            return (found.name, found.sure)
        }
    }

    /// Okunan yazı tek bir büyük harf mi ("A", "(B)", "[Y]"); değilse nil.
    static func letter(_ read: String?) -> Character? {
        guard let read else { return nil }
        let core = read.trimmingCharacters(in: .whitespacesAndNewlines).trimBoth("()[]{}<>.,:")
        guard core.count == 1, let c = core.first, let ascii = c.asciiValue, ascii >= 65, ascii <= 90 else { return nil }
        return c
    }

    /// Ad kendi ailesini kesin gösteriyor mu: okunan yazıda gerçekten "LT" / "RS" (Xbox) ya da "L2" / "R1" / OPTIONS
    /// (PlayStation) yazıyor. Benzer harflerden düzeltilen ad ("RI" → R1, "LB" → L3) kesin sayılmaz.
    static func sureName(_ named: String, _ read: String?) -> Bool {
        let text = String((read ?? "").uppercased().filter(\.isLetterOrDigit))
        switch named {
        case "LT", "RT", "LS", "RS", "L1", "L2", "R1", "R2": return text.contains(named)
        case "OPTIONS": return true
        default: return false
        }
    }

    /// Renk tonundan (0–360) PlayStation yüz tuşu: yeşil △, mavi ✕, pembe □, kırmızı ○; sarı/turuncu düğme değildir.
    public static func faceButtonByHue(_ hue: Double) -> String? {
        if hue >= 120 && hue < 190 { return "△" }
        if hue >= 190 && hue < 260 { return "✕" }
        if hue >= 285 && hue < 335 { return "□" }
        if hue >= 335 || hue < 18 { return "○" }
        return nil
    }

    private static let shoulderInside = Patterns.make("[LR][123IZ7]")
    private static let xboxShoulderInside = Patterns.make("[LR][TBS8]")

    /// Simgenin içindeki "L2", "R1", "L3", "OPTIONS" (PlayStation) ya da "LB", "RT", "LS" (Xbox) gibi yazı; düğme adı
    /// değilse nil. Benzer harfler aileye göre düzeltilir: PlayStation'da "RI" → R1, "LB" → L3; Xbox'ta "RI" → RT,
    /// "L8" → LB. "LT" / "RT" / "LS" / "RS" her ailede Xbox'tır.
    /// `stick`: yazı simgenin tam yerinden okundu; tek "L" / "R" sol / sağ çubuktur (Xbox oyunları "LS" / "RS" yazar).
    /// `leanXbox`: Otomatik'te karar Xbox'a yatıyor; okunan "LB" / "RB" L3 / R3'e çevrilmez.
    public static func label(_ read: String?, stick: Bool = false, family: ButtonFamily = .playstation, leanXbox: Bool = false) -> String? {
        guard let read else { return nil }
        let xbox = family == .xbox
        var text = String(read.uppercased().filter(\.isLetterOrDigit))
        if stick && (text == "L" || text == "R") { return xbox ? nil : text }
        if text.hasPrefix("OPTION") { return "OPTIONS" }
        if xbox && stick && text == "VIEW" { return ButtonTokens.view }
        if xbox && stick && text == "MENU" { return ButtonTokens.menu }
        if text.count == 3 && (text.first == "C" || text.first == "I") { text = String(text.dropFirst()) }
        // Kesite komşu harf karışabilir ("DL2T"): kısa yazının içinde L/R + rakam (Xbox'ta harf) aranır.
        if (3...5).contains(text.count), let inside = Patterns.firstMatch(xbox ? xboxShoulderInside : shoulderInside, text) { text = inside }
        let chars = Array(text)
        guard chars.count == 2, chars[0] == "L" || chars[0] == "R" else { return nil }
        // Xbox'ın tetik ve çubuk tuşu adları PlayStation'da yoktur.
        if chars[1] == "T" || chars[1] == "S" { return text }
        if xbox {
            let second: Character
            switch chars[1] {
            case "I", "1", "7", "L": second = "T"
            case "B", "8", "3": second = "B"
            case "5": second = "S"
            default: second = chars[1]
            }
            let named = String([chars[0], second])
            if ButtonTokens.xboxNames.contains(named) { return named }
        }
        if leanXbox && (chars[1] == "B" || chars[1] == "8") { return String([chars[0], "B"]) }
        let digit: Character
        switch chars[1] {
        case "I", "7": digit = "1"
        case "Z": digit = "2"
        case "B": digit = "3"
        default: digit = chars[1]
        }
        let name = String([chars[0], digit])
        return ["L1", "L2", "L3", "R1", "R2", "R3"].contains(name) ? name : nil
    }

    /// Simgenin aranacağı kesit: boşluğun tamamı (dar ise satır yüksekliği kadar genişletilir), satırın 2 katı yükseklik.
    static func cropAround(_ image: PixelImage, _ box: TextBox) -> PixelImage? { around(image, box)?.crop }

    private static func around(_ image: PixelImage, _ box: TextBox) -> Placed? {
        let w = Double(image.width), h = Double(image.height)
        let lineHeight = (box.bottom - box.top) * h
        let cy = (box.top + box.bottom) / 2 * h
        var left = box.left * w, right = box.right * w
        let minWidth = lineHeight * 2.0
        if right - left < minWidth {
            let cx = (left + right) / 2
            left = cx - minWidth / 2
            right = cx + minWidth / 2
        }
        let x0 = Int(floor(max(0, left - 2)))
        let y0 = Int(floor(max(0, cy - lineHeight)))
        let x1 = Int(ceil(min(w, right + 2)))
        let y1 = Int(ceil(min(h, cy + lineHeight)))
        if x1 - x0 < 6 || y1 - y0 < 6 { return nil }
        return Placed(crop: image.crop(x0, y0, x1 - x0, y1 - y0), x: x0, y: y0)
    }

    /// Parlak yazı siyah, gerisi beyaz (Otsu eşiği): yazı tanıma simgedeki ince beyaz yazıyı böyle okuyor.
    static func binarized(_ image: PixelImage) -> PixelImage {
        var values = [Double](repeating: 0, count: image.width * image.height)
        for y in 0..<image.height { for x in 0..<image.width { values[y * image.width + x] = image.luma(x, y) } }
        let t = otsu(values)
        let result = PixelImage(width: image.width, height: image.height)
        for i in values.indices {
            let v = values[i] > t ? 0 : 255
            result.pixels[i] = PixelImage.opaque(v, v, v)
        }
        return result
    }

    /// Simgenin yalnız içi: siyah-beyaza çevrilir (parlak yazı siyah), kenardaki çerçeve (daire, kapsül) silinir; içi
    /// çoğunlukla siyahsa (dolu daire) ters çevrilir.
    private static func interior(_ icon: PixelImage) -> PixelImage {
        let bin = binarized(icon)
        let cx = Double(bin.width) / 2, cy = Double(bin.height) / 2
        let rx = Double(bin.width) * 0.5 * 0.7, ry = Double(bin.height) * 0.5 * 0.7
        func outside(_ x: Int, _ y: Int) -> Bool {
            let dx = (Double(x) - cx) / rx, dy = (Double(y) - cy) / ry
            return dx * dx + dy * dy > 1
        }
        var inside = 0, dark = 0
        for y in 0..<bin.height {
            for x in 0..<bin.width where !outside(x, y) {
                inside += 1
                if bin.blue(x, y) == 0 { dark += 1 }
            }
        }
        let filled = inside > 0 && dark * 2 > inside
        for y in 0..<bin.height {
            for x in 0..<bin.width {
                let v = outside(x, y) ? 255 : filled ? 255 - bin.blue(x, y) : bin.blue(x, y)
                bin.set(x, y, v, v, v)
            }
        }
        return bin
    }

    /// Renkleri ters çevrilmiş kopya (beyaz kutudaki koyu yazı için).
    private static func inverted(_ image: PixelImage) -> PixelImage {
        let result = PixelImage(width: image.width, height: image.height)
        for y in 0..<image.height {
            for x in 0..<image.width { result.set(x, y, 255 - image.red(x, y), 255 - image.green(x, y), 255 - image.blue(x, y)) }
        }
        return result
    }

    /// Yazı tanıma çok kısa ve tek başına duran yazıyı ("L1") çoğu zaman okumaz: kesit, kenar rengiyle doldurulmuş geniş
    /// bir zeminin ortasına konur ve en az 140 piksel boya büyütülür.
    public static func padded(_ image: PixelImage) -> PixelImage { paddedMap(image).image }

    /// `padded` ve görüntünün zemindeki yeri (büyütmeden önceki piksel ve zemin boyutu).
    private struct PaddedMap { let image: PixelImage; let x: Int; let y: Int; let width: Double; let height: Double }

    private static func paddedMap(_ image: PixelImage) -> PaddedMap {
        let w = image.width, h = image.height
        var sum = 0.0, n = 0
        if h > 0 {
            for x in 0..<w {
                for y in [0, h - 1] {
                    sum += Double(image.red(x, y) + image.green(x, y) + image.blue(x, y))
                    n += 3
                }
            }
        }
        let edge = Int(min(255, max(0, sum / Double(max(n, 1)))))
        let cw = w * 4, ch = h * 2
        let canvas = PixelImage(width: cw, height: ch, pixels: [UInt32](repeating: PixelImage.opaque(edge, edge, edge), count: cw * ch))
        let ox = (cw - w) / 2, oy = (ch - h) / 2
        for y in 0..<h { for x in 0..<w { canvas.pixels[(oy + y) * cw + ox + x] = image.pixels[y * w + x] } }
        let factor = Int(ceil(140.0 / Double(max(ch, 1))))
        return PaddedMap(image: factor > 1 ? canvas.scaled(min(factor, 8)) : canvas, x: ox, y: oy, width: Double(cw), height: Double(ch))
    }

    /// Otsu eşiği: parlak ile koyuyu en iyi ayıran değer.
    static func otsu(_ values: [Double]) -> Double {
        var hist = [Double](repeating: 0, count: 64)
        for v in values { hist[min(63, max(0, Int(v * 63.999)))] += 1 }
        let total = Double(values.count)
        var sumAll = 0.0
        for i in 0..<64 { sumAll += Double(i) * hist[i] }
        var sumB = 0.0, wB = 0.0, best = 0.0
        var threshold = 32
        for i in 0..<64 {
            wB += hist[i]
            if wB == 0 { continue }
            let wF = total - wB
            if wF == 0 { break }
            sumB += Double(i) * hist[i]
            let mB = sumB / wB, mF = (sumAll - sumB) / wF
            let between = wB * wF * (mB - mF) * (mB - mF)
            if between > best {
                best = between
                threshold = i
            }
        }
        return (Double(threshold) + 0.5) / 64
    }

    /// Kesitin gri tonlu ve renk doygunluğu tutan küçük kopyası (satırlar üstten).
    private final class Gray {
        let width: Int
        let height: Int
        var luma: [Double]
        var sat: [Double]
        var hue: [Double]

        struct Blob { let minX, minY, maxX, maxY: Int; let mask: [Bool]; let saturation: Double; let hue: Double }

        init(width: Int, height: Int) {
            self.width = width
            self.height = height
            luma = [Double](repeating: 0, count: width * height)
            sat = luma
            hue = luma
        }

        static func from(_ image: PixelImage, _ target: Int) -> Gray? {
            if image.width < 2 || image.height < 2 { return nil }
            let scale = Double(target) / Double(image.height)
            let width = max(4, Int(Double(image.width) * scale))
            let gray = Gray(width: width, height: target)
            for y in 0..<target {
                // Kutu ortalaması: küçültürken ince çizgiler kaybolmasın.
                let sy0 = Int(Double(y) / scale)
                let sy1 = max(sy0 + 1, min(image.height, Int(Double(y + 1) / scale)))
                for x in 0..<width {
                    let sx0 = Int(Double(x) / scale)
                    let sx1 = max(sx0 + 1, min(image.width, Int(Double(x + 1) / scale)))
                    var r = 0.0, g = 0.0, b = 0.0
                    var count = 0
                    let ya = min(sy0, image.height - 1), xa = min(sx0, image.width - 1)
                    if ya < sy1 && xa < sx1 {
                        for yy in ya..<sy1 {
                            for xx in xa..<sx1 {
                                b += Double(image.blue(xx, yy))
                                g += Double(image.green(xx, yy))
                                r += Double(image.red(xx, yy))
                                count += 1
                            }
                        }
                    }
                    r /= Double(count) * 255
                    g /= Double(count) * 255
                    b /= Double(count) * 255
                    let i = y * width + x
                    gray.luma[i] = 0.299 * r + 0.587 * g + 0.114 * b
                    let mx = max(r, g, b), mn = min(r, g, b)
                    gray.sat[i] = mx > 0.15 ? (mx - mn) / mx : 0
                    let d = mx - mn
                    if d < 1e-6 {
                        gray.hue[i] = 0
                    } else if mx == r {
                        gray.hue[i] = 60 * (((g - b) / d).truncatingRemainder(dividingBy: 6) + 6).truncatingRemainder(dividingBy: 6)
                    } else if mx == g {
                        gray.hue[i] = 60 * ((b - r) / d + 2)
                    } else {
                        gray.hue[i] = 60 * ((r - g) / d + 4)
                    }
                }
            }
            return gray
        }

        /// Kesitteki simge: yüksekliği kesitin üçte birinden büyük, kareye yakın, ortaya yakın en büyük parlak leke.
        /// `grow`: parlak alan bir piksel büyütülür; ince çizgili çerçeve (Starfield'ın "RT" kapsülü) küçültmede kopuk kalıyor.
        func iconBlob(grow: Bool = false) -> Blob? {
            let t = ButtonIcons.otsu(luma)
            var mask = luma.map { $0 > t }
            if grow {
                var grown = mask
                for y in 0..<height {
                    for x in 0..<width where mask[y * width + x] {
                        for dy in -1...1 {
                            for dx in -1...1 {
                                let nx = x + dx, ny = y + dy
                                if nx >= 0 && nx < width && ny >= 0 && ny < height { grown[ny * width + nx] = true }
                            }
                        }
                    }
                }
                mask = grown
            }
            var best: Blob?
            var bestScore = 0.0
            var stack: [Int] = []
            for start in 0..<(width * height) {
                if !mask[start] { continue }
                var pixels: [Int] = []
                mask[start] = false
                stack.append(start)
                var minX = width, minY = height, maxX = 0, maxY = 0
                while let p = stack.popLast() {
                    pixels.append(p)
                    let x = p % width, y = p / width
                    minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                    if x + 1 < width && mask[p + 1] { mask[p + 1] = false; stack.append(p + 1) }
                    if x > 0 && mask[p - 1] { mask[p - 1] = false; stack.append(p - 1) }
                    if y + 1 < height && mask[p + width] { mask[p + width] = false; stack.append(p + width) }
                    if y > 0 && mask[p - width] { mask[p - width] = false; stack.append(p - width) }
                }
                let bw = maxX - minX + 1, bh = maxY - minY + 1
                if bh < height / 3 || bh >= height { continue }
                let aspect = Double(bw) / Double(bh)
                if aspect <= 0.4 || aspect >= 2.2 { continue }
                let cx = Double(minX + maxX) / 2 / Double(width)
                let squareness = 1 - min(1.0, abs(log(aspect)))
                let score = Double(bw * bh) * (0.5 + squareness) * (1.2 - abs(cx - 0.5))
                if score <= bestScore { continue }
                bestScore = score
                let s = pixels.reduce(0.0) { $0 + sat[$1] } / Double(max(pixels.count, 1))
                // Renk tonu, doygun piksellerin ton açılarının ortalaması (kırmızı 0/360 sınırında bölünmesin diye vektörle).
                var hx = 0.0, hy = 0.0
                for i in pixels where sat[i] >= 0.25 {
                    hx += cos(hue[i] * .pi / 180) * sat[i]
                    hy += sin(hue[i] * .pi / 180) * sat[i]
                }
                let h = (atan2(hy, hx) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
                var blobMask = [Bool](repeating: false, count: width * height)
                for i in pixels { blobMask[i] = true }
                best = Blob(minX: minX, minY: minY, maxX: maxX, maxY: maxY, mask: blobMask, saturation: s, hue: h)
            }
            return best
        }

        /// Lekenin orta satırında açık-koyu geçiş sayısının yarısı (dokunmatik yüzey ızgarası çok çizgilidir).
        func stripes(_ b: Blob) -> Int {
            let y = (b.minY + b.maxY) / 2
            let row = (0..<(b.maxX - b.minX + 1)).map { luma[y * width + b.minX + $0] }
            let t = ButtonIcons.otsu(row)
            var flips = 0
            for i in 1..<max(1, row.count) where (row[i] > t) != (row[i - 1] > t) { flips += 1 }
            return flips / 2
        }

        /// Yön tuşu: artı biçimli leke; ötekilerden parlak kol yönü verir.
        func dpad(_ b: Blob) -> String? {
            let w = b.maxX - b.minX + 1, h = b.maxY - b.minY + 1
            if w < 6 || h < 6 { return nil }
            func cell(_ cx: Int, _ cy: Int) -> (on: Double, light: Double) {
                let x0 = b.minX + cx * w / 3
                let x1 = max(x0 + 1, b.minX + (cx + 1) * w / 3)
                let y0 = b.minY + cy * h / 3
                let y1 = max(y0 + 1, b.minY + (cy + 1) * h / 3)
                var on = 0, n = 0
                var light = 0.0
                for y in y0..<y1 {
                    for x in x0..<x1 {
                        n += 1
                        let p = y * width + x
                        if b.mask[p] { on += 1 }
                        light += luma[p]
                    }
                }
                return (Double(on) / Double(max(n, 1)), light / Double(max(n, 1)))
            }
            let corners = [cell(0, 0), cell(2, 0), cell(0, 2), cell(2, 2)].map(\.on)
            let arms = [("↑", cell(1, 0)), ("←", cell(0, 1)), ("→", cell(2, 1)), ("↓", cell(1, 2))]
            if corners.max()! >= 0.2 || !arms.allSatisfy({ $0.1.on > 0.35 }) { return nil }
            // Kararlı sıralama (eşitlikte ilk kol önde kalır).
            let sorted = arms.enumerated().sorted { $0.element.1.light != $1.element.1.light ? $0.element.1.light > $1.element.1.light : $0.offset < $1.offset }.map(\.element)
            return sorted[0].1.light > sorted[1].1.light * 1.12 ? sorted[0].0 : "✜"
        }

        /// Simgenin içindeki sembol: iç dairede azınlıkta kalan renk ✕ ○ △ □ (`symbols`) ve Xbox'ın A B Y harfleri, View ⧉
        /// ve Menu ☰ (`letters`) şablonlarıyla karşılaştırılır. Simgeler iç içe halkalı olabilir: birkaç iç yarıçapta
        /// aranır. Eşik uygulanmaz; çağıran puana bakar.
        func symbol(_ b: Blob, letters: Bool = false, symbols: Bool = true) -> Shape? {
            var best: Shape?
            for factor in [0.74, 0.6, 0.48] {
                guard let found = symbolAt(b, factor, letters, symbols) else { continue }
                if best == nil || found.score > best!.score { best = found }
            }
            return best
        }

        private struct Mark { let x: Double; let y: Double; let v: Double }

        private func symbolAt(_ b: Blob, _ factor: Double, _ letters: Bool, _ symbols: Bool) -> Shape? {
            let w = b.maxX - b.minX + 1, h = b.maxY - b.minY + 1
            let cx = Double(b.minX + b.maxX) / 2, cy = Double(b.minY + b.maxY) / 2
            let r = Double(min(w, h)) / 2 * factor
            if r < 4 { return nil }
            var inner: [Mark] = []
            for y in Int(cy - r)...Int(cy + r) {
                for x in Int(cx - r)...Int(cx + r) {
                    if x < 0 || y < 0 || x >= width || y >= height { continue }
                    let dx = Double(x) - cx, dy = Double(y) - cy
                    if dx * dx + dy * dy > r * r { continue }
                    inner.append(Mark(x: dx / r, y: dy / r, v: luma[y * width + x]))
                }
            }
            if inner.count <= 30 { return nil }
            let values = inner.map(\.v)
            if values.max()! - values.min()! <= 0.15 { return nil }
            let t = ButtonIcons.otsu(values)
            let bright = inner.filter { $0.v > t }.count
            let minorityIsBright = bright * 2 < inner.count
            let marks = inner.filter { minorityIsBright ? $0.v > t : $0.v <= t }
            if marks.count < 6 { return nil }
            // İşaretli noktalar sembolün kendi kutusuna oranlanır, 16x16 ızgarada şablonlarla karşılaştırılır.
            let mx0 = marks.map(\.x).min()!, mx1 = marks.map(\.x).max()!
            let my0 = marks.map(\.y).min()!, my1 = marks.map(\.y).max()!
            let n = Templates.n
            var grid = [Bool](repeating: false, count: n * n)
            for m in marks {
                let gx = min(n - 1, Int((m.x - mx0) / max(mx1 - mx0, 0.01) * Double(n)))
                let gy = min(n - 1, Int((m.y - my0) / max(my1 - my0, 0.01) * Double(n)))
                grid[gy * n + gx] = true
            }
            // İçinde yan yana iki parça olan işaret ("R3", "L3") yazıdır, sembol değildir: şablonla karşılaştırılmaz.
            // Örnekleme ızgarada tek hücrelik boşluklar bırakır: parçalar bir hücre büyütülüp sayılır.
            // Alt alta üç yatay çizgi ise Xbox'ın Menu tuşudur (☰); çizgiler yakın olduğu için yalnız yatayda büyütülmüş
            // ızgarada da bakılır.
            let parts = ButtonIcons.componentBoxes(Templates.grown(grid), n)
            if letters && parts.count < 3 && ButtonIcons.isMenu(ButtonIcons.componentBoxes(Templates.grownSideways(grid), n), n) {
                return Shape(name: ButtonTokens.menu, score: 0.9, sure: true)
            }
            if parts.count >= 2 {
                if letters && ButtonIcons.isMenu(parts, n) { return Shape(name: ButtonTokens.menu, score: 0.9, sure: true) }
                ButtonIcons.trace?("sembol \(factor): birden çok parça (yazı)")
                return nil
            }
            // Karenin köşeleri doludur; daire ve çarpıda köşe hücreleri boş kalır. Köşeleri yuvarlak kare (GTA 6) için
            // köşe hücresinin bir hücre içerisi de sayılır.
            var corners = 0
            for (qx, qy) in [(0, 0), (n - 3, 0), (0, n - 3), (n - 3, n - 3)] {
                var hit = false
                for yy in qy..<(qy + 3) where !hit {
                    for xx in qx..<(qx + 3) where grid[yy * n + xx] { hit = true; break }
                }
                if hit { corners += 1 }
            }
            // Kare ile daire, şeklin merkezden uzaklığıyla ayrılır: karede çapraz yönler kenar yönlerinden ~1,4 kat uzaktır.
            let corneriness = ButtonIcons.corneriness(marks.map { ($0.x - (mx0 + mx1) / 2, $0.y - (my0 + my1) / 2) })
            ButtonIcons.trace?("köşelilik \(corneriness)")
            var bestName: String?
            var bestScore = 0.0, bestSymbol = 0.0, bestLetter = 0.0
            let gridFill = Templates.fill(grid)
            for (name, template) in Templates.all {
                let letter = Templates.isLetter(name)
                // Çarpı X harfiyle aynıdır: iki ailede de aranır.
                if letter ? !letters : (!symbols && name != "✕") { continue }
                if name == "□" && corners < 4 { continue }
                // Harfin içi boşluklu: dolu leke (yarısı dolu "LT" dairesi) B sanılmasın diye doluluk da uymalı.
                if letter && abs(gridFill - Templates.fill(template)) > 0.25 { continue }
                let score = Templates.similarity(grid, template)
                ButtonIcons.trace?("sembol \(factor) \(name) \(score) köşe \(corners)")
                if letter { bestLetter = max(bestLetter, score) } else { bestSymbol = max(bestSymbol, score) }
                if bestName == nil || score > bestScore {
                    bestName = name
                    bestScore = score
                }
            }
            guard var name = bestName else { return nil }
            if name == "○" && corneriness > 1.10 { name = "□" } else if name == "□" && corneriness < 1.06 { name = "○" }
            // Kesin karar: harf, PlayStation şekillerinden belirgin biçimde iyi uyuyor (ya da tersi). Çarpı iki ailede de var.
            let margin = 0.06
            let sure = name != "✕" && (Templates.isLetter(name) ? bestScore - bestSymbol >= margin : bestScore - bestLetter >= margin)
            return Shape(name: name, score: bestScore, sure: sure)
        }
    }

    /// Şablonla eşleşen şekil: ad, benzerlik (0–1) ve öteki ailenin şekillerinden belirgin biçimde iyi mi.
    private struct Shape { let name: String; let score: Double; let sure: Bool }

    /// Alt alta, eşit aralıklı, en az ızgaranın yarısı genişliğinde üç yatay çizgi: Xbox'ın Menu tuşu (☰).
    private static func isMenu(_ found: [[Int]], _ n: Int) -> Bool {
        // Örnekleme çizgi kenarında tek satırlık kırıntı bırakabilir: en çok bir ince parça atlanır.
        let parts = found.count == 4
            ? Array(found.enumerated().sorted { a, b in
                let areaA = (a.element[2] - a.element[0] + 1) * (a.element[3] - a.element[1] + 1)
                let areaB = (b.element[2] - b.element[0] + 1) * (b.element[3] - b.element[1] + 1)
                return areaA != areaB ? areaA > areaB : a.offset < b.offset
            }.prefix(3).map(\.element))
            : found
        if parts.count != 3 { return false }
        let sorted = parts.enumerated().sorted { $0.element[1] != $1.element[1] ? $0.element[1] < $1.element[1] : $0.offset < $1.offset }.map(\.element)
        let nn = Double(n)
        if sorted.contains(where: { Double($0[2] - $0[0] + 1) < nn * 0.6 || Double($0[3] - $0[1] + 1) > nn * 0.4 }) { return false }
        // Üç çizgi aynı boyda ve hizada.
        let widths = sorted.map { $0[2] - $0[0] }, lefts = sorted.map { $0[0] }
        if Double(widths.max()! - widths.min()!) > nn * 0.2 || Double(lefts.max()! - lefts.min()!) > nn * 0.15 { return false }
        let c0 = Double(sorted[0][1] + sorted[0][3]) / 2
        let c1 = Double(sorted[1][1] + sorted[1][3]) / 2
        let c2 = Double(sorted[2][1] + sorted[2][3]) / 2
        return abs((c1 - c0) - (c2 - c1)) <= nn * 0.15
    }

    /// Çapraz yönlerdeki en uzak işaretin merkeze uzaklığı / eksen yönlerindeki en uzak işaretinki. Kare ~1,4, daire ~1
    /// (yalnız ○ / □ kararında kullanılır).
    private static func corneriness(_ points: [(Double, Double)]) -> Double {
        var far = [Double](repeating: 0, count: 16)
        for (x, y) in points {
            let bin = Int((atan2(y, x) + .pi) / (2 * .pi) * 16) % 16
            far[bin] = max(far[bin], (x * x + y * y).squareRoot())
        }
        // 16 dilim: 0, 4, 8, 12 eksen yönleri (sol, üst, sağ, alt); 2, 6, 10, 14 çaprazlar.
        let axis = (far[0] + far[4] + far[8] + far[12]) / 4
        let diagonal = (far[2] + far[6] + far[10] + far[14]) / 4
        return axis <= 0 ? 1 : diagonal / axis
    }

    /// Izgaradaki sekiz yönlü bağlı parçaların kutuları [minX, minY, maxX, maxY] (tek hücrelik kırıntılar sayılmaz).
    private static func componentBoxes(_ grid: [Bool], _ n: Int) -> [[Int]] {
        var seen = [Bool](repeating: false, count: grid.count)
        var boxes: [[Int]] = []
        var stack: [Int] = []
        for start in grid.indices {
            if !grid[start] || seen[start] { continue }
            var size = 0
            var minX = n, minY = n, maxX = 0, maxY = 0
            seen[start] = true
            stack.append(start)
            while let p = stack.popLast() {
                size += 1
                let x = p % n, y = p / n
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for dy in -1...1 {
                    for dx in -1...1 {
                        let nx = x + dx, ny = y + dy
                        if (dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= n || ny >= n { continue }
                        let q = ny * n + nx
                        if grid[q] && !seen[q] { seen[q] = true; stack.append(q) }
                    }
                }
            }
            if size > 2 { boxes.append([minX, minY, maxX, maxY]) }
        }
        return boxes
    }

    /// ✕ ○ △ □ ve Xbox'ın A B Y harfleri ile View ⧉ şablonları (16x16, sembolün kendi kutusuna oranlı çizgi).
    private enum Templates {
        static let n = 16

        static let all: [(String, [Bool])] = [
            ("✕", draw { x, y in abs(x - y) < 0.17 || abs(x + y - 1) < 0.17 }),
            ("○", draw { x, y in let d = ((x - 0.5) * (x - 0.5) + (y - 0.5) * (y - 0.5)).squareRoot(); return d > 0.34 && d < 0.52 }),
            ("□", draw { x, y in x < 0.18 || x > 0.82 || y < 0.18 || y > 0.82 }),
            // Tepe üstte; iki yan kenar ve taban.
            ("△", draw { x, y in y > 0.82 || abs(x - (0.5 - y / 2)) < 0.12 || abs(x - (0.5 + y / 2)) < 0.12 }),
            // A: üçgenin iki kenarı gibi iki bacak, tabanı yok; ortanın altında yatay çizgi.
            (ButtonTokens.a, draw { x, y in
                abs(x - (0.5 - y / 2)) < 0.13 || abs(x - (0.5 + y / 2)) < 0.13 ||
                    (abs(y - 0.63) < 0.08 && x > 0.5 - y / 2 && x < 0.5 + y / 2)
            }),
            // B: solda dikey gövde, üstte, ortada ve altta yatay çizgi, sağda iki göbek.
            (ButtonTokens.b, draw { x, y in
                x < 0.2 || (x < 0.7 && (y < 0.14 || abs(y - 0.5) < 0.08 || y > 0.86)) ||
                    (x >= 0.6 && (bowl(x, y, 0.25) || bowl(x, y, 0.75)))
            }),
            // Y: üstten ortaya inen iki kol ve alta inen gövde.
            (ButtonTokens.y, draw { x, y in
                (y < 0.58 && (abs(x - y * 0.9) < 0.13 || abs(x - (1 - y * 0.9)) < 0.13)) || (y >= 0.45 && abs(x - 0.5) < 0.11)
            }),
            // View: üst üste binen iki kare çerçeve.
            (ButtonTokens.view, draw { x, y in frame(x, y, 0, 0, 0.66, 0.66) || frame(x, y, 0.34, 0.34, 1, 1) }),
        ]

        /// Izgaranın dolu hücre oranı.
        static func fill(_ g: [Bool]) -> Double { Double(g.filter { $0 }.count) / Double(g.count) }

        /// Xbox şablonu mu (harf ya da View; PlayStation şekilleriyle yarışır).
        static func isLetter(_ name: String) -> Bool {
            name == ButtonTokens.a || name == ButtonTokens.b || name == ButtonTokens.y || name == ButtonTokens.view || name == ButtonTokens.menu
        }

        private static func bowl(_ x: Double, _ y: Double, _ cy: Double) -> Bool {
            let d = (((x - 0.6) / 0.38) * ((x - 0.6) / 0.38) + ((y - cy) / 0.25) * ((y - cy) / 0.25)).squareRoot()
            return abs(d - 0.82) < 0.2
        }

        private static func frame(_ x: Double, _ y: Double, _ x0: Double, _ y0: Double, _ x1: Double, _ y1: Double) -> Bool {
            let s = 0.12
            let inside = x >= x0 && x <= x1 && y >= y0 && y <= y1
            return inside && (x - x0 < s || x1 - x < s || y - y0 < s || y1 - y < s)
        }

        /// Yalnız sağa ve sola bir hücre büyütülmüş ızgara (yatay çizgilerdeki örnekleme boşlukları kapanır).
        static func grownSideways(_ g: [Bool]) -> [Bool] {
            var output = g
            for y in 0..<n {
                for x in 0..<n where g[y * n + x] {
                    if x + 1 < n { output[y * n + x + 1] = true }
                    if x > 0 { output[y * n + x - 1] = true }
                }
            }
            return output
        }

        private static func draw(_ f: (Double, Double) -> Bool) -> [Bool] {
            var g = [Bool](repeating: false, count: n * n)
            for y in 0..<n { for x in 0..<n { g[y * n + x] = f((Double(x) + 0.5) / Double(n), (Double(y) + 0.5) / Double(n)) } }
            return g
        }

        static func grown(_ g: [Bool]) -> [Bool] {
            var output = g
            for y in 0..<n {
                for x in 0..<n where g[y * n + x] {
                    if x + 1 < n { output[y * n + x + 1] = true }
                    if x > 0 { output[y * n + x - 1] = true }
                    if y + 1 < n { output[(y + 1) * n + x] = true }
                    if y > 0 { output[(y - 1) * n + x] = true }
                }
            }
            return output
        }

        /// İşaretlerin ne kadarı şablonda (bir hücre payla) × şablonun ne kadarı işaretlenmiş (bir hücre payla).
        static func similarity(_ a: [Bool], _ t: [Bool]) -> Double {
            let gt = grown(t), ga = grown(a)
            var inA = 0, aCount = 0, inT = 0, tCount = 0
            for i in 0..<(n * n) {
                if a[i] { aCount += 1; if gt[i] { inA += 1 } }
                if t[i] { tCount += 1; if ga[i] { inT += 1 } }
            }
            return Double(inA) / Double(max(aCount, 1)) * Double(inT) / Double(max(tCount, 1))
        }
    }
}
