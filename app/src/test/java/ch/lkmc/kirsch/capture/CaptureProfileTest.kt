package ch.lkmc.kirsch.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProfileTest {
    @Test
    fun qualityProfilesUseLongerEvidenceDrivenSweep() {
        assertTrue(CaptureProfile.QUALITY_RAW.frameCount > 5)
        assertEquals(1_000_000_000L, CaptureProfile.QUALITY_RAW.frameIntervalNs * 8)
        assertEquals(CaptureMode.YUV, CaptureProfile.QUALITY_YUV.preferredMode)
    }

    @Test
    fun quickProfileIsSingleFrameYuvFallback() {
        assertEquals(1, CaptureProfile.QUICK_SINGLE.frameCount)
        assertEquals(CaptureMode.YUV, CaptureProfile.QUICK_SINGLE.preferredMode)
    }

    @Test
    fun sweepIsTheOnlySweepProfileAndUsesNativeCadence() {
        assertTrue(CaptureProfile.SWEEP.sweep)
        assertEquals(CaptureMode.YUV, CaptureProfile.SWEEP.preferredMode)
        // Displacement, not pacing, ends the sweep: no frame-interval request.
        assertEquals(0L, CaptureProfile.SWEEP.frameIntervalNs)
        assertTrue(CaptureProfile.SWEEP.frameCount >= 5)
        assertEquals(
            listOf(CaptureProfile.SWEEP),
            CaptureProfile.entries.filter(CaptureProfile::sweep),
        )
    }
}
