// SPDX-License-Identifier: AGPL-3.0-only

import GTNative
import SwiftUI

/// Ana sayfa (Windows: MainView "Çeviri Merkezi"): başlat/durdur, PS5/Xbox · Mac · TV durumu, oyun, son çeviri, canlı önizleme.
struct HomeView: View {
    @Environment(MacModel.self) private var model
    @State private var preview: NSImage?
    @State private var activity: PoolActivity?
    private let updates = UpdateState.shared
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.space("l")) {
                if let version = updates.availableVersion { updateBanner(version) }
                if !model.pendingPacks.isEmpty || model.installingPacks { packBanner }
                header
                statusRow
                HStack(alignment: .top, spacing: Theme.space("l")) {
                    livePreview
                    lastTranslation
                }
                footer
                if let other = model.state.otherServer {
                    HintText(text: L("Ağda başka bir GameTranslator açık (\(other)). TV ona bağlanmış olabilir; oradaki çeviriyi kapat.",
                                     "Another GameTranslator is open on the network (\(other)). The TV may be connected to it; stop translation there."))
                }
            }
            .padding(Theme.space("xl"))
        }
        .task(id: model.settings.packsEnabled) {
            // Açılışta ve 6 saatte bir paket sunucusuna bakılır; yeni paket varsa şeritte sorulur.
            while !Task.isCancelled {
                await model.checkPackUpdates()
                try? await Task.sleep(for: .seconds(6 * 3600))
            }
        }
        .task(id: model.settings.sharePool) {
            // Ortak havuz kapalıysa sayım gösterilmez (Windows ile aynı); 10 dakikada bir yenilenir.
            while !Task.isCancelled {
                activity = model.settings.sharePool ? await PoolActivity.fetch() : nil
                try? await Task.sleep(for: .seconds(PoolActivity.refreshInterval))
            }
        }
        .onReceive(timer) { _ in
            guard model.state.streaming else { return }
            preview = model.snapshot(width: 960)
        }
    }

    private var headline: (title: String, detail: String, color: Color) {
        let state = model.state
        switch state.phase {
        case "streaming": return (L("Çeviri açık", "Translation is on"), L("Altyazılar okunuyor ve TV'ye gönderiliyor.", "Subtitles are being read and sent to the TV."), .csSuccess)
        case "starting", "connecting", "waking": return (L("Bağlanıyor…", "Connecting…"), model.describe(state.message), .csWarning)
        case "reconnecting": return (L("Yeniden bağlanıyor…", "Reconnecting…"), model.describe(state.message), .csWarning)
        case "stopping": return (L("Durduruluyor…", "Stopping…"), "", .csSecondary)
        case "failed": return (L("Bağlanılamadı", "Couldn't connect"), model.describe(state.message), .csDanger)
        default: return (L("Çeviri kapalı", "Translation is off"), L("Başlatmak için düğmeye bas.", "Press the button to start."), .csSecondary)
        }
    }

    private var header: some View {
        HStack(spacing: Theme.space("l")) {
            Button(action: model.toggle) {
                Image(systemName: model.state.active ? "stop.fill" : "play.fill")
                    .font(.system(size: 30, weight: .bold))
                    .frame(width: 84, height: 84)
                    .background(Circle().fill(model.state.active ? headline.color.opacity(0.25) : Color.csAccent))
                    .foregroundStyle(model.state.active ? headline.color : Color.csOnAccent)
            }
            .buttonStyle(.plain)
            .disabled(model.state.phase == "stopping")
            .help(model.state.active ? L("Çeviriyi durdur", "Stop translation") : L("Çeviriyi başlat", "Start translation"))
            VStack(alignment: .leading, spacing: 6) {
                Text(headline.title).font(.csTitle).foregroundStyle(Color.csText)
                if !headline.detail.isEmpty { Text(headline.detail).foregroundStyle(Color.csSecondary) }
                if !model.state.runningGame.isEmpty || !model.state.packGame.isEmpty {
                    Label(model.state.runningGame.isEmpty ? model.state.packGame : model.state.runningGame, systemImage: "gamecontroller")
                        .foregroundStyle(Color.csAccentText)
                }
            }
            Spacer()
        }
    }

    private func updateBanner(_ version: String) -> some View {
        HStack(spacing: Theme.space("m")) {
            Image(systemName: "arrow.down.circle.fill").font(.title2).foregroundStyle(Color.csAccentText)
            VStack(alignment: .leading, spacing: 2) {
                Text(L("GameTranslator \(version) çıktı", "GameTranslator \(version) is available")).font(.headline).foregroundStyle(Color.csText)
                Text(L("Yeni sürümü indirip kurmak için Güncelle'ye bas.", "Press Update to download and install it."))
                    .font(.caption).foregroundStyle(Color.csSecondary)
            }
            Spacer()
            Button(L("Güncelle", "Update")) { updates.checkNow() }.buttonStyle(CSButtonStyle.primary)
        }
        .padding(Theme.space("m"))
        .background(Color.csAccentSoft, in: RoundedRectangle(cornerRadius: Theme.radius("card")))
    }

    private var packBanner: some View {
        let names = model.pendingPacks.map(\.title).prefix(3).joined(separator: ", ")
        let more = model.pendingPacks.count > 3 ? " +\(model.pendingPacks.count - 3)" : ""
        return HStack(spacing: Theme.space("m")) {
            Image(systemName: "shippingbox.fill").font(.title2).foregroundStyle(Color.csAccentText)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.installingPacks ? L("Oyun paketleri güncelleniyor…", "Updating game packs…")
                     : L("Yeni oyun paketleri var", "New game packs are available"))
                    .font(.headline).foregroundStyle(Color.csText)
                if !model.installingPacks {
                    Text(names + more).font(.caption).foregroundStyle(Color.csSecondary).lineLimit(1)
                }
            }
            Spacer()
            if model.installingPacks {
                ProgressView().controlSize(.small)
            } else {
                Button(L("Sonra", "Later")) { model.postponePackUpdates() }.buttonStyle(CSButtonStyle.secondary)
                Button(L("Güncelle", "Update")) { Task { await model.installPackUpdates() } }.buttonStyle(CSButtonStyle.primary)
            }
        }
        .padding(Theme.space("m"))
        .background(Color.csSurface, in: RoundedRectangle(cornerRadius: Theme.radius("card")))
    }

    private var footer: some View {
        HStack(spacing: Theme.space("m")) {
            if let text = activity?.text(turkish: UILanguage.isTurkish) {
                Label(text, systemImage: "person.3.fill").font(.callout).foregroundStyle(Color.csSecondary)
            }
            Spacer()
            Button {
                NSWorkspace.shared.open(AppIdentity.supportURL)
            } label: {
                Label(L("Projeyi destekle", "Support the project"), systemImage: "cup.and.saucer.fill")
            }
            .buttonStyle(CSButtonStyle.secondary)
            .help(L("GameTranslator ücretsiz ve açık kaynaklı. Beğendiysen bir kahve ısmarlayabilirsin.",
                    "GameTranslator is free and open source. If you like it, you can buy us a coffee."))
        }
    }

    private var statusRow: some View {
        let state = model.state
        let xbox = model.consoleKind == Store.kindXbox
        return HStack(spacing: Theme.space("m")) {
            StatusTile(symbol: "gamecontroller.fill", title: xbox ? "Xbox" : (model.console?.name ?? "PS5"),
                       detail: state.streaming ? L("Bağlı", "Connected") : state.connecting ? L("Bağlanıyor", "Connecting") : L("Eşleşti", "Paired"),
                       on: state.streaming)
            StatusTile(symbol: "desktopcomputer", title: "Mac",
                       detail: state.streaming ? L("Çeviriyor", "Translating") : L("Hazır", "Ready"), on: state.streaming)
            StatusTile(symbol: "tv", title: "TV",
                       detail: state.tvClients > 0 ? L("Bağlı", "Connected") : L("Bağlı değil", "Not connected"), on: state.tvClients > 0)
        }
    }

    private var livePreview: some View {
        VStack(alignment: .leading, spacing: Theme.space("s")) {
            Text(L("CANLI", "LIVE")).font(.caption.weight(.semibold)).foregroundStyle(Color.csSecondary)
            ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: Theme.radius("card")).fill(Color.black)
                if let preview {
                    Image(nsImage: preview).resizable().aspectRatio(contentMode: .fit)
                } else {
                    Text(L("Çeviri açıkken burada oyun görüntüsü görünür.", "The game picture appears here while translating."))
                        .foregroundStyle(Color.csSecondary).padding()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                if !model.state.lastTranslation.isEmpty && model.state.streaming {
                    Text(model.state.lastTranslation)
                        .font(.system(size: 15, weight: .semibold))
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(Color.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 6))
                        .foregroundStyle(.white)
                        .padding(10)
                }
            }
            .aspectRatio(16 / 9, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: Theme.radius("card")))
        }
        .frame(maxWidth: .infinity)
    }

    private var lastTranslation: some View {
        VStack(alignment: .leading, spacing: Theme.space("s")) {
            Text(L("SON ALTYAZI", "LAST SUBTITLE")).font(.caption.weight(.semibold)).foregroundStyle(Color.csSecondary)
            VStack(alignment: .leading, spacing: 8) {
                if model.state.lastTranslation.isEmpty {
                    Text(L("Henüz çeviri yok.", "No translation yet.")).foregroundStyle(Color.csSecondary)
                } else {
                    Text(model.state.lastTranslation).font(.system(size: 17, weight: .semibold)).foregroundStyle(Color.csText)
                        .textSelection(.enabled)
                    Text(model.state.lastSource.replacingOccurrences(of: "\n", with: " ")).foregroundStyle(Color.csSecondary)
                        .textSelection(.enabled)
                }
                Divider().padding(.vertical, 4)
                Text(L("\(model.state.translated) satır çevrildi · \(model.state.packHits) paketten", "\(model.state.translated) lines translated · \(model.state.packHits) from packs"))
                    .font(.caption).foregroundStyle(Color.csSecondary)
                Button(L("TV'ye deneme altyazısı gönder", "Send a test subtitle to the TV")) { model.sendTestSubtitle() }
                    .buttonStyle(.link)
            }
            .padding(Theme.space("m"))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.csSurface, in: RoundedRectangle(cornerRadius: Theme.radius("card")))
        }
        .frame(width: 320)
    }
}

/// PS5 / Mac / TV durum kutusu.
struct StatusTile: View {
    let symbol: String
    let title: String
    let detail: String
    let on: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: symbol).font(.title3).foregroundStyle(Color.csText)
            Text(title).font(.headline).foregroundStyle(Color.csText).lineLimit(1)
            HStack(spacing: 6) {
                Circle().fill(on ? Color.csSuccess : Color.csSecondary).frame(width: 7, height: 7)
                Text(detail).font(.caption).foregroundStyle(on ? Color.csSuccess : Color.csSecondary)
            }
        }
        .padding(Theme.space("m"))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.csSurface, in: RoundedRectangle(cornerRadius: Theme.radius("card")))
    }
}
