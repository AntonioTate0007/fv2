package com.thumbshade.app.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import com.thumbshade.app.data.ChargingAnim
import com.thumbshade.app.data.ChargingMode
import com.thumbshade.app.data.ColorSource
import com.thumbshade.app.data.MediaLook
import com.thumbshade.app.data.NotifAnim
import com.thumbshade.app.data.NumberAlign
import com.thumbshade.app.data.SnapStyle
import com.thumbshade.app.data.Themes
import com.thumbshade.app.ui.currentAccent
import kotlin.math.PI
import kotlin.math.abs
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ButtonFace(
    pulse: Int,
    battery: Float?,
    docked: Boolean,
    dockedRight: Boolean,
    shown: MutableTransitionState<Boolean>,
) {
    val s by SettingsRepo.state.collectAsState()
    val all by NotificationRepo.items.collectAsState()
    val media by MediaHub.state.collectAsState()
    val context = LocalContext.current
    val visible = remember(all, s) { ShadeFilter.visible(all, s) }
    val latest = visible.maxByOrNull { it.postTime }
    val theme = Themes.resolve(s, isSystemInDarkTheme())
    val accent = Color(currentAccent(context, s))
    val dockedLook = docked && s.dockedLook
    val half = docked && s.snapStyle == SnapStyle.HALF

    // New-notification animation.
    val scale = remember { Animatable(1f) }
    val hop = remember { Animatable(0f) }
    val tilt = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(pulse) {
        if (pulse == 0) return@LaunchedEffect
        val k = (s.newNotifIntensity / 100f).coerceIn(0.1f, 1f)
        when (s.newNotifAnim) {
            NotifAnim.NONE -> Unit
            NotifAnim.POP -> {
                scale.snapTo(1f + 0.45f * k)
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow))
            }
            NotifAnim.HOP -> {
                hop.animateTo(-28f * k, tween(140))
                hop.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMediumLow))
            }
            NotifAnim.WIGGLE -> {
                repeat(3) {
                    tilt.animateTo(20f * k, tween(70))
                    tilt.animateTo(-20f * k, tween(70))
                }
                tilt.animateTo(0f, tween(70))
            }
            NotifAnim.GLOW -> {
                glow.snapTo(k)
                glow.animateTo(0f, tween(1100))
            }
        }
    }

    AnimatedVisibility(visibleState = shown, enter = EnterTransition.None, exit = ExitTransition.None, modifier = Modifier.fillMaxSize()) {
        val appear by with(Anims) { progress(s.appearAnim) }
        // The button travels to and from the screen edge it sits nearest.
        val side = if (dockedRight) 1 else -1
        val shape: Shape = when {
            dockedLook -> RoundedCornerShape(percent = s.dockedCornerPercent.coerceIn(0, 50))
            s.perCorner -> RoundedCornerShape(
                topStart = s.cornerTopLeftDp.dp, topEnd = s.cornerTopRightDp.dp,
                bottomStart = s.cornerBottomLeftDp.dp, bottomEnd = s.cornerBottomRightDp.dp,
            )
            else -> RoundedCornerShape(percent = s.buttonCornerPercent.coerceIn(0, 50))
        }
        val notifColor = latest?.color?.takeIf { it != 0 }?.let { Color(it).copy(alpha = 1f) }
        val bg = when (s.buttonBgSource) {
            ColorSource.THEME -> Color(theme.card)
            ColorSource.CUSTOM -> Color(if (dockedLook) s.dockedColor else s.buttonColor)
            ColorSource.NOTIFICATION -> notifColor ?: Color(s.buttonColor)
            ColorSource.NONE -> Color.Transparent
        }
        val border: Color? = when (s.buttonBorderSource) {
            ColorSource.THEME -> accent
            ColorSource.CUSTOM -> Color(s.buttonBorderColor)
            ColorSource.NOTIFICATION -> notifColor ?: Color(s.buttonBorderColor)
            ColorSource.NONE -> null
        }
        val charging = s.chargingMode != ChargingMode.NONE && battery != null
        val ringWidth = s.chargingThicknessDp.dp
        val infinite = rememberInfiniteTransition(label = "charging")
        val sweep by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "sweep")
        val breathe by infinite.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breathe")

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .graphicsLayer { with(Anims) { apply(s.appearAnim, appear, side) } }
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationY = hop.value * density
                    rotationZ = tilt.value
                    alpha = if (dockedLook) s.dockedAlpha else s.buttonAlpha
                }
                .drawBehind {
                    if (glow.value > 0f) {
                        drawCircle(accent.copy(alpha = 0.55f * glow.value), radius = size.minDimension / 2 + 10.dp.toPx() * glow.value)
                    }
                    if (charging && battery != null) {
                        val stroke = ringWidth.toPx()
                        val ringAlpha = if (s.chargingAnim == ChargingAnim.BREATHE) breathe else 1f
                        val start = if (s.chargingAnim == ChargingAnim.SWEEP) sweep - 90f else -90f
                        val sweepAngle = if (s.chargingMode == ChargingMode.PROGRESS) 360f * battery else 360f
                        drawArc(
                            color = accent.copy(alpha = ringAlpha),
                            startAngle = start,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            style = Stroke(width = stroke),
                        )
                    }
                },
        ) {
            val w = maxWidth
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(if (charging) ringWidth + 2.dp else 0.dp)
                    .clip(shape)
                    .background(bg)
                    .then(if (border != null && s.buttonBorderDp > 0) Modifier.border(s.buttonBorderDp.dp, border, shape) else Modifier),
            ) {
                // When half tucked behind the edge, keep the content in the visible half.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = if (half && !dockedRight) w / 2 else 0.dp,
                            end = if (half && dockedRight) w / 2 else 0.dp,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedIconView(
                        s.animatedIcon,
                        if (s.animatedIconColor != 0L) Color(s.animatedIconColor) else accent,
                        Modifier.fillMaxSize(),
                    )
                    val m = media
                    val showMedia = s.mediaLook != MediaLook.NOTHING && m != null && (m.playing || !s.mediaOnlyPlaying)
                    when {
                        showMedia && m != null -> MediaFace(m, s.mediaLook, Color(s.mediaAnimColor), s.mediaDimPercent)
                        s.showLatestIcon && latest != null -> AppIcon(latest.pkg, Modifier.fillMaxSize().padding(10.dp))
                    }
                    val showNumber = s.showCount && visible.isNotEmpty() && !(s.numberHideSingle && visible.size == 1)
                    if (showNumber) {
                        val covered = showMedia || (s.showLatestIcon && latest != null)
                        val align = when (s.numberAlign) {
                            NumberAlign.CENTER -> if (covered) Alignment.BottomEnd else Alignment.Center
                            NumberAlign.TOP_START -> Alignment.TopStart
                            NumberAlign.TOP_END -> Alignment.TopEnd
                            NumberAlign.BOTTOM_START -> Alignment.BottomStart
                            NumberAlign.BOTTOM_END -> Alignment.BottomEnd
                        }
                        Text(
                            text = if (visible.size > 99) "99+" else visible.size.toString(),
                            color = Color(s.numberColor),
                            fontSize = s.numberSizeSp.sp,
                            fontWeight = if (s.numberBold) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .align(align)
                                .then(
                                    if (covered || align != Alignment.Center) {
                                        Modifier
                                            .padding(2.dp)
                                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                            .padding(horizontal = 5.dp)
                                    } else Modifier
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** What the button shows while media plays. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaFace(m: MediaHub.Media, look: MediaLook, tint: Color, dimPercent: Int) {
    val infinite = rememberInfiniteTransition(label = "media")
    val spin by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "spin")
    val phase by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "phase")
    val rotation = if (m.playing) spin else 0f
    val t = if (m.playing) phase else 0.25f
    val art = m.art?.let { bmp -> remember(bmp) { bmp.asImageBitmap() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (look) {
            MediaLook.NOTHING -> Unit
            MediaLook.ALBUM_ART -> if (art != null) {
                Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            MediaLook.RECORD, MediaLook.CD -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .graphicsLayer { rotationZ = rotation }
                    .clip(CircleShape)
                    .background(
                        if (look == MediaLook.RECORD) {
                            Brush.radialGradient(listOf(Color(0xFF2A2A2A), Color(0xFF0B0B0B)))
                        } else {
                            Brush.sweepGradient(listOf(Color(0xFFD7DCE0), Color(0xFF9AA4AC), Color(0xFFF2F4F5), Color(0xFFB8C0C6), Color(0xFFD7DCE0)))
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) {
                    Image(
                        art, null, contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize(if (look == MediaLook.RECORD) 0.5f else 0.85f)
                            .clip(CircleShape),
                    )
                }
                Canvas(Modifier.fillMaxSize()) {
                    if (look == MediaLook.RECORD) {
                        for (i in 1..4) drawCircle(Color.White.copy(alpha = 0.06f), radius = size.minDimension / 2 * (0.55f + i * 0.1f), style = Stroke(1f))
                    }
                    drawCircle(Color(0xFF111111), radius = size.minDimension * 0.06f)
                }
            }
            MediaLook.TAPE -> Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val cy = size.height / 2
                drawRoundRect(tint.copy(alpha = 0.25f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                for (cx in listOf(size.width * 0.3f, size.width * 0.7f)) {
                    val r = size.minDimension * 0.18f
                    drawCircle(tint, radius = r, center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
                    for (k in 0 until 3) {
                        val a = Math.toRadians((rotation + k * 120).toDouble())
                        drawLine(tint, Offset(cx, cy), Offset(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat()), strokeWidth = 2.dp.toPx())
                    }
                }
            }
            MediaLook.EQUALIZER -> Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val bars = 4
                val bw = size.width / (bars * 2 - 1)
                for (i in 0 until bars) {
                    val level = 0.25f + 0.75f * abs(sin((t * 2 * PI + i * 1.3).toFloat()))
                    val bh = size.height * level
                    drawRoundRect(
                        tint, topLeft = Offset(i * 2 * bw, size.height - bh), size = Size(bw, bh),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 3),
                    )
                }
            }
            MediaLook.PULSE -> Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(tint.copy(alpha = 0.3f * (1f - t)), radius = r * (0.4f + 0.6f * t))
                drawCircle(tint, radius = r * 0.3f)
            }
            MediaLook.WAVE -> Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val path = Path()
                val steps = 40
                for (i in 0..steps) {
                    val x = size.width * i / steps
                    val y = size.height / 2 + size.height * 0.3f * sin((i / steps.toFloat() * 2 * PI + t * 2 * PI).toFloat())
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, tint, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            MediaLook.NOTES -> {
                Text("♪", color = tint, fontSize = 20.sp, modifier = Modifier.graphicsLayer { translationY = -20f * t * density; alpha = 1f - t; translationX = -6f * density })
                Text("♫", color = tint, fontSize = 16.sp, modifier = Modifier.graphicsLayer { val u = (t + 0.5f) % 1f; translationY = -20f * u * density; alpha = 1f - u; translationX = 8f * density })
            }
            MediaLook.TICKER -> Text(
                m.title.ifBlank { m.artist },
                color = tint,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE).padding(horizontal = 4.dp),
            )
        }
        if (dimPercent > 0 && (look == MediaLook.ALBUM_ART)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimPercent / 100f)))
        }
    }
}

