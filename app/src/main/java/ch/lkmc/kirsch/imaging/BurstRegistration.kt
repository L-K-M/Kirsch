package ch.lkmc.kirsch.imaging

import kotlin.math.hypot
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

object BurstRegistration {
    data class Result(
        val aligned: List<Mat>,
        val validMasks: List<Mat>,
        val report: JSONArray,
        val referenceIndex: Int,
        val acceptedFrameCount: Int,
    )

    fun register(frames: List<CaptureFrameLoader.LoadedFrame>): Result {
        val referenceIndex = frames.size / 2
        val reference = frames[referenceIndex].bgr
        val orb = ORB.create(8_000)
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val referenceGray = gray(reference)
        val referenceKeypoints = org.opencv.core.MatOfKeyPoint()
        val referenceDescriptors = Mat()
        orb.detectAndCompute(referenceGray, Mat(), referenceKeypoints, referenceDescriptors)
        referenceGray.release()
        require(referenceDescriptors.rows() >= 12) { "Reference frame has too few features for safe fusion" }
        val referencePoints = referenceKeypoints.toArray()
        val aligned = ArrayList<Mat>(frames.size)
        val masks = ArrayList<Mat>(frames.size)
        val reports = JSONArray()
        var accepted = 0
        frames.forEachIndexed { index, frame ->
            if (index == referenceIndex) {
                aligned += normalized(frame.bgr, frames[referenceIndex].exposureProduct, frame.exposureProduct)
                masks += Mat(frame.bgr.rows(), frame.bgr.cols(), CvType.CV_8UC1, Scalar(255.0))
                reports.put(JSONObject().put("frame", frame.index).put("reference", true))
                accepted++
                return@forEachIndexed
            }
            val registration = registerOne(
                frame,
                reference,
                referencePoints,
                referenceDescriptors,
                orb,
                matcher,
                frames[referenceIndex].exposureProduct,
            )
            aligned += registration.image
            masks += registration.mask
            reports.put(registration.report.put("frame", frame.index))
            if (registration.accepted) accepted++
            frame.bgr.release()
        }
        reference.release()
        referenceDescriptors.release()
        referenceKeypoints.release()
        orb.clear()
        matcher.clear()
        return Result(aligned, masks, reports, referenceIndex, accepted)
    }

    private data class RegisteredFrame(
        val image: Mat,
        val mask: Mat,
        val report: JSONObject,
        val accepted: Boolean,
    )

