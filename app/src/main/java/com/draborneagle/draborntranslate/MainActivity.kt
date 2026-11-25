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

private enum class TranslationMode(
    val label: String,
    val icon: Int,
    val accent: Int,
    val area: ReadingArea = ReadingArea.SUBTITLES,
    val focus: Boolean = false,
    val captions: Boolean = true,
) {
    SUBTITLES(ReadingArea.SUBTITLES.label, R.drawable.ic_tv_screen, ACCENT),
    IMAGE(VISUAL_TRANSLATION, R.drawable.ic_focus_region, MINT, focus = true, captions = false),
    BOTH("Altyazı + $VISUAL_TRANSLATION", R.drawable.ic_capture_album, LAVENDER, focus = true),
    GAME_TEXT(ReadingArea.GAME_TEXT.label, R.drawable.ic_controller, PEACH, area = ReadingArea.GAME_TEXT),
}

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("capture", MODE_PRIVATE) }
    private lateinit var config: CaptureSettings
    private var pending = false
    private var demoMessage: String? = null
    private var demoCaption: Caption? = null
    private var demoSource: String? = null
    private var demoTranslator: CaptionTranslator? = null
    private var demoReader: SubtitleReader? = null
    private var voicePreview: TurkishVoice? = null
    private var tunnelAddress = ""
    private var tunnelSession: String? = null
    private val popups = mutableSetOf<Dialog>()
    private var aboutDialog: Dialog? = null
    private var renderAboutUpdate: ((UpdateState) -> Unit)? = null
    private var updateResultPending = false
    private lateinit var panelHelp: Button
    private lateinit var profileChoice: Button
    private lateinit var gameHudLabel: TextView
    private var portalConnectionBadge: TextView? = null

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

    private val observe: (SessionState.State) -> Unit = { render() }
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
        val route = if (intent.getStringExtra("connection") == Source.MOBILE.name) Source.MOBILE else Source.PORTAL
        config = readCaptureSettings(route)
        pending = savedInstanceState?.getBoolean("pending") ?: false
        tunnelAddress = savedInstanceState?.getString("tunnelAddress").orEmpty()
        tunnelSession = savedInstanceState?.getString("tunnelSession")
        updateResultPending = savedInstanceState?.getBoolean("updateResultPending") ?: false
        buildUi()
        render()
        if (savedInstanceState?.getBoolean("aboutOpen") == true) showAbout()
        CaptureInbox.watch(this)
        CaptureInbox.enqueue(this)
        if (savedInstanceState == null && intent.getBooleanExtra("openFrameShare", false)) {
            main.post { if (!isFinishing && !isDestroyed) beginOrStop() }
        }
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
        render()
    }

    override fun onPause() {
        AppUpdater.remove(observeUpdate)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
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

    private fun buildUi() {
        val mobile = config.source == Source.MOBILE
        val accent = if (mobile) Palette.blue else Palette.rose
        val content = if (mobile) contentPage("Telefonda oyna", accent) else portalPage()
        val capture = if (mobile) card() else connectionSurface(Palette.purple)
        if (mobile) {
            content.append(featureHeader("Oyun telefonda", "PS Remote Play ile oyun görüntüsünü paylaş.",
                R.drawable.ic_phone_screen, accent), 0)
        } else {
            capture.addView(portalIllustration(), LinearLayout.LayoutParams(-1, dp(126)))
            val badge = copy("", 12, Palette.rose, true).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(dp(8), dp(5), dp(8), dp(5))
                maxWidth = dp(148)
            }
            portalConnectionBadge = badge
            capture.append(row().apply {
                addView(copy("PlayStation App yayını", 14, Palette.white, true),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(badge, LinearLayout.LayoutParams(-2, -2))
            }, 10)
        }

        content.append(capture, if (mobile) 12 else 0)
        startStop = button("Canlı çeviriyi başlat") { beginOrStop() }
        styleButton(startStop, if (mobile) ACCENT to LAVENDER else LAVENDER to Palette.rose, activated = PEACH to ROSE)
        actionIcon(startStop, R.drawable.ic_portal_play, activeIcon = R.drawable.ic_portal_stop)
        if (!mobile) {
            startStop.textSize = 16f
            startStop.minimumHeight = dp(53)
        }
        capture.append(startStop, if (mobile) 0 else 12)
        openSource = if (mobile) button("") { openSourceApp(config.source, offerInstall = true) }
            else connectionOption("PlayStation App’i aç", R.drawable.ic_phone_screen, Palette.purple) {
                openSourceApp(config.source, offerInstall = true)
            }
        capture.append(openSource, 8)
        displayChoice = connectionOption("", R.drawable.ic_tv_screen, Palette.blue) { selectDisplay() }
        readingChoice = connectionOption("", R.drawable.ic_capture_album, Palette.rose) { selectReadingArea() }
        profileChoice = connectionOption("", R.drawable.ic_controller, Palette.purple) { selectGameProfile() }
        val focusArea = connectionOption("Görüntüde alan seç", R.drawable.ic_focus_region, Palette.mint) {
            startActivity(Intent(this, FocusActivity::class.java))
        }
        if (mobile) {
            capture.append(readingChoice, 7)
            capture.append(profileChoice, 7)
            capture.append(focusArea, 7)
        } else {
            content.append(copy("Çeviri seçenekleri", 22, Palette.white, true).apply {
                isAccessibilityHeading = true
            }, 19)
            content.append(displayChoice, 9)
            content.append(readingChoice, 7)
            content.append(profileChoice, 7)
            content.append(focusArea, 7)
        }

        val live = if (mobile) card(highlight = true) else connectionSurface(Palette.mint)
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
        gameHudLabel = label("", 15, MINT).apply { setLineSpacing(dp(4).toFloat(), 1f) }
        live.append(gameHudLabel, 8)
        copyTurkish = button("Türkçeyi kopyala") {
            displayedCaption()?.let { copyText("Türkçe çeviri", it.turkish) }
        }
        live.append(copyTurkish, 8)
        copySource = button("İngilizceyi kopyala") {
            recognizedText()?.let { copyText("Okunan İngilizce", it) }
        }
        live.append(copySource, 6)

        panelCard = if (mobile) card() else connectionSurface(Palette.purple)
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
        panelHelp = button("Portal’da paneli açma adımları") { showPortalHelp() }
        panelCard.append(panelHelp, 6)

        val guide = content.addDetails("Bağlantı ve ekran paylaşımı", expandedByDefault = mobile)
        instructions = label("", 15, MUTED).apply { setLineSpacing(dp(4).toFloat(), 1f) }
        guide.append(instructions, 10)

        val settings = content.addDetails("Görünüm ve oyun paketi", expandedByDefault = mobile)
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
        packButton = button("Oyun paketleri") { startActivity(Intent(this, GamePacksActivity::class.java)) }
        settings.append(packButton, 8)
        settings.append(button("Hakkında ve güncellemeler") { showAbout() }, 8)
        testButton = button("Çeviriyi dene") { showTranslationTest() }
        content.append(testButton, 12)
        voiceButton = button("Türkçe sesi dene") { previewVoice() }
        content.append(voiceButton, 6)
    }

    private fun portalPage(): LinearLayout {
        val (scroll, content) = scrollColumn()
        pageRoot(stack().apply {
            addView(row().apply {
                setPadding(dp(16), dp(4), dp(16), dp(4))
                addView(press("‹") { finish() }.apply {
                    background = RippleDrawable(ColorStateList.valueOf(0x29FFFFFF),
                        filled(0xFF211A31.toInt(), 24, 0xFF47385F.toInt()), null)
                    textSize = 27f
                    setTextColor(Palette.purple)
                    contentDescription = "Geri"
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(copy("PlayStation Portal", 22, Palette.white, true).apply {
                    isAccessibilityHeading = true
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(13) })
            })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }, Palette.purple, connectionBackground)
        return content
    }

    private fun render() {
        val state = SessionState.current
        val mobile = config.source == Source.MOBILE
        val textInstructions = if (config.readingArea == ReadingArea.GAME_TEXT) {
            "Çevirmek istediğin menüyü veya görevi açıp metni birkaç saniye ekranda tut."
        } else "Oyunun İngilizce altyazısını aç."
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
        openSource.text = if (mobile) "${config.source.appName} uygulamasını aç" else "PlayStation App’i aç"
        val panelTesting = state.portalPanel?.testing == true
        val currentSource = state.source == config.source
        val statusBadge = when {
            pending -> "Ekran paylaşımı bekleniyor" to Palette.blue
            state.active && currentSource && state.videoVisible -> "Canlı çeviri açık" to Palette.mint
            state.active && currentSource -> "Yayın bekleniyor" to Palette.blue
            state.active -> "Başka bağlantı açık" to Palette.peach
            panelTesting && currentSource -> "Panel denemesi açık" to Palette.mint
            else -> "Çeviri kapalı" to Palette.rose
        }
        portalConnectionBadge?.connectionBadge(statusBadge.first, statusBadge.second)
        val controlsAvailable = !state.active && !pending && !panelTesting
        listOf(bandControl, sizeControl).forEach { it.isEnabled = controlsAvailable }
        displayChoice.isEnabled = controlsAvailable
        displayChoice.text = config.display.label
        displayChoice.contentDescription = "Altyazı ve ses · ${config.display.label}"
        readingChoice.isEnabled = controlsAvailable
        readingChoice.text = readingMode().label
        readingChoice.contentDescription = "Çeviri modu · ${readingChoice.text}"
        profileChoice.isEnabled = controlsAvailable
        profileChoice.text = when {
            state.suggestGtaProfile && config.gameProfile == GameReadingProfile.GENERAL -> "Oyun profili · GTA 6 öneriliyor"
            else -> "Oyun profili · ${config.gameProfile.label}"
        }
        packButton.isEnabled = controlsAvailable && demoTranslator == null
        packButton.text = if (ImportedGamePack.hasGta(this)) "GTA 6 Türkçe paketi hazır" else "Oyun paketleri"
        bandSettings.visibility = if (config.captionEnabled && config.readingArea == ReadingArea.SUBTITLES && !config.gtaProfileActive)
            View.VISIBLE else View.GONE
        voiceButton.visibility = if (config.speakOnPhone) View.VISIBLE else View.GONE
        voiceButton.isEnabled = controlsAvailable
        startStop.isEnabled = !pending && demoTranslator == null
        startStop.isActivated = state.active && currentSource
        startStop.text = when {
            !state.active -> "Canlı çeviriyi başlat"
            state.source == config.source -> "Canlı çeviriyi durdur"
            else -> "Açık çeviriye dön"
        }
        testButton.isEnabled = controlsAvailable && demoTranslator == null
        renderPortalPanel(state)

        val caption = displayedCaption()
        val recognized = recognizedText()
        val source = caption?.source
        val differentReading = recognized?.takeUnless { it == source }
        status.text = when {
            state.active && state.source != config.source -> "Diğer bağlantıda canlı çeviri açık."
            panelTesting && state.source != config.source -> "Diğer bağlantı panelinde örnek altyazı açık."
            state.active || panelTesting -> state.voiceIssue ?: state.translationIssue ?: state.status
            demoMessage != null -> demoMessage
            state.source == null || state.source == config.source -> state.status
            else -> "Çeviriyi başlatmak için oyun görüntüsünü aç."
        }
        captionLabel.text = caption?.turkish.orEmpty()
        sourceLabel.text = source?.let { "Çevirinin İngilizce metni\n$it" }.orEmpty()
        recognizedLabel.text = differentReading?.let { "Okunan İngilizce\n$it" }.orEmpty()
        val hud = state.gameHud.takeIf { state.active && currentSource }.orEmpty()
        gameHudLabel.text = hud.joinToString("\n\n") { block ->
            val header = block.title ?: block.kind.label
            val text = block.lines.joinToString("\n") { it.text.replace(coloredHudText, "$1") }
            "$header\n$text"
        }
        gameHudLabel.visibility = if (hud.isEmpty()) View.GONE else View.VISIBLE
        captionLabel.visibility = if (caption == null) View.GONE else View.VISIBLE
        copyTurkish.visibility = captionLabel.visibility
        sourceLabel.visibility = if (source == null) View.GONE else View.VISIBLE
        recognizedLabel.visibility = if (differentReading == null) View.GONE else View.VISIBLE
        copySource.visibility = if (recognized == null) View.GONE else View.VISIBLE
    }

    private fun displayedCaption(): Caption? =
        if (demoMessage != null) demoCaption else SessionState.current.takeIf { it.source == config.source }
            ?.let { if (it.active) it.caption else it.lastCaption }

    private fun recognizedText(): String? =
        if (demoMessage != null) demoSource else displayedCaption()?.source

    private fun selectDisplay() {
        showPopup("Altyazı ve ses", R.drawable.ic_tv_screen, ACCENT) { dialog ->
            for ((index, display) in SubtitleDisplay.entries.withIndex()) {
                val (icon, accent) = when (display) {
                    SubtitleDisplay.PHONE -> R.drawable.ic_phone_screen to ACCENT
                    SubtitleDisplay.PORTAL -> R.drawable.ic_portal_device to LAVENDER
                    SubtitleDisplay.BOTH -> R.drawable.ic_portal_link to MINT
                    SubtitleDisplay.VOICE -> R.drawable.ic_notification to PEACH
                }
                append(connectionOption(display.label, icon, accent, selected = display == config.display) {
                    setDisplay(display)
                    render()
                    dialog.dismiss()
                }, if (index == 0) 14 else 7)
            }
        }
    }

    private fun setDisplay(display: SubtitleDisplay) {
        config = config.copy(display = display)
        prefs.edit().putString("display", display.name).putString("display.${config.source.name}", display.name).apply()
    }

    private fun selectGameProfile() {
        showPopup("Oyun profili", R.drawable.ic_controller, Palette.purple) { dialog ->
            append(label("GTA 6 profili altyazı, görev, ipucu ve telefon metninin yerini otomatik ayarlar.", 14, MUTED), 12)
            GameReadingProfile.entries.forEach { profile ->
                append(connectionOption(profile.label, R.drawable.ic_controller, Palette.purple,
                    selected = profile == config.gameProfile) {
                    config = if (profile == GameReadingProfile.GTA_6) {
                        config.copy(gameProfile = profile, readingArea = ReadingArea.SUBTITLES, captionEnabled = true)
                    } else config.copy(gameProfile = profile)
                    prefs.edit().putString("gameProfile", profile.name)
                        .putString("readingArea", config.readingArea.name).putBoolean("subtitles", config.captionEnabled).apply()
                    render()
                    dialog.dismiss()
                }, 8)
            }
            if (config.gameProfile == GameReadingProfile.GTA_6) {
                append(connectionOption(
                    if (config.plainPhonePanel) "Telefon paneli · Sade" else "Telefon paneli · Sohbet",
                    R.drawable.ic_phone_screen, MINT,
                ) {
                    config = config.copy(plainPhonePanel = !config.plainPhonePanel)
                    prefs.edit().putBoolean("plainPhonePanel", config.plainPhonePanel).apply()
                    dialog.dismiss()
                    selectGameProfile()
                }, 8)
                if (!ImportedGamePack.hasGta(this)) {
                    append(button("GTA 6 Türkçe paketini aç") {
                        dialog.dismiss()
                        startActivity(Intent(this, GamePacksActivity::class.java))
                    }, 8)
                }
            }
        }
    }

    private fun readingMode() = when {
        config.focus && config.captionEnabled -> TranslationMode.BOTH
        config.focus -> TranslationMode.IMAGE
        config.readingArea == ReadingArea.GAME_TEXT -> TranslationMode.GAME_TEXT
        else -> TranslationMode.SUBTITLES
    }

    private fun selectReadingArea() {
        val selected = readingMode()
        showPopup("Çeviri modu", R.drawable.ic_focus_region, Palette.rose) { dialog ->
            for ((index, mode) in TranslationMode.entries.withIndex()) {
                append(connectionOption(mode.label, mode.icon, mode.accent, selected = mode == selected) {
                    config = config.copy(readingArea = mode.area, focus = mode.focus, captionEnabled = mode.captions)
                    prefs.edit().putString("readingArea", mode.area.name)
                        .putBoolean("focus", mode.focus).putBoolean("subtitles", mode.captions).apply()
                    render()
                    dialog.dismiss()
                }, if (index == 0) 14 else 7)
            }
        }
    }

    private fun previewVoice() {
        voicePreview?.close()
        voicePreview = TurkishVoice(this) { issue ->
            Toast.makeText(this, issue, Toast.LENGTH_LONG).show()
        }.also { it.speak("Bu bir Türkçe altyazı ses testidir.") }
    }

    private fun renderPortalPanel(state: SessionState.State) {
        panelCard.visibility = if (config.showOnPortal) View.VISIBLE else View.GONE
        val panel = state.portalPanel?.takeIf { state.source == config.source }
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
        launchPanelTest.isEnabled = !pending && demoTranslator == null &&
            !(state.source != config.source && state.portalPanel?.testing == true)
        launchPanelTest.isActivated = panel?.testing == true
        launchPanelTest.text = if (launchPanelTest.isActivated) PORTAL_STOP else PORTAL_START
        panelStatus.text = state.portalIssue?.takeIf { state.source == config.source } ?: when {
            panel?.previewing == true -> "Örnek yazı 3 dakika gösterilir. Portal’da oyuna dönüp yazının değiştiğini kontrol et."
            panel?.testing == true && panel.opened -> "Panel açıldı. Oyuna dönüp örnek yazının Portal ekranında değiştiğini kontrol et."
            panel?.testing == true -> "Portal bağlantısı hazır. Kopyalayıp Game Base üzerinden Portal’da aç."
            panel?.opened == true -> "Bir tarayıcı altyazıyı alıyor. Portal’da Yana Sabitle görünüyorsa seç."
            panel != null -> "PS5 evdeki ağdaysa yerel bağlantıyı kullan. PS5 uzaktaysa dış bağlantıyı hazırla."
            state.active && state.source == config.source -> "Portal bağlantısı hazırlanıyor…"
            state.active || state.portalPanel?.testing == true -> "Portal paneli için diğer bağlantıyı durdur."
            else -> "Bağlantıyı başlatıp Portal’da örnek yazıyı gör. Canlı çeviriye aynı panelden geçebilirsin."
        }
    }

    private fun togglePanelTest() {
        val state = SessionState.current
        if (state.portalPanel?.testing == true) {
            if (state.source == config.source) CaptureService.stop(this)
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
        val state = SessionState.current
        if (state.active) {
            val other = state.source
            if (other != null && other != config.source) startActivity(connectionIntent(this, other))
            else CaptureService.stop(this)
            return
        }
        voicePreview?.close()
        voicePreview = null
        val sourcePackage = config.source.packageName ?: return
        if (packageManager.getLaunchIntentForPackage(sourcePackage) == null) {
            openSourceApp(config.source, offerInstall = true)
            return
        }
        demoMessage = null
        demoCaption = null
        pending = true
        render()
        if (config.overlayOnPhone && !Settings.canDrawOverlays(this)) {
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
                    SessionState.update(SessionState.current.copy(active = true, source = config.source, status = "Ekran paylaşımı başlatılıyor…"))
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
            else SessionState.State(source = config.source, status = message,
                lastCaption = state.lastCaption?.takeIf { state.source == config.source }))
        render()
    }

    private fun openSourceApp(source: Source, offerInstall: Boolean) {
        val packageId = source.packageName ?: return
        val intent = packageManager.getLaunchIntentForPackage(packageId)
        if (intent != null) {
            startActivity(intent)
        } else if (offerInstall) {
            AlertDialog.Builder(this)
                .setTitle(source.appName)
                .setMessage("Bu modu kullanmak için uygulamayı Google Play’den indir.")
                .setPositiveButton("Google Play’i aç") { _, _ ->
                    openLink("https://play.google.com/store/apps/details?id=$packageId")
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

    private fun showAbout(checkNow: Boolean = false) {
        if (aboutDialog?.isShowing == true) return
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        showPopup("DraBornTranslate", R.drawable.brand_mark, overline = "DrabornEagle") { dialog ->
            aboutDialog = dialog
            append(
                label("İngilizce oyun metinlerini oynarken canlı olarak Türkçeye çevirir. Çeviri çevrimdışı ve ücretsizdir.", 14, MUTED)
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
            if (checkNow && check.isEnabled) check.performClick()
        }
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

    private fun actionIcon(button: Button, icon: Int, activeIcon: Int? = null, tint: Int = BACKGROUND) {
        val icons = StateListDrawable().apply {
            if (activeIcon != null) addState(intArrayOf(android.R.attr.state_activated), getDrawable(activeIcon)?.mutate())
            addState(intArrayOf(), getDrawable(icon)?.mutate())
            setTintList(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(0xFF909AB8.toInt(), tint)))
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

    private fun LinearLayout.addDetails(title: String, expandedByDefault: Boolean = true): LinearLayout {
        val container = card()
        val body = column()
        val heading = button(title)
        heading.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        heading.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_disclosure, 0)
        body.visibility = if (expandedByDefault) View.VISIBLE else View.GONE
        heading.isSelected = expandedByDefault
        if (Build.VERSION.SDK_INT >= 30) heading.stateDescription = if (expandedByDefault) "Açık" else "Kapalı"
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
    }
}

private val coloredHudText = Regex("""⁅#[0-9a-fA-F]{6}\|([^⁆]*)⁆""")
