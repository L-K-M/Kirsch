package ch.lkmc.kirsch.capture

import android.media.Image
import java.nio.ByteBuffer
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Measures camera motion between consecutive sweep frames plus a sharpness
 * metric, on a heavily subsampled copy of the luma plane so full-rate
 * analysis stays cheap (a few hundred kilobytes per frame, no full-plane
 * copies).
 *
 * Shift is translation-only phase correlation, which is sufficient to
 * accumulate sweep displacement; the offline registration remains the
 * authority on actual geometry. Must be used from a single thread.
 */
class SweepFrameAnalyzer(val analysisWidth: Int = 256) {
    data class Measurement(
        val shiftX: Double,
        val shiftY: Double,
        val sharpness: Double,
        val trackingResponse: Double = 1.0,
    )

    private var previous: Mat? = null

    init {
        require(analysisWidth >= 3)
    }

    fun measure(image: Image): Measurement {
        val plane = image.planes[0]
        val crop = image.cropRect
        return measureLuma(
            plane.buffer,
            plane.rowStride,
            plane.pixelStride,
            crop.left,
            crop.top,
            crop.width(),
            crop.height(),
        )
    }

    internal fun measureLuma(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
    ): Measurement {
        val sharpness = LumaSharpness.measure(
            buffer,
            rowStride,
            pixelStride,
            cropLeft,
            cropTop,
            cropWidth,
            cropHeight,
        )
        val width = minOf(cropWidth, analysisWidth)
        val height = maxOf(1, cropHeight * width / cropWidth)
        val base = buffer.position()
        val bytes = ByteArray(width * height)
        for (row in 0 until height) {
            val sourceY = row * cropHeight / height
            val rowOffset = base + (cropTop + sourceY) * rowStride + cropLeft * pixelStride
            for (column in 0 until width) {
                val sourceX = column * cropWidth / width
                bytes[row * width + column] = buffer.get(rowOffset + sourceX * pixelStride)
            }
        }
        // The caller survives analysis exceptions and keeps sweeping, so
        // every native Mat must be released even on a throwing OpenCV call.
        val gray = Mat(height, width, CvType.CV_8UC1)
        var current: Mat? = null
        var correlationWindow: Mat? = null
        try {
            gray.put(0, 0, bytes)
            val next = Mat()
            current = next
            gray.convertTo(next, CvType.CV_32FC1)
            val previousFrame = previous
            val response = doubleArrayOf(1.0)
            val shift = if (previousFrame != null &&
                previousFrame.rows() == next.rows() &&
                previousFrame.cols() == next.cols()
            ) {
                val window = Mat()
                correlationWindow = window
                Imgproc.phaseCorrelate(previousFrame, next, window, response)
            } else {
                org.opencv.core.Point(0.0, 0.0)
            }
            previousFrame?.release()
            previous = next
            current = null
            return Measurement(shift.x, shift.y, sharpness, response[0])
        } finally {
            gray.release()
            current?.release()
            correlationWindow?.release()
        }
    }

    fun release() {
        previous?.release()
        previous = null
    }
}
