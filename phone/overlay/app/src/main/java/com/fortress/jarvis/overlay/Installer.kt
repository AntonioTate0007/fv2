package com.fortress.jarvis.overlay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the Termux family from F-Droid and hands each APK to Android's package
 * installer. All four must come from the same source (same signing key), which is
 * why this never mixes in Play Store or GitHub builds.
 */
object Installer {
    data class Pkg(val id: String, val label: String, val required: Boolean)

    val PACKAGES = listOf(
        Pkg("com.termux", "Termux", true),
        Pkg("com.termux.api", "Termux:API", true),
        Pkg("com.termux.widget", "Termux:Widget", false),
        Pkg("com.termux.boot", "Termux:Boot", false),
    )

    fun installed(ctx: Context, id: String): Boolean = try {
        ctx.packageManager.getPackageInfo(id, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun missing(ctx: Context): List<Pkg> = PACKAGES.filter { !installed(ctx, it.id) }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** F-Droid's package API → direct APK URL for the suggested version. */
    fun resolveUrl(id: String): String {
        val json = URL("https://f-droid.org/api/v1/packages/$id").readText()
        val code = JSONObject(json).getLong("suggestedVersionCode")
        return "https://f-droid.org/repo/${id}_$code.apk"
    }

    /** Blocking download into the app cache. Call off the main thread. */
    fun download(ctx: Context, url: String, name: String, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "apks").apply { mkdirs() }
        val out = File(dir, "$name.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} for $url")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    /** Opens the system installer for a downloaded APK. */
    fun install(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }
}
