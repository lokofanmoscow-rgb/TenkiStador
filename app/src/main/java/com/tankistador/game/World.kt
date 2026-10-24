package com.tankistador.game

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
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

/**
 * A round, deformable planet. The surface is a radius sampled at [N] evenly spaced angles.
 * Craters are irregular, throw up a rim, glow while hot and crack the crust. Lost ground also
 * lowers the planet's mass, so heavily bombed planets pull a little weaker.
 */
class Planet(val id: Int, val x: Float, val y: Float, val baseR: Float, val density: Float, val style: Int, val texSeed: Int) {
    companion object {
        const val N = 360
    }

    val r = FloatArray(N) { baseR }
    val heat = FloatArray(N)
    val minR = baseR * 0.28f
    private val mass0 = density * baseR * baseR * baseR
    var mass = mass0
        private set
    var maxR = baseR
        private set
    var version = 0
    val scorches = ArrayList<FloatArray>()
    val cracks = ArrayList<FloatArray>()
    private val area0 = (PI * baseR * baseR).toFloat()

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
        if (d2 > maxR * maxR * 1.0001f) return false
        return sqrt(d2) < surfaceAt(atan2(dy, dx))
    }

    fun surfacePoint(angle: Float, lift: Float = 0f, out: FloatArray) {
        val rr = surfaceAt(angle) + lift
        out[0] = x + cos(angle) * rr
        out[1] = y + sin(angle) * rr
    }

    /** Outward surface normal (radians) at [angle], from the neighbouring samples. */
    fun normalAt(angle: Float, span: Float = 0.05f): Float {
        val r1 = surfaceAt(angle - span)
        val r2 = surfaceAt(angle + span)
        val ax = x + cos(angle - span) * r1; val ay = y + sin(angle - span) * r1
        val bx = x + cos(angle + span) * r2; val by = y + sin(angle + span) * r2
        return atan2(-(bx - ax), by - ay)
    }

    /** Steepness of the ground at [angle] in radians and which way is downhill (+1 = growing angle). */
    fun slope(angle: Float, out: FloatArray) {
        val sr = surfaceAt(angle)
        val d = 6f / sr
        val h1 = surfaceAt(angle - d)
        val h2 = surfaceAt(angle + d)
        out[0] = atan(abs(h2 - h1) / (2 * d * sr))
        out[1] = if (h2 < h1) 1f else -1f
    }

    /** Removes an irregular, roughly circular chunk. Returns true if the surface changed. */
    fun carve(ex: Float, ey: Float, er: Float, rng: Random): Boolean {
        val dx = ex - x
        val dy = ey - y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > maxR + er * 1.2f) return false
        val p1 = rng.nextFloat() * TWO_PI
        val p2 = rng.nextFloat() * TWO_PI
        val p3 = rng.nextFloat() * TWO_PI
        var changed = false
        val carvedIdx = ArrayList<Int>()
        for (i in 0 until N) {
            val phi = i * TWO_PI / N
            val ux = cos(phi)
            val uy = sin(phi)
            val eri = er * (1f + 0.10f * sin(3 * phi + p1) + 0.06f * sin(7 * phi + p2) + 0.04f * sin(13 * phi + p3))
            val b = ux * dx + uy * dy
            val c = dx * dx + dy * dy - eri * eri
            val disc = b * b - c
            if (disc < 0) {
                rim(i, ux, uy, dx, dy, eri, er)
                continue
            }
            val s = sqrt(disc)
            val t1 = b - s
            val t2 = b + s
            if (t2 <= 0) continue
            if (r[i] > t1 && r[i] <= t2) {
                val nr = max(t1, minR)
                if (nr < r[i]) {
                    r[i] = nr
                    heat[i] = min(1f, heat[i] + er / 30f)
                    carvedIdx.add(i)
                    changed = true
                }
            } else if (r[i] < t1) {
                rim(i, ux, uy, dx, dy, eri, er)
            }
        }
        if (!changed) return false
        version++
        unstable = max(unstable, 2.5f)
        maxR = r.max()
        var area = 0f
        for (v in r) area += v * v
        area *= 0.5f * TWO_PI / N
        mass = mass0 * (area / area0).coerceIn(0.05f, 1.2f).pow(1.5f).coerceAtLeast(0.15f)
        if (scorches.size > 70) scorches.removeAt(0)
        scorches.add(floatArrayOf(ex, ey, er * 1.35f))
        if (er >= 20f && carvedIdx.isNotEmpty()) addCracks(carvedIdx, er, rng)
        return true
    }

    /** Material thrown out of a crater piles up just outside it. */
    private fun rim(i: Int, ux: Float, uy: Float, dx: Float, dy: Float, eri: Float, er: Float) {
        if (r[i] < baseR * 0.8f) return
        val px = ux * r[i] - dx
        val py = uy * r[i] - dy
        val d = sqrt(px * px + py * py)
        if (d < eri || d > eri * 1.5f) return
        val lift = er * 0.08f * (1f - (d - eri) / (eri * 0.5f))
        if (lift > 0f) r[i] = min(baseR * 1.05f, r[i] + lift)
    }

    private fun addCracks(idx: List<Int>, er: Float, rng: Random) {
        val n = 2 + min(3, (er / 25f).toInt())
        repeat(n) {
            val i = idx[rng.nextInt(idx.size)]
            val a0 = i * TWO_PI / N
            var px = x + cos(a0) * r[i]
            var py = y + sin(a0) * r[i]
            var dir = a0 + PI.toFloat() + (rng.nextFloat() - 0.5f) * 1.6f
            val segs = 4 + rng.nextInt(4)
            val pts = FloatArray((segs + 1) * 2)
            pts[0] = px; pts[1] = py
            for (k in 1..segs) {
                dir += (rng.nextFloat() - 0.5f) * 0.9f
                val len = er * (0.15f + rng.nextFloat() * 0.18f)
                px += cos(dir) * len
                py += sin(dir) * len
                pts[k * 2] = px; pts[k * 2 + 1] = py
            }
            cracks.add(pts)
        }
        while (cracks.size > 40) cracks.removeAt(0)
    }

    /** Seconds of ground instability left after a blast; crater walls crumble meanwhile. */
    var unstable = 0f

    /**
     * Lets over-steep ground crumble: material slides from a high sample to its lower
     * neighbour until the slope is stable. Returns the sample that moved most, or -1.
     */
    fun relax(): Int {
        var worst = -1
        var worstMove = 0.3f
        val spacing = TWO_PI / N
        for (i in 0 until N) {
            val j = (i + 1) % N
            val a = r[i]; val b = r[j]
            val maxStep = (a + b) * 0.5f * spacing * 1.25f
            val diff = a - b
            if (abs(diff) > maxStep) {
                val move = (abs(diff) - maxStep) * 0.35f
                if (diff > 0) { r[i] -= move; r[j] += move * 0.92f } else { r[j] -= move; r[i] += move * 0.92f }
                if (move > worstMove) { worstMove = move; worst = if (diff > 0) i else j }
            }
        }
        if (worst >= 0) version++
        return worst
    }

    /** A settling rock adds a little ground where it lands. */
    fun deposit(angle: Float, amount: Float) {
        var a = angle / TWO_PI * N
        a %= N.toFloat()
        if (a < 0) a += N
        val i = a.toInt() % N
        for (k in -1..1) {
            val j = (i + k + N) % N
            r[j] = min(baseR * 1.07f, r[j] + amount * (if (k == 0) 0.5f else 0.25f))
        }
        version++
    }

    fun cool(dt: Float): Boolean {
        var hot = false
        val k = exp(-dt / 2.2f)
        for (i in 0 until N) if (heat[i] > 0.01f) {
            heat[i] *= k
            hot = true
        } else heat[i] = 0f
        return hot
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
    var lastHitBy = -1

    // Free flight after being thrown by a blast.
    var air = false
    var vx = 0f
    var vy = 0f
    var airTime = 0f
    var spin = 0f
    var rot = 0f

    /** Angular speed along the surface (rad/s) when pushed or sliding downhill. */
    var slide = 0f

    val look get() = Tanks.all[model].look
    val sc get() = look.scale * SIZE
    val radius get() = 11f * sc

    companion object {
        /** Tanks are drawn and collide larger than their art units so they read well on a phone. */
        const val SIZE = 1.45f
    }

    fun ratio(p: Part) = when (p) {
        Part.GUN -> gun / gunMax; Part.HULL -> hull / hullMax; Part.ANTENNA -> antenna / antennaMax; Part.TRACKS -> tracks / tracksMax
    }.coerceIn(0f, 1f)

    /** Planet version the tank was last settled on; ground changes make it settle again. */
    var placedVersion = -1

    /** Seconds since the driver last pushed the tracks; a driven tank does not slide back. */
    var driveT = 1f

    /**
     * Settles the tank on the ground. The tracks rest on the highest point under each half,
     * so a tank rolls over holes narrower than itself instead of dropping into them.
     * Returns the height it fell.
     */
    fun place(): Float {
        val oldR = sqrt((x - planet.x) * (x - planet.x) + (y - planet.y) * (y - planet.y))
        val sr0 = planet.surfaceAt(ang)
        val span = 11.5f * sc / max(sr0, 10f)
        var lA = ang; var lR = -1f; var rA = ang; var rR = -1f
        for (k in 0..6) {
            val f = k / 6f
            val a1 = ang - span * f
            val r1 = planet.surfaceAt(a1) + f * f * span * span * sr0 * 0.5f
            if (r1 > lR) { lR = r1; lA = a1 }
            val a2 = ang + span * f
            val r2 = planet.surfaceAt(a2) + f * f * span * span * sr0 * 0.5f
            if (r2 > rR) { rR = r2; rA = a2 }
        }
        lR = planet.surfaceAt(lA); rR = planet.surfaceAt(rA)
        val lx = planet.x + cos(lA) * lR; val ly = planet.y + sin(lA) * lR
        val rx2 = planet.x + cos(rA) * rR; val ry2 = planet.y + sin(rA) * rR
        val ux = cos(ang); val uy = sin(ang)
        var supportR = sr0
        var nx = ux; var ny = uy
        val tx = rx2 - lx; val ty = ry2 - ly
        val tl = sqrt(tx * tx + ty * ty)
        if (tl > 1f) {
            nx = ty / tl; ny = -tx / tl
            // Where the radial line through the tank meets the line between both contacts.
            val den = ux * ty - uy * tx
            if (abs(den) > 1e-4f) {
                val s = ((lx - planet.x) * ty - (ly - planet.y) * tx) / den
                if (s > 0f) supportR = max(s, sr0)
            }
        } else {
            val n = planet.normalAt(ang, 7f * DEG)
            nx = cos(n); ny = sin(n)
        }
        nx = nx * 0.85f + ux * 0.15f
        ny = ny * 0.85f + uy * 0.15f
        normal = atan2(ny, nx)
        val lift = 6.5f * sc
        x = planet.x + ux * supportR + cos(normal) * lift
        y = planet.y + uy * supportR + sin(normal) * lift
        rot = normal
        placedVersion = planet.version
        return max(0f, oldR - (supportR + lift))
    }

    /** How far the hull is tilted away from "straight up" (radians, signed toward growing angle). */
    fun tilt() = wrapAngle(normal - ang)

    /** World-space angle (radians) the barrel points at. */
    fun aimWorld() = normal + (90f - aim) * DEG

    /** A broken gun can only spit shells out weakly. */
    fun maxSpeed(): Float {
        if (gun <= 0f) return World.MAX_SPEED * 0.2f
        return World.MAX_SPEED * (0.45f + 0.55f * (gun / gunMax))
    }

    fun wobbleDeg(): Float = if (gun <= 0f) 12f else (1f - gun / gunMax) * 6f

    /** Local art-space point to world space. Local "up" is -y. */
    fun local(lx: Float, ly: Float, out: FloatArray, at: Int = 0) {
        val s = sc
        out[at] = x + (-sin(normal) * lx - cos(normal) * ly) * s
        out[at + 1] = y + (cos(normal) * lx - sin(normal) * ly) * s
    }

    /** World positions of the parts in [Part] order, used to decide what a blast hits hardest. */
    fun anchors(out: FloatArray) {
        val l = look
        val ty = (if (l.hull == 3) -6f else -4f) - 3f
        local(cos(aim * DEG) * l.barrelLen * 0.6f, ty - sin(aim * DEG) * l.barrelLen * 0.6f, out, 0)
        local(0f, -1f, out, 2)
        local(-9f, (if (l.hull == 3) -5.5f else -3f) - l.antennaH * 0.6f, out, 4)
        local(0f, 5f, out, 6)
    }
}

