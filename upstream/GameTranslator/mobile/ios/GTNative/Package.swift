// swift-tools-version: 6.2
// SPDX-License-Identifier: AGPL-3.0-only

import PackageDescription

/// iPhone uygulamasının yerel katmanı (Android'deki Engine/Store/Translators'ın karşılığı). Flutter'a bağlı değildir:
/// kanallar Runner'daki ince köprüde (GTChannels.swift) bu paketin düz eşlemeleriyle konuşur. macOS'ta da derlenir,
/// böylece birim denemeleri `swift test` ile Mac'te koşar.
let package = Package(
    name: "GTNative",
    platforms: [.iOS(.v17), .macOS(.v26)],
    products: [
        .library(name: "GTNative", targets: ["GTNative"]),
    ],
    dependencies: [
        .package(path: "../../../remoteplay"),
        .package(path: "../../../core"),
        // LG TV'ye kurulum: Geliştirici Modu'nun SSH'ı (RSA anahtarlı; Android'de JSch).
        .package(url: "https://github.com/orlandos-nl/Citadel.git", exact: "0.12.0"),
        // Xbox ev yayını (Android'de io.github.webrtc-sdk).
        .package(url: "https://github.com/stasel/WebRTC.git", exact: "157.0.0"),
    ],
    targets: [
        .target(
            name: "GTNative",
            dependencies: [
                .product(name: "RemotePlay", package: "remoteplay"),
                .product(name: "GameTranslatorCore", package: "core"),
                .product(name: "Citadel", package: "Citadel"),
                .product(name: "WebRTC", package: "WebRTC"),
            ],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "GTNativeTests",
            dependencies: ["GTNative"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
