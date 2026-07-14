package ch.lkmc.kirsch.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanStateMachineTest {
    @Test
    fun followsCaptureProcessingAndReviewPath() {
        val machine = ScanStateMachine()
        listOf(
            ScanState.CONVERGING,
            ScanState.CAPTURING,
            ScanState.PERSISTING,
            ScanState.QUEUED,
            ScanState.PROCESSING,
            ScanState.REVIEW,
            ScanState.ACCEPTED,
        ).forEach(machine::transition)
        assertEquals(ScanState.ACCEPTED, machine.state)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSilentSkipFromPreviewToAccepted() {
        ScanStateMachine().transition(ScanState.ACCEPTED)
    }
}
