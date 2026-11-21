// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal enum class FrameSource { WAITING, VISIBLE, DARK }

/** Android'in izin verdiği yansıtmayı okur; görüntü hiçbir dosyaya yazılmaz. */
internal class ScreenFrames(
    private val context: Context,
    private val settings: CaptureSettings,
    private val reserveFrame: () -> Boolean,
    private val discardFrame: () -> Unit,
    private val onFrame: (Bitmap) -> Unit,
    private val onEmptyFrame: () -> Unit,
    private val onSource: (FrameSource) -> Unit,
    private val onEnded: (String) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("DraBornTranslate-frame").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    @Volatile var lastFrameAt = 0L
        private set

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var video = Pixels(0, 0, 0, 0)
    private var scan = Pixels(0, 0, 0, 0)
    private var scanBuffer: ByteBuffer? = null
    private var lastSampleAt = 0L
    private var darkSince = 0L
    private var signal = FrameSource.WAITING
    private var visible = true

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!closed) onEnded("Android ekran paylaşımını sonlandırdı.")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            changeSize(width, height)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            darkSince = 0L
            if (!isVisible) setSignal(FrameSource.WAITING)
        }
    }

    fun start(resultCode: Int, grant: Intent) {
        handler.post {
            if (closed) return@post
            try {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                val session = checkNotNull(manager.getMediaProjection(resultCode, grant))
                projection = session
                session.registerCallback(callback, handler)
                val size = displaySize()
                attachReader(size.first, size.second, session)
            } catch (error: RuntimeException) {
                onEnded("Ekran paylaşımı başlatılamadı. Android iznini yeniden ver.")
            }
        }
    }

    fun onLegacyRotation() {
        if (Build.VERSION.SDK_INT >= 34) return
        handler.post {
            val size = displaySize()
            changeSize(size.first, size.second)
        }
    }

    private fun changeSize(width: Int, height: Int) {
        val session = projection ?: return
        if (closed || width < 2 || height < 2) return
        try {
            attachReader(width, height, session)
        } catch (error: RuntimeException) {
            onEnded("Yeni ekran boyutu okunamadı. Altyazıyı yeniden başlat.")
        }
    }

    private fun attachReader(width: Int, height: Int, session: MediaProjection) {
        val captureWidth = 1920f
        val scale = minOf(1f, captureWidth / maxOf(width, height))
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(2)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(2)
        val previous = reader
        if (previous?.width == scaledWidth && previous.height == scaledHeight) return

        val nextVideo = videoArea(scaledWidth, scaledHeight)
        val nextScan = settings.scanArea(nextVideo)
        val nextReader = ImageReader.newInstance(scaledWidth, scaledHeight, PixelFormat.RGBA_8888, 2)
        try {
            nextReader.setOnImageAvailableListener(::onImage, handler)
            val density = context.resources.configuration.densityDpi
            val target = display
            if (target == null) {
                display = checkNotNull(
                    session.createVirtualDisplay(
                        "DraBornTranslate",
                        scaledWidth,
                        scaledHeight,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        nextReader.surface,
                        null,
                        handler,
                    ),
                )
            } else {
                // Her izin yalnız bir VirtualDisplay oluşturabilir. Döndürmede aynı ekran yeniden boyutlanır.
                target.surface = null
                target.resize(scaledWidth, scaledHeight, density)
                target.surface = nextReader.surface
            }
        } catch (error: RuntimeException) {
            nextReader.close()
            throw error
        }
        reader = nextReader
        video = nextVideo
        scan = nextScan
        scanBuffer = null
        lastSampleAt = 0L
        darkSince = 0L
        previous?.close()
        setSignal(FrameSource.WAITING)
    }

    private fun onImage(newest: ImageReader) {
        if (closed || newest !== reader) return
        val image = try {
            newest.acquireLatestImage()
        } catch (error: IllegalStateException) {
            return
        } ?: return
        image.use {
            if (!visible) return
            val now = SystemClock.elapsedRealtime()
            lastFrameAt = now
            if (now - lastSampleAt < settings.readingArea.sampleIntervalMs) return

            val plane = image.planes.firstOrNull() ?: return
            val rgba = plane.buffer
            val rowStride = plane.rowStride
            if (plane.pixelStride != 4 || rowStride < image.width * 4) return

            if (isDark(rgba, rowStride)) {
                lastSampleAt = now
                if (darkSince == 0L) darkSince = now
                if (now - darkSince >= 2700L) setSignal(FrameSource.DARK)
                if (reserveFrame()) onEmptyFrame()
                return
            }
            darkSince = 0L
            setSignal(FrameSource.VISIBLE)
            if (!reserveFrame()) return
            lastSampleAt = now
            val bitmap = try {
                copyScanArea(rgba, rowStride)
            } catch (error: RuntimeException) {
                discardFrame()
                onEnded("Oyun karesi okunamadı. Kaynağı açıp yeniden dene.")
                return
            }
            if (bitmap == null) discardFrame() else onFrame(bitmap)
        }
    }

    private fun isDark(pixels: ByteBuffer, stride: Int): Boolean {
        var lit = 0
        for (row in 1..5) {
            val y = video.y + video.height * row / 6
            for (column in 1..9) {
                val x = video.x + video.width * column / 10
                val index = pixels.position() + y * stride + x * 4
                if (index + 2 >= pixels.limit()) continue
                if ((pixels.get(index).toInt() and 255) > 20 ||
                    (pixels.get(index + 1).toInt() and 255) > 20 ||
                    (pixels.get(index + 2).toInt() and 255) > 20
                ) lit++
                if (lit >= 2) return false
            }
        }
        // Karanlık bir sahnede okuma alanında açık renkli yazı varsa OCR'a devam et.
        for (y in scan.y until scan.y + scan.height step 4) {
            for (x in scan.x until scan.x + scan.width step 6) {
                val index = pixels.position() + y * stride + x * 4
                if (index + 2 >= pixels.limit()) continue
                if ((pixels.get(index).toInt() and 255) > 70 ||
                    (pixels.get(index + 1).toInt() and 255) > 70 ||
                    (pixels.get(index + 2).toInt() and 255) > 70
                ) return false
            }
        }
        return true
    }

    private fun copyScanArea(source: ByteBuffer, stride: Int): Bitmap? {
        val rowBytes = scan.width * 4
        val offset = source.position() + scan.y * stride + scan.x * 4
        if (offset + (scan.height - 1) * stride + rowBytes > source.limit()) return null
        val input = source.duplicate()
        val output = scanBuffer ?: ByteBuffer.allocateDirect(rowBytes * scan.height).also { scanBuffer = it }
        output.clear()
        for (row in 0 until scan.height) {
            val start = offset + row * stride
            input.limit(start + rowBytes)
            input.position(start)
            output.put(input)
        }
        output.flip()
        return Bitmap.createBitmap(scan.width, scan.height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(output)
        }
    }

    private fun setSignal(next: FrameSource) {
        if (signal == next) return
        signal = next
        onSource(next)
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Pair<Int, Int> {
        val manager = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = manager.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        manager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.post {
            display?.release()
            reader?.close()
            projection?.unregisterCallback(callback)
            projection?.stop()
            scanBuffer = null
            thread.quitSafely()
        }
    }
}
