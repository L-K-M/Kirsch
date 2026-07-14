package ch.lkmc.kirsch

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Spinner
import ch.lkmc.kirsch.archival.ArchivalMetadataStore
import ch.lkmc.kirsch.archival.ScaleAuthority
import ch.lkmc.kirsch.derivative.DerivativeStore
import ch.lkmc.kirsch.derivative.FeatureAvailability
import ch.lkmc.kirsch.derivative.FeatureCatalog
import ch.lkmc.kirsch.derivative.RestorationRecipe
import java.io.File
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
        status.text = getString(
            R.string.review_status,
            if (manifest.optBoolean("used_fusion")) "fused" else "single-frame fallback",
            manifest.optString("state"),
        )
        val editable = manifest.optString("state") == "review"
        cornerEditor.isEnabled = editable
        editingControls.forEach { it.isEnabled = editable }
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(0xff151412.toInt())
        }
        content.addView(TextView(this).apply {
            setText(R.string.review_title)
            setTextColor(0xfff3ede2.toInt())
            textSize = 22f
        })
        content.addView(TextView(this).apply {
            setText(R.string.corner_editor_help)
            setTextColor(0xffd7cfc3.toInt())
            textSize = 14f
        })
        cornerEditor = CornerEditorView(this)
        content.addView(cornerEditor, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Button(this).apply {
            setText(R.string.apply_manual_corners)
            contentDescription = getString(R.string.apply_manual_corners)
            setOnClickListener { runTask(getString(R.string.applying_manual_corners)) {
                DerivativeStore.createManualRectification(manifestFile, cornerEditor.normalizedPoints()).file
            } }
            editingControls += this
        })
        val recipeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        RestorationRecipe.entries.forEach { recipe ->
            recipeRow.addView(Button(this).apply {
                text = recipe.label
                contentDescription = getString(R.string.create_restored_derivative, recipe.label)
                setOnClickListener { runTask(getString(R.string.processing_recipe, recipe.label)) {
                    DerivativeStore.createRestoration(manifestFile, recipe).file
                } }
                editingControls += this
            })
        }
        content.addView(HorizontalScrollView(this).apply { addView(recipeRow) })
        val actionRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        actionRow.addView(Button(this).apply {
            setText(R.string.accept_scan)
            setOnClickListener {
                val result = runCatching { DerivativeStore.accept(manifestFile) }
                status.text = result.fold(
                    onSuccess = {
                        loadScan()
                        getString(R.string.scan_accepted)
                    },
                    onFailure = { getString(R.string.processing_failed, it.message ?: it.javaClass.simpleName) },
                )
                status.announceForAccessibility(status.text)
            }
            editingControls += this
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply {
            setText(R.string.feature_status)
            setOnClickListener { showFeatureStatus() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply {
            setText(R.string.archival_scale)
            setOnClickListener { showArchivalScaleDialog() }
            editingControls += this
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(actionRow)
        status = TextView(this).apply {
            setTextColor(0xffd7cfc3.toInt())
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun runTask(message: String, operation: () -> File) {
        status.text = message
        editingControls.forEach { it.isEnabled = false }
        cornerEditor.isEnabled = false
        Thread({
            val result = runCatching(operation)
            runOnUiThread {
                result.fold(
                    onSuccess = {
                        loadScan()
                        status.text = getString(R.string.derivative_created, it.name)
                    },
                    onFailure = { status.text = getString(R.string.processing_failed, it.message ?: it.javaClass.simpleName) },
                )
                if (result.isFailure) loadScan()
                status.announceForAccessibility(status.text)
            }
        }, "kirsch-derivative").start()
    }

    private fun showFeatureStatus() {
        val message = FeatureCatalog.features.joinToString("\n\n") { feature ->
            if (feature.availability == FeatureAvailability.AVAILABLE) {
                "${feature.label}: available"
            } else {
                "${feature.label}: unavailable\n${feature.reason}"
            }
        }
        AlertDialog.Builder(this).setTitle(R.string.feature_status).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
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
