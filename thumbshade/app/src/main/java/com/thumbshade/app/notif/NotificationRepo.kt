package com.thumbshade.app.notif

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** Every active notification the listener knows about, newest state wins. */
object NotificationRepo {
    private val map = LinkedHashMap<String, ShadeItem>()
    private val _items = MutableStateFlow<List<ShadeItem>>(emptyList())
    val items: StateFlow<List<ShadeItem>> = _items
    val connected = MutableStateFlow(false)

    @Synchronized
    fun replaceAll(list: List<ShadeItem>) {
        map.clear()
        list.forEach { map[it.key] = it }
        publish()
    }

    @Synchronized
    fun upsert(item: ShadeItem) {
        map[item.key] = item
        publish()
    }

    @Synchronized
    fun remove(key: String) {
        if (map.remove(key) != null) publish()
    }

    @Synchronized
    fun get(key: String): ShadeItem? = map[key]

    private fun publish() {
        _items.value = map.values.toList()
    }
}

/**
 * Notifications the app snoozed itself and expects straight back (the mute "blink" and releases).
 * Their removal is not shown and their re-post does not run the rules again.
 */
object RoundTrips {
    private val expected = ConcurrentHashMap<String, Long>()

    fun expect(key: String, windowMs: Long) {
        expected[key] = SystemClock.elapsedRealtime() + windowMs
    }

    fun isExpected(key: String): Boolean = (expected[key] ?: 0L) > SystemClock.elapsedRealtime()

    fun consume(key: String): Boolean {
        val until = expected.remove(key) ?: return false
        return until > SystemClock.elapsedRealtime()
    }

    fun forget(key: String) {
        expected.remove(key)
    }
}

object AppInfoCache {
    private val labels = ConcurrentHashMap<String, String>()
    private val icons = ConcurrentHashMap<String, Drawable>()

    fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            val info: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(if (pkg == "android") "Android System" else pkg)
    }

    /** The app's icon, or the icon pack's / your own replacement for it. */
    fun icon(context: Context, pkg: String): Drawable? = com.thumbshade.app.icons.IconStore.iconFor(context, pkg) ?: originalIcon(context, pkg)

    fun originalIcon(context: Context, pkg: String): Drawable? = icons[pkg] ?: runCatching {
        context.packageManager.getApplicationIcon(pkg)
    }.getOrNull()?.also { icons[pkg] = it }

    data class AppEntry(val pkg: String, val label: String)

    /** Launchable apps, for pickers. */
    fun launchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return resolved.map { it.activityInfo.packageName }
            .distinct()
            .map { AppEntry(it, label(context, it)) }
            .sortedBy { it.label.lowercase() }
    }
}
