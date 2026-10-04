package ch.lkmc.kirsch.capture

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureOrientationTest {
    @Test
    fun rearSensorAndDisplayRotationsComposeInClockwiseOutputCoordinates() {
        val displays = listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)
        val expected = listOf(listOf(0, 3, 2, 1), listOf(1, 0, 3, 2), listOf(2, 1, 0, 3), listOf(3, 2, 1, 0))
        for ((sensorIndex, sensor) in listOf(0, 90, 180, 270).withIndex()) {
            for ((displayIndex, display) in displays.withIndex()) {
                assertEquals(expected[sensorIndex][displayIndex], CaptureOrientation.clockwiseQuarterTurns(sensor, display))
            }
        }
    }

    @Test
    fun unknownOrientationDoesNotInventARotation() {
        for (sensor in listOf(null, -90, 45, 360)) {
            assertNull(CaptureOrientation.clockwiseQuarterTurns(sensor, Surface.ROTATION_0))
        }
        assertNull(CaptureOrientation.clockwiseQuarterTurns(90, 99))
    }
}
