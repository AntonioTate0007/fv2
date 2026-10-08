package com.thumbshade.app.access

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional. Reports which app is in front and where the keyboard is, performs Back / Home /
 * Recents, and pastes saved texts into the focused field. It never reads screen text.
 */
class AssistService : AccessibilityService() {
    data class State(
        val foregroundPkg: String? = null,
        val keyboardVisible: Boolean = false,
        val keyboardTop: Int? = null,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refresh(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refresh(event)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // A text field gaining focus usually means the keyboard is about to open.
                if (event.source?.isEditable == true) {
                    listOf(150L, 450L, 900L).forEach { d -> android.os.Handler(mainLooper).postDelayed({ refresh(null) }, d) }
                }
            }
        }
    }

    private fun refresh(event: AccessibilityEvent?) {
        val windows: List<AccessibilityWindowInfo> = runCatching { windows }.getOrNull().orEmpty()
        val app = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        var pkg = runCatching { app?.root?.packageName?.toString() }.getOrNull()
        if (pkg == null && event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) pkg = event.packageName?.toString()
        if (pkg == packageName) pkg = _state.value.foregroundPkg

        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val bounds = Rect()
        ime?.getBoundsInScreen(bounds)
        val kbVisible = ime != null && bounds.height() > 0
        _state.value = State(pkg ?: _state.value.foregroundPkg, kbVisible, if (kbVisible) bounds.top else null)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        _state.value = State()
        super.onDestroy()
    }

    /** Puts [text] into the focused text field. */
    fun paste(text: String): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable) return false
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("ThumbShade", text))
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        // Fallback: append through set-text.
        val existing = node.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        @Volatile
        var instance: AssistService? = null
            private set

        /** Used by [UsageWatcher] while the accessibility service is off. */
        fun reportForeground(pkg: String) {
            if (instance == null && _state.value.foregroundPkg != pkg) _state.value = _state.value.copy(foregroundPkg = pkg)
        }

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, AssistService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
