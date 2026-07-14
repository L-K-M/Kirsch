package ch.lkmc.kirsch.capture

import kotlin.math.hypot
import kotlin.math.min

/**
 * Frame selection and completion policy for the freehand glare-removal sweep.
 *
 * The 2026-07-14 GRL-AL10 product sweep audit showed that a fixed-count burst
 * does not enforce the geometry fusion needs, and the first field sweeps
 * showed that a single displacement span does not either: a one-directional
 * wiggle satisfies a span target while leaving most of the print without a
 * genuinely different perspective. Completion therefore requires directional
 * coverage — the kept views must reach a displacement target in all four
 * directions (right, down, left, up) around the sweep's starting point.
 *
 * Design constraint carried over from PLAN.md: progress and completion are
 * functions of camera motion only. Frames are gated on spacing and a
 * relative sharpness (stability) check — never on glare or highlight
 * content. The user guidance is fixed text, and no visual target is placed
 * at print corners or any other predetermined image position.
 *
 * All coordinates are in the units of the caller's shift measurements
 * (analysis pixels); [frameWidth] must be in the same units.
 */
class SweepPolicy(
    private val frameWidth: Int,
    private val settings: Settings = Settings(),
) {
    data class Settings(
        /**
         * Kept views must displace at least this fraction of the frame width
         * in each of the four directions before the sweep can complete.
         */
        val directionReachFraction: Double = 0.10,
        /** A frame is kept only this far (fraction of frame width) from the last kept frame. */
        val minKeepSpacingFraction: Double = 0.025,
        /** Fusion needs at least this many views before completion. */
        val minFrames: Int = 5,
        /** Hard cap on persisted frames per sweep. */
        val maxFrames: Int = 18,
        /** The sweep ends with whatever was gathered after this long. */
        val maxDurationNs: Long = 20_000_000_000L,
        /** Frames sharper than this fraction of the recent best pass the stability gate. */
        val minSharpnessRatio: Double = 0.4,
        /** How many recent frames define the sharpness reference. */
        val sharpnessWindow: Int = 8,
    ) {
        init {
            require(directionReachFraction > 0 && minKeepSpacingFraction > 0)
            require(minFrames in 1..maxFrames)
            require(maxDurationNs > 0 && sharpnessWindow > 0)
            require(minSharpnessRatio in 0.0..1.0)
        }
    }

    class Decision(
        val keep: Boolean,
        val complete: Boolean,
        /** Monotonic 0..1, driven by directional coverage and kept-frame count only. */
        val progress: Float,
        /** Monotonic 0..1 per direction, ordered right (+x), down (+y), left (-x), up (-y). */
        val directionProgress: FloatArray,
        val keptCount: Int,
        val timedOut: Boolean,
    )

    private val directionTarget = settings.directionReachFraction * frameWidth
    private val minSpacing = settings.minKeepSpacingFraction * frameWidth
    private val recentSharpness = ArrayDeque<Double>()

    // Reach of kept views along +x, +y, -x, -y relative to the first kept frame.
    private val reach = DoubleArray(4)
    private var positionX = 0.0
    private var positionY = 0.0
    private var lastKeptX = 0.0
    private var lastKeptY = 0.0
    private var keptCount = 0
    private var firstTimestampNs = Long.MIN_VALUE
    private var completed = false
    private var timedOut = false
    private var bestProgress = 0f

    init {
        require(frameWidth > 0)
    }

    /**
     * Records one observed frame. [shiftX]/[shiftY] are the measured
     * translation relative to the previous observed frame (pass zero for the
     * first frame); [sharpness] is any consistent per-frame focus metric.
     */
    fun observe(shiftX: Double, shiftY: Double, sharpness: Double, timestampNs: Long): Decision {
        if (firstTimestampNs == Long.MIN_VALUE) firstTimestampNs = timestampNs
        positionX += shiftX
        positionY += shiftY
        val sharpEnough = recentSharpness.isEmpty() ||
            sharpness >= settings.minSharpnessRatio * recentSharpness.max()
        recentSharpness.addLast(sharpness)
        while (recentSharpness.size > settings.sharpnessWindow) recentSharpness.removeFirst()

        var keep = false
        if (!completed && keptCount < settings.maxFrames) {
            val spaced = keptCount == 0 ||
                hypot(positionX - lastKeptX, positionY - lastKeptY) >= minSpacing
            // Redundant views are skipped so the frame budget is spent on
            // frames that extend coverage — and a direction stops counting
            // once it reaches its target, so no single direction can consume
            // the budget and complete the sweep through the frame cap alone.
            val extendsCoverage = keptCount < settings.minFrames ||
                (positionX > reach[0] && reach[0] < directionTarget) ||
                (positionY > reach[1] && reach[1] < directionTarget) ||
                (-positionX > reach[2] && reach[2] < directionTarget) ||
                (-positionY > reach[3] && reach[3] < directionTarget)
            if (spaced && extendsCoverage && (sharpEnough || keptCount == 0)) {
                keep = true
                keptCount += 1
                lastKeptX = positionX
                lastKeptY = positionY
                reach[0] = maxOf(reach[0], positionX)
                reach[1] = maxOf(reach[1], positionY)
                reach[2] = maxOf(reach[2], -positionX)
                reach[3] = maxOf(reach[3], -positionY)
            }
        }

        val directions = FloatArray(4) { index ->
            min(1.0, reach[index] / directionTarget).toFloat()
        }
        val coverage = directions.map(Float::toDouble).average()
        val frameFraction = min(1.0, keptCount.toDouble() / settings.minFrames)
        bestProgress = maxOf(bestProgress, min(coverage, frameFraction).toFloat())

        if (!completed) {
            val covered = directions.all { it >= 1f }
            val enough = keptCount >= settings.minFrames && covered
            val capped = keptCount >= settings.maxFrames
            val expired = keptCount >= 1 &&
                timestampNs - firstTimestampNs >= settings.maxDurationNs
            if (enough || capped || expired) {
                completed = true
                timedOut = expired && !enough && !capped
            }
        }
        return Decision(
            keep = keep,
            complete = completed,
            progress = if (completed && !timedOut) 1f else bestProgress,
            directionProgress = directions,
            keptCount = keptCount,
            timedOut = timedOut,
        )
    }
}
