// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Phone-only, touch-through visual translation over the captured game text. */
internal class LensOverlay(
    private val context: Context,
    private val settings: CaptureSettings,
) : AutoCloseable {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var canvas: FrameLayout? = null

    fun show(cues: List<LensCue>, region: FrameRegion) {
        if (cues.isEmpty() || region.frameWidth < 2 || region.frameHeight < 2) {
            close()
            return
        }
        val root = canvas ?: FrameLayout(context).also { view ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Android 12+ untrusted overlay touch-through opacity limit.
                alpha = 0.79f
            }
            windows.addView(view, params)
            canvas = view
        }
        root.removeAllViews()
        val screenWidth = if (Build.VERSION.SDK_INT >= 30) windows.maximumWindowMetrics.bounds.width()
            else context.resources.displayMetrics.widthPixels
        val screenHeight = if (Build.VERSION.SDK_INT >= 30) windows.maximumWindowMetrics.bounds.height()
            else context.resources.displayMetrics.heightPixels
        val scaleX = screenWidth.toFloat() / region.frameWidth
        val scaleY = screenHeight.toFloat() / region.frameHeight

        cues.take(18).forEach { cue ->
            val box = cue.bounds
            val x = ((box.left + region.x) * scaleX).roundToInt().coerceIn(0, screenWidth - 1)
            val y = ((box.top + region.y) * scaleY).roundToInt().coerceIn(0, screenHeight - 1)
            val available = (screenWidth - x - context.dp(5)).coerceAtLeast(context.dp(20))
            val originalWidth = (box.width() * scaleX).roundToInt()
            val expanded = (cue.turkish.length * (settings.textSize * 0.54f)).roundToInt()
            val width = maxOf(originalWidth + context.dp(12), expanded + context.dp(12))
                .coerceAtMost(minOf(available, (screenWidth * 0.70f).roundToInt()))
                .coerceAtLeast(minOf(available, context.dp(25)))

            val label = TextView(context).apply {
                text = cue.turkish
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = if (settings.readingArea == ReadingArea.DUAL) 6 else 3
                ellipsize = TextUtils.TruncateAt.END
                setAutoSizeTextTypeUniformWithConfiguration(
                    11, settings.textSize, 1, TypedValue.COMPLEX_UNIT_SP,
                )
                setPadding(context.dp(5), context.dp(4), context.dp(5), context.dp(4))
                setShadowLayer(2f, 0f, 1f, Color.BLACK)
                background = GradientDrawable().apply {
                    setColor(0xF0141B2D.toInt())
                    setStroke(context.dp(1), 0xAA7DE5FF.toInt())
                    cornerRadius = context.dp(7).toFloat()
                }
            }
            root.addView(label, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = x
                topMargin = y
            })
        }
    }

    override fun close() {
        val view = canvas ?: return
        canvas = null
        windows.removeViewImmediate(view)
    }
}
