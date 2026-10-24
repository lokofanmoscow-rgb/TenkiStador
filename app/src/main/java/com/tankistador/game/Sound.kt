package com.tankistador.game

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
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
            val sp = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
            for (old in 1..5) File(ctx.cacheDir, "sfx_v$old").deleteRecursively()
            val dir = File(ctx.cacheDir, "sfx_v6")
            dir.mkdirs()
            for ((name, _) in Sfx.all) {
                val f = File(dir, "$name.wav")
                if (!f.exists() || f.length() < 100) f.writeBytes(Synth.wav(Sfx.render(name)))
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
}

/**
 * The effect recipes. Every sound is layered the way a sound designer would build it:
 * a sharp transient, a pitched body and a noisy tail, then placed in a small room.
 */
object Sfx {
    private const val SR = Synth.RATE
    private const val TAU = 2.0 * PI

    val all: List<Pair<String, () -> FloatArray>> = listOf(
        "click" to ::click, "beep" to ::beep, "shot" to ::shot, "boom" to { boom(1.8f, 1.0) },
        "bigboom" to { boom(3.2f, 0.6) }, "bounce" to ::bounce, "move" to ::engine, "tele" to ::tele,
        "fanfare" to ::fanfare, "victory" to ::victory, "promo" to ::promo, "lose" to ::lose, "cash" to ::cash, "repair" to ::repair,
        "hit" to ::hit, "fire" to ::fire, "split" to ::split, "rocket" to ::rocket, "quake" to ::quake,
        "meteor" to ::meteor, "grav" to ::grav,
    )

    /** Interface clicks and pings sit under the music; everything else plays at the music's level. */
    private val quiet = setOf("click", "beep", "split")

    /** Renders a sound at its mix level so effects and music come out equally loud. */
    fun render(name: String): FloatArray {
        val gen = all.first { it.first == name }.second
        val target = if (name in quiet) Mixer.UI_LOUDNESS else Mixer.EFFECT_LOUDNESS
        return Synth.toLoudness(gen(), target)
    }

    private inline fun fill(sec: Float, f: (t: Double) -> Double): FloatArray {
        val o = Synth.buf(sec)
        for (i in o.indices) o[i] = f(i.toDouble() / SR).toFloat()
        return o
    }

    private fun room(dry: FloatArray, mix: Double, roomSize: Double = 0.75): FloatArray {
        Synth.addReverb(dry, dry.copyOf(), mix, roomSize)
        return dry
    }

    private fun add(o: FloatArray, start: Double, sec: Double, f: (t: Double) -> Double) {
        val s0 = (start * SR).toInt()
        val n = (sec * SR).toInt()
        for (k in 0 until n) {
            val i = s0 + k
            if (i < 0 || i >= o.size) continue
            o[i] += f(k.toDouble() / SR).toFloat()
        }
    }

    /** Short noise grains scattered in time: falling debris, crackles, rattles. */
    private fun grains(o: FloatArray, from: Double, to: Double, count: Int, fLow: Double, fHigh: Double, amp0: Double, amp1: Double, r: Random) {
        repeat(count) {
            val t0 = from + r.nextDouble() * (to - from)
            val k = (t0 - from) / (to - from)
            val amp = amp0 + (amp1 - amp0) * k
            val bp = Synth.BandPass(fLow + r.nextDouble() * (fHigh - fLow), 3.0)
            val len = 0.012 + r.nextDouble() * 0.03
            add(o, t0, len) { t -> bp.run(Synth.noise(r)) * exp(-t / len * 4) * amp }
        }
    }

    private fun strings(o: FloatArray, start: Double, dur: Double, notes: IntArray, vol: Double, attack: Double) {
        val ph = DoubleArray(notes.size * 3)
        val lps = Array(notes.size) { Synth.LowPass(1400.0) }
        add(o, start, dur + 0.8) { t ->
            val a = when {
                t < attack -> t / attack
                t < dur -> 1.0
                else -> exp(-(t - dur) * 5)
            }
            var x = 0.0
            for ((j, m) in notes.withIndex()) {
                val f = Synth.hz(m) * (1.0 + 0.003 * sin(TAU * 5.0 * t + j))
                ph[j * 3] += f * 1.004 / SR; ph[j * 3 + 1] += f * 0.996 / SR; ph[j * 3 + 2] += f / SR
                x += lps[j].run(Synth.saw(ph[j * 3]) + Synth.saw(ph[j * 3 + 1]) + 0.6 * Synth.saw(ph[j * 3 + 2]))
            }
            x * a * vol / notes.size
        }
    }

