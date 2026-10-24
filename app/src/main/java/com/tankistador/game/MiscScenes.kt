package com.tankistador.game

import android.graphics.Canvas
import kotlin.math.sin

class RecordsScene(game: Game) : Scene(game) {
    private val list = game.store.loadProfiles().sortedByDescending { it.score }
    private var page = 0
    private val perPage = 9

    override fun layout() {
        buttons.clear()
        val cx = vw / 2
        buttons.add(Button("◀", Button.Style.METAL) { if (page > 0) page-- }.set(cx - 380, vh - 84, cx - 310, vh - 34))
        buttons.add(Button("▶", Button.Style.METAL) { if ((page + 1) * perPage < list.size) page++ }.set(cx + 310, vh - 84, cx + 380, vh - 34))
        buttons.add(Button("Назад", Button.Style.BLUE) { game.go(MenuScene(game)) }.set(cx - 110, vh - 84, cx + 110, vh - 34))
    }

    override fun back() = game.go(MenuScene(game))

    override fun draw(c: Canvas) {
        game.drawStars(c)
        val cx = vw / 2
        Gfx.screen(c, cx - 560, 30f, cx + 560, vh - 110)
        Gfx.text(c, "РЕКОРДЫ", cx, 76f, 36f, Gfx.YELLOW)
        val cols = floatArrayOf(cx - 530, cx - 470, cx - 140, cx + 40, cx + 150, cx + 260, cx + 380, cx + 530)
        val head = listOf("#", "Танкист", "Звание", "Очки", "Раунды", "Победы", "Точность", "Серия")
        val y0 = 118f
        for ((i, h) in head.withIndex()) {
            Gfx.text(c, h, cols[i], y0, 17f, 0xFF80CBC4.toInt(), if (i == 1 || i == 0) Gfx.Align.LEFT else Gfx.Align.RIGHT)
        }
        if (list.isEmpty()) {
            Gfx.text(c, "Пока никто не воевал. Будьте первым!", cx, 260f, 24f, 0xFFFFFFFF.toInt())
        }
        for (k in 0 until perPage) {
            val i = page * perPage + k
            val p = list.getOrNull(i) ?: break
            val y = y0 + 44 + k * 44
            if (k % 2 == 0) Gfx.rr(c, cx - 540, y - 28, cx + 540, y + 12, 6f, 0x22FFFFFF)
            val col = if (i == 0) Gfx.YELLOW else 0xFFFFFFFF.toInt()
            val vals = listOf("${i + 1}", p.name, Ranks.names[p.rank], "${p.score}", "${p.rounds}", "${p.wins}",
                "${p.pct(p.direct, p.shots)}%", "${p.bestStreak}")
            for ((j, v) in vals.withIndex()) {
                Gfx.text(c, v, cols[j], y, 20f, if (j == 2) 0xFFB0BEC5.toInt() else col,
                    if (j == 1 || j == 0) Gfx.Align.LEFT else Gfx.Align.RIGHT, shadow = false)
            }
        }
        buttons.draw(c)
    }
}

class SettingsScene(game: Game) : Scene(game) {
    private val s = game.settings
    private var confirmReset = false

    override fun layout() {
        buttons.clear()
        val cx = vw / 2
        val x0 = cx + 60
        fun row(i: Int, label: () -> String, act: () -> Unit) {
            val y = 140f + i * 66
            val b = Button(label(), Button.Style.BLUE)
            b.onClick = { act(); b.label = label(); game.saveSettings() }
            b.set(x0, y, x0 + 300, y + 50)
            b.textSize = 21f
            buttons.add(b)
        }
        row(0, { if (s.sound) "Включён" else "Выключен" }) { s.sound = !s.sound }
        row(1, { if (s.turnTime == 0) "Без ограничения" else "${s.turnTime} секунд" }) {
            s.turnTime = when (s.turnTime) { 30 -> 45; 45 -> 60; 60 -> 0; else -> 30 }
        }
        row(2, { if (s.hints) "Показывать" else "Скрывать" }) { s.hints = !s.hints }
        row(3, { if (s.follow) "Следить" else "Общий план" }) { s.follow = !s.follow }
        val reset = Button("Стереть рекорды", Button.Style.RED)
        reset.onClick = {
            if (confirmReset) {
                game.store.clearProfiles()
                reset.label = "Рекорды стёрты"
                confirmReset = false
            } else {
                confirmReset = true
                reset.label = "Точно стереть?"
            }
        }
        buttons.add(reset.set(x0, 140f + 4 * 66, x0 + 300, 190f + 4 * 66)).textSize = 21f
        buttons.add(Button("Назад", Button.Style.GREEN) { game.go(MenuScene(game)) }.set(cx - 120, vh - 100, cx + 120, vh - 44))
    }

    override fun back() = game.go(MenuScene(game))

