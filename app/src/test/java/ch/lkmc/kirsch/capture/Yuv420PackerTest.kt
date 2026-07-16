package ch.lkmc.kirsch.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Yuv420PackerTest {
    @Test
    fun copiesPlaneWithoutPadding() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6),
            Yuv420Packer.copyPlane(source, rowStride = 3, pixelStride = 1, width = 3, height = 2),
        )
    }

    @Test
    fun removesRowPaddingAndInterleaving() {
        val source = ByteBuffer.wrap(
            byteArrayOf(
                10, 99, 11, 99, 0, 0,
                12, 99, 13, 99, 0, 0,
            ),
        )
        assertArrayEquals(
            byteArrayOf(10, 11, 12, 13),
            Yuv420Packer.copyPlane(source, rowStride = 6, pixelStride = 2, width = 2, height = 2),
        )
    }

    @Test
    fun appliesCropOffset() {
        val source = ByteBuffer.wrap(
            byteArrayOf(
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
            ),
        )
        assertArrayEquals(
            byteArrayOf(6, 7, 10, 11),
            Yuv420Packer.copyPlane(
                source,
                rowStride = 4,
                pixelStride = 1,
                width = 2,
                height = 2,
                startX = 1,
                startY = 1,
            ),
        )
    }

    @Test
    fun copiesInterleavedRowWithoutTrailingPadding() {
        // The last row ends exactly at the final sampled byte; no padding
        // beyond it may be required to exist.
        val source = ByteBuffer.wrap(byteArrayOf(1, 9, 2, 9, 3))
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            Yuv420Packer.copyPlane(source, rowStride = 6, pixelStride = 2, width = 3, height = 1),
        )
    }

    @Test
    fun boundsCheckCoversTheFullBulkReadSpan() {
        // The bulk row read spans (width - 1) * pixelStride + 1 bytes; the
        // buffer is exactly that long, so the read must fit with no slack.
        val source = ByteBuffer.wrap(byteArrayOf(7, 0, 0, 8))
        assertArrayEquals(
            byteArrayOf(7, 8),
            Yuv420Packer.copyPlane(source, rowStride = 8, pixelStride = 3, width = 2, height = 1),
        )
    }

    @Test
    fun rejectsBufferSmallerThanDeclaredGeometry() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420Packer.copyPlane(source, rowStride = 3, pixelStride = 1, width = 3, height = 2)
        }
    }

    @Test
    fun rejectsOddI420CropGeometry() {
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420Packer.requireEvenI420Crop(left = 1, top = 0, width = 4, height = 4)
        }
    }
}
