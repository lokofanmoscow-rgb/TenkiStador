package com.tankistador.game

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class BattleScene(game: Game, val match: Match) : Scene(game) {
    private enum class St { INTRO, TURN, AIM, FLIGHT, SETTLE, ROUND_END, PROMO }

    companion object {
        const val PANEL_H = 170f
        const val MAX_TURNS = 40
        const val PLATE_H = 118f
    }

    private val snapshot = match.toJson().apply { put("stage", "battle") }
    val world = World(match.seed + match.round * 7919L)
    private val players = match.players
    private val renderer: WorldRenderer
    private val cam = Camera()
    private val mon = Camera()
    private val rng = Random(match.seed xor 0x5DEECE66DL + match.round)

    private var st = St.INTRO
    private var stT = 0f
    var turn = match.starter
        private set
    private var turnsTaken = 0
    private val weaponIdx = IntArray(players.size)
    private var turnTimer = 0f
    private var lastBeep = 0
    private var ai: Ai? = null
    private var aiPlan: Ai.Plan? = null
    private var aiT = 0f
    private var aiMoveLeft = 0f
    private val aiShots = IntArray(players.size)
    private var hintOn = game.settings.hints

    private var banner = ""
    private var bannerSub = ""
    private var bannerT = 0f
    private var bannerDur = 1f
    private var bannerColor = Gfx.YELLOW

    private var monInfo: String? = null
    private var monInfoT = 0f
    private var monLine = ""
    private var monLineT = 0f
    private var monNoise = 0f

    private var pickerOpen = false
    private var exitOpen = false
    private val picker = ButtonSet()
    private val exitSet = ButtonSet()

    // Camera state.
    private var fit = 1f
    private var zoom = 1f
    private var manual = false
    private var manZoom = 1f
    private var manX = 0f
    private var manY = 0f
    private var focusT = 0f
    private var focusX = 0f
    private var focusY = 0f
    private var shake = 0f
    private var monX = 0f
    private var monY = 0f

    // Pointers on the battlefield.
    private val ptrs = HashMap<Int, FloatArray>()
    private var aimPtr = -1
    private var pinchDist = 0f
    private var pinchZoom = 1f
    private var pinchWX = 0f
    private var pinchWY = 0f
    private var knobPtr = -1
    private var knobKind = 0

    // Round bookkeeping.
    private var winner = -2
    private val startMoney = IntArray(players.size) { players[it].money }
    private val startRank = IntArray(players.size) { players[it].rank }
    private val promos = ArrayList<Int>()
    private var promoIdx = 0
    private var moveSoundT = 0f
    private var lostShown = false

    // Layout.
    private var panelTop = 0f
    private val monRect = RectF()
    private val ovalRect = RectF()
    private var powKX = 0f
    private var powKY = 0f
    private var angKX = 0f
    private var angKY = 0f
    private val knobR = 34f

    private lateinit var bWPrev: Button
    private lateinit var bWNext: Button
    private lateinit var bInfo: Button
    private lateinit var bLeft: Button
    private lateinit var bRight: Button
    private lateinit var bPowM: Button
    private lateinit var bPowP: Button
    private lateinit var bAngM: Button
    private lateinit var bAngP: Button
    private lateinit var bFire: Button
    private lateinit var bHint: Button
    private lateinit var bZoomIn: Button
    private lateinit var bZoomOut: Button
    private lateinit var bExit: Button

    private val hintPath = FloatArray(400)
    // The path of each player's previous shot, drawn faintly while they aim again.
    private val lastPath = Array(players.size) { FloatArray(600) }
    private val lastPathN = IntArray(players.size)
    private var recT = 0f
    private var recording = -1
    private var timeScale = 1f
    private var slowT = 0f
    private val hintCount = IntArray(1)
    private var hintKey = ""
    private val tmp = FloatArray(2)

    init {
        world.generate(players)
        renderer = WorldRenderer(world)
        renderer.names = players.map { it.name }
        for (p in players) p.resetRoundStats()
        for (i in players.indices) weaponIdx[i] = 0
        buildButtons()
    }

    private val tank get() = world.tanks[turn]
    private val player get() = players[turn]
    private val humanTurn get() = !player.isAI && st == St.AIM

    // ------------------------------------------------------------------ layout

    private fun buildButtons() {
        bWPrev = buttons.add(Button("◀", Button.Style.METAL) { cycleWeapon(-1) })
        bWNext = buttons.add(Button("▶", Button.Style.METAL) { cycleWeapon(1) })
        bInfo = buttons.add(Button("?", Button.Style.BLUE) { showWeaponInfo() })
        bLeft = buttons.add(Button("◀", Button.Style.METAL, repeat = true))
        bRight = buttons.add(Button("▶", Button.Style.METAL, repeat = true))
        bPowM = buttons.add(Button("−", Button.Style.METAL, repeat = true) { if (humanTurn) tank.power = max(0f, tank.power - 1f) })
        bPowP = buttons.add(Button("+", Button.Style.METAL, repeat = true) { if (humanTurn) tank.power = min(100f, tank.power + 1f) })
        bAngM = buttons.add(Button("↻", Button.Style.METAL, repeat = true) { if (humanTurn) tank.aim = max(0f, tank.aim - 1f) })
        bAngP = buttons.add(Button("↺", Button.Style.METAL, repeat = true) { if (humanTurn) tank.aim = min(180f, tank.aim + 1f) })
        bFire = buttons.add(Button("ОГОНЬ", Button.Style.ROUND_RED) { fire() })
        bHint = buttons.add(Button("ПОДСКАЗКА", Button.Style.FLAT) {
            hintOn = !hintOn
            game.settings.hints = hintOn
            game.saveSettings()
        })
        bZoomIn = buttons.add(Button("+", Button.Style.FLAT) { zoomBy(1.3f) })
        bZoomOut = buttons.add(Button("−", Button.Style.FLAT) { zoomBy(1 / 1.3f) })
        bExit = buttons.add(Button("✕", Button.Style.FLAT) { openExit() })
        bFire.textSize = 20f
        bHint.textSize = 14f
    }

    override fun layout() {
        panelTop = vh - PANEL_H
        val cx = vw / 2
        monRect.set(cx - 128, panelTop + 18, cx + 128, vh - 14)
        ovalRect.set(cx - 618, panelTop + 34, cx - 352, panelTop + 112)
        bWPrev.set(cx - 618, panelTop + 120, cx - 548, panelTop + 158)
        bInfo.set(cx - 540, panelTop + 120, cx - 430, panelTop + 158)
        bWNext.set(cx - 422, panelTop + 120, cx - 352, panelTop + 158)
        bLeft.set(cx - 332, panelTop + 40, cx - 250, panelTop + 100)
        bRight.set(cx - 240, panelTop + 40, cx - 158, panelTop + 100)
        powKX = cx + 232; powKY = panelTop + 70
        angKX = cx + 412; angKY = panelTop + 70
        bPowM.set(cx + 150, panelTop + 122, cx + 188, panelTop + 160)
        bPowP.set(cx + 276, panelTop + 122, cx + 314, panelTop + 160)
        bAngM.set(cx + 330, panelTop + 122, cx + 368, panelTop + 160)
        bAngP.set(cx + 456, panelTop + 122, cx + 494, panelTop + 160)
        bFire.set(cx + 512, panelTop + 26, cx + 618, panelTop + 132)
        bHint.set(cx + 506, panelTop + 136, cx + 624, panelTop + 164)
        bExit.set(vw - 58, 126f, vw - 14, 170f)
        bZoomIn.set(vw - 58, 180f, vw - 14, 224f)
        bZoomOut.set(vw - 58, 234f, vw - 14, 278f)
        // Keep the field below the player plates but let it draw behind them.
        cam.view.set(0f, PLATE_H, vw, panelTop)
        cam.clip.set(0f, 0f, vw, panelTop)
        fit = min(vw / (World.FIELD_W + 100f), (panelTop - PLATE_H) / (World.FIELD_H + 40f))
        cam.scale = fit * zoom
        mon.view.set(monRect.left + 4, monRect.top + 4, monRect.right - 4, monRect.bottom - 4)
        mon.scale = fit * 4.2f
        if (pickerOpen) buildPicker()
        if (exitOpen) buildExit()
    }

    override fun enter() {
        showBanner("Раунд ${match.round}", "из ${match.totalRounds}", 2.2f, Gfx.YELLOW)
        game.sound.play("fanfare", 0.7f)
        renderer.activeTank = -1
    }

    // ------------------------------------------------------------------ flow

    private fun setState(s: St) {
        st = s
        stT = 0f
    }

    private fun startTurn() {
        setState(St.TURN)
        renderer.activeTank = turn
        val p = player
        showBanner(if (p.isAI) "Ходит ${p.name}" else "${p.name}, ваш ход!", Ranks.names[p.rank], 1.3f, p.color)
        monNoise = 0.45f
        manual = false
        turnTimer = game.settings.turnTime.toFloat()
        lastBeep = 99
        if (Weapons.all[weaponIdx[turn]].price > p.money) weaponIdx[turn] = 0
        ai = null
        aiPlan = null
        aiT = 0f
        aiMoveLeft = 0f
        if (p.isAI) startAi(true)
    }

    private fun startAi(allowMove: Boolean) {
        val enemy = world.tanks.firstOrNull { it.idx != turn && it.alive } ?: return
        ai = Ai(world, tank, enemy, player.aiLevel, player.money, aiShots[turn], rng, allowMove)
        aiPlan = null
    }

    private fun fire() {
        if (!humanTurn) return
        doFire()
    }

    private fun doFire() {
        val p = player
        val w = Weapons.all[weaponIdx[turn]]
        if (w.price > p.money) {
            showBanner("Не хватает денег!", "${w.name} стоит ${w.price}", 1.4f, Gfx.RED)
            game.sound.play("beep")
            return
        }
        p.money -= w.price
        if (!w.instant) {
            p.rShots++
            if (!p.isAI && hintOn && tank.antenna > 0f) p.rHintShots++
            aiShots[turn]++
        }
        world.fire(tank, w)
        when (w.kind) {
            WeaponKind.REPAIR -> game.sound.play("repair")
            WeaponKind.SHIELD -> game.sound.play("tele", 0.8f, 1.4f)
            WeaponKind.ROCKETS, WeaponKind.HOMING -> game.sound.play("rocket")
            WeaponKind.NUKE, WeaponKind.GRAVITY -> game.sound.play("shot", 1f, 0.7f)
            else -> game.sound.play("shot", 1f, if (w.damage > 30f) 0.85f else 1f)
        }
        if (!w.instant) {
            recording = turn
            lastPathN[turn] = 0
            recT = 0f
        }
        if (tank.gun <= 0f && !w.instant) showBanner("Пушка сломана!", "снаряд еле вылетает", 1.4f, Gfx.RED)
        lostShown = false
        setState(St.FLIGHT)
    }

    private fun afterShot() {
        if (checkRoundOver()) return
        world.burnFires()
        processEvents()
        if (checkRoundOver()) return
        turnsTaken++
        if (turnsTaken >= MAX_TURNS) {
            finishRound()
            return
        }
        turn = (turn + 1) % players.size
        startTurn()
    }

    private fun checkRoundOver(): Boolean {
        if (world.tanks.count { it.alive } <= 1) {
            finishRound()
            return true
        }
        return false
    }

    private fun finishRound() {
        val alive = world.tanks.filter { it.alive }
        winner = when {
            alive.size == 1 -> alive[0].idx
            alive.isEmpty() -> -1
            else -> {
                val a = alive.maxByOrNull { it.hull / it.hullMax }!!
                val b = alive.minByOrNull { it.hull / it.hullMax }!!
                if (abs(a.hull / a.hullMax - b.hull / b.hullMax) < 0.02f) -1 else a.idx
            }
        }
        for ((i, p) in players.withIndex()) {
            val t = world.tanks[i]
            // A wrecked tank is towed back and patched to a third of its hull for free.
            p.hull = if (t.alive) t.hull else max(t.hull, p.tank.hull * 0.3f)
            p.gun = t.gun; p.antenna = t.antenna; p.tracks = t.tracks; p.fuel = t.fuel
            if (i == winner) {
                p.roundWins++
                p.money += Econ.WIN_PRIZE + Econ.WIN_PRIZE_PER_ROUND * (match.round - 1)
                p.xp += 100
            } else {
                p.money += Econ.LOSE_PRIZE
            }
        }
        val now = System.currentTimeMillis()
        for ((i, p) in players.withIndex()) {
            val prof = game.store.profile(p.name)
            prof.rounds++
            if (i == winner) {
                prof.wins++
                prof.streak++
                prof.bestStreak = max(prof.bestStreak, prof.streak)
            } else prof.streak = 0
            prof.shots += p.rShots
            prof.direct += p.rDirect
            prof.damage += p.rDamage
            if (p.rSuicide) prof.suicides++
            prof.colonists += p.rColonists
            prof.buildings += p.rBuildings
            prof.hintShots += p.rHintShots
            prof.xp = max(prof.xp, p.xp)
            prof.lastPlayed = now
            game.store.updateProfile(prof)
            if (p.rank > startRank[i]) promos.add(i)
        }
        match.starter = if (winner >= 0) (winner + 1) % players.size else (match.starter + 1) % players.size
        match.round++
        renderer.activeTank = -1
        setState(St.ROUND_END)
        if (winner >= 0) {
            showBanner("${players[winner].name} побеждает!", "Раунд окончен", 3f, players[winner].color)
            game.sound.play("fanfare")
        } else {
            showBanner("Ничья!", "Раунд окончен", 3f, Gfx.YELLOW)
            game.sound.play("lose")
        }
    }

    private fun leaveRound() {
        if (promos.isNotEmpty() && st == St.ROUND_END) {
            setState(St.PROMO)
            promoIdx = 0
            game.sound.play("promo")
            return
        }
        if (st == St.PROMO && promoIdx + 1 < promos.size) {
            promoIdx++
            stT = 0f
            game.sound.play("promo")
            return
        }
        if (match.over) game.go(MatchOverScene(game, match))
        else Flow.startRound(game, match)
    }

    // ------------------------------------------------------------------ events

    private fun processEvents() {
        for (ev in world.events) {
            when (ev) {
                is Ev.Explosion -> {
                    game.sound.play(if (ev.radius > 60f) "bigboom" else "boom", min(1f, 0.4f + ev.radius / 60f))
                    shake = max(shake, min(14f, ev.radius * 0.2f))
                    if (game.settings.follow && !manual && ev.radius > 0f) {
                        focusT = 1.1f; focusX = ev.x; focusY = ev.y
                    }
                }
                is Ev.Hit -> {
                    val by = ev.by
                    val dmg = ev.dmg.roundToInt().coerceAtLeast(1)
                    if (by != ev.tank && by in players.indices) {
                        val p = players[by]
                        p.money += dmg * Econ.PER_DAMAGE
                        p.rDamage += dmg
                        p.xp += dmg
                        if (ev.direct) {
                            p.rDirect++
                            p.money += Econ.DIRECT_BONUS
                            p.xp += 30
                            showBanner("Прямое попадание!", "+${Econ.DIRECT_BONUS} монет", 1.3f, Gfx.YELLOW)
                        }
                    }
                    game.sound.play("hit", 0.7f)
                    val t = world.tanks[ev.tank]
                    ev.part?.let { part ->
                        val (v, m) = when (part) {
                            Part.GUN -> t.gun to t.gunMax; Part.ANTENNA -> t.antenna to t.antennaMax
                            Part.TRACKS -> t.tracks to t.tracksMax; Part.HULL -> t.hull to t.hullMax
                        }
                        monLine = "${players[ev.tank].name}: ${part.title.lowercase()} ${v.roundToInt()}/${m.roundToInt()}" +
                            if (v <= 0f) " — СЛОМАНО" else ""
                        monLineT = 2.5f
                        if (v <= 0f && ev.partDmg > 0f) {
                            val what = when (part) {
                                Part.GUN -> "пушка сломана"; Part.TRACKS -> "гусеницы разбиты"; Part.ANTENNA -> "антенна сбита"; Part.HULL -> ""
                            }
                            showBanner(players[ev.tank].name + ": " + what, "", 1.6f, Gfx.RED)
                        }
                    }
                }
                is Ev.Killed -> {
                    slowT = 1.4f
                    if (ev.by == ev.tank || ev.by < 0) {
                        players[ev.tank].rSuicide = true
                        showBanner("Самоподрыв!", players[ev.tank].name + " перестарался", 2f, Gfx.RED)
                    } else if (ev.by in players.indices) {
                        players[ev.by].money += Econ.KILL_BONUS
                        players[ev.by].xp += 150
                        showBanner("${players[ev.tank].name} уничтожен!", "+${Econ.KILL_BONUS} монет", 2f, players[ev.by].color)
                    }
                    game.sound.play("bigboom")
                }
                is Ev.BuildingLost -> if (ev.by in players.indices) {
                    val p = players[ev.by]
                    p.money = max(0, p.money - Econ.FINE_BUILDING)
                    p.rBuildings++
                    monLine = "Штраф за постройку: −${Econ.FINE_BUILDING}"
                    monLineT = 2f
                }
                is Ev.ColonistLost -> if (ev.by in players.indices) {
                    val p = players[ev.by]
                    p.money = max(0, p.money - Econ.FINE_COLONIST)
                    p.rColonists++
                    monLine = "Штраф за поселенца: −${Econ.FINE_COLONIST}"
                    monLineT = 2f
                }
                is Ev.Bounce -> game.sound.play("bounce", 0.6f)
                is Ev.Teleport -> game.sound.play("tele")
                is Ev.Split -> game.sound.play("split", 0.6f)
                is Ev.Lost -> if (!lostShown) {
                    lostShown = true
                    showBanner("Мимо!", "Снаряд улетел в космос", 1.3f, Gfx.GREY)
                }
                is Ev.FireStart -> game.sound.play("fire")
                is Ev.Burn -> game.sound.play("fire", 0.6f)
                is Ev.Rocket -> game.sound.play("rocket", 0.8f)
                is Ev.MineLaid -> {
                    game.sound.play("beep", 0.6f, 1.4f)
                    monLine = "Мины поставлены"; monLineT = 2f
                }
                is Ev.Quake -> {
                    game.sound.play("quake")
                    shake = max(shake, 12f)
                }
                is Ev.MeteorIncoming -> {
                    game.sound.play("meteor")
                    showBanner("Звездопад!", "метеориты на подлёте", 1.5f, 0xFFFFAB40.toInt())
                }
                is Ev.BlackHole -> {
                    game.sound.play("grav")
                    showBanner("Чёрная дыра!", "", 1.3f, 0xFFB388FF.toInt())
                }
                is Ev.Thrown -> {
                    showBanner("${players[ev.tank].name} летит!", "взрывом сорвало с грунта", 1.6f, players[ev.tank].color)
                }
                is Ev.Landed -> if (ev.dmg > 0f) game.sound.play("hit", 0.8f, 0.7f)
                is Ev.AsteroidBroken -> game.sound.play("boom", 0.5f, 1.3f)
                else -> {}
            }
        }
        world.events.clear()
    }

    // ------------------------------------------------------------------ helpers

    private fun showBanner(text: String, sub: String, dur: Float, color: Int) {
        banner = text; bannerSub = sub; bannerT = dur; bannerDur = dur; bannerColor = color
    }

    private fun cycleWeapon(d: Int) {
        if (!humanTurn) return
        val n = Weapons.all.size
        weaponIdx[turn] = (weaponIdx[turn] + d + n) % n
        monNoise = 0.15f
        monInfo = null
    }

    private fun showWeaponInfo() {
        val w = Weapons.all[weaponIdx[turn]]
        monInfo = w.name + "\n" + "тип: ${w.type}, " + (if (w.price == 0) "бесплатно" else "цена ${w.price}") +
            (if (w.damage > 0) ", урон ${w.damage.roundToInt()}" else "") + "\n" + w.desc
        monInfoT = 5f
    }

    private fun zoomBy(f: Float) {
        if (!manual) {
            manual = true
            manX = cam.cx; manY = cam.cy; manZoom = zoom
        }
        manZoom = (manZoom * f).coerceIn(0.7f, 4f)
    }

    private fun openExit() {
        exitOpen = true
        buildExit()
    }

    private fun buildExit() {
        exitSet.clear()
        val cx = vw / 2; val cy = vh / 2
        exitSet.add(Button("Выйти", Button.Style.RED) {
            game.store.saveMatch(Match.fromJson(snapshot))
            game.go(MenuScene(game))
        }.set(cx - 200, cy + 30, cx - 20, cy + 86))
        exitSet.add(Button("Играть", Button.Style.GREEN) { exitOpen = false }.set(cx + 20, cy + 30, cx + 200, cy + 86))
    }

    private fun buildPicker() {
        picker.clear()
        val cols = 6
        val cw = 196f; val ch = 112f
        val w = cols * cw + (cols - 1) * 10
        val left = vw / 2 - w / 2
        val top = 100f
        for ((i, wp) in Weapons.all.withIndex()) {
            val col = i % cols; val row = i / cols
            val b = Button(wp.name, Button.Style.FLAT) {
                weaponIdx[turn] = wp.id
                pickerOpen = false
                monNoise = 0.15f
            }
            b.set(left + col * (cw + 10), top + row * (ch + 10), left + col * (cw + 10) + cw, top + row * (ch + 10) + ch)
            picker.add(b)
        }
        picker.add(Button("✕", Button.Style.RED) { pickerOpen = false }.set(left + w - 50, 42f, left + w, 86f))
    }

    // ------------------------------------------------------------------ update

    override fun update(dt: Float) {
        stT += dt
        if (bannerT > 0f) bannerT -= dt
        if (monInfoT > 0f) monInfoT -= dt else monInfo = null
        if (monLineT > 0f) monLineT -= dt
        if (monNoise > 0f) monNoise -= dt
        if (focusT > 0f) focusT -= dt
        shake = max(0f, shake - dt * 30f)

        buttons.update(dt)
        val human = humanTurn
        bWPrev.enabled = human; bWNext.enabled = human; bInfo.enabled = st == St.AIM
        bLeft.enabled = human && tank.fuel > 0f && tank.tracks > 0f && !tank.air
        bRight.enabled = bLeft.enabled
        bPowM.enabled = human; bPowP.enabled = human; bAngM.enabled = human; bAngP.enabled = human
        bFire.enabled = human && Weapons.all[weaponIdx[turn]].price <= player.money
        bHint.selected = hintOn
        bHint.label = if (hintOn) "ПОДСКАЗКА ✓" else "ПОДСКАЗКА"

        if (slowT > 0f) slowT -= dt
        timeScale += ((if (slowT > 0f) 0.35f else 1f) - timeScale) * min(1f, dt * 6f)
        world.update(dt * timeScale)
        processEvents()
        recordShot(dt * timeScale)
        renderer.movingTank = -1

        when (st) {
            St.INTRO -> if (stT > 2.2f) startTurn()
            St.TURN -> if (stT > 0.9f) setState(St.AIM)
            St.AIM -> if (player.isAI) updateAi(dt) else updateHuman(dt)
            St.FLIGHT -> if (!world.busy && stT > 0.3f) {
                recording = -1
                setState(St.SETTLE)
            }
            St.SETTLE -> if (stT > 0.9f || (stT > 0.4f && world.particles.size < 30)) afterShot()
            St.ROUND_END, St.PROMO -> {}
        }
        updateCamera(dt)
    }

    private fun recordShot(dt: Float) {
        val who = recording
        if (who < 0) return
        val lead = world.projectiles.firstOrNull { !it.sub } ?: return
        recT += dt
        if (recT < 0.04f) return
        recT = 0f
        val n = lastPathN[who]
        if (n * 2 + 1 < lastPath[who].size) {
            lastPath[who][n * 2] = lead.x
            lastPath[who][n * 2 + 1] = lead.y
            lastPathN[who] = n + 1
        }
    }

    private fun updateHuman(dt: Float) {
        val dir = if (bLeft.isHeld) -1 else if (bRight.isHeld) 1 else 0
        if (dir != 0 && tank.tracks <= 0f) {
            monLine = "Гусеницы разбиты — танк не едет"
            monLineT = 1f
        } else if (dir != 0 && tank.fuel <= 0f) {
            monLine = "Бак пуст"
            monLineT = 1f
        }
        if (dir != 0 && world.moveTank(tank, dir, dt)) {
            renderer.movingTank = turn
            moveSoundT -= dt
            if (moveSoundT <= 0f) {
                game.sound.play("move", 0.5f)
                moveSoundT = 0.2f
            }
            monLine = "Топливо: ${tank.fuel.roundToInt()}"
            monLineT = 1f
        }
        if (game.settings.turnTime > 0 && !pickerOpen && !exitOpen) {
            turnTimer -= dt
            val sec = turnTimer.toInt()
            if (turnTimer < 5.5f && sec != lastBeep && turnTimer > 0f) {
                lastBeep = sec
                game.sound.play("beep", 0.5f)
            }
            if (turnTimer <= 0f) {
                showBanner("Время вышло!", "Ход переходит", 1.4f, Gfx.RED)
                aimPtr = -1
                setState(St.SETTLE)
                stT = 0.5f
            }
        }
    }

    private fun updateAi(dt: Float) {
        aiT += dt
        val a = ai ?: return
        if (aiMoveLeft > 0f) {
            aiMoveLeft -= dt
            val plan = aiPlan
            if (plan != null && world.moveTank(tank, plan.moveDir, dt)) renderer.movingTank = turn
            else aiMoveLeft = 0f
            if (aiMoveLeft <= 0f) startAi(false)
            return
        }
        if (aiPlan == null) {
            if (a.think(36)) {
                aiPlan = a.plan
                val plan = aiPlan!!
                if (plan.moveDir != 0) {
                    aiMoveLeft = plan.moveTime
                    return
                }
                weaponIdx[turn] = plan.weapon.id
            }
            return
        }
        val plan = aiPlan!!
        val t = tank
        val da = plan.aim - t.aim
        val dp = plan.power - t.power
        t.aim += da.coerceIn(-70f * dt, 70f * dt)
        t.power += dp.coerceIn(-60f * dt, 60f * dt)
        if (abs(da) < 0.3f && abs(dp) < 0.3f && aiT > 1.2f) {
            t.aim = plan.aim
            t.power = plan.power
            doFire()
        }
    }

    private fun updateCamera(dt: Float) {
        var tx: Float; var ty: Float; var tz: Float
        val lead = world.projectiles.firstOrNull { !it.sub } ?: world.projectiles.firstOrNull()
        val flyer = world.tanks.firstOrNull { it.air }
        val hole = world.holes.firstOrNull()
        when {
            manual -> { tx = manX; ty = manY; tz = manZoom }
            hole != null && game.settings.follow -> { tx = hole.x; ty = hole.y; tz = 1.8f }
            flyer != null && game.settings.follow -> { tx = flyer.x; ty = flyer.y; tz = 1.5f }
            focusT > 0f -> { tx = focusX; ty = focusY; tz = 1.7f }
            st == St.FLIGHT && game.settings.follow && lead != null -> {
                var ax = 0f; var ay = 0f
                for (p in world.projectiles) { ax += p.x; ay += p.y }
                ax /= world.projectiles.size; ay /= world.projectiles.size
                tx = lerp(0f, ax, 0.55f); ty = lerp(0f, ay, 0.55f); tz = 1.25f
            }
            else -> { tx = 0f; ty = 0f; tz = 1f }
        }
        val k = 1f - exp(-dt * 4f)
        zoom += (tz - zoom) * k
        cam.cx += (tx - cam.cx) * k
        cam.cy += (ty - cam.cy) * k
        val limX = World.FIELD_W / 2; val limY = World.FIELD_H / 2
        cam.cx = clamp(cam.cx, -limX, limX)
        cam.cy = clamp(cam.cy, -limY, limY)
        cam.scale = fit * zoom

        // The monitor follows whatever is flying, otherwise the active tank.
        val mole = world.moles.firstOrNull()
        val mt = world.tanks.getOrNull(turn)
        val call = world.meteorCalls.firstOrNull()
        val (mx, my) = when {
            hole != null -> hole.x to hole.y
            flyer != null -> flyer.x to flyer.y
            lead != null -> lead.x to lead.y
            call != null -> call.x to call.y
            mole != null -> mole.x to mole.y
            mt != null -> (mt.x + cos(mt.normal) * 8f) to (mt.y + sin(mt.normal) * 8f)
            else -> 0f to 0f
        }
        val km = 1f - exp(-dt * 8f)
        monX += (mx - monX) * km
        monY += (my - monY) * km
        if (abs(mx - monX) > 400f || abs(my - monY) > 400f) { monX = mx; monY = my }
        mon.cx = monX; mon.cy = monY
    }

    // ------------------------------------------------------------------ input

    override fun down(id: Int, x: Float, y: Float) {
        if (exitOpen) {
            exitSet.down(id, x, y); return
        }
        if (pickerOpen) {
            picker.down(id, x, y); return
        }
        if (st == St.ROUND_END || st == St.PROMO) {
            if (stT > 1.2f) leaveRound()
            return
        }
        if (buttons.down(id, x, y)) {
            game.sound.play("click", 0.4f)
            return
        }
        if (humanTurn && y > panelTop) {
            if (hypot(x - powKX, y - powKY) < knobR + 14) { knobPtr = id; knobKind = 0; knob(x, y); return }
            if (hypot(x - angKX, y - angKY) < knobR + 14) { knobPtr = id; knobKind = 1; knob(x, y); return }
            if (ovalRect.contains(x, y)) {
                pickerOpen = true
                buildPicker()
                game.sound.play("click", 0.4f)
                return
            }
        }
        if (y < panelTop) {
            ptrs[id] = floatArrayOf(x, y)
            if (ptrs.size == 1) {
                if (humanTurn) {
                    aimPtr = id
                    aimAt(x, y)
                }
            } else if (ptrs.size == 2) {
                aimPtr = -1
                startPinch()
            }
        }
    }

    override fun move(id: Int, x: Float, y: Float) {
        if (exitOpen) { exitSet.move(id, x, y); return }
        if (pickerOpen) { picker.move(id, x, y); return }
        buttons.move(id, x, y)
        if (id == knobPtr) { knob(x, y); return }
        val p = ptrs[id] ?: return
        p[0] = x; p[1] = y
        if (ptrs.size >= 2) pinch()
        else if (id == aimPtr && humanTurn) aimAt(x, y)
    }

    override fun up(id: Int, x: Float, y: Float) {
        if (exitOpen) { exitSet.up(id, x, y); return }
        if (pickerOpen) { picker.up(id, x, y); return }
        buttons.up(id, x, y)
        if (id == knobPtr) knobPtr = -1
        ptrs.remove(id)
        if (id == aimPtr) aimPtr = -1
    }

    override fun cancel() {
        buttons.cancelAll()
        ptrs.clear()
        aimPtr = -1
        knobPtr = -1
    }

    override fun back() {
        if (pickerOpen) pickerOpen = false
        else if (exitOpen) exitOpen = false
        else openExit()
    }

    private fun knob(x: Float, y: Float) {
        if (!humanTurn) return
        if (knobKind == 0) {
            val a = Math.toDegrees(atan2((y - powKY).toDouble(), (x - powKX).toDouble())).toFloat()
            var rel = (a - 135f) % 360f
            if (rel < 0) rel += 360f
            val frac = if (rel <= 270f) rel / 270f else if (rel < 315f) 1f else 0f
            tank.power = (frac * 100f).coerceIn(0f, 100f)
        } else {
            val a = Math.toDegrees(atan2((y - angKY).toDouble(), (x - angKX).toDouble())).toFloat()
            var aim = -a
            if (aim < 0f) aim = if (x > angKX) 0f else 180f
            tank.aim = aim.coerceIn(0f, 180f)
        }
    }

    private fun aimAt(sx: Float, sy: Float) {
        val t = tank
        val wx = cam.wx(sx); val wy = cam.wy(sy)
        val ang = atan2(wy - t.y, wx - t.x)
        var aim = 90f - wrapAngle(ang - t.normal) / DEG
        if (aim > 270f) aim -= 360f
        if (aim < -90f) aim += 360f
        t.aim = aim.coerceIn(0f, 180f)
    }

    private fun startPinch() {
        val pts = ptrs.values.toList()
        val a = pts[0]; val b = pts[1]
        pinchDist = max(10f, hypot(a[0] - b[0], a[1] - b[1]))
        if (!manual) {
            manual = true
            manX = cam.cx; manY = cam.cy; manZoom = zoom
        }
        pinchZoom = manZoom
        val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
        pinchWX = cam.wx(mx); pinchWY = cam.wy(my)
    }

    private fun pinch() {
        val pts = ptrs.values.toList()
        val a = pts[0]; val b = pts[1]
        val d = max(10f, hypot(a[0] - b[0], a[1] - b[1]))
        manZoom = (pinchZoom * d / pinchDist).coerceIn(0.7f, 4f)
        val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
        val s = fit * manZoom
        manX = pinchWX - (mx - cam.view.centerX()) / s
        manY = pinchWY - (my - cam.view.centerY()) / s
        // Apply instantly so the content sticks to the fingers.
        zoom = manZoom; cam.scale = s; cam.cx = manX; cam.cy = manY
    }

    // ------------------------------------------------------------------ drawing

    override fun draw(c: Canvas) {
        val sx = if (shake > 0f) (rng.nextFloat() - 0.5f) * shake else 0f
        val sy = if (shake > 0f) (rng.nextFloat() - 0.5f) * shake else 0f
        game.drawStars(c, -cam.cx * 0.03f, -cam.cy * 0.03f)
        drawSun(c)
        c.save()
        c.translate(sx, sy)
        renderer.draw(c, cam, game.time, false) { cc -> drawAimOverlay(cc) }
        c.restore()
        drawVignette(c)
        if (world.flash > 0f) {
            Gfx.fill.shader = null
            Gfx.fill.color = Gfx.withAlpha(0xFFFFF8E1.toInt(), (min(1f, world.flash / 0.6f) * 170).toInt())
            c.drawRect(0f, 0f, vw, panelTop, Gfx.fill)
        }
        drawTopHud(c)
        drawBanner(c)
        drawPanel(c)
        if (pickerOpen) drawPicker(c)
        if (st == St.ROUND_END && stT > 0.8f) drawRoundEnd(c)
        if (st == St.PROMO) drawPromo(c)
        if (exitOpen) drawExit(c)
    }

    private fun drawAimOverlay(c: Canvas) {
        if (st != St.AIM && st != St.TURN) return
        val t = world.tanks.getOrNull(turn) ?: return
        if (!t.alive) return
        val px = 1f / cam.scale
        val ln = lastPathN[turn]
        if (ln > 1 && !player.isAI) {
            val f = Gfx.fill
            f.shader = null
            f.color = Gfx.withAlpha(t.color, 90)
            for (i in 0 until ln step 2) c.drawCircle(lastPath[turn][i * 2], lastPath[turn][i * 2 + 1], 1.3f * px, f)
        }
        world.muzzleFor(t, t.aim, tmp)
        val a = t.aimWorld()
        val len = 20f + t.power * 0.9f
        val p = Gfx.stroke
        p.strokeWidth = 2f * px
        p.color = Gfx.withAlpha(t.color, 200)
        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * px, 5f * px), 0f)
        c.drawLine(tmp[0], tmp[1], tmp[0] + cos(a) * len, tmp[1] + sin(a) * len, p)
        p.pathEffect = null
        if (!player.isAI && hintOn && t.antenna > 0f) {
            val key = "${t.aim}|${t.power}|${t.x}|${t.y}|${world.planets.sumOf { it.version }}|${weaponIdx[turn]}"
            if (key != hintKey) {
                hintKey = key
                val secs = 0.35f + 1.5f * (t.antenna / t.antennaMax)
                world.simulate(t, t.aim, t.power, null, secs, hintPath, hintCount, Weapons.all[weaponIdx[turn]])
            }
            val f = Gfx.fill
            f.shader = null
            val n = hintCount[0]
            for (i in 0 until n) {
                if (i % 2 == 1) continue
                f.color = Gfx.withAlpha(0xFFFFFFFF.toInt(), 220 - 180 * i / max(1, n))
                c.drawCircle(hintPath[i * 2], hintPath[i * 2 + 1], 1.8f * px, f)
            }
        }
    }

    private fun drawTopHud(c: Canvas) {
        for (i in players.indices) {
            val p = players[i]
            val t = world.tanks[i]
            val l = if (i == 0) 10f else vw - 350f
            val r = l + 340f
            val active = i == turn && st in setOf(St.TURN, St.AIM, St.FLIGHT, St.SETTLE)
            Gfx.rr(c, l, 8f, r, 112f, 12f, 0xCC0C1418.toInt())
            Gfx.rrStroke(c, l, 8f, r, 112f, 12f, if (active) p.color else 0xFF37474F.toInt(), if (active) 3f else 2f)
            Gfx.text(c, p.name, l + 12, 31f, 20f, p.color, Gfx.Align.LEFT)
            Gfx.text(c, Ranks.names[p.rank] + if (p.isAI) " · ИИ" else "", r - 10, 29f, 13f, 0xFFB0BEC5.toInt(), Gfx.Align.RIGHT)
            drawSchematic(c, t, l + 12, 40f, 76f, 64f)
            val bl = l + 98; val br = r - 10
            val rows = listOf(
                Triple("КОРПУС", t.hull, t.hullMax), Triple("ПУШКА", t.gun, t.gunMax),
                Triple("ГУСЕНИЦЫ", t.tracks, t.tracksMax), Triple("АНТЕННА", t.antenna, t.antennaMax),
            )
            for ((k, row) in rows.withIndex()) {
                val y = 40f + k * 17f
                val f = (row.second / row.third).coerceIn(0f, 1f)
                Gfx.bar(c, bl, y, br, y + 14f, f, if (row.second <= 0f) 0xFF424242.toInt() else Gfx.healthColor(f))
                val txt = if (row.second <= 0f) "${row.first}: СЛОМАНО" else "${row.first} ${row.second.roundToInt()}/${row.third.roundToInt()}"
                Gfx.text(c, txt, (bl + br) / 2, y + 11.5f, 11f, if (row.second <= 0f) 0xFFFF8A80.toInt() else 0xFFFFFFFF.toInt())
            }
            if (t.shield > 0f) {
                val sf = (t.shield / 40f).coerceIn(0f, 1f)
                Gfx.rr(c, bl, 40f, bl + (br - bl) * sf, 54f, 7f, 0x6640C4FF)
            }
        }
        val score = players.joinToString(" : ") { it.roundWins.toString() }
        Gfx.text(c, score, vw / 2, 48f, 36f, 0xFFFFFFFF.toInt())
        Gfx.text(c, "Раунд ${min(match.round, match.totalRounds)} из ${match.totalRounds}", vw / 2, 70f, 14f, 0xFFB0BEC5.toInt())
        if (world.fires.isNotEmpty()) Gfx.text(c, "🔥 ${world.fires.size}", vw / 2, 92f, 14f, 0xFFFF8A65.toInt())
        bExit.draw(c); bZoomIn.draw(c); bZoomOut.draw(c)
        // Money above the control panel, as on the old arcade cabinets.
        for (i in players.indices) {
            val p = players[i]
            val x = if (i == 0) vw / 2 - 480 else vw / 2 + 480
            val col = if (i == turn && st == St.AIM) Gfx.GREEN else 0xFFB0BEC5.toInt()
            Gfx.text(c, "${p.money} монет", x, panelTop - 8, 20f, col)
        }
    }

    /** The distant sun that lights every planet from the upper left, with a lens flare. */
    private fun drawSun(c: Canvas) {
        val sx = vw * 0.08f - cam.cx * 0.05f
        val sy = PLATE_H + 30f - cam.cy * 0.05f
        val pulse = 1f + 0.03f * sin(game.time * 0.7f)
        Gfx.addGlow(c, sx, sy, 260f * pulse, 0x33FFE0B2)
        Gfx.addGlow(c, sx, sy, 90f * pulse, 0x88FFF3E0.toInt())
        Gfx.addGlow(c, sx, sy, 28f, 0xFFFFFFFF.toInt())
        // Flare ghosts along the line through the screen centre.
        val cx = vw / 2; val cy = panelTop / 2
        val ghosts = floatArrayOf(0.35f, 0.62f, 0.9f, 1.25f)
        val sizes = floatArrayOf(18f, 34f, 12f, 54f)
        val colors = intArrayOf(0x2240C4FF, 0x22FFAB40, 0x3369F0AE, 0x18B388FF)
        for (i in ghosts.indices) {
            val k = ghosts[i]
            Gfx.addGlow(c, sx + (cx - sx) * 2f * k, sy + (cy - sy) * 2f * k, sizes[i], colors[i])
        }
    }

    private val vignette = Paint(Paint.ANTI_ALIAS_FLAG)
    private var vignetteKey = 0f

    private fun drawVignette(c: Canvas) {
        val key = vw * 10000f + panelTop
        if (key != vignetteKey) {
            vignetteKey = key
            val r = kotlin.math.hypot(vw / 2, panelTop / 2) * 1.05f
            vignette.shader = RadialGradient(vw / 2, panelTop / 2, r, intArrayOf(0x00000000, 0x00000000, 0x88000000.toInt()),
                floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, vw, panelTop, vignette)
    }

    /** Side view of the tank with every part tinted by how damaged it is. */
    private fun drawSchematic(c: Canvas, t: Tank, l: Float, top: Float, w: Float, h: Float) {
        Gfx.rr(c, l, top, l + w, top + h, 8f, 0xFF061014.toInt())
        Gfx.rrStroke(c, l, top, l + w, top + h, 8f, 0xFF263238.toInt(), 1.5f)
        fun col(f: Float, broken: Boolean) = if (broken) 0xFF37474F.toInt() else Gfx.healthColor(f)
        val f = Gfx.fill
        f.shader = null
        val cx = l + w / 2; val by = top + h - 12
        // Tracks.
        f.color = col(t.tracks / t.tracksMax, t.tracks <= 0f)
        Gfx.rr(c, cx - 30, by - 4, cx + 30, by + 8, 6f, f.color)
        f.color = 0xFF0B1418.toInt()
        for (k in 0 until 5) c.drawCircle(cx - 24 + k * 12f, by + 2, 3f, f)
        // Hull.
        f.color = col(t.hull / t.hullMax, t.hull <= 0f)
        val hp = android.graphics.Path()
        hp.moveTo(cx - 30, by - 4); hp.lineTo(cx - 24, by - 16); hp.lineTo(cx + 24, by - 16); hp.lineTo(cx + 30, by - 4); hp.close()
        c.drawPath(hp, f)
        // Turret and gun.
        val gc = col(t.gun / t.gunMax, t.gun <= 0f)
        f.color = gc
        c.drawCircle(cx + 2, by - 18, 8f, f)
        Gfx.stroke.color = gc; Gfx.stroke.strokeWidth = 4f
        val gl = if (t.gun <= 0f) 8f else 22f
        c.drawLine(cx + 6, by - 20, cx + 6 + gl * 0.9f, by - 20 - gl * 0.45f, Gfx.stroke)
        // Antenna.
        val ac = col(t.antenna / t.antennaMax, t.antenna <= 0f)
        Gfx.stroke.color = ac; Gfx.stroke.strokeWidth = 2.5f
        if (t.antenna <= 0f) c.drawLine(cx - 18, by - 16, cx - 24, by - 22, Gfx.stroke)
        else c.drawLine(cx - 18, by - 16, cx - 20, by - 44, Gfx.stroke)
        f.color = ac
        if (t.antenna > 0f) c.drawCircle(cx - 20, by - 45, 3f, f)
        if (!t.alive) Gfx.text(c, "✕", cx, top + h / 2 + 10, 34f, Gfx.RED)
    }

    private fun drawBanner(c: Canvas) {
        if (bannerT <= 0f || banner.isEmpty()) return
        val age = bannerDur - bannerT
        val a = (min(1f, min(age / 0.2f, bannerT / 0.35f)) * 255).toInt()
        val scale = 1f + 0.25f * max(0f, 1f - age / 0.2f)
        val cy = panelTop * 0.42f
        c.save()
        c.scale(scale, scale, vw / 2, cy)
        val w = max(Gfx.measure(banner, 40f), Gfx.measure(bannerSub, 20f)) + 80f
        Gfx.fill.shader = LinearGradient(vw / 2 - w / 2, 0f, vw / 2 + w / 2, 0f,
            intArrayOf(0x00000000, Gfx.withAlpha(0xFF000000.toInt(), a * 2 / 3), Gfx.withAlpha(0xFF000000.toInt(), a * 2 / 3), 0x00000000),
            floatArrayOf(0f, 0.2f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(vw / 2 - w / 2, cy - 44, vw / 2 + w / 2, cy + 30, Gfx.fill)
        Gfx.fill.shader = null
        Gfx.text(c, banner, vw / 2, cy, 40f, bannerColor, alpha = a)
        if (bannerSub.isNotEmpty()) Gfx.text(c, bannerSub, vw / 2, cy + 24, 18f, 0xFFE0E0E0.toInt(), alpha = a)
        c.restore()
    }

    private fun label(c: Canvas, s: String, cx: Float, y: Float, w: Float) {
        Gfx.rr(c, cx - w / 2, y, cx + w / 2, y + 22, 6f, 0xFF1E272E.toInt())
        Gfx.rrStroke(c, cx - w / 2, y, cx + w / 2, y + 22, 6f, 0xFF90A4AE.toInt(), 1.5f)
        Gfx.text(c, s, cx, y + 16, 14f, 0xFFECEFF1.toInt(), shadow = false)
    }

    private fun drawPanel(c: Canvas) {
        val cx = vw / 2
        Gfx.metalPanel(c, -12f, panelTop, vw + 12f, vh + 24f, 18f)
        // Section wells.
        for ((l, r) in listOf(cx - 628 to cx - 342, cx - 342 to cx - 148, cx + 140 to cx + 324, cx + 320 to cx + 504, cx + 500 to cx + 630)) {
            Gfx.rr(c, l + 4, panelTop + 10, r - 4, vh - 6, 10f, 0x33000000)
        }
        label(c, "ОРУЖИЕ", (ovalRect.left + ovalRect.right) / 2, panelTop + 8, 110f)
        label(c, "ХОД", cx - 245, panelTop + 12, 70f)
        label(c, "СИЛА", powKX, panelTop + 8, 80f)
        label(c, "УГОЛ", angKX, panelTop + 8, 80f)

        // Weapon display.
        val w = Weapons.all[weaponIdx[turn]]
        val f = Gfx.fill
        f.shader = RadialGradient(ovalRect.centerX(), ovalRect.centerY() - 10, ovalRect.width() * 0.6f,
            intArrayOf(0xFF8E2A22.toInt(), 0xFF4A0F0B.toInt()), null, Shader.TileMode.CLAMP)
        c.drawOval(ovalRect, f)
        f.shader = null
        Gfx.stroke.color = 0xFFD7CCC8.toInt(); Gfx.stroke.strokeWidth = 4f
        c.drawOval(ovalRect, Gfx.stroke)
        Gfx.rr(c, ovalRect.left + 30, ovalRect.centerY() - 20, ovalRect.right - 30, ovalRect.centerY() + 8, 6f, 0xFF283593.toInt())
        Gfx.text(c, w.name, ovalRect.centerX(), ovalRect.centerY() + 1, 21f, 0xFFFFFFFF.toInt())
        val affordable = w.price <= player.money
        Gfx.text(c, (if (w.price == 0) "бесплатно" else "${w.price} монет") + " · " + w.type, ovalRect.centerX(), ovalRect.centerY() + 28, 14f,
            if (affordable) 0xFFFFE082.toInt() else Gfx.RED)
        if (t0().gun <= 0f) Gfx.text(c, "ПУШКА СЛОМАНА", bFire.rect.centerX(), bFire.rect.top - 2, 12f, Gfx.RED)
        if (humanTurn) Gfx.text(c, "нажмите для списка", ovalRect.centerX(), ovalRect.top + 18, 11f, 0xAAFFFFFF.toInt(), shadow = false)

        // Fuel.
        val t = world.tanks[turn]
        if (t.tracks <= 0f) Gfx.lcd(c, cx - 332, panelTop + 108, cx - 158, panelTop + 136, "НЕ ЕДЕТ", Gfx.RED, 17f)
        else Gfx.lcd(c, cx - 332, panelTop + 108, cx - 158, panelTop + 136, String.format("%05.1f", t.fuel))
        Gfx.text(c, "топливо", cx - 245, panelTop + 155, 13f, 0xFFECEFF1.toInt())

        // Knobs.
        drawKnob(c, powKX, powKY, knobR, 135f + t.power / 100f * 270f, Gfx.YELLOW, true)
        drawKnob(c, angKX, angKY, knobR, -t.aim, 0xFF80DEEA.toInt(), false)
        Gfx.lcd(c, cx + 192, panelTop + 124, cx + 272, panelTop + 158, String.format("%05.1f", t.power))
        Gfx.lcd(c, cx + 372, panelTop + 124, cx + 452, panelTop + 158, "${t.aim.roundToInt()}°")

        drawMonitor(c)
        for (b in buttons.list) if (b !== bExit && b !== bZoomIn && b !== bZoomOut) b.draw(c)
    }

    private fun t0() = world.tanks[turn]

    private fun drawKnob(c: Canvas, x: Float, y: Float, r: Float, pointerDeg: Float, color: Int, dial: Boolean) {
        val f = Gfx.fill
        f.color = 0x66000000
        c.drawCircle(x + 2, y + 4, r + 6, f)
        f.shader = LinearGradient(x, y - r - 6, x, y + r + 6, 0xFFCFD8DC.toInt(), 0xFF455A64.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r + 6, f)
        f.shader = RadialGradient(x - r * 0.3f, y - r * 0.35f, r * 1.3f, intArrayOf(0xFF8D6E63.toInt(), 0xFF4E342E.toInt(), 0xFF2B1B17.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, f)
        f.shader = null
        val s = Gfx.stroke
        s.strokeWidth = 2f
        if (dial) {
            for (i in 0..10) {
                val a = (135f + i * 27f) * DEG
                s.color = if (i <= 10 * (pointerDeg - 135f) / 270f) color else 0xFF546E7A.toInt()
                c.drawLine(x + cos(a) * (r + 1), y + sin(a) * (r + 1), x + cos(a) * (r + 5), y + sin(a) * (r + 5), s)
            }
        } else {
            for (i in 0..6) {
                val a = (-i * 30f) * DEG
                s.color = 0xFF546E7A.toInt()
                c.drawLine(x + cos(a) * (r + 1), y + sin(a) * (r + 1), x + cos(a) * (r + 5), y + sin(a) * (r + 5), s)
            }
        }
        val a = pointerDeg * DEG
        s.color = color; s.strokeWidth = 4f; s.strokeCap = Paint.Cap.ROUND
        c.drawLine(x + cos(a) * r * 0.2f, y + sin(a) * r * 0.2f, x + cos(a) * r * 0.85f, y + sin(a) * r * 0.85f, s)
        f.color = color
        c.drawCircle(x + cos(a) * r * 0.85f, y + sin(a) * r * 0.85f, 4f, f)
        f.color = 0xFF263238.toInt()
        c.drawCircle(x, y, 5f, f)
    }

    private fun drawMonitor(c: Canvas) {
        val m = monRect
        // Bezel.
        Gfx.rr(c, m.left - 12, m.top - 14, m.right + 12, m.bottom + 8, 18f, 0x77000000)
        Gfx.gradRect(c, m.left - 10, m.top - 12, m.right + 10, m.bottom + 6, 16f, 0xFFECEFF1.toInt(), 0xFF90A4AE.toInt())
        Gfx.rrStroke(c, m.left - 10, m.top - 12, m.right + 10, m.bottom + 6, 16f, 0xFF37474F.toInt(), 3f)
        Gfx.rr(c, m.left, m.top, m.right, m.bottom, 8f, 0xFF000000.toInt())
        // Little camera lens and REC lamp on the bezel.
        Gfx.fill.color = 0xFF263238.toInt()
        c.drawCircle(m.centerX(), m.top - 6, 4.5f, Gfx.fill)
        Gfx.fill.color = 0xFF4FC3F7.toInt()
        c.drawCircle(m.centerX() - 1, m.top - 7, 1.5f, Gfx.fill)
        val rec = (game.time * 2).toInt() % 2 == 0
        Gfx.fill.color = if (rec) 0xFFFF1744.toInt() else 0xFF5D1010.toInt()
        c.drawCircle(m.right - 6, m.top - 6, 3.5f, Gfx.fill)

        c.save()
        c.clipRect(m.left + 2, m.top + 2, m.right - 2, m.bottom - 2)
        val t = world.tanks[turn]
        val noSignal = !player.isAI && t.antenna <= 0f
        when {
            monNoise > 0f || noSignal || st == St.INTRO -> {
                c.drawBitmap(Tex.tvNoise((game.time * 24).toInt()), null, m, Gfx.bmp)
                if (noSignal) Gfx.text(c, "НЕТ СИГНАЛА", m.centerX(), m.centerY() + 8, 22f, Gfx.RED)
                if (noSignal) Gfx.text(c, "почините антенну", m.centerX(), m.centerY() + 30, 13f, 0xFFFFFFFF.toInt())
            }
            monInfo != null -> {
                Gfx.rr(c, m.left, m.top, m.right, m.bottom, 8f, 0xFF04120C.toInt())
                val lines = monInfo!!.split("\n")
                Gfx.text(c, lines[0], m.centerX(), m.top + 26, 19f, Gfx.YELLOW)
                Gfx.text(c, lines.getOrElse(1) { "" }, m.centerX(), m.top + 46, 13f, Gfx.LCD, shadow = false)
                Gfx.textBlock(c, lines.getOrElse(2) { "" }, m.centerX(), m.top + 54, m.width() - 24, 13f, 0xFFE0F2F1.toInt())
            }
            else -> {
                renderer.draw(c, mon, game.time, true)
                if (st == St.AIM && player.isAI) Gfx.text(c, "${player.name} целится" + ".".repeat(((game.time * 3).toInt() % 3) + 1),
                    m.centerX(), m.bottom - 30, 14f, 0xFFFFFFFF.toInt())
            }
        }
        if (monLineT > 0f && monInfo == null) {
            Gfx.rr(c, m.left + 4, m.top + 4, m.right - 4, m.top + 26, 5f, 0xAA000000.toInt())
            Gfx.text(c, monLine, m.centerX(), m.top + 20, 14f, Gfx.LCD, shadow = false)
        }
        if (game.settings.turnTime > 0 && st == St.AIM && !player.isAI) {
            val sec = max(0, turnTimer.toInt())
            Gfx.text(c, String.format("%d:%02d", sec / 60, sec % 60), m.centerX(), m.bottom - 8, 15f, if (sec <= 5) Gfx.RED else 0xFFFFFFFF.toInt(), font = Gfx.mono)
        }
        // Scanlines.
        Gfx.fill.color = 0x22000000
        var y = m.top
        while (y < m.bottom) {
            c.drawRect(m.left, y, m.right, y + 1.5f, Gfx.fill)
            y += 4f
        }
        c.restore()
    }

    private fun dim(c: Canvas, a: Int = 170) {
        Gfx.fill.shader = null
        Gfx.fill.color = Gfx.withAlpha(0xFF000000.toInt(), a)
        c.drawRect(0f, 0f, vw, vh, Gfx.fill)
    }

    private fun drawPicker(c: Canvas) {
        dim(c)
        val first = picker.list.first().rect
        val last = picker.list[Weapons.all.size - 1].rect
        val l = first.left - 20; val r = picker.list[5].rect.right + 20
        Gfx.screen(c, l, 30f, r, last.bottom + 20, Gfx.YELLOW)
        Gfx.text(c, "АРСЕНАЛ", l + 24, 72f, 28f, Gfx.YELLOW, Gfx.Align.LEFT)
        Gfx.text(c, "Деньги: ${player.money}", r - 70, 72f, 20f, Gfx.GREEN, Gfx.Align.RIGHT)
        for ((i, b) in picker.list.withIndex()) {
            if (i >= Weapons.all.size) { b.draw(c); continue }
            val w = Weapons.all[i]
            val rc = b.rect
            val sel = i == weaponIdx[turn]
            val ok = w.price <= player.money
            Gfx.rr(c, rc.left, rc.top, rc.right, rc.bottom, 10f, if (b.pressed) 0xFF37474F.toInt() else 0xFF1C2A30.toInt())
            Gfx.rrStroke(c, rc.left, rc.top, rc.right, rc.bottom, 10f, if (sel) Gfx.GREEN else 0xFF455A64.toInt(), if (sel) 3f else 1.5f)
            Gfx.fill.color = w.color
            c.drawCircle(rc.left + 14, rc.top + 20, 6f, Gfx.fill)
            Gfx.text(c, w.name, rc.left + 26, rc.top + 26, 17f, if (ok) 0xFFFFFFFF.toInt() else 0xFF757575.toInt(), Gfx.Align.LEFT)
            Gfx.text(c, if (w.price == 0) "даром" else "${w.price}", rc.right - 8, rc.top + 26, 15f, if (ok) Gfx.YELLOW else Gfx.RED, Gfx.Align.RIGHT)
            Gfx.text(c, w.type + if (w.damage > 0) " · урон ${w.damage.roundToInt()}" else "", rc.left + 10, rc.top + 45, 12f, 0xFF80CBC4.toInt(), Gfx.Align.LEFT, shadow = false)
            val lines = Gfx.wrap(w.desc, rc.width() - 16, 11.5f)
            for ((k, line) in lines.take(4).withIndex()) {
                Gfx.text(c, line, rc.left + 8, rc.top + 62 + k * 13, 11.5f, 0xFFB0BEC5.toInt(), Gfx.Align.LEFT, shadow = false)
            }
        }
    }

    private fun drawExit(c: Canvas) {
        dim(c)
        val cx = vw / 2; val cy = vh / 2
        Gfx.screen(c, cx - 260, cy - 120, cx + 260, cy + 110)
        Gfx.text(c, "Выйти в меню?", cx, cy - 60, 32f, Gfx.YELLOW)
        Gfx.text(c, "Игра сохранится. Раунд начнётся заново.", cx, cy - 18, 17f, 0xFFE0E0E0.toInt())
        exitSet.draw(c)
    }

    private fun drawRoundEnd(c: Canvas) {
        dim(c, 150)
        val cx = vw / 2
        val top = 120f
        Gfx.screen(c, cx - 360, top, cx + 360, top + 380)
        Gfx.text(c, "ИТОГИ РАУНДА ${match.round - 1}", cx, top + 46, 30f, Gfx.YELLOW)
        val wtxt = if (winner >= 0) "Победитель: ${players[winner].name}" else "Ничья"
        Gfx.text(c, wtxt, cx, top + 84, 22f, if (winner >= 0) players[winner].color else 0xFFFFFFFF.toInt())
        for ((i, p) in players.withIndex()) {
            val y = top + 130 + i * 100
            Gfx.text(c, "${Ranks.names[p.rank]} ${p.name}", cx - 330, y, 21f, p.color, Gfx.Align.LEFT)
            val delta = p.money - startMoney[i]
            Gfx.text(c, (if (delta >= 0) "+" else "") + "$delta монет", cx + 330, y, 21f, if (delta >= 0) Gfx.GREEN else Gfx.RED, Gfx.Align.RIGHT)
            Gfx.text(c, "Выстрелов: ${p.rShots}   прямых: ${p.rDirect}   урон: ${p.rDamage}", cx - 330, y + 28, 16f, 0xFFE0E0E0.toInt(), Gfx.Align.LEFT, shadow = false)
            val extra = buildList {
                if (p.rSuicide) add("самоподрыв")
                if (p.rBuildings > 0) add("построек: ${p.rBuildings}")
                if (p.rColonists > 0) add("поселенцев: ${p.rColonists}")
            }.joinToString(", ")
            if (extra.isNotEmpty()) Gfx.text(c, "Потери мирных: $extra", cx - 330, y + 52, 15f, 0xFFFF8A80.toInt(), Gfx.Align.LEFT, shadow = false)
        }
        if (stT > 1.2f) Gfx.text(c, "Нажмите, чтобы продолжить", cx, top + 360, 17f, 0xFFB0BEC5.toInt(),
            alpha = (180 + 75 * sin(game.time * 4)).toInt())
    }

    private fun drawPromo(c: Canvas) {
        dim(c, 160)
        val p = players[promos[promoIdx]]
        val cx = vw / 2; val cy = vh / 2 - 40
        val k = min(1f, stT / 0.4f)
        c.save()
        c.scale(k, k, cx, cy)
        drawMedal(c, cx, cy - 70, 70f, game.time)
        c.restore()
        Gfx.text(c, "ПОВЫШЕНИЕ!", cx, cy + 50, 40f, Gfx.YELLOW)
        Gfx.text(c, "${Ranks.names[startRank[promos[promoIdx]]]} ${p.name}", cx, cy + 90, 22f, p.color)
        Gfx.text(c, "теперь", cx, cy + 118, 18f, 0xFFE0E0E0.toInt())
        Gfx.text(c, Ranks.names[p.rank], cx, cy + 152, 30f, Gfx.GREEN)
        Gfx.text(c, "Жалованье: ${Ranks.salary(p.rank)} монет за раунд", cx, cy + 184, 16f, 0xFFB0BEC5.toInt())
    }
}

/** A gold star medal used for promotions and the match winner. */
fun drawMedal(c: Canvas, x: Float, y: Float, r: Float, t: Float) {
    val f = Gfx.fill
    for (i in 0 until 10) {
        val a = i * 36f * DEG + t * 0.4f
        Gfx.glow(c, x + cos(a) * r * 1.25f, y + sin(a) * r * 1.25f, r * 0.18f, 0xAAFFF59D.toInt())
    }
    f.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.2f, 0xFF90CAF9.toInt(), 0xFF0D47A1.toInt(), Shader.TileMode.CLAMP)
    c.drawCircle(x, y, r, f)
    f.shader = null
    Gfx.stroke.color = 0xFFFFD54F.toInt(); Gfx.stroke.strokeWidth = r * 0.08f
    c.drawCircle(x, y, r * 0.95f, Gfx.stroke)
    val path = android.graphics.Path()
    for (i in 0 until 10) {
        val a = (-90f + i * 36f) * DEG
        val rr = if (i % 2 == 0) r * 0.78f else r * 0.33f
        val px = x + cos(a) * rr; val py = y + sin(a) * rr
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    f.shader = LinearGradient(x, y - r, x, y + r, 0xFFFFF59D.toInt(), 0xFFFF8F00.toInt(), Shader.TileMode.CLAMP)
    c.drawPath(path, f)
    f.shader = null
    Gfx.stroke.color = 0xFF8D5A00.toInt(); Gfx.stroke.strokeWidth = r * 0.03f
    c.drawPath(path, Gfx.stroke)
}
