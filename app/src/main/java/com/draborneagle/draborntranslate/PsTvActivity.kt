// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Android dashboard adapting the upstream translation dashboard and its PS5 → phone → TV stages.
 * The native console stream and DraBornTranslate's local translator own the live session.
 */
class PsTvActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private var tab = 0
    private val tabs = mutableListOf<Button>()
    private var controls: Dashboard? = null
    private var frame: Bitmap? = null
    private val listener: (SessionState.State) -> Unit = { display(it) }
    private val previewListener: () -> Unit = { refreshPreview() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: 0
        val (scroller, content) = scrollColumn()
        scroll = scroller
        body = content
        val root = stack()
        pageRoot(root)
        root.addView(titleBar("PS5 ve TV"))
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row().apply {
            setPadding(dp(13), dp(6), dp(13), dp(10))
            for ((index, label) in listOf("Çeviri", "Rehber", "Ayarlar").withIndex()) {
                val button = press(label) { showTab(index) }
                tabs.add(button)
                addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
        })
        showTab(tab)
    }

    override fun onStart() {
        super.onStart()
        SessionState.observe(listener)
        LiveFocusFrames.observe(previewListener)
    }

    override fun onResume() {
        super.onResume()
        display(SessionState.current)
        refreshPreview()
    }

    override fun onStop() {
        SessionState.remove(listener)
        LiveFocusFrames.remove(previewListener)
        super.onStop()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putInt("tab", tab)
        super.onSaveInstanceState(state)
    }

    private fun showTab(index: Int) {
        tab = index
        releasePreview()
        controls = null
        body.removeAllViews()
        for ((i, button) in tabs.withIndex()) {
            button.isSelected = i == tab
            button.setTextColor(if (i == tab) Palette.blue else Palette.quiet)
            button.background = filled(if (i == tab) 0xFF233C5A.toInt() else Palette.surface, 15, Palette.border)
        }
        when (tab) {
            0 -> showDashboard()
            1 -> showGuide()
            2 -> showSettings()
        }
        scroll.scrollTo(0, 0)
    }

    private fun showDashboard() {
        val hero = stack().apply {
            background = blend(0xFF193E53.toInt(), 0xFF252144.toInt())
            setPadding(dp(16), dp(17), dp(16), dp(15))
        }
        body.addBelow(hero)
        val headline = copy("", 26, Palette.white, true).apply { isAccessibilityHeading = true }
        val status = copy("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val start = press("Çeviriyi başlat", Palette.mint) { startOrStop() }
        hero.addView(headline)
        hero.addBelow(status, 5)
        hero.addBelow(start, 14)

        val console = copy("", 12, Palette.blue)
        val phone = copy("", 12, Palette.mint)
        val tv = copy("", 12, Palette.purple)
        body.addBelow(row().apply {
            addDevice("PS5", R.drawable.ic_controller, Palette.blue, console) { pair() }
            addDevice("Telefon", R.drawable.ic_phone_screen, Palette.mint, phone) {
                startActivity(Intent(this@PsTvActivity, FocusActivity::class.java))
            }
            addDevice("TV", R.drawable.ic_tv_screen, Palette.purple, tv) { openTv() }
        }, 10)

        val live = stack().apply {
            background = filled(Palette.surface, 18, Palette.border)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        body.addBelow(live, 15)
        live.addView(copy("Son çeviri", 17, Palette.white, true))
        val caption = copy("Oyundaki İngilizce altyazıyı aç.", 17, Palette.quiet)
        live.addBelow(caption, 7)
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = filled(Palette.background, 13)
            clipToOutline = true
            contentDescription = "PS5 oyun görüntüsünün canlı önizlemesi"
            visibility = View.GONE
        }
        live.addView(preview, LinearLayout.LayoutParams(-1, dp(165)).apply { topMargin = dp(10) })

        body.addBelow(navTile(
            "TV ekranını hazırla", "TV tarayıcısında altyazı panelini aç.",
            R.drawable.ic_tv_screen, Palette.purple,
        ) { openTv() }, 11)
        body.addBelow(navTile(
            "DraBorn Odak", null, R.drawable.ic_focus_region, Palette.peach,
        ) { startActivity(Intent(this, FocusActivity::class.java)) }, 8)
        controls = Dashboard(headline, status, start, console, phone, tv, caption, preview)
        display(SessionState.current)
        refreshPreview()
    }

    private fun LinearLayout.addDevice(title: String, icon: Int, accent: Int, detail: TextView, action: () -> Unit) {
        addView(stack().apply {
            minimumHeight = dp(100)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            gravity = Gravity.CENTER_HORIZONTAL
            background = filled(Palette.surface, 15, Palette.border)
            addView(glyph(icon, accent), LinearLayout.LayoutParams(dp(22), dp(22)))
            addBelow(copy(title, 14, Palette.white, true).apply { gravity = Gravity.CENTER }, 5)
            addBelow(detail.apply { gravity = Gravity.CENTER }, 2)
            setOnClickListener { action() }
            isFocusable = true
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
    }

    private fun display(state: SessionState.State) {
        val dashboard = controls ?: return
        val paired = ConsolePairing.load(this)
        val active = state.active && state.source == Source.PS5
        val status = when {
            active && state.videoVisible -> "Canlı görüntü"
            active -> "PS5’e bağlanılıyor"
            paired != null -> "Çeviriye hazır"
            else -> "PS5 kurulumu"
        }
        dashboard.title.text = status
        dashboard.status.text = when {
            active || (paired != null && state.source == Source.PS5) ->
                state.translationIssue ?: state.status
            state.active -> "Diğer bağlantıda canlı çeviri açık."
            paired != null -> "Eşleşen konsol · ${paired.name}"
            else -> "Telefonu ve PS5’i aynı ağa bağla. İlk kullanımda konsolu eşleştir."
        }
        dashboard.start.text = when {
            active -> "Çeviriyi durdur"
            state.active -> "Açık çeviriye dön"
            paired != null -> "Çeviriyi başlat"
            else -> "PS5’i eşleştir"
        }
        dashboard.console.text = when {
            active && state.videoVisible -> "Bağlı"
            active -> "Bağlanıyor"
            paired != null -> paired.name
            else -> "Eşleşmedi"
        }
        dashboard.phone.text = if (active) "Çeviri açık" else "Hazır"
        val panel = state.portalPanel?.takeIf { state.source == Source.PS5 }
        dashboard.tv.text = when {
            panel?.opened == true -> "Panel açık"
            panel != null -> "Panel hazır"
            else -> "Kurulum"
        }
        val translation = (state.caption ?: state.lastCaption).takeIf { state.source == Source.PS5 }?.turkish
        dashboard.caption.text = translation ?: "Oyundaki İngilizce altyazıyı aç."
        dashboard.caption.setTextColor(if (translation == null) Palette.quiet else Palette.white)
        if (!active) releasePreview()
    }

    private fun refreshPreview() {
        val preview = controls?.preview ?: return
        val state = SessionState.current
        if (state.source != Source.PS5 || !state.active || !state.videoVisible) return
        val next = LiveFocusFrames.snapshot() ?: return
        val previous = frame
        frame = next
        preview.setImageBitmap(next)
        preview.visibility = View.VISIBLE
        previous?.recycle()
    }

    private fun releasePreview() {
        controls?.preview?.apply {
            setImageDrawable(null)
            visibility = View.GONE
        }
        frame?.recycle()
        frame = null
    }

    private fun startOrStop() {
        val active = SessionState.current
        if (active.active) {
            val source = active.source
            if (source != null && source != Source.PS5) startActivity(connectionIntent(this, source))
            else CaptureService.stop(this)
            return
        }
        if (ConsolePairing.load(this) == null || !ConsolePairing.available) {
            pair()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !capturePreferences().getBoolean("askedNotifications", false)) {
            capturePreferences().edit().putBoolean("askedNotifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 62)
            return
        }
        connect()
    }

    @Deprecated("Android permission callback")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 62) connect()
    }

    private fun connect() {
        if (SessionState.current.active) return
        val request = readCaptureSettings(Source.PS5)
        val state = SessionState.current
        SessionState.update(state.copy(active = true, source = Source.PS5, status = "PS5 bağlantısı başlatılıyor…"))
        runCatching { CaptureService.startConsole(this, request) }.onFailure {
            SessionState.update(SessionState.State(source = Source.PS5, status = "PS5 bağlantısı başlatılamadı."))
        }
    }

    private fun pair() {
        if (!SessionState.current.active) startActivity(Intent(this, ConsoleSetupActivity::class.java))
        else AlertDialog.Builder(this).setMessage("Konsol eşlemesini değiştirmek için açık çeviriyi durdur.")
            .setPositiveButton("Kapat", null).show()
    }

    private fun openTv() = startActivity(Intent(this, TvPanelActivity::class.java))

    private fun showGuide() {
        body.addBelow(copy("PS5’te kurulum", 23, Palette.white, true))
        body.addBelow(copy("PS5’te Ayarlar → Sistem → Uzaktan Oynama → Cihazı Eşleştir yolunu aç."), 8)
        body.addBelow(press("Konsol eşleme ekranı", Palette.blue) { pair() }, 11)
        body.addBelow(copy("TV’de oynamak", 20, Palette.white, true), 22)
        body.addBelow(copy("Ana hesabınla TV’de oyna. Telefon bağlantısı için PS5’te ikinci PSN kullanıcısını eşleştir ve oyunun İngilizce altyazısını aç."), 7)
        body.addBelow(press("TV panelini aç") { openTv() }, 11)
        body.addBelow(press("PS Remote Play ile telefonda oyna") { startActivity(connectionIntent(this, Source.MOBILE)) }, 10)
    }

    private fun showSettings() {
        val prefs = capturePreferences()
        val settings = readCaptureSettings(Source.PS5)
        body.addBelow(copy("Altyazı ayarları", 23, Palette.white, true))
        body.addBelow(press("Çeviri çıkışı · ${outputName(settings.display)}") {
            val options = listOf(SubtitleDisplay.BOTH, SubtitleDisplay.PHONE, SubtitleDisplay.PORTAL)
            AlertDialog.Builder(this).setTitle("Çeviri çıkışı")
                .setSingleChoiceItems(options.map(::outputName).toTypedArray(), options.indexOf(settings.display)) { dialog, index ->
                    prefs.edit().putString("display.PS5", options[index].name).apply()
                    dialog.dismiss()
                    showTab(2)
                }.setNegativeButton("Kapat", null).show()
        }, 11)
        val modes = listOf("Altyazı bölgesi", "DraBorn Odak", "Altyazı + DraBorn Odak", "Tüm oyun metinleri")
        val current = if (settings.readingArea == ReadingArea.GAME_TEXT) 3 else if (!settings.focus) 0
            else if (settings.captionEnabled) 2 else 1
        body.addBelow(press(modes[current]) {
            AlertDialog.Builder(this).setTitle("Çeviri modu")
                .setSingleChoiceItems(modes.toTypedArray(), current) { dialog, index ->
                    prefs.edit().putString("readingArea", if (index == 3) ReadingArea.GAME_TEXT.name else ReadingArea.SUBTITLES.name)
                        .putBoolean("focus", index == 1 || index == 2)
                        .putBoolean("subtitles", index != 1).apply()
                    dialog.dismiss()
                    showTab(2)
                }.setNegativeButton("Kapat", null).show()
        }, 8)
        addSlider("Altyazı bandının yüksekliği", "band", settings.bandTop, 0, CaptureSettings.MAX_TOP)
        addSlider("Yazı boyutu", "size", settings.textSize, CaptureSettings.MIN_SIZE, CaptureSettings.MAX_SIZE)
        if (SessionState.current.active) body.addBelow(copy("Bu ayarlar bir sonraki canlı oturumda uygulanır.", 13), 5)
        body.addBelow(press("Oyun paketleri") { startActivity(Intent(this, GamePacksActivity::class.java)) }, 12)
        body.addBelow(press("PS5 eşlemesi") { pair() }, 8)
    }

    private fun addSlider(label: String, key: String, initial: Int, min: Int, max: Int) {
        val value = copy("$label · $initial", 14, Palette.white)
        body.addBelow(value, 16)
        body.addBelow(SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = initial
            contentDescription = label
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, changed: Int, fromUser: Boolean) {
                    if (fromUser) {
                        value.text = "$label · $changed"
                        capturePreferences().edit().putInt(key, changed).apply()
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, 3)
    }

    override fun onDestroy() {
        releasePreview()
        super.onDestroy()
    }

    private fun outputName(display: SubtitleDisplay) = when (display) {
        SubtitleDisplay.BOTH -> "Telefon ve TV"
        SubtitleDisplay.PORTAL -> "TV tarayıcısı"
        else -> "Telefon"
    }

    private data class Dashboard(
        val title: TextView, val status: TextView, val start: Button, val console: TextView,
        val phone: TextView, val tv: TextView, val caption: TextView, val preview: ImageView,
    )
}

class TvPanelActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var url: TextView
    private lateinit var copyAddress: Button
    private lateinit var openAddress: Button
    private lateinit var test: Button
    private val listener: (SessionState.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = contentPage("TV ekranını hazırla", Palette.purple)
        body.addBelow(copy("TV altyazı paneli", 25, Palette.white, true))
        body.addBelow(copy("Telefon ve TV aynı ağda olmalı. Panel adresini TV’nin tarayıcısına yaz."), 7)
        test = press("Örnek altyazıyı göster", Palette.purple) {
            val state = SessionState.current
            if (state.source == Source.PS5 && state.portalPanel?.testing == true) CaptureService.stop(this)
            else if (state.source == Source.PS5 && state.active && state.portalPanel != null) CaptureService.togglePreview(this)
            else if (!state.active && state.portalPanel?.testing != true) {
                CaptureService.testPanel(this, readCaptureSettings(Source.PS5).textSize, Source.PS5)
            }
        }
        body.addBelow(test, 14)
        info = copy("")
        body.addBelow(info, 11)
        url = copy("", 17, Palette.blue, true).apply { setTextIsSelectable(true) }
        body.addBelow(url, 10)
        copyAddress = press("Adresi kopyala") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("TV paneli", url.text))
        }
        openAddress = press("Telefonda dene") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.text.toString())))
        }
        body.addBelow(copyAddress, 11)
        body.addBelow(openAddress, 8)
    }

    override fun onStart() {
        super.onStart()
        SessionState.observe(listener)
    }

    override fun onStop() {
        SessionState.remove(listener)
        super.onStop()
    }

    private fun render(state: SessionState.State) {
        val panel = state.portalPanel?.takeIf { state.source == Source.PS5 }
        info.text = when {
            panel?.opened == true -> "TV paneli bağlantısı açık."
            panel != null -> "Bu adresi TV tarayıcısında aç."
            state.active && state.source != Source.PS5 -> "TV panelini açmak için diğer canlı oturumu durdur."
            state.active -> state.portalIssue ?: "TV çıkışını PS5 ve TV → Ayarlar’dan seçip çeviriyi tekrar başlat."
            else -> "Örnek altyazıyla TV bağlantısını dene."
        }
        test.text = when {
            panel?.testing == true -> "Örnek altyazıyı durdur"
            panel?.previewing == true -> "Canlı çeviriye dön"
            else -> "Örnek altyazıyı göster"
        }
        test.isEnabled = (panel != null || !state.active) &&
            !(state.source != Source.PS5 && state.portalPanel?.testing == true)
        url.text = panel?.url.orEmpty()
        copyAddress.visibility = if (panel == null) View.GONE else View.VISIBLE
        openAddress.visibility = copyAddress.visibility
        url.visibility = copyAddress.visibility
    }
}
