// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Aynı altyazı satırının art arda karelerdeki okumalarını sözcük sözcük birleştirir (Windows: ReadingConsensus.cs,
/// Android: ReadingConsensus.kt; aynı kurallar). Yazı tanımanın hatası kareden kareye değişir: bir karede "Slop! The pack
/// is here", ötekinde "Stop! The pock is here" okunur; hiçbiri tam doğru değildir ama her sözcüğün doğrusu okumalardan
/// birinde vardır. En iyi okuma temel alınır; onun gerçek sözcük olmayan parçaları, aynı yerde gerçek sözcük okuyan başka
/// bir okumadan tamamlanır. Gerçek sözcüğe dokunulmaz ve sözcük sayısı farklı okumalar kullanılmaz (hizası belirsizdir);
/// bu yüzden birleştirme yeni hata üretmez.
public enum ReadingConsensus {
    /// `best`: temel alınan okuma. `readings`: aynı satırın bütün okumaları (`best` dahil olabilir). `isWord`: parça
    /// gerçek bir sözcük mü (sözcük listesi); nil ise birleştirme yapılmaz.
    public static func merge(_ best: String, readings: [String], isWord: ((String) -> Bool)?) -> String {
        guard let isWord, readings.count >= 2 else { return best }
        var rows = best.splitKeepingEmpty("\n")
        let others = readings.filter { $0 != best }.map { $0.splitKeepingEmpty("\n") }.filter { $0.count == rows.count }
        if others.isEmpty { return best }
        var changed = false
        for row in rows.indices {
            var words = rows[row].split(separator: " ").map(String.init)
            let aligned = others.map { $0[row].split(separator: " ").map(String.init) }.filter { $0.count == words.count }
            if aligned.isEmpty { continue }
            for index in words.indices {
                if isGood(words[index], isWord) { continue }
                // Aynı yerde gerçek sözcük okuyan okumalardan en çok tekrar edeni (eşitlikte ilk görülen).
                var counts: [(word: String, count: Int)] = []
                for candidate in aligned.map({ $0[index] }) where isGood(candidate, isWord) && close(candidate, words[index]) {
                    if let i = counts.firstIndex(where: { $0.word == candidate }) { counts[i].count += 1 } else { counts.append((candidate, 1)) }
                }
                var better: (word: String, count: Int)?
                for entry in counts where better == nil || entry.count > better!.count { better = entry }
                guard let better else { continue }
                words[index] = better.word
                changed = true
            }
            rows[row] = words.joined(separator: " ")
        }
        return changed ? rows.joined(separator: "\n") : best
    }

    /// Parçanın harf kısmı gerçek bir sözcük mü; harfsiz parçalar (sayı, işaret) ve özel adlar iyi sayılır.
    private static func isGood(_ text: String, _ isWord: (String) -> Bool) -> Bool {
        let token = Array(text)
        var start = 0, end = token.count
        while start < end && !token[start].isLetter { start += 1 }
        while end > start && !token[end - 1].isLetter { end -= 1 }
        let word = Array(token[start..<end])
        if word.filter(\.isLetter).count < 2 { return true }
        let rest = word.dropFirst()
        let proper = word[0].isUppercase && rest.filter(\.isLetter).allSatisfy(\.isLowercase)
        let acronym = word.allSatisfy { !$0.isLetter || $0.isUppercase }
        return acronym || isWord(String(word)) ||
            (proper && !rest.contains { !$0.isLetter && $0 != "'" && $0 != "’" && $0 != "-" })
    }

    /// İki parça aynı sözcüğün okumaları olacak kadar yakın mı (boyları ve harfleri benzer).
    private static func close(_ a: String, _ b: String) -> Bool {
        if abs(a.count - b.count) > 2 { return false }
        let x = TextMatch.normalize(a), y = TextMatch.normalize(b)
        return !x.isEmpty && !y.isEmpty && TextMatch.isSimilar(x, y, threshold: 0.5)
    }
}
