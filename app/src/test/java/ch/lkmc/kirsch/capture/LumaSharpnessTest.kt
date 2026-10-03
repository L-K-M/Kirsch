package ch.lkmc.kirsch.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumaSharpnessTest {
    @Test
    fun nativeDetailIsVisibleEvenWhenDecimationWouldAliasItAway() {
        val width = 4096
        val height = 3072
        val sharp = ByteBuffer.wrap(ByteArray(width * height) { index ->
            if ((index % width / 4) % 2 == 0) 64 else 192.toByte()
        })
        val blurred = ByteBuffer.wrap(ByteArray(width * height) { 128.toByte() })
        val sharpness = LumaSharpness.measure(sharp, width, 1, 0, 0, width, height)
        val blur = LumaSharpness.measure(blurred, width, 1, 0, 0, width, height)
        assertTrue("native detail sharpness=$sharpness", sharpness > 1000.0)
        assertEquals(0.0, blur, 0.0)
    }

    @Test
    fun bufferPositionCropAndPaddingDoNotChangeSharpness() {
        val width = 256
        val height = 192
        val pixels = ByteArray(width * height) { index ->
            ((index % width * 31 + index / width * 7) % 256).toByte()
        }
        val tight = ByteBuffer.wrap(pixels)
        val base = 17
        val stride = width + 35
        val left = 9
        val top = 4
        val padded = ByteBuffer.allocate(base + (height + top) * stride)
        pixels.forEachIndexed { index, value ->
            padded.put(base + (top + index / width) * stride + left + index % width, value)
        }
        padded.position(base)
        assertEquals(
            LumaSharpness.measure(tight, width, 1, 0, 0, width, height),
            LumaSharpness.measure(padded, stride, 1, left, top, width, height),
            0.0,
        )
        assertEquals(base, padded.position())
    }
}
