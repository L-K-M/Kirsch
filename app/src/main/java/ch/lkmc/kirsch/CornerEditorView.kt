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
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffb84d.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(resources.displayMetrics.density * 3, 0f, 0f, Color.BLACK)
    }
    private var bitmap: Bitmap? = null
    private val destination = RectF()
    private var activeCorner = -1
    private var points = mutableListOf(
        Point(0.04, 0.04),
        Point(0.96, 0.04),
        Point(0.96, 0.96),
        Point(0.04, 0.96),
    )

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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
        displayPoints.forEach { canvas.drawCircle(it.first, it.second, radius, handlePaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (destination.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeCorner = points.indices.minBy { index ->
                    val point = displayPoint(points[index])
                    hypot((event.x - point.first).toDouble(), (event.y - point.second).toDouble())
                }
                parent.requestDisallowInterceptTouchEvent(true)
                updateCorner(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateCorner(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateCorner(event.x, event.y)
                activeCorner = -1
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

    private fun updateCorner(x: Float, y: Float) {
        if (activeCorner !in points.indices) return
        points[activeCorner] = Point(
            ((x - destination.left) / destination.width()).toDouble().coerceIn(0.0, 1.0),
            ((y - destination.top) / destination.height()).toDouble().coerceIn(0.0, 1.0),
        )
        invalidate()
    }

    private fun displayPoint(point: Point): Pair<Float, Float> =
        Pair(
            destination.left + destination.width() * point.x.toFloat(),
            destination.top + destination.height() * point.y.toFloat(),
        )
}
