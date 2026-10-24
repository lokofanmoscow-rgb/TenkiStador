package com.tankistador.game

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** The synthesised audio must be finite, audible and not clipped flat. */
class AudioTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots", "audio").also { it.mkdirs() }

    private fun check(name: String, a: FloatArray) {
        assertTrue("$name is empty", a.isNotEmpty())
        var peak = 0f
        var sum = 0.0
        for (v in a) {
            assertTrue("$name has NaN", !v.isNaN())
            peak = maxOf(peak, abs(v))
            sum += v * v
        }
        val rms = sqrt(sum / a.size)
        assertTrue("$name too quiet: $peak", peak > 0.2f)
        assertTrue("$name rms $rms", rms > 0.01 && rms < 0.7)
        File(outDir, "$name.wav").writeBytes(Synth.wav(a))
    }

    @Test
    fun effects() {
        for ((name, _) in Sfx.all) check(name, Sfx.render(name))
    }

    private fun db(x: Float) = 20 * kotlin.math.log10(maxOf(x, 1e-6f))

    /** Weapon sounds and battle music must come out at the same level (within 2 dB). */
    @Test
    fun effectsAndMusicAreBalanced() {
        for (track in MusicGen.tracks) {
            val measured = Synth.loudness(MusicGen.render(track))
            val expected = Mixer.trackLoudness[track]!!
            assertTrue("$track loudness table is stale: $measured vs $expected", abs(db(measured) - db(expected)) < 0.5f)
            val heard = db(measured * Mixer.musicVolume(track))
            assertTrue("$track plays at $heard dB", abs(heard - db(Mixer.EFFECT_LOUDNESS)) < 1f)
        }
        val battle = db(Mixer.trackLoudness["battle"]!! * Mixer.musicVolume("battle"))
        for (name in listOf("shot", "boom", "bigboom", "rocket", "hit", "quake", "meteor", "grav", "bounce", "fire", "tele")) {
            val l = db(Synth.loudness(Sfx.render(name)))
            assertTrue("$name is ${l - battle} dB away from the music", abs(l - battle) < 2f)
        }
    }

    @Test
    fun music() {
        for (name in MusicGen.tracks) {
            val t0 = System.currentTimeMillis()
            val a = MusicGen.render(name)
            println("music $name: ${a.size / Synth.RATE} s rendered in ${System.currentTimeMillis() - t0} ms")
            check("music_$name", a)
            assertTrue(a.size > Synth.RATE * 15)
            // The loop seam should be no rougher than the music itself.
            var inner = 0f
            for (i in 1 until a.size) inner = maxOf(inner, abs(a[i] - a[i - 1]))
            val seam = abs(a.first() - a.last())
            assertTrue("$name seam $seam vs $inner", seam <= maxOf(0.1f, inner))
        }
    }
}
