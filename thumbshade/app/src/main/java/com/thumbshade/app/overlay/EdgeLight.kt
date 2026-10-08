package com.thumbshade.app.overlay

import androidx.core.graphics.drawable.toBitmap
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.thumbshade.app.data.EdgeColorMode
import com.thumbshade.app.data.EdgeStyle
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.ShadeItem
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Lights up the screen edge (and/or around the button) when a notification arrives. */
object EdgeLight {
    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null
    private var owner: OverlayOwner? = null

    fun onNotification(context: Context, item: ShadeItem, styleOverride: String?, colorOverride: Long?) {
        val s = SettingsRepo.current
        if (!s.edgeLight && styleOverride == null) return
        if (!Settings.canDrawOverlays(context)) return
        val pm = context.getSystemService(PowerManager::class.java)
        if (s.edgeOnlyScreenOn && pm?.isInteractive == false && !s.edgeWakeScreen) return

        val color = when {
            colorOverride != null -> Color(colorOverride)
            s.edgeColorMode == EdgeColorMode.CUSTOM -> Color(s.edgeCustomColor)
            s.edgeColorMode == EdgeColorMode.ACCENT -> Color(com.thumbshade.app.ui.currentAccent(context, s))
            item.color != 0 -> Color(item.color).copy(alpha = 1f)
            else -> Color(com.thumbshade.app.ui.currentAccent(context, s))
        }
        val border = styleOverride?.let { runCatching { EdgeStyle.valueOf(it) }.getOrNull() } ?: s.edgeStyle
        val buttonFx = if (styleOverride == null) s.buttonEffect else null
        // The app's icon colours: the light shimmers through them (a rule's own colour still wins).
        val palette = if (colorOverride == null && s.edgeColorMode == EdgeColorMode.APP_ICON) iconColors(context, item.pkg) else emptyList()
        val main1 = palette.firstOrNull() ?: color
        main.post { show(context.applicationContext, border, buttonFx, main1, s.edgeDurationMs.toLong(), s.edgeThicknessDp, s.edgeWakeScreen, palette) }
    }

    private val paletteCache = java.util.concurrent.ConcurrentHashMap<String, List<Color>>()

    /** The main colours of [pkg]'s icon (as shown, so icon packs count), cached. */
    fun iconColors(context: Context, pkg: String): List<Color> = paletteCache.getOrPut(pkg + ":" + com.thumbshade.app.icons.IconStore.version.value) {
        runCatching {
            val d = com.thumbshade.app.notif.AppInfoCache.icon(context, pkg) ?: return@runCatching emptyList()
            val bmp = d.toBitmap(48, 48)
            val px = IntArray(48 * 48)
            bmp.getPixels(px, 0, 48, 0, 0, 48, 48)
            IconPalette.extract(px, 4).map { Color(it) }
        }.getOrDefault(emptyList())
    }

    fun preview(context: Context) {
        val s = SettingsRepo.current
        // For the app-colours mode, preview with the newest notification's app.
        val palette = if (s.edgeColorMode == EdgeColorMode.APP_ICON) {
            com.thumbshade.app.notif.NotificationRepo.items.value.maxByOrNull { it.postTime }?.let { iconColors(context, it.pkg) }.orEmpty()
                .ifEmpty { listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC05), Color(0xFF34A853)) }
        } else emptyList()
        val color = palette.firstOrNull() ?: if (s.edgeColorMode == EdgeColorMode.CUSTOM) Color(s.edgeCustomColor) else Color(com.thumbshade.app.ui.currentAccent(context, s))
        show(context.applicationContext, s.edgeStyle, s.buttonEffect, color, s.edgeDurationMs.toLong(), s.edgeThicknessDp, false, palette)
    }

    private fun show(context: Context, border: EdgeStyle, buttonFx: EdgeStyle?, color: Color, durationMs: Long, thicknessDp: Int, wake: Boolean, palette: List<Color> = emptyList()) {
        dismiss(context)
        if (wake) {
            runCatching {
                @Suppress("DEPRECATION")
                val wl = context.getSystemService(PowerManager::class.java)!!
                    .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "thumbshade:edge")
                wl.acquire(durationMs + 500)
            }
        }
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val params = OverlayWindows.params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            touchable = false,
            noLimits = true,
        )
        val center = OverlayService.instance?.buttonCenter()
        val size = OverlayService.instance?.buttonSize()
        val o = OverlayOwner()
        val view = OverlayWindows.composeView(context, o) {
            EdgeCanvas(
                border = if (border.aroundButton) null else border,
                buttonFx = buttonFx?.takeIf { it.aroundButton && center != null } ?: border.takeIf { it.aroundButton && center != null },
                color = color,
                palette = palette,
                durationMs = durationMs,
                thicknessDp = thicknessDp,
                buttonCenter = center?.let { Offset(it.first.toFloat(), it.second.toFloat()) },
                buttonHalf = size?.let { Offset(it.first / 2f, it.second / 2f) } ?: Offset.Zero,
            )
        }
        runCatching { wm.addView(view, params) }.onFailure { o.destroy(); return }
        current = view
        owner = o
        main.postDelayed({ if (current === view) dismiss(context) }, durationMs + 200)
    }

    private fun dismiss(context: Context) {
        val v = current ?: return
        runCatching { context.getSystemService(WindowManager::class.java)?.removeView(v) }
        owner?.destroy()
        current = null
        owner = null
    }
}

