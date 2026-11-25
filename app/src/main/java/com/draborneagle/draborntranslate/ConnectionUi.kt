// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal val connectionBackground = 0xFF0B0B16.toInt()
private val cardColor = 0xFF171624.toInt()
private val cardEdge = 0xFF343045.toInt()

internal fun Context.connectionSurface(accent: Int) = stack().apply {
    background = filled(mix(cardColor, accent, 0.045f), 23, mix(cardEdge, accent, 0.16f))
    setPadding(dp(15), dp(14), dp(15), dp(15))
}

private fun Context.coverImage() = ImageView(this).apply {
    setImageResource(R.drawable.connection_cover)
    scaleType = ImageView.ScaleType.CENTER_CROP
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

internal fun Context.homeCover(status: TextView) = FrameLayout(this).apply {
    background = filled(connectionBackground, 24)
    clipToOutline = true
    addView(coverImage().apply {
        foreground = GradientDrawable(
            GradientDrawable.Orientation.TR_BL,
            intArrayOf(0x00140E28, 0x4D13132B, 0xFA0C0C19.toInt()),
        )
    }, FrameLayout.LayoutParams(-1, dp(224)))
    addView(stack().apply {
        minimumHeight = dp(224)
        gravity = Gravity.BOTTOM
        setPadding(dp(18), dp(32), dp(18), dp(18))
        addView(copy("Oyunu Türkçe oyna", 34, Palette.white, true).apply {
            isAccessibilityHeading = true
            setShadowLayer(dp(6).toFloat(), 0f, dp(2).toFloat(), 0xBF080814.toInt())
        })
        status.setPadding(dp(10), dp(6), dp(10), dp(6))
        addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
    }, FrameLayout.LayoutParams(-1, -2))
}

internal fun Context.connectionDestination(
    title: String,
    detail: String,
    icon: Int,
    accent: Int,
    open: () -> Unit,
) = stack().apply {
    val face = filled(mix(cardColor, accent, 0.13f), 20, mix(cardEdge, accent, 0.33f))
    background = RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), face, null)
    setPadding(dp(13), dp(13), dp(13), dp(14))
    contentDescription = title
    isFocusable = true
    setOnClickListener { open() }
    addView(row().apply {
        addView(glyph(icon, accent).also {
            it.background = filled(mix(cardColor, accent, 0.22f), 12)
            it.setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(37), dp(37)))
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(glyph(R.drawable.ic_disclosure, accent).apply { rotation = -90f },
            LinearLayout.LayoutParams(dp(19), dp(19)))
    })
    addBelow(copy(title, 16, Palette.white, true), 10)
    addBelow(copy(detail, 12), 4)
}

internal fun Context.portalIllustration() = FrameLayout(this).apply {
    background = filled(0xFF211B3A.toInt(), 15)
    val device = row().apply {
        background = filled(0xFF0C0F1C.toInt(), 28, 0xFF706194.toInt())
        setPadding(dp(7), dp(7), dp(7), dp(7))
        addView(PortalGrip(context, dpad = true), LinearLayout.LayoutParams(dp(36), -1))
        addView(coverImage().apply {
            background = filled(0xFF0B0C15.toInt(), 8)
            clipToOutline = true
        }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(PortalGrip(context, dpad = false), LinearLayout.LayoutParams(dp(36), -1))
    }
    addView(device, FrameLayout.LayoutParams(-1, dp(102), Gravity.CENTER).apply {
        marginStart = dp(14)
        marginEnd = dp(14)
    })
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
}

private class PortalGrip(context: Context, private val dpad: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val housing = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width / 2f
        val top = height * 0.32f
        paint.color = 0xFF242336.toInt()
        housing.set(x * 0.28f, 0f, x * 1.72f, height.toFloat())
        canvas.drawRoundRect(housing, x * 0.72f, x * 0.72f, paint)
        paint.color = 0xFFC7A8F8.toInt()
        val step = x * 0.24f
        if (dpad) {
            canvas.drawRect(x - step * 0.33f, top - step, x + step * 0.33f, top + step, paint)
            canvas.drawRect(x - step, top - step * 0.33f, x + step, top + step * 0.33f, paint)
        } else {
            val dot = step * 0.36f
            canvas.drawCircle(x - step, top, dot, paint)
            canvas.drawCircle(x + step, top, dot, paint)
            canvas.drawCircle(x, top - step, dot, paint)
            canvas.drawCircle(x, top + step, dot, paint)
        }
        paint.color = 0xFF7D7199.toInt()
        canvas.drawCircle(x, height * 0.68f, x * 0.30f, paint)
        paint.color = 0xFF171627.toInt()
        canvas.drawCircle(x, height * 0.68f, x * 0.23f, paint)
    }
}

internal fun Context.connectionOption(
    text: String,
    icon: Int,
    accent: Int,
    selected: Boolean? = null,
    onClick: () -> Unit,
): Button = press(text, action = onClick).apply {
    minimumHeight = dp(49)
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
    typeface = Typeface.create(Typeface.DEFAULT, 660, false)
    isSelected = selected == true
    val tone = mix(cardColor, accent, if (selected == false) 0.045f else 0.17f)
    val edge = mix(cardEdge, accent, if (selected == false) 0.12f else 0.59f)
    background = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), filled(cardColor, 15, cardEdge))
        addState(intArrayOf(android.R.attr.state_focused), filled(tone, 15, accent))
        addState(intArrayOf(), RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), filled(tone, 15, edge), null))
    }
    setTextColor(ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(Palette.quiet, if (selected == false) Palette.white else accent),
    ))
    setPadding(dp(13), dp(9), dp(12), dp(9))
    compoundDrawablePadding = dp(10)
    val trailing = when (selected) {
        true -> optionIcon(R.drawable.ic_portal_check, accent)
        false -> null
        null -> optionIcon(R.drawable.ic_disclosure, accent)
    }
    setCompoundDrawablesRelative(optionIcon(icon, accent), null, trailing, null)
}

private fun Context.optionIcon(id: Int, tint: Int): Drawable? = getDrawable(id)?.mutate()?.apply {
    setTint(tint)
    setBounds(0, 0, dp(21), dp(21))
}