    override fun draw(c: Canvas) {
        game.drawStars(c)
        val cx = vw / 2
        Gfx.screen(c, cx - 420, 40f, cx + 420, vh - 130)
        Gfx.text(c, "НАСТРОЙКИ", cx, 92f, 36f, Gfx.YELLOW)
        val labels = listOf("Звук", "Время на ход", "Подсказка траектории", "Камера в полёте", "Таблица рекордов")
        for ((i, l) in labels.withIndex()) {
            Gfx.text(c, l, cx + 30, 174f + i * 66, 23f, 0xFFFFFFFF.toInt(), Gfx.Align.RIGHT)
        }
        buttons.draw(c)
    }
}

class AuthorsScene(game: Game) : Scene(game) {
    private var t = 0f

    override fun layout() {
        buttons.clear()
        buttons.add(Button("Назад", Button.Style.GREEN) { game.go(MenuScene(game)) }.set(vw / 2 - 120, vh - 90, vw / 2 + 120, vh - 36))
    }

    override fun update(dt: Float) {
        super.update(dt)
        t += dt
    }

    override fun back() = game.go(MenuScene(game))

    override fun draw(c: Canvas) {
        game.drawStars(c, sin(t * 0.2f) * 20f, 0f)
        val cx = vw / 2
        Gfx.screen(c, cx - 440, 40f, cx + 440, vh - 120)
        Gfx.text(c, "АВТОРЫ", cx, 92f, 36f, Gfx.YELLOW)
        val text = "TankiStador — фанатская игра по мотивам старых артиллерийских дуэлей на планетах.\n\n" +
            "Танки ходят по очереди, ездят по крошечным планетам и стреляют снарядами, " +
            "которые притягивает каждая планета на пути. Между раундами — танковый базар, ремонт и новые звания.\n\n" +
            "Код, графика и звуки сгенерированы программно, без чужих ресурсов.\n" +
            "Сделано с помощью Claude Code."
        Gfx.textBlock(c, text, cx, 120f, 800f, 21f, 0xFFE0F2F1.toInt())
        for (i in 0 until 3) {
            c.save()
            val x = cx - 200 + i * 200f
            c.translate(x, vh - 160 + sin(t * 2 + i) * 4)
            c.scale(2.4f, 2.4f)
            TankArt.draw(c, Tanks.all[i * 3 + 1].look, if (i == 1) Player.COLOR_RIGHT else Player.COLOR_LEFT,
                TankArt.State(aim = 90f + 50f * sin(t + i), time = t + i, active = true))
            c.restore()
        }
        buttons.draw(c)
    }
}

class MatchOverScene(game: Game, val match: Match) : Scene(game) {
    private var t = 0f
    private val winner: Player?

    init {
        val best = match.players.maxOf { it.roundWins }
        val top = match.players.filter { it.roundWins == best }
        winner = if (top.size == 1) top[0] else null
        for (p in match.players) {
            val prof = game.store.profile(p.name)
            prof.matches++
            if (p === winner) prof.matchWins++
            game.store.updateProfile(prof)
        }
        game.store.clearSave()
    }

    override fun enter() = game.sound.play("fanfare")

    override fun layout() {
        buttons.clear()
        buttons.add(Button("В меню", Button.Style.GREEN) { game.go(MenuScene(game)) }.set(vw / 2 - 260, vh - 96, vw / 2 - 20, vh - 40))
        buttons.add(Button("Реванш", Button.Style.BLUE) { Flow.startRound(game, Flow.newMatch(game)) }.set(vw / 2 + 20, vh - 96, vw / 2 + 260, vh - 40))
    }

    override fun update(dt: Float) {
        super.update(dt)
        t += dt
    }

    override fun back() = game.go(MenuScene(game))

    override fun draw(c: Canvas) {
        game.drawStars(c)
        val cx = vw / 2
        Gfx.screen(c, cx - 440, 30f, cx + 440, vh - 120)
        Gfx.text(c, "ИТОГИ СРАЖЕНИЯ", cx, 80f, 36f, Gfx.YELLOW)
        drawMedal(c, cx, 170f, 60f, t)
        val w = winner
        Gfx.text(c, if (w != null) "Победил ${Ranks.names[w.rank]} ${w.name}!" else "Боевая ничья!", cx, 270f, 30f, w?.color ?: 0xFFFFFFFF.toInt())
        Gfx.text(c, match.players.joinToString("  :  ") { "${it.name} ${it.roundWins}" }, cx, 310f, 24f, 0xFFFFFFFF.toInt())
        for ((i, p) in match.players.withIndex()) {
            val prof = game.store.profile(p.name)
            val y = 370f + i * 80
            Gfx.text(c, "${Ranks.names[p.rank]} ${p.name}", cx - 400, y, 22f, p.color, Gfx.Align.LEFT)
            Gfx.text(c, "очков ${prof.score}, деньги ${p.money}", cx + 400, y, 20f, Gfx.YELLOW, Gfx.Align.RIGHT)
            Gfx.text(c, "Всего раундов: ${prof.rounds}, побед: ${prof.wins}, точность ${prof.pct(prof.direct, prof.shots)}%",
                cx - 400, y + 30, 17f, 0xFFB0BEC5.toInt(), Gfx.Align.LEFT, shadow = false)
        }
        buttons.draw(c)
    }
}
