package ch.lkmc.kirsch.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun contiguousAndInterleavedPathsAgree() {
        // The same samples laid out with pixelStride 1 and pixelStride 2 must
        // pack identically, so the bulk-row path cannot drift from the
        // per-sample one.
        val packed = ByteBuffer.wrap(
            byteArrayOf(
                1, 2, 3, 0,
                4, 5, 6, 0,
                7, 8, 9, 0,
            ),
        )
        val interleaved = ByteBuffer.wrap(
            byteArrayOf(
                1, 99, 2, 99, 3, 99, 0, 0,
                4, 99, 5, 99, 6, 99, 0, 0,
                7, 99, 8, 99, 9, 99, 0, 0,
            ),
        )
        assertArrayEquals(
            Yuv420Packer.copyPlane(packed, rowStride = 4, pixelStride = 1, width = 2, height = 2, startX = 1, startY = 1),
            Yuv420Packer.copyPlane(interleaved, rowStride = 8, pixelStride = 2, width = 2, height = 2, startX = 1, startY = 1),
        )
    }

    @Test
    fun contiguousRowsStillRejectATruncatedPlane() {
        // The last sample of the last row has to be readable; a short buffer
        // must fail the same way whichever path reads it.
        val short = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420Packer.copyPlane(short, rowStride = 3, pixelStride = 1, width = 3, height = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420Packer.copyPlane(short, rowStride = 3, pixelStride = 2, width = 3, height = 2)
        }
    }

    @Test
    fun honoursTheBuffersStartingPosition() {
        // The camera hands over buffers whose position is not always zero;
        // the bulk path must key off it rather than off index zero.
        val source = ByteBuffer.wrap(byteArrayOf(9, 9, 1, 2, 3, 4)).apply { position(2) }
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4),
            Yuv420Packer.copyPlane(source, rowStride = 2, pixelStride = 1, width = 2, height = 2),
        )
        // The source buffer's own position must be untouched.
        assertEquals(2, source.position())
    }

    @Test
    fun rejectsOddI420CropGeometry() {
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420Packer.requireEvenI420Crop(left = 1, top = 0, width = 4, height = 4)
        }
    }
}
