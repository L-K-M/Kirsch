package ch.lkmc.kirsch.geometry

import android.hardware.camera2.CameraMetadata
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject

@Suppress("DEPRECATION")
class CameraIntrinsicsMetadataTest : InstrumentationTestCase() {
    fun testCalibratedPrincipalPointUsesActiveRelativeCoordinates() {
        val result = requireNotNull(CameraIntrinsics.fromCapture(characteristics(), metadata(), 2000, 1500))
        assertIntrinsics(result, 1700.0, 1800.0, 1010.0, 745.0)
    }

    fun testPhysicalFallbackUsesActiveRelativeCoordinates() {
        val result = requireNotNull(CameraIntrinsics.fromCapture(characteristics(calibration = null), metadata(), 2000, 1500))
        assertIntrinsics(result, 1500.0, 1500.0, 1000.0, 750.0)
    }

    fun testAbsentCropUsesTheWholeRelativeArray() {
        val metadata = metadata(crop = null)
        val calibrated = requireNotNull(CameraIntrinsics.fromCapture(characteristics(), metadata, 2000, 1500))
        assertIntrinsics(calibrated, 1700.0, 1800.0, 1010.0, 745.0)
        val fallback = requireNotNull(CameraIntrinsics.fromCapture(characteristics(calibration = null), metadata, 2000, 1500))
        assertIntrinsics(fallback, 1500.0, 1500.0, 1000.0, 750.0)
    }

    fun testOffModeUsesPreCorrectionArrayAndCalibration() {
        val characteristics = characteristics(
            preCorrection = distinctPreCorrection(),
            calibration = doubleArrayOf(3400.0, 3600.0, 2120.0, 1590.0, 0.0),
        )
        val metadata = metadata(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF, rectangle(0, 0, 4200, 3200))
        val result = requireNotNull(CameraIntrinsics.fromCapture(characteristics, metadata, 2100, 1600))
        assertIntrinsics(result, 1700.0, 1800.0, 1060.0, 795.0)
    }

    fun testOffModeUsesPreCorrectionArrayForPhysicalFallback() {
        val characteristics = characteristics(preCorrection = distinctPreCorrection(), calibration = null)
        val metadata = metadata(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF, rectangle(0, 0, 4200, 3200))
        val result = requireNotNull(CameraIntrinsics.fromCapture(characteristics, metadata, 2100, 1600))
        assertIntrinsics(result, 1500.0, 1500.0, 1050.0, 800.0)
    }

    fun testOffModeAbsentCropUsesTheWholePreCorrectionArray() {
        val characteristics = characteristics(
            preCorrection = distinctPreCorrection(),
            calibration = doubleArrayOf(3400.0, 3600.0, 2120.0, 1590.0, 0.0),
        )
        val result = requireNotNull(CameraIntrinsics.fromCapture(
            characteristics, metadata(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF, crop = null), 2100, 1600,
        ))
        assertIntrinsics(result, 1700.0, 1800.0, 1060.0, 795.0)
    }

    fun testFastAndHighQualityUseActiveArrayAndDeclineUncorrectedCalibration() {
        for (mode in listOf(CameraMetadata.DISTORTION_CORRECTION_MODE_FAST, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)) {
            val result = requireNotNull(CameraIntrinsics.fromCapture(
                characteristics(preCorrection = distinctPreCorrection()), metadata(mode), 2000, 1500,
            ))
            assertIntrinsics(result, 1500.0, 1500.0, 1000.0, 750.0)
        }
    }

    fun testUnknownModeWithDistinctArraysDeclinesIntrinsics() {
        for (mode in listOf(null, 99)) {
            assertNull(CameraIntrinsics.fromCapture(
                characteristics(preCorrection = distinctPreCorrection()), metadata(mode), 2000, 1500,
            ))
        }
    }

    fun testUnknownModeWithMatchingArraysPreservesLegacyCalibration() {
        val result = requireNotNull(CameraIntrinsics.fromCapture(characteristics(), metadata(mode = null), 2000, 1500))
        assertIntrinsics(result, 1700.0, 1800.0, 1010.0, 745.0)
    }

    fun testOffModeWithoutPreCorrectionArrayDeclinesIntrinsics() {
        assertNull(CameraIntrinsics.fromCapture(
            characteristics(preCorrection = null), metadata(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF), 2000, 1500,
        ))
    }

