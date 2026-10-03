package com.thumbshade.app.notif

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.thumbshade.app.rules.Categorize
import com.thumbshade.app.rules.Cat
import com.thumbshade.app.rules.NotifFacts

data class NAction(
    val title: String,
    val intent: PendingIntent?,
    val remoteInputs: List<RemoteInput>,
) {
    val isReply: Boolean get() = remoteInputs.any { it.allowFreeFormInput }
}

data class Msg(val sender: String, val text: String)

/** One notification as the shade shows it. Built once per post from the StatusBarNotification. */
data class ShadeItem(
    val key: String,
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val bigText: String,
    val subText: String,
    val postTime: Long,
    val showWhen: Boolean,
    val color: Int,
    val smallIcon: Icon?,
    val largeIcon: Icon?,
    val picture: Bitmap?,
    val actions: List<NAction>,
    val contentIntent: PendingIntent?,
    val ongoing: Boolean,
    val clearable: Boolean,
    val groupSummary: Boolean,
    val groupKey: String,
    val category: Cat,
    val importance: Int,
    val matchesDnd: Boolean,
    val mediaToken: MediaSession.Token?,
    val progress: Int,
    val progressMax: Int,
    val progressIndeterminate: Boolean,
    val messages: List<Msg>,
    val conversationTitle: String,
    val isGroupConversation: Boolean,
    val people: List<Person>,
    val channelId: String,
    val silent: Boolean,
    val sbn: StatusBarNotification,
) {
    val displayText: String get() = bigText.ifBlank { text }
    val replyAction: NAction? get() = actions.firstOrNull { it.isReply }
    val allText: String get() = listOf(title, displayText, subText).filter { it.isNotBlank() }.joinToString("\n") +
        messages.joinToString("") { "\n" + it.text }

    fun facts(fromContact: Boolean): NotifFacts = NotifFacts(
        key = key,
        pkg = pkg,
        appName = appName,
        title = title,
        text = displayText,
        category = category,
        importance = importance,
        isGroupChat = isGroupConversation,
        fromContact = fromContact,
        hasPicture = picture != null,
        hasReply = replyAction != null,
        ongoing = ongoing,
        silent = silent,
        isReaction = Categorize.isReaction(displayText),
    )

    companion object {
        @Suppress("DEPRECATION")
        fun from(context: Context, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?): ShadeItem {
            val n = sbn.notification
            val extras: Bundle = n.extras ?: Bundle()
            val title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

            var importance = 3
            var matchesDnd = true
            var channelId = n.channelId.orEmpty()
            if (ranking != null) {
                val r = NotificationListenerService.Ranking()
                if (ranking.getRanking(sbn.key, r)) {
                    importance = r.importance
                    matchesDnd = r.matchesInterruptionFilter()
                    r.channel?.id?.let { channelId = it }
                }
            }

            val messages = (extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: emptyArray()).mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                val msgText = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val sender = b.getCharSequence("sender")?.toString()
                    ?: (b.getParcelable("sender_person") as? Person)?.name?.toString()
                    ?: ""
                Msg(sender, msgText)
            }

            val picture: Bitmap? = (extras.get(Notification.EXTRA_PICTURE) as? Bitmap)
                ?: if (Build.VERSION.SDK_INT >= 31) {
                    (extras.get(Notification.EXTRA_PICTURE_ICON) as? Icon)?.let { icon ->
                        runCatching { (icon.loadDrawable(context) as? android.graphics.drawable.BitmapDrawable)?.bitmap }.getOrNull()
                    }
                } else null

            val people = (extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST) ?: arrayListOf()).toList()
            val mediaToken = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
            val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
            val isMessaging = extras.containsKey(Notification.EXTRA_MESSAGES) || extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null

            return ShadeItem(
                key = sbn.key,
                pkg = sbn.packageName,
                appName = AppInfoCache.label(context, sbn.packageName),
                title = title,
                text = text,
                bigText = bigText,
                subText = subText,
                postTime = if (n.`when` > 0) n.`when` else sbn.postTime,
                showWhen = extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true) && n.`when` > 0,
                color = n.color,
                smallIcon = n.smallIcon,
                largeIcon = n.getLargeIcon(),
                picture = picture,
                actions = (n.actions ?: emptyArray()).map { a ->
                    NAction(a.title?.toString().orEmpty(), a.actionIntent, a.remoteInputs?.toList() ?: emptyList())
                },
                contentIntent = n.contentIntent,
                ongoing = sbn.isOngoing,
                clearable = sbn.isClearable,
                groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
                groupKey = sbn.groupKey.orEmpty(),
                category = Categorize.of(n.category, sbn.packageName, isMessaging, mediaToken != null, progressMax > 0 || indeterminate),
                importance = importance,
                matchesDnd = matchesDnd,
                mediaToken = mediaToken,
                progress = progress,
                progressMax = progressMax,
                progressIndeterminate = indeterminate,
                messages = messages,
                conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty(),
                isGroupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
                people = people,
                channelId = channelId,
                silent = importance <= 2,
                sbn = sbn,
            )
        }
    }
}
