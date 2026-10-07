package com.thumbshade.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import com.thumbshade.app.access.AssistService
import com.thumbshade.app.data.GestureAction
import com.thumbshade.app.data.GestureType
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.MediaHub
import com.thumbshade.app.notif.NotifOps
import com.thumbshade.app.notif.NotificationRepo
import com.thumbshade.app.notif.ShadeFilter
import com.thumbshade.app.notif.ShadeListenerService
import com.thumbshade.app.rules.Effects
import com.thumbshade.app.rules.HoldStore

object GestureRunner {
    fun run(context: Context, action: GestureAction) {
        when (action.type) {
            GestureType.NONE -> Unit
            GestureType.TOGGLE_SHADE -> OverlayService.instance?.toggleShade()
                ?: OverlayService.send(context, OverlayService.ACTION_TOGGLE_SHADE)
            GestureType.OPEN_LATEST -> {
                val latest = ShadeFilter.visible(NotificationRepo.items.value, SettingsRepo.current)
                    .maxByOrNull { it.postTime }
                if (latest != null) NotifOps.open(context, latest) else Effects.toast(context, "No notifications")
            }
            GestureType.OPEN_APP -> launch(context, action.arg)
            GestureType.QUICK_TEXT -> com.thumbshade.app.access.QuickContacts.text(context, com.thumbshade.app.access.QuickContacts.number(action.arg))
            GestureType.OPEN_APP_NOTIFICATION -> {
                // Straight into the newest notification (the chat, the email…), else just the app.
                val newest = NotificationRepo.items.value.filter { it.pkg == action.arg }.maxByOrNull { it.postTime }
                if (newest != null) NotifOps.open(context, newest) else launch(context, action.arg)
            }
            GestureType.APP_SCREEN, GestureType.SHORTCUT, GestureType.CUSTOM_INTENT -> fire(context, action.type, action.arg)
            GestureType.BACK -> global(context, AccessibilityService.GLOBAL_ACTION_BACK)
            GestureType.HOME -> global(context, AccessibilityService.GLOBAL_ACTION_HOME)
            GestureType.RECENTS -> global(context, AccessibilityService.GLOBAL_ACTION_RECENTS)
            GestureType.LAST_APP -> {
                if (global(context, AccessibilityService.GLOBAL_ACTION_RECENTS)) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        global(context, AccessibilityService.GLOBAL_ACTION_RECENTS)
                    }, 250)
                }
            }
            GestureType.NOTIFICATIONS -> global(context, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            GestureType.QUICK_SETTINGS -> global(context, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            GestureType.SCREENSHOT -> global(context, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            GestureType.LOCK_SCREEN -> global(context, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            GestureType.ASSISTANT -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                runCatching { context.startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            GestureType.TORCH -> Effects.toggleTorch(context)
            GestureType.TOGGLE_MUTE -> Effects.toggleMute(context)
            GestureType.PASTE_TEXT -> {
                val text = action.arg.ifBlank { SettingsRepo.current.savedTexts.firstOrNull().orEmpty() }
                if (text.isBlank()) {
                    Effects.toast(context, "Add a saved text in Settings first")
                } else if (AssistService.instance?.paste(text) != true) {
                    NotifOps.copy(context, "Saved text", text)
                    Effects.toast(context, "Copied. Turn on the accessibility service to paste directly")
                }
            }
            GestureType.HIDE_BUTTON -> OverlayService.instance?.hideButtonFor(SettingsRepo.current.hideSeconds)
            GestureType.CLEAR_ALL -> ShadeListenerService.cancelAll()
            GestureType.RELEASE_HELD -> {
                val n = HoldStore.releaseAll()
                Effects.toast(context, if (n == 0) "Nothing is being held" else "Released $n")
            }
            GestureType.NEXT_MODE -> SettingsRepo.update { s ->
                val next = (s.activeMode + 1) % s.modes.size.coerceAtLeast(1)
                Effects.toast(context, "Mode: " + (s.modes.getOrNull(next)?.name ?: ""))
                s.copy(activeMode = next)
            }
            GestureType.MEDIA_PLAY_PAUSE -> MediaHub.playPause()
            GestureType.MEDIA_NEXT -> MediaHub.next()
        }
    }

    private fun global(context: Context, action: Int): Boolean {
        val svc = AssistService.instance
        if (svc == null) {
            Effects.toast(context, "Turn on the ThumbShade accessibility service for this gesture")
            return false
        }
        return svc.performGlobalAction(action)
    }

    fun launch(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: runCatching { Intent.parseUri(pkg, Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Effects.toast(context, "Could not open it") }
    }

    /**
     * Runs an app screen ("pkg/Class"), a shortcut (an intent URI) or a custom intent
     * ("activity:", "broadcast:" or "service:" followed by an intent URI).
     * Returns an error message, or null when it worked.
     */
    fun fire(context: Context, type: GestureType, arg: String): String? {
        if (arg.isBlank()) return "Nothing chosen yet"
        val result = runCatching {
            when (type) {
                GestureType.APP_SCREEN -> {
                    val cn = android.content.ComponentName.unflattenFromString(arg) ?: error("Not an app screen")
                    context.startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                GestureType.SHORTCUT -> context.startActivity(Intent.parseUri(arg, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else -> {
                    val target = arg.substringBefore(':')
                    val intent = Intent.parseUri(arg.substringAfter(':'), Intent.URI_INTENT_SCHEME)
                    when (target) {
                        "broadcast" -> context.sendBroadcast(intent)
                        "service" -> context.startService(intent)
                        else -> context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        val error = result.exceptionOrNull() ?: return null
        val message = when (error) {
            is android.content.ActivityNotFoundException -> "No app can open this"
            is SecurityException -> "The app doesn't let other apps open this"
            else -> error.message ?: "Could not run it"
        }
        Effects.toast(context, message)
        return message
    }

    @Suppress("unused")
    private val screenshotSupported = Build.VERSION.SDK_INT >= 28
}
