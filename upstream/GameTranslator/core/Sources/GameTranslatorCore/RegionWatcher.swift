// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Metni çevirir; bloklar. Çevrilemezse nil (ya da hata).
public typealias TextTranslate = (String) throws -> String?

/// İşi bir yerde çalıştırır (Android'deki Executor; varsayılanı aynı iş parçacığında hemen çalıştırır).
public typealias WorkRunner = (@escaping () -> Void) -> Void

/// Ek alanların çevirisini TV'ye ileten taraf (gerçekte `SubtitleServer`).
public protocol RegionSender: AnyObject {
    /// `text` boşsa alanın kutusu kalkar.
    func sendRegion(id: String, text: String, box: TextBox?, lineHeight: Double?)
    func clearRegions()
}

/// Bir ek alanın okuması: satırlar (kutuları okunan görüntüye oranla) ve renkli yazılmış sözcük öbekleri.
public struct RegionReading: Sendable {
    public var lines: [TextLine]
    public var colored: [ColoredPhrase]

    public init(lines: [TextLine], colored: [ColoredPhrase] = []) {
        self.lines = lines
        self.colored = colored
    }
}

/// Ek alanın görüntüsünü okur; bloklar. Okunamazsa hata fırlatır (boş alan boş okuma döner).
public protocol RegionReader {
    func read(_ image: PixelImage) throws -> RegionReading
}

/// Kumanda simgelerini ve renkli sözcükleri görüntüden tanıyan okuyucu (Windows: WindowsOcrReader, ReadsIcons açıkken;
/// Android: IconReader). `recognize` görüntüdeki satırları ve sözcükleri yerleriyle verir (görüntüye oranla, 0–1, üstten);
/// telefonda ML Kit, iOS'ta Apple Vision'dır. Yazı tanımanın atladığı ya da yanlış okuduğu düğme simgesi satıra eklenir
/// ("Hold ✕ to climb"); simgenin içindeki yazı ("L2") gerekirse aynı tanıyıcıyla küçük bir kesitten okunur.
///
/// Simgelerin hangi kumandadan olduğu `layout`'ta tutulur (Otomatik'te karelerdeki simgelerden ve düz okunan tuş
/// adlarından karar verilir); uygulama ayarı ve kaynağı `setButtonLayout(_:xboxSource:)` ile verir. `IconReader` bir
/// yapıdır ama karar paylaşılan bir nesnededir: kopyası (örneğin `RegionWatcher`'a verilen) aynı kararı kullanır.
public struct IconReader: RegionReader {
    private let recognize: (PixelImage) throws -> [OcrLine]

    /// Simgelerin hangi kumandadan olduğu kararı (seçilmişse sabit, Otomatik'te karelerden; Windows: ButtonLayout).
    public let layout: ButtonLayoutVote

    public init(recognize: @escaping (PixelImage) throws -> [OcrLine], layout: ButtonLayoutVote = ButtonLayoutVote()) {
        self.recognize = recognize
        self.layout = layout
    }

    /// Tuş simgelerinin kumandası: ayardaki seçim (`ButtonLayout.parse("auto" / "playstation" / "xbox")`) ve görüntü
    /// kaynağı Xbox mu (true: Xbox, false: PS5 Remote Play, nil: bilinmiyor). Otomatik'te kaynağa göre çözülür; seçim
    /// değişince Otomatik karar sıfırlanır.
    public func setButtonLayout(_ setting: ButtonLayout, xboxSource: Bool?) {
        layout.setting = ButtonLayoutVote.resolve(setting, xboxSource: xboxSource)
    }

    public func read(_ image: PixelImage) throws -> RegionReading {
        let raw = try recognize(image)
        // Renkli yazılmış sözcükler (GTA 6: "Go to the plane" içinde pembe "plane"); çeviride aynı renkte gösterilir.
        let colored = raw.flatMap { ColoredWords.phrases($0.words, image: image) }
        let width = Double(image.width), height = Double(image.height)
        let layout = self.layout
        let lines = raw.map { line -> TextLine in
            guard let box = line.box else { return TextLine(line.text, box: nil) }
            // Düz okunan tuş adları ("Hold LT to aim") Otomatik kararı besler.
            IconWords.observe(line.text, vote: layout)
            var text = IconWords.withIcons(line.text, words: line.words, width: width, height: height) { place in
                ButtonIcons.classify(image, box: place, readText: readPlain, line: box, readWords: readWords, layout: layout)
            }
            text = IconWords.withEdgeIcons(text, line: box, width: width, height: height) { place in
                // Kenardaki simge satırla birlikte okunur (satır kutusu ∪ kenar kutusu): yazılı rozet ("LT") yerinde aranır.
                let joined = TextBox(left: min(box.left, place.left), top: min(box.top, place.top),
                                     right: max(box.right, place.right), bottom: max(box.bottom, place.bottom))
                return ButtonIcons.classify(image, box: place, readText: readPlain, strict: true, line: joined,
                                            readWords: readWords, layout: layout)
            }
            // Xbox oyununda harf olarak okunan tuşlar ("Press A to jump") işarete çevrilir.
            text = IconWords.withXboxLetters(text, family: layout.current)
            return TextLine(text.trimmingCharacters(in: .whitespacesAndNewlines), box: box)
        }
        return RegionReading(lines: lines, colored: colored)
    }

