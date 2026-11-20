// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val ocrPending = AtomicBoolean(false)
    private var frames: ScreenFrames? = null
    private var ocr: SubtitleReader? = null
    private var translator: DeviceTranslator? = null
    private var captions: CaptionSession? = null
    private var overlay: CaptionOverlay? = null
    private var panel: PortalPanelServer? = null
    private var sourceName = "PlayStation App"
    private var source = FrameSource.WAITING
    private var epoch = 0
    @Volatile private var modelReady = false
    private var ended = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish("Canlı altyazı durduruldu.")
            return START_NOT_STICKY
        }
        if (frames != null || ended) return START_NOT_STICKY

        val grant = intent?.let(::readGrant)
        if (grant == null || intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED) != Activity.RESULT_OK) {
            finish("Android ekran paylaşımı izni gerekiyor.")
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification("Türkçe çeviri modeli hazırlanıyor…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (error: SecurityException) {
            finish("Ekran paylaşımı iznini yeniden ver.")
            return START_NOT_STICKY
        }
        val config = CaptureSettings(
            intent.getIntExtra(EXTRA_TOP, 65),
            intent.getIntExtra(EXTRA_SIZE, 22),
            if (intent.getBooleanExtra(EXTRA_MOBILE, false)) Source.MOBILE else Source.PORTAL,
            SubtitleDisplay.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_DISPLAY) }
                ?: SubtitleDisplay.PHONE,
        )
        if (config.showOnPhone && !Settings.canDrawOverlays(this)) {
            finish("Telefonda altyazı göstermek için üstte gösterme izni gerekiyor.")
            return START_NOT_STICKY
        }
        var panelIssue: String? = null
        if (config.showOnPortal) {
            try {
                panel = openPanel(config.textSize)
            } catch (error: IOException) {
                panelIssue = error.message ?: "Portal paneli açılamadı."
            } catch (_: SecurityException) {
                panelIssue = "Android yerel ağ erişimine izin vermedi."
            }
            if (panel == null && !config.showOnPhone) {
                finish(panelIssue ?: "Portal paneli açılamadı.")
                return START_NOT_STICKY
            }
        }
        sourceName = config.source.appName
        SessionState.update(SessionState.State(
            active = true,
            status = "Türkçe çeviri modeli hazırlanıyor…",
            portalPanel = panel?.let { SessionState.PortalPanel(it.url) },
            portalIssue = panelIssue,
        ))
        val language = DeviceTranslator().also { translator = it }
        ocr = SubtitleReader()
        if (config.showOnPhone) overlay = CaptionOverlay(this, config)
        captions = CaptionSession(
            translate = language::translate,
            show = ::showCaption,
            onFailure = { updateStatus("Çeviri yanıtı alınamadı. Satır yeniden denenecek.") },
        )
        frames = ScreenFrames(
            context = this,
            bandTop = config.bandTop,
            reserveFrame = { modelReady && ocrPending.compareAndSet(false, true) },
            discardFrame = { ocrPending.set(false) },
            onFrame = { bitmap -> main.post { recognize(bitmap) } },
            onSource = { value -> main.post { sourceChanged(value) } },
            onEnded = { message -> main.post { finish(message) } },
        ).also { it.start(Activity.RESULT_OK, grant) }
        main.postDelayed(modelTimeout, 180_000L)
        language.prepare { ok ->
            main.removeCallbacks(modelTimeout)
            if (ended) return@prepare
            if (!ok) {
                finish("Türkçe çeviri modeli indirilemedi. İnterneti kontrol edip yeniden başlat.")
                return@prepare
            }
            modelReady = true
            sourceChanged(source)
            main.post(checkFrames)
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        frames?.onLegacyRotation()
        val current = SessionState.current.caption
        overlay?.close()
        if (current != null) showCaption(current)
    }

    private fun recognize(bitmap: Bitmap) {
        val reader = ocr
        if (ended || reader == null) {
            bitmap.recycle()
            ocrPending.set(false)
            return
        }
        if (source != FrameSource.VISIBLE) sourceChanged(FrameSource.VISIBLE)
        val frameEpoch = epoch
        reader.read(bitmap) { lines ->
            ocrPending.set(false)
            if (ended || frameEpoch != epoch) return@read
            if (lines == null) {
                updateStatus("Yazı tanıma yanıtı alınamadı.")
            } else {
                updateStatus(if (lines.isEmpty()) "Görüntü alınıyor. Altyazı bekleniyor." else "İngilizce altyazı okunuyor.")
                captions?.read(lines)
            }
        }
    }

    private fun showCaption(caption: Caption?) {
        if (ended) return
        panel?.publish(caption?.turkish)
        try {
            overlay?.show(caption?.turkish)
        } catch (error: RuntimeException) {
            main.post { finish("Altyazı katmanı açılamadı. Üstte gösterme iznini kontrol et.") }
            return
        }
        SessionState.update(SessionState.current.copy(caption = caption))
    }

    private fun openPanel(textSize: Int): PortalPanelServer {
        val address = portalLanAddress(this)
            ?: throw IOException("Telefonu PS5 ile aynı yerel Wi-Fi ağına bağla, ardından canlı altyazıyı başlat.")
        val page = assets.open("portal-panel.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val badge = resources.openRawResource(R.raw.google_translate_attribution).use { it.readBytes() }
        return try {
            PortalPanelServer(address, page, badge, textSize) {
                main.post {
                    val state = SessionState.current
                    val currentPanel = state.portalPanel
                    if (!ended && currentPanel != null) {
                        SessionState.update(state.copy(portalPanel = currentPanel.copy(opened = true)))
                    }
                }
            }
        } catch (_: IOException) {
            throw IOException("Portal paneli yerel ağda açılamadı. Wi-Fi bağlantını kontrol et.")
        }
    }

    private fun sourceChanged(value: FrameSource) {
        if (ended) return
        if (source != value) {
            source = value
            epoch++
            captions?.reset()
        }
        if (!modelReady) return
        updateStatus(when (value) {
            FrameSource.WAITING -> "${sourceName} üzerinde tam ekran oyun görüntüsünü aç."
            FrameSource.VISIBLE -> "Görüntü alınıyor. Altyazı bekleniyor."
            FrameSource.DARK -> "Görüntü karanlık. Yayını ve ekran paylaşımı iznini kontrol et."
        })
    }

    private fun updateStatus(message: String) {
        val state = SessionState.current
        if (ended || state.status == message) return
        SessionState.update(state.copy(status = message))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun finish(message: String) {
        if (ended) return
        ended = true
        SessionState.update(SessionState.State(status = message))
        stopSelf()
    }

    override fun onDestroy() {
        if (!ended) {
            ended = true
            SessionState.update(SessionState.State(status = "Canlı altyazı durduruldu."))
        }
        modelReady = false
        main.removeCallbacks(modelTimeout)
        main.removeCallbacks(checkFrames)
        frames?.close()
        captions?.close()
        overlay?.close()
        runCatching { panel?.close() }
        panel = null
        ocr?.close()
        translator?.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private val modelTimeout = Runnable {
        finish("Dil modeli hazırlanamadı. İnternet bağlantısını kontrol edip tekrar dene.")
    }

    private val checkFrames = object : Runnable {
        override fun run() {
            if (ended) return
            val arrived = frames?.lastFrameAt ?: 0L
            if (arrived == 0L || SystemClock.elapsedRealtime() - arrived > 5000L) {
                sourceChanged(FrameSource.WAITING)
            }
            main.postDelayed(this, 1000L)
        }
    }

    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Canlı altyazı", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DraBornTranslate")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .build()
    }

    @Suppress("DEPRECATION")
    private fun readGrant(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_GRANT, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_GRANT)

    companion object {
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 25
        private const val ACTION_STOP = "com.draborneagle.draborntranslate.STOP"
        private const val EXTRA_GRANT = "grant"
        private const val EXTRA_RESULT = "resultCode"
        private const val EXTRA_TOP = "bandTop"
        private const val EXTRA_SIZE = "textSize"
        private const val EXTRA_MOBILE = "mobile"
        private const val EXTRA_DISPLAY = "display"

        internal fun start(context: Context, resultCode: Int, grant: Intent, config: CaptureSettings) {
            context.startForegroundService(Intent(context, CaptureService::class.java).apply {
                putExtra(EXTRA_GRANT, grant)
                putExtra(EXTRA_RESULT, resultCode)
                putExtra(EXTRA_TOP, config.bandTop)
                putExtra(EXTRA_SIZE, config.textSize)
                putExtra(EXTRA_MOBILE, config.source == Source.MOBILE)
                putExtra(EXTRA_DISPLAY, config.display.name)
            })
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
