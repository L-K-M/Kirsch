package ch.lkmc.kirsch.geometry

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencv.core.Point

class PrintGeometryTest {
    @Test
    fun ordersRotatedDiamondWithoutDuplicatingCorners() {
        val points = listOf(
            Point(0.5, 0.05),
            Point(0.95, 0.5),
            Point(0.5, 0.95),
            Point(0.05, 0.5),
        )
        val ordered = PrintGeometry.validateNormalizedQuad(points.shuffled())
        assertEquals(4, ordered.distinctBy { it.x to it.y }.size)
        assertEquals(0.405, PrintGeometry.polygonArea(ordered), 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCollapsedManualBoundary() {
        PrintGeometry.validateNormalizedQuad(
            listOf(
                Point(0.5, 0.5),
                Point(0.501, 0.5),
                Point(0.501, 0.501),
                Point(0.5, 0.501),
            ),
        )
    }

    @Test
    fun tiltedRectangleStillStartsAtUpperLeft() {
        val topLeft = Point(0.12, 0.18)
        val ordered = PrintGeometry.validateNormalizedQuad(
            listOf(
                Point(0.88, 0.10),
                Point(0.92, 0.82),
                topLeft,
                Point(0.08, 0.90),
            ),
        )
        assertEquals(topLeft.x, ordered[0].x, 0.0)
        assertEquals(topLeft.y, ordered[0].y, 0.0)
    }
}
