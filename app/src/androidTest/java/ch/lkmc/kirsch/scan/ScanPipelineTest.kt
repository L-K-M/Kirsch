package ch.lkmc.kirsch.scan

import android.test.InstrumentationTestCase
import ch.lkmc.kirsch.derivative.DerivativeStore
import ch.lkmc.kirsch.archival.ArchivalMetadataStore
import ch.lkmc.kirsch.archival.ScaleAuthority
import ch.lkmc.kirsch.geometry.CameraIntrinsics
import ch.lkmc.kirsch.geometry.PrintGeometry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

@Suppress("DEPRECATION")
class ScanPipelineTest : InstrumentationTestCase() {
    override fun setUp() {
        super.setUp()
        check(OpenCVLoader.initLocal())
    }

    fun testFullFrameRectificationKeepsEveryPixel() {
        val source = Mat(64, 96, CvType.CV_8UC3, Scalar(30.0, 90.0, 140.0))
        val output = PrintGeometry.rectify(source, PrintGeometry.fullFrame(source))
        try {
            assertEquals(source.cols(), output.cols())
            assertEquals(source.rows(), output.rows())
            assertEquals(0.0, Core.norm(source, output, Core.NORM_INF))
        } finally {
            source.release()
            output.release()
        }
    }

    fun testAcceptingOlderVersionRestoresItsRotationAndPhysicalScale() {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "saved-version-test-${UUID.randomUUID()}").apply { mkdirs() }
        val master = File(directory, "acquisition-master.jpg")
        val source = Mat(64, 96, CvType.CV_8UC3, Scalar(40.0, 100.0, 170.0))
        try {
            check(Imgcodecs.imwrite(master.absolutePath, source))
            val manifest = File(directory, "scan.json").apply {
                writeText(JSONObject().put("state", "review").put("preview_path", master.name)
                    .put("derivatives", JSONArray().put(record(master, "acquisition-master")
                        .put("kind", "acquisition-master").put("media_type", "image/jpeg"))).toString())
            }
            ArchivalMetadataStore.record(manifest, 150.0, 100.0, ScaleAuthority.CONFIRMED_DIMENSIONS, null)
            DerivativeStore.createRotation(manifest)
            assertEquals(100.0, ScanManifestStore.read(manifest).getJSONObject("archival_scale").getDouble("physical_width_mm"))

            DerivativeStore.accept(manifest, "content://saved-master", master.name)

            val accepted = ScanManifestStore.read(manifest)
            val scale = accepted.getJSONObject("archival_scale")
            assertEquals(150.0, scale.getDouble("physical_width_mm"))
            assertEquals(100.0, scale.getDouble("physical_height_mm"))
            assertEquals(96, scale.getInt("pixel_width"))
            assertEquals(64, scale.getInt("pixel_height"))
            assertEquals(96 * 25.4 / 150.0, scale.getDouble("sampling_frequency_ppi_x"), 1e-6)
            assertEquals(64 * 25.4 / 100.0, scale.getDouble("sampling_frequency_ppi_y"), 1e-6)
            assertEquals(master.name, accepted.getString("preview_path"))
            assertEquals(0, accepted.getInt("output_rotation_quarter_turns"))
            assertEquals(master.name, accepted.getJSONObject("extensions").getString("gallery_source_path"))
        } finally {
            source.release()
            directory.deleteRecursively()
        }
    }

    fun testSingleFrameProcessingAndManualReviewRetainGeometryAndSources() = assertSingleFramePipeline(null)

    fun testCapturedOrientationSurvivesManualReviewRotationAndAcceptance() = assertSingleFramePipeline(1)

    fun testMalformedCaptureOrientationFailsBeforeProcessing() {
        val context = instrumentation.targetContext
        val processor = ScanProcessor(context)
        for (value in listOf(-1, 4, 0.5, "1", JSONObject.NULL)) {
            val captureId = "orientation-test-${UUID.randomUUID()}"
            val capture = File(context.cacheDir, captureId).apply { mkdirs() }
            val product = File(processor.scanRoot(), captureId)
            try {
                val manifest = File(capture, "capture.json").apply {
                    writeText(JSONObject().put("capture_id", captureId).put("status", "accepted").put("mode", "yuv-420-888")
                        .put("camera", JSONObject().put("capture_output_rotation_quarter_turns", value)).toString())
                }
                try {
                    processor.process(manifest)
                    fail("Accepted malformed capture orientation: $value")
                } catch (error: IllegalArgumentException) {
                    assertTrue(error.message.orEmpty().contains("orientation"))
                }
                assertFalse(product.exists())
            } finally {
                capture.deleteRecursively()
                product.deleteRecursively()
            }
        }
    }

    private fun assertSingleFramePipeline(captureQuarterTurns: Int?) {
        val context = instrumentation.targetContext
        val captureId = "capture-test-${UUID.randomUUID()}"
        val capture = File(context.cacheDir, captureId).apply { mkdirs() }
        val processor = ScanProcessor(context)
        val product = File(processor.scanRoot(), captureId)
        val source = Mat(64, 96, CvType.CV_8UC3, Scalar(40.0, 100.0, 170.0))
        val yuv = Mat()
        try {
            source.put(0, 0, byteArrayOf(80, 120, 160.toByte()))
            Imgproc.cvtColor(source, yuv, Imgproc.COLOR_BGR2YUV_I420)
            val bytes = ByteArray((yuv.total() * yuv.elemSize()).toInt())
            yuv.get(0, 0, bytes)
            val payload = File(capture, "frame.i420").apply { writeBytes(bytes) }
            val metadata = File(capture, "frame.json").apply {
                writeText(JSONObject().put("lens_focal_length_mm", 4.0)
                    .put("sensor_exposure_time_ns", 8_000_000).put("sensor_sensitivity_iso", 100).toString())
            }
            val characteristics = File(capture, "camera.json").apply {
                writeText(JSONObject().put("camera_id", "0")
                    .put("sensor_active_array", JSONObject().put("left", 0).put("top", 0).put("right", 4000).put("bottom", 3000))
                    .put("sensor_pixel_array", JSONObject().put("width", 4000).put("height", 3000))
                    .put("sensor_physical_size_mm", JSONObject().put("width", 6).put("height", 4.5)).toString())
            }
            val manifest = File(capture, "capture.json").apply {
                writeText(JSONObject().put("capture_id", captureId).put("status", "accepted").put("mode", "yuv-420-888")
                    .put("camera", JSONObject().put("characteristics_file", record(characteristics, "camera-characteristics"))
                        .put("capture_output_rotation_quarter_turns", captureQuarterTurns))
                    .put("frames", JSONArray().put(JSONObject().put("frame_index", 0).put("width", 96).put("height", 64)
                        .put("files", JSONArray().put(record(payload, "i420")).put(record(metadata, "capture-metadata"))))).toString())
            }
            val acquisitionHash = hash(manifest)
            val result = processor.process(manifest)
            assertFalse(result.usedFusion)
            val scan = ScanManifestStore.read(result.manifest)
            assertEquals("review", scan.getString("state"))
            assertFalse(scan.getBoolean("auto_crop_detected"))
            val camera = CameraIntrinsics.fromJson(scan.optJSONObject("working_intrinsics"))
            assertNotNull(camera)
            assertEquals(64.0, camera!!.focalX, 1e-6)
            val initialWidth = if (captureQuarterTurns == 1) 64 else 96
            val initialHeight = if (captureQuarterTurns == 1) 96 else 64
            val master = Imgcodecs.imread(result.preview.absolutePath)
            try {
                assertEquals(initialWidth, master.cols())
                assertEquals(initialHeight, master.rows())
            } finally { master.release() }
            if (captureQuarterTurns != null) {
                assertEquals(captureQuarterTurns, scan.getInt("output_rotation_quarter_turns"))
                val working = Imgcodecs.imread(File(product, scan.getString("working_image_path")).absolutePath)
                val expected = Mat()
                val tiff = Imgcodecs.imread(File(result.preview.parentFile, "acquisition-master.tif").absolutePath, Imgcodecs.IMREAD_UNCHANGED)
                val actual = Mat()
                try {
                    assertEquals(96, working.cols())
                    assertEquals(64, working.rows())
                    Core.rotate(working, expected, Core.ROTATE_90_CLOCKWISE)
                    tiff.convertTo(actual, CvType.CV_8UC3, 1.0 / 257.0)
                    assertEquals(0.0, Core.norm(expected, actual, Core.NORM_INF))
                    val derivatives = scan.getJSONArray("derivatives")
                    for (index in 0 until derivatives.length()) {
                        val derivative = derivatives.getJSONObject(index)
                        assertEquals(captureQuarterTurns, derivative.getInt("output_rotation_quarter_turns"))
                        val image = Imgcodecs.imread(File(product, derivative.getString("path")).absolutePath, Imgcodecs.IMREAD_UNCHANGED)
                        try {
                            assertEquals(initialWidth, image.cols())
                            assertEquals(initialHeight, image.rows())
                        } finally { image.release() }
                    }
                } finally {
                    working.release()
                    expected.release()
                    tiff.release()
                    actual.release()
                }
            }
            val corrected = DerivativeStore.createManualRectification(result.manifest, listOf(
                Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0),
            ))
            val reviewed = Imgcodecs.imread(corrected.file.absolutePath)
            try {
                assertEquals(initialWidth, reviewed.cols())
                assertEquals(initialHeight, reviewed.rows())
            } finally { reviewed.release() }
            assertEquals(acquisitionHash, hash(manifest))
            assertTrue(payload.isFile)
            assertEquals(corrected.file.name, File(ScanManifestStore.read(result.manifest).getString("preview_path")).name)
            ArchivalMetadataStore.record(result.manifest, 150.0, 100.0, ScaleAuthority.CONFIRMED_DIMENSIONS, null)
            val rotated = DerivativeStore.createRotation(result.manifest)
            val turned = Imgcodecs.imread(rotated.file.absolutePath)
            try {
                assertEquals(initialHeight, turned.cols())
                assertEquals(initialWidth, turned.rows())
            } finally { turned.release() }
            val afterTurn = DerivativeStore.createManualRectification(result.manifest, listOf(
                Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0),
            ))
            val croppedAfterTurn = Imgcodecs.imread(afterTurn.file.absolutePath)
            try {
                assertEquals(initialHeight, croppedAfterTurn.cols())
                assertEquals(initialWidth, croppedAfterTurn.rows())
            } finally { croppedAfterTurn.release() }
            val scale = ScanManifestStore.read(result.manifest).getJSONObject("archival_scale")
            assertEquals(100.0, scale.getDouble("physical_width_mm"))
            assertEquals(150.0, scale.getDouble("physical_height_mm"))
            assertEquals(initialHeight, scale.getInt("pixel_width"))
            assertEquals(initialWidth, scale.getInt("pixel_height"))
            val snapshot = hash(result.manifest)
            if (captureQuarterTurns == null) {
                DerivativeStore.accept(result.manifest)
            } else {
                DerivativeStore.accept(result.manifest, "content://oriented-master", "derivatives/acquisition-master.jpg")
                val accepted = ScanManifestStore.read(result.manifest)
                assertEquals(captureQuarterTurns, accepted.getInt("output_rotation_quarter_turns"))
                assertEquals(150.0, accepted.getJSONObject("archival_scale").getDouble("physical_width_mm"))
                assertEquals(100.0, accepted.getJSONObject("archival_scale").getDouble("physical_height_mm"))
                assertEquals(hash(result.manifest), hash(processor.process(manifest).manifest))
            }
            val acceptedHash = hash(result.manifest)
            assertFalse(snapshot == acceptedHash)
            try {
                DerivativeStore.createRotation(result.manifest)
                fail("Accepted scans must reject edits")
            } catch (_: IllegalArgumentException) {
                assertEquals(acceptedHash, hash(result.manifest))
            }
        } finally {
            source.release()
            yuv.release()
            capture.deleteRecursively()
            product.deleteRecursively()
        }
    }

    private fun record(file: File, role: String): JSONObject = JSONObject()
        .put("path", file.name).put("role", role).put("bytes", file.length()).put("sha256", hash(file))

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
