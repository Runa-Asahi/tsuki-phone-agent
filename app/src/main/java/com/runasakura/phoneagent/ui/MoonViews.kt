package com.runasakura.phoneagent.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Background sky: navy → silver-mist gradient, a few fixed stars, a soft crescent halo and slow moonlight motes
 * that drift like loose hair. Cheap: ≤18 circles per frame, ~30 fps, stops when detached/hidden or when the system
 * asks for reduced motion (then a single static frame is drawn).
 */
class MoonSkyView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var colors: TsukiTheme.Colors = TsukiTheme.current(ctx); set(v) { field = v; shader = null; invalidate() }
    var animateMotes = !TsukiTheme.reduceMotion(ctx)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Shader? = null
    private val rnd = java.util.Random(7)
    private val stars = List(26) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.55f, 0.6f + rnd.nextFloat() * 1.2f, rnd.nextFloat()) }
    private val motes = List(18) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 1.2f + rnd.nextFloat() * 2.2f, rnd.nextFloat() * 6.28f) }
    private var t = 0f
    private val shape = Path()
    private var last = 0L

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { shader = null }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); if (w <= 0 || h <= 0) return
        val k = colors
        if (shader == null) shader = LinearGradient(0f, 0f, 0f, h, k.skyTop, k.skyBottom, Shader.TileMode.CLAMP)
        paint.shader = shader; c.drawRect(0f, 0f, w, h, paint); paint.shader = null
        // halo + crescent, top right
        val cx = w * 0.84f; val cy = h * 0.09f; val r = w * 0.09f
        paint.shader = RadialGradient(cx, cy, r * 3.2f, k.glow, 0x00000000, Shader.TileMode.CLAMP); c.drawCircle(cx, cy, r * 3.2f, paint); paint.shader = null
        if (k.fullMoon) {   // 纯月: a pearl full moon with a soft rim
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = r * 0.08f; paint.color = k.star; c.drawCircle(cx, cy, r, paint); paint.style = Paint.Style.FILL
        } else { paint.color = k.star and 0x00FFFFFF or (0x55 shl 24); crescent(c, cx, cy, r, paint) }
        // stars twinkle slowly (alpha only)
        for (s in stars) {
            val a = (0.35f + 0.65f * (0.5f + 0.5f * sin(t * 0.8f + s[3] * 6.28f))).coerceIn(0f, 1f)
            paint.color = (k.star and 0x00FFFFFF) or (((k.star ushr 24) * a).toInt() shl 24)
            c.drawCircle(s[0] * w, s[1] * h, s[2] * resources.displayMetrics.density * 0.7f, paint)
        }
        val d = resources.displayMetrics.density
        when (k.particle) {
            TsukiTheme.Particle.PETALS -> for (m in motes) {   // 樱: petals fall, sway and spin
                val y = ((m[1] + t * 0.018f * (0.6f + m[2] / 3f)) % 1f + 1f) % 1f
                val x = ((m[0] + t * 0.006f) % 1f) * w + sin(t * 0.7f + m[3]) * w * 0.04f
                paint.color = (k.glow and 0x00FFFFFF) or ((200 * sin(y * PI).toFloat()).toInt() shl 24)
                c.save(); c.translate(x, y * h); c.rotate((t * 40f + m[3] * 57f) % 360f)
                val s = m[2] * d * 2.2f; shape.reset(); shape.moveTo(0f, -s); shape.quadTo(s * 0.9f, 0f, 0f, s); shape.quadTo(-s * 0.9f, 0f, 0f, -s)
                c.drawPath(shape, paint); c.restore()
            }
            TsukiTheme.Particle.SPARKLES -> for (m in motes) {   // 纯月: four-point glints pulse in place
                val a = (0.5f + 0.5f * sin(t * 1.3f + m[3])).coerceIn(0f, 1f)
                paint.color = (k.glow and 0x00FFFFFF) or ((180 * a).toInt() shl 24)
                val x = m[0] * w; val y = m[1] * h; val s = m[2] * d * 2.4f * (0.6f + 0.4f * a)
                shape.reset(); shape.moveTo(x, y - s); shape.quadTo(x, y, x + s, y); shape.quadTo(x, y, x, y + s); shape.quadTo(x, y, x - s, y); shape.quadTo(x, y, x, y - s)
                c.drawPath(shape, paint)
            }
            TsukiTheme.Particle.MOTES -> for (m in motes) {   // 夜月: silver motes drift up and sway
                val y = ((m[1] - t * 0.012f * (0.5f + m[2] / 3f)) % 1f + 1f) % 1f
                val x = m[0] * w + sin(t * 0.5f + m[3]) * w * 0.02f
                paint.color = (k.glow and 0x00FFFFFF) or ((110 * sin(y * PI).toFloat()).toInt() shl 24)
                c.drawCircle(x, y * h, m[2] * d, paint)
            }
        }
        if (animateMotes && isShown) {
            val now = System.nanoTime(); if (last != 0L) t += ((now - last) / 1e9f).coerceAtMost(0.1f); last = now
            postInvalidateDelayed(33)
        } else last = 0L
    }

    companion object {
        /** A crescent: a circle with an offset circle cut out. */
        fun crescent(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint, cut: Float = 0.42f) {
            val path = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
            val hole = Path().apply { addCircle(cx + r * cut, cy - r * cut * 0.6f, r * 0.92f, Path.Direction.CW) }
            path.op(hole, Path.Op.DIFFERENCE); c.drawPath(path, p)
        }
    }
}

/** Small moon-phase loading indicator (new → full → new). Static half moon with reduced motion. */
class MoonPhaseView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var tint: Int = TsukiTheme.current(ctx).accent; set(v) { field = v; invalidate() }
    var running = !TsukiTheme.reduceMotion(ctx)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frame = 0L
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f - 1; val cx = width / 2f; val cy = height / 2f
        val ph = if (running) TsukiTheme.phase(frame) else 0.25f
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * resources.displayMetrics.density; p.color = tint and 0x00FFFFFF or (0x66 shl 24)
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL; p.color = tint
        // lit part: terminator ellipse width follows cos of the phase angle
        val ang = ph * 2 * PI; val k = cos(ang).toFloat()
        val path = Path()
        val waxing = ph < 0.5f
        path.addArc(cx - r, cy - r, cx + r, cy + r, if (waxing) -90f else 90f, 180f)
        val rx = r * kotlin.math.abs(k)
        path.arcTo(cx - rx, cy - r, cx + rx, cy + r, if (waxing) 90f else -90f, if (k > 0) 180f else -180f, false)
        c.drawPath(path, p)
        if (running && isShown) { frame++; postInvalidateDelayed(60) }
    }
}
