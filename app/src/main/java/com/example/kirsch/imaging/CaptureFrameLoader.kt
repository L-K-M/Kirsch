package com.example.kirsch.imaging

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
    )

    fun load(captureDirectory: File): Pair<JSONObject, List<LoadedFrame>> {
        val manifest = JSONObject(File(captureDirectory, "capture.json").readText())
        require(manifest.getString("status") == "accepted") { "Capture package is not accepted" }
        require(manifest.getString("mode") == "yuv-420-888") {
            "RAW acquisition retained without derivative: validated DNG demosaic/color processing is not available; capture a YUV quality sweep for processing"
        }
        val allFrameRecords = manifest.getJSONArray("frames")
        val selectedPositions = evenlySpacedPositions(allFrameRecords.length(), maximum = 5)
        val frames = buildList {
            for (position in selectedPositions) {
                val record = allFrameRecords.getJSONObject(position)
                val files = record.getJSONArray("files")
                var payload: File? = null
                var role: String? = null
                var metadata: File? = null
                for (index in 0 until files.length()) {
                    val file = files.getJSONObject(index)
                    val resolved = File(captureDirectory, file.getString("path"))
                    verifyFile(resolved, file)
                    when (file.getString("role")) {
                        "i420", "dng", "raw-sensor" -> {
                            payload = resolved
                            role = file.getString("role")
                        }
                        "capture-metadata" -> metadata = resolved
                    }
                }
                val source = requireNotNull(payload) { "Frame ${record.getInt("frame_index")} has no payload" }
                val image = if (role == "i420") {
                    loadI420(source, record.getInt("width"), record.getInt("height"))
                } else {
                    Mat()
                }
                require(!image.empty()) { "Unable to decode ${source.name}; the acquisition is retained" }
                val bgr = toEightBitBgr(image)
                if (bgr !== image) image.release()
                val meta = metadata?.let { JSONObject(it.readText()) }
                val exposure = meta?.optionalPositiveLong("sensor_exposure_time_ns")
                val sensitivity = meta?.optionalPositiveLong("sensor_sensitivity_iso")
                add(
                    LoadedFrame(
                        index = record.getInt("frame_index"),
                        bgr = bgr,
                        exposureProduct = if (exposure != null && sensitivity != null) {
                            exposure.toDouble() * sensitivity.toDouble()
                        } else {
                            null
                        },
                        sourceBitDepth = 8,
                    ),
                )
            }
        }
        require(frames.isNotEmpty()) { "Capture package has no frames" }
        return manifest to frames
    }

    private fun loadI420(file: File, width: Int, height: Int): Mat {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        val bytes = file.readBytes()
        require(bytes.size == width * height * 3 / 2) { "Invalid I420 byte count for ${file.name}" }
        val yuv = Mat(height * 3 / 2, width, CvType.CV_8UC1)
        yuv.put(0, 0, bytes)
        val bgr = Mat()
        Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_I420)
        yuv.release()
        return bgr
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

    private fun evenlySpacedPositions(count: Int, maximum: Int): List<Int> {
        if (count <= maximum) return (0 until count).toList()
        return (0 until maximum).map { index -> index * (count - 1) / (maximum - 1) }.distinct()
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
