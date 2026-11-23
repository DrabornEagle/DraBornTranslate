// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore

private const val BACKGROUND = 0xFF0C0F22.toInt()
private const val PANEL = 0xFF161A34.toInt()
private const val OUTLINE = 0xFF343B63.toInt()
private const val WHITE = 0xFFF5F7FF.toInt()
private const val MUTED = 0xFFB8C0D9.toInt()
private const val ACCENT = 0xFF79E5FF.toInt()
private const val LAVENDER = 0xFFB6A8FF.toInt()
private const val MINT = 0xFF73E5BE.toInt()
private const val PEACH = 0xFFFFC298.toInt()
private const val ROSE = 0xFFFFA5BB.toInt()
private const val PORTAL_START = "Portal Bağlantısını Başlat"
private const val PORTAL_STOP = "Portal Bağlantısını Durdur"

class MainActivity : Activity() {
    private enum class EntryMode { HOME, PS5_TV, PORTAL }
    private var entryMode = EntryMode.HOME
    private val prefs by lazy { getSharedPreferences("capture", MODE_PRIVATE) }
    private lateinit var config: CaptureSettings
    private var pending = false
    private var demoMessage: String? = null
    private var demoCaption: Caption? = null
    private var demoSource: String? = null
    private var demoTranslator: CaptionTranslator? = null
    private var demoReader: SubtitleReader? = null
    private var voicePreview: TurkishVoice? = null
    private var packLoading = false
    private var packDownloadPercent = 0
    private var tunnelAddress = ""
    private var tunnelSession: String? = null
    private val popups = mutableSetOf<Dialog>()
    private var aboutDialog: Dialog? = null
    private var renderAboutUpdate: ((UpdateState) -> Unit)? = null
    private var updateResultPending = false

    private lateinit var portal: Button
    private lateinit var phone: Button
    private lateinit var instructions: TextView
    private lateinit var openSource: Button
    private lateinit var startStop: Button
    private lateinit var displayChoice: Button
    private lateinit var readingChoice: Button
    private lateinit var packButton: Button
    private lateinit var panelCard: LinearLayout
    private lateinit var panelStatus: TextView
    private lateinit var copyPanel: Button
    private lateinit var testPanel: Button
    private lateinit var launchPanelTest: Button
    private lateinit var previewPanel: Button
    private lateinit var tunnelWizard: Button
    private lateinit var status: TextView
    private lateinit var captionLabel: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var recognizedLabel: TextView
    private lateinit var copySource: Button
    private lateinit var copyTurkish: Button
    private lateinit var bandPreview: BandPreview
    private lateinit var bandSettings: LinearLayout
    private lateinit var bandControl: SeekBar
    private lateinit var sizeControl: SeekBar
    private lateinit var testButton: Button
    private lateinit var voiceButton: Button

