package ch.lkmc.kirsch.geometry

import kotlin.math.hypot
import kotlin.math.atan2
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object PrintGeometry {
    data class Quad(val points: List<Point>, val area: Double)

    fun detect(image: Mat, minimumAreaFraction: Double = 0.12): List<Quad> {
        val scale = minOf(1.0, 1600.0 / maxOf(image.cols(), image.rows()))
        val resources = mutableListOf<Mat>()
        val contours = mutableListOf<MatOfPoint>()
        fun own(image: Mat): Mat = image.also { resources += it }
        try {
            val small = own(Mat())
            Imgproc.resize(image, small, Size(), scale, scale, Imgproc.INTER_AREA)
            val gray = own(Mat())
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            val edges = own(Mat())
            Imgproc.Canny(gray, edges, 45.0, 135.0)
            val hierarchy = own(Mat())
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val minimumArea = small.cols().toDouble() * small.rows() * minimumAreaFraction
            val quads = contours.mapNotNull { contour ->
                val curve = MatOfPoint2f(*contour.toArray()).also { resources += it }
                val approximation = MatOfPoint2f().also { resources += it }
                Imgproc.approxPolyDP(curve, approximation, Imgproc.arcLength(curve, true) * 0.02, true)
                val points = approximation.toArray()
                val area = if (points.size == 4) kotlin.math.abs(Imgproc.contourArea(approximation)) else 0.0
                val polygon = MatOfPoint(*points).also { resources += it }
                val convex = points.size == 4 && Imgproc.isContourConvex(polygon)
                curve.release()
                approximation.release()
                polygon.release()
                contour.release()
                if (area >= minimumArea && convex) {
                    Quad(order(points.map { Point(it.x / scale, it.y / scale) }), area / (scale * scale))
                } else {
                    null
                }
            }.distinctBy { quad ->
                quad.points.joinToString { point -> "${(point.x / 20).toInt()},${(point.y / 20).toInt()}" }
            }.sortedByDescending(Quad::area)
            return quads
        } finally {
            resources.forEach(Mat::release)
            contours.forEach(Mat::release)
        }
    }

    fun fullFrame(image: Mat): Quad = Quad(
        listOf(
            Point(0.0, 0.0),
            Point(image.cols() - 1.0, 0.0),
            Point(image.cols() - 1.0, image.rows() - 1.0),
            Point(0.0, image.rows() - 1.0),
        ),
        image.cols().toDouble() * image.rows(),
    )

    fun validateNormalizedQuad(points: List<Point>): List<Point> {
        require(points.size == 4) { "A print boundary requires four corners" }
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0 }) {
            "Corner coordinates must be finite and normalized"
        }
        val ordered = order(points)
        require(ordered.distinctBy { point -> point.x to point.y }.size == 4) { "Print corners must be distinct" }
        require(polygonArea(ordered) >= 0.01) { "Print boundary is too small" }
        val crosses = ordered.indices.map { index ->
            val first = ordered[index]
            val second = ordered[(index + 1) % 4]
            val third = ordered[(index + 2) % 4]
            (second.x - first.x) * (third.y - second.y) -
                (second.y - first.y) * (third.x - second.x)
        }
        require(crosses.all { it > 0 } || crosses.all { it < 0 }) { "Print boundary must be convex and uncrossed" }
        return ordered
    }

    fun polygonArea(points: List<Point>): Double = kotlin.math.abs(
        points.indices.sumOf { index ->
            val first = points[index]
            val second = points[(index + 1) % points.size]
            first.x * second.y - second.x * first.y
        } / 2.0,
    )

    /**
     * Recovers rectangle shape in the camera frame. Recorded intrinsics also
     * handle frontal and single-axis poses, where vanishing points alone
     * cannot determine focal length. Older packages can still use the
     * uncalibrated two-axis solve, with projected edges as the final fallback.
     * Corners are top-left, top-right, bottom-right, bottom-left.
     */
    fun aspectRatio(points: List<Point>, imageWidth: Int, imageHeight: Int, intrinsics: CameraIntrinsics? = null): Double? {
        if (points.size != 4 || imageWidth <= 0 || imageHeight <= 0) return null
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        // Zhang & He index the corners row-major: m1 m2 over m3 m4.
        val m1 = homogeneous(points[0])
        val m2 = homogeneous(points[1])
        val m3 = homogeneous(points[3])
        val m4 = homogeneous(points[2])
        val k2Denominator = dot(cross(m2, m4), m3)
        val k3Denominator = dot(cross(m3, m4), m2)
        if (k2Denominator == 0.0 || k3Denominator == 0.0) return null
        val k2 = dot(cross(m1, m4), m3) / k2Denominator
        val k3 = dot(cross(m1, m4), m2) / k3Denominator
        val n2 = scaleMinus(k2, m2, m1)
        val n3 = scaleMinus(k3, m3, m1)
        if (n2.any { !it.isFinite() } || n3.any { !it.isFinite() }) return null

        if (intrinsics != null) {
            fun lengthSquared(n: DoubleArray): Double {
                val x = (n[0] - intrinsics.centerX * n[2]) / intrinsics.focalX
                val y = (n[1] - intrinsics.centerY * n[2]) / intrinsics.focalY
                return x * x + y * y + n[2] * n[2]
            }
            return plausibleRatio(lengthSquared(n2), lengthSquared(n3))
        }

        val centerX = imageWidth / 2.0
        val centerY = imageHeight / 2.0
        // Both vanishing points at infinity means an affine (near-frontal)
        // view: the projected edges already carry the true ratio.
        val affine = kotlin.math.abs(n2[2]) < AFFINE_EPSILON && kotlin.math.abs(n3[2]) < AFFINE_EPSILON
        if (affine) return null

        val squaredFocal = -(
            (n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * centerX + n2[2] * n3[2] * centerX * centerX) +
                (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * centerY + n2[2] * n3[2] * centerY * centerY)
            ) / (n2[2] * n3[2])
        if (!squaredFocal.isFinite() || squaredFocal <= 0.0) return null

        val widthSquared = normalizedLengthSquared(n2, centerX, centerY, squaredFocal)
        val heightSquared = normalizedLengthSquared(n3, centerX, centerY, squaredFocal)
        return plausibleRatio(widthSquared, heightSquared)
    }

    private fun plausibleRatio(widthSquared: Double, heightSquared: Double): Double? {
        if (widthSquared <= 0.0 || heightSquared <= 0.0 || !widthSquared.isFinite() || !heightSquared.isFinite()) return null
        val ratio = kotlin.math.sqrt(widthSquared / heightSquared)
        // A recovered ratio far outside anything a print can be means the
        // corners were not a projected rectangle.
        return ratio.takeIf { it.isFinite() && it in MIN_PLAUSIBLE_RATIO..MAX_PLAUSIBLE_RATIO }
    }

    /**
     * Output pixel dimensions for a rectification. With a recovered [ratio]
     * the axes are redistributed to match it while holding the projected
     * estimate's total pixel count, so correcting the shape never invents
     * resolution. Without one, or if the arithmetic degenerates, the
     * projected lengths are used as they were.
     *
     * The pixel count is held to within integer truncation of both axes —
     * under a percent — not exactly, so nothing downstream should treat it
     * as a guarantee.
     */
    fun outputSize(projectedWidth: Double, projectedHeight: Double, ratio: Double?): Pair<Int, Int> {
        val fallback = projectedWidth.toInt().coerceAtLeast(1) to projectedHeight.toInt().coerceAtLeast(1)
        if (ratio == null || !ratio.isFinite() || ratio <= 0.0) return fallback
        if (!(projectedWidth > 0) || !(projectedHeight > 0)) return fallback
        val correctedHeight = kotlin.math.sqrt(projectedWidth * projectedHeight / ratio)
        val correctedWidth = ratio * correctedHeight
        if (!correctedWidth.isFinite() || !correctedHeight.isFinite()) return fallback
        return correctedWidth.toInt().coerceAtLeast(1) to correctedHeight.toInt().coerceAtLeast(1)
    }

    /**
     * Rectifies [quad] out of [image]. The output is sized from the recovered
     * physical aspect ratio when [aspectRatio] can supply one, keeping the
     * same total pixel count as the projected-edge estimate so no resolution
     * is invented; otherwise the projected edges are used directly.
     */
    fun rectify(image: Mat, quad: Quad, interpolation: Int = Imgproc.INTER_CUBIC, intrinsics: CameraIntrinsics? = null): Mat {
        if (quad.points == fullFrame(image).points) return image.clone()
        val (topLeft, topRight, bottomRight, bottomLeft) = quad.points
        val projectedWidth = maxOf(distance(topLeft, topRight), distance(bottomLeft, bottomRight))
        val projectedHeight = maxOf(distance(topLeft, bottomLeft), distance(topRight, bottomRight))
        val (width, height) = outputSize(
            projectedWidth,
            projectedHeight,
            aspectRatio(quad.points, image.cols(), image.rows(), intrinsics),
        )
        val source = MatOfPoint2f(topLeft, topRight, bottomRight, bottomLeft)
        val destination = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width - 1.0, 0.0),
            Point(width - 1.0, height - 1.0),
            Point(0.0, height - 1.0),
        )
        var transform: Mat? = null
        val output = Mat()
        try {
            val mapping = Imgproc.getPerspectiveTransform(source, destination)
            transform = mapping
            Imgproc.warpPerspective(image, output, mapping, Size(width.toDouble(), height.toDouble()), interpolation)
            return output
        } catch (error: Throwable) {
            output.release()
            throw error
        } finally {
            transform?.release()
            source.release()
            destination.release()
        }
    }

    private fun order(points: List<Point>): List<Point> {
        val centerX = points.sumOf(Point::x) / points.size
        val centerY = points.sumOf(Point::y) / points.size
        val winding = points.sortedBy { point -> atan2(point.y - centerY, point.x - centerX) }
        val first = winding.indices.minWith(
            compareBy<Int> { winding[it].x + winding[it].y }.thenBy { winding[it].x },
        )
        return winding.indices.map { offset -> winding[(first + offset) % winding.size] }
    }

    private fun distance(first: Point, second: Point): Double = hypot(first.x - second.x, first.y - second.y)

    private const val AFFINE_EPSILON = 1e-9

    /** 20:1 covers every print, panorama, and album strip a user could scan. */
    private const val MIN_PLAUSIBLE_RATIO = 0.05
    private const val MAX_PLAUSIBLE_RATIO = 20.0

    private fun homogeneous(point: Point): DoubleArray = doubleArrayOf(point.x, point.y, 1.0)

    private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun scaleMinus(scale: Double, a: DoubleArray, b: DoubleArray): DoubleArray =
        doubleArrayOf(scale * a[0] - b[0], scale * a[1] - b[1], scale * a[2] - b[2])

    /**
     * |A⁻¹n|² for the intrinsic matrix A = [[f,0,u0],[0,f,v0],[0,0,1]] — the
     * squared length of an edge direction measured in the camera frame.
     */
    private fun normalizedLengthSquared(
        n: DoubleArray,
        centerX: Double,
        centerY: Double,
        squaredFocal: Double,
    ): Double {
        val x = n[0] - centerX * n[2]
        val y = n[1] - centerY * n[2]
        return (x * x + y * y) / squaredFocal + n[2] * n[2]
    }
}
