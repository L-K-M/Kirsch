package ch.lkmc.kirsch.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

object BurstRegistration {
    private const val MINIMUM_MATCHES = 12
    private const val MINIMUM_SHARPNESS_RATIO = 0.6
    private const val MAXIMUM_RESIDUAL_PIXELS = 1.5

    data class Result(
        val aligned: List<Mat>,
        val validMasks: List<Mat>,
        val report: JSONArray,
        val referenceIndex: Int,
        val acceptedFrameCount: Int,
    )

    /** Select before registration if the caller needs a single-frame fallback. */
    fun referenceIndex(frames: List<CaptureFrameLoader.LoadedFrame>): Int {
        require(frames.isNotEmpty())
        return bestReference(frames.map { sharpness(it.bgr) })
    }

    /** Consumes the input Mats, including on failure, to bound full-resolution native memory. */
    fun register(frames: List<CaptureFrameLoader.LoadedFrame>): Result = try {
        registerFrames(frames)
    } finally {
        frames.forEach { it.bgr.release() }
    }

    private fun registerFrames(frames: List<CaptureFrameLoader.LoadedFrame>): Result {
        require(frames.isNotEmpty())
        val scores = frames.map { sharpness(it.bgr) }
        val referenceIndex = bestReference(scores)
        val reference = frames[referenceIndex].bgr
        val orb = ORB.create(8_000)
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val referenceGray = gray(reference)
        val referenceKeypoints = org.opencv.core.MatOfKeyPoint()
        val referenceDescriptors = Mat()
        val emptyMask = Mat()
        val aligned = ArrayList<Mat>(frames.size)
        val masks = ArrayList<Mat>(frames.size)
        try {
            orb.detectAndCompute(referenceGray, emptyMask, referenceKeypoints, referenceDescriptors)
            require(referenceDescriptors.rows() >= MINIMUM_MATCHES) { "Reference frame has too few features for safe fusion" }
            referenceGray.release()
            val referencePoints = referenceKeypoints.toArray()
            val reports = JSONArray()
            var accepted = 0
            frames.forEachIndexed { index, frame ->
                if (index == referenceIndex) {
                    aligned += reference.clone()
                    masks += Mat(reference.rows(), reference.cols(), CvType.CV_8UC1, Scalar(255.0))
                    reports.put(JSONObject().put("frame", frame.index).put("reference", true).put("sharpness", scores[index]))
                    accepted++
                    return@forEachIndexed
                }
                val registration = if (scores[index] < scores[referenceIndex] * MINIMUM_SHARPNESS_RATIO) {
                    rejected(reference.size(), "frame_too_blurred")
                } else {
                    registerOne(frame, reference, referencePoints, referenceDescriptors, orb, matcher)
                }
                aligned += registration.image
                masks += registration.mask
                reports.put(registration.report.put("frame", frame.index).put("sharpness", scores[index]))
                if (registration.accepted) accepted++
                frame.bgr.release()
            }
            return Result(aligned, masks, reports, referenceIndex, accepted)
        } catch (error: Throwable) {
            aligned.forEach(Mat::release)
            masks.forEach(Mat::release)
            throw error
        } finally {
            referenceGray.release()
            referenceDescriptors.release()
            referenceKeypoints.release()
            emptyMask.release()
            orb.clear()
            matcher.clear()
        }
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
    ): RegisteredFrame {
        val temporary = mutableListOf<Mat>()
        val pairs = mutableListOf<org.opencv.core.MatOfDMatch>()
        val warped = Mat()
        val valid = Mat()
        var accepted = false
        try {
            val gray = gray(frame.bgr).also(temporary::add)
            val keypoints = org.opencv.core.MatOfKeyPoint().also(temporary::add)
            val descriptors = Mat().also(temporary::add)
            orb.detectAndCompute(gray, Mat().also(temporary::add), keypoints, descriptors)
            if (descriptors.rows() < MINIMUM_MATCHES) return rejected(reference.size(), "insufficient_features")
            matcher.knnMatch(descriptors, referenceDescriptors, pairs, 2)
            val good = pairs.mapNotNull { pair ->
                val matches = pair.toArray()
                matches.firstOrNull()?.takeIf {
                    matches.size >= 2 && it.distance < 0.75f * matches[1].distance
                }
            }.distinctBy { it.trainIdx }
            if (good.size < MINIMUM_MATCHES) return rejected(reference.size(), "insufficient_matches", good.size)
            val framePoints = keypoints.toArray()
            val sourcePoints = MatOfPoint2f(*good.map { framePoints[it.queryIdx].pt }.toTypedArray()).also(temporary::add)
            val destinationPoints = MatOfPoint2f(*good.map { referencePoints[it.trainIdx].pt }.toTypedArray()).also(temporary::add)
            val inlierMask = Mat().also(temporary::add)
            val homography = Calib3d.findHomography(
                sourcePoints, destinationPoints, Calib3d.USAC_MAGSAC, MAXIMUM_RESIDUAL_PIXELS, inlierMask,
            ).also(temporary::add)
            if (homography.empty()) return rejected(reference.size(), "homography_failed", good.size)
            val maskBytes = ByteArray(inlierMask.rows())
            inlierMask.get(0, 0, maskBytes)
            val inliers = maskBytes.indices.filter { maskBytes[it].toInt() != 0 }
            if (inliers.size < MINIMUM_MATCHES || inliers.size < good.size * 0.35) {
                return rejected(reference.size(), "insufficient_inliers", good.size)
            }
            val sourceArray = sourcePoints.toArray()
            val destinationArray = destinationPoints.toArray()
            val inlierSources = inliers.map(sourceArray::get)
            val inlierDestinations = inliers.map(destinationArray::get)
            if (!hasCoverage(inlierSources, frame.bgr.size()) || !hasCoverage(inlierDestinations, reference.size())) {
                return rejected(reference.size(), "localized_inliers", good.size)
            }
            val inlierSource = MatOfPoint2f(*inlierSources.toTypedArray()).also(temporary::add)
            val projected = MatOfPoint2f().also(temporary::add)
            Core.perspectiveTransform(inlierSource, projected, homography)
            val projectedArray = projected.toArray()
            val meanResidual = inliers.indices.sumOf { position ->
                val expected = inlierDestinations[position]
                val actual = projectedArray[position]
                hypot(expected.x - actual.x, expected.y - actual.y)
            } / inliers.size
            if (!meanResidual.isFinite() || meanResidual > MAXIMUM_RESIDUAL_PIXELS) {
                return rejected(reference.size(), "residual_too_high", good.size)
            }
            val corners = MatOfPoint2f(
                Point(0.0, 0.0), Point(frame.bgr.cols().toDouble(), 0.0),
                Point(frame.bgr.cols().toDouble(), frame.bgr.rows().toDouble()), Point(0.0, frame.bgr.rows().toDouble()),
            ).also(temporary::add)
            val projectedCorners = MatOfPoint2f().also(temporary::add)
            Core.perspectiveTransform(corners, projectedCorners, homography)
            if (!plausibleFootprint(projectedCorners.toArray(), reference.size())) {
                return rejected(reference.size(), "implausible_homography", good.size)
            }
            // YUV is already tone mapped. Sensor exposure ratios are not RGB
            // gains: use robust observations of matched, unclipped features.
            val gain = observedGain(frame.bgr, reference, inlierSources, inlierDestinations)
            if (gain !in 0.8..1.25) return rejected(reference.size(), "exposure_change_too_large", good.size)
            val normalized = Mat().also(temporary::add)
            frame.bgr.convertTo(normalized, CvType.CV_8UC3, gain)
            Imgproc.warpPerspective(normalized, warped, homography, reference.size())
            val sourceMask = Mat(frame.bgr.rows(), frame.bgr.cols(), CvType.CV_8UC1, Scalar(255.0)).also(temporary::add)
            Imgproc.warpPerspective(sourceMask, valid, homography, reference.size(), Imgproc.INTER_LINEAR)
            // The same interpolation footprint as the image must be wholly
            // inside the source. Nearest masks admitted dark border mixtures.
            Imgproc.threshold(valid, valid, 254.0, 255.0, Imgproc.THRESH_BINARY)
            if (Core.countNonZero(valid) < reference.total() * 0.5) {
                return rejected(reference.size(), "insufficient_overlap", good.size)
            }
            accepted = true
            return RegisteredFrame(
                warped, valid,
                JSONObject().put("matches", good.size).put("inliers", inliers.size)
                    .put("mean_residual_px", meanResidual).put("observed_rgb_gain", gain),
                accepted = true,
            )
        } finally {
            temporary.forEach(Mat::release)
            pairs.forEach(Mat::release)
            if (!accepted) {
                warped.release()
                valid.release()
            }
        }
    }

