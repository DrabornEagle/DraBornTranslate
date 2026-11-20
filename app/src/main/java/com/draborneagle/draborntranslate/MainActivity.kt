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
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

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

    private lateinit var portal: Button
    private lateinit var phone: Button
    private lateinit var instructions: TextView
    private lateinit var openSource: Button
    private lateinit var startStop: Button
    private lateinit var displayChoice: Button
    private lateinit var panelCard: LinearLayout
    private lateinit var panelStatus: TextView
    private lateinit var copyPanel: Button
    private lateinit var testPanel: Button
    private lateinit var status: TextView
    private lateinit var captionLabel: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var attribution: View
    private lateinit var bandPreview: BandPreview
    private lateinit var bandControl: SeekBar
    private lateinit var sizeControl: SeekBar
    private lateinit var testButton: Button

    private val observe: (SessionState.State) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = CaptureSettings(
            prefs.getInt("band", 65).coerceIn(0, CaptureSettings.MAX_TOP),
            prefs.getInt("size", 22).coerceIn(CaptureSettings.MIN_SIZE, CaptureSettings.MAX_SIZE),
            if (prefs.getBoolean("mobile", false)) Source.MOBILE else Source.PORTAL,
            SubtitleDisplay.entries.firstOrNull { it.name == prefs.getString("display", null) }
                ?: SubtitleDisplay.PHONE,
        )
        pending = savedInstanceState?.getBoolean("pending") ?: false
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
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        finishDemo()
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
        testPanel = button("Paneli bu telefonda sına") {
            SessionState.current.portalPanel?.url?.let(::openLink)
        }
        panelCard.append(testPanel, 6)
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
        settings.addView(label("Okuma bölgesi", 17, WHITE, bold = true))
        settings.append(label("Yeşil bandı oyundaki İngilizce altyazı hizasına getir.", 14, MUTED), 4)
        bandPreview = BandPreview()
        settings.addView(bandPreview, LinearLayout.LayoutParams(-1, dp(84)).apply { topMargin = dp(8) })
        bandControl = slider("Altyazı bölgesinin yüksekliği", CaptureSettings.MAX_TOP, config.bandTop) { value ->
            config = config.copy(bandTop = value)
            prefs.edit().putInt("band", value).apply()
            bandPreview.invalidate()
            bandPreview.contentDescription = "Oyun görüntüsünün yüzde ${value} hizasındaki okuma bandı"
        }
        settings.addView(bandControl)
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
        content.append(button("Kaynak kod ve lisans") { showLicense() }, 8)
    }

    private fun render() {
        val state = SessionState.current
        val mobile = config.source == Source.MOBILE
        instructions.text = if (mobile) {
            "1. PS Remote Play ile telefondan konsola bağlan.\n" +
                "2. Oyunun İngilizce altyazısını aç ve yatay tam ekrana geç.\n" +
                "3. Buraya dön, altyazıyı başlat ve Android paylaşımında PS Remote Play’i seç."
        } else {
            "1. Portal’da oyuna gir ve İngilizce altyazıyı aç.\n" +
                "2. PS5’te Game Base’den bir sesli sohbet aç ve Paylaşımlı Ekran’ı başlat.\n" +
                "3. Telefonda PlayStation App’te ikinci hesabınla yayını yatay tam ekran izle.\n" +
                "4. Buraya dön, altyazıyı başlat ve Android paylaşımında PlayStation App’i seç."
        }
        openSource.text = "${config.source.appName} uygulamasını aç"
        styleButton(portal, !mobile)
        styleButton(phone, mobile)
        portal.isSelected = !mobile
        phone.isSelected = mobile
        listOf(portal, phone, bandControl, sizeControl).forEach { it.isEnabled = !state.active && !pending }
        displayChoice.visibility = if (mobile) View.GONE else View.VISIBLE
        displayChoice.isEnabled = !state.active && !pending
        displayChoice.text = "Altyazı ekranı · ${config.display.label}"
        startStop.isEnabled = !pending && demoTranslator == null
        startStop.text = if (state.active) "Canlı altyazıyı durdur" else "Google ile canlı altyazıyı başlat"
        testButton.isEnabled = !state.active && !pending && demoTranslator == null
        renderPortalPanel(state)

        val caption = state.caption ?: demoCaption
        status.text = if (state.active) state.status else demoMessage ?: state.status
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
                val selected = choices[index]
                config = config.copy(display = selected)
                prefs.edit().putString("display", selected.name).apply()
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun renderPortalPanel(state: SessionState.State) {
        panelCard.visibility = if (config.showOnPortal) View.VISIBLE else View.GONE
        val panel = state.portalPanel
        copyPanel.visibility = if (panel == null) View.GONE else View.VISIBLE
        testPanel.visibility = copyPanel.visibility
        panelStatus.text = state.portalIssue ?: when {
            panel?.opened == true -> "Panel bir tarayıcıda açıldı. Portal’da oyunun yanında kalması için Yana Sabitle’yi seç."
            panel != null -> "Bağlantı hazır. Telefon ve PS5 aynı yerel ağda kalmalı."
            state.active -> "Portal bağlantısı hazırlanıyor…"
            else -> "Deneysel. PS5 bağlantıyı açabiliyor ve Yana Sabitle’yi sunuyorsa Türkçe altyazı Portal’da görünebilir."
        }
    }

    private fun copyPanelLink() {
        val url = SessionState.current.portalPanel?.url ?: return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("DraBornTranslate paneli", url))
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Portal paneli bağlantısı kopyalandı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPortalHelp() {
        AlertDialog.Builder(this)
            .setTitle("Portal yan paneli")
            .setMessage(
                "1. Telefonu ve PS5’i aynı yerel ağa bağla. Paylaşımlı Ekran yayınıyla canlı altyazıyı başlat.\n\n" +
                    "2. Panel bağlantısını kopyala. PlayStation App’te kendi PS5 hesabına Game Base mesajıyla gönder ve Paylaşımlı Ekran yayınına dön.\n\n" +
                    "3. Portal’da PS düğmesine bas. PS5’in Game Base mesajından bağlantıyı aç. Options menüsünde Yana Sabitle görünüyorsa seçip oyuna dön.\n\n" +
                    "Bu seçenek PS5 sistem yazılımına ve Uzaktan Oynama oturumuna göre değişebilir.",
            )
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun beginOrStop() {
        if (SessionState.current.active) {
            CaptureService.stop(this)
            return
        }
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
                    SessionState.update(SessionState.State(true, "Ekran paylaşımı başlatılıyor…"))
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
        SessionState.update(SessionState.State(status = message))
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
