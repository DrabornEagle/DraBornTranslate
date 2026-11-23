// SPDX-License-Identifier: AGPL-3.0-only

import AppKit
import GTNative
import Observation
import RemotePlay
import SwiftUI

/// Xbox girişi ve konsol seçimi MacModel'in gözlenen alanlarında değil (XboxAccount kendi kaydında tutar). Kurulum
/// durumunu okuyan görünümler bu sayacı da okur; Xbox'ta bir şey değişince `MacModel.refreshSetup()` sayacı artırır.
@MainActor
@Observable
final class SetupSignal {
    static let shared = SetupSignal()
    private(set) var revision = 0
    func bump() { revision += 1 }
}

extension MacModel {
    /// Ana pencere gösterilsin mi: kurulum tamam ve kurulum sihirbazı bitirilmiş. Sihirbaz PS5/Xbox eşleşince hemen
    /// kapanmaz; TV, çeviri ve deneme adımları da gösterilir, sonunda `introSeen` true olur.
    var setupComplete: Bool {
        _ = SetupSignal.shared.revision
        return ready && introSeen
    }

    /// Kurulum durumunu yeniden okur (PS5 kaydı ve Xbox girişi/seçimi değişince).
    func refreshSetup() {
        reloadSetup()
        SetupSignal.shared.bump()
    }

    var isXbox: Bool { consoleKind == Store.kindXbox }

    /// Altyazı sunucusunun portu (çeviri kapalıyken motor 0 bildirir; TV uygulaması 8787'yi bekler).
    var serverPortOrDefault: Int { state.serverPort > 0 ? state.serverPort : 8787 }

    /// Mac'in yerel ağdaki adresi (motor bildirmediyse arayüzlerden okunur).
    var macAddress: String? { state.localAddress ?? Engine.localAddress() }

    /// PS5 eşleştirme iletisinin Mac'e uyarlanmış hâli ("telefon" yerine "Mac").
    func describePairing(_ detail: String?) -> String {
        guard let detail else { return L("Eşleştirme başarısız.", "Pairing failed.") }
        if detail.hasPrefix("@reg_unreachable") {
            return L("Mac PS5'i ağda göremiyor. Mac ile PS5 aynı ağda olmalı: ikisi de aynı Wi-Fi bandında (ikisi de 2.4 GHz ya da ikisi de 5 GHz) ya da PS5 modeme kabloyla bağlı. Modemde \"bant geçişi\" (band steering) ya da istemci yalıtımı açıksa kapatın. Sistem Ayarları › Gizlilik ve Güvenlik › Yerel Ağ'da GameTranslator'ın açık olduğuna da bakın.",
                     "The Mac can't see the PS5 on the network. The Mac and the PS5 must be on the same network: both on the same Wi-Fi band (both 2.4 GHz or both 5 GHz), or the PS5 connected to the router by cable. If the router has band steering or client isolation on, turn it off. Also check that GameTranslator is allowed under System Settings › Privacy & Security › Local Network.")
        }
        return describe(detail)
    }

    /// PSN girişinin hatası, kullanıcıya anlatılacak biçimde.
    func describePSN(_ error: Error) -> String {
        guard let error = error as? PSNAccount.PSNError else { return error.localizedDescription }
        switch error {
        case .noCode:
            return L("Giriş tamamlanmadı. Yeniden deneyin.", "Sign-in was not completed. Try again.")
        case let .refused(status, detail):
            return L("Sony girişi kabul etmedi", "Sony did not accept the sign-in") + suffix(status, detail)
        case let .rateLimited(status, detail):
            return L("Sony çok fazla deneme olduğunu söylüyor. Birkaç dakika bekleyip yeniden deneyin.",
                     "Sony says there were too many attempts. Wait a few minutes and try again.") + suffix(status, detail)
        case let .http(status, detail):
            return L("Sony'nin sunucusu hata verdi", "Sony's server returned an error") + suffix(status, detail)
        case let .network(text):
            return L("Sony'ye ulaşılamadı: \(text)", "Could not reach Sony: \(text)")
        case .badResponse:
            return L("Sony'nin yanıtı anlaşılamadı. Yeniden deneyin.", "Sony's response could not be understood. Try again.")
        }
    }

