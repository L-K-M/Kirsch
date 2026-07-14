package ch.lkmc.kirsch.scan

enum class ScanState {
    PREVIEW,
    CONVERGING,
    CAPTURING,
    PERSISTING,
    QUEUED,
    PROCESSING,
    REVIEW,
    ACCEPTED,
    FAILED,
}

class ScanStateMachine(initial: ScanState = ScanState.PREVIEW) {
    var state: ScanState = initial
        private set

    fun transition(next: ScanState): ScanState {
        require(next in allowed.getValue(state)) { "Invalid scan transition: $state -> $next" }
        state = next
        return state
    }

    companion object {
        private val allowed = mapOf(
            ScanState.PREVIEW to setOf(ScanState.CONVERGING, ScanState.FAILED),
            ScanState.CONVERGING to setOf(ScanState.CAPTURING, ScanState.FAILED, ScanState.PREVIEW),
            ScanState.CAPTURING to setOf(ScanState.PERSISTING, ScanState.FAILED),
            ScanState.PERSISTING to setOf(ScanState.QUEUED, ScanState.FAILED),
            ScanState.QUEUED to setOf(ScanState.PROCESSING, ScanState.FAILED),
            ScanState.PROCESSING to setOf(ScanState.REVIEW, ScanState.FAILED, ScanState.QUEUED),
            ScanState.REVIEW to setOf(ScanState.ACCEPTED, ScanState.QUEUED, ScanState.FAILED),
            ScanState.ACCEPTED to setOf(ScanState.QUEUED),
            ScanState.FAILED to setOf(ScanState.PREVIEW, ScanState.QUEUED),
        )
    }
}