class Projectile(
    var x: Float, var y: Float, var vx: Float, var vy: Float, val w: Weapon, val owner: Int,
    val kind: WeaponKind = w.kind, val look: Look = w.look,
    val damage: Float = w.damage, val radius: Float = w.radius, val sub: Boolean = false,
) {
    var age = 0f
    var bounces = 0
    var drill = -1f
    var alive = true
    var spin = 0f
    var fuse = 0f
    var thrustLeft = 0f
    var lastVr = 0f
    var smokeT = 0f
    var color = 0
    val trail = FloatArray(TRAIL * 2)
    var trailLen = 0
    var trailHead = 0
    private var trailTimer = 0f

    fun pushTrail(dt: Float) {
        trailTimer += dt
        if (trailTimer < 0.03f) return
        trailTimer = 0f
        trail[trailHead * 2] = x
        trail[trailHead * 2 + 1] = y
        trailHead = (trailHead + 1) % TRAIL
        if (trailLen < TRAIL) trailLen++
    }

    companion object {
        const val TRAIL = 12
    }
}

class Mole(val planet: Planet, var ang: Float, val dir: Int, val owner: Int, val w: Weapon) {
    var t = 0f
    var alive = true
    var x = 0f
    var y = 0f
}

class Mine(val planet: Planet, val ang: Float, val owner: Int, val w: Weapon) {
    var t = 0f
    var alive = true
}

class Fire(val planet: Planet, val ang: Float, val half: Float, var turns: Int, val owner: Int, val dmg: Float)

class Quake(val planet: Planet, val ang: Float, val owner: Int, val w: Weapon) {
    var step = 0
    var t = 0f
}

class MeteorCall(val x: Float, val y: Float, val nx: Float, val ny: Float, val owner: Int, val w: Weapon) {
    var left = 5
    var t = 0.2f
}

class Singularity(val x: Float, val y: Float, val owner: Int, val w: Weapon) {
    var t = 0f
    val life = 2.6f
    var bite = 0f
    var hurt = 0f
    val strength get() = 5.5e5f * min(1f, t / 0.5f) * (if (t > life - 0.3f) (life - t) / 0.3f else 1f).coerceAtLeast(0f)
}

class Asteroid(var x: Float, var y: Float, val vx: Float, val vy: Float, val r: Float, var hp: Float, seed: Int) {
    var rot = 0f
    val spin = ((seed % 7) - 3) * 0.15f
    val shape = FloatArray(9) { 0.75f + ((seed * (it + 3) * 7919) % 100) / 400f }
    var alive = true
}

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
    var color2 = 0
    var type = 0 // see P_* constants in World
    var grow = 0f
    var rot = 0f
    var vrot = 0f
    var flag = 0
}

class FloatText(val x: Float, var y: Float, val text: String, val color: Int, val size: Float = 15f) {
    var life = 1.8f
}

sealed class Ev {
    class Shot(val owner: Int, val w: Weapon) : Ev()
    class Explosion(val x: Float, val y: Float, val radius: Float) : Ev()
    class Hit(val tank: Int, val dmg: Float, val by: Int, val direct: Boolean, val part: Part?, val partDmg: Float) : Ev()
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
    class MineLaid(val owner: Int) : Ev()
    class Quake(val owner: Int) : Ev()
    class MeteorIncoming(val owner: Int) : Ev()
    class Rocket(val owner: Int) : Ev()
    class BlackHole(val x: Float, val y: Float) : Ev()
    class Thrown(val tank: Int) : Ev()
    class Landed(val tank: Int, val dmg: Float) : Ev()
    class AsteroidBroken(val x: Float, val y: Float) : Ev()
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
        const val FIELD_H = 660f
        const val LOST_DIST = 2600f

        const val P_SPARK = 0
        const val P_SMOKE = 1
        const val P_ROCK = 2
        const val P_FLASH = 3
        const val P_FLAME = 4
        const val P_RING = 5
        const val P_FIREBALL = 6
        const val P_DUST = 7
        const val P_EMBER = 8

