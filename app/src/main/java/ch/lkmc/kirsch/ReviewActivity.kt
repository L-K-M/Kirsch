package ch.lkmc.kirsch

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Spinner
import ch.lkmc.kirsch.archival.ArchivalMetadataStore
import ch.lkmc.kirsch.archival.ScaleAuthority
import ch.lkmc.kirsch.derivative.DerivativeStore
import ch.lkmc.kirsch.derivative.RestorationRecipe
import ch.lkmc.kirsch.scan.ScanManifestStore
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import org.opencv.core.Point

class ReviewActivity : Activity() {
    private lateinit var manifestFile: File
    private lateinit var cornerEditor: CornerEditorView
    private lateinit var status: TextView
    private val editingControls = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manifestFile = File(requireNotNull(intent.getStringExtra(EXTRA_MANIFEST)))
        buildUi()
        loadScan()
    }

    private fun loadScan() {
        val manifest = JSONObject(manifestFile.readText())
        val root = requireNotNull(manifestFile.parentFile)
        val working = File(root, manifest.getString("working_image_path"))
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(working, 1800) }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(working.absolutePath, options))
        cornerEditor.setImage(bitmap)
        val quadRecord = if (manifest.has("manual_quad")) {
            manifest.getJSONObject("manual_quad")
        } else {
            manifest.getJSONObject("selected_quad")
        }
        val selected = quadRecord.getJSONArray("normalized_points")
        cornerEditor.setNormalizedPoints(
            (0 until selected.length()).map { index ->
                val point = selected.getJSONArray(index)
                Point(point.getDouble(0), point.getDouble(1))
            },
        )
        val accepted = manifest.optString("state") == "accepted"
        val exported = manifest.optJSONObject("extensions")?.has("gallery_uri") == true
        status.text = if (accepted && exported) {
            getString(R.string.scan_accepted)
        } else if (accepted) {
            // Accepted before the photo-library export existed: locked, but
            // never claimed to be in the gallery.
            getString(R.string.scan_locked)
        } else {
            getString(
                R.string.review_status,
                if (manifest.optBoolean("used_fusion")) {
                    getString(R.string.review_output_fused)
                } else {
                    getString(R.string.review_output_single)
                },
            )
        }
        val editable = manifest.optString("state") == "review"
        cornerEditor.isEnabled = editable
        editingControls.forEach { it.isEnabled = editable }
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        content.addView(
            TextView(this).apply {
                setText(R.string.review_title)
                setTextColor(0xFFF3EDE2.toInt())
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        status = TextView(this).apply {
            setTextColor(0xFFD7CFC3.toInt())
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)

        content.addView(sectionHeader(R.string.review_corners_section))
        content.addView(caption(R.string.corner_editor_help))
        cornerEditor = CornerEditorView(this)
        content.addView(
            cornerEditor,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        content.addView(
            Button(this).apply {
                setText(R.string.apply_manual_corners)
                contentDescription = getString(R.string.apply_manual_corners)
                setOnClickListener {
                    runTask(getString(R.string.applying_manual_corners)) {
                        DerivativeStore.createManualRectification(manifestFile, cornerEditor.normalizedPoints()).file
                    }
                }
                editingControls += this
            },
        )
        content.addView(caption(R.string.review_corners_caption))

        content.addView(sectionHeader(R.string.review_enhance_section))
        content.addView(caption(R.string.review_enhance_caption))
        RestorationRecipe.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { recipe ->
                row.addView(
                    Button(this).apply {
                        text = recipe.label
                        contentDescription = getString(R.string.create_restored_derivative, recipe.label)
                        setOnClickListener {
                            runTask(getString(R.string.processing_recipe, recipe.label)) {
                                DerivativeStore.createRestoration(manifestFile, recipe).file
                            }
                        }
                        editingControls += this
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            content.addView(row)
        }

        content.addView(sectionHeader(R.string.review_finish_section))
        content.addView(
            Button(this).apply {
                setText(R.string.accept_scan)
                setOnClickListener { saveScan() }
                editingControls += this
            },
        )
        content.addView(caption(R.string.review_save_caption))
        content.addView(
            Button(this).apply {
                setText(R.string.archival_scale)
                setOnClickListener { showArchivalScaleDialog() }
                editingControls += this
            },
        )
        content.addView(caption(R.string.review_scale_caption))

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(0xFF0E0D0B.toInt())
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }

    private fun runTask(message: String, operation: () -> File) {
        status.text = message
        editingControls.forEach { it.isEnabled = false }
        cornerEditor.isEnabled = false
        Thread({
            val result = runCatching(operation)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        loadScan()
                        status.text = getString(R.string.derivative_created, it.name)
                    },
                    onFailure = {
                        loadScan()
                        status.text = getString(R.string.processing_failed, it.message ?: it.javaClass.simpleName)
                    },
                )
                status.announceForAccessibility(status.text)
            }
        }, "kirsch-derivative").start()
    }

    private class ExportChoice(val label: String, val relativePath: String)

    /**
     * Restored derivatives are separate copies that never replace the
     * master, so finishing a scan asks which version goes to the photo
     * library when restored copies exist. Without the chooser, an
     * enhancement the user just created would be silently ignored by the
     * export.
     */
    private fun saveScan() {
        Thread({
            val choices = runCatching(::exportChoices)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                choices.fold(
                    onSuccess = { list ->
                        if (list.size <= 1) performSave(list.first()) else showSaveChooser(list)
                    },
                    onFailure = {
                        status.text = getString(R.string.processing_failed, it.message ?: it.javaClass.simpleName)
                    },
                )
            }
        }, "kirsch-save-choices").start()
    }

    private fun exportChoices(): List<ExportChoice> {
        val manifest = ScanManifestStore.locked { JSONObject(manifestFile.readText()) }
        val choices = mutableListOf(
            ExportChoice(getString(R.string.save_version_master), manifest.getString("preview_path")),
        )
        val derivatives = manifest.optJSONArray("derivatives")
        if (derivatives != null) {
            for (index in 0 until derivatives.length()) {
                val record = derivatives.getJSONObject(index)
                if (record.optString("kind") != "restored") continue
                val recipe = record.optString("recipe")
                val label = RestorationRecipe.entries.firstOrNull { it.id == recipe }?.label ?: recipe
                choices += ExportChoice(
                    getString(R.string.save_version_restored, label),
                    record.getString("path"),
                )
            }
        }
        return choices
    }

    private fun showSaveChooser(choices: List<ExportChoice>) {
        var selected = 0
        AlertDialog.Builder(this)
            .setTitle(R.string.save_version_title)
            .setSingleChoiceItems(choices.map(ExportChoice::label).toTypedArray(), 0) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.save_version_confirm) { _, _ -> performSave(choices[selected]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Finishing a scan runs in fail-closed order: the chosen JPEG goes into
     * the device photo library first (the user-visible deliverable), then
     * the scan is accepted (locked), then the export and its source path are
     * recorded in the scan manifest's extensions.
     */
    private fun performSave(choice: ExportChoice) {
        status.text = getString(R.string.saving_scan)
        editingControls.forEach { it.isEnabled = false }
        cornerEditor.isEnabled = false
        Thread({
            val result = runCatching {
                val (record, source) = ScanManifestStore.locked {
                    val record = JSONObject(manifestFile.readText())
                    require(record.getString("state") == "review") { "Only a scan in review can be saved" }
                    val source = File(requireNotNull(manifestFile.parentFile), choice.relativePath)
                    require(source.isFile) { "Export source is missing: ${choice.relativePath}" }
                    record to source
                }
                // The slow MediaStore write runs outside the manifest lock;
                // accept() re-checks the state and records the export
                // atomically. If a race loses that re-check, its gallery row
                // is removed so no orphan duplicate stays behind.
                val galleryUri = exportToGallery(record, source)
                try {
                    DerivativeStore.accept(manifestFile, galleryUri.toString(), choice.relativePath)
                } catch (error: Throwable) {
                    contentResolver.delete(galleryUri, null, null)
                    throw error
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        loadScan()
                        status.text = getString(R.string.scan_accepted)
                    },
                    onFailure = {
                        loadScan()
                        status.text = getString(R.string.processing_failed, it.message ?: it.javaClass.simpleName)
                    },
                )
                status.announceForAccessibility(status.text)
            }
        }, "kirsch-save").start()
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
            val uri = contentResolver.insert(collection, values)
                ?: error("The photo library rejected the scan")
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    staged.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not write to the photo library")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                contentResolver.delete(uri, null, null)
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
        val staged = File(cacheDir, "gallery-export-${UUID.randomUUID()}.jpg")
        try {
            source.copyTo(staged, overwrite = true)
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
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

    private fun showArchivalScaleDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val width = EditText(this).apply {
            hint = getString(R.string.width_mm)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val height = EditText(this).apply {
            hint = getString(R.string.height_mm)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val authority = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ReviewActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.confirmed_dimensions), getString(R.string.coplanar_target)),
            )
        }
        val target = EditText(this).apply { hint = getString(R.string.target_id_optional) }
        listOf(width, height, authority, target).forEach(form::addView)
        AlertDialog.Builder(this)
            .setTitle(R.string.archival_scale)
            .setView(form)
            .setPositiveButton(R.string.record_scale) { _, _ ->
                val selectedAuthority = if (authority.selectedItemPosition == 0) {
                    ScaleAuthority.CONFIRMED_DIMENSIONS
                } else {
                    ScaleAuthority.COPLANAR_TARGET
                }
                val result = runCatching {
                    ArchivalMetadataStore.record(
                        manifestFile,
                        physicalWidthMm = requireNotNull(width.text.toString().toDoubleOrNull()),
                        physicalHeightMm = requireNotNull(height.text.toString().toDoubleOrNull()),
                        authority = selectedAuthority,
                        targetId = target.text.toString().trim().ifEmpty { null },
                    )
                }
                status.text = result.fold(
                    onSuccess = { getString(R.string.scale_recorded, it.ppiX, it.ppiY) },
                    onFailure = { getString(R.string.processing_failed, it.message ?: getString(R.string.invalid_dimensions)) },
                )
                status.announceForAccessibility(status.text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sectionHeader(resId: Int): TextView = TextView(this).apply {
        setText(resId)
        setTextColor(0xFFFFB84D.toInt())
        textSize = 13f
        letterSpacing = 0.1f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun caption(resId: Int): TextView = TextView(this).apply {
        setText(resId)
        setTextColor(0xFF8F887D.toInt())
        textSize = 12f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun sampleSize(file: File, maximumDimension: Int): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maximumDimension) sample *= 2
        return sample
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_MANIFEST = "scanManifest"
        fun intent(context: Context, manifest: File): Intent =
            Intent(context, ReviewActivity::class.java).putExtra(EXTRA_MANIFEST, manifest.absolutePath)
    }
}
