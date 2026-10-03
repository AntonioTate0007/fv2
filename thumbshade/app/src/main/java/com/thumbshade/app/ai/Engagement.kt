package com.thumbshade.app.ai

import android.content.Context
import android.content.SharedPreferences
import com.thumbshade.app.data.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/**
 * Learns, on the phone, what you do with notifications: opening and replying count for an app
 * and a sender, swiping away unread counts against them. Old habits fade (two-week half-life).
 */
object Engagement {
    enum class Event(val weight: Float) { OPENED(1f), REPLIED(1.5f), ACTION(0.7f), DISMISSED(-0.7f) }

    @Serializable
    data class Score(val value: Float = 0f, val at: Long = 0L)

    const val HALF_LIFE_MS = 14L * 24 * 3600 * 1000

    /** [value] decayed from [from] to [to]. */
    fun decay(value: Float, from: Long, to: Long): Float {
        if (to <= from) return value
        val lambda = ln(2.0) / HALF_LIFE_MS
        return (value * exp(-lambda * (to - from))).toFloat()
    }

    /** Squashes a running total into -1 … 1. */
    fun squash(value: Float): Float = tanh(value / 3f)

    private val scores = ConcurrentHashMap<String, Score>()
    private var prefs: SharedPreferences? = null
    private val serializer = MapSerializer(String.serializer(), Score.serializer())
    @Volatile private var dirty = false

    fun init(context: Context) {
        val p = context.getSharedPreferences("engagement", Context.MODE_PRIVATE)
        prefs = p
        p.getString("json", null)?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }?.let { scores.putAll(it) }
    }

    private fun appKey(pkg: String) = pkg
    private fun senderKey(pkg: String, sender: String) = "$pkg|${sender.trim().lowercase()}"

    fun record(pkg: String, sender: String, event: Event, now: Long = System.currentTimeMillis()) {
        listOfNotNull(appKey(pkg), sender.takeIf { it.isNotBlank() }?.let { senderKey(pkg, it) }).forEach { key ->
            scores.compute(key) { _, old ->
                val base = old?.let { decay(it.value, it.at, now) } ?: 0f
                Score((base + event.weight).coerceIn(-30f, 30f), now)
            }
        }
        dirty = true
        save()
    }

    /** -1 (you always ignore these) … 1 (you always open them). */
    fun learned(pkg: String, sender: String, now: Long = System.currentTimeMillis()): Float {
        val app = scores[appKey(pkg)]?.let { squash(decay(it.value, it.at, now)) } ?: 0f
        val person = sender.takeIf { it.isNotBlank() }?.let { scores[senderKey(pkg, it)] }?.let { squash(decay(it.value, it.at, now)) }
        return if (person != null) person * 0.6f + app * 0.4f else app
    }

    /** Apps you engage with most and least, for the settings screen. */
    fun top(n: Int, now: Long = System.currentTimeMillis()): List<Pair<String, Float>> =
        scores.filterKeys { '|' !in it }.map { (k, v) -> k to squash(decay(v.value, v.at, now)) }
            .sortedByDescending { kotlin.math.abs(it.second) }.take(n)

    fun reset() {
        scores.clear()
        dirty = true
        save()
    }

    @Synchronized
    private fun save() {
        if (!dirty) return
        dirty = false
        prefs?.edit()?.putString("json", AppJson.encodeToString(serializer, HashMap(scores)))?.apply()
    }
}