    private fun registerOne(
        frame: CaptureFrameLoader.LoadedFrame,
        reference: Mat,
        referencePoints: Array<KeyPoint>,
        referenceDescriptors: Mat,
        orb: ORB,
        matcher: DescriptorMatcher,
        referenceExposure: Double?,
    ): RegisteredFrame {
        val gray = gray(frame.bgr)
        val keypoints = org.opencv.core.MatOfKeyPoint()
        val descriptors = Mat()
        orb.detectAndCompute(gray, Mat(), keypoints, descriptors)
        gray.release()
        if (descriptors.rows() < 12) {
            keypoints.release()
            descriptors.release()
            return rejected(frame.bgr.size(), "insufficient_features")
        }
        val pairs = mutableListOf<org.opencv.core.MatOfDMatch>()
        matcher.knnMatch(descriptors, referenceDescriptors, pairs, 2)
        val good = pairs.mapNotNull { pair ->
            val matches = pair.toArray()
            pair.release()
            matches.firstOrNull()?.takeIf {
                matches.size >= 2 && it.distance < 0.75f * matches[1].distance
            }
        }
        val framePoints = keypoints.toArray()
        keypoints.release()
        descriptors.release()
        if (good.size < 12) return rejected(frame.bgr.size(), "insufficient_matches", good.size)
        val sourcePoints = MatOfPoint2f(*good.map { framePoints[it.queryIdx].pt }.toTypedArray())
        val destinationPoints = MatOfPoint2f(*good.map { referencePoints[it.trainIdx].pt }.toTypedArray())
        val inlierMask = Mat()
        val homography = Calib3d.findHomography(
            sourcePoints,
            destinationPoints,
            Calib3d.USAC_MAGSAC,
            3.0,
            inlierMask,
        )
        if (homography.empty()) {
            sourcePoints.release()
            destinationPoints.release()
            inlierMask.release()
            homography.release()
            return rejected(frame.bgr.size(), "homography_failed", good.size)
        }
        val maskBytes = ByteArray(inlierMask.rows().toInt())
        inlierMask.get(0, 0, maskBytes)
        val inliers = maskBytes.indices.filter { maskBytes[it].toInt() != 0 }
        if (inliers.size < 10) {
            sourcePoints.release()
            destinationPoints.release()
            inlierMask.release()
            homography.release()
            return rejected(frame.bgr.size(), "insufficient_inliers", good.size)
        }
        val sourceArray = sourcePoints.toArray()
        val destinationArray = destinationPoints.toArray()
        val inlierSource = MatOfPoint2f(*inliers.map(sourceArray::get).toTypedArray())
        val projected = MatOfPoint2f()
        Core.perspectiveTransform(inlierSource, projected, homography)
        val projectedArray = projected.toArray()
        val meanResidual = inliers.indices.sumOf { position ->
            val expected = destinationArray[inliers[position]]
            val actual = projectedArray[position]
            hypot(expected.x - actual.x, expected.y - actual.y)
        } / inliers.size
        val center = MatOfPoint2f(Point(frame.bgr.cols() / 2.0, frame.bgr.rows() / 2.0))
        val shiftedCenter = MatOfPoint2f()
        Core.perspectiveTransform(center, shiftedCenter, homography)
        val shiftPoint = shiftedCenter.toArray()[0]
        val centerShift = hypot(
            shiftPoint.x - frame.bgr.cols() / 2.0,
            shiftPoint.y - frame.bgr.rows() / 2.0,
        )
        val normalized = normalized(frame.bgr, referenceExposure, frame.exposureProduct)
        val warped = Mat()
        Imgproc.warpPerspective(normalized, warped, homography, reference.size())
        normalized.release()
        val sourceMask = Mat(frame.bgr.rows(), frame.bgr.cols(), CvType.CV_8UC1, Scalar(255.0))
        val valid = Mat()
        Imgproc.warpPerspective(sourceMask, valid, homography, reference.size(), Imgproc.INTER_NEAREST)
        sourceMask.release()
        sourcePoints.release()
        destinationPoints.release()
        inlierMask.release()
        homography.release()
        inlierSource.release()
        projected.release()
        center.release()
        shiftedCenter.release()
        return RegisteredFrame(
            warped,
            valid,
            JSONObject()
                .put("matches", good.size)
                .put("inliers", inliers.size)
                .put("mean_residual_px", meanResidual)
                .put("center_shift_px", centerShift),
            accepted = meanResidual <= 3.0,
        ).let { result ->
            if (meanResidual <= 3.0) result else {
                result.image.release()
                result.mask.release()
                rejected(frame.bgr.size(), "residual_too_high", good.size)
            }
        }
    }

    private fun normalized(image: Mat, referenceExposure: Double?, exposure: Double?): Mat {
        val gain = if (referenceExposure != null && exposure != null && exposure > 0) {
            (referenceExposure / exposure).coerceIn(0.25, 4.0)
        } else {
            1.0
        }
        return Mat().also { image.convertTo(it, CvType.CV_8UC3, gain) }
    }

    private fun rejected(size: org.opencv.core.Size, reason: String, matches: Int? = null): RegisteredFrame {
        val report = JSONObject().put("error", reason)
        if (matches != null) report.put("matches", matches)
        return RegisteredFrame(
            Mat.zeros(size, CvType.CV_8UC3),
            Mat.zeros(size, CvType.CV_8UC1),
            report,
            accepted = false,
        )
    }

    private fun gray(image: Mat): Mat = Mat().also { Imgproc.cvtColor(image, it, Imgproc.COLOR_BGR2GRAY) }
}
