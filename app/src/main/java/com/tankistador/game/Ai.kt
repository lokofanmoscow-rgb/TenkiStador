package com.tankistador.game

import java.util.Random
import kotlin.math.sqrt

/**
 * Computer opponent. Plans a shot by flying many test shells through the same physics the
 * real shells use, spread over several frames, then adds a level-dependent aiming error.
 * Rocket weapons get a second search that includes their motors.
 */
class Ai(
    private val world: World,
    private val me: Tank,
    private val target: Tank,
    private val level: Int,
    private val money: Int,
    private val shotsAtTarget: Int,
    private val rng: Random,
    private val allowMove: Boolean,
) {
    class Plan(val aim: Float, val power: Float, val weapon: Weapon, val moveDir: Int, val moveTime: Float)

    private enum class Phase { COARSE, FINE, MOTOR, DONE }

    private val cand = ArrayList<FloatArray>()
    private var idx = 0
    private var phase = Phase.COARSE
    private val top = ArrayList<FloatArray>() // aim, power, score
    private var simWeapon: Weapon? = null
    private var chosen: Weapon? = null
    private var motorBest = floatArrayOf(0f, 0f, Float.MAX_VALUE)
    var plan: Plan? = null
        private set

    init {
        var a = 4f
        while (a <= 176f) {
            var p = 20f
            while (p <= 100f) {
                cand.add(floatArrayOf(a, p))
                p += 5f
            }
            a += 4f
        }
    }

    private fun score(aim: Float, power: Float): Float {
        val r = world.simulate(me, aim, power, target, 9f, w = simWeapon)
        if (r.hitTank == target.idx) return 0f
        if (r.hitTank == me.idx) return 5000f
        if (!r.landed) return 800f + r.minDist
        val dx = r.x - target.x; val dy = r.y - target.y
        var d = sqrt(dx * dx + dy * dy)
        val sx = r.x - me.x; val sy = r.y - me.y
        if (sx * sx + sy * sy < 60f * 60f) d += 3000f
        return d
    }

    private fun remember(aim: Float, power: Float, s: Float) {
        top.add(floatArrayOf(aim, power, s))
        top.sortBy { it[2] }
        if (top.size > 3) top.removeAt(top.size - 1)
    }

    /** Runs up to [budget] test shots. Returns true once the plan is ready. */
    fun think(budget: Int): Boolean {
        if (plan != null) return true
        var left = budget
        while (left > 0 && idx < cand.size) {
            val c = cand[idx++]
            val s = score(c[0], c[1])
            when (phase) {
                Phase.COARSE -> remember(c[0], c[1], s)
                Phase.FINE -> if (s < top[0][2]) top.add(0, floatArrayOf(c[0], c[1], s))
                Phase.MOTOR -> if (s < motorBest[2]) motorBest = floatArrayOf(c[0], c[1], s)
                Phase.DONE -> {}
            }
            left--
        }
        if (idx < cand.size) return false
        when (phase) {
            Phase.COARSE -> {
                phase = Phase.FINE
                cand.clear()
                idx = 0
                for (t in top.toList()) {
                    var a = t[0] - 3f
                    while (a <= t[0] + 3f) {
                        var p = t[1] - 4f
                        while (p <= t[1] + 4f) {
                            if (p in 5f..100f && a in 0f..180f) cand.add(floatArrayOf(a, p))
                            p += 1f
                        }
                        a += 1f
                    }
                }
                return false
            }
            Phase.FINE -> {
                val best = top.first()
                if (shouldMove(best[2])) {
                    plan = Plan(me.aim, me.power, Weapons.all[0], if (rng.nextBoolean()) 1 else -1, 0.8f + rng.nextFloat() * 0.8f)
                    return true
                }
                val w = chooseWeapon(best[2])
                chosen = w
                if (w.thrust > 0f && w.kind != WeaponKind.HOMING) {
                    // Rockets keep accelerating, so search again with the motor switched on.
                    phase = Phase.MOTOR
                    simWeapon = w
                    cand.clear()
                    idx = 0
                    var a = best[0] - 12f
                    while (a <= best[0] + 12f) {
                        var p = 8f
                        while (p <= 100f) {
                            if (a in 0f..180f) cand.add(floatArrayOf(a, p))
                            p += 3f
                        }
                        a += 1.5f
                    }
                    return false
                }
                plan = finalPlan(best[0], best[1], w)
                return true
            }
            Phase.MOTOR -> {
                val w = chosen!!
                plan = if (motorBest[2] < 200f) finalPlan(motorBest[0], motorBest[1], w)
                else finalPlan(top.first()[0], top.first()[1], Weapons.all[0])
                phase = Phase.DONE
                return true
            }
            Phase.DONE -> return true
        }
    }

    private fun gauss() = rng.nextGaussian().toFloat().coerceIn(-2.5f, 2.5f)

    private fun shouldMove(bestScore: Float) =
        allowMove && bestScore > 160f && me.fuel > 40f && me.tracks > 0f && me.gun > 0f && rng.nextFloat() < 0.6f

    private fun finalPlan(aim0: Float, power0: Float, w: Weapon): Plan {
        val sharpen = Math.pow(0.72, shotsAtTarget.toDouble()).toFloat().coerceAtLeast(0.25f)
        val aimErr = floatArrayOf(7f, 2.8f, 0.9f)[level.coerceIn(0, 2)] * sharpen
        val powErr = floatArrayOf(8f, 3.5f, 1.2f)[level.coerceIn(0, 2)] * sharpen
        val aim = (aim0 + gauss() * aimErr).coerceIn(0f, 180f)
        val power = (power0 + gauss() * powErr).coerceIn(5f, 100f)
        return Plan(aim, power, w, 0, 0f)
    }

    private fun affordable(id: Int, reserve: Int = 150) = Weapons.all[id].price + reserve <= money

    private fun pick(ids: IntArray, chance: Float): Weapon? {
        val pool = ids.filter { affordable(it) }
        if (pool.isEmpty() || rng.nextFloat() > chance) return null
        return Weapons.all[pool[rng.nextInt(pool.size)]]
    }

    private fun chooseWeapon(bestScore: Float): Weapon {
        val w = Weapons.all
        val repair = Weapons.byKind(WeaponKind.REPAIR)
        val shield = Weapons.byKind(WeaponKind.SHIELD)
        val hullFrac = me.hull / me.hullMax
        if (me.gun <= 0f && affordable(repair.id, 0) && rng.nextFloat() < 0.9f) return repair
        if (hullFrac < 0.35f && affordable(repair.id, 0) && rng.nextFloat() < 0.45f) return repair
        if (hullFrac < 0.5f && me.shield <= 0f && affordable(shield.id, 50) && rng.nextFloat() < 0.15f) return shield
        if (level == 0 && rng.nextFloat() < 0.55f) return w[0]
        val samePlanet = target.planet === me.planet
        if (bestScore < 25f) {
            val nuke = Weapons.byKind(WeaponKind.NUKE)
            if (level >= 1 && affordable(nuke.id, 400) && !samePlanet && rng.nextFloat() < 0.3f) return nuke
            return pick(intArrayOf(1, 1, 2, 2, 7, 10, 8, 9, 15, 17, 18, 13, 14, 5), 0.85f) ?: w[0]
        }
        if (bestScore < 90f) {
            return pick(intArrayOf(3, 4, 5, 8, 9, 10, 11, 15, 13, 12), 0.75f) ?: w[0]
        }
        val homing = Weapons.byKind(WeaponKind.HOMING)
        if (affordable(homing.id, 100) && rng.nextFloat() < 0.6f) return homing
        return w[0]
    }
}