    /// Simgenin içindeki kısa yazı ("L2"): kesit düz okunur. Kesit okunamazsa simge yazısız sayılır.
    private func readPlain(_ image: PixelImage) -> String? {
        let text = ((try? recognize(image)) ?? []).map(\.text).joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }

    /// Satırın siyah-beyaz okumasındaki sözcükler ve yerleri (simgenin yerine düşen sözcük aranır).
    private func readWords(_ image: PixelImage) -> [OcrWord] { ((try? recognize(image)) ?? []).flatMap(\.words) }
}

/// Ek alanları ana altyazıdan ayrı ve seyrek okur, çevirip TV'ye kendi yerleriyle gönderir (Windows: RegionWatcher.cs,
/// Android: RegionWatcher.kt; aynı kurallar; CouchSubs / Nino Game Translator AGPL-3.0 RegionWatcher uyarlaması).
/// - Ana okuma hattını beklemez, onu bekletmez: `offer` hemen döner, tur `executor`'da sürer.
/// - En çok `intervalMs`'de bir tur atılır; önceki tur bitmediyse kare atlanır.
/// - Bölge izi değişmeyen alan yeniden okunmaz.
/// - Yazı ancak kararlıysa çevrilir: iz bir tur değişmediyse ya da iki okuma aynı yazıyı verdiyse.
/// - Yazı değişmedikçe yeniden çevrilmez; boşalan alanın kutusu kalkar.
/// - Paket ve çeviri motoru altyazıyla aynıdır; ama ek alan satırları ortak havuza önerilmez.
public final class RegionWatcher: @unchecked Sendable {
    /// Windows'taki tur aralığı. Telefon pil ve ısı için daha seyrek tur atar (`phoneIntervalMs`).
    public static let defaultIntervalMs: Int64 = 250
    /// Telefondaki tur aralığı: yazı yine bir saniye içinde çevrilir, işlemci ana altyazıya kalır.
    public static let phoneIntervalMs: Int64 = 450
    private static let renderedLimit = 64
    /// Yarım okuma en çok bu kadar tur bekletilir; tamamı gelmezse yine de gösterilir.
    private static let prefixWaitLimit = 4

    /// Okunan alandan çevrilecek yazı: parçalar, karedeki yeri, satır yüksekliği ve karşılaştırma anahtarı.
    public struct Candidate: Equatable, Sendable {
        public var paragraphs: [String]
        public var box: TextBox?
        public var lineHeight: Double?
        public var key: String
        public var colored: [ColoredPhrase] = []
    }

    private final class State {
        var signature: [UInt8]?
        var lastRead: String?
        var candidate: Candidate?
        var shown = ""
        var renderedKey: String?
        var renderedText: String?
        var renderedBox: TextBox?
        var renderedLineHeight: Double?
        /// Son gösterilen (boş olmayan) yazı; alan boşalınca da hatırlanır.
        var remembered = ""
        /// Yarım okuma ("Fly to a", tamamı "Fly to a drop") kaç turdur bekletiliyor.
        var prefixWaits = 0
        /// Gösterilen yazıdaki tuş simgesi sayısı.
        var shownIcons = 0
    }

    /// Alanların durumu; `reset` yeni bir tablo kurar, süren tur eskisinde kalır.
    private final class StateTable {
        var items: [String: State] = [:]
    }

    private struct Translated { let result: [String?]; let fromPack: Int }

    private struct TranslationFailed: Error, CustomStringConvertible {
        var description: String { "çeviri motoru yanıt vermedi" }
    }

