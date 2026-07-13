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
import com.example.kirsch.capture.CaptureMode
import com.example.kirsch.capture.CapturePackageZipper
import java.io.File

class MainActivity : Activity(), Camera2BurstController.Listener {
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
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startCameraIfReady()
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
                    CapturePackageZipper.zip(sources, output)
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
        return root.listFiles { file: File -> file.isDirectory && File(file, "capture.json").exists() }
            ?.sortedByDescending(File::getName)
            ?: emptyList()
    }

    private fun showExportDialog() {
        val packages = capturePackages()
        if (packages.isEmpty()) {
            onStatus(getString(R.string.export_nothing))
            return
        }
        val labels = buildList {
            add(getString(R.string.export_all, packages.size))
            addAll(packages.map(File::getName))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.export_title)
            .setItems(labels.toTypedArray()) { _, index ->
                pendingExport = if (index == 0) packages else listOf(packages[index - 1])
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
        runOnUiThread {
            statusView.text = getString(R.string.capture_package_path, manifestPath)
        }
    }

    private fun startCameraIfReady() {
        if (!resumed || !textureView.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }
        val mode = if (modeSpinner.selectedItemPosition == 0) CaptureMode.RAW else CaptureMode.YUV
        controller.start(mode)
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
}
