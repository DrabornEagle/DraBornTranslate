// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.draborneagle.draborntranslate.ConsoleSession
import com.draborneagle.draborntranslate.GameReadingProfile
import com.draborneagle.draborntranslate.GameTranslatorEngine
import com.draborneagle.draborntranslate.Gta6Profile
import com.draborneagle.draborntranslate.GtaProfileReader
import com.draborneagle.draborntranslate.HudBlock
import com.draborneagle.draborntranslate.HudKind
import com.draborneagle.draborntranslate.HudTranslationSession
import com.draborneagle.draborntranslate.ImportedGamePack
import com.draborneagle.draborntranslate.MissionJournal
import com.draborneagle.draborntranslate.MissionLineSource
import com.gametranslator.gametranslator_mobile.core.ContinuationTranslator
import com.gametranslator.gametranslator_mobile.core.EnglishWords
import com.gametranslator.gametranslator_mobile.core.ExtraAreas
import com.gametranslator.gametranslator_mobile.core.ButtonLayout
import com.gametranslator.gametranslator_mobile.core.ButtonLayoutVote
import com.gametranslator.gametranslator_mobile.core.IconReader
import com.gametranslator.gametranslator_mobile.core.OcrLine
import com.gametranslator.gametranslator_mobile.core.OcrWord
import com.gametranslator.gametranslator_mobile.core.PackMatch
import com.gametranslator.gametranslator_mobile.core.PixelImage
import com.gametranslator.gametranslator_mobile.core.RegionFrame
import com.gametranslator.gametranslator_mobile.core.RegionSender
import com.gametranslator.gametranslator_mobile.core.RegionWatcher
import com.gametranslator.gametranslator_mobile.core.UiPrompts
import com.gametranslator.gametranslator_mobile.core.FreeTranslator
import com.gametranslator.gametranslator_mobile.core.GamePackLibrary
import com.gametranslator.gametranslator_mobile.core.Glossary
import com.gametranslator.gametranslator_mobile.core.HttpPackServer
import com.gametranslator.gametranslator_mobile.core.Languages
import com.gametranslator.gametranslator_mobile.core.Misspelling
import com.gametranslator.gametranslator_mobile.core.OcrNoise
import com.gametranslator.gametranslator_mobile.core.PackGate
import com.gametranslator.gametranslator_mobile.core.PackStore
import com.gametranslator.gametranslator_mobile.core.PackTranslation
import com.gametranslator.gametranslator_mobile.core.PrefixTranslator
import com.gametranslator.gametranslator_mobile.core.Ps5Status
import com.gametranslator.gametranslator_mobile.core.ReadScheduler
import com.gametranslator.gametranslator_mobile.core.RepeatedText
import com.gametranslator.gametranslator_mobile.core.RunningGame
import com.gametranslator.gametranslator_mobile.core.ScreenLine
import com.gametranslator.gametranslator_mobile.core.ShortPhrases
import com.gametranslator.gametranslator_mobile.core.SubtitleFinder
import com.gametranslator.gametranslator_mobile.core.SubtitleHistory
import com.gametranslator.gametranslator_mobile.core.SubtitlePipeline
import com.gametranslator.gametranslator_mobile.core.SubtitleServer
import com.gametranslator.gametranslator_mobile.core.SubtitleSink
import com.gametranslator.gametranslator_mobile.core.TextBox
import com.gametranslator.gametranslator_mobile.core.TextLine
import com.gametranslator.gametranslator_mobile.core.TextTranslator
import com.gametranslator.gametranslator_mobile.core.UiText
import com.gametranslator.gametranslator_mobile.xbox.XboxError
import com.gametranslator.gametranslator_mobile.xbox.XboxFrames
import com.gametranslator.gametranslator_mobile.xbox.XboxStreamSession
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Uygulamanın çekirdeği: PS5 Remote Play oturumunu açar, gelen karelerde altyazı bölgesini okur, çevirir ve
 * TV'deki uygulamaya (ve telefondaki ekrana) gönderir. Bilgisayardaki AppController + StreamSession'ın karşılığı.
 * Tek örnek: arka plan servisi başlatır/durdurur, Flutter arayüzü durumunu izler.
 */
object Engine : Native.SessionListener {
    private const val TAG = "GameTranslator"
    /** Yazı tanıma için saniyede en çok bu kadar kare çözülüp verilir. */
    private const val MAX_FPS = 5
    private const val LOG_LINES = 300
    private const val OTHER_SERVER_AFTER_MS = 15000L

    /** idle, starting, waking, connecting, streaming, reconnecting, stopping, failed */
    @Volatile var phase = "idle"; private set
    @Volatile var message = ""; private set
    @Volatile var running = false; private set
    @Volatile var lastSource = ""; private set
    @Volatile var lastTranslation = ""; private set
    @Volatile var lastRead = ""; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var frameWidth = 0; private set
    @Volatile var frameHeight = 0; private set
    @Volatile var translatedCount = 0; private set
    /** Paketten gösterilen satır sayısı ve son satırın geldiği paketin (oynanan oyunun) adı. */
    @Volatile var packHits = 0; private set
    @Volatile var packGame = ""; private set
    /** PS5'in bildirdiği, o an çalışan oyunun adı (keşif sorusu; Xbox'ta ve bilinmiyorsa boş). */
    @Volatile var runningGame = ""; private set

    val server = SubtitleServer()

    /** Durum değişince ana iş parçacığında çağrılır. */
    @Volatile var onChanged: ((Map<String, Any?>) -> Unit)? = null

    /**
     * "Bu telefonda göster" ekranı için altyazı olayları; ana iş parçacığında çağrılır. TV'ye giden her `subtitle` ve
     * `clear` aynı anda buraya da gelir (durum bildirimi [onChanged] saniyede en çok 10 kez gider ve silmeyi taşımaz).
     * Dinleyici bağlanınca ekranda duran satır varsa hemen gönderilir.
     */
    var onSubtitle: ((Map<String, Any?>) -> Unit)?
        get() = subtitleListener
        set(value) {
            subtitleListener = value
            val current = screenLine.current(SystemClock.elapsedRealtime()) ?: return
            main.post { if (subtitleListener === value) value?.invoke(current) }
        }
    @Volatile private var subtitleListener: ((Map<String, Any?>) -> Unit)? = null
    private val screenLine = ScreenLine()

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var controlThread: Thread? = null
    private val control: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gt-control").also { controlThread = it } }
    private val reader = Executors.newSingleThreadExecutor { r -> Thread(r, "gt-reader") }
    private val translating = Executors.newSingleThreadExecutor { r -> Thread(r, "gt-translate") }
    /** Ortak havuzun gönderimleri (ağ; okumayı ve çeviriyi bekletmez). */

    private val logs = ArrayDeque<String>()
    private val notifyPending = AtomicBoolean(false)

    private var appContext: Context? = null
    private var store: Store? = null
    @Volatile private var settings = Settings()
    private var session = 0L
    private var retry: ScheduledFuture<*>? = null
    /** Durdurma istendi ama denetim iş parçacığı henüz işlemedi (uyandırma beklemesini keser). */
    @Volatile private var stopRequested = false

    private val frameLock = Any()
    private var frame: Bitmap? = null
    private val reading = AtomicBoolean(false)
    private val scheduler = ReadScheduler()
    private var recognizer: TextRecognizer? = null
    @Volatile private var pipeline: SubtitlePipeline? = null
    private var gtaReader: GtaProfileReader? = null
    private var gtaTranslator: GameTranslatorEngine? = null
    private var gtaHud: HudTranslationSession? = null
    private var gtaReadAt = 0L
    private var lastLines: List<String> = emptyList()
    private var lastBox: TextBox? = null

    /** Ağda bulunan başka GameTranslator sunucusu (açık duran bilgisayar uygulaması); TV ona bağlanmış olabilir. */
    @Volatile private var otherServer: String? = null

    init {
        server.onClientsChanged = { count ->
            if (count > 0) otherServer = null
            notifyChanged()
        }
    }

    /**
     * Çeviri başladıktan bir süre sonra TV hâlâ bağlanmadıysa ağda başka bir GameTranslator var mı bakılır: TV
     * uygulaması (1.0.5 ve öncesi) ilk bulduğu sunucuya bağlanır, o da çoğu zaman açık duran bilgisayar uygulamasıdır.
     */
    private fun watchForOtherServer() {
        otherServer = null
        Thread({
            try {
                Thread.sleep(OTHER_SERVER_AFTER_MS)
                if (!running || server.connectedClients > 0) return@Thread
                val own = localAddress() ?: return@Thread
                val found = com.gametranslator.gametranslator_mobile.core.OtherServers.find(own)
                if (found != null && running && server.connectedClients == 0) {
                    otherServer = found
                    log(1, "ağda başka bir GameTranslator açık: $found (TV ona bağlanmış olabilir)")
                    notifyChanged()
                }
            } catch (error: Exception) {
            }
        }, "gt-other-server").apply { isDaemon = true }.start()
    }

