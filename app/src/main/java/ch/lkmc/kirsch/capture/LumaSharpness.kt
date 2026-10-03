package ch.lkmc.kirsch.capture

import java.nio.ByteBuffer

/** Measures native-pixel detail without copying a full-resolution luma plane. */
object LumaSharpness {
    private const val PATCH_SIZE = 96
    private val PATCH_CENTERS = doubleArrayOf(0.25, 0.5, 0.75)

    fun measure(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Double {
        require(rowStride > 0 && pixelStride > 0 && width >= 3 && height >= 3)
        require(left >= 0 && top >= 0)
        val base = buffer.position()
        val last = base.toLong() + (top.toLong() + height - 1) * rowStride +
            (left.toLong() + width - 1) * pixelStride
        require(last < buffer.limit()) { "Luma buffer is too small for its crop and strides" }
        val patchWidth = minOf(PATCH_SIZE, width)
        val patchHeight = minOf(PATCH_SIZE, height)
        var count = 0
        var sum = 0.0
        var squaredSum = 0.0
        // Decimated luma can conceal blur spanning several native pixels.
        // Nine patches retain the sensor's pixel scale for the stability gate
        // while reading fewer than 85,000 pixels from a 12 MP frame.
        for (centerY in PATCH_CENTERS) {
            val patchTop = (height * centerY - patchHeight / 2).toInt()
                .coerceIn(0, height - patchHeight)
            for (centerX in PATCH_CENTERS) {
                val patchLeft = (width * centerX - patchWidth / 2).toInt()
                    .coerceIn(0, width - patchWidth)
                for (y in patchTop + 1 until patchTop + patchHeight - 1) {
                    val row = base + (top + y) * rowStride + left * pixelStride
                    for (x in patchLeft + 1 until patchLeft + patchWidth - 1) {
                        val index = row + x * pixelStride
                        val value = (buffer.get(index - pixelStride).toInt() and 0xff) +
                            (buffer.get(index + pixelStride).toInt() and 0xff) +
                            (buffer.get(index - rowStride).toInt() and 0xff) +
                            (buffer.get(index + rowStride).toInt() and 0xff) -
                            4 * (buffer.get(index).toInt() and 0xff)
                        sum += value
                        squaredSum += value.toDouble() * value
                        count += 1
                    }
                }
            }
        }
        val mean = sum / count
        return maxOf(0.0, squaredSum / count - mean * mean)
    }
}
