// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal class CaptionOverlay(
    private val context: Context,
    private val settings: CaptureSettings,
) : AutoCloseable {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private var label: TextView? = null

    fun show(text: String?) {
        if (text == null) {
            close()
            return
        }
        label?.let {
            it.text = text
            return
        }
        val margin = context.dp(14)
        val caption = TextView(context).apply {
            this.text = text
            textSize = settings.textSize.toFloat()
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            maxWidth = minOf(context.dp(700), context.resources.displayMetrics.widthPixels - margin * 2)
            if (settings.readingArea == ReadingArea.GAME_TEXT) {
                maxLines = 6
                ellipsize = TextUtils.TruncateAt.END
            }
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF101423.toInt())
                cornerRadius = context.dp(14).toFloat()
            }
            setPadding(margin, context.dp(8), margin, context.dp(6))
            addView(caption)
            addView(translationAttribution(context).apply {
                layoutParams = LinearLayout.LayoutParams(context.dp(152), context.dp(19)).also {
                    it.topMargin = context.dp(3)
                }
            })
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
    }

    override fun close() {
        val view = panel ?: return
        panel = null
        label = null
        windows.removeViewImmediate(view)
    }
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun translationAttribution(context: Context) = ImageView(context).apply {
    val badge = context.resources.openRawResource(R.raw.google_translate_attribution).use {
        BitmapFactory.decodeStream(it)
    }
    setImageBitmap(badge)
    scaleType = ImageView.ScaleType.FIT_CENTER
    contentDescription = "Powered by Google Translate"
    background = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = context.dp(3).toFloat()
    }
    setPadding(context.dp(4), context.dp(2), context.dp(4), context.dp(2))
}
