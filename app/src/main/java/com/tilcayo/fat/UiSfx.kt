package com.tilcayo.fat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Дополнительные звуки интерфейса и эффектов. Синтезируются в фоне при старте,
 * файлов не требуют. Каждый звук играет на коротком статичном AudioTrack.
 */
class UiSfx {
    enum class S { TICK, ON, OFF, OPEN, CLOSE, SPARKLE, POWER, LEVEL, WHOOSH }

    private val sr = 22050
    private val bank = HashMap<S, ShortArray>()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile var volume = 1f
    @Volatile private var ready = false
    private var active = 0

    init {
        val th = Thread {
            try {
                build()
            } catch (ex: Exception) {
            }
            ready = true
        }
        th.isDaemon = true
        th.name = "ui-sfx"
        th.start()
    }

    /** Тон с плавной частотой и затуханием. */
    private fun tone(f0: Double, f1: Double, dur: Double, vol: Double = 0.5, decay: Double = 5.0): ShortArray {
        val n = (dur * sr).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val x = i.toDouble() / n
            val f = f0 + (f1 - f0) * x
            phase += 2.0 * PI * f / sr
            val env = min(1.0, x * 40.0) * exp(-x * decay)
            val v = sin(phase) + 0.35 * sin(phase * 2.0)
            out[i] = (v * env * vol * 24000.0).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun concat(vararg parts: ShortArray): ShortArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ShortArray(total)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    private fun mix(a: ShortArray, b: ShortArray): ShortArray {
        val n = maxOf(a.size, b.size)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val v = (if (i < a.size) a[i].toInt() else 0) + (if (i < b.size) b[i].toInt() else 0)
            out[i] = v.coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun build() {
        bank[S.TICK] = tone(1900.0, 1500.0, 0.035, 0.4, 7.0)
        bank[S.ON] = concat(tone(620.0, 700.0, 0.06, 0.45, 3.0), tone(930.0, 1050.0, 0.09, 0.45, 5.0))
        bank[S.OFF] = concat(tone(820.0, 740.0, 0.06, 0.4, 3.0), tone(480.0, 400.0, 0.09, 0.4, 5.0))
        bank[S.OPEN] = tone(280.0, 980.0, 0.2, 0.4, 3.0)
        bank[S.CLOSE] = tone(900.0, 260.0, 0.16, 0.4, 3.5)
        bank[S.SPARKLE] = concat(
            tone(1200.0, 1200.0, 0.05, 0.35, 5.0),
            tone(1500.0, 1500.0, 0.05, 0.35, 5.0),
            tone(1800.0, 1800.0, 0.05, 0.35, 5.0),
            tone(2250.0, 2250.0, 0.05, 0.35, 5.0),
            tone(2700.0, 2700.0, 0.12, 0.35, 6.0),
        )
        bank[S.POWER] = mix(tone(180.0, 1300.0, 0.34, 0.45, 2.0), tone(360.0, 2000.0, 0.34, 0.2, 2.5))
        bank[S.LEVEL] = concat(
            tone(523.0, 523.0, 0.09, 0.45, 3.0),
            tone(659.0, 659.0, 0.09, 0.45, 3.0),
            tone(784.0, 784.0, 0.09, 0.45, 3.0),
            tone(1047.0, 1047.0, 0.3, 0.5, 4.0),
        )
        // свист: шум, прогнанный через скользящий фильтр
        val n = (0.26 * sr).toInt()
        val w = ShortArray(n)
        var lp = 0.0
        val rnd = java.util.Random(7)
        for (i in 0 until n) {
            val x = i.toDouble() / n
            val k = 0.04 + 0.5 * x
            lp += (rnd.nextDouble() * 2.0 - 1.0 - lp) * k
            val env = sin(PI * x)
            w[i] = (lp * env * 20000.0).toInt().coerceIn(-32767, 32767).toShort()
        }
        bank[S.WHOOSH] = w
    }

    fun play(s: S, rate: Float = 1f, vol: Float = 1f) {
        if (!ready || volume <= 0.01f) return
        val pcm = bank[s] ?: return
        if (active >= 6) return
        try {
            val rt = (sr * rate).toInt().coerceIn(8000, 48000)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rt)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            t.setVolume((vol * volume).coerceIn(0f, 1f))
            t.play()
            active++
            val ms = pcm.size * 1000L / rt + 90L
            handler.postDelayed(
                {
                    try {
                        t.release()
                    } catch (ex: Exception) {
                    }
                    active--
                },
                ms,
            )
        } catch (ex: Exception) {
        }
    }
}
