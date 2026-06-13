package com.fortress.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.fortress.app.ui.theme.ProfitGreen
import com.fortress.app.ui.theme.RiskRed
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

fun money(value: Double): String {
    val sign = if (value < 0) "-" else ""
    return "$sign$${"%,.2f".format(abs(value))}"
}

fun signedMoney(value: Double): String {
    val sign = if (value >= 0) "+" else "-"
    return "$sign$${"%,.2f".format(abs(value))}"
}

fun pct(value: Double): String = "${"%.1f".format(value * 100)}%"

fun signedPct(value: Double): String {
    val sign = if (value >= 0) "+" else ""
    return "$sign${"%.1f".format(value * 100)}%"
}

/** Minimal line chart for a normalized equity curve. */
@Composable
fun Sparkline(
    points: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = ProfitGreen
) {
    if (points.size < 2) return
    val up = points.last() >= points.first()
    val lineColor = if (up) ProfitGreen else RiskRed
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val min = points.min()
            val max = points.max()
            val range = (max - min).takeIf { it != 0.0 } ?: 1.0
            val dx = size.width / (points.size - 1)
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = dx * i
                val y = size.height - ((p - min) / range).toFloat() * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = if (color == ProfitGreen) lineColor else color,
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
        }
    }
}