@Composable
private fun EdgeCanvas(
    border: EdgeStyle?,
    buttonFx: EdgeStyle?,
    color: Color,
    palette: List<Color>,
    durationMs: Long,
    thicknessDp: Int,
    buttonCenter: Offset?,
    buttonHalf: Offset,
) {
    val infinite = rememberInfiniteTransition(label = "edge")
    val phase by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    var envelope by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        // Fade in, hold, fade out over the whole duration.
        val start = System.currentTimeMillis()
        while (true) {
            val t = (System.currentTimeMillis() - start) / durationMs.toFloat()
            envelope = when {
                t < 0.12f -> t / 0.12f
                t > 0.8f -> ((1f - t) / 0.2f).coerceAtLeast(0f)
                else -> 1f
            }
            if (t >= 1f) break
            delay(16)
        }
    }
    val flicker = remember { Random(System.nanoTime()) }

    Canvas(Modifier.fillMaxSize()) {
        val stroke = thicknessDp.dp.toPx()
        val corner = 36.dp.toPx()
        if (palette.size < 2) {
            if (border != null) drawBorder(border, color, envelope, phase, stroke, corner, flicker)
            if (buttonFx != null && buttonCenter != null) drawButtonFx(buttonFx, color, envelope, phase, buttonCenter, buttonHalf)
        } else {
            // App colours: draw each effect as usual, then repaint what it drew with a slowly
            // turning sweep of the icon's colours, keeping the effect's shape and fade.
            val loop = (palette + palette.first()).toTypedArray()
            if (border != null) recolored(sweep(loop, Offset(size.width / 2, size.height / 2), phase)) {
                drawBorder(border, color, envelope, phase, stroke, corner, flicker)
            }
            if (buttonFx != null && buttonCenter != null) recolored(sweep(loop, buttonCenter, phase)) {
                drawButtonFx(buttonFx, color, envelope, phase, buttonCenter, buttonHalf)
            }
        }
    }
}

private fun sweep(colors: Array<Color>, center: Offset, phase: Float): Brush {
    val n = colors.size - 1
    // Shift the stops round with the phase so the colours travel.
    val stops = colors.mapIndexed { i, c -> ((i.toFloat() / n + phase) % 1f) to c }.sortedBy { it.first }
    val first = stops.first()
    val last = stops.last()
    val wrapped = listOf(0f to last.second) + stops + listOf(1f to first.second)
    return Brush.sweepGradient(*wrapped.toTypedArray(), center = center)
}

/** Draws [block] into its own layer, then paints [brush] only where it drew (keeping its alpha). */
private inline fun DrawScope.recolored(brush: Brush, block: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint())
    block()
    drawRect(brush, blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn)
    canvas.restore()
}

private fun DrawScope.borderPath(inset: Float, corner: Float): Path = Path().apply {
    addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(corner, corner)))
}

