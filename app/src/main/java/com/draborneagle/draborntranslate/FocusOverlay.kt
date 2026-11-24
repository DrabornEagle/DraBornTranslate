// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.floor

internal class FocusOverlay(private val context: Context) : AutoCloseable {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var surface: FocusCanvas? = null

    fun show(frameWidth: Int, frameHeight: Int, result: List<FocusText>) {
        val visible = result.filter { it.turkish != null }
        if (visible.isEmpty()) {
            close()
            return
        }
        val view = surface ?: FocusCanvas(context).also {
            val params = WindowManager.LayoutParams(
                -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = 0.5f
            }
            windows.addView(it, params)
            surface = it
        }
        view.show(frameWidth, frameHeight, null, visible)
    }

    override fun close() {
        val view = surface ?: return
        surface = null
        runCatching { windows.removeViewImmediate(view) }
    }
}

/** Maps bounding boxes to the same fit-center transform used to show the source bitmap. */
internal class FocusCanvas(context: Context, private val onSelect: ((FocusRegion) -> Unit)? = null) : View(context) {
    private val image = RectF()
    private val start = FloatArray(2)
    private var selection: RectF? = null
    private var frame: Bitmap? = null
    private var frameWidth = 1
    private var frameHeight = 1
    private var result = emptyList<FocusText>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6CF5D7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
    }
    private val letters = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    fun show(width: Int, height: Int, bitmap: Bitmap?, lines: List<FocusText>) {
        frameWidth = width
        frameHeight = height
        frame = bitmap
        result = lines
        invalidate()
    }

    fun select(region: FocusRegion?) {
        selection = region?.let { RectF(it.left, it.top, it.right, it.bottom) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val x = (width - frameWidth * scale) / 2
        val y = (height - frameHeight * scale) / 2
        image.set(x, y, width - x, height - y)
        frame?.let { canvas.drawBitmap(it, null, image, fill) }
        canvas.save()
        canvas.clipRect(image)
        for (entry in result) {
            val text = entry.turkish ?: continue
            val source = entry.box
            val box = RectF(x + source.left * scale, y + source.top * scale, x + source.right * scale, y + source.bottom * scale)
            drawReplacement(canvas, text, box, entry.background, scale)
        }
        selection?.let { canvas.drawRect(
            x + it.left * image.width(), y + it.top * image.height(),
            x + it.right * image.width(), y + it.bottom * image.height(), outline,
        ) }
        canvas.restore()
    }

    private fun drawReplacement(canvas: Canvas, text: String, box: RectF, background: Int, scale: Float) {
        val pad = maxOf(2f, 3f * scale)
        box.inset(-pad, -pad)
        if (!box.intersect(image)) return
        val textWidth = floor(box.width() - pad * 2).toInt().coerceAtLeast(1)
        val luminance = Color.luminance(background)
        val base = if (luminance > 0.62f) 0xFFF6F1F4.toInt() else Color.rgb(Color.red(background), Color.green(background), Color.blue(background))
        fill.color = base
        letters.color = if (Color.luminance(base) > 0.62f) 0xFF111525.toInt() else Color.WHITE
        letters.textSize = box.height().coerceIn(10f, 36f) * 0.64f
        var layout = layout(text, textWidth)
        val available = (box.height() - pad).coerceAtLeast(8f)
        while (layout.height > available && letters.textSize > 8f) {
            letters.textSize -= 1f
            layout = layout(text, textWidth)
        }
        val extraHeight = (layout.height + 2 * pad - box.height()).coerceAtLeast(0f)
        if (extraHeight > 0f) {
            val down = minOf(extraHeight, image.bottom - box.bottom)
            box.bottom += down
            box.top = maxOf(image.top, box.top - (extraHeight - down))
        }
        canvas.drawRoundRect(box, pad, pad, fill)
        canvas.save()
        canvas.clipRect(box)
        canvas.translate(box.left + pad, box.top + maxOf(0f, (box.height() - layout.height) / 2f))
        layout.draw(canvas)
        canvas.restore()
    }

    private fun layout(text: String, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, letters, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val select = onSelect ?: return false
        if (image.width() <= 1 || image.height() <= 1) return false
        val x = ((event.x - image.left) / image.width()).coerceIn(0f, 1f)
        val y = ((event.y - image.top) / image.height()).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!image.contains(event.x, event.y)) return false
                parent.requestDisallowInterceptTouchEvent(true)
                start[0] = x
                start[1] = y
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val region = FocusRegion(minOf(start[0], x), minOf(start[1], y), maxOf(start[0], x), maxOf(start[1], y))
                if (abs(x - start[0]) >= 0.03f && abs(y - start[1]) >= 0.03f) {
                    select(region)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        performClick()
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