    fun testPhysicalCameraMismatchStillDeclinesIntrinsics() {
        val metadata = metadata().put("active_physical_camera_id", "other-camera")
        assertNull(CameraIntrinsics.fromCapture(characteristics(), metadata, 2000, 1500))
    }

    fun testNonzeroSkewStillUsesPhysicalFallback() {
        val result = requireNotNull(CameraIntrinsics.fromCapture(
            characteristics(calibration = doubleArrayOf(3400.0, 3600.0, 2020.0, 1490.0, 2.0)), metadata(), 2000, 1500,
        ))
        assertIntrinsics(result, 1500.0, 1500.0, 1000.0, 750.0)
    }

    fun testNegativeCropOriginsDeclineIntrinsics() {
        for (crop in listOf(rectangle(-1, 0, 3999, 3000), rectangle(0, -1, 4000, 2999))) {
            assertNull(CameraIntrinsics.fromCapture(characteristics(), metadata(crop = crop), 2000, 1500))
        }
    }

    fun testCropOutsideActiveArrayDeclinesIntrinsics() {
        for (crop in listOf(rectangle(0, 0, 4001, 3000), rectangle(0, 0, 4000, 3001))) {
            assertNull(CameraIntrinsics.fromCapture(characteristics(), metadata(crop = crop), 2000, 1500))
        }
    }

    fun testCropOutsidePreCorrectionArrayDeclinesIntrinsics() {
        for (crop in listOf(rectangle(0, 0, 4201, 3200), rectangle(0, 0, 4200, 3201))) {
            assertNull(CameraIntrinsics.fromCapture(
                characteristics(preCorrection = distinctPreCorrection()),
                metadata(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF, crop), 2100, 1600,
            ))
        }
    }

    fun testPresentNonObjectCropDeclinesIntrinsics() {
        for (crop in listOf("malformed", 10, true, JSONArray(), JSONObject.NULL)) {
            val metadata = metadata().put("scaler_crop_region", crop)
            assertNull(CameraIntrinsics.fromCapture(characteristics(), metadata, 2000, 1500))
        }
    }

    fun testNonFiniteSelectedArrayOriginsDeclineIntrinsics() {
        for (coordinate in listOf("left", "top")) {
            val characteristics = characteristics()
            characteristics.getJSONObject("sensor_active_array").put(coordinate, "NaN")
            assertNull(CameraIntrinsics.fromCapture(characteristics, metadata(), 2000, 1500))
        }
    }

    fun testZeroOriginImageCropPreservesFullStreamPrincipalPoint() {
        val result = requireNotNull(CameraIntrinsics.fromCapture(
            streamCharacteristics(), streamMetadata(rectangle(0, 0, 800, 900)), 800, 900,
        ))
        assertIntrinsics(result, 800.0, 800.0, 600.0, 450.0)
    }

    fun testOffsetImageCropTranslatesPhysicalIntrinsicsWithoutRescaling() {
        val result = requireNotNull(CameraIntrinsics.fromCapture(
            streamCharacteristics(), streamMetadata(rectangle(200, 100, 1000, 800)), 800, 700,
        ))
        assertIntrinsics(result, 800.0, 800.0, 400.0, 350.0)
    }

    fun testOffsetImageCropTranslatesCalibratedIntrinsicsWithoutRescaling() {
        val result = requireNotNull(CameraIntrinsics.fromCapture(
            streamCharacteristics(doubleArrayOf(3000.0, 3100.0, 2010.0, 1505.0, 0.0)),
            streamMetadata(rectangle(200, 100, 1000, 800)), 800, 700,
        ))
        assertIntrinsics(result, 900.0, 930.0, 403.0, 351.5)
    }

    fun testFullImageCropPreservesLegacyIntrinsicsWithOrWithoutStreamSize() {
        for (knownSize in listOf(true, false)) {
            val characteristics = streamCharacteristics()
            if (!knownSize) characteristics.remove("capture_size")
            val result = requireNotNull(CameraIntrinsics.fromCapture(
                characteristics, streamMetadata(rectangle(0, 0, 1200, 900)), 1200, 900,
            ))
            assertIntrinsics(result, 800.0, 800.0, 600.0, 450.0)
        }
    }

