package ch.lkmc.kirsch

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import org.json.JSONObject
import org.opencv.core.Point

class ReviewActivity : Activity() {
    private lateinit var manifestFile: File
    private lateinit var cornerEditor: CornerEditorView
    private lateinit var status: TextView
    private val editingControls = mutableListOf<View>()
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manifestFile = File(requireNotNull(intent.getStringExtra(EXTRA_MANIFEST)))
        buildUi()
        loadScan()
    }

    private class LoadedScan(
        val bitmap: Bitmap,
        val points: List<Point>,
        val statusText: String,
        val editable: Boolean,
    )

    /**
     * Loads the manifest and the multi-megapixel working bitmap off the main
     * thread — decoding inline froze the screen on open and after every
     * task. [statusOverride] replaces the state-derived status line so task
     * results survive the reload. Must be called from the main thread.
     */
    private fun loadScan(statusOverride: String? = null) {
        val generation = ++loadGeneration
        editingControls.forEach { it.isEnabled = false }
        cornerEditor.isEnabled = false
        Thread({
            val loaded = runCatching(::readScan)
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) {
                    // A superseded or abandoned load frees its bitmap right
                    // away instead of waiting for the GC to notice it.
                    loaded.getOrNull()?.bitmap?.recycle()
                    return@runOnUiThread
                }
                loaded.fold(
                    onSuccess = { scan ->
                        cornerEditor.setImage(scan.bitmap)
                        cornerEditor.setNormalizedPoints(scan.points)
                        status.text = statusOverride ?: scan.statusText
                        cornerEditor.isEnabled = scan.editable
                        editingControls.forEach { it.isEnabled = scan.editable }
                    },
                    onFailure = {
                        status.text = getString(R.string.processing_failed, it.message ?: it.javaClass.simpleName)
                    },
                )
                if (statusOverride != null) status.announceForAccessibility(status.text)
            }
        }, "kirsch-scan-load").start()
    }

    private fun readScan(): LoadedScan {
        val manifest = JSONObject(manifestFile.readText())
        val root = requireNotNull(manifestFile.parentFile)
        val working = File(root, manifest.getString("working_image_path"))
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(working, 1800) }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(working.absolutePath, options))
        val quadRecord = if (manifest.has("manual_quad")) {
            manifest.getJSONObject("manual_quad")
        } else {
            manifest.getJSONObject("selected_quad")
        }
        val selected = quadRecord.getJSONArray("normalized_points")
        val points = (0 until selected.length()).map { index ->
            val point = selected.getJSONArray(index)
            Point(point.getDouble(0), point.getDouble(1))
        }
        val accepted = manifest.optString("state") == "accepted"
        val exported = manifest.optJSONObject("extensions")?.has("gallery_uri") == true
        val statusText = if (accepted && exported) {
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
        return LoadedScan(bitmap, points, statusText, manifest.optString("state") == "review")
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
                    onSuccess = { loadScan(getString(R.string.derivative_created, it.name)) },
                    onFailure = {
                        loadScan(getString(R.string.processing_failed, it.message ?: it.javaClass.simpleName))
                    },
                )
            }
        }, "kirsch-derivative").start()
    }

    /**
     * Finishing a scan runs in fail-closed order: the current output JPEG
     * goes into the device photo library first (the user-visible
     * deliverable), then the scan is accepted (locked), then the export is
     * recorded in the scan manifest's extensions.
     */
    private fun saveScan() {
        status.text = getString(R.string.saving_scan)
        editingControls.forEach { it.isEnabled = false }
        cornerEditor.isEnabled = false
        Thread({
            val result = runCatching {
                val (record, root) = ScanManifestStore.locked {
                    val record = JSONObject(manifestFile.readText())
                    require(record.getString("state") == "review") { "Only a scan in review can be saved" }
                    record to requireNotNull(manifestFile.parentFile)
                }
                // The slow MediaStore write runs outside the manifest lock;
                // accept() re-checks the state and records the export
                // atomically. If a race loses that re-check, its gallery row
                // is removed so no orphan duplicate stays behind.
                val galleryUri = exportToGallery(record, root)
                try {
                    DerivativeStore.accept(manifestFile, galleryUri.toString())
                } catch (error: Throwable) {
                    contentResolver.delete(galleryUri, null, null)
                    throw error
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { loadScan(getString(R.string.scan_accepted)) },
                    onFailure = {
                        loadScan(getString(R.string.processing_failed, it.message ?: it.javaClass.simpleName))
                    },
                )
            }
        }, "kirsch-save").start()
    }

    private fun exportToGallery(record: JSONObject, root: File): Uri {
        val preview = File(root, record.getString("preview_path"))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${record.getString("scan_id")}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Kirsch")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: error("The photo library rejected the scan")
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                preview.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not write to the photo library")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            contentResolver.delete(uri, null, null)
            throw error
        }
        return uri
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