    // --- Altyazının gittiği yerler: TV sunucusu ve telefondaki ekran ---

    private fun showSubtitle(text: String, durationMs: Int, box: TextBox?, replaces: Boolean) {
        server.sendSubtitle(text, durationMs, box, replaces)
        val event = screenLine.show(text, durationMs, replaces, SystemClock.elapsedRealtime())
        main.post { if (running) ConsoleSession.subtitle(lastSource, text) }
        main.post { subtitleListener?.invoke(event) }
    }

    private fun clearSubtitle() {
        server.clear()
        main.post { ConsoleSession.subtitle(null, null) }
        val event = screenLine.clear()
        main.post { subtitleListener?.invoke(event) }
    }

    // --- Durum ---

    fun state(): Map<String, Any?> = mapOf(
        "phase" to phase,
        "message" to message,
        "running" to running,
        "tvClients" to server.connectedClients,
        "otherServer" to otherServer,
        "serverPort" to server.boundPort,
        "localAddress" to localAddress(),
        "lastSource" to lastSource,
        "lastTranslation" to lastTranslation,
        "lastRead" to lastRead,
        "frames" to frameCount,
        "frameWidth" to frameWidth,
        "frameHeight" to frameHeight,
        "translated" to translatedCount,
        "packHits" to packHits,
        "packGame" to packGame,
        "runningGame" to runningGame,
        "nativeAvailable" to Native.available,
    )

    // --- Altyazı geçmişi (Windows: AppController.AddToHistory) ---

    /** Çevrilen satırların geçmişi; oturumlar arasında uygulamanın dosyalarında (history.json) kalır. İlk istekte okunur. */
    @Volatile private var history: SubtitleHistory? = null
    private var historyFile: File? = null
    /** Geçmiş dosyaya sırayla yazılır; yazmalar üst üste binmez, çeviriyi bekletmez. */
    private val historySaving = Executors.newSingleThreadExecutor { r -> Thread(r, "gt-history").apply { isDaemon = true } }
    /** Son paket eşleşmesinin kaynak satırı: hemen ardından gelen çifti "paketten" diye işaretlemek için. */
    @Volatile private var lastPackSource: String? = null

    @Synchronized
    private fun historyOf(context: Context): SubtitleHistory = history ?: run {
        val file = File(context.applicationContext.filesDir, "history.json")
        historyFile = file
        SubtitleHistory.load(file).also { history = it }
    }

    /** Geçmiş, en yenisi başta (arayüz için düz eşlemeler). */
    fun historyEntries(context: Context): List<Map<String, Any?>> = historyOf(context).entries().map { entry ->
        mapOf("source" to entry.source, "translation" to entry.translation, "date" to entry.date, "fromPack" to entry.fromPack)
    }

    /** Geçmişi siler (dosyadaki de boşalır). */
    fun clearHistory(context: Context) {
        historyOf(context).clear()
        saveHistory()
    }

    private fun addToHistory(source: String, translation: String, fromPack: Boolean) {
        val app = appContext ?: return
        if (historyOf(app).add(source, translation, fromPack)) saveHistory()
        MissionJournal.observe(app, source, translation, MissionLineSource.CONSOLE)
    }

    private fun saveHistory() {
        val current = history ?: return
        val file = historyFile ?: return
        historySaving.execute {
            try {
                current.save(file)
            } catch (error: Exception) {
                log(1, "geçmiş kaydedilemedi: ${error.javaClass.simpleName}")
            }
        }
    }

    fun logLines(): List<String> = synchronized(logs) { logs.toList() }

