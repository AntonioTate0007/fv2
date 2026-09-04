package com.fortress.jarvis.overlay

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The arc-reactor HUD. Pure Canvas, no dependencies.
 *
 * States drive the animation:
 *  IDLE      – translucent, slow tick rotation, faint breathing
 *  LISTENING – full opacity, a wave runs around the bar ring
 *  THINKING  – three arcs orbit the ring
 *  SPEAKING  – bars jump like an audio spectrum, core pulses
 *
 * The view is a square ring plus a caption strip underneath for the last thing
 * Jarvis said.
 */
class ReactorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var state: State = State.IDLE
        set(value) {
            if (field != value) {
                field = value
                stateChangedAt = System.currentTimeMillis()
                invalidate()
            }
        }

    /** Opacity when idle. The window "goes translucent" by dropping to this. */
    var idleAlpha: Float = 0.38f

    var caption: String = ""
        set(value) {
            field = value
            captionShownAt = System.currentTimeMillis()
            invalidate()
        }

    private val bars = 72
    private val ticks = 48
    private val barLevel = FloatArray(bars) { 0.3f }
    private val barTarget = FloatArray(bars) { 0.3f }
    private var rotation = 0f
    private var lastFrameNs = 0L
    private var startNs = System.nanoTime()
    private var lastSpectrumMs = 0L
    private var alphaNow = idleAlpha
    private var stateChangedAt = System.currentTimeMillis()
    private var captionShownAt = 0L
    private var envelope = 0.5f
    private val rnd = Random(7)

    private val cyan = Color.rgb(0x35, 0xE4, 0xFF)
    private val cyanDim = Color.rgb(0x18, 0x7A, 0x8C)
    private val white = Color.rgb(0xE6, 0xFB, 0xFF)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = cyanDim; strokeWidth = dp(1.2f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = cyan; strokeWidth = dp(1.5f); strokeCap = Paint.Cap.ROUND
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = cyan; strokeWidth = dp(2.2f); strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = white; strokeWidth = dp(2.5f); strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = cyan; maskFilter = BlurMaskFilter(dp(14f), BlurMaskFilter.Blur.NORMAL)
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val coreRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = white; strokeWidth = dp(1.5f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white; textSize = sp(12f); textAlign = Paint.Align.CENTER
    }
    private val captionBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(170, 8, 14, 20)
    }
    private val arcRect = RectF()
    private val captionRect = RectF()

    init {
        // BlurMaskFilter needs a software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    /** Height reserved under the ring for the caption. */
    val captionHeight: Float get() = dp(40f)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNs = 0L
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val nowNs = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else ((nowNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNs = nowNs
        val t = (nowNs - startNs) / 1e9f
        step(dt, t)

        val ringH = height - captionHeight
        val cx = width / 2f
        val cy = ringH / 2f
        val radius = min(width, ringH.toInt()) / 2f - dp(6f)
        val a255 = (alphaNow * 255f).toInt().coerceIn(0, 255)

        drawGlow(canvas, cx, cy, radius, t, a255)
        drawOuterRing(canvas, cx, cy, radius, a255)
        drawBars(canvas, cx, cy, radius, a255)
        if (state == State.THINKING) drawThinkingArcs(canvas, cx, cy, radius, t, a255)
        drawCore(canvas, cx, cy, radius, t, a255)
        drawCaption(canvas, cx, ringH, a255)

        // Idle needs far fewer frames than a speaking spectrum.
        if (state == State.IDLE && caption.isEmpty()) postInvalidateDelayed(66) else postInvalidateOnAnimation()
    }

    // ── animation ─────────────────────────────────────────────────────────

    private fun step(dt: Float, t: Float) {
        // opacity eases toward the state's target
        val target = if (state == State.IDLE) idleAlpha else 1f
        alphaNow += (target - alphaNow) * min(1f, dt * 6f)

        rotation += dt * when (state) {
            State.IDLE -> 6f
            State.LISTENING -> 30f
            State.THINKING -> 90f
            State.SPEAKING -> 18f
        }

        val nowMs = System.currentTimeMillis()
        when (state) {
            State.SPEAKING -> {
                if (nowMs - lastSpectrumMs > 70) {
                    lastSpectrumMs = nowMs
                    envelope = (envelope + (rnd.nextFloat() - 0.5f) * 0.5f).coerceIn(0.25f, 1f)
                    for (i in 0 until bars) {
                        val shape = 0.55f + 0.45f * sin(i * 0.9f + t * 3f)
                        barTarget[i] = (0.2f + rnd.nextFloat() * 0.8f * envelope * shape).coerceIn(0.12f, 1f)
                    }
                }
                lerpBars(dt * 18f)
            }
            State.LISTENING -> {
                for (i in 0 until bars) barTarget[i] = 0.35f + 0.4f * (0.5f + 0.5f * sin(t * 5f - i * 0.35f))
                lerpBars(dt * 10f)
            }
            State.THINKING -> {
                for (i in 0 until bars) barTarget[i] = 0.22f
                lerpBars(dt * 6f)
            }
            State.IDLE -> {
                for (i in 0 until bars) barTarget[i] = 0.28f + 0.06f * sin(t * 1.2f + i * 0.25f)
                lerpBars(dt * 4f)
            }
        }
    }

    private fun lerpBars(k: Float) {
        val f = min(1f, k)
        for (i in 0 until bars) barLevel[i] += (barTarget[i] - barLevel[i]) * f
    }

    // ── drawing ───────────────────────────────────────────────────────────

    private fun drawGlow(c: Canvas, cx: Float, cy: Float, r: Float, t: Float, a: Int) {
        val pulse = when (state) {
            State.SPEAKING -> 0.55f + 0.45f * envelope
            State.LISTENING -> 0.5f + 0.2f * sin(t * 3f)
            State.THINKING -> 0.45f
            State.IDLE -> 0.25f + 0.05f * sin(t * 1.5f)
        }
        glowPaint.alpha = (a * 0.45f * pulse).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, r * 0.5f, glowPaint)
    }

    private fun drawOuterRing(c: Canvas, cx: Float, cy: Float, r: Float, a: Int) {
        ringPaint.alpha = (a * 0.7f).toInt()
        c.drawCircle(cx, cy, r, ringPaint)
        c.drawCircle(cx, cy, r * 0.88f, ringPaint)
        tickPaint.alpha = a
        for (i in 0 until ticks) {
            val ang = Math.toRadians((rotation + i * 360f / ticks).toDouble())
            val len = if (i % 4 == 0) dp(7f) else dp(3f)
            val r0 = r - len
            c.drawLine(
                cx + (r0 * cos(ang)).toFloat(), cy + (r0 * sin(ang)).toFloat(),
                cx + (r * cos(ang)).toFloat(), cy + (r * sin(ang)).toFloat(), tickPaint
            )
        }
    }

    private fun drawBars(c: Canvas, cx: Float, cy: Float, r: Float, a: Int) {
        val inner = r * 0.52f
        val span = r * 0.32f
        for (i in 0 until bars) {
            val ang = Math.toRadians((-rotation * 0.5f + i * 360f / bars).toDouble())
            val len = inner + span * barLevel[i]
            barPaint.alpha = (a * (0.45f + 0.55f * barLevel[i])).toInt().coerceIn(0, 255)
            c.drawLine(
                cx + (inner * cos(ang)).toFloat(), cy + (inner * sin(ang)).toFloat(),
                cx + (len * cos(ang)).toFloat(), cy + (len * sin(ang)).toFloat(), barPaint
            )
        }
    }

    private fun drawThinkingArcs(c: Canvas, cx: Float, cy: Float, r: Float, t: Float, a: Int) {
        val rr = r * 0.94f
        arcRect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        arcPaint.alpha = a
        c.drawArc(arcRect, t * 160f, 70f, false, arcPaint)
        c.drawArc(arcRect, -t * 110f + 120f, 50f, false, arcPaint)
        c.drawArc(arcRect, t * 60f + 240f, 30f, false, arcPaint)
    }

    private fun drawCore(c: Canvas, cx: Float, cy: Float, r: Float, t: Float, a: Int) {
        val scale = when (state) {
            State.SPEAKING -> 1f + 0.06f * sin(t * 14f) + 0.08f * envelope
            State.LISTENING -> 1f + 0.05f * sin(t * 3f)
            State.THINKING -> 0.96f + 0.03f * sin(t * 8f)
            State.IDLE -> 1f + 0.02f * sin(t * 1.5f)
        }
        val cr = r * 0.4f * scale
        corePaint.shader = RadialGradient(
            cx, cy, cr,
            intArrayOf(white, cyan, Color.argb(0, 0x35, 0xE4, 0xFF)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        corePaint.alpha = a
        c.drawCircle(cx, cy, cr, corePaint)
        coreRingPaint.alpha = (a * 0.9f).toInt()
        c.drawCircle(cx, cy, r * 0.28f, coreRingPaint)
        ringPaint.alpha = (a * 0.5f).toInt()
        c.drawCircle(cx, cy, r * 0.46f, ringPaint)
    }

    private fun drawCaption(c: Canvas, cx: Float, top: Float, a: Int) {
        if (caption.isEmpty()) return
        val age = System.currentTimeMillis() - captionShownAt
        val ttl = 9000L
        if (age > ttl) { caption = ""; return }
        val fade = if (age > ttl - 1000) (ttl - age) / 1000f else 1f
        val text = TextUtils.ellipsize(caption, textPaint, width - dp(20f), TextUtils.TruncateAt.END).toString()
        val tw = textPaint.measureText(text)
        val h = dp(24f)
        captionRect.set(cx - tw / 2 - dp(10f), top + dp(4f), cx + tw / 2 + dp(10f), top + dp(4f) + h)
        captionBg.alpha = (170 * fade).toInt()
        c.drawRoundRect(captionRect, h / 2, h / 2, captionBg)
        textPaint.alpha = (255 * fade).toInt()
        c.drawText(text, cx, top + dp(4f) + h / 2 + textPaint.textSize * 0.35f, textPaint)
    }
}
