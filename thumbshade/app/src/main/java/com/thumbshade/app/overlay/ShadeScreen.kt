package com.thumbshade.app.overlay

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.thumbshade.app.data.CardBg
import com.thumbshade.app.data.CardStyle
import com.thumbshade.app.data.HeaderIcon
import com.thumbshade.app.data.TextAlignChoice
import com.thumbshade.app.ui.clockTime
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.thumbshade.app.data.AppSettings
import com.thumbshade.app.data.BrowseStyle
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.data.ShadeAnim
import com.thumbshade.app.notif.Extract
import com.thumbshade.app.notif.MediaHub
import com.thumbshade.app.notif.NAction
import com.thumbshade.app.notif.NotifOps
import com.thumbshade.app.notif.NotificationRepo
import com.thumbshade.app.notif.ShadeFilter
import com.thumbshade.app.notif.ShadeItem
import com.thumbshade.app.rules.Effects
import com.thumbshade.app.rules.HoldStore
import com.thumbshade.app.ui.AppIcon
import com.thumbshade.app.ui.MainActivity
import com.thumbshade.app.ui.NotifIcon
import com.thumbshade.app.ui.ThumbTheme
import com.thumbshade.app.ui.relativeTime
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private enum class Panel { NONE, REPLY, SNOOZE, ACTIONS, MENU }

