package com.example.bantayex.monitoring

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Center-screen "look here" indicator shown during a gaze warning/violation
 * while taking an exam. A big pulsing bright-yellow ring sits in the middle,
 * with four bright-yellow arrows floating just outside it, pointing inward.
 * Background stays bright/white — no dark scrim — so the indicator pops
 * purely through color and size, not by dimming the screen.
 */
class GazeIndicatorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var isShown = false

    companion object {
        private val ARROW_COLOR = Color.parseColor("#FFEB00") // bright yellow
        private val TEXT_COLOR = Color.parseColor("#1A1A1A")  // near-black, readable on white
    }

    private val ringPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val shaftPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        isAntiAlias = true
    }

    private val headPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val messagePaint = Paint().apply {
        color = TEXT_COLOR
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }

    /** color param kept for call-site compatibility but no longer used for
     *  the arrows — they're always bright yellow now regardless of state. */
    fun show(color: Int = ARROW_COLOR) {
        isShown = true
        invalidate()
    }

    fun hide() {
        isShown = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isShown) return
        if (width <= 0 || height <= 0) return

        // No scrim here on purpose — background stays fully bright/white,
        // the ring + arrows carry all the visual weight.

        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height).toFloat()

        val pulse = ((sin(System.currentTimeMillis() / 350.0) + 1.0) / 2.0).toFloat()
        val circleRadius = base * 0.14f + pulse * (base * 0.02f)
        val alpha = (200 + pulse * 55).toInt().coerceIn(0, 255)

        val gap = base * 0.045f
        val shaftLength = base * 0.09f
        val headLength = base * 0.055f
        val headWidth = base * 0.05f
        val availableFromCenter = min(cx, cy) - base * 0.02f

        val maxReach = circleRadius + gap + shaftLength + headLength
        val fitScale = if (maxReach > availableFromCenter) availableFromCenter / maxReach else 1f

        ringPaint.strokeWidth = base * 0.02f
        ringPaint.color = ARROW_COLOR
        ringPaint.alpha = alpha
        canvas.drawCircle(cx, cy, circleRadius * fitScale, ringPaint)

        dotPaint.color = ARROW_COLOR
        dotPaint.alpha = 255
        canvas.drawCircle(cx, cy, base * 0.018f, dotPaint)

        shaftPaint.strokeWidth = base * 0.016f
        shaftPaint.color = ARROW_COLOR
        shaftPaint.alpha = alpha
        headPaint.color = ARROW_COLOR
        headPaint.alpha = alpha

        val directions = listOf(
            0f to -1f,
            0f to 1f,
            -1f to 0f,
            1f to 0f
        )
        for ((ux, uy) in directions) {
            drawInwardArrow(
                canvas, cx, cy, ux, uy,
                circleRadius * fitScale, gap * fitScale,
                shaftLength * fitScale, headLength * fitScale, headWidth * fitScale
            )
        }

        messagePaint.textSize = base * 0.065f
        val textY = (cy + (circleRadius * fitScale) + base * 0.16f).coerceAtMost(height - base * 0.04f)
        canvas.drawText("Please keep your eyes on the screen.", cx, textY, messagePaint)

        postInvalidateDelayed(16)
    }

    private fun drawInwardArrow(
        canvas: Canvas,
        cx: Float, cy: Float,
        ux: Float, uy: Float,
        circleRadius: Float, gap: Float,
        shaftLength: Float, headLength: Float, headWidth: Float
    ) {
        val tipX = cx + ux * (circleRadius + gap)
        val tipY = cy + uy * (circleRadius + gap)

        val shaftEndX = tipX + ux * headLength
        val shaftEndY = tipY + uy * headLength

        val tailX = shaftEndX + ux * shaftLength
        val tailY = shaftEndY + uy * shaftLength

        canvas.drawLine(tailX, tailY, shaftEndX, shaftEndY, shaftPaint)

        val perpX = -uy
        val perpY = ux
        val base1X = shaftEndX + perpX * headWidth / 2f
        val base1Y = shaftEndY + perpY * headWidth / 2f
        val base2X = shaftEndX - perpX * headWidth / 2f
        val base2Y = shaftEndY - perpY * headWidth / 2f

        val headPath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(base1X, base1Y)
            lineTo(base2X, base2Y)
            close()
        }
        canvas.drawPath(headPath, headPaint)
    }
}