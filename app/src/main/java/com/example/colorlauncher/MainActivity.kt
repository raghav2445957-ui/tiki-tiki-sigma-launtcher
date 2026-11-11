package com.example.colorlauncher

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {

    private class App(val label: String, val cn: ComponentName, val icon: Drawable)

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var pager: ViewPager
    private lateinit var dots: DotsView
    private lateinit var dock: LinearLayout
    private lateinit var clockBox: LinearLayout
    private lateinit var clock: TextClock
    private var jellyOn = true
    private var glassOn = true
    private var labels = true
    private var iconDp = 56
    private var clockSp = 72f
    private var sig = ""

    private val cols = 4
    private val rows = 5
    private var firstRender = true
    private var wpId = -1
    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { buildUi() } catch (t: Throwable) { showError(t) }
    }

    /** If anything fails at startup, show the error instead of closing silently. */
    private fun showError(t: Throwable) {
        val tv = TextView(this).apply {
            text = "Launcher error:\n\n" + Log.getStackTraceString(t)
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(d(16), d(48), d(16), d(16))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(tv) })
    }

    private fun buildUi() {
        readPrefs(); sig = prefSig()
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
        preferHighRefreshRate()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x1F000000)
            setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, b.top, 0, b.bottom)
                ins
            }
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                Wall.sw = v.width.toFloat(); Wall.sh = v.height.toFloat()
            }
        }

        // Clock + date
        clock = TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, clockSp)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setShadowLayer(d(6).toFloat(), 0f, d(1).toFloat(), 0x55000000)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, MMM d"; format24Hour = "EEEE, MMM d"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xE6FFFFFF.toInt())
            setShadowLayer(d(4).toFloat(), 0f, d(1).toFloat(), 0x55000000)
            gravity = Gravity.CENTER
        }
        clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(clock, LinearLayout.LayoutParams(-1, -2))
            addView(date, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(clockBox, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = d(36); bottomMargin = d(12)
        })

        pager = ViewPager(this)
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))

        dots = DotsView(this)
        root.addView(dots, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = d(4); bottomMargin = d(4)
        })

        // Glass dock
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(d(8), d(10), d(8), d(10))
        }
        restyle()
        root.addView(dock, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(d(14), d(6), d(14), d(10))
        })

        // Smooth pager: staggered parallax, GPU layer only while moving
        pager.setPageTransformer(false) { page, pos ->
            val a = abs(pos).coerceAtMost(1f)
            page.alpha = 1f - 0.5f * a
            val g = page as ViewGroup
            val s = 1f - 0.14f * a
            for (r in 0 until g.childCount) {
                val row = g.getChildAt(r) as ViewGroup
                for (c in 0 until row.childCount) {
                    val cell = row.getChildAt(c)
                    val k = (c - (cols - 1) / 2f) / ((cols - 1) / 2f)
                    cell.translationX = pos * page.width * 0.22f * k
                    cell.scaleX = s; cell.scaleY = s
                }
            }
        }
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageScrolled(p: Int, off: Float, px: Int) {
                val pos = p + off
                dots.setPosition(pos)
                val t = pos.coerceIn(0f, 1f)
                clockBox.alpha = 1f - t
                clockBox.translationY = -t * d(28)
                clockBox.scaleX = 1f - 0.08f * t; clockBox.scaleY = clockBox.scaleX
                val n = pager.adapter?.count ?: 1
                if (n > 1) try {
                    WallpaperManager.getInstance(this@MainActivity)
                        .setWallpaperOffsets(pager.windowToken, pos / (n - 1), 0.5f)
                } catch (e: Exception) {}
            }
            override fun onPageScrollStateChanged(state: Int) {
                pager.setLayerType(
                    if (state == ViewPager.SCROLL_STATE_IDLE) View.LAYER_TYPE_NONE
                    else View.LAYER_TYPE_HARDWARE, null
                )
            }
        })

        setContentView(root)
        clockBox.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        load()

        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT < 33 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked", false)
        ) {
            prefs.edit().putBoolean("asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        loadWallpaper()
    }

    override fun onResume() {
        super.onResume()
        if (!::dock.isInitialized) return
        val now = prefSig()
        if (now != sig) {
            sig = now
            readPrefs()
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, clockSp)
            restyle()
            load()
        }
        val id = try {
            WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        } catch (e: Exception) { -1 }
        if (id != wpId) { wpId = id; loadWallpaper() }
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (e: Exception) { }
        io.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (::pager.isInitialized) pager.setCurrentItem(0, true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (::pager.isInitialized) pager.setCurrentItem(0, true) }

    private fun readPrefs() {
        jellyOn = Prefs.bool(this, "jelly", true)
        glassOn = Prefs.bool(this, "glass", true)
        labels = Prefs.bool(this, "labels", true)
        iconDp = Prefs.int(this, "icon", 56)
        clockSp = Prefs.int(this, "clock", 72).toFloat()
    }

    private fun prefSig() =
        listOf("jelly", "glass", "labels", "icon", "clock").joinToString("|") { Prefs.raw(this, it) }

    private fun restyle() {
        dock.background = if (glassOn) Glass(dock, 30f)
        else GradientDrawable().apply { cornerRadius = d(30).toFloat(); setColor(0x66000000) }
        dots.background = if (glassOn) Glass(dots, 11f)
        else GradientDrawable().apply { cornerRadius = d(11).toFloat(); setColor(0x55000000) }
    }

    private fun preferHighRefreshRate() {
        try {
            val dsp = windowManager.defaultDisplay ?: return
            val cur = dsp.mode
            val best = dsp.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        } catch (e: Throwable) { }
    }

    // ---------- wallpaper -> blurred glass source ----------

    private fun loadWallpaper() {
        io.execute {
            try {
                val dr = WallpaperManager.getInstance(this).drawable ?: return@execute
                val iw = dr.intrinsicWidth.coerceAtLeast(1)
                val ih = dr.intrinsicHeight.coerceAtLeast(1)
                val w = 96
                val h = (w.toFloat() * ih / iw).toInt().coerceAtLeast(8)
                val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                dr.setBounds(0, 0, w, h)
                dr.draw(Canvas(small))
                val blurred = blurSmall(small)
                runOnUiThread { Wall.bmp = blurred; dock.invalidate() }
            } catch (e: Throwable) { /* no access: falls back to translucent glass */ }
        }
    }

    // ---------- apps ----------

    private fun load() {
        io.execute {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val all = pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    App(
                        it.loadLabel(pm).toString(),
                        ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                        it.loadIcon(pm)
                    )
                }
                .sortedBy { it.label.lowercase() }

            val dockPkgs = listOf(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
                Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            ).mapNotNull {
                pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            }.filter { it != "android" }.distinct()

            val dockApps = dockPkgs.mapNotNull { p -> all.firstOrNull { it.cn.packageName == p } }.take(4)
            val rest = all.filter { a -> dockApps.none { it.cn.packageName == a.cn.packageName } }
            runOnUiThread { render(rest, dockApps) }
        }
    }

    private fun render(apps: List<App>, dockApps: List<App>) {
        val pages = apps.chunked(cols * rows).ifEmpty { listOf(emptyList()) }
        val keep = pager.currentItem
        pager.adapter = object : PagerAdapter() {
            override fun getCount() = pages.size
            override fun isViewFromObject(v: View, o: Any) = v === o
            override fun instantiateItem(c: ViewGroup, pos: Int): Any =
                buildPage(pages[pos]).also { c.addView(it) }
            override fun destroyItem(c: ViewGroup, pos: Int, o: Any) = c.removeView(o as View)
        }
        pager.setCurrentItem(keep.coerceAtMost(pages.size - 1), false)
        dots.setCount(pages.size)
        dock.removeAllViews()
        dockApps.forEach { dock.addView(cell(it, false), LinearLayout.LayoutParams(0, -2, 1f)) }

        if (firstRender) {
            firstRender = false
            pager.alpha = 0f
            dock.alpha = 0f
            dock.translationY = d(90).toFloat()
            pager.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    pager.viewTreeObserver.removeOnPreDrawListener(this)
                    enter()
                    return true
                }
            })
        }
    }

    /** Icons spring in one after another; dock rises like a droplet. */
    private fun enter() {
        pager.alpha = 1f
        val page = pager.getChildAt(0) as? ViewGroup
        if (page != null) {
            var i = 0
            for (r in 0 until page.childCount) {
                val row = page.getChildAt(r) as ViewGroup
                for (c in 0 until row.childCount) {
                    val cell = row.getChildAt(c)
                    cell.alpha = 0f; cell.scaleX = 0.5f; cell.scaleY = 0.5f
                    cell.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setStartDelay(i * 14L).setDuration(560).setInterpolator(Spring()).start()
                    i++
                }
            }
        }
        dock.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(650)
            .setInterpolator(Spring()).withEndAction { dock.invalidate() }.start()
    }

    // ---------- views ----------

    private fun buildPage(apps: List<App>): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(10), 0, d(10), 0)
        }
        for (r in 0 until rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val app = apps.getOrNull(r * cols + c)
                row.addView(
                    if (app != null) cell(app, labels) else View(this),
                    LinearLayout.LayoutParams(0, -1, 1f)
                )
            }
            page.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return page
    }

    private fun cell(app: App, showLabel: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val size = d(iconDp)
        val icon = ImageView(this).apply {
            setImageDrawable(app.icon)
            if (glassOn) foreground = Rim(resources.displayMetrics.density)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) =
                    o.setRoundRect(0, 0, v.width, v.height, v.width * 0.3f)
            }
        }
        box.addView(icon, LinearLayout.LayoutParams(size, size))
        if (showLabel) {
            box.addView(TextView(this).apply {
                text = app.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setShadowLayer(d(3).toFloat(), 0f, d(1).toFloat(), 0x99000000.toInt())
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(5); marginStart = d(2); marginEnd = d(2) })
        }
        // Press feedback: jelly squash & wobble (or a simple spring if turned off)
        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (jellyOn) Motion.down(v) else v.animate()
                    .scaleX(0.86f).scaleY(0.86f).setStartDelay(0).setDuration(110)
                    .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (jellyOn) Motion.up(v) else v.animate()
                    .scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(420)
                    .setInterpolator(Spring()).start()
            }
            false
        }
        box.setOnClickListener {
            val opts = ActivityOptions.makeScaleUpAnimation(icon, 0, 0, icon.width, icon.height)
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                opts.toBundle()
            )
        }
        box.setOnLongClickListener { showMenu(icon, app); true }
        return box
    }

    /** Glass context card that pops with a spring. */
    private fun showMenu(anchor: View, app: App) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val w = d(210)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, d(8), 0, d(8))
        }
        card.background = Glass(card, 22f, 0x38000000)
        val pw = PopupWindow(card, w, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = 0f

        fun row(label: String, danger: Boolean, act: () -> Unit) {
            card.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(if (danger) 0xFFFF7A7A.toInt() else Color.WHITE)
                setPadding(d(20), d(13), d(20), d(13))
                setOnClickListener { pw.dismiss(); act() }
            })
        }
        val uri = Uri.parse("package:${app.cn.packageName}")
        row("App info", false) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)) }
        row("Uninstall", true) { startActivity(Intent(Intent.ACTION_DELETE, uri)) }

        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val screenW = resources.displayMetrics.widthPixels
        val x = (loc[0] + anchor.width / 2 - w / 2).coerceIn(d(12), screenW - w - d(12))
        val above = loc[1] > (if (Wall.sh > 0) Wall.sh else resources.displayMetrics.heightPixels.toFloat()) * 0.55f
        val y = if (above) loc[1] - card.measuredHeight - d(8) else loc[1] + anchor.height + d(8)

        card.pivotX = w / 2f
        card.pivotY = if (above) card.measuredHeight.toFloat() else 0f
        card.scaleX = 0.6f; card.scaleY = 0.6f; card.alpha = 0f
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420)
            .setInterpolator(Spring()).withEndAction { card.invalidate() }.start()
    }

    // ---------- page indicator ----------

    private class DotsView(c: Context) : View(c) {
        private var count = 1
        private var pos = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { visibility = GONE }
        fun setCount(n: Int) {
            count = n
            visibility = if (n < 2) GONE else VISIBLE
            requestLayout(); invalidate()
        }
        override fun onMeasure(wm: Int, hm: Int) {
            val dn = resources.displayMetrics.density
            setMeasuredDimension((((count - 1) * 14 + 44) * dn).toInt(), (22 * dn).toInt())
        }
        fun setPosition(x: Float) { pos = x; invalidate() }
        override fun onDraw(c: Canvas) {
            if (count < 2) return
            val dn = resources.displayMetrics.density
            val r = 3 * dn; val gap = 14 * dn
            val startX = width / 2f - (count - 1) * gap / 2f
            val cy = height / 2f
            p.color = 0x66FFFFFF
            for (i in 0 until count) c.drawCircle(startX + i * gap, cy, r, p)
            p.color = Color.WHITE
            val x = startX + pos * gap
            c.drawRoundRect(x - r * 2.2f, cy - r, x + r * 2.2f, cy + r, r, r, p)
        }
    }
}
