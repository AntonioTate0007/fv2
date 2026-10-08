package com.jarvis.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Everything that touches the glasses through Meta's Wearables Device Access Toolkit:
 * keeping a device session open, grabbing photos from the glasses camera, and
 * (on glasses that report them) button presses that summon Jarvis.
 */
class GlassesLink(private val scope: CoroutineScope) {

    private val selector by lazy { AutoDeviceSelector() }
    private val sessionLock = Mutex()
    private var session: DeviceSession? = null
    private var inputsJob: Job? = null

    private val _summons = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when a glasses button/touch gesture asks for Jarvis. */
    val summons: SharedFlow<Unit> = _summons

    val isRegistered: Boolean
        get() = JarvisApp.wearablesReady &&
            runCatching { Wearables.registrationState.value == RegistrationState.REGISTERED }.getOrDefault(false)

    /** Opens (or reuses) a session with the glasses. Returns null if they aren't reachable. */
    suspend fun ensureSession(): DeviceSession? = sessionLock.withLock {
        session?.let { if (it.state.value == DeviceSessionState.STARTED) return@withLock it }
        session?.let { runCatching { it.stop() } }
        session = null
        if (!isRegistered) return@withLock null

        val created = Wearables.createSession(selector)
            .onFailure { error, _ -> Log.w(TAG, "createSession failed: ${error.description}") }
            .getOrNull() ?: return@withLock null
        created.start()
        val reached = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
            created.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED }
        }
        if (reached != DeviceSessionState.STARTED) {
            Log.w(TAG, "Session didn't start (state=$reached)")
            runCatching { created.stop() }
            return@withLock null
        }
        session = created
        listenForButtons(created)
        created
    }

    private fun listenForButtons(s: DeviceSession) {
        inputsJob?.cancel()
        val inputs = s.addInputs()
            .onFailure { error, _ -> Log.i(TAG, "Glasses inputs unavailable: ${error.description}") }
            .getOrNull() ?: return
        inputsJob = scope.launch {
            inputs.events.collect { event ->
                val summon = when (event) {
                    is InputEvent.Button -> true
                    is InputEvent.Capture -> event.pressType == CapturePressType.HOLD ||
                        event.pressType == CapturePressType.DOUBLE_PRESS
                    is InputEvent.Select -> true
                    else -> false
                }
                if (summon) _summons.tryEmit(Unit)
            }
        }
    }

    /**
     * Turns the glasses camera on and takes photos while [keepShooting] stays true.
     *
     * - Photo mode: up to 3 shots, returns only the sharpest (motion blur is common
     *   when you're moving your head).
     * - Video mode: up to [VIDEO_FRAMES] shots (at least 2), all returned in time
     *   order so Grok can follow what happens — the closest thing to live video the
     *   xAI API accepts.
     *
     * Returns an empty list if the camera isn't available.
     */
    suspend fun captureFrames(video: Boolean, keepShooting: () -> Boolean): List<ByteArray> {
        val s = ensureSession() ?: return emptyList()
        val camera = s.addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 15))
            .onFailure { error, _ -> Log.w(TAG, "addCamera failed: ${error.description}") }
            .getOrNull() ?: return emptyList()
        val stream = camera.stream
        // Drain frames so the SDK's pipeline keeps flowing; we only want stills.
        val drain = scope.launch { stream.videoStream.collect { } }
        try {
            stream.start().onFailure { error, _ -> Log.w(TAG, "stream.start failed: ${error.description}") }
            val streaming = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                stream.state.first { it == StreamState.STREAMING || it == StreamState.STOPPED || it == StreamState.CLOSED }
            }
            if (streaming != StreamState.STREAMING) return emptyList()

            val maxShots = if (video) VIDEO_FRAMES else PHOTO_SHOTS
            val minShots = if (video) 2 else 1
            val shotsTaken = mutableListOf<Bitmap>()
            var attempts = 0
            do {
                stream.capturePhoto()
                    .onFailure { error, _ -> Log.w(TAG, "capturePhoto failed: ${error.description}") }
                    .getOrNull()
                    ?.let(::toBitmap)
                    ?.let(shotsTaken::add)
                attempts++
            } while (attempts < maxShots && (keepShooting() || attempts < minShots))

            if (video) return shotsTaken.map { toJpeg(it, VIDEO_EDGE) }
            return listOfNotNull(shotsTaken.maxByOrNull(::sharpness)?.let { toJpeg(it, PHOTO_EDGE) })
        } finally {
            drain.cancel()
            runCatching { stream.stop() }
            runCatching { s.removeCamera() }
        }
    }

    fun close() {
        inputsJob?.cancel()
        runCatching { session?.stop() }
        session = null
    }

    private fun toBitmap(photo: PhotoData): Bitmap? = when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
            val buf = photo.data
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) // Android 10+ decodes HEIC natively
        }
    }

    /** Downscale to keep uploads fast; Grok doesn't need full resolution. */
    private fun toJpeg(src: Bitmap, maxEdge: Int): ByteArray {
        val scale = maxEdge.toFloat() / max(src.width, src.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    /** Variance of the Laplacian on a small grayscale copy — higher means less motion blur. */
    private fun sharpness(src: Bitmap): Double {
        val w = 160
        val h = max(1, src.height * w / max(1, src.width))
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        val gray = IntArray(px.size) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = (4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]).toDouble()
            sum += lap
            sumSq += lap * lap
            n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    private companion object {
        const val TAG = "GlassesLink"
        const val SESSION_TIMEOUT_MS = 15_000L
        const val STREAM_TIMEOUT_MS = 8_000L
        const val PHOTO_SHOTS = 3
        const val PHOTO_EDGE = 1280
        const val VIDEO_FRAMES = 4
        const val VIDEO_EDGE = 768 // smaller frames keep a 4-frame clip fast and cheap
    }
}
