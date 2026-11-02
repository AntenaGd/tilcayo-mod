package com.tilcayo.fat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Хеллоуинская музыка. Играет в игре вместо прежней: ничего скачивать не нужно,
 * мелодия синтезируется при запуске (около секунды) и зацикливается.
 * Минор, музыкальная шкатулка, «терменвокс», колокол и сердцебиение.
 * Методы те же, что у прежнего Music: start(), setPlaying(), release().
 */
class HalloweenMusic {
    private companion object {
        const val SR = 22050
        const val BPM = 88.0
        const val BARS = 16
    }

    @Volatile private var want = false
    @Volatile private var released = false
    @Volatile private var started = false
    @Volatile private var vol = 0.55f
    private var track: AudioTrack? = null
    private val lock = Any()

    fun start() {
        if (started) return
        started = true
        val th = Thread {
            val pcm = render()
            synchronized(lock) {
                if (!released) {
                    val t = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build(),
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SR)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(pcm.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    t.write(pcm, 0, pcm.size)
                    t.setLoopPoints(0, pcm.size, -1)
                    t.setVolume(vol)
                    track = t
                    if (want) t.play()
                }
            }
        }
        th.isDaemon = true
        th.name = "halloween-music"
        th.start()
    }

    /** Громкость музыки 0..1. */
    fun setVolume(v: Float) {
        vol = v.coerceIn(0f, 1f)
        synchronized(lock) {
            try {
                track?.setVolume(vol)
            } catch (ex: Exception) {
            }
        }
    }

    fun setPlaying(on: Boolean) {
        want = on
        synchronized(lock) {
            val t = track ?: return
            try {
                if (on) t.play() else t.pause()
            } catch (ex: IllegalStateException) {
            }
        }
    }

    fun release() {
        released = true
        synchronized(lock) {
            try {
                track?.stop()
            } catch (ex: IllegalStateException) {
            }
            track?.release()
            track = null
        }
    }

