package com.tankistador.game

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val DEG = (PI / 180.0).toFloat()
const val TWO_PI = (2 * PI).toFloat()

fun clamp(v: Float, a: Float, b: Float) = if (v < a) a else if (v > b) b else v
fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun wrapAngle(a: Float): Float {
    var r = a % TWO_PI
    if (r > PI) r -= TWO_PI
    if (r < -PI) r += TWO_PI
    return r
}

/** A round, deformable planet. The surface is a radius sampled at [N] evenly spaced angles. */
class Planet(val id: Int, val x: Float, val y: Float, val baseR: Float, val density: Float, val style: Int, val texSeed: Int) {
    companion object {
        const val N = 256
    }

    val r = FloatArray(N) { baseR }
    val minR = baseR * 0.3f
    val mass = density * baseR * baseR * baseR
    var version = 0
    val scorches = ArrayList<FloatArray>()

    fun surfaceAt(angle: Float): Float {
        var a = angle / TWO_PI * N
        a %= N.toFloat()
        if (a < 0) a += N
        val i0 = floor(a).toInt() % N
        val t = a - floor(a)
        return r[i0] * (1 - t) + r[(i0 + 1) % N] * t
    }

    fun angleOf(px: Float, py: Float) = atan2(py - y, px - x)

    fun contains(px: Float, py: Float): Boolean {
        val dx = px - x
        val dy = py - y
        val d2 = dx * dx + dy * dy
        if (d2 > baseR * baseR * 1.0001f) return false
        return sqrt(d2) < surfaceAt(atan2(dy, dx))
    }

    fun surfacePoint(angle: Float, lift: Float = 0f, out: FloatArray) {
        val rr = surfaceAt(angle) + lift
        out[0] = x + cos(angle) * rr
        out[1] = y + sin(angle) * rr
    }

    /** Removes the part of the planet covered by a circle. Returns true if the surface changed. */
    fun carve(ex: Float, ey: Float, er: Float): Boolean {
        val dx = ex - x
        val dy = ey - y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > baseR + er) return false
        var changed = false
        val c = dx * dx + dy * dy - er * er
        for (i in 0 until N) {
            val phi = i * TWO_PI / N
            val ux = cos(phi)
            val uy = sin(phi)
            val b = ux * dx + uy * dy
            val disc = b * b - c
            if (disc < 0) continue
            val s = sqrt(disc)
            val t1 = b - s
            val t2 = b + s
            if (t2 <= 0) continue
            if (r[i] > t1 && r[i] <= t2) {
                val nr = max(t1, minR)
                if (nr < r[i]) {
                    r[i] = nr
                    changed = true
                }
            }
        }
        if (changed) {
            version++
            if (scorches.size > 60) scorches.removeAt(0)
            scorches.add(floatArrayOf(ex, ey, er * 1.25f))
        }
        return changed
    }
}

class Tank(val idx: Int, var planet: Planet, var ang: Float, val model: Int, val color: Int) {
    var aim = 60f
    var power = 55f
    var gun = 1f; var gunMax = 1f
    var hull = 1f; var hullMax = 1f
    var antenna = 1f; var antennaMax = 1f
    var tracks = 1f; var tracksMax = 1f
    var fuel = 0f; var fuelMax = 1f
    var shield = 0f
    var alive = true

    var x = 0f
    var y = 0f
    var normal = 0f
    var drawLift = 0f
    var hitFlash = 0f
    var lastPart: Part? = null

    val look get() = Tanks.all[model].look
    val sc get() = look.scale * SIZE
    val radius get() = 11f * sc

    companion object {
        /** Tanks are drawn and collide larger than their art units so they read well on a phone. */
        const val SIZE = 1.45f
    }

    private val tmp = FloatArray(2)

    /** Snaps the tank onto the current surface. Returns the height it fell. */
    fun place(): Float {
        val oldR = sqrt((x - planet.x) * (x - planet.x) + (y - planet.y) * (y - planet.y))
        val d = 7f * DEG
        planet.surfacePoint(ang - d, 0f, tmp)
        val ax = tmp[0]; val ay = tmp[1]
        planet.surfacePoint(ang + d, 0f, tmp)
        val bx = tmp[0]; val by = tmp[1]
        var nx = by - ay
        var ny = -(bx - ax)
        val nl = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-4f)
        nx /= nl; ny /= nl
        val rx = cos(ang); val ry = sin(ang)
        nx = nx * 0.5f + rx * 0.5f
        ny = ny * 0.5f + ry * 0.5f
        normal = atan2(ny, nx)
        val sr = planet.surfaceAt(ang)
        val lift = 6.5f * sc
        x = planet.x + rx * sr + cos(normal) * lift
        y = planet.y + ry * sr + sin(normal) * lift
        val newR = sr + lift
        return max(0f, oldR - newR)
    }

    /** World-space angle (radians) the barrel points at. */
    fun aimWorld() = normal + (90f - aim) * DEG

    fun muzzle(out: FloatArray) {
        val a = aimWorld()
        val len = (look.barrelLen + 5f) * sc
        out[0] = x + cos(normal) * 3f * sc + cos(a) * len
        out[1] = y + sin(normal) * 3f * sc + sin(a) * len
    }

    fun maxSpeed() = World.MAX_SPEED * (0.55f + 0.45f * (gun / gunMax))
}

