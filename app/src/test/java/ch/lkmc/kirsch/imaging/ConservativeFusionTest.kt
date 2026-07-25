package ch.lkmc.kirsch.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConservativeFusionTest {
    private fun select(vararg lumas: Int) =
        ConservativeFusion.select(lumas.toList().toIntArray(), lumas.size)

    @Test
    fun withoutValidSamplesTheSelectionIsEmpty() {
        assertEquals(0, ConservativeFusion.select(IntArray(4), validCount = 0).size)
    }

    @Test
    fun agreeingViewsAreAllAveraged() {
        // Five views of the same unlit surface. Nothing indicates a moving
        // highlight, so every view contributes and the pixel gets the full
        // noise reduction instead of one frame's noise.
        val selection = select(118, 119, 120, 121, 122)
        assertEquals(2, selection.anchor)
        assertEquals(0, selection.first)
        assertEquals(5, selection.lastExclusive)
    }

    @Test
    fun aMajorityGlaredPixelAnchorsLowAndAveragesOnlyTheCleanViews() {
        // Three of five views carry the highlight, which lifts the median far
        // enough above the low sample to trip the outlier test.
        val selection = select(100, 102, 130, 200, 260)
        assertEquals(0, selection.anchor)
        assertEquals(0, selection.first)
        assertEquals(2, selection.lastExclusive)
    }

    @Test
    fun aMinorityGlaredPixelStillExcludesTheGlaredViews() {
        // Two of five views are glared. The median is itself a clean sample,
        // so the anchor stays there — and the highlight is far outside the
        // agreement window either way.
        val selection = select(100, 102, 104, 200, 240)
        assertEquals(2, selection.anchor)
        assertEquals(0, selection.first)
        assertEquals(3, selection.lastExclusive)
    }

    @Test
    fun theAgreementToleranceStaysBelowTheOutlierSpread() {
        // A view bright enough to have triggered outlier rejection must never
        // rejoin the average through the agreement window.
        assertTrue(ConservativeFusion.AGREEMENT_TOLERANCE < ConservativeFusion.OUTLIER_SPREAD)
    }

    @Test
    fun twoViewsKeepSingleSampleBehaviour() {
        // Below three views there is nothing to reject against, so the
        // conservative choice is unchanged and no averaging happens.
        val selection = select(100, 220)
        assertEquals(1, selection.anchor)
        assertEquals(1, selection.size)
    }

    @Test
    fun aSingleViewIsUsedAsIs() {
        val selection = select(77)
        assertEquals(0, selection.anchor)
        assertEquals(0, selection.first)
        assertEquals(1, selection.lastExclusive)
    }

    @Test
    fun theWindowNeverLeavesTheValidPrefix() {
        // Entries beyond validCount are stale from a previous pixel and must
        // not be pulled into the average.
        val lumas = intArrayOf(100, 101, 102, 0, 0, 0)
        val selection = ConservativeFusion.select(lumas, validCount = 3)
        assertEquals(0, selection.first)
        assertEquals(3, selection.lastExclusive)
    }
}
