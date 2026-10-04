package ch.lkmc.kirsch.capture

import android.view.Surface

/** Clockwise output rotation for the rear camera, sampled at the shutter. */
object CaptureOrientation {
    fun clockwiseQuarterTurns(sensorOrientationDegrees: Int?, displayRotation: Int): Int? {
        val sensorDegrees = sensorOrientationDegrees ?: return null
        if (sensorDegrees !in listOf(0, 90, 180, 270)) return null
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return null
        }
        return (sensorDegrees - displayDegrees + 360) % 360 / 90
    }
}
