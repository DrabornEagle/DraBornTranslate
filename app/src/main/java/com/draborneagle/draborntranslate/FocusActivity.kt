// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
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
    private lateinit var emptyPreview: LinearLayout
    private lateinit var frameLabel: TextView
    private lateinit var entire: Button
    private lateinit var follow: Button
    private lateinit var enlarge: Button
    private var sourcesDialog: Dialog? = null
    private var viewerDialog: Dialog? = null
    private var resumeCaptureLookup = false
    private var foreground = false
    private var pendingFrame = false
    private var revisionSeen = -1L
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
        if (busy) Unit
        else if (pendingFrame || (followsLive && SystemClock.elapsedRealtime() - lastLiveCheck >= 1_250L)) showLive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = CaptureInbox.share(intent)
        capture = source?.copy(
            name = intent.getStringExtra("captureName") ?: source.name,
            modified = intent.getLongExtra("captureModified", source.modified),
            size = intent.getLongExtra("captureSize", source.size),
            added = intent.getLongExtra("captureAdded", source.added),
        )
        resumeCaptureLookup = savedInstanceState?.getBoolean("resumeCaptureLookup") == true
        reader = FocusTranslator(this)
        val content = contentPage(VISUAL_TRANSLATION, Palette.peach)
        val selected = capture
        val title = when {
            selected == null -> "Oyun görüntüsü"
            selected.added > 0 -> "${if (selected.video) "Klip" else "Görüntü"} · ${captureDate(selected.added)}"
            else -> selected.name
        }
        frameLabel = copy(title, 13, Palette.peach, true)
        enlarge = press("Büyüt") { showFullScreen() }.apply {
            contentDescription = "Görüntüyü tam ekranda aç"
            visibility = View.GONE
        }
        val workspace = stack().apply {
            background = blend(0xFF2C2044.toInt(), 0xFF161E35.toInt(), 23)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(row().apply {
                addView(frameLabel, LinearLayout.LayoutParams(0, -2, 1f))
                addView(enlarge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            })
        }
        canvas = FocusCanvas(this) { area ->
            if (!busy) {
                updateFollow(false)
                selection = area
                canvas.select(selection)
            }
        }.apply {
            contentDescription = "Görüntüyü büyütmek için dokun, alan seçmek için sürükle"
            setOnClickListener { showFullScreen() }
            visibility = View.GONE
        }
        val preview = FrameLayout(this).apply {
            background = filled(0xFF0A1020.toInt(), 17, Palette.border)
            clipToOutline = true
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
        }
        emptyPreview = stack().apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(glyph(R.drawable.ic_focus_region, Palette.peach), LinearLayout.LayoutParams(dp(49), dp(49)))
            addBelow(copy("Çevirilecek görüntüyü aç", 17, Palette.white, true).apply { gravity = Gravity.CENTER }, 10)
            addBelow(copy("Canlı kare, PS App yakalaması veya telefondan bir dosya seç.", 13).apply {
                gravity = Gravity.CENTER
            }, 4)
        }
        preview.addView(emptyPreview, FrameLayout.LayoutParams(-1, -1))
        workspace.addView(preview, LinearLayout.LayoutParams(-1, dp(218)).apply { topMargin = dp(8) })
        status = copy("", 13).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        workspace.addBelow(status, 9)
        content.addBelow(workspace)

        if (selected == null) {
            val quick = stack().apply {
                addView(press("Güncel oyun karesini al", Palette.peach) { takeCurrentFrame() })
                addBelow(actionRow(
                    press("Son PS App yakalaması", Palette.blue) { openLatestCapture() },
                    press("Kaynak seç") { showSources(SessionState.current) },
                ), 8)
                addBelow(actionRow(
                    press("Fotoğraf veya klip seç") {
                        @Suppress("DEPRECATION")
                        startActivityForResult(mediaPicker(), PICK_MEDIA)
                    },
                    press("Canlı alanı takip et") {
                        updateFollow(!followsLive)
                        if (followsLive) showLive(promptSource = true)
                    }.also { follow = it },
                ), 8)
            }
            content.addBelow(quick, 12)
        }

        liveCaption = copy("", 17, Palette.mint, true).apply {
            visibility = View.GONE
            background = filled(0xFF172C37.toInt(), 15)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        content.addBelow(liveCaption, 9)
        frameTime = SeekBar(this).apply { max = 1000; contentDescription = "Klip konumu"; visibility = View.GONE }
        content.addBelow(frameTime, 9)
        sample = press("Seçilen kareyi aç") {
            val duration = current?.durationMs ?: return@press
            capture?.let { loadCapture(it, duration * frameTime.progress / 1000, markInitial = false) }
        }.apply { visibility = View.GONE }
        content.addBelow(sample)

        translate = press("Alanı çevir", Palette.mint) { translateArea() }
        original = press("İngilizceyi göster") {
            sourceOnly = !sourceOnly
            original.text = if (sourceOnly) "Türkçeyi göster" else "İngilizceyi göster"
            if (result.isEmpty()) paintResult() else displayResult()
        }
        content.addBelow(actionRow(translate, original), 11)
        entire = press("Tüm görüntüyü çevir") {
            updateFollow(false)
            selection = FocusRegion.full
            canvas.select(selection)
            translateArea()
        }
        content.addBelow(entire, 8)
        rows = stack()
        content.addBelow(rows, 9)
        updateControls()
        capture?.let { loadCapture(it) } ?: run {
            selection = FocusRegion.saved(this) ?: FocusRegion.full
            showLive()
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (capture == null) {
            LiveFocusFrames.observe(liveObserver)
            SessionState.observe(captionObserver)
        }
    }

    override fun onStop() {
        foreground = false
        LiveFocusFrames.remove(liveObserver)
        SessionState.remove(captionObserver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (resumeCaptureLookup) {
            resumeCaptureLookup = false
            findLatestCapture {
                status.text = "PS App’te Kütüphane → Yakalamalar → İndir yolunu kullan."
            }
        } else if (capture == null && (current == null || pendingFrame) && !busy) showLive()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("resumeCaptureLookup", resumeCaptureLookup)
        super.onSaveInstanceState(outState)
    }

    private fun updateFollow(enabled: Boolean) {
        followsLive = enabled
        if (::follow.isInitialized) {
            follow.text = if (enabled) "Canlı takibi durdur" else "Canlı alanı takip et"
            follow.isSelected = enabled
        }
    }

    private fun showLive(promptSource: Boolean = false) {
        if (capture != null || busy) return
        if (useLiveFrame(force = promptSource)) return
        val state = SessionState.current
        pendingFrame = state.active
        status.text = if (state.active) "İlk oyun karesi bekleniyor. Oyun yayınını ekranda aç."
            else "Canlı kare için bir oyun bağlantısı seç."
        if (promptSource && !(state.active && state.source == Source.PS5)) showSources(state)
    }

    private fun useLiveFrame(force: Boolean, freshOnly: Boolean = false): Boolean {
        val sampled = LiveFocusFrames.sample() ?: return false
        val now = SystemClock.elapsedRealtime()
        val fresh = now - sampled.observedAt < 4_000L
        if (freshOnly && !fresh) {
            sampled.bitmap.recycle()
            return false
        }
        pendingFrame = false
        if (revisionSeen == LiveFocusFrames.revision && !force) {
            sampled.bitmap.recycle()
            return true
        }
        revisionSeen = LiveFocusFrames.revision
        lastLiveCheck = now
        setFrame(CaptureFrame(sampled.bitmap))
        val source = when (sampled.source) {
            Source.PS5 -> "Konsol"
            Source.MOBILE -> "PS Remote Play"
            Source.PORTAL -> "PS App"
        }
        frameLabel.text = if (fresh) "$source · Oyun karesi" else "$source · Son görülen kare"
        if (followsLive) translateArea()
        return true
    }

    private fun takeCurrentFrame() {
        if (busy) return
        if (SessionState.current.active && useLiveFrame(force = true, freshOnly = true)) return
        findLatestCapture { showLive(promptSource = true) }
    }

    private fun openLatestCapture(requestAccess: Boolean = true) {
        findLatestCapture {
            if (requestAccess && !CaptureInbox.canRead(this)) {
                requestPermissions(CaptureInbox.permissions(), CAPTURE_ACCESS)
            } else {
                showSources(SessionState.current, downloadHint = true)
            }
        }
    }

    private fun findLatestCapture(onMissing: () -> Unit) {
        if (busy) return
        val request = ++serial
        val priorStatus = status.text
        setBusy(true)
        status.text = "Son PS App yakalaması aranıyor…"
        disk.execute {
            val latest = CaptureInbox.latest(this)
            runOnUiThread {
                if (isDestroyed || isFinishing || request != serial) return@runOnUiThread
                setBusy(false)
                status.text = priorStatus
                if (!foreground) return@runOnUiThread
                if (latest == null) {
                    onMissing()
                } else {
                    pendingFrame = false
                    updateFollow(false)
                    startActivity(captureIntent(latest))
                }
            }
        }
    }

    private fun showSources(state: SessionState.State, downloadHint: Boolean = false) {
        if (busy || isFinishing) return
        sourcesDialog?.dismiss()
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        sourcesDialog = dialog
        val choices = if (state.active && state.source != null) listOf(state.source) else listOf(Source.PS5, Source.PORTAL, Source.MOBILE)
        val surface = stack().apply {
            background = blend(0xFF382650.toInt(), 0xFF171D35.toInt(), 26)
            setPadding(dp(16), dp(17), dp(16), dp(14))
            addView(copy("Oyun görüntüsü kaynağı", 22, Palette.white, true).apply { isAccessibilityHeading = true })
            if (downloadHint) {
                addBelow(copy("PS App’te Kütüphane → Yakalamalar → İndir yolunu kullan. Buraya döndüğünde son yakalama açılır.", 14), 7)
            }
            addBelow(navTile(
                if (downloadHint) "PlayStation App’i aç" else "Son PS App yakalaması",
                "Telefona indirilen fotoğraf veya klip",
                R.drawable.ic_capture_album, Palette.mint, 0xFF23494E.toInt() to 0xFF202E45.toInt(),
            ) {
                dialog.dismiss()
                if (downloadHint) {
                    resumeCaptureLookup = openPlayStationApp()
                    if (!resumeCaptureLookup) status.text = "PlayStation App açılamadı."
                } else openLatestCapture()
            }, 13)
            for (route in choices) {
                val card = when (route) {
                    Source.PS5 -> Triple("PS5 ve TV’ye bağlan", R.drawable.ic_controller, Palette.blue)
                    Source.PORTAL -> Triple(if (state.active) "PlayStation App yayınını aç" else "Portal yayınını paylaş",
                        R.drawable.ic_portal_device, Palette.rose)
                    Source.MOBILE -> Triple(if (state.active) "PS Remote Play’i öne getir" else "PS Remote Play ekranını paylaş",
                        R.drawable.ic_phone_screen, Palette.purple)
                }
                addBelow(navTile(card.first, null, card.second, card.third) {
                    dialog.dismiss()
                    openLiveSource(route, state.active)
                }, 8)
            }
            addBelow(press("Kapat") { dialog.dismiss() }, 10)
        }
        dialog.setContentView(ScrollView(this).apply { addView(surface) })
        dialog.setOnDismissListener { if (sourcesDialog === dialog) sourcesDialog = null }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.68f)
            setLayout(dp((resources.configuration.screenWidthDp - 30).coerceIn(200, 435)), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun openLiveSource(route: Source, active: Boolean) {
        val external = if (active) route.packageName?.let(packageManager::getLaunchIntentForPackage) else null
        val target = external ?: connectionIntent(this, route).apply {
            if (route != Source.PS5 && !active) putExtra("openFrameShare", true)
        }
        startActivity(target)
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
        emptyPreview.visibility = View.GONE
        canvas.visibility = View.VISIBLE
        enlarge.visibility = View.VISIBLE
        result = emptyList()
        updateControls()
        rows.removeAllViews()
        paintResult()
        canvas.select(selection)
        old?.bitmap?.recycle()
        frameTime.visibility = if (frame.durationMs > 0L) View.VISIBLE else View.GONE
        sample.visibility = frameTime.visibility
        if (frame.durationMs > 0L) sample.text = "Kareyi seç · ${frame.timeMs / 1000} sn"
        if (!followsLive) status.text = "Tam ekran için dokun. Alan seçmek için sürükle."
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
        val translated = result.count { !it.turkish.isNullOrBlank() && !it.turkish.equals(it.source, ignoreCase = true) }
        status.text = when {
            failed -> "Yazı tanınamadı. Alanı daha dar seçip tekrar dene."
            result.isEmpty() -> "Bu alanda okunabilir metin bulunamadı."
            sourceOnly -> "${result.size} kaynak metin"
            else -> "$translated Türkçe çeviri · ${result.size} okunan metin"
        }
        updateControls()
        paintResult()
        rows.removeAllViews()
        for (entry in result) {
            val output = if (sourceOnly) null else entry.turkish
            val isTranslated = !output.isNullOrBlank() && !output.equals(entry.source, ignoreCase = true)
            val item = stack().apply {
                setPadding(dp(13), dp(10), dp(13), dp(10))
                background = filled(0xFF1B243C.toInt(), 15, Palette.border)
                addView(copy(if (isTranslated) "Türkçe" else "Kaynak metin", 11,
                    if (isTranslated) Palette.mint else Palette.quiet, true))
                addBelow(copy(output ?: entry.source, 16, Palette.white).apply { setTextIsSelectable(true) }, 4)
            }
            rows.addBelow(item, 6)
        }
    }

    private fun paintResult() {
        val frame = current?.bitmap ?: return
        canvas.show(frame.width, frame.height, frame, if (sourceOnly) emptyList() else result)
        if (result.any { it.turkish != null } && !sourceOnly) canvas.select(null)
    }

    private fun showFullScreen() {
        if (viewerDialog?.isShowing == true) return
        val snapshot = current?.bitmap?.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val translations = result
        var showOriginal = sourceOnly
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        viewerDialog = dialog
        val viewer = FocusCanvas(this, zoomable = true).apply {
            contentDescription = "Tam ekran oyun görüntüsü"
            show(snapshot.width, snapshot.height, snapshot, if (showOriginal) emptyList() else translations)
        }
        val toggle = press(if (showOriginal) "Türkçeyi göster" else "İngilizceyi göster", Palette.mint) {}.apply {
            visibility = if (translations.any { !it.turkish.isNullOrBlank() }) View.VISIBLE else View.GONE
            setOnClickListener {
                showOriginal = !showOriginal
                text = if (showOriginal) "Türkçeyi göster" else "İngilizceyi göster"
                viewer.show(snapshot.width, snapshot.height, snapshot, if (showOriginal) emptyList() else translations)
            }
        }
        val root = stack().apply {
            setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { target, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    target.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                }
                insets
            }
            addView(row().apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(press("‹") { dialog.dismiss() }.apply {
                    contentDescription = "Tam ekranı kapat"
                    textSize = 24f
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(copy("Tam ekran", 17, Palette.white, true).apply { isAccessibilityHeading = true },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10); marginEnd = dp(8) })
                addView(toggle, LinearLayout.LayoutParams(-2, -2))
            })
            addView(viewer, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (viewerDialog === dialog) viewerDialog = null
            viewer.show(1, 1, null, emptyList())
            snapshot.recycle()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= 30) insetsController?.hide(WindowInsets.Type.statusBars())
            else {
                @Suppress("DEPRECATION")
                addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
        root.requestApplyInsets()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateControls()
    }

    private fun updateControls() {
        val hasFrame = current != null
        translate.isEnabled = hasFrame && !busy
        original.isEnabled = hasFrame && result.isNotEmpty() && !busy
        entire.isEnabled = hasFrame && !busy
        sample.isEnabled = hasFrame && !busy
        enlarge.isEnabled = hasFrame
    }

    @Deprecated("Android permission callback")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code != CAPTURE_ACCESS) return
        if (CaptureInbox.canRead(this)) openLatestCapture(requestAccess = false)
        else status.text = "Medya izni ver veya Fotoğraf veya klip seç düğmesini kullan."
    }

    @Deprecated("Android activity result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != PICK_MEDIA || result != RESULT_OK) return
        val uri = data?.data ?: return
        val video = contentResolver.getType(uri)?.startsWith("video/") == true
        startActivity(captureIntent(SavedCapture(uri, if (video) "Seçilen klip" else "Seçilen görüntü", video, 0, 0)))
    }

    override fun onDestroy() {
        serial++
        sourcesDialog?.dismiss()
        viewerDialog?.dismiss()
        reader.close()
        current?.bitmap?.recycle()
        disk.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val PICK_MEDIA = 51
        const val CAPTURE_ACCESS = 52
    }
}