    private fun horn(o: FloatArray, start: Double, dur: Double, notes: IntArray, vol: Double) {
        val ph = DoubleArray(notes.size)
        val lps = Array(notes.size) { Synth.LowPass(400.0) }
        add(o, start, dur + 0.6) { t ->
            val a = when {
                t < 0.12 -> t / 0.12
                t < dur -> 1.0
                else -> exp(-(t - dur) * 6)
            }
            var x = 0.0
            for ((j, m) in notes.withIndex()) {
                ph[j] += Synth.hz(m) / SR
                lps[j].set(300.0 + 1800.0 * a)
                x += lps[j].run(Synth.saw(ph[j]) * 0.8 + Synth.sq(ph[j]) * 0.2)
            }
            x * a * vol / notes.size
        }
    }

    private fun drum(o: FloatArray, start: Double, vol: Double, freq: Double, r: Random, decay: Double = 6.0) {
        var p = 0.0
        val lp = Synth.LowPass(900.0)
        add(o, start, 1.2) { t ->
            p += (freq + freq * 0.8 * exp(-t * 30)) / SR
            (sin(TAU * p) * exp(-t * decay) + lp.run(Synth.noise(r)) * exp(-t * 25) * 0.7) * vol
        }
    }

    /** Wooden tock for buttons. */
    private fun click(): FloatArray {
        val r = Random(101); val bp = Synth.BandPass(1100.0, 5.0)
        return Synth.normalize(fill(0.06f) { t -> bp.run(Synth.noise(r)) * exp(-t * 120) * 1.5 + sin(TAU * 700 * t) * exp(-t * 90) * 0.5 }, 0.3f)
    }

    /** Radar ping with a faint echo. */
    private fun beep(): FloatArray {
        val o = fill(0.45f) { t -> sin(TAU * 1046 * t) * Synth.env(t, 0.003, 9.0) }
        add(o, 0.12, 0.3) { t -> sin(TAU * 1046 * t) * Synth.env(t, 0.003, 12.0) * 0.3 }
        return Synth.normalize(o, 0.35f)
    }

    /** Tank cannon: crack, blast, chest thump and a ringing barrel. */
    private fun shot(): FloatArray {
        val r = Random(102)
        val blast = Synth.LowPass(3000.0)
        val tail = Synth.LowPass(400.0)
        val crack = Synth.BandPass(4000.0, 0.8)
        var p = 0.0
        val o = fill(1.3f) { t ->
            blast.set(300.0 + 2700.0 * exp(-t * 20))
            p += (38.0 + 55.0 * exp(-t * 18)) / SR
            val tr = if (t < 0.006) crack.run(Synth.noise(r)) * 2.0 else 0.0
            val bl = blast.run(Synth.noise(r)) * exp(-t * 14) * 2.2
            val th = sin(TAU * p) * exp(-t * 7) * 1.1
            val ring = (sin(TAU * 340 * t) + 0.6 * sin(TAU * 910 * t) + 0.3 * sin(TAU * 1530 * t)) * exp(-t * 12) * 0.08
            val tl = tail.run(Synth.noise(r)) * exp(-t * 3) * 0.5
            tr + bl + th + ring + tl
        }
        return Synth.normalize(room(o, 0.3, 0.8), 0.92f)
    }

