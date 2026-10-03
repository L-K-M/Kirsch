package ch.lkmc.kirsch.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepExposurePolicyTest {
    @Test
    fun indoorSweepTradesLongExposureForSensitivity() {
        val exposure = select(40_000_000L, 100)
        assertEquals(SweepExposurePolicy.TARGET_EXPOSURE_NS, exposure.timeNs)
        assertEquals(480, exposure.sensitivityIso)
        assertBrightnessPreserved(40_000_000L, 100, exposure)
    }

    @Test
    fun sensitivityLimitDoesNotTurnDarkRoomsIntoUnderexposedScans() {
        val exposure = select(100_000_000L, 800, maxIso = 1600)
        assertEquals(50_000_000L, exposure.timeNs)
        assertEquals(1600, exposure.sensitivityIso)
        assertBrightnessPreserved(100_000_000L, 800, exposure)
    }

    @Test
    fun alreadyFastExposureIsPreserved() {
        assertEquals(SweepExposurePolicy.Exposure(2_000_000L, 200), select(2_000_000L, 200))
    }

    @Test
    fun cameraExposureBoundsAreRespected() {
        val exposure = SweepExposurePolicy.select(
            40_000_000L,
            100,
            10_000_000L..1_000_000_000L,
            50..6400,
        )
        assertEquals(10_000_000L, exposure.timeNs)
        assertEquals(400, exposure.sensitivityIso)
    }

    private fun select(timeNs: Long, iso: Int, maxIso: Int = 6400) = SweepExposurePolicy.select(
        timeNs,
        iso,
        100_000L..1_000_000_000L,
        50..maxIso,
    )

    private fun assertBrightnessPreserved(
        timeNs: Long,
        iso: Int,
        selected: SweepExposurePolicy.Exposure,
    ) {
        val ratio = selected.timeNs.toDouble() * selected.sensitivityIso / (timeNs.toDouble() * iso)
        assertTrue("brightness ratio=$ratio", ratio in 0.995..1.005)
    }
}
