package ch.lkmc.kirsch

/** Bounds and gestures for inspecting a scan without stretching or losing it off screen. */
internal class PreviewViewport {
    data class Bounds(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    var zoom = 1f
        private set
    private var fittedWidth = 0f
    private var fittedHeight = 0f
    private var viewWidth = 0f
    private var viewHeight = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    val bounds: Bounds
        get() {
            val width = fittedWidth * zoom
            val height = fittedHeight * zoom
            return Bounds((viewWidth - width) / 2f + offsetX, (viewHeight - height) / 2f + offsetY, width, height)
        }

    fun configure(imageWidth: Float, imageHeight: Float, width: Float, height: Float) {
        require(listOf(imageWidth, imageHeight, width, height).all { it.isFinite() && it > 0f })
        viewWidth = width
        viewHeight = height
        val fit = minOf(width / imageWidth, height / imageHeight)
        fittedWidth = imageWidth * fit
        fittedHeight = imageHeight * fit
        reset()
    }

    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (!factor.isFinite() || factor <= 0f || !focusX.isFinite() || !focusY.isFinite()) return
        val nextZoom = (zoom * factor).coerceIn(1f, 8f)
        val ratio = nextZoom / zoom
        val anchorX = focusX - viewWidth / 2f
        val anchorY = focusY - viewHeight / 2f
        offsetX = anchorX + (offsetX - anchorX) * ratio
        offsetY = anchorY + (offsetY - anchorY) * ratio
        zoom = nextZoom
        constrainOffsets()
    }

    fun panBy(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        offsetX += dx
        offsetY += dy
        constrainOffsets()
    }

    fun reset() {
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
    }

    private fun constrainOffsets() {
        val horizontalLimit = ((fittedWidth * zoom - viewWidth) / 2f).coerceAtLeast(0f)
        val verticalLimit = ((fittedHeight * zoom - viewHeight) / 2f).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-horizontalLimit, horizontalLimit)
        offsetY = offsetY.coerceIn(-verticalLimit, verticalLimit)
    }
}
