package com.tankistador.game

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Composes the looping background music offline. Three original pieces:
 *  - "menu":   a slow, wide synth piece in E minor with a sequenced arpeggio;
 *  - "shop":   a relaxed electric-piano groove for the tank bazaar;
 *  - "battle": a driving action track in F sharp minor.
 * Each piece is rendered once with a few extra seconds; that tail is folded back onto the
 * start, so reverb and echoes carry over the loop point without a seam.
 */
object MusicGen {
    private const val SR = Synth.RATE
    private const val TAU = 2.0 * PI
    private const val TAIL = 4.0

    val tracks = listOf("menu", "shop", "battle")

    fun render(name: String): FloatArray = when (name) {
        "battle" -> battle()
        "shop" -> shop()
        else -> menu()
    }

    // ------------------------------------------------------------------ mixing

    private class Mix(val loop: Double) {
        val loopLen = (loop * SR).toInt()
        val len = ((loop + TAIL) * SR).toInt()
        val dry = FloatArray(len)
        val duck = FloatArray(len)
        val wet = FloatArray(len)
        val echo = FloatArray(len)
        val kicks = ArrayList<Double>()

        fun put(i: Int, v: Double, send: Double, ducked: Boolean = false, echoSend: Double = 0.0) {
            if (i < 0 || i >= len) return
            if (ducked) duck[i] += v.toFloat() else dry[i] += v.toFloat()
            wet[i] += (v * send).toFloat()
            if (echoSend > 0) echo[i] += (v * echoSend).toFloat()
        }
    }

    private fun finish(m: Mix, reverb: Double, room: Double, echoTime: Double, echoFb: Double, duckDepth: Double): FloatArray {
        // Side-chain: pads and bass dip under every kick, then breathe back in.
        val env = FloatArray(m.len) { 1f }
        for (k in m.kicks) for (shift in doubleArrayOf(0.0, m.loop)) {
            val s0 = ((k + shift) * SR).toInt()
            for (j in 0 until (0.35 * SR).toInt()) {
                val i = s0 + j
                if (i >= m.len) break
                val t = j.toDouble() / SR
                val g = 1.0 - duckDepth * exp(-t * 9.0)
                env[i] = min(env[i].toDouble(), g).toFloat()
            }
        }
        val out = FloatArray(m.len)
        for (i in 0 until m.len) out[i] = m.dry[i] + m.duck[i] * env[i]
        // Tempo-synced echo.
        if (echoTime > 0) {
            val d = (echoTime * SR).toInt()
            val line = FloatArray(m.len)
            for (i in 0 until m.len) {
                val back = if (i >= d) line[i - d] else 0f
                line[i] = m.echo[i] + back * echoFb.toFloat()
                out[i] += back * 0.6f
                m.wet[i] += back * 0.3f
            }
        }
        val rv = Synth.Reverb(room)
        for (i in 0 until m.len) out[i] += (rv.run(m.wet[i].toDouble()) * reverb).toFloat()
        // Fold the tail onto the start.
        val loopOut = out.copyOf(m.loopLen)
        for (i in m.loopLen until m.len) loopOut[i - m.loopLen] += out[i]
        return Synth.normalize(loopOut, 0.82f)
    }

    // ------------------------------------------------------------------ instruments

