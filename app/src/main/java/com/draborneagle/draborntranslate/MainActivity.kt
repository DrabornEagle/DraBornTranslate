// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private var packLoading = false
    private var tunnelAddress = ""
    private var tunnelSession: String? = null

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
    private lateinit var modelLabel: TextView
    private lateinit var modelButton: Button
    private lateinit var bandPreview: BandPreview
    private lateinit var bandSettings: LinearLayout
    private lateinit var bandControl: SeekBar
    private lateinit var sizeControl: SeekBar
    private lateinit var testButton: Button
    private lateinit var voiceButton: Button

    private val observe: (SessionState.State) -> Unit = { render() }
    private val main = Handler(Looper.getMainLooper())
    private var modelState = OfflineModelStore.State(OfflineModelStore.Stage.MISSING, "")
    private val checkModel = object : Runnable {
        override fun run() {
            updateModelState()
            main.postDelayed(this, 1000)
        }
    }

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
        buildUi()
        updateModelState()
        render()
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
        main.post(checkModel)
    }

    override fun onStop() {
        SessionState.remove(observe)
        main.removeCallbacks(checkModel)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending", pending)
        outState.putString("tunnelAddress", tunnelAddress)
        outState.putString("tunnelSession", tunnelSession)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        finishDemo()
        voicePreview?.close()
        super.onDestroy()
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
        content.append(hero, 0)

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
        modelLabel = label("", 14, MUTED)
        capture.append(modelLabel, 12)
        modelButton = button("") { changeModelDownload() }
        styleButton(modelButton, primary = true)
        capture.append(modelButton, 8)

        startStop = button("Canlı çeviriyi başlat") { beginOrStop() }
        styleButton(startStop, primary = true)
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
        panelCard.append(tunnelWizard, 6)
        previewPanel = button("Portal’da örnek yazı göster") { CaptureService.togglePreview(this) }
        panelCard.append(previewPanel, 6)
        launchPanelTest = button("Portal bağlantısını test et") { togglePanelTest() }
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
        testButton = button("Çeviriyi dene") { showTranslationTest() }
        content.append(testButton, 12)
        voiceButton = button("Türkçe sesi dene") { previewVoice() }
        content.append(voiceButton, 6)
        content.append(button("Kaynak kod ve lisans") { showLicense() }, 8)
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
            packLoading -> "Oyun paketi yükleniyor…"
            ImportedGamePack.hasPack(this) -> "Oyun paketi · ${prefs.getString("packTitle", "Yüklü")}"
            else -> "Oyun paketi ekle"
        }
        bandSettings.visibility = if (config.readingArea == ReadingArea.SUBTITLES) View.VISIBLE else View.GONE
        voiceButton.visibility = if (config.speakOnPhone) View.VISIBLE else View.GONE
        voiceButton.isEnabled = controlsAvailable
        val modelReady = modelState.stage == OfflineModelStore.Stage.READY
        modelLabel.text = if (modelReady) "Çevrimdışı çeviri ve gelişmiş model hazır."
        else modelState.message
        modelButton.visibility = if (modelReady) View.GONE else View.VISIBLE
        modelButton.isEnabled = modelState.stage != OfflineModelStore.Stage.VERIFYING
        modelButton.text = when (modelState.stage) {
            OfflineModelStore.Stage.DOWNLOADING -> "İndirmeyi durdur"
            OfflineModelStore.Stage.VERIFYING -> "Dosya doğrulanıyor…"
            else -> "Gelişmiş modeli indir · ${OfflineModelStore.MODEL_SIZE}"
        }
        startStop.isEnabled = !pending && demoTranslator == null && !packLoading
        startStop.text = if (state.active) "Canlı çeviriyi durdur" else "Canlı çeviriyi başlat"
        testButton.isEnabled = controlsAvailable && demoTranslator == null
        renderPortalPanel(state)

        val caption = displayedCaption()
        val recognized = recognizedText()
        val source = caption?.source
        val differentReading = recognized?.takeUnless { it == source }
        status.text = if (state.active || panelTesting) {
            val message = state.voiceIssue ?: state.translationIssue ?: state.status
            state.subtitleDelayMs?.let { "$message\nKare → Türkçe · $it ms" } ?: message
        } else demoMessage ?: state.status
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
        if (demoMessage != null) demoSource
        else SessionState.current.let { it.recognizedSource ?: displayedCaption()?.source }

    private fun updateModelState() {
        val current = runCatching { OfflineModelStore.status(this) }.getOrElse {
            OfflineModelStore.State(OfflineModelStore.Stage.MISSING, "Model indirmesi şu anda açılmıyor.")
        }
        if (modelState == current) return
        modelState = current
        render()
    }

    private fun changeModelDownload() {
        if (modelState.stage == OfflineModelStore.Stage.DOWNLOADING) {
            runCatching { OfflineModelStore.cancel(this) }
            updateModelState()
            return
        }
        if (modelState.stage != OfflineModelStore.Stage.MISSING) return
        AlertDialog.Builder(this)
            .setTitle("Gelişmiş çevrimdışı model")
            .setMessage("${OfflineModelStore.MODEL_NAME} için bir defalık ${OfflineModelStore.MODEL_SIZE} indirme yapılır. " +
                "Uygulama, ekranda duran repliklerde bu modeli ek bir kalite denetimi için kullanır.")
            .setNeutralButton("Model lisansı") { _, _ -> openLink(OfflineModelStore.TERMS) }
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("İndir") { _, _ ->
                runCatching { OfflineModelStore.start(this) }.onFailure { error ->
                    Toast.makeText(this, error.message ?: "İndirme başlatılamadı.", Toast.LENGTH_LONG).show()
                }
                updateModelState()
            }
            .show()
    }

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
        packLoading = true
        render()
        val app = applicationContext
        Thread({
            val title = runCatching { load(app) }.getOrNull()
            if (title != null) prefs.edit().putString("packTitle", title).apply()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                packLoading = false
                Toast.makeText(this,
                    if (title == null) "Geçerli bir GameTranslator oyun paketi seç." else "Oyun paketi yüklendi.",
                    Toast.LENGTH_LONG,
                ).show()
                render()
            }
        }, "DraBornTranslate-pack-import").start()
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
        launchPanelTest.text = if (panel?.testing == true) "Portal testini durdur" else "Portal bağlantısını test et"
        panelStatus.text = state.portalIssue ?: when {
            panel?.previewing == true -> "Örnek yazı 3 dakika gösterilir. Portal’da oyuna dönüp yazının değiştiğini kontrol et."
            panel?.testing == true && panel.opened -> "Test paneli açıldı. Oyuna dönüp metnin Portal ekranında değiştiğini kontrol et."
            panel?.testing == true -> "Test bağlantısı hazır. Kopyalayıp Game Base üzerinden Portal’da aç."
            panel?.opened == true -> "Bir tarayıcı altyazıyı alıyor. Portal’da Yana Sabitle görünüyorsa seç."
            panel != null -> "PS5 evdeki ağdaysa yerel bağlantıyı kullan. PS5 uzaktaysa dış bağlantıyı hazırla."
            state.active -> "Portal bağlantısı hazırlanıyor…"
            else -> "Örnek yazıyla bağlantıyı test et veya canlı altyazıyı başlat. PS5 uzaktaysa dış bağlantıyı hazırla."
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
            showIdle("Portal bağlantı testi açılamadı. Uygulama açıkken yeniden dene.")
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
        val fields = column().apply { setPadding(dp(20), dp(8), dp(20), dp(12)) }
        val scroll = ScrollView(this).apply { addView(fields) }
        fields.addView(label("1. Komutu Termux’a yapıştır ve çalıştır. Termux açık kalsın.", 15, WHITE))
        fields.append(button("Komutu kopyala ve Termux’u aç") {
            copyText("Cloudflare tünel komutu", portalTunnelCommand(panel.url))
            val termux = packageManager.getLaunchIntentForPackage("com.termux")
            if (termux != null) startActivity(termux)
            else Toast.makeText(this, "Komut kopyalandı. Termux’u açıp yapıştır.", Toast.LENGTH_LONG).show()
        }, 6)
        fields.append(label("2. Termux’taki trycloudflare.com HTTPS adresini buraya yapıştır.", 15, WHITE), 12)
        val address = EditText(this).apply {
            hint = "https://…trycloudflare.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(tunnelAddress)
            maxLines = 2
        }
        fields.append(address, 4)
        val result = label("", 14, ACCENT).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val dialog = AlertDialog.Builder(this).setTitle("Uzak PS5 bağlantısı").setView(scroll)
            .setNegativeButton("Kapat", null).create()
        fun publicLink(): String? {
            if (SessionState.current.portalPanel?.url != panel.url) {
                result.text = "Panel oturumu değişti. Canlı altyazı ekranından yeni bağlantıyı hazırla."
                return null
            }
            val link = portalPublicLink(panel.url, address.text.toString())
            if (link == null) {
                address.error = "Termux’un verdiği HTTPS adresini yapıştır."
            } else {
                tunnelAddress = "https://${URI(link).host}"
            }
            return link
        }
        val test = button("Dış bağlantıyı kontrol et") {}.apply {
            setOnClickListener {
                val link = publicLink() ?: return@setOnClickListener
                isEnabled = false
                result.text = "Panel bağlantısı kontrol ediliyor…"
                checkTunnelLink(link) { message ->
                    if (dialog.isShowing) {
                        isEnabled = true
                        result.text = if (SessionState.current.portalPanel?.url == panel.url) message
                        else "Panel oturumu değişti. Yeni bağlantıyı hazırla."
                    }
                }
            }
        }
        fields.append(test, 4)
        fields.append(result, 6)
        fields.append(label("3. Tam bağlantıyı Game Base mesajında ana hesabına gönder. Portal’da aç ve varsa Yana Sabitle’yi seç.", 15, WHITE), 10)
        fields.append(button("Tam Portal bağlantısını kopyala") {
            val link = publicLink() ?: return@button
            copyText("Portal HTTPS paneli", link)
            result.text = "Bağlantı kopyalandı. Mesajı gönderdikten sonra PS App yayınını tam ekranda açık bırak."
        }, 6)
        fields.isFocusableInTouchMode = true
        fields.requestFocus()
        dialog.show()
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
        AlertDialog.Builder(this)
            .setTitle("Portal yan paneli")
            .setMessage(
                "1. Portal bağlantı testini başlat. PS5 uzaktaysa PS5 uzakta · Bağlantıyı hazırla ile HTTPS adresi al.\n\n" +
                    "2. Panel bağlantısını kopyala ve PlayStation App’te kendi PS5 hesabına Game Base mesajıyla gönder.\n\n" +
                    "3. Portal’da PS düğmesine basıp Game Base mesajındaki bağlantıyı aç. Options menüsünde Yana Sabitle görünüyorsa seçip oyuna dön. Test metninin ekranda değiştiğini kontrol et.\n\n" +
                    "4. Test açıkken PlayStation App’te Paylaşımlı Ekran yayınına dön ve DraBornTranslate’te canlı altyazıyı başlat. Panel bağlantın korunur.\n\n" +
                    "Kısa test sona ererse canlı altyazıyı başlat ve bağlantıyı yeniden hazırla.\n\n" +
                    "Yana Sabitle görünmezse bu Portal oturumunda sayfayı oyunun yanında açık tutamazsın.",
            )
            .setPositiveButton("Tamam", null)
            .show()
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
        val translator = GameTranslatorEngine(this, preferQuality = true)
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
            reader.read(exampleBitmap()) { result ->
                if (demoTranslator !== translator) return@read
                val original = result?.lines.orEmpty().joinToString("\n").trim()
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

    private fun showLicense() {
        AlertDialog.Builder(this)
            .setTitle("DraBornTranslate ${packageManager.getPackageInfo(packageName, 0).versionName}")
            .setMessage("GameTranslator v1.8.48 ve CouchSubs kaynaklarından yararlanır.\n" +
                "© Sinan Yorulmaz ve Sefflex · GNU AGPL v3\n\n" +
                "${OfflineModelStore.MODEL_NAME} · ${OfflineModelStore.MODEL_LICENSE}\n" +
                "llama.cpp · MIT\nGoogle ML Kit · Ekrandaki yazıyı tanıma")
            .setPositiveButton("GitHub") { _, _ -> openLink("https://github.com/DrabornEagle/DraBornTranslate") }
            .setNegativeButton("Model lisansı") { _, _ -> openLink(OfflineModelStore.TERMS) }
            .setNeutralButton("Lisans metni") { _, _ ->
                val terms = assets.open("LICENSE").bufferedReader().use { it.readText() }
                AlertDialog.Builder(this).setTitle("GNU AGPL v3").setMessage(terms).setPositiveButton("Kapat", null).show()
            }
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
        styleButton(this, false)
    }

    private fun styleButton(button: Button, primary: Boolean) {
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val selected = intArrayOf(android.R.attr.state_selected)
        val shaped = StateListDrawable().apply {
            addState(disabled, shape(0xFF1B2038.toInt(), OUTLINE, 14))
            addState(selected, shape(0xFF332F63.toInt(), LAVENDER, 14))
            addState(intArrayOf(), if (primary) {
                gradient(GradientDrawable.Orientation.LEFT_RIGHT, ACCENT, LAVENDER, 14)
            } else shape(0xFF242A4B.toInt(), OUTLINE, 14))
        }
        button.backgroundTintList = null
        button.background = RippleDrawable(ColorStateList.valueOf(0x3389C5FF), shaped, null)
        button.setPadding(dp(12), dp(10), dp(12), dp(10))
        button.setTextColor(ColorStateList(
            arrayOf(disabled, selected, intArrayOf()),
            intArrayOf(0xFF909AB8.toInt(), WHITE, if (primary) BACKGROUND else WHITE),
        ))
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
        val body = column().apply { visibility = View.GONE }
        val heading = button(title)
        heading.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        heading.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_disclosure, 0)
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