    /** Explosion: crack, roaring body, sub drop and debris raining down. */
    private fun boom(sec: Float, bright: Double): FloatArray {
        val r = Random(103)
        val body = Synth.LowPass(2500.0)
        val rumble = Synth.LowPass(160.0)
        var p = 0.0
        val o = fill(sec) { t ->
            body.set(120.0 + 2400.0 * bright * exp(-t * 4.5))
            p += (28.0 + 45.0 * exp(-t * 5)) / SR
            val crack = if (t < 0.01) Synth.noise(r) * 1.5 else 0.0
            val a = (if (t < 0.005) t / 0.005 else 1.0) * exp(-t * 2.2 / sec)
            crack + (body.run(Synth.noise(r)) * 2.6 + rumble.run(Synth.noise(r)) * 2.0 + sin(TAU * p) * exp(-t * 2.5)) * a
        }
        grains(o, 0.15, sec * 0.75, (40 * sec).toInt(), 700.0, 3200.0, 0.35, 0.05, r)
        if (bright < 0.8) {
            // A second, deeper roll for the big ones.
            val lp = Synth.LowPass(110.0)
            add(o, 0.25, sec - 0.25) { t -> lp.run(Synth.noise(r)) * 2.5 * sin(PI * min(1.0, t / 1.5)) * exp(-t * 1.2) }
        }
        return Synth.normalize(room(o, 0.4, 0.86), 0.95f)
    }

    /** Heavy rubber "dunk" with a metal rattle. */
    private fun bounce(): FloatArray {
        val r = Random(104); val lp = Synth.LowPass(500.0)
        var p = 0.0
        val o = fill(0.35f) { t ->
            p += (55.0 + 75.0 * exp(-t * 25)) / SR
            sin(TAU * p) * exp(-t * 18) + lp.run(Synth.noise(r)) * exp(-t * 40) * 0.5
        }
        grains(o, 0.01, 0.12, 4, 1800.0, 2600.0, 0.25, 0.1, r)
        return Synth.normalize(o, 0.55f)
    }

    /** Tracked vehicle: diesel pulse and track clatter. */
    private fun engine(): FloatArray {
        val r = Random(105); val lp = Synth.LowPass(480.0); val bp = Synth.BandPass(2500.0, 5.0)
        val o = fill(0.3f) { t ->
            var pulse = 0.0
            for (h in 1..6) pulse += sin(TAU * 32.0 * h * t) / h
            val growl = lp.run(pulse * 0.6 + Synth.noise(r) * 0.4)
            val tick = if ((t * 18).toInt() != ((t - 1.0 / SR) * 18).toInt()) 1.0 + r.nextDouble() else 0.0
            (growl * 1.3 + bp.run(tick * 4) * 0.45) * sin(PI * t / 0.3)
        }
        return Synth.normalize(o, 0.45f)
    }

    /** Sci-fi phase swell for the teleport. */
    private fun tele(): FloatArray {
        val r = Random(106); val bp = Synth.BandPass(800.0, 3.0)
        var p = 0.0
        val o = fill(1.1f) { t ->
            val k = t / 1.1
            p += (500.0 + 900.0 * k + 200.0 * sin(TAU * 11 * t)) / SR
            val swell = if (k < 0.6) (k / 0.6) * (k / 0.6) else exp(-(k - 0.6) * 9)
            bp.set(600.0 + 4000.0 * k, 3.0)
            (sin(TAU * p) * 0.35 + bp.run(Synth.noise(r)) * 1.4) * swell
        }
        return Synth.normalize(room(o, 0.5, 0.85), 0.5f)
    }

    /** Soft sine-based pad note: no sharp edges, just a slow swell. */
    private fun softPad(o: FloatArray, start: Double, dur: Double, notes: IntArray, vol: Double, attack: Double) {
        val ph = DoubleArray(notes.size * 2)
        val lp = Synth.LowPass(900.0)
        add(o, start, dur + 1.2) { t ->
            val a = when {
                t < attack -> sin(PI / 2 * t / attack).let { it * it }
                t < dur -> 1.0
                else -> exp(-(t - dur) * 3.0)
            }
            var x = 0.0
            for ((j, m) in notes.withIndex()) {
                val f = Synth.hz(m)
                ph[j * 2] += f * 1.002 / SR; ph[j * 2 + 1] += f * 0.998 / SR
                x += sin(TAU * ph[j * 2]) + 0.4 * Synth.tri(ph[j * 2 + 1])
            }
            lp.run(x) * a * vol / notes.size
        }
    }

    /** Glassy bell with a gentle onset. */
    private fun softBell(o: FloatArray, start: Double, midi: Int, vol: Double) {
        val f = Synth.hz(midi)
        add(o, start, 2.0) { t ->
            val a = min(1.0, t / 0.012)
            (sin(TAU * f * t) * exp(-t * 2.2) + 0.3 * sin(TAU * f * 2.0 * t) * exp(-t * 4.0) + 0.12 * sin(TAU * f * 3.01 * t) * exp(-t * 7.0)) * a * vol
        }
    }

