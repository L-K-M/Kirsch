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
        val small = Mat()
        Imgproc.resize(image, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 45.0, 135.0)
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val minimumArea = small.cols().toDouble() * small.rows() * minimumAreaFraction
        val quads = contours.mapNotNull { contour ->
            val curve = MatOfPoint2f(*contour.toArray())
            val approximation = MatOfPoint2f()
            Imgproc.approxPolyDP(curve, approximation, Imgproc.arcLength(curve, true) * 0.02, true)
            val points = approximation.toArray()
            val area = if (points.size == 4) kotlin.math.abs(Imgproc.contourArea(approximation)) else 0.0
            val polygon = MatOfPoint(*points)
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
        hierarchy.release()
        edges.release()
        gray.release()
        small.release()
        return quads
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

    fun rectify(image: Mat, quad: Quad, interpolation: Int = Imgproc.INTER_CUBIC): Mat {
        val (topLeft, topRight, bottomRight, bottomLeft) = quad.points
        val width = maxOf(distance(topLeft, topRight), distance(bottomLeft, bottomRight)).toInt().coerceAtLeast(1)
        val height = maxOf(distance(topLeft, bottomLeft), distance(topRight, bottomRight)).toInt().coerceAtLeast(1)
        val source = MatOfPoint2f(topLeft, topRight, bottomRight, bottomLeft)
        val destination = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width - 1.0, 0.0),
            Point(width - 1.0, height - 1.0),
            Point(0.0, height - 1.0),
        )
        val transform = Imgproc.getPerspectiveTransform(source, destination)
        val output = Mat()
        Imgproc.warpPerspective(image, output, transform, Size(width.toDouble(), height.toDouble()), interpolation)
        transform.release()
        source.release()
        destination.release()
        return output
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
}
