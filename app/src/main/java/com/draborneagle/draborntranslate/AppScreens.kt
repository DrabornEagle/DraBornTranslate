// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal object Palette {
    val background = 0xFF0C1020.toInt()
    val surface = 0xFF181F35.toInt()
    val border = 0xFF303B56.toInt()
    val white = 0xFFF6F7FF.toInt()
    val quiet = 0xFFB4C0DA.toInt()
    val blue = 0xFF8EDFFF.toInt()
    val purple = 0xFFCBABFF.toInt()
    val mint = 0xFF91E9CB.toInt()
    val peach = 0xFFFFBD92.toInt()
}

internal fun Context.stack() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }

internal fun Context.filled(color: Int, radius: Int = 18, outline: Int? = null) = GradientDrawable().apply {
    cornerRadius = dp(radius).toFloat()
    setColor(color)
    outline?.let { setStroke(dp(1), it) }
}

internal fun Context.blend(top: Int, bottom: Int, radius: Int = 22) =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Palette.border)
    }

internal fun Context.copy(label: CharSequence, size: Int = 15, color: Int = Palette.quiet, bold: Boolean = false) =
    TextView(this).apply {
        text = label
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

internal fun Context.glyph(resource: Int, tint: Int) = ImageView(this).apply {
    setImageResource(resource)
    setColorFilter(tint)
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

internal fun LinearLayout.addBelow(view: View, gap: Int = 0) {
    addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(gap) })
}

internal fun Context.press(label: String, accent: Int? = null, action: () -> Unit) = Button(this).apply {
    isAllCaps = false
    text = label
    textSize = 14f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    minimumHeight = dp(48)
    minHeight = dp(48)
    setPadding(dp(12), dp(9), dp(12), dp(9))
    setTextColor(if (accent == null) Palette.white else Palette.background)
    stateListAnimator = null
    background = RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF),
        filled(accent ?: Palette.surface, 15, if (accent == null) Palette.border else null),
        null,
    )
    setOnClickListener { action() }
}

internal fun Context.navTile(
    title: String,
    description: String?,
    icon: Int,
    accent: Int,
    colors: Pair<Int, Int>? = null,
    open: () -> Unit,
) = row().apply {
    val compact = description == null
    setPadding(dp(14), dp(if (compact) 12 else 16), dp(13), dp(if (compact) 12 else 16))
    minimumHeight = dp(if (compact) 65 else 90)
    val face = colors?.let { blend(it.first, it.second) } ?: filled(Palette.surface, 18, Palette.border)
    background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), face, null)
    isFocusable = true
    contentDescription = title
    setOnClickListener { open() }
    val symbol = FrameLayout(context).apply {
        background = filled((accent and 0x00FFFFFF) or 0x24000000, 13)
        addView(glyph(icon, accent), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
    }
    addView(symbol, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(11) })
    addView(stack().apply {
        addView(copy(title, if (compact) 15 else 19, Palette.white, true))
        if (description != null) addBelow(copy(description, 13), 3)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    if (!compact) addView(glyph(R.drawable.ic_disclosure, accent), LinearLayout.LayoutParams(dp(18), dp(18)))
}

internal fun Activity.pageRoot(view: View) {
    val outer = FrameLayout(this).apply {
        setBackgroundColor(Palette.background)
        addView(view, FrameLayout.LayoutParams(-1, -1))
        setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val area = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                target.setPadding(area.left, area.top, area.right, area.bottom)
            }
            insets
        }
    }
    setContentView(outer)
    outer.requestApplyInsets()
}

internal fun Activity.titleBar(title: String, accent: Int = Palette.blue) = row().apply {
    setPadding(dp(14), dp(5), dp(16), dp(5))
    addView(press("‹") { finish() }.apply {
        textSize = 27f
        setTextColor(accent)
        contentDescription = "Geri"
    }, LinearLayout.LayoutParams(dp(48), dp(48)))
    addView(copy(title, 20, Palette.white, true).apply { isAccessibilityHeading = true },
        LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
}

internal fun Activity.scrollColumn(): Pair<ScrollView, LinearLayout> {
    val column = stack().apply { setPadding(dp(16), dp(12), dp(16), dp(20)) }
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
    }
    return scroll to column
}

