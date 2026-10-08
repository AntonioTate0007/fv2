package com.thumbshade.app.overlay

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.thumbshade.app.App
import com.thumbshade.app.R
import com.thumbshade.app.access.AssistService
import com.thumbshade.app.data.AppSettings
import com.thumbshade.app.data.ClusterSide
import com.thumbshade.app.data.AppBehavior
import com.thumbshade.app.data.EmptyBehavior
import com.thumbshade.app.data.GestureType
import com.thumbshade.app.data.KeyboardBehavior
import com.thumbshade.app.data.LandscapeBehavior
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.NotificationRepo
import com.thumbshade.app.notif.ShadeItem
import com.thumbshade.app.ui.MainActivity
import com.thumbshade.app.ui.ShadeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that owns the floating button, the icon cluster around it and the shade.
 */
class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val owner = OverlayOwner()

    private var buttonView: GestureFrame? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var clusterView: View? = null
    private var clusterParams: WindowManager.LayoutParams? = null
    private var shadeView: View? = null
    private val shadeState = MutableTransitionState(false)

    /** Compose-observable state for the button face. */
    private var pulse by mutableIntStateOf(0)
    /** Bumps each time the shade closes, so the icon cluster can fold away. */
    private var shadeClosed by mutableIntStateOf(0)
    /** Bumps when a long press is released without picking anything: the icons fall out. */
    private var iconsDrop by mutableIntStateOf(0)
    private var battery by mutableStateOf<Float?>(null)
    private var buttonHiddenUntil = 0L
    private var lastApplied: AppSettings? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            battery = if (plugged && level >= 0) level / scale.toFloat() else null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        com.thumbshade.app.access.UsageWatcher.start(this)
        instance = this
        wm = getSystemService(WindowManager::class.java)!!
        // Android can refuse a foreground start (e.g. when the app isn't visible). Don't crash; stop
        // quietly and let the next app launch start the service again.
        if (runCatching { startAsForeground() }.onFailure { android.util.Log.w("ThumbShade", "startForeground refused", it) }.isFailure) {
            stopSelf()
            return
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        scope.launch {
            // Re-post the running notification when its buttons are switched on or off.
            SettingsRepo.state.map { it.notificationControls }.distinctUntilChanged().drop(1).collect { startAsForeground() }
        }
        scope.launch {
            combine(SettingsRepo.state, NotificationRepo.items, AssistService.state) { s, items, assist -> Triple(s, items, assist) }
                .collect { (s, items, assist) -> applyState(s, items.size, assist) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_SHADE -> toggleShade()
            ACTION_OPEN_SHADE -> openShade()
            ACTION_TOGGLE_BUTTON -> SettingsRepo.update { it.copy(showButton = !it.showButton) }
            ACTION_STOP -> {
                SettingsRepo.update { it.copy(serviceEnabled = false) }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        com.thumbshade.app.access.UsageWatcher.stop()
        if (instance === this) instance = null
        runCatching { unregisterReceiver(batteryReceiver) }
        removeButton()
        removeShadeNow()
        owner.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val open = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_SHADE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggleButton = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_BUTTON),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val settings = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_shade)
            .setContentTitle("ThumbShade is running")
            .setContentText("Tap to open the shade")
            .setContentIntent(open)
        if (SettingsRepo.current.notificationControls) {
            builder.addAction(0, "Toggle shade", open)
                .addAction(0, "Toggle button", toggleButton)
                .addAction(0, "Settings", settings)
        }
        val notification: Notification = builder
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // region Button

    /** Docked against an edge right now (saved dock or a temporary dock from a behaviour). */
    var docked by mutableStateOf(false)
        private set
    var dockedOnRight by mutableStateOf(true)
        private set
    /** Pulled out from the edge for a moment to show who just wrote. */
    var peeking by mutableStateOf(false)
        private set
    private var peekAnim: android.animation.ValueAnimator? = null
    private val peekBack = Runnable { endPeek() }
    /** Drives the button's appear / hide animation. */
    val buttonShown = MutableTransitionState(false)
    private var lastSize: Pair<Int, Int>? = null
    private var hiding = false

    private fun landscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post { reapply() }
    }

    private fun reapply() = applyState(SettingsRepo.current, NotificationRepo.items.value.size, AssistService.state.value)

    private fun applyState(s: AppSettings, count: Int, assist: AssistService.State) {
        val now = System.currentTimeMillis()
        val inChosenApp = assist.foregroundPkg != null && assist.foregroundPkg in s.hideInApps
        val keyboard = assist.keyboardVisible
        val empty = count == 0

        val hide = !s.showButton || now < buttonHiddenUntil || !Settings.canDrawOverlays(this) ||
            (inChosenApp && s.appBehavior == AppBehavior.HIDE) ||
            (keyboard && s.keyboardBehavior == KeyboardBehavior.HIDE) ||
            (empty && s.emptyBehavior == EmptyBehavior.HIDE) ||
            (landscape() && s.landscapeBehavior == LandscapeBehavior.HIDE)
        if (hide) {
            hideButtonAnimated()
            return
        }
        val forceDock = (inChosenApp && s.appBehavior == AppBehavior.DOCK) ||
            (keyboard && s.keyboardBehavior == KeyboardBehavior.DOCK) ||
            (empty && s.emptyBehavior == EmptyBehavior.DOCK)
        val clickThrough = (inChosenApp && s.appBehavior == AppBehavior.CLICK_THROUGH) ||
            (keyboard && s.keyboardBehavior == KeyboardBehavior.CLICK_THROUGH)

        if (!dragging) {
            docked = s.buttonDocked || forceDock
        }
        val bounds = OverlayWindows.screenBounds(this)
        val useDockedLook = docked && s.dockedLook
        val w = OverlayWindows.dp(this, if (useDockedLook) s.dockedWidthDp else s.buttonWidthDp)
        val h = OverlayWindows.dp(this, if (useDockedLook) s.dockedHeightDp else s.buttonHeightDp)

        // The cluster window depends on settings, so rebuild everything when those change.
        val rebuild = lastApplied?.let {
            it.iconCluster != s.iconCluster || it.clusterSide != s.clusterSide || it.clusterIconDp != s.clusterIconDp ||
                it.clusterMax != s.clusterMax
        } ?: false
        if (rebuild) removeButton()
        lastApplied = s
        if (buttonView == null) addButton(s, w, h)
        val params = buttonParams ?: return
        hiding = false
        buttonShown.targetState = true

        if (!dragging) {
            val portraitW = min(bounds.width(), bounds.height())
            val portraitH = max(bounds.width(), bounds.height())
            val land = landscape()
            var y = if (s.buttonYFrac >= 0) {
                Placement.fromFrac(s.buttonYFrac, h, bounds.height(), portraitH, land, s.landscapeBehavior)
            } else (bounds.height() * 0.62f).toInt()
            val x: Int
            if (docked) {
                // A temporary dock goes to whichever side the button is nearest.
                val right = if (s.buttonDocked) s.dockRight else
                    (s.buttonXFrac < 0f || s.buttonXFrac >= 0.5f)
                dockedOnRight = right
                x = Placement.dockX(bounds.width(), w, right, s.snapStyle)
            } else {
                x = if (s.buttonXFrac >= 0) {
                    Placement.fromFrac(s.buttonXFrac, w, bounds.width(), portraitW, land, s.landscapeBehavior)
                } else bounds.width() - w - OverlayWindows.dp(this, 8)
            }
            // Keyboard: lift the button above it without saving that position.
            val kbTop = assist.keyboardTop
            if (keyboard && s.keyboardBehavior == KeyboardBehavior.MOVE_ABOVE && kbTop != null) {
                y = min(y, kbTop - h - OverlayWindows.dp(this, 12))
            }
            y = y.coerceIn(0, max(0, bounds.height() - h))

            // While peeking, the button stays pulled out; the peek puts it back itself.
            val targetX = if (peeking) params.x else x
            var changed = params.x != targetX || params.y != y || params.width != w || params.height != h
            params.x = targetX
            params.y = y
            params.width = w
            params.height = h
            // Click-through: untouchable windows must be at most 80% opaque for touches to pass.
            val flags = if (clickThrough) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            val alpha = if (clickThrough) 0.8f else 1f
            if (flags != params.flags || alpha != params.alpha) {
                params.flags = flags
                params.alpha = alpha
                changed = true
            }
            if (changed) updateButtonLayout()
        }
        if (lastSize != Pair(w, h) && clusterView != null) {
            // Cluster window is sized from the button; rebuild it for the new size.
            clusterView?.let { v -> runCatching { wm.removeView(v) } }
            clusterView = null
            clusterParams = null
            if (s.iconCluster) addCluster(s)
        }
        lastSize = Pair(w, h)
    }

    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton(s: AppSettings, w: Int, h: Int) {
        val params = OverlayWindows.params(w, h, touchable = true, noLimits = true)
        val bounds = OverlayWindows.screenBounds(this)
        params.x = bounds.width() - w
        params.y = (bounds.height() * 0.62f).toInt()

        val frame = GestureFrame(this)
        // Compose finds its lifecycle on the window's root view, so the root needs the owner too.
        owner.attach(frame)
        val face = OverlayWindows.composeView(this, owner) {
            ButtonFace(pulse = pulse, battery = battery, docked = docked, dockedRight = dockedOnRight, shown = buttonShown, peeking = peeking)
        }
        frame.addView(face, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.listener = object : GestureFrame.Listener {
            private var startX = 0
            private var startY = 0

            override fun onTap() = GestureRunner.run(this@OverlayService, SettingsRepo.current.let { it.modes.getOrNull(it.activeMode) ?: it.modes.first() }.tap)

            override fun onSwipe(direction: GestureFrame.Direction) {
                val st = SettingsRepo.current
                val mode = st.modes.getOrNull(st.activeMode) ?: st.modes.first()
                val action = when (direction) {
                    GestureFrame.Direction.UP -> mode.up
                    GestureFrame.Direction.DOWN -> mode.down
                    GestureFrame.Direction.LEFT -> mode.left
                    GestureFrame.Direction.RIGHT -> mode.right
                }
                GestureRunner.run(this@OverlayService, action)
            }

            private fun mode() = SettingsRepo.current.let { it.modes.getOrNull(it.activeMode) ?: it.modes.first() }

            override fun wheelEnabled() = mode().wheel && mode().wheelSlots.any { it.type != GestureType.NONE }

            override fun switcherEnabled() = SettingsRepo.current.longPressAction == com.thumbshade.app.data.LongPressAction.APP_SWITCHER

            override fun moveGesture(): com.thumbshade.app.data.MoveGesture {
                val st = SettingsRepo.current
                return if (st.longPressAction == com.thumbshade.app.data.LongPressAction.MOVE) com.thumbshade.app.data.MoveGesture.LONG_PRESS else st.moveGesture
            }

            override fun moveHoldMs() = SettingsRepo.current.moveHoldMs.toLong()

            override fun onMoveArmed() {
                Haptics.play(this@OverlayService, Haptics.Kind.CONFIRM, SettingsRepo.current.switcherVibrationStrength.coerceAtLeast(0.6f))
            }

            override fun onDoubleTap() {
                val a = mode().doubleTap
                if (a.type == GestureType.NONE) onTap() else GestureRunner.run(this@OverlayService, a)
            }

            override fun onSwitcherStart() {
                val c = buttonCenter() ?: return
                val size = buttonSize() ?: return
                val st = SettingsRepo.current
                val appsOnly = com.thumbshade.app.access.RecentApps.forSwitcher(this@OverlayService, st.switcherCount.coerceIn(1, com.thumbshade.app.data.GestureMode.SLOT_COUNT))
                // Favourite people come later: keep holding and they fold out in the outer ring.
                val apps = appsOnly.take(com.thumbshade.app.data.GestureMode.RINGS[0] + com.thumbshade.app.data.GestureMode.RINGS[1])
                switcherCenter = c
                switcherHalf = size
                if (apps.none { it.type != GestureType.NONE }) {
                    // Nothing to switch to: still let the icons fall out on release.
                    switcherOpen = true
                    return
                }
                ActionWheel.show(
                    this@OverlayService,
                    apps,
                    c.first.toFloat(), c.second.toFloat(), size.first / 2f, size.second / 2f,
                )
                ScrollSounds.prepare(this@OverlayService, st.switcherSound)
                ScrollSounds.play(this@OverlayService, st.switcherSound, st.switcherSoundVolume, st.scrollSoundRespectSilent, frame)
                switcherOpen = true
                if (st.switcherVibration) Haptics.play(this@OverlayService, Haptics.Kind.OPEN, st.switcherVibrationStrength)
                else frame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            private var switcherCenter: Pair<Int, Int>? = null
            private var switcherHalf: Pair<Int, Int>? = null

            override fun onSwitcherHoldLonger(): Boolean {
                if (!switcherOpen) return false
                val people = com.thumbshade.app.access.QuickContacts.forSwitcher(this@OverlayService)
                if (people.isEmpty()) return false
                val opened = if (ActionWheel.isOpen) ActionWheel.extendOuter(people) else {
                    // No apps to show: open the wheel with just the favourites, in the outer ring.
                    val c = switcherCenter ?: return false
                    val sz = switcherHalf ?: return false
                    val appSlots = com.thumbshade.app.data.GestureMode.RINGS[0] + com.thumbshade.app.data.GestureMode.RINGS[1]
                    ActionWheel.show(this@OverlayService, List(appSlots) { com.thumbshade.app.data.GestureAction() } + people,
                        c.first.toFloat(), c.second.toFloat(), sz.first / 2f, sz.second / 2f)
                    ActionWheel.isOpen
                }
                if (opened) {
                    val st = SettingsRepo.current
                    if (st.switcherVibration) Haptics.play(this@OverlayService, Haptics.Kind.OPEN, st.switcherVibrationStrength)
                    ScrollSounds.play(this@OverlayService, st.switcherSound, st.switcherSoundVolume, st.scrollSoundRespectSilent, frame, force = true)
                }
                return opened
            }

            override fun moveExtraMs() = SettingsRepo.current.moveExtraMs.toLong()

            /** True while the wheel on screen is the app switcher (not a gesture-mode wheel). */
            private var switcherOpen = false

            override fun onWheelStart() {
                val c = buttonCenter() ?: return
                val size = buttonSize() ?: return
                ActionWheel.show(this@OverlayService, mode(), c.first.toFloat(), c.second.toFloat(), size.first / 2f, size.second / 2f)
                val st = SettingsRepo.current
                ScrollSounds.prepare(this@OverlayService, st.scrollSound)
                if (st.wheelHaptic) frame.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }

            override fun onWheelMove(rawX: Float, rawY: Float) {
                if (ActionWheel.move(rawX, rawY)) {
                    val st = SettingsRepo.current
                    if (switcherOpen) {
                        if (st.switcherVibration) Haptics.play(this@OverlayService, Haptics.Kind.TICK, st.switcherVibrationStrength)
                        ScrollSounds.play(this@OverlayService, st.switcherSound, st.switcherSoundVolume, st.scrollSoundRespectSilent, frame)
                    } else {
                        if (st.wheelHaptic) frame.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        ScrollSounds.play(this@OverlayService, st.scrollSound, st.scrollSoundVolume, st.scrollSoundRespectSilent, frame)
                    }
                }
            }

            override fun onWheelEnd(run: Boolean) {
                val action = ActionWheel.end()
                val st = SettingsRepo.current
                if (run && action != null && switcherOpen) {
                    if (st.switcherVibration) Haptics.play(this@OverlayService, Haptics.Kind.CONFIRM, st.switcherVibrationStrength)
                    // Picking one gets a slightly louder tick.
                    ScrollSounds.play(this@OverlayService, st.switcherSound, (st.switcherSoundVolume * 1.4f).coerceAtMost(1f), st.scrollSoundRespectSilent, frame, force = true)
                }
                val wasSwitcher = switcherOpen
                switcherOpen = false
                if (run && wasSwitcher && action != null && action.arg.isNotBlank() &&
                    (action.type == GestureType.OPEN_APP || action.type == GestureType.OPEN_APP_NOTIFICATION)
                ) com.thumbshade.app.ai.AppPredictor.onPicked(this@OverlayService, action.arg)
                if (run && action != null) GestureRunner.run(this@OverlayService, action)
                else if (run && wasSwitcher && st.releaseShowsIcons) iconsDrop++
            }

            override fun onDragStart() {
                cancelPeek()
                dragging = true
                val st = SettingsRepo.current
                // Undocking: switch to the free-floating size and pull the button fully on screen.
                if (docked) {
                    docked = false
                    val nw = OverlayWindows.dp(this@OverlayService, st.buttonWidthDp)
                    val nh = OverlayWindows.dp(this@OverlayService, st.buttonHeightDp)
                    val b = OverlayWindows.screenBounds(this@OverlayService)
                    params.width = nw
                    params.height = nh
                    params.x = if (dockedOnRight) b.width() - nw else 0
                }
                startX = params.x
                startY = params.y
                updateButtonLayout()
                frame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onDrag(dx: Float, dy: Float) {
                val b = OverlayWindows.screenBounds(this@OverlayService)
                val st = SettingsRepo.current
                val p = Placement.clamp((startX + dx).toInt(), (startY + dy).toInt(), params.width, params.height, b.width(), b.height(), st.allowOffscreen)
                params.x = p.x
                params.y = p.y
                updateButtonLayout()
            }

            override fun onDragEnd(velocityX: Float) {
                val b = OverlayWindows.screenBounds(this@OverlayService)
                val st = SettingsRepo.current
                val flingPx = st.flingVelocityDp * resources.displayMetrics.density
                val dock = Placement.releaseDock(params.x, params.width, b.width(), velocityX, flingPx, st.snapToEdge, st.snapZonePercent / 100f)
                val portrait = !landscape()
                val yFrac = Placement.toFrac(params.y, params.height, b.height())
                val xFrac = Placement.toFrac(params.x.coerceAtLeast(0), params.width, b.width())
                dragging = false
                SettingsRepo.update {
                    it.copy(
                        buttonDocked = dock != null,
                        dockRight = dock ?: it.dockRight,
                        buttonYFrac = if (portrait || it.landscapeBehavior == LandscapeBehavior.RELATIVE) yFrac else it.buttonYFrac,
                        buttonXFrac = if (dock == null) xFrac else if (dock) 1f else 0f,
                    )
                }
                reapply()
            }
        }
        runCatching { wm.addView(frame, params) }.onFailure { return }
        buttonView = frame
        buttonParams = params
        lastSize = Pair(w, h)
        if (s.iconCluster) addCluster(s)
    }

    private fun clusterSizePx(s: AppSettings): Pair<Int, Int> {
        val icon = OverlayWindows.dp(this, s.clusterIconDp)
        val gap = OverlayWindows.dp(this, 6)
        val bw = buttonParams?.width ?: OverlayWindows.dp(this, s.buttonWidthDp)
        val bh = buttonParams?.height ?: OverlayWindows.dp(this, s.buttonHeightDp)
        return when (s.clusterSide) {
            ClusterSide.RING -> Pair(bw + 2 * (icon + gap), bh + 2 * (icon + gap))
            ClusterSide.ABOVE -> Pair(max(bw, (icon + gap) * s.clusterMax), icon + gap)
            ClusterSide.SIDE -> Pair(icon + gap, max(bh, (icon + gap) * s.clusterMax))
        }
    }

    private fun addCluster(s: AppSettings) {
        val (w, h) = clusterSizePx(s)
        val params = OverlayWindows.params(w, h, touchable = false, noLimits = true)
        val view = OverlayWindows.composeView(this, owner) { IconCluster(pulse, shadeClosed, iconsDrop) }
        runCatching { wm.addView(view, params) }.onFailure { return }
        clusterView = view
        clusterParams = params
        positionCluster()
    }

    private fun positionCluster() {
        val bp = buttonParams ?: return
        val cp = clusterParams ?: return
        val s = SettingsRepo.current
        val b = OverlayWindows.screenBounds(this)
        when (s.clusterSide) {
            ClusterSide.RING -> {
                cp.x = bp.x + bp.width / 2 - cp.width / 2
                cp.y = bp.y + bp.height / 2 - cp.height / 2
            }
            ClusterSide.ABOVE -> {
                cp.x = (bp.x + bp.width / 2 - cp.width / 2).coerceIn(0, max(0, b.width() - cp.width))
                cp.y = bp.y - cp.height
            }
            ClusterSide.SIDE -> {
                val onRight = bp.x + bp.width / 2 > b.width() / 2
                cp.x = if (onRight) bp.x - cp.width else bp.x + bp.width
                cp.y = bp.y + bp.height / 2 - cp.height / 2
            }
        }
        clusterView?.let { v -> runCatching { wm.updateViewLayout(v, cp) } }
    }

    private fun updateButtonLayout() {
        val v = buttonView ?: return
        val p = buttonParams ?: return
        runCatching { wm.updateViewLayout(v, p) }
        positionCluster()
    }

    /** Plays the hide animation, then removes the windows. */
    private fun hideButtonAnimated() {
        if (buttonView == null || hiding) return
        hiding = true
        buttonShown.targetState = false
        clusterView?.let { v -> runCatching { wm.removeView(v) } }
        clusterView = null
        clusterParams = null
        main.postDelayed({ if (hiding && !buttonShown.targetState) removeButton() }, 650)
    }

    private fun removeButton() {
        buttonView?.let { v -> runCatching { wm.removeView(v) } }
        clusterView?.let { v -> runCatching { wm.removeView(v) } }
        buttonView = null
        clusterView = null
        buttonParams = null
        clusterParams = null
        hiding = false
        buttonShown.targetState = false
    }

    /** Plays the new-notification animation, for the "Try it" button in settings. */
    fun testPulse() {
        pulse++
    }

    /** Plays the hide and then the appear animation, for the "Try it" button in settings. */
    fun replayAppear() {
        if (buttonView == null) return
        buttonShown.targetState = false
        main.postDelayed({ if (buttonView != null) buttonShown.targetState = true }, 550)
    }

    /** Centre of the button on screen, for effects drawn around it. */
    fun buttonCenter(): Pair<Int, Int>? {
        val p = buttonParams ?: return null
        return Pair(p.x + p.width / 2, p.y + p.height / 2)
    }

    fun buttonSize(): Pair<Int, Int>? = buttonParams?.let { Pair(it.width, it.height) }

    fun hideButtonFor(seconds: Int) {
        buttonHiddenUntil = System.currentTimeMillis() + seconds * 1000L
        hideButtonAnimated()
        main.postDelayed({ reapply() }, seconds * 1000L + 50)
    }

    // endregion

    // region Shade

    val isShadeOpen: Boolean get() = shadeView != null && shadeState.targetState

    fun toggleShade() {
        if (isShadeOpen) closeShade() else openShade()
    }

    fun openShade() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            if (SettingsRepo.current.lockscreenShade) {
                startActivity(Intent(this, ShadeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        if (shadeView != null) {
            shadeState.targetState = true
            return
        }
        val params = OverlayWindows.params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            touchable = true,
            focusable = true,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            val overlay = SettingsRepo.current.shadeOverlay
            if (android.os.Build.VERSION.SDK_INT >= 31 && (overlay == com.thumbshade.app.data.ShadeOverlay.BLUR || overlay == com.thumbshade.app.data.ShadeOverlay.DIM_BLUR)) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = (SettingsRepo.current.blurRadiusDp * resources.displayMetrics.density).toInt()
            }
        }
        val frame = KeyFrame(this) { closeShade() }
        owner.attach(frame)
        val content = OverlayWindows.composeView(this, owner) {
            ShadeScreen(
                visibleState = shadeState,
                onClose = { closeShade() },
                onOpenSettings = {
                    closeShade()
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }
        frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        shadeState.targetState = true
        runCatching { wm.addView(frame, params) }.onFailure { return }
        shadeView = frame
        frame.requestFocus()
    }

    fun closeShade() {
        if (shadeView == null) return
        shadeClosed++
        ShadeSeen.lastClosedAt = System.currentTimeMillis()
        shadeState.targetState = false
        main.postDelayed({ if (!shadeState.targetState) removeShadeNow() }, 650)
    }

    private fun removeShadeNow() {
        shadeView?.let { v -> runCatching { wm.removeView(v) } }
        shadeView = null
    }

    // endregion

    fun notificationArrived(item: ShadeItem) {
        pulse++
        val s = SettingsRepo.current
        val fromPerson = item.messages.isNotEmpty() || item.category == com.thumbshade.app.rules.Cat.MESSAGE
        if (!s.peekOnContact || !docked || dragging || !fromPerson || buttonView == null) return
        // Only for someone with a picture: look it up off the main thread, then peek.
        Thread {
            val hasPhoto = com.thumbshade.app.notif.ContactPhotos.forItem(this, item) != null
            if (hasPhoto) main.post { startPeek() }
        }.start()
    }

    /** Slides the docked button fully out, shows the sender, and the icons fall out around it. */
    private fun startPeek() {
        val p = buttonParams ?: return
        if (!docked || dragging) return
        val s = SettingsRepo.current
        val b = OverlayWindows.screenBounds(this)
        val margin = OverlayWindows.dp(this, 8)
        val out = if (dockedOnRight) b.width() - p.width - margin else margin
        main.removeCallbacks(peekBack)
        peeking = true
        animateButtonX(out, android.view.animation.OvershootInterpolator(1.4f)) {
            if (s.releaseShowsIcons || s.iconCluster) iconsDrop++
        }
        main.postDelayed(peekBack, s.peekSeconds.coerceIn(1, 15) * 1000L)
    }

    private fun endPeek() {
        if (!peeking) return
        val p = buttonParams ?: run { peeking = false; return }
        val b = OverlayWindows.screenBounds(this)
        val home = Placement.dockX(b.width(), p.width, dockedOnRight, SettingsRepo.current.snapStyle)
        animateButtonX(home, android.view.animation.AccelerateDecelerateInterpolator()) {
            peeking = false
            reapply()
        }
    }

    private fun cancelPeek() {
        main.removeCallbacks(peekBack)
        peekAnim?.cancel()
        peekAnim = null
        peeking = false
    }

    private fun animateButtonX(target: Int, interpolator: android.animation.TimeInterpolator, done: () -> Unit) {
        val p = buttonParams ?: return
        peekAnim?.cancel()
        peekAnim = android.animation.ValueAnimator.ofInt(p.x, target).apply {
            duration = 320
            this.interpolator = interpolator
            addUpdateListener {
                p.x = it.animatedValue as Int
                updateButtonLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) done() }
            })
            start()
        }
    }

    companion object {
        const val ACTION_TOGGLE_SHADE = "com.thumbshade.app.ACTION_TOGGLE_SHADE"
        const val ACTION_OPEN_SHADE = "com.thumbshade.app.ACTION_OPEN_SHADE"
        const val ACTION_TOGGLE_BUTTON = "com.thumbshade.app.ACTION_TOGGLE_BUTTON"
        const val ACTION_STOP = "com.thumbshade.app.ACTION_STOP"
        private const val NOTIFICATION_ID = 7

        @Volatile
        var instance: OverlayService? = null
            private set

        fun startIfEnabled(context: Context) {
            if (!SettingsRepo.current.serviceEnabled) return
            if (!Settings.canDrawOverlays(context)) return
            runCatching {
                context.startForegroundService(Intent(context, OverlayService::class.java))
            }
        }

        fun send(context: Context, action: String) {
            runCatching {
                context.startForegroundService(Intent(context, OverlayService::class.java).setAction(action))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun onNewNotification(item: ShadeItem) {
            instance?.let { svc -> svc.main.post { svc.notificationArrived(item) } }
        }
    }
}

/** Turns raw touches into tap, swipe and long-press-then-drag, using screen coordinates. */
class GestureFrame(context: Context) : FrameLayout(context) {
    companion object {
        const val MOVE_AFTER_MS = 1000L
        /** How long the second tap must be held before the button follows the finger. */
        const val HOLD_TO_MOVE_MS = 150L
    }

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    interface Listener {
        fun onTap()
        fun onSwipe(direction: Direction)
        fun onDragStart()
        fun onDrag(dx: Float, dy: Float)
        /** [velocityX] in px/s, positive = towards the right. */
        fun onDragEnd(velocityX: Float)
        /** Wheel mode: the finger left the button, so show the wheel and follow it. */
        fun wheelEnabled(): Boolean = false
        fun onWheelStart() {}
        fun onWheelMove(rawX: Float, rawY: Float) {}
        /** [run] false when the gesture was cancelled. */
        fun onWheelEnd(run: Boolean) {}
        /** Long press opens the app switcher instead of moving the button. */
        fun switcherEnabled(): Boolean = false
        fun onSwitcherStart() {}
        /** Held still past the super long hold: show the favourites. True if they appeared. */
        fun onSwitcherHoldLonger(): Boolean = false
        /** After the favourites appear, how much longer to hold still before moving. */
        fun moveExtraMs(): Long = 1500
        /** How the button is picked up to move. */
        fun moveGesture(): com.thumbshade.app.data.MoveGesture = com.thumbshade.app.data.MoveGesture.LONG_PRESS
        fun doubleTapHoldMoves(): Boolean = moveGesture() == com.thumbshade.app.data.MoveGesture.DOUBLE_TAP_HOLD
        /** Super long hold: extra time after the long press. */
        fun moveHoldMs(): Long = 1500
        /** The hold was long enough: the button is about to follow the finger. */
        fun onMoveArmed() {}
        fun onDoubleTap() { onTap() }
    }

    var listener: Listener? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private val longPress = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var dragging = false
    private var wheeling = false
    private var velocity: VelocityTracker? = null
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    /** The finger came down for a second tap: holding it moves the button. */
    private var secondTap = false
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    /** A single tap waits briefly in case a second one follows. */
    private val pendingTap = Runnable { listener?.onTap() }
    private val holdToMove = Runnable {
        if (secondTap && !dragging) {
            dragging = true
            downX = lastX
            downY = lastY
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            listener?.onDragStart()
        }
    }
    private val startDrag = Runnable {
        if (moved) return@Runnable
        val gesture = listener?.moveGesture()
        val superHold = listener?.moveHoldMs() ?: MOVE_AFTER_MS
        if (listener?.switcherEnabled() == true) {
            // Long press: the switcher folds out and the finger picks an app, like the wheel.
            wheeling = true
            switcherAt = Pair(lastX, lastY)
            listener?.onSwitcherStart()
            // Keep holding still: the favourites fold out; keep holding still longer and the button moves.
            postDelayed(stageTwo, superHold)
        } else if (gesture == com.thumbshade.app.data.MoveGesture.SUPER_LONG_HOLD) {
            switcherAt = Pair(lastX, lastY)
            postDelayed(superMove, superHold)
        } else if (gesture == com.thumbshade.app.data.MoveGesture.DOUBLE_TAP_HOLD) {
            // Long press does nothing else; moving is double-tap-and-hold.
        } else {
            dragging = true
            listener?.onDragStart()
        }
    }
    /** Super long hold on the switcher: favourites fold out, and (unless moving is double-tap) moving comes next. */
    private val stageTwo = Runnable {
        if (!wheeling) return@Runnable
        val (x, y) = switcherAt ?: return@Runnable
        if (abs(lastX - x) > slop || abs(lastY - y) > slop) return@Runnable
        val movesByHold = listener?.moveGesture() != com.thumbshade.app.data.MoveGesture.DOUBLE_TAP_HOLD
        if (listener?.onSwitcherHoldLonger() == true) {
            switcherAt = Pair(lastX, lastY)
            if (movesByHold) postDelayed(switchToMove, listener?.moveExtraMs() ?: MOVE_AFTER_MS)
        } else if (movesByHold) {
            switchToMove.run()
        }
    }

    /** Holding still on the switcher a while longer means "move the button" instead. */
    private val switchToMove = Runnable {
        if (!wheeling) return@Runnable
        val (x, y) = switcherAt ?: return@Runnable
        if (abs(lastX - x) > slop || abs(lastY - y) > slop) return@Runnable
        listener?.onWheelEnd(false)
        listener?.onMoveArmed()
        wheeling = false
        dragging = true
        downX = lastX
        downY = lastY
        listener?.onDragStart()
    }
    /** Super long hold without the switcher: start moving if the finger stayed put. */
    private val superMove = Runnable {
        if (moved || dragging || wheeling) return@Runnable
        val (x, y) = switcherAt ?: return@Runnable
        if (abs(lastX - x) > slop || abs(lastY - y) > slop) return@Runnable
        listener?.onMoveArmed()
        dragging = true
        downX = lastX
        downY = lastY
        listener?.onDragStart()
    }
    private var switcherAt: Pair<Float, Float>? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                trackRaw(event)
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                switcherAt = null
                moved = false
                dragging = false
                wheeling = false
                val now = android.os.SystemClock.uptimeMillis()
                secondTap = listener?.doubleTapHoldMoves() == true && now - lastTapUp < doubleTapTimeout &&
                    abs(downX - lastTapX) < slop * 4 && abs(downY - lastTapY) < slop * 4
                if (secondTap) {
                    removeCallbacks(pendingTap)
                    postDelayed(holdToMove, HOLD_TO_MOVE_MS)
                } else {
                    postDelayed(startDrag, longPress)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                trackRaw(event)
                lastX = event.rawX
                lastY = event.rawY
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (secondTap && !dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    // Second tap and the finger is already moving: start moving the button now.
                    removeCallbacks(holdToMove)
                    dragging = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    listener?.onDragStart()
                    listener?.onDrag(dx, dy)
                } else if (dragging) {
                    listener?.onDrag(dx, dy)
                } else if (secondTap) {
                    // Waiting to see whether this second tap is held.
                } else if (wheeling) {
                    listener?.onWheelMove(event.rawX, event.rawY)
                } else if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                    moved = true
                    removeCallbacks(startDrag)
                    if (listener?.wheelEnabled() == true) {
                        wheeling = true
                        listener?.onWheelStart()
                        listener?.onWheelMove(event.rawX, event.rawY)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(startDrag)
                removeCallbacks(switchToMove)
                removeCallbacks(holdToMove)
                removeCallbacks(superMove)
                removeCallbacks(stageTwo)
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                when {
                    wheeling -> listener?.onWheelEnd(true)
                    dragging -> {
                        trackRaw(event)
                        val v = velocity
                        v?.computeCurrentVelocity(1000)
                        listener?.onDragEnd(v?.xVelocity ?: 0f)
                    }
                    moved -> listener?.onSwipe(
                        if (abs(dx) > abs(dy)) {
                            if (dx > 0) Direction.RIGHT else Direction.LEFT
                        } else {
                            if (dy > 0) Direction.DOWN else Direction.UP
                        }
                    )
                    secondTap -> {
                        // A quick double tap without holding.
                        performClick()
                        lastTapUp = 0L
                        listener?.onDoubleTap()
                    }
                    listener?.doubleTapHoldMoves() == true -> {
                        // Wait a moment: a second tap would mean "move" or "double tap".
                        performClick()
                        lastTapUp = android.os.SystemClock.uptimeMillis()
                        lastTapX = event.rawX
                        lastTapY = event.rawY
                        postDelayed(pendingTap, doubleTapTimeout)
                    }
                    else -> {
                        performClick()
                        listener?.onTap()
                    }
                }
                dragging = false
                secondTap = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startDrag)
                removeCallbacks(switchToMove)
                removeCallbacks(holdToMove)
                removeCallbacks(superMove)
                removeCallbacks(stageTwo)
                secondTap = false
                if (dragging) listener?.onDragEnd(0f)
                if (wheeling) listener?.onWheelEnd(false)
                dragging = false
                wheeling = false
            }
        }
        return true
    }

    /** The window moves while dragging, so track velocity in screen coordinates. */
    private fun trackRaw(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        velocity?.addMovement(copy)
        copy.recycle()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** Container for the shade window: Back closes the shade. */
class KeyFrame(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    init {
        isFocusableInTouchMode = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
