package ch.lkmc.kirsch

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import ch.lkmc.kirsch.capture.CapturePackageZipper
import ch.lkmc.kirsch.capture.CaptureProfile
import ch.lkmc.kirsch.derivative.FeatureAvailability
import ch.lkmc.kirsch.derivative.FeatureCatalog
import ch.lkmc.kirsch.scan.ScanManifestStore
import ch.lkmc.kirsch.scan.ScanProcessor
import java.io.File
import org.json.JSONObject

/**
 * Debug, benchmark, and data controls, kept out of the scanning UI: capture
 * mode override, benchmark print ID, package export, and the capability
 * status list.
 */
class SettingsActivity : Activity() {
    companion object {
        private const val EXPORT_DOCUMENT_REQUEST = 101
        private const val STATE_PENDING_EXPORT = "pendingExport"

        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }

    private lateinit var statusView: TextView
    private var pendingExport: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The SAF picker can outlive this Activity instance (rotation,
        // process death); the selected packages must survive recreation or
        // onActivityResult lands on an instance with nothing to export.
        pendingExport = savedInstanceState?.getStringArrayList(STATE_PENDING_EXPORT)
            ?.map(::File)
            ?: emptyList()
        EdgeToEdge.apply(window)
        buildUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            STATE_PENDING_EXPORT,
            ArrayList(pendingExport.map(File::getAbsolutePath)),
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_DOCUMENT_REQUEST) return
        val destination = data?.data
        val sources = pendingExport
        pendingExport = emptyList()
        if (resultCode != RESULT_OK || destination == null) return
        if (sources.isEmpty()) {
            status(getString(R.string.export_failed, "export selection was lost"))
            return
        }
        status(getString(R.string.export_running))
        Thread({
            val result = runCatching {
                contentResolver.openOutputStream(destination)?.use { output ->
                    val zipSources = ScanManifestStore.locked {
                        sources.map { directory ->
                            val prefix = if (directory.parentFile?.name == "product") "scans" else "acquisitions"
                            val manifest = File(directory, "scan.json")
                            val manifestBytes = manifest.takeIf(File::isFile)?.readBytes()
                            val files = if (manifestBytes != null) {
                                val record = JSONObject(manifestBytes.decodeToString())
                                buildSet {
                                    add("scan.json")
                                    listOf("working_image_path", "processing_report").forEach { field ->
                                        record.optString(field).takeIf(String::isNotBlank)?.let(::add)
                                    }
                                    val derivatives = record.optJSONArray("derivatives")
                                    if (derivatives != null) {
                                        for (index in 0 until derivatives.length()) {
                                            add(derivatives.getJSONObject(index).getString("path"))
                                        }
                                    }
                                }.map { relative -> File(directory, relative) }.filter(File::isFile)
                            } else {
                                directory.walkTopDown()
                                    .filter {
                                        it.isFile && !it.name.endsWith(".partial") && !it.name.contains(".partial.")
                                    }
                                    .toList()
                            }
                            CapturePackageZipper.Source(
                                "$prefix/${directory.name}",
                                directory,
                                includedFiles = files,
                                contentOverrides = if (manifestBytes != null) {
                                    mapOf("scan.json" to manifestBytes)
                                } else {
                                    emptyMap()
                                },
                            )
                        }
                    }
                    CapturePackageZipper.zipNamed(zipSources, output)
                } ?: error("Could not open the selected export destination")
            }
            runOnUiThread {
                // The export intentionally finishes even if the Activity is
                // recreated mid-write; only the status update is dropped.
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        status(
                            getString(
                                R.string.export_done,
                                it.entryCount,
                                it.byteCount / (1024.0 * 1024.0),
                            ),
                        )
                    },
                    onFailure = {
                        status(getString(R.string.export_failed, it.message ?: it.javaClass.simpleName))
                    },
                )
            }
        }, "capture-export").start()
    }

    private fun exportablePackages(): List<File> {
        val root = getExternalFilesDir("captures") ?: File(filesDir, "captures")
        val captures = root.listFiles { file: File -> file.isDirectory && File(file, "capture.json").exists() }
            .orEmpty().toList()
        val scans = ScanProcessor(this).scanRoot()
            .listFiles { file: File ->
                if (!file.isDirectory || !File(file, "scan.json").exists()) return@listFiles false
                runCatching {
                    JSONObject(File(file, "scan.json").readText()).optString("state") in setOf("review", "accepted")
                }.getOrDefault(false)
            }
            .orEmpty().toList()
        return (captures + scans).sortedByDescending(File::getName)
    }

    private fun showExportDialog() {
        val packages = exportablePackages()
        if (packages.isEmpty()) {
            status(getString(R.string.export_nothing))
            return
        }
        val labels = buildList {
            add(getString(R.string.export_all, packages.size))
            addAll(
                packages.map { packageDirectory ->
                    val kind = if (packageDirectory.parentFile?.name == "product") "scan" else "acquisition"
                    "$kind · ${packageDirectory.name}"
                },
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.export_title)
            .setItems(labels.toTypedArray()) { _, index ->
                pendingExport = if (index == 0) {
                    packages
                } else {
                    val selected = packages[index - 1]
                    if (selected.parentFile?.name == "product") {
                        val acquisitionRoot = getExternalFilesDir("captures") ?: File(filesDir, "captures")
                        listOfNotNull(selected, File(acquisitionRoot, selected.name).takeIf(File::isDirectory))
                    } else {
                        listOf(selected)
                    }
                }
                val suggestedName = if (index == 0) {
                    "kirsch-captures-${packages.first().name}.zip"
                } else {
                    "${packages[index - 1].name}.zip"
                }
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, suggestedName)
                }
                startActivityForResult(intent, EXPORT_DOCUMENT_REQUEST)
            }
            .show()
    }

    private fun showFeatureStatus() {
        val message = FeatureCatalog.features.joinToString("\n\n") { feature ->
            if (feature.availability == FeatureAvailability.AVAILABLE) {
                "${feature.label}: available"
            } else {
                "${feature.label}: unavailable\n${feature.reason}"
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.feature_status)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun status(message: String) {
        statusView.text = message
        statusView.announceForAccessibility(message)
    }

    private fun buildUi() {
        val preferences = MainActivity.preferences(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        content.addView(
            TextView(this).apply {
                setText(R.string.settings_title)
                setTextColor(0xFFF3EDE2.toInt())
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )

        content.addView(sectionHeader(R.string.settings_capture_section))
        val profiles = CaptureProfile.entries
        val profileSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                profiles.map(CaptureProfile::displayName),
            )
            setSelection(profiles.indexOf(MainActivity.selectedProfile(preferences)))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    preferences.edit()
                        .putString(MainActivity.PREF_PROFILE, profiles[position].name)
                        .apply()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        content.addView(profileSpinner)
        content.addView(caption(R.string.settings_capture_caption))

        content.addView(sectionHeader(R.string.settings_benchmark_section))
        val printIdField = EditText(this).apply {
            hint = getString(R.string.settings_print_id_hint)
            setText(preferences.getString(MainActivity.PREF_PRINT_ID, ""))
            setTextColor(0xFFF3EDE2.toInt())
            setHintTextColor(0xFFAAA399.toInt())
            isSingleLine = true
        }
        printIdField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                preferences.edit()
                    .putString(MainActivity.PREF_PRINT_ID, s?.toString()?.trim().orEmpty())
                    .apply()
            }
        })
        content.addView(printIdField)
        content.addView(caption(R.string.settings_print_id_caption))

        content.addView(sectionHeader(R.string.settings_data_section))
        content.addView(
            Button(this).apply {
                setText(R.string.export_captures)
                setOnClickListener { showExportDialog() }
            },
        )
        content.addView(caption(R.string.settings_export_caption))
        content.addView(
            Button(this).apply {
                setText(R.string.feature_status)
                setOnClickListener { showFeatureStatus() }
            },
        )
        content.addView(caption(R.string.settings_capabilities_caption))

        statusView = TextView(this).apply {
            setTextColor(0xFFD7CFC3.toInt())
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(statusView)

        content.addView(
            TextView(this).apply {
                setText(R.string.settings_privacy_note)
                setTextColor(0xFF8F887D.toInt())
                textSize = 12f
                setPadding(0, dp(20), 0, 0)
            },
        )

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
                setOnApplyWindowInsetsListener { _, insets ->
                    val bars = EdgeToEdge.systemBarInsets(insets)
                    content.setPadding(
                        dp(20) + bars.left,
                        dp(16) + bars.top,
                        dp(20) + bars.right,
                        dp(28) + bars.bottom,
                    )
                    insets
                }
            },
        )
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
        setPadding(0, dp(4), 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
