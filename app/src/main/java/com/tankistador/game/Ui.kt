package com.tankistador.game

import android.graphics.Canvas
import android.graphics.RectF

/** A tappable rectangle drawn by the game. Supports auto-repeat while held. */
class Button(
    var label: String,
    var style: Style = Style.METAL,
    var repeat: Boolean = false,
    var onClick: () -> Unit = {},
) {
    enum class Style { METAL, GREEN, RED, BLUE, FLAT, TEXT, ROUND_RED }

    val rect = RectF()
    var enabled = true
    var visible = true
    var selected = false
    var pressed = false
    var textSize = 0f
    var textColor = 0
    private var pointer = -1
    private var held = 0f
    private var nextRepeat = 0f

    fun set(l: Float, t: Float, r: Float, b: Float): Button {
        rect.set(l, t, r, b)
        return this
    }

    fun hit(x: Float, y: Float) = visible && enabled && x >= rect.left - 4 && x <= rect.right + 4 && y >= rect.top - 4 && y <= rect.bottom + 4

    fun down(id: Int, x: Float, y: Float): Boolean {
        if (!hit(x, y)) return false
        pressed = true
        pointer = id
        held = 0f
        nextRepeat = 0.35f
        if (repeat) onClick()
        return true
    }

    fun move(id: Int, x: Float, y: Float) {
        if (id == pointer && !hit(x, y) && !repeat) pressed = false
        if (id == pointer && hit(x, y) && !repeat) pressed = true
    }

    fun up(id: Int, x: Float, y: Float): Boolean {
        if (id != pointer) return false
        pointer = -1
        val was = pressed
        pressed = false
        if (was && !repeat && hit(x, y)) {
            onClick()
            return true
        }
        return was
    }

    fun cancel() {
        pointer = -1
        pressed = false
    }

    val isHeld get() = pressed && pointer >= 0

    fun update(dt: Float) {
        if (!repeat || !isHeld || !enabled) return
        held += dt
        if (held >= nextRepeat) {
            onClick()
            nextRepeat = held + if (held > 1.2f) 0.03f else 0.08f
        }
    }

    fun draw(c: Canvas) {
        if (!visible) return
        val l = rect.left; val t = rect.top; val r = rect.right; val b = rect.bottom
        val h = b - t
        val off = if (pressed) 2f else 0f
        val size = if (textSize > 0) textSize else (h * 0.5f).coerceAtMost(30f)
        val alpha = if (enabled) 255 else 110
        when (style) {
            Style.TEXT -> {
                val col = if (textColor != 0) textColor else Gfx.YELLOW
                Gfx.text(c, label, (l + r) / 2, (t + b) / 2 + size * 0.36f + off, size * (if (pressed) 0.95f else 1f),
                    if (selected) Gfx.GREEN else col, alpha = alpha)
                return
            }
            Style.ROUND_RED -> {
                val cx = (l + r) / 2; val cy = (t + b) / 2; val rad = minOf(r - l, b - t) / 2
                Gfx.fill.shader = null
                Gfx.fill.color = 0xFF263238.toInt()
                c.drawCircle(cx, cy + 3, rad, Gfx.fill)
                Gfx.fill.shader = android.graphics.RadialGradient(cx - rad * 0.3f, cy - rad * 0.35f + off, rad * 1.2f,
                    if (enabled) intArrayOf(0xFFFF8A80.toInt(), 0xFFD32F2F.toInt(), 0xFF7F0000.toInt()) else intArrayOf(0xFF9E9E9E.toInt(), 0xFF616161.toInt(), 0xFF424242.toInt()),
                    floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy + off, rad - 3, Gfx.fill)
                Gfx.fill.shader = null
                Gfx.stroke.color = 0xFF1A1A1A.toInt(); Gfx.stroke.strokeWidth = 3f
                c.drawCircle(cx, cy + off, rad - 2, Gfx.stroke)
                Gfx.text(c, label, cx, cy + size * 0.36f + off, size, 0xFFFFFFFF.toInt(), alpha = alpha)
                return
            }
            else -> {}
        }
        val (top, bottom) = when (style) {
            Style.GREEN -> 0xFF66BB6A.toInt() to 0xFF1B5E20.toInt()
            Style.RED -> 0xFFEF5350.toInt() to 0xFF8E1B1B.toInt()
            Style.BLUE -> 0xFF4F7FD6.toInt() to 0xFF1A2F6E.toInt()
            Style.FLAT -> 0xFF2C3A44.toInt() to 0xFF1A242B.toInt()
            else -> 0xFFB0BCC6.toInt() to 0xFF5C6974.toInt()
        }
        Gfx.rr(c, l + 2, t + 3, r + 2, b + 3, 9f, 0x77000000)
        if (pressed) Gfx.gradRect(c, l, t + off, r, b + off, 9f, bottom, top)
        else Gfx.gradRect(c, l, t, r, b, 9f, top, bottom)
        Gfx.rrStroke(c, l, t + off, r, b + off, 9f, if (selected) Gfx.GREEN else 0xFF14191E.toInt(), if (selected) 3f else 2f)
        Gfx.rrStroke(c, l + 2, t + 2 + off, r - 2, b - 2 + off, 7f, 0x40FFFFFF, 1.5f)
        val col = if (textColor != 0) textColor else if (style == Style.METAL) 0xFF1B232A.toInt() else 0xFFFFFFFF.toInt()
        Gfx.text(c, label, (l + r) / 2, (t + b) / 2 + size * 0.36f + off, size, col, shadow = style != Style.METAL, alpha = alpha)
        if (!enabled) Gfx.rr(c, l, t, r, b, 9f, 0x55000000)
    }
}

class ButtonSet {
    val list = ArrayList<Button>()

    fun add(b: Button): Button {
        list.add(b)
        return b
    }

    fun clear() = list.clear()

    fun down(id: Int, x: Float, y: Float): Boolean {
        for (b in list.asReversed()) if (b.down(id, x, y)) return true
        return false
    }

    fun move(id: Int, x: Float, y: Float) = list.forEach { it.move(id, x, y) }

    fun up(id: Int, x: Float, y: Float): Boolean {
        var any = false
        for (b in list.toList()) if (b.up(id, x, y)) any = true
        return any
    }

    fun cancelAll() = list.forEach { it.cancel() }
    fun update(dt: Float) = list.forEach { it.update(dt) }
    fun draw(c: Canvas) = list.forEach { it.draw(c) }
}
