package com.thumbshade.app.ai

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.ln

/**
 * Predicts which app you'll want next. Pure maths, no Android, so it can be tested.
 *
 * It keeps three small tables of fading counts:
 *  - what you open after each app ("after Gmail you often open Calendar"),
 *  - what you open at this time of day, weekday or weekend,
 *  - what you open overall.
 * Counts fade with a three-week half-life, so new habits take over from old ones.
 */
class NextAppModel(
    val after: MutableMap<String, MutableMap<String, Count>> = HashMap(),
    val byTime: MutableMap<String, MutableMap<String, Count>> = HashMap(),
    val overall: MutableMap<String, Count> = HashMap(),
) {
    @Serializable
    data class Count(val value: Float = 0f, val at: Long = 0L)

    @Serializable
    data class Snapshot(
        val after: Map<String, Map<String, Count>> = emptyMap(),
        val byTime: Map<String, Map<String, Count>> = emptyMap(),
        val overall: Map<String, Count> = emptyMap(),
    )

    /** Extra things known right now that make some apps more likely. */
    data class Context(
        /** Apps with a notification, and how long ago it arrived (ms). */
        val notificationAge: Map<String, Long> = emptyMap(),
        /** Headphones are connected. */
        val headphones: Boolean = false,
        /** Apps that play audio (music, podcasts). */
        val audioApps: Set<String> = emptySet(),
    )

    companion object {
        const val HALF_LIFE_MS = 21L * 24 * 3600 * 1000
        /** A switch after this long a gap starts a new session ("first app after unlocking"). */
        const val SESSION_GAP_MS = 20L * 60 * 1000
        const val SESSION_START = ""
        private const val MAX_PER_ROW = 40
        private const val MAX_ROWS = 300
        private const val NOTIFICATION_WINDOW_MS = 15L * 60 * 1000

        fun decay(value: Float, from: Long, to: Long): Float {
            if (to <= from) return value
            return (value * exp(-ln(2.0) / HALF_LIFE_MS * (to - from))).toFloat()
        }

        /** "w2" = weekday, 08:00–11:59 (six 4-hour slots, weekday or weekend). */
        fun timeKey(timeMs: Long, zone: ZoneId): String {
            val t = Instant.ofEpochMilli(timeMs).atZone(zone)
            val weekend = t.dayOfWeek.value >= 6
            return (if (weekend) "e" else "w") + (t.hour / 4)
        }

        fun from(s: Snapshot) = NextAppModel(
            s.after.mapValuesTo(HashMap()) { HashMap(it.value) },
            s.byTime.mapValuesTo(HashMap()) { HashMap(it.value) },
            HashMap(s.overall),
        )
    }

    fun snapshot() = Snapshot(after.mapValues { HashMap(it.value) }, byTime.mapValues { HashMap(it.value) }, HashMap(overall))

    private fun bump(row: MutableMap<String, Count>, app: String, w: Float, now: Long) {
        val old = row[app]
        row[app] = Count((old?.let { decay(it.value, it.at, now) } ?: 0f) + w, now)
        if (row.size > MAX_PER_ROW) {
            row.entries.minByOrNull { decay(it.value.value, it.value.at, now) }?.key?.let { row.remove(it) }
        }
    }

    private fun rowOf(map: MutableMap<String, MutableMap<String, Count>>, key: String): MutableMap<String, Count> {
        if (map.size >= MAX_ROWS && key !in map) {
            // Forget the row used least recently.
            map.entries.minByOrNull { r -> r.value.values.maxOfOrNull { it.at } ?: 0L }?.key?.let { map.remove(it) }
        }
        return map.getOrPut(key) { HashMap() }
    }

    /** You opened [next] at [timeMs], coming from [previous] (null or "" = start of a session). */
    fun observe(previous: String?, next: String, timeMs: Long, zone: ZoneId, weight: Float = 1f) {
        if (next.isBlank() || next == previous) return
        bump(rowOf(after, previous ?: SESSION_START), next, weight, timeMs)
        bump(rowOf(byTime, timeKey(timeMs, zone)), next, weight, timeMs)
        bump(overall, next, weight, timeMs)
    }

    private fun share(row: Map<String, Count>?, now: Long): Map<String, Float> {
        if (row.isNullOrEmpty()) return emptyMap()
        val values = row.mapValues { decay(it.value.value, it.value.at, now) }
        val total = values.values.sum()
        return if (total <= 0f) emptyMap() else values.mapValues { it.value / total }
    }

    /** How many app switches it has learned from (roughly, after fading). */
    fun experience(now: Long): Float = overall.values.sumOf { decay(it.value, it.at, now).toDouble() }.toFloat()

    /**
     * The [k] most likely next apps, best first, with their scores. [current] is the app in front
     * (null when unknown); it and anything in [exclude] are never suggested.
     */
    fun predict(current: String?, now: Long, zone: ZoneId, k: Int, ctx: Context = Context(), exclude: Set<String> = emptySet()): List<Pair<String, Float>> {
        val afterShare = share(after[current ?: SESSION_START], now)
        val timeShare = share(byTime[timeKey(now, zone)], now)
        val overallShare = share(overall, now)
        val candidates = HashSet<String>().apply {
            addAll(afterShare.keys); addAll(timeShare.keys)
            addAll(overallShare.entries.sortedByDescending { it.value }.take(25).map { it.key })
            addAll(ctx.notificationAge.keys)
        }
        return candidates.asSequence()
            .filter { it != current && it !in exclude && it.isNotBlank() }
            .map { app ->
                var s = 3.0f * (afterShare[app] ?: 0f) + 1.5f * (timeShare[app] ?: 0f) + 0.6f * (overallShare[app] ?: 0f)
                ctx.notificationAge[app]?.let { age ->
                    if (age in 0 until NOTIFICATION_WINDOW_MS) s += 0.6f * (1f - age.toFloat() / NOTIFICATION_WINDOW_MS)
                }
                if (ctx.headphones && app in ctx.audioApps) s += 0.5f
                app to s
            }
            .filter { it.second > 0.02f }
            .sortedByDescending { it.second }
            .take(k)
            .toList()
    }
}
