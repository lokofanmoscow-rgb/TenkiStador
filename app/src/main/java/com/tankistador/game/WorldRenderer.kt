package com.tankistador.game

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private val tmp = FloatArray(2)
    private val path = Path()

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

        r.set(-World.FIELD_W * 0.75f, -World.FIELD_H * 0.75f, World.FIELD_W * 0.75f, World.FIELD_H * 0.75f)
        Gfx.bmp.alpha = 200
        c.drawBitmap(nebula, null, r, Gfx.bmp)
        Gfx.bmp.alpha = 255

        for (pl in world.planets) drawPlanet(c, pl, px)
        for (b in world.buildings) if (b.alive) drawBuilding(c, b, t)
        for (cl in world.colonists) if (cl.alive) drawColonist(c, cl, t)
        for (tk in world.tanks) drawTank(c, tk, t, px, closeUp)
        for (m in world.moles) drawMole(c, m, t)
        for (pr in world.projectiles) drawProjectile(c, pr, px)
        drawParticles(c)
        overlay?.invoke(c)
        for (ft in world.texts) {
            val a = (min(1f, ft.life / 0.5f) * 255).toInt()
            Gfx.text(c, ft.text, ft.x, ft.y, 15f * px, ft.color, alpha = a)
        }
        c.restore()
    }

    private fun drawPlanet(c: Canvas, pl: Planet, px: Float) {
        val g = gfx(pl)
        c.drawCircle(pl.x, pl.y, g.atmoR, g.atmo)
        c.drawPath(g.path, g.body)
        if (pl.scorches.isNotEmpty()) {
            c.save()
            c.clipPath(g.path)
            for (sc in pl.scorches) {
                p.shader = RadialGradient(sc[0], sc[1], sc[2], intArrayOf(0x99201510.toInt(), 0x55201510, 0x00201510), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(sc[0], sc[1], sc[2], p)
            }
            p.shader = null
            c.restore()
        }
        c.drawPath(g.path, g.shade)
        s.shader = null
        s.color = 0x55000000
        s.strokeWidth = 1.4f * px
        c.drawPath(g.path, s)
    }

    private fun orient(c: Canvas, pl: Planet, ang: Float, lift: Float) {
        pl.surfacePoint(ang, lift, tmp)
        c.translate(tmp[0], tmp[1])
        c.rotate(ang / DEG + 90f)
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

    private fun drawTank(c: Canvas, tk: Tank, t: Float, px: Float, closeUp: Boolean) {
        c.save()
        val lx = tk.x + cos(tk.normal) * tk.drawLift
        val ly = tk.y + sin(tk.normal) * tk.drawLift
        c.translate(lx, ly)
        c.rotate(tk.normal / DEG + 90f)
        c.scale(Tank.SIZE, Tank.SIZE)
        TankArt.draw(c, tk.look, tk.color, TankArt.State(
            aim = tk.aim, gunOk = tk.gun, antennaOk = tk.antenna, tracksOk = tk.tracks,
            shield = tk.shield, flash = tk.hitFlash, time = t + tk.idx * 1.7f,
            moving = tk.idx == movingTank, active = tk.idx == activeTank, dead = !tk.alive,
        ))
        c.restore()
        if (!tk.alive) return
        if (!closeUp) {
            val name = names.getOrNull(tk.idx) ?: ""
            val up = 30f * tk.sc + 6f * px
            val nx = lx + cos(tk.normal) * up
            val ny = ly + sin(tk.normal) * up
            if (tk.idx == activeTank) {
                val bob = sin(t * 5f) * 3f * px
                p.color = Gfx.withAlpha(tk.color, 230)
                path.reset()
                val ay = ny + 6f * px + bob
                path.moveTo(nx - 6f * px, ay - 8f * px); path.lineTo(nx + 6f * px, ay - 8f * px); path.lineTo(nx, ay); path.close()
                c.drawPath(path, p)
                Gfx.text(c, name, nx, ny - 6f * px + bob, 13f * px, tk.color)
            } else {
                Gfx.text(c, name, nx, ny, 12f * px, Gfx.withAlpha(tk.color, 200))
            }
        }
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

    private fun drawProjectile(c: Canvas, pr: Projectile, px: Float) {
        val n = pr.trailLen
        if (n > 1) {
            s.strokeWidth = 2.2f * px
            var prevX = 0f; var prevY = 0f
            for (k in 0 until n) {
                val idx = (pr.trailHead - n + k + Projectile.TRAIL) % Projectile.TRAIL
                val x = pr.trail[idx * 2]; val y = pr.trail[idx * 2 + 1]
                if (k > 0) {
                    s.color = Gfx.withAlpha(pr.w.color, (k * 160 / n))
                    c.drawLine(prevX, prevY, x, y, s)
                }
                prevX = x; prevY = y
            }
            if (n > 0) {
                s.color = Gfx.withAlpha(pr.w.color, 160)
                c.drawLine(prevX, prevY, pr.x, pr.y, s)
            }
        }
        val rad = if (pr.sub) 2.2f else 3.2f
        Gfx.glow(c, pr.x, pr.y, rad * 3.2f, Gfx.withAlpha(pr.w.color, 120))
        p.shader = null
        p.color = pr.w.color
        c.drawCircle(pr.x, pr.y, max(rad, 2f * px), p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(pr.x - rad * 0.3f, pr.y - rad * 0.3f, rad * 0.4f, p)
    }

    private fun drawParticles(c: Canvas) {
        p.shader = null
        for (q in world.particles) {
            val f = (q.life / q.max).coerceIn(0f, 1f)
            when (q.type) {
                0, 2 -> {
                    p.color = Gfx.withAlpha(q.color, (255 * min(1f, f * 1.5f)).toInt())
                    c.drawCircle(q.x, q.y, q.size, p)
                }
                1 -> {
                    p.color = Gfx.withAlpha(q.color, ((q.color ushr 24) * f).toInt())
                    c.drawCircle(q.x, q.y, q.size, p)
                }
                3 -> Gfx.glow(c, q.x, q.y, q.size * (0.6f + 0.6f * (1 - f)), Gfx.withAlpha(q.color, (255 * f).toInt()))
                4 -> {
                    Gfx.glow(c, q.x, q.y, q.size * 1.8f, Gfx.withAlpha(q.color, (160 * f).toInt()))
                    p.color = Gfx.withAlpha(0xFFFFF59D.toInt(), (220 * f).toInt())
                    c.drawCircle(q.x, q.y, q.size * 0.45f, p)
                }
                5 -> {
                    s.color = Gfx.withAlpha(q.color, (220 * f).toInt())
                    s.strokeWidth = 2.5f
                    c.drawCircle(q.x, q.y, q.size, s)
                }
            }
        }
    }
}