    private func suffix(_ status: Int?, _ detail: String?) -> String {
        var parts: [String] = []
        if let status { parts.append("HTTP \(status)") }
        if let detail, !detail.isEmpty { parts.append(detail) }
        return parts.isEmpty ? "." : " (\(parts.joined(separator: " · ")))."
    }
}

// MARK: - Ortak küçük parçalar

/// Başlığı düz metin olan kart (Card'ın başlığı yerelleştirme anahtarıdır; burada L() ile seçilmiş metin kullanılır).
struct TitledCard<Content: View>: View {
    let title: String
    var symbol: String?
    var subtitle: String?
    @ViewBuilder var content: Content

    var body: some View {
        Card {
            HStack(spacing: Theme.space("m")) {
                if let symbol { IconBadge(symbol: symbol) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: title).font(.csHeadline)
                    if let subtitle { Text(verbatim: subtitle).font(.csCallout).foregroundStyle(Color.csSecondary) }
                }
            }
            content
        }
    }
}

/// Bölüm ekranlarının başlığı.
struct ScreenHeader: View {
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: title).font(.csTitle)
            if let subtitle { Text(verbatim: subtitle).foregroundStyle(Color.csSecondary) }
        }
    }
}

/// Numaralı talimat satırı (düz metinle).
struct StepRow: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.space("m")) {
            Text("\(number)")
                .font(.csCallout.monospacedDigit().weight(.semibold))
                .foregroundStyle(Color.csAccentText)
                .frame(width: 24, height: 24)
                .background(Circle().fill(Color.csAccentSoft))
            Text(verbatim: text)
                .font(.csBody)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Seçilebilir liste satırı (bulunan konsol, TV, Xbox).
struct PickRow: View {
    let title: String
    var detail: String?
    var badge: String?
    var badgeGood = true
    let selected: Bool
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Theme.space("m")) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(selected ? Color.csAccentText : Color.csSecondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: title).font(.csBody.weight(.semibold)).foregroundStyle(Color.csText)
                    if let detail { Text(verbatim: detail).font(.csCallout).foregroundStyle(Color.csSecondary) }
                }
                Spacer()
                if let badge {
                    Text(verbatim: badge)
                        .font(.csCaption.weight(.semibold))
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background((badgeGood ? Color.csSuccess : Color.csSecondary).opacity(0.15), in: Capsule())
                        .foregroundStyle(badgeGood ? Color.csSuccess : Color.csSecondary)
                }
            }
            .padding(Theme.space("m"))
            .background(selected ? Color.csAccentSoft : Color.csBackground, in: RoundedRectangle(cornerRadius: Theme.radius("control")))
            .overlay(RoundedRectangle(cornerRadius: Theme.radius("control")).strokeBorder(selected ? Color.csAccentText.opacity(0.5) : Color.csBorder))
            .contentShape(RoundedRectangle(cornerRadius: Theme.radius("control")))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
}

/// Uyarı kutusu (ağda başka GameTranslator, izin eksik vb.).
struct NoticeBox: View {
    let text: String
    var symbol = "exclamationmark.triangle.fill"
    var tone: Color = .csWarning

    var body: some View {
        HStack(alignment: .top, spacing: Theme.space("m")) {
            Image(systemName: symbol).foregroundStyle(tone)
            Text(verbatim: text).font(.csCallout).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(Theme.space("m"))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tone.opacity(0.12), in: RoundedRectangle(cornerRadius: Theme.radius("control")))
    }
}

/// Panoya metin kopyalar.
@MainActor func copyToPasteboard(_ text: String) {
    NSPasteboard.general.clearContents()
    NSPasteboard.general.setString(text, forType: .string)
}

/// Ağda başka bir GameTranslator açıksa uyarı.
struct OtherServerNotice: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        if let other = model.state.otherServer {
            NoticeBox(text: L("Ağda başka bir GameTranslator açık (\(other)). TV ağda ilk bulduğuna bağlanır; altyazı oradan gelebilir. Oradaki çeviriyi kapatın.",
                              "Another GameTranslator is running on the network (\(other)). The TV connects to the first one it finds, so subtitles may come from there. Stop translation on that device."))
        }
    }
}
