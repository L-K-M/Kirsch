package ch.lkmc.kirsch.imaging

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFrameSelectionTest {
    @Test
    fun retainsSharpestViewWithinDirectionalCoverageBudget() {
        val observations = listOf(
            CaptureFrameSelection.Observation(0.0, 0.0, 25.0),
            CaptureFrameSelection.Observation(-3.0, 5.0, 36.0),
            CaptureFrameSelection.Observation(100.0, 0.0, 20.0),
            CaptureFrameSelection.Observation(-100.0, 0.0, 20.0),
            CaptureFrameSelection.Observation(0.0, 100.0, 20.0),
            CaptureFrameSelection.Observation(0.0, -100.0, 20.0),
        )

        assertEquals(listOf(1, 2, 3, 4, 5), CaptureFrameSelection.positions(6, 5, observations))
    }

    @Test
    fun equallySharpViewsKeepTheOriginAndAllFourDirectionalExtremes() {
        val positions = listOf(
            0.0 to 0.0, 100.0 to 0.0, 80.0 to 0.0,
            0.0 to 100.0, 0.0 to 80.0, -100.0 to 0.0,
            -80.0 to 0.0, 0.0 to -100.0, 0.0 to -80.0,
        ).map { (x, y) -> CaptureFrameSelection.Observation(x, y, 100.0) }
        assertEquals(listOf(0, 1, 3, 5, 7), CaptureFrameSelection.positions(9, 5, positions))
    }

    @Test
    fun fillsDuplicateExtremesWithTheMostDistantRemainingView() {
        val positions = listOf(0.0, 100.0, 50.0, 20.0, -100.0, -50.0, -20.0)
            .map { CaptureFrameSelection.Observation(it, 0.0, 100.0) }
        assertEquals(listOf(0, 1, 2, 4, 5), CaptureFrameSelection.positions(7, 5, positions))
    }

    @Test
    fun malformedOrMissingMeasurementsUseTheLegacySpacing() {
        assertEquals(listOf(0, 2, 4, 6, 8), CaptureFrameSelection.positions(9, 5))
        val malformed = List(9) { CaptureFrameSelection.Observation(Double.NaN, 0.0, 100.0) }
        assertEquals(listOf(0, 2, 4, 6, 8), CaptureFrameSelection.positions(9, 5, malformed))
    }

    @Test
    fun shorterStacksKeepEveryView() {
        assertEquals(listOf(0, 1, 2), CaptureFrameSelection.positions(3, 5))
    }
}
