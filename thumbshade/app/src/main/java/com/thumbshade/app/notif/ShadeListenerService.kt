package com.thumbshade.app.notif

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.thumbshade.app.ai.AiHub
import com.thumbshade.app.ai.Digest
import com.thumbshade.app.ai.Engagement
import com.thumbshade.app.overlay.EdgeLight
import com.thumbshade.app.overlay.OverlayService
import com.thumbshade.app.rules.HoldStore
import com.thumbshade.app.rules.RuleEngine

class ShadeListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        NotificationRepo.connected.value = true
        refreshAll(currentRanking)
        runCatching { OverlayWarning.sweep(this, activeNotifications) }
        runCatching { MediaHub.onListenerConnected(this) }
        HoldStore.reconcile(this)
        OverlayService.startIfEnabled(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        NotificationRepo.connected.value = false
        MediaHub.onListenerDisconnected()
        runCatching { requestRebind(ComponentName(this, ShadeListenerService::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (OverlayWarning.maybeHide(this, sbn)) return
        val previous = NotificationRepo.get(sbn.key)
        val item = runCatching { ShadeItem.from(this, sbn, rankingMap) }
            .onFailure { Log.w(TAG, "could not read ${sbn.key}", it) }
            .getOrNull() ?: return
        NotificationRepo.upsert(item)
        if (RoundTrips.consume(sbn.key)) return
        if (HoldStore.onReposted(sbn.key)) {
            runCatching { Digest.onReturned(this, item) }
            OverlayService.onNewNotification(item)
            return
        }

        val onlyAlertOnce = (sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        val changed = previous == null || previous.title != item.title || previous.displayText != item.displayText
        if (!changed || (previous != null && onlyAlertOnce)) return
        if (item.groupSummary) return

        runCatching { AiHub.onPosted(this, item) }
        var outcome = runCatching { RuleEngine.onPosted(this, item) }
            .onFailure { Log.w(TAG, "rules failed", it) }
            .getOrNull() ?: RuleEngine.Outcome()
        // Smart digest: what rules didn't handle and isn't important waits for the next digest.
        if (!outcome.removed && runCatching { Digest.consider(this, item, AiHub.urgency(item)) }.getOrDefault(false)) {
            outcome = outcome.copy(removed = true, silenced = true)
        }
        if (!outcome.removed && !outcome.silenced && !item.ongoing) {
            runCatching { EdgeLight.onNotification(this, item, outcome.edgeStyle, outcome.edgeColor) }
        }
        OverlayService.onNewNotification(item)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        if (reason == REASON_SNOOZED && RoundTrips.isExpected(sbn.key)) return
        // Learn from what you did in the system shade too: tapped it, or swiped it away.
        NotificationRepo.get(sbn.key)?.let { item ->
            when (reason) {
                REASON_CLICK -> AiHub.record(item, Engagement.Event.OPENED)
                REASON_CANCEL -> AiHub.record(item, Engagement.Event.DISMISSED)
            }
        }
        AiHub.forget(sbn.key)
        NotificationRepo.remove(sbn.key)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        refreshAll(rankingMap)
    }

    private fun refreshAll(ranking: RankingMap?) {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        NotificationRepo.replaceAll(active.filterNot { OverlayWarning.isWarning(this, it) }.mapNotNull { sbn ->
            runCatching { ShadeItem.from(this, sbn, ranking) }.getOrNull()
        })
    }

    companion object {
        private const val TAG = "ThumbShadeListener"

        @Volatile
        var instance: ShadeListenerService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, ShadeListenerService::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }

        fun cancel(key: String) {
            runCatching { instance?.cancelNotification(key) }
        }

        fun cancelAll() {
            runCatching { instance?.cancelAllNotifications() }
        }

        fun snooze(key: String, durationMs: Long): Boolean {
            val svc = instance ?: return false
            return runCatching { svc.snoozeNotification(key, durationMs) }.isSuccess
        }

        fun suppressEffects(on: Boolean) {
            runCatching { instance?.requestListenerHints(if (on) HINT_HOST_DISABLE_NOTIFICATION_EFFECTS else 0) }
        }

        fun setInterruptionFilter(filter: Int): Boolean {
            val svc = instance ?: return false
            return runCatching { svc.requestInterruptionFilter(filter) }.isSuccess
        }

        fun snoozedKeys(): Set<String> =
            runCatching { instance?.snoozedNotifications?.mapNotNull { it?.key }?.toSet() }.getOrNull() ?: emptySet()

        fun resync() {
            instance?.let { it.refreshAll(runCatching { it.currentRanking }.getOrNull()) }
        }
    }
}

/**
 * Android posts "<app> is displaying over other apps" for every overlay app. The listener can
 * snooze it like any other notification; it comes back when the snooze ends, so sweep again then.
 */
object OverlayWarning {
    private const val MARKER = "AlertWindowNotification"
    private const val SNOOZE_MS = 7L * 24 * 3600 * 1000

    fun isWarning(context: Context, sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != "android") return false
        val tag = sbn.tag.orEmpty()
        val channel = sbn.notification.channelId.orEmpty()
        return (tag.contains(MARKER) || channel.contains(MARKER)) &&
            (tag.contains(context.packageName) || channel.contains(context.packageName))
    }

    fun maybeHide(context: Context, sbn: StatusBarNotification): Boolean {
        if (!com.thumbshade.app.data.SettingsRepo.current.hideOverlayWarning) return false
        if (!isWarning(context, sbn)) return false
        ShadeListenerService.snooze(sbn.key, SNOOZE_MS)
        return true
    }

    fun sweep(context: Context, active: Array<StatusBarNotification>?) {
        active?.forEach { maybeHide(context, it) }
    }
}
