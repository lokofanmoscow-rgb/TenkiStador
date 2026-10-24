package com.tankistador.game

import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** Small offline DSP toolkit used to build the sound effects and the music. */
object Synth {
    const val RATE = 22050
    private const val TAU = 2.0 * PI

    fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    fun buf(sec: Float) = FloatArray((sec * RATE).toInt())

    /** One-pole low-pass. */
    class LowPass(cutoff: Double) {
        private var a = coef(cutoff)
        private var z = 0.0
        fun set(cutoff: Double) { a = coef(cutoff) }
        fun run(x: Double): Double { z += a * (x - z); return z }
        private fun coef(fc: Double) = 1.0 - exp(-TAU * fc.coerceIn(5.0, RATE * 0.45) / RATE)
    }

    /** RBJ band-pass biquad (constant peak gain). */
    class BandPass(fc: Double, q: Double) {
        private var b0 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        init { set(fc, q) }
        fun set(fc: Double, q: Double) {
            val w = TAU * fc.coerceIn(20.0, RATE * 0.45) / RATE
            val alpha = sin(w) / (2 * q)
            val a0 = 1 + alpha
            b0 = alpha / a0; b2 = -alpha / a0
            a1 = -2 * cos(w) / a0; a2 = (1 - alpha) / a0
        }
        fun run(x: Double): Double {
            val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    /** A compact Schroeder/Freeverb style room. */
    class Reverb(room: Double = 0.8, private val damp: Double = 0.35) {
        private val combLen = intArrayOf(558, 594, 638, 678)
        private val combs = Array(4) { DoubleArray(combLen[it]) }
        private val combIdx = IntArray(4)
        private val combStore = DoubleArray(4)
        private val apLen = intArrayOf(278, 220)
        private val aps = Array(2) { DoubleArray(apLen[it]) }
        private val apIdx = IntArray(2)
        private val fb = room

        fun run(x: Double): Double {
            var out = 0.0
            for (i in 0 until 4) {
                val b = combs[i]
                val y = b[combIdx[i]]
                combStore[i] = y * (1 - damp) + combStore[i] * damp
                b[combIdx[i]] = x * 0.25 + combStore[i] * fb
                combIdx[i] = (combIdx[i] + 1) % b.size
                out += y
            }
            for (i in 0 until 2) {
                val b = aps[i]
                val bufOut = b[apIdx[i]]
                val y = -out + bufOut
                b[apIdx[i]] = out + bufOut * 0.5
                apIdx[i] = (apIdx[i] + 1) % b.size
                out = y
            }
            return out
        }
    }

    fun noise(r: Random) = r.nextDouble() * 2 - 1
    fun saw(phase: Double) = 2.0 * (phase - kotlin.math.floor(phase + 0.5))
    fun tri(phase: Double) = 1.0 - 4.0 * abs(phase - kotlin.math.floor(phase + 0.5))
    fun sq(phase: Double) = if ((phase - kotlin.math.floor(phase)) < 0.5) 1.0 else -1.0

    /** Attack/exponential-decay envelope. */
    fun env(t: Double, attack: Double, decay: Double) = if (t < attack) t / attack else exp(-(t - attack) * decay)

    /** Runs [wet] through a room and adds it to [dry]. */
    fun addReverb(dry: FloatArray, wet: FloatArray, mix: Double, room: Double = 0.8) {
        val rv = Reverb(room)
        for (i in dry.indices) dry[i] += (rv.run(wet[i].toDouble()) * mix).toFloat()
    }

    fun normalize(a: FloatArray, peak: Float = 0.9f): FloatArray {
        var m = 1e-6f
        for (v in a) m = max(m, abs(v))
        val k = peak / m
        for (i in a.indices) a[i] = (tanh(a[i] * k * 1.1) / tanh(1.1)).toFloat().coerceIn(-1f, 1f)
        return a
    }

    /** 16-bit mono PCM WAV. */
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
