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
            val dir = File(ctx.cacheDir, "sfx_v3")
            dir.mkdirs()
            for ((name, gen) in Sfx.all) {
                val f = File(dir, "$name.wav")
                if (!f.exists() || f.length() < 100) f.writeBytes(Synth.wav(gen()))
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

/** The effect recipes: filtered noise, metal partials, brass chords and a touch of room. */
object Sfx {
    private const val SR = Synth.RATE
    private const val TAU = 2.0 * PI

    val all: List<Pair<String, () -> FloatArray>> = listOf(
        "click" to ::click, "beep" to ::beep, "shot" to ::shot, "boom" to { boom(1.5f, 1.0) },
        "bigboom" to { boom(2.8f, 0.55) }, "bounce" to ::bounce, "move" to ::engine, "tele" to ::tele,
        "fanfare" to ::fanfare, "promo" to ::promo, "lose" to ::lose, "cash" to ::cash, "repair" to ::repair,
        "hit" to ::hit, "fire" to ::fire, "split" to ::split, "rocket" to ::rocket, "quake" to ::quake,
        "meteor" to ::meteor, "grav" to ::grav,
    )

    private inline fun fill(sec: Float, f: (t: Double) -> Double): FloatArray {
        val o = Synth.buf(sec)
        for (i in o.indices) o[i] = f(i.toDouble() / SR).toFloat()
        return o
    }

    private fun room(dry: FloatArray, mix: Double, roomSize: Double = 0.75): FloatArray {
        Synth.addReverb(dry, dry.copyOf(), mix, roomSize)
        return dry
    }

    /** Short mechanical tick for buttons. */
    private fun click(): FloatArray {
        val r = Random(1); val bp = Synth.BandPass(2600.0, 3.0)
        return Synth.normalize(fill(0.04f) { t -> bp.run(Synth.noise(r)) * exp(-t * 260) + sin(TAU * 1100 * t) * exp(-t * 400) * 0.3 }, 0.35f)
    }

    /** Soft warning tone (timer, errors). */
    private fun beep(): FloatArray =
        Synth.normalize(fill(0.16f) { t -> (sin(TAU * 740 * t) + 0.3 * sin(TAU * 1480 * t)) * Synth.env(t, 0.006, 22.0) }, 0.4f)

    private fun shot(): FloatArray {
        val r = Random(2)
        val crack = Synth.BandPass(1800.0, 0.7)
        val body = Synth.LowPass(500.0)
        var p = 0.0
        val o = fill(0.9f) { t ->
            p += (45.0 + 70.0 * exp(-t * 20)) / SR
            sin(TAU * p) * exp(-t * 9) * 0.9 + crack.run(Synth.noise(r)) * exp(-t * 45) * 1.4 + body.run(Synth.noise(r)) * exp(-t * 6) * 0.9
        }
        return Synth.normalize(room(o, 0.25), 0.9f)
    }

    private fun boom(sec: Float, bright: Double): FloatArray {
        val r = Random(3)
        val lp = Synth.LowPass(2000.0)
        val lp2 = Synth.LowPass(900.0)
        var p = 0.0
        val o = fill(sec) { t ->
            lp.set(150.0 + 2200.0 * bright * exp(-t * 5))
            p += (32.0 + 40.0 * exp(-t * 6)) / SR
            val a = (if (t < 0.008) t / 0.008 else 1.0) * exp(-t * 2.6 / sec)
            val crackle = if (r.nextDouble() < 0.004 * exp(-t * 3)) Synth.noise(r) * 2 else 0.0
            (lp.run(Synth.noise(r)) * 2.4 + lp2.run(Synth.noise(r)) * 1.2 + sin(TAU * p) * 0.9 * exp(-t * 3) + crackle) * a
        }
        return Synth.normalize(room(o, 0.35, 0.82), 0.95f)
    }

    /** Dull rubbery thud. */
    private fun bounce(): FloatArray {
        val r = Random(4); val lp = Synth.LowPass(600.0)
        var p = 0.0
        return Synth.normalize(fill(0.3f) { t ->
            p += (70.0 + 110.0 * exp(-t * 30)) / SR
            sin(TAU * p) * exp(-t * 16) + lp.run(Synth.noise(r)) * exp(-t * 30) * 0.6
        }, 0.6f)
    }

    /** Diesel growl with track clatter. */
    private fun engine(): FloatArray {
        val r = Random(5); val lp = Synth.LowPass(380.0); val bp = Synth.BandPass(3000.0, 4.0)
        var p = 0.0
        return Synth.normalize(fill(0.3f) { t ->
            p += 46.0 / SR
            val growl = lp.run(Synth.saw(p) + 0.5 * Synth.saw(p * 2.01) + 0.3 * Synth.noise(r))
            val clank = if ((t * 22).toInt() != ((t - 1.0 / SR) * 22).toInt()) 1.0 else 0.0
            val env = sin(PI * t / 0.3)
            (growl * 1.2 + bp.run(clank * 3) * 0.6) * env
        }, 0.45f)
    }

    /** Filtered whoosh with a shimmer. */
    private fun tele(): FloatArray {
        val r = Random(6); val bp = Synth.BandPass(400.0, 2.0)
        val o = fill(0.9f) { t ->
            bp.set(400.0 + 3600.0 * (t / 0.9), 2.0)
            bp.run(Synth.noise(r)) * sin(PI * t / 0.9) * 1.8 + sin(TAU * (900 + 900 * t) * t) * 0.15 * sin(PI * t / 0.9)
        }
        return Synth.normalize(room(o, 0.4), 0.55f)
    }

    /** A sum of detuned saws through a filter that opens: a brass section. */
    private fun brass(o: FloatArray, start: Double, dur: Double, notes: IntArray, vol: Double) {
        val s0 = (start * SR).toInt()
        val n = ((dur + 0.6) * SR).toInt()
        val lps = Array(notes.size) { Synth.LowPass(300.0) }
        val ph = DoubleArray(notes.size * 2)
        for (k in 0 until n) {
            val t = k.toDouble() / SR
            val a = when {
                t < 0.09 -> t / 0.09
                t < dur -> 1.0 - 0.2 * (t / dur)
                else -> 0.8 * exp(-(t - dur) * 6)
            }
            var x = 0.0
            for ((j, m) in notes.withIndex()) {
                val f = Synth.hz(m)
                ph[j * 2] += f * 1.002 / SR; ph[j * 2 + 1] += f * 0.998 / SR
                lps[j].set(350.0 + 2200.0 * a)
                x += lps[j].run(Synth.saw(ph[j * 2]) + Synth.saw(ph[j * 2 + 1]))
            }
            val i = s0 + k
            if (i < o.size) o[i] += (x * a * vol / notes.size).toFloat()
        }
    }

    private fun timpani(o: FloatArray, start: Double, vol: Double, freq: Double) {
        val s0 = (start * SR).toInt()
        val r = Random(7); val lp = Synth.LowPass(400.0)
        var p = 0.0
        for (k in 0 until (1.2 * SR).toInt()) {
            val t = k.toDouble() / SR
            p += (freq + freq * 0.6 * exp(-t * 25)) / SR
            val i = s0 + k
            if (i < o.size) o[i] += ((sin(TAU * p) * 0.9 + lp.run(Synth.noise(r)) * exp(-t * 20)) * exp(-t * 3.5) * vol).toFloat()
        }
    }

    /** Round start / win: a low brass chord over a timpani hit. */
    private fun fanfare(): FloatArray {
        val o = Synth.buf(2.2f)
        timpani(o, 0.0, 0.9, 55.0)
        brass(o, 0.02, 1.2, intArrayOf(45, 52, 57, 61), 1.0)
        return Synth.normalize(room(o, 0.45, 0.85), 0.8f)
    }

    /** Promotion: two broad brass chords, subdominant to tonic. */
    private fun promo(): FloatArray {
        val o = Synth.buf(3.2f)
        timpani(o, 0.0, 0.7, 49.0)
        brass(o, 0.0, 0.75, intArrayOf(41, 53, 57, 60, 65), 0.9)
        timpani(o, 0.85, 1.0, 55.0)
        brass(o, 0.85, 1.6, intArrayOf(45, 57, 61, 64, 69), 1.1)
        return Synth.normalize(room(o, 0.5, 0.86), 0.85f)
    }

    /** Defeat: a dark minor chord sinking down. */
    private fun lose(): FloatArray {
        val o = Synth.buf(2.6f)
        brass(o, 0.0, 0.8, intArrayOf(38, 50, 53, 57), 0.8)
        brass(o, 0.8, 1.2, intArrayOf(33, 45, 48, 52), 0.8)
        timpani(o, 0.8, 0.6, 41.0)
        return Synth.normalize(room(o, 0.45, 0.85), 0.7f)
    }

    /** Purchase: a heavy latch clunk and a mechanical lock. */
    private fun cash(): FloatArray {
        val r = Random(8)
        val bp1 = Synth.BandPass(900.0, 2.0); val bp2 = Synth.BandPass(3400.0, 6.0)
        var p = 0.0
        val o = fill(0.55f) { t ->
            p += (90.0 + 60.0 * exp(-t * 40)) / SR
            val clunk = bp1.run(Synth.noise(r)) * exp(-t * 35) * 1.5 + sin(TAU * p) * exp(-t * 18) * 0.8
            val t2 = t - 0.14
            val latch = if (t2 > 0) bp2.run(Synth.noise(r)) * exp(-t2 * 60) * 1.4 + sin(TAU * 2350 * t2) * exp(-t2 * 30) * 0.15 else 0.0
            clunk + latch
        }
        return Synth.normalize(room(o, 0.2), 0.6f)
    }

    /** Wrench ratchet and a short metallic ring. */
    private fun repair(): FloatArray {
        val r = Random(9); val bp = Synth.BandPass(2500.0, 4.0)
        val o = fill(0.8f) { t ->
            val tick = t % 0.075
            val ratchet = if (t < 0.38) bp.run(Synth.noise(r)) * exp(-tick * 90) * 1.6 else 0.0
            val t2 = t - 0.4
            val ring = if (t2 > 0) (sin(TAU * 520 * t2) + 0.6 * sin(TAU * 1370 * t2) + 0.3 * sin(TAU * 2210 * t2)) * exp(-t2 * 7) * 0.25 else 0.0
            ratchet + ring
        }
        return Synth.normalize(room(o, 0.2), 0.55f)
    }

    /** Armour being struck. */
    private fun hit(): FloatArray {
        val r = Random(10); val lp = Synth.LowPass(1500.0)
        val fs = doubleArrayOf(212.0, 587.0, 1013.0, 1490.0)
        val o = fill(0.7f) { t ->
            var v = 0.0
            for ((k, f) in fs.withIndex()) v += sin(TAU * f * t) * exp(-t * (7.0 + k * 4)) / (k + 1)
            v * 0.6 + lp.run(Synth.noise(r)) * exp(-t * 40) * 0.9
        }
        return Synth.normalize(room(o, 0.25), 0.75f)
    }

    private fun fire(): FloatArray {
        val r = Random(11); val lp = Synth.LowPass(300.0)
        return Synth.normalize(fill(0.9f) { t ->
            val crack = if (r.nextDouble() < 0.012) Synth.noise(r) * 1.5 else 0.0
            (crack + lp.run(Synth.noise(r)) * 1.5) * sin(PI * t / 0.9)
        }, 0.5f)
    }

    /** Casing popping open. */
    private fun split(): FloatArray {
        val r = Random(12); val lp = Synth.LowPass(3000.0)
        var p = 0.0
        return Synth.normalize(fill(0.3f) { t ->
            p += (120.0 + 200.0 * exp(-t * 30)) / SR
            lp.run(Synth.noise(r)) * exp(-t * 35) + sin(TAU * p) * exp(-t * 18) * 0.6
        }, 0.6f)
    }

    private fun rocket(): FloatArray {
        val r = Random(13); val bp = Synth.BandPass(600.0, 1.0); val lp = Synth.LowPass(200.0)
        val o = fill(1.1f) { t ->
            bp.set(500.0 + 1800.0 * (t / 1.1), 1.0)
            val a = (if (t < 0.05) t / 0.05 else 1.0) * exp(-t * 2.2)
            (bp.run(Synth.noise(r)) * 1.8 + lp.run(Synth.noise(r)) * 1.2) * a
        }
        return Synth.normalize(room(o, 0.2), 0.75f)
    }

    private fun quake(): FloatArray {
        val r = Random(14); val lp = Synth.LowPass(120.0)
        val o = fill(2.2f) { t ->
            val trem = 0.6 + 0.4 * sin(TAU * 9 * t)
            (lp.run(Synth.noise(r)) * 3.5 + sin(TAU * 34 * t) * 0.6) * trem * sin(PI * t / 2.2)
        }
        return Synth.normalize(room(o, 0.3), 0.9f)
    }

    /** Falling whistle turning into a roar. */
    private fun meteor(): FloatArray {
        val r = Random(15); val lp = Synth.LowPass(800.0)
        var p = 0.0
        val o = fill(1.4f) { t ->
            p += (2400.0 - 1900.0 * (t / 1.4)) / SR
            sin(TAU * p) * 0.25 * (t / 1.4) + lp.run(Synth.noise(r)) * (t / 1.4) * 1.5
        }
        return Synth.normalize(room(o, 0.3), 0.6f)
    }

    /** Deep falling hum with a swirl of noise. */
    private fun grav(): FloatArray {
        val r = Random(16); val bp = Synth.BandPass(300.0, 3.0)
        var p = 0.0
        val o = fill(1.8f) { t ->
            p += (110.0 - 75.0 * (t / 1.8)) / SR
            bp.set(250.0 + 200.0 * sin(TAU * 3 * t), 3.0)
            (sin(TAU * p) * 0.8 + bp.run(Synth.noise(r)) * 1.2) * sin(PI * t / 1.8)
        }
        return Synth.normalize(room(o, 0.45), 0.75f)
    }
}

/** Plays the looping background music, generated in the background on first launch. */
class Music(ctx: Context) : MusicPlayer {
    private val dir = File(ctx.cacheDir, "music_v1").also { it.mkdirs() }
    private val ready = HashMap<String, File>()
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var current: String? = null
    private var wanted: String? = null
    private var paused = false

    override var enabled: Boolean = true
        set(v) {
            field = v
            if (!v) stopPlayer() else wanted?.let { play(it) }
        }

    init {
        Thread {
            for (name in listOf("menu", "battle")) {
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
                setVolume(0.45f, 0.45f)
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
