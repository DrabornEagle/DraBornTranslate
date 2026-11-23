// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import GameTranslatorCore

/// Motorun verdiği (kaynak, çeviri) çiftinden seslendirilecek cümleler. Eski Mac hattı her cümleyi ayrı çevirip
/// `SpeechScript.lines`'a veriyordu; GTNative altyazıyı bütün olarak çevirir. Bu yüzden kaynak ve çeviri aynı kurallarla
/// (SubtitleSegmenter) parçalanır: çevrilecek parça sayıları tutuyorsa parçalar sırayla eşlenir (konuşmacı ve tire
/// kaynaktan gelir); tutmuyorsa bütün çeviri tek cümle olarak, ilk konuşmacının sesiyle söylenir.
enum DubbingScript {
    static func lines(source: String, translation: String) -> [SpokenLine] {
        let pieces = SubtitleSegmenter.split(source)
        let translated = SubtitleSegmenter.split(translation).filter(\.translate).map(\.text)
        let wanted = pieces.filter(\.translate).count
        guard wanted > 0 else { return [] }
        if translated.count == wanted {
            var next = translated.makeIterator()
            let translations = pieces.map { $0.translate ? next.next() : nil }
            return SpeechScript.lines(pieces: pieces, translations: translations)
        }
        // Parçalar tutmadı: çevirideki etiket ve tireler atılır, kalan metin bir cümle olur.
        let turkish = translated.joined(separator: " ")
        guard turkish.contains(where: \.isLetter) else { return [] }
        let english = pieces.filter(\.translate).map(\.text).joined(separator: " ")
        let speaker = pieces.first { !$0.translate && $0.text.hasSuffix(": ") }.map { SubtitleSegmenter.name(fromLabel: $0.text) }
        return [SpokenLine(speaker: speaker?.isEmpty == false ? speaker : nil, english: english, turkish: turkish)]
    }
}
