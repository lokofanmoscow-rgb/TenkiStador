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
        val t0 = System.currentTimeMillis()
        val menu = MusicGen.render("menu")
        val battle = MusicGen.render("battle")
        println("music rendered in ${System.currentTimeMillis() - t0} ms")
        check("music_menu", menu)
        check("music_battle", battle)
        assertTrue(menu.size > Synth.RATE * 15)
        // The loop seam should not click: the last and first samples are close.
        assertTrue(abs(menu.last() - menu.first()) < 0.25f)
        assertTrue(abs(battle.last() - battle.first()) < 0.25f)
    }
}
