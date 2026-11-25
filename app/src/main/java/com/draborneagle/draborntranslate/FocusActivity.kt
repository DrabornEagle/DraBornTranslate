// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
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
        reader = FocusTranslator(this)
        val content = contentPage("DraBorn Odak", Palette.peach)
        val selected = capture
        val title = when {
            selected == null -> "Oyun görüntüsü"
            selected.added > 0 -> "${if (selected.video) "Klip" else "Görüntü"} · ${captureDate(selected.added)}"
            else -> selected.name
        }
        frameLabel = copy(title, 13, Palette.peach, true)
        val workspace = stack().apply {
            background = blend(0xFF2C2044.toInt(), 0xFF161E35.toInt(), 23)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(frameLabel)
        }
        canvas = FocusCanvas(this) { area ->
            if (!busy) {
                updateFollow(false)
                selection = area
                canvas.select(selection)
            }
        }.apply {
            contentDescription = "Çevrilecek alanı görüntü üzerinde sürükleyerek seç"
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
            addBelow(copy("Bir canlı kare al veya telefondan dosya seç.", 13).apply { gravity = Gravity.CENTER }, 4)
        }
        preview.addView(emptyPreview, FrameLayout.LayoutParams(-1, -1))
        workspace.addView(preview, LinearLayout.LayoutParams(-1, dp(218)).apply { topMargin = dp(8) })
        status = copy("", 13).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        workspace.addBelow(status, 9)
        content.addBelow(workspace)

        if (selected == null) {
            val quick = stack().apply {
                addView(press("Güncel oyun karesini al", Palette.peach) { showLive(promptSource = true) })
                addBelow(actionRow(
                    press("Fotoğraf veya klip seç") {
                        @Suppress("DEPRECATION")
                        startActivityForResult(mediaPicker(), 51)
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

    override fun onResume() {
        super.onResume()
        if (capture == null && (current == null || pendingFrame) && !busy) showLive()
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
        val sampled = LiveFocusFrames.sample()
        if (sampled != null) {
            pendingFrame = false
            if (revisionSeen == LiveFocusFrames.revision && !promptSource) {
                sampled.bitmap.recycle()
                return
            }
            revisionSeen = LiveFocusFrames.revision
            lastLiveCheck = SystemClock.elapsedRealtime()
            setFrame(CaptureFrame(sampled.bitmap))
            val age = ((lastLiveCheck - sampled.observedAt) / 1000L).coerceAtLeast(0L)
            val source = when (sampled.source) {
                Source.PS5 -> "Konsol"
                Source.MOBILE -> "PS Remote Play"
                Source.PORTAL -> "PS App"
            }
            frameLabel.text = if (age <= 3L) "$source · Oyun karesi" else "$source · Son görülen kare"
            if (followsLive) translateArea()
            return
        }
        val state = SessionState.current
        pendingFrame = state.active
        status.text = if (state.active) "İlk oyun karesi bekleniyor. Oyun yayınını ekranda aç."
            else "Canlı kare için bir oyun bağlantısı seç."
        if (promptSource) showSources(state)
    }

    private fun showSources(state: SessionState.State) {
        if (state.active && state.source == Source.PS5) return
        val choices = if (state.active && state.source != null) listOf(state.source) else listOf(Source.PS5, Source.PORTAL, Source.MOBILE)
        val labels = choices.map { route ->
            when (route) {
                Source.PS5 -> "PS5 ve TV’ye bağlan"
                Source.PORTAL -> if (state.active) "PlayStation App yayınını aç" else "Portal yayınını paylaş"
                Source.MOBILE -> if (state.active) "PS Remote Play’i öne getir" else "PS Remote Play ekranını paylaş"
            }
        }
        AlertDialog.Builder(this).setTitle("Oyun görüntüsü kaynağı")
            .setItems(labels.toTypedArray()) { _, selected ->
                val route = choices[selected]
                val external = if (state.active) route.packageName?.let(packageManager::getLaunchIntentForPackage) else null
                val target = external ?: connectionIntent(this, route).apply {
                    if (route != Source.PS5 && !state.active) putExtra("openFrameShare", true)
                }
                startActivity(target)
            }.setNegativeButton("Vazgeç", null).show()
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
        result = emptyList()
        updateControls()
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
    }

    @Deprecated("Android activity result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != 51 || result != RESULT_OK) return
        val uri = data?.data ?: return
        val video = contentResolver.getType(uri)?.startsWith("video/") == true
        startActivity(captureIntent(SavedCapture(uri, if (video) "Seçilen klip" else "Seçilen görüntü", video, 0, 0)))
    }

    override fun onDestroy() {
        serial++
        reader.close()
        current?.bitmap?.recycle()
        disk.shutdownNow()
        super.onDestroy()
    }
}
