package com.tankistador.game

import android.graphics.Canvas

/** Things only the Android host can do (dialogs, quitting). Tests use a no-op host. */
interface Host {
    fun askText(title: String, initial: String, onDone: (String) -> Unit)
    fun quit()
}

abstract class Scene(val game: Game) {
    val buttons = ButtonSet()
    val vw get() = game.vw
    val vh get() = game.vh

    open fun enter() {}
    open fun layout() {}
    open fun update(dt: Float) = buttons.update(dt)
    abstract fun draw(c: Canvas)
    open fun down(id: Int, x: Float, y: Float) {
        if (buttons.down(id, x, y)) game.sound.play("click", 0.5f)
    }
    open fun move(id: Int, x: Float, y: Float) = buttons.move(id, x, y)
    open fun up(id: Int, x: Float, y: Float) {
        buttons.up(id, x, y)
    }
    open fun cancel() = buttons.cancelAll()
    open fun back() {}
}

class Game(val host: Host, kv: KeyValue, val sound: SoundPlayer) {
    var vw = 1280f
    var vh = 720f
    var time = 0f
    val store = Storage(kv)
    var settings = store.loadSettings()
    var scene: Scene = MenuScene(this)
        private set
    private var fade = 0f
    private var stars: android.graphics.Bitmap? = null

    init {
        sound.enabled = settings.sound
    }

    fun start() {
        scene.layout()
        scene.enter()
    }

    fun go(s: Scene) {
        scene.cancel()
        scene = s
        s.layout()
        s.enter()
        fade = 1f
    }

    fun resize(w: Float, h: Float) {
        if (w == vw && h == vh) return
        vw = w; vh = h
        stars = null
        scene.layout()
    }

    fun saveSettings() {
        store.saveSettings(settings)
        sound.enabled = settings.sound
    }

    fun update(dt: Float) {
        time += dt
        if (fade > 0f) fade = (fade - dt * 4f).coerceAtLeast(0f)
        scene.update(dt)
    }

    fun draw(c: Canvas) {
        scene.draw(c)
        if (fade > 0f) {
            Gfx.fill.shader = null
            Gfx.fill.color = Gfx.withAlpha(0xFF000000.toInt(), (fade * 255).toInt())
            c.drawRect(0f, 0f, vw, vh, Gfx.fill)
        }
    }

    /** Full-screen starfield shared by the space scenes. */
    fun drawStars(c: Canvas, parallaxX: Float = 0f, parallaxY: Float = 0f) {
        val b = stars ?: Tex.stars(7, (vw + 80).toInt(), (vh + 80).toInt()).also { stars = it }
        c.drawBitmap(b, -40f + parallaxX.coerceIn(-40f, 40f), -40f + parallaxY.coerceIn(-40f, 40f), null)
        // A handful of twinkling stars on top.
        for (i in 0 until 14) {
            val x = (i * 397 % 1000) / 1000f * vw
            val y = (i * 613 % 1000) / 1000f * vh
            val a = (0.5f + 0.5f * kotlin.math.sin(time * (1.3f + i * 0.17f) + i)).coerceIn(0f, 1f)
            Gfx.glow(c, x, y, 5f, Gfx.withAlpha(0xFFFFFFFF.toInt(), (a * 200).toInt()))
        }
    }

    fun back() = scene.back()
}

/** Abstraction over [Sound] so the game logic can run without Android audio. */
interface SoundPlayer {
    var enabled: Boolean
    fun play(name: String, vol: Float = 1f, rate: Float = 1f)
}

class AndroidSound(private val s: Sound) : SoundPlayer {
    override var enabled: Boolean
        get() = s.enabled
        set(v) { s.enabled = v }

    override fun play(name: String, vol: Float, rate: Float) = s.play(name, vol, rate)
}

object NoSound : SoundPlayer {
    override var enabled = false
    override fun play(name: String, vol: Float, rate: Float) {}
}