        /** Surface, deep rock and dust colours for each planet style. */
        fun material(style: Int): IntArray = when (style) {
            0 -> intArrayOf(0xFFA8A8AD.toInt(), 0xFF4A4A50.toInt(), 0xFFCFCFD2.toInt())
            1 -> intArrayOf(0xFFDCE7EF.toInt(), 0xFF6F8BA3.toInt(), 0xFFF2F7FA.toInt())
            2 -> intArrayOf(0xFFD6AE73.toInt(), 0xFF7A4B24.toInt(), 0xFFEBD2A2.toInt())
            3 -> intArrayOf(0xFFC2503C.toInt(), 0xFF5A1710.toInt(), 0xFFE07C62.toInt())
            4 -> intArrayOf(0xFF7AA356.toInt(), 0xFF4E3B2A.toInt(), 0xFFC7B884.toInt())
            5 -> intArrayOf(0xFFE0A061.toInt(), 0xFF7E3A16.toInt(), 0xFFF4D2A0.toInt())
            6 -> intArrayOf(0xFF7F55B5.toInt(), 0xFF24133D.toInt(), 0xFFB998E3.toInt())
            else -> intArrayOf(0xFF4A3B36.toInt(), 0xFF161212.toInt(), 0xFFFF8F00.toInt())
        }
    }

    val rng = Random(seed)
    val planets = ArrayList<Planet>()
    val tanks = ArrayList<Tank>()
    val projectiles = ArrayList<Projectile>()
    val moles = ArrayList<Mole>()
    val mines = ArrayList<Mine>()
    val fires = ArrayList<Fire>()
    val quakes = ArrayList<Quake>()
    val meteorCalls = ArrayList<MeteorCall>()
    val holes = ArrayList<Singularity>()
    val asteroids = ArrayList<Asteroid>()
    val buildings = ArrayList<Building>()
    val colonists = ArrayList<Colonist>()
    val particles = ArrayList<Particle>()
    val texts = ArrayList<FloatText>()
    val events = ArrayList<Ev>()

    // Delayed explosions: time, x, y, radius, damage, owner, carve(0/1).
    private val pending = ArrayList<FloatArray>()

    // Shots still to leave the barrel (bursts): time, tank, aim offset.
    private val queued = ArrayList<FloatArray>()
    private val queuedWeapon = ArrayList<Weapon>()

    // Fragments of broken asteroids join the field at the end of the step.
    private val newAsteroids = ArrayList<Asteroid>()
    private var accumulator = 0f
    var time = 0f
    var nebulaSeed = 0

    /** Seconds of very strong flash left (big bombs), for the scene to brighten the screen. */
    var flash = 0f
    private val tmp = FloatArray(2)
    private val tmp8 = FloatArray(8)
    private var relaxT = 0f

    fun rf() = rng.nextFloat()
    fun rr(a: Float, b: Float) = a + (b - a) * rng.nextFloat()

    // ---------------------------------------------------------------- generation

    fun generate(players: List<Player>) {
        nebulaSeed = rng.nextInt()
        val count = 3 + rng.nextInt(3)
        var attempts = 0
        while (planets.size < count && attempts < 5000) {
            attempts++
            val r = rr(55f, 118f)
            val px = rr(-FIELD_W / 2 + r + 20, FIELD_W / 2 - r - 20)
            val py = rr(-FIELD_H / 2 + r + 10, FIELD_H / 2 - r - 10)
            val ok = planets.all { p ->
                val dx = p.x - px; val dy = p.y - py
                sqrt(dx * dx + dy * dy) > p.baseR + r + 115f
            }
            if (ok) planets.add(Planet(planets.size, px, py, r, rr(0.85f, 1.15f), rng.nextInt(8), rng.nextInt()))
            if (attempts % 800 == 0 && planets.size < 2) planets.clear()
        }
        if (planets.size < 2) {
            planets.clear()
            planets.add(Planet(0, -520f, 60f, 100f, 1f, 0, 1))
            planets.add(Planet(1, 0f, -160f, 80f, 1f, 2, 2))
            planets.add(Planet(2, 520f, 80f, 110f, 1f, 3, 3))
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
        if (rf() < 0.6f) {
            val n = 1 + rng.nextInt(4)
            var tries = 0
            while (asteroids.size < n && tries < 200) {
                tries++
                val ar = rr(9f, 20f)
                val ax = rr(-FIELD_W / 2, FIELD_W / 2)
                val ay = rr(-FIELD_H / 2, FIELD_H / 2)
                if (planets.any { sqrt((it.x - ax) * (it.x - ax) + (it.y - ay) * (it.y - ay)) < it.baseR + ar + 60f }) continue
                val a = rr(0f, TWO_PI)
                val sp = rr(6f, 18f)
                asteroids.add(Asteroid(ax, ay, cos(a) * sp, sin(a) * sp, ar, ar * 2.2f, rng.nextInt(1000) + 1))
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
        for (h in holes) {
            val dx = h.x - x
            val dy = h.y - y
            val d2 = dx * dx + dy * dy + 120f
            val d = sqrt(d2)
            val f = G * h.strength / (d2 * d)
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

    private fun nearestPlanet(x: Float, y: Float): Planet? =
        planets.minByOrNull { sqrt((it.x - x) * (it.x - x) + (it.y - y) * (it.y - y)) - it.baseR }

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
     * Flies one shell without side effects. Used by the computer player and the aiming hint.
     * Rocket motors and drifting asteroids are taken into account. [path] receives (x, y) pairs.
     */
    fun simulate(
        t: Tank, aim: Float, power: Float, target: Tank?, maxTime: Float,
        path: FloatArray? = null, pathCount: IntArray? = null, w: Weapon? = null,
    ): SimResult {
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
        val thrust = w?.thrust ?: 0f
        val burn = if (w?.kind == WeaponKind.HOMING) 0f else (w?.burn ?: 0f)
        while (time < maxTime) {
            gravity(x, y, g)
            vx += g[0] * DT
            vy += g[1] * DT
            if (time < burn && thrust > 0f) {
                val sp = sqrt(vx * vx + vy * vy).coerceAtLeast(1f)
                vx += vx / sp * thrust * DT
                vy += vy / sp * thrust * DT
            }
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
                for (a in asteroids) {
                    if (!a.alive) continue
                    val ax = a.x + a.vx * time - x; val ay = a.y + a.vy * time - y
                    if (ax * ax + ay * ay < a.r * a.r) {
                        hit = true
                        break
                    }
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
                t.hull = min(t.hullMax, t.hull + 20f)
                val worst = listOf(Part.GUN, Part.ANTENNA, Part.TRACKS).minByOrNull { t.ratio(it) }!!
                when (worst) {
                    Part.GUN -> t.gun = min(t.gunMax, t.gun + 10f)
                    Part.ANTENNA -> t.antenna = min(t.antennaMax, t.antenna + 10f)
                    Part.TRACKS -> t.tracks = min(t.tracksMax, t.tracks + 10f)
                    else -> {}
                }
                texts.add(FloatText(t.x, t.y - 20, "корпус +20 · ${worst.title.lowercase()} +10", 0xFF69F0AE.toInt()))
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
            WeaponKind.TRIPLE -> for (o in floatArrayOf(-4f, 0f, 4f)) spawnShot(t, w, o, 0f)
            WeaponKind.BURST -> {
                spawnShot(t, w, 0f, 0f)
                for (k in 1 until 5) {
                    queued.add(floatArrayOf(time + k * 0.17f, t.idx.toFloat(), rr(-2.5f, 2.5f)))
                    queuedWeapon.add(w)
                }
            }
            WeaponKind.ROCKETS -> {
                for (k in 0 until 6) spawnShot(t, w, -11f + k * 4.4f + rr(-1f, 1f), rr(-4f, 4f))
                events.add(Ev.Rocket(t.idx))
            }
            else -> spawnShot(t, w, 0f, 0f)
        }
        if (w.kind == WeaponKind.HOMING) events.add(Ev.Rocket(t.idx))
    }

    private fun spawnShot(t: Tank, w: Weapon, aimOff: Float, powOff: Float) {
        if (!t.alive) return
        val wob = t.wobbleDeg()
        val a = t.aim + aimOff + rr(-wob, wob)
        muzzleFor(t, a, tmp)
        val mx = tmp[0]; val my = tmp[1]
        launchVelocity(t, a, t.power + powOff, tmp)
        val p = Projectile(mx, my, tmp[0], tmp[1], w, t.idx)
        p.thrustLeft = w.burn
        p.spin = atan2(tmp[1], tmp[0])
        projectiles.add(p)
        // Muzzle flash and smoke.
        val dir = atan2(tmp[1], tmp[0])
        repeat(8) {
            val q = spawn(mx, my, P_SMOKE)
            val aa = dir + rr(-0.6f, 0.6f)
            val s = rr(15f, 60f)
            q.vx = cos(aa) * s; q.vy = sin(aa) * s
            q.max = rr(0.5f, 1.1f); q.life = q.max; q.size = rr(2.5f, 5f); q.grow = 9f; q.color = 0x99B0B0B0.toInt()
        }
        val fl = spawn(mx, my, P_FLASH)
        fl.max = 0.1f; fl.life = fl.max; fl.size = 12f; fl.color = 0xFFFFF59D.toInt()
        repeat(5) {
            val q = spawn(mx, my, P_SPARK)
            val aa = dir + rr(-0.3f, 0.3f)
            val s = rr(80f, 200f)
            q.vx = cos(aa) * s; q.vy = sin(aa) * s
            q.max = rr(0.1f, 0.25f); q.life = q.max; q.size = 1.2f; q.color = 0xFFFFE082.toInt()
        }
    }

    /** Drives the tank along the surface. Returns false when it cannot move. */
    fun moveTank(t: Tank, dir: Int, dt: Float): Boolean {
        if (!t.alive || t.air || t.fuel <= 0f || t.tracks <= 0f) return false
        val base = 34f * (0.3f + 0.7f * t.tracks / t.tracksMax)
        val sr = t.planet.surfaceAt(t.ang)
        // Uphill the tank crawls, downhill it rolls a little faster, but it always gets through.
        val uphill = -t.tilt() * dir
        val climb = (uphill / DEG).coerceIn(-40f, 85f)
        val factor = if (climb > 0f) (1f - climb / 95f).coerceAtLeast(0.15f) else 1f + (-climb) / 160f
        val speed = base * factor
        val nAng = t.ang + dir * speed * dt / sr
        for (o in tanks) {
            if (o === t || !o.alive || o.planet !== t.planet || o.air) continue
            val gap = abs(wrapAngle(o.ang - nAng)) * sr
            val gapNow = abs(wrapAngle(o.ang - t.ang)) * sr
            if (gap < 34f && gap < gapNow) return false
        }
        t.ang = wrapAngle(nAng)
        t.driveT = 0f
        t.slide = 0f
        // Worn tracks and climbing waste fuel.
        val effort = 1f + max(0f, climb) / 60f
        t.fuel = max(0f, t.fuel - base * dt * effort * (0.5f + 0.5f * (1f - t.tracks / t.tracksMax)))
        val fall = t.place()
        if (fall > 1f) t.drawLift += fall
        if (rf() < 0.5f) {
            val p = spawn(t.x - cos(t.normal) * 6, t.y - sin(t.normal) * 6, P_DUST)
            p.vx = -dir * cos(t.normal + PI.toFloat() / 2) * 12f + rr(-5f, 5f)
            p.vy = -dir * sin(t.normal + PI.toFloat() / 2) * 12f + rr(-5f, 5f)
            p.max = 0.7f; p.life = p.max; p.size = 2.5f; p.grow = 6f; p.color = World.material(t.planet.style)[2]
        }
        return true
    }

    fun burnFires() {
        val it = fires.iterator()
        while (it.hasNext()) {
            val f = it.next()
            for (t in tanks) {
                if (!t.alive || t.air || t.planet !== f.planet) continue
                if (abs(wrapAngle(t.ang - f.ang)) < f.half + 0.06f) {
                    events.add(Ev.Burn(t.idx, f.dmg))
                    t.local(0f, 6f, tmp)
                    damage(t, f.dmg, f.owner, false, tmp[0], tmp[1])
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

    val busy
        get() = projectiles.isNotEmpty() || moles.isNotEmpty() || pending.isNotEmpty() || queued.isNotEmpty() ||
            quakes.isNotEmpty() || meteorCalls.isNotEmpty() || holes.isNotEmpty() ||
            tanks.any { it.alive && (it.air || it.drawLift > 0.5f || abs(it.slide) > 0.01f) }

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
        var qi = 0
        while (qi < queued.size) {
            val q = queued[qi]
            if (q[0] <= time) {
                spawnShot(tanks[q[1].toInt()], queuedWeapon[qi], q[2], 0f)
                events.add(Ev.Shot(q[1].toInt(), queuedWeapon[qi]))
                queued.removeAt(qi); queuedWeapon.removeAt(qi)
            } else qi++
        }
        for (i in projectiles.indices.reversed()) stepProjectile(projectiles[i], dt)
        projectiles.removeAll { !it.alive }
        for (m in moles) stepMole(m, dt)
        moles.removeAll { !it.alive }
        stepMines(dt)
        for (q in quakes) stepQuake(q, dt)
        quakes.removeAll { it.step > 4 }
        for (m in meteorCalls) stepMeteorCall(m, dt)
        meteorCalls.removeAll { it.left <= 0 }
        for (h in holes) stepHole(h, dt)
        holes.removeAll { it.t >= it.life }
        for (a in asteroids) {
            a.x += a.vx * dt; a.y += a.vy * dt; a.rot += a.spin * dt
            if (a.x > FIELD_W / 2 + 140) a.x = -FIELD_W / 2 - 140
            if (a.x < -FIELD_W / 2 - 140) a.x = FIELD_W / 2 + 140
            if (a.y > FIELD_H / 2 + 140) a.y = -FIELD_H / 2 - 140
            if (a.y < -FIELD_H / 2 - 140) a.y = FIELD_H / 2 + 140
        }
        var pi = 0
        while (pi < pending.size) {
            val e = pending[pi]
            if (e[0] <= time) {
                pending.removeAt(pi)
                explode(e[1], e[2], e[3], e[4], e[5].toInt(), null, e[6] > 0f)
            } else pi++
        }
        relaxT -= dt
        if (relaxT <= 0f) {
            relaxT = 0.05f
            for (p in planets) {
                if (p.unstable <= 0f) continue
                p.unstable -= 0.05f
                val i = p.relax()
                if (i >= 0 && rf() < 0.6f) {
                    // Crumbling ground throws a little dust and grit.
                    val a = i * TWO_PI / Planet.N
                    p.surfacePoint(a, 1f, tmp)
                    dustBurst(tmp[0], tmp[1], p, 2)
                    if (rf() < 0.5f) {
                        val m = material(p.style)
                        rocks(tmp[0], tmp[1], 1, m[0], m[1], 30f, p)
                    }
                }
            }
        }
        for (t in tanks) stepTank(t, dt)
        for (c in colonists) stepColonist(c, dt)
        if (newAsteroids.isNotEmpty() || asteroids.any { !it.alive }) {
            asteroids.removeAll { !it.alive }
            asteroids.addAll(newAsteroids)
            newAsteroids.clear()
        }
    }

    private fun stepProjectile(p: Projectile, dt: Float) {
        p.age += dt
        gravity(p.x, p.y, g)
        if (p.drill >= 0f) {
            g[0] *= 0.2f; g[1] *= 0.2f
        }
        p.vx += g[0] * dt
        p.vy += g[1] * dt
        if (p.thrustLeft > 0f && p.w.thrust > 0f && !p.sub && (p.kind != WeaponKind.HOMING || p.age > 0.15f)) {
            p.thrustLeft -= dt
            val sp = sqrt(p.vx * p.vx + p.vy * p.vy).coerceAtLeast(1f)
            p.vx += p.vx / sp * p.w.thrust * dt
            p.vy += p.vy / sp * p.w.thrust * dt
            p.smokeT -= dt
            if (p.smokeT <= 0f) {
                p.smokeT = 0.02f
                val q = spawn(p.x - p.vx / sp * 6f, p.y - p.vy / sp * 6f, P_SMOKE)
                q.vx = -p.vx * 0.1f + rr(-8f, 8f); q.vy = -p.vy * 0.1f + rr(-8f, 8f)
                q.max = rr(0.6f, 1.2f); q.life = q.max; q.size = rr(1.8f, 3f); q.grow = 7f; q.color = 0x88C8C8C8.toInt()
            }
        }
        if (p.kind == WeaponKind.HOMING && p.age > 1f) steerHoming(p, dt)
        if (p.look == Look.METEOR) {
            p.smokeT -= dt
            if (p.smokeT <= 0f) {
                p.smokeT = 0.015f
                val q = spawn(p.x, p.y, P_FLAME)
                q.vx = -p.vx * 0.15f + rr(-10f, 10f); q.vy = -p.vy * 0.15f + rr(-10f, 10f)
                q.max = rr(0.25f, 0.5f); q.life = q.max; q.size = rr(3f, 5f); q.color = if (rf() < 0.5f) 0xFFFFA726.toInt() else 0xFFFF5722.toInt()
            }
        }
        p.x += p.vx * dt
        p.y += p.vy * dt
        val speed = sqrt(p.vx * p.vx + p.vy * p.vy)
        p.spin += speed * dt * 0.05f
        p.pushTrail(dt)

        if (!p.sub && p.kind == WeaponKind.CLUSTER && p.drill < 0f) {
            val np = nearestPlanet(p.x, p.y)
            val vr = if (np != null) {
                val dx = p.x - np.x; val dy = p.y - np.y
                val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                (p.vx * dx + p.vy * dy) / d
            } else 0f
            if ((p.age > 0.5f && p.lastVr > 0f && vr <= 0f) || p.age >= 2.2f) {
                openCluster(p)
                return
            }
            p.lastVr = vr
        }
        if (!p.sub && p.kind == WeaponKind.FIREWORK) {
            val near = tanks.any { it.alive && it.idx != p.owner && (it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y) < 85f * 85f }
            val np = nearestPlanet(p.x, p.y)
            val vr = if (np != null) {
                val dx = p.x - np.x; val dy = p.y - np.y
                (p.vx * dx + p.vy * dy) / sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            } else 0f
            if (p.age > 0.5f && (near || (p.lastVr > 0f && vr <= 0f && p.age > 1.2f))) {
                burstFirework(p, p.x, p.y, null)
                return
            }
            p.lastVr = vr
        }
        if (p.fuse > 0f) {
            p.fuse -= dt
            if (p.fuse <= 0f) {
                p.alive = false
                explode(p.x, p.y, p.radius, p.damage, p.owner, null, planetAt(p.x, p.y) != null)
                return
            }
        }

        if (p.drill >= 0f) {
            p.drill += dt
            p.vx *= 0.97f; p.vy *= 0.97f
            if (rf() < 0.7f) debris(p.x, p.y, 1, planetAt(p.x, p.y))
            val inside = planetAt(p.x, p.y) != null
            if (p.drill > 0.4f || (!inside && p.drill > 0.05f)) {
                p.alive = false
                explode(p.x, p.y, p.radius, p.damage, p.owner, null, true)
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
        for (a in asteroids) {
            if (!a.alive) continue
            val dx = a.x - p.x; val dy = a.y - p.y
            if (dx * dx + dy * dy < a.r * a.r) {
                hitAsteroid(a, p)
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
            explode(p.x, p.y, p.radius * 0.6f, p.damage * 0.5f, p.owner, null, true)
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
        val sp = min(sqrt(p.vx * p.vx + p.vy * p.vy), 340f)
        val na = cur + turn
        p.vx = cos(na) * sp
        p.vy = sin(na) * sp
    }

    private fun openCluster(p: Projectile) {
        p.alive = false
        events.add(Ev.Split(p.x, p.y))
        for (k in 0 until 8) {
            val q = Projectile(p.x, p.y, p.vx * 0.85f + rr(-75f, 75f), p.vy * 0.85f + rr(-75f, 75f), p.w, p.owner,
                WeaponKind.SHELL, Look.BOMBLET, p.damage, p.radius, true)
            q.age = 1f
            projectiles.add(q)
        }
        val fl = spawn(p.x, p.y, P_FLASH)
        fl.max = 0.15f; fl.life = fl.max; fl.size = 14f; fl.color = 0xFFFFF9C4.toInt()
        smokePuff(p.x, p.y, 6, 4f)
    }

    private fun burstFirework(p: Projectile, x: Float, y: Float, pl: Planet?) {
        p.alive = false
        events.add(Ev.Split(x, y))
        val colors = intArrayOf(0xFFFF4081.toInt(), 0xFF40C4FF.toInt(), 0xFFFFEB3B.toInt(), 0xFF69F0AE.toInt(), 0xFFE040FB.toInt())
        val base = if (pl != null) pl.angleOf(x, y) else 0f
        for (k in 0 until 10) {
            val a = if (pl != null) base + (k - 4.5f) * 0.3f else k * TWO_PI / 10 + rr(-0.2f, 0.2f)
            val s = rr(110f, 165f)
            val q = Projectile(x + cos(a) * 3, y + sin(a) * 3, cos(a) * s + p.vx * 0.25f, sin(a) * s + p.vy * 0.25f, p.w, p.owner,
                WeaponKind.SHELL, Look.SPARK, p.damage, p.radius, true)
            q.age = 1f
            q.fuse = rr(1.1f, 1.9f)
            q.color = colors[k % colors.size]
            projectiles.add(q)
        }
        repeat(30) {
            val q = spawn(x, y, P_SPARK)
            val a = rr(0f, TWO_PI); val s = rr(60f, 220f)
            q.vx = cos(a) * s; q.vy = sin(a) * s
            q.max = rr(0.3f, 0.7f); q.life = q.max; q.size = 1.4f; q.color = colors[it % colors.size]
        }
        val fl = spawn(x, y, P_FLASH)
        fl.max = 0.2f; fl.life = fl.max; fl.size = 26f; fl.color = 0xFFFFFFFF.toInt()
    }

    private fun surfaceNormal(pl: Planet, x: Float, y: Float): Float = pl.normalAt(pl.angleOf(x, y))

    private fun impact(p: Projectile, pl: Planet, tank: Tank?) {
        when (p.kind) {
            WeaponKind.BOUNCER -> if (tank == null && p.bounces < 4) {
                bounce(p, pl, 0.72f)
                return
            }
            WeaponKind.HOPPER -> {
                explode(p.x, p.y, p.radius, p.damage, p.owner, tank, true)
                if (p.bounces < 2) {
                    val a = pl.angleOf(p.x, p.y)
                    val n = pl.normalAt(a)
                    val tx = -sin(n); val ty = cos(n)
                    val along = (p.vx * tx + p.vy * ty).let { if (abs(it) < 40f) 40f * sign(it + 0.001f) else it } * 0.6f
                    pl.surfacePoint(a, 4f, tmp)
                    p.x = tmp[0]; p.y = tmp[1]
                    p.vx = cos(n) * 135f + tx * along
                    p.vy = sin(n) * 135f + ty * along
                    p.bounces++
                    p.age = 0.31f
                    return
                }
                p.alive = false
                return
            }
            WeaponKind.DRILL -> if (tank == null) {
                p.drill = 0f
                p.vx *= 0.55f; p.vy *= 0.55f
                debris(p.x, p.y, 10, pl)
                return
            }
            WeaponKind.MOLES -> if (tank == null) {
                p.alive = false
                val a = pl.angleOf(p.x, p.y)
                for (d in intArrayOf(-1, 1)) moles.add(Mole(pl, a, d, p.owner, p.w))
                debris(p.x, p.y, 10, pl)
                events.add(Ev.Bounce(p.x, p.y))
                return
            }
            WeaponKind.MINES -> if (tank == null) {
                p.alive = false
                val a = pl.angleOf(p.x, p.y)
                val sr = pl.surfaceAt(a)
                for (k in -1..1) mines.add(Mine(pl, wrapAngle(a + k * 34f / sr), p.owner, p.w))
                debris(p.x, p.y, 8, pl)
                events.add(Ev.MineLaid(p.owner))
                return
            }
            WeaponKind.NAPALM -> {
                p.alive = false
                val a = pl.angleOf(p.x, p.y)
                fires.add(Fire(pl, a, 42f / pl.surfaceAt(a), 3, p.owner, 10f))
                events.add(Ev.FireStart(p.owner))
                explode(p.x, p.y, p.radius, p.damage, p.owner, tank, true)
                return
            }
            WeaponKind.MELON -> {
                p.alive = false
                explode(p.x, p.y, p.radius, p.damage, p.owner, tank, true)
                val n = surfaceNormal(pl, p.x, p.y)
                for (k in 0 until 5) {
                    val a = n + (k - 2) * 0.42f + rr(-0.15f, 0.15f)
                    val s = rr(95f, 165f)
                    val q = Projectile(p.x + cos(n) * 5, p.y + sin(n) * 5, cos(a) * s, sin(a) * s, p.w, p.owner,
                        WeaponKind.SHELL, Look.SEED, p.damage, p.radius, true)
                    q.age = 1f
                    projectiles.add(q)
                }
                repeat(16) {
                    val q = spawn(p.x, p.y, P_ROCK)
                    val a = n + rr(-1.2f, 1.2f); val s = rr(40f, 140f)
                    q.vx = cos(a) * s; q.vy = sin(a) * s
                    q.max = rr(0.8f, 1.5f); q.life = q.max; q.size = rr(1.5f, 3f)
                    q.color = if (rf() < 0.5f) 0xFFE53935.toInt() else 0xFF43A047.toInt(); q.vrot = rr(-8f, 8f)
                }
                events.add(Ev.Split(p.x, p.y))
                return
            }
            WeaponKind.QUAKE -> {
                p.alive = false
                explode(p.x, p.y, p.radius, p.damage, p.owner, tank, true)
                quakes.add(Quake(pl, pl.angleOf(p.x, p.y), p.owner, p.w))
                events.add(Ev.Quake(p.owner))
                return
            }
            WeaponKind.METEORS -> if (!p.sub) {
                p.alive = false
                val n = surfaceNormal(pl, p.x, p.y)
                meteorCalls.add(MeteorCall(p.x, p.y, cos(n), sin(n), p.owner, p.w))
                sparkle(p.x, p.y, 0xFFFFAB40.toInt())
                events.add(Ev.MeteorIncoming(p.owner))
                return
            }
            WeaponKind.GRAVITY -> {
                p.alive = false
                val n = surfaceNormal(pl, p.x, p.y)
                holes.add(Singularity(p.x + cos(n) * 6f, p.y + sin(n) * 6f, p.owner, p.w))
                events.add(Ev.BlackHole(p.x, p.y))
                return
            }
            WeaponKind.NUKE -> {
                p.alive = false
                explode(p.x, p.y, p.radius, p.damage, p.owner, tank, true)
                flash = 0.6f
                for (k in 0 until 3) {
                    val a = rr(0f, TWO_PI)
                    val d = p.radius * rr(0.6f, 0.95f)
                    pending.add(floatArrayOf(time + 0.35f + k * 0.25f, p.x + cos(a) * d, p.y + sin(a) * d, 26f, 12f, p.owner.toFloat(), 1f))
                }
                return
            }
            WeaponKind.FIREWORK -> if (!p.sub) {
                burstFirework(p, p.x, p.y, pl)
                return
            }
            WeaponKind.CLUSTER -> if (!p.sub) {
                p.alive = false
                explode(p.x, p.y, p.radius * 1.6f, p.damage * 2f, p.owner, tank, true)
                return
            }
            WeaponKind.PIERCE -> {
                p.alive = false
                explode(p.x, p.y, p.radius, p.damage, p.owner, tank, true, directMult = 1.8f)
                return
            }
            WeaponKind.TELEPORT -> {
                p.alive = false
                val owner = tanks.getOrNull(p.owner)
                if (owner != null && owner.alive) {
                    sparkle(owner.x, owner.y, 0xFFCE93D8.toInt())
                    owner.air = false
                    owner.slide = 0f
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
        explode(p.x, p.y, p.radius, p.damage, p.owner, tank, true)
    }

    private fun bounce(p: Projectile, pl: Planet, restitution: Float) {
        val a = pl.angleOf(p.x, p.y)
        val n = pl.normalAt(a)
        val nx = cos(n); val ny = sin(n)
        val vn = p.vx * nx + p.vy * ny
        if (vn < 0) {
            p.vx = (p.vx - 2 * vn * nx) * restitution
            p.vy = (p.vy - 2 * vn * ny) * restitution
        }
        pl.surfacePoint(a, 2f, tmp)
        p.x = tmp[0]; p.y = tmp[1]
        p.bounces++
        events.add(Ev.Bounce(p.x, p.y))
        dustBurst(p.x, p.y, pl, 6)
    }

    private fun hitAsteroid(a: Asteroid, p: Projectile) {
        p.alive = false
        a.hp -= p.damage.coerceAtLeast(6f)
        explode(p.x, p.y, p.radius, p.damage, p.owner, null, false)
        if (a.hp <= 0f) breakAsteroid(a)
    }

    private fun breakAsteroid(a: Asteroid) {
        if (!a.alive) return
        a.alive = false
        events.add(Ev.AsteroidBroken(a.x, a.y))
        rocks(a.x, a.y, (a.r * 1.2f).toInt(), 0xFF8D8478.toInt(), 0xFF4E4842.toInt(), a.r * 3f, null)
        if (a.r > 13f) {
            for (k in 0 until 2) {
                val ang = rr(0f, TWO_PI)
                newAsteroids.add(Asteroid(a.x + cos(ang) * a.r * 0.5f, a.y + sin(ang) * a.r * 0.5f,
                    a.vx + cos(ang) * 25f, a.vy + sin(ang) * 25f, a.r * 0.6f, a.r * 1.2f, rng.nextInt(1000) + 1))
            }
        }
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
            if (!t.alive || t.air || t.planet !== m.planet) continue
            if (t.idx == m.owner && m.t < 0.6f) continue
            val dx = t.x - m.x; val dy = t.y - m.y
            if (dx * dx + dy * dy < (t.radius + 6f) * (t.radius + 6f)) boom = true
        }
        if (boom) {
            m.alive = false
            explode(m.x, m.y, m.w.radius, m.w.damage, m.owner, null, true)
        }
    }

    private fun stepMines(dt: Float) {
        for (m in mines) {
            if (!m.alive) continue
            m.t += dt
            if (m.t < 0.8f) continue
            m.planet.surfacePoint(m.ang, 2f, tmp)
            for (t in tanks) {
                if (!t.alive || t.air || t.planet !== m.planet) continue
                val dx = t.x - tmp[0]; val dy = t.y - tmp[1]
                if (dx * dx + dy * dy < (t.radius + 8f) * (t.radius + 8f)) {
                    m.alive = false
                    pending.add(floatArrayOf(time + 0.25f, tmp[0], tmp[1], m.w.radius, m.w.damage, m.owner.toFloat(), 1f))
                    events.add(Ev.Bounce(tmp[0], tmp[1]))
                    break
                }
            }
        }
        mines.removeAll { !it.alive }
    }

    private fun stepQuake(q: Quake, dt: Float) {
        q.t += dt
        if (q.t < 0.14f) return
        q.t = 0f
        q.step++
        if (q.step > 4) return
        val sr = q.planet.surfaceAt(q.ang)
        for (d in intArrayOf(-1, 1)) {
            val a = q.ang + d * q.step * 30f / sr
            q.planet.surfacePoint(a, 0f, tmp)
            explode(tmp[0], tmp[1], q.w.radius, q.w.damage, q.owner, null, true)
        }
    }

    private fun stepMeteorCall(m: MeteorCall, dt: Float) {
        m.t -= dt
        if (m.t > 0f) return
        m.t = 0.32f
        m.left--
        val tx = -m.ny; val ty = m.nx
        val side = rr(-110f, 110f)
        val sx = m.x + m.nx * 520f + tx * side
        val sy = m.y + m.ny * 520f + ty * side
        val aimX = m.x + tx * rr(-45f, 45f)
        val aimY = m.y + ty * rr(-45f, 45f)
        val dx = aimX - sx; val dy = aimY - sy
        val d = sqrt(dx * dx + dy * dy)
        val q = Projectile(sx, sy, dx / d * 300f, dy / d * 300f, m.w, m.owner, WeaponKind.METEORS, Look.METEOR, m.w.damage, m.w.radius, true)
        q.age = 1f
        q.spin = rr(0f, 6f)
        projectiles.add(q)
    }

    private fun stepHole(h: Singularity, dt: Float) {
        h.t += dt
        h.bite -= dt
        if (h.bite <= 0f && h.t < h.life - 0.2f) {
            h.bite = 0.12f
            for (p in planets) p.carve(h.x, h.y, 7f + 14f * h.t / h.life, rng)
        }
        h.hurt -= dt
        if (h.hurt <= 0f) {
            h.hurt = 0.25f
            for (t in tanks) {
                if (!t.alive) continue
                val dx = h.x - t.x; val dy = h.y - t.y
                val d = sqrt(dx * dx + dy * dy)
                if (d < 95f) {
                    damage(t, 3.7f * (1f - d / 95f) + 1f, h.owner, false, h.x, h.y, quiet = true)
                    if (!t.air) {
                        val tx = -sin(t.normal); val ty = cos(t.normal)
                        t.slide += (dx * tx + dy * ty) / d * 0.25f
                    }
                }
            }
            for (c in colonists) {
                if (!c.alive) continue
                c.planet.surfacePoint(c.ang, 2f, tmp)
                if ((tmp[0] - h.x) * (tmp[0] - h.x) + (tmp[1] - h.y) * (tmp[1] - h.y) < 50f * 50f) {
                    c.alive = false
                    events.add(Ev.ColonistLost(h.owner))
                }
            }
        }
        // Swirling matter.
        repeat(3) {
            val a = rr(0f, TWO_PI); val d = rr(40f, 90f)
            val q = spawn(h.x + cos(a) * d, h.y + sin(a) * d, P_DUST)
            q.vx = -sin(a) * 70f - cos(a) * 30f; q.vy = cos(a) * 70f - sin(a) * 30f
            q.max = rr(0.6f, 1.2f); q.life = q.max; q.size = rr(1.5f, 3f); q.color = if (rf() < 0.5f) 0xFFB388FF.toInt() else 0xFF7C4DFF.toInt()
        }
        if (h.t >= h.life) {
            explode(h.x, h.y, h.w.radius, h.w.damage, h.owner, null, true)
            flash = max(flash, 0.3f)
        }
    }

    private fun stepTank(t: Tank, dt: Float) {
        if (!t.alive) return
        if (t.air) {
            t.airTime += dt
            gravity(t.x, t.y, g)
            t.vx += g[0] * dt; t.vy += g[1] * dt
            t.x += t.vx * dt; t.y += t.vy * dt
            t.rot += t.spin * dt
            for (p in planets) {
                val dx = t.x - p.x; val dy = t.y - p.y
                val d = sqrt(dx * dx + dy * dy)
                val a = atan2(dy, dx)
                if (d < p.surfaceAt(a) + 6.5f * t.sc) {
                    land(t, p, a)
                    return
                }
            }
            if (t.x * t.x + t.y * t.y > LOST_DIST * LOST_DIST || t.airTime > 14f) {
                t.alive = false
                t.hull = 0f
                t.air = false
                events.add(Ev.Killed(t.idx, t.lastHitBy))
                texts.add(FloatText(t.x, t.y, "улетел в космос", 0xFFFF8A80.toInt()))
            }
            return
        }
        t.driveT += dt
        if (t.placedVersion != t.planet.version) {
            val fall = t.place()
            if (fall > 1f) t.drawLift += fall
        }
        // Pushed along the ground, or sliding down a steep crater wall (unless being driven).
        val tilt = t.tilt()
        val steep = abs(tilt) > 40f * DEG && t.driveT > 0.3f
        if (steep) t.slide += sign(tilt) * (abs(tilt) - 40f * DEG) * 2.5f * dt
        if (abs(t.slide) > 0.0005f) {
            val sr = t.planet.surfaceAt(t.ang)
            val nAng = t.ang + t.slide * dt
            val blocked = tanks.any { o ->
                o !== t && o.alive && !o.air && o.planet === t.planet &&
                    abs(wrapAngle(o.ang - nAng)) * sr < 30f && abs(wrapAngle(o.ang - nAng)) < abs(wrapAngle(o.ang - t.ang))
            }
            if (blocked) t.slide = 0f else {
                t.ang = wrapAngle(nAng)
                t.place()
                if (rf() < 0.06f) dustBurst(t.x - cos(t.normal) * 7f, t.y - sin(t.normal) * 7f, t.planet, 1)
            }
            t.slide *= exp(-3f * dt)
            if (abs(t.slide) * sr < 1.5f && !steep) t.slide = 0f
        }
    }

    private fun land(t: Tank, p: Planet, a: Float) {
        val speed = sqrt(t.vx * t.vx + t.vy * t.vy)
        t.air = false
        t.planet = p
        t.ang = a
        val n = p.normalAt(a)
        // Keep some of the sideways speed as a slide.
        val tang = -t.vx * sin(n) + t.vy * cos(n)
        t.slide = tang / p.surfaceAt(a) * 0.5f
        t.place()
        t.drawLift = 0f
        dustBurst(t.x, t.y, p, 12)
        val dmg = max(0f, speed - 90f) * 0.18f
        if (dmg > 0f) {
            t.local(0f, 8f, tmp)
            damage(t, dmg, t.lastHitBy, false, tmp[0], tmp[1])
        }
        events.add(Ev.Landed(t.idx, dmg))
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
            if (t.alive && !t.air && t.planet === c.planet && abs(wrapAngle(t.ang - c.ang)) * sr < 20f) {
                c.dir = if (wrapAngle(c.ang - t.ang) > 0) 1 else -1
            }
        }
    }

    // ---------------------------------------------------------------- damage

    fun explode(
        x: Float, y: Float, radius: Float, damage: Float, owner: Int, direct: Tank?, carve: Boolean,
        directMult: Float = 1.15f,
    ) {
        events.add(Ev.Explosion(x, y, radius))
        if (carve && radius > 0) {
            for (p in planets) p.carve(x, y, radius * 0.85f, rng)
        }
        for (a in asteroids) {
            if (!a.alive) continue
            val dx = a.x - x; val dy = a.y - y
            if (dx * dx + dy * dy < (radius + a.r) * (radius + a.r)) {
                a.hp -= damage * 0.6f
                if (a.hp <= 0f) breakAsteroid(a)
            }
        }
        for (t in tanks) {
            if (!t.alive) continue
            val dx = t.x - x; val dy = t.y - y
            val d = sqrt(dx * dx + dy * dy)
            val reach = radius + t.radius
            val f: Float
            if (t === direct) {
                damage(t, damage * directMult, owner, true, x, y)
                f = 1f
            } else if (d < reach && damage > 0) {
                f = 1f - d / reach
                damage(t, damage * (0.25f + 0.75f * f), owner, false, x, y)
            } else f = 0f
            if (f > 0f && t.alive) knock(t, x, y, radius, damage, f)
        }
        for (b in buildings) {
            if (!b.alive) continue
            b.planet.surfacePoint(b.ang, 4f, tmp)
            val dx = tmp[0] - x; val dy = tmp[1] - y
            if (dx * dx + dy * dy < (radius + 6f) * (radius + 6f)) {
                b.alive = false
                events.add(Ev.BuildingLost(owner))
                rocks(tmp[0], tmp[1], 10, 0xFFBDBDBD.toInt(), 0xFF8D6E63.toInt(), 120f, b.planet)
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
        for (m in mines) {
            if (!m.alive) continue
            m.planet.surfacePoint(m.ang, 2f, tmp)
            val dx = tmp[0] - x; val dy = tmp[1] - y
            if (dx * dx + dy * dy < (radius + 12f) * (radius + 12f)) {
                m.alive = false
                pending.add(floatArrayOf(time + rr(0.12f, 0.25f), tmp[0], tmp[1], m.w.radius, m.w.damage, m.owner.toFloat(), 1f))
            }
        }
        // Buildings whose ground vanished are lost too.
        for (b in buildings) if (b.alive && b.planet.surfaceAt(b.ang) < b.planet.baseR * 0.55f) b.alive = false
        for (t in tanks) {
            if (!t.alive || t.air) continue
            val oldX = t.x; val oldY = t.y
            val fall = t.place()
            if (fall > 1f) {
                t.drawLift += fall
                if (fall > 22f) damage(t, (fall - 22f) * 0.3f, owner, false, oldX, oldY + 0.01f)
            }
        }
        explosionFx(x, y, radius)
    }

    /** Blast pushes the tank along the ground, or throws it into the air if strong enough. */
    private fun knock(t: Tank, x: Float, y: Float, radius: Float, damage: Float, f: Float) {
        if (t.air) return
        var dx = t.x - x; var dy = t.y - y
        val d = sqrt(dx * dx + dy * dy)
        if (d < 0.5f) { dx = cos(t.normal); dy = sin(t.normal) } else { dx /= d; dy /= d }
        val impulse = (damage * 0.6f + radius * 0.8f) * f * 3f / (t.sc * t.sc)
        val nx = cos(t.normal); val ny = sin(t.normal)
        val up = dx * nx + dy * ny
        val side = -dx * sin(t.normal) + dy * cos(t.normal)
        if (impulse > 110f && up > -0.2f) {
            t.air = true
            t.airTime = 0f
            t.vx = (dx * 0.6f + nx * 0.6f) * (impulse - 30f)
            t.vy = (dy * 0.6f + ny * 0.6f) * (impulse - 30f)
            t.spin = side.sign * rr(2f, 6f)
            t.rot = t.normal
            t.slide = 0f
            t.drawLift = 0f
            t.lastHitBy = t.lastHitBy
            events.add(Ev.Thrown(t.idx))
        } else {
            t.slide += side * impulse * 0.5f / t.planet.surfaceAt(t.ang)
        }
    }

    fun damage(t: Tank, amount: Float, by: Int, direct: Boolean, fromX: Float, fromY: Float, quiet: Boolean = false) {
        if (!t.alive || amount <= 0f) return
        var dmg = amount
        if (t.shield > 0f) {
            val ab = min(t.shield, dmg)
            t.shield -= ab
            dmg -= ab
            sparkle(t.x, t.y, 0xFF40C4FF.toInt())
        }
        if (dmg <= 0.01f) return
        if (by >= 0) t.lastHitBy = by
        // The part closest to the blast takes the most, the hull always takes a share.
        t.anchors(tmp8)
        val w = FloatArray(4)
        var sum = 0f
        for (i in 0 until 4) {
            val dx = tmp8[i * 2] - fromX; val dy = tmp8[i * 2 + 1] - fromY
            val d = sqrt(dx * dx + dy * dy) / t.sc
            w[i] = 1f / ((d + 4f) * (d + 4f))
            sum += w[i]
        }
        for (i in 0 until 4) w[i] /= sum
        val hullDmg = dmg * (0.55f + 0.45f * w[Part.HULL.ordinal])
        val gunDmg = dmg * 1.25f * w[Part.GUN.ordinal]
        val antDmg = dmg * 1.25f * w[Part.ANTENNA.ordinal]
        val trkDmg = dmg * 1.25f * w[Part.TRACKS.ordinal]
        t.hull = max(0f, t.hull - hullDmg)
        t.gun = max(0f, t.gun - gunDmg)
        t.antenna = max(0f, t.antenna - antDmg)
        t.tracks = max(0f, t.tracks - trkDmg)
        if (direct && rf() < 0.4f) t.fuel = max(0f, t.fuel - dmg * 1.5f)
        t.hitFlash = 0.35f
        val main = listOf(Part.GUN to gunDmg, Part.ANTENNA to antDmg, Part.TRACKS to trkDmg).maxByOrNull { it.second }!!
        t.lastPart = main.first
        if (!quiet || hullDmg >= 3f) {
            texts.add(FloatText(t.x, t.y - 18f, "-" + hullDmg.toInt().coerceAtLeast(1), if (direct) 0xFFFFEB3B.toInt() else 0xFFFF8A80.toInt(), if (direct) 19f else 15f))
        }
        if (main.second >= 4f && !quiet) {
            texts.add(FloatText(t.x, t.y - 4f, "${main.first.title.lowercase()} −${main.second.toInt()}", 0xFFFFCC80.toInt(), 11f))
        }
        events.add(Ev.Hit(t.idx, hullDmg, by, direct, main.first, main.second))
        if (t.gun <= 0f && gunDmg > 0f) sparksAt(t, Part.GUN)
        if (t.antenna <= 0f && antDmg > 0f) sparksAt(t, Part.ANTENNA)
        if (t.tracks <= 0f && trkDmg > 0f) sparksAt(t, Part.TRACKS)
        if (t.hull <= 0f) {
            t.alive = false
            events.add(Ev.Killed(t.idx, by))
            pending.add(floatArrayOf(time + 0.3f, t.x, t.y, 46f, 40f, by.toFloat(), 1f))
            rocks(t.x, t.y, 22, t.color, 0xFF37474F.toInt(), 180f, t.planet)
        }
    }

    private fun sparksAt(t: Tank, part: Part) {
        t.anchors(tmp8)
        val x = tmp8[part.ordinal * 2]; val y = tmp8[part.ordinal * 2 + 1]
        repeat(14) {
            val q = spawn(x, y, P_SPARK)
            val a = rr(0f, TWO_PI); val s = rr(40f, 140f)
            q.vx = cos(a) * s; q.vy = sin(a) * s
            q.max = rr(0.2f, 0.5f); q.life = q.max; q.size = 1.2f; q.color = 0xFFFFE082.toInt()
        }
    }

    // ---------------------------------------------------------------- visuals

    private fun spawn(x: Float, y: Float, type: Int): Particle {
        val p = Particle()
        p.x = x; p.y = y; p.type = type
        if (particles.size < 1500) particles.add(p)
        return p
    }

    fun sparkle(x: Float, y: Float, color: Int) {
        repeat(18) {
            val p = spawn(x + rr(-10f, 10f), y + rr(-10f, 10f), P_FLAME)
            p.vx = rr(-15f, 15f); p.vy = rr(-40f, -5f)
            p.max = rr(0.5f, 1.1f); p.life = p.max; p.size = rr(2f, 3.5f); p.color = color
        }
        val ring = spawn(x, y, P_RING)
        ring.max = 0.5f; ring.life = ring.max; ring.size = 6f; ring.grow = 50f; ring.color = color
    }

    fun debris(x: Float, y: Float, n: Int, planet: Planet?) {
        val m = material(planet?.style ?: 0)
        rocks(x, y, n, m[0], m[1], 140f, planet)
    }

    /** Tumbling rock chunks, mostly thrown away from the ground. */
    private fun rocks(x: Float, y: Float, n: Int, c1: Int, c2: Int, speed: Float, planet: Planet?) {
        val up = if (planet != null) atan2(y - planet.y, x - planet.x) else 0f
        repeat(n) {
            val p = spawn(x, y, P_ROCK)
            val a = if (planet != null) up + rr(-1.3f, 1.3f) else rr(0f, TWO_PI)
            val s = rr(speed * 0.25f, speed)
            p.vx = cos(a) * s; p.vy = sin(a) * s
            p.max = rr(1.2f, 2.6f); p.life = p.max; p.size = rr(1.2f, 3.6f)
            p.color = if (rf() < 0.6f) c1 else c2
            p.rot = rr(0f, 6f); p.vrot = rr(-10f, 10f)
        }
    }

    private fun dustBurst(x: Float, y: Float, planet: Planet, n: Int) {
        val c = material(planet.style)[2]
        val a0 = planet.angleOf(x, y)
        repeat(n) {
            val p = spawn(x, y, P_DUST)
            val t = a0 + PI.toFloat() / 2 * (if (rf() < 0.5f) 1 else -1)
            val s = rr(15f, 60f)
            p.vx = cos(t) * s + cos(a0) * rr(5f, 20f); p.vy = sin(t) * s + sin(a0) * rr(5f, 20f)
            p.max = rr(0.6f, 1.3f); p.life = p.max; p.size = rr(2f, 4f); p.grow = 8f; p.color = c
        }
    }

    private fun smokePuff(x: Float, y: Float, n: Int, size: Float) {
        repeat(n) {
            val p = spawn(x + rr(-size, size), y + rr(-size, size), P_SMOKE)
            p.vx = rr(-12f, 12f); p.vy = rr(-12f, 12f)
            p.max = rr(0.8f, 1.6f); p.life = p.max; p.size = size; p.grow = size * 2f; p.color = 0x88909090.toInt()
        }
    }

    private fun explosionFx(x: Float, y: Float, radius: Float) {
        if (radius <= 0f) return
        val pl = planets.minByOrNull { sqrt((it.x - x) * (it.x - x) + (it.y - y) * (it.y - y)) - it.surfaceAt(it.angleOf(x, y)) }
        val nearGround = pl != null && sqrt((pl.x - x) * (pl.x - x) + (pl.y - y) * (pl.y - y)) < pl.surfaceAt(pl.angleOf(x, y)) + radius * 0.8f
        val up = if (pl != null) atan2(y - pl.y, x - pl.x) else rr(0f, TWO_PI)

        val fl = spawn(x, y, P_FLASH)
        fl.max = 0.3f; fl.life = fl.max; fl.size = radius * 1.8f; fl.color = 0xFFFFF8E1.toInt()
        val ring = spawn(x, y, P_RING)
        ring.max = 0.35f; ring.life = ring.max; ring.size = radius * 0.3f; ring.grow = radius * 4.5f; ring.color = 0xFFFFFFFF.toInt()
        val nf = 5 + (radius / 6f).toInt()
        repeat(nf) {
            val p = spawn(x + rr(-radius, radius) * 0.3f, y + rr(-radius, radius) * 0.3f, P_FIREBALL)
            val a = if (nearGround) up + rr(-1.4f, 1.4f) else rr(0f, TWO_PI)
            val s = rr(radius * 0.6f, radius * 1.7f)
            p.vx = cos(a) * s; p.vy = sin(a) * s
            p.max = rr(0.45f, 0.85f); p.life = p.max; p.size = rr(radius * 0.22f, radius * 0.45f); p.grow = radius * 0.5f
        }
        val ns = 10 + (radius * 0.6f).toInt()
        repeat(ns) {
            val p = spawn(x, y, P_SPARK)
            val a = if (nearGround && rf() < 0.8f) up + rr(-1.5f, 1.5f) else rr(0f, TWO_PI)
            val s = rr(radius * 2f, radius * 5f)
            p.vx = cos(a) * s; p.vy = sin(a) * s
            p.max = rr(0.18f, 0.45f); p.life = p.max; p.size = rr(1f, 1.8f)
            p.color = if (rf() < 0.5f) 0xFFFFF59D.toInt() else 0xFFFFB74D.toInt()
        }
        repeat((radius / 4f).toInt()) {
            val p = spawn(x, y, P_EMBER)
            val a = if (nearGround) up + rr(-1.2f, 1.2f) else rr(0f, TWO_PI)
            val s = rr(radius * 1f, radius * 2.5f)
            p.vx = cos(a) * s; p.vy = sin(a) * s
            p.max = rr(0.8f, 1.8f); p.life = p.max; p.size = rr(1f, 1.8f); p.color = 0xFFFF8F00.toInt()
        }
        repeat(3 + (radius / 7f).toInt()) {
            val p = spawn(x + rr(-radius, radius) * 0.4f, y + rr(-radius, radius) * 0.4f, P_SMOKE)
            val a = if (nearGround) up + rr(-0.8f, 0.8f) else rr(0f, TWO_PI)
            val s = rr(8f, 26f)
            p.vx = cos(a) * s; p.vy = sin(a) * s
            p.max = rr(1.6f, 3.2f); p.life = p.max; p.size = rr(radius * 0.18f, radius * 0.32f) + 2f; p.grow = radius * 0.45f
            p.color = if (rf() < 0.5f) 0xAA3E3A36.toInt() else 0x99575049.toInt()
        }
        if (pl != null && nearGround) {
            val m = material(pl.style)
            rocks(x, y, (radius * 0.7f).toInt().coerceAtMost(60), m[0], m[1], radius * 4f, pl)
            dustBurst(x, y, pl, (radius / 3f).toInt())
        }
    }

    private fun updateVisuals(dt: Float) {
        if (flash > 0f) flash = max(0f, flash - dt)
        for (p in planets) p.cool(dt)
        for (i in particles.indices.reversed()) {
            val p = particles[i]
            p.life -= dt
            if (p.life <= 0f) {
                particles.removeAt(i)
                continue
            }
            when (p.type) {
                P_ROCK, P_EMBER -> {
                    gravity(p.x, p.y, g)
                    p.vx += g[0] * dt
                    p.vy += g[1] * dt
                    p.rot += p.vrot * dt
                }
                P_SMOKE -> {
                    // Smoke drifts away from the ground.
                    val np = nearestPlanet(p.x, p.y)
                    if (np != null) {
                        val dx = p.x - np.x; val dy = p.y - np.y
                        val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                        p.vx += dx / d * 7f * dt; p.vy += dy / d * 7f * dt
                    }
                    p.vx *= 0.985f; p.vy *= 0.985f
                }
                P_FIREBALL -> { p.vx *= 0.9f; p.vy *= 0.9f }
                P_FLAME, P_DUST -> { p.vx *= 0.96f; p.vy *= 0.96f }
            }
            if (holes.isNotEmpty() && p.type != P_ROCK && p.type != P_EMBER) {
                for (h in holes) {
                    val dx = h.x - p.x; val dy = h.y - p.y
                    val d2 = dx * dx + dy * dy + 100f
                    val f = 2.5e5f * min(1f, h.t) / (d2 * sqrt(d2))
                    p.vx += dx * f * dt; p.vy += dy * f * dt
                }
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.size += p.grow * dt
            if (p.type == P_ROCK || p.type == P_EMBER) {
                val pl = planetAt(p.x, p.y)
                if (pl != null) {
                    if (p.type == P_EMBER || p.flag >= 2) {
                        particles.removeAt(i)
                        continue
                    }
                    // Bounce once, then come to rest on the ground.
                    val a = pl.angleOf(p.x, p.y)
                    val nx = cos(a); val ny = sin(a)
                    val vn = p.vx * nx + p.vy * ny
                    pl.surfacePoint(a, 0.5f, tmp)
                    p.x = tmp[0]; p.y = tmp[1]
                    if (p.flag == 0 && vn < -30f) {
                        p.vx = (p.vx - 2 * vn * nx) * 0.35f
                        p.vy = (p.vy - 2 * vn * ny) * 0.35f
                        p.flag = 1
                    } else {
                        p.vx = 0f; p.vy = 0f; p.vrot = 0f
                        p.flag = 2
                        if (p.size > 1.8f) pl.deposit(a, p.size * 0.3f)
                        p.type = P_DUST
                        p.grow = 0f
                        p.life = min(p.life, 1.2f); p.max = max(p.life, 0.1f)
                    }
                }
            }
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
            // Damaged tanks smoke, badly damaged ones burn, wrecks smoulder.
            val hr = t.hull / t.hullMax
            if ((!t.alive || hr < 0.45f) && rf() < dt * (if (!t.alive || hr < 0.2f) 14f else 6f)) {
                t.local(rr(-6f, 6f), -4f, tmp)
                val q = spawn(tmp[0], tmp[1], P_SMOKE)
                q.vx = cos(t.normal) * 14f + rr(-5f, 5f); q.vy = sin(t.normal) * 14f + rr(-5f, 5f)
                q.max = rr(1.2f, 2.2f); q.life = q.max; q.size = 2.5f; q.grow = 7f; q.color = if (hr < 0.2f || !t.alive) 0xAA202020.toInt() else 0x88707070.toInt()
                if (!t.alive || hr < 0.2f) {
                    val f = spawn(tmp[0], tmp[1], P_FLAME)
                    f.vx = cos(t.normal) * 18f; f.vy = sin(t.normal) * 18f
                    f.max = rr(0.3f, 0.6f); f.life = f.max; f.size = rr(2f, 3.5f); f.color = 0xFFFF7043.toInt()
                }
            }
            if (t.alive && t.gun <= 0f && rf() < dt * 2f) sparksAt(t, Part.GUN)
        }
        for (f in fires) {
            if (rf() < dt * 30f) {
                val a = f.ang + rr(-f.half, f.half)
                f.planet.surfacePoint(a, 1f, tmp)
                val p = spawn(tmp[0], tmp[1], P_FLAME)
                p.vx = cos(a) * rr(10f, 30f); p.vy = sin(a) * rr(10f, 30f)
                p.max = rr(0.4f, 0.9f); p.life = p.max; p.size = rr(2.5f, 5f)
                p.color = if (rf() < 0.5f) 0xFFFFA726.toInt() else 0xFFFF5722.toInt()
            }
        }
    }
}
