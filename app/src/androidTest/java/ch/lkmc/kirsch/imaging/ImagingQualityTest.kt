package ch.lkmc.kirsch.imaging

import android.test.InstrumentationTestCase
import android.util.Log
import android.hardware.camera2.CameraMetadata
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/** Exercises the bundled native OpenCV path, rather than mocked JVM Mats. */
class ImagingQualityTest : InstrumentationTestCase() {
    override fun setUp() {
        super.setUp()
        check(OpenCVLoader.initLocal())
    }

    fun testRenderedBrightnessIsPreservedWhenMetadataExposureDiffers() {
        // Camera YUV is already tone mapped. Identical rendered pixels must
        // remain identical even if the sensor exposure products differ.
        val source = texturedImage()
        val frames = listOf(1.0, 2.0, 4.0).mapIndexed { index, exposure ->
            CaptureFrameLoader.LoadedFrame(index, source.clone(), exposure, 8)
        }
        val result = BurstRegistration.register(frames)
        try {
            assertEquals(3, result.acceptedFrameCount)
            result.aligned.forEach { image ->
                assertTrue("Metadata gain changed rendered brightness", Core.norm(source, image, Core.NORM_INF) <= 1.0)
            }
        } finally {
            source.release()
            result.aligned.forEach(Mat::release)
            result.validMasks.forEach(Mat::release)
            frames.forEach { it.bgr.release() }
        }
    }

    fun testEqualLumaDifferentColorsDoNotBlendIntoReference() {
        // These colors have practically equal luma but disagree by 80 code
        // values in blue. Luma-only averaging invents an intermediate color.
        val reference = Mat(4, 4, CvType.CV_8UC3, Scalar(80.0, 120.0, 100.0))
        val other = Mat(4, 4, CvType.CV_8UC3, Scalar(160.0, 104.0, 100.0))
        val images = listOf(reference, other, reference.clone())
        val masks = images.map { Mat(4, 4, CvType.CV_8UC1, Scalar(255.0)) }
        val result = ConservativeFusion.fuse(images, masks, referenceIndex = 0)
        try {
            assertEquals(0.0, Core.norm(reference, result.image, Core.NORM_INF))
            assertEquals(170.0, result.confidence.get(0, 0)[0])
        } finally {
            images.forEach(Mat::release)
            masks.forEach(Mat::release)
            result.image.release()
            result.confidence.release()
            result.failure.release()
        }
    }

    fun testSmallResidualShiftsPreserveOrdinaryPrintDetail() {
        val reference = printedDetailImage()
        try {
            for (residualPixels in listOf(0.0, 0.75, 1.5, 3.0)) {
                // Registration can leave local sampling errors without glare.
                // A displaced dark letter must not replace the sharp reference.
                val images = listOf(reference.clone()) + listOf(1.0, -1.0).map { direction ->
                    val transform = Mat.eye(2, 3, CvType.CV_64FC1)
                    transform.put(0, 2, direction * residualPixels)
                    transform.put(1, 2, -direction * residualPixels / 2)
                    try {
                        Mat().also {
                            Imgproc.warpAffine(
                                reference, it, transform, reference.size(),
                                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE,
                            )
                        }
                    } finally {
                        transform.release()
                    }
                }
                val masks = images.map { Mat(reference.size(), CvType.CV_8UC1, Scalar.all(255.0)) }
                val result = ConservativeFusion.fuse(images, masks, referenceIndex = 0)
                val difference = Mat()
                try {
                    Core.absdiff(reference, result.image, difference)
                    val error = Core.mean(difference).`val`.take(3).average()
                    Log.i("KirschQualityTest", "ordinary_print residual_px=$residualPixels fused_rgb_mae=$error")
                    assertTrue("Residual shifts changed ordinary print detail: $error at $residualPixels px", error < 1.0)
                    assertTrue(
                        "Fusion invented large color changes without glare at $residualPixels px",
                        Core.norm(reference, result.image, Core.NORM_INF) <= 16.0,
                    )
                } finally {
                    difference.release()
                    images.forEach(Mat::release)
                    masks.forEach(Mat::release)
                    result.image.release()
                    result.confidence.release()
                    result.failure.release()
                }
            }
        } finally {
            reference.release()
        }
    }

