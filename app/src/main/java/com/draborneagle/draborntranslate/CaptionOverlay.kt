// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

internal class CaptionOverlay(
    private val context: Context,
    private val settings: CaptureSettings,
) : AutoCloseable {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var panel: LinearLayout? = null
    private var label: TextView? = null
    private var pages = emptyList<String>()
    private var page = 0
    private val advance = object : Runnable {
        override fun run() {
            if (pages.size < 2) return
            page = (page + 1) % pages.size
            label?.text = pages[page]
            main.postDelayed(this, PAGE_INTERVAL_MS)
        }
    }

    fun show(cue: Caption?) {
        if (cue == null) {
            close()
            return
        }
        label?.let {
            showText(it, cue.turkish)
            return
        }
        val margin = context.dp(14)
        val caption = TextView(context).apply {
            textSize = settings.textSize.toFloat()
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            maxWidth = minOf(context.dp(700), context.resources.displayMetrics.widthPixels - margin * 2)
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF151833.toInt())
                setStroke(context.dp(1), 0xFF4A5B8D.toInt())
                cornerRadius = context.dp(14).toFloat()
            }
            setPadding(margin, context.dp(8), margin, context.dp(8))
            addView(caption)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (settings.captionsAtTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            y = margin
            // Android 12+ bu değerin altında kalan tek dokunulmaz katmandan dokunmaları iletir.
            alpha = 0.79f
        }
        windows.addView(view, params)
        panel = view
        label = caption
        showText(caption, cue.turkish)
    }

    private fun showText(caption: TextView, text: String) {
        if (settings.readingArea == ReadingArea.SUBTITLES) {
            caption.text = text
            return
        }
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        pages = text.lines().chunked(if (landscape) 4 else 6).map { it.joinToString("\n") }
        page = page.coerceAtMost(pages.lastIndex)
        caption.text = pages[page]
        if (pages.size == 1) main.removeCallbacks(advance)
        else if (!main.hasCallbacks(advance)) main.postDelayed(advance, PAGE_INTERVAL_MS)
    }

    override fun close() {
        main.removeCallbacks(advance)
        pages = emptyList()
        page = 0
        val view = panel ?: return
        panel = null
        label = null
        windows.removeViewImmediate(view)
    }

    private companion object {
        const val PAGE_INTERVAL_MS = 3600L
    }
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
