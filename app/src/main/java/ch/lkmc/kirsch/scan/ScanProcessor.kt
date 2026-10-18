package ch.lkmc.kirsch.scan

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import ch.lkmc.kirsch.geometry.PrintGeometry
import ch.lkmc.kirsch.imaging.BurstRegistration
import ch.lkmc.kirsch.imaging.CaptureFrameLoader
import ch.lkmc.kirsch.imaging.ConservativeFusion
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.CvType
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

class ScanProcessor(private val context: Context) {
    data class Result(val manifest: File, val preview: File, val usedFusion: Boolean)

    fun process(captureManifest: File): Result {
        require(captureManifest.name == "capture.json" && captureManifest.isFile)
        val captureDirectory = requireNotNull(captureManifest.parentFile)
        val captureRecord = JSONObject(captureManifest.readText())
        require(captureRecord.getString("status") == "accepted") { "Only accepted captures can enter processing" }
        val captureId = captureRecord.getString("capture_id")
        val root = File(scanRoot(), captureId)
        val manifestFile = File(root, "scan.json")
        val processingAttempt = if (manifestFile.isFile) {
            runCatching { JSONObject(manifestFile.readText()).optInt("processing_attempt", 0) }.getOrDefault(0) + 1
        } else {
            1
        }
        if (manifestFile.isFile) {
            val existing = JSONObject(manifestFile.readText())
            if (existing.optString("state") == "review" || existing.optString("state") == "accepted") {
                return Result(manifestFile, File(root, existing.getString("preview_path")), existing.optBoolean("used_fusion"))
            }
        }
        val cameraRecord = captureRecord.optJSONObject("camera")
        val outputQuarterTurns = if (cameraRecord?.has("capture_output_rotation_quarter_turns") == true) {
            val value = cameraRecord.opt("capture_output_rotation_quarter_turns")
            require(value is Number && value.toDouble() in 0.0..3.0 && value.toDouble() == value.toInt().toDouble()) {
                "Capture orientation must be an integer from 0 to 3 clockwise quarter turns"
            }
            value.toInt()
        } else 0
        check(root.mkdirs() || root.isDirectory) { "Unable to create scan directory: $root" }
        val started = SystemClock.elapsedRealtime()
        val thermalStart = context.getSystemService(PowerManager::class.java)?.currentThermalStatus
        val stateMachine = ScanStateMachine(ScanState.QUEUED)
        writeState(manifestFile, captureId, stateMachine.transition(ScanState.PROCESSING), processingAttempt)
        val (_, frames) = CaptureFrameLoader.load(captureDirectory)
        val resources = frames.map { it.bgr }.toMutableList()
        fun own(image: Mat): Mat = image.also { resources += it }
        try {
            val referenceIndex = BurstRegistration.referenceIndex(frames)
            val intrinsics = frames[referenceIndex].intrinsics
            val sourceBitDepth = frames.maxOf(CaptureFrameLoader.LoadedFrame::sourceBitDepth)
            var registrationReport = JSONArray()
            var fusionFailure: String? = null
            var usedFusion = false
            val fused: ConservativeFusion.Result
            if (frames.size >= 3) {
                val fallbackImage = own(frames[referenceIndex].bgr.clone())
                val registration = runCatching { BurstRegistration.register(frames) }.getOrElse { error ->
                    fusionFailure = error.message ?: error.javaClass.simpleName
                    null
                }
                if (registration != null) {
                    resources.addAll(registration.aligned)
                    resources.addAll(registration.validMasks)
                }
                if (registration != null && registration.acceptedFrameCount >= 3) {
                    registrationReport = registration.report
                    fused = ConservativeFusion.fuse(
                        registration.aligned,
                        registration.validMasks,
                        registration.referenceIndex,
                        registration.acceptedFrameCount,
                    )
                    registration.aligned.forEach(Mat::release)
                    registration.validMasks.forEach(Mat::release)
                    usedFusion = true
                    fallbackImage.release()
                } else {
                    if (registration != null) {
                        registrationReport = registration.report
                        registration.aligned.forEach(Mat::release)
                        registration.validMasks.forEach(Mat::release)
                        fusionFailure = "Fewer than three frames passed registration"
                    }
                    fused = singleFrame(fallbackImage, fusionFailed = true)
                    fallbackImage.release()
                }
            } else {
                fused = singleFrame(frames[referenceIndex].bgr, fusionFailed = false)
            }
            resources.addAll(listOf(fused.image, fused.confidence, fused.failure))
            val workingDirectory = File(root, "working").apply { mkdirs() }
            val fusedWorking = File(workingDirectory, "fused.png")
            require(Imgcodecs.imwrite(fusedWorking.absolutePath, fused.image)) { "Unable to persist fused working image" }
            val detectedQuads = PrintGeometry.detect(fused.image)
            val selectedQuad = detectedQuads.firstOrNull() ?: PrintGeometry.fullFrame(fused.image)
            val rectified = own(PrintGeometry.rectify(fused.image, selectedQuad, intrinsics = intrinsics))
            val confidence = own(PrintGeometry.rectify(fused.confidence, selectedQuad, Imgproc.INTER_NEAREST, intrinsics))
            val failure = own(PrintGeometry.rectify(fused.failure, selectedQuad, Imgproc.INTER_NEAREST, intrinsics))
            val outputRotation = when (outputQuarterTurns) {
                1 -> Core.ROTATE_90_CLOCKWISE
                2 -> Core.ROTATE_180
                3 -> Core.ROTATE_90_COUNTERCLOCKWISE
                else -> null
            }
            // Working pixels, calibration, and saved corners stay sensor-native.
            // Only the delivered image and its maps follow the capture preview.
            if (outputRotation != null) {
                listOf(rectified, confidence, failure).forEach { Core.rotate(it, it, outputRotation) }
            }
            val derivatives = File(root, "derivatives").apply { mkdirs() }
            val preview = File(derivatives, "acquisition-master.jpg")
            val tiff = File(derivatives, "acquisition-master.tif")
            val confidenceFile = File(derivatives, "confidence.png")
            val failureFile = File(derivatives, "failure.png")
            val jpegOptions = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 96).also { resources += it }
            require(Imgcodecs.imwrite(preview.absolutePath, rectified, jpegOptions)) { "JPEG export failed" }
            jpegOptions.release()
            val sixteenBit = own(Mat())
            rectified.convertTo(sixteenBit, CvType.CV_16UC3, 257.0)
            require(Imgcodecs.imwrite(tiff.absolutePath, sixteenBit)) { "TIFF export failed" }
            require(Imgcodecs.imwrite(confidenceFile.absolutePath, confidence)) { "Confidence export failed" }
            require(Imgcodecs.imwrite(failureFile.absolutePath, failure)) { "Failure export failed" }
            sixteenBit.release()
            val reportFile = File(root, "processing-report.json")
            val report = JSONObject()
                .put("capture_id", captureId)
                .put("processor", "opencv-orb-magsac-conservative-v2")
                .put("used_fusion", usedFusion)
                .put("fusion_failure", fusionFailure)
                .put("reference_frame", frames[referenceIndex].index)
                .put("auto_crop_detected", detectedQuads.isNotEmpty())
                .put("registration", registrationReport)
                .put("detected_regions", quadsJson(detectedQuads, fused.image.cols(), fused.image.rows()))
                .put("selected_quad", quadJson(selectedQuad, fused.image.cols(), fused.image.rows()))
                .put("source_bit_depth", sourceBitDepth)
                .put("output_rotation_quarter_turns", outputQuarterTurns)
                .put("output_pixel_width", rectified.cols())
                .put("output_pixel_height", rectified.rows())
                .put("tiff_container_bit_depth", 16)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("java_heap_used_bytes", usedHeapBytes())
                .put("thermal_status_start", thermalStart)
                .put("thermal_status_end", context.getSystemService(PowerManager::class.java)?.currentThermalStatus)
                .put("processing_attempt", processingAttempt)
            ScanManifestStore.write(reportFile, report)
            val manifest = JSONObject()
                .put("schema_version", "1.0.0")
                .put("scan_id", captureId)
                .put("created_utc", Instant.now().toString())
                .put("state", stateMachine.transition(ScanState.REVIEW).name.lowercase())
                .put("processing_attempt", processingAttempt)
                .put("acquisition_manifest", "capture-package:$captureId")
                .put("acquisition_sha256", sha256(captureManifest))
                .put("source_retained", true)
                .put("used_fusion", usedFusion)
                .put("fusion_failure", fusionFailure)
                .put("auto_crop_detected", detectedQuads.isNotEmpty())
                .put("working_intrinsics", intrinsics?.toJson())
                .put("output_rotation_quarter_turns", outputQuarterTurns)
                .put("preview_path", preview.relativeTo(root).invariantSeparatorsPath)
                .put("working_image_path", fusedWorking.relativeTo(root).invariantSeparatorsPath)
                .put("processing_report", reportFile.relativeTo(root).invariantSeparatorsPath)
                .put("selected_quad", quadJson(selectedQuad, fused.image.cols(), fused.image.rows()))
                .put(
                    "derivatives",
                    JSONArray(
                        listOf(
                            derivativeRecord(root, preview, "acquisition-master", "image/jpeg", outputQuarterTurns),
                            derivativeRecord(root, tiff, "acquisition-master", "image/tiff", outputQuarterTurns),
                            derivativeRecord(root, confidenceFile, "confidence-map", "image/png", outputQuarterTurns),
                            derivativeRecord(root, failureFile, "failure-map", "image/png", outputQuarterTurns),
                        ),
                    ),
                )
            ScanManifestStore.write(manifestFile, manifest)
            rectified.release()
            confidence.release()
            failure.release()
            fused.image.release()
            fused.confidence.release()
            fused.failure.release()
            frames.forEach { it.bgr.release() }
            return Result(manifestFile, preview, usedFusion)
        } finally {
            // Native storage is outside the Java heap. Every failure path must
            // release it before the worker proceeds to the next scan.
            resources.forEach(Mat::release)
        }
    }

    fun scanRoot(): File = File(context.getExternalFilesDir("scans") ?: context.filesDir, "product")

    private fun singleFrame(image: Mat, fusionFailed: Boolean): ConservativeFusion.Result =
        ConservativeFusion.Result(
            image.clone(),
            Mat.ones(image.rows(), image.cols(), CvType.CV_8UC1).also { it.setTo(org.opencv.core.Scalar(255.0)) },
            Mat.zeros(image.rows(), image.cols(), CvType.CV_8UC1).also {
                if (fusionFailed) it.setTo(org.opencv.core.Scalar(255.0))
            },
        )

    fun markFailed(captureManifest: File, error: Throwable) {
        val captureId = captureManifest.parentFile?.name ?: return
        val root = File(scanRoot(), captureId).apply { mkdirs() }
        val existing = File(root, "scan.json")
        val attempt = if (existing.isFile) {
            runCatching { JSONObject(existing.readText()).optInt("processing_attempt", 1) }.getOrDefault(1)
        } else {
            1
        }
        ScanManifestStore.write(
            existing,
            JSONObject()
                .put("schema_version", "1.0.0")
                .put("scan_id", captureId)
                .put("state", "failed")
                .put("processing_attempt", attempt)
                .put("acquisition_manifest", "capture-package:$captureId")
                .put("error", error.message ?: error.javaClass.simpleName),
        )
    }

    private fun writeState(file: File, captureId: String, state: ScanState, processingAttempt: Int) {
        ScanManifestStore.write(
            file,
            JSONObject()
                .put("schema_version", "1.0.0")
                .put("scan_id", captureId)
                .put("state", state.name.lowercase())
                .put("processing_attempt", processingAttempt)
                .put("acquisition_manifest", "capture-package:$captureId"),
        )
    }

    private fun quadsJson(quads: List<PrintGeometry.Quad>, width: Int, height: Int): JSONArray =
        JSONArray(quads.map { quadJson(it, width, height) })

    private fun quadJson(quad: PrintGeometry.Quad, width: Int, height: Int): JSONObject = JSONObject()
        .put("area_pixels", quad.area)
        .put(
            "normalized_points",
            JSONArray(quad.points.map { point -> JSONArray(listOf(point.x / (width - 1).coerceAtLeast(1), point.y / (height - 1).coerceAtLeast(1))) }),
        )

    private fun derivativeRecord(
        root: File,
        file: File,
        kind: String,
        mediaType: String,
        quarterTurns: Int,
    ): JSONObject = JSONObject()
        .put("path", file.relativeTo(root).invariantSeparatorsPath)
        .put("kind", kind)
        .put("media_type", mediaType)
        .put("output_rotation_quarter_turns", quarterTurns)
        .put("bytes", file.length())
        .put("sha256", sha256(file))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}