    fun testTwoCleanViewsRemoveBroadReferenceGlare() {
        val clean = Mat(96, 96, CvType.CV_8UC3, Scalar(100.0, 120.0, 140.0))
        val reference = clean.clone()
        Imgproc.rectangle(reference, Point(20.0, 20.0), Point(75.0, 75.0), Scalar(170.0, 190.0, 210.0), -1)
        val images = listOf(reference, clean.clone(), clean.clone())
        val masks = images.map { Mat(clean.size(), CvType.CV_8UC1, Scalar.all(255.0)) }
        val result = ConservativeFusion.fuse(images, masks, referenceIndex = 0)
        try {
            assertEquals(0.0, Core.norm(clean, result.image, Core.NORM_INF))
            assertEquals(170.0, result.confidence.get(48, 48)[0])
        } finally {
            clean.release()
            images.forEach(Mat::release)
            masks.forEach(Mat::release)
            result.image.release()
            result.confidence.release()
            result.failure.release()
        }
    }

    fun testOneDarkerShadowDoesNotReplaceReference() {
        val reference = Mat(96, 96, CvType.CV_8UC3, Scalar(100.0, 120.0, 140.0))
        val shadow = reference.clone()
        Imgproc.rectangle(shadow, Point(20.0, 20.0), Point(75.0, 75.0), Scalar(40.0, 60.0, 80.0), -1)
        val images = listOf(reference, shadow, reference.clone())
        val masks = images.map { Mat(reference.size(), CvType.CV_8UC1, Scalar.all(255.0)) }
        val result = ConservativeFusion.fuse(images, masks, referenceIndex = 0)
        try {
            assertEquals(0.0, Core.norm(reference, result.image, Core.NORM_INF))
        } finally {
            images.forEach(Mat::release)
            masks.forEach(Mat::release)
            result.image.release()
            result.confidence.release()
            result.failure.release()
        }
    }

    fun testOneCleanViewCannotRemoveReferenceGlare() {
        val clean = Mat(96, 96, CvType.CV_8UC3, Scalar(100.0, 120.0, 140.0))
        val reference = clean.clone()
        Imgproc.rectangle(reference, Point(20.0, 20.0), Point(75.0, 75.0), Scalar(170.0, 190.0, 210.0), -1)
        val images = listOf(reference, clean.clone(), reference.clone())
        val masks = images.map { Mat(clean.size(), CvType.CV_8UC1, Scalar.all(255.0)) }
        val result = ConservativeFusion.fuse(images, masks, referenceIndex = 0)
        try {
            assertEquals(0.0, Core.norm(reference, result.image, Core.NORM_INF))
        } finally {
            clean.release()
            images.forEach(Mat::release)
            masks.forEach(Mat::release)
            result.image.release()
            result.confidence.release()
            result.failure.release()
        }
    }

    fun testMaskedCleanViewCannotAuthorizeGlareRemoval() {
        val clean = Mat(96, 96, CvType.CV_8UC3, Scalar(100.0, 120.0, 140.0))
        val reference = clean.clone()
        Imgproc.rectangle(reference, Point(20.0, 20.0), Point(75.0, 75.0), Scalar(170.0, 190.0, 210.0), -1)
        val images = listOf(reference, clean.clone(), clean.clone())
        val masks = listOf(
            Mat(clean.size(), CvType.CV_8UC1, Scalar.all(255.0)),
            Mat(clean.size(), CvType.CV_8UC1, Scalar.all(255.0)),
            Mat.zeros(clean.size(), CvType.CV_8UC1),
        )
        val result = ConservativeFusion.fuse(images, masks, referenceIndex = 0, contributingFrameCount = 2)
        try {
            assertEquals(0.0, Core.norm(reference, result.image, Core.NORM_INF))
        } finally {
            clean.release()
            images.forEach(Mat::release)
            masks.forEach(Mat::release)
            result.image.release()
            result.confidence.release()
            result.failure.release()
        }
    }

    fun testBroadColorDisagreementDoesNotMasqueradeAsGlare() {
        val reference = Mat(96, 96, CvType.CV_8UC3, Scalar(140.0, 160.0, 180.0))
        // Lower luma with a brighter blue channel changes hue, rather than
        // removing a neutral reflection from the reference's surface.
        val other = Mat(96, 96, CvType.CV_8UC3, Scalar(180.0, 80.0, 80.0))
        val images = listOf(reference, other, other.clone())
        val masks = images.map { Mat(reference.size(), CvType.CV_8UC1, Scalar.all(255.0)) }
        val result = ConservativeFusion.fuse(images, masks, referenceIndex = 0)
        try {
            assertEquals(0.0, Core.norm(reference, result.image, Core.NORM_INF))
        } finally {
            images.forEach(Mat::release)
            masks.forEach(Mat::release)
            result.image.release()
            result.confidence.release()
            result.failure.release()
        }
    }

