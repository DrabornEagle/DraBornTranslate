// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.roundToInt

internal data class SavedCapture(
    val uri: Uri,
    val name: String,
    val video: Boolean,
    val modified: Long,
    val size: Long,
) {
    val key: String get() = MessageDigest.getInstance("SHA-256")
        .digest("$uri:$modified:$size".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal data class CaptureFrame(val bitmap: Bitmap, val durationMs: Long = 0L, val timeMs: Long = 0L)

internal object CaptureInbox {
    private const val PERIODIC_JOB = 521
    private const val NEW_FILES_JOB = 522
    private const val MAX_RECENT = 48
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private fun prefs(context: Context) = context.getSharedPreferences("captures", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("auto", false)

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT == 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun canRead(context: Context): Boolean = permissions().any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto", value).apply()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!value) {
            observer?.let(context.contentResolver::unregisterContentObserver)
            observer = null
            scheduler.cancel(PERIODIC_JOB)
            scheduler.cancel(NEW_FILES_JOB)
            return
        }
        watch(context)
        val service = ComponentName(context, CaptureInboxJob::class.java)
        scheduler.schedule(JobInfo.Builder(PERIODIC_JOB, service).setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
        enqueue(context)
    }

    fun watch(context: Context) {
        if (observer != null || !enabled(context) || !canRead(context)) return
        val app = context.applicationContext
        val registered = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { enqueue(app) }
        }
        app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, registered)
        app.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, registered)
        observer = registered
    }

    fun enqueue(context: Context) {
        if (!enabled(context) || !canRead(context)) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(NEW_FILES_JOB) != null) return
        val service = ComponentName(context, CaptureInboxJob::class.java)
        scheduler.schedule(JobInfo.Builder(NEW_FILES_JOB, service).setMinimumLatency(1_500L).build())
    }

    fun items(context: Context): List<SavedCapture> = buildList {
        if (!canRead(context)) return@buildList
        addAll(query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/PS App/", false))
        addAll(query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/PS App/", true))
    }.sortedByDescending { it.modified }.take(MAX_RECENT)

    private fun query(context: Context, collection: Uri, folder: String, video: Boolean): List<SavedCapture> {
        val names = arrayOf(
            android.provider.BaseColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE,
        )
        val selection = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.SIZE} > 0")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(folder))
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, MAX_RECENT)
        }
        return runCatching {
            context.contentResolver.query(collection, names, selection, null)?.use { cursor ->
                buildList {
                    while (size < MAX_RECENT && cursor.moveToNext()) {
                        add(SavedCapture(
                            ContentUris.withAppendedId(collection, cursor.getLong(0)),
                            cursor.getString(1) ?: "PlayStation yakalaması", video, cursor.getLong(2), cursor.getLong(3),
                        ))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun load(context: Context, capture: SavedCapture, atMs: Long? = null): CaptureFrame {
        if (!capture.video) {
            val source = ImageDecoder.createSource(context.contentResolver, capture.uri)
            return CaptureFrame(ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (w, h) = dimensions(info.size.width, info.size.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(w, h)
            })
        }
        return MediaMetadataRetriever().use { video ->
            video.setDataSource(context, capture.uri)
            val duration = video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val time = (atMs ?: duration / 2).coerceIn(0L, (duration - 1).coerceAtLeast(0L))
            val width = video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val height = video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val (w, h) = dimensions(width, height)
            val bitmap = video.getScaledFrameAtTime(time * 1_000, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                ?: error("Videodan kare alınamadı.")
            CaptureFrame(bitmap, duration, time)
        }
    }

    private fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1f, 1920f / maxOf(width, height))
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    fun cached(context: Context, capture: SavedCapture): List<FocusText>? = runCatching {
        val array = JSONArray(file(context, capture).readFully().toString(Charsets.UTF_8))
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            FocusText(Rect(row.getInt("left"), row.getInt("top"), row.getInt("right"), row.getInt("bottom")),
                row.getString("en"), row.optString("tr").ifBlank { null }, row.getInt("background"))
        }
    }.getOrNull()

    @Synchronized
    fun save(context: Context, capture: SavedCapture, result: List<FocusText>) {
        val target = file(context, capture)
        target.baseFile.parentFile?.mkdirs()
        val payload = JSONArray(result.map { item ->
            JSONObject().put("left", item.box.left).put("top", item.box.top)
                .put("right", item.box.right).put("bottom", item.box.bottom)
                .put("en", item.source).put("tr", item.turkish.orEmpty()).put("background", item.background)
        }).toString()
        val stream = target.startWrite()
        try {
            stream.write(payload.toByteArray())
            target.finishWrite(stream)
        } catch (error: java.io.IOException) {
            target.failWrite(stream)
            throw error
        }
        target.baseFile.parentFile?.listFiles { entry -> entry.extension == "json" }
            ?.sortedByDescending(File::lastModified)?.drop(MAX_RECENT)?.forEach(File::delete)
    }

    private fun file(context: Context, capture: SavedCapture) =
        AtomicFile(File(context.filesDir, "capture-inbox/${capture.key}.json"))

    fun share(intent: Intent): SavedCapture? {
        @Suppress("DEPRECATION")
        val uri = if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else intent.data
        if (uri?.scheme != "content") return null
        val video = intent.type?.startsWith("video/") == true
        return SavedCapture(uri, if (video) "Paylaşılan klip" else "Paylaşılan yakalama", video, 0, 0)
    }
}

class CaptureInboxJob : JobService() {
    private val disk = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var active: JobParameters? = null
    private var translator: FocusTranslator? = null
    private var completed = 0

    override fun onStartJob(params: JobParameters): Boolean {
        if (!CaptureInbox.enabled(this) || !CaptureInbox.canRead(this)) return false
        if (active != null) {
            // OS can dispatch the periodic and change-triggered jobs together.
            main.post { jobFinished(params, true) }
            return true
        }
        active = params
        completed = 0
        translator = FocusTranslator(this)
        disk.execute {
            val work = CaptureInbox.items(this).filter { CaptureInbox.cached(this, it) == null }.take(6)
            main.post { next(params, work.iterator()) }
        }
        return true
    }

    private fun next(params: JobParameters, files: Iterator<SavedCapture>) {
        if (active !== params) return
        if (!files.hasNext()) {
            if (completed > 0) notifyCompleted(completed)
            translator?.close()
            translator = null
            active = null
            jobFinished(params, false)
            return
        }
        val capture = files.next()
        disk.execute {
            val frame = runCatching { CaptureInbox.load(this, capture) }.getOrNull()
            main.post {
                if (active !== params) {
                    frame?.bitmap?.recycle()
                    return@post
                }
                if (frame == null) {
                    next(params, files)
                } else {
                    translator?.read(frame.bitmap, FocusRegion.full) { lines ->
                        if (active === params) {
                            disk.execute {
                                val saved = lines != null && runCatching { CaptureInbox.save(this, capture, lines) }.isSuccess
                                main.post {
                                    if (active === params && saved && lines.orEmpty().any { it.turkish != null }) completed++
                                    next(params, files)
                                }
                            }
                        }
                    }
                    frame.bitmap.recycle()
                }
            }
        }
    }

    private fun notifyCompleted(count: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ps-captures", "PS App yakalamaları", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 82, Intent(this, CaptureInboxActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(82, Notification.Builder(this, "ps-captures").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DraBorn Odak").setContentText("$count PlayStation yakalaması çevrildi.")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (active === params) {
            active = null
            translator?.close()
            translator = null
        }
        return CaptureInbox.enabled(this)
    }

    override fun onDestroy() {
        active = null
        translator?.close()
        disk.shutdownNow()
        super.onDestroy()
    }
}