    /// Bir kez çalışan, sonucunu bekleyenlere veren çeviri işi (Android: FutureTask).
    private final class TranslationTask: @unchecked Sendable {
        private let condition = NSCondition()
        private var started = false
        private var result: Result<Translated, Error>?
        private let work: () throws -> Translated
        var onFailure: (() -> Void)?

        init(_ work: @escaping () throws -> Translated) { self.work = work }

        func run() {
            condition.lock()
            if started {
                condition.unlock()
                return
            }
            started = true
            condition.unlock()
            let outcome = Result { try work() }
            if case .failure = outcome { onFailure?() }
            condition.lock()
            result = outcome
            condition.broadcast()
            condition.unlock()
        }

        func get() throws -> Translated {
            condition.lock()
            while result == nil { condition.wait() }
            let outcome = result!
            condition.unlock()
            return try outcome.get()
        }
    }

    private let reader: RegionReader
    private let server: RegionSender
    private let intervalMs: Int64
    private let executor: WorkRunner
    private let prefetchExecutor: WorkRunner?
    private let clock: () -> Int64

    private let lock = NSLock()
    /// Çevrilmiş yazılar (anahtar → TV'ye giden metin). Oyun görev yazısını arada silip yeniden gösteriyor; aynı yazı
    /// gelince çevirmene yeniden gidilmez, anında gösterilir.
    private var rendered: [String: String] = [:]
    /// Süren ya da bitmiş çeviriler (anahtar → paragrafların çevirisi).
    private var inflight: [String: TranslationTask] = [:]
    private var states = StateTable()
    private var lastPass: Int64?
    private var busyFlag = false
    private var session = 0
    private var passCount = 0
    private var readCount = 0
    private var engine: TextTranslate
    private var lookup: ((String) -> PackMatch?)?
    private var logger: ((String) -> Void)?
    private var keeps = false
    private var skipper: ((String) -> Bool)?
    private var words: ((String) -> Bool)?

