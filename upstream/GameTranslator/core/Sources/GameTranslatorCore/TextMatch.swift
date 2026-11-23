// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// OCR çıktılarını karşılaştırmak için sadeleştirme ve gürültüye dayanıklı benzerlik.
public enum TextMatch {
    /// Küçük harf, yalnız harf/rakam, tek boşluk.
    public static func normalize(_ s: String) -> String {
        let scalars = s.lowercased().unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : " " }
        return String(scalars).split(separator: " ").joined(separator: " ")
    }

    /// Levenshtein oranı `threshold` ve üstündeyse benzer sayılır. Boş metin hiçbir metne benzemez.
    public static func isSimilar(_ a: String, _ b: String, threshold: Double = 0.85) -> Bool {
        if a == b { return !a.isEmpty }
        let x = Array(a), y = Array(b)
        if x.isEmpty || y.isEmpty { return false }
        var previous = Array(0...y.count)
        for i in 1...x.count {
            var row = [i] + Array(repeating: 0, count: y.count)
            for j in 1...y.count {
                row[j] = min(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1))
            }
            previous = row
        }
        return 1 - Double(previous[y.count]) / Double(max(x.count, y.count)) >= threshold
    }

    /// Yeni okuma, gönderilmiş altyazının yeniden okuması mı (sadeleştirilmiş metinler). Süslü yazı tipinde ya da
    /// solarken OCR bir iki sözcüğü her karede başka çöpe çevirir ("ou$3e", "waters2aljd") ya da iki satırlık
    /// altyazının bir satırını kaçırır; karakter benzerliği düşse de sözcükler aynı kalır. Rakam karışmış sözcükler
    /// ve tek harfler çöp sayılıp karşılaştırmaya girmez. Metin belirgin uzadıysa (daktilo efekti) yeni satırdır.
    public static func isSameSubtitle(_ reread: String, _ sent: String) -> Bool {
        if isSimilar(reread, sent) { return true }
        let x = cleanWords(reread), y = cleanWords(sent)
        var remaining = y
        var shared = 0
        for word in x {
            if let index = remaining.firstIndex(of: word) {
                remaining.remove(at: index)
                shared += 1
            }
        }
        // Kaçırılmış satır: yeni okumanın sözcüklerinin hemen hepsi gönderilende var.
        if x.count >= 4, x.count <= y.count, Double(shared) >= Double(x.count) * 0.85 { return true }
        // Kısa satırda tek sözcük anlamı değiştirir ("where is the boy" / "ship"); orada yalnız karakter benzerliği geçerli.
        let longer = max(x.count, y.count), shorter = min(x.count, y.count)
        guard shorter >= 6, Double(shorter) / Double(longer) >= 0.75 else { return false }
        return Double(shared) / Double(longer) >= 0.75
    }

    /// Satırları ayrı ayrı sadeleştirip alt alta birleştirir (Windows: TextMatch.NormalizeRows).
    public static func normalizeRows(_ s: String) -> String {
        s.replacingOccurrences(of: "\r\n", with: "\n").splitKeepingEmpty("\n").map(normalize).filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// Yeni satır, az önce gösterilen satırın uzamış ya da düzelmiş okuması mı (Android: TextMatch.isRevision): daktilo
    /// efektinde büyüyen cümle ("We do not have" → "We do not have time."), bir iki sözcüğü başka okunan aynı cümle. TV
    /// böyle bir çeviriyi öncekinin üstüne yığmaz, yerine koyar.
    public static func isRevision(_ newText: String, _ oldText: String) -> Bool {
        let x = normalize(newText), y = normalize(oldText)
        if x.isEmpty || y.isEmpty { return false }
        if isGrowth(newText, oldText) || isGrowth(oldText, newText) { return true }
        if isSimilar(x, y, threshold: 0.8) { return true }
        let a = cleanWords(x)
        var b = cleanWords(y)
        let shorter = min(a.count, b.count), longer = max(a.count, b.count)
        if shorter < 5 { return false }
        if similarity(a, b) >= 0.65 { return true }
        var shared = 0
        for word in a {
            if let index = b.firstIndex(of: word) {
                b.remove(at: index)
                shared += 1
            }
        }
        return Double(shared) / Double(longer) >= 0.7
    }

    /// Okuma, gönderilmiş satırın aynı cümleyi bozuk okuyan ve ondan daha iyi olmayan bir yeniden okuması mı
    /// (Android: TextMatch.isWorseReread).
    public static func isWorseReread(_ reread: String, _ sent: String) -> Bool {
        let a = normalize(reread), b = normalize(sent)
        let x = cleanWords(a), y = cleanWords(b)
        if x.count < 5 || y.isEmpty || isGrowth(reread, sent) || isGrowth(sent, reread) { return false }
        let reads = similarity(x, y) >= 0.65 || isSimilar(a, b, threshold: 0.75)
        return reads && OcrNoise.score(reread) < OcrNoise.score(sent) + 4
    }

    /// `newText`, `oldText`'in sonuna (ya da başına) sözcük eklenmiş hali mi (daktilo efekti).
    public static func isGrowth(_ newText: String, _ oldText: String) -> Bool {
        let x = normalize(newText).split(separator: " "), y = normalize(oldText).split(separator: " ")
        if y.isEmpty || x.count <= y.count { return false }
        return x.prefix(y.count).elementsEqual(y) || (y.count >= 2 && x.suffix(y.count).elementsEqual(y))
    }

    /// Levenshtein oranı (sözcük dizileri için).
    private static func similarity<T: Equatable>(_ x: [T], _ y: [T]) -> Double {
        if x.isEmpty || y.isEmpty { return x.isEmpty && y.isEmpty ? 1 : 0 }
        var previous = Array(0...y.count)
        var row = [Int](repeating: 0, count: y.count + 1)
        for i in 1...x.count {
            row[0] = i
            for j in 1...y.count {
                row[j] = min(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1))
            }
            swap(&previous, &row)
        }
        return 1 - Double(previous[y.count]) / Double(max(x.count, y.count))
    }

    private static func cleanWords(_ normalized: String) -> [Substring] {
        // Tek harfler ("a", "I" ya da OCR'ın uydurduğu "O") karşılaştırmaya bir şey katmaz.
        normalized.split(separator: " ").filter { $0.count > 1 && !$0.contains(where: \.isNumber) }
    }
}
