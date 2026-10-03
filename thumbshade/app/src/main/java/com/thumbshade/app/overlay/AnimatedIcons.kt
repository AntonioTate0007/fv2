package com.thumbshade.app.overlay

import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import com.thumbshade.app.data.AnimatedIcon
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Seconds since the composable appeared, updated every frame. */
@Composable
private fun rememberSeconds(): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t.floatValue = (it - start) / 1_000_000_000f }
    }
    return t
}

/** Battery level 0..1, refreshed every half minute. */
@Composable
private fun rememberBattery(context: Context): State<Float> {
    val level = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        val bm = context.getSystemService(BatteryManager::class.java)
        while (true) {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }?.let { level.floatValue = it / 100f }
            delay(30_000)
        }
    }
    return level
}

/** An animation drawn inside the button, behind the number and icons. */
@Composable
fun AnimatedIconView(style: AnimatedIcon, color: Color, modifier: Modifier = Modifier) {
    if (style == AnimatedIcon.NONE) return
    val t by rememberSeconds()
    val battery by rememberBattery(LocalContext.current)
    Canvas(modifier) { drawAnimatedIcon(style, color, t, battery) }
}

fun DrawScope.drawAnimatedIcon(style: AnimatedIcon, color: Color, t: Float, battery: Float) {
    val c = center
    val r = size.minDimension / 2f
    val tau = (2 * PI).toFloat()
    when (style) {
        AnimatedIcon.NONE -> Unit
        AnimatedIcon.RIM_LIGHT -> rotate(t * 120f) {
            drawCircle(
                Brush.sweepGradient(listOf(Color.Transparent, color.copy(alpha = 0.2f), color, Color.Transparent), c),
                radius = r * 0.9f, style = Stroke(r * 0.12f, cap = StrokeCap.Round),
            )
        }
        AnimatedIcon.MESH_ORB -> {
            // Three soft colour blobs drifting round each other.
            for (i in 0 until 3) {
                val a = t * (0.6f + i * 0.25f) + i * tau / 3f
                val p = Offset(c.x + cos(a) * r * 0.35f, c.y + sin(a) * r * 0.35f)
                val hue = ((t * 20f + i * 120f) % 360f)
                val col = if (i == 0) color else Color.hsv(hue, 0.65f, 1f)
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.85f), Color.Transparent), p, r * 0.9f), radius = r, center = c)
            }
        }
        AnimatedIcon.BATTERY_DOTS -> {
            val n = 12
            val lit = (battery * n).toInt().coerceIn(1, n)
            for (k in 0 until n) {
                val a = -PI.toFloat() / 2 + k * tau / n
                val p = Offset(c.x + cos(a) * r * 0.72f, c.y + sin(a) * r * 0.72f)
                val front = k == lit - 1
                val alpha = when {
                    front -> 0.5f + 0.5f * sin(t * 6f)
                    k < lit -> 1f
                    else -> 0.2f
                }
                drawCircle(color.copy(alpha = alpha.coerceIn(0.15f, 1f)), radius = r * 0.08f, center = p)
            }
        }
        AnimatedIcon.OCEAN_WAVE -> {
            // Water filled to the battery level, with two waves rolling across.
            val level = size.height * (1f - battery.coerceIn(0.15f, 0.95f))
            for (layer in 0 until 2) {
                val path = Path()
                path.moveTo(0f, size.height)
                val steps = 24
                for (k in 0..steps) {
                    val x = size.width * k / steps
                    val y = level + sin(x / size.width * tau * 1.2f + t * (2f + layer) + layer * 1.7f) * r * 0.08f
                    path.lineTo(x, y)
                }
                path.lineTo(size.width, size.height)
                path.close()
                drawPath(path, color.copy(alpha = if (layer == 0) 0.45f else 0.8f))
            }
        }
        AnimatedIcon.RADAR -> {
            for (k in 1..3) drawCircle(color.copy(alpha = 0.25f), radius = r * k / 3.4f, center = c, style = Stroke(1.2f))
            rotate(t * 140f) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, Color.Transparent, color.copy(alpha = 0.7f)), c),
                    startAngle = 0f, sweepAngle = 360f, useCenter = true,
                    topLeft = Offset(c.x - r * 0.88f, c.y - r * 0.88f), size = Size(r * 1.76f, r * 1.76f),
                )
            }
            val rnd = Random(4)
            repeat(3) {
                val a = rnd.nextFloat() * tau
                val d = r * (0.3f + rnd.nextFloat() * 0.5f)
                val sweepNow = (t * 140f / 360f * tau) % tau
                val since = ((sweepNow - a) % tau + tau) % tau
                drawCircle(color.copy(alpha = (1f - since / tau).coerceIn(0f, 1f)), radius = r * 0.06f, center = Offset(c.x + cos(a) * d, c.y + sin(a) * d))
            }
        }
        AnimatedIcon.RIPPLE -> for (i in 0 until 3) {
            val p = (t * 0.6f + i / 3f) % 1f
            drawCircle(color.copy(alpha = 1f - p), radius = r * 0.1f + p * r * 0.85f, center = c, style = Stroke(r * 0.05f))
        }
        AnimatedIcon.FIREFLIES -> {
            val rnd = Random(8)
            repeat(9) {
                val fx = 0.3f + rnd.nextFloat() * 0.6f
                val fy = 0.3f + rnd.nextFloat() * 0.6f
                val ph = rnd.nextFloat() * tau
                val p = Offset(c.x + sin(t * fx + ph) * r * 0.65f, c.y + cos(t * fy + ph * 1.3f) * r * 0.65f)
                val glow = (0.5f + 0.5f * sin(t * (1.5f + fx) + ph)).coerceIn(0f, 1f)
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = glow), Color.Transparent), p, r * 0.16f), radius = r * 0.16f, center = p)
            }
        }
        AnimatedIcon.SPECTRUM -> {
            val bars = 9
            val bw = size.width * 0.7f / bars
            val x0 = size.width * 0.15f
            for (k in 0 until bars) {
                val h = (0.25f + 0.75f * abs(sin(t * (2.1f + k * 0.37f) + k * 0.9f) * cos(t * 1.3f + k))) * size.height * 0.55f
                drawRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.9f), color), startY = c.y + size.height * 0.28f - h, endY = c.y + size.height * 0.28f),
                    topLeft = Offset(x0 + k * bw + bw * 0.15f, c.y + size.height * 0.28f - h), size = Size(bw * 0.7f, h),
                )
            }
        }
        AnimatedIcon.SHIMMER -> {
            val p = (t * 0.5f) % 1.4f - 0.2f
            val x = size.width * p * 1.6f - size.width * 0.3f
            drawRect(Brush.linearGradient(
                listOf(Color.Transparent, color.copy(alpha = 0.75f), Color.Transparent),
                start = Offset(x - r * 0.5f, 0f), end = Offset(x + r * 0.5f, size.height),
            ))
        }
        AnimatedIcon.CONSTELLATION -> {
            val rnd = Random(12)
            val stars = List(7) { Offset(c.x + (rnd.nextFloat() - 0.5f) * r * 1.4f, c.y + (rnd.nextFloat() - 0.5f) * r * 1.4f) }
            for (i in 0 until stars.size - 1) drawLine(color.copy(alpha = 0.35f), stars[i], stars[i + 1], 1.5f)
            stars.forEachIndexed { i, p ->
                val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * (1.5f + i * 0.4f) + i))
                drawCircle(color.copy(alpha = tw), radius = r * 0.055f, center = p)
            }
        }
        AnimatedIcon.HEARTBEAT -> {
            // A trace that scrolls across, with a beat each second.
            val path = Path()
            val steps = 40
            for (k in 0..steps) {
                val f = k / steps.toFloat()
                val phase = ((f + t * 0.8f) % 1f)
                val y = when {
                    phase in 0.40f..0.45f -> -(phase - 0.40f) / 0.05f * 0.25f
                    phase in 0.45f..0.50f -> -0.25f + (phase - 0.45f) / 0.05f * 0.95f
                    phase in 0.50f..0.55f -> 0.7f - (phase - 0.50f) / 0.05f * 0.85f
                    phase in 0.55f..0.60f -> -0.15f + (phase - 0.55f) / 0.05f * 0.15f
                    else -> 0f
                }
                val x = size.width * (0.1f + 0.8f * f)
                val yy = c.y - y * r * 0.7f
                if (k == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            }
            drawPath(path, color, style = Stroke(r * 0.07f, cap = StrokeCap.Round))
        }
        AnimatedIcon.STARFIELD -> {
            val rnd = Random(2)
            repeat(16) {
                val a = rnd.nextFloat() * tau
                val p = (t * (0.3f + rnd.nextFloat() * 0.4f) + rnd.nextFloat()) % 1f
                val d = p * p * r
                val pos = Offset(c.x + cos(a) * d, c.y + sin(a) * d)
                drawCircle(color.copy(alpha = p), radius = 0.5f + p * r * 0.05f, center = pos)
            }
        }
        AnimatedIcon.SHAPE_MORPH -> {
            // Blends between a triangle, a square and a circle while turning.
            val shapes = listOf(3f, 4f, 40f)
            val cycle = t * 0.4f
            val from = shapes[cycle.toInt() % 3]
            val to = shapes[(cycle.toInt() + 1) % 3]
            val f = (cycle % 1f).let { it * it * (3 - 2 * it) }
            val path = Path()
            val n = 60
            for (k in 0..n) {
                val a = k / n.toFloat() * tau
                fun polyR(sides: Float): Float {
                    val seg = tau / sides
                    return cos(seg / 2f) / cos((a % seg) - seg / 2f)
                }
                val rr = r * 0.55f * (polyR(from) * (1 - f) + polyR(to) * f)
                val p = Offset(c.x + cos(a + t * 0.5f) * rr, c.y + sin(a + t * 0.5f) * rr)
                if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, color, style = Stroke(r * 0.07f))
        }
        AnimatedIcon.ORBIT -> {
            drawCircle(color, radius = r * 0.13f, center = c)
            for (i in 0 until 2) {
                val rr = r * (0.45f + i * 0.28f)
                drawCircle(color.copy(alpha = 0.2f), radius = rr, center = c, style = Stroke(1.2f))
                val a = t * (1.6f - i * 0.6f) + i * 2f
                drawCircle(color, radius = r * (0.07f - i * 0.015f), center = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr))
            }
        }
        AnimatedIcon.CLOCK -> {
            // Real time.
            val now = Calendar.getInstance()
            val sec = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
            val min = now.get(Calendar.MINUTE) + sec / 60f
            val hr = now.get(Calendar.HOUR) + min / 60f
            for (k in 0 until 12) {
                val a = k * tau / 12
                drawLine(color.copy(alpha = 0.5f), Offset(c.x + cos(a) * r * 0.72f, c.y + sin(a) * r * 0.72f), Offset(c.x + cos(a) * r * 0.82f, c.y + sin(a) * r * 0.82f), 1.5f)
            }
            fun hand(turns: Float, len: Float, width: Float, col: Color) {
                val a = turns * tau - PI.toFloat() / 2
                drawLine(col, c, Offset(c.x + cos(a) * r * len, c.y + sin(a) * r * len), width, cap = StrokeCap.Round)
            }
            hand(hr / 12f, 0.42f, r * 0.08f, color)
            hand(min / 60f, 0.62f, r * 0.055f, color)
            hand(sec / 60f, 0.7f, r * 0.025f, color.copy(alpha = 0.7f))
        }
        AnimatedIcon.BATTERY_RING -> {
            drawCircle(color.copy(alpha = 0.2f), radius = r * 0.78f, center = c, style = Stroke(r * 0.1f))
            drawArc(
                color, startAngle = -90f, sweepAngle = 360f * battery, useCenter = false,
                topLeft = Offset(c.x - r * 0.78f, c.y - r * 0.78f), size = Size(r * 1.56f, r * 1.56f),
                style = Stroke(r * 0.1f, cap = StrokeCap.Round),
            )
            val a = -PI.toFloat() / 2 + battery * tau
            drawCircle(Color.White.copy(alpha = 0.5f + 0.5f * sin(t * 4f)), radius = r * 0.06f, center = Offset(c.x + cos(a) * r * 0.78f, c.y + sin(a) * r * 0.78f))
        }
        AnimatedIcon.AURORA -> for (i in 0 until 3) {
            val path = Path()
            val steps = 20
            val base = c.y + (i - 1) * r * 0.25f
            for (k in 0..steps) {
                val x = size.width * k / steps
                val y = base + sin(x / size.width * tau + t * (0.8f + i * 0.3f) + i) * r * 0.18f
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val col = if (i == 1) color else Color.hsv((150f + i * 60f + t * 10f) % 360f, 0.6f, 1f)
            drawPath(path, col.copy(alpha = 0.55f), style = Stroke(r * 0.22f, cap = StrokeCap.Round))
        }
    }
}
