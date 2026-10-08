package com.thumbshade.app.ai

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.thumbshade.app.access.AssistService
import com.thumbshade.app.access.UsageWatcher
import com.thumbshade.app.data.AppJson
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.NotificationRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Feeds [NextAppModel] from your app history (Usage access) or, without it, from the app-in-front
 * reports of the accessibility service, and answers "which apps next?" for the switcher.
 * Everything stays in this app's private storage.
 */
object AppPredictor {
    private const val PREFS = "predictor"
    private var prefs: SharedPreferences? = null
    @Volatile private var model = NextAppModel()
    @Volatile private var lastSync = 0L
    @Volatile private var lastApp: String? = null
    @Volatile private var lastAppAt = 0L
    private val launchable = ConcurrentHashMap<String, Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.getString("model", null)?.let { json ->
            runCatching { AppJson.decodeFromString(NextAppModel.Snapshot.serializer(), json) }.getOrNull()?.let { model = NextAppModel.from(it) }
        }
        lastSync = p.getLong("lastSync", 0L)
        // Without Usage access, learn from what the accessibility service sees.
        scope.launch {
            AssistService.state.map { it.foregroundPkg }.distinctUntilChanged().collect { pkg ->
                if (pkg != null && !UsageWatcher.hasAccess(app) && SettingsRepo.current.ai.predictApps) onForeground(app, pkg, System.currentTimeMillis())
            }
        }
        scope.launch { sync(app) }
    }

    private fun ignorable(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName || pkg == "com.android.systemui" || pkg == "android") return true
        return !launchable.getOrPut(pkg) { context.packageManager.getLaunchIntentForPackage(pkg) != null }
    }

    @Volatile private var homePkg: String? = null
    @Volatile private var homeCheckedAt = 0L
    /** The home-screen app, looked up at most once a minute (syncs can pass thousands of events). */
    private fun home(c: Context): String? {
        val now = System.currentTimeMillis()
        if (now - homeCheckedAt > 60_000) {
            homePkg = c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
            homeCheckedAt = now
        }
        return homePkg
    }

    @Synchronized
    private fun onForeground(context: Context, pkg: String, at: Long, weight: Float = 1f) {
        if (pkg == home(context)) {
            // Going home doesn't count as an app, but it doesn't break the chain either.
            return
        }
        if (ignorable(context, pkg) || pkg == lastApp) return
        val previous = lastApp.takeIf { at - lastAppAt < NextAppModel.SESSION_GAP_MS }
        model.observe(previous, pkg, at, ZoneId.systemDefault(), weight)
        lastApp = pkg
        lastAppAt = at
        save()
    }

    /** Reads app switches since the last read from Android's usage history (the last week at first). */
    @Synchronized
    fun sync(context: Context) {
        if (!SettingsRepo.current.ai.predictApps || !UsageWatcher.hasAccess(context)) return
        val now = System.currentTimeMillis()
        val since = if (lastSync > 0) lastSync else now - 7L * 24 * 3600 * 1000
        runCatching {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
            val events = usm.queryEvents(since, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) onForeground(context, e.packageName, e.timeStamp)
            }
        }
        lastSync = now
        prefs?.edit()?.putLong("lastSync", now)?.apply()
    }

    /** You picked [pkg] from the switcher: learn it a little harder. */
    fun onPicked(context: Context, pkg: String) {
        val now = System.currentTimeMillis()
        val from = AssistService.state.value.foregroundPkg ?: lastApp
        synchronized(this) {
            model.observe(from, pkg, now, ZoneId.systemDefault(), 0.5f)
            save()
        }
    }

    private fun headphones(context: Context): Boolean = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (android.os.Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }.getOrDefault(false)

    private val audioCache = ConcurrentHashMap<String, Boolean>()
    private fun isAudioApp(context: Context, pkg: String): Boolean = audioCache.getOrPut(pkg) {
        runCatching { context.packageManager.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_AUDIO }.getOrDefault(false)
    }

    /** The apps you're most likely to want next, best first. */
    fun predict(context: Context, k: Int, exclude: Set<String> = emptySet()): List<String> {
        if (!SettingsRepo.current.ai.predictApps) return emptyList()
        sync(context)
        val now = System.currentTimeMillis()
        val current = AssistService.state.value.foregroundPkg ?: lastApp.takeIf { now - lastAppAt < NextAppModel.SESSION_GAP_MS }
        val notifAge = NotificationRepo.items.value.groupBy { it.pkg }.mapValues { (_, list) -> now - list.maxOf { it.postTime } }
        val m = model
        val candidates = m.predict(current, now, ZoneId.systemDefault(), k * 3, NextAppModel.Context(notifAge, headphones(context), emptySet()), exclude)
        val audio = candidates.map { it.first }.filter { isAudioApp(context, it) }.toSet()
        val ctx = NextAppModel.Context(notifAge, headphones(context), audio)
        return m.predict(current, now, ZoneId.systemDefault(), k * 2, ctx, exclude)
            .map { it.first }
            .filterNot { ignorable(context, it) || it == home(context) }
            .take(k)
    }

    fun experience(): Int = model.experience(System.currentTimeMillis()).toInt()

    fun reset() {
        synchronized(this) {
            model = NextAppModel()
            lastApp = null
            lastSync = System.currentTimeMillis()
            prefs?.edit()?.putLong("lastSync", lastSync)?.remove("model")?.apply()
        }
    }

    private var pendingSave = false
    private fun save() {
        if (pendingSave) return
        pendingSave = true
        // Many switches can arrive at once while syncing; write once shortly after.
        scope.launch {
            kotlinx.coroutines.delay(2_000)
            pendingSave = false
            val json = synchronized(this@AppPredictor) { AppJson.encodeToString(NextAppModel.Snapshot.serializer(), model.snapshot()) }
            prefs?.edit()?.putString("model", json)?.apply()
        }
    }
}
