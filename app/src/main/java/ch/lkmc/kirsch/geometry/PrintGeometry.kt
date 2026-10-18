package ch.lkmc.kirsch.geometry

import kotlin.math.hypot
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object PrintGeometry {
    data class Quad(val points: List<Point>, val area: Double)

    fun detect(image: Mat, minimumAreaFraction: Double = 0.12): List<Quad> {
        val scale = minOf(1.0, DETECTION_MAX_DIMENSION / maxOf(image.cols(), image.rows()))
        val resources = mutableListOf<Mat>()
        fun own(image: Mat): Mat = image.also { resources += it }
        try {
            val small = own(Mat())
            Imgproc.resize(image, small, Size(), scale, scale, Imgproc.INTER_AREA)
            val plane = own(Mat())
            val edges = own(Mat())
            val hierarchy = own(Mat())
            val closing = own(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
            val minimumArea = small.cols().toDouble() * small.rows() * minimumAreaFraction
            val quads = mutableListOf<Quad>()
            // A pale print on a warm surface can lose its boundary in grayscale.
            // Color planes retain that contrast; closing only bridges tiny edge gaps.
            for (channel in -1..2) {
                if (channel == -1) Imgproc.cvtColor(small, plane, Imgproc.COLOR_BGR2GRAY)
                else Core.extractChannel(small, plane, channel)
                Imgproc.GaussianBlur(plane, plane, Size(5.0, 5.0), 0.0)
                Imgproc.Canny(plane, edges, 45.0, 135.0)
                Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, closing)
                val contours = mutableListOf<MatOfPoint>()
                try {
                    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    for (contour in contours) {
                        val points = detectContour(contour, minimumArea, small.cols(), small.rows()) ?: continue
                        val fullSizePoints = points.map { Point(it.x / scale, it.y / scale) }
                        quads += Quad(fullSizePoints, polygonArea(fullSizePoints))
                    }
                } finally {
                    contours.forEach(Mat::release)
                }
            }
            return quads.distinctBy { quad ->
                quad.points.joinToString { point -> "${(point.x / 20).toInt()},${(point.y / 20).toInt()}" }
            }.sortedByDescending(Quad::area)
        } finally {
            resources.forEach(Mat::release)
        }
    }

    private fun detectContour(contour: MatOfPoint, minimumArea: Double, width: Int, height: Int): List<Point>? {
        if (kotlin.math.abs(Imgproc.contourArea(contour)) < minimumArea) return null
        val contourPoints = contour.toArray()
        if (contourPoints.any { it.x <= 1.0 || it.y <= 1.0 || it.x >= width - 2.0 || it.y >= height - 2.0 }) return null
        val curve = MatOfPoint2f(*contourPoints)
        val approximation = MatOfPoint2f()
        try {
            Imgproc.approxPolyDP(curve, approximation, Imgproc.arcLength(curve, true) * 0.02, true)
            val points = approximation.toArray()
            if (points.size != 4) return null
            val polygon = MatOfPoint(*points)
            try {
                if (!Imgproc.isContourConvex(polygon)) return null
            } finally {
                polygon.release()
            }
            val refined = supportedCorners(contourPoints, order(points.toList()), width, height) ?: return null
            return refined.takeIf { polygonArea(it) >= minimumArea }
        } finally {
            approximation.release()
            curve.release()
        }
    }

    private data class EdgeLine(val center: Point, val direction: Point) {
        fun offset(point: Point): Double = (point.x - center.x) * -direction.y + (point.y - center.y) * direction.x
        fun projection(point: Point): Double = (point.x - center.x) * direction.x + (point.y - center.y) * direction.y
    }

    private fun supportedCorners(contour: Array<Point>, corners: List<Point>, width: Int, height: Int): List<Point>? {
        // Polygon simplification puts rounded corners inside the print. Intersect
        // its four supported straight sides instead, retaining perspective.
        val samples = contour.indices.flatMap { index ->
            val start = contour[index]
            val end = contour[(index + 1) % contour.size]
            val count = ceil(distance(start, end) / EDGE_SAMPLE_SPACING).toInt().coerceAtLeast(1)
            (0 until count).map { sample ->
                val fraction = sample.toDouble() / count
                Point(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction)
            }
        }
        val minimumEdge = corners.indices.minOf { distance(corners[it], corners[(it + 1) % 4]) }
        val lines = corners.indices.map { index ->
            val start = corners[index]
            val end = corners[(index + 1) % 4]
            val length = distance(start, end)
            val approximate = EdgeLine(start, Point((end.x - start.x) / length, (end.y - start.y) / length))
            val nearby = samples.filter {
                val position = approximate.projection(it) / length
                position in EDGE_CENTRAL_START..EDGE_CENTRAL_END &&
                    kotlin.math.abs(approximate.offset(it)) <= minimumEdge * EDGE_SEARCH_FRACTION
            }
            val firstFit = fitLine(nearby) ?: return null
            val inliers = nearby.filter { kotlin.math.abs(firstFit.offset(it)) <= EDGE_INLIER_DISTANCE }
            val line = fitLine(inliers) ?: return null
            val span = inliers.maxOf(line::projection) - inliers.minOf(line::projection)
            val error = kotlin.math.sqrt(inliers.sumOf { line.offset(it) * line.offset(it) } / inliers.size)
            if (span < length * MINIMUM_STRAIGHT_EDGE_FRACTION || error > MAXIMUM_EDGE_FIT_ERROR) return null
            line
        }
        val refined = corners.indices.map { index ->
            val corner = intersection(lines[(index + 3) % 4], lines[index]) ?: return null
            if (!corner.x.isFinite() || !corner.y.isFinite() ||
                corner.x !in 0.0..(width - 1.0) || corner.y !in 0.0..(height - 1.0) ||
                distance(corner, corners[index]) > minimumEdge * MAXIMUM_CORNER_EXTENSION_FRACTION
            ) return null
            corner
        }
        val turns = refined.indices.map { index ->
            val first = refined[index]
            val second = refined[(index + 1) % 4]
            val third = refined[(index + 2) % 4]
            (second.x - first.x) * (third.y - second.y) - (second.y - first.y) * (third.x - second.x)
        }
        return refined.takeIf { turns.all { it > 0.0 } || turns.all { it < 0.0 } }
    }

    private fun fitLine(points: List<Point>): EdgeLine? {
        if (points.size < MINIMUM_EDGE_SAMPLES) return null
        val center = Point(points.sumOf(Point::x) / points.size, points.sumOf(Point::y) / points.size)
        val xx = points.sumOf { (it.x - center.x) * (it.x - center.x) }
        val yy = points.sumOf { (it.y - center.y) * (it.y - center.y) }
        val xy = points.sumOf { (it.x - center.x) * (it.y - center.y) }
        val angle = 0.5 * atan2(2.0 * xy, xx - yy)
        return EdgeLine(center, Point(cos(angle), sin(angle)))
    }

    private fun intersection(first: EdgeLine, second: EdgeLine): Point? {
        val determinant = first.direction.x * second.direction.y - first.direction.y * second.direction.x
        if (kotlin.math.abs(determinant) < 1e-6) return null
        val offsetX = second.center.x - first.center.x
        val offsetY = second.center.y - first.center.y
        val position = (offsetX * second.direction.y - offsetY * second.direction.x) / determinant
        return Point(first.center.x + position * first.direction.x, first.center.y + position * first.direction.y)
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

    private const val DETECTION_MAX_DIMENSION = 1600.0
    private const val EDGE_SAMPLE_SPACING = 4.0
    private const val EDGE_CENTRAL_START = 0.15
    private const val EDGE_CENTRAL_END = 0.85
    private const val EDGE_SEARCH_FRACTION = 0.06
    private const val EDGE_INLIER_DISTANCE = 2.5
    private const val MINIMUM_EDGE_SAMPLES = 8
    private const val MINIMUM_STRAIGHT_EDGE_FRACTION = 0.55
    private const val MAXIMUM_EDGE_FIT_ERROR = 1.5
    private const val MAXIMUM_CORNER_EXTENSION_FRACTION = 0.12

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
