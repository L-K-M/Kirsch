package ch.lkmc.kirsch.capture

import kotlin.math.abs
import kotlin.math.max

/**
 * Chooses how the burst holds white balance constant.
 *
 * Field evidence (GRL-AL10, 2026-07-13, capture be3820d8): replaying the
 * preview's reported COLOR_CORRECTION_GAINS/TRANSFORM with AWB_MODE_OFF baked
 * an implausible HAL-reported gain vector (greenEven 1.946 vs greenOdd 1.0,
 * blue 1.0 under warm light) into every frame, producing an unrecoverable
 * color cast on a device with no RAW path. AWB lock, which the same device
 * advertises, avoids trusting result-reported gains at all.
 */
object WhiteBalanceStrategy {
    enum class Mode { LOCK, MANUAL_REPLAY, AUTO }

    /**
     * Prefer AWB lock whenever the device advertises it; fall back to
     * replaying converged gains only when lock is unavailable, manual post
     * processing is supported, and the reported gains look like real AWB
     * output. Otherwise leave AWB in auto and accept possible drift.
     */
    fun select(
        awbLockAvailable: Boolean,
        supportsManualPostProcessing: Boolean,
        gainsPlausible: Boolean,
    ): Mode = when {
        awbLockAvailable -> Mode.LOCK
        supportsManualPostProcessing && gainsPlausible -> Mode.MANUAL_REPLAY
        else -> Mode.AUTO
    }

    /**
     * Real AWB applies the same gain to both green channels and keeps gains
     * in a bounded range. A large even/odd green split or an out-of-range
     * value indicates placeholder or misreported result metadata that must
     * not be replayed.
     */
    fun gainsPlausible(
        red: Float,
        greenEven: Float,
        greenOdd: Float,
        blue: Float,
    ): Boolean {
        val gains = floatArrayOf(red, greenEven, greenOdd, blue)
        if (gains.any { !it.isFinite() || it < MIN_GAIN || it > MAX_GAIN }) return false
        val greenSplit = abs(greenEven - greenOdd) / max(greenEven, greenOdd)
        return greenSplit <= MAX_GREEN_SPLIT
    }

    // Empirically derived from the single device audited so far (GRL-AL10);
    // revisit against the device matrix as more sensors are qualified.
    private const val MIN_GAIN = 0.5f
    private const val MAX_GAIN = 8f
    private const val MAX_GREEN_SPLIT = 0.05f
}
