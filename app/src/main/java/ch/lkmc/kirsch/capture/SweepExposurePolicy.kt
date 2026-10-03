package ch.lkmc.kirsch.capture

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Keeps the locked brightness while shortening exposure for a moving sweep. */
object SweepExposurePolicy {
    const val TARGET_EXPOSURE_NS = 8_333_333L

    data class Exposure(val timeNs: Long, val sensitivityIso: Int)

    fun select(
        exposureNs: Long,
        sensitivityIso: Int,
        exposureRange: LongRange,
        sensitivityRange: IntRange,
    ): Exposure {
        require(exposureNs > 0 && sensitivityIso > 0)
        require(exposureRange.first > 0 && exposureRange.first <= exposureRange.last)
        require(sensitivityRange.first > 0 && sensitivityRange.first <= sensitivityRange.last)
        val brightness = exposureNs.toDouble() * sensitivityIso
        val target = minOf(exposureNs, TARGET_EXPOSURE_NS).coerceIn(exposureRange)
        // Exhausting ISO should lengthen the shutter, not silently darken the
        // print. The actual result remains authoritative on camera rounding.
        val minimumForBrightness = ceil(brightness / sensitivityRange.last).toLong()
        val timeNs = maxOf(target, minimumForBrightness).coerceIn(exposureRange)
        val iso = (brightness / timeNs).roundToInt().coerceIn(sensitivityRange)
        return Exposure(timeNs, iso)
    }
}