    fun testBlurredMiddleFrameDoesNotBecomeReference() {
        val sharp = texturedImage()
        val blurred = Mat()
        Imgproc.GaussianBlur(sharp, blurred, org.opencv.core.Size(11.0, 11.0), 4.0)
        val frames = listOf(sharp.clone(), blurred, sharp.clone()).mapIndexed { index, image ->
            CaptureFrameLoader.LoadedFrame(index, image, 1.0, 8)
        }
        var result: BurstRegistration.Result? = null
        try {
            result = BurstRegistration.register(frames)
            assertTrue("Blurred middle observation was chosen", result.referenceIndex != 1)
            assertEquals(0.0, Core.norm(sharp, result.aligned[result.referenceIndex], Core.NORM_INF))
        } finally {
            sharp.release()
            result?.aligned?.forEach(Mat::release)
            result?.validMasks?.forEach(Mat::release)
            frames.forEach { it.bgr.release() }
        }
    }

    fun testShiftedViewsRemoveMovingGlareWithoutLosingTexture() {
        val clean = texturedColorImage()
        val shifts = listOf(0 to 0, 8 to -3, -7 to 5, 5 to 8, -4 to -8)
        val centers = listOf(80 to 80, 240 to 80, 80 to 240, 240 to 240, 160 to 160)
        val truths = shifts.map { (x, y) ->
            val transform = Mat.eye(3, 3, CvType.CV_64FC1)
            transform.put(0, 2, x.toDouble())
            transform.put(1, 2, y.toDouble())
            try {
                Mat().also { Imgproc.warpPerspective(clean, it, transform, clean.size()) }
            } finally {
                transform.release()
            }
        }
        val frames = truths.mapIndexed { index, truth ->
            val image = truth.clone()
            val glare = Mat.zeros(image.size(), CvType.CV_8UC3)
            Imgproc.circle(glare, Point(centers[index].first.toDouble(), centers[index].second.toDouble()), 35, Scalar.all(70.0), -1)
            Core.add(image, glare, image)
            glare.release()
            CaptureFrameLoader.LoadedFrame(index, image, 1.0, 8)
        }
        val referenceIndex = BurstRegistration.referenceIndex(frames)
        val reference = frames[referenceIndex].bgr.clone()
        val registration = BurstRegistration.register(frames)
        val result = ConservativeFusion.fuse(registration.aligned, registration.validMasks, registration.referenceIndex, registration.acceptedFrameCount)
        try {
            assertEquals(5, registration.acceptedFrameCount)
            val referenceError = interiorError(reference, truths[referenceIndex])
            val fusedError = interiorError(result.image, truths[referenceIndex])
            Log.i("KirschQualityTest", "synthetic reference_rgb_mae=$referenceError fused_rgb_mae=$fusedError improvement=${1 - fusedError / referenceError}")
            assertTrue("The reference must contain measurable glare", referenceError > 3.0)
            assertTrue("Fusion did not improve glare: $fusedError versus $referenceError", fusedError < referenceError * 0.3)
            assertTrue("Registration/fusion degraded fine color texture: $fusedError", fusedError < 2.0)
        } finally {
            clean.release()
            truths.forEach(Mat::release)
            reference.release()
            registration.aligned.forEach(Mat::release)
            registration.validMasks.forEach(Mat::release)
            result.image.release()
            result.confidence.release()
            result.failure.release()
            frames.forEach { it.bgr.release() }
        }
    }

    fun testLocalizedMatchesDoNotAuthorizeWholeImageFusion() {
        val image = Mat(320, 320, CvType.CV_8UC3, Scalar.all(110.0))
        val texture = texturedImage()
        val sourcePatch = texture.submat(0, 60, 0, 60)
        val targetPatch = image.submat(130, 190, 130, 190)
        sourcePatch.copyTo(targetPatch)
        sourcePatch.release()
        targetPatch.release()
        texture.release()
        val frames = List(3) { CaptureFrameLoader.LoadedFrame(it, image.clone(), 1.0, 8) }
        val registration = BurstRegistration.register(frames)
        try {
            assertEquals(1, registration.acceptedFrameCount)
            assertEquals("localized_inliers", registration.report.getJSONObject(0).getString("error"))
        } finally {
            image.release()
            registration.aligned.forEach(Mat::release)
            registration.validMasks.forEach(Mat::release)
            frames.forEach { it.bgr.release() }
        }
    }

