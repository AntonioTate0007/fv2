package com.thumbshade.app.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thumbshade.app.data.ClusterSide
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.MediaHub
import com.thumbshade.app.notif.NotificationRepo
import com.thumbshade.app.notif.ShadeFilter
import com.thumbshade.app.ui.AppIcon
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun ButtonFace(pulse: Int, battery: Float?) {
    val s by SettingsRepo.state.collectAsState()
    val all by NotificationRepo.items.collectAsState()
    val media by MediaHub.state.collectAsState()
    val visible = remember(all, s) { ShadeFilter.visible(all, s) }
    val latest = visible.maxByOrNull { it.postTime }

    val scale = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            scale.snapTo(1.25f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow))
        }
    }

    val shape = RoundedCornerShape(percent = s.buttonCornerPercent.coerceIn(0, 50))
    val ringColor = Color(s.accentColor)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = s.buttonAlpha
            }
            .drawBehind {
                if (s.chargingRing && battery != null) {
                    val stroke = 3.dp.toPx()
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * battery,
                        useCenter = false,
                        style = Stroke(width = stroke),
                    )
                }
            }
            .padding(if (s.chargingRing && battery != null) 4.dp else 0.dp)
            .clip(shape)
            .background(Color(s.buttonColor))
            .border(s.buttonBorderDp.dp, Color(s.buttonBorderColor), shape),
        contentAlignment = Alignment.Center,
    ) {
        val m = media
        val art = m?.art
        when {
            s.showAlbumArt && m != null && m.playing && art != null -> Image(
                bitmap = remember(art) { art.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            s.showLatestIcon && latest != null -> AppIcon(latest.pkg, Modifier.fillMaxSize().padding(10.dp))
        }
        if (s.showCount && visible.isNotEmpty()) {
            Text(
                text = if (visible.size > 99) "99+" else visible.size.toString(),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = if (s.showLatestIcon || (s.showAlbumArt && m?.playing == true)) {
                    Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 5.dp)
                } else Modifier,
            )
        }
    }
}

/** App icons of the latest notifications, around, above or beside the button. */
@Composable
fun IconCluster() {
    val s by SettingsRepo.state.collectAsState()
    val all by NotificationRepo.items.collectAsState()
    val pkgs = remember(all, s) {
        ShadeFilter.visible(all, s).sortedByDescending { it.postTime }.map { it.pkg }.distinct()
    }
    val shown = pkgs.take(s.clusterMax)
    val overflow = pkgs.size - shown.size
    val density = LocalDensity.current
    val iconPx = with(density) { s.clusterIconDp.dp.toPx() }
    val gapPx = with(density) { 6.dp.toPx() }
    val bw = with(density) { s.buttonWidthDp.dp.toPx() }
    val bh = with(density) { s.buttonHeightDp.dp.toPx() }
    val mono = if (s.monochromeIcons) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null

    Box(Modifier.fillMaxSize()) {
        shown.forEachIndexed { i, pkg ->
            val offset: IntOffset = when (s.clusterSide) {
                ClusterSide.RING -> {
                    // Spread around an ellipse just outside the button, starting at the top.
                    val count = shown.size + if (overflow > 0) 1 else 0
                    val angle = Math.toRadians(-90.0 + i * 360.0 / count)
                    val rx = bw / 2 + gapPx / 2 + iconPx / 2
                    val ry = bh / 2 + gapPx / 2 + iconPx / 2
                    IntOffset((rx * cos(angle)).toInt(), (ry * sin(angle)).toInt())
                }
                ClusterSide.ABOVE -> {
                    val total = shown.size * (iconPx + gapPx)
                    IntOffset((-total / 2 + i * (iconPx + gapPx) + iconPx / 2).toInt(), 0)
                }
                ClusterSide.SIDE -> {
                    val total = shown.size * (iconPx + gapPx)
                    IntOffset(0, (-total / 2 + i * (iconPx + gapPx) + iconPx / 2).toInt())
                }
            }
            AppIcon(
                pkg,
                Modifier
                    .align(Alignment.Center)
                    .offset { offset }
                    .size(s.clusterIconDp.dp),
                colorFilter = mono,
            )
        }
        if (overflow > 0 && s.clusterSide == ClusterSide.RING) {
            val count = shown.size + 1
            val angle = Math.toRadians(-90.0 + shown.size * 360.0 / count)
            val r = min(bw, bh) / 2 + gapPx / 2 + iconPx / 2
            Text(
                "+$overflow",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset((r * cos(angle)).toInt(), (r * sin(angle)).toInt()) }
                    .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
