// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import Observation
import Sparkle

/// Yeni sürüm bilgisi (Windows: ana ekrandaki güncelleme bandı). Sparkle yeni sürüm bulunca ana sayfada bant görünür;
/// "Güncelle" Sparkle'ın kendi penceresini açar (indirme, kurulum ve yeniden başlatma onda).
@MainActor
@Observable
final class UpdateState: NSObject, SPUUpdaterDelegate {
    static let shared = UpdateState()

    private(set) var availableVersion: String?
    @ObservationIgnored weak var controller: SPUStandardUpdaterController?

    func checkNow() { controller?.checkForUpdates(nil) }

    nonisolated func updater(_ updater: SPUUpdater, didFindValidUpdate item: SUAppcastItem) {
        let version = item.displayVersionString
        Task { @MainActor in self.availableVersion = version }
    }

    nonisolated func updaterDidNotFindUpdate(_ updater: SPUUpdater) {
        Task { @MainActor in self.availableVersion = nil }
    }
}
