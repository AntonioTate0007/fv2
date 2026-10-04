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
