package ch.lkmc.kirsch.capture

import android.hardware.camera2.CaptureResult
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
    fun detected50HzLightingUsesItsTenMillisecondPeriod() {
        val sceneFlicker = CaptureResult.STATISTICS_SCENE_FLICKER_50HZ
        val exposure = select(40_000_000L, 100, sceneFlicker = sceneFlicker)
        assertEquals(10_000_000L, exposure.timeNs)
        assertEquals(400, exposure.sensitivityIso)
        assertBrightnessPreserved(40_000_000L, 100, exposure)
        assertTrue(exposure.timeNs <= SweepExposurePolicy.motionExposureBudgetNs(sceneFlicker))
    }

    @Test
    fun onlyExplicit50HzDetectionChangesTheMotionBudget() {
        listOf(null, CaptureResult.STATISTICS_SCENE_FLICKER_NONE, CaptureResult.STATISTICS_SCENE_FLICKER_60HZ)
            .forEach { sceneFlicker ->
                val exposure = select(40_000_000L, 100, sceneFlicker = sceneFlicker)
                assertEquals(SweepExposurePolicy.TARGET_EXPOSURE_NS, exposure.timeNs)
                assertEquals(480, exposure.sensitivityIso)
                assertEquals(SweepExposurePolicy.TARGET_EXPOSURE_NS, SweepExposurePolicy.motionExposureBudgetNs(sceneFlicker))
            }
    }

    @Test
    fun detected50HzMotionBudgetDoesNotMislabelTheTargetAsLowLight() {
        assertEquals(10_000_000L, SweepExposurePolicy.motionExposureBudgetNs(CaptureResult.STATISTICS_SCENE_FLICKER_50HZ))
    }

    @Test
    fun detected50HzLightingKeepsAlreadyFastExposure() {
        val exposure = select(2_000_000L, 200, sceneFlicker = CaptureResult.STATISTICS_SCENE_FLICKER_50HZ)
        assertEquals(SweepExposurePolicy.Exposure(2_000_000L, 200), exposure)
    }

    @Test
    fun detected50HzSensitivityLimitStillPreservesBrightnessAndExceedsBudget() {
        val sceneFlicker = CaptureResult.STATISTICS_SCENE_FLICKER_50HZ
        val exposure = select(100_000_000L, 800, maxIso = 1600, sceneFlicker = sceneFlicker)
        assertEquals(50_000_000L, exposure.timeNs)
        assertEquals(1600, exposure.sensitivityIso)
        assertBrightnessPreserved(100_000_000L, 800, exposure)
        assertTrue(exposure.timeNs > SweepExposurePolicy.motionExposureBudgetNs(sceneFlicker))
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

    private fun select(timeNs: Long, iso: Int, maxIso: Int = 6400, sceneFlicker: Int? = null) = SweepExposurePolicy.select(
        timeNs,
        iso,
        100_000L..1_000_000_000L,
        50..maxIso,
        sceneFlicker,
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