    private fun bestReference(scores: List<Double>): Int = scores.indices.maxWith(
        compareBy<Int> { scores[it] }.thenBy { -abs(it - scores.size / 2) },
    )

    private fun sharpness(image: Mat): Double {
        val gray = gray(image)
        val reduced = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        try {
            val scale = minOf(1.0, 1_000.0 / maxOf(image.cols(), image.rows()))
            Imgproc.resize(gray, reduced, Size(), scale, scale, Imgproc.INTER_AREA)
            Imgproc.Laplacian(reduced, laplacian, CvType.CV_32F)
            Core.meanStdDev(laplacian, mean, deviation)
            return deviation.toArray()[0].let { it * it }
        } finally {
            gray.release()
            reduced.release()
            laplacian.release()
            mean.release()
            deviation.release()
        }
    }

    private fun hasCoverage(points: List<Point>, size: Size): Boolean {
        val width = points.maxOf { it.x } - points.minOf { it.x }
        val height = points.maxOf { it.y } - points.minOf { it.y }
        return width >= size.width * 0.25 && height >= size.height * 0.25 && width * height >= size.area() * 0.1
    }

    private fun plausibleFootprint(points: Array<Point>, size: Size): Boolean {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        var twiceArea = 0.0
        for (index in points.indices) {
            val first = points[index]
            val second = points[(index + 1) % 4]
            val third = points[(index + 2) % 4]
            if ((second.x - first.x) * (third.y - second.y) - (second.y - first.y) * (third.x - second.x) <= 0) return false
            twiceArea += first.x * second.y - first.y * second.x
        }
        return twiceArea / (2.0 * size.area()) in 0.5..2.0
    }

