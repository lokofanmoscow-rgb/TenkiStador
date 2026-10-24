package com.tankistador.game

import android.graphics.Canvas
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MenuScene(game: Game) : Scene(game) {
    private lateinit var bNew: Button
    private lateinit var bLoad: Button
    private lateinit var bSettings: Button
    private lateinit var bExit: Button
    private var profiles: List<Profile> = emptyList()
    private var shown = 0
    private var cycleT = 0f
    private var t = 0f
    private val texRecords = Tex.planet(2, 77)
    private val texAuthors = Tex.planet(0, 91)
    private val texSmall = Tex.planet(3, 15)
    private var recX = 0f; private var recY = 0f; private var recR = 0f
    private var autX = 0f; private var autY = 0f; private var autR = 0f

    override fun enter() {
        profiles = game.store.loadProfiles().sortedByDescending { it.score }
        // Start the cycle at the most recently played profile.
        val last = profiles.maxByOrNull { it.lastPlayed }
        shown = if (last != null) profiles.indexOf(last) else 0
        bLoad.enabled = game.store.hasAnySave()
    }

    override fun layout() {
        buttons.clear()
        val cx = vw / 2
        val top = PANEL_TOP
        bNew = buttons.add(Button("Новая игра", Button.Style.TEXT) { game.go(NewGameScene(game)) }.set(cx - 200, top + 18, cx + 200, top + 60))
        bLoad = buttons.add(Button("Загрузить игру", Button.Style.TEXT) {
            game.go(SaveSlotsScene(game, null) { game.go(MenuScene(game)) })
        }.set(cx - 200, top + 66, cx + 200, top + 108))
        bSettings = buttons.add(Button("Настройки", Button.Style.TEXT) { game.go(SettingsScene(game)) }.set(cx - 200, top + 114, cx + 200, top + 156))
        bExit = buttons.add(Button("Выход", Button.Style.TEXT) { game.host.quit() }.set(cx - 200, top + 166, cx + 200, top + 206))
        for (b in listOf(bNew, bLoad, bSettings)) { b.textSize = 30f; b.textColor = 0xFFFFFFFF.toInt() }
        bExit.textSize = 26f; bExit.textColor = 0xFFFF5252.toInt()
        bLoad.enabled = game.store.hasAnySave()
        recX = cx - 470; recY = 300f; recR = 82f
        autX = cx + 470; autY = 230f; autR = 74f
    }

    override fun update(dt: Float) {
        super.update(dt)
        t += dt
        cycleT += dt
        if (cycleT > 4.5f && profiles.size > 1) {
            cycleT = 0f
            shown = (shown + 1) % profiles.size
        }
    }

    override fun down(id: Int, x: Float, y: Float) {
        if (hypot(x - recX, y - recY) < recR + 10) {
            game.sound.play("click", 0.5f)
            game.go(RecordsScene(game)); return
        }
        super.down(id, x, y)
    }

    override fun back() = game.host.quit()

    override fun draw(c: Canvas) {
        game.drawStars(c, sin(t * 0.1f) * 20f, cos(t * 0.08f) * 12f)
        val cx = vw / 2
        // Decorative planets that double as buttons.
        Tex.drawBall(c, texRecords, recX, recY, recR, t * 4f)
        Gfx.text(c, "РЕКОРДЫ", recX, recY + 10, 30f, 0xFF76FF03.toInt())
        Tex.drawBall(c, texAuthors, autX, autY, autR, -t * 3f)
        Tex.drawBall(c, texSmall, cx + 560, vh - 120, 26f, t * 10f)
        drawSatellite(c, cx - 520 + cos(t * 0.35f) * 60f, vh - 150 + sin(t * 0.5f) * 30f, t)
        drawMenuTank(c, autX - 20, autY - autR - 6, t)

        drawLogo(c, cx, 76f, t)
        val top = PANEL_TOP
        Gfx.screen(c, cx - 230, top, cx + 230, top + 214)
        buttons.draw(c)

        drawStats(c, cx, top + 262)
    }

    private fun drawStats(c: Canvas, cx: Float, y0: Float) {
        val p = profiles.getOrNull(shown)
        if (p == null) {
            Gfx.text(c, "Добро пожаловать, танкист!", cx, y0 + 20, 24f, 0xFFFFFFFF.toInt())
            Gfx.text(c, "Начните новую игру, чтобы попасть в таблицу рекордов.", cx, y0 + 52, 18f, 0xFF76FF03.toInt())
            return
        }
        val fade = (minOf(cycleT / 0.4f, (4.5f - cycleT) / 0.4f)).coerceIn(0f, 1f)
        val a = (fade * 255).toInt()
        val pos = shown + 1
        var y = y0 + 4
        val lines = listOf(
            "Место $pos, очков ${p.score}" to 0xFFFFFFFF.toInt(),
            "${Ranks.names[p.rank]} ${p.name}" to 0xFFFFEB3B.toInt(),
            "Сыграно раундов: ${p.rounds}, из них выиграно: ${p.wins}" to 0xFFE0E0E0.toInt(),
            "Прямых попаданий: ${p.direct}, точность: ${p.pct(p.direct, p.shots)}%" to 0xFF76FF03.toInt(),
            "Лучшая серия побед: ${p.bestStreak}" to 0xFF76FF03.toInt(),
            "Самоподрывы: ${p.pct(p.suicides, p.rounds)}%" to 0xFFFF5252.toInt(),
            "Задето поселенцев: ${p.colonists}, построек: ${p.buildings}" to 0xFFFF5252.toInt(),
            "Выстрелов с подсказкой: ${p.pct(p.hintShots, p.shots)}%" to 0xFF448AFF.toInt(),
        )
        for ((i, l) in lines.withIndex()) {
            Gfx.text(c, l.first, cx, y, if (i == 0) 24f else 19f, l.second, alpha = a)
            y += if (i == 0) 30f else 25f
        }
    }

    companion object {
        const val PANEL_TOP = 128f
    }

    private fun drawSatellite(c: Canvas, x: Float, y: Float, t: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(-25f + sin(t) * 6f)
        val f = Gfx.fill
        f.shader = null
        f.color = 0xFF1E88E5.toInt()
        c.drawRect(-46f, -9f, -14f, 9f, f)
        c.drawRect(14f, -9f, 46f, 9f, f)
        f.color = 0xFF90CAF9.toInt()
        for (k in 0 until 4) {
            c.drawRect(-44f + k * 8f, -7f, -38f + k * 8f, 7f, f)
            c.drawRect(16f + k * 8f, -7f, 22f + k * 8f, 7f, f)
        }
        Gfx.gradRect(c, -14f, -13f, 14f, 13f, 5f, 0xFFECEFF1.toInt(), 0xFF78909C.toInt())
        f.color = 0xFF37474F.toInt()
        c.drawCircle(0f, 0f, 5f, f)
        Gfx.stroke.color = 0xFFB0BEC5.toInt(); Gfx.stroke.strokeWidth = 2f
        c.drawLine(0f, -13f, 0f, -26f, Gfx.stroke)
        f.color = if ((t * 2).toInt() % 2 == 0) 0xFFFF5252.toInt() else 0xFF5D1010.toInt()
        c.drawCircle(0f, -27f, 3f, f)
        c.restore()
    }

    private fun drawMenuTank(c: Canvas, x: Float, y: Float, t: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(-12f)
        c.scale(2.6f, 2.6f)
        TankArt.draw(c, Tanks.all[3].look, Player.COLOR_LEFT, TankArt.State(aim = 40f + sin(t) * 20f, time = t, active = true))
        c.restore()
    }

}

