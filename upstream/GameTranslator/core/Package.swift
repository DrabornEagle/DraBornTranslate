// swift-tools-version: 6.2
// SPDX-License-Identifier: AGPL-3.0-only

import PackageDescription

let package = Package(
    name: "GameTranslatorCore",
    platforms: [.macOS(.v26), .iOS(.v16)],
    products: [
        .library(name: "GameTranslatorCore", targets: ["GameTranslatorCore"]),
        .executable(name: "tv-smoke", targets: ["tv-smoke"]),
    ],
    targets: [
        .target(
            name: "GameTranslatorCore",
            resources: [
                .copy("Resources/obs-overlay.html"),
                // Gömülü altyazı fontu (SIL OFL, değiştirilmeden; lisansı fonts/Quicksand-OFL.txt).
                .copy("Resources/fonts"),
                // Ücretsiz çevirinin genel terim sözlüğü ve yazım denetiminin sözcük listesi (Windows'takilerin aynısı).
                .copy("Resources/glossary-en-tr.json"),
                .copy("Resources/english-words.txt.gz"),
            ],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .executableTarget(
            name: "tv-smoke",
            dependencies: ["GameTranslatorCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "GameTranslatorCoreTests",
            dependencies: ["GameTranslatorCore"],
            // Windows ve Android'le ortak deneme dosyaları; denemeler onları kaynak klasöründen (#filePath) okur.
            exclude: ["Fixtures"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
