package ch.lkmc.kirsch.geometry

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

/**
 * The recovered aspect ratio is checked against synthetic projections of
 * rectangles of known physical size, through a pinhole camera whose principal
 * point sits where [PrintGeometry.aspectRatio] assumes it does. The
 * construction is exact, so these assertions are tight.
 */
class PrintAspectRatioTest {
    private companion object {
        const val IMAGE_WIDTH = 4000
        const val IMAGE_HEIGHT = 3000
        const val FOCAL = 1500.0
        const val DISTANCE = 400.0
    }

    /**
     * Projects a [widthMm] × [heightMm] rectangle held at [tiltXDegrees] and
     * [tiltYDegrees] to the camera, returning its corners in this object's
     * order: top-left, top-right, bottom-right, bottom-left.
     */
    private fun project(
        widthMm: Double,
        heightMm: Double,
        tiltXDegrees: Double,
        tiltYDegrees: Double,
    ): List<Point> {
        val centerX = IMAGE_WIDTH / 2.0
        val centerY = IMAGE_HEIGHT / 2.0
        val a = Math.toRadians(tiltXDegrees)
        val b = Math.toRadians(tiltYDegrees)
        return listOf(
            -widthMm / 2 to -heightMm / 2,
            widthMm / 2 to -heightMm / 2,
            widthMm / 2 to heightMm / 2,
            -widthMm / 2 to heightMm / 2,
        ).map { (x, y) ->
            val rotatedY = y * cos(a)
            val depthFromX = y * sin(a)
            val cameraX = x * cos(b) + depthFromX * sin(b)
            val cameraZ = -x * sin(b) + depthFromX * cos(b) + DISTANCE
            Point(
                FOCAL * cameraX / cameraZ + centerX,
                FOCAL * rotatedY / cameraZ + centerY,
            )
        }
    }

    private fun projectedEdgeRatio(points: List<Point>): Double {
        fun distance(first: Point, second: Point) =
            hypot(first.x - second.x, first.y - second.y)
        val width = maxOf(distance(points[0], points[1]), distance(points[3], points[2]))
        val height = maxOf(distance(points[0], points[3]), distance(points[1], points[2]))
        return width / height
    }

    private fun recovered(points: List<Point>): Double {
        val ratio = PrintGeometry.aspectRatio(points, IMAGE_WIDTH, IMAGE_HEIGHT)
        assertNotNull("expected a recovered aspect ratio", ratio)
        return ratio!!
    }

    @Test
    fun recoversAThreeToTwoPrintFromATiltedView() {
        val points = project(widthMm = 150.0, heightMm = 100.0, tiltXDegrees = 25.0, tiltYDegrees = 15.0)
        assertEquals(1.5, recovered(points), 1e-6)
    }

    @Test
    fun recoversAPortraitPrintFromATiltedView() {
        val points = project(widthMm = 100.0, heightMm = 150.0, tiltXDegrees = 20.0, tiltYDegrees = -18.0)
        assertEquals(2.0 / 3.0, recovered(points), 1e-6)
    }

    @Test
    fun recoversASquarePrint() {
        val points = project(widthMm = 150.0, heightMm = 150.0, tiltXDegrees = 22.0, tiltYDegrees = 22.0)
        assertEquals(1.0, recovered(points), 1e-6)
    }

    @Test
    fun theProjectedEdgeEstimateItReplacesIsMateriallyWrong() {
        // This is the reason the construction is worth having: sizing the
        // output by projected edge lengths stretches an off-axis scan by
        // several percent, and by more than a fifth at a steep angle.
        val gentle = project(widthMm = 150.0, heightMm = 100.0, tiltXDegrees = 25.0, tiltYDegrees = 15.0)
        assertTrue(projectedEdgeRatio(gentle) / 1.5 - 1.0 > 0.06)

        val steep = project(widthMm = 102.0, heightMm = 152.0, tiltXDegrees = 30.0, tiltYDegrees = 10.0)
        assertTrue(projectedEdgeRatio(steep) / (102.0 / 152.0) - 1.0 > 0.20)
        assertEquals(102.0 / 152.0, recovered(steep), 1e-6)
    }

    @Test
    fun aFrontalViewFallsBackToTheProjectedEdges() {
        // Both vanishing points run off to infinity, so there is nothing to
        // solve — and the projected edges are already exactly right.
        val points = project(widthMm = 150.0, heightMm = 100.0, tiltXDegrees = 0.0, tiltYDegrees = 0.0)
        assertNull(PrintGeometry.aspectRatio(points, IMAGE_WIDTH, IMAGE_HEIGHT))
        assertEquals(1.5, projectedEdgeRatio(points), 1e-9)
    }

