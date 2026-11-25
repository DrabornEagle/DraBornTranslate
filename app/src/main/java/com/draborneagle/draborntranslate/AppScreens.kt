// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import com.gametranslator.gametranslator_mobile.ConsoleStudioActivity

internal const val VISUAL_TRANSLATION = "Görsel Çeviri"

internal object Palette {
    val background = 0xFF090C16.toInt()
    val surface = 0xFF171D32.toInt()
    val border = 0xFF364261.toInt()
    val white = 0xFFF6F7FF.toInt()
    val quiet = 0xFFB4C0DA.toInt()
    val blue = 0xFF76E4FF.toInt()
    val purple = 0xFFCEABFF.toInt()
    val mint = 0xFF86F0D3.toInt()
    val peach = 0xFFFFB89B.toInt()
    val rose = 0xFFFFA7DD.toInt()
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
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, if (size >= 23) 750 else 640, false)
        setLineSpacing(dp(2).toFloat(), 1f)
        if (size >= 23) letterSpacing = -0.025f
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
    typeface = Typeface.create(Typeface.DEFAULT, 660, false)
    minimumHeight = dp(48)
    minHeight = dp(48)
    setPadding(dp(13), dp(10), dp(13), dp(10))
    val foreground = if (accent == null) Palette.white else Palette.background
    setTextColor(ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(Palette.quiet, foreground),
    ))
    stateListAnimator = null
    val active = if (accent == null) filled(Palette.surface, 15, Palette.border)
        else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, mix(accent, Palette.purple, 0.30f))).apply {
            cornerRadius = dp(16).toFloat()
        }
    background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), filled(0xFF15192A.toInt(), 15))
        addState(intArrayOf(), RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), active, null))
    }
    setOnClickListener { action() }
}

internal fun mix(front: Int, back: Int, ratio: Float): Int {
    val a = 1f - ratio
    return android.graphics.Color.rgb(
        (android.graphics.Color.red(front) * a + android.graphics.Color.red(back) * ratio).toInt(),
        (android.graphics.Color.green(front) * a + android.graphics.Color.green(back) * ratio).toInt(),
        (android.graphics.Color.blue(front) * a + android.graphics.Color.blue(back) * ratio).toInt(),
    )
}

