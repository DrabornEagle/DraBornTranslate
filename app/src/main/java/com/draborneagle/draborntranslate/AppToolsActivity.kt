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
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        content.addBelow(featureHeader("Sürüm $version", "GitHub üzerinden imzalı Android güncellemeleri",
            R.drawable.ic_package_update, Palette.blue))
        check = press("Yeni sürüm kontrolü", Palette.blue) {
            if (AppUpdater.state is UpdateState.Ready) AppUpdater.continueInstall(this, requested = true)
            else AppUpdater.check(this)
        }
        content.addBelow(check, 18)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        content.addBelow(progress, 8)
        status = copy("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addBelow(status, 5)
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
    private lateinit var liveDownload: Button
    private lateinit var missionImport: Button
    private lateinit var imported: Button
    private lateinit var manage: Button
    private lateinit var state: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = contentPage("Oyun paketleri", Palette.purple)
        content.addBelow(featureHeader("GTA 6 Türkçe", "Görev paketini GitHub’dan kur ve çevrimdışı ara.",
            R.drawable.ic_game_package, Palette.purple))
        state = copy("", 14, Palette.mint, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addBelow(state.apply {
            background = filled(0xFF20263D.toInt(), 16, Palette.border)
            setPadding(dp(13), dp(11), dp(13), dp(11))
        }, 10)
        download = press("GTA 6 paketini indir", Palette.purple) {
            perform { app ->
                val status = missionStatus(GtaMissionStore.download(app))
                if (GtaMissionStore.hasLocal(app)) "$status · Aramada özel paket kullanılıyor." else status
            }
        }
        content.addBelow(download, 15)
        content.addBelow(press("Görev ara", Palette.mint) {
            startActivity(Intent(this, GtaMissionSearchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }, 8)
        missionImport = press("Görev paketi seç (.zip)") { chooseFile(MISSION_IMPORT, "application/zip") }
        content.addBelow(missionImport, 12)
        content.addBelow(copy("Canlı altyazı", 17, Palette.white, true).apply { isAccessibilityHeading = true }, 16)
        liveDownload = press("GTA 6 altyazı paketini indir") {
            perform { app ->
                val title = ImportedGamePack.downloadGta(app) ?: throw IOException("Altyazı paketi indirilemedi.")
                preferences.edit().putString("gtaPackTitle", title).apply()
                title
            }
        }
        content.addBelow(liveDownload, 8)
        imported = press("Altyazı paketi seç (.json veya .json.gz)") { chooseFile(SUBTITLE_IMPORT, "*/*") }
        content.addBelow(imported, 8)
        manage = press("Yüklü paketleri yönet") { managePacks() }
        content.addBelow(manage, 10)
        render()
    }

    @Suppress("DEPRECATION")
    private fun chooseFile(code: Int, mime: String) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = mime
            addCategory(Intent.CATEGORY_OPENABLE)
        }, code)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun missionStatus(catalog: GtaMissionCatalog): String {
        val count = catalog.missions.sumOf { it.textCount }
        return "Görev paketi hazır · ${catalog.missions.size} görev · $count metin"
    }

    private fun render(message: String? = null) {
        val downloaded = GtaMissionStore.hasDownload(this)
        state.text = message ?: when {
            busy -> "Paket hazırlanıyor…"
            GtaMissionStore.hasLocal(this) -> "Özel GTA 6 görev paketi kullanılıyor."
            downloaded -> "GitHub görev paketi çevrimdışı kullanıma hazır."
            else -> "GTA 6 görev paketi henüz indirilmedi."
        }
        listOf(download, liveDownload, missionImport, imported, manage).forEach { it.isEnabled = !busy }
        download.text = if (downloaded) "GitHub’daki görev paketini güncelle" else "GTA 6 paketini indir"
        liveDownload.text = if (ImportedGamePack.hasGta(this)) "Altyazı paketi güncellemesini denetle" else "GTA 6 altyazı paketini indir"
    }

    private fun perform(operation: (Context) -> String) {
        busy = true
        render()
        val app = applicationContext
        worker.execute {
            val result = runCatching { operation(app) }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    busy = false
                    render(result.getOrElse { error -> error.message ?: "Paket açılamadı." })
                }
            }
        }
    }

    private fun managePacks() {
        if (busy) return
        val choices = buildList<Pair<String, (Context) -> Unit>> {
            if (GtaMissionStore.hasDownload(this@GamePacksActivity)) {
                add("GitHub görev paketi" to { app -> GtaMissionStore.remove(app, local = false) })
            }
            if (GtaMissionStore.hasLocal(this@GamePacksActivity)) {
                add("Özel görev paketi" to { app -> GtaMissionStore.remove(app, local = true) })
            }
            if (ImportedGamePack.hasGta(this@GamePacksActivity)) add("GTA 6 altyazı paketi" to { app ->
                ImportedGamePack.remove(app, gta = true)
                preferences.edit().remove("gtaPackTitle").apply()
            })
            if (ImportedGamePack.hasPack(this@GamePacksActivity)) {
                add((preferences.getString("packTitle", null) ?: "İçe aktarılan altyazı paketi") to { app ->
                    ImportedGamePack.remove(app)
                    preferences.edit().remove("packTitle").apply()
                })
            }
        }
        if (choices.isEmpty()) {
            render("Yüklü oyun paketi yok.")
            return
        }
        AlertDialog.Builder(this).setTitle("Kaldırılacak paketi seç")
            .setItems(choices.map { it.first }.toTypedArray()) { _, index ->
                val (label, remove) = choices[index]
                AlertDialog.Builder(this).setTitle("$label kaldırılsın mı?")
                    .setNegativeButton("Vazgeç", null).setPositiveButton("Kaldır") { _, _ ->
                        perform { app ->
                            remove(app)
                            "$label kaldırıldı."
                        }
                    }.show()
            }.setNegativeButton("Kapat", null).show()
    }

    @Deprecated("Android activity result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (result != RESULT_OK) return
        val uri = data?.data ?: return
        when (code) {
            MISSION_IMPORT -> perform { app -> missionStatus(GtaMissionStore.installLocal(app, uri)) }
            SUBTITLE_IMPORT -> perform { app ->
                ImportedGamePack.install(app, uri).also { title -> preferences.edit().putString("packTitle", title).apply() }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val SUBTITLE_IMPORT = 28
        const val MISSION_IMPORT = 29
    }
}
