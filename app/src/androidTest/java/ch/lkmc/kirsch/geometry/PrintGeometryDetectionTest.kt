package ch.lkmc.kirsch.geometry

import android.test.InstrumentationTestCase
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

@Suppress("DEPRECATION")
class PrintGeometryDetectionTest : InstrumentationTestCase() {
    override fun setUp() {
        super.setUp()
        check(OpenCVLoader.initLocal())
    }

    fun testFindsOuterCardWhenItsColorBoundaryIsWeakInGrayscale() {
        val image = Mat(900, 1000, CvType.CV_8UC3, Scalar(100.0, 180.0, 225.0))
        try {
            for (y in 20 until image.rows() step 37) {
                Imgproc.line(image, Point(0.0, y.toDouble()), Point(999.0, y + 12.0), Scalar(104.0, 183.0, 228.0), 2)
            }
            roundedCard(image, Scalar(208.0, 201.0, 199.0), 32)
            // The conspicuous illustration border must not replace the card's boundary.
            Imgproc.rectangle(image, Point(280.0, 310.0), Point(740.0, 610.0), Scalar(25.0, 25.0, 25.0), 4)

            assertCardBoundary(image)
        } finally {
            image.release()
        }
    }

    fun testRoundedCornersUseTheStraightSidesWithoutCuttingOffTheBorder() {
        val image = Mat(900, 1000, CvType.CV_8UC3, Scalar(90.0, 90.0, 90.0))
        try {
            roundedCard(image, Scalar(220.0, 220.0, 220.0), 45)

            assertCardBoundary(image)
        } finally {
            image.release()
        }
    }

    fun testKeepsPerspectiveInsteadOfReplacingThePrintWithARotatedRectangle() {
        val image = Mat(900, 1000, CvType.CV_8UC3, Scalar(75.0, 75.0, 75.0))
        val corners = listOf(Point(180.0, 160.0), Point(840.0, 220.0), Point(760.0, 740.0), Point(240.0, 680.0))
        val polygon = MatOfPoint(*corners.toTypedArray())
        try {
            Imgproc.fillConvexPoly(image, polygon, Scalar(215.0, 215.0, 215.0))
            val quad = PrintGeometry.detect(image).firstOrNull()
            assertNotNull("The projected print boundary should be detected", quad)
            for (index in corners.indices) assertClose(corners[index], quad!!.points[index])
        } finally {
            polygon.release()
            image.release()
        }
    }

    fun testRejectsAnOvalInsteadOfInventingFourPrintCorners() {
        val image = Mat(900, 1000, CvType.CV_8UC3, Scalar(90.0, 90.0, 90.0))
        try {
            Imgproc.ellipse(image, Point(500.0, 450.0), Size(330.0, 250.0), 15.0, 0.0, 360.0, Scalar(220.0, 220.0, 220.0), -1)
            assertTrue("An oval has no four straight print edges", PrintGeometry.detect(image).isEmpty())
        } finally {
            image.release()
        }
    }

    fun testRejectsAClippedBackgroundRegion() {
        val image = Mat(900, 1000, CvType.CV_8UC3, Scalar(80.0, 80.0, 80.0))
        try {
            Imgproc.rectangle(image, Point(0.0, 0.0), Point(999.0, 760.0), Scalar(215.0, 215.0, 215.0), -1)
            assertTrue("The frame edge is not evidence of a print boundary", PrintGeometry.detect(image).isEmpty())
        } finally {
            image.release()
        }
    }

    private fun roundedCard(image: Mat, color: Scalar, radius: Int) {
        val r = radius.toDouble()
        Imgproc.rectangle(image, Point(200.0 + r, 230.0), Point(820.0 - r, 690.0), color, -1)
        Imgproc.rectangle(image, Point(200.0, 230.0 + r), Point(820.0, 690.0 - r), color, -1)
        for (x in listOf(200.0 + r, 820.0 - r)) {
            for (y in listOf(230.0 + r, 690.0 - r)) Imgproc.circle(image, Point(x, y), radius, color, -1)
        }
    }

    private fun assertCardBoundary(image: Mat) {
        val quad = PrintGeometry.detect(image).firstOrNull()
        assertNotNull("The card should have a detected outer boundary", quad)
        val corners = listOf(Point(200.0, 230.0), Point(820.0, 230.0), Point(820.0, 690.0), Point(200.0, 690.0))
        for (index in corners.indices) assertClose(corners[index], quad!!.points[index])
    }

    private fun assertClose(expected: Point, actual: Point) {
        assertEquals("Corner x", expected.x, actual.x, 6.0)
        assertEquals("Corner y", expected.y, actual.y, 6.0)
    }
}
