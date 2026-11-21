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
    private var translator: CaptionTranslator? = null
    private var captions: CaptionSession? = null
    private var overlay: CaptionOverlay? = null
    private var voice: TurkishVoice? = null
    private var panel: PortalPanelServer? = null
    private var sourceName = "PlayStation App"
    private var source = FrameSource.WAITING
    private var readingArea = ReadingArea.SUBTITLES
    private var epoch = 0
    private var modelReady = false
    private var ended = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish(stopMessage())
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TOGGLE_PREVIEW) {
            togglePanelPreview()
            return START_NOT_STICKY
        }
        if (frames != null || ended) return START_NOT_STICKY
        if (intent?.action == ACTION_TEST_PANEL) {
            if (panel == null) startPanelTest(intent.getIntExtra(EXTRA_SIZE, 22))
            return START_NOT_STICKY
        }

        val grant = intent?.let(::readGrant)
        if (grant == null || intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED) != Activity.RESULT_OK) {
            captureStartFailed("Android ekran paylaşımı izni gerekiyor.")
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification("Türkçe çeviri modeli hazırlanıyor…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (_: SecurityException) {
            captureStartFailed("Ekran paylaşımı iznini yeniden ver.")
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            captureStartFailed("Canlı altyazıyı uygulama açıkken yeniden başlat.")
            return START_NOT_STICKY
        }
        main.removeCallbacks(panelTestTick)
        main.removeCallbacks(panelTestTimeout)
        val config = CaptureSettings(
            intent.getIntExtra(EXTRA_TOP, CaptureSettings.DEFAULT_BAND_TOP),
            intent.getIntExtra(EXTRA_SIZE, 22),
            if (intent.getBooleanExtra(EXTRA_MOBILE, false)) Source.MOBILE else Source.PORTAL,
            SubtitleDisplay.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_DISPLAY) }
                ?: SubtitleDisplay.PHONE,
            ReadingArea.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_READING_AREA) }
                ?: ReadingArea.SUBTITLES,
        )
        if (config.showOnPhone && !Settings.canDrawOverlays(this)) {
            finish("Telefonda altyazı göstermek için üstte gösterme izni gerekiyor.")
            return START_NOT_STICKY
        }
        val language = GameTranslatorEngine(this)
        translator = language
        if (!config.showOnPortal) closePanel()
        val panelIssue = if (config.showOnPortal) ensurePanel(config.textSize) else null
        if (panelIssue != null && !config.showOnPhone) {
            finish(panelIssue)
            return START_NOT_STICKY
        }
        val previousPanel = SessionState.current.portalPanel
        panel?.publish(null)
        sourceName = config.source.appName
        readingArea = config.readingArea
        SessionState.update(SessionState.State(
            active = true,
            status = "Telefondaki Türkçe çeviri modeli hazırlanıyor…",
            portalPanel = panel?.let {
                SessionState.PortalPanel(it.url, opened = previousPanel?.opened == true && previousPanel.url == it.url)
            },
            portalIssue = panelIssue,
        ))
        ocr = SubtitleReader(readingArea)
        if (config.showOnPhone) overlay = CaptionOverlay(this, config)
        if (config.speakOnPhone) voice = TurkishVoice(this) { issue ->
            if (!ended) {
                SessionState.update(SessionState.current.copy(voiceIssue = issue))
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(issue))
            }
        }
        captions = CaptionSession(
            translate = language::translate,
            show = ::showCaption,
            onFailure = ::updateTranslationIssue,
            onRead = { recognized ->
                if (!ended) {
                    SessionState.update(SessionState.current.copy(recognizedSource = recognized, translationIssue = null))
                }
            },
            cancelTranslation = language::cancelCurrent,
            translateKnown = language::translateKnown,
            refine = language::refine,
            readingArea = readingArea,
        )
        frames = ScreenFrames(
            context = this,
            settings = config,
            reserveFrame = { ocrPending.compareAndSet(false, true) },
            discardFrame = { ocrPending.set(false) },
            onFrame = { bitmap -> main.post { recognize(bitmap) } },
            onEmptyFrame = {
                main.post {
                    ocrPending.set(false)
                    if (!ended && modelReady) captions?.read(emptyList())
                }
            },
            onSource = { value -> main.post { sourceChanged(value) } },
            onEnded = { message -> main.post { finish(message) } },
        ).also { it.start(Activity.RESULT_OK, grant) }
        main.postDelayed(modelTimeout, 180_000L)
        language.prepare { ok ->
            main.removeCallbacks(modelTimeout)
            if (ended) return@prepare
            if (!ok) {
                finish("Türkçe çeviri modeli açılamadı. Telefonda boş yer kontrolü yapıp oturumu yeniden başlat.")
                return@prepare
            }
            modelReady = true
            sourceChanged(source)
            main.post(checkFrames)
        }
        return START_NOT_STICKY
    }

    private fun startPanelTest(textSize: Int) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        val message = "Portal bağlantı testi açık · 2,5 dakika"
        try {
            startForeground(NOTIFICATION_ID, notification(message), type)
        } catch (_: IllegalStateException) {
            finish("Portal testini uygulama açıkken yeniden başlat.")
            return
        } catch (_: SecurityException) {
            finish("Android Portal testini başlatmaya izin vermedi.")
            return
        }
        val issue = ensurePanel(textSize)
        val server = panel
        if (server == null) {
            finish(issue ?: "Portal test paneli açılamadı.")
            return
        }
        SessionState.update(SessionState.State(
            status = message,
            portalPanel = SessionState.PortalPanel(server.url, testing = true),
        ))
        main.post(panelTestTick)
        main.postDelayed(panelTestTimeout, 150_000L)
    }

    private fun captureStartFailed(message: String) {
        val state = SessionState.current
        if (state.portalPanel?.testing == true && panel != null) {
            SessionState.update(state.copy(active = false, status = message))
        } else {
            finish(message)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        frames?.onLegacyRotation()
        val current = SessionState.current.caption
        overlay?.close()
        if (overlay != null && current != null) showCaption(current)
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
        reader.read(bitmap) { result ->
            ocrPending.set(false)
            if (ended || frameEpoch != epoch) return@read
            if (result == null) {
                updateStatus("Yazı tanıma yanıtı alınamadı.")
            } else if (modelReady) {
                updateStatus(when {
                    result.lines.isEmpty() -> waitingForText()
                    readingArea == ReadingArea.GAME_TEXT -> "Oyun metni okunuyor."
                    else -> "İngilizce altyazı okunuyor."
                })
                captions?.read(result.lines, result.confident)
            }
        }
    }

    private fun showCaption(caption: Caption?) {
        if (ended) return
        val state = SessionState.current
        if (state.portalPanel?.previewing != true) publishCaption(caption)
        if (caption == null && state.caption != null && readingArea == ReadingArea.SUBTITLES) voice?.pause()
        if (caption != null && caption != state.caption &&
            (readingArea == ReadingArea.GAME_TEXT || caption.source != state.caption?.source)
        ) {
            val spoken = if (readingArea == ReadingArea.GAME_TEXT) {
                val previous = state.caption?.turkish?.lines().orEmpty().toSet()
                caption.turkish.lines().filterNot(previous::contains).joinToString("\n")
            } else caption.turkish
            voice?.speak(spoken, latestOnly = readingArea == ReadingArea.SUBTITLES)
        }
        try {
            overlay?.show(caption)
        } catch (error: RuntimeException) {
            main.post { finish("Altyazı katmanı açılamadı. Üstte gösterme iznini kontrol et.") }
            return
        }
        SessionState.update(SessionState.current.copy(caption = caption, lastCaption = caption ?: state.lastCaption))
        if (caption != null) updateTranslationIssue(null)
    }

    private fun publishCaption(caption: Caption?) {
        panel?.publish(caption?.turkish)
    }

    private fun openPanel(textSize: Int): PortalPanelServer {
        val address = portalLanAddress(this)
            ?: throw IOException("Telefonu evdeki Wi-Fi ağına bağlayıp yeniden dene.")
        val page = assets.open("portal-panel.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return try {
            PortalPanelServer(address, page, textSize) {
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

    private fun ensurePanel(textSize: Int): String? {
        if (panel != null) return null
        return try {
            panel = openPanel(textSize)
            null
        } catch (error: IOException) {
            error.message ?: "Portal paneli açılamadı."
        } catch (_: SecurityException) {
            "Android yerel ağ erişimine izin vermedi."
        }
    }

    private fun closePanel() {
        val previous = panel
        panel = null
        runCatching { previous?.close() }
    }

    private fun sourceChanged(value: FrameSource) {
        if (ended) return
        if (source != value) {
            val previous = source
            source = value
            epoch++
            if (value == FrameSource.WAITING) voice?.pause()
            when {
                value == FrameSource.WAITING || previous == FrameSource.WAITING -> captions?.reset()
                value == FrameSource.DARK -> captions?.blankFrame()
            }
        }
        if (!modelReady) return
        updateStatus(when (value) {
            FrameSource.WAITING -> "${sourceName} üzerinde tam ekran oyun görüntüsünü aç."
            FrameSource.VISIBLE -> waitingForText()
            FrameSource.DARK -> "Görüntü karanlık. Yayını ve ekran paylaşımı iznini kontrol et."
        })
    }

    private fun waitingForText() = if (readingArea == ReadingArea.GAME_TEXT) {
        "Görüntü alınıyor. Okunabilir oyun metni bekleniyor."
    } else "Görüntü alınıyor. Altyazı bekleniyor."

    private fun updateStatus(message: String) {
        val state = SessionState.current
        if (ended || state.status == message) return
        SessionState.update(state.copy(status = message))
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID, notification(state.voiceIssue ?: state.translationIssue ?: message),
        )
    }

    private fun updateTranslationIssue(issue: String?) {
        val state = SessionState.current
        if (ended || state.translationIssue == issue) return
        SessionState.update(state.copy(translationIssue = issue))
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID, notification(state.voiceIssue ?: issue ?: state.status),
        )
    }

    private fun finish(message: String) {
        if (ended) return
        ended = true
        SessionState.update(SessionState.State(status = message, lastCaption = SessionState.current.lastCaption))
        stopSelf()
    }

    private fun stopMessage() = if (SessionState.current.portalPanel?.testing == true) {
        "Portal bağlantı testi durduruldu."
    } else {
        "Canlı altyazı durduruldu."
    }

    override fun onTimeout(startId: Int) {
        panelTestTimeout.run()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (fgsType == ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE) panelTestTimeout.run()
    }

    override fun onDestroy() {
        if (!ended) {
            ended = true
            SessionState.update(SessionState.State(status = stopMessage(), lastCaption = SessionState.current.lastCaption))
        }
        modelReady = false
        main.removeCallbacks(modelTimeout)
        main.removeCallbacks(checkFrames)
        main.removeCallbacks(panelTestTick)
        main.removeCallbacks(panelTestTimeout)
        main.removeCallbacks(endPanelPreview)
        frames?.close()
        captions?.close()
        overlay?.close()
        voice?.close()
        closePanel()
        ocr?.close()
        translator?.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private val modelTimeout = Runnable {
        finish("Çeviri modeli çok geç açıldı. Oturumu yeniden başlat.")
    }

    private val panelTestTimeout = Runnable {
        if (SessionState.current.portalPanel?.testing == true) finish("Portal bağlantı testi sona erdi.")
    }

    private val endPanelPreview = Runnable { setPanelPreview(false) }

    private fun togglePanelPreview() {
        val state = SessionState.current
        val current = state.portalPanel ?: return
        if (state.active && !current.testing) setPanelPreview(!current.previewing)
    }

    private fun setPanelPreview(enabled: Boolean) {
        val state = SessionState.current
        val current = state.portalPanel ?: return
        if (ended || current.previewing == enabled) return
        main.removeCallbacks(panelTestTick)
        main.removeCallbacks(endPanelPreview)
        SessionState.update(state.copy(portalPanel = current.copy(previewing = enabled)))
        if (enabled) {
            panelTestTick.run()
            main.postDelayed(endPanelPreview, 180_000L)
        } else {
            publishCaption(state.caption)
        }
    }

    private val panelTestTick = object : Runnable {
        private var first = true
        override fun run() {
            val current = SessionState.current.portalPanel ?: return
            if (ended || (!current.testing && !current.previewing)) return
            panel?.publish(
                if (first) "Beni bekleme.\nSen önden git." else "Gözünü yoldan ayırma.\nKöprüde buluşuruz.",
                testing = true,
            )
            first = !first
            main.postDelayed(this, 2300L)
        }
    }

    private val checkFrames = object : Runnable {
        override fun run() {
            if (ended) return
            val arrived = frames?.lastFrameAt ?: 0L
            if (arrived == 0L || SystemClock.elapsedRealtime() - arrived > 5000L) {
                updateStatus("${sourceName} üzerinde tam ekran oyun görüntüsünü aç.")
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
        private const val ACTION_TEST_PANEL = "com.draborneagle.draborntranslate.TEST_PANEL"
        private const val ACTION_TOGGLE_PREVIEW = "com.draborneagle.draborntranslate.TOGGLE_PREVIEW"
        private const val EXTRA_GRANT = "grant"
        private const val EXTRA_RESULT = "resultCode"
        private const val EXTRA_TOP = "bandTop"
        private const val EXTRA_SIZE = "textSize"
        private const val EXTRA_MOBILE = "mobile"
        private const val EXTRA_DISPLAY = "display"
        private const val EXTRA_READING_AREA = "readingArea"

        internal fun start(context: Context, resultCode: Int, grant: Intent, config: CaptureSettings) {
            context.startForegroundService(Intent(context, CaptureService::class.java).apply {
                putExtra(EXTRA_GRANT, grant)
                putExtra(EXTRA_RESULT, resultCode)
                putExtra(EXTRA_TOP, config.bandTop)
                putExtra(EXTRA_SIZE, config.textSize)
                putExtra(EXTRA_MOBILE, config.source == Source.MOBILE)
                putExtra(EXTRA_DISPLAY, config.display.name)
                putExtra(EXTRA_READING_AREA, config.readingArea.name)
            })
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }

        internal fun testPanel(context: Context, textSize: Int) {
            context.startForegroundService(Intent(context, CaptureService::class.java).apply {
                action = ACTION_TEST_PANEL
                putExtra(EXTRA_SIZE, textSize)
            })
        }

        internal fun togglePreview(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_TOGGLE_PREVIEW))
        }
    }
}
