package ch.lkmc.kirsch

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import ch.lkmc.kirsch.derivative.DerivativeStore
import ch.lkmc.kirsch.scan.ScanGalleryExporter
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    fun testRotationInFlightSurvivesRecreationAndReloadsCommittedPreview() {
        val manifest = createScan("review")
        openScan(manifest)
        val started = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val completed = CountDownLatch(1)
        try {
            startDelayedOperation(started, allowCompletion, completed) {
                DerivativeStore.createRotation(manifest).file
            }
            assertTrue("The operation should start", started.await(5, TimeUnit.SECONDS))
            recreateReview()
            var editsDisabled = false
            instrumentation.runOnMainSync {
                val buttons = descendants(requireNotNull(activity).window.decorView).filterIsInstance<Button>()
                editsDisabled = buttons.all { !it.isEnabled }
            }
            assertTrue("Recreation must keep edits and saving disabled while rotation is running", editsDisabled)
            allowCompletion.countDown()
            awaitLoaded(480, 640)
            instrumentation.runOnMainSync {
                val save = descendants(requireNotNull(activity).window.decorView).filterIsInstance<Button>()
                    .single { it.text == instrumentation.targetContext.getString(R.string.accept_scan) }
                assertTrue("The committed rotated preview should return to review", save.isEnabled)
            }
            assertEquals(1, JSONObject(manifest.readText()).getInt("output_rotation_quarter_turns"))
        } finally {
            allowCompletion.countDown()
            assertTrue("The delayed operation should finish", completed.await(10, TimeUnit.SECONDS))
        }
    }

    fun testFailedCorrectionInFlightKeepsDraftAcrossRecreation() {
        val manifest = createScan("review")
        openScan(manifest)
        val draft = listOf(Point(0.1, 0.1), Point(0.1, 0.1), Point(0.9, 0.9), Point(0.1, 0.9))
        instrumentation.runOnMainSync {
            val editor = requireNotNull(findView(requireNotNull(activity).window.decorView, CornerEditorView::class.java))
            editor.setNormalizedPoints(draft)
            editor.onCornersChanged?.invoke()
        }
        val started = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val completed = CountDownLatch(1)
        try {
            startDelayedOperation(started, allowCompletion, completed) {
                DerivativeStore.createManualRectification(manifest, draft).file
            }
            assertTrue("The operation should start", started.await(5, TimeUnit.SECONDS))
            recreateReview()
            var editsDisabled = false
            instrumentation.runOnMainSync {
                editsDisabled = descendants(requireNotNull(activity).window.decorView).filterIsInstance<Button>()
                    .all { !it.isEnabled }
            }
            assertTrue("Recreation must retain the running correction", editsDisabled)
            allowCompletion.countDown()
            awaitMessage("Print corners must be distinct")
            instrumentation.runOnMainSync {
                val root = requireNotNull(activity).window.decorView
                val editor = requireNotNull(findView(root, CornerEditorView::class.java))
                assertEquals(draft, editor.normalizedPoints())
                assertTrue(editor.isEnabled)
                val save = descendants(root).filterIsInstance<Button>()
                    .single { it.text == instrumentation.targetContext.getString(R.string.accept_scan) }
                assertFalse("Failed correction must preserve the unsaved draft", save.isEnabled)
            }
            assertFalse(JSONObject(manifest.readText()).has("manual_quad"))
        } finally {
            allowCompletion.countDown()
            assertTrue("The delayed operation should finish", completed.await(10, TimeUnit.SECONDS))
        }
    }

    fun testAcceptedOperationInFlightReloadsTheSavedVersionAfterRecreation() {
        val manifest = createScan("review")
        openScan(manifest)
        val started = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val completed = CountDownLatch(1)
        try {
            startDelayedOperation(started, allowCompletion, completed) {
                // Exercise the actual acceptance commit and selected export
                // source without depending on MediaStore transfer timing.
                DerivativeStore.accept(manifest, "content://media/external/images/media/1", "saved.jpg")
                manifest
            }
            assertTrue("The operation should start", started.await(5, TimeUnit.SECONDS))
            recreateReview()
            allowCompletion.countDown()
            awaitMessage("scan.json")
            assertPreviewColor(Color.GREEN)
            instrumentation.runOnMainSync {
                val root = requireNotNull(activity).window.decorView
                assertTrue("Accepted scans must stay immutable", descendants(root).filterIsInstance<Button>()
                    .all { !it.isEnabled })
                assertFalse(requireNotNull(findView(root, CornerEditorView::class.java)).isEnabled)
            }
            assertEquals("accepted", JSONObject(manifest.readText()).getString("state"))
        } finally {
            allowCompletion.countDown()
            assertTrue("The delayed operation should finish", completed.await(10, TimeUnit.SECONDS))
        }
    }

    fun testGalleryExportAcceptsTheSelectedVersionWithoutChangingItsSource() {
        val manifest = createScan("review")
        val source = File(directory, "saved.jpg")
        val image = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.GREEN)
        source.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 96, it)) }
        image.recycle()
        val original = source.readBytes()
        val context = instrumentation.targetContext.applicationContext
        var galleryUri: Uri? = null
        try {
            ScanGalleryExporter(context).save(manifest, "saved.jpg")
            val record = JSONObject(manifest.readText())
            galleryUri = Uri.parse(record.getJSONObject("extensions").getString("gallery_uri"))
            assertEquals("accepted", record.getString("state"))
            assertEquals("saved.jpg", record.getString("preview_path"))
            assertEquals("saved.jpg", record.getJSONObject("extensions").getString("gallery_source_path"))
            assertTrue("Gallery metadata must not change the retained derivative", original.contentEquals(source.readBytes()))
            val exported = requireNotNull(context.contentResolver.openInputStream(galleryUri)).use { it.readBytes() }
            assertTrue(exported.isNotEmpty())
            val exif = ExifInterface(ByteArrayInputStream(exported))
            assertEquals("test-scan", exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        } finally {
            galleryUri?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    fun testReturningToExistingReviewReloadsTheVersionSavedByAnotherInstance() {
        val manifest = createScan("review")
        openScan(manifest)
        val previous = requireNotNull(activity)
        val monitor = instrumentation.addMonitor(ReviewActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { previous.startActivity(ReviewActivity.intent(previous, manifest)) }
        activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000))
        instrumentation.removeMonitor(monitor)
        val current = requireNotNull(activity)
        var galleryUri: Uri? = null
        try {
            awaitLoaded()
            instrumentation.runOnMainSync {
                descendants(current.window.decorView).filterIsInstance<Button>()
                    .single { it.text == instrumentation.targetContext.getString(R.string.rotate_scan) }
                    .performClick()
            }
            awaitLoaded(480, 640)
            val selected = JSONObject(manifest.readText()).getString("preview_path")
            ScanGalleryExporter(instrumentation.targetContext.applicationContext).save(manifest, selected)
            galleryUri = Uri.parse(JSONObject(manifest.readText()).getJSONObject("extensions").getString("gallery_uri"))
            instrumentation.runOnMainSync { current.finish() }
            activity = previous
            awaitLoaded(480, 640)
            var locked = false
            instrumentation.runOnMainSync {
                locked = descendants(previous.window.decorView).filterIsInstance<Button>().all { !it.isEnabled } &&
                    !requireNotNull(findView(previous.window.decorView, CornerEditorView::class.java)).isEnabled
            }
            assertTrue("Returning to an existing screen must show the accepted state", locked)
        } finally {
            instrumentation.runOnMainSync { current.finish() }
            activity = previous
            galleryUri?.let { instrumentation.targetContext.contentResolver.delete(it, null, null) }
        }
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

    private fun recreateReview() {
        val monitor = instrumentation.addMonitor(ReviewActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { requireNotNull(activity).recreate() }
        activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000))
        instrumentation.removeMonitor(monitor)
        awaitLoaded()
    }

    private fun awaitMessage(message: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var visible = false
        while (!visible && System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                visible = descendants(requireNotNull(activity).window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString().contains(message) }
            }
            if (!visible) Thread.sleep(50)
        }
        assertTrue("Review should display the completed operation: $message", visible)
    }

    private fun startDelayedOperation(
        started: CountDownLatch,
        allowCompletion: CountDownLatch,
        completed: CountDownLatch,
        operation: () -> File,
    ) {
        instrumentation.runOnMainSync {
            // Pause the real derivative operation before its manifest commit,
            // making the recreation ordering deterministic without slowing it
            // by image size or adding a production test hook.
            val task = ReviewActivity::class.java.declaredMethods.single { it.name == "runTask" }
            task.isAccessible = true
            val reset = task.parameterTypes[1].enumConstants!!.single { it.toString() == "RESET" }
            val delayed = {
                started.countDown()
                try {
                    check(allowCompletion.await(10, TimeUnit.SECONDS))
                    operation()
                } finally {
                    completed.countDown()
                }
            }
            task.invoke(requireNotNull(activity), "Running delayed review operation", reset, delayed)
        }
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