    /// `translator`: çeviri motoru; hat yeniden kurulunca değiştirilir. `executor`: turların çalıştığı yer (telefonda
    /// ayrı bir iş parçacığı; testlerde aynı iş parçacığı). `prefetchExecutor`: kararlılık beklenirken çevirinin şimdiden
    /// istendiği yer; nil ise önceden çevrilmez. `clock`: tekdüze artan saat, milisaniye.
    public init(reader: RegionReader, translator: @escaping TextTranslate, server: RegionSender,
                intervalMs: Int64 = RegionWatcher.defaultIntervalMs, executor: @escaping WorkRunner = { $0() },
                prefetchExecutor: WorkRunner? = nil, clock: @escaping () -> Int64 = monotonicMilliseconds) {
        self.reader = reader
        self.engine = translator
        self.server = server
        self.intervalMs = intervalMs
        self.executor = executor
        self.prefetchExecutor = prefetchExecutor
        self.clock = clock
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    /// Çeviri motoru; hat yeniden kurulunca değiştirilir.
    public var translator: TextTranslate {
        get { locked { engine } }
        set { locked { engine = newValue } }
    }
    /// Oyun paketi: satırın hazır çevirisi (ana altyazıyla aynı arama; önce tuş çubuğu sözlüğü).
    public var packLookup: ((String) -> PackMatch?)? {
        get { locked { lookup } }
        set { locked { lookup = newValue } }
    }
    public var log: ((String) -> Void)? {
        get { locked { logger } }
        set { locked { logger = newValue } }
    }
    /// Tuş ipuçları ("Hold ✕ to climb", "✕ Select ○ Back") atılmaz, simgesiyle çevrilir (okuyucu simgeleri tanıyorsa).
    public var keepsPrompts: Bool {
        get { locked { keeps } }
        set { locked { keeps = newValue } }
    }
    /// Ek alanda çevrilmeyecek satır (altyazının alana düşen satırı); nil ise hepsi çevrilir.
    public var skipLine: ((String) -> Bool)? {
        get { locked { skipper } }
        set { locked { skipper = newValue } }
    }
    /// Sözcük listesi (İngilizce oyunlarda); küçük yazıdaki "m" okuma hatası bununla onarılır.
    public var wordCheck: ((String) -> Bool)? {
        get { locked { words } }
        set { locked { words = newValue } }
    }

    /// Okuyucu simgeleri tanıyorsa (`IconReader`) tuş simgelerinin kumanda kararı; değilse nil.
    public var buttonLayout: ButtonLayoutVote? { (reader as? IconReader)?.layout }

    /// Tuş simgelerinin kumandası (ayar ve kaynak Xbox mu; bkz. `IconReader.setButtonLayout`). Okuyucu simge tanımıyorsa
    /// etkisizdir. Yalnız seçim değişince Otomatik karar sıfırlanır; her hat güncellemesinde çağrılabilir.
    public func setButtonLayout(_ setting: ButtonLayout, xboxSource: Bool?) {
        (reader as? IconReader)?.setButtonLayout(setting, xboxSource: xboxSource)
    }

    public var passes: Int { locked { passCount } }
    public var reads: Int { locked { readCount } }
    public var isBusy: Bool { locked { busyFlag } }

    private var currentSession: Int { locked { session } }

    /// Her karede çağrılabilir; tur zamanı geldiyse ve önceki tur bittiyse turu `executor`'da başlatır, hemen döner.
    public func offer(_ frame: RegionFrame, areas: [WatchedArea]) {
        if areas.isEmpty && !anyShown() { return }
        let time = clock()
        let start: Bool = locked {
            if let last = lastPass, time - last < intervalMs { return false }
            if busyFlag { return false }
            busyFlag = true
            lastPass = time
            return true
        }
        if !start { return }
        executor { [self] in
            pass(frame, areas: areas)
            locked { busyFlag = false }
        }
    }

    private func anyShown() -> Bool {
        locked { states.items.values.contains { !$0.shown.isEmpty } }
    }

    /// Bir tur: kaldırılan alanların kutusu kalkar, her alan gerekirse okunur ve kararlı yazısı çevrilir. Testler
    /// doğrudan çağırır.
    public func pass(_ frame: RegionFrame, areas: [WatchedArea]) {
        let (started, table, removed): (Int, StateTable, [String]) = locked {
            passCount += 1
            let ids = Set(areas.map(\.id))
            let table = states
            let gone = table.items.filter { !ids.contains($0.key) && !$0.value.shown.isEmpty }.map(\.key).sorted()
            table.items = table.items.filter { ids.contains($0.key) }
            return (session, table, gone)
        }
        for id in removed { server.sendRegion(id: id, text: "", box: nil, lineHeight: nil) }
        for area in areas {
            visit(area, frame, table, started)
            if currentSession != started { return }
        }
    }

    private func visit(_ area: WatchedArea, _ frame: RegionFrame, _ table: StateTable, _ started: Int) {
        guard let crop = frame.area(area.region), crop.width >= 2, crop.height >= 2 else { return }
        let signature = PixelImage.signature(crop)
        let state: State = locked {
            if let existing = table.items[area.id] { return existing }
            let created = State()
            table.items[area.id] = created
            return created
        }
        let previous = state.signature
        let changed = previous == nil || PixelImage.changed(previous!, signature)
        state.signature = signature
        if !changed {
            // İz bir tur değişmedi: okunan yazı kararlıdır.
            if let stable = state.candidate {
                state.candidate = nil
                commit(stable, area, state, started)
            }
            return
        }
        let reading: RegionReading
        do {
            reading = try reader.read(crop)
        } catch {
            // Yazı tanıma hatası boş alan değildir. Eski kutu kalır; aynı kare sonraki turda yeniden okunur. Başarısız
            // kare, önceki adayın kararlılığını da doğrulayamaz.
            state.signature = nil
            state.lastRead = nil
            state.candidate = nil
            return
        }
        if currentSession != started { return }
        let (skip, keep, check): (((String) -> Bool)?, Bool, ((String) -> Bool)?) = locked { (skipper, keeps, words) }
        locked { readCount += 1 }
        let lines = skip.map { skip in reading.lines.filter { !skip($0.text) } } ?? reading.lines
        var candidate = Self.makeCandidate(lines, region: area.region, keepPrompts: keep, wordCheck: check)
        if !reading.colored.isEmpty {
            let paragraphs = candidate.paragraphs
            candidate.colored = reading.colored.filter { phrase in paragraphs.contains { $0.contains(phrase.text) } }
        }
        if state.lastRead == candidate.key {
            state.candidate = nil
            commit(candidate, area, state, started)
        } else {
            state.lastRead = candidate.key
            state.candidate = candidate
            // Yazı kararlı mı diye bir tur beklenirken çevirisi şimdiden istenir: doğrulanınca çoğu zaman hazırdır.
            prefetch(candidate, state)
        }
    }

    private func commit(_ original: Candidate, _ area: WatchedArea, _ state: State, _ started: Int) {
        var candidate = original
        if state.shown == candidate.key {
            guard state.renderedKey == candidate.key, let text = state.renderedText, !text.isEmpty else { return }
            if state.renderedBox == candidate.box && state.renderedLineHeight == candidate.lineHeight { return }
            server.sendRegion(id: area.id, text: text, box: candidate.box, lineHeight: candidate.lineHeight)
            state.renderedBox = candidate.box
            state.renderedLineHeight = candidate.lineHeight
            return
        }
        // Yazı belirirken renkli sözcük geç geliyor: önce "Fly to a", yarım saniye sonra "Fly to a drop" okunuyordu.
        // Az önce gösterilen yazının başı okunursa tamamı beklenir.
        if !candidate.key.isEmpty && state.remembered.count > candidate.key.count &&
            state.remembered.hasPrefix(candidate.key + " ") && state.prefixWaits < Self.prefixWaitLimit {
            state.prefixWaits += 1
            state.candidate = candidate
            return
        }
        state.prefixWaits = 0
        // Ekrandakiyle neredeyse aynı okuma (harita arkada oynarken tuş çubuğu "SET DESTINATION ✕ EXIT ○" / "SET
        // DESTINATIPN EXIT ○" diye okunup kutu sürekli değişiyordu): daha çok tuş simgesi bulmadıysa ekrandaki kalır.
        // Simgesi fazla ama ekrandakinin bir sözcüğünü simgeyle değiştirmiş ("Find □" ← "Find Donnie"): bozuk okumadır.
        let candidateIcons = Self.icons(candidate.paragraphs)
        if !candidate.key.isEmpty && !state.shown.isEmpty && state.shown != candidate.key &&
            ((candidateIcons <= state.shownIcons && (TextMatch.isSimilar(state.shown, candidate.key, threshold: 0.85) ||
                                                     Self.isPartOf(candidate.key, state.shown))) ||
             (candidateIcons > state.shownIcons && Self.losesWords(candidate.key, state.shown))) {
            return
        }
        // Altyazı bu sırada gösterilmiş olabilir: alana taşan altyazı ucu gönderilmeden önce bir kez daha denetlenir.
        if !candidate.key.isEmpty, let skipNow = skipLine, candidate.paragraphs.allSatisfy(skipNow) {
            candidate = Candidate(paragraphs: [], box: nil, lineHeight: nil, key: "")
        }
        let previousShown = state.shown
        state.shown = candidate.key
        state.shownIcons = Self.icons(candidate.paragraphs)
        let log = self.log
        let cached: String? = candidate.key.isEmpty ? nil : locked { rendered[candidate.key] }
        if let cached {
            // Daha önce çevrildi: anında, çevirmene gitmeden.
            if cached.isEmpty {
                server.sendRegion(id: area.id, text: "", box: nil, lineHeight: nil)
            } else {
                server.sendRegion(id: area.id, text: cached, box: candidate.box, lineHeight: candidate.lineHeight)
            }
            state.renderedKey = candidate.key
            state.renderedText = cached
            state.renderedBox = cached.isEmpty ? nil : candidate.box
            state.renderedLineHeight = cached.isEmpty ? nil : candidate.lineHeight
            state.remembered = candidate.key
            log?("ek alan \(area.name): \(Self.excerpt(candidate.paragraphs.joined(separator: " "))) → önceki çeviri ⇒ " +
                 Self.excerpt(ColoredWords.plain(cached.replacingOccurrences(of: "\n", with: "/"))))
            return
        }
        if candidate.key.isEmpty {
            server.sendRegion(id: area.id, text: "", box: nil, lineHeight: nil)
            state.renderedKey = candidate.key
            state.renderedText = ""
            state.renderedBox = nil
            state.renderedLineHeight = nil
            log?("ek alan \(area.name): boşaldı")
            return
        }
        let translated: Translated
        do {
            let task = translation(candidate)
            // Önceden istenmediyse (ya da sırada bekliyorsa) burada çalışır; çalışıyorsa bitmesi beklenir.
            task.run()
            translated = try task.get()
            locked { inflight[candidate.key] = nil }
        } catch {
            if currentSession == started {
                if state.shown == candidate.key {
                    state.shown = previousShown
                    state.candidate = candidate
                }
                log?("ek alan \(area.name): \(Self.excerpt(candidate.paragraphs.joined(separator: " "))) çevrilemedi (\(error))")
            }
            return
        }
        if currentSession != started || state.shown != candidate.key { return }
        // Motorun düşürdüğü tuş simgeleri yerine konur ("SHOW TEXT △" ⇒ "Metni göster" ⇒ "Metni göster △").
        var text = candidate.paragraphs.enumerated().map { i, source -> String in
            guard i < translated.result.count, let value = translated.result[i]?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !value.isEmpty else { return source }
            return UiPrompts.keepIcons(source: source, translation: value)
        }
        // Çevirisi aslıyla aynı (ad, sayı, zaten hedef dilde): kutu gösterilmez.
        let same = candidate.paragraphs.indices.allSatisfy { i in
            TextMatch.isSimilar(TextMatch.normalize(candidate.paragraphs[i]), TextMatch.normalize(text[i]), threshold: 0.90)
        }
        if same {
            remember(candidate.key, "")
            server.sendRegion(id: area.id, text: "", box: nil, lineHeight: nil)
            state.renderedKey = candidate.key
            state.renderedText = ""
            state.renderedBox = nil
            state.renderedLineHeight = nil
            return
        }
        if !candidate.colored.isEmpty { text = withColors(candidate, text) }
        let origin = translated.fromPack == candidate.paragraphs.count ? "paket" : translated.fromPack > 0 ? "paket+motor" : "motor"
        let joined = text.joined(separator: "\n")
        log?("ek alan \(area.name): \(Self.excerpt(candidate.paragraphs.joined(separator: " "))) → \(origin) ⇒ " +
             Self.excerpt(ColoredWords.plain(text.joined(separator: " / "))) + Self.place(candidate.box))
        server.sendRegion(id: area.id, text: joined, box: candidate.box, lineHeight: candidate.lineHeight)
        remember(candidate.key, joined)
        state.remembered = candidate.key
        state.renderedKey = candidate.key
        state.renderedText = joined
        state.renderedBox = candidate.box
        state.renderedLineHeight = candidate.lineHeight
    }

    /// Renkli sözcüklerin çevirisi aynı renkte gösterilir: her renkli öbek tek başına çevrilir (önce paket) ve karşılığı
    /// cümlede işaretlenir (`ColoredWords.mark`). Çeviri olmazsa metin renksiz kalır.
    private func withColors(_ candidate: Candidate, _ text: [String]) -> [String] {
        let lookup = packLookup, engine = translator
        var parts: [(String, String)] = []
        for phrase in candidate.colored {
            // Renk süs: çevrilemezse sözcük renksiz kalır.
            guard let translated = lookup?(phrase.text)?.text ?? (try? engine(phrase.text)) ?? nil else { continue }
            let trimmed = translated.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { parts.append((trimmed.trimEnd(".!?"), phrase.color)) }
        }
        if parts.isEmpty { return text }
        let marked = text.map { ColoredWords.mark($0, parts: parts) }
        log?("renkli: \(candidate.colored.map { $0.text + " " + $0.color }.joined(separator: ", ")) → \(parts.map(\.0).joined(separator: ", "))" +
             (marked == text ? " (çeviride bulunamadı)" : ""))
        return marked
    }

    /// Çevirinin görevini verir (yoksa kurar, henüz başlatmaz); başarısızı listeden çıkar ki sonra yeniden denensin.
    private func translation(_ candidate: Candidate) -> TranslationTask {
        let key = candidate.key, paragraphs = candidate.paragraphs
        return locked {
            if let existing = inflight[key] { return existing }
            if inflight.count > 32 { inflight.removeAll() }
            let task = TranslationTask { [weak self] in
                guard let self else { throw TranslationFailed() }
                return try self.translateAll(paragraphs)
            }
            task.onFailure = { [weak self, weak task] in
                guard let self else { return }
                self.locked { if let task, self.inflight[key] === task { self.inflight[key] = nil } }
            }
            inflight[key] = task
            return task
        }
    }

    /// Kararlılık beklenirken çeviriyi başlatır. Yarım okuma (az önce gösterilen yazının başı) ve daha önce çevrilmiş yazı
    /// için istenmez.
    private func prefetch(_ candidate: Candidate, _ state: State) {
        guard let executor = prefetchExecutor else { return }
        if candidate.key.isEmpty || candidate.key == state.shown { return }
        if state.remembered.hasPrefix(candidate.key + " ") { return }
        if locked({ rendered[candidate.key] != nil }) { return }
        if let skip = skipLine, candidate.paragraphs.allSatisfy(skip) { return }
        // Hata gözlenmez; asıl gösterimde yeniden denenir.
        let task = translation(candidate)
        executor { task.run() }
    }

    private func translateAll(_ paragraphs: [String]) throws -> Translated {
        let lookup = packLookup, engine = translator
        var result: [String?] = paragraphs.map { paragraph in
            guard let text = lookup?(paragraph)?.text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            return text
        }
        let fromPack = result.compactMap { $0 }.count
        for i in result.indices where result[i] == nil {
            guard let value = try engine(paragraphs[i]) else { throw TranslationFailed() }
            result[i] = value
        }
        return Translated(result: result, fromPack: fromPack)
    }

    private func remember(_ key: String, _ text: String) {
        locked {
            if rendered.count >= Self.renderedLimit { rendered.removeAll() }
            rendered[key] = text
        }
    }

    /// Çeviri durdu ya da hat yeniden kuruldu: süren tur bırakılır, TV'deki ek alan kutuları kalkar.
    public func reset() {
        let shown: Bool = locked {
            session += 1
            let shown = states.items.values.contains { !$0.shown.isEmpty }
            states = StateTable()
            lastPass = nil
            return shown
        }
        if shown { server.clearRegions() }
    }

    // MARK: - Okumadan aday kurma (durumsuz)

    private static func words(_ key: String) -> [String] {
        key.split(separator: " ").map(String.init).filter { $0.contains(where: \.isLetterOrDigit) }
    }

    /// Okuma, gösterilen yazının eksik hâli mi ("find □" ← "find donnie"): sözcüklerinin hepsi gösterilende geçiyor
    /// ama gösterilenin bir sözcüğü kayıp.
    public static func losesWords(_ reading: String, _ shown: String) -> Bool {
        let have = words(reading), shownWords = words(shown)
        func alike(_ a: String, _ b: String) -> Bool { a == b || TextMatch.isSimilar(a, b, threshold: 0.75) }
        let lost = shownWords.filter { word in !have.contains { alike($0, word) } }.count
        return lost > 0 && !have.isEmpty && have.allSatisfy { word in shownWords.contains { alike($0, word) } }
    }

    /// Okuma, gösterilen yazının kısa (bir sözcüğü kaçmış olabilir) bir parçası mı.
    public static func isPartOf(_ reading: String, _ shown: String) -> Bool {
        let part = reading.split(separator: " ").map(String.init)
        let whole = shown.split(separator: " ").map(String.init)
        if part.isEmpty || part.count >= whole.count { return false }
        var at = 0, misses = 0
        for word in part {
            var found = -1
            for i in at..<max(at, whole.count) where whole[i] == word || TextMatch.isSimilar(whole[i], word, threshold: 0.75) {
                found = i
                break
            }
            if found < 0 {
                misses += 1
                if misses > 1 { return false }
                continue
            }
            at = found + 1
        }
        return true
    }

    private static func icons(_ paragraphs: [String]) -> Int {
        paragraphs.reduce(0) { total, p in total + p.split(separator: " ").filter { ButtonPrompts.isIcon(String($0)) }.count }
    }

    /// En az iki harfi ve iki harfli bir sözcüğü olan satır yazıdır; geri kalanı sayı ve simgedir.
    public static func isWords(_ text: String) -> Bool {
        text.filter(\.isLetter).count >= 2 && text.whitespaceTokens.contains { $0.filter(\.isLetter).count >= 2 }
    }

    /// Satırlar çevrilecek parçalara: cümle bitince ya da kısa başlıktan sonra büyük harfle yeni parça başlar.
    public static func paragraphs(_ lines: [TextLine]) -> [String] {
        var result: [String] = []
        for line in lines.map({ $0.text.trimmingCharacters(in: .whitespacesAndNewlines) }) {
            if let last = result.last, !startsNew(last, line) {
                result[result.count - 1] = last + " " + line
            } else {
                result.append(line)
            }
        }
        return result
    }

    private static func startsNew(_ previous: String, _ line: String) -> Bool {
        SubtitleStabilizer.endsSentence(previous) ||
            (line.first?.isUppercase == true && previous.whitespaceTokens.count <= 3 && !previous.hasSuffix(","))
    }

    /// Okumadan çevrilecek parçalar: kumanda ipuçları (simgeler tanınmıyorsa), yazı tanıma çöpleri ve harfsiz
    /// göstergeler atılır; satırlar yukarıdan aşağı parçalara toplanır. `wordCheck`: sözcük listesi ("m" onarımı için).
    public static func makeCandidate(_ reading: [TextLine], region: TextBox, keepPrompts: Bool = false,
                                     wordCheck: ((String) -> Bool)? = nil) -> Candidate {
        // Alanın sol ya da sağ kenarına değen satır, alanın dışında süren bir yazının parçasıdır (uzun altyazı satırının
        // ucu telefon alanına taşıyordu: "ot it?", "nd..."). Kesilen harfler okunmadığından kesik satır kenara tam
        // değmeyebilir: pay %4.
        var lines = inReadingOrder(reading
            .filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && (keepPrompts || !ButtonPrompts.isPrompt($0.text)) }
            .map { TextLine(OcrNoise.clean($0.text), box: $0.box) }
            .filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && isWords($0.text) }
            .filter { line in line.box.map { $0.left > 0.04 && $0.right < 0.96 } ?? true })
        // Oyunun menü listesi (GTA 6'da duraklatınca soldaki "Game / Store / Help") çevrilmez; alan boş sayılır.
        if isMenuList(lines) { lines = [] }
        // Küçük yazıda "m" harfi "rn" / "nn" okunuyor ("frorn your noailbox"): sözlüğe göre düzeltilir.
        let paragraphs = paragraphs(lines).map { LetterM.fix($0, isWord: wordCheck) }
        let boxes = lines.compactMap(\.box)
        let box = boxes.first.map { first in boxes.dropFirst().reduce(first) { $0.union($1) }.placed(in: region) }
        let heights = boxes.map { ($0.bottom - $0.top) * (region.bottom - region.top) }.sorted()
        let lineHeight = heights.isEmpty ? nil : heights[heights.count / 2]
        // Anahtar tuş simgelerini de içerir: simgesi sonradan bulunan okuma ("SET DESTINATION ✕") eskisinden ayrılsın.
        let key = paragraphs.flatMap { $0.split(separator: " ").map(String.init) }
            .map { token in ButtonPrompts.isIcon(token) && !token.contains(where: \.isLetterOrDigit) ? token : TextMatch.normalize(token) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return Candidate(paragraphs: paragraphs, box: box, lineHeight: lineHeight, key: key)
    }

