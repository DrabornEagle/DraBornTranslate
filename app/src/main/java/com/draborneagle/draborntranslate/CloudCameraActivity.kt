// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.Manifest
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Optical mode. No access to the Portal cloud session or system overlay. */
class CloudCameraActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("cloud-camera-v071", MODE_PRIVATE) }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator by lazy { GameTranslatorEngine(this) }
    private lateinit var controller: LifecycleCameraController
    private lateinit var preview: PreviewView
    private lateinit var guides: RegionView
    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private lateinit var message: TextView
    private lateinit var footer: LinearLayout
    private var sub = CloudRegion(6, 65, 88, 30)
    private var lensRegion = CloudRegion(52, 6, 46, 48)
    private var selected = 0
    private var mode = 0
    private var editing = false
    private var readerOnly = false
    private var ready = false
    private var resumed = false
    private var destroyed = false
    private var bound = false
    private var lastRead = 0L
    private var interval = 340L
    private var lensCues = emptyList<LensCue>()
    private var captionSize = 22
    private var lensSize = 15
    private var zoom = 0
    private var showLensOverlay = true
    private val captions by lazy {
        CaptionSession(translate = translator::translate, show = { line ->
            if (!destroyed) subtitle.text = line?.turkish.orEmpty()
        }, onFailure = { it?.let(::showStatus) })
    }
    private val lens by lazy {
        LensSession(translate = translator::translate, show = { cues, _ ->
            if (!destroyed) {
                lensCues = cues
                message.text = cues.joinToString("\n") { it.turkish }
                guides.invalidate()
            }
        }, onFailure = { it?.let(::showStatus) })
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = when (prefs.getInt("orientation", 0)) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sub = load("sub", sub)
        lensRegion = load("lens", lensRegion)
        mode = prefs.getInt("mode", 0).coerceIn(0, 2)
        captionSize = prefs.getInt("captionSize", 22).coerceIn(16, 40)
        lensSize = prefs.getInt("lensSize", 15).coerceIn(12, 28)
        zoom = prefs.getInt("zoom", 0).coerceIn(0, 100)
        interval = prefs.getLong("interval", 340L).coerceIn(250L, 900L)
        showLensOverlay = prefs.getBoolean("overlay", true)
        makeUi()
        controller = LifecycleCameraController(this).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisAnalyzer(mainExecutor, MlKitAnalyzer(
                listOf(recognizer), CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                mainExecutor
            ) { result ->
                val now = SystemClock.elapsedRealtime()
                if (ready && resumed && !destroyed && !editing &&
                    now - lastRead >= interval && guides.width > 0 && guides.height > 0) {
                    lastRead = now
                    result.getValue(recognizer)?.let(::readText)
                }
            })
        }
        preview.controller = controller
        controller.cameraState.observe(this) { state ->
            if (state.type == androidx.camera.core.CameraState.Type.OPEN) {
                controller.setLinearZoom(zoom / 100f)
                showStatus("Kamera hazır · Mavi altyazı / yeşil Lens")
            }
            if (state.error != null) showStatus("Kamera bağlantısı başarısız. İzni ve diğer kamera uygulamalarını kontrol et.")
        }
        showStatus("Türkçe çeviri motoru hazırlanıyor…")
        translator.prepare { ok ->
            ready = ok
            showStatus(if (ok) "Hazır · İngilizce altyazıları aç." else "Çeviri motoru hazırlanamadı.")
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 701)
    }

    private fun bindCamera() {
        if (bound || destroyed) return
        try {
            controller.bindToLifecycle(this)
            bound = true
        } catch (e: Exception) { showStatus("Kamera başlatılamadı: " + e.message.orEmpty()) }
    }

    private fun makeUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF090D1B.toInt()) }
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        guides = RegionView()
        root.addView(guides, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = panel(0xE51B2341.toInt(), 15)
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(action("‹ Geri") { finish() })
        top.addView(TextView(this).apply {
            text = "Cloud Gaming Pro · 0.7.1"
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(action("Ayarlar") { settings() })
        header.addView(top)
        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER }
        tools.addView(action("Altyazı") { editRegion(0) }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(action("Lens") { editRegion(1) }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(action("Bitir") { editing = false; footer.visibility = View.VISIBLE; guides.invalidate(); resetReads() }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(action("Okuma") {
            editing = false; readerOnly = !readerOnly; footer.visibility = View.VISIBLE; guides.invalidate()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(tools)
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(8), dp(5), dp(8), 0) })
        footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = panel(0xEF131E36.toInt(), 15)
        }
        status = TextView(this).apply { textSize = 12f; setTextColor(0xFF8CEBFF.toInt()) }
        subtitle = TextView(this).apply { textSize = captionSize.toFloat(); setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        message = TextView(this).apply { textSize = lensSize.toFloat(); setTextColor(0xFF8EF3BF.toInt()) }
        footer.addView(status)
        footer.addView(subtitle)
        footer.addView(ScrollView(this).apply { addView(message) }, LinearLayout.LayoutParams(-1, dp(72)))
        root.addView(footer, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(6)) })
        setContentView(root)
    }

    private fun editRegion(which: Int) {
        selected = which
        readerOnly = false
        editing = true
        footer.visibility = View.GONE
        guides.invalidate()
    }

    private fun settings() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(12)) }
        fun heading(s: String) { content.addView(TextView(this).apply {
            text = s; textSize = 16f; setTextColor(0xFF8CEBFF.toInt()); setPadding(0, dp(12), 0, dp(5))
        }) }
        fun choice(title: String, options: Array<String>, initial: Int, change: (Int) -> Unit) {
            content.addView(action(title + " · " + options[initial]) {
                AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(options, initial) { dialog, index ->
                    dialog.dismiss(); change(index)
                }.setNegativeButton("Kapat", null).show()
            })
        }
        fun slider(title: String, min: Int, max: Int, initial: Int, change: (Int) -> Unit) {
            val label = TextView(this).apply { text = "$title: $initial" }
            content.addView(label)
            content.addView(SeekBar(this).apply {
                this.max = max - min; progress = (initial - min).coerceIn(0, this.max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) { resetReads() }
                    override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                        if (user) { label.text = "$title: ${value + min}"; change(value + min) }
                    }
                })
            })
        }
        heading("Görüntü ve çalışma modu")
        choice("Ekran yönü", arrayOf("Yatay", "Dikey", "Otomatik"), prefs.getInt("orientation", 0).coerceIn(0, 2)) {
            prefs.edit().putInt("orientation", it).apply()
            requestedOrientation = when (it) {
                1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                2 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        choice("Çeviri modu", arrayOf("Altyazı + Lens", "Altyazı", "Lens"), mode) {
            mode = it; prefs.edit().putInt("mode", it).apply(); resetReads(); guides.invalidate()
        }
        slider("Kamera yakınlaştırma %", 0, 100, zoom) {
            zoom = it; prefs.edit().putInt("zoom", it).apply()
            if (bound) controller.setLinearZoom(it / 100f)
        }
        slider("OCR sonuç aralığı (ms)", 250, 900, interval.toInt()) {
            interval = it.toLong(); prefs.edit().putLong("interval", interval).apply()
        }
        heading("Altyazı ve Lens · bağımsız alanlar (%)")
        fun regionControls(name: String, initial: CloudRegion, get: () -> CloudRegion, set: (CloudRegion) -> Unit) {
            heading(name)
            slider("Sol", 0, 95, initial.left) { set(get().copy(left = it).normalized()) }
            slider("Üst", 0, 95, initial.top) { set(get().copy(top = it).normalized()) }
            slider("Genişlik", 5, 100, initial.width) { set(get().copy(width = it).normalized()) }
            slider("Yükseklik", 5, 100, initial.height) { set(get().copy(height = it).normalized()) }
        }
        regionControls("Mavi · Altyazı", sub, { sub }) { sub = it; save("sub", it); guides.invalidate() }
        regionControls("Yeşil · Lens", lensRegion, { lensRegion }) { lensRegion = it; save("lens", it); guides.invalidate() }
        heading("Türkçe metin görünümü")
        slider("Altyazı punto", 16, 40, captionSize) {
            captionSize = it; subtitle.textSize = it.toFloat(); prefs.edit().putInt("captionSize", it).apply()
        }
        slider("Lens punto", 12, 28, lensSize) {
            lensSize = it; message.textSize = it.toFloat(); prefs.edit().putInt("lensSize", it).apply(); guides.invalidate()
        }
        choice("Lens katmanı", arrayOf("Kapalı", "Açık"), if (showLensOverlay) 1 else 0) {
            showLensOverlay = it == 1; prefs.edit().putBoolean("overlay", showLensOverlay).apply(); guides.invalidate()
        }
        AlertDialog.Builder(this).setTitle("Cloud Gaming Pro · Kontrol merkezi")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Tamam", null)
            .setNeutralButton("Alanları sıfırla") { _, _ ->
                sub = CloudRegion(6, 65, 88, 30); lensRegion = CloudRegion(52, 6, 46, 48)
                save("sub", sub); save("lens", lensRegion); resetReads(); guides.invalidate()
            }.show()
    }

    private fun resetReads() { captions.reset(); lens.reset(); subtitle.text = ""; message.text = ""; lensCues = emptyList(); guides.invalidate() }
    private fun load(key: String, fallback: CloudRegion): CloudRegion = CloudRegion(
        prefs.getInt(key+"L", fallback.left), prefs.getInt(key+"T", fallback.top),
        prefs.getInt(key+"W", fallback.width), prefs.getInt(key+"H", fallback.height)
    ).normalized()
    private fun save(key: String, area: CloudRegion) {
        prefs.edit().putInt(key+"L", area.left).putInt(key+"T", area.top)
            .putInt(key+"W", area.width).putInt(key+"H", area.height).apply()
    }
    private fun readText(result: com.google.mlkit.vision.text.Text) {
        val frame = Pixels(0, 0, guides.width, guides.height)
        val lines = result.textBlocks.flatMap { block -> block.lines.mapNotNull { line ->
            line.boundingBox?.let { CloudTextBlock(line.text, it.left, it.top, it.right, it.bottom) }
        } }
        if (mode != 2) captions.read(lines.filter { CloudTextLayout.containsCenter(sub.pixels(frame), it) }
            .sortedWith(compareBy({ it.top }, { it.left })).map { it.text }.distinct().take(4))
        if (mode != 1) {
            val blocks = result.textBlocks.mapNotNull { block -> block.boundingBox?.let {
                CloudTextBlock(block.text, it.left, it.top, it.right, it.bottom)
            } }.filter { CloudTextLayout.containsCenter(lensRegion.pixels(frame), it) }
            lens.read(blocks.map { DetectedLine(it.text, Rect(it.left, it.top, it.right, it.bottom)) },
                FrameRegion(0, 0, guides.width, guides.height))
        }
    }
    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; if (::guides.isInitialized) resetReads(); super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 701 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) bindCamera()
        else if (requestCode == 701) showStatus("Kamera izni verilmedi. Kamerasız mod ana ekranda.")
    }
    override fun onDestroy() {
        destroyed = true
        if (::controller.isInitialized) { controller.clearImageAnalysisAnalyzer(); controller.unbind() }
        captions.close(); lens.close(); recognizer.close(); translator.close()
        super.onDestroy()
    }
    private fun showStatus(s: String) { runOnUiThread { if (!destroyed && ::status.isInitialized) status.text = s } }
    private fun action(s: String, click: () -> Unit) = Button(this).apply {
        text = s; textSize = 12f; isAllCaps = false; setTextColor(Color.WHITE)
        background = panel(0xFF37436A.toInt(), 10); setOnClickListener { click() }
    }
    private fun panel(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    private inner class RegionView : View(this@CloudCameraActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var startX = 0f
        private var startY = 0f
        private var original = sub
        private var resize = false
        private fun bounds(r: CloudRegion): RectF {
            val p = r.pixels(Pixels(0, 0, width, height))
            return RectF(p.x.toFloat(), p.y.toFloat(), (p.x+p.width).toFloat(), (p.y+p.height).toFloat())
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (readerOnly) { canvas.drawColor(0xFF0B1022.toInt()); return }
            fun drawRegion(region: CloudRegion, color: Int, title: String, active: Boolean) {
                val box = bounds(region)
                paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(if (active) 3 else 1).toFloat()
                canvas.drawRoundRect(box, dp(8).toFloat(), dp(8).toFloat(), paint)
                paint.style = Paint.Style.FILL; paint.textSize = dp(12).toFloat()
                canvas.drawText(title, box.left+dp(6), (box.top+dp(17)).coerceAtMost(box.bottom), paint)
                if (active) canvas.drawCircle(box.right-dp(8), box.bottom-dp(8), dp(10).toFloat(), paint)
            }
            if (mode != 2 || editing) drawRegion(sub, 0xFF58C8FF.toInt(), "ALTYAZI", editing && selected == 0)
            if (mode != 1 || editing) drawRegion(lensRegion, 0xFF55E3B5.toInt(), "LENS", editing && selected == 1)
            if (!editing && showLensOverlay && mode != 1) {
                lensCues.forEach { cue ->
                    val r = RectF(cue.bounds)
                    if (!r.intersect(0f, 0f, width.toFloat(), height.toFloat())) return@forEach
                    canvas.save(); canvas.clipRect(r)
                    paint.color = 0xE8192033.toInt(); paint.style = Paint.Style.FILL
                    canvas.drawRoundRect(r, dp(4).toFloat(), dp(4).toFloat(), paint)
                    paint.color = Color.WHITE; paint.textSize = lensSize * resources.displayMetrics.scaledDensity
                    val available = r.width() - dp(6)
                    if (available > 0) {
                        var remaining = cue.turkish.replace('\n', ' ')
                        var y = r.top - paint.ascent()
                        while (remaining.isNotEmpty() && y + paint.descent() <= r.bottom) {
                            val count = paint.breakText(remaining, true, available, null)
                            if (count < 1) break
                            val wordEnd = if (count < remaining.length) remaining.take(count).lastIndexOf(' ') else -1
                            val end = if (wordEnd > 0) wordEnd else count
                            canvas.drawText(remaining.take(end), r.left+dp(3), y, paint)
                            remaining = remaining.drop(end).trimStart(); y += paint.fontSpacing
                        }
                    }
                    canvas.restore()
                }
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!editing || width == 0 || height == 0) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    original = if (selected == 0) sub else lensRegion
                    val r = bounds(original)
                    if (!r.contains(event.x, event.y)) return false
                    startX = event.x; startY = event.y
                    resize = event.x >= r.right - dp(30) && event.y >= r.bottom - dp(30)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ((event.x-startX)*100/width).toInt()
                    val dy = ((event.y-startY)*100/height).toInt()
                    val next = if (resize) original.copy(width = original.width+dx, height = original.height+dy).normalized()
                    else original.move(dx, dy)
                    if (selected == 0) sub = next else lensRegion = next
                    invalidate(); return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (selected == 0) save("sub", sub) else save("lens", lensRegion)
                    performClick(); return true
                }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
