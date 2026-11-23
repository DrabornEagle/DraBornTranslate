// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import GameTranslatorCore
#if canImport(Translation)
import Translation
#endif

/// Bir metni çeviren motor (Android: core TextTranslator). Çeviremezse nil döner; çağıran yedeğe geçer.
public protocol TextTranslating: Sendable {
    func translate(_ text: String) async -> String?
}

/// Kapanışla tanımlanan motor (sarmalayıcılar için).
struct ClosureTranslator: TextTranslating {
    let body: @Sendable (String) async -> String?
    func translate(_ text: String) async -> String? { await body(text) }
}

/// Kısa süreli ağ istekleri; çeviri altyazı ekrandan gitmeden yetişmeli.
enum Http {
    static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 4
        configuration.timeoutIntervalForResource = 8
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }()

    static func get(_ url: URL, timeout: TimeInterval, headers: [String: String] = [:]) async -> Data? {
        var request = URLRequest(url: url, timeoutInterval: timeout)
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        guard let (data, response) = try? await session.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return data
    }
}

/// Google'ın anahtarsız çeviri ucu (Windows ve Android'deki GoogleGtxTranslator ile aynı uçlar ve sıra).
public struct GoogleGtxTranslator: TextTranslating {
    let source: String
    let target: String

    public init(source: String, target: String) {
        self.source = source
        self.target = Languages.googleCode(target)
    }

    public func translate(_ text: String) async -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "" }
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let query = trimmed.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
        let endpoints = [
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=\(source)&tl=\(target)&dt=t&q=\(query)",
            "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl=\(source)&tl=\(target)&dt=t&q=\(query)",
            "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=\(source)&tl=\(target)&q=\(query)",
        ]
        let agent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
        for endpoint in endpoints {
            guard let url = URL(string: endpoint),
                  let data = await Http.get(url, timeout: 3.5, headers: ["User-Agent": agent]),
                  let translated = Self.parse(data), !translated.isEmpty else { continue }
            return translated
        }
        return nil
    }

    /// İki yanıt biçimi: `[[["çeviri","kaynak",...],...],...]` ve yedek uçtaki `["çeviri"]`.
    static func parse(_ data: Data) -> String? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [Any], !root.isEmpty else { return nil }
        let text: String
        if let sentences = root[0] as? [Any] {
            text = sentences.compactMap { ($0 as? [Any])?.first as? String }.filter { $0 != "null" }.joined()
                .trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            text = (root[0] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        }
        return text.isEmpty ? nil : unescapeHtml(text)
    }

    /// Yedek uç HTML varlıkları döndürebilir ("&#39;", "&quot;").
    static func unescapeHtml(_ text: String) -> String {
        guard text.contains("&") else { return text }
        var result = text
        for (entity, value) in [("&quot;", "\""), ("&#39;", "'"), ("&apos;", "'"), ("&lt;", "<"), ("&gt;", ">"), ("&amp;", "&")] {
            result = result.replacingOccurrences(of: entity, with: value)
        }
        return result
    }
}

/// DeepL API (kullanıcının kendi anahtarıyla). ":fx" ile biten anahtar ücretsiz uca gider.
public struct DeepLTranslator: TextTranslating {
    let key: String
    let source: String
    /// DeepL'in hedef kodu ("TR", "EN-US", "PT-BR"…).
    let target: String

    public func translate(_ text: String) async -> String? {
        let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        let host = trimmedKey.hasSuffix(":fx") ? "api-free.deepl.com" : "api.deepl.com"
        guard let url = URL(string: "https://\(host)/v2/translate") else { return nil }
        var request = URLRequest(url: url, timeoutInterval: 6)
        request.httpMethod = "POST"
        request.setValue("DeepL-Auth-Key \(trimmedKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: [
            "text": [text], "source_lang": source.uppercased(), "target_lang": target.uppercased(),
        ])
        guard let (data, response) = try? await Http.session.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let first = (json["translations"] as? [[String: Any]])?.first,
              let translated = first["text"] as? String,
              !translated.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return translated
    }
}

/// Önce `primary`; çeviremezse `fallback` (ör. internet koptuğunda cihaz üstü çeviri).
struct FallbackTranslator: TextTranslating {
    let primary: TextTranslating
    let fallback: TextTranslating

    func translate(_ text: String) async -> String? {
        if let result = await primary.translate(text) { return result }
        return await fallback.translate(text)
    }
}

/// Sözlükteki terimler çevirmene yer tutucuyla gider, çeviriden sonra Türkçesiyle geri konur (Android: GlossaryTranslator).
struct GlossaryTranslator: TextTranslating {
    let glossary: Glossary
    let inner: TextTranslating

    func translate(_ text: String) async -> String? {
        if glossary.isEmpty { return await inner.translate(text) }
        let protector = TermProtector(glossary: glossary, sources: [text])
        let (input, used) = protector.protect(text)
        if used.isEmpty { return await inner.translate(text) }
        guard let translated = await inner.translate(input) else { return nil }
        if let restored = TermProtector.restore(translated, expected: used, all: protector.allSlots) { return restored }
        return await inner.translate(text)
    }
}