    /// Satırlar okuma sırasıyla: yukarıdan aşağı, aynı hizadakiler soldan sağa. Başka renkte yazılmış sözcük ayrı satır
    /// okunuyor ve biraz yukarıda başladığı için öne geçiyordu ("Brian Follow").
    public static func inReadingOrder(_ source: [TextLine]) -> [TextLine] {
        func stable(_ lines: [TextLine], by key: (TextLine) -> Double) -> [TextLine] {
            lines.enumerated().sorted { key($0.element) != key($1.element) ? key($0.element) < key($1.element) : $0.offset < $1.offset }.map(\.element)
        }
        var rows: [[TextLine]] = []
        for line in stable(source, by: { $0.box?.top ?? 0 }) {
            let index: Int? = line.box.flatMap { box in
                rows.firstIndex { row in
                    guard let first = row[0].box else { return false }
                    return abs((first.top + first.bottom) / 2 - (box.top + box.bottom) / 2) < max(first.bottom - first.top, box.bottom - box.top) * 0.5
                }
            }
            if let index { rows[index].append(line) } else { rows.append([line]) }
        }
        return rows.flatMap { stable($0, by: { $0.box?.left ?? 0 }) }
    }

    /// Menü listesi mi: alt alta en az üç kısa satır (en çok üç sözcük, tuş simgeleri sayılmaz), hiçbiri cümle gibi
    /// bitmiyor ve hiçbiri tuş simgesiyle bitmiyor. Sonu tuşla biten satır tuş göstergesidir ("SET DESTINATION ✕") ve
    /// çevrilir; menü maddesinde simge başta durur ("○ Store") ya da hiç yoktur.
    public static func isMenuList(_ lines: [TextLine]) -> Bool {
        if lines.count < 3 { return false }
        return lines.allSatisfy { line in
            let tokens = line.text.split(separator: " ").map(String.init)
            guard let last = tokens.last, !ButtonPrompts.isIcon(last) else { return false }
            let count = tokens.filter { !ButtonPrompts.isIcon($0) && $0.contains(where: \.isLetter) }.count
            return (1...3).contains(count) && !SubtitleStabilizer.endsSentence(line.text)
        }
    }

    private static func excerpt(_ text: String) -> String { text.count <= 120 ? text : String(text.prefix(119)) + "…" }

    /// Kutunun karedeki yeri (günlük için).
    private static func place(_ box: TextBox?) -> String {
        guard let box else { return "" }
        return String(format: " [x %%%.0f–%.0f, y %%%.0f–%.0f]", box.left * 100, box.right * 100, box.top * 100, box.bottom * 100)
    }
}
