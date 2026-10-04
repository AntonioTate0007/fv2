package com.thumbshade.app.overlay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import com.thumbshade.app.data.TickSound
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Little click sounds while scrolling the shade, like the detents of a dial. The sounds are
 * synthesised on the phone (a few milliseconds of audio each), so no sound files are shipped.
 */
object ScrollSounds {
    private const val RATE = 44_100
    private var pool: SoundPool? = null
    private val ids = HashMap<TickSound, Int>()
    private val loaded = HashSet<Int>()
    private var lastPlay = 0L
    private val rnd = Random(System.nanoTime())

    /** PCM samples (-1..1) for each sound. Pure maths; also used by tests. */
    fun synth(sound: TickSound): FloatArray {
        fun buf(ms: Int) = FloatArray(RATE * ms / 1000)
        fun t(i: Int) = i.toDouble() / RATE
        val noise = Random(7)
        return when (sound) {
            TickSound.NONE, TickSound.SYSTEM -> FloatArray(0)
            TickSound.TICK -> buf(18).also { b ->
                for (i in b.indices) b[i] = (sin(2 * PI * 3200 * t(i)) * exp(-t(i) * 420) * 0.8 + (noise.nextDouble() - 0.5) * exp(-t(i) * 2500) * 0.6).toFloat()
            }
            TickSound.SOFT -> buf(25).also { b ->
                for (i in b.indices) b[i] = (sin(2 * PI * 1100 * t(i)) * exp(-t(i) * 220) * 0.7).toFloat()
            }
            TickSound.WOOD -> buf(45).also { b ->
                for (i in b.indices) b[i] = ((sin(2 * PI * 820 * t(i)) * 0.6 + sin(2 * PI * 1730 * t(i)) * 0.35) * exp(-t(i) * 140)).toFloat()
            }
            TickSound.POP -> buf(35).also { b ->
                var phase = 0.0
                for (i in b.indices) {
                    val f = 300 + 700 * exp(-t(i) * 120)
                    phase += 2 * PI * f / RATE
                    b[i] = (sin(phase) * exp(-t(i) * 110) * 0.8).toFloat()
                }
            }
            TickSound.BUBBLE -> buf(45).also { b ->
                var phase = 0.0
                for (i in b.indices) {
                    val f = 420 + 900 * (t(i) / 0.045)
                    phase += 2 * PI * f / RATE
                    b[i] = (sin(phase) * sin(PI * t(i) / 0.045) * 0.7).toFloat()
                }
            }
            TickSound.KEYBOARD -> buf(30).also { b ->
                for (i in b.indices) {
                    val click = (noise.nextDouble() - 0.5) * exp(-t(i) * 1800) * 1.2
                    val body = sin(2 * PI * 2300 * t(i)) * exp(-t(i) * 300) * 0.35
                    val thock = sin(2 * PI * 180 * t(i)) * exp(-t(i) * 160) * 0.4
                    b[i] = (click + body + thock).toFloat()
                }
            }
            TickSound.TYPEWRITER -> buf(40).also { b ->
                var lp = 0.0
                for (i in b.indices) {
                    lp += ((noise.nextDouble() - 0.5) - lp) * 0.35
                    b[i] = (lp * exp(-t(i) * 220) * 2.4 + sin(2 * PI * 140 * t(i)) * exp(-t(i) * 120) * 0.45).toFloat()
                }
            }
            TickSound.GLASS -> buf(140).also { b ->
                for (i in b.indices) b[i] = ((sin(2 * PI * 2500 * t(i)) * 0.4 + sin(2 * PI * 3720 * t(i)) * 0.3 + sin(2 * PI * 5110 * t(i)) * 0.2) * exp(-t(i) * 38)).toFloat()
            }
            TickSound.RATCHET -> buf(22).also { b ->
                for (i in b.indices) {
                    val click = (noise.nextDouble() - 0.5) * exp(-t(i) * 900)
                    b[i] = (click * 0.9 + sin(2 * PI * 1450 * t(i)) * exp(-t(i) * 500) * 0.4).toFloat()
                }
            }
        }.also { b ->
            // Short fade-out so nothing clicks at the end.
            val tail = minOf(b.size, RATE / 500)
            for (k in 0 until tail) b[b.size - 1 - k] *= k / tail.toFloat()
        }
    }

    private fun wav(samples: FloatArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32000).toInt().toShort()) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(samples.size * 2)
        return h.array() + data.array()
    }

    private fun pool(): SoundPool = pool ?: SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
        .also { p ->
            p.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
            pool = p
        }

    /** Gets [sound] ready so the first tick isn't late. */
    fun prepare(context: Context, sound: TickSound) {
        if (sound == TickSound.NONE || sound == TickSound.SYSTEM || sound in ids) return
        runCatching {
            val dir = File(context.cacheDir, "ticks").apply { mkdirs() }
            val f = File(dir, sound.name.lowercase() + "_v1.wav")
            if (!f.exists()) FileOutputStream(f).use { it.write(wav(synth(sound))) }
            ids[sound] = pool().load(f.path, 1)
        }
    }

    /**
     * One click. [volume] 0..1. Skipped while the phone is on silent or vibrate when
     * [respectSilent], and never faster than about 28 a second.
     */
    fun play(context: Context, sound: TickSound, volume: Float, respectSilent: Boolean, view: android.view.View? = null, force: Boolean = false) {
        if (sound == TickSound.NONE) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPlay < 35 && !force) return
        lastPlay = now
        if (respectSilent && context.getSystemService(AudioManager::class.java)?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (sound == TickSound.SYSTEM) {
            view?.playSoundEffect(android.view.SoundEffectConstants.CLICK)
            return
        }
        prepare(context, sound)
        val id = ids[sound] ?: return
        if (synchronized(loaded) { id !in loaded }) return
        // A slightly different pitch each time sounds natural rather than mechanical.
        val pitch = 0.96f + rnd.nextFloat() * 0.08f
        pool?.play(id, volume, volume, 1, 0, pitch)
    }
}
