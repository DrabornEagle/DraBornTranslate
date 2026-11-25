// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.draborneagle.draborntranslate.ConsolePairing
import com.draborneagle.draborntranslate.ConsoleSession
import com.draborneagle.draborntranslate.ConsoleSetupActivity
import com.draborneagle.draborntranslate.TvPanelActivity
import com.draborneagle.draborntranslate.FocusActivity
import com.draborneagle.draborntranslate.GamePacksActivity
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import com.gametranslator.gametranslator_mobile.core.Languages
import com.gametranslator.gametranslator_mobile.core.LoopbackRelay
import com.gametranslator.gametranslator_mobile.core.OtherServers
import com.gametranslator.gametranslator_mobile.core.ServerAddress
import com.gametranslator.gametranslator_mobile.core.UiText
import com.gametranslator.gametranslator_mobile.xbox.XboxConsole
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Flutter arayüzü ile [Engine] arasındaki köprü: "gt/bridge" yöntem kanalı, "gt/events" durum akışı ve
 * "gt/subtitles" altyazı olayları ("Bu telefonda göster" ekranı).
 */
class ConsoleStudioActivity : FlutterActivity() {
    private val background = Executors.newCachedThreadPool()

    /** "Bilgisayarın altyazısını göster" ekranı açıkken bilgisayarın sayfasını telefonun kendi adresinden veren köprü. */
    private var displayRelay: LoopbackRelay? = null
    private var events: EventChannel.EventSink? = null
    private var subtitles: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        MethodChannel(messenger, "gt/bridge").setMethodCallHandler(::handle)
        // Telefondan TV uygulaması kurma sihirbazı (tvinstall/).
        com.gametranslator.gametranslator_mobile.tvinstall.TvInstallChannel.attach(this, messenger)
        EventChannel(messenger, "gt/events").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                this@ConsoleStudioActivity.events = events
                Engine.onChanged = { state -> this@ConsoleStudioActivity.events?.success(state) }
                events.success(Engine.state())
            }

            override fun onCancel(arguments: Any?) {
                this@ConsoleStudioActivity.events = null
            }
        })
        EventChannel(messenger, "gt/subtitles").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                this@ConsoleStudioActivity.subtitles = events
                Engine.onSubtitle = { event -> this@ConsoleStudioActivity.subtitles?.success(event) }
            }

            override fun onCancel(arguments: Any?) {
                this@ConsoleStudioActivity.subtitles = null
            }
        })
    }

    override fun onDestroy() {
        events = null
        subtitles = null
        closeDisplayRelay()
        background.shutdown()
        super.onDestroy()
    }

    @Synchronized
    private fun closeDisplayRelay() {
        displayRelay?.stop()
        displayRelay = null
    }

    @Synchronized
    private fun openDisplayRelay(host: String, port: Int): Int {
        displayRelay?.stop()
        return LoopbackRelay(host, port).also { displayRelay = it }.start()
    }

    /**
     * Ekran olarak bağlanılacak GameTranslator sunucusunu arar (bloklar): en son bağlanılan adres çeviri yapıyorsa o;
     * değilse telefonun /24 ağında çeviri yapan, o da yoksa bulunan herhangi biri (ya da yanıt veren son adres).
     */
    private fun findDisplayServer(store: Store): Map<String, Any?> {
        val own = Engine.localAddress()
        val choice = ServerAddress.choose(
            last = store.displayServer?.let { ServerAddress.parse(it) },
            probe = { OtherServers.hello(it.host, it.port) },
            scan = { own?.let { address -> OtherServers.findDisplaySource(address) } },
        ) ?: return mapOf("found" to false, "wifi" to (own != null))
        return mapOf("found" to true, "host" to choice.first.host, "port" to choice.first.port, "active" to choice.second, "wifi" to (own != null))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        migratePairing()
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        Engine.noteLastExit(this)
    }

    private fun migratePairing() {
        val flag = getSharedPreferences("console-studio-migration", MODE_PRIVATE)
        if (flag.getBoolean("paired-v5", false)) return
        val store = Store(this)
        if (store.console == null && store.accountId == null) {
            ConsolePairing.load(this)?.let { old ->
                store.accountId = old.account
                store.console = Console(old.host, old.name, old.keys.copyOfRange(0, 16), old.keys.copyOfRange(16, 32))
                store.introSeen = true
            }
        }
        flag.edit().putBoolean("paired-v5", true).apply()
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        val store = Store(this)
        when (call.method) {
            "getState" -> result.success(Engine.state())
            "openTool" -> {
                when (call.arguments as? String) {
                    "focus" -> startActivity(Intent(this, FocusActivity::class.java))
                    "packs" -> startActivity(Intent(this, GamePacksActivity::class.java))
                    "tvBrowser" -> startActivity(Intent(this, TvPanelActivity::class.java))
                    "manual" -> startActivity(Intent(this, ConsoleSetupActivity::class.java))
                    "home" -> finish()
                    else -> return result.notImplemented()
                }
                result.success(null)
            }
            "getSetup" -> result.success(setup(store))
            "getSettings" -> result.success(store.settings.also { UiText.use(it.uiLanguage) }.toJson().toString())
            // Çeviri dilleri ve hangi motorun hangisine çevirebildiği (tek tanım: core/Languages.kt).
            "getLanguages" -> result.success(Languages.toMaps())
            "setSettings" -> {
                val json = runCatching { JSONObject(call.arguments as String) }.getOrNull()
                    ?: return result.error("bad_settings", "Ayarlar okunamadı", null)
                val before = UiText.language
                store.settings = Settings.fromJson(json)
                UiText.use(store.settings.uiLanguage)
                if (UiText.language != before) TranslatorService.refreshNotification()
                Engine.applySettings(this)
                result.success(null)
            }
            "setAccountId" -> {
                val bytes = Store.decode(call.arguments as String)
                if (bytes == null || bytes.size != 8) return result.error("bad_account", "Hesap kimliği 8 bayt olmalı", null)
                store.accountId = bytes
                result.success(null)
            }
            "forgetAccount" -> {
                store.accountId = null
                store.console = null
                result.success(null)
            }
            "setIntroSeen" -> {
                store.introSeen = call.arguments as? Boolean ?: true
                result.success(null)
            }
            "noticeSeen" -> result.success(store.noticeSeen(call.arguments as? String ?: ""))
            "setNoticeSeen" -> {
                store.setNoticeSeen(call.arguments as? String ?: "")
                result.success(null)
            }
            "forgetConsole" -> {
                store.console = null
                result.success(null)
            }
            "discover" -> {
                val extra = (call.argument<List<String>>("hosts") ?: emptyList())
                val timeout = call.argument<Int>("timeoutMs") ?: 3000
                background.execute {
                    val found = Engine.discover(extra, timeout)
                    runOnUiThread { result.success(found) }
                }
            }
            "register" -> {
                val host = ConsolePairing.localHost(call.argument<String>("host").orEmpty())
                val pin = call.argument<Int>("pin") ?: -1
                if (host == null || pin !in 0..99_999_999) return result.error("bad_arguments", "Adres ve kod gerekli", null)
                background.execute {
                    val outcome = Engine.register(this, host.trim(), pin)
                    runOnUiThread { result.success(outcome) }
                }
            }
            "start" -> {
                if (ConsoleSession.blocked()) return result.error(
                    "other_session", "Önce açık olan Portal veya telefon oturumunu durdur.", null,
                )
                requestNotificationPermission()
                try {
                    TranslatorService.start(this)
                    result.success(null)
                } catch (error: Exception) {
                    // Sistem hizmeti başlatmayı reddetti (ör. arka plan kısıtlaması): sessiz kalmasın.
                    val text = "${error.javaClass.simpleName}: ${error.message}"
                    Engine.note(0, "çeviri hizmeti başlatılamadı: $text")
                    result.error("start_failed", text, null)
                }
            }
            "stop" -> {
                TranslatorService.stop(this)
                result.success(null)
            }
            "snapshot" -> {
                val width = (call.arguments as? Int) ?: 960
                background.execute {
                    val jpeg = Engine.snapshot(width)
                    runOnUiThread { result.success(jpeg) }
                }
            }
            "testSubtitle" -> {
                background.execute { Engine.sendTestSubtitle() }
                result.success(null)
            }
            "logs" -> result.success(Engine.logLines())
            // --- Altyazı geçmişi (Windows'taki "Altyazı geçmişi" sekmesi) ---
            "getHistory" -> background.execute {
                val entries = runCatching { Engine.historyEntries(this) }.getOrElse { emptyList() }
                runOnUiThread { result.success(entries) }
            }
            "clearHistory" -> background.execute {
                runCatching { Engine.clearHistory(this) }
                runOnUiThread { result.success(null) }
            }
            // --- İkinci ekran: "Bu telefonda göster" ve "Bilgisayarın altyazısını göster" ---
            "keepScreenOn" -> {
                if (call.arguments as? Boolean == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                result.success(null)
            }
            "getScreenPrefs" -> result.success(mapOf("scale" to store.screenScale.toDouble(), "previous" to store.screenPrevious,
                "displayServer" to store.displayServer))
            "setScreenPrefs" -> {
                call.argument<Double>("scale")?.let { store.screenScale = it.toFloat() }
                call.argument<Boolean>("previous")?.let { store.screenPrevious = it }
                result.success(null)
            }
            "findDisplayServer" -> background.execute {
                val found = runCatching { findDisplayServer(store) }.getOrElse { mapOf("found" to false, "wifi" to true) }
                runOnUiThread { result.success(found) }
            }
            "probeDisplayServer" -> {
                val address = ServerAddress.parse(call.arguments as? String ?: "")
                    ?: return result.success(mapOf("valid" to false, "found" to false))
                background.execute {
                    val active = OtherServers.hello(address.host, address.port, 2000)
                    runOnUiThread {
                        result.success(mapOf("valid" to true, "found" to (active != null), "host" to address.host, "port" to address.port,
                            "active" to (active == true)))
                    }
                }
            }
            "openDisplayRelay" -> {
                val address = ServerAddress.parse("${call.argument<String>("host")}:${call.argument<Int>("port")}")
                    ?: return result.error("bad_arguments", "Adres geçersiz", null)
                store.displayServer = address.toString()
                background.execute {
                    val local = runCatching { openDisplayRelay(address.host, address.port) }.getOrNull()
                    runOnUiThread { if (local != null) result.success(local) else result.error("relay", "Köprü açılamadı", null) }
                }
            }
            "closeDisplayRelay" -> {
                closeDisplayRelay()
                result.success(null)
            }
            // --- Konsol türü ve Xbox (Beta) ---
            "setConsoleKind" -> {
                store.consoleKind = call.arguments as? String ?: Store.KIND_PS5
                result.success(null)
            }
            "xboxStartSignIn" -> background.execute {
                val outcome = XboxAccount.startSignIn(this)
                runOnUiThread { result.success(outcome) }
            }
            "xboxAwaitSignIn" -> background.execute {
                val outcome = XboxAccount.awaitSignIn(this)
                runOnUiThread { result.success(outcome) }
            }
            "xboxCancelSignIn" -> {
                XboxAccount.cancelSignIn()
                result.success(null)
            }
            "xboxConsoles" -> background.execute {
                val outcome = XboxAccount.consoles(this)
                runOnUiThread { result.success(outcome) }
            }
            "xboxSelectConsole" -> {
                val id = call.argument<String>("id") ?: ""
                if (id.isBlank()) return result.error("bad_arguments", "Xbox kimliği gerekli", null)
                XboxAccount.selectConsole(this, XboxConsole(id, call.argument<String>("name") ?: "", call.argument<String>("model") ?: "",
                    call.argument<Boolean>("streamingEnabled") ?: true, call.argument<String>("powerState") ?: ""))
                result.success(null)
            }
            "xboxForgetConsole" -> {
                store.xboxConsole = null
                result.success(null)
            }
            "xboxSignOut" -> background.execute {
                // Keystore işlemi ana iş parçacığını bekletmesin.
                XboxAccount.signOut(this)
                runOnUiThread { result.success(null) }
            }
            else -> result.notImplemented()
        }
    }

    private fun setup(store: Store): Map<String, Any?> {
        val console = store.console
        return mapOf(
            "hasAccount" to (store.accountId != null),
            "accountId" to store.accountId?.let { Store.encode(it) },
            "consoleHost" to console?.host,
            "consoleName" to console?.name,
            "introSeen" to store.introSeen,
            "consoleKind" to store.consoleKind,
            "xboxSignedIn" to xboxSignedIn(store),
            "xboxConsoleName" to store.xboxConsole?.let { it.name.ifEmpty { it.model.ifEmpty { "Xbox" } } },
            "version" to runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull(),
        )
    }

    /**
     * Microsoft girişi var mı? Jeton Keystore'dan yalnız Xbox seçiliyken (ya da daha önce okunduysa) okunur;
     * PlayStation kullananlarda Keystore'a hiç dokunulmaz.
     */
    private fun xboxSignedIn(store: Store): Boolean =
        store.consoleKind == Store.KIND_XBOX && runCatching { XboxAccount.auth(this).signedIn }.getOrDefault(false)

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

}
