package com.fortress.jarvis.overlay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

/**
 * Bridge to Termux's RUN_COMMAND service. Tapping the ring runs ~/.shortcuts/Jarvis;
 * the setup wizard uses it to write settings and start the bot.
 *
 * Termux only honours these intents once `allow-external-apps=true` is set in
 * ~/.termux/termux.properties — the bootstrap script does that.
 */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val HOME = "/data/data/com.termux/files/home"

    /** Git branch the bootstrap installs from. Switch to "main" once PR #32 merges. */
    const val REPO_BRANCH = "claude/project-ideas-ki8sdp"
    const val BOOTSTRAP =
        "curl -sSL https://raw.githubusercontent.com/AntonioTate0007/fv2/$REPO_BRANCH/phone/setup-termux.sh | BRANCH=$REPO_BRANCH bash"

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun permitted(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Run a shortcut script (name under ~/.shortcuts) inside Termux, in the background. */
    fun run(ctx: Context, script: String = "Jarvis", args: List<String> = emptyList()): Boolean =
        runPath(ctx, "$HOME/.shortcuts/$script", args)

    /** Run any executable inside Termux by absolute path. */
    fun runPath(ctx: Context, path: String, args: List<String> = emptyList()): Boolean {
        if (!installed(ctx)) { toast(ctx, "Termux isn't installed"); return false }
        if (!permitted(ctx)) { toast(ctx, "Grant 'run Termux commands' first"); return false }
        val intent = Intent().apply {
            setClassName(PACKAGE, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", path)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            if (args.isNotEmpty()) putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args.toTypedArray())
        }
        return try {
            ctx.startForegroundService(intent)
            true
        } catch (e: Exception) {
            toast(ctx, "Termux refused: run the bootstrap step first (allow-external-apps)")
            false
        }
    }

    fun openTermux(ctx: Context): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun toast(ctx: Context, msg: String) =
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
