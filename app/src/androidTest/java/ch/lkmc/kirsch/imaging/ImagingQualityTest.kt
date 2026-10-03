package ch.lkmc.kirsch.imaging

import android.test.InstrumentationTestCase
import android.util.Log
import java.util.Random
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
