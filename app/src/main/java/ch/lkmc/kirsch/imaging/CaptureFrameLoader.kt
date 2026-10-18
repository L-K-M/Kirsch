package ch.lkmc.kirsch.imaging

import ch.lkmc.kirsch.geometry.CameraIntrinsics
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

object CaptureFrameLoader {
    data class LoadedFrame(
        val index: Int,
        val bgr: Mat,
        val exposureProduct: Double?,
        val sourceBitDepth: Int,
        val intrinsics: CameraIntrinsics? = null,
    )

    fun load(captureDirectory: File): Pair<JSONObject, List<LoadedFrame>> {
        val manifest = JSONObject(File(captureDirectory, "capture.json").readText())
        require(manifest.getString("status") == "accepted") { "Capture package is not accepted" }
        require(manifest.getString("mode") == "yuv-420-888") {
            "RAW acquisition retained without derivative: validated DNG demosaic/color processing is not available; capture a YUV quality sweep for processing"
        }
        val allFrameRecords = manifest.getJSONArray("frames")
        val characteristics = manifest.optJSONObject("camera")?.optJSONObject("characteristics_file")?.let { record ->
            val file = resolveAsset(captureDirectory, record.getString("path"))
            verifyFile(file, record)
            JSONObject(file.readText())
        }
        val observations = (0 until allFrameRecords.length()).map { position ->
            val record = allFrameRecords.getJSONObject(position)
            val observation = record.optJSONObject("extensions")?.optJSONObject("sweep_position")
            CaptureFrameSelection.Observation(
                observation?.optDouble("x", Double.NaN) ?: Double.NaN,
                observation?.optDouble("y", Double.NaN) ?: Double.NaN,
                observation?.optDouble("sharpness", Double.NaN) ?: Double.NaN,
            )
        }
        val selectedPositions = CaptureFrameSelection.positions(allFrameRecords.length(), maximum = 5, observations)
        val frames = mutableListOf<LoadedFrame>()
        try {
            for (position in selectedPositions) {
                val record = allFrameRecords.getJSONObject(position)
                val frameIndex = record.getInt("frame_index")
                val files = record.getJSONArray("files")
                var payload: File? = null
                var role: String? = null
                var metadata: File? = null
                for (index in 0 until files.length()) {
                    val file = files.getJSONObject(index)
                    val resolved = resolveAsset(captureDirectory, file.getString("path"))
                    verifyFile(resolved, file)
                    when (file.getString("role")) {
                        "i420", "dng", "raw-sensor" -> {
                            payload = resolved
                            role = file.getString("role")
                        }
                        "capture-metadata" -> metadata = resolved
                    }
                }
                val meta = metadata?.let { JSONObject(it.readText()) }
                val source = requireNotNull(payload) { "Frame $frameIndex has no payload" }
                val image = if (role == "i420") {
                    loadI420(source, record.getInt("width"), record.getInt("height"))
                } else {
                    Mat()
                }
                require(!image.empty()) { "Unable to decode ${source.name}; the acquisition is retained" }
                val bgr = toEightBitBgr(image)
                if (bgr !== image) image.release()
                val exposure = meta?.optionalPositiveLong("sensor_exposure_time_ns")
                val sensitivity = meta?.optionalPositiveLong("sensor_sensitivity_iso")
                val loaded = LoadedFrame(
                    index = frameIndex,
                    bgr = bgr,
                    exposureProduct = if (exposure != null && sensitivity != null) {
                        exposure.toDouble() * sensitivity.toDouble()
                    } else {
                        null
                    },
                    sourceBitDepth = 8,
                    intrinsics = if (characteristics != null && meta != null) {
                        CameraIntrinsics.fromCapture(characteristics, meta, bgr.cols(), bgr.rows())
                    } else null,
                )
                frames += loaded
            }
            require(frames.isNotEmpty()) { "Capture package has no frames" }
            return manifest to frames
        } catch (error: Throwable) {
            frames.forEach { it.bgr.release() }
            throw error
        }
    }

    private fun loadI420(file: File, width: Int, height: Int): Mat {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        val pixels = width.toLong() * height
        require(pixels <= Int.MAX_VALUE / 3) { "I420 image dimensions exceed decoder limits" }
        val bytes = file.readBytes()
        require(bytes.size.toLong() == pixels * 3 / 2) { "Invalid I420 byte count for ${file.name}" }
        val yuv = Mat(height * 3 / 2, width, CvType.CV_8UC1)
        yuv.put(0, 0, bytes)
        val bgr = Mat()
        try {
            Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_I420)
            return bgr
        } catch (error: Throwable) {
            bgr.release()
            throw error
        } finally {
            yuv.release()
        }
    }

    private fun toEightBitBgr(source: Mat): Mat {
        var image = source
        if (source.depth() != CvType.CV_8U) {
            image = Mat()
            source.convertTo(image, CvType.CV_8U, 1.0 / 256.0)
        }
        if (image.channels() == 3) return image
        val bgr = Mat()
        Imgproc.cvtColor(image, bgr, Imgproc.COLOR_GRAY2BGR)
        if (image !== source) image.release()
        return bgr
    }

    private fun JSONObject.optionalPositiveLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name).takeIf { it > 0 }

    private fun resolveAsset(directory: File, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && !path.contains('\\') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid acquisition asset path" }
        val file = File(directory, path)
        require(file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) {
            "Acquisition asset escapes its package"
        }
        return file
    }

    private fun verifyFile(file: File, record: JSONObject) {
        require(file.isFile) { "Missing acquisition asset: ${record.getString("path")}" }
        require(file.length() == record.getLong("bytes")) { "Acquisition asset size mismatch: ${record.getString("path")}" }
        require(sha256(file) == record.getString("sha256")) { "Acquisition asset hash mismatch: ${record.getString("path")}" }
    }

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
}