    @Test
    fun aSingleAxisTiltFallsBackAndTheFallbackIsNotAccurate() {
        // A phone held level side to side but tipped forward keeps the top
        // and bottom edges parallel in the image, so only one vanishing point
        // is finite and no focal length can be solved for. Unlike the frontal
        // case, the projected-edge fallback is materially wrong here — pinned
        // so the limitation is a known property rather than a surprise, and
        // so a later intrinsics-based fix has something to improve on.
        val pitch = project(widthMm = 150.0, heightMm = 100.0, tiltXDegrees = 35.0, tiltYDegrees = 0.0)
        assertNull(PrintGeometry.aspectRatio(pitch, IMAGE_WIDTH, IMAGE_HEIGHT))
        assertTrue(projectedEdgeRatio(pitch) / 1.5 - 1.0 > 0.25)

        val yaw = project(widthMm = 150.0, heightMm = 100.0, tiltXDegrees = 0.0, tiltYDegrees = 35.0)
        assertNull(PrintGeometry.aspectRatio(yaw, IMAGE_WIDTH, IMAGE_HEIGHT))

        // Barely any second-axis tilt is enough to make the solve work, which
        // is why the two-axis cases above recover exactly.
        val nearlyPitch = project(widthMm = 150.0, heightMm = 100.0, tiltXDegrees = 35.0, tiltYDegrees = 2.0)
        assertEquals(1.5, recovered(nearlyPitch), 1e-4)
    }

    @Test
    fun rejectsCornersWithNoRealFocalLength() {
        // Found by search over random quads: this one drives squaredFocal
        // negative, so no camera squares these corners up.
        assertNull(
            PrintGeometry.aspectRatio(
                listOf(
                    Point(1366.0, 592.0),
                    Point(2543.0, 388.0),
                    Point(2129.0, 1151.0),
                    Point(409.0, 1519.0),
                ),
                IMAGE_WIDTH,
                IMAGE_HEIGHT,
            ),
        )
    }

    @Test
    fun rejectsAnImplausiblyElongatedResult() {
        // A 30:1 strip solves exactly, so this pins the plausibility bound
        // rather than the solver: nothing a user scans is that elongated, and
        // a ratio out there means the corners were not a print.
        val strip = project(widthMm = 3000.0, heightMm = 100.0, tiltXDegrees = 25.0, tiltYDegrees = 15.0)
        assertNull(PrintGeometry.aspectRatio(strip, IMAGE_WIDTH, IMAGE_HEIGHT))
    }

    @Test
    fun outputSizeMatchesTheRecoveredRatioAtTheProjectedPixelCount() {
        val (width, height) = PrintGeometry.outputSize(1600.0, 1000.0, 1.5)
        assertEquals(1.5, width.toDouble() / height, 1e-3)
        // Pixel count is held: correcting the shape must not invent detail.
        assertEquals(1600.0 * 1000.0, width.toDouble() * height, 1600.0 * 1000.0 * 0.01)
    }

    @Test
    fun outputSizeFallsBackToProjectedLengths() {
        assertEquals(1600 to 1000, PrintGeometry.outputSize(1600.4, 1000.6, null))
        assertEquals(1600 to 1000, PrintGeometry.outputSize(1600.0, 1000.0, Double.NaN))
        assertEquals(1600 to 1000, PrintGeometry.outputSize(1600.0, 1000.0, 0.0))
        assertEquals(1600 to 1000, PrintGeometry.outputSize(1600.0, 1000.0, -1.5))
    }

    @Test
    fun outputSizeNeverReturnsAnEmptyImage() {
        val (width, height) = PrintGeometry.outputSize(0.4, 0.4, 1.5)
        assertTrue(width >= 1 && height >= 1)
        val (degenerateWidth, degenerateHeight) = PrintGeometry.outputSize(0.0, 0.0, 1.5)
        assertTrue(degenerateWidth >= 1 && degenerateHeight >= 1)
    }

    @Test
    fun rejectsMalformedInput() {
        val square = project(widthMm = 100.0, heightMm = 100.0, tiltXDegrees = 20.0, tiltYDegrees = 20.0)
        assertNull(PrintGeometry.aspectRatio(square.take(3), IMAGE_WIDTH, IMAGE_HEIGHT))
        assertNull(PrintGeometry.aspectRatio(square, 0, IMAGE_HEIGHT))
        assertNull(
            PrintGeometry.aspectRatio(
                square.dropLast(1) + Point(Double.NaN, 0.0),
                IMAGE_WIDTH,
                IMAGE_HEIGHT,
            ),
        )
    }

}
