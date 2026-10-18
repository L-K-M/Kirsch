package ch.lkmc.kirsch

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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
import ch.lkmc.kirsch.scan.ScanGalleryExporter
import java.io.File
import org.json.JSONObject
import org.opencv.core.Point
import kotlin.math.abs

class ReviewActivity : Activity(), ReviewOperations.Listener {
    private lateinit var manifestFile: File
    private lateinit var cornerEditor: CornerEditorView
    private lateinit var scanPreview: ScanPreviewView
    private lateinit var previewDetails: TextView
    private lateinit var qualityAdvisory: TextView
    private lateinit var status: TextView
    private lateinit var activeOutput: TextView
    private lateinit var saveGuidance: TextView
    private lateinit var revertButton: Button
    private lateinit var saveButton: Button
    private lateinit var rotateButton: Button
    private val editingControls = mutableListOf<View>()
    private var loadGeneration = 0
    private var operationInProgress = true
    private var editable = false
    private var restoredActive = false
    private var appliedPoints = emptyList<Point>()
    private var readyStatus = ""
    private var resumedBefore = false
    private enum class DraftPolicy { PRESERVE, RESET }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.optIn(this)
        manifestFile = File(requireNotNull(intent.getStringExtra(EXTRA_MANIFEST)))
        buildUi()
        val draft = savedInstanceState?.getDoubleArray(STATE_DRAFT_CORNERS)
            ?.takeIf { it.size == 8 }
            ?.toList()?.chunked(2)?.map { Point(it[0], it[1]) }
        ReviewOperations.bind(manifestFile, this)
        loadCurrentScan(draft)
    }

    override fun onReviewOperationChanged() {
        if (!isFinishing && !isDestroyed) loadCurrentScan()
    }

    override fun onResume() {
        super.onResume()
        ReviewOperations.bind(manifestFile, this)
        // onCreate already loaded the restored draft. Later resumes may follow
        // another review instance editing or saving this same scan.
        if (resumedBefore) {
            val draft = cornerEditor.normalizedPoints().takeIf { editable && hasUnappliedCorners() }
            loadCurrentScan(draft)
        }
        resumedBefore = true
    }

    override fun onPause() {
        ReviewOperations.unbind(manifestFile, this)
        super.onPause()
    }

    private fun loadCurrentScan(fallbackDraft: List<Point>? = null) {
        val operation = ReviewOperations.snapshot(manifestFile)
        if (operation == null) {
            loadScan(pointsOverride = fallbackDraft)
            return
        }
        val result = operation.result
        if (result == null) {
            loadScan(operation.message, operation.draft)
            return
        }
        val message = result.fold(
            onSuccess = {
                if (operation.kind == ReviewOperations.Kind.SAVE) getString(R.string.scan_accepted)
                else getString(R.string.derivative_created, it.name)
            },
            onFailure = { getString(R.string.processing_failed, it.message ?: it.javaClass.simpleName) },
        )
        val draft = operation.draft.takeIf {
            result.isFailure || operation.draftPolicy == ReviewOperations.DraftPolicy.PRESERVE
        }
        loadScan(message, draft, operation.id)
    }

    private class LoadedScan(
        val bitmap: Bitmap,
        val previewBitmap: Bitmap,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val points: List<Point>,
        val statusText: String,
        val editable: Boolean,
        val restoredLabel: String?,
        val outputLabel: String,
        val advisory: String,
    )

    /**
     * Loads the manifest and the multi-megapixel working bitmap off the main
     * thread — decoding inline froze the screen on open and after every
     * task. [statusOverride] replaces the state-derived status line so task
     * results survive the reload. Must be called from the main thread.
     */
    private fun loadScan(
        statusOverride: String? = null,
        pointsOverride: List<Point>? = null,
        completedOperationId: Long? = null,
    ) {
        val generation = ++loadGeneration
        setBusy(true)
        Thread({
            val loaded = runCatching(::readScan)
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) {
                    // A superseded or abandoned load frees its bitmap right
                    // away instead of waiting for the GC to notice it.
                    loaded.getOrNull()?.bitmap?.recycle()
                    loaded.getOrNull()?.previewBitmap?.recycle()
                    return@runOnUiThread
                }
                loaded.fold(
                    onSuccess = { scan ->
                        cornerEditor.setImage(scan.bitmap)
                        scanPreview.setImage(scan.previewBitmap)
                        previewDetails.text = getString(R.string.review_preview_dimensions, scan.pixelWidth, scan.pixelHeight)
                        qualityAdvisory.text = scan.advisory
                        qualityAdvisory.visibility = if (scan.advisory.isEmpty()) View.GONE else View.VISIBLE
                        appliedPoints = scan.points
                        cornerEditor.setNormalizedPoints(pointsOverride ?: scan.points)
                        status.text = statusOverride ?: scan.statusText
                        readyStatus = status.text.toString()
                        editable = scan.editable
                        restoredActive = scan.restoredLabel != null
                        completedOperationId?.let { ReviewOperations.acknowledge(manifestFile, it) }
                        setBusy(ReviewOperations.snapshot(manifestFile) != null)
                        if (editable && hasUnappliedCorners()) {
                            val guidance = getString(R.string.unapplied_corners)
                            status.text = if (statusOverride == null) guidance else "$statusOverride\n$guidance"
                        }
                        // What "SAVE TO PHOTOS" will actually export.
                        // Enhancements replace the active output, so the user
                        // needs to see which one is live.
                        activeOutput.text = scan.outputLabel
                    },
                    onFailure = {
                        operationInProgress = false
                        val failure = getString(R.string.review_load_failed, it.message ?: it.javaClass.simpleName)
                        status.text = if (statusOverride == null) failure else "$statusOverride\n$failure"
                    },
                )
                if (statusOverride != null) status.announceForAccessibility(status.text)
            }
        }, "kirsch-scan-load").start()
    }

    private fun readScan(): LoadedScan {
        val manifest = ScanManifestStore.read(manifestFile)
        val root = requireNotNull(manifestFile.parentFile)
        val working = File(root, manifest.getString("working_image_path"))
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(working, 1800) }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(working.absolutePath, options))
        var previewBitmap: Bitmap? = null
        try {
            val accepted = manifest.optString("state") == "accepted"
            val extensions = manifest.optJSONObject("extensions")
            val outputPath = if (accepted) {
                extensions?.optString("gallery_source_path")?.takeIf(String::isNotBlank)
                    ?: manifest.getString("preview_path")
            } else {
                manifest.getString("preview_path")
            }
            val output = File(root, outputPath)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.absolutePath, bounds)
            val previewOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize(output, 3000) }
            val decodedPreview = requireNotNull(BitmapFactory.decodeFile(output.absolutePath, previewOptions)) {
                "Unable to load the finished scan"
            }
            previewBitmap = decodedPreview
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
            require(points.size == 4 && points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0 }) {
                "The saved print corners are invalid"
            }
            val exported = manifest.optJSONObject("extensions")?.has("gallery_uri") == true
            val restoredLabel = activeRecipe(manifest, outputPath)
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
            return LoadedScan(
                bitmap,
                decodedPreview,
                bounds.outWidth,
                bounds.outHeight,
                points,
                statusText,
                manifest.optString("state") == "review",
                restoredLabel,
                activeOutputLabel(manifest, outputPath, restoredLabel),
                buildList {
                    if (manifest.has("auto_crop_detected") && !manifest.optBoolean("auto_crop_detected") && !manifest.has("manual_quad")) {
                        add(getString(if (accepted) R.string.review_uncropped_saved_warning else R.string.review_uncropped_warning))
                    }
                    if (!manifest.optBoolean("used_fusion") && manifest.optString("fusion_failure").isNotBlank()) {
                        add(getString(if (accepted) R.string.review_fusion_saved_warning else R.string.review_fusion_warning))
                    }
                }.joinToString("\n\n"),
            )
        } catch (error: Throwable) {
            bitmap.recycle()
            previewBitmap?.recycle()
            throw error
        }
    }

    /** The recipe label of the active output, or null when it is unrestored. */
    private fun activeRecipe(manifest: JSONObject, preview: String): String? {
        val derivatives = manifest.optJSONArray("derivatives") ?: return null
        for (index in derivatives.length() - 1 downTo 0) {
            val entry = derivatives.optJSONObject(index) ?: continue
            if (entry.optString("path") != preview) continue
            if (entry.optString("kind") != "restored") return null
            val recipe = entry.optString("recipe")
            return restorationLabel(recipe)
        }
        return null
    }

    private fun activeOutputLabel(manifest: JSONObject, preview: String, restoredLabel: String?): String {
        if (restoredLabel != null) return getString(R.string.active_output_restored, restoredLabel)
        val derivatives = manifest.optJSONArray("derivatives")
        if (derivatives != null) {
            for (index in derivatives.length() - 1 downTo 0) {
                val entry = derivatives.optJSONObject(index) ?: continue
                if (entry.optString("path") != preview) continue
                if (entry.optString("recipe") == "manual-rectification") {
                    return getString(R.string.active_output_corrected)
                }
                break
            }
        }
        return getString(R.string.active_output_master)
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
            setText(R.string.loading_scan)
            setTextColor(0xFFD7CFC3.toInt())
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)

        content.addView(sectionHeader(R.string.review_preview_section))
        scanPreview = ScanPreviewView(this)
        content.addView(scanPreview, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(caption(R.string.review_preview_help))
        rotateButton = Button(this).apply {
            setText(R.string.rotate_scan)
            setOnClickListener {
                val scanManifest = manifestFile
                runTask(getString(R.string.rotating_scan), DraftPolicy.RESET) {
                    DerivativeStore.createRotation(scanManifest).file
                }
            }
            editingControls += this
        }
        content.addView(rotateButton)
        previewDetails = caption(R.string.loading_scan)
        content.addView(previewDetails)
        qualityAdvisory = TextView(this).apply {
            setTextColor(0xFFFFB84D.toInt())
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(qualityAdvisory)

        content.addView(sectionHeader(R.string.review_corners_section))
        content.addView(caption(R.string.corner_editor_help))
        cornerEditor = CornerEditorView(this)
        cornerEditor.onCornersChanged = {
            setBusy(operationInProgress)
            status.text = if (hasUnappliedCorners()) getString(R.string.unapplied_corners) else readyStatus
        }
        content.addView(
            cornerEditor,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        content.addView(
            Button(this).apply {
                setText(R.string.apply_manual_corners)
                contentDescription = getString(R.string.apply_manual_corners)
                setOnClickListener {
                    val points = cornerEditor.normalizedPoints()
                    val scanManifest = manifestFile
                    runTask(getString(R.string.applying_manual_corners), DraftPolicy.RESET) {
                        DerivativeStore.createManualRectification(scanManifest, points).file
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
                            val scanManifest = manifestFile
                            runTask(getString(R.string.processing_recipe, recipe.label)) {
                                DerivativeStore.createRestoration(scanManifest, recipe).file
                            }
                        }
                        editingControls += this
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            content.addView(row)
        }
        revertButton = Button(this).apply {
            setText(R.string.revert_to_unrestored)
            setOnClickListener {
                val scanManifest = manifestFile
                runTask(getString(R.string.reverting_to_unrestored)) {
                    DerivativeStore.revertToUnrestored(scanManifest).file
                }
            }
            editingControls += this
        }
        content.addView(revertButton)

        content.addView(
            Button(this).apply {
                setText(R.string.archival_scale)
                setOnClickListener { showArchivalScaleDialog() }
                editingControls += this
            },
        )
        content.addView(caption(R.string.review_scale_caption))
        content.addView(caption(R.string.review_save_caption))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        activeOutput = TextView(this).apply {
            setTextColor(0xFFFFB84D.toInt())
            textSize = 13f
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        footer.addView(activeOutput)
        saveGuidance = TextView(this).apply {
            setText(R.string.review_apply_before_save)
            setTextColor(0xFFD7CFC3.toInt())
            textSize = 12f
            visibility = View.GONE
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        footer.addView(saveGuidance)
        saveButton = Button(this).apply {
            setText(R.string.accept_scan)
            setOnClickListener { saveScan() }
            editingControls += this
        }
        footer.addView(saveButton)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E0D0B.toInt())
            addView(
                ScrollView(this@ReviewActivity).apply {
                    addView(content, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(footer)
        }
        setContentView(root)
        // Keep the single export action reachable while the image and corner
        // controls scroll. Root insets also protect it from system bars and
        // the keyboard after a crop changes the scrollable preview's height.
        SystemBars.pad(root, left = true, top = true, right = true, bottom = true, includeIme = true)
    }

    private fun runTask(message: String, draftPolicy: DraftPolicy = DraftPolicy.PRESERVE, operation: () -> File) {
        if (operationInProgress) return
        val draft = cornerEditor.normalizedPoints()
        status.text = message
        setBusy(true)
        ReviewOperations.start(
            manifestFile, message, draft,
            if (draftPolicy == DraftPolicy.RESET) ReviewOperations.DraftPolicy.RESET else ReviewOperations.DraftPolicy.PRESERVE,
            ReviewOperations.Kind.DERIVATIVE, operation,
        )
    }

    private class ExportChoice(val label: String, val relativePath: String)

    /**
     * Finishing a scan exports the active output by default, but every
     * version on disk stays eligible: the chooser lists each exportable copy
     * with the active output preselected, so saving an older restoration or
     * the unrestored master takes one tap instead of a revert cycle.
     */
    private fun saveScan() {
        if (operationInProgress || hasUnappliedCorners()) return
        val previousStatus = status.text.toString()
        setBusy(true)
        status.setText(R.string.loading_save_versions)
        Thread({
            val options = runCatching(::exportChoices)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                options.fold(
                    onSuccess = { export ->
                        if (export.choices.size <= 1) {
                            performSave(export.choices.first())
                        } else {
                            showSaveChooser(export, previousStatus)
                        }
                    },
                    onFailure = {
                        loadScan(getString(R.string.processing_failed, it.message ?: it.javaClass.simpleName))
                    },
                )
            }
        }, "kirsch-save-choices").start()
    }

    private class ExportOptions(val choices: List<ExportChoice>, val activeIndex: Int)

    private fun exportChoices(): ExportOptions {
        val manifest = ScanManifestStore.locked { JSONObject(manifestFile.readText()) }
        val active = manifest.getString("preview_path")
        val choices = mutableListOf<ExportChoice>()
        var activeIndex = -1
        // Every JPEG in the derivative graph is exportable: the acquisition
        // master, corner-corrected copies, and each restoration. Maps and the
        // TIFF container are not photo-library material. Records written by
        // ScanProcessor carry media_type, records appended by DerivativeStore
        // do not, so both signals are accepted.
        val derivatives = manifest.optJSONArray("derivatives")
        if (derivatives != null) {
            for (index in 0 until derivatives.length()) {
                val record = derivatives.getJSONObject(index)
                val path = record.optString("path")
                if (record.optString("media_type") != "image/jpeg" && !path.endsWith(".jpg")) continue
                val label = when (record.optString("kind")) {
                    "restored" -> {
                        val recipe = record.optString("recipe")
                        val name = restorationLabel(recipe)
                        getString(R.string.save_version_restored, name)
                    }
                    "acquisition-derived" -> getString(R.string.save_version_rectified)
                    else -> getString(R.string.save_version_master)
                }
                if (path == active) activeIndex = choices.size
                choices += ExportChoice(label, path)
            }
        }
        // preview_path always points at a derivative record; if a manifest
        // somehow lacks one, still offer the active output itself.
        if (activeIndex < 0) {
            choices.add(0, ExportChoice(getString(R.string.save_version_master), active))
            activeIndex = 0
        }
        return ExportOptions(choices, activeIndex)
    }

    private fun showSaveChooser(options: ExportOptions, previousStatus: String) {
        var selected = options.activeIndex
        var saving = false
        AlertDialog.Builder(this)
            .setTitle(R.string.save_version_title)
            .setSingleChoiceItems(
                options.choices.map(ExportChoice::label).toTypedArray(),
                options.activeIndex,
            ) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.save_version_confirm) { _, _ ->
                saving = true
                performSave(options.choices[selected])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                if (!saving && !isFinishing && !isDestroyed) {
                    setBusy(false)
                    status.text = previousStatus
                }
            }
            .show()
    }

    private fun restorationLabel(recipe: String): String =
        RestorationRecipe.entries.firstOrNull { it.id == recipe }?.label
            ?: if (recipe == "rotate-clockwise") getString(R.string.rotated_copy) else recipe

    /**
     * Finishing a scan runs in fail-closed order: the chosen JPEG goes into
     * the device photo library first (the user-visible deliverable), then
     * the scan is accepted (locked), then the export and its source path are
     * recorded in the scan manifest's extensions.
     */
    private fun performSave(choice: ExportChoice) {
        val message = getString(R.string.saving_scan)
        status.text = message
        setBusy(true)
        val scanManifest = manifestFile
        val exporter = ScanGalleryExporter(applicationContext)
        ReviewOperations.start(
            scanManifest, message, cornerEditor.normalizedPoints(), ReviewOperations.DraftPolicy.PRESERVE,
            ReviewOperations.Kind.SAVE,
        ) { exporter.save(scanManifest, choice.relativePath) }
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

    private fun setBusy(busy: Boolean) {
        operationInProgress = busy
        editingControls.forEach { it.isEnabled = !busy && editable }
        cornerEditor.isEnabled = !busy && editable
        revertButton.isEnabled = !busy && editable && restoredActive
        saveButton.isEnabled = !busy && editable && !hasUnappliedCorners()
        saveGuidance.visibility = if (editable && hasUnappliedCorners()) View.VISIBLE else View.GONE
        rotateButton.isEnabled = !busy && editable && !hasUnappliedCorners()
    }

    private fun hasUnappliedCorners(): Boolean {
        val current = cornerEditor.normalizedPoints()
        if (appliedPoints.size != current.size) return false
        return current.zip(appliedPoints).any { (first, second) -> abs(first.x - second.x) > 0.000001 || abs(first.y - second.y) > 0.000001 }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (editable) outState.putDoubleArray(STATE_DRAFT_CORNERS, cornerEditor.normalizedPoints().flatMap { listOf(it.x, it.y) }.toDoubleArray())
    }

    override fun onDestroy() {
        ++loadGeneration
        ReviewOperations.unbind(manifestFile, this)
        scanPreview.releaseImage()
        cornerEditor.releaseImage()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MANIFEST = "scanManifest"
        private const val STATE_DRAFT_CORNERS = "draftCorners"
        fun intent(context: Context, manifest: File): Intent =
            Intent(context, ReviewActivity::class.java).putExtra(EXTRA_MANIFEST, manifest.absolutePath)
    }
}
