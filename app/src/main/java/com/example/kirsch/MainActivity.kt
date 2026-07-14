package com.example.kirsch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.example.kirsch.capture.Camera2BurstController
import com.example.kirsch.capture.CapturePackageZipper
import com.example.kirsch.capture.CaptureProfile
import com.example.kirsch.scan.ScanProcessor
import com.example.kirsch.scan.ScanQueue
import com.example.kirsch.scan.ScanManifestStore
import java.io.File
import org.json.JSONObject

class MainActivity : Activity(), Camera2BurstController.Listener, ScanQueue.Listener {
    private companion object {
        const val CAMERA_PERMISSION_REQUEST = 100
        const val EXPORT_DOCUMENT_REQUEST = 101
        const val STATE_PENDING_EXPORT = "pendingExport"
    }

    private lateinit var textureView: TextureView
    private lateinit var printId: EditText
    private lateinit var modeSpinner: Spinner
    private lateinit var captureButton: Button
    private lateinit var exportButton: Button
    private lateinit var reviewButton: Button
    private lateinit var statusView: TextView
    private lateinit var controller: Camera2BurstController
    private var resumed = false
    private var pendingExport: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The SAF picker can outlive this Activity instance (rotation,
        // process death); the selected packages must survive recreation or
        // onActivityResult lands on an instance with nothing to export.
        pendingExport = savedInstanceState?.getStringArrayList(STATE_PENDING_EXPORT)
            ?.map(::File)
            ?: emptyList()
        buildUi()
        controller = Camera2BurstController(this, textureView, this)
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                startCameraIfReady()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                startCameraIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                controller.stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        captureButton.setOnClickListener {
            controller.capture(printId.text.toString())
        }
        exportButton.setOnClickListener {
            showExportDialog()
        }
        reviewButton.setOnClickListener {
            showReviewDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startCameraIfReady()
        ScanQueue.resumePending(this, this)
    }

    override fun onPause() {
        resumed = false
        controller.stop()
        super.onPause()
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraIfReady()
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            onStatus("Camera permission is required")
        }
    }

    override fun onStatus(message: String) {
        runOnUiThread {
            statusView.text = message
        }
    }

    override fun onBusyChanged(busy: Boolean) {
        runOnUiThread {
            captureButton.isEnabled = !busy
            exportButton.isEnabled = !busy
            reviewButton.isEnabled = !busy
            modeSpinner.isEnabled = !busy
            printId.isEnabled = !busy
        }
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
            onStatus(getString(R.string.export_failed, "export selection was lost"))
            return
        }
        onStatus(getString(R.string.export_running))
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
                        onStatus(
                            getString(
                                R.string.export_done,
                                it.entryCount,
                                it.byteCount / (1024.0 * 1024.0),
                            ),
                        )
                    },
                    onFailure = {
                        onStatus(getString(R.string.export_failed, it.message ?: it.javaClass.simpleName))
                    },
                )
            }
        }, "capture-export").start()
    }

    private fun capturePackages(): List<File> {
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
        val packages = capturePackages()
        if (packages.isEmpty()) {
            onStatus(getString(R.string.export_nothing))
            return
        }
        val labels = buildList {
            add(getString(R.string.export_all, packages.size))
            addAll(packages.map { packageDirectory ->
                val kind = if (packageDirectory.parentFile?.name == "product") "scan" else "acquisition"
                "$kind · ${packageDirectory.name}"
            })
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

    override fun onCaptureFinished(manifestPath: String) {
        val manifest = File(manifestPath)
        val status = runCatching { JSONObject(manifest.readText()).getString("status") }.getOrNull()
        val mode = runCatching { JSONObject(manifest.readText()).getString("mode") }.getOrNull()
        if (status == "accepted" && mode == "yuv-420-888") {
            ScanQueue.enqueue(this, manifest, this)
        } else if (status == "accepted") {
            onStatus(getString(R.string.raw_acquisition_saved))
        } else {
            onStatus(getString(R.string.capture_not_processed, status ?: "invalid"))
        }
    }

    override fun onScanQueued(captureId: String) {
        onStatus(getString(R.string.scan_queued, captureId))
    }

    override fun onScanReady(result: ScanProcessor.Result) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            statusView.text = getString(R.string.scan_ready, result.manifest.parentFile?.name)
            reviewButton.isEnabled = true
            statusView.announceForAccessibility(statusView.text)
        }
    }

    override fun onScanFailed(captureId: String, error: Throwable) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            statusView.text = getString(R.string.scan_failed, captureId, error.message ?: error.javaClass.simpleName)
            statusView.announceForAccessibility(statusView.text)
        }
    }

    private fun startCameraIfReady() {
        if (!resumed || !textureView.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }
        controller.start(CaptureProfile.entries[modeSpinner.selectedItemPosition])
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF151412.toInt())
        }
        textureView = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(textureView)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            setText(R.string.capture_title)
            setTextColor(0xFFF3EDE2.toInt())
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        controls.addView(title)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        printId = EditText(this).apply {
            hint = "print-id"
            setText(R.string.unassigned_print_id)
            setTextColor(0xFFF3EDE2.toInt())
            setHintTextColor(0xFFAAA399.toInt())
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.capture_modes).toList(),
            )
        }
        row.addView(printId)
        row.addView(modeSpinner, LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT))
        controls.addView(row)

        captureButton = Button(this).apply {
            setText(R.string.capture_burst)
        }
        controls.addView(captureButton)

        exportButton = Button(this).apply {
            setText(R.string.export_captures)
        }
        controls.addView(exportButton)

        reviewButton = Button(this).apply {
            setText(R.string.review_scans)
        }
        controls.addView(reviewButton)

        statusView = TextView(this).apply {
            setText(R.string.waiting_for_camera)
            setTextColor(0xFFD7CFC3.toInt())
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        controls.addView(statusView)
        root.addView(controls)
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showReviewDialog() {
        val root = ScanProcessor(this).scanRoot()
        val scans = root.listFiles { directory -> directory.isDirectory && File(directory, "scan.json").isFile }
            ?.map { File(it, "scan.json") }
            ?.filter { manifest ->
                runCatching {
                    JSONObject(manifest.readText()).optString("state") in setOf("review", "accepted")
                }.getOrDefault(false)
            }
            ?.sortedByDescending { it.parentFile?.name }
            .orEmpty()
        if (scans.isEmpty()) {
            onStatus(getString(R.string.no_scans))
            return
        }
        val labels = scans.map { manifest ->
            val record = JSONObject(manifest.readText())
            "${record.getString("scan_id")} · ${record.getString("state")}"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.review_scans)
            .setItems(labels.toTypedArray()) { _, index -> startActivity(ReviewActivity.intent(this, scans[index])) }
            .show()
    }
}
