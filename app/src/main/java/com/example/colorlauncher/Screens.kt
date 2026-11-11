package com.example.colorlauncher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

// ---------------------------------------------------------------- shared

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("flow", Context.MODE_PRIVATE)
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun put(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun put(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun raw(c: Context, k: String) = sp(c).all[k]?.toString() ?: "-"
}

/** "Jelly touch": squash on press, wobbly settle on release. */
object Motion {
    fun down(v: View) {
        (v.tag as? ValueAnimator)?.cancel()
        v.animate().cancel()
        v.animate().scaleX(0.93f).scaleY(0.84f).setStartDelay(0).setDuration(110)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun up(v: View) {
        (v.tag as? ValueAnimator)?.cancel()
        v.animate().cancel()
        val sx = v.scaleX; val sy = v.scaleY
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 650
        a.addUpdateListener {
            val t = it.animatedFraction
            val px = (1.0 - exp(-5.5 * t) * cos(2.0 * PI * t)).toFloat()
            val py = (1.0 - exp(-4.5 * t) * cos(2.0 * PI * 1.45 * t)).toFloat()
            if (t >= 1f) { v.scaleX = 1f; v.scaleY = 1f }
            else { v.scaleX = sx + (1f - sx) * px; v.scaleY = sy + (1f - sy) * py }
        }
        v.tag = a
        a.start()
    }

    fun attach(v: View) {
        v.setOnTouchListener { x, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> down(x)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> up(x)
            }
            false
        }
    }
}

/** Slowly drifting colour glow behind the glass cards. */
class Blobs(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 18000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        p.shader = RadialGradient(cx, cy, r, col, col and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        blob(c, w * (0.2f + 0.12f * sin(t)), h * (0.20f + 0.06f * cos(t)), w * 0.85f, 0x66FF7A45)
        blob(c, w * (0.85f + 0.08f * cos(t)), h * (0.50f + 0.08f * sin(t)), w * 0.95f, 0x558A4DFF)
        blob(c, w * (0.30f + 0.10f * sin(t + 2f)), h * (0.88f + 0.04f * cos(t)), w * 0.9f, 0x4400C2FF)
    }
}

private class Logo(c: Context) : View(c) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        bg.shader = LinearGradient(0f, 0f, w, w, 0xFFFF8A4C.toInt(), 0xFFB23CFF.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, w, w, w * 0.3f, w * 0.3f, bg)
        wave.strokeWidth = w * 0.075f
        path.reset()
        path.moveTo(w * .24f, w * .58f)
        path.cubicTo(w * .34f, w * .30f, w * .46f, w * .30f, w * .52f, w * .50f)
        path.cubicTo(w * .58f, w * .70f, w * .68f, w * .70f, w * .78f, w * .40f)
        c.drawPath(path, wave)
    }
}

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun lp(w: Int, h: Int, f: LinearLayout.LayoutParams.() -> Unit = {}) =
    LinearLayout.LayoutParams(w, h).apply(f)

private fun Context.tv(t: String, sp: Float, col: Int, bold: Boolean = false) = TextView(this).apply {
    text = t; textSize = sp; setTextColor(col)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

private fun Context.card() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(14), dp(18), dp(14))
    background = Glass(this, 22f, 0x14FFFFFF, false)
}

private fun Context.divider() = View(this).apply { setBackgroundColor(0x1AFFFFFF) }

private fun Context.section(t: String) = tv(t, 12f, 0x99FFFFFF.toInt(), true).apply {
    letterSpacing = 0.08f
    setPadding(dp(6), dp(18), 0, dp(8))
}

private fun Context.button(text: String, primary: Boolean, click: () -> Unit): TextView =
    TextView(this).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        if (primary) {
            setTextColor(0xFF14161C.toInt())
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(Color.WHITE) }
        } else {
            setTextColor(Color.WHITE)
            background = Glass(this, 28f, 0x14FFFFFF, false)
        }
        Motion.attach(this)
        setOnClickListener { click() }
    }

