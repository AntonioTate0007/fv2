package com.thumbshade.app.overlay

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thumbshade.app.data.GestureAction
import com.thumbshade.app.data.GestureMode
import com.thumbshade.app.data.GestureType
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.AppInfoCache
import com.thumbshade.app.ui.AppIcon
import com.thumbshade.app.ui.ThumbTheme
import com.thumbshade.app.ui.currentAccent
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The action wheel: press the button, slide onto a slot and lift. Slots sit in three rings that
 * follow the button's shape; slots that would fall off screen are mirrored to the other side.
 */
object ActionWheel {
    data class Slot(val action: GestureAction, val x: Float, val y: Float, val ring: Int)

    private var view: View? = null
    private var owner: OverlayOwner? = null
    private var slots: List<Slot> = emptyList()
    private val selected = mutableIntStateOf(-1)
    private var hitRadius = 0f

    /** Lays out the slots around a button centred at ([cx], [cy]) with half sizes [hw] × [hh]. */
    fun layout(mode: GestureMode, cx: Float, cy: Float, hw: Float, hh: Float, density: Float, screenW: Float, screenH: Float): List<Slot> =
        layout(List(GestureMode.SLOT_COUNT) { mode.slot(it) }, cx, cy, hw, hh, density, screenW, screenH)

    /** [actions] fill the inner ring first, then the middle and outer; NONE leaves a gap. */
    fun layout(actions: List<GestureAction>, cx: Float, cy: Float, hw: Float, hh: Float, density: Float, screenW: Float, screenH: Float): List<Slot> {
        val margin = 30f * density
        val out = mutableListOf<Slot>()
        var index = 0
        GestureMode.RINGS.forEachIndexed { ring, count ->
            val gap = (56f + ring * 60f) * density
            for (k in 0 until count) {
                val action = actions.getOrNull(index++) ?: GestureAction()
                if (action.type == GestureType.NONE) continue
                // Start at the top and go round; offset alternate rings so slots don't line up.
                val a = (-Math.PI / 2 + (k + if (ring % 2 == 1) 0.5 else 0.0) * 2 * Math.PI / count).toFloat()
                var x = cx + cos(a) * (hw + gap)
                var y = cy + sin(a) * (hh + gap)
                if (x < margin || x > screenW - margin) x = cx - (x - cx)
                if (y < margin || y > screenH - margin) y = cy - (y - cy)
                out += Slot(action, x.coerceIn(margin, screenW - margin), y.coerceIn(margin, screenH - margin), ring)
            }
        }
        return out
    }

    fun show(context: Context, mode: GestureMode, cx: Float, cy: Float, hw: Float, hh: Float) =
        show(context, List(GestureMode.SLOT_COUNT) { mode.slot(it) }, cx, cy, hw, hh)

    /** Opens the wheel with [actions]; the slots fold out from the button one after another. */
    fun show(context: Context, actions: List<GestureAction>, cx: Float, cy: Float, hw: Float, hh: Float) {
        end()
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val d = context.resources.displayMetrics
        slots = layout(actions, cx, cy, hw, hh, d.density, d.widthPixels.toFloat(), d.heightPixels.toFloat())
        if (slots.isEmpty()) return
        hitRadius = 34f * d.density
        selected.intValue = -1
        val params = OverlayWindows.params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            touchable = false,
            noLimits = true,
        )
        val o = OverlayOwner()
        val v = OverlayWindows.composeView(context, o) { WheelView(slots, Offset(cx, cy), selected.intValue) }
        runCatching { wm.addView(v, params) }.onFailure { o.destroy(); return }
        view = v
        owner = o
    }

    /** Highlights the slot under the finger. Returns true when the highlighted slot changed. */
    fun move(x: Float, y: Float): Boolean {
        val i = slots.indices.minByOrNull { hypot(slots[it].x - x, slots[it].y - y) }
            ?.takeIf { hypot(slots[it].x - x, slots[it].y - y) <= hitRadius } ?: -1
        if (i == selected.intValue) return false
        selected.intValue = i
        return i >= 0
    }

    /** Closes the wheel and returns the chosen action, if the finger was on one. */
    fun end(): GestureAction? {
        val chosen = slots.getOrNull(selected.intValue)?.action
        view?.let { v -> runCatching { v.context.getSystemService(WindowManager::class.java)?.removeView(v) } }
        owner?.destroy()
        view = null
        owner = null
        slots = emptyList()
        selected.intValue = -1
        return chosen
    }
}

private fun shortLabel(context: Context, a: GestureAction): String = when (a.type) {
    GestureType.OPEN_APP -> AppInfoCache.label(context, a.arg)
    GestureType.PASTE_TEXT -> "\"" + a.arg.take(12) + "\""
    else -> a.label.ifBlank { a.type.label }
}

@Composable
private fun WheelView(slots: List<ActionWheel.Slot>, center: Offset, selected: Int) {
    ThumbTheme {
        val context = LocalContext.current
        val accent = Color(currentAccent(context, SettingsRepo.current))
        val open = remember { Animatable(0f) }
        LaunchedEffect(Unit) { open.animateTo(1f, tween(380)) }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = (open.value * 3f).coerceAtMost(1f) }) {
            Canvas(Modifier.fillMaxSize()) {
                // Faint guide lines from the button to each slot.
                if (open.value >= 1f) slots.forEachIndexed { i, sl ->
                    drawLine(accent.copy(alpha = if (i == selected) 0.7f else 0.15f), center, Offset(sl.x, sl.y), if (i == selected) 4f else 2f)
                }
                drawCircle(accent.copy(alpha = 0.25f), radius = 14f, center = center, style = Stroke(3f))
            }
            slots.forEachIndexed { i, sl ->
                val on = i == selected
                val sizeDp = 60.dp
                // Fold out: each slot travels from the button to its place, a little after the one before.
                val p = ((open.value * 1.6f) - i * 0.6f / slots.size.coerceAtLeast(1)).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
                Box(
                    Modifier
                        .offset {
                            val x = center.x + (sl.x - center.x) * p
                            val y = center.y + (sl.y - center.y) * p
                            IntOffset((x - sizeDp.toPx() / 2).toInt(), (y - sizeDp.toPx() / 2).toInt())
                        }
                        .size(sizeDp)
                        .graphicsLayer { alpha = p }
                        .scale((0.4f + 0.6f * p) * if (on) 1.18f else 1f)
                        .background(if (on) accent else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f), CircleShape)
                        .border(1.5.dp, accent.copy(alpha = 0.6f), CircleShape)
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sl.action.type == GestureType.OPEN_APP) {
                        AppIcon(sl.action.arg, Modifier.size(34.dp))
                    } else {
                        Text(
                            shortLabel(context, sl.action),
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
