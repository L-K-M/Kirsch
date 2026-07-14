package ch.lkmc.kirsch.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepPolicyTest {
    private fun policy(
        minFrames: Int = 5,
        maxFrames: Int = 18,
        maxDurationNs: Long = 20_000_000_000L,
    ) = SweepPolicy(
        frameWidth = 256,
        settings = SweepPolicy.Settings(
            directionReachFraction = 0.10,
            minKeepSpacingFraction = 0.025,
            minFrames = minFrames,
            maxFrames = maxFrames,
            maxDurationNs = maxDurationNs,
        ),
    )

    /** Drives the policy along per-frame shifts; returns the last decision. */
    private fun sweep(policy: SweepPolicy, moves: List<Pair<Double, Double>>): SweepPolicy.Decision {
        var decision = policy.observe(0.0, 0.0, 100.0, 0L)
        moves.forEachIndexed { index, (dx, dy) ->
            decision = policy.observe(dx, dy, 100.0, (index + 1) * 40_000_000L)
        }
        return decision
    }

    /** n frames of (dx, dy) each. */
    private fun leg(n: Int, dx: Double, dy: Double) = List(n) { dx to dy }

    @Test
    fun stationaryCameraMakesNoProgressAndDoesNotComplete() {
        val decision = sweep(policy(), leg(30, 0.1, -0.1))
        assertFalse(decision.complete)
        assertEquals(1, decision.keptCount)
        assertTrue(decision.progress < 0.25f)
    }

    @Test
    fun oneDirectionalWiggleDoesNotComplete() {
        // Field finding: a wide horizontal-only sweep must not finish the
        // ring — it leaves the vertical directions without changed views.
        val decision = sweep(
            policy(),
            leg(30, 3.0, 0.0) + leg(60, -3.0, 0.0) + leg(30, 3.0, 0.0),
        )
        assertFalse(decision.complete)
        val directions = decision.directionProgress
        assertEquals(1f, directions[0])
        assertEquals(1f, directions[2])
        assertTrue(directions[1] < 1f && directions[3] < 1f)
    }

    @Test
    fun fourDirectionCoverageCompletes() {
        // Right, back through center to left, down, back up: a loose cross.
        val decision = sweep(
            policy(),
            leg(10, 3.0, 0.0) + leg(20, -3.0, 0.0) + leg(10, 3.0, 0.0) +
                leg(10, 0.0, 3.0) + leg(20, 0.0, -3.0),
        )
        assertTrue(decision.complete)
        assertFalse(decision.timedOut)
        assertEquals(1f, decision.progress)
        assertTrue("kept ${decision.keptCount}", decision.keptCount >= 5)
        assertTrue("kept ${decision.keptCount}", decision.keptCount <= 18)
    }

    @Test
    fun redundantFramesInsideCoveredTerritoryAreNotKept() {
        val policy = policy()
        sweep(policy, leg(10, 3.0, 0.0))
        // Return toward the origin: covered ground, nothing new to keep
        // once minFrames views exist.
        val back = policy.observe(-6.0, 0.0, 100.0, 999_000_000L)
        assertFalse(back.keep)
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
        policy.observe(7.0, 0.0, 100.0, 1L)
        val capped = policy.observe(7.0, 0.0, 100.0, 2L)
        assertTrue(capped.complete)
        assertEquals(3, capped.keptCount)
        val after = policy.observe(7.0, 0.0, 100.0, 3L)
        assertFalse(after.keep)
        assertEquals(3, after.keptCount)
    }

    @Test
    fun progressIsMonotonic() {
        val policy = policy()
        var previous = 0f
        var decision = policy.observe(0.0, 0.0, 100.0, 0L)
        val moves = leg(15, 3.0, 0.0) + leg(30, -3.0, 1.0) + leg(30, 3.0, -2.0)
        moves.forEachIndexed { index, (dx, dy) ->
            decision = policy.observe(dx, dy, 100.0, (index + 1) * 40_000_000L)
            assertTrue(decision.progress >= previous)
            previous = decision.progress
        }
    }
}
