package com.thumbshade.app.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Things in a notification you can act on: events, parcels, flights, addresses, emails. */
object Entities {
    sealed interface Entity { val label: String }
    data class Event(val start: ZonedDateTime, val allDay: Boolean, val phrase: String) : Entity {
        override val label get() = "Add to calendar"
    }
    data class Tracking(val carrier: String, val number: String, val url: String) : Entity {
        override val label get() = "Track $carrier parcel"
    }
    data class Flight(val code: String) : Entity { override val label get() = "Flight $code status" }
    data class Address(val text: String) : Entity { override val label get() = "Open in Maps" }
    data class Email(val address: String) : Entity { override val label get() = "Email $address" }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val weekdays = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )

    private val relDay = Regex("(?i)\\b(today|tonight|this (morning|afternoon|evening)|tomorrow|tmrw|tmr|day after tomorrow)\\b")
    private val weekday = Regex("(?i)\\b(next |this |on )?(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(day|nesday|sday|urday|r?sday)?\\b")
    private val monthDay = Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(st|nd|rd|th)?(,?\\s+(\\d{4}))?\\b")
    private val dayMonth = Regex("(?i)\\b(\\d{1,2})(st|nd|rd|th)?\\s+(of\\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?(\\s+(\\d{4}))?\\b")
    private val numericDate = Regex("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(/(\\d{2,4}))?(?![\\d/])")
    private val clock = Regex("(?i)(?<![\\d:])(\\d{1,2})(:(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])|(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])|\\b(noon|midday|midnight)\\b")
    private val atHour = Regex("(?i)\\bat\\s+(\\d{1,2})(?![\\d:%])\\b")

    private fun parseTime(text: String, evening: Boolean): Pair<LocalTime, String>? {
        clock.find(text)?.let { m ->
            m.groups[7]?.value?.lowercase()?.let { word ->
                return (if (word == "midnight") LocalTime.MIDNIGHT else LocalTime.NOON) to m.value
            }
            if (m.groups[4] != null) {
                var h = m.groupValues[1].toInt()
                val min = m.groups[3]?.value?.toInt() ?: 0
                val pm = m.groupValues[4].lowercase().startsWith("p")
                if (h !in 1..12 || min > 59) return null
                if (pm && h != 12) h += 12
                if (!pm && h == 12) h = 0
                return LocalTime.of(h, min) to m.value
            }
            return LocalTime.of(m.groupValues[5].toInt(), m.groupValues[6].toInt()) to m.value
        }
        // "at 8" — assume the evening when the text says tonight, otherwise daytime hours.
        atHour.find(text)?.let { m ->
            var h = m.groupValues[1].toInt()
            if (h !in 1..12) return null
            if (evening || h in 1..6) h += 12
            return LocalTime.of(h % 24, 0) to m.value
        }
        return null
    }

    private fun parseDay(text: String, now: ZonedDateTime): Pair<LocalDate, String>? {
        val today = now.toLocalDate()
        relDay.find(text)?.let { m ->
            val w = m.value.lowercase()
            val d = when {
                w.startsWith("day after") -> today.plusDays(2)
                w.startsWith("tom") || w.startsWith("tmr") -> today.plusDays(1)
                else -> today
            }
            return d to m.value
        }
        monthDay.find(text)?.let { m ->
            val month = months.indexOf(m.groupValues[1].lowercase().take(3)) + 1
            val day = m.groupValues[2].toInt()
            val year = m.groups[5]?.value?.toInt()
            return date(today, year, month, day)?.let { it to m.value }
        }
        dayMonth.find(text)?.let { m ->
            val month = months.indexOf(m.groupValues[4].lowercase().take(3)) + 1
            val day = m.groupValues[1].toInt()
            val year = m.groups[6]?.value?.toInt()
            return date(today, year, month, day)?.let { it to m.value }
        }
        numericDate.find(text)?.let { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            val year = m.groups[4]?.value?.toInt()?.let { if (it < 100) 2000 + it else it }
            return date(today, year, month, day)?.let { it to m.value }
        }
        weekday.find(text)?.let { m ->
            val dow = weekdays[m.groupValues[2].lowercase().take(3)] ?: return null
            val next = m.groupValues[1].trim().equals("next", true)
            var d = today.with(TemporalAdjusters.nextOrSame(dow))
            if (next && d == today) d = d.plusWeeks(1)
            return d to m.value
        }
        return null
    }

    /** A month/day without a year means the next time that date comes round. */
    private fun date(today: LocalDate, year: Int?, month: Int, day: Int): LocalDate? = runCatching {
        if (year != null) return@runCatching LocalDate.of(year, month, day)
        val d = LocalDate.of(today.year, month, day)
        if (d.isBefore(today)) d.plusYears(1) else d
    }.getOrNull()

    /** An event the text mentions, if it names a day and/or a time. */
    fun event(text: String, now: ZonedDateTime): Event? {
        val evening = Regex("(?i)\\b(tonight|this evening)\\b").containsMatchIn(text)
        val day = parseDay(text, now)
        val time = parseTime(text, evening)
        if (day == null && time == null) return null
        // A bare time with no day and no "at" is often just a timestamp ("Sent 10:42"): skip it.
        if (day == null && time != null && !Regex("(?i)\\b(at|by|from|until|till|starts?|begins?|boards?|departs?|meet|meeting|appointment|reservation|pick ?up|due)\\b").containsMatchIn(text)) return null
        val zone = now.zone
        val start = when {
            day != null && time != null -> ZonedDateTime.of(day.first, time.first, zone)
            day != null -> ZonedDateTime.of(day.first, if (evening) LocalTime.of(20, 0) else LocalTime.MIDNIGHT, zone)
            else -> {
                val t = ZonedDateTime.of(now.toLocalDate(), time!!.first, zone)
                if (t.isBefore(now.minusMinutes(30))) t.plusDays(1) else t
            }
        }
        val allDay = time == null && !evening
        val phrase = listOfNotNull(day?.second, time?.second).joinToString(" ")
        return Event(start, allDay, phrase)
    }

    private val ups = Regex("\\b(1Z[0-9A-Z]{16})\\b")
    private val usps = Regex("\\b((?:94|93|92|95|82)\\d{20}|(?:94|93|92|95)\\d{18}|[A-Z]{2}\\d{9}US)\\b")
    private val fedex = Regex("(?<!\\d)(\\d{12}|\\d{15})(?!\\d)")
    private val dhl = Regex("(?<!\\d)(\\d{10})(?!\\d)")
    private val trackingWord = Regex("(?i)\\b(track(ing)?|shipment|shipped|parcel|package|delivery|delivered|out for delivery|order)\\b")
    private val flight = Regex("\\b([A-Z]{2}|[A-Z]\\d|\\d[A-Z])\\s?(\\d{2,4})\\b")
    private val flightWord = Regex("(?i)\\b(flight|boarding|boards|gate|departs?|departure|arrival|check[- ]in)\\b")
    private val address = Regex(
        "(?i)\\b(\\d{1,5}\\s+(?:[\\p{L}0-9.'-]+\\s+){0,4}(?:street|st|avenue|ave|road|rd|boulevard|blvd|lane|ln|drive|dr|way|court|ct|place|pl|square|sq|terrace|parkway|pkwy|highway|hwy)\\.?)(?-i:(,\\s*[A-Z][\\p{L}.'-]*(\\s[A-Z][\\p{L}.'-]*){0,2}))?(?-i:(,?\\s+[A-Z]{2}\\s+\\d{5}(-\\d{4})?))?"
    )
    private val email = Regex("(?i)\\b([a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,})\\b")

    fun tracking(text: String): Tracking? {
        ups.find(text)?.let { val n = it.groupValues[1]; return Tracking("UPS", n, "https://www.ups.com/track?tracknum=$n") }
        usps.find(text)?.let { val n = it.groupValues[1]; return Tracking("USPS", n, "https://tools.usps.com/go/TrackConfirmAction?tLabels=$n") }
        if (!trackingWord.containsMatchIn(text)) return null
        if (Regex("(?i)fedex").containsMatchIn(text)) fedex.find(text)?.let { val n = it.groupValues[1]; return Tracking("FedEx", n, "https://www.fedex.com/fedextrack/?trknbr=$n") }
        if (Regex("(?i)\\bdhl\\b").containsMatchIn(text)) dhl.find(text)?.let { val n = it.groupValues[1]; return Tracking("DHL", n, "https://www.dhl.com/global-en/home/tracking.html?tracking-id=$n") }
        return null
    }

    fun flight(text: String): Flight? {
        if (!flightWord.containsMatchIn(text)) return null
        val m = flight.findAll(text).firstOrNull { m ->
            // Skip things like "A1 2024" years and gate numbers written "Gate B12".
            val before = text.substring(0, m.range.first).takeLast(6).lowercase()
            !before.contains("gate") && m.groupValues[2].length in 2..4 && !(m.groupValues[2].length == 4 && m.groupValues[2].startsWith("20"))
        } ?: return null
        return Flight(m.groupValues[1] + m.groupValues[2])
    }

    fun address(text: String): Address? = address.find(text)?.value?.trim()?.trimEnd(',', '.')?.let { Address(it) }

    fun emails(text: String): List<Email> = email.findAll(text).map { Email(it.groupValues[1]) }.distinctBy { it.address.lowercase() }.toList()

    /** Everything worth a button, most useful first. */
    fun find(text: String, now: ZonedDateTime): List<Entity> = buildList {
        event(text, now)?.let(::add)
        tracking(text)?.let(::add)
        flight(text)?.let(::add)
        address(text)?.let(::add)
    }
}
