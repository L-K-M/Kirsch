package com.example.kirsch.archival

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleMeasurementTest {
    @Test
    fun derivesPpiOnlyFromConfirmedPhysicalDimensions() {
        val measurement = ScaleMeasurement(
            pixelWidth = 6000,
            pixelHeight = 4000,
            physicalWidthMm = 254.0,
            physicalHeightMm = 169.333333,
            authority = ScaleAuthority.CONFIRMED_DIMENSIONS,
            targetId = null,
        )
        assertEquals(600.0, measurement.ppiX, 0.001)
        assertEquals(600.0, measurement.ppiY, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun targetAuthorityRequiresTraceableTargetId() {
        ScaleMeasurement(6000, 4000, 254.0, 169.3, ScaleAuthority.COPLANAR_TARGET, null)
    }
}