    fun testPackedImageCropKeepsFullStreamCalibrationThroughLoader() {
        val directory = File(instrumentation.targetContext.cacheDir, "intrinsics-${UUID.randomUUID()}")
        check(directory.mkdirs())
        var frames = emptyList<CaptureFrameLoader.LoadedFrame>()
        try {
            val payload = File(directory, "frame.i420")
            payload.writeBytes(ByteArray(80 * 70 * 3 / 2) { if (it < 80 * 70) 100 else 128.toByte() })
            val characteristics = File(directory, "characteristics.json")
            val active = JSONObject().put("left", 0).put("top", 0).put("right", 4000).put("bottom", 3000)
            characteristics.writeText(JSONObject()
                .put("camera_id", "camera")
                .put("sensor_active_array", active)
                .put("sensor_pre_correction_active_array", active)
                .put("sensor_pixel_array", JSONObject().put("width", 4000).put("height", 3000))
                .put("sensor_physical_size_mm", JSONObject().put("width", 6.0).put("height", 4.5))
                .put("capture_size", JSONObject().put("width", 120).put("height", 90))
                .toString())
            val metadata = File(directory, "metadata.json")
            metadata.writeText(JSONObject()
                .put("lens_focal_length_mm", 4.0)
                .put("distortion_correction_mode", CameraMetadata.DISTORTION_CORRECTION_MODE_FAST)
                .put("image_crop_region", JSONObject().put("left", 20).put("top", 10).put("right", 100).put("bottom", 80))
                .toString())
            File(directory, "capture.json").writeText(JSONObject()
                .put("status", "accepted").put("mode", "yuv-420-888")
                .put("camera", JSONObject().put("characteristics_file", assetRecord(characteristics, "camera-characteristics")))
                .put("frames", JSONArray().put(JSONObject()
                    .put("frame_index", 0).put("width", 80).put("height", 70)
                    .put("files", JSONArray().put(assetRecord(payload, "i420")).put(assetRecord(metadata, "capture-metadata")))))
                .toString())

            frames = CaptureFrameLoader.load(directory).second
            assertEquals(1, frames.size)
            assertEquals(80, frames.single().bgr.cols())
            assertEquals(70, frames.single().bgr.rows())
            val intrinsics = requireNotNull(frames.single().intrinsics)
            assertEquals(80.0, intrinsics.focalX, 1e-9)
            assertEquals(80.0, intrinsics.focalY, 1e-9)
            assertEquals(40.0, intrinsics.centerX, 1e-9)
            assertEquals(35.0, intrinsics.centerY, 1e-9)
        } finally {
            frames.forEach { it.bgr.release() }
            directory.deleteRecursively()
        }
    }

    private fun assetRecord(file: File, role: String): JSONObject = JSONObject()
        .put("path", file.name).put("role", role).put("bytes", file.length())
        .put("sha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })

    private fun interiorError(image: Mat, truth: Mat): Double {
        val difference = Mat()
        Core.absdiff(image, truth, difference)
        val interior = difference.submat(20, 300, 20, 300)
        return try {
            Core.mean(interior).`val`.take(3).average()
        } finally {
            interior.release()
            difference.release()
        }
    }

    private fun texturedColorImage(): Mat {
        val random = Random(93)
        val pixels = ByteArray(320 * 320 * 3) { (80 + random.nextInt(100)).toByte() }
        return Mat(320, 320, CvType.CV_8UC3).also { it.put(0, 0, pixels) }
    }

    private fun printedDetailImage(): Mat {
        val image = Mat(320, 320, CvType.CV_8UC3, Scalar(220.0, 224.0, 220.0))
        Imgproc.putText(image, "SCAN", Point(15.0, 58.0), Imgproc.FONT_HERSHEY_COMPLEX, 1.05, Scalar.all(24.0), 2, Imgproc.LINE_AA)
        Imgproc.putText(image, "DETAIL", Point(15.0, 112.0), Imgproc.FONT_HERSHEY_COMPLEX, 1.05, Scalar.all(24.0), 2, Imgproc.LINE_AA)
        listOf(Scalar(20.0, 205.0, 235.0), Scalar(50.0, 55.0, 190.0), Scalar(180.0, 100.0, 50.0)).forEachIndexed { index, color ->
            val left = 180.0 + index * 50.0
            Imgproc.rectangle(image, Point(left, 145.0), Point(left + 20.0, 290.0), color, -1)
        }
        for (y in 150 until 290 step 8) {
            for (x in 25 until 150 step 8) {
                val color = if ((x / 8 + y / 8) % 2 == 0) Scalar.all(30.0) else Scalar(65.0, 85.0, 145.0)
                Imgproc.circle(image, Point(x.toDouble(), y.toDouble()), 2, color, -1, Imgproc.LINE_AA)
            }
        }
        return image
    }

    private fun texturedImage(): Mat {
        val random = Random(47)
        val pixels = ByteArray(320 * 320 * 3)
        for (offset in pixels.indices step 3) {
            val value = 40 + random.nextInt(170)
            for (channel in 0..2) pixels[offset + channel] = value.toByte()
        }
        return Mat(320, 320, CvType.CV_8UC3).also { it.put(0, 0, pixels) }
    }
}