class Projectile(var x: Float, var y: Float, var vx: Float, var vy: Float, val w: Weapon, val owner: Int, val sub: Boolean = false) {
    var age = 0f
    var bounces = 0
    var drill = -1f
    var alive = true
    val trail = FloatArray(TRAIL * 2)
    var trailLen = 0
    var trailHead = 0
    private var trailTimer = 0f

    fun pushTrail(dt: Float) {
        trailTimer += dt
        if (trailTimer < 0.025f) return
        trailTimer = 0f
        trail[trailHead * 2] = x
        trail[trailHead * 2 + 1] = y
        trailHead = (trailHead + 1) % TRAIL
        if (trailLen < TRAIL) trailLen++
    }

    companion object {
        const val TRAIL = 40
    }
}

class Mole(val planet: Planet, var ang: Float, val dir: Int, val owner: Int, val w: Weapon) {
    var t = 0f
    var alive = true
    var x = 0f
    var y = 0f
}

class Fire(val planet: Planet, val ang: Float, val half: Float, var turns: Int, val owner: Int, val dmg: Float)

class Building(val planet: Planet, val ang: Float, val style: Int) {
    var alive = true
}

class Colonist(val planet: Planet, var ang: Float, var dir: Int, val hue: Int) {
    var alive = true
    var phase = 0f
    var wait = 0f
}

class Particle {
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
    var life = 0f; var max = 1f
    var size = 1f
    var color = 0
    var type = 0 // 0 spark, 1 smoke, 2 debris (gravity), 3 flash, 4 flame, 5 ring
    var grow = 0f
}

class FloatText(val x: Float, var y: Float, val text: String, val color: Int) {
    var life = 1.6f
}

sealed class Ev {
    class Shot(val owner: Int, val w: Weapon) : Ev()
    class Explosion(val x: Float, val y: Float, val radius: Float) : Ev()
    class Hit(val tank: Int, val dmg: Float, val by: Int, val direct: Boolean, val part: Part?) : Ev()
    class Killed(val tank: Int, val by: Int) : Ev()
    class BuildingLost(val by: Int) : Ev()
    class ColonistLost(val by: Int) : Ev()
    class Bounce(val x: Float, val y: Float) : Ev()
    class Teleport(val tank: Int) : Ev()
    class Repaired(val tank: Int) : Ev()
    class Shielded(val tank: Int) : Ev()
    class Split(val x: Float, val y: Float) : Ev()
    class Lost(val owner: Int) : Ev()
    class FireStart(val owner: Int) : Ev()
    class Burn(val tank: Int, val dmg: Float) : Ev()
}

class SimResult {
    var hitTank = -1
    var x = 0f
    var y = 0f
    var minDist = Float.MAX_VALUE
    var landed = false
    var time = 0f
    var planet = -1
}

class World(val seed: Long) {
    companion object {
        const val G = 4f
        const val MAX_SPEED = 450f
        const val DT = 1f / 120f
        const val MAX_FLIGHT = 14f
        const val FIELD_W = 1700f
        const val FIELD_H = 720f
        const val LOST_DIST = 2600f
    }

    val rng = Random(seed)
    val planets = ArrayList<Planet>()
    val tanks = ArrayList<Tank>()
    val projectiles = ArrayList<Projectile>()
    val moles = ArrayList<Mole>()
    val fires = ArrayList<Fire>()
    val buildings = ArrayList<Building>()
    val colonists = ArrayList<Colonist>()
    val particles = ArrayList<Particle>()
    val texts = ArrayList<FloatText>()
    val events = ArrayList<Ev>()
    private val pending = ArrayList<FloatArray>() // delayed tank wreck explosions: x, y, owner
    private var accumulator = 0f
    var time = 0f
    var nebulaSeed = 0
    private val tmp = FloatArray(2)

    fun rf() = rng.nextFloat()
    fun rr(a: Float, b: Float) = a + (b - a) * rng.nextFloat()

    // ---------------------------------------------------------------- generation