/** The "TankPlanet" title: metal letters over a ringed planet. */
fun drawLogo(c: Canvas, cx: Float, base: Float, t: Float) {
    val size = 66f
    val text = "TankPlanet"
    val w = Gfx.measure(text, size)
    // Ringed planet behind the title.
    val px = cx + w / 2 + 22f
    val py = base - size * 0.36f
    Gfx.glow(c, px, py, 70f, 0x443D5AFE)
    val f = Gfx.fill
    f.shader = android.graphics.RadialGradient(px - 10, py - 12, 40f, 0xFFFFCC80.toInt(), 0xFF8D3C14.toInt(), android.graphics.Shader.TileMode.CLAMP)
    c.drawCircle(px, py, 30f, f)
    f.shader = null
    val ring = android.graphics.RectF(px - 58, py - 12, px + 58, py + 12)
    Gfx.stroke.color = 0xCCFFE0B2.toInt(); Gfx.stroke.strokeWidth = 4f
    c.save()
    c.rotate(-14f, px, py)
    c.drawArc(ring, 0f, 180f, false, Gfx.stroke)
    c.restore()
    // Letters: dark outline, then a steel gradient.
    val p = Gfx.text
    p.typeface = Gfx.bold
    p.textSize = size
    p.textAlign = android.graphics.Paint.Align.CENTER
    p.style = android.graphics.Paint.Style.STROKE
    p.strokeWidth = 9f
    p.color = 0xFF0B1220.toInt()
    c.drawText(text, cx, base, p)
    p.style = android.graphics.Paint.Style.FILL
    p.shader = android.graphics.LinearGradient(0f, base - size * 0.8f, 0f, base + 4f,
        intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFD54F.toInt(), 0xFFFF8F00.toInt(), 0xFF8D4A00.toInt()),
        floatArrayOf(0f, 0.35f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
    c.drawText(text, cx, base, p)
    p.shader = null
    p.strokeWidth = 0f
    // A glint sweeping across the title.
    val gx = cx - w / 2 + ((t * 0.35f) % 1.6f) * w
    c.save()
    c.clipRect(cx - w / 2, base - size, cx + w / 2, base + 8)
    Gfx.glow(c, gx, base - size * 0.35f, 40f, 0x55FFFFFF)
    c.restore()
    // Front half of the ring passes over the planet.
    c.save()
    c.rotate(-14f, px, py)
    Gfx.stroke.color = 0xEEFFE0B2.toInt()
    c.drawArc(ring, 180f, 180f, false, Gfx.stroke)
    c.restore()
}