    private fun observedGain(source: Mat, reference: Mat, sourcePoints: List<Point>, referencePoints: List<Point>): Double {
        val ratios = sourcePoints.indices.step(maxOf(1, sourcePoints.size / 300)).mapNotNull { index ->
            val sourceLuma = neighborhoodLuma(source, sourcePoints[index])
            val referenceLuma = neighborhoodLuma(reference, referencePoints[index])
            if (sourceLuma in 32.0..220.0 && referenceLuma in 32.0..220.0) referenceLuma / sourceLuma else null
        }.sorted()
        if (ratios.size < MINIMUM_MATCHES) return 1.0
        // The caller rejects large changes, which cannot be compensated
        // safely in an 8-bit rendered image without clipping detail.
        return ratios[ratios.size / 2]
    }

    private fun neighborhoodLuma(image: Mat, point: Point): Double {
        val x = point.x.roundToInt().coerceIn(2, image.cols() - 3)
        val y = point.y.roundToInt().coerceIn(2, image.rows() - 3)
        val patch = image.submat(y - 2, y + 3, x - 2, x + 3)
        return try {
            val channels = Core.mean(patch).`val`
            (29 * channels[0] + 150 * channels[1] + 77 * channels[2]) / 256
        } finally {
            patch.release()
        }
    }

    private fun rejected(size: Size, reason: String, matches: Int? = null): RegisteredFrame {
        val report = JSONObject().put("error", reason)
        if (matches != null) report.put("matches", matches)
        return RegisteredFrame(Mat.zeros(size, CvType.CV_8UC3), Mat.zeros(size, CvType.CV_8UC1), report, accepted = false)
    }

    private fun gray(image: Mat): Mat {
        val result = Mat()
        try {
            Imgproc.cvtColor(image, result, Imgproc.COLOR_BGR2GRAY)
            return result
        } catch (error: Throwable) {
            result.release()
            throw error
        }
    }
}