    fun generate(players: List<Player>) {
        nebulaSeed = rng.nextInt()
        val count = 3 + rng.nextInt(2)
        var attempts = 0
        while (planets.size < count && attempts < 4000) {
            attempts++
            val r = rr(58f, 120f)
            val px = rr(-FIELD_W / 2 + r + 20, FIELD_W / 2 - r - 20)
            val py = rr(-FIELD_H / 2 + r + 20, FIELD_H / 2 - r - 20)
            val ok = planets.all { p ->
                val dx = p.x - px; val dy = p.y - py
                sqrt(dx * dx + dy * dy) > p.baseR + r + 120f
            }
            if (ok) planets.add(Planet(planets.size, px, py, r, rr(0.85f, 1.15f), rng.nextInt(8), rng.nextInt()))
            if (attempts % 800 == 0 && planets.size < 2) planets.clear()
        }
        if (planets.size < 2) {
            planets.clear()
            planets.add(Planet(0, -480f, 60f, 100f, 1f, 0, 1))
            planets.add(Planet(1, 0f, -200f, 80f, 1f, 2, 2))
            planets.add(Planet(2, 480f, 80f, 110f, 1f, 3, 3))
        }
        val sorted = planets.sortedBy { it.x }
        val homes = listOf(sorted.first(), sorted.last())
        for ((i, pl) in players.withIndex()) {
            val home = homes[i % homes.size]
            val other = homes[(i + 1) % homes.size]
            val toward = atan2(other.y - home.y, other.x - home.x)
            val ang = toward + rr(-65f, 65f) * DEG
            val t = Tank(i, home, ang, pl.model, pl.color)
            val m = pl.tank
            t.gunMax = m.gun.toFloat(); t.hullMax = m.hull.toFloat(); t.antennaMax = m.antenna.toFloat()
            t.tracksMax = m.tracks.toFloat(); t.fuelMax = m.fuel.toFloat()
            t.gun = max(0f, pl.gun); t.hull = max(1f, pl.hull); t.antenna = max(0f, pl.antenna)
            t.tracks = max(0f, pl.tracks); t.fuel = max(0f, pl.fuel)
            t.aim = if (i == 0) 60f else 120f
            t.power = 55f
            t.place()
            tanks.add(t)
        }
        for (p in planets) {
            val houses = rng.nextInt(3) + if (p.baseR > 90) 1 else 0
            repeat(houses) {
                val a = rr(-PI.toFloat(), PI.toFloat())
                if (tanks.none { it.planet === p && abs(wrapAngle(it.ang - a)) < 0.45f } &&
                    buildings.none { it.planet === p && abs(wrapAngle(it.ang - a)) < 0.25f }
                ) {
                    buildings.add(Building(p, a, rng.nextInt(3)))
                    repeat(1 + rng.nextInt(2)) {
                        colonists.add(Colonist(p, a + rr(-0.15f, 0.15f), if (rng.nextBoolean()) 1 else -1, rng.nextInt(4)))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- physics helpers

    fun gravity(x: Float, y: Float, out: FloatArray) {
        var ax = 0f
        var ay = 0f
        for (p in planets) {
            val dx = p.x - x
            val dy = p.y - y
            var d2 = dx * dx + dy * dy
            val soft = p.baseR * p.baseR * 0.25f
            if (d2 < soft) d2 = soft
            val d = sqrt(d2)
            val f = G * p.mass / (d2 * d)
            ax += dx * f
            ay += dy * f
        }
        out[0] = ax
        out[1] = ay
    }

    fun planetAt(x: Float, y: Float): Planet? {
        for (p in planets) if (p.contains(x, y)) return p
        return null
    }

    fun launchVelocity(t: Tank, aim: Float, power: Float, out: FloatArray) {
        val a = t.normal + (90f - aim) * DEG
        val s = clamp(power, 0f, 100f) / 100f * t.maxSpeed()
        out[0] = cos(a) * s
        out[1] = sin(a) * s
    }

    fun muzzleFor(t: Tank, aim: Float, out: FloatArray) {
        val a = t.normal + (90f - aim) * DEG
        val len = (t.look.barrelLen + 5f) * t.sc
        out[0] = t.x + cos(t.normal) * 3f * t.sc + cos(a) * len
        out[1] = t.y + sin(t.normal) * 3f * t.sc + sin(a) * len
    }

    /**
     * Flies a plain shell without side effects. Used by the computer player and the aiming hint.
     * [path] receives (x, y) pairs every few steps when not null.
     */
    fun simulate(t: Tank, aim: Float, power: Float, target: Tank?, maxTime: Float, path: FloatArray? = null, pathCount: IntArray? = null): SimResult {
        val res = SimResult()
        muzzleFor(t, aim, tmp)
        var x = tmp[0]
        var y = tmp[1]
        launchVelocity(t, aim, power, tmp)
        var vx = tmp[0]
        var vy = tmp[1]
        val g = FloatArray(2)
        var time = 0f
        var step = 0
        var pc = 0
        while (time < maxTime) {
            gravity(x, y, g)
            vx += g[0] * DT
            vy += g[1] * DT
            x += vx * DT
            y += vy * DT
            time += DT
            step++
            if (path != null && step % 4 == 0 && pc * 2 + 1 < path.size) {
                path[pc * 2] = x; path[pc * 2 + 1] = y; pc++
            }
            if (target != null) {
                val dx = target.x - x; val dy = target.y - y
                val d = sqrt(dx * dx + dy * dy)
                if (d < res.minDist) res.minDist = d
            }
            var hit = false
            for (tk in tanks) {
                if (!tk.alive) continue
                if (tk === t && time < 0.3f) continue
                val dx = tk.x - x; val dy = tk.y - y
                if (dx * dx + dy * dy < tk.radius * tk.radius) {
                    res.hitTank = tk.idx
                    hit = true
                    break
                }
            }
            if (!hit) {
                val p = planetAt(x, y)
                if (p != null) {
                    res.planet = p.id
                    hit = true
                }
            }
            if (hit) {
                res.landed = true
                break
            }
            if (x * x + y * y > LOST_DIST * LOST_DIST) break
        }
        res.x = x; res.y = y; res.time = time
        pathCount?.set(0, pc)
        return res
    }

    // ---------------------------------------------------------------- actions

    fun fire(t: Tank, w: Weapon) {
        events.add(Ev.Shot(t.idx, w))
        when (w.kind) {
            WeaponKind.REPAIR -> {
                t.hull = min(t.hullMax, t.hull + 35f)
                texts.add(FloatText(t.x, t.y - 20, "+35", 0xFF69F0AE.toInt()))
                sparkle(t.x, t.y, 0xFF69F0AE.toInt())
                events.add(Ev.Repaired(t.idx))
                return
            }
            WeaponKind.SHIELD -> {
                t.shield = 40f
                sparkle(t.x, t.y, 0xFF40C4FF.toInt())
                events.add(Ev.Shielded(t.idx))
                return
            }
            else -> {}
        }
        // A damaged gun shakes the shot a little.
        val wobble = (1f - t.gun / t.gunMax).coerceAtLeast(0f) * 5f
        val aims = if (w.kind == WeaponKind.TRIPLE) floatArrayOf(t.aim - 4f, t.aim, t.aim + 4f) else floatArrayOf(t.aim)
        for (a0 in aims) {
            val a = a0 + rr(-wobble, wobble)
            muzzleFor(t, a, tmp)
            val mx = tmp[0]; val my = tmp[1]
            launchVelocity(t, a, t.power, tmp)
            projectiles.add(Projectile(mx, my, tmp[0], tmp[1], w, t.idx))
        }
        muzzleFor(t, t.aim, tmp)
        repeat(10) {
            val p = spawn(tmp[0], tmp[1], 1)
            val a = t.aimWorld() + rr(-0.5f, 0.5f)
            val s = rr(20f, 70f)
            p.vx = cos(a) * s; p.vy = sin(a) * s
            p.max = rr(0.4f, 0.9f); p.life = p.max; p.size = rr(3f, 6f); p.color = 0xAAD0D0D0.toInt(); p.grow = 8f
        }
        val fl = spawn(tmp[0], tmp[1], 3)
        fl.max = 0.12f; fl.life = fl.max; fl.size = 9f; fl.color = 0xFFFFF59D.toInt()
    }

    /** Drives the tank along the surface. Returns false when it cannot move. */
    fun moveTank(t: Tank, dir: Int, dt: Float): Boolean {
        if (!t.alive || t.fuel <= 0f || t.tracks <= 0f) return false
        val speed = 34f * (0.35f + 0.65f * t.tracks / t.tracksMax)
        val sr = t.planet.surfaceAt(t.ang)
        val nAng = t.ang + dir * speed * dt / sr
        for (o in tanks) {
            if (o === t || !o.alive || o.planet !== t.planet) continue
            val gap = abs(wrapAngle(o.ang - nAng)) * sr
            val gapNow = abs(wrapAngle(o.ang - t.ang)) * sr
            if (gap < 34f && gap < gapNow) return false
        }
        t.ang = wrapAngle(nAng)
        t.fuel = max(0f, t.fuel - speed * dt * 0.5f)
        t.place()
        if (rf() < 0.5f) {
            val p = spawn(t.x - cos(t.normal) * 6, t.y - sin(t.normal) * 6, 1)
            p.vx = -dir * cos(t.normal + PI.toFloat() / 2) * 10f + rr(-5f, 5f)
            p.vy = -dir * sin(t.normal + PI.toFloat() / 2) * 10f + rr(-5f, 5f)
            p.max = 0.6f; p.life = p.max; p.size = 2.5f; p.grow = 5f; p.color = 0x88B0A090.toInt()
        }
        return true
    }

    fun burnFires() {
        val it = fires.iterator()
        while (it.hasNext()) {
            val f = it.next()
            for (t in tanks) {
                if (!t.alive || t.planet !== f.planet) continue
                if (abs(wrapAngle(t.ang - f.ang)) < f.half + 0.06f) {
                    events.add(Ev.Burn(t.idx, f.dmg))
                    damage(t, f.dmg, f.owner, false, t.x, t.y - 1f)
                }
            }
            for (c in colonists) {
                if (c.alive && c.planet === f.planet && abs(wrapAngle(c.ang - f.ang)) < f.half) {
                    c.alive = false
                    events.add(Ev.ColonistLost(f.owner))
                }
            }
            f.turns--
            if (f.turns <= 0) it.remove()
        }
    }

    // ---------------------------------------------------------------- simulation

    val busy get() = projectiles.isNotEmpty() || moles.isNotEmpty() || pending.isNotEmpty() || tanks.any { it.drawLift > 0.5f }

    fun update(frameDt: Float) {
        accumulator += min(frameDt, 0.1f)
        while (accumulator >= DT) {
            accumulator -= DT
            step(DT)
        }
        updateVisuals(frameDt)
    }

    private val g = FloatArray(2)

    private fun step(dt: Float) {
        time += dt
        for (i in projectiles.indices.reversed()) {
            val p = projectiles[i]
            stepProjectile(p, dt)
        }
        projectiles.removeAll { !it.alive }
        for (m in moles) stepMole(m, dt)
        moles.removeAll { !it.alive }
        if (pending.isNotEmpty()) {
            val e = pending.removeAt(0)
            explode(e[0], e[1], 44f, 26f, e[2].toInt(), null, true)
        }
        for (c in colonists) stepColonist(c, dt)
    }

    private fun stepProjectile(p: Projectile, dt: Float) {
        p.age += dt
        gravity(p.x, p.y, g)
        if (p.drill >= 0f) {
            g[0] *= 0.2f; g[1] *= 0.2f
        }
        p.vx += g[0] * dt
        p.vy += g[1] * dt
        if (p.w.kind == WeaponKind.HOMING && p.age > 1f) steerHoming(p, dt)
        p.x += p.vx * dt
        p.y += p.vy * dt
        p.pushTrail(dt)

        if (p.w.kind == WeaponKind.CLUSTER && !p.sub && p.age >= 1.5f) {
            p.alive = false
            events.add(Ev.Split(p.x, p.y))
            for (k in 0 until 6) {
                val q = Projectile(p.x, p.y, p.vx + rr(-70f, 70f), p.vy + rr(-70f, 70f), p.w, p.owner, true)
                q.age = 1f
                projectiles.add(q)
            }
            burst(p.x, p.y, 8, 0xFFAED581.toInt(), 60f)
            return
        }

        if (p.drill >= 0f) {
            p.drill += dt
            p.vx *= 0.97f; p.vy *= 0.97f
            if (rf() < 0.6f) debris(p.x, p.y, 1, planetAt(p.x, p.y))
            val inside = planetAt(p.x, p.y) != null
            if (p.drill > 0.4f || (!inside && p.drill > 0.05f)) {
                p.alive = false
                explode(p.x, p.y, p.w.radius, p.w.damage, p.owner, null, true)
            }
            return
        }

        for (t in tanks) {
            if (!t.alive) continue
            if (t.idx == p.owner && p.age < 0.3f) continue
            val dx = t.x - p.x; val dy = t.y - p.y
            if (dx * dx + dy * dy < t.radius * t.radius) {
                impact(p, t.planet, t)
                return
            }
        }
        val pl = planetAt(p.x, p.y)
        if (pl != null) {
            impact(p, pl, null)
            return
        }
        if (p.x * p.x + p.y * p.y > LOST_DIST * LOST_DIST) {
            p.alive = false
            if (!p.sub) events.add(Ev.Lost(p.owner))
            return
        }
        if (p.age > MAX_FLIGHT) {
            p.alive = false
            explode(p.x, p.y, p.w.radius * 0.6f, p.w.damage * 0.5f, p.owner, null, true)
        }
    }

    private fun steerHoming(p: Projectile, dt: Float) {
        var best: Tank? = null
        var bd = Float.MAX_VALUE
        for (t in tanks) {
            if (!t.alive || t.idx == p.owner) continue
            val d = (t.x - p.x) * (t.x - p.x) + (t.y - p.y) * (t.y - p.y)
            if (d < bd) { bd = d; best = t }
        }
        val t = best ?: return
        val want = atan2(t.y - p.y, t.x - p.x)
        val cur = atan2(p.vy, p.vx)
        val diff = wrapAngle(want - cur)
        val turn = clamp(diff, -2.2f * dt, 2.2f * dt)
        val sp = min(sqrt(p.vx * p.vx + p.vy * p.vy), 330f)
        val na = cur + turn
        p.vx = cos(na) * sp
        p.vy = sin(na) * sp
        if (rf() < 0.5f) {
            val s = spawn(p.x, p.y, 4)
            s.max = 0.3f; s.life = s.max; s.size = 3f; s.color = 0xFF4DD0E1.toInt()
        }
    }

    private fun impact(p: Projectile, pl: Planet, tank: Tank?) {
        val w = p.w
        when (w.kind) {
            WeaponKind.BOUNCER -> if (tank == null && p.bounces < 4) {
                val nx0 = p.x - pl.x; val ny0 = p.y - pl.y
                val a = atan2(ny0, nx0)
                // Surface normal from neighbouring samples.
                pl.surfacePoint(a - 0.05f, 0f, tmp)
                val ax = tmp[0]; val ay = tmp[1]
                pl.surfacePoint(a + 0.05f, 0f, tmp)
                var nx = tmp[1] - ay
                var ny = -(tmp[0] - ax)
                val nl = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-4f)
                nx /= nl; ny /= nl
                val vn = p.vx * nx + p.vy * ny
                if (vn < 0) {
                    p.vx = (p.vx - 2 * vn * nx) * 0.72f
                    p.vy = (p.vy - 2 * vn * ny) * 0.72f
                }
                pl.surfacePoint(a, 2f, tmp)
                p.x = tmp[0]; p.y = tmp[1]
                p.bounces++
                events.add(Ev.Bounce(p.x, p.y))
                burst(p.x, p.y, 5, 0xFF81D4FA.toInt(), 40f)
                return
            }
            WeaponKind.DRILL -> if (tank == null) {
                p.drill = 0f
                p.vx *= 0.55f; p.vy *= 0.55f
                debris(p.x, p.y, 8, pl)
                return
            }
            WeaponKind.MOLES -> if (tank == null) {
                p.alive = false
                val a = pl.angleOf(p.x, p.y)
                for (d in intArrayOf(-1, 1)) {
                    val m = Mole(pl, a, d, p.owner, w)
                    moles.add(m)
                }
                debris(p.x, p.y, 10, pl)
                events.add(Ev.Bounce(p.x, p.y))
                return
            }
            WeaponKind.NAPALM -> {
                p.alive = false
                val a = pl.angleOf(p.x, p.y)
                fires.add(Fire(pl, a, 42f / pl.surfaceAt(a), 3, p.owner, 6f))
                events.add(Ev.FireStart(p.owner))
                explode(p.x, p.y, w.radius, w.damage, p.owner, tank, true)
                return
            }
            WeaponKind.TELEPORT -> {
                p.alive = false
                val owner = tanks.getOrNull(p.owner)
                if (owner != null && owner.alive) {
                    sparkle(owner.x, owner.y, 0xFFCE93D8.toInt())
                    owner.planet = pl
                    owner.ang = pl.angleOf(p.x, p.y) + if (tank != null && tank !== owner) 0.35f else 0f
                    owner.place()
                    owner.drawLift = 0f
                    sparkle(owner.x, owner.y, 0xFFCE93D8.toInt())
                    events.add(Ev.Teleport(owner.idx))
                }
                return
            }
            else -> {}
        }
        p.alive = false
        explode(p.x, p.y, w.radius, w.damage, p.owner, tank, true)
    }

    private fun stepMole(m: Mole, dt: Float) {
        m.t += dt
        val sr = m.planet.surfaceAt(m.ang)
        m.ang = wrapAngle(m.ang + m.dir * 40f * dt / sr)
        m.planet.surfacePoint(m.ang, 3f, tmp)
        m.x = tmp[0]; m.y = tmp[1]
        if (rf() < 0.3f) debris(m.x, m.y, 1, m.planet)
        var boom = m.t > 4f
        for (t in tanks) {
            if (!t.alive || t.planet !== m.planet) continue
            if (t.idx == m.owner && m.t < 0.6f) continue
            val dx = t.x - m.x; val dy = t.y - m.y
            if (dx * dx + dy * dy < (t.radius + 6f) * (t.radius + 6f)) boom = true
        }
        if (boom) {
            m.alive = false
            explode(m.x, m.y, m.w.radius, m.w.damage, m.owner, null, true)
        }
    }

    private fun stepColonist(c: Colonist, dt: Float) {
        if (!c.alive) return
        c.phase += dt
        if (c.wait > 0) {
            c.wait -= dt
            return
        }
        val sr = c.planet.surfaceAt(c.ang)
        c.ang = wrapAngle(c.ang + c.dir * 5f * dt / sr)
        if (rf() < dt * 0.25f) c.dir = -c.dir
        if (rf() < dt * 0.2f) c.wait = rr(0.5f, 2f)
        for (t in tanks) {
            if (t.alive && t.planet === c.planet && abs(wrapAngle(t.ang - c.ang)) * sr < 20f) {
                c.dir = if (wrapAngle(c.ang - t.ang) > 0) 1 else -1
            }
        }
    }

    // ---------------------------------------------------------------- damage

    fun explode(x: Float, y: Float, radius: Float, damage: Float, owner: Int, direct: Tank?, carve: Boolean) {
        events.add(Ev.Explosion(x, y, radius))
        if (carve && radius > 0) {
            for (p in planets) p.carve(x, y, radius * 0.8f)
        }
        for (t in tanks) {
            if (!t.alive) continue
            val dx = t.x - x; val dy = t.y - y
            val d = sqrt(dx * dx + dy * dy)
            val reach = radius + t.radius
            if (t === direct) {
                damage(t, damage * 1.15f, owner, true, x, y)
            } else if (d < reach && damage > 0) {
                val f = 1f - d / reach
                damage(t, damage * (0.25f + 0.75f * f), owner, false, x, y)
            }
        }
        for (b in buildings) {
            if (!b.alive) continue
            b.planet.surfacePoint(b.ang, 4f, tmp)
            val dx = tmp[0] - x; val dy = tmp[1] - y
            if (dx * dx + dy * dy < (radius + 6f) * (radius + 6f)) {
                b.alive = false
                events.add(Ev.BuildingLost(owner))
                debris(tmp[0], tmp[1], 10, null, 0xFFBDBDBD.toInt())
            }
        }
        for (c in colonists) {
            if (!c.alive) continue
            c.planet.surfacePoint(c.ang, 2f, tmp)
            val dx = tmp[0] - x; val dy = tmp[1] - y
            if (dx * dx + dy * dy < (radius + 4f) * (radius + 4f)) {
                c.alive = false
                events.add(Ev.ColonistLost(owner))
            }
        }
        // Buildings and colonists whose ground vanished are lost too.
        for (b in buildings) if (b.alive && b.planet.surfaceAt(b.ang) < b.planet.baseR * 0.55f) b.alive = false
        for (t in tanks) {
            if (!t.alive) continue
            val oldX = t.x; val oldY = t.y
            val fall = t.place()
            if (fall > 1f) {
                // Animate the drop instead of teleporting the tank.
                t.drawLift += fall
                if (fall > 22f) damage(t, (fall - 22f) * 0.3f, owner, false, oldX, oldY)
            }
        }
        explosionFx(x, y, radius)
    }

    fun damage(t: Tank, amount: Float, by: Int, direct: Boolean, fromX: Float, fromY: Float) {
        if (!t.alive || amount <= 0f) return
        var dmg = amount
        if (t.shield > 0f) {
            val ab = min(t.shield, dmg)
            t.shield -= ab
            dmg -= ab
            sparkle(t.x, t.y, 0xFF40C4FF.toInt())
        }
        if (dmg <= 0.01f) return
        t.hull = max(0f, t.hull - dmg)
        t.hitFlash = 0.35f
        val above = (fromX - t.x) * cos(t.normal) + (fromY - t.y) * sin(t.normal) > 2f
        val part = if (above) (if (rf() < 0.5f) Part.GUN else Part.ANTENNA) else Part.TRACKS
        val pd = dmg * 0.6f
        when (part) {
            Part.GUN -> t.gun = max(0f, t.gun - pd)
            Part.ANTENNA -> t.antenna = max(0f, t.antenna - pd)
            Part.TRACKS -> t.tracks = max(0f, t.tracks - pd)
            Part.HULL -> {}
        }
        if (direct && rf() < 0.5f) t.fuel = max(0f, t.fuel - dmg)
        t.lastPart = part
        texts.add(FloatText(t.x, t.y - 18f, "-" + dmg.toInt().coerceAtLeast(1), if (direct) 0xFFFFEB3B.toInt() else 0xFFFF8A80.toInt()))
        events.add(Ev.Hit(t.idx, dmg, by, direct, part))
        if (t.hull <= 0f) {
            t.alive = false
            events.add(Ev.Killed(t.idx, by))
            pending.add(floatArrayOf(t.x, t.y, by.toFloat()))
            debris(t.x, t.y, 24, null, t.color)
        }
    }

    // ---------------------------------------------------------------- visuals

    private fun spawn(x: Float, y: Float, type: Int): Particle {
        val p = Particle()
        p.x = x; p.y = y; p.type = type
        if (particles.size < 900) particles.add(p)
        return p
    }

    fun burst(x: Float, y: Float, n: Int, color: Int, speed: Float) {
        repeat(n) {
            val p = spawn(x, y, 0)
            val a = rr(0f, TWO_PI)
            val s = rr(speed * 0.3f, speed)
            p.vx = cos(a) * s; p.vy = sin(a) * s
            p.max = rr(0.25f, 0.6f); p.life = p.max; p.size = rr(1.5f, 3f); p.color = color
        }
    }

    fun sparkle(x: Float, y: Float, color: Int) {
        repeat(18) {
            val p = spawn(x + rr(-10f, 10f), y + rr(-10f, 10f), 4)
            p.vx = rr(-15f, 15f); p.vy = rr(-40f, -5f)
            p.max = rr(0.5f, 1.1f); p.life = p.max; p.size = rr(2f, 3.5f); p.color = color
        }
        val ring = spawn(x, y, 5)
        ring.max = 0.5f; ring.life = ring.max; ring.size = 6f; ring.grow = 50f; ring.color = color
    }

    fun debris(x: Float, y: Float, n: Int, planet: Planet?, color: Int = 0) {
        val col = if (color != 0) color else when (planet?.style ?: 0) {
            0 -> 0xFF9E9E9E.toInt(); 1 -> 0xFFDDE7EE.toInt(); 2 -> 0xFFD2A46A.toInt(); 3 -> 0xFFB5523E.toInt()
            4 -> 0xFF6D8F4A.toInt(); 5 -> 0xFFE09A58.toInt(); 6 -> 0xFF8E6BBF.toInt(); else -> 0xFF5D4037.toInt()
        }
        repeat(n) {
            val p = spawn(x, y, 2)
            val a = rr(0f, TWO_PI)
            val s = rr(30f, 140f)
            p.vx = cos(a) * s; p.vy = sin(a) * s
            p.max = rr(0.8f, 1.8f); p.life = p.max; p.size = rr(1.5f, 3.5f); p.color = col
        }
    }

    private fun explosionFx(x: Float, y: Float, radius: Float) {
        if (radius <= 0f) return
        val fl = spawn(x, y, 3)
        fl.max = 0.35f; fl.life = fl.max; fl.size = radius * 1.1f; fl.color = 0xFFFFF3C4.toInt()
        val ring = spawn(x, y, 5)
        ring.max = 0.45f; ring.life = ring.max; ring.size = radius * 0.4f; ring.grow = radius * 2.6f; ring.color = 0xFFFFCC80.toInt()
        val n = (radius * 0.9f).toInt().coerceIn(8, 70)
        repeat(n) {
            val p = spawn(x, y, 0)
            val a = rr(0f, TWO_PI)
            val s = rr(radius * 0.8f, radius * 3.2f)
            p.vx = cos(a) * s; p.vy = sin(a) * s
            p.max = rr(0.3f, 0.8f); p.life = p.max; p.size = rr(1.5f, 3.2f)
            p.color = if (rf() < 0.5f) 0xFFFFD54F.toInt() else 0xFFFF7043.toInt()
        }
        repeat(n / 2) {
            val p = spawn(x + rr(-radius, radius) * 0.4f, y + rr(-radius, radius) * 0.4f, 1)
            p.vx = rr(-15f, 15f); p.vy = rr(-15f, 15f)
            p.max = rr(0.8f, 1.8f); p.life = p.max; p.size = rr(radius * 0.15f, radius * 0.35f) + 2f; p.grow = radius * 0.5f
            p.color = 0x99505050.toInt()
        }
        val pl = planets.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) - it.baseR * it.baseR }
        debris(x, y, n / 2, pl)
    }

    private fun updateVisuals(dt: Float) {
        for (i in particles.indices.reversed()) {
            val p = particles[i]
            p.life -= dt
            if (p.life <= 0f) {
                particles.removeAt(i)
                continue
            }
            if (p.type == 2) {
                gravity(p.x, p.y, g)
                p.vx += g[0] * dt
                p.vy += g[1] * dt
            } else if (p.type == 1 || p.type == 4) {
                p.vx *= 0.97f; p.vy *= 0.97f
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.size += p.grow * dt
            if (p.type == 2 && planetAt(p.x, p.y) != null) particles.removeAt(i)
        }
        for (i in texts.indices.reversed()) {
            val t = texts[i]
            t.life -= dt
            t.y -= 14f * dt
            if (t.life <= 0f) texts.removeAt(i)
        }
        for (t in tanks) {
            if (t.hitFlash > 0f) t.hitFlash -= dt
            if (t.drawLift > 0f) t.drawLift = max(0f, t.drawLift - 120f * dt)
        }
        for (f in fires) {
            if (rf() < dt * 30f) {
                val a = f.ang + rr(-f.half, f.half)
                f.planet.surfacePoint(a, 1f, tmp)
                val p = spawn(tmp[0], tmp[1], 4)
                p.vx = cos(a) * rr(10f, 30f); p.vy = sin(a) * rr(10f, 30f)
                p.max = rr(0.4f, 0.9f); p.life = p.max; p.size = rr(2.5f, 5f)
                p.color = if (rf() < 0.5f) 0xFFFFA726.toInt() else 0xFFFF5722.toInt()
            }
        }
    }
}
