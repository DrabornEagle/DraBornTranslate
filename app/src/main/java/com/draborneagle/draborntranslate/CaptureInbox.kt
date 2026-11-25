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
import android.content.ContentResolver
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
import android.provider.BaseColumns
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
    val added: Long = modified,
    val durationMs: Long = 0,
) {
    val key: String get() = MessageDigest.getInstance("SHA-256")
        .digest("$uri:$modified:$size".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal data class CaptureFrame(val bitmap: Bitmap, val durationMs: Long = 0L, val timeMs: Long = 0L)

internal object CaptureInbox {
    private const val PERIODIC_JOB = 521
    private const val NEW_FILES_JOB = 522
    internal const val MEDIA_CHANGE_JOB = 523
    private const val MAX_RECENT = 120
    private val collections = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private var observer: ContentObserver? = null
    @Volatile var processing: String? = null
        private set
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

    fun fullAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 33 ->
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        else -> canRead(context)
    }

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto", value).apply()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (value) {
            watch(context)
            enqueue(context)
        } else {
            for (job in listOf(PERIODIC_JOB, NEW_FILES_JOB, MEDIA_CHANGE_JOB)) scheduler.cancel(job)
            stopWatchingIfIdle(context)
        }
        notifyChanged()
    }

    fun observe(context: Context, listener: () -> Unit) {
        listeners.add(listener)
        watch(context)
    }

    fun removeObserver(context: Context, listener: () -> Unit) {
        listeners.remove(listener)
        stopWatchingIfIdle(context)
    }

    private fun stopWatchingIfIdle(context: Context) {
        if (listeners.isNotEmpty() || enabled(context)) return
        observer?.let(context.contentResolver::unregisterContentObserver)
        observer = null
    }

    fun watch(context: Context) {
        val app = context.applicationContext
        if (enabled(app) && canRead(app)) {
            val scheduler = app.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(PERIODIC_JOB) == null) scheduler.schedule(
                job(app, PERIODIC_JOB).setPeriodic(15 * 60 * 1000L).setPersisted(true).build(),
            )
            armChanges(app)
        }
        if (observer != null || (listeners.isEmpty() && !enabled(app))) return
        val refresh = Runnable {
            notifyChanged()
            enqueue(app)
        }
        val registered = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, 320L)
            }
        }
        for (uri in collections) app.contentResolver.registerContentObserver(uri, true, registered)
        observer = registered
    }

    fun armChanges(context: Context) {
        if (!enabled(context) || !canRead(context)) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(MEDIA_CHANGE_JOB) != null) return
        val trigger = job(context, MEDIA_CHANGE_JOB)
            .setTriggerContentUpdateDelay(400L).setTriggerContentMaxDelay(2_000L)
        for (uri in collections) trigger.addTriggerContentUri(
            JobInfo.TriggerContentUri(uri, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS),
        )
        scheduler.schedule(trigger.build())
    }

    fun enqueue(context: Context) {
        if (!enabled(context) || !canRead(context)) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(NEW_FILES_JOB) == null) scheduler.schedule(
            job(context, NEW_FILES_JOB).setMinimumLatency(600L).build(),
        )
    }

    private fun job(context: Context, id: Int) = JobInfo.Builder(id, ComponentName(context, CaptureInboxJob::class.java))

    fun notifyChanged() {
        handler.post { listeners.toList().forEach { it() } }
    }

    fun markProcessing(key: String?) {
        if (processing == key) return
        processing = key
        notifyChanged()
    }

    fun items(context: Context): List<SavedCapture> = buildList {
        addAll(query(context, collections[0], video = false))
        addAll(query(context, collections[1], video = true))
    }.sortedWith(compareByDescending<SavedCapture> { it.added }
        .thenByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0 })
        .take(MAX_RECENT)

    private fun query(context: Context, collection: Uri, video: Boolean): List<SavedCapture> {
        val id = BaseColumns._ID
        val name = MediaStore.MediaColumns.DISPLAY_NAME
        val path = MediaStore.MediaColumns.RELATIVE_PATH
        val modified = MediaStore.MediaColumns.DATE_MODIFIED
        val added = MediaStore.MediaColumns.DATE_ADDED
        val sizeColumn = MediaStore.MediaColumns.SIZE
        val duration = MediaStore.Video.VideoColumns.DURATION
        val fields = arrayOf(id, name, modified, sizeColumn, added) + if (video) arrayOf(duration) else emptyArray()
        val matchName = if (video) "%-master_playlist.%" else "%-screenshotUrl.%"
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "($path LIKE ? OR $path LIKE ? OR ($path LIKE ? AND $name LIKE ?)) AND $sizeColumn > 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("%/PS App/%", "%/PlayStation App/%", "Download/%", matchName))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "$added DESC, $id DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_RECENT)
        }
        return runCatching {
            context.contentResolver.query(collection, fields, args, null)?.use { cursor ->
                buildList {
                    while (size < MAX_RECENT && cursor.moveToNext()) {
                        add(SavedCapture(
                            ContentUris.withAppendedId(collection, cursor.getLong(0)),
                            cursor.getString(1) ?: "PlayStation yakalaması", video, cursor.getLong(2), cursor.getLong(3),
                            cursor.getLong(4).takeIf { it > 0 } ?: cursor.getLong(2),
                            if (video) cursor.getLong(5) else 0L,
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
        notifyChanged()
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
    private var scanAgain = false
    private var rearmMedia = false

    override fun onStartJob(params: JobParameters): Boolean {
        if (!CaptureInbox.enabled(this) || !CaptureInbox.canRead(this)) return false
        if (params.jobId == CaptureInbox.MEDIA_CHANGE_JOB) {
            rearmMedia = true
            CaptureInbox.notifyChanged()
        } else {
            CaptureInbox.armChanges(this)
        }
        if (active != null) {
            scanAgain = true
            return false
        }
        active = params
        completed = 0
        scanAgain = false
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
            CaptureInbox.markProcessing(null)
            jobFinished(params, false)
            val again = scanAgain
            val rearm = rearmMedia
            scanAgain = false
            rearmMedia = false
            val app = applicationContext
            main.postDelayed({
                if (rearm) CaptureInbox.armChanges(app)
                if (again) CaptureInbox.enqueue(app)
            }, 500L)
            return
        }
        val capture = files.next()
        CaptureInbox.markProcessing(capture.key)
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
            .setContentTitle(VISUAL_TRANSLATION).setContentText("$count PlayStation yakalaması çevrildi.")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (active === params) {
            active = null
            translator?.close()
            translator = null
            CaptureInbox.markProcessing(null)
        }
        if (rearmMedia) {
            rearmMedia = false
            val app = applicationContext
            main.postDelayed({ CaptureInbox.armChanges(app) }, 500L)
        }
        return CaptureInbox.enabled(this)
    }

    override fun onDestroy() {
        active = null
        CaptureInbox.markProcessing(null)
        translator?.close()
        disk.shutdownNow()
        super.onDestroy()
    }
}