private fun stagger(g: ViewGroup) {
    val dn = g.resources.displayMetrics.density
    for (i in 0 until g.childCount) {
        val v = g.getChildAt(i)
        v.alpha = 0f; v.translationY = 36f * dn
        v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(650)
            .setInterpolator(Spring()).start()
    }
}

/** Edge-to-edge dark screen with drifting glow and a scrolling column. */
private fun Activity.screen(content: LinearLayout): View {
    window.setDecorFitsSystemWindows(false)
    window.isNavigationBarContrastEnforced = false
    window.insetsController?.setSystemBarsAppearance(
        0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    )
    val root = FrameLayout(this).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0E1117.toInt(), 0xFF1B2030.toInt())
        )
    }
    root.addView(Blobs(this), FrameLayout.LayoutParams(-1, -1))
    val sv = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    content.setPadding(dp(22), dp(22), dp(22), dp(28))
    sv.addView(content, FrameLayout.LayoutParams(-1, -2))
    root.addView(sv, FrameLayout.LayoutParams(-1, -1))
    root.setOnApplyWindowInsetsListener { _, ins ->
        val b = ins.getInsets(WindowInsets.Type.systemBars())
        sv.setPadding(0, b.top, 0, b.bottom)
        ins
    }
    return root
}

// ---------------------------------------------------------------- welcome

class SetupActivity : Activity() {
    private lateinit var dot: View
    private lateinit var status: TextView
    private lateinit var primary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val white = Color.WHITE
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val logo = Logo(this)
        Motion.attach(logo)
        col.addView(logo, lp(dp(92), dp(92)) { topMargin = dp(28) })
        col.addView(tv("Flow Launcher", 34f, white, true).apply { gravity = Gravity.CENTER },
            lp(-1, -2) { topMargin = dp(18) })
        col.addView(tv("Fluid glass. Jelly motion. Light as air.", 15f, 0xB3FFFFFF.toInt())
            .apply { gravity = Gravity.CENTER }, lp(-1, -2) { topMargin = dp(6); bottomMargin = dp(26) })

        val sc = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dot = View(this)
        status = tv("", 15f, white)
        sc.addView(dot, lp(dp(12), dp(12)) { marginEnd = dp(12) })
        sc.addView(status, lp(0, -2) { weight = 1f })
        col.addView(sc, lp(-1, -2))

        val fc = card()
        listOf(
            "Living Flow glass dock and menus",
            "Jelly touch on every icon",
            "Floating glass page pill",
            "Resizable clock and icons",
            "No background services, no ads"
        ).forEach { fc.addView(tv("•   $it", 14.5f, 0xE6FFFFFF.toInt()), lp(-1, -2) { topMargin = dp(5); bottomMargin = dp(5) }) }
        col.addView(fc, lp(-1, -2) { topMargin = dp(12) })

        primary = button("Set as default launcher", true) { onPrimary() }
        col.addView(primary, lp(-1, dp(56)) { topMargin = dp(22) })
        col.addView(button("Launcher settings", false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, lp(-1, dp(56)) { topMargin = dp(12) })
        col.addView(tv("Didn't work? Open system settings", 13f, 0x99FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        }, lp(-1, -2) { topMargin = dp(10) })
        col.addView(tv("Works on Android 11 and up", 12f, 0x66FFFFFF).apply { gravity = Gravity.CENTER },
            lp(-1, -2) { topMargin = dp(6) })

        setContentView(screen(col))
        stagger(col)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun isDefault(): Boolean {
        val ri = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )
        return ri?.activityInfo?.packageName == packageName
    }

    private fun refresh() {
        val def = isDefault()
        status.text = if (def) "Flow is your default launcher" else "Not your default launcher yet"
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (def) 0xFF3DDC84.toInt() else 0xFFFFB020.toInt())
        }
        primary.text = if (def) "Open home screen" else "Set as default launcher"
    }

    private fun onPrimary() {
        if (isDefault()) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        try {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 7)
                return
            }
        } catch (e: Exception) { }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }
}

