// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import com.gametranslator.gametranslator_mobile.core.ColoredWords
import com.gametranslator.gametranslator_mobile.core.PackKey
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

internal object GtaMissionRecording {
    const val EXTRA_MISSION_ID = "mission_subtitle_recording_id"
    const val EXTRA_MISSION_TITLE = "mission_subtitle_recording_title"
    private const val MAX_TEXT = 1_000
    private const val REPEAT_MS = 1_000L
    private val missionIdFormat = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dra-mission-recording").apply { isDaemon = true }
    }
    private var db: Database? = null

    fun start(context: Context, missionId: String?): Recording? =
        missionId?.takeIf { it.length <= 80 && missionIdFormat.matches(it) }
            ?.let { Recording(context.applicationContext, it) }

    // Readers use the same queue as writes so an activity reopening after a capture sees the saved cues.
    fun dialogue(context: Context, missionId: String): GtaMissionDialogue? =
        worker.submit<GtaMissionDialogue?> {
            val scenes = linkedMapOf<String, GtaMissionScene>()
            val lines = mutableListOf<GtaMissionText>()
            database(context).readableDatabase.rawQuery(
                """SELECT section, objective, speaker, text, elapsed_ms, started_at, source_text FROM mission_cues
                    WHERE mission = ? AND recording = (
                        SELECT recording FROM mission_cues WHERE mission = ? ORDER BY id DESC LIMIT 1
                    ) ORDER BY elapsed_ms, id""",
                arrayOf(missionId, missionId),
            ).use { rows ->
                while (rows.moveToNext()) {
                    val section = "recorded-${rows.getInt(0)}"
                    scenes.getOrPut(section) {
                        val objective = rows.getString(1)
                        val date = if (scenes.isEmpty()) DateFormat
                            .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.forLanguageTag("tr-TR"))
                            .format(Date(rows.getLong(5))) else null
                        GtaMissionScene(section, objective ?: "Oynanış kaydı", date?.let { "Oyun oturumu · $it" }, null)
                    }
                    lines.add(GtaMissionText(GtaTextType.DIALOGUE, rows.getString(3)?.takeIf(String::isNotBlank), rows.getString(6),
                        section, rows.getString(2), null, rows.getLong(4), null))
                }
            }
            if (lines.isEmpty()) null else GtaMissionDialogue(scenes.values.toList(), lines, recorded = true)
        }.get()

    private fun database(context: Context): Database =
        db ?: Database(context.applicationContext).also { db = it }

    internal class Recording(private val app: Context, private val missionId: String) {
        private val id = UUID.randomUUID().toString()
        private val startedAt = System.currentTimeMillis()
        private val startedTick = SystemClock.elapsedRealtime()
        private var section = 0
        private var objectiveText: String? = null
        private var objectiveKey = ""
        private var objectiveCandidate = ""
        private var previous: Cue? = null
        private var closed = false

        private data class Cue(val section: Int, val elapsed: Long, val source: String)

        @Synchronized
        fun observeObjectives(blocks: List<HudBlock>, requireConfirmation: Boolean = false) {
            if (closed) return
            val objective = objectiveText(blocks.firstOrNull { it.kind == HudKind.OBJECTIVE })
            val key = PackKey.canon(objective.orEmpty())
            val repeated = key == objectiveCandidate
            objectiveCandidate = key
            if (requireConfirmation && !repeated) return
            if (key.isBlank() || key == objectiveKey) return
            if (previous?.section == section) section++
            objectiveText = objective
            objectiveKey = key
        }

        @Synchronized
        fun translateObjective(source: HudBlock, translated: HudBlock) {
            if (closed) return
            val original = objectiveText(source) ?: return
            val title = objectiveText(translated) ?: return
            val isCurrent = PackKey.canon(original) == objectiveKey
            val stored = if (isCurrent) objectiveText ?: original else original
            if (stored == title) return
            if (isCurrent) objectiveText = title
            val values = ContentValues().apply { put("objective", title) }
            write {
                update("mission_cues", values, "mission = ? AND recording = ? AND objective = ?", arrayOf(missionId, id, stored))
            }
        }

        @Synchronized
        fun add(source: String, translation: String? = null, observedAt: Long = SystemClock.elapsedRealtime()) {
            if (closed) return
            val sourceKey = PackKey.canon(source).take(MAX_TEXT)
            if (sourceKey.isEmpty()) return
            val elapsed = (observedAt - startedTick).coerceAtLeast(0)
            val last = previous
            if (last != null && last.section == section && last.source == sourceKey &&
                elapsed - last.elapsed in 0L..REPEAT_MS) return
            val speaker = singleSpeaker(source)
            val localizedName = speaker?.let { Gta6Profile.localizeSpeaker("$it:").removeSuffix(":") }
            val original = captionText(source, speaker, localizedName)
            if (original.isBlank()) return
            previous = Cue(section, elapsed, sourceKey)
            val values = ContentValues().apply {
                put("mission", missionId)
                put("recording", id)
                put("started_at", startedAt)
                put("elapsed_ms", elapsed)
                put("section", section)
                put("objective", objectiveText)
                put("speaker", localizedName)
                put("text", translation?.let { captionText(it, speaker, localizedName) }.orEmpty())
                put("source_text", original)
            }
            write { insertOrThrow("mission_cues", null, values) }
        }

        @Synchronized
        fun translate(source: String, translation: String, observedAt: Long) {
            if (closed) return
            val speaker = singleSpeaker(source)
            val localizedName = speaker?.let { Gta6Profile.localizeSpeaker("$it:").removeSuffix(":") }
            val original = captionText(source, speaker, localizedName)
            val text = captionText(translation, speaker, localizedName)
            if (original.isBlank() || text.isBlank()) return
            val elapsed = (observedAt - startedTick).coerceAtLeast(0)
            val values = ContentValues().apply { put("text", text) }
            write {
                update("mission_cues", values, "mission = ? AND recording = ? AND elapsed_ms = ? AND source_text = ?",
                    arrayOf(missionId, id, elapsed.toString(), original))
            }
        }

        private fun singleSpeaker(text: String): String? = Gta6Profile.speakerName(text)?.takeUnless {
            text.lineSequence().drop(1).any { line -> Gta6Profile.speakerName(line) != null }
        }

        private fun objectiveText(block: HudBlock?): String? = block?.lines
            ?.joinToString(" ") { ColoredWords.plain(it.text) }?.trim()?.take(180)?.takeIf(String::isNotEmpty)

        private fun captionText(value: String, speaker: String?, localized: String?): String {
            val text = ColoredWords.plain(value).trim().take(MAX_TEXT)
            val label = Gta6Profile.speakerName(text)
            return if (label != null && (label.equals(speaker, true) || label.equals(localized, true))) {
                text.substringAfter(':').trim().ifBlank { text }
            } else text
        }

        private fun write(change: SQLiteDatabase.() -> Unit) {
            worker.execute {
                try {
                    database(app).writableDatabase.change()
                } catch (error: SQLiteException) {
                    Log.w("GtaMissionRecording", "Görev altyazısı kaydedilemedi · ${error.javaClass.simpleName}")
                }
            }
        }

        @Synchronized
        fun close() {
            closed = true
        }
    }

    private class Database(context: Context) : SQLiteOpenHelper(context, "gta-mission-captions.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE mission_cues (
                    id INTEGER PRIMARY KEY, mission TEXT NOT NULL, recording TEXT NOT NULL,
                    started_at INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, section INTEGER NOT NULL,
                    objective TEXT, speaker TEXT, text TEXT NOT NULL, source_text TEXT
                )""",
            )
            db.execSQL("CREATE INDEX mission_cues_latest ON mission_cues(mission, id DESC)")
            createOrderIndex(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE mission_cues ADD COLUMN source_text TEXT")
                db.execSQL("DROP INDEX IF EXISTS mission_cues_order")
                createOrderIndex(db)
            }
        }

        private fun createOrderIndex(db: SQLiteDatabase) {
            db.execSQL("CREATE INDEX mission_cues_order ON mission_cues(mission, recording, elapsed_ms, id)")
        }
    }
}
