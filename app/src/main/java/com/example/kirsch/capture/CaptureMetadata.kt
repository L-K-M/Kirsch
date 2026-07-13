package com.example.kirsch.capture

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Range
import android.util.Rational
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

object CaptureMetadata {
    fun deviceJson(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("brand", Build.BRAND)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("hardware", Build.HARDWARE)
        .put("os_release", Build.VERSION.RELEASE)
        .put("sdk_int", Build.VERSION.SDK_INT)
        .put("build_fingerprint", Build.FINGERPRINT)

    fun resultJson(
        result: TotalCaptureResult,
        tag: BurstFrameTag,
        imageTimestampNs: Long,
        imageReceivedElapsedNs: Long,
        sourcePlaneLayouts: List<Yuv420Packer.PlaneLayout>?,
        crop: Rect?,
    ): JSONObject = JSONObject()
        .put("capture_id", tag.captureId)
        .put("frame_index", tag.frameIndex)
        .put("frame_number", result.frameNumber)
        .put("sequence_id", result.sequenceId)
        .put("sensor_timestamp_ns", result.get(CaptureResult.SENSOR_TIMESTAMP))
        .put("image_timestamp_ns", imageTimestampNs)
        .put("image_received_elapsed_ns", imageReceivedElapsedNs)
        .put("sensor_exposure_time_ns", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
        .put("sensor_frame_duration_ns", result.get(CaptureResult.SENSOR_FRAME_DURATION))
        .put("sensor_sensitivity_iso", result.get(CaptureResult.SENSOR_SENSITIVITY))
        .put("sensor_rolling_shutter_skew_ns", result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW))
        .put("lens_aperture", result.get(CaptureResult.LENS_APERTURE))
        .put("lens_focal_length_mm", result.get(CaptureResult.LENS_FOCAL_LENGTH))
        .put("lens_focus_distance_diopters", result.get(CaptureResult.LENS_FOCUS_DISTANCE))
        .put("ae_state", result.get(CaptureResult.CONTROL_AE_STATE))
        .put("awb_state", result.get(CaptureResult.CONTROL_AWB_STATE))
        .put("af_state", result.get(CaptureResult.CONTROL_AF_STATE))
        .put("flash_state", result.get(CaptureResult.FLASH_STATE))
        .put("ae_lock_requested", result.request.get(CaptureRequest.CONTROL_AE_LOCK))
        .put("awb_lock_requested", result.request.get(CaptureRequest.CONTROL_AWB_LOCK))
        .put("ae_mode", result.get(CaptureResult.CONTROL_AE_MODE))
        .put("awb_mode", result.get(CaptureResult.CONTROL_AWB_MODE))
        .put("af_mode", result.get(CaptureResult.CONTROL_AF_MODE))
        .put("black_level_lock", result.get(CaptureResult.BLACK_LEVEL_LOCK))
        .put("color_correction_gains", floatArray(result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
            floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue)
        }))
        .put("color_correction_transform", result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.toString())
        .put("neutral_color_point", rationalArray(result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)))
        .put("scaler_crop_region", rectJson(result.get(CaptureResult.SCALER_CROP_REGION)))
        .put("image_crop_region", rectJson(crop))
        .put(
            "active_physical_camera_id",
            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
        )
        .put("lens_shading_map_mode", result.get(CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE))
        .put("source_planes", sourcePlaneLayouts?.let(::planeLayoutsJson))

    fun characteristicsJson(
        cameraId: String,
        characteristics: CameraCharacteristics,
        previewSize: Size,
        captureSize: Size,
        mode: CaptureMode,
        minFrameDurationNs: Long,
        stallDurationNs: Long,
    ): JSONObject {
        val capabilities = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        ) ?: intArrayOf()
        val fpsRanges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
        ) ?: emptyArray<Range<Int>>()
        return JSONObject()
            .put("camera_id", cameraId)
            .put("lens_facing", characteristics.get(CameraCharacteristics.LENS_FACING))
            .put("hardware_level", characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
            .put("capabilities", JSONArray(capabilities.toList()))
            .put("sensor_orientation_degrees", characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION))
            .put("sensor_active_array", rectJson(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)))
            .put("sensor_pixel_array", sizeJson(characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)))
            .put("sensor_white_level", characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))
            .put("sensor_timestamp_source", characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
            .put("ae_lock_available", characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE))
            .put("awb_lock_available", characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE))
            .put("minimum_focus_distance", characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE))
            .put("preview_size", sizeJson(previewSize))
            .put("capture_size", sizeJson(captureSize))
            .put("capture_mode", mode.manifestValue)
            .put("output_min_frame_duration_ns", minFrameDurationNs)
            .put("output_stall_duration_ns", stallDurationNs)
            .put(
                "ae_target_fps_ranges",
                JSONArray(fpsRanges.map { range -> JSONArray(listOf(range.lower, range.upper)) }),
            )
    }

    private fun planeLayoutsJson(layouts: List<Yuv420Packer.PlaneLayout>): JSONArray =
        JSONArray(layouts.map { layout ->
            JSONObject()
                .put("row_stride", layout.rowStride)
                .put("pixel_stride", layout.pixelStride)
                .put("buffer_remaining", layout.bufferRemaining)
        })

    private fun floatArray(values: FloatArray?): Any? = values?.let { array ->
        JSONArray(array.map(Float::toDouble))
    }

    private fun rationalArray(values: Array<Rational>?): Any? = values?.let { array ->
        JSONArray(array.map { rational ->
            JSONObject()
                .put("numerator", rational.numerator)
                .put("denominator", rational.denominator)
        })
    }

    private fun rectJson(rect: Rect?): Any? = rect?.let {
        JSONObject()
            .put("left", it.left)
            .put("top", it.top)
            .put("right", it.right)
            .put("bottom", it.bottom)
    }

    private fun sizeJson(size: Size?): Any? = size?.let {
        JSONObject().put("width", it.width).put("height", it.height)
    }
}

data class BurstFrameTag(
    val generation: Long,
    val captureId: String,
    val frameIndex: Int,
)

data class TaggedCaptureResult(
    val tag: BurstFrameTag,
    val result: TotalCaptureResult,
)
