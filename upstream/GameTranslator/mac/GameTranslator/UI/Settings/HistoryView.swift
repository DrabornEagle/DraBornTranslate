// SPDX-License-Identifier: AGPL-3.0-only

import AppKit
import Combine
import SwiftUI
import UniformTypeIdentifiers

/// Altyazı geçmişi (telefon: history_page.dart): çevrilen son satırlar, en yenisi üstte. Satır kopyalama, hepsini
/// kopyalama, TXT/JSON olarak dışa aktarma ve temizleme. Çeviri açıkken kendiliğinden yenilenir.
struct HistoryView: View {
    @Environment(MacModel.self) private var model
    @State private var entries: [Entry] = []
    @State private var confirmClear = false
    @State private var copied: Int64?
    private let timer = Timer.publish(every: 2, on: .main, in: .common).autoconnect()

    struct Entry: Identifiable, Equatable {
        let id: Int64
        let source: String
        let translation: String
        let date: Date
        let fromPack: Bool
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.space("l")) {
            HStack(alignment: .firstTextBaseline) {
                ScreenHeader(title: Section.history.title,
                             subtitle: entries.isEmpty ? nil : L("\(entries.count) satır · en yenisi üstte", "\(entries.count) lines · newest first"))
                Spacer()
                Button(L("Hepsini kopyala", "Copy all")) { copyToPasteboard(plainText) }
                    .buttonStyle(.secondary).disabled(entries.isEmpty)
                Menu(L("Dışa aktar", "Export")) {
                    Button(L("Metin (TXT)…", "Text (TXT)…")) { export(json: false) }
                    Button("JSON…") { export(json: true) }
                }
                .fixedSize()
                .disabled(entries.isEmpty)
                Button(L("Temizle", "Clear"), role: .destructive) { confirmClear = true }
                    .buttonStyle(.secondary).disabled(entries.isEmpty)
            }
            if entries.isEmpty {
                VStack(spacing: Theme.space("m")) {
                    Image(systemName: "clock.arrow.circlepath").font(.system(size: 40)).foregroundStyle(Color.csSecondary)
                    Text(L("Oynarken çevrilen altyazılar burada birikir. Hızlı geçen bir satırı sonradan buradan okuyabilirsiniz.",
                           "Subtitles translated while you play collect here. You can read a line that went by too fast later on."))
                        .multilineTextAlignment(.center).foregroundStyle(Color.csSecondary).frame(maxWidth: 420)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(entries) { entry in
                    row(entry)
                        .listRowSeparator(.visible)
                        .contextMenu {
                            Button(L("Çeviriyi kopyala", "Copy translation")) { copy(entry, text: entry.translation) }
                            Button(L("Asıl metni kopyala", "Copy original")) { copy(entry, text: entry.source) }
                            Button(L("İkisini kopyala", "Copy both")) { copy(entry, text: "\(entry.translation)\n\(entry.source)") }
                        }
                }
                .listStyle(.inset)
                .scrollContentBackground(.hidden)
                .background(Color.csSurface, in: RoundedRectangle(cornerRadius: Theme.radius("card")))
                HintText(text: L("Son 200 satır bu Mac'te saklanır. Satıra tıklayınca çevirisi kopyalanır; sağ tıkla başka seçenekler.",
                                 "The last 200 lines are kept on this Mac. Click a line to copy its translation; right-click for more."))
            }
        }
        .padding(Theme.space("xl"))
        .onAppear(perform: reload)
        .onReceive(timer) { _ in reload() }
        .confirmationDialog(L("Geçmiş silinsin mi?", "Clear the history?"), isPresented: $confirmClear) {
            Button(L("Temizle", "Clear"), role: .destructive) {
                model.clearHistory()
                entries = []
            }
        } message: {
            Text(L("Kaydedilen bütün satırlar bu Mac'ten silinir.", "All saved lines are deleted from this Mac."))
        }
    }

    private func row(_ entry: Entry) -> some View {
        Button {
            copy(entry, text: entry.translation)
        } label: {
            HStack(alignment: .top, spacing: Theme.space("m")) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: entry.translation).font(.csBody.weight(.semibold)).foregroundStyle(Color.csText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(verbatim: entry.source.replacingOccurrences(of: "\n", with: " ")).font(.csCallout).foregroundStyle(Color.csSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                VStack(alignment: .trailing, spacing: 4) {
                    Text(entry.date, format: .dateTime.hour().minute().second()).font(.csCaption.monospacedDigit()).foregroundStyle(Color.csSecondary)
                    if !Calendar.current.isDateInToday(entry.date) {
                        Text(entry.date, format: .dateTime.day().month(.abbreviated)).font(.csCaption).foregroundStyle(Color.csSecondary)
                    }
                    if entry.fromPack {
                        Text(L("paketten", "from pack"))
                            .font(.csCaption.weight(.semibold)).padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Color.csAccentSoft, in: Capsule()).foregroundStyle(Color.csAccentText)
                    }
                    if copied == entry.id {
                        Label(L("Kopyalandı", "Copied"), systemImage: "checkmark").font(.csCaption).foregroundStyle(Color.csSuccess)
                    }
                }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func reload() {
        let raw = model.history
        let mapped = raw.enumerated().map { index, map -> Entry in
            let millis = (map["date"] as? NSNumber)?.int64Value ?? 0
            return Entry(id: millis * 1000 + Int64(index % 1000), source: map["source"] as? String ?? "",
                         translation: map["translation"] as? String ?? "",
                         date: Date(timeIntervalSince1970: Double(millis) / 1000), fromPack: map["fromPack"] as? Bool ?? false)
        }
        // Motor en yenisini başa koyar; yine de tarihe göre sıralanır.
        let sorted = mapped.sorted { $0.date > $1.date }
        if sorted != entries { entries = sorted }
    }

    private func copy(_ entry: Entry, text: String) {
        copyToPasteboard(text)
        copied = entry.id
        Task {
            try? await Task.sleep(for: .seconds(1.5))
            if copied == entry.id { copied = nil }
        }
    }

    private var plainText: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return entries.map { entry in
            "[\(formatter.string(from: entry.date))]\(entry.fromPack ? " (" + L("paketten", "from pack") + ")" : "")\n\(entry.translation)\n\(entry.source)"
        }.joined(separator: "\n\n")
    }

    private var jsonData: Data? {
        let formatter = ISO8601DateFormatter()
        let list: [[String: Any]] = entries.map {
            ["date": formatter.string(from: $0.date), "source": $0.source, "translation": $0.translation, "fromPack": $0.fromPack]
        }
        return try? JSONSerialization.data(withJSONObject: list, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
    }

    private func export(json: Bool) {
        let panel = NSSavePanel()
        let stamp = Date().formatted(.iso8601.year().month().day())
        panel.nameFieldStringValue = "GameTranslator-\(L("gecmis", "history"))-\(stamp).\(json ? "json" : "txt")"
        panel.allowedContentTypes = [json ? .json : .plainText]
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let data = json ? jsonData : Data(plainText.utf8)
        do {
            try data?.write(to: url, options: .atomic)
        } catch {
            NSAlert(error: error).runModal()
        }
    }
}
