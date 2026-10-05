package com.thumbshade.app.access

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.NotificationRepo

/**
 * Apps for the long-press switcher: your favourites first, then the apps you used most recently
 * (needs Usage access), then apps with notifications waiting.
 */
object RecentApps {
    /** Marks a switcher slot as a prediction (drawn with a sparkle). */
    const val PREDICTED = "predicted"

    /** Apps with notifications waiting, newest notification first. */
    fun withNotifications(context: Context, max: Int): List<String> {
        val s = SettingsRepo.current
        val own = context.packageName
        val current = AssistService.state.value.foregroundPkg
        return com.thumbshade.app.notif.ShadeFilter.visible(NotificationRepo.items.value, s)
            .sortedByDescending { it.postTime }
            .map { it.pkg }
            .distinct()
            .filter { it != own && it != current }
            .take(max)
    }

    /** What the long-press switcher shows, by the user's choice. */
    fun forSwitcher(context: Context, max: Int): List<com.thumbshade.app.data.GestureAction> {
        fun notif(pkg: String) = com.thumbshade.app.data.GestureAction(com.thumbshade.app.data.GestureType.OPEN_APP_NOTIFICATION, pkg)
        fun app(pkg: String) = com.thumbshade.app.data.GestureAction(com.thumbshade.app.data.GestureType.OPEN_APP, pkg)
        fun predicted(pkg: String) = com.thumbshade.app.data.GestureAction(com.thumbshade.app.data.GestureType.OPEN_APP, pkg, PREDICTED)
        val ai = SettingsRepo.current.ai
        return when (SettingsRepo.current.switcherSource) {
            com.thumbshade.app.data.SwitcherSource.PREDICTED ->
                com.thumbshade.app.ai.AppPredictor.predict(context, max).map(::predicted)
            com.thumbshade.app.data.SwitcherSource.RECENT_APPS -> list(context, max).map(::app)
            com.thumbshade.app.data.SwitcherSource.NOTIFICATIONS -> withNotifications(context, max).map(::notif)
            com.thumbshade.app.data.SwitcherSource.BOTH -> {
                // Inner ring first (nearest the thumb): predictions, then apps with notifications,
                // then recently used apps, without repeats.
                val pred = com.thumbshade.app.ai.AppPredictor.predict(context, minOf(ai.predictCount, max))
                val notifs = withNotifications(context, max).filterNot { it in pred }
                val recent = list(context, max).filterNot { it in pred || it in notifs }
                (pred.map(::predicted) + notifs.map(::notif) + recent.map(::app)).take(max)
            }
        }
    }

    fun list(context: Context, max: Int): List<String> {
        val pm = context.packageManager
        val own = context.packageName
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val current = AssistService.state.value.foregroundPkg
        fun ok(pkg: String) = pkg != own && pkg != home && pkg != current && pm.getLaunchIntentForPackage(pkg) != null

        val out = LinkedHashSet<String>()
        SettingsRepo.current.switcherFavorites.filter(::ok).forEach { out += it }
        if (UsageWatcher.hasAccess(context)) {
            runCatching {
                val usm = context.getSystemService(UsageStatsManager::class.java)
                val now = System.currentTimeMillis()
                usm?.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3L * 24 * 3600 * 1000, now)
                    .orEmpty()
                    .filter { it.lastTimeUsed > 0 && it.totalTimeInForeground > 0 }
                    .sortedByDescending { it.lastTimeUsed }
                    .map { it.packageName }
                    .filter(::ok)
                    .forEach { if (out.size < max) out += it }
            }
        }
        NotificationRepo.items.value.sortedByDescending { it.postTime }.map { it.pkg }.filter(::ok).forEach { if (out.size < max) out += it }
        return out.take(max)
    }
}