    private val observe: (SessionState.State) -> Unit = { if (entryMode == EntryMode.PORTAL) render() }
    private val observeUpdate: (UpdateState) -> Unit = { state ->
        renderAboutUpdate?.invoke(state)
        if (updateResultPending && state != UpdateState.Checking) {
            updateResultPending = false
            if (state == UpdateState.Current && aboutDialog?.isShowing == true) showCurrentVersion()
        }
        AppUpdater.continueInstall(this)
    }
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        removeOldTranslationCredentials()
        config = CaptureSettings(
            prefs.getInt("band", CaptureSettings.DEFAULT_BAND_TOP).coerceIn(0, CaptureSettings.MAX_TOP),
            prefs.getInt("size", 22).coerceIn(CaptureSettings.MIN_SIZE, CaptureSettings.MAX_SIZE),
            if (prefs.getBoolean("mobile", false)) Source.MOBILE else Source.PORTAL,
            SubtitleDisplay.entries.firstOrNull { it.name == prefs.getString("display", null) }
                ?: SubtitleDisplay.PHONE,
            ReadingArea.entries.firstOrNull { it.name == prefs.getString("readingArea", null) }
                ?: ReadingArea.SUBTITLES,
        )
        pending = savedInstanceState?.getBoolean("pending") ?: false
        tunnelAddress = savedInstanceState?.getString("tunnelAddress").orEmpty()
        tunnelSession = savedInstanceState?.getString("tunnelSession")
        updateResultPending = savedInstanceState?.getBoolean("updateResultPending") ?: false
        entryMode = savedInstanceState?.getString("entryMode")?.let { saved ->
            EntryMode.entries.firstOrNull { it.name == saved }
        } ?: EntryMode.HOME
        navigate(entryMode)
        if (savedInstanceState?.getBoolean("aboutOpen") == true) showAbout()
    }

    private fun removeOldTranslationCredentials() {
        if (prefs.getBoolean("translation032Migrated", false)) return
        deleteSharedPreferences("dialogue-credentials")
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("DraBornTranslate.Gemini")
        }
        prefs.edit().putBoolean("translation032Migrated", true).apply()
    }

    override fun onStart() {
        super.onStart()
        SessionState.observe(observe)
    }

    override fun onStop() {
        SessionState.remove(observe)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.observe(observeUpdate)
    }

    override fun onPause() {
        AppUpdater.remove(observeUpdate)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("entryMode", entryMode.name)
        outState.putBoolean("pending", pending)
        outState.putString("tunnelAddress", tunnelAddress)
        outState.putString("tunnelSession", tunnelSession)
        outState.putBoolean("aboutOpen", aboutDialog?.isShowing == true)
        outState.putBoolean("updateResultPending", updateResultPending)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        popups.toList().forEach(Dialog::dismiss)
        finishDemo()
        voicePreview?.close()
        super.onDestroy()
    }


    @Deprecated("Back navigation uses the Activity callback for Android 10 compatibility")
    override fun onBackPressed() {
        if (entryMode == EntryMode.HOME) super.onBackPressed()
        else navigate(EntryMode.HOME)
    }

    private fun navigate(mode: EntryMode) {
        entryMode = mode
        when (mode) {
            EntryMode.HOME -> buildLandingUi()
            EntryMode.PS5_TV -> buildPs5TvUi()
            EntryMode.PORTAL -> {
                buildUi()
                render()
            }
        }
    }

    /** A common portrait-safe shell shared by the two entry points. */
    private fun newPage(): LinearLayout {
        val viewport = FrameLayout(this).apply {
            background = gradient(GradientDrawable.Orientation.TOP_BOTTOM,
                0xFF24164D.toInt(), BACKGROUND, 0)
        }
        viewport.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            insets
        }
        setContentView(viewport)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        viewport.addView(scroll)
        return column().apply {
            setPadding(dp(17), dp(24), dp(17), dp(28))
            layoutParams = FrameLayout.LayoutParams(
                minOf(resources.displayMetrics.widthPixels, dp(610)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            scroll.addView(this)
        }
    }

    private fun brandHeader(content: LinearLayout, subtitle: String) {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(GradientDrawable.Orientation.TL_BR,
                0xFF43246D.toInt(), 0xFF18375B.toInt(), 23)
                .apply { setStroke(dp(1), 0xFF7464B8.toInt()) }
            setPadding(dp(15), dp(16), dp(15), dp(16))
        }
        hero.addView(ImageView(this).apply {
            setImageResource(R.drawable.brand_mark)
            contentDescription = "DraBornTranslate logosu"
        }, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(12) })
        hero.addView(column().apply {
            addView(label("DraBornTranslate", 22, WHITE, true))
            append(label(subtitle, 13, MUTED), 4)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val more = button("ⓘ") { showAbout() }.apply {
            contentDescription = "Hakkında ve sürüm"
            textSize = 22f
            minWidth = dp(48)
        }
        hero.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.append(hero, 0)
    }

    private fun buildLandingUi() {
        val content = newPage()
        brandHeader(content, "Türkçe oyun deneyiminin merkezi")
        content.append(label("Oynamak istediğin cihazı seç", 24, WHITE, true).apply {
            isAccessibilityHeading = true
        }, 30)
        content.append(label("PS5 ve TV bağlantısı veya mevcut PS Portal çeviri araçlarına tek dokunuşla geç.", 15, MUTED), 9)

        val psCard = card(true).apply {
            background = gradient(GradientDrawable.Orientation.TL_BR,
                0xFF17446D.toInt(), 0xFF263164.toInt(), 24)
                .apply { setStroke(dp(2), 0xFF3CBEFF.toInt()) }
            isClickable = true
            setOnClickListener { navigate(EntryMode.PS5_TV) }
        }
        psCard.addView(label("🎮  PS5 ve TV'ye Bağlan", 21, WHITE, true))
        psCard.append(label("Dil ayarları • PSN kurulum ekranı • TV bağlantı adımları", 14, MUTED), 11)
        psCard.append(button("PS5 / TV kurulumunu aç") { navigate(EntryMode.PS5_TV) }.apply {
            styleButton(this, ACCENT to LAVENDER)
        }, 16)
        content.append(psCard, 22)

        val portalCard = card(true).apply {
            background = gradient(GradientDrawable.Orientation.TL_BR,
                0xFF462A6D.toInt(), 0xFF1B4562.toInt(), 24)
                .apply { setStroke(dp(2), 0xFFB6A8FF.toInt()) }
            isClickable = true
            setOnClickListener { navigate(EntryMode.PORTAL) }
        }
        portalCard.addView(label("🕹  PS Portal'a Bağlan", 21, WHITE, true))
        portalCard.append(label("Eski DraBornTranslate • Canlı altyazı • Lens • GTA 6 oyun paketi", 14, MUTED), 11)
        portalCard.append(button("Portal çevirisini aç") { navigate(EntryMode.PORTAL) }.apply {
            styleButton(this, LAVENDER to ACCENT)
        }, 16)
        content.append(portalCard, 14)

        val gta = card(true).apply {
            background = gradient(GradientDrawable.Orientation.TL_BR, 0xFF193D42.toInt(), 0xFF202950.toInt(), 20)
                .apply { setStroke(dp(1), 0xFF59D7B4.toInt()) }
        }
        gta.addView(label("⚡ GTA 6 • Hazır Türkçe paket", 19, WHITE, true))
        val installed = Gta6PackInstaller.installedInfo(this)
        gta.append(label(when {
            packLoading -> "GTA 6 paketi indiriliyor · %$packDownloadPercent"
            installed != null -> "Yüklü ve doğrulanmış: " + installed.entries + " Türkçe satır"
            else -> "Sunucudaki GTA 6 Türkçe paketi • yaklaşık 475 bin satır. İlk indirme internet gerektirir."
        }, 14, MUTED), 8)
        gta.append(label("Eşleşen İngilizce satır doğrudan paketten gelir. Bulunmayan satırlarda yerel çeviri devreye girer.", 13, MINT), 8)
        gta.append(button(if (packLoading) "Paket yükleniyor…" else if (installed == null) "GTA 6 paketini indir" else "GTA 6 paketini kontrol et / güncelle") {
            AlertDialog.Builder(this)
                .setTitle("GTA 6 Türkçe oyun paketi")
                .setMessage("Paket yaklaşık 25 MB indirir ve telefonda yer kaplar. Dosya SHA-256 ve satır sayısıyla doğrulanır. Devam edilsin mi?")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("İndir / Güncelle") { _, _ ->
                    installPack { app ->
                        val result = Gta6PackInstaller.install(app, ::updateGta6DownloadProgress)
                        "GTA 6 · " + result.entries + " Türkçe satır"
                    }
                }.show()
        }.apply { isEnabled = !packLoading; styleButton(this, MINT to ACCENT) }, 14)
        gta.append(button("Dosyadan GTA 6 paketi yükle (.json.gz)") {
            pickGamePack()
        }, 9)
        gta.append(button("İndirme hatası mı? Paketi tarayıcıda aç") {
            openLink("https://gametranslator-packs.gametranslator-pool.workers.dev/v1/packs/gta-6")
        }, 9)
        content.append(gta, 19)

        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val versionCard = card()
        versionCard.addView(label("DraBornTranslate  v" + version, 17, WHITE, true))
        versionCard.append(label("Resmî güncellemeler: github.com/DrabornEagle/DraBornTranslate", 12, MUTED), 8)
        versionCard.append(button("🔄  GitHub'da yeni sürüm kontrolü") {
            showAbout()
            updateResultPending = true
            AppUpdater.check(this)
        }.apply { styleButton(this, MINT to ACCENT) }, 14)
        content.append(versionCard, 22)
    }

    private fun choosePsLanguage(
        heading: String, key: String, choices: Array<String>
    ) {
        showPopup(heading, R.drawable.ic_portal_check) { dialog ->
            append(label("Şimdilik uygulamanın çeviri motoru İngilizce → Türkçe dil çiftini destekler.", 13, MUTED), 12)
            choices.forEach { choice ->
                append(button(choice) {
                    prefs.edit().putString(key, choice).apply()
                    dialog.dismiss()
                    buildPs5TvUi()
                }, 7)
            }
        }
    }

    private fun buildPs5TvUi() {
        val content = newPage()
        brandHeader(content, "PS5 / TV · Bağlantı kurulumu")
        content.append(button("←  Ana sayfa") { navigate(EntryMode.HOME) }, 14)
        content.append(label("Kurulum", 32, WHITE, true), 24)
        content.append(label("Önce PlayStation hesabını bağla.", 16, MUTED), 5)

        val language = card()
        language.addView(label("Dil tercihleri", 18, WHITE, true))
        val appLanguage = prefs.getString("setupAppLanguage", "Otomatik · Türkçe") ?: "Otomatik · Türkçe"
        val translated = prefs.getString("setupTranslated", "Türkçe") ?: "Türkçe"
        val game = prefs.getString("setupGame", "English · İngilizce") ?: "English · İngilizce"
        language.append(button("Aa   Uygulama dili     " + appLanguage) {
            choosePsLanguage("Uygulama dili", "setupAppLanguage",
                arrayOf("Otomatik · Türkçe", "Türkçe"))
        }, 11)
        language.append(button("🌐   Çeviri dili     " + translated) {
            choosePsLanguage("Çeviri dili", "setupTranslated",
                arrayOf("Türkçe"))
        }, 8)
        language.append(button("🎮   Oyunun dili     " + game) {
            choosePsLanguage("Oyunun dili", "setupGame",
                arrayOf("English · İngilizce"))
        }, 8)
        content.append(language, 22)

        content.append(label("Konsolun", 18, WHITE, true), 23)
        val ps5 = card(true).apply {
            background = shape(0xFF092B50.toInt(), 0xFF3FAFFF.toInt(), 18)
        }
        ps5.addView(label("PlayStation 5   ✓", 20, WHITE, true))
        ps5.append(label("Remote Play / ikinci PSN hesabı için kurulum adımları.", 14, MUTED), 6)
        content.append(ps5, 10)
        content.append(label("Adım 1 / 2   •   PSN hesabı", 15, ACCENT, true), 24)

        val account = card(true)
        account.addView(label("👤   PSN ile giriş yap", 20, WHITE, true))
        account.append(label("PS5'te ikinci kullanıcının (yan hesabın) PSN hesabıyla giriş yap. Böylece kendi hesabınla oynarken kumandan TV'den kopmaz.", 15, MUTED), 13)
        account.append(label("🔒 Sony parolan hiçbir zaman DraBornTranslate'e girilmez.", 13, ACCENT), 14)
        account.append(button("Sony PSN hesap sayfasını aç") {
            showPopup("PlayStation hesabı", R.drawable.ic_portal_check, ACCENT) { dialog ->
                append(label("Bu bağlantı Sony'nin resmî hesap sayfasını açar. Hesabını görüntülemek, uygulamanın PS5 ile eşleştiği anlamına gelmez.", 15, WHITE), 14)
                append(label("Doğrudan PSN eşleştirme ve Chiaki bağlantı motoru bu Android sürümünde henüz entegre edilmemiştir.", 14, MUTED), 10)
                append(button("Sony hesabını aç") {
                    dialog.dismiss()
                    openLink("https://www.playstation.com/acct/management/")
                }, 14)
            }
        }.apply { styleButton(this, ACCENT to LAVENDER) }, 17)
        content.append(account, 18)

        content.append(label("Adım 2 / 2   •   TV ve oyun görüntüsü", 15, LAVENDER, true), 23)
        val tv = card()
        tv.addView(label("📺   TV'ye bağlantı", 19, WHITE, true))
        tv.append(label("PS5 ve TV aynı oturumdayken bağlantı adımlarını tamamla. Bu ekrandaki TV eşleştirmesi henüz etkin değil; mevcut Portal canlı çevirisi ayrı modda çalışır.", 14, MUTED), 11)
        tv.append(button("PS Portal çevirisine geç") { navigate(EntryMode.PORTAL) }, 14)
        content.append(tv, 11)
    }

    private fun buildUi() {
        val viewport = FrameLayout(this).apply {
            background = gradient(GradientDrawable.Orientation.TOP_BOTTOM, 0xFF1B1438.toInt(), BACKGROUND, 0)
        }
        viewport.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            insets
        }
        setContentView(viewport)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        viewport.addView(scroll)
        val content = column().apply {
            setPadding(dp(16), dp(16), dp(16), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                minOf(resources.displayMetrics.widthPixels, dp(610)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            )
        }
        scroll.addView(content)
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(17), dp(16), dp(17))
            background = gradient(GradientDrawable.Orientation.TL_BR,
                0xFF392269.toInt(), 0xFF192643.toInt(), 24).apply { setStroke(dp(1), 0xFF56528F.toInt()) }
        }
        hero.addView(ImageView(this).apply {
            setImageResource(R.drawable.brand_mark)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(12) })
        hero.addView(column().apply {
            addView(label("DraBornTranslate", 23, WHITE, bold = true).apply { isAccessibilityHeading = true })
            append(label("Oyunlar için canlı Türkçe", 14, MUTED), 3)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(ImageButton(this).apply {
            val background = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
            setBackgroundResource(background.resourceId)
            setImageResource(R.drawable.ic_more_vert)
            setColorFilter(LAVENDER)
            contentDescription = "Hakkında"
            tooltipText = contentDescription
            setOnClickListener { showAbout() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.append(hero, 0)
        content.append(button("←  Ana sayfa") { navigate(EntryMode.HOME) }, 13)

        content.append(label("Oyun kaynağı", 15, LAVENDER, bold = true), 18)
        val inputs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        portal = button("PS Portal") { select(Source.PORTAL) }
        phone = button("Telefonda oyna") { select(Source.MOBILE) }
        inputs.addView(portal, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(5) })
        inputs.addView(phone, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(5) })
        content.append(inputs, 8)

        val capture = card()
        content.append(capture, 14)
        capture.addView(sectionTitle("Canlı çeviri"))
        displayChoice = button("") { selectDisplay() }
        capture.append(displayChoice, 10)
        readingChoice = button("") { selectReadingArea() }
        capture.append(readingChoice, 6)

        startStop = button("Canlı çeviriyi başlat") { beginOrStop() }
        styleButton(startStop, ACCENT to LAVENDER)
        capture.append(startStop, 10)
        openSource = button("") { openSourceApp(config.source, offerInstall = true) }
        capture.append(openSource, 6)

        val live = card(highlight = true)
        content.append(live, 14)
        live.addView(sectionTitle("Son çeviri"))
        status = label("", 14, ACCENT, bold = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        live.append(status, 7)
        captionLabel = label("", 22, WHITE, bold = true).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        live.append(captionLabel, 10)
        sourceLabel = label("", 14, MUTED).apply { setLineSpacing(dp(2).toFloat(), 1f) }
        live.append(sourceLabel, 8)
        recognizedLabel = label("", 14, LAVENDER).apply { setLineSpacing(dp(2).toFloat(), 1f) }
        live.append(recognizedLabel, 8)
        copyTurkish = button("Türkçeyi kopyala") {
            displayedCaption()?.let { copyText("Türkçe çeviri", it.turkish) }
        }
        live.append(copyTurkish, 8)
        copySource = button("İngilizceyi kopyala") {
            recognizedText()?.let { copyText("Okunan İngilizce", it) }
        }
        live.append(copySource, 6)

        panelCard = card()
        content.append(panelCard, 14)
        panelCard.addView(sectionTitle("Portal yan paneli"))
        panelStatus = label("", 14, MUTED)
        panelCard.append(panelStatus, 6)
        copyPanel = button("Panel bağlantısını kopyala") { copyPanelLink() }
        panelCard.append(copyPanel, 10)
        testPanel = button("Yerel bağlantıyı telefonda aç") {
            SessionState.current.portalPanel?.url?.let(::openLink)
        }
        panelCard.append(testPanel, 6)
        tunnelWizard = button("PS5 uzakta · Bağlantıyı hazırla") { showTunnelWizard() }
        styleButton(tunnelWizard, LAVENDER to PEACH)
        actionIcon(tunnelWizard, R.drawable.ic_portal_link)
        panelCard.append(tunnelWizard, 6)
        previewPanel = button("Portal’da örnek yazı göster") { CaptureService.togglePreview(this) }
        panelCard.append(previewPanel, 6)
        launchPanelTest = button(PORTAL_START) { togglePanelTest() }
        styleButton(launchPanelTest, MINT to ACCENT, activated = PEACH to ROSE)
        actionIcon(launchPanelTest, R.drawable.ic_portal_play, activeIcon = R.drawable.ic_portal_stop)
        panelCard.append(launchPanelTest, 6)
        panelCard.append(button("Portal’da paneli açma adımları") { showPortalHelp() }, 6)

        val guide = content.addDetails("Bağlantı ve ekran paylaşımı")
        instructions = label("", 15, MUTED).apply { setLineSpacing(dp(4).toFloat(), 1f) }
        guide.append(instructions, 10)

        val settings = content.addDetails("Görünüm ve oyun paketi")
        bandSettings = column()
        settings.append(bandSettings, 12)
        bandSettings.addView(label("Altyazı hizası", 16, WHITE, bold = true))
        bandSettings.append(label("Mavi bandı oyundaki İngilizce altyazının hizasına getir.", 14, MUTED), 4)
        bandPreview = BandPreview()
        bandSettings.addView(bandPreview, LinearLayout.LayoutParams(-1, dp(84)).apply { topMargin = dp(8) })
        bandControl = slider("Altyazı bölgesinin yüksekliği", CaptureSettings.MAX_TOP, config.bandTop) { value ->
            config = config.copy(bandTop = value)
            prefs.edit().putInt("band", value).apply()
            bandPreview.invalidate()
            bandPreview.contentDescription = "Oyun görüntüsünün yüzde ${value} hizasındaki okuma bandı"
        }
        bandSettings.addView(bandControl)
        val sizeLabel = label("Altyazı boyutu · ${config.textSize}", 15, WHITE)
        settings.append(sizeLabel, 8)
        sizeControl = slider("Altyazı yazı boyutu", CaptureSettings.MAX_SIZE - CaptureSettings.MIN_SIZE,
            config.textSize - CaptureSettings.MIN_SIZE) { value ->
            val size = value + CaptureSettings.MIN_SIZE
            config = config.copy(textSize = size)
            prefs.edit().putInt("size", size).apply()
            sizeLabel.text = "Altyazı boyutu · $size"
        }
        settings.addView(sizeControl)
        packButton = button("") { showPackMenu() }
        settings.append(packButton, 8)
        settings.append(button("Açık kaynak lisansı") { showLegalNotices() }.apply {
            background = RippleDrawable(ColorStateList.valueOf(0x22B6A8FF), null, shape(WHITE, WHITE, 12))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(LAVENDER)
            textSize = 13f
        }, 4)
        testButton = button("Çeviriyi dene") { showTranslationTest() }
        content.append(testButton, 12)
        voiceButton = button("Türkçe sesi dene") { previewVoice() }
        content.append(voiceButton, 6)
    }

    private fun render() {
        val state = SessionState.current
        val mobile = config.source == Source.MOBILE
        val textInstructions = when (config.readingArea) {
            ReadingArea.LENS -> "Lens modu yazıların üzerine Türkçe katman yerleştirir. Oyun görüntüsü telefon ekranında açık olmalı."
            ReadingArea.GAME_TEXT -> "Çevirmek istediğin menüyü veya görevi açıp metni birkaç saniye ekranda tut."
            ReadingArea.SUBTITLES -> "Oyunun İngilizce altyazısını aç."
        }
        instructions.text = if (mobile) {
            "1. PS Remote Play ile telefondan konsola bağlan.\n" +
                "2. Yatay tam ekrana geç. $textInstructions\n" +
                "3. Buraya dön, altyazıyı başlat ve Android paylaşımında PS Remote Play’i seç."
        } else {
            "1. Portal’da PS düğmesi → Game Base → Paylaşımlı Ekran’ı Başlat.\n" +
                "2. Telefonda PlayStation App’te ikinci hesabınla yayın önizlemesine dokun ve yatay tam ekrana geç.\n" +
                "3. DraBornTranslate’te başlat ve Android paylaşımında Tek uygulama → PlayStation App’i seç. Yayını önde tut.\n" +
                "4. $textInstructions"
        }
        openSource.text = "${config.source.appName} uygulamasını aç"
        portal.isSelected = !mobile
        phone.isSelected = mobile
        val panelTesting = state.portalPanel?.testing == true
        val controlsAvailable = !state.active && !pending && !panelTesting && !packLoading
        listOf(portal, phone, bandControl, sizeControl).forEach { it.isEnabled = controlsAvailable }
        displayChoice.visibility = if (mobile) View.GONE else View.VISIBLE
        displayChoice.isEnabled = controlsAvailable
        displayChoice.text = "Çeviri çıkışı · ${config.display.label}"
        readingChoice.isEnabled = controlsAvailable
        readingChoice.text = "Çevrilecek alan · ${config.readingArea.label}"
        packButton.isEnabled = controlsAvailable && demoTranslator == null
        packButton.text = when {
            packLoading -> "GTA 6 paketi indiriliyor · %$packDownloadPercent"
            ImportedGamePack.hasPack(this) -> "Oyun paketi · ${prefs.getString("packTitle", "Yüklü")}"
            Gta6PackInstaller.isInstalled(this) -> "GTA 6 · " +
                (Gta6PackInstaller.installedInfo(this)?.entries?.toString() ?: "Yüklü") + " satır"
            else -> "Oyun paketi ekle"
        }
        bandSettings.visibility = if (config.readingArea == ReadingArea.SUBTITLES) View.VISIBLE else View.GONE
        voiceButton.visibility = if (config.speakOnPhone) View.VISIBLE else View.GONE
        voiceButton.isEnabled = controlsAvailable
        startStop.isEnabled = !pending && demoTranslator == null && !packLoading
        startStop.text = if (state.active) "Canlı çeviriyi durdur" else "Canlı çeviriyi başlat"
        testButton.isEnabled = controlsAvailable && demoTranslator == null
        renderPortalPanel(state)

        val caption = displayedCaption()
        val recognized = recognizedText()
        val source = caption?.source
        val differentReading = recognized?.takeUnless { it == source }
        status.text = if (state.active || panelTesting) state.voiceIssue ?: state.translationIssue ?: state.status
        else demoMessage ?: state.status
        captionLabel.text = caption?.turkish.orEmpty()
        sourceLabel.text = source?.let { "Çevirinin İngilizce metni\n$it" }.orEmpty()
        recognizedLabel.text = differentReading?.let { "Okunan İngilizce\n$it" }.orEmpty()
        captionLabel.visibility = if (caption == null) View.GONE else View.VISIBLE
        copyTurkish.visibility = captionLabel.visibility
        sourceLabel.visibility = if (source == null) View.GONE else View.VISIBLE
        recognizedLabel.visibility = if (differentReading == null) View.GONE else View.VISIBLE
        copySource.visibility = if (recognized == null) View.GONE else View.VISIBLE
    }

    private fun displayedCaption(): Caption? =
        if (demoMessage != null) demoCaption else SessionState.current.let { if (it.active) it.caption else it.lastCaption }

    private fun recognizedText(): String? =
        if (demoMessage != null) demoSource else displayedCaption()?.source

    private fun select(value: Source) {
        config = config.copy(source = value)
        prefs.edit().putBoolean("mobile", value == Source.MOBILE).apply()
        render()
    }

    private fun selectDisplay() {
        val choices = SubtitleDisplay.entries
        AlertDialog.Builder(this)
            .setTitle("Çeviri çıkışı")
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOf(config.display)) { dialog, index ->
                setDisplay(choices[index])
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun setDisplay(display: SubtitleDisplay) {
        config = config.copy(display = display)
        prefs.edit().putString("display", display.name).apply()
    }

    private fun selectReadingArea() {
        val choices = ReadingArea.entries
        AlertDialog.Builder(this)
            .setTitle("Çevrilecek alan")
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOf(config.readingArea)) { dialog, index ->
                config = config.copy(readingArea = choices[index])
                prefs.edit().putString("readingArea", config.readingArea.name).apply()
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun previewVoice() {
        voicePreview?.close()
        voicePreview = TurkishVoice(this) { issue ->
            Toast.makeText(this, issue, Toast.LENGTH_LONG).show()
        }.also { it.speak("Bu bir Türkçe altyazı ses testidir.") }
    }

    private fun showPackMenu() {
        val current = ImportedGamePack.hasPack(this)
        val appPacks = ImportedGamePack.installedAppPacks(this)
        val actions = linkedMapOf<String, () -> Unit>()
        actions["🎮 GTA 6 Türkçe paketini indir / güncelle"] = {
            installPack { app ->
                val result = Gta6PackInstaller.install(app, ::updateGta6DownloadProgress)
                "GTA 6 · " + result.entries + " Türkçe satır"
            }
        }
        if (Gta6PackInstaller.isInstalled(this)) {
            actions["GTA 6 paketini kaldır"] = {
                Gta6PackInstaller.remove(this)
                prefs.edit().remove("packTitle").apply()
                render()
            }
        }
        if (appPacks.isNotEmpty()) {
            actions["Yüklü GameTranslator’dan al"] = { pickInstalledPack(appPacks) }
        }
        actions[".json veya .json.gz dosyası seç"] = ::pickGamePack
        if (current) actions["Paketi kaldır"] = {
            ImportedGamePack.remove(this)
            prefs.edit().remove("packTitle").apply()
            render()
        }
        val entries = actions.entries.toList()
        AlertDialog.Builder(this)
            .setTitle("Oyun paketi")
            .setItems(entries.map { it.key }.toTypedArray()) { _, index -> entries[index].value() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun pickInstalledPack(names: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("GameTranslator paketleri")
            .setItems(names.map { it.substringBefore(".json").replace('-', ' ') }.toTypedArray()) { _, index ->
                installPack { app -> ImportedGamePack.installFromApp(app, names[index]) }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun pickGamePack() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, GAME_PACK)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Dosya seçici açılamadı.", Toast.LENGTH_LONG).show()
        }
    }

    private fun installPack(load: (Context) -> String) {
        if (packLoading) return
        packLoading = true
        packDownloadPercent = 0
        refreshCurrentMode()
        val app = applicationContext
        Thread({
            val result = runCatching { load(app) }
            result.getOrNull()?.let { prefs.edit().putString("packTitle", it).apply() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                packLoading = false
                packDownloadPercent = 0
                val message = result.fold(
                    onSuccess = { title -> "Oyun paketi yüklendi: " + title },
                    onFailure = { error -> "Paket yüklenemedi: " + (error.message ?: "Bağlantı veya dosya hatası") },
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshCurrentMode()
            }
        }, "DraBornTranslate-pack-import").start()
    }

    private fun updateGta6DownloadProgress(percent: Int) {
        main.post {
            if (packLoading && !isFinishing && !isDestroyed && packDownloadPercent != percent) {
                packDownloadPercent = percent
                refreshCurrentMode()
            }
        }
    }

    private fun refreshCurrentMode() {
        when (entryMode) {
            EntryMode.HOME -> buildLandingUi()
            EntryMode.PORTAL -> render()
            EntryMode.PS5_TV -> Unit
        }
    }

    private fun renderPortalPanel(state: SessionState.State) {
        panelCard.visibility = if (config.showOnPortal) View.VISIBLE else View.GONE
        val panel = state.portalPanel
        if (tunnelSession != panel?.url) {
            tunnelAddress = ""
            tunnelSession = panel?.url
        }
        copyPanel.visibility = if (panel == null) View.GONE else View.VISIBLE
        testPanel.visibility = copyPanel.visibility
        tunnelWizard.visibility = copyPanel.visibility
        tunnelWizard.isEnabled = panel != null && !pending
        previewPanel.visibility = if (state.active && panel != null && !panel.testing) View.VISIBLE else View.GONE
        previewPanel.text = if (panel?.previewing == true) "Canlı çeviriye dön" else "Portal’da örnek yazı göster"
        launchPanelTest.visibility = if (state.active) View.GONE else View.VISIBLE
        launchPanelTest.isEnabled = !pending && demoTranslator == null
        launchPanelTest.isActivated = panel?.testing == true
        launchPanelTest.text = if (launchPanelTest.isActivated) PORTAL_STOP else PORTAL_START
        panelStatus.text = state.portalIssue ?: when {
            panel?.previewing == true -> "Örnek yazı 3 dakika gösterilir. Portal’da oyuna dönüp yazının değiştiğini kontrol et."
            panel?.testing == true && panel.opened -> "Panel açıldı. Oyuna dönüp örnek yazının Portal ekranında değiştiğini kontrol et."
            panel?.testing == true -> "Portal bağlantısı hazır. Kopyalayıp Game Base üzerinden Portal’da aç."
            panel?.opened == true -> "Bir tarayıcı altyazıyı alıyor. Portal’da Yana Sabitle görünüyorsa seç."
            panel != null -> "PS5 evdeki ağdaysa yerel bağlantıyı kullan. PS5 uzaktaysa dış bağlantıyı hazırla."
            state.active -> "Portal bağlantısı hazırlanıyor…"
            else -> "Bağlantıyı başlatıp Portal’da örnek yazıyı gör. Canlı çeviriye aynı panelden geçebilirsin."
        }
    }

    private fun togglePanelTest() {
        if (SessionState.current.portalPanel?.testing == true) {
            CaptureService.stop(this)
            return
        }
        demoMessage = null
        demoCaption = null
        if (!config.showOnPortal) setDisplay(SubtitleDisplay.PORTAL)
        try {
            CaptureService.testPanel(this, config.textSize)
        } catch (_: RuntimeException) {
            showIdle("Portal bağlantısı açılamadı. Uygulama açıkken yeniden dene.")
        }
        render()
    }

    private fun copyPanelLink() {
        val url = SessionState.current.portalPanel?.url ?: return
        copyText("DraBornTranslate paneli", url)
    }

    private fun copyText(label: String, value: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Kopyalandı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTunnelWizard() {
        val panel = SessionState.current.portalPanel ?: return
        showPopup("Uzak PS5 bağlantısı", R.drawable.ic_portal_link, LAVENDER) { dialog ->
            val terminal = popupStep(1, "Tüneli başlat", "Komutu Termux’a yapıştırıp çalıştır. Tünel açık kalsın.")
            terminal.append(button("Komutu kopyala ve Termux’u aç") {
                copyText("Cloudflare tünel komutu", portalTunnelCommand(panel.url))
                val termux = packageManager.getLaunchIntentForPackage("com.termux")
                if (termux != null) startActivity(termux)
                else Toast.makeText(this@MainActivity, "Komut kopyalandı. Termux’u açıp yapıştır.", Toast.LENGTH_LONG).show()
            }.apply { styleButton(this, LAVENDER to PEACH) }, 10)

            val connection = popupStep(2, "Dış adresi kontrol et", "Termux’taki trycloudflare.com HTTPS adresini yapıştır.")
            val address = EditText(this@MainActivity).apply {
                hint = "https://…trycloudflare.com"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setText(tunnelAddress)
                setTextColor(WHITE)
                setHintTextColor(MUTED)
                textSize = 14f
                minHeight = dp(50)
                maxLines = 2
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), shape(BACKGROUND, ACCENT, 12))
                    addState(intArrayOf(), shape(BACKGROUND, OUTLINE, 12))
                }
            }
            val result = label("", 14, ACCENT).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                visibility = View.GONE
            }
            fun showResult(message: String) {
                result.text = message
                result.visibility = View.VISIBLE
            }
            fun publicLink(): String? {
                if (SessionState.current.portalPanel?.url != panel.url) {
                    showResult("Panel oturumu değişti. Yeni bağlantıyı hazırla.")
                    return null
                }
                val link = portalPublicLink(panel.url, address.text.toString())
                if (link == null) address.error = "Termux’un verdiği HTTPS adresini yapıştır."
                else tunnelAddress = "https://${URI(link).host}"
                return link
            }
            connection.append(address, 10)
            val test = button("Dış bağlantıyı kontrol et").apply {
                setOnClickListener {
                    val link = publicLink() ?: return@setOnClickListener
                    isEnabled = false
                    showResult("Panel bağlantısı kontrol ediliyor…")
                    checkTunnelLink(link) { message ->
                        if (dialog.isShowing) {
                            isEnabled = true
                            showResult(when {
                                SessionState.current.portalPanel?.url != panel.url -> "Panel oturumu değişti. Yeni bağlantıyı hazırla."
                                portalPublicLink(panel.url, address.text.toString()) != link -> "Adres değişti. Yeni adresi kontrol et."
                                else -> message
                            })
                        }
                    }
                }
            }
            connection.append(test, 8)
            connection.append(result, 8)

            val share = popupStep(3, "Portal’da aç", "Tam bağlantıyı Game Base mesajıyla ana hesabına gönder. Portal’da aç ve Yana Sabitle görünüyorsa seç.")
            share.append(button("Tam Portal bağlantısını kopyala") {
                val link = publicLink() ?: return@button
                copyText("Portal HTTPS paneli", link)
                showResult("Bağlantı kopyalandı. Mesajı gönderdikten sonra PlayStation App yayınını tam ekranda açık bırak.")
            }.apply { styleButton(this, MINT to ACCENT) }, 10)
        }
    }

    private fun checkTunnelLink(link: String, complete: (String) -> Unit) {
        Thread({
            val result = try {
                val request = URI(link).toURL().openConnection() as HttpURLConnection
                try {
                    request.requestMethod = "HEAD"
                    request.instanceFollowRedirects = false
                    request.connectTimeout = 4500
                    request.readTimeout = 4500
                    when (val code = request.responseCode) {
                        200 -> if (request.getHeaderField("X-DraBorn-Panel") == "ready") {
                            "Dış bağlantı hazır. Tam bağlantıyı kopyalayıp Portal’da aç."
                        } else "Bu adres DraBornTranslate panelini göstermiyor. Termux’taki güncel adresi kontrol et."
                        404 -> "Panel bulunamadı. Güncel Termux komutunu çalıştırıp yeni adresi yapıştır."
                        502, 503, 504, 530 -> "Tünel panele ulaşamadı ($code). DraBornTranslate’te panelin açık olduğunu ve Termux’u kontrol et."
                        else -> "Cloudflare HTTP $code döndürdü. Termux’taki güncel adresi kontrol et."
                    }
                } finally {
                    request.disconnect()
                }
            } catch (_: IOException) {
                "Bağlantı yanıt vermedi. Termux’ta tünel kaydını bekleyip tekrar kontrol et."
            }
            runOnUiThread { if (!isDestroyed) complete(result) }
        }, "DraBornTranslate-tunnel-check").start()
    }

    private fun showPortalHelp() {
        showPopup("Portal panelini açma adımları", R.drawable.ic_portal_steps, ACCENT) {
            popupStep(1, "Bağlantıyı başlat", "$PORTAL_START düğmesine dokun. Bağlantı 2,5 dakika boyunca örnek yazı gösterir.")
            popupStep(2, "Bağlantıyı gönder", "PS5 aynı ağdaysa panel bağlantısını kopyala. Uzaktaysa PS5 uzakta · Bağlantıyı hazırla ile HTTPS adresi al. PlayStation App’te adresi Game Base mesajıyla ana hesabına gönder.")
            popupStep(3, "Portal’da paneli aç", "PS düğmesi → Game Base yoluyla mesajdaki adresi aç. Options menüsünde Yana Sabitle görünüyorsa seç ve oyuna dön. Örnek yazının güncellendiğini kontrol et.")
            popupStep(4, "Canlı çeviriyi başlat", "Örnek yazı açıkken PlayStation App’te Paylaşımlı Ekran yayınına dön ve DraBornTranslate’te canlı çeviriyi başlat. Süre dolduysa yeni panel bağlantısı al.")
            append(label("Yana Sabitle seçeneği yoksa çeviriyi telefon ekranında takip edebilirsin.", 13, ACCENT), 12)
        }
    }

    private fun beginOrStop() {
        if (SessionState.current.active) {
            CaptureService.stop(this)
            return
        }
        voicePreview?.close()
        voicePreview = null
        if (packageManager.getLaunchIntentForPackage(config.source.packageName) == null) {
            openSourceApp(config.source, offerInstall = true)
            return
        }
        demoMessage = null
        demoCaption = null
        if (config.readingArea == ReadingArea.LENS && !config.showOnPhone) {
            Toast.makeText(this, "Lens modu için çeviri çıkışını Telefon veya Telefon ve Portal seç.", Toast.LENGTH_LONG).show()
            return
        }
        pending = true
        render()
        if (config.showOnPhone && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "DraBornTranslate için diğer uygulamaların üstünde gösterme iznini aç.", Toast.LENGTH_LONG).show()
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), OVERLAY)
            return
        }
        requestCapture()
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("askedNotifications", false)
        ) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION)
        } else {
            showCapturePicker()
        }
    }

    @Suppress("DEPRECATION")
    private fun showCapturePicker() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), PROJECTION)
    }

    @Deprecated("Android Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            GAME_PACK -> if (resultCode == RESULT_OK) data?.data?.let { uri ->
                installPack { app -> ImportedGamePack.install(app, uri) }
            }
            OVERLAY -> {
                if (Settings.canDrawOverlays(this)) requestCapture()
                else showIdle("Canlı altyazı için diğer uygulamaların üzerinde gösterme iznini aç.")
            }
            PROJECTION -> {
                if (resultCode != RESULT_OK || data == null) {
                    showIdle("Ekran paylaşımı iptal edildi.")
                    return
                }
                pending = false
                try {
                    SessionState.update(SessionState.current.copy(active = true, status = "Ekran paylaşımı başlatılıyor…"))
                    CaptureService.start(this, resultCode, data, config)
                    if (Build.VERSION.SDK_INT < 34) openSourceApp(config.source, offerInstall = false)
                } catch (error: RuntimeException) {
                    showIdle("Canlı altyazı servisinin açılması için tekrar izin ver.")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION && pending) showCapturePicker()
    }

    private fun showIdle(message: String) {
        pending = false
        val state = SessionState.current
        SessionState.update(if (state.portalPanel?.testing == true) state.copy(active = false, status = message)
            else SessionState.State(status = message, lastCaption = state.lastCaption))
        render()
    }

    private fun openSourceApp(source: Source, offerInstall: Boolean) {
        val intent = packageManager.getLaunchIntentForPackage(source.packageName)
        if (intent != null) {
            startActivity(intent)
        } else if (offerInstall) {
            AlertDialog.Builder(this)
                .setTitle(source.appName)
                .setMessage("Bu modu kullanmak için uygulamayı Google Play’den indir.")
                .setPositiveButton("Google Play’i aç") { _, _ ->
                    openLink("https://play.google.com/store/apps/details?id=${source.packageName}")
                }
                .setNegativeButton("Kapat", null)
                .show()
        }
    }

    private fun showTranslationTest() {
        AlertDialog.Builder(this)
            .setTitle("Çeviriyi dene")
            .setItems(arrayOf("Örnek görseldeki altyazıyı oku", "İngilizce metni gir")) { _, index ->
                if (index == 0) runDemo() else showTextTest()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showTextTest() {
        val input = EditText(this).apply {
            hint = "İngilizce repliği yapıştır"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setLines(4)
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("İngilizce metni çevir")
            .setView(input)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Çevir", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val original = input.text.toString().trim()
                if (original.isEmpty()) {
                    input.error = "İngilizce metni gir."
                    return@setOnClickListener
                }
                dialog.dismiss()
                runDemo(original)
            }
        }
        dialog.show()
    }

    private fun runDemo(typed: String? = null) {
        val translator = GameTranslatorEngine(this)
        demoTranslator = translator
        demoMessage = "Telefondaki çeviri modeli hazırlanıyor…"
        demoCaption = null
        demoSource = typed
        render()
        translator.prepare { ready ->
            if (demoTranslator !== translator) return@prepare
            if (!ready) {
                completeDemo("Çeviri modeli açılamadı. Telefonda boş yer kontrolü yapıp yeniden dene.")
                return@prepare
            }
            if (typed != null) {
                translateDemo(translator, typed)
                return@prepare
            }
            demoMessage = "Örnek görseldeki İngilizce altyazı okunuyor…"
            render()
            val reader = SubtitleReader().also { demoReader = it }
            reader.read(exampleBitmap()) { lines ->
                if (demoTranslator !== translator) return@read
                val original = lines.orEmpty().joinToString("\n").trim()
                if (original.isEmpty()) {
                    completeDemo("Örnek görseldeki yazı okunamadı.")
                    return@read
                }
                translateDemo(translator, original)
            }
        }
    }

    private fun translateDemo(translator: CaptionTranslator, original: String) {
        demoSource = original
        demoMessage = "İngilizce metin çevriliyor…"
        render()
        translator.translate(TranslationInput(original)) { result ->
            if (demoTranslator !== translator) return@translate
            val text = result.text
            if (text.isNullOrBlank()) completeDemo(result.issue ?: "Metin çevrilemedi.")
            else completeDemo("Türkçe çeviri", Caption(original, formatDialogue(text)))
        }
    }

    private fun completeDemo(message: String, caption: Caption? = null) {
        finishDemo()
        demoMessage = message
        demoCaption = caption
        render()
        status.requestRectangleOnScreen(android.graphics.Rect(0, 0, status.width, status.height), false)
    }

    private fun finishDemo() {
        demoTranslator?.close()
        demoReader?.close()
        demoTranslator = null
        demoReader = null
    }

    private fun exampleBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(960, 186, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("We've got twenty minutes.", 480f, 76f, paint)
        canvas.drawText("Don't start without me.", 480f, 132f, paint)
        return bitmap
    }

    private fun showAbout() {
        if (aboutDialog?.isShowing == true) return
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        showPopup("DraBornTranslate", R.drawable.brand_mark, overline = "DrabornEagle") { dialog ->
            aboutDialog = dialog
            append(
                label("İngilizce oyun yazılarını ücretsiz olarak cihazda çevirir. Lens modunda Türkçe yazıyı görüntünün üzerinde gösterir. Google ML Kit modeli ilk kullanımda internetten indirilebilir; ardından çeviri cihazda çalışır.", 14, MUTED)
                    .apply { setLineSpacing(dp(3).toFloat(), 1f) },
                16,
            )
            append(label("Sürüm $version", 14, WHITE, bold = true).apply {
                background = shape(0xFF191D36.toInt(), OUTLINE, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }, 16)

            val check = button("Yeni sürüm kontrolü") {
                if (AppUpdater.state is UpdateState.Ready) AppUpdater.continueInstall(this@MainActivity, requested = true)
                else {
                    updateResultPending = true
                    AppUpdater.check(this@MainActivity)
                }
            }
            styleButton(check, ACCENT to LAVENDER)
            append(check, 12)
            val progress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressTintList = ColorStateList.valueOf(ACCENT)
                progressBackgroundTintList = ColorStateList.valueOf(OUTLINE)
                max = 100
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            append(progress, 8)
            val message = label("", 14, MUTED).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            append(message, 6)

            val updateUi: (UpdateState) -> Unit = { state ->
                check.isEnabled = state == UpdateState.Idle || state == UpdateState.Current ||
                    state is UpdateState.Ready || state is UpdateState.Message
                check.text = if (state is UpdateState.Ready) "Kurulum iznini aç" else "Yeni sürüm kontrolü"
                progress.visibility = if (state is UpdateState.Downloading) View.VISIBLE else View.GONE
                if (state is UpdateState.Downloading) progress.progress = state.percent
                message.text = when (state) {
                    UpdateState.Idle, UpdateState.Current -> ""
                    UpdateState.Checking -> "GitHub’da yeni sürüm aranıyor…"
                    is UpdateState.Downloading -> "v${state.version} indiriliyor · %${state.percent}"
                    is UpdateState.Ready -> "v${state.version} indirildi. Android’de bu uygulama için kurulum izni ver."
                    UpdateState.Installing, is UpdateState.Confirm -> "Güncelleme Android’e gönderiliyor…"
                    is UpdateState.Message -> state.text
                }
                message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
            }
            updateUi(AppUpdater.state)
            renderAboutUpdate = updateUi
            append(label("Güncellemeler yalnızca resmî DraBornTranslate GitHub sürümlerinden alınır.", 13, MUTED), 10)
        }
    }

    private fun checkUpstreamSourceVersion() {
        Thread {
            val result = runCatching { GameTranslatorSource.latest() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showPopup("GameTranslator kaynak sürümü", R.drawable.ic_portal_check, LAVENDER) {
                    append(label("Referans: " + GameTranslatorSource.REFERENCE, 14, MUTED), 12)
                    val version = result.getOrNull()
                    if (version == null) {
                        append(label("GitLab sürümüne ulaşılamadı. İnternet bağlantını kontrol et.", 15, WHITE), 10)
                    } else {
                        append(label("En yeni kaynak: " + version.tag, 18, WHITE, bold = true), 12)
                        append(label(version.title, 14, MUTED), 6)
                        append(label("Yeni kaynaklar GitHub'da ayrı kaynak klasörüne alınır. " +
                            "Testler ve entegrasyon bitmeden APK'ya uygulanmaz.", 14, MUTED), 10)
                        append(button("GitLab sürüm notlarını aç") { openLink(version.url) }, 12)
                    }
                }
            }
        }.start()
    }

    private fun showCurrentVersion() {
        showPopup("Sürüm kontrolü", R.drawable.ic_portal_check, MINT) {
            append(label("En güncel sürümü kullanıyorsun.", 16, WHITE, bold = true), 16)
        }
    }

    private fun showPopup(
        title: String,
        icon: Int,
        accent: Int = LAVENDER,
        overline: String? = null,
        content: LinearLayout.(Dialog) -> Unit,
    ) {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val surface = column().apply {
            background = gradient(GradientDrawable.Orientation.TL_BR, 0xFF24214B.toInt(), PANEL, 28)
                .apply { setStroke(dp(1), 0xFF56528F.toInt()) }
            setPadding(dp(20), dp(16), dp(20), dp(20))
            isFocusableInTouchMode = true
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(icon)
            if (icon != R.drawable.brand_mark) {
                background = shape(0xFF252944.toInt(), OUTLINE, 15)
                setColorFilter(accent)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
        header.addView(column().apply {
            if (overline != null) addView(label(overline, 13, accent, bold = true))
            append(label(title, 20, WHITE, bold = true).apply { isAccessibilityHeading = true }, if (overline != null) 2 else 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ImageButton(this).apply {
            background = RippleDrawable(ColorStateList.valueOf(0x33B6A8FF), shape(PANEL, OUTLINE, 14), null)
            setImageResource(R.drawable.ic_close)
            setColorFilter(LAVENDER)
            contentDescription = "Kapat"
            tooltipText = contentDescription
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(6) })
        surface.addView(header)
        surface.content(dialog)
        popups.add(dialog)
        dialog.setOnDismissListener {
            popups.remove(dialog)
            if (aboutDialog === dialog) {
                aboutDialog = null
                renderAboutUpdate = null
                updateResultPending = false
            }
        }
        dialog.setContentView(ScrollView(this).apply { addView(surface) })
        surface.requestFocus()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(minOf(420, (resources.configuration.screenWidthDp - 32).coerceAtLeast(200))),
                WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            setDimAmount(0.65f)
        }
    }

    private fun LinearLayout.popupStep(number: Int, title: String, description: String): LinearLayout {
        val step = card()
        val heading = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(number.toString(), 14, BACKGROUND, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = "$number. adım"
            background = gradient(GradientDrawable.Orientation.TL_BR, ACCENT, LAVENDER, 9)
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
        heading.addView(label(title, 15, WHITE, bold = true).apply { isAccessibilityHeading = true },
            LinearLayout.LayoutParams(0, -2, 1f))
        step.addView(heading)
        step.append(label(description, 14, MUTED).apply { setLineSpacing(dp(2).toFloat(), 1f) }, 8)
        append(step, 10)
        return step
    }

    private fun showLegalNotices() {
        AlertDialog.Builder(this)
            .setTitle("Yasal bildirimler")
            .setMessage("GameTranslator v1.8.48 ve CouchSubs · © Sinan Yorulmaz ve Sefflex\n" +
                "Bu uygulama GNU AGPL v3 koşullarıyla paylaşılabilir. Garanti verilmez.\n\n" +
                "Töz-Çevir-1 · Apache 2.0\nGoogle ML Kit · Ekrandaki yazıyı tanıma")
            .setPositiveButton("GNU AGPL v3") { _, _ ->
                val terms = assets.open("LICENSE").bufferedReader().use { it.readText() }
                AlertDialog.Builder(this).setTitle("GNU AGPL v3").setMessage(terms).setPositiveButton("Kapat", null).show()
            }
            .setNeutralButton("Model lisansı") { _, _ -> openLink("https://huggingface.co/TozAI/Toz-Cevir-1") }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "Bu bağlantıyı açacak bir uygulama bulunamadı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun sectionTitle(value: String) = label(value, 18, WHITE, bold = true).apply {
        isAccessibilityHeading = true
    }

    private fun button(value: String, action: (() -> Unit)? = null) = Button(this).apply {
        text = value
        textSize = 14f
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        minimumHeight = dp(48)
        if (action != null) setOnClickListener { action() }
        styleButton(this)
    }

    private fun styleButton(button: Button, colors: Pair<Int, Int>? = null, activated: Pair<Int, Int>? = null) {
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val selected = intArrayOf(android.R.attr.state_selected)
        val shaped = StateListDrawable().apply {
            addState(disabled, shape(0xFF1B2038.toInt(), OUTLINE, 14))
            addState(selected, shape(0xFF332F63.toInt(), LAVENDER, 14))
            if (activated != null) addState(intArrayOf(android.R.attr.state_activated),
                gradient(GradientDrawable.Orientation.LEFT_RIGHT, activated.first, activated.second, 14))
            addState(intArrayOf(), colors?.let {
                gradient(GradientDrawable.Orientation.LEFT_RIGHT, it.first, it.second, 14)
            } ?: shape(0xFF242A4B.toInt(), OUTLINE, 14))
        }
        button.backgroundTintList = null
        button.background = RippleDrawable(ColorStateList.valueOf(0x3389C5FF), shaped, null)
        button.setPadding(dp(12), dp(10), dp(12), dp(10))
        button.setTextColor(ColorStateList(
            arrayOf(disabled, selected, intArrayOf()),
            intArrayOf(0xFF909AB8.toInt(), WHITE, if (colors != null) BACKGROUND else WHITE),
        ))
    }

    private fun actionIcon(button: Button, icon: Int, activeIcon: Int? = null) {
        val icons = StateListDrawable().apply {
            if (activeIcon != null) addState(intArrayOf(android.R.attr.state_activated), getDrawable(activeIcon)?.mutate())
            addState(intArrayOf(), getDrawable(icon)?.mutate())
            setTintList(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(0xFF909AB8.toInt(), BACKGROUND)))
        }
        button.compoundDrawablePadding = dp(8)
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icons, null, null, null)
    }

    private fun slider(description: String, limit: Int, value: Int, changed: (Int) -> Unit) = SeekBar(this).apply {
        contentDescription = description
        max = limit
        progress = value
        thumbTintList = ColorStateList.valueOf(ACCENT)
        progressTintList = ColorStateList.valueOf(ACCENT)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                changed(progress)
            }
        })
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun card(highlight: Boolean = false) = column().apply {
        background = if (highlight) gradient(GradientDrawable.Orientation.TL_BR,
            0xFF212B50.toInt(), PANEL, 21).apply { setStroke(dp(1), 0xFF486087.toInt()) }
        else shape(PANEL, OUTLINE, 21)
        setPadding(dp(16), dp(15), dp(16), dp(15))
    }

    private fun shape(color: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radius).toFloat()
    }

    private fun gradient(orientation: GradientDrawable.Orientation, start: Int, end: Int, radius: Int) =
        GradientDrawable(orientation, intArrayOf(start, end)).apply { cornerRadius = dp(radius).toFloat() }

    private fun LinearLayout.addDetails(title: String): LinearLayout {
        val container = card()
        val body = column()
        val heading = button(title)
        heading.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        heading.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_disclosure, 0)
        heading.isSelected = true
        if (Build.VERSION.SDK_INT >= 30) heading.stateDescription = "Açık"
        heading.setOnClickListener {
            val expanded = body.visibility != View.VISIBLE
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            heading.isSelected = expanded
            if (Build.VERSION.SDK_INT >= 30) heading.stateDescription = if (expanded) "Açık" else "Kapalı"
        }
        container.addView(heading, LinearLayout.LayoutParams(-1, -2))
        container.addView(body, LinearLayout.LayoutParams(-1, -2))
        append(container, 12)
        return body
    }

    private fun LinearLayout.append(view: View, gap: Int) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }

    private inner class BandPreview : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var video = Pixels(0, 0, 1, 1)

        init {
            contentDescription = "Oyun görüntüsündeki altyazı okuma bölgesi"
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            val boxHeight = minOf(height - dp(4), width * 9 / 16).coerceAtLeast(1)
            val boxWidth = boxHeight * 16 / 9
            video = Pixels((width - boxWidth) / 2, (height - boxHeight) / 2, boxWidth, boxHeight)
        }

        override fun onDraw(canvas: Canvas) {
            val left = video.x.toFloat()
            val top = video.y.toFloat()
            val radius = dp(7).toFloat()
            paint.color = BACKGROUND
            canvas.drawRoundRect(left, top, left + video.width, top + video.height, radius, radius, paint)
            paint.color = 0xFF345D81.toInt()
            val band = config.scanArea(video)
            canvas.drawRoundRect(band.x.toFloat(), band.y.toFloat(), (band.x + band.width).toFloat(),
                (band.y + band.height).toFloat(), radius / 2, radius / 2, paint)
            paint.color = ACCENT
            val centerY = band.y + band.height / 2f
            canvas.drawRoundRect(left + video.width * 0.24f, centerY - dp(1), left + video.width * 0.76f,
                centerY + dp(1), dp(1).toFloat(), dp(1).toFloat(), paint)
        }
    }

    companion object {
        private const val OVERLAY = 10
        private const val NOTIFICATION = 11
        private const val PROJECTION = 12
        private const val GAME_PACK = 13
    }
}
