// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.gametranslator.gametranslator_mobile.Engine
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executor

internal const val MISSION_LABEL_EXTRA = "missionLabel"
internal const val PICK_MISSION_MEDIA_EXTRA = "pickMissionMedia"

internal class MissionJournalPanel(
    private val activity: Activity,
    private val mission: GtaMission,
    savedState: Bundle?,
    private val worker: Executor,
) {
    private val app = activity.applicationContext
    private var host: LinearLayout? = null
    private var page = savedState?.getInt("journalPage") ?: 0
    private var fileScene = savedState?.getString("journalFileScene")
    private var fileIsTurkish = savedState?.getBoolean("journalFileTurkish") ?: false
    private var bundledDialogueCount = 0
    private var revision = 0
    private var busyTranslation = false
    private var translator: GameTranslatorEngine? = null

    fun attach(container: LinearLayout, dialogueCount: Int) {
        host = container
        bundledDialogueCount = dialogueCount
        refresh()
    }

    fun detach() {
        host = null
        revision++
    }

    fun refresh() {
        val destination = host ?: return
        val request = ++revision
        worker.execute {
            val result = runCatching { MissionJournal.page(app, mission.id, page * PAGE_SIZE, PAGE_SIZE) }
            activity.runOnUiThread {
                if (!isAvailable() || request != revision || host != destination) return@runOnUiThread
                result.onSuccess { data ->
                    if (page > 0 && data.lines.isEmpty()) {
                        page--
                        refresh()
                    } else render(destination, data)
                }.onFailure {
                    destination.removeAllViews()
                    destination.addBelow(with(activity) {
                        miamiSection("Konuşma kayıtları", "Kayıtlar okunamadı.", Miami.coral).apply {
                            addBelow(press("Yeniden dene") { refresh() }, 8)
                        }
                    })
                }
            }
        }
    }

    private fun render(destination: LinearLayout, data: JournalPage) = with(activity) {
        destination.removeAllViews()
        val active = MissionJournal.recording?.takeIf { it.missionId == mission.id }
        val chosenScene = scene(active?.sceneId)
        val status = when {
            active != null -> "Kayıt açık · ${chosenScene?.title ?: "Sahne belirtilmedi"}"
            data.total > 0 -> "${data.total} kişisel konuşma kaydı"
            bundledDialogueCount > 0 -> "Bu göreve kişisel konuşma kaydı ekleyebilirsin."
            else -> "Bu görev için konuşma kaydı yok. Canlı altyazıları kaydedebilir veya klibinden ekleyebilirsin."
        }
        val tools = miamiSection("Konuşma kayıtlarım", status, Miami.aqua)
        if (active == null) tools.addBelow(press("Canlı kayıt başlat", Miami.aqua) { selectCaptureScene() }, 10)
        else {
            tools.addBelow(actionRow(
                press("Kaydı durdur", Miami.coral) { MissionJournal.stop(mission.id); refresh() },
                press("Sahneyi değiştir") { selectCaptureScene() },
            ), 10)
            tools.addBelow(press("Kayıtları yenile") { refresh() }, 6)
        }
        tools.addBelow(press("Klipten veya görüntüden ekle") {
            chooseMissionScene(mission, "Kayıt hangi sahneye ait?") { scene ->
                startActivity(Intent(this, FocusActivity::class.java).apply {
                    putExtra(MISSION_EXTRA, mission.id)
                    putExtra(MISSION_LABEL_EXTRA, mission.englishTitle ?: mission.title)
                    putExtra(MISSION_SCENE_EXTRA, scene)
                    putExtra(PICK_MISSION_MEDIA_EXTRA, true)
                })
            }
        }, 7)
        tools.addBelow(actionRow(
            press("SRT / VTT ekle") { chooseSubtitleLanguage() },
            press("Altyazı geçmişinden seç") { chooseHistory() },
        ), 6)
        destination.addBelow(tools)
        if (data.lines.isEmpty()) return@with
        val start = page * PAGE_SIZE
        destination.addBelow(copy("${start + 1}–${start + data.lines.size} / ${data.total} kayıt",
            13, Miami.lavender, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }, 12)
        if (data.total > PAGE_SIZE) destination.addBelow(actionRow(
            press("Önceki kayıtlar") { page--; refresh() }.apply { isEnabled = page > 0 },
            press("Sonraki kayıtlar") { page++; refresh() }.apply { isEnabled = start + data.lines.size < data.total },
        ), 6)
        val missing = data.lines.any { it.line.turkish == null && it.line.english != null }
        if (missing) destination.addBelow(press(if (busyTranslation) "Çevriliyor…" else "Bu sayfayı Türkçeye çevir", Miami.aqua) {
            translate(data.lines)
        }.apply { isEnabled = !busyTranslation }, 8)
        data.lines.forEach { saved ->
            val line = saved.line
            val lineScene = scene(line.sceneId)
            val headline = listOfNotNull(line.speaker, lineScene?.title).joinToString(" · ")
                .ifEmpty { "Sahne belirtilmedi" }
            val timing = line.startMs?.let { missionTimeRange(it, line.endMs) }
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(line.capturedAt))
            destination.addBelow(miamiSection(headline, line.turkish ?: line.english.orEmpty(), Miami.aqua).apply {
                val original = line.english?.takeIf { line.turkish != null && it != line.turkish }
                if (original != null) addBelow(copy(original, 14).apply { setTextIsSelectable(true) }, 5)
                if (line.turkish == null) addBelow(copy("İngilizce kayıt", 12, Miami.coral), 4)
                lineScene?.environment?.let { addBelow(copy(it, 13, Miami.coral), 4) }
                lineScene?.context?.let { addBelow(copy(it, 13), 4) }
                addBelow(copy(listOfNotNull(line.source.label, timing, date).joinToString(" · "), 12), 5)
                addBelow(press("Kayıt ayrıntıları") { editLine(saved) }, 8)
            }, 8)
        }
    }

    private fun selectCaptureScene() = with(activity) {
        chooseMissionScene(mission, "Canlı kaydı hangi sahneye bağlayalım?") { scene ->
            MissionJournal.start(mission.id, scene)
            refresh()
        }
    }

    private fun chooseSubtitleLanguage() = with(activity) {
        AlertDialog.Builder(this).setTitle("Altyazı dosyasının dili").setItems(arrayOf("Türkçe", "İngilizce")) { _, index ->
            chooseMissionScene(mission, "Altyazılar hangi sahneye ait?") { scene ->
                fileScene = scene
                fileIsTurkish = index == 0
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, PICK_SUBTITLE)
            }
        }.setNegativeButton("Vazgeç", null).show()
    }

    fun handleResult(code: Int, data: Intent): Boolean {
        if (code != PICK_SUBTITLE) return false
        val uri = data.data ?: return true
        val scene = fileScene
        val isTurkish = fileIsTurkish
        saveLines {
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Altyazı dosyası açılamadı.")
            val bytes = input.use { readLimited(it, MissionSubtitleFile.MAX_BYTES) }
            MissionSubtitleFile.parse(bytes.toString(Charsets.UTF_8)).map { cue ->
                JournalLine(cue.text.takeUnless { isTurkish }, cue.text.takeIf { isTurkish },
                    MissionLineSource.SUBTITLE_FILE, scene, cue.speaker, startMs = cue.startMs, endMs = cue.endMs)
            }
        }
        return true
    }

    private fun chooseHistory() {
        worker.execute {
            val entries = runCatching { Engine.historyEntries(app) }.getOrDefault(emptyList())
            activity.runOnUiThread {
                if (!isAvailable()) return@runOnUiThread
                if (entries.isEmpty()) {
                    toast("Konsol altyazı geçmişi boş.")
                    return@runOnUiThread
                }
                val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                val labels = entries.map {
                    val stamp = format.format(Date(it["date"] as? Long ?: 0))
                    "$stamp\n${it["translation"]}"
                }
                val selected = BooleanArray(entries.size)
                AlertDialog.Builder(activity).setTitle("Göreve ait altyazıları seç")
                    .setMultiChoiceItems(labels.toTypedArray(), selected) { _, index, isChecked -> selected[index] = isChecked }
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Göreve ekle") { _, _ ->
                        val chosen = entries.filterIndexed { index, _ -> selected[index] }.asReversed()
                        if (chosen.isEmpty()) {
                            toast("Önce en az bir altyazı seç.")
                        } else activity.chooseMissionScene(mission, "Altyazılar hangi sahneye ait?") { scene ->
                            saveLines {
                                chosen.mapNotNull { entry ->
                                    val text = (entry["translation"] as? String)?.takeIf(String::isNotBlank)
                                        ?: return@mapNotNull null
                                    JournalLine(entry["source"] as? String, text, MissionLineSource.HISTORY, scene,
                                        capturedAt = entry["date"] as? Long ?: 0)
                                }
                            }
                        }
                    }.show()
            }
        }
    }

    private fun saveLines(read: () -> List<JournalLine>) {
        worker.execute {
            val saved = runCatching { MissionJournal.add(app, mission.id, read()) }
            activity.runOnUiThread {
                if (!isAvailable()) return@runOnUiThread
                toast(saved.fold({ if (it > 0) "$it konuşma kaydı eklendi." else "Bu kayıtlar zaten eklenmiş." },
                    { it.message ?: "Konuşmalar kaydedilemedi." }))
                refresh()
            }
        }
    }

    private fun editLine(saved: SavedJournalLine) = with(activity) {
        val actions = arrayOf("Konuşmacıyı düzenle", "Sahne seç", "Kaydı kaldır")
        AlertDialog.Builder(this).setTitle("Kayıt ayrıntıları").setItems(actions) { _, index ->
            when (index) {
                0 -> {
                    val input = EditText(this).apply {
                        hint = "Konuşmacı adı"
                        filters = arrayOf(InputFilter.LengthFilter(80))
                        setSingleLine()
                        setText(saved.line.speaker)
                    }
                    AlertDialog.Builder(this).setTitle("Konuşmacı").setView(input)
                        .setNegativeButton("Vazgeç", null).setPositiveButton("Kaydet") { _, _ ->
                            update { MissionJournal.annotate(app, mission.id, saved.id, saved.line.sceneId,
                                input.text.toString().trim().ifBlank { null }) }
                        }.show()
                }
                1 -> chooseMissionScene(mission, "Konuşma hangi sahnede geçiyor?") { scene ->
                    update { MissionJournal.annotate(app, mission.id, saved.id, scene, saved.line.speaker) }
                }
                2 -> AlertDialog.Builder(this).setTitle("Bu konuşma kaydı kaldırılsın mı?")
                    .setNegativeButton("Vazgeç", null).setPositiveButton("Kaldır") { _, _ ->
                        update { MissionJournal.delete(app, mission.id, saved.id) }
                    }.show()
            }
        }.setNegativeButton("Kapat", null).show()
    }

    private fun update(task: () -> Unit) {
        worker.execute {
            val result = runCatching { task() }
            activity.runOnUiThread {
                if (!isAvailable()) return@runOnUiThread
                if (result.isFailure) toast("Kayıt güncellenemedi.")
                refresh()
            }
        }
    }

    private fun translate(lines: List<SavedJournalLine>) {
        if (busyTranslation) return
        val requests = lines.mapIndexedNotNull { index, row ->
            val english = row.line.english ?: return@mapIndexedNotNull null
            if (row.line.turkish != null) return@mapIndexedNotNull null
            val previous = lines.getOrNull(index - 1)?.takeIf {
                it.batch == row.batch && it.line.sceneId == row.line.sceneId
            }?.line?.english
            row.id to TranslationInput(english, previousSubtitle = previous)
        }
        if (requests.isEmpty()) return
        val engine = translator ?: GameTranslatorEngine(app, GameReadingProfile.GTA_6).also { translator = it }
        busyTranslation = true
        refresh()
        val translated = mutableListOf<Pair<Long, String>>()
        fun next(index: Int) {
            if (!isAvailable()) return
            val request = requests.getOrNull(index)
            if (request == null) {
                busyTranslation = false
                update {
                    translated.forEach { (id, result) -> MissionJournal.saveTranslation(app, mission.id, id, result) }
                }
                if (translated.size < requests.size) toast("${translated.size} / ${requests.size} satır çevrildi.")
                return
            }
            val (id, input) = request
            engine.translate(input) { reply ->
                reply.text?.takeUnless { it.equals(input.text, ignoreCase = true) }?.let { translated.add(id to it) }
                next(index + 1)
            }
        }
        next(0)
    }

    private fun scene(id: String?) = mission.scenes.firstOrNull { it.id == id }
    private fun isAvailable() = !activity.isFinishing && !activity.isDestroyed
    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    fun saveState(state: Bundle) {
        state.putInt("journalPage", page)
        state.putString("journalFileScene", fileScene)
        state.putBoolean("journalFileTurkish", fileIsTurkish)
    }

    fun close() {
        detach()
        translator?.close()
    }

    private companion object {
        const val PAGE_SIZE = 25
        const val PICK_SUBTITLE = 63
    }
}