private fun enterFor(anim: ShadeAnim): EnterTransition = when (anim) {
    ShadeAnim.SLIDE -> slideInVertically { it } + fadeIn()
    ShadeAnim.FADE -> fadeIn()
    ShadeAnim.SCALE -> scaleIn(initialScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeIn()
    ShadeAnim.EXPAND -> expandVertically(expandFrom = Alignment.Bottom) + fadeIn()
    ShadeAnim.BOUNCE -> slideInVertically(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) { it }
    ShadeAnim.DROP -> slideInVertically { -it / 3 } + fadeIn()
    ShadeAnim.NONE -> EnterTransition.None
    ShadeAnim.ZOOM -> scaleIn(initialScale = 0.3f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeIn()
    ShadeAnim.POP -> scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn()
    ShadeAnim.ELASTIC -> slideInVertically(spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow)) { it / 2 } + fadeIn()
    ShadeAnim.GLIDE -> slideInHorizontally { it } + fadeIn()
}

private fun exitFor(anim: ShadeAnim): ExitTransition = when (anim) {
    ShadeAnim.SLIDE, ShadeAnim.BOUNCE -> slideOutVertically { it } + fadeOut()
    ShadeAnim.FADE -> fadeOut()
    ShadeAnim.SCALE -> scaleOut(targetScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeOut()
    ShadeAnim.EXPAND -> shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
    ShadeAnim.DROP -> slideOutVertically { it / 3 } + fadeOut()
    ShadeAnim.NONE -> ExitTransition.None
    ShadeAnim.ZOOM -> scaleOut(targetScale = 0.3f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeOut()
    ShadeAnim.POP -> scaleOut(targetScale = 0.7f) + fadeOut()
    ShadeAnim.ELASTIC -> slideOutVertically { it / 2 } + fadeOut()
    ShadeAnim.GLIDE -> slideOutHorizontally { -it } + fadeOut()
}

@Composable
fun ShadeScreen(
    visibleState: MutableTransitionState<Boolean>,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ThumbTheme {
        val s by SettingsRepo.state.collectAsState()
        val all by NotificationRepo.items.collectAsState()
        val media by MediaHub.state.collectAsState()
        val held by HoldStore.held.collectAsState()
        val entries = remember(all, s) { ShadeFilter.entries(all, s) }
        val dim by animateFloatAsState(if (visibleState.targetState) s.dimBehind else 0f, label = "dim")

        LaunchedEffect(entries.isEmpty(), media == null) {
            if (s.closeWhenEmpty && entries.isEmpty() && media == null && visibleState.currentState) {
                delay(700)
                onClose()
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = dim))
                .pointerInput(Unit) { detectTapGestures { onClose() } },
        ) {
            AnimatedVisibility(
                visibleState = visibleState,
                enter = enterFor(s.shadeAnim),
                exit = exitFor(s.shadeAnim),
                modifier = Modifier.align(
                    when (s.shadeAlign) {
                        com.thumbshade.app.data.ShadeAlign.LEFT -> Alignment.BottomStart
                        com.thumbshade.app.data.ShadeAlign.CENTER -> Alignment.BottomCenter
                        com.thumbshade.app.data.ShadeAlign.RIGHT -> Alignment.BottomEnd
                    }
                ),
            ) {
                ShadePanel(s, entries, media, held.size, onClose, onOpenSettings)
            }
        }
    }
}

@Composable
private fun ShadePanel(
    s: AppSettings,
    entries: List<ShadeFilter.Entry>,
    media: MediaHub.Media?,
    heldCount: Int,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val config = LocalConfiguration.current
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(entries.size) {
        if (s.newestAtBottom && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Column(
        Modifier
            .fillMaxWidth(s.shadeWidth)
            .heightIn(max = config.screenHeightDp.dp * s.shadeMaxHeight)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            // Swallow taps on the panel so they don't close the shade.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (entries.isEmpty()) "No notifications" else "${entries.size} notification" + if (entries.size == 1) "" else "s",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (heldCount > 0) {
                TextButton(onClick = {
                    val n = HoldStore.releaseAll()
                    Effects.toast(context, "Released $n")
                }) { Text("$heldCount held · release") }
            }
            Spacer(Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                IconButton(onClick = {
                    entries.forEach { e ->
                        when (e) {
                            is ShadeFilter.Single -> NotifOps.dismiss(e.item)
                            is ShadeFilter.Group -> e.items.forEach(NotifOps::dismiss)
                        }
                    }
                }) { Icon(Icons.Filled.ClearAll, "Clear all", tint = MaterialTheme.colorScheme.onSurface) }
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurface) }
        }

        if (s.showMedia && media != null) MediaCard(media, s)

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(s.rowSpacingDp.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(entries, key = { it.id }) { entry ->
                Box(Modifier.browse(listState, entry.id, s.browseStyle)) {
                    when (entry) {
                        is ShadeFilter.Single -> NotificationCard(entry.item, s, onClose)
                        is ShadeFilter.Group -> GroupCard(entry, s, onClose)
                    }
                }
            }
        }
    }
}

/**
 * Browsing styles: each card is transformed by how far it sits from the middle of the list
 * (frac -1 at the top edge .. 1 at the bottom edge).
 */
private fun Modifier.browse(state: LazyListState, key: Any, style: BrowseStyle): Modifier =
    if (style == BrowseStyle.LIST) this else graphicsLayer {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@graphicsLayer
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat().coerceAtLeast(1f)
        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val f = ((item.offset + item.size / 2f - center) / viewport).coerceIn(-1f, 1f)
        val a = abs(f)
        cameraDistance = 14f * density
        when (style) {
            BrowseStyle.LIST -> Unit
            BrowseStyle.WHEEL -> {
                rotationX = -f * 60f
                scaleX = 1f - a * 0.2f; scaleY = scaleX
                alpha = 1f - a * 0.45f
            }
            BrowseStyle.COVERFLOW -> {
                rotationX = -f * 40f
                scaleX = 1f - a * 0.12f; scaleY = scaleX
            }
            BrowseStyle.SPOTLIGHT -> {
                alpha = 1f - a * 0.75f
                scaleX = 1f - a * 0.08f; scaleY = scaleX
            }
            BrowseStyle.FAN -> {
                transformOrigin = TransformOrigin(0.5f, 1.6f)
                rotationZ = f * 14f
            }
            BrowseStyle.WAVE -> translationX = sin(f * PI.toFloat()) * 36f * density
            BrowseStyle.CASCADE -> translationX = f * 48f * density
            BrowseStyle.SWAY -> {
                transformOrigin = TransformOrigin(0.5f, 0f)
                rotationZ = sin(f * PI.toFloat()) * 7f
            }
            BrowseStyle.TUMBLE -> {
                rotationZ = f * 25f
                alpha = 1f - a * 0.5f
                scaleX = 1f - a * 0.15f; scaleY = scaleX
            }
            BrowseStyle.HELIX -> {
                rotationY = f * 55f
                translationX = sin(f * PI.toFloat()) * 20f * density
            }
        }
    }

private fun pick(color: Long, auto: Color): Color = if (color != 0L) Color(color) else auto

private fun TextAlignChoice.toAlign(): TextAlign = when (this) {
    TextAlignChoice.START -> TextAlign.Start
    TextAlignChoice.CENTER -> TextAlign.Center
    TextAlignChoice.END -> TextAlign.End
}

private fun accentFor(item: ShadeItem?, s: AppSettings, fallback: Color): Color {
    item?.let { s.perAppColor[it.pkg] }?.let { return Color(it) }
    if (item != null && item.color != 0) return Color(item.color).copy(alpha = 1f)
    return pick(s.card.fallbackColor, fallback)
}

/** The card's background, from the card style. */
@Composable
private fun cardBrush(s: AppSettings, accent: Color): Brush {
    val c = s.card
    val theme = MaterialTheme.colorScheme.surfaceContainer
    return when (c.bgSource) {
        CardBg.THEME -> SolidColor(theme)
        CardBg.CUSTOM -> SolidColor(Color(c.bgColor))
        CardBg.NOTIFICATION -> SolidColor(accent.copy(alpha = 0.22f).compositeOver(theme))
        CardBg.GRADIENT -> Brush.linearGradient(listOf(Color(c.bgColor), Color(c.gradientEnd)))
    }
}

/** Shape, background, border and click handling shared by every card. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.card(s: AppSettings, accent: Color, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
    val shape = RoundedCornerShape(s.cardCornerDp.dp)
    val c = s.card
    val borderColor = if (c.borderFromNotification) accent else Color(c.borderColor)
    return this
        .fillMaxWidth()
        .clip(shape)
        .background(cardBrush(s, accent), shape)
        .then(if (c.borderWidthDp > 0) Modifier.border(c.borderWidthDp.dp, borderColor, shape) else Modifier)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationCard(item: ShadeItem, s: AppSettings, onClose: () -> Unit) {
    if (s.swipeToDismiss && item.clearable) {
        val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
            if (v != SwipeToDismissBoxValue.Settled) {
                NotifOps.dismiss(item)
                true
            } else false
        })
        SwipeToDismissBox(state = state, backgroundContent = {}) {
            CardBody(item, s, onClose)
        }
    } else {
        CardBody(item, s, onClose)
    }
}

@Composable
private fun HeaderIconView(item: ShadeItem, c: CardStyle, accent: Color) {
    val size = c.headerIconDp.dp
    when (c.headerIcon) {
        HeaderIcon.NONE -> return
        HeaderIcon.SMALL -> if (item.smallIcon != null) {
            NotifIcon(item.smallIcon, item.key + ":s:" + item.postTime, Modifier.size(size), tint = accent)
        } else AppIcon(item.pkg, Modifier.size(size))
        HeaderIcon.APP -> AppIcon(item.pkg, Modifier.size(size))
        HeaderIcon.SENDER -> {
            val picture = item.senderIcon ?: item.largeIcon
            Box(Modifier.size(size)) {
                if (picture != null) {
                    NotifIcon(picture, item.key + ":p:" + item.postTime, Modifier.fillMaxSize().clip(CircleShape))
                } else {
                    AppIcon(item.pkg, Modifier.fillMaxSize())
                }
                if (c.appBadge && picture != null) {
                    AppIcon(
                        item.pkg,
                        Modifier
                            .size(size * 0.45f)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape),
                    )
                }
            }
        }
    }
    Spacer(Modifier.width(8.dp))
}

@Composable
private fun CardButton(label: String, c: CardStyle, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(c.buttonCornerDp.dp)
    Box(
        Modifier
            .padding(end = 6.dp, top = 4.dp)
            .clip(shape)
            .then(if (c.buttonBackground) Modifier.background(Color(c.buttonBgColor), shape) else Modifier)
            .then(if (c.buttonBorder) Modifier.border(c.buttonBorderDp.dp, Color(c.buttonBorderColor), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = (c.buttonPaddingDp + 4).dp, vertical = c.buttonPaddingDp.dp),
    ) {
        Text(
            label,
            color = pick(c.buttonColor, accent),
            fontSize = c.buttonSp.sp,
            fontWeight = if (c.buttonBold) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun CardBody(item: ShadeItem, s: AppSettings, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = s.card
    var panel by remember(item.key) { mutableStateOf(Panel.NONE) }
    var replyTo by remember(item.key) { mutableStateOf<NAction?>(null) }
    val accent = accentFor(item, s, MaterialTheme.colorScheme.primary)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyLines = s.perAppLines[item.pkg] ?: if (c.limitBodyLines) s.bodyMaxLines else Int.MAX_VALUE

    Column(
        Modifier
            .card(
                s, accent,
                onClick = {
                    NotifOps.open(context, item)
                    if (s.closeAfterOpen) onClose()
                },
                onLongClick = { panel = if (panel == Panel.MENU) Panel.NONE else Panel.MENU },
            )
            .padding(horizontal = (c.paddingDp + 2).dp, vertical = c.paddingDp.dp),
    ) {
        // Header: icon, app name, subtitle, time.
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderIconView(item, c, accent)
            if (c.showAppName) {
                Text(
                    item.appName,
                    color = pick(c.appNameColor, accent),
                    fontSize = c.appNameSp.sp,
                    fontWeight = if (c.appNameBold) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
            if (c.showSubtitle && c.subtitleInHeader && item.subText.isNotBlank()) {
                Text(
                    (if (c.showAppName) " · " else "") + item.subText,
                    color = pick(c.subtitleColor, secondary),
                    fontSize = c.subtitleSp.sp,
                    fontWeight = if (c.subtitleBold) FontWeight.Bold else FontWeight.Normal,
                    maxLines = c.headerLines.coerceIn(1, 3),
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            if (c.showTime && item.showWhen) {
                Text(
                    if (c.clockTime) clockTime(item.postTime) else relativeTime(item.postTime),
                    color = pick(c.timeColor, secondary),
                    fontSize = c.timeSp.sp,
                    fontWeight = if (c.timeBold) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }

        val chat = item.messages.isNotEmpty()
        val bigIcon = when {
            !s.showLargeIcon -> null
            chat && c.senderPicture && !(c.hideSenderIfInHeader && c.headerIcon == HeaderIcon.SENDER) -> item.senderIcon ?: item.largeIcon
            chat && c.hideSenderIfInHeader && c.headerIcon == HeaderIcon.SENDER -> null
            else -> item.largeIcon
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (c.showTitle && item.title.isNotBlank()) {
                    Text(
                        item.title,
                        color = pick(c.titleColor, onSurface),
                        fontSize = c.titleSp.sp,
                        fontWeight = if (c.titleBold) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = c.titleLines.coerceAtLeast(1),
                        overflow = TextOverflow.Ellipsis,
                        textAlign = c.titleAlign.toAlign(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (c.showSubtitle && !c.subtitleInHeader && item.subText.isNotBlank()) {
                    Text(
                        item.subText,
                        color = pick(c.subtitleColor, secondary),
                        fontSize = c.subtitleSp.sp,
                        fontWeight = if (c.subtitleBold) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (c.showBody) {
                    val bodyStyle = Modifier.fillMaxWidth()
                    if (chat) {
                        item.messages.takeLast(bodyLines.coerceIn(1, 50)).forEach { m ->
                            // In one-to-one chats the sender is the title; don't repeat it on every line.
                            val showSender = m.sender.isNotBlank() && !(c.hideTitleFromBody && m.sender == item.title)
                            Text(
                                (if (showSender) m.sender + ": " else "") + m.text,
                                color = pick(c.bodyColor, secondary),
                                fontSize = c.bodySp.sp,
                                fontWeight = if (c.bodyBold) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = c.bodyAlign.toAlign(),
                                modifier = bodyStyle,
                            )
                        }
                    } else if (item.displayText.isNotBlank() && !(c.hideTitleFromBody && item.displayText == item.title)) {
                        Text(
                            item.displayText,
                            color = pick(c.bodyColor, secondary),
                            fontSize = c.bodySp.sp,
                            fontWeight = if (c.bodyBold) FontWeight.Bold else FontWeight.Normal,
                            maxLines = bodyLines,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = c.bodyAlign.toAlign(),
                            modifier = bodyStyle,
                        )
                    }
                }
            }
            if (bigIcon != null) {
                Spacer(Modifier.width(10.dp))
                NotifIcon(
                    bigIcon,
                    item.key + ":l:" + item.postTime + bigIcon.hashCode(),
                    Modifier
                        .size(c.largeIconDp.dp)
                        .clip(if (c.roundLargeIcon) CircleShape else RoundedCornerShape(12.dp)),
                )
            }
        }
        val pic = item.picture
        if (s.showPictures && pic != null) {
            Image(
                bitmap = remember(pic) { pic.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
        if (item.progressMax > 0 || item.progressIndeterminate) {
            val barColor = pick(c.progressColor, accent)
            if (item.progressIndeterminate) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = barColor)
            } else {
                LinearProgressIndicator(
                    progress = { item.progress / item.progressMax.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = barColor,
                )
            }
        }
        if (s.showActions) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                item.actions.forEach { a ->
                    CardButton(a.title, c, accent) {
                        if (a.isReply) {
                            replyTo = a
                            panel = Panel.REPLY
                        } else {
                            NotifOps.press(context, a)
                        }
                    }
                }
                IconButton(onClick = { panel = if (panel == Panel.SNOOZE) Panel.NONE else Panel.SNOOZE }) {
                    Icon(Icons.Filled.Snooze, "Snooze", tint = secondary)
                }
                CardButton("Actions", c.copy(buttonColor = 0, buttonBackground = false, buttonBorder = false), secondary) {
                    panel = if (panel == Panel.ACTIONS) Panel.NONE else Panel.ACTIONS
                }
            }
        }
        when (panel) {
            Panel.NONE -> Unit
            Panel.REPLY -> replyTo?.let { a -> ReplyRow(a) { panel = Panel.NONE } }
            Panel.SNOOZE -> SnoozeRow(item, s) { panel = Panel.NONE }
            Panel.ACTIONS -> ExtractPanel(item)
            Panel.MENU -> MenuRow(item, s, onClose) { panel = Panel.NONE }
        }
    }
}

@Composable
private fun ReplyRow(action: NAction, done: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(action.title.ifBlank { "Reply" }) },
            modifier = Modifier.weight(1f),
            maxLines = 4,
        )
        IconButton(onClick = {
            if (text.isNotBlank()) {
                val ok = NotifOps.reply(context, action, text)
                Effects.toast(context, if (ok) "Sent" else "Could not send")
                done()
            }
        }) { Icon(Icons.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun SnoozeRow(item: ShadeItem, s: AppSettings, done: () -> Unit) {
    val context = LocalContext.current
    val options = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours", s.customSnoozeMinutes to "${s.customSnoozeMinutes} min")
        .distinctBy { it.first }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (minutes, label) ->
            AssistChip(onClick = {
                NotifOps.snooze(item, minutes)
                Effects.toast(context, "Snoozed for $label")
                done()
            }, label = { Text(label) })
        }
    }
}

@Composable
private fun ExtractPanel(item: ShadeItem) {
    val context = LocalContext.current
    val text = item.allText
    val code = remember(text) { Extract.code(text) }
    val links = remember(text) { Extract.links(text) }
    val phones = remember(text) { Extract.phones(text) }
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (code != null) {
            ExtractRow("Code $code", Icons.Filled.ContentCopy) {
                NotifOps.copy(context, "Code", code)
                Effects.toast(context, "Copied $code")
            }
        }
        links.forEach { url -> ExtractRow(url, Icons.Filled.OpenInNew) { NotifOps.openUrl(context, url) } }
        phones.forEach { p -> ExtractRow(p, Icons.Filled.Phone) { NotifOps.dial(context, p) } }
        ExtractRow("Copy the message", Icons.Filled.ContentCopy) {
            NotifOps.copy(context, item.title, text)
            Effects.toast(context, "Copied")
        }
        ExtractRow("Share the message", Icons.Filled.Share) { NotifOps.share(context, text) }
    }
}

@Composable
private fun ExtractRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MenuRow(item: ShadeItem, s: AppSettings, onClose: () -> Unit, done: () -> Unit) {
    val context = LocalContext.current
    val pkg = item.pkg
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val pinned = pkg in s.pinTop
        AssistChip(onClick = {
            SettingsRepo.update { it.copy(pinTop = if (pinned) it.pinTop - pkg else it.pinTop + pkg, pinBottom = it.pinBottom - pkg) }
            done()
        }, label = { Text(if (pinned) "Unpin" else "Pin to top") }, leadingIcon = { Icon(Icons.Filled.PushPin, null, Modifier.size(16.dp)) })
        AssistChip(onClick = {
            SettingsRepo.update { it.copy(pinBottom = if (pkg in it.pinBottom) it.pinBottom - pkg else it.pinBottom + pkg, pinTop = it.pinTop - pkg) }
            done()
        }, label = { Text(if (pkg in s.pinBottom) "Unpin bottom" else "Pin to bottom") })
        AssistChip(onClick = {
            SettingsRepo.update { it.copy(groupApps = if (pkg in it.groupApps) it.groupApps - pkg else it.groupApps + pkg) }
            done()
        }, label = { Text(if (pkg in s.groupApps) "Ungroup app" else "Group app") }, leadingIcon = { Icon(Icons.Filled.ViewAgenda, null, Modifier.size(16.dp)) })
        AssistChip(onClick = {
            SettingsRepo.update { it.copy(excludeApps = it.excludeApps + pkg) }
            Effects.toast(context, "${item.appName} hidden from the shade")
            done()
        }, label = { Text("Hide app") }, leadingIcon = { Icon(Icons.Filled.Block, null, Modifier.size(16.dp)) })
        AssistChip(onClick = {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_NEW_RULE_FOR_APP)
                    .putExtra(MainActivity.EXTRA_PKG, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            onClose()
        }, label = { Text("Make a rule") }, leadingIcon = { Icon(Icons.Filled.Rule, null, Modifier.size(16.dp)) })
        AssistChip(onClick = {
            NotifOps.appInfo(context, pkg)
            onClose()
        }, label = { Text("App info") }, leadingIcon = { Icon(Icons.Filled.Info, null, Modifier.size(16.dp)) })
        AssistChip(onClick = {
            NotificationRepo.items.value.filter { it.pkg == pkg }.forEach(NotifOps::dismiss)
            done()
        }, label = { Text("Clear app") })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupCard(group: ShadeFilter.Group, s: AppSettings, onClose: () -> Unit) {
    val context = LocalContext.current
    val body = @Composable {
        Box(
            Modifier.card(
                s, accentFor(group.items.first(), s, MaterialTheme.colorScheme.primary),
                onClick = {
                    NotifOps.open(context, group.items.first())
                    if (s.closeAfterOpen) onClose()
                },
            ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(group.pkg, Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(group.appName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(
                        "${group.items.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                group.items.take(6).forEach { item ->
                    Text(
                        listOf(item.title, item.displayText).filter { it.isNotBlank() }.joinToString(": "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                NotifOps.open(context, item)
                                if (s.closeAfterOpen) onClose()
                            }
                            .padding(top = 4.dp),
                    )
                }
                if (group.items.size > 6) {
                    Text("+${group.items.size - 6} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (s.swipeToDismiss) {
        val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
            if (v != SwipeToDismissBoxValue.Settled) {
                group.items.forEach(NotifOps::dismiss)
                true
            } else false
        })
        SwipeToDismissBox(state = state, backgroundContent = {}) { body() }
    } else body()
}

@Composable
private fun MediaCard(m: MediaHub.Media, s: AppSettings) {
    val context = LocalContext.current
    var position by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(m) {
        while (true) {
            if (!dragging) position = m.livePosition().toFloat()
            delay(500)
        }
    }
    val art = m.art
    Box(Modifier.card(s, pick(s.card.mediaTint, MaterialTheme.colorScheme.primary), onClick = { MediaHub.openApp(context) })) {
        Box {
            if (s.albumArtBackground && art != null) {
                Image(
                    bitmap = remember(art) { art.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                    alpha = 0.55f,
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.75f)))),
                )
            }
            val onArt = s.albumArtBackground && art != null
            val fg = pick(s.card.mediaTint, if (onArt) Color.White else MaterialTheme.colorScheme.onSurface)
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(m.pkg, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(m.title, color = fg, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (m.can(android.media.session.PlaybackState.ACTION_REWIND) || m.can(android.media.session.PlaybackState.ACTION_SEEK_TO)) {
                        IconButton(onClick = { MediaHub.seekBy(-10_000) }) { Icon(Icons.Filled.Replay10, "Back 10 s", tint = fg) }
                        IconButton(onClick = { MediaHub.seekBy(10_000) }) { Icon(Icons.Filled.Forward10, "Forward 10 s", tint = fg) }
                    }
                }
                if (m.artist.isNotBlank()) {
                    Text(m.artist + if (m.album.isNotBlank()) " · " + m.album else "", color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (s.card.mediaNextTrack && m.nextTitle.isNotBlank()) {
                    Text("Next: " + m.nextTitle, color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (m.durationMs > 0) {
                    Slider(
                        value = position.coerceIn(0f, m.durationMs.toFloat()),
                        onValueChange = {
                            dragging = true
                            position = it
                        },
                        onValueChangeFinished = {
                            MediaHub.seekTo(position.toLong())
                            dragging = false
                        },
                        valueRange = 0f..m.durationMs.toFloat(),
                    )
                    Row {
                        Text(formatMs(position.toLong()), color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        Text(formatMs(m.durationMs), color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    m.customActions.take(2).forEach { ca ->
                        CustomActionButton(ca, fg)
                    }
                    IconButton(onClick = { MediaHub.previous() }) { Icon(Icons.Filled.SkipPrevious, "Previous", tint = fg, modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = { MediaHub.playPause() }, modifier = Modifier.size(56.dp)) {
                        Icon(if (m.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play / pause", tint = fg, modifier = Modifier.size(44.dp))
                    }
                    IconButton(onClick = { MediaHub.next() }) { Icon(Icons.Filled.SkipNext, "Next", tint = fg, modifier = Modifier.size(32.dp)) }
                    m.customActions.drop(2).take(2).forEach { ca ->
                        CustomActionButton(ca, fg)
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomActionButton(action: android.media.session.PlaybackState.CustomAction, tint: Color) {
    val context = LocalContext.current
    val bmp = remember(action.action, action.icon) {
        MediaHub.customIcon(context, action)?.let { d ->
            runCatching { d.toBitmap(64, 64).asImageBitmap() }.getOrNull()
        }
    }
    IconButton(onClick = { MediaHub.custom(action) }) {
        if (bmp != null) {
            Image(bmp, action.name?.toString(), Modifier.size(24.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint))
        } else {
            Text(action.name?.toString()?.take(2).orEmpty(), color = tint)
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