/** App icons of the latest notifications, around, above or beside the button. */
@Composable
fun IconCluster(pulse: Int = 0, shadeClosed: Int = 0) {
    val s by SettingsRepo.state.collectAsState()
    // 0 = spread out, 1 = folded into the button. Each new notification spreads them again.
    val fold = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(pulse, s.clusterFold, s.clusterFoldSeconds) {
        fold.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
        if (s.clusterFold && s.clusterFoldSeconds > 0) {
            kotlinx.coroutines.delay(s.clusterFoldSeconds * 1000L)
            fold.animateTo(1f, tween(500))
        }
    }
    // You've looked at them in the shade: tuck them away until something new arrives.
    LaunchedEffect(shadeClosed) {
        if (shadeClosed > 0 && SettingsRepo.current.clusterFoldAfterShade) fold.animateTo(1f, tween(400))
    }
    val spread = 1f - fold.value
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
                    .offset { IntOffset((offset.x * spread).toInt(), (offset.y * spread).toInt()) }
                    .size(s.clusterIconDp.dp)
                    .graphicsLayer {
                        scaleX = 0.3f + 0.7f * spread
                        scaleY = scaleX
                        alpha = spread
                    },
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
                    .offset { IntOffset((r * cos(angle) * spread).toInt(), (r * sin(angle) * spread).toInt()) }
                    .graphicsLayer { alpha = spread }
                    .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