internal fun Context.featureHeader(title: String, detail: String, icon: Int, accent: Int) = row().apply {
    setPadding(dp(16), dp(17), dp(16), dp(17))
    background = blend(mix(Palette.surface, accent, 0.19f), 0xFF1D1B3B.toInt(), 24)
    addView(stack().apply {
        addView(copy(title, 25, Palette.white, true).apply { isAccessibilityHeading = true })
        addBelow(copy(detail, 13), 5)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    addView(FrameLayout(context).apply {
        background = blend(mix(Palette.surface, accent, 0.32f), 0xFF15152A.toInt(), 19)
        addView(glyph(icon, accent), FrameLayout.LayoutParams(dp(31), dp(31), Gravity.CENTER))
    }, LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginStart = dp(12) })
}

internal fun Context.connectionHero(
    title: String,
    detail: String?,
    icon: Int,
    accent: Int,
    status: TextView,
) = stack().apply {
    background = ConnectionHeroBackground(accent, dp(27).toFloat())
    setPadding(dp(19), dp(18), dp(19), dp(18))
    addView(row().apply {
        addView(stack().apply {
            addView(copy(title, 29, Palette.white, true).apply { isAccessibilityHeading = true })
            if (detail != null) addBelow(copy(detail, 14, Palette.quiet), 6)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(FrameLayout(context).apply {
            background = blend(mix(Palette.surface, accent, 0.28f), 0xFF241D50.toInt(), 21)
            rotation = -8f
            addView(glyph(icon, accent), FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(68), dp(68)).apply { marginStart = dp(13) })
    })
    status.setPadding(dp(10), dp(6), dp(10), dp(6))
    addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(13) })
}

internal fun TextView.connectionBadge(value: String, color: Int) {
    if (text == value && currentTextColor == color && background != null) return
    text = value
    setTextColor(color)
    background = context.filled(0xCF10142B.toInt(), 13, mix(Palette.border, color, 0.35f))
}

private class ConnectionHeroBackground(private val accent: Int, private val radius: Float) : Drawable() {
    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val panel = RectF()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        panel.set(bounds)
        base.shader = LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
            0xFF252057.toInt(), 0xFF132739.toInt(), Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(panel.right * 0.92f, panel.top + panel.height() * 0.17f,
            (panel.width() * 0.65f).coerceAtLeast(1f), accent and 0x00FFFFFF or 0x68000000,
            accent and 0x00FFFFFF, Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
            mix(Palette.border, Palette.rose, 0.4f), mix(Palette.border, accent, 0.5f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(panel, radius, radius, base)
        canvas.drawRoundRect(panel, radius, radius, glow)
        canvas.drawRoundRect(panel, radius, radius, rim)
    }

    override fun setAlpha(alpha: Int) { base.alpha = alpha; glow.alpha = alpha; rim.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        base.colorFilter = colorFilter
        glow.colorFilter = colorFilter
        rim.colorFilter = colorFilter
    }
    @Deprecated("Drawable opacity")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal fun Context.actionRow(first: View, second: View) = row().apply {
    addView(first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
    addView(second, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
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
    if (!compact) addView(glyph(R.drawable.ic_disclosure, accent).apply { rotation = -90f },
        LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(7) })
}

internal fun Activity.pageRoot(view: View, accent: Int = Palette.blue) {
    val outer = FrameLayout(this).apply {
        background = AmbientBackground(accent)
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
    setPadding(dp(16), dp(8), dp(16), dp(8))
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
    }, accent)
    return content
}

internal fun connectionIntent(context: Context, source: Source): Intent =
    if (source == Source.PS5) Intent(context, ConsoleStudioActivity::class.java)
    else Intent(context, MainActivity::class.java).putExtra("connection", source.name)

private class AmbientBackground(private val accent: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var top: Shader? = null
    private var edge: Shader? = null

    override fun onBoundsChange(rect: Rect) {
        super.onBoundsChange(rect)
        val radius = (rect.width() * 0.85f).coerceAtLeast(1f)
        top = RadialGradient(rect.right * 0.89f, rect.top.toFloat(), radius,
            intArrayOf(mix(Palette.background, accent, 0.20f), Palette.background), null, Shader.TileMode.CLAMP)
        edge = RadialGradient(rect.left.toFloat(), rect.top + rect.height() * 0.68f, radius,
            intArrayOf(0x442A246F, 0x002A246F), null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        paint.shader = top
        canvas.drawRect(bounds, paint)
        paint.shader = edge
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable opacity")
    override fun getOpacity() = PixelFormat.OPAQUE
}

class HomeActivity : Activity() {
    private lateinit var connectionStatus: TextView
    private val activeSession: (SessionState.State) -> Unit = { state ->
        val message = when {
            state.active -> when (state.source) {
                Source.PS5 -> "PS5 · Canlı çeviri"
                Source.MOBILE -> "Telefon · Canlı çeviri"
                else -> "Portal · Canlı çeviri"
            }
            ImportedGamePack.hasGta(this) -> "GTA 6 Türkçe paketi hazır"
            else -> "Çeviri cihazda çalışır"
        }
        connectionStatus.connectionBadge(message, if (state.active) Palette.mint else Palette.blue)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (scroll, content) = scrollColumn()
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val screen = stack()
        screen.addBelow(brandBar(version))
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        screen.addView(bottomBar())
        pageRoot(screen, Palette.purple)
        connectionStatus = copy("", 12, Palette.blue, true)
        content.addBelow(connectionHero("Oyunu Türkçe\ntakip et", null, R.drawable.ic_controller, Palette.blue, connectionStatus))
        content.addBelow(copy("Bağlantı seç", 20, Palette.white, true).apply { isAccessibilityHeading = true }, 20)
        content.addBelow(navTile(
            "PS5 ve TV’ye bağlan", "Konsol yayını ve TV altyazısı",
            R.drawable.ic_controller, Palette.blue, 0xFF145786.toInt() to 0xFF152C50.toInt(),
        ) { startActivity(connectionIntent(this, Source.PS5)) }, 10)
        content.addBelow(navTile(
            "Portal’a bağlan", "PS App yayını ve Portal altyazı paneli",
            R.drawable.ic_portal_device, Palette.rose, 0xFF63356E.toInt() to 0xFF2E2459.toInt(),
        ) { startActivity(connectionIntent(this, Source.PORTAL)) }, 10)
        content.addBelow(actionRow(
            navTile("Telefonda oyna", null, R.drawable.ic_phone_screen, Palette.mint,
                0xFF1D4848.toInt() to 0xFF172737.toInt()) { startActivity(connectionIntent(this, Source.MOBILE)) },
            navTile("Oyun paketleri", null, R.drawable.ic_game_package, Palette.peach,
                0xFF51374B.toInt() to 0xFF232339.toInt()) { open(GamePacksActivity::class.java) },
        ), 11)
        if (savedInstanceState == null && ImportedGamePack.hasGta(this)) checkInstalledPack()
    }

    private fun brandBar(version: String) = row().apply {
        setPadding(dp(17), dp(7), dp(16), dp(4))
        addView(ImageView(context).apply { setImageResource(R.drawable.brand_mark) },
            LinearLayout.LayoutParams(dp(41), dp(41)).apply { marginEnd = dp(10) })
        addView(stack().apply {
            addView(copy("DraBornTranslate", 19, Palette.white, true))
            addBelow(copy("Sürüm $version", 12, Palette.quiet), 1)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(press("⋮") { open(UpdatesActivity::class.java) }.apply {
            textSize = 22f
            contentDescription = "Sürüm ve güncellemeler"
        }, LinearLayout.LayoutParams(dp(46), dp(48)))
    }

    private fun bottomBar() = row().apply {
        setPadding(dp(7), dp(5), dp(7), dp(5))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(5)
        }
        background = blend(0xFF232447.toInt(), 0xFF121B31.toInt(), 23)
        addView(bottomItem("Ana ekran", R.drawable.ic_controller, Palette.blue, true) {}, LinearLayout.LayoutParams(0, -2, 1f))
        addView(bottomItem("Yakalamalar", R.drawable.ic_capture_album, Palette.mint) { open(CaptureInboxActivity::class.java) },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(bottomItem(VISUAL_TRANSLATION, R.drawable.ic_focus_region, Palette.peach) { open(FocusActivity::class.java) },
            LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun bottomItem(label: String, icon: Int, accent: Int, selected: Boolean = false, tap: () -> Unit) =
        stack().apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(54)
            contentDescription = label
            isSelected = selected
            if (!selected) setOnClickListener { tap() }
            isFocusable = true
            if (selected) background = blend(0xFF264C68.toInt(), 0xFF2F355C.toInt(), 17)
            addView(glyph(icon, accent), LinearLayout.LayoutParams(dp(21), dp(21)))
            addView(copy(label, 11, if (selected) accent else Palette.white, true).apply { gravity = Gravity.CENTER })
        }

    override fun onStart() {
        super.onStart()
        SessionState.observe(activeSession)
    }

    override fun onResume() {
        super.onResume()
        activeSession(SessionState.current)
        CaptureInbox.watch(this)
        CaptureInbox.enqueue(this)
    }

    override fun onStop() {
        SessionState.remove(activeSession)
        super.onStop()
    }

    private fun open(target: Class<out Activity>) = startActivity(Intent(this, target))

    private fun checkInstalledPack() {
        val app = applicationContext
        Thread({
            val title = runCatching { ImportedGamePack.downloadGta(app, force = false) }.getOrNull()
            if (title != null) app.capturePreferences().edit().putString("gtaPackTitle", title).apply()
        }, "DraBornTranslate-pack-update").start()
    }
}
