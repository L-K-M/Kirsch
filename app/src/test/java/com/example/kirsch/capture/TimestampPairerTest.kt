package com.example.kirsch.capture

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
}
