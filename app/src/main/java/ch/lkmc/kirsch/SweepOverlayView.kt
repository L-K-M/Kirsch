package ch.lkmc.kirsch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * Camera overlay for the scan flow. In framing mode it draws corner guides;
 * during a sweep it draws a four-segment coverage ring in the
 * fingerprint-enrollment pattern: each segment (right, down, left, up) fills
 * as the user's accumulated motion reaches that direction's target.
 *
 * The ring is driven exclusively by measured camera motion reported by the
 * capture controller; the guidance text is fixed, and no visual target is
 * ever placed at print corners or any other image position. Nothing shown
 * here reacts to glare or image content.
 */
class SweepOverlayView(context: Context) : View(context) {
    enum class Mode { HIDDEN, FRAMING, SWEEPING }

    private val density = resources.displayMetrics.density
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99F3EDE2.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val scrimPaint = Paint().apply { color = 0x59000000 }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB84D.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3EDE2.toInt()
        textSize = 26f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3EDE2.toInt()
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
    }
    private val hintShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80000000.toInt()
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
    }
    private val arcBounds = RectF()

    // Ring segment centers in canvas degrees: right, down, left, up. Each
    // segment spans 90 degrees and grows outward from its center.
    private val segmentCenters = floatArrayOf(0f, 90f, 180f, 270f)
    private val segmentGapDegrees = 8f

    private var mode = Mode.HIDDEN
    private var progress = 0f
    private var directions = FloatArray(4)
    private var framingHint: String = ""
    private var sweepHint: String = ""

    fun showFraming(hint: String) {
        mode = Mode.FRAMING
        framingHint = hint
        invalidate()
    }

    fun showSweeping(hint: String) {
        if (mode != Mode.SWEEPING) {
            progress = 0f
            directions = FloatArray(4)
        }
        mode = Mode.SWEEPING
        sweepHint = hint
        invalidate()
    }

    fun setSweepProgress(value: Float, directionValues: FloatArray) {
        progress = value.coerceIn(0f, 1f)
        if (directionValues.size == directions.size) {
            directions = directionValues.copyOf()
        }
        if (mode == Mode.SWEEPING) invalidate()
    }

    fun hide() {
        mode = Mode.HIDDEN
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            Mode.HIDDEN -> Unit
            Mode.FRAMING -> drawFraming(canvas)
            Mode.SWEEPING -> drawSweeping(canvas)
        }
    }

    private fun drawFraming(canvas: Canvas) {
        val insetX = width * 0.12f
        val insetY = height * 0.18f
        val arm = 26f * density
        val corners = arrayOf(
            floatArrayOf(insetX, insetY, 1f, 1f),
            floatArrayOf(width - insetX, insetY, -1f, 1f),
            floatArrayOf(width - insetX, height - insetY, -1f, -1f),
            floatArrayOf(insetX, height - insetY, 1f, -1f),
        )
        for ((x, y, dx, dy) in corners) {
            canvas.drawLine(x, y, x + arm * dx, y, guidePaint)
            canvas.drawLine(x, y, x, y + arm * dy, guidePaint)
        }
        // Below the top brackets: the bottom of the screen belongs to the
        // controls row and must stay clear of overlay text.
        drawHint(canvas, framingHint, insetY + 44f * density)
    }

    private fun drawSweeping(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        val centerX = width / 2f
        val centerY = height * 0.42f
        val radius = min(width, height) * 0.19f
        arcBounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        val segmentSpan = 90f - segmentGapDegrees
        for (index in segmentCenters.indices) {
            val center = segmentCenters[index]
            canvas.drawArc(arcBounds, center - segmentSpan / 2, segmentSpan, false, trackPaint)
            val sweep = segmentSpan * directions[index].coerceIn(0f, 1f)
            if (sweep > 0f) {
                canvas.drawArc(arcBounds, center - sweep / 2, sweep, false, arcPaint)
            }
        }
        val percent = "${(progress * 100).toInt()}%"
        canvas.drawText(percent, centerX, centerY + percentPaint.textSize / 3f, percentPaint)
        drawHint(canvas, sweepHint, centerY + radius + 34f * density)
    }

    private fun drawHint(canvas: Canvas, hint: String, top: Float) {
        if (hint.isEmpty()) return
        val lineHeight = hintPaint.textSize * 1.35f
        hint.split('\n').forEachIndexed { index, line ->
            val y = top + index * lineHeight
            canvas.drawText(line, width / 2f + density, y + density, hintShadow)
            canvas.drawText(line, width / 2f, y, hintPaint)
        }
    }
}
