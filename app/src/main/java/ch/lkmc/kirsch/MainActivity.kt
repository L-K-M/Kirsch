package ch.lkmc.kirsch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import ch.lkmc.kirsch.capture.Camera2BurstController
import ch.lkmc.kirsch.capture.CaptureProfile
import ch.lkmc.kirsch.scan.ScanProcessor
import ch.lkmc.kirsch.scan.ScanQueue
import java.io.File
import org.json.JSONObject

class MainActivity : Activity(), Camera2BurstController.Listener, ScanQueue.Listener {
    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
        const val PREFS_NAME = "kirsch-settings"
        const val PREF_PROFILE = "capture_profile"
        const val PREF_PRINT_ID = "print_id"

        fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun selectedProfile(preferences: SharedPreferences): CaptureProfile {
            val name = preferences.getString(PREF_PROFILE, CaptureProfile.SWEEP.name)
            return CaptureProfile.entries.firstOrNull { it.name == name } ?: CaptureProfile.SWEEP
        }
    }

    private lateinit var textureView: TextureView
    private lateinit var overlay: SweepOverlayView
    private lateinit var statusChip: TextView
    private lateinit var shutterButton: View
    private lateinit var libraryButton: TextView
    private lateinit var settingsButton: TextView
    private lateinit var controller: Camera2BurstController
    private var resumed = false
    private var capturing = false
    private var previewWidth = 0
    private var previewHeight = 0
    private var pendingReviewScanId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        controller = Camera2BurstController(this, textureView, this)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                startCameraIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                configureTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                controller.stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        shutterButton.setOnClickListener { startScan() }
        libraryButton.setOnClickListener { showLibraryDialog() }
        settingsButton.setOnClickListener {
            startActivity(SettingsActivity.intent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // A scan that finished while this screen was paused (Settings open,
        // app backgrounded) still deserves its automatic review hand-off.
        val pendingScanId = pendingReviewScanId
        if (pendingScanId != null) {
            val manifest = File(File(ScanProcessor(this).scanRoot(), pendingScanId), "scan.json")
            val ready = manifest.isFile && runCatching {
                JSONObject(manifest.readText()).optString("state") in setOf("review", "accepted")
            }.getOrDefault(false)
            if (ready) {
                pendingReviewScanId = null
                startActivity(ReviewActivity.intent(this, manifest))
                return
            }
        }
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
            showStatus(getString(R.string.camera_permission_needed))
        }
    }

    private fun startScan() {
        val printId = preferences(this).getString(PREF_PRINT_ID, null)
            ?.takeIf(String::isNotBlank)
            ?: getString(R.string.unassigned_print_id)
        pendingReviewScanId = null
        statusChip.visibility = View.GONE
        controller.capture(printId)
    }

    // Camera2BurstController.Listener

    override fun onStatus(message: String) {
        showStatus(message)
    }

    override fun onBusyChanged(busy: Boolean) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            capturing = busy
            shutterButton.isEnabled = !busy
            shutterButton.alpha = if (busy) 0.4f else 1f
            libraryButton.isEnabled = !busy
            settingsButton.isEnabled = !busy
            if (!busy) overlay.showFraming(getString(R.string.framing_hint))
        }
    }

    override fun onSweepProgress(progress: Float, keptFrames: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed || !capturing) return@runOnUiThread
            overlay.showSweeping(getString(R.string.sweep_hint))
            overlay.setSweepProgress(progress)
        }
    }

    override fun onPreviewConfigured(width: Int, height: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            previewWidth = width
            previewHeight = height
            configureTransform()
        }
    }

    override fun onCaptureFinished(manifestPath: String) {
        val manifest = File(manifestPath)
        val record = runCatching { JSONObject(manifest.readText()) }.getOrNull()
        val status = record?.optString("status")
        val mode = record?.optString("mode")
        if (status == "accepted" && mode == "yuv-420-888") {
            pendingReviewScanId = manifest.parentFile?.name
            ScanQueue.enqueue(this, manifest, this)
        } else if (status == "accepted") {
            showStatus(getString(R.string.raw_acquisition_saved))
        } else {
            showStatus(getString(R.string.capture_not_processed, status ?: "invalid"))
        }
    }

    // ScanQueue.Listener

    override fun onScanQueued(captureId: String) {
        showStatus(getString(R.string.scan_queued))
    }

    override fun onScanReady(result: ScanProcessor.Result) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val scanId = result.manifest.parentFile?.name
            statusChip.text = getString(R.string.scan_ready)
            statusChip.visibility = View.VISIBLE
            statusChip.announceForAccessibility(statusChip.text)
            if (resumed && scanId != null && scanId == pendingReviewScanId) {
                pendingReviewScanId = null
                startActivity(ReviewActivity.intent(this, result.manifest))
            }
        }
    }

    override fun onScanFailed(captureId: String, error: Throwable) {
        showStatus(getString(R.string.scan_failed, error.message ?: error.javaClass.simpleName))
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            statusChip.text = message
            statusChip.visibility = View.VISIBLE
        }
    }

    private fun startCameraIfReady() {
        if (!resumed || !textureView.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }
        overlay.showFraming(getString(R.string.framing_hint))
        controller.start(selectedProfile(preferences(this)))
    }

    /**
     * The activity is locked to portrait and the HAL pre-rotates preview
     * buffers for TextureView, so only the aspect ratio needs correcting:
     * scale the stretched content back to its true aspect and center-crop.
     */
    private fun configureTransform() {
        if (previewWidth == 0 || previewHeight == 0) return
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return
        val contentWidth = minOf(previewWidth, previewHeight).toFloat()
        val contentHeight = maxOf(previewWidth, previewHeight).toFloat()
        val scale = maxOf(viewWidth / contentWidth, viewHeight / contentHeight)
        val matrix = Matrix()
        matrix.setScale(
            contentWidth * scale / viewWidth,
            contentHeight * scale / viewHeight,
            viewWidth / 2f,
            viewHeight / 2f,
        )
        textureView.setTransform(matrix)
    }

    private fun showLibraryDialog() {
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
            showStatus(getString(R.string.no_scans))
            return
        }
        val labels = scans.map { manifest ->
            val record = JSONObject(manifest.readText())
            val state = if (record.optString("state") == "accepted") {
                getString(R.string.scan_state_accepted)
            } else {
                getString(R.string.scan_state_review)
            }
            "${record.getString("scan_id")} · $state"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.library_title)
            .setItems(labels.toTypedArray()) { _, index ->
                startActivity(ReviewActivity.intent(this, scans[index]))
            }
            .show()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        textureView = TextureView(this)
        root.addView(
            textureView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay = SweepOverlayView(this)
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        statusChip = TextView(this).apply {
            setTextColor(0xFFF3EDE2.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            background = pill(0xB3151412.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(
            statusChip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(56) },
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(36))
        }
        libraryButton = pillButton(getString(R.string.library_button))
        settingsButton = pillButton(getString(R.string.settings_button))
        shutterButton = View(this).apply {
            background = shutterDrawable()
            contentDescription = getString(R.string.shutter_description)
            isClickable = true
            isFocusable = true
        }
        controls.addView(
            libraryButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(shutterButton, LinearLayout.LayoutParams(dp(80), dp(80)))
        controls.addView(
            settingsButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        val title = TextView(this).apply {
            setText(R.string.app_name)
            setTextColor(0xCCF3EDE2.toInt())
            textSize = 15f
            letterSpacing = 0.25f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(
            title,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                topMargin = dp(18)
                leftMargin = dp(20)
            },
        )
        setContentView(root)
    }

    private fun pillButton(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(0xFFF3EDE2.toInt())
        textSize = 14f
        gravity = Gravity.CENTER
        background = pill(0xB3151412.toInt())
        setPadding(dp(18), dp(10), dp(18), dp(10))
        isClickable = true
        isFocusable = true
    }

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(22).toFloat()
        setColor(color)
    }

    private fun shutterDrawable(): LayerDrawable {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x00000000)
            setStroke(dp(4), 0xFFF3EDE2.toInt())
        }
        val core = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFF3EDE2.toInt())
        }
        return LayerDrawable(arrayOf(ring, core)).apply {
            val inset = dp(10)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
