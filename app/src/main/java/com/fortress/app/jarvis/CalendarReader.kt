package com.fortress.app.jarvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/** One entry off the device calendar, normalized for Jarvis to read out. */
data class AgendaEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String?,
    val allDay: Boolean
) {
    /** "9:00 AM" or "All day", phone-locale aware. */
    fun whenLabel(context: Context): String =
        if (allDay) "All day"
        else DateUtils.formatDateTime(context, startMillis, DateUtils.FORMAT_SHOW_TIME)
}

/**
 * Reads the user's calendar straight off the device via CalendarContract — no Google
 * OAuth, no backend. This covers any account already synced to the phone (Google,
 * Exchange, iCloud-via-sync, etc.). Gated behind READ_CALENDAR; returns empty until
 * the permission is granted.
 */
object CalendarReader {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun upcoming(
        context: Context,
        windowMillis: Long = TimeUnit.HOURS.toMillis(36),
        limit: Int = 12
    ): List<AgendaEvent> {
        if (!hasPermission(context)) return emptyList()

        val now = System.currentTimeMillis()
        val end = now + windowMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )

        val events = mutableListOf<AgendaEvent>()
        runCatching {
            context.contentResolver.query(
                uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext() && events.size < limit) {
                    events += AgendaEvent(
                        title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(busy)",
                        startMillis = c.getLong(1),
                        endMillis = c.getLong(2),
                        location = c.getString(3)?.takeIf { it.isNotBlank() },
                        allDay = c.getInt(4) == 1
                    )
                }
            }
        }
        return events
    }
}
