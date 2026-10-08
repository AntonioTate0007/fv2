package com.thumbshade.app.notif

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.thumbshade.app.rules.HoldStore

/** Things the shade and the rules do to a notification. */
object NotifOps {
    private const val TAG = "ThumbShadeOps"

    private fun options(): Bundle? = if (Build.VERSION.SDK_INT >= 34) {
        ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
    } else null

    fun send(context: Context, pi: PendingIntent?, fillIn: Intent? = null): Boolean {
        if (pi == null) return false
        return runCatching {
            pi.send(context, 0, fillIn, null, null, null, options())
            true
        }.onFailure { Log.w(TAG, "send failed", it) }.getOrDefault(false)
    }

    fun open(context: Context, item: ShadeItem): Boolean {
        com.thumbshade.app.ai.AiHub.record(item, com.thumbshade.app.ai.Engagement.Event.OPENED)
        val ok = send(context, item.contentIntent)
        if (!ok) {
            // No content intent: open the app instead.
            context.packageManager.getLaunchIntentForPackage(item.pkg)?.let {
                runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        if ((item.sbn.notification.flags and android.app.Notification.FLAG_AUTO_CANCEL) != 0) dismiss(item, learn = false)
        return ok
    }

    /** [learn]: count it as "you swiped this away" (not for clear-all or auto-cancel). */
    fun dismiss(item: ShadeItem, learn: Boolean = true) {
        if (!item.clearable) return
        if (learn) com.thumbshade.app.ai.AiHub.record(item, com.thumbshade.app.ai.Engagement.Event.DISMISSED)
        ShadeListenerService.cancel(item.key)
    }

    fun press(context: Context, action: NAction, item: ShadeItem? = null): Boolean {
        item?.let { com.thumbshade.app.ai.AiHub.record(it, com.thumbshade.app.ai.Engagement.Event.ACTION) }
        return send(context, action.intent)
    }

    fun reply(context: Context, action: NAction, text: String, item: ShadeItem? = null): Boolean {
        item?.let { com.thumbshade.app.ai.AiHub.record(it, com.thumbshade.app.ai.Engagement.Event.REPLIED) }
        val pi = action.intent ?: return false
        val inputs = action.remoteInputs.toTypedArray()
        if (inputs.isEmpty()) return false
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return send(context, pi, intent)
    }

    fun snooze(item: ShadeItem, minutes: Int) {
        HoldStore.hold(item, System.currentTimeMillis() + minutes * 60_000L, HoldStore.Reason.SNOOZE)
    }

    fun copy(context: Context, label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun openUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun dial(context: Context, number: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun share(context: Context, text: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun appInfo(context: Context, pkg: String) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
