// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CaptureInboxActivity : Activity() {
    private val disk = Executors.newSingleThreadExecutor()
    private val thumbnails = Executors.newFixedThreadPool(2)
    private val images = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private lateinit var adapter: CapturesAdapter
    private lateinit var empty: TextView
    private lateinit var mode: TextView
    private lateinit var auto: Button
    private lateinit var access: Button
    private var refresh = 0
    private val mediaChanged = { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = stack()
        pageRoot(screen)
        screen.addView(titleBar("PS App yakalamaları", Palette.mint))
        val tools = stack().apply { setPadding(dp(16), dp(7), dp(16), dp(10)) }
        screen.addView(tools)
        val statusCard = stack().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = blend(0xFF173A3C.toInt(), 0xFF162737.toInt())
        }
        mode = copy("", 15, Palette.white, true)
        statusCard.addView(mode)
        statusCard.addBelow(copy("PS App’ten telefona inen görüntüler burada görünür.", 13), 4)
        auto = press("Otomatik çeviri", Palette.mint) { toggleAutomatic() }
        statusCard.addBelow(auto, 9)
        access = press("Medya erişimini düzenle") { requestMedia() }.apply { visibility = View.GONE }
        statusCard.addBelow(access, 6)
        tools.addBelow(statusCard)

        tools.addBelow(row().apply {
            addView(press("PS App’i aç") { launchPlayStation() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
            addView(press("Dosya seç") { chooseCapture() }, LinearLayout.LayoutParams(0, -2, 1f))
        }, 9)

        val gridArea = FrameLayout(this)
        screen.addView(gridArea, LinearLayout.LayoutParams(-1, 0, 1f))
        empty = copy("Yakalamalar yükleniyor…").apply {
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(12), dp(26), dp(12))
        }
        gridArea.addView(empty, FrameLayout.LayoutParams(-1, -1))
        adapter = CapturesAdapter()
        val grid = GridView(this).apply {
            numColumns = if (resources.configuration.screenWidthDp >= 600) 3 else 2
            horizontalSpacing = dp(10)
            verticalSpacing = dp(10)
            setPadding(dp(16), dp(2), dp(16), dp(16))
            clipToPadding = false
            this.adapter = this@CaptureInboxActivity.adapter
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            emptyView = empty
            setOnItemClickListener { _, _, position, _ ->
                val selected = this@CaptureInboxActivity.adapter.getItem(position)
                open(selected.capture)
            }
        }
        gridArea.addView(grid, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onStart() {
        super.onStart()
        CaptureInbox.observe(this, mediaChanged)
    }

    override fun onResume() {
        super.onResume()
        CaptureInbox.watch(this)
        CaptureInbox.enqueue(this)
        reload()
    }

    override fun onStop() {
        CaptureInbox.removeObserver(this, mediaChanged)
        super.onStop()
    }

    private fun reload() {
        if (isDestroyed || isFinishing) return
        val enabled = CaptureInbox.enabled(this)
        val readable = CaptureInbox.canRead(this)
        val full = CaptureInbox.fullAccess(this)
        mode.text = when {
            !readable -> "Yakalamalar için medya erişimi gerekiyor."
            !full -> "Seçili fotoğraflara erişiliyor. Yeni yakalamalar için tam erişim ver."
            enabled -> "Otomatik çeviri açık"
            else -> "Otomatik çeviri kapalı"
        }
        auto.text = if (enabled && readable) "Otomatik çeviriyi durdur" else "Otomatik çeviriyi aç"
        access.visibility = if (!full) View.VISIBLE else View.GONE
        val request = ++refresh
        disk.execute {
            val current = CaptureInbox.processing
            val rows = CaptureInbox.items(this).map { capture ->
                val result = CaptureInbox.cached(this, capture)
                val count = result?.count { it.turkish != null } ?: 0
                val caption = when {
                    count > 0 -> "$count çeviri"
                    current == capture.key -> "Çevriliyor…"
                    result != null && result.isEmpty() -> "Yazı bulunamadı"
                    result != null -> "Metin okundu"
                    else -> if (enabled) "Sırada" else "Çevirmek için aç"
                }
                CaptureTile(capture, caption)
            }
            runOnUiThread {
                if (isDestroyed || request != refresh) return@runOnUiThread
                empty.text = if (readable) "Yeni yakalama bekleniyor. PS App’te yakalamayı aç ve İndir’e dokun."
                else "Medya izni ver veya PS App’te Paylaş → DraBornTranslate’i seç."
                adapter.submit(rows)
            }
        }
    }

    private fun toggleAutomatic() {
        if (CaptureInbox.enabled(this) && CaptureInbox.canRead(this)) {
            CaptureInbox.setEnabled(this, false)
            reload()
        } else if (CaptureInbox.canRead(this)) enableAutomatic()
        else requestMedia()
    }

    private fun requestMedia() {
        requestPermissions(CaptureInbox.permissions(), MEDIA_PERMISSION)
    }

    private fun enableAutomatic() {
        CaptureInbox.setEnabled(this, true)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
        }
        reload()
    }

    @Deprecated("Android permission callback")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == MEDIA_PERMISSION && CaptureInbox.canRead(this)) enableAutomatic() else reload()
    }

    private fun launchPlayStation() {
        val app = packageManager.getLaunchIntentForPackage("com.scee.psxandroid")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.scee.psxandroid"))
        runCatching { startActivity(app) }
    }

    @Suppress("DEPRECATION")
    private fun chooseCapture() {
        startActivityForResult(mediaPicker(), PICK_CAPTURE)
    }

    @Deprecated("Android activity result callback")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != PICK_CAPTURE || result != RESULT_OK) return
        val uri = data?.data ?: return
        val video = contentResolver.getType(uri)?.startsWith("video/") == true
        open(SavedCapture(uri, if (video) "Seçilen klip" else "Seçilen görüntü", video, 0, 0))
    }

    private fun open(item: SavedCapture) = startActivity(captureIntent(item))

    override fun onDestroy() {
        refresh++
        disk.shutdownNow()
        thumbnails.shutdownNow()
        images.evictAll()
        super.onDestroy()
    }

    private fun thumbnail(capture: SavedCapture, target: ImageView) {
        val key = capture.key
        if (target.tag == key) return
        target.tag = key
        val cached = images.get(key)
        target.setImageBitmap(cached)
        if (cached != null) return
        thumbnails.execute {
            val bitmap = runCatching { contentResolver.loadThumbnail(capture.uri, Size(500, 282), null) }.getOrNull()
                ?: if (capture.video) null else runCatching {
                    val src = ImageDecoder.createSource(contentResolver, capture.uri)
                    ImageDecoder.decodeBitmap(src) { decoder, _, _ -> decoder.setTargetSampleSize(4) }
                }.getOrNull()
            if (bitmap == null) return@execute
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                images.put(key, bitmap)
                if (target.tag == key) target.setImageBitmap(bitmap)
            }
        }
    }

    private data class CaptureTile(val capture: SavedCapture, val status: String)

    private inner class CapturesAdapter : BaseAdapter() {
        private var tiles = emptyList<CaptureTile>()

        fun submit(next: List<CaptureTile>) {
            if (tiles == next) return
            tiles = next
            notifyDataSetChanged()
        }

        override fun getCount(): Int = tiles.size
        override fun getItem(position: Int): CaptureTile = tiles[position]
        override fun getItemId(position: Int): Long = tiles[position].capture.uri.lastPathSegment?.toLongOrNull()
            ?: position.toLong()

        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val tile = recycled as? CaptureCell ?: CaptureCell()
            val model = getItem(position)
            tile.bind(model, newest = position == 0)
            return tile
        }
    }

    private inner class CaptureCell : LinearLayout(this@CaptureInboxActivity) {
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val type = copy("", 11, Palette.white, true)
        private val timestamp = copy("", 13, Palette.white, true)
        private val state = copy("", 12)

        init {
            orientation = VERTICAL
            background = filled(Palette.surface, 15, Palette.border)
            clipToOutline = true
            val preview = object : FrameLayout(context) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    val side = View.MeasureSpec.getSize(widthSpec)
                    super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(side * 9 / 16, View.MeasureSpec.EXACTLY))
                }
            }.apply {
                setBackgroundColor(0xFF2A3855.toInt())
                addView(glyph(R.drawable.ic_capture_album, Palette.blue),
                    FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                addView(image, FrameLayout.LayoutParams(-1, -1))
                addView(type.apply {
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    background = filled(0xE512192B.toInt(), 8)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
                    .apply { topMargin = dp(6); marginStart = dp(6) })
            }
            addView(preview, LayoutParams(-1, -2))
            addView(stack().apply {
                setPadding(dp(9), dp(8), dp(9), dp(9))
                addView(timestamp)
                addBelow(state, 2)
            }, LayoutParams(-1, -2))
        }

        fun bind(item: CaptureTile, newest: Boolean) {
            val data = item.capture
            val kind = if (data.video) {
                val total = data.durationMs / 1000
                if (total > 0) "▶ %d:%02d".format(Locale.ROOT, total / 60, total % 60) else "▶ Klip"
            } else if (newest) "En yeni" else "Görüntü"
            type.text = kind
            timestamp.text = captureDate(data.added)
            state.text = item.status
            state.setTextColor(if (item.status.endsWith("çeviri")) Palette.mint else Palette.quiet)
            background = filled(Palette.surface, 15, if (newest) Palette.mint else Palette.border)
            contentDescription = "$kind · ${timestamp.text} · ${state.text}"
            tag = "capture:${data.uri}"
            thumbnail(data, image)
        }
    }

    companion object {
        private const val MEDIA_PERMISSION = 41
        private const val PICK_CAPTURE = 42
        private const val NOTIFICATION_PERMISSION = 43
    }
}

internal fun Activity.captureIntent(capture: SavedCapture) = Intent(this, FocusActivity::class.java).apply {
    action = Intent.ACTION_VIEW
    setDataAndType(capture.uri, if (capture.video) "video/*" else "image/*")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    putExtra("captureName", capture.name)
    putExtra("captureModified", capture.modified)
    putExtra("captureSize", capture.size)
    putExtra("captureAdded", capture.added)
}

internal fun mediaPicker() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    type = "*/*"
    addCategory(Intent.CATEGORY_OPENABLE)
    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
}

internal fun captureDate(seconds: Long): String =
    if (seconds <= 0) "Tarih bilinmiyor"
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.forLanguageTag("tr-TR"))
        .format(Date(seconds * 1_000L))
