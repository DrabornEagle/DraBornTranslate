// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * PS Portal CLOUD stream has no supported live capture/Share Screen API.
 * This activity ONLY reads physical light from the user's Portal display via
 * the phone's back camera, and renders translated captions on the PHONE.
 *
 * No stream interception, Sony authentication, video recording or uploads.
 */
class CloudCameraActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val cameraThread = HandlerThread("DraBornTranslate-cloud-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val manager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator: GameTranslatorEngine by lazy { GameTranslatorEngine(this) }
    private val busy = AtomicBoolean(false)
    private val opening = AtomicBoolean(false)
    private val prefs by lazy { getSharedPreferences("cloud-camera-v07", MODE_PRIVATE) }
    private var config = CaptureSettings(readingArea = ReadingArea.DUAL)
    private var camera: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var cameraId: String? = null
    private var sensorOrientation = 90
    @Volatile private var cameraActive = false
    @Volatile private var destroyed = false
    @Volatile private var ready = false
    private var lastReadAt = 0L
    private lateinit var preview: TextureView
    private lateinit var guides: CloudRegionView
    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private lateinit var message: TextView

    private val captions by lazy {
        CaptionSession(
            translate = translator::translate,
            show = { line ->
                if (!destroyed && line != null) subtitle.text = line.turkish
            },
            onFailure = { problem -> problem?.let(::showStatus) },
        )
    }

    private val lens by lazy {
        LensSession(
            translate = translator::translate,
            show = { translated, _ ->
                if (!destroyed && translated.isNotEmpty()) {
                    val text = translated.take(3).joinToString("\n") { it.turkish }
                    message.text = text
                    if (::guides.isInitialized) guides.setTranslation(text)
                }
            },
            onFailure = { problem -> problem?.let(::showStatus) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        config = config.copy(
            bandTop = prefs.getInt("band", 65).coerceIn(0, CaptureSettings.MAX_TOP),
            lensLeft = prefs.getInt("lensLeft", 52),
            lensTop = prefs.getInt("lensTop", 6),
            lensWidth = prefs.getInt("lensWidth", 46),
            lensHeight = prefs.getInt("lensHeight", 48),
        )
        makeUi()
        showStatus("GTA 6 Türkçe paketi ve çeviri motoru hazırlanıyor…")
        translator.prepare { ok ->
            ready = ok
            showStatus(if (ok) "Portal ekranını kameraya göster. Mavi: altyazı, yeşil: mesaj."
                       else "Çeviri motoru hazırlanamadı. GTA 6 paketini ve boş alanı kontrol et.")
        }
    }

    private fun makeUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0B0F22.toInt()) }
        preview = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    if (cameraActive) startCamera()
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    stopCamera()
                    return true
                }
            }
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        guides = CloudRegionView()
        root.addView(guides, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = panel(0xDF1C2040.toInt(), 12)
        }
        header.addView(action("← Geri") { finish() })
        header.addView(TextView(this).apply {
            text = "☁  Cloud Gaming Translate · v0.7"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("⚙ Alanları ayarla") { showRegionEditor() })
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(10), dp(8), dp(10), 0)
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(9), dp(15), dp(10))
            background = panel(0xE518213B.toInt(), 16)
        }
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFA6E9FE.toInt())
        }
        bottom.addView(status)
        subtitle = TextView(this).apply {
            text = "🎮 Türkçe konuşma altyazıları burada görünecek."
            textSize = 20f
            setTextColor(Color.WHITE)
            maxLines = 3
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        bottom.addView(subtitle)
        message = TextView(this).apply {
            text = "📱 Telefon mesajları Lens alanından okunacak."
            textSize = 15f
            setTextColor(0xFF8FF2C7.toInt())
            maxLines = 4
        }
        bottom.addView(message)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(9), 0, dp(9), dp(8))
        })
        setContentView(root)
    }

    private fun showRegionEditor() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        layout.addView(TextView(this).apply {
            text = "Telefon kamerasını Portal ekranına sabitle. Mavi alan konuşma altyazısı, yeşil alan oyun telefonu/mesajları içindir. Bu alanlar kamera görüntüsünün yüzdesidir."
            textSize = 14f
        })
        fun setting(title: String, key: String, initial: Int, max: Int, change: (Int) -> Unit) {
            val label = TextView(this).apply { text = "$title: %$initial"; textSize = 15f }
            layout.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            layout.addView(SeekBar(this).apply {
                this.max = max
                progress = initial.coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                    override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                        if (!user) return
                        change(value)
                        prefs.edit().putInt(key, value).apply()
                        label.text = "$title: %$value"
                        guides.invalidate()
                    }
                })
            })
        }
        setting("Altyazı bandı", "band", config.bandTop, 70) {
            config = config.copy(bandTop = it)
        }
        setting("Lens soldan", "lensLeft", config.lensLeft, 85) {
            config = config.copy(lensLeft = it)
        }
        setting("Lens üstten", "lensTop", config.lensTop, 85) {
            config = config.copy(lensTop = it)
        }
        setting("Lens genişlik", "lensWidth", config.lensWidth, 100) {
            config = config.copy(lensWidth = it.coerceAtLeast(15))
        }
        setting("Lens yükseklik", "lensHeight", config.lensHeight, 100) {
            config = config.copy(lensHeight = it.coerceAtLeast(15))
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        AlertDialog.Builder(this)
            .setTitle("Cloud Gaming · İki okuma alanı")
            .setView(scroll)
            .setPositiveButton("Kaydet", null)
            .setNeutralButton("Varsayılan") { _, _ ->
                config = config.copy(bandTop = 65, lensLeft = 52, lensTop = 6,
                    lensWidth = 46, lensHeight = 48)
                prefs.edit().clear().apply()
                guides.invalidate()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        cameraActive = true
        if (::preview.isInitialized && preview.isAvailable) startCamera()
    }

    override fun onPause() {
        cameraActive = false
        stopCamera()
        super.onPause()
    }

    private fun startCamera() {
        if (!cameraActive || camera != null || destroyed || !preview.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 701)
            return
        }
        if (!opening.compareAndSet(false, true)) return
        try {
            val id = manager.cameraIdList.firstOrNull { candidate ->
                manager.getCameraCharacteristics(candidate)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
            if (id == null) {
                opening.set(false)
                showStatus("Bu telefonda kullanılabilir kamera bulunamadı.")
                return
            }
            cameraId = id
            val characteristics = manager.getCameraCharacteristics(id)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            @Suppress("MissingPermission")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opening.set(false)
                    if (!cameraActive || destroyed) {
                        device.close()
                        return
                    }
                    camera = device
                    configureCamera(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    opening.set(false)
                    device.close()
                    camera = null
                    showStatus("Kamera bağlantısı kesildi. Tekrar aç.")
                }
                override fun onError(device: CameraDevice, error: Int) {
                    opening.set(false)
                    device.close()
                    camera = null
                    showStatus("Kamera açılamadı ($error). Kamera iznini kontrol et.")
                }
            }, cameraHandler)
        } catch (error: Exception) {
            opening.set(false)
            showStatus("Kamera açılamadı: ${error.message ?: "Cihaz kamerasını kontrol et."}")
        }
    }

    private fun configureCamera(device: CameraDevice) {
        try {
            val id = cameraId ?: return
            val sizes = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.toList().orEmpty()
            val selected = sizes.filter { it.width <= 1920 && it.height <= 1080 }
                .minByOrNull { abs(it.width - 1280) + abs(it.height - 720) }
                ?: sizes.minByOrNull { abs(it.width - 1280) + abs(it.height - 720) }
                ?: Size(1280, 720)
            val texture = preview.surfaceTexture ?: return
            texture.setDefaultBufferSize(selected.width, selected.height)
            val display = Surface(texture)
            val images = ImageReader.newInstance(
                selected.width, selected.height, ImageFormat.YUV_420_888, 2
            )
            reader = images
            images.setOnImageAvailableListener({ source -> onImage(source) }, cameraHandler)
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(display, images.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!cameraActive || destroyed) {
                            session.close()
                            return
                        }
                        capture = session
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(display)
                            request.addTarget(images.surface)
                            request.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            session.setRepeatingRequest(request.build(), null, cameraHandler)
                            showStatus("Kamera açık · Portal bulut ekranını kadraja al.")
                        } catch (error: CameraAccessException) {
                            showStatus("Kamera önizlemesi başlatılamadı.")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        showStatus("Telefon kamera çözünürlüğü uyumsuz. Yeniden dene.")
                    }
                }, cameraHandler)
        } catch (error: Exception) {
            showStatus("Kamera görüntüsü hazırlanamadı: ${error.message.orEmpty()}")
        }
    }

    private fun onImage(source: ImageReader) {
        val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        val now = SystemClock.elapsedRealtime()
        if (!ready || !cameraActive || destroyed || now - lastReadAt < 620L ||
            !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastReadAt = now
        val orientation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val rotation = (sensorOrientation - orientation + 360) % 360
        try {
            val input = InputImage.fromMediaImage(image, rotation)
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    if (cameraActive && !destroyed) onRecognized(
                        result, if (rotation % 180 == 0) image.width else image.height,
                        if (rotation % 180 == 0) image.height else image.width,
                    )
                }
                .addOnFailureListener {
                    showStatus("Kamera OCR metni okuyamadı. Portal ekranına odaklan.")
                }
                .addOnCompleteListener {
                    image.close()
                    busy.set(false)
                }
        } catch (_: RuntimeException) {
            image.close()
            busy.set(false)
        }
    }

    private fun onRecognized(result: com.google.mlkit.vision.text.Text, width: Int, height: Int) {
        val area = Pixels(0, 0, width, height)
        val lines = result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                line.boundingBox?.let { box ->
                    CloudTextBlock(line.text, box.left, box.top, box.right, box.bottom)
                }
            }
        }
        captions.read(CloudTextLayout.subtitles(area, config.bandTop, lines))
        val blocks = result.textBlocks.mapNotNull { block ->
            block.boundingBox?.let { box ->
                CloudTextBlock(block.text, box.left, box.top, box.right, box.bottom)
            }
        }
        val selected = CloudTextLayout.messages(area, config, blocks)
        lens.read(
            selected.map {
                DetectedLine(it.text, Rect(it.left, it.top, it.right, it.bottom))
            },
            FrameRegion(0, 0, width, height),
        )
    }

    private fun stopCamera() {
        opening.set(false)
        cameraHandler.post {
            runCatching { capture?.close() }
            capture = null
            runCatching { camera?.close() }
            camera = null
            runCatching { reader?.close() }
            reader = null
        }
    }

    private fun showStatus(text: String) {
        main.post { if (!destroyed && ::status.isInitialized) status.text = text }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 701) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            else showStatus("Bulut oyunlarını canlı okumak için kamera izni gerekiyor.")
        }
    }

    override fun onDestroy() {
        destroyed = true
        cameraActive = false
        stopCamera()
        cameraThread.quitSafely()
        captions.close()
        lens.close()
        recognizer.close()
        translator.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }


    private fun action(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = panel(0xFF3A3563.toInt(), 10)
        setOnClickListener { click() }
    }

    private fun panel(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class CloudRegionView : View(this@CloudCameraActivity) {
        private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(14).toFloat(); color = Color.WHITE }
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE8192033.toInt() }
        private var translated = ""

        fun setTranslation(value: String) {
            if (translated != value) {
                translated = value
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val frame = Pixels(0, 0, width, height)
            val subtitles = config.subtitleArea(frame)
            val messages = config.lensArea(frame)
            brush.color = 0xFF58C8FF.toInt()
            canvas.drawRoundRect(
                subtitles.x.toFloat(), subtitles.y.toFloat(),
                (subtitles.x + subtitles.width).toFloat(),
                (subtitles.y + subtitles.height).toFloat(), 12f, 12f, brush,
            )
            brush.color = 0xFF55E3B5.toInt()
            canvas.drawRoundRect(
                messages.x.toFloat(), messages.y.toFloat(),
                (messages.x + messages.width).toFloat(),
                (messages.y + messages.height).toFloat(), 12f, 12f, brush,
            )
            if (translated.isNotBlank() && messages.width > dp(100)) {
                val left = messages.x + dp(7).toFloat()
                val top = messages.y + dp(7).toFloat()
                val maxWidth = (messages.width - dp(20)).toFloat()
                val rows = mutableListOf<String>()
                var row = ""
                for (word in translated.replace('\n', ' ').split(Regex("\\s+"))) {
                    val candidate = if (row.isEmpty()) word else "$row $word"
                    if (row.isNotEmpty() && glyph.measureText(candidate) > maxWidth) {
                        rows.add(row)
                        row = word
                    } else row = candidate
                    if (rows.size >= 3) break
                }
                if (row.isNotEmpty() && rows.size < 4) rows.add(row)
                val visible = rows.take(4)
                val lineStep = dp(19).toFloat()
                val heightNeeded = visible.size * lineStep + dp(12)
                canvas.drawRoundRect(
                    left, top, left + maxWidth, top + heightNeeded,
                    dp(8).toFloat(), dp(8).toFloat(), shade
                )
                visible.forEachIndexed { index, line ->
                    canvas.drawText(line, left + dp(5), top + dp(17) + index * lineStep, glyph)
                }
            }
        }
    }
}