    /** Warm pad: three detuned saws through a gentle low-pass. */
    private fun pad(m: Mix, start: Double, dur: Double, midi: Int, vol: Double, attack: Double, cutoff: Double, send: Double, ducked: Boolean = true) {
        val f = Synth.hz(midi)
        val lp1 = Synth.LowPass(cutoff); val lp2 = Synth.LowPass(cutoff * 1.3)
        val rel = 1.0
        val n = ((dur + rel) * SR).toInt()
        val s0 = (start * SR).toInt()
        var p1 = 0.0; var p2 = 0.31; var p3 = 0.67
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val a = when {
                t < attack -> t / attack
                t < dur -> 1.0
                else -> exp(-(t - dur) * 4.0)
            }
            p1 += f * 1.004 / SR; p2 += f * 0.996 / SR; p3 += f * 0.5 / SR
            val x = lp2.run(lp1.run(Synth.saw(p1) + Synth.saw(p2) + 0.5 * Synth.tri(p3))) * 0.4
            m.put(s0 + k, x * a * vol, send, ducked)
        }
    }

    /** Pulse-wave pluck with a closing filter: the sequenced arpeggio. */
    private fun arp(m: Mix, start: Double, midi: Int, vol: Double, bright: Double) {
        val f = Synth.hz(midi)
        val lp = Synth.LowPass(2000.0)
        val n = (0.32 * SR).toInt()
        val s0 = (start * SR).toInt()
        var p = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            p += f / SR
            lp.set(400.0 + 2600.0 * bright * exp(-t * 18.0))
            val ph = p - kotlin.math.floor(p)
            val pulse = if (ph < 0.32) 1.0 else -1.0
            m.put(s0 + k, lp.run(pulse) * Synth.env(t, 0.003, 11.0) * vol, 0.35, echoSend = 0.5)
        }
    }

    /** Smooth lead: sine plus a little triangle, slow vibrato, soft attack. */
    private fun lead(m: Mix, start: Double, dur: Double, midi: Int, vol: Double) {
        val f = Synth.hz(midi)
        val n = ((dur + 0.6) * SR).toInt()
        val s0 = (start * SR).toInt()
        var p = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val a = when {
                t < 0.05 -> t / 0.05
                t < dur -> 1.0 - 0.25 * (t / dur)
                else -> 0.75 * exp(-(t - dur) * 7.0)
            }
            val vib = 1.0 + 0.005 * sin(TAU * 5.5 * t) * min(1.0, t / 0.4)
            p += f * vib / SR
            val x = sin(TAU * p) * 0.75 + Synth.tri(p) * 0.25
            m.put(s0 + k, x * a * vol, 0.45, echoSend = 0.25)
        }
    }

    /** Analog-style bass: saw and sub sine with a short filter blip. */
    private fun bass(m: Mix, start: Double, dur: Double, midi: Int, vol: Double, cutoff: Double) {
        val f = Synth.hz(midi)
        val lp = Synth.LowPass(cutoff)
        val n = ((dur + 0.05) * SR).toInt()
        val s0 = (start * SR).toInt()
        var p = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            p += f / SR
            lp.set(cutoff * (0.6 + 1.4 * exp(-t * 14.0)))
            val a = if (t < 0.004) t / 0.004 else if (t < dur) 1.0 else exp(-(t - dur) * 60.0)
            val x = lp.run(Synth.saw(p)) * 0.7 + sin(TAU * p * 0.5) * 0.5
            m.put(s0 + k, x * a * vol, 0.03, ducked = true)
        }
    }

    /** Electric piano: two sine partials with a bell-like attack. */
    private fun epiano(m: Mix, start: Double, midi: Int, vol: Double, decay: Double = 2.2) {
        val f = Synth.hz(midi)
        val n = (min(3.0, 6.0 / decay) * SR).toInt()
        val s0 = (start * SR).toInt()
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val mod = sin(TAU * f * 2.0 * t) * 1.4 * exp(-t * 9.0)
            val x = sin(TAU * f * t + mod) * exp(-t * decay) + 0.25 * sin(TAU * f * 3.0 * t) * exp(-t * decay * 3)
            m.put(s0 + k, x * vol * (if (t < 0.002) t / 0.002 else 1.0), 0.3, ducked = true)
        }
    }

    /** Wooden mallet note. */
    private fun marimba(m: Mix, start: Double, midi: Int, vol: Double) {
        val f = Synth.hz(midi)
        val n = (0.9 * SR).toInt()
        val s0 = (start * SR).toInt()
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val x = sin(TAU * f * t) * exp(-t * 6.5) + 0.35 * sin(TAU * f * 3.98 * t) * exp(-t * 22.0)
            m.put(s0 + k, x * vol, 0.3, echoSend = 0.2)
        }
    }

    /** Short bright brass chord for accents. */
    private fun stab(m: Mix, start: Double, notes: IntArray, vol: Double) {
        val n = (0.5 * SR).toInt()
        val s0 = (start * SR).toInt()
        val lps = Array(notes.size) { Synth.LowPass(800.0) }
        val ph = DoubleArray(notes.size * 2)
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val a = Synth.env(t, 0.01, 7.0)
            var x = 0.0
            for ((j, note) in notes.withIndex()) {
                val f = Synth.hz(note)
                ph[j * 2] += f * 1.003 / SR; ph[j * 2 + 1] += f * 0.997 / SR
                lps[j].set(500.0 + 3500.0 * exp(-t * 10.0))
                x += lps[j].run(Synth.saw(ph[j * 2]) + Synth.saw(ph[j * 2 + 1]))
            }
            m.put(s0 + k, x * a * vol / notes.size, 0.35)
        }
    }

    private fun kick(m: Mix, start: Double, vol: Double) {
        m.kicks.add(start)
        val n = (0.5 * SR).toInt()
        val s0 = (start * SR).toInt()
        var p = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            p += (46.0 + 110.0 * exp(-t * 32.0)) / SR
            val click = 0.4 * exp(-t * 900.0) * min(1.0, t / 0.0004)
            m.put(s0 + k, (sin(TAU * p) * exp(-t * 6.5) + click) * vol, 0.02)
        }
    }

    private fun clap(m: Mix, start: Double, vol: Double, r: Random) {
        val n = (0.35 * SR).toInt()
        val s0 = (start * SR).toInt()
        val bp = Synth.BandPass(1300.0, 1.2)
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            // Three quick slaps then a short tail.
            val e = exp(-(t % 0.011) * 300.0) * (if (t < 0.033) 1.0 else 0.0) + exp(-t * 18.0) * 0.6
            m.put(s0 + k, bp.run(Synth.noise(r)) * e * vol, 0.4)
        }
    }

    private fun snare(m: Mix, start: Double, vol: Double, r: Random) {
        val n = (0.32 * SR).toInt()
        val s0 = (start * SR).toInt()
        val bp = Synth.BandPass(2600.0, 0.7)
        var p = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            p += (190.0 + 60 * exp(-t * 40)) / SR
            val x = sin(TAU * p) * exp(-t * 28.0) * 0.6 + bp.run(Synth.noise(r)) * exp(-t * 14.0) * 1.5
            m.put(s0 + k, x * vol, 0.3)
        }
    }

    private fun hat(m: Mix, start: Double, vol: Double, r: Random, open: Boolean = false) {
        val n = ((if (open) 0.35 else 0.06) * SR).toInt()
        val s0 = (start * SR).toInt()
        val bp = Synth.BandPass(9000.0, 1.0)
        val d = if (open) 9.0 else 75.0
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            m.put(s0 + k, bp.run(Synth.noise(r)) * exp(-t * d) * vol, 0.08)
        }
    }

    private fun shaker(m: Mix, start: Double, vol: Double, r: Random) {
        val n = (0.08 * SR).toInt()
        val s0 = (start * SR).toInt()
        val bp = Synth.BandPass(6500.0, 2.0)
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val e = if (t < 0.015) t / 0.015 else exp(-(t - 0.015) * 45.0)
            m.put(s0 + k, bp.run(Synth.noise(r)) * e * vol, 0.1)
        }
    }

    private fun rim(m: Mix, start: Double, vol: Double) {
        val n = (0.08 * SR).toInt()
        val s0 = (start * SR).toInt()
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            m.put(s0 + k, (sin(TAU * 1700 * t) + 0.6 * sin(TAU * 820 * t)) * exp(-t * 70.0) * vol, 0.25)
        }
    }

    private fun crash(m: Mix, start: Double, vol: Double, r: Random) {
        val n = (2.2 * SR).toInt()
        val s0 = (start * SR).toInt()
        val bp = Synth.BandPass(7000.0, 0.6)
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            m.put(s0 + k, bp.run(Synth.noise(r)) * exp(-t * 2.2) * vol, 0.3)
        }
    }

    private fun tom(m: Mix, start: Double, vol: Double, freq: Double) {
        val n = (0.6 * SR).toInt()
        val s0 = (start * SR).toInt()
        var p = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            p += (freq + freq * 0.7 * exp(-t * 18.0)) / SR
            m.put(s0 + k, sin(TAU * p) * exp(-t * 7.0) * vol, 0.3)
        }
    }

    /** Rising filtered noise leading into a new section. */
    private fun riser(m: Mix, start: Double, dur: Double, vol: Double, r: Random) {
        val n = (dur * SR).toInt()
        val s0 = (start * SR).toInt()
        val bp = Synth.BandPass(400.0, 1.5)
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            bp.set(400.0 + 5000.0 * (t / dur) * (t / dur), 1.5)
            m.put(s0 + k, bp.run(Synth.noise(r)) * (t / dur) * vol, 0.5)
        }
    }

    // ------------------------------------------------------------------ pieces

    /** "Starlight Drift": E minor, 96 bpm, two eight-bar sections. */
    private fun menu(): FloatArray {
        val beat = 60.0 / 96.0
        val bar = beat * 4
        val m = Mix(bar * 16)
        val r = Random(41)
        val roots = intArrayOf(40, 36, 43, 38, 40, 36, 45, 47, 36, 38, 40, 40, 36, 38, 47, 47)
        val chords = arrayOf(
            intArrayOf(52, 55, 59, 64), intArrayOf(48, 52, 55, 59), intArrayOf(50, 55, 59, 62), intArrayOf(50, 54, 57, 62),
            intArrayOf(52, 55, 59, 64), intArrayOf(48, 52, 55, 59), intArrayOf(52, 57, 60, 64), intArrayOf(51, 54, 59, 63),
            intArrayOf(48, 52, 55, 60), intArrayOf(50, 54, 57, 62), intArrayOf(52, 55, 59, 64), intArrayOf(52, 55, 59, 62),
            intArrayOf(48, 52, 55, 60), intArrayOf(50, 54, 57, 62), intArrayOf(51, 54, 59, 63), intArrayOf(51, 54, 59, 63),
        )
        for (b in 0 until 16) {
            val t0 = b * bar
            val second = b >= 8
            for (n in chords[b]) pad(m, t0, bar, n, 0.05, 0.8, 1100.0, 0.6)
            // Sequenced sixteenth-note arpeggio over two octaves; the filter opens in part two.
            val tones = chords[b]
            for (s in 0 until 16) {
                val idx = intArrayOf(0, 1, 2, 3, 2, 1, 2, 3)[s % 8]
                val note = tones[idx] + 12 + (if (s >= 8 && s % 4 == 3) 12 else 0)
                arp(m, t0 + s * beat / 4, note, 0.045, if (second) 0.9 else 0.45 + 0.04 * b)
            }
            for (e in 0 until 8) {
                val note = roots[b] + if (e % 2 == 1) 12 else 0
                bass(m, t0 + e * beat / 2, beat / 2 * 0.8, note, 0.2, 320.0)
            }
            if (second) for (q in 0 until 4) kick(m, t0 + q * beat, 0.6) else { kick(m, t0, 0.6); kick(m, t0 + 2 * beat, 0.55) }
            clap(m, t0 + beat, 0.16, r); clap(m, t0 + 3 * beat, 0.18, r)
            for (e in 0 until 4) hat(m, t0 + e * beat + beat / 2, 0.06, r)
            if (b % 4 == 3) hat(m, t0 + 3.5 * beat, 0.07, r, open = true)
            if (b == 7) riser(m, t0, bar, 0.12, r)
            if (b == 0 || b == 8) crash(m, t0, 0.12, r)
        }
        // Melody over the second section: (bar, beat, length, note)
        val mel = listOf(
            doubleArrayOf(8.0, 0.0, 2.0, 76.0), doubleArrayOf(8.0, 2.0, 1.0, 79.0), doubleArrayOf(8.0, 3.0, 1.0, 76.0),
            doubleArrayOf(9.0, 0.0, 3.0, 78.0), doubleArrayOf(9.0, 3.0, 1.0, 74.0),
            doubleArrayOf(10.0, 0.0, 2.0, 79.0), doubleArrayOf(10.0, 2.0, 2.0, 83.0),
            doubleArrayOf(11.0, 0.0, 1.0, 81.0), doubleArrayOf(11.0, 1.0, 1.0, 79.0), doubleArrayOf(11.0, 2.0, 2.0, 76.0),
            doubleArrayOf(12.0, 0.0, 2.0, 76.0), doubleArrayOf(12.0, 2.0, 1.0, 79.0), doubleArrayOf(12.0, 3.0, 1.0, 84.0),
            doubleArrayOf(13.0, 0.0, 2.0, 83.0), doubleArrayOf(13.0, 2.0, 1.0, 81.0), doubleArrayOf(13.0, 3.0, 1.0, 78.0),
            doubleArrayOf(14.0, 0.0, 2.0, 75.0), doubleArrayOf(14.0, 2.0, 2.0, 78.0),
            doubleArrayOf(15.0, 0.0, 4.0, 71.0),
        )
        for (n in mel) lead(m, n[0] * bar + n[1] * beat, n[2] * beat * 0.92, n[3].toInt(), 0.09)
        return finish(m, 0.32, 0.86, beat * 0.75, 0.38, 0.45)
    }

    /** "Bazaar Workshop": a laid-back groove in D minor, 100 bpm. */
    private fun shop(): FloatArray {
        val beat = 60.0 / 100.0
        val bar = beat * 4
        val m = Mix(bar * 16)
        val r = Random(53)
        val roots = intArrayOf(38, 43, 38, 43, 46, 48, 38, 45)
        val chords = arrayOf(
            intArrayOf(53, 57, 60, 64), intArrayOf(53, 55, 59, 62), intArrayOf(53, 57, 60, 64), intArrayOf(53, 55, 59, 64),
            intArrayOf(53, 57, 58, 62), intArrayOf(52, 55, 58, 64), intArrayOf(53, 57, 60, 65), intArrayOf(52, 55, 57, 61),
        )
        // Walking bass lines, one per chord: four quarter notes.
        val walk = arrayOf(
            intArrayOf(38, 41, 45, 43), intArrayOf(43, 47, 50, 48), intArrayOf(38, 45, 41, 40), intArrayOf(43, 41, 40, 42),
            intArrayOf(46, 50, 53, 50), intArrayOf(48, 52, 47, 45), intArrayOf(38, 41, 43, 44), intArrayOf(45, 49, 52, 49),
        )
        for (b in 0 until 16) {
            val c = b % 8
            val t0 = b * bar
            // Syncopated piano comping: on 1, the "and" of 2, and 4.
            for (hit in doubleArrayOf(0.0, 1.5, 3.0)) for (n in chords[c]) epiano(m, t0 + hit * beat, n, 0.05, 3.0)
            for (q in 0 until 4) bass(m, t0 + q * beat, beat * 0.85, walk[c][q], 0.2, 420.0)
            kick(m, t0, 0.45); kick(m, t0 + 2.5 * beat, 0.35)
            rim(m, t0 + beat, 0.12); rim(m, t0 + 3 * beat, 0.12)
            for (s in 0 until 8) shaker(m, t0 + s * beat / 2 + 0.03 * (s % 2), if (s % 2 == 1) 0.07 else 0.04, r)
            if (b >= 8) {
                // A marimba line answers the piano in the second half.
                val tones = chords[c]
                val pattern = intArrayOf(3, 2, 1, 2)
                for ((k, i) in pattern.withIndex()) if ((b + k) % 3 != 0) marimba(m, t0 + (0.5 + k) * beat, tones[i] + 12, 0.07)
            }
        }
        return finish(m, 0.28, 0.8, beat * 0.5, 0.25, 0.25)
    }

    /** "Iron Orbit": F sharp minor, 126 bpm, verse and chorus of eight bars each. */
    private fun battle(): FloatArray {
        val beat = 60.0 / 126.0
        val bar = beat * 4
        val m = Mix(bar * 16)
        val r = Random(67)
        val roots = intArrayOf(42, 38, 45, 40, 42, 38, 40, 40, 47, 38, 42, 37, 47, 38, 40, 37)
        val chords = arrayOf(
            intArrayOf(54, 57, 61), intArrayOf(50, 54, 57), intArrayOf(52, 57, 61), intArrayOf(52, 56, 59),
            intArrayOf(54, 57, 61), intArrayOf(50, 54, 57), intArrayOf(52, 56, 59), intArrayOf(52, 56, 59),
            intArrayOf(54, 59, 62), intArrayOf(54, 57, 62), intArrayOf(54, 57, 61), intArrayOf(53, 56, 61),
            intArrayOf(54, 59, 62), intArrayOf(54, 57, 62), intArrayOf(52, 56, 59), intArrayOf(53, 56, 61),
        )
        for (b in 0 until 16) {
            val t0 = b * bar
            val chorus = b >= 8
            for (n in chords[b]) pad(m, t0, bar, n - 12, 0.035, 0.3, 700.0, 0.4)
            // Galloping sixteenth bass: root, root, octave, root.
            for (s in 0 until 16) {
                val note = roots[b] + if (s % 4 == 2) 12 else 0
                bass(m, t0 + s * beat / 4, beat / 4 * 0.7, note, if (s % 4 == 0) 0.22 else 0.15, 450.0)
            }
            for (q in 0 until 4) kick(m, t0 + q * beat, 0.65)
            snare(m, t0 + beat, 0.22, r); snare(m, t0 + 3 * beat, 0.24, r)
            for (s in 0 until 8) hat(m, t0 + s * beat / 2, if (s % 2 == 1) 0.07 else 0.04, r)
            stab(m, t0, chords[b].map { it + 12 }.toIntArray(), if (chorus) 0.11 else 0.08)
            stab(m, t0 + 1.5 * beat, chords[b].map { it + 12 }.toIntArray(), if (chorus) 0.09 else 0.06)
            if (chorus) {
                // High string ostinato on eighths.
                for (e in 0 until 8) lead(m, t0 + e * beat / 2, beat / 2 * 0.8, chords[b][e % 3] + 24, 0.035)
            }
            if (b % 4 == 3) {
                // Tom fill into the next phrase.
                val fill = doubleArrayOf(3.0, 3.25, 3.5, 3.75)
                val pitch = doubleArrayOf(140.0, 120.0, 100.0, 82.0)
                for (i in fill.indices) tom(m, t0 + fill[i] * beat, 0.35, pitch[i])
            }
            if (b == 0 || b == 8) crash(m, t0, 0.16, r)
            if (b == 7 || b == 15) riser(m, t0 + 2 * beat, 2 * beat, 0.12, r)
        }
        return finish(m, 0.22, 0.8, beat * 0.75, 0.3, 0.5)
    }
}
