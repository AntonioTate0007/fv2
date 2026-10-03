package com.thumbshade.app.notif

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tracks the active media session so the shade and the button can show a player. */
object MediaHub {
    data class Media(
        val pkg: String,
        val title: String,
        val artist: String,
        val album: String,
        val art: Bitmap?,
        val durationMs: Long,
        val positionMs: Long,
        val positionUpdatedAt: Long,
        val speed: Float,
        val playing: Boolean,
        val actions: Long,
        val customActions: List<PlaybackState.CustomAction>,
        val nextTitle: String = "",
    ) {
        /** Position extrapolated to now. */
        fun livePosition(): Long {
            if (!playing) return positionMs
            val elapsed = SystemClock.elapsedRealtime() - positionUpdatedAt
            val p = positionMs + (elapsed * speed).toLong()
            return if (durationMs > 0) p.coerceIn(0, durationMs) else p
        }

        fun can(action: Long) = actions and action != 0L
    }

    private val _state = MutableStateFlow<Media?>(null)
    val state: StateFlow<Media?> = _state

    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() {
            controller = null
            _state.value = null
        }
    }

    fun onListenerConnected(service: ShadeListenerService) {
        val msm = service.getSystemService(MediaSessionManager::class.java) ?: return
        manager = msm
        val cn = ComponentName(service, ShadeListenerService::class.java)
        msm.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
        pick(msm.getActiveSessions(cn))
    }

    fun onListenerDisconnected() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
        _state.value = null
    }

    private fun pick(list: List<MediaController>?) {
        val chosen = list.orEmpty().firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.orEmpty().firstOrNull { it.metadata != null }
        if (chosen?.sessionToken == controller?.sessionToken && chosen != null) return
        controller?.unregisterCallback(callback)
        controller = chosen
        chosen?.registerCallback(callback, main)
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) {
            _state.value = null
            return
        }
        val ps = c.playbackState
        _state.value = Media(
            pkg = c.packageName,
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "",
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = ps?.position ?: 0,
            positionUpdatedAt = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed ?: 1f,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            actions = ps?.actions ?: 0,
            customActions = ps?.customActions.orEmpty(),
            nextTitle = runCatching {
                val queue = c.queue.orEmpty()
                val i = queue.indexOfFirst { it.queueId == ps?.activeQueueItemId }
                queue.getOrNull(i + 1)?.takeIf { i >= 0 }?.description?.title?.toString()
            }.getOrNull().orEmpty(),
        )
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) = controller?.transportControls?.seekTo(ms)
    fun stop() = controller?.transportControls?.stop()
    fun seekBy(deltaMs: Long) {
        val m = _state.value ?: return
        seekTo((m.livePosition() + deltaMs).coerceAtLeast(0))
    }

    fun custom(action: PlaybackState.CustomAction) =
        controller?.transportControls?.sendCustomAction(action, action.extras)

    fun openApp(context: android.content.Context) {
        val c = controller ?: return
        val pi = c.sessionActivity
        if (pi != null) NotifOps.send(context, pi) else {
            context.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                runCatching { context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    fun customIcon(context: android.content.Context, action: PlaybackState.CustomAction): android.graphics.drawable.Drawable? {
        val pkg = controller?.packageName ?: return null
        return runCatching {
            context.packageManager.getResourcesForApplication(pkg).getDrawable(action.icon, null)
        }.getOrNull()
    }
}
