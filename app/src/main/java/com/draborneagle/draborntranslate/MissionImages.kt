// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.URI
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Displays public source thumbnails. The pack stores links to the original publisher. */
internal object MissionImages {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4)
    private data class Request(val url: String, val size: Int)

    private val cache = object : LruCache<Request, Bitmap>(12 * 1024) {
        override fun sizeOf(key: Request, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val pending = mutableMapOf<Request, MutableList<(Bitmap?) -> Unit>>()

    fun load(image: GtaMissionImage, size: Int, show: (Bitmap?) -> Unit) {
        val request = Request(image.url, size)
        cache[request]?.let { show(it); return }
        pending[request]?.let { it.add(show); return }
        pending[request] = mutableListOf(show)
        io.execute {
            val bitmap = runCatching {
                val uri = missionImageUri(request.url) ?: return@runCatching null
                fetch(uri)?.let { decode(it, request.size) }
            }.getOrNull()
            main.post {
                if (bitmap != null) cache.put(request, bitmap)
                pending.remove(request)?.forEach { it(bitmap) }
            }
        }
    }

    private fun fetch(source: URI): ByteArray? {
        var uri = source
        repeat(3) {
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 4500
                connection.readTimeout = 4500
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "DraBornTranslate Android")
                when (connection.responseCode) {
                    200 -> {
                        if (connection.contentType?.startsWith("image/", ignoreCase = true) != true ||
                            connection.contentLengthLong > MAX_BYTES) return null
                        return connection.inputStream.use { readLimited(it, MAX_BYTES) }
                    }
                    301, 302, 303, 307, 308 -> {
                        val target = connection.getHeaderField("Location") ?: return null
                        uri = missionImageUri(uri.resolve(target).toString()) ?: return null
                    }
                    else -> return null
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun decode(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 32_000_000) {
            return null
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > size * 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private const val MAX_BYTES = 2 * 1024 * 1024
}

internal class MissionImageView(
    context: Context,
    private val source: GtaMissionImage,
    private val maxSize: Int,
    private val onLoaded: (() -> Unit)? = null,
) : ImageView(context) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MissionImages.load(source, maxSize) { bitmap ->
            if (!isAttachedToWindow || bitmap == null) return@load
            setImageBitmap(bitmap)
            scaleType = ScaleType.CENTER_CROP
            onLoaded?.invoke()
        }
    }
}
