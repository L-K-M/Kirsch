package ch.lkmc.kirsch.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.media.Image
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

class CapturePackageWriter(
    context: Context,
    val captureId: String,
    private val printId: String,
    private val mode: CaptureMode,
    private val requestedFrameCount: Int,
    private val characteristics: CameraCharacteristics,
    cameraJson: JSONObject,
    private val captureProfile: CaptureProfile,
) {
    private val created = Instant.now()
    private val createdElapsedMs = SystemClock.elapsedRealtime()
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val initialThermalStatus = powerManager?.currentThermalStatus
    private val root = File(
        context.getExternalFilesDir("captures") ?: File(context.filesDir, "captures"),
        captureId,
    )
    private val framesDirectory = File(root, "frames")
    private val frameRecords = Collections.synchronizedMap(mutableMapOf<Int, JSONObject>())
    private val errors = Collections.synchronizedList(mutableListOf<String>())
    private val warnings = Collections.synchronizedList(mutableListOf<String>())
    private val deliveredFrameCount = AtomicInteger()
    private val cameraRecord: JSONObject

    init {
        check(framesDirectory.mkdirs() || framesDirectory.isDirectory) {
            "Unable to create capture directory: $framesDirectory"
        }
        val characteristicsFile = File(root, "camera-characteristics.json")
        writeJsonAtomically(characteristicsFile, cameraJson)
        cameraRecord = JSONObject(cameraJson.toString())
            .put("characteristics_file", fileRecord(characteristicsFile, "camera-characteristics", "application/json"))
    }

    fun outputDirectory(): File = root

    fun addError(message: String) {
        errors += message
    }

    fun addWarning(message: String) {
        warnings += message
    }

    fun recordDeliveredFrame() {
        deliveredFrameCount.incrementAndGet()
    }

    fun writeFrame(image: Image, tagged: TaggedCaptureResult): Int {
        val tag = tagged.tag
        val stem = "frame-%02d".format(tag.frameIndex)
        val receivedElapsedNs = SystemClock.elapsedRealtimeNanos()
        var packed: Yuv420Packer.Packed? = null
        val imageFile: File
        val imageRole: String
        val mediaType: String
        val width: Int
        val height: Int
        val format: String
        if (mode == CaptureMode.RAW) {
            require(image.format == android.graphics.ImageFormat.RAW_SENSOR)
            imageFile = File(framesDirectory, "$stem.dng")
            val temporary = File(framesDirectory, "$stem.dng.partial")
            val creator = DngCreator(characteristics, tagged.result)
            try {
                FileOutputStream(temporary).use { output -> creator.writeImage(output, image) }
            } finally {
                creator.close()
            }
            moveAtomically(temporary, imageFile)
            imageRole = "dng"
            mediaType = "image/x-adobe-dng"
            width = image.width
            height = image.height
            format = "RAW_SENSOR"
        } else {
            require(image.format == android.graphics.ImageFormat.YUV_420_888)
            packed = Yuv420Packer.pack(image)
            imageFile = File(framesDirectory, "$stem.i420")
            writeBytesAtomically(imageFile, packed.bytes)
            imageRole = "i420"
            mediaType = "application/octet-stream"
            width = packed.width
            height = packed.height
            format = "I420"
        }
        val metadataFile = File(framesDirectory, "$stem.json")
        val metadata = CaptureMetadata.resultJson(
            tagged.result,
            tag,
            image.timestamp,
            receivedElapsedNs,
            packed?.sourcePlanes,
            packed?.crop ?: image.cropRect,
        )
        writeJsonAtomically(metadataFile, metadata)
        val record = JSONObject()
            .put("frame_index", tag.frameIndex)
            .put("sensor_timestamp_ns", tagged.result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP))
            .put("width", width)
            .put("height", height)
            .put("format", format)
            .put(
                "files",
                JSONArray(
                    listOf(
                        fileRecord(imageFile, imageRole, mediaType),
                        fileRecord(metadataFile, "capture-metadata", "application/json"),
                    ),
                ),
            )
            .put(
                "extensions",
                JSONObject()
                    .put("image_timestamp_ns", image.timestamp)
                    .put("capture_result_frame_number", tagged.result.frameNumber),
            )
        frameRecords[tag.frameIndex] = record
        return frameRecords.size
    }

    @Synchronized
    fun finish(success: Boolean): File {
        val sortedFrames = synchronized(frameRecords) {
            frameRecords.toSortedMap().values.toList()
        }
        val errorSnapshot = synchronized(errors) { errors.toList() }
        val warningSnapshot = synchronized(warnings) { warnings.toList() }
        val receivedCount = deliveredFrameCount.get()
        val accepted = success &&
            receivedCount == requestedFrameCount &&
            sortedFrames.size == requestedFrameCount &&
            errorSnapshot.isEmpty()
        val manifest = JSONObject()
            .put("schema_version", "0.1.0")
            .put("capture_id", captureId)
            .put("print_id", printId)
            .put("created_utc", created.toString())
            .put("completed_utc", Instant.now().toString())
            .put("status", if (accepted) "accepted" else "failed")
            .put("mode", mode.manifestValue)
            .put("requested_frame_count", requestedFrameCount)
            .put("received_frame_count", receivedCount)
            .put("persisted_frame_count", sortedFrames.size)
            .put("camera", cameraRecord)
            .put("device", CaptureMetadata.deviceJson())
            .put("frames", JSONArray(sortedFrames))
            .put("errors", JSONArray(errorSnapshot))
            .put(
                "extensions",
                JSONObject()
                    .put("warnings", JSONArray(warningSnapshot))
                    .put("capture_profile", captureProfile.manifestValue)
                    .put("requested_frame_interval_ns", captureProfile.frameIntervalNs)
                    .put("capture_elapsed_ms", SystemClock.elapsedRealtime() - createdElapsedMs)
                    .put("java_heap_used_bytes", usedHeapBytes())
                    .put("thermal_status_start", initialThermalStatus)
                    .put("thermal_status_end", powerManager?.currentThermalStatus),
            )
        val output = File(root, "capture.json")
        writeJsonAtomically(output, manifest)
        return output
    }

    private fun fileRecord(file: File, role: String, mediaType: String): JSONObject =
        JSONObject()
            .put("path", file.relativeTo(root).invariantSeparatorsPath)
            .put("role", role)
            .put("media_type", mediaType)
            .put("bytes", file.length())
            .put("sha256", sha256(file))

    private fun writeJsonAtomically(destination: File, value: JSONObject) {
        writeBytesAtomically(destination, (value.toString(2) + "\n").toByteArray(Charsets.UTF_8))
    }

    private fun writeBytesAtomically(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.partial")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        moveAtomically(temporary, destination)
    }

    private fun moveAtomically(source: File, destination: File) {
        check(!destination.exists()) { "Refusing to overwrite $destination" }
        check(source.renameTo(destination)) { "Unable to finalize $destination" }
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
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
