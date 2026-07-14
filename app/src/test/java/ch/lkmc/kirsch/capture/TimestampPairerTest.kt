package ch.lkmc.kirsch.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class TimestampPairerTest {
    @Test
    fun pairsWhenImageArrivesFirst() {
        val pairs = mutableListOf<Pair<String, String>>()
        val pairer = TimestampPairer<String, String>(5, { image, result ->
            pairs += image to result
        }, {})

        pairer.addImage(10, "image")
        pairer.addResult(10, "result")

        assertEquals(listOf("image" to "result"), pairs)
        assertEquals(0 to 0, pairer.pendingCounts())
    }

    @Test
    fun pairsWhenResultArrivesFirst() {
        val pairs = mutableListOf<Pair<String, String>>()
        val pairer = TimestampPairer<String, String>(5, { image, result ->
            pairs += image to result
        }, {})

        pairer.addResult(20, "result")
        pairer.addImage(20, "image")

        assertEquals(listOf("image" to "result"), pairs)
    }

    @Test
    fun dropsOldestImageAtCapacityAndOnClear() {
        val dropped = mutableListOf<String>()
        val pairer = TimestampPairer<String, String>(1, { _, _ -> }, dropped::add)

        pairer.addImage(1, "first")
        pairer.addImage(2, "second")
        pairer.clear()

        assertEquals(listOf("first", "second"), dropped)
    }

    @Test
    fun evictsOldestUnmatchedResultsSoSweepSkipsCannotAccumulate() {
        val pairs = mutableListOf<Pair<String, String>>()
        val pairer = TimestampPairer<String, String>(
            maxPendingImages = 1,
            onPair = { image, result -> pairs += image to result },
            onDropImage = {},
            maxPendingResults = 2,
        )

        pairer.addResult(1, "r1")
        pairer.addResult(2, "r2")
        pairer.addResult(3, "r3")
        pairer.addImage(1, "late-image")
        pairer.addImage(3, "recent-image")

        assertEquals(listOf("recent-image" to "r3"), pairs)
        assertEquals(1 to 1, pairer.pendingCounts())
    }
}
