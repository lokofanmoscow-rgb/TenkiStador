package com.tankistador.game

import java.util.Random
import kotlin.math.sqrt

/**
 * Computer opponent. Plans a shot by flying many test shells through the same physics the
 * real shells use, spread over several frames, then adds a level-dependent aiming error.
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

    private val cand = ArrayList<FloatArray>()
    private var idx = 0
    private var refined = false
    private val top = ArrayList<FloatArray>() // aim, power, score
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
        val r = world.simulate(me, aim, power, target, 9f)
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
            if (!refined) remember(c[0], c[1], score(c[0], c[1]))
            else {
                val s = score(c[0], c[1])
                if (s < top[0][2]) top.add(0, floatArrayOf(c[0], c[1], s))
            }
            left--
        }
        if (idx < cand.size) return false
        if (!refined) {
            refined = true
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
        plan = decide()
        return true
    }

    private fun gauss() = rng.nextGaussian().toFloat().coerceIn(-2.5f, 2.5f)

    private fun decide(): Plan {
        val best = top.firstOrNull() ?: floatArrayOf(me.aim, me.power, 9999f)
        val bestScore = best[2]
        if (allowMove && bestScore > 160f && me.fuel > 40f && me.tracks > 0f && rng.nextFloat() < 0.6f) {
            return Plan(me.aim, me.power, Weapons.all[0], if (rng.nextBoolean()) 1 else -1, 0.8f + rng.nextFloat() * 0.8f)
        }
        val sharpen = Math.pow(0.72, shotsAtTarget.toDouble()).toFloat().coerceAtLeast(0.25f)
        val aimErr = floatArrayOf(7f, 2.8f, 0.9f)[level.coerceIn(0, 2)] * sharpen
        val powErr = floatArrayOf(8f, 3.5f, 1.2f)[level.coerceIn(0, 2)] * sharpen
        val aim = (best[0] + gauss() * aimErr).coerceIn(0f, 180f)
        val power = (best[1] + gauss() * powErr).coerceIn(5f, 100f)
        return Plan(aim, power, chooseWeapon(bestScore), 0, 0f)
    }

    private fun affordable(id: Int, reserve: Int = 150) = Weapons.all[id].price + reserve <= money

    private fun chooseWeapon(bestScore: Float): Weapon {
        val w = Weapons.all
        val hullFrac = me.hull / me.hullMax
        if (hullFrac < 0.35f && affordable(11, 0) && rng.nextFloat() < 0.45f) return w[11]
        if (hullFrac < 0.5f && me.shield <= 0f && affordable(12, 50) && rng.nextFloat() < 0.15f) return w[12]
        if (level == 0 && rng.nextFloat() < 0.6f) return w[0]
        if (bestScore < 25f) {
            if (level >= 1 && affordable(9, 400) && rng.nextFloat() < 0.35f) return w[9]
            val pool = intArrayOf(1, 1, 2, 3, 4, 6, 7, 5).filter { affordable(it) }
            if (pool.isNotEmpty() && rng.nextFloat() < 0.8f) return w[pool[rng.nextInt(pool.size)]]
            return w[0]
        }
        if (bestScore < 90f) {
            val pool = intArrayOf(2, 4, 6, 1, 5).filter { affordable(it) }
            if (pool.isNotEmpty() && rng.nextFloat() < 0.7f) return w[pool[rng.nextInt(pool.size)]]
            return w[0]
        }
        if (affordable(8, 100) && rng.nextFloat() < 0.6f) return w[8]
        return w[0]
    }
}
