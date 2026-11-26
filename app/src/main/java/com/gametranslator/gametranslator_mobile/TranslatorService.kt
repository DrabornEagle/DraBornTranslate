// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile

import com.draborneagle.draborntranslate.ConsoleSession
import com.draborneagle.draborntranslate.FocusActivity
import com.draborneagle.draborntranslate.GtaMissionRecording
import com.draborneagle.draborntranslate.R
import com.draborneagle.draborntranslate.VISUAL_TRANSLATION

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.gametranslator.gametranslator_mobile.core.UiText

/**
 * Çeviri sürerken uygulamayı ayakta tutan ön plan servisi: ekran kapansa ya da başka uygulamaya geçilse de PS5
 * akışı, yazı okuma ve TV'ye gönderim sürer. İşin kendisi [Engine]'dedir.
 */
class TranslatorService : Service() {
    companion object {
        const val ACTION_STOP = "com.gametranslator.mobile.STOP"
        private const val CHANNEL = "translation"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, missionId: String? = null) {
            val intent = Intent(context, TranslatorService::class.java)
                .putExtra(GtaMissionRecording.EXTRA_MISSION_ID, missionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TranslatorService::class.java))
        }

        @Volatile private var live: TranslatorService? = null

        /** Arayüz dili değişti: servis çalışıyorsa bildirim yeni dille yeniden yazılır. */
        fun refreshNotification() {
            val service = live ?: return
            (service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, service.buildNotification())
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        CrashLog.install(this)
        Engine.note(2, "hizmet başladı (${if (intent == null) "sistem yeniden başlattı" else "kullanıcı başlattı"})")
        // intent == null: hizmeti sistem yeniden başlattı. Uygulama az önce çökerek kapandıysa çeviri kendiliğinden
        // yeniden başlatılmaz; yoksa aynı hata her seferinde uygulamayı yeniden kapatır (kullanıcı "çeviri
        // başlatılıyor" görüp ana ekrana atılıyordu). Neden günlükte yazar, kullanıcı çeviriyi kendisi başlatır.
        if (intent == null && CrashLog.crashedRecently(this)) {
            Engine.noteLastExit(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION_ID, notification)
        if (!ConsoleSession.begin()) {
            stopSelf()
            return START_NOT_STICKY
        }
        live = this
        acquireLocks()
        Engine.start(this, intent?.getStringExtra(GtaMissionRecording.EXTRA_MISSION_ID))
        // START_NOT_STICKY: süreç ölürse (çökme, bellek) sistem hizmeti kendiliğinden yeniden başlatmaz. Eskiden
        // yeniden başlatıyordu ve çeviri kullanıcı "başlat" demeden açılıyordu: çökme döngüsü, ve Xbox'ta kullanıcı
        // istemeden açılıp kapanan uzaktan oynatma oturumu. Çeviriyi kullanıcı yeniden başlatır.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        live = null
        Engine.stop()
        ConsoleSession.end()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** Ekran kapalıyken işlemci ve Wi-Fi uykuya geçmesin: akış kopar, TV altyazısız kalır. */
    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gametranslator:translation").apply { acquire() }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "gametranslator:translation").apply { acquire() }
        }
    }

    /** Bildirim arayüz diliyle yazılır (Flutter'dan geçmez: [UiText]). */
    private fun buildNotification(): Notification {
        UiText.use(Store(this).settings.uiLanguage)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, UiText.t("notification.channel"), NotificationManager.IMPORTANCE_LOW))
        val focus = PendingIntent.getActivity(this, 18, Intent(this, FocusActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, ConsoleStudioActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TranslatorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle(UiText.t("notification.title"))
            .setContentText(UiText.t("notification.text"))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, VISUAL_TRANSLATION, focus).build())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, UiText.t("notification.stop"), stop).build())
            .build()
    }
}
