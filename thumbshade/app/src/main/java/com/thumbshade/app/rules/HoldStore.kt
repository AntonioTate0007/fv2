package com.thumbshade.app.rules

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.service.quicksettings.TileService
import com.thumbshade.app.data.AppJson
import com.thumbshade.app.notif.RoundTrips
import com.thumbshade.app.notif.ShadeItem
import com.thumbshade.app.notif.ShadeListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Notifications held back by a rule or snoozed from the shade. The system snooze does the work
 * (the notification comes back on its own at [Held.releaseAt]); this keeps the list so the app
 * can show it and hand everything back early. Listeners have no "unsnooze", so an early release
 * re-snoozes for a few milliseconds and the system re-posts it straight away.
 */
object HoldStore {
    @Serializable
    enum class Reason { HOLD, SNOOZE }

    @Serializable
    data class Held(
        val key: String,
        val pkg: String,
        val appName: String,
        val title: String,
        val releaseAt: Long,
        val reason: Reason,
    )

    private const val RELEASE_MS = 10L
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private val serializer = ListSerializer(Held.serializer())
    private val _held = MutableStateFlow<List<Held>>(emptyList())
    val held: StateFlow<List<Held>> = _held

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = context.getSharedPreferences("held", Context.MODE_PRIVATE)
        prefs = p
        p.getString("json", null)
            ?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }
            ?.let { _held.value = it }
        prune()
    }

    @Synchronized
    private fun save(list: List<Held>) {
        _held.value = list
        prefs?.edit()?.putString("json", AppJson.encodeToString(serializer, list))?.apply()
        appContext?.let { ctx ->
            runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, ReleaseTileService::class.java)) }
        }
    }

    fun hold(item: ShadeItem, releaseAt: Long, reason: Reason): Boolean {
        val duration = releaseAt - System.currentTimeMillis()
        if (duration <= 0) return false
        if (!ShadeListenerService.snooze(item.key, duration)) return false
        RoundTrips.forget(item.key)
        save(_held.value.filterNot { it.key == item.key } + Held(item.key, item.pkg, item.appName, item.title, releaseAt, reason))
        return true
    }

    fun release(key: String): Boolean {
        RoundTrips.expect(key, 5_000)
        val ok = ShadeListenerService.snooze(key, RELEASE_MS)
        if (!ok) RoundTrips.forget(key)
        save(_held.value.filterNot { it.key == key })
        return ok
    }

    /**
     * A held notification is snoozed, so any post with its key means it came back (its time
     * arrived). Forget it and tell the caller, so rules don't hold it again.
     */
    fun onReposted(key: String): Boolean {
        if (_held.value.none { it.key == key }) return false
        save(_held.value.filterNot { it.key == key })
        return true
    }

    fun releaseAll(): Int {
        val keys = _held.value.map { it.key }
        var n = 0
        keys.forEach { if (release(it)) n++ }
        if (keys.isNotEmpty()) RuleLog.add("Released $n of ${keys.size} held notification(s)")
        return n
    }

    /** Forget entries whose time has passed. */
    fun prune() {
        val now = System.currentTimeMillis()
        val list = _held.value
        val kept = list.filter { it.releaseAt > now }
        if (kept.size != list.size) save(kept)
    }

    /** On listener connect: keep only entries the system still has snoozed. */
    fun reconcile(context: Context) {
        prune()
        val snoozed = ShadeListenerService.snoozedKeys()
        if (snoozed.isEmpty() && _held.value.isEmpty()) return
        val kept = _held.value.filter { it.key in snoozed }
        if (kept.size != _held.value.size) save(kept)
    }

    val count: Int get() = _held.value.size
}
