// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.Executors

class UpdatesActivity : Activity() {
    private lateinit var check: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val observe: (UpdateState) -> Unit = { update ->
        render(update)
        AppUpdater.continueInstall(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = contentPage("Sürüm ve güncellemeler")
        content.addBelow(copy("DraBornTranslate", 26, Palette.white, true))
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        content.addBelow(copy("Kurulu sürüm · $version", 15, Palette.blue), 5)
        check = press("Yeni sürüm kontrolü", Palette.blue) {
            if (AppUpdater.state is UpdateState.Ready) AppUpdater.continueInstall(this, requested = true)
            else AppUpdater.check(this)
        }
        content.addBelow(check, 18)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        content.addBelow(progress, 8)
        status = copy("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addBelow(status, 5)
        content.addBelow(press("Açık kaynak lisansı") {
            val notice = assets.open("NOTICE").bufferedReader().use { it.readText() }
            val license = assets.open("LICENSE").bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle("Açık kaynak lisansı")
                .setMessage("$notice\n\n$license").setPositiveButton("Kapat", null).show()
        }, 20)
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.observe(observe)
    }

    override fun onPause() {
        AppUpdater.remove(observe)
        super.onPause()
    }

    private fun render(state: UpdateState) {
        check.isEnabled = state == UpdateState.Idle || state == UpdateState.Current ||
            state is UpdateState.Message || state is UpdateState.Ready
        check.text = if (state is UpdateState.Ready) "Kurulum iznini aç" else "Yeni sürüm kontrolü"
        progress.visibility = if (state is UpdateState.Downloading) View.VISIBLE else View.GONE
        if (state is UpdateState.Downloading) progress.progress = state.percent
        status.text = when (state) {
            UpdateState.Idle -> "GitHub’daki imzalı Android sürümü kontrol edilir."
            UpdateState.Checking -> "GitHub’da yeni sürüm aranıyor…"
            UpdateState.Current -> "En güncel sürümü kullanıyorsun."
            is UpdateState.Downloading -> "v${state.version} indiriliyor · %${state.percent}"
            is UpdateState.Ready -> "v${state.version} kurulum için hazır."
            UpdateState.Installing, is UpdateState.Confirm -> "Android kurulum ekranı açılıyor…"
            is UpdateState.Message -> state.text
        }
    }
}

class GamePacksActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences("capture", MODE_PRIVATE) }
    private lateinit var download: Button
    private lateinit var imported: Button
    private lateinit var state: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = contentPage("Oyun paketleri", Palette.purple)
        content.addBelow(copy("GTA 6 Türkçe", 27, Palette.white, true))
        content.addBelow(copy("Eşleşen altyazılar indirdiğin paketten okunur."), 5)
        state = copy("", 14, Palette.mint, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addBelow(state, 10)
        download = press("Türkçe paketi indir", Palette.purple) {
            install("gtaPackTitle") { app -> ImportedGamePack.downloadGta(app) ?: throw IOException("Paket indirilemedi.") }
        }
        content.addBelow(download, 15)
        imported = press("JSON veya JSON.GZ seç") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 28)
        }
        content.addBelow(imported, 10)
        content.addBelow(press("Yüklü paketleri yönet") { manage() }, 10)
        render()
    }

    private fun render(message: String? = null) {
        val hasGta = ImportedGamePack.hasGta(this)
        val title = preferences.getString("gtaPackTitle", null)
        state.text = message ?: when {
            busy -> "Paket hazırlanıyor…"
            hasGta -> title ?: "GTA 6 Türkçe paketi hazır."
            else -> "GTA 6 paketi henüz indirilmedi."
        }
        download.isEnabled = !busy
        imported.isEnabled = !busy
        download.text = if (hasGta) "Paket güncellemesini denetle" else "Türkçe paketi indir"
    }

    private fun install(key: String, operation: (Context) -> String) {
        busy = true
        render()
        val app = applicationContext
        worker.execute {
            val result = runCatching { operation(app) }
            result.getOrNull()?.let { preferences.edit().putString(key, it).apply() }
            runOnUiThread {
                if (!isDestroyed) {
                    busy = false
                    render(result.getOrElse { it.message ?: "Paket açılamadı." })
                }
            }
        }
    }

    private fun manage() {
        if (busy) return
        val ids = buildList {
            if (ImportedGamePack.hasGta(this@GamePacksActivity)) add("GTA 6 Türkçe" to true)
            if (ImportedGamePack.hasPack(this@GamePacksActivity)) {
                add((preferences.getString("packTitle", null) ?: "İçe aktarılan paket") to false)
            }
        }
        if (ids.isEmpty()) {
            render("Yüklü oyun paketi yok.")
            return
        }
        AlertDialog.Builder(this).setTitle("Kaldırılacak paketi seç")
            .setItems(ids.map { it.first }.toTypedArray()) { _, index ->
                val (label, gta) = ids[index]
                AlertDialog.Builder(this).setTitle("$label kaldırılsın mı?")
                    .setNegativeButton("Vazgeç", null).setPositiveButton("Kaldır") { _, _ ->
                        ImportedGamePack.remove(this, gta = gta)
                        preferences.edit().remove(if (gta) "gtaPackTitle" else "packTitle").apply()
                        render()
                    }.show()
            }.setNegativeButton("Kapat", null).show()
    }

    @Deprecated("Android activity result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 28 && result == RESULT_OK) data?.data?.let { uri ->
            install("packTitle") { app -> ImportedGamePack.install(app, uri) }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
