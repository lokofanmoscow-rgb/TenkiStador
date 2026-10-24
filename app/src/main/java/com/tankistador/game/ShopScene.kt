package com.tankistador.game

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Between rounds each human visits the tank bazaar: buy a new tank, repair and refuel. */
class ShopScene(game: Game, val match: Match, val idx: Int) : Scene(game) {
    private val p = match.players[idx]
    private var sel = p.model
    private var t = 0f
    private var toast = ""
    private var toastT = 0f
    private var salaryT = 4f
    private val repairBtns = ArrayList<Button>()
    private lateinit var bBuy: Button
    private lateinit var bFuel: Button
    private lateinit var bAll: Button
    private lateinit var bSave: Button
    private var sceneTop = 0f
    private var sceneBottom = 0f
    private var left = 0f
    private var right = 0f
    private val parts = Part.values()

    override fun layout() {
        buttons.clear()
        repairBtns.clear()
        val cx = vw / 2
        left = cx - 620; right = cx + 620
        sceneTop = 66f; sceneBottom = 330f
        bSave = buttons.add(Button("Сохранить", Button.Style.BLUE) {
            game.store.saveMatch(match)
            showToast("Игра сохранена")
        }.set(right - 170, 338f, right - 6, 374f))
        bSave.textSize = 18f
        for ((i, part) in parts.withIndex()) {
            val y = 404f + i * 42f
            repairBtns.add(buttons.add(Button("", Button.Style.GREEN) {
                if (p.repair(part)) {
                    game.sound.play("repair", 0.6f)
                    game.store.saveMatch(match)
                } else showToast(if (p.partValue(part) >= p.partMax(part)) "Уже исправно" else "Не хватает денег")
            }.set(right - 128, y, right - 14, y + 34)))
        }
        bFuel = buttons.add(Button("", Button.Style.GREEN) {
            if (p.refuel()) {
                game.sound.play("cash", 0.6f)
                game.store.saveMatch(match)
            } else showToast(if (p.fuel >= p.tank.fuel) "Бак полон" else "Не хватает денег")
        }.set(right - 128, 404f + 4 * 42f, right - 14, 438f + 4 * 42f))
        for (b in repairBtns + bFuel) b.textSize = 16f
        bBuy = buttons.add(Button("Купить", Button.Style.GREEN) { buy() }.set(left + 120, 590f, left + 360, 632f))
        bBuy.textSize = 20f
        bAll = buttons.add(Button("Починить всё", Button.Style.METAL) {
            var any = false
            for (part in parts) any = p.repair(part) || any
            any = p.refuel() || any
            if (any) {
                game.sound.play("repair", 0.6f)
                game.store.saveMatch(match)
            } else showToast("Нечего чинить или нет денег")
        }.set(left, 652f, left + 300, 702f))
        buttons.add(Button("ДАЛЕЕ ▶", Button.Style.BLUE) { next() }.set(right - 300, 652f, right, 702f)).textSize = 26f
        buttons.add(Button("В меню", Button.Style.FLAT) { leave() }.set(cx - 90, 652f, cx + 90, 702f)).textSize = 18f
    }

    override fun enter() = game.sound.play("cash", 0.5f)

    private fun showToast(s: String) {
        toast = s
        toastT = 1.8f
        game.sound.play("beep", 0.4f)
    }

    private fun buy() {
        if (sel == p.model) return
        val m = Tanks.all[sel]
        when {
            p.rank < m.minRank -> showToast("Нужно звание: ${Ranks.names[m.minRank]}")
            p.money < p.upgradeCost(sel) -> showToast("Не хватает денег")
            p.buy(sel) -> {
                game.sound.play("fanfare", 0.7f)
                game.store.saveMatch(match)
                toast = "Поздравляем с покупкой!"; toastT = 2f
            }
        }
    }

    private fun next() {
        game.store.saveMatch(match)
        Flow.openShopOrBattle(game, match, idx)
    }

    private fun leave() {
        game.store.saveMatch(match)
        game.go(MenuScene(game))
    }

