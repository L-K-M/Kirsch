package ch.lkmc.kirsch.capture

import android.graphics.Rect
import android.media.Image
import java.nio.ByteBuffer

object Yuv420Packer {
    data class Packed(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val sourcePlanes: List<PlaneLayout>,
        val crop: Rect,
    )

    data class PlaneLayout(
        val rowStride: Int,
        val pixelStride: Int,
        val bufferRemaining: Int,
    )

    fun pack(image: Image): Packed {
        require(image.format == android.graphics.ImageFormat.YUV_420_888)
        require(image.planes.size == 3)
        val crop = Rect(image.cropRect)
        val width = crop.width()
        val height = crop.height()
        requireEvenI420Crop(crop.left, crop.top, width, height)
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val layouts = image.planes.map {
            PlaneLayout(it.rowStride, it.pixelStride, it.buffer.remaining())
        }
        val y = copyPlane(
            image.planes[0].buffer,
            image.planes[0].rowStride,
            image.planes[0].pixelStride,
            width,
            height,
            crop.left,
            crop.top,
        )
        val u = copyPlane(
            image.planes[1].buffer,
            image.planes[1].rowStride,
            image.planes[1].pixelStride,
            chromaWidth,
            chromaHeight,
            crop.left / 2,
            crop.top / 2,
        )
        val v = copyPlane(
            image.planes[2].buffer,
            image.planes[2].rowStride,
            image.planes[2].pixelStride,
            chromaWidth,
            chromaHeight,
            crop.left / 2,
            crop.top / 2,
        )
        return Packed(y + u + v, width, height, layouts, crop)
    }

    fun requireEvenI420Crop(left: Int, top: Int, width: Int, height: Int) {
        require(left % 2 == 0 && top % 2 == 0 && width % 2 == 0 && height % 2 == 0) {
            "canonical I420 requires an even crop origin and dimensions"
        }
    }

    /**
     * Extracts a [width] x [height] region at ([startX], [startY]) from a
     * strided camera plane into tightly packed bytes.
     *
     * A 12 MP frame is about 18 MB across its three planes, and a sweep
     * persists up to twenty-two of them while the camera is still streaming
     * and reader buffers are scarce. A per-sample `get(index)` walk therefore
     * costs roughly eighteen million bounds-checked reads per frame on the
     * capture IO path. When a row is contiguous — `pixelStride == 1`, which
     * holds for luma on every device and for chroma on planar ones — the whole
     * row is taken in one bulk transfer instead. The interleaved path is
     * unchanged; NV12/NV21 chroma still needs a sample walk.
     */
    fun copyPlane(
        source: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        startX: Int = 0,
        startY: Int = 0,
    ): ByteArray {
        require(rowStride > 0 && pixelStride > 0)
        require(width > 0 && height > 0)
        require(startX >= 0 && startY >= 0)
        val buffer = source.duplicate()
        val base = buffer.position()
        val limit = buffer.limit()
        val output = ByteArray(width * height)
        val contiguous = pixelStride == 1
        var outputIndex = 0
        for (row in 0 until height) {
            val rowStart = base + (startY + row) * rowStride + startX * pixelStride
            if (contiguous) {
                // The last sample of the row must be readable, same bound the
                // per-sample path enforces.
                require(rowStart >= 0 && rowStart + width - 1 < limit) {
                    "plane buffer is too small for declared strides and crop"
                }
                buffer.position(rowStart)
                buffer.get(output, outputIndex, width)
                outputIndex += width
            } else {
                for (column in 0 until width) {
                    val sourceIndex = rowStart + column * pixelStride
                    require(sourceIndex < limit) {
                        "plane buffer is too small for declared strides and crop"
                    }
                    output[outputIndex++] = buffer.get(sourceIndex)
                }
            }
        }
        return output
    }
}
