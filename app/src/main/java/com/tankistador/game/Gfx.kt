package com.tankistador.game

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared paints and small drawing helpers for the hand-drawn UI. */
object Gfx {
    val bold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    const val YELLOW = 0xFFFFD54F.toInt()
    const val GREEN = 0xFF76FF03.toInt()
    const val LCD = 0xFF7CFC5A.toInt()
    const val CYAN = 0xFF4DD0E1.toInt()
    const val GREY = 0xFF9E9E9E.toInt()
    const val RED = 0xFFFF5252.toInt()

    enum class Align { LEFT, CENTER, RIGHT }

    fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Align = Align.CENTER, shadow: Boolean = true, font: Typeface = bold, alpha: Int = 255,
    ) {
        text.typeface = font
        text.textSize = size
        text.textAlign = when (align) {
            Align.LEFT -> Paint.Align.LEFT; Align.CENTER -> Paint.Align.CENTER; Align.RIGHT -> Paint.Align.RIGHT
        }
        if (shadow) {
            text.color = Color.BLACK
            text.alpha = alpha * 3 / 4
            c.drawText(s, x + size * 0.07f, y + size * 0.07f, text)
        }
        text.color = color
        text.alpha = alpha * Color.alpha(color) / 255
        c.drawText(s, x, y, text)
        text.alpha = 255
    }

    fun measure(s: String, size: Float, font: Typeface = bold): Float {
        text.typeface = font
        text.textSize = size
        return text.measureText(s)
    }

    fun wrap(s: String, width: Float, size: Float): List<String> {
        val out = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (measure(cand, size) > width && line.isNotEmpty()) {
                    out.add(line)
                    line = word
                } else line = cand
            }
            out.add(line)
        }
        return out
    }

    fun textBlock(c: Canvas, s: String, cx: Float, top: Float, width: Float, size: Float, color: Int, align: Align = Align.CENTER): Float {
        var y = top + size
        val x = when (align) { Align.LEFT -> cx - width / 2; Align.CENTER -> cx; Align.RIGHT -> cx + width / 2 }
        for (line in wrap(s, width, size)) {
            text(c, line, x, y, size, color, align, shadow = false)
            y += size * 1.22f
        }
        return y - size
    }

    fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        fill.shader = null
        fill.color = color
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, rad, rad, fill)
    }

    fun rrStroke(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int, w: Float) {
        stroke.color = color
        stroke.strokeWidth = w
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, rad, rad, stroke)
    }

    fun gradRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, top: Int, bottom: Int) {
        fill.shader = LinearGradient(0f, t, 0f, b, top, bottom, Shader.TileMode.CLAMP)
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null
    }

    fun rivet(c: Canvas, x: Float, y: Float, r: Float = 4f) {
        fill.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.2f, 0xFFF5F5F5.toInt(), 0xFF5A6470.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
    }

    /** Brushed steel panel with rivets in the corners. */
    fun metalPanel(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float = 14f, tint: Int = 0) {
        rr(c, l + 3, t + 4, r + 3, b + 4, rad, 0x66000000)
        gradRect(c, l, t, r, b, rad, if (tint != 0) tint else 0xFF7C8A99.toInt(), 0xFF39434F.toInt())
        rrStroke(c, l + 2, t + 2, r - 2, b - 2, rad - 1, 0x55FFFFFF, 2f)
        rrStroke(c, l, t, r, b, rad, 0xFF1E252D.toInt(), 3f)
        val inset = rad * 0.75f + 4
        rivet(c, l + inset, t + inset); rivet(c, r - inset, t + inset)
        rivet(c, l + inset, b - inset); rivet(c, r - inset, b - inset)
    }

    /** Dark glass screen with a coloured frame (menus, shop info). */
    fun screen(c: Canvas, l: Float, t: Float, r: Float, b: Float, frame: Int = 0xFF2E7D6B.toInt(), rad: Float = 16f) {
        rr(c, l - 10, t - 10, r + 10, b + 10, rad + 8, 0xFFB0BEC5.toInt())
        gradRect(c, l - 10, t - 10, r + 10, b + 10, rad + 8, 0xFFE3E9EE.toInt(), 0xFF8C9AA6.toInt())
        rrStroke(c, l - 10, t - 10, r + 10, b + 10, rad + 8, 0xFF37474F.toInt(), 3f)
        gradRect(c, l, t, r, b, rad, 0xFF0F2A2E.toInt(), 0xFF07161A.toInt())
        rrStroke(c, l, t, r, b, rad, frame, 3f)
        // Gloss.
        fill.shader = LinearGradient(0f, t, 0f, t + (b - t) * 0.45f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        rect.set(l + 4, t + 4, r - 4, t + (b - t) * 0.45f)
        c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null
        rivet(c, l - 2, t - 2, 4.5f); rivet(c, r + 2, t - 2, 4.5f); rivet(c, l - 2, b + 2, 4.5f); rivet(c, r + 2, b + 2, 4.5f)
    }

    fun lcd(c: Canvas, l: Float, t: Float, r: Float, b: Float, s: String, color: Int = LCD, size: Float = (b - t) * 0.72f) {
        rr(c, l, t, r, b, 6f, 0xFF0B140B.toInt())
        rrStroke(c, l, t, r, b, 6f, 0xFF263238.toInt(), 2f)
        text(c, s, (l + r) / 2, (t + b) / 2 + size * 0.36f, size, color, Align.CENTER, shadow = false, font = mono)
    }

    fun bar(c: Canvas, l: Float, t: Float, r: Float, b: Float, frac: Float, color: Int) {
        rr(c, l, t, r, b, (b - t) / 2, 0xFF1B2228.toInt())
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.01f) {
            gradRect(c, l + 2, t + 2, l + 2 + (r - l - 4) * f, b - 2, (b - t - 4) / 2, lighten(color, 0.35f), color)
        }
        rrStroke(c, l, t, r, b, (b - t) / 2, 0xFF0D1114.toInt(), 1.5f)
    }

    fun healthColor(f: Float) = when {
        f > 0.6f -> 0xFF43A047.toInt(); f > 0.3f -> 0xFFFFB300.toInt(); else -> 0xFFE53935.toInt()
    }

    fun lighten(c: Int, f: Float): Int {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return Color.argb(Color.alpha(c), (r + (255 - r) * f).toInt(), (g + (255 - g) * f).toInt(), (b + (255 - b) * f).toInt())
    }

    fun darken(c: Int, f: Float): Int {
        return Color.argb(Color.alpha(c), (Color.red(c) * (1 - f)).toInt(), (Color.green(c) * (1 - f)).toInt(), (Color.blue(c) * (1 - f)).toInt())
    }

    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        fill.shader = RadialGradient(x, y, r, color, withAlpha(color, 0), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
    }

    /** Light that adds to whatever is underneath: fire, sparks, flashes. */
    val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) }

    fun addGlow(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        if (r <= 0.1f) return
        light.shader = RadialGradient(x, y, r, color, withAlpha(color, 0), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, light)
        light.shader = null
    }

    val blur = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
}

