package ch.lkmc.kirsch.geometry

import android.hardware.camera2.CameraMetadata
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
            val captureSize = characteristics.optJSONObject("capture_size")
            if (characteristics.has("capture_size") && captureSize == null) return null
            val streamWidth = captureSize?.optDouble("width", Double.NaN) ?: width.toDouble()
            val streamHeight = captureSize?.optDouble("height", Double.NaN) ?: height.toDouble()
            if (!streamWidth.isFinite() || !streamHeight.isFinite() || streamWidth <= 0 || streamHeight <= 0 ||
                streamWidth != streamWidth.toInt().toDouble() || streamHeight != streamHeight.toInt().toDouble()
            ) return null
            val imageCrop = metadata.optJSONObject("image_crop_region")
            if (metadata.has("image_crop_region") && imageCrop == null) return null
            val imageLeft = imageCrop?.optDouble("left", Double.NaN) ?: 0.0
            val imageTop = imageCrop?.optDouble("top", Double.NaN) ?: 0.0
            val imageRight = imageCrop?.optDouble("right", Double.NaN) ?: width.toDouble()
            val imageBottom = imageCrop?.optDouble("bottom", Double.NaN) ?: height.toDouble()
            if (listOf(imageLeft, imageTop, imageRight, imageBottom).any { !it.isFinite() || it != it.toInt().toDouble() }) return null
            if (imageLeft < 0 || imageTop < 0 || imageRight > streamWidth || imageBottom > streamHeight ||
                imageRight - imageLeft != width.toDouble() || imageBottom - imageTop != height.toDouble()
            ) return null
            if (imageCrop == null && (streamWidth != width.toDouble() || streamHeight != height.toDouble())) return null

            val active = characteristics.optJSONObject("sensor_active_array") ?: return null
            val preCorrection = characteristics.optJSONObject("sensor_pre_correction_active_array")
            // Crop coordinates start at the selected array's origin, although
            // the array rectangle itself is positioned in the full pixel array.
            val basis = when (metadata.optInt("distortion_correction_mode", -1)) {
                CameraMetadata.DISTORTION_CORRECTION_MODE_OFF -> preCorrection ?: return null
                CameraMetadata.DISTORTION_CORRECTION_MODE_FAST,
                CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY -> active
                else -> {
                    if (preCorrection != null && !sameArray(preCorrection, active)) return null
                    active
                }
            }
            val basisLeft = basis.optDouble("left", Double.NaN)
            val basisTop = basis.optDouble("top", Double.NaN)
            if (!basisLeft.isFinite() || !basisTop.isFinite()) return null
            val basisWidth = basis.optDouble("right", Double.NaN) - basisLeft
            val basisHeight = basis.optDouble("bottom", Double.NaN) - basisTop
            if (!basisWidth.isFinite() || !basisHeight.isFinite() || basisWidth <= 0 || basisHeight <= 0) return null
            val crop = metadata.optJSONObject("scaler_crop_region")
            if (metadata.has("scaler_crop_region") && crop == null) return null
            val left = crop?.optDouble("left", Double.NaN) ?: 0.0
            val top = crop?.optDouble("top", Double.NaN) ?: 0.0
            val right = crop?.optDouble("right", Double.NaN) ?: basisWidth
            val bottom = crop?.optDouble("bottom", Double.NaN) ?: basisHeight
            val cropWidth = right - left
            val cropHeight = bottom - top
            if (!left.isFinite() || !top.isFinite() || !cropWidth.isFinite() || !cropHeight.isFinite() || cropWidth <= 0 || cropHeight <= 0) return null
            if (left < 0 || top < 0 || right > basisWidth || bottom > basisHeight) return null

            // The ImageReader stream is aspect-cropped/scaled from the sensor.
            // Packing Image.cropRect then only removes pixels, without rescaling.
            fun projectToPackedImage(sensor: CameraIntrinsics): CameraIntrinsics {
                val stream = sensor.forOutput(left, top, cropWidth, cropHeight, streamWidth.toInt(), streamHeight.toInt())
                return CameraIntrinsics(stream.focalX, stream.focalY, stream.centerX - imageLeft, stream.centerY - imageTop)
            }

            // Calibration is relative to the pre-correction array's origin.
            // Use it only when that array matches the YUV coordinate system;
            // distortion correction otherwise changes its geometry.
            val calibration = metadata.optJSONArray("lens_intrinsic_calibration")
                ?: characteristics.optJSONArray("lens_intrinsic_calibration")
            if (preCorrection != null && calibration != null && calibration.length() == 5 &&
                sameArray(preCorrection, basis) &&
                kotlin.math.abs(calibration.optDouble(4, Double.NaN)) < 1e-6
            ) {
                val calibrated = runCatching {
                    projectToPackedImage(CameraIntrinsics(
                        calibration.getDouble(0), calibration.getDouble(1),
                        calibration.getDouble(2), calibration.getDouble(3),
                    ))
                }.getOrNull()
                if (calibrated != null) return calibrated
            }

            val sensorSize = characteristics.optJSONObject("sensor_pixel_array") ?: return null
            val physicalSize = characteristics.optJSONObject("sensor_physical_size_mm") ?: return null
            val focalMm = metadata.optDouble("lens_focal_length_mm", Double.NaN)
            val focalX = focalMm * sensorSize.optDouble("width", Double.NaN) / physicalSize.optDouble("width", Double.NaN)
            val focalY = focalMm * sensorSize.optDouble("height", Double.NaN) / physicalSize.optDouble("height", Double.NaN)
            return runCatching {
                // Non-RAW streams additionally centre-crop the reported sensor
                // crop to the stream aspect ratio before scaling to output pixels.
                projectToPackedImage(CameraIntrinsics(focalX, focalY, basisWidth / 2, basisHeight / 2))
            }.getOrNull()
        }

        private fun sameArray(first: JSONObject, second: JSONObject): Boolean =
            listOf("left", "top", "right", "bottom").all { first.optDouble(it) == second.optDouble(it) }
    }
}
