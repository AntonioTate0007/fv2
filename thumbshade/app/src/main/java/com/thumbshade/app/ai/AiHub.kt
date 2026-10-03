package com.thumbshade.app.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.Extract
import com.thumbshade.app.notif.ShadeItem
import com.thumbshade.app.rules.Categorize
import com.thumbshade.app.rules.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/** Ties the smart features to live notifications: urgency, ordering, summaries, location. */
object AiHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val contactFlags = ConcurrentHashMap<String, Boolean>()

    /** Work done once when a notification arrives (the contacts lookup is the slow part). */
    fun onPosted(context: Context, item: ShadeItem) {
        contactFlags[item.key] = runCatching { RuleEngine.fromContact(context, item) }.getOrDefault(false)
    }

    fun forget(key: String) {
        contactFlags.remove(key)
    }

    fun learned(item: ShadeItem): Float =
        if (SettingsRepo.current.ai.learn) Engagement.learned(item.pkg, item.sender) else 0f

    fun urgency(item: ShadeItem): Urgency = UrgencyModel.classify(
        UrgencyModel.Features(
            cat = item.category,
            importance = item.importance,
            text = item.title + "\n" + item.allText,
            fromContact = contactFlags[item.key] ?: false,
            isReaction = Categorize.isReaction(item.displayText),
            ongoing = item.ongoing,
            hasCode = Extract.code(item.allText) != null,
            isGroupChat = item.isGroupConversation,
        ),
        learned(item),
    )

    /** Higher = more important; the shade puts these closest to your thumb. */
    fun priority(item: ShadeItem): Float = UrgencyModel.score(urgency(item), learned(item))

    fun record(item: ShadeItem, event: Engagement.Event) {
        if (SettingsRepo.current.ai.learn) Engagement.record(item.pkg, item.sender, event)
    }

    // region Summaries

    private val _summaries = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Summary text by request id (the id changes whenever the content does). */
    val summaries: StateFlow<Map<String, String>> = _summaries
    private val nanoDone = ConcurrentHashMap.newKeySet<String>()

    private fun put(id: String, text: String) {
        _summaries.value = (_summaries.value + (id to text)).let { if (it.size > 200) it.entries.drop(it.size - 200).associate { e -> e.key to e.value } else it }
    }

    /** Shows the built-in summary now, then Gemini Nano's when the phone has it. */
    fun requestSummary(context: Context, id: String, heuristic: () -> String, transcript: () -> String) {
        if (id !in _summaries.value) put(id, heuristic())
        if (!SettingsRepo.current.ai.useNano || !nanoDone.add(id)) return
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            Nano.summarize(app, transcript())?.let { put(id, "✦ $it") }
        }
    }

    fun conversationSummary(context: Context, item: ShadeItem): String? {
        val ai = SettingsRepo.current.ai
        if (!ai.summaries || item.messages.size < ai.summaryMinMessages) return null
        val id = item.key + ":" + item.messages.size + ":" + item.messages.last().text.hashCode()
        val lines = item.messages.map { Summaries.Line(it.sender, it.text) }
        requestSummary(context, id, {
            val mention = item.messages.asReversed().firstNotNullOfOrNull { Entities.event(it.text, ZonedDateTime.now())?.phrase }
            Summaries.conversation(lines, mention)
        }, { Summaries.transcript(lines) })
        return _summaries.value[id]
    }

    fun groupSummary(context: Context, appName: String, items: List<ShadeItem>): String? {
        if (!SettingsRepo.current.ai.summaries || items.size < 3) return null
        val id = "group:" + items.joinToString(",") { it.key + it.postTime }.hashCode()
        val briefs = items.map { Summaries.Brief(it.title, it.displayText) }
        requestSummary(context, id, { Summaries.group(appName, briefs) }, {
            items.joinToString("\n") { (if (it.title.isNotBlank()) it.title + ": " else "") + it.displayText }
        })
        return _summaries.value[id]
    }

    // endregion

    /** "https://maps.google.com/?q=lat,lng" for the last known location, or null. */
    fun locationLink(context: Context): String? {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        @Suppress("MissingPermission")
        val loc = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return "https://maps.google.com/?q=%.5f,%.5f".format(java.util.Locale.US, loc.latitude, loc.longitude)
    }
}
