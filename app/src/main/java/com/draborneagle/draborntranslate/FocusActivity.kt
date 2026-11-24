// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

class FocusActivity : Activity() {
    private val disk = Executors.newSingleThreadExecutor()
    private lateinit var canvas: FocusCanvas
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var liveCaption: TextView
    private lateinit var translate: Button
    private lateinit var original: Button
    private lateinit var frameTime: SeekBar
    private lateinit var sample: Button
    private lateinit var reader: FocusTranslator
    private var current: CaptureFrame? = null
    private var capture: SavedCapture? = null
    private var selection = FocusRegion.full
    private var result = emptyList<FocusText>()
    private var sourceOnly = false
    private var busy = false
    private var serial = 0
    private var followsLive = false
    private var lastLiveCheck = 0L
    private val captionObserver: (SessionState.State) -> Unit = { state ->
        liveCaption.text = state.caption?.turkish.orEmpty()
        liveCaption.visibility = if (state.caption == null) View.GONE else View.VISIBLE
    }
    private val liveObserver = {
        if (followsLive && !busy && android.os.SystemClock.elapsedRealtime() - lastLiveCheck >= 1_250L) showLive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = CaptureInbox.share(intent)
        capture = source?.copy(
            name = intent.getStringExtra("captureName") ?: source.name,
            modified = intent.getLongExtra("captureModified", source.modified),
            size = intent.getLongExtra("captureSize", source.size),
        )
        reader = FocusTranslator(this)
        val content = page()
        content.addView(heading("DraBorn Odak"))
        content.addView(text(capture?.name ?: "Oyun görüntüsü").apply { setTextColor(0xFFA6D9EA.toInt()) })
        canvas = FocusCanvas(this) { area ->
            if (!busy) {
                followsLive = false
                selection = area
                canvas.select(selection)
            }
        }.apply {
            background = panelColor(0xFF070D1D.toInt())
            contentDescription = "Çevrilecek alanı görüntü üzerinde sürükleyerek seç"
        }
        content.addView(canvas, LinearLayout.LayoutParams(-1, dp(260)).apply { topMargin = dp(12) })
        liveCaption = heading("").apply { textSize = 19f; visibility = View.GONE }
        content.addView(liveCaption)
        status = text("Çevirmek istediğin alanı sürükleyerek seç.").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        translate = action("Alanı çevir", primary = true) { translateArea() }
        original = action("İngilizceyi göster") {
            sourceOnly = !sourceOnly
            original.text = if (sourceOnly) "Türkçeyi göster" else "İngilizceyi göster"
            paintResult()
        }
        tools.addView(translate, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        tools.addView(original, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(tools)
        content.addView(action("Tüm görüntüyü seç") {
            selection = FocusRegion.full
            canvas.select(selection)
            translateArea()
        })
        frameTime = SeekBar(this).apply { max = 1000; contentDescription = "Klip konumu"; visibility = View.GONE }
        content.addView(frameTime)
        sample = action("Seçilen kareyi aç") {
            val duration = current?.durationMs ?: return@action
            capture?.let { loadCapture(it, duration * frameTime.progress / 1000, markInitial = false) }
        }.apply { visibility = View.GONE }
        content.addView(sample)
        if (capture == null) {
            content.addView(action("Güncel oyun karesini al") { showLive() })
            content.addView(action("Canlı alanı takip et") {
                followsLive = !followsLive
                status.text = if (followsLive) "Seçili alan canlı görüntüyle güncelleniyor." else "Canlı alan takibi durdu."
                if (followsLive) showLive()
            })
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rows)
        content.addView(action("Geri dön") { finish() })
        capture?.let { loadCapture(it) } ?: run {
            selection = FocusRegion.saved(this) ?: FocusRegion.full
            showLive()
        }
    }

    override fun onStart() {
        super.onStart()
        if (capture == null) {
            LiveFocusFrames.observe(liveObserver)
            SessionState.observe(captionObserver)
        }
    }

    override fun onStop() {
        LiveFocusFrames.remove(liveObserver)
        SessionState.remove(captionObserver)
        super.onStop()
    }

    private fun showLive() {
        if (busy) return
        lastLiveCheck = android.os.SystemClock.elapsedRealtime()
        val bitmap = LiveFocusFrames.snapshot()
        if (bitmap == null) {
            status.text = "Canlı çeviriyi DraBorn Odak ile başlat ve oyunu aç. İlk kare geldiğinde burada görünecek."
            return
        }
        setFrame(CaptureFrame(bitmap))
        if (followsLive) translateArea()
    }

    private fun loadCapture(item: SavedCapture, atMs: Long? = null, markInitial: Boolean = true) {
        val request = ++serial
        setBusy(true)
        status.text = if (item.video) "Klip karesi açılıyor…" else "Yakalama açılıyor…"
        disk.execute {
            val loaded = runCatching { CaptureInbox.load(this, item, atMs) }.getOrNull()
            val cached = if (atMs == null && item.modified > 0) CaptureInbox.cached(this, item) else null
            runOnUiThread {
                if (isDestroyed || request != serial) {
                    loaded?.bitmap?.recycle()
                    return@runOnUiThread
                }
                setBusy(false)
                if (loaded == null) {
                    status.text = "Yakalama açılamadı. PS App’te tekrar indir veya DraBornTranslate ile paylaş."
                    return@runOnUiThread
                }
                setFrame(loaded)
                if (markInitial && loaded.durationMs > 0) frameTime.progress = (loaded.timeMs * 1000 / loaded.durationMs).toInt()
                if (cached == null) translateArea()
                else {
                    result = cached
                    displayResult()
                }
            }
        }
    }

    private fun setFrame(frame: CaptureFrame) {
        val old = current
        current = frame
        result = emptyList()
        rows.removeAllViews()
        paintResult()
        canvas.select(selection)
        old?.bitmap?.recycle()
        frameTime.visibility = if (frame.durationMs > 0L) View.VISIBLE else View.GONE
        sample.visibility = frameTime.visibility
        if (frame.durationMs > 0L) sample.text = "Kareyi seç · ${frame.timeMs / 1000} sn"
        if (!followsLive) status.text = "Alanı sürükleyerek seç ve çevir."
    }

    private fun translateArea() {
        val frame = current?.bitmap ?: return
        if (busy) return
        val request = ++serial
        if (capture == null) selection.save(this)
        setBusy(true)
        status.text = "Oyun metni okunuyor…"
        reader.read(frame, selection) { lines ->
            if (isDestroyed || request != serial) return@read
            setBusy(false)
            result = lines.orEmpty()
            displayResult(lines == null)
        }
    }

    private fun displayResult(failed: Boolean = false) {
        status.text = when {
            failed -> "Yazı tanınamadı. Alanı daha dar seçip tekrar dene."
            result.isEmpty() -> "Bu alanda okunabilir metin bulunamadı."
            else -> "${result.count { it.turkish != null }} / ${result.size} metin Türkçe"
        }
        paintResult()
        rows.removeAllViews()
        for (entry in result) {
            val item = text(entry.turkish ?: entry.source).apply {
                setTextIsSelectable(true)
                setPadding(dp(12), dp(6), dp(12), dp(8))
                setTextColor(if (entry.turkish == null) 0xFFB2C1D8.toInt() else Color.WHITE)
                background = panelColor(0xFF202746.toInt())
            }
            rows.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    private fun paintResult() {
        val frame = current?.bitmap ?: return
        canvas.show(frame.width, frame.height, frame, if (sourceOnly) emptyList() else result)
        if (result.any { it.turkish != null } && !sourceOnly) canvas.select(null)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        translate.isEnabled = !value
        sample.isEnabled = !value
    }

    override fun onDestroy() {
        serial++
        reader.close()
        current?.bitmap?.recycle()
        disk.shutdownNow()
        super.onDestroy()
    }
}

class CaptureInboxActivity : Activity() {
    private val disk = Executors.newSingleThreadExecutor()
    private lateinit var records: LinearLayout
    private lateinit var toggle: Button
    private var refresh = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = page()
        content.addView(heading("PS App yakalamaları"))
        content.addView(text("PS App’te İndir’e dokun veya yakalamayı doğrudan DraBornTranslate ile paylaş."))
        toggle = action("", primary = true) { toggleAutomatic() }
        content.addView(toggle)
        content.addView(action("Fotoğraf veya klip seç") { pickMedia() })
        content.addView(action("Listeyi yenile") { reload() })
        records = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(records)
        content.addView(action("Geri dön") { finish() })
    }

    override fun onResume() {
        super.onResume()
        CaptureInbox.watch(this)
        CaptureInbox.enqueue(this)
        reload()
    }

    private fun reload() {
        toggle.text = if (CaptureInbox.enabled(this)) "Otomatik çeviriyi durdur" else "Otomatik çeviriyi aç"
        val id = ++refresh
        disk.execute {
            val files = CaptureInbox.items(this)
            val results = files.map { it to CaptureInbox.cached(this, it).orEmpty().count { line -> line.turkish != null } }
            runOnUiThread {
                if (isDestroyed || id != refresh) return@runOnUiThread
                records.removeAllViews()
                if (files.isEmpty()) records.addView(text("Telefonda erişilebilir PS App yakalaması yok."))
                for ((capture, count) in results) {
                    val type = if (capture.video) "Klip" else "Görüntü"
                    val suffix = if (count > 0) " · $count çeviri" else ""
                    records.addView(action("$type$suffix\n${capture.name}") { open(capture) })
                }
            }
        }
    }

    private fun toggleAutomatic() {
        if (CaptureInbox.enabled(this)) {
            CaptureInbox.setEnabled(this, false)
            reload()
            return
        }
        if (CaptureInbox.canRead(this)) enable()
        else requestPermissions(CaptureInbox.permissions(), 41)
    }

    private fun enable() {
        CaptureInbox.setEnabled(this, true)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 43)
        }
        reload()
    }

    @Deprecated("Android permission callback")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 41 && CaptureInbox.canRead(this)) enable()
    }

    @Suppress("DEPRECATION")
    private fun pickMedia() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }, 42)
    }

    @Deprecated("Android Activity result callback")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != 42 || result != RESULT_OK) return
        val uri = data?.data ?: return
        val clip = contentResolver.getType(uri)?.startsWith("video/") == true
        open(SavedCapture(uri, if (clip) "Seçilen klip" else "Seçilen görüntü", clip, 0, 0))
    }

    private fun open(capture: SavedCapture) {
        startActivity(Intent(this, FocusActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(capture.uri, if (capture.video) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("captureName", capture.name)
            putExtra("captureModified", capture.modified)
            putExtra("captureSize", capture.size)
        })
    }

    override fun onDestroy() {
        disk.shutdownNow()
        super.onDestroy()
    }
}

