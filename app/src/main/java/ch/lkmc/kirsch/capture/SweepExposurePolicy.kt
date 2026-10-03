package ch.lkmc.kirsch.capture

import android.hardware.camera2.CaptureResult
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Keeps the locked brightness while shortening exposure for a moving sweep. */
object SweepExposurePolicy {
    const val TARGET_EXPOSURE_NS = 8_333_333L
    private const val TARGET_EXPOSURE_50HZ_NS = 10_000_000L

    data class Exposure(val timeNs: Long, val sensitivityIso: Int)

    // AE-off bypasses antibanding. Use the standard 50Hz anti-flicker period
    // only when the camera explicitly detects that lighting.
    internal fun motionExposureBudgetNs(sceneFlicker: Int?): Long =
        if (sceneFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ) TARGET_EXPOSURE_50HZ_NS
        else TARGET_EXPOSURE_NS

    fun select(
        exposureNs: Long,
        sensitivityIso: Int,
        exposureRange: LongRange,
        sensitivityRange: IntRange,
        sceneFlicker: Int? = null,
    ): Exposure {
        require(exposureNs > 0 && sensitivityIso > 0)
        require(exposureRange.first > 0 && exposureRange.first <= exposureRange.last)
        require(sensitivityRange.first > 0 && sensitivityRange.first <= sensitivityRange.last)
        val brightness = exposureNs.toDouble() * sensitivityIso
        val target = minOf(exposureNs, motionExposureBudgetNs(sceneFlicker)).coerceIn(exposureRange)
        // Exhausting ISO should lengthen the shutter, not silently darken the
        // print. The actual result remains authoritative on camera rounding.
        val minimumForBrightness = ceil(brightness / sensitivityRange.last).toLong()
        val timeNs = maxOf(target, minimumForBrightness).coerceIn(exposureRange)
        val iso = (brightness / timeNs).roundToInt().coerceIn(sensitivityRange)
        return Exposure(timeNs, iso)
    }
}