    fun testNonFullImageCropWithoutStreamSizeDeclinesIntrinsics() {
        val characteristics = streamCharacteristics().also { it.remove("capture_size") }
        assertNull(CameraIntrinsics.fromCapture(
            characteristics, streamMetadata(rectangle(200, 100, 1000, 800)), 800, 700,
        ))
    }

    fun testAbsentImageCropWithConflictingStreamSizeDeclinesIntrinsics() {
        assertNull(CameraIntrinsics.fromCapture(streamCharacteristics(), metadata(crop = null), 800, 700))
    }

    fun testMalformedImageCropDeclinesIntrinsics() {
        val malformed = listOf(
            "malformed", JSONObject.NULL, rectangle(-1, 0, 799, 700),
            rectangle(500, 0, 1300, 700), rectangle(0, 300, 800, 1000),
            rectangle(200, 100, 999, 800), rectangle(200, 100, 1000, 800).put("left", 200.5),
            JSONObject().put("left", 200).put("top", 100).put("right", 1000),
        )
        for (crop in malformed) {
            val metadata = streamMetadata(rectangle(200, 100, 1000, 800)).put("image_crop_region", crop)
            assertNull("Accepted image crop: $crop", CameraIntrinsics.fromCapture(streamCharacteristics(), metadata, 800, 700))
        }
    }

    fun testMalformedStreamSizeDeclinesIntrinsics() {
        val malformed = listOf(
            "malformed", JSONObject.NULL, JSONObject().put("width", 1200),
            JSONObject().put("width", 0).put("height", 900),
            JSONObject().put("width", 1200.5).put("height", 900),
        )
        for (size in malformed) {
            val characteristics = streamCharacteristics().put("capture_size", size)
            assertNull("Accepted capture size: $size", CameraIntrinsics.fromCapture(
                characteristics, streamMetadata(rectangle(200, 100, 1000, 800)), 800, 700,
            ))
        }
    }

    private fun streamCharacteristics(calibration: DoubleArray? = null): JSONObject = characteristics(calibration = calibration)
        .put("sensor_active_array", rectangle(0, 0, 4000, 3000))
        .put("sensor_pre_correction_active_array", rectangle(0, 0, 4000, 3000))
        .put("sensor_pixel_array", JSONObject().put("width", 4000).put("height", 3000))
        .put("sensor_physical_size_mm", JSONObject().put("width", 6.0).put("height", 4.5))
        .put("capture_size", JSONObject().put("width", 1200).put("height", 900))

    private fun streamMetadata(imageCrop: JSONObject): JSONObject = metadata(crop = null)
        .put("lens_focal_length_mm", 4.0)
        .put("image_crop_region", imageCrop)

    private fun characteristics(
        preCorrection: JSONObject? = rectangle(100, 200, 4100, 3200),
        calibration: DoubleArray? = doubleArrayOf(3400.0, 3600.0, 2020.0, 1490.0, 0.0),
    ): JSONObject = JSONObject()
        .put("camera_id", "camera")
        .put("sensor_active_array", rectangle(100, 200, 4100, 3200))
        .put("sensor_pre_correction_active_array", preCorrection)
        .put("sensor_pixel_array", JSONObject().put("width", 4400).put("height", 3600))
        .put("sensor_physical_size_mm", JSONObject().put("width", 4.4).put("height", 3.6))
        .put("lens_intrinsic_calibration", calibration?.let { JSONArray(it.toList()) })

    private fun metadata(
        mode: Int? = CameraMetadata.DISTORTION_CORRECTION_MODE_FAST,
        crop: JSONObject? = rectangle(0, 0, 4000, 3000),
    ): JSONObject = JSONObject()
        .put("lens_focal_length_mm", 3.0)
        .put("distortion_correction_mode", mode)
        .put("scaler_crop_region", crop)

    private fun distinctPreCorrection(): JSONObject = rectangle(60, 100, 4260, 3300)

    private fun rectangle(left: Int, top: Int, right: Int, bottom: Int): JSONObject = JSONObject()
        .put("left", left).put("top", top).put("right", right).put("bottom", bottom)

    private fun assertIntrinsics(value: CameraIntrinsics, focalX: Double, focalY: Double, centerX: Double, centerY: Double) {
        assertEquals(focalX, value.focalX, 1e-9)
        assertEquals(focalY, value.focalY, 1e-9)
        assertEquals(centerX, value.centerX, 1e-9)
        assertEquals(centerY, value.centerY, 1e-9)
    }
}
