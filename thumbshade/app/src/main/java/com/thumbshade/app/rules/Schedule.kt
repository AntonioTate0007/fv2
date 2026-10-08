package com.thumbshade.app.rules

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object Schedule {
    /** The next moment a held notification should be delivered, strictly after [now]. */
    fun nextRelease(action: RuleAction, now: Instant, zone: ZoneId): Instant = when (action.batchMode) {
        BatchMode.TIMES -> nextTime(action.batchTimes, now, zone)
        BatchMode.INTERVAL -> nextInterval(action.intervalMinutes, now, zone)
    }

    fun nextTime(minutesOfDay: List<Int>, now: Instant, zone: ZoneId): Instant {
        val times = minutesOfDay.filter { it in 0 until 24 * 60 }.distinct().sorted()
        if (times.isEmpty()) return now.plusSeconds(3600)
        val local = ZonedDateTime.ofInstant(now, zone)
        val today = local.toLocalDate()
        for (dayOffset in 0..1) {
            val date: LocalDate = today.plusDays(dayOffset.toLong())
            for (m in times) {
                val candidate = ZonedDateTime.of(date, LocalTime.of(m / 60, m % 60), zone).toInstant()
                if (candidate.isAfter(now)) return candidate
            }
        }
        return now.plusSeconds(24 * 3600)
    }

    /** Aligned to the clock: every 60 minutes means on the hour, every 30 at :00 and :30. */
    fun nextInterval(intervalMinutes: Int, now: Instant, zone: ZoneId): Instant {
        val step = intervalMinutes.coerceIn(5, 24 * 60)
        val local = ZonedDateTime.ofInstant(now, zone)
        val midnight = local.toLocalDate().atStartOfDay(zone)
        val minutesSinceMidnight = (now.epochSecond - midnight.toEpochSecond()) / 60
        val nextSlot = (minutesSinceMidnight / step + 1) * step
        return midnight.plusMinutes(nextSlot).toInstant()
    }

    fun formatMinute(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}