internal fun Activity.contentPage(title: String, accent: Int = Palette.blue): LinearLayout {
    val (scroll, content) = scrollColumn()
    pageRoot(stack().apply {
        addView(titleBar(title, accent))
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    })
    return content
}

internal fun connectionIntent(context: Context, source: Source): Intent =
    if (source == Source.PS5) Intent(context, PsTvActivity::class.java)
    else Intent(context, MainActivity::class.java).putExtra("connection", source.name)

class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (scroll, content) = scrollColumn()
        pageRoot(scroll)
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val identity = row().apply {
            setPadding(dp(13), dp(15), dp(10), dp(15))
            background = blend(0xFF242047.toInt(), 0xFF15263C.toInt())
            addView(ImageView(context).apply {
                setImageResource(R.drawable.brand_mark)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(11) })
            addView(stack().apply {
                addView(copy("DraBornTranslate", 22, Palette.white, true).apply { isAccessibilityHeading = true })
                addBelow(copy("Sürüm $version", 13, Palette.blue), 2)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(press("⋮") { open(UpdatesActivity::class.java) }.apply {
                textSize = 22f
                contentDescription = "Sürüm ve güncellemeler"
            }, LinearLayout.LayoutParams(dp(45), dp(48)))
        }
        content.addBelow(identity)
        content.addBelow(copy("Oyun bağlantısı", 18, Palette.white, true).apply { isAccessibilityHeading = true }, 24)
        content.addBelow(navTile(
            "PS5 ve TV’ye bağlan", "Konsolu eşleştir ve altyazıyı telefonda veya TV’de oku.",
            R.drawable.ic_controller, Palette.blue, 0xFF1E3354.toInt() to 0xFF142139.toInt(),
        ) { startActivity(connectionIntent(this, Source.PS5)) }, 10)
        content.addBelow(navTile(
            "Portal’a bağlan", "PlayStation App ekran paylaşımıyla canlı altyazıyı aç.",
            R.drawable.ic_portal_device, Palette.purple, 0xFF392455.toInt() to 0xFF241D3C.toInt(),
        ) { startActivity(connectionIntent(this, Source.PORTAL)) }, 10)
        content.addBelow(copy("Diğer araçlar", 18, Palette.white, true).apply { isAccessibilityHeading = true }, 23)
        content.addBelow(toolRow(
            navTile("Yakalamalar", null, R.drawable.ic_capture_album, Palette.mint) { open(CaptureInboxActivity::class.java) },
            navTile("DraBorn Odak", null, R.drawable.ic_focus_region, Palette.peach) { open(FocusActivity::class.java) },
        ), 10)
        content.addBelow(toolRow(
            navTile("Telefonda oyna", null, R.drawable.ic_phone_screen, Palette.blue) { startActivity(connectionIntent(this, Source.MOBILE)) },
            navTile("Oyun paketleri", null, R.drawable.ic_game_package, Palette.purple) { open(GamePacksActivity::class.java) },
        ), 8)
        if (savedInstanceState == null && ImportedGamePack.hasGta(this)) checkInstalledPack()
    }

    override fun onResume() {
        super.onResume()
        CaptureInbox.watch(this)
        CaptureInbox.enqueue(this)
    }

    private fun open(target: Class<out Activity>) = startActivity(Intent(this, target))

    private fun checkInstalledPack() {
        val app = applicationContext
        Thread({
            val title = runCatching { ImportedGamePack.downloadGta(app, force = false) }.getOrNull()
            if (title != null) app.capturePreferences().edit().putString("gtaPackTitle", title).apply()
        }, "DraBornTranslate-pack-update").start()
    }

    private fun toolRow(first: View, second: View) = row().apply {
        addView(first, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(4) })
        addView(second, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(4) })
    }
}
