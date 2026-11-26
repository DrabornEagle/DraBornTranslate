// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.gametranslator.gametranslator_mobile.core.TextBox

/** A single, touch-through layer keeps captions and HUD text aligned to the video. */
internal class GtaHudOverlay(
    private val context: Context,
    private val settings: CaptureSettings,
) : AutoCloseable {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var surface: Surface? = null

    fun show(caption: Caption?, blocks: List<HudBlock>) {
        if (caption == null && blocks.isEmpty()) {
            close()
            return
        }
        val view = surface ?: Surface(context).also {
            windows.addView(it, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = if (settings.focus) 0.55f else 0.79f
            })
            surface = it
        }
        view.caption = caption
        view.blocks = blocks
        view.invalidate()
    }

    override fun close() {
        val view = surface ?: return
        surface = null
        windows.removeViewImmediate(view)
    }

    private inner class Surface(context: Context) : View(context) {
        var caption: Caption? = null
        var blocks: List<HudBlock> = emptyList()
        private val background = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(1.5f, 0f, 1f, Color.BLACK)
        }
        private val textScale = resources.displayMetrics.scaledDensity
        private val radius = context.dp(7).toFloat()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width < 2 || height < 2) return
            val video = videoArea(width, height)
            val clip = RectF(video.x.toFloat(), video.y.toFloat(), (video.x + video.width).toFloat(), (video.y + video.height).toFloat())
            canvas.save()
            canvas.clipRect(clip)
            for (block in blocks) {
                val box = screenBox(block.box, video)
                if (block.kind == HudKind.PHONE) drawPhone(canvas, block, box, clip)
                else drawBlock(canvas, block, box, clip)
            }
            caption?.let { cue ->
                val area = cue.position?.box?.let { screenBox(it, video) }
                    ?: RectF(clip.left + clip.width() * 0.20f, clip.bottom - clip.height() * 0.22f,
                        clip.right - clip.width() * 0.20f, clip.bottom - clip.height() * 0.10f)
                val text = SpannableString(cue.turkish)
                val name = Gta6Profile.speakerName(cue.turkish)
                val color = cue.position?.speakerColor?.let(::validColor)
                if (name != null && color != null) text.setSpan(ForegroundColorSpan(color), 0, name.length, 0)
                val size = minOf(settings.textSize * textScale, clip.height() * 0.060f)
                drawCard(canvas, text, area, clip, size, 0xEE10111B.toInt(), centered = true)
            }
            canvas.restore()
        }

        private fun drawBlock(canvas: Canvas, block: HudBlock, box: RectF, video: RectF) {
            val content = SpannableStringBuilder()
            block.title?.let { content.append(it).append('\n') }
            for ((index, line) in block.lines.withIndex()) {
                if (index > 0) content.append(if (block.kind == HudKind.CONTROLS) "   " else "\n")
                content.append(styledText(line.text))
            }
            val width = video.width() * when (block.kind) {
                HudKind.OBJECTIVE -> 0.47f
                HudKind.CONTROLS -> 0.42f
                HudKind.PAUSE_MENU -> 0.22f
                else -> 0.30f
            }
            val left = when (block.kind) {
                HudKind.HELP -> box.right + radius
                HudKind.OBJECTIVE -> box.centerX() - width / 2
                HudKind.PAUSE_MENU -> box.left
                else -> box.right - width
            }
            val area = RectF(left, box.top, left + width, box.bottom)
            val size = minOf(15f * textScale, video.height() * 0.045f)
            drawCard(canvas, content, area, video, size, 0xE8212539.toInt(), centered = block.kind == HudKind.OBJECTIVE)
        }

        private fun drawCard(
            canvas: Canvas, text: CharSequence, anchor: RectF, video: RectF,
            size: Float, fill: Int, centered: Boolean,
        ) {
            val padding = radius
            val width = minOf(maxOf(anchor.width() + padding * 2, video.width() * 0.20f), video.width() * 0.82f)
            val left = (anchor.centerX() - width / 2).coerceIn(video.left, video.right - width)
            val layout = layout(text, width - padding * 2, size, centered)
            val height = maxOf(anchor.height(), layout.height + padding * 2)
            val top = (anchor.centerY() - height / 2).coerceIn(video.top, maxOf(video.top, video.bottom - height))
            background.color = fill
            canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, background)
            drawText(canvas, layout, left + padding, top + (height - layout.height) / 2)
        }

        private fun drawPhone(canvas: Canvas, block: HudBlock, box: RectF, video: RectF) {
            val padding = radius
            val available = video.height() * 0.78f
            val width = video.width() * 0.34f
            val left = if (box.left - video.left >= width + padding * 2) box.left - width - padding
                else (box.right + padding).coerceAtMost(video.right - width)
            val title = block.title.orEmpty()
            val initials = title.split(' ').mapNotNull { it.firstOrNull() }.take(2).joinToString("")
            val header = if (settings.plainPhonePanel || initials.isBlank()) title else "$initials  $title"
            val lines = listOf(HudLine(header, HudLineStyle.NAME)) + block.lines
            var size = minOf(14f * textScale, video.height() * 0.042f)
            var layouts = lines.map { layout(styledText(it.text), width - padding * 4, size) }
            while (size > 8f && phoneHeight(layouts, padding) > available) {
                size -= 1f
                layouts = lines.map { layout(styledText(it.text), width - padding * 4, size) }
            }
            val rawHeight = phoneHeight(layouts, padding)
            val shrink = minOf(1f, available / rawHeight)
            val height = rawHeight * shrink
            val top = box.top.coerceIn(video.top, video.bottom - height)
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(shrink, shrink)
            background.color = if (settings.plainPhonePanel) 0xB5191B27.toInt() else 0xF1161930.toInt()
            canvas.drawRoundRect(0f, 0f, width, rawHeight, radius, radius, background)
            var y = padding
            for ((index, layout) in layouts.withIndex()) {
                val style = lines[index].style
                val offset = if (!settings.plainPhonePanel && style == HudLineStyle.OUTGOING) padding * 2 else padding
                if (!settings.plainPhonePanel && style != HudLineStyle.NAME) {
                    background.color = when (style) {
                        HudLineStyle.OUTGOING -> 0xFF294C56.toInt()
                        HudLineStyle.REPLY -> 0xFF3D395E.toInt()
                        else -> 0xFF2A2E49.toInt()
                    }
                    canvas.drawRoundRect(offset, y, width - padding, y + layout.height + padding, radius, radius, background)
                }
                drawText(canvas, layout, offset + padding / 2, y + padding / 2)
                y += layout.height + padding * 1.35f
            }
            canvas.restore()
        }

        private fun phoneHeight(rows: List<StaticLayout>, padding: Float): Float =
            padding * 2 + rows.sumOf { it.height }.toFloat() + rows.size * padding * 1.35f

        private fun layout(text: CharSequence, width: Float, size: Float, centered: Boolean = false): StaticLayout {
            val paint = TextPaint(bodyPaint).apply { textSize = size }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
                .setIncludePad(false).setAlignment(if (centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .build()
        }

        private fun drawText(canvas: Canvas, text: StaticLayout, x: Float, y: Float) {
            canvas.save()
            canvas.translate(x, y)
            text.draw(canvas)
            canvas.restore()
        }
    }
}

private fun screenBox(box: TextBox, area: Pixels) = RectF(
    area.x + (box.left * area.width).toFloat(), area.y + (box.top * area.height).toFloat(),
    area.x + (box.right * area.width).toFloat(), area.y + (box.bottom * area.height).toFloat(),
)

private val coloredText = Regex("""⁅(#[0-9a-fA-F]{6})\|([^⁆]*)⁆""")
private fun styledText(value: String): CharSequence {
    val builder = SpannableStringBuilder()
    var start = 0
    for (match in coloredText.findAll(value)) {
        builder.append(value.substring(start, match.range.first))
        val begin = builder.length
        builder.append(match.groupValues[2])
        validColor(match.groupValues[1])?.let { builder.setSpan(ForegroundColorSpan(it), begin, builder.length, 0) }
        start = match.range.last + 1
    }
    builder.append(value.substring(start))
    return builder
}

private fun validColor(value: String): Int? = runCatching { Color.parseColor(value) }.getOrNull()
