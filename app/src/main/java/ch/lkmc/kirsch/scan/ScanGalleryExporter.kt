package ch.lkmc.kirsch.scan

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import ch.lkmc.kirsch.derivative.DerivativeStore
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/** Exports a chosen immutable derivative without retaining a review Activity. */
internal class ScanGalleryExporter(context: Context) {
    private val context = context.applicationContext

    fun save(manifest: File, relativePath: String): File {
        val (record, source) = ScanManifestStore.locked {
            val record = JSONObject(manifest.readText())
            require(record.getString("state") == "review") { "Only a scan in review can be saved" }
            val source = File(requireNotNull(manifest.parentFile), relativePath)
            require(source.isFile) { "Export source is missing: $relativePath" }
            record to source
        }
        // Publish outside the manifest lock. A losing acceptance race removes
        // its gallery copy so a concurrent save cannot leave a duplicate.
        val galleryUri = exportToGallery(record, source)
        try {
            DerivativeStore.accept(manifest, galleryUri.toString(), relativePath)
        } catch (error: Throwable) {
            context.contentResolver.delete(galleryUri, null, null)
            throw error
        }
        return manifest
    }

    private fun exportToGallery(record: JSONObject, source: File): Uri {
        // The gallery copy gets a human-readable name and real timestamps;
        // the machine scan_id stays in EXIF ImageDescription for provenance.
        val takenMs = runCatching { Instant.parse(record.getString("created_utc")).toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(takenMs))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Kirsch-$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Kirsch")
            put(MediaStore.Images.Media.DATE_TAKEN, takenMs)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val staged = stageWithExif(source, record, takenMs)
        try {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
                ?: error("The photo library rejected the scan")
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    staged.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not write to the photo library")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                check(context.contentResolver.update(uri, values, null, null) == 1) {
                    "The photo library could not publish the scan"
                }
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
            return uri
        } finally {
            staged.delete()
        }
    }

    /**
     * Copies the export source into cache and stamps EXIF creation time,
     * software, and the scan ID before the bytes leave app storage. The
     * on-disk derivative itself stays untouched (its recorded hash must not
     * change).
     */
    private fun stageWithExif(source: File, record: JSONObject, takenMs: Long): File {
        val staged = File(context.cacheDir, "gallery-export-${UUID.randomUUID()}.jpg")
        try {
            source.copyTo(staged, overwrite = true)
            val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            val exif = ExifInterface(staged.absolutePath)
            exif.setAttribute(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(Date(takenMs)),
            )
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Kirsch ${versionName.orEmpty()}".trim())
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, record.getString("scan_id"))
            exif.saveAttributes()
            return staged
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
    }

}
