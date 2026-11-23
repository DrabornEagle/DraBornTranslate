// SPDX-License-Identifier: AGPL-3.0-only

import GameTranslatorCore
import GTNative
import SwiftUI

/// Okuma alanı (Windows: MainView "Altyazının okunduğu yer"): otomatik bulma, hazır yerler, canlı görüntü üstünde
/// sürüklenip boyutlandırılan kutu, ek alanlar ve onların görünümü.
struct ReadingAreaView: View {
    @Environment(MacModel.self) private var model
    @State private var preview: NSImage?
    @State private var gta6Done = false
    /// Önizlemede düzenlenen kutu: 0 altyazı bölgesi, 1–3 ek alan yuvası (Windows'taki gibi elle çizilir).
    @State private var editing = 0
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        SettingsPage(title: L("Altyazının okunduğu yer", "Where the subtitles are read"),
                     subtitle: L("Çoğu oyunda Otomatik yeterli: uygulama altyazının yerini kendisi bulur. Bulamazsa aşağıdan bir yer seç ya da mavi kutuyu ayarla.",
                                 "Automatic is enough for most games: the app finds where the subtitles are by itself. If it cannot, pick a place below or adjust the blue box.")) {
            gta6Banner
            regionCard
            extraAreasCard
            extraLookCard
        }
        .onAppear { refresh() }
        .onReceive(timer) { _ in if model.state.streaming { refresh() } }
    }

    private func refresh() { preview = model.snapshot(width: 960) }

    // MARK: - GTA 6

    private var gta6Banner: some View {
        HStack(spacing: Theme.space("l")) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(verbatim: L("Tek tıkla GTA 6 için en iyi okuma", "Best reading for GTA 6 in one click")).font(.csBody.weight(.semibold))
                    Badge(text: L("DENEYSEL", "EXPERIMENTAL"))
                }
                HintText(text: L("Altyazının yeri kendiliğinden bulunur; alttaki görev yazısı, sol üstteki ipucu kutusu ve telefon mesajları da çevrilir.",
                                 "Subtitles are found automatically; the quest text at the bottom, the hint box at the top left and phone messages are also translated."))
            }
            Spacer()
            if gta6Done { DoneLabel(text: L("Ayarlandı", "Done")) }
            Button(L("GTA 6 için ayarla", "Use for GTA 6")) {
                var settings = model.settings
                settings.extraAreas = settings.extraAreas.withGta6Reading()
                settings.autoRegion = true
                model.settings = settings
                gta6Done = true
            }
            .buttonStyle(.primary)
        }
        .padding(Theme.space("l"))
        .background(Color.csAccentSoft, in: RoundedRectangle(cornerRadius: Theme.radius("card")))
    }

    // MARK: - Altyazı bölgesi

    private var regionCard: some View {
        SettingsCard(title: L("Altyazı bölgesi", "Subtitle area"),
                     footer: model.settings.autoRegion
                        ? L("Uygulama ilk konuşmalardan altyazının çıktığı şeridi öğrenir ve yalnız orayı okur; menü, görev yazısı ve tuş ipuçları atlanır. Altyazı bulunmazsa otomatiği kapatıp kutuyu elle yerleştir.",
                            "The app learns the band where subtitles appear from the first lines of dialogue and reads only there; menus, objectives and button prompts are skipped. If subtitles are not found, turn this off and place the box yourself.")
                        : L("Mavi kutuyu köşelerinden ya da kenarlarından çekerek boyutlandır, ortasından tutup taşı ya da boş bir yere sürükleyerek yeni kutu çiz. Kutu dar olursa cümlenin başı ya da sonu kesilir.",
                            "Drag the corners or edges of the blue box to resize it, drag its middle to move it, or drag on an empty spot to draw a new box. If the box is too narrow, the start or end of a sentence gets cut off.")) {
            if editing == 0 { presetPicker }
            Picker(L("Düzenlenen kutu", "Box being edited"), selection: $editing) {
                Text(verbatim: L("Altyazı", "Subtitle")).tag(0)
                ForEach(1...ExtraAreas.slots, id: \.self) { slot in Text(verbatim: L("Ek alan \(slot)", "Extra area \(slot)")).tag(slot) }
            }
            .pickerStyle(.segmented)
            if editing == 0 {
                RegionCanvas(image: preview, region: model.settings.region, auto: model.settings.autoRegion,
                             extras: model.settings.watchedAreas, streaming: model.state.streaming) { region in
                    var settings = model.settings
                    settings.region = region
                    settings.autoRegion = false
                    model.settings = settings
                }
                if !model.settings.autoRegion { edgeSliders }
            } else {
                slotEditor(editing)
            }
        }
    }

    /// Ek alan yuvasını önizlemede elle çizme (Windows: Okuma Alanı › ek alanlar › "Alanı çiz").
    @ViewBuilder
    private func slotEditor(_ slot: Int) -> some View {
        let slots = model.settings.extraAreas
        let current = slots.region(slot).map(Region.of) ?? Region(top: 0.40, bottom: 0.60, left: 0.35, right: 0.65)
        RegionCanvas(image: preview, region: current, auto: false,
                     extras: model.settings.watchedAreas.filter { $0.id != "area\(slot)" }, streaming: model.state.streaming) { region in
            var settings = model.settings
            settings.extraAreas = settings.extraAreas.withRegion(slot, region.box).withLive(slot, true)
            model.settings = settings
        }
        HStack {
            HintText(text: slots.region(slot) == nil
                     ? L("Bu yuva boş. Önizlemede boş bir yere sürükleyerek alanı çiz; alan sürekli okunur ve TV'de ayrı kutu olarak çevrilir.",
                         "This slot is empty. Drag on an empty spot in the preview to draw the area; it is read continuously and translated as a separate box on the TV.")
                     : L("Kutuyu taşı ya da kenarlarından boyutlandır. Alan sürekli okunur.", "Move the box or resize it by its edges. The area is read continuously."))
            Spacer()
            if slots.region(slot) != nil {
                Toggle(L("Sürekli oku", "Read continuously"), isOn: Binding(
                    get: { model.settings.extraAreas.live(slot) },
                    set: { live in
                        var settings = model.settings
                        settings.extraAreas = settings.extraAreas.withLive(slot, live)
                        model.settings = settings
                    }))
                Button(L("Alanı kaldır", "Remove area")) {
                    var settings = model.settings
                    settings.extraAreas = settings.extraAreas.withRegion(slot, nil).withLive(slot, false)
                    model.settings = settings
                }
                .buttonStyle(.secondary)
            }
        }
    }

    private var presetPicker: some View {
        let selected = RegionPreset.selected(model.settings)
        return VStack(alignment: .leading, spacing: 8) {
            FlowLayout(spacing: 8) {
                ForEach(RegionPreset.allCases) { preset in
                    Button { apply(preset) } label: {
                        Text(verbatim: preset.title)
                            .lineLimit(1)
                            .font(.csCallout.weight(selected == preset ? .semibold : .regular))
                            .padding(.horizontal, 12).padding(.vertical, 7)
                            .foregroundStyle(selected == preset ? Color.csOnAccent : Color.csText)
                            .background(selected == preset ? Color.csAccent : Color.csBackground, in: Capsule())
                            .overlay(Capsule().strokeBorder(selected == preset ? .clear : Color.csBorder))
                    }
                    .buttonStyle(.plain)
                    .help(preset.help)
                }
            }
            HintText(text: selected.help)
        }
    }

    private func apply(_ preset: RegionPreset) {
        var settings = model.settings
        switch preset {
        case .auto: settings.autoRegion = true
        case .custom: settings.autoRegion = false
        default:
            settings.autoRegion = false
            if let region = preset.region { settings.region = region }
        }
        model.settings = settings
    }

    private var edgeSliders: some View {
        Grid(horizontalSpacing: Theme.space("l"), verticalSpacing: Theme.space("s")) {
            GridRow {
                edgeSlider(L("Üst", "Top"), \.top)
                edgeSlider(L("Sol", "Left"), \.left)
            }
            GridRow {
                edgeSlider(L("Alt", "Bottom"), \.bottom)
                edgeSlider(L("Sağ", "Right"), \.right)
            }
            GridRow {
                Button(L("Varsayılana dön", "Reset to default")) {
                    var settings = model.settings
                    settings.region = Region()
                    model.settings = settings
                }
                .buttonStyle(.link)
                .gridCellColumns(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func edgeSlider(_ title: String, _ edge: WritableKeyPath<Region, Double>) -> some View {
        SliderRow(title: title, value: Binding(
            get: { Int((model.settings.region[keyPath: edge] * 100).rounded()) },
            set: { value in
                var region = model.settings.region
                region[keyPath: edge] = Double(value) / 100
                var settings = model.settings
                settings.region = region.clamped(changing: edge)
                model.settings = settings
            }), range: 0...100, format: { "%\($0)" })
    }

    // MARK: - Ek alanlar

    private var extraAreasCard: some View {
        let slots = model.settings.extraAreas
        let full = (1...ExtraAreas.slots).allSatisfy { slots.live($0) }
        let custom = (1...ExtraAreas.slots).filter { slot in
            slots.live(slot) && slots.region(slot).map { ExtraAreaPreset.matching($0) == nil } == true
        }
        return SettingsCard(title: L("Ekrandaki başka yazıları da çevir", "Also translate other text on screen"),
                            badge: L("DENEYSEL", "EXPERIMENTAL"),
                            footer: L("En çok 3 alan seç. Çevirisi TV'de ve OBS'de İngilizce yazının üstünde küçük bir kutuda, kumanda tuşları simgeleriyle görünür. TV uygulaması 1.0.12 gerekir. Bu özellik yeni: bir sorun görürsen alanları kapat. Altyazı çevirisi bundan etkilenmez.",
                                      "Pick up to 3 areas. Their translation shows on the TV and in OBS in a small box over the original text, with controller buttons as icons. Needs TV app 1.0.12. This is new: if something looks wrong, turn the areas off. Subtitle translation is not affected.")) {
            ForEach(ExtraAreaPreset.allCases, id: \.self) { preset in
                let on = slots.has(preset)
                ToggleRow(title: preset.title, help: preset.help, isOn: Binding(
                    get: { on },
                    set: { value in
                        var settings = model.settings
                        settings.extraAreas = settings.extraAreas.with(preset, on: value)
                        model.settings = settings
                    }))
                .disabled(!on && full)
            }
            ForEach(custom, id: \.self) { slot in
                ToggleRow(title: L("Alan \(slot) (elle seçilmiş)", "Area \(slot) (custom)"),
                          help: L("Bilgisayar uygulamasında elle çizilmiş alan.", "An area drawn by hand in the PC app."),
                          isOn: Binding(get: { true }, set: { value in
                              var settings = model.settings
                              settings.extraAreas = settings.extraAreas.withLive(slot, value)
                              model.settings = settings
                          }))
            }
            if full {
                NoticeText(text: L("Aynı anda en çok 3 alan okunur. Önce birini kapat.", "Up to 3 areas can be read at once. Turn one off first."))
            }
        }
    }

    private var extraLookCard: some View {
        @Bindable var model = model
        return SettingsCard(title: L("Ek alanların görünümü", "Look of the extra areas"),
                            footer: L("TV, OBS ve Mac ekranında aynıdır.", "The same on the TV, in OBS and on the Mac screen.")) {
            SliderRow(title: L("Kutu zemini", "Box background"),
                      help: model.settings.style.regionBackground < 100
                        ? L("Zemin saydamken İngilizce yazı arkadan görünebilir.", "With a see-through background the English text may show through.") : nil,
                      value: $model.settings.style.regionBackground, range: 0...100, step: 5, format: { "%\($0)" })
            SliderRow(title: L("Yazı boyutu", "Text size"),
                      help: L("Kutuya göre seçilen boyun yüzdesi.", "A percentage of the size chosen for the box."),
                      value: $model.settings.style.regionScale, range: 70...150, step: 10, format: { "%\($0)" })
            ChoiceRow(title: L("Yazı rengi", "Text color"), selection: $model.settings.style.regionColor, options: [
                ("", L("Altyazıyla aynı", "Like the subtitles")), ("white", L("Beyaz", "White")), ("yellow", L("Sarı", "Yellow")),
            ])
            ChoiceRow(title: L("Kumanda tuşları", "Controller buttons"),
                      help: L("Ek alanlarda oyunun gösterdiği tuş simgeleri hangi kumandanın. Otomatik: Xbox kaynağında Xbox, PS5 Remote Play'de PlayStation; yakalama kartı ve ekran yakalamada ekrandaki simgelerden anlaşılır.",
                              "The controller whose button icons the game shows in the extra areas. Automatic: Xbox for the Xbox source, PlayStation for PS5 Remote Play, and for capture cards and screen capture it is decided from the icons on screen."),
                      selection: $model.settings.buttonLayout, options: [
                          ("auto", L("Otomatik", "Automatic")), ("playstation", "PlayStation"), ("xbox", "Xbox"),
                      ])
        }
    }
}

// MARK: - Hazır yerler

/// Altyazı bölgesinin hazır yerleri (Windows: MainView.OnPresetClick ile aynı değerler).
enum RegionPreset: String, CaseIterable, Identifiable {
    case auto, bottomCenter, bottomWide, fullBottom, topBanner, custom

    var id: String { rawValue }

    var region: Region? {
        switch self {
        case .bottomCenter: Region(top: 0.72, bottom: 0.85, left: 0.16, right: 0.84)
        case .bottomWide: Region(top: 0.65, bottom: 0.94, left: 0.08, right: 0.92)
        case .fullBottom: Region(top: 0.50, bottom: 0.98, left: 0.02, right: 0.98)
        case .topBanner: Region(top: 0.03, bottom: 0.22, left: 0.08, right: 0.92)
        case .auto, .custom: nil
        }
    }

    @MainActor var title: String {
        switch self {
        case .auto: L("Otomatik (Önerilen)", "Automatic (Recommended)")
        case .bottomCenter: L("Alt Orta (Standart)", "Bottom Center (Standard)")
        case .bottomWide: L("Geniş Alt Bant", "Wide Bottom Band")
        case .fullBottom: L("Tüm Alt Yarı", "Whole Bottom Half")
        case .topBanner: L("Üst Bölge (Görevler)", "Top Area (Quests)")
        case .custom: L("Özel Kutu", "Custom Box")
        }
    }

    @MainActor var help: String {
        switch self {
        case .auto: L("Uygulama altyazının yerini kendisi bulur; menüleri, ipuçlarını ve göstergeleri yok sayar.",
                      "The app finds where the subtitles are by itself and ignores menus, hints and on-screen indicators.")
        case .bottomCenter: L("Çoğu oyunun altyazısının durduğu orta alt bölge.", "The lower middle area where most games show subtitles.")
        case .bottomWide: L("Uzun ya da iki satırlık altyazılar için daha geniş bant.", "A wider band for long or two-line subtitles.")
        case .fullBottom: L("Ekranın alt yarısının tamamı; altyazının yeri değişen oyunlar için.", "The whole lower half of the screen, for games whose subtitles move around.")
        case .topBanner: L("Ekranın üstü; görev ya da altyazısı yukarıda çıkan oyunlar için.", "The top of the screen, for games that show quests or subtitles up there.")
        case .custom: L("Kutuyu görüntü üstünde kendin çiz ya da kenarlarını aşağıdan ayarla.", "Draw the box over the picture yourself or set its edges below.")
        }
    }

    static func selected(_ settings: GTNative.Settings) -> RegionPreset {
        if settings.autoRegion { return .auto }
        return allCases.first { preset in preset.region.map { $0.isClose(to: settings.region) } ?? false } ?? .custom
    }
}

extension Region {
    /// En az %5 yükseklik ve genişlik, kenarlar 0–1 içinde; `changing` kenarı öbürünü itmez, kendisi durur.
    func clamped(changing edge: WritableKeyPath<Region, Double>? = nil) -> Region {
        let minimum = RegionCanvas.minimum
        var r = self
        r.top = min(max(r.top, 0), 1 - minimum)
        r.left = min(max(r.left, 0), 1 - minimum)
        r.bottom = min(max(r.bottom, minimum), 1)
        r.right = min(max(r.right, minimum), 1)
        if r.bottom - r.top < minimum {
            if edge == \Region.top { r.top = r.bottom - minimum } else { r.bottom = min(r.top + minimum, 1); r.top = r.bottom - minimum }
        }
        if r.right - r.left < minimum {
            if edge == \Region.left { r.left = r.right - minimum } else { r.right = min(r.left + minimum, 1); r.left = r.right - minimum }
        }
        return r
    }

    func isClose(to other: Region) -> Bool {
        abs(top - other.top) < 0.001 && abs(bottom - other.bottom) < 0.001 && abs(left - other.left) < 0.001 && abs(right - other.right) < 0.001
    }
}

// MARK: - Ek alan adları

extension ExtraAreaPreset {
    @MainActor var title: String {
        switch self {
        case .bottomObjective: L("Alttaki görev yazısı", "Objective at the bottom")
        case .helpBox: L("İpucu kutusu", "Help box")
        case .phoneMessage: L("Telefon mesajı", "Phone message")
        case .quest: L("Görev yazısı", "Quest text")
        case .notification: L("Bildirim", "Notification")
        case .phone: L("Telefon ya da e-posta", "Phone or email")
        }
    }

    @MainActor var help: String {
        switch self {
        case .bottomObjective: L("Altyazının altında, ortada (GTA 6)", "Below the subtitles, centered (GTA 6)")
        case .helpBox: L("Sol üstteki ipucu (GTA 6)", "Hint at the top left (GTA 6)")
        case .phoneMessage: L("Sağ alttaki mesaj kartı (GTA 6)", "Message card at the bottom right (GTA 6)")
        case .quest: L("Sağ üst köşe", "Top right corner")
        case .notification: L("Sol alt köşe", "Bottom left corner")
        case .phone: L("Sağ alt köşe", "Bottom right corner")
        }
    }
}

// MARK: - Canlı görüntü ve kutu

/// Oyun görüntüsü üstünde altyazı kutusu: ortasından taşınır, köşe ve kenarlarından boyutlandırılır, boş yere
/// sürükleyince yeni kutu çizilir. Sürükleme bitince `commit` çağrılır (her harekette ayar kaydedilmesin).
struct RegionCanvas: View {
    static let minimum = 0.05

    let image: NSImage?
    let region: Region
    let auto: Bool
    let extras: [WatchedArea]
    let streaming: Bool
    let commit: (Region) -> Void

    @State private var draft: Region?

    private var shown: Region { draft ?? region }

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            ZStack(alignment: .topLeading) {
                Color.black
                if let image {
                    Image(nsImage: image).resizable().interpolation(.medium)
                } else {
                    Text(verbatim: L("Çeviri açıkken burada oyun görüntüsü görünür.", "The game picture appears here while translating."))
                        .font(.csCallout).foregroundStyle(Color.white.opacity(0.6))
                        .frame(maxWidth: .infinity).padding(.top, 18)
                }
                // Boş yere sürükleyince yeni kutu.
                Color.clear.contentShape(Rectangle()).gesture(drawGesture(size))
                ForEach(extras, id: \.id) { area in
                    let preset = ExtraAreaPreset.matching(area.region)
                    areaBox(Region.of(area.region), size: size, label: preset?.title ?? area.name)
                }
                box(size)
            }
            .coordinateSpace(name: "canvas")
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: Theme.radius("card")))
        .overlay(alignment: .topTrailing) {
            if streaming {
                Label(L("CANLI", "LIVE"), systemImage: "dot.radiowaves.left.and.right")
                    .font(.caption.weight(.semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Color.black.opacity(0.6), in: Capsule()).padding(8)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("Okunan alanın önizlemesi", "Preview of the reading area"))
    }

    private func rect(_ r: Region, _ size: CGSize) -> CGRect {
        CGRect(x: r.left * size.width, y: r.top * size.height, width: (r.right - r.left) * size.width, height: (r.bottom - r.top) * size.height)
    }

    private func areaBox(_ r: Region, size: CGSize, label: String) -> some View {
        let frame = rect(r, size)
        return RoundedRectangle(cornerRadius: 4)
            .strokeBorder(Color.csSuccess, style: StrokeStyle(lineWidth: 1.5, dash: [5, 3]))
            .background(Color.csSuccess.opacity(0.08))
            .overlay(alignment: .topLeading) {
                Text(verbatim: label).font(.system(size: 10, weight: .semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 4).background(Color.csSuccess.opacity(0.8)).lineLimit(1)
            }
            .frame(width: frame.width, height: frame.height)
            .offset(x: frame.minX, y: frame.minY)
            .allowsHitTesting(false)
    }

    private func box(_ size: CGSize) -> some View {
        let frame = rect(shown, size)
        let color = Color.csAccent
        return ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: 4)
                .strokeBorder(color.opacity(auto ? 0.5 : 1), style: StrokeStyle(lineWidth: 2, dash: auto ? [6, 4] : []))
                .background(color.opacity(auto ? 0.05 : 0.14))
                .overlay(alignment: .topLeading) {
                    Text(verbatim: auto ? L("OTOMATİK · kutuyu taşırsan elle seçime geçer", "AUTOMATIC · moving the box switches to manual")
                                        : L("OKUNAN ALAN", "READING AREA"))
                        .font(.system(size: 10, weight: .bold)).foregroundStyle(.white)
                        .padding(.horizontal, 5).padding(.vertical, 2)
                        .background(color.opacity(auto ? 0.6 : 0.9)).lineLimit(1)
                }
                .contentShape(Rectangle())
                .gesture(editGesture(size, edges: [.top, .bottom, .left, .right], move: true))
                .onHover { inside in if inside { NSCursor.openHand.set() } else { NSCursor.arrow.set() } }
                .frame(width: frame.width, height: frame.height)
            ForEach(Handle.allCases, id: \.self) { handle in
                let point = handle.point(in: CGRect(origin: .zero, size: frame.size))
                Rectangle()
                    .fill(Color.white)
                    .overlay(Rectangle().strokeBorder(color, lineWidth: 1.5))
                    .frame(width: 9, height: 9)
                    // Tutma alanı görünen kareden büyük.
                    .padding(5)
                    .contentShape(Rectangle())
                    .gesture(editGesture(size, edges: handle.edges, move: false))
                    .onHover { inside in if inside { handle.cursor.set() } else { NSCursor.arrow.set() } }
                    .position(point)
            }
        }
        .frame(width: frame.width, height: frame.height, alignment: .topLeading)
        .offset(x: frame.minX, y: frame.minY)
    }

    private func editGesture(_ size: CGSize, edges: Set<RegionEdge>, move: Bool) -> some Gesture {
        DragGesture(minimumDistance: 1, coordinateSpace: .named("canvas"))
            .onChanged { value in
                let dx = value.translation.width / max(size.width, 1), dy = value.translation.height / max(size.height, 1)
                draft = move ? region.moved(dx: dx, dy: dy) : region.resized(edges, dx: dx, dy: dy)
            }
            .onEnded { _ in finish() }
    }

    private func drawGesture(_ size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 4, coordinateSpace: .named("canvas"))
            .onChanged { value in
                func clamp(_ v: Double) -> Double { min(max(v, 0), 1) }
                let x0 = clamp(value.startLocation.x / size.width), y0 = clamp(value.startLocation.y / size.height)
                let x1 = clamp(value.location.x / size.width), y1 = clamp(value.location.y / size.height)
                draft = Region(top: min(y0, y1), bottom: max(y0, y1), left: min(x0, x1), right: max(x0, x1))
            }
            .onEnded { _ in
                // Çok küçük çizim (tıklama) yok sayılır; yeterince büyükse en az %5'e getirilir.
                if let drawn = draft, drawn.bottom - drawn.top >= 0.02 || drawn.right - drawn.left >= 0.02 {
                    draft = drawn.clamped()
                } else {
                    draft = nil
                }
                finish()
            }
    }

    private func finish() {
        if let draft, !draft.isClose(to: region) || auto { commit(draft) }
        draft = nil
    }

    enum RegionEdge: Hashable { case top, bottom, left, right }

    enum Handle: CaseIterable {
        case topLeft, top, topRight, right, bottomRight, bottom, bottomLeft, left

        var edges: Set<RegionEdge> {
            switch self {
            case .topLeft: [.top, .left]
            case .top: [.top]
            case .topRight: [.top, .right]
            case .right: [.right]
            case .bottomRight: [.bottom, .right]
            case .bottom: [.bottom]
            case .bottomLeft: [.bottom, .left]
            case .left: [.left]
            }
        }

        func point(in r: CGRect) -> CGPoint {
            let x = edges.contains(.left) ? r.minX : edges.contains(.right) ? r.maxX : r.midX
            let y = edges.contains(.top) ? r.minY : edges.contains(.bottom) ? r.maxY : r.midY
            return CGPoint(x: x, y: y)
        }

        var cursor: NSCursor {
            switch self {
            case .top, .bottom: .resizeUpDown
            case .left, .right: .resizeLeftRight
            default: .crosshair
            }
        }
    }
}

extension Region {
    /// Kutu taşınır; ekranın dışına çıkmaz.
    func moved(dx: Double, dy: Double) -> Region {
        let w = right - left, h = bottom - top
        let x = min(max(left + dx, 0), 1 - w), y = min(max(top + dy, 0), 1 - h)
        return Region(top: y, bottom: y + h, left: x, right: x + w)
    }

    /// Seçilen kenarlar çekilir; karşı kenara %5'ten çok yaklaşmaz.
    func resized(_ edges: Set<RegionCanvas.RegionEdge>, dx: Double, dy: Double) -> Region {
        let minimum = RegionCanvas.minimum
        var r = self
        if edges.contains(.top) { r.top = min(max(top + dy, 0), bottom - minimum) }
        if edges.contains(.bottom) { r.bottom = max(min(bottom + dy, 1), top + minimum) }
        if edges.contains(.left) { r.left = min(max(left + dx, 0), right - minimum) }
        if edges.contains(.right) { r.right = max(min(right + dx, 1), left + minimum) }
        return r
    }
}
