package com.tankistador.game

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the cartoon tanks. Coordinates are local: origin at the tank centre, "up" is -y,
 * the body is roughly 24 units wide before [TankLook.scale].
 */
object TankArt {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val r = RectF()
    private const val OUTLINE = 0xFF1A1F24.toInt()

    class State(
        val aim: Float = 60f,
        val gunOk: Float = 1f,
        val antennaOk: Float = 1f,
        val tracksOk: Float = 1f,
        val shield: Float = 0f,
        val flash: Float = 0f,
        val time: Float = 0f,
        val moving: Boolean = false,
        val active: Boolean = false,
        val dead: Boolean = false,
    )

    fun draw(c: Canvas, look: TankLook, color: Int, st: State) {
        c.save()
        c.scale(look.scale, look.scale)
        val body = if (st.dead) 0xFF424242.toInt() else color
        val dark = Gfx.darken(body, 0.45f)
        val light = Gfx.lighten(body, 0.35f)

        antenna(c, look, st)
        barrel(c, look, st, dark)
        turret(c, look, body, light, dark, st)
        hull(c, look, body, light, dark)
        tracks(c, look, st)
        extras(c, look, body, dark, st)

        if (st.flash > 0f) {
            p.shader = null
            p.color = Gfx.withAlpha(0xFFFFFFFF.toInt(), (st.flash / 0.35f * 200).toInt())
            c.drawCircle(0f, -1f, 13f, p)
        }
        if (st.shield > 0f) {
            p.shader = RadialGradient(0f, -2f, 19f, 0x0040C4FF, 0x6640C4FF, Shader.TileMode.CLAMP)
            c.drawCircle(0f, -2f, 19f, p)
            p.shader = null
            s.color = Gfx.withAlpha(0xFF80DEEA.toInt(), 150 + (60 * sin(st.time * 6)).toInt())
            s.strokeWidth = 1.2f
            c.drawCircle(0f, -2f, 19f, s)
        }
        c.restore()
    }

    private fun tracks(c: Canvas, look: TankLook, st: State) {
        val w = 13f
        p.shader = LinearGradient(0f, 2f, 0f, 8f, 0xFF4A4F55.toInt(), 0xFF1E2226.toInt(), Shader.TileMode.CLAMP)
        r.set(-w, 1.5f, w, 8.5f)
        c.drawRoundRect(r, 3.5f, 3.5f, p)
        p.shader = null
        s.color = OUTLINE; s.strokeWidth = 0.9f
        c.drawRoundRect(r, 3.5f, 3.5f, s)
        val n = look.wheels
        val step = (2 * w - 6f) / (n - 1)
        val spin = if (st.moving) st.time * 8f else 0f
        for (i in 0 until n) {
            val x = -w + 3f + i * step
            p.color = 0xFF9EA7AE.toInt()
            c.drawCircle(x, 5f, 2.3f, p)
            p.color = 0xFF5F6870.toInt()
            c.drawCircle(x, 5f, 1.1f, p)
            s.color = 0xFF2B3036.toInt(); s.strokeWidth = 0.5f
            c.drawLine(x + cos(spin + i) * 2f, 5f + sin(spin + i) * 2f, x - cos(spin + i) * 2f, 5f - sin(spin + i) * 2f, s)
        }
        if (st.tracksOk <= 0f) {
            s.color = 0xFFFF7043.toInt(); s.strokeWidth = 0.8f
            c.drawLine(w - 4, 2f, w - 1, 8f, s)
            c.drawLine(-w + 2, 3f, -w + 5, 8f, s)
        }
    }

