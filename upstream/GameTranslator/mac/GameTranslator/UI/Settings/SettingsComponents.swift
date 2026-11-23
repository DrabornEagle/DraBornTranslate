// SPDX-License-Identifier: AGPL-3.0-only

import GameTranslatorCore
import SwiftUI

// Ayar ekranlarının ortak parçaları: sayfa düzeni, başlıklı kart, açıklamalı anahtar, kaydırıcı ve seçici satırları.
// Ölçü ve renkler Theme'den gelir (HomeView ile aynı dil).

/// Ayar sayfası: büyük başlık, kısa açıklama ve altında kartlar.
struct SettingsPage<Content: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.space("l")) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(verbatim: title).font(.csTitle).foregroundStyle(Color.csText)
                    HintText(text: subtitle)
                }
                content
            }
            .padding(Theme.space("xl"))
            .frame(maxWidth: 860, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }    }
}

/// Başlıklı ayar kartı; altında isteğe bağlı açıklama.
struct SettingsCard<Content: View>: View {
    var title: String?
    var badge: String?
    var footer: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.space("s")) {
            VStack(alignment: .leading, spacing: Theme.space("m")) {
                if let title {
                    HStack(spacing: 8) {
                        Text(verbatim: title).font(.csHeadline)
                        if let badge { Badge(text: badge) }
                    }
                }
                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Theme.space("xl"))
            .background(Color.csSurface, in: RoundedRectangle(cornerRadius: Theme.radius("card")))
            .overlay(RoundedRectangle(cornerRadius: Theme.radius("card")).strokeBorder(Color.csBorder))
            if let footer {
                HintText(text: footer).padding(.horizontal, Theme.space("s"))
            }
        }
    }
}

/// Küçük renkli etiket ("DENEYSEL").
struct Badge: View {
    let text: String
    var color: Color = .csWarning

    var body: some View {
        Text(verbatim: text)
            .font(.system(size: 10, weight: .bold))
            .padding(.horizontal, 6).padding(.vertical, 2)
            .foregroundStyle(color)
            .background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: 4))
    }
}

/// Başlık, açıklama ve sağda bir denetim.
struct SettingRow<Trailing: View>: View {
    let title: String
    var help: String?
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(alignment: .center, spacing: Theme.space("l")) {
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: title).font(.csBody.weight(.medium))
                if let help { HintText(text: help) }
            }
            Spacer(minLength: Theme.space("m"))
            trailing
        }
    }
}

/// Açıklamalı aç/kapa satırı.
struct ToggleRow: View {
    let title: String
    var help: String?
    @Binding var isOn: Bool

    var body: some View {
        SettingRow(title: title, help: help) {
            Toggle("", isOn: $isOn).toggleStyle(.switch).labelsHidden()
        }
    }
}

/// Başlığı ve değeri üstte, kaydırıcısı altında olan satır.
struct SliderRow: View {
    let title: String
    var help: String?
    @Binding var value: Int
    let range: ClosedRange<Int>
    var step = 1
    /// Değerin gösterimi (ör. "%72", "46 px").
    var format: (Int) -> String = { "\($0)" }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(verbatim: title).font(.csBody.weight(.medium))
                Spacer()
                Text(verbatim: format(value)).font(.csCode).foregroundStyle(Color.csAccentText)
            }
            Slider(value: Binding(get: { Double(min(max(value, range.lowerBound), range.upperBound)) },
                                  set: { value = Int(($0 / Double(step)).rounded()) * step }),
                   in: Double(range.lowerBound)...Double(range.upperBound), step: Double(step))
            if let help { HintText(text: help) }
        }
    }
}

/// Başlığı üstte, parçalı seçicisi altında olan satır.
struct ChoiceRow<Value: Hashable>: View {
    let title: String
    var help: String?
    @Binding var selection: Value
    let options: [(value: Value, label: String)]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: title).font(.csBody.weight(.medium))
            Picker(title, selection: $selection) {
                ForEach(options.indices, id: \.self) { index in
                    Text(verbatim: options[index].label).tag(options[index].value)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            if let help { HintText(text: help) }
        }
    }
}

/// Bilgi ya da uyarı notu.
struct NoticeText: View {
    let text: String
    var symbol = "info.circle.fill"
    var color: Color = .csWarning

    var body: some View {
        Label {
            Text(verbatim: text).fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: symbol)
        }
        .font(.csCallout)
        .foregroundStyle(color)
    }
}

/// Altyazı yazı renkleri (TV, OBS ve ikinci ekranda aynı tonlar).
enum SubtitleColors {
    @MainActor static var all: [(key: String, name: String, color: Color)] {
        SubtitleTextColor.allCases.map { color in
            (color.rawValue, name(color.rawValue), Color(hex: color.hex))
        }
    }

    @MainActor static func name(_ key: String) -> String {
        switch key {
        case "yellow": L("Sarı", "Yellow")
        case "cyan": L("Mavi", "Blue")
        case "green": L("Yeşil", "Green")
        case "orange": L("Turuncu", "Orange")
        default: L("Beyaz", "White")
        }
    }

    static func color(_ key: String) -> Color {
        Color(hex: (SubtitleTextColor(rawValue: key.lowercased()) ?? .white).hex)
    }
}

/// Yuvarlak renk düğmeleri.
struct ColorSwatches: View {
    @Binding var selection: String

    var body: some View {
        HStack(spacing: 10) {
            ForEach(SubtitleColors.all, id: \.key) { item in
                let selected = selection.lowercased() == item.key
                Button { selection = item.key } label: {
                    Circle()
                        .fill(item.color)
                        .frame(width: 24, height: 24)
                        .overlay(Circle().strokeBorder(Color.csBorder))
                        .padding(3)
                        .overlay(Circle().strokeBorder(selected ? Color.csAccent : .clear, lineWidth: 2))
                }
                .buttonStyle(.plain)
                .help(item.name)
                .accessibilityLabel(item.name)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }
}

/// Öğeleri satıra sığdığı kadar dizip taşanı alt satıra geçiren düzen (hazır yer düğmeleri için).
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        return CGSize(width: rows.map(\.width).max() ?? 0, height: rows.last.map { $0.y + $0.height } ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.items {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: bounds.minY + row.y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
        }
    }

    private struct Row {
        var items: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
        var y: CGFloat = 0
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows = [Row()]
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            if let last = rows.last, !last.items.isEmpty, last.width + spacing + size.width > width {
                rows.append(Row(y: last.y + last.height + spacing))
            }
            var row = rows.removeLast()
            row.width += (row.items.isEmpty ? 0 : spacing) + size.width
            row.height = max(row.height, size.height)
            row.items.append(index)
            rows.append(row)
        }
        return rows
    }
}

extension Color {
    /// "#RRGGBB" tonu.
    init(hex: String) {
        let (r, g, b) = DesignTokens.rgb(hex) ?? (1, 1, 1)
        self.init(.sRGB, red: r, green: g, blue: b)
    }
}
