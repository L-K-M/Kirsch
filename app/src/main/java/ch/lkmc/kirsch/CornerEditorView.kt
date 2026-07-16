package ch.lkmc.kirsch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import org.opencv.core.Point

class CornerEditorView(context: Context) : View(context) {
    private companion object {
        const val GRAB_RADIUS_DP = 48f
    }

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffb84d.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3
    }
    // Contrast against arbitrary photo content comes from a dark halo drawn
    // under each white mark instead of Paint.setShadowLayer: shadow layers on
    // shapes force the whole view into a software layer, which would make
    // every drag frame rasterize the full bitmap on the UI thread.
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3000000.toInt()
        style = Paint.Style.FILL
    }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private val reticleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3.5f
    }
    private val handleHaloWidth = resources.displayMetrics.density * 1.5f
    // Allocated once: onDraw runs on every drag frame.
    private val reticlePaints = arrayOf(reticleHaloPaint, reticlePaint)
    private val reticleDirections = arrayOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)
    private var bitmap: Bitmap? = null
    private val destination = RectF()
    private var activeCorner = -1
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f
    private var magnifier: android.widget.Magnifier? = null
    private var points = mutableListOf(
        Point(0.04, 0.04),
        Point(0.96, 0.04),
        Point(0.96, 0.96),
        Point(0.04, 0.96),
    )

    init {
        contentDescription = context.getString(R.string.corner_editor_description)
        minimumHeight = (280 * resources.displayMetrics.density).toInt()
    }

    fun setImage(value: Bitmap) {
        bitmap?.takeIf { it !== value }?.recycle()
        bitmap = value
        requestLayout()
        invalidate()
    }

    fun setNormalizedPoints(value: List<Point>) {
        if (value.size == 4) points = value.map { Point(it.x, it.y) }.toMutableList()
        invalidate()
    }

    fun normalizedPoints(): List<Point> = points.map { Point(it.x, it.y) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val source = bitmap
        val desiredHeight = if (source == null || source.width == 0) {
            minimumHeight
        } else {
            (width * source.height.toFloat() / source.width).toInt().coerceAtLeast(minimumHeight)
        }
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap ?: return
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        destination.set(
            (width - drawWidth) / 2,
            (height - drawHeight) / 2,
            (width + drawWidth) / 2,
            (height + drawHeight) / 2,
        )
        canvas.drawBitmap(source, null, destination, imagePaint)
        val displayPoints = points.map(::displayPoint)
        displayPoints.indices.forEach { index ->
            val first = displayPoints[index]
            val second = displayPoints[(index + 1) % displayPoints.size]
            canvas.drawLine(first.first, first.second, second.first, second.second, edgePaint)
        }
        val radius = 12 * resources.displayMetrics.density
        displayPoints.forEachIndexed { index, point ->
            if (index == activeCorner) {
                // While dragging, the pixels at the corner must stay visible
                // both on screen and inside the Magnifier (which snapshots
                // this view's rendering): draw an open reticle, not a disc.
                for (paint in reticlePaints) {
                    canvas.drawCircle(point.first, point.second, radius, paint)
                    for ((dx, dy) in reticleDirections) {
                        canvas.drawLine(
                            point.first + dx * radius * 0.45f,
                            point.second + dy * radius * 0.45f,
                            point.first + dx * radius * 0.95f,
                            point.second + dy * radius * 0.95f,
                            paint,
                        )
                    }
                }
            } else {
                canvas.drawCircle(point.first, point.second, radius + handleHaloWidth, handleHaloPaint)
                canvas.drawCircle(point.first, point.second, radius, handlePaint)
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            // A mid-drag disable would otherwise swallow ACTION_UP and leave
            // the magnifier frozen on screen.
            activeCorner = -1
            magnifier?.dismiss()
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (destination.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nearest = points.indices.minBy { index ->
                    val point = displayPoint(points[index])
                    hypot((event.x - point.first).toDouble(), (event.y - point.second).toDouble())
                }
                val handle = displayPoint(points[nearest])
                val distance = hypot(
                    (event.x - handle.first).toDouble(),
                    (event.y - handle.second).toDouble(),
                )
                // Only a touch near a handle grabs it — a stray tap must not
                // teleport a corner across the image.
                if (distance > GRAB_RADIUS_DP * resources.displayMetrics.density) return false
                activeCorner = nearest
                // Drag preserves the initial finger-to-handle offset so the
                // corner is not buried under the fingertip.
                grabOffsetX = handle.first - event.x
                grabOffsetY = handle.second - event.y
                parent.requestDisallowInterceptTouchEvent(true)
                updateCorner(event.x, event.y)
                showMagnifier()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateCorner(event.x, event.y)
                showMagnifier()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateCorner(event.x, event.y)
                activeCorner = -1
                magnifier?.dismiss()
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * PhotoScan-style loupe: while dragging, the exact pixels under the
     * handle are shown magnified and offset away from the finger.
     */
    private fun showMagnifier() {
        if (activeCorner !in points.indices) return
        val loupe = magnifier ?: android.widget.Magnifier.Builder(this)
            .setInitialZoom(2.5f)
            .build()
            .also { magnifier = it }
        val handle = displayPoint(points[activeCorner])
        loupe.show(handle.first, handle.second)
    }

    private fun updateCorner(x: Float, y: Float) {
        if (activeCorner !in points.indices) return
        points[activeCorner] = Point(
            ((x + grabOffsetX - destination.left) / destination.width()).toDouble().coerceIn(0.0, 1.0),
            ((y + grabOffsetY - destination.top) / destination.height()).toDouble().coerceIn(0.0, 1.0),
        )
        invalidate()
    }

    private fun displayPoint(point: Point): Pair<Float, Float> =
        Pair(
            destination.left + destination.width() * point.x.toFloat(),
            destination.top + destination.height() * point.y.toFloat(),
        )
}