    private fun render(): ShortArray {
        val beat = 60.0 / BPM
        val total = (BARS * 4 * beat * SR).toInt()
        val buf = FloatArray(total)
        val rnd = Random(31)
        val twoPi = 2.0 * PI

        fun mtof(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)

        // голос: звук f(t) на отрезке [start, start + dur] секунд; хвост переходит через границу цикла
        fun voice(start: Double, dur: Double, vol: Float, f: (Double) -> Double) {
            val i0 = (start * SR).toInt()
            val n = (dur * SR).toInt()
            for (j in 0 until n) {
                buf[(i0 + j) % total] += (f(j.toDouble() / SR) * vol).toFloat()
            }
        }

        fun bass(t0: Double, m: Int, vol: Float) {
            val fr = mtof(m)
            voice(t0, beat * 0.92, vol) { t ->
                val env = min(1.0, t / 0.02) * exp(-2.2 * t)
                (sin(twoPi * fr * t) + 0.35 * sin(twoPi * fr * 2.0 * t)) * env
            }
        }

        fun pluck(t0: Double, m: Int, vol: Float) {
            val fr = mtof(m)
            voice(t0, 0.9, vol) { t ->
                val env = exp(-5.0 * t)
                (sin(twoPi * fr * t) + 0.4 * sin(twoPi * fr * 2.0 * t) + 0.15 * sin(twoPi * fr * 3.0 * t)) * env
            }
        }

        fun lead(t0: Double, lenBeats: Double, m: Int, vol: Float) {
            val fr = mtof(m)
            val dur = lenBeats * beat
            voice(t0, dur + 0.15, vol) { t ->
                val att = min(1.0, t / 0.09)
                val rel = if (t > dur) max(0.0, 1.0 - (t - dur) / 0.15) else 1.0
                val vib = 0.012 * min(1.0, t / 0.4)
                val ph = twoPi * fr * t + (vib * fr / 5.5) * sin(twoPi * 5.5 * t)
                (sin(ph) + 0.2 * sin(2.0 * ph)) * att * rel
            }
        }

        fun bell(t0: Double, m: Int, vol: Float) {
            val fr = mtof(m)
            val parts = doubleArrayOf(1.0, 2.0, 2.76, 5.4, 8.93)
            val amps = doubleArrayOf(1.0, 0.6, 0.5, 0.25, 0.12)
            voice(t0, 5.0, vol) { t ->
                var s = 0.0
                for (q in parts.indices) s += amps[q] * sin(twoPi * fr * parts[q] * t) * exp(-(0.9 + 0.5 * parts[q]) * t)
                s
            }
        }

        fun kick(t0: Double, vol: Float) {
            voice(t0, 0.4, vol) { t ->
                val ph = twoPi * (48.0 * t + 70.0 * (1.0 - exp(-20.0 * t)) / 20.0)
                sin(ph) * exp(-9.0 * t)
            }
        }

        fun hat(t0: Double, vol: Float) {
            voice(t0, 0.09, vol) { t -> (rnd.nextDouble() * 2.0 - 1.0) * exp(-55.0 * t) }
        }

        fun pad(t0: Double, notes: IntArray, vol: Float) {
            val dur = 4 * beat + 0.5
            voice(t0, dur, vol) { t ->
                val env = sin(PI * min(1.0, t / dur)).pow(2)
                var s = 0.0
                for (m in notes) {
                    val fr = mtof(m + 12)
                    s += sin(twoPi * fr * t) + sin(twoPi * fr * 1.004 * t)
                }
                s * env
            }
        }

        // аккорды: Am, Am, F, E (с соль-диезом)
        val rootM = intArrayOf(45, 45, 41, 40)
        val chords = arrayOf(
            intArrayOf(57, 60, 64),
            intArrayOf(57, 60, 64),
            intArrayOf(53, 57, 60),
            intArrayOf(52, 56, 59),
        )
        val arp = intArrayOf(0, 1, 2, 1, 0, 1, 2, 1)

        for (bar in 0 until BARS) {
            val ci = bar % 4
            val b0 = bar * 4 * beat
            pad(b0, chords[ci], 0.028f)
            for (b in 0 until 4) bass(b0 + b * beat, rootM[ci], 0.2f)
            for (e in 0 until 8) pluck(b0 + e * beat / 2.0, chords[ci][arp[e]] + 24, 0.06f)
            if (bar >= 8) {
                kick(b0, 0.32f)
                kick(b0 + 2 * beat, 0.26f)
                for (e in 0 until 4) hat(b0 + (e + 0.5) * beat, 0.045f)
            } else {
                kick(b0, 0.16f)
            }
            if (bar % 4 == 0) bell(b0, 57, 0.09f)
        }

        // мелодия: (доля, длина в долях, нота MIDI), 8 тактов, играется дважды
        val melody = listOf(
            Triple(0.0, 2.0, 76), Triple(2.0, 1.0, 72), Triple(3.0, 1.0, 74),
            Triple(4.0, 1.5, 76), Triple(5.5, 0.5, 69), Triple(6.0, 2.0, 72),
            Triple(8.0, 2.0, 77), Triple(10.0, 1.0, 76), Triple(11.0, 1.0, 72),
            Triple(12.0, 1.0, 71), Triple(13.0, 1.0, 68), Triple(14.0, 2.0, 71),
            Triple(16.0, 2.0, 76), Triple(18.0, 1.0, 77), Triple(19.0, 1.0, 76),
            Triple(20.0, 1.5, 74), Triple(21.5, 0.5, 72), Triple(22.0, 2.0, 69),
            Triple(24.0, 2.0, 72), Triple(26.0, 1.0, 69), Triple(27.0, 1.0, 72),
            Triple(28.0, 1.0, 71), Triple(29.0, 1.0, 68), Triple(30.0, 2.0, 69),
        )
        for (rep in 0 until 2) {
            for ((bt, len, m) in melody) lead((rep * 32 + bt) * beat, len, m, 0.14f)
        }

        // жуткий «вой» в последнем такте: глиссандо вниз
        run {
            val t0 = 60 * beat
            val dur = 4 * beat
            val i0 = (t0 * SR).toInt()
            val n = (dur * SR).toInt()
            var ph = 0.0
            for (j in 0 until n) {
                val t = j.toDouble() / SR
                val fr = 1320.0 - 660.0 * (t / dur)
                ph += twoPi * fr / SR
                val env = sin(PI * (t / dur))
                buf[(i0 + j) % total] += (sin(ph + 0.8 * sin(twoPi * 6.0 * t)) * env * 0.045).toFloat()
            }
        }

        var peak = 0f
        for (v in buf) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        val g = if (peak > 0f) 0.9f / peak else 1f
        val pcm = ShortArray(total)
        for (i in 0 until total) {
            pcm[i] = (tanh((buf[i] * g * 1.1f).toDouble()) * 30000.0).toInt().toShort()
        }
        return pcm
    }
}
