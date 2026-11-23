// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Uygulamanın adı ve kimlikleri tek yerde.
enum AppIdentity {
    static let name = "GameTranslator"
    static let tvAppId = "com.gametranslator.tv"
    static let samsungAppId = "GameTransT.GameTranslator"
    static let tvGuideURL = URL(string: "https://gitlab.com/sefflex/GameTranslator/-/blob/main/docs/tv-kurulum.md")!
    static let sourceURL = URL(string: "https://gitlab.com/sefflex/GameTranslator")!
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }
    static let serverPort: UInt16 = 8787
    /// Gönüllü destek (Windows: AppInfo.SupportUrl).
    static let supportURL = URL(string: "https://buymeacoffee.com/gametranslator")!

    /// Uygulamanın kalıcı dosyaları (dublaj modeli; motorun geçmişi ve paketleri de aynı klasörde).
    static var dataDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent(name, isDirectory: true)
    }
}
