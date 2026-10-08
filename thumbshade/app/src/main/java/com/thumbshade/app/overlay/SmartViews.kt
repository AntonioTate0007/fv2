package com.thumbshade.app.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thumbshade.app.ai.AiHub
import com.thumbshade.app.ai.Entities
import com.thumbshade.app.ai.EntityActions
import com.thumbshade.app.ai.Replies
import com.thumbshade.app.ai.Urgency
import com.thumbshade.app.data.AppSettings
import com.thumbshade.app.notif.Extract
import com.thumbshade.app.notif.NotifOps
import com.thumbshade.app.notif.ShadeItem
import com.thumbshade.app.rules.Effects
import com.thumbshade.app.ui.LockGate
import java.time.ZonedDateTime

/** A summary line with a sparkle, tinted with the card's accent. */
@Composable
fun SummaryLine(text: String, accent: Color) {
    Row(
        Modifier
            .padding(top = 6.dp)
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.AutoAwesome, "Summary", Modifier.size(14.dp).padding(top = 2.dp), tint = accent)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ConversationSummary(item: ShadeItem, accent: Color) {
    val context = LocalContext.current
    val all by AiHub.summaries.collectAsState()
    val text = remember(item.key, item.messages.size, all) { AiHub.conversationSummary(context, item) }
    if (text != null) SummaryLine(text, accent)
}

@Composable
fun GroupSummary(appName: String, items: List<ShadeItem>, accent: Color) {
    val context = LocalContext.current
    val all by AiHub.summaries.collectAsState()
    val text = remember(items.map { it.key + it.postTime }, all) { AiHub.groupSummary(context, appName, items) }
    if (text != null) SummaryLine(text, accent)
}

/** "Urgent" / "Low" tag in the card header. */
@Composable
fun UrgencyBadge(u: Urgency) {
    val (label, color) = when (u) {
        Urgency.URGENT -> "Urgent" to MaterialTheme.colorScheme.error
        Urgency.LOW -> "Low" to MaterialTheme.colorScheme.outline
        else -> return
    }
    Text(
        label,
        fontSize = 10.sp,
        color = color,
        modifier = Modifier
            .padding(start = 6.dp)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** One-tap replies for messages you can answer from the shade. */
@Composable
fun SmartReplyChips(item: ShadeItem, s: AppSettings, lockScreen: Boolean) {
    val reply = item.replyAction ?: return
    if (!s.ai.smartReplies) return
    val context = LocalContext.current
    val last = item.messages.lastOrNull()?.text ?: item.displayText
    val suggestions = remember(item.key, last, item.smartReplies) { Replies.suggest(last, item.smartReplies) }
    if (suggestions.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        suggestions.forEach { r ->
            SuggestionChip(
                onClick = {
                    val send = {
                        val text = when (r.kind) {
                            Replies.Kind.TEXT -> r.text
                            Replies.Kind.LOCATION -> AiHub.locationLink(context)?.let { "I'm here: $it" }
                        }
                        if (text == null) {
                            Effects.toast(context, "Allow location for ThumbShade (Smart tab) to share it")
                        } else {
                            val ok = NotifOps.reply(context, reply, text, item)
                            Effects.toast(context, if (ok) "Sent “$text”" else "Could not send")
                        }
                    }
                    if (lockScreen && !s.replyOnLock) LockGate.unlockThen(context, send) else send()
                },
                label = { Text(r.text, maxLines = 1) },
                icon = if (r.kind == Replies.Kind.LOCATION) ({ Icon(Icons.Filled.MyLocation, null, Modifier.size(16.dp)) }) else null,
            )
        }
    }
}

/** Buttons for what the notification mentions: an event, a parcel, a flight, an address, a code. */
@Composable
fun SmartActionChips(item: ShadeItem, s: AppSettings) {
    if (!s.ai.smartActions) return
    val context = LocalContext.current
    val text = item.allText
    val entities = remember(item.key, text) { Entities.find(text, ZonedDateTime.now()) }
    val code = remember(item.key, text) { Extract.code(text) }
    if (entities.isEmpty() && code == null && item.smartActions.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (code != null) {
            AssistChip(
                onClick = {
                    NotifOps.copy(context, "Code", code)
                    Effects.toast(context, "Copied $code")
                },
                label = { Text("Copy $code") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
        entities.forEach { e ->
            val icon = when (e) {
                is Entities.Event -> Icons.Filled.Event
                is Entities.Tracking -> Icons.Filled.LocalShipping
                is Entities.Flight -> Icons.Filled.Flight
                is Entities.Address -> Icons.Filled.Place
                is Entities.Email -> Icons.Filled.Mail
            }
            AssistChip(
                onClick = { EntityActions.run(context, e, item.title.ifBlank { item.appName }) },
                label = { Text(EntityActions.chipLabel(e), maxLines = 1) },
                leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
        // Actions Android's assistant added (e.g. "Open in Maps").
        item.smartActions.filterNot { it.isReply }.forEach { a ->
            AssistChip(
                onClick = { NotifOps.press(context, a, item) },
                label = { Text(a.title, maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
}