private fun DrawScope.drawBorder(style: EdgeStyle, color: Color, env: Float, phase: Float, stroke: Float, corner: Float, rnd: Random) {
    val path = borderPath(stroke / 2, corner)
    when (style) {
        EdgeStyle.BASIC -> drawPath(path, color.copy(alpha = env), style = Stroke(stroke))
        EdgeStyle.GLOW -> {
            val breathe = 0.6f + 0.4f * sin(phase * 2 * PI).toFloat()
            for (i in 4 downTo 1) {
                drawPath(path, color.copy(alpha = env * breathe * 0.18f * (5 - i)), style = Stroke(stroke * i * 1.6f))
            }
            drawPath(path, color.copy(alpha = env), style = Stroke(stroke * 0.6f))
        }
        EdgeStyle.MULTICOLOUR -> {
            // Hue wheel that turns with the phase; hue wraps, so there is no seam.
            val stops = (0..12).map { k ->
                val f = k / 12f
                f to Color.hsv(((f - phase + 1f) % 1f) * 360f, 0.85f, 1f, env)
            }.toTypedArray()
            val brush = Brush.sweepGradient(*stops, center = Offset(size.width / 2, size.height / 2))
            drawPath(path, brush, style = Stroke(stroke))
        }
        EdgeStyle.HEARTBEAT -> {
            // Two quick beats then rest.
            val t = phase
            val beat = when {
                t < 0.12f -> t / 0.12f
                t < 0.24f -> 1f - (t - 0.12f) / 0.12f
                t < 0.36f -> (t - 0.24f) / 0.12f
                t < 0.5f -> 1f - (t - 0.36f) / 0.14f
                else -> 0f
            }
            drawPath(path, color.copy(alpha = env * (0.25f + 0.75f * beat)), style = Stroke(stroke * (1f + beat)))
        }
        EdgeStyle.NEON -> {
            val on = rnd.nextFloat() > 0.08f
            val a = if (on) env else env * 0.2f
            drawPath(path, color.copy(alpha = a * 0.35f), style = Stroke(stroke * 3f))
            drawPath(path, Color.White.copy(alpha = a * 0.9f), style = Stroke(stroke * 0.4f))
            drawPath(path, color.copy(alpha = a), style = Stroke(stroke))
        }
        EdgeStyle.COMET -> {
            val measure = PathMeasure()
            measure.setPath(path, false)
            val len = measure.length
            val head = phase * len
            val tail = len * 0.25f
            val segments = 12
            for (i in 0 until segments) {
                val a = head - tail * i / segments
                val b = head - tail * (i + 1) / segments
                val seg = Path()
                val start = ((b % len) + len) % len
                val end = ((a % len) + len) % len
                if (end > start) {
                    measure.getSegment(start, end, seg, true)
                    drawPath(seg, color.copy(alpha = env * (1f - i / segments.toFloat())), style = Stroke(stroke * 1.4f, cap = StrokeCap.Round))
                }
            }
        }
        EdgeStyle.ECHO -> for (i in 0 until 3) {
            // Copies of the border that drift inwards and fade.
            val p = (phase + i / 3f) % 1f
            val inset = stroke / 2 + p * 48.dp.toPx()
            drawPath(borderPath(inset, (corner - inset / 2).coerceAtLeast(0f)), color.copy(alpha = env * (1f - p)), style = Stroke(stroke * (1f - p * 0.6f)))
        }
        EdgeStyle.LIGHTNING -> {
            // A jagged bolt along each side that re-strikes a few times a second.
            val strike = (phase * 5).toInt()
            val r = Random(strike * 31 + 7)
            val flash = 1f - (phase * 5 % 1f)
            drawPath(path, color.copy(alpha = env * 0.25f), style = Stroke(stroke))
            fun bolt(x0: Float, y0: Float, x1: Float, y1: Float, horizontal: Boolean) {
                val bp = Path().apply { moveTo(x0, y0) }
                val n = 14
                for (k in 1..n) {
                    val f = k / n.toFloat()
                    val jitter = (r.nextFloat() - 0.5f) * stroke * 4
                    val x = x0 + (x1 - x0) * f + if (horizontal) 0f else jitter
                    val y = y0 + (y1 - y0) * f + if (horizontal) jitter else 0f
                    bp.lineTo(x, y)
                }
                drawPath(bp, color.copy(alpha = env * flash * 0.5f), style = Stroke(stroke * 2.2f))
                drawPath(bp, Color.White.copy(alpha = env * flash), style = Stroke(stroke * 0.5f))
            }
            val e = stroke * 1.5f
            bolt(e, 0f, e, size.height, false)
            bolt(size.width - e, 0f, size.width - e, size.height, false)
            if (r.nextBoolean()) bolt(0f, e, size.width, e, true) else bolt(0f, size.height - e, size.width, size.height - e, true)
        }
        EdgeStyle.RISE -> {
            // Light climbs both sides from the bottom, then the whole frame glows.
            val level = (phase * 1.4f).coerceAtMost(1f)
            val top = size.height * (1f - level)
            drawLine(color.copy(alpha = env), Offset(stroke / 2, size.height), Offset(stroke / 2, top), stroke)
            drawLine(color.copy(alpha = env), Offset(size.width - stroke / 2, size.height), Offset(size.width - stroke / 2, top), stroke)
            drawLine(color.copy(alpha = env), Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            if (level >= 1f) drawPath(path, color.copy(alpha = env * (1f - (phase * 1.4f - 1f) / 0.4f).coerceIn(0f, 1f)), style = Stroke(stroke))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = env * 0.25f)), startY = top, endY = size.height), topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(stroke * 4, size.height - top))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = env * 0.25f)), startY = top, endY = size.height), topLeft = Offset(size.width - stroke * 4, top), size = androidx.compose.ui.geometry.Size(stroke * 4, size.height - top))
        }
        EdgeStyle.DRIP -> {
            // The top edge glows and drops run down the sides.
            drawPath(path, color.copy(alpha = env * 0.6f), style = Stroke(stroke))
            val r = Random(11)
            repeat(10) { k ->
                val left = k % 2 == 0
                val speed = 0.6f + r.nextFloat() * 0.8f
                val offset = r.nextFloat()
                val p = (phase * speed + offset) % 1f
                val x = if (left) stroke * (1.5f + r.nextFloat()) else size.width - stroke * (1.5f + r.nextFloat())
                val y = corner + p * (size.height - 2 * corner)
                val drop = stroke * (1.2f + r.nextFloat())
                drawLine(color.copy(alpha = env * (1f - p) * 0.6f), Offset(x, y - drop * 5), Offset(x, y), drop * 0.6f, cap = StrokeCap.Round)
                drawCircle(color.copy(alpha = env * (1f - p)), radius = drop, center = Offset(x, y))
            }
        }
        EdgeStyle.CONVERGE -> {
            // Two beams start at the bottom centre, run round both sides and meet at the top.
            val measure = PathMeasure()
            val loop = Path().apply {
                // Start at bottom centre, clockwise round to the top centre.
                moveTo(size.width / 2, size.height - stroke / 2)
                lineTo(stroke / 2, size.height - stroke / 2)
                lineTo(stroke / 2, stroke / 2)
                lineTo(size.width / 2, stroke / 2)
            }
            val mirror = Path().apply {
                moveTo(size.width / 2, size.height - stroke / 2)
                lineTo(size.width - stroke / 2, size.height - stroke / 2)
                lineTo(size.width - stroke / 2, stroke / 2)
                lineTo(size.width / 2, stroke / 2)
            }
            val grow = (phase * 1.25f).coerceAtMost(1f)
            for (pp in listOf(loop, mirror)) {
                measure.setPath(pp, false)
                val seg = Path()
                measure.getSegment(0f, measure.length * grow, seg, true)
                drawPath(seg, color.copy(alpha = env), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            if (grow >= 1f) {
                val flash = 1f - (phase * 1.25f - 1f) / 0.25f
                drawCircle(color.copy(alpha = env * flash.coerceIn(0f, 1f) * 0.6f), radius = 40.dp.toPx(), center = Offset(size.width / 2, 0f))
            }
        }
        else -> drawPath(path, color.copy(alpha = env), style = Stroke(stroke))
    }
}

