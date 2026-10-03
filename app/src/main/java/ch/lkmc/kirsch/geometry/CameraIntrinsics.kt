package ch.lkmc.kirsch.geometry

import org.json.JSONObject

/** Pinhole calibration in the unrotated working image's pixel coordinates. */
data class CameraIntrinsics(
    val focalX: Double,
    val focalY: Double,
    val centerX: Double,
    val centerY: Double,
) {
    init {
        require(focalX.isFinite() && focalX > 0 && focalY.isFinite() && focalY > 0)
        require(centerX.isFinite() && centerY.isFinite())
    }

    fun toJson(): JSONObject = JSONObject()
        .put("focal_x", focalX).put("focal_y", focalY)
        .put("center_x", centerX).put("center_y", centerY)

    fun forOutput(left: Double, top: Double, cropWidth: Double, cropHeight: Double, width: Int, height: Int): CameraIntrinsics {
        require(left.isFinite() && top.isFinite() && cropWidth.isFinite() && cropHeight.isFinite())
        require(cropWidth > 0 && cropHeight > 0 && width > 0 && height > 0)
        val outputRatio = width.toDouble() / height
        val visibleWidth = minOf(cropWidth, cropHeight * outputRatio)
        val visibleHeight = minOf(cropHeight, cropWidth / outputRatio)
        val visibleLeft = left + (cropWidth - visibleWidth) / 2
        val visibleTop = top + (cropHeight - visibleHeight) / 2
        return CameraIntrinsics(
            focalX * width / visibleWidth,
            focalY * height / visibleHeight,
            (centerX - visibleLeft) * width / visibleWidth,
            (centerY - visibleTop) * height / visibleHeight,
        )
    }

    companion object {
        fun fromJson(value: JSONObject?): CameraIntrinsics? = value?.let {
            runCatching {
                CameraIntrinsics(it.getDouble("focal_x"), it.getDouble("focal_y"), it.getDouble("center_x"), it.getDouble("center_y"))
            }.getOrNull()
        }

        fun fromCapture(characteristics: JSONObject, metadata: JSONObject, width: Int, height: Int): CameraIntrinsics? {
            if (width <= 0 || height <= 0) return null
            val physicalId = metadata.optString("active_physical_camera_id")
            if (physicalId.isNotBlank() && physicalId != characteristics.optString("camera_id")) return null
            val active = characteristics.optJSONObject("sensor_active_array") ?: return null
            val crop = metadata.optJSONObject("scaler_crop_region") ?: active
            val left = crop.optDouble("left", Double.NaN)
            val top = crop.optDouble("top", Double.NaN)
            val cropWidth = crop.optDouble("right", Double.NaN) - left
            val cropHeight = crop.optDouble("bottom", Double.NaN) - top
            if (!left.isFinite() || !top.isFinite() || !cropWidth.isFinite() || !cropHeight.isFinite() || cropWidth <= 0 || cropHeight <= 0) return null

            // Calibration is relative to the pre-correction array's origin.
            // Use it only when that array matches the YUV coordinate system;
            // distortion correction otherwise changes its geometry.
            val preCorrection = characteristics.optJSONObject("sensor_pre_correction_active_array")
            val calibration = metadata.optJSONArray("lens_intrinsic_calibration")
                ?: characteristics.optJSONArray("lens_intrinsic_calibration")
            if (preCorrection != null && calibration != null && calibration.length() == 5 &&
                listOf("left", "top", "right", "bottom").all { preCorrection.optDouble(it) == active.optDouble(it) } &&
                kotlin.math.abs(calibration.optDouble(4, Double.NaN)) < 1e-6
            ) {
                val calibrated = runCatching {
                    CameraIntrinsics(
                        calibration.getDouble(0), calibration.getDouble(1),
                        calibration.getDouble(2) + active.getDouble("left"),
                        calibration.getDouble(3) + active.getDouble("top"),
                    ).forOutput(left, top, cropWidth, cropHeight, width, height)
                }.getOrNull()
                if (calibrated != null) return calibrated
            }

            val sensorSize = characteristics.optJSONObject("sensor_pixel_array") ?: return null
            val physicalSize = characteristics.optJSONObject("sensor_physical_size_mm") ?: return null
            val focalMm = metadata.optDouble("lens_focal_length_mm", Double.NaN)
            val focalX = focalMm * sensorSize.optDouble("width", Double.NaN) / physicalSize.optDouble("width", Double.NaN)
            val focalY = focalMm * sensorSize.optDouble("height", Double.NaN) / physicalSize.optDouble("height", Double.NaN)
            val centerX = (active.optDouble("left", Double.NaN) + active.optDouble("right", Double.NaN)) / 2
            val centerY = (active.optDouble("top", Double.NaN) + active.optDouble("bottom", Double.NaN)) / 2
            return runCatching {
                // Non-RAW streams additionally centre-crop the reported sensor
                // crop to the stream aspect ratio before scaling to output pixels.
                CameraIntrinsics(focalX, focalY, centerX, centerY)
                    .forOutput(left, top, cropWidth, cropHeight, width, height)
            }.getOrNull()
        }
    }
}
