package ch.lkmc.kirsch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** The deliverable, separate from the unrectified image used to edit print corners. */
class ScanPreviewView(context: Context) : View(context) {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val viewport = PreviewViewport()
    private var bitmap: Bitmap? = null
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            viewport.zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            invalidate()
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onDoubleTap(event: MotionEvent): Boolean {
            toggleZoom(event.x, event.y)
            return true
        }

        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (viewport.zoom <= 1f || scaleDetector.isInProgress) return false
            viewport.panBy(-distanceX, -distanceY)
            invalidate()
            return true
        }
    })

    init {
        setBackgroundColor(0xFF000000.toInt())
        contentDescription = context.getString(R.string.scan_preview_description)
        isClickable = true
        isFocusable = true
        minimumHeight = (280 * resources.displayMetrics.density).toInt()
    }

    fun setImage(value: Bitmap) {
        bitmap?.takeIf { it !== value }?.recycle()
        bitmap = value
        configureViewport()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val image = bitmap
        val preferredHeight = if (image == null) minimumHeight else {
            (width * image.height.toFloat() / image.width).toInt()
                .coerceIn(minimumHeight, (440 * resources.displayMetrics.density).toInt())
        }
        setMeasuredDimension(width, resolveSize(preferredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        configureViewport()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val bounds = viewport.bounds
        destination.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
        canvas.drawBitmap(image, null, destination, imagePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val inspecting = viewport.zoom > 1f || scaleDetector.isInProgress || event.pointerCount > 1
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(inspecting)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        toggleZoom(width / 2f, height / 2f)
        return true
    }

    fun releaseImage() {
        bitmap?.recycle()
        bitmap = null
    }

    private fun toggleZoom(x: Float, y: Float) {
        if (viewport.zoom > 1f) viewport.reset() else viewport.zoomBy(2f, x, y)
        invalidate()
    }

    private fun configureViewport() {
        val image = bitmap ?: return
        if (width <= 0 || height <= 0) return
        viewport.configure(image.width.toFloat(), image.height.toFloat(), width.toFloat(), height.toFloat())
    }
}
