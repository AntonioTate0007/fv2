package com.thumbshade.app.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.thumbshade.app.rules.Effects

/** Opens the right app for an extracted entity. No permissions needed: each is a plain intent. */
object EntityActions {
    fun run(context: Context, e: Entities.Entity, title: String) {
        val intent = when (e) {
            is Entities.Event -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.start.toInstant().toEpochMilli())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.start.toInstant().toEpochMilli() + if (e.allDay) 86_400_000L else 3_600_000L)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, e.allDay)
            is Entities.Tracking -> Intent(Intent.ACTION_VIEW, Uri.parse(e.url))
            is Entities.Flight -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("flight " + e.code)))
            is Entities.Address -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(e.text)))
            is Entities.Email -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + e.address))
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Effects.toast(context, "No app can open this") }
    }

    /** Short chip text: "Add to calendar · Sun 10:00". */
    fun chipLabel(e: Entities.Entity): String = when (e) {
        is Entities.Event -> {
            val day = e.start.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
            val time = if (e.allDay) "" else " " + java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).format(e.start)
            "Add to calendar · $day$time"
        }
        else -> e.label
    }
}
