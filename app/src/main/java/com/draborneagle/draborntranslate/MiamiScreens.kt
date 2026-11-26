// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

internal object Miami {
    val night = 0xFF090C19.toInt()
    val surface = 0xFF151A30.toInt()
    val edge = 0xFF424766.toInt()
    val coral = 0xFFFFAD98.toInt()
    val pink = 0xFFFFA9D1.toInt()
    val aqua = 0xFF8CF7E0.toInt()
    val lavender = 0xFFD2BDFF.toInt()
}

internal fun Activity.miamiPage(title: String, accent: Int): LinearLayout {
    val (scroll, content) = scrollColumn()
    pageRoot(stack().apply {
        addView(titleBar(title, accent))
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }, accent, Miami.night)
    return content
}

internal fun Context.miamiHero(title: String, detail: String, accent: Int) = FrameLayout(this).apply {
    background = filled(Miami.surface, 26, Miami.edge)
    clipToOutline = true
    addView(ImageView(context).apply {
        setImageResource(R.drawable.miami_waterfront)
        scaleType = ImageView.ScaleType.CENTER_CROP
        foreground = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xF4090C19.toInt(), 0xBA101329.toInt(), 0x20101329))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, FrameLayout.LayoutParams(-1, dp(152), Gravity.TOP))
    addView(stack().apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(152)
        setPadding(dp(19), dp(17), dp(52), dp(17))
        addView(View(context).apply { background = filled(accent, 3) },
            LinearLayout.LayoutParams(dp(30), dp(3)))
        addBelow(copy(title, 30, Palette.white, true).apply {
            typeface = Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 800, false)
            isAccessibilityHeading = true
        }, 9)
        addBelow(copy(detail, 14, Palette.white), 4)
    }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
}

internal fun Context.miamiPanel(accent: Int = Miami.lavender) = stack().apply {
    background = filled(mix(Miami.surface, accent, 0.06f), 22, mix(Miami.edge, accent, 0.15f))
    setPadding(dp(15), dp(14), dp(15), dp(14))
}

internal fun Context.miamiSection(title: String, detail: String, accent: Int = Miami.lavender) =
    miamiPanel(accent).apply {
        addView(copy(title, 15, accent, true).apply { isAccessibilityHeading = true })
        if (detail.isNotBlank()) addBelow(copy(detail, 16, Palette.white), 6)
    }

internal fun Button.miamiChoice(selected: Boolean, accent: Int) {
    isSelected = selected
    val face = if (selected) GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(accent, mix(accent, Miami.lavender, 0.37f))).apply {
        cornerRadius = context.dp(15).toFloat()
    } else context.filled(Miami.surface, 15, Miami.edge)
    background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), face, null)
    setTextColor(if (selected) Miami.night else Palette.white)
}

internal fun Context.openBrowserLink(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "Bağlantıyı açacak tarayıcı bulunamadı.", Toast.LENGTH_SHORT).show()
    }
}