/** Procedural textures: planets, nebulae, stars and TV noise. */
object Tex {
    private fun hash(ix: Int, iy: Int, seed: Int): Float {
        var h = ix * 374761393 + iy * 668265263 + seed * 982451653
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    fun noise(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val a = hash(x0, y0, seed); val b = hash(x0 + 1, y0, seed)
        val c = hash(x0, y0 + 1, seed); val d = hash(x0 + 1, y0 + 1, seed)
        return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy
    }

    fun fbm(x: Float, y: Float, seed: Int, oct: Int = 5): Float {
        var sum = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
        for (i in 0 until oct) {
            sum += noise(x * f, y * f, seed + i * 101) * amp
            norm += amp
            amp *= 0.5f; f *= 2.03f
        }
        return sum / norm
    }

    private fun ramp(stops: IntArray, t: Float): Int {
        val tt = t.coerceIn(0f, 0.9999f) * (stops.size - 1)
        val i = tt.toInt()
        val f = tt - i
        val a = stops[i]; val b = stops[min(i + 1, stops.size - 1)]
        val r = ((a shr 16 and 255) * (1 - f) + (b shr 16 and 255) * f).toInt()
        val g = ((a shr 8 and 255) * (1 - f) + (b shr 8 and 255) * f).toInt()
        val bl = ((a and 255) * (1 - f) + (b and 255) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private val palettes = arrayOf(
        intArrayOf(0x4A4A50, 0x77777E, 0xA8A8AD, 0xD4D4D6, 0xF0F0F0),
        intArrayOf(0x7F98AE, 0xB3C7D6, 0xDCE7EF, 0xF6FAFC),
        intArrayOf(0x7A4B24, 0xB07C45, 0xD6AE73, 0xEBD2A2, 0xF7EBCF),
        intArrayOf(0x5A1710, 0x8F2E22, 0xC2503C, 0xE07C62, 0xF2A88E),
        intArrayOf(0x1C3D78, 0x2C67AD, 0x3E7D35, 0x7AA356, 0xC7B884),
        intArrayOf(0x7E3A16, 0xB8662F, 0xE0A061, 0xF4D2A0),
        intArrayOf(0x24133D, 0x4D2C7A, 0x7F55B5, 0xB998E3),
        intArrayOf(0x161212, 0x2E2424, 0x4A3B36, 0x6B5A52),
    )

    private val cache = HashMap<Long, Bitmap>()

    /**
     * Planet surface with baked relief: a height field is built first, then every pixel is lit
     * from the upper left (the same direction as the scene's sun) using the local slope.
     */
    fun planet(style: Int, seed: Int, size: Int = 320): Bitmap {
        val key = style.toLong() * 1_000_003L + seed + size * 7L
        cache[key]?.let { return it }
        val px = IntArray(size * size)
        val hgt = FloatArray(size * size)
        val col0 = IntArray(size * size)
        val pal = palettes[style % palettes.size]
        val sc = 6f / size
        val craters = if (style == 0 || style == 7 || style == 3) 16 else 5
        val cr = FloatArray(craters * 3)
        for (i in 0 until craters) {
            cr[i * 3] = hash(i, 1, seed) * size
            cr[i * 3 + 1] = hash(i, 2, seed) * size
            cr[i * 3 + 2] = (0.025f + hash(i, 3, seed) * 0.09f) * size
        }
        for (y in 0 until size) for (x in 0 until size) {
            val fx = x * sc; val fy = y * sc
            var v: Float
            var h: Float
            when (style) {
                1 -> { v = fbm(fx * 0.4f, fy * 2.2f, seed) * 0.8f + fbm(fx, fy, seed + 7) * 0.2f; h = v * 0.6f }
                2 -> {
                    val w = fbm(fx * 0.5f, fy * 0.5f, seed + 3) * 4f
                    v = 0.5f + 0.5f * sin(fy * 1.6f + w)
                    val d = fbm(fx * 1.5f, fy * 1.5f, seed)
                    v = v * 0.6f + d * 0.4f
                    h = d
                }
                4 -> {
                    val hh = fbm(fx * 0.7f, fy * 0.7f, seed)
                    v = if (hh < 0.5f) hh * 0.75f else 0.5f + (hh - 0.5f) * 1.0f
                    h = max(hh, 0.5f)
                }
                5 -> {
                    val w = fbm(fx * 0.6f, fy * 0.6f, seed) * 1.6f
                    v = 0.5f + 0.5f * sin(fy * 3.1f + w * 2f)
                    h = v * 0.25f
                }
                7 -> { v = fbm(fx, fy, seed) * 0.8f; h = v }
                else -> { v = fbm(fx, fy, seed); h = v }
            }
            for (i in 0 until craters) {
                val dx = x - cr[i * 3]; val dy = y - cr[i * 3 + 1]
                val d = sqrt(dx * dx + dy * dy) / cr[i * 3 + 2]
                if (d < 1.3f) {
                    h += if (d < 0.9f) -0.25f * (1f - d * d / 0.81f) else 0.12f * (1f - abs(d - 1.05f) / 0.25f).coerceAtLeast(0f)
                }
            }
            hgt[y * size + x] = h
            var c = ramp(pal, v)
            if (style == 7) {
                val crack = abs(fbm(fx * 1.3f, fy * 1.3f, seed + 9) - 0.5f)
                if (crack < 0.035f) c = ramp(intArrayOf(0xFFEB3B, 0xFF6F00, 0xD84315), crack / 0.035f) or 0x01000000
            }
            col0[y * size + x] = c
        }
        val bump = when (style) { 5 -> 3f; 1 -> 6f; else -> 11f }
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            val gx = hgt[y * size + min(x + 1, size - 1)] - hgt[y * size + max(x - 1, 0)]
            val gy = hgt[min(y + 1, size - 1) * size + x] - hgt[max(y - 1, 0) * size + x]
            // Light from the upper left: slopes facing it brighten, the rest darken.
            val lit = (-(gx * -0.6f + gy * -0.8f)) * bump
            var c = col0[i]
            val glowing = style == 7 && (c ushr 24) == 0x01
            c = c or (0xFF shl 24)
            px[i] = if (glowing) c else shade(c, lit.coerceIn(-0.45f, 0.4f))
        }
        val b = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        cache[key] = b
        return b
    }

    /** White wisps on transparent background, drawn slowly turning over some planets. */
    fun clouds(seed: Int, size: Int = 256): Bitmap {
        val px = IntArray(size * size)
        val sc = 5f / size
        for (y in 0 until size) for (x in 0 until size) {
            val n = fbm(x * sc * 1.3f, y * sc * 3f, seed + 31)
            val a = ((n - 0.52f) * 3.2f).coerceIn(0f, 0.85f)
            px[y * size + x] = ((a * 255).toInt() shl 24) or 0xF4F8FF
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun shade(c: Int, k: Float): Int {
        fun ch(v: Int) = (if (k > 0) v + (255 - v) * k else v * (1 + k)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(c shr 16 and 255) shl 16) or (ch(c shr 8 and 255) shl 8) or ch(c and 255)
    }

    /** Soft coloured clouds on transparent background. */
    fun nebula(seed: Int, w: Int = 400, h: Int = 220): Bitmap {
        val px = IntArray(w * h)
        val colors = arrayOf(
            intArrayOf(0x2E7D32, 0xC62828), intArrayOf(0x6A1B9A, 0xD81B60), intArrayOf(0x1565C0, 0x00897B),
            intArrayOf(0xE65100, 0x6D4C41), intArrayOf(0x283593, 0x8E24AA),
        )
        val pair = colors[Math.floorMod(seed, colors.size)]
        val blobs = 2 + Math.floorMod(seed / 7, 2)
        val bx = FloatArray(blobs) { 0.15f + hash(it, 11, seed) * 0.7f }
        val by = FloatArray(blobs) { 0.15f + hash(it, 12, seed) * 0.7f }
        val br = FloatArray(blobs) { 0.12f + hash(it, 13, seed) * 0.14f }
        for (y in 0 until h) for (x in 0 until w) {
            val u = x.toFloat() / w; val v = y.toFloat() / h
            var mask = 0f
            for (i in 0 until blobs) {
                val dx = (u - bx[i]) * 1.7f; val dy = v - by[i]
                val d = sqrt(dx * dx + dy * dy) / br[i]
                mask = max(mask, (1f - d).coerceAtLeast(0f))
            }
            val n = fbm(u * 6f, v * 4f, seed)
            val a = (mask * (0.4f + n * 1.2f) - 0.15f).coerceIn(0f, 0.85f)
            val mix = fbm(u * 3f + 5f, v * 3f, seed + 5)
            val c = ramp(intArrayOf(pair[0], pair[1]), mix)
            px[y * w + x] = (c and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun stars(seed: Int, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF020308.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = java.util.Random(seed.toLong())
        val n = w * h / 900
        repeat(n) {
            val x = r.nextFloat() * w; val y = r.nextFloat() * h
            val s = r.nextFloat()
            val size = if (s > 0.97f) 1.8f else if (s > 0.85f) 1.2f else 0.7f
            val tint = when (r.nextInt(5)) { 0 -> 0xFFBBDEFB.toInt(); 1 -> 0xFFFFF9C4.toInt(); 2 -> 0xFFFFCCBC.toInt(); else -> Color.WHITE }
            p.color = tint
            p.alpha = (90 + r.nextInt(165))
            c.drawCircle(x, y, size, p)
            if (s > 0.985f) {
                p.alpha = 60
                c.drawRect(x - 5, y - 0.4f, x + 5, y + 0.4f, p)
                c.drawRect(x - 0.4f, y - 5, x + 0.4f, y + 5, p)
            }
        }
        return b
    }

    private var noiseFrames: Array<Bitmap>? = null

    fun tvNoise(i: Int): Bitmap {
        val frames = noiseFrames ?: Array(4) { k ->
            val w = 96; val h = 56
            val r = java.util.Random(k * 31L + 5)
            val px = IntArray(w * h) {
                val v = 40 + r.nextInt(200)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }.also { noiseFrames = it }
        return frames[Math.floorMod(i, frames.size)]
    }

    /** A sphere with lighting drawn from a texture; used for decorative planets in menus. */
    fun drawBall(c: Canvas, tex: Bitmap, x: Float, y: Float, r: Float, rot: Float = 0f) {
        val p = Gfx.bmp
        val m = android.graphics.Matrix()
        m.setScale(2 * r / tex.width, 2 * r / tex.height)
        m.postRotate(rot, r, r)
        m.postTranslate(x - r, y - r)
        val sh = android.graphics.BitmapShader(tex, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        sh.setLocalMatrix(m)
        p.shader = sh
        c.drawCircle(x, y, r, p)
        p.shader = RadialGradient(x - r * 0.35f, y - r * 0.4f, r * 1.6f,
            intArrayOf(0x30FFFFFF, 0x00000000, 0xAA000000.toInt(), 0xEE000000.toInt()), floatArrayOf(0f, 0.3f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
    }

    fun sunRays(c: Canvas, x: Float, y: Float, r: Float, t: Float) {
        val p = Gfx.fill
        p.color = 0xFFFFEB3B.toInt()
        for (i in 0 until 12) {
            val a = i * PI.toFloat() / 6 + t * 0.3f
            val l = r * (1.45f + 0.15f * sin(t * 3 + i))
            val path = android.graphics.Path()
            path.moveTo(x + cos(a - 0.12f) * r * 0.9f, y + sin(a - 0.12f) * r * 0.9f)
            path.lineTo(x + cos(a) * l, y + sin(a) * l)
            path.lineTo(x + cos(a + 0.12f) * r * 0.9f, y + sin(a + 0.12f) * r * 0.9f)
            path.close()
            c.drawPath(path, p)
        }
        p.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r, 0xFFFFF9C4.toInt(), 0xFFFFB300.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
    }
}
