// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

private const val BACKGROUND = 0xFF0B111C.toInt()
private const val PANEL = 0xFF131E30.toInt()
private const val WHITE = 0xFFF3F6FB.toInt()
private const val MUTED = 0xFFAEBBD1.toInt()
private const val ACCENT = 0xFF7CF0D2.toInt()

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("capture", MODE_PRIVATE) }
    private lateinit var config: CaptureSettings
    private var pending = false
    private var demoMessage: String? = null
    private var demoCaption: Caption? = null
    private var demoTranslator: DeviceTranslator? = null
    private var demoReader: SubtitleReader? = null
    private var voicePreview: TurkishVoice? = null
    private var tunnelAddress = ""
    private var tunnelSession: String? = null

    private lateinit var portal: Button
    private lateinit var phone: Button
    private lateinit var instructions: TextView
    private lateinit var openSource: Button
    private lateinit var startStop: Button
    private lateinit var displayChoice: Button
    private lateinit var readingChoice: Button
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
    private lateinit var attribution: View
    private lateinit var bandPreview: BandPreview
    private lateinit var bandSettings: LinearLayout
    private lateinit var bandControl: SeekBar
    private lateinit var sizeControl: SeekBar
    private lateinit var testButton: Button
    private lateinit var voiceButton: Button

    private val observe: (SessionState.State) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = CaptureSettings(
            prefs.getInt("band", 65).coerceIn(0, CaptureSettings.MAX_TOP),
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
        render()
    }

    override fun onStart() {
        super.onStart()
        SessionState.observe(observe)
    }

    override fun onStop() {
        SessionState.remove(observe)
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
        val viewport = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
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
            setPadding(dp(20), dp(22), dp(20), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                minOf(resources.displayMetrics.widthPixels, dp(610)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            )
        }
        scroll.addView(content)
        content.addView(label("DraBornTranslate", 32, WHITE, bold = true))
        content.append(label("Canlı Türkçe oyun altyazısı", 16, MUTED), 6)

        val inputs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        portal = button("PS Portal") { select(Source.PORTAL) }
        phone = button("Telefonda oyna") { select(Source.MOBILE) }
        inputs.addView(portal, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
        inputs.addView(phone, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        content.append(inputs, 22)

        displayChoice = button("") { selectDisplay() }
        content.append(displayChoice, 14)
        readingChoice = button("") { selectReadingArea() }
        content.append(readingChoice, 6)

        val guide = card()
        content.append(guide, 14)
        instructions = label("", 15, MUTED).apply { setLineSpacing(dp(4).toFloat(), 1f) }
        guide.addView(instructions)
        openSource = button("") { openSourceApp(config.source, offerInstall = true) }
        guide.append(openSource, 14)

        startStop = button("Google ile canlı altyazıyı başlat") { beginOrStop() }
        styleButton(startStop, true)
        content.append(startStop, 14)

        panelCard = card()
        content.append(panelCard, 14)
        panelCard.addView(label("Portal yan paneli", 17, WHITE, bold = true))
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

        val live = card()
        content.append(live, 14)
        status = label("", 14, ACCENT, bold = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        live.addView(status)
        captionLabel = label("", 23, WHITE, bold = true)
        live.append(captionLabel, 9)
        sourceLabel = label("", 14, MUTED)
        live.append(sourceLabel, 4)
        attribution = translationAttribution(this)
        live.addView(attribution, LinearLayout.LayoutParams(dp(152), dp(19)).apply { topMargin = dp(8) })

        val settings = card()
        content.append(settings, 18)
        bandSettings = column()
        settings.addView(bandSettings)
        bandSettings.addView(label("Altyazı hizası", 17, WHITE, bold = true))
        bandSettings.append(label("Yeşil bandı oyundaki İngilizce altyazı hizasına getir.", 14, MUTED), 4)
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
        testButton = button("Google ile örnek altyazıyı çevir") { runDemo() }
        content.append(testButton, 14)
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
        styleButton(portal, !mobile)
        styleButton(phone, mobile)
        portal.isSelected = !mobile
        phone.isSelected = mobile
        val panelTesting = state.portalPanel?.testing == true
        val controlsAvailable = !state.active && !pending && !panelTesting
        listOf(portal, phone, bandControl, sizeControl).forEach { it.isEnabled = controlsAvailable }
        displayChoice.visibility = if (mobile) View.GONE else View.VISIBLE
        displayChoice.isEnabled = controlsAvailable
        displayChoice.text = "Altyazı ekranı · ${config.display.label}"
        readingChoice.isEnabled = controlsAvailable
        readingChoice.text = "Çevrilecek alan · ${config.readingArea.label}"
        bandSettings.visibility = if (config.readingArea == ReadingArea.SUBTITLES) View.VISIBLE else View.GONE
        voiceButton.visibility = if (config.speakOnPhone) View.VISIBLE else View.GONE
        voiceButton.isEnabled = controlsAvailable
        startStop.isEnabled = !pending && demoTranslator == null
        startStop.text = if (state.active) "Canlı altyazıyı durdur" else "Google ile canlı altyazıyı başlat"
        testButton.isEnabled = controlsAvailable && demoTranslator == null
        renderPortalPanel(state)

        val caption = state.caption ?: demoCaption
        status.text = if (state.active || panelTesting) state.voiceIssue ?: state.status else demoMessage ?: state.status
        captionLabel.text = caption?.turkish.orEmpty()
        sourceLabel.text = caption?.source.orEmpty()
        listOf(captionLabel, sourceLabel, attribution).forEach {
            it.visibility = if (caption == null) View.GONE else View.VISIBLE
        }
    }

    private fun select(value: Source) {
        config = config.copy(source = value)
        prefs.edit().putBoolean("mobile", value == Source.MOBILE).apply()
        render()
    }

    private fun selectDisplay() {
        val choices = SubtitleDisplay.entries
        AlertDialog.Builder(this)
            .setTitle("Altyazı ekranı")
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
        tunnelWizard.isEnabled = panel?.testing == false && !pending
        previewPanel.visibility = if (state.active && panel != null && !panel.testing) View.VISIBLE else View.GONE
        previewPanel.text = if (panel?.previewing == true) "Canlı çeviriye dön" else "Portal’da örnek yazı göster"
        launchPanelTest.visibility = if (state.active) View.GONE else View.VISIBLE
        launchPanelTest.isEnabled = !pending && demoTranslator == null
        launchPanelTest.text = if (panel?.testing == true) "Portal testini durdur" else "Portal bağlantısını test et"
        panelStatus.text = state.portalIssue ?: when {
            panel?.previewing == true -> "Örnek yazı 3 dakika gösterilir. Portal’da oyuna dönüp yazının değiştiğini kontrol et."
            panel?.testing == true && panel.opened -> "Test paneli açıldı. Oyuna dönüp metnin Portal ekranında değiştiğini kontrol et."
            panel?.testing == true -> "Test bağlantısı hazır. Kopyalayıp Game Base üzerinden Portal’da aç."
            panel?.opened == true -> "Panel bir tarayıcıda açıldı. Portal’da Yana Sabitle görünüyorsa seç."
            panel != null -> "PS5 evdeki ağdaysa yerel bağlantıyı kullan. PS5 uzaktaysa dış bağlantıyı hazırla."
            state.active -> "Portal bağlantısı hazırlanıyor…"
            else -> "Uzak PS5 için önce canlı altyazıyı başlat. Aynı ağdaki PS5 için Portal bağlantısını test et."
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
        if (panel.testing) return
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
                        502, 503, 504, 530 -> "Tünel panele ulaşamadı ($code). Canlı altyazıyı ve Termux oturumunu kontrol et."
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
                "1. Telefonu ve PS5’i aynı yerel ağa bağla. Portal bağlantı testini başlat.\n\n" +
                    "2. Panel bağlantısını kopyala ve PlayStation App’te kendi PS5 hesabına Game Base mesajıyla gönder.\n\n" +
                    "3. Portal’da PS düğmesine basıp Game Base mesajındaki bağlantıyı aç. Options menüsünde Yana Sabitle görünüyorsa seçip oyuna dön. Test metninin ekranda değiştiğini kontrol et.\n\n" +
                    "4. Test açıkken PlayStation App’te Paylaşımlı Ekran yayınına dön ve DraBornTranslate’te canlı altyazıyı başlat. Panel bağlantın korunur.\n\n" +
                    "PS5 uzaktaysa canlı altyazıyı başlatıp PS5 uzakta · Bağlantıyı hazırla düğmesini kullan.\n\n" +
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
            else SessionState.State(status = message))
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

    private fun runDemo() {
        val translator = DeviceTranslator().also { demoTranslator = it }
        demoMessage = "Örnek için cihazdaki dil modeli hazırlanıyor…"
        demoCaption = null
        render()
        translator.prepare { ready ->
            if (demoTranslator !== translator) return@prepare
            if (!ready) {
                completeDemo("Dil modeli hazırlanamadı. İnterneti kontrol edip yeniden dene.")
                return@prepare
            }
            demoMessage = "Örnek görseldeki İngilizce altyazı okunuyor…"
            render()
            val reader = SubtitleReader().also { demoReader = it }
            reader.read(exampleBitmap()) { lines ->
                if (demoTranslator !== translator) return@read
                val original = lines.orEmpty().joinToString(" ").trim()
                if (original.isEmpty()) {
                    completeDemo("Örnek görseldeki yazı okunamadı.")
                    return@read
                }
                translator.translate(original) { result ->
                    if (demoTranslator !== translator) return@translate
                    if (result.isNullOrBlank()) completeDemo("Örnek altyazı çevrilemedi.")
                    else completeDemo("Örnek altyazı çevrildi.", Caption(original, result))
                }
            }
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
        canvas.drawText("We need to reach the bridge before dark.", 480f, 108f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        })
        return bitmap
    }

    private fun showLicense() {
        AlertDialog.Builder(this)
            .setTitle("DraBornTranslate ${packageManager.getPackageInfo(packageName, 0).versionName}")
            .setMessage("GameTranslator v1.8.48 ve CouchSubs kaynaklarından yararlanır.\n" +
                "© Sinan Yorulmaz ve Sefflex · GNU AGPL v3\n\n" +
                "Çeviri, Google ML Kit ile cihazda yapılır. Google çevirilerin doğruluğu, güvenilirliği, satılabilirliği, belirli bir amaca uygunluğu ve hak ihlali içermemesi konusunda açık veya örtük garanti vermez.")
            .setPositiveButton("GitHub") { _, _ -> openLink("https://github.com/DrabornEagle/DraBornTranslate") }
            .setNeutralButton("Lisans metni") { _, _ ->
                val terms = assets.open("LICENSE").bufferedReader().use { it.readText() }
                AlertDialog.Builder(this).setTitle("GNU AGPL v3").setMessage(terms).setPositiveButton("Kapat", null).show()
            }
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

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 14f
        isAllCaps = false
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minimumHeight = dp(48)
        setOnClickListener { action() }
        styleButton(this, false)
    }

    private fun styleButton(button: Button, primary: Boolean) {
        button.background = shape(if (primary) ACCENT else 0xFF223249.toInt())
        button.setTextColor(if (primary) BACKGROUND else WHITE)
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

    private fun card() = column().apply {
        background = shape(PANEL)
        setPadding(dp(16), dp(15), dp(16), dp(15))
    }

    private fun shape(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(15).toFloat()
    }

    private fun LinearLayout.append(view: View, gap: Int) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }

    private inner class BandPreview : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            contentDescription = "Oyun görüntüsündeki altyazı okuma bölgesi"
        }

        override fun onDraw(canvas: Canvas) {
            val boxHeight = height.toFloat() - dp(4)
            val boxWidth = boxHeight * 16 / 9
            val left = (width - boxWidth) / 2
            val top = dp(2).toFloat()
            val radius = dp(7).toFloat()
            paint.color = BACKGROUND
            canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight, radius, radius, paint)
            paint.color = 0xFF34695F.toInt()
            val bandTop = top + boxHeight * config.bandTop / 100
            val bandHeight = boxHeight * CaptureSettings.BAND_HEIGHT / 100
            canvas.drawRoundRect(left + boxWidth * 0.06f, bandTop, left + boxWidth * 0.94f,
                bandTop + bandHeight, radius / 2, radius / 2, paint)
            paint.color = ACCENT
            val centerY = bandTop + bandHeight / 2
            canvas.drawRoundRect(left + boxWidth * 0.24f, centerY - dp(1), left + boxWidth * 0.76f,
                centerY + dp(1), dp(1).toFloat(), dp(1).toFloat(), paint)
        }
    }

    companion object {
        private const val OVERLAY = 10
        private const val NOTIFICATION = 11
        private const val PROJECTION = 12
    }
}
