package com.tankistador.game

import android.graphics.Canvas

class NewGameScene(game: Game) : Scene(game) {
    private val s = game.settings
    private val levels = listOf("новичок", "норма", "ас")
    private val roundOptions = listOf(3, 5, 7, 10)
    private val moneyOptions = listOf(800, 1500, 3000, 6000)
    private lateinit var bLeft: Button
    private lateinit var bRight: Button
    private lateinit var bType: Button

    override fun layout() {
        buttons.clear()
        val cx = vw / 2
        val top = 70f
        bLeft = buttons.add(Button(s.lastLeft, Button.Style.FLAT) {
            game.host.askText("Имя левого игрока", s.lastLeft) { s.lastLeft = it; bLeft.label = it }
        }.set(cx - 70, top + 78, cx + 230, top + 122))
        bRight = buttons.add(Button(s.lastRight, Button.Style.FLAT) {
            game.host.askText("Имя правого игрока", s.lastRight) { s.lastRight = it; bRight.label = it }
        }.set(cx - 70, top + 134, cx + 230, top + 178))
        bType = buttons.add(Button(typeLabel(), Button.Style.BLUE) {
            s.rightIsAI = !s.rightIsAI
            if (s.rightIsAI && s.lastRight == "Правый") s.lastRight = "Компьютер"
            if (!s.rightIsAI && s.lastRight == "Компьютер") s.lastRight = "Правый"
            bRight.label = s.lastRight
            bType.label = typeLabel()
        }.set(cx + 240, top + 134, cx + 380, top + 178))
        for (b in listOf(bLeft, bRight)) { b.textSize = 22f; b.textColor = 0xFFFFFFFF.toInt() }
        bLeft.textColor = Player.COLOR_LEFT
        bRight.textColor = Player.COLOR_RIGHT
        bType.textSize = 17f

        arrows(top + 206, { s.aiLevel = (s.aiLevel + 2) % 3 }, { s.aiLevel = (s.aiLevel + 1) % 3 })
        arrows(top + 262, { s.rounds = cycle(roundOptions, s.rounds, -1) }, { s.rounds = cycle(roundOptions, s.rounds, 1) })
        arrows(top + 318, { s.startMoney = cycle(moneyOptions, s.startMoney, -1) }, { s.startMoney = cycle(moneyOptions, s.startMoney, 1) })

        buttons.add(Button("Начать", Button.Style.GREEN) { start() }.set(cx - 300, top + 400, cx - 40, top + 456))
        buttons.add(Button("Отмена", Button.Style.RED) { game.go(MenuScene(game)) }.set(cx + 40, top + 400, cx + 300, top + 456))
    }

    private fun typeLabel() = if (s.rightIsAI) "ИИ" else "Человек"

    private fun cycle(list: List<Int>, v: Int, d: Int): Int {
        val i = list.indexOf(v).let { if (it < 0) 0 else it }
        return list[(i + d + list.size) % list.size]
    }

    private fun arrows(y: Float, prev: () -> Unit, next: () -> Unit) {
        val cx = vw / 2
        buttons.add(Button("◀", Button.Style.METAL, onClick = prev).set(cx - 70, y, cx - 20, y + 44))
        buttons.add(Button("▶", Button.Style.METAL, onClick = next).set(cx + 180, y, cx + 230, y + 44))
    }

    private fun start() {
        if (s.lastLeft.equals(s.lastRight, ignoreCase = true)) s.lastRight = s.lastRight + " 2"
        game.saveSettings()
        game.store.clearSave()
        Flow.startRound(game, Flow.newMatch(game))
    }

    override fun back() = game.go(MenuScene(game))

    override fun draw(c: Canvas) {
        game.drawStars(c)
        val cx = vw / 2
        val top = 70f
        Gfx.screen(c, cx - 420, top, cx + 420, top + 480)
        Gfx.text(c, "НОВАЯ ИГРА", cx, top + 52, 36f, 0xFFFFFFFF.toInt())
        val lx = cx - 390
        Gfx.text(c, "Левый игрок", lx, top + 108, 22f, Player.COLOR_LEFT, Gfx.Align.LEFT)
        Gfx.text(c, "Правый игрок", lx, top + 164, 22f, Player.COLOR_RIGHT, Gfx.Align.LEFT)
        Gfx.text(c, "Сложность ИИ", lx, top + 236, 22f, 0xFFFFFFFF.toInt(), Gfx.Align.LEFT)
        Gfx.text(c, "Раундов", lx, top + 292, 22f, 0xFFFFFFFF.toInt(), Gfx.Align.LEFT)
        Gfx.text(c, "Стартовые деньги", lx, top + 348, 22f, 0xFFFFFFFF.toInt(), Gfx.Align.LEFT)
        val vx = cx + 80
        Gfx.text(c, "…${levels[s.aiLevel.coerceIn(0, 2)]}…", vx, top + 236, 24f, if (s.rightIsAI) Gfx.YELLOW else Gfx.GREY)
        Gfx.text(c, "${s.rounds}", vx, top + 292, 26f, Gfx.YELLOW)
        Gfx.text(c, "${s.startMoney}", vx, top + 348, 26f, Gfx.YELLOW)
        Gfx.text(c, "Нажмите на имя, чтобы изменить его", cx, top + 386, 15f, 0xFF90A4AE.toInt())
        buttons.draw(c)
    }
}
