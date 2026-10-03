package ch.lkmc.kirsch

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Point

/** Exercises the actual decoded preview, not only the manifest selection rule. */
@Suppress("DEPRECATION")
class ReviewActivityTest : InstrumentationTestCase() {
    private var activity: Activity? = null
    private lateinit var directory: File

    override fun setUp() {
        super.setUp()
        directory = File(instrumentation.targetContext.cacheDir, "review-test-${System.nanoTime()}")
        check(directory.mkdirs())
    }

    override fun tearDown() {
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        instrumentation.waitForIdleSync()
        directory.deleteRecursively()
        super.tearDown()
    }

    fun testDisplaysFinishedScanRatherThanCornerEditingSource() {
        val manifest = createScan("review")
        openScan(manifest)

        assertPreviewColor(Color.RED)
        instrumentation.runOnMainSync {
            val editor = requireNotNull(findView(requireNotNull(activity).window.decorView, CornerEditorView::class.java))
            assertTrue(editor.isEnabled)
            val image = Bitmap.createBitmap(editor.width, editor.height, Bitmap.Config.ARGB_8888)
            editor.draw(Canvas(image))
            assertEquals(Color.BLUE, image.getPixel(editor.width / 2, editor.height / 2))
            image.recycle()
        }
    }

    fun testAcceptedScanDisplaysTheVersionActuallySaved() {
        val manifest = createScan("accepted")
        openScan(manifest)

        assertPreviewColor(Color.GREEN)
        instrumentation.runOnMainSync {
            val root = requireNotNull(activity).window.decorView
            val editor = requireNotNull(findView(root, CornerEditorView::class.java))
            assertFalse(editor.isEnabled)
            val save = descendants(root).filterIsInstance<Button>()
                .single { it.text == instrumentation.targetContext.getString(R.string.accept_scan) }
            assertFalse(save.isEnabled)
        }
    }

    fun testUncertainCropAndFusionAreExplainedBeforeSaving() {
        val manifest = createScan("review")
        val record = JSONObject(manifest.readText())
            .put("auto_crop_detected", false)
            .put("fusion_failure", "insufficient-registration")
        manifest.writeText(record.toString())
        openScan(manifest)

        instrumentation.runOnMainSync {
            val texts = descendants(requireNotNull(activity).window.decorView).filterIsInstance<TextView>()
                .map { it.text.toString() }
            assertTrue(texts.any { it.contains(instrumentation.targetContext.getString(R.string.review_uncropped_warning)) })
            assertTrue(texts.any { it.contains(instrumentation.targetContext.getString(R.string.review_fusion_warning)) })
        }
    }

    fun testUnappliedCornerChangesBlockSavingAndSurviveRecreation() {
        openScan(createScan("review"))
        var draftX = 0.0
        instrumentation.runOnMainSync {
            val root = requireNotNull(activity).window.decorView
            val editor = requireNotNull(findView(root, CornerEditorView::class.java))
            val render = Bitmap.createBitmap(editor.width, editor.height, Bitmap.Config.ARGB_8888)
            editor.draw(Canvas(render))
            render.recycle()
            val x = editor.width * 0.05f
            val y = editor.height * 0.05f
            val start = SystemClock.uptimeMillis()
            listOf(
                Triple(MotionEvent.ACTION_DOWN, x, y),
                Triple(MotionEvent.ACTION_MOVE, x + 40f, y + 20f),
                Triple(MotionEvent.ACTION_UP, x + 40f, y + 20f),
            ).forEach { (action, touchX, touchY) ->
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, touchX, touchY, 0)
                editor.onTouchEvent(event)
                event.recycle()
            }
            draftX = editor.normalizedPoints().first().x
            assertTrue(draftX > 0.05)
            val save = descendants(root).filterIsInstance<Button>()
                .single { it.text == instrumentation.targetContext.getString(R.string.accept_scan) }
            assertFalse(save.isEnabled)
        }

