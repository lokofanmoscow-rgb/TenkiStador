package com.tankistador.game

import android.graphics.Canvas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists the save slots. With a [toSave] match the player picks the row to save into;
 * without one the rows are loaded (and can be deleted).
 */
class SaveSlotsScene(game: Game, private val toSave: Match?, private val onBack: () -> Unit) : Scene(game) {
    private val saving = toSave != null
    private val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))

    /** Row ids: -1 is the autosave, 0..SLOTS-1 are the numbered slots. */
    private val rows: List<Int> = if (saving) (0 until Storage.SLOTS).toList() else listOf(-1) + (0 until Storage.SLOTS)
    private val rowRects = ArrayList<android.graphics.RectF>()
    private val delRects = ArrayList<android.graphics.RectF>()
    private var confirm = -2
    private var confirmDelete = false
    private var toast = ""
    private var toastT = 0f
    private var pressed = -2

    private fun matchFor(row: Int): Match? = if (row < 0) game.store.loadMatch() else game.store.loadSlot(row)
    private fun savedAt(row: Int): Long = if (row < 0) game.store.autoSavedAt() else game.store.slotSavedAt(row)

    override fun layout() {
        buttons.clear()
        rowRects.clear()
        delRects.clear()
        val cx = vw / 2
        val top = 104f
        val h = if (saving) 54f else 50f
        for ((k, _) in rows.withIndex()) {
            val y = top + k * (h + 6)
            rowRects.add(android.graphics.RectF(cx - 520, y, cx + 520, y + h))
            delRects.add(android.graphics.RectF(cx + 452, y + 6, cx + 512, y + h - 6))
        }
        buttons.add(Button("Назад", Button.Style.BLUE) { onBack() }.set(cx - 110, vh - 66, cx + 110, vh - 16)).textSize = 22f
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (toastT > 0f) toastT -= dt
    }

    override fun back() = onBack()

    override fun down(id: Int, x: Float, y: Float) {
        pressed = rowRects.indexOfFirst { it.contains(x, y) }
        if (pressed < 0) pressed = -2
        super.down(id, x, y)
    }

    override fun up(id: Int, x: Float, y: Float) {
        super.up(id, x, y)
        val k = rowRects.indexOfFirst { it.contains(x, y) }
        if (k < 0 || k != pressed) {
            pressed = -2
            return
        }
        pressed = -2
        game.sound.play("click", 0.5f)
        val row = rows[k]
        val m = matchFor(row)
        if (saving) {
            if (m != null && confirm != row) {
                confirm = row
                confirmDelete = false
                return
            }
            game.store.saveSlot(row, toSave!!)
            game.sound.play("cash", 0.7f)
            confirm = -2
            toast = "Сохранено в слот ${row + 1}"
            toastT = 1.6f
            return
        }
        if (m == null) return
        if (delRects[k].contains(x, y) && row >= 0) {
            if (confirm == row && confirmDelete) {
                game.store.deleteSlot(row)
                confirm = -2
                toast = "Слот ${row + 1} очищен"
                toastT = 1.4f
            } else {
                confirm = row
                confirmDelete = true
            }
            return
        }
        if (!Flow.resumeMatch(game, m)) {
            toast = "Эта игра уже закончена"
            toastT = 1.6f
        }
    }

    private fun describe(m: Match): String {
        val names = m.players.joinToString("  ${m.players[0].roundWins}:${m.players.getOrNull(1)?.roundWins ?: 0}  ") { it.name }
        val round = minOf(m.round, m.totalRounds)
        val where = if (m.stage == "battle") "бой" else "базар"
        return "$names · раунд $round из ${m.totalRounds} · $where"
    }

    override fun draw(c: Canvas) {
        game.drawStars(c)
        val cx = vw / 2
        Gfx.screen(c, cx - 550, 24f, cx + 550, vh - 82)
        Gfx.text(c, if (saving) "СОХРАНИТЬ ИГРУ" else "ЗАГРУЗИТЬ ИГРУ", cx, 72f, 34f, Gfx.YELLOW)
        Gfx.text(c, if (saving) "Выберите строку для сохранения" else "Выберите сохранение",
            cx + 520, 70f, 15f, 0xFF90A4AE.toInt(), Gfx.Align.RIGHT)
        for ((k, row) in rows.withIndex()) {
            val r = rowRects[k]
            val m = matchFor(row)
            val isConfirm = confirm == row
            val bg = when {
                pressed == k -> 0xFF37474F.toInt()
                isConfirm -> 0xFF4E2A12.toInt()
                k % 2 == 0 -> 0xFF15242B.toInt()
                else -> 0xFF101C22.toInt()
            }
            Gfx.rr(c, r.left, r.top, r.right, r.bottom, 10f, bg)
            Gfx.rrStroke(c, r.left, r.top, r.right, r.bottom, 10f, if (isConfirm) 0xFFFFA726.toInt() else 0xFF2E4A55.toInt(), if (isConfirm) 2.5f else 1.5f)
            val label = if (row < 0) "Авто" else "Слот ${row + 1}"
            Gfx.text(c, label, r.left + 18, r.centerY() + 8, 21f, if (row < 0) 0xFF80CBC4.toInt() else Gfx.YELLOW, Gfx.Align.LEFT)
            if (m == null) {
                Gfx.text(c, "— пусто —", r.left + 150, r.centerY() + 7, 19f, 0xFF607D8B.toInt(), Gfx.Align.LEFT)
                continue
            }
            val text = when {
                isConfirm && saving -> "Нажмите ещё раз, чтобы перезаписать"
                isConfirm && confirmDelete -> "Нажмите ✕ ещё раз, чтобы удалить"
                else -> describe(m)
            }
            Gfx.text(c, text, r.left + 150, r.centerY() + 7, 19f, if (isConfirm) 0xFFFFCC80.toInt() else 0xFFFFFFFF.toInt(), Gfx.Align.LEFT)
            val at = savedAt(row)
            if (at > 0 && !isConfirm) {
                Gfx.text(c, fmt.format(Date(at)), r.right - (if (!saving && row >= 0) 80 else 18), r.centerY() + 6, 15f, 0xFF90A4AE.toInt(), Gfx.Align.RIGHT)
            }
            if (!saving && row >= 0) {
                val d = delRects[k]
                Gfx.rr(c, d.left, d.top, d.right, d.bottom, 8f, 0xFF5D1A1A.toInt())
                Gfx.text(c, "✕", d.centerX(), d.centerY() + 8, 22f, 0xFFFF8A80.toInt())
            }
        }
        buttons.draw(c)
        if (toastT > 0f) {
            val a = (minOf(1f, toastT / 0.4f) * 255).toInt()
            Gfx.rr(c, cx - 240, vh / 2 - 30, cx + 240, vh / 2 + 22, 12f, Gfx.withAlpha(0xFF000000.toInt(), a * 3 / 4))
            Gfx.text(c, toast, cx, vh / 2 + 4, 24f, Gfx.GREEN, alpha = a)
        }
    }
}
