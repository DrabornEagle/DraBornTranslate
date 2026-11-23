// SPDX-License-Identifier: AGPL-3.0-only

import RemotePlay
import SwiftUI
import WebKit

/// Sony'nin giriş sayfası (Windows: PsnLoginWindow, telefon: PsnLoginPage). Giriş bitince sayfa
/// `remoteplay/redirect` adresine gider; o adres yakalanır ve hesap kimliği alınır. Parola yalnız Sony'ye gider.
/// Her açılışta çerezsiz yeni bir oturum kullanılır; önceki girişler (ör. kendi hesabınız) karışmaz.
struct PSNLoginSheet: View {
    @Environment(MacModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    /// Hesap bağlanınca çağrılır.
    var onLinked: () -> Void = {}

    @State private var reloadToken = 0
    @State private var linking = false
    @State private var error: String?
    @State private var loading = true

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: Theme.space("m")) {
                Image(systemName: "lock.fill").foregroundStyle(Color.csSecondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("PSN Girişi", "PSN Sign-in")).font(.csHeadline)
                    Text(L("Sony'nin kendi sayfası. Şifreniz bu uygulamaya kaydedilmez.", "Sony's own page. Your password is not stored by this app."))
                        .font(.csCaption).foregroundStyle(Color.csSecondary)
                }
                Spacer()
                if loading && !linking { ProgressView().controlSize(.small) }
                Button {
                    error = nil
                    reloadToken += 1
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .help(L("Sayfayı yeniden yükle", "Reload the page"))
                .disabled(linking)
                Button(L("Vazgeç", "Cancel")) { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
            .padding(Theme.space("m"))
            Divider()
            ZStack {
                PSNWebView(reloadToken: reloadToken, loading: $loading) { url in
                    Task { await finish(url) }
                }
                .opacity(linking ? 0.25 : 1)
                if linking {
                    VStack(spacing: Theme.space("m")) {
                        ProgressView()
                        Text(L("Hesap bağlanıyor…", "Linking the account…"))
                    }
                }
            }
            if let error {
                Divider()
                HStack {
                    ErrorText(error: error)
                    Spacer()
                    Button(L("Yeniden dene", "Try again")) {
                        self.error = nil
                        reloadToken += 1
                    }
                }
                .padding(Theme.space("m"))
            }
        }
        .frame(width: 560, height: 720)
        .background(Color.csBackground)
    }

    private func finish(_ url: URL) async {
        guard !linking else { return }
        linking = true
        defer { linking = false }
        do {
            try await model.finishPSNLogin(redirect: url)
            model.refreshSetup()
            onLinked()
            dismiss()
        } catch {
            self.error = model.describePSN(error)
        }
    }
}

/// Sony giriş sayfasını gösteren WKWebView; yönlendirme adresine gidilince gezinmeyi durdurup adresi verir.
private struct PSNWebView: NSViewRepresentable {
    let reloadToken: Int
    @Binding var loading: Bool
    let onRedirect: (URL) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        context.coordinator.lastToken = reloadToken
        view.load(URLRequest(url: PSNAccount.authorizeURL))
        return view
    }

    func updateNSView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        if context.coordinator.lastToken != reloadToken {
            context.coordinator.lastToken = reloadToken
            view.load(URLRequest(url: PSNAccount.authorizeURL))
        }
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        var parent: PSNWebView
        var lastToken = 0
        private var delivered = false

        init(_ parent: PSNWebView) { self.parent = parent }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url, url.absoluteString.hasPrefix(PSNAccount.redirectPrefix) else {
                decisionHandler(.allow)
                return
            }
            decisionHandler(.cancel)
            guard !delivered else { return }
            delivered = true
            parent.onRedirect(url)
            // Hata olursa yeniden yüklemede bir sonraki yönlendirme de yakalansın.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.delivered = false }
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { parent.loading = true }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { parent.loading = false }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { parent.loading = false }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            parent.loading = false
        }
    }
}
