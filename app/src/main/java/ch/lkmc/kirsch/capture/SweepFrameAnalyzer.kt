package ch.lkmc.kirsch.capture

import android.media.Image
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
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
    data class Measurement(val shiftX: Double, val shiftY: Double, val sharpness: Double)

    private var previous: Mat? = null

    fun measure(image: Image): Measurement {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val crop = image.cropRect
        val step = maxOf(1, crop.width() / analysisWidth)
        val width = crop.width() / step
        val height = crop.height() / step
        val bytes = ByteArray(width * height)
        for (row in 0 until height) {
            val rowOffset = (crop.top + row * step) * rowStride + crop.left * pixelStride
            val columnStep = step * pixelStride
            for (column in 0 until width) {
                bytes[row * width + column] = buffer.get(rowOffset + column * columnStep)
            }
        }
        // The caller survives analysis exceptions and keeps sweeping, so
        // every native Mat must be released even on a throwing OpenCV call.
        val gray = Mat(height, width, CvType.CV_8UC1)
        val laplacian = Mat()
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        var current: Mat? = null
        try {
            gray.put(0, 0, bytes)
            Imgproc.Laplacian(gray, laplacian, CvType.CV_32F)
            Core.meanStdDev(laplacian, mean, deviation)
            val sharpness = deviation.toArray()[0].let { it * it }
            val next = Mat()
            current = next
            gray.convertTo(next, CvType.CV_32FC1)
            val previousFrame = previous
            val shift = if (previousFrame != null &&
                previousFrame.rows() == next.rows() &&
                previousFrame.cols() == next.cols()
            ) {
                Imgproc.phaseCorrelate(previousFrame, next)
            } else {
                org.opencv.core.Point(0.0, 0.0)
            }
            previousFrame?.release()
            previous = next
            current = null
            return Measurement(shift.x, shift.y, sharpness)
        } finally {
            gray.release()
            laplacian.release()
            mean.release()
            deviation.release()
            current?.release()
        }
    }

    fun release() {
        previous?.release()
        previous = null
    }
}
