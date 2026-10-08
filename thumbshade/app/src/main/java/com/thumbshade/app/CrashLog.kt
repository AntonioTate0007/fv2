package com.thumbshade.app

import android.content.Context
import java.io.File

/**
 * Saves the last uncaught crash so the General tab can show it (there's no other way to see
 * a stack trace on the phone without a computer).
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(
                    "ThumbShade crash at ${java.util.Date()}\n" +
                        "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), " +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "Thread: ${thread.name}\n\n" + error.stackTraceToString()
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