    override fun back() = leave()

    override fun update(dt: Float) {
        super.update(dt)
        t += dt
        if (toastT > 0f) toastT -= dt
        if (salaryT > 0f) salaryT -= dt
        val own = sel == p.model
        for ((i, b) in repairBtns.withIndex()) {
            val part = parts[i]
            b.visible = own
            val cost = p.repairCost(part)
            b.label = if (cost <= 0) "исправно" else "🔧 $cost"
            b.enabled = cost > 0
        }
        bFuel.visible = own
        val fc = p.refuelCost()
        bFuel.label = if (fc <= 0) "полный" else "⛽ $fc"
        bFuel.enabled = fc > 0
        bBuy.visible = !own
        val m = Tanks.all[sel]
        bBuy.label = if (p.rank < m.minRank) "Закрыто" else "Купить за ${p.upgradeCost(sel)}"
        bBuy.enabled = p.canBuy(sel)
    }

    override fun down(id: Int, x: Float, y: Float) {
        if (y in sceneTop..sceneBottom && x in left..right) {
            val slot = ((x - left) / ((right - left) / Tanks.all.size)).toInt().coerceIn(0, Tanks.all.size - 1)
            if (slot != sel) {
                sel = slot
                game.sound.play("click", 0.5f)
            }
            return
        }
        super.down(id, x, y)
    }

    override fun draw(c: Canvas) {
        Gfx.fill.shader = null
        Gfx.fill.color = 0xFF05070C.toInt()
        c.drawRect(0f, 0f, vw, vh, Gfx.fill)
        drawLandscape(c)
        drawTitle(c)

        // Info bar.
        Gfx.rr(c, left, 338f, right, 374f, 8f, 0xFF0E2A20.toInt())
        Gfx.rrStroke(c, left, 338f, right, 374f, 8f, 0xFF4CAF50.toInt(), 2f)
        Gfx.text(c, "${Ranks.names[p.rank]} ${p.name}", left + 16, 364f, 21f, p.color, Gfx.Align.LEFT)
        Gfx.text(c, "Баланс:", right - 420, 364f, 21f, 0xFFFFFFFF.toInt(), Gfx.Align.LEFT)
        Gfx.text(c, "${p.money}", right - 330, 364f, 23f, Gfx.GREEN, Gfx.Align.LEFT)
        val next = Ranks.nextXp(p.rank)
        Gfx.text(c, if (next > 0) "опыт ${p.xp}/$next" else "опыт ${p.xp}", right - 196, 362f, 14f, 0xFF90A4AE.toInt(), Gfx.Align.RIGHT)

        drawModelInfo(c)
        drawPreview(c)
        drawParts(c)
        buttons.draw(c)

        if (salaryT > 0f && p.lastSalary > 0) {
            val a = (minOf(1f, salaryT / 0.6f) * 255).toInt()
            Gfx.rr(c, vw / 2 - 330, sceneTop + 10, vw / 2 + 330, sceneTop + 46, 10f, Gfx.withAlpha(0xFF000000.toInt(), a / 2))
            Gfx.text(c, "${Ranks.names[p.rank]} ${p.name} получил жалованье: ${p.lastSalary} монет", vw / 2, sceneTop + 35, 20f, Gfx.YELLOW, alpha = a)
        }
        if (toastT > 0f) {
            val a = (minOf(1f, toastT / 0.4f) * 255).toInt()
            Gfx.rr(c, vw / 2 - 260, 270f, vw / 2 + 260, 318f, 12f, Gfx.withAlpha(0xFF000000.toInt(), a * 3 / 4))
            Gfx.text(c, toast, vw / 2, 302f, 24f, 0xFFFFFFFF.toInt(), alpha = a)
        }
    }