    private fun log(level: Int, text: String) {
        val line = "${if (level == 0) "E" else if (level == 1) "W" else "I"} $text"
        when (level) {
            0 -> Log.e(TAG, text)
            1 -> Log.w(TAG, text)
            else -> Log.i(TAG, text)
        }
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > LOG_LINES) logs.removeFirst()
        }
    }

    private fun setPhase(value: String, text: String = "") {
        phase = value
        message = text
        log(if (value == "failed") 0 else 2, "durum: $value $text".trim())
        main.post { ConsoleSession.phase(value) }
        notifyChanged()
    }

    /** Arayüze en çok ~10 Hz bildirim: kare sayacı her karede değişir. */
    private fun notifyChanged() {
        if (!notifyPending.compareAndSet(false, true)) return
        main.postDelayed({
            notifyPending.set(false)
            onChanged?.invoke(state())
        }, 100)
    }

    /**
     * Denetim iş parçacığında çalıştırır. Yürütücü yakalanmayan hatayı sessizce yutar; hata günlüğe ve arayüze
     * yazılmazsa uygulama "başlatılıyor"da takılı kalmış görünür.
     */
    private fun onControl(block: () -> Unit) {
        control.execute {
            try {
                block()
            } catch (error: Throwable) {
                Log.e(TAG, "denetim hatası", error)
                running = false
                setPhase("failed", UiText.code("unexpected", "${error.javaClass.simpleName}: ${error.message}"))
            }
        }
    }

    // --- Başlat / durdur ---

    /** Uygulama önceki açılışta kendiliğinden kapandıysa nedeni günlüğe yazılır (her kapanma bir kez). */
    fun noteLastExit(context: Context) {
        for (line in CrashLog.collect(context)) log(0, line)
    }

    /** Arayüz ve hizmet tarafındaki olaylar da aynı günlüğe yazılır (Ayarlar > Günlük). */
    fun note(level: Int, text: String) = log(level, text)

    /**
     * "Başlat"a basıldı ama hiçbir şey olmadı bildirimleri için: başlatma işi denetim iş parçacığında birkaç saniyede
     * çalışmaya başlamadıysa o iş parçacığının o an ne yaptığı günlüğe yazılır.
     */
    private fun watchControl(began: java.util.concurrent.atomic.AtomicBoolean) {
        Thread({
            try {
                Thread.sleep(3000)
                if (began.get()) return@Thread
                val stack = controlThread?.stackTrace?.take(14)?.joinToString("\n") { "  $it" }
                log(0, "başlatma 3 sn'dir sırada bekliyor; denetim iş parçacığı şunu yapıyor:\n" + (stack?.ifEmpty { "  (boşta)" } ?: "  (iş parçacığı yok)"))
            } catch (error: Throwable) {
            }
        }, "gt-start-watch").apply { isDaemon = true }.start()
    }

    fun start(context: Context) {
        val app = context.applicationContext
        noteLastExit(app)
        log(2, "başlat istendi (durum $phase, çalışıyor=$running)")
        val began = java.util.concurrent.atomic.AtomicBoolean(false)
        watchControl(began)
        onControl {
            began.set(true)
            if (running) {
                log(1, "başlatma yok sayıldı: çeviri zaten açık görünüyor (durum $phase)")
                notifyChanged()
                return@onControl
            }
            stopRequested = false
            appContext = app
            val s = Store(app)
            store = s
            settings = s.settings
            if (s.consoleKind == Store.KIND_XBOX) {
                // Xbox (Beta): Microsoft girişi ve seçili Xbox yeter; PSN, eşleştirme ve libchiaki gerekmez.
                if (s.xboxConsole == null || !XboxAccount.auth(app).signedIn)
                    return@onControl setPhase("failed", UiText.code("need_xbox_setup"))
            } else {
                if (!Native.available) return@onControl setPhase("failed", UiText.code("native_missing"))
                if (s.accountId == null || s.console == null) return@onControl setPhase("failed", UiText.code("need_ps_setup"))
            }
            running = true
            lastDeliveredFrame = 0L
            lastSource = ""
            lastTranslation = ""
            lastRead = ""
            GameTranslatorEngine.primeConsoleModel(app)
            frameCount = 0
            translatedCount = 0
            packHits = 0
            packGame = ""
            runningGame = ""
            ps5Game = null
            setPhase("starting")
            refreshPacks(app)
            loadServerAssets(app)
            val port = server.start()
            server.active = true
            watchForOtherServer()
            log(2, "altyazı sunucusu: ${localAddress() ?: "?"}:$port")
            server.sendStyle(settings.style)
            buildPipeline()
            if (s.consoleKind != Store.KIND_XBOX) watchRunningGame()
            connect()
        }
    }

    fun stop() {
        stopRequested = true
        // Oturumun kapanması birkaç saniye sürer (iş parçacıkları beklenir); arayüz bu sırada "durduruluyor" gösterir.
        if (running || session != 0L || xbox != null) setPhase("stopping")
        onControl {
            if (!running && session == 0L && xbox == null) return@onControl setPhase("idle")
            running = false
            retry?.cancel(false)
            retry = null
            gameWatch?.cancel(false)
            gameWatch = null
            runningGame = ""
            closeSession()
            pipeline?.reset()
            pipeline = null
            reader.execute(::resetGtaReading)
            // Ek alan kutuları da TV'den kalkar (süren tur bırakılır).
            regionWatcher?.reset()
            regionWatcher = null
            server.clearRegions()
            clearSubtitle()
            server.stop()
            otherServer = null
            synchronized(frameLock) { frame = null }
            setPhase("idle")
        }
    }

    /** Ayarlar değişti: görünüm hemen TV'ye gider; dil ya da çeviri motoru değiştiyse hat yeniden kurulur. */
    fun applySettings(context: Context) {
        val app = context.applicationContext
        onControl {
            val before = settings
            val now = Store(app).settings
            settings = now
            if (!running) return@onControl
            if (now.style != before.style) server.sendStyle(now.style)
            if (now.engine != before.engine || now.deepLKey != before.deepLKey || now.sourceLanguage != before.sourceLanguage
                || now.targetLanguage != before.targetLanguage || now.glossary != before.glossary
                || now.packsEnabled != before.packsEnabled) {
                buildPipeline()
                // Çeviri dili değişti: o dilin paketleri (ilk kez ya da süresi geldiyse) iner.
                if (now.targetLanguage != before.targetLanguage || now.sourceLanguage != before.sourceLanguage) refreshPacks(app)
            }
            if (now.region != before.region || now.autoRegion != before.autoRegion) {
                scheduler.reset()
                pipeline?.reset()
            }
            if (now.gta6Reading != before.gta6Reading) {
                reader.execute(::resetGtaReading)
                buildPipeline()
            }
            if (now.extraAreas != before.extraAreas) {
                // Ek alan değişti: altyazı okuması alanları yeniden süzsün; okuyucu gerekiyorsa kurulur, gerekmiyorsa kalkar.
                scheduler.reset()
                updateRegionWatcher()
            }
            // Tuş simgelerinin kumandası değişti: ek alanın okuyucusu yeni seçimle (Otomatik karar sıfırlanarak) okur.
            if (now.buttonLayout != before.buttonLayout) updateRegionWatcher()
        }
    }

    private fun buildPipeline() {
        pipeline?.reset()
        val s = settings
        val app = checkNotNull(appContext)
        val turkishExtras = PackTranslation.applies(s.sourceLanguage, s.targetLanguage)
        val glossary = if (turkishExtras) s.parsedGlossary() else Glossary.EMPTY
        if (!glossary.isEmpty) log(2, "terim sözlüğü: ${glossary.count} terim")
        val local = TextTranslator { text -> GameTranslatorEngine.consoleTranslation(app, text) }
        val engine: TextTranslator = if (turkishExtras) FreeTranslator(local, glossary) else local
        // Ek alanlar aynı motorla çevrilir (bilgisayarda MakeTranslator); altyazıya özgü sarmalayıcılar (konuşmacı adı,
        // bölünmüş cümle) görev yazısına uygulanmaz.
        regionTranslator = engine
        // Baştaki konuşmacı işareti ("- ", ">>") motorlara gönderilmez; hangi motor çevirirse çevirsin korunur. Ekranda
        // sarılmış satırlar motora tek satır olarak gider.
        val prefixed = PrefixTranslator(engine)
        val joined = TextTranslator { text -> prefixed.translate(text.replace('\n', ' ')) }
        // Art arda altyazılara bölünen cümle bütün olarak çevrilir, konuşmacı adı çevrilmez (hangi motor seçilirse seçilsin).
        // Kısa ünlemler ("Well?", "Hurry!") İngilizceden Türkçeye hazır karşılıkla çevrilir.
        val misspelling = Misspelling { word -> englishWords?.isWord(word) ?: true }
        val translator: TextTranslator = ContinuationTranslator(joined,
            looksMisread = if (s.sourceLanguage == "en") { text -> englishWords != null && misspelling.looksMisread(text) } else null,
            phrasebook = if (turkishExtras) ShortPhrases::translate else null)
        val gta = s.gta6Reading && turkishExtras
        val built = SubtitlePipeline(translator, object : SubtitleSink {
            override fun show(text: String, durationMs: Int, box: TextBox?, replaces: Boolean) =
                showSubtitle(if (gta) Gta6Profile.localizeSpeaker(text) else text, durationMs, box, replaces)
            override fun clear() = clearSubtitle()
        }, translating, joinLines = false)
        // Okuma onarımı ve okumaların birleştirilmesi İngilizce sözcük listesiyle (ilk kullanımda arka planda açılır).
        if (s.sourceLanguage == "en") {
            built.englishSource = true
            englishWords?.let { words -> built.wordCheck = words::isWord } ?: loadEnglishWords()
        }
        // Altyazı bölgesine düşen arayüz yazısı (envanter çubuğu, savaş günlüğü) kısa sürede aynen yinelenir ve cümle gibi
        // bitmez; üç kez çevrildikten sonra bir süre okunmaz.
        built.repeats = RepeatedText()
        built.onRepeatBlocked = { line -> log(2, "arayüz yazısı: \"$line\" yineleniyor, artık çevrilmeyecek") }
        built.onPair = { source, translation ->
            lastSource = source
            lastTranslation = translation
            translatedCount++
            // Yalnız altyazı satırları geçmişe girer; ek alanların çevirileri girmez (bilgisayarda da öyle).
            addToHistory(source, translation, fromPack = lastPackSource == source)
            log(2, "çeviri: \"${source.replace('\n', ' ')}\" → \"$translation\"")
            notifyChanged()
        }
        built.onTranslateFailed = { source -> log(1, "çevrilemedi: \"${source.replace('\n', ' ')}\"") }
        // Gecikmenin nerede oluştuğu: satır ilk okunduğundan TV'ye gidene kadar; kararlılık beklemesi ve çeviri süresi.
        built.onTiming = { source, total, settle, translate, packed ->
            log(2, "süre: ${total} ms (karar ${settle} ms, ${if (packed) "paket" else "çeviri"} ${translate} ms) \"${source.replace('\n', ' ').take(40)}\"")
        }
        // Oyun paketi: satır paketteyse motora gidilmez. Bulanık arama yalnız son tam eşleşmenin paketinde (oynanan oyun).
        // Ek alanların paketi: önce tuş çubuğu sözlüğü ("OPEN ✕" → "Aç ✕"; motora gitmez), sonra oyun paketi. Görev yazısı
        // gibi kısa satırlar başka oyunların paketlerinde de geçer: paket yalnız oyun bilindiğinde kullanılır.
        // Oyun paketleri İngilizce→Türkçe ve İngilizce→Arapça (her dilin kendi paketleri); tuş çubuğu sözlüğü yalnız Türkçe.
        val packLanguage = PackTranslation.packLanguage(s.sourceLanguage, s.targetLanguage)
        val regionLibrary = if (s.packsEnabled && packLanguage != null) packLibrary(app, packLanguage) else null
        regionPackLookup = if (packLanguage == null) null else { text ->
            (if (turkishExtras) UiPrompts.translate(text)?.let { PackMatch(it) } else null)
                ?: regionLibrary?.takeIf { packsReady.get() && it.gameKnown }?.let { library -> PackTranslation.match(library, text) }
        }
        if (s.packsEnabled && packLanguage != null) {
            val library = packLibrary(app, packLanguage)
            // Paketler ayrı iş parçacığında açılır (14 paket, orta telefonda 5–10 sn): çeviri iş parçacığında açılınca
            // ilk satırların hepsi paketlerin bitmesini bekliyor, altyazı geç geliyordu. Paketler hazır olana kadar
            // satırlar çeviri motoruna gider.
            if (!packsReady.get()) packLoader.execute {
                val started = SystemClock.elapsedRealtime()
                try {
                    val infos = library.infos
                    packsReady.set(true)
                    log(2, "oyun paketleri: ${infos.size} paket, ${infos.sumOf { it.entries }} satır (${SystemClock.elapsedRealtime() - started} ms)")
                    // PS5 oyunu paketler açılmadan bildirdiyse paketi şimdi seçilir.
                    applyDetectedGame(library)
                } catch (error: Throwable) {
                    // Bellek yetmezse paketsiz sürer; çeviri motoru çalışmaya devam eder.
                    log(0, "oyun paketleri açılamadı: ${error.javaClass.simpleName}")
                }
            }
            // Paket yalnız oynanan oyunun paketiyse kullanılır (başka oyunun genel satırları yanlış çeviri basıyordu). PS5
            // oyunu bildirdiyse ve paketi varsa paket ilk satırdan kullanılır (yalnız o oyunun paketi aranır).
            val gate = PackGate()
            built.packLookup = { text ->
                // Paketler henüz açılmadıysa (ya da yeniden yükleniyorsa) beklenmez, satır çeviri motoruna gider.
                val match = if (!packsReady.get()) null else PackTranslation.match(library, text)
                if (library.gameKnown) match else gate.filter(text, match)
            }
            built.onPackHit = { source, match ->
                packHits++
                lastPackSource = source
                packGame = library.scopeTitle ?: match.packId ?: ""
                log(2, "paket (${match.packId}): \"${source.replace('\n', ' ')}\"")
            }
        }
        built.onReadText = { text -> if (text != lastRead) { lastRead = text; notifyChanged() } }
        scheduler.reset()
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        pipeline = built
        updateRegionWatcher()
    }

    // --- İngilizce sözcük listesi ---

    /** Okuma onarımı ve okumaların birleştirilmesi için sözcük listesi; ilk İngilizce oturumda açılır, bellekte kalır. */
    @Volatile private var englishWords: EnglishWords? = null
    private val wordsLoading = AtomicBoolean(false)

    /** Listeyi arka planda açar (~0,3 sn) ve hazır olunca o anki hatta bağlar. */
    private fun loadEnglishWords() {
        val app = appContext ?: return
        if (!wordsLoading.compareAndSet(false, true)) return
        Thread({
            try {
                val started = SystemClock.elapsedRealtime()
                val words = runCatching { app.assets.open("english-words.txt.gz") }.getOrElse { app.assets.open("english-words.txt") }
                    .use { EnglishWords.load(it) }
                englishWords = words
                log(2, "İngilizce sözcük listesi: ${words.size} sözcük (${SystemClock.elapsedRealtime() - started} ms)")
                pipeline?.let { current -> if (settings.sourceLanguage == "en") current.wordCheck = words::isWord }
                regionWatcher?.let { watcher -> if (settings.sourceLanguage == "en") watcher.wordCheck = words::isWord }
            } catch (error: Throwable) {
                // Liste yoksa okuma onarılmadan sürer.
                log(1, "İngilizce sözcük listesi açılamadı: ${error.javaClass.simpleName}")
                wordsLoading.set(false)
            }
        }, "gt-words").apply { isDaemon = true }.start()
    }

    // --- PS5'te çalışan oyun ---

    /** PS5'e çalışan oyunu sorma aralığı (soru PS5'i uyandırmaz). */
    private const val RUNNING_GAME_EVERY_S = 45L
    private val gameWatcher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gt-game").apply { isDaemon = true } }
    private var gameWatch: ScheduledFuture<*>? = null
    /** PS5'in son bildirdiği oyun (bilinmiyorsa null). */
    @Volatile private var ps5Game: RunningGame? = null

    /**
     * Çeviri açıkken PS5'e hangi oyunun çalıştığı sorulur (UDP 9302, Windows'taki WatchRunningGameAsync gibi): havuz
     * satırları oyunun kimliği ve adıyla gider, paketi varsa o paket ilk satırdan (ve yalnız o) kullanılır.
     */
    private fun watchRunningGame() {
        gameWatch?.cancel(false)
        gameWatch = gameWatcher.scheduleWithFixedDelay({
            try {
                checkRunningGame()
            } catch (error: Throwable) {
                // Ağ hatası çeviriyi etkilemez.
            }
        }, 0, RUNNING_GAME_EVERY_S, TimeUnit.SECONDS)
    }

    private fun checkRunningGame() {
        val s = store ?: return
        if (!running || s.consoleKind == Store.KIND_XBOX) return
        val host = s.console?.host ?: return
        val info = Ps5Status.query(host, 2000) ?: return
        val game = info.game
        if (game == ps5Game) return
        ps5Game = game
        runningGame = game?.name ?: ""
        val library = synchronized(packLock) { packs }
        if (library != null) applyDetectedGame(library)
        log(2, if (game != null) "PS5'te çalışan oyun: ${game.name ?: "?"} (${game.titleId})" +
            (library?.detected?.let { ", paket: $it" } ?: ", paketi yok") else "PS5'te oyun çalışmıyor")
        notifyChanged()
    }

    /** PS5'in bildirdiği oyunun paketi (varsa) seçilir; paketler kapalıysa ya da oyun yoksa seçim kalkar. */
    private fun applyDetectedGame(library: GamePackLibrary) {
        library.detected = when {
            !settings.packsEnabled -> null
            settings.gta6Reading -> "gta-6"
            else -> library.findByGameName(ps5Game?.name)
        }
    }

    // --- Oyun paketleri ---

    private val packLock = Any()
    /** Paketler açılıp hazır mı; değilse paket araması atlanır. */
    private val packsReady = AtomicBoolean(false)
    /** Paketleri açan / yeniden yükleyen iş parçacığı (çeviriyi bekletmez). */
    private val packLoader = Executors.newSingleThreadExecutor { r -> Thread(r, "gt-pack-load").apply { isDaemon = true } }
    private var packs: GamePackLibrary? = null
    private var packStore: PackStore? = null
    /** [packs] ve [packStore]'un dili (çeviri dili değişince yeniden kurulur). */
    private var packsLanguage = PackStore.LANGUAGE
    private val packRefreshing = AtomicBoolean(false)

    /** Uygulamayla gelen (assets/packs) ve sunucudan inen paketler; ilk aramada açılır, uygulama boyunca bellekte kalır. */
    private fun packLibrary(app: Context, language: String): GamePackLibrary = synchronized(packLock) {
        selectPackLanguage(language)
        packs ?: store(app).let { store ->
            val turkish = language == PackStore.LANGUAGE
            // Uygulamayla gelen paketler Türkçedir; başka dillerin paketleri yalnız sunucudan iner. Önceki paket sunucusundan
            // kalan paketler en düşük öncelikte: aynı satır bizim paketimizde varsa onunki.
            GamePackLibrary({
                if (!turkish) emptyList()
                else ImportedGamePack.sourceFiles(app)
            }, store.folder, lowPriority = { store.legacyIds() })
        }.also { packs = it }
    }

    /** Dil değiştiyse açık kütüphane ve depo bırakılır; sonraki aramada o dilinkiler açılır. */
    private fun selectPackLanguage(language: String) = synchronized(packLock) {
        if (packsLanguage != language) {
            packs = null
            packStore = null
            packsLanguage = language
            packsReady.set(false)
        }
    }

    /** Ayarlardaki çeviri dilinin paket dili (paketi olmayan dilde Türkçe deposu: eskisi gibi yenilenir, kullanılmaz). */
    private fun settingsPackLanguage(): String =
        PackTranslation.packLanguage(settings.sourceLanguage, settings.targetLanguage) ?: PackStore.LANGUAGE

    private fun store(app: Context): PackStore = synchronized(packLock) {
        val language = packsLanguage
        val turkish = language == PackStore.LANGUAGE
        // Uygulamadaki paketlerin .gz özetleri derlemede yazılır (build.gradle.kts: copyGamePacks); AGP varlıkları açtığı
        // için telefonda dosyadan hesaplanamaz. Türkçe paketler eski klasörde kalır; başka diller kendi klasöründe.
        packStore ?: PackStore(File(app.filesDir, if (turkish) "packs-remote" else "packs-remote-$language"),
            HttpPackServer(PackStore.DEFAULT_URL), bundledHashes = {
                if (!turkish) emptySet()
                else ImportedGamePack.downloadedChecksum(app)?.let(::setOf).orEmpty()
            }, language = language).also { packStore = it }
    }

    /**
     * Haftada bir sunucudaki yeni ve güncellenen paketleri indirir (SHA-256 doğrulanır; uygulamada olanlar inmez).
     * Arka planda; değişiklik olduysa ve paketler açıksa yeniden yüklenir.
     */
    private fun refreshPacks(app: Context) {
        if (!settings.packsEnabled || !packRefreshing.compareAndSet(false, true)) return
        selectPackLanguage(settingsPackLanguage())
        Thread({
            try {
                val target = store(app)
                // Türkçe dışındaki bir dilin paketleri ilk kez hemen iner: kullanıcı o dili kendisi seçti.
                val result = target.refresh(force = target.language != PackStore.LANGUAGE && target.installed().isEmpty())
                if (!result.skipped) log(2, "paket sunucusu: ${result.downloaded.size} indirildi, ${result.removed.size} kaldırıldı")
                if (result.downloaded.isNotEmpty() || result.removed.isNotEmpty()) {
                    val library = synchronized(packLock) { packs }
                    if (library != null) packLoader.execute {
                        // Yeniden yükleme sürerken satırlar motora gider (çeviri iş parçacığı beklemez).
                        packsReady.set(false)
                        runCatching { library.reload(); library.infos }
                        packsReady.set(true)
                    }
                }
            } catch (error: Throwable) {
                log(1, "paket sunucusuna ulaşılamadı: ${error.javaClass.simpleName}")
            } finally {
                packRefreshing.set(false)
            }
        }, "gt-packs").start()
    }

    // --- PS5 oturumu ---

    /** [relocated]: adres bu denemede zaten yenilendi; ağ ikinci kez taranmaz. */
    private fun connect(relocated: Boolean = false) {
        if (!running) return
        val s = store ?: return
        if (s.consoleKind == Store.KIND_XBOX) return connectXbox(s)
        val console = s.console ?: return setPhase("failed", UiText.code("no_pairing"))
        val account = s.accountId ?: return setPhase("failed", UiText.code("no_account"))
        setPhase("connecting", console.host)

        // PS5 açık mı? Kapalıysa (dinlenme modu) ayara göre uyandırılır.
        when (probe(console.host)) {
            true -> {}
            false -> {
                val credential = console.wakeCredential
                if (!settings.autoWake || credential == null) {
                    setPhase("failed", UiText.code("rest_mode"))
                    return scheduleRetry(10)
                }
                setPhase("waking", console.host)
                Native.wake(console.host, credential)
                val deadline = SystemClock.elapsedRealtime() + 60000
                var awake = false
                while (!stopRequested && !awake && SystemClock.elapsedRealtime() < deadline) awake = probe(console.host) == true
                if (stopRequested) return
                if (!awake) {
                    setPhase("failed", UiText.code("no_wake"))
                    return scheduleRetry(10)
                }
                setPhase("connecting", console.host)
            }
            null -> {
                // Modem PS5'e yeni adres vermiş olabilir: ağ taranır, aynı adlı konsol bulunursa kayıt güncellenir.
                val moved = if (relocated) null else relocate(console)
                if (stopRequested) return
                if (moved != null) {
                    log(2, "PS5 yeni adreste bulundu: ${console.host} → $moved")
                    s.console = Console(moved, console.name, console.registKey, console.morning)
                    return connect(relocated = true)
                }
                setPhase("failed", UiText.code("not_found", console.host))
                return scheduleRetry(5)
            }
        }

        log(2, "PS5 oturumu: ${console.name} ${console.host}")
        session = Native.sessionStart(console.host, console.registKey, console.morning, account, MAX_FPS, this)
        if (session == 0L) {
            setPhase("failed", UiText.code("session_failed"))
            scheduleRetry(5)
        }
    }

    /**
     * Kayıtlı adres yanıt vermiyorsa konsolu ağda arar. Aynı adlı tek konsol varsa adresini döner; yoksa ya da
     * birden çok aday varsa null (başka bir PS5'in adresi kayda yazılmasın).
     */
    private fun relocate(console: Console): String? {
        val sameName = discover(emptyList(), 2500).filter { it["host"] != console.host && it["name"] == console.name }
        return sameName.singleOrNull()?.get("host") as? String
    }

    /** true: açık, false: dinlenme modunda, null: yanıt yok. */
    private fun probe(host: String): Boolean? {
        var result: Boolean? = null
        Native.discover(arrayOf(host), 1500, object : Native.ConsoleCallback {
            override fun onConsole(host: String, name: ByteArray, awake: Boolean) {
                if (result != true) result = awake
            }
        })
        return result
    }

    private fun closeSession() {
        val handle = session
        session = 0
        if (handle != 0L) Native.sessionStop(handle)
        closeXbox()
    }

    private fun scheduleRetry(seconds: Long) {
        if (!running) return
        retry?.cancel(false)
        retry = control.schedule({ if (running && session == 0L) connect() }, seconds, TimeUnit.SECONDS)
    }

    // Native.SessionListener: libchiaki'nin iş parçacıklarından çağrılır.

    override fun onLog(level: Int, message: String?) {
        // Bilgi satırları (her 5 sn'de kalp atışı, paket dökümleri) günlüğü doldurur; hata ve uyarılar tutulur.
        if (message != null && (level < 2 || message.startsWith("Session") || message.startsWith("Starting") || message.startsWith("Switched")))
            log(level, "chiaki: $message")
    }

    override fun onEvent(event: Int, quitReason: Int, reasonText: String?) {
        when (event) {
            Native.EVENT_CONNECTED -> setPhase("streaming")
            Native.EVENT_LOGIN_PIN -> onControl {
                closeSession()
                running = false
                setPhase("failed", UiText.code("login_pin"))
            }
            Native.EVENT_QUIT -> onControl {
                // Oturum kendi iş parçacığından kapatılamaz; denetim iş parçacığında bırakılır.
                closeSession()
                if (!running) return@onControl
                when (quitReason) {
                    Native.QUIT_STOPPED -> {}
                    Native.QUIT_IN_USE -> {
                        setPhase("failed", UiText.code("in_use"))
                        scheduleRetry(10)
                    }
                    Native.QUIT_REFUSED -> {
                        running = false
                        setPhase("failed", UiText.code("refused"))
                    }
                    else -> {
                        setPhase("reconnecting", reasonText ?: UiText.code("lost"))
                        scheduleRetry(3)
                    }
                }
            }
        }
    }

    override fun onFrame(buffer: ByteBuffer, width: Int, height: Int, stride: Int) {
        if (!running || width < 2 || height < 2 || stride != width * 4) return
        synchronized(frameLock) {
            var target = frame
            if (target == null || target.width != width || target.height != height) {
                target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                frame = target
                frameWidth = width
                frameHeight = height
            }
            buffer.rewind()
            target.copyPixelsFromBuffer(buffer)
        }
        frameCount++
        broadcastFrame()
        if (frameCount % MAX_FPS == 1L) notifyChanged()
        offerRegions()
        // Önceki kare hâlâ okunuyorsa bu kare atlanır; okuma kareleri biriktirmez.
        if (pipeline == null || !reading.compareAndSet(false, true)) return
        reader.execute { readFrame() }
    }

    // --- Xbox oturumu (Beta) ---

    /** Açık Xbox ev yayını oturumu; PlayStation seçiliyken hep null. */
    @Volatile private var xbox: XboxStreamSession? = null
    private var xboxPixels: IntArray? = null

    /**
     * Xbox ev yayınını açar: görüntü WebRTC ile gelir ve PS5 kareleriyle aynı okuma hattına girer. Uyandırma, ağda arama
     * ve PSN yoktur; oturum koparsa kendisi yeniden dener (5 sn), yeniden giriş gerektiren hatada durur.
     */
    private fun connectXbox(s: Store) {
        val app = appContext ?: return
        val console = s.xboxConsole ?: return setPhase("failed", UiText.code("no_xbox"))
        closeXbox()
        setPhase("connecting", console.name)
        log(2, "Xbox oturumu: ${console.name} ${console.model}")
        lateinit var created: XboxStreamSession
        created = XboxStreamSession(app, XboxAccount.auth(app), console, object : XboxStreamSession.Listener {
            // Kapatılmış (eski) oturumun geç gelen çağrıları yok sayılır.
            private val current: Boolean get() = xbox === created && running

            override fun onLog(level: Int, text: String) = log(level, "xbox: $text")

            override fun onConnecting() {
                if (current && phase != "connecting") setPhase("connecting", console.name)
            }

            override fun onStreaming() {
                if (current) setPhase("streaming")
            }

            override fun onFailed(kind: XboxError, detail: String?, willRetry: Boolean) {
                if (!current) return
                val text = XboxAccount.describeStream(kind)
                if (willRetry) return setPhase(if (kind == XboxError.NETWORK) "reconnecting" else "failed", text)
                onControl {
                    if (xbox !== created) return@onControl
                    closeXbox()
                    running = false
                    setPhase("failed", text)
                }
            }

            override fun onFrame(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, strideY: Int, strideU: Int, strideV: Int, width: Int, height: Int) {
                if (current) onXboxFrame(y, u, v, strideY, strideU, strideV, width, height)
            }
        }, 1000L / MAX_FPS, CrashLog.xboxSafeDecoder(app))
        xbox = created
        // Oturum açıkken süreç ölürse işaret kalır; sonraki açılışta çökmenin Xbox sırasında olduğu anlaşılır.
        CrashLog.markXboxOpen(app, true)
        created.start()
    }

    private fun closeXbox() {
        val open = xbox
        xbox = null
        open?.dispose()
        if (open != null) appContext?.let { CrashLog.markXboxOpen(it, false) }
    }

    /** WebRTC'nin çözücü iş parçacığından çağrılır; kare PS5'teki gibi [frame]'e yazılır ve okumaya verilir. */
    private fun onXboxFrame(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, strideY: Int, strideU: Int, strideV: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        var pixels = xboxPixels
        if (pixels == null || pixels.size != width * height) {
            pixels = IntArray(width * height)
            xboxPixels = pixels
        }
        XboxFrames.i420ToArgb(y, u, v, strideY, strideU, strideV, width, height, pixels)
        synchronized(frameLock) {
            // Durdurma bu kare çevrilirken geldiyse kare saklanmaz (durdurunca önizleme boşalır).
            if (!running) return
            var target = frame
            if (target == null || target.width != width || target.height != height) {
                target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                frame = target
                frameWidth = width
                frameHeight = height
            }
            target.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        frameCount++
        broadcastFrame()
        if (frameCount % MAX_FPS == 1L) notifyChanged()
        offerRegions()
        // Önceki kare hâlâ okunuyorsa bu kare atlanır; okuma kareleri biriktirmez.
        if (pipeline == null || !reading.compareAndSet(false, true)) return
        reader.execute { readFrame() }
    }

    // --- Yazı okuma ---

    private fun readFrame() {
        val current = pipeline
        if (current != null && settings.gta6Reading && PackTranslation.applies(settings.sourceLanguage, settings.targetLanguage)) {
            readGtaFrame(current)
            return
        }
        val engine = recognizer
        val auto = settings.autoRegion
        if (auto) findSubtitles()
        // Otomatikte okunan alan öğrenilen şerittir (henüz öğrenilmediyse ekranın alt yarısına yakın geniş alan).
        val region = if (auto) autoRegion ?: AUTO_START else settings.region
        if (regionMoved.getAndSet(false)) scheduler.reset()
        var crop: Bitmap? = null
        var cropX = 0
        var cropY = 0
        var fullWidth = 0
        var fullHeight = 0
        synchronized(frameLock) {
            val source = frame
            if (source != null) {
                fullWidth = source.width
                fullHeight = source.height
                cropX = (region.left * fullWidth).toInt().coerceIn(0, fullWidth - 2)
                cropY = (region.top * fullHeight).toInt().coerceIn(0, fullHeight - 2)
                val w = ((region.right - region.left) * fullWidth).toInt().coerceIn(1, fullWidth - cropX)
                val h = ((region.bottom - region.top) * fullHeight).toInt().coerceIn(1, fullHeight - cropY)
                crop = Bitmap.createBitmap(source, cropX, cropY, w, h)
            }
        }
        val image = crop
        if (current == null || engine == null || image == null) {
            reading.set(false)
            return
        }
        if (scheduler.canReuse(signature(image))) {
            current.onRead(lastLines, lastBox)
            reading.set(false)
            return
        }
        val ocrStarted = SystemClock.elapsedRealtime()
        engine.process(InputImage.fromBitmap(image, 0)).addOnCompleteListener(reader) { task ->
            noteOcr(SystemClock.elapsedRealtime() - ocrStarted)
            try {
                if (task.isSuccessful) {
                    var found = rows(task.result, cropX, cropY, fullWidth, fullHeight)
                    // Otomatik algılamada alan sert bir kırpma değildir: şeritteki satırlar ve onlara bitişik satırlar
                    // altyazıdır; şerit henüz öğrenilmediyse ortadan belirgin biçimde uzak satırlar atılır.
                    if (auto) found = autoBand?.let { band -> SubtitleFinder.grow(found, band) } ?: SubtitleFinder.withoutOffCenterLines(found)
                    // Sürekli okunan ek alanların içine düşen satırlar (alttaki görev yazısı) altyazı değildir.
                    found = ExtraAreas.withoutWatchedAreas(found, settings.watchedAreas())
                    // GTA 6'da alttaki görev yazısı ek alan kapalıyken de altyazıya karışmaz.
                    found = ExtraAreas.withoutGta6Objective(found, ExtraAreas.isGta6(runningGame, packGame))
                    val lines = found.map { it.text }
                    val box = union(found)
                    lastLines = lines
                    lastBox = box
                    current.onRead(lines, box)
                } else {
                    log(1, "yazı tanıma başarısız: ${task.exception?.message}")
                }
            } finally {
                reading.set(false)
            }
        }
    }

    private fun readGtaFrame(current: SubtitlePipeline) {
        val now = SystemClock.elapsedRealtime()
        if (now - gtaReadAt < 340L) {
            reading.set(false)
            return
        }
        val app = appContext
        val bitmap = copyFrame(1280)
        if (app == null || bitmap == null) {
            bitmap?.recycle()
            reading.set(false)
            return
        }
        gtaReadAt = now
        val ocr = gtaReader ?: GtaProfileReader(reader).also { gtaReader = it }
        val hud = gtaHud ?: run {
            val translator = GameTranslatorEngine(app, GameReadingProfile.GTA_6)
            gtaTranslator = translator
            HudTranslationSession({ input, done ->
                val requested = if (settings.packsEnabled) translator::translate else translator::translateFallback
                requested(input) { reply -> reader.execute { done(reply) } }
            }, ::publishGtaHud).also { gtaHud = it }
        }
        ocr.read(bitmap) { result ->
            try {
                if (!running || pipeline !== current || gtaReader !== ocr || result == null) return@read
                current.onRead(result.subtitles.map { it.text }, result.captionPosition()?.box, result.allowFallback)
                hud.read(result.hud)
            } finally {
                reading.set(false)
            }
        }
    }

    private fun publishGtaHud(blocks: List<HudBlock>) {
        for (kind in HudKind.entries) {
            val block = blocks.firstOrNull { it.kind == kind }
            val text = if (block == null) "" else (listOfNotNull(block.title) + block.lines.map { it.text }).joinToString("\n")
            val box = block?.box?.let {
                when (kind) {
                    HudKind.HELP -> TextBox(it.right + 0.01, it.top, (it.right + 0.29).coerceAtMost(1.0), it.bottom)
                    HudKind.PHONE -> {
                        val left = if (it.left > 0.34) it.left - 0.33 else it.right.coerceAtMost(0.66)
                        TextBox(left, it.top, left + 0.32, it.bottom)
                    }
                    else -> it
                }
            }
            server.sendRegion("gta-${kind.name.lowercase(java.util.Locale.ROOT)}", text, box)
        }
    }

    private fun resetGtaReading() {
        gtaHud?.reset()
        gtaHud = null
        gtaReader?.close()
        gtaReader = null
        gtaTranslator?.close()
        gtaTranslator = null
        gtaReadAt = 0L
    }

    /** Bölgenin küçültülmüş parlaklık özeti; iki karenin aynı olup olmadığını anlamak için. */
    private fun signature(image: Bitmap): ByteArray {
        val small = Bitmap.createScaledBitmap(image, 32, 8, true)
        val pixels = IntArray(32 * 8)
        small.getPixels(pixels, 0, 32, 0, 0, 32, 8)
        if (small !== image) small.recycle()
        return ByteArray(pixels.size) { i ->
            val p = pixels[i]
            ((((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10).toByte()
        }
    }

    /**
     * Tanınan satırları yukarıdan aşağı sıralar, aynı hizadakileri birleştirir; her satırın tüm ekrandaki yeri (0–1)
     * de verilir (otomatik algılama satırları yerlerine göre süzer).
     */
    internal fun rows(result: Text, cropX: Int, cropY: Int, fullWidth: Int, fullHeight: Int): List<TextLine> {
        data class Piece(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
        val pieces = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val b = line.boundingBox ?: return@mapNotNull null
            if (line.text.isBlank()) null else Piece(line.text.trim(), b.left, b.top, b.right, b.bottom)
        }.sortedBy { it.top }
        if (pieces.isEmpty()) return emptyList()

        val grouped = mutableListOf<MutableList<Piece>>()
        for (piece in pieces) {
            val row = grouped.lastOrNull()
            val reference = row?.first()
            // Dikey ortaları yarım satır yüksekliğinden yakınsa aynı satırdır.
            if (reference != null && abs((piece.top + piece.bottom) / 2 - (reference.top + reference.bottom) / 2) <
                max(reference.bottom - reference.top, piece.bottom - piece.top) / 2) row.add(piece)
            else grouped.add(mutableListOf(piece))
        }
        return grouped.map { row ->
            val sorted = row.sortedBy { it.left }
            TextLine(sorted.joinToString(" ") { it.text }, TextBox(
                (cropX + row.minOf { it.left }).toDouble() / fullWidth, (cropY + row.minOf { it.top }).toDouble() / fullHeight,
                (cropX + row.maxOf { it.right }).toDouble() / fullWidth, (cropY + row.maxOf { it.bottom }).toDouble() / fullHeight))
        }
    }

    /** Satırların hepsini içine alan kutu (yazının ekrandaki yeri); satır yoksa null. */
    private fun union(lines: List<TextLine>): TextBox? {
        val boxes = lines.mapNotNull { it.box }
        if (boxes.isEmpty()) return null
        return TextBox(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    // --- Okuma hızı ölçümü (günlük için; 10 sn'de bir) ---
    private var ocrCount = 0
    private var ocrTotalMs = 0L
    private var ocrMaxMs = 0L
    private var ocrWindowStart = 0L

    /** Okuma iş parçacığından: ML Kit okuma süresi. 10 sn'de bir okuma sayısı ve ortalama süre günlüğe yazılır. */
    private fun noteOcr(ms: Long) {
        val now = SystemClock.elapsedRealtime()
        if (ocrWindowStart == 0L) ocrWindowStart = now
        ocrCount++
        ocrTotalMs += ms
        if (ms > ocrMaxMs) ocrMaxMs = ms
        val window = now - ocrWindowStart
        if (window >= 10_000) {
            log(2, "okuma: ${"%.1f".format(java.util.Locale.ROOT, ocrCount * 1000.0 / window)} okuma/sn, ML Kit ort. ${ocrTotalMs / ocrCount} ms, en çok ${ocrMaxMs} ms")
            ocrCount = 0; ocrTotalMs = 0; ocrMaxMs = 0; ocrWindowStart = now
        }
    }

    // --- Otomatik altyazı algılama (SubtitleFinder) ---
    // Karenin tamamı ayrı bir yazı tanıma istemcisiyle aralıklı okunur (şerit öğrenilene kadar saniyede bir, sonra iki
    // saniyede bir; Windows'taki TryFindSubtitles ile aynı). Öğrenilen şerit, ayarlarda "otomatik" açıkken okunan alan
    // olur; hızlı okuma yalnız o şeritte yapılır. Yalnız otomatik açıkken çalışır.

    // Şerit öğrenildikten sonra tam kare okuması seyrek: her okuma ikinci bir ML Kit işi, zayıf telefonda ana okumayı
    // yavaşlatıyordu. Altyazı şeridi oyun boyunca nadiren yer değiştirir.
    private const val FIND_EVERY_MS = 5000L
    private const val LEARN_EVERY_MS = 1000L
    /** Tam kare okuması bu genişlikten büyükse küçültülür (720p'de altyazı harfleri ML Kit için yeterince büyük kalır). */
    private const val FIND_MAX_WIDTH = 1280
    /** Şerit öğrenilene kadar okunan alan: ekranın alt yarısına yakın, kenarlar hariç. */
    private val AUTO_START = Region(top = 0.55, bottom = 0.98, left = 0.08, right = 0.92)
    /** Okunan alan şeridin bu kadar üstünden ve altından başlar: uzayan altyazının satırları kesilmesin. */
    private const val BAND_MARGIN_TOP = 0.08
    private const val BAND_MARGIN_BOTTOM = 0.10

    private val finder = SubtitleFinder()
    private val findThread = Executors.newSingleThreadExecutor { r -> Thread(r, "gt-finder").apply { isDaemon = true } }
    private var findRecognizer: TextRecognizer? = null
    private val finding = AtomicBoolean(false)
    @Volatile private var lastFind = 0L
    private var findBand: Pair<Double, Double>? = null
    /** Öğrenilen şerit ve ondan çıkan okuma alanı; henüz öğrenilmediyse null. */
    @Volatile private var autoBand: Pair<Double, Double>? = null
    @Volatile private var autoRegion: Region? = null
    /** Okuma alanı değişti: okuma iş parçacığı bir sonraki karede yeniden kullanmayı sıfırlar. */
    private val regionMoved = AtomicBoolean(false)

    /** Okuma iş parçacığından çağrılır; vakti geldiyse karenin tamamını arka planda okur. */
    private fun findSubtitles() {
        val now = SystemClock.elapsedRealtime()
        val every = if (autoRegion == null) LEARN_EVERY_MS else FIND_EVERY_MS
        if (now - lastFind < every || !finding.compareAndSet(false, true)) return
        lastFind = now
        val copy = synchronized(frameLock) {
            val source = frame ?: return@synchronized null
            if (source.width > FIND_MAX_WIDTH) Bitmap.createScaledBitmap(source, FIND_MAX_WIDTH, source.height * FIND_MAX_WIDTH / source.width, true)
            else source.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (copy == null) {
            finding.set(false)
            return
        }
        // Altyazının okuyucusu bekletilmesin: tam kare ayrı bir istemciyle okunur.
        val engine = findRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { findRecognizer = it }
        try {
            engine.process(InputImage.fromBitmap(copy, 0)).addOnCompleteListener(findThread) { task ->
                try {
                    if (task.isSuccessful) learnBand(task.result, copy.width, copy.height, now)
                } catch (error: Throwable) {
                    // Deneme okuması başarısız olursa yalnız o tarama atlanır.
                } finally {
                    copy.recycle()
                    finding.set(false)
                }
            }
        } catch (error: Throwable) {
            copy.recycle()
            finding.set(false)
        }
    }

    /** Tam karenin okumasını sınıflar; şerit belirgin biçimde değiştiyse okunan alanı günceller. */
    private fun learnBand(result: Text, width: Int, height: Int, now: Long) {
        val lines = rows(result, 0, 0, width, height).mapNotNull { line ->
            OcrNoise.clean(line.text).takeIf { it.isNotEmpty() }?.let { line.copy(text = it) }
        }
        finder.classify(lines, now)
        val band = finder.band ?: return
        // Şerit her yeni altyazıyla azıcık oynar; okunan alan yalnız belirgin değişince güncellenir (alan değişince okuma
        // baştan kararlı hale gelmek zorunda kalır).
        val old = findBand
        if (old != null && abs(old.first - band.first) <= 0.035 && abs(old.second - band.second) <= 0.035) return
        findBand = band
        autoBand = band
        autoRegion = Region(top = max(0.0, band.first - BAND_MARGIN_TOP), bottom = min(0.99, band.second + BAND_MARGIN_BOTTOM),
            left = AUTO_START.left, right = AUTO_START.right)
        regionMoved.set(true)
        log(2, "otomatik: altyazı şeridi %${(band.first * 100).toInt()}–%${(band.second * 100).toInt()} (okunan alan güncellendi)")
    }

    // --- Ek alanlar (RegionWatcher) ---
    // Görev yazısı, ipucu kutusu, telefon mesajı gibi alanlar altyazıdan ayrı, kendi iş parçacığında ve kendi yazı tanıma
    // istemcisiyle okunur (Windows: UpdateRegionWatcher). Tur en çok 450 ms'de bir atılır, görüntüsü değişmeyen alan
    // yeniden okunmaz; altyazının okuyucusu ve çevirisi beklemez. Yalnız en az bir alan açıkken çalışır. Çeviriler TV'ye
    // ayrı kutu olarak gider (SubtitleServer.sendRegion); ek alan satırları ortak havuza gönderilmez.

    private val regionThread = Executors.newSingleThreadExecutor { r -> Thread(r, "gt-regions").apply { isDaemon = true } }
    /** Kararlılık beklenirken çevirinin şimdiden istendiği iş parçacığı (okuma turunu bekletmesin). */
    private val regionPrefetch = Executors.newSingleThreadExecutor { r -> Thread(r, "gt-region-translate").apply { isDaemon = true } }
    @Volatile private var regionWatcher: RegionWatcher? = null
    @Volatile private var regionTranslator: TextTranslator? = null
    @Volatile private var regionPackLookup: ((String) -> PackMatch?)? = null
    private var regionRecognizer: TextRecognizer? = null

    private val regionSender = object : RegionSender {
        override fun sendRegion(id: String, text: String, box: TextBox?, lineHeight: Double?) = server.sendRegion(id, text, box, lineHeight)
        override fun clearRegions() = server.clearRegions()
    }

    /** Ek alanın kesiti, son kareden (kare yoksa ya da alan çok küçükse null). */
    private val regionFrame = RegionFrame { region ->
        synchronized(frameLock) {
            val source = frame ?: return@synchronized null
            val left = kotlin.math.floor(source.width * region.left).toInt().coerceIn(0, source.width)
            val top = kotlin.math.floor(source.height * region.top).toInt().coerceIn(0, source.height)
            val right = kotlin.math.ceil(source.width * region.right).toInt().coerceIn(left, source.width)
            val bottom = kotlin.math.ceil(source.height * region.bottom).toInt().coerceIn(top, source.height)
            val w = right - left
            val h = bottom - top
            if (w < 16 || h < 16) return@synchronized null
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, left, top, w, h)
            PixelImage(w, h, pixels)
        }
    }

    /** Ek alanın simge tanıyan okuyucusu (tuş simgelerinin kumandası ayarlanır). */
    private var regionIconReader: IconReader? = null

    /** Denetim iş parçacığında: ek alan okuyucusu gerekiyorsa kurulur ya da güncellenir, gerekmiyorsa kaldırılır. */
    private fun updateRegionWatcher() {
        val translator = regionTranslator
        if (!running || pipeline == null || translator == null || settings.watchedAreas().isEmpty() ||
            settings.gta6Reading && PackTranslation.applies(settings.sourceLanguage, settings.targetLanguage)) {
            regionWatcher?.let {
                it.reset()
                log(2, "ek alanlar kapandı")
            }
            regionWatcher = null
            return
        }
        val watcher = regionWatcher ?: RegionWatcher(IconReader(::recognizeRegion).also { regionIconReader = it }, translator, regionSender,
            RegionWatcher.PHONE_INTERVAL_MS, regionThread, regionPrefetch).also { created ->
            // Okuyucu kumanda simgelerini görüntüden tanır: tuş ipuçları atılmaz, simgesiyle çevrilir ("Hold ✕ to climb").
            created.keepsPrompts = true
            created.log = { text -> log(2, text) }
            // Ek alana düşen altyazı satırı (konuşmacı adlı ya da o an gösterilen altyazıya benzeyen) ek alanda çevrilmez.
            created.skipLine = { text -> ExtraAreas.skipLine(text, lastSource.ifEmpty { null }) }
            log(2, "ek alanlar açık: ${settings.watchedAreas().size}")
        }
        watcher.translator = translator
        // Tuş simgelerinin kumandası: Otomatik'te kaynağa göre (Xbox kaynağı Xbox, PS5 Remote Play PlayStation).
        regionIconReader?.layout?.setting = ButtonLayoutVote.resolve(ButtonLayout.parse(settings.buttonLayout),
            store?.consoleKind == Store.KIND_XBOX)
        watcher.packLookup = regionPackLookup
        watcher.wordCheck = if (settings.sourceLanguage == "en") englishWords?.let { words -> words::isWord } else null
        regionWatcher = watcher
    }

    /** Kare geldikçe çağrılır; tur vakti geldiyse ek alanlar arka planda okunur (hemen döner). */
    private fun offerRegions() {
        val watcher = regionWatcher ?: return
        watcher.offer(regionFrame, settings.watchedAreas())
    }

    /**
     * Ek alanın okuyucusu: kesit ML Kit'in ayrı istemcisiyle okunur (ek alan iş parçacığında, bloklayarak). Satırlar ve
     * sözcükleri kesite oranla (0–1) döner; simge ve renk tanıma bunları kullanır.
     */
    private fun recognizeRegion(image: PixelImage): List<OcrLine> {
        if (image.width < 16 || image.height < 16) return emptyList()
        val engine = regionRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { regionRecognizer = it }
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            val result = Tasks.await(engine.process(InputImage.fromBitmap(bitmap, 0)), 5, TimeUnit.SECONDS)
            val w = image.width.toDouble()
            val h = image.height.toDouble()
            fun box(rect: android.graphics.Rect) = TextBox(rect.left / w, rect.top / h, rect.right / w, rect.bottom / h)
            return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                if (line.text.isBlank()) return@mapNotNull null
                val words = line.elements.mapNotNull { element -> element.boundingBox?.let { OcrWord(element.text, box(it)) } }
                OcrLine(line.text.trim(), line.boundingBox?.let { box(it) }, words)
            }.sortedBy { it.box?.top ?: 0.0 }
        } finally {
            bitmap.recycle()
        }
    }

    // --- Arayüz için yardımcılar ---

    private var lastDeliveredFrame = 0L

    private fun broadcastFrame() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDeliveredFrame < 900L || !ConsoleSession.active) return
        lastDeliveredFrame = now
        val copy = copyFrame(1440) ?: return
        main.post { ConsoleSession.frame(copy, now) }
    }

    private fun copyFrame(maxWidth: Int): Bitmap? = synchronized(frameLock) {
        val source = frame ?: return null
        val width = maxWidth.coerceIn(2, source.width)
        if (width == source.width) source.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createScaledBitmap(source, width, maxOf(2, source.height * width / source.width), true)
    }

    /** Son karenin JPEG görüntüsü. */
    fun snapshot(maxWidth: Int): ByteArray? {
        val copy = copyFrame(maxWidth) ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                copy.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        } finally {
            copy.recycle()
        }
    }

    private fun loadServerAssets(context: Context) {
        if (server.overlayPage == null) server.overlayPage = runCatching { context.assets.open("obs-overlay.html").use { it.readBytes() } }.getOrNull()
        if (server.screenPage == null) server.screenPage = runCatching { context.assets.open("second-screen.html").use { it.readBytes() } }.getOrNull()
        if (server.fontFile == null) server.fontFile = runCatching { context.assets.open("Quicksand-Bold.ttf").use { it.readBytes() } }.getOrNull()
    }

    fun sendTestSubtitle(context: Context) {
        loadServerAssets(context)
        if (server.boundPort == 0) server.start()
        // Deneme sırasında da "çeviri yapan" sayılır: TV bilgisayardaki uygulamaya bağlıysa telefona geçsin.
        server.active = true
        server.sendStyle(settings.style)
        val r = settings.region
        UiText.use(settings.uiLanguage)
        showSubtitle(UiText.t("test.subtitle"), 8000, TextBox(0.3, r.bottom - 0.1, 0.7, r.bottom - 0.03), false)
    }

    /** Telefonun yerel ağdaki IPv4 adresi (Wi-Fi öncelikli). */
    fun localAddress(): String? {
        var fallback: String? = null
        try {
            for (network in NetworkInterface.getNetworkInterfaces()) {
                if (!network.isUp || network.isLoopback) continue
                for (address in network.inetAddresses) {
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    if (network.name.startsWith("wlan")) return address.hostAddress
                    if (fallback == null) fallback = address.hostAddress
                }
            }
        } catch (error: Exception) {
        }
        return fallback
    }

    /** Aramada sorulacak adresler: yayın adresleri ve /24 ağlardaki tüm adresler (bazı ağlar yayını geçirmez). */
    private fun discoveryTargets(extra: List<String>): Array<String> {
        val targets = linkedSetOf("255.255.255.255")
        targets.addAll(extra.filter { it.isNotBlank() })
        try {
            for (network in NetworkInterface.getNetworkInterfaces()) {
                if (!network.isUp || network.isLoopback) continue
                for (address in network.interfaceAddresses) {
                    val ip = address.address as? Inet4Address ?: continue
                    address.broadcast?.hostAddress?.let { targets.add(it) }
                    if (address.networkPrefixLength >= 24) {
                        val b = ip.address
                        val prefix = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
                        for (i in 1..254) targets.add(prefix + i)
                    }
                }
            }
        } catch (error: Exception) {
        }
        return targets.toTypedArray()
    }

    /** Ağdaki PS5'leri arar (bloklar). [extra]: elle girilen adresler. */
    fun discover(extra: List<String>, timeoutMs: Int): List<Map<String, Any?>> {
        if (!Native.available) return emptyList()
        val found = LinkedHashMap<String, Map<String, Any?>>()
        Native.discover(discoveryTargets(extra), timeoutMs, object : Native.ConsoleCallback {
            override fun onConsole(host: String, name: ByteArray, awake: Boolean) {
                synchronized(found) { found[host] = mapOf("host" to host, "name" to String(name, Charsets.UTF_8), "awake" to awake) }
            }
        })
        return synchronized(found) { found.values.toList() }
    }

    /** PS5'in "Cihaz Eşleştir" koduyla kayıt (bloklar, en çok ~30 sn). Başarılıysa kaydı saklar. */
    /**
     * Telefon PS5'e ağdan hiç ulaşamıyor mu: TCP bağlantısı "No route to host" (EHOSTUNREACH) ile hemen düşerse PS5'in ağ
     * adresi çözülemiyor demektir (aynı Wi-Fi'da ama farklı bantta, ya da modemde istemciler arası yalıtım). PS5'in
     * kapalı portu "refused", uyuyan PS5 zaman aşımı verir; bunlar ulaşılabilir sayılır.
     */
    private fun unreachable(host: String): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 9295), 2500) }
        false
    } catch (error: java.io.IOException) {
        val text = (error.message ?: "") + " " + (error.cause?.message ?: "")
        log(1, "PS5'e ağ denemesi: ${error.javaClass.simpleName} $text")
        text.contains("EHOSTUNREACH") || text.contains("No route to host", ignoreCase = true)
    }

    fun register(context: Context, host: String, pin: Int): Map<String, Any?> {
        val s = Store(context.applicationContext)
        val account = s.accountId ?: return mapOf("ok" to false, "detail" to UiText.code("need_psn"))
        if (!Native.available) return mapOf("ok" to false, "detail" to UiText.code("native_missing"))
        val out = ByteArray(Native.REGISTERED_SIZE)
        val reason = IntArray(1)
        val errors = mutableListOf<String>()
        log(2, "PS5 kaydı başlıyor: $host")
        val result = Native.register(host, account, pin, out, reason, object : Native.LogCallback {
            override fun onLog(level: Int, message: String?) {
                if (message == null) return
                log(level, "chiaki: $message")
                if (level == 0) synchronized(errors) { errors.add(message) }
            }
        })
        if (result != 0) {
            val code = reason[0]
            val detail = when {
                code == 0x80108b02.toInt() -> UiText.code("reg_unknown_account")
                code == 0x80108b09.toInt() || code == 0x80108b12.toInt() -> UiText.code("reg_bad_code")
                result == 2 -> UiText.code("cancelled")
                // PS5 "Cihaz Eşleştir" ekranında değilken kayıt aramasını reddeder ya da hiç yanıtlamaz. Telefon PS5'i ağda
                // hiç göremiyorsa (Wi-Fi bantları ya da istemciler arası yalıtım) sorun eşleştirme ekranı değil ağdır.
                synchronized(errors) { errors.any { it.contains("search", ignoreCase = true) } } ->
                    if (unreachable(host)) UiText.code("reg_unreachable") else UiText.code("reg_not_ready")
                else -> synchronized(errors) { errors.firstOrNull() } ?: UiText.code("reg_no_reply")
            }
            log(0, "PS5 kaydı başarısız: $detail")
            return mapOf("ok" to false, "reason" to code, "detail" to detail)
        }
        val nameEnd = (0 until 32).firstOrNull { out[it] == 0.toByte() } ?: 32
        val name = String(out, 0, nameEnd, Charsets.UTF_8).ifBlank { "PS5" }
        s.console = Console(host, name, out.copyOfRange(32, 48), out.copyOfRange(48, 64))
        log(2, "PS5 kaydı tamam: $name")
        return mapOf("ok" to true, "name" to name, "host" to host)
    }
}