        val monitor = instrumentation.addMonitor(ReviewActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { requireNotNull(activity).recreate() }
        activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000))
        instrumentation.removeMonitor(monitor)
        awaitLoaded()
        instrumentation.runOnMainSync {
            val root = requireNotNull(activity).window.decorView
            val editor = requireNotNull(findView(root, CornerEditorView::class.java))
            assertEquals(draftX, editor.normalizedPoints().first().x, 0.000001)
            val save = descendants(root).filterIsInstance<Button>()
                .single { it.text == instrumentation.targetContext.getString(R.string.accept_scan) }
            assertFalse(save.isEnabled)
        }
    }

    fun testRotateUpdatesTheVisibleDeliverable() {
        openScan(createScan("review"))
        instrumentation.runOnMainSync {
            val rotate = descendants(requireNotNull(activity).window.decorView).filterIsInstance<Button>()
                .single { it.text == instrumentation.targetContext.getString(R.string.rotate_scan) }
            rotate.performClick()
            assertFalse(rotate.isEnabled)
        }

        awaitLoaded(480, 640)
        val record = JSONObject(File(directory, "scan.json").readText())
        assertEquals(1, record.getInt("output_rotation_quarter_turns"))
        assertEquals("review", record.getString("state"))
        assertTrue(File(directory, record.getString("preview_path")).isFile)
    }

    fun testSaveChooserPreventsConcurrentEditsAndCancelReturnsToReview() {
        openScan(createScan("review"))
        instrumentation.runOnMainSync {
            val save = descendants(requireNotNull(activity).window.decorView).filterIsInstance<Button>()
                .single { it.text == instrumentation.targetContext.getString(R.string.accept_scan) }
            save.performClick()
            assertFalse(save.isEnabled)
            save.performClick()
            assertTrue(descendants(requireNotNull(activity).window.decorView).filterIsInstance<Button>()
                .all { !it.isEnabled })
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        var chooserOpened = false
        while (!chooserOpened && System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            chooserOpened = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(instrumentation.targetContext.getString(R.string.save_version_title))
                ?.isNotEmpty() == true
            if (!chooserOpened) Thread.sleep(50)
        }
        assertTrue("Save should offer the stored versions", chooserOpened)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val save = descendants(requireNotNull(activity).window.decorView).filterIsInstance<Button>()
                .single { it.text == instrumentation.targetContext.getString(R.string.accept_scan) }
            assertTrue(save.isEnabled)
        }
        assertEquals("review", JSONObject(File(directory, "scan.json").readText()).getString("state"))
    }

    fun testApplyingReorderedCornersClearsTheDraftAndEnablesSaving() {
        openScan(createScan("review"))
        instrumentation.runOnMainSync {
            val root = requireNotNull(activity).window.decorView
            val editor = requireNotNull(findView(root, CornerEditorView::class.java))
            // Handles can exchange identities during correction. The processor
            // orders their boundary before it stores the applied result.
            editor.setNormalizedPoints(listOf(
                Point(0.95, 0.05), Point(0.05, 0.05), Point(0.95, 0.95), Point(0.05, 0.95),
            ))
            editor.onCornersChanged?.invoke()
            val save = descendants(root).filterIsInstance<Button>()
                .single { it.text == instrumentation.targetContext.getString(R.string.accept_scan) }
            assertFalse(save.isEnabled)
            descendants(root).filterIsInstance<Button>()
                .single { it.text == instrumentation.targetContext.getString(R.string.apply_manual_corners) }
                .performClick()
        }
        val deadline = System.nanoTime() + 10_000_000_000L
        var applied = false
        while (!applied && System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val apply = descendants(requireNotNull(activity).window.decorView).filterIsInstance<Button>()
                    .single { it.text == instrumentation.targetContext.getString(R.string.apply_manual_corners) }
                applied = apply.isEnabled && JSONObject(File(directory, "scan.json").readText()).has("manual_quad")
            }
            if (!applied) Thread.sleep(50)
        }
        assertTrue("Corner correction should finish", applied)
        instrumentation.runOnMainSync {
            val root = requireNotNull(activity).window.decorView
            val save = descendants(root).filterIsInstance<Button>()
                .single { it.text == instrumentation.targetContext.getString(R.string.accept_scan) }
            assertTrue("Successfully applied corners should enable saving", save.isEnabled)
            val editor = requireNotNull(findView(root, CornerEditorView::class.java))
            assertEquals(0.05, editor.normalizedPoints().first().x, 0.000001)
        }
    }

    fun testFailedCornerApplicationKeepsTheDraftForCorrection() {
        openScan(createScan("review"))
        val draft = listOf(Point(0.1, 0.1), Point(0.1, 0.1), Point(0.9, 0.9), Point(0.1, 0.9))
        instrumentation.runOnMainSync {
            val root = requireNotNull(activity).window.decorView
            val editor = requireNotNull(findView(root, CornerEditorView::class.java))
            editor.setNormalizedPoints(draft)
            editor.onCornersChanged?.invoke()
            descendants(root).filterIsInstance<Button>()
                .single { it.text == instrumentation.targetContext.getString(R.string.apply_manual_corners) }
                .performClick()
        }
        val deadline = System.nanoTime() + 10_000_000_000L
        var completed = false
        while (!completed && System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                completed = descendants(requireNotNull(activity).window.decorView).filterIsInstance<Button>()
                    .single { it.text == instrumentation.targetContext.getString(R.string.apply_manual_corners) }.isEnabled
            }
            if (!completed) Thread.sleep(50)
        }
        assertTrue("Rejected correction should return to review", completed)
        var messages = emptyList<String>()
        instrumentation.runOnMainSync {
            val root = requireNotNull(activity).window.decorView
            val editor = requireNotNull(findView(root, CornerEditorView::class.java))
            assertEquals(draft, editor.normalizedPoints())
            val save = descendants(root).filterIsInstance<Button>()
                .single { it.text == instrumentation.targetContext.getString(R.string.accept_scan) }
            assertFalse(save.isEnabled)
            messages = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }
        }
        assertTrue("The rejected corner reason should remain visible", messages.any { it.contains("Print corners must be distinct") })
        assertTrue("The draft should explain how to apply a correction", messages.any {
            it.contains(instrumentation.targetContext.getString(R.string.unapplied_corners))
        })
        assertFalse(JSONObject(File(directory, "scan.json").readText()).has("manual_quad"))
    }

    private fun createScan(state: String): File {
        writeImage("working.jpg", Color.BLUE)
        writeImage("active.jpg", Color.RED)
        writeImage("saved.jpg", Color.GREEN)
        val record = JSONObject()
            .put("scan_id", "test-scan")
            .put("state", state)
            .put("working_image_path", "working.jpg")
            .put("preview_path", "active.jpg")
            .put("selected_quad", JSONObject().put("normalized_points", JSONArray(listOf(
                JSONArray(listOf(0.05, 0.05)), JSONArray(listOf(0.95, 0.05)),
                JSONArray(listOf(0.95, 0.95)), JSONArray(listOf(0.05, 0.95)),
            ))))
            .put("derivatives", JSONArray(listOf(
                JSONObject().put("path", "active.jpg").put("kind", "acquisition-master").put("media_type", "image/jpeg"),
                JSONObject().put("path", "saved.jpg").put("kind", "acquisition-master").put("media_type", "image/jpeg"),
            )))
        if (state == "accepted") record.put("extensions", JSONObject()
            .put("gallery_uri", "content://media/external/images/media/1")
            .put("gallery_source_path", "saved.jpg"))
        return File(directory, "scan.json").apply { writeText(record.toString()) }
    }

    private fun writeImage(name: String, color: Int) {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun openScan(manifest: File) {
        activity = instrumentation.startActivitySync(
            ReviewActivity.intent(instrumentation.targetContext, manifest).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        awaitLoaded()
    }

    private fun awaitLoaded(pixelWidth: Int = 640, pixelHeight: Int = 480) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var loaded = false
        while (!loaded && System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                loaded = descendants(requireNotNull(activity).window.decorView).filterIsInstance<TextView>()
                    .any { it.text == instrumentation.targetContext.getString(R.string.review_preview_dimensions, pixelWidth, pixelHeight) }
            }
            if (!loaded) Thread.sleep(50)
        }
        assertTrue("The finished scan should load", loaded)
    }

    private fun assertPreviewColor(expected: Int) {
        instrumentation.runOnMainSync {
            val preview = requireNotNull(findView(requireNotNull(activity).window.decorView, ScanPreviewView::class.java))
            val image = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
            preview.draw(Canvas(image))
            assertEquals(expected, image.getPixel(preview.width / 2, preview.height / 2))
            image.recycle()
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun <T : View> findView(root: View, type: Class<T>): T? =
        descendants(root).firstOrNull(type::isInstance)?.let(type::cast)
}