/// Baştaki konuşmacı işareti ("- ", ">>") çevirmene gönderilmez (Android: PrefixTranslator).
struct PrefixTranslator: TextTranslating {
    let inner: TextTranslating

    func translate(_ text: String) async -> String? {
        let (prefix, rest) = SpeakerPrefix.split(text)
        if prefix.isEmpty { return await inner.translate(text) }
        if rest.isEmpty { return prefix.trimmingCharacters(in: .whitespaces) }
        guard let translated = await inner.translate(rest)?.trimmingCharacters(in: .whitespacesAndNewlines), !translated.isEmpty else {
            return nil
        }
        return prefix + translated
    }
}

/// Cihaz üstü çeviri, bilgisayardaki ücretsiz çeviriyle aynı metin hattından geçer (FreeTranslation: oyun deyimleri,
/// sözlük, Türkçe düzeltmeler). Android: core/FreeTranslation.kt FreeTranslator.
final class FreeTranslator: TextTranslating, @unchecked Sendable {
    private let inner: TextTranslating
    private let glossary: Glossary
    private let names = SpeakerNames()

    init(inner: TextTranslating, glossary: Glossary) {
        self.inner = inner
        self.glossary = glossary
    }

    func translate(_ text: String) async -> String? {
        let pieces = SubtitleSegmenter.split(text)
        names.learn(from: pieces)
        let known = names.all
        var translations: [String?] = []
        for piece in pieces {
            guard piece.translate else {
                translations.append(nil)
                continue
            }
            let inner = self.inner
            do {
                let translated = try await FreeTranslation.translateAsync(piece.text, context: nil, glossary: glossary,
                                                                          normalize: { SubtitleSegmenter.normalizeCase($0, names: known) }) { input in
                    guard let output = await inner.translate(input)?.trimmingCharacters(in: .whitespacesAndNewlines) else {
                        throw TranslationFailed()
                    }
                    return output
                }
                translations.append(translated)
            } catch {
                return nil
            }
        }
        let final = TurkishPostEdit.register(translations)
        return pieces.indices.map { final[$0] ?? pieces[$0].text }.joined().trimmingCharacters(in: .whitespacesAndNewlines)
    }

    struct TranslationFailed: Error {}
}

/// Apple'ın cihaz üstü çevirisi. Dil modeli yüklü değilse çeviremez (nil) ve çağıran Google'a düşer; modeli indirmek
/// Ayarlar > Uygulamalar > Çeviri'den ya da ilk kullanımda sistemin sorusuyla olur.
public final class AppleTranslator: TextTranslating, @unchecked Sendable {
    private let source: String
    private let target: String
    private let lock = NSLock()
    private var state: Any?
    private var unavailable = false
    private var installedState: Bool?

    init(source: String, target: String) {
        self.source = source
        self.target = target
    }

    /// iOS 18–25'te çeviri oturumu yalnız SwiftUI'nin `.translationTask`'ından alınır; Runner'daki görünmez görünüm bu
    /// kancayı kurar: (metin, kaynak dil, hedef dil) → çeviri (çeviremezse nil).
    nonisolated(unsafe) public static var hosted: ((String, String, String) async -> String?)?

    public func translate(_ text: String) async -> String? {
        #if canImport(Translation)
        if #available(iOS 26.0, macOS 26.0, *) {
            guard let session = await session() else { return nil }
            return try? await session.translate(text).targetText
        }
        if #available(iOS 18.0, macOS 15.0, *), let hosted = Self.hosted, await installed() {
            return await hosted(text, source, target)
        }
        #endif
        return nil
    }

    /// Model hazır mı (günlük için).
    func prepare() async -> Bool {
        #if canImport(Translation)
        if #available(iOS 26.0, macOS 26.0, *) { return await session() != nil }
        if #available(iOS 18.0, macOS 15.0, *) {
            guard Self.hosted != nil else { return false }
            return await installed()
        }
        #endif
        return false
    }

    #if canImport(Translation)
    /// Dil modeli cihazda yüklü mü (yüklü değilse çeviri sistemin indirme sorusunu açmasın diye denenmez).
    @available(iOS 18.0, macOS 15.0, *)
    private func installed() async -> Bool {
        if let known = lock.withLock({ installedState }) { return known }
        let status = await LanguageAvailability().status(from: Locale.Language(identifier: source), to: Locale.Language(identifier: target))
        let value = status == .installed
        lock.withLock { installedState = value }
        return value
    }
    #endif

    #if canImport(Translation)
    @available(iOS 26.0, macOS 26.0, *)
    private func session() async -> TranslationSession? {
        if let existing = lock.withLock({ state as? TranslationSession }) { return existing }
        if lock.withLock({ unavailable }) { return nil }
        let from = Locale.Language(identifier: source), to = Locale.Language(identifier: target)
        guard await LanguageAvailability().status(from: from, to: to) == .installed else {
            lock.withLock { unavailable = true }
            return nil
        }
        let created = TranslationSession(installedSource: from, target: to)
        lock.withLock { state = created }
        return created
    }
    #endif
}
