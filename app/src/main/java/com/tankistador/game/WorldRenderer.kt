package com.tankistador.game

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Maps world coordinates to a rectangle on the virtual screen. */
class Camera {
    var cx = 0f
    var cy = 0f
    var scale = 1f
    val view = RectF()

    /** Area to draw into; defaults to [view] when empty. */
    val clip = RectF()

    fun sx(x: Float) = view.centerX() + (x - cx) * scale
    fun sy(y: Float) = view.centerY() + (y - cy) * scale
    fun wx(sx: Float) = cx + (sx - view.centerX()) / scale
    fun wy(sy: Float) = cy + (sy - view.centerY()) / scale

    fun apply(c: Canvas) {
        c.translate(view.centerX(), view.centerY())
        c.scale(scale, scale)
        c.translate(-cx, -cy)
    }
}

class WorldRenderer(val world: World) {
    private class PlanetGfx(val path: Path, var version: Int, val body: Paint, val shade: Paint, val atmo: Paint, val atmoR: Float)

    private val planetGfx = HashMap<Int, PlanetGfx>()
    val nebula: Bitmap = Tex.nebula(world.nebulaSeed)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val r = RectF()
    private val tmp = FloatArray(2)
    private val path = Path()
    private val seg = Path()

    var names: List<String> = emptyList()
    var activeTank = -1
    var movingTank = -1

