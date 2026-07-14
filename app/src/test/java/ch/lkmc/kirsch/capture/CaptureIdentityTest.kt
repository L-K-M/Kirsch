package ch.lkmc.kirsch.capture

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureIdentityTest {
    @Test
    fun normalizesPrintIdsForBenchmarkSchema() {
        assertEquals("family-print-12", CaptureIdentity.normalizePrintId(" Family Print #12 "))
        assertEquals("print-123", CaptureIdentity.normalizePrintId("123"))
        assertEquals("unassigned", CaptureIdentity.normalizePrintId("---"))
    }

    @Test
    fun createsStableCaptureId() {
        assertEquals(
            "capture-20260713t120000123z-a1b2c3",
            CaptureIdentity.captureId(Instant.parse("2026-07-13T12:00:00.123Z"), "A1-B2-C3"),
        )
    }
}