    private fun drawTitle(c: Canvas) {
        val cx = vw / 2
        Gfx.gradRect(c, cx - 230, 8f, cx + 230, 60f, 10f, 0xFFFF8F00.toInt(), 0xFFBF360C.toInt())
        Gfx.rrStroke(c, cx - 230, 8f, cx + 230, 60f, 10f, 0xFF3E2723.toInt(), 3f)
        Gfx.rivet(c, cx - 216, 34f); Gfx.rivet(c, cx + 216, 34f)
        Gfx.text(c, "ТАНКОВЫЙ БАЗАР", cx, 47f, 34f, 0xFFFFF8E1.toInt())
        Gfx.text(c, if (match.players.size > 1) "раунд ${match.round} из ${match.totalRounds}" else "", right, 40f, 16f, 0xFF90A4AE.toInt(), Gfx.Align.RIGHT)
    }

    private fun drawLandscape(c: Canvas) {
        val l = left; val r = right; val top = sceneTop; val bottom = sceneBottom
        c.save()
        c.clipRect(l, top, r, bottom)
        Gfx.fill.shader = LinearGradient(0f, top, 0f, bottom, 0xFF0B1E5B.toInt(), 0xFF5C8DD6.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(l, top, r, bottom, Gfx.fill)
        Gfx.fill.shader = null
        Tex.sunRays(c, l + (r - l) * 0.55f, top + 50, 22f, t)
        // Drifting clouds and balloons.
        for (i in 0 until 4) {
            val x = l + ((i * 330f + t * (12f + i * 3)) % (r - l + 200)) - 100
            val y = top + 30 + i * 18f
            cloud(c, x, y, 1f + i * 0.15f)
        }
        for (i in 0 until 3) {
            val x = l + ((i * 420f + 200 + t * 7f) % (r - l + 100)) - 50
            val y = top + 40 + 20 * sin(t * 0.7f + i) + i * 15
            balloon(c, x, y, intArrayOf(0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF8E24AA.toInt())[i])
        }
        mountains(c, l, r, bottom, 150f, 0xFF4E342E.toInt(), 0xFF3E2723.toInt(), 1)
        mountains(c, l, r, bottom, 105f, 0xFF6D4C41.toInt(), 0xFF4E342E.toInt(), 2)
        Gfx.fill.shader = LinearGradient(0f, bottom - 40, 0f, bottom, 0xFF33691E.toInt(), 0xFF1B3A0E.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(l, bottom - 40, r, bottom, Gfx.fill)
        Gfx.fill.shader = null

        val slotW = (r - l) / Tanks.all.size
        for ((i, m) in Tanks.all.withIndex()) {
            val x = l + slotW * (i + 0.5f)
            val y = bottom - 46
            if (i == sel) {
                Gfx.glow(c, x, y - 10, 70f, 0x66FFF59D)
                Gfx.stroke.color = Gfx.YELLOW; Gfx.stroke.strokeWidth = 3f
                c.drawOval(x - 58, y + 12, x + 58, y + 32, Gfx.stroke)
            }
            c.save()
            c.translate(x, y)
            val sc = 3.2f
            c.scale(sc, sc)
            val locked = p.rank < m.minRank
            TankArt.draw(c, m.look, if (locked) 0xFF757575.toInt() else p.color, TankArt.State(
                aim = 45f + 30f * sin(t * 0.8f + i), time = t + i, active = i == sel,
            ))
            c.restore()
            if (locked) Gfx.text(c, "🔒", x + 30, y - 34, 22f, 0xFFFFFFFF.toInt())
            if (i == p.model) Gfx.text(c, "ваш", x, y + 36, 15f, Gfx.GREEN)
        }
        c.restore()
        Gfx.rrStroke(c, l, top, r, bottom, 4f, 0xFF263238.toInt(), 3f)
    }

    private fun cloud(c: Canvas, x: Float, y: Float, s: Float) {
        Gfx.fill.color = 0xCCB0C4DE.toInt()
        c.drawCircle(x, y, 16f * s, Gfx.fill)
        c.drawCircle(x + 18 * s, y - 6 * s, 20f * s, Gfx.fill)
        c.drawCircle(x + 38 * s, y, 15f * s, Gfx.fill)
        c.drawRect(x, y, x + 38 * s, y + 15 * s, Gfx.fill)
    }

    private fun balloon(c: Canvas, x: Float, y: Float, color: Int) {
        Gfx.fill.shader = android.graphics.RadialGradient(x - 5, y - 6, 20f, Gfx.lighten(color, 0.4f), color, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, 14f, Gfx.fill)
        Gfx.fill.shader = null
        Gfx.stroke.color = 0xFF5D4037.toInt(); Gfx.stroke.strokeWidth = 1f
        c.drawLine(x - 8, y + 10, x - 4, y + 24, Gfx.stroke)
        c.drawLine(x + 8, y + 10, x + 4, y + 24, Gfx.stroke)
        Gfx.fill.color = 0xFF8D6E63.toInt()
        c.drawRect(x - 5, y + 23, x + 5, y + 29, Gfx.fill)
    }

    private fun mountains(c: Canvas, l: Float, r: Float, bottom: Float, h: Float, col: Int, dark: Int, seed: Int) {
        val path = Path()
        path.moveTo(l, bottom)
        var x = l
        var k = 0
        val peaks = ArrayList<FloatArray>()
        while (x <= r + 80) {
            val ph = h * (0.55f + 0.45f * Tex.noise(k * 0.9f, seed.toFloat(), seed))
            val px = x + 60
            path.lineTo(px, bottom - 40 - ph)
            peaks.add(floatArrayOf(px, bottom - 40 - ph, ph))
            x += 120 + 60 * Tex.noise(k * 1.7f, 3f, seed)
            path.lineTo(x, bottom - 40 - ph * 0.35f)
            k++
        }
        path.lineTo(r + 80, bottom)
        path.close()
        Gfx.fill.shader = LinearGradient(0f, bottom - h - 40, 0f, bottom, col, dark, Shader.TileMode.CLAMP)
        c.drawPath(path, Gfx.fill)
        Gfx.fill.shader = null
        Gfx.fill.color = 0xFFF5F5F5.toInt()
        for (pk in peaks) {
            if (pk[2] < h * 0.75f) continue
            val sp = Path()
            sp.moveTo(pk[0], pk[1])
            sp.lineTo(pk[0] - 22, pk[1] + 26)
            sp.lineTo(pk[0] - 8, pk[1] + 20)
            sp.lineTo(pk[0], pk[1] + 28)
            sp.lineTo(pk[0] + 10, pk[1] + 19)
            sp.lineTo(pk[0] + 20, pk[1] + 24)
            sp.close()
            c.drawPath(sp, Gfx.fill)
        }
    }

    private fun drawModelInfo(c: Canvas) {
        val m = Tanks.all[sel]
        val l = left; val r = left + 470f
        Gfx.screen(c, l + 6, 392f, r, 640f, 0xFF4CAF50.toInt(), 12f)
        Gfx.text(c, "«${m.name}»", (l + r) / 2, 426f, 26f, Gfx.YELLOW)
        Gfx.textBlock(c, m.desc, (l + r) / 2, 436f, r - l - 40, 18f, 0xFFFFFFFF.toInt())
        val own = sel == p.model
        val info = when {
            own -> "Это ваш танк. Сдать его можно за ${Tanks.tradeIn(p.model)}."
            p.rank < m.minRank -> "Продаётся со звания «${Ranks.names[m.minRank]}»"
            else -> "Цена ${m.price}, ваш танк зачтут за ${Tanks.tradeIn(p.model)}"
        }
        Gfx.text(c, info, (l + r) / 2, 572f, 15f, if (!own && p.rank < m.minRank) Gfx.RED else 0xFF80CBC4.toInt())
        if (own) Gfx.text(c, "Пожалуйста, подготовьте танк к бою!", (l + r) / 2, 618f, 16f, 0xFF90A4AE.toInt())
    }

    private fun drawPreview(c: Canvas) {
        val cx = vw / 2
        Gfx.screen(c, cx - 128, 392f, cx + 128, 640f, 0xFF4CAF50.toInt(), 12f)
        val m = Tanks.all[sel]
        Gfx.glow(c, cx, 540f, 110f, 0x3381D4FA)
        c.save()
        c.translate(cx, 520f)
        c.scale(5.6f / m.look.scale, 5.6f / m.look.scale)
        val own = sel == p.model
        TankArt.draw(c, m.look, p.color, TankArt.State(
            aim = 90f + 70f * sin(t * 0.9f),
            gunOk = if (own) p.gun else 1f, antennaOk = if (own) p.antenna else 1f, tracksOk = if (own) p.tracks else 1f,
            time = t, active = true, moving = true,
        ))
        c.restore()
        Gfx.stroke.color = 0x5581D4FA; Gfx.stroke.strokeWidth = 2f
        val a = t * 1.3f
        c.drawLine(cx + cos(a) * 100, 516f + sin(a) * 20, cx - cos(a) * 100, 516f - sin(a) * 20, Gfx.stroke)
        Gfx.text(c, if (own) "ваш танк" else "${m.price} монет", cx, 628f, 18f, if (own) Gfx.GREEN else Gfx.YELLOW)
    }

    private fun drawParts(c: Canvas) {
        val l = right - 470f; val r = right
        Gfx.screen(c, l, 392f, r - 6, 640f, 0xFF4CAF50.toInt(), 12f)
        val m = Tanks.all[sel]
        val own = sel == p.model
        val cur = p.tank
        val maxes = intArrayOf(m.gun, m.hull, m.antenna, m.tracks)
        val curMax = intArrayOf(cur.gun, cur.hull, cur.antenna, cur.tracks)
        for ((i, part) in parts.withIndex()) {
            val y = 404f + i * 42f
            Gfx.text(c, part.title, l + 16, y + 24, 18f, 0xFFE0E0E0.toInt(), Gfx.Align.LEFT)
            val bl = l + 120; val br = if (own) r - 140 else r - 24
            if (own) {
                val v = p.partValue(part); val mx = p.partMax(part)
                Gfx.bar(c, bl, y + 4, br, y + 30, v / mx, Gfx.healthColor(v / mx))
                Gfx.text(c, "${v.roundToInt()}/${mx.roundToInt()}", (bl + br) / 2, y + 23, 16f, 0xFFFFFFFF.toInt())
            } else {
                val diff = maxes[i] - curMax[i]
                Gfx.bar(c, bl, y + 4, br, y + 30, maxes[i] / 450f, 0xFF1E88E5.toInt())
                Gfx.text(c, "${maxes[i]} ед.", (bl + br) / 2 - 30, y + 23, 16f, 0xFFFFFFFF.toInt())
                Gfx.text(c, (if (diff >= 0) "+" else "") + diff, br - 10, y + 23, 16f, if (diff >= 0) Gfx.GREEN else Gfx.RED, Gfx.Align.RIGHT)
            }
        }
        val y = 404f + 4 * 42f
        Gfx.text(c, "Топливо", l + 16, y + 24, 18f, 0xFFE0E0E0.toInt(), Gfx.Align.LEFT)
        val bl = l + 120; val br = if (own) r - 140 else r - 24
        if (own) {
            Gfx.bar(c, bl, y + 4, br, y + 30, p.fuel / p.tank.fuel, 0xFFFFA000.toInt())
            Gfx.text(c, "${p.fuel.roundToInt()}/${p.tank.fuel}", (bl + br) / 2, y + 23, 16f, 0xFFFFFFFF.toInt())
        } else {
            Gfx.bar(c, bl, y + 4, br, y + 30, m.fuel / 500f, 0xFFFFA000.toInt())
            Gfx.text(c, "${m.fuel} л", (bl + br) / 2, y + 23, 16f, 0xFFFFFFFF.toInt())
        }
        Gfx.text(c, if (own) "Ремонт: пушка 4, корпус/антенна/гусеницы 3 за ед." else "Сравнение с вашим танком",
            (l + r) / 2, 628f, 13f, 0xFF90A4AE.toInt())
    }
}
