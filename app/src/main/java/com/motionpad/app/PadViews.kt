package com.motionpad.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Botão que pressiona um ou mais bits enquanto o dedo está em cima dele. */
@SuppressLint("ViewConstructor")
class PadButton(
    context: Context,
    private val label: String,
    private val mask: Int,
    private val state: ControllerState,
    private val round: Boolean,
    private val accent: Int,
) : View(context) {
    private var down = false
    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val pad = 4 * dp
        rect.set(pad, pad, width - pad, height - pad)
        fill.color = if (down) accent else BTN_BG
        stroke.color = accent
        text.color = if (down) Color.BLACK else Color.WHITE
        text.textSize = min(rect.height() * 0.42f, 26 * dp)
        if (round) {
            val r = min(rect.width(), rect.height()) / 2
            c.drawCircle(rect.centerX(), rect.centerY(), r, fill)
            c.drawCircle(rect.centerX(), rect.centerY(), r, stroke)
        } else {
            val r = min(rect.height() / 2, 22 * dp)
            c.drawRoundRect(rect, r, r, fill)
            c.drawRoundRect(rect, r, r, stroke)
        }
        c.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> set(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> set(false)
        }
        return true
    }

    private fun set(d: Boolean) {
        if (d == down) return
        down = d
        if (d) {
            state.press(mask)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } else state.release(mask)
        invalidate()
    }
}

/** Analógico virtual: arraste o dedo a partir de onde tocou; solta = volta ao centro. */
class StickView(context: Context, private val state: ControllerState) : View(context) {
    private val dp = resources.displayMetrics.density
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BTN_BG }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = 0xFF3A4150.toInt() }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5A6273.toInt() }
    private var kx = 0f
    private var ky = 0f

    override fun onDraw(c: Canvas) {
        val r = min(width, height) / 2f - 4 * dp
        val cx = width / 2f
        val cy = height / 2f
        c.drawCircle(cx, cy, r, base)
        c.drawCircle(cx, cy, r, ring)
        val travel = r * 0.6f
        c.drawCircle(cx + kx * travel, cy - ky * travel, r * 0.38f, knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val r = min(width, height) / 2f * 0.6f
                var x = (e.x - width / 2f) / r
                var y = -(e.y - height / 2f) / r
                val len = hypot(x, y)
                if (len > 1f) { x /= len; y /= len }
                if (len < 0.12f) { x = 0f; y = 0f } // zona morta
                update(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> update(0f, 0f)
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        kx = x; ky = y
        state.stickX = x; state.stickY = y
        invalidate()
    }
}

const val BTN_BG = 0xFF232833.toInt()
