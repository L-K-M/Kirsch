package ch.lkmc.kirsch.derivative

import android.graphics.BitmapFactory
import ch.lkmc.kirsch.archival.ScaleAuthority
import ch.lkmc.kirsch.archival.ScaleMeasurement
import ch.lkmc.kirsch.geometry.PrintGeometry
import ch.lkmc.kirsch.geometry.CameraIntrinsics
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
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.imgcodecs.Imgcodecs

object DerivativeStore {
    data class Created(val file: File, val manifest: File)

    fun createRotation(scanManifest: File): Created {
        val root = requireNotNull(scanManifest.parentFile)
        val snapshot = ScanManifestStore.read(scanManifest)
        require(snapshot.getString("state") == "review") { "Accepted scans are immutable; start a new revision to edit" }
        val parent = File(root, snapshot.getString("preview_path"))
        // The paired TIFF avoids another JPEG decode/re-encode cycle on each
        // turn. A restored JPEG gets a lossless companion on its first turn.
        val lossless = File(parent.parentFile, parent.nameWithoutExtension + ".tif")
        val sourceFile = lossless.takeIf(File::isFile) ?: parent
        val source = Imgcodecs.imread(sourceFile.absolutePath, Imgcodecs.IMREAD_COLOR)
        require(!source.empty()) { "Unable to decode rotation source" }
        val rotated = Mat()
        val sixteenBit = Mat()
        val options = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 96)
        val file = uniqueFile(File(root, "derivatives"), "rotated", ".jpg")
        val tiff = File(file.parentFile, file.nameWithoutExtension + ".tif")
        try {
            Core.rotate(source, rotated, Core.ROTATE_90_CLOCKWISE)
            rotated.convertTo(sixteenBit, CvType.CV_16UC3, 257.0)
            writeImageAtomically(file, rotated, options, "Unable to write rotated scan")
            writeImageAtomically(tiff, sixteenBit, null, "Unable to write rotated TIFF")
            return ScanManifestStore.locked {
                val current = JSONObject(scanManifest.readText())
                require(current.getString("state") == "review" && current.getString("preview_path") == snapshot.getString("preview_path")) {
                    "Active scan changed while rotating"
                }
                val graph = current.getJSONArray("derivatives")
                val parentKind = (0 until graph.length()).map { graph.getJSONObject(it) }
                    .lastOrNull { it.optString("path") == snapshot.getString("preview_path") }?.optString("kind")
                val kind = if (parentKind == "restored") "restored" else "acquisition-derived"
                setOutputRotation(current, (current.optInt("output_rotation_quarter_turns", 0) + 1) % 4)
                appendDerivative(current, root, file, kind, "rotate-clockwise", sourceFile)
                appendDerivative(current, root, tiff, kind, "rotate-clockwise", sourceFile)
                current.put("preview_path", file.relativeTo(root).invariantSeparatorsPath)
                rescaleArchivalScale(current, file)
                ScanManifestStore.write(scanManifest, current)
                Created(file, scanManifest)
            }
        } catch (error: Throwable) {
            file.delete()
            tiff.delete()
            throw error
        } finally {
            source.release()
            rotated.release()
            sixteenBit.release()
            options.release()
        }
    }

    fun createRestoration(scanManifest: File, recipe: RestorationRecipe): Created {
        val parent = ScanManifestStore.locked {
            val manifest = JSONObject(scanManifest.readText())
            require(manifest.getString("state") == "review") { "Accepted scans are immutable; start a new revision to edit" }
            File(requireNotNull(scanManifest.parentFile), manifest.getString("preview_path"))
        }
        val root = requireNotNull(scanManifest.parentFile)
        val source = Imgcodecs.imread(parent.absolutePath, Imgcodecs.IMREAD_COLOR)
        require(!source.empty()) { "Unable to decode ${parent.name}" }
        val output = try {
            RestorationProcessor.apply(source, recipe)
        } finally {
            source.release()
        }
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
                // The restoration the user asked for becomes the scan's active
                // output, so review and the photo-library export show it. The
                // acquisition-derived master stays on disk and in the graph,
                // and revertToUnrestored() returns to it.
                current.put("preview_path", file.relativeTo(root).invariantSeparatorsPath)
                rescaleArchivalScale(current, file)
                ScanManifestStore.write(scanManifest, current)
                Created(file, scanManifest)
            }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /**
     * Returns the active output to the newest image derivative that is not a
     * restoration — the manually rectified master if the corners were
     * corrected, otherwise the acquisition master. Restored copies stay on
     * disk and in the derivative graph; only which one the scan currently
     * presents changes.
     */
    fun revertToUnrestored(scanManifest: File): Created = ScanManifestStore.locked {
        val manifest = JSONObject(scanManifest.readText())
        require(manifest.getString("state") == "review") {
            "Accepted scans are immutable; start a new revision to edit"
        }
        val root = requireNotNull(scanManifest.parentFile)
        val derivatives = manifest.getJSONArray("derivatives")
        var target: String? = null
        var targetRotation = 0
        for (index in 0 until derivatives.length()) {
            val entry = derivatives.getJSONObject(index)
            if (entry.optString("kind") == "restored") continue
            val path = entry.optString("path")
            // The graph also holds the TIFF container and the confidence and
            // failure maps; only a JPEG can be the presented output. Records
            // written by ScanProcessor carry media_type, records appended here
            // do not, so both signals are accepted.
            if (entry.optString("media_type") != "image/jpeg" && !path.endsWith(".jpg")) continue
            target = path
            targetRotation = entry.optInt("output_rotation_quarter_turns", 0)
        }
        val path = requireNotNull(target) { "This scan has no unrestored copy to return to" }
        val file = File(root, path)
        require(file.isFile) { "The unrestored copy is missing from storage" }
        manifest.put("preview_path", path)
        setOutputRotation(manifest, targetRotation)
        rescaleArchivalScale(manifest, file)
        ScanManifestStore.write(scanManifest, manifest)
        Created(file, scanManifest)
    }

    /**
     * Physical print size does not change when the active output does, but
     * pixel dimensions can — a manual rectification reshapes the print and a
     * classical upscale doubles it. Sampling frequency is re-derived from the
     * recorded physical size rather than dropped, so a measurement the user
     * took once survives every later edit. It is dropped only when the record
     * is not usable.
     */
    private fun rescaleArchivalScale(manifest: JSONObject, preview: File) {
        val scale = manifest.optJSONObject("archival_scale") ?: return
        val authority = ScaleAuthority.fromManifestValue(scale.optString("authority"))
        val widthMm = scale.optDouble("physical_width_mm", Double.NaN)
        val heightMm = scale.optDouble("physical_height_mm", Double.NaN)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(preview.absolutePath, bounds)
        val measurement = if (authority == null) {
            null
        } else {
            runCatching {
                ScaleMeasurement(
                    pixelWidth = bounds.outWidth,
                    pixelHeight = bounds.outHeight,
                    physicalWidthMm = widthMm,
                    physicalHeightMm = heightMm,
                    authority = authority,
                    targetId = scale.optString("target_id").takeIf(String::isNotBlank),
                )
            }.getOrNull()
        }
        if (measurement == null) {
            manifest.remove("archival_scale")
            return
        }
        scale.put("pixel_width", measurement.pixelWidth)
            .put("pixel_height", measurement.pixelHeight)
            .put("sampling_frequency_ppi_x", measurement.ppiX)
            .put("sampling_frequency_ppi_y", measurement.ppiY)
            .put("rescaled_utc", Instant.now().toString())
    }

    private fun setOutputRotation(manifest: JSONObject, quarterTurns: Int) {
        val previous = manifest.optInt("output_rotation_quarter_turns", 0)
        if ((previous % 2) != (quarterTurns % 2)) {
            manifest.optJSONObject("archival_scale")?.let { scale ->
                val width = scale.optDouble("physical_width_mm", Double.NaN)
                val height = scale.optDouble("physical_height_mm", Double.NaN)
                if (width.isFinite() && height.isFinite()) {
                    scale.put("physical_width_mm", height).put("physical_height_mm", width)
                }
            }
        }
        manifest.put("output_rotation_quarter_turns", quarterTurns)
    }

    fun createManualRectification(scanManifest: File, normalizedPoints: List<Point>): Created {
        val validatedPoints = PrintGeometry.validateNormalizedQuad(normalizedPoints)
        val snapshot = ScanManifestStore.read(scanManifest)
        require(snapshot.getString("state") == "review") { "Accepted scans are immutable; start a new revision to edit" }
        val intrinsics = CameraIntrinsics.fromJson(snapshot.optJSONObject("working_intrinsics"))
        val quarterTurns = snapshot.optInt("output_rotation_quarter_turns", 0)
        val root = requireNotNull(scanManifest.parentFile)
        val parent = File(root, snapshot.getString("working_image_path"))
        val file = uniqueFile(File(root, "derivatives"), "manual-rectified", ".jpg")
        val tiff = File(file.parentFile, file.nameWithoutExtension + ".tif")
        val source = Imgcodecs.imread(parent.absolutePath, Imgcodecs.IMREAD_COLOR)
        var rectified: Mat? = null
        val sixteenBit = Mat()
        val options = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 96)
        try {
            require(!source.empty()) { "Unable to decode manual-correction source" }
            val points = validatedPoints.map { point ->
                Point(point.x * (source.cols() - 1), point.y * (source.rows() - 1))
            }
            val quad = PrintGeometry.Quad(points, PrintGeometry.polygonArea(points))
            val image = PrintGeometry.rectify(source, quad, intrinsics = intrinsics)
            rectified = image
            source.release()
            val rotation = when (quarterTurns) {
                1 -> Core.ROTATE_90_CLOCKWISE
                2 -> Core.ROTATE_180
                3 -> Core.ROTATE_90_COUNTERCLOCKWISE
                else -> null
            }
            if (rotation != null) Core.rotate(image, image, rotation)
            writeImageAtomically(file, image, options, "Manual rectification export failed")
            image.convertTo(sixteenBit, CvType.CV_16UC3, 257.0)
            writeImageAtomically(tiff, sixteenBit, null, "Manual TIFF export failed")
            return ScanManifestStore.locked {
                val current = JSONObject(scanManifest.readText())
                require(current.getString("state") == "review" &&
                    current.getString("preview_path") == snapshot.getString("preview_path") &&
                    current.getString("working_image_path") == snapshot.getString("working_image_path") &&
                    current.optInt("output_rotation_quarter_turns", 0) == quarterTurns
                ) { "Active scan changed while processing corners" }
                appendDerivative(current, root, file, "acquisition-derived", "manual-rectification", parent)
                appendDerivative(current, root, tiff, "acquisition-derived", "manual-rectification", parent)
                current.put("preview_path", file.relativeTo(root).invariantSeparatorsPath)
                current.put("manual_quad", JSONObject().put(
                    "normalized_points",
                    org.json.JSONArray(validatedPoints.map { org.json.JSONArray(listOf(it.x, it.y)) }),
                ))
                rescaleArchivalScale(current, file)
                ScanManifestStore.write(scanManifest, current)
                Created(file, scanManifest)
            }
        } catch (error: Throwable) {
            file.delete()
            tiff.delete()
            throw error
        } finally {
            source.release()
            rectified?.release()
            sixteenBit.release()
            options.release()
        }
    }

    /**
     * Accepts (locks) a scan, optionally recording the photo-library export
     * in the same atomic manifest transaction: the state re-check, the state
     * flip, and the export record land together or not at all.
     * [gallerySourcePath] selects the accepted derivative (relative to the
     * scan root), so its orientation and physical scale describe the image
     * the user actually saved.
     */
    fun accept(
        scanManifest: File,
        galleryUri: String? = null,
        gallerySourcePath: String? = null,
    ) = ScanManifestStore.locked {
        val manifest = JSONObject(scanManifest.readText())
        require(manifest.getString("state") == "review") { "Only a scan in review can be accepted" }
        if (gallerySourcePath != null) {
            val root = requireNotNull(scanManifest.parentFile).canonicalFile
            val graph = manifest.getJSONArray("derivatives")
            val selected = (0 until graph.length()).map { graph.getJSONObject(it) }
                .lastOrNull { it.optString("path") == gallerySourcePath }
            require(selected != null &&
                (selected.optString("media_type") == "image/jpeg" || gallerySourcePath.endsWith(".jpg"))
            ) { "The selected output is not a scan image" }
            val file = File(root, gallerySourcePath).canonicalFile
            require(file.toPath().startsWith(root.toPath()) && file.isFile) {
                "The selected output is missing from scan storage"
            }
            val rotation = selected.optInt("output_rotation_quarter_turns", 0)
            require(rotation in 0..3) { "The selected output has an invalid orientation" }
            setOutputRotation(manifest, rotation)
            manifest.put("preview_path", gallerySourcePath)
            rescaleArchivalScale(manifest, file)
        }
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
                .put("output_rotation_quarter_turns", manifest.optInt("output_rotation_quarter_turns", 0))
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
        try {
            val written = if (options == null) {
                Imgcodecs.imwrite(temporary.absolutePath, image)
            } else {
                Imgcodecs.imwrite(temporary.absolutePath, image, options)
            }
            check(written) { errorMessage }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
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
