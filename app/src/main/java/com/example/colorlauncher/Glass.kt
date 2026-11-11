package com.example.colorlauncher

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.Interpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max

/** Shared, pre-blurred (tiny) copy of the wallpaper + screen size. */
object Wall {
    @Volatile var bmp: Bitmap? = null
    @Volatile var sw = 0f
    @Volatile var sh = 0f
}

/** Soft damped spring: tiny overshoot, settles fast. */
class Spring : Interpolator {
    override fun getInterpolation(t: Float): Float =
        if (t >= 1f) 1f else (1.0 - exp(-6.0 * t) * cos(2.0 * PI * t)).toFloat()
}

/**
 * "Liquid glass" surface: blurred+saturated wallpaper slice, tint,
 * top sheen and a diagonal specular rim. Works on Android 11 (no RenderEffect).
 */
class Glass(private val v: View, radiusDp: Float, tint: Int = 0x24FFFFFF, private val wall: Boolean = true) : Drawable() {
    private val dn = v.resources.displayMetrics.density
    private val r = radiusDp * dn
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })
    }
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val tintP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.3f * dn
    }
    private val loc = IntArray(2)
    private val m = Matrix()
    private var shader: BitmapShader? = null
    private var shaderBmp: Bitmap? = null

    override fun onBoundsChange(b: Rect) {
        val h = rim.strokeWidth / 2
        rect.set(b); rect.inset(h, h)
        sheen.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(0x66FFFFFF, 0x14FFFFFF, 0x00FFFFFF, 0x2EFFFFFF),
            floatArrayOf(0f, .4f, .7f, 1f), Shader.TileMode.CLAMP
        )
        rim.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(0xCCFFFFFF.toInt(), 0x1AFFFFFF, 0x1AFFFFFF, 0x99FFFFFF.toInt()),
            floatArrayOf(0f, .35f, .65f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(c: Canvas) {
        val b = Wall.bmp
        if (wall && b != null && Wall.sw > 0f) {
            if (b !== shaderBmp) {
                shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderBmp = b
            }
            v.getLocationOnScreen(loc)
            val s = max(Wall.sw / b.width, Wall.sh / b.height)
            m.reset()
            m.postScale(s, s)
            m.postTranslate((Wall.sw - b.width * s) / 2f - loc[0], (Wall.sh - b.height * s) / 2f - loc[1])
            shader!!.setLocalMatrix(m)
            fill.shader = shader
            c.drawRoundRect(rect, r, r, fill)
        } else {
            c.drawRoundRect(rect, r, r, plain)
        }
        c.drawRoundRect(rect, r, r, tintP)
        c.drawRoundRect(rect, r, r, sheen)
        c.drawRoundRect(rect, r, r, rim)
    }

    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Glassy edge + gloss laid over each app icon. */
class Rim(private val dn: Float) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.1f * dn
    }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var rad = 0f

    override fun onBoundsChange(b: Rect) {
        rect.set(b); rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        rad = b.width() * 0.3f
        stroke.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(0xB3FFFFFF.toInt(), 0x14FFFFFF, 0x14FFFFFF, 0x66FFFFFF),
            floatArrayOf(0f, .4f, .6f, 1f), Shader.TileMode.CLAMP
        )
        gloss.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(0x38FFFFFF, 0x00FFFFFF), floatArrayOf(0f, .55f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(c: Canvas) {
        c.drawRoundRect(rect, rad, rad, gloss)
        c.drawRoundRect(rect, rad, rad, stroke)
    }

    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Tiny CPU box-blur used once per wallpaper change on a ~96px copy. */
fun blurSmall(src: Bitmap, radius: Int = 3, passes: Int = 3): Bitmap {
    val w = src.width; val h = src.height
    var a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
    var b = IntArray(w * h)
    fun pass(from: IntArray, to: IntArray, horizontal: Boolean) {
        for (y in 0 until h) for (x in 0 until w) {
            var rr = 0; var gg = 0; var bb = 0; var n = 0
            for (k in -radius..radius) {
                val xx = if (horizontal) (x + k).coerceIn(0, w - 1) else x
                val yy = if (horizontal) y else (y + k).coerceIn(0, h - 1)
                val p = from[yy * w + xx]
                rr += (p shr 16) and 255; gg += (p shr 8) and 255; bb += p and 255; n++
            }
            to[y * w + x] = (0xFF shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
        }
    }
    repeat(passes) {
        pass(a, b, true); pass(b, a, false)
    }
    return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
}
