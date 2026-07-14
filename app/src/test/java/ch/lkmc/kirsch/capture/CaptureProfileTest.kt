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
}
