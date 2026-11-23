// SPDX-License-Identifier: AGPL-3.0-only

import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import GTNative
import SwiftUI

/// Bağlantı: görüntü kaynağı, PS5 ya da Xbox, TV uygulaması, ikinci ekran ve OBS adresi.
struct ConnectionView: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.space("l")) {
                ScreenHeader(title: Section.connection.title,
                             subtitle: L("Konsol, TV ve diğer ekranlar.", "Console, TV and other screens."))
                OtherServerNotice()
                VideoSourceCard()
                if model.isXbox { XboxCard() } else { PS5Card() }
                TitledCard(title: L("TV uygulaması", "TV app"), symbol: "tv",
                           subtitle: L("Altyazıyı TV'de oyunun üstünde gösteren GameTranslator uygulaması.", "The GameTranslator app that shows subtitles over the game on the TV.")) {
                    StatusRow(title: "TV", value: model.state.tvClients > 0 ? L("Bağlı", "Connected") : L("Bağlı değil", "Not connected"),
                              tone: model.state.tvClients > 0 ? .good : .idle)
                    TvInstallPanel()
                }
                HStack(alignment: .top, spacing: Theme.space("l")) {
                    SecondScreenCard()
                    ObsCard()
                }
            }
            .padding(Theme.space("xl"))
        }
    }
}

// MARK: - Konsol

private struct ConsoleKindPicker: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        HStack {
            Text(L("Konsol türü", "Console type"))
            Picker("", selection: Binding(get: { model.consoleKind }, set: { value in
                model.consoleKind = value
                model.refreshSetup()
            })) {
                Text("PS5").tag(Store.kindPS5)
                Text("Xbox (Beta)").tag(Store.kindXbox)
            }
            .labelsHidden()
            .frame(width: 160)
            Spacer()
        }
    }
}

private struct PS5Card: View {
    @Environment(MacModel.self) private var model
    @State private var repair = false
    @State private var confirmForgetConsole = false
    @State private var confirmForgetAccount = false

    var body: some View {
        TitledCard(title: "PS5", symbol: "gamecontroller",
                   subtitle: L("Remote Play ile, ikinci bir PSN hesabıyla.", "Over Remote Play, with a second PSN account.")) {
            if let console = model.console {
                StatusRow(title: LocalizedStringKey(console.name), value: console.host, tone: model.state.streaming ? .good : .idle)
            } else {
                StatusRow(title: "PS5", value: L("Eşleştirilmedi", "Not paired"), tone: .warning)
            }
            StatusRow(title: "PSN", value: model.hasAccount ? L("Hesap bağlı", "Account linked") : L("Giriş yok", "Not signed in"),
                      tone: model.hasAccount ? .good : .warning)
            HStack {
                Button(L("Yeniden eşleştir", "Pair again")) { repair = true }.buttonStyle(.secondary)
                Button(L("PSN hesabını değiştir", "Change PSN account")) { confirmForgetAccount = true }.buttonStyle(.secondary)
                Spacer()
                Button(L("PS5'i unut", "Forget PS5"), role: .destructive) { confirmForgetConsole = true }.buttonStyle(.link)
            }
            .disabled(model.state.active)
            if model.state.active {
                HintText(text: L("Değişiklik için önce çeviriyi durdurun.", "Stop translation first to make changes."))
            }
            ConsoleKindPicker()
        }
        .sheet(isPresented: $repair) {
            VStack(alignment: .leading, spacing: Theme.space("l")) {
                HStack {
                    Text(L("PS5 ile yeniden eşleştir", "Pair with the PS5 again")).font(.csTitle)
                    Spacer()
                    Button(L("Kapat", "Close")) { repair = false }.keyboardShortcut(.cancelAction)
                }
                PS5PairingPanel { repair = false }
            }
            .padding(Theme.space("xl"))
            .frame(width: 620)
            .environment(model)
            .appSurface()
        }
        .confirmationDialog(L("PS5 unutulsun mu?", "Forget the PS5?"), isPresented: $confirmForgetConsole) {
            Button(L("PS5'i unut", "Forget PS5"), role: .destructive) {
                model.forgetConsole()
                model.refreshSetup()
            }
        } message: {
            Text(L("Eşleştirme silinir; kurulum ekranı açılır ve PS5'i yeniden eşleştirmeniz gerekir.", "The pairing is deleted; setup opens and you'll need to pair the PS5 again."))
        }
        .confirmationDialog(L("PSN hesabı değiştirilsin mi?", "Change the PSN account?"), isPresented: $confirmForgetAccount) {
            Button(L("Hesaptan çık", "Sign out"), role: .destructive) {
                model.forgetAccount()
                model.refreshSetup()
            }
        } message: {
            Text(L("Hesap ve PS5 eşleştirmesi silinir; kurulum ekranında ikinci kullanıcının hesabıyla yeniden giriş yapıp eşleştirirsiniz.",
                   "The account and the PS5 pairing are deleted; in setup you sign in again with the second user's account and pair."))
        }
    }
}

private struct XboxCard: View {
    @Environment(MacModel.self) private var model
    @State private var choose = false
    @State private var confirmSignOut = false