internal fun Activity.page(): LinearLayout {
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(22))
    }
    val page = ScrollView(this).apply {
        setBackgroundColor(0xFF0D1429.toInt())
        addView(content)
        isFillViewport = true
        setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            insets
        }
    }
    setContentView(page)
    return content
}

internal fun Activity.heading(value: String) = text(value).apply {
    textSize = 26f
    typeface = Typeface.DEFAULT_BOLD
    isAccessibilityHeading = true
    setTextColor(Color.WHITE)
}

internal fun Activity.text(value: String) = TextView(this).apply {
    text = value
    textSize = 15f
    setTextColor(0xFFBECAE1.toInt())
    setPadding(0, dp(6), 0, dp(8))
}

internal fun Activity.action(value: String, primary: Boolean = false, run: () -> Unit) = Button(this).apply {
    text = value
    textSize = 14f
    isAllCaps = false
    gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
    minHeight = dp(48)
    setTextColor(if (primary) 0xFF061926.toInt() else Color.WHITE)
    backgroundTintList = android.content.res.ColorStateList.valueOf(
        if (primary) 0xFF6CF5D7.toInt() else 0xFF2D3862.toInt(),
    )
    setOnClickListener { run() }
}

internal fun Activity.panelColor(value: Int) = GradientDrawable().apply {
    setColor(value)
    cornerRadius = dp(16).toFloat()
}
