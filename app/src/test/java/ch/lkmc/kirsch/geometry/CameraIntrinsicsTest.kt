package ch.lkmc.kirsch.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraIntrinsicsTest {
    @Test
    fun centerCropAndScalePreserveTheOpticalCenter() {
        val sensor = CameraIntrinsics(3000.0, 3000.0, 2020.0, 1530.0)
        val output = sensor.forOutput(20.0, 30.0, 4000.0, 3000.0, 1920, 1080)
        assertEquals(1440.0, output.focalX, 1e-9)
        assertEquals(1440.0, output.focalY, 1e-9)
        assertEquals(960.0, output.centerX, 1e-9)
        assertEquals(540.0, output.centerY, 1e-9)
    }

    @Test
    fun offCenterZoomMovesThePrincipalPoint() {
        val sensor = CameraIntrinsics(3000.0, 3000.0, 2000.0, 1500.0)
        val output = sensor.forOutput(800.0, 600.0, 2000.0, 1500.0, 2000, 1500)
        assertEquals(1200.0, output.centerX, 1e-9)
        assertEquals(900.0, output.centerY, 1e-9)
        assertEquals(3000.0, output.focalX, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnEmptySensorCrop() {
        CameraIntrinsics(3000.0, 3000.0, 2000.0, 1500.0).forOutput(0.0, 0.0, 0.0, 0.0, 100, 100)
    }
}
