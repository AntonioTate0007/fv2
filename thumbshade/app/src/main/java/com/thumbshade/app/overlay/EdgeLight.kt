package com.thumbshade.app.overlay

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
            s.edgeColorMode == EdgeColorMode.ACCENT -> Color(s.accentColor)
            item.color != 0 -> Color(item.color).copy(alpha = 1f)
            else -> Color(s.accentColor)
        }
        val border = styleOverride?.let { runCatching { EdgeStyle.valueOf(it) }.getOrNull() } ?: s.edgeStyle
        val buttonFx = if (styleOverride == null) s.buttonEffect else null
        main.post { show(context.applicationContext, border, buttonFx, color, s.edgeDurationMs.toLong(), s.edgeThicknessDp, s.edgeWakeScreen) }
    }

    fun preview(context: Context) {
        val s = SettingsRepo.current
        show(context.applicationContext, s.edgeStyle, s.buttonEffect, if (s.edgeColorMode == EdgeColorMode.CUSTOM) Color(s.edgeCustomColor) else Color(s.accentColor), s.edgeDurationMs.toLong(), s.edgeThicknessDp, false)
    }

    private fun show(context: Context, border: EdgeStyle, buttonFx: EdgeStyle?, color: Color, durationMs: Long, thicknessDp: Int, wake: Boolean) {
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
                durationMs = durationMs,
                thicknessDp = thicknessDp,
                buttonCenter = center?.let { Offset(it.first.toFloat(), it.second.toFloat()) },
                buttonRadius = size?.let { maxOf(it.first, it.second) / 2f } ?: 0f,
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
    durationMs: Long,
    thicknessDp: Int,
    buttonCenter: Offset?,
    buttonRadius: Float,
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
        if (border != null) drawBorder(border, color, envelope, phase, stroke, corner, flicker)
        if (buttonFx != null && buttonCenter != null) drawButtonFx(buttonFx, color, envelope, phase, buttonCenter, buttonRadius)
    }
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
        else -> drawPath(path, color.copy(alpha = env), style = Stroke(stroke))
    }
}

private fun DrawScope.drawButtonFx(style: EdgeStyle, color: Color, env: Float, phase: Float, c: Offset, r: Float) {
    val reach = 3.2f * r
    when (style) {
        EdgeStyle.RIPPLE -> for (i in 0 until 3) {
            val p = (phase + i / 3f) % 1f
            drawCircle(color.copy(alpha = env * (1f - p)), radius = r + p * reach, center = c, style = Stroke(6f * (1f - p) + 2f))
        }
        EdgeStyle.SONAR -> {
            val p = phase
            drawCircle(color.copy(alpha = env * 0.35f * (1f - p)), radius = r + p * reach, center = c)
            drawCircle(color.copy(alpha = env * (1f - p)), radius = r + p * reach, center = c, style = Stroke(4f))
        }
        EdgeStyle.HALO -> {
            val breathe = 0.5f + 0.5f * sin(phase * 2 * PI).toFloat()
            for (i in 5 downTo 1) drawCircle(color.copy(alpha = env * breathe * 0.12f * (6 - i)), radius = r + i * 8f, center = c)
        }
        else -> Unit
    }
}
