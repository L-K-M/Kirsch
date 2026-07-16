package ch.lkmc.kirsch.derivative

import ch.lkmc.kirsch.geometry.PrintGeometry
import ch.lkmc.kirsch.scan.ScanManifestStore
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.imgcodecs.Imgcodecs

object DerivativeStore {
    data class Created(val file: File, val manifest: File)

    fun createRestoration(scanManifest: File, recipe: RestorationRecipe): Created {
        val parent = ScanManifestStore.locked {
            val manifest = JSONObject(scanManifest.readText())
            require(manifest.getString("state") == "review") { "Accepted scans are immutable; start a new revision to edit" }
            File(requireNotNull(scanManifest.parentFile), manifest.getString("preview_path"))
        }
        val root = requireNotNull(scanManifest.parentFile)
        val source = Imgcodecs.imread(parent.absolutePath, Imgcodecs.IMREAD_COLOR)
        require(!source.empty()) { "Unable to decode ${parent.name}" }
        val output = RestorationProcessor.apply(source, recipe)
        source.release()
        val file = uniqueFile(File(root, "derivatives"), "restored-${recipe.id}", ".jpg")
        val options = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 96)
        try {
            writeImageAtomically(file, output, options, "Unable to write ${recipe.label}")
        } finally {
            options.release()
            output.release()
        }
        return try {
            ScanManifestStore.locked {
                val current = JSONObject(scanManifest.readText())
                require(current.getString("state") == "review") { "Scan state changed while processing" }
                require(File(root, current.getString("preview_path")).canonicalFile == parent.canonicalFile) {
                    "Active preview changed while processing"
                }
                appendDerivative(current, root, file, "restored", recipe.id, parent)
                ScanManifestStore.write(scanManifest, current)
                Created(file, scanManifest)
            }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    fun createManualRectification(scanManifest: File, normalizedPoints: List<Point>): Created {
        val validatedPoints = PrintGeometry.validateNormalizedQuad(normalizedPoints)
        val parent = ScanManifestStore.locked {
            val manifest = JSONObject(scanManifest.readText())
            require(manifest.getString("state") == "review") { "Accepted scans are immutable; start a new revision to edit" }
            File(requireNotNull(scanManifest.parentFile), manifest.getString("working_image_path"))
        }
        val root = requireNotNull(scanManifest.parentFile)
        val source = Imgcodecs.imread(parent.absolutePath, Imgcodecs.IMREAD_COLOR)
        require(!source.empty()) { "Unable to decode manual-correction source" }
        val points = validatedPoints.map { point ->
            Point(
                point.x.coerceIn(0.0, 1.0) * source.cols(),
                point.y.coerceIn(0.0, 1.0) * source.rows(),
            )
        }
        val quad = PrintGeometry.Quad(points, PrintGeometry.polygonArea(points))
        val rectified = PrintGeometry.rectify(source, quad)
        source.release()
        val file = uniqueFile(File(root, "derivatives"), "manual-rectified", ".jpg")
        val options = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 96)
        try {
            writeImageAtomically(file, rectified, options, "Manual rectification export failed")
        } finally {
            options.release()
        }
        val tiff = File(file.parentFile, file.nameWithoutExtension + ".tif")
        val sixteenBit = Mat()
        rectified.convertTo(sixteenBit, CvType.CV_16UC3, 257.0)
        try {
            writeImageAtomically(tiff, sixteenBit, null, "Manual TIFF export failed")
        } catch (error: Throwable) {
            file.delete()
            throw error
        } finally {
            sixteenBit.release()
            rectified.release()
        }
        return try {
            ScanManifestStore.locked {
                val current = JSONObject(scanManifest.readText())
                require(current.getString("state") == "review") { "Scan state changed while processing" }
                appendDerivative(current, root, file, "acquisition-derived", "manual-rectification", parent)
                appendDerivative(current, root, tiff, "acquisition-derived", "manual-rectification", parent)
                current.put("preview_path", file.relativeTo(root).invariantSeparatorsPath)
                current.put("manual_quad", JSONObject().put(
                    "normalized_points",
                    org.json.JSONArray(validatedPoints.map { org.json.JSONArray(listOf(it.x, it.y)) }),
                ))
                current.remove("archival_scale")
                ScanManifestStore.write(scanManifest, current)
                Created(file, scanManifest)
            }
        } catch (error: Throwable) {
            file.delete()
            tiff.delete()
            throw error
        }
    }

    /**
     * Accepts (locks) a scan, optionally recording the photo-library export
     * in the same atomic manifest transaction: the state re-check, the state
     * flip, and the export record land together or not at all.
     * [gallerySourcePath] records which derivative (relative to the scan
     * root) was exported, so provenance survives the user's version choice.
     */
    fun accept(
        scanManifest: File,
        galleryUri: String? = null,
        gallerySourcePath: String? = null,
    ) = ScanManifestStore.locked {
        val manifest = JSONObject(scanManifest.readText())
        require(manifest.getString("state") == "review") { "Only a scan in review can be accepted" }
        manifest.put("state", "accepted").put("accepted_utc", Instant.now().toString())
        if (galleryUri != null) {
            val extensions = manifest.optJSONObject("extensions") ?: JSONObject()
            extensions.put("gallery_uri", galleryUri)
            extensions.put("gallery_saved_utc", Instant.now().toString())
            if (gallerySourcePath != null) extensions.put("gallery_source_path", gallerySourcePath)
            manifest.put("extensions", extensions)
        }
        ScanManifestStore.write(scanManifest, manifest)
    }

    private fun appendDerivative(
        manifest: JSONObject,
        root: File,
        file: File,
        kind: String,
        recipe: String,
        parent: File,
    ) {
        manifest.getJSONArray("derivatives").put(
            JSONObject()
                .put("path", file.relativeTo(root).invariantSeparatorsPath)
                .put("kind", kind)
                .put("recipe", recipe)
                .put("created_utc", Instant.now().toString())
                .put("parent_path", parent.relativeTo(root).invariantSeparatorsPath)
                .put("parent_sha256", sha256(parent))
                .put("bytes", file.length())
                .put("sha256", sha256(file)),
        )
    }

    private fun uniqueFile(directory: File, stem: String, extension: String): File {
        check(directory.mkdirs() || directory.isDirectory)
        return File(directory, "$stem-${UUID.randomUUID().toString().take(8)}$extension")
    }

    private fun writeImageAtomically(destination: File, image: Mat, options: MatOfInt?, errorMessage: String) {
        val temporary = File(
            destination.parentFile,
            ".${destination.nameWithoutExtension}.${UUID.randomUUID()}.partial.${destination.extension}",
        )
        val written = if (options == null) {
            Imgcodecs.imwrite(temporary.absolutePath, image)
        } else {
            Imgcodecs.imwrite(temporary.absolutePath, image, options)
        }
        if (!written) {
            temporary.delete()
            error(errorMessage)
        }
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
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
