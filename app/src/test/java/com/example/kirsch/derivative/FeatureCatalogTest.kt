package com.example.kirsch.derivative

import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureCatalogTest {
    @Test(expected = IllegalStateException::class)
    fun absentGenerativeWeightsCannotProduceAPlaceholder() {
        FeatureCatalog.requireAvailable("generative-restoration")
    }

    @Test
    fun deterministicRestorationsAreAvailable() {
        assertEquals(
            FeatureAvailability.AVAILABLE,
            FeatureCatalog.requireAvailable("descreen").availability,
        )
    }
}
