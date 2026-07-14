package ch.lkmc.kirsch.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepPolicyTest {
    private fun policy(
        minFrames: Int = 5,
        maxFrames: Int = 12,
        maxDurationNs: Long = 12_000_000_000L,
    ) = SweepPolicy(
        frameWidth = 256,
        settings = SweepPolicy.Settings(
            targetSpanFraction = 0.25,
            minKeepSpacingFraction = 0.02,
            minFrames = minFrames,
            maxFrames = maxFrames,
            maxDurationNs = maxDurationNs,
        ),
    )

    @Test
    fun stationaryCameraMakesNoProgressAndDoesNotComplete() {
        val policy = policy()
        var decision = policy.observe(0.0, 0.0, 100.0, 0L)
        assertTrue(decision.keep)
        repeat(30) { index ->
            decision = policy.observe(0.1, -0.1, 100.0, (index + 1) * 40_000_000L)
        }
        assertFalse(decision.complete)
        assertEquals(1, decision.keptCount)
        assertTrue(decision.progress < 0.25f)
    }

    @Test
    fun steadySweepKeepsSpacedFramesAndCompletesAtTargetSpan() {
        val policy = policy()
        var decision = policy.observe(0.0, 0.0, 100.0, 0L)
        var frames = 1
        // 3 analysis px per frame: spacing gate (5.12px) keeps every second frame.
        while (!decision.complete && frames < 100) {
            decision = policy.observe(3.0, 0.0, 100.0, frames * 40_000_000L)
            frames += 1
        }
        assertTrue(decision.complete)
        assertFalse(decision.timedOut)
        assertEquals(1f, decision.progress)
        // Span target is 64px => ~22 frames of 3px; keeps are ~6px apart.
        assertTrue("kept ${decision.keptCount}", decision.keptCount >= 5)
        assertTrue("kept ${decision.keptCount}", decision.keptCount <= 12)
    }

    @Test
    fun blurryFramesAreSkippedButProgressRecovers() {
        val policy = policy()
        policy.observe(0.0, 0.0, 100.0, 0L)
        val blurry = policy.observe(8.0, 0.0, 1.0, 40_000_000L)
        assertFalse(blurry.keep)
        val sharp = policy.observe(8.0, 0.0, 90.0, 80_000_000L)
        assertTrue(sharp.keep)
        assertEquals(2, sharp.keptCount)
    }

    @Test
    fun fastSweepStillRequiresMinimumFrames() {
        val policy = policy(minFrames = 5)
        var decision = policy.observe(0.0, 0.0, 100.0, 0L)
        decision = policy.observe(70.0, 0.0, 100.0, 40_000_000L)
        assertFalse("span alone must not complete the sweep", decision.complete)
        var frames = 2
        while (!decision.complete && frames < 20) {
            decision = policy.observe(6.0, 0.0, 100.0, frames * 40_000_000L)
            frames += 1
        }
        assertTrue(decision.complete)
        assertTrue(decision.keptCount >= 5)
    }

    @Test
    fun timeoutCompletesWithWhateverWasGathered() {
        val policy = policy(maxDurationNs = 1_000_000_000L)
        policy.observe(0.0, 0.0, 100.0, 0L)
        val decision = policy.observe(0.0, 0.0, 100.0, 1_000_000_001L)
        assertTrue(decision.complete)
        assertTrue(decision.timedOut)
        assertTrue(decision.progress < 1f)
    }

    @Test
    fun frameCapCompletesAndNothingIsKeptAfterCompletion() {
        val policy = policy(minFrames = 1, maxFrames = 3)
        policy.observe(0.0, 0.0, 100.0, 0L)
        policy.observe(6.0, 0.0, 100.0, 1L)
        val capped = policy.observe(6.0, 0.0, 100.0, 2L)
        assertTrue(capped.complete)
        assertEquals(3, capped.keptCount)
        val after = policy.observe(6.0, 0.0, 100.0, 3L)
        assertFalse(after.keep)
        assertEquals(3, after.keptCount)
    }

    @Test
    fun progressIsMonotonic() {
        val policy = policy()
        var previous = 0f
        for (frame in 0 until 40) {
            val decision = policy.observe(
                if (frame % 3 == 0) 4.0 else -1.0,
                0.0,
                100.0,
                frame * 40_000_000L,
            )
            assertTrue(decision.progress >= previous)
            previous = decision.progress
        }
    }
}