// ---------------------------------------------------------------- settings

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val back = tv("‹", 36f, Color.WHITE).apply {
            setPadding(0, 0, dp(16), dp(4))
            setOnClickListener { finish() }
        }
        Motion.attach(back)
        header.addView(back)
        header.addView(tv("Settings", 30f, Color.WHITE, true))
        col.addView(header, lp(-1, -2) { bottomMargin = dp(6) })

        col.addView(section("LOOK & FEEL"))
        col.addView(card().apply {
            addView(switchRow("Jelly touch", "Squash and wobble when you tap", "jelly", true))
            addView(divider(), lp(-1, 1))
            addView(switchRow("Glass effects", "Frosted dock, menus and icon edges", "glass", true))
            addView(divider(), lp(-1, 1))
            addView(switchRow("App names", "Show labels under icons", "labels", true))
            addView(divider(), lp(-1, 1))
            addView(seekRow("Icon size", "icon", 44, 64, 56, "dp"))
            addView(divider(), lp(-1, 1))
            addView(seekRow("Clock size", "clock", 48, 96, 72, "sp"))
        })

        col.addView(section("SYSTEM"))
        col.addView(card().apply {
            addView(actionRow("Default launcher", "Choose Flow in system settings") {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            })
            addView(divider(), lp(-1, 1))
            addView(actionRow("Wallpaper", "Change your wallpaper") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
            })
        })

        col.addView(section("ABOUT"))
        col.addView(card().apply {
            addView(tv("Flow Launcher 1.0", 16f, Color.WHITE))
            addView(tv("Long-press the clock on the home screen to open settings.", 12.5f, 0x99FFFFFF.toInt()),
                lp(-1, -2) { topMargin = dp(4) })
        })

        setContentView(screen(col))
        stagger(col)
    }

    private fun accent() = ColorStateList.valueOf(0xFFFF8A4C.toInt())

    private fun switchRow(title: String, sub: String, key: String, def: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val txt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 16f, Color.WHITE))
            addView(tv(sub, 12.5f, 0x99FFFFFF.toInt()))
        }
        val sw = Switch(this).apply {
            isChecked = Prefs.bool(this@SettingsActivity, key, def)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFFFF8A4C.toInt(), 0xFFB0B4BD.toInt())
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x88FF8A4C.toInt(), 0x55FFFFFF)
            )
            setOnCheckedChangeListener { _, v -> Prefs.put(this@SettingsActivity, key, v) }
        }
        row.addView(txt, lp(0, -2) { weight = 1f })
        row.addView(sw)
        return row
    }

    private fun seekRow(title: String, key: String, min: Int, max: Int, def: Int, unit: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        val cur = Prefs.int(this, key, def).coerceIn(min, max)
        val value = tv("$cur $unit", 14f, 0xB3FFFFFF.toInt())
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tv(title, 16f, Color.WHITE), lp(0, -2) { weight = 1f })
            addView(value)
        }
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = cur - min
            progressTintList = accent(); thumbTintList = accent()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                    value.text = "${min + p} $unit"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    Prefs.put(this@SettingsActivity, key, min + (s?.progress ?: 0))
                }
            })
        }
        row.addView(top)
        row.addView(sb, lp(-1, -2) { topMargin = dp(4) })
        return row
    }

    private fun actionRow(title: String, sub: String, click: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { click() }
        }
        val txt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 16f, Color.WHITE))
            addView(tv(sub, 12.5f, 0x99FFFFFF.toInt()))
        }
        row.addView(txt, lp(0, -2) { weight = 1f })
        row.addView(tv("›", 26f, 0x99FFFFFF.toInt()))
        Motion.attach(row)
        return row
    }
}
