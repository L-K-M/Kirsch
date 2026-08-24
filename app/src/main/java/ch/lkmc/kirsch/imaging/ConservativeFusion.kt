package ch.lkmc.kirsch.imaging

import org.opencv.core.CvType
import org.opencv.core.Mat

object ConservativeFusion {
    data class Result(val image: Mat, val confidence: Mat, val failure: Mat)

    /**
     * A pixel's registered views, sorted by luma, split into the sample the
     * glare policy anchors on and the contiguous run of samples that agree
     * with it. [first] is inclusive, [lastExclusive] exclusive; both are
     * positions in the sorted arrays, not frame indices. An empty selection
     * ([lastExclusive] == [first]) means the pixel had no valid view.
     */
    data class Selection(val anchor: Int, val first: Int, val lastExclusive: Int) {
        val size: Int get() = lastExclusive - first
    }

    /**
     * A view is a temporal outlier when the brightest sample sits this far
     * above the low-percentile sample. Specular highlights on a glossy print
     * move between views; the surface underneath does not.
     */
    const val OUTLIER_SPREAD = 24

    /** ...and when the median has also been pulled up this far above the low sample. */
    const val OUTLIER_MEDIAN_LIFT = 10

    /**
     * Samples within this many luma code values of the anchor are treated as
     * agreeing observations of the same surface and are averaged.
     *
     * Averaging matters: selecting a single sample per pixel keeps
     * single-frame noise in full and, because neighbouring pixels can resolve
     * to different frames, adds a switching speckle that no single input
     * frame has. Averaging the agreeing run removes both without changing
     * which samples the glare policy considers acceptable.
     *
     * The value stays strictly below [OUTLIER_SPREAD] so a sample far enough
     * above the anchor to have triggered outlier rejection can never rejoin
     * through the agreement window.
     */
    const val AGREEMENT_TOLERANCE = 10

    /**
     * Anchors on a sample and returns the run of samples that agree with it.
     *
     * The anchor is the median view, or the low-percentile view when the
     * spread across views indicates a moving highlight. This is the
     * conservative glare decision and is unchanged; the agreement run only
     * decides how many of the acceptable samples are averaged together.
     *
     * [sortedLumas] must be ascending over its first [validCount] entries.
     */
    fun select(
        sortedLumas: IntArray,
        validCount: Int,
        tolerance: Int = AGREEMENT_TOLERANCE,
    ): Selection {
        require(validCount >= 0 && validCount <= sortedLumas.size)
        require(tolerance >= 0)
        if (validCount == 0) return Selection(anchor = -1, first = 0, lastExclusive = 0)
        val anchor = if (validCount < 3) {
            validCount / 2
        } else {
            val low = (validCount - 1) / 5
            val median = (validCount - 1) / 2
            val movingHighlight = sortedLumas[validCount - 1] - sortedLumas[low] >= OUTLIER_SPREAD &&
                sortedLumas[median] - sortedLumas[low] >= OUTLIER_MEDIAN_LIFT
            if (movingHighlight) low else median
        }
        val anchorLuma = sortedLumas[anchor]
        var first = anchor
        while (first > 0 && anchorLuma - sortedLumas[first - 1] <= tolerance) first -= 1
        var lastExclusive = anchor + 1
        while (lastExclusive < validCount && sortedLumas[lastExclusive] - anchorLuma <= tolerance) {
            lastExclusive += 1
        }
        return Selection(anchor, first, lastExclusive)
    }

    /**
     * [contributingFrameCount] is how many of [images] can actually supply a
     * sample — registration rejects arrive here as zero images behind an
     * all-zero mask and never raise a pixel's valid count, so scaling
     * confidence by `images.size` would report a ceiling the map can never
     * reach.
     */
    fun fuse(
        images: List<Mat>,
        masks: List<Mat>,
        referenceIndex: Int,
        contributingFrameCount: Int = images.size,
    ): Result {
        require(images.isNotEmpty() && images.size == masks.size)
        val height = images[0].rows()
        val width = images[0].cols()
        require(images.all { it.rows() == height && it.cols() == width && it.type() == CvType.CV_8UC3 })
        val confidenceDivisor = contributingFrameCount.coerceIn(1, images.size)
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
                val sourceOffset = column * 3
                var validCount = 0
                images.indices.forEach { index ->
                    if (maskRows[index][column].toInt() and 0xff != 0) {
                        val b = imageRows[index][sourceOffset].toInt() and 0xff
                        val g = imageRows[index][sourceOffset + 1].toInt() and 0xff
                        val r = imageRows[index][sourceOffset + 2].toInt() and 0xff
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
                var allSaturated = validCount > 0
                for (index in 0 until validCount) allSaturated = allSaturated && sampleLumas[index] >= 250
                val selection = select(sampleLumas, validCount)
                if (selection.size == 0) {
                    outputRow[sourceOffset] = imageRows[referenceIndex][sourceOffset]
                    outputRow[sourceOffset + 1] = imageRows[referenceIndex][sourceOffset + 1]
                    outputRow[sourceOffset + 2] = imageRows[referenceIndex][sourceOffset + 2]
                } else {
                    var sumB = 0
                    var sumG = 0
                    var sumR = 0
                    for (position in selection.first until selection.lastExclusive) {
                        val source = imageRows[sampleIndices[position]]
                        sumB += source[sourceOffset].toInt() and 0xff
                        sumG += source[sourceOffset + 1].toInt() and 0xff
                        sumR += source[sourceOffset + 2].toInt() and 0xff
                    }
                    val contributors = selection.size
                    val rounding = contributors / 2
                    outputRow[sourceOffset] = ((sumB + rounding) / contributors).toByte()
                    outputRow[sourceOffset + 1] = ((sumG + rounding) / contributors).toByte()
                    outputRow[sourceOffset + 2] = ((sumR + rounding) / contributors).toByte()
                }
                confidenceRow[column] = minOf(255, validCount * 255 / confidenceDivisor).toByte()
                failureRow[column] = if (validCount < 3 || allSaturated) 0xff.toByte() else 0
            }
            output.put(row, 0, outputRow)
            confidence.put(row, 0, confidenceRow)
            failure.put(row, 0, failureRow)
        }
        return Result(output, confidence, failure)
    }
}
