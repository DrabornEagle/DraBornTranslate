// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
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
    private lateinit var dialogueImport: Button
    private lateinit var dialogueConnect: Button
    private lateinit var imported: Button
    private lateinit var manage: Button
    private lateinit var state: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = miamiPage("Oyun Paketleri", Miami.coral)
        content.addBelow(miamiHero("GTA 6 Türkçe", "Çevrimdışı görev rehberi ve canlı altyazı paketleri.", Miami.coral))
        val missions = miamiPanel(Miami.coral)
        missions.addView(copy("Görev rehberi", 19, Palette.white, true).apply { isAccessibilityHeading = true })
        state = copy("", 14, Miami.coral).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        missions.addBelow(state, 5)
        download = press("GTA 6 paketini indir", Miami.coral) {
            perform { app ->
                val status = missionStatus(GtaMissionStore.download(app))
                if (GtaMissionStore.hasLocal(app)) "$status · Aramada özel paket kullanılıyor." else status
            }
        }
        missions.addBelow(download, 12)
        missions.addBelow(press("Görev ara") {
            startActivity(Intent(this, GtaMissionSearchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }, 8)
        missionImport = press("Görev paketi seç (.zip)") { chooseFile(MISSION_IMPORT, "application/zip") }
        missions.addBelow(missionImport, 8)
        dialogueImport = press("Replik paketi seç (.zip)") { chooseFile(DIALOGUE_IMPORT, "application/zip") }
        missions.addBelow(dialogueImport, 8)
        dialogueConnect = press("Replik paketi bağla") { showDialogueSource() }
        missions.addBelow(dialogueConnect, 8)
        content.addBelow(missions, 14)
        val subtitles = miamiPanel(Miami.aqua)
        subtitles.addView(copy("Türkçe altyazı", 19, Palette.white, true).apply { isAccessibilityHeading = true })
        liveDownload = press("GTA 6 altyazı paketini indir", Miami.aqua) {
            perform { app ->
                val title = ImportedGamePack.downloadGta(app) ?: throw IOException("Altyazı paketi indirilemedi.")
                preferences.edit().putString("gtaPackTitle", title).apply()
                title
            }
        }
        subtitles.addBelow(liveDownload, 10)
        imported = press("Altyazı paketi seç (.gz veya .json)") { chooseFile(SUBTITLE_IMPORT, "*/*") }
        subtitles.addBelow(imported, 8)
        content.addBelow(subtitles, 12)
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

    private fun showDialogueSource() {
        val current = GtaMissionStore.dialogueSource(this)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            hint = "GitHub manifest.json bağlantısı"
            setText(current.orEmpty())
        }
        val form = stack().apply {
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Görev replik paketi")
            .setMessage("Yayımlama izni olan paketin GitHub manifest bağlantısını gir.")
            .setView(form)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton(if (current == null) "Bağla ve indir" else "Kontrol et", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val source = githubDialogueManifest(input.text.toString())
            if (source == null) {
                input.error = "GitHub’daki manifest.json dosyasının HTTPS bağlantısını gir."
                return@setOnClickListener
            }
            dialog.dismiss()
            perform { app ->
                val coverage = GtaMissionStore.connectDialogue(app, source.toString())
                dialogueStatus(coverage)
            }
        }
    }

    private fun missionStatus(catalog: GtaMissionCatalog): String {
        return "GitHub paketi hazır · ${catalog.missions.size} görev"
    }

    private fun dialogueStatus(coverage: GtaDialoguePackCoverage) =
        "Replik paketi hazır · ${coverage.missions} görev · ${coverage.lines} replik kaydı"

    private fun render(message: String? = null) {
        val downloaded = GtaMissionStore.hasDownload(this)
        state.text = message ?: when {
            busy -> "Paket hazırlanıyor…"
            GtaMissionStore.hasLocal(this) -> "Özel GTA 6 görev paketi kullanılıyor."
            downloaded -> "GitHub görev paketi çevrimdışı kullanıma hazır."
            else -> "Uygulamaya dâhil görev rehberi çevrimdışı hazır."
        }
        listOf(download, liveDownload, missionImport, dialogueImport, dialogueConnect, imported, manage)
            .forEach { it.isEnabled = !busy }
        download.text = if (downloaded) "GitHub’daki görev paketini güncelle" else "GTA 6 paketini indir"
        dialogueConnect.text = if (GtaMissionStore.dialogueSource(this) == null) "Replik paketi bağla" else "Replik paketini güncelle"
        liveDownload.text = if (ImportedGamePack.hasGtaDownload(this)) {
            "Altyazı paketi güncellemesini denetle"
        } else "GTA 6 altyazı paketini indir"
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
            if (GtaMissionStore.dialogueSource(this@GamePacksActivity) != null) {
                add("GitHub replik paketi" to GtaMissionStore::removeDialogue)
            }
            if (GtaMissionStore.hasLocalDialogue(this@GamePacksActivity)) {
                add("Yerel görev replik paketi" to GtaMissionStore::removeLocalDialogue)
            }
            if (ImportedGamePack.hasLocalGta(this@GamePacksActivity)) {
                add("Yerel GTA 6 altyazı paketi" to ImportedGamePack::removeLocalGta)
            }
            if (ImportedGamePack.hasGtaDownload(this@GamePacksActivity)) add("İndirilen GTA 6 altyazı paketi" to { app ->
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
            DIALOGUE_IMPORT -> perform { app -> dialogueStatus(GtaMissionStore.installLocalDialogue(app, uri)) }
            SUBTITLE_IMPORT -> perform { app ->
                val installed = ImportedGamePack.install(app, uri)
                if (!installed.gta) preferences.edit().putString("packTitle", installed.title).apply()
                "${installed.title} · Paket hazır"
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
        const val DIALOGUE_IMPORT = 30
    }
}
