package com.fortress.jarvis.overlay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

/** Bridge to Termux's RUN_COMMAND service: tapping the ring runs ~/.shortcuts/Jarvis. */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val HOME = "/data/data/com.termux/files/home"

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun permitted(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Run a shortcut script inside Termux, in the background. */
    fun run(ctx: Context, script: String = "Jarvis"): Boolean {
        if (!installed(ctx)) { toast(ctx, "Termux isn't installed"); return false }
        if (!permitted(ctx)) { toast(ctx, "Grant 'run Termux commands' in Jarvis Overlay"); return false }
        val intent = Intent().apply {
            setClassName(PACKAGE, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "$HOME/.shortcuts/$script")
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        return try {
            ctx.startForegroundService(intent)
            true
        } catch (e: Exception) {
            toast(ctx, "Termux refused: set allow-external-apps=true in ~/.termux/termux.properties")
            false
        }
    }

    private fun toast(ctx: Context, msg: String) =
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
