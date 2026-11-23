// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Independent percentage-based regions in the *visible* FIT_CENTER camera preview. */
internal data class CloudRegion(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    fun normalized(): CloudRegion {
        val x = left.coerceIn(0, 95)
        val y = top.coerceIn(0, 95)
        return CloudRegion(
            x, y, width.coerceIn(5, 100 - x), height.coerceIn(5, 100 - y)
        )
    }

    fun move(dx: Int, dy: Int): CloudRegion {
        val r = normalized()
        return r.copy(
            left = (r.left + dx).coerceIn(0, 100 - r.width),
            top = (r.top + dy).coerceIn(0, 100 - r.height),
        )
    }

    fun resize(handle: Int, dx: Int, dy: Int): CloudRegion {
        val r = normalized()
        var x0 = r.left
        var y0 = r.top
        var x1 = r.left + r.width
        var y1 = r.top + r.height
        when (handle) {
            1 -> { x0 = (x0 + dx).coerceIn(0, x1 - 5); y0 = (y0 + dy).coerceIn(0, y1 - 5) }
            2 -> { x1 = (x1 + dx).coerceIn(x0 + 5, 100); y0 = (y0 + dy).coerceIn(0, y1 - 5) }
            3 -> { x0 = (x0 + dx).coerceIn(0, x1 - 5); y1 = (y1 + dy).coerceIn(y0 + 5, 100) }
            4 -> { x1 = (x1 + dx).coerceIn(x0 + 5, 100); y1 = (y1 + dy).coerceIn(y0 + 5, 100) }
        }
        return CloudRegion(x0, y0, x1 - x0, y1 - y0)
    }

    fun pixel(area: Pixels): Pixels = percentRect(area, left, top, width, height)

    companion object {
        val SUBTITLES = CloudRegion(6, 65, 88, 28)
        val MESSAGES = CloudRegion(52, 6, 45, 49)
    }
}

/** Screen regions remain independent of the camera's sensor orientation. */
internal class CloudRegionOverlay(context: Context) : View(context) {
    var subtitleRegion = CloudRegion.SUBTITLES
        set(value) { field = value.normalized(); invalidate() }
    var messageRegion = CloudRegion.MESSAGES
        set(value) { field = value.normalized(); invalidate() }
    var selected = 0
        set(value) { field = value.coerceIn(0, 1); invalidate() }
    var editing = false
        set(value) { field = value; invalidate() }
    var aspect = 16f / 9f
        set(value) { field = value.coerceIn(0.4f, 2.5f); invalidate() }
    var onChanged: ((CloudRegion, CloudRegion) -> Unit)? = null

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2.5f)
        style = Paint.Style.STROKE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(13f); isFakeBoldText = true
    }
    private val video = RectF()
    private var gestureHandle = 0
    private var gestureRegion = 0
    private var originX = 0f
    private var originY = 0f
    private var startRegion = CloudRegion.SUBTITLES
    private var active = false

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun updateVideoBounds() {
        val aw = width.toFloat().coerceAtLeast(1f)
        val ah = height.toFloat().coerceAtLeast(1f)
        val imageW = min(aw, ah * aspect)
        val imageH = imageW / aspect
        video.set((aw - imageW) / 2f, (ah - imageH) / 2f,
            (aw + imageW) / 2f, (ah + imageH) / 2f)
    }

    private fun onScreen(rect: CloudRegion): RectF {
        val r = rect.normalized()
        return RectF(
            video.left + video.width() * r.left / 100f,
            video.top + video.height() * r.top / 100f,
            video.left + video.width() * (r.left + r.width) / 100f,
            video.top + video.height() * (r.top + r.height) / 100f,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateVideoBounds()
        val rects = listOf(onScreen(subtitleRegion), onScreen(messageRegion))
        rects.forEachIndexed { i, rect ->
            val color = if (i == 0) 0xFF5CCBFF.toInt() else 0xFF64E6BE.toInt()
            fill.color = if (editing) (if (i == 0) 0x245CCBFF else 0x2464E6BE) else Color.TRANSPARENT
            canvas.drawRoundRect(rect, dp(10f), dp(10f), fill)
            stroke.color = color
            stroke.strokeWidth = dp(if (editing && selected == i) 3.5f else 2f)
            canvas.drawRoundRect(rect, dp(10f), dp(10f), stroke)
            if (editing) {
                val name = if (i == 0) "ALTYAZI" else "TELEFON / LENS"
                fill.color = 0xE51B2140.toInt()
                canvas.drawRoundRect(rect.left, rect.top - dp(27f), rect.left + dp(142f),
                    rect.top, dp(7f), dp(7f), fill)
                canvas.drawText(name, rect.left + dp(8f), rect.top - dp(9f), words)
                if (selected == i) {
                    fill.color = color
                    for (x in listOf(rect.left, rect.right)) {
                        for (y in listOf(rect.top, rect.bottom)) {
                            canvas.drawCircle(x, y, dp(8f), fill)
                        }
                    }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return false
        updateVideoBounds()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = false
                val boxes = listOf(onScreen(subtitleRegion), onScreen(messageRegion))
                val hit = boxes.indices.reversed().firstOrNull { index ->
                    val box = boxes[index]
                    box.contains(event.x, event.y) ||
                        (index == selected && nearCorner(box, event.x, event.y) != 0)
                } ?: return true
                selected = hit
                gestureRegion = hit
                val b = boxes[hit]
                gestureHandle = nearCorner(b, event.x, event.y)
                startRegion = if (hit == 0) subtitleRegion else messageRegion
                originX = event.x
                originY = event.y
                active = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                val dx = ((event.x - originX) * 100 / video.width()).toInt()
                val dy = ((event.y - originY) * 100 / video.height()).toInt()
                val next = if (gestureHandle == 0) startRegion.move(dx, dy)
                           else startRegion.resize(gestureHandle, dx, dy)
                if (gestureRegion == 0) subtitleRegion = next else messageRegion = next
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active) {
                    active = false
                    onChanged?.invoke(subtitleRegion, messageRegion)
                    performClick()
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun nearCorner(b: RectF, x: Float, y: Float): Int {
        val radius = dp(26f)
        val points = listOf(b.left to b.top, b.right to b.top,
            b.left to b.bottom, b.right to b.bottom)
        return (points.indexOfFirst { (px, py) ->
            kotlin.math.abs(px - x) <= radius && kotlin.math.abs(py - y) <= radius
        } + 1).coerceAtLeast(0)
    }

    fun contentBox(): RectF { updateVideoBounds(); return RectF(video) }
}