    /** Round start: a calm rising swell with a soft two-note call on top. */
    private fun fanfare(): FloatArray {
        val o = Synth.buf(2.8f)
        softPad(o, 0.0, 1.4, intArrayOf(50, 57, 64, 66), 0.9, 0.5)
        softBell(o, 0.35, 69, 0.22)
        softBell(o, 0.7, 74, 0.25)
        // A low, rounded swell instead of a drum hit.
        add(o, 0.0, 1.8) { t -> sin(TAU * 55.0 * t) * sin(PI * min(1.0, t / 0.4) / 2) * exp(-t * 2.0) * 0.35 }
        return Synth.normalize(room(o, 0.5, 0.86), 0.55f)
    }

    /** Victory: a warm major chord and a rising arpeggio of bells. */
    private fun victory(): FloatArray {
        val o = Synth.buf(3.4f)
        softPad(o, 0.0, 1.8, intArrayOf(43, 50, 55, 59, 62), 0.9, 0.35)
        val arp = intArrayOf(67, 71, 74, 79)
        for ((k, n) in arp.withIndex()) softBell(o, 0.15 + k * 0.16, n, 0.2 + k * 0.02)
        softBell(o, 0.9, 83, 0.16)
        return Synth.normalize(room(o, 0.55, 0.88), 0.55f)
    }

    /** Promotion: strings rise through three chords and resolve with brass and cymbal. */
    private fun promo(): FloatArray {
        val o = Synth.buf(4.2f)
        val r = Random(108)
        strings(o, 0.0, 0.8, intArrayOf(50, 57, 62, 65), 0.8, 0.25)
        strings(o, 0.8, 0.7, intArrayOf(46, 53, 58, 62), 0.85, 0.15)
        strings(o, 1.5, 0.6, intArrayOf(48, 55, 60, 64), 0.9, 0.15)
        strings(o, 2.1, 1.4, intArrayOf(50, 57, 62, 66, 69), 1.0, 0.1)
        horn(o, 2.1, 1.3, intArrayOf(38, 50, 57, 62), 0.8)
        // Timpani roll and a cymbal swell into the final chord.
        var t = 1.5
        while (t < 2.1) { drum(o, t, 0.25 + (t - 1.5) * 0.6, 73.0, r, 9.0); t += 0.07 }
        drum(o, 2.1, 0.9, 55.0, r, 3.5)
        val bp = Synth.BandPass(7000.0, 0.7)
        add(o, 1.3, 2.4) { tt -> bp.run(Synth.noise(r)) * (if (tt < 0.8) (tt / 0.8) * 0.5 else 0.5 * exp(-(tt - 0.8) * 2.5)) }
        return Synth.normalize(room(o, 0.5, 0.88), 0.85f)
    }

    /** Defeat: a slow falling line in the low strings. */
    private fun lose(): FloatArray {
        val o = Synth.buf(3.8f)
        strings(o, 0.0, 1.0, intArrayOf(45, 52, 57, 60), 0.8, 0.35)
        strings(o, 1.0, 1.0, intArrayOf(41, 48, 53, 57), 0.8, 0.3)
        strings(o, 2.0, 1.3, intArrayOf(40, 47, 52, 56), 0.8, 0.3)
        return Synth.normalize(room(o, 0.5, 0.86), 0.65f)
    }

    /** Loading new equipment: servo whir, heavy latch and a hiss of air. */
    private fun cash(): FloatArray {
        val r = Random(109)
        val o = Synth.buf(0.9f)
        val lp = Synth.LowPass(1200.0)
        var p = 0.0
        add(o, 0.0, 0.28) { t ->
            p += (220.0 + 260.0 * (t / 0.28)) / SR
            lp.run(Synth.saw(p)) * sin(PI * t / 0.28) * 0.35
        }
        val bp = Synth.BandPass(700.0, 2.0)
        var q = 0.0
        add(o, 0.28, 0.4) { t ->
            q += (80.0 + 60.0 * exp(-t * 40)) / SR
            bp.run(Synth.noise(r)) * exp(-t * 35) * 1.6 + sin(TAU * q) * exp(-t * 16) * 0.9
        }
        val hiss = Synth.BandPass(5500.0, 1.0)
        add(o, 0.34, 0.5) { t -> hiss.run(Synth.noise(r)) * exp(-t * 8) * 0.35 }
        return Synth.normalize(room(o, 0.2), 0.6f)
    }