    private fun gfx(pl: Planet): PlanetGfx {
        var g = planetGfx[pl.id]
        if (g == null) {
            val tex = Tex.planet(pl.style, pl.texSeed)
            val sh = BitmapShader(tex, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            val m = Matrix()
            m.setScale(2 * pl.baseR / tex.width, 2 * pl.baseR / tex.height)
            m.postRotate((pl.texSeed % 360).toFloat(), pl.baseR, pl.baseR)
            m.postTranslate(pl.x - pl.baseR, pl.y - pl.baseR)
            sh.setLocalMatrix(m)
            val body = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = sh }
            val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(pl.x - pl.baseR * 0.35f, pl.y - pl.baseR * 0.42f, pl.baseR * 1.6f,
                    intArrayOf(0x28FFFFFF, 0x00000000, 0x8C000000.toInt(), 0xF0000000.toInt()),
                    floatArrayOf(0f, 0.3f, 0.72f, 1f), Shader.TileMode.CLAMP)
            }
            val tint = when (pl.style) {
                1 -> 0xFFB3E5FC.toInt(); 3 -> 0xFFFF8A65.toInt(); 4 -> 0xFF81D4FA.toInt(); 6 -> 0xFFB388FF.toInt()
                7 -> 0xFFFF7043.toInt(); else -> 0xFFCFD8DC.toInt()
            }
            val atmoR = pl.baseR * 1.18f
            val atmo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                val inner = pl.baseR / atmoR
                shader = RadialGradient(pl.x, pl.y, atmoR,
                    intArrayOf(Gfx.withAlpha(tint, 0), Gfx.withAlpha(tint, 0), Gfx.withAlpha(tint, 70), Gfx.withAlpha(tint, 0)),
                    floatArrayOf(0f, inner - 0.06f, inner + 0.01f, 1f), Shader.TileMode.CLAMP)
            }
            g = PlanetGfx(Path(), -1, body, shade, atmo, atmoR)
            planetGfx[pl.id] = g
        }
        if (g.version != pl.version) {
            g.path.reset()
            for (i in 0 until Planet.N) {
                val a = i * TWO_PI / Planet.N
                val x = pl.x + cos(a) * pl.r[i]
                val y = pl.y + sin(a) * pl.r[i]
                if (i == 0) g.path.moveTo(x, y) else g.path.lineTo(x, y)
            }
            g.path.close()
            g.version = pl.version
        }
        return g
    }

    fun draw(c: Canvas, cam: Camera, t: Float, closeUp: Boolean, overlay: ((Canvas) -> Unit)? = null) {
        c.save()
        c.clipRect(if (cam.clip.isEmpty) cam.view else cam.clip)
        cam.apply(c)
        val px = 1f / cam.scale // one screen unit in world units

        r.set(-World.FIELD_W * 0.75f, -World.FIELD_H * 0.85f, World.FIELD_W * 0.75f, World.FIELD_H * 0.85f)
        Gfx.bmp.alpha = 200
        c.drawBitmap(nebula, null, r, Gfx.bmp)
        Gfx.bmp.alpha = 255

        for (a in world.asteroids) if (a.alive) drawAsteroid(c, a)
        for (pl in world.planets) drawPlanet(c, pl, px)
        for (b in world.buildings) if (b.alive) drawBuilding(c, b, t)
        for (cl in world.colonists) if (cl.alive) drawColonist(c, cl, t)
        for (m in world.mines) drawMine(c, m, t)
        for (tk in world.tanks) drawTank(c, tk, t, px, closeUp)
        for (m in world.moles) drawMole(c, m, t)
        for (h in world.holes) drawHole(c, h, t)
        drawParticles(c, px, false)
        for (pr in world.projectiles) drawProjectile(c, pr, px, t)
        drawParticles(c, px, true)
        overlay?.invoke(c)
        for (ft in world.texts) {
            val a = (min(1f, ft.life / 0.5f) * 255).toInt()
            Gfx.text(c, ft.text, ft.x, ft.y, ft.size * px, ft.color, alpha = a)
        }
        c.restore()
    }

    // ------------------------------------------------------------------ planets

    private fun drawPlanet(c: Canvas, pl: Planet, px: Float) {
        val g = gfx(pl)
        val mat = World.material(pl.style)
        c.drawCircle(pl.x, pl.y, g.atmoR, g.atmo)
        c.drawPath(g.path, g.body)
        c.save()
        c.clipPath(g.path)
        for (sc in pl.scorches) {
            p.shader = RadialGradient(sc[0], sc[1], sc[2], intArrayOf(0xAA1A120C.toInt(), 0x551A120C, 0x001A120C), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(sc[0], sc[1], sc[2], p)
        }
        p.shader = null
        // Exposed rock along the crater walls.
        buildCarved(pl, -1f)
        s.shader = null
        s.color = Gfx.withAlpha(mat[1], 230)
        s.strokeWidth = 11f
        c.drawPath(seg, s)
        s.color = Gfx.withAlpha(Gfx.darken(mat[1], 0.35f), 200)
        s.strokeWidth = 4f
        c.drawPath(seg, s)
        // Cracks in the crust.
        s.color = 0x99140D08.toInt()
        s.strokeWidth = 1.3f
        for (cr in pl.cracks) {
            path.reset()
            path.moveTo(cr[0], cr[1])
            var i = 2
            while (i < cr.size) { path.lineTo(cr[i], cr[i + 1]); i += 2 }
            c.drawPath(path, s)
        }
        c.restore()
        c.drawPath(g.path, g.shade)
        // Freshly blasted ground glows and cools down.
        if (buildCarved(pl, 0.04f)) {
            s.color = 0x33FF6D00
            s.strokeWidth = 10f
            c.drawPath(seg, s)
            var i = 0
            while (i < Planet.N) {
                val h = pl.heat[i]
                if (h > 0.04f) {
                    val a1 = i * TWO_PI / Planet.N
                    val a2 = (i + 1) * TWO_PI / Planet.N
                    val r1 = pl.r[i]; val r2 = pl.r[(i + 1) % Planet.N]
                    s.color = Gfx.withAlpha(if (h > 0.6f) 0xFFFFE082.toInt() else 0xFFFF6D00.toInt(), (255 * min(1f, h * 1.3f)).toInt())
                    s.strokeWidth = 2.6f
                    c.drawLine(pl.x + cos(a1) * r1, pl.y + sin(a1) * r1, pl.x + cos(a2) * r2, pl.y + sin(a2) * r2, s)
                }
                i++
            }
        }
        s.color = 0x55000000
        s.strokeWidth = 1.4f * px
        c.drawPath(g.path, s)
    }

    /** Collects the surface segments that are dug out (or hot when [minHeat] >= 0) into [seg]. */
    private fun buildCarved(pl: Planet, minHeat: Float): Boolean {
        seg.reset()
        var open = false
        var any = false
        for (k in 0..Planet.N) {
            val i = k % Planet.N
            val on = if (minHeat < 0f) pl.r[i] < pl.baseR - 1.5f else pl.heat[i] > minHeat
            if (on) {
                val a = i * TWO_PI / Planet.N
                val x = pl.x + cos(a) * pl.r[i]
                val y = pl.y + sin(a) * pl.r[i]
                if (!open) { seg.moveTo(x, y); open = true } else seg.lineTo(x, y)
                any = true
            } else open = false
        }
        return any
    }

    private fun orient(c: Canvas, pl: Planet, ang: Float, lift: Float) {
        pl.surfacePoint(ang, lift, tmp)
        c.translate(tmp[0], tmp[1])
        c.rotate(pl.normalAt(ang) / DEG + 90f)
    }

    private fun drawAsteroid(c: Canvas, a: Asteroid) {
        c.save()
        c.translate(a.x, a.y)
        c.rotate(a.rot / DEG)
        path.reset()
        for (k in 0 until a.shape.size) {
            val ang = k * TWO_PI / a.shape.size
            val rr = a.r * a.shape[k]
            if (k == 0) path.moveTo(cos(ang) * rr, sin(ang) * rr) else path.lineTo(cos(ang) * rr, sin(ang) * rr)
        }
        path.close()
        p.shader = RadialGradient(-a.r * 0.3f, -a.r * 0.3f, a.r * 1.3f, 0xFFA1958A.toInt(), 0xFF3E3832.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        p.color = 0x55000000
        c.drawCircle(a.r * 0.25f, a.r * 0.1f, a.r * 0.2f, p)
        c.drawCircle(-a.r * 0.3f, a.r * 0.35f, a.r * 0.13f, p)
        s.color = 0xFF2A2622.toInt(); s.strokeWidth = 1f
        c.drawPath(path, s)
        c.restore()
    }

    private fun drawBuilding(c: Canvas, b: Building, t: Float) {
        c.save()
        orient(c, b.planet, b.ang, -1f)
        when (b.style) {
            0 -> {
                p.color = 0xFFECEFF1.toInt()
                r.set(-6f, -6f, 6f, 6f)
                c.drawArc(r, 180f, 180f, true, p)
                p.color = 0xFF546E7A.toInt()
                c.drawRect(-1.2f, -3f, 1.2f, 0.5f, p)
                p.color = if ((t * 0.5f + b.ang).toInt() % 2 == 0) 0xFFFFF59D.toInt() else 0xFFFFE082.toInt()
                c.drawCircle(3f, -2.5f, 0.9f, p)
            }
            1 -> {
                p.color = 0xFFFFCC80.toInt()
                c.drawRect(-5f, -6f, 5f, 0.5f, p)
                p.color = 0xFFC62828.toInt()
                path.reset(); path.moveTo(-6.5f, -6f); path.lineTo(0f, -11f); path.lineTo(6.5f, -6f); path.close()
                c.drawPath(path, p)
                p.color = 0xFF4FC3F7.toInt()
                c.drawRect(-3f, -4.5f, -0.8f, -2.3f, p)
                p.color = 0xFF6D4C41.toInt()
                c.drawRect(1.2f, -4f, 3.2f, 0.5f, p)
            }
            else -> {
                p.color = 0xFFB0BEC5.toInt()
                c.drawRect(-2.2f, -14f, 2.2f, 0.5f, p)
                p.color = 0xFF78909C.toInt()
                c.drawRect(-4f, -3f, 4f, 0.5f, p)
                p.color = if ((t * 2).toInt() % 2 == 0) 0xFFFF5252.toInt() else 0xFF7F0000.toInt()
                c.drawCircle(0f, -15f, 1.3f, p)
            }
        }
        c.restore()
    }

    private fun drawColonist(c: Canvas, cl: Colonist, t: Float) {
        c.save()
        orient(c, cl.planet, cl.ang, 0f)
        val step = if (cl.wait > 0) 0f else sin(cl.phase * 10f) * 1.2f
        s.color = 0xFF212121.toInt(); s.strokeWidth = 0.6f
        c.drawLine(0f, -2.2f, -step * 0.6f, 0f, s)
        c.drawLine(0f, -2.2f, step * 0.6f, 0f, s)
        p.color = when (cl.hue) { 0 -> 0xFF42A5F5.toInt(); 1 -> 0xFFFFA726.toInt(); 2 -> 0xFFAB47BC.toInt(); else -> 0xFF26A69A.toInt() }
        c.drawRect(-0.9f, -4.6f, 0.9f, -2f, p)
        p.color = 0xFFFFE0B2.toInt()
        c.drawCircle(0f, -5.5f, 1.05f, p)
        c.restore()
    }

    private fun drawMine(c: Canvas, m: Mine, t: Float) {
        if (!m.alive) return
        c.save()
        orient(c, m.planet, m.ang, 0f)
        p.color = 0xFF37474F.toInt()
        r.set(-4.5f, -3.5f, 4.5f, 3.5f)
        c.drawArc(r, 180f, 180f, true, p)
        p.color = 0xFF263238.toInt()
        c.drawRect(-5f, -0.6f, 5f, 0.6f, p)
        s.color = 0xFF90A4AE.toInt(); s.strokeWidth = 0.6f
        c.drawLine(0f, -3.5f, 0f, -5.5f, s)
        val armed = m.t > 0.8f
        val on = ((t + m.ang) * (if (armed) 3f else 1f)).toInt() % 2 == 0
        p.color = if (on) 0xFFFF1744.toInt() else 0xFF5D1010.toInt()
        c.drawCircle(0f, -5.8f, 1.1f, p)
        if (on && armed) Gfx.glow(c, 0f, -5.8f, 4f, 0x88FF1744.toInt())
        c.restore()
    }

    private fun drawHole(c: Canvas, h: Singularity, t: Float) {
        val k = (h.strength / 5.5e5f).coerceIn(0f, 1f)
        val rad = 4f + 6f * k
        p.shader = RadialGradient(h.x, h.y, rad * 6f, intArrayOf(0xAA000000.toInt(), 0x44120024, 0x00000000), floatArrayOf(0.15f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(h.x, h.y, rad * 6f, p)
        p.shader = null
        for (i in 0 until 4) {
            s.color = Gfx.withAlpha(if (i % 2 == 0) 0xFFB388FF.toInt() else 0xFFFF80AB.toInt(), (200 * k).toInt())
            s.strokeWidth = 1.6f
            val rr = rad * (1.6f + i * 0.5f)
            r.set(h.x - rr, h.y - rr * 0.45f, h.x + rr, h.y + rr * 0.45f)
            c.save()
            c.rotate((t * (120f + i * 40f)) % 360f, h.x, h.y)
            c.drawArc(r, i * 50f, 120f, false, s)
            c.restore()
        }
        p.color = 0xFF000000.toInt()
        c.drawCircle(h.x, h.y, rad, p)
        s.color = Gfx.withAlpha(0xFFE1BEE7.toInt(), (180 * k).toInt()); s.strokeWidth = 1f
        c.drawCircle(h.x, h.y, rad + 0.8f, s)
    }

    // ------------------------------------------------------------------ tanks

    private fun drawTank(c: Canvas, tk: Tank, t: Float, px: Float, closeUp: Boolean) {
        c.save()
        val lx = tk.x + cos(tk.normal) * tk.drawLift
        val ly = tk.y + sin(tk.normal) * tk.drawLift
        c.translate(lx, ly)
        c.rotate((if (tk.air) tk.rot else tk.normal) / DEG + 90f)
        c.scale(Tank.SIZE, Tank.SIZE)
        TankArt.draw(c, tk.look, tk.color, TankArt.State(
            aim = tk.aim, gunOk = tk.gun, antennaOk = tk.antenna, tracksOk = tk.tracks,
            shield = tk.shield, flash = tk.hitFlash, time = t + tk.idx * 1.7f,
            moving = tk.idx == movingTank || kotlin.math.abs(tk.slide) > 0.01f, active = tk.idx == activeTank, dead = !tk.alive,
        ))
        c.restore()
        if (!tk.alive || closeUp) return
        val name = names.getOrNull(tk.idx) ?: ""
        val nUp = if (tk.air) -1.5707964f else tk.normal
        val up = 30f * tk.sc + 8f * px
        val nx = lx + cos(nUp) * up
        val ny = ly + sin(nUp) * up
        val bob = if (tk.idx == activeTank) sin(t * 5f) * 3f * px else 0f
        if (tk.idx == activeTank) {
            p.color = Gfx.withAlpha(tk.color, 230)
            path.reset()
            val ay = ny + 6f * px + bob
            path.moveTo(nx - 6f * px, ay - 8f * px); path.lineTo(nx + 6f * px, ay - 8f * px); path.lineTo(nx, ay); path.close()
            c.drawPath(path, p)
        }
        Gfx.text(c, name, nx, ny - 12f * px + bob, (if (tk.idx == activeTank) 13f else 12f) * px, tk.color)
        // Tiny hull bar under the name.
        val w = 30f * px; val h = 3.5f * px
        val by = ny - 8f * px + bob
        p.color = 0xCC000000.toInt()
        c.drawRect(nx - w / 2 - px, by - px, nx + w / 2 + px, by + h + px, p)
        val f = (tk.hull / tk.hullMax).coerceIn(0f, 1f)
        p.color = Gfx.healthColor(f)
        c.drawRect(nx - w / 2, by, nx - w / 2 + w * f, by + h, p)
    }

    private fun drawMole(c: Canvas, m: Mole, t: Float) {
        c.save()
        orient(c, m.planet, m.ang, 0f)
        c.scale(m.dir.toFloat(), 1f)
        val leg = sin(t * 30f) * 1.5f
        s.color = 0xFF37474F.toInt(); s.strokeWidth = 0.7f
        c.drawLine(-2f, -2f, -3f + leg, 0f, s)
        c.drawLine(2f, -2f, 1f - leg, 0f, s)
        p.color = 0xFF8D6E63.toInt()
        c.drawCircle(0f, -3.5f, 3f, p)
        p.color = 0xFFFFEB3B.toInt()
        c.drawCircle(1.6f, -4.2f, 0.9f, p)
        s.color = 0xFFBDBDBD.toInt(); s.strokeWidth = 0.6f
        val k = t * 12f
        c.drawLine(-3f, -4f, -5f, -4f + sin(k), s)
        c.restore()
    }

    // ------------------------------------------------------------------ projectiles

    private fun drawProjectile(c: Canvas, pr: Projectile, px: Float, t: Float) {
        val tracer = pr.look == Look.SHELL || pr.look == Look.HE || pr.look == Look.AP || pr.look == Look.BOMBLET || pr.look == Look.SEED
        if (tracer && pr.trailLen > 1) {
            s.strokeWidth = 1.2f * px
            var prevX = 0f; var prevY = 0f
            val n = pr.trailLen
            for (k in 0 until n) {
                val idx = (pr.trailHead - n + k + Projectile.TRAIL) % Projectile.TRAIL
                val x = pr.trail[idx * 2]; val y = pr.trail[idx * 2 + 1]
                if (k > 0) {
                    s.color = Gfx.withAlpha(0xFFE0E0E0.toInt(), k * 60 / n)
                    c.drawLine(prevX, prevY, x, y, s)
                }
                prevX = x; prevY = y
            }
        }
        val k = max(1f, px * 1.15f)
        c.save()
        c.translate(pr.x, pr.y)
        val dir = atan2(pr.vy, pr.vx) / DEG
        when (pr.look) {
            Look.BALL, Look.FROG, Look.MELON, Look.GRAV, Look.METEOR, Look.MINE_POD, Look.BOMBLET, Look.SPARK -> c.rotate(pr.spin / DEG)
            else -> c.rotate(dir)
        }
        c.scale(k, k)
        when (pr.look) {
            Look.SHELL -> shell(c, 5f, 1.5f, 0xFFD7CCC8.toInt(), 0xFF6D5D52.toInt(), 0)
            Look.HE -> shell(c, 6.5f, 2.3f, 0xFF9E9D24.toInt(), 0xFF3B3A0E.toInt(), 0xFFD32F2F.toInt())
            Look.AP -> {
                shell(c, 7f, 1.3f, 0xFF78909C.toInt(), 0xFF263238.toInt(), 0)
                p.color = 0xFFFFD54F.toInt()
                c.drawCircle(6.2f, 0f, 0.7f, p)
            }
            Look.ROCKET, Look.FIREWORK, Look.HOMING -> rocket(c, pr, t)
            Look.BALL -> {
                p.shader = RadialGradient(-1.2f, -1.2f, 4.5f, 0xFFE1F5FE.toInt(), 0xFF0277BD.toInt(), Shader.TileMode.CLAMP)
                c.drawCircle(0f, 0f, 3.4f, p)
                p.shader = null
                s.color = 0xFFFFFFFF.toInt(); s.strokeWidth = 0.8f
                c.drawLine(-3.2f, 0f, 3.2f, 0f, s)
            }
            Look.FROG -> {
                p.shader = RadialGradient(-1.2f, -1.2f, 4.5f, 0xFFDCEDC8.toInt(), 0xFF33691E.toInt(), Shader.TileMode.CLAMP)
                c.drawCircle(0f, 0f, 3.4f, p)
                p.shader = null
                p.color = 0xFF1B5E20.toInt()
                c.drawCircle(1.2f, 1.2f, 0.8f, p); c.drawCircle(-1.5f, 0.6f, 0.6f, p)
                p.color = 0xFFFFFFFF.toInt()
                c.drawCircle(-1f, -2.6f, 1f, p); c.drawCircle(1f, -2.6f, 1f, p)
                p.color = 0xFF000000.toInt()
                c.drawCircle(-1f, -2.6f, 0.45f, p); c.drawCircle(1f, -2.6f, 0.45f, p)
            }
            Look.CANISTER -> {
                fins(c, -4.5f, 2.6f, 0xFF455A64.toInt())
                p.shader = LinearGradient(0f, -2.6f, 0f, 2.6f, 0xFFCFD8DC.toInt(), 0xFF546E7A.toInt(), Shader.TileMode.CLAMP)
                r.set(-4.5f, -2.6f, 4.5f, 2.6f)
                c.drawRoundRect(r, 2f, 2f, p)
                p.shader = null
                p.color = 0xFF37474F.toInt()
                c.drawRect(-2.2f, -2.6f, -1.4f, 2.6f, p)
                c.drawRect(1.4f, -2.6f, 2.2f, 2.6f, p)
            }
            Look.BOMBLET -> {
                p.color = 0xFF37474F.toInt()
                c.drawCircle(0f, 0f, 1.9f, p)
                p.color = 0xFFFFB300.toInt()
                c.drawCircle(0.6f, -0.6f, 0.5f, p)
            }
            Look.SPARK -> {
                val col = if (pr.color != 0) pr.color else 0xFFFFFFFF.toInt()
                Gfx.glow(c, 0f, 0f, 5f, Gfx.withAlpha(col, 170))
                p.color = 0xFFFFFFFF.toInt()
                c.drawCircle(0f, 0f, 1.2f, p)
            }
            Look.MELON -> {
                p.color = 0xFF2E7D32.toInt()
                r.set(-4.2f, -3.4f, 4.2f, 3.4f)
                c.drawOval(r, p)
                s.color = 0xFF1B5E20.toInt(); s.strokeWidth = 0.9f
                for (i in -1..1) c.drawLine(-3.8f, i * 1.6f, 3.8f, i * 1.6f, s)
                p.color = 0x55FFFFFF
                c.drawCircle(-1.6f, -1.6f, 1f, p)
            }
            Look.SEED -> {
                p.color = 0xFF212121.toInt()
                r.set(-2f, -1.1f, 2f, 1.1f)
                c.drawOval(r, p)
            }
            Look.POD -> {
                p.color = 0xFF6D4C41.toInt()
                r.set(-4f, -2.4f, 4f, 2.4f)
                c.drawRoundRect(r, 2.4f, 2.4f, p)
                p.color = 0xFFFFEB3B.toInt()
                c.drawCircle(2.5f, -0.6f, 0.8f, p)
                s.color = 0xFFBDBDBD.toInt(); s.strokeWidth = 0.6f
                c.drawLine(-2f, -2.4f, -3f, -4.2f, s)
            }
            Look.MINE_POD -> {
                p.color = 0xFF37474F.toInt()
                c.drawCircle(0f, 0f, 3f, p)
                p.color = if ((t * 4).toInt() % 2 == 0) 0xFFFF1744.toInt() else 0xFF5D1010.toInt()
                c.drawCircle(0f, 0f, 1.1f, p)
                s.color = 0xFF90A4AE.toInt(); s.strokeWidth = 0.6f
                for (i in 0 until 4) {
                    val a = i * 1.5708f
                    c.drawLine(cos(a) * 3f, sin(a) * 3f, cos(a) * 4.3f, sin(a) * 4.3f, s)
                }
            }
            Look.NAPALM -> {
                fins(c, -4.5f, 2.4f, 0xFF7F0000.toInt())
                p.shader = LinearGradient(0f, -2.4f, 0f, 2.4f, 0xFFEF5350.toInt(), 0xFF8E0000.toInt(), Shader.TileMode.CLAMP)
                r.set(-4.5f, -2.4f, 5f, 2.4f)
                c.drawRoundRect(r, 2.4f, 2.4f, p)
                p.shader = null
                p.color = 0xFFFFCA28.toInt()
                path.reset(); path.moveTo(-0.5f, 1.4f); path.lineTo(0.8f, -1.6f); path.lineTo(2f, 1.4f); path.close()
                c.drawPath(path, p)
            }
            Look.DRILL -> {
                p.color = 0xFF5D4037.toInt()
                r.set(-5f, -2.2f, 0f, 2.2f)
                c.drawRect(r, p)
                p.shader = LinearGradient(0f, -2.4f, 0f, 2.4f, 0xFFECEFF1.toInt(), 0xFF607D8B.toInt(), Shader.TileMode.CLAMP)
                path.reset(); path.moveTo(0f, -2.4f); path.lineTo(6f, 0f); path.lineTo(0f, 2.4f); path.close()
                c.drawPath(path, p)
                p.shader = null
                s.color = 0xFF37474F.toInt(); s.strokeWidth = 0.5f
                val off = (pr.spin * 3f) % 1.5f
                var x = off
                while (x < 5.5f) {
                    val hh = 2.4f * (1f - x / 6f)
                    c.drawLine(x, -hh, x + 0.8f, hh, s)
                    x += 1.5f
                }
            }
            Look.QUAKE -> {
                fins(c, -5f, 3f, 0xFF3E2723.toInt())
                p.shader = LinearGradient(0f, -2.8f, 0f, 2.8f, 0xFFBCAAA4.toInt(), 0xFF4E342E.toInt(), Shader.TileMode.CLAMP)
                r.set(-5f, -2.8f, 4.5f, 2.8f)
                c.drawOval(r, p)
                p.shader = null
                s.color = 0xFFFFCA28.toInt(); s.strokeWidth = 0.7f
                path.reset(); path.moveTo(-2f, -1.6f); path.lineTo(-0.5f, 0f); path.lineTo(-1.6f, 0.4f); path.lineTo(0.5f, 1.8f)
                c.drawPath(path, s)
            }
            Look.BEACON -> {
                p.color = 0xFF455A64.toInt()
                r.set(-3.5f, -2f, 3.5f, 2f)
                c.drawRoundRect(r, 2f, 2f, p)
                val on = (t * 6).toInt() % 2 == 0
                p.color = if (on) 0xFFFFAB40.toInt() else 0xFF6D3A00.toInt()
                c.drawCircle(0f, 0f, 1.2f, p)
                if (on) Gfx.glow(c, 0f, 0f, 6f, 0x88FFAB40.toInt())
            }
            Look.METEOR -> {
                Gfx.glow(c, 0f, 0f, 11f, 0x99FF6D00.toInt())
                path.reset()
                for (i in 0 until 8) {
                    val a = i * TWO_PI / 8
                    val rr = 4f * (0.75f + 0.25f * sin(i * 2.7f))
                    if (i == 0) path.moveTo(cos(a) * rr, sin(a) * rr) else path.lineTo(cos(a) * rr, sin(a) * rr)
                }
                path.close()
                p.shader = RadialGradient(-1f, -1f, 5f, 0xFFFFCC80.toInt(), 0xFF4E342E.toInt(), Shader.TileMode.CLAMP)
                c.drawPath(path, p)
                p.shader = null
            }
            Look.GRAV -> {
                Gfx.glow(c, 0f, 0f, 9f, 0x88B388FF.toInt())
                p.color = 0xFF120024.toInt()
                c.drawCircle(0f, 0f, 3.4f, p)
                s.color = 0xFFB388FF.toInt(); s.strokeWidth = 0.9f
                r.set(-5.5f, -2f, 5.5f, 2f)
                c.drawOval(r, s)
            }
            Look.NUKE -> {
                fins(c, -6f, 3.8f, 0xFF424242.toInt())
                p.shader = LinearGradient(0f, -3.6f, 0f, 3.6f, 0xFFFFF59D.toInt(), 0xFFF9A825.toInt(), Shader.TileMode.CLAMP)
                r.set(-6f, -3.6f, 6f, 3.6f)
                c.drawOval(r, p)
                p.shader = null
                p.color = 0xFF212121.toInt()
                c.drawRect(-2.5f, -3.4f, -1.5f, 3.4f, p)
                c.drawRect(0.5f, -3.5f, 1.5f, 3.5f, p)
                s.color = 0xFF212121.toInt(); s.strokeWidth = 0.6f
                c.drawOval(r, s)
            }
            Look.CAPSULE -> {
                Gfx.glow(c, 0f, 0f, 9f, 0x88CE93D8.toInt())
                p.shader = LinearGradient(0f, -2.4f, 0f, 2.4f, 0xFFF3E5F5.toInt(), 0xFF7B1FA2.toInt(), Shader.TileMode.CLAMP)
                r.set(-4f, -2.4f, 4f, 2.4f)
                c.drawRoundRect(r, 2.4f, 2.4f, p)
                p.shader = null
            }
        }
        c.restore()
    }

    /** A bullet-shaped shell pointing along +x. */
    private fun shell(c: Canvas, len: Float, rad: Float, light: Int, dark: Int, band: Int) {
        path.reset()
        path.moveTo(-len * 0.7f, -rad)
        path.lineTo(len * 0.25f, -rad)
        path.quadTo(len * 0.8f, -rad * 0.6f, len, 0f)
        path.quadTo(len * 0.8f, rad * 0.6f, len * 0.25f, rad)
        path.lineTo(-len * 0.7f, rad)
        path.close()
        p.shader = LinearGradient(0f, -rad, 0f, rad, light, dark, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        p.color = dark
        c.drawRect(-len * 0.7f, -rad, -len * 0.55f, rad, p)
        if (band != 0) {
            p.color = band
            c.drawRect(len * 0.05f, -rad, len * 0.2f, rad, p)
        }
    }

    private fun fins(c: Canvas, x: Float, h: Float, color: Int) {
        p.color = color
        path.reset()
        path.moveTo(x + 2f, -h * 0.5f); path.lineTo(x - 1.5f, -h * 1.15f); path.lineTo(x - 0.5f, -h * 0.3f); path.close()
        path.moveTo(x + 2f, h * 0.5f); path.lineTo(x - 1.5f, h * 1.15f); path.lineTo(x - 0.5f, h * 0.3f); path.close()
        c.drawPath(path, p)
    }

    private fun rocket(c: Canvas, pr: Projectile, t: Float) {
        val body = when (pr.look) {
            Look.HOMING -> 0xFF00ACC1.toInt(); Look.FIREWORK -> 0xFFD81B60.toInt(); else -> 0xFFECEFF1.toInt()
        }
        val nose = when (pr.look) {
            Look.HOMING -> 0xFF263238.toInt(); Look.FIREWORK -> 0xFFFFEB3B.toInt(); else -> 0xFFD32F2F.toInt()
        }
        if (pr.thrustLeft > 0f || pr.look == Look.FIREWORK) {
            val fl = 4f + 3f * ((t * 37f + pr.x) % 1f)
            p.color = 0xCCFF9800.toInt()
            path.reset(); path.moveTo(-4.5f, -1.2f); path.lineTo(-4.5f - fl, 0f); path.lineTo(-4.5f, 1.2f); path.close()
            c.drawPath(path, p)
            p.color = 0xFFFFF59D.toInt()
            path.reset(); path.moveTo(-4.5f, -0.6f); path.lineTo(-4.5f - fl * 0.5f, 0f); path.lineTo(-4.5f, 0.6f); path.close()
            c.drawPath(path, p)
        }
        fins(c, -3.5f, 1.6f, nose)
        p.shader = LinearGradient(0f, -1.4f, 0f, 1.4f, Gfx.lighten(body, 0.4f), Gfx.darken(body, 0.35f), Shader.TileMode.CLAMP)
        r.set(-4.5f, -1.4f, 3.5f, 1.4f)
        c.drawRect(r, p)
        p.shader = null
        p.color = nose
        path.reset(); path.moveTo(3.5f, -1.4f); path.quadTo(6.5f, -0.6f, 7f, 0f); path.quadTo(6.5f, 0.6f, 3.5f, 1.4f); path.close()
        c.drawPath(path, p)
        if (pr.look == Look.HOMING) {
            p.color = if ((t * 8).toInt() % 2 == 0) 0xFFFF1744.toInt() else 0xFF7F0000.toInt()
            c.drawCircle(5.6f, 0f, 0.6f, p)
        }
    }

    // ------------------------------------------------------------------ particles

    private fun drawParticles(c: Canvas, px: Float, front: Boolean) {
        p.shader = null
        for (q in world.particles) {
            val isFront = q.type == World.P_SPARK || q.type == World.P_FLASH || q.type == World.P_RING || q.type == World.P_FIREBALL || q.type == World.P_EMBER
            if (isFront != front) continue
            val f = (q.life / q.max).coerceIn(0f, 1f)
            when (q.type) {
                World.P_SPARK -> {
                    s.color = Gfx.withAlpha(q.color, (255 * min(1f, f * 1.6f)).toInt())
                    s.strokeWidth = max(q.size, 1.1f * px)
                    c.drawLine(q.x, q.y, q.x - q.vx * 0.035f, q.y - q.vy * 0.035f, s)
                }
                World.P_SMOKE -> {
                    p.color = Gfx.withAlpha(q.color, ((q.color ushr 24) * min(1f, f * 1.3f) * min(1f, (1f - f) * 6f + 0.2f)).toInt())
                    c.drawCircle(q.x, q.y, q.size, p)
                }
                World.P_ROCK -> {
                    p.color = q.color
                    val sz = q.size
                    val cr = cos(q.rot) * sz; val sr = sin(q.rot) * sz
                    path.reset()
                    path.moveTo(q.x + cr, q.y + sr)
                    path.lineTo(q.x - sr * 0.8f, q.y + cr * 0.8f)
                    path.lineTo(q.x - cr * 0.9f, q.y - sr * 0.9f)
                    path.lineTo(q.x + sr * 0.6f, q.y - cr * 0.6f)
                    path.close()
                    c.drawPath(path, p)
                }
                World.P_FLASH -> Gfx.glow(c, q.x, q.y, q.size * (0.6f + 0.6f * (1 - f)), Gfx.withAlpha(q.color, (255 * f).toInt()))
                World.P_FLAME -> {
                    Gfx.glow(c, q.x, q.y, q.size * 1.8f, Gfx.withAlpha(q.color, (150 * f).toInt()))
                    p.color = Gfx.withAlpha(0xFFFFF59D.toInt(), (220 * f).toInt())
                    c.drawCircle(q.x, q.y, q.size * 0.45f, p)
                }
                World.P_RING -> {
                    s.color = Gfx.withAlpha(q.color, (200 * f).toInt())
                    s.strokeWidth = max(1.2f * px, 2.2f * f)
                    c.drawCircle(q.x, q.y, q.size, s)
                }
                World.P_FIREBALL -> {
                    val (outer, inner) = when {
                        f > 0.75f -> 0xFFFFE082.toInt() to 0xFFFFFFFF.toInt()
                        f > 0.5f -> 0xFFFF9800.toInt() to 0xFFFFEE58.toInt()
                        f > 0.3f -> 0xFFD84315.toInt() to 0xFFFF9800.toInt()
                        else -> 0xFF3E3633.toInt() to 0xFF6D3B22.toInt()
                    }
                    val a = (255 * min(1f, f * 2.2f)).toInt()
                    p.shader = RadialGradient(q.x, q.y, q.size, intArrayOf(Gfx.withAlpha(inner, a), Gfx.withAlpha(outer, a * 3 / 4), Gfx.withAlpha(outer, 0)),
                        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                    c.drawCircle(q.x, q.y, q.size, p)
                    p.shader = null
                }
                World.P_DUST -> {
                    p.color = Gfx.withAlpha(q.color, (110 * f).toInt())
                    c.drawCircle(q.x, q.y, q.size, p)
                }
                World.P_EMBER -> {
                    p.color = Gfx.withAlpha(0xFFFFB74D.toInt(), (120 * f).toInt())
                    c.drawCircle(q.x, q.y, q.size * 2.2f, p)
                    p.color = Gfx.withAlpha(0xFFFFF8E1.toInt(), (255 * f).toInt())
                    c.drawCircle(q.x, q.y, q.size * 0.8f, p)
                }
            }
        }
    }
}
