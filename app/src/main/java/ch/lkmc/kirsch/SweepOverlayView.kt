package ch.lkmc.kirsch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.AnimationUtils
import kotlin.math.abs
import kotlin.math.exp
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
    private val flourishPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB84D.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
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

    // Measured motion arrives in bursts, so the drawn ring chases the
    // reported values with a short exponential ease instead of jumping.
    // This is pure rendering: the underlying progress stays motion-only.
    private var displayedProgress = 0f
    private val displayedDirections = FloatArray(4)
    private var lastFrameMs = 0L
    private var flourishStartMs = 0L

    private companion object {
        const val CHASE_RATE = 11f
        const val SETTLE_EPSILON = 0.003f
        const val FLOURISH_DURATION_MS = 450L
    }

    fun showFraming(hint: String) {
        mode = Mode.FRAMING
        framingHint = hint
        invalidate()
    }

    fun showSweeping(hint: String) {
        if (mode != Mode.SWEEPING) {
            progress = 0f
            directions = FloatArray(4)
            displayedProgress = 0f
            displayedDirections.fill(0f)
            lastFrameMs = 0L
            flourishStartMs = 0L
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
        val now = AnimationUtils.currentAnimationTimeMillis()
        val deltaSeconds = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        lastFrameMs = now
        val blend = 1f - exp(-deltaSeconds * CHASE_RATE)
        displayedProgress += (progress - displayedProgress) * blend
        for (index in displayedDirections.indices) {
            displayedDirections[index] += (directions[index] - displayedDirections[index]) * blend
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        val centerX = width / 2f
        val centerY = height * 0.42f
        val radius = min(width, height) * 0.19f
        arcBounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        val segmentSpan = 90f - segmentGapDegrees
        for (index in segmentCenters.indices) {
            val center = segmentCenters[index]
            canvas.drawArc(arcBounds, center - segmentSpan / 2, segmentSpan, false, trackPaint)
            val sweep = segmentSpan * displayedDirections[index].coerceIn(0f, 1f)
            if (sweep > 0f) {
                canvas.drawArc(arcBounds, center - sweep / 2, sweep, false, arcPaint)
            }
        }
        val percent = "${(displayedProgress * 100).toInt()}%"
        canvas.drawText(percent, centerX, centerY + percentPaint.textSize / 3f, percentPaint)
        drawHint(canvas, sweepHint, centerY + radius + 34f * density)

        // A completed sweep (a genuinely covered one reports exactly 1)
        // gets a brief expanding-ring flourish while the frames persist.
        if (progress >= 1f && flourishStartMs == 0L && displayedProgress > 0.995f) {
            flourishStartMs = now
        }
        var flourishing = false
        if (flourishStartMs != 0L) {
            val phase = ((now - flourishStartMs).toFloat() / FLOURISH_DURATION_MS).coerceIn(0f, 1f)
            if (phase < 1f) {
                flourishing = true
                val ease = 1f - (1f - phase) * (1f - phase)
                flourishPaint.alpha = (140 * (1f - phase)).toInt()
                canvas.drawCircle(centerX, centerY, radius + 16f * density * ease, flourishPaint)
            }
        }

        val settling = abs(progress - displayedProgress) > SETTLE_EPSILON ||
            displayedDirections.indices.any { abs(directions[it] - displayedDirections[it]) > SETTLE_EPSILON }
        if (settling || flourishing) postInvalidateOnAnimation()
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
