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

    fun testSingleFrameProcessingAndManualReviewRetainGeometryAndSources() {
        val context = instrumentation.targetContext
        val captureId = "capture-test-${UUID.randomUUID()}"
        val capture = File(context.cacheDir, captureId).apply { mkdirs() }
        val processor = ScanProcessor(context)
        val product = File(processor.scanRoot(), captureId)
        val source = Mat(64, 96, CvType.CV_8UC3, Scalar(40.0, 100.0, 170.0))
        val yuv = Mat()
        try {
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
                    .put("camera", JSONObject().put("characteristics_file", record(characteristics, "camera-characteristics")))
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
            val master = Imgcodecs.imread(result.preview.absolutePath)
            try {
                assertEquals(96, master.cols())
                assertEquals(64, master.rows())
            } finally { master.release() }
            val corrected = DerivativeStore.createManualRectification(result.manifest, listOf(
                Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0),
            ))
            val reviewed = Imgcodecs.imread(corrected.file.absolutePath)
            try {
                assertEquals(96, reviewed.cols())
                assertEquals(64, reviewed.rows())
            } finally { reviewed.release() }
            assertEquals(acquisitionHash, hash(manifest))
            assertTrue(payload.isFile)
            assertEquals(corrected.file.name, File(ScanManifestStore.read(result.manifest).getString("preview_path")).name)
            ArchivalMetadataStore.record(result.manifest, 150.0, 100.0, ScaleAuthority.CONFIRMED_DIMENSIONS, null)
            val rotated = DerivativeStore.createRotation(result.manifest)
            val turned = Imgcodecs.imread(rotated.file.absolutePath)
            try {
                assertEquals(64, turned.cols())
                assertEquals(96, turned.rows())
            } finally { turned.release() }
            val afterTurn = DerivativeStore.createManualRectification(result.manifest, listOf(
                Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0),
            ))
            val croppedAfterTurn = Imgcodecs.imread(afterTurn.file.absolutePath)
            try {
                assertEquals(64, croppedAfterTurn.cols())
                assertEquals(96, croppedAfterTurn.rows())
            } finally { croppedAfterTurn.release() }
            val scale = ScanManifestStore.read(result.manifest).getJSONObject("archival_scale")
            assertEquals(100.0, scale.getDouble("physical_width_mm"))
            assertEquals(150.0, scale.getDouble("physical_height_mm"))
            assertEquals(64, scale.getInt("pixel_width"))
            assertEquals(96, scale.getInt("pixel_height"))
            val snapshot = hash(result.manifest)
            DerivativeStore.accept(result.manifest)
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
