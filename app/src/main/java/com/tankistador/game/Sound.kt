package com.tankistador.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Synthesised sound effects: generated once into WAV files and played via SoundPool. */
class Sound(ctx: Context) {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    var enabled = true

    init {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val sp = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(attrs).build()
            val dir = File(ctx.cacheDir, "sfx_v1")
            dir.mkdirs()
            for ((name, gen) in generators()) {
                val f = File(dir, "$name.wav")
                if (!f.exists() || f.length() < 100) f.writeBytes(wav(gen()))
                ids[name] = sp.load(f.absolutePath, 1)
            }
            pool = sp
        } catch (e: Throwable) {
            pool = null
        }
    }

    fun play(name: String, vol: Float = 1f, rate: Float = 1f) {
        if (!enabled) return
        val sp = pool ?: return
        val id = ids[name] ?: return
        try {
            sp.play(id, vol, vol, 1, 0, rate.coerceIn(0.5f, 2f))
        } catch (_: Throwable) {
        }
    }

    fun release() {
        pool?.release()
        pool = null
    }

    companion object {
        const val RATE = 22050

        private fun generators(): List<Pair<String, () -> FloatArray>> = listOf(
            "shot" to { shot() },
            "boom" to { boom(0.9f, 1f) },
            "bigboom" to { boom(1.8f, 0.6f) },
            "bounce" to { sweep(0.12f, 700f, 260f, 0.6f) },
            "click" to { tone(0.035f, 1300f, 0.35f, square = true) },
            "move" to { engine() },
            "tele" to { sweep(0.5f, 300f, 1600f, 0.5f) },
            "fanfare" to { notes(floatArrayOf(523f, 659f, 784f, 1047f), 0.14f, 0.45f) },
            "promo" to { notes(floatArrayOf(392f, 523f, 659f, 784f, 1047f, 1319f), 0.09f, 0.4f) },
            "lose" to { notes(floatArrayOf(392f, 330f, 262f, 196f), 0.18f, 0.4f) },
            "beep" to { tone(0.09f, 880f, 0.35f, square = true) },
            "hit" to { clang() },
            "cash" to { notes(floatArrayOf(1568f, 2093f), 0.06f, 0.3f) },
            "fire" to { crackle() },
            "repair" to { notes(floatArrayOf(660f, 880f, 990f), 0.07f, 0.35f) },
            "split" to { sweep(0.15f, 1200f, 500f, 0.4f) },
        )

        private fun buf(sec: Float) = FloatArray((sec * RATE).toInt())

        private fun shot(): FloatArray {
            val o = buf(0.35f)
            val r = Random(1)
            var lp = 0f
            for (i in o.indices) {
                val t = i / RATE.toFloat()
                lp += (r.nextFloat() * 2 - 1 - lp) * 0.25f
                val env = exp(-t * 14f)
                o[i] = (lp * 0.9f + sin(2 * PI * (90 - t * 120) * t).toFloat() * 0.8f) * env
            }
            return o
        }

        private fun boom(sec: Float, bright: Float): FloatArray {
            val o = buf(sec)
            val r = Random(2)
            var lp = 0f
            var lp2 = 0f
            for (i in o.indices) {
                val t = i / RATE.toFloat()
                val k = 0.02f + 0.18f * bright * exp(-t * 6f)
                lp += (r.nextFloat() * 2 - 1 - lp) * k
                lp2 += (lp - lp2) * 0.3f
                val env = (if (t < 0.01f) t / 0.01f else 1f) * exp(-t * 3.2f / sec)
                o[i] = (lp2 * 3.2f + sin(2 * PI * 48 * t).toFloat() * 0.5f * exp(-t * 4f)) * env
            }
            return o
        }

        private fun sweep(sec: Float, f0: Float, f1: Float, vol: Float): FloatArray {
            val o = buf(sec)
            var ph = 0.0
            for (i in o.indices) {
                val t = i / RATE.toFloat()
                val f = f0 + (f1 - f0) * (t / sec)
                ph += 2 * PI * f / RATE
                o[i] = sin(ph).toFloat() * vol * exp(-t * 3f / sec) * (if (t < 0.005f) t / 0.005f else 1f)
            }
            return o
        }

        private fun tone(sec: Float, f: Float, vol: Float, square: Boolean = false): FloatArray {
            val o = buf(sec)
            for (i in o.indices) {
                val t = i / RATE.toFloat()
                val s = sin(2 * PI * f * t).toFloat()
                o[i] = (if (square) (if (s > 0) 0.6f else -0.6f) else s) * vol * exp(-t * 2f / sec)
            }
            return o
        }

        private fun notes(fs: FloatArray, each: Float, vol: Float): FloatArray {
            val o = buf(each * fs.size + 0.25f)
            for ((n, f) in fs.withIndex()) {
                val start = (n * each * RATE).toInt()
                val len = ((each + 0.25f) * RATE).toInt()
                for (i in 0 until len) {
                    val idx = start + i
                    if (idx >= o.size) break
                    val t = i / RATE.toFloat()
                    val s = sin(2 * PI * f * t).toFloat()
                    val sq = if (s > 0) 0.35f else -0.35f
                    o[idx] += (s * 0.6f + sq * 0.4f) * vol * exp(-t * 7f)
                }
            }
            return o
        }

        private fun engine(): FloatArray {
            val o = buf(0.22f)
            for (i in o.indices) {
                val t = i / RATE.toFloat()
                val saw = ((t * 70f) % 1f) * 2 - 1
                val trem = 0.6f + 0.4f * sin(2 * PI * 18 * t).toFloat()
                val env = sin(PI * t / 0.22f).toFloat()
                o[i] = saw * 0.35f * trem * env
            }
            return o
        }

        private fun clang(): FloatArray {
            val o = buf(0.5f)
            val fs = floatArrayOf(410f, 1130f, 1720f, 2650f)
            for (i in o.indices) {
                val t = i / RATE.toFloat()
                var v = 0f
                for ((k, f) in fs.withIndex()) v += sin(2 * PI * f * t).toFloat() * exp(-t * (6f + k * 3f)) / (k + 1)
                o[i] = v * 0.5f
            }
            return o
        }

        private fun crackle(): FloatArray {
            val o = buf(0.6f)
            val r = Random(3)
            var lp = 0f
            for (i in o.indices) {
                val t = i / RATE.toFloat()
                val imp = if (r.nextFloat() < 0.01f) (r.nextFloat() * 2 - 1) else 0f
                lp += (r.nextFloat() * 2 - 1 - lp) * 0.05f
                o[i] = (imp * 0.9f + lp * 0.5f) * exp(-t * 3f)
            }
            return o
        }

        fun wav(samples: FloatArray): ByteArray {
            val data = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32000).toInt()
                data[i * 2] = (v and 0xFF).toByte()
                data[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
            }
            val out = ByteArrayOutputStream()
            fun i32(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
            fun i16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
            out.write("RIFF".toByteArray()); i32(36 + data.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
            out.write("data".toByteArray()); i32(data.size); out.write(data)
            return out.toByteArray()
        }
    }
}
