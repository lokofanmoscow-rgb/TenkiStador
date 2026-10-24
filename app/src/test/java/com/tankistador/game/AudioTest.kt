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
        for ((name, gen) in Sfx.all) check(name, gen())
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