/** The button's outline grown by [grow]: a pill or circle, following the button's shape. */
private fun shapePath(c: Offset, half: Offset, grow: Float): Path {
    val hw = half.x + grow
    val hh = half.y + grow
    val r = minOf(hw, hh)
    return Path().apply { addRoundRect(RoundRect(c.x - hw, c.y - hh, c.x + hw, c.y + hh, CornerRadius(r, r))) }
}

private fun DrawScope.drawButtonFx(style: EdgeStyle, color: Color, env: Float, phase: Float, c: Offset, half: Offset) {
    val r = maxOf(half.x, half.y, 1f)
    val reach = 3.2f * r
    when (style) {
        EdgeStyle.RIPPLE -> for (i in 0 until 3) {
            val p = (phase + i / 3f) % 1f
            drawPath(shapePath(c, half, p * reach), color.copy(alpha = env * (1f - p)), style = Stroke(6f * (1f - p) + 2f))
        }
        EdgeStyle.SONAR -> {
            val p = phase
            drawPath(shapePath(c, half, p * reach), color.copy(alpha = env * 0.35f * (1f - p)))
            drawPath(shapePath(c, half, p * reach), color.copy(alpha = env * (1f - p)), style = Stroke(4f))
        }
        EdgeStyle.HALO -> {
            val breathe = 0.5f + 0.5f * sin(phase * 2 * PI).toFloat()
            for (i in 5 downTo 1) drawPath(shapePath(c, half, i * 8f), color.copy(alpha = env * breathe * 0.12f * (6 - i)))
        }
        EdgeStyle.PULSE_RINGS -> for (i in 0 until 4) {
            // Evenly spaced solid rings beating outward together.
            val beat = 0.5f + 0.5f * sin((phase * 2 - i * 0.15f) * 2 * PI).toFloat()
            drawPath(shapePath(c, half, 10f + i * r * 0.45f + beat * 6f), color.copy(alpha = env * beat * (1f - i / 4f)), style = Stroke(5f))
        }
        EdgeStyle.WAVE -> {
            // A wobbling outline whose bumps travel round the button.
            for (ring in 0 until 2) {
                val base = r + 14f + ring * 18f
                val wave = Path()
                val n = 96
                for (k in 0..n) {
                    val a = k / n.toFloat() * 2 * PI
                    val rr = base + 7f * sin(a * 6 + phase * 2 * PI * (if (ring == 0) 1 else -1)).toFloat()
                    val x = c.x + (rr * kotlin.math.cos(a)).toFloat() * (half.x + 14f) / r
                    val y = c.y + (rr * sin(a)).toFloat() * (half.y + 14f) / r
                    if (k == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
                }
                drawPath(wave, color.copy(alpha = env * (0.9f - ring * 0.4f)), style = Stroke(4f))
            }
        }
        EdgeStyle.BUBBLES -> {
            val rnd = Random(3)
            repeat(16) {
                val speed = 0.5f + rnd.nextFloat()
                val p = (phase * speed + rnd.nextFloat()) % 1f
                val x = c.x + (rnd.nextFloat() - 0.5f) * half.x * 2.4f + 10f * sin((p * 4 + it) * PI).toFloat()
                val y = c.y - half.y - p * reach * 1.4f
                val br = 4f + rnd.nextFloat() * 10f
                drawCircle(color.copy(alpha = env * (1f - p)), radius = br, center = Offset(x, y), style = Stroke(2.5f))
                drawCircle(Color.White.copy(alpha = env * (1f - p) * 0.6f), radius = br * 0.25f, center = Offset(x - br * 0.35f, y - br * 0.35f))
            }
        }
        EdgeStyle.FIREWORKS -> {
            // Three bursts, staggered, each a ring of sparks that falls slightly as it fades.
            for (b in 0 until 3) {
                val p = (phase * 1.5f + b / 3f) % 1f
                val rnd = Random(b * 17 + 5)
                val bc = Offset(c.x + (rnd.nextFloat() - 0.5f) * reach, c.y - r - rnd.nextFloat() * reach)
                val hue = (rnd.nextFloat() * 360f)
                val spark = if (b == 0) color else Color.hsv(hue, 0.8f, 1f)
                repeat(14) { k ->
                    val a = k / 14f * 2 * PI
                    val dist = p * r * 2.2f
                    val pt = Offset(bc.x + (dist * kotlin.math.cos(a)).toFloat(), bc.y + (dist * sin(a)).toFloat() + p * p * r)
                    drawCircle(spark.copy(alpha = env * (1f - p)), radius = 3.5f * (1f - p) + 1f, center = pt)
                }
            }
        }
        EdgeStyle.ECLIPSE -> {
            // A dark disc slides across a glowing corona.
            val corona = 0.7f + 0.3f * sin(phase * 4 * PI).toFloat()
            for (i in 6 downTo 1) drawPath(shapePath(c, half, i * 6f), color.copy(alpha = env * corona * 0.1f * (7 - i)))
            val shift = (phase * 2f - 1f) * r * 0.5f
            drawPath(shapePath(Offset(c.x + shift, c.y), half, 2f), Color.Black.copy(alpha = env * 0.85f))
            drawPath(shapePath(c, half, 4f), color.copy(alpha = env), style = Stroke(3f))
        }
        EdgeStyle.SPOTLIGHT -> {
            // The rest of the screen dims; a soft light falls on the button.
            val glow = 0.8f + 0.2f * sin(phase * 2 * PI).toFloat()
            drawRect(Brush.radialGradient(
                0f to Color.Transparent,
                0.35f to color.copy(alpha = env * 0.15f * glow),
                1f to Color.Black.copy(alpha = env * 0.55f),
                center = c,
                radius = reach * 1.6f,
            ))
            drawPath(shapePath(c, half, 6f), color.copy(alpha = env * glow), style = Stroke(4f))
        }
        EdgeStyle.SPARKLE -> {
            val rnd = Random(9)
            repeat(18) {
                val a = rnd.nextFloat() * 2 * PI
                val d = r + 8f + rnd.nextFloat() * reach * 0.7f
                val tw = sin((phase * 2 + rnd.nextFloat()) * 2 * PI).toFloat().coerceAtLeast(0f)
                val pt = Offset(c.x + (d * kotlin.math.cos(a)).toFloat(), c.y + (d * sin(a)).toFloat())
                val len = 4f + 8f * tw
                val col = (if (it % 3 == 0) Color.White else color).copy(alpha = env * tw)
                drawLine(col, Offset(pt.x - len, pt.y), Offset(pt.x + len, pt.y), 2.5f, cap = StrokeCap.Round)
                drawLine(col, Offset(pt.x, pt.y - len), Offset(pt.x, pt.y + len), 2.5f, cap = StrokeCap.Round)
            }
        }
        EdgeStyle.CHARGE -> {
            // A ring fills like a charging meter, then flashes.
            val fill = (phase * 1.3f).coerceAtMost(1f)
            val ring = shapePath(c, half, 10f)
            drawPath(ring, color.copy(alpha = env * 0.2f), style = Stroke(7f))
            val m = PathMeasure()
            m.setPath(ring, false)
            val seg = Path()
            m.getSegment(0f, m.length * fill, seg, true)
            drawPath(seg, color.copy(alpha = env), style = Stroke(7f, cap = StrokeCap.Round))
            if (fill >= 1f) drawPath(shapePath(c, half, 10f), color.copy(alpha = env * 0.35f * (1f - (phase * 1.3f - 1f) / 0.3f).coerceIn(0f, 1f)))
        }
        EdgeStyle.VORTEX -> {
            // Spiral arms that spin around the button.
            for (arm in 0 until 4) {
                val sp = Path()
                val n = 40
                for (k in 0..n) {
                    val f = k / n.toFloat()
                    val a = arm * PI / 2 + phase * 2 * PI + f * 2.4 * PI
                    val d = r + 6f + f * reach * 0.8f
                    val pt = Offset(c.x + (d * kotlin.math.cos(a)).toFloat(), c.y + (d * sin(a)).toFloat())
                    if (k == 0) sp.moveTo(pt.x, pt.y) else sp.lineTo(pt.x, pt.y)
                }
                drawPath(sp, Brush.radialGradient(listOf(color.copy(alpha = env), Color.Transparent), center = c, radius = r + reach * 0.8f), style = Stroke(5f, cap = StrokeCap.Round))
            }
        }
        EdgeStyle.CONFETTI -> {
            val rnd = Random(21)
            repeat(26) {
                val speed = 0.6f + rnd.nextFloat() * 0.8f
                val p = (phase * speed + rnd.nextFloat()) % 1f
                val a = (rnd.nextFloat() - 0.5f) * PI * 1.2 - PI / 2
                val v = reach * (0.6f + rnd.nextFloat() * 0.6f)
                val x = c.x + (v * p * kotlin.math.cos(a)).toFloat()
                val y = c.y + (v * p * sin(a)).toFloat() + p * p * reach * 1.2f
                val col = if (it % 4 == 0) color else Color.hsv(rnd.nextFloat() * 360f, 0.75f, 1f)
                val w = 6f + rnd.nextFloat() * 6f
                val tilt = p * 6f + it
                val dx = w * kotlin.math.cos(tilt)
                val dy = w * 0.5f * sin(tilt.toDouble()).toFloat()
                drawLine(col.copy(alpha = env * (1f - p * 0.7f)), Offset(x - dx, y - dy), Offset(x + dx, y + dy), 5f)
            }
        }
        else -> Unit
    }
}
