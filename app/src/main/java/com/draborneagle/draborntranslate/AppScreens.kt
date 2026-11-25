// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
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
        connectionStatus.text = when {
            state.active -> when (state.source) {
                Source.PS5 -> "PS5 · Canlı çeviri"
                Source.MOBILE -> "Telefon · Canlı çeviri"
                else -> "Portal · Canlı çeviri"
            }
            ImportedGamePack.hasGta(this) -> "GTA 6 Türkçe paketi hazır"
            else -> "Çeviri cihazda çalışır"
        }
        connectionStatus.setTextColor(if (state.active) Palette.mint else Palette.blue)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (scroll, content) = scrollColumn()
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val screen = stack()
        screen.addBelow(brandBar(version))
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        screen.addBelow(bottomBar())
        pageRoot(screen, Palette.purple)
        val hero = stack().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF312767.toInt(), 0xFF203456.toInt(), 0xFF15374B.toInt())).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), 0xFF52648B.toInt())
            }
            setPadding(dp(17), dp(15), dp(17), dp(15))
            val devices = row().apply {
                addView(deviceMark(R.drawable.ic_controller, Palette.blue))
                val wire = View(context).apply { background = blend(Palette.blue, Palette.purple, 3) }
                addView(wire, LinearLayout.LayoutParams(0, dp(2), 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
                addView(deviceMark(R.drawable.ic_portal_device, Palette.purple))
            }
            addView(devices)
            addBelow(copy("Oyunu Türkçe takip et", 28, Palette.white, true).apply {
                isAccessibilityHeading = true
            }, 12)
            connectionStatus = copy("", 13, Palette.blue, true)
            addBelow(connectionStatus, 4)
        }
        content.addBelow(hero)
        content.addBelow(copy("Bağlantı seç", 18, Palette.white, true).apply { isAccessibilityHeading = true }, 16)
        content.addBelow(navTile(
            "PS5 ve TV’ye bağlan", "Canlı altyazı ve TV kurulumu",
            R.drawable.ic_controller, Palette.blue, 0xFF163C5B.toInt() to 0xFF17223E.toInt(),
        ) { startActivity(connectionIntent(this, Source.PS5)) }, 8)
        content.addBelow(navTile(
            "Portal’a bağlan", "Paylaşımlı ekran ve Portal paneli",
            R.drawable.ic_portal_device, Palette.purple, 0xFF462B66.toInt() to 0xFF22203F.toInt(),
        ) { startActivity(connectionIntent(this, Source.PORTAL)) }, 8)
        content.addBelow(actionRow(
            navTile("Telefonda oyna", null, R.drawable.ic_phone_screen, Palette.blue) { startActivity(connectionIntent(this, Source.MOBILE)) },
            navTile("Oyun paketleri", null, R.drawable.ic_game_package, Palette.purple) { open(GamePacksActivity::class.java) },
        ), 8)
        if (savedInstanceState == null && ImportedGamePack.hasGta(this)) checkInstalledPack()
    }

    private fun deviceMark(icon: Int, accent: Int) = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(66), dp(49))
        background = blend(mix(Palette.surface, accent, 0.25f), 0xFF202342.toInt(), 15)
        addView(glyph(icon, accent), FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
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
        setPadding(dp(9), dp(6), dp(9), dp(6))
        background = filled(0xFF161C30.toInt(), 20, Palette.border)
        addView(bottomItem("Ana ekran", R.drawable.ic_controller, Palette.blue, true) {}, LinearLayout.LayoutParams(0, -2, 1f))
        addView(bottomItem("Yakalamalar", R.drawable.ic_capture_album, Palette.mint) { open(CaptureInboxActivity::class.java) },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(bottomItem("DraBorn Odak", R.drawable.ic_focus_region, Palette.peach) { open(FocusActivity::class.java) },
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
            if (selected) background = filled(0xFF293858.toInt(), 16)
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
