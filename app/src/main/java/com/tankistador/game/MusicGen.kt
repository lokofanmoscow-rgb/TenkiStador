package com.tankistador.game

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Composes the two looping background themes offline: a steady march for the menus and
 * the bazaar, and a tense, darker piece for battle. Each loop is rendered twice so the
 * reverb tail of the end flows into the beginning, then the second pass is kept.
 */
object MusicGen {
    private const val SR = Synth.RATE
    private const val TAU = 2.0 * PI

    fun render(name: String): FloatArray = if (name == "battle") battle() else menu()

    // ------------------------------------------------------------------ voices

    private class Mix(val sec: Double) {
        val len = (sec * SR).toInt()
        val dry = FloatArray(len)
        val wet = FloatArray(len)

        fun put(i: Int, v: Double, send: Double) {
            if (i < 0 || i >= len) return
            dry[i] += v.toFloat()
            wet[i] += (v * send).toFloat()
        }
    }

    /** Two detuned saws through a soft low-pass: strings/pad. */
    private fun pad(m: Mix, start: Double, dur: Double, midi: Int, vol: Double, attack: Double, cutoff: Double, send: Double) {
        val f = Synth.hz(midi)
        val lp1 = Synth.LowPass(cutoff); val lp2 = Synth.LowPass(cutoff)
        val rel = 0.8
        val n = ((dur + rel) * SR).toInt()
        val s0 = (start * SR).toInt()
        var p1 = 0.0; var p2 = 0.37
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val a = when {
                t < attack -> t / attack
                t < dur -> 1.0
                else -> exp(-(t - dur) * 5.0)
            }
            p1 += f * 1.003 / SR; p2 += f * 0.997 / SR
            val x = lp2.run(lp1.run(Synth.saw(p1) + Synth.saw(p2))) * 0.5
            m.put(s0 + k, x * a * vol, send)
        }
    }

    /** A brassy lead with gentle vibrato. */
    private fun horn(m: Mix, start: Double, dur: Double, midi: Int, vol: Double, send: Double) {
        val f = Synth.hz(midi)
        val lp = Synth.LowPass(1600.0)
        val n = ((dur + 0.5) * SR).toInt()
        val s0 = (start * SR).toInt()
        var p = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val a = when {
                t < 0.07 -> t / 0.07
                t < dur -> 1.0 - 0.15 * (t / dur)
                else -> 0.85 * exp(-(t - dur) * 9.0)
            }
            val vib = 1.0 + 0.004 * sin(TAU * 5.2 * t) * (t / (t + 0.3))
            p += f * vib / SR
            val x = lp.run(Synth.saw(p) * 0.7 + Synth.sq(p) * 0.3)
            m.put(s0 + k, x * a * vol, send)
        }
    }

    /** Plucked bass/ostinato note. */
    private fun pluck(m: Mix, start: Double, midi: Int, vol: Double, decay: Double, cutoff: Double, send: Double = 0.05, triangle: Boolean = true) {
        val f = Synth.hz(midi)
        val lp = Synth.LowPass(cutoff)
        val n = (5.0 / decay * SR).toInt().coerceAtMost(SR * 2)
        val s0 = (start * SR).toInt()
        var p = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            p += f / SR
            val raw = if (triangle) Synth.tri(p) else Synth.saw(p)
            m.put(s0 + k, lp.run(raw) * Synth.env(t, 0.004, decay) * vol, send)
        }
    }

    /** Bell with inharmonic partials. */
    private fun bell(m: Mix, start: Double, midi: Int, vol: Double, send: Double) {
        val f = Synth.hz(midi)
        val n = (3.0 * SR).toInt()
        val s0 = (start * SR).toInt()
        val parts = doubleArrayOf(1.0, 2.76, 5.4, 8.93)
        val amps = doubleArrayOf(1.0, 0.5, 0.25, 0.12)
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            var x = 0.0
            for (j in parts.indices) x += sin(TAU * f * parts[j] * t) * amps[j] * exp(-t * (1.2 + j * 1.6))
            m.put(s0 + k, x * vol * (if (t < 0.003) t / 0.003 else 1.0), send)
        }
    }

    private fun kick(m: Mix, start: Double, vol: Double) {
        val n = (0.45 * SR).toInt()
        val s0 = (start * SR).toInt()
        var p = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val f = 42.0 + 70.0 * exp(-t * 28.0)
            p += f / SR
            m.put(s0 + k, sin(TAU * p) * exp(-t * 7.0) * vol, 0.02)
        }
    }

    private fun snare(m: Mix, start: Double, vol: Double, r: Random) {
        val n = (0.3 * SR).toInt()
        val s0 = (start * SR).toInt()
        val bp = Synth.BandPass(2200.0, 0.8)
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val body = sin(TAU * 185.0 * t) * exp(-t * 30.0) * 0.5
            val rattle = bp.run(Synth.noise(r)) * exp(-t * 16.0) * 1.6
            m.put(s0 + k, (body + rattle) * vol, 0.25)
        }
    }

    private fun hat(m: Mix, start: Double, vol: Double, r: Random) {
        val n = (0.06 * SR).toInt()
        val s0 = (start * SR).toInt()
        val bp = Synth.BandPass(8000.0, 1.2)
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            m.put(s0 + k, bp.run(Synth.noise(r)) * exp(-t * 70.0) * vol, 0.05)
        }
    }

    private fun tom(m: Mix, start: Double, vol: Double, from: Double, to: Double) {
        val n = (1.0 * SR).toInt()
        val s0 = (start * SR).toInt()
        var p = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val f = to + (from - to) * exp(-t * 9.0)
            p += f / SR
            m.put(s0 + k, sin(TAU * p) * exp(-t * 4.0) * vol, 0.35)
        }
    }

    private fun finish(m: Mix, loopSec: Double, reverb: Double, room: Double): FloatArray {
        Synth.addReverb(m.dry, m.wet, reverb, room)
        val from = (loopSec * SR).toInt()
        val out = m.dry.copyOfRange(from, from + (loopSec * SR).toInt())
        return Synth.normalize(out, 0.8f)
    }

    // ------------------------------------------------------------------ themes

    /** "Orbit March": A minor, 88 bpm, eight bars. */
    private fun menu(): FloatArray {
        val beat = 60.0 / 88.0
        val bar = beat * 4
        val loop = bar * 8
        val m = Mix(loop * 2)
        val r = Random(11)
        val bass = intArrayOf(45, 41, 48, 43, 45, 41, 50, 40)
        val chords = arrayOf(
            intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(55, 60, 64), intArrayOf(55, 59, 62),
            intArrayOf(57, 60, 64), intArrayOf(57, 60, 65), intArrayOf(53, 57, 62), intArrayOf(56, 59, 64),
        )
        // Lead: (bar, beat offset, length in beats, note)
        val lead = listOf(
            doubleArrayOf(0.0, 0.0, 2.0, 64.0), doubleArrayOf(0.0, 2.0, 2.0, 69.0),
            doubleArrayOf(1.0, 0.0, 3.0, 72.0), doubleArrayOf(1.0, 3.0, 1.0, 69.0),
            doubleArrayOf(2.0, 0.0, 2.0, 67.0), doubleArrayOf(2.0, 2.0, 2.0, 64.0),
            doubleArrayOf(3.0, 0.0, 4.0, 62.0),
            doubleArrayOf(4.0, 0.0, 2.0, 64.0), doubleArrayOf(4.0, 2.0, 2.0, 69.0),
            doubleArrayOf(5.0, 0.0, 2.0, 72.0), doubleArrayOf(5.0, 2.0, 2.0, 69.0),
            doubleArrayOf(6.0, 0.0, 2.0, 74.0), doubleArrayOf(6.0, 2.0, 2.0, 69.0),
            doubleArrayOf(7.0, 0.0, 2.0, 68.0), doubleArrayOf(7.0, 2.0, 2.0, 71.0),
        )
        for (pass in 0 until 2) {
            val o = pass * loop
            for (b in 0 until 8) {
                val t0 = o + b * bar
                for (n in chords[b]) pad(m, t0, bar, n, 0.05, 0.35, 900.0, 0.5)
                // Syncopated march bass on eighths 0, 3, 4, 6.
                for (e in intArrayOf(0, 3, 4, 6)) pluck(m, t0 + e * beat / 2, bass[b], 0.3, 7.0, 500.0)
                kick(m, t0, 0.55); kick(m, t0 + 2 * beat, 0.5)
                snare(m, t0 + beat, 0.16, r); snare(m, t0 + 3 * beat, 0.18, r)
                for (e in 0 until 8) hat(m, t0 + e * beat / 2, if (e % 2 == 0) 0.05 else 0.03, r)
                if (b == 3 || b == 7) for (s in 0 until 4) snare(m, t0 + 3 * beat + s * beat / 4, 0.08 + s * 0.03, r)
            }
            for (l in lead) {
                horn(m, o + l[0] * bar + l[1] * beat, l[2] * beat * 0.95, l[3].toInt(), 0.11, 0.45)
            }
        }
        return finish(m, loop, 0.35, 0.82)
    }

    /** "Cold Orbit": D minor, 72 bpm, eight bars of tension for the battlefield. */
    private fun battle(): FloatArray {
        val beat = 60.0 / 72.0
        val bar = beat * 4
        val loop = bar * 8
        val m = Mix(loop * 2)
        val r = Random(23)
        val bass = intArrayOf(38, 38, 34, 36, 38, 38, 43, 45)
        val chords = arrayOf(
            intArrayOf(50, 53, 57), intArrayOf(50, 53, 57), intArrayOf(46, 50, 53), intArrayOf(48, 52, 55),
            intArrayOf(50, 53, 57), intArrayOf(50, 53, 57), intArrayOf(55, 58, 62), intArrayOf(57, 61, 64),
        )
        for (pass in 0 until 2) {
            val o = pass * loop
            // A low drone under the whole loop, swelling over two bars at a time.
            for (b in 0 until 8 step 2) {
                pad(m, o + b * bar, bar * 2, 26, 0.07, 1.2, 260.0, 0.3)
                pad(m, o + b * bar, bar * 2, 33, 0.05, 1.2, 320.0, 0.3)
            }
            for (b in 0 until 8) {
                val t0 = o + b * bar
                for (n in chords[b]) pad(m, t0, bar, n, 0.035, 1.0, 700.0, 0.7)
                // Restless eighth-note ostinato.
                for (e in 0 until 8) {
                    val note = if (e % 4 == 3) bass[b] + 12 else bass[b]
                    pluck(m, t0 + e * beat / 2, note, if (e % 2 == 0) 0.16 else 0.11, 12.0, 650.0, 0.1, triangle = false)
                }
                tom(m, t0, 0.45, 110.0, 55.0)
                tom(m, t0 + 2.5 * beat, 0.25, 120.0, 62.0)
                for (e in intArrayOf(1, 3)) hat(m, t0 + e * beat + beat / 2, 0.035, r)
                if (b % 2 == 0) {
                    bell(m, t0, 69, 0.05, 0.8)
                    bell(m, t0 + 3 * beat, 65, 0.04, 0.8)
                    bell(m, t0 + 6 * beat, 74, 0.045, 0.8)
                }
            }
        }
        return finish(m, loop, 0.5, 0.86)
    }
}
