package ch.lkmc.kirsch.imaging

import org.opencv.core.CvType
import org.opencv.core.Mat

object ConservativeFusion {
    data class Result(val image: Mat, val confidence: Mat, val failure: Mat)

    fun fuse(images: List<Mat>, masks: List<Mat>, referenceIndex: Int): Result {
        require(images.isNotEmpty() && images.size == masks.size)
        val height = images[0].rows()
        val width = images[0].cols()
        require(images.all { it.rows() == height && it.cols() == width && it.type() == CvType.CV_8UC3 })
        val output = Mat(height, width, CvType.CV_8UC3)
        val confidence = Mat(height, width, CvType.CV_8UC1)
        val failure = Mat(height, width, CvType.CV_8UC1)
        val imageRows = images.map { ByteArray(width * 3) }
        val maskRows = masks.map { ByteArray(width) }
        val outputRow = ByteArray(width * 3)
        val confidenceRow = ByteArray(width)
        val failureRow = ByteArray(width)
        val sampleIndices = IntArray(images.size)
        val sampleLumas = IntArray(images.size)
        for (row in 0 until height) {
            images.forEachIndexed { index, image ->
                image.get(row, 0, imageRows[index])
                masks[index].get(row, 0, maskRows[index])
            }
            for (column in 0 until width) {
                var validCount = 0
                images.indices.forEach { index ->
                    if (maskRows[index][column].toInt() and 0xff != 0) {
                        val offset = column * 3
                        val b = imageRows[index][offset].toInt() and 0xff
                        val g = imageRows[index][offset + 1].toInt() and 0xff
                        val r = imageRows[index][offset + 2].toInt() and 0xff
                        sampleIndices[validCount] = index
                        sampleLumas[validCount] = (29 * b + 150 * g + 77 * r) shr 8
                        validCount++
                    }
                }
                for (index in 1 until validCount) {
                    val luma = sampleLumas[index]
                    val frame = sampleIndices[index]
                    var insertion = index
                    while (insertion > 0 && sampleLumas[insertion - 1] > luma) {
                        sampleLumas[insertion] = sampleLumas[insertion - 1]
                        sampleIndices[insertion] = sampleIndices[insertion - 1]
                        insertion--
                    }
                    sampleLumas[insertion] = luma
                    sampleIndices[insertion] = frame
                }
                val outputOffset = column * 3
                var allSaturated = validCount > 0
                for (index in 0 until validCount) allSaturated = allSaturated && sampleLumas[index] >= 250
                val selectedFrame = when {
                    validCount == 0 -> referenceIndex
                    validCount < 3 -> sampleIndices[validCount / 2]
                    else -> {
                        val lowIndex = (validCount - 1) / 5
                        val medianIndex = (validCount - 1) / 2
                        val temporalOutlier = sampleLumas[validCount - 1] - sampleLumas[lowIndex] >= 24 &&
                            sampleLumas[medianIndex] - sampleLumas[lowIndex] >= 10
                        sampleIndices[if (temporalOutlier) lowIndex else medianIndex]
                    }
                }
                val sourceOffset = column * 3
                outputRow[outputOffset] = imageRows[selectedFrame][sourceOffset]
                outputRow[outputOffset + 1] = imageRows[selectedFrame][sourceOffset + 1]
                outputRow[outputOffset + 2] = imageRows[selectedFrame][sourceOffset + 2]
                confidenceRow[column] = ((validCount * 255) / images.size).toByte()
                failureRow[column] = if (validCount < 3 || allSaturated) 0xff.toByte() else 0
            }
            output.put(row, 0, outputRow)
            confidence.put(row, 0, confidenceRow)
            failure.put(row, 0, failureRow)
        }
        return Result(output, confidence, failure)
    }
}