    private fun hull(c: Canvas, look: TankLook, body: Int, light: Int, dark: Int) {
        path.reset()
        when (look.hull) {
            1 -> { // wedge
                path.moveTo(-14f, 2.5f); path.lineTo(-12f, -3.5f); path.lineTo(6f, -4.5f); path.lineTo(15f, 0f); path.lineTo(13f, 2.5f)
            }
            2 -> { // rounded beetle
                path.moveTo(-14f, 2.5f)
                path.cubicTo(-14f, -6f, 14f, -6f, 14f, 2.5f)
            }
            3 -> { // boxy
                path.moveTo(-14f, 2.5f); path.lineTo(-14f, -4.5f); path.lineTo(-11f, -6f); path.lineTo(11f, -6f); path.lineTo(14f, -4.5f); path.lineTo(14f, 2.5f)
            }
            else -> {
                path.moveTo(-14f, 2.5f); path.lineTo(-11f, -4f); path.lineTo(11f, -4f); path.lineTo(14f, 2.5f)
            }
        }
        path.close()
        p.shader = LinearGradient(0f, -6f, 0f, 3f, light, dark, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        s.color = OUTLINE; s.strokeWidth = 1f
        c.drawPath(path, s)
        // Stripe and bolts.
        p.color = Gfx.withAlpha(0xFFFFFFFF.toInt(), 60)
        r.set(-10f, -1.8f, 10f, -0.6f)
        c.drawRect(r, p)
        p.color = Gfx.darken(body, 0.6f)
        for (x in intArrayOf(-8, -3, 3, 8)) c.drawCircle(x.toFloat(), 1f, 0.6f, p)
    }

    private fun turret(c: Canvas, look: TankLook, body: Int, light: Int, dark: Int, st: State) {
        val ty = if (look.hull == 3) -6f else -4f
        when (look.turret) {
            0 -> { // glass dome with a little pilot
                p.color = 0xFF263238.toInt()
                r.set(-6.5f, ty - 1.5f, 6.5f, ty + 1.5f)
                c.drawRoundRect(r, 1.5f, 1.5f, p)
                pilot(c, 0f, ty - 4f, st)
                p.shader = RadialGradient(-2f, ty - 7f, 9f, 0x88E1F5FE.toInt(), 0x3381D4FA, Shader.TileMode.CLAMP)
                r.set(-6f, ty - 11f, 6f, ty + 1f)
                c.drawArc(r, 180f, 180f, true, p)
                p.shader = null
                s.color = 0xCCB3E5FC.toInt(); s.strokeWidth = 0.8f
                c.drawArc(r, 180f, 180f, false, s)
                p.color = 0xAAFFFFFF.toInt()
                c.drawCircle(-2.8f, ty - 7.5f, 1.1f, p)
            }
            1 -> { // box turret
                p.shader = LinearGradient(0f, ty - 7f, 0f, ty, light, body, Shader.TileMode.CLAMP)
                r.set(-6.5f, ty - 6.5f, 6.5f, ty + 0.5f)
                c.drawRoundRect(r, 1.5f, 1.5f, p)
                p.shader = null
                s.color = OUTLINE; s.strokeWidth = 0.9f
                c.drawRoundRect(r, 1.5f, 1.5f, s)
                p.color = 0xFF263238.toInt()
                r.set(-3.5f, ty - 5f, 3.5f, ty - 3f)
                c.drawRoundRect(r, 1f, 1f, p)
                eyes(c, 0f, ty - 4f, st)
            }
            else -> { // round turret
                p.shader = RadialGradient(-2f, ty - 6f, 8f, light, dark, Shader.TileMode.CLAMP)
                c.drawCircle(0f, ty - 2.5f, 6f, p)
                p.shader = null
                s.color = OUTLINE; s.strokeWidth = 0.9f
                c.drawCircle(0f, ty - 2.5f, 6f, s)
                p.color = 0xFF263238.toInt()
                r.set(-4f, ty - 5f, 4f, ty - 2.5f)
                c.drawRoundRect(r, 1.2f, 1.2f, p)
                eyes(c, 0f, ty - 3.7f, st)
            }
        }
    }

    /** A round-headed little pilot with goggles. */
    private fun pilot(c: Canvas, x: Float, y: Float, st: State) {
        p.color = 0xFFFFCC80.toInt()
        c.drawCircle(x, y, 3.4f, p)
        p.color = 0xFF6D4C41.toInt()
        r.set(x - 3.6f, y - 3.8f, x + 3.6f, y - 0.6f)
        c.drawArc(r, 180f, 180f, true, p)
        eyes(c, x, y + 0.2f, st)
        s.color = 0xFF8D6E63.toInt(); s.strokeWidth = 0.5f
        r.set(x - 1.3f, y + 0.8f, x + 1.3f, y + 2.2f)
        c.drawArc(r, 20f, 140f, false, s)
    }

    private fun eyes(c: Canvas, x: Float, y: Float, st: State) {
        val blink = ((st.time * 0.7f) % 3f) > 2.9f
        val look = (st.aim - 90f) / 90f * -0.6f
        p.color = 0xFFFFFFFF.toInt()
        if (blink) {
            r.set(x - 2.6f, y - 0.2f, x - 0.4f, y + 0.3f); c.drawRect(r, p)
            r.set(x + 0.4f, y - 0.2f, x + 2.6f, y + 0.3f); c.drawRect(r, p)
            return
        }
        c.drawCircle(x - 1.4f, y, 1.15f, p)
        c.drawCircle(x + 1.4f, y, 1.15f, p)
        p.color = 0xFF111111.toInt()
        c.drawCircle(x - 1.4f + look, y, 0.55f, p)
        c.drawCircle(x + 1.4f + look, y, 0.55f, p)
    }

    private fun barrel(c: Canvas, look: TankLook, st: State, dark: Int) {
        val ty = (if (look.hull == 3) -6f else -4f) - 3f
        val len = if (st.gunOk <= 0f) look.barrelLen * 0.35f else look.barrelLen
        val a = -st.aim * DEG
        c.save()
        c.translate(0f, ty)
        c.rotate(a / DEG)
        val offs = if (look.doubleBarrel) floatArrayOf(-look.barrelW * 0.55f, look.barrelW * 0.55f) else floatArrayOf(0f)
        for (o in offs) {
            p.shader = LinearGradient(0f, o - look.barrelW / 2, 0f, o + look.barrelW / 2, 0xFF90A4AE.toInt(), 0xFF37474F.toInt(), Shader.TileMode.CLAMP)
            r.set(0f, o - look.barrelW / 2, len, o + look.barrelW / 2)
            c.drawRoundRect(r, 0.8f, 0.8f, p)
            p.shader = null
            s.color = OUTLINE; s.strokeWidth = 0.6f
            c.drawRoundRect(r, 0.8f, 0.8f, s)
            if (st.gunOk > 0f) {
                p.color = dark
                r.set(len - 2.2f, o - look.barrelW / 2 - 0.6f, len, o + look.barrelW / 2 + 0.6f)
                c.drawRect(r, p)
            } else {
                p.color = 0xFFFF7043.toInt()
                c.drawCircle(len, o, 0.8f, p)
            }
        }
        c.restore()
    }

    private fun antenna(c: Canvas, look: TankLook, st: State) {
        val baseX = -9f
        val baseY = if (look.hull == 3) -5.5f else -3f
        val h = if (st.antennaOk <= 0f) look.antennaH * 0.3f else look.antennaH
        val sway = sin(st.time * 2.3f) * 1.2f
        s.color = 0xFF263238.toInt(); s.strokeWidth = 0.8f
        if (st.antennaOk <= 0f) {
            c.drawLine(baseX, baseY, baseX - 1f, baseY - h, s)
            c.drawLine(baseX - 1f, baseY - h, baseX - 4f, baseY - h + 2f, s)
            return
        }
        c.drawLine(baseX, baseY, baseX + sway, baseY - h, s)
        if (look.dish) {
            p.color = 0xFFCFD8DC.toInt()
            r.set(baseX + sway - 3.5f, baseY - h - 1.5f, baseX + sway + 3.5f, baseY - h + 2.5f)
            c.drawArc(r, 0f, 180f, true, p)
            s.strokeWidth = 0.5f
            c.drawArc(r, 0f, 180f, true, s)
        }
        val on = ((st.time * 2f).toInt() % 2 == 0) || !st.active
        p.color = if (on) (if (st.active) 0xFF76FF03.toInt() else 0xFFFF5252.toInt()) else 0xFF33691E.toInt()
        c.drawCircle(baseX + sway, baseY - h - (if (look.dish) 2f else 0f), 1.4f, p)
    }

    private fun extras(c: Canvas, look: TankLook, body: Int, dark: Int, st: State) {
        when (look.extra) {
            1 -> { // spikes
                p.color = 0xFFB0BEC5.toInt()
                for (i in -2..2) {
                    path.reset()
                    val x = i * 5f
                    path.moveTo(x - 1.5f, -4f); path.lineTo(x, -8.5f); path.lineTo(x + 1.5f, -4f); path.close()
                    c.drawPath(path, p)
                }
            }
            2 -> { // chimney with smoke
                p.color = 0xFF455A64.toInt()
                r.set(8f, -10f, 10.5f, -4f)
                c.drawRect(r, p)
                p.color = 0x66CFD8DC
                val k = (st.time % 1f)
                c.drawCircle(9.2f + k * 2f, -11f - k * 5f, 1.2f + k * 1.5f, p)
            }
            3 -> { // horn
                p.color = 0xFFFFF8E1.toInt()
                path.reset()
                path.moveTo(12f, -1f); path.lineTo(19f, -5f); path.lineTo(13.5f, 1.5f); path.close()
                c.drawPath(path, p)
                s.color = OUTLINE; s.strokeWidth = 0.6f
                c.drawPath(path, s)
            }
            4 -> { // crown
                p.color = 0xFFFFD54F.toInt()
                path.reset()
                val y = -15.5f
                path.moveTo(-4f, y + 3f); path.lineTo(-4f, y); path.lineTo(-2f, y + 1.5f); path.lineTo(0f, y - 1f)
                path.lineTo(2f, y + 1.5f); path.lineTo(4f, y); path.lineTo(4f, y + 3f); path.close()
                c.drawPath(path, p)
            }
        }
    }
}