    /** Impact wrench, then air release. */
    private fun repair(): FloatArray {
        val r = Random(110)
        val o = Synth.buf(0.9f)
        val bp = Synth.BandPass(2000.0, 2.5)
        add(o, 0.0, 0.4) { t ->
            val buzz = 0.5 + 0.5 * Synth.sq(t * 38)
            bp.run(Synth.noise(r)) * buzz * 1.4 * sin(PI * t / 0.4)
        }
        val hiss = Synth.BandPass(5000.0, 1.0)
        add(o, 0.42, 0.45) { t -> hiss.run(Synth.noise(r)) * exp(-t * 7) * 0.8 }
        return Synth.normalize(room(o, 0.15), 0.55f)
    }

    /** A shell striking armour plate. */
    private fun hit(): FloatArray {
        val r = Random(111)
        val fs = doubleArrayOf(180.0, 470.0, 830.0, 1290.0, 1960.0)
        val o = fill(0.95f) { t ->
            var v = 0.0
            for ((k, f) in fs.withIndex()) v += sin(TAU * f * t) * exp(-t * (5.0 + k * 3.5)) / (1 + k * 0.6)
            val tr = if (t < 0.004) Synth.noise(r) * 1.5 else 0.0
            tr + v * 0.45 + sin(TAU * 80 * t) * exp(-t * 14) * 0.6
        }
        grains(o, 0.02, 0.25, 6, 2500.0, 4500.0, 0.2, 0.05, r)
        return Synth.normalize(room(o, 0.22), 0.75f)
    }

    /** Roaring flames with popping embers. */
    private fun fire(): FloatArray {
        val r = Random(112); val lp = Synth.LowPass(350.0)
        val o = fill(1.1f) { t -> lp.run(Synth.noise(r)) * 1.6 * (0.7 + 0.3 * sin(TAU * 3 * t)) * sin(PI * t / 1.1) }
        grains(o, 0.05, 1.0, 30, 1500.0, 5000.0, 0.35, 0.2, r)
        return Synth.normalize(o, 0.5f)
    }

    /** Casing popping open. */
    private fun split(): FloatArray {
        val r = Random(113); val bp = Synth.BandPass(3500.0, 1.0)
        return Synth.normalize(fill(0.35f) { t ->
            bp.run(Synth.noise(r)) * exp(-t * 45) * 1.3 + (sin(TAU * 2100 * t) + 0.6 * sin(TAU * 3400 * t)) * exp(-t * 30) * 0.3
        }, 0.55f)
    }

    /** Rocket launch: ignition pop and a rising roar. */
    private fun rocket(): FloatArray {
        val r = Random(114); val bp = Synth.BandPass(300.0, 0.8); val lp = Synth.LowPass(180.0)
        val o = fill(1.3f) { t ->
            bp.set(300.0 + 1200.0 * min(1.0, t / 0.8), 0.8)
            val a = (if (t < 0.03) t / 0.03 else 1.0) * exp(-t * 1.8)
            val pop = if (t < 0.02) Synth.noise(r) * 1.2 else 0.0
            pop + (bp.run(Synth.noise(r)) * 2.0 + lp.run(Synth.noise(r)) * 1.4) * a
        }
        grains(o, 0.05, 0.9, 25, 1200.0, 3500.0, 0.25, 0.05, r)
        return Synth.normalize(room(o, 0.25), 0.75f)
    }

    /** Earthquake: deep rumble with crumbling rock. */
    private fun quake(): FloatArray {
        val r = Random(115); val lp = Synth.LowPass(90.0)
        val o = fill(2.4f) { t ->
            val trem = 0.6 + 0.4 * sin(TAU * 7 * t)
            (lp.run(Synth.noise(r)) * 4.0 + sin(TAU * 30 * t) * 0.5) * trem * sin(PI * t / 2.4)
        }
        grains(o, 0.1, 2.2, 45, 250.0, 900.0, 0.4, 0.2, r)
        return Synth.normalize(room(o, 0.3), 0.9f)
    }

