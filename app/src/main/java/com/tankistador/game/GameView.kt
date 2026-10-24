package com.tankistador.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Hosts the game: runs the frame loop and maps the screen onto a virtual canvas that is
 * at least 1280x720 units, keeping the aspect ratio of the device.
 */
class GameView(context: Context, val game: Game) : View(context), Choreographer.FrameCallback {
    private var scale = 1f
    private var lastNanos = 0L
    private var running = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        scale = min(w / 1280f, h / 720f)
        game.resize(w / scale, h / scale)
    }

    fun resume() {
        if (running) return
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNanos == 0L) 1f / 60f else ((frameTimeNanos - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = frameTimeNanos
        game.update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(scale, scale)
        game.draw(canvas)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = game.scene
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                s.down(e.getPointerId(i), e.getX(i) / scale, e.getY(i) / scale)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                s.move(e.getPointerId(i), e.getX(i) / scale, e.getY(i) / scale)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                s.up(e.getPointerId(i), e.getX(i) / scale, e.getY(i) / scale)
            }
            MotionEvent.ACTION_CANCEL -> s.cancel()
        }
        return true
    }
}
