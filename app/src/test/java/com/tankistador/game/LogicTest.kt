package com.tankistador.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogicTest {

    private fun twoPlanetWorld(): World {
        val w = World(1)
        w.planets.add(Planet(0, -400f, 0f, 100f, 1f, 0, 1))
        w.planets.add(Planet(1, 400f, 0f, 100f, 1f, 2, 2))
        val players = listOf(Player("A", false, 1, 0), Player("B", true, 1, 1))
        for ((i, p) in players.withIndex()) {
            p.fillTank()
            val t = Tank(i, w.planets[i], if (i == 0) -1.2f else -1.9f, 0, p.color)
            t.gunMax = p.tank.gun.toFloat(); t.gun = t.gunMax
            t.hullMax = p.tank.hull.toFloat(); t.hull = t.hullMax
            t.antennaMax = 30f; t.antenna = 30f; t.tracksMax = 40f; t.tracks = 40f; t.fuelMax = 200f; t.fuel = 200f
            t.place()
            w.tanks.add(t)
        }
        return w
    }

    @Test
    fun generatedWorldHasSeparatedPlanetsAndPlacedTanks() {
        repeat(20) { seed ->
            val w = World(seed.toLong())
            val players = listOf(Player("A", false, 1, 0), Player("B", true, 1, 1)).onEach { it.fillTank() }
            w.generate(players)
            assertTrue(w.planets.size >= 2)
            for (a in w.planets) for (b in w.planets) if (a !== b) {
                assertTrue(hypot(a.x - b.x, a.y - b.y) > a.baseR + b.baseR)
            }
            assertEquals(2, w.tanks.size)
            assertTrue(w.tanks[0].planet !== w.tanks[1].planet)
            for (t in w.tanks) {
                val d = hypot(t.x - t.planet.x, t.y - t.planet.y)
                assertTrue("tank should rest on the surface", d > t.planet.baseR && d < t.planet.baseR + 15f)
            }
        }
    }

    @Test
    fun carvingDigsACraterButKeepsTheCore() {
        val p = Planet(0, 0f, 0f, 100f, 1f, 0, 0)
        assertTrue(p.carve(0f, -100f, 30f))
        assertTrue(p.surfaceAt(-Math.PI.toFloat() / 2) < 75f)
        assertEquals(100f, p.surfaceAt(Math.PI.toFloat() / 2), 0.01f)
        repeat(30) { p.carve(0f, 0f, 200f) }
        assertTrue(p.surfaceAt(0f) >= p.minR - 0.01f)
    }

    @Test
    fun gravityPullsShellsBackToThePlanet() {
        val w = twoPlanetWorld()
        val t = w.tanks[0]
        val r = w.simulate(t, 60f, 25f, null, 10f)
        assertTrue("a weak shot falls back", r.landed)
        assertEquals(0, r.planet)
    }

    @Test
    fun computerFindsAShotThatLandsNearTheEnemy() {
        val w = twoPlanetWorld()
        val ai = Ai(w, w.tanks[1], w.tanks[0], 2, 5000, 5, Random(3), false)
        var guard = 0
        while (!ai.think(200)) guard++
        val plan = ai.plan
        assertNotNull(plan)
        val r = w.simulate(w.tanks[1], plan!!.aim, plan.power, w.tanks[0], 9f)
        val d = if (r.hitTank == 0) 0f else hypot(r.x - w.tanks[0].x, r.y - w.tanks[0].y)
        assertTrue("ace computer should land close, was $d", d < 80f)
    }

    @Test
    fun directHitDamagesAndKillsAwardEvents() {
        val w = twoPlanetWorld()
        val target = w.tanks[0]
        val before = target.hull
        w.explode(target.x, target.y, 30f, 40f, 1, target, true)
        assertTrue(target.hull < before)
        assertTrue(w.events.any { it is Ev.Hit && it.direct && it.by == 1 })
        target.hull = 1f
        w.explode(target.x, target.y, 30f, 40f, 1, target, true)
        assertFalse(target.alive)
        assertTrue(w.events.any { it is Ev.Killed && it.tank == 0 && it.by == 1 })
    }

    @Test
    fun movingSpendsFuelAndStopsWhenEmpty() {
        val w = twoPlanetWorld()
        val t = w.tanks[0]
        val ang = t.ang
        repeat(30) { w.moveTank(t, 1, 1f / 30f) }
        assertTrue(t.ang != ang)
        assertTrue(t.fuel < 200f)
        t.fuel = 0f
        assertFalse(w.moveTank(t, 1, 0.1f))
    }

    @Test
    fun everyWeaponResolvesWithoutHanging() {
        for (wp in Weapons.all) {
            val w = twoPlanetWorld()
            val t = w.tanks[0]
            t.aim = 70f; t.power = 60f
            w.fire(t, wp)
            var steps = 0
            while (w.busy && steps < 60 * 30) {
                w.update(1f / 60f)
                steps++
            }
            assertFalse("${wp.name} still busy", w.busy)
        }
    }

    @Test
    fun shopRepairsAndUpgrades() {
        val p = Player("P", false, 1, 0)
        p.fillTank()
        p.money = 200
        p.hull = 50f
        assertTrue(p.repair(Part.HULL))
        assertEquals(100f, p.hull, 0.01f)
        assertEquals(200 - 50 * Econ.repairCost[Part.HULL.ordinal], p.money)
        // Not enough money: repair what the wallet covers.
        p.money = 30
        p.hull = 50f
        assertTrue(p.repair(Part.HULL))
        assertEquals(60f, p.hull, 0.01f)
        assertEquals(0, p.money)
        p.money = 10_000
        assertFalse("rank too low for the Mammoth", p.canBuy(7))
        assertTrue(p.buy(1))
        assertEquals(1, p.model)
        assertEquals(Tanks.all[1].hull.toFloat(), p.hull, 0.01f)
    }

    @Test
    fun ranksGrowWithExperience() {
        assertEquals(0, Ranks.of(0))
        assertEquals(1, Ranks.of(150))
        assertEquals(Ranks.names.size - 1, Ranks.of(1_000_000))
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val kv = MemKV()
        val store = Storage(kv)
        val m = aiMatch()
        m.round = 2
        m.players[0].money = 777
        m.players[1].model = 3
        store.saveMatch(m)
        val back = store.loadMatch()!!
        assertEquals(2, back.round)
        assertEquals(777, back.players[0].money)
        assertEquals(3, back.players[1].model)
        val prof = Profile("Иван").apply { xp = 500; wins = 3 }
        store.updateProfile(prof)
        assertEquals(500, store.profile("иван").xp)
    }

    @Test
    fun computerVersusComputerPlaysAWholeMatch() {
        val game = newGame()
        val m = aiMatch(rounds = 2, seed = 7L)
        Flow.startRound(game, m)
        var frames = 0
        while (game.scene !is MatchOverScene && frames < 60 * 60 * 20) {
            game.update(1f / 60f)
            val s = game.scene
            if (s is BattleScene && frames % 30 == 0) s.down(0, 10f, 10f)
            frames++
        }
        assertTrue("match should finish, stuck in ${game.scene.javaClass.simpleName}", game.scene is MatchOverScene)
        assertEquals(2, m.players.sumOf { it.roundWins }.coerceAtMost(2))
        assertTrue(game.store.loadProfiles().size >= 2)
    }
}
