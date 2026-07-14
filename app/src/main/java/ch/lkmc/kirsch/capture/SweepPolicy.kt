package ch.lkmc.kirsch.capture

import kotlin.math.hypot
import kotlin.math.min

/**
 * Frame selection and completion policy for the freehand glare-removal sweep.
 *
 * The 2026-07-14 GRL-AL10 product sweep audit showed that a fixed-count burst
 * does not enforce the geometry fusion needs: on devices that ignore pacing
 * requests, nine frames span ~320ms and a few dozen pixels, far less than a
 * typical specular footprint. This policy makes accumulated view displacement
 * the completion condition instead of a frame count.
 *
 * Design constraint carried over from PLAN.md: progress and completion are
 * functions of camera motion only. Frames are gated on spacing and a
 * relative sharpness (stability) check — never on glare or highlight
 * content, and the user guidance never depends on what the image shows.
 *
 * All coordinates are in the units of the caller's shift measurements
 * (analysis pixels); [frameWidth] must be in the same units.
 */
class SweepPolicy(
    private val frameWidth: Int,
    private val settings: Settings = Settings(),
) {
    data class Settings(
        /** Kept views must span this fraction of the frame width before the sweep can complete. */
        val targetSpanFraction: Double = 0.22,
        /** A frame is kept only this far (fraction of frame width) from the last kept frame. */
        val minKeepSpacingFraction: Double = 0.02,
        /** Fusion needs at least this many views before completion. */
        val minFrames: Int = 5,
        /** Hard cap on persisted frames per sweep. */
        val maxFrames: Int = 12,
        /** The sweep ends with whatever was gathered after this long. */
        val maxDurationNs: Long = 12_000_000_000L,
        /** Frames sharper than this fraction of the recent best pass the stability gate. */
        val minSharpnessRatio: Double = 0.4,
        /** How many recent frames define the sharpness reference. */
        val sharpnessWindow: Int = 8,
    ) {
        init {
            require(targetSpanFraction > 0 && minKeepSpacingFraction > 0)
            require(minFrames in 1..maxFrames)
            require(maxDurationNs > 0 && sharpnessWindow > 0)
            require(minSharpnessRatio in 0.0..1.0)
        }
    }

    data class Decision(
        val keep: Boolean,
        val complete: Boolean,
        /** Monotonic 0..1, driven by displacement span and kept-frame count only. */
        val progress: Float,
        val keptCount: Int,
        val timedOut: Boolean,
    )

    private val targetSpan = settings.targetSpanFraction * frameWidth
    private val minSpacing = settings.minKeepSpacingFraction * frameWidth
    private val recentSharpness = ArrayDeque<Double>()

    private var positionX = 0.0
    private var positionY = 0.0
    private var lastKeptX = 0.0
    private var lastKeptY = 0.0
    private var minX = 0.0
    private var maxX = 0.0
    private var minY = 0.0
    private var maxY = 0.0
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
            if (spaced && (sharpEnough || keptCount == 0)) {
                keep = true
                keptCount += 1
                lastKeptX = positionX
                lastKeptY = positionY
                if (keptCount == 1) {
                    minX = positionX; maxX = positionX
                    minY = positionY; maxY = positionY
                } else {
                    minX = min(minX, positionX); maxX = maxOf(maxX, positionX)
                    minY = min(minY, positionY); maxY = maxOf(maxY, positionY)
                }
            }
        }

        val span = hypot(maxX - minX, maxY - minY)
        val spanFraction = min(1.0, span / targetSpan)
        val frameFraction = min(1.0, keptCount.toDouble() / settings.minFrames)
        bestProgress = maxOf(bestProgress, min(spanFraction, frameFraction).toFloat())

        if (!completed) {
            val enough = keptCount >= settings.minFrames && span >= targetSpan
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
            keptCount = keptCount,
            timedOut = timedOut,
        )
    }
}
