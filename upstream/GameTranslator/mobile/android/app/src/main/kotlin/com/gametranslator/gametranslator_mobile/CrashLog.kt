// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uygulama kendiliğinden kapanınca nedeni saklar; sonraki açılışta günlüğe (Ayarlar > Günlük) yazılır. Kullanıcıların
 * "çeviri başlayınca ana ekrana atıyor" bildirimlerinde neden başka türlü öğrenilemiyordu.
 *  - Java/Kotlin hatası: yakalanmayan hata dosyaya yazılır (iz dahil).
 *  - Yerel (C/C++) çökme, bellek yetmemesi, yanıt vermeme: Android 11+ sürecin son kapanma nedenini verir.
 * Hiçbir şey cihaz dışına gönderilmez.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val PREFS = "crash-log"
    private const val KEY_REPORTED = "reported-exit"
    private const val KEY_XBOX_OPEN = "xbox-open"
    private const val KEY_TRACE = "reported-native-crash"
    // "-2": ilk sürüm her yerel çökmede yazılım çözücüsüne geçiyordu (kitaplık yükleme çökmesinde de); o işaret sayılmaz.
    private const val KEY_XBOX_SAFE = "xbox-safe-decoder-2"
    private const val MAX_TRACE_LINES = 24

    @Volatile private var installed = false

    /** Süreçte bir kez: yakalanmayan hatayı dosyaya yazan işleyici (önceki işleyici de çağrılır, süreç yine kapanır). */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, FILE).writeText(describe(thread.name, error, System.currentTimeMillis()))
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Hatanın günlükte okunacak metni: an, iş parçacığı, hata ve nedenleri, izin ilk satırları. */
    internal fun describe(thread: String, error: Throwable, atMs: Long): String {
        val text = StringBuilder()
        text.append(stamp(atMs)).append(" iş parçacığı ").append(thread).append('\n')
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            text.append(if (depth == 0) "" else "neden: ").append(current.javaClass.name).append(": ").append(current.message ?: "").append('\n')
            for (frame in current.stackTrace.take(if (depth == 0) MAX_TRACE_LINES else 8)) text.append("  ").append(frame).append('\n')
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return text.toString().trimEnd()
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    /**
     * Önceki kapanmanın günlük satırları (yoksa boş). Her kapanma bir kez döner: saklanan Java hatası silinir, sistemin
     * verdiği kapanma kaydı da işaretlenir.
     */
    @Synchronized
    fun collect(context: Context): List<String> {
        val app = context.applicationContext
        val lines = ArrayList<String>()
        try {
            val file = File(app.filesDir, FILE)
            if (file.exists()) {
                lines += "önceki açılışta uygulama hatayla kapandı:"
                lines += file.readText().lines().filter { it.isNotBlank() }
                file.delete()
            }
        } catch (ignored: Throwable) {
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = exits(app, 16)
        history.firstOrNull()?.let { exit ->
            if (prefs.getLong(KEY_REPORTED, 0L) != exit.atMs) {
                prefs.edit().putLong(KEY_REPORTED, exit.atMs).apply()
                if (exit.abnormal) lines += "önceki kapanma (${stamp(exit.atMs)}): ${exit.reason}" + (exit.detail?.let { " — $it" } ?: "")
            }
        }
        // Son yerel çökmenin ayrıntısı bir kez yazılır. En son kapanma olması gerekmez: kullanıcı çökmeden sonra
        // uygulamayı açıp kapatmış ya da çökme bu ayrıntıyı yazmayan eski sürümde olmuş olabilir.
        history.firstOrNull { it.native }?.let { crash ->
            if (prefs.getLong(KEY_TRACE, 0L) != crash.atMs) {
                prefs.edit().putLong(KEY_TRACE, crash.atMs).apply()
                val trace = crash.trace()
                lines += "yerel çökme ayrıntısı (${stamp(crash.atMs)}):"
                lines += trace.ifEmpty { listOf("  döküm yok (Android 12 öncesi ya da sistem saklamamış)") }
                // Çökme görüntü çözücüsündeyse sonraki Xbox oturumları yazılım çözücüsüyle açılır (daha çok işlemci
                // harcar ama üreticinin donanım çözücüsünden geçmez). Başka yerdeki çökmede bu önlem alınmaz.
                if (inVideoDecoder(trace) && !prefs.getBoolean(KEY_XBOX_SAFE, false)) {
                    prefs.edit().putBoolean(KEY_XBOX_SAFE, true).apply()
                    lines += "çökme görüntü çözücüsünde: bundan sonra Xbox görüntüsü yazılım çözücüsüyle açılacak"
                }
            }
        }
        prefs.edit().putBoolean(KEY_XBOX_OPEN, false).apply()
        lines += device()
        return lines
    }

    @Volatile private var deviceLogged = false

    /** Cihaz bilgisi süreçte bir kez yazılır: çökme bildirimlerinde hangi cihaz ve Android sürümü olduğu görülsün. */
    private fun device(): List<String> {
        if (deviceLogged) return emptyList()
        deviceLogged = true
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return listOf("cihaz: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), yonga $soc")
    }

    /** Xbox oturumu açıldı / düzgün kapandı. Açıkken süreç ölürse işaret kalır ve [collect] bunu görür. */
    fun markXboxOpen(context: Context, open: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_XBOX_OPEN, open).apply()
    }

    /** Xbox görüntüsü yazılım çözücüsüyle mi açılsın (önceki Xbox oturumunda yerel çökme olduysa). */
    fun xboxSafeDecoder(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_XBOX_SAFE, false)

    /** Uygulama az önce (son [withinMs] içinde) çökerek mi kapandı: sistemin yeniden başlattığı hizmet döngüye girmesin. */
    fun crashedRecently(context: Context, withinMs: Long = 120_000L): Boolean {
        val exit = lastExit(context.applicationContext) ?: return false
        return exit.abnormal && System.currentTimeMillis() - exit.atMs < withinMs
    }

    /** Çökme özeti görüntü çözücüsünü mü gösteriyor (çözücü iş parçacığı, MediaCodec ya da Codec2 kareleri). */
    internal fun inVideoDecoder(trace: List<String>): Boolean = trace.any { line ->
        listOf("Decod", "decod", "MediaCodec", "mediacodec", "libstagefright", "libcodec2", "omx", "OMX").any { it in line }
    }

    internal class Exit(val atMs: Long, val reason: String, val detail: String?, val abnormal: Boolean,
        val native: Boolean = false, val trace: () -> List<String> = { emptyList() })

    /** Kapanma nedeninin günlükteki adı; olağan kapanmalar (kullanıcı kapattı, sistem güncelledi...) [abnormal] değildir. */
    internal fun classify(reason: Int): Pair<String, Boolean> = when (reason) {
        4 -> "çökme (Java/Kotlin hatası)" to true                 // REASON_CRASH
        5 -> "yerel çökme (C/C++ kitaplığı)" to true              // REASON_CRASH_NATIVE
        6 -> "yanıt vermedi (ANR)" to true                        // REASON_ANR
        3 -> "bellek yetmedi, sistem kapattı" to true             // REASON_LOW_MEMORY
        9 -> "aşırı kaynak kullanımı, sistem kapattı" to true     // REASON_EXCESSIVE_RESOURCE_USAGE
        7 -> "başlatma hatası" to true                            // REASON_INITIALIZATION_FAILURE
        else -> "olağan ($reason)" to false
    }

    private fun lastExit(app: Context): Exit? = exits(app, 1).firstOrNull()

    /** Sürecin son kapanmaları, en yenisi başta (Android 11+; öncesinde boş). */
    private fun exits(app: Context, max: Int): List<Exit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(app.packageName, 0, max).map { info: ApplicationExitInfo ->
                val (name, abnormal) = classify(info.reason)
                val native = info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                // Android 12+: yerel çökmenin dökümü; günlüğe yalnız özeti (sinyal, kitaplık ve işlev adları) yazılır.
                val trace: () -> List<String> = {
                    if (native && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            info.traceInputStream?.use { com.gametranslator.gametranslator_mobile.core.TombstoneSummary.summarize(it.readBytes()) } ?: emptyList()
                        } catch (error: Throwable) {
                            listOf("  çökme dökümü okunamadı: ${error.javaClass.simpleName}")
                        }
                    } else emptyList()
                }
                Exit(info.timestamp, name, info.description?.take(200), abnormal, native, trace)
            }
        } catch (error: Throwable) {
            emptyList()
        }
    }
}
