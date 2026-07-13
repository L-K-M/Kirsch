package com.example.kirsch.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceStrategyTest {
    @Test
    fun lockIsPreferredWheneverAvailable() {
        assertEquals(
            WhiteBalanceStrategy.Mode.LOCK,
            WhiteBalanceStrategy.select(
                awbLockAvailable = true,
                supportsManualPostProcessing = true,
                gainsPlausible = true,
            ),
        )
        assertEquals(
            WhiteBalanceStrategy.Mode.LOCK,
            WhiteBalanceStrategy.select(
                awbLockAvailable = true,
                supportsManualPostProcessing = false,
                gainsPlausible = false,
            ),
        )
    }

    @Test
    fun manualReplayRequiresPlausibleGains() {
        assertEquals(
            WhiteBalanceStrategy.Mode.MANUAL_REPLAY,
            WhiteBalanceStrategy.select(
                awbLockAvailable = false,
                supportsManualPostProcessing = true,
                gainsPlausible = true,
            ),
        )
        assertEquals(
            WhiteBalanceStrategy.Mode.AUTO,
            WhiteBalanceStrategy.select(
                awbLockAvailable = false,
                supportsManualPostProcessing = true,
                gainsPlausible = false,
            ),
        )
        assertEquals(
            WhiteBalanceStrategy.Mode.AUTO,
            WhiteBalanceStrategy.select(
                awbLockAvailable = false,
                supportsManualPostProcessing = false,
                gainsPlausible = true,
            ),
        )
    }

    @Test
    fun typicalConvergedGainsArePlausible() {
        assertTrue(WhiteBalanceStrategy.gainsPlausible(1.9f, 1.0f, 1.0f, 2.1f))
        assertTrue(WhiteBalanceStrategy.gainsPlausible(1.0f, 1.0f, 1.0f, 1.0f))
    }

    @Test
    fun grlAl10FieldGainsAreRejected() {
        // Observed on GRL-AL10 (capture be3820d8): green channels split by
        // nearly 2x and blue left at unity under warm light.
        assertFalse(WhiteBalanceStrategy.gainsPlausible(1.933f, 1.946f, 1.0f, 1.0f))
    }

    @Test
    fun outOfRangeOrNonFiniteGainsAreRejected() {
        assertFalse(WhiteBalanceStrategy.gainsPlausible(0.1f, 1.0f, 1.0f, 1.0f))
        assertFalse(WhiteBalanceStrategy.gainsPlausible(1.0f, 1.0f, 1.0f, 9.5f))
        assertFalse(WhiteBalanceStrategy.gainsPlausible(Float.NaN, 1.0f, 1.0f, 1.0f))
    }
}
