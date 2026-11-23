// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.Manifest
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * v0.7.1. CameraX PreviewView FIT_CENTER handles device/sensor orientation.
 * - CAMERA: two optical regions -> subtitles and game-phone text
 * - AUDIO: microphone speech -> Turkish captions, with no camera at all
 * - BOTH: microphone dialogue + camera message OCR (no duplicate dialogues)
 *
 * Captures no internal PlayStation Portal video stream and does not inject
 * overlays into its hardware screen. All captions render on this phone only.
 */
class CloudStudioActivity : ComponentActivity() {
    private enum class Mode { CAMERA, AUDIO, BOTH }
    private val prefs by lazy { getSharedPreferences("cloud-studio-v071", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val engine by lazy { GameTranslatorEngine(this) }
    private val inFlight = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var active = false
    private var closing = false
    private var translationReady = false
    private var startedAt = 0L
    private var voice: CloudSpeechInput? = null
    private var requestedOnce = false
    private var mode = Mode.CAMERA
    private var subtitleRect = CloudRegion.SUBTITLES
    private var phoneRect = CloudRegion.MESSAGES
    private lateinit var root: FrameLayout
    private lateinit var viewfinder: PreviewView
    private lateinit var overlay: CloudRegionOverlay
    private lateinit var status: TextView
    private lateinit var english: TextView
    private lateinit var turkish: TextView
    private lateinit var messages: TextView
    private lateinit var modeLabel: TextView
    private lateinit var footer: LinearLayout

    private val captions by lazy {
        CaptionSession(
            translate = engine::translate,
            show = { caption ->
                if (!closing && mode == Mode.CAMERA && caption != null) {
                    turkish.text = caption.turkish
                    english.text = caption.source
                }
            },
            onFailure = { error -> error?.let(::notice) },
        )
    }
    private val lens by lazy {
        LensSession(
            translate = engine::translate,
            show = { cues, _ ->
                if (!closing && cues.isNotEmpty() && mode != Mode.AUDIO) {
                    val translated = cues.take(3).joinToString("\n") { it.turkish }
                    messages.text = "📱 " + translated
                    overlay.messageTranslation = translated
                }
            },
            onFailure = { error -> error?.let(::notice) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = if (prefs.getBoolean("portrait", false)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val requestedMode = if (savedInstanceState == null) intent.getStringExtra("startMode")
            else null
        mode = runCatching {
            Mode.valueOf(requestedMode ?: prefs.getString("mode", "CAMERA") ?: "CAMERA")
        }
            .getOrDefault(Mode.CAMERA)
        subtitleRect = readRect("sub", CloudRegion.SUBTITLES)
        phoneRect = readRect("msg", CloudRegion.MESSAGES)
        buildUi()
        notice("Türkçe oyun paketi ve çeviri motoru hazırlanıyor…")
        engine.prepare { ok ->
            translationReady = ok
            notice(if (ok) "Hazır • PS Portal ekranı veya hoparlörünü kullan."
                   else "Türkçe çeviri motoru hazırlanamadı. Oyun paketini kontrol et.")
        }
    }

    private fun readRect(prefix: String, default: CloudRegion) = CloudRegion(
        prefs.getInt(prefix + "X", default.left),
        prefs.getInt(prefix + "Y", default.top),
        prefs.getInt(prefix + "W", default.width),
        prefs.getInt(prefix + "H", default.height),
    ).normalized()

    private fun storeRegions() {
        subtitleRect = overlay.subtitleRegion
        phoneRect = overlay.messageRegion
        prefs.edit()
            .putInt("subX", subtitleRect.left).putInt("subY", subtitleRect.top)
            .putInt("subW", subtitleRect.width).putInt("subH", subtitleRect.height)
            .putInt("msgX", phoneRect.left).putInt("msgY", phoneRect.top)
            .putInt("msgW", phoneRect.width).putInt("msgH", phoneRect.height)
            .apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun panel(color: Int, radius: Float = 16f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), 0x665D7098)
    }

    private fun button(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        minHeight = dp(44)
        setTextColor(Color.WHITE)
        background = panel(0xFF393560.toInt(), 13f)
        setPadding(dp(8), dp(5), dp(8), dp(5))
        setOnClickListener { click() }
    }

    private fun txt(value: String, size: Float, color: Int = Color.WHITE) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(0xFF0B1024.toInt()) }
        viewfinder = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF090D1C.toInt())
        }
        root.addView(viewfinder, FrameLayout.LayoutParams(-1, -1))
        overlay = CloudRegionOverlay(this).apply {
            subtitleRegion = subtitleRect
            messageRegion = phoneRect
            onChanged = { _, _ -> storeRegions() }
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel(0xE7192544.toInt(), 16f)
            setPadding(dp(10), dp(6), dp(10), dp(7))
            val head = LinearLayout(this@CloudStudioActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(button("← Geri") { finish() })
            modeLabel = txt("☁ DraBornTranslate v0.7.1", 16f)
            modeLabel.gravity = Gravity.CENTER
            head.addView(modeLabel, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(button("↔ Yatay/Dikey") { toggleOrientation() })
            addView(head)
            val buttons = LinearLayout(this@CloudStudioActivity).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(button("📷 Kamera") { select(Mode.CAMERA) })
            buttons.addView(button("🎙 Ses") { select(Mode.AUDIO) })
            buttons.addView(button("📷+🎙") { select(Mode.BOTH) })
            buttons.addView(button("▣ Alanlar") { toggleEdit() })
            buttons.addView(button("⚙ Hassas ayar") { showPreciseEditor() })
            buttons.addView(button("🔍 Yakınlaştır") { showZoom() })
            val horizontal = HorizontalScrollView(this@CloudStudioActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(buttons)
            }
            addView(horizontal)
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(9), dp(5), dp(9), 0)
        })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel(0xE9121D35.toInt(), 18f)
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        status = txt("", 12f, 0xFF91DFF4.toInt())
        panel.addView(status)
        english = txt("English • Portal'dan gelen replik burada görünecek.", 12f, 0xFFACB9D8.toInt())
        english.maxLines = 2
        panel.addView(english)
        turkish = txt("Türkçe altyazılar burada görüntülenecek.", 21f)
        turkish.maxLines = 3
        turkish.gravity = Gravity.CENTER
        panel.addView(turkish)
        messages = txt("📱 Lens: oyun içi mesajlar ayrı çevrilir.", 14f, 0xFF86E8BC.toInt())
        messages.maxLines = 3
        panel.addView(messages)
        footer = panel
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(9), 0, dp(9), dp(6))
        })
        setContentView(root)
        syncVisualMode()
    }

    private fun select(next: Mode) {
        if (mode == next) return
        mode = next
        prefs.edit().putString("mode", next.name).apply()
        stopCamera()
        stopVoice()
        captions.reset()
        lens.reset()
        overlay.messageTranslation = ""
        english.text = "English • Yeni oturum"
        turkish.text = "Türkçe altyazılar burada görüntülenecek."
        syncVisualMode()
        ensurePermissions(true)
    }

    private fun syncVisualMode() {
        val cameraMode = mode != Mode.AUDIO
        viewfinder.visibility = if (cameraMode) View.VISIBLE else View.GONE
        overlay.visibility = if (cameraMode) View.VISIBLE else View.GONE
        if (!cameraMode) overlay.editing = false
        if (::footer.isInitialized) footer.visibility = View.VISIBLE
        modeLabel.text = when (mode) {
            Mode.CAMERA -> "☁ Kamera · OCR + Lens"
            Mode.AUDIO -> "🎙 Sesli Türkçe altyazı"
            Mode.BOTH -> "☁ Kamera + Ses birlikte"
        }
        messages.visibility = if (cameraMode) View.VISIBLE else View.GONE
        notice(when (mode) {
            Mode.CAMERA -> "Kamera Portal ekranına bakmalı. Mavi: altyazı, yeşil: mesaj."
            Mode.AUDIO -> "Kamera gerekmez. Portal hoparlörünü telefon mikrofonuna yaklaştır."
            Mode.BOTH -> "Konuşma mikrofondan, yazılı mesajlar kameradan çevrilir."
        })
    }

    private fun toggleEdit() {
        if (mode == Mode.AUDIO) {
            notice("Ses modunda kamera alanı gerekmez. Kamera veya Birlikte modunu seç.")
            return
        }
        overlay.editing = !overlay.editing
        footer.visibility = if (overlay.editing) View.GONE else View.VISIBLE
        notice(if (overlay.editing) "Mavi/yeşil kutuyu sürükle. Köşe noktalarından yeniden boyutlandır."
               else "Okuma alanları kaydedildi.")
        if (!overlay.editing) storeRegions()
    }

    private fun toggleOrientation() {
        val portrait = !prefs.getBoolean("portrait", false)
        prefs.edit().putBoolean("portrait", portrait).apply()
        requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    /** Detailed controls and touch handles always edit the same selected region. */
    private fun showPreciseEditor() {
        if (mode == Mode.AUDIO) {
            notice("Ses modunda kamera bölgesi ayarı gerekmez.")
            return
        }
        val regionView = overlay
        regionView.editing = true
        footer.visibility = View.GONE
        val parent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(7), dp(18), dp(9))
        }
        parent.addView(txt("Önizlemede mavi/yeşil kutuyu sürükle veya dört köşeden yeniden boyutlandır. Aşağıdaki %1 hassasiyetli değerler aynı bölgeyi günceller.", 14f))
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(choices)
        val sliderBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(sliderBox)
        val names = listOf("Sol", "Üst", "Genişlik", "Yükseklik")
        fun valuesOf(region: CloudRegion) = listOf(region.left, region.top, region.width, region.height)
        fun selectedRegion() = if (regionView.selected == 0) regionView.subtitleRegion else regionView.messageRegion

        fun setAxis(axis: Int, value: Int) {
            val old = selectedRegion()
            val updated = when (axis) {
                0 -> old.copy(left = value)
                1 -> old.copy(top = value)
                2 -> old.copy(width = value.coerceAtLeast(5))
                else -> old.copy(height = value.coerceAtLeast(5))
            }.normalized()
            if (regionView.selected == 0) regionView.subtitleRegion = updated
            else regionView.messageRegion = updated
            storeRegions()
        }

        fun makeRows() {
            sliderBox.removeAllViews()
            for (axis in 0..3) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val title = txt("", 14f)
                val initial = valuesOf(selectedRegion())[axis]
                title.text = names[axis] + " • %" + initial
                sliderBox.addView(title)
                val slider = SeekBar(this).apply {
                    max = 100
                    progress = initial
                }
                fun adjust(value: Int) {
                    setAxis(axis, value.coerceIn(0, 100))
                    val actual = valuesOf(selectedRegion())[axis]
                    slider.progress = actual
                    title.text = names[axis] + " • %" + actual
                }
                row.addView(button("−1") { adjust(slider.progress - 1) })
                row.addView(slider, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button("+1") { adjust(slider.progress + 1) })
                slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (fromUser) adjust(value)
                    }
                })
                sliderBox.addView(row)
            }
        }
        choices.addView(button("🔵 Konuşma altyazısı") {
            regionView.selected = 0
            makeRows()
        })
        choices.addView(button("🟢 Telefon / Lens") {
            regionView.selected = 1
            makeRows()
        })
        makeRows()
        parent.addView(button("↺ Her iki alanı sıfırla") {
            regionView.subtitleRegion = CloudRegion.SUBTITLES
            regionView.messageRegion = CloudRegion.MESSAGES
            storeRegions()
            makeRows()
        })
        val scroller = ScrollView(this).apply { addView(parent) }
        AlertDialog.Builder(this)
            .setTitle("Cloud Translate • Profesyonel alan editörü")
            .setView(scroller)
            .setPositiveButton("Kaydet") { _, _ -> storeRegions() }
            .setNegativeButton("Kapat", null)
            .create()
            .apply {
                setOnDismissListener {
                    regionView.editing = false
                    footer.visibility = View.VISIBLE
                    storeRegions()
                }
                show()
            }
    }

    private fun showZoom() {
        if (mode == Mode.AUDIO) {
            notice("Ses modunda yakınlaştırma gerekmez.")
            return
        }
        val device = camera
        val zoom = device?.cameraInfo?.zoomState?.value
        if (device == null || zoom == null) {
            notice("Kamera hazır değil.")
            return
        }
        val zoomMax = zoom.maxZoomRatio.coerceAtMost(6f)
        val bar = SeekBar(this).apply {
            this.max = 100
            progress = (((zoom.zoomRatio - 1f) / (zoomMax - 1f).coerceAtLeast(0.1f)) * 100f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
                override fun onProgressChanged(bar: SeekBar, percent: Int, user: Boolean) {
                    if (user) device.cameraControl.setZoomRatio(1f + (zoomMax - 1f) * percent / 100f)
                }
            })
        }
        AlertDialog.Builder(this).setTitle("Portal ekranına odaklan • Kamera zoom")
            .setView(bar).setPositiveButton("Tamam", null).show()
    }

    override fun onResume() {
        super.onResume()
        active = true
        ensurePermissions(!requestedOnce)
        requestedOnce = true
    }

    private fun ensurePermissions(ask: Boolean) {
        if (!active || closing) return
        val missing = buildList {
            if (mode != Mode.AUDIO &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.CAMERA)
            if (mode != Mode.CAMERA &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isNotEmpty() && ask) {
            requestPermissions(missing.toTypedArray(), 1071)
            return
        }
        if (mode != Mode.AUDIO &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        if (mode != Mode.CAMERA &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startVoice()
        if (missing.isNotEmpty()) notice("Eksik izin: " + missing.joinToString { it.substringAfterLast('.') })
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 1071) ensurePermissions(false)
    }

    private fun startCamera() {
        if (!active || closing || mode == Mode.AUDIO) return
        viewfinder.post {
            if (!active || closing || mode == Mode.AUDIO) return@post
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    if (!active || closing || mode == Mode.AUDIO) return@addListener
                    val current = future.get()
                    current.unbindAll()
                    provider = current
                    val preview = Preview.Builder().build()
                    preview.surfaceProvider = viewfinder.surfaceProvider
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy -> analyzeFrame(proxy) }
                    val group = UseCaseGroup.Builder()
                        .addUseCase(preview).addUseCase(analysis)
                    viewfinder.viewPort?.let { group.setViewPort(it) }
                    camera = current.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, group.build()
                    )
                    notice("KameraX yatay/dikey görüntüyü otomatik hizalıyor. Alanları istediğin gibi sürükle.")
                } catch (error: Exception) {
                    notice("Kamera açılamadı: \${error.message.orEmpty()}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!active || closing || !translationReady || mode == Mode.AUDIO ||
            now - startedAt < 520L || !inFlight.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        startedAt = now
        val image = proxy.image
        if (image == null) {
            proxy.close()
            inFlight.set(false)
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val width = if (rotation % 180 == 0) proxy.width else proxy.height
        val height = if (rotation % 180 == 0) proxy.height else proxy.width
        val input = InputImage.fromMediaImage(image, rotation)
        ocr.process(input)
            .addOnSuccessListener { text ->
                if (!closing && active) showOcr(text, width, height)
            }
            .addOnFailureListener { notice("Metin algılanamadı. Kamerayı Portal ekranına odakla.") }
            .addOnCompleteListener {
                proxy.close()
                inFlight.set(false)
            }
    }

    private fun showOcr(text: Text, width: Int, height: Int) {
        val area = Pixels(0, 0, width, height)
        overlay.aspect = width.toFloat() / height.coerceAtLeast(1)
        val blocks = text.textBlocks
        if (mode == Mode.CAMERA) {
            val lines = blocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    line.boundingBox?.let { rect ->
                        CloudTextBlock(line.text, rect.left, rect.top, rect.right, rect.bottom)
                    }
                }
            }
            val selected = CloudRegionText.lines(area, subtitleRect, lines)
            captions.read(selected)
        }
        val messageBlocks = blocks.mapNotNull { block ->
            block.boundingBox?.let { bounds ->
                CloudTextBlock(block.text, bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }
        val selectedMessages = CloudRegionText.blocks(area, phoneRect, messageBlocks)
        lens.read(selectedMessages.map {
            DetectedLine(it.text, Rect(it.left, it.top, it.right, it.bottom))
        }, FrameRegion(0, 0, width, height))
    }

    private fun startVoice() {
        if (!active || closing || mode == Mode.CAMERA || voice != null) return
        val allowSystem = prefs.getBoolean("allowSystemSpeech", false)
        val input = CloudSpeechInput(
            context = this,
            systemRecognizerConsent = allowSystem,
            onFinal = { text ->
                english.text = "🎙 $text"
                engine.translate(TranslationInput(text, ReadingArea.SUBTITLES)) { response ->
                    if (!closing && mode != Mode.CAMERA) {
                        val result = response.text?.takeIf(String::isNotBlank)
                        if (result != null) turkish.text = result
                        else notice(response.issue ?: "Bu konuşma çevrilemedi.")
                    }
                }
            },
            onPreview = { text ->
                if (mode != Mode.CAMERA) english.text = "🎙 $text"
            },
            onStatus = ::notice,
        )
        if (!input.availableWithoutNetwork && !allowSystem) {
            AlertDialog.Builder(this)
                .setTitle("Çevrimdışı ses tanıma bulunamadı")
                .setMessage("Telefonunun yerel İngilizce ses tanıma motoru kullanılamıyor. Sistem tanıma hizmeti ses verilerini sağlayıcının sunucusuna gönderebilir. Açık iznin olmadan kullanılmaz.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sistem tanımaya izin ver") { _, _ ->
                    prefs.edit().putBoolean("allowSystemSpeech", true).apply()
                    startVoice()
                }.show()
            return
        }
        if (input.start()) voice = input
    }

    private fun stopVoice() { voice?.stop(); voice = null }
    private fun stopCamera() {
        runCatching { provider?.unbindAll() }
        camera = null
        inFlight.set(false)
    }

    override fun onPause() {
        active = false
        stopVoice()
        stopCamera()
        super.onPause()
    }

    override fun onDestroy() {
        closing = true
        stopVoice()
        stopCamera()
        ocr.close()
        engine.close()
        executor.shutdown()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun notice(msg: String) {
        main.post { if (!closing && ::status.isInitialized) status.text = msg }
    }
}