    /** Incoming meteor: a doppler-falling roar with crackle. */
    private fun meteor(): FloatArray {
        val r = Random(116); val bp = Synth.BandPass(3000.0, 1.2)
        val o = fill(1.5f) { t ->
            val k = t / 1.5
            bp.set(3000.0 - 2500.0 * k, 1.2)
            bp.run(Synth.noise(r)) * 2.0 * k * k
        }
        grains(o, 0.4, 1.45, 30, 2000.0, 5000.0, 0.05, 0.35, r)
        return Synth.normalize(room(o, 0.3), 0.6f)
    }

    /** Gravity bomb: suction swell, warped warble and a sub drop. */
    private fun grav(): FloatArray {
        val r = Random(117); val bp = Synth.BandPass(400.0, 2.0)
        var p = 0.0
        var q = 0.0
        val o = fill(2.1f) { t ->
            val k = t / 2.1
            p += (90.0 - 65.0 * k) / SR
            q += (220.0 + 120.0 * sin(TAU * 7 * t)) / SR
            val suck = if (k < 0.7) (k / 0.7) * (k / 0.7) else exp(-(k - 0.7) * 10)
            bp.set(300.0 + 500.0 * sin(TAU * 2 * t).let { it * it }, 2.0)
            sin(TAU * p) * 0.9 * sin(PI * k) + sin(TAU * q) * 0.15 * sin(PI * k) + bp.run(Synth.noise(r)) * 1.3 * suck
        }
        return Synth.normalize(room(o, 0.45), 0.75f)
    }
}

/**
 * One place for the mix. The music tracks are mastered to roughly the same loudness, and the
 * player volumes below bring them to [EFFECT_LOUDNESS], the level every effect is rendered at.
 */
object Mixer {
    const val EFFECT_LOUDNESS = 0.16f
    const val UI_LOUDNESS = 0.09f

    /** Loudest-200 ms level of the rendered tracks, measured once (see AudioTest). */
    val trackLoudness = mapOf("menu" to 0.343f, "shop" to 0.348f, "battle" to 0.266f)

    fun musicVolume(track: String): Float = (EFFECT_LOUDNESS / (trackLoudness[track] ?: 0.25f)).coerceIn(0.1f, 1f)
}

/** Plays the looping background music, generated in the background on first launch. */
class Music(ctx: Context) : MusicPlayer {
    private val dir = File(ctx.cacheDir, "music_v2").also { it.mkdirs() }
    private val ready = HashMap<String, File>()
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    override var current: String? = null
        private set
    private var wanted: String? = null
    private var paused = false

    override var enabled: Boolean = true
        set(v) {
            field = v
            if (!v) stopPlayer() else wanted?.let { play(it) }
        }

    init {
        Thread {
            // Old renders are replaced by the new pieces.
            File(ctx.cacheDir, "music_v1").deleteRecursively()
            for (name in MusicGen.tracks) {
                try {
                    val f = File(dir, "$name.wav")
                    if (!f.exists() || f.length() < 1000) f.writeBytes(Synth.wav(MusicGen.render(name)))
                    main.post {
                        ready[name] = f
                        if (wanted == name && current != name) play(name)
                    }
                } catch (_: Throwable) {
                }
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    override fun play(track: String) {
        wanted = track
        if (!enabled || paused) return
        if (current == track && player != null) return
        val f = ready[track] ?: return
        stopPlayer()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(f.absolutePath)
                isLooping = true
                val v = Mixer.musicVolume(track)
                setVolume(v, v)
                prepare()
                start()
            }
            current = track
        } catch (_: Throwable) {
            stopPlayer()
        }
    }

    override fun pause() {
        paused = true
        try { player?.pause() } catch (_: Throwable) {}
    }

    override fun resume() {
        paused = false
        if (!enabled) return
        val p = player
        if (p != null) try { p.start() } catch (_: Throwable) {} else wanted?.let { play(it) }
    }

    private fun stopPlayer() {
        try { player?.release() } catch (_: Throwable) {}
        player = null
        current = null
    }

    fun release() = stopPlayer()
}
