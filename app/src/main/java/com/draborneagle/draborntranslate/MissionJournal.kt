// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

internal enum class MissionLineSource(val label: String) {
    CONSOLE("Canlı konsol"), REMOTE_PLAY("Canlı Remote Play"), PS_APP("Canlı PS App"),
    HISTORY("Konsol altyazı geçmişi"), CLIP("Oyun klibi"), SCREENSHOT("Ekran görüntüsü"),
    SUBTITLE_FILE("Altyazı dosyası"),
}

internal data class JournalLine(
    val english: String?,
    val turkish: String?,
    val source: MissionLineSource,
    val sceneId: String?,
    val speaker: String? = null,
    val capturedAt: Long = System.currentTimeMillis(),
    val startMs: Long? = null,
    val endMs: Long? = null,
)

internal data class SavedJournalLine(val id: Long, val batch: String, val line: JournalLine)
internal data class JournalPage(val total: Long, val lines: List<SavedJournalLine>)

internal object MissionJournal {
    data class Recording(val missionId: String, val sceneId: String?, val batch: String, val startedAt: Long)

    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var database: Store? = null
    @Volatile var recording: Recording? = null
        private set

    @Synchronized
    fun start(missionId: String, sceneId: String?) {
        val current = recording
        recording = if (current?.missionId == missionId) current.copy(sceneId = sceneId)
        else Recording(missionId, sceneId, UUID.randomUUID().toString(), SystemClock.elapsedRealtime())
    }

    @Synchronized
    fun stop(missionId: String) {
        if (recording?.missionId == missionId) recording = null
    }

    fun observe(context: Context, original: String, turkish: String, source: MissionLineSource) {
        val active = recording ?: return
        if (original.isBlank() || turkish.isBlank()) return
        val app = context.applicationContext
        val line = JournalLine(original, turkish, source, active.sceneId,
            startMs = (SystemClock.elapsedRealtime() - active.startedAt).coerceAtLeast(0))
        writer.execute {
            try {
                store(app).appendActive(active, line)
            } catch (error: RuntimeException) {
                Log.w("MissionJournal", "Konuşma kaydedilemedi.", error)
            }
        }
    }

    fun page(context: Context, missionId: String, offset: Int, size: Int): JournalPage =
        store(context).page(missionId, offset, size)

    fun add(context: Context, missionId: String, lines: List<JournalLine>): Int =
        store(context).appendImport(missionId, lines)

    fun saveTranslation(context: Context, missionId: String, id: Long, text: String) {
        store(context).writableDatabase.update("lines", ContentValues().apply { put("turkish", text.take(MAX_TEXT)) },
            "mission = ? AND id = ?", arrayOf(missionId, id.toString()))
    }

    fun annotate(context: Context, missionId: String, id: Long, sceneId: String?, speaker: String?) {
        store(context).writableDatabase.update("lines", ContentValues().apply {
            put("scene", sceneId)
            put("speaker", speaker)
        }, "mission = ? AND id = ?", arrayOf(missionId, id.toString()))
    }

    fun delete(context: Context, missionId: String, id: Long) {
        store(context).writableDatabase.delete("lines", "mission = ? AND id = ?", arrayOf(missionId, id.toString()))
    }

    private fun store(context: Context): Store = database ?: synchronized(this) {
        database ?: Store(context.applicationContext).also { database = it }
    }

    private class Store(context: Context) : SQLiteOpenHelper(context, "gta-mission-journal.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE lines (
                    id INTEGER PRIMARY KEY,
                    mission TEXT NOT NULL,
                    batch TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    scene TEXT,
                    speaker TEXT,
                    recorded_at INTEGER NOT NULL,
                    start_ms INTEGER,
                    end_ms INTEGER,
                    english TEXT,
                    turkish TEXT
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX mission_lines ON lines (mission, id)")
            db.execSQL("CREATE UNIQUE INDEX journal_batch ON lines (mission, batch, position)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

        fun appendActive(recording: Recording, line: JournalLine) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.query("lines", arrayOf("position", "start_ms", "scene", "english", "turkish", "source"),
                    "mission = ? AND batch = ?", arrayOf(recording.missionId, recording.batch), null, null,
                    "position DESC", "1").use { previous ->
                    val hasPrevious = previous.moveToFirst()
                    val duplicate = hasPrevious && line.startMs != null &&
                        line.startMs - previous.getLong(1) in 0L..8_000L &&
                        line.sceneId == previous.getString(2) && line.english == previous.getString(3) &&
                        line.turkish == previous.getString(4) && line.source.name == previous.getString(5)
                    if (!duplicate) {
                        val position = if (hasPrevious) previous.getLong(0) + 1 else 0
                        db.insertOrThrow("lines", null, values(recording.missionId, recording.batch, position, line))
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        fun appendImport(mission: String, lines: List<JournalLine>): Int {
            if (lines.isEmpty()) return 0
            val digest = MessageDigest.getInstance("SHA-256")
            for (line in lines) {
                val timestamp = line.capturedAt.takeIf { line.source == MissionLineSource.HISTORY }
                val fields = listOf(line.source.name, line.sceneId, line.english, line.turkish, line.speaker,
                    line.startMs, line.endMs, timestamp)
                for (field in fields) {
                    val text = field?.toString().orEmpty()
                    digest.update("${text.length}:$text".toByteArray(Charsets.UTF_8))
                }
            }
            val batch = digest.digest().joinToString("") { "%02x".format(it) }
            val db = writableDatabase
            var added = 0
            db.beginTransaction()
            try {
                lines.forEachIndexed { position, line ->
                    val id = db.insertWithOnConflict("lines", null, values(mission, batch, position.toLong(), line),
                        SQLiteDatabase.CONFLICT_IGNORE)
                    if (id != -1L) added++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return added
        }

        fun page(mission: String, offset: Int, size: Int): JournalPage {
            val db = readableDatabase
            db.beginTransactionNonExclusive()
            try {
                val args = arrayOf(mission)
                val count = DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM lines WHERE mission = ?", args)
                val rows = db.query("lines", arrayOf("id", "batch", "english", "turkish", "source", "scene", "speaker",
                    "recorded_at", "start_ms", "end_ms"), "mission = ?", args, null, null,
                    "id ASC", "$offset, $size").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(SavedJournalLine(cursor.getLong(0), cursor.getString(1),
                                JournalLine(cursor.getString(2), cursor.getString(3),
                                    MissionLineSource.valueOf(cursor.getString(4)), cursor.getString(5),
                                    cursor.getString(6), cursor.getLong(7),
                                    cursor.getLong(8).takeUnless { cursor.isNull(8) },
                                    cursor.getLong(9).takeUnless { cursor.isNull(9) })))
                        }
                    }
                }
                db.setTransactionSuccessful()
                return JournalPage(count, rows)
            } finally {
                db.endTransaction()
            }
        }

        private fun values(mission: String, batch: String, index: Long, line: JournalLine) = ContentValues().apply {
            put("mission", mission)
            put("batch", batch)
            put("position", index)
            put("source", line.source.name)
            put("scene", line.sceneId)
            put("speaker", line.speaker)
            put("recorded_at", line.capturedAt)
            put("start_ms", line.startMs)
            put("end_ms", line.endMs)
            put("english", line.english?.take(MAX_TEXT))
            put("turkish", line.turkish?.take(MAX_TEXT))
        }
    }

    private const val MAX_TEXT = 4_000
}