    var body: some View {
        let _ = SetupSignal.shared.revision
        TitledCard(title: "Xbox (Beta)", symbol: "gamecontroller",
                   subtitle: L("Ev yayınıyla, kendi Microsoft hesabınızla.", "Over home streaming, with your own Microsoft account.")) {
            StatusRow(title: "Microsoft", value: model.xbox.signedIn ? L("Giriş yapıldı", "Signed in") : L("Giriş yok", "Not signed in"),
                      tone: model.xbox.signedIn ? .good : .warning)
            StatusRow(title: "Xbox", value: model.xbox.selectedConsole?.label ?? L("Seçilmedi", "Not chosen"),
                      tone: model.xbox.selectedConsole != nil ? .good : .warning)
            HStack {
                Button(L("Konsolu değiştir", "Change console")) { choose = true }.buttonStyle(.secondary)
                Spacer()
                Button(L("Microsoft hesabından çık", "Sign out of Microsoft account"), role: .destructive) { confirmSignOut = true }.buttonStyle(.link)
            }
            .disabled(model.state.active || !model.xbox.signedIn)
            ConsoleKindPicker()
        }
        .sheet(isPresented: $choose) {
            VStack(alignment: .leading, spacing: Theme.space("l")) {
                HStack {
                    Text(L("Xbox'ınızı seçin", "Choose your Xbox")).font(.csTitle)
                    Spacer()
                    Button(L("Kapat", "Close")) { choose = false }.keyboardShortcut(.cancelAction)
                }
                XboxConsolePanel(onChosen: { choose = false }, onNeedsSignIn: { choose = false })
            }
            .padding(Theme.space("xl"))
            .frame(width: 620)
            .environment(model)
            .appSurface()
        }
        .confirmationDialog(L("Microsoft hesabından çıkılsın mı?", "Sign out of the Microsoft account?"), isPresented: $confirmSignOut) {
            Button(L("Çık", "Sign out"), role: .destructive) {
                Task {
                    await model.xbox.signOut()
                    model.refreshSetup()
                }
            }
        } message: {
            Text(L("Giriş anahtarı Anahtar Zinciri'nden ve seçili Xbox kayıttan silinir; kurulum ekranı açılır.",
                   "The sign-in token is removed from the Keychain and the chosen Xbox is forgotten; setup opens."))
        }
    }
}

// MARK: - İkinci ekran ve OBS

private struct SecondScreenCard: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        let url = model.macAddress.map { "http://\($0):\(model.serverPortOrDefault)/ekran" }
        TitledCard(title: L("İkinci ekran", "Second screen"), symbol: "ipad.and.iphone") {
            HintText(text: L("TV'niz desteklenmiyorsa çeviriyi tablet, telefon ya da başka bir bilgisayarın tarayıcısında okuyun. Aynı ağdaki cihazda bu adresi açın ya da kodu okutun.",
                             "If your TV isn't supported, read the translation in the browser of a tablet, phone or another computer. Open this address on a device on the same network, or scan the code."))
            if let url {
                HStack(alignment: .top, spacing: Theme.space("l")) {
                    if let image = QRCode.image(url) {
                        Image(nsImage: image)
                            .interpolation(.none)
                            .resizable()
                            .frame(width: 132, height: 132)
                            .padding(8)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: Theme.radius("control")))
                            .accessibilityLabel(L("Adresin QR kodu", "QR code of the address"))
                    }
                    VStack(alignment: .leading, spacing: Theme.space("s")) {
                        Text(verbatim: url).font(.csCode).textSelection(.enabled)
                        HStack {
                            Button(L("Kopyala", "Copy")) { copyToPasteboard(url) }.buttonStyle(.secondary)
                            Button(L("Aç", "Open")) { if let link = URL(string: url) { NSWorkspace.shared.open(link) } }.buttonStyle(.link)
                        }
                    }
                }
            } else {
                NoticeBox(text: L("Mac yerel ağa bağlı görünmüyor.", "The Mac doesn't seem to be on a local network."))
            }
            HintText(text: L("Sayfa çeviri açıkken çalışır. Adres çubuğunu gizlemek için sayfadaki \"Tam ekran\"a dokunun.",
                             "The page works while translation is on. Tap \"Full screen\" on the page to hide the address bar."))
        }
    }
}

private struct ObsCard: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        let url = model.macAddress.map { "http://\($0):\(model.serverPortOrDefault)/obs" }
        TitledCard(title: L("Yayın için OBS", "OBS for streaming"), symbol: "video") {
            HintText(text: L("OBS'te \"Tarayıcı Kaynağı\" ekleyip adres olarak bunu yazın (1920×1080). Sayfanın zemini saydamdır; altyazı yayının ya da kaydın üstünde görünür.",
                             "In OBS add a \"Browser Source\" with this address (1920×1080). The page has a transparent background, so subtitles appear over your stream or recording."))
            if let url {
                Text(verbatim: url).font(.csCode).textSelection(.enabled)
                Button(L("Adresi kopyala", "Copy the address")) { copyToPasteboard(url) }.buttonStyle(.secondary)
            } else {
                NoticeBox(text: L("Mac yerel ağa bağlı görünmüyor.", "The Mac doesn't seem to be on a local network."))
            }
            HintText(text: L("OBS bu Mac'teyse adres olarak http://127.0.0.1:\(model.serverPortOrDefault)/obs da olur.",
                             "If OBS runs on this Mac, http://127.0.0.1:\(model.serverPortOrDefault)/obs also works."))
        }
    }
}

/// CoreImage ile QR kodu.
enum QRCode {
    private static let context = CIContext()

    static func image(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cgImage = context.createCGImage(output, from: output.extent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: output.extent.width, height: output.extent.height))
    }
}
